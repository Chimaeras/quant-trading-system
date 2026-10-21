#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""数据可用性基线探针（只读）。

用途：在写任何实验代码之前，先确认本地数据湖能支撑哪一类复现实验。
本脚本 **只执行 SELECT**，不建表、不写库、不改任何文件。

资源占用：单连接、顺序查询、每个查询带 LIMIT 或聚合。全量聚合在
market_data（约 245 万行）上跑，预计 30 秒内完成。

用法：
    python research/paper_loop/baselines/probe_data_availability.py [--json 输出路径]
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime

try:
    import pymysql
except ImportError:  # pragma: no cover
    print("需要 pymysql：pip install pymysql", file=sys.stderr)
    sys.exit(2)

DB = {
    "host": os.environ.get("QUANT_DB_HOST", "localhost"),
    "port": int(os.environ.get("QUANT_DB_PORT", "3306")),
    "user": os.environ.get("QUANT_DB_USER", "root"),
    "password": os.environ.get("QUANT_DB_PASSWORD", os.environ.get("DB_PASSWORD", "")),
    "database": os.environ.get("QUANT_DB_NAME", "quant_db"),
    "charset": "utf8mb4",
    "cursorclass": pymysql.cursors.DictCursor,
}

# 只读守卫：任何非 SELECT/SHOW/DESCRIBE 语句一律拒绝执行。
def _readonly(sql: str) -> bool:
    head = sql.strip().lstrip("(").lower()
    return head.startswith(("select", "show", "describe", "desc", "explain", "with"))


def q(cur, sql, params=None):
    if not _readonly(sql):
        raise RuntimeError(f"拒绝执行非只读语句: {sql[:60]}")
    cur.execute(sql, params or ())
    return cur.fetchall()


def scalar(cur, sql, params=None):
    rows = q(cur, sql, params)
    if not rows:
        return None
    return list(rows[0].values())[0]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default=None, help="把结果写到该 JSON 路径")
    args = ap.parse_args()

    out: dict = {"probe_time": datetime.now().isoformat(timespec="seconds")}

    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            # ---------- 0. 连接与版本 ----------
            out["db"] = {
                "host": DB["host"], "port": DB["port"], "database": DB["database"],
                "server": scalar(cur, "SELECT VERSION()"),
            }

            # ---------- 1. 表是否存在 ----------
            names = [
                "market_data", "market_price_raw", "adjust_factor",
                "stock_info", "security_master_history", "trading_calendar",
                "index_quote", "technical_indicators", "stock_financial_snapshot",
                "selector_result", "selector_result_item", "t1_candidate",
                "backtest_run", "label_snapshot", "feature_snapshot_row",
                "stock_trend_score", "stock_multi_dim_score",
            ]
            rows = q(
                cur,
                "SELECT TABLE_NAME AS t, TABLE_ROWS AS approx_rows "
                "FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s",
                (DB["database"],),
            )
            present = {r["t"]: r["approx_rows"] for r in rows}
            out["tables_approx_rows"] = {n: present.get(n) for n in names}

            # ---------- 2. market_data 规模与覆盖 ----------
            out["market_data"] = {
                "rows": scalar(cur, "SELECT COUNT(*) FROM market_data"),
                "distinct_codes": scalar(cur, "SELECT COUNT(DISTINCT stock_code) FROM market_data"),
                "distinct_dates": scalar(cur, "SELECT COUNT(DISTINCT trade_date) FROM market_data"),
                "min_date": str(scalar(cur, "SELECT MIN(trade_date) FROM market_data")),
                "max_date": str(scalar(cur, "SELECT MAX(trade_date) FROM market_data")),
            }
            dup = q(
                cur,
                "SELECT stock_code, trade_date, COUNT(*) c FROM market_data "
                "GROUP BY stock_code, trade_date HAVING c>1 LIMIT 5",
            )
            out["market_data"]["duplicate_key_rows_sample"] = [
                {"code": r["stock_code"], "date": str(r["trade_date"]), "n": r["c"]} for r in dup
            ]
            out["market_data"]["duplicate_key_count"] = scalar(
                cur,
                "SELECT COUNT(*) FROM (SELECT 1 FROM market_data "
                "GROUP BY stock_code, trade_date HAVING COUNT(*)>1) x",
            )

            # ---------- 3. 逐年覆盖（决定研究样本区间） ----------
            out["by_year"] = [
                {"year": r["y"], "rows": r["n_rows"], "codes": r["codes"], "dates": r["dates"]}
                for r in q(
                    cur,
                    "SELECT YEAR(trade_date) y, COUNT(*) n_rows, "
                    "COUNT(DISTINCT stock_code) codes, COUNT(DISTINCT trade_date) dates "
                    "FROM market_data GROUP BY YEAR(trade_date) ORDER BY y",
                )
            ]

            # ---------- 4. 每交易日截面宽度（近 12 年） ----------
            out["cross_section_width_recent"] = [
                {"year": r["y"], "avg_codes_per_date": float(r["avg_codes"])}
                for r in q(
                    cur,
                    "SELECT YEAR(trade_date) y, AVG(c) avg_codes FROM ("
                    "  SELECT trade_date, COUNT(DISTINCT stock_code) c FROM market_data "
                    "  WHERE trade_date >= '2015-01-01' GROUP BY trade_date"
                    ") t GROUP BY YEAR(trade_date) ORDER BY y",
                )
            ]

            # ---------- 5. 字段填充率 ----------
            out["field_fill"] = [
                dict(r)
                for r in q(
                    cur,
                    "SELECT COUNT(*) n_rows, "
                    "SUM(close_price IS NULL OR close_price<=0) bad_close, "
                    "SUM(open_price  IS NULL OR open_price <=0) bad_open, "
                    "SUM(high_price<low_price) high_lt_low, "
                    "SUM(pre_close  IS NULL OR pre_close<=0) no_pre_close, "
                    "SUM(volume     IS NULL OR volume<=0) zero_volume, "
                    "SUM(amount     IS NULL OR amount<=0) zero_amount, "
                    "SUM(turnover_rate IS NOT NULL AND turnover_rate>0) has_turnover, "
                    "SUM(total_market_value IS NOT NULL AND total_market_value>0) has_mktcap, "
                    "SUM(pe_ratio IS NOT NULL) has_pe, "
                    "SUM(pb_ratio IS NOT NULL) has_pb "
                    "FROM market_data",
                )
            ]

            # ---------- 5b. 市值填充率逐年（判断能否做 size 因子） ----------
            out["mktcap_fill_by_year"] = [
                dict(r)
                for r in q(
                    cur,
                    "SELECT YEAR(trade_date) y, COUNT(*) n_rows, "
                    "SUM(total_market_value IS NOT NULL AND total_market_value>0) has_mktcap, "
                    "SUM(turnover_rate IS NOT NULL AND turnover_rate>0) has_turnover, "
                    "SUM(volume>0 AND turnover_rate>0) derivable_float "
                    "FROM market_data WHERE trade_date>='2010-01-01' "
                    "GROUP BY YEAR(trade_date) ORDER BY y",
                )
            ]

            # ---------- 6. 成交状态判定所需字段的可得性 ----------
            out["limit_move_feasibility"] = [
                dict(r)
                for r in q(
                    cur,
                    "SELECT YEAR(trade_date) y, COUNT(*) n_rows, "
                    "SUM(change_pct IS NOT NULL) has_chg_pct, "
                    "SUM(ABS(change_pct)>=9.5) approx_limit_moves "
                    "FROM market_data WHERE trade_date>='2015-01-01' "
                    "GROUP BY YEAR(trade_date) ORDER BY y",
                )
            ]

            # ---------- 7. 日历与基准 ----------
            out["trading_calendar"] = {
                "rows": scalar(cur, "SELECT COUNT(*) FROM trading_calendar"),
                "trading_days": scalar(
                    cur, "SELECT COUNT(*) FROM trading_calendar WHERE is_trading_day=1"
                ),
                "min_date": str(scalar(cur, "SELECT MIN(trade_date) FROM trading_calendar")),
                "max_date": str(scalar(cur, "SELECT MAX(trade_date) FROM trading_calendar")),
            }

            out["index_quote"] = [
                {"code": r["index_code"], "rows": r["n"], "min": str(r["mn"]), "max": str(r["mx"])}
                for r in q(
                    cur,
                    "SELECT index_code, COUNT(*) n, MIN(trade_date) mn, MAX(trade_date) mx "
                    "FROM index_quote GROUP BY index_code",
                )
            ]

            # ---------- 8. 已被占用的研究产物（稀疏度） ----------
            out["sparse_research_artifacts"] = {
                "selector_result_runs": scalar(cur, "SELECT COUNT(*) FROM selector_result"),
                "selector_result_items": scalar(cur, "SELECT COUNT(*) FROM selector_result_item"),
                "selector_result_distinct_dates": scalar(
                    cur, "SELECT COUNT(DISTINCT trade_date) FROM selector_result"
                ),
                "t1_candidate": scalar(cur, "SELECT COUNT(*) FROM t1_candidate"),
                "backtest_run": scalar(cur, "SELECT COUNT(*) FROM backtest_run"),
                "label_snapshot": scalar(cur, "SELECT COUNT(*) FROM label_snapshot"),
            }

            # ---------- 9. 股票主数据（幸存者偏差证据） ----------
            out["stock_info"] = {
                "rows": scalar(cur, "SELECT COUNT(*) FROM stock_info"),
                "status_breakdown": [
                    dict(r) for r in q(
                        cur, "SELECT status, COUNT(*) n FROM stock_info GROUP BY status"
                    )
                ],
                "min_list_date": str(scalar(cur, "SELECT MIN(list_date) FROM stock_info")),
                "max_list_date": str(scalar(cur, "SELECT MAX(list_date) FROM stock_info")),
            }

            # ---------- 10. 前复权一致性证据：负价与极端比值 ----------
            out["qfq_sanity"] = dict(
                q(
                    cur,
                    "SELECT SUM(close_price<=0) nonpos_close, "
                    "SUM(pre_close>0 AND ABS(close_price/pre_close-1)>0.5) extreme_jump "
                    "FROM market_data",
                )[0]
            )
    finally:
        conn.close()

    text = json.dumps(out, ensure_ascii=False, indent=2, default=str)
    print(text)
    if args.json:
        os.makedirs(os.path.dirname(os.path.abspath(args.json)), exist_ok=True)
        with open(args.json, "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
