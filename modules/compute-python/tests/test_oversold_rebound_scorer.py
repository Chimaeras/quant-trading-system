"""
超跌反弹打分器与可交易性钩子测试（合成数据，离线可跑）。

注意：score_oversold_rebound 是 Point-in-time 纯函数，仅对最后一根 bar 打分；
本测试用确定性合成数据验证：长度守卫、分数区间、可复现性，以及 tradability 钩子。
该分数是「超跌反弹机会强度」，非已校准上涨概率——测试不假设其概率含义。
"""
import numpy as np
import pandas as pd

from engines.oversold_rebound_scorer import score_oversold_rebound
from engines.oversold_rebound_scorer import _score_confirmation, _volume_confirmation
from engines.tradability import tradability_check


def _synthetic_df(n: int = 300, seed: int = 0) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    # 先涨后跌，制造一个超跌尾部以触发打分维度（仅当 n>200 时叠加衰减尾部）
    base = 20.0 + np.cumsum(rng.standard_normal(n) * 0.1)
    if n > 200:
        base[200:] = base[200] * np.exp(-np.arange(n - 200) * 0.004)
    dates = pd.date_range("2024-01-01", periods=n, freq="D")
    return pd.DataFrame({
        "date": dates,
        "open": base,
        "high": base * (1 + np.abs(rng.standard_normal(n)) * 0.01),
        "low": base * (1 - np.abs(rng.standard_normal(n)) * 0.01),
        "close": base,
        "volume": (1_000_000 + rng.standard_normal(n) * 100_000).clip(min=100_000),
        "amount": base * 1_000_000,
    })


def test_score_returns_none_when_insufficient_length():
    df = _synthetic_df(n=100, seed=1)
    assert score_oversold_rebound(df) is None


def test_score_range_and_deterministic():
    df = _synthetic_df(n=300, seed=2)
    r1 = score_oversold_rebound(df)
    r2 = score_oversold_rebound(df)
    assert r1 is not None
    assert 0.0 <= r1.weighted_score <= 100.0
    # 纯函数可复现
    assert r1.weighted_score == r2.weighted_score
    assert r1.total_score == r2.total_score


def test_score_detail_fields_present():
    df = _synthetic_df(n=300, seed=3)
    r = score_oversold_rebound(df)
    assert r is not None
    for k in ("rsi14", "ret60_pct", "percentile_1y", "vol_ratio_5_30"):
        assert k in r.detail


def test_tradability_basic():
    df = _synthetic_df(n=300, seed=4)
    ok, reason = tradability_check(df)
    assert ok and reason == "可交易"


def test_tradability_blocks_zero_volume():
    df = _synthetic_df(n=300, seed=5)
    df.loc[df.index[-1], "volume"] = 0
    ok, reason = tradability_check(df)
    assert not ok and "停牌" in reason


def test_tradability_blocks_limit_up():
    df = _synthetic_df(n=300, seed=6)
    df["limit_up"] = False
    df.loc[df.index[-1], "limit_up"] = True
    ok, reason = tradability_check(df)
    assert not ok and "涨停" in reason


def test_tradability_empty():
    ok, reason = tradability_check(pd.DataFrame())
    assert not ok and "无数据" in reason


def test_tradability_nan_limit_flags_are_not_blocking():
    """回归：标志列以 NaN/NA 表示「该行不适用」时不得被当成锁定。

    bool(float('nan')) 为 True、bool(pd.NA) 抛 TypeError，旧实现会把整段行情误杀成
    「一字涨停不可买入」。只有显式为真的标志才应拦截。
    """
    for flag in (np.nan, pd.NA, None):
        df = _synthetic_df(n=300, seed=7)
        df["limit_up"] = flag
        df["limit_down"] = flag
        ok, reason = tradability_check(df)
        assert ok and reason == "可交易"

    df = _synthetic_df(n=300, seed=8)
    df["limit_up"] = [pd.NA] * (len(df) - 1) + [True]
    ok, reason = tradability_check(df)
    assert not ok and "涨停" in reason


def _shape_last_30(df: pd.DataFrame, first_half: float, second_half: float, last5: float, prev5: float) -> pd.DataFrame:
    """把最后 30 根 bar 的价格/量能改写成指定形状。

    close 前 15 根=10、后 15 根=9 → end < start（下跌）；volume 按 30/15/5 三段给定，
    用于精确命中 volumePriceType 的放量 / 中段 / 缩量分支。
    """
    df = df.copy()
    df.iloc[-30:-15, df.columns.get_loc("close")] = 10.0
    df.iloc[-15:, df.columns.get_loc("close")] = 9.0
    df.iloc[-30:-15, df.columns.get_loc("volume")] = first_half
    df.iloc[-15:, df.columns.get_loc("volume")] = second_half
    df.iloc[-10:-5, df.columns.get_loc("volume")] = prev5
    df.iloc[-5:, df.columns.get_loc("volume")] = last5
    return df


def test_volume_price_type_mid_band_matches_java():
    """回归：下跌且量能处在中段（既未放量也未缩量）时，Java 归为
    DOWN_VOL_SHRINK_THEN_WARM_EXPAND(+5)。Python 旧实现落到 DOWN_VOL_SHRINK(0 分)，
    与 Java 差 5 分，会污染跨端打分对拍。
    """
    df = _shape_last_30(_synthetic_df(n=300, seed=9), 1_000_000, 1_000_000, 1_000_000, 1_000_000)
    r = score_oversold_rebound(df)
    assert r is not None
    assert r.detail["volume_price_type"] == "DOWN_VOL_SHRINK_THEN_WARM_EXPAND"


def test_volume_price_type_shrink_without_warm_expand():
    """下跌 + 后段缩量但末 5 日未回暖 → DOWN_VOL_SHRINK（不加 5 分）。"""
    df = _shape_last_30(_synthetic_df(n=300, seed=10), 1_000_000, 900_000, 900_000, 900_000)
    r = score_oversold_rebound(df)
    assert r is not None
    assert r.detail["volume_price_type"] == "DOWN_VOL_SHRINK"


def test_volume_price_type_expand_when_second_half_heavier():
    """下跌 + 后段放量 >5% → DOWN_VOL_EXPAND（+10 分）。"""
    df = _shape_last_30(_synthetic_df(n=300, seed=11), 1_000_000, 1_200_000, 1_200_000, 1_200_000)
    r = score_oversold_rebound(df)
    assert r is not None
    assert r.detail["volume_price_type"] == "DOWN_VOL_EXPAND"


def test_volume_confirmation_window_pinned_to_java():
    """回归：prev10 必须为 n-13..n-4 含端（10 值），与 Java avg(n-13,n-4) 对齐。

    构造：索引 7..15=100，索引 16=1000，索引 17..19=200（n=20）。
    - 正确窗口（含索引16）：prev10=(9*100+1000)/10=190 → recent3=200 不 > 190*1.2=228 → False
    - 旧 bug 窗口（不含索引16，9 值）：prev10=100 → 200>120 → True（错误）
    """
    n = 20
    amt = pd.Series([100.0] * n)
    amt.iloc[7:16] = 100.0
    amt.iloc[16] = 1000.0
    amt.iloc[17:20] = 200.0
    assert _volume_confirmation(amt, n) is False


def test_volume_confirmation_true_when_recent_dominates():
    n = 20
    amt = pd.Series([100.0] * n)
    amt.iloc[17:20] = 500.0  # recent3=500 > prev10=100*1.2=120 → True
    assert _volume_confirmation(amt, n) is True


def test_score_confirmation_insufficient_length():
    close = pd.Series([10.0] * 30)
    open_ = pd.Series([10.0] * 30)
    high = pd.Series([10.5] * 30)
    low = pd.Series([9.5] * 30)
    amount = pd.Series([1e6] * 30)
    pts, has, summary = _score_confirmation(close, open_, high, low, amount)
    assert pts == 0 and has is False and summary == "数据不足"

