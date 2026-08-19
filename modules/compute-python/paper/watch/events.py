"""盘中盯盘事件流水：append-only events.jsonl，供监控面板增量拉取。

每行一个 JSON 事件（已脱敏），格式：
{"seq": 1, "ts": "09:35:02", "date": "2026-08-19", "cycle": 18,
 "type": "QUOTE|BAR_CLOSED|SIGNAL|SIZING|RISK_GATE|SUBMIT|ACCOUNT|CYCLE|ERROR",
"code": "600519", "name": "贵州茅台", "detail": {...}}
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from ..mx_http import sanitize_payload

EVENT_TYPES = {
    "QUOTE", "QUOTE_FAIL", "BAR_CLOSED", "SIGNAL", "SIZING",
    "RISK_GATE", "SUBMIT", "ACCOUNT", "CYCLE", "ERROR",
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
) -> list[dict]:
    """按游标增量读取事件；损坏行跳过，不让面板因单行脏数据 500。"""
    path = events_path(log_base, trade_date)
    if not path.exists():
        return []
    out: list[dict] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
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
            if event_type and str(event.get("type", "")) != event_type:
                continue
            out.append(event)
            if len(out) >= limit:
                break
    return out


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
