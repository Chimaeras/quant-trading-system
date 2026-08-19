"""盘中 watch 核心验收：全部离线，不调用 trade/cancel。"""
from __future__ import annotations

from datetime import datetime, timedelta
from pathlib import Path

from paper.config import PaperConfig
from paper.models import Balance, Candidate, Intent, IntentType, MxOrder, Position, Quote, QuoteValidity, RiskStatus
from paper.risk import check_watch_intent
from paper.watch.bar_store import BarStore
from paper.watch.cooldown import CooldownBook
from paper.watch.kline_client import _parse_eastmoney_klines, parse_kline_payload
from paper.trading_calendar import TradingCalendarService
from paper.watch.loop import WatchLoop
from paper.watch.models import WatchBar
from paper.watch.signal_adapter import SignalAdapter


T0 = datetime(2026, 8, 18, 9, 30)


def _bars(code: str, closes: list[float], period: int = 5) -> list[WatchBar]:
    return [
        WatchBar(
            code=code,
            period_min=period,
            open=value,
            high=value,
            low=value,
            close=value,
            volume=1000,
            start_ts=T0 + timedelta(minutes=period * index),
            end_ts=T0 + timedelta(minutes=period * (index + 1)),
            closed=True,
        )
        for index, value in enumerate(closes)
    ]


def _config(**kwargs) -> PaperConfig:
    data = dict(
        api_key="test-key",
        min_cash_reserve=0,
        max_total_weight=1.0,
        max_positions=5,
        index_circuit_breaker=False,
    )
    data.update(kwargs)
    return PaperConfig(**data)


def _quote(code="600519", last=100.0, **kwargs) -> Quote:
    data = dict(
        code=code,
        name="测试",
        exchange="SH",
        last=last,
        pct_chg=1.0,
        volume=1000,
        market_time=T0,
        received_at=T0,
        validity=QuoteValidity.VALID,
        limit_up_price=110.0,
    )
    data.update(kwargs)
    return Quote(**data)


def test_bar_store_does_not_emit_forming_bar_and_emits_once_on_bucket_switch():
    store = BarStore(5)
    first = store.update_snapshot("600519", _quote(), T0 + timedelta(minutes=1))
    assert first.new_closed == []
    second = store.update_snapshot("600519", _quote(last=101), T0 + timedelta(minutes=4))
    assert second.new_closed == []
    third = store.update_snapshot("600519", _quote(last=102), T0 + timedelta(minutes=5))
    assert len(third.new_closed) == 1
    assert third.new_closed[0].closed is True
    fourth = store.update_snapshot("600519", _quote(last=103), T0 + timedelta(minutes=5, seconds=30))
    assert fourth.new_closed == []


def test_signal_adapter_matches_existing_ma_cross_and_externalizes_position():
    flat = [10.0] * 25
    ramp = [10.0 + i * 0.3 for i in range(10)]
    closes = flat + ramp
    adapter = SignalAdapter("MA_CROSS", ma_short=5, ma_long=20)
    bars = _bars("600519", closes)
    adapter.seed("600519", bars[:25])
    signals = [adapter.on_closed_bar("600519", bar, position_qty=0) for bar in bars[25:]]
    signal = next(item for item in signals if item.action == "buy")
    assert signal.action == "buy"
    assert signal.reason_codes == ["MA_GOLDEN", "BAR_CLOSED"]

    sell_adapter = SignalAdapter("MA_CROSS", ma_short=5, ma_long=20)
    sell_closes = [10.0 + i * 0.3 for i in range(30)]
    sell_closes += [sell_closes[-1] - i * 0.4 for i in range(1, 11)]
    sell_bars = _bars("600519", sell_closes)
    sell_adapter.seed("600519", sell_bars[:30])
    sell_signals = [sell_adapter.on_closed_bar("600519", bar, position_qty=100) for bar in sell_bars[30:]]
    sell_signal = next(item for item in sell_signals if item.action == "sell")
    assert sell_signal.action == "sell"
    assert sell_signal.reason_codes == ["MA_DEATH", "BAR_CLOSED"]


def test_cooldown_only_blocks_reverse_signal():
    book = CooldownBook(15)
    book.record("600519", "buy", T0)
    assert book.can_trade("600519", "buy", T0 + timedelta(minutes=1))
    assert not book.can_trade("600519", "sell", T0 + timedelta(minutes=1))
    assert book.can_trade("600519", "sell", T0 + timedelta(minutes=15))


def test_sell_risk_allows_sell_during_halt_but_caps_available_quantity():
    position = Position("600519", "测试", 500, 300, 90, 100, 50000, 500)
    intent = Intent("600519", IntentType.SELL_LIMIT, side="sell", qty=300, limit_price=99)
    out = check_watch_intent(
        intent,
        _quote(last=100),
        Balance(total_assets=100000, avail_balance=10000),
        {"600519": position},
        [],
        _config(halt=True),
    )
    assert out.risk_status == RiskStatus.PASS

    too_many = Intent("600519", IntentType.SELL_LIMIT, side="sell", qty=400, limit_price=99)
    out2 = check_watch_intent(
        too_many,
        _quote(last=100),
        Balance(total_assets=100000, avail_balance=10000),
        {"600519": position},
        [],
        _config(halt=True),
    )
    assert out2.risk_reject_reason == "SELL_QTY_EXCEEDS_AVAILABLE"


def test_live_buy_rejects_derived_limit_price():
    from paper.decide import derive_limit_up_price

    quote = derive_limit_up_price(_quote(pct_chg=1.0, limit_up_price=None))
    intent = Intent("600519", IntentType.BUY_LIMIT, side="buy", qty=100, limit_price=100.2)
    out = check_watch_intent(
        intent,
        quote,
        Balance(total_assets=100000, avail_balance=100000),
        {},
        [],
        _config(),
        strict_limit_data=True,
    )
    assert out.risk_reject_reason == "LIMIT_DATA_UNAVAILABLE"


def test_live_buy_accepts_official_limit_price():
    quote = _quote(limit_up_source="mx_query:limit_up_price")
    intent = Intent("600519", IntentType.BUY_LIMIT, side="buy", qty=100, limit_price=100.2)
    out = check_watch_intent(
        intent,
        quote,
        Balance(total_assets=100000, avail_balance=100000),
        {},
        [],
        _config(),
        strict_limit_data=True,
    )
    assert out.risk_status == RiskStatus.PASS


class _WatchBroker:
    def __init__(self):
        self.trade_calls = []
        self.balance_value = Balance(total_assets=100000, avail_balance=100000)

    def balance(self, run_id="adhoc"):
        return self.balance_value, None

    def positions(self, run_id="adhoc"):
        return [], None

    def orders(self, run_id="adhoc"):
        return [], None

    def trade(self, *args, **kwargs):
        self.trade_calls.append((args, kwargs))
        raise AssertionError("dry-run 不得调用 trade")


class _WatchQuote:
    def __init__(self):
        self.calls = 0

    def snapshot(self, code, name="", *, run_id="adhoc", received_at=None):
        from paper.mx_result import MxResult

        self.calls += 1
        return _quote(received_at=received_at, market_time=received_at), MxResult(
            endpoint="/query", ok=True, code="0", message="ok"
        )


def test_watch_loop_dry_run_has_no_trade_and_persists_tick(tmp_path: Path):
    code = "600519"
    closes = [10.0] * 25 + [10.0 + i * 0.3 for i in range(10)]
    broker = _WatchBroker()
    loop = WatchLoop(
        _config(),
        _WatchQuote(),
        broker,
        log_base=tmp_path,
        seed_bars={code: _bars(code, closes)},
        now_fn=lambda: T0 + timedelta(minutes=5),
    )
    candidate = Candidate(code, "测试", "SH", source="watchlist")
    first = loop.run_once("2026-08-18", candidates_override=[candidate], now=T0 + timedelta(minutes=5))
    assert broker.trade_calls == []
    assert first.status in {"OK", "DEGRADED"}
    assert list((tmp_path / "watch" / "2026-08-18").rglob("tick.json"))


def test_watch_live_requires_cli_confirm_and_environment_switch(tmp_path: Path):
    broker = _WatchBroker()
    loop = WatchLoop(
        _config(watch_live=False),
        _WatchQuote(),
        broker,
        log_base=tmp_path,
        seed_bars={},
    )
    tick = loop.run_once(
        "2026-08-18",
        live=True,
        confirm=True,
        candidates_override=[],
        now=T0 + timedelta(minutes=5),
    )
    assert tick.status == "BLOCKED"
    assert broker.trade_calls == []


def test_kline_parser_ignores_forming_bar():
    payload = {
        "data": {
            "data": {
                "searchDataResultDTO": {
                    "dataTableDTOList": [
                        {
                            "dateGranularity": "MIN",
                            "nameMap": {
                                "o": "开盘价",
                                "h": "最高价",
                                "l": "最低价",
                                "c": "收盘价",
                                "v": "成交量",
                            },
                            "headName": [
                                "2026-08-18 09:25",
                                "2026-08-18 09:30",
                                "2026-08-18 09:35",
                            ],
                            "rawTable": {
                                "o": ["10", "10.1", "10.2"],
                                "h": ["10.1", "10.2", "10.3"],
                                "l": ["9.9", "10.0", "10.1"],
                                "c": ["10.0", "10.1", "10.2"],
                                "v": ["100", "200", "300"],
                            },
                        }
                    ]
                }
            }
        }
    }
    bars = parse_kline_payload(payload, "600519", 5, datetime(2026, 8, 18, 9, 40))
    assert len(bars) == 3
    assert bars[-1].close == 10.2


def test_kline_parser_rejects_daily_table_without_minute_granularity():
    payload = {
        "data": {
            "data": {
                "searchDataResultDTO": {
                    "dataTableDTOList": [
                        {
                            # 无 dateGranularity 的日线 DATA_BROWSER 表
                            "nameMap": {
                                "o": "开盘价", "h": "最高价", "l": "最低价", "c": "收盘价", "v": "成交量",
                            },
                            "headName": ["2026-08-15", "2026-08-16"],
                            "rawTable": {"o": ["10", "10.1"], "h": ["10.1", "10.2"], "l": ["9.9", "10.0"], "c": ["10.0", "10.1"], "v": ["100", "200"]},
                        }
                    ]
                }
            }
        }
    }
    try:
        parse_kline_payload(payload, "600519", 5, datetime(2026, 8, 18, 9, 40))
    except ValueError as exc:
        assert "分钟" in str(exc)
    else:
        raise AssertionError("无分钟粒度的日线表必须被拒绝")


def test_kline_parser_accepts_nested_field_minute_granularity():
    payload = {
        "data": {
            "data": {
                "searchDataResultDTO": {
                    "dataTableDTOList": [{
                        "field": {"dateGranularity": "MIN"},
                        "nameMap": {"o": "开盘价", "h": "最高价", "l": "最低价", "c": "收盘价"},
                        "headName": ["2026-08-18 09:30"],
                        "rawTable": {"o": ["10"], "h": ["10.1"], "l": ["9.9"], "c": ["10.0"]},
                    }],
                }
            }
        }
    }
    bars = parse_kline_payload(payload, "600519", 5, datetime(2026, 8, 18, 9, 40))
    assert len(bars) == 1
    assert bars[0].close == 10.0


def test_eastmoney_kline_parser_uses_raw_ohlcv_and_skips_forming_bar():
    payload = {
        "rc": 0,
        "data": {
            "klines": [
                "2026-08-18 09:30,10,10.2,10.3,9.9,12,0,0,0,0,0",
                "2026-08-18 09:35,10.2,10.1,10.4,10.0,8,0,0,0,0,0",
                "2026-08-18 09:40,10.1,10.3,10.5,10.1,9,0,0,0,0,0",
            ]
        },
    }
    bars = _parse_eastmoney_klines(payload, "600519", 5, datetime(2026, 8, 18, 9, 45))
    assert len(bars) == 3
    assert bars[0].volume == 1200
    assert bars[-1].source == "eastmoney_kline"
    forming = _parse_eastmoney_klines(payload, "600519", 5, datetime(2026, 8, 18, 9, 44))
    assert len(forming) == 2


def test_kline_parser_rejects_mismatched_code():
    payload = {
        "data": {
            "data": {
                "searchDataResultDTO": {
                    "dataTableDTOList": [
                        {
                            "dateGranularity": "MIN",
                            "entityTagDTO": {"secuCode": "000001"},
                            "nameMap": {"o": "开盘价", "h": "最高价", "l": "最低价", "c": "收盘价", "v": "成交量"},
                            "headName": ["2026-08-18 09:30"],
                            "rawTable": {"o": ["10"], "h": ["10.1"], "l": ["9.9"], "c": ["10.0"], "v": ["100"]},
                        }
                    ]
                }
            }
        }
    }
    try:
        parse_kline_payload(payload, "600519", 5, datetime(2026, 8, 18, 9, 40))
    except ValueError as exc:
        assert "不对齐" in str(exc)
    else:
        raise AssertionError("返回代码与请求代码不一致必须被拒绝")


class _ResultBroker(_WatchBroker):
    """按脚本回放 trade 结果的 broker。"""

    def __init__(self, results):
        super().__init__()
        self._results = list(results)

    def trade(self, *args, **kwargs):
        self.trade_calls.append((args, kwargs))
        return self._results.pop(0)


def test_submit_classifies_rejected_vs_unknown(tmp_path: Path):
    from paper.mx_result import MxResult

    intent = Intent("600519", IntentType.BUY_LIMIT, side="buy", qty=100, limit_price=10.0)

    # 明确业务拒绝（ok=False）→ REJECTED，而不是 UNKNOWN_SUBMIT
    rejected_result = MxResult(endpoint="/mockTrading", ok=False, code="300", message="余额不足")
    loop = WatchLoop(_config(), _WatchQuote(), _ResultBroker([rejected_result]), log_base=tmp_path, seed_bars={})
    rejected = loop._submit(intent, "t1")
    assert rejected.status == "REJECTED"

    # ok=True 但缺 orderId → UNKNOWN_SUBMIT
    ok_no_id = MxResult(endpoint="/mockTrading", ok=True, code="200", message="ok", data={})
    loop2 = WatchLoop(_config(), _WatchQuote(), _ResultBroker([ok_no_id]), log_base=tmp_path, seed_bars={})
    missing_id = loop2._submit(intent, "t2")
    assert missing_id.status == "UNKNOWN_SUBMIT"
    assert missing_id.error_code == "NO_ORDER_ID"


def _trade_result(*, ok=True, code="200", order_id=None):
    from paper.mx_result import MxResult

    return MxResult(
        endpoint="/mockTrading", ok=ok, code=code, message="ok",
        data={"orderId": order_id} if order_id else {},
    )


def test_live_halt_mid_cycle_blocks_remaining_submits(tmp_path: Path):
    from paper.mx_result import MxErrorKind, MxResult

    code_a, code_b = "600519", "000002"
    # 种子全平盘（MA5==MA20），第二轮新收盘 bar close=11 触发金叉
    flat = [10.0] * 30

    class _SeqQuote:
        def __init__(self, lasts):
            self._lasts = list(lasts)

        def snapshot(self, code, name="", *, run_id="adhoc", received_at=None):
            last = self._lasts.pop(0)
            return _quote(code=code, last=last, limit_up_price=round(last * 1.1, 2), received_at=received_at, market_time=received_at), MxResult(
                endpoint="/query", ok=True, code="0", message="ok"
            )

    # 每轮每票一次快照：9:35 建 forming，9:39:30 写入 close=11，9:41 收盘该 bar
    quote = _SeqQuote([10.0, 10.0, 11.0, 11.0, 10.5, 10.5])

    class _Broker(_WatchBroker):
        def __init__(self):
            super().__init__()
            self.balance_value = Balance(total_assets=1_000_000, avail_balance=1_000_000)
            self.result = MxResult(
                endpoint="/mockTrading", ok=False, code="113", message="配额耗尽",
                kind=MxErrorKind.QUOTA_EXHAUSTED,
            )

        def trade(self, *args, **kwargs):
            self.trade_calls.append((args, kwargs))
            return self.result

    broker = _Broker()
    loop = WatchLoop(
        _config(watch_live=True),
        quote,
        broker,
        log_base=tmp_path,
        seed_bars={code_a: _bars(code_a, flat), code_b: _bars(code_b, flat)},
        now_fn=lambda: T0 + timedelta(minutes=5),
        calendar=TradingCalendarService(source="weekday_fallback"),
    )
    candidates = [
        Candidate(code_a, "测试A", "SH", source="watchlist"),
        Candidate(code_b, "测试B", "SZ", source="watchlist"),
    ]
    loop.run_once("2026-08-18", live=True, confirm=True, candidates_override=candidates, now=T0 + timedelta(minutes=5))
    loop.run_once("2026-08-18", live=True, confirm=True, candidates_override=candidates, now=T0 + timedelta(minutes=9, seconds=30))
    tick = loop.run_once("2026-08-18", live=True, confirm=True, candidates_override=candidates, now=T0 + timedelta(minutes=11))
    # 两只同轮金叉；第一笔提交被 113 拒绝触发 STOP_RUN 后，第二笔不得再真实提交
    assert len(broker.trade_calls) == 1
    assert broker.trade_calls[0][0][0] == code_a
    assert loop._live_halted is True
    assert tick.live is False
    assert any(record["status"] == "REJECTED" for record in tick.submits)
    assert any(reason == "LIVE_HALTED_NO_SUBMIT" for intent in tick.intents for reason in intent["reason_codes"])


def test_quote_stop_run_halts_live_before_intent_submit(tmp_path: Path):
    from paper.mx_result import MxErrorKind, MxResult

    code = "600519"
    closes = [10.0] * 25 + [10.0 + i * 0.3 for i in range(10)]

    class _QuotaQuote:
        def snapshot(self, code, name="", *, run_id="adhoc", received_at=None):
            return None, MxResult(
                endpoint="/query", ok=False, code="113", message="配额耗尽",
                kind=MxErrorKind.QUOTA_EXHAUSTED,
            )

    broker = _ResultBroker([])
    broker.balance_value = Balance(total_assets=1_000_000, avail_balance=1_000_000)
    loop = WatchLoop(
        _config(watch_live=True),
        _QuotaQuote(),
        broker,
        log_base=tmp_path,
        seed_bars={code: _bars(code, closes)},
        now_fn=lambda: T0 + timedelta(minutes=5),
        calendar=TradingCalendarService(source="weekday_fallback"),
    )
    tick = loop.run_once(
        "2026-08-18",
        live=True,
        confirm=True,
        candidates_override=[Candidate(code, "测试", "SH", source="watchlist")],
        now=T0 + timedelta(minutes=5),
    )
    assert broker.trade_calls == []
    assert tick.live is False
    assert loop._live_halted is True


def test_prepare_errors_reported_once_per_day(tmp_path: Path):
    code = "600519"
    candidate = Candidate(code, "测试", "SH", source="watchlist")
    # 000001 无种子 K 线 → KLINE_DISABLED 进入 prepare 错误
    disabled = Candidate("000001", "无数据", "SZ", source="watchlist")
    loop = WatchLoop(
        _config(),
        _WatchQuote(),
        _WatchBroker(),
        log_base=tmp_path,
        seed_bars={code: _bars(code, [10.0] * 30)},
        now_fn=lambda: T0 + timedelta(minutes=5),
    )
    first = loop.run_once("2026-08-18", candidates_override=[candidate, disabled], now=T0 + timedelta(minutes=5))
    second = loop.run_once("2026-08-18", candidates_override=[candidate, disabled], now=T0 + timedelta(minutes=10))
    assert any("KLINE_DISABLED" in err for err in first.errors)
    assert not any("KLINE_DISABLED" in err for err in second.errors)
