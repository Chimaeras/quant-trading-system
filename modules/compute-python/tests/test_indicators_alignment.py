"""
技术指标口径对拍测试：Python utils.indicators 与 Java TechnicalIndicators 同输入同输出。

Java 参考实现（TechnicalIndicators.java）：
- ema：alpha=2/(span+1)，种子 = 前 span 根 SMA
- macd：dif=EMA(fast)-EMA(slow)，dea=EMA(dif, signal)，柱线 macd=(dif-dea)*2
- boll：样本标准差（ddof=1）
"""
import numpy as np
import pandas as pd

from utils.indicators import bollinger_bands, ema, macd


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
