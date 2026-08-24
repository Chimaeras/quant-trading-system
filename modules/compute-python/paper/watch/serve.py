"""watch 监控面板 API（标准库实现，无第三方依赖）。

默认只绑定 127.0.0.1；交易事件/行情只读，不下单不联网，仅允许维护本地观察池。

端点：
  GET /api/watch/days                          可用交易日列表
  GET /api/watch/{date}/events?after_seq=&code=&type=&limit=   事件增量
  GET /api/watch/{date}/state                  当日汇总（state.json + 最近 CYCLE）
  GET /api/watch/{date}/ticks                  当日每轮 tick 摘要列表
"""
from __future__ import annotations

import json
import re
import sqlite3
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from .events import _tail_lines, events_path, read_events

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def _json_response(handler: BaseHTTPRequestHandler, payload, status: int = 200) -> None:
    body = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _days(log_base: Path) -> list[str]:
    watch_dir = log_base / "watch"
    if not watch_dir.is_dir():
        return []
    return sorted(
        (item.name for item in watch_dir.iterdir() if item.is_dir() and DATE_RE.match(item.name)),
        reverse=True,
    )


def _state(log_base: Path, trade_date: str) -> dict:
    state_path = log_base / "watch" / trade_date / "state.json"
    state = {}
    if state_path.exists():
        try:
            state = json.loads(state_path.read_text(encoding="utf-8"))
        except ValueError:
            state = {}
    # 最近一轮 CYCLE 概要：从文件尾部反向找，避免每 3s 全量读事件
    last_cycle = {}
    tail = _read_tail(log_base, trade_date, lines=500)
    for line in reversed(tail):
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if isinstance(event, dict) and event.get("type") == "CYCLE" and event.get("detail", {}).get("phase") == "end":
            last_cycle = event
            break
    return {"state": state, "last_cycle": last_cycle}


def _read_tail(log_base: Path, trade_date: str, *, lines: int = 500) -> list[str]:
    return _tail_lines(events_path(log_base, trade_date), lines)


def _query_int(query: dict, key: str, default: int) -> int:
    raw = query.get(key, [str(default)])[0]
    try:
        return int(raw)
    except (TypeError, ValueError) as exc:
        raise BadRequestError(f"参数 {key} 非法: {raw!r}") from exc


class BadRequestError(ValueError):
    pass


def _pool_exchange(raw, code: str) -> str:
    value = str(raw or "").strip().upper()
    aliases = {"SSE": "SH", "SHSE": "SH", "SZSE": "SZ", "BSE": "BJ"}
    value = aliases.get(value, value)
    inferred = "BJ" if code.startswith(("4", "8", "92")) else "SH" if code.startswith(("5", "6", "9")) else "SZ"
    if value and value not in {"SH", "SZ", "BJ"}:
        raise BadRequestError(f"交易所非法: {value}")
    if value and value != inferred:
        raise BadRequestError(f"证券代码 {code} 与交易所 {value} 不匹配")
    return value or inferred


def _read_json_body(handler: BaseHTTPRequestHandler) -> dict:
    try:
        length = int(handler.headers.get("Content-Length", "0"))
    except ValueError as exc:
        raise BadRequestError("Content-Length 非法") from exc
    if length <= 0 or length > 16_384:
        raise BadRequestError("请求体不能为空且不得超过 16KB")
    try:
        payload = json.loads(handler.rfile.read(length).decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise BadRequestError("请求体必须是 JSON 对象") from exc
    if not isinstance(payload, dict):
        raise BadRequestError("请求体必须是 JSON 对象")
    return payload


def _watch_pool(store_path: Path, *, max_size: int = 50) -> dict:
    if not store_path.exists():
        return {"items": [], "total": 0, "max_size": max_size, "available": False}
    try:
        conn = sqlite3.connect(store_path, timeout=5)
        conn.row_factory = sqlite3.Row
        try:
            rows = [dict(row) for row in conn.execute(
                """
                SELECT code, name, exchange, source, manual, sort_order, created_at, updated_at
                FROM watch_pool_member
                ORDER BY manual DESC,
                    CASE WHEN manual=1 THEN updated_at END DESC,
                    sort_order ASC, code
                """
            ).fetchall()]
            return {
                "items": rows, "total": len(rows), "max_size": max_size,
                "available": True,
            }
        finally:
            conn.close()
    except sqlite3.Error as exc:
        return {"items": [], "total": 0, "max_size": max_size, "available": False,
                "error": f"{type(exc).__name__}:{exc}"}


def _add_watch_pool_member(store_path: Path, payload: dict, *, max_size: int = 50) -> dict:
    code = str(payload.get("code", payload.get("stock_code", ""))).strip()
    if not re.fullmatch(r"\d{6}", code):
        raise BadRequestError("证券代码必须是 6 位数字")
    exchange = _pool_exchange(payload.get("exchange", payload.get("exchange_type")), code)
    name = str(payload.get("name", payload.get("stock_name", ""))).strip() or code
    if len(name) > 100:
        raise BadRequestError("证券名称不得超过 100 个字符")
    now = datetime.now().isoformat()
    try:
        conn = sqlite3.connect(store_path, timeout=5)
        conn.row_factory = sqlite3.Row
        try:
            conn.execute("PRAGMA busy_timeout=5000")
            conn.execute("BEGIN IMMEDIATE")
            existing = conn.execute(
                "SELECT manual FROM watch_pool_member WHERE code=?", (code,)
            ).fetchone()
            if existing is None:
                total = int(conn.execute("SELECT COUNT(*) FROM watch_pool_member").fetchone()[0])
                if total >= max_size:
                    evict = conn.execute(
                        "SELECT code FROM watch_pool_member WHERE manual=0 "
                        "ORDER BY sort_order DESC, code DESC LIMIT 1"
                    ).fetchone()
                    if evict is None:
                        conn.rollback()
                        raise BadRequestError(
                            f"观察池已达到 {max_size} 只上限，请先移除人工标的"
                        )
                    conn.execute("DELETE FROM watch_pool_member WHERE code=?", (evict[0],))
            conn.execute(
                """
                INSERT INTO watch_pool_member(
                    code, name, exchange, source, manual, sort_order, created_at, updated_at
                ) VALUES(?, ?, ?, 'manual', 1, 0, ?, ?)
                ON CONFLICT(code) DO UPDATE SET
                    name=excluded.name, exchange=excluded.exchange,
                    source='manual', manual=1, updated_at=excluded.updated_at
                """,
                (code, name, exchange, now, now),
            )
            conn.commit()
            row = conn.execute("SELECT * FROM watch_pool_member WHERE code=?", (code,)).fetchone()
            return dict(row)
        finally:
            conn.close()
    except BadRequestError:
        raise
    except sqlite3.Error as exc:
        raise BadRequestError(f"观察池保存失败: {type(exc).__name__}:{exc}") from exc


def _remove_watch_pool_member(store_path: Path, code: str) -> bool:
    if not re.fullmatch(r"\d{6}", code):
        raise BadRequestError("证券代码必须是 6 位数字")
    try:
        conn = sqlite3.connect(store_path, timeout=5)
        try:
            conn.execute("PRAGMA busy_timeout=5000")
            cursor = conn.execute("DELETE FROM watch_pool_member WHERE code=?", (code,))
            conn.commit()
            return cursor.rowcount > 0
        finally:
            conn.close()
    except sqlite3.Error as exc:
        raise BadRequestError(f"观察池移除失败: {type(exc).__name__}:{exc}") from exc


def _ticks(log_base: Path, trade_date: str) -> list[dict]:
    day_dir = log_base / "watch" / trade_date
    ticks: list[dict] = []
    if not day_dir.is_dir():
        return ticks
    for run_dir in sorted(item for item in day_dir.iterdir() if item.is_dir()):
        tick_path = run_dir / "tick.json"
        if not tick_path.exists():
            continue
        try:
            tick = json.loads(tick_path.read_text(encoding="utf-8"))
        except ValueError:
            continue
        ticks.append({
            "run_id": tick.get("run_id", run_dir.name),
            "started_at": tick.get("started_at", ""),
            "finished_at": tick.get("finished_at", ""),
            "live": tick.get("live", False),
            "status": tick.get("status", ""),
            "bars": len(tick.get("new_closed_bars", [])),
            "signals": len(tick.get("signals", [])),
            "submits": len(tick.get("submits", [])),
            "errors": len(tick.get("errors", [])),
            "account": tick.get("account", {}),
        })
    return ticks


def _market_snapshot(store_path: Path, trade_date: str, *, limit: int = 10000) -> dict:
    if not store_path.exists():
        return {"quotes": [], "active_pool": [], "batch": {}, "available": False}
    uri = f"file:{store_path.resolve().as_posix()}?mode=ro"
    try:
        conn = sqlite3.connect(uri, uri=True, timeout=2)
        conn.row_factory = sqlite3.Row
        try:
            quotes = [dict(row) for row in conn.execute(
                "SELECT q.* FROM latest_quote q "
                "INNER JOIN watch_pool_member p ON p.code=q.code "
                "WHERE q.trade_date=? ORDER BY q.code LIMIT ?",
                (trade_date, max(limit, 1)),
            ).fetchall()]
            active = [dict(row) for row in conn.execute(
                "SELECT a.* FROM active_pool_member a "
                "INNER JOIN watch_pool_member p ON p.code=a.code "
                "WHERE a.trade_date=? ORDER BY a.mandatory DESC, a.score DESC, a.code",
                (trade_date,)
            ).fetchall()]
            batch_row = conn.execute(
                "SELECT * FROM market_data_batch WHERE trade_date=? "
                "ORDER BY started_at DESC LIMIT 1", (trade_date,)
            ).fetchone()
            batch = dict(batch_row) if batch_row is not None else {}
            return {
                "quotes": quotes,
                "active_pool": active,
                "batch": batch,
                "available": True,
            }
        finally:
            conn.close()
    except sqlite3.Error as exc:
        return {
            "quotes": [], "active_pool": [], "batch": {}, "available": False,
            "error": f"{type(exc).__name__}:{exc}",
        }


def make_handler(log_base: Path, store_path: Path | None = None, *, max_pool_size: int = 100):
    runtime_store = store_path or (log_base / "watch" / "runtime.sqlite3")
    class WatchServeHandler(BaseHTTPRequestHandler):
        # 静默默认访问日志，避免刷屏；错误照常返回 4xx/5xx
        def log_message(self, fmt, *args):  # noqa: N802
            pass

        def do_OPTIONS(self):  # noqa: N802
            # 浏览器不应直连本地写服务；页面统一经同源 Java 代理访问。
            return _json_response(self, {
                "error": "cross_origin_not_supported",
                "message": "watch-serve 不接受浏览器跨域访问",
            }, 403)

        def _browser_write_forbidden(self) -> bool:
            if not self.headers.get("Origin"):
                return False
            _json_response(self, {
                "error": "browser_write_forbidden",
                "message": "观察池写操作必须经同源鉴权代理访问",
            }, 403)
            return True

        def do_GET(self):  # noqa: N802
            parsed = urlparse(self.path)
            parts = [part for part in parsed.path.split("/") if part]

            if parsed.path == "/api/watch/health":
                return _json_response(self, {"ok": True, "service": "watch-serve"})

            if parsed.path == "/api/watch/days":
                return _json_response(self, {"days": _days(log_base)})

            if parsed.path == "/api/watch/pool":
                return _json_response(
                    self, _watch_pool(runtime_store, max_size=max_pool_size)
                )

            if len(parts) == 3 and parts[0] == "api" and parts[1] == "watch" and DATE_RE.match(parts[2]):
                trade_date = parts[2]
                if trade_date not in _days(log_base):
                    return _json_response(self, {"error": "date_not_found", "date": trade_date}, 404)
                # /api/watch/{date} 直接给当日汇总
                return _json_response(self, _state(log_base, trade_date))

            if len(parts) == 4 and parts[0] == "api" and parts[1] == "watch" and DATE_RE.match(parts[2]):
                trade_date, resource = parts[2], parts[3]
                query = parse_qs(parsed.query)
                if trade_date not in _days(log_base):
                    return _json_response(self, {"error": "date_not_found", "date": trade_date}, 404)
                if resource == "events":
                    try:
                        after_seq = _query_int(query, "after_seq", 0)
                        limit = min(_query_int(query, "limit", 2000), 10000)
                    except BadRequestError as exc:
                        return _json_response(self, {"error": "bad_request", "message": str(exc)}, 400)
                    events = read_events(
                        log_base,
                        trade_date,
                        after_seq=after_seq,
                        code=query.get("code", [""])[0],
                        event_type=query.get("type", [""])[0],
                        limit=limit,
                        latest=query.get("latest", ["0"])[0] == "1",
                    )
                    return _json_response(self, {"date": trade_date, "events": events})
                if resource == "state":
                    return _json_response(self, _state(log_base, trade_date))
                if resource == "ticks":
                    return _json_response(self, {"date": trade_date, "ticks": _ticks(log_base, trade_date)})
                if resource == "market":
                    try:
                        limit = min(_query_int(query, "limit", 10000), 10000)
                    except BadRequestError as exc:
                        return _json_response(self, {"error": "bad_request", "message": str(exc)}, 400)
                    return _json_response(
                        self, {"date": trade_date, **_market_snapshot(runtime_store, trade_date, limit=limit)}
                    )
                return _json_response(self, {"error": "unknown_resource"}, 404)

            return _json_response(self, {"error": "not_found"}, 404)

        def do_POST(self):  # noqa: N802
            if self._browser_write_forbidden():
                return None
            parsed = urlparse(self.path)
            if parsed.path != "/api/watch/pool":
                return _json_response(self, {"error": "not_found"}, 404)
            try:
                item = _add_watch_pool_member(
                    runtime_store, _read_json_body(self), max_size=max_pool_size
                )
                return _json_response(self, {"item": item, "added": True})
            except BadRequestError as exc:
                return _json_response(self, {"error": "bad_request", "message": str(exc)}, 400)

        def do_DELETE(self):  # noqa: N802
            if self._browser_write_forbidden():
                return None
            parsed = urlparse(self.path)
            parts = [part for part in parsed.path.split("/") if part]
            if len(parts) != 4 or parts[:3] != ["api", "watch", "pool"]:
                return _json_response(self, {"error": "not_found"}, 404)
            try:
                deleted = _remove_watch_pool_member(runtime_store, parts[3])
                return _json_response(self, {"code": parts[3], "deleted": deleted})
            except BadRequestError as exc:
                return _json_response(self, {"error": "bad_request", "message": str(exc)}, 400)

    return WatchServeHandler


def serve(log_base: Path, host: str = "127.0.0.1", port: int = 8790,
          store_path: Path | None = None, *, max_pool_size: int = 100) -> None:
    from .store import SqliteWatchStore

    if str(host).strip().lower() not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("watch-serve 无独立登录态，只允许绑定本机回环地址")
    runtime_store = store_path or (Path(log_base) / "watch" / "runtime.sqlite3")
    # watch-serve 可以先于盯盘循环启动；先完成幂等迁移，确保观察池接口可用。
    initialized = SqliteWatchStore(runtime_store)
    initialized.close()
    httpd = ThreadingHTTPServer(
        (host, port), make_handler(
            Path(log_base), store_path=runtime_store, max_pool_size=max_pool_size
        )
    )
    print(f"watch-serve 监控 API: http://{host}:{port}/api/watch/days (log_base={log_base})")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
