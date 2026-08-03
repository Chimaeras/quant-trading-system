"""
研究工具测试：前瞻标签、Rank IC、分组收益、Brier、ECE。
全部使用确定性合成数据，离线可跑通，不依赖网络/数据库。
"""
import numpy as np
import pandas as pd

from research.evaluation import (
    brier_score,
    expected_calibration_error,
    group_returns,
    rank_ic,
)
from research.labels import forward_return, label_up


def _close(n: int = 60, seed: int = 0) -> pd.Series:
    rng = np.random.default_rng(seed)
    return pd.Series(10.0 + np.cumsum(rng.standard_normal(n) * 0.2))


def test_forward_return_tail_nan():
    close = _close(20, seed=1)
    fwd = forward_return(close, 3)
    assert fwd.isna().sum() == 3
    # label[i] = close[i+3]/close[i] - 1
    assert abs(fwd.iloc[5] - (close.iloc[8] / close.iloc[5] - 1.0) * 100.0) < 1e-9


def test_label_up_tail_nan_and_values():
    close = _close(20, seed=2)
    up = label_up(close, 2, threshold=0.0)
    assert up.isna().sum() == 2
    # 非NaN 标签只能是 0/1
    vals = set(up.dropna().unique())
    assert vals.issubset({0.0, 1.0})


def test_rank_ic_perfect_positive():
    close = _close(50, seed=3)
    fwd = forward_return(close, 5)
    ic = rank_ic(fwd, fwd)
    assert abs(ic - 1.0) < 1e-9


def test_rank_ic_perfect_negative():
    close = _close(50, seed=4)
    fwd = forward_return(close, 5)
    ic = rank_ic(-fwd, fwd)
    assert abs(ic + 1.0) < 1e-9


def test_group_returns_shape():
    close = _close(100, seed=5)
    factor = close.pct_change(5)
    fwd = forward_return(close, 5)
    g = group_returns(factor, fwd, n_groups=5)
    assert list(g.columns) == ["group", "mean_ret", "count"]
    assert len(g) <= 5
    assert g["count"].sum() > 0


def test_brier_score_known_value():
    # 预测 [1,0,1,0] 实际 [1,0,0,1] → 误差平方 [0,0,1,1] → 0.5
    prob = pd.Series([1.0, 0.0, 1.0, 0.0])
    actual = pd.Series([1.0, 0.0, 0.0, 1.0])
    assert abs(brier_score(prob, actual) - 0.5) < 1e-9


def test_brier_score_perfect_is_zero():
    prob = pd.Series([0.2, 0.7, 0.9])
    actual = pd.Series([0.0, 1.0, 1.0])  # 按阈值 0.5 与概率一致
    # 完美分类但概率非 0/1 → Brier > 0
    assert brier_score(prob, actual) > 0.0
    # 概率=实际（0/1）→ Brier=0
    assert abs(brier_score(actual, actual) - 0.0) < 1e-9


def test_ece_zero_when_perfectly_calibrated():
    # 预测概率=实际（0/1）→ 每箱 acc=prob → ECE=0
    prob = pd.Series([0.0, 1.0, 0.0, 1.0, 1.0])
    actual = pd.Series([0.0, 1.0, 0.0, 1.0, 1.0])
    assert abs(expected_calibration_error(prob, actual, n_bins=5) - 0.0) < 1e-9


def test_ece_positive_when_miscalibrated():
    prob = pd.Series([0.9, 0.9, 0.9, 0.9])
    actual = pd.Series([0.0, 0.0, 0.0, 1.0])  # 实际频率 0.25，预测 0.9 → 偏差大
    assert expected_calibration_error(prob, actual, n_bins=4) > 0.3
