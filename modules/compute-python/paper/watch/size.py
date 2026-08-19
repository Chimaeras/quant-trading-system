"""watch 买卖数量和限价计算。"""
from __future__ import annotations

import math

from ..config import LOT_SIZE, MIN_COMMISSION, PRICE_DECIMALS, PaperConfig
from ..models import Balance


def round_limit_price(price: float, exchange: str) -> float:
    return round(price, PRICE_DECIMALS.get(exchange, 2))


def buy_qty(balance: Balance, last: float, config: PaperConfig) -> int:
    """固定权重买入；先保留现金底线，再按整手取整。"""
    if last <= 0:
        return 0
    # 目标仓位沿用 Paper V1 的 avail_balance × weight 口径；
    # 可买上限再扣除现金底线和最低佣金，组合级风险会做最终校验。
    target = math.floor(balance.avail_balance * config.max_name_weight / last / LOT_SIZE) * LOT_SIZE
    investable = max(balance.avail_balance - config.min_cash_reserve, 0.0)
    affordable = math.floor(max(investable - MIN_COMMISSION, 0.0) / last / LOT_SIZE) * LOT_SIZE
    return max(min(target, affordable), 0)


def sell_qty(available_qty: int) -> int:
    if available_qty <= 0:
        return 0
    return (available_qty // LOT_SIZE) * LOT_SIZE


def limit_price(last: float, exchange: str, side: str, slippage_bps: int) -> float:
    factor = 1 + slippage_bps / 10000 if side == "buy" else 1 - slippage_bps / 10000
    return round_limit_price(last * factor, exchange)


def sizing_detail(balance: Balance, last: float, config: PaperConfig, side: str, available_qty: int = 0) -> dict:
    """买卖数量的推导过程，供监控面板展示；纯计算，不参与决策。"""
    if side == "buy":
        target = math.floor(balance.avail_balance * config.max_name_weight / last / LOT_SIZE) * LOT_SIZE if last > 0 else 0
        investable = max(balance.avail_balance - config.min_cash_reserve, 0.0)
        affordable = math.floor(max(investable - MIN_COMMISSION, 0.0) / last / LOT_SIZE) * LOT_SIZE if last > 0 else 0
        return {
            "side": "buy",
            "avail_balance": balance.avail_balance,
            "weight": config.max_name_weight,
            "last": last,
            "target_qty": target,
            "investable_after_reserve": round(investable, 2),
            "affordable_qty": affordable,
            "final_qty": buy_qty(balance, last, config),
        }
    return {
        "side": "sell",
        "available_qty": available_qty,
        "lot_qty": sell_qty(available_qty),
    }
