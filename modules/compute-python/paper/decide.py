"""V1 研判纯函数：昨日 T+1 候选 → 今日 BUY_LIMIT / NOOP。

实现开发文档 §10.2 伪代码，逐条给出 reason_code。禁止任何 HTTP 与 IO。
"""
from __future__ import annotations

import math
from datetime import datetime

from .config import BUYABILITY_BLOCKED, PaperConfig, PRICE_DECIMALS
from .models import Balance, Candidate, Intent, IntentType, MxOrder, Quote, QuoteValidity

# 弱涨停信号阈值：T 日 is_limit_up 且今日涨幅 ≥ 9.8% 视为锁死
_WEAK_LIMIT_PCT = 9.8


def in_trading_window(now: datetime) -> bool:
    """连续竞价时段判定（工作日 09:30-11:30 / 13:00-15:00，节假日由风控闸 1 兜底）。"""
    if now.weekday() >= 5:
        return False
    hhmm = now.hour * 100 + now.minute
    return (930 <= hhmm < 1130) or (1300 <= hhmm < 1500)


def round_limit_price(price: float, exchange: str) -> float:
    """限价小数位：沪 2 位，深 3 位。"""
    return round(price, PRICE_DECIMALS.get(exchange, 2))


def limit_price_for_buy(last: float, exchange: str, slippage_bps: int) -> float:
    """买限价 = last * (1 + slippage_bps/10000)，按市场小数位取整。"""
    return round_limit_price(last * (1 + slippage_bps / 10000), exchange)


def _noop(code: str, reason: str, source: str) -> Intent:
    """构造 NOOP 意图。"""
    return Intent(stock_code=code, intent_type=IntentType.NOOP, reason_codes=[reason], candidate_source=source)


def target_qty(avail_balance: float, last: float, max_name_weight: float) -> int:
    """目标股数 = floor(avail * weight / last / 100) * 100（整手）。"""
    if last <= 0:
        return 0
    lots = math.floor(avail_balance * max_name_weight / last / 100)
    return max(lots, 0) * 100


def affordable_qty(avail_balance: float, last: float, commission_estimate: float = 5.0) -> int:
    """资金可买手数（留最低佣金缓冲）。"""
    if last <= 0:
        return 0
    usable = avail_balance - commission_estimate
    if usable <= 0:
        return 0
    return math.floor(usable / last / 100) * 100


def decide(
    candidates: list[Candidate],
    quotes: dict[str, Quote],
    balance: Balance,
    positions_qty: dict[str, int],
    open_orders: list[MxOrder],
    config: PaperConfig,
    now: datetime,
) -> list[Intent]:
    """对每只候选产出至多一个 Intent（V1 只买入）。

    Args:
        positions_qty: {code: 持仓股数}（妙想 positions 归纳）。
        open_orders: 未完成委托（含原文 status；同向判断按 side 原文/映射）。
    """
    intents: list[Intent] = []

    # 全局闸：时段与熔断
    if not in_trading_window(now):
        return [_noop(c.stock_code, "TIME_WINDOW", c.source) for c in candidates]
    if config.halt:
        return [_noop(c.stock_code, "HALT", c.source) for c in candidates]

    buy_codes_with_open_order = {o.code for o in open_orders if _is_buy_side(o.side)}

    for cand in candidates:
        code = cand.stock_code
        quote = quotes.get(code)

        # 板块闸
        board = cand.board_blocked(config.allow_chinext, config.allow_star)
        if board:
            intents.append(_noop(code, board, cand.source))
            continue

        # 行情闸
        if quote is None or quote.error:
            intents.append(_noop(code, "QUOTE_MISSING", cand.source))
            continue
        if quote.last <= 0:
            intents.append(_noop(code, "QUOTE_MISSING", cand.source))
            continue
        if quote.validity == QuoteValidity.STALE:
            intents.append(_noop(code, "QUOTE_STALE", cand.source))
            continue
        if quote.validity == QuoteValidity.NOT_TRADABLE or (quote.volume or 0) == 0:
            intents.append(_noop(code, "NOT_TRADABLE", cand.source))
            continue

        # 涨停锁死：真实涨停价接口未冻结，用弱信号（T 日涨停 + 今日涨幅≥9.8%）
        if cand.is_limit_up and (quote.pct_chg or 0) >= _WEAK_LIMIT_PCT:
            intents.append(_noop(code, "LIMIT_LOCKED", cand.source))
            continue

        # buyability 差的默认放弃（中文枚举，T1CandidateService 产出）
        if cand.buyability == BUYABILITY_BLOCKED:
            intents.append(_noop(code, "BUYABILITY_BLOCKED", cand.source))
            continue

        # 已持仓/重复委托
        target = target_qty(balance.avail_balance, quote.last, config.max_name_weight)
        if positions_qty.get(code, 0) >= target and target > 0:
            intents.append(_noop(code, "ALREADY_FILLED", cand.source))
            continue
        if code in buy_codes_with_open_order:
            intents.append(_noop(code, "DUPLICATE_ORDER", cand.source))
            continue

        # 目标数量
        qty = min(target, affordable_qty(balance.avail_balance, quote.last))
        if qty < 100:
            intents.append(_noop(code, "QTY_TOO_SMALL", cand.source))
            continue

        limit_price = limit_price_for_buy(quote.last, cand.exchange_type, config.slippage_bps)
        intents.append(
            Intent(
                stock_code=code,
                intent_type=IntentType.BUY_LIMIT,
                side="buy",
                qty=qty,
                price_type="limit",
                limit_price=limit_price,
                reason_codes=["T1_CANDIDATE", "QUOTE_VALID"],
                quote_ts=quote.market_time.isoformat() if quote.market_time else "",
                candidate_source=cand.source,
            )
        )
    return intents


def _is_buy_side(side: str) -> bool:
    """委托方向判断：枚举未冻结，兼容 buy/买/1/B 等常见形式。"""
    return str(side).strip().lower() in ("buy", "b", "1", "买", "买入")
