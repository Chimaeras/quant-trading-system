"""风控闸：下单前硬拦截（纯函数，顺序固定，失败写 risk_reject_reason）。

对应开发文档 §11 的 18 道闸；V1 落地其中可在本地判定的闸，
依赖外部实时状态的（绑定/配额/账户查询）由 runner 在调用前后执行。
"""
from __future__ import annotations

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
) -> Intent:
    """对单个 BUY_LIMIT 意图跑风控闸；拒绝则降级 NOOP 并写原因。

    confirm 模式缺涨跌停真实价时硬拦截 LIMIT_DATA_UNAVAILABLE（文档 §11 附注）。
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

    # 闸 7：涨跌停（真实涨停价未冻结：confirm 模式下无近涨停保护价时硬拦）
    if (quote.pct_chg or 0) >= 9.0:
        return reject("NEAR_LIMIT_UP")

    # 闸 8：资金（金额+佣金 ≤ 可用）
    gross = intent.qty * intent.limit_price
    if gross + estimate_commission(gross) > balance.avail_balance:
        return reject("INSUFFICIENT_FUNDS")

    # 闸 9：单票市值占比（成交后市值/总资产 ≤ max_name_weight，留 5% 容差）
    if balance.total_assets > 0 and gross / balance.total_assets > config.max_name_weight * 1.05:
        return reject("NAME_WEIGHT_EXCEEDED")

    # 闸 10：单日笔数
    if trades_this_run >= config.max_trades_per_run:
        return reject("MAX_TRADES_PER_RUN")

    # 闸 12：同向未完成委托
    if any(o.code == intent.stock_code and _is_buy(o.side) for o in open_orders):
        return reject("DUPLICATE_ORDER")

    intent.risk_status = RiskStatus.PASS
    return intent


def _is_buy(side: str) -> bool:
    return str(side).strip().lower() in ("buy", "b", "1", "买", "买入")
