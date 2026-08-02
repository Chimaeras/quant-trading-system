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
    """RSI 相对强弱指标，使用标准 Wilder 指数平滑。
    首个窗口使用 SMA 初始化，之后用 Wilder 递推。
    """
    delta = series.diff()
    gain = delta.clip(lower=0)
    loss = (-delta).clip(lower=0)

    # 首个窗口用 SMA 初始化
    avg_gain = gain.rolling(window=period).mean()
    avg_loss = loss.rolling(window=period).mean()

    # Wilder 指数平滑递推: avg = (prev * (period-1) + current) / period
    alpha = 1.0 / period
    for i in range(period + 1, len(series)):
        avg_gain.iloc[i] = avg_gain.iloc[i - 1] * (1 - alpha) + gain.iloc[i] * alpha
        avg_loss.iloc[i] = avg_loss.iloc[i - 1] * (1 - alpha) + loss.iloc[i] * alpha

    rs = avg_gain / avg_loss.replace(0, float("nan"))
    rsi_val = 100.0 - 100.0 / (1.0 + rs)
    return rsi_val.fillna(50).clip(0, 100)
