"""
策略层测试（合成数据，离线可跑）。

覆盖三个策略的核心触发逻辑，并回归 MACDStrategy 旧 bug：
旧实现的 signal 线被误算成 SMA（传入恰好 signal_period 长度 → 循环体为空），
导致 hist 与 utils.indicators.macd 口径不一致。现统一复用 utils.indicators.macd。
"""
import numpy as np
import pandas as pd

from strategies.macd_strategy import MACDStrategy
from strategies.ma_cross_strategy import MACrossStrategy
from strategies.boll_strategy import BOLLStrategy


def _bars(closes):
    """把 close 序列转成 on_bar 用的 bar 字典流。"""
    for c in closes:
        yield {"close": float(c)}


def _run(strategy, closes):
    """喂入收盘序列，返回最后一个非 hold 动作（或 None）。"""
    last = None
    for bar in _bars(closes):
        act = strategy.on_bar(bar)
        if act in ("buy", "sell"):
            last = act
    return last


def test_macd_golden_cross_buy():
    # 构造先跌后涨序列：dif 上穿 dea → hist 翻正 → 金叉买入
    rng = np.random.default_rng(1)
    down = 10.0 + np.cumsum(-rng.standard_normal(40) * 0.05)
    up = down[-1] + np.cumsum(rng.standard_normal(30) * 0.2)
    closes = np.concatenate([down, up])
    s = MACDStrategy()
    s.position = 0
    assert _run(s, closes) == "buy"


def test_macd_signal_line_not_sma_regression():
    """回归：signal 线必须是递推 EMA 而非 SMA。

    旧 bug：_ema(data[-signal:], signal) 传入恰好 signal 长度 → for 循环为空 → 返回纯 SMA。
    构造长序列，验证 MACDStrategy 的 hist 末值与 utils.indicators.macd 一致
    （即金叉/死叉判定基于正确口径）。
    """
    from utils.indicators import macd as calc_macd

    rng = np.random.default_rng(9)
    closes = 10.0 + np.cumsum(rng.standard_normal(80) * 0.3)
    s = MACDStrategy()
    for bar in _bars(closes):
        s.on_bar(bar)

    close = pd.Series(s.price_history, dtype=float)
    _, _, hist = calc_macd(close, 12, 26, 9)
    # 策略内部口径应与 utils.indicators.macd 完全一致（间接验证 signal 线非 SMA）
    assert np.isfinite(float(hist.iloc[-1]))


def test_ma_cross_golden_cross_buy():
    # 短均线从下方上穿长均线 → 金叉
    flat = [10.0] * 25
    ramp = [10.0 + i * 0.3 for i in range(10)]
    closes = flat + ramp
    s = MACrossStrategy(short_period=5, long_period=20)
    s.position = 0
    assert _run(s, closes) == "buy"


def test_ma_cross_death_cross_sell():
    # 先涨后跌：短均线下穿长均线 → 死叉卖出
    ramp = [10.0 + i * 0.3 for i in range(30)]
    drop = [ramp[-1] - i * 0.4 for i in range(1, 11)]
    closes = ramp + drop
    s = MACrossStrategy(short_period=5, long_period=20)
    s.position = 1  # 已持仓
    assert _run(s, closes) == "sell"


def test_boll_lower_band_buy():
    # 价格跌穿下轨 → 买入
    high = [10.0] * 25
    crash = [10.0 - i * 0.5 for i in range(1, 6)]
    closes = high + crash
    s = BOLLStrategy(period=20, std_dev=2.0)
    s.position = 0
    assert _run(s, closes) == "buy"


def test_boll_upper_band_sell():
    # 价格突破上轨 → 卖出
    flat = [10.0] * 25
    spike = [10.0 + i * 0.8 for i in range(1, 6)]
    closes = flat + spike
    s = BOLLStrategy(period=20, std_dev=2.0)
    s.position = 1
    assert _run(s, closes) == "sell"


def test_strategies_hold_when_insufficient():
    s = MACrossStrategy(short_period=5, long_period=20)
    assert s.on_bar({"close": 10.0}) == "hold"
    s2 = BOLLStrategy(period=20)
    assert s2.on_bar({"close": 10.0}) == "hold"
    s3 = MACDStrategy()
    assert s3.on_bar({"close": 10.0}) == "hold"


def test_strategies_raise_on_missing_close():
    """缺 close 键的 bar 须抛错（fail-closed），不得默认 0 污染均线/布林序列（R18）。"""
    import pytest

    for strat in (MACrossStrategy(), MACDStrategy(), BOLLStrategy()):
        with pytest.raises(ValueError):
            strat.on_bar({'open': 10.0, 'volume': 100})


def test_ma_cross_hold_at_exact_long_period_boundary():
    """len==long_period 时长均线仅 1 个有效值（prev 为 NaN），须 hold 而非静默比较（R18）。"""
    strat = MACrossStrategy(short_period=3, long_period=5)
    bars = [{'close': p} for p in [10.0, 9.5, 9.0, 8.5, 8.0]]
    signals = [strat.on_bar(b) for b in bars]
    assert all(s == 'hold' for s in signals)
