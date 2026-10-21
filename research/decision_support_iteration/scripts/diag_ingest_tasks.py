"""从入库任务表取证：2026-09-08 起的「数据断崖」是否为**入库管线部分失败**。

`data_update_task`（49 行，任务级）/ `data_update_task_item`（144,108 行，标的级）
记录了历史数据更新任务的执行结果。若断崖是管线故障，应能在任务表里看到
时间上吻合的失败/部分成功记录，并定位到失败的交易所或标的集合。

只读 SELECT。
"""
from __future__ import annotations
import os

import sys

import pymysql

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DSN = dict(host="127.0.0.1", user="root", password=os.environ.get("DB_PASSWORD", ""),
           database="quant_db", charset="utf8mb4")


def q(cur, sql, args=None):
    cur.execute(sql, args or ())
    return cur.fetchall()


def main() -> int:
    c = pymysql.connect(**DSN)
    cur = c.cursor()

    print("=== 1) 全部任务（按创建时间），只看关键列 ===")
    rows = q(cur, """
        SELECT id, task_id, status, mode, start_date, end_date, total_stocks,
               processed_stocks, inserted_count, fetched_count, failed_stocks,
               created_at, finished_at, LEFT(COALESCE(message,''), 120)
        FROM data_update_task ORDER BY created_at
    """)
    print(f"  {'id':>3} {'status':<14} {'mode':<10} {'range':<24} {'tot':>5} {'proc':>5} "
          f"{'ins':>8} {'created_at':<20} msg")
    for r in rows:
        rng = f"{r[4]}..{r[5]}"
        print(f"  {r[0]:>3} {str(r[2]):<14} {str(r[3]):<10} {rng:<24} "
              f"{str(r[6]):>5} {str(r[7]):>5} {str(r[8]):>8} {str(r[11]):<20} {r[13] or ''}")

    print("\n=== 2) 标的级结果：按交易所 + 状态 汇总（全部任务）===")
    for r in q(cur, """
        SELECT exchange_type, status, COUNT(*) n, SUM(inserted_count) ins,
               MAX(created_at) last
        FROM data_update_task_item
        GROUP BY exchange_type, status ORDER BY exchange_type, status
    """):
        print(f"  {r[0]:<6} {str(r[1]):<12} n={r[2]:>7} inserted={str(r[3]):>10} last={r[4]}")

    print("\n=== 3) 最近任务的标的级明细：按交易所看成功/失败 ===")
    last = q(cur, "SELECT task_id FROM data_update_task ORDER BY created_at DESC LIMIT 1")
    if last:
        tid = last[0][0]
        print(f"  最近任务 task_id={tid}")
        for r in q(cur, """
            SELECT exchange_type, status, COUNT(*) n, SUM(inserted_count) ins
            FROM data_update_task_item WHERE task_id=%s
            GROUP BY exchange_type, status ORDER BY exchange_type, status
        """, (tid,)):
            print(f"    {r[0]:<6} {str(r[1]):<12} n={r[2]:>6} inserted={r[3]}")
        print("  该任务的失败样例（最多 10 条）：")
        for r in q(cur, """
            SELECT stock_code, exchange_type, status, inserted_count,
                   LEFT(COALESCE(message,''), 100)
            FROM data_update_task_item
            WHERE task_id=%s AND status <> 'SUCCESS'
            ORDER BY stock_code LIMIT 10
        """, (tid,)):
            print(f"    {r[0]:<8} {r[1]:<6} {str(r[2]):<12} ins={r[3]} {r[4]}")

    print("\n=== 4) SZSE 249 只 vs 其它：是否与某次任务的失败集合吻合 ===")
    for r in q(cur, """
        SELECT DATE(created_at) d, exchange_type, status, COUNT(*) n
        FROM data_update_task_item
        WHERE created_at >= '2026-09-01'
        GROUP BY DATE(created_at), exchange_type, status
        ORDER BY d, exchange_type, status
    """):
        print(f"  {r[0]}  {r[1]:<6} {str(r[2]):<12} n={r[3]}")

    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
