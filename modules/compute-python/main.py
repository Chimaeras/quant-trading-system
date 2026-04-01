"""
量化引擎入口 - 支持回测和实盘
"""
import pandas as pd
import sys
from pathlib import Path

# 添加项目根目录
sys.path.insert(0, str(Path(__file__).parent))

from strategies import MACrossStrategy
from engines import BacktestEngine, DataEngine


def run_backtest_demo():
    """运行回测演示"""
    print("=" * 50)
    print("  量化引擎 - 回测演示")
    print("=" * 50)

    # 加载数据
    data_engine = DataEngine()
    data = data_engine.load_csv('000001')
    print(f"加载数据: {len(data)} 条")

    # 创建策略和引擎
    strategy = MACrossStrategy(short_period=5, long_period=20)
    engine = BacktestEngine(initial_cash=1000000)

    # 运行回测
    result = engine.run(strategy, data)
    print("\n回测结果:")
    for k, v in result.items():
        print(f"  {k}: {v}")

    return result


if __name__ == '__main__':
    run_backtest_demo()
