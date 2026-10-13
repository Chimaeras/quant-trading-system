"""D-STALE-BAR-01 回归：陈旧 bar 不得进入策略预热序列。

缺陷背景（2026-10-13 定位）：`closed_bar` 是「同一交易日重启恢复」缓存，但
`load_recent_closed_bars` 原先只过滤周期、不过滤日期，于是「最近 80 根」跨月取证；
`loop` 又只按**根数**判断「已预热」并跳过网络补拉，最终把 8 月的旧价与当日实时价
拼成假连续序列，均线在断层处跳变，伪造出金叉买入（实测 601579 的 ma5 被算成
22.866，真实应为 34.32；当日 5 个 buy 信号中 4 个是此缺陷的产物）。

本文件锁定三层防线：日期作用域的缓存加载、基于交易日历的采纳闸门、缓存保留策略。
"""
from datetime import datetime, timedelta
from pathlib import Path

import pytest
import requests

from paper.config import PaperConfig
from paper.models import Candidate, Quote, QuoteValidity
from paper.mx_result import MxResult
from paper.trading_calendar import TradingCalendarService, load_calendar_from_file
from paper.watch.loop import WatchLoop, _allowed_bar_dates, _series_is_usable
from paper.watch.minute_bars import MinuteBarClient
from paper.watch.models import WatchBar
from paper.watch.signal_adapter import SignalAdapter
from paper.watch.store import SqliteWatchStore

_CALENDAR_PATH = Path(__file__).resolve().parents[1] / "trading-calendar.2026.json"
_CODE = "601579"


def _bars(start: str, count: int, close: float, period: int = 5) -> list[WatchBar]:
    """构造从 `start` 起、连续 count 根、收盘价恒为 close 的 5 分钟 bar。"""
    base = datetime.fromisoformat(start)
    return [
        WatchBar(
            _CODE, period, close, close, close, close, 1000.0,
            base + timedelta(minutes=period * i),
            base + timedelta(minutes=period * (i + 1)),
            closed=True,
        )
        for i in range(count)
    ]


# --------------------------------------------------------------------------
# 防线 2：基于交易日历的采纳闸门
# --------------------------------------------------------------------------

def test_series_is_usable_rejects_stale_series_whose_tail_spans_only_two_dates():
    """核心回归：陈旧序列的尾部可能**只跨 2 个日期**，纯计数判据会放行。

    这正是「尾部 ≤2 个交易日就采信」这类判据不够用的原因——必须比对具体日期是否
    落在允许窗口内。此用例若改为计数判据会失败，从而锁住实现选择。
    """
    calendar = load_calendar_from_file(_CALENDAR_PATH)
    allowed = _allowed_bar_dates(calendar, "2026-10-13", 2)

    stale = _bars("2026-08-27T14:30", 20, 20.0)   # 6 周前的旧价
    fresh = _bars("2026-10-13T14:40", 1, 34.30)   # 今日实时价
    series = stale + fresh

    assert len({b.start_ts.date() for b in series[-21:]}) == 2, "前提：尾部确实只跨 2 个日期"
    assert not _series_is_usable(series, 21, allowed)


def test_series_is_usable_accepts_current_and_previous_trading_day():
    calendar = load_calendar_from_file(_CALENDAR_PATH)
    allowed = _allowed_bar_dates(calendar, "2026-10-13", 2)
    assert allowed == {"2026-10-13", "2026-10-12"}

    prev = _bars("2026-10-12T14:40", 20, 34.0)
    today = _bars("2026-10-13T09:35", 1, 34.30)
    assert _series_is_usable(prev + today, 21, allowed)


def test_series_is_usable_fails_closed_without_window_or_bars():
    allowed = {"2026-10-13", "2026-10-12"}
    assert not _series_is_usable(_bars("2026-10-13T09:35", 21, 34.3), 21, set())
    # 根数不足时必须拒绝（不得因「只差几根」而放行）
    assert not _series_is_usable(_bars("2026-10-13T09:35", 20, 34.3), 21, allowed)


def test_allowed_bar_dates_walks_back_across_national_day_holiday():
    """国庆假期（10-01~10-07）必须被跳过，否则长假后窗口算错导致误拒。"""
    calendar = load_calendar_from_file(_CALENDAR_PATH)
    assert _allowed_bar_dates(calendar, "2026-10-09", 3) == {
        "2026-10-09", "2026-10-08", "2026-09-30",
    }


# --------------------------------------------------------------------------
# 防线 1：缓存加载的日期作用域
# --------------------------------------------------------------------------

def test_load_recent_closed_bars_scopes_to_trade_date(tmp_path):
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    for trade_date, close in (("2026-08-27", 20.0), ("2026-10-13", 34.3)):
        store.save_market_cycle(
            trade_date=trade_date,
            closed_bars=_bars(f"{trade_date}T14:30", 3, close),
            forming_checkpoints=[],
        )

    scoped = store.load_recent_closed_bars(5, 80, trade_date="2026-10-13")
    assert [b.close for b in scoped[_CODE]] == [34.3, 34.3, 34.3]

    # 不传日期 = 显式跨日取证，仍能拿到陈旧行——这正是缺陷的入口，
    # 因此真实调用方（loop）必须传 trade_date，并有防线 2 兜底。
    assert len(store.load_recent_closed_bars(5, 80)[_CODE]) == 6


# --------------------------------------------------------------------------
# 防线 3：缓存保留策略
# --------------------------------------------------------------------------

def test_prune_closed_bars_keeps_only_recent_trade_dates(tmp_path):
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    for trade_date in ("2026-08-27", "2026-08-28", "2026-10-13"):
        store.save_market_cycle(
            trade_date=trade_date,
            closed_bars=_bars(f"{trade_date}T14:30", 2, 10.0),
            forming_checkpoints=[],
        )

    assert store.prune_closed_bars(2) == 2   # 08-27 的两根被清除

    remaining = {
        row["trade_date"]
        for row in store._conn.execute("SELECT DISTINCT trade_date FROM closed_bar")
    }
    assert remaining == {"2026-08-28", "2026-10-13"}
    # 当日数据必须完整保留，否则会丢掉「同日重启恢复」能力
    assert len(store.load_recent_closed_bars(5, 80, trade_date="2026-10-13")[_CODE]) == 2


def test_prune_closed_bars_is_noop_below_retention(tmp_path):
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    store.save_market_cycle(
        trade_date="2026-10-13",
        closed_bars=_bars("2026-10-13T14:30", 2, 10.0),
        forming_checkpoints=[],
    )
    assert store.prune_closed_bars(2) == 0


# --------------------------------------------------------------------------
# 行为证明：被污染的序列确实会伪造金叉
# --------------------------------------------------------------------------

def test_contaminated_series_fabricates_a_golden_cross_that_clean_data_does_not():
    """缺陷产物复现：断层处 ma5 被旧价压低后突然上穿 ma20，产生假买入信号。"""
    fresh = _bars("2026-10-13T14:40", 1, 34.30)[0]

    contaminated = SignalAdapter("MA_CROSS", ma_short=5, ma_long=20)
    contaminated.seed(_CODE, _bars("2026-08-27T14:30", 21, 20.0))
    signal = contaminated.on_closed_bar(_CODE, fresh, position_qty=0)

    assert signal.action == "buy", "陈旧序列应复现出假金叉"
    assert signal.detail["ma_short_curr"] == pytest.approx(22.86, abs=0.01)
    assert signal.detail["ma_short_prev"] == pytest.approx(20.0, abs=0.01)

    # 同一根实时 bar，喂给连续的真实序列则没有金叉
    clean = SignalAdapter("MA_CROSS", ma_short=5, ma_long=20)
    clean.seed(_CODE, _bars("2026-10-13T09:35", 21, 34.30))
    assert clean.on_closed_bar(_CODE, fresh, position_qty=0).action == "hold"


# --------------------------------------------------------------------------
# 修复 4：分钟线本地沉淀客户端的线上契约（全部离线，monkeypatch requests）
# --------------------------------------------------------------------------

class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


def test_minute_bar_client_push_uses_snake_case_and_bearer(monkeypatch):
    captured = {}

    def fake_post(url, json=None, headers=None, timeout=None):
        captured.update(url=url, json=json, headers=headers)
        return _FakeResponse({"code": 200, "data": {"saved": 2, "skipped": 0}})

    monkeypatch.setattr("paper.watch.minute_bars.requests.post", fake_post)
    client = MinuteBarClient("http://localhost:8080", "tok")

    assert client.push(_bars("2026-10-13T14:40", 2, 34.30)) == 2
    assert captured["url"] == "http://localhost:8080/api/data/minute-bars/bulk"
    assert captured["headers"]["Authorization"] == "Bearer tok"
    item = captured["json"][0]
    # Java DTO 用 @JsonProperty 声明 snake_case；键名漂移会让沉淀静默丢字段
    assert item["stock_code"] == _CODE
    assert item["period_min"] == 5
    assert item["trade_date"] == "2026-10-13"
    assert item["start_ts"].startswith("2026-10-13T14:40")
    assert item["close"] == 34.30


def test_minute_bar_client_is_best_effort_on_transport_failure(monkeypatch):
    """Java 不可用时必须降级而不是抛错：行情判定不允许被本地沉淀拖垮。"""
    def boom(*args, **kwargs):
        raise requests.RequestException("connection refused")

    monkeypatch.setattr("paper.watch.minute_bars.requests.post", boom)
    client = MinuteBarClient("http://localhost:8080")

    assert client.push(_bars("2026-10-13T14:40", 1, 34.30)) == 0
    assert "RequestException" in client.last_error
    assert client.recent([_CODE]) == {}
    assert client.last_error


def test_minute_bar_client_recent_parses_bars_and_drops_bad_rows(monkeypatch):
    rows = [
        {"code": _CODE, "period_min": 5, "start_ts": "2026-10-13T14:40:00",
         "end_ts": "2026-10-13T14:45:00", "trade_date": "2026-10-13", "open": 34.3,
         "high": 34.36, "low": 34.3, "close": 34.3, "volume": 457700,
         "source": "eastmoney_batch"},
        {"code": _CODE, "period_min": 5},          # 缺字段 → 丢弃
        "not-a-dict",                              # 非对象 → 丢弃
    ]
    monkeypatch.setattr(
        "paper.watch.minute_bars.requests.post",
        lambda *a, **k: _FakeResponse({"data": {"bars_by_code": {_CODE: rows}}}),
    )
    client = MinuteBarClient("http://localhost:8080")

    bars = client.recent([_CODE], limit_per_code=80, lookback_days=4)
    assert len(bars[_CODE]) == 1
    parsed = bars[_CODE][0]
    assert parsed.close == 34.3
    assert parsed.period_min == 5
    assert parsed.start_ts.date().isoformat() == "2026-10-13"
    assert parsed.closed is True


def test_minute_bar_client_disabled_without_base_url(monkeypatch):
    def forbidden(*args, **kwargs):
        raise AssertionError("未配置 base_url 时不应发起任何请求")

    monkeypatch.setattr("paper.watch.minute_bars.requests.post", forbidden)
    client = MinuteBarClient("")

    assert client.enabled is False
    assert client.push(_bars("2026-10-13T14:40", 1, 34.30)) == 0
    assert client.recent([_CODE]) == {}


# --------------------------------------------------------------------------
# 修复 4：loop 接线——预热优先本地分钟线，且新收盘 bar 会沉淀
# --------------------------------------------------------------------------

_REPLAY_BASE = datetime(2026, 8, 18, 9, 30)
_REPLAY_DATE = "2026-08-18"


class _QuoteStub:
    def snapshot(self, code, name="", *, run_id="adhoc", received_at=None):
        quote = Quote(code=code, name="测试", exchange="SH", last=10.0, pct_chg=1.0,
                      volume=1000, market_time=received_at, received_at=received_at,
                      validity=QuoteValidity.VALID, limit_up_price=110.0)
        return quote, MxResult(endpoint="/query", ok=True, code="0", message="ok")


class _BrokerStub:
    def account(self, *args, **kwargs):
        return None, MxResult(endpoint="/account", ok=False, code="E", message="shadow")


class _KlineForbidden:
    """本地序列可用时若回退到外部 K 线即失败。"""

    def fetch_history(self, *args, **kwargs):
        raise AssertionError("本地分钟线可用时不应调用外部 K 线")


class _FakeMinuteBarClient:
    def __init__(self, bars_by_code=None):
        self._bars = bars_by_code or {}
        self.pushed: list = []
        self.recent_calls: list = []
        self.last_error = ""

    def recent(self, codes, *, limit_per_code=80, lookback_days=5):
        self.recent_calls.append((list(codes), limit_per_code, lookback_days))
        return {code: self._bars[code] for code in codes if code in self._bars}

    def push(self, bars):
        self.pushed.extend(bars)
        return len(bars)


def _loop_with_client(tmp_path, monkeypatch, client, *, kline_client=None, seed=None):
    candidate = Candidate(_CODE, "测试", "SH", source="t1")
    monkeypatch.setattr(
        "paper.watch.loop.load_watch_universe",
        lambda cfg, d, ov=None, store=None: ([candidate], "t1", []),
    )
    loop = WatchLoop(
        PaperConfig(api_key="k", min_cash_reserve=0, max_total_weight=1.0,
                    max_positions=5, index_circuit_breaker=False),
        _QuoteStub(),
        _BrokerStub(),
        kline_client=kline_client,
        log_base=tmp_path,
        store=SqliteWatchStore(tmp_path / "runtime.sqlite3"),
        calendar=load_calendar_from_file(_CALENDAR_PATH),
        seed_bars={_CODE: seed} if seed else None,
        now_fn=lambda: _REPLAY_BASE,
        minute_bar_client=client,
    )
    return loop, candidate


def _advance(loop, candidate, minutes):
    return loop.run_once(
        _REPLAY_DATE, candidates_override=[candidate],
        now=_REPLAY_BASE + timedelta(minutes=minutes),
    )


def test_warmup_prefers_usable_local_minute_bars(tmp_path, monkeypatch):
    """本地已有连续分钟线时，预热不应再打外部 K 线。"""
    calendar = load_calendar_from_file(_CALENDAR_PATH)
    assert _REPLAY_DATE in _allowed_bar_dates(calendar, _REPLAY_DATE, 2), "前提：夹具日在允许窗口内"

    client = _FakeMinuteBarClient({_CODE: _bars(f"{_REPLAY_DATE}T09:35", 21, 10.0)})
    loop, candidate = _loop_with_client(
        tmp_path, monkeypatch, client, kline_client=_KlineForbidden())

    tick = _advance(loop, candidate, 97)

    assert client.recent_calls, "预热应先查询本地分钟线"
    assert tick.metrics.get("minute_bar_local_warmup") == 1
    assert loop.adapter.ready(_CODE), "本地 21 根应足以让 MA_CROSS(5,20) 就绪"


def test_cycle_pushes_newly_closed_bars_to_local_sink(tmp_path, monkeypatch):
    """新收盘 bar 必须沉淀到主库，否则本地路径永远长不出数据。"""
    client = _FakeMinuteBarClient()
    loop, candidate = _loop_with_client(
        tmp_path, monkeypatch, client,
        seed=_bars(f"{_REPLAY_DATE}T09:30", 19, 10.0))

    _advance(loop, candidate, 97)
    _advance(loop, candidate, 100)

    assert client.pushed, "跨桶收盘的 bar 应同步沉淀"
    assert {bar.code for bar in client.pushed} == {_CODE}
    assert all(bar.closed for bar in client.pushed)


# --------------------------------------------------------------------------
# R2-01 回归（2026-10-13 Codex 验证）：网络回退路径必须复用同一日期闸门
# --------------------------------------------------------------------------

class _KlineReturns:
    """外部 K 线客户端替身：固定返回给定序列，并记录调用次数。"""

    def __init__(self, bars):
        self._bars = list(bars)
        self.calls = 0

    def fetch_history(self, *args, **kwargs):
        self.calls += 1
        return list(self._bars), None


def test_network_warmup_rejects_stale_series_outside_trading_window(tmp_path, monkeypatch):
    """R2-01：窗口外的网络序列不得被采信。

    缺陷背景：本地路径走 ``_series_is_usable``，网络回退路径却直接
    ``prepared_bars[code] = list(bars)``，于是**完全相同的陈旧返回值**本地被拒、
    网络被接受并 ``ready=True``（实测 002388：网络返回 30 根全为 2026-09-30，
    本地 usable=False、网络 ready=True）。停牌/陈旧序列因此仍能进入预热，
    均线在断层处跳变并伪造金叉。
    """
    calendar = load_calendar_from_file(_CALENDAR_PATH)
    allowed = _allowed_bar_dates(calendar, _REPLAY_DATE, 2)
    assert allowed == {"2026-08-18", "2026-08-17"}, "前提：允许窗口为当日+上一交易日"

    stale = _bars("2026-07-06T09:35", 30, 10.0)
    assert not _series_is_usable(stale, 21, allowed), "前提：该序列尾部确实在窗口外"

    client = _FakeMinuteBarClient()          # 本地无分钟线 -> 必须回退网络
    kline = _KlineReturns(stale)
    loop, candidate = _loop_with_client(
        tmp_path, monkeypatch, client, kline_client=kline)

    _advance(loop, candidate, 97)

    assert kline.calls == 1, "本地不可用时应回退外部 K 线"
    assert not loop.adapter.ready(_CODE), (
        "窗口外的网络序列不得进入预热序列（修复前此处为 ready=True）"
    )
    assert any(
        "KLINE_SERIES_OUT_OF_TRADING_WINDOW" in err for err in loop._prepare_errors
    ), f"应记录明确错误而不是静默采用；实际 {loop._prepare_errors}"


def test_network_warmup_accepts_series_inside_trading_window(tmp_path, monkeypatch):
    """对照：窗口内的网络序列仍应正常预热（修复不得误伤正常路径）。"""
    calendar = load_calendar_from_file(_CALENDAR_PATH)
    allowed = _allowed_bar_dates(calendar, _REPLAY_DATE, 2)

    fresh = _bars("2026-08-18T09:35", 30, 10.0)
    assert _series_is_usable(fresh, 21, allowed), "前提：该序列在窗口内"

    client = _FakeMinuteBarClient()
    kline = _KlineReturns(fresh)
    loop, candidate = _loop_with_client(
        tmp_path, monkeypatch, client, kline_client=kline)

    _advance(loop, candidate, 97)

    assert loop.adapter.ready(_CODE), "窗口内的网络序列应正常预热"
    assert not any(
        "KLINE_SERIES_OUT_OF_TRADING_WINDOW" in err for err in loop._prepare_errors
    )


