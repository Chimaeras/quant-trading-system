"""只读：为覆盖率分母口径（剔除 as-of 休眠标的）标定回看窗口。

定义（透明、可复现、无前视）：
  expected(d, W) = stock_info 中 status=1 且 list_date<=d
                   且该标的在 [d-W 自然日, d] 内至少有一根 market_data bar 的标的数
  coverage(d)    = (d 当日实际有合格 bar 的标的数) / expected(d, W)

只用 <= d 的数据，无前视。阈值保持 0.95 不变（本轮不放宽任何闸门）。
"""
from __future__ import annotations
import os

import statistics

import pymysql

DB = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
          database="quant_db", charset="utf8mb4")

WINDOWS = [120, 180, 250, 365]
SAMPLE_DAYS = [
    "2026-03-31", "2026-04-30", "2026-05-29", "2026-06-30",
    "2026-07-15", "2026-07-31", "2026-08-04", "2026-08-24", "2026-09-07",
]


def main() -> None:
    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    print("W=回看自然日窗口；coverage = covered / expected(d,W)；阈值 0.95")
    print(f"{'signal_date':<12} {'covered':>8} " + " ".join(f"W={w:<7}" for w in WINDOWS))
    header_extra = " ".join(f"{'exp/ratio':>14}" for _ in WINDOWS)
    print(f"{'':<12} {'':>8} {header_extra}")

    summary: dict[int, list[float]] = {w: [] for w in WINDOWS}
    for d in SAMPLE_DAYS:
        cur.execute("SELECT COUNT(DISTINCT stock_code) FROM market_data WHERE trade_date=%s", (d,))
        covered = cur.fetchone()[0]
        cells = []
        for w in WINDOWS:
            cur.execute(
                """SELECT COUNT(*) FROM stock_info si
                   WHERE si.status=1 AND si.list_date IS NOT NULL AND si.list_date<=%s
                     AND EXISTS (SELECT 1 FROM market_data m WHERE m.stock_code=si.stock_code
                                 AND m.trade_date<=%s AND m.trade_date>=DATE_SUB(%s, INTERVAL %s DAY))""",
                (d, d, d, w))
            exp = cur.fetchone()[0]
            ratio = covered / exp if exp else 0.0
            summary[w].append(ratio)
            cells.append(f"{exp:>6}/{ratio:.4f}")
        print(f"{d:<12} {covered:>8} " + " ".join(f"{c:>14}" for c in cells))

    print("\n=== 各窗口在样本日上的表现 ===")
    for w in WINDOWS:
        rs = summary[w]
        n_pass = sum(1 for r in rs if r >= 0.95)
        print(f"  W={w:<4} min={min(rs):.4f} median={statistics.median(rs):.4f} "
              f"max={max(rs):.4f}  pass(>=0.95)={n_pass}/{len(rs)}")

    # 现状对照
    print("\n=== 现状口径 (W=∞, 即 stock_info 全量) 对照 ===")
    for d in SAMPLE_DAYS:
        cur.execute("SELECT COUNT(DISTINCT stock_code) FROM market_data WHERE trade_date=%s", (d,))
        covered = cur.fetchone()[0]
        cur.execute("""SELECT COUNT(*) FROM stock_info WHERE status=1 AND list_date IS NOT NULL
                       AND list_date<=%s""", (d,))
        exp = cur.fetchone()[0]
        print(f"  {d}  expected={exp}  coverage={covered/exp:.4f}  "
              f"{'PASS' if covered/exp >= 0.95 else 'BLOCKED'}")

    conn.close()


if __name__ == "__main__":
    main()
