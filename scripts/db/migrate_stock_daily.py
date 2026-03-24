#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
query.stock_daily → quant_db 数据迁移脚本

源表 query.stock_daily 字段:
  init_date, stock_code, stock_code_name_gb, stock_name_gb,
  open_price, high_price, low_price, change, change_percent, close_price,
  volume, volume_balance, turnover_rate, ma5, ma10, ma15, ma20, ma30, rsi14,
  created_time, modified_time

目标表:
  - quant_db.stock_info: 补充股票基础信息(若缺失)
  - quant_db.market_data: 行情数据
  - quant_db.technical_indicators: 技术指标(ma, rsi)

依赖: pip install pymysql
用法: python scripts/db/migrate_stock_daily.py [--batch-size 5000] [--dry-run]
"""

import argparse
import os
import sys
from datetime import datetime
from decimal import Decimal

try:
    import pymysql
except ImportError:
    print("请先安装: pip install pymysql")
    sys.exit(1)


def infer_exchange_type(stock_code: str) -> str:
    """根据股票代码推断交易所"""
    code = str(stock_code).strip()
    if not code:
        return "SZSE"
    # 去除可能的后缀 .SZ .SH .BJ
    base = code.split(".")[0] if "." in code else code
    if base.startswith(("6", "5", "9")) and len(base) >= 4:
        return "SSE"  # 沪市主板/科创板/ETF
    if base.startswith(("8", "4")) and len(base) >= 4:
        return "BSE"  # 北交所
    return "SZSE"  # 000/001/002/003/300 深市


def init_date_to_date(init_date) -> str:
    """init_date(int 20240102) -> '2024-01-02'"""
    if init_date is None:
        return None
    s = str(int(init_date))
    if len(s) == 8:
        return f"{s[:4]}-{s[4:6]}-{s[6:8]}"
    return None


def to_decimal(v, default=None):
    if v is None:
        return default
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def to_int(v, default=None):
    if v is None:
        return default
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def main():
    parser = argparse.ArgumentParser(description="迁移 query.stock_daily 到 quant_db")
    parser.add_argument("--batch-size", type=int, default=5000)
    parser.add_argument("--dry-run", action="store_true", help="只统计不写入")
    parser.add_argument("--limit", type=int, default=0, help="限制迁移条数(仅market_data)，0=不限制，用于测试")
    args = parser.parse_args()

    src_config = {
        "host": os.getenv("SRC_DB_HOST", "localhost"),
        "port": int(os.getenv("SRC_DB_PORT", "3306")),
        "user": os.getenv("SRC_DB_USER", "root"),
        "password": os.getenv("SRC_DB_PASSWORD", "123456"),
        "database": "query",
        "charset": "utf8mb4",
    }
    dst_config = {
        "host": os.getenv("DST_DB_HOST", "localhost"),
        "port": int(os.getenv("DST_DB_PORT", "3306")),
        "user": os.getenv("DST_DB_USER", "root"),
        "password": os.getenv("DST_DB_PASSWORD", "123456"),
        "database": "quant_db",
        "charset": "utf8mb4",
    }

    src = pymysql.connect(**src_config)
    dst = pymysql.connect(**dst_config)

    try:
        src_cur = src.cursor()
        dst_cur = dst.cursor()

        # 1. 统计源数据
        src_cur.execute("SELECT COUNT(*) FROM stock_daily")
        total = src_cur.fetchone()[0]
        print(f"源表 stock_daily 总记录数: {total}")

        if args.dry_run:
            src_cur.execute(
                "SELECT MIN(init_date), MAX(init_date) FROM stock_daily"
            )
            min_d, max_d = src_cur.fetchone()
            print(f"日期范围: {min_d} ~ {max_d}")
            src_cur.execute(
                "SELECT COUNT(DISTINCT stock_code) FROM stock_daily"
            )
            print(f"股票数量: {src_cur.fetchone()[0]}")
            return

        # 2. 先补充 stock_info（从 stock_daily 提取唯一股票）
        print("\n[1/3] 补充 stock_info...")
        src_cur.execute("""
            SELECT stock_code,
                COALESCE(NULLIF(TRIM(MAX(stock_name_gb)),''),
                    NULLIF(TRIM(MAX(stock_code_name_gb)),''), stock_code) as name
            FROM stock_daily
            GROUP BY stock_code
        """)
        stocks = src_cur.fetchall()
        ins_stock = 0
        for code, name in stocks:
            code_clean = str(code).split(".")[0] if code else ""
            if not code_clean:
                continue
            ex = infer_exchange_type(code_clean)
            ex_name = "深交所" if ex == "SZSE" else ("上交所" if ex == "SSE" else "北交所")
            name_safe = (name or code_clean)[:50]
            dst_cur.execute("""
                INSERT INTO stock_info (stock_code, stock_name, exchange_type, exchange_type_name, status)
                VALUES (%s, %s, %s, %s, 1)
                ON DUPLICATE KEY UPDATE stock_name=VALUES(stock_name), exchange_type_name=VALUES(exchange_type_name)
            """, (code_clean, name_safe, ex, ex_name))
            if dst_cur.rowcount == 1:
                ins_stock += 1
        dst.commit()
        print(f"  补充 stock_info: {ins_stock} 条新增")

        # 3. 迁移 market_data（分批）
        print("\n[2/3] 迁移 market_data...")
        processed = 0
        sql_md = """
            INSERT INTO market_data (stock_code, exchange_type, trade_date,
                open_price, high_price, low_price, close_price, pre_close,
                change_pct, volume, amount, turnover_rate)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON DUPLICATE KEY UPDATE
                open_price=VALUES(open_price), high_price=VALUES(high_price),
                low_price=VALUES(low_price), close_price=VALUES(close_price),
                pre_close=VALUES(pre_close), change_pct=VALUES(change_pct),
                volume=VALUES(volume), amount=VALUES(amount), turnover_rate=VALUES(turnover_rate),
                updated_at=CURRENT_TIMESTAMP
        """
        sql_select = """
            SELECT init_date, stock_code, open_price, high_price, low_price, close_price,
                   `change`, change_percent, volume, volume_balance, turnover_rate
            FROM stock_daily
            ORDER BY init_date, stock_code
        """ + (" LIMIT %s" if args.limit > 0 else "")
        src_cur.execute(sql_select, (args.limit,) if args.limit > 0 else ())
        batch = []
        for row in src_cur:
            init_d, code, o, h, l, c, chg, chg_pct, vol, vol_bal, turn = row
            code_clean = str(code).split(".")[0] if code else ""
            if not code_clean:
                continue
            trade_date = init_date_to_date(init_d)
            if not trade_date:
                continue
            ex = infer_exchange_type(code_clean)
            pre_close = None
            if c is not None and chg is not None:
                try:
                    pre_close = float(c) - float(chg)
                except (TypeError, ValueError):
                    pass
            amount = to_decimal(vol_bal)
            batch.append((
                code_clean, ex, trade_date,
                to_decimal(o), to_decimal(h), to_decimal(l), to_decimal(c),
                pre_close, to_decimal(chg_pct), to_int(vol), amount, to_decimal(turn),
            ))
            if len(batch) >= args.batch_size:
                dst_cur.executemany(sql_md, batch)
                dst.commit()
                processed += len(batch)
                batch = []
                print(f"  已处理 market_data: {processed} 条")
        if batch:
            dst_cur.executemany(sql_md, batch)
            dst.commit()
            processed += len(batch)
        print(f"  market_data 完成: 共 {processed} 条")

        # 4. 迁移 technical_indicators（仅 ma、rsi 有数据的记录）
        print("\n[3/3] 迁移 technical_indicators...")
        sql_ti = """
            INSERT INTO technical_indicators (stock_code, exchange_type, trade_date, ma5, ma10, ma20, ma30, rsi14)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            ON DUPLICATE KEY UPDATE
                ma5=COALESCE(VALUES(ma5), ma5), ma10=COALESCE(VALUES(ma10), ma10),
                ma20=COALESCE(VALUES(ma20), ma20), ma30=COALESCE(VALUES(ma30), ma30),
                rsi14=COALESCE(VALUES(rsi14), rsi14), updated_at=CURRENT_TIMESTAMP
        """
        sql_ti_select = """
            SELECT init_date, stock_code, ma5, ma10, ma15, ma20, ma30, rsi14
            FROM stock_daily
            WHERE (ma5 IS NOT NULL OR ma10 IS NOT NULL OR ma20 IS NOT NULL OR ma30 IS NOT NULL OR rsi14 IS NOT NULL)
            ORDER BY init_date, stock_code
        """ + (" LIMIT %s" if args.limit > 0 else "")
        src_cur.execute(sql_ti_select, (args.limit,) if args.limit > 0 else ())
        ti_batch = []
        ti_processed = 0
        for row in src_cur:
            init_d, code, ma5, ma10, ma15, ma20, ma30, rsi14 = row
            code_clean = str(code).split(".")[0] if code else ""
            if not code_clean:
                continue
            trade_date = init_date_to_date(init_d)
            if not trade_date:
                continue
            ex = infer_exchange_type(code_clean)
            ti_batch.append((
                code_clean, ex, trade_date,
                to_decimal(ma5), to_decimal(ma10), to_decimal(ma20), to_decimal(ma30),
                to_decimal(rsi14),
            ))
            if len(ti_batch) >= args.batch_size:
                dst_cur.executemany(sql_ti, ti_batch)
                dst.commit()
                ti_processed += len(ti_batch)
                ti_batch = []
                print(f"  已处理 technical_indicators: {ti_processed} 条")
        if ti_batch:
            dst_cur.executemany(sql_ti, ti_batch)
            dst.commit()
            ti_processed += len(ti_batch)
        print(f"  technical_indicators 完成: 共 {ti_processed} 条")

        print("\n迁移完成。")
    finally:
        src.close()
        dst.close()


if __name__ == "__main__":
    main()
