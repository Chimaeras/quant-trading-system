"""定位入库管线失败原因：最近两次全市场任务（id 48/49）为何在 SZSE 前 250 只就停。

已确认事实：
  · 最后一次**全市场成功**：id 47，2026-09-08 10:06，range 2026-08-31..2026-09-08，5484/5484 成功；
  · 其后 id 48/49（range 2026-07-22..2026-10-13，今天 10:31/10:34）**FAILED**，
    分别只处理 252 / 153 只，且**全部是 SZSE**；
  · 标的级 last_insert：BSE/SSE 停在 2026-09-08 10:28~10:30，SZSE 一直到 2026-10-13 10:35。
本脚本取这两次任务的 message 与标的级明细，确认失败是否集中在 SZSE 起始处（=取数源问题）。

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

    print("=== 1) 任务 47/48/49 完整记录 ===")
    for r in q(cur, """
        SELECT id, task_id, status, mode, start_date, end_date, total_stocks,
               processed_stocks, success_stocks, failed_stocks, inserted_count,
               fetched_count, skipped_count, stock_list, created_at, started_at,
               finished_at, message
        FROM data_update_task WHERE id IN (47,48,49) ORDER BY id
    """):
        cols = ["id", "task_id", "status", "mode", "start", "end", "total",
                "processed", "success", "failed", "inserted", "fetched",
                "skipped", "stock_list", "created_at", "started_at", "finished_at", "message"]
        print(f"\n  --- task id={r[0]} ---")
        for k, v in zip(cols, r):
            if k in ("task_id",):
                continue
            s = str(v)
            if len(s) > 200:
                s = s[:200] + f"…(+{len(s) - 200})"
            print(f"    {k:<12} = {s}")

    print("\n=== 2) 失败任务(48/49)标的级明细：状态与代码区间 ===")
    for tid in (48, 49):
        rows = q(cur, """
            SELECT stock_code, exchange_type, status, inserted_count, fetched_count,
                   skipped_count, LEFT(COALESCE(message,''), 80), created_at
            FROM data_update_task_item
            WHERE task_id = (SELECT task_id FROM data_update_task WHERE id=%s)
            ORDER BY id
        """, (tid,))
        print(f"\n  --- task id={tid}: {len(rows)} items ---")
        if not rows:
            print("    （无标的级记录）")
            continue
        codes = [r[0] for r in rows]
        print(f"    代码范围: {min(codes)} .. {max(codes)}")
        print(f"    交易所: {sorted({r[1] for r in rows})}")
        print(f"    状态:   {sorted({r[2] for r in rows})}")
        print(f"    前 5 条:")
        for r in rows[:5]:
            print(f"      {r[0]:<8} {r[1]:<6} {str(r[2]):<10} ins={r[3]} fetch={r[4]} "
                  f"skip={r[5]} {r[6]} {r[7]}")
        print(f"    后 5 条:")
        for r in rows[-5:]:
            print(f"      {r[0]:<8} {r[1]:<6} {str(r[2]):<10} ins={r[3]} fetch={r[4]} "
                  f"skip={r[5]} {r[6]} {r[7]}")
        nonempty = [r for r in rows if r[6]]
        print(f"    带 message 的条目数: {len(nonempty)}")
        for r in nonempty[:5]:
            print(f"      {r[0]} :: {r[6]}")

    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
