"""watch 信号到订单意图的纯函数转换。"""
from __future__ import annotations

from datetime import datetime

from ..decide import derive_limit_up_price
from ..models import Balance, Candidate, Intent, IntentType, Position, Quote, QuoteValidity
from ..config import LOT_SIZE, PaperConfig
from .models import WatchSignal
from .size import buy_qty, limit_price, sell_qty


def _noop(code: str, reason: str, source: str = "watch") -> Intent:
    return Intent(
        stock_code=code,
        intent_type=IntentType.NOOP,
        reason_codes=[reason],
        candidate_source=source,
    )


def build_watch_intent(
    candidate: Candidate,
    signal: WatchSignal,
    quote: Quote | None,
    balance: Balance,
    position: Position | None,
    config: PaperConfig,
    *,
    now: datetime,
    security_rule=None,
) -> Intent:
    """只依赖已收盘信号和柜台持仓，不访问策略对象 position。"""
    if signal.action not in {"buy", "sell"}:
        return _noop(candidate.stock_code, "HOLD", candidate.source)
    if quote is None:
        return _noop(candidate.stock_code, "QUOTE_MISSING", candidate.source)
    if quote.validity != QuoteValidity.VALID or quote.last <= 0 or (quote.volume or 0) <= 0:
        return _noop(candidate.stock_code, f"QUOTE_{quote.validity.value}", candidate.source)

    reason_codes = list(signal.reason_codes)
    reason_codes.append("BAR_CLOSED") if "BAR_CLOSED" not in reason_codes else None
    quote_ts = quote.market_time.isoformat() if quote.market_time else now.isoformat()

    if signal.action == "buy":
        if position is not None and position.qty > 0:
            return _noop(candidate.stock_code, "ALREADY_POSITION_NO_ADD", candidate.source)
        planned_limit = limit_price(
            quote.last,
            candidate.exchange_type,
            "buy",
            config.slippage_bps,
        )
        tick_size = getattr(security_rule, "tick_size", None) if security_rule is not None else None
        decimals = getattr(security_rule, "price_decimals", None) if security_rule is not None else None
        if tick_size and tick_size > 0:
            planned_limit = round(round(planned_limit / tick_size) * tick_size, decimals or 2)
        # 仓位和可负担数量必须按实际委托限价计算；按最新价取整后再叠加
        # 滑点会让名义金额穿过 max_name_weight，导致正常信号被风控反拒。
        qty = buy_qty(balance, planned_limit, config)
        if qty < LOT_SIZE:
            return _noop(candidate.stock_code, "QTY_TOO_SMALL", candidate.source)
        quote = derive_limit_up_price(quote)
        return Intent(
            stock_code=candidate.stock_code,
            intent_type=IntentType.BUY_LIMIT,
            side="buy",
            qty=qty,
            price_type="limit",
            limit_price=planned_limit,
            reason_codes=reason_codes,
            quote_ts=quote_ts,
            candidate_source="watch",
        )

    available = position.avail_qty if position is not None else 0
    allow_odd_lot_sell = bool(
        getattr(security_rule, "odd_lot_sell", False) if security_rule is not None else False
    )
    qty = available if allow_odd_lot_sell and 0 < available < LOT_SIZE else sell_qty(available)
    if qty <= 0:
        return _noop(candidate.stock_code, "NO_SELLABLE", candidate.source)
    return Intent(
        stock_code=candidate.stock_code,
        intent_type=IntentType.SELL_LIMIT,
        side="sell",
        qty=qty,
        price_type="limit",
        limit_price=limit_price(quote.last, candidate.exchange_type, "sell", config.slippage_bps),
        reason_codes=reason_codes,
        quote_ts=quote_ts,
        candidate_source="watch",
    )
