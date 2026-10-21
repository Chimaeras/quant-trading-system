#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R003 预注册前的**可行性探针**（只读，不含任何假设检验）。

用途：在写 R003 的预注册之前，先确认三件事能不能**在本地算出来**：
  1. 估算法价差（Corwin–Schultz 高低价估计量，日频）的可算比例与量级；
  2. 另一条独立估计路线（Roll 1984 月度序列协方差）的可算比例；
  3. R001 反转分位组合的**换手率**量级（NMV 的核心变量）。

本脚本**不做**任何判定、不设阈值、不产出结论——它只回答「数据够不够」。
真正的判定写在 `rounds/R003_prereg.md`（本脚本落盘之后才写）。

只读：仅执行 SELECT，不建表、不写库、不改任何生产文件。

用法：
    python research/paper_loop/baselines/probe_cost_feasibility.py [--json 路径]
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

try:
    import pymysql
except ImportError:  # pragma: no cover
    print("需要 pymysql：pip install pymysql", file=sys.stderr)
    sys.exit(2)

HERE = Path(__file__).resolve().parent
R001_DIR = HERE.parent / "experiments" / "R001_reversal"
_spec = importlib.util.spec_from_file_location("r001", R001_DIR / "run_reversal_ic.py")
r001 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(r001)

DB = {
    "host": os.environ.get("QUANT_DB_HOST", "localhost"),
    "port": int(os.environ.get("QUANT_DB_PORT", "3306")),
    "user": os.environ.get("QUANT_DB_USER", "root"),
    "password": os.environ.get("QUANT_DB_PASSWORD", os.environ.get("DB_PASSWORD", "")),
    "database": os.environ.get("QUANT_DB_NAME", "quant_db"),
    "charset": "utf8mb4",
}

PANEL_START = "2023-11-01"   # 与 R001 的 load_panel 同起点（保证 20 日窗口可用）
WINDOW = ("2024-01-02", "2026-09-07")

# 与 R001 主配置一致，仅供换手率探针使用
N_SIG, H_HOLD = 20, 3

# Corwin–Schultz 的常数
_CS_K = 3.0 - 2.0 * math.sqrt(2.0)


def log(msg: str) -> None:
    print(msg, flush=True)


def load_hl() -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame, dict]:
    """取 high/low/close 面板（只读）。"""
    conn = pymysql.connect(**DB)
    try:
        sql = (
            "SELECT stock_code, trade_date, high_price, low_price, close_price "
            "FROM market_data WHERE trade_date >= %s "
            "ORDER BY trade_date, stock_code, id"
        )
        raw = pd.read_sql(sql, conn, params=(PANEL_START,))
    finally:
        conn.close()
    meta = {"rows_loaded": int(len(raw))}
    raw["stock_code"] = raw["stock_code"].astype(str)
    raw["trade_date"] = pd.to_datetime(raw["trade_date"])
    for c in ("high_price", "low_price", "close_price"):
        raw[c] = pd.to_numeric(raw[c], errors="coerce")
    before = len(raw)
    raw = raw.drop_duplicates(subset=["stock_code", "trade_date"], keep="last")
    meta["duplicate_rows_dropped"] = int(before - len(raw))
    bad = (
        raw["high_price"].isna() | (raw["high_price"] <= 0)
        | raw["low_price"].isna() | (raw["low_price"] <= 0)
        | raw["close_price"].isna() | (raw["close_price"] <= 0)
    )
    meta["rows_nonpositive_dropped"] = int(bad.sum())
    raw = raw[~bad]
    hl = raw.pivot(index="trade_date", columns="stock_code", values="high_price").sort_index()
    lo = raw.pivot(index="trade_date", columns="stock_code", values="low_price").sort_index()
    cl = raw.pivot(index="trade_date", columns="stock_code", values="close_price").sort_index()
    return hl, lo, cl, meta


def corwin_schultz_daily(hl: pd.DataFrame, lo: pd.DataFrame) -> pd.DataFrame:
    """逐「相邻两日窗口」的 Corwin–Schultz 有效价差（比例），负数截断为 0。

    S = 2(e^a - 1)/(1 + e^a)
    a = (sqrt(2b) - sqrt(b))/(3-2sqrt2) - sqrt(g/(3-2sqrt2))
    b = ln(H_t/L_t)^2 + ln(H_{t+1}/L_{t+1})^2
    g = ln(max(H_t,H_{t+1})/min(L_t,L_{t+1}))^2
    """
    # 用 numpy 直接算：h1/l1 与 h2/l2 来自同一 frame 的相邻切片，列序一致，
    # 不能用 DataFrame.combine（它会按 index 做外连接，把行数撑大）。
    H1, L1 = hl.iloc[:-1].to_numpy(dtype=float), lo.iloc[:-1].to_numpy(dtype=float)
    H2, L2 = hl.iloc[1:].to_numpy(dtype=float), lo.iloc[1:].to_numpy(dtype=float)
    ok = ((H1 > 0) & (L1 > 0) & (H2 > 0) & (L2 > 0) & (H1 >= L1) & (H2 >= L2))
    Hmax = np.maximum(H1, H2)
    Lmin = np.minimum(L1, L2)
    with np.errstate(divide="ignore", invalid="ignore"):
        b = np.log(H1 / L1) ** 2 + np.log(H2 / L2) ** 2
        g = np.log(Hmax / Lmin) ** 2
        a = (np.sqrt(2.0 * b) - np.sqrt(b)) / _CS_K - np.sqrt(g / _CS_K)
        s = 2.0 * (np.exp(a) - 1.0) / (1.0 + np.exp(a))
    s = np.where(ok, s, np.nan)
    s = np.where(np.isfinite(s), s, np.nan)
    neg = int(np.nansum(s < 0))
    s = np.clip(s, 0.0, None)
    out = pd.DataFrame(s, index=hl.index[1:], columns=hl.columns)
    return out, neg, int(np.isfinite(s).sum())


def roll_monthly(cl: pd.DataFrame) -> tuple[pd.Series, dict]:
    """Roll(1984) 月度有效价差：S = 2*sqrt(-Cov(r_t, r_{t-1}))，协方差>0 记 0。

    注意：两列相乘必须**按位置**对齐。`r.iloc[1:] * r.iloc[:-1]` 的 index 标签不同，
    pandas 会按标签做外连接，把「本行×本行」当成「本行×上一行」（自乘 → AC≈1）。
    这里统一用 `.shift()` 在同一 index 上取值，避免该陷阱。
    """
    m = cl.resample("ME").last()
    r = np.log(m / m.shift(1))
    r = r.iloc[1:]                      # 丢掉首个 NaN 月
    dm = r - r.mean()
    cov = (dm * dm.shift(1)).mean(skipna=True)      # Cov(r_t, r_{t-1})，同 index 对齐
    sd = r.std(ddof=1)
    ac1 = cov / (sd * sd)
    cov2 = (dm * dm.shift(2)).mean(skipna=True)
    ac2 = cov2 / (sd * sd)
    neg_cov = int((cov < 0).sum())
    s = np.sqrt((-cov).clip(lower=0.0)) * 2.0
    return s, {
        "stock_months": int(r.size), "neg_covariances": neg_cov,
        "computable": int(cov.notna().sum()), "months": int(r.shape[0]),
        "ac1_median": float(ac1.median()), "ac1_p10": float(ac1.quantile(0.10)),
        "ac1_p90": float(ac1.quantile(0.90)), "ac1_negative_share": float((ac1 < 0).mean()),
        "ac2_median": float(ac2.median()),
    }


def tick_floor_probe(cl: pd.DataFrame, per_stock_spread: pd.Series) -> dict:
    """模型自由的「一跳」下界 + CS 量级的合理性对照。

    A 股最小报价单位 0.01 元 → 单边最少跨 0.005 元 → 双边最少 0.01 元。
    以收益率表示：round_trip_tick = 0.01 / P。这是**不可回避的下界**（与波动率无关）。
    同时把 CS 估计换算成「几跳」，用于判断估计量是否量级可信。
    """
    win = (cl.index >= WINDOW[0]) & (cl.index <= WINDOW[1])
    px = cl.loc[win]
    med_px = px.median(axis=0, skipna=True)
    tick_bp = 0.01 / med_px * 1e4
    common = med_px.index.intersection(per_stock_spread.index)
    cs_bp = per_stock_spread.loc[common] * 1e4
    tb = tick_bp.loc[common]
    ticks = cs_bp / tb
    return {
        "median_price_cny": float(med_px.median()),
        "price_p10": float(med_px.quantile(0.10)),
        "price_p90": float(med_px.quantile(0.90)),
        "tick_round_trip_bp_median": float(tb.median()),
        "tick_round_trip_bp_p90": float(tb.quantile(0.90)),
        "cs_spread_in_ticks_median": float(ticks.median()),
        "cs_spread_in_ticks_p90": float(ticks.quantile(0.90)),
        "stocks_with_cs_over_4_ticks_share": float((ticks > 4.0).mean()),
    }


def turnover_probe() -> dict:
    """R001 主配置（N=20,H=3）分位组合的换手率量级。"""
    panel, _ = r001.load_panel()
    close, openp, pre, vol = panel["close"], panel["open"], panel["pre"], panel["vol"]
    sig = close / close.shift(N_SIG) - 1.0
    entry = openp.shift(-1)
    exit_ = close.shift(-(1 + H_HOLD))
    pre_entry = pre.shift(-1)
    pre_exit = pre.shift(-(1 + H_HOLD))
    code_pct = pd.Series({c: r001.limit_pct(c) for c in close.columns}, dtype="float64")
    suspended = (vol.shift(-1) <= 0) | (vol.shift(-(1 + H_HOLD)) <= 0) | (vol <= 0)
    not_buyable = entry >= pre_entry.mul(code_pct + 1.0, axis=1) * (1.0 - r001.LIMIT_EPS)
    not_sellable = exit_ <= pre_exit.mul(1.0 - code_pct, axis=1) * (1.0 + r001.LIMIT_EPS)
    valid = (sig.notna() & entry.notna() & exit_.notna() & entry.gt(0) & exit_.gt(0)
             & ~suspended & ~not_buyable & ~not_sellable)
    sig_np, use = sig.to_numpy(), valid.to_numpy()
    dates = close.index
    win = (dates >= WINDOW[0]) & (dates <= WINDOW[1])

    prev_d1 = prev_d10 = None
    jac_d1, jac_d10, n_used = [], [], 0
    for i in np.nonzero(win)[0]:
        f = use[i]
        if int(f.sum()) < r001.MIN_NAMES_PER_DATE:
            continue
        cur_d1 = set(np.array(close.columns)[f][np.argsort(sig_np[i][f])[:10]])
        cur_d10 = set(np.array(close.columns)[f][np.argsort(sig_np[i][f])[-10:]])
        if prev_d1 is not None:
            jac_d1.append(len(cur_d1 & prev_d1) / 10.0)
            jac_d10.append(len(cur_d10 & prev_d10) / 10.0)
        prev_d1, prev_d10 = cur_d1, cur_d10
        n_used += 1

    def pct(a, q):
        a = np.asarray(a, dtype=float)
        return float(np.nanpercentile(a, q)) if a.size else float("nan")

    one_sided_d1 = 1.0 - float(np.nanmean(jac_d1))
    one_sided_d10 = 1.0 - float(np.nanmean(jac_d10))
    td_per_month = 21.0
    return {
        "days_used": n_used,
        "one_sided_turnover_daily_D1": one_sided_d1,
        "one_sided_turnover_daily_D10": one_sided_d10,
        "one_sided_turnover_monthly_D1": one_sided_d1 * td_per_month,
        "one_sided_turnover_monthly_D10": one_sided_d10 * td_per_month,
        "one_sided_turnover_annual_D1": one_sided_d1 * 242.0,
        "one_sided_turnover_annual_D10": one_sided_d10 * 242.0,
        "jaccard_overlap_daily_median_D1": pct(jac_d1, 50),
        "jaccard_overlap_daily_median_D10": pct(jac_d10, 50),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default=str(HERE / "B003_cost_feasibility.json"))
    args = ap.parse_args()

    hl, lo, cl, meta = load_hl()
    log(f"[probe] hl 面板 {hl.shape[0]} 交易日 x {hl.shape[1]} 代码  "
        f"{str(hl.index[0])[:10]} -> {str(hl.index[-1])[:10]}")
    meta["hl_dates"] = int(hl.shape[0])
    meta["hl_codes"] = int(hl.shape[1])

    cs, neg_daily, n_cs = corwin_schultz_daily(hl, lo)
    win = (cs.index >= WINDOW[0]) & (cs.index <= WINDOW[1])
    cs_w = cs.loc[win]
    total_cells = int(cs_w.shape[0] * cs_w.shape[1])
    valid_cells = int(cs_w.notna().sum().sum())
    per_stock = cs_w.mean(axis=0, skipna=True)          # 每只股票在窗口内的日均 S（比例）
    per_stock_ok = per_stock.dropna()
    log(f"[probe] CS 可算单元格 {valid_cells}/{total_cells} "
        f"= {valid_cells / max(total_cells, 1):.3%}，负估计截断数（全样本）={neg_daily}")
    log(f"[probe] CS 每股日均价差（bp）中位数={per_stock_ok.median() * 1e4:.2f} "
        f"p25={per_stock_ok.quantile(0.25) * 1e4:.2f} "
        f"p75={per_stock_ok.quantile(0.75) * 1e4:.2f} "
        f"p90={per_stock_ok.quantile(0.90) * 1e4:.2f}")

    roll, roll_meta = roll_monthly(cl)
    roll_ok = roll.dropna()
    log(f"[probe] Roll 月度：{roll_meta}")
    log(f"[probe] Roll 带宽中位数={roll_ok.median() * 1e4:.2f}bp（可算 {len(roll_ok)}/{cl.shape[1]} 只）")

    tf = tick_floor_probe(cl, per_stock)
    log(f"[probe] 价格中位数={tf['median_price_cny']:.2f} 元 → 一跳双边下界中位数"
        f"={tf['tick_round_trip_bp_median']:.2f}bp p90={tf['tick_round_trip_bp_p90']:.2f}bp")
    log(f"[probe] CS 估计折合跳数：中位数={tf['cs_spread_in_ticks_median']:.2f} 跳 "
        f"p90={tf['cs_spread_in_ticks_p90']:.2f} 跳；CS>4 跳的股票占比"
        f"={tf['stocks_with_cs_over_4_ticks_share']:.1%}")

    tob = turnover_probe()
    log(f"[probe] 单边换手：D1 日 {tob['one_sided_turnover_daily_D1']:.3f} "
        f"→ 月 {tob['one_sided_turnover_monthly_D1']:.2f} "
        f"→ 年 {tob['one_sided_turnover_annual_D1']:.1f}；D10 年 "
        f"{tob['one_sided_turnover_annual_D10']:.1f}")

    out = {
        "probe": "R003 预注册前可行性探针（无假设检验、无阈值）",
        "window": list(WINDOW),
        "panel_meta": meta,
        "corwin_schultz": {
            "cells_total": total_cells,
            "cells_valid": valid_cells,
            "cell_valid_rate": valid_cells / max(total_cells, 1),
            "negative_estimates_truncated_all_sample": neg_daily,
            "per_stock_mean_spread_bp": {
                "p25": float(per_stock_ok.quantile(0.25) * 1e4),
                "median": float(per_stock_ok.median() * 1e4),
                "mean": float(per_stock_ok.mean() * 1e4),
                "p75": float(per_stock_ok.quantile(0.75) * 1e4),
                "p90": float(per_stock_ok.quantile(0.90) * 1e4),
            },
            "stocks_estimable": int(len(per_stock_ok)),
        },
        "roll_monthly": {
            **roll_meta,
            "spread_bp_p25": float(roll_ok.quantile(0.25) * 1e4),
            "spread_bp_median": float(roll_ok.median() * 1e4),
            "spread_bp_p75": float(roll_ok.quantile(0.75) * 1e4),
            "stocks_estimable": int(len(roll_ok)),
        },
        "tick_floor": tf,
        "frozen_cost_bp": {
            "commission_buy": r001.COMMISSION * 1e4,
            "sell_tax": r001.SELL_TAX * 1e4,
            "slippage_one_side": r001.SLIPPAGE * 1e4,
            "round_trip_total": (r001.COMMISSION + r001.SELL_TAX + 2 * r001.SLIPPAGE) * 1e4,
        },
        "turnover": tob,
        "note": "本文件不含任何判定；判定见 rounds/R003_prereg.md（本文件之后落盘）。",
    }
    Path(args.json).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"[probe] 写出 {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
