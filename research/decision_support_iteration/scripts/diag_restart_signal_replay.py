"""D-5/D-6 诊断（不属于交付物）：重启后是否会对**同一根已处理 bar** 重复发信号。

缺陷主张：`strategy_state.last_processed_bar_ts` 只写不读，因此重启后
策略状态从 store 的 bar 重建，同一根 bar 可能被再次判定并再次 emit("SIGNAL")。

关键约束：`in_trading_window` 为 09:30-11:30 / 13:00-15:00，且 bar 只在
**更晚的桶**收到快照时才确认结束（bar_store.py:179）。因此每根 bar 的关闭
都需要两次 run_once：一次建立 forming bar，下一次跨桶关闭它。

本脚本用真实 SqliteWatchStore 构造「重启」场景，实测是否真会重复。
"""
from __future__ import annotations

import pathlib
import sys
import tempfile
from datetime import datetime, timedelta

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / "modules" / "compute-python"))

from paper.config import PaperConfig            # noqa: E402
from paper.models import Candidate, Quote, QuoteValidity  # noqa: E402
from paper.mx_result import MxResult            # noqa: E402
from paper.trading_calendar import TradingCalendarService  # noqa: E402
from paper.watch.loop import WatchLoop          # noqa: E402
from paper.watch.models import WatchBar         # noqa: E402
from paper.watch.store import SqliteWatchStore  # noqa: E402

BASE = datetime(2026, 8, 18, 9, 30)
CODE = "600519"
N_SEED = 19          # 19 根 → 结束于 base+95 = 11:05，仍在窗口内


class _SeqQuote:
    """每次快照返回下一个价格；用尽后维持最后一个值。"""

    def __init__(self, lasts):
        self._lasts = list(lasts)
        self._last = self._lasts[0] if self._lasts else 10.0

    def snapshot(self, code, name="", *, run_id="adhoc", received_at=None):
        if self._lasts:
            self._last = self._lasts.pop(0)
        q = Quote(code=code, name="测试", exchange="SH", last=self._last, pct_chg=1.0, volume=1000,
                  market_time=received_at, received_at=received_at,
                  validity=QuoteValidity.VALID, limit_up_price=110.0)
        return q, MxResult(endpoint="/query", ok=True, code="0", message="ok")


class _Broker:
    def account(self, *a, **k):
        return None, MxResult(endpoint="/account", ok=False, code="E", message="shadow")


def _seed_bars(n=N_SEED, period=5):
    return [WatchBar(CODE, period, 10.0, 10.0, 10.0, 10.0, 1000,
                     BASE + timedelta(minutes=period * i),
                     BASE + timedelta(minutes=period * (i + 1)), closed=True)
            for i in range(n)]


def _loop(store, seed):
    cfg = PaperConfig(api_key="k", min_cash_reserve=0, max_total_weight=1.0,
                      max_positions=5, index_circuit_breaker=False)
    return WatchLoop(cfg, _SeqQuote([10.0, 11.0, 10.5, 12.0, 9.5]), _Broker(),
                     log_base=store.path.parent, store=store,
                     calendar=TradingCalendarService(source="test"),
                     seed_bars={CODE: seed} if seed is not None else None,
                     now_fn=lambda: BASE)


def _run(loop, minutes, tag=""):
    """推进一轮，返回 (信号摘要, 诊断字符串)。"""
    import paper.watch.loop as loop_mod
    orig = loop_mod.load_watch_universe
    loop_mod.load_watch_universe = lambda cfg, d, ov=None, store=None: (
        [Candidate(CODE, "测试", "SH", source="t1")], "t1", [])
    try:
        tick = loop.run_once("2026-08-18",
                             candidates_override=[Candidate(CODE, "测试", "SH", source="t1")],
                             now=BASE + timedelta(minutes=minutes))
    finally:
        loop_mod.load_watch_universe = orig
    diag = (f"  [diag {tag}] t=+{minutes}min status={tick.status} "
            f"signals={len(tick.signals)} new_closed={len(tick.new_closed_bars)} "
            f"errors={tick.errors[:2]}")
    return [(s["bar_ts"], s["action"], tuple(s["reason_codes"])) for s in tick.signals], diag


def _dump_strategy_state(store):
    try:
        return store._conn.execute(
            "SELECT code, strategy, last_processed_bar_ts, warmup_ready "
            "FROM strategy_state ORDER BY last_processed_bar_ts"
        ).fetchall()
    except Exception as exc:  # noqa: BLE001
        return f"<读取失败 {type(exc).__name__}: {exc}>"


def main() -> int:
    td = tempfile.mkdtemp(prefix="diag_replay_")
    store = SqliteWatchStore(pathlib.Path(td) / "runtime.sqlite3")
    out: list[str] = []
    try:
        # ---- 会话 1 ----
        loop1 = _loop(store, _seed_bars())
        _, d1 = _run(loop1, 97, "s1")     # 建立 forming bar
        out.append(d1)
        sig1, d2 = _run(loop1, 100, "s1")  # 跨桶：关闭 1 根新 bar → 产信号
        out.append(d2)
        out.append(f"会话1 信号: {sig1}")

        persisted = store.load_recent_closed_bars(5, 80).get(CODE, [])
        out.append(f"会话1 后 store 内 bar 数: {len(persisted)}"
                   f"（末根 end_ts={persisted[-1].end_ts.isoformat() if persisted else '—'}）")
        out.append(f"会话1 后 strategy_state: {_dump_strategy_state(store)}")

        # ---- 会话 2：模拟重启（历史来源 = store，不再用内存 seed）----
        loop2 = _loop(store, list(persisted))
        sig2a, d3 = _run(loop2, 103, "s2")   # 仅建立 forming bar（opening snapshot）
        out.append(d3)
        sig2b, d4 = _run(loop2, 105, "s2")   # 跨桶：可能重放同一根 bar
        out.append(d4)
        sig2 = sig2a + sig2b
        out.append(f"会话2 信号: {sig2}")
        out.append(f"会话2 后 strategy_state: {_dump_strategy_state(store)}")

        dup = set(sig1) & set(sig2)
        out.append("")
        out.append(f"两份信号交集（=重复播报的同一根 bar）: {dup if dup else '空'}")
        out.append("结论: " + ("存在重复播报" if dup else "未观察到重复播报"))
    finally:
        for name in ("_event_log", "_events", "_handle"):
            h = getattr(store, name, None)
            close = getattr(h, "close", None)
            if callable(close):
                try:
                    close()
                except Exception:  # noqa: BLE001
                    pass
    text = "\n".join(out)
    (pathlib.Path(__file__).resolve().parents[1] / "diag_restart_signal_replay.txt").write_text(
        text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
