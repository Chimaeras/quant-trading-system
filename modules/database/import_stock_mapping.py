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
from datetime import datetime
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
    """加载 CSV 并返回记录列表。
    若 CSV 含 status 列（上市/退市标记）则一并保留，否则默认 status=1（上市）。
    """
    rows = []
    with open(csv_path, "r", encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        for row in reader:
            code = (row.get("code") or "").strip()
            name = (row.get("name") or "").strip()
            market = (row.get("market") or "").strip()
            if code and name and market:
                status = (row.get("status") or "").strip()
                rows.append({
                    "code": code,
                    "name": name,
                    "market": market,
                    "status": status if status else "1",
                })
    return rows


def suffix_from_exchange(exchange_type: str) -> str:
    if exchange_type == "SSE":
        return "SH"
    if exchange_type == "BSE":
        return "BJ"
    return "SZ"


def write_universe_file(rows: list[dict], output_path: str, csv_filename: str = "") -> int:
    """
    生成项目内固定全量清单（code.suffix，每行一只）
    返回写入条数。

    注意：此清单基于导入时的存量快照，仅含当时在市的标的（survivorship bias）。
    回测/研究若需 point-in-time 成员资格，应以 SecurityMasterHistory（含 valid_from/valid_to）
    为准，而非此静态清单。清单头部记录快照来源与日期以便溯源。
    """
    lines = set()
    for row in rows:
        market = row["market"]
        if market not in MARKET_MAP:
            continue
        exchange_type, _ = map_market(market)
        suffix = suffix_from_exchange(exchange_type)
        lines.add(f'{row["code"]}.{suffix}')

    snapshot_date = datetime.now().strftime("%Y-%m-%d")
    out = Path(output_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        f.write("# A-share universe snapshot (generated)\n")
        f.write("# format: stock_code.exchange_suffix\n")
        f.write(f"# snapshot_date: {snapshot_date}\n")
        if csv_filename:
            f.write(f"# source_csv: {csv_filename}\n")
        f.write("# WARNING: 仅含快照日存量标的（survivorship bias）；"
                "回测须用 SecurityMasterHistory PIT 成员资格\n")
        for line in sorted(lines):
            f.write(f"{line}\n")
    return len(lines)


def main():
    # 默认 CSV 路径
    default_csv = Path(__file__).parent.parent.parent / "Download" / "A股代码名称映射表_20260227.csv"
    default_universe = (
        Path(__file__).parent.parent
        / "backend-java"
        / "src"
        / "main"
        / "resources"
        / "universe"
        / "a_share_codes.txt"
    )
    csv_path = sys.argv[1] if len(sys.argv) > 1 else str(default_csv)
    universe_output = sys.argv[2] if len(sys.argv) > 2 else str(default_universe)

    if not os.path.isfile(csv_path):
        print(f"错误: CSV 文件不存在: {csv_path}")
        sys.exit(1)

    try:
        import pymysql
    except ImportError:
        print("请先安装 pymysql: pip install pymysql")
        sys.exit(1)

    db_password = os.getenv("DB_PASSWORD")
    if not db_password:
        print("错误：未设置环境变量 DB_PASSWORD（数据库密码）。"
              "请先设置后再运行，例如：export DB_PASSWORD='your_password'")
        sys.exit(1)

    rows = load_csv(csv_path)
    print(f"从 CSV 加载了 {len(rows)} 条记录")

    # 数据库配置 (与 application.yml 一致)
    db_config = {
        "host": os.getenv("DB_HOST", "localhost"),
        "port": int(os.getenv("DB_PORT", "3306")),
        "user": os.getenv("DB_USER", "root"),
        "password": db_password,
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
            VALUES (%s, %s, %s, %s, %s)
            ON DUPLICATE KEY UPDATE
                stock_name = VALUES(stock_name),
                exchange_type_name = VALUES(exchange_type_name),
                status = VALUES(status),
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
                (row["code"], row["name"], exchange_type, exchange_type_name, row.get("status", "1")),
            )
            if cursor.rowcount == 1:
                inserted += 1
            elif cursor.rowcount == 2:
                updated += 1

        conn.commit()
        print(f"导入完成: 新增 {inserted} 条, 更新 {updated} 条, 跳过 {skipped} 条")
    finally:
        conn.close()

    # 额外输出项目内固定全量清单（供 ALL 模式读取）
    csv_basename = Path(csv_path).name
    written = write_universe_file(rows, universe_output, csv_basename)
    print(f"已生成全量清单: {universe_output} ({written} 条)")


if __name__ == "__main__":
    main()
