#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R002 实验：R001 的 IS→OOS 衰减是不是「数据构造产物」？

对应论文：LR2018《The History of the Cross-Section of Stock Returns》(RFS 31(7), NBER WP 22894)
立项预注册：research/paper_loop/rounds/R002_prereg.md（**本脚本在其之后落盘**）
实验编号：`R002_universe`

假设（H1，默认立场 = 尝试杀死它）：
  R001 的「IS 强 / OOS 衰减」主要来自本地面板的**数据构造**，而非信号本身失效。
  H1a 构成通道：2026-02 批量补录的 1,888 只代码改变了 OOS 截面构成（占 OOS 截面 29.9%、IS 0%）。
  H1b 退市收益通道：R001 丢弃「退出价缺失」信号 ≈ 对业绩型退市赋 0 收益；
                    按 L&R/Shumway 约定补 −30% 后反转收益应显著下降。

机制一致性：信号 / 入场 / 退出 / 涨跌停 / 分位 / HAC 全部按 R001 的定义在同一面板上重算，
并以 **parity 自检**要求本脚本复现 `R001_reversal/result.json` 的 OOS 主配置 IC（|diff| < 1e-9）。
R001 的实验产物只读、不修改。

只读：本脚本只执行 SELECT，不建表、不写库、不改任何生产文件。
"""

from __future__ import annotations

import importlib.util
import json
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
R001_DIR = HERE.parent / "R001_reversal"
_spec = importlib.util.spec_from_file_location("r001", R001_DIR / "run_reversal_ic.py")
r001 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(r001)

# --------------------------------------------------------------------------
# 冻结常量（与 R002_prereg.md 一一对应；不得事后改动）
# --------------------------------------------------------------------------
N, H = 20, 3
IS = ("2024-01-02", "2025-12-31")
OOS = ("2026-01-01", "2026-09-07")
IS_A = ("2024-01-02", "2024-12-31")
IS_B = ("2025-01-01", "2025-12-31")
BATCH_MONTH = "2026-02"
MIN_HIST_IS_DAYS = 120
SENS_MIN_HIST = (96, 144)
DELTA_PRIMARY = -0.30
SENS_DELTA = (-0.24, -0.36)
PLACEBO_DRAWS = 200
RANDOM_SEED = 20260914
MAX_VARIANTS = 12
MIN_NAMES = r001.MIN_NAMES_PER_DATE
LIMIT_EPS = r001.LIMIT_EPS
K4_MIN_DISCRIMINATION = 0.002
K3_MIN_DROP_BP = 5.0
H1B_ACCEPT_DROP_BP = 26.9
K2_MIN_SHARE = 0.50
K5_MIN_FORESIGHT_IC = 0.99
LAG = N + H + 2

LOG: list[str] = []


def log(msg: str) -> None:
    print(msg, flush=True)
    LOG.append(msg)


def date_seg(dates: pd.DatetimeIndex, seg) -> np.ndarray:
    lo, hi = seg
    d = np.array([str(x)[:10] for x in dates])
    return (d >= lo) & (d <= hi)


def main() -> int:  # noqa: C901
    panel, meta = r001.load_panel()
    close, openp, pre, vol = panel["close"], panel["open"], panel["pre"], panel["vol"]
    dates, codes = close.index, list(close.columns)
    n_d = len(dates)
    log(f"[R002] panel {n_d} dates x {len(codes)} codes  {str(dates[0])[:10]} -> {str(dates[-1])[:10]}")

    # ---------- R001 完全一致的机制（逐行对应 run_reversal_ic.evaluate）----------
    sig = close / close.shift(N) - 1.0
    entry = openp.shift(-1)
    exit_ = close.shift(-(1 + H))
    pre_entry = pre.shift(-1)
    pre_exit = pre.shift(-(1 + H))
    code_pct = pd.Series({c: r001.limit_pct(c) for c in codes}, dtype="float64")
    lu_entry = pre_entry.mul(code_pct + 1.0, axis=1)
    ld_exit = pre_exit.mul(1.0 - code_pct, axis=1)
    suspended = (vol.shift(-1) <= 0) | (vol.shift(-(1 + H)) <= 0) | (vol <= 0)
    not_buyable = entry >= lu_entry * (1.0 - LIMIT_EPS)
    not_sellable = exit_ <= ld_exit * (1.0 + LIMIT_EPS)
    valid_base = sig.notna() & entry.notna() & exit_.notna() & entry.gt(0) & exit_.gt(0)
    filled = (valid_base & ~suspended & ~not_buyable & ~not_sellable).to_numpy()
    gross_np = (exit_ / entry - 1.0).to_numpy()
    net_np = (exit_ * (1.0 - r001.SELL_TAX - r001.SLIPPAGE)
              / (entry * (1.0 + r001.COMMISSION + r001.SLIPPAGE)) - 1.0).to_numpy()
    sig_np = sig.to_numpy()
    entry_np = entry.to_numpy()
    vol_np = vol.to_numpy()
    close_np = close.to_numpy()
    exit_np = exit_.to_numpy()

    is_m, oos_m = date_seg(dates, IS), date_seg(dates, OOS)
    isA_m, isB_m = date_seg(dates, IS_A), date_seg(dates, IS_B)

    # ---------- 池定义（prereg §5）----------
    okp = np.isfinite(close_np) & (close_np > 0)
    is_days = okp[is_m].sum(axis=0)
    oos_days = okp[oos_m].sum(axis=0)
    first_ok = np.array([str(dates[np.argmax(okp[:, j])])[:10] if okp[:, j].any() else ""
                         for j in range(len(codes))])
    last_ok = np.array([str(dates[n_d - 1 - np.argmax(okp[::-1, j])])[:10] if okp[:, j].any()
                        else "" for j in range(len(codes))])
    batch = np.array([f[:7] == BATCH_MONTH for f in first_ok])
    consistent = (is_days >= MIN_HIST_IS_DAYS) & (oos_days >= 1)
    attrited = np.array([(l != "") and (l < str(dates[-1])[:10]) for l in last_ok])
    log(f"[R002] batch={int(batch.sum())} consistent={int(consistent.sum())} "
        f"attrited={int(attrited.sum())}")

    # ---------- 与 R001 同构的评估函数 ----------
    def compute(seg_m, keep, net=None, fill=None, want_deciles=True):
        net_a = net_np if net is None else net
        fill_a = filled if fill is None else fill
        keep_row = np.broadcast_to(keep[None, :], (n_d, len(codes)))
        use = fill_a & keep_row & seg_m[:, None]
        ics, dec_sum, dec_n = [], np.zeros(10), np.zeros(10)
        univ_sum = univ_cnt = 0.0
        for i in np.nonzero(seg_m)[0]:
            f = use[i]
            nf = int(f.sum())
            if nf < MIN_NAMES:
                continue
            s, r = sig_np[i][f], net_a[i][f]
            m = np.isfinite(s) & np.isfinite(r)
            if not m.all():
                s, r = s[m], r[m]
                if len(s) < MIN_NAMES:
                    continue
            ic = r001.spearman_ic(s, r)
            if np.isfinite(ic):
                ics.append(ic)
            univ_sum += float(r.mean()); univ_cnt += 1
            if want_deciles:
                order = pd.Series(s).rank(method="first").to_numpy()
                grp = np.minimum((order - 1) * 10 // len(s), 9).astype(int)
                for k in range(10):
                    mk = grp == k
                    if mk.any():
                        dec_sum[k] += float(r[mk].mean()); dec_n[k] += 1
        a = np.array(ics, dtype=float)
        mu, t_nw, _, se = r001.newey_west_t(a, LAG)
        dec = [(dec_sum[k] / dec_n[k]) if dec_n[k] else math.nan for k in range(10)]
        return {"ic_mean": float(mu), "ic_t_nw": float(t_nw), "ic_days": int(len(a)),
                "decile_net": [float(x) for x in dec],
                "d1_net": float(dec[0]), "d10_net": float(dec[9]),
                "spread_net_d1_d10": float(dec[0] - dec[9]),
                "universe_net_mean": (univ_sum / univ_cnt) if univ_cnt else math.nan}

    all_keep = np.ones(len(codes), dtype=bool)

    # ---------- parity 自检：必须复现 R001 冻结产物 ----------
    r001_res = json.loads((R001_DIR / "result.json").read_text("utf-8"))
    r001_oos = next(x for x in r001_res["segments"]["OOS"] if x["N"] == N and x["h"] == H)
    r001_is = next(x for x in r001_res["segments"]["IS"] if x["N"] == N and x["h"] == H)
    oos_all = compute(oos_m, all_keep)
    is_all = compute(is_m, all_keep)
    d_oos = abs(oos_all["ic_mean"] - r001_oos["ic_mean"])
    d_is = abs(is_all["ic_mean"] - r001_is["ic_mean"])
    log(f"[parity] OOS IC mine={oos_all['ic_mean']:.10f} R001={r001_oos['ic_mean']:.10f} |diff|={d_oos:.3e}")
    log(f"[parity] IS  IC mine={is_all['ic_mean']:.10f} R001={r001_is['ic_mean']:.10f} |diff|={d_is:.3e}")
    parity = {"oos_ic_mine": oos_all["ic_mean"], "oos_ic_r001": r001_oos["ic_mean"],
              "oos_abs_diff": d_oos, "is_ic_mine": is_all["ic_mean"],
              "is_ic_r001": r001_is["ic_mean"], "is_abs_diff": d_is,
              "pass": bool(d_oos < 1e-9 and d_is < 1e-9)}

    # ---------- V1/V2：全池 vs 构成一致池（OOS）----------
    cons_oos = compute(oos_m, consistent)
    d_ic_real = oos_all["ic_mean"] - cons_oos["ic_mean"]
    batch_share = ((cons_oos["ic_mean"] - oos_all["ic_mean"])
                   / (is_all["ic_mean"] - oos_all["ic_mean"]))
    log(f"[R002] OOS IC 全池={oos_all['ic_mean']:+.4f} t={oos_all['ic_t_nw']:+.2f} | "
        f"构成一致池={cons_oos['ic_mean']:+.4f} t={cons_oos['ic_t_nw']:+.2f}")
    log(f"[R002] dIC_real={d_ic_real:+.4f}  batch 解释的衰减份额={batch_share:+.3f}")

    # ---------- V3：安慰剂（同规模随机剔除，200 次）----------
    rng = np.random.default_rng(RANDOM_SEED)
    pool = np.nonzero(oos_days >= 1)[0]
    nbatch_in_pool = int(batch[pool].sum())
    placebo = []
    for _ in range(PLACEBO_DRAWS):
        drop = rng.choice(pool, size=nbatch_in_pool, replace=False)
        k = all_keep.copy(); k[drop] = False
        placebo.append(oos_all["ic_mean"] - compute(oos_m, k, want_deciles=False)["ic_mean"])
    placebo = np.array([x for x in placebo if np.isfinite(x)])
    p50 = float(np.percentile(np.abs(placebo), 50))
    p95 = float(np.percentile(np.abs(placebo), 95))
    pmax = float(np.abs(placebo).max())
    log(f"[R002] placebo n={len(placebo)} |dIC| p50={p50:.4f} p95={p95:.4f} max={pmax:.4f} "
        f"| |dIC_real|={abs(d_ic_real):.4f}")

    # ---------- V4/V5：阳性对照（K4）----------
    bo = compute(oos_m, batch)
    co = compute(oos_m, consistent)
    discrimination = abs(bo["ic_mean"] - co["ic_mean"])
    log(f"[R002] 阳性对照 IC(batch-only)={bo['ic_mean']:+.4f} IC(consistent-only)={co['ic_mean']:+.4f} "
        f"|diff|={discrimination:.4f} (K4 阈值 {K4_MIN_DISCRIMINATION})")

    # ---------- V6/V7：IS 内部构成对照 ----------
    ia, ib = compute(isA_m, all_keep), compute(isB_m, all_keep)
    setA = set(np.nonzero(okp[isA_m].sum(axis=0) >= 1)[0].tolist())
    setB = set(np.nonzero(okp[isB_m].sum(axis=0) >= 1)[0].tolist())
    onlyA, onlyB = len(setA - setB), len(setB - setA)
    log(f"[R002] IS 内部构成变化：仅 IS-A 有 {onlyA} 只 / 仅 IS-B 有 {onlyB} 只；"
        f"IC IS-A={ia['ic_mean']:+.4f} IS-B={ib['ic_mean']:+.4f}")

    # ---------- V8-V10：H1b 退市收益通道 ----------
    #   可补 δ 的集合：entry/sig/close 有效、未涨停、退出价缺失，且**非机械性**
    #   （机械性 = 退出所在的日历索引越过面板最后一日，所有名字同等受影响）
    exit_idx = np.arange(n_d) + 1 + H
    mechanical = (exit_idx >= n_d)[:, None]
    st_ok = (np.isfinite(sig_np) & np.isfinite(entry_np) & (entry_np > 0)
             & (close_np > 0) & (vol_np > 0) & ~(not_buyable.to_numpy()))
    miss_exit = st_ok & ~np.isfinite(exit_np) & ~mechanical
    imp_all = miss_exit
    imp_attr = miss_exit & attrited[None, :]
    log(f"[R002] H1b: 可补 δ 的缺失退出信号={int(imp_all.sum())} "
        f"(attrited 名字内 {int(imp_attr.sum())}；机械性越界 {int((st_ok & ~np.isfinite(exit_np) & mechanical).sum())})")

    def h1b(delta: float, restrict=False):
        imp = imp_attr if restrict else imp_all
        net2 = net_np.copy()
        fill2 = filled.copy()
        # exit_eff = entry × (1+δ)  ⇒  net = (1+δ)(1−税−滑点)/(1+佣金+滑点) − 1
        net2[imp] = (1.0 + delta) * (1.0 - r001.SELL_TAX - r001.SLIPPAGE) / (
            1.0 + r001.COMMISSION + r001.SLIPPAGE) - 1.0
        fill2[imp] = True
        r = compute(is_m, all_keep, net=net2, fill=fill2)
        r.update({"delta": delta, "restrict_attrited": restrict,
                  "imputed_signals": int(imp.sum())})
        return r

    h_prim = h1b(DELTA_PRIMARY)
    h_sens = [h1b(d) for d in SENS_DELTA]
    h_rest = h1b(DELTA_PRIMARY, restrict=True)
    drop_bp = (r001_is["d1_net_mean"] - h_prim["d1_net"]) * 100.0
    drop_bp_rest = (r001_is["d1_net_mean"] - h_rest["d1_net"]) * 100.0
    log(f"[R002] H1b δ={DELTA_PRIMARY}: D1={h_prim['d1_net']*100:+.4f}% "
        f"(R001 {r001_is['d1_net_mean']*100:+.4f}%) → 下降 {drop_bp:+.2f}bp "
        f"(K3 阈值 {K3_MIN_DROP_BP}bp；H1b 成立需 ≥{H1B_ACCEPT_DROP_BP}bp)")
    log(f"[R002] H1b 仅 attrited 名字补 δ：下降 {drop_bp_rest:+.2f}bp；"
        f"±20% δ 敏感性 drops={[round((r001_is['d1_net_mean']-x['d1_net'])*100, 2) for x in h_sens]}bp")

    # ---------- V11/V12：MIN_HIST ±20% ----------
    sens_hist = []
    for mh in SENS_MIN_HIST:
        cc = (is_days >= mh) & (oos_days >= 1)
        r = compute(oos_m, cc)
        sens_hist.append({"MIN_HIST_IS_DAYS": mh, "codes": int(cc.sum()),
                          "ic_oos": r["ic_mean"], "ic_oos_t": r["ic_t_nw"]})
        log(f"[R002] MIN_HIST={mh}: codes={int(cc.sum())} IC_OOS={r['ic_mean']:+.4f} t={r['ic_t_nw']:+.2f}")

    # ---------- P5 描述性：attrited 名字在 D1/D10 中的占比 ----------
    d1a = d1n = d10a = d10n = 0
    use = filled & is_m[:, None]
    for i in np.nonzero(is_m)[0]:
        f = use[i]
        if int(f.sum()) < MIN_NAMES:
            continue
        s = sig_np[i][f]
        order = pd.Series(s).rank(method="first").to_numpy()
        grp = np.minimum((order - 1) * 10 // len(s), 9).astype(int)
        pos = np.nonzero(f)[0]
        i1, i10 = pos[grp == 0], pos[grp == 9]
        d1a += int(attrited[i1].sum()); d1n += len(i1)
        d10a += int(attrited[i10].sum()); d10n += len(i10)
    p5 = {"attr_share_in_D1_IS": d1a / d1n if d1n else math.nan,
          "attr_share_in_D10_IS": d10a / d10n if d10n else math.nan,
          "attr_share_in_universe": float(attrited.mean()),
          "D1_cells": d1n, "D10_cells": d10n}
    log(f"[R002] P5: attrited 占比 D1={p5['attr_share_in_D1_IS']:.4f} "
        f"D10={p5['attr_share_in_D10_IS']:.4f} 全池={p5['attr_share_in_universe']:.4f}")

    # ---------- K5 管线校验（用 R001 自己的 evaluate，canary 单独计）----------
    fore = r001.evaluate(panel, meta, N, H, "canary_foresight", extra_leak="foresight")
    fore_ic = fore["ic_mean"]
    log(f"[R002] foresight canary IC={fore_ic:.6f} (K5 阈值 {K5_MIN_FORESIGHT_IC})")

    variants = ["v_ic_oos_Uall", "v_ic_oos_Uconsistent", f"v_placebo_x{PLACEBO_DRAWS}",
                "v_ic_oos_Ubatchonly", "v_ic_oos_Uconsistentonly", "v_ic_isA", "v_ic_isB",
                "v_h1b_d24", "v_h1b_d30", "v_h1b_d36", "v_minhist_96", "v_minhist_144"]
    results = {
        "meta": {"panel_dates": n_d, "panel_codes": len(codes), "N": N, "H": H,
                 "IS": list(IS), "OOS": list(OOS), "batch": int(batch.sum()),
                 "consistent": int(consistent.sum()), "attrited": int(attrited.sum()),
                 "MIN_HIST_IS_DAYS": MIN_HIST_IS_DAYS, "DELTA_PRIMARY": DELTA_PRIMARY,
                 "PLACEBO_DRAWS": PLACEBO_DRAWS, "RANDOM_SEED": RANDOM_SEED},
        "parity_with_R001": parity,
        "T1_composition": {
            "ic_oos_U_all": oos_all["ic_mean"], "ic_oos_U_all_t": oos_all["ic_t_nw"],
            "ic_oos_U_consistent": cons_oos["ic_mean"], "ic_oos_U_consistent_t": cons_oos["ic_t_nw"],
            "ic_is_U_all": is_all["ic_mean"], "ic_is_U_all_t": is_all["ic_t_nw"],
            "d_ic_real": d_ic_real, "batch_explained_share": batch_share,
            "placebo_abs_p50": p50, "placebo_abs_p95": p95, "placebo_abs_max": pmax,
            "placebo_draws": int(len(placebo)),
            "ic_oos_U_all_deciles": oos_all["decile_net"],
            "ic_oos_U_consistent_deciles": cons_oos["decile_net"],
        },
        "T2_positive_control": {"ic_U_batch_only": bo["ic_mean"], "ic_U_consistent_only": co["ic_mean"],
                                "discrimination": discrimination, "k4_threshold": K4_MIN_DISCRIMINATION},
        "T3_is_internal_composition": {"only_in_IS_A": onlyA, "only_in_IS_B": onlyB,
                                       "ic_isA": ia["ic_mean"], "ic_isB": ib["ic_mean"]},
        "T4_delisting_channel": {"r001_is_d1_net": r001_is["d1_net_mean"],
                                 "r001_is_spread_bp": r001_is["spread_net_d1_d10"] * 100.0,
                                 "primary": h_prim, "sensitivity": h_sens,
                                 "attrited_only": h_rest,
                                 "d1_drop_bp": drop_bp, "d1_drop_bp_attrited_only": drop_bp_rest,
                                 "k3_threshold_bp": K3_MIN_DROP_BP, "h1b_accept_bp": H1B_ACCEPT_DROP_BP,
                                 "imputable_signals": int(imp_all.sum()),
                                 "imputable_signals_attrited": int(imp_attr.sum())},
        "T5_attrited_membership": p5,
        "T6_pipeline_canary": {"foresight_ic": fore_ic, "k5_threshold": K5_MIN_FORESIGHT_IC},
        "T7_min_hist_sensitivity": sens_hist,
        "variant_disclosure": {"variants_tried": len(variants), "max_variants": MAX_VARIANTS,
                               "list": variants, "controls_separate": ["foresight_canary"],
                               "restricted_recomputations_same_config": ["v_h1b_d30_attrited_only"],
                               "descriptive_not_variants": ["P5_attrited_membership_share"]},
    }
    verdict = decide(results)
    results["verdict"] = verdict
    log(f"[R002] VERDICT = {verdict['status']}  killed_by={verdict['killed_by']}")
    for x in verdict["reasons"]:
        log("   - " + x)

    (HERE / "config.json").write_text(json.dumps({
        "N": N, "H": H, "IS": IS, "OOS": OOS, "BATCH_MONTH": BATCH_MONTH,
        "MIN_HIST_IS_DAYS": MIN_HIST_IS_DAYS, "SENS_MIN_HIST": SENS_MIN_HIST,
        "DELTA_PRIMARY": DELTA_PRIMARY, "SENS_DELTA": SENS_DELTA,
        "PLACEBO_DRAWS": PLACEBO_DRAWS, "RANDOM_SEED": RANDOM_SEED,
        "MAX_VARIANTS": MAX_VARIANTS, "LAG": LAG,
        "costs": {"COMMISSION": r001.COMMISSION, "SELL_TAX": r001.SELL_TAX,
                  "SLIPPAGE": r001.SLIPPAGE},
        "prereg": "research/paper_loop/rounds/R002_prereg.md",
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    (HERE / "result.json").write_text(json.dumps(results, ensure_ascii=False, indent=2,
                                                  default=str), encoding="utf-8")
    (HERE / "run.log").write_text("\n".join(LOG), encoding="utf-8")
    log(f"[R002] -> {HERE}")
    return 0


def decide(res: dict) -> dict:
    """按 R002_prereg.md 第 8/9 节判定。不得事后放宽。"""
    killed, reasons = [], []
    t1, t2 = res["T1_composition"], res["T2_positive_control"]
    t4, t6 = res["T4_delisting_channel"], res["T6_pipeline_canary"]
    vd = res["variant_disclosure"]

    if not res["parity_with_R001"]["pass"]:
        killed.append("K6")
        reasons.append("K6 机制 parity 失败：本脚本未能复现 R001 冻结产物 → 本轮无效")
    if vd["variants_tried"] > vd["max_variants"]:
        killed.append("K7")
        reasons.append(f"K7 变体数 {vd['variants_tried']} > 上限 {vd['max_variants']} → 本轮无效")
    if not (t6["foresight_ic"] >= t6["k5_threshold"]):
        killed.append("K5")
        reasons.append(f"K5 完全预见金丝雀 IC={t6['foresight_ic']:.6f} < {t6['k5_threshold']}")
    if not (t2["discrimination"] >= t2["k4_threshold"]):
        killed.append("K4")
        reasons.append(f"K4 阳性对照 |diff|={t2['discrimination']:.4f} < {t2['k4_threshold']} "
                       f"→ 估计量无鉴别力（空实验）")

    h1a_stat = abs(t1["d_ic_real"]) > t1["placebo_abs_p95"]
    h1a_econ = (t1["batch_explained_share"] >= K2_MIN_SHARE
                if np.isfinite(t1["batch_explained_share"]) else False)
    if not h1a_stat:
        killed.append("K1")
        reasons.append(f"K1 构成通道统计判据未过：|dIC_real|={abs(t1['d_ic_real']):.4f} "
                       f"≤ 安慰剂 p95={t1['placebo_abs_p95']:.4f}")
    if not h1a_econ:
        killed.append("K2")
        reasons.append(f"K2 构成通道经济判据未过：batch 解释的衰减份额="
                       f"{t1['batch_explained_share']:+.3f} < {K2_MIN_SHARE}")

    if t4["d1_drop_bp"] < t4["k3_threshold_bp"]:
        killed.append("K3")
        reasons.append(f"K3 退市通道量级不足：δ={t4['primary']['delta']} 时 D1 净收益仅下降 "
                       f"{t4['d1_drop_bp']:.2f}bp < {t4['k3_threshold_bp']}bp")
    elif t4["d1_drop_bp"] < t4["h1b_accept_bp"]:
        reasons.append(f"H1b 未达成立线：下降 {t4['d1_drop_bp']:.2f}bp < {t4['h1b_accept_bp']}bp")

    drops = [(t4["r001_is_d1_net"] - s["d1_net"]) * 100.0 for s in t4["sensitivity"]]
    k8 = any((d >= t4["h1b_accept_bp"]) != (t4["d1_drop_bp"] >= t4["h1b_accept_bp"]) for d in drops)
    if k8:
        killed.append("K8")
        reasons.append(f"K8 ±20% δ 敏感性使 H1b 结论反转：drops={[round(d, 2) for d in drops]}bp")

    h1a = h1a_stat and h1a_econ
    h1b = (t4["d1_drop_bp"] >= t4["h1b_accept_bp"]) and not k8
    if {"K4", "K5", "K6", "K7"} & set(killed):
        status = "INCONCLUSIVE"
    elif not h1a and not h1b:
        status = "REJECTED"
        reasons.append("H1a 与 H1b 均不成立 → 「R001 的结果是数据构造产物」这一解释被证伪")
    elif h1a and h1b:
        status = "ADOPT_CANDIDATE"
        reasons.append("H1a 与 H1b 均成立 → 数据构造通道成立；本轮仍不产生新因子、不接生产")
    else:
        status = "INCONCLUSIVE"
        reasons.append(f"仅一条通道成立（H1a={h1a}, H1b={h1b}）")
    return {"status": status, "killed_by": killed, "reasons": reasons,
            "h1a_supported": bool(h1a), "h1b_supported": bool(h1b)}


if __name__ == "__main__":
    sys.exit(main())
