"""
数据加载工具
"""
import pandas as pd
from pathlib import Path
from typing import Optional, Union


def load_ohlcv(path: Union[str, Path], symbol: Optional[str] = None) -> pd.DataFrame:
    """
    加载OHLCV数据
    支持 CSV 格式，需包含 open/high/low/close/volume 列
    """
    df = pd.read_csv(path, index_col=0, parse_dates=True)
    # 标准化列名
    col_map = {
        'Open': 'open', 'High': 'high', 'Low': 'low',
        'Close': 'close', 'Volume': 'volume'
    }
    df.columns = [col_map.get(c, c) for c in df.columns]
    return df
