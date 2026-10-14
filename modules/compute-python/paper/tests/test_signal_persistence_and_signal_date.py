# -*- coding: utf-8 -*-
"""D-WATCH-01 / D-WATCH-02 回归测试（2026-10-14）。

D-WATCH-01：盘中信号明细必须落库。
    修复前 cycle_metric 只存 buy_signal_count/sell_signal_count 两个计数，
    具体标的、bar 时点与指标值全部丢失——进程重启后无法回答「信号准不准」，
    也无法把盘中信号并入任何事后回验。

D-WATCH-02：盯盘跨日必须自我校正 signal_date。
    修复前 signal_date 由 CLI 在启动时固定传入，长循环跨日后不再更新。
    实测 2026-10-13 15:40 以 --signal-date 2026-10-13 启动的进程，
    到 10-14 仍在用 10-13 作为 selection_signal_date。
"""
from __future__ import annotations

from datetime import datetime, timedelta
from pathlib import Path

from paper.config import PaperConfig
from paper.models import Candidate
from paper.trading_calendar import TradingCalendarService
from paper.watch.loop import WatchLoop
from paper.watch.models import WatchBar
from paper.watch.store import SqliteWatchStore

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


class _QuietQuote:
    """行情替身：snapshot/snapshot_many 同时可用，报价随轮次推进，不发网络请求。

    `advance()` 把报价推到第 n 个 5 分钟桶，驱动 bar_store 收盘出 bar——
    单轮固定报价永远只会形成一根未收盘的 forming bar，产不出信号。
    """

    def __init__(self, series: list[float], code: str = "600519"):
        self.code = code
        self.series = list(series)
        self.idx = 0
        self.calls = 0
        self.batch_calls = 0

    def advance(self, idx: int) -> None:
        self.idx = idx

    def _price(self) -> float:
        return self.series[min(self.idx, len(self.series) - 1)]

    def _market_time(self):
        return T0 + timedelta(minutes=5 * (self.idx + 1))

    def _one(self, code, received_at):
        from paper.models import Quote, QuoteValidity
        price = self._price()
        return Quote(
            code=code,
            name="测试",
            exchange="SH",
            last=price,
            pct_chg=1.0,
            volume=1000,
            market_time=self._market_time(),
            received_at=self._market_time(),
            validity=QuoteValidity.VALID,
            limit_up_price=price * 1.1,
        )

    def snapshot(self, code, name="", *, run_id="adhoc", received_at=None):
        from paper.mx_result import MxResult
        self.calls += 1
        return self._one(code, received_at), MxResult(
            endpoint="/query", ok=True, code="0", message="ok"
        )

    def snapshot_many(self, candidates, *, run_id="adhoc", received_at=None):
        from paper.mx_result import MxResult
        self.batch_calls += 1
        return {
            candidate.stock_code: (
                self._one(candidate.stock_code, received_at),
                MxResult(endpoint="eastmoney/ulist", ok=True, code="0", message="ok"),
            )
            for candidate in candidates
        }


class _NoopBroker:
    def balance(self):
        from paper.models import Balance
        return Balance(cash=1_000_000.0, total_asset=1_000_000.0, frozen_cash=0.0)

    def positions(self):
        return []

    def orders(self):
        return []


# --------------------------------------------------------------------------
# D-WATCH-01
# --------------------------------------------------------------------------

def test_signal_events_are_persisted_with_detail(tmp_path: Path):
    """信号明细落库：run_id/标的/动作/bar 时点/原因码/指标明细都必须可查。

    构造：历史 25 根横盘在 10.0，当日报价从 10.0 推到 13.4。
    收盘 bar 逐根产生，MA5 必然上穿 MA20 → 至少产出一次 MA_GOLDEN 买入信号。
    """
    code = "600519"
    history = [10.0] * 25
    today = [10.0, 10.1, 10.4, 11.0, 11.8, 12.6, 13.4]
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    try:
        quote = _QuietQuote(today, code=code)
        loop = WatchLoop(
            _config(),
            quote,
            _NoopBroker(),
            log_base=tmp_path,
            seed_bars={code: _bars(code, history)},
            store=store,
            calendar=TradingCalendarService(),
            now_fn=lambda: T0,
        )
        candidate = Candidate(code, "测试", "SH", source="watchlist")
        all_signals = []
        for step in range(len(today)):
            quote.advance(step)
            tick = loop.run_once(
                "2026-08-18",
                candidates_override=[candidate],
                now=T0 + timedelta(minutes=5 * (step + 1)),
            )
            all_signals.extend(tick.signals)

        # 前提：夹具必须真的产出信号，否则本测试是空转（此前的教训）
        assert all_signals, "夹具未产出任何信号，测试无法验证落库行为"
        buy_signals = [s for s in all_signals if s.get("action") == "buy"]
        assert buy_signals, "夹具未产出买入信号，无法验证 MA_GOLDEN 落库"
        assert any("MA_GOLDEN" in (s.get("reason_codes") or []) for s in buy_signals)

        rows = store._conn.execute(
            "SELECT run_id, trade_date, code, action, strategy, bar_ts, valid_until,"
            " bar_period_min, reason_codes, detail_json"
            " FROM signal_event ORDER BY bar_ts"
        ).fetchall()
        assert len(rows) == len(all_signals), (
            f"落库行数 {len(rows)} 与信号数 {len(all_signals)} 不一致"
        )
        for row in rows:
            assert row["run_id"]
            assert row["trade_date"] == "2026-08-18"
            assert row["code"] == code
            assert row["strategy"] == "MA_CROSS"
            assert row["bar_ts"], "bar_ts 必须落库，否则无法定位信号发生的 bar"
            assert row["valid_until"], "valid_until 必须落库，否则无法判断信号是否失效"
            assert row["bar_period_min"] == 5
            assert row["reason_codes"], "reason_codes 必须落库"
            assert row["detail_json"] and row["detail_json"] != "{}", (
                "指标明细必须落库，否则事后无法复核信号为何触发"
            )

        # 买入信号那一行的关键字段必须完整（这是最有价值的一行）
        buy_rows = [r for r in rows if r["action"] == "buy"]
        assert buy_rows
        assert "MA_GOLDEN" in buy_rows[0]["reason_codes"]
        assert "ma_short_curr" in buy_rows[0]["detail_json"]
        assert "ma_long_curr" in buy_rows[0]["detail_json"]
    finally:
        store.close()


def test_signal_events_are_idempotent_per_run(tmp_path: Path):
    """同一轮重复写不产生重复行（主键 run_id+code+action）。"""
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    try:
        tick = {
            "run_id": "r1",
            "trade_date": "2026-08-18",
            "signals": [
                {"code": "600519", "action": "buy", "strategy": "MA_CROSS",
                 "bar_ts": "2026-08-18T09:35:00+08:00", "reason_codes": ["MA_GOLDEN"],
                 "detail": {"ma_short_curr": 10.1}},
            ],
        }
        assert store.save_signal_events(tick) == 1
        assert store.save_signal_events(tick) == 1
        count = store._conn.execute("SELECT COUNT(*) FROM signal_event").fetchone()[0]
        assert count == 1, "同一轮同标的同动作必须幂等，不得重复追加"
        # 明细被更新而非重复插入
        row = store._conn.execute(
            "SELECT reason_codes, detail_json FROM signal_event"
        ).fetchone()
        assert row["reason_codes"] == "MA_GOLDEN"
        assert "ma_short_curr" in row["detail_json"]
    finally:
        store.close()


def test_signal_events_empty_is_noop(tmp_path: Path):
    """无信号轮次不应写入任何行，也不应报错。"""
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    try:
        assert store.save_signal_events({"run_id": "r1", "trade_date": "2026-08-18", "signals": []}) == 0
        assert store.save_signal_events({"run_id": "r2", "trade_date": "2026-08-18"}) == 0
        count = store._conn.execute("SELECT COUNT(*) FROM signal_event").fetchone()[0]
        assert count == 0
    finally:
        store.close()


# --------------------------------------------------------------------------
# D-WATCH-02
# --------------------------------------------------------------------------

def test_signal_date_advances_across_trading_day_boundary(tmp_path: Path):
    """跨日后 selection_signal_date 必须校正为「当天的上一交易日」，而非启动日。"""
    code = "600519"
    loop = WatchLoop(
        _config(),
        _QuietQuote([10.0] * 30, code=code),
        _NoopBroker(),
        log_base=tmp_path,
        seed_bars={code: _bars(code, [10.0] * 30)},
        calendar=TradingCalendarService(),
        now_fn=lambda: T0 + timedelta(days=1, minutes=5),
    )
    # 启动时传入 2026-08-18；次日（08-19，周三）运行时上一交易日应为 08-18
    tick = loop.run_once(
        "2026-08-18",
        candidates_override=[Candidate(code, "测试", "SH", source="watchlist")],
        now=T0 + timedelta(days=1, minutes=5),
    )
    assert tick.metrics["selection_signal_date"] == "2026-08-18", (
        "跨日后应使用当天的上一交易日（08-18 恰为 08-19 的上一交易日）"
    )
    # 关键回归：不得停留在「启动日即信号日」的错误口径上被误读为今天
    assert tick.metrics["selection_signal_date"] != tick.trade_date


def test_signal_date_stable_within_same_trading_day(tmp_path: Path):
    """同一交易日内反复运行，signal_date 必须稳定，不被改来改去。"""
    code = "600519"
    loop = WatchLoop(
        _config(),
        _QuietQuote([10.0] * 30, code=code),
        _NoopBroker(),
        log_base=tmp_path,
        seed_bars={code: _bars(code, [10.0] * 30)},
        calendar=TradingCalendarService(),
        now_fn=lambda: T0 + timedelta(minutes=5),
    )
    candidate = Candidate(code, "测试", "SH", source="watchlist")
    first = loop.run_once("2026-08-18", candidates_override=[candidate], now=T0 + timedelta(minutes=5))
    second = loop.run_once("2026-08-18", candidates_override=[candidate], now=T0 + timedelta(minutes=10))
    assert first.metrics["selection_signal_date"] == "2026-08-18"
    assert second.metrics["selection_signal_date"] == "2026-08-18"


def test_explicit_signal_date_is_respected(tmp_path: Path):
    """调用方显式传入与启动值不同的日期时，不得被自动改写。"""
    code = "600519"
    loop = WatchLoop(
        _config(),
        _QuietQuote([10.0] * 30, code=code),
        _NoopBroker(),
        log_base=tmp_path,
        seed_bars={code: _bars(code, [10.0] * 30)},
        calendar=TradingCalendarService(),
        now_fn=lambda: T0 + timedelta(days=1, minutes=5),
    )
    loop.run_once("2026-08-18", candidates_override=[Candidate(code, "测试", "SH", source="watchlist")],
                  now=T0 + timedelta(days=1, minutes=5))
    # 显式改传一个不同日期：应原样尊重，而不是被重算成上一交易日
    tick = loop.run_once("2026-08-11",
                         candidates_override=[Candidate(code, "测试", "SH", source="watchlist")],
                         now=T0 + timedelta(days=1, minutes=10))
    assert tick.metrics["selection_signal_date"] == "2026-08-11"
