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


def check_watch_intent(
    intent: Intent,
    quote: Quote | None,
    balance: Balance,
    positions: dict[str, object],
    open_orders: list[MxOrder],
    config: PaperConfig,
    *,
    trades_this_day: int = 0,
    trades_this_cycle: int = 0,
    cooldown_ok: bool = True,
    universe_codes: set[str] | None = None,
    pool_median_pct_chg: float | None = None,
    confirmed: bool = True,
    strict_limit_data: bool = False,
    security_rule=None,
    require_security_rule: bool = False,
    gate_trace: list | None = None,
) -> Intent:
    """盘中 watch 风控：买卖共用基础闸，卖出不被 HALT 错误禁止。

    positions 的 value 可以是 Position，也可以是兼容的测试 double；
    只读取 code/qty/avail_qty/market_value 属性。
    """
    if intent.intent_type == IntentType.NOOP:
        intent.risk_status = RiskStatus.PASS
        if gate_trace is not None:
            gate_trace.append({"gate": "NOOP", "result": "PASS"})
        return intent

    def reject(reason: str) -> Intent:
        intent.intent_type = IntentType.NOOP
        intent.risk_status = RiskStatus.REJECT
        intent.risk_reject_reason = reason
        if reason not in intent.reason_codes:
            intent.reason_codes.append(reason)
        return intent

    def note(gate: str, passed: bool) -> None:
        if gate_trace is not None:
            gate_trace.append({"gate": gate, "result": "PASS" if passed else "REJECT"})

    if not confirmed:
        note("CONFIRM", False)
        return reject("CONFIRM_REQUIRED")
    note("CONFIRM", True)
    if universe_codes is not None and intent.stock_code not in universe_codes:
        note("WATCHLIST", False)
        return reject("NOT_IN_WATCHLIST")
    note("WATCHLIST", True)
    if intent.intent_type not in {IntentType.BUY_LIMIT, IntentType.SELL_LIMIT}:
        note("INTENT_TYPE", False)
        return reject("UNSUPPORTED_WATCH_INTENT")
    note("INTENT_TYPE", True)
    if quote is None or quote.validity != QuoteValidity.VALID or quote.last <= 0:
        note("QUOTE_VALID", False)
        return reject("QUOTE_NOT_VALID")
    note("QUOTE_VALID", True)
    if intent.limit_price is None or intent.limit_price <= 0:
        note("PRICE", False)
        return reject("PRICE_INVALID")
    note("PRICE", True)
    def rule_value(name: str, default=None):
        if security_rule is None:
            return default
        if isinstance(security_rule, dict):
            return security_rule.get(name, default)
        return getattr(security_rule, name, default)

    if require_security_rule and security_rule is None:
        note("SECURITY_RULE", False)
        return reject("SECURITY_RULE_UNAVAILABLE")
    rule_status = str(rule_value("status", "UNKNOWN"))
    if require_security_rule and rule_status != "ACTIVE":
        note("SECURITY_STATUS", False)
        return reject(f"SECURITY_{rule_status}")
    if security_rule is not None and rule_status not in {"ACTIVE", "UNKNOWN"}:
        note("SECURITY_STATUS", False)
        return reject(f"SECURITY_{rule_value('status', 'UNKNOWN')}")
    note("SECURITY_RULE", True)
    lot_size = int(rule_value("buy_lot", LOT_SIZE) or LOT_SIZE)
    odd_lot_sell = bool(rule_value("odd_lot_sell", False))
    if intent.qty <= 0 or (
        intent.qty % lot_size != 0
        and not (intent.intent_type == IntentType.SELL_LIMIT and odd_lot_sell)
    ):
        note("LOT_SIZE", False)
        return reject("LOT_SIZE_VIOLATION")
    note("LOT_SIZE", True)
    board = str(rule_value("board", ""))
    is_chinext = board == "CHINEXT" if board else intent.stock_code.startswith(("300", "301"))
    is_star = board == "STAR" if board else intent.stock_code.startswith(("688", "689"))
    if intent.intent_type == IntentType.BUY_LIMIT and is_chinext and not config.allow_chinext:
        note("BOARD_POLICY", False)
        return reject("BOARD_BLOCKED_CHINEXT")
    if intent.intent_type == IntentType.BUY_LIMIT and is_star and not config.allow_star:
        note("BOARD_POLICY", False)
        return reject("BOARD_BLOCKED_STAR")
    note("BOARD_POLICY", True)
    if trades_this_day >= config.max_trades_per_day:
        note("MAX_TRADES_PER_DAY", False)
        return reject("MAX_TRADES_PER_DAY")
    note("MAX_TRADES_PER_DAY", True)
    if trades_this_cycle >= config.max_trades_per_cycle:
        note("MAX_TRADES_PER_CYCLE", False)
        return reject("MAX_TRADES_PER_CYCLE")
    note("MAX_TRADES_PER_CYCLE", True)
    if not cooldown_ok:
        note("COOLDOWN", False)
        return reject("COOLDOWN")
    note("COOLDOWN", True)

    position = positions.get(intent.stock_code)
    current_qty = int(getattr(position, "qty", 0) or 0)
    available_qty = int(getattr(position, "avail_qty", 0) or 0)

    if intent.intent_type == IntentType.SELL_LIMIT:
        # HALT 只禁开仓，允许减仓/退出风险。
        allow_odd_lot_sell = odd_lot_sell
        available_lot = (
            available_qty if allow_odd_lot_sell
            else available_qty - available_qty % lot_size
        )
        if available_lot <= 0:
            note("SELLABLE", False)
            return reject("NO_SELLABLE")
        note("SELLABLE", True)
        if intent.qty > available_lot:
            note("SELL_QTY_CAP", False)
            return reject("SELL_QTY_EXCEEDS_AVAILABLE")
        note("SELL_QTY_CAP", True)
        # 已知跌停价时，跌停价及以下的卖单大概率无法成交；继续提交只会
        # 制造无意义委托，且可能掩盖“当前不可卖”的状态。未知跌停价仍由
        # 下面的近跌停保护和行情有效性闸门兜底。
        if quote.limit_down_price is not None and intent.limit_price <= quote.limit_down_price:
            note("LIMIT_DOWN_NOT_SELLABLE", False)
            return reject("LIMIT_DOWN_NOT_SELLABLE")
        note("LIMIT_DOWN_NOT_SELLABLE", True)
        if (quote.pct_chg or 0) <= -9.0 and quote.limit_down_price is None:
            note("NEAR_LIMIT_DOWN", False)
            return reject("NEAR_LIMIT_DOWN")
        note("NEAR_LIMIT_DOWN", True)
        if any(
            order.code == intent.stock_code
            and order.side == "sell"
            and order.status in ACTIVE_ORDER_STATUSES
            and order_remaining_qty(order) > 0
            for order in open_orders
        ):
            note("DUPLICATE_SELL_ORDER", False)
            return reject("DUPLICATE_SELL_ORDER")
        note("DUPLICATE_SELL_ORDER", True)
        intent.risk_status = RiskStatus.PASS
        return intent

    # BUY_LIMIT 组合级闸。
    if config.halt:
        note("HALT", False)
        return reject("HALT")
    note("HALT", True)
    if quote.limit_up_price is None or (
        strict_limit_data and str(quote.limit_up_source).startswith("derived:")
    ):
        note("LIMIT_DATA", False)
        return reject("LIMIT_DATA_UNAVAILABLE")
    note("LIMIT_DATA", True)
    if intent.limit_price >= quote.limit_up_price:
        note("LIMIT_UP_NOT_BUYABLE", False)
        return reject("LIMIT_UP_NOT_BUYABLE")
    note("LIMIT_UP_NOT_BUYABLE", True)
    if (quote.pct_chg or 0) >= 9.0:
        note("NEAR_LIMIT_UP", False)
        return reject("NEAR_LIMIT_UP")
    note("NEAR_LIMIT_UP", True)

    gross = intent.qty * intent.limit_price
    total_cost = gross + estimate_commission(gross)
    if total_cost > balance.avail_balance:
        note("FUNDS", False)
        return reject("INSUFFICIENT_FUNDS")
    note("FUNDS", True)
    if balance.avail_balance - total_cost < config.min_cash_reserve:
        note("CASH_RESERVE_FLOOR", False)
        return reject("CASH_RESERVE_FLOOR")
    note("CASH_RESERVE_FLOOR", True)

    position_value = max(
        float(getattr(balance, "market_value", 0.0) or 0.0),
        sum(float(getattr(item, "market_value", 0.0) or 0.0) for item in positions.values()),
    )
    active_buy_orders = [
        order for order in open_orders
        if order.side == "buy"
        and order.status in ACTIVE_ORDER_STATUSES
        and order_remaining_qty(order) > 0
    ]
    if any(order.price is None or order.price <= 0 for order in active_buy_orders):
        note("OPEN_BUY_EXPOSURE", False)
        return reject("OPEN_BUY_EXPOSURE_UNKNOWN")
    pending_buy_value = sum(
        order_remaining_qty(order) * float(order.price)
        for order in active_buy_orders
    )
    note("OPEN_BUY_EXPOSURE", True)
    if balance.total_assets > 0 and (position_value + pending_buy_value + gross) / balance.total_assets > config.max_total_weight:
        note("TOTAL_WEIGHT", False)
        return reject("TOTAL_WEIGHT_EXCEEDED")
    note("TOTAL_WEIGHT", True)
    current_name_value = float(getattr(position, "market_value", 0.0) or 0.0)
    pending_name_value = sum(
        order_remaining_qty(order) * float(order.price)
        for order in active_buy_orders
        if order.code == intent.stock_code
    )
    if (
        balance.total_assets > 0
        and (current_name_value + pending_name_value + gross) / balance.total_assets > config.max_name_weight
    ):
        note("NAME_WEIGHT", False)
        return reject("NAME_WEIGHT_EXCEEDED")
    note("NAME_WEIGHT", True)
    occupied_codes = {
        str(code) for code, item in positions.items()
        if int(getattr(item, "qty", 0) or 0) > 0
    }
    occupied_codes.update(order.code for order in active_buy_orders)
    if (
        config.max_positions > 0
        and intent.stock_code not in occupied_codes
        and len(occupied_codes) >= config.max_positions
    ):
        note("MAX_POSITIONS", False)
        return reject("MAX_POSITIONS")
    note("MAX_POSITIONS", True)
    if config.index_circuit_breaker and pool_median_pct_chg is not None and pool_median_pct_chg <= -3.0:
        note("INDEX_CIRCUIT_BREAKER", False)
        return reject("INDEX_CIRCUIT_BREAKER")
    note("INDEX_CIRCUIT_BREAKER", True)
    if any(
        order.code == intent.stock_code
        and order.side == "buy"
        and order.status in ACTIVE_ORDER_STATUSES
        and order_remaining_qty(order) > 0
        for order in open_orders
    ):
        note("DUPLICATE_BUY_ORDER", False)
        return reject("DUPLICATE_BUY_ORDER")
    note("DUPLICATE_BUY_ORDER", True)

    intent.risk_status = RiskStatus.PASS
    return intent
