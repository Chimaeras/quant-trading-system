"""decide.py 纯函数测试：V1 决策伪代码逐条覆盖（文档验收 A5-A8、A13）。"""
from __future__ import annotations

from datetime import datetime

import pytest

from paper.config import PaperConfig
from paper.decide import (
    affordable_qty,
    decide,
    in_trading_window,
    limit_price_for_buy,
    target_qty,
)
from paper.models import (
    Balance,
    Candidate,
    IntentType,
    MxOrder,
    Quote,
    QuoteValidity,
)

TRADE_TIME = datetime(2026, 8, 19, 9, 35)  # 周三连续竞价


def _config(**overrides) -> PaperConfig:
    base = dict(
        api_key="k", halt=False, allow_chinext=False, allow_star=False,
        max_name_weight=0.20, slippage_bps=20, max_trades_per_run=5,
    )
    base.update(overrides)
    return PaperConfig(**base)


def _quote(code="600519", last=100.0, **kw) -> Quote:
    defaults = dict(
        code=code, name="测试", exchange="SH", last=last, pct_chg=1.0,
        volume=1_000_000, volume_ratio=1.2, market_time=TRADE_TIME,
        received_at=TRADE_TIME, validity=QuoteValidity.VALID,
    )
    defaults.update(kw)
    return Quote(**defaults)


def _cand(code="600519", **kw) -> Candidate:
    defaults = dict(stock_code=code, stock_name="测试", exchange_type="SH", source="file")
    defaults.update(kw)
    return Candidate(**defaults)


def _balance(avail=200_000.0, total=200_000.0) -> Balance:
    return Balance(total_assets=total, avail_balance=avail)


def _run(cands, quotes, balance=None, positions=None, orders=None, config=None, now=TRADE_TIME):
    return decide(
        cands, quotes, balance or _balance(), positions or {}, orders or [],
        config or _config(), now,
    )


class TestTimeWindow:
    """A6：非交易时段全部 NOOP/TIME_WINDOW。"""

    def test_after_hours(self):
        intents = _run([_cand()], {"600519": _quote()}, now=datetime(2026, 8, 19, 16, 0))
        assert intents[0].intent_type == IntentType.NOOP
        assert "TIME_WINDOW" in intents[0].reason_codes

    def test_weekend(self):
        intents = _run([_cand()], {"600519": _quote()}, now=datetime(2026, 8, 22, 10, 0))
        assert "TIME_WINDOW" in intents[0].reason_codes

    def test_window_check(self):
        assert in_trading_window(datetime(2026, 8, 19, 9, 30)) is True
        assert in_trading_window(datetime(2026, 8, 19, 11, 30)) is False
        assert in_trading_window(datetime(2026, 8, 19, 13, 0)) is True
        assert in_trading_window(datetime(2026, 8, 19, 15, 0)) is False


class TestHalt:
    """A13：PAPER_HALT=1 禁止买入。"""

    def test_halt(self):
        intents = _run([_cand()], {"600519": _quote()}, config=_config(halt=True))
        assert intents[0].intent_type == IntentType.NOOP
        assert "HALT" in intents[0].reason_codes


class TestPerCandidate:
    """逐条候选闸。"""

    def test_buy_limit_happy_path(self):
        intents = _run([_cand()], {"600519": _quote(last=100.0)})
        assert intents[0].intent_type == IntentType.BUY_LIMIT
        assert intents[0].qty == 400  # 200000*0.2/100/100=4 手
        assert intents[0].limit_price == pytest.approx(100.2)  # 100*(1+20bps)，沪2位

    def test_board_blocked_chinext(self):
        """A8：创业板默认拒绝。"""
        intents = _run(
            [_cand(code="300750", exchange_type="SZ")],
            {"300750": _quote(code="300750", exchange="SZ")},
        )
        assert intents[0].reason_codes == ["BOARD_BLOCKED_CHINEXT"]

    def test_quote_missing(self):
        intents = _run([_cand()], {})
        assert intents[0].reason_codes == ["QUOTE_MISSING"]

    def test_quote_stale(self):
        intents = _run([_cand()], {"600519": _quote(validity=QuoteValidity.STALE)})
        assert intents[0].reason_codes == ["QUOTE_STALE"]

    def test_not_tradable_zero_volume(self):
        intents = _run([_cand()], {"600519": _quote(volume=0, validity=QuoteValidity.NOT_TRADABLE)})
        assert intents[0].reason_codes == ["NOT_TRADABLE"]

    def test_limit_locked_weak_signal(self):
        intents = _run(
            [_cand(is_limit_up=True)],
            {"600519": _quote(pct_chg=9.9)},
        )
        assert intents[0].reason_codes == ["LIMIT_LOCKED"]

    def test_buyability_blocked(self):
        intents = _run([_cand(buyability="大概率买不进")], {"600519": _quote()})
        assert intents[0].reason_codes == ["BUYABILITY_BLOCKED"]

    def test_already_filled(self):
        intents = _run([_cand()], {"600519": _quote()}, positions={"600519": 400})
        assert intents[0].reason_codes == ["ALREADY_FILLED"]

    def test_duplicate_order(self):
        orders = [MxOrder(order_id="1", code="600519", side="buy", price=100.0, qty=100,
                          filled_qty=0, status="2")]
        intents = _run([_cand()], {"600519": _quote()}, orders=orders)
        assert intents[0].reason_codes == ["DUPLICATE_ORDER"]

    def test_filled_order_not_duplicate(self):
        """已成的历史委托（status=4）不阻止新买入。"""
        orders = [MxOrder(order_id="1", code="600519", side="buy", price=100.0, qty=100,
                          filled_qty=100, status="4")]
        intents = _run([_cand()], {"600519": _quote()}, orders=orders)
        assert intents[0].intent_type == IntentType.BUY_LIMIT

    def test_increment_buy(self):
        """P0-05：目标 400 股、已有 100 股 → 只买 300 股增量。"""
        intents = _run([_cand()], {"600519": _quote()}, positions={"600519": 100})
        assert intents[0].intent_type == IntentType.BUY_LIMIT
        assert intents[0].qty == 300

    def test_active_buy_covers_target_no_buy(self):
        """P0-05：活动买单已覆盖目标 → 不再生成买入。"""
        orders = [MxOrder(order_id="1", code="600519", side="buy", price=100.0, qty=400,
                          filled_qty=0, status="2")]
        intents = _run([_cand()], {"600519": _quote()}, orders=orders)
        assert intents[0].intent_type == IntentType.NOOP
        assert intents[0].reason_codes == ["DUPLICATE_ORDER"]

    def test_qty_too_small(self):
        """资金不够一手（茅台 1298 元/股 × 20% 权重 < 100 股）。"""
        intents = _run([_cand()], {"600519": _quote(last=150000.0)})
        assert intents[0].reason_codes == ["QTY_TOO_SMALL"]


class TestEmptyCandidates:
    """A5：空候选是成功 NOOP。"""

    def test_empty(self):
        assert _run([], {}) == []


class TestQtyMath:
    """目标股数与限价计算。"""

    def test_target_qty(self):
        assert target_qty(200_000, 100.0, 0.20) == 400
        assert target_qty(200_000, 1298.0, 0.20) == 0  # 40000/1298=30.8 股 → 0 手
        assert target_qty(200_000, 50.0, 0.20) == 800

    def test_affordable_qty(self):
        assert affordable_qty(200_000, 100.0) == 1900  # 留 5 元佣金缓冲
        assert affordable_qty(100, 100.0) == 0

    def test_limit_price_decimals(self):
        """沪 2 位、深 3 位小数。"""
        assert limit_price_for_buy(100.0, "SH", 20) == pytest.approx(100.2)
        # 20bps=0.2% 上浮：33.3333*1.002=33.39997，深市 3 位取整 33.4
        assert limit_price_for_buy(33.3333, "SZ", 20) == pytest.approx(33.4)
