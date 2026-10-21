"""只读：完整测算修正分母后的逐日覆盖率分布（P0-1 修复的标定依据）。

expected(d, W=120) = stock_info 中 status=1 且 list_date<=d，
                     且该标的在 [d-120 自然日, d] 内有 market_data bar 的标的数
covered(d)         = d 当日 market_data 的 distinct stock_code 数

目的：判断 0.95 阈值在「修正分母」后是否可达；若不可达，量化真实上界。
"""
from __future__ import annotations
import os

import statistics

import pymysql

DB = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
          database="quant_db", charset="utf8mb4")
W = 120


def main() -> None:
    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    # 逐日：当日 bar 数
    cur.execute("""SELECT trade_date, COUNT(DISTINCT stock_code) FROM market_data
                   WHERE trade_date >= '2026-01-01' AND trade_date <= '2026-08-31'
                   GROUP BY trade_date ORDER BY trade_date""")
    days = cur.fetchall()

    # 对每个 as-of 日计算 expected(W)
    active_since: dict[str, list] = {}
    cur.execute("""SELECT stock_code, MAX(trade_date) FROM market_data
                   GROUP BY stock_code""")
    last_bar = {r[0]: r[1] for r in cur.fetchall()}

    cur.execute("""SELECT stock_code, list_date FROM stock_info
                   WHERE status=1 AND list_date IS NOT NULL""")
    info = cur.fetchall()

    import datetime as dt

    rows = []
    for d, covered in days:
        cutoff = d - dt.timedelta(days=W)
        exp = 0
        for code, ld in info:
            if ld is None or ld > d:
                continue
            lb = last_bar.get(code)
            if lb is None:
                continue
            # 该标的在 [cutoff, d] 内有 bar：用 last_bar>=cutoff 近似（末日仍在窗口内）
            if lb >= cutoff:
                exp += 1
        rows.append((d, covered, exp, covered / exp if exp else 0.0))
    conn.close()

    ratios = [r[3] for r in rows if r[3] > 0]
    print(f"样本交易日数: {len(rows)}  (2026-01-01..2026-08-31, W={W})")
    print(f"coverage 分布: min={min(ratios):.4f} p25={statistics.quantiles(ratios, n=4)[0]:.4f} "
          f"median={statistics.median(ratios):.4f} p75={statistics.quantiles(ratios, n=4)[2]:.4f} "
          f"max={max(ratios):.4f}")
    n_pass = sum(1 for r in ratios if r >= 0.95)
    print(f"达到 0.95 的日数: {n_pass}/{len(ratios)}")
    print(f"expected 范围: {min(r[2] for r in rows)} .. {max(r[2] for r in rows)}")
    print(f"covered  范围: {min(r[1] for r in rows)} .. {max(r[1] for r in rows)}")

    print("\n逐日明细（前 5 与后 5）:")
    for r in rows[:5] + rows[-5:]:
        print(f"  {r[0]}  covered={r[1]:>5}  expected={r[2]:>5}  ratio={r[3]:.4f}")

    # 缺口构成
    worst = min(rows, key=lambda r: r[3])
    print(f"\n最差日 {worst[0]}: covered={worst[1]} expected={worst[2]} 缺口={worst[2]-worst[1]}")
    best = max(rows, key=lambda r: r[3])
    print(f"最好日 {best[0]}: covered={best[1]} expected={best[2]} 缺口={best[2]-best[1]}")


if __name__ == "__main__":
    main()
