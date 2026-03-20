"""
均线交叉策略
"""
from typing import Dict, Optional

from .base_strategy import BaseStrategy


class MACrossStrategy(BaseStrategy):
    """均线交叉策略"""

    def __init__(self, short_period: int = 5, long_period: int = 20):
        super().__init__('MA_Cross', {
            'short_period': short_period,
            'long_period': long_period
        })
        self.short_ma: list = []
        self.long_ma: list = []
        self.price_history: list = []

    def on_bar(self, bar: Dict) -> Optional[str]:
        """K线处理逻辑"""
        close_price = bar.get('close', bar.get('Close', 0))
        self.price_history.append(close_price)

        if len(self.price_history) < self.params['long_period']:
            return 'hold'

        # 计算均线
        short_ma = sum(self.price_history[-self.params['short_period']:]) / self.params['short_period']
        long_ma = sum(self.price_history[-self.params['long_period']:]) / self.params['long_period']

        self.short_ma.append(short_ma)
        self.long_ma.append(long_ma)

        # 金叉买入
        if len(self.short_ma) >= 2:
            if self.short_ma[-2] <= self.long_ma[-2] and self.short_ma[-1] > self.long_ma[-1]:
                if self.position == 0:
                    return 'buy'

        # 死叉卖出
        if len(self.short_ma) >= 2:
            if self.short_ma[-2] >= self.long_ma[-2] and self.short_ma[-1] < self.long_ma[-1]:
                if self.position > 0:
                    return 'sell'

        return 'hold'

    def on_tick(self, tick: Dict) -> None:
        """tick处理"""
        pass
