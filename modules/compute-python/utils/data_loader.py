"""
数据加载工具
"""
import pandas as pd
from pathlib import Path
from typing import Optional, Union

# 大小写不敏感的列名映射：支持 Open/OPEN/open/开盘价 等常见变体
_COL_MAP_CI = {
    'open': 'open', '开盘价': 'open',
    'high': 'high', '最高价': 'high',
    'low': 'low', '最低价': 'low',
    'close': 'close', '收盘价': 'close',
    'volume': 'volume', '成交量': 'volume', 'vol': 'volume',
}

REQUIRED_OHLCV_COLUMNS = ('open', 'high', 'low', 'close', 'volume')


def load_ohlcv(path: Union[str, Path], symbol: Optional[str] = None) -> pd.DataFrame:
    """
    加载OHLCV数据
    支持 CSV 格式，需包含 open/high/low/close/volume 列（大小写不敏感）。
    """
    df = pd.read_csv(path, index_col=0, parse_dates=True)
    # 大小写不敏感标准化列名
    df.columns = [_COL_MAP_CI.get(str(c).strip().lower(), c) for c in df.columns]
    # 校验必填列，缺失时在调用点立即报错而非下游 KeyError
    missing = [c for c in REQUIRED_OHLCV_COLUMNS if c not in df.columns]
    if missing:
        raise ValueError(
            f"load_ohlcv: 缺少必填列 {missing}，"
            f"现有列 {list(df.columns)}（列名大小写不敏感映射后仍不匹配）"
        )
    return df
