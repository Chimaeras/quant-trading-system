"""决定性检验（评估合同 DS-EC-1）：B0 的优势是「动量/量能的代理」还是独立信息？

  T1 全池 Rank IC 只有 ~0.004，而十分位表第 9 档 +0.29%/日、第 0 档 -0.07%/日 —— 说明
     「秩相关」这个指标对本规则几乎没有分辨力，不能只用它判定「无预测力」。
  T2 因此改用**跨越式对比**与**回归分解**：在控制 ret5 / volume_ratio / ma_pos 后，
     rule_score>=35 是否仍有增量？
  T3 时间切分：只用 1-5 月确定规则形态与阈值档位，再在 6-8 月（未见）上验证。
     这是本数据下唯一诚实的样本外检验——2026-09-08 起行情断崖，无法再往后推。
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


def daily_mean(df: pd.DataFrame, mask: pd.Series, col: str = "net_ret") -> pd.Series:
    return df[mask & ~df["unfillable"]].groupby("date")[col].mean()


def paired_report(label: str, a: pd.Series, b: pd.Series) -> dict:
    common = a.index.intersection(b.index)
    d = (a.loc[common] - b.loc[common]).dropna().to_numpy()
    lo_n, hi_n = norm_ci(d)
    lo_b, hi_b = block_bootstrap_ci(d)
    return {"label": label, "days": int(d.size), "mean_diff": float(d.mean()) if d.size else float("nan"),
            "ci95_norm": [lo_n, hi_n], "ci95_block": [lo_b, hi_b],
            "share_positive": float((d > 0).mean()) if d.size else float("nan")}


def main() -> int:
    pool = pd.read_pickle(BASE / "cache_pool.pkl")
    out = {}

    # ---------- T1：全池 IC（含无候选日）vs 十分位对比 ----------
    print("=== T1 为什么 Rank IC 会骗人 ===")
    ics, dec_ninth, dec_zero = [], [], []
    for _d, g in pool.groupby("date"):
        if len(g) < 50:
            continue
        ics.append(float(g["rule_score"].rank().corr(g["net_ret"].rank())))
        q = pd.qcut(g["rule_score"].rank(method="first"), 10, labels=False, duplicates="drop")
        dec_ninth.append(float(g.loc[q == 9, "net_ret"].mean()))
        dec_zero.append(float(g.loc[q == 0, "net_ret"].mean()))
    ics, dec_ninth, dec_zero = map(np.asarray, (ics, dec_ninth, dec_zero))
    spread = dec_ninth - dec_zero
    print(f"  days={ics.size}  mean Spearman IC={ics.mean():+.4f} CI={norm_ci(ics)}")
    print(f"  第9档-第0档 日度价差 mean={spread.mean():+.5f} CI_norm={norm_ci(spread)} "
          f"CI_block={block_bootstrap_ci(spread)} pos_days={(spread > 0).mean():.3f}")
    out["T1_ic_vs_decile"] = {
        "ic_mean": float(ics.mean()), "ic_ci": list(norm_ci(ics)),
        "decile9_minus_decile0_mean": float(spread.mean()),
        "decile9_minus_decile0_ci_norm": list(norm_ci(spread)),
        "decile9_minus_decile0_ci_block": list(block_bootstrap_ci(spread)),
        "share_days_spread_positive": float((spread > 0).mean()),
    }

    # ---------- T2：回归分解（控制 ret5 / volume_ratio / ma_pos）----------
    print("\n=== T2 控制 5 日动量/量比/趋势后，rule_score>=35 是否还有增量 ===")
    df = pool.dropna(subset=["net_ret", "ret5", "volume_ratio", "ma_pos"]).copy()
    dd = pd.get_dummies(df["date"].astype(str), drop_first=True).astype(float)
    y = df["net_ret"].to_numpy(dtype=float)

    def ols(X: np.ndarray, yv: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        beta, *_ = np.linalg.lstsq(X, yv, rcond=None)
        resid = yv - X @ beta
        n, k = X.shape
        if n <= k:
            return beta, np.full(k, np.nan)
        s2 = resid @ resid / (n - k)
        cov = s2 * np.linalg.pinv(X.T @ X)
        return beta, np.sqrt(np.diag(cov))

    def z(s: pd.Series) -> np.ndarray:
        a = s.to_numpy(dtype=float)
        a = np.clip(a, np.nanpercentile(a, 1), np.nanpercentile(a, 99))
        return (a - a.mean()) / (a.std() + 1e-12)

    base_cols = np.column_stack([np.ones(len(df)), dd.to_numpy()])
    feats = {
        "ret5": z(df["ret5"]),
        "volume_ratio": z(df["volume_ratio"]),
        "ma_pos": z(df["ma_pos"]),
    }
    X_ctrl = np.column_stack([base_cols] + list(feats.values()))
    X_full = np.column_stack([X_ctrl, (df["rule_score"] >= 35.0).to_numpy(dtype=float)])

    # 用双残差化避免数百个日期哑变量直接进 OLS 的数值问题
    # 1) 把 y、treatment 分别对 [日期哑变量 + 控制变量] 回归，取残差
    Xr = X_ctrl
    resid_y = y - Xr @ np.linalg.lstsq(Xr, y, rcond=None)[0]
    t = (df["rule_score"] >= 35.0).to_numpy(dtype=float)
    resid_t = t - Xr @ np.linalg.lstsq(Xr, t, rcond=None)[0]
    # 2) 残差对残差回归 → rule_score>=35 的增量系数
    denom = float(resid_t @ resid_t)
    coef = float(resid_t @ resid_y / denom) if denom > 0 else float("nan")
    resid_final = resid_y - coef * resid_t
    n, k = len(df), Xr.shape[1] + 1
    s2 = float(resid_final @ resid_final / max(1, n - k))
    se = float(np.sqrt(s2 / denom)) if denom > 0 else float("nan")
    print(f"  n={n:,}  rule_score>=35 增量系数 = {coef:+.5f}  se={se:.5f}  "
          f"t={coef / se:+.2f}  95%CI=[{coef - 1.96 * se:+.5f},{coef + 1.96 * se:+.5f}]")
    out["T2_regression"] = {"n": int(n), "coef_ge35_after_controls": coef, "se": se,
                            "t": (coef / se if se else None),
                            "ci95": [coef - 1.96 * se, coef + 1.96 * se],
                            "controls": list(feats) + ["date_fixed_effects"]}

    # ---------- T3：时间切分（1-5 月定型 / 6-8 月验证）----------
    print("\n=== T3 时间切分：6-8 月为未见区间 ===")
    split = pd.Timestamp("2026-06-01")
    tr = pool[pool["date"] < split]
    te = pool[pool["date"] >= split]
    print(f"  训练区间 {tr['date'].min().date()}..{tr['date'].max().date()} rows={len(tr):,} days={tr['date'].nunique()}")
    print(f"  验证区间 {te['date'].min().date()}..{te['date'].max().date()} rows={len(te):,} days={te['date'].nunique()}")

    stage = []
    for label, g in (("train_2026_01_05", tr), ("holdout_2026_06_08", te)):
        b0 = daily_mean(g, g["rule_score"] >= 35.0)
        b1 = daily_mean(g, g["rule_score"].notna())
        b2a = g.sort_values(["date", "ret5"], ascending=[True, False]).groupby("date").head(10)
        b2a = b2a[~b2a["unfillable"]].groupby("date")["net_ret"].mean()
        rec = {
            "stage": label, "days_total": int(g["date"].nunique()),
            "b0_days": int(b0.size), "b0_mean": float(b0.mean()) if b0.size else None,
            "b0_ci_block": list(block_bootstrap_ci(b0.to_numpy())) if b0.size else None,
            "b1_mean": float(b1.mean()),
            "b2a_mean": float(b2a.mean()),
            "paired_b0_vs_b1": paired_report(f"{label}:B0-B1", b0, b1),
            "paired_b0_vs_b2a": paired_report(f"{label}:B0-B2a", b0, b2a),
        }
        stage.append(rec)
        print(f"  [{label}] B0 days={rec['b0_days']} mean={rec['b0_mean']} "
              f"CI={rec['b0_ci_block']}")
        print(f"      B1={rec['b1_mean']:+.5f}  B2a={rec['b2a_mean']:+.5f}")
        print(f"      B0-B1 diff={rec['paired_b0_vs_b1']['mean_diff']:+.5f} "
              f"CI={rec['paired_b0_vs_b1']['ci95_block']}")
        print(f"      B0-B2a diff={rec['paired_b0_vs_b2a']['mean_diff']:+.5f} "
              f"CI={rec['paired_b0_vs_b2a']['ci95_block']}")
    out["T3_temporal_split"] = stage

    (BASE / "eval_decisive.json").write_text(json.dumps(out, ensure_ascii=False, indent=1),
                                             encoding="utf-8")
    print("\n[written]", BASE / "eval_decisive.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
