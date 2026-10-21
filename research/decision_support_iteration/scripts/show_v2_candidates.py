"""查看 v2 口径下真正落库的候选（2026-08-19）与相关元数据。

背景：2026-08-19 是**第一次**用当前生产代码（含 P0-1 覆盖率分母修正）成功落库的
候选快照。此前的 2026-08-11/08-18/08-24 快照都是 `t1_rule_v1` + 阈值 40.0 时代的产物，
2026-08-19 那次请求读到的却是「空快照复用」，因此本次落库才是新口径的首次真实产出。
"""
from __future__ import annotations
import os

import json
import sys

import pymysql

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SQL = """
SELECT trade_date, topn, candidates_json, settled,
       JSON_EXTRACT(ecology_json, '$.eligibility') AS elig
FROM t1_candidate WHERE trade_date = %s
"""


def main() -> int:
    c = pymysql.connect(host="127.0.0.1", user="root", password=os.environ.get("DB_PASSWORD", ""),
                        database="quant_db", charset="utf8mb4")
    try:
        with c.cursor() as cur:
            for day in ("2026-08-19", "2026-09-07"):
                print(f"\n{'=' * 70}\n{day}\n{'=' * 70}")
                cur.execute(SQL, (day,))
                rows = cur.fetchall()
                if not rows:
                    print("  （无快照）")
                    continue
                for td, topn, cj, settled, elig in rows:
                    e = json.loads(elig) if elig else {}
                    cands = json.loads(cj) if cj else []
                    print(f"  topn={topn} settled={settled} 候选数={len(cands)}")
                    print("  eligibility:")
                    for k in sorted(e):
                        if k != "rejected_by_reason":
                            print(f"    {k} = {e[k]}")
                    rbr = e.get("rejected_by_reason") or {}
                    if rbr:
                        print(f"    rejected_by_reason = {json.dumps(rbr, ensure_ascii=False)}")
                    print("  候选明细:")
                    for cd in cands[:10]:
                        keys = ("stock_code", "code", "stock_name", "exchange_type",
                                "rule_score", "score_type", "rank", "close", "close_price")
                        print("    " + "  ".join(
                            f"{k}={cd[k]}" for k in keys if k in cd))
    finally:
        c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
