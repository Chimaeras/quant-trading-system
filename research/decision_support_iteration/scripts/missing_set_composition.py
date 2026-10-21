"""只读：精确定位「stock_info 在市标的」与「当日实际有 bar 标的」的差集构成。

这是 P0-1 分母口径修复的最终依据。对 as-of 日 d：
  E = stock_info 中 status=1 且 list_date<=d 的标的
  C = d 当日 market_data 的标的
  M = E \ C（缺失集，即覆盖率缺口）
把 M 按「最后一次 bar 距 d 的天数」分桶，判断 M 主要是
  (a) 长期休眠/退市嫌疑（last_bar 很旧）
  (b) 近期仍活跃但当日缺 bar（真正的数据缺失或停牌）
"""
from __future__ import annotations
import os

import datetime as dt

import pymysql

DB = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
          database="quant_db", charset="utf8mb4")

BUCKETS = [(0, 5), (6, 20), (21, 60), (61, 120), (121, 250), (251, 500), (501, 100000)]


def main() -> None:
    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    for d in [dt.date(2026, 8, 24), dt.date(2026, 6, 30), dt.date(2026, 4, 30)]:
        cur.execute("""SELECT stock_code FROM stock_info
                       WHERE status=1 AND list_date IS NOT NULL AND list_date<=%s""", (d,))
        E = {r[0] for r in cur.fetchall()}
        cur.execute("SELECT DISTINCT stock_code FROM market_data WHERE trade_date=%s", (d,))
        C = {r[0] for r in cur.fetchall()}
        M = E - C
        print(f"\n===== as-of {d} =====")
        print(f"  E (stock_info status=1, list_date<=d) = {len(E)}")
        print(f"  C (当日有 bar)                        = {len(C)}")
        print(f"  M (缺失集)                            = {len(M)}")
        print(f"  coverage = {len(C)/len(E):.4f}")

        # 缺失集按 last_bar 分桶
        if not M:
            continue
        codes = tuple(M)
        cur.execute("""SELECT stock_code, MAX(trade_date) FROM market_data
                       WHERE stock_code IN %s GROUP BY stock_code""", (codes,))
        last = dict(cur.fetchall())
        never = [c for c in M if c not in last]
        print(f"  M 中完全无任何 bar 的: {len(never)}")
        print("  M 的 last_bar 距 d 的分桶:")
        total = 0
        for lo, hi in BUCKETS:
            n = sum(1 for c in M if c in last and lo <= (d - last[c]).days <= hi)
            total += n
            if n:
                print(f"    {lo:>4}..{hi if hi < 100000 else 'inf':<6} 天: {n:>4}  ({n/len(M)*100:5.1f}%)")
        print(f"    合计 {total} (+从未交易 {len(never)} = {total+len(never)})")

        # 关键对照：M 中有多少在 d 之后 10 个交易日内恢复交易（= 停牌嫌疑）
        cur.execute("""SELECT DISTINCT trade_date FROM market_data
                       WHERE trade_date > %s ORDER BY trade_date LIMIT 10""", (d,))
        after = [r[0] for r in cur.fetchall()]
        if after:
            cur.execute("""SELECT DISTINCT stock_code FROM market_data
                           WHERE trade_date BETWEEN %s AND %s""", (after[0], after[-1]))
            resumed = {r[0] for r in cur.fetchall()}
            n_res = len(M & resumed)
            print(f"  M 中在 d 之后 10 个交易日内恢复有 bar 的: {n_res} "
                  f"(停牌嫌疑, 占 M 的 {n_res/len(M)*100:.1f}%)")
            print(f"  M 中此后一直无 bar 的: {len(M) - n_res} (退市/长期休眠嫌疑)")
            # 剔除休眠后的修正分母
            E2 = len(E) - (len(M) - n_res)
            print(f"  => 修正分母 E' = {E2}, coverage' = {len(C)/E2:.4f}")

    conn.close()


if __name__ == "__main__":
    main()
