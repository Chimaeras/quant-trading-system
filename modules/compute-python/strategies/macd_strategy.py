"""
MACD 策略

口径与 utils.indicators.macd（= Java TechnicalIndicators.macd）一致：
dif=EMA(fast)-EMA(slow)；dea=EMA(dif, signal)；柱线 hist=(dif-dea)*2。
金叉（hist 由 <=0 翻正）买入；死叉（hist 由 >=0 翻负）卖出。

实现：策略内部累积 close 序列，每根 bar 调用 utils.indicators.macd 一次性算出
整段柱线，取末两根判交叉——避免重复实现 EMA，也避免旧实现把 signal 线误算成 SMA。
"""
from typing import Dict, Optional

import pandas as pd

from utils.indicators import macd as calc_macd
from .base_strategy import BaseStrategy


class MACDStrategy(BaseStrategy):
    """MACD 金叉死叉策略"""

    def __init__(self, fast: int = 12, slow: int = 26, signal: int = 9):
        super().__init__('MACD', {'fast': fast, 'slow': slow, 'signal': signal})
        self.price_history: list = []

    def on_bar(self, bar: Dict) -> Optional[str]:
        """K线处理逻辑"""
        close_price = bar.get('close', bar.get('Close', 0))
        self.price_history.append(close_price)

        fast_period = self.params['fast']
        slow_period = self.params['slow']
        signal_period = self.params['signal']

        # 需足够长度产出稳定 signal 线（slow 的 EMA 需 warmup，再叠加 signal 的 EMA warmup）
        if len(self.price_history) < slow_period + signal_period:
            return 'hold'

        close = pd.Series(self.price_history, dtype=float)
        _, _, hist = calc_macd(close, fast_period, slow_period, signal_period)

        if len(hist) < 2:
            return 'hold'
        prev_h = float(hist.iloc[-2])
        curr_h = float(hist.iloc[-1])

        # 金叉：柱线由非正翻正
        if prev_h <= 0 < curr_h:
            if self.position == 0:
                return 'buy'
        # 死叉：柱线由非负翻负
        if prev_h >= 0 > curr_h:
            if self.position > 0:
                return 'sell'

        return 'hold'

    def on_tick(self, tick: Dict) -> None:
        """tick处理"""
        pass
