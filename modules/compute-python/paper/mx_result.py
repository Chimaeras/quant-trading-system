"""妙想响应包络归一化与错误码分类。

兼容两种成功包络（以真实抓包为准）：
- 交易类 mockTrading：{"code": "200", "message": "成功", "data": {...}}，code 为字符串
- 行情类 query：{"success": true, "status": 0, "code": 0, "message": "ok", ...}

P1-09：非字典 payload 统一 PARSE_ERROR，不抛未捕获异常；
保留 HTTP 状态码与耗时供审计（不泄露敏感信息）。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum


class MxErrorKind(str, Enum):
    """错误分类，决定重试与停机策略。"""

    OK = "OK"
    QUOTA_EXHAUSTED = "QUOTA_EXHAUSTED"  # 113：停止本轮
    KEY_INVALID = "KEY_INVALID"  # 114/116：停止，不重试
    KEY_MISSING = "KEY_MISSING"  # 115：检查配置
    NOT_BOUND = "NOT_BOUND"  # 404：先去绑定
    OFF_HOURS = "OFF_HOURS"  # 文案含非交易时间：skip 不硬打
    HTTP_ERROR = "HTTP_ERROR"  # 网关/网络/5xx：查询类可有限重试
    BIZ_ERROR = "BIZ_ERROR"  # 其余业务失败
    PARSE_ERROR = "PARSE_ERROR"  # JSON 不完整/形状非法


def classify_code(code: int | str | None, message: str = "") -> MxErrorKind:
    """按妙想错误码与文案分类。"""
    if code is None:
        return MxErrorKind.BIZ_ERROR
    s = str(code)
    if s in ("200", "0"):
        return MxErrorKind.OK
    if s == "113":
        return MxErrorKind.QUOTA_EXHAUSTED
    if s in ("114", "116"):
        return MxErrorKind.KEY_INVALID
    if s == "115":
        return MxErrorKind.KEY_MISSING
    if s == "404":
        return MxErrorKind.NOT_BOUND
    if "非交易时间" in message or "不在交易时间" in message:
        return MxErrorKind.OFF_HOURS
    return MxErrorKind.BIZ_ERROR


@dataclass
class MxResult:
    """归一化后的妙想响应（保留原始 JSON 与审计字段）。"""

    endpoint: str
    ok: bool
    code: str
    message: str
    data: object = None
    kind: MxErrorKind = MxErrorKind.OK
    request_id: str = ""
    received_at: str = field(default_factory=lambda: datetime.now().isoformat(timespec="seconds"))
    retry_count: int = 0
    raw: dict = field(default_factory=dict)
    raw_path: str = ""  # 脱敏原文落盘路径
    http_status: int | None = None  # HTTP 层状态码（P1-09）
    elapsed_ms: int | None = None  # 请求耗时（P1-09）

    @classmethod
    def from_json(cls, endpoint: str, payload, retry_count: int = 0) -> "MxResult":
        """双包络归一化；非字典 payload 一律 PARSE_ERROR，绝不抛异常。"""
        if not isinstance(payload, dict):
            return cls(
                endpoint=endpoint, ok=False, code="PARSE_ERROR",
                message=f"响应不是 JSON 对象: {type(payload).__name__}",
                kind=MxErrorKind.PARSE_ERROR, retry_count=retry_count,
            )
        ok = bool(payload.get("success")) or str(payload.get("code")) == "200"
        # 行情类顶层 status==0 也视为成功（与官方 mx_data.py 一致）
        if str(payload.get("status")) == "0":
            ok = True
        code = str(payload.get("code", payload.get("status", "")))
        message = str(payload.get("message", ""))
        kind = classify_code(payload.get("code", payload.get("status")), message)
        if ok:
            kind = MxErrorKind.OK
        data = payload.get("data")
        # 行情类 data.data 才是业务体，这里保留原样由 parser 处理
        return cls(
            endpoint=endpoint,
            ok=ok,
            code=code,
            message=message,
            data=data,
            kind=kind,
            request_id=str(payload.get("requestId", "")),
            retry_count=retry_count,
            raw=payload,
        )

    def stop_run(self) -> bool:
        """该错误是否应中止整轮 run（配额耗尽 / Key 问题 / 未绑定）。"""
        return self.kind in (
            MxErrorKind.QUOTA_EXHAUSTED,
            MxErrorKind.KEY_INVALID,
            MxErrorKind.KEY_MISSING,
            MxErrorKind.NOT_BOUND,
        )

    def retryable(self) -> bool:
        """是否可安全重试（仅网关/网络类，且非 trade/cancel）。"""
        return self.kind in (MxErrorKind.HTTP_ERROR, MxErrorKind.PARSE_ERROR)
