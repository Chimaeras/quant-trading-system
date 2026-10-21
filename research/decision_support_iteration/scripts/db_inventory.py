"""只读：盘点本地 quant_db 的数据资产（PLAN §一/§四.1）。

用途：为「数据与标签可信度」提供实测证据（日期范围、覆盖率、PIT 主数据状态）。
严禁任何写操作：本脚本只执行 SELECT / SHOW。
"""
from __future__ import annotations
import os

import json
import sys

import pymysql

DB = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
          database="quant_db", connect_timeout=10, charset="utf8mb4")

FORBIDDEN = ("insert", "update", "delete", "drop", "alter", "create", "truncate", "replace")


def q(cur, sql, args=None):
    """只允许只读语句。"""
    head = sql.lstrip().lower()
    if not head.startswith(("select", "show", "describe", "explain")):
        raise RuntimeError(f"refusing non-read-only SQL: {sql[:60]}")
    for bad in FORBIDDEN:
        if head.startswith(bad):
            raise RuntimeError(f"refusing mutating SQL: {sql[:60]}")
    cur.execute(sql, args or ())
    return cur.fetchall()


def main() -> int:
    conn = pymysql.connect(**DB)
    out: dict = {}
    try:
        cur = conn.cursor()

        # ---- 表清单 ----
        rows = q(cur, "SELECT table_name, table_rows FROM information_schema.tables "
                      "WHERE table_schema='quant_db' ORDER BY table_name")
        out["tables"] = {r[0]: int(r[1] or 0) for r in rows}
        print(f"[tables] {len(out['tables'])} 张")

        # ---- 行情主表：日期范围与每日条数 ----
        for tbl in ("stock_daily", "market_data"):
            try:
                r = q(cur, f"SELECT MIN(trade_date), MAX(trade_date), COUNT(*) FROM {tbl}")[0]
                out[f"{tbl}_range"] = {"min": str(r[0]), "max": str(r[1]), "rows": int(r[2])}
                print(f"[{tbl}] {r[0]} .. {r[1]}  rows={r[2]}")
            except Exception as e:  # noqa: BLE001
                print(f"[{tbl}] skip: {str(e)[:100]}")

        # ---- 最近 15 个交易日的行情覆盖 ----
        try:
            rows = q(cur, "SELECT trade_date, COUNT(*) c, COUNT(DISTINCT stock_code) s "
                          "FROM stock_daily GROUP BY trade_date ORDER BY trade_date DESC LIMIT 15")
            out["recent_daily_coverage"] = [
                {"trade_date": str(r[0]), "bars": int(r[1]), "stocks": int(r[2])} for r in rows]
            print("\n[最近15日行情覆盖] trade_date  bars  stocks")
            for r in rows:
                print(f"  {r[0]}  {r[1]:>7}  {r[2]:>7}")
        except Exception as e:  # noqa: BLE001
            print(f"[recent coverage] skip: {str(e)[:100]}")

        # ---- 交易日历 ----
        try:
            r = q(cur, "SELECT COUNT(*), MIN(trade_date), MAX(trade_date) FROM trading_calendar_day")[0]
            out["trading_calendar"] = {"rows": int(r[0]), "min": str(r[1]), "max": str(r[2])}
            print(f"\n[trading_calendar] rows={r[0]} {r[1]} .. {r[2]}")
            rows = q(cur, "SELECT source, reliability, COUNT(*) FROM trading_calendar_day "
                          "GROUP BY source, reliability")
            out["trading_calendar_src"] = [
                {"source": r[0], "reliability": r[1], "n": int(r[2])} for r in rows]
            for r in rows:
                print(f"   source={r[0]} reliability={r[1]} n={r[2]}")
        except Exception as e:  # noqa: BLE001
            print(f"[trading_calendar] skip: {str(e)[:100]}")

        # ---- 证券主数据（PIT 关键） ----
        for tbl in ("security_master_history", "stock_info"):
            try:
                r = q(cur, f"SELECT COUNT(*) FROM {tbl}")[0]
                out[f"{tbl}_rows"] = int(r[0])
                print(f"\n[{tbl}] rows={r[0]}")
            except Exception as e:  # noqa: BLE001
                print(f"[{tbl}] skip: {str(e)[:100]}")
        try:
            rows = q(cur, "SELECT source, reliability, COUNT(*) FROM security_master_history "
                          "GROUP BY source, reliability")
            out["security_master_history_src"] = [
                {"source": r[0], "reliability": r[1], "n": int(r[2])} for r in rows]
            print("[security_master_history 来源分布]")
            for r in rows:
                print(f"   source={r[0]} reliability={r[1]} n={r[2]}")
        except Exception as e:  # noqa: BLE001
            print(f"[smh src] skip: {str(e)[:100]}")

        # ---- T1 候选快照（重复生成/固定性的证据） ----
        try:
            rows = q(cur, "SELECT trade_date, top_n, settled, "
                          "LEFT(candidates_json,1) lc, CHAR_LENGTH(candidates_json) len, created_at "
                          "FROM t1_candidate ORDER BY trade_date DESC LIMIT 20")
            out["t1_candidate_recent"] = [
                {"trade_date": str(r[0]), "top_n": r[1], "settled": bool(r[2]),
                 "json_prefix": r[3], "json_len": r[4], "created_at": str(r[5])} for r in rows]
            print("\n[t1_candidate 最近]")
            for r in rows:
                print(f"   {r[0]} top_n={r[1]} settled={r[2]} json_len={r[4]} created={r[5]}")
        except Exception as e:  # noqa: BLE001
            print(f"[t1_candidate] skip: {str(e)[:100]}")

        # ---- 模型注册表（概率轨是否有 champion） ----
        try:
            rows = q(cur, "SELECT model_version, algorithm, feature_set_version, stage, strategy_type, "
                          "horizon, created_at FROM model_version "
                          "ORDER BY created_at DESC LIMIT 15")
            out["model_versions"] = [
                {"model_version": r[0], "algorithm": r[1], "feature_set_version": r[2],
                 "stage": r[3], "strategy_type": r[4], "horizon": r[5], "created_at": str(r[6])}
                for r in rows]
            print("\n[model_version 最近]")
            for r in rows:
                print(f"   {r[0]} alg={r[1]} feat={r[2]} stage={r[3]} strat={r[4]} h={r[5]} {r[6]}")
        except Exception as e:  # noqa: BLE001
            print(f"[model_version] skip: {str(e)[:100]}")

        # ---- 校准器 ----
        for tbl in ("calibrator",):
            try:
                rows = q(cur, f"SELECT COUNT(*) FROM {tbl}")
                out[f"{tbl}_rows"] = int(rows[0][0])
                print(f"\n[{tbl}] rows={rows[0][0]}")
            except Exception as e:  # noqa: BLE001
                print(f"[{tbl}] skip: {str(e)[:100]}")

        print("\n=== JSON SUMMARY ===")
        print(json.dumps(out, ensure_ascii=False, indent=1, default=str))
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
