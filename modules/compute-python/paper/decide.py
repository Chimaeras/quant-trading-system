"""V1 研判纯函数：昨日 T+1 候选 → 今日 BUY_LIMIT / NOOP。

实现开发文档 §10.2 伪代码，逐条给出 reason_code。禁止任何 HTTP 与 IO。
"""
from __future__ import annotations

import math
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP

from .broker_client import ACTIVE_ORDER_STATUSES, order_remaining_qty
from .config import BUYABILITY_BLOCKED, LOT_SIZE, PaperConfig, PRICE_DECIMALS
from .models import Balance, Candidate, Intent, IntentType, MxOrder, Quote, QuoteValidity
# D-RISK-01：板块涨跌幅判定已收敛到 risk.py（风控侧）作为唯一来源，
# 避免 decide.py 与 risk.py 各有一套「涨停/接近涨停」口径。
from .risk import board_limit_pct

# 弱涨停信号阈值（辅助信号，禁止作为最终安全依据，硬闸见 risk.py limit_up_price）
_WEAK_LIMIT_PCT = 9.8


def in_trading_window(now: datetime) -> bool:
    """连续竞价时段判定（工作日 09:30-11:30 / 13:00-15:00，节假日由风控闸 1 兜底）。"""
    if now.weekday() >= 5:
        return False
    hhmm = now.hour * 100 + now.minute
    return (930 <= hhmm < 1130) or (1300 <= hhmm < 1500)


def _round_half_up(value: float, ndigits: int) -> float:
    """A 股『四舍五入』取整到 ndigits 位小数。

    交易所涨跌停/限价按四舍五入（half up）取到最小价位；Python 内置 round 是银行家舍入，
    在精确 .005 边界会舍到偶数（如 round(2.675, 2) → 2.67），与约定不一致。用 Decimal
    ROUND_HALF_UP 在十进制表示上按半价上舍入。
    """
    q = Decimal(1).scaleb(-ndigits)
    return float(Decimal(str(value)).quantize(q, rounding=ROUND_HALF_UP))


def round_limit_price(price: float, exchange: str) -> float:
    """限价小数位：沪/北/深均为 2 位（PRICE_DECIMALS）。A 股报价最小价位为 0.01。"""
    return _round_half_up(price, PRICE_DECIMALS.get(exchange, 2))


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


def derive_limit_up_price(quote: Quote) -> Quote:
    """从 last 与 pct_chg 反推涨停价：主板 10%、北交所 30%、创业板/科创板 20%。

    prev_close=last/(1+pct/100)，再按对应市场涨跌幅取整到 2 位。

    仅当两个输入均可用才推导，并在 limit_up_source 记录计算方式（审计）。
    pct_chg 缺失时保持 None，由风控闸 LIMIT_DATA_UNAVAILABLE 阻断（P0-06）。
    """
    if quote.limit_up_price is not None or quote.last <= 0 or quote.pct_chg is None:
        return quote
    # pct_chg 在 [-90,-100] 区间（如 -99.99）说明前收近 0 或数据异常：用它反推 prev_close 会爆炸。
    # 与 marketdata/rules.apply_rule_price_limits 的 -90 挡保持一致。
    if quote.pct_chg <= -90:
        return quote
    prev_close = quote.last / (1 + quote.pct_chg / 100)
    limit_pct = board_limit_pct(quote.code, quote.exchange)
    quote.limit_up_price = _round_half_up(prev_close * (1 + limit_pct / 100), 2)
    quote.limit_up_source = (
        f"derived:prev_close=last/(1+pct_chg/100)={prev_close:.4f};"
        f"limit_up=round_half_up(prev*{limit_pct/100+1:.2f},2)"
    )
    return quote


def active_buy_qty(open_orders: list[MxOrder], code: str) -> int:
    """同一证券的活动买入委托剩余数量之和（P0-05：目标仓位扣减项）。"""
    total = 0
    for order in open_orders:
        if order.code == code and order.side == "buy" and order.status in ACTIVE_ORDER_STATUSES:
            total += order_remaining_qty(order)
    return total


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

    for cand in candidates:
        code = cand.stock_code
        quote = quotes.get(code)

        # 板块闸
        board = cand.board_blocked(config.allow_chinext, config.allow_star)
        if board:
            intents.append(_noop(code, board, cand.source))
            continue

        # 行情闸
        if quote is None:
            intents.append(_noop(code, "QUOTE_MISSING", cand.source))
            continue
        if quote.validity == QuoteValidity.STALE:
            intents.append(_noop(code, "QUOTE_STALE", cand.source))
            continue
        if quote.validity == QuoteValidity.NOT_TRADABLE or (quote.volume or 0) == 0:
            intents.append(_noop(code, "NOT_TRADABLE", cand.source))
            continue
        # P1-02：INVALID（含 FUTURE_TIMESTAMP/解析失败）绝不生成 BUY
        if quote.validity != QuoteValidity.VALID:
            intents.append(_noop(code, f"QUOTE_{quote.validity.value}", cand.source))
            continue
        if quote.error or quote.last <= 0:
            intents.append(_noop(code, "QUOTE_MISSING", cand.source))
            continue

        # 涨停价推导（P0-06 硬闸输入；缺失时由风控 LIMIT_DATA_UNAVAILABLE 阻断）
        quote = derive_limit_up_price(quote)
        # 弱涨停信号（辅助）：T 日涨停 + 今日涨幅≥9.8%
        if cand.is_limit_up and (quote.pct_chg or 0) >= _WEAK_LIMIT_PCT:
            intents.append(_noop(code, "LIMIT_LOCKED", cand.source))
            continue

        # buyability 差的默认放弃（中文枚举，T1CandidateService 产出）
        if cand.buyability == BUYABILITY_BLOCKED:
            intents.append(_noop(code, "BUYABILITY_BLOCKED", cand.source))
            continue

        # 仓位增量（P0-05）：desired = target - current - active_buy，禁止把目标当新增
        target = target_qty(balance.avail_balance, quote.last, config.max_name_weight)
        current = positions_qty.get(code, 0)
        active_buy = active_buy_qty(open_orders, code)
        if target <= 0:
            intents.append(_noop(code, "QTY_TOO_SMALL", cand.source))
            continue
        if current >= target:
            intents.append(_noop(code, "ALREADY_FILLED", cand.source))
            continue
        if active_buy > 0:
            # 已有活动买单：本轮不重复下单（剩余目标由既有委托补足）
            intents.append(_noop(code, "DUPLICATE_ORDER", cand.source))
            continue
        desired = max(target - current - active_buy, 0)
        if desired < LOT_SIZE:
            intents.append(_noop(code, "QTY_TOO_SMALL", cand.source))
            continue

        # 目标数量（增量口径 + 资金可买上限）
        qty = min(desired, affordable_qty(balance.avail_balance, quote.last))
        if qty < LOT_SIZE:
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
