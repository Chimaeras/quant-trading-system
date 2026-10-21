"""只读：量化「停牌」是否是覆盖率缺口的全部来源（P0-1 标定关键证据）。

判别停牌的无前视定义：
  标的 c 在 d 日「疑似停牌」  <=>  c 在 d-5 交易日有 bar 且 c 在 d+5 交易日有 bar，
                                  但 c 在 d 日没有 bar。
（只用 d 前后各 5 个交易日的存在性，不涉及任何价格或标签，故不引入前视偏差；
 仅用于离线诊断，不进入生产选股逻辑。）

若「疑似停牌数」≈ 缺口数，则缺口是市场结构（停牌）而非数据缺失，
0.95 阈值在含停牌分母下不可达。
"""
from __future__ import annotations
import os

import statistics

import pymysql

DB = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
          database="quant_db", charset="utf8mb4")
K = 5


def main() -> None:
    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    cur.execute("""SELECT DISTINCT trade_date FROM market_data
                   WHERE trade_date >= '2026-01-01' AND trade_date <= '2026-08-31'
                   ORDER BY trade_date""")
    days = [r[0] for r in cur.fetchall()]
    print(f"交易日 {len(days)} 天: {days[0]} .. {days[-1]}")

    # 每日标的集合
    sets: dict = {}
    cur.execute("""SELECT trade_date, stock_code FROM market_data
                   WHERE trade_date >= '2025-12-01' AND trade_date <= '2026-09-30'""")
    for d, c in cur.fetchall():
        sets.setdefault(d, set()).add(c)

    # as-of 分母：status=1 且 list_date<=d
    cur.execute("SELECT stock_code, list_date FROM stock_info WHERE status=1 AND list_date IS NOT NULL")
    info = cur.fetchall()

    print(f"\n{'signal_date':<12}{'cover':>7}{'exp':>7}{'gap':>6}{'susp':>7}{'gap-susp':>9}{'cov':>9}{'cov_noSusp':>11}")
    rows = []
    for i in range(K, len(days) - K):
        d = days[i]
        prev, nxt = days[i - K], days[i + K]
        present = sets.get(d, set())
        both = sets.get(prev, set()) & sets.get(nxt, set())
        susp = len(both - present)
        covered = len(present)
        exp = sum(1 for code, ld in info if ld <= d and code in both)
        gap = exp - covered
        cov = covered / exp if exp else 0.0
        cov_ns = covered / (exp - susp) if exp - susp > 0 else 0.0
        rows.append((d, covered, exp, gap, susp, cov, cov_ns))
        if i % 20 == 0:
            print(f"{str(d):<12}{covered:>7}{exp:>7}{gap:>6}{susp:>7}{gap-susp:>9}{cov:>9.4f}{cov_ns:>11.4f}")

    c1 = [r[5] for r in rows]
    c2 = [r[6] for r in rows]
    print("\n=== 汇总 ===")
    print(f"  含停牌分母 coverage: min={min(c1):.4f} median={statistics.median(c1):.4f} max={max(c1):.4f} "
          f"pass(>=0.95)={sum(1 for x in c1 if x>=0.95)}/{len(c1)}")
    print(f"  剔除停牌分母 coverage: min={min(c2):.4f} median={statistics.median(c2):.4f} max={max(c2):.4f} "
          f"pass(>=0.95)={sum(1 for x in c2 if x>=0.95)}/{len(c2)}")
    gaps = [r[3] for r in rows]
    susps = [r[4] for r in rows]
    print(f"  缺口 gap: median={statistics.median(gaps):.0f}  range={min(gaps)}..{max(gaps)}")
    print(f"  疑似停牌 susp: median={statistics.median(susps):.0f}  range={min(susps)}..{max(susps)}")
    resid = [r[3] - r[4] for r in rows]
    print(f"  残差 gap-susp: median={statistics.median(resid):.0f}  range={min(resid)}..{max(resid)}")

    conn.close()


if __name__ == "__main__":
    main()
