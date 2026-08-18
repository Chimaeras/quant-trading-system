"""risk.py 风控闸测试（文档验收 A7、A9 等）。"""
from __future__ import annotations

from datetime import datetime

import pytest

from paper.config import PaperConfig
from paper.decide import decide
from paper.models import Balance, Candidate, Intent, IntentType, MxOrder, Quote, QuoteValidity, RiskStatus
from paper.risk import check_intent, estimate_commission

T = datetime(2026, 8, 19, 9, 35)


def _config(**kw) -> PaperConfig:
    base = dict(api_key="k", max_name_weight=0.20, max_trades_per_run=5)
    base.update(kw)
    return PaperConfig(**base)


def _quote(last=100.0) -> Quote:
    return Quote(code="600519", name="t", exchange="SH", last=last, pct_chg=1.0,
                 volume=1000, market_time=T, received_at=T, validity=QuoteValidity.VALID,
                 limit_up_price=110.0, limit_up_source="test")


def _intent(qty=400, price=100.2) -> Intent:
    return Intent(stock_code="600519", intent_type=IntentType.BUY_LIMIT, qty=qty,
                  limit_price=price, candidate_source="file")


def _check(intent, *, confirmed=True, quote=None, balance=None, orders=None,
           config=None, trades=0, whitelist=None):
    return check_intent(
        intent, quote if quote is not None else _quote(),
        balance or Balance(total_assets=200_000, avail_balance=200_000),
        orders or [], config or _config(), trades,
        whitelist if whitelist is not None else {"600519"},
        confirmed=confirmed,
    )


class TestGates:
    def test_pass(self):
        intent = _check(_intent())
        assert intent.risk_status == RiskStatus.PASS
        assert intent.intent_type == IntentType.BUY_LIMIT

    def test_confirm_required(self):
        """A9：无 --confirm 拒绝。"""
        intent = _check(_intent(), confirmed=False)
        assert intent.risk_status == RiskStatus.REJECT
        assert intent.risk_reject_reason == "CONFIRM_REQUIRED"

    def test_lot_size(self):
        """A7：qty=150 拒绝。"""
        intent = _check(_intent(qty=150))
        assert intent.risk_reject_reason == "LOT_SIZE_VIOLATION"

    def test_whitelist(self):
        intent = _check(_intent(), whitelist=set())
        assert intent.risk_reject_reason == "NOT_IN_WHITELIST"

    def test_price_invalid(self):
        intent = _check(_intent(price=None))
        assert intent.risk_reject_reason == "PRICE_INVALID"

    def test_quote_not_valid(self):
        intent = _check(_intent(), quote=_quote().validity.__class__ and
                        Quote(code="600519", name="t", exchange="SH", last=100.0,
                              validity=QuoteValidity.STALE))
        assert intent.risk_reject_reason == "QUOTE_NOT_VALID"

    def test_near_limit_up(self):
        q = _quote()
        q.pct_chg = 9.5
        intent = _check(_intent(), quote=q)
        assert intent.risk_reject_reason == "NEAR_LIMIT_UP"

    def test_insufficient_funds(self):
        intent = _check(_intent(), balance=Balance(total_assets=10_000, avail_balance=10_000))
        assert intent.risk_reject_reason == "INSUFFICIENT_FUNDS"

    def test_name_weight(self):
        intent = _check(_intent(qty=600))  # 60000 > 200000*0.2*1.05=42000
        assert intent.risk_reject_reason == "NAME_WEIGHT_EXCEEDED"

    def test_max_trades(self):
        intent = _check(_intent(), trades=5)
        assert intent.risk_reject_reason == "MAX_TRADES_PER_RUN"

    def test_duplicate_order(self):
        orders = [MxOrder(order_id="1", code="600519", side="buy", price=100.2, qty=400,
                          filled_qty=0, status="2")]
        intent = _check(_intent(), orders=orders)
        assert intent.risk_reject_reason == "DUPLICATE_ORDER"

    def test_filled_history_order_not_duplicate(self):
        """已成（4）/已撤（8）/废单（9）不阻止新买入。"""
        for status in ("4", "8", "9"):
            orders = [MxOrder(order_id="1", code="600519", side="buy", price=100.2, qty=400,
                              filled_qty=0, status=status)]
            intent = _check(_intent(), orders=orders)
            assert intent.risk_status == RiskStatus.PASS, status

    def test_halt(self):
        intent = _check(_intent(), config=_config(halt=True))
        assert intent.risk_reject_reason == "HALT"


class TestCommission:
    def test_min_commission(self):
        assert estimate_commission(1000.0) == 5.0

    def test_rate_commission(self):
        assert estimate_commission(100_000.0) == pytest.approx(30.0)


class TestNoopPassThrough:
    def test_noop_untouched(self):
        noop = Intent(stock_code="x", intent_type=IntentType.NOOP)
        out = _check(noop)
        assert out.risk_status == RiskStatus.PASS


class TestIntegrationDecideRisk:
    """decide → risk 集成：产出意图过闸后保留。"""

    def test_full_chain(self):
        config = _config()
        cand = Candidate(stock_code="600519", stock_name="t", exchange_type="SH", source="file")
        intents = decide([cand], {"600519": _quote()}, Balance(200_000, 200_000), {}, [], config, T)
        assert intents[0].intent_type == IntentType.BUY_LIMIT
        out = check_intent(intents[0], _quote(), Balance(200_000, 200_000), [], config, 0, {"600519"}, confirmed=True)
        assert out.risk_status == RiskStatus.PASS
