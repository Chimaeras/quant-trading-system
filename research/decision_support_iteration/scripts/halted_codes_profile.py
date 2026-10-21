"""「中途停止交易」代码的定性（只读 SELECT）。

动机
----
离线评价在 `offline_metrics.py:120` 用
    tradable = sub[~sub["unfillable"]]
把 upfillable（次日无数据 / 次日一字板）的选股**整体剔除**再算均值。
若「选完之后停牌/退市」的标的因此系统性退出样本，则组合收益会被高估
（停牌多伴坏消息）。

本脚本不重跑评价，只回答一个前置问题：
    这些「在评价窗口内中途消失」的代码，到底是**退市**还是**长期停牌**？
    以及它们的最后交易日之后是否真的再无 bar？

判定：对每个代码取最后 8 个有 bar 的交易日，看是否连续到某个日期后彻底停止；
并检查其在 `stock_info` 中是否 status=1（若 status=1 却停止交易 ⇒ 不是退市，
而是停牌或数据缺失）。
"""
from __future__ import annotations
import os

import datetime as dt
import json
import pathlib
import sys

import pymysql

OUT = pathlib.Path(__file__).resolve().parents[1] / "halted_codes_profile.json"
DSN = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
           database="quant_db", charset="utf8mb4")


def main() -> int:
    conn = pymysql.connect(**DSN)
    cur = conn.cursor()
    out: dict = {}
    try:
        # 窗口内最后交易日早于窗口末尾 20 天的代码
        win_start, win_end = dt.date(2026, 1, 1), dt.date(2026, 8, 31)
        cur.execute("SELECT stock_code, MAX(trade_date) FROM market_data GROUP BY stock_code")
        last = {str(c): d for c, d in cur.fetchall()}
        cands = sorted(c for c, d in last.items()
                       if d and win_start < d < win_end - dt.timedelta(days=20))

        # 全市场的最后交易日（判断是否只是数据整体到某日为止）
        cur.execute("SELECT MAX(trade_date) FROM market_data")
        market_last = cur.fetchone()[0]

        rows = []
        for code in cands:
            cur.execute("""SELECT trade_date FROM market_data WHERE stock_code=%s
                           GROUP BY trade_date ORDER BY trade_date DESC LIMIT 8""", (code,))
            tail = [str(r[0]) for r in cur.fetchall()]
            cur.execute("""SELECT exchange_type, stock_name, list_date, status
                           FROM stock_info WHERE stock_code=%s""", (code,))
            si = cur.fetchall()
            rows.append({
                "code": code,
                "last_trade_date": str(last[code]),
                "tail_trade_dates": tail,
                "in_stock_info": [{"ex": e, "name": n, "list_date": str(ld), "status": int(st)}
                                  for e, n, ld, st in si],
                "days_since_last_bar": (market_last - last[code]).days,
            })

        out["market_last_trade_date"] = str(market_last)
        out["n_candidates"] = len(rows)
        out["n_still_status1"] = sum(
            1 for r in rows if any(x["status"] == 1 for x in r["in_stock_info"]))
        out["n_absent_from_stock_info"] = sum(1 for r in rows if not r["in_stock_info"])
        out["rows"] = rows
    finally:
        conn.close()

    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"market last trade date      : {out['market_last_trade_date']}")
    print(f"candidate codes             : {out['n_candidates']}")
    print(f"  still status=1 in stock_info: {out['n_still_status1']}")
    print(f"  absent from stock_info      : {out['n_absent_from_stock_info']}")
    print()
    print(f"{'code':8s} {'last_bar':11s} {'days_ago':>8s}  tail / name")
    for r in rows:
        nm = r["in_stock_info"][0]["name"] if r["in_stock_info"] else "<不在主表>"
        print(f"{r['code']:8s} {r['last_trade_date']:11s} {r['days_since_last_bar']:8d}  "
              f"{r['tail_trade_dates'][:4]} {nm}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
