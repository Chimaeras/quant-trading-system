"""风控闸：下单前硬拦截（纯函数，顺序固定，失败写 risk_reject_reason）。

对应开发文档 §11 的 18 道闸；V1 落地其中可在本地判定的闸，
依赖外部实时状态的（绑定/配额/账户查询）由 runner 在调用前后执行。
"""
from __future__ import annotations

from .broker_client import ACTIVE_ORDER_STATUSES, order_remaining_qty
from .config import (
    COMMISSION_RATE,
    LOT_SIZE,
    MIN_COMMISSION,
    PaperConfig,
)
from .models import Balance, Intent, IntentType, MxOrder, Quote, QuoteValidity, RiskStatus


def estimate_commission(gross_amount: float) -> float:
    """佣金 = max(金额×0.0003, 5)（TradingFeePolicy 口径）。"""
    return max(gross_amount * COMMISSION_RATE, MIN_COMMISSION)


def check_intent(
    intent: Intent,
    quote: Quote | None,
    balance: Balance,
    open_orders: list[MxOrder],
    config: PaperConfig,
    trades_this_run: int,
    candidates_whitelist: set[str],
    *,
    confirmed: bool,
    positions_qty: dict[str, int] | None = None,
) -> Intent:
    """对单个 BUY_LIMIT 意图跑风控闸；拒绝则降级 NOOP 并写原因。

    涨停硬闸（P0-06）：limit_up_price 缺失 → LIMIT_DATA_UNAVAILABLE；
    买入价达到涨停价 → LIMIT_UP_NOT_BUYABLE。禁止以 9%/9.8% 近似值替代。
    """
    if intent.intent_type != IntentType.BUY_LIMIT:
        intent.risk_status = RiskStatus.PASS if intent.intent_type == IntentType.NOOP else RiskStatus.REJECT
        return intent

    def reject(reason: str) -> Intent:
        intent.intent_type = IntentType.NOOP
        intent.risk_status = RiskStatus.REJECT
        intent.risk_reject_reason = reason
        intent.reason_codes.append(reason)
        return intent

    # 闸 17：人工确认
    if not confirmed:
        return reject("CONFIRM_REQUIRED")

    # 闸 3：白名单
    if intent.stock_code not in candidates_whitelist:
        return reject("NOT_IN_WHITELIST")

    # 闸 5：手数
    if intent.qty <= 0 or intent.qty % LOT_SIZE != 0:
        return reject("LOT_SIZE_VIOLATION")

    # 闸 6/11：限价与行情新鲜度
    if intent.limit_price is None or intent.limit_price <= 0:
        return reject("PRICE_INVALID")
    if quote is None or quote.validity != QuoteValidity.VALID:
        return reject("QUOTE_NOT_VALID")

    # 闸 16：熔断
    if config.halt:
        return reject("HALT")

    # 闸 7a：涨停硬闸（P0-06）——无可靠涨停价即阻断，禁止以近似涨幅放行
    if quote.limit_up_price is None:
        return reject("LIMIT_DATA_UNAVAILABLE")
    if intent.limit_price >= quote.limit_up_price:
        return reject("LIMIT_UP_NOT_BUYABLE")
    # 闸 7b：近涨停辅助信号（弱保护，非最终安全依据）
    if (quote.pct_chg or 0) >= 9.0:
        return reject("NEAR_LIMIT_UP")

    # 闸 8：资金（金额+佣金 ≤ 可用）
    gross = intent.qty * intent.limit_price
    if gross + estimate_commission(gross) > balance.avail_balance:
        return reject("INSUFFICIENT_FUNDS")

    # 闸 9：单票市值占比（P0-05：按执行后持仓 = 已有持仓 + 本单估算）
    current_qty = (positions_qty or {}).get(intent.stock_code, 0)
    post_qty = current_qty + intent.qty
    if balance.total_assets > 0 and post_qty * intent.limit_price / balance.total_assets > config.max_name_weight * 1.05:
        return reject("NAME_WEIGHT_EXCEEDED")

    # 闸 10：单日笔数
    if trades_this_run >= config.max_trades_per_run:
        return reject("MAX_TRADES_PER_RUN")

    # 闸 12：同向未完成委托（仅活动状态且剩余数量 > 0）
    if any(
        o.code == intent.stock_code
        and o.side == "buy"
        and o.status in ACTIVE_ORDER_STATUSES
        and order_remaining_qty(o) > 0
        for o in open_orders
    ):
        return reject("DUPLICATE_ORDER")

    intent.risk_status = RiskStatus.PASS
    return intent
