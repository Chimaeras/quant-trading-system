#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
A股代码映射表导入脚本
将 A股代码名称映射表_*.csv 导入到 stock_info 表

用法:
    python scripts/db/import_stock_mapping.py <csv文件路径>
    或
    python scripts/db/import_stock_mapping.py  # 使用默认路径

依赖: pip install pymysql
"""

import csv
import os
import sys
from pathlib import Path

# 市场类型到 exchange_type / exchange_type_name 的映射
MARKET_MAP = {
    "深市主板": ("SZSE", "深交所"),
    "创业板": ("SZSE", "深交所"),
    "沪市主板": ("SSE", "上交所"),
    "科创板": ("SSE", "上交所"),
    "北交所": ("BSE", "北交所"),
}


def map_market(market: str) -> tuple[str, str]:
    """将 CSV 中的 market 映射为 (exchange_type, exchange_type_name)"""
    return MARKET_MAP.get(market, ("SZSE", "未知"))  # 默认深交所


def load_csv(csv_path: str) -> list[dict]:
    """加载 CSV 并返回记录列表"""
    rows = []
    with open(csv_path, "r", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        for row in reader:
            code = (row.get("code") or "").strip()
            name = (row.get("name") or "").strip()
            market = (row.get("market") or "").strip()
            if code and name and market:
                rows.append({"code": code, "name": name, "market": market})
    return rows


def main():
    # 默认 CSV 路径
    default_csv = Path(__file__).parent.parent.parent / "Download" / "A股代码名称映射表_20260227.csv"
    csv_path = sys.argv[1] if len(sys.argv) > 1 else str(default_csv)

    if not os.path.isfile(csv_path):
        print(f"错误: CSV 文件不存在: {csv_path}")
        sys.exit(1)

    try:
        import pymysql
    except ImportError:
        print("请先安装 pymysql: pip install pymysql")
        sys.exit(1)

    rows = load_csv(csv_path)
    print(f"从 CSV 加载了 {len(rows)} 条记录")

    # 数据库配置 (与 application.yml 一致)
    db_config = {
        "host": os.getenv("DB_HOST", "localhost"),
        "port": int(os.getenv("DB_PORT", "3306")),
        "user": os.getenv("DB_USER", "root"),
        "password": os.getenv("DB_PASSWORD", "123456"),
        "database": "quant_db",
        "charset": "utf8mb4",
    }

    conn = pymysql.connect(**db_config)
    try:
        cursor = conn.cursor()
        inserted = 0
        updated = 0
        skipped = 0

        sql = """
            INSERT INTO stock_info (stock_code, stock_name, exchange_type, exchange_type_name, status)
            VALUES (%s, %s, %s, %s, 1)
            ON DUPLICATE KEY UPDATE
                stock_name = VALUES(stock_name),
                exchange_type_name = VALUES(exchange_type_name),
                updated_at = CURRENT_TIMESTAMP
        """

        for row in rows:
            market = row["market"]
            if market not in MARKET_MAP:
                skipped += 1
                continue
            exchange_type, exchange_type_name = map_market(market)
            cursor.execute(
                sql,
                (row["code"], row["name"], exchange_type, exchange_type_name),
            )
            if cursor.rowcount == 1:
                inserted += 1
            elif cursor.rowcount == 2:
                updated += 1

        conn.commit()
        print(f"导入完成: 新增 {inserted} 条, 更新 {updated} 条, 跳过 {skipped} 条")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
