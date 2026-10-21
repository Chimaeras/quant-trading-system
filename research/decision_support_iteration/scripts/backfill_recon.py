"""只读取证：Flyway 迁移状态 + security_master_history 相关来源可用性。

目的
----
启动后端前确认两件事：
  1. Flyway 已应用的迁移是否覆盖全部脚本（若缺，启动会执行写库迁移）。
  2. `security_master_history` 为空时，本地/外部究竟有哪些**真实**历史来源可回填
     （禁止编造 —— 必须找到真实数据源，否则如实报告不可行）。

仅 SELECT / SHOW。
"""
from __future__ import annotations
import os

import json
import re
import sys

import pymysql

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DSN = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
           database="quant_db", charset="utf8mb4")
ALLOWED = re.compile(r"^\s*(SELECT|SHOW|DESCRIBE|DESC|EXPLAIN)\b", re.I)


def q(cur, sql, args=None):
    if not ALLOWED.match(sql):
        raise RuntimeError(f"只读守卫拒绝：{sql[:60]}")
    cur.execute(sql, args or ())
    return cur.fetchall()


def main() -> int:
    conn = pymysql.connect(**DSN)
    try:
        with conn.cursor() as cur:
            print("=== flyway_schema_history ===")
            try:
                rows = q(cur, "SELECT installed_rank, version, description, success, installed_on "
                              "FROM flyway_schema_history ORDER BY installed_rank")
                for r in rows:
                    print(f"  {r[0]:>3} v{r[1]:<6} {str(r[2])[:44]:<46} success={r[3]} {r[4]}")
                applied = {str(r[1]) for r in rows}
            except Exception as exc:  # noqa: BLE001
                print(f"  [warn] 读取失败: {exc}")
                applied = set()
            print(f"  已应用 {len(applied)} 个版本")

            print("=== 与被回填目标相关的表是否存在 ===")
            for t in ("security_master_history", "stock_info", "trading_calendar",
                      "index_quote", "market_data"):
                try:
                    n = q(cur, f"SELECT COUNT(*) FROM {t}")[0][0]
                    print(f"  {t:<28} rows={n}")
                except Exception as exc:  # noqa: BLE001
                    print(f"  {t:<28} [missing] {exc}")

            print("=== security_master_history 结构 ===")
            try:
                for r in q(cur, "SHOW COLUMNS FROM security_master_history"):
                    print(f"  {r[0]:<26} {r[1]:<20} null={r[2]} key={r[3]}")
            except Exception as exc:  # noqa: BLE001
                print(f"  [warn] {exc}")

            print("=== 可能的真实历史来源：全库表清单中含 history/master/name 的表 ===")
            rows = q(cur, "SELECT table_name, table_rows FROM information_schema.tables "
                          "WHERE table_schema='quant_db' ORDER BY table_name")
            names = [r[0] for r in rows]
            for r in rows:
                if re.search(r"history|master|name|cal|delist|suspend", r[0], re.I):
                    print(f"  {r[0]:<34} ~rows={r[1]}")
            print(f"  (全库共 {len(names)} 张表)")

            print("=== stock_info 是否有历史维度字段 ===")
            for r in q(cur, "SHOW COLUMNS FROM stock_info"):
                print(f"  {r[0]:<26} {r[1]:<20}")
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
