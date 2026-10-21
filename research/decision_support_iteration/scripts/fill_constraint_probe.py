"""成交约束证据：B0 入选标的的 T+1 开盘可成交性与入场滑点敏感性。

为什么需要这个（任务书 §1「覆盖率/成交约束证据」）
--------------------------------------------------
现有评价（`offline_eval.py:244-257`）的成交模型只有一条约束：

    unfillable = next_open.isna() or next_open >= 理论涨停价

并且**假设以 T+1 开盘价零滑点成交**。这留下三个未被检验的乐观来源：

  F1 零滑点假设：报告的收益是「按开盘价成交」。若入选标的系统性地**高开**，
     则边际的一部分是**买不到的价格**，而非可实现的优势。
  F2 只排除「一字板」：`next_open >= 涨停价` 只抓开盘即涨停。
     开盘价接近涨停（如涨停价的 99%）时同样难以按该价成交。
  F3 未报告可成交子集占比：`offline_metrics.py:120` 直接**剔除** unfillable
     样本，但不披露剔除比例与其对结论的敏感度。

本脚本在**不改变任何选股规则**的前提下，只做成交层的量化：
  · 复现 B0 入选集合（口径完全照抄 `offline_eval.py`）
  · 剔除 unfillable（与现有一致）
  · 报告 T+1 开盘相对 T 收盘的跳空分布
  · 报告「开盘价 / 涨停价」的接近度分布
  · 做入场滑点敏感性：entry = next_open*(1+s)，s ∈ {0, 0.5%, 1%, 2%, 3%}
  · 做「排除高开标的」敏感性：k = 0 与 k = 1（对齐生产 T+1 收盘出场）

只读（仅 SELECT），不写库、不调用任何服务或下单接口。
"""
from __future__ import annotations
import os

import datetime as dt
import json
import pathlib
import re
import sys

import numpy as np
import pandas as pd
import pymysql

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

OUT = pathlib.Path(__file__).resolve().parents[1] / "fill_constraint.json"

# ---- 与 offline_eval.py 逐字一致的冻结参数（不得在此调优） ----
RULE_SCORE_THRESHOLD = 35.0
RULE_SCORE_MAX = 50.0
DEFAULT_TOP_N = 10
LOOKBACK_CALENDAR_DAYS = 60
MIN_BARS = 30
MIN_AVG_AMOUNT = 30_000.0
COMMISSION_RATE = 3.0 / 10_000
STAMP_TAX_RATE = 5.0 / 10_000
TRANSFER_FEE_RATE = 0.1 / 10_000
COST = COMMISSION_RATE * 2 + STAMP_TAX_RATE + TRANSFER_FEE_RATE * 2

START = dt.date(2026, 1, 5)
END = dt.date(2026, 8, 31)

DSN = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
           database="quant_db", charset="utf8mb4")
ALLOWED = re.compile(r"^\s*(SELECT|SHOW|DESCRIBE|DESC|EXPLAIN)\b", re.I)


# ==================== 与 offline_eval.py 相同的复刻函数 ====================
def java_round2(v: float) -> float:
    import math
    return math.floor(v * 100.0 + 0.5) / 100.0


def clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def resolve_limit_pct(code: str, exchange: str, name: str) -> float:
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


def valid_daily_bar(o, h, l, c) -> bool:
    for p in (o, h, l, c):
        if p is None or not np.isfinite(p) or p <= 0:
            return False
    return h >= max(o, c) and l <= min(o, c)


def read_sql(conn, sql: str, args=None) -> pd.DataFrame:
    if not ALLOWED.match(sql):
        raise RuntimeError(f"只允许只读语句，拒绝：{sql[:60]}")
    with conn.cursor() as cur:
        cur.execute(sql, args or ())
        rows = cur.fetchall()
        cols = [d[0] for d in cur.description]
    return pd.DataFrame(rows, columns=cols)


def load_prices(conn, start, end) -> pd.DataFrame:
    df = read_sql(conn, """
        SELECT stock_code, exchange_type, trade_date, open_price, high_price, low_price,
               close_price, pre_close, volume, amount
        FROM market_data
        WHERE trade_date BETWEEN %s AND %s
        ORDER BY stock_code, exchange_type, trade_date
    """, (start, end))
    df = df.rename(columns={"stock_code": "code", "exchange_type": "ex",
                            "trade_date": "date", "open_price": "open",
                            "high_price": "high", "low_price": "low",
                            "close_price": "close"})
    for col in ("open", "high", "low", "close", "pre_close", "amount"):
        df[col] = pd.to_numeric(df[col], errors="coerce").astype("float64")
    df["volume"] = pd.to_numeric(df["volume"], errors="coerce").fillna(0).astype("int64")
    df["date"] = pd.to_datetime(df["date"])
    return df


def load_universe(conn) -> pd.DataFrame:
    df = read_sql(conn, "SELECT stock_code, exchange_type, stock_name, list_date, status FROM stock_info")
    df = df.rename(columns={"stock_code": "code", "exchange_type": "ex",
                            "stock_name": "name"})
    df["list_date"] = pd.to_datetime(df["list_date"], errors="coerce")
    df["status"] = pd.to_numeric(df["status"], errors="coerce")
    return df


# ==================== B0 入选集合复现 ====================
def build_picks() -> pd.DataFrame:
    warm = START - dt.timedelta(days=LOOKBACK_CALENDAR_DAYS * 2)
    conn = pymysql.connect(**DSN)
    try:
        px = load_prices(conn, warm, END)
        uni = load_universe(conn)
        days = read_sql(conn, """
            SELECT DISTINCT trade_date FROM market_data
            WHERE trade_date BETWEEN %s AND %s ORDER BY trade_date
        """, (START, END))
    finally:
        conn.close()

    days = [pd.Timestamp(d).date() for d in days["trade_date"]]
    print(f"[load] rows={len(px):,} universe={len(uni):,} signal_days={len(days)}", flush=True)

    px = px.sort_values(["code", "ex", "date"]).reset_index(drop=True)
    g = px.groupby(["code", "ex"], sort=False)

    px["ret5"] = px["close"] / g["close"].shift(5) - 1.0
    prev_vol5 = g["volume"].transform(
        lambda s: s.shift(1).rolling(5, min_periods=5).mean())
    px["vol_ratio"] = px["volume"] / prev_vol5
    px["ma20"] = g["close"].transform(lambda s: s.rolling(20, min_periods=20).mean())
    px["ma_pos"] = px["close"] / px["ma20"] - 1.0
    px["avg_amount20"] = g["amount"].transform(
        lambda s: s.rolling(20, min_periods=20).mean())
    px["bars_in_window"] = g.cumcount() + 1

    # 全量 vectorize 打分（2.46M 行，逐行 apply 太慢）
    ret5 = px["ret5"].to_numpy(dtype="float64")
    vr_raw = px["vol_ratio"].to_numpy(dtype="float64")
    ma_pos = px["ma_pos"].to_numpy(dtype="float64")

    mom = np.where(np.isfinite(ret5),
                   np.clip(ret5 * 100.0 * 0.4, -15.0, 20.0), 0.0)
    vr = np.where(np.isfinite(vr_raw), np.floor(vr_raw * 100.0 + 0.5) / 100.0, np.nan)
    vol = np.where(np.isfinite(vr), np.clip((vr - 1.0) * 10.0, -10.0, 15.0), 0.0)
    tr = np.where(np.isfinite(ma_pos), np.clip(ma_pos * 15.0, -10.0, 15.0), 0.0)
    px["rule_score"] = np.clip(mom + vol + tr, 0.0, RULE_SCORE_MAX)

    px = px[(px["date"].dt.date >= START) & (px["date"].dt.date <= END)].copy()
    uni_idx = uni.set_index(["code", "ex"])
    px = px.join(uni_idx[["name", "list_date", "status"]], on=["code", "ex"])

    px["limit_pct"] = [resolve_limit_pct(c, e, n) for c, e, n in
                       zip(px["code"], px["ex"], px["name"].fillna(""))]
    px["limit_up_close"] = [round(pc * (1 + lp), 2) if np.isfinite(pc) and pc > 0 else np.nan
                            for pc, lp in zip(px["pre_close"], px["limit_pct"])]
    px["is_limit_up_close"] = px["close"] >= px["limit_up_close"]
    px["valid_bar"] = [valid_daily_bar(o, h, l, c) for o, h, l, c in
                       zip(px["open"], px["high"], px["low"], px["close"])]
    px["is_st"] = px["name"].fillna("").str.upper().str.contains("ST")

    # 单次布尔过滤：逐次 `pool = pool[~mask]` 会让后续 mask 的索引与被裁剪后的
    # DataFrame 不匹配（pandas 会 reindex），可能静默算错。这里一次性算完所有条件。
    keep = (
        (px["bars_in_window"] >= MIN_BARS)
        & px["valid_bar"].fillna(False)
        & (~px["is_st"].fillna(False))
        & (~px["is_limit_up_close"].fillna(False))
        & (px["avg_amount20"] >= MIN_AVG_AMOUNT)
        & px["status"].notna() & (px["status"] == 1)
        & px["list_date"].notna()
        & (px["list_date"] <= px["date"])
    )
    pool = px[keep].copy()

    # ---- 前向行情（与 offline_eval.py:230-249 同构） ----
    key = ["code", "ex"]
    nxt = px[key + ["date", "open", "high", "low", "close", "pre_close", "limit_pct"]].copy()
    nxt = nxt.sort_values(key + ["date"])
    gg = nxt.groupby(key, sort=False)
    nxt["next_date"] = gg["date"].shift(-1)
    nxt["next_open"] = gg["open"].shift(-1)
    nxt["next_high"] = gg["high"].shift(-1)
    nxt["next_low"] = gg["low"].shift(-1)
    nxt["next_close"] = gg["close"].shift(-1)
    nxt["next_pre_close"] = gg["pre_close"].shift(-1)
    nxt["next_limit_pct"] = gg["limit_pct"].shift(-1)
    nxt = nxt[key + ["date", "next_date", "next_open", "next_high", "next_low",
                     "next_close", "next_pre_close", "next_limit_pct"]]
    pool = pool.merge(nxt, on=key + ["date"], how="left", suffixes=("", "_n"))

    # ---- 选出 B0（阈值 + TopN，逐日） ----
    cand = pool[pool["rule_score"] >= RULE_SCORE_THRESHOLD].copy()
    cand = cand.sort_values(["date", "rule_score"], ascending=[True, False])
    picks = cand.groupby("date", sort=True).head(DEFAULT_TOP_N).copy()

    # ---- T+1 成交层指标 ----
    picks["next_limit_up"] = [
        round(pc * (1 + lp), 2) if (np.isfinite(pc) and pc > 0 and np.isfinite(lp)) else np.nan
        for pc, lp in zip(picks["next_pre_close"], picks["next_limit_pct"])
    ]
    picks["unfillable"] = (picks["next_open"].isna()
                           | (picks["next_open"] >= picks["next_limit_up"]))
    # 开盘价 / 理论涨停价：越接近 1 越难按开盘价买到
    with np.errstate(divide="ignore", invalid="ignore"):
        picks["open_vs_limit"] = picks["next_open"] / picks["next_limit_up"]
        picks["gap_open"] = picks["next_open"] / picks["close"] - 1.0
    # 一字板：T+1 最低价也触及涨停价（全天无低于涨停的成交）
    picks["one_word_board"] = picks["next_low"] >= picks["next_limit_up"]
    # 名义可成交但几乎无量的判断留给使用者；这里只报告数值

    meta = dict(start=str(START), end=str(END), topn=DEFAULT_TOP_N,
                rule_score_threshold=RULE_SCORE_THRESHOLD, cost_rate=COST,
                signal_days=len(days), scored_rows=int(len(pool)),
                total_picks=int(len(picks)))
    return picks, meta


# ==================== 统计 ====================
def boot_ci(x: np.ndarray, block: int = 5, n: int = 4000, seed: int = 7):
    """块自助 95% CI（与既有评价同法，保留日内相关性）。"""
    x = np.asarray(x, dtype="float64")
    x = x[np.isfinite(x)]
    if len(x) < 3:
        return [float("nan"), float("nan")]
    rng = np.random.default_rng(seed)
    nb = int(np.ceil(len(x) / block))
    means = []
    for _ in range(n):
        idx = []
        for _ in range(nb):
            s = rng.integers(0, len(x))
            idx.extend(range(s, min(s + block, len(x))))
        means.append(x[idx].mean())
    return [float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))]


def daily_series(df: pd.DataFrame, ret: pd.Series) -> pd.Series:
    """先按日取均值（等权组合日收益），再跨日统计。

    这与 `offline_metrics.py:121` 的口径**一致**：
        out[ret_col] = tradable.groupby("date")[ret_col].mean()
    不能直接用逐笔均值 —— 那会给「入选多的日子」更高权重，
    与冻结合同的组合口径不同（实测逐笔 1.905% vs 组合 1.530%）。
    当日全部不可成交 → 该日为空，不填 0。
    """
    tmp = pd.DataFrame({"date": df["date"].to_numpy(), "r": ret.to_numpy()})
    return tmp.groupby("date")["r"].mean().dropna()


def summarize(x, label: str) -> dict:
    x = pd.to_numeric(pd.Series(x), errors="coerce").dropna().to_numpy(dtype="float64")
    if not len(x):
        return dict(label=label, days=0)
    return dict(label=label, days=int(len(x)), mean_net_ret=float(x.mean()),
                median=float(np.median(x)),
                std=float(x.std(ddof=1)) if len(x) > 1 else 0.0,
                ci95_block_bootstrap=boot_ci(x),
                win_rate_daily=float((x > 0).mean()))


def main() -> int:
    picks, meta = build_picks()
    tradable = picks[~picks["unfillable"]].copy()
    print(f"[picks] total={len(picks)} unfillable={int(picks['unfillable'].sum())} "
          f"tradable={len(tradable)}", flush=True)

    report: dict = {"meta": meta, "coverage_and_fill": {}, "slippage": [], "gap_filter": {}}

    # ---- F3：可成交子集占比 ----
    report["coverage_and_fill"] = dict(
        total_picks=int(len(picks)),
        unfillable=int(picks["unfillable"].sum()),
        unfillable_ratio=float(picks["unfillable"].mean()),
        one_word_board=int(picks["one_word_board"].sum()),
        one_word_board_ratio=float(picks["one_word_board"].mean()),
        open_at_or_above_99pct_of_limit=int((picks["open_vs_limit"] >= 0.99).sum()),
        open_at_or_above_97pct_of_limit=int((picks["open_vs_limit"] >= 0.97).sum()),
    )

    # ---- F2：开盘价接近涨停的分布 ----
    ov = pd.to_numeric(tradable["open_vs_limit"], errors="coerce").dropna()
    report["open_vs_limit_quantiles"] = {
        q: float(ov.quantile(q)) for q in (0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99, 1.0)
    }

    # ---- 跳空分布 ----
    go = pd.to_numeric(tradable["gap_open"], errors="coerce").dropna()
    report["gap_open_quantiles"] = {
        q: float(go.quantile(q)) for q in (0.05, 0.25, 0.5, 0.75, 0.95)
    }
    report["gap_open_mean"] = float(go.mean())

    # ---- F1：入场滑点敏感性（逐日组合口径） ----
    t = tradable
    series = {}
    for s in (0.0, 0.005, 0.01, 0.02, 0.03):
        entry = t["next_open"] * (1.0 + s)
        ret = t["next_close"] / entry - 1.0 - COST
        ser = daily_series(t, ret)
        series[s] = ser
        row = summarize(ser, f"entry_slippage_{s*100:.1f}pct")
        row["slippage"] = s
        report["slippage"].append(row)

    # ---- 配对差（同一批日、同一批标的，只有入场滑点不同） ----
    # 这一步才是「滑点是否侵蚀优势」的判定证据：均值各自看 CI 会被共同方差掩盖。
    report["slippage_paired_vs_zero"] = []
    base = series[0.0]
    for s in (0.005, 0.01, 0.02, 0.03):
        common = base.index.intersection(series[s].index)
        diff = (series[s].loc[common] - base.loc[common]).dropna().to_numpy(dtype="float64")
        report["slippage_paired_vs_zero"].append(dict(
            slippage=s, days=int(len(diff)), mean_diff=float(diff.mean()),
            ci95_block_bootstrap=boot_ci(diff),
        ))

    # ---- 保本滑点：使组合均值为 0 的入场滑点 ----
    # 净收益 = next_close/(next_open*(1+s)) - 1 - cost；一阶近似下 s* ≈ mean_ret/cost_fold
    report["breakeven_slippage_approx"] = float(series[0.0].mean() / (1.0 + series[0.0].mean()))

    # ---- 与冻结基线的口径一致性校验 ----
    report["baseline_no_slippage"] = summarize(series[0.0], "no_slippage")
    report["baseline_matches_frozen"] = bool(
        abs(series[0.0].mean() - 0.015303550444946206) < 1e-12)

    # ---- 排除高开标的（只买当日开盘涨幅 < 阈值的） ----
    for thr in (0.03, 0.05, 0.07, 0.09):
        keep = t[pd.to_numeric(t["gap_open"], errors="coerce") < thr]
        ser = daily_series(keep, keep["next_close"] / keep["next_open"] - 1.0 - COST)
        row = summarize(ser, f"gap_lt_{thr*100:.0f}pct")
        row["dropped_picks"] = int(len(t) - len(keep))
        common = base.index.intersection(ser.index)
        diff = (ser.loc[common] - base.loc[common]).dropna().to_numpy(dtype="float64")
        row["paired_vs_unfiltered"] = dict(
            days=int(len(diff)), mean_diff=float(diff.mean()),
            ci95_block_bootstrap=boot_ci(diff))
        report["gap_filter"][f"gap_lt_{thr*100:.0f}pct"] = row

    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=1))
    print(f"[done] -> {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
