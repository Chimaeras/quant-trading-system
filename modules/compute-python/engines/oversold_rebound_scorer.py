“””
超跌反弹机会筛选打分系统（Python 版本）

与后端 Java OversoldReboundScoringService 口径保持一致：
- 趋势维度: 40分（ret60+ret120+均线位置+周K阴线）
- 量能维度: 30分（量比+量价配合）
- 超跌维度: 30分（RSI+布林带+历史分位）
- 确认信号: 15分（底背离+量能确认+反转K线）
- 加权得分 = (trend/40)*100*0.35 + (volume/30)*100*0.25 + (oversold/30)*100*0.25 + (confirm/15)*100*0.15
“””

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Any, Optional, List

import numpy as np
import pandas as pd

from utils.indicators import bollinger_bands, rsi


@dataclass
class ScoreResult:
    trend_score_40: int
    volume_score_30: int
    oversold_score_30: int
    total_score: float
    weighted_score: float
    confirmation_score: int
    confirmation_has_signal: bool
    confirmation_summary: str
    detail: Dict[str, Any]


def score_oversold_rebound(df: pd.DataFrame) -> Optional[ScoreResult]:
    “””
    输入 df 需包含至少 260 条日线，且包含列：
    - date（可选，用于周K统计；若无则跳过周K分）
    - open, high, low, close, volume, amount（amount 用于确认信号）
    “””
    if df is None or len(df) < 260:
        return None

    close = df[“close”].astype(float).reset_index(drop=True)
    open_ = df[“open”].astype(float).reset_index(drop=True)
    high = df[“high”].astype(float).reset_index(drop=True) if “high” in df.columns else open_
    low = df[“low”].astype(float).reset_index(drop=True) if “low” in df.columns else open_
    volume = df[“volume”].astype(float).fillna(0).reset_index(drop=True)
    amount = df[“amount”].astype(float).fillna(0).reset_index(drop=True) if “amount” in df.columns else volume * close

    n = len(close)
    last_close = float(close.iloc[-1])

    ret60 = (last_close / float(close.iloc[-61]) - 1) * 100 if n >= 61 else 0.0
    ret120 = (last_close / float(close.iloc[-121]) - 1) * 100 if n >= 121 else 0.0
    ret20 = (last_close / float(close.iloc[-21]) - 1) * 100 if n >= 21 else 0.0

    ma20 = float(close.rolling(20).mean().iloc[-1])
    ma60 = float(close.rolling(60).mean().iloc[-1])
    ma120 = float(close.rolling(120).mean().iloc[-1])

    avg_amount20 = float(amount.rolling(20).mean().iloc[-1]) if n >= 20 else 0.0

    # 周K连续阴线（如果有 date）
    bear_weeks = 0
    if “date” in df.columns:
        d = pd.to_datetime(df[“date”])
        tmp = df.copy()
        tmp[“date”] = d
        tmp[“iso_year”] = tmp[“date”].dt.isocalendar().year.astype(int)
        tmp[“iso_week”] = tmp[“date”].dt.isocalendar().week.astype(int)
        g = tmp.groupby([“iso_year”, “iso_week”], sort=True)
        week_open = g[“open”].first()
        week_close = g[“close”].last()
        wk = pd.DataFrame({“open”: week_open, “close”: week_close}).reset_index(drop=True)
        for i in range(len(wk) - 1, -1, -1):
            if float(wk.loc[i, “close”]) < float(wk.loc[i, “open”]):
                bear_weeks += 1
            else:
                break

    v5 = float(volume.rolling(5).mean().iloc[-1])
    v30 = float(volume.rolling(30).mean().iloc[-1])
    v_ratio = (v5 / v30) if v30 > 0 else 0.0

    rsi14 = float(rsi(close, 14).iloc[-1])

    upper, mid, lower = bollinger_bands(close, 20, 2.0)
    boll_mid = float(mid.iloc[-1])
    std20 = float(close.rolling(20).std(ddof=1).iloc[-1])  # 样本标准差，与 Java 对齐
    boll_lower = float(lower.iloc[-1])
    boll_lower_extreme = boll_mid - 3.5 * std20

    win = close.iloc[max(0, n - 252):]
    min252 = float(win.min())
    max252 = float(win.max())
    percentile = 0.5 if (max252 - min252) <= 0 else (last_close - min252) / (max252 - min252)

    # --- 打分子函数 ---

    def score_ret60(x: float) -> int:
        if x <= -20:
            return 15
        if x <= -10:
            return 10
        if x <= -5:
            return 5
        return 0

    def score_ret120(x: float) -> int:
        if x <= -30:
            return 5
        if x <= -20:
            return 3
        if x <= -10:
            return 1
        return 0

    def score_bear_weeks(w: int) -> int:
        if w >= 5:
            return 10
        if w == 4:
            return 8
        if w == 3:
            return 5
        return 0

    # --- 趋势维度（40分）---
    trend = 0
    trend += score_ret60(ret60)
    trend += score_ret120(ret120)
    if last_close < ma20:
        trend += 3
    if last_close < ma60:
        trend += 4
    if last_close < ma120:
        trend += 3
    trend += score_bear_weeks(bear_weeks)

    # --- 量能维度（30分）---
    volume_score = 0
    if v_ratio > 0.8:
        volume_score += 15
    elif v_ratio >= 0.5:
        volume_score += 10

    # 量价配合：最近30日价格下跌时的量价结构
    start = float(close.iloc[-30])
    end = last_close
    v_first = float(volume.iloc[-30:-15].mean())
    v_second = float(volume.iloc[-15:].mean())
    v_last5 = float(volume.iloc[-5:].mean())
    v_prev5 = float(volume.iloc[-10:-5].mean())

    down = end < start
    if down and v_second > v_first * 1.05:
        volume_score += 10
        vp_type = “DOWN_VOL_EXPAND”
    elif down and (v_second < v_first * 0.95) and (v_last5 > v_prev5 * 1.05):
        volume_score += 5
        vp_type = “DOWN_VOL_SHRINK_THEN_WARM_EXPAND”
    else:
        vp_type = “DOWN_VOL_SHRINK”

    # --- 超跌维度（30分）---
    oversold = 0
    if rsi14 < 30:
        oversold += 10
    elif rsi14 < 40:
        oversold += 5

    if last_close < boll_lower_extreme:
        oversold += 10
    elif last_close < boll_lower:
        oversold += 8

    if percentile < 0.10:
        oversold += 10
    elif percentile < 0.20:
        oversold += 5
    elif percentile < 0.30:
        oversold += 2

    # --- 确认信号（15分）—— 与 Java ReboundConfirmationScorer 对齐 ---
    confirm_score, confirm_has, confirm_summary = _score_confirmation(close, open_, high, low, amount)

    # --- 统一加权得分 (0-100分制，与 Java 一致) ---
    weighted = (
        (trend / 40.0) * 100 * 0.35
        + (volume_score / 30.0) * 100 * 0.25
        + (oversold / 30.0) * 100 * 0.25
        + (confirm_score / 15.0) * 100 * 0.15
    )
    if not confirm_has:
        weighted = min(weighted, 70.0)

    detail = {
        “ret20_pct”: round(ret20, 2),
        “ret60_pct”: round(ret60, 2),
        “ret120_pct”: round(ret120, 2),
        “avg_amount20”: round(avg_amount20, 2),
        “ma20”: round(ma20, 2),
        “ma60”: round(ma60, 2),
        “ma120”: round(ma120, 2),
        “consecutive_bear_weeks”: int(bear_weeks),
        “vol_ratio_5_30”: round(v_ratio, 2),
        “volume_price_type”: vp_type,
        “rsi14”: round(rsi14, 2),
        “boll_mid”: round(boll_mid, 2),
        “boll_lower”: round(boll_lower, 2),
        “boll_lower_extreme”: round(boll_lower_extreme, 2),
        “percentile_1y”: round(float(percentile), 4),
        “confirmation_score”: confirm_score,
        “confirmation_summary”: confirm_summary,
        “confirmation_has_signal”: confirm_has,
    }

    return ScoreResult(
        trend_score_40=int(trend),
        volume_score_30=int(volume_score),
        oversold_score_30=int(oversold),
        total_score=round(float(weighted), 2),
        weighted_score=round(float(weighted), 2),
        confirmation_score=confirm_score,
        confirmation_has_signal=confirm_has,
        confirmation_summary=confirm_summary,
        detail=detail,
    )


def _score_confirmation(
    close: pd.Series, open_: pd.Series, high: pd.Series, low: pd.Series, amount: pd.Series
) -> tuple:
    “””确认信号评分（与 Java ReboundConfirmationScorer 一致）”””
    n = len(close)
    if n < 35:
        return 0, False, “数据不足”

    pts = 0
    parts = []

    if _macd_bottom_divergence(close, n):
        pts += 6
        parts.append(“MACD底背离”)
    if _volume_confirmation(amount, n):
        pts += 5
        parts.append(“量能确认”)
    if _hammer_or_morning_star(open_, high, low, close, n):
        pts += 4
        parts.append(“反转K线”)

    pts = min(pts, 15)
    has = pts >= 5
    summary = “+”.join(parts) if parts else “无确认信号”
    return pts, has, summary


def _macd_bottom_divergence(close: pd.Series, n: int) -> bool:
    “””MACD底背离检测”””
    if n < 35:
        return False
    close_arr = close.to_numpy()
    ema_fast = _ema(close_arr, 12)
    ema_slow = _ema(close_arr, 26)
    dif = ema_fast - ema_slow
    dea = _ema(dif, 9)
    macd_hist = (dif - dea) * 2

    lookback = min(20, n - 5)
    # 找最近两个低点的位置
    segment = close_arr[n - lookback:n - 3]
    low1 = float(“inf”)
    low2 = float(“inf”)
    idx1 = -1
    idx2 = -1
    for i, val in enumerate(segment):
        idx = n - lookback + i
        if val < low1:
            low2, idx2 = low1, idx1
            low1, idx1 = val, idx
        elif val < low2:
            low2, idx2 = val, idx

    if idx1 < 0 or idx2 < 0 or idx1 == idx2:
        return False
    # 价格新低但MACD柱状线未创新低
    price_lower = close_arr[n - 1] <= low1 * 1.01
    return price_lower and macd_hist[idx1] > macd_hist[idx2]


def _volume_confirmation(amount: pd.Series, n: int) -> bool:
    “””量能确认：近3日均量 > 前10日均量的1.2倍”””
    if n < 13:
        return False
    recent3 = float(amount.iloc[n - 3:].mean())
    prev10 = float(amount.iloc[n - 13:n - 4].mean())
    return prev10 > 0 and recent3 > prev10 * 1.2


def _hammer_or_morning_star(
    open_: pd.Series, high: pd.Series, low: pd.Series, close: pd.Series, n: int
) -> bool:
    “””锤子线或启明星形态检测”””
    if n < 3:
        return False

    o = float(open_.iloc[-1])
    h = float(high.iloc[-1])
    l = float(low.iloc[-1])
    c = float(close.iloc[-1])
    body = abs(c - o)
    range_val = h - l
    if range_val < 1e-9:
        return False

    lower_shadow = min(o, c) - l
    hammer = lower_shadow >= body * 2 and body / range_val < 0.35

    o2 = float(open_.iloc[-2])
    c2 = float(close.iloc[-2])
    o3 = float(open_.iloc[-3])
    c3 = float(close.iloc[-3])
    morning_star = (
        c3 < o3
        and abs(c2 - o2) / max(1e-9, c2) < 0.01
        and c > o
        and c > (o3 + c3) / 2
    )

    return hammer or morning_star


def _ema(values: np.ndarray, span: int) -> np.ndarray:
    “””EMA 计算，使用前 span 根 SMA 作为种子值（与 Java 对齐）”””
    alpha = 2.0 / (span + 1)
    out = np.zeros_like(values)
    seed_len = min(span, len(values))
    out[0] = float(np.mean(values[:seed_len]))
    for i in range(1, len(values)):
        out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
    return out

