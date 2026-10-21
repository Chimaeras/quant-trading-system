#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R001 实验：A 股「过去收益 → 未来收益」反转假设的本地复现与证伪。

对应论文：LSY2019《Size and Value in China》(NBER WP 24458 / JFE 2019)
立项预注册：research/paper_loop/rounds/R001_prereg.md（**本脚本在其之后落盘**）
实验编号：`R001_reversal`

假设（H1）：过去 N 日收益负向预测未来净收益；过去输家分位在扣费后仍有长多价值。
默认立场：**尝试杀死它**。杀死条件见 prereg 第 9 节，不得事后放宽。

设计要点（全部来自 prereg，不得在此偏离）：
- 数据：本地 MySQL quant_db.market_data（前复权），面板 2024-01-02 → 2026-09-07
- 信号：只用 trade_date ≤ signal_date 的数据（向量化 shift，结构上禁止前视）
- 入场：signal_date 下一交易日开盘；退出：入场后第 h 个交易日收盘，h ∈ {2,3}
- 成本：买万三 / 卖万五 / 单边滑点万二（复用 ResearchConvention 数值，不硬编码新口径）
- 成交状态：NOT_BUYABLE / SUSPENDED / NOT_SELLABLE / DELISTED，**不删除样本**
- 统计：日度截面 Spearman rank IC + Newey-West HAC(lam) t 值 + 十分位净收益
- 控制：泄漏金丝雀（entry 日开盘信息）+ 完全预见金丝雀（管线健全性硬校验）

只读：本脚本只执行 SELECT，不建表、不写库、不改任何生产文件。

事后修改登记（`D-R010-05`，用户裁定 C「改实现」，2026-09-15）：
  本脚本原按**加法式**口径算净收益（出场因子缺卖出佣金 `−c`），比生产实现（两级乘性）**偏乐观**
  （主项 ≈ `3.00 bp of E`）。现改为与生产实现同构的**两级乘性**口径。按预注册 §10.4：
  * 修改前判定 / 修改后判定：两者均为 `INCONCLUSIVE`（该判定不靠净收益卡边界得出，故**判定词未变**，
    此处不自我裁定，只登记事实与数值）；
  * 冻结产物**不覆盖**：重跑须设 `R001_OUT_DIR` 指向新目录；本目录的 `result.json` / `run.log` /
    `config.json` 保持历史原样（历史数值仍按加法式口径，两者以本文件的登记 + 报告的勘误段区分）；
  * 检索面未缩小：加法式读数由报告勘误逐项并列保留（不删、不改写）。
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
LABEL_HORIZONS = (2, 3)  # ResearchConvention.LABEL_HORIZONS（T+2 / T+3 成交口径）
PRIMARY_N = 20           # 预注册主配置
PRIMARY_H = 3            # 预注册主配置
GRID_N = (1, 5, 20)      # 预注册主网格（含 N=1，用于 K5 现有因子重复判定）
SENS_N = (16, 24)        # ±20% 敏感性
MIN_NAMES_PER_DATE = 30  # 单日截面最少有效样本
RANDOM_SEED = 20260914   # 预注册声明；本实验无随机过程，仅用于记录

IS_A = ("2024-01-02", "2024-12-31")
IS_B = ("2025-01-01", "2025-12-31")
OOS = ("2026-01-01", "2026-09-07")
RUN_OOS = os.environ.get("R001_RUN_OOS", "1") == "1"

LIMIT_EPS = 0.001        # 涨跌停判定容差（前复权价的小数舍入）

DB = {
    "host": os.environ.get("QUANT_DB_HOST", "localhost"),
    "port": int(os.environ.get("QUANT_DB_PORT", "3306")),
    "user": os.environ.get("QUANT_DB_USER", "root"),
    "password": os.environ.get("QUANT_DB_PASSWORD", os.environ.get("DB_PASSWORD", "")),
    "database": os.environ.get("QUANT_DB_NAME", "quant_db"),
    "charset": "utf8mb4",
}

HERE = os.path.dirname(os.path.abspath(__file__))
# 输出目录：默认即冻结产物目录（历史行为不变）；重跑（如 D-R010-05 口径改写）必须显式设
# R001_OUT_DIR 指向新目录，以免覆盖冻结的 result.json / run.log / config.json（预注册 §10.4）。
OUT_DIR = os.environ.get("R001_OUT_DIR", HERE)
# 重跑目录由本脚本自建：否则 log() 在写 run.log 时才失败，且失败发生在「实验开始」之后，
# 会留下一个只有半行日志的目录，看起来像跑过但没跑完。
os.makedirs(OUT_DIR, exist_ok=True)


def log(msg: str) -> None:
    line = f"[{datetime.now().strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(os.path.join(OUT_DIR, "run.log"), "a", encoding="utf-8") as fh:
        fh.write(line + "\n")


# ----------------------------------------------------------------------------
# 1. 上证/深证/北交所涨跌幅规则（镜像 com.quant.service.backtest.TradingLimitRules）
#    注意：ST 5% 无法实现——本地无历史名称，stock_info 为当前快照（非 PIT），
#    用当前 ST 状态过滤历史会造成前视。已在 prereg 第 5 节披露。
# ----------------------------------------------------------------------------
def limit_pct(stock_code: str) -> float:
    code = str(stock_code).split(".")[0].strip()
    if code.startswith(("688", "689")):          # 科创板
        return 0.20
    if code.startswith(("300", "301", "302")):   # 创业板
        return 0.20
    if code.startswith(("8", "4", "920")):       # 北交所
        return 0.30
    return 0.10                                   # 主板


# ----------------------------------------------------------------------------
# 2. 数据加载（只读）
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

    # 2.1 重复主键：保留 id 最大的一行（SQL 已按 trade_date, stock_code, id 升序）
    before = len(raw)
    raw = raw.drop_duplicates(subset=["stock_code", "trade_date"], keep="last")
    meta["duplicate_rows_dropped"] = int(before - len(raw))

    # 2.2 非正价/无量行：从可交易池剔除并计数（不删除记录意义上的样本，
    #     这些行本身无法定价，属于数据缺陷；计数在此披露）
    bad = (
        (raw["close_price"].isna()) | (raw["close_price"] <= 0)
        | (raw["open_price"].isna()) | (raw["open_price"] <= 0)
        | (raw["pre_close"].isna()) | (raw["pre_close"] <= 0)
    )
    meta["rows_nonpositive_price_dropped"] = int(bad.sum())
    raw = raw[~bad]

    meta["amount_median"] = float(raw["amount"].median())
    meta["amount_p90"] = float(raw["amount"].quantile(0.9))
    meta["distinct_codes"] = int(raw["stock_code"].nunique())
    meta["distinct_dates"] = int(raw["trade_date"].nunique())

    # 2.3 面板化（index=交易日，columns=股票）
    p: dict[str, pd.DataFrame] = {}
    for name, col in (
        ("open", "open_price"), ("close", "close_price"), ("pre", "pre_close"),
        ("vol", "volume"), ("amt", "amount"), ("chg", "change_pct"),
    ):
        p[name] = raw.pivot(index="trade_date", columns="stock_code", values=col).sort_index()
    return p, meta


# ----------------------------------------------------------------------------
# 3. 统计工具
# ----------------------------------------------------------------------------
def spearman_ic(a: np.ndarray, b: np.ndarray) -> float:
    """Spearman rank 相关系数（手工实现，避免额外依赖）。"""
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
    """返回 (mean, t_nw, t_naive, se_nw)。Bartlett 核。"""
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
    se_naive = math.sqrt(g0 / t)
    return xbar, xbar / se_nw, xbar / se_naive, se_nw


def limit_up(pre: pd.DataFrame, pct) -> pd.DataFrame:
    """pre_close × (1 + limit_pct)；pct 为标量或按股票对齐的 Series。"""
    return pre.mul(pct + 1.0, axis=1) if hasattr(pct, "index") else pre * (1.0 + pct)


# ----------------------------------------------------------------------------
# 4. 单次配置评估
# ----------------------------------------------------------------------------
def evaluate(p, meta, n: int, h: int, cfg_name: str, signal=None,
             universe_mask=None, stride: int = 1, extra_leak: str | None = None):
    """在给定 (N, h) 上计算 IC / 分位 / 漏斗。

    signal: 自定义信号面板（用于金丝雀）；None 时用 close/close.shift(N)-1。
    extra_leak: 'entry_open' → 信号改用 entry 日开盘（故意前视，金丝雀）。
                'foresight' → 信号 = 真实未来净收益（管线健全性硬校验）。
    """
    close, openp, pre, vol, amt = p["close"], p["open"], p["pre"], p["vol"], p["amt"]

    if extra_leak == "entry_open":
        # 使用 signal_date 次日开盘（决策时不可知）→ 故意泄漏 1 天
        sig = openp.shift(-1) / close - 1.0
    elif extra_leak == "foresight":
        entry_ = openp.shift(-1)
        exit_ = close.shift(-(1 + h))
        sig = exit_ / entry_ - 1.0          # 完全预见（仅用于校验统计管线）
    elif signal is not None:
        sig = signal
    else:
        sig = close / close.shift(n) - 1.0  # 只用 ≤ signal_date 的收盘价

    entry = openp.shift(-1)                 # 次日开盘入场
    exit_ = close.shift(-(1 + h))           # 入场后第 h 个交易日收盘退出
    pre_entry = pre.shift(-1)               # entry 日 pre_close（用于涨跌停价）
    pre_exit = pre.shift(-(1 + h))          # exit 日 pre_close

    # ---- 成交状态（int8 编码；不删除样本；只在 FILLED 上统计收益，
    #      与生产 ScoringBacktestService 的 metricScope=FILLED_SAMPLE 一致）
    code_pct = pd.Series({c: limit_pct(c) for c in close.columns}, dtype="float64")
    lu_entry = pre_entry.mul(code_pct + 1.0, axis=1)     # entry 日涨停价
    ld_exit = pre_exit.mul(1.0 - code_pct, axis=1)       # exit 日跌停价

    suspended = (vol.shift(-1) <= 0) | (vol.shift(-(1 + h)) <= 0) | (vol <= 0)
    not_buyable = entry >= lu_entry * (1.0 - LIMIT_EPS)
    not_sellable = exit_ <= ld_exit * (1.0 + LIMIT_EPS)

    valid = sig.notna() & entry.notna() & exit_.notna() & entry.gt(0) & exit_.gt(0)
    if universe_mask is not None:
        # 重新对齐到完整面板：未覆盖的股票视为不可用（fill_value=False），
        # 避免 pandas 对缺列做对齐时把 bool 提升为 object
        um = universe_mask.reindex(index=close.index, columns=close.columns,
                                  fill_value=False).fillna(False).astype(bool)
        valid = valid & um

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

    gross = (exit_ / entry - 1.0)
    # 成本口径：**两级乘性**（与 com.quant.research.ResearchConvention 实现同构；D-R010-05 裁定 C）
    #   入场 entry×(1+SLIPPAGE)×(1+COMMISSION)；出场 exit×(1−SLIPPAGE)×(1−COMMISSION−SELL_TAX)
    #   修改前为 `exit×(1−SELL_TAX−SLIPPAGE)/(entry×(1+COMMISSION+SLIPPAGE))−1`（出场因子缺卖出佣金，
    #   偏乐观 ≈3.00 bp of E）。表达式结构须与 `run_cost_breakeven.py::apply_cost` 在 s=1.0 时逐位相同。
    net = (exit_ * (1.0 - SLIPPAGE) * (1.0 - COMMISSION - SELL_TAX)) \
        / (entry * (1.0 + SLIPPAGE) * (1.0 + COMMISSION)) - 1.0

    # ---- 分日截面
    dates = close.index
    ic_list, ic_dates = [], []
    dec_sum = np.zeros(10)
    dec_cnt = np.zeros(10)
    dec_gross_sum = np.zeros(10)
    univ_sum = univ_cnt = 0.0
    funnel = {"signal_days": 0, "signal_count": 0, "buyable_count": 0, "filled_count": 0,
              "NOT_BUYABLE": 0, "SUSPENDED": 0, "NOT_SELLABLE": 0, "DELISTED": 0}

    sig_np, net_np, gross_np = sig.to_numpy(), net.to_numpy(), gross.to_numpy()

    for i in range(0, len(dates), stride):
        row = st[i]
        f = row == FILLED
        funnel["NOT_BUYABLE"] += int((row == C_NB).sum())
        funnel["SUSPENDED"] += int((row == C_SUSP).sum())
        funnel["NOT_SELLABLE"] += int((row == C_NS).sum())
        n_sig = int((row != INVALID).sum())
        if n_sig:
            funnel["signal_days"] += 1
            funnel["signal_count"] += n_sig
            funnel["buyable_count"] += n_sig - int((row == C_NB).sum())
        n_f = int(f.sum())
        funnel["filled_count"] += n_f
        if n_f < MIN_NAMES_PER_DATE:
            continue

        s = sig_np[i][f]
        r = net_np[i][f]
        g = gross_np[i][f]
        m = np.isfinite(s) & np.isfinite(r) & np.isfinite(g)
        if not m.all():
            s, r, g = s[m], r[m], g[m]
            if len(s) < MIN_NAMES_PER_DATE:
                continue

        ic = spearman_ic(s, r)
        if np.isfinite(ic):
            ic_list.append(ic)
            ic_dates.append(dates[i])

        univ_sum += float(r.mean()); univ_cnt += 1

        order = pd.Series(s).rank(method="first").to_numpy()
        grp = np.minimum((order - 1) * 10 // len(s), 9).astype(int)
        for k in range(10):
            mk = grp == k
            if mk.any():
                dec_sum[k] += float(r[mk].mean())
                dec_gross_sum[k] += float(g[mk].mean())
                dec_cnt[k] += 1

    ic_arr = np.array(ic_list, dtype=float)
    lag = n + h + 2
    mean_ic, t_nw, t_naive, se_nw = newey_west_t(ic_arr, lag)
    deciles = [
        {"decile": k + 1,
         "net_mean": (dec_sum[k] / dec_cnt[k]) if dec_cnt[k] else math.nan,
         "gross_mean": (dec_gross_sum[k] / dec_cnt[k]) if dec_cnt[k] else math.nan,
         "days": int(dec_cnt[k])}
        for k in range(10)
    ]
    d1, d10 = deciles[0]["net_mean"], deciles[9]["net_mean"]
    g1, g10 = deciles[0]["gross_mean"], deciles[9]["gross_mean"]

    out = {
        "config": cfg_name, "N": n, "h": h, "stride": stride,
        "ic_mean": mean_ic, "ic_t_nw": t_nw, "ic_t_naive": t_naive,
        "ic_lag": lag, "ic_days": len(ic_arr),
        "ic_positive_rate": float((ic_arr > 0).mean()) if len(ic_arr) else math.nan,
        "ic_ir": (mean_ic / float(ic_arr.std(ddof=1))) if len(ic_arr) > 1 and ic_arr.std(ddof=1) > 0 else math.nan,
        "deciles": deciles,
        "spread_net_d1_d10": (d1 - d10) if np.isfinite(d1) and np.isfinite(d10) else math.nan,
        "spread_gross_d1_d10": (g1 - g10) if np.isfinite(g1) and np.isfinite(g10) else math.nan,
        "d1_net_mean": d1, "d10_net_mean": d10,
        "universe_net_mean": (univ_sum / univ_cnt) if univ_cnt else math.nan,
        "universe_days": int(univ_cnt),
        "funnel": funnel,
        "first_ic_date": str(ic_dates[0].date()) if ic_dates else None,
        "last_ic_date": str(ic_dates[-1].date()) if ic_dates else None,
    }
    return out


# ----------------------------------------------------------------------------
# 5. 主流程
# ----------------------------------------------------------------------------
def main() -> int:
    if os.path.exists(os.path.join(OUT_DIR, "run.log")):
        os.remove(os.path.join(OUT_DIR, "run.log"))
    log("R001 实验开始（只读）")
    log(f"预注册：rounds/R001_prereg.md ｜ 主配置 N={PRIMARY_N}, h={PRIMARY_H}")

    p, meta = load_panel()
    log(f"加载完成：{meta['rows_loaded_since_2023_11_01']} 行，"
        f"去重丢弃 {meta['duplicate_rows_dropped']} 行，"
        f"非正价剔除 {meta['rows_nonpositive_price_dropped']} 行")

    dates = p["close"].index
    seg_mask = {
        "IS_A": (dates >= IS_A[0]) & (dates <= IS_A[1]),
        "IS_B": (dates >= IS_B[0]) & (dates <= IS_B[1]),
        "IS": (dates >= IS_A[0]) & (dates <= IS_B[1]),
        "OOS": (dates >= OOS[0]) & (dates <= OOS[1]),
    }
    meta["segment_trading_days"] = {k: int(v.sum()) for k, v in seg_mask.items()}
    log(f"分段交易日数：{meta['segment_trading_days']}")

    def bool_frame(flags) -> pd.DataFrame:
        """把按交易日的布尔序列广播成与价格面板同形的布尔 DataFrame。"""
        arr = np.broadcast_to(
            np.asarray(flags, dtype=bool)[:, None],
            (len(dates), len(p["close"].columns)))
        return pd.DataFrame(arr, index=dates, columns=p["close"].columns)

    def mask_frame(seg: str) -> pd.DataFrame:
        return bool_frame(seg_mask[seg])

    results: dict = {"meta": meta, "segments": {}}
    grid_report = {}

    # ---- 5.1 主网格（IS）+ OOS
    for seg in ("IS_A", "IS_B", "IS"):
        results["segments"][seg] = []
        um = mask_frame(seg)
        for n in GRID_N:
            for h in LABEL_HORIZONS:
                r = evaluate(p, meta, n, h, f"grid_N{n}_h{h}", universe_mask=um)
                results["segments"][seg].append(r)
                log(f"  [{seg}] N={n} h={h} IC={r['ic_mean']:.4f} tNW={r['ic_t_nw']:.2f} "
                    f"days={r['ic_days']} D1-D10net={r['spread_net_d1_d10']:.5f} "
                    f"D1net={r['d1_net_mean']:.5f} univnet={r['universe_net_mean']:.5f}")

    primary_is = next(r for r in results["segments"]["IS"]
                      if r["N"] == PRIMARY_N and r["h"] == PRIMARY_H)
    grid_report["primary_IS"] = primary_is

    if RUN_OOS:
        results["segments"]["OOS"] = []
        um = mask_frame("OOS")
        for n in GRID_N:
            for h in LABEL_HORIZONS:
                r = evaluate(p, meta, n, h, f"grid_N{n}_h{h}", universe_mask=um)
                results["segments"]["OOS"].append(r)
                log(f"  [OOS] N={n} h={h} IC={r['ic_mean']:.4f} tNW={r['ic_t_nw']:.2f} "
                    f"days={r['ic_days']} D1-D10net={r['spread_net_d1_d10']:.5f}")
        grid_report["primary_OOS"] = next(
            r for r in results["segments"]["OOS"]
            if r["N"] == PRIMARY_N and r["h"] == PRIMARY_H)

    # ---- 5.2 去重叠子样本（K8）
    um_is = mask_frame("IS")
    results["decimated_IS"] = evaluate(
        p, meta, PRIMARY_N, PRIMARY_H, f"decimated_N{PRIMARY_N}_h{PRIMARY_H}",
        universe_mask=um_is, stride=PRIMARY_N + PRIMARY_H + 2)
    log(f"  [IS 去重叠 stride={PRIMARY_N + PRIMARY_H + 2}] IC="
        f"{results['decimated_IS']['ic_mean']:.4f} tNW={results['decimated_IS']['ic_t_nw']:.2f} "
        f"days={results['decimated_IS']['ic_days']}")

    # ---- 5.3 ±20% 敏感性（K6）
    results["sensitivity_IS"] = []
    for n in SENS_N:
        for h in LABEL_HORIZONS:
            r = evaluate(p, meta, n, h, f"sens_N{n}_h{h}", universe_mask=um_is)
            results["sensitivity_IS"].append(r)
            log(f"  [IS sens] N={n} h={h} IC={r['ic_mean']:.4f} tNW={r['ic_t_nw']:.2f} days={r['ic_days']}")

    # ---- 5.4 稳健性：板块 / 年度 / 流动性相对闸
    code_map = pd.Series({c: str(c) for c in p["close"].columns})
    board = code_map.map(lambda c: (
        "STAR" if c.split(".")[0].startswith(("688", "689")) else
        "CHINEXT" if c.split(".")[0].startswith(("300", "301", "302")) else
        "BSE" if c.split(".")[0].startswith(("8", "4", "920")) else "MAIN"))
    results["robustness"] = []
    for b in ("MAIN", "CHINEXT", "STAR", "BSE"):
        cols = board[board == b].index
        if len(cols) < MIN_NAMES_PER_DATE:
            continue
        bm = mask_frame("IS")[cols]
        r = evaluate(p, meta, PRIMARY_N, PRIMARY_H, f"board_{b}", universe_mask=bm)
        results["robustness"].append(r)
        log(f"  [IS board={b}] IC={r['ic_mean']:.4f} tNW={r['ic_t_nw']:.2f} "
            f"days={r['ic_days']} n_codes={len(cols)}")

    for y, (a, z) in (("2024", IS_A), ("2025", IS_B), ("2026", OOS)):
        ym = bool_frame((dates >= a) & (dates <= z))
        r = evaluate(p, meta, PRIMARY_N, PRIMARY_H, f"year_{y}", universe_mask=ym)
        results["robustness"].append(r)
        log(f"  [year={y}] IC={r['ic_mean']:.4f} tNW={r['ic_t_nw']:.2f} days={r['ic_days']}")

    # 流动性相对闸：signal 当日 amount ≥ 截面中位数
    amt = p["amt"]
    rel_liq = amt.ge(amt.median(axis=1), axis=0)
    r = evaluate(p, meta, PRIMARY_N, PRIMARY_H, "liq_above_median",
                 universe_mask=(rel_liq & mask_frame("IS")))
    results["robustness"].append(r)
    log(f"  [IS liq>median] IC={r['ic_mean']:.4f} tNW={r['ic_t_nw']:.2f} days={r['ic_days']}")

    # ---- 5.5 控制实验（K7）
    results["controls"] = {}
    results["controls"]["canary_entry_open_leak"] = evaluate(
        p, meta, PRIMARY_N, PRIMARY_H, "canary_entry_open_leak",
        universe_mask=um_is, extra_leak="entry_open")
    log(f"  [控制/泄漏金丝雀] IC={results['controls']['canary_entry_open_leak']['ic_mean']:.4f} "
        f"tNW={results['controls']['canary_entry_open_leak']['ic_t_nw']:.2f}")
    results["controls"]["canary_foresight"] = evaluate(
        p, meta, PRIMARY_N, PRIMARY_H, "canary_foresight",
        universe_mask=um_is, extra_leak="foresight")
    log(f"  [控制/完全预见金丝雀] IC={results['controls']['canary_foresight']['ic_mean']:.6f} "
        f"tNW={results['controls']['canary_foresight']['ic_t_nw']:.2f}")

    # ---- 5.6 变体计数披露
    variants = len(GRID_N) * len(LABEL_HORIZONS) + len(SENS_N) * len(LABEL_HORIZONS)
    results["variant_disclosure"] = {
        "grid_variants": len(GRID_N) * len(LABEL_HORIZONS),
        "sensitivity_variants": len(SENS_N) * len(LABEL_HORIZONS),
        "total_variants_tried": variants,
        "controls": 2, "robustness_runs": len(results["robustness"]),
        "note": "阈值在运行前由 R001_prereg.md 固定，未因结果收紧或放宽",
    }

    # ---- 5.7 判定（严格按 prereg 第 9 节）
    verdict = decide(results, grid_report)
    results["verdict"] = verdict
    log(f"判定：{verdict['status']} ｜ 触发杀死条件：{verdict['killed_by']}")

    cfg = {
        "experiment_id": "R001_reversal", "paper_id": "LSY2019",
        "prereg": "research/paper_loop/rounds/R001_prereg.md",
        "run_time": datetime.now().isoformat(timespec="seconds"),
        "cost": {"commission": COMMISSION, "sell_tax": SELL_TAX, "slippage": SLIPPAGE},
        "entry_assumption": "NEXT_TRADING_DAY_OPEN",
        "exit_assumption": "HOLDING_PERIOD_CLOSE (h in {2,3})",
        "primary": {"N": PRIMARY_N, "h": PRIMARY_H},
        "grid_N": list(GRID_N), "sensitivity_N": list(SENS_N),
        "segments": {"IS_A": IS_A, "IS_B": IS_B, "OOS": OOS},
        "min_names_per_date": MIN_NAMES_PER_DATE, "random_seed": RANDOM_SEED,
        "data_source": "quant_db.market_data (qfq)",
        "run_oos": RUN_OOS,
    }
    with open(os.path.join(OUT_DIR, "config.json"), "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2)

    with open(os.path.join(OUT_DIR, "result.json"), "w", encoding="utf-8") as fh:
        json.dump(results, fh, ensure_ascii=False, indent=2, default=str)
    log("结果写入 result.json / config.json")
    return 0


def decide(results: dict, grid_report: dict) -> dict:
    """严格按 R001_prereg.md 第 9 节判定。不得在此放宽条件。"""
    p_is = grid_report["primary_IS"]
    p_oos = grid_report.get("primary_OOS")
    killed, reasons, status = [], [], ""

    ic, t = p_is["ic_mean"], p_is["ic_t_nw"]
    if not (np.isfinite(t) and t <= -2.0) or not (np.isfinite(ic) and ic < 0):
        killed.append("K1")
        reasons.append(f"IS 主配置 IC={ic:.4f} tNW={t:.2f}，未达 IC<0 且 t≤-2.0")

    spread_g, spread_n = p_is["spread_gross_d1_d10"], p_is["spread_net_d1_d10"]
    if np.isfinite(spread_g) and spread_g > 0 and (not np.isfinite(spread_n) or spread_n <= 0):
        killed.append("K2")
        reasons.append(f"毛价差 {spread_g:.5f} > 0 但净价差 {spread_n:.5f} ≤ 0")

    if not (np.isfinite(p_is["d1_net_mean"]) and p_is["d1_net_mean"] > 0
            and p_is["d1_net_mean"] > p_is["universe_net_mean"]):
        killed.append("K3")
        reasons.append(f"D1 净收益 {p_is['d1_net_mean']:.5f} 未同时 > 0 且 > 全池 "
                       f"{p_is['universe_net_mean']:.5f}")

    if p_oos is not None:
        if np.isfinite(p_oos["ic_mean"]) and p_oos["ic_mean"] >= 0:
            killed.append("K4")
            reasons.append(f"OOS IC={p_oos['ic_mean']:.4f} 符号翻转")
        elif not (np.isfinite(p_oos["ic_t_nw"]) and p_oos["ic_t_nw"] <= -2.0):
            status = "INCONCLUSIVE"
            reasons.append(f"OOS IC={p_oos['ic_mean']:.4f} 符号一致但 tNW="
                           f"{p_oos['ic_t_nw']:.2f} 不显著")

    is_map = {(r["N"], r["h"]): r for r in results["segments"]["IS"]}
    def sig(k):
        r = is_map.get(k)
        return bool(r and np.isfinite(r["ic_t_nw"]) and r["ic_t_nw"] <= -2.0)
    if sig((1, PRIMARY_H)) and not sig((5, PRIMARY_H)) and not sig((PRIMARY_N, PRIMARY_H)):
        killed.append("K5")
        reasons.append("仅 N=1 显著，N=5/20 均不显著 → 与现有 er1 重复")

    sens_ok = sum(
        1 for r in results["sensitivity_IS"]
        if np.isfinite(r["ic_mean"]) and r["ic_mean"] < 0
        and np.isfinite(r["ic_t_nw"]) and r["ic_t_nw"] <= -2.0)
    if sens_ok < 3:
        killed.append("K6")
        reasons.append(f"±20% 敏感性仅 {sens_ok}/4 组复现")

    canary = results["controls"]["canary_entry_open_leak"]
    fore = results["controls"]["canary_foresight"]
    canary_ok = (
        np.isfinite(canary["ic_mean"]) and np.isfinite(p_is["ic_mean"])
        and abs(canary["ic_mean"]) > abs(p_is["ic_mean"])
        and abs(canary["ic_t_nw"] or 0) > abs(p_is["ic_t_nw"] or 0)
    )
    foresight_ok = np.isfinite(fore["ic_mean"]) and fore["ic_mean"] > 0.99
    if not (canary_ok and foresight_ok):
        killed.append("K7")
        reasons.append(f"控制实验未通过：泄漏金丝雀 IC={canary['ic_mean']:.4f} "
                       f"(primary {p_is['ic_mean']:.4f})，完全预见 IC={fore['ic_mean']:.4f}")

    dec = results["decimated_IS"]
    if not (np.isfinite(dec["ic_mean"]) and dec["ic_mean"] < 0):
        killed.append("K8")
        reasons.append(f"去重叠子样本 IC={dec['ic_mean']:.4f} ≥ 0")

    if killed:
        # K7 = 工具有缺陷：本轮结论一律不可作为证据（prereg 第 9 节）
        if "K7" in killed:
            final = "INCONCLUSIVE"
        else:
            final = "REJECTED"
        return {"status": final, "killed_by": killed, "reasons": reasons}
    if status == "INCONCLUSIVE":
        return {"status": "INCONCLUSIVE", "killed_by": [], "reasons": reasons}
    return {"status": "ADOPT_CANDIDATE", "killed_by": [], "reasons": reasons}


if __name__ == "__main__":
    sys.exit(main())
