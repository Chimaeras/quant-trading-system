"""覆盖率闸「真实数据回放」（只读，仅 SELECT）。

目的
----
把 `T1CandidateService.generateCandidates` 的**覆盖率闸**（D-T1-06，2026-10-13 修正分母）
逐行重算在**真实 quant_db** 上，回答一个此前只有单测证据的问题：

    「修复后的分母，在真实信号日上到底判 PASS 还是 BLOCK？」

对照口径（两者都算，用于证明修复确实改变了结论）：
  · OLD 分母 = `stock_info` 中 status=1 且 list_date<=T 的**全部键**（原实现，恒不通过）
  · NEW 分母 = 上述键 ∩ 「[T-60d, T] 内真的有 bar」（修正后）

被回放的 Java 逻辑
------------------
  expected(OLD) = |{k : status=1 ∧ list_date<=T}|
  activeRecently = SELECT DISTINCT stock_code, exchange_type FROM market_data
                   WHERE trade_date BETWEEN T-60d AND T
  expected(NEW) = |{k : status=1 ∧ list_date<=T ∧ k ∈ activeRecently}|
  covered(NEW)  = |{k ∈ expected(NEW) : validDailyBar(bar(k, T))}|
  validDailyBar = OHLC 全 >0 ∧ high >= max(open,close) ∧ low <= min(open,close)
  coverage = covered / expected ; 闸门 = coverage >= 0.95

仅执行 SELECT；非 SELECT 直接拒绝。
"""
from __future__ import annotations
import os

import json
import pathlib
import re
import sys
from datetime import date, timedelta

import pymysql

OUT = pathlib.Path(__file__).resolve().parents[1] / "coverage_replay.json"
ACTIVE_WINDOW_DAYS = 60
MIN_DAILY_COVERAGE = 0.95

DSN = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
           database="quant_db", charset="utf8mb4")

ALLOWED = re.compile(r"^\s*(SELECT|SHOW|DESCRIBE|DESC|EXPLAIN)\b", re.I)


def q(cur, sql, args=None):
    if not ALLOWED.match(sql):
        raise RuntimeError(f"拒绝非只读语句: {sql[:60]}")
    cur.execute(sql, args or ())
    return cur.fetchall()


def valid_bar(o, h, l, c) -> bool:
    """对齐 T1CandidateService.validDailyBar（930-938）。"""
    if None in (o, h, l, c):
        return False
    vals = [float(o), float(h), float(l), float(c)]
    if any((not (v > 0)) for v in vals):
        return False
    o, h, l, c = vals
    return h >= max(o, c) and l <= min(o, c)


def replay(cur, t: date) -> dict:
    # 主表键（PIT 回退路径 = stock_info 当前截面 + list_date 前视过滤）
    uni = q(cur, """SELECT exchange_type, stock_code, list_date FROM stock_info
                    WHERE status = 1 AND list_date IS NOT NULL AND list_date <= %s""", (t,))
    uni_keys = {f"{code}|{ex}" for ex, code, _ in uni}

    # 修正后的分母：回看窗口内真的有 bar 的键
    start = t - timedelta(days=ACTIVE_WINDOW_DAYS)
    active = {f"{code}|{ex}" for code, ex in q(
        cur, """SELECT DISTINCT stock_code, exchange_type FROM market_data
                WHERE trade_date BETWEEN %s AND %s""", (start, t))}

    expected_new_keys = uni_keys & active

    # 当日 bar（validDailyBar 判定）
    today = q(cur, """SELECT stock_code, exchange_type, open_price, high_price,
                             low_price, close_price FROM market_data
                      WHERE trade_date = %s""", (t,))
    valid_keys = {f"{code}|{ex}" for code, ex, o, h, l, c in today if valid_bar(o, h, l, c)}
    raw_keys = {f"{code}|{ex}" for code, ex, *_ in today}

    exp_old = len(uni_keys)
    exp_new = len(expected_new_keys)
    cov_new = len(expected_new_keys & valid_keys)
    cov_old = len(uni_keys & valid_keys)

    return {
        "trade_date": t.isoformat(),
        "expected_OLD": exp_old,
        "covered_OLD": cov_old,
        "coverage_OLD": round(cov_old / exp_old, 6) if exp_old else 0.0,
        "verdict_OLD": "PASS" if (exp_old and cov_old / exp_old >= MIN_DAILY_COVERAGE) else "BLOCK",
        "expected_NEW": exp_new,
        "covered_NEW": cov_new,
        "coverage_NEW": round(cov_new / exp_new, 6) if exp_new else 0.0,
        "verdict_NEW": "PASS" if (exp_new and cov_new / exp_new >= MIN_DAILY_COVERAGE) else "BLOCK",
        "bars_that_day": len(raw_keys),
        "keys_in_uni_not_active": len(uni_keys - active),
    }


def main() -> int:
    conn = pymysql.connect(**DSN)
    cur = conn.cursor()
    out: dict = {"active_window_days": ACTIVE_WINDOW_DAYS,
                 "min_daily_coverage": MIN_DAILY_COVERAGE, "days": []}
    try:
        # 1) 生产快照真实存在的 3 个日期（含 eligibility 块的最后 3 次运行）
        prod = [date(2026, 8, 11), date(2026, 8, 18), date(2026, 8, 24)]
        # 2) 正常期对照（此前已标定通过）
        ctrl = [date(2026, 3, 31), date(2026, 8, 3), date(2026, 8, 4)]
        # 3) 数据断崖之后
        cliff = [date(2026, 9, 7), date(2026, 9, 8), date(2026, 10, 12)]
        for label, days in (("PROD_SNAPSHOT", prod), ("CONTROL", ctrl), ("CLIFF", cliff)):
            for d in days:
                try:
                    row = replay(cur, d)
                except Exception as exc:  # noqa: BLE001
                    row = {"trade_date": d.isoformat(), "error": f"{type(exc).__name__}:{exc}"}
                row["group"] = label
                out["days"].append(row)
    finally:
        conn.close()

    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    hdr = f"{'group':14s} {'date':11s} {'old_cov':>8s} {'OLD':>6s} {'new_cov':>8s} {'NEW':>6s} {'bars':>6s} {'uni-active':>10s}"
    print(hdr)
    print("-" * len(hdr))
    for r in out["days"]:
        if "error" in r:
            print(f"{r['group']:14s} {r['trade_date']:11s} ERROR {r['error']}")
            continue
        print(f"{r['group']:14s} {r['trade_date']:11s} {r['coverage_OLD']:8.4f} "
              f"{r['verdict_OLD']:>6s} {r['coverage_NEW']:8.4f} {r['verdict_NEW']:>6s} "
              f"{r['bars_that_day']:6d} {r['keys_in_uni_not_active']:10d}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
