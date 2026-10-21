"""诊断：2026-09-07 为何 `NO_ELIGIBLE_CANDIDATE`（阈值 35.0 内无人）。

只读 SELECT，复刻生产 `scoreT1` 与预过滤（口径同 `offline_eval.py`），
输出该日 rule_score 的分布，特别是 **最大值**：
  · max < 35  → 当天确实无人过线（阈值/市场状态问题）
  · max >= 35 → 说明有票过线却没被选中，问题在别处（需继续查）

不改阈值——35.0 是冻结基线。
"""
from __future__ import annotations
import os

import datetime as dt
import math
import sys

import numpy as np
import pandas as pd
import pymysql

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

THRESHOLD = 35.0
MAX_SCORE = 50.0
LOOKBACK = 90
MIN_BARS = 30
MIN_AVG_AMOUNT = 30_000.0

DSN = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
           database="quant_db", charset="utf8mb4")

DATES = [dt.date(2026, 8, 11), dt.date(2026, 8, 18), dt.date(2026, 9, 4),
         dt.date(2026, 9, 7)]


def java_round2(v):
    return math.floor(v * 100.0 + 0.5) / 100.0


def resolve_limit_pct(code, exchange, name):
    digits = "".join(ch for ch in (code or "") if ch.isdigit())
    norm = digits[-6:] if len(digits) > 6 else digits
    ex = (exchange or "").strip().upper()
    if ex == "BSE" or norm[:1] in {"8", "4"} or norm.startswith("92"):
        return 0.30
    if ex == "SSE" and (norm.startswith("688") or norm.startswith("689")):
        return 0.20
    if ex == "SZSE" and norm.startswith(("300", "301", "302")):
        return 0.20
    if (name or "").upper().find("ST") >= 0:
        return 0.05
    return 0.10


def main() -> int:
    conn = pymysql.connect(**DSN)
    warm = min(DATES) - dt.timedelta(days=LOOKBACK)
    px = pd.read_sql("""
        SELECT stock_code, exchange_type, trade_date, open_price, high_price, low_price,
               close_price, pre_close, volume, amount
        FROM market_data WHERE trade_date BETWEEN %s AND %s
        ORDER BY stock_code, exchange_type, trade_date
    """, conn, params=(warm, max(DATES)))
    uni = pd.read_sql("SELECT stock_code, exchange_type, stock_name, list_date, status FROM stock_info",
                      conn)
    conn.close()
    px = px.rename(columns={"stock_code": "code", "exchange_type": "ex", "trade_date": "date",
                            "open_price": "open", "high_price": "high", "low_price": "low",
                            "close_price": "close"})
    px["date"] = pd.to_datetime(px["date"])
    for c in ("open", "high", "low", "close", "pre_close", "amount"):
        px[c] = pd.to_numeric(px[c], errors="coerce").astype("float64")
    px["volume"] = pd.to_numeric(px["volume"], errors="coerce").fillna(0).astype("int64")
    px = px.sort_values(["code", "ex", "date"]).reset_index(drop=True)
    g = px.groupby(["code", "ex"], sort=False)

    px["ret5"] = px["close"] / g["close"].shift(5) - 1.0
    px["vol_ratio"] = px["volume"] / g["volume"].transform(
        lambda s: s.shift(1).rolling(5, min_periods=5).mean())
    px["ma20"] = g["close"].transform(lambda s: s.rolling(20, min_periods=20).mean())
    px["ma_pos"] = px["close"] / px["ma20"] - 1.0
    px["avg_amount20"] = g["amount"].transform(lambda s: s.rolling(20, min_periods=20).mean())
    px["bars"] = g.cumcount() + 1

    ret5 = px["ret5"].to_numpy("float64")
    vr_raw = px["vol_ratio"].to_numpy("float64")
    ma_pos = px["ma_pos"].to_numpy("float64")
    mom = np.where(np.isfinite(ret5), np.clip(ret5 * 100.0 * 0.4, -15.0, 20.0), 0.0)
    vr = np.where(np.isfinite(vr_raw), np.floor(vr_raw * 100.0 + 0.5) / 100.0, np.nan)
    vol = np.where(np.isfinite(vr), np.clip((vr - 1.0) * 10.0, -10.0, 15.0), 0.0)
    tr = np.where(np.isfinite(ma_pos), np.clip(ma_pos * 15.0, -10.0, 15.0), 0.0)
    px["rule_score"] = np.clip(mom + vol + tr, 0.0, MAX_SCORE)
    px["mom"] = mom
    px["vol"] = vol
    px["tr"] = tr

    uni = uni.rename(columns={"stock_code": "code", "exchange_type": "ex",
                              "stock_name": "name"})
    uni["list_date"] = pd.to_datetime(uni["list_date"], errors="coerce")
    uni["status"] = pd.to_numeric(uni["status"], errors="coerce")
    px = px.join(uni.set_index(["code", "ex"])[["name", "list_date", "status"]],
                 on=["code", "ex"])
    px["limit_pct"] = [resolve_limit_pct(c, e, n) for c, e, n in
                       zip(px["code"], px["ex"], px["name"].fillna(""))]
    px["limit_up_close"] = [round(pc * (1 + lp), 2) if np.isfinite(pc) and pc > 0 else np.nan
                            for pc, lp in zip(px["pre_close"], px["limit_pct"])]
    px["is_st"] = px["name"].fillna("").str.upper().str.contains("ST")
    ok_bar = ((px["open"] > 0) & (px["high"] > 0) & (px["low"] > 0) & (px["close"] > 0)
              & (px["high"] >= px[["open", "close"]].max(axis=1))
              & (px["low"] <= px[["open", "close"]].min(axis=1)))
    keep = (ok_bar & (px["bars"] >= MIN_BARS) & (~px["is_st"].fillna(False))
            & (~(px["close"] >= px["limit_up_close"]).fillna(False))
            & (px["avg_amount20"] >= MIN_AVG_AMOUNT)
            & px["status"].notna() & (px["status"] == 1)
            & px["list_date"].notna() & (px["list_date"] <= px["date"]))
    pool = px[keep]

    print(f"阈值（冻结）= {THRESHOLD}")
    for d in DATES:
        sub = pool[pool["date"].dt.date == d]
        if sub.empty:
            print(f"\n{d}: 池内 0 行")
            continue
        s = sub["rule_score"]
        print(f"\n{d}: 池内 {len(sub):>5} 行  score max={s.max():.2f} "
              f"p99={s.quantile(0.99):.2f} p95={s.quantile(0.95):.2f} median={s.median():.2f}")
        print(f"   过线(>=35) 数量 = {int((s >= THRESHOLD).sum())}")
        top = sub.nlargest(5, "rule_score")[["code", "ex", "rule_score", "mom", "vol", "tr"]]
        for _, r in top.iterrows():
            print(f"     {r['code']}|{r['ex']:<5} score={r['rule_score']:>6.2f} "
                  f"mom={r['mom']:>6.2f} vol={r['vol']:>6.2f} tr={r['tr']:>6.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
