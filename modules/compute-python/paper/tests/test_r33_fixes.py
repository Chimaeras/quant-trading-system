"""R33 回归测试：OMS 柜台状态机漏边、内部撮合零股、未知提交认领守卫、
serve 非有限值/异常兜底、balance 非有限值、北交所镜像交易所推断。

全部离线（stub 客户端 / 本地 SQLite），不发任何真实请求。
"""
from __future__ import annotations

import http.client
import json
import threading
from datetime import datetime, timedelta
from http.server import ThreadingHTTPServer
from pathlib import Path

import pytest

from paper.models import Balance, MxOrder
from paper.tests.test_watch import T0, _WatchBroker, _WatchQuote, _config
from paper.watch.oms import OmsService, OrderStatus
from paper.watch.store import SqliteWatchStore

T = datetime(2026, 8, 29, 9, 35)


def _oms(tmp_path, session="r33", channel="EXTERNAL_PAPER"):
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    return store, OmsService(store, session_id=session, channel=channel)


def _create(oms, *, side="buy", qty=100, price=10.5, code="000001", ts="2026-08-29T09:35:00"):
    order, _ = oms.create(
        run_id="run", strategy="MA_CROSS", signal_bar_ts=ts,
        code=code, side=side, qty=qty, limit_price=price, ttl_sec=600, now=T,
    )
    return order


# ------------------------------------------------------- OMS 柜台状态机漏边（P1-5）

class TestCounterStatusTransitions:
    def test_partial_order_can_be_rejected_by_counter(self, tmp_path):
        """部成之后柜台报废（status=9）必须可达。

        修复前 PARTIAL 的转移集合没有 REJECTED，sync_external 抛
        WatchStoreError → loop._live_halted 置位 → 实盘停到人工重启。
        """
        store, oms = _oms(tmp_path)
        try:
            order = _create(oms)
            oms.transition(order.client_order_id, OrderStatus.SUBMITTING)
            oms.transition(order.client_order_id, OrderStatus.SUBMITTED, broker_order_id="mx-1")
            partial = MxOrder("mx-1", "000001", "buy", 10.5, 100, 40, "3", ts="2026-08-29 09:36:00",
                              raw={"tradePrice": 1050, "tradePriceDec": 2})
            oms.sync_external([partial], T + timedelta(minutes=1))

            rejected = MxOrder("mx-1", "000001", "buy", 10.5, 100, 40, "9", ts="2026-08-29 09:37:00",
                               raw={"tradePrice": 1050, "tradePriceDec": 2})
            oms.sync_external([rejected], T + timedelta(minutes=2))

            assert oms.get(order.client_order_id).status == OrderStatus.REJECTED.value
        finally:
            store.close()

    def test_cancel_pending_order_can_be_rejected_by_counter(self, tmp_path):
        """撤单在途时柜台报废单同样必须可达。"""
        store, oms = _oms(tmp_path)
        try:
            order = _create(oms)
            oms.transition(order.client_order_id, OrderStatus.SUBMITTING)
            oms.transition(order.client_order_id, OrderStatus.SUBMITTED, broker_order_id="mx-2")
            oms.transition(order.client_order_id, OrderStatus.CANCEL_PENDING)

            rejected = MxOrder("mx-2", "000001", "buy", 10.5, 100, 0, "9", ts="2026-08-29 09:37:00")
            oms.sync_external([rejected], T + timedelta(minutes=2))

            assert oms.get(order.client_order_id).status == OrderStatus.REJECTED.value
        finally:
            store.close()

    def test_stale_submitted_snapshot_does_not_roll_partial_back(self, tmp_path):
        """柜台快照 status=1/2 且 filled_qty=0，但本地已有成交证据时不得倒退为 SUBMITTED。"""
        store, oms = _oms(tmp_path)
        try:
            order = _create(oms)
            oms.transition(order.client_order_id, OrderStatus.SUBMITTING)
            oms.transition(order.client_order_id, OrderStatus.SUBMITTED, broker_order_id="mx-3")
            partial = MxOrder("mx-3", "000001", "buy", 10.5, 100, 40, "3", ts="2026-08-29 09:36:00",
                              raw={"tradePrice": 1050, "tradePriceDec": 2})
            oms.sync_external([partial], T + timedelta(minutes=1))

            # 旧快照：状态仍是「已报」，累计成交为 0
            stale = MxOrder("mx-3", "000001", "buy", 10.5, 100, 0, "2", ts="2026-08-29 09:36:30")
            oms.sync_external([stale], T + timedelta(minutes=2))

            current = oms.get(order.client_order_id)
            assert current.status == OrderStatus.PARTIAL.value
            assert current.filled_qty == 40
        finally:
            store.close()

    def test_mirror_row_marks_920_code_as_bj(self, tmp_path):
        """北交所 920xxx 也以 "9" 开头，镜像单交易所必须先判 BJ。"""
        store, oms = _oms(tmp_path)
        try:
            oms.sync_external(
                [MxOrder("mx-9", "920001", "buy", 10.0, 100, 0, "2", ts="2026-08-29 09:36:00")],
                T + timedelta(minutes=1),
            )
            row = store._conn.execute(
                "SELECT exchange FROM external_order WHERE broker_order_id=?", ("mx-9",),
            ).fetchone()
            assert row["exchange"] == "BJ"
        finally:
            store.close()


# ------------------------------------------------------- 内部撮合零股（P1-6）

def test_internal_odd_lot_sell_is_matched_without_lot_rounding(tmp_path: Path):
    """odd_lot_sell 允许卖出不足一手的可卖量（如 50 股），撮合端不得再取整。

    修复前 qty = qty // 100 * 100 → 0，该委托永远等不到成交，零股永久滞留持仓。
    """
    from paper.watch.internal_broker import InternalPaperBroker
    from paper.watch.models import WatchBar

    config = _config(
        watch_execution_mode="INTERNAL_PAPER",
        paper_session_id="test-odd-lot",
        internal_initial_cash=10_000,
        internal_fill_participation=0.10,
    )
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    try:
        broker = InternalPaperBroker(store, config, now_fn=lambda: T0)
        store.save_simulation(
            {
                "session_id": config.paper_session_id,
                "trade_date": T0.strftime("%Y-%m-%d"),
                "initial_cash": config.internal_initial_cash,
                "cash": config.internal_initial_cash,
                "frozen_cash": 0.0,
                "realized_pnl": 0.0,
                "created_at": T0.isoformat(),
            },
            [{
                "code": "000001", "name": "平安银行", "qty": 50, "available_qty": 50,
                "today_buy_qty": 0, "avg_cost": 9.0, "last_price": 10.0,
            }],
        )
        oms = OmsService(store, session_id=config.paper_session_id, channel="INTERNAL_PAPER")
        order, _ = oms.create(
            run_id="run-1", strategy="MA_CROSS", signal_bar_ts=T0.isoformat(),
            code="000001", side="sell", qty=50, limit_price=10.0, ttl_sec=600, now=T0,
        )
        order = oms.transition(order.client_order_id, OrderStatus.SUBMITTING)
        assert broker.submit_local_order(order).ok is True

        fills = broker.process_bars([
            WatchBar(
                code="000001", period_min=5, open=10.0, high=10.0, low=10.0, close=10.0,
                volume=1000, start_ts=T0, end_ts=T0 + timedelta(minutes=5), closed=True,
            )
        ], now=T0 + timedelta(minutes=5))

        assert len(fills) == 1
        assert fills[0]["qty"] == 50
        filled = oms.get(order.client_order_id)
        assert filled.status == OrderStatus.FILLED.value
        assert filled.filled_qty == 50
    finally:
        store.close()


def test_internal_buy_still_requires_round_lots(tmp_path: Path):
    """买单取整不能被零股修复破坏：零股旁路只对卖单开放。"""
    from paper.watch.internal_broker import InternalPaperBroker
    from paper.watch.models import WatchBar

    config = _config(
        watch_execution_mode="INTERNAL_PAPER",
        paper_session_id="test-buy-lot",
        internal_initial_cash=10_000,
        internal_fill_participation=0.10,
    )
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    try:
        broker = InternalPaperBroker(store, config, now_fn=lambda: T0)
        oms = OmsService(store, session_id=config.paper_session_id, channel="INTERNAL_PAPER")
        order, _ = oms.create(
            run_id="run-1", strategy="MA_CROSS", signal_bar_ts=T0.isoformat(),
            code="600519", side="buy", qty=150, limit_price=10.0, ttl_sec=600, now=T0,
        )
        order = oms.transition(order.client_order_id, OrderStatus.SUBMITTING)
        assert broker.submit_local_order(order).ok is True

        fills = broker.process_bars([
            WatchBar(
                code="600519", period_min=5, open=10.0, high=10.0, low=10.0, close=10.0,
                volume=1000, start_ts=T0, end_ts=T0 + timedelta(minutes=5), closed=True,
            )
        ], now=T0 + timedelta(minutes=5))

        assert len(fills) == 1
        assert fills[0]["qty"] == 100  # 150 → 取整到 100 手内可成交部分
    finally:
        store.close()


# --------------------------------------------------- 未知提交对账守卫（P1-7）

class TestUnknownSubmitReconcile:
    def _loop(self, tmp_path):
        from paper.watch.loop import WatchLoop

        return WatchLoop(_config(), _WatchQuote(), _WatchBroker(), log_base=tmp_path, seed_bars={})

    def _pending(self, **overrides):
        pending = {
            "stock_code": "600519", "side": "buy", "qty": 100,
            "price": 10.0, "run_id": "run",
        }
        pending.update(overrides)
        return pending

    def _state(self, tmp_path, pending, open_order_ids=None):
        from paper.watch.state import WatchState

        loop = self._loop(tmp_path)
        loop._state = WatchState(
            trade_date="2026-08-18",
            unknown_submits=[pending],
            open_order_ids=list(open_order_ids or []),
        )
        loop._unknown_halt = True
        loop._live_halted = True
        return loop

    def test_preexisting_same_params_order_is_not_claimed(self, tmp_path: Path):
        """对账前既有的同参委托不代表本次提交到达柜台，不得据此解除冻结。"""
        pending = self._pending()
        loop = self._state(tmp_path, pending, open_order_ids=["o-old"])
        candidate = MxOrder("o-old", "600519", "buy", 10.0, 100, 0, "2")

        messages = loop._reconcile_unknown_submits([candidate], "2026-08-18")

        assert messages == []
        assert loop._state.unknown_submits == [pending]
        assert loop._live_halted is True

    def test_active_order_not_in_open_ids_releases_freeze(self, tmp_path: Path):
        pending = self._pending()
        loop = self._state(tmp_path, pending)
        candidate = MxOrder("o-new", "600519", "buy", 10.0, 100, 0, "2")

        messages = loop._reconcile_unknown_submits([candidate], "2026-08-18")

        assert loop._state.unknown_submits == []
        assert any(m.startswith("UNKNOWN_SUBMIT_RECONCILED") for m in messages)

    def test_terminal_zero_fill_order_releases_freeze(self, tmp_path: Path):
        """废单/全撤也是最强的「已到达柜台」证据。"""
        pending = self._pending()
        loop = self._state(tmp_path, pending)
        candidate = MxOrder("o-cancel", "600519", "buy", 10.0, 100, 0, "8")

        loop._reconcile_unknown_submits([candidate], "2026-08-18")

        assert loop._state.unknown_submits == []

    def test_terminal_with_fills_stays_frozen_and_flags_manual(self, tmp_path: Path):
        """已终态但带成交：成交未被本进程记账，保持冻结并要求人工核对。"""
        pending = self._pending()
        loop = self._state(tmp_path, pending)
        candidate = MxOrder("o-filled", "600519", "buy", 10.0, 100, 100, "4")

        messages = loop._reconcile_unknown_submits([candidate], "2026-08-18")

        assert loop._state.unknown_submits == [pending]
        assert loop._live_halted is True
        assert any(m.startswith("UNKNOWN_SUBMIT_FILLED_NEEDS_MANUAL") for m in messages)

    def test_price_mismatch_is_not_claimed(self, tmp_path: Path):
        pending = self._pending()
        loop = self._state(tmp_path, pending)
        candidate = MxOrder("o-other", "600519", "buy", 12.0, 100, 0, "2")

        assert loop._reconcile_unknown_submits([candidate], "2026-08-18") == []
        assert loop._state.unknown_submits == [pending]


# ---------------------------------------------------- serve 清洗与兜底（P1/P2）

def test_optional_finite_converts_non_finite_to_none():
    from paper.watch.serve import _optional_finite

    assert _optional_finite(None) is None
    assert _optional_finite("nan") is None
    assert _optional_finite(float("inf")) is None
    assert _optional_finite("-inf") is None
    assert _optional_finite("abc") is None
    # 合法值（含 0）必须原样保留：0 是有意义的可用资金，不能被当成「未知」
    assert _optional_finite(0) == 0.0
    assert _optional_finite("12.5") == 12.5


def test_infer_exchange_prefers_bj_for_920_prefix():
    from paper.watch.serve import _infer_exchange

    assert _infer_exchange("920001") == "BJ"
    assert _infer_exchange("430047") == "BJ"
    assert _infer_exchange("830799") == "BJ"
    assert _infer_exchange("600519") == "SH"
    assert _infer_exchange("000001") == "SZ"


def test_pool_exchange_rejects_code_market_mismatch():
    from paper.watch.serve import BadRequestError, _pool_exchange

    assert _pool_exchange("BSE", "920001") == "BJ"
    assert _pool_exchange("", "920001") == "BJ"
    with pytest.raises(BadRequestError):
        _pool_exchange("SH", "920001")
    with pytest.raises(BadRequestError):
        _pool_exchange("HK", "920001")


def test_server_returns_500_json_instead_of_dropping_connection(tmp_path: Path, monkeypatch):
    """处理函数里未预期异常必须回 5xx JSON；修复前 BaseHTTPRequestHandler 直接断连。"""
    from paper.watch import serve as serve_mod

    def boom(_log_base):
        raise RuntimeError("boom")

    monkeypatch.setattr(serve_mod, "_days", boom)
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0),
        serve_mod.make_handler(tmp_path, store_path=tmp_path / "runtime.sqlite3"),
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
        conn.request("GET", "/api/watch/days")
        res = conn.getresponse()
        body = json.loads(res.read().decode("utf-8"))
        assert res.status == 500
        assert body["error"] == "internal_error"
        assert body["message"] == "RuntimeError"
    finally:
        server.shutdown()
        server.server_close()


# ------------------------------------------------------- 非有限值拦截（P2）

def test_balance_from_mx_rejects_nan_and_infinity():
    """json.loads 默认接受 NaN/Infinity 字面量，裸 float() 会放行并污染全部风控比较。"""
    with pytest.raises(ValueError, match="非有限值"):
        Balance.from_mx({"totalAssets": float("nan"), "availBalance": 1.0})
    with pytest.raises(ValueError, match="非有限值"):
        Balance.from_mx({"totalAssets": 1.0, "availBalance": float("inf")})
    with pytest.raises(ValueError, match="非有限值"):
        Balance.from_mx({"totalAssets": 1.0, "availBalance": 1.0, "frozenMoney": float("inf")})


def test_balance_from_mx_keeps_valid_values():
    balance = Balance.from_mx({
        "totalAssets": "100000", "availBalance": 60000, "frozenMoney": 0,
        "totalPosValue": 40000, "accName": "测试", "accID": "acc-1",
    })
    assert balance.total_assets == 100000.0
    assert balance.avail_balance == 60000.0
    assert balance.frozen == 0.0
    assert balance.market_value == 40000.0
