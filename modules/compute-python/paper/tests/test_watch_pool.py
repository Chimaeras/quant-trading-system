from __future__ import annotations

import http.client
import json
import threading
from http.server import ThreadingHTTPServer

from paper.config import PaperConfig
from paper.watch.serve import (
    _add_watch_pool_member, _remove_watch_pool_member, _watch_pool, make_handler,
)
from paper.watch.store import SqliteWatchStore
from paper.watch.universe import load_watch_universe


class _Response:
    def __init__(self, body: dict):
        self._body = body

    def raise_for_status(self):
        return None

    def json(self):
        return self._body


def _hot_response(*codes: tuple[str, str, str]) -> dict:
    return {
        "data": {
            "items": [
                {"stock_code": code, "stock_name": name, "exchange_type": exchange}
                for code, name, exchange in codes
            ]
        }
    }


def test_watchlist_is_default_mode_and_uses_hot_stocks(monkeypatch):
    config = PaperConfig(api_key="test")
    assert config.watch_universe_mode == "WATCHLIST"
    assert config.max_observe_codes == 50

    monkeypatch.setattr(
        "paper.watch.universe.requests.get",
        lambda *args, **kwargs: _Response(
            _hot_response(("600519", "贵州茅台", "SSE"), ("000001", "平安银行", "SZSE"))
        ),
    )
    candidates, source, errors = load_watch_universe(config, "2026-08-20")

    assert source == "hot_stocks"
    assert [(item.stock_code, item.exchange_type) for item in candidates] == [
        ("600519", "SH"), ("000001", "SZ")
    ]
    assert errors == []


def test_hot_refresh_preserves_manual_members(monkeypatch, tmp_path):
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    store.upsert_watch_pool_member({
        "code": "300750", "name": "宁德时代", "exchange": "SZ"
    })
    monkeypatch.setattr(
        "paper.watch.universe.requests.get",
        lambda *args, **kwargs: _Response(
            _hot_response(("600519", "贵州茅台", "SSE"), ("000001", "平安银行", "SZSE"))
        ),
    )

    candidates, source, errors = load_watch_universe(
        PaperConfig(api_key="test"), "2026-08-20", store=store
    )

    assert source == "watch_pool"
    assert [item.stock_code for item in candidates] == ["300750", "600519", "000001"]
    assert errors == []
    rows = store.list_watch_pool()
    assert rows[0]["code"] == "300750" and rows[0]["manual"] == 1
    assert {row["source"] for row in rows[1:]} == {"hot_stocks"}
    store.close()


def test_watch_serve_pool_helpers_add_list_and_remove(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    store = SqliteWatchStore(store_path)
    store.close()

    saved = _add_watch_pool_member(store_path, {"code": "920000", "name": "北交示例"})
    assert saved["exchange"] == "BJ"
    assert saved["manual"] == 1
    pool = _watch_pool(store_path)
    assert pool["available"] is True
    assert pool["total"] == 1
    assert pool["items"][0]["code"] == "920000"
    assert _remove_watch_pool_member(store_path, "920000") is True
    assert _watch_pool(store_path)["total"] == 0

    sh_b = _add_watch_pool_member(store_path, {"code": "900001", "name": "沪市B股"})
    assert sh_b["exchange"] == "SH"


def test_manual_addition_evicts_lowest_ranked_hot_stock_at_capacity(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    store = SqliteWatchStore(store_path)
    store.sync_default_watch_pool([
        {"code": f"{index:06d}", "name": f"热门{index}", "exchange": "SZ"}
        for index in range(50)
    ])
    store.close()

    _add_watch_pool_member(store_path, {"code": "600519", "name": "贵州茅台"})
    pool = _watch_pool(store_path)

    assert pool["total"] == 50
    assert pool["items"][0]["code"] == "600519"
    assert "000049" not in {item["code"] for item in pool["items"]}


def test_watch_serve_uses_configured_pool_capacity(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    store = SqliteWatchStore(store_path)
    store.upsert_watch_pool_member({"code": "000001", "name": "一", "exchange": "SZ"})
    store.upsert_watch_pool_member({"code": "000002", "name": "二", "exchange": "SZ"})
    store.close()

    pool = _watch_pool(store_path, max_size=2)
    assert pool["max_size"] == 2
    try:
        _add_watch_pool_member(
            store_path, {"code": "000003", "name": "三"}, max_size=2
        )
    except ValueError as exc:
        assert "2 只上限" in str(exc)
    else:
        raise AssertionError("人工观察池达到配置上限后必须拒绝新增")


def test_watch_serve_rejects_direct_browser_write(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    store = SqliteWatchStore(store_path)
    store.close()
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0), make_handler(tmp_path, store_path=store_path)
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
        body = json.dumps({"code": "600519", "name": "贵州茅台"})
        conn.request("POST", "/api/watch/pool", body=body, headers={
            "Content-Type": "application/json",
            "Origin": "https://malicious.example",
        })
        response = conn.getresponse()
        payload = json.loads(response.read().decode("utf-8"))
        conn.close()
        assert response.status == 403
        assert payload["error"] == "browser_write_forbidden"
        assert _watch_pool(store_path)["total"] == 0
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)


def test_hot_refresh_fills_capacity_when_manual_member_is_also_hot(tmp_path):
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    store.upsert_watch_pool_member({"code": "000000", "name": "人工热门", "exchange": "SZ"})
    store.sync_default_watch_pool([
        {"code": f"{index:06d}", "name": f"热门{index}", "exchange": "SZ"}
        for index in range(60)
    ], max_size=50)

    rows = store.list_watch_pool()
    store.close()

    assert len(rows) == 50
    assert rows[0]["code"] == "000000" and rows[0]["manual"] == 1
    assert rows[-1]["code"] == "000049"
