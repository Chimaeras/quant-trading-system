"""watch 当日状态持久化，支持重启恢复。"""
from __future__ import annotations

import json
import os
import tempfile
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
    # trade 请求超时/无响应时无法确认是否已受理；该记录必须跨进程保留，
    # 在对账前禁止再次下单，避免重启后重复委托。
    unknown_submits: list[dict] = field(default_factory=list)
    updated_at: str = ""
    # 仅运行时使用，不写回文件。自动交易不能把损坏状态当成全新账户继续。
    recovery_error: str = ""

    def to_dict(self) -> dict:
        return {
            "trade_date": self.trade_date,
            "day_trade_count": self.day_trade_count,
            "cooldowns": self.cooldowns,
            "open_order_ids": self.open_order_ids,
            "unknown_submits": self.unknown_submits,
            "updated_at": self.updated_at or datetime.now().isoformat(),
        }

    def save(self, path: Path, cooldown: CooldownBook) -> None:
        self.cooldowns = cooldown.to_dict()
        self.updated_at = datetime.now().isoformat()
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = json.dumps(self.to_dict(), ensure_ascii=False, indent=2)
        temp_name = ""
        try:
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=path.parent,
                prefix=f".{path.name}.",
                suffix=".tmp",
                delete=False,
            ) as handle:
                temp_name = handle.name
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_name, path)
        finally:
            if temp_name and os.path.exists(temp_name):
                try:
                    os.unlink(temp_name)
                except OSError:
                    pass

    @classmethod
    def from_dict(
        cls,
        data: dict,
        trade_date: str,
        cooldown_min: int,
    ) -> tuple["WatchState", CooldownBook]:
        """从 JSON/数据库共用的数据结构恢复当日安全状态。"""
        if not isinstance(data, dict):
            raise ValueError("state 顶层必须是对象")
        if data.get("trade_date") != trade_date:
            state = cls(trade_date=trade_date)
            return state, CooldownBook(cooldown_min=cooldown_min)
        state = cls(
            trade_date=trade_date,
            day_trade_count=max(int(data.get("day_trade_count", 0)), 0),
            cooldowns=data.get("cooldowns", {}) if isinstance(data.get("cooldowns", {}), dict) else {},
            open_order_ids=[str(value) for value in data.get("open_order_ids", [])],
            unknown_submits=[
                item for item in data.get("unknown_submits", [])
                if isinstance(item, dict)
            ],
            updated_at=str(data.get("updated_at", "")),
        )
        return state, CooldownBook.from_dict(state.cooldowns, cooldown_min)

    @classmethod
    def load(cls, path: Path, trade_date: str, cooldown_min: int) -> tuple["WatchState", CooldownBook]:
        if not path.exists():
            state = cls(trade_date=trade_date)
            return state, CooldownBook(cooldown_min=cooldown_min)
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            return cls.from_dict(data, trade_date, cooldown_min)
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as exc:
            # 无法证明历史计数/unknown 完整时必须显式带出恢复错误；dry-run
            # 可以继续看行情，live 由 WatchLoop fail-closed 阻断。
            state = cls(
                trade_date=trade_date,
                recovery_error=f"{type(exc).__name__}:{exc}",
            )
            return state, CooldownBook(cooldown_min=cooldown_min)
