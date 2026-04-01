"""
信号引擎 - 策略信号生成与分发
"""
from typing import Dict, List, Any
from datetime import datetime


class SignalEngine:
    """信号引擎 - 聚合多策略信号"""

    def __init__(self):
        self.strategies: List[Any] = []
        self.signal_history: List[Dict] = []

    def add_strategy(self, strategy: Any) -> None:
        """添加策略"""
        self.strategies.append(strategy)

    def process_bar(self, bar: Dict) -> List[Dict]:
        """处理K线，收集所有策略信号"""
        signals = []
        for strategy in self.strategies:
            signal = strategy.on_bar(bar)
            if signal and signal != 'hold':
                sig_record = {
                    'strategy': strategy.name,
                    'action': signal,
                    'price': bar.get('close', bar.get('Close', 0)),
                    'timestamp': datetime.now().isoformat()
                }
                signals.append(sig_record)
                self.signal_history.append(sig_record)
        return signals
