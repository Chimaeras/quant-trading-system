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
    assert config.max_observe_codes == 200

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


def test_hot_refresh_keeps_full_hot_topn_plus_manuals(monkeypatch, tmp_path):
    """人工标的不挤占热门 TopN：max_size=2 时应得到 2 只热门 + 1 只人工。"""
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    store.upsert_watch_pool_member({
        "code": "300750", "name": "宁德时代", "exchange": "SZ"
    })
    monkeypatch.setattr(
        "paper.watch.universe.requests.get",
        lambda *args, **kwargs: _Response(
            _hot_response(
                ("600519", "贵州茅台", "SSE"),
                ("000001", "平安银行", "SZSE"),
                ("600036", "招商银行", "SSE"),
            )
        ),
    )

    candidates, source, errors = load_watch_universe(
        PaperConfig(api_key="test", max_observe_codes=2), "2026-08-20", store=store
    )

    assert source == "watch_pool"
    assert errors == []
    codes = [item.stock_code for item in candidates]
    assert codes[0] == "300750"
    assert set(codes[1:]) == {"600519", "000001"}
    assert len(codes) == 3
    rows = store.list_watch_pool()
    assert sum(1 for row in rows if row["manual"] == 1) == 1
    assert sum(1 for row in rows if row["manual"] == 0) == 2
    store.close()


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


def test_empty_candidate_list_does_not_wipe_hot_pool(tmp_path):
    """D-2 回归：空候选（上游失败）不得清空非人工成员。

    旧实现在 values 为空时仍执行 `DELETE FROM watch_pool_member WHERE manual=0`，
    于是 T1 模式下一次候选获取失败就把 204 只的池子清成只剩人工成员。
    「取不到」不等于「没有」。
    """
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    # 先灌入热门成员（非人工）
    store.sync_default_watch_pool(
        [{"code": f"60{i:04d}", "name": f"热门{i}", "exchange": "SSE"} for i in range(5)],
        max_size=200, source="hot_stocks",
    )
    store.upsert_watch_pool_member({"code": "300750", "name": "宁德时代", "exchange": "SZ"})
    before = store.list_watch_pool()
    assert len(before) == 6

    # 上游失败 → 候选为空
    store.sync_default_watch_pool([], max_size=200, source="t1")

    after = store.list_watch_pool()
    assert len(after) == 6, f"空候选把池子清了: {len(before)} -> {len(after)}"
    assert {row["code"] for row in after} == {row["code"] for row in before}
    store.close()


def test_non_empty_candidate_list_still_replaces_hot_members(tmp_path):
    """对照组：非空候选必须照旧生效，别把 D-2 修成"永不刷新"。"""
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    store.sync_default_watch_pool(
        [{"code": "600000", "name": "旧热门", "exchange": "SSE"}], max_size=200
    )
    store.upsert_watch_pool_member({"code": "300750", "name": "宁德时代", "exchange": "SZ"})

    store.sync_default_watch_pool(
        [{"code": "000001", "name": "平安银行", "exchange": "SZSE"}], max_size=200, source="t1"
    )

    rows = store.list_watch_pool()
    codes = {row["code"] for row in rows}
    assert codes == {"300750", "000001"}, codes  # 人工保留、热门被替换
    assert rows[0]["code"] == "300750" and rows[0]["manual"] == 1
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


def test_manual_addition_stacks_on_top_of_full_hot_pool(tmp_path):
    """热门 TopN 已满时，人工标的叠加而不是挤掉热门股。"""
    store_path = tmp_path / "runtime.sqlite3"
    store = SqliteWatchStore(store_path)
    store.sync_default_watch_pool([
        {"code": f"{index:06d}", "name": f"热门{index}", "exchange": "SZ"}
        for index in range(50)
    ], max_size=50)
    store.close()

    _add_watch_pool_member(store_path, {"code": "600519", "name": "贵州茅台"}, max_size=50)
    pool = _watch_pool(store_path, max_size=50)

    assert pool["total"] == 51
    assert pool["items"][0]["code"] == "600519"
    assert "000049" in {item["code"] for item in pool["items"]}


def test_watch_serve_uses_configured_pool_capacity(tmp_path):
    store_path = tmp_path / "runtime.sqlite3"
    store = SqliteWatchStore(store_path)
    # max_size=2 → 绝对上限 2+50=52；先塞满 52 只再拒绝新增。
    for index in range(52):
        store.upsert_watch_pool_member({
            "code": f"{index:06d}", "name": f"码{index}", "exchange": "SZ"
        })
    store.close()

    pool = _watch_pool(store_path, max_size=2)
    assert pool["max_size"] == 2
    assert pool["total"] == 52
    try:
        _add_watch_pool_member(
            store_path, {"code": "600519", "name": "贵州茅台"}, max_size=2
        )
    except ValueError as exc:
        assert "52 只上限" in str(exc)
    else:
        raise AssertionError("观察池达到绝对上限后必须拒绝新增")


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

    # 人工 000000 保留；热门仍灌满 50 只（跳过已是 manual 的 000000，补到 000050）
    assert len(rows) == 51
    assert rows[0]["code"] == "000000" and rows[0]["manual"] == 1
    hot_codes = [row["code"] for row in rows if row["manual"] == 0]
    assert len(hot_codes) == 50
    assert "000001" in hot_codes
    assert "000050" in hot_codes
    assert "000000" not in hot_codes


def test_drastic_candidate_shrink_does_not_wipe_pool(tmp_path):
    """D-3 回归：**非空但极少**的候选同样不得清池。

    实测缺陷：盯盘循环以 `PAPER_MAX_OBSERVE_CODES=10` 启动后，每轮都用 10 只热门股
    覆写 204 只的池子，池子长期停在 10 只。与 D-2 不同，这种情况**日志全是 OK、
    没有任何错误**，只能靠池子规模才能发现。
    """
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    store.sync_default_watch_pool(
        [{"code": f"60{i:04d}", "name": f"热门{i}", "exchange": "SSE"} for i in range(200)],
        max_size=200, source="hot_stocks",
    )
    store.upsert_watch_pool_member({"code": "300750", "name": "宁德时代", "exchange": "SZ"})
    assert len(store.list_watch_pool()) == 201

    # max_size 被误配成 10 → 只送来 10 只
    store.sync_default_watch_pool(
        [{"code": f"00{i:04d}", "name": f"少数{i}", "exchange": "SZ"} for i in range(10)],
        max_size=10, source="hot_stocks",
    )

    rows = store.list_watch_pool()
    hot = [r for r in rows if r["manual"] == 0]
    assert len(hot) == 200, f"骤降候选把池子清了: 200 -> {len(hot)}"
    assert {r["code"] for r in rows if r["manual"] == 1} == {"300750"}
    store.close()


def test_moderate_refresh_still_replaces_pool(tmp_path):
    """对照组：正常幅度的刷新必须照旧生效，别把 D-3 修成"永不刷新"。"""
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    store.sync_default_watch_pool(
        [{"code": f"60{i:04d}", "name": f"旧{i}", "exchange": "SSE"} for i in range(20)],
        max_size=200,
    )
    # 20 -> 15：不足一半的骤降不成立（15*2=30 >= 20），应正常替换
    store.sync_default_watch_pool(
        [{"code": f"00{i:04d}", "name": f"新{i}", "exchange": "SZ"} for i in range(15)],
        max_size=200, source="t1",
    )
    hot = [r for r in store.list_watch_pool() if r["manual"] == 0]
    assert len(hot) == 15
    assert {r["code"] for r in hot} == {f"00{i:04d}" for i in range(15)}
    store.close()


def test_explicitly_reducing_pool_size_is_honoured(tmp_path):
    """真有缩减意图时必须生效：池子小的时候不做骤降保护。

    避免"为了防误配反而让用户无法主动下调 PAPER_MAX_OBSERVE_CODES"。
    """
    store = SqliteWatchStore(tmp_path / "runtime.sqlite3")
    # 池子只有 3 只（< 骤降保护的门槛 4），此时 1 只候选应被接受
    store.sync_default_watch_pool(
        [{"code": f"60{i:04d}", "name": f"旧{i}", "exchange": "SSE"} for i in range(3)],
        max_size=200,
    )
    store.sync_default_watch_pool(
        [{"code": "000001", "name": "平安银行", "exchange": "SZSE"}], max_size=1, source="hot_stocks"
    )
    hot = [r for r in store.list_watch_pool() if r["manual"] == 0]
    assert len(hot) == 1 and hot[0]["code"] == "000001"
    store.close()
