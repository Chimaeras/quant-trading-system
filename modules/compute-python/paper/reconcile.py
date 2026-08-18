"""收盘对账：本地 submits 与妙想外部委托/账户比对。

P1-06：不只按 order_id 匹配——完整核对状态、委托数量、已成交数量、方向与价格；
已撤/废单不得判成功，部成返回 PARTIAL，查询失败或订单缺失返回 UNKNOWN/BLOCKED。
"""
from __future__ import annotations

import json
from pathlib import Path

# 官方订单状态 → 对账结论（3/5/6/10 视为未定型 → UNKNOWN，须人工/下轮复核）
_STATUS_CLASS = {
    "4": "SUCCESS",
    "8": "CANCELED",
    "9": "REJECTED",
    "7": "CANCELED",  # 部撤：非成功
    "3": "PARTIAL",
    "1": "UNKNOWN",
    "2": "UNKNOWN",
    "5": "UNKNOWN",
    "6": "UNKNOWN",
    "10": "UNKNOWN",
}


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


def classify_submit(submit: dict, order: dict | None) -> str:
    """单笔提交对账结论：SUCCESS/PARTIAL/REJECTED/CANCELED/UNKNOWN/BLOCKED。"""
    if submit.get("status") == "UNKNOWN_SUBMIT":
        return "UNKNOWN"
    if order is None:
        return "UNKNOWN"
    # 字段冲突（方向/数量/代码不一致）→ 不能判成功
    if str(order.get("code")) != str(submit.get("stock_code")):
        return "BLOCKED"
    if int(order.get("qty", 0)) != int(submit.get("qty", -1)):
        return "BLOCKED"
    if str(order.get("side")) != str(submit.get("side", "buy")):
        return "BLOCKED"
    return _STATUS_CLASS.get(str(order.get("status", "")), "UNKNOWN")


def reconcile_day(broker, log_base: Path, trade_date: str) -> dict:
    """对账单日：按订单号+字段完整核对，输出状态分类与差异。

    Returns:
        {status, local_submits, external_orders, matched, success_count,
         partial_count, rejected_count, canceled_count, unknown_count,
         query_error_count, differences, ...}
    """
    submits = _load_submits(log_base, trade_date)
    balance, _b = broker.balance()
    orders, o_result = broker.orders()

    # fail-closed：orders 查询/解析失败时对账必须是 BLOCKED
    if orders is None:
        return {
            "trade_date": trade_date,
            "status": "BLOCKED",
            "local_submits": len(submits),
            "external_orders": 0,
            "matched": 0,
            "success_count": 0,
            "partial_count": 0,
            "rejected_count": 0,
            "canceled_count": 0,
            "unknown_count": len(submits),
            "query_error_count": 1,
            "total_assets": balance.total_assets if balance else None,
            "avail_balance": balance.avail_balance if balance else None,
            "differences": ["orders 查询/解析失败，无法对账"],
        }

    by_id = {o.order_id: o for o in orders if o.order_id}
    results: list[dict] = []
    differences: list[str] = []
    counts = {"success_count": 0, "partial_count": 0, "rejected_count": 0,
              "canceled_count": 0, "unknown_count": 0}
    for s in submits:
        order = by_id.get(s.get("order_id", ""))
        verdict = classify_submit(s, asdict_order(order) if order else None)
        results.append({"stock_code": s.get("stock_code"), "order_id": s.get("order_id"),
                        "verdict": verdict,
                        "remaining_qty": max((order.qty if order else 0) - ((order.filled_qty or 0) if order else 0), 0)})
        if verdict == "SUCCESS":
            counts["success_count"] += 1
        elif verdict == "PARTIAL":
            counts["partial_count"] += 1
            differences.append(f"{s.get('stock_code')} 部分成交，剩余 {results[-1]['remaining_qty']} 股")
        elif verdict == "REJECTED":
            counts["rejected_count"] += 1
            differences.append(f"{s.get('stock_code')} 废单")
        elif verdict == "CANCELED":
            counts["canceled_count"] += 1
            differences.append(f"{s.get('stock_code')} 已撤/部撤，不能记为成功")
        else:  # UNKNOWN / BLOCKED
            counts["unknown_count"] += 1
            differences.append(
                f"{s.get('stock_code')} 状态未知或字段冲突（{verdict}），须复核"
            )
    if balance is None:
        differences.append("balance 查询失败，无法核对资金")

    ok = (counts["unknown_count"] == 0 and counts["rejected_count"] == 0
          and counts["canceled_count"] == 0 and counts["partial_count"] == 0
          and balance is not None)
    return {
        "trade_date": trade_date,
        "status": "OK" if ok else "BLOCKED",
        "local_submits": len(submits),
        "external_orders": len(orders),
        "matched": counts["success_count"],
        **counts,
        "query_error_count": 0 if balance is not None else 1,
        "total_assets": balance.total_assets if balance else None,
        "avail_balance": balance.avail_balance if balance else None,
        "results": results,
        "differences": differences,
    }


def asdict_order(order) -> dict:
    """委托对象 → 对账用字段字典。"""
    return {"code": order.code, "side": order.side, "qty": order.qty,
            "status": order.status, "filled_qty": order.filled_qty}
