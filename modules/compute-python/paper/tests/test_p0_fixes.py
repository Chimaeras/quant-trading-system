"""P0 修复验收测试：官方字段解析 / fail-closed / stop_run / 涨停硬闸 / 副作用保护。

全部离线；所有 stub 的 trade/cancel 默认不产生真实副作用。
"""
from __future__ import annotations

from datetime import datetime

import pytest

from paper.broker_client import (
    ACTIVE_ORDER_STATUSES,
    BrokerClient,
    order_remaining_qty,
    parse_order_item,
    parse_position_item,
    scaled_price,
)
from paper.config import PaperConfig
from paper.decide import active_buy_qty, derive_limit_up_price
from paper.models import Balance, Candidate, Intent, IntentType, MxOrder, Quote, QuoteValidity, RiskStatus
from paper.mx_result import MxErrorKind, MxResult
from paper.risk import check_intent

T = datetime(2026, 8, 19, 9, 35)

# 官方格式非空样本（脱敏，字段形状以修复清单 §4 为准）
POSITION_OFFICIAL = {
    "secCode": "600519", "secName": "贵州茅台", "count": 200, "availCount": 200,
    "value": 259600.0, "costPrice": 125000, "costPriceDec": 3,
    "price": 129800, "priceDec": 3, "profit": 960.0,
}
ORDER_OFFICIAL = {
    "id": "ORD-88", "secCode": "600519", "drt": 1, "price": 100200, "priceDec": 3,
    "count": 100, "tradeCount": 0, "status": 2, "time": "2026-08-19 09:31:00",
}


class TestOfficialParsing:
    """P0-01：官方字段严格解析。"""

    def test_position_official_fields(self):
        p = parse_position_item(dict(POSITION_OFFICIAL))
        assert p.code == "600519"
        assert p.qty == 200 and p.avail_qty == 200
        assert p.cost == pytest.approx(125.0)  # 125000 / 10^3
        assert p.last == pytest.approx(129.8)
        assert p.market_value == pytest.approx(259600.0)
        assert p.profit == pytest.approx(960.0)

    def test_order_official_fields(self):
        o = parse_order_item(dict(ORDER_OFFICIAL))
        assert o.order_id == "ORD-88"
        assert o.code == "600519"
        assert o.side == "buy"  # drt=1
        assert o.price == pytest.approx(100.2)
        assert o.qty == 100
        assert o.filled_qty == 0
        assert o.status == "2"

    def test_scaled_price(self):
        assert scaled_price(100200, 3) == pytest.approx(100.2)
        assert scaled_price(1002, 1) == pytest.approx(100.2)
        assert scaled_price(1002, 0) == pytest.approx(1002.0)

    def test_drt_sell_and_invalid(self):
        item = dict(ORDER_OFFICIAL, drt=2)
        assert parse_order_item(item).side == "sell"
        with pytest.raises(ValueError, match="drt"):
            parse_order_item(dict(ORDER_OFFICIAL, drt=3))

    def test_all_status_enum_valid(self):
        for status in range(1, 11):
            o = parse_order_item(dict(ORDER_OFFICIAL, status=status))
            assert o.status == str(status)
        with pytest.raises(ValueError, match="status"):
            parse_order_item(dict(ORDER_OFFICIAL, status=99))

    def test_malformed_position_missing_code(self):
        with pytest.raises(ValueError, match="secCode"):
            parse_position_item({k: v for k, v in POSITION_OFFICIAL.items() if k != "secCode"})

    def test_malformed_order_missing_id(self):
        with pytest.raises(ValueError, match="id"):
            parse_order_item({k: v for k, v in ORDER_OFFICIAL.items() if k != "id"})

    def test_active_status_set(self):
        assert ACTIVE_ORDER_STATUSES == {"1", "2", "3", "5", "6", "10"}

    def test_order_remaining_qty(self):
        base = dict(ORDER_OFFICIAL, count=100, tradeCount=40, status=3)
        o = parse_order_item(base)
        assert order_remaining_qty(o) == 60
        assert order_remaining_qty(parse_order_item(dict(base, status=4))) == 0
        assert order_remaining_qty(parse_order_item(dict(base, status=8))) == 0


class _FakeHttp:
    """离线 HTTP stub：返回预置 payload。"""

    def __init__(self, payload):
        self._payload = payload

    def post(self, endpoint, body, *, retryable=False, run_id="adhoc"):
        return MxResult.from_json(endpoint, self._payload)


class TestBrokerFailClosed:
    """P0-01/P0-02：malformed 整体 PARSE_ERROR，绝不返回 UNKNOWN 条目。"""

    def test_positions_malformed_entry_blocks(self):
        http = _FakeHttp({"code": "200", "message": "成功",
                          "data": {"posList": [dict(POSITION_OFFICIAL, secCode="")]}})
        broker = BrokerClient(http)
        positions, result = broker.positions()
        assert positions is None
        assert result.kind == MxErrorKind.PARSE_ERROR

    def test_orders_malformed_entry_blocks(self):
        http = _FakeHttp({"code": "200", "message": "成功",
                          "data": {"orders": [dict(ORDER_OFFICIAL, count=0)]}})
        broker = BrokerClient(http)
        orders, result = broker.orders()
        assert orders is None
        assert result.kind == MxErrorKind.PARSE_ERROR

    def test_positions_missing_poslist_blocks(self):
        http = _FakeHttp({"code": "200", "message": "成功", "data": {}})
        broker = BrokerClient(http)
        positions, result = broker.positions()
        assert positions is None
        assert result.kind == MxErrorKind.PARSE_ERROR

    def test_positions_ok(self):
        http = _FakeHttp({"code": "200", "message": "成功", "data": {"posList": [POSITION_OFFICIAL]}})
        broker = BrokerClient(http)
        positions, result = broker.positions()
        assert result.ok
        assert len(positions) == 1
        assert positions[0].code == "600519"


class TestTradeCancelValidation:
    """P1-12 输入校验（副作用接口非法输入在 HTTP 前拒绝）。"""

    def _broker(self):
        return BrokerClient(_FakeHttp({}))

    def test_invalid_side_raises(self):
        with pytest.raises(ValueError, match="side"):
            self._broker().trade("600519", "unknown", 100, price=100.0)

    def test_invalid_code_raises(self):
        with pytest.raises(ValueError, match="stock_code"):
            self._broker().trade("60051", "buy", 100, price=100.0)

    def test_odd_lot_raises(self):
        with pytest.raises(ValueError, match="qty"):
            self._broker().trade("600519", "buy", 150, price=100.0)

    def test_cancel_without_code_raises(self):
        with pytest.raises(ValueError, match="stock_code"):
            self._broker().cancel_order("ORD-1", "")


# ---------------------------------------------------------------- runner 集成

from paper.runner import PaperRunner  # noqa: E402


class GuardBroker:
    """副作用保护 stub：trade/cancel 默认抛异常，仅显式放开才可计数。"""

    def __init__(self, *, allow_trade=False, balance=None, orders=None, positions=None):
        self.allow_trade = allow_trade
        self.trade_calls: list[dict] = []
        self._balance = balance or Balance(200_000, 200_000)
        self._orders = orders or []
        self._positions = positions or []
        self.fail_positions = False
        self.fail_orders = False
        self.fail_balance = False
        self.fail_post_query = False

    def balance(self, run_id="adhoc"):
        if self.fail_balance or (self.fail_post_query and self.trade_calls):
            return None, MxResult(endpoint="/balance", ok=False, code="500",
                                  message="失败", kind=MxErrorKind.HTTP_ERROR)
        return self._balance, None

    def positions(self, run_id="adhoc"):
        if self.fail_positions or (self.fail_post_query and self.trade_calls):
            return None, MxResult(endpoint="/positions", ok=False, code="500",
                                  message="失败", kind=MxErrorKind.HTTP_ERROR)
        return self._positions, None

    def orders(self, run_id="adhoc"):
        if self.fail_orders or (self.fail_post_query and self.trade_calls):
            return None, MxResult(endpoint="/orders", ok=False, code="500",
                                  message="失败", kind=MxErrorKind.HTTP_ERROR)
        return list(self._orders), None

    def trade(self, code, side, qty, *, use_market_price=False, price=None, run_id="adhoc"):
        if not self.allow_trade:
            raise AssertionError("副作用保护：测试未显式允许 trade 调用")
        self.trade_calls.append({"code": code, "side": side, "qty": qty, "price": price})
        return MxResult(endpoint="/trade", ok=True, code="200", message="成功",
                        data={"orderId": f"ORD-{len(self.trade_calls)}"})


class GuardQuoteClient:
    def __init__(self, quotes):
        self._quotes = quotes

    def snapshot(self, code, name="", *, run_id="adhoc", received_at=None):
        q = self._quotes.get(code)
        if q is None:
            return None, MxResult(endpoint="/query", ok=False, code="E", message="无行情",
                                  kind=MxErrorKind.BIZ_ERROR)
        return q, None


def _config(**kw) -> PaperConfig:
    base = dict(api_key="k", max_name_weight=0.20)
    base.update(kw)
    return PaperConfig(**base)


def _quote(code="600519", last=100.0, **kw) -> Quote:
    defaults = dict(code=code, name="t", exchange="SH", last=last, pct_chg=1.0,
                    volume=1000, market_time=T, received_at=T, validity=QuoteValidity.VALID)
    defaults.update(kw)
    return Quote(**defaults)


def _cand(code="600519", **kw):
    defaults = dict(stock_code=code, stock_name="t", exchange_type="SH", source="file")
    defaults.update(kw)
    return Candidate(**defaults)


def _runner(tmp_path, quotes, broker, **cfg):
    from paper.trading_calendar import TradingCalendarService

    return PaperRunner(_config(**cfg), GuardQuoteClient(quotes), broker, log_base=tmp_path, now=T,
                       trading_calendar=TradingCalendarService())


class TestFailClosed:
    """P0-02：任一准备/收尾查询失败 → trade_calls=0 或结果非 SUCCESS。"""

    CANDS = [_cand()]

    def test_positions_failed_blocks(self, tmp_path):
        broker = GuardBroker()
        broker.fail_positions = True
        runner = _runner(tmp_path, {"600519": _quote()}, broker)
        run, abort = runner.dry_run("2026-08-18", candidates_override=self.CANDS)
        assert abort == "POSITIONS_FAILED"
        assert broker.trade_calls == []

    def test_orders_failed_blocks(self, tmp_path):
        broker = GuardBroker()
        broker.fail_orders = True
        runner = _runner(tmp_path, {"600519": _quote()}, broker)
        run, abort = runner.dry_run("2026-08-18", candidates_override=self.CANDS)
        assert abort == "ORDERS_FAILED"
        assert broker.trade_calls == []

    def test_balance_failed_blocks(self, tmp_path):
        broker = GuardBroker()
        broker.fail_balance = True
        runner = _runner(tmp_path, {"600519": _quote()}, broker)
        run, abort = runner.dry_run("2026-08-18", candidates_override=self.CANDS)
        assert abort == "BALANCE_FAILED"
        assert broker.trade_calls == []

    def test_post_query_failed_not_success(self, tmp_path):
        """提交后查询失败 → abort=POST_QUERY_FAILED，绝不记为成功。"""
        broker = GuardBroker(allow_trade=True)
        broker.fail_post_query = True
        runner = _runner(tmp_path, {"600519": _quote()}, broker)
        preview, _ = runner.dry_run("2026-08-18", candidates_override=self.CANDS)
        run, abort = runner.run_confirm(
            "2026-08-18", runner.preview_token(), preview_run_id=preview.run_id
        )
        assert len(broker.trade_calls) == 1  # 提交发生在查询失败之前
        assert abort == "POST_QUERY_FAILED"
        assert any("POST_QUERY_FAILED" in e for e in run.errors)
        assert run.account_after == {}


class TestStopRun:
    """P0-03：停止性错误后剩余意图全部 SKIPPED_RUN_STOPPED。"""

    def test_stop_run_skips_remaining(self, tmp_path):
        broker = GuardBroker(allow_trade=True)
        cands = [_cand("600519"), _cand("000001", exchange_type="SZ")]
        quotes = {"600519": _quote("600519"), "000001": _quote("000001", last=10.0, exchange="SZ")}

        stop_result = MxResult(endpoint="/trade", ok=False, code="113", message="额度不足",
                               kind=MxErrorKind.QUOTA_EXHAUSTED)

        runner = _runner(tmp_path, quotes, broker)

        def _trade(code, side, qty, *, use_market_price=False, price=None, run_id="adhoc"):
            broker.trade_calls.append({"code": code, "qty": qty})
            return stop_result

        broker.trade = _trade
        preview, _ = runner.dry_run("2026-08-18", candidates_override=cands)
        assert sum(1 for i in preview.intents if i["intent_type"] == "BUY_LIMIT") == 2
        run, abort = runner.run_confirm(
            "2026-08-18", runner.preview_token(), preview_run_id=preview.run_id
        )
        assert len(broker.trade_calls) == 1  # 只调用一次 trade
        skipped = [i for i in run.intents if "SKIPPED_RUN_STOPPED" in i.get("reason_codes", [])]
        assert len(skipped) == 1
        assert any(e.startswith("STOP_RUN") for e in run.errors)


class TestLimitUpHardGate:
    """P0-06：涨停价硬闸。"""

    def _intent(self, price=100.2, qty=400):
        return Intent(stock_code="600519", intent_type=IntentType.BUY_LIMIT, qty=qty,
                      limit_price=price, candidate_source="file")

    def _check(self, quote, intent=None):
        return check_intent(
            intent or self._intent(), quote,
            Balance(total_assets=200_000, avail_balance=200_000), [], _config(),
            0, {"600519"}, confirmed=True, positions_qty={},
        )

    def test_price_at_limit_up_rejected_low_pct(self):
        """pct_chg 低于 9% 但买入价达到涨停价 → LIMIT_UP_NOT_BUYABLE。"""
        quote = _quote(pct_chg=1.0)
        quote = derive_limit_up_price(quote)  # 100/1.01*1.1 ≈ 108.91
        intent = self._intent(price=108.91)
        out = self._check(quote, intent)
        assert out.risk_reject_reason == "LIMIT_UP_NOT_BUYABLE"

    def test_limit_data_unavailable_rejected(self):
        """pct_chg 缺失 → limit_up_price 为 None → LIMIT_DATA_UNAVAILABLE。"""
        quote = _quote(pct_chg=None)
        quote = derive_limit_up_price(quote)
        assert quote.limit_up_price is None
        out = self._check(quote)
        assert out.risk_reject_reason == "LIMIT_DATA_UNAVAILABLE"

    def test_normal_price_passes(self):
        quote = derive_limit_up_price(_quote(pct_chg=1.0))
        out = self._check(quote, self._intent(price=100.2))
        assert out.risk_status == RiskStatus.PASS

    def test_derive_records_source(self):
        quote = derive_limit_up_price(_quote(pct_chg=1.0))
        assert quote.limit_up_price == pytest.approx(108.91)
        assert quote.limit_up_source.startswith("derived:")

    def test_sell_limit_up_not_blocked(self):
        """涨停硬闸只禁买入：卖出意图不经买入闸（NOOP 直通，本测试锁定语义）。"""
        sell = Intent(stock_code="600519", intent_type=IntentType.NOOP, side="sell")
        out = self._check(derive_limit_up_price(_quote()), sell)
        assert out.risk_status == RiskStatus.PASS


class TestActiveBuyAccounting:
    """P0-05 辅助：活动买单剩余数量统计。"""

    def test_active_buy_qty_only_active(self):
        orders = [
            parse_order_item(dict(ORDER_OFFICIAL, id="1", count=100, tradeCount=100, status=4)),
            parse_order_item(dict(ORDER_OFFICIAL, id="2", count=200, tradeCount=50, status=3)),
            parse_order_item(dict(ORDER_OFFICIAL, id="3", drt=2, count=100, tradeCount=0, status=2)),
        ]
        assert active_buy_qty(orders, "600519") == 150
