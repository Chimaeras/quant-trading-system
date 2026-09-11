"""可恢复的内部 Paper Broker，默认 NEXT_BAR + 成交量参与率撮合。"""
from __future__ import annotations

import hashlib
import math
from datetime import datetime

from .. import clock
from ..config import COMMISSION_RATE, MIN_COMMISSION, STAMP_TAX_RATE, PaperConfig
from ..models import Balance, MxOrder, Position
from ..mx_result import MxErrorKind, MxResult
from .models import WatchBar
from .oms import ACTIVE_STATUSES, LocalOrder, OmsService, OrderStatus
from .store import WatchStore, WatchStoreError


def _ok(endpoint: str, data: dict | None = None, message: str = "ok") -> MxResult:
    return MxResult(endpoint=endpoint, ok=True, code="200", message=message, data=data or {})


def _fail(endpoint: str, code: str, message: str) -> MxResult:
    return MxResult(
        endpoint=endpoint,
        ok=False,
        code=code,
        message=message,
        kind=MxErrorKind.BIZ_ERROR,
    )


class InternalPaperBroker:
    """BrokerClient 兼容接口；所有资金、订单、成交均落同一 WatchStore。"""

    is_internal_paper = True

    def __init__(self, store: WatchStore, config: PaperConfig, *, now_fn=None):
        self.store = store
        self.config = config
        self.session_id = config.paper_session_id
        self.now_fn = now_fn or clock.now
        self.oms = OmsService(store, session_id=self.session_id, channel="INTERNAL_PAPER")

    def _state(self) -> tuple[dict, dict[str, dict]]:
        current = self.now_fn()
        trade_date = current.strftime("%Y-%m-%d")
        account, rows = self.store.load_simulation(self.session_id)
        if account is None:
            account = {
                "session_id": self.session_id,
                "trade_date": trade_date,
                "initial_cash": self.config.internal_initial_cash,
                "cash": self.config.internal_initial_cash,
                "frozen_cash": 0.0,
                "realized_pnl": 0.0,
                "created_at": current.isoformat(),
            }
            positions: dict[str, dict] = {}
            self.store.save_simulation(account, [])
            return account, positions
        positions = {str(row["code"]): dict(row) for row in rows}
        if str(account["trade_date"]) != trade_date:
            active_sell: dict[str, int] = {}
            for order in self.oms.active():
                if order.side == "sell":
                    active_sell[order.code] = active_sell.get(order.code, 0) + order.remaining_qty
            for code, position in positions.items():
                position["today_buy_qty"] = 0
                position["available_qty"] = max(
                    int(position["qty"]) - active_sell.get(code, 0), 0
                )
            account["trade_date"] = trade_date
            self.store.save_simulation(account, positions.values())
        return account, positions

    @staticmethod
    def _position_model(row: dict) -> Position:
        qty = int(row["qty"])
        last = float(row.get("last_price", 0.0) or row.get("avg_cost", 0.0))
        cost = float(row.get("avg_cost", 0.0))
        value = qty * last
        return Position(
            code=str(row["code"]),
            name=str(row.get("name", "")),
            qty=qty,
            avail_qty=int(row["available_qty"]),
            cost=cost,
            last=last,
            market_value=value,
            profit=(last - cost) * qty,
            raw={"channel": "INTERNAL_PAPER", "todayBuyQty": int(row.get("today_buy_qty", 0))},
        )

    @staticmethod
    def _mx_status(status: str) -> str:
        return {
            OrderStatus.CREATED.value: "1",
            OrderStatus.SUBMITTING.value: "1",
            OrderStatus.SUBMITTED.value: "2",
            OrderStatus.PARTIAL.value: "3",
            OrderStatus.FILLED.value: "4",
            OrderStatus.CANCEL_PENDING.value: "6",
            OrderStatus.CANCELLED.value: "8",
            OrderStatus.REJECTED.value: "9",
            OrderStatus.UNKNOWN.value: "10",
            OrderStatus.EXPIRED.value: "8",
        }[status]

    def balance(self, run_id: str = "adhoc") -> tuple[Balance, MxResult]:
        account, positions = self._state()
        market_value = sum(
            int(row["qty"]) * float(row.get("last_price", 0.0) or row.get("avg_cost", 0.0))
            for row in positions.values()
        )
        cash = float(account["cash"])
        frozen = float(account["frozen_cash"])
        return Balance(
            total_assets=cash + market_value,
            avail_balance=max(cash - frozen, 0.0),
            frozen=frozen,
            market_value=market_value,
            acc_name=f"Internal Paper/{self.session_id}",
            acc_id=self.session_id,
            raw={"channel": "INTERNAL_PAPER", "realizedPnl": account["realized_pnl"]},
        ), _ok("internal/balance")

    def positions(self, run_id: str = "adhoc") -> tuple[list[Position], MxResult]:
        _account, positions = self._state()
        return [self._position_model(row) for row in positions.values()], _ok("internal/positions")

    def orders(self, run_id: str = "adhoc") -> tuple[list[MxOrder], MxResult]:
        # 账户快照中的 orders 表示在途委托；终态订单不应继续进入风控或监控面板。
        rows = self.oms.active()
        orders = [
            MxOrder(
                order_id=str(row.client_order_id),
                code=str(row.code),
                side=str(row.side),
                price=row.limit_price,
                qty=int(row.qty),
                filled_qty=int(row.filled_qty),
                status=self._mx_status(str(row.status)),
                ts=str(row.created_at),
                raw={"clientOrderId": row.client_order_id, "localStatus": row.status},
            )
            for row in rows
        ]
        return orders, _ok("internal/orders")

    def submit_local_order(self, order: LocalOrder) -> MxResult:
        """冻结资金/可卖数量并接受订单；不在提交瞬间成交。"""
        account, positions = self._state()
        if order.status != OrderStatus.SUBMITTING.value:
            return _fail("internal/trade", "STATE", f"订单状态不是 SUBMITTING: {order.status}")
        if order.limit_price is None or order.limit_price <= 0 or order.qty <= 0:
            return _fail("internal/trade", "PARAM", "限价或数量非法")
        if order.side == "buy":
            gross = order.qty * order.limit_price
            reserve = gross + max(gross * COMMISSION_RATE, MIN_COMMISSION)
            available = float(account["cash"]) - float(account["frozen_cash"])
            if available + 1e-8 < reserve:
                return _fail("internal/trade", "INSUFFICIENT_CASH", "内部模拟账户可用资金不足")
            account["frozen_cash"] = float(account["frozen_cash"]) + reserve
        elif order.side == "sell":
            position = positions.get(order.code)
            if position is None or int(position["available_qty"]) < order.qty:
                return _fail("internal/trade", "INSUFFICIENT_POSITION", "内部模拟账户可卖数量不足/T+1")
            position["available_qty"] = int(position["available_qty"]) - order.qty
        else:
            return _fail("internal/trade", "SIDE", "买卖方向非法")
        self.store.apply_internal_execution(
            client_order_id=order.client_order_id,
            order_fields={
                "status": OrderStatus.SUBMITTED.value,
                "broker_order_id": order.client_order_id,
            },
            account=account,
            positions=positions.values(),
        )
        return _ok(
            "internal/trade",
            {"orderId": order.client_order_id, "clientOrderId": order.client_order_id},
            "内部限价单已接受，等待下一根 bar 撮合",
        )

    def trade(self, *args, **kwargs) -> MxResult:
        raise ValueError("InternalPaperBroker 必须通过持久化 local_order 提交")

    def cancel_order(self, order_id: str, stock_code: str = "", run_id: str = "adhoc") -> MxResult:
        try:
            order = self.oms.get(order_id)
        except WatchStoreError as exc:
            return _fail("internal/cancel", "NOT_FOUND", str(exc))
        if stock_code and order.code != stock_code:
            return _fail("internal/cancel", "CODE_MISMATCH", "订单证券代码不匹配")
        if order.status not in ACTIVE_STATUSES:
            return _fail("internal/cancel", "TERMINAL", f"订单已终态: {order.status}")
        account, positions = self._state()
        if order.side == "buy" and order.limit_price is not None:
            remaining_commission = max(
                max(order.qty * order.limit_price * COMMISSION_RATE, MIN_COMMISSION)
                - min(order.total_fee, max(order.qty * order.limit_price * COMMISSION_RATE, MIN_COMMISSION)),
                0.0,
            )
            release = order.remaining_qty * order.limit_price + remaining_commission
            account["frozen_cash"] = max(float(account["frozen_cash"]) - release, 0.0)
        elif order.side == "sell":
            position = positions.get(order.code)
            if position is not None:
                position["available_qty"] = min(
                    int(position["available_qty"]) + order.remaining_qty,
                    int(position["qty"]),
                )
        self.store.apply_internal_execution(
            client_order_id=order.client_order_id,
            order_fields={"status": OrderStatus.CANCELLED.value},
            account=account,
            positions=positions.values(),
        )
        return _ok("internal/cancel", {"orderId": order.client_order_id}, "已撤销")

    def cancel_all(self, run_id: str = "adhoc") -> MxResult:
        failures: list[str] = []
        for order in self.oms.active():
            result = self.cancel_order(order.client_order_id, order.code, run_id)
            if not result.ok:
                failures.append(order.client_order_id)
        return _ok("internal/cancel", {"failed": failures}, "批量撤单完成") if not failures else _fail(
            "internal/cancel", "PARTIAL", "部分内部订单撤销失败: " + ",".join(failures)
        )

    def process_bars(self, bars: list[WatchBar], now: datetime | None = None) -> list[dict]:
        """先撮合历史活动单，再让当前 bar 的新信号创建下一批订单。"""
        if not bars:
            return []
        current = now or self.now_fn()
        account, positions = self._state()
        bar_map = {bar.code: bar for bar in bars if bar.closed}
        for code, bar in bar_map.items():
            if code in positions:
                positions[code]["last_price"] = bar.close
        capacity = {
            code: math.floor(max(bar.volume, 0.0) * self.config.internal_fill_participation / 100) * 100
            for code, bar in bar_map.items()
        }
        fills: list[dict] = []
        for order in self.oms.active():
            if order.status not in {OrderStatus.SUBMITTED.value, OrderStatus.PARTIAL.value}:
                continue
            bar = bar_map.get(order.code)
            if bar is None or order.limit_price is None or capacity.get(order.code, 0) < 100:
                continue
            try:
                signal_time = datetime.fromisoformat(order.signal_bar_ts)
            except ValueError:
                continue
            if bar.end_ts <= signal_time:
                continue
            touched = (
                bar.low <= order.limit_price if order.side == "buy"
                else bar.high >= order.limit_price
            )
            if not touched:
                continue
            qty = min(order.remaining_qty, capacity[order.code])
            qty = qty // 100 * 100
            if qty <= 0:
                continue
            capacity[order.code] -= qty
            slip = self.config.slippage_bps / 10000
            if order.side == "buy":
                reference = bar.open if bar.open <= order.limit_price else order.limit_price
                price = min(order.limit_price, reference * (1 + slip))
            else:
                reference = bar.open if bar.open >= order.limit_price else order.limit_price
                price = max(order.limit_price, reference * (1 - slip))
            price = round(price, 4)
            previous_fills = self.store.list_fills(order.client_order_id)
            old_gross = sum(float(item["gross_amount"]) for item in previous_fills)
            old_commission = sum(float(item["commission"]) for item in previous_fills)
            gross = price * qty
            target_commission = max((old_gross + gross) * COMMISSION_RATE, MIN_COMMISSION)
            commission = max(target_commission - old_commission, 0.0)
            stamp_tax = gross * STAMP_TAX_RATE if order.side == "sell" else 0.0
            total_fee = commission + stamp_tax
            new_filled = order.filled_qty + qty
            average = ((order.avg_fill_price or 0.0) * order.filled_qty + gross) / new_filled
            new_status = OrderStatus.FILLED if new_filled == order.qty else OrderStatus.PARTIAL
            if order.side == "buy":
                release = qty * order.limit_price + commission
                account["frozen_cash"] = max(float(account["frozen_cash"]) - release, 0.0)
                if new_filled == order.qty:
                    # 全额成交：提交时按「限价×全额」冻结的预估佣金 ≥ 按实际成交价累计的佣金，
                    # 差值会永久滞留 frozen_cash，抬高资金占用、误拒后续买单；撤单路径虽释放该差值，
                    # 但全成交路径此前不释放，造成「撤单不泄漏、全成交泄漏」的不一致。此处补释放。
                    reserved_commission = max(order.qty * order.limit_price * COMMISSION_RATE, MIN_COMMISSION)
                    actual_commission = float(order.total_fee) + total_fee
                    extra_release = max(reserved_commission - actual_commission, 0.0)
                    account["frozen_cash"] = max(float(account["frozen_cash"]) - extra_release, 0.0)
                account["cash"] = float(account["cash"]) - gross - total_fee
                position = positions.setdefault(order.code, {
                    "code": order.code, "name": "", "qty": 0, "available_qty": 0,
                    "today_buy_qty": 0, "avg_cost": 0.0, "last_price": price,
                })
                old_qty = int(position["qty"])
                position["qty"] = old_qty + qty
                position["today_buy_qty"] = int(position.get("today_buy_qty", 0)) + qty
                position["avg_cost"] = (
                    float(position["avg_cost"]) * old_qty + gross + total_fee
                ) / (old_qty + qty)
                position["last_price"] = bar.close
            else:
                position = positions.get(order.code)
                if position is None or int(position["qty"]) < qty:
                    raise WatchStoreError("内部卖单撮合时持仓数量不足")
                account["cash"] = float(account["cash"]) + gross - total_fee
                account["realized_pnl"] = float(account["realized_pnl"]) + (
                    price - float(position["avg_cost"])
                ) * qty - total_fee
                position["qty"] = int(position["qty"]) - qty
                position["last_price"] = bar.close
                if int(position["qty"]) == 0:
                    positions.pop(order.code)
            fill_id = hashlib.sha256(
                f"{order.client_order_id}|{bar.end_ts.isoformat()}|{new_filled}".encode("utf-8")
            ).hexdigest()[:32]
            fill = {
                "fill_id": fill_id,
                "client_order_id": order.client_order_id,
                "session_id": self.session_id,
                "code": order.code,
                "side": order.side,
                "qty": qty,
                "price": price,
                "gross_amount": gross,
                "commission": commission,
                "stamp_tax": stamp_tax,
                "total_fee": total_fee,
                "filled_at": current.isoformat(),
                "bar_ts": bar.end_ts.isoformat(),
                "price_source": "NEXT_BAR_PARTICIPATION_CAP",
                "metadata": {
                    "matching_model": self.config.internal_matching_model,
                    "participation": self.config.internal_fill_participation,
                    "bar_volume": bar.volume,
                },
            }
            inserted = self.store.apply_internal_execution(
                client_order_id=order.client_order_id,
                order_fields={
                    "status": new_status.value,
                    "filled_qty": new_filled,
                    "avg_fill_price": average,
                    "total_fee": order.total_fee + total_fee,
                },
                account=account,
                positions=positions.values(),
                fill=fill,
            )
            if inserted:
                fills.append(fill | {"status": new_status.value})
        self.store.save_simulation(account, positions.values())
        return fills
