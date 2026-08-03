"""
可交易性过滤（纯函数，作为打分/选股前的预过滤钩子）。

设计要点：
- 纯函数，无副作用，不修改打分逻辑；调用方在调用 score_oversold_rebound 前先用本函数预过滤。
- Point-in-time：仅使用输入 df 的最后一根 bar（决策日），不引入未来信息。
- 规则保守：价格/量为正、非停牌；若数据带 limit_up/limit_down 列，排除一字涨跌停锁定。
"""
from __future__ import annotations

from typing import Tuple

import pandas as pd


def tradability_check(df: pd.DataFrame) -> Tuple[bool, str]:
    """检查 df 最后一根 bar 是否可交易。

    返回 (tradable, reason)。reason 在不可交易时给出原因，可交易时为 "可交易"。
    """
    if df is None or len(df) == 0:
        return False, "无数据"

    last = df.iloc[-1]
    close = float(last.get("close", 0.0) or 0.0)
    volume = float(last.get("volume", 0.0) or 0.0)

    if close <= 0:
        return False, "价格无效"
    if volume <= 0:
        return False, "停牌或无成交"

    # 可选列：一字涨跌停锁定（数据源若提供则启用）
    if "limit_up" in df.columns and bool(last.get("limit_up", False)):
        return False, "一字涨停不可买入"
    if "limit_down" in df.columns and bool(last.get("limit_down", False)):
        return False, "一字跌停"

    return True, "可交易"
