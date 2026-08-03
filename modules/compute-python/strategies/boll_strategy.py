"""
布林带策略 - 价格突破上轨卖出，突破下轨买入

口径与 utils.indicators.bollinger_bands（= Java TechnicalIndicators，样本标准差 ddof=1）一致。
实现：每根 bar 复用 utils.indicators.bollinger_bands，避免旧实现手写方差且未与对齐口径共享。
"""
from typing import Dict, Optional

import pandas as pd

from utils.indicators import bollinger_bands
from .base_strategy import BaseStrategy


class BOLLStrategy(BaseStrategy):
    """布林带策略 - 价格突破上轨卖出，突破下轨买入"""

    def __init__(self, period: int = 20, std_dev: float = 2.0):
        super().__init__('BOLL', {'period': period, 'std_dev': std_dev})
        self.price_history: list = []

    def on_bar(self, bar: Dict) -> Optional[str]:
        """K线处理逻辑"""
        close_price = bar.get('close', bar.get('Close', 0))
        self.price_history.append(close_price)

        period = self.params['period']
        if len(self.price_history) < period:
            return 'hold'

        close = pd.Series(self.price_history, dtype=float)
        std_mult = self.params.get('std_dev', 2.0)
        upper, _, lower = bollinger_bands(close, period, std_mult)

        upper_now = float(upper.iloc[-1])
        lower_now = float(lower.iloc[-1])

        # 下轨附近买入
        if close_price <= lower_now and self.position == 0:
            return 'buy'
        # 上轨附近卖出
        if close_price >= upper_now and self.position > 0:
            return 'sell'

        return 'hold'

    def on_tick(self, tick: Dict) -> None:
        """tick处理"""
        pass
