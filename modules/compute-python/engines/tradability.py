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


def _flag_is_true(value) -> bool:
    """把可选布尔标志安全归一为 bool：None/NaN/NA → False，其余按真值判定。

    必要性：bool(float('nan')) 为 True（会把「不适用」误判为「锁定」），
    bool(pd.NA) 直接抛 TypeError。标志列在真实行情里常以 NaN 表示该行不适用。
    """
    try:
        if value is None or pd.isna(value):
            return False
    except (TypeError, ValueError):
        return False
    return bool(value)


def tradability_check(df: pd.DataFrame) -> Tuple[bool, str]:
    """检查 df 最后一根 bar 是否可交易。

    返回 (tradable, reason)。reason 在不可交易时给出原因，可交易时为 "可交易"。

    区分「列缺失」与「值为 0/NaN」：仅有 close 的研究样本（无量数据）不应被
    误判为停牌；volume 列缺失时跳过成交量检查而非一律阻塞。
    """
    if df is None or len(df) == 0:
        return False, "无数据"

    last = df.iloc[-1]

    # close 是必检列：缺失或无效直接判不可交易
    if "close" not in df.columns:
        return False, "缺少 close 列"
    close_raw = last.get("close")
    # NaN 是 falsy 但 float('nan) > 0 为 False，需显式处理
    if close_raw is None or pd.isna(close_raw):
        return False, "价格无效（NaN）"
    close = float(close_raw)
    if close <= 0:
        return False, "价格无效"

    # volume 是可选列：缺失时（仅 close 的研究样本）跳过量检查；
    # 存在但为 0/NaN 时才判停牌。避免「无量数据」被误杀。
    if "volume" in df.columns:
        vol_raw = last.get("volume")
        if vol_raw is None or pd.isna(vol_raw) or float(vol_raw) <= 0:
            return False, "停牌或无成交"

    # 可选列：一字涨跌停锁定（数据源若提供则启用）。
    # 标志常为 NaN/NA（该行不适用），必须先归一，只把「显式为真」视为锁定，
    # 否则 NaN 会把每一行都误杀成一字涨跌停。
    if "limit_up" in df.columns and _flag_is_true(last.get("limit_up")):
        return False, "一字涨停不可买入"
    if "limit_down" in df.columns and _flag_is_true(last.get("limit_down")):
        return False, "一字跌停"

    return True, "可交易"
