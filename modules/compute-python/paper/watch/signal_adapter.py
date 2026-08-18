"""把现有 strategies 包装为持仓外置的 watch 信号适配器。"""
from __future__ import annotations

from dataclasses import dataclass

from strategies.boll_strategy import BOLLStrategy
from strategies.ma_cross_strategy import MACrossStrategy
from strategies.macd_strategy import MACDStrategy

from .bar_store import BarStore
from .models import WatchBar, WatchSignal


@dataclass
class _StrategyState:
    strategy: object
    seeded_count: int = 0


class SignalAdapter:
    """每只股票独立维护策略价格历史，但不读取策略对象的 position。"""

    def __init__(
        self,
        strategy_name: str = "MA_CROSS",
        *,
        ma_short: int = 5,
        ma_long: int = 20,
        boll_period: int = 20,
        boll_std: float = 2.0,
    ):
        self.strategy_name = strategy_name.upper()
        self.ma_short = ma_short
        self.ma_long = ma_long
        self.boll_period = boll_period
        self.boll_std = boll_std
        self._states: dict[str, _StrategyState] = {}

    def _new_strategy(self):
        if self.strategy_name == "MA_CROSS":
            return MACrossStrategy(self.ma_short, self.ma_long)
        if self.strategy_name == "MACD":
            return MACDStrategy()
        if self.strategy_name == "BOLL":
            return BOLLStrategy(self.boll_period, self.boll_std)
        raise ValueError(f"未知 watch 策略: {self.strategy_name}")

    def required_bars(self) -> int:
        if self.strategy_name == "MA_CROSS":
            return max(self.ma_long + 1, self.ma_short + 2)
        if self.strategy_name == "MACD":
            return 26 + 2 * 9
        return self.boll_period

    def seed(self, code: str, bars: list[WatchBar]) -> None:
        state = _StrategyState(self._new_strategy(), 0)
        for bar in bars:
            state.strategy.on_bar({"close": bar.close})
            state.seeded_count += 1
        self._states[code] = state

    def ready(self, code: str) -> bool:
        state = self._states.get(code)
        return state is not None and state.seeded_count >= self.required_bars()

    def on_closed_bar(self, code: str, bar: WatchBar, position_qty: int) -> WatchSignal:
        state = self._states.setdefault(code, _StrategyState(self._new_strategy(), 0))
        # position 是输入快照，不依赖 BaseStrategy.position 的历史副作用。
        state.strategy.position = 1 if position_qty > 0 else 0
        action = state.strategy.on_bar({"close": bar.close}) or "hold"
        state.seeded_count += 1
        reason = self._reason(action)
        return WatchSignal(
            code=code,
            action=action,
            strategy=self.strategy_name,
            bar_ts=bar.end_ts.isoformat(),
            reason_codes=reason,
        )

    def _reason(self, action: str) -> list[str]:
        if self.strategy_name == "MA_CROSS":
            return ["MA_GOLDEN" if action == "buy" else "MA_DEATH", "BAR_CLOSED"] if action in {"buy", "sell"} else ["BAR_CLOSED"]
        if self.strategy_name == "MACD":
            return ["MACD_HIST_UP" if action == "buy" else "MACD_HIST_DOWN", "BAR_CLOSED"] if action in {"buy", "sell"} else ["BAR_CLOSED"]
        return ["BOLL_LOWER" if action == "buy" else "BOLL_UPPER", "BAR_CLOSED"] if action in {"buy", "sell"} else ["BAR_CLOSED"]
