"""
离线评估脚本（需要后端服务已启动，并且已落库选股结果）。

用途：
- 对比 classic(v1) 与 geopolicy_news_v1 在同一 tradeDate 的 TopN 组合次日收益
- 输出宏观风险分级（GREEN/YELLOW/RED）与建议仓位，便于做分层统计

运行示例（PowerShell）：
  cd modules/compute-python
  python examples/geopolicy_eval.py --trade-date 2026-04-08 --topn 20

可选：
  python examples/geopolicy_eval.py --trade-date 2026-04-08 --base-url http://localhost:8080
"""

from __future__ import annotations

import argparse
import statistics
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

import requests


@dataclass
class PickRow:
    stock_code: str
    exchange_type: str
    final_score: float


def _get_json(base_url: str, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
    url = base_url.rstrip("/") + path
    r = requests.get(url, params=params, timeout=30)
    r.raise_for_status()
    j = r.json()
    # 后端统一 ApiResponse: {code, message, data}
    return j.get("data", j)


def _post_json(base_url: str, path: str, body: Dict[str, Any]) -> Any:
    url = base_url.rstrip("/") + path
    r = requests.post(url, json=body, timeout=120)
    r.raise_for_status()
    j = r.json()
    return j.get("data", j)


def get_latest_selector_result(
    base_url: str, trade_date: str, factor_version: Optional[str]
) -> Tuple[Dict[str, Any], List[PickRow]]:
    data = _get_json(
        base_url,
        "/api/selector/result",
        params={"tradeDate": trade_date, "factorVersion": factor_version},
    )
    items = data.get("items", []) or []
    rows: List[PickRow] = []
    for it in items:
        rows.append(
            PickRow(
                stock_code=str(it.get("stock_code", "")),
                exchange_type=str(it.get("exchange_type", "")),
                final_score=float(it.get("final_score") or 0.0),
            )
        )
    return data, rows


def get_close_series(
    base_url: str, symbol: str, exchange: str, start_date: str, end_date: str
) -> List[Dict[str, Any]]:
    # /api/market/kline 返回 List<Map>，包含 close/date 等字段（后端会做列名映射）
    data = _get_json(
        base_url,
        "/api/market/kline",
        params={
            "symbol": symbol,
            "exchange": exchange,
            "startDate": start_date,
            "endDate": end_date,
            "limit": 10,
        },
    )
    return list(data or [])


def _extract_close(row: Dict[str, Any]) -> Optional[float]:
    for k in ("close", "Close", "close_price", "closePrice"):
        v = row.get(k)
        if v is None:
            continue
        try:
            return float(v)
        except Exception:
            continue
    return None


def compute_next_trading_return(
    base_url: str, symbol: str, exchange: str, trade_date: str
) -> Optional[float]:
    # 取 trade_date 当天 + 后续几天 K 线，找到 trade_date 和下一条交易日的 close
    kl = get_close_series(base_url, symbol, exchange, trade_date, trade_date)
    if not kl:
        # 容错：有时 start=end 只返回 1 天；这里扩大窗口拉 10 条再匹配
        kl = _get_json(
            base_url,
            "/api/market/kline",
            params={"symbol": symbol, "exchange": exchange, "limit": 30},
        )
        kl = list(kl or [])

    # row 里可能没有日期字段；这里按列表顺序近似（后端按 tradeDate 升序）
    closes = [c for c in (_extract_close(r) for r in kl) if c is not None]
    if len(closes) < 2:
        return None
    c0, c1 = closes[-2], closes[-1]
    if c0 == 0:
        return None
    return (c1 / c0 - 1.0) * 100.0


def summarize_portfolio(returns: List[float]) -> Dict[str, Any]:
    if not returns:
        return {"n": 0}
    return {
        "n": len(returns),
        "mean_ret_pct": round(statistics.fmean(returns), 4),
        "median_ret_pct": round(statistics.median(returns), 4),
        "win_rate": round(sum(1 for r in returns if r > 0) / len(returns), 4),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--trade-date", required=True)
    ap.add_argument("--topn", type=int, default=20)
    ap.add_argument("--run-if-missing", action="store_true", help="若结果不存在则触发一次 run")
    args = ap.parse_args()

    base_url: str = args.base_url
    trade_date: str = args.trade_date
    topn: int = args.topn

    # classic（不传 factorVersion）
    classic_meta, classic_rows = get_latest_selector_result(base_url, trade_date, None)
    # geopolicy
    geo_meta, geo_rows = get_latest_selector_result(base_url, trade_date, "geopolicy_news_v1")

    if args.run_if_missing and not geo_rows:
        _post_json(
            base_url,
            "/api/selector/geopolicy-news/run",
            {"tradeDate": trade_date, "topN": max(50, topn), "persist": True},
        )
        geo_meta, geo_rows = get_latest_selector_result(base_url, trade_date, "geopolicy_news_v1")

    classic_pick = classic_rows[:topn]
    geo_pick = geo_rows[:topn]

    classic_rets: List[float] = []
    for r in classic_pick:
        ret = compute_next_trading_return(base_url, r.stock_code, r.exchange_type, trade_date)
        if ret is not None:
            classic_rets.append(ret)

    geo_rets: List[float] = []
    for r in geo_pick:
        ret = compute_next_trading_return(base_url, r.stock_code, r.exchange_type, trade_date)
        if ret is not None:
            geo_rets.append(ret)

    macro = geo_meta.get("macro") or {}
    print("== 日期 ==", trade_date)
    if macro:
        print(
            "== 宏观风险 ==",
            f"level={macro.get('risk_level')} pos={macro.get('recommended_position_pct')}",
            f"geoRisk={macro.get('geo_risk_index01')} newsSent={macro.get('news_sent_index01')} policy={macro.get('policy_score01')}",
        )

    print("\n== Classic 组合次日收益（TopN）==")
    print(summarize_portfolio(classic_rets))
    print("\n== Geopolicy 组合次日收益（TopN）==")
    print(summarize_portfolio(geo_rets))


if __name__ == "__main__":
    main()

