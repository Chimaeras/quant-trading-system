"""妙想模拟交易客户端：balance / positions / orders / trade / cancel。

trade 与 cancel 不自动重试：超时必须先查 orders 对账（见 runner）。
"""
from __future__ import annotations

from .models import Balance, MxOrder, Position
from .mx_http import MxHttpClient
from .mx_result import MxResult

BASE = "/api/claw/mockTrading"


def _num(value) -> float:
    return float(value) if value is not None else 0.0


class BrokerClient:
    """五接口封装，只做取数与提交，不做业务判断。"""

    def __init__(self, http: MxHttpClient):
        self._http = http

    def balance(self, run_id: str = "adhoc") -> tuple[Balance | None, MxResult]:
        """资金查询（也是绑定健康检查）。"""
        result = self._http.post(f"{BASE}/balance", {"moneyUnit": 1}, retryable=True, run_id=run_id)
        if not result.ok or not isinstance(result.data, dict):
            return None, result
        try:
            return Balance.from_mx(result.data), result
        except ValueError as exc:
            result.message = f"balance 解析失败: {exc}"
            return None, result

    def positions(self, run_id: str = "adhoc") -> tuple[list[Position], MxResult]:
        """持仓查询。posList 非空样本未冻结，字段映射缺失时该条记 raw 不猜。"""
        result = self._http.post(f"{BASE}/positions", {"moneyUnit": 1}, retryable=True, run_id=run_id)
        if not result.ok or not isinstance(result.data, dict):
            return [], result
        pos_list = result.data.get("posList") or []
        positions = []
        for item in pos_list:
            if not isinstance(item, dict):
                continue
            try:
                positions.append(
                    Position(
                        code=str(item.get("stockCode", item.get("secuCode", ""))),
                        name=str(item.get("stockName", item.get("stockNameChg", ""))),
                        qty=int(item.get("holdNum", item.get("quantity", 0))),
                        avail_qty=int(item.get("availNum", item.get("availableQty", item.get("holdNum", 0)))),
                        cost=_num(item.get("costPrice", item.get("avgCost"))),
                        last=_num(item.get("lastPrice", item.get("newPrice"))),
                        market_value=_num(item.get("maketValue", item.get("marketValue"))),
                        profit=_num(item.get("profit", item.get("floatProfit"))),
                        raw=item,
                    )
                )
            except (TypeError, ValueError):
                positions.append(Position(code="UNKNOWN", name="", qty=0, avail_qty=0, cost=0.0, last=0.0, market_value=0.0, profit=0.0, raw=item))
        return positions, result

    def orders(self, run_id: str = "adhoc") -> tuple[list[MxOrder], MxResult]:
        """委托查询（fltOrderDrt/fltOrderStatus 固定 0 不过滤，枚举待冻结）。"""
        body = {"fltOrderDrt": 0, "fltOrderStatus": 0}
        result = self._http.post(f"{BASE}/orders", body, retryable=True, run_id=run_id)
        if not result.ok or not isinstance(result.data, dict):
            return [], result
        orders = []
        for item in result.data.get("orders") or []:
            if not isinstance(item, dict):
                continue
            orders.append(
                MxOrder(
                    order_id=str(item.get("orderId", item.get("orderNo", ""))),
                    code=str(item.get("stockCode", item.get("secuCode", ""))),
                    side=str(item.get("orderDrt", item.get("side", ""))),
                    price=item.get("orderPrice", item.get("price")),
                    qty=int(item.get("orderNum", item.get("quantity", 0)) or 0),
                    filled_qty=item.get("dealNum", item.get("filledQty")),
                    status=str(item.get("orderStatus", item.get("status", ""))),
                    ts=str(item.get("orderTime", item.get("createTime", ""))),
                    raw=item,
                )
            )
        return orders, result

    def trade(
        self,
        stock_code: str,
        side: str,
        qty: int,
        *,
        use_market_price: bool = False,
        price: float | None = None,
        run_id: str = "adhoc",
    ) -> MxResult:
        """买/卖委托。不自动重试；调用方必须已完成风控。"""
        body: dict = {
            "type": "buy" if side == "buy" else "sell",
            "stockCode": stock_code,
            "quantity": qty,
            "useMarketPrice": use_market_price,
        }
        if not use_market_price:
            if price is None:
                raise ValueError("限价单必须提供 price")
            body["price"] = price
        return self._http.post(f"{BASE}/trade", body, retryable=False, run_id=run_id)

    def cancel_order(self, order_id: str, stock_code: str = "", run_id: str = "adhoc") -> MxResult:
        """按委托编号撤单（orderId 必须来自 orders 查询）。"""
        body: dict = {"type": "order", "orderId": order_id}
        if stock_code:
            body["stockCode"] = stock_code
        return self._http.post(f"{BASE}/cancel", body, retryable=False, run_id=run_id)

    def cancel_all(self, run_id: str = "adhoc") -> MxResult:
        """撤销全部可撤委托。"""
        return self._http.post(f"{BASE}/cancel", {"type": "all"}, retryable=False, run_id=run_id)
