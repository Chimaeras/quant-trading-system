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
    newey_west_se,
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
    assert list(g.columns) == ["group", "mean_ret", "count", "n_groups"]
    assert len(g) <= 5
    assert g["count"].sum() > 0
    # 正常情形实际分组数应等于请求分组数
    assert int(g["n_groups"].iloc[0]) == len(g)


def test_group_returns_reports_dropped_bins():
    """因子大量重复时 qcut(duplicates='drop') 合并 bin，n_groups 应 < n_groups 请求值。"""
    factor = pd.Series([1.0] * 40 + [2.0] * 40)  # 只有两个唯一值 → 无法等频分 5 组
    fwd = pd.Series(np.linspace(-1.0, 1.0, 80))
    g = group_returns(factor, fwd, n_groups=5)
    # 实际分组数应 <= 2 < 5，且 n_groups 列如实反映
    assert len(g) <= 2
    assert int(g["n_groups"].iloc[0]) == len(g)


def test_rank_ic_nan_on_constant_factor():
    # 常量因子无区分度 → NaN（而非 0）
    fwd = forward_return(_close(50, seed=10), 5)
    factor = pd.Series([1.0] * len(fwd))
    assert np.isnan(rank_ic(factor, fwd))


def test_rank_ic_nan_on_constant_target():
    factor = _close(50, seed=11)
    fwd = pd.Series([0.0] * len(factor))
    assert np.isnan(rank_ic(factor, fwd))


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


def test_newey_west_se_matches_sample_mean_se():
    """iid 数据上 HAC 标准误（lag=0）应≈ 样本标准差/sqrt(n)——均值的 SE，而非标准差本身。

    回归 R17-P1：此前漏除 n，返回值偏大 sqrt(n) 倍，t 值被同倍低估。
    """
    rng = np.random.default_rng(42)
    v = pd.Series(rng.standard_normal(10_000))
    se = newey_west_se(v, max_lag=0)
    expected = v.std(ddof=0) / (len(v) ** 0.5)
    assert abs(se - expected) / expected < 0.01


def test_newey_west_se_positive_lag_reduces_vs_iid_only_for_persistent_series():
    """强正自相关序列的 HAC SE 应大于 iid 假设下的 SE（重叠 IC 场景）。"""
    rng = np.random.default_rng(7)
    n = 5_000
    eps = rng.standard_normal(n)
    ar = np.empty(n)
    ar[0] = eps[0]
    for i in range(1, n):
        ar[i] = 0.9 * ar[i - 1] + eps[i]
    v = pd.Series(ar)
    assert newey_west_se(v, max_lag=5) > newey_west_se(v, max_lag=0)
