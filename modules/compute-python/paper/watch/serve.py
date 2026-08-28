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
    now = datetime.now().isoformat()
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


def _resolve_session_id(conn, requested: str) -> str:
    """指定 session_id 用之；缺省优先取最近账户快照的会话，其次最近更新的 sim_account。

    以 account_snapshot.session_id 为权威（盯盘循环每轮都写入活跃会话），
    避免仅靠 sim_account.updated_at 被旧会话/其他进程触碰而选错。
    """
    if requested:
        return requested
    for sql in (
        "SELECT session_id FROM account_snapshot ORDER BY captured_at DESC, rowid DESC LIMIT 1",
        "SELECT session_id FROM sim_account ORDER BY updated_at DESC, rowid DESC LIMIT 1",
    ):
        try:
            row = conn.execute(sql).fetchone()
        except sqlite3.Error:
            continue
        if row and row["session_id"]:
            return str(row["session_id"])
    return ""


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
        "avg_cost": cost,
        "last_price": last,
        "market_value": qty * last,
        "profit": (last - cost) * qty,
        "profit_rate": ((last / cost - 1) * 100) if cost > 0 else None,
    }


def _account(store_path: Path, session_id: str) -> dict:
    conn = _ro_conn(store_path)
    if conn is None:
        return {"available": False, "error": "store_unavailable"}
    try:
        sid = _resolve_session_id(conn, session_id)
        if not sid:
            return {"available": False, "error": "no_session"}
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
                "price_basis": "snapshot",
                "initial_cash": 0.0, "cash": 0.0, "frozen_cash": 0.0,
                "avail_balance": 0.0, "market_value": mv, "total_assets": mv,
                "realized_pnl": 0.0, "unrealized_pnl": unr, "total_profit": unr,
                "total_return_pct": 0.0,
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
        return {
            "available": True,
            "session_id": sid,
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
            "total_return_pct": (total_assets / initial_cash - 1) * 100 if initial_cash else 0.0,
            "position_count": len(positions),
            "open_order_count": _open_order_count(conn, sid),
            "positions": positions,
        }
    except sqlite3.Error as exc:
        return {"available": False, "error": f"{type(exc).__name__}:{exc}"}
    finally:
        conn.close()


def _open_order_count(conn, sid: str) -> int:
    try:
        placeholders = ",".join("?" for _ in _ACTIVE_ORDER_STATUSES)
        return int(conn.execute(
            "SELECT COUNT(*) FROM local_order WHERE session_id=? AND status IN (" + placeholders + ")",
            (sid, *_ACTIVE_ORDER_STATUSES),
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
        "total_fee": row.get("total_fee"),
        "status": row.get("status"),
        "broker_order_id": row.get("broker_order_id"),
        "created_at": row.get("created_at"),
        "updated_at": row.get("updated_at"),
        "expires_at": row.get("expires_at"),
        "last_error_code": row.get("last_error_code"),
        "last_error_message": row.get("last_error_message"),
        "metadata": metadata,
    }


def _orders(store_path: Path, params: dict) -> dict:
    conn = _ro_conn(store_path)
    if conn is None:
        return {"available": False, "error": "store_unavailable"}
    try:
        sid = _resolve_session_id(conn, (params.get("session_id") or [""])[0])
        status_filter = (params.get("status") or [""])[0]
        code_filter = (params.get("code") or [""])[0]
        trade_date = (params.get("trade_date") or [""])[0].strip()
        limit = max(min(_query_int(params, "limit", 200), 1000), 1)
        clauses = ["session_id=?"]
        vals: list[object] = [sid]
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
        names = _security_names(conn)
        orders = [_order_view(dict(row), names) for row in rows]
        return {"available": True, "session_id": sid, "orders": orders, "count": len(orders)}
    except sqlite3.Error as exc:
        return {"available": False, "error": f"{type(exc).__name__}:{exc}"}
    finally:
        conn.close()


def _fill_view(row: dict, names: dict[str, str]) -> dict:
    metadata = _decode_metadata(row.get("metadata_json"))
    return {
        "fill_id": row.get("fill_id"),
        "client_order_id": row.get("client_order_id"),
        "code": row.get("code"),
        "name": names.get(str(row.get("code", "")), ""),
        "side": row.get("side"),
        "qty": row.get("qty"),
        "price": row.get("price"),
        "gross_amount": row.get("gross_amount"),
        "commission": row.get("commission"),
        "stamp_tax": row.get("stamp_tax"),
        "total_fee": row.get("total_fee"),
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
        sid = _resolve_session_id(conn, (params.get("session_id") or [""])[0])
        code_filter = (params.get("code") or [""])[0]
        trade_date = (params.get("trade_date") or [""])[0].strip()
        limit = max(min(_query_int(params, "limit", 200), 1000), 1)
        clauses = ["session_id=?"]
        vals: list[object] = [sid]
        if code_filter:
            clauses.append("code=?")
            vals.append(code_filter)
        if trade_date:
            clauses.append("substr(filled_at, 1, 10)=?")
            vals.append(trade_date)
        rows = conn.execute(
            "SELECT * FROM fill WHERE " + " AND ".join(clauses)
            + " ORDER BY filled_at DESC, fill_id DESC LIMIT " + str(limit),
            vals,
        ).fetchall()
        names = _security_names(conn)
        fills = [_fill_view(dict(row), names) for row in rows]
        return {"available": True, "session_id": sid, "fills": fills, "count": len(fills)}
    except sqlite3.Error as exc:
        return {"available": False, "error": f"{type(exc).__name__}:{exc}"}
    finally:
        conn.close()


def _pnl(store_path: Path, session_id: str) -> dict:
    """逐代码重放成交，还原每笔/每只已实现盈亏；未实现按当前持仓口径。"""
    conn = _ro_conn(store_path)
    if conn is None:
        return {"available": False, "error": "store_unavailable"}
    try:
        sid = _resolve_session_id(conn, session_id)
        if not sid:
            return {"available": False, "error": "no_session"}
        names = _security_names(conn)
        pos_rows = conn.execute(
            "SELECT * FROM sim_position WHERE session_id=? ORDER BY code", (sid,)
        ).fetchall()
        positions = {str(row["code"]): dict(row) for row in pos_rows}
        fill_rows = conn.execute(
            "SELECT * FROM fill WHERE session_id=?"
            " ORDER BY filled_at, bar_ts, client_order_id, fill_id", (sid,)
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
                if resource == "account":
                    return _json_response(self, _account(runtime_store, (query.get("session_id") or [""])[0]))
                if resource == "pnl":
                    return _json_response(self, _pnl(runtime_store, (query.get("session_id") or [""])[0]))
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
