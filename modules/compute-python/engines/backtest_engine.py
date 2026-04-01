"""
回测引擎 - 支持快速回测策略
"""
import pandas as pd
from typing import Dict, List, Any
from datetime import datetime


class BacktestEngine:
    """回测引擎"""

    def __init__(self, initial_cash: float = 1000000):
        self.initial_cash = initial_cash
        self.cash = initial_cash
        self.position = 0
        self.avg_cost = 0.0
        self.trades: List[Dict] = []
        self.equity_curve: List[Dict] = []

    def run(self, strategy: Any, data: pd.DataFrame) -> Dict:
        """
        运行回测
        :param strategy: 策略实例
        :param data: 历史数据 DataFrame (需含 open, high, low, close, volume 列)
        :return: 回测结果
        """
        self.cash = self.initial_cash
        self.position = 0
        self.avg_cost = 0.0
        self.trades = []
        self.equity_curve = []

        # 统一列名
        data = data.copy()
        col_map = {'Open': 'open', 'High': 'high', 'Low': 'low', 'Close': 'close', 'Volume': 'volume'}
        data.columns = [col_map.get(c, c) for c in data.columns]

        for index, row in data.iterrows():
            bar = row.to_dict()
            if 'close' not in bar and 'Close' in bar:
                bar['close'] = bar['Close']
            signal = strategy.on_bar(bar)

            # 执行交易
            if signal == 'buy' and self.cash > 0:
                volume = int(self.cash * 0.95 / bar['close'])
                if volume > 0:
                    cost = volume * bar['close']
                    self.cash -= cost
                    self.avg_cost = (self.avg_cost * self.position + bar['close'] * volume) / (self.position + volume)
                    self.position += volume
                    self.trades.append({
                        'type': 'buy',
                        'price': bar['close'],
                        'volume': volume,
                        'time': str(index)
                    })

            elif signal == 'sell' and self.position > 0:
                revenue = self.position * bar['close']
                self.cash += revenue
                self.trades.append({
                    'type': 'sell',
                    'price': bar['close'],
                    'volume': self.position,
                    'time': str(index)
                })
                self.position = 0
                self.avg_cost = 0.0

            # 记录权益曲线
            equity = self.cash + self.position * bar['close']
            self.equity_curve.append({
                'time': str(index),
                'equity': equity
            })

        return self._calculate_metrics(data)

    def _calculate_metrics(self, data: pd.DataFrame) -> Dict:
        """计算回测指标"""
        if not self.equity_curve:
            return {}

        final_equity = self.cash + self.position * self.equity_curve[-1]['equity'] if self.position > 0 else self.cash
        if self.position > 0 and data is not None and len(data) > 0:
            last_close = data.iloc[-1]['close'] if 'close' in data.columns else data.iloc[-1].get('Close', 0)
            final_equity = self.cash + self.position * last_close

        total_return = (final_equity - self.initial_cash) / self.initial_cash if self.initial_cash > 0 else 0

        # 计算夏普比率
        equity_series = pd.Series([e['equity'] for e in self.equity_curve])
        returns = equity_series.pct_change().dropna()
        sharpe_ratio = float((returns.mean() / returns.std()) * (252 ** 0.5)) if len(returns) > 0 and returns.std() > 0 else 0.0

        max_drawdown = self._calculate_max_drawdown(equity_series)
        win_rate = self._calculate_win_rate()

        return {
            'initial_cash': self.initial_cash,
            'final_equity': round(final_equity, 2),
            'total_return': f'{total_return:.2%}',
            'sharpe_ratio': round(sharpe_ratio, 2),
            'max_drawdown': f'{max_drawdown:.2%}',
            'total_trades': len(self.trades),
            'win_rate': round(win_rate, 4)
        }

    def _calculate_max_drawdown(self, equity: pd.Series) -> float:
        """计算最大回撤"""
        if len(equity) == 0:
            return 0.0
        peak = equity.expanding(min_periods=1).max()
        drawdown = (equity - peak) / peak.replace(0, 1)
        return abs(float(drawdown.min()))

    def _calculate_win_rate(self) -> float:
        """计算胜率"""
        if len(self.trades) < 2:
            return 0.0
        wins = 0
        pairs = 0
        i = 0
        while i < len(self.trades) - 1:
            if self.trades[i]['type'] == 'buy' and self.trades[i + 1]['type'] == 'sell':
                pairs += 1
                if self.trades[i + 1]['price'] > self.trades[i]['price']:
                    wins += 1
                i += 2
            else:
                i += 1
        return wins / pairs if pairs > 0 else 0.0
