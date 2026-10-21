"""只读（快速版）：为覆盖率分母的「后随活跃窗口」标定窗口长度 W。

active(d, W) = {(code,exchange) : 在 [d-W 自然日, d] 内至少有一根 bar}
expected(d)  = active(d,W) ∩ {status=1 且 list_date<=d}
coverage     = covered(d) / expected(d)     阈值 KEEP 0.95

用「该 key 的 last_bar_date >= d-W」做等价判定（last_bar_date 是单调的），
因此只需一次全量聚合，避免逐日重查。
"""
from __future__ import annotations
import os

import datetime as dt
import statistics

import pymysql

DB = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
          database="quant_db", charset="utf8mb4")
WINDOWS = [60, 120, 180, 250]


def main() -> None:
    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    cur.execute("""SELECT CONCAT(stock_code,'|',exchange_type) k, MAX(trade_date) last_bar
                   FROM market_data GROUP BY stock_code, exchange_type""")
    last_bar = {r[0]: r[1] for r in cur.fetchall()}
    print(f"market_data 不同 (code,exchange) 键: {len(last_bar)}")

    cur.execute("SELECT CONCAT(stock_code,'|',exchange_type) k, list_date FROM stock_info WHERE status=1")
    info = {r[0]: r[1] for r in cur.fetchall()}
    print(f"stock_info status=1 键: {len(info)}")

    cur.execute("""SELECT trade_date, COUNT(DISTINCT CONCAT(stock_code,'|',exchange_type)) c
                   FROM market_data WHERE trade_date>='2026-01-01' AND trade_date<='2026-08-31'
                   GROUP BY trade_date ORDER BY trade_date""")
    days = [(r[0], r[1]) for r in cur.fetchall()]
    print(f"正常期交易日: {len(days)}  ({days[0][0]} .. {days[-1][0]})\n")

    print(f"{'W':>5} {'min':>9} {'p25':>9} {'median':>9} {'max':>9} {'pass>=.95':>11}")
    for W in WINDOWS:
        ratios = []
        for d, covered in days:
            lo = d - dt.timedelta(days=W)
            exp = sum(1 for k, ld in info.items()
                      if ld is not None and ld <= d and last_bar.get(k) is not None
                      and last_bar[k] >= lo)
            ratios.append(covered / exp if exp else 0.0)
        q = statistics.quantiles(ratios, n=4)
        print(f"{W:>5} {min(ratios):>9.4f} {q[0]:>9.4f} {statistics.median(ratios):>9.4f} "
              f"{max(ratios):>9.4f} {sum(1 for r in ratios if r >= 0.95):>8}/{len(ratios)}")

    print("\n=== 断崖检出能力（W=120）：真实数据断崖 2026-09-08 ===")
    W = 120
    for d in [dt.date(2026, 9, 7), dt.date(2026, 9, 8), dt.date(2026, 10, 12)]:
        cur.execute("""SELECT COUNT(DISTINCT CONCAT(stock_code,'|',exchange_type)) FROM market_data
                       WHERE trade_date=%s""", (d,))
        covered = cur.fetchone()[0]
        lo = d - dt.timedelta(days=W)
        exp = sum(1 for k, ld in info.items()
                  if ld is not None and ld <= d and last_bar.get(k) is not None and last_bar[k] >= lo)
        ratio = covered / exp if exp else 0.0
        print(f"  {d}  covered={covered:>5}  expected={exp:>5}  coverage={ratio:.4f}  "
              f"{'PASS' if ratio >= 0.95 else 'BLOCKED(正确阻断)'}")

    conn.close()


if __name__ == "__main__":
    main()
