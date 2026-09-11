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
import math
import re
import sqlite3
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from .. import clock
from .events import _tail_lines, events_path, read_events

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def _json_response(handler: BaseHTTPRequestHandler, payload, status: int = 200) -> None:
    # allow_nan=False：任何潜伏的 NaN/Infinity 会在序列化时显式抛错，
    # 而不是泄出 `NaN`/`Infinity` 字面量（浏览器 JSON.parse 会直接崩）。
    body = json.dumps(payload, ensure_ascii=False, default=str, allow_nan=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _finite(value, default: float = 0.0) -> float:
    """把非有限/非法浮点收敛为默认值，杜绝 NaN/Infinity 泄入 JSON。

    数据库里可能落入字符串 'nan'/'inf'（float() 会静默解出非有限值），
    只要一只有问题的持仓就会让整个 /account 在浏览器 JSON.parse 处崩溃。
    """
    try:
        v = float(value)
    except (TypeError, ValueError):
        return default
    return v if math.isfinite(v) else default


def _optional_finite(value):
    """可空数值字段清洗：None 保持 None，非有限/非法值一律收敛为 None。

    与 _finite 的区别是有「未知」这个合法取值（如 frozen_cash 上游不给）。
    上游账户快照的原始 payload 会原样透传，而 _json_response 用 allow_nan=False：
    一个 NaN 就会让 json.dumps 在 send_response 之前抛 ValueError，客户端只看到
    连接被关、连 HTTP 状态码都没有。
    """
    if value is None:
        return None
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    return v if math.isfinite(v) else None


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


def _infer_exchange(code: str) -> str:
    """按代码前缀推断市场。北交所 920xxx 也以 "9" 开头，故 BJ 必须先判（同 loop._exchange_for_code）。"""
    if code.startswith(("4", "8", "92")):
        return "BJ"
    return "SH" if code.startswith(("5", "6", "9")) else "SZ"


def _pool_exchange(raw, code: str) -> str:
    value = str(raw or "").strip().upper()
    aliases = {"SSE": "SH", "SHSE": "SH", "SZSE": "SZ", "BSE": "BJ"}
    value = aliases.get(value, value)
    inferred = _infer_exchange(code)
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


def _watch_pool(store_path: Path, *, max_size: int = 200) -> dict:
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


def _add_watch_pool_member(store_path: Path, payload: dict, *, max_size: int = 200) -> dict:
    code = str(payload.get("code", payload.get("stock_code", ""))).strip()
    if not re.fullmatch(r"\d{6}", code):
        raise BadRequestError("证券代码必须是 6 位数字")
    exchange = _pool_exchange(payload.get("exchange", payload.get("exchange_type")), code)
    name = str(payload.get("name", payload.get("stock_name", ""))).strip() or code
    if len(name) > 100:
        raise BadRequestError("证券名称不得超过 100 个字符")
    now = clock.now().isoformat()
    # 热门 TopN 占满 max_size；人工标的允许叠加，绝对上限留 50 余量。
    absolute_cap = int(max_size) + 50
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
                if total >= absolute_cap:
                    conn.rollback()
                    raise BadRequestError(
                        f"观察池已达到 {absolute_cap} 只上限（热门 Top{max_size} + 人工余量），请先移除标的"
                    )
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


_ACTIVE_ORDER_STATUSES = ("CREATED", "SUBMITTING", "SUBMITTED", "PARTIAL", "CANCEL_PENDING")


def _ro_conn(store_path: Path):
    """只读连接：不建表不写库，避免与盯盘循环竞争（线程安全，每请求独立）。"""
    if not store_path.exists():
        return None
    uri = f"file:{store_path.resolve().as_posix()}?mode=ro"
    try:
        conn = sqlite3.connect(uri, uri=True, timeout=2)
        conn.row_factory = sqlite3.Row
        return conn
    except sqlite3.Error:
        return None


def _security_names(conn) -> dict[str, str]:
    """从观察池与最新行情兜底补股票名；前端仍会二次解析（Java /stockinfo）。"""
    names: dict[str, str] = {}
    try:
        for row in conn.execute("SELECT code, name FROM watch_pool_member WHERE name != ''"):
            names.setdefault(str(row["code"]), str(row["name"]))
        # 取每个代码"最新一条非空名称"，避免最新行情名为空时丢失更早的名称。
        for row in conn.execute(
            "SELECT q.code, q.name FROM latest_quote q "
            "JOIN (SELECT code, MAX(trade_date) md FROM latest_quote WHERE name != '' GROUP BY code) m "
            "  ON q.code=m.code AND q.trade_date=m.md WHERE q.name != ''"
        ):
            names.setdefault(str(row["code"]), str(row["name"]))
    except sqlite3.Error:
        pass
    return names


def _normalize_channel(value: str) -> str:
    aliases = {
        "INTERNAL": "INTERNAL_PAPER", "EXTERNAL": "EXTERNAL_PAPER",
        "LOCAL": "INTERNAL_PAPER", "MX": "EXTERNAL_PAPER",
    }
    normalized = str(value or "").strip().upper()
    return aliases.get(normalized, normalized)


def _channel_values(channel: str) -> tuple[str, ...]:
    normalized = _normalize_channel(channel)
    if normalized == "INTERNAL_PAPER":
        return ("INTERNAL_PAPER", "internal")
    if normalized == "EXTERNAL_PAPER":
        return ("EXTERNAL_PAPER", "external")
    return (normalized,)


def _resolve_account_scope(conn, requested_channel: str, requested_session: str) -> tuple[str, str]:
    """账户必须以 channel + session_id 解析；缺省选择最近活跃的完整作用域。"""
    channel = _normalize_channel(requested_channel)
    session_id = str(requested_session or "").strip()
    if channel and session_id:
        return channel, session_id
    try:
        clauses, values = [], []
        if channel:
            clauses.append("channel IN (" + ",".join("?" for _ in _channel_values(channel)) + ")")
            values.extend(_channel_values(channel))
        if session_id:
            clauses.append("session_id=?")
            values.append(session_id)
        where = " WHERE " + " AND ".join(clauses) if clauses else ""
        row = conn.execute(
            "SELECT channel, session_id FROM account_snapshot" + where
            + " ORDER BY captured_at DESC, rowid DESC LIMIT 1", values,
        ).fetchone()
        if row:
            return _normalize_channel(row["channel"]), str(row["session_id"])
    except sqlite3.Error:
        pass
    if session_id:
        return channel or "INTERNAL_PAPER", session_id
    try:
        row = conn.execute(
            "SELECT session_id FROM sim_account ORDER BY updated_at DESC, rowid DESC LIMIT 1"
        ).fetchone()
        if row:
            return "INTERNAL_PAPER", str(row["session_id"])
    except sqlite3.Error:
        pass
    return channel, ""


def _resolve_session_id(conn, requested: str) -> str:
    """兼容旧调用；新 API 必须同时传 execution_mode。"""
    return _resolve_account_scope(conn, "", requested)[1]


def _position_view(row: dict, names: dict[str, str]) -> dict:
    qty = int(row.get("qty") or 0)
    cost = _finite(row.get("avg_cost") or 0.0)
    last = _finite(row.get("last_price") or cost, cost)
    code = str(row["code"])
    return {
        "code": code,
        "name": row.get("name") or names.get(code, ""),
        "qty": qty,
        "available_qty": int(row.get("available_qty") or 0),
        "today_buy_qty": int(row.get("today_buy_qty") or 0),
        "frozen_qty": max(qty - int(row.get("available_qty") or 0), 0),
        "avg_cost": cost,
        "last_price": last,
        "market_value": qty * last,
        "profit": (last - cost) * qty,
        "profit_rate": ((last / cost - 1) * 100) if cost > 0 else None,
        "day_profit": None,
        "day_profit_rate": None,
        "position_pct": None,
        "exchange": _infer_exchange(code),
        "price_source": "INTERNAL_SNAPSHOT",
        "source_updated_at": row.get("updated_at"),
    }


def _account(store_path: Path, session_id: str, execution_mode: str = "") -> dict:
    conn = _ro_conn(store_path)
    if conn is None:
        return {"available": False, "error": "store_unavailable"}
    try:
        channel, sid = _resolve_account_scope(conn, execution_mode, session_id)
        if not sid:
            return {"available": False, "error": "no_session"}
        if channel == "EXTERNAL_PAPER":
            return _external_account(conn, channel, sid)
        if channel == "SHADOW":
            return _shadow_account_projection(conn, channel, sid)
        names = _security_names(conn)
        account = conn.execute(
            "SELECT * FROM sim_account WHERE session_id=?", (sid,)
        ).fetchone()
        pos_rows = conn.execute(
            "SELECT * FROM sim_position WHERE session_id=? ORDER BY code", (sid,)
        ).fetchall()
        positions = [_position_view(dict(row), names) for row in pos_rows]
        if account is None:
            mv = sum(p["market_value"] for p in positions)
            unr = sum(p["profit"] for p in positions)
            return {
                "available": True, "session_id": sid, "positions": positions,
                "account_scope": f"{channel}:{sid}", "execution_mode": channel,
                "account_source": "LOCAL_SIMULATION", "source_status": "NOT_AVAILABLE",
                "price_basis": "snapshot",
                "initial_cash": None, "cash": None, "frozen_cash": None,
                "avail_balance": None, "market_value": mv, "total_assets": None,
                "realized_pnl": None, "unrealized_pnl": unr, "total_profit": None,
                "total_return_pct": None, "day_profit": None, "day_return_pct": None,
                "position_count": len(positions), "open_order_count": 0,
                "errors": ["sim_account 尚无记录（未发生交易）"],
            }
        initial_cash = float(account["initial_cash"])
        cash = float(account["cash"])
        frozen = float(account["frozen_cash"])
        realized = float(account["realized_pnl"] or 0.0)
        market_value = sum(p["market_value"] for p in positions)
        unrealized = sum(p["profit"] for p in positions)
        total_assets = cash + market_value
        total_profit = realized + unrealized
        day_profit, day_return_pct = _internal_day_pnl(
            conn, channel, sid, str(account["trade_date"]), total_assets
        )
        return {
            "available": True,
            "session_id": sid,
            "account_scope": f"{channel}:{sid}",
            "execution_mode": channel,
            "account_source": "LOCAL_SIMULATION",
            "source_status": "LIVE",
            "captured_at": account["updated_at"],
            "freshness_seconds": _freshness_seconds(account["updated_at"]),
            "price_basis": "snapshot",
            "initial_cash": initial_cash,
            "cash": cash,
            "frozen_cash": frozen,
            "avail_balance": max(cash - frozen, 0.0),
            "market_value": market_value,
            "total_assets": total_assets,
            "realized_pnl": realized,
            "unrealized_pnl": unrealized,
            "total_profit": total_profit,
            "total_return_pct": (total_assets / initial_cash - 1) * 100 if initial_cash else None,
            "day_profit": day_profit,
            "day_return_pct": day_return_pct,
            "position_count": len(positions),
            "open_order_count": _open_order_count(conn, channel, sid),
            "positions": positions,
            "errors": [],
            "capabilities": {
                "exact_positions": True, "exact_orders": True, "exact_fees": True,
                "exact_realized_pnl": True, "cancellable_orders": True,
            },
        }
    except sqlite3.Error as exc:
        return {"available": False, "error": f"{type(exc).__name__}:{exc}"}
    finally:
        conn.close()


def _freshness_seconds(captured_at) -> float | None:
    try:
        captured = datetime.fromisoformat(str(captured_at))
        return max((clock.now() - captured).total_seconds(), 0.0)
    except (TypeError, ValueError):
        return None


def _internal_day_pnl(conn, channel: str, sid: str, trade_date: str,
                      total_assets: float) -> tuple[float | None, float | None]:
    try:
        values = _channel_values(channel)
        row = conn.execute(
            "SELECT payload_json FROM account_snapshot WHERE channel IN ("
            + ",".join("?" for _ in values)
            + ") AND session_id=? AND trade_date=? AND consistent=1 "
              "ORDER BY captured_at, rowid LIMIT 1",
            (*values, sid, trade_date),
        ).fetchone()
        payload = _decode_metadata(row["payload_json"]) if row else {}
        baseline = payload.get("total_assets")
        if baseline is None:
            return None, None
        baseline_value = float(baseline)
        day_profit = total_assets - baseline_value
        return day_profit, (day_profit / baseline_value * 100) if baseline_value else None
    except (sqlite3.Error, TypeError, ValueError):
        return None, None


def _sync_state(conn, channel: str, sid: str, captured_at: str) -> tuple[str, list[str]]:
    try:
        row = conn.execute(
            "SELECT * FROM account_sync_state WHERE channel=? AND session_id=?",
            (channel, sid),
        ).fetchone()
    except sqlite3.Error:
        row = None
    errors = [str(row["last_error"])] if row and row["last_error"] else []
    status = str(row["source_status"]) if row else "LIVE"
    freshness = _freshness_seconds(captured_at)
    if status == "LIVE" and freshness is not None and freshness > 120:
        status = "STALE"
    return status, errors


def _external_position_view(row: dict, names: dict[str, str], captured_at: str) -> dict:
    code = str(row.get("code", ""))
    qty = int(row.get("qty") or 0)
    cost = _finite(row.get("cost"), 0.0)
    last = _finite(row.get("last"), cost)
    market_value = _finite(row.get("market_value"), qty * last)
    profit = _finite(row.get("profit"), (last - cost) * qty)
    return {
        "code": code, "name": row.get("name") or names.get(code, ""),
        "exchange": row.get("exchange") or _infer_exchange(code),
        "qty": qty, "available_qty": int(row.get("avail_qty") or 0),
        "today_buy_qty": None, "frozen_qty": None, "avg_cost": cost,
        "last_price": last, "market_value": market_value, "profit": profit,
        "profit_rate": row.get("profit_rate"), "day_profit": row.get("day_profit"),
        "day_profit_rate": row.get("day_profit_rate"), "position_pct": row.get("position_pct"),
        "price_source": row.get("price_source") or "MX_ACCOUNT_SNAPSHOT",
        "source_updated_at": captured_at,
    }


def _latest_snapshot(conn, channel: str, sid: str):
    values = _channel_values(channel)
    return conn.execute(
        "SELECT * FROM account_snapshot WHERE channel IN ("
        + ",".join("?" for _ in values)
        + ") AND session_id=? AND consistent=1 ORDER BY captured_at DESC, rowid DESC LIMIT 1",
        (*values, sid),
    ).fetchone()


def _external_account(conn, channel: str, sid: str) -> dict:
    snapshot = _latest_snapshot(conn, channel, sid)
    if snapshot is None:
        return {
            "available": False, "error": "no_consistent_external_snapshot",
            "account_scope": f"{channel}:{sid}", "session_id": sid,
            "execution_mode": channel, "account_source": "MX_MIRROR",
            "source_status": "NOT_AVAILABLE",
        }
    payload = _decode_metadata(snapshot["payload_json"])
    captured_at = str(snapshot["captured_at"])
    names = _security_names(conn)
    positions = [_external_position_view(row, names, captured_at)
                 for row in payload.get("positions", []) if isinstance(row, dict)]
    source_status, errors = _sync_state(conn, channel, sid, captured_at)
    total_assets = _optional_finite(payload.get("total_assets"))
    market_value = _optional_finite(payload.get("market_value"))
    avail = _optional_finite(payload.get("avail_balance"))
    frozen = _optional_finite(payload.get("frozen_cash"))
    unrealized = sum(_finite(row.get("profit"), 0.0) for row in positions)
    return {
        "available": True, "account_scope": f"{channel}:{sid}", "session_id": sid,
        "execution_mode": channel, "account_source": "MX_MIRROR",
        "source_status": source_status, "captured_at": captured_at,
        "freshness_seconds": _freshness_seconds(captured_at), "price_basis": "broker_snapshot",
        "total_assets": total_assets, "initial_cash": None, "cash": None,
        "avail_balance": avail, "frozen_cash": frozen, "market_value": market_value,
        "position_count": len(positions),
        "open_order_count": _open_order_count(conn, channel, sid),
        "total_profit": _optional_finite(payload.get("total_profit")),
        "total_return_pct": _optional_finite(payload.get("total_return_pct")),
        "day_profit": _optional_finite(payload.get("day_profit")),
        "day_return_pct": _optional_finite(payload.get("day_return_pct")),
        "realized_pnl": None, "unrealized_pnl": unrealized,
        "positions": positions, "errors": errors,
        "capabilities": {
            "exact_positions": True, "exact_orders": True, "exact_fees": False,
            "exact_realized_pnl": False, "cancellable_orders": True,
        },
    }


def _shadow_account_projection(conn, channel: str, sid: str) -> dict:
    snapshot = _latest_snapshot(conn, channel, sid)
    payload = _decode_metadata(snapshot["payload_json"]) if snapshot else {}
    captured_at = str(snapshot["captured_at"]) if snapshot else None
    return {
        "available": True, "account_scope": f"{channel}:{sid}", "session_id": sid,
        "execution_mode": channel, "account_source": "SHADOW",
        "source_status": "LIVE" if snapshot else "NOT_AVAILABLE",
        "captured_at": captured_at, "freshness_seconds": _freshness_seconds(captured_at),
        "total_assets": _optional_finite(payload.get("total_assets")),
        "initial_cash": _optional_finite(payload.get("total_assets")),
        "cash": _optional_finite(payload.get("total_assets")),
        "avail_balance": _optional_finite(payload.get("avail_balance")),
        "frozen_cash": None, "market_value": 0.0, "position_count": 0,
        "open_order_count": 0, "total_profit": None, "total_return_pct": None,
        "day_profit": None, "day_return_pct": None, "realized_pnl": None,
        "unrealized_pnl": None, "positions": [],
        "errors": ["影子模式为分析资金基准，不是交易账户"],
        "capabilities": {
            "exact_positions": False, "exact_orders": False, "exact_fees": False,
            "exact_realized_pnl": False, "cancellable_orders": False,
        },
    }


def _open_order_count(conn, channel: str, sid: str) -> int:
    try:
        if channel == "EXTERNAL_PAPER":
            return int(conn.execute(
                "SELECT COUNT(*) FROM external_order WHERE channel=? AND session_id=? "
                "AND normalized_status IN ('SUBMITTING','SUBMITTED','PARTIAL','CANCEL_PENDING','UNKNOWN') "
                "AND remaining_qty>0", (channel, sid),
            ).fetchone()[0])
        placeholders = ",".join("?" for _ in _ACTIVE_ORDER_STATUSES)
        channel_values = _channel_values(channel)
        return int(conn.execute(
            "SELECT COUNT(*) FROM local_order WHERE channel IN ("
            + ",".join("?" for _ in channel_values)
            + ") AND session_id=? AND status IN (" + placeholders + ") AND qty-filled_qty>0",
            (*channel_values, sid, *_ACTIVE_ORDER_STATUSES),
        ).fetchone()[0])
    except sqlite3.Error:
        return 0


def _decode_metadata(raw) -> dict:
    """解码 metadata_json；单条坏数据返回空对象，避免拖垮整个 /orders 或 /fills。"""
    if not raw:
        return {}
    try:
        decoded = json.loads(raw)
    except ValueError:
        return {}
    return decoded if isinstance(decoded, dict) else {}


def _order_view(row: dict, names: dict[str, str]) -> dict:
    metadata = _decode_metadata(row.get("metadata_json"))
    return {
        "client_order_id": row.get("client_order_id"),
        "session_id": row.get("session_id"),
        "channel": row.get("channel"),
        "run_id": row.get("run_id"),
        "strategy": row.get("strategy"),
        "signal_bar_ts": row.get("signal_bar_ts"),
        "code": row.get("code"),
        "name": names.get(str(row.get("code", "")), ""),
        "side": row.get("side"),
        "order_type": row.get("order_type"),
        "limit_price": row.get("limit_price"),
        "qty": row.get("qty"),
        "filled_qty": row.get("filled_qty"),
        "avg_fill_price": row.get("avg_fill_price"),
        "total_fee": None if metadata.get("fee_unknown") else row.get("total_fee"),
        "status": row.get("status"),
        "local_status": row.get("status"),
        "broker_status": metadata.get("broker_status"),
        "normalized_status": row.get("status"),
        "remaining_qty": max(int(row.get("qty") or 0) - int(row.get("filled_qty") or 0), 0),
        "origin": "WATCH_OMS",
        "broker_order_id": row.get("broker_order_id"),
        "created_at": row.get("created_at"),
        "updated_at": row.get("updated_at"),
        "expires_at": row.get("expires_at"),
        "last_error_code": row.get("last_error_code"),
        "last_error_message": row.get("last_error_message"),
        "metadata": metadata,
    }


def _external_order_view(row: dict, names: dict[str, str]) -> dict:
    metadata = _decode_metadata(row.get("metadata_json"))
    return {
        "client_order_id": row.get("client_order_id") or None,
        "broker_order_id": row.get("broker_order_id"),
        "origin": row.get("origin") or "EXTERNAL/UNTRACKED",
        "channel": row.get("channel"), "session_id": row.get("session_id"),
        "run_id": None, "strategy": None, "signal_bar_ts": None,
        "code": row.get("code"), "name": row.get("name") or names.get(str(row.get("code", "")), ""),
        "side": row.get("side"), "order_type": row.get("order_type"),
        "limit_price": row.get("limit_price"), "qty": row.get("qty"),
        "filled_qty": row.get("filled_qty"), "remaining_qty": row.get("remaining_qty"),
        "avg_fill_price": row.get("avg_fill_price"), "total_fee": row.get("total_fee"),
        "status": row.get("normalized_status"), "local_status": None,
        "broker_status": row.get("broker_status"),
        "normalized_status": row.get("normalized_status"),
        "created_at": row.get("created_at") or row.get("broker_time"),
        "broker_time": row.get("broker_time"), "updated_at": row.get("updated_at"),
        "expires_at": None, "last_error_code": None, "last_error_message": None,
        "metadata": metadata,
    }


def _orders(store_path: Path, params: dict) -> dict:
    conn = _ro_conn(store_path)
    if conn is None:
        return {"available": False, "error": "store_unavailable"}
    try:
        channel, sid = _resolve_account_scope(
            conn,
            (params.get("execution_mode") or params.get("channel") or [""])[0],
            (params.get("session_id") or [""])[0],
        )
        status_filter = (params.get("status") or [""])[0]
        code_filter = (params.get("code") or [""])[0]
        trade_date = (params.get("trade_date") or [""])[0].strip()
        limit = max(min(_query_int(params, "limit", 200), 1000), 1)
        names = _security_names(conn)
        if channel == "EXTERNAL_PAPER":
            clauses = ["channel=?", "session_id=?"]
            vals: list[object] = [channel, sid]
            if status_filter:
                statuses = [s for s in status_filter.split(",") if s]
                clauses.append("normalized_status IN (" + ",".join("?" for _ in statuses) + ")")
                vals.extend(statuses)
            if code_filter:
                clauses.append("code=?")
                vals.append(code_filter)
            if trade_date:
                clauses.append("substr(COALESCE(NULLIF(created_at,''), broker_time), 1, 10)=?")
                vals.append(trade_date)
            rows = conn.execute(
                "SELECT * FROM external_order WHERE " + " AND ".join(clauses)
                + " ORDER BY updated_at DESC, broker_order_id DESC LIMIT " + str(limit), vals,
            ).fetchall()
            orders = [_external_order_view(dict(row), names) for row in rows]
            # 尚未出现在柜台镜像中的本地 UNKNOWN/SUBMITTING 也必须展示；按 broker id/client id 去重。
            seen_clients = {str(row.get("client_order_id")) for row in orders if row.get("client_order_id")}
            pending = conn.execute(
                "SELECT * FROM local_order WHERE channel=? AND session_id=? "
                "AND status IN ('CREATED','SUBMITTING','UNKNOWN') ORDER BY created_at DESC",
                (channel, sid),
            ).fetchall()
            for row in pending:
                view = _order_view(dict(row), names)
                if view["client_order_id"] not in seen_clients:
                    orders.append(view)
            orders = orders[:limit]
            return {
                "available": True, "account_scope": f"{channel}:{sid}",
                "execution_mode": channel, "session_id": sid,
                "orders": orders, "count": len(orders),
            }
        channel_values = _channel_values(channel or "INTERNAL_PAPER")
        clauses = ["channel IN (" + ",".join("?" for _ in channel_values) + ")", "session_id=?"]
        vals = [*channel_values, sid]
        if status_filter:
            statuses = [s for s in status_filter.split(",") if s]
            clauses.append("status IN (" + ",".join("?" for _ in statuses) + ")")
            vals.extend(statuses)
        if code_filter:
            clauses.append("code=?")
            vals.append(code_filter)
        if trade_date:
            clauses.append("substr(created_at, 1, 10)=?")
            vals.append(trade_date)
        rows = conn.execute(
            "SELECT * FROM local_order WHERE " + " AND ".join(clauses)
            + " ORDER BY created_at DESC, client_order_id DESC LIMIT " + str(limit),
            vals,
        ).fetchall()
        orders = [_order_view(dict(row), names) for row in rows]
        return {
            "available": True, "account_scope": f"{channel or 'INTERNAL_PAPER'}:{sid}",
            "execution_mode": channel or "INTERNAL_PAPER", "session_id": sid,
            "orders": orders, "count": len(orders),
        }
    except sqlite3.Error as exc:
        return {"available": False, "error": f"{type(exc).__name__}:{exc}"}
    finally:
        conn.close()


def _fill_view(row: dict, names: dict[str, str]) -> dict:
    metadata = _decode_metadata(row.get("metadata_json"))
    fee_unknown = bool(metadata.get("fee_unknown"))
    return {
        "fill_id": row.get("fill_id"),
        "client_order_id": row.get("client_order_id"),
        "code": row.get("code"),
        "name": names.get(str(row.get("code", "")), ""),
        "side": row.get("side"),
        "qty": row.get("qty"),
        "price": row.get("price"),
        "gross_amount": row.get("gross_amount"),
        "commission": None if fee_unknown else row.get("commission"),
        "stamp_tax": None if fee_unknown else row.get("stamp_tax"),
        "total_fee": None if fee_unknown else row.get("total_fee"),
        "fee_unknown": fee_unknown,
        "estimated": bool(metadata.get("price_estimated")),
        "filled_at": row.get("filled_at"),
        "bar_ts": row.get("bar_ts"),
        "price_source": row.get("price_source"),
        "metadata": metadata,
    }


def _fills(store_path: Path, params: dict) -> dict:
    conn = _ro_conn(store_path)
    if conn is None:
        return {"available": False, "error": "store_unavailable"}
    try:
        channel, sid = _resolve_account_scope(
            conn,
            (params.get("execution_mode") or params.get("channel") or [""])[0],
            (params.get("session_id") or [""])[0],
        )
        code_filter = (params.get("code") or [""])[0]
        trade_date = (params.get("trade_date") or [""])[0].strip()
        limit = max(min(_query_int(params, "limit", 200), 1000), 1)
        channel_values = _channel_values(channel or "INTERNAL_PAPER")
        clauses = ["o.channel IN (" + ",".join("?" for _ in channel_values) + ")", "f.session_id=?"]
        vals: list[object] = [*channel_values, sid]
        if code_filter:
            clauses.append("f.code=?")
            vals.append(code_filter)
        if trade_date:
            clauses.append("substr(f.filled_at, 1, 10)=?")
            vals.append(trade_date)
        rows = conn.execute(
            "SELECT f.* FROM fill f JOIN local_order o ON o.client_order_id=f.client_order_id WHERE "
            + " AND ".join(clauses)
            + " ORDER BY f.filled_at DESC, f.fill_id DESC LIMIT " + str(limit),
            vals,
        ).fetchall()
        names = _security_names(conn)
        fills = [_fill_view(dict(row), names) for row in rows]
        return {
            "available": True, "account_scope": f"{channel or 'INTERNAL_PAPER'}:{sid}",
            "execution_mode": channel or "INTERNAL_PAPER", "session_id": sid,
            "fills": fills, "count": len(fills),
        }
    except sqlite3.Error as exc:
        return {"available": False, "error": f"{type(exc).__name__}:{exc}"}
    finally:
        conn.close()


def _pnl(store_path: Path, session_id: str, execution_mode: str = "") -> dict:
    """逐代码重放成交，还原每笔/每只已实现盈亏；未实现按当前持仓口径。"""
    conn = _ro_conn(store_path)
    if conn is None:
        return {"available": False, "error": "store_unavailable"}
    try:
        channel, sid = _resolve_account_scope(conn, execution_mode, session_id)
        if not sid:
            return {"available": False, "error": "no_session"}
        if channel == "EXTERNAL_PAPER":
            account_view = _external_account(conn, channel, sid)
            if not account_view.get("available"):
                return account_view
            per_code = [{
                "code": row["code"], "name": row.get("name", ""),
                "qty": row["qty"], "avg_cost": row.get("avg_cost"),
                "realized": None, "unrealized": row.get("profit"),
                "total": None, "trades": [],
            } for row in account_view.get("positions", [])]
            return {
                "available": True, "account_scope": f"{channel}:{sid}",
                "execution_mode": channel, "session_id": sid,
                "price_basis": "broker_snapshot", "per_code": per_code,
                "totals": {
                    "realized": None,
                    "unrealized": account_view.get("unrealized_pnl"),
                    "total": None, "realized_account": None, "consistent": None,
                },
                "errors": ["妙想接口未提供精确已实现盈亏；本地聚合成交不作为柜台完整历史"],
            }
        names = _security_names(conn)
        pos_rows = conn.execute(
            "SELECT * FROM sim_position WHERE session_id=? ORDER BY code", (sid,)
        ).fetchall()
        positions = {str(row["code"]): dict(row) for row in pos_rows}
        channel_values = _channel_values(channel or "INTERNAL_PAPER")
        fill_rows = conn.execute(
            "SELECT f.* FROM fill f JOIN local_order o ON o.client_order_id=f.client_order_id "
            "WHERE o.channel IN (" + ",".join("?" for _ in channel_values) + ") AND f.session_id=?"
            " ORDER BY f.filled_at, f.bar_ts, f.client_order_id, f.fill_id", (*channel_values, sid)
        ).fetchall()
        account = conn.execute(
            "SELECT * FROM sim_account WHERE session_id=?", (sid,)
        ).fetchone()
        realized_total = float((account["realized_pnl"] if account else 0.0) or 0.0)

        per_code: dict[str, dict] = {}
        for row in fill_rows:
            code = str(row["code"])
            side = str(row["side"])
            qty = int(row["qty"])
            price = _finite(row["price"])
            fee = _finite(row["total_fee"] or 0.0)
            gross = _finite(row["gross_amount"] or 0.0)
            rec = per_code.setdefault(code, {
                "code": code, "name": names.get(code, ""),
                "qty": 0, "avg_cost": 0.0, "realized": 0.0,
                "unrealized": 0.0, "total": 0.0, "trades": [],
            })
            if side == "buy":
                old_qty = int(rec["qty"])
                rec["avg_cost"] = (rec["avg_cost"] * old_qty + gross + fee) / (old_qty + qty) if old_qty + qty else 0.0
                rec["qty"] = old_qty + qty
            else:  # sell
                realized = (price - rec["avg_cost"]) * qty - fee
                rec["realized"] += realized
                rec["trades"].append({
                    "fill_id": row["fill_id"],
                    "ts": row["filled_at"],
                    "qty": qty,
                    "price": price,
                    "fee": fee,
                    "avg_cost": rec["avg_cost"],
                    "realized": realized,
                })
                rec["qty"] = int(rec["qty"]) - qty
                if rec["qty"] <= 0:
                    rec["qty"] = 0

        # 纯持仓但无成交流水的代码（如外部导入/对账重建的持仓）也要进入 per_code，
        # 否则收益分析会缺项、与总览账户卡对不上；其已实现=0、未实现按持仓快照。
        for code, pos in positions.items():
            if code not in per_code:
                per_code[code] = {
                    "code": code, "name": names.get(code, ""),
                    "qty": 0, "avg_cost": 0.0, "realized": 0.0,
                    "unrealized": 0.0, "total": 0.0, "trades": [],
                }
        for code, rec in per_code.items():
            pos = positions.get(code)
            if pos is None:
                rec["unrealized"] = 0.0
            else:
                # 现持仓/均价以 sim_position 为权威（重放可能因 seed/裁剪偏离），已实现仍取重放
                rec["qty"] = int(pos.get("qty") or 0)
                rec["avg_cost"] = _finite(pos.get("avg_cost") or 0.0)
                cost = rec["avg_cost"]
                last = _finite(pos.get("last_price") or cost, cost)
                rec["unrealized"] = (last - cost) * rec["qty"]
            rec["total"] = rec["realized"] + rec["unrealized"]

        result = sorted(per_code.values(), key=lambda item: item["code"])
        total_realized = sum(item["realized"] for item in result)
        total_unrealized = sum(item["unrealized"] for item in result)
        return {
            "available": True,
            "session_id": sid,
            "account_scope": f"{channel or 'INTERNAL_PAPER'}:{sid}",
            "execution_mode": channel or "INTERNAL_PAPER",
            "price_basis": "snapshot",
            "per_code": result,
            "totals": {
                "realized": total_realized,
                "unrealized": total_unrealized,
                "total": total_realized + total_unrealized,
                "realized_account": realized_total,
                "consistent": abs(total_realized - realized_total) < 0.01,
            },
        }
    except sqlite3.Error as exc:
        return {"available": False, "error": f"{type(exc).__name__}:{exc}"}
    finally:
        conn.close()


def _account_scopes(store_path: Path) -> dict:
    conn = _ro_conn(store_path)
    if conn is None:
        return {"available": False, "accounts": [], "error": "store_unavailable"}
    try:
        rows = conn.execute(
            "SELECT channel, session_id, MAX(captured_at) captured_at "
            "FROM account_snapshot GROUP BY channel, session_id ORDER BY captured_at DESC"
        ).fetchall()
        seen: set[tuple[str, str]] = set()
        accounts = []
        for row in rows:
            channel = _normalize_channel(row["channel"])
            sid = str(row["session_id"])
            key = (channel, sid)
            if key in seen:
                continue
            seen.add(key)
            accounts.append({
                "account_scope": f"{channel}:{sid}", "execution_mode": channel,
                "session_id": sid,
                "account_source": "MX_MIRROR" if channel == "EXTERNAL_PAPER" else (
                    "SHADOW" if channel == "SHADOW" else "LOCAL_SIMULATION"
                ),
                "captured_at": row["captured_at"],
            })
        return {"available": True, "accounts": accounts, "count": len(accounts)}
    except sqlite3.Error as exc:
        return {"available": False, "accounts": [], "error": f"{type(exc).__name__}:{exc}"}
    finally:
        conn.close()


def make_handler(log_base: Path, store_path: Path | None = None, *, max_pool_size: int = 200):
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

        def _guarded(self, invoke) -> None:
            """未预期异常兜底，保证客户端总能拿到一个带状态码的 JSON 响应。

            没有这层兜底时，处理函数里任何未捕获异常都会让 BaseHTTPRequestHandler
            直接断连：客户端看到的是「连接被关闭」，既没有 5xx 也没有错误体，排查时
            与「服务没启动」无法区分。
            """
            try:
                invoke()
            except (BrokenPipeError, ConnectionResetError):
                # 客户端已断开，回写必然失败，静默收场。
                pass
            except Exception as exc:  # noqa: BLE001 —— 兜底须覆盖全部未预期异常
                try:
                    _json_response(self, {
                        "error": "internal_error",
                        "message": type(exc).__name__,
                    }, 500)
                except Exception:  # noqa: BLE001 —— 响应已部分写出，无可挽回
                    pass

        def do_GET(self):  # noqa: N802
            self._guarded(self._handle_get)

        def _handle_get(self) -> None:
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

            if len(parts) == 3 and parts[0] == "api" and parts[1] == "watch":
                if DATE_RE.match(parts[2]):
                    trade_date = parts[2]
                    if trade_date not in _days(log_base):
                        return _json_response(self, {"error": "date_not_found", "date": trade_date}, 404)
                    # /api/watch/{date} 直接给当日汇总
                    return _json_response(self, _state(log_base, trade_date))
                # 非日期资源：盯盘模拟账户（持仓 / 收益 / 委托 / 成交）
                query = parse_qs(parsed.query)
                resource = parts[2]
                execution_mode = (query.get("execution_mode") or query.get("channel") or [""])[0]
                session_id = (query.get("session_id") or [""])[0]
                if resource == "accounts":
                    return _json_response(self, _account_scopes(runtime_store))
                if resource == "account":
                    return _json_response(self, _account(runtime_store, session_id, execution_mode))
                if resource == "pnl":
                    return _json_response(self, _pnl(runtime_store, session_id, execution_mode))
                if resource in ("orders", "fills"):
                    try:
                        payload = _orders(runtime_store, query) if resource == "orders" else _fills(runtime_store, query)
                    except BadRequestError as exc:
                        return _json_response(self, {"error": "bad_request", "message": str(exc)}, 400)
                    return _json_response(self, payload)
                return _json_response(self, {"error": "unknown_resource"}, 404)

            if len(parts) == 4 and parts[0] == "api" and parts[1] == "watch" and DATE_RE.match(parts[2]):
                trade_date, resource = parts[2], parts[3]
                query = parse_qs(parsed.query)
                if trade_date not in _days(log_base):
                    return _json_response(self, {"error": "date_not_found", "date": trade_date}, 404)
                if resource == "events":
                    try:
                        after_seq = max(_query_int(query, "after_seq", 0), 0)
                        # 与 orders/fills/market 统一口径：limit 必须同时有上下界，
                        # 否则 limit=0/-1 的返回条数取决于内部走 tail 还是全扫描。
                        limit = max(min(_query_int(query, "limit", 2000), 10000), 1)
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
                        limit = max(min(_query_int(query, "limit", 10000), 10000), 1)
                    except BadRequestError as exc:
                        return _json_response(self, {"error": "bad_request", "message": str(exc)}, 400)
                    return _json_response(
                        self, {"date": trade_date, **_market_snapshot(runtime_store, trade_date, limit=limit)}
                    )
                return _json_response(self, {"error": "unknown_resource"}, 404)

            return _json_response(self, {"error": "not_found"}, 404)

        def do_POST(self):  # noqa: N802
            self._guarded(self._handle_post)

        def _handle_post(self) -> None:
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
            self._guarded(self._handle_delete)

        def _handle_delete(self) -> None:
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
          store_path: Path | None = None, *, max_pool_size: int = 200) -> None:
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
