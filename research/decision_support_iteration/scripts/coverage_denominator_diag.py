"""只读：诊断覆盖率分母的构成（P0-1 修复依据）。

严格只读（SELECT/SHOW）。回答：
  1. stock_info 中有多少标的在 market_data 里从未出现（完全无行情）；
  2. 2026 年内 bar 数分布，有多少标的 < 50 根（长期停牌/新上市嫌疑）；
  3. 给定 as-of 日的分母在不同口径下分别是多少，以及各口径下的实际覆盖率。
"""
from __future__ import annotations
import os

import pymysql

DB = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
          database="quant_db", charset="utf8mb4")


def main() -> None:
    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    cur.execute("SELECT COUNT(*) FROM stock_info si WHERE NOT EXISTS "
                "(SELECT 1 FROM market_data m WHERE m.stock_code = si.stock_code)")
    print("stock_info 中在 market_data 完全无行的标的数:", cur.fetchone()[0])

    cur.execute("SELECT COUNT(DISTINCT stock_code) FROM market_data WHERE trade_date >= '2026-01-01'")
    print("2026 年 market_data 出现过的不同标的数:", cur.fetchone()[0])

    cur.execute("""SELECT bars, COUNT(*) n FROM (
                     SELECT stock_code, COUNT(*) bars FROM market_data
                     WHERE trade_date >= '2026-01-01' GROUP BY stock_code) t
                   GROUP BY bars ORDER BY bars LIMIT 12""")
    print("2026 年 bar 数最低的若干档 (bars, 标的数):", cur.fetchall())

    cur.execute("""SELECT COUNT(*) FROM (
                     SELECT stock_code, COUNT(*) bars FROM market_data
                     WHERE trade_date >= '2026-01-01' GROUP BY stock_code) t WHERE bars < 50""")
    print("  2026 年 bar 数 < 50 的标的数:", cur.fetchone()[0])

    cur.execute("""SELECT COUNT(*) FROM (
                     SELECT stock_code, COUNT(*) bars FROM market_data
                     WHERE trade_date >= '2026-01-01' GROUP BY stock_code) t WHERE bars = 0""")

    print("\n=== 各口径分母 vs 实际覆盖率（as-of 2026-08-24） ===")
    d = "2026-08-24"
    cur.execute("SELECT COUNT(DISTINCT stock_code) FROM market_data WHERE trade_date = %s", (d,))
    covered = cur.fetchone()[0]
    print(f"  当日实际有行情的标的 (covered) = {covered}")

    variants = {
        "V0 现状: stock_info status=1 且 list_date<=d":
            "SELECT COUNT(*) FROM stock_info WHERE status=1 AND list_date IS NOT NULL AND list_date <= %s",
        "V1 = V0 且该标的在 d 之前曾有行情":
            """SELECT COUNT(*) FROM stock_info si WHERE si.status=1 AND si.list_date IS NOT NULL
               AND si.list_date <= %s AND EXISTS (SELECT 1 FROM market_data m
                   WHERE m.stock_code=si.stock_code AND m.trade_date <= %s)""",
        "V2 = V0 且该标的在 d 之前 250 个自然日内曾有行情":
            """SELECT COUNT(*) FROM stock_info si WHERE si.status=1 AND si.list_date IS NOT NULL
               AND si.list_date <= %s AND EXISTS (SELECT 1 FROM market_data m
                   WHERE m.stock_code=si.stock_code AND m.trade_date <= %s
                     AND m.trade_date >= DATE_SUB(%s, INTERVAL 250 DAY))""",
    }
    for label, sql in variants.items():
        if sql.count("%s") == 1:
            cur.execute(sql, (d,))
        elif sql.count("%s") == 3:
            cur.execute(sql, (d, d, d))
        else:
            cur.execute(sql, (d, d))
        exp = cur.fetchone()[0]
        ratio = covered / exp if exp else 0
        verdict = "PASS" if ratio >= 0.95 else "BLOCKED"
        print(f"  {label}: expected={exp}  coverage={ratio:.4f}  -> {verdict}")

    cur.execute("""SELECT si.stock_code, si.stock_name, si.list_date, si.status FROM stock_info si
                   WHERE NOT EXISTS (SELECT 1 FROM market_data m WHERE m.stock_code = si.stock_code)
                   LIMIT 10""")
    print("\n  完全无行情的 stock_info 样本:")
    for r in cur.fetchall():
        print("   ", r)

    conn.close()


if __name__ == "__main__":
    main()
