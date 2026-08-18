"""妙想模拟交易客户端：balance / positions / orders / trade / cancel。

trade 与 cancel 不自动重试：超时必须先查 orders 对账（见 runner）。
positions / orders 按官方字段严格解析：任一非空条目 malformed 即整体
PARSE_ERROR（fail-closed），绝不追加 UNKNOWN 零值条目继续运行。
"""
from __future__ import annotations

from .models import Balance, MxOrder, Position
from .mx_http import MxHttpClient
from .mx_result import MxErrorKind, MxResult

BASE = "/api/claw/mockTrading"

# 官方订单状态枚举（1-10），是否仍视为活动委托由 decide/risk 判断
ORDER_STATUSES = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10"}
# 需要继续视为 active 的状态（P1-01 基线：7/10 由 remaining 判断，此处先按 active 处理）
ACTIVE_ORDER_STATUSES = {"1", "2", "3", "5", "6", "7", "10"}

# drt 方向映射：1 买入，2 卖出，其他值解析失败
_DRT_SIDES = {"1": "buy", "2": "sell"}


def _parse_error(result: MxResult, message: str) -> MxResult:
    """把查询结果改写为 PARSE_ERROR（fail-closed）。"""
    result.ok = False
    result.kind = MxErrorKind.PARSE_ERROR
    result.message = message
    return result


def scaled_price(raw, dec) -> float:
    """官方价格还原：原始整数 / 10^dec（priceDec/costPriceDec 缩放因子）。"""
    if raw is None:
        raise ValueError("价格原始值缺失")
    value = float(raw)
    d = int(dec or 0)
    return value / (10 ** d)


def _required(item: dict, key: str):
    """取必填字段；缺失或空值抛 ValueError（严格校验）。"""
    value = item.get(key)
    if value is None or (isinstance(value, str) and not value.strip()):
        raise ValueError(f"缺少必填字段 {key}")
    return value


def parse_position_item(item: dict) -> Position:
    """解析单条官方持仓（字段表见修复清单 §4.1）；malformed 抛 ValueError。"""
    code = str(_required(item, "secCode")).strip()
    if not code.isdigit() or len(code) != 6:
        raise ValueError(f"secCode 非法: {code!r}")
    qty = int(_required(item, "count"))
    avail = int(_required(item, "availCount"))
    if qty < 0 or avail < 0:
        raise ValueError("持仓数量为负")
    cost = scaled_price(_required(item, "costPrice"), item.get("costPriceDec", 0))
    last = scaled_price(_required(item, "price"), item.get("priceDec", 0))
    if cost < 0 or last <= 0:
        raise ValueError("成本价/最新价非法")
    return Position(
        code=code,
        name=str(item.get("secName", "")),
        qty=qty,
        avail_qty=avail,
        cost=cost,
        last=last,
        market_value=float(_required(item, "value")),
        profit=float(item.get("profit", 0.0)),
        raw=item,
    )


def parse_order_item(item: dict) -> MxOrder:
    """解析单条官方委托（字段表见修复清单 §4.2）；malformed 抛 ValueError。"""
    order_id = str(_required(item, "id")).strip()
    code = str(_required(item, "secCode")).strip()
    if not code.isdigit() or len(code) != 6:
        raise ValueError(f"secCode 非法: {code!r}")
    drt = str(_required(item, "drt")).strip()
    side = _DRT_SIDES.get(drt)
    if side is None:
        raise ValueError(f"drt 方向非法: {drt!r}（仅支持 1=buy / 2=sell）")
    qty = int(_required(item, "count"))
    if qty <= 0:
        raise ValueError("委托数量必须为正")
    status = str(_required(item, "status")).strip()
    if status not in ORDER_STATUSES:
        raise ValueError(f"status 非法: {status!r}")
    price = scaled_price(_required(item, "price"), item.get("priceDec", 0))
    filled_raw = item.get("tradeCount")
    filled = int(filled_raw) if filled_raw not in (None, "") else 0
    return MxOrder(
        order_id=order_id,
        code=code,
        side=side,
        price=price,
        qty=qty,
        filled_qty=filled,
        status=status,
        ts=str(item.get("time", "")),
        raw=item,
    )


def order_remaining_qty(order: MxOrder) -> int:
    """活动委托剩余数量 = 委托量 - 已成交量（非活动状态返回 0）。"""
    if order.status not in ACTIVE_ORDER_STATUSES:
        return 0
    return max(order.qty - (order.filled_qty or 0), 0)


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
            return None, _parse_error(result, f"balance 解析失败: {exc}")

    def positions(self, run_id: str = "adhoc") -> tuple[list[Position] | None, MxResult]:
        """持仓查询（官方字段严格解析；malformed 返回 (None, PARSE_ERROR)）。"""
        result = self._http.post(f"{BASE}/positions", {"moneyUnit": 1}, retryable=True, run_id=run_id)
        if not result.ok or not isinstance(result.data, dict):
            return None, result
        pos_list = result.data.get("posList")
        if pos_list is None:
            return None, _parse_error(result, "positions 响应缺少 posList 字段")
        if not isinstance(pos_list, list):
            return None, _parse_error(result, "posList 不是列表")
        positions: list[Position] = []
        for item in pos_list:
            if not isinstance(item, dict):
                return None, _parse_error(result, f"posList 含非对象条目: {item!r}")
            try:
                positions.append(parse_position_item(item))
            except (TypeError, ValueError) as exc:
                return None, _parse_error(result, f"持仓条目解析失败: {exc}（阻断，不猜字段）")
        return positions, result

    def orders(self, run_id: str = "adhoc") -> tuple[list[MxOrder] | None, MxResult]:
        """委托查询（官方字段严格解析；malformed 返回 (None, PARSE_ERROR)）。"""
        body = {"fltOrderDrt": 0, "fltOrderStatus": 0}
        result = self._http.post(f"{BASE}/orders", body, retryable=True, run_id=run_id)
        if not result.ok or not isinstance(result.data, dict):
            return None, result
        orders_raw = result.data.get("orders")
        if orders_raw is None:
            return None, _parse_error(result, "orders 响应缺少 orders 字段")
        if not isinstance(orders_raw, list):
            return None, _parse_error(result, "orders 不是列表")
        orders: list[MxOrder] = []
        for item in orders_raw:
            if not isinstance(item, dict):
                return None, _parse_error(result, f"orders 含非对象条目: {item!r}")
            try:
                orders.append(parse_order_item(item))
            except (TypeError, ValueError) as exc:
                return None, _parse_error(result, f"委托条目解析失败: {exc}（阻断，不猜字段）")
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
        if side not in ("buy", "sell"):
            raise ValueError(f"side 非法: {side!r}（仅允许 buy/sell，禁止默认映射为 sell）")
        if not (isinstance(stock_code, str) and len(stock_code) == 6 and stock_code.isdigit()):
            raise ValueError(f"stock_code 非法: {stock_code!r}（须为 6 位数字）")
        if not isinstance(qty, int) or qty <= 0 or qty % 100 != 0:
            raise ValueError(f"qty 非法: {qty!r}（须为正的整百）")
        body: dict = {
            "type": side,
            "stockCode": stock_code,
            "quantity": qty,
            "useMarketPrice": use_market_price,
        }
        if not use_market_price:
            if price is None or price <= 0:
                raise ValueError("限价单必须提供正数 price")
            body["price"] = price
        return self._http.post(f"{BASE}/trade", body, retryable=False, run_id=run_id)

    def cancel_order(self, order_id: str, stock_code: str = "", run_id: str = "adhoc") -> MxResult:
        """按委托编号撤单（orderId 必须来自 orders 查询，且须带证券代码）。"""
        if not order_id:
            raise ValueError("cancel 必须提供 order_id")
        if not (isinstance(stock_code, str) and len(stock_code) == 6 and stock_code.isdigit()):
            raise ValueError("cancel 必须提供经查询验证的 6 位证券代码 stock_code")
        body: dict = {"type": "order", "orderId": order_id, "stockCode": stock_code}
        return self._http.post(f"{BASE}/cancel", body, retryable=False, run_id=run_id)

    def cancel_all(self, run_id: str = "adhoc") -> MxResult:
        """撤销全部可撤委托。"""
        return self._http.post(f"{BASE}/cancel", {"type": "all"}, retryable=False, run_id=run_id)
