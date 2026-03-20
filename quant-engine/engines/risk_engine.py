"""
风控引擎 - 风险控制
"""
from typing import Dict, Optional, Tuple


class RiskEngine:
    """风控引擎"""

    def __init__(self, max_position_pct: float = 0.1, max_drawdown: float = 0.2,
                 max_daily_loss: float = 0.05):
        self.max_position_pct = max_position_pct  # 单标的最大仓位比例
        self.max_drawdown = max_drawdown  # 最大回撤
        self.max_daily_loss = max_daily_loss  # 单日最大亏损

    def check_order(self, order: Dict, account: Dict) -> Tuple[bool, str]:
        """
        检查订单是否通过风控
        :return: (是否通过, 原因)
        """
        total_asset = account.get('totalAsset', account.get('total_asset', 0))
        cash = account.get('cash', 0)
        amount = order.get('amount', order.get('price', 0) * order.get('volume', 0))

        if order.get('action') == 'buy':
            if amount > cash:
                return False, "可用资金不足"
            if total_asset > 0 and amount / total_asset > self.max_position_pct:
                return False, f"单笔订单超过仓位限制({self.max_position_pct:.0%})"

        return True, ""

    def check_drawdown(self, current_equity: float, peak_equity: float) -> bool:
        """检查是否触发最大回撤"""
        if peak_equity <= 0:
            return True
        dd = (peak_equity - current_equity) / peak_equity
        return dd <= self.max_drawdown
