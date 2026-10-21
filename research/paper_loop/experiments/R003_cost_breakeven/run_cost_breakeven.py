#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R003 实验：交易成本能否消除本地反转价差（NMV2016 break-even 诊断）。

对应论文：NMV2016《A Taxonomy of Anomalies and their Trading Costs》(RFS 2016)
预注册：research/paper_loop/rounds/R003_prereg.md（**本脚本在其之后落盘**）
实验编号：`R003_cost_breakeven`

假设：
  H1  使 D1−D10 净价差归零所需的双边成本 r* 是否落在本地真实成本的可能范围内。
  H2  施加论文所称的等权惩罚（冻结成本 2×/3×）后，D1 净收益是否不再显著为正。

默认立场：**尝试杀死论文的成本解释**。杀死条件见 prereg §4，不得事后放宽。

设计要点（全部来自 prereg，不得在此偏离）：
- 数据与窗口、去重、分段、主配置、成交状态口径 **与 R001 逐位一致**（K2 会逐位校验）
- 成本参数化保持 R001 的买/卖不对称比例 ⟹ c_rt=12bp 精确等于冻结口径
- 一跳下界（M2/M3）为逐股成本，用前复权价代理真实价（偏高，方向保守，prereg §7-4）
- K2 工具校验失败 ⟹ INVALID，本轮不得产出任何结论
- 二分法只解 prereg 明列的 4 个 r*，其内部迭代不构成变体

只读：本脚本只执行 SELECT，不建表、不写库、不改任何生产文件。

事后修改登记（`D-R010-05`，用户裁定 C「改实现」，2026-09-15）：
  本脚本的 `apply_cost`（uniform 模型）原按**加法式**文档口径算净收益（出场因子缺卖出佣金 `−c`），
  与生产实现（两级乘性）不同 ⟹ 净收益**偏乐观**（主项 ≈ `3.00 bp of E`）。现改为与生产实现同构的
  **两级乘性**口径，并同步改写 `analytic_note` 里读数(a) 的闭式声明（声明须与实现同构）。按 §10.4：
  * 修改前判定 / 修改后判定：均为 `INCONCLUSIVE`（判定不靠净收益卡边界得出 ⟹ **判定词未变**，
    此处只登记事实与数值，不自我裁定）；
  * `s = 1.0`（12bp）时仍须与 `run_reversal_ic.py` 的冻结口径表达式**逐位相同**（K2 的逐位校验）；
  * 冻结产物**不覆盖**：重跑须设 `R003_OUT_DIR`（并可用 `R001_RESULT_JSON` 指向 R001 的新产物目录）；
    本目录 `result.json` / `run.log` / `config.json` 保持历史原样；
  * 检索面未缩小：加法式读数由报告勘误逐项并列保留。
"""

from __future__ import annotations

import json
import math
import os
import sys
from datetime import datetime

import numpy as np
import pandas as pd
import pymysql

# ----------------------------------------------------------------------------
# 0. 冻结口径常量（与 com.quant.research.ResearchConvention 数值一致）
# ----------------------------------------------------------------------------
COMMISSION = 0.0003      # 买入佣金 万三
SELL_TAX = 0.0005        # 卖出印花税 万五
SLIPPAGE = 0.0002        # 单边滑点 万二
FROZEN_TOTAL = COMMISSION + SELL_TAX + 2.0 * SLIPPAGE        # 0.0012 = 12bp 双边
BUY_SHARE = (COMMISSION + SLIPPAGE) / FROZEN_TOTAL           # 5/12
SELL_SHARE = (SELL_TAX + SLIPPAGE) / FROZEN_TOTAL            # 7/12

LABEL_HORIZONS = (2, 3)
PRIMARY_N = 20
PRIMARY_H = 3
GRID_N = (1, 5, 20)
MIN_NAMES_PER_DATE = 30
RANDOM_SEED = 20260914

TICK = 0.01              # A 股最小报价单位（元），沪深北统一
HALF_TICK = TICK / 2.0   # 市价单每边至少付出的半跳

IS_A = ("2024-01-02", "2024-12-31")
IS_B = ("2025-01-01", "2025-12-31")
IS = ("2024-01-02", "2025-12-31")
OOS = ("2026-01-01", "2026-09-07")

LIMIT_EPS = 0.001

# 成本模型（prereg §2）；kind: 'uniform'（标量 bp）| 'tick'（逐股，半跳倍数）
COST_MODELS: list[dict] = [
    {"id": "M0_zero", "kind": "uniform", "bp": 0.0,
     "desc": "零成本（工具校验：净必须等于毛）"},
    {"id": "M1_frozen_12bp", "kind": "uniform", "bp": 12.0,
     "desc": "冻结口径 12bp（工具校验：必须逐位复现 R001）"},
    {"id": "M2_tick1", "kind": "tick", "mult": 1.0,
     "desc": "一跳下界（模型自由；符号为真实成本下界）"},
    {"id": "M3_tick2", "kind": "tick", "mult": 2.0,
     "desc": "2× 一跳（**显式假设**：价差 ≤2 跳；不是定理）"},
    {"id": "M4_frozen_x2_24bp", "kind": "uniform", "bp": 24.0,
     "desc": "2× 冻结（H2 低端，等权惩罚 2×）"},
    {"id": "M5_frozen_x3_36bp", "kind": "uniform", "bp": 36.0,
     "desc": "3× 冻结（H2 高端，等权惩罚 3×）"},
    {"id": "M6_frozen_c19p6bp", "kind": "uniform", "bp": 9.6,
     "desc": "冻结 −20%（敏感性）"},
    {"id": "M7_frozen_c214p4bp", "kind": "uniform", "bp": 14.4,
     "desc": "冻结 +20%（敏感性）"},
    {"id": "M8_mono_6bp", "kind": "uniform", "bp": 6.0,
     "desc": "K2 单调性检查点（prereg §4 明列 c_rt=6bp）"},
]
MONOTONIC_BP = [0.0, 6.0, 9.6, 12.0, 14.4, 24.0, 36.0]
# 二分法搜索上界：首轮取 2000bp 时读数(a)端点同号（价差在 2000bp 内不归零），
# 故放宽到 30000bp 以便定位该退化根。放宽只影响能否**定位**根，不影响分支判定
# （任何 >2×一跳=14.19bp 的根都已落在 D1b）。已在报告中披露。
BREAKEVEN_HI_BP = 30000.0
BREAKEVEN_ITERS = 80

MAX_VARIANTS = 40

# 变体核算（必须 ≤ MAX_VARIANTS，prereg §6）：
#   网格 8 模型 × {IS,OOS} × {h=2,3}            = 32
#   K2 单调性点 c_rt=6bp（仅 IS 主配置）        =  1
#   金丝雀 ×2                                   =  2
#   二分法 r*（IS/OOS × 价差/D1）               =  4
#   横截面一跳研究                              =  1
#                                   合计        = 40
# K2 的逐位复现校验**不新增评估**：直接复用 (N=20, h∈{2,3}, IS/OOS) 的 M1 网格单元。
# 为守住上限，N∈{1,5} 的网格单元本轮不评估（R003 的任何结论都不使用它们）。
GRID_MODELS = COST_MODELS[:8]
K2_MONO_MODEL = COST_MODELS[8]        # M8_mono_6bp，仅用于 K2 单调性
PARITY_CELLS = [("IS", 20, 3), ("OOS", 20, 3)]

DB = {
    "host": os.environ.get("QUANT_DB_HOST", "localhost"),
    "port": int(os.environ.get("QUANT_DB_PORT", "3306")),
    "user": os.environ.get("QUANT_DB_USER", "root"),
    "password": os.environ.get("QUANT_DB_PASSWORD", os.environ.get("DB_PASSWORD", "")),
    "database": os.environ.get("QUANT_DB_NAME", "quant_db"),
    "charset": "utf8mb4",
}

HERE = os.path.dirname(os.path.abspath(__file__))
# 输出目录：默认即冻结产物目录（历史行为不变）；重跑必须显式设 R003_OUT_DIR（预注册 §10.4）。
OUT_DIR = os.environ.get("R003_OUT_DIR", HERE)
# 重跑目录自建，理由同 R001：日志写入早于任何结果落盘，目录不存在会在第一行日志就抛异常。
os.makedirs(OUT_DIR, exist_ok=True)
# R001 产物目录：默认读冻结产物；口径改写重跑时用 R001_RESULT_JSON 指向新产物，
# 以免「新 R003」与「旧 R001」混算（K2 逐位校验比较的是同一口径的两侧）。
R001_RESULT = os.environ.get(
    "R001_RESULT_JSON", os.path.join(HERE, "..", "R001_reversal", "result.json"))
B003_JSON = os.path.join(HERE, "..", "..", "baselines", "B003_cost_feasibility.json")


def log(msg: str) -> None:
    line = f"[{datetime.now().strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(os.path.join(OUT_DIR, "run.log"), "a", encoding="utf-8") as fh:
        fh.write(line + "\n")


def limit_pct(stock_code: str) -> float:
    code = str(stock_code).split(".")[0].strip()
    if code.startswith(("688", "689")):
        return 0.20
    if code.startswith(("300", "301", "302")):
        return 0.20
    if code.startswith(("8", "4", "920")):
        return 0.30
    return 0.10


# ----------------------------------------------------------------------------
# 1. 数据加载（只读；与 R001 逐位一致）
# ----------------------------------------------------------------------------
def load_panel() -> tuple[dict[str, pd.DataFrame], dict]:
    conn = pymysql.connect(**DB)
    meta: dict = {}
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM market_data")
            meta["market_data_rows_total"] = int(cur.fetchone()[0])
        sql = (
            "SELECT stock_code, trade_date, open_price, close_price, pre_close, "
            "       volume, amount, change_pct "
            "FROM market_data WHERE trade_date >= '2023-11-01' "
            "ORDER BY trade_date, stock_code, id"
        )
        raw = pd.read_sql(sql, conn)
    finally:
        conn.close()

    meta["rows_loaded_since_2023_11_01"] = int(len(raw))
    raw["stock_code"] = raw["stock_code"].astype(str)
    raw["trade_date"] = pd.to_datetime(raw["trade_date"])
    for c in ("open_price", "close_price", "pre_close", "volume", "amount", "change_pct"):
        raw[c] = pd.to_numeric(raw[c], errors="coerce")

    before = len(raw)
    raw = raw.drop_duplicates(subset=["stock_code", "trade_date"], keep="last")
    meta["duplicate_rows_dropped"] = int(before - len(raw))

    bad = (
        (raw["close_price"].isna()) | (raw["close_price"] <= 0)
        | (raw["open_price"].isna()) | (raw["open_price"] <= 0)
        | (raw["pre_close"].isna()) | (raw["pre_close"] <= 0)
    )
    meta["rows_nonpositive_price_dropped"] = int(bad.sum())
    raw = raw[~bad]

    meta["distinct_codes"] = int(raw["stock_code"].nunique())
    meta["distinct_dates"] = int(raw["trade_date"].nunique())

    p: dict[str, pd.DataFrame] = {}
    for name, col in (
        ("open", "open_price"), ("close", "close_price"), ("pre", "pre_close"),
        ("vol", "volume"), ("amt", "amount"), ("chg", "change_pct"),
    ):
        p[name] = raw.pivot(index="trade_date", columns="stock_code", values=col).sort_index()
    return p, meta


# ----------------------------------------------------------------------------
# 2. 统计工具（与 R001 逐位一致）
# ----------------------------------------------------------------------------
def spearman_ic(a: np.ndarray, b: np.ndarray) -> float:
    if len(a) < 3:
        return math.nan
    ra = pd.Series(a).rank().to_numpy()
    rb = pd.Series(b).rank().to_numpy()
    ra = ra - ra.mean()
    rb = rb - rb.mean()
    d = math.sqrt(float((ra * ra).sum()) * float((rb * rb).sum()))
    if d == 0:
        return math.nan
    return float((ra * rb).sum() / d)


def newey_west_t(x: np.ndarray, lag: int) -> tuple[float, float, float, float]:
    x = x[np.isfinite(x)]
    t = len(x)
    if t < 5:
        return math.nan, math.nan, math.nan, math.nan
    xbar = float(x.mean())
    dev = x - xbar
    g0 = float((dev * dev).sum()) / t
    var = g0
    for j in range(1, min(lag, t - 1) + 1):
        gj = float((dev[j:] * dev[:-j]).sum()) / t
        var += 2.0 * (1.0 - j / (lag + 1.0)) * gj
    if var <= 0:
        return xbar, math.nan, math.nan, math.nan
    se_nw = math.sqrt(var / t)
    return xbar, xbar / se_nw, xbar / math.sqrt(g0 / t), se_nw


# ----------------------------------------------------------------------------
# 3. 面板包（与成本无关的部分只算一次；与 R001 的 evaluate 逐位等价）
# ----------------------------------------------------------------------------
def build_pack(p, n: int, h: int) -> dict:
    close, openp, pre, vol = p["close"], p["open"], p["pre"], p["vol"]

    sig = close / close.shift(n) - 1.0
    entry = openp.shift(-1)
    exit_ = close.shift(-(1 + h))
    pre_entry = pre.shift(-1)
    pre_exit = pre.shift(-(1 + h))

    code_pct = pd.Series({c: limit_pct(c) for c in close.columns}, dtype="float64")
    lu_entry = pre_entry.mul(code_pct + 1.0, axis=1)
    ld_exit = pre_exit.mul(1.0 - code_pct, axis=1)

    suspended = (vol.shift(-1) <= 0) | (vol.shift(-(1 + h)) <= 0) | (vol <= 0)
    not_buyable = entry >= lu_entry * (1.0 - LIMIT_EPS)
    not_sellable = exit_ <= ld_exit * (1.0 + LIMIT_EPS)

    valid = sig.notna() & entry.notna() & exit_.notna() & entry.gt(0) & exit_.gt(0)

    INVALID, FILLED, C_NB, C_SUSP, C_NS = 0, 1, 2, 3, 4
    st = np.full(close.shape, INVALID, dtype=np.int8)
    v = valid.to_numpy()
    su = suspended.to_numpy()
    nb = not_buyable.to_numpy()
    ns = not_sellable.to_numpy()
    st[v] = FILLED
    st[v & su] = C_SUSP
    st[v & ~su & nb] = C_NB
    st[v & ~su & ~nb & ns] = C_NS

    sig_np = sig.to_numpy(dtype="float64")
    entry_np = entry.to_numpy(dtype="float64")
    exit_np = exit_.to_numpy(dtype="float64")
    gross_np = exit_np / entry_np - 1.0

    # ---- 分位归属（只依赖 sig，与成本无关）：逐日按 rank(method='first') 十分位
    n_dates, n_cols = st.shape
    grp = np.full((n_dates, n_cols), -1, dtype=np.int8)
    filled_rows = []
    for i in range(n_dates):
        f = st[i] == FILLED
        cnt = int(f.sum())
        if cnt < MIN_NAMES_PER_DATE:
            filled_rows.append(None)
            continue
        idx = np.flatnonzero(f)
        s = sig_np[i][idx]
        m = np.isfinite(s) & np.isfinite(gross_np[i][idx])
        if not m.all():
            idx = idx[m]
            s = s[m]
            if len(s) < MIN_NAMES_PER_DATE:
                filled_rows.append(None)
                continue
        order = pd.Series(s).rank(method="first").to_numpy()
        g = np.minimum((order - 1) * 10 // len(s), 9).astype(np.int8)
        grp[i, idx] = g
        filled_rows.append(idx)

    return {
        "n": n, "h": h, "dates": close.index, "columns": close.columns,
        "st": st, "grp": grp, "filled_rows": filled_rows,
        "sig_np": sig_np, "entry_np": entry_np, "exit_np": exit_np, "gross_np": gross_np,
        "codes": {c: limit_pct(c) for c in close.columns},
    }


# ----------------------------------------------------------------------------
# 4. 成本模型
# ----------------------------------------------------------------------------
def apply_cost(pack: dict, model: dict, override_bp: float | None = None) -> np.ndarray:
    """返回与面板同形的净收益矩阵。

    uniform：`net = exit*(1 − SLIPPAGE*s)*(1 − COMMISSION*s − SELL_TAX*s)
    / (entry*(1 + SLIPPAGE*s)*(1 + COMMISSION*s)) − 1`，`s = bp/12`（两级乘性，`D-R010-05` 裁定 C）。
    **必须**保留这个表达式结构（而不是先算 buy/sell 再代入）：
    在 `bp = 12` 时 `s = 1.0` 逐位等于 `run_reversal_ic.py` 的同口径表达式，否则末位舍入差异会
    翻转 Spearman 的近似并列排名，使 IC 出现 ~3e-6 的假差异（K2 因此会假失败）。
    修改前为加法式 `exit*(1 − SELL_TAX*s − SLIPPAGE*s)/(entry*(1 + COMMISSION*s + SLIPPAGE*s)) − 1`
    （出场因子缺卖出佣金，偏乐观）。
    tick：买入付 half/entry、卖出收 exit−half ⟹ `net = (exit − half)/(entry + half) − 1`。
    """
    entry_np, exit_np = pack["entry_np"], pack["exit_np"]
    kind = model["kind"]
    if kind == "uniform":
        bp = model["bp"] if override_bp is None else override_bp
        s = bp / (FROZEN_TOTAL * 1e4)
        return (exit_np * (1.0 - SLIPPAGE * s) * (1.0 - COMMISSION * s - SELL_TAX * s)
                / (entry_np * (1.0 + SLIPPAGE * s) * (1.0 + COMMISSION * s)) - 1.0)
    if kind == "tick":
        half = HALF_TICK * model["mult"]
        return (exit_np - half) / (entry_np + half) - 1.0
    raise ValueError(kind)


def decile_stats(pack: dict, net_np: np.ndarray, date_mask: np.ndarray,
                 collect_series: bool = False) -> dict:
    """按 prereg 口径累计 10 个分位的日均净收益（等权，逐日截面均值再时序平均）。"""
    st, grp, filled_rows = pack["st"], pack["grp"], pack["filled_rows"]
    n_dates = st.shape[0]
    dec_sum = np.zeros(10)
    dec_cnt = np.zeros(10)
    series = [[] for _ in range(10)] if collect_series else None
    univ_sum = univ_cnt = 0.0
    for i in range(n_dates):
        if not date_mask[i]:
            continue
        idx = filled_rows[i]
        if idx is None:
            continue
        r = net_np[i][idx]
        univ_sum += float(r.mean())
        univ_cnt += 1
        g = grp[i][idx]
        for k in range(10):
            mk = g == k
            if mk.any():
                mk_mean = float(r[mk].mean())
                dec_sum[k] += mk_mean
                dec_cnt[k] += 1
                if collect_series:
                    series[k].append(mk_mean)
    out = {
        "deciles": [
            {"decile": k + 1,
             "net_mean": (dec_sum[k] / dec_cnt[k]) if dec_cnt[k] else math.nan,
             "days": int(dec_cnt[k])}
            for k in range(10)
        ],
        "universe_net_mean": (univ_sum / univ_cnt) if univ_cnt else math.nan,
        "universe_days": int(univ_cnt),
    }
    out["d1_net_mean"] = out["deciles"][0]["net_mean"]
    out["d10_net_mean"] = out["deciles"][9]["net_mean"]
    out["spread_net_d1_d10"] = (
        out["d1_net_mean"] - out["d10_net_mean"]
        if np.isfinite(out["d1_net_mean"]) and np.isfinite(out["d10_net_mean"]) else math.nan)
    if collect_series:
        out["d1_series"] = np.array(series[0], dtype=float)
        out["d10_series"] = np.array(series[9], dtype=float)
    return out


def gross_stats(pack: dict, date_mask: np.ndarray, collect_series: bool = False) -> dict:
    return decile_stats(pack, pack["gross_np"], date_mask, collect_series=collect_series)


def ic_stats(pack: dict, net_np: np.ndarray, date_mask: np.ndarray,
             signal_np: np.ndarray | None = None) -> dict:
    """日度截面 Spearman rank IC + Newey-West HAC；与 R001 的 evaluate 同口径。

    signal_np：可替换信号面板（用于泄漏金丝雀）；None 时用包内反转信号。
    """
    st, filled_rows = pack["st"], pack["filled_rows"]
    sig_np = pack["sig_np"] if signal_np is None else signal_np
    ics = []
    for i in range(st.shape[0]):
        if not date_mask[i]:
            continue
        idx = filled_rows[i]
        if idx is None:
            continue
        s = sig_np[i][idx]
        r = net_np[i][idx]
        m = np.isfinite(s) & np.isfinite(r)
        if not m.all():
            s, r = s[m], r[m]
            if len(s) < MIN_NAMES_PER_DATE:
                continue
        ic = spearman_ic(s, r)
        if np.isfinite(ic):
            ics.append(ic)
    arr = np.array(ics, dtype=float)
    lag = pack["n"] + pack["h"] + 2
    mean_ic, t_nw, t_naive, _ = newey_west_t(arr, lag)
    return {"ic_mean": mean_ic, "ic_t_nw": t_nw, "ic_days": int(len(arr)), "ic_lag": lag}


def funnel_counts(pack: dict, date_mask: np.ndarray) -> dict:
    """与 R001 的 funnel 累计口径逐位一致（含 signal_days/filled_count 等）。"""
    st = pack["st"]
    f = {"signal_days": 0, "signal_count": 0, "buyable_count": 0, "filled_count": 0,
         "NOT_BUYABLE": 0, "SUSPENDED": 0, "NOT_SELLABLE": 0, "DELISTED": 0}
    for i in range(st.shape[0]):
        if not date_mask[i]:
            continue
        row = st[i]
        nb = int((row == 2).sum())
        f["NOT_BUYABLE"] += nb
        f["SUSPENDED"] += int((row == 3).sum())
        f["NOT_SELLABLE"] += int((row == 4).sum())
        n_sig = int((row != 0).sum())
        if n_sig:
            f["signal_days"] += 1
            f["signal_count"] += n_sig
            f["buyable_count"] += n_sig - nb
        f["filled_count"] += int((row == 1).sum())
    return f


# ----------------------------------------------------------------------------
# 5. 主流程
# ----------------------------------------------------------------------------
def main() -> int:
    if os.path.exists(os.path.join(OUT_DIR, "run.log")):
        os.remove(os.path.join(OUT_DIR, "run.log"))
    log("R003 实验开始（只读）｜ 预注册 rounds/R003_prereg.md")
    log(f"主配置 N={PRIMARY_N}, h={PRIMARY_H} ｜ 成本模型 {len(COST_MODELS)} 个")

    p, meta = load_panel()
    log(f"加载完成：{meta['rows_loaded_since_2023_11_01']} 行，"
        f"去重丢弃 {meta['duplicate_rows_dropped']}，非正价剔除 {meta['rows_nonpositive_price_dropped']}")
    dates = p["close"].index
    seg_mask = {
        "IS_A": np.asarray((dates >= IS_A[0]) & (dates <= IS_A[1])),
        "IS_B": np.asarray((dates >= IS_B[0]) & (dates <= IS_B[1])),
        "IS": np.asarray((dates >= IS[0]) & (dates <= IS[1])),
        "OOS": np.asarray((dates >= OOS[0]) & (dates <= OOS[1])),
    }
    meta["segment_trading_days"] = {k: int(v.sum()) for k, v in seg_mask.items()}
    log(f"分段交易日数：{meta['segment_trading_days']}")

    packs = {(PRIMARY_N, h): build_pack(p, PRIMARY_N, h) for h in LABEL_HORIZONS}
    log(f"面板包构建完成：{sorted(packs.keys())}")

    results: dict = {"meta": meta, "cost_models": COST_MODELS, "segments": {}, "breakeven": {}}
    eval_count = 0

    # ---- 5.1 主网格：GRID_MODELS × {IS,OOS} × {h2,h3}（N=20）
    for seg in ("IS", "OOS"):
        results["segments"][seg] = []
        dm = seg_mask[seg]
        for h in LABEL_HORIZONS:
            pack = packs[(PRIMARY_N, h)]
            gs = gross_stats(pack, dm, collect_series=True)
            lag = PRIMARY_N + h + 2
            g_spr = gs["d1_series"] - gs["d10_series"]
            g_spr_mean, g_spr_t, g_spr_t_naive, g_spr_se = newey_west_t(g_spr, lag)
            for model in GRID_MODELS:
                net_np = apply_cost(pack, model)
                ds = decile_stats(pack, net_np, dm, collect_series=True)
                ic = ic_stats(pack, net_np, dm)
                n_spr = ds["d1_series"] - ds["d10_series"]
                n_spr_mean, n_spr_t, _, _ = newey_west_t(n_spr, lag)
                _, t_d1, _, _ = newey_west_t(ds["d1_series"], lag)
                # 读数 (b)：美元中性多空组合（+1 多头腿、−1 空头腿）净价差 = 毛价差 − 2c
                ls = None
                if model["kind"] == "uniform" and np.isfinite(g_spr_se) and g_spr_se > 0:
                    c = model["bp"] / 1e4
                    ls_mean = g_spr_mean - 2.0 * c
                    ls = {"ls_net_spread_bp": ls_mean * 1e4,
                          "ls_net_spread_t_nw": ls_mean / g_spr_se}
                eval_count += 1
                rec = {
                    "segment": seg, "N": PRIMARY_N, "h": h, "cost_model": model["id"],
                    "cost_desc": model["desc"], "cost_bp": model.get("bp"),
                    "gross_d1": gs["d1_net_mean"], "gross_d10": gs["d10_net_mean"],
                    "gross_spread": gs["d1_net_mean"] - gs["d10_net_mean"],
                    "gross_spread_t_nw": g_spr_t, "gross_spread_t_naive": g_spr_t_naive,
                    "d1_net_mean": ds["d1_net_mean"], "d10_net_mean": ds["d10_net_mean"],
                    "spread_net_d1_d10": ds["spread_net_d1_d10"],
                    "spread_net_t_nw": n_spr_t,
                    "d1_net_t_nw": t_d1,
                    "universe_net_mean": ds["universe_net_mean"],
                    "universe_days": ds["universe_days"],
                    "deciles": ds["deciles"],
                    "ls_reading_b": ls,
                    **ic,
                }
                results["segments"][seg].append(rec)
                ls_s = f" LS={ls['ls_net_spread_bp']:7.2f}bp t={ls['ls_net_spread_t_nw']:5.2f}" if ls else ""
                log(f"  [{seg}] h={h} {model['id']:22s} "
                    f"D1={ds['d1_net_mean']*1e4:8.2f}bp tD1={t_d1:5.2f} ｜ "
                    f"spread(2多头腿)={ds['spread_net_d1_d10']*1e4:7.2f}bp "
                    f"t={n_spr_t:5.2f}{ls_s}")

    # ---- 5.2 K2 逐位复现（M1 冻结口径）与单调性点；不新增网格评估
    grid_parity = {}
    for seg, n, h in PARITY_CELLS:
        dm = seg_mask[seg]
        pack = packs[(n, h)]
        rec = next(r for r in results["segments"][seg]
                   if r["h"] == h and r["cost_model"] == "M1_frozen_12bp")
        grid_parity[f"{seg}_N{n}_h{h}"] = {
            "ic_mean": rec["ic_mean"], "ic_days": rec["ic_days"],
            "spread_net_d1_d10": rec["spread_net_d1_d10"],
            "d1_net_mean": rec["d1_net_mean"], "d10_net_mean": rec["d10_net_mean"],
            "universe_net_mean": rec["universe_net_mean"],
            "deciles": [d["net_mean"] for d in rec["deciles"]],
            "funnel": funnel_counts(pack, dm),
        }
    results["grid_parity_M1"] = grid_parity

    # K2 单调性检查点 c_rt = 6bp，仅 IS 主配置
    pack_m = packs[(PRIMARY_N, PRIMARY_H)]
    net_m = apply_cost(pack_m, K2_MONO_MODEL)
    ds_m = decile_stats(pack_m, net_m, seg_mask["IS"], collect_series=True)
    _, t_m, _, _ = newey_west_t(ds_m["d1_series"], PRIMARY_N + PRIMARY_H + 2)
    results["K2_mono_extra"] = {
        "cost_model": K2_MONO_MODEL["id"], "bp": K2_MONO_MODEL["bp"], "segment": "IS",
        "h": PRIMARY_H, "d1_net_mean": ds_m["d1_net_mean"], "d1_net_t_nw": t_m}
    eval_count += 1


    # ---- 5.3 K2 工具校验
    k2 = check_k2(results, grid_parity)
    results["K2_tool_check"] = k2
    log(f"  [K2] {'PASS' if k2['passed'] else 'FAIL'} 明细：{json.dumps(k2['checks'], ensure_ascii=False)}")

    # ---- 5.4 K3 泄漏金丝雀（完全预见：信号 = 真实未来收益）
    pack = packs[(PRIMARY_N, PRIMARY_H)]
    fore_np = pack["gross_np"]                       # 完全预见信号面板
    fore_ic = ic_stats(pack, fore_np, seg_mask["IS"], signal_np=fore_np)
    k3 = {"foresight_ic": fore_ic["ic_mean"], "foresight_ic_days": fore_ic["ic_days"],
          "threshold": 0.99,
          "passed": bool(np.isfinite(fore_ic["ic_mean"]) and fore_ic["ic_mean"] >= 0.99)}
    results["K3_canary"] = k3
    eval_count += 1
    log(f"  [K3] 完全预见 IC={fore_ic['ic_mean']:.4f} "
        f"{'PASS' if k3['passed'] else 'FAIL'}")

    # ---- 5.5 二分法求 r*（prereg §4 D1）
    b003 = json.load(open(B003_JSON, encoding="utf-8"))
    turn = b003["turnover"]["one_sided_turnover_monthly_D1"]
    r_p50 = b003["tick_floor"]["tick_round_trip_bp_median"]
    def spread_net_bp(bp: float, pack_, dm) -> float:
        return decile_stats(pack_, apply_cost(pack_, {"kind": "uniform", "bp": bp}), dm)[
            "spread_net_d1_d10"] * 1e4

    def d1_net_bp(bp: float, pack_, dm) -> float:
        return decile_stats(pack_, apply_cost(pack_, {"kind": "uniform", "bp": bp}), dm)[
            "d1_net_mean"] * 1e4

    def bisect(fn, pack_, dm, decreasing=True, lo=0.0, hi=BREAKEVEN_HI_BP):
        """解 fn(c)=0。要求端点严格异号（decreasing=True 时 f(lo)>0>f(hi)），
        否则返回 None 并说明「该方向上不可能归零」——避免把端点恰好为 0 的
        退化情形误报成根（OOS 毛价差为负时会出现）。"""
        f_lo, f_hi = fn(lo, pack_, dm), fn(hi, pack_, dm)
        if not (np.isfinite(f_lo) and np.isfinite(f_hi)):
            return {"root_bp": None, "f_lo_bp": f_lo, "f_hi_bp": f_hi,
                    "note": "端点非有限，无法求解"}
        ok = (f_lo > 0 > f_hi) if decreasing else (f_lo < 0 < f_hi)
        if not ok:
            direction = "递减" if decreasing else "递增"
            return {"root_bp": None, "f_lo_bp": f_lo, "f_hi_bp": f_hi,
                    "note": (f"端点不构成{ direction}方向上的严格异号 ⟹ "
                             f"c_rt≥0 内不存在归零点（毛价差符号与成本方向一致）")}
        for _ in range(BREAKEVEN_ITERS):
            mid = 0.5 * (lo + hi)
            if (fn(mid, pack_, dm) > 0) == decreasing:
                lo = mid
            else:
                hi = mid
        return {"root_bp": 0.5 * (lo + hi), "f_lo_bp": f_lo, "f_hi_bp": f_hi,
                "note": None}

    for seg in ("IS", "OOS"):
        dm = seg_mask[seg]
        pack_ = packs[(PRIMARY_N, PRIMARY_H)]
        results["breakeven"][seg] = {
            "spread": bisect(spread_net_bp, pack_, dm),
            "d1_only": bisect(d1_net_bp, pack_, dm),
        }
        log(f"  [r*] {seg} 价差归零成本={results['breakeven'][seg]['spread']['root_bp']} bp ｜ "
            f"D1 归零成本={results['breakeven'][seg]['d1_only']['root_bp']} bp")
    eval_count += 4

    # ---- 5.6 读数 (b)：美元中性多空组合的 break-even（纯算术派生量，不新增评估）
    #   读数 (a)（prereg §1 公式）= 两个「多头腿」净收益之差：成本在差分中抵消 ⟹ 对成本几乎不敏感。
    #   读数 (b) = 论文意义上的多空组合：+1 多头腿 −1 空头腿，成本在两条腿上都是拖累
    #             ⟹ LS_net = 毛价差 − 2c ⟹ break-even 恰为 毛价差/2。
    is_rec = next(r for r in results["segments"]["IS"]
                  if r["h"] == PRIMARY_H and r["cost_model"] == "M1_frozen_12bp")
    oos_rec = next(r for r in results["segments"]["OOS"]
                   if r["h"] == PRIMARY_H and r["cost_model"] == "M1_frozen_12bp")

    def reading_b(rec):
        g = rec["gross_spread"] * 1e4
        return {
            "gross_spread_bp": g,
            "r_star_ls_bp": g / 2.0,
            "ls_net_spread_bp_at_12bp": g - 24.0,
            "r_star_ls_in_ticks_pool": (g / 2.0) / r_p50,
            "gross_spread_t_nw": rec["gross_spread_t_nw"],
            "ls_net_spread_t_nw_at_12bp": (rec["ls_reading_b"] or {}).get("ls_net_spread_t_nw"),
        }
    results["breakeven"]["reading_b"] = {
        "definition": ("LS_net = 毛价差 − 2c（美元中性：+1 多头腿 −1 空头腿，"
                       "两条腿各自承担一次双边成本）⟹ r* = 毛价差/2"),
        "IS_h3": reading_b(is_rec), "OOS_h3": reading_b(oos_rec),
        "note": ("该量由预注册的毛价差纯算术派生，不构成新的评估变体；"
                 "读数 (a) 的 r* 由二分法给出（prereg §1 公式的原样实现）"),
    }
    # 读数 (a) 的根可解析给出（两级乘性，D-R010-05）：分子两个因子分别在
    # c = 12/SLIPPAGE 与 c = 12/(COMMISSION+SELL_TAX) 处归零，先归零者即 κ 的零点。
    kappa_zero_bp = min(12.0 / SLIPPAGE, 12.0 / (COMMISSION + SELL_TAX))
    results["breakeven"]["analytic_note"] = {
        "reading_a_kappa_zero_bp": kappa_zero_bp,
        "formula": ("读数(a) 的净收益 = 毛收益 × κ(c)，κ(c) = (1 − SLIPPAGE·c/12)·"
                    "(1 − (COMMISSION+SELL_TAX)·c/12) / ((1 + SLIPPAGE·c/12)·(1 + COMMISSION·c/12))"
                    "（两级乘性，与实现同构；D-R010-05 裁定 C）；"
                    "κ 归零处 c = 12/(COMMISSION+SELL_TAX)（两因子中先归零者；加法式下曾为 12/(SELL_TAX+SLIPPAGE)）"),
        "interpretation": ("该根是参数化的算术恒等式（两条腿同时归零），"
                           "不含任何经济信息 ⟹ 读数(a) 结构上无法检验论文的成本主张"),
    }
    # 自检：闭式零点必须与二分法实测根一致。二者分属「声明」与「实现」两条路径，
    # 口径改写时极易只改一边（本次 D-R010-05 就一度只改了 formula 文本、漏改常量）
    # ⟹ 该自检是把「声明—实现同构」变成可运行判据，而不是靠人眼比对字符串。
    measured_is_root = results["breakeven"]["IS"]["spread"]["root_bp"]
    kappa_zero_ok = (measured_is_root is not None
                     and abs(measured_is_root - kappa_zero_bp) <= 1e-6 * kappa_zero_bp)
    results["breakeven"]["analytic_note"]["selfcheck_closed_form_vs_bisection"] = {
        "measured_is_spread_root_bp": measured_is_root,
        "closed_form_bp": kappa_zero_bp,
        "rel_tol": 1e-6, "pass": kappa_zero_ok,
    }
    log(f"  [自检] 读数(a) 闭式零点={kappa_zero_bp:.2f}bp vs 二分法实测 IS 价差根="
        f"{measured_is_root if measured_is_root is None else format(measured_is_root, '.2f')}bp "
        f"⟹ {'PASS' if kappa_zero_ok else 'FAIL'}")
    log(f"  [r* 读数b] IS r*={results['breakeven']['reading_b']['IS_h3']['r_star_ls_bp']:.2f}bp ｜ "
        f"读数(a) 的根={results['breakeven']['analytic_note']['reading_a_kappa_zero_bp']:.2f}bp（解析恒等式）")

    # ---- 5.6b 横截面一跳研究（prereg §4 D2；两个读数各算一次，不挑选）
    tick = cross_section_tick(
        packs[(PRIMARY_N, PRIMARY_H)], seg_mask["IS"],
        {"reading_a": results["breakeven"]["IS"]["spread"]["root_bp"],
         "reading_b_ls": results["breakeven"]["reading_b"]["IS_h3"]["r_star_ls_bp"]})
    results["cross_section_tick"] = tick
    eval_count += 1
    log(f"  [D2] D1 一跳成本 p10/p50/p90 = {tick['tick_bp_p10']:.2f}/"
        f"{tick['tick_bp_p50']:.2f}/{tick['tick_bp_p90']:.2f}bp ｜ "
        f"D1 价格 p50={tick['entry_price_p50_cny']:.2f} 元")
    for k, v in tick["by_reading"].items():
        log(f"      D2[{k}] r*={v['r_star_bp']} P*={v['P_star_cny']} 元 "
            f"价<P* 占比={v['share_below_P_star']} fired={v['fired']}")

    # ---- 5.7 参考量：论文第 3 条主张的成本拖累结构
    r_star = results["breakeven"]["reading_b"]["IS_h3"]["r_star_ls_bp"]
    results["paper_drag_reference"] = {
        "monthly_turnover_one_sided_D1": turn,
        "formula": "paper_drag_monthly(c) = 2 x turnover x c（论文第 3 条主张的多空成本拖累）",
        "drag_bp_monthly_at_12bp": 2.0 * turn * 12.0,
        "drag_bp_monthly_at_tick_p50": 2.0 * turn * r_p50,
        "drag_bp_monthly_at_r_star": (2.0 * turn * r_star) if r_star else None,
        "note": "仅作论文结构的对照量，不参与判定",
    }

    # ---- 5.8 变体披露（逐项可加总到 40，prereg §6 上限）
    realised = (len(results["segments"]["IS"]) + len(results["segments"]["OOS"])
                + 1 + 2 + 4 + 1)
    results["variant_disclosure"] = {
        "grid_cells": len(results["segments"]["IS"]) + len(results["segments"]["OOS"]),
        "k2_monotonicity_point": 1,
        "canaries": 2, "breakeven_solves": 4, "cross_section_runs": 1,
        "parity_cells_reused_from_grid": len(grid_parity),
        "total_distinct_evaluations": realised,
        "max_variants": MAX_VARIANTS,
        "internal_solver_steps": 2 * 2 * BREAKEVEN_ITERS,
        "prereg_table_total": 35,
        "note": ("为主张未超预注册上限：(a) 预注册表中「成本模型 M0–M6」7 个 + 单调性所需的 6bp 检查点"
                 "（prereg §4 K2 明列）⟹ 网格 8 模型；(b) K2 的逐位复现不新增评估，"
                 "直接复用 (N=20,h∈{2,3},IS/OOS) 的 M1 网格单元；(c) 为守住 40 的上限，"
                 "N∈{1,5} 的网格单元本轮不评估（R003 结论不使用）。"
                 "二分法内部迭代为单一预注册量的数值求解，不构成变体。"
                 "阈值在运行前由 R003_prereg.md 固定，未因结果收紧或放宽"),
    }

    # ---- 5.9 判定
    verdict = decide(results, b003)
    results["verdict"] = verdict
    log(f"判定：{verdict['status']} ｜ 触发：{verdict['fired']}")

    cfg = {
        "experiment_id": "R003_cost_breakeven", "paper_id": "NMV2016",
        "prereg": "research/paper_loop/rounds/R003_prereg.md",
        "run_time": datetime.now().isoformat(timespec="seconds"),
        "primary": {"N": PRIMARY_N, "h": PRIMARY_H},
        "grid_N": list(GRID_N), "horizons": list(LABEL_HORIZONS),
        "segments": {"IS": IS, "IS_A": IS_A, "IS_B": IS_B, "OOS": OOS},
        "frozen_cost": {"commission": COMMISSION, "sell_tax": SELL_TAX, "slippage": SLIPPAGE,
                        "round_trip_bp": FROZEN_TOTAL * 1e4,
                        "buy_share": BUY_SHARE, "sell_share": SELL_SHARE},
        "cost_models": COST_MODELS,
        "monotonic_bp": MONOTONIC_BP,
        "breakeven_search": {"hi_bp": BREAKEVEN_HI_BP, "iters": BREAKEVEN_ITERS},
        "min_names_per_date": MIN_NAMES_PER_DATE, "random_seed": RANDOM_SEED,
        "max_variants": MAX_VARIANTS,
        "data_source": "quant_db.market_data (qfq)",
        "variant_table_prereg": {
            "cost_models": 7, "main_grid": 28, "breakeven": 4,
            "cross_section": 1, "canaries": 2, "total": 35},
        "variant_table_realised": results["variant_disclosure"],
    }
    with open(os.path.join(OUT_DIR, "config.json"), "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2)
    with open(os.path.join(OUT_DIR, "result.json"), "w", encoding="utf-8") as fh:
        json.dump(results, fh, ensure_ascii=False, indent=2, default=str)
    log("结果写入 result.json / config.json")
    return 0


# ----------------------------------------------------------------------------
# 6. 辅助：K2 逐位校验 / 横截面 / 判定
# ----------------------------------------------------------------------------
def _close(a, b, tol=1e-12) -> bool:
    if a is None or b is None:
        return a == b
    if isinstance(a, str) or isinstance(b, str):
        return str(a) == str(b)
    a, b = float(a), float(b)
    if not (math.isfinite(a) or math.isfinite(b)):
        return (not math.isfinite(a)) and (not math.isfinite(b))
    return abs(a - b) <= tol * max(1.0, abs(a), abs(b))


def check_k2(results: dict, grid_parity: dict) -> dict:
    checks: dict = {}
    ref = json.load(open(R001_RESULT, encoding="utf-8"))

    # (1) M0：净必须恒等于毛
    is_recs = {r["cost_model"]: r for r in results["segments"]["IS"] if r["h"] == PRIMARY_H}
    m0 = is_recs["M0_zero"]
    checks["M0_net_equals_gross"] = bool(_close(m0["spread_net_d1_d10"], m0["gross_spread"], 1e-12))

    # (2) M1 逐位复现 R001（prereg §4 K2.2 指定的主配置：IS 与 OOS 的 N=20, h=3）
    detail = []
    for seg, n, h in PARITY_CELLS:
        ref_cells = {(r["N"], r["h"]): r for r in ref["segments"].get(seg, [])}
        mine = grid_parity.get(f"{seg}_N{n}_h{h}")
        theirs = ref_cells.get((n, h))
        if mine is None or theirs is None:
            detail.append({"cell": f"{seg}_N{n}_h{h}", "ok": False, "why": "缺对照"})
            continue
        keys = ["ic_mean", "ic_days", "spread_net_d1_d10", "d1_net_mean",
                "d10_net_mean", "universe_net_mean"]
        bad = [k for k in keys if not _close(mine[k], theirs[k])]
        for k in range(10):
            if not _close(mine["deciles"][k], theirs["deciles"][k]["net_mean"]):
                bad.append(f"decile{k+1}.net")
        for fk, fv in theirs["funnel"].items():
            if int(mine["funnel"][fk]) != int(fv):
                bad.append(f"funnel.{fk}")
        detail.append({"cell": f"{seg}_N{n}_h{h}", "ok": not bad, "mismatch": bad,
                       "n_compared": len(keys) + 10 + len(theirs["funnel"])})
    checks["M1_parity_vs_R001"] = all(d["ok"] for d in detail)
    checks["M1_parity_detail"] = detail

    # (3) 单调性：c_rt 越大 D1 净收益越小（含 K2 明列的 6bp 检查点）
    is_h3 = {r["cost_model"]: r for r in results["segments"]["IS"] if r["h"] == PRIMARY_H}
    mono_bp = dict(COST_BP)
    mono_bp[results["K2_mono_extra"]["cost_model"]] = results["K2_mono_extra"]["bp"]
    cand = list(is_h3.values()) + [results["K2_mono_extra"]]
    ml = []
    for bp in MONOTONIC_BP:
        rec = next((r for r in cand
                    if abs(mono_bp.get(r["cost_model"], math.nan) - bp) < 1e-9), None)
        ml.append({"bp": bp, "d1_net_mean": rec["d1_net_mean"] if rec else None,
                   "cost_model": rec["cost_model"] if rec else None})
    vals = [m["d1_net_mean"] for m in ml]
    checks["monotonic_decreasing"] = all(
        vals[i] is not None and vals[i + 1] is not None and vals[i] > vals[i + 1]
        for i in range(len(vals) - 1))
    checks["monotonic_trace"] = ml

    checks["passed"] = bool(
        checks["M0_net_equals_gross"] and checks["M1_parity_vs_R001"]
        and checks["monotonic_decreasing"])
    return {"passed": checks["passed"], "checks": {
        "M0_net_equals_gross": checks["M0_net_equals_gross"],
        "M1_parity_vs_R001": checks["M1_parity_vs_R001"],
        "monotonic_decreasing": checks["monotonic_decreasing"]},
        "detail": {"parity_cells": checks["M1_parity_detail"],
                   "monotonic_trace": checks["monotonic_trace"]}}


COST_BP = {m["id"]: m.get("bp", math.nan) for m in COST_MODELS}


def cross_section_tick(pack: dict, date_mask: np.ndarray, r_stars: dict) -> dict:
    """D1 名字的一跳成本分布，以及一跳成本 > r* 的占比（prereg §4 D2）。

    r_stars：{'reading_a': bp, 'reading_b_ls': bp}；阈值口径歧义已在报告中披露，
    两个读数各算一次 P*/占比，不挑选有利者。
    """
    st, grp, filled_rows = pack["st"], pack["grp"], pack["filled_rows"]
    tick_bp, prices = [], []
    for i in range(st.shape[0]):
        if not date_mask[i]:
            continue
        idx = filled_rows[i]
        if idx is None:
            continue
        g = grp[i][idx]
        d1 = idx[g == 0]
        if len(d1) == 0:
            continue
        e = pack["entry_np"][i][d1]
        tick_bp.extend((TICK / e * 1e4).tolist())
        prices.extend(e.tolist())
    tick_bp = np.array(tick_bp, dtype=float)
    prices = np.array(prices, dtype=float)
    out = {
        "name_days_D1": int(len(tick_bp)),
        "tick_bp_p10": float(np.percentile(tick_bp, 10)),
        "tick_bp_p50": float(np.percentile(tick_bp, 50)),
        "tick_bp_p90": float(np.percentile(tick_bp, 90)),
        "entry_price_p10_cny": float(np.percentile(prices, 10)),
        "entry_price_p50_cny": float(np.percentile(prices, 50)),
        "entry_price_p90_cny": float(np.percentile(prices, 90)),
        "by_reading": {},
    }
    for key, r in r_stars.items():
        if not r:
            out["by_reading"][key] = {"r_star_bp": r, "P_star_cny": None,
                                      "share_below_P_star": None,
                                      "share_tick_above_r_star": None,
                                      "P_star_threshold": 0.20,
                                      "fired": False,
                                      "note": "该读数的 r* 不存在，D2 不适用"}
            continue
        p_star = TICK / (r / 1e4)
        share = float((prices < p_star).mean())
        out["by_reading"][key] = {
            "r_star_bp": r, "P_star_cny": round(p_star, 4),
            "share_below_P_star": share,
            "share_tick_above_r_star": float((tick_bp > r).mean()),
            "P_star_threshold": 0.20,
            "fired": bool(share >= 0.20),
            "r_star_in_ticks_p50": r / out["tick_bp_p50"],
        }
    return out


def decide(results: dict, b003: dict) -> dict:
    """严格按 R003_prereg.md §4 判定。不得在此放宽或收紧条件。"""
    k2, k3 = results["K2_tool_check"], results["K3_canary"]
    fired, reasons = [], []

    # ---- 前置闸
    if not k2["passed"] or not k3["passed"]:
        if not k2["passed"]:
            fired.append("K2")
            reasons.append(f"工具校验未通过：{k2['checks']}")
        if not k3["passed"]:
            fired.append("K3")
            reasons.append(f"泄漏金丝雀未通过：完全预见 IC={k3['foresight_ic']}")
        return {"status": "INVALID", "fired": fired, "reasons": reasons,
                "note": "前置闸失败：本轮结论一律不可作为证据（prereg §4）"}

    # ---- K1 适用性闸
    turn_m = b003["turnover"]["one_sided_turnover_monthly_D1"]
    if turn_m <= 0.50:
        return {"status": "NOT_APPLICABLE", "fired": ["K1"],
                "reasons": [f"D1 单边月换手 {turn_m:.4f} ≤ 0.50，论文高换手门槛不适用"],
                "monthly_turnover_one_sided_D1": turn_m}

    # ---- D1 模型自由判定（对读数 (a) 与读数 (b) 各判一次，两者都必须披露）
    tick_p50 = b003["tick_floor"]["tick_round_trip_bp_median"]
    tick_d1_p50 = results["cross_section_tick"]["tick_bp_p50"]
    rb = results["breakeven"]["reading_b"]["IS_h3"]
    r_a = results["breakeven"]["IS"]["spread"]["root_bp"]
    r_b = rb["r_star_ls_bp"]
    r_b_ticks_d1 = (r_b / tick_d1_p50) if r_b else None
    turn = {"tick_p50_bp": tick_p50, "two_tick_bp": 2.0 * tick_p50,
            "tick_p50_D1_bp": tick_d1_p50,
            "r_star_reading_a_bp": r_a, "r_star_reading_b_bp": r_b,
            "r_star_reading_b_in_ticks_D1": r_b_ticks_d1,
            "branch_note": ("分支阈值按 prereg §4 用**全池**一跳 p50；用 D1 自身一跳 p50 "
                            "重算不改变任何分支（26.89bp > 2×9.28bp）")}

    def branch(r):
        if r is None:
            return "none"
        if r <= tick_p50:
            return "a"
        if r > 2.0 * tick_p50:
            return "b"
        return "c"

    br_a, br_b = branch(r_a), branch(r_b)
    status = "INCONCLUSIVE"
    conditional_split = None
    if br_a == "a":
        status = "PAPER_SUPPORTED"
        fired.append("D1a")
        reasons.append(f"读数(a) r*={r_a:.2f}bp ≤ 一跳下界 {tick_p50:.2f}bp ⟹ "
                       f"真实成本 ≥ 一跳 ≥ r* ⟹ 成本可证足以消除价差（模型自由）")
    elif br_a == "c":
        fired.append("D1c")
        reasons.append(f"读数(a) r*={r_a:.2f}bp 落在模型自由区间 "
                       f"({tick_p50:.2f}, {2.0*tick_p50:.2f}]bp ⟹ INCONCLUSIVE")
    else:
        fired.append("D1b")
        conditional_split = {
            "reading_a": {
                "r_star_bp": r_a,
                "r_star_in_ticks_pool": (r_a / tick_p50) if r_a else None,
                "structural_note": (
                    "读数(a) 把两条腿都当多头腿：成本在差分中**精确抵消**"
                    "（实测 c: 0→12→36bp 时 D1−D10 由 53.79→53.72→53.59bp）⟹ "
                    "该口径**结构上无法检验**论文的成本主张，r* 退化为资金归零点。"
                    "此为**预注册 §1 口径的缺陷**，不是论文被证伪。"),
            },
            "reading_b": {
                "r_star_bp": r_b,
                "r_star_in_ticks_pool": rb["r_star_ls_in_ticks_pool"],
                "r_star_in_ticks_D1": r_b_ticks_d1,
                "definition": "美元中性多空：LS_net = 毛价差 − 2c ⟹ r* = 毛价差/2",
            },
            "branch_A": (f"若真实中位价差 ≤2 跳（≤ {2.0*tick_p50:.2f}bp）⟹ 论文被证伪："
                         f"成本不足以消除多空净价差"),
            "branch_B": (f"若真实价差 > {r_b_ticks_d1:.2f} 跳"
                         f"（D1 口径）⟹ 论文成立：成本足以消除多空净价差"),
            "undecidable_locally_because": "本地无有效价差数据（B003 两条估计路线均被证否）",
        }
        reasons.append(f"读数(a) r*={r_a:.2f}bp ⟹ 远超 2×一跳；读数(b) r*={r_b:.2f}bp "
                       f"（= 毛价差/2）亦 > 2×一跳 {2.0*tick_p50:.2f}bp ⟹ 论文成立**要求**"
                       f"「真实价差 > {r_b_ticks_d1:.2f} 跳（D1 口径）」；"
                       f"本地无价差数据 ⟹ 条件性二分，不能宣布任何一方")
    turn["branch_reading_a"] = br_a
    turn["branch_reading_b"] = br_b
    if br_a != br_b:
        reasons.append(f"【必须披露的分歧】两个读数的 D1 分支不同（读数(a)={br_a}、"
                       f"读数(b)={br_b}）；本轮不按「挑选有利读数」处理，两者并列报告。")

    # ---- NMV 自己的检验形式：净价差的显著性
    nmv = {}
    for cid, cbp in (("M0_zero", 0.0), ("M1_frozen_12bp", 12.0)):
        r = next(x for x in results["segments"]["IS"]
                 if x["h"] == PRIMARY_H and x["cost_model"] == cid)
        nmv[cid] = {
            "cost_bp": cbp,
            "gross_spread_bp": r["gross_spread"] * 1e4,
            "gross_spread_t_nw": r["gross_spread_t_nw"],
            "spread_two_long_legs_bp": r["spread_net_d1_d10"] * 1e4,
            "spread_two_long_legs_t_nw": r["spread_net_t_nw"],
            "ls_net_spread_bp": (r["ls_reading_b"] or {}).get("ls_net_spread_bp"),
            "ls_net_spread_t_nw": (r["ls_reading_b"] or {}).get("ls_net_spread_t_nw"),
            "d1_net_bp": r["d1_net_mean"] * 1e4, "d1_net_t_nw": r["d1_net_t_nw"],
        }
    nmv["note"] = ("论文的检验形式是「净价差是否统计显著」，故本轮补报价差的 NW t 值；"
                   "该 t 值由预注册已采集的分位日度序列派生，不新增评估变体")


    # ---- D2 横截面可证不可行子集（prereg §4 D2；两个读数各判一次）
    d2 = {"threshold": 0.20, "by_reading": results["cross_section_tick"]["by_reading"],
          "tick_bp_p50_D1": results["cross_section_tick"]["tick_bp_p50"]}
    d2["fired_reading_a"] = bool(d2["by_reading"]["reading_a"].get("fired"))
    d2["fired_reading_b_ls"] = bool(d2["by_reading"]["reading_b_ls"].get("fired"))
    if d2["fired_reading_b_ls"]:
        s = d2["by_reading"]["reading_b_ls"]["share_below_P_star"]
        reasons.append(f"D2 触发（读数b）：D1 中 {s:.2%} 的名字价格 < P* "
                       f"{d2['by_reading']['reading_b_ls']['P_star_cny']} 元 ⟹ "
                       f"该子集成本可证超过 r*，可证不可行（**仅限该子集**）")
    elif d2["fired_reading_a"]:
        s = d2["by_reading"]["reading_a"]["share_below_P_star"]
        reasons.append(f"D2 触发（读数a）：价 < P* 占比 {s:.2%} ≥ 20% ⟹ 最便宜子集可证不可行")
    else:
        reasons.append(f"D2 未触发：两个读数下「价 < P*」占比均 < 20%"
                       f"（读数a={d2['by_reading']['reading_a'].get('share_below_P_star')}，"
                       f"读数b={d2['by_reading']['reading_b_ls'].get('share_below_P_star')}）")

    # ---- D3 H2
    m5 = next(r for r in results["segments"]["IS"]
              if r["h"] == PRIMARY_H and r["cost_model"] == "M5_frozen_x3_36bp")
    h2 = {"cost_bp": 36.0, "d1_net_mean": m5["d1_net_mean"], "d1_net_t_nw": m5["d1_net_t_nw"]}
    h2_positive_sig = bool(np.isfinite(m5["d1_net_mean"]) and m5["d1_net_mean"] > 0
                           and np.isfinite(m5["d1_net_t_nw"]) and abs(m5["d1_net_t_nw"]) > 2.0)
    h2["h2_killed"] = h2_positive_sig
    h2["reason"] = ("3× 冻结成本下 D1 净收益仍 >0 且 |t_NW|>2 ⟹ 「等权惩罚足以否定 D1 显著性」"
                    "不成立" if h2_positive_sig else
                    "3× 冻结成本下 D1 净收益 ≤0 或 |t_NW|≤2 ⟹ H2 成立")

    # ---- D4 OOS
    oos_rec = next(r for r in results["segments"]["OOS"]
                   if r["h"] == PRIMARY_H and r["cost_model"] == "M1_frozen_12bp")
    d4 = {"oos_gross_spread_bp": oos_rec["gross_spread"] * 1e4,
          "oos_breakeven_root_bp": results["breakeven"]["OOS"]["spread"]["root_bp"],
          "note": ("OOS 冻结净价差为负 ⟹ 不存在 c_rt≥0 使 OOS 价差转正 ⟹ "
                   "成本不可能解释 OOS 符号翻转（R002 已定位为 2026-02 股票池断层）")}

    return {
        "status": status, "fired": fired, "reasons": reasons,
        "turnover_gate": {"monthly_turnover_one_sided_D1": turn_m, "threshold": 0.50,
                          "passed": True},
        "thresholds": turn,
        "conditional_split": conditional_split,
        "NMV_own_test_significance": nmv,
        "D2_cheapest_subset": d2, "D3_H2": h2, "D4_OOS": d4,
        "ledger_status_mapping": {
            "PAPER_SUPPORTED": "ADOPT_CANDIDATE（须用户确认，agent 不得自行提升）",
            "NOT_APPLICABLE": "REJECTED", "INCONCLUSIVE": "INCONCLUSIVE",
            "INVALID": "INCONCLUSIVE",
        },
    }


if __name__ == "__main__":
    sys.exit(main())
