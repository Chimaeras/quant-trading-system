"""收盘对账：本地 submits 与妙想外部账户比对。"""
from __future__ import annotations

import json
from pathlib import Path


def _load_submits(log_base: Path, trade_date: str) -> list[dict]:
    """汇总当日全部 run 的 submits。"""
    day_dir = log_base / "paper" / trade_date
    submits: list[dict] = []
    if not day_dir.exists():
        return submits
    for run_path in sorted(day_dir.iterdir()):
        f = run_path / "submits.json"
        if f.exists():
            data = json.loads(f.read_text(encoding="utf-8"))
            if isinstance(data, list):
                submits.extend(data)
    return submits


def reconcile_day(broker, log_base: Path, trade_date: str) -> dict:
    """对账单日：匹配 orderId，输出差异与状态。

    Returns:
        {status: OK|BLOCKED, local_submits, external_orders, matched,
         unknown, differences}
    """
    submits = _load_submits(log_base, trade_date)
    balance, _b = broker.balance()
    orders, _o = broker.orders()

    external_ids = {o.order_id for o in orders if o.order_id}
    matched = [s for s in submits if s.get("order_id") in external_ids]
    unknown = [
        s for s in submits
        if s.get("status") == "UNKNOWN_SUBMIT" or (s.get("order_id") and s["order_id"] not in external_ids)
    ]

    differences: list[str] = []
    if unknown:
        differences.append(f"{len(unknown)} 笔提交无法在外部委托中确认（含 UNKNOWN_SUBMIT）")
    if balance is None:
        differences.append("balance 查询失败，无法核对资金")

    status = "BLOCKED" if differences else "OK"
    return {
        "trade_date": trade_date,
        "status": status,
        "local_submits": len(submits),
        "external_orders": len(orders),
        "matched": len(matched),
        "unknown": len(unknown),
        "total_assets": balance.total_assets if balance else None,
        "avail_balance": balance.avail_balance if balance else None,
        "differences": differences,
    }
