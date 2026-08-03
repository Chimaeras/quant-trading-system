"""
技术指标计算（口径与 Java TechnicalIndicators 对齐）
"""
import numpy as np
import pandas as pd
from typing import Optional


def sma(series: pd.Series, period: int) -> pd.Series:
    """简单移动平均"""
    return series.rolling(window=period).mean()


def ema(series: pd.Series, period: int) -> pd.Series:
    """指数移动平均（与 Java TechnicalIndicators.ema 口径一致）：
    用前 span 根 SMA 作为种子值，之后按 alpha=2/(span+1) 递推。
    注意：pandas ewm(adjust=False) 的种子是首个值而非 SMA，与 Java 不一致，故不用 ewm。
    """
    alpha = 2.0 / (period + 1)
    vals = series.to_numpy(dtype=float)
    n = len(vals)
    if n == 0:
        return pd.Series(dtype=float)
    seed_len = min(period, n)
    seed = float(np.sum(vals[:seed_len]) / seed_len) if seed_len > 0 else float("nan")
    out = np.empty(n)
    out[0] = seed
    for i in range(1, n):
        out[i] = alpha * vals[i] + (1 - alpha) * out[i - 1]
    return pd.Series(out, index=series.index)


def bollinger_bands(series: pd.Series, period: int = 20, std_dev: float = 2.0) -> tuple:
    """布林带（使用样本标准差 ddof=1，与 Java 对齐）"""
    ma = sma(series, period)
    std = series.rolling(window=period).std(ddof=1)
    upper = ma + std_dev * std
    lower = ma - std_dev * std
    return upper, ma, lower


def macd(series: pd.Series, fast: int = 12, slow: int = 26, signal: int = 9) -> tuple:
    """MACD（与 Java TechnicalIndicators.macd 口径一致）：
    dif=EMA(fast)-EMA(slow)；dea=EMA(dif, signal)；柱线 macd=(dif-dea)*2。
    """
    ema_fast = ema(series, fast)
    ema_slow = ema(series, slow)
    macd_line = ema_fast - ema_slow
    signal_line = ema(macd_line, signal)
    histogram = (macd_line - signal_line) * 2
    return macd_line, signal_line, histogram


def rsi(series: pd.Series, period: int = 14) -> pd.Series:
    """RSI 相对强弱指标，使用标准 Wilder 指数平滑（与 Java TechnicalIndicators.rsi 对齐）。

    口径：
    - 前置不足 period 的位置返回 50（与 Java `Arrays.fill(out, 50.0)` 一致）。
    - 首个 RSI（index=period）用前 period 个 delta 的 SMA 初始化 avgGain/avgLoss。
    - 之后用 Wilder 递推：avg = (prev*(period-1) + cur) / period。
    - 边界：avgLoss≈0（<1e-12）→ 100；avgGain≈0（<1e-12）→ 0；否则 100-100/(1+avgGain/avgLoss)。

    历史 bug：旧实现用 `avg_loss.replace(0,nan)` 导致 loss 全 0 的强上涨窗口被 fillna(50)，
    与 Java 返回 100 不一致；已改为显式三分支对齐。
    """
    vals = series.to_numpy(dtype=float)
    n = len(vals)
    out = np.full(n, 50.0)
    if n <= period:
        return pd.Series(out, index=series.index)

    delta = np.diff(vals)  # delta[i] = vals[i+1] - vals[i], 长度 n-1
    gain = np.where(delta > 0, delta, 0.0)
    loss = np.where(delta < 0, -delta, 0.0)

    # SMA 初始化：用前 period 个 delta（即 delta[0..period-1]，对应 close[1..period]）
    avg_gain = float(np.sum(gain[:period]) / period)
    avg_loss = float(np.sum(loss[:period]) / period)

    # i 为 close 的索引；delta 索引 = i-1
    for i in range(period, n):
        if i > period:
            g = gain[i - 1]
            l = loss[i - 1]
            avg_gain = (avg_gain * (period - 1) + g) / period
            avg_loss = (avg_loss * (period - 1) + l) / period
        if avg_loss < 1e-12:
            out[i] = 100.0
        elif avg_gain < 1e-12:
            out[i] = 0.0
        else:
            out[i] = 100.0 - 100.0 / (1.0 + avg_gain / avg_loss)

    return pd.Series(out, index=series.index)
