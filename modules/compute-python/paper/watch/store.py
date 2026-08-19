"""Paper Watch 持久化运行库。

个人单机阶段使用 SQLite WAL：同一事务持久化已收盘 bar、forming bar、
策略进度和运行状态。业务层只依赖本模块公开方法，后续可替换为 MySQL/
PostgreSQL，而不把 SQL 细节扩散到 WatchLoop。
"""
from __future__ import annotations

import json
import os
import socket
import sqlite3
import time
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Iterable, Protocol

from .models import WatchBar


SCHEMA_VERSION = 2
LEASE_NAME = "paper-watch-live"


class WatchStoreError(RuntimeError):
    """运行库不可用或事务失败。"""


class WatchStore(Protocol):
    """WatchLoop 依赖的存储契约，后续可换 MySQL/PostgreSQL。"""

    path: Path

    def health(self) -> dict: ...
    def acquire_lease(self, owner_id: str, *, ttl_sec: int, mode: str = "live",
                      now_epoch: float | None = None) -> tuple[bool, dict]: ...
    def renew_lease(self, owner_id: str, *, ttl_sec: int,
                    now_epoch: float | None = None) -> bool: ...
    def release_lease(self, owner_id: str) -> bool: ...
    def lease_snapshot(self) -> dict: ...
    def load_recent_closed_bars(self, period_min: int,
                                limit_per_code: int) -> dict[str, list[WatchBar]]: ...
    def load_forming_checkpoints(self, trade_date: str,
                                 period_min: int) -> dict[str, dict]: ...
    def save_market_cycle(self, *, trade_date: str, closed_bars: Iterable[WatchBar],
                          forming_checkpoints: Iterable[dict]) -> None: ...
    def save_strategy_progress(self, rows: Iterable[dict]) -> None: ...
    def save_runtime_state(self, state_key: str, trade_date: str,
                           payload: dict) -> None: ...
    def load_runtime_state(self, state_key: str, trade_date: str) -> dict | None: ...
    def save_cycle_metric(self, tick: dict) -> None: ...
    def create_local_order(self, order: dict) -> tuple[dict, bool]: ...
    def get_local_order(self, client_order_id: str) -> dict | None: ...
    def list_local_orders(self, *, statuses: Iterable[str] | None = None,
                          channel: str = "", session_id: str = "") -> list[dict]: ...
    def update_local_order(self, client_order_id: str, fields: dict) -> dict: ...
    def append_fill(self, fill: dict, order_fields: dict) -> bool: ...
    def list_fills(self, client_order_id: str = "", session_id: str = "") -> list[dict]: ...
    def save_account_snapshot(self, snapshot: dict) -> None: ...
    def load_simulation(self, session_id: str) -> tuple[dict | None, list[dict]]: ...
    def save_simulation(self, account: dict, positions: Iterable[dict]) -> None: ...
    def apply_internal_execution(self, *, client_order_id: str, order_fields: dict,
                                 account: dict, positions: Iterable[dict],
                                 fill: dict | None = None) -> bool: ...
    def close(self) -> None: ...


def default_owner_id() -> str:
    return f"{socket.gethostname()}:{os.getpid()}:{os.urandom(6).hex()}"


class SqliteWatchStore:
    """SQLite WAL 单机运行库；一个 WatchLoop 持有一个连接。"""

    def __init__(self, path: str | Path, *, timeout_sec: float = 5.0):
        self.path = Path(path).expanduser().resolve()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        try:
            self._conn = sqlite3.connect(
                self.path,
                timeout=timeout_sec,
                isolation_level=None,
            )
            self._conn.row_factory = sqlite3.Row
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.execute("PRAGMA synchronous=FULL")
            self._conn.execute("PRAGMA foreign_keys=ON")
            self._conn.execute("PRAGMA busy_timeout=5000")
            self._migrate()
        except (OSError, sqlite3.Error) as exc:
            raise WatchStoreError(f"运行库初始化失败: {type(exc).__name__}:{exc}") from exc

    @contextmanager
    def _transaction(self):
        try:
            self._conn.execute("BEGIN IMMEDIATE")
            yield
            self._conn.execute("COMMIT")
        except Exception:
            try:
                self._conn.execute("ROLLBACK")
            except sqlite3.Error:
                pass
            raise

    def _migrate(self) -> None:
        try:
            self._conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS schema_meta (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    version INTEGER NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS engine_lease (
                    lease_name TEXT PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    pid INTEGER NOT NULL,
                    host TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    acquired_epoch REAL NOT NULL,
                    heartbeat_epoch REAL NOT NULL,
                    expires_epoch REAL NOT NULL
                );

                CREATE TABLE IF NOT EXISTS closed_bar (
                    code TEXT NOT NULL,
                    period_min INTEGER NOT NULL,
                    start_ts TEXT NOT NULL,
                    end_ts TEXT NOT NULL,
                    trade_date TEXT NOT NULL,
                    open REAL NOT NULL,
                    high REAL NOT NULL,
                    low REAL NOT NULL,
                    close REAL NOT NULL,
                    volume REAL NOT NULL,
                    source TEXT NOT NULL,
                    quality_status TEXT NOT NULL DEFAULT 'VALID',
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (code, period_min, start_ts)
                );
                CREATE INDEX IF NOT EXISTS idx_closed_bar_period_code_time
                    ON closed_bar(period_min, code, start_ts DESC);

                CREATE TABLE IF NOT EXISTS forming_bar_checkpoint (
                    code TEXT NOT NULL,
                    period_min INTEGER NOT NULL,
                    trade_date TEXT NOT NULL,
                    start_ts TEXT NOT NULL,
                    end_ts TEXT NOT NULL,
                    open REAL NOT NULL,
                    high REAL NOT NULL,
                    low REAL NOT NULL,
                    close REAL NOT NULL,
                    volume REAL NOT NULL,
                    source TEXT NOT NULL,
                    last_cumulative_volume REAL NOT NULL,
                    discard_on_close INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (code, period_min)
                );

                CREATE TABLE IF NOT EXISTS strategy_state (
                    code TEXT NOT NULL,
                    strategy TEXT NOT NULL,
                    parameter_hash TEXT NOT NULL,
                    last_processed_bar_ts TEXT NOT NULL,
                    warmup_ready INTEGER NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (code, strategy, parameter_hash)
                );

                CREATE TABLE IF NOT EXISTS runtime_state (
                    state_key TEXT PRIMARY KEY,
                    trade_date TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS cycle_metric (
                    run_id TEXT PRIMARY KEY,
                    trade_date TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    finished_at TEXT NOT NULL,
                    status TEXT NOT NULL,
                    payload_json TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_cycle_metric_date
                    ON cycle_metric(trade_date, started_at);

                CREATE TABLE IF NOT EXISTS local_order (
                    client_order_id TEXT PRIMARY KEY,
                    idempotency_key TEXT NOT NULL UNIQUE,
                    session_id TEXT NOT NULL,
                    channel TEXT NOT NULL,
                    run_id TEXT NOT NULL,
                    strategy TEXT NOT NULL,
                    signal_bar_ts TEXT NOT NULL,
                    code TEXT NOT NULL,
                    side TEXT NOT NULL,
                    order_type TEXT NOT NULL,
                    limit_price REAL,
                    qty INTEGER NOT NULL,
                    filled_qty INTEGER NOT NULL DEFAULT 0,
                    avg_fill_price REAL,
                    total_fee REAL NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    broker_order_id TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL DEFAULT '',
                    last_error_code TEXT NOT NULL DEFAULT '',
                    last_error_message TEXT NOT NULL DEFAULT '',
                    metadata_json TEXT NOT NULL DEFAULT '{}'
                );
                CREATE INDEX IF NOT EXISTS idx_local_order_active
                    ON local_order(channel, session_id, status, created_at);
                CREATE INDEX IF NOT EXISTS idx_local_order_broker
                    ON local_order(broker_order_id);

                CREATE TABLE IF NOT EXISTS fill (
                    fill_id TEXT PRIMARY KEY,
                    client_order_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    code TEXT NOT NULL,
                    side TEXT NOT NULL,
                    qty INTEGER NOT NULL,
                    price REAL NOT NULL,
                    gross_amount REAL NOT NULL,
                    commission REAL NOT NULL,
                    stamp_tax REAL NOT NULL,
                    total_fee REAL NOT NULL,
                    filled_at TEXT NOT NULL,
                    bar_ts TEXT NOT NULL DEFAULT '',
                    price_source TEXT NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    FOREIGN KEY(client_order_id) REFERENCES local_order(client_order_id)
                );
                CREATE INDEX IF NOT EXISTS idx_fill_session_time
                    ON fill(session_id, filled_at);

                CREATE TABLE IF NOT EXISTS account_snapshot (
                    snapshot_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    channel TEXT NOT NULL,
                    trade_date TEXT NOT NULL,
                    captured_at TEXT NOT NULL,
                    consistent INTEGER NOT NULL,
                    payload_json TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_account_snapshot_session
                    ON account_snapshot(session_id, captured_at DESC);

                CREATE TABLE IF NOT EXISTS sim_account (
                    session_id TEXT PRIMARY KEY,
                    trade_date TEXT NOT NULL,
                    initial_cash REAL NOT NULL,
                    cash REAL NOT NULL,
                    frozen_cash REAL NOT NULL,
                    realized_pnl REAL NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS sim_position (
                    session_id TEXT NOT NULL,
                    code TEXT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    qty INTEGER NOT NULL,
                    available_qty INTEGER NOT NULL,
                    today_buy_qty INTEGER NOT NULL,
                    avg_cost REAL NOT NULL,
                    last_price REAL NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY(session_id, code),
                    FOREIGN KEY(session_id) REFERENCES sim_account(session_id)
                );
                """
            )
            row = self._conn.execute("SELECT version FROM schema_meta WHERE id=1").fetchone()
            if row is not None and int(row["version"]) > SCHEMA_VERSION:
                raise WatchStoreError(
                    f"运行库 schema={row['version']} 高于程序支持版本 {SCHEMA_VERSION}"
                )
            self._conn.execute(
                """
                INSERT INTO schema_meta(id, version, updated_at) VALUES(1, ?, ?)
                ON CONFLICT(id) DO UPDATE SET version=excluded.version, updated_at=excluded.updated_at
                """,
                (SCHEMA_VERSION, datetime.now().isoformat()),
            )
        except sqlite3.Error as exc:
            raise WatchStoreError(f"运行库迁移失败: {type(exc).__name__}:{exc}") from exc

    def health(self) -> dict:
        """轻量读写健康检查，不改变业务状态。"""
        started = time.monotonic()
        try:
            row = self._conn.execute("PRAGMA quick_check(1)").fetchone()
            ok = bool(row and str(row[0]).lower() == "ok")
            with self._transaction():
                self._conn.execute("SELECT 1")
            return {
                "ok": ok,
                "path": str(self.path),
                "schema_version": SCHEMA_VERSION,
                "elapsed_ms": int((time.monotonic() - started) * 1000),
            }
        except (OSError, sqlite3.Error) as exc:
            return {
                "ok": False,
                "path": str(self.path),
                "error": f"{type(exc).__name__}:{exc}",
                "elapsed_ms": int((time.monotonic() - started) * 1000),
            }

    # ---------------------------- engine lease ----------------------------

    def acquire_lease(
        self,
        owner_id: str,
        *,
        ttl_sec: int,
        mode: str = "live",
        now_epoch: float | None = None,
    ) -> tuple[bool, dict]:
        now = float(now_epoch if now_epoch is not None else time.time())
        expires = now + ttl_sec
        try:
            with self._transaction():
                row = self._conn.execute(
                    "SELECT * FROM engine_lease WHERE lease_name=?",
                    (LEASE_NAME,),
                ).fetchone()
                if (
                    row is not None
                    and str(row["owner_id"]) != owner_id
                    and float(row["expires_epoch"]) > now
                ):
                    return False, dict(row)
                acquired = now if row is None or str(row["owner_id"]) != owner_id else float(row["acquired_epoch"])
                self._conn.execute(
                    """
                    INSERT INTO engine_lease(
                        lease_name, owner_id, pid, host, mode,
                        acquired_epoch, heartbeat_epoch, expires_epoch
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(lease_name) DO UPDATE SET
                        owner_id=excluded.owner_id,
                        pid=excluded.pid,
                        host=excluded.host,
                        mode=excluded.mode,
                        acquired_epoch=excluded.acquired_epoch,
                        heartbeat_epoch=excluded.heartbeat_epoch,
                        expires_epoch=excluded.expires_epoch
                    """,
                    (
                        LEASE_NAME, owner_id, os.getpid(), socket.gethostname(), mode,
                        acquired, now, expires,
                    ),
                )
            return True, self.lease_snapshot()
        except sqlite3.Error as exc:
            raise WatchStoreError(f"获取 live 租约失败: {type(exc).__name__}:{exc}") from exc

    def renew_lease(
        self,
        owner_id: str,
        *,
        ttl_sec: int,
        now_epoch: float | None = None,
    ) -> bool:
        now = float(now_epoch if now_epoch is not None else time.time())
        try:
            with self._transaction():
                cursor = self._conn.execute(
                    """
                    UPDATE engine_lease
                    SET heartbeat_epoch=?, expires_epoch=?
                    WHERE lease_name=? AND owner_id=?
                    """,
                    (now, now + ttl_sec, LEASE_NAME, owner_id),
                )
            return cursor.rowcount == 1
        except sqlite3.Error as exc:
            raise WatchStoreError(f"续租 live 租约失败: {type(exc).__name__}:{exc}") from exc

    def release_lease(self, owner_id: str) -> bool:
        try:
            with self._transaction():
                cursor = self._conn.execute(
                    "DELETE FROM engine_lease WHERE lease_name=? AND owner_id=?",
                    (LEASE_NAME, owner_id),
                )
            return cursor.rowcount == 1
        except sqlite3.Error as exc:
            raise WatchStoreError(f"释放 live 租约失败: {type(exc).__name__}:{exc}") from exc

    def lease_snapshot(self) -> dict:
        row = self._conn.execute(
            "SELECT * FROM engine_lease WHERE lease_name=?",
            (LEASE_NAME,),
        ).fetchone()
        return dict(row) if row is not None else {}

    # ----------------------------- bar state ------------------------------

    def load_recent_closed_bars(self, period_min: int, limit_per_code: int) -> dict[str, list[WatchBar]]:
        """一次查询加载每个代码最近 N 根 bar，避免全市场逐股 SQL。"""
        if limit_per_code <= 0:
            return {}
        try:
            rows = self._conn.execute(
                """
                SELECT * FROM (
                    SELECT b.*,
                           ROW_NUMBER() OVER(PARTITION BY code ORDER BY start_ts DESC) AS rn
                    FROM closed_bar b
                    WHERE period_min=?
                ) ranked
                WHERE rn <= ?
                ORDER BY code, start_ts
                """,
                (period_min, limit_per_code),
            ).fetchall()
        except sqlite3.Error as exc:
            raise WatchStoreError(f"读取历史 bar 失败: {type(exc).__name__}:{exc}") from exc
        out: dict[str, list[WatchBar]] = {}
        for row in rows:
            bar = WatchBar(
                code=str(row["code"]),
                period_min=int(row["period_min"]),
                open=float(row["open"]),
                high=float(row["high"]),
                low=float(row["low"]),
                close=float(row["close"]),
                volume=float(row["volume"]),
                start_ts=datetime.fromisoformat(str(row["start_ts"])),
                end_ts=datetime.fromisoformat(str(row["end_ts"])),
                closed=True,
                source=str(row["source"]),
            )
            out.setdefault(bar.code, []).append(bar)
        return out

    def load_forming_checkpoints(self, trade_date: str, period_min: int) -> dict[str, dict]:
        try:
            rows = self._conn.execute(
                """
                SELECT * FROM forming_bar_checkpoint
                WHERE trade_date=? AND period_min=?
                """,
                (trade_date, period_min),
            ).fetchall()
        except sqlite3.Error as exc:
            raise WatchStoreError(f"读取 forming bar 失败: {type(exc).__name__}:{exc}") from exc
        out: dict[str, dict] = {}
        for row in rows:
            code = str(row["code"])
            out[code] = {
                "bar": WatchBar(
                    code=code,
                    period_min=int(row["period_min"]),
                    open=float(row["open"]),
                    high=float(row["high"]),
                    low=float(row["low"]),
                    close=float(row["close"]),
                    volume=float(row["volume"]),
                    start_ts=datetime.fromisoformat(str(row["start_ts"])),
                    end_ts=datetime.fromisoformat(str(row["end_ts"])),
                    closed=False,
                    source=str(row["source"]),
                ),
                "last_cumulative_volume": float(row["last_cumulative_volume"]),
                "discard_on_close": bool(row["discard_on_close"]),
            }
        return out

    def save_market_cycle(
        self,
        *,
        trade_date: str,
        closed_bars: Iterable[WatchBar],
        forming_checkpoints: Iterable[dict],
    ) -> None:
        now = datetime.now().isoformat()
        bar_rows = [
            (
                bar.code, bar.period_min, bar.start_ts.isoformat(), bar.end_ts.isoformat(),
                trade_date, bar.open, bar.high, bar.low, bar.close, bar.volume,
                bar.source, "VALID", now,
            )
            for bar in closed_bars
        ]
        forming_rows = []
        for checkpoint in forming_checkpoints:
            bar = checkpoint.get("bar")
            if not isinstance(bar, WatchBar):
                continue
            forming_rows.append((
                bar.code, bar.period_min, trade_date,
                bar.start_ts.isoformat(), bar.end_ts.isoformat(),
                bar.open, bar.high, bar.low, bar.close, bar.volume, bar.source,
                float(checkpoint.get("last_cumulative_volume", 0.0) or 0.0),
                1 if checkpoint.get("discard_on_close") else 0,
                now,
            ))
        try:
            with self._transaction():
                if bar_rows:
                    self._conn.executemany(
                        """
                        INSERT INTO closed_bar(
                            code, period_min, start_ts, end_ts, trade_date,
                            open, high, low, close, volume, source,
                            quality_status, created_at
                        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(code, period_min, start_ts) DO UPDATE SET
                            end_ts=excluded.end_ts,
                            open=excluded.open,
                            high=excluded.high,
                            low=excluded.low,
                            close=excluded.close,
                            volume=excluded.volume,
                            source=excluded.source,
                            quality_status=excluded.quality_status
                        """,
                        bar_rows,
                    )
                if forming_rows:
                    self._conn.executemany(
                        """
                        INSERT INTO forming_bar_checkpoint(
                            code, period_min, trade_date, start_ts, end_ts,
                            open, high, low, close, volume, source,
                            last_cumulative_volume, discard_on_close, updated_at
                        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(code, period_min) DO UPDATE SET
                            trade_date=excluded.trade_date,
                            start_ts=excluded.start_ts,
                            end_ts=excluded.end_ts,
                            open=excluded.open,
                            high=excluded.high,
                            low=excluded.low,
                            close=excluded.close,
                            volume=excluded.volume,
                            source=excluded.source,
                            last_cumulative_volume=excluded.last_cumulative_volume,
                            discard_on_close=excluded.discard_on_close,
                            updated_at=excluded.updated_at
                        """,
                        forming_rows,
                    )
                self._conn.execute(
                    "DELETE FROM forming_bar_checkpoint WHERE trade_date<>?",
                    (trade_date,),
                )
        except sqlite3.Error as exc:
            raise WatchStoreError(f"持久化行情周期失败: {type(exc).__name__}:{exc}") from exc

    # ------------------------- strategy/runtime state ---------------------

    def save_strategy_progress(self, rows: Iterable[dict]) -> None:
        now = datetime.now().isoformat()
        values = [
            (
                str(row["code"]), str(row["strategy"]), str(row["parameter_hash"]),
                str(row["last_processed_bar_ts"]), 1 if row.get("warmup_ready") else 0,
                now,
            )
            for row in rows
            if row.get("code") and row.get("last_processed_bar_ts")
        ]
        if not values:
            return
        try:
            with self._transaction():
                self._conn.executemany(
                    """
                    INSERT INTO strategy_state(
                        code, strategy, parameter_hash, last_processed_bar_ts,
                        warmup_ready, updated_at
                    ) VALUES(?, ?, ?, ?, ?, ?)
                    ON CONFLICT(code, strategy, parameter_hash) DO UPDATE SET
                        last_processed_bar_ts=excluded.last_processed_bar_ts,
                        warmup_ready=excluded.warmup_ready,
                        updated_at=excluded.updated_at
                    """,
                    values,
                )
        except sqlite3.Error as exc:
            raise WatchStoreError(f"保存策略进度失败: {type(exc).__name__}:{exc}") from exc

    def save_runtime_state(self, state_key: str, trade_date: str, payload: dict) -> None:
        try:
            encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str)
            with self._transaction():
                self._conn.execute(
                    """
                    INSERT INTO runtime_state(state_key, trade_date, payload_json, updated_at)
                    VALUES(?, ?, ?, ?)
                    ON CONFLICT(state_key) DO UPDATE SET
                        trade_date=excluded.trade_date,
                        payload_json=excluded.payload_json,
                        updated_at=excluded.updated_at
                    """,
                    (state_key, trade_date, encoded, datetime.now().isoformat()),
                )
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存运行状态失败: {type(exc).__name__}:{exc}") from exc

    def load_runtime_state(self, state_key: str, trade_date: str) -> dict | None:
        try:
            row = self._conn.execute(
                "SELECT trade_date, payload_json FROM runtime_state WHERE state_key=?",
                (state_key,),
            ).fetchone()
            if row is None or str(row["trade_date"]) != trade_date:
                return None
            payload = json.loads(str(row["payload_json"]))
            if not isinstance(payload, dict):
                raise ValueError("runtime_state payload 不是对象")
            return payload
        except (ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"读取运行状态失败: {type(exc).__name__}:{exc}") from exc

    def save_cycle_metric(self, tick: dict) -> None:
        try:
            payload = json.dumps(tick.get("metrics", {}), ensure_ascii=False, default=str)
            with self._transaction():
                self._conn.execute(
                    """
                    INSERT INTO cycle_metric(
                        run_id, trade_date, started_at, finished_at, status, payload_json
                    ) VALUES(?, ?, ?, ?, ?, ?)
                    ON CONFLICT(run_id) DO UPDATE SET
                        finished_at=excluded.finished_at,
                        status=excluded.status,
                        payload_json=excluded.payload_json
                    """,
                    (
                        str(tick.get("run_id", "")), str(tick.get("trade_date", "")),
                        str(tick.get("started_at", "")), str(tick.get("finished_at", "")),
                        str(tick.get("status", "")), payload,
                    ),
                )
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存周期指标失败: {type(exc).__name__}:{exc}") from exc

    # ---------------------------- OMS / fills ----------------------------

    @staticmethod
    def _decode_json_columns(row: sqlite3.Row | dict | None) -> dict | None:
        if row is None:
            return None
        data = dict(row)
        for key in ("metadata_json", "payload_json"):
            if key in data:
                try:
                    data[key.removesuffix("_json")] = json.loads(data[key] or "{}")
                except (TypeError, ValueError):
                    data[key.removesuffix("_json")] = {}
        return data

    def create_local_order(self, order: dict) -> tuple[dict, bool]:
        """按 idempotency_key 幂等创建本地订单。"""
        required = (
            "client_order_id", "idempotency_key", "session_id", "channel",
            "run_id", "strategy", "signal_bar_ts", "code", "side", "order_type",
            "qty", "status", "created_at",
        )
        missing = [key for key in required if order.get(key) in (None, "")]
        if missing:
            raise WatchStoreError(f"创建本地订单缺字段: {','.join(missing)}")
        now = str(order.get("updated_at") or datetime.now().isoformat())
        try:
            with self._transaction():
                existing = self._conn.execute(
                    "SELECT * FROM local_order WHERE idempotency_key=?",
                    (str(order["idempotency_key"]),),
                ).fetchone()
                if existing is not None:
                    return self._decode_json_columns(existing), False
                self._conn.execute(
                    """
                    INSERT INTO local_order(
                        client_order_id, idempotency_key, session_id, channel,
                        run_id, strategy, signal_bar_ts, code, side, order_type,
                        limit_price, qty, filled_qty, avg_fill_price, total_fee,
                        status, broker_order_id, created_at, updated_at, expires_at,
                        last_error_code, last_error_message, metadata_json
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        str(order["client_order_id"]), str(order["idempotency_key"]),
                        str(order["session_id"]), str(order["channel"]), str(order["run_id"]),
                        str(order["strategy"]), str(order["signal_bar_ts"]), str(order["code"]),
                        str(order["side"]), str(order["order_type"]), order.get("limit_price"),
                        int(order["qty"]), int(order.get("filled_qty", 0)),
                        order.get("avg_fill_price"), float(order.get("total_fee", 0.0)),
                        str(order["status"]), str(order.get("broker_order_id", "")),
                        str(order["created_at"]), now, str(order.get("expires_at", "")),
                        str(order.get("last_error_code", "")),
                        str(order.get("last_error_message", "")),
                        json.dumps(order.get("metadata", {}), ensure_ascii=False, default=str),
                    ),
                )
                row = self._conn.execute(
                    "SELECT * FROM local_order WHERE client_order_id=?",
                    (str(order["client_order_id"]),),
                ).fetchone()
            return self._decode_json_columns(row), True
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"创建本地订单失败: {type(exc).__name__}:{exc}") from exc

    def get_local_order(self, client_order_id: str) -> dict | None:
        try:
            row = self._conn.execute(
                "SELECT * FROM local_order WHERE client_order_id=?",
                (client_order_id,),
            ).fetchone()
            return self._decode_json_columns(row)
        except sqlite3.Error as exc:
            raise WatchStoreError(f"读取本地订单失败: {type(exc).__name__}:{exc}") from exc

    def list_local_orders(
        self,
        *,
        statuses: Iterable[str] | None = None,
        channel: str = "",
        session_id: str = "",
    ) -> list[dict]:
        clauses: list[str] = []
        params: list[object] = []
        status_values = [str(value) for value in statuses] if statuses is not None else []
        if status_values:
            clauses.append("status IN (" + ",".join("?" for _ in status_values) + ")")
            params.extend(status_values)
        if channel:
            clauses.append("channel=?")
            params.append(channel)
        if session_id:
            clauses.append("session_id=?")
            params.append(session_id)
        where = " WHERE " + " AND ".join(clauses) if clauses else ""
        try:
            rows = self._conn.execute(
                "SELECT * FROM local_order" + where + " ORDER BY created_at, client_order_id",
                params,
            ).fetchall()
            return [self._decode_json_columns(row) for row in rows]
        except sqlite3.Error as exc:
            raise WatchStoreError(f"查询本地订单失败: {type(exc).__name__}:{exc}") from exc

    def update_local_order(self, client_order_id: str, fields: dict) -> dict:
        allowed = {
            "status", "broker_order_id", "filled_qty", "avg_fill_price", "total_fee",
            "expires_at", "last_error_code", "last_error_message", "metadata_json",
        }
        values = dict(fields)
        if "metadata" in values:
            values["metadata_json"] = json.dumps(values.pop("metadata"), ensure_ascii=False, default=str)
        invalid = set(values) - allowed
        if invalid:
            raise WatchStoreError(f"本地订单更新字段非法: {','.join(sorted(invalid))}")
        values["updated_at"] = datetime.now().isoformat()
        try:
            with self._transaction():
                assignments = ", ".join(f"{key}=?" for key in values)
                cursor = self._conn.execute(
                    f"UPDATE local_order SET {assignments} WHERE client_order_id=?",
                    [*values.values(), client_order_id],
                )
                if cursor.rowcount != 1:
                    raise WatchStoreError(f"本地订单不存在: {client_order_id}")
                row = self._conn.execute(
                    "SELECT * FROM local_order WHERE client_order_id=?",
                    (client_order_id,),
                ).fetchone()
            return self._decode_json_columns(row)
        except sqlite3.Error as exc:
            raise WatchStoreError(f"更新本地订单失败: {type(exc).__name__}:{exc}") from exc

    def append_fill(self, fill: dict, order_fields: dict) -> bool:
        """成交与订单累计值在同一事务落库；重复 fill_id 不重复记账。"""
        allowed = {"status", "filled_qty", "avg_fill_price", "total_fee"}
        if set(order_fields) - allowed:
            raise WatchStoreError("成交附带的订单更新字段非法")
        try:
            with self._transaction():
                cursor = self._conn.execute(
                    """
                    INSERT OR IGNORE INTO fill(
                        fill_id, client_order_id, session_id, code, side, qty, price,
                        gross_amount, commission, stamp_tax, total_fee, filled_at,
                        bar_ts, price_source, metadata_json
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        str(fill["fill_id"]), str(fill["client_order_id"]),
                        str(fill["session_id"]), str(fill["code"]), str(fill["side"]),
                        int(fill["qty"]), float(fill["price"]), float(fill["gross_amount"]),
                        float(fill["commission"]), float(fill["stamp_tax"]),
                        float(fill["total_fee"]), str(fill["filled_at"]),
                        str(fill.get("bar_ts", "")), str(fill["price_source"]),
                        json.dumps(fill.get("metadata", {}), ensure_ascii=False, default=str),
                    ),
                )
                if cursor.rowcount == 0:
                    return False
                values = dict(order_fields)
                values["updated_at"] = datetime.now().isoformat()
                assignments = ", ".join(f"{key}=?" for key in values)
                changed = self._conn.execute(
                    f"UPDATE local_order SET {assignments} WHERE client_order_id=?",
                    [*values.values(), str(fill["client_order_id"])],
                )
                if changed.rowcount != 1:
                    raise WatchStoreError("成交对应本地订单不存在")
            return True
        except (KeyError, TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存成交失败: {type(exc).__name__}:{exc}") from exc

    def list_fills(self, client_order_id: str = "", session_id: str = "") -> list[dict]:
        clauses: list[str] = []
        params: list[str] = []
        if client_order_id:
            clauses.append("client_order_id=?")
            params.append(client_order_id)
        if session_id:
            clauses.append("session_id=?")
            params.append(session_id)
        where = " WHERE " + " AND ".join(clauses) if clauses else ""
        try:
            rows = self._conn.execute(
                "SELECT * FROM fill" + where + " ORDER BY filled_at, fill_id", params
            ).fetchall()
            return [self._decode_json_columns(row) for row in rows]
        except sqlite3.Error as exc:
            raise WatchStoreError(f"查询成交失败: {type(exc).__name__}:{exc}") from exc

    def save_account_snapshot(self, snapshot: dict) -> None:
        try:
            with self._transaction():
                self._conn.execute(
                    """
                    INSERT INTO account_snapshot(
                        snapshot_id, session_id, channel, trade_date, captured_at,
                        consistent, payload_json
                    ) VALUES(?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        str(snapshot["snapshot_id"]), str(snapshot["session_id"]),
                        str(snapshot["channel"]), str(snapshot["trade_date"]),
                        str(snapshot["captured_at"]), 1 if snapshot.get("consistent") else 0,
                        json.dumps(snapshot.get("payload", {}), ensure_ascii=False, default=str),
                    ),
                )
        except (KeyError, TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存账户快照失败: {type(exc).__name__}:{exc}") from exc

    # -------------------------- internal paper ---------------------------

    def load_simulation(self, session_id: str) -> tuple[dict | None, list[dict]]:
        try:
            account = self._conn.execute(
                "SELECT * FROM sim_account WHERE session_id=?", (session_id,)
            ).fetchone()
            positions = self._conn.execute(
                "SELECT * FROM sim_position WHERE session_id=? ORDER BY code", (session_id,)
            ).fetchall()
            return (dict(account) if account is not None else None), [dict(row) for row in positions]
        except sqlite3.Error as exc:
            raise WatchStoreError(f"读取内部模拟账户失败: {type(exc).__name__}:{exc}") from exc

    def save_simulation(self, account: dict, positions: Iterable[dict]) -> None:
        now = datetime.now().isoformat()
        position_rows = list(positions)
        try:
            with self._transaction():
                self._conn.execute(
                    """
                    INSERT INTO sim_account(
                        session_id, trade_date, initial_cash, cash, frozen_cash,
                        realized_pnl, created_at, updated_at
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET
                        trade_date=excluded.trade_date,
                        initial_cash=excluded.initial_cash,
                        cash=excluded.cash,
                        frozen_cash=excluded.frozen_cash,
                        realized_pnl=excluded.realized_pnl,
                        updated_at=excluded.updated_at
                    """,
                    (
                        str(account["session_id"]), str(account["trade_date"]),
                        float(account["initial_cash"]), float(account["cash"]),
                        float(account["frozen_cash"]), float(account.get("realized_pnl", 0.0)),
                        str(account.get("created_at", now)), now,
                    ),
                )
                self._conn.execute(
                    "DELETE FROM sim_position WHERE session_id=?", (str(account["session_id"]),)
                )
                if position_rows:
                    self._conn.executemany(
                        """
                        INSERT INTO sim_position(
                            session_id, code, name, qty, available_qty, today_buy_qty,
                            avg_cost, last_price, updated_at
                        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        [
                            (
                                str(account["session_id"]), str(item["code"]),
                                str(item.get("name", "")), int(item["qty"]),
                                int(item["available_qty"]), int(item.get("today_buy_qty", 0)),
                                float(item["avg_cost"]), float(item.get("last_price", 0.0)), now,
                            )
                            for item in position_rows if int(item.get("qty", 0)) > 0
                        ],
                    )
        except (KeyError, TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存内部模拟账户失败: {type(exc).__name__}:{exc}") from exc

    def apply_internal_execution(
        self,
        *,
        client_order_id: str,
        order_fields: dict,
        account: dict,
        positions: Iterable[dict],
        fill: dict | None = None,
    ) -> bool:
        """订单变化、成交和内部账户在同一事务提交。"""
        allowed = {
            "status", "filled_qty", "avg_fill_price", "total_fee",
            "last_error_code", "last_error_message", "broker_order_id",
        }
        if set(order_fields) - allowed:
            raise WatchStoreError("内部执行的订单更新字段非法")
        now = datetime.now().isoformat()
        position_rows = list(positions)
        try:
            with self._transaction():
                inserted = True
                if fill is not None:
                    cursor = self._conn.execute(
                        """
                        INSERT OR IGNORE INTO fill(
                            fill_id, client_order_id, session_id, code, side, qty, price,
                            gross_amount, commission, stamp_tax, total_fee, filled_at,
                            bar_ts, price_source, metadata_json
                        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            str(fill["fill_id"]), client_order_id, str(fill["session_id"]),
                            str(fill["code"]), str(fill["side"]), int(fill["qty"]),
                            float(fill["price"]), float(fill["gross_amount"]),
                            float(fill["commission"]), float(fill["stamp_tax"]),
                            float(fill["total_fee"]), str(fill["filled_at"]),
                            str(fill.get("bar_ts", "")), str(fill["price_source"]),
                            json.dumps(fill.get("metadata", {}), ensure_ascii=False, default=str),
                        ),
                    )
                    inserted = cursor.rowcount == 1
                    if not inserted:
                        return False
                values = dict(order_fields)
                values["updated_at"] = now
                assignments = ", ".join(f"{key}=?" for key in values)
                changed = self._conn.execute(
                    f"UPDATE local_order SET {assignments} WHERE client_order_id=?",
                    [*values.values(), client_order_id],
                )
                if changed.rowcount != 1:
                    raise WatchStoreError("内部执行对应本地订单不存在")
                self._conn.execute(
                    """
                    INSERT INTO sim_account(
                        session_id, trade_date, initial_cash, cash, frozen_cash,
                        realized_pnl, created_at, updated_at
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET
                        trade_date=excluded.trade_date,
                        initial_cash=excluded.initial_cash,
                        cash=excluded.cash,
                        frozen_cash=excluded.frozen_cash,
                        realized_pnl=excluded.realized_pnl,
                        updated_at=excluded.updated_at
                    """,
                    (
                        str(account["session_id"]), str(account["trade_date"]),
                        float(account["initial_cash"]), float(account["cash"]),
                        float(account["frozen_cash"]), float(account.get("realized_pnl", 0.0)),
                        str(account.get("created_at", now)), now,
                    ),
                )
                self._conn.execute(
                    "DELETE FROM sim_position WHERE session_id=?", (str(account["session_id"]),)
                )
                if position_rows:
                    self._conn.executemany(
                        """
                        INSERT INTO sim_position(
                            session_id, code, name, qty, available_qty, today_buy_qty,
                            avg_cost, last_price, updated_at
                        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        [
                            (
                                str(account["session_id"]), str(item["code"]),
                                str(item.get("name", "")), int(item["qty"]),
                                int(item["available_qty"]), int(item.get("today_buy_qty", 0)),
                                float(item["avg_cost"]), float(item.get("last_price", 0.0)), now,
                            )
                            for item in position_rows if int(item.get("qty", 0)) > 0
                        ],
                    )
            return inserted
        except (KeyError, TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存内部执行失败: {type(exc).__name__}:{exc}") from exc

    def close(self) -> None:
        try:
            self._conn.close()
        except sqlite3.Error:
            pass
