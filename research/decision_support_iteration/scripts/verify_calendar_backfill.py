"""回填后核验：日历区间 + 断言端点语义 + 关键日期的候选生成结果。

只读查询用 SELECT；候选生成走项目自带 `POST /api/research/t1/candidates`（会按设计落库）。
"""
from __future__ import annotations
import os

import json
import pathlib
import sys
import urllib.error
import urllib.request

import pymysql

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://127.0.0.1:8080"
TOKEN_FILE = pathlib.Path(__file__).resolve().parents[1] / ".api_token"
TOKEN = TOKEN_FILE.read_text(encoding="utf-8").strip()


def call(method: str, path: str, token=True, timeout=600):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:  # noqa: BLE001
            return e.code, {"raw": raw[:300]}


def db(sql, args=None):
    c = pymysql.connect(host="127.0.0.1", user="root", password=os.environ.get("DB_PASSWORD", ""),
                        database="quant_db", charset="utf8mb4")
    try:
        with c.cursor() as cur:
            cur.execute(sql, args or ())
            return cur.fetchall()
    finally:
        c.close()


def main() -> int:
    print("=== 日历区间 AFTER 回填 ===")
    print("  min/max/count =", db("SELECT MIN(trade_date),MAX(trade_date),COUNT(*) FROM trading_calendar")[0])
    print("  source 分布   =", db("SELECT source,COUNT(*) FROM trading_calendar GROUP BY source"))
    print("  2026-09 天数  =", db("SELECT COUNT(*) FROM trading_calendar WHERE trade_date BETWEEN '2026-09-01' AND '2026-09-30'")[0][0])
    print("  2026-10 天数  =", db("SELECT COUNT(*) FROM trading_calendar WHERE trade_date BETWEEN '2026-10-01' AND '2026-10-31'")[0][0])

    print("\n=== 候选端点完整响应（2026-09-07，回填后）===")
    st, r = call("GET", "/api/research/t1/candidates/2026-09-07")
    print(f"  HTTP {st}")
    print("  " + json.dumps(r, ensure_ascii=False)[:700])

    for d in ("2026-08-18", "2026-09-07", "2026-09-08", "2026-10-12"):
        print(f"\n=== POST /candidates tradeDate={d} ===")
        st, r = call("POST", f"/api/research/t1/candidates?tradeDate={d}&topN=10")
        data = r.get("data") or r
        keys = ("status", "message", "calendar_trading_day", "candidate_count",
                "total_scored", "daily_coverage", "snapshot_reused")
        print(f"  HTTP {st} " + " ".join(f"{k}={data.get(k)}" for k in keys if k in data))
        cands = data.get("candidates")
        if isinstance(cands, list) and cands:
            print(f"  candidates({len(cands)}): " +
                  ", ".join(str(c.get("stock_code") or c.get("code")) for c in cands[:10]))
        elig = data.get("eligibility")
        if isinstance(elig, dict):
            print("  eligibility=" + json.dumps(
                {k: v for k, v in elig.items() if k != "rejected_by_reason"},
                ensure_ascii=False)[:300])
    return 0


if __name__ == "__main__":
    sys.exit(main())
