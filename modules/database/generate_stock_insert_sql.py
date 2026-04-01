#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 A股代码映射表 CSV 生成 stock_info 插入 SQL 文件
生成后可用: mysql -u root -p quant_db < scripts/db/stock_info_import.sql

用法:
    python scripts/db/generate_stock_insert_sql.py <csv文件路径> [输出sql路径]
"""

import csv
import os
import sys
from pathlib import Path

MARKET_MAP = {
    "深市主板": ("SZSE", "深交所"),
    "创业板": ("SZSE", "深交所"),
    "沪市主板": ("SSE", "上交所"),
    "科创板": ("SSE", "上交所"),
    "北交所": ("BSE", "北交所"),
}


def escape_sql(s: str) -> str:
    """转义 SQL 字符串中的单引号"""
    return s.replace("\\", "\\\\").replace("'", "''") if s else ""


def main():
    script_dir = Path(__file__).parent
    default_csv = script_dir.parent.parent / "Download" / "A股代码名称映射表_20260227.csv"
    default_sql = script_dir / "stock_info_import.sql"

    csv_path = sys.argv[1] if len(sys.argv) > 1 else str(default_csv)
    sql_path = sys.argv[2] if len(sys.argv) > 2 else str(default_sql)

    if not os.path.isfile(csv_path):
        print(f"错误: CSV 文件不存在: {csv_path}")
        sys.exit(1)

    rows = []
    with open(csv_path, "r", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            code = (row.get("code") or "").strip()
            name = (row.get("name") or "").strip()
            market = (row.get("market") or "").strip()
            if code and name and market and market in MARKET_MAP:
                ex_type, ex_name = MARKET_MAP[market]
                rows.append((code, name, ex_type, ex_name))

    with open(sql_path, "w", encoding="utf-8") as out:
        out.write("-- A股代码映射表导入 stock_info\n")
        out.write("-- 由 generate_stock_insert_sql.py 生成\n")
        out.write("USE quant_db;\n\n")
        for code, name, ex_type, ex_name in rows:
            cn, nn, en = escape_sql(code), escape_sql(name), escape_sql(ex_name)
            out.write(
                f"INSERT INTO stock_info (stock_code, stock_name, exchange_type, exchange_type_name, status) "
                f"VALUES ('{cn}', '{nn}', '{ex_type}', '{en}', 1) "
                f"ON DUPLICATE KEY UPDATE stock_name=VALUES(stock_name), "
                f"exchange_type_name=VALUES(exchange_type_name), updated_at=CURRENT_TIMESTAMP;\n"
            )
    print(f"已生成 {sql_path}, 共 {len(rows)} 条记录")


if __name__ == "__main__":
    main()
