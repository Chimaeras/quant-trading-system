"""
量化策略模块
"""
from .base_strategy import BaseStrategy
from .ma_cross_strategy import MACrossStrategy
from .boll_strategy import BOLLStrategy
from .macd_strategy import MACDStrategy

__all__ = ['BaseStrategy', 'MACrossStrategy', 'BOLLStrategy', 'MACDStrategy']
