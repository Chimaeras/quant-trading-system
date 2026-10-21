"""离线样本外评价（评估合同 DS-EC-1）——严格只读，不写库、不调用服务。

目的：在**冻结基线**下，用与生产同口径的股票池/打分/成交约束，建立可复现的
B0 生产规则 vs B1 同池等权 vs B2 简单技术规则 对照，并报告覆盖率与不可成交比例。

口径来源（逐条对应生产源码，见 baseline.md）：
  · 打分 scoreT1：T1CandidateService.java:730-738
      动量 clamp(ret5*100*0.4, -15, 20)   （ret5 = close[T]/close[T-5]-1）
      量能 clamp((vr-1)*10, -10, 15)       （vr = 当日量/近5日（不含当日）均量，四舍五入 2 位）
      趋势 clamp(maPos*15, -10, 15)        （maPos = close[T]/MA20-1）
      再 clamp 到 [0, 50]
  · 入选阈值 35.0（t1_rule_v2）、TopN=10
  · 预过滤：窗口 ≥30 bar；validDailyBar；排除收盘涨停；流动性 avg_amount20 ≥ 30000（万元）
  · 可成交：T+1 开盘买入、T+1 收盘卖出；T+1 开盘涨停（一字板）→ unfillable
  · 费用：双边，参数显式列出

不做的（避免自欺）：
  · 不在本区间的数据上调参；阈值 35 作为**冻结基线**照用
  · 不把「T 日收盘→T+1 收盘」的方向统计当作可交易收益（仅作辅助对照）
  · 不剔除无候选日、不把未覆盖股票当成无信号
"""
from __future__ import annotations
import os

import argparse
import datetime as dt
import json
import math
import pathlib
import sys

import numpy as np
import pandas as pd
import pymysql

# ==================== 生产参数（冻结，不得在本评价中调优） ====================
RULE_SCORE_THRESHOLD = 35.0     # t1_rule_v2
RULE_SCORE_MAX = 50.0
DEFAULT_TOP_N = 10
LOOKBACK_CALENDAR_DAYS = 60     # 与 LOOKBACK_DAYS 一致（自然日）
MIN_BARS = 30
MIN_AVG_AMOUNT = 30_000.0       # 万元 ≈ 3 亿元/日
MIN_LISTING_DAYS = 60
ACTIVE_WINDOW_DAYS = 60         # 覆盖率分母的活跃窗口（与 P0-1 修复一致）

# ==================== 费用口径（显式，便于复核） ====================
COMMISSION_RATE = 3.0 / 10_000  # 双边佣金各 3bp
STAMP_TAX_RATE = 5.0 / 10_000   # 印花税（卖出单边）
TRANSFER_FEE_RATE = 0.1 / 10_000  # 过户费，双边

DB = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
          database="quant_db", charset="utf8mb4")


# ==================== 生产公式的忠实复刻 ====================
def java_round2(v: float) -> float:
    """Java Math.round(v*100)/100（正数下等价于 floor(x+0.5)）。"""
    return math.floor(v * 100.0 + 0.5) / 100.0


def clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def resolve_limit_pct(code: str, exchange: str, name: str) -> float:
    """TradingLimitRules.resolveLimitPct 的复刻。"""
    digits = "".join(ch for ch in (code or "") if ch.isdigit())
    norm = digits[-6:] if len(digits) > 6 else digits
    ex = (exchange or "").strip().upper()
    if ex == "BSE" or norm[:1] in {"8", "4"} or norm.startswith("92"):
        return 0.30
    if ex == "SSE" and (norm.startswith("688") or norm.startswith("689")):
        return 0.20
    if ex == "SZSE" and (norm.startswith("300") or norm.startswith("301") or norm.startswith("302")):
        return 0.20
    if (name or "").upper().find("ST") >= 0:
        return 0.05
    return 0.10


def is_limit_up_at_close(close: float, pre_close: float, limit_pct: float) -> bool:
    """StrategyBacktestEngine.isLimitUpAtClose 的复刻（收盘价 ≥ 涨停价且涨停价 > 0）。"""
    if pre_close is None or pre_close <= 0 or not np.isfinite(pre_close):
        return False
    limit_up = round(pre_close * (1.0 + limit_pct), 2)
    return limit_up > 0 and close >= limit_up


def valid_daily_bar(o, h, l, c) -> bool:
    for p in (o, h, l, c):
        if p is None or not np.isfinite(p) or p <= 0:
            return False
    return h >= max(o, c) and l <= min(o, c)


# ==================== 数据装载 ====================
def load_prices(conn, start: dt.date, end: dt.date) -> pd.DataFrame:
    """区间行情（含预热），一次取回后在内存里做窗口计算。"""
    sql = """
        SELECT stock_code, exchange_type, trade_date, open_price, high_price, low_price,
               close_price, pre_close, volume, amount
        FROM market_data
        WHERE trade_date BETWEEN %s AND %s
        ORDER BY stock_code, exchange_type, trade_date
    """
    with conn.cursor() as cur:
        cur.execute(sql, (start, end))
        rows = cur.fetchall()
    df = pd.DataFrame(rows, columns=["code", "ex", "date", "open", "high", "low",
                                     "close", "pre_close", "volume", "amount"])
    for col in ("open", "high", "low", "close", "pre_close", "amount"):
        df[col] = pd.to_numeric(df[col], errors="coerce").astype("float64")
    df["volume"] = pd.to_numeric(df["volume"], errors="coerce").fillna(0).astype("int64")
    df["date"] = pd.to_datetime(df["date"])
    return df


def load_universe(conn) -> pd.DataFrame:
    """证券主表（注意：security_master_history 为空 → 存在幸存者偏差，必须披露）。"""
    with conn.cursor() as cur:
        cur.execute("SELECT stock_code, exchange_type, stock_name, list_date, status FROM stock_info")
        rows = cur.fetchall()
    df = pd.DataFrame(rows, columns=["code", "ex", "name", "list_date", "status"])
    df["list_date"] = pd.to_datetime(df["list_date"], errors="coerce")
    return df


def load_trading_days(conn, start: dt.date, end: dt.date) -> list[dt.date]:
    with conn.cursor() as cur:
        cur.execute("""SELECT DISTINCT trade_date FROM market_data
                       WHERE trade_date BETWEEN %s AND %s ORDER BY trade_date""", (start, end))
        return [r[0] for r in cur.fetchall()]


# ==================== 打分 ====================
def compute_scores(df: pd.DataFrame) -> pd.DataFrame:
    """按 (code, ex) 分组计算 ret5 / ma20 / volume_ratio / rule_score。"""
    df = df.sort_values(["code", "ex", "date"]).copy()
    g = df.groupby(["code", "ex"], sort=False)

    df["close_5ago"] = g["close"].shift(5)
    df["ret5"] = df["close"] / df["close_5ago"] - 1.0

    df["ma20"] = g["close"].transform(lambda s: s.rolling(20, min_periods=20).mean())
    df["ma_pos"] = df["close"] / df["ma20"] - 1.0

    # volume_ratio：当日量 / 近5日（不含当日）均量，再四舍五入 2 位（与生产一致）
    df["vol_prev5_mean"] = g["volume"].transform(
        lambda s: s.shift(1).rolling(5, min_periods=1).mean())
    with np.errstate(divide="ignore", invalid="ignore"):
        vr = np.where(df["vol_prev5_mean"].to_numpy() > 0,
                      df["volume"].to_numpy() / df["vol_prev5_mean"].to_numpy(), 0.0)
    df["volume_ratio"] = [java_round2(float(v)) if np.isfinite(v) else 0.0 for v in vr]

    # avg_amount20：近 20 日（含当日）均额，与 passesMinAvgAmount 对齐
    df["avg_amount20"] = g["amount"].transform(lambda s: s.rolling(20, min_periods=1).mean())
    df["bars_in_window"] = g.cumcount() + 1

    mom = np.clip(df["ret5"].to_numpy() * 100.0 * 0.4, -15.0, 20.0)
    vol_score = np.clip((df["volume_ratio"].to_numpy() - 1.0) * 10.0, -10.0, 15.0)
    trend = np.clip(df["ma_pos"].to_numpy() * 15.0, -10.0, 15.0)
    raw = np.nan_to_num(mom) + np.nan_to_num(vol_score) + np.nan_to_num(trend)
    df["rule_score"] = np.clip(raw, 0.0, RULE_SCORE_MAX)
    return df


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", default="2026-01-05")
    ap.add_argument("--end", default="2026-08-31")
    ap.add_argument("--topn", type=int, default=DEFAULT_TOP_N)
    ap.add_argument("--out", default="research/decision_support_iteration")
    args = ap.parse_args()

    start = dt.date.fromisoformat(args.start)
    end = dt.date.fromisoformat(args.end)
    # 预热：多取 120 自然日，保证 T 日已有 ≥30 bar 与 MA20
    warm = start - dt.timedelta(days=180)

    conn = pymysql.connect(**DB)
    try:
        print(f"[load] market_data {warm} .. {end}", flush=True)
        px = load_prices(conn, warm, end)
        uni = load_universe(conn)
        days = [d for d in load_trading_days(conn, start, end)]
        print(f"[load] rows={len(px):,} universe={len(uni):,} signal_days={len(days)}", flush=True)
    finally:
        conn.close()

    px = compute_scores(px)
    # 只在信号日区间内评价（预热数据仅用于窗口计算）
    px = px[(px["date"].dt.date >= start) & (px["date"].dt.date <= end)].copy()

    uni_idx = uni.set_index(["code", "ex"])
    px = px.join(uni_idx[["name", "list_date", "status"]], on=["code", "ex"])

    # ---- 预过滤（与生产同口径） ----
    px["limit_pct"] = [resolve_limit_pct(c, e, n) for c, e, n in
                       zip(px["code"], px["ex"], px["name"].fillna(""))]
    px["limit_up_close"] = [round(pc * (1 + lp), 2) if np.isfinite(pc) and pc > 0 else np.nan
                            for pc, lp in zip(px["pre_close"], px["limit_pct"])]
    px["is_limit_up_close"] = px["close"] >= px["limit_up_close"]

    px["valid_bar"] = [valid_daily_bar(o, h, l, c) for o, h, l, c in
                       zip(px["open"], px["high"], px["low"], px["close"])]
    px["is_st"] = px["name"].fillna("").str.upper().str.contains("ST")

    reasons = {}
    pool = px.copy()
    n0 = len(pool)

    def drop(mask: pd.Series, label: str) -> None:
        nonlocal pool
        removed = int(mask.sum())
        if removed:
            reasons[label] = reasons.get(label, 0) + removed
        pool = pool[~mask]

    drop(pool["bars_in_window"] < MIN_BARS, "BARS_LT_30")
    drop(~pool["valid_bar"].fillna(False), "INVALID_BAR")
    drop(pool["is_st"].fillna(False), "ST")
    drop(pool["is_limit_up_close"].fillna(False), "LIMIT_UP_AT_CLOSE")
    drop(pool["avg_amount20"] < MIN_AVG_AMOUNT, "LOW_LIQUIDITY")
    drop(pool["status"].isna() | (pool["status"] != 1), "NOT_IN_UNIVERSE")
    drop(pool["list_date"].isna(), "NO_LIST_DATE")
    drop(pool["list_date"] > pool["date"], "NOT_YET_LISTED")

    print(f"[pool] {n0:,} -> {len(pool):,} rows; drop reasons:", json.dumps(reasons, ensure_ascii=False))

    # ---- 次日可成交收益（open[t+1] -> close[t+1]） + 不可成交判定 ----
    nxt = px[["code", "ex", "date", "open", "close", "pre_close", "limit_pct", "limit_up_close"]].copy()
    nxt["date"] = nxt["date"] - pd.Timedelta(days=0)
    key = ["code", "ex"]
    # 用「下一交易日的行」做 merge：对每个 (code, ex) 取 date 之后最近一行
    nxt = nxt.sort_values(key + ["date"])
    nxt["next_date"] = nxt.groupby(key, sort=False)["date"].shift(-1)
    nxt["next_open"] = nxt.groupby(key, sort=False)["open"].shift(-1)
    nxt["next_close"] = nxt.groupby(key, sort=False)["close"].shift(-1)
    nxt["next_pre_close"] = nxt.groupby(key, sort=False)["pre_close"].shift(-1)
    nxt["next_limit_pct"] = nxt.groupby(key, sort=False)["limit_pct"].shift(-1)
    nxt = nxt[key + ["date", "next_date", "next_open", "next_close", "next_pre_close", "next_limit_pct"]]

    pool = pool.merge(nxt, on=key + ["date"], how="left", suffixes=("", "_n"))

    # 次日开盘是否一字板（开盘价 ≥ 涨停价）→ 买不进
    pool["next_open_limit"] = [
        (round(pc * (1 + lp), 2) if np.isfinite(pc) and pc > 0 else np.nan)
        for pc, lp in zip(pool["next_pre_close"], pool["next_limit_pct"])
    ]
    pool["unfillable"] = pool["next_open"].isna() | (pool["next_open"] >= pool["next_open_limit"])

    with np.errstate(divide="ignore", invalid="ignore"):
        pool["gross_ret"] = pool["next_close"] / pool["next_open"] - 1.0
        # 辅助口径：T 收盘 -> T+1 收盘 方向（不可交易，仅对照）
        pool["dir_ret"] = pool["next_close"] / pool["close"] - 1.0

    cost = COMMISSION_RATE * 2 + STAMP_TAX_RATE + TRANSFER_FEE_RATE * 2
    pool["net_ret"] = pool["gross_ret"] - cost
    pool["dir_net_ret"] = pool["dir_ret"] - cost

    pool.to_pickle(pathlib.Path(args.out) / "cache_pool.pkl")
    meta = dict(start=str(start), end=str(end), topn=args.topn,
                rule_score_threshold=RULE_SCORE_THRESHOLD,
                cost_rate=cost, rows_scored=int(len(pool)),
                drop_reasons=reasons, signal_days=len(days))
    (pathlib.Path(args.out) / "cache_meta.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    print("[done] cached pool ->", pathlib.Path(args.out) / "cache_pool.pkl")
    print("[meta]", json.dumps(meta, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
