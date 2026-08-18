"""watch 当日状态持久化，支持重启恢复。"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from .cooldown import CooldownBook


@dataclass
class WatchState:
    trade_date: str
    day_trade_count: int = 0
    cooldowns: dict = field(default_factory=dict)
    open_order_ids: list[str] = field(default_factory=list)
    updated_at: str = ""

    def to_dict(self) -> dict:
        return {
            "trade_date": self.trade_date,
            "day_trade_count": self.day_trade_count,
            "cooldowns": self.cooldowns,
            "open_order_ids": self.open_order_ids,
            "updated_at": self.updated_at or datetime.now().isoformat(),
        }

    def save(self, path: Path, cooldown: CooldownBook) -> None:
        self.cooldowns = cooldown.to_dict()
        self.updated_at = datetime.now().isoformat()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.to_dict(), ensure_ascii=False, indent=2), encoding="utf-8")

    @classmethod
    def load(cls, path: Path, trade_date: str, cooldown_min: int) -> tuple["WatchState", CooldownBook]:
        if not path.exists():
            state = cls(trade_date=trade_date)
            return state, CooldownBook(cooldown_min=cooldown_min)
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if data.get("trade_date") != trade_date:
                state = cls(trade_date=trade_date)
                return state, CooldownBook(cooldown_min=cooldown_min)
            state = cls(
                trade_date=trade_date,
                day_trade_count=max(int(data.get("day_trade_count", 0)), 0),
                cooldowns=data.get("cooldowns", {}) if isinstance(data.get("cooldowns", {}), dict) else {},
                open_order_ids=[str(value) for value in data.get("open_order_ids", [])],
                updated_at=str(data.get("updated_at", "")),
            )
            return state, CooldownBook.from_dict(state.cooldowns, cooldown_min)
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            # 状态损坏时不复用未知交易计数；调用方仍会以柜台 orders 做安全校验。
            state = cls(trade_date=trade_date)
            return state, CooldownBook(cooldown_min=cooldown_min)
