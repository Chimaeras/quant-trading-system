"""watch 每轮快照和日报落盘。"""
from __future__ import annotations

import json
from pathlib import Path

from ..artifacts import atomic_write_text
from ..mx_http import sanitize_payload
from .models import WatchTick


def watch_day_dir(log_base: Path, trade_date: str) -> Path:
    return Path(log_base) / "watch" / trade_date


def write_tick(log_base: Path, tick: WatchTick) -> Path:
    out = watch_day_dir(Path(log_base), tick.trade_date) / tick.run_id
    out.mkdir(parents=True, exist_ok=True)
    # 复用 paper.artifacts 的原子写：write_text 在进程中断/磁盘满时留下半截 JSON，
    # 事后排查与自动化读取都会解析失败。
    atomic_write_text(
        out / "tick.json",
        json.dumps(sanitize_payload(tick.to_dict()), ensure_ascii=False, indent=2, default=str),
    )
    return out


def append_daily_summary(log_base: Path, tick: WatchTick) -> Path:
    out = watch_day_dir(Path(log_base), tick.trade_date)
    out.mkdir(parents=True, exist_ok=True)
    path = out / "daily.md"
    lines = [
        f"## {tick.finished_at} WatchTick {tick.run_id}",
        "",
        f"- 模式: {'模拟盘 live' if tick.live else 'dry-run'}",
        f"- 状态: {tick.status}",
        f"- 新收盘 bar: {len(tick.new_closed_bars)}，信号: {len(tick.signals)}，意图: {len(tick.intents)}，提交: {len(tick.submits)}",
    ]
    for error in tick.errors:
        lines.append(f"- 错误: {error}")
    with path.open("a", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return path
