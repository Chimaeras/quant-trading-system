"""第 3 轮：多窗口 walk-forward + 持有期敏感性（评估合同 DS-EC-1）。

目的：替换第 2 轮的单次 1-5 月/6-8 月切分，回答两个问题：
  W  规则的尾部优势是否在**每个**滚动窗口都成立（而非只在某个窗口）？
  H  收益是否只是 T+1 单日噪声（换持有期后是否还在）？

方法：
  · 滚动窗口：训练 W_train 个交易日用于「定型」（按训练区间挑阈值/形态），
    随后在紧邻的 W_valid 个交易日上验证，步长 = W_valid（不重叠验证段）。
  · 两种定型规则并列，避免把「阈值搜索的噪声」当成结论：
      fixed   —— 生产冻结阈值 35.0（不随窗口变化）
      fitted  —— 在训练窗口内从网格挑出均值最高的阈值，再套用到验证窗口
  · 持有期 k ∈ {1,2,3,5,10}：T+1 开盘买入 → T+k 收盘卖出。
    费用：买入 3bp 佣金 + 0.1bp 过户；卖出 3bp 佣金 + 5bp 印花税 + 0.1bp 过户；
    每多持有一天加 0.1bp 过户。

只读：不改任何生产代码、不写库。
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import pathlib
import sys

import numpy as np
import pandas as pd
import pymysql

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from offline_eval import (  # noqa: E402
    DB, MIN_AVG_AMOUNT, MIN_BARS, RULE_SCORE_MAX, compute_scores, is_limit_up_at_close,
    load_prices, load_universe, resolve_limit_pct, valid_daily_bar,
)
from offline_metrics import block_bootstrap_ci, norm_ci  # noqa: E402

BASE = pathlib.Path("research/decision_support_iteration")
THRESHOLD_GRID = (25.0, 30.0, 32.0, 35.0, 38.0, 40.0, 42.0, 45.0)
FROZEN_THRESHOLD = 35.0
HOLDING_PERIODS = (1, 2, 3, 5, 10)
TOPN = 10

# 费用分解（与 R2 一致，可复核）
BUY_COST = 3.0 / 10_000 + 0.1 / 10_000          # 佣金 + 过户
SELL_COST = 3.0 / 10_000 + 5.0 / 10_000 + 0.1 / 10_000  # 佣金 + 印花税 + 过户
EXTRA_DAY_COST = 0.1 / 10_000                    # 每多持有一天


def cost_for_holding(k: int) -> float:
    """持有 k 个交易日（T+1 开盘买，T+k 收盘卖）的总费用。"""
    return BUY_COST + SELL_COST + EXTRA_DAY_COST * (k - 1)


def build_panel(start: dt.date, end: dt.date) -> pd.DataFrame:
    warm = start - dt.timedelta(days=180)
    conn = pymysql.connect(**DB)
    try:
        px = load_prices(conn, warm, end)
        uni = load_universe(conn)
    finally:
        conn.close()
    px = compute_scores(px)
    px = px[(px["date"].dt.date >= start) & (px["date"].dt.date <= end)].copy()
    px = px.join(uni.set_index(["code", "ex"])[["name", "list_date", "status"]], on=["code", "ex"])

    px["limit_pct"] = [resolve_limit_pct(c, e, n) for c, e, n in
                       zip(px["code"], px["ex"], px["name"].fillna(""))]
    px["limit_up_close"] = [round(pc * (1 + lp), 2) if np.isfinite(pc) and pc > 0 else np.nan
                            for pc, lp in zip(px["pre_close"], px["limit_pct"])]
    px["is_limit_up_close"] = px["close"] >= px["limit_up_close"]
    px["valid_bar"] = [valid_daily_bar(o, h, l, c) for o, h, l, c in
                       zip(px["open"], px["high"], px["low"], px["close"])]
    px["is_st"] = px["name"].fillna("").str.upper().str.contains("ST")

    # ---- 先在全量面板上算多周期前向收益（保持索引对齐），再过滤 ----
    px = px.sort_values(["code", "ex", "date"]).reset_index(drop=True)
    g = px.groupby(["code", "ex"], sort=False)
    px["fwd_open"] = g["open"].shift(-1)
    px["next_pre_close"] = g["pre_close"].shift(-1)
    px["next_limit_pct"] = g["limit_pct"].shift(-1)
    px["next_open_limit"] = [
        (round(pc * (1 + lp), 2) if np.isfinite(pc) and pc > 0 else np.nan)
        for pc, lp in zip(px["next_pre_close"], px["next_limit_pct"])
    ]
    # 不可成交：T+1 开盘一字板（开盘价 >= 涨停价），或次日无数据
    px["unfillable"] = px["fwd_open"].isna() | (px["fwd_open"] >= px["next_open_limit"])

    for k in HOLDING_PERIODS:
        exit_close = g["close"].shift(-k)
        with np.errstate(divide="ignore", invalid="ignore"):
            px[f"gross_h{k}"] = exit_close / px["fwd_open"] - 1.0
        px[f"net_h{k}"] = px[f"gross_h{k}"] - cost_for_holding(k)
        px[f"has_h{k}"] = exit_close.notna() & px["fwd_open"].notna()

    # 预过滤（与生产同口径）
    keep = (
        (px["bars_in_window"] >= MIN_BARS)
        & px["valid_bar"].fillna(False)
        & (~px["is_st"].fillna(False))
        & (~px["is_limit_up_close"].fillna(False))
        & (px["avg_amount20"] >= MIN_AVG_AMOUNT)
        & (px["status"] == 1)
        & px["list_date"].notna()
        & (px["list_date"] <= px["date"])
    )
    return px[keep].copy()


def picks_for(pool: pd.DataFrame, thr: float, k: int) -> pd.DataFrame:
    sub = pool[(pool["rule_score"] >= thr) & pool[f"has_h{k}"].fillna(False)].copy()
    sub = sub.sort_values(["date", "rule_score"], ascending=[True, False])
    return sub.groupby("date", sort=True).head(TOPN)


def daily_net(pool: pd.DataFrame, mask: pd.Series, k: int, rank_col=None) -> pd.Series:
    sub = pool[mask.fillna(False) & pool[f"has_h{k}"].fillna(False)].copy()
    if sub.empty:
        return pd.Series(dtype=float)
    if rank_col is not None:
        sub = sub.sort_values(["date", rank_col], ascending=[True, False])
        sub = sub.groupby("date", sort=True).head(TOPN)
    trad = sub[~sub["unfillable"]]
    return trad.groupby("date")[f"net_h{k}"].mean()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rebuild", action="store_true", help="重新从 DB 构建面板并缓存")
    ap.add_argument("--start", default="2026-01-05")
    ap.add_argument("--end", default="2026-08-31")
    args = ap.parse_args()

    cache = BASE / "cache_panel_hold.pkl"
    if args.rebuild or not cache.exists():
        print("[panel] building from DB ...", flush=True)
        panel = build_panel(dt.date.fromisoformat(args.start), dt.date.fromisoformat(args.end))
        panel.to_pickle(cache)
        print(f"[panel] rows={len(panel):,} -> cached {cache} "
              f"({cache.stat().st_size / 1e6:.0f} MB)", flush=True)
    panel = pd.read_pickle(cache)
    days = sorted(panel["date"].unique())
    print(f"[panel] rows={len(panel):,} signal_days={len(days)}")

    out = {}

    # ============ H 持有期敏感性（全区间）============
    print("\n=== H 持有期敏感性（2026-01-05..2026-08-31，全区间）===")
    hold = []
    for k in HOLDING_PERIODS:
        b0 = daily_net(panel, panel["rule_score"] >= FROZEN_THRESHOLD, k)
        b1 = daily_net(panel, panel["rule_score"].notna(), k)
        b2a = daily_net(panel, panel["ret5"].notna(), k, rank_col="ret5")
        common = b0.index.intersection(b1.index)
        d = (b0.loc[common] - b1.loc[common]).dropna()
        cm2 = b0.index.intersection(b2a.index)
        d2 = (b0.loc[cm2] - b2a.loc[cm2]).dropna()
        rec = {
            "holding_days": k, "cost": cost_for_holding(k),
            "b0_days": int(b0.size), "b0_mean": float(b0.mean()) if b0.size else None,
            "b0_ci_block": list(block_bootstrap_ci(b0.to_numpy())) if b0.size else None,
            "b1_mean": float(b1.mean()), "b2a_mean": float(b2a.mean()),
            "b0_minus_b1": float(d.mean()) if d.size else None,
            "b0_minus_b1_ci": list(block_bootstrap_ci(d.to_numpy())) if d.size else None,
            "b0_minus_b2a": float(d2.mean()) if d2.size else None,
            "b0_minus_b2a_ci": list(block_bootstrap_ci(d2.to_numpy())) if d2.size else None,
        }
        hold.append(rec)
        print(f"  k={k:>2}d cost={rec['cost'] * 100:.2f}bp  B0={rec['b0_mean']:+.5f} "
              f"CI={[round(x, 5) for x in rec['b0_ci_block']]}  B1={rec['b1_mean']:+.5f}  "
              f"B2a={rec['b2a_mean']:+.5f}")
        print(f"        B0-B1={rec['b0_minus_b1']:+.5f} CI={[round(x, 5) for x in rec['b0_minus_b1_ci']]}"
              f"   B0-B2a={rec['b0_minus_b2a']:+.5f} CI={[round(x, 5) for x in rec['b0_minus_b2a_ci']]}")
    out["holding_sensitivity"] = hold

    # ============ W 多窗口 walk-forward（固定 k=1）============
    print("\n=== W 多窗口 walk-forward（T+1 收盘退出，k=1）===")
    k = 1
    folds_out = []
    for w_train, w_valid in ((60, 20), (80, 20), (60, 40)):
        print(f"\n  --- 训练 {w_train} 日 / 验证 {w_valid} 日（步长=验证长度）---")
        starts = list(range(0, max(0, len(days) - w_train - w_valid + 1), w_valid))
        for si in starts:
            tr_days = days[si:si + w_train]
            va_days = days[si + w_train:si + w_train + w_valid]
            if len(va_days) < 5:
                continue
            tr = panel[panel["date"].isin(tr_days)]
            va = panel[panel["date"].isin(va_days)]

            # --- fixed 阈值 35 ---
            fx_va = daily_net(va, va["rule_score"] >= FROZEN_THRESHOLD, k)
            fx_va_b1 = daily_net(va, va["rule_score"].notna(), k)

            # --- fitted：在训练窗口内按均值挑阈值 ---
            scored = []
            for thr in THRESHOLD_GRID:
                dtr = daily_net(tr, tr["rule_score"] >= thr, k)
                if dtr.size >= 10:  # 训练窗口内至少 10 个有候选日才允许参与择优
                    scored.append((float(dtr.mean()), thr))
            best = max(scored)[1] if scored else None
            ft_va = daily_net(va, va["rule_score"] >= best, k) if best else pd.Series(dtype=float)

            rec = {
                "w_train": w_train, "w_valid": w_valid,
                "train_range": [str(pd.Timestamp(tr_days[0]).date()), str(pd.Timestamp(tr_days[-1]).date())],
                "valid_range": [str(pd.Timestamp(va_days[0]).date()), str(pd.Timestamp(va_days[-1]).date())],
                "valid_days": len(va_days),
                "fitted_threshold": best,
                "fixed_b0_days": int(fx_va.size),
                "fixed_b0_mean": float(fx_va.mean()) if fx_va.size else None,
                "fixed_b1_mean": float(fx_va_b1.mean()) if fx_va_b1.size else None,
                "fitted_b0_days": int(ft_va.size),
                "fitted_b0_mean": float(ft_va.mean()) if ft_va.size else None,
            }
            if fx_va.size and fx_va_b1.size:
                common = fx_va.index.intersection(fx_va_b1.index)
                dd = (fx_va.loc[common] - fx_va_b1.loc[common]).dropna()
                rec["fixed_b0_minus_b1"] = float(dd.mean())
            folds_out.append(rec)
            print(f"    验证 {rec['valid_range'][0]}..{rec['valid_range'][1]} "
                  f"fitted_thr={best}  fixed: days={rec['fixed_b0_days']:>2} "
                  f"mean={rec['fixed_b0_mean'] if rec['fixed_b0_mean'] is None else round(rec['fixed_b0_mean'], 5)} "
                  f"B1={round(rec['fixed_b1_mean'], 5) if rec['fixed_b1_mean'] is not None else None} "
                  f"fitted_mean={rec['fitted_b0_mean'] if rec['fitted_b0_mean'] is None else round(rec['fitted_b0_mean'], 5)}")
    out["walk_forward"] = folds_out

    # ---- walk-forward 汇总 ----
    print("\n  === walk-forward 汇总（固定阈值 35）===")
    fx = [f for f in folds_out if f["fixed_b0_mean"] is not None]
    if fx:
        vals = np.array([f["fixed_b0_mean"] for f in fx], dtype=float)
        diffs = np.array([f.get("fixed_b0_minus_b1", np.nan) for f in fx], dtype=float)
        diffs = diffs[np.isfinite(diffs)]
        summary = {
            "n_folds": len(fx),
            "folds_positive": int((vals > 0).sum()),
            "folds_ci_excludes_zero": None,  # 单折样本太少，不做逐折 CI
            "mean_of_fold_means": float(vals.mean()),
            "min_fold_mean": float(vals.min()), "max_fold_mean": float(vals.max()),
            "stdev_of_fold_means": float(vals.std(ddof=1)) if vals.size > 1 else None,
            "mean_of_fold_diffs_vs_b1": float(diffs.mean()) if diffs.size else None,
            "folds_diff_positive": int((diffs > 0).sum()) if diffs.size else 0,
            "pooled_ci_block": list(block_bootstrap_ci(vals)),
        }
        print(f"    折数={summary['n_folds']}  正收益折数={summary['folds_positive']}  "
              f"折均值={summary['mean_of_fold_means']:+.5f} "
              f"(min={summary['min_fold_mean']:+.5f}, max={summary['max_fold_mean']:+.5f}, "
              f"sd={summary['stdev_of_fold_means']})")
        print(f"    vs B1：折均值差={summary['mean_of_fold_diffs_vs_b1']:+.5f}  "
              f"为正折数={summary['folds_diff_positive']}/{len(diffs)}")
        out["walk_forward_summary_fixed"] = summary

    ft = [f for f in folds_out if f["fitted_b0_mean"] is not None]
    if ft:
        fvals = np.array([f["fitted_b0_mean"] for f in ft], dtype=float)
        oos = np.mean([f["fitted_threshold"] == FROZEN_THRESHOLD for f in ft])
        print(f"\n    定型阈值统计：{len(ft)} 折中等于 35 的比例={oos:.2f}；"
              f"折均值={fvals.mean():+.5f}；为正折数={int((fvals > 0).sum())}")
        out["walk_forward_summary_fitted"] = {
            "n_folds": len(ft), "mean_of_fold_means": float(fvals.mean()),
            "folds_positive": int((fvals > 0).sum()),
            "share_folds_choosing_35": float(oos),
            "thresholds_chosen": [f["fitted_threshold"] for f in ft],
        }

    # ---- 合并池化：把 (w_train=80,w_valid=20) 这一组配置的验证段去重后合并 ----
    # 注意：所有折的验证段联合起来**不是全区间**——1-3 月没有样本外的 walk-forward 证据，
    # 因为它们只能作为早期训练窗口。该覆盖缺口必须与结论同读。
    print("\n  === 池化：单个配置下验证段的并集（去重）===")
    pooled_records = []
    for cfg in ((80, 20), (60, 20), (60, 40)):
        folds = [f for f in folds_out if (f["w_train"], f["w_valid"]) == cfg
                 and f["fixed_b0_mean"] is not None]
        if not folds:
            continue
        all_days = set()
        for f in folds:
            d0 = pd.Timestamp(f["valid_range"][0])
            d1 = pd.Timestamp(f["valid_range"][1])
            all_days |= {d for d in days if d0 <= d <= d1}
        sub = panel[panel["date"].isin(sorted(all_days))]
        b0 = daily_net(sub, sub["rule_score"] >= FROZEN_THRESHOLD, k)
        b1 = daily_net(sub, sub["rule_score"].notna(), k)
        common = b0.index.intersection(b1.index)
        diff = (b0.loc[common] - b1.loc[common]).dropna()
        rec = {
            "config": f"train{cfg[0]}_valid{cfg[1]}",
            "union_days": len(all_days),
            "b0_days": int(b0.size), "b0_mean": float(b0.mean()) if b0.size else None,
            "b0_ci_block": list(block_bootstrap_ci(b0.to_numpy())) if b0.size else None,
            "b1_mean": float(b1.mean()),
            "diff_mean": float(diff.mean()) if diff.size else None,
            "diff_ci_block": list(block_bootstrap_ci(diff.to_numpy())) if diff.size else None,
            "share_days_b0_beats_b1": float((diff > 0).mean()) if diff.size else None,
            "union_range": [str(pd.Timestamp(min(all_days)).date()), str(pd.Timestamp(max(all_days)).date())],
        }
        pooled_records.append(rec)
        print(f"    {rec['config']}: 并集 {rec['union_range'][0]}..{rec['union_range'][1]} "
              f"({rec['union_days']} 日; B0 有候选 {rec['b0_days']} 日)")
        print(f"        B0={rec['b0_mean']:+.5f} CI={[round(x, 5) for x in rec['b0_ci_block']]}  "
              f"B1={rec['b1_mean']:+.5f}")
        print(f"        B0-B1={rec['diff_mean']:+.5f} CI={[round(x, 5) for x in rec['diff_ci_block']]}  "
              f"B0 胜出日占比={rec['share_days_b0_beats_b1']:.3f}")
    out["walk_forward_pooled_union"] = pooled_records

    # ---- 扩展窗口：更早的训练窗口以覆盖 1-3 月 ----
    print("\n  === 扩展覆盖检验：把 walk-forward 起点提前到 2 月 ===")
    for w_train, w_valid in ((40, 20), (50, 20)):
        sub_days = days
        fold_recs = []
        for si in range(0, max(0, len(sub_days) - w_train - w_valid + 1), w_valid):
            va_days = sub_days[si + w_train:si + w_train + w_valid]
            if len(va_days) < 5:
                continue
            va = panel[panel["date"].isin(va_days)]
            d = daily_net(va, va["rule_score"] >= FROZEN_THRESHOLD, k)
            d1 = daily_net(va, va["rule_score"].notna(), k)
            fold_recs.append({
                "valid_range": [str(pd.Timestamp(va_days[0]).date()), str(pd.Timestamp(va_days[-1]).date())],
                "b0_days": int(d.size), "b0_mean": float(d.mean()) if d.size else None,
                "b1_mean": float(d1.mean()) if d1.size else None,
            })
        ok = [f for f in fold_recs if f["b0_mean"] is not None]
        if ok:
            vals = np.array([f["b0_mean"] for f in ok])
            print(f"    train{w_train}/valid{w_valid}: {len(ok)} 折，"
                  f"最早验证段={ok[0]['valid_range'][0]}，正收益折数={int((vals > 0).sum())}/{len(ok)}，"
                  f"折均值={vals.mean():+.5f} (min={vals.min():+.5f}, max={vals.max():+.5f})")
            out.setdefault("walk_forward_extended", []).append({
                "w_train": w_train, "w_valid": w_valid, "n_folds": len(ok),
                "earliest_valid": ok[0]["valid_range"][0],
                "folds_positive": int((vals > 0).sum()),
                "mean_of_fold_means": float(vals.mean()),
                "min_fold_mean": float(vals.min()), "max_fold_mean": float(vals.max()),
                "folds": fold_recs,
            })

    (BASE / "eval_walkforward_holding.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print("\n[written]", BASE / "eval_walkforward_holding.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
