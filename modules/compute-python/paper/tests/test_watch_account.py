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
import sqlite3
import threading
from http.server import ThreadingHTTPServer

import pytest

from paper.watch.serve import (
    _account, _decode_metadata, _fills, _orders, _pnl, _positions, make_handler,
)
from paper.watch.store import SqliteWatchStore

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


def test_positions_endpoint(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    _build_store(store_path)

    pos = _positions(store_path, SESSION)
    assert pos["available"] is True
    assert pos["count"] == 2
    assert {p["code"] for p in pos["positions"]} == {"000001", "600519"}
    assert pos["positions"][0]["profit"] == (11.0 - 10.0) * 600


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
    assert totals["unrealized"] == pytest.approx(4600.0)
    assert totals["total"] == pytest.approx(REALIZED + 4600.0)

    codes = {c["code"]: c for c in pnl["per_code"]}
    assert set(codes) == {"000001", "600519"}
    assert codes["000001"]["trades"][0]["realized"] == pytest.approx(REALIZED)
    assert codes["000001"]["realized"] == pytest.approx(REALIZED)
    assert codes["000001"]["unrealized"] == pytest.approx((11.0 - 10.0) * 600)
    assert codes["600519"]["trades"] == []
    assert codes["600519"]["unrealized"] == pytest.approx((1520.0 - 1500.0) * 200)


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
