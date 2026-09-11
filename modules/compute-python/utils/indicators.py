"""
技术指标计算（口径与 Java TechnicalIndicators 对齐）

性能：ema/rsi 的一阶 IIR 递推优先用 scipy.signal.lfilter 向量化；
scipy 不可用时回退到纯 Python 循环（数值口径完全一致，仅较慢）。
未引入硬依赖：scipy 为可选加速，缺失时自动降级。
"""
import numpy as np
import pandas as pd
from typing import Optional

try:
    from scipy.signal import lfilter as _lfilter
    _HAS_SCIPY = True
except ImportError:  # scipy 非必需，缺失时降级为纯 Python 递推
    _HAS_SCIPY = False


def _iir_first_order(x: np.ndarray, alpha: float, seed: float) -> np.ndarray:
    """一阶 IIR 递推：y[n] = alpha*x[n] + (1-alpha)*y[n-1]，初始 y[-1]=seed。

    返回与 x 等长的数组。有 scipy 时用 lfilter 一次性向量化；
    否则纯 Python 循环（与原实现一致，保证数值口径相同）。
    """
    n = len(x)
    out = np.empty(n, dtype=float)
    if n == 0:
        return out
    if _HAS_SCIPY:
        # lfilter: y[n] = b0*x[n] + z[n-1]，状态更新 z[n] = -a1*y[n]。
        # 初值 z[-1] = -a1*seed = (1-alpha)*seed，故 zi 必须是 (1-alpha)*seed，
        # 才能得到 y[0] = alpha*x[0] + (1-alpha)*seed，与下方回退口径一致。
        # （若写成 zi=[seed]，首项会多出 alpha*seed，EMA/MACD/RSI 全体漂移。）
        out, _ = _lfilter([alpha], [1.0, -(1.0 - alpha)], x, zi=[(1.0 - alpha) * seed])
        return out
    one_minus = 1.0 - alpha
    out[0] = alpha * x[0] + one_minus * seed
    for i in range(1, n):
        out[i] = alpha * x[i] + one_minus * out[i - 1]
    return out


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
    out = np.empty(n, dtype=float)
    out[0] = seed
    if n > 1:
        # 从 i=1 起递推，初始状态 = seed（out[0]）
        out[1:] = _iir_first_order(vals[1:], alpha, seed)
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
    - 边界：avgGain≈0 且 avgLoss≈0（横盘/停牌复牌，完全无波动）→ 50（中性，与 Java
      TechnicalIndicators.rsi 的首个分支一致，不应判为超买）；avgLoss≈0（<1e-12）→ 100；
      avgGain≈0（<1e-12）→ 0；否则 100-100/(1+avgGain/avgLoss)。

    实现：avgGain/avgLoss 的 Wilder 递推是一阶 IIR（c=(period-1)/period, d=1/period），
    用 _iir_first_order 向量化后，三分支用 np.where 组装（loss 分支最后应用以保持优先级）。
    无 scipy 时 _iir_first_order 自动降级为循环，数值口径不变。
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
    avg_gain0 = float(np.sum(gain[:period]) / period)
    avg_loss0 = float(np.sum(loss[:period]) / period)

    # Wilder 递推系数：avg = c*prev + d*cur，cur = gain[i-1]
    c = (period - 1) / period
    d = 1.0 / period

    # 递推段：i = period+1 .. n-1，输入序列 gain[period .. n-2]（长度 m = n-1-period）
    m = n - 1 - period
    if m > 0:
        seq_gain = gain[period:n - 1]
        seq_loss = loss[period:n - 1]
        ag = _iir_first_order(seq_gain, d, avg_gain0)  # ag[k] = avg_gain @ i=period+1+k
        al = _iir_first_order(seq_loss, d, avg_loss0)
        tail = _rsi_branch(ag, al)  # out[period+1 .. n-1]
        out[period + 1:] = tail

    # index=period 处用 SMA 初值（递推尚未发生）
    out[period] = _rsi_branch(np.array([avg_gain0]), np.array([avg_loss0]))[0]

    return pd.Series(out, index=series.index)


def _rsi_branch(avg_gain: np.ndarray, avg_loss: np.ndarray) -> np.ndarray:
    """RSI 三分支：avgGain≈0 且 avgLoss≈0 → 50（中性，同 Java 首个分支）；
    avgLoss<1e-12 → 100；avgGain<1e-12 → 0；否则 Wilder 公式。
    """
    with np.errstate(divide="ignore", invalid="ignore"):
        default = 100.0 - 100.0 / (1.0 + avg_gain / avg_loss)
    res = np.where(avg_gain < 1e-12, 0.0, default)
    res = np.where(avg_loss < 1e-12, 100.0, res)  # loss 优先
    # 两者同时≈0（横盘/停牌复牌）→ 50 中性，最后应用以覆盖上面两个分支，与 Java 一致
    res = np.where((avg_gain < 1e-12) & (avg_loss < 1e-12), 50.0, res)
    return res
