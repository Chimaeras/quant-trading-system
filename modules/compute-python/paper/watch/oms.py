"""持久化本地 OMS：幂等订单、单向状态机、柜台状态归一化。"""
from __future__ import annotations

import hashlib
import json
import secrets
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import Enum

from .. import clock
from ..models import MxOrder
from .store import WatchStore, WatchStoreError


class OrderStatus(str, Enum):
    CREATED = "CREATED"
    SUBMITTING = "SUBMITTING"
    SUBMITTED = "SUBMITTED"
    PARTIAL = "PARTIAL"
    FILLED = "FILLED"
    CANCEL_PENDING = "CANCEL_PENDING"
    CANCELLED = "CANCELLED"
    REJECTED = "REJECTED"
    UNKNOWN = "UNKNOWN"
    EXPIRED = "EXPIRED"


ACTIVE_STATUSES = {
    OrderStatus.CREATED.value,
    OrderStatus.SUBMITTING.value,
    OrderStatus.SUBMITTED.value,
    OrderStatus.PARTIAL.value,
    OrderStatus.CANCEL_PENDING.value,
    OrderStatus.UNKNOWN.value,
}
TERMINAL_STATUSES = {
    OrderStatus.FILLED.value,
    OrderStatus.CANCELLED.value,
    OrderStatus.REJECTED.value,
    OrderStatus.EXPIRED.value,
}

# 单向状态机（fail-closed）：未列出的边一律拒绝。因此必须以「柜台可能发出什么」为准，
# 而不是以「我们期望的股市语义」为准 —— 漏掉一条柜台真实可达的边，代价不是丢一笔订单，
# 而是 sync_external 抛错 → loop._live_halted 置位 → 实盘一路停到人工重启。
# REJECTED 对 PARTIAL / CANCEL_PENDING 均可达（部成或撤单在途时柜台报废单 status=9）。
_TRANSITIONS = {
    OrderStatus.CREATED: {OrderStatus.SUBMITTING, OrderStatus.REJECTED, OrderStatus.EXPIRED},
    OrderStatus.SUBMITTING: {
        OrderStatus.SUBMITTED, OrderStatus.PARTIAL, OrderStatus.FILLED,
        OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.UNKNOWN,
    },
    OrderStatus.SUBMITTED: {
        OrderStatus.PARTIAL, OrderStatus.FILLED, OrderStatus.CANCEL_PENDING,
        OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.UNKNOWN, OrderStatus.EXPIRED,
    },
    OrderStatus.PARTIAL: {
        OrderStatus.PARTIAL, OrderStatus.FILLED, OrderStatus.CANCEL_PENDING,
        OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.UNKNOWN, OrderStatus.EXPIRED,
    },
    OrderStatus.CANCEL_PENDING: {
        OrderStatus.CANCEL_PENDING, OrderStatus.CANCELLED, OrderStatus.REJECTED,
        OrderStatus.PARTIAL, OrderStatus.FILLED, OrderStatus.UNKNOWN,
    },
    OrderStatus.UNKNOWN: {
        OrderStatus.UNKNOWN, OrderStatus.SUBMITTED, OrderStatus.PARTIAL,
        OrderStatus.FILLED, OrderStatus.CANCEL_PENDING, OrderStatus.CANCELLED,
        OrderStatus.REJECTED,
    },
}


@dataclass(frozen=True)
class LocalOrder:
    client_order_id: str
    idempotency_key: str
    session_id: str
    channel: str
    run_id: str
    strategy: str
    signal_bar_ts: str
    code: str
    side: str
    order_type: str
    limit_price: float | None
    qty: int
    filled_qty: int
    avg_fill_price: float | None
    total_fee: float
    status: str
    broker_order_id: str
    created_at: str
    updated_at: str
    expires_at: str
    last_error_code: str = ""
    last_error_message: str = ""
    metadata: dict | None = None

    @classmethod
    def from_dict(cls, row: dict) -> "LocalOrder":
        return cls(
            client_order_id=str(row["client_order_id"]),
            idempotency_key=str(row["idempotency_key"]),
            session_id=str(row["session_id"]),
            channel=str(row["channel"]),
            run_id=str(row["run_id"]),
            strategy=str(row["strategy"]),
            signal_bar_ts=str(row["signal_bar_ts"]),
            code=str(row["code"]),
            side=str(row["side"]),
            order_type=str(row["order_type"]),
            limit_price=float(row["limit_price"]) if row.get("limit_price") is not None else None,
            qty=int(row["qty"]),
            filled_qty=int(row.get("filled_qty", 0)),
            avg_fill_price=(
                float(row["avg_fill_price"]) if row.get("avg_fill_price") is not None else None
            ),
            total_fee=float(row.get("total_fee", 0.0)),
            status=str(row["status"]),
            broker_order_id=str(row.get("broker_order_id", "")),
            created_at=str(row["created_at"]),
            updated_at=str(row["updated_at"]),
            expires_at=str(row.get("expires_at", "")),
            last_error_code=str(row.get("last_error_code", "")),
            last_error_message=str(row.get("last_error_message", "")),
            metadata=row.get("metadata") if isinstance(row.get("metadata"), dict) else {},
        )

    @property
    def remaining_qty(self) -> int:
        return max(self.qty - self.filled_qty, 0)


def order_fingerprint(
    *,
    session_id: str,
    channel: str,
    strategy: str,
    signal_bar_ts: str,
    code: str,
    side: str,
    qty: int,
    limit_price: float | None,
) -> str:
    payload = {
        "session_id": session_id,
        "channel": channel,
        "strategy": strategy,
        "signal_bar_ts": signal_bar_ts,
        "code": code,
        "side": side,
        "qty": qty,
        "limit_price": round(float(limit_price), 6) if limit_price is not None else None,
    }
    raw = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def external_status(order: MxOrder) -> OrderStatus:
    """按项目已冻结的妙想官方枚举映射，不猜测未定义状态。"""
    filled = int(order.filled_qty or 0)
    if order.status == "3":
        return OrderStatus.PARTIAL
    if order.status == "4":
        return OrderStatus.FILLED
    if order.status in {"5", "6"}:
        return OrderStatus.CANCEL_PENDING
    if order.status in {"7", "8"}:
        return OrderStatus.CANCELLED
    if order.status == "9":
        return OrderStatus.REJECTED
    if order.status == "10":
        return OrderStatus.UNKNOWN
    if order.status in {"1", "2"}:
        return OrderStatus.PARTIAL if filled > 0 else OrderStatus.SUBMITTED
    return OrderStatus.UNKNOWN


def _external_average_price(order: MxOrder) -> tuple[float | None, str]:
    """只解析已冻结的 tradePrice/tradePriceDec；缺失时明确降级为委托价估算。"""
    raw = order.raw if isinstance(order.raw, dict) else {}
    trade_price = raw.get("tradePrice")
    if trade_price not in (None, ""):
        try:
            value = float(trade_price) / (10 ** int(raw.get("tradePriceDec", 0) or 0))
        except (TypeError, ValueError, OverflowError):
            value = 0.0
        if value > 0:
            return value, "MX_ORDER_AGGREGATE"
    if order.price is not None and float(order.price) > 0:
        return float(order.price), "BROKER_ORDER_AGGREGATE_ESTIMATE"
    return None, ""


class OmsService:
    def __init__(self, store: WatchStore, *, session_id: str, channel: str):
        self.store = store
        self.session_id = session_id
        self.channel = channel

    def create(
        self,
        *,
        run_id: str,
        strategy: str,
        signal_bar_ts: str,
        code: str,
        side: str,
        qty: int,
        limit_price: float,
        ttl_sec: int,
        metadata: dict | None = None,
        now: datetime | None = None,
    ) -> tuple[LocalOrder, bool]:
        current = now or clock.now()
        key = order_fingerprint(
            session_id=self.session_id,
            channel=self.channel,
            strategy=strategy,
            signal_bar_ts=signal_bar_ts,
            code=code,
            side=side,
            qty=qty,
            limit_price=limit_price,
        )
        row, created = self.store.create_local_order({
            "client_order_id": "co-" + secrets.token_hex(12),
            "idempotency_key": key,
            "session_id": self.session_id,
            "channel": self.channel,
            "run_id": run_id,
            "strategy": strategy,
            "signal_bar_ts": signal_bar_ts,
            "code": code,
            "side": side,
            "order_type": "LIMIT",
            "limit_price": limit_price,
            "qty": qty,
            "status": OrderStatus.CREATED.value,
            "created_at": current.isoformat(),
            "updated_at": current.isoformat(),
            "expires_at": (current + timedelta(seconds=ttl_sec)).isoformat(),
            "metadata": metadata or {},
        })
        return LocalOrder.from_dict(row), created

    def transition(self, client_order_id: str, target: OrderStatus, **fields) -> LocalOrder:
        current = self.get(client_order_id)
        source = OrderStatus(current.status)
        if source != target and target not in _TRANSITIONS.get(source, set()):
            raise WatchStoreError(f"订单状态禁止倒退: {source.value}->{target.value}")
        fields["status"] = target.value
        return LocalOrder.from_dict(self.store.update_local_order(client_order_id, fields))

    def get(self, client_order_id: str) -> LocalOrder:
        row = self.store.get_local_order(client_order_id)
        if row is None:
            raise WatchStoreError(f"本地订单不存在: {client_order_id}")
        return LocalOrder.from_dict(row)

    def active(self) -> list[LocalOrder]:
        return [
            LocalOrder.from_dict(row)
            for row in self.store.list_local_orders(
                statuses=ACTIVE_STATUSES, channel=self.channel, session_id=self.session_id
            )
        ]

    def record_fill(
        self,
        order: LocalOrder,
        *,
        qty: int,
        price: float,
        commission: float,
        stamp_tax: float,
        filled_at: datetime,
        bar_ts: str,
        price_source: str,
        fill_id: str = "",
        metadata: dict | None = None,
    ) -> LocalOrder:
        if qty <= 0 or qty > order.remaining_qty:
            raise WatchStoreError("成交数量超过订单剩余数量")
        new_filled = order.filled_qty + qty
        previous_gross = (order.avg_fill_price or 0.0) * order.filled_qty
        average = (previous_gross + price * qty) / new_filled
        status = OrderStatus.FILLED if new_filled == order.qty else OrderStatus.PARTIAL
        total_fee = order.total_fee + commission + stamp_tax
        stable_fill_id = fill_id or hashlib.sha256(
            f"{order.client_order_id}|{bar_ts}|{new_filled}".encode("utf-8")
        ).hexdigest()[:32]
        self.store.append_fill({
            "fill_id": stable_fill_id,
            "client_order_id": order.client_order_id,
            "session_id": order.session_id,
            "code": order.code,
            "side": order.side,
            "qty": qty,
            "price": price,
            "gross_amount": price * qty,
            "commission": commission,
            "stamp_tax": stamp_tax,
            "total_fee": commission + stamp_tax,
            "filled_at": filled_at.isoformat(),
            "bar_ts": bar_ts,
            "price_source": price_source,
            "metadata": metadata or {},
        }, {
            "status": status.value,
            "filled_qty": new_filled,
            "avg_fill_price": average,
            "total_fee": total_fee,
        })
        return self.get(order.client_order_id)

    def sync_external(self, broker_orders: list[MxOrder], now: datetime | None = None) -> list[dict]:
        """用柜台证据推进本地状态；无唯一匹配时保持 UNKNOWN。"""
        current = now or clock.now()
        changes: list[dict] = []
        used: set[str] = set()
        linked: dict[str, str] = {}
        for local in self.active():
            match = next(
                (item for item in broker_orders if local.broker_order_id and item.order_id == local.broker_order_id),
                None,
            )
            if match is None and local.status in {
                OrderStatus.SUBMITTING.value, OrderStatus.UNKNOWN.value
            }:
                candidates = [
                    item for item in broker_orders
                    if item.order_id not in used
                    and item.code == local.code and item.side == local.side and item.qty == local.qty
                    and item.price is not None and local.limit_price is not None
                    and abs(float(item.price) - local.limit_price) <= 0.02
                ]
                if len(candidates) == 1:
                    match = candidates[0]
            if match is None:
                continue
            used.add(match.order_id)
            linked[match.order_id] = local.client_order_id
            target = external_status(match)
            if local.status == OrderStatus.CANCEL_PENDING.value and target == OrderStatus.SUBMITTED:
                # 撤单请求后的柜台快照可能仍是旧“已报”，不能把本地状态倒退。
                target = OrderStatus.CANCEL_PENDING
            filled = max(int(match.filled_qty or 0), local.filled_qty)
            # 柜台快照（status=1/2）本身 filled_qty 可能为 0，但本地已有成交证据：此时
            # external_status 会给出 SUBMITTED，而本地已是 PARTIAL —— 直接照搬即为状态倒退。
            # 以合并后的 filled 为准重导目标态。
            if target == OrderStatus.SUBMITTED and filled > 0:
                target = OrderStatus.PARTIAL
            fields = {
                "broker_order_id": match.order_id,
                "filled_qty": filled,
            }
            aggregate_price, price_source = _external_average_price(match)
            if filled > local.filled_qty and aggregate_price is not None:
                delta = filled - local.filled_qty
                # orders 只提供累计成交证据；不拆成虚构逐笔，费用保持 unknown。
                local = self.record_fill(
                    local,
                    qty=delta,
                    price=aggregate_price,
                    commission=0.0,
                    stamp_tax=0.0,
                    filled_at=current,
                    bar_ts="",
                    price_source=price_source,
                    fill_id=f"mx-{match.order_id}-{filled}",
                    metadata={
                        "fee_unknown": True,
                        "mx_status": match.status,
                        "aggregate": True,
                        "price_estimated": price_source == "BROKER_ORDER_AGGREGATE_ESTIMATE",
                    },
                )
                fields.pop("filled_qty", None)
                if target == OrderStatus.PARTIAL and local.status == target.value:
                    local = self.transition(
                        local.client_order_id,
                        target,
                        broker_order_id=match.order_id,
                    )
                    changes.append({
                        "client_order_id": local.client_order_id,
                        "broker_order_id": local.broker_order_id,
                        "status": local.status,
                        "filled_qty": local.filled_qty,
                    })
                    continue
            updated = self.transition(local.client_order_id, target, **fields)
            changes.append({
                "client_order_id": updated.client_order_id,
                "broker_order_id": updated.broker_order_id,
                "status": updated.status,
                "filled_qty": updated.filled_qty,
            })
        mirror_rows = []
        for broker_order in broker_orders:
            normalized = external_status(broker_order).value
            filled = max(int(broker_order.filled_qty or 0), 0)
            average, source = _external_average_price(broker_order)
            mirror_rows.append({
                "broker_order_id": broker_order.order_id,
                "client_order_id": linked.get(broker_order.order_id, ""),
                "origin": "WATCH_OMS" if broker_order.order_id in linked else "EXTERNAL/UNTRACKED",
                "code": broker_order.code,
                "name": str((broker_order.raw or {}).get("secName", "")),
                # 北交所 920xxx 同样以 "9" 开头，必须先判 BJ（与 loop._exchange_for_code 一致），
                # 否则镜像单会被标成 SH。
                "exchange": "BJ" if broker_order.code.startswith(("4", "8", "92")) else (
                    "SH" if broker_order.code.startswith(("5", "6", "9")) else "SZ"
                ),
                "side": broker_order.side,
                "order_type": "LIMIT",
                "limit_price": broker_order.price,
                "qty": broker_order.qty,
                "filled_qty": filled,
                "remaining_qty": max(broker_order.qty - filled, 0)
                    if normalized not in TERMINAL_STATUSES else 0,
                "avg_fill_price": average if filled > 0 else None,
                "total_fee": None,
                "broker_status": broker_order.status,
                "normalized_status": normalized,
                "broker_time": broker_order.ts,
                "created_at": broker_order.ts,
                "metadata": {
                    "fee_unknown": True,
                    "price_source": source or None,
                    "price_estimated": source == "BROKER_ORDER_AGGREGATE_ESTIMATE",
                },
            })
        self.store.upsert_external_orders(
            channel=self.channel,
            session_id=self.session_id,
            orders=mirror_rows,
            captured_at=current.isoformat(),
        )
        return changes
