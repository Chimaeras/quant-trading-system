"""
策略基类 - 所有策略都需要继承这个类
"""
from abc import ABC, abstractmethod
from datetime import datetime
from typing import Dict, List, Optional, Any


class BaseStrategy(ABC):
    """策略基类"""

    def __init__(self, name: str, params: Dict[str, Any] = None):
        self.name = name
        self.params = params or {}
        self.position = 0  # 持仓
        self.cash = 0.0  # 现金
        self.signals: List[Dict] = []  # 信号记录

    @abstractmethod
    def on_bar(self, bar: Dict) -> Optional[str]:
        """
        每根K线触发
        返回: 'buy' | 'sell' | 'hold' | None
        """
        pass

    @abstractmethod
    def on_tick(self, tick: Dict) -> None:
        """每个tick触发"""
        pass

    def generate_signal(self, action: str, price: float, volume: int) -> Dict:
        """生成交易信号"""
        return {
            'strategy': self.name,
            'action': action,
            'price': price,
            'volume': volume,
            'timestamp': datetime.now().isoformat()
        }
