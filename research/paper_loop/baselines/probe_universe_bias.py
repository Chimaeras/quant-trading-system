#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""B002 基线探针：股票池偏差的「信号质量」（R002 立项前，只读）。

目的：在写 `rounds/R002_prereg.md` **之前**，先测出候选偏差通道在本地面板上
到底有多少可检验的信号质量。若某通道的信号质量接近 0，则针对它的实验是**空实验**，
不得写进预注册（R001 的 K2/K7 就是「条件不可触发 / 与假设不同构」的教训）。

测量三条通道（全部复用 R001 的 `evaluate()`，保证机制完全一致）：
  A. attrited（样本期内停止交易的名字）：数量、信号数、已实现收益、在 D1/D10 中的占比
  B. 因退出价缺失而被丢弃的信号（= Shumway 意义上的「缺失的退市收益」）的质量
  C. 2026-02 批量新增的代码（疑似供应商补录而非真实 IPO）：对 OOS 截面的构成占比

只读：只执行 SELECT。
"""

from __future__ import annotations

import importlib.util
import json
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
R001_SCRIPT = HERE.parent / "experiments" / "R001_reversal" / "run_reversal_ic.py"

_spec = importlib.util.spec_from_file_location("r001", R001_SCRIPT)
r001 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(r001)

OUT = HERE / "B002_universe_bias.json"


def main() -> int:
    panel, meta = r001.load_panel()
    close, openp, vol = panel["close"], panel["open"], panel["vol"]
    dates = close.index
    codes = list(close.columns)
    print(f"[probe] panel {len(dates)} dates x {len(codes)} codes", flush=True)

    first = close.notna().idxmax()
    last = close.notna()[::-1].idxmax()
    END = dates[-1]
    START = dates[0]

    attrited = [c for c in codes if last[c] < END]
    newcodes = [c for c in codes if first[c] > START]
    # 批量新增：首次出现日期集中在 2026-02
    batch = [c for c in newcodes if str(first[c])[:7] == "2026-02"]

    print(f"[probe] attrited={len(attrited)} new={len(newcodes)} batch2026_02={len(batch)}",
          flush=True)

    # ---- 通道 A：attrited 的名字 ----
    att_set = set(attrited)
    att_mask = pd.DataFrame(
        np.broadcast_to(np.array([c in att_set for c in codes])[None, :],
                        (len(dates), len(codes))),
        index=dates, columns=codes)

    # attrited 的末段已实现收益（最后 20 日），以及它们停止前的最后一日涨跌
    tail_rets, last_day_rets = [], []
    for c in attrited:
        s = close[c].dropna()
        if len(s) >= 21 and s.iloc[-21] > 0:
            tail_rets.append(float(s.iloc[-1] / s.iloc[-21] - 1.0))
        if len(s) >= 2 and s.iloc[-2] > 0:
            last_day_rets.append(float(s.iloc[-1] / s.iloc[-2] - 1.0))

    def desc(x):
        if not x:
            return {"n": 0}
        a = np.array(x)
        return {"n": len(a), "mean": float(a.mean()), "median": float(np.median(a)),
                "p10": float(np.percentile(a, 10)), "min": float(a.min())}

    # ---- 通道 B：退出价缺失导致被丢弃的信号质量 ----
    n, h = r001.PRIMARY_N, r001.PRIMARY_H
    entry = openp.shift(-1)
    exit_ = close.shift(-(1 + h))
    exit_missing = exit_.isna() & entry.notna() & (close / close.shift(n) - 1).notna()
    dropped_mass = int(exit_missing.to_numpy().sum())
    dropped_att = int((exit_missing & att_mask).to_numpy().sum())
    # 这些信号在「被丢弃前」的可见收益（signal→entry 已实现部分，不含退市段）
    sig = close / close.shift(n) - 1.0
    dropped_sig_ret = sig.where(exit_missing).stack().dropna()

    # ---- 通道 C：batch 代码对 OOS 截面的占比 ----
    oos_mask = np.array([str(d) >= r001.OOS[0] for d in dates])
    batch_set = set(batch)
    batch_cols = np.array([c in batch_set for c in codes])
    oos_cells = close.loc[oos_mask].notna().to_numpy()
    oos_total = int(oos_cells.sum())
    oos_batch = int(oos_cells[:, batch_cols].sum())
    is_mask = np.array([str(d) < r001.OOS[0] for d in dates])
    is_cells = close.loc[is_mask].notna().to_numpy()
    is_total = int(is_cells.sum())
    is_batch = int(is_cells[:, batch_cols].sum())
    # attrited 在 IS 截面的占比
    is_att = int(is_cells[:, np.array([c in att_set for c in codes])].sum())

    out = {
        "meta": {"panel_dates": len(dates), "panel_codes": len(codes),
                 "date_start": str(START), "date_end": str(END),
                 "primary_N": n, "primary_h": h},
        "channel_A_attrited": {
            "count": len(attrited),
            "codes": sorted(attrited),
            "share_of_panel_codes": len(attrited) / len(codes),
            "last_20d_return": desc(tail_rets),
            "last_day_return": desc(last_day_rets),
            "is_signal_cells": is_att,
            "is_signal_cells_share": is_att / is_total if is_total else None,
        },
        "channel_B_missing_exit": {
            "dropped_signal_count": dropped_mass,
            "dropped_signal_count_in_attrited": dropped_att,
            "dropped_reason": "entry 存在但 exit 价缺失（名字已停止交易或越过面板末尾）",
            "visible_signal_return_of_dropped": {
                "n": int(len(dropped_sig_ret)),
                "mean": float(dropped_sig_ret.mean()) if len(dropped_sig_ret) else None,
                "median": float(dropped_sig_ret.median()) if len(dropped_sig_ret) else None,
            },
        },
        "channel_C_batch_2026_02": {
            "count": len(batch),
            "is_cells_share": is_batch / is_total if is_total else None,
            "oos_cells_share": oos_batch / oos_total if oos_total else None,
            "batch_codes_sample": sorted(batch)[:20],
        },
        "notes": [
            "本探针只测量信号质量，不下任何结论；结论只在 R002 的预注册实验中给出。",
            "通道 B 的质量若为 0，则「退市收益缺失」通道在本面板上不可检验。",
        ],
    }
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in out.items() if k != "channel_A_attrited"},
                     ensure_ascii=False, indent=2)[:3000])
    print("\n[channel A]", json.dumps(out["channel_A_attrited"], ensure_ascii=False)[:800])
    print(f"\n[probe] -> {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
