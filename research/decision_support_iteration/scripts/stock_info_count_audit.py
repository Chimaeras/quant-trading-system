"""只读：核对 stock_info 的计数口径——行数 vs 去重证券数，以及重复码的存在。

关键问题：Java 代码用 `infoByKey`（Map，按 code|exchange 去重）计算 expected，
因此我的 SQL 必须同样按 (stock_code, exchange_type) 去重，否则分母被重复行放大。
"""
from __future__ import annotations
import os

import pymysql

DB = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
          database="quant_db", charset="utf8mb4")


def main() -> None:
    conn = pymysql.connect(**DB)
    cur = conn.cursor()

    checks = [
        ("行数 COUNT(*)", "SELECT COUNT(*) FROM stock_info"),
        ("去重 code|exchange", "SELECT COUNT(DISTINCT CONCAT(stock_code,'|',exchange_type)) FROM stock_info"),
        ("去重 stock_code", "SELECT COUNT(DISTINCT stock_code) FROM stock_info"),
        ("status=1 行数", "SELECT COUNT(*) FROM stock_info WHERE status=1"),
        ("status=1 去重 code|exchange",
         "SELECT COUNT(DISTINCT CONCAT(stock_code,'|',exchange_type)) FROM stock_info WHERE status=1"),
        ("status=1 且 list_date 非空 去重",
         "SELECT COUNT(DISTINCT CONCAT(stock_code,'|',exchange_type)) FROM stock_info "
         "WHERE status=1 AND list_date IS NOT NULL"),
        ("status=1 且 list_date<=2026-08-24 去重",
         "SELECT COUNT(DISTINCT CONCAT(stock_code,'|',exchange_type)) FROM stock_info "
         "WHERE status=1 AND list_date IS NOT NULL AND list_date<='2026-08-24'"),
        ("status!=1 行数", "SELECT COUNT(*) FROM stock_info WHERE status<>1"),
        ("status IS NULL 行数", "SELECT COUNT(*) FROM stock_info WHERE status IS NULL"),
        ("list_date IS NULL 行数", "SELECT COUNT(*) FROM stock_info WHERE list_date IS NULL"),
    ]
    for label, sql in checks:
        cur.execute(sql)
        print(f"  {label:<48} = {cur.fetchone()[0]}")

    cur.execute("""SELECT stock_code, exchange_type, COUNT(*) n FROM stock_info
                   GROUP BY stock_code, exchange_type HAVING n>1 ORDER BY n DESC LIMIT 10""")
    dups = cur.fetchall()
    print(f"\n  (code,exchange) 重复组数: {len(dups)}")
    for r in dups:
        print("   ", r)

    cur.execute("""SELECT COUNT(*) FROM (SELECT stock_code FROM stock_info
                   GROUP BY stock_code HAVING COUNT(DISTINCT exchange_type)>1) t""")
    print(f"  同一 code 跨多个 exchange_type 的: {cur.fetchone()[0]}")

    conn.close()


if __name__ == "__main__":
    main()
