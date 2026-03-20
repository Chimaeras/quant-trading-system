"""
布林带策略
"""
from typing import Dict, Optional

from .base_strategy import BaseStrategy


class BOLLStrategy(BaseStrategy):
    """布林带策略 - 价格突破上轨卖出，突破下轨买入"""

    def __init__(self, period: int = 20, std_dev: float = 2.0):
        super().__init__('BOLL', {'period': period, 'std_dev': std_dev})
        self.price_history: list = []

    def on_bar(self, bar: Dict) -> Optional[str]:
        """K线处理逻辑"""
        import math

        close_price = bar.get('close', bar.get('Close', 0))
        self.price_history.append(close_price)

        period = self.params['period']
        if len(self.price_history) < period:
            return 'hold'

        # 计算中轨(MA)、上轨、下轨
        ma = sum(self.price_history[-period:]) / period
        variance = sum((p - ma) ** 2 for p in self.price_history[-period:]) / period
        std = math.sqrt(variance) if variance > 0 else 0
        std_mult = self.params.get('std_dev', 2.0)
        upper = ma + std_mult * std
        lower = ma - std_mult * std

        # 下轨附近买入
        if close_price <= lower and self.position == 0:
            return 'buy'
        # 上轨附近卖出
        if close_price >= upper and self.position > 0:
            return 'sell'

        return 'hold'

    def on_tick(self, tick: Dict) -> None:
        """tick处理"""
        pass
