"""把现有 strategies 包装为持仓外置的 watch 信号适配器。"""
from __future__ import annotations
from datetime import timedelta
from .. import clock

import hashlib
import json
from dataclasses import dataclass

from strategies.boll_strategy import BOLLStrategy
from strategies.ma_cross_strategy import MACrossStrategy
from strategies.macd_strategy import MACDStrategy
from utils.indicators import bollinger_bands, macd, sma

from .bar_store import BarStore
from .models import WatchBar, WatchSignal


def _round_or_none(value) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number != number:  # NaN
        return None
    return round(number, 4)


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

    def reset(self) -> None:
        """清空上一交易日的策略状态，避免跨日重复使用价格序列。"""
        self._states.clear()

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

    def parameter_hash(self) -> str:
        """策略进度的稳定版本键；参数变化后不会误复用旧进度。"""
        payload = {
            "strategy": self.strategy_name,
            "ma_short": self.ma_short,
            "ma_long": self.ma_long,
            "boll_period": self.boll_period,
            "boll_std": self.boll_std,
        }
        encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()[:16]

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
        end = (bar.end_ts.astimezone(clock.CN_TZ) if bar.end_ts.tzinfo is not None
               else bar.end_ts.replace(tzinfo=clock.CN_TZ))
        return WatchSignal(
            code=code,
            action=action,
            strategy=self.strategy_name,
            bar_ts=end.isoformat(),
            reason_codes=self._reason(action),
            detail=self._indicator_detail(state.strategy),
            strategy_version="intraday_rules_v1:" + self.parameter_hash(),
            bar_period_min=bar.period_min,
            valid_until=min(end + timedelta(minutes=bar.period_min),
                            end.replace(hour=15, minute=0, second=0, microsecond=0)).isoformat(),
            invalidation_conditions=["下一根 bar 收盘", "行情过期或缺失", "策略或观察池变化", "当日收盘"],
        )

    def _indicator_detail(self, strategy) -> dict:
        """信号判定的过程值（末两根指标值），仅供展示，不参与判定。"""
        try:
            history = getattr(strategy, "price_history", None)
            if not history:
                return {}
            import pandas as pd

            close = pd.Series(list(history), dtype=float)
            if self.strategy_name == "MA_CROSS":
                short = sma(close, self.ma_short)
                long_ = sma(close, self.ma_long)
                return {
                    "ma_short_prev": _round_or_none(short.iloc[-2]),
                    "ma_short_curr": _round_or_none(short.iloc[-1]),
                    "ma_long_prev": _round_or_none(long_.iloc[-2]),
                    "ma_long_curr": _round_or_none(long_.iloc[-1]),
                }
            if self.strategy_name == "MACD":
                _, _, hist = macd(close)
                return {
                    "hist_prev": _round_or_none(hist.iloc[-2]),
                    "hist_curr": _round_or_none(hist.iloc[-1]),
                }
            if self.strategy_name == "BOLL":
                upper, _, lower = bollinger_bands(close, self.boll_period, self.boll_std)
                return {
                    "upper": _round_or_none(upper.iloc[-1]),
                    "lower": _round_or_none(lower.iloc[-1]),
                    "close": float(close.iloc[-1]),
                }
        except Exception:
            return {}
        return {}

    def _reason(self, action: str) -> list[str]:
        if self.strategy_name == "MA_CROSS":
            return ["MA_GOLDEN" if action == "buy" else "MA_DEATH", "BAR_CLOSED"] if action in {"buy", "sell"} else ["BAR_CLOSED"]
        if self.strategy_name == "MACD":
            return ["MACD_HIST_UP" if action == "buy" else "MACD_HIST_DOWN", "BAR_CLOSED"] if action in {"buy", "sell"} else ["BAR_CLOSED"]
        return ["BOLL_LOWER" if action == "buy" else "BOLL_UPPER", "BAR_CLOSED"] if action in {"buy", "sell"} else ["BAR_CLOSED"]
