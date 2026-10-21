"""只读：定位 284 个「同一 stock_code 跨多个 exchange_type」的重复条目。

Java 端 `infoByKey` 以 `stock_code|exchange_type` 为键（T1CandidateService.java:134,141-146），
因此这 284 个重复条目会计入 expected 分母；若其中一个 exchange_type 在 market_data 中
永远没有 bar，则分母被系统性放大，覆盖率闸不可达。
"""
from __future__ import annotations
import os

import pymysql

DB = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
          database="quant_db", charset="utf8mb4")


def main() -> None:
    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    cur.execute("SELECT COUNT(DISTINCT stock_code) FROM market_data")
    print("market_data 去重 stock_code 数:", cur.fetchone()[0])
    cur.execute("SELECT COUNT(DISTINCT CONCAT(stock_code,'|',exchange_type)) FROM market_data")
    print("market_data 去重 code|exchange 数:", cur.fetchone()[0])

    cur.execute("""SELECT exchange_type, COUNT(*) FROM stock_info GROUP BY exchange_type""")
    print("\nstock_info 按 exchange_type:", cur.fetchall())

    print("\n=== 284 个重复 stock_code 的样例（含两行 exchange_type 与各自 market_data bar 数）===")
    cur.execute("""SELECT stock_code, GROUP_CONCAT(CONCAT(exchange_type,':',stock_name) ORDER BY exchange_type)
                   FROM stock_info GROUP BY stock_code
                   HAVING COUNT(DISTINCT exchange_type) > 1 LIMIT 10""")
    samples = cur.fetchall()
    for code, desc in samples:
        cur.execute("""SELECT exchange_type, COUNT(*) bars FROM market_data
                       WHERE stock_code=%s GROUP BY exchange_type""", (code,))
        bars = cur.fetchall()
        print(f"  {code}: stock_info=[{desc}]  market_data_bars={bars}")

    print("\n=== 关键：重复 code 中，有几个 exchange_type 组合在 market_data 里只有 1 种？ ===")
    cur.execute("""SELECT COUNT(*) FROM (
                     SELECT si.stock_code, COUNT(DISTINCT si.exchange_type) n_exp,
                            COUNT(DISTINCT m.exchange_type) n_act
                     FROM stock_info si
                     LEFT JOIN market_data m ON m.stock_code = si.stock_code
                       AND m.exchange_type = si.exchange_type
                     GROUP BY si.stock_code
                     HAVING n_exp > 1 AND n_act < n_exp) t""")
    print("  exchange_type 组合在 market_data 中不完整的 code 数:", cur.fetchone()[0])

    cur.execute("""SELECT COUNT(*) FROM stock_info si WHERE NOT EXISTS (
                     SELECT 1 FROM market_data m WHERE m.stock_code = si.stock_code
                       AND m.exchange_type = si.exchange_type)""")
    print("  stock_info 中 (code,exchange) 在 market_data 完全无 bar 的行数:", cur.fetchone()[0])

    print("\n=== 修正分母模拟（as-of 2026-08-24）===")
    d = "2026-08-24"
    cur.execute("SELECT COUNT(DISTINCT CONCAT(stock_code,'|',exchange_type)) FROM market_data WHERE trade_date=%s", (d,))
    covered = cur.fetchone()[0]
    cur.execute("""SELECT COUNT(*) FROM stock_info WHERE status=1 AND list_date IS NOT NULL AND list_date<=%s""", (d,))
    e0 = cur.fetchone()[0]
    cur.execute("""SELECT COUNT(*) FROM stock_info si WHERE si.status=1 AND si.list_date<=%s
                   AND EXISTS (SELECT 1 FROM market_data m WHERE m.stock_code=si.stock_code
                               AND m.exchange_type=si.exchange_type)""", (d,))
    e1 = cur.fetchone()[0]
    print(f"  E0 现状(全部 stock_info 行)      = {e0}  coverage={covered/e0:.4f}")
    print(f"  E1 仅保留曾在 market_data 出现的 (code,exchange) = {e1}  coverage={covered/e1:.4f}  "
          f"{'PASS' if covered/e1>=0.95 else 'BLOCKED'}")
    print(f"  E2 仅保留 (code,exchange) 且 d 之前 120 日内有 bar = ", end="")
    cur.execute("""SELECT COUNT(*) FROM stock_info si WHERE si.status=1 AND si.list_date<=%s
                   AND EXISTS (SELECT 1 FROM market_data m WHERE m.stock_code=si.stock_code
                               AND m.exchange_type=si.exchange_type AND m.trade_date<=%s
                               AND m.trade_date>=DATE_SUB(%s, INTERVAL 120 DAY))""", (d, d, d))
    e2 = cur.fetchone()[0]
    print(f"{e2}  coverage={covered/e2:.4f}  {'PASS' if covered/e2>=0.95 else 'BLOCKED'}")

    conn.close()


if __name__ == "__main__":
    main()
