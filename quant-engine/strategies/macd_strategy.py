"""
MACD 策略
"""
from typing import Dict, Optional

from .base_strategy import BaseStrategy


class MACDStrategy(BaseStrategy):
    """MACD 金叉死叉策略"""

    def __init__(self, fast: int = 12, slow: int = 26, signal: int = 9):
        super().__init__('MACD', {'fast': fast, 'slow': slow, 'signal': signal})
        self.price_history: list = []
        self.ema_fast: list = []
        self.ema_slow: list = []
        self.macd_line: list = []
        self.signal_line: list = []
        self.histogram: list = []

    def _ema(self, data: list, period: int) -> float:
        if len(data) < period:
            return sum(data) / len(data) if data else 0
        k = 2 / (period + 1)
        ema = sum(data[:period]) / period
        for price in data[period:]:
            ema = price * k + ema * (1 - k)
        return ema

    def on_bar(self, bar: Dict) -> Optional[str]:
        """K线处理逻辑"""
        close_price = bar.get('close', bar.get('Close', 0))
        self.price_history.append(close_price)

        fast_period = self.params['fast']
        slow_period = self.params['slow']
        signal_period = self.params['signal']

        if len(self.price_history) < slow_period + signal_period:
            return 'hold'

        ema_fast = self._ema(self.price_history, fast_period)
        ema_slow = self._ema(self.price_history, slow_period)
        macd = ema_fast - ema_slow

        self.ema_fast.append(ema_fast)
        self.ema_slow.append(ema_slow)
        self.macd_line.append(macd)

        if len(self.macd_line) >= signal_period:
            signal_val = self._ema(self.macd_line[-signal_period:], signal_period)
            self.signal_line.append(signal_val)
            self.histogram.append(macd - signal_val)

            # 金叉
            if len(self.histogram) >= 2 and self.histogram[-2] <= 0 < self.histogram[-1]:
                if self.position == 0:
                    return 'buy'
            # 死叉
            if len(self.histogram) >= 2 and self.histogram[-2] >= 0 > self.histogram[-1]:
                if self.position > 0:
                    return 'sell'

        return 'hold'

    def on_tick(self, tick: Dict) -> None:
        """tick处理"""
        pass
