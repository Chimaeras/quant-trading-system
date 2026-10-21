"""补全两项检验：

  C1 修正 T1 的 NaN：按 rank 分档时并列值会使某档为空，需剔除非有限值后再统计。
     同时用「并列稳健」的方式再看一次尾部价差，避免用 IC 单指标下结论。
  C2 阈值迁移（关键样本外问题）：6-8 月为未见区间，在其上考察
     「阈值从 30 调到 45，B0 与 B2a 的表现如何变化」。
     若 B0 的优势只在某个特定阈值上出现 → 阈值是拟合噪声；
     若在阈值区间内单调/稳定 → 阈值承载真实信息。
     注意：这一步在未见区间上做**读数**，不是调参；任何后续"选出来的最优阈值"
     都不得回填为生产参数，除非在更晚的未读区间复现。
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
HOLDOUT_FROM = pd.Timestamp("2026-06-01")


def daily(g: pd.DataFrame, mask: pd.Series) -> pd.Series:
    return g[mask & ~g["unfillable"]].groupby("date")["net_ret"].mean()


def main() -> int:
    pool = pd.read_pickle(BASE / "cache_pool.pkl")
    out = {}

    # ---------- C1 修正尾部价差（剔除非有限 IC）----------
    print("=== C1 全池尾部价差（并列稳健）===")
    rows, ics = [], []
    for _d, g in pool.groupby("date"):
        if len(g) < 100:
            continue
        q = pd.qcut(g["rule_score"].rank(method="first"), 10, labels=False, duplicates="drop")
        hi = g.loc[q == q.max(), "net_ret"]
        lo = g.loc[q == q.min(), "net_ret"]
        if len(hi) < 5 or len(lo) < 5:
            continue
        rows.append({"date": _d, "hi": float(hi.mean()), "lo": float(lo.mean()),
                     "n_hi": int(len(hi)), "n_lo": int(len(lo))})
        ic = g["rule_score"].rank().corr(g["net_ret"].rank())
        if np.isfinite(ic):
            ics.append(float(ic))
    t = pd.DataFrame(rows)
    spread = (t["hi"] - t["lo"]).to_numpy(dtype=float)
    spread = spread[np.isfinite(spread)]
    print(f"  days={len(t)}  top-decile={t['hi'].mean():+.5f}  bottom-decile={t['lo'].mean():+.5f}")
    print(f"  价差 mean={spread.mean():+.5f}  CI_norm={norm_ci(spread)}  "
          f"CI_block={block_bootstrap_ci(spread)}  pos_days={(spread > 0).mean():.3f}")
    print(f"  有限 Spearman IC：days={len(ics)} mean={np.mean(ics):+.4f} CI={norm_ci(np.array(ics))}")
    out["C1_tail_spread"] = {
        "days": int(len(t)),
        "top_decile_mean": float(t["hi"].mean()), "bottom_decile_mean": float(t["lo"].mean()),
        "spread_mean": float(spread.mean()), "spread_ci_norm": list(norm_ci(spread)),
        "spread_ci_block": list(block_bootstrap_ci(spread)),
        "share_days_positive": float((spread > 0).mean()),
        "finite_ic_days": int(len(ics)), "finite_ic_mean": float(np.mean(ics)),
        "finite_ic_ci": list(norm_ci(np.asarray(ics))),
        "note": "并列值使部分交易日的高/低档为空，已逐日剔除后统计；IC 单指标对本规则无分辨力",
    }

    # ---------- C2 阈值迁移（在未见区间上读数）----------
    print("\n=== C2 未见区间（2026-06-01..2026-08-31）上的阈值读数 ===")
    te = pool[pool["date"] >= HOLDOUT_FROM]
    print(f"  区间 rows={len(te):,} days={te['date'].nunique()}")
    b2a = te.sort_values(["date", "ret5"], ascending=[True, False]).groupby("date").head(10)
    b2a_daily = daily(b2a, b2a["ret5"].notna())
    b1_daily = daily(te, te["rule_score"].notna())

    table = []
    prev_mean = None
    for thr in (25.0, 30.0, 32.0, 35.0, 38.0, 40.0, 42.0, 45.0):
        d = daily(te, te["rule_score"] >= thr)
        rec = {
            "threshold": thr, "days_with_picks": int(d.size),
            "picks": int((te["rule_score"] >= thr).sum()),
            "avg_picks_per_day": round(float((te["rule_score"] >= thr).sum() / max(1, te["date"].nunique())), 2),
            "mean_net_ret": float(d.mean()) if d.size else None,
            "ci95_block": list(block_bootstrap_ci(d.to_numpy())) if d.size else None,
            "win_rate": float((d > 0).mean()) if d.size else None,
        }
        table.append(rec)
        print(f"  thr>={thr:5.1f} days={rec['days_with_picks']:>3} picks={rec['picks']:>5} "
              f"mean={rec['mean_net_ret']} CI={rec['ci95_block']}")
    print(f"\n  参照：B1 同池等权={b1_daily.mean():+.5f}   B2a ret5 Top10={b2a_daily.mean():+.5f}")

    # 稳定性：相邻阈值之间是否单调
    vals = [r["mean_net_ret"] for r in table if r["mean_net_ret"] is not None]
    monotone_frac = float(np.mean([vals[i + 1] >= vals[i] for i in range(len(vals) - 1)]))
    print(f"  相邻阈值单调上升比例 = {monotone_frac:.2f}（越接近 1 越说明阈值承载真实信息，"
          f"越接近 0.5 越像噪声）")
    out["C2_threshold_transfer_holdout"] = {
        "window": [str(te["date"].min().date()), str(te["date"].max().date())],
        "table": table, "b1_mean": float(b1_daily.mean()), "b2a_mean": float(b2a_daily.mean()),
        "monotone_ascending_fraction": monotone_frac,
    }

    # ---------- C3 B0 vs B2a 在未见区间的分布 ----------
    print("\n=== C3 未见区间 B0 与 B2a 的日度差分布 ===")
    b0_daily = daily(te, te["rule_score"] >= 35.0)
    common = b0_daily.index.intersection(b2a_daily.index)
    diff = (b0_daily.loc[common] - b2a_daily.loc[common]).dropna()
    print(f"  配对天数={diff.size}  mean_diff={diff.mean():+.5f}  "
          f"CI_norm={norm_ci(diff.to_numpy())}  CI_block={block_bootstrap_ci(diff.to_numpy())}")
    print(f"  B0 胜率={float((diff > 0).mean()):.3f}  B0 日数={b0_daily.size}  B2a 日数={b2a_daily.size}")
    out["C3_B0_vs_B2a_holdout"] = {
        "paired_days": int(diff.size), "mean_diff": float(diff.mean()),
        "ci95_norm": list(norm_ci(diff.to_numpy())),
        "ci95_block": list(block_bootstrap_ci(diff.to_numpy())),
        "b0_win_share": float((diff > 0).mean()),
        "b0_days": int(b0_daily.size), "b2a_days": int(b2a_daily.size),
    }

    (BASE / "eval_threshold_transfer.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print("\n[written]", BASE / "eval_threshold_transfer.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
