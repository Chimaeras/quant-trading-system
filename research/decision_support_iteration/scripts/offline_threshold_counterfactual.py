"""反事实对照：旧生产阈值 40.0（`t1_rule_v1`）vs 新阈值 35.0（`t1_rule_v2`）。

**为什么这是本轮最重要的缺口**：R2 只检验了 `scoreT1>=35`，
而生产快照 `t1_candidate` 实际使用的是 `t1_rule_v1` / 40.0。
没有同口径对照，就**无法回答「34098346 是否改善了准确性」**（D-T1-01 的遗留未验证项）。

对照必须是**公平**的：同池、同成交口径、同费用、同日度配对。
除阈值外还并列报告 TopN 的影响（阈值降低会带来更多候选，若不做 TopN 截断，
两者差异会混入"持仓数不同"这一无关变量）。
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
OLD_THRESHOLD = 40.0   # t1_rule_v1（生产快照实际使用的阈值）
NEW_THRESHOLD = 35.0   # t1_rule_v2


def variant_daily(pool: pd.DataFrame, thr: float, topn: int | None) -> pd.DataFrame:
    sub = pool[pool["rule_score"] >= thr].copy()
    if topn is not None:
        sub = sub.sort_values(["date", "rule_score"], ascending=[True, False])
        sub = sub.groupby("date", sort=True).head(topn)
    elig = pool[pool["date"].isin(sub["date"].unique())]
    per_day_pool = elig.groupby("date").size().rename("n_pool")
    g = sub.groupby("date")
    out = pd.DataFrame({
        "n_picks": g.size(),
        "n_unfillable": g["unfillable"].sum(),
        "hit_rate": g["gross_ret"].apply(lambda s: float((s > 0).mean())),
    })
    trad = sub[~sub["unfillable"]]
    out["net_ret"] = trad.groupby("date")["net_ret"].mean()
    return out.join(per_day_pool).reset_index()


def paired(a: pd.DataFrame, b: pd.DataFrame, label: str) -> dict:
    da = a.set_index("date")["net_ret"]
    db = b.set_index("date")["net_ret"]
    common = da.index.intersection(db.index)
    d = (da.loc[common] - db.loc[common]).dropna().to_numpy()
    lo_n, hi_n = norm_ci(d)
    lo_b, hi_b = block_bootstrap_ci(d)
    return {"label": label, "paired_days": int(d.size),
            "mean_diff": float(d.mean()) if d.size else float("nan"),
            "ci95_norm": [lo_n, hi_n], "ci95_block": [lo_b, hi_b],
            "share_days_new_wins": float((d > 0).mean()) if d.size else float("nan")}


def main() -> int:
    pool = pd.read_pickle(BASE / "cache_pool.pkl")
    out = {"old_threshold": OLD_THRESHOLD, "new_threshold": NEW_THRESHOLD}

    print("=== 反事实：旧 40.0 vs 新 35.0（同池/同成交/同费用）===")
    grid = []
    for topn in (10, 20, 50):
        a_new = variant_daily(pool, NEW_THRESHOLD, topn)
        a_old = variant_daily(pool, OLD_THRESHOLD, topn)
        r_new = {**{"threshold": NEW_THRESHOLD, "topn": topn},
                 "days_with_picks": int(a_new["n_picks"].gt(0).sum()),
                 "total_picks": int(a_new["n_picks"].sum()),
                 "mean_net_ret": float(a_new["net_ret"].mean()),
                 "ci95_block": list(block_bootstrap_ci(a_new["net_ret"].to_numpy())),
                 "hit_rate": float(a_new["hit_rate"].mean()),
                 "unfillable_rate": float(a_new["n_unfillable"].sum() / max(1, a_new["n_picks"].sum())),
                 "coverage_picks_over_pool": float(a_new["n_picks"].sum() / max(1, a_new["n_pool"].sum()))}
        r_old = {**{"threshold": OLD_THRESHOLD, "topn": topn},
                 "days_with_picks": int(a_old["n_picks"].gt(0).sum()),
                 "total_picks": int(a_old["n_picks"].sum()),
                 "mean_net_ret": float(a_old["net_ret"].mean()),
                 "ci95_block": list(block_bootstrap_ci(a_old["net_ret"].to_numpy())),
                 "hit_rate": float(a_old["hit_rate"].mean()),
                 "unfillable_rate": float(a_old["n_unfillable"].sum() / max(1, a_old["n_picks"].sum())),
                 "coverage_picks_over_pool": float(a_old["n_picks"].sum() / max(1, a_old["n_pool"].sum()))}
        cmp = paired(a_new, a_old, f"thr35-thr40@top{topn}")
        grid.append({"topn": topn, "new": r_new, "old": r_old, "paired_new_minus_old": cmp})
        print(f"\n  --- TopN={topn} ---")
        print(f"    新(35): 有候选日={r_new['days_with_picks']:>3}/155  选数={r_new['total_picks']:>5}  "
              f"日均={r_new['mean_net_ret']:+.5f}  CI={r_new['ci95_block']}")
        print(f"    旧(40): 有候选日={r_old['days_with_picks']:>3}/155  选数={r_old['total_picks']:>5}  "
              f"日均={r_old['mean_net_ret']:+.5f}  CI={r_old['ci95_block']}")
        print(f"    配对差(新-旧)={cmp['mean_diff']:+.5f}  CI_norm={cmp['ci95_norm']}  "
              f"CI_block={cmp['ci95_block']}  新胜率={cmp['share_days_new_wins']:.3f}")

    out["grid"] = grid

    # ---- 未见区间单独看 ----
    print("\n=== 未见区间（2026-06-01..2026-08-31）上单独对照 ===")
    te = pool[pool["date"] >= pd.Timestamp("2026-06-01")]
    hold = []
    for topn in (10, 20):
        a_new = variant_daily(te, NEW_THRESHOLD, topn)
        a_old = variant_daily(te, OLD_THRESHOLD, topn)
        cmp = paired(a_new, a_old, f"holdout thr35-thr40@top{topn}")
        hold.append({
            "topn": topn,
            "new_days": int(a_new["n_picks"].gt(0).sum()), "new_picks": int(a_new["n_picks"].sum()),
            "new_mean": float(a_new["net_ret"].mean()),
            "new_ci": list(block_bootstrap_ci(a_new["net_ret"].to_numpy())),
            "old_days": int(a_old["n_picks"].gt(0).sum()), "old_picks": int(a_old["n_picks"].sum()),
            "old_mean": float(a_old["net_ret"].mean()),
            "old_ci": list(block_bootstrap_ci(a_old["net_ret"].to_numpy())),
            "paired": cmp,
        })
        print(f"  TopN={topn}: 新(35) days={hold[-1]['new_days']} picks={hold[-1]['new_picks']} "
              f"mean={hold[-1]['new_mean']:+.5f} | 旧(40) days={hold[-1]['old_days']} "
              f"picks={hold[-1]['old_picks']} mean={hold[-1]['old_mean']:+.5f}")
        print(f"       配对差={cmp['mean_diff']:+.5f} CI_block={cmp['ci95_block']} "
              f"新胜率={cmp['share_days_new_wins']:.3f}")
    out["holdout"] = hold

    (BASE / "eval_threshold_counterfactual.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print("\n[written]", BASE / "eval_threshold_counterfactual.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
