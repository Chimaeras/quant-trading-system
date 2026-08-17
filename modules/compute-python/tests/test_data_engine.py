"""
DataEngine.load_csv 校验测试（R19）：缺列/非数值/非日期索引 fail-closed，乱序自动排序。
"""
import numpy as np
import pandas as pd
import pytest

from engines.data_engine import DataEngine


def _write_csv(tmp_path, df, name="600000.csv"):
    p = tmp_path / name
    df.to_csv(p)
    return p


def _good_df(n=20):
    rng = np.random.default_rng(0)
    dates = pd.date_range("2025-01-01", periods=n, freq="D")
    close = 10 + np.cumsum(rng.standard_normal(n) * 0.1)
    return pd.DataFrame({
        "open": close, "high": close + 0.5, "low": close - 0.5,
        "close": close, "volume": np.full(n, 1000),
    }, index=dates)


def test_load_csv_passes_and_sorts_unsorted(tmp_path):
    df = _good_df(10)
    shuffled = df.iloc[::-1]  # 时间倒序
    p = _write_csv(tmp_path, shuffled)
    out = DataEngine(data_dir=str(tmp_path)).load_csv("600000", str(p))
    assert out.index.is_monotonic_increasing
    assert list(out.columns) == ["open", "high", "low", "close", "volume"]


def test_load_csv_rejects_missing_columns(tmp_path):
    df = _good_df(5).drop(columns=["volume"])
    p = _write_csv(tmp_path, df)
    with pytest.raises(ValueError, match="缺少必需列"):
        DataEngine(data_dir=str(tmp_path)).load_csv("600000", str(p))


def test_load_csv_rejects_non_numeric_prices(tmp_path):
    df = _good_df(5)
    df.iloc[2, df.columns.get_loc("close")] = "1,234.5"  # 千分位脏字符串
    p = _write_csv(tmp_path, df)
    with pytest.raises(ValueError, match="非数值"):
        DataEngine(data_dir=str(tmp_path)).load_csv("600000", str(p))


def test_load_csv_rejects_non_date_index(tmp_path):
    p = tmp_path / "600000.csv"
    pd.DataFrame({
        "note": ["x"],  # 首列非日期
        "open": [1.0], "high": [1.0], "low": [1.0], "close": [1.0], "volume": [100],
    }).to_csv(p, index=False)
    # index_col=0 会把 note 当索引 → 非日期索引被拒
    with pytest.raises(ValueError, match="日期索引"):
        DataEngine(data_dir=str(tmp_path)).load_csv("600000", str(p))
