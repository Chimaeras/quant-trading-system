"""诊断「数据断崖」：2026-09-08 起每日 bar 数由 ~5456 掉到 ~249。

关键问题不是「数字变小了」，而是**性质**：
  (a) 行情源整体停更（那么 249 也是陈旧残留）？
  (b) 入库管线崩了一半（只剩部分标的在更新）？
  (c) 断崖前后代码集合是否同一批（若是同一小批，说明是管线选择性问题）？
这决定了「日频选股还能不能跑」，属于数据可信度问题，优先级最高。

全部只读 SELECT。
"""
from __future__ import annotations
import os

import sys

import pymysql

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DSN = dict(host="127.0.0.1", user="root", password=os.environ.get("DB_PASSWORD", ""),
           database="quant_db", charset="utf8mb4")


def q(cur, sql, args=None):
    cur.execute(sql, args or ())
    return cur.fetchall()


def main() -> int:
    c = pymysql.connect(**DSN)
    cur = c.cursor()

    print("=== 1) 全表日期边界与每日 bar 数（尾段）===")
    for r in q(cur, """
        SELECT trade_date, COUNT(*) bars, COUNT(DISTINCT stock_code) codes,
               MAX(created_at) last_insert
        FROM market_data WHERE trade_date >= '2026-08-25'
        GROUP BY trade_date ORDER BY trade_date
    """):
        print(f"  {r[0]}  bars={r[1]:>5}  codes={r[2]:>5}  last_insert={r[4] if len(r)>4 else r[3]}")

    print("\n=== 2) 断崖后那 ~249 只的交易所分布 ===")
    for r in q(cur, """
        SELECT exchange_type, COUNT(DISTINCT stock_code) codes, COUNT(*) bars
        FROM market_data WHERE trade_date >= '2026-09-08'
        GROUP BY exchange_type ORDER BY codes DESC
    """):
        print(f"  {r[0]:<6} codes={r[1]:>5} bars={r[2]:>6}")

    print("\n=== 3) 断崖后集合 vs 断崖前集合 的重叠 ===")
    after = set()
    for (code,) in q(cur, "SELECT DISTINCT stock_code FROM market_data WHERE trade_date >= '2026-09-08'"):
        after.add(code)
    before = set()
    for (code,) in q(cur, "SELECT DISTINCT stock_code FROM market_data WHERE trade_date BETWEEN '2026-08-25' AND '2026-09-07'"):
        before.add(code)
    print(f"  断崖前 codes={len(before)}  断崖后 codes={len(after)}")
    print(f"  交集={len(after & before)}  仅断崖后={len(after - before)}  仅断崖前={len(before - after)}")
    extra = sorted(after - before)[:15]
    if extra:
        print(f"  仅出现在断崖后的代码样例: {extra}")

    print("\n=== 4) 断崖后代码是否在 stock_info 中、是否 ST ===")
    if after:
        placeholders = ",".join(["%s"] * len(after))
        rows = q(cur, f"""
            SELECT COUNT(*) total,
                   SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END) active,
                   SUM(CASE WHEN UPPER(stock_name) LIKE '%%ST%%' THEN 1 ELSE 0 END) st_named
            FROM stock_info WHERE stock_code IN ({placeholders})
        """, tuple(after))
        print(f"  stock_info 命中: total={rows[0][0]} active={rows[0][1]} ST命名={rows[0][2]}")
        # 断崖后的代码里，有多少根本不在 stock_info
        rows2 = q(cur, f"SELECT stock_code FROM stock_info WHERE stock_code IN ({placeholders})",
                  tuple(after))
        ininfo = {r[0] for r in rows2}
        print(f"  不在 stock_info 的: {sorted(after - ininfo)[:15]}")

    print("\n=== 5) 是否存在「入库/更新任务」类表并有无失败记录 ===")
    tabs = q(cur, """
        SELECT table_name FROM information_schema.tables
        WHERE table_schema='quant_db'
          AND (table_name LIKE '%%update%%' OR table_name LIKE '%%ingest%%'
               OR table_name LIKE '%%task%%' OR table_name LIKE '%%job%%'
               OR table_name LIKE '%%sync%%' OR table_name LIKE '%%fetch%%')
        ORDER BY table_name
    """)
    print(f"  候选表: {[t[0] for t in tabs]}")
    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
