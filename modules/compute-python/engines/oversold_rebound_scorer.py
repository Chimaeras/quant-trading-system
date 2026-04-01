"""
超跌反弹机会筛选打分系统（Python 版本）

与后端 Java 口径保持一致：趋势40 + 成交量30 + 超跌30 = total_score(0-100)
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Any, Optional

import pandas as pd

from utils.indicators import bollinger_bands, rsi


@dataclass
class ScoreResult:
    trend_score_40: int
    volume_score_30: int
    oversold_score_30: int
    total_score: float
    weighted_score: float
    detail: Dict[str, Any]


def score_oversold_rebound(df: pd.DataFrame) -> Optional[ScoreResult]:
    """
    输入 df 需包含至少 260 条日线，且包含列：
    - date（可选，用于周K统计；若无则跳过周K分）
    - open, close, volume（必须）
    """
    if df is None or len(df) < 260:
        return None

    close = df["close"].astype(float).reset_index(drop=True)
    open_ = df["open"].astype(float).reset_index(drop=True)
    volume = df["volume"].astype(float).fillna(0).reset_index(drop=True)

    n = len(close)
    last_close = float(close.iloc[-1])

    ret60 = (last_close / float(close.iloc[-61]) - 1) * 100 if n >= 61 else 0.0
    ret120 = (last_close / float(close.iloc[-121]) - 1) * 100 if n >= 121 else 0.0

    ma20 = float(close.rolling(20).mean().iloc[-1])
    ma60 = float(close.rolling(60).mean().iloc[-1])
    ma120 = float(close.rolling(120).mean().iloc[-1])

    # 周K连续阴线（如果有 date）
    bear_weeks = 0
    if "date" in df.columns:
        d = pd.to_datetime(df["date"])
        tmp = df.copy()
        tmp["date"] = d
        tmp["iso_year"] = tmp["date"].dt.isocalendar().year.astype(int)
        tmp["iso_week"] = tmp["date"].dt.isocalendar().week.astype(int)
        g = tmp.groupby(["iso_year", "iso_week"], sort=True)
        week_open = g["open"].first()
        week_close = g["close"].last()
        wk = pd.DataFrame({"open": week_open, "close": week_close}).reset_index(drop=True)
        # 从最新周向前数连续阴线
        for i in range(len(wk) - 1, -1, -1):
            if float(wk.loc[i, "close"]) < float(wk.loc[i, "open"]):
                bear_weeks += 1
            else:
                break

    v5 = float(volume.rolling(5).mean().iloc[-1])
    v30 = float(volume.rolling(30).mean().iloc[-1])
    v_ratio = (v5 / v30) if v30 > 0 else 0.0

    rsi14 = float(rsi(close, 14).iloc[-1])

    upper, mid, lower = bollinger_bands(close, 20, 2.0)
    boll_mid = float(mid.iloc[-1])
    std20 = float(close.rolling(20).std().iloc[-1])
    boll_lower = float(lower.iloc[-1])
    boll_lower_extreme = boll_mid - 3.5 * std20

    win = close.iloc[max(0, n - 252):]
    min252 = float(win.min())
    max252 = float(win.max())
    percentile = 0.5 if (max252 - min252) <= 0 else (last_close - min252) / (max252 - min252)

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

    # 趋势40
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

    # 成交量30（量价配合这里简化为：最近30日下跌且后半段量高于前半段算“放量下跌”）
    volume_score = 0
    if v_ratio > 0.8:
        volume_score += 15
    elif v_ratio >= 0.5:
        volume_score += 10

    start = float(close.iloc[-30])
    end = last_close
    v_first = float(volume.iloc[-30:-15].mean())
    v_second = float(volume.iloc[-15:].mean())
    v_last5 = float(volume.iloc[-5:].mean())
    v_prev5 = float(volume.iloc[-10:-5].mean())
    if end < start and v_second > v_first * 1.05:
        volume_score += 10
        vp_type = "DOWN_VOL_EXPAND"
    elif end < start and (v_second < v_first * 0.95) and (v_last5 > v_prev5 * 1.05):
        volume_score += 5
        vp_type = "DOWN_VOL_SHRINK_THEN_WARM_EXPAND"
    else:
        vp_type = "DOWN_VOL_SHRINK"

    # 超跌30
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

    total = trend + volume_score + oversold
    weighted = (trend / 40.0) * 100 * 0.4 + (volume_score / 30.0) * 100 * 0.3 + (oversold / 30.0) * 100 * 0.3

    detail = {
        "ret60_pct": round(ret60, 2),
        "ret120_pct": round(ret120, 2),
        "ma20": round(ma20, 2),
        "ma60": round(ma60, 2),
        "ma120": round(ma120, 2),
        "consecutive_bear_weeks": int(bear_weeks),
        "vol_ratio_5_30": round(v_ratio, 2),
        "volume_price_type": vp_type,
        "rsi14": round(rsi14, 2),
        "boll_mid": round(boll_mid, 2),
        "boll_lower": round(boll_lower, 2),
        "boll_lower_extreme": round(boll_lower_extreme, 2),
        "percentile_1y": round(float(percentile), 4),
    }

    return ScoreResult(
        trend_score_40=int(trend),
        volume_score_30=int(volume_score),
        oversold_score_30=int(oversold),
        total_score=round(float(total), 2),
        weighted_score=round(float(weighted), 2),
        detail=detail,
    )

