"""诊断：新解锁区间里「零候选」是阈值问题还是数据问题。

`POST /candidates` 返回 `NO_ELIGIBLE_CANDIDATE` 只说明「阈值内无人」，
不区分「分数普遍低」与「池子太小」。这里只看数据侧分布（只读 SELECT），
不改阈值——阈值 35.0 是冻结基线，改动需走标定流程并留痕。
"""
from __future__ import annotations
import os

import sys

import numpy as np
import pandas as pd
import pymysql

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

START, END = "2026-08-01", "2026-10-12"

DSN = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
           database="quant_db", charset="utf8mb4")


def main() -> int:
    conn = pymysql.connect(**DSN)
    df = pd.read_sql("""
        SELECT trade_date, COUNT(*) AS bars,
               SUM(CASE WHEN amount >= 30000 THEN 1 ELSE 0 END) AS liquid_bars
        FROM market_data
        WHERE trade_date BETWEEN %s AND %s
        GROUP BY trade_date ORDER BY trade_date
    """, conn, params=(START, END))
    conn.close()
    df["trade_date"] = pd.to_datetime(df["trade_date"]).dt.date
    print("=== 每日 bar 数（断崖可见）===")
    prev = None
    for _, r in df.iterrows():
        mark = ""
        if prev is not None and r["bars"] < prev * 0.5:
            mark = "   <== 断崖"
        print(f"  {r['trade_date']}  bars={r['bars']:>6}  额>=3亿={r['liquid_bars']:>6}{mark}")
        prev = r["bars"]
    return 0


if __name__ == "__main__":
    sys.exit(main())
