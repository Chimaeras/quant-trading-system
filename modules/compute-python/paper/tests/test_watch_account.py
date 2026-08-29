"""盯盘模拟账户只读端点（serve.py server 侧）测试。

复用 SqliteWatchStore 建库造种子（sim_account/sim_position/local_order/fill），
直接调用 serve 的查询函数并启动 HTTP 服务验证端点路由，断言：
  - /account 字段齐全、总资产/浮动盈亏/已实现盈亏/收益率正确，session_id 缺省自动落到最新会话
  - /orders 能取到终态单、/fills 成交记录齐全
  - /pnl 逐代码重放已实现与 sim_account.realized_pnl 一致（consistent=True）
"""
from __future__ import annotations

import http.client
import json
import math
import sqlite3
import threading
from datetime import datetime
from http.server import ThreadingHTTPServer

import pytest

from paper.watch.serve import (
    _account, _decode_metadata, _fills, _orders, _pnl, make_handler,
)
from paper.watch.store import SqliteWatchStore, WatchStoreError
from paper.watch.oms import OmsService, OrderStatus
from paper.models import MxOrder

SESSION = "acct-test"
OLD_SESSION = "old-sess"

# 一笔买卖重放得到已实现 392.5 = (11.0 - 10.005) * 400 - 5.5，与 sim_account.realized_pnl 一致
REALIZED = 392.5


def _build_store(store_path):
    store = SqliteWatchStore(store_path)
    # 观察池成员提供股票名（serve 用名字兜底回填）
    store.upsert_watch_pool_member({"code": "000001", "name": "平安银行", "exchange": "SZ"})
    store.upsert_watch_pool_member({"code": "600519", "name": "贵州茅台", "exchange": "SH"})
    store.close()

    conn = sqlite3.connect(store_path)
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        conn.executemany(
            """
            INSERT INTO sim_account(
                session_id, trade_date, initial_cash, cash, frozen_cash,
                realized_pnl, created_at, updated_at
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                (SESSION, "2026-08-26", 100000.0, 60000.0, 5000.0, REALIZED,
                 "2026-08-26", "2026-08-26 11:00:00"),
                (OLD_SESSION, "2026-08-25", 50000.0, 40000.0, 0.0, 0.0,
                 "2026-08-25", "2026-08-25 11:00:00"),
            ],
        )
        conn.executemany(
            """
            INSERT INTO sim_position(
                session_id, code, name, qty, available_qty, today_buy_qty,
                avg_cost, last_price, updated_at
            ) VALUES(?, ?, '', ?, ?, ?, ?, ?, ?)
            """,
            [
                (SESSION, "000001", 600, 600, 0, 10.0, 11.0, "2026-08-26 11:00:00"),
                (SESSION, "600519", 200, 0, 200, 1500.0, 1520.0, "2026-08-26 11:00:00"),
            ],
        )
        conn.executemany(
            """
            INSERT INTO local_order(
                client_order_id, idempotency_key, session_id, channel, run_id,
                strategy, signal_bar_ts, code, side, order_type, limit_price,
                qty, filled_qty, avg_fill_price, total_fee, status,
                broker_order_id, created_at, updated_at, expires_at,
                last_error_code, last_error_message, metadata_json
            ) VALUES(?, ?, ?, 'internal', 'run1', 's1', ?, ?, ?, 'MARKET', ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', '', '{}')
            """,
            [
                ("ord1", "ik1", SESSION, "2026-08-26 09:29:00", "000001", "buy", None,
                 1000, 1000, 10.0, 5.0, "FILLED", "b1", "2026-08-26 09:29:30", "2026-08-26 09:30:00"),
                ("ord2", "ik2", SESSION, "2026-08-26 09:30:00", "600519", "buy", None,
                 200, 200, 1500.0, 90.0, "FILLED", "b2", "2026-08-26 09:30:30", "2026-08-26 09:31:00"),
                ("ord3", "ik3", SESSION, "2026-08-26 09:59:00", "000001", "sell", None,
                 400, 400, 11.0, 5.5, "FILLED", "b3", "2026-08-26 09:59:30", "2026-08-26 10:00:00"),
                ("ord4", "ik4", SESSION, "2026-08-26 10:30:00", "600519", "buy", None,
                 200, 0, None, 0.0, "SUBMITTED", "b4", "2026-08-26 10:30:00", "2026-08-26 10:30:00"),
            ],
        )
        conn.executemany(
            """
            INSERT INTO fill(
                fill_id, client_order_id, session_id, code, side, qty, price,
                gross_amount, commission, stamp_tax, total_fee, filled_at,
                bar_ts, price_source, metadata_json
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', 'SIMULATOR', '{}')
            """,
            [
                ("f1", "ord1", SESSION, "000001", "buy", 1000, 10.0,
                 10000.0, 5.0, 0.0, 5.0, "2026-08-26 09:30:00"),
                ("f2", "ord2", SESSION, "600519", "buy", 200, 1500.0,
                 300000.0, 90.0, 0.0, 90.0, "2026-08-26 09:31:00"),
                ("f3", "ord3", SESSION, "000001", "sell", 400, 11.0,
                 4400.0, 3.3, 2.2, 5.5, "2026-08-26 10:00:00"),
            ],
        )
        conn.commit()
    finally:
        conn.close()


def test_account_fields_and_session_autodetect(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    _build_store(store_path)

    acc = _account(store_path, "")
    assert acc["available"] is True
    assert acc["session_id"] == SESSION  # 缺省取自 updated_at 最新的 acct-test
    assert acc["position_count"] == 2
    assert acc["open_order_count"] == 1  # ord4 SUBMITTED 在途
    assert acc["cash"] == 60000.0
    assert acc["frozen_cash"] == 5000.0
    assert acc["avail_balance"] == acc["cash"] - acc["frozen_cash"]  # 可用=现金-冻结
    assert acc["price_basis"] == "snapshot"  # 未实现/市值基于持仓快照价（last_price）
    assert acc["market_value"] == 600 * 11.0 + 200 * 1520.0  # 310600
    assert acc["total_assets"] == 60000.0 + 310600.0
    assert acc["unrealized_pnl"] == (11.0 - 10.0) * 600 + (1520.0 - 1500.0) * 200  # 4600
    assert acc["realized_pnl"] == REALIZED
    assert acc["total_profit"] == REALIZED + 4600.0
    assert acc["total_return_pct"] == (370600.0 / 100000.0 - 1) * 100
    names = {p["code"]: p["name"] for p in acc["positions"]}
    assert names["000001"] == "平安银行"
    assert names["600519"] == "贵州茅台"
    assert acc["positions"][0]["market_value"] == 600 * 11.0


def test_orders_include_terminal_and_filter(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    _build_store(store_path)

    all_orders = _orders(store_path, {"limit": ["200"]})
    assert all_orders["available"] is True
    assert all_orders["count"] == 4
    statuses = {o["status"] for o in all_orders["orders"]}
    # 终态单能取到（历史上曾经只有事件流在途单，容易漏掉 FILLED/CANCELLED）
    assert "FILLED" in statuses
    assert "SUBMITTED" in statuses
    # 严格按 created_at 倒序
    created = [o["created_at"] for o in all_orders["orders"]]
    assert created == sorted(created, reverse=True)

    filled = _orders(store_path, {"status": ["FILLED"], "limit": ["200"]})
    assert filled["count"] == 3
    assert all(o["status"] == "FILLED" for o in filled["orders"])

    snapped = _orders(store_path, {"session_id": [OLD_SESSION], "limit": ["200"]})
    assert snapped["count"] == 0


def test_fills_endpoint(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    _build_store(store_path)

    fills = _fills(store_path, {"limit": ["200"]})
    assert fills["available"] is True
    assert fills["count"] == 3
    assert {f["code"] for f in fills["fills"]} == {"000001", "600519"}
    row = next(f for f in fills["fills"] if f["fill_id"] == "f3")
    assert row["side"] == "sell"
    assert row["price"] == 11.0
    assert row["total_fee"] == 5.5


def test_pnl_replay_matches_account(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    _build_store(store_path)

    pnl = _pnl(store_path, SESSION)
    assert pnl["available"] is True
    totals = pnl["totals"]
    assert totals["realized"] == pytest.approx(REALIZED)
    assert totals["realized_account"] == pytest.approx(REALIZED)
    assert totals["consistent"] is True  # 重放已实现与 sim_account.realized_pnl 一致
    assert pnl["price_basis"] == "snapshot"
    assert totals["unrealized"] == pytest.approx(4600.0)
    assert totals["total"] == pytest.approx(REALIZED + 4600.0)

    codes = {c["code"]: c for c in pnl["per_code"]}
    assert set(codes) == {"000001", "600519"}
    assert codes["000001"]["trades"][0]["realized"] == pytest.approx(REALIZED)
    assert codes["000001"]["realized"] == pytest.approx(REALIZED)
    assert codes["000001"]["unrealized"] == pytest.approx((11.0 - 10.0) * 600)
    # 现持仓/均价以 sim_position 为权威（重放可能因 seed/裁剪偏离），与未实现、前端实时覆盖口径一致
    assert codes["000001"]["qty"] == 600
    assert codes["000001"]["avg_cost"] == pytest.approx(10.0)
    assert codes["600519"]["trades"] == []
    assert codes["600519"]["unrealized"] == pytest.approx((1520.0 - 1500.0) * 200)
    assert codes["600519"]["qty"] == 200
    assert codes["600519"]["avg_cost"] == pytest.approx(1500.0)


def test_watch_serve_routes_account_orders(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    _build_store(store_path)
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0), make_handler(tmp_path, store_path=store_path)
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
        conn.request("GET", "/api/watch/account")
        res = conn.getresponse()
        acc = json.loads(res.read().decode("utf-8"))
        assert res.status == 200
        assert acc["session_id"] == SESSION

        conn.request("GET", "/api/watch/orders?status=FILLED")
        res = conn.getresponse()
        orders = json.loads(res.read().decode("utf-8"))
        assert res.status == 200
        assert orders["count"] == 3

        conn.request("GET", "/api/watch/positions")
        res = conn.getresponse()
        payload = json.loads(res.read().decode("utf-8"))
        assert res.status == 404
        assert payload["error"] == "unknown_resource"

        conn.request("GET", "/api/watch/unknown")
        res = conn.getresponse()
        payload = json.loads(res.read().decode("utf-8"))
        assert res.status == 404
        assert payload["error"] == "unknown_resource"
        conn.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)


def test_decode_metadata_is_defensive():
    """单条 metadata_json 坏数据不拖垮 /orders 或 /fills。"""
    assert _decode_metadata(None) == {}
    assert _decode_metadata("") == {}
    assert _decode_metadata("{not-json") == {}
    assert _decode_metadata("[1,2]") == {}          # 非 dict 视为空
    assert _decode_metadata('{"a": 1}') == {"a": 1}


def test_orders_survive_corrupt_metadata_row(tmp_path):
    """有一行 metadata_json 非法时，/orders 仍返回全部订单且坏行 metadata 为空。"""
    store_path = tmp_path / "runtime.sqlite3"
    _build_store(store_path)
    conn = sqlite3.connect(store_path)
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        conn.execute(
            """
            INSERT INTO local_order(
                client_order_id, idempotency_key, session_id, channel, run_id,
                strategy, signal_bar_ts, code, side, order_type, limit_price,
                qty, filled_qty, avg_fill_price, total_fee, status,
                broker_order_id, created_at, updated_at, expires_at,
                last_error_code, last_error_message, metadata_json
            ) VALUES(?, ?, ?, 'internal', 'run1', 's1', ?, ?, ?, 'MARKET', ?, ?, ?, ?, ?, 'FILLED', ?, ?, ?, '', '', '', '{bad-json')
            """,
            ("ord-corrupt", "ik-c", SESSION, "2026-08-26 11:00:00", "000001", "buy",
             None, 100, 100, 10.0, 5.0, "b-c", "2026-08-26 11:00:00", "2026-08-26 11:00:00"),
        )
        conn.commit()
    finally:
        conn.close()

    result = _orders(store_path, {"limit": ["200"]})
    assert result["count"] == 5
    corrupt = next(o for o in result["orders"] if o["client_order_id"] == "ord-corrupt")
    assert corrupt["metadata"] == {}


def test_finite_sanitizes_nan_and_inf():
    """_finite 把 nan/inf/非法值收敛为默认值，杜绝 NaN/Infinity 泄入 JSON。"""
    from paper.watch.serve import _finite

    assert _finite("nan", 5.0) == 5.0
    assert _finite("inf", 5.0) == 5.0
    assert _finite("abc", 7.0) == 7.0
    assert _finite(None, 3.0) == 3.0
    assert _finite(float("nan")) == 0.0
    assert _finite(float("inf")) == 0.0
    assert _finite(12.5) == 12.5
    assert _finite(0) == 0.0


def test_account_survives_nan_price(tmp_path):
    """一条 last_price='nan' 的持仓不拖垮 /account：数值有限且响应不含 NaN/Infinity 字面量。"""
    store_path = tmp_path / "runtime.sqlite3"
    _build_store(store_path)
    conn = sqlite3.connect(store_path)
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        conn.execute(
            "UPDATE sim_position SET last_price='nan' WHERE session_id=? AND code='000001'",
            (SESSION,),
        )
        conn.commit()
    finally:
        conn.close()

    # 直接查 _account：_finite 兜底将 nan 收敛为 avg_cost，数值必须有限。
    acc = _account(store_path, SESSION)
    assert acc["available"] is True
    for key in ("cash", "frozen_cash", "market_value", "total_assets",
                "realized_pnl", "unrealized_pnl", "total_profit", "total_return_pct"):
        assert math.isfinite(float(acc[key])), key
    assert math.isfinite(float(acc["positions"][0]["market_value"]))
    assert math.isfinite(float(acc["positions"][0]["profit"]))

    # 走 HTTP 路由：_json_response 现为 allow_nan=False，命中 NaN 会抛错而非输出字面量。
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0), make_handler(tmp_path, store_path=store_path)
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
        conn.request("GET", "/api/watch/account")
        res = conn.getresponse()
        body = res.read().decode("utf-8")
        assert res.status == 200
        assert "nan" not in body.lower()
        assert "inf" not in body.lower()
        conn.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)

def test_account_snapshot_pruned_to_latest(tmp_path):
    """save_account_snapshot 每轮追加且 serve 只读最新：连存超量后仅保留最新 _ACCOUNT_SNAPSHOT_KEEP 条。"""
    from paper.watch.store import _ACCOUNT_SNAPSHOT_KEEP

    store_path = tmp_path / "runtime.sqlite3"
    store = SqliteWatchStore(store_path)
    total = _ACCOUNT_SNAPSHOT_KEEP + 20
    try:
        for i in range(total):
            store.save_account_snapshot({
                "snapshot_id": f"run-{i}:before",
                "session_id": SESSION,
                "channel": "internal",
                "trade_date": "2026-08-26",
                "captured_at": f"2026-08-26 09:30:{i:02d}",
                "consistent": True,
                "payload": {"total_assets": float(i)},
            })
    finally:
        store.close()

    conn = sqlite3.connect(store_path)
    try:
        rows = conn.execute(
            "SELECT captured_at FROM account_snapshot WHERE session_id=? "
            "ORDER BY captured_at DESC, rowid DESC", (SESSION,)
        ).fetchall()
    finally:
        conn.close()
    assert len(rows) == _ACCOUNT_SNAPSHOT_KEEP
    # 最新一条保留，最旧被裁剪
    assert rows[0][0] == f"2026-08-26 09:30:{total - 1:02d}"


def test_orders_fills_filter_by_trade_date(tmp_path):
    """_orders/_fills 支持可选 trade_date 过滤（委托按 created_at、成交按 filled_at 日期前缀）。"""
    store_path = tmp_path / "runtime.sqlite3"
    _build_store(store_path)

    conn = sqlite3.connect(store_path)
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        conn.execute(
            """
            INSERT INTO local_order(
                client_order_id, idempotency_key, session_id, channel, run_id,
                strategy, signal_bar_ts, code, side, order_type, limit_price,
                qty, filled_qty, avg_fill_price, total_fee, status,
                broker_order_id, created_at, updated_at, expires_at,
                last_error_code, last_error_message, metadata_json
            ) VALUES(?, ?, ?, 'internal', 'run1', 's1', ?, ?, ?, 'MARKET', ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', '', '{}')
            """,
            ("ord-x", "ik-x", SESSION, "2026-08-27 09:00:00", "000001", "buy", None,
             500, 500, 10.5, 2.0, "FILLED", "bx", "2026-08-27 09:00:00", "2026-08-27 09:01:00"),
        )
        conn.execute(
            """
            INSERT INTO fill(
                fill_id, client_order_id, session_id, code, side, qty, price,
                gross_amount, commission, stamp_tax, total_fee, filled_at,
                bar_ts, price_source, metadata_json
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', 'SIMULATOR', '{}')
            """,
            ("f-x", "ord-x", SESSION, "000001", "buy", 500, 10.5,
             5250.0, 2.0, 0.0, 2.0, "2026-08-27 09:01:00"),
        )
        conn.commit()
    finally:
        conn.close()

    # 无日期：全量（4 单 + 1 单 = 5；成交 3 + 1 = 4）
    assert _orders(store_path, {})["count"] == 5
    assert _fills(store_path, {})["count"] == 4
    # 按日期：各只回该日
    day2_orders = _orders(store_path, {"trade_date": ["2026-08-27"]})
    assert day2_orders["count"] == 1 and day2_orders["orders"][0]["client_order_id"] == "ord-x"
    assert _orders(store_path, {"trade_date": ["2026-08-26"]})["count"] == 4
    day2_fills = _fills(store_path, {"trade_date": ["2026-08-27"]})
    assert day2_fills["count"] == 1 and day2_fills["fills"][0]["fill_id"] == "f-x"
    assert _fills(store_path, {"trade_date": ["2026-08-26"]})["count"] == 3
    # 无效日期：空
    assert _orders(store_path, {"trade_date": ["2026-08-28"]})["count"] == 0


def test_same_session_isolated_by_execution_mode_and_external_projection(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    store = SqliteWatchStore(store_path)
    try:
        store.bind_account_scope("INTERNAL_PAPER", SESSION)
        store.bind_account_scope("EXTERNAL_PAPER", SESSION)
        store.save_simulation({
            "session_id": SESSION, "trade_date": "2026-08-29",
            "initial_cash": 100000.0, "cash": 80000.0, "frozen_cash": 0.0,
            "realized_pnl": 0.0,
        }, [{
            "code": "000001", "name": "平安银行", "qty": 1000,
            "available_qty": 1000, "today_buy_qty": 0,
            "avg_cost": 10.0, "last_price": 11.0,
        }])
        store.save_account_snapshot({
            "snapshot_id": "external:1", "session_id": SESSION,
            "channel": "EXTERNAL_PAPER", "trade_date": "2026-08-29",
            "captured_at": datetime.now().isoformat(), "consistent": True,
            "payload": {
                "total_assets": 520000.0, "avail_balance": 400000.0,
                "frozen_cash": 10000.0, "market_value": 120000.0,
                "positions": [{
                    "code": "600519", "name": "贵州茅台", "qty": 100,
                    "avail_qty": 100, "cost": 1100.0, "last": 1200.0,
                    "market_value": 120000.0, "profit": 10000.0,
                    "profit_rate": 9.09,
                }], "orders": [],
            },
        })
    finally:
        store.close()

    internal = _account(store_path, SESSION, "INTERNAL_PAPER")
    external = _account(store_path, SESSION, "EXTERNAL_PAPER")
    assert internal["account_source"] == "LOCAL_SIMULATION"
    assert {row["code"] for row in internal["positions"]} == {"000001"}
    assert external["account_source"] == "MX_MIRROR"
    assert external["total_assets"] == 520000.0
    assert external["positions"][0]["code"] == "600519"
    assert external["realized_pnl"] is None


def test_external_terminal_orders_are_not_open_and_untracked_are_visible(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    store = SqliteWatchStore(store_path)
    try:
        store.bind_account_scope("EXTERNAL_PAPER", SESSION)
        now = datetime.now().isoformat()
        store.save_account_snapshot({
            "snapshot_id": "external:orders", "session_id": SESSION,
            "channel": "EXTERNAL_PAPER", "trade_date": "2026-08-29",
            "captured_at": now, "consistent": True,
            "payload": {"total_assets": 100000.0, "avail_balance": 100000.0,
                        "frozen_cash": 0.0, "market_value": 0.0,
                        "positions": [], "orders": []},
        })
        store.upsert_external_orders(
            channel="EXTERNAL_PAPER", session_id=SESSION, captured_at=now,
            orders=[
                {"broker_order_id": "mx-filled", "code": "000001", "side": "buy",
                 "qty": 100, "filled_qty": 100, "remaining_qty": 0,
                 "broker_status": "4", "normalized_status": "FILLED"},
                {"broker_order_id": "mx-cancelled", "code": "000002", "side": "buy",
                 "qty": 100, "filled_qty": 0, "remaining_qty": 0,
                 "broker_status": "8", "normalized_status": "CANCELLED"},
                {"broker_order_id": "mx-rejected", "code": "000003", "side": "buy",
                 "qty": 100, "filled_qty": 0, "remaining_qty": 0,
                 "broker_status": "9", "normalized_status": "REJECTED"},
            ],
        )
    finally:
        store.close()

    account = _account(store_path, SESSION, "EXTERNAL_PAPER")
    orders = _orders(store_path, {
        "session_id": [SESSION], "execution_mode": ["EXTERNAL_PAPER"]
    })
    assert account["open_order_count"] == 0
    assert orders["count"] == 3
    assert all(row["origin"] == "EXTERNAL/UNTRACKED" for row in orders["orders"])


def test_external_fee_unknown_is_null_in_fill_api(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    _build_store(store_path)
    conn = sqlite3.connect(store_path)
    try:
        conn.execute(
            "UPDATE local_order SET channel='EXTERNAL_PAPER' WHERE client_order_id='ord1'"
        )
        conn.execute(
            "UPDATE fill SET metadata_json=? WHERE fill_id='f1'",
            (json.dumps({"fee_unknown": True, "price_estimated": True}),),
        )
        conn.commit()
    finally:
        conn.close()
    result = _fills(store_path, {
        "session_id": [SESSION], "execution_mode": ["EXTERNAL_PAPER"]
    })
    row = next(item for item in result["fills"] if item["fill_id"] == "f1")
    assert row["total_fee"] is None
    assert row["commission"] is None
    assert row["fee_unknown"] is True
    assert row["estimated"] is True


def test_mode_switch_blocked_while_old_scope_has_unknown_order(tmp_path):
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    try:
        store.bind_account_scope("INTERNAL_PAPER", SESSION)
        store.create_local_order({
            "client_order_id": "unsafe-order", "idempotency_key": "unsafe-key",
            "session_id": SESSION, "channel": "INTERNAL_PAPER", "run_id": "run",
            "strategy": "MA_CROSS", "signal_bar_ts": "2026-08-29T09:35:00",
            "code": "000001", "side": "buy", "order_type": "LIMIT",
            "limit_price": 10.0, "qty": 100, "status": "UNKNOWN",
            "created_at": datetime.now().isoformat(),
        })
        with pytest.raises(WatchStoreError, match="禁止切换"):
            store.bind_account_scope("EXTERNAL_PAPER", SESSION)
    finally:
        store.close()


def test_external_cumulative_fill_is_incremental_and_mirror_links_once(tmp_path):
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    try:
        store.bind_account_scope("EXTERNAL_PAPER", SESSION)
        oms = OmsService(store, session_id=SESSION, channel="EXTERNAL_PAPER")
        local, _ = oms.create(
            run_id="run", strategy="MA_CROSS", signal_bar_ts="2026-08-29T09:35:00",
            code="000001", side="buy", qty=100, limit_price=10.5, ttl_sec=600,
            now=datetime(2026, 8, 29, 9, 35),
        )
        local = oms.transition(local.client_order_id, OrderStatus.SUBMITTING)
        oms.transition(local.client_order_id, OrderStatus.SUBMITTED, broker_order_id="mx-1")
        partial = MxOrder(
            "mx-1", "000001", "buy", 10.5, 100, 40, "3",
            ts="2026-08-29 09:36:00",
            raw={"secName": "平安银行", "tradePrice": 1050, "tradePriceDec": 2},
        )
        oms.sync_external([partial], datetime(2026, 8, 29, 9, 36))
        oms.sync_external([partial], datetime(2026, 8, 29, 9, 36, 5))
        assert [row["qty"] for row in store.list_fills(session_id=SESSION, channel="EXTERNAL_PAPER")] == [40]

        filled = MxOrder(
            "mx-1", "000001", "buy", 10.5, 100, 100, "4",
            ts="2026-08-29 09:37:00",
            raw={"secName": "平安银行", "tradePrice": 1050, "tradePriceDec": 2},
        )
        oms.sync_external([filled], datetime(2026, 8, 29, 9, 37))
        oms.sync_external([filled], datetime(2026, 8, 29, 9, 37, 5))
        fills = store.list_fills(session_id=SESSION, channel="EXTERNAL_PAPER")
        assert [row["qty"] for row in fills] == [40, 60]
        assert all(row["price_source"] == "MX_ORDER_AGGREGATE" for row in fills)
        mirror = store._conn.execute(
            "SELECT * FROM external_order WHERE channel=? AND session_id=? AND broker_order_id=?",
            ("EXTERNAL_PAPER", SESSION, "mx-1"),
        ).fetchone()
        assert mirror["client_order_id"] == local.client_order_id
        assert mirror["origin"] == "WATCH_OMS"
        assert mirror["filled_qty"] == 100
        assert mirror["remaining_qty"] == 0
    finally:
        store.close()
