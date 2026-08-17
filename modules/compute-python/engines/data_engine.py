"""
数据引擎 - 行情数据采集与加载

正式路径禁止静默造数：CSV 缺失时抛 FileNotFoundError。
仅当显式 demo=True / CLI --demo 时才生成样本数据，且调用方应给产物打 DEMO 水印。
"""
import pandas as pd
from typing import Optional
from pathlib import Path


class DataEngine:
    """数据引擎 - 支持本地数据和显式 demo 样本。"""

    def __init__(self, data_dir: Optional[str] = None):
        self.data_dir = Path(data_dir) if data_dir else Path(__file__).parent.parent / 'data'

    def load_csv(
        self,
        symbol: str,
        filepath: Optional[str] = None,
        *,
        demo: bool = False,
    ) -> pd.DataFrame:
        """从 CSV 加载历史数据。

        Args:
            symbol: 标的代码（用于默认路径与 demo 样本）。
            filepath: 可选显式 CSV 路径。
            demo: 仅当 True 时，文件缺失可生成样本数据；默认 False 则抛错。

        Raises:
            FileNotFoundError: 非 demo 模式下 CSV 不存在。
        """
        path = Path(filepath) if filepath else self.data_dir / f"{symbol}.csv"
        if not path.exists():
            if demo:
                return self._generate_sample_data(symbol)
            raise FileNotFoundError(
                f"行情 CSV 不存在: {path.resolve()}。"
                f"正式 research 路径禁止静默造数；若仅做烟雾测试请传 demo=True 或 CLI --demo。"
            )
        df = pd.read_csv(path, index_col=0, parse_dates=True)
        return self._validate(df, path)

    @staticmethod
    def _validate(df: pd.DataFrame, path: Path) -> pd.DataFrame:
        """加载后校验：缺列 / 价格列非数值 / 索引非日期 / 未排序。

        fail-closed：坏数据在入口抛带文件路径的明确错误，
        而非让 KeyError/NaN 在下游打分器里以误导性结果显现。
        """
        required = ['open', 'high', 'low', 'close', 'volume']
        missing = [c for c in required if c not in df.columns]
        if missing:
            raise ValueError(f"{path}: 缺少必需列 {missing}，实际列 {list(df.columns)}")

        for c in ('open', 'high', 'low', 'close'):
            coerced = pd.to_numeric(df[c], errors='coerce')
            bad = coerced.isna() & df[c].notna()
            if bad.any():
                raise ValueError(
                    f"{path}: 列 '{c}' 含非数值（如千分位/脏字符串），"
                    f"首个坏行索引: {df.index[bad.argmax()]}"
                )
            df[c] = coerced

        if not isinstance(df.index, pd.DatetimeIndex):
            raise ValueError(
                f"{path}: 首列不是可解析的日期索引（得到 {type(df.index).__name__}），"
                f"无法保证时间序，下游滚动窗口/前瞻标签将失真"
            )

        if not df.index.is_monotonic_increasing:
            df = df.sort_index()
        return df

    def _generate_sample_data(self, symbol: str, days: int = 300) -> pd.DataFrame:
        """生成模拟 K 线（仅 demo 模式）。

        默认 days=300，使样本长度满足 score_oversold_rebound 的 260 条下限。
        使用 np.random.default_rng（确定性、不污染全局随机状态）。
        """
        import numpy as np
        from datetime import datetime

        rng = np.random.default_rng(42)
        base_price = 12.5 if symbol != '600519' else 1750.0
        dates = pd.date_range(end=datetime.now(), periods=days, freq='D')
        returns = rng.standard_normal(days) * 0.02
        closes = base_price * np.cumprod(1 + returns)
        opens = np.roll(closes, 1)
        opens[0] = base_price
        highs = np.maximum(opens, closes) * (1 + np.abs(rng.standard_normal(days) * 0.01))
        lows = np.minimum(opens, closes) * (1 - np.abs(rng.standard_normal(days) * 0.01))
        volumes = (1000000 + rng.standard_normal(days) * 200000).clip(min=100000).astype(int)

        return pd.DataFrame({
            'open': opens,
            'high': highs,
            'low': lows,
            'close': closes,
            'volume': volumes
        }, index=dates)
