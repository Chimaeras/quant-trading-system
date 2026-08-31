"""反向信号冷却表，可持久化到 state.json。"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta


@dataclass
class CooldownBook:
    cooldown_min: int = 15
    last_signal: dict[str, dict[str, str]] = field(default_factory=dict)

    def can_trade(self, code: str, side: str, now: datetime) -> bool:
        previous = self.last_signal.get(code, {})
        previous_side = previous.get("side", "")
        previous_at = previous.get("at", "")
        if not previous_side or previous_side == side or not previous_at:
            return True
        try:
            elapsed = now - datetime.fromisoformat(previous_at)
        except (ValueError, TypeError):
            # ValueError：previous_at 非法 iso；TypeError：now 为 naive 而 previous_at 为 aware
            # （或反之），相减抛 TypeError。两者都视为「无法判断冷却是否到期」→ 保守拦截。
            return False
        return elapsed >= timedelta(minutes=self.cooldown_min)

    def record(self, code: str, side: str, now: datetime) -> None:
        if side not in {"buy", "sell"}:
            raise ValueError(f"side 非法: {side}")
        self.last_signal[code] = {"side": side, "at": now.isoformat()}

    def reason(self, code: str, side: str, now: datetime) -> str:
        return "" if self.can_trade(code, side, now) else "COOLDOWN"

    def to_dict(self) -> dict:
        return dict(self.last_signal)

    @classmethod
    def from_dict(cls, payload: dict, cooldown_min: int) -> "CooldownBook":
        data = payload if isinstance(payload, dict) else {}
        return cls(cooldown_min=cooldown_min, last_signal={
            str(code): value for code, value in data.items()
            if isinstance(value, dict) and value.get("side") in {"buy", "sell"} and value.get("at")
        })
