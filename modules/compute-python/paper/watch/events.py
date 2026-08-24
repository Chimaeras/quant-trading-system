"""盘中盯盘事件流水：append-only events.jsonl，供监控面板增量拉取。

每行一个 JSON 事件（已脱敏），格式：
{"seq": 1, "ts": "09:35:02", "date": "2026-08-19", "cycle": 18,
 "type": "QUOTE|BAR_CLOSED|SIGNAL|SIZING|RISK_GATE|ORDER|SUBMIT|FILL|CANCEL|ACCOUNT|READINESS|CYCLE|ERROR",
"code": "600519", "name": "贵州茅台", "detail": {...}}
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from ..mx_http import sanitize_payload

EVENT_TYPES = {
    "QUOTE", "QUOTE_FAIL", "BAR_CLOSED", "SIGNAL", "SIZING",
    "RISK_GATE", "ORDER", "SUBMIT", "FILL", "CANCEL", "ACCOUNT",
    "READINESS", "CYCLE", "ERROR", "MARKET_DATA", "ACTIVE_POOL",
    "QUOTE_QUALITY",
}


def events_path(log_base: Path, trade_date: str) -> Path:
    return Path(log_base) / "watch" / trade_date / "events.jsonl"


def read_events(
    log_base: Path,
    trade_date: str,
    *,
    after_seq: int = 0,
    code: str = "",
    event_type: str = "",
    limit: int = 2000,
    latest: bool = False,
) -> list[dict]:
    """按游标增量读取事件；损坏行跳过，不让面板因单行脏数据 500。"""
    path = events_path(log_base, trade_date)
    if not path.exists():
        return []
    out: list[dict] = []
    event_types = {
        item.strip() for item in str(event_type or "").split(",") if item.strip()
    }
    lines = (
        _tail_lines(
            path,
            limit,
            predicate=(
                (lambda event: str(event.get("type", "")) in event_types)
                if event_types else None
            ),
        ) if latest and after_seq <= 0
        else _lines_after_seq(path, after_seq) if after_seq > 0
        else None
    )
    handle = path.open("r", encoding="utf-8") if lines is None else None
    try:
        source = handle if handle is not None else lines
        for line in source:
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except ValueError:
                continue
            if not isinstance(event, dict):
                continue
            try:
                seq = int(event.get("seq", 0))
            except (TypeError, ValueError):
                continue
            if seq <= after_seq:
                continue
            if code and str(event.get("code", "")) != code:
                continue
            if event_types and str(event.get("type", "")) not in event_types:
                continue
            out.append(event)
            if len(out) >= limit:
                break
    finally:
        if handle is not None:
            handle.close()
    return out


def _event_seq(raw: bytes) -> int | None:
    try:
        event = json.loads(raw.decode("utf-8"))
        return int(event.get("seq", 0)) if isinstance(event, dict) else None
    except (UnicodeDecodeError, ValueError, TypeError):
        return None


def _lines_after_seq(path: Path, after_seq: int, *, block_size: int = 64 * 1024) -> list[str]:
    """从文件尾部回溯到游标，正常轮询只读取本轮新增字节。"""
    selected_reversed: list[bytes] = []
    with path.open("rb") as handle:
        handle.seek(0, 2)
        position = handle.tell()
        carry = b""
        boundary_found = False
        while position > 0 and not boundary_found:
            size = min(block_size, position)
            position -= size
            handle.seek(position)
            data = handle.read(size) + carry
            parts = data.split(b"\n")
            if position > 0:
                carry = parts[0]
                complete = parts[1:]
            else:
                carry = b""
                complete = parts
            for raw in reversed(complete):
                if not raw.strip():
                    continue
                seq = _event_seq(raw)
                if seq is not None and seq <= after_seq:
                    boundary_found = True
                    break
                selected_reversed.append(raw)
    return [raw.decode("utf-8", errors="replace") for raw in reversed(selected_reversed)]


def _tail_lines(
    path: Path,
    lines: int,
    *,
    block_size: int = 64 * 1024,
    predicate=None,
) -> list[str]:
    if lines <= 0 or not path.exists():
        return []
    selected_reversed: list[bytes] = []
    with path.open("rb") as handle:
        handle.seek(0, 2)
        position = handle.tell()
        carry = b""
        while position > 0 and len(selected_reversed) < lines:
            size = min(block_size, position)
            position -= size
            handle.seek(position)
            data = handle.read(size) + carry
            parts = data.split(b"\n")
            if position > 0:
                carry = parts[0]
                complete = parts[1:]
            else:
                carry = b""
                complete = parts
            for raw in reversed(complete):
                if raw.strip():
                    if predicate is not None:
                        try:
                            event = json.loads(raw.decode("utf-8"))
                        except (UnicodeDecodeError, ValueError, TypeError):
                            continue
                        if not isinstance(event, dict) or not predicate(event):
                            continue
                    selected_reversed.append(raw)
                    if len(selected_reversed) >= lines:
                        break
    return [
        raw.decode("utf-8", errors="replace").strip()
        for raw in reversed(selected_reversed)
    ]


class EventLog:
    """按交易日追加事件；seq 跨重启续接（打开时数已有行数）。"""

    def __init__(self, log_base: Path, trade_date: str, now_fn=None):
        self.log_base = Path(log_base)
        self.trade_date = trade_date
        self.now_fn = now_fn or datetime.now
        self._seq = 0
        self._handle = None
        self._prepare()

    def _prepare(self) -> None:
        path = events_path(self.log_base, self.trade_date)
        if path.exists():
            with path.open("r", encoding="utf-8") as handle:
                for line in handle:
                    if line.strip():
                        self._seq += 1
        path.parent.mkdir(parents=True, exist_ok=True)
        self._handle = path.open("a", encoding="utf-8")

    def emit(
        self,
        event_type: str,
        *,
        code: str = "",
        name: str = "",
        cycle: int = 0,
        detail: dict | None = None,
        now: datetime | None = None,
    ) -> int:
        if event_type not in EVENT_TYPES:
            raise ValueError(f"未知事件类型: {event_type}")
        current = now or self.now_fn()
        self._seq += 1
        event = {
            "seq": self._seq,
            "ts": current.isoformat(timespec="seconds"),
            "date": self.trade_date,
            "cycle": cycle,
            "type": event_type,
            "code": code,
            "name": name,
            "detail": sanitize_payload(detail or {}),
        }
        self._handle.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")
        self._handle.flush()
        return self._seq

    def close(self) -> None:
        if self._handle and not self._handle.closed:
            self._handle.close()
