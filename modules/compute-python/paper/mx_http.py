"""妙想网关统一 HTTP 层：POST、超时、查询类有限重试、原始 JSON 脱敏落盘。

密钥只走环境变量/配置对象，绝不写入日志、异常信息或落盘文件。
"""
from __future__ import annotations

import hashlib
import json
import time
from pathlib import Path

import requests

from .config import PaperConfig
from .mx_result import MxErrorKind, MxResult

_TIMEOUT_SEC = 30
_RETRY_BACKOFF_SEC = (1, 2, 4)  # 指数退避


def sanitize_payload(payload: dict) -> dict:
    """脱敏：删除 uid/accUID 等身份字段，其余保留供 parser 与审计。"""
    cleaned = json.loads(json.dumps(payload, ensure_ascii=False, default=str))
    data = cleaned.get("data")
    if isinstance(data, dict):
        for key in ("uid", "accUID"):
            data.pop(key, None)
    return cleaned


def raw_hash(payload: dict) -> str:
    """原始 JSON 哈希（quote 追溯用）。"""
    return hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str).encode("utf-8")
    ).hexdigest()[:16]


class MxHttpClient:
    """妙想网关客户端：query 类可重试；trade/cancel 一律不自动重试。"""

    def __init__(self, config: PaperConfig, artifact_dir: Path | None = None):
        self._config = config
        self._artifact_dir = artifact_dir
        self._session = requests.Session()

    def _endpoint_url(self, endpoint: str) -> str:
        return f"{self._config.api_base}{endpoint}"

    def _save_raw(self, run_id: str, endpoint: str, payload: dict) -> str:
        """按 run_id/endpoint 落盘脱敏原文；返回相对路径（未配置目录则空）。"""
        if self._artifact_dir is None:
            return ""
        safe = endpoint.strip("/").replace("/", "_")
        path = self._artifact_dir / "raw" / f"{run_id}_{safe}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(sanitize_payload(payload), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return str(path)

    def post(
        self,
        endpoint: str,
        body: dict,
        *,
        retryable: bool = False,
        run_id: str = "adhoc",
    ) -> MxResult:
        """POST 请求；retryable=True 时对网关错误做指数退避重试（≤PAPER_MAX_RETRY）。

        trade/cancel 必须传 retryable=False：超时先查 orders，禁止立即重发。
        """
        url = self._endpoint_url(endpoint)
        headers = {"apikey": self._config.api_key, "Content-Type": "application/json"}
        attempts = self._config.max_retry if retryable else 1
        last: MxResult | None = None
        for attempt in range(attempts):
            try:
                resp = self._session.post(url, headers=headers, json=body, timeout=_TIMEOUT_SEC)
                payload = resp.json()
            except (requests.RequestException, ValueError) as exc:
                # ValueError: JSON 解析失败；异常文本不包含密钥
                last = MxResult(
                    endpoint=endpoint,
                    ok=False,
                    code="HTTP_ERROR",
                    message=f"请求或解析失败: {type(exc).__name__}",
                    kind=MxErrorKind.HTTP_ERROR if retryable else MxErrorKind.PARSE_ERROR,
                    retry_count=attempt,
                )
            else:
                last = MxResult.from_json(endpoint, payload, retry_count=attempt)
                last.raw_path = self._save_raw(run_id, endpoint, payload)
                if last.ok or not last.retryable() or not retryable:
                    return last
            if attempt < attempts - 1 and retryable:
                time.sleep(_RETRY_BACKOFF_SEC[min(attempt, len(_RETRY_BACKOFF_SEC) - 1)])
        assert last is not None
        return last

    def close(self) -> None:
        """关闭底层连接。"""
        self._session.close()
