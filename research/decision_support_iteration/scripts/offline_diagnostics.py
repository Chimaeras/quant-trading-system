"""承上启下：解释 B0 的 +1.53%/日 是否可信（评估合同 DS-EC-1 第 2 节）。

必须回答的问题：
  Q1 Rank IC ≈ 0.004 与 B0 尾部 +1.53%/日 是否矛盾？→ 只用 ≥35 候选做同一 IC 检验
  Q2 阈值 35 是否有信息？→ 阈值敏感性（仅作稳健性，不作为样本外证据）
  Q3 收益是否集中在极少数标的/极少数月份？→ 集中度与逐月稳定性
  Q4 去掉最好的 k 个标的后是否还成立？→ 留一法（leave-top-k-out）
"""
from __future__ import annotations

import json
import pathlib
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from offline_metrics import block_bootstrap_ci, norm_ci  # noqa: E402

BASE = pathlib.Path("research/decision_support_iteration")


def main() -> int:
    pool = pd.read_pickle(BASE / "cache_pool.pkl")
    out = {}

    # ---------- Q2 阈值敏感性 ----------
    print("=== Q2 阈值敏感性（净收益为可成交等权日均）===")
    rows = []
    for thr in (25.0, 30.0, 32.0, 35.0, 38.0, 40.0, 42.0, 45.0):
        sub = pool[pool["rule_score"] >= thr]
        trad = sub[~sub["unfillable"]]
        per_day = trad.groupby("date")["net_ret"].mean()
        picks = sub.groupby("date").size()
        rows.append({
            "threshold": thr,
            "days_with_picks": int(picks.size),
            "total_picks": int(len(sub)),
            "avg_picks_per_day": float(picks.mean()) if picks.size else 0.0,
            "mean_net_ret": float(per_day.mean()) if per_day.size else float("nan"),
            "median_net_ret": float(per_day.median()) if per_day.size else float("nan"),
            "win_rate": float((per_day > 0).mean()) if per_day.size else float("nan"),
            "distinct_codes": int(sub["code"].nunique()),
            "ci95_block": list(block_bootstrap_ci(per_day.to_numpy())),
        })
        print(f"  thr>={thr:5.1f} days={rows[-1]['days_with_picks']:>3} picks={rows[-1]['total_picks']:>5} "
              f"avg/day={rows[-1]['avg_picks_per_day']:.2f} mean={rows[-1]['mean_net_ret']:+.5f} "
              f"codes={rows[-1]['distinct_codes']:>4} CI={rows[-1]['ci95_block']}")
    out["threshold_sensitivity"] = rows

    # ---------- Q1 在候选子集内重算排序信息 ----------
    print("\n=== Q1 候选子集内的排序能力（score 是否还能区分高低）===")
    ic_rows = []
    for thr in (30.0, 32.0, 35.0):
        sub = pool[pool["rule_score"] >= thr]
        vals = []
        for _d, g in sub.groupby("date"):
            if len(g) < 8:
                continue
            vals.append(float(g["rule_score"].rank().corr(g["net_ret"].rank())))
        vals = np.array(vals, dtype=float)
        rec = {
            "threshold": thr, "days": int(vals.size),
            "mean_spearman": float(vals.mean()) if vals.size else float("nan"),
            "ci95_norm": list(norm_ci(vals)),
            "share_positive": float((vals > 0).mean()) if vals.size else float("nan"),
        }
        ic_rows.append(rec)
        print(f"  thr>={thr}: days={rec['days']:>3} mean_spearman={rec['mean_spearman']:+.4f} "
              f"CI={rec['ci95_norm']} pos={rec['share_positive']:.3f}")
    out["candidate_subset_ic"] = ic_rows

    # 全池分组（十分位）单调性
    print("\n=== 全池按 rule_score 十分位的次日净收益（单调性检验）===")
    dec = pool.copy()
    dec["decile"] = dec.groupby("date")["rule_score"].transform(
        lambda s: pd.qcut(s.rank(method="first"), 10, labels=False, duplicates="drop"))
    grp = dec.groupby("decile").agg(n=("net_ret", "size"), mean_net=("net_ret", "mean"),
                                    hit=("gross_ret", lambda s: float((s > 0).mean())))
    print(grp.to_string())
    out["decile_table"] = json.loads(grp.reset_index().to_json(orient="records"))

    # ---------- Q3 集中度 ----------
    print("\n=== Q3 集中度 ===")
    sub = pool[pool["rule_score"] >= 35.0]
    per_day_picks = sub.groupby("date").size()
    conc = sub.groupby("code")["net_ret"].agg(["size", "mean"]).sort_values("size", ascending=False)
    print(f"  候选总数={len(sub)}  覆盖信号日={sub['date'].nunique()}  不同标的={sub['code'].nunique()}")
    print(f"  每日候选数：min={per_day_picks.min()} max={per_day_picks.max()} "
          f"mean={per_day_picks.mean():.2f} median={per_day_picks.median()}")
    print(f"  每日候选数分布：\n{per_day_picks.value_counts().sort_index().to_string()}")
    print(f"  出现最多的 10 只标的：\n{conc.head(10).to_string()}")
    out["concentration"] = {
        "total_picks": int(len(sub)), "distinct_codes": int(sub["code"].nunique()),
        "picks_per_day_min": int(per_day_picks.min()), "picks_per_day_max": int(per_day_picks.max()),
        "picks_per_day_mean": float(per_day_picks.mean()),
        "top10_codes_share": float(conc["size"].head(10).sum() / len(sub)),
        "top_codes": {str(k): int(v) for k, v in conc["size"].head(10).items()},
    }

    # ---------- Q4 去掉贡献最大的标的 ----------
    print("\n=== Q4 留一法：剔除贡献最大的 k 只标的后 B0 是否仍成立 ===")
    daily_all = sub[~sub["unfillable"]].groupby("date")["net_ret"].mean()
    base_mean = float(daily_all.mean())
    contrib = (sub[~sub["unfillable"]].groupby("code")["net_ret"]
               .agg(["size", "mean"])
               .assign(total=lambda d: d["size"] * d["mean"])
               .sort_values("total", ascending=False))
    loo_rows = []
    for k in (1, 3, 5, 10, 20):
        drop_codes = set(contrib.index[:k])
        kept = sub[~sub["unfillable"] & ~sub["code"].isin(drop_codes)]
        d = kept.groupby("date")["net_ret"].mean()
        lo, hi = block_bootstrap_ci(d.to_numpy())
        loo_rows.append({"k_dropped": k, "days": int(d.size),
                         "mean_net_ret": float(d.mean()),
                         "ci95_block": [lo, hi],
                         "still_positive_ci": bool(lo > 0)})
    print(f"  基准 mean={base_mean:+.5f}")
    for r in loo_rows:
        print(f"  剔除 top-{r['k_dropped']:>2} 标的：days={r['days']:>3} mean={r['mean_net_ret']:+.5f} "
              f"CI={r['ci95_block']} CI>0={r['still_positive_ci']}")
    out["leave_top_k_out"] = loo_rows

    # ---------- 逐月 ----------
    print("\n=== 逐月稳定性（B0 vs B1）===")
    sub2 = pool.copy()
    sub2["is_b0"] = sub2["rule_score"] >= 35.0
    months = []
    for m, g in sub2.groupby(sub2["date"].dt.to_period("M")):
        b0 = g[g["is_b0"] & ~g["unfillable"]].groupby("date")["net_ret"].mean()
        b1 = g[~g["unfillable"]].groupby("date")["net_ret"].mean()
        months.append({
            "month": str(m), "b0_days": int(b0.size), "b0_mean": float(b0.mean()) if b0.size else None,
            "b1_mean": float(b1.mean()), "diff": float(b0.mean() - b1.mean()) if b0.size else None,
        })
    for r in months:
        print(f"  {r['month']}  B0_days={r['b0_days']:>3} B0={r['b0_mean']}  B1={r['b1_mean']:+.5f}  diff={r['diff']}")
    out["monthly"] = months

    (BASE / "eval_diagnostics.json").write_text(json.dumps(out, ensure_ascii=False, indent=1),
                                                encoding="utf-8")
    print("\n[written]", BASE / "eval_diagnostics.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
