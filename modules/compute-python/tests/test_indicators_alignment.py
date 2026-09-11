"""
技术指标口径对拍测试：Python utils.indicators 与 Java TechnicalIndicators 同输入同输出。

Java 参考实现（TechnicalIndicators.java）：
- ema：alpha=2/(span+1)，种子 = 前 span 根 SMA
- macd：dif=EMA(fast)-EMA(slow)，dea=EMA(dif, signal)，柱线 macd=(dif-dea)*2
- boll：样本标准差（ddof=1）
"""
import numpy as np
import pandas as pd

from utils.indicators import bollinger_bands, ema, macd, rsi


def _java_ema(values, span):
    alpha = 2.0 / (span + 1)
    out = [0.0] * len(values)
    seed_len = min(span, len(values))
    out[0] = sum(values[:seed_len]) / seed_len
    for i in range(1, len(values)):
        out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
    return out


def _series(values):
    return pd.Series([float(v) for v in values])


def test_ema_matches_java_sma_seed():
    close = [10, 10.5, 10.2, 10.8, 11.0, 10.9, 11.2, 11.5, 11.3, 11.8]
    py = ema(_series(close), 3)
    expected = _java_ema(close, 3)
    assert np.allclose(py.to_numpy(), expected, atol=1e-9)


def test_macd_histogram_is_doubled_dif_minus_dea():
    close = [10 + i * 0.1 + np.sin(i) for i in range(60)]
    dif_line, dea_line, hist = macd(_series(close), 12, 26, 9)

    # 结构断言：柱线 = (dif - dea) * 2
    assert np.allclose(hist, (dif_line - dea_line) * 2, atol=1e-9)

    # Java 参考实现对拍
    java_dif = np.array(_java_ema(close, 12)) - np.array(_java_ema(close, 26))
    java_dea = np.array(_java_ema(java_dif.tolist(), 9))
    assert np.allclose(hist, (java_dif - java_dea) * 2, atol=1e-9)


def test_bollinger_uses_sample_std_dev():
    close = [10, 11, 12, 11, 13, 12, 10, 12, 13, 11,
             12, 13, 12, 11, 12, 13, 14, 13, 12, 11]
    upper, ma, lower = bollinger_bands(_series(close), 20, 2.0)

    window = close[-20:]
    exp_ma = sum(window) / 20
    var = sum((p - exp_ma) ** 2 for p in window) / 19  # ddof=1
    exp_std = var ** 0.5

    assert abs(ma.iloc[-1] - exp_ma) < 1e-9
    assert abs(upper.iloc[-1] - (exp_ma + 2 * exp_std)) < 1e-9
    assert abs(lower.iloc[-1] - (exp_ma - 2 * exp_std)) < 1e-9


def _java_rsi(close, period):
    """Java TechnicalIndicators.rsi 参考实现（Wilder 递推）。"""
    n = len(close)
    out = [50.0] * n
    if n <= period:
        return out
    avg_gain = 0.0
    avg_loss = 0.0
    for i in range(1, period + 1):
        d = close[i] - close[i - 1]
        if d > 0:
            avg_gain += d
        else:
            avg_loss += -d
    avg_gain /= period
    avg_loss /= period
    for i in range(period, n):
        if i > period:
            d = close[i] - close[i - 1]
            g = max(d, 0.0)
            l = max(-d, 0.0)
            avg_gain = (avg_gain * (period - 1) + g) / period
            avg_loss = (avg_loss * (period - 1) + l) / period
        if avg_gain < 1e-12 and avg_loss < 1e-12:
            out[i] = 50.0  # 完全无波动（横盘/停牌复牌），Java 首个分支
        elif avg_loss < 1e-12:
            out[i] = 100.0
        elif avg_gain < 1e-12:
            out[i] = 0.0
        else:
            out[i] = 100.0 - 100.0 / (1.0 + avg_gain / avg_loss)
    return out


def test_rsi_matches_java_wilder():
    rng = np.random.default_rng(7)
    close = (10.0 + np.cumsum(rng.standard_normal(80) * 0.3)).tolist()
    py = rsi(_series(close), 14)
    expected = _java_rsi(close, 14)
    assert np.allclose(py.to_numpy(), expected, atol=1e-9)


def test_rsi_strong_uptrend_is_100():
    # 连续上涨：每个 delta>0 → avg_loss=0 → Java 返回 100（旧 Python bug 返回 50）
    close = [10 + i * 0.5 for i in range(30)]
    py = rsi(_series(close), 14)
    expected = _java_rsi(close, 14)
    assert np.allclose(py.to_numpy(), expected, atol=1e-9)
    assert py.iloc[-1] == 100.0


def test_rsi_strong_downtrend_is_0():
    close = [30 - i * 0.5 for i in range(30)]
    py = rsi(_series(close), 14)
    expected = _java_rsi(close, 14)
    assert np.allclose(py.to_numpy(), expected, atol=1e-9)
    assert py.iloc[-1] == 0.0


def test_rsi_short_series_returns_50():
    close = [10, 11, 10.5, 10.8]
    py = rsi(_series(close), 14)
    assert np.allclose(py.to_numpy(), 50.0)


def test_rsi_flat_series_is_50_not_100():
    """回归：完全无波动（横盘/停牌复牌）时 avgGain≈avgLoss≈0，Java 首个分支判为中性 50，
    旧 Python 实现按「avgLoss≈0」落到 100，会把横盘误标为超买。
    """
    close = [12.0] * 40
    py = rsi(_series(close), 14)
    expected = _java_rsi(close, 14)
    assert np.allclose(py.to_numpy(), expected, atol=1e-9)
    assert py.iloc[-1] == 50.0
    # 停牌复牌：中间一段完全不动，末段才开始波动
    stalled = [12.0] * 20 + [12.0] * 10 + [13.0, 14.0, 15.0]
    py2 = rsi(_series(stalled), 14)
    expected2 = _java_rsi(stalled, 14)
    assert np.allclose(py2.to_numpy(), expected2, atol=1e-9)
    assert py2.iloc[28] == 50.0


def test_iir_first_order_seed_contract():
    """回归：一阶 IIR 的首项必须是 alpha*x[0] + (1-alpha)*seed。

    scipy lfilter 的状态是 z[-1] = -a1*y[-1] = (1-alpha)*seed，故 zi 要传
    (1-alpha)*seed；旧实现传 zi=[seed] 会让首项多出 alpha*seed，EMA/MACD/RSI 全体漂移。
    """
    from utils.indicators import _iir_first_order

    x = np.array([3.0, 1.0, 4.0, 1.0, 5.0, 9.0])
    alpha, seed = 0.3, 2.0
    out = _iir_first_order(x, alpha, seed)

    expected = [alpha * x[0] + (1 - alpha) * seed]
    for value in x[1:]:
        expected.append(alpha * value + (1 - alpha) * expected[-1])
    assert np.allclose(out, expected, atol=1e-12)
