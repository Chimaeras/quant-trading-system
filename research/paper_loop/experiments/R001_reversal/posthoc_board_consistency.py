#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R001 事后诊断：IS→OOS 衰减是否由股票池构成变化造成。

**本脚本是事后诊断（post-hoc），不参与 R001 的预注册判定。**
R001 的判定已由 `run_reversal_ic.py` 按 `rounds/R001_prereg.md` 第 9 节独立给出
（INCONCLUSIVE）。本节目的仅是回答一个新发现的混淆问题：

    本地 `market_data` 的板块覆盖在样本期内发生了结构性变化——
    2024 年**完全没有**创业板(300/301)与科创板(688)数据，2026 年两者合计约 2,000 只。
    因此 IS(2024-2025) 与 OOS(2026) 的截面构成不同，
    「样本外衰减」可能是构成变化造成的伪像，而不是因子失效。

诊断设计（每个都用与主实验完全相同的口径与成本）：
  A. IS 主板 / OOS 主板        —— 板块一致化
  B. IS 与 OOS 均有数据的代码交集 —— 代码级一致化
  C. 北交所（2024-2026 全程存在）—— 唯一全程可比的板块
  D. 逐年 IC（2024 / 2025 / 2026）—— 已有结果，此处重列便于对照

只读：仅执行 SELECT。
"""

from __future__ import annotations

import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_reversal_ic as R  # noqa: E402

N, H = R.PRIMARY_N, R.PRIMARY_H


def main() -> int:
    p, meta = R.load_panel()
    close = p["close"]
    dates = close.index
    cols = close.columns

    def is_mask(a, z):
        return np.asarray((dates >= a) & (dates <= z), dtype=bool)

    is_a = is_mask(*R.IS_A)
    is_b = is_mask(*R.IS_B)
    is_all = is_a | is_b
    oos = is_mask(*R.OOS)

    def f(flags):
        return pd.DataFrame(np.broadcast_to(np.asarray(flags, bool)[:, None],
                                           (len(dates), len(cols))),
                            index=dates, columns=cols)

    def board_of(code: str) -> str:
        c = str(code).split(".")[0]
        if c.startswith(("688", "689")):
            return "STAR"
        if c.startswith(("300", "301", "302")):
            return "CHINEXT"
        if c.startswith(("8", "4", "920")):
            return "BSE"
        return "MAIN"

    board = pd.Series({c: board_of(c) for c in cols})
    out: dict = {"note": "post-hoc 诊断，不参与 R001 判定", "N": N, "h": H, "cases": []}

    def run_case(name, seg_flags, code_subset=None):
        m = f(seg_flags)
        if code_subset is not None:
            m = m[code_subset]
        r = R.evaluate(p, meta, N, H, name, universe_mask=m)
        out["cases"].append({
            "case": name, "codes_in_mask": int(m.shape[1]),
            "ic_mean": r["ic_mean"], "ic_t_nw": r["ic_t_nw"], "ic_days": r["ic_days"],
            "d1_net": r["d1_net_mean"], "d10_net": r["d10_net_mean"],
            "net_spread_d1_d10": r["spread_net_d1_d10"],
            "universe_net": r["universe_net_mean"],
            "deciles_net": [round(d["net_mean"], 5) for d in r["deciles"]],
        })
        print(f"  {name:<34} IC={r['ic_mean']:+.4f} tNW={r['ic_t_nw']:+.2f} "
              f"days={r['ic_days']:>4} spread={r['spread_net_d1_d10']:+.5f}")
        return r

    # ---- 数据实况：板块在样本内的可用代码数（决定性证据）
    print("== 板块代码覆盖（按分段）==")
    cover = {}
    for b in ("MAIN", "CHINEXT", "STAR", "BSE"):
        bc = board[board == b].index
        cov = {}
        for name, fl in (("IS", is_all), ("OOS", oos)):
            sub = close.loc[fl, bc] if len(bc) else None
            cov[name] = int((sub.notna().sum(axis=1) >= 30).sum()) if sub is not None else 0
        cover[b] = cov
        print(f"  {b:<8} codes={len(bc):>5}  可用交易日(≥30只有数据): IS={cov['IS']:>4} OOS={cov['OOS']:>4}")
    out["board_coverage"] = cover

    print("== 诊断 A：板块一致化 ==")
    main_codes = board[board == "MAIN"].index
    run_case("A1_IS_MAIN", is_all, main_codes)
    run_case("A2_OOS_MAIN", oos, main_codes)
    bse_codes = board[board == "BSE"].index
    run_case("C1_IS_BSE", is_all, bse_codes)
    run_case("C2_OOS_BSE", oos, bse_codes)

    print("== 诊断 B：代码交集一致化 ==")
    have_is = close.loc[is_all].notna().any(axis=0)
    have_oos = close.loc[oos].notna().any(axis=0)
    both = cols[have_is & have_oos]
    print(f"  IS 有数据 {int(have_is.sum())} 只；OOS 有数据 {int(have_oos.sum())} 只；交集 {len(both)} 只")
    run_case("B1_IS_intersect", is_all, both)
    run_case("B2_OOS_intersect", oos, both)

    # 输出路径可被 env 重定向：本脚本经 `run_reversal_ic` 重算净收益，故其数值**不是**冻结的，
    # 而是「当前口径」的函数；口径改写后的重算必须写到别处，否则覆盖冻结诊断产物（预注册 §10.4）。
    out_path = os.environ.get(
        "POSTHOC_OUT_JSON",
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "posthoc_board_consistency.json"))
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2, default=str)
    print(f"结果写入 {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
