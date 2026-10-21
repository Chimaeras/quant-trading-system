"""核对 t1_candidate 存量快照的 threshold_version 与生成时间。

目的：判断 2026-08-11 / 2026-08-18 返回 `NO_ELIGIBLE_CANDIDATE` 且
`snapshot_reused=True`，到底是「规则退化」还是「读到 v1 时代的旧快照」。
"""
from __future__ import annotations
import os

import json
import sys

import pymysql

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SQL = """
SELECT trade_date, topn, settled, created_at,
       CHAR_LENGTH(candidates_json) AS cand_len,
       JSON_EXTRACT(ecology_json, '$.eligibility') AS elig
FROM t1_candidate ORDER BY trade_date
"""


def main() -> int:
    c = pymysql.connect(host="127.0.0.1", user="root", password=os.environ.get("DB_PASSWORD", ""),
                        database="quant_db", charset="utf8mb4")
    try:
        with c.cursor() as cur:
            cur.execute(SQL)
            rows = cur.fetchall()
    finally:
        c.close()

    print(f"{'trade_date':<12} {'ver':<12} {'rule_thr':>8} {'eligible':>8} {'cand_len':>8}  created_at")
    for td, topn, settled, created, cand_len, elig in rows:
        e = json.loads(elig) if elig else {}
        if isinstance(e, str):
            e = json.loads(e)
        print(f"{str(td):<12} {str(e.get('threshold_version')):<12} "
              f"{str(e.get('rule_score_threshold')):>8} {str(e.get('eligible_count')):>8} "
              f"{cand_len:>8}  {created}")
        if e.get("daily_coverage") is not None:
            print(f"{'':<12}   daily_coverage={e.get('daily_coverage')} "
                  f"coverage_denominator_rule={e.get('coverage_denominator_rule')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
