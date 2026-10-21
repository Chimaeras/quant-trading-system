"""第 11 轮证据：把离线评估窗口**外推**到数据边界，确认断崖后果。

已知（本轮已取证）：「断崖」不是市场休市，而是**入库管线部分失败**——
2026-09-08 之后只有 249 只 SZSE 标的在更新，其余 5,235 只停在 2026-09-07。
那么按生产规则（阈值 35.0、topn 10）在 2026-09-08..2026-10-12 会产生什么？

本脚本用与第 9 轮 `fill_constraint_probe.py` **完全相同**的打分与过滤口径，
只把窗口往后延，输出：
  · 各日池内标的数（衡量「可评分宇宙」萎缩程度）
  · 各日是否有标的过阈值
  · 逐日组合净收益（若存在入选）
并把 2026-01-05..2026-08-31 的冻结基线一并重算，确认复现仍然逐位一致
（若基线不一致，说明脚本被改坏，后续外推结论不采信）。

只读 SELECT。
"""
from __future__ import annotations
import os

import json
import math
import pathlib
import sys

import numpy as np
import pandas as pd
import pymysql

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

THRESHOLD = 35.0
MAX_SCORE = 50.0
MIN_BARS = 30
MIN_AVG_AMOUNT = 30_000.0
DEFAULT_TOP_N = 10
COST = 0.00112

FROZEN = dict(total_picks=215, rows_scored=248337,
              mean_net_ret=0.015303550444946206)

DSN = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
           database="quant_db", charset="utf8mb4")

OUT = pathlib.Path(__file__).resolve().parents[1] / "cliff_impact.json"


def resolve_limit_pct(code, exchange, name):
    digits = "".join(ch for ch in (code or "") if ch.isdigit())
    norm = digits[-6:] if len(digits) > 6 else digits
    ex = (exchange or "").strip().upper()
    if ex == "BSE" or norm[:1] in {"8", "4"} or norm.startswith("92"):
        return 0.30
    if ex == "SSE" and norm.startswith(("688", "689")):
        return 0.20
    if ex == "SZSE" and norm.startswith(("300", "301", "302")):
        return 0.20
    if (name or "").upper().find("ST") >= 0:
        return 0.05
    return 0.10


def load(start: str, end: str):
    conn = pymysql.connect(**DSN)
    try:
        px = pd.read_sql("""
            SELECT stock_code, exchange_type, trade_date, open_price, high_price,
                   low_price, close_price, pre_close, volume, amount
            FROM market_data WHERE trade_date BETWEEN %s AND %s
            ORDER BY stock_code, exchange_type, trade_date
        """, conn, params=(start, end))
        uni = pd.read_sql(
            "SELECT stock_code, exchange_type, stock_name, list_date, status FROM stock_info",
            conn)
    finally:
        conn.close()
    px = px.rename(columns={"stock_code": "code", "exchange_type": "ex",
                            "trade_date": "date", "open_price": "open",
                            "high_price": "high", "low_price": "low",
                            "close_price": "close"})
    px["date"] = pd.to_datetime(px["date"])
    for col in ("open", "high", "low", "close", "pre_close", "amount"):
        px[col] = pd.to_numeric(px[col], errors="coerce").astype("float64")
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
    # 下一日可成交性（用于净收益与 unfillable）
    px["next_open"] = g["open"].shift(-1)
    px["next_close"] = g["close"].shift(-1)
    px["next_pre_close"] = g["pre_close"].shift(-1)
    px["next_low"] = g["low"].shift(-1)

    ret5 = px["ret5"].to_numpy("float64")
    vr_raw = px["vol_ratio"].to_numpy("float64")
    ma_pos = px["ma_pos"].to_numpy("float64")
    mom = np.where(np.isfinite(ret5), np.clip(ret5 * 100.0 * 0.4, -15.0, 20.0), 0.0)
    vr = np.where(np.isfinite(vr_raw), np.floor(vr_raw * 100.0 + 0.5) / 100.0, np.nan)
    vol = np.where(np.isfinite(vr), np.clip((vr - 1.0) * 10.0, -10.0, 15.0), 0.0)
    tr = np.where(np.isfinite(ma_pos), np.clip(ma_pos * 15.0, -10.0, 15.0), 0.0)
    px["rule_score"] = np.clip(mom + vol + tr, 0.0, MAX_SCORE)

    uni = uni.rename(columns={"stock_code": "code", "exchange_type": "ex",
                              "stock_name": "name"})
    uni["list_date"] = pd.to_datetime(uni["list_date"], errors="coerce")
    uni["status"] = pd.to_numeric(uni["status"], errors="coerce")
    px = px.join(uni.set_index(["code", "ex"])[["name", "list_date", "status"]],
                 on=["code", "ex"])
    px["limit_pct"] = [resolve_limit_pct(c0, e0, n0) for c0, e0, n0 in
                       zip(px["code"], px["ex"], px["name"].fillna(""))]
    px["next_limit_up"] = [round(pc * (1 + lp), 2) if np.isfinite(pc) and pc > 0 else np.nan
                           for pc, lp in zip(px["next_pre_close"], px["limit_pct"])]
    px["is_st"] = px["name"].fillna("").str.upper().str.contains("ST")
    ok_bar = ((px["open"] > 0) & (px["high"] > 0) & (px["low"] > 0) & (px["close"] > 0)
              & (px["high"] >= px[["open", "close"]].max(axis=1))
              & (px["low"] <= px[["open", "close"]].min(axis=1)))
    keep = (ok_bar & (px["bars"] >= MIN_BARS) & (~px["is_st"].fillna(False))
            & (~(px["close"] >= [round(pc * (1 + lp), 2) if np.isfinite(pc) and pc > 0
                                 else np.nan
                                 for pc, lp in zip(px["pre_close"], px["limit_pct"])])
               ).fillna(False)
            & (px["avg_amount20"] >= MIN_AVG_AMOUNT)
            & px["status"].notna() & (px["status"] == 1)
            & px["list_date"].notna() & (px["list_date"] <= px["date"]))
    return px[keep].copy()


def portfolio_daily(df: pd.DataFrame) -> pd.Series:
    """先按日取均值，再跨日 —— 与 offline_metrics.py:121 同口径。"""
    return df.groupby("date")["net_ret"].mean()


def main() -> int:
    # 多取 90 天做回看预热
    px = load("2025-10-01", "2026-10-12")
    print(f"可评分行数 = {len(px)}")

    win_a = ("2026-01-05", "2026-08-31")
    win_b = ("2026-09-08", "2026-10-12")

    out = {}
    for label, (s, e) in (("frozen_A", win_a), ("cliff_B", win_b)):
        sub = px[(px["date"] >= s) & (px["date"] <= e)].copy()
        elig = sub[sub["rule_score"] >= THRESHOLD].copy()
        tops = (elig.sort_values(["date", "rule_score"], ascending=[True, False])
                .groupby("date").head(DEFAULT_TOP_N).copy())
        unfill = tops["next_open"].isna() | (tops["next_open"] >= tops["next_limit_up"])
        tops["unfillable"] = unfill
        tradable = tops[~unfill].copy()
        tradable["net_ret"] = (tradable["next_close"] / tradable["next_open"] - 1.0) - COST
        daily = portfolio_daily(tradable)
        info = dict(
            signal_days=int(sub["date"].nunique()),
            rows_scored=int(len(sub)),
            eligible_total=int(len(elig)),
            total_picks=int(len(tops)),
            unfillable=int(unfill.sum()),
            days_with_picks=int(daily.size),
            mean_net_ret=(None if daily.empty else float(daily.mean())),
        )
        out[label] = info
        print(f"\n--- {label} {s}..{e} ---")
        for k, v in info.items():
            print(f"  {k} = {v}")
        # 逐日池内规模
        per_day = sub.groupby("date").size()
        print(f"  每日池内规模: min={per_day.min()} median={int(per_day.median())} max={per_day.max()}")

    print("\n=== 冻结基线复现校验（窗口 A）===")
    a = out["frozen_A"]
    checks = {
        "total_picks == 215": a["total_picks"] == FROZEN["total_picks"],
        "rows_scored == 248337": a["rows_scored"] == FROZEN["rows_scored"],
        "mean_net_ret == 冻结值": (a["mean_net_ret"] is not None and
                                   abs(a["mean_net_ret"] - FROZEN["mean_net_ret"]) < 1e-12),
    }
    for k, v in checks.items():
        print(f"  [{'PASS' if v else 'FAIL'}] {k}")
    out["baseline_matches_frozen"] = all(checks.values())
    if not out["baseline_matches_frozen"]:
        print("  !! 基线不复现 —— 后续外推结论不采信")

    print("\n=== 断崖窗口逐日明细 ===")
    sub_b = px[(px["date"] >= win_b[0]) & (px["date"] <= win_b[1])]
    for d, grp in sub_b.groupby("date"):
        over = int((grp["rule_score"] >= THRESHOLD).sum())
        print(f"  {d.date()}  池内={len(grp):>4}  过阈值={over:>3}  "
              f"最高分={grp['rule_score'].max():.2f}")

    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n已写出 {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
