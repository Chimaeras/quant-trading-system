"""决策时间与来源边界回归；仅注入假响应，不联网或调用订单接口。"""
from datetime import datetime, timedelta
from types import SimpleNamespace

import pytest

from paper.candidates import load_from_t1
from paper.clock import CN_TZ
from paper.config import PaperConfig
from paper.models import Candidate, Quote, QuoteValidity
from paper.mx_result import MxResult
from paper.trading_calendar import TradingCalendarService
from paper.watch.loop import WatchLoop
from paper.watch.models import WatchBar
from paper.watch.signal_adapter import SignalAdapter
from paper.watch.store import SqliteWatchStore


def response(monkeypatch, payload):
    monkeypatch.setattr("paper.candidates.requests.get", lambda *args, **kwargs: SimpleNamespace(
        raise_for_status=lambda: None, json=lambda: {"data": payload}))


def test_t1_loader_rejects_date_mismatch_and_missing_snapshot(monkeypatch):
    response(monkeypatch, {"trade_date": "2026-08-17", "candidates": []})
    with pytest.raises(ValueError, match="数据日"):
        load_from_t1("http://unused", "2026-08-18")
    response(monkeypatch, {"found": False, "trade_date": "2026-08-18", "candidates": []})
    with pytest.raises(ValueError, match="快照"):
        load_from_t1("http://unused", "2026-08-18")


def test_t1_loader_does_not_adopt_legacy_probability_as_verified(monkeypatch):
    response(monkeypatch, {"trade_date": "2026-08-18", "status": "OK", "candidates": [{
        "stock_code": "000001", "stock_name": "测试股份", "exchange_type": "SZSE",
        "score": 0.9, "probability": 0.9, "rule_score": 37, "is_probability": True}]})
    candidate = load_from_t1("http://unused", "2026-08-18")[0]
    assert candidate.score == 37
    assert candidate.probability is None
    assert candidate.selection_lineage["trade_date"] == "2026-08-18"


def test_t1_pool_expires_after_its_observation_session(monkeypatch, tmp_path):
    candidate = Candidate("000001", "测试股份", "SZ", signal_date="2026-08-17")
    monkeypatch.setattr("paper.watch.loop.load_watch_universe", lambda *args, **kwargs: ([candidate], "t1", []))
    loop = WatchLoop(PaperConfig(watch_universe_mode="T1"), object(), object(), log_base=tmp_path,
                     calendar=TradingCalendarService(source="test"))
    errors = loop.prepare("2026-08-17", now=datetime(2026, 8, 19, 10))
    assert not loop._candidates
    assert any(e.startswith("T1_INVALID_OBSERVATION_DAY") for e in errors)


def test_signal_carries_bar_time_version_and_bounded_expiry():
    adapter = SignalAdapter()
    start = datetime(2026, 8, 18, 14, 55, tzinfo=CN_TZ)
    end = start.replace(hour=15, minute=0)
    bar = WatchBar("000001", 5, 10, 10, 10, 10, 1000, start, end, closed=True)
    signal = adapter.on_closed_bar("000001", bar, 0)
    assert signal.bar_ts == end.isoformat()
    assert signal.valid_until == end.isoformat()
    assert signal.strategy_version.startswith("intraday_rules_v1:")
    assert signal.holding_horizon == "UNVALIDATED"
    assert "当日收盘" in signal.invalidation_conditions


def test_t1_pool_rejects_weekday_fallback_calendar_without_explicit_source(monkeypatch, tmp_path):
    """D-1 回归：T1 观察池必须使用**正式**日历。

    缺陷复现：`loop.py` 原先写作
        `not A or not B and not C or D`
    因 `and` 优先级高于 `or`，实际等价于 `not A or D`，
    即「必须显式注入日历」这一项是死代码。于是 T1 模式在未注入日历时
    （weekday fallback，available=True）会把节假日/周末的陈旧候选当成当日观察池继续分析。

    本测试用「available=True 但并非显式来源」的 weekday fallback 日历：
    修正前该候选会被放行，修正后必须被 T1_INVALID_OBSERVATION_DAY 拦下。
    """
    from paper.watch.loop import load_watch_universe

    # 信号日周二、观察日周三：使 weekday fallback 认为观察日是交易日（放大缺陷可见性）
    signal_date = "2026-08-18"
    candidate = Candidate("000001", "测试股份", "SZ", signal_date=signal_date)

    def fake_universe(*args, **kwargs):
        return [candidate], "t1", []

    monkeypatch.setattr("paper.watch.loop.load_watch_universe", fake_universe)

    loop = WatchLoop(
        PaperConfig(watch_universe_mode="T1"),
        object(),
        object(),
        log_base=tmp_path,
        # 关键：传入的是「默认构造」的日历——它是 explicit 的，但行为仍是周一至周五回退
        # （trading_calendar.py:26-28），等价于未注入正式日历。
        calendar=TradingCalendarService(source="weekday_fallback"),
    )
    assert load_watch_universe is not None  # 保持导入引用，避免被误删
    errors = loop.prepare(signal_date, now=datetime(2026, 8, 19, 10))
    assert not loop._candidates, "周一至周五回退日历不得放行 T1 观察池"
    assert any(e.startswith("T1_INVALID_OBSERVATION_DAY") for e in errors), errors


def test_t1_pool_accepts_calendar_loaded_from_official_file(monkeypatch, tmp_path):
    """D-1 反向回归：真正的正式日历（由文件加载）必须照常放行。

    避免修复退化为「一律阻断 T1」——那会让默认启动口径失效。
    """
    import json

    signal_date = "2026-08-18"
    candidate = Candidate("000001", "测试股份", "SZ", signal_date=signal_date)
    monkeypatch.setattr("paper.watch.loop.load_watch_universe",
                        lambda *a, **k: ([candidate], "t1", []))

    calendar_file = tmp_path / "trading-calendar.2026.json"
    calendar_file.write_text(json.dumps({"holidays": [], "extra_workdays": []}), encoding="utf-8")

    from paper.trading_calendar import load_calendar_from_file

    loop = WatchLoop(
        PaperConfig(watch_universe_mode="T1"),
        object(),
        object(),
        log_base=tmp_path,
        calendar=load_calendar_from_file(calendar_file),
    )
    errors = loop.prepare(signal_date, now=datetime(2026, 8, 19, 10))
    assert loop._candidates, f"正式日历不得被误阻断: {errors}"
    assert not any(e.startswith("T1_INVALID_OBSERVATION_DAY") for e in errors), errors


def test_warmup_does_not_erase_expired_bar_audit_trail(monkeypatch, tmp_path):
    """D-3 回归：「未预热 + 已过期」时 EXPIRED_BAR 不得被 WARMUP 抹掉。

    缺陷复现：`loop.py` 原先先执行过期分支（写入 status=INVALIDATED、
    reason_codes+=EXPIRED_BAR），随后 warmup 分支用
    `signal.reason_codes = ["WARMUP","BAR_CLOSED"]` **整体替换**，
    导致过期原因在审计上完全消失。
    """
    code = "600519"
    base = datetime(2026, 8, 18, 9, 30)

    class _SeqQuote:
        def __init__(self, lasts):
            self._lasts = list(lasts)

        def snapshot(self, code, name="", *, run_id="adhoc", received_at=None):
            last = self._lasts.pop(0)
            quote = Quote(
                code=code, name="测试", exchange="SH", last=last, pct_chg=1.0, volume=1000,
                market_time=received_at, received_at=received_at,
                validity=QuoteValidity.VALID, limit_up_price=110.0,
            )
            return quote, MxResult(endpoint="/query", ok=True, code="0", message="ok")

    class _Broker:
        def account(self, *args, **kwargs):
            return None, MxResult(endpoint="/account", ok=False, code="E", message="shadow")

    def _bars(closes, period=5):
        return [
            WatchBar(code, period, v, v, v, v, 1000,
                     base + timedelta(minutes=period * i),
                     base + timedelta(minutes=period * (i + 1)), closed=True)
            for i, v in enumerate(closes)
        ]

    loop = WatchLoop(
        PaperConfig(api_key="k", min_cash_reserve=0, max_total_weight=1.0,
                    max_positions=5, index_circuit_breaker=False),
        _SeqQuote([10.0, 11.0, 10.5]),
        _Broker(),
        log_base=tmp_path,
        seed_bars={code: _bars([10.0] * 3)},
        now_fn=lambda: base,
    )
    # 构造「可执行信号 + 未预热 + 已过期」这一交叉场景：
    # 真实 MA 交叉在本夹具下只会给出 hold，无法区分两个分支的先后，
    # 因此这里替换 on_closed_bar，直接返回 action="buy" 且已过期的信号。
    # 这样既强制走了 warmup 分支（强制 ready=False），也强制走了过期分支。
    monkeypatch.setattr(loop.adapter, "ready", lambda _code: False)

    real_on_closed_bar = loop.adapter.on_closed_bar

    def actionable_expired_signal(code_, bar, position_qty):
        signal = real_on_closed_bar(code_, bar, position_qty)
        signal.action = "buy"
        # 与生产同风格：naive 本地时间 ISO 字符串，且早于决策时刻 → 必然过期
        signal.valid_until = (base.replace(tzinfo=CN_TZ) - timedelta(minutes=1)).isoformat()
        return signal

    monkeypatch.setattr(loop.adapter, "on_closed_bar", actionable_expired_signal)
    candidate = Candidate(code, "测试", "SH", source="watchlist")

    loop.run_once("2026-08-18", candidates_override=[candidate], now=base + timedelta(minutes=5))
    loop.run_once("2026-08-18", candidates_override=[candidate], now=base + timedelta(minutes=9, seconds=30))
    tick = loop.run_once("2026-08-18", candidates_override=[candidate],
                         now=base + timedelta(minutes=11))

    signals = [s for s in tick.signals if s.get("reason_codes")]
    assert signals, f"应至少产生一条信号记录用于审计；tick.errors={tick.errors}"
    latest = signals[-1]
    # 未预热 + 已过期：动作必须被压成 hold，且两个原因都要留下
    assert latest["action"] == "hold", latest
    assert "EXPIRED_BAR" in latest["reason_codes"], latest["reason_codes"]
    assert "WARMUP" in latest["reason_codes"], latest["reason_codes"]


def _d2_loop(monkeypatch, tmp_path, *, ready_value, action, valid_until_fn=None,
             code="600519", seed_count=30):
    """构造 D-2 用例所需的 WatchLoop：可控 ready / 可控动作。

    与 D-3 用例同源夹具：seed_count 根已收盘 bar 结束于 base+seed_count*5 分钟，
    随后把 now 推到更晚，使新桶关闭并产出信号。
    """
    base = datetime(2026, 8, 18, 9, 30)

    class _SeqQuote:
        def __init__(self, lasts):
            self._lasts = list(lasts)

        def snapshot(self, code, name="", *, run_id="adhoc", received_at=None):
            last = self._lasts.pop(0)
            quote = Quote(
                code=code, name="测试", exchange="SH", last=last, pct_chg=1.0, volume=1000,
                market_time=received_at, received_at=received_at,
                validity=QuoteValidity.VALID, limit_up_price=110.0,
            )
            return quote, MxResult(endpoint="/query", ok=True, code="0", message="ok")

    class _Broker:
        def account(self, *args, **kwargs):
            return None, MxResult(endpoint="/account", ok=False, code="E", message="shadow")

    def _bars(closes, period=5):
        return [
            WatchBar(code, period, v, v, v, v, 1000,
                     base + timedelta(minutes=period * i),
                     base + timedelta(minutes=period * (i + 1)), closed=True)
            for i, v in enumerate(closes)
        ]

    loop = WatchLoop(
        PaperConfig(api_key="k", min_cash_reserve=0, max_total_weight=1.0,
                    max_positions=5, index_circuit_breaker=False),
        _SeqQuote([10.0, 11.0, 10.5]),
        _Broker(),
        log_base=tmp_path,
        seed_bars={code: _bars([10.0] * seed_count)},
        now_fn=lambda: base,
    )
    monkeypatch.setattr(loop.adapter, "ready", lambda _c: ready_value)
    real_on_closed_bar = loop.adapter.on_closed_bar

    def patched(code_, bar, position_qty):
        signal = real_on_closed_bar(code_, bar, position_qty)
        signal.action = action
        if valid_until_fn is not None:
            signal.valid_until = valid_until_fn(bar)
        return signal

    monkeypatch.setattr(loop.adapter, "on_closed_bar", patched)
    return loop, base, code


def test_warmup_signal_declares_why_it_is_not_actionable(monkeypatch, tmp_path):
    """D-2 回归：预热不足的信号必须显式给出「不可执行」的失效条件与持有期口径。

    缺陷复现：`signal_adapter` 原先无条件写入
        holding_horizon="UNVALIDATED"
        invalidation_conditions=["下一根 bar 收盘", "行情过期或缺失", ...]
    该模板与本次原因码无关；叠加 UI 从不渲染 `signal_status`，
    用户无法区分「预热不足」与「bar 已过期」。
    """
    loop, base, code = _d2_loop(monkeypatch, tmp_path, ready_value=False, action="buy")
    candidate = Candidate(code, "测试", "SH", source="watchlist")

    tick = None
    for minutes in (5, 9.5, 11):
        tick = loop.run_once("2026-08-18", candidates_override=[candidate],
                             now=base + timedelta(minutes=minutes))
    signals = [s for s in tick.signals if s.get("reason_codes")]
    assert signals, f"应产出信号记录；errors={tick.errors}"
    latest = signals[-1]

    assert latest["signal_status"] == "WARMUP", latest["signal_status"]
    assert latest["holding_horizon"] == "UNVALIDATED", latest["holding_horizon"]
    joined = "；".join(latest["invalidation_conditions"])
    assert "预热不足" in joined, joined
    assert "不可执行" in joined, joined
    assert latest["action"] == "hold"


def test_actionable_signal_declares_intraday_eod_horizon(monkeypatch, tmp_path):
    """D-2 反向回归：真正可执行（已预热且有买卖动作）的信号必须给出确定口径。

    避免修复退化为「一切都标成不可执行」——那会让可用信号同样无法解读。
    """
    loop, base, code = _d2_loop(monkeypatch, tmp_path, ready_value=True, action="buy")
    candidate = Candidate(code, "测试", "SH", source="watchlist")

    tick = None
    for minutes in (5, 9.5, 11):
        tick = loop.run_once("2026-08-18", candidates_override=[candidate],
                             now=base + timedelta(minutes=minutes))
    signals = [s for s in tick.signals if s.get("reason_codes")]
    assert signals, f"应产出信号记录；errors={tick.errors}"
    latest = signals[-1]

    assert latest["action"] == "buy", latest["action"]
    assert latest["signal_status"] == "OBSERVE", latest["signal_status"]
    assert latest["holding_horizon"] == "INTRADAY_EOD", latest["holding_horizon"]
    joined = "；".join(latest["invalidation_conditions"])
    assert "当日收盘" in joined, joined


def _d4_metrics(monkeypatch, tmp_path, *, universe_code, prepare_errors):
    """跑一轮并返回 tick.metrics；universe_code 控制覆盖率口径。

    必须连 `load_watch_universe` 一起替换：`prepare()` 会把 `_universe_source`
    改写成它返回的 source（注入 candidates_override 时默认是 "override"），
    所以单靠事后赋值会被 prepare 覆盖。
    """
    loop, base, code = _d2_loop(monkeypatch, tmp_path, ready_value=True, action="buy")

    def fake_universe(config, signal_date, override=None, *, store=None):
        return [Candidate(code, "测试", "SH", source=universe_code)], universe_code, list(prepare_errors)

    monkeypatch.setattr("paper.watch.loop.load_watch_universe", fake_universe)

    candidate = Candidate(code, "测试", "SH", source="watchlist")
    tick = None
    for minutes in (5, 9.5, 11):
        tick = loop.run_once("2026-08-18", candidates_override=[candidate],
                             now=base + timedelta(minutes=minutes))
    assert tick is not None
    assert loop._universe_source == universe_code, loop._universe_source
    return tick.metrics


def test_coverage_scope_distinguishes_pool_mode_from_full_market(monkeypatch, tmp_path):
    """D-4 回归：`market_wide_coverage` 必须能被区分为「口径不符」而非「数据缺口」。

    缺陷复现：T1（观察池）模式源码为
        `market_wide_coverage = quote_coverage if source == "full_market" and ... else None`
    于是该字段恒为 None，前端只能显示固定字符串「未扫描全市场」——
    既不含数值，又容易被读成「全市场数据缺失」，而池内模式本就不扫描全市场。
    """
    # T1（池内）模式：不适用，但**必须**给出原因与适用范围
    metrics = _d4_metrics(monkeypatch, tmp_path, universe_code="t1", prepare_errors=[])

    assert metrics["coverage_scope"] == "OBSERVATION_POOL", metrics["coverage_scope"]
    assert metrics["market_wide_coverage"] is None, metrics["market_wide_coverage"]
    assert metrics["market_wide_coverage_na_reason"] == "UNIVERSE_NOT_FULL_MARKET", metrics
    # 真实覆盖率并没有丢失：它由 quote_coverage 承载（口径 = 本轮候选池）
    assert metrics["quote_coverage"] is not None, metrics
    assert metrics["quote_coverage"] > 0, metrics
    assert metrics["universe_count"] == 1, metrics

    # full_market 模式且池构建成功：此时该字段才真正表示全市场覆盖
    metrics_full = _d4_metrics(monkeypatch, tmp_path, universe_code="full_market",
                               prepare_errors=[])
    assert metrics_full["coverage_scope"] == "FULL_MARKET", metrics_full["coverage_scope"]
    assert metrics_full["market_wide_coverage"] is not None, metrics_full
    assert metrics_full["market_wide_coverage_na_reason"] is None, metrics_full


def test_full_market_unavailable_is_reported_as_such_not_as_pool_mode(monkeypatch, tmp_path):
    """D-4 反向回归：全市场池构建失败必须与「池内模式」区分开。

    避免修复退化为「一律说口径不符」——那会把真实的取数失败也说成不适用，
    属于用诚实的外壳掩盖故障。
    """
    metrics = _d4_metrics(monkeypatch, tmp_path, universe_code="full_market",
                          prepare_errors=["FULL_UNIVERSE_UNAVAILABLE:requests.ConnectionError"])
    assert metrics["coverage_scope"] == "FULL_MARKET", metrics["coverage_scope"]
    assert metrics["market_wide_coverage"] is None, metrics["market_wide_coverage"]
    assert metrics["market_wide_coverage_na_reason"] == "FULL_UNIVERSE_UNAVAILABLE", metrics


# ===== D-5 方向核查（负面结论）：重启是否会重复播报同一根 bar =====

_REPLAY_BASE = datetime(2026, 8, 18, 9, 30)
_REPLAY_CODE = "600519"
_REPLAY_SEED = 19   # 19 根 × 5min → 结束于 11:05，仍在 09:30-11:30 窗口内


class _ReplayQuote:
    """按序返回价格；用尽后维持最后一个值，保证快照始终有效。"""

    def __init__(self, lasts):
        self._lasts = list(lasts)
        self._last = self._lasts[0] if self._lasts else 10.0

    def snapshot(self, code, name="", *, run_id="adhoc", received_at=None):
        if self._lasts:
            self._last = self._lasts.pop(0)
        quote = Quote(
            code=code, name="测试", exchange="SH", last=self._last, pct_chg=1.0, volume=1000,
            market_time=received_at, received_at=received_at,
            validity=QuoteValidity.VALID, limit_up_price=110.0,
        )
        return quote, MxResult(endpoint="/query", ok=True, code="0", message="ok")


class _ReplayBroker:
    def account(self, *args, **kwargs):
        return None, MxResult(endpoint="/account", ok=False, code="E", message="shadow")


def _replay_seed_bars(n=_REPLAY_SEED, period=5):
    return [
        WatchBar(_REPLAY_CODE, period, 10.0, 10.0, 10.0, 10.0, 1000,
                 _REPLAY_BASE + timedelta(minutes=period * i),
                 _REPLAY_BASE + timedelta(minutes=period * (i + 1)), closed=True)
        for i in range(n)
    ]


def _replay_loop(store, seed, log_base):
    return WatchLoop(
        PaperConfig(api_key="k", min_cash_reserve=0, max_total_weight=1.0,
                    max_positions=5, index_circuit_breaker=False),
        _ReplayQuote([10.0, 11.0, 10.5, 12.0, 9.5]),
        _ReplayBroker(),
        log_base=log_base,
        store=store,
        calendar=TradingCalendarService(source="test"),
        seed_bars={_REPLAY_CODE: seed} if seed is not None else None,
        now_fn=lambda: _REPLAY_BASE,
    )


def _replay_run(loop, minutes):
    """推进一轮（观察池用 monkeypatch 之外的方式注入），返回信号摘要。"""
    candidate = Candidate(_REPLAY_CODE, "测试", "SH", source="t1")
    tick = loop.run_once("2026-08-18", candidates_override=[candidate],
                         now=_REPLAY_BASE + timedelta(minutes=minutes))
    return [(s["bar_ts"], s["action"], tuple(s["reason_codes"])) for s in tick.signals]


def test_restart_does_not_rebroadcast_the_same_closed_bar(monkeypatch, tmp_path):
    """D-5 负面核查：重启**不会**重复播报同一根 bar（原先担心的缺陷不成立）。

    背景：`strategy_state.last_processed_bar_ts` 只写不读，曾被怀疑会导致重启后
    重放同一根 bar 的信号。本用例用真实 SQLite store 构造「重启」实测该主张。

    不成立的原因（结构性，而非偶然）：
      · `closed_bar` 主键为 `(code, period_min, start_ts)` 且写入走
        `ON CONFLICT ... DO UPDATE`（store.py:152-167, 709），同一根 bar 至多一行；
      · 信号只在 `bar_store.update_snapshot()` 返回的 `new_closed` 上产生，
        而 bar 仅在**跨桶**时关闭一次（bar_store.py:179-188）；
      · 因此重启后由 store 重建的 bar 只会进入策略预热序列，不会再次触发判定。

    该结论的价值：**阻止**后续基于错误缺陷描述去加一层「推测性去重」——
    那会引入新的状态而与既有主键约束重复。
    """
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    candidate = Candidate(_REPLAY_CODE, "测试", "SH", source="t1")
    monkeypatch.setattr(
        "paper.watch.loop.load_watch_universe",
        lambda cfg, d, ov=None, store=None: ([candidate], "t1", []),
    )

    # ---- 会话 1 ----
    loop1 = _replay_loop(store, _replay_seed_bars(), tmp_path)
    _replay_run(loop1, 97)          # 建立 forming bar
    sig1 = _replay_run(loop1, 100)  # 跨桶 → 关闭 1 根新 bar，产出信号
    assert sig1, "夹具应至少产出一条信号，否则本用例证明不了什么"

    persisted = store.load_recent_closed_bars(5, 80).get(_REPLAY_CODE, [])
    assert persisted, "会话1 必须把 bar 落库，否则重启场景不成立"
    # 结构性前提：同一 (code, period_min, start_ts) 至多一行
    dup_rows = store._conn.execute(
        "SELECT start_ts, COUNT(*) c FROM closed_bar "
        "WHERE code=? AND period_min=5 GROUP BY start_ts HAVING c > 1",
        (_REPLAY_CODE,),
    ).fetchall()
    assert not dup_rows, f"closed_bar 出现重复主键行: {dup_rows}"

    # ---- 会话 2：重启，历史来源改为 store（不再用内存 seed）----
    loop2 = _replay_loop(store, list(persisted), tmp_path)
    _replay_run(loop2, 103)         # 建立 forming bar
    sig2 = _replay_run(loop2, 105)  # 跨桶

    overlap = set(sig1) & set(sig2)
    assert not overlap, f"重启后重复播报了同一根 bar: {overlap}"
    # strategy_state 确实被写入（这是「只写不读」这一半的事实）
    rows = store._conn.execute(
        "SELECT last_processed_bar_ts, warmup_ready FROM strategy_state WHERE code=?",
        (_REPLAY_CODE,),
    ).fetchall()
    assert rows, "strategy_state 应被写入"
