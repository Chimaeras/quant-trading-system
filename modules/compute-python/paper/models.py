"""Paper Live 领域模型：行情、账户、委托、意图与运行快照。"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum


class QuoteValidity(str, Enum):
    """行情有效性判定。"""

    VALID = "VALID"
    STALE = "STALE"
    INVALID = "INVALID"
    NOT_TRADABLE = "NOT_TRADABLE"


class IntentType(str, Enum):
    """订单意图类型（V1 只产出 NOOP 与 BUY_LIMIT）。"""

    NOOP = "NOOP"
    BUY_LIMIT = "BUY_LIMIT"
    BUY_MARKET = "BUY_MARKET"
    SELL_LIMIT = "SELL_LIMIT"
    SELL_MARKET = "SELL_MARKET"
    CANCEL_THEN_REPLACE = "CANCEL_THEN_REPLACE"


class RiskStatus(str, Enum):
    """风控结论：PASS 放行，REJECT 拒绝，PENDING 依赖运行期状态。"""

    PASS = "PASS"
    REJECT = "REJECT"
    PENDING = "PENDING"  # 待运行期风控
    FROZEN = "FROZEN"  # 未知提交状态，冻结该标的


@dataclass
class Quote:
    """一次问句查数解析出的实时行情快照。"""

    code: str  # 6 位代码
    name: str
    exchange: str  # SH / SZ
    last: float
    pct_chg: float | None = None  # 百分数，如 0.38 表示 +0.38%
    volume: float | None = None  # 股
    amount: float | None = None
    volume_ratio: float | None = None
    market_time: datetime | None = None  # 行情时间（headName 解析）
    received_at: datetime | None = None  # 本地接收时间
    source: str = "mx_query"
    raw_hash: str = ""
    raw_path: str = ""
    validity: QuoteValidity = QuoteValidity.VALID
    freshness_seconds: float | None = None
    error: str = ""  # 解析失败原因（validity=INVALID 时）
    limit_up_price: float | None = None  # 涨停价硬闸（P0-06）
    limit_up_source: str = ""  # 涨停价来源与计算方式（审计用）
    limit_down_price: float | None = None  # 跌停价（当前仅作为卖出安全信息）
    limit_down_source: str = ""

    def is_tradable_snapshot(self) -> bool:
        """有效且未过期且成交活跃。"""
        return (
            self.validity == QuoteValidity.VALID
            and self.last > 0
            and (self.volume or 0) > 0
        )


@dataclass
class Balance:
    """妙想模拟账户资金快照。"""

    total_assets: float = 0.0
    avail_balance: float = 0.0
    frozen: float = 0.0
    market_value: float = 0.0
    acc_name: str = ""
    acc_id: str = ""
    raw: dict = field(default_factory=dict)

    @classmethod
    def from_mx(cls, data: dict) -> "Balance":
        """从 mockTrading/balance 的 data 构建；缺关键字段抛错（fail-closed）。"""
        for key in ("totalAssets", "availBalance"):
            if key not in data:
                raise ValueError(f"balance 响应缺少字段 {key}，拒绝猜测解析")
        return cls(
            total_assets=float(data["totalAssets"]),
            avail_balance=float(data["availBalance"]),
            frozen=float(data.get("frozenMoney", 0.0)),
            market_value=float(data.get("totalPosValue", 0.0)),
            acc_name=str(data.get("accName", "")),
            acc_id=str(data.get("accID", "")),
            raw=data,
        )


@dataclass
class Position:
    """妙想持仓条目（字段以绑定后抓包为准，posList 非空样本待冻结）。"""

    code: str
    name: str
    qty: int
    avail_qty: int
    cost: float
    last: float
    market_value: float
    profit: float
    raw: dict = field(default_factory=dict)


@dataclass
class MxOrder:
    """妙想委托条目（订单状态枚举未冻结，status 保留原文）。"""

    order_id: str
    code: str
    side: str
    price: float | None
    qty: int
    filled_qty: int | None
    status: str  # 原文状态，未映射
    ts: str = ""
    raw: dict = field(default_factory=dict)


@dataclass
class Candidate:
    """观察池候选（来自 Java T1 或本地文件）。"""

    stock_code: str
    stock_name: str
    exchange_type: str  # SH / SZ
    score: float | None = None
    rule_score: float | None = None
    probability: float | None = None
    close: float | None = None
    buyability: str = ""
    risk_tags: list = field(default_factory=list)
    is_limit_up: bool = False
    lianban_days: int = 0
    signal_date: str = ""
    trade_date: str = ""
    source: str = "t1"

    def board_blocked(self, allow_chinext: bool, allow_star: bool) -> str | None:
        """板块闸：返回拦截原因码或 None。创业板 300 开头，科创 688/689。"""
        if self.stock_code.startswith("300") and not allow_chinext:
            return "BOARD_BLOCKED_CHINEXT"
        if self.stock_code.startswith(("688", "689")) and not allow_star:
            return "BOARD_BLOCKED_STAR"
        return None


@dataclass
class Intent:
    """单只候选的订单意图（每只候选最多一个）。"""

    stock_code: str
    intent_type: IntentType
    side: str = "buy"  # buy / sell
    qty: int = 0
    price_type: str = "limit"
    limit_price: float | None = None
    reason_codes: list[str] = field(default_factory=list)
    quote_ts: str = ""
    candidate_source: str = ""
    risk_status: RiskStatus = RiskStatus.PENDING
    risk_reject_reason: str = ""

    def to_dict(self) -> dict:
        """序列化（写 intents.json）。"""
        return {
            "stock_code": self.stock_code,
            "intent_type": self.intent_type.value,
            "side": self.side,
            "qty": self.qty,
            "price_type": self.price_type,
            "limit_price": self.limit_price,
            "reason_codes": self.reason_codes,
            "quote_ts": self.quote_ts,
            "candidate_source": self.candidate_source,
            "risk_status": self.risk_status.value,
            "risk_reject_reason": self.risk_reject_reason,
        }


@dataclass
class SubmitRecord:
    """一笔真实提交的外部记录（trade 调用结果）。"""

    stock_code: str
    side: str
    qty: int
    price: float | None
    ok: bool
    order_id: str = ""
    error_code: str = ""
    error_message: str = ""
    status: str = "SUBMITTED"  # SUBMITTED / UNKNOWN_SUBMIT / REJECTED
    request_id: str = ""
    raw: dict = field(default_factory=dict)


@dataclass
class PaperRun:
    """一次 Paper Live 运行的完整审计快照。"""

    run_id: str
    mode: str  # dry-run / run
    signal_date: str
    trade_date: str
    candidate_source: str = ""
    strategy_version: str = "paper-v1"
    config_snapshot: dict = field(default_factory=dict)
    candidates: list[dict] = field(default_factory=list)
    quotes: dict[str, dict] = field(default_factory=dict)
    intents: list[dict] = field(default_factory=list)
    submits: list[dict] = field(default_factory=list)
    risk_rejects: list[dict] = field(default_factory=list)
    account_before: dict = field(default_factory=dict)
    account_after: dict = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)
    status: str = "CREATED"  # CREATED/PREPARING/PREVIEW_READY/CONFIRMING/SUBMITTING/SUCCESS/PARTIAL/BLOCKED/UNKNOWN/STOPPED
    preview_run_id: str = ""  # confirm 关联的 preview（P0-04 审计链）
    metrics: dict = field(default_factory=dict)  # P2-04 运行级指标
