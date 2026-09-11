"""妙想网关统一 HTTP 层：POST、超时、查询类有限重试、原始 JSON 脱敏落盘。

密钥只走环境变量/配置对象，绝不写入日志、异常信息或落盘文件。
P1-09：统一 HTTP 状态码检查（4xx/5xx/超时/解析错误分类），仅查询类可有限重试；
P1-10：递归脱敏 uid/accUID/accID/accName 等账户标识（任意层级）；
P1-04：raw 文件名带单调 call_seq，同一 endpoint 多次调用不覆盖；
P2-01：哈希使用完整 SHA-256。
"""
from __future__ import annotations

import hashlib
import json
import time
from threading import Lock
from pathlib import Path

import requests

from .config import PaperConfig
from .mx_result import MxErrorKind, MxResult

_TIMEOUT_SEC = 30
_RETRY_BACKOFF_SEC = (1, 2, 4)  # 指数退避

# 任意层级需要脱敏的账户标识/敏感字段（P1-10）
SENSITIVE_KEYS = {
    "uid", "accUID", "accID", "accountId", "account_id",
    "accountNo", "account_no", "accName", "accountName", "account_name",
    "账户名称", "apikey", "apiKey", "api_key", "token",
}
_MASK = "***"


def _sanitize_value(value):
    """递归脱敏 dict/list 中的敏感字段（保留非敏感业务字段）。"""
    if isinstance(value, dict):
        return {
            k: (_MASK if k in SENSITIVE_KEYS else _sanitize_value(v))
            for k, v in value.items()
        }
    if isinstance(value, list):
        return [_sanitize_value(v) for v in value]
    return value


def sanitize_payload(payload: dict) -> dict:
    """脱敏：递归遮蔽账户标识与密钥同义字段，其余保留供 parser 与审计。"""
    cleaned = json.loads(json.dumps(payload, ensure_ascii=False, default=str))
    return _sanitize_value(cleaned)


def raw_hash(payload: dict) -> str:
    """原始 JSON 完整 SHA-256 哈希（P2-01：不截断，防碰撞）。"""
    return hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str).encode("utf-8")
    ).hexdigest()


class MxHttpClient:
    """妙想网关客户端：query 类可重试；trade/cancel 一律不自动重试。"""

    def __init__(self, config: PaperConfig, artifact_dir: Path | None = None):
        self._config = config
        self._artifact_dir = artifact_dir
        self._session = requests.Session()
        self._call_seq = 0  # 单调递增调用序号（raw 文件名防覆盖）
        self._raw_lock = Lock()

    def _endpoint_url(self, endpoint: str) -> str:
        return f"{self._config.api_base}{endpoint}"

    def _save_raw(self, run_id: str, endpoint: str, payload: dict) -> str:
        """按 run_id/call_seq/endpoint 落盘脱敏原文；返回相对路径（未配置目录则空）。"""
        if self._artifact_dir is None:
            return ""
        with self._raw_lock:
            self._call_seq += 1
            safe = endpoint.strip("/").replace("/", "_")
            path = self._artifact_dir / "raw" / f"{run_id}_{self._call_seq:04d}_{safe}.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                json.dumps(sanitize_payload(payload), ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
        return str(path)

    def save_raw(self, run_id: str, endpoint: str, payload: dict) -> str:
        """供同一 PaperRun 的辅助只读数据源复用脱敏原文落盘能力。"""
        return self._save_raw(run_id, endpoint, payload)

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
        # max(1, ...)：attempts 为 0 时循环体一次都不执行，last 保持 None，就会走到下面的
        # 断言（-O 下断言被剥离则直接返回 None，让调用方在 result.ok 上 AttributeError）。
        # 配置层已要求 max_retry>=1，这里是最后一道防线。
        attempts = max(1, self._config.max_retry) if retryable else 1
        last: MxResult | None = None
        for attempt in range(attempts):
            started = time.monotonic()
            try:
                resp = self._session.post(url, headers=headers, json=body, timeout=_TIMEOUT_SEC)
            except requests.RequestException as exc:
                # 网络错误：异常文本不含密钥（headers 不进入异常）
                last = MxResult(
                    endpoint=endpoint,
                    ok=False,
                    code="HTTP_ERROR",
                    message=f"网络错误: {type(exc).__name__}",
                    kind=MxErrorKind.HTTP_ERROR,
                    retry_count=attempt,
                    elapsed_ms=int((time.monotonic() - started) * 1000),
                )
            else:
                elapsed = int((time.monotonic() - started) * 1000)
                if resp.status_code >= 400:
                    # P1-09：4xx/5xx 是 HTTP 层错误，不进入业务包络解析
                    is_server_error = resp.status_code >= 500
                    last = MxResult(
                        endpoint=endpoint, ok=False, code=str(resp.status_code),
                        message=f"HTTP {resp.status_code}",
                        kind=MxErrorKind.HTTP_ERROR if is_server_error else MxErrorKind.BIZ_ERROR,
                        retry_count=attempt,
                        http_status=resp.status_code, elapsed_ms=elapsed,
                    )
                    # 5xx 且为查询类：可继续有限重试（循环底部退避）；4xx 一律不重试
                    if not retryable or not is_server_error:
                        return last
                else:
                    try:
                        payload = resp.json()
                    except ValueError:
                        payload = None
                    last = MxResult.from_json(endpoint, payload, retry_count=attempt)
                    last.http_status = resp.status_code
                    last.elapsed_ms = elapsed
                    if isinstance(payload, dict):
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
