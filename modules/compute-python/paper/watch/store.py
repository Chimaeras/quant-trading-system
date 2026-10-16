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

from .. import clock
from .models import WatchBar


SCHEMA_VERSION = 6
LEASE_NAME = "paper-watch-live"
# account_snapshot 每轮 tick 追加，但 serve 仅读最新一条；保留最近 N 条防止长期膨胀。
_ACCOUNT_SNAPSHOT_KEEP = 50
# signal_event 每轮可能写入多只标的；按交易日保留最近 N 天，避免运行库无限增长。
_SIGNAL_EVENT_KEEP_DAYS = 30


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
    def save_signal_events(self, tick: dict) -> int: ...
    def save_market_data_batch(self, batch: dict, quality_rows: Iterable[dict]) -> None: ...
    def save_quote_quality(self, scope: str, trade_date: str, rows: Iterable[dict]) -> None: ...
    def load_quote_quality(self, scope: str, trade_date: str) -> list[dict]: ...
    def upsert_latest_quotes(self, rows: Iterable[dict]) -> None: ...
    def list_latest_quotes(self, *, trade_date: str = "", limit: int = 10000) -> list[dict]: ...
    def save_active_pool(self, trade_date: str, rows: Iterable[dict]) -> None: ...
    def load_active_pool(self, trade_date: str) -> list[dict]: ...
    def save_security_rules(self, trade_date: str, rows: Iterable[dict]) -> None: ...
    def load_security_rules(self, trade_date: str) -> list[dict]: ...
    def save_universe_snapshot(self, trade_date: str, source: str,
                               universe_hash: str, rows: Iterable[dict]) -> None: ...
    def sync_default_watch_pool(self, rows: Iterable[dict], *, max_size: int = 50, source: str = "hot_stocks") -> None: ...
    def list_watch_pool(self, *, limit: int = 500) -> list[dict]: ...
    def upsert_watch_pool_member(self, row: dict) -> dict: ...
    def remove_watch_pool_member(self, code: str) -> bool: ...
    def watch_pool_revision(self) -> str: ...
    def create_local_order(self, order: dict) -> tuple[dict, bool]: ...
    def get_local_order(self, client_order_id: str) -> dict | None: ...
    def list_local_orders(self, *, statuses: Iterable[str] | None = None,
                          channel: str = "", session_id: str = "") -> list[dict]: ...
    def update_local_order(self, client_order_id: str, fields: dict) -> dict: ...
    def append_fill(self, fill: dict, order_fields: dict) -> bool: ...
    def list_fills(self, client_order_id: str = "", session_id: str = "",
                   channel: str = "") -> list[dict]: ...
    def save_account_snapshot(self, snapshot: dict) -> None: ...
    def bind_account_scope(self, channel: str, session_id: str) -> dict: ...
    def save_account_sync_state(self, state: dict) -> None: ...
    def upsert_external_orders(self, *, channel: str, session_id: str,
                               orders: Iterable[dict], captured_at: str) -> None: ...
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

                CREATE TABLE IF NOT EXISTS signal_event (
                    run_id TEXT NOT NULL,
                    trade_date TEXT NOT NULL,
                    code TEXT NOT NULL,
                    action TEXT NOT NULL,
                    strategy TEXT NOT NULL DEFAULT '',
                    bar_ts TEXT NOT NULL DEFAULT '',
                    valid_until TEXT NOT NULL DEFAULT '',
                    bar_period_min INTEGER,
                    reason_codes TEXT NOT NULL DEFAULT '',
                    detail_json TEXT NOT NULL DEFAULT '{}',
                    recorded_at TEXT NOT NULL,
                    PRIMARY KEY(run_id, code, action)
                );
                CREATE INDEX IF NOT EXISTS idx_signal_event_date
                    ON signal_event(trade_date, bar_ts);
                CREATE INDEX IF NOT EXISTS idx_signal_event_code
                    ON signal_event(code, trade_date, bar_ts);

                CREATE TABLE IF NOT EXISTS market_data_batch (
                    batch_id TEXT PRIMARY KEY,
                    provider TEXT NOT NULL,
                    trade_date TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    received_at TEXT NOT NULL,
                    finished_at TEXT NOT NULL,
                    requested_count INTEGER NOT NULL,
                    returned_count INTEGER NOT NULL,
                    covered_count INTEGER NOT NULL,
                    missing_count INTEGER NOT NULL,
                    duplicate_count INTEGER NOT NULL,
                    page_count INTEGER NOT NULL,
                    coverage REAL NOT NULL,
                    elapsed_ms INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    payload_json TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_market_data_batch_date_time
                    ON market_data_batch(trade_date, started_at DESC);

                CREATE TABLE IF NOT EXISTS latest_quote (
                    trade_date TEXT NOT NULL,
                    code TEXT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    exchange TEXT NOT NULL DEFAULT '',
                    last REAL NOT NULL,
                    pct_chg REAL,
                    volume REAL,
                    amount REAL,
                    source TEXT NOT NULL,
                    market_time TEXT NOT NULL DEFAULT '',
                    received_at TEXT NOT NULL DEFAULT '',
                    processed_at TEXT NOT NULL,
                    validity TEXT NOT NULL,
                    quality TEXT NOT NULL,
                    quality_reason TEXT NOT NULL DEFAULT '',
                    batch_id TEXT NOT NULL DEFAULT '',
                    freshness_seconds REAL,
                    PRIMARY KEY(trade_date, code)
                );
                CREATE INDEX IF NOT EXISTS idx_latest_quote_quality
                    ON latest_quote(trade_date, quality, processed_at DESC);

                CREATE TABLE IF NOT EXISTS quote_quality_state (
                    scope TEXT NOT NULL,
                    trade_date TEXT NOT NULL,
                    code TEXT NOT NULL,
                    quality TEXT NOT NULL,
                    reason TEXT NOT NULL DEFAULT '',
                    consecutive_missing INTEGER NOT NULL DEFAULT 0,
                    last_market_time TEXT NOT NULL DEFAULT '',
                    last_received_at TEXT NOT NULL DEFAULT '',
                    last_price REAL,
                    last_volume REAL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY(scope, trade_date, code)
                );

                CREATE TABLE IF NOT EXISTS active_pool_member (
                    trade_date TEXT NOT NULL,
                    code TEXT NOT NULL,
                    mandatory INTEGER NOT NULL DEFAULT 0,
                    reasons_json TEXT NOT NULL DEFAULT '[]',
                    score REAL,
                    as_of TEXT NOT NULL,
                    PRIMARY KEY(trade_date, code)
                );
                CREATE INDEX IF NOT EXISTS idx_active_pool_date
                    ON active_pool_member(trade_date, mandatory DESC, score DESC);

                CREATE TABLE IF NOT EXISTS security_rule_snapshot (
                    trade_date TEXT NOT NULL,
                    code TEXT NOT NULL,
                    rule_version TEXT NOT NULL,
                    source TEXT NOT NULL,
                    reliability TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    captured_at TEXT NOT NULL,
                    PRIMARY KEY(trade_date, code)
                );

                CREATE TABLE IF NOT EXISTS universe_snapshot (
                    universe_hash TEXT PRIMARY KEY,
                    trade_date TEXT NOT NULL,
                    source TEXT NOT NULL,
                    security_count INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    captured_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS watch_pool_member (
                    code TEXT PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '',
                    exchange TEXT NOT NULL,
                    source TEXT NOT NULL,
                    manual INTEGER NOT NULL DEFAULT 0,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_watch_pool_source_time
                    ON watch_pool_member(manual DESC, source, updated_at DESC);

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
                    ON account_snapshot(channel, session_id, captured_at DESC);

                CREATE TABLE IF NOT EXISTS account_scope_registry (
                    account_scope TEXT PRIMARY KEY,
                    channel TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    execution_mode TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    last_seen_at TEXT NOT NULL,
                    UNIQUE(channel, session_id)
                );

                CREATE TABLE IF NOT EXISTS account_sync_state (
                    channel TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    source_status TEXT NOT NULL,
                    captured_at TEXT NOT NULL DEFAULT '',
                    last_success_at TEXT NOT NULL DEFAULT '',
                    last_error TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY(channel, session_id)
                );

                CREATE TABLE IF NOT EXISTS external_order (
                    channel TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    broker_order_id TEXT NOT NULL,
                    client_order_id TEXT NOT NULL DEFAULT '',
                    origin TEXT NOT NULL DEFAULT 'EXTERNAL/UNTRACKED',
                    code TEXT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    exchange TEXT NOT NULL DEFAULT '',
                    side TEXT NOT NULL,
                    order_type TEXT NOT NULL DEFAULT 'LIMIT',
                    limit_price REAL,
                    qty INTEGER NOT NULL,
                    filled_qty INTEGER NOT NULL DEFAULT 0,
                    remaining_qty INTEGER NOT NULL DEFAULT 0,
                    avg_fill_price REAL,
                    total_fee REAL,
                    broker_status TEXT NOT NULL,
                    normalized_status TEXT NOT NULL,
                    broker_time TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL,
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    PRIMARY KEY(channel, session_id, broker_order_id)
                );
                CREATE INDEX IF NOT EXISTS idx_external_order_scope_status
                    ON external_order(channel, session_id, normalized_status, updated_at DESC);

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
            columns = {
                str(row[1]) for row in self._conn.execute("PRAGMA table_info(watch_pool_member)").fetchall()
            }
            if "sort_order" not in columns:
                try:
                    self._conn.execute(
                        "ALTER TABLE watch_pool_member ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"
                    )
                except sqlite3.OperationalError as exc:
                    # watch 与 watch-serve 同时首次启动时，另一进程可能已完成该列迁移。
                    if "duplicate column" not in str(exc).lower():
                        raise
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
                (SCHEMA_VERSION, clock.now().isoformat()),
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

    def load_recent_closed_bars(
        self,
        period_min: int,
        limit_per_code: int,
        *,
        trade_date: str | None = None,
    ) -> dict[str, list[WatchBar]]:
        """一次查询加载每个代码最近 N 根 bar，避免全市场逐股 SQL。

        `trade_date` 是本缓存的**安全边界**，不是可选优化：`closed_bar` 只服务
        「同一交易日重启恢复」，跨日历史一律由公开分钟 K 线补齐。

        缺陷背景（2026-10-13 定位）：旧签名没有日期参数，于是「最近 80 根」会跨月
        取证——某票的 80 根横跨 8~10 月十余个交易日，而 `loop` 只按**根数**判断
        「已预热」并跳过网络补拉，导致 8 月的旧价与当日实时价被拼成一条假连续
        序列，均线在断层处跳变，伪造出金叉买入（实测 601579 ma5 被算成 22.866，
        真实应为 34.32）。因此真实调用方必须传 `trade_date`；`None` 仅保留给
        需要跨日审计的显式场景。
        """
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
                      AND (? IS NULL OR trade_date=?)
                ) ranked
                WHERE rn <= ?
                ORDER BY code, start_ts
                """,
                (period_min, trade_date, trade_date, limit_per_code),
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

    def prune_closed_bars(self, keep_trade_dates: int) -> int:
        """只保留最近 N 个交易日的 `closed_bar`，返回删除行数。

        缓存职责仅限当日/隔日重启恢复，更早的行既无用途又是有害的：实测 16379 行
        混杂 8~10 月数据，且正是上文跨月取证缺陷的燃料。避免用固定日期做保留，
        改为按表内实际存在的交易日回数，这样长假期间也不会误删当日数据。
        """
        if keep_trade_dates <= 0:
            return 0
        try:
            with self._transaction():
                kept = self._conn.execute(
                    """
                    SELECT DISTINCT trade_date FROM closed_bar
                    ORDER BY trade_date DESC LIMIT ?
                    """,
                    (keep_trade_dates,),
                ).fetchall()
                if len(kept) < keep_trade_dates:
                    return 0
                cutoff = str(kept[-1]["trade_date"])
                cursor = self._conn.execute(
                    "DELETE FROM closed_bar WHERE trade_date < ?", (cutoff,)
                )
                return int(cursor.rowcount or 0)
        except sqlite3.Error as exc:
            raise WatchStoreError(f"清理历史 bar 失败: {type(exc).__name__}:{exc}") from exc

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
        now = clock.now().isoformat()
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
        now = clock.now().isoformat()
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
                    (state_key, trade_date, encoded, clock.now().isoformat()),
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

    # ------------------------- signal plane -------------------------

    def save_signal_events(self, tick: dict) -> int:
        """把本轮产出的信号明细落库，返回写入条数。

        存在意义（D-WATCH-01，2026-10-14）：此前 cycle_metric 只记录
        ``buy_signal_count`` / ``sell_signal_count`` 两个计数，**信号对应的具体标的与
        指标值全部丢失**——只能从被重定向的 stdout 里人工翻找，进程重启后更无从回溯。
        结果是「信号准不准」无法在事后回答，也无法把盘中信号并入任何统计分析。
        本方法把每条信号连同 bar 时点、原因码与指标明细一并持久化，使信号可回溯、可回验。

        幂等：主键 (run_id, code, action)，同一轮重复写入只更新不重复追加。
        """
        signals = tick.get("signals") or []
        if not signals:
            return 0
        run_id = str(tick.get("run_id", ""))
        trade_date = str(tick.get("trade_date", ""))
        now = clock.now().isoformat()
        written = 0
        try:
            with self._transaction():
                for signal in signals:
                    if not isinstance(signal, dict):
                        continue
                    code = str(signal.get("code") or signal.get("stock_code") or "")
                    if not code:
                        continue
                    reasons = signal.get("reason_codes") or []
                    self._conn.execute(
                        """
                        INSERT INTO signal_event(
                            run_id, trade_date, code, action, strategy, bar_ts, valid_until,
                            bar_period_min, reason_codes, detail_json, recorded_at
                        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(run_id, code, action) DO UPDATE SET
                            strategy=excluded.strategy,
                            bar_ts=excluded.bar_ts,
                            valid_until=excluded.valid_until,
                            bar_period_min=excluded.bar_period_min,
                            reason_codes=excluded.reason_codes,
                            detail_json=excluded.detail_json,
                            recorded_at=excluded.recorded_at
                        """,
                        (
                            run_id, trade_date, code,
                            str(signal.get("action", "")),
                            str(signal.get("strategy", "")),
                            str(signal.get("bar_ts", "")),
                            str(signal.get("valid_until", "")),
                            signal.get("bar_period_min"),
                            ",".join(str(r) for r in reasons),
                            json.dumps(signal.get("detail", {}), ensure_ascii=False, default=str),
                            now,
                        ),
                    )
                    written += 1
                # 定期清理：按交易日只保留最近 _SIGNAL_EVENT_KEEP_DAYS 天
                distinct_dates = [
                    row[0] for row in self._conn.execute(
                        "SELECT DISTINCT trade_date FROM signal_event ORDER BY trade_date DESC"
                    ).fetchall()
                ]
                if len(distinct_dates) > _SIGNAL_EVENT_KEEP_DAYS:
                    for stale in distinct_dates[_SIGNAL_EVENT_KEEP_DAYS:]:
                        self._conn.execute("DELETE FROM signal_event WHERE trade_date=?", (stale,))
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存信号明细失败: {type(exc).__name__}:{exc}") from exc
        return written

    # ------------------------- market data plane -------------------------

    def save_market_data_batch(self, batch: dict, quality_rows: Iterable[dict]) -> None:
        now = clock.now().isoformat()
        rows = list(quality_rows)
        try:
            encoded = json.dumps(batch, ensure_ascii=False, sort_keys=True, default=str)
            with self._transaction():
                self._conn.execute(
                    """
                    INSERT INTO market_data_batch(
                        batch_id, provider, trade_date, started_at, received_at,
                        finished_at, requested_count, returned_count, covered_count,
                        missing_count, duplicate_count, page_count, coverage,
                        elapsed_ms, status, payload_json
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(batch_id) DO UPDATE SET
                        finished_at=excluded.finished_at,
                        status=excluded.status,
                        payload_json=excluded.payload_json
                    """,
                    (
                        str(batch.get("batch_id", "")), str(batch.get("provider", "")),
                        str(batch.get("trade_date", "")), str(batch.get("started_at", "")),
                        str(batch.get("received_at", "")), str(batch.get("finished_at", "")),
                        int(batch.get("requested_count", 0) or 0),
                        int(batch.get("returned_count", 0) or 0),
                        int(batch.get("covered_count", 0) or 0),
                        int(batch.get("missing_count", 0) or 0),
                        int(batch.get("duplicate_count", 0) or 0),
                        int(batch.get("page_count", 0) or 0),
                        float(batch.get("coverage", 0.0) or 0.0),
                        int(batch.get("elapsed_ms", 0) or 0), str(batch.get("status", "")),
                        encoded,
                    ),
                )
                for row in rows:
                    self._conn.execute(
                        """
                        INSERT INTO quote_quality_state(
                            scope, trade_date, code, quality, reason, consecutive_missing,
                            last_market_time, last_received_at, last_price,
                            last_volume, updated_at
                        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(scope, trade_date, code) DO UPDATE SET
                            quality=excluded.quality,
                            reason=excluded.reason,
                            consecutive_missing=excluded.consecutive_missing,
                            last_market_time=excluded.last_market_time,
                            last_received_at=excluded.last_received_at,
                            last_price=excluded.last_price,
                            last_volume=excluded.last_volume,
                            updated_at=excluded.updated_at
                        """,
                        (
                            "L1", str(batch.get("trade_date", "")),
                            str(row.get("code", "")), str(row.get("quality", "MISSING")),
                            str(row.get("reason", "")), int(row.get("consecutive_missing", 0) or 0),
                            str(row.get("last_market_time", "")), str(row.get("last_received_at", "")),
                            row.get("last_price"), row.get("last_volume"), now,
                        ),
                    )
                    self._conn.execute(
                        """
                        UPDATE latest_quote
                        SET quality=?, quality_reason=?, processed_at=?
                        WHERE trade_date=? AND code=?
                        """,
                        (
                            str(row.get("quality", "MISSING")),
                            str(row.get("reason", "")), now,
                            str(batch.get("trade_date", "")),
                            str(row.get("code", "")),
                        ),
                    )
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存行情批次失败: {type(exc).__name__}:{exc}") from exc

    def save_quote_quality(self, scope: str, trade_date: str, rows: Iterable[dict]) -> None:
        values = list(rows)
        if not values:
            return
        now = clock.now().isoformat()
        try:
            with self._transaction():
                self._conn.executemany(
                    """
                    INSERT INTO quote_quality_state(
                        scope, trade_date, code, quality, reason, consecutive_missing,
                        last_market_time, last_received_at, last_price,
                        last_volume, updated_at
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(scope, trade_date, code) DO UPDATE SET
                        quality=excluded.quality, reason=excluded.reason,
                        consecutive_missing=excluded.consecutive_missing,
                        last_market_time=excluded.last_market_time,
                        last_received_at=excluded.last_received_at,
                        last_price=excluded.last_price, last_volume=excluded.last_volume,
                        updated_at=excluded.updated_at
                    """,
                    [(
                        scope, trade_date, str(row.get("code", "")), str(row.get("quality", "MISSING")),
                        str(row.get("reason", "")), int(row.get("consecutive_missing", 0) or 0),
                        str(row.get("last_market_time", "")), str(row.get("last_received_at", "")),
                        row.get("last_price"), row.get("last_volume"), now,
                    ) for row in values if row.get("code")],
                )
                self._conn.executemany(
                    """
                    UPDATE latest_quote
                    SET quality=?, quality_reason=?, processed_at=?
                    WHERE trade_date=? AND code=?
                    """,
                    [(
                        str(row.get("quality", "MISSING")),
                        str(row.get("reason", "")), now, trade_date,
                        str(row.get("code", "")),
                    ) for row in values if row.get("code")],
                )
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存行情质量状态失败: {type(exc).__name__}:{exc}") from exc

    def load_quote_quality(self, scope: str, trade_date: str) -> list[dict]:
        try:
            rows = self._conn.execute(
                "SELECT * FROM quote_quality_state WHERE scope=? AND trade_date=?",
                (scope, trade_date),
            ).fetchall()
            return [dict(row) for row in rows]
        except sqlite3.Error as exc:
            raise WatchStoreError(f"读取行情质量状态失败: {type(exc).__name__}:{exc}") from exc

    def upsert_latest_quotes(self, rows: Iterable[dict]) -> None:
        values = list(rows)
        if not values:
            return
        try:
            with self._transaction():
                self._conn.executemany(
                    """
                    INSERT INTO latest_quote(
                        trade_date, code, name, exchange, last, pct_chg, volume, amount,
                        source, market_time, received_at, processed_at, validity,
                        quality, quality_reason, batch_id, freshness_seconds
                    ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(trade_date, code) DO UPDATE SET
                        name=excluded.name, exchange=excluded.exchange, last=excluded.last,
                        pct_chg=excluded.pct_chg, volume=excluded.volume, amount=excluded.amount,
                        source=excluded.source, market_time=excluded.market_time,
                        received_at=excluded.received_at, processed_at=excluded.processed_at,
                        validity=excluded.validity, quality=excluded.quality,
                        quality_reason=excluded.quality_reason, batch_id=excluded.batch_id,
                        freshness_seconds=excluded.freshness_seconds
                    """,
                    [(
                        str(row.get("trade_date", "")),
                        str(row.get("code", "")), str(row.get("name", "")),
                        str(row.get("exchange", "")), float(row.get("last", 0.0) or 0.0),
                        row.get("pct_chg"), row.get("volume"), row.get("amount"),
                        str(row.get("source", "")), str(row.get("market_time", "")),
                        str(row.get("received_at", "")), str(row.get("processed_at", "")),
                        str(row.get("validity", "INVALID")), str(row.get("quality", "INVALID")),
                        str(row.get("quality_reason", "")), str(row.get("batch_id", "")),
                        row.get("freshness_seconds"),
                    ) for row in values if row.get("code")],
                )
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存最新行情失败: {type(exc).__name__}:{exc}") from exc

    def list_latest_quotes(self, *, trade_date: str = "", limit: int = 10000) -> list[dict]:
        try:
            if trade_date:
                rows = self._conn.execute(
                    "SELECT * FROM latest_quote WHERE trade_date=? ORDER BY code LIMIT ?",
                    (trade_date, max(int(limit), 1)),
                ).fetchall()
            else:
                rows = self._conn.execute(
                    "SELECT * FROM latest_quote ORDER BY processed_at DESC LIMIT ?",
                    (max(int(limit), 1),),
                ).fetchall()
            return [dict(row) for row in rows]
        except sqlite3.Error as exc:
            raise WatchStoreError(f"读取最新行情失败: {type(exc).__name__}:{exc}") from exc

    def save_active_pool(self, trade_date: str, rows: Iterable[dict]) -> None:
        values = list(rows)
        try:
            with self._transaction():
                self._conn.execute("DELETE FROM active_pool_member WHERE trade_date=?", (trade_date,))
                self._conn.executemany(
                    """
                    INSERT INTO active_pool_member(
                        trade_date, code, mandatory, reasons_json, score, as_of
                    ) VALUES(?, ?, ?, ?, ?, ?)
                    """,
                    [(
                        trade_date, str(row.get("code", "")), 1 if row.get("mandatory") else 0,
                        json.dumps(row.get("reasons", []), ensure_ascii=False),
                        row.get("score"), str(row.get("as_of", "")),
                    ) for row in values if row.get("code")],
                )
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存活跃池失败: {type(exc).__name__}:{exc}") from exc

    def load_active_pool(self, trade_date: str) -> list[dict]:
        try:
            rows = self._conn.execute(
                "SELECT * FROM active_pool_member WHERE trade_date=?", (trade_date,)
            ).fetchall()
            return [dict(row) for row in rows]
        except sqlite3.Error as exc:
            raise WatchStoreError(f"读取活跃池失败: {type(exc).__name__}:{exc}") from exc

    def save_security_rules(self, trade_date: str, rows: Iterable[dict]) -> None:
        now = clock.now().isoformat()
        values = list(rows)
        try:
            with self._transaction():
                self._conn.executemany(
                    """
                    INSERT INTO security_rule_snapshot(
                        trade_date, code, rule_version, source, reliability,
                        payload_json, captured_at
                    ) VALUES(?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(trade_date, code) DO UPDATE SET
                        rule_version=excluded.rule_version, source=excluded.source,
                        reliability=excluded.reliability, payload_json=excluded.payload_json,
                        captured_at=excluded.captured_at
                    """,
                    [(
                        trade_date, str(row.get("code", "")), str(row.get("rule_version", "unknown")),
                        str(row.get("source", "unknown")), str(row.get("reliability", "limited")),
                        json.dumps(row, ensure_ascii=False, sort_keys=True, default=str), now,
                    ) for row in values if row.get("code")],
                )
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存证券规则失败: {type(exc).__name__}:{exc}") from exc

    def load_security_rules(self, trade_date: str) -> list[dict]:
        try:
            rows = self._conn.execute(
                "SELECT payload_json FROM security_rule_snapshot WHERE trade_date=?",
                (trade_date,),
            ).fetchall()
            out: list[dict] = []
            for row in rows:
                payload = json.loads(str(row["payload_json"]))
                if isinstance(payload, dict):
                    out.append(payload)
            return out
        except (ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"读取证券规则失败: {type(exc).__name__}:{exc}") from exc

    def save_universe_snapshot(self, trade_date: str, source: str,
                               universe_hash: str, rows: Iterable[dict]) -> None:
        values = list(rows)
        try:
            payload = json.dumps(values, ensure_ascii=False, sort_keys=True, default=str)
            with self._transaction():
                self._conn.execute(
                    """
                    INSERT INTO universe_snapshot(
                        universe_hash, trade_date, source, security_count,
                        payload_json, captured_at
                    ) VALUES(?, ?, ?, ?, ?, ?)
                    ON CONFLICT(universe_hash) DO NOTHING
                    """,
                    (
                        universe_hash, trade_date, source, len(values), payload,
                        clock.now().isoformat(),
                    ),
                )
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存证券池版本失败: {type(exc).__name__}:{exc}") from exc

    # ---------------------------- watch pool ----------------------------

    def sync_default_watch_pool(self, rows: Iterable[dict], *, max_size: int = 200, source: str = "hot_stocks") -> None:
        """刷新启动默认热门股；人工加入的成员始终保留，且不挤占热门 TopN 名额。

        D-2（2026-10-15 修正）：**空候选不得清池**。
        此前 `values` 为空时仍会执行 `DELETE FROM watch_pool_member WHERE manual=0`，
        把全部非人工成员删光 —— 而"候选为空"有两种完全不同的成因：
          (a) 上游热门榜真的返回 0 条；
          (b) 上游取候选失败（限流/超时/覆盖闸不通过）。
        旧实现把 (b) 也当成 (a) 处理，于是在 T1 模式下一旦候选获取失败，
        观察池就被清成只剩人工成员（实测由 204 只掉到 6 只）。
        「取不到」不等于「没有」，故此处直接返回，保留既有池子。

        D-3（2026-10-15 修正）：**候选数骤降不得清池**。
        D-2 只挡住了"空"这一种情形，但**非空的极小集合**同样会毁掉池子，
        而且更隐蔽：实测盯盘循环以 `PAPER_MAX_OBSERVE_CODES=10` 启动后，
        每一轮都用 10 只热门股覆写 204 只的池子，池子长期停在 10 只，
        且日志里全是 OK、没有任何错误 —— 完全看不出问题。
        「取到很少」与「真的有很少」是两回事：`max_size` 配错、上游只回一页、
        覆盖闸只放行一部分，都会产生"非空但极少"。
        故当新集合不足既有热门池的一半时拒绝刷新并保留原池
        （要真正缩减池子，请显式把 max_size 调小，见下方 `cap_shrink_is_honoured`）。
        """
        values = list(rows)
        if not values:
            return
        now = clock.now().isoformat()
        try:
            with self._transaction():
                current_hot = int(self._conn.execute(
                    "SELECT COUNT(*) FROM watch_pool_member WHERE manual=0"
                ).fetchone()[0])
                capacity = max(0, int(max_size))
                # 既有池子够大、而本次候选数不足其一半 ⟹ 判为"取到很少"，保留原池。
                if current_hot >= 4 and len(values) * 2 < current_hot:
                    return
                self._conn.execute("DELETE FROM watch_pool_member WHERE manual=0")
                manual_codes = {
                    str(row[0]) for row in self._conn.execute(
                        "SELECT code FROM watch_pool_member WHERE manual=1"
                    ).fetchall()
                }
                # 热门榜按 max_size 完整灌入；已人工加入的代码跳过，避免覆盖 manual 标记。
                defaults = [
                    row for row in values
                    if str(row.get("code", "")).strip() not in manual_codes
                ][:capacity]
                self._conn.executemany(
                    """
                    INSERT INTO watch_pool_member(
                        code, name, exchange, source, manual, sort_order, created_at, updated_at
                    ) VALUES(?, ?, ?, ?, 0, ?, ?, ?)
                    ON CONFLICT(code) DO NOTHING
                    """,
                    [(
                        str(row.get("code", "")).strip(),
                        str(row.get("name", "")).strip(),
                        str(row.get("exchange", "")).strip(),
                        source,
                        index, now, now,
                    ) for index, row in enumerate(defaults) if row.get("code") and row.get("exchange")],
                )
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"刷新默认观察池失败: {type(exc).__name__}:{exc}") from exc

    def list_watch_pool(self, *, limit: int = 500) -> list[dict]:
        try:
            rows = self._conn.execute(
                """
                SELECT code, name, exchange, source, manual, sort_order, created_at, updated_at
                FROM watch_pool_member
                ORDER BY manual DESC,
                    CASE WHEN manual=1 THEN updated_at END DESC,
                    sort_order ASC, code
                LIMIT ?
                """,
                (max(1, min(int(limit), 10000)),),
            ).fetchall()
            return [dict(row) for row in rows]
        except (TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"读取观察池失败: {type(exc).__name__}:{exc}") from exc

    def upsert_watch_pool_member(self, row: dict) -> dict:
        code = str(row.get("code", "")).strip()
        name = str(row.get("name", "")).strip() or code
        exchange = str(row.get("exchange", "")).strip()
        if not code or not exchange:
            raise WatchStoreError("人工观察池成员缺少 code/exchange")
        now = clock.now().isoformat()
        try:
            with self._transaction():
                self._conn.execute(
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
            saved = self._conn.execute(
                "SELECT * FROM watch_pool_member WHERE code=?", (code,)
            ).fetchone()
            return dict(saved) if saved is not None else {}
        except sqlite3.Error as exc:
            raise WatchStoreError(f"保存人工观察标的失败: {type(exc).__name__}:{exc}") from exc

    def remove_watch_pool_member(self, code: str) -> bool:
        try:
            with self._transaction():
                cursor = self._conn.execute(
                    "DELETE FROM watch_pool_member WHERE code=?", (str(code).strip(),)
                )
            return cursor.rowcount > 0
        except sqlite3.Error as exc:
            raise WatchStoreError(f"移除观察标的失败: {type(exc).__name__}:{exc}") from exc

    def watch_pool_revision(self) -> str:
        try:
            row = self._conn.execute(
                "SELECT COUNT(*) AS n, COALESCE(MAX(updated_at), '') AS ts FROM watch_pool_member"
            ).fetchone()
            return f"{int(row['n'])}:{row['ts']}" if row is not None else "0:"
        except sqlite3.Error as exc:
            raise WatchStoreError(f"读取观察池版本失败: {type(exc).__name__}:{exc}") from exc

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
        now = str(order.get("updated_at") or clock.now().isoformat())
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
        values["updated_at"] = clock.now().isoformat()
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
                values["updated_at"] = clock.now().isoformat()
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

    def list_fills(self, client_order_id: str = "", session_id: str = "",
                   channel: str = "") -> list[dict]:
        clauses: list[str] = []
        params: list[str] = []
        if client_order_id:
            clauses.append("f.client_order_id=?")
            params.append(client_order_id)
        if session_id:
            clauses.append("f.session_id=?")
            params.append(session_id)
        if channel:
            clauses.append("o.channel=?")
            params.append(channel)
        where = " WHERE " + " AND ".join(clauses) if clauses else ""
        try:
            rows = self._conn.execute(
                "SELECT f.* FROM fill f JOIN local_order o ON o.client_order_id=f.client_order_id"
                + where + " ORDER BY f.filled_at, f.fill_id", params
            ).fetchall()
            return [self._decode_json_columns(row) for row in rows]
        except sqlite3.Error as exc:
            raise WatchStoreError(f"查询成交失败: {type(exc).__name__}:{exc}") from exc

    def bind_account_scope(self, channel: str, session_id: str) -> dict:
        """注册不可变账户作用域；相同 session 可在不同 channel 下完全隔离。"""
        normalized_channel = str(channel).strip().upper()
        normalized_session = str(session_id).strip()
        if normalized_channel not in {"SHADOW", "INTERNAL_PAPER", "EXTERNAL_PAPER"}:
            raise WatchStoreError(f"执行模式非法: {normalized_channel}")
        if not normalized_session:
            raise WatchStoreError("session_id 不能为空")
        account_scope = f"{normalized_channel}:{normalized_session}"
        now = clock.now().isoformat()
        try:
            with self._transaction():
                unsafe = self._conn.execute(
                    "SELECT COUNT(*) FROM local_order WHERE session_id=? AND channel<>? "
                    "AND status IN ('SUBMITTING','UNKNOWN','CANCEL_PENDING')",
                    (normalized_session, normalized_channel),
                ).fetchone()[0]
                if int(unsafe) > 0:
                    raise WatchStoreError(
                        "同名会话的旧执行模式仍有 UNKNOWN/SUBMITTING/未完成撤单，禁止切换"
                    )
                row = self._conn.execute(
                    "SELECT * FROM account_scope_registry WHERE account_scope=?", (account_scope,)
                ).fetchone()
                if row is not None and str(row["execution_mode"]) != normalized_channel:
                    raise WatchStoreError("运行会话的 execution_mode 禁止热切换")
                self._conn.execute(
                    """
                    INSERT INTO account_scope_registry(
                        account_scope, channel, session_id, execution_mode, created_at, last_seen_at
                    ) VALUES(?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_scope) DO UPDATE SET last_seen_at=excluded.last_seen_at
                    """,
                    (account_scope, normalized_channel, normalized_session,
                     normalized_channel, now, now),
                )
                saved = self._conn.execute(
                    "SELECT * FROM account_scope_registry WHERE account_scope=?", (account_scope,)
                ).fetchone()
            return dict(saved)
        except sqlite3.Error as exc:
            raise WatchStoreError(f"绑定账户作用域失败: {type(exc).__name__}:{exc}") from exc

    def save_account_sync_state(self, state: dict) -> None:
        now = clock.now().isoformat()
        try:
            with self._transaction():
                self._conn.execute(
                    """
                    INSERT INTO account_sync_state(
                        channel, session_id, source_status, captured_at,
                        last_success_at, last_error, updated_at
                    ) VALUES(?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(channel, session_id) DO UPDATE SET
                        source_status=excluded.source_status,
                        captured_at=excluded.captured_at,
                        last_success_at=CASE WHEN excluded.last_success_at != ''
                            THEN excluded.last_success_at ELSE account_sync_state.last_success_at END,
                        last_error=excluded.last_error,
                        updated_at=excluded.updated_at
                    """,
                    (
                        str(state["channel"]), str(state["session_id"]),
                        str(state.get("source_status", "ERROR")),
                        str(state.get("captured_at", "")),
                        str(state.get("last_success_at", "")),
                        str(state.get("last_error", "")), now,
                    ),
                )
        except (KeyError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存账户同步状态失败: {type(exc).__name__}:{exc}") from exc

    def upsert_external_orders(self, *, channel: str, session_id: str,
                               orders: Iterable[dict], captured_at: str) -> None:
        rows = list(orders)
        try:
            with self._transaction():
                for item in rows:
                    self._conn.execute(
                        """
                        INSERT INTO external_order(
                            channel, session_id, broker_order_id, client_order_id, origin,
                            code, name, exchange, side, order_type, limit_price, qty,
                            filled_qty, remaining_qty, avg_fill_price, total_fee,
                            broker_status, normalized_status, broker_time, created_at,
                            updated_at, metadata_json
                        ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(channel, session_id, broker_order_id) DO UPDATE SET
                            client_order_id=CASE WHEN excluded.client_order_id != ''
                                THEN excluded.client_order_id ELSE external_order.client_order_id END,
                            origin=CASE WHEN excluded.client_order_id != ''
                                THEN 'WATCH_OMS' ELSE external_order.origin END,
                            code=excluded.code, name=excluded.name, exchange=excluded.exchange,
                            side=excluded.side, order_type=excluded.order_type,
                            limit_price=excluded.limit_price, qty=excluded.qty,
                            filled_qty=MAX(external_order.filled_qty, excluded.filled_qty),
                            remaining_qty=CASE
                                WHEN excluded.filled_qty >= external_order.filled_qty
                                THEN excluded.remaining_qty ELSE external_order.remaining_qty END,
                            avg_fill_price=COALESCE(excluded.avg_fill_price, external_order.avg_fill_price),
                            total_fee=COALESCE(excluded.total_fee, external_order.total_fee),
                            broker_status=excluded.broker_status,
                            normalized_status=excluded.normalized_status,
                            broker_time=excluded.broker_time,
                            created_at=CASE WHEN external_order.created_at=''
                                THEN excluded.created_at ELSE external_order.created_at END,
                            updated_at=excluded.updated_at,
                            metadata_json=excluded.metadata_json
                        """,
                        (
                            str(channel), str(session_id), str(item["broker_order_id"]),
                            str(item.get("client_order_id", "")), str(item.get("origin", "EXTERNAL/UNTRACKED")),
                            str(item.get("code", "")), str(item.get("name", "")),
                            str(item.get("exchange", "")), str(item.get("side", "")),
                            str(item.get("order_type", "LIMIT")), item.get("limit_price"),
                            int(item.get("qty", 0)), int(item.get("filled_qty", 0)),
                            int(item.get("remaining_qty", 0)), item.get("avg_fill_price"),
                            item.get("total_fee"), str(item.get("broker_status", "")),
                            str(item.get("normalized_status", "UNKNOWN")),
                            str(item.get("broker_time", "")), str(item.get("created_at", "")),
                            str(captured_at), json.dumps(item.get("metadata", {}), ensure_ascii=False, default=str),
                        ),
                    )
        except (KeyError, TypeError, ValueError, sqlite3.Error) as exc:
            raise WatchStoreError(f"保存外部委托镜像失败: {type(exc).__name__}:{exc}") from exc

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
                # 快照每轮 tick 追加，但下游仅读最新一条；裁剪到最近 N 条，避免 runtime.sqlite3 长期膨胀。
                self._conn.execute(
                    """
                    DELETE FROM account_snapshot
                    WHERE channel = ? AND session_id = ?
                      AND rowid NOT IN (
                          SELECT rowid FROM account_snapshot
                          WHERE channel = ? AND session_id = ?
                          ORDER BY captured_at DESC, rowid DESC
                          LIMIT ?
                      )
                    """,
                    (
                        str(snapshot["channel"]), str(snapshot["session_id"]),
                        str(snapshot["channel"]), str(snapshot["session_id"]),
                        _ACCOUNT_SNAPSHOT_KEEP,
                    ),
                )
                self._conn.execute(
                    """
                    INSERT INTO account_sync_state(
                        channel, session_id, source_status, captured_at,
                        last_success_at, last_error, updated_at
                    ) VALUES(?, ?, 'LIVE', ?, ?, '', ?)
                    ON CONFLICT(channel, session_id) DO UPDATE SET
                        source_status='LIVE', captured_at=excluded.captured_at,
                        last_success_at=excluded.last_success_at, last_error='',
                        updated_at=excluded.updated_at
                    """,
                    (
                        str(snapshot["channel"]), str(snapshot["session_id"]),
                        str(snapshot["captured_at"]), str(snapshot["captured_at"]),
                        clock.now().isoformat(),
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
        now = clock.now().isoformat()
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
        now = clock.now().isoformat()
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
