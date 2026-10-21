"""离线样本外评价：指标计算（评估合同 DS-EC-1）。

比较对象：
  B0 冻结基线   = 生产 scoreT1 >= 35 的 TopN（默认 10）
  B0b 同规则放宽 = scoreT1 >= 35 的**全部**（用来看阈值本身的区分度）
  B1 同池等权   = 当日合格池全部等权（基准）
  B2a 简单透明  = 纯 ret5 排序 TopN
  B2b 简单透明  = 纯量比排序 TopN

指标（每个都同时报样本数与覆盖率）：
  · 扣费后净收益（open[t+1] -> close[t+1]）均值 / 中位数 / 胜率
  · 相对同池等权（B1）的**日度配对差**（同信号日、同池，避免混池比较）
  · 命中率（毛收益 > 0）与 T 收盘->T+1 收盘方向命中率（辅助，标注不可交易）
  · unfillable 比例（T+1 开盘一字板或次日无数据）
  · 集中度：单只标的在全部入选中的占比（Top10 集中风险）
  · 95% CI：对**日度**序列做 i.i.d. 与 block bootstrap 两种
"""
from __future__ import annotations

import datetime as dt
import json
import pathlib
import sys

import numpy as np
import pandas as pd

RNG_SEED = 20261013
THRESHOLD = 35.0  # t1_rule_v2 冻结阈值（取自 cache_meta，此处仅作默认）


def block_bootstrap_ci(x: np.ndarray, *, block: int = 5, n: int = 5000,
                       seed: int = RNG_SEED) -> tuple[float, float]:
    """对时间序列做 moving-block bootstrap 的均值 95% CI。"""
    x = np.asarray(x, dtype=float)
    x = x[np.isfinite(x)]
    if x.size < 3:
        return (float("nan"), float("nan"))
    rng = np.random.default_rng(seed)
    k = max(1, min(block, x.size))
    n_blocks = int(np.ceil(x.size / k))
    starts_max = x.size - k
    if starts_max < 1:
        return (float("nan"), float("nan"))
    means = np.empty(n)
    for i in range(n):
        s = rng.integers(0, starts_max + 1, size=n_blocks)
        sample = np.concatenate([x[j:j + k] for j in s])[:x.size]
        means[i] = sample.mean()
    return (float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5)))


def norm_ci(x: np.ndarray) -> tuple[float, float]:
    x = np.asarray(x, dtype=float)
    x = x[np.isfinite(x)]
    if x.size < 3:
        return (float("nan"), float("nan"))
    se = x.std(ddof=1) / np.sqrt(x.size)
    return (float(x.mean() - 1.96 * se), float(x.mean() + 1.96 * se))


def summarize(name: str, daily: pd.DataFrame, *, eligible_col: str = "n_eligible",
              unfillable_col: str = "n_unfillable", ret_col: str = "net_ret") -> dict:
    """daily: 每个信号日一行的组合层序列。"""
    r = daily[ret_col].to_numpy(dtype=float)
    lo_n, hi_n = norm_ci(r)
    lo_b, hi_b = block_bootstrap_ci(r)
    total_ret = float(np.prod(1.0 + r) - 1.0) if r.size else float("nan")
    curve = np.cumprod(1.0 + np.nan_to_num(r))
    peak = np.maximum.accumulate(curve)
    mdd = float(np.max(1.0 - curve / peak)) if curve.size else float("nan")
    return {
        "strategy": name,
        "days": int(r.size),
        "mean_net_ret": float(np.nanmean(r)) if r.size else float("nan"),
        "median_net_ret": float(np.nanmedian(r)) if r.size else float("nan"),
        "std_net_ret": float(np.nanstd(r, ddof=1)) if r.size > 1 else float("nan"),
        "win_rate_daily": float(np.nanmean(r > 0)) if r.size else float("nan"),
        "ci95_norm": [lo_n, hi_n],
        "ci95_block_bootstrap": [lo_b, hi_b],
        "cum_return": total_ret,
        "max_drawdown": mdd,
        "avg_picks_per_day": float(daily["n_picks"].mean()) if "n_picks" in daily else float("nan"),
        "total_picks": int(daily["n_picks"].sum()) if "n_picks" in daily else 0,
        "avg_eligible_per_day": float(daily[eligible_col].mean()) if eligible_col in daily else float("nan"),
        "total_unfillable": int(daily[unfillable_col].sum()) if unfillable_col in daily else 0,
        "unfillable_ratio": (float(daily[unfillable_col].sum() / max(1, daily["n_picks"].sum()))
                             if "n_picks" in daily else float("nan")),
        "hit_rate_gross": float(np.nanmean(daily["hit_rate"].to_numpy(dtype=float)))
        if "hit_rate" in daily else float("nan"),
        "coverage_picks_over_pool": (float(daily["n_picks"].sum() / max(1, daily["n_eligible_sum"].sum()))
                                     if "n_eligible_sum" in daily else float("nan")),
        "zero_pick_days": int((daily["n_picks"] == 0).sum()) if "n_picks" in daily else 0,
    }


def portfolio_daily(pool: pd.DataFrame, mask: pd.Series, rank_col: str | None,
                    topn: int | None, *, ret_col: str = "net_ret") -> pd.DataFrame:
    """按信号日聚合成组合层日度序列（等权）。"""
    sub = pool[mask.fillna(False)].copy()
    if sub.empty:
        return pd.DataFrame(columns=["date", "n_picks", "n_eligible", "n_eligible_sum",
                                     "n_unfillable", ret_col, "hit_rate"])
    if rank_col is not None:
        sub = sub.sort_values(["date", rank_col], ascending=[True, False])
        if topn is not None:
            sub = sub.groupby("date", sort=True).head(topn)
    # 净收益在可成交标的上计算；不可成交的按“不参与”处理，但必须计数披露
    elig = pool[pool["date"].isin(sub["date"].unique())]
    per_day_elig = elig.groupby("date").size().rename("n_eligible")
    per_day_elig_sum = elig.groupby("date").size().rename("n_eligible_sum")
    g = sub.groupby("date")
    out = pd.DataFrame({
        "n_picks": g.size(),
        "n_unfillable": g["unfillable"].sum(),
        "avg_gross": g["gross_ret"].mean(),
        "hit_rate": g["gross_ret"].apply(lambda s: float((s > 0).mean())),
        "dir_hit_rate": g["dir_ret"].apply(lambda s: float((s > 0).mean())),
    })
    tradable = sub[~sub["unfillable"]]
    out[ret_col] = tradable.groupby("date")[ret_col].mean()
    out["dir_net_ret"] = tradable.groupby("date")["dir_net_ret"].mean()
    out = out.join(per_day_elig).join(per_day_elig_sum)
    # 当日全部不可成交 → 该日收益缺失（不做 0 填充）
    return out.reset_index().rename(columns={"index": "date"})


def main() -> int:
    ap = __import__("argparse").ArgumentParser()
    ap.add_argument("--dir", default="research/decision_support_iteration")
    ap.add_argument("--topn", type=int, default=10)
    args = ap.parse_args()

    base = pathlib.Path(args.dir)
    pool = pd.read_pickle(base / "cache_pool.pkl")
    meta = json.loads((base / "cache_meta.json").read_text(encoding="utf-8"))
    print(f"[pool] rows={len(pool):,} days={pool['date'].nunique()} "
          f"threshold={meta['rule_score_threshold']} cost={meta['cost_rate']}")

    results = []
    daily_tables = {}

    specs = [
        ("B0_production_top10", pool["rule_score"] >= THRESHOLD, "rule_score", args.topn),
        ("B0b_threshold_all", pool["rule_score"] >= THRESHOLD, "rule_score", None),
        ("B1_pool_equal_weight", pool["rule_score"].notna(), None, None),
        ("B2a_ret5_top10", pool["ret5"].notna(), "ret5", args.topn),
        ("B2b_volume_ratio_top10", pool["volume_ratio"].notna(), "volume_ratio", args.topn),
    ]
    for name, mask, rank, topn in specs:
        d = portfolio_daily(pool, mask, rank, topn)
        daily_tables[name] = d
        results.append(summarize(name, d))
        n_days = len(d)
        print(f"  {name:<24} days={n_days:>3} picks={int(d['n_picks'].sum()):>6} "
              f"mean_net={results[-1]['mean_net_ret']:+.5f} "
              f"hit={results[-1]['hit_rate_gross']:.3f} "
              f"unfill={results[-1]['unfillable_ratio']:.4f}")

    # ---- 相对同池等权（B1）的日度配对差 ----
    paired = []
    b1 = daily_tables["B1_pool_equal_weight"].set_index("date")["net_ret"]
    for name in ("B0_production_top10", "B0b_threshold_all", "B2a_ret5_top10", "B2b_volume_ratio_top10"):
        other = daily_tables[name].set_index("date")["net_ret"]
        common = b1.index.intersection(other.index)
        diff = (other.loc[common] - b1.loc[common]).dropna()
        lo_n, hi_n = norm_ci(diff.to_numpy())
        lo_b, hi_b = block_bootstrap_ci(diff.to_numpy())
        rec = {
            "strategy": name, "vs": "B1_pool_equal_weight", "paired_days": int(diff.size),
            "mean_diff": float(diff.mean()) if diff.size else float("nan"),
            "ci95_norm": [lo_n, hi_n], "ci95_block_bootstrap": [lo_b, hi_b],
            "share_days_positive": float((diff > 0).mean()) if diff.size else float("nan"),
        }
        paired.append(rec)
        print(f"  [paired vs B1] {name:<24} days={rec['paired_days']:>3} "
              f"mean_diff={rec['mean_diff']:+.5f} CI_norm=[{lo_n:+.5f},{hi_n:+.5f}] "
              f"CI_block=[{lo_b:+.5f},{hi_b:+.5f}] pos_days={rec['share_days_positive']:.3f}")

    # ---- 分阶段（按自然月）----
    stage_rows = []
    for name, d in daily_tables.items():
        d = d.copy()
        d["month"] = pd.to_datetime(d["date"]).dt.to_period("M").astype(str)
        for m, g in d.groupby("month"):
            stage_rows.append({
                "strategy": name, "month": m, "days": int(len(g)),
                "picks": int(g["n_picks"].sum()),
                "mean_net_ret": float(g["net_ret"].mean()),
                "hit_rate": float(g["hit_rate"].mean()),
                "unfillable": int(g["n_unfillable"].sum()),
            })

    # ---- 集中度（B0 单只标的占比） ----
    sub = pool[pool["rule_score"] >= 35.0].sort_values(["date", "rule_score"], ascending=[True, False])
    top = sub.groupby("date", sort=True).head(args.topn)
    conc = top.groupby("code").size().sort_values(ascending=False)
    conc_top = conc.head(15)

    # ---- Rank IC（B0 打分对次日可成交净收益）----
    ic_rows = []
    for d, g in pool.groupby("date"):
        if len(g) < 30:
            continue
        rs = g["rule_score"].rank()
        rr = g["net_ret"].rank()
        ic_rows.append({"date": str(d), "n": len(g), "spearman": float(rs.corr(rr))})
    ic = pd.DataFrame(ic_rows)

    out = {
        "meta": meta,
        "cost_rate": meta["cost_rate"],
        "summaries": results,
        "paired_vs_B1": paired,
        "stage_by_month": stage_rows,
        "concentration_top15_codes": {str(k): int(v) for k, v in conc_top.items()},
        "rank_ic": {
            "days": int(len(ic)),
            "mean_spearman": float(ic["spearman"].mean()) if len(ic) else float("nan"),
            "median_spearman": float(ic["spearman"].median()) if len(ic) else float("nan"),
            "ci95_norm": list(norm_ci(ic["spearman"].to_numpy())) if len(ic) else [None, None],
            "share_positive": float((ic["spearman"] > 0).mean()) if len(ic) else float("nan"),
        },
    }
    (base / "eval_results.json").write_text(json.dumps(out, ensure_ascii=False, indent=1),
                                            encoding="utf-8")
    for name, d in daily_tables.items():
        d.to_csv(base / f"daily_{name}.csv", index=False, encoding="utf-8")
    print("\n[RankIC] mean spearman =", out["rank_ic"]["mean_spearman"],
          " days =", out["rank_ic"]["days"])
    print("[written]", base / "eval_results.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
