"""
均线交叉策略

口径与 utils.indicators.sma（= Java TechnicalIndicators）一致。
金叉（短均线上穿长均线）买入；死叉（短均线下穿长均线）卖出。

实现：每根 bar 复用 utils.indicators.sma 算出整段均线，取末两根判交叉，
避免旧实现每根 bar O(period) 重算且未与对齐口径共享。
"""
from typing import Dict, Optional

import pandas as pd

from utils.indicators import sma
from .base_strategy import BaseStrategy


class MACrossStrategy(BaseStrategy):
    """均线交叉策略"""

    def __init__(self, short_period: int = 5, long_period: int = 20):
        super().__init__('MA_Cross', {
            'short_period': short_period,
            'long_period': long_period
        })
        self.price_history: list = []

    def on_bar(self, bar: Dict) -> Optional[str]:
        """K线处理逻辑"""
        close_price = bar.get('close', bar.get('Close'))
        # 缺键默认 0 会污染整段均线（EMA/SMA 无法从 0 种子恢复）——fail-closed 直接抛错
        if not isinstance(close_price, (int, float)):
            raise ValueError(f"bar 缺少有效 close 字段: {bar!r}")
        self.price_history.append(close_price)

        long_period = self.params['long_period']
        if len(self.price_history) < long_period:
            return 'hold'

        close = pd.Series(self.price_history, dtype=float)
        short_ma = sma(close, self.params['short_period'])
        long_ma = sma(close, long_period)

        # 短/长均线都须有 ≥2 个非 NaN 值：len==long_period 时长均线仅 1 个有效值，
        # l_prev 为 NaN 使所有比较为 False，交叉信号永不触发
        if len(short_ma.dropna()) < 2 or len(long_ma.dropna()) < 2:
            return 'hold'
        # 均线末值
        s_prev = float(short_ma.iloc[-2])
        s_curr = float(short_ma.iloc[-1])
        l_prev = float(long_ma.iloc[-2])
        l_curr = float(long_ma.iloc[-1])

        # 金叉
        if s_prev <= l_prev and s_curr > l_curr:
            if self.position == 0:
                return 'buy'
        # 死叉
        if s_prev >= l_prev and s_curr < l_curr:
            if self.position > 0:
                return 'sell'

        return 'hold'

    def on_tick(self, tick: Dict) -> None:
        """tick处理"""
        pass
