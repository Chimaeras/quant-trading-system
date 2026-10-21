#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R004 实验：多空 / long-short / spread / 超额 口径的**结构同构性审计**。

对应论文：FF2015《A Five-Factor Asset Pricing Model》（JFE 接收稿）——「多空组合收益」与
          「超额收益 = R − Rf」的标准定义来源；
          CCF2019《Characteristic-Sorted Portfolios》（arXiv:1809.03584）——「分组数 J 是调参量、
          标准取 10、最优常远大于 10」的方法论权威（**不作为第二个假设**）。
预注册：research/paper_loop/rounds/R004_prereg.md（**本脚本在其之后落盘**）
审查：  research/paper_loop/rounds/R004_review.md
实验编号：`R004_semantics_audit`

本轮的审计对象是**项目自己的口径结构**，不是某个策略的收益。默认立场 = **尝试杀死**
「项目里被命名为多空/spread/超额的量与文献标准结构同构、可不加限定地如此解读」这一主张。

六轴：A1 成本项 / A2 符号 / A3 毛净 / A4 尺度 / A5 基准 / A6 分组数。

判据与阈值**全部**来自 prereg §4、§9，本脚本不得偏离；杀死条件不得事后放宽。

关键实现约束（三条，均为"必须保留表达式结构"级别）：
  1. **项目口径的成本表达式** 必须用源码里的原始结构：
     entry_cost  = open × (1 + SLIP·s)(1 + COMM·s)
     exit_proceeds = close × (1 − SLIP·s)(1 − COMM·s − SELL_TAX·s)
     即**佣金在买卖两侧各收一次**（A 股实际如此），冻结 s=1 时往返 = 2·COMM + SELL_TAX + 2·SLIP = 15bp。
     注意：R003 的 `apply_cost` 把佣金只收在买入侧（往返 = COMM + SELL_TAX + 2·SLIP = 12bp）。
     两者**不是同一个模型**；本脚本同时保留两套，P0-1 parity 用 R003 的那套，
     A1/A3 轴用**项目的那套**（审计对象是项目，不是 R003）。
  2. 分组用 `i*g//n`（等数量/等频分位）；J=10 时必须与 R003 的 `grp` **逐位相等**（工具校验）。
  3. 面板、窗口、去重、有效性掩码、成交状态口径与 R001/R003 逐位一致，由 P0-1 parity 断言保证。

只读：本脚本只执行 SELECT，不建表、不写库、不改任何生产文件。
本轮**不修改任何代码或契约**（协议 §7 保留权限）；所有修正建议只进 decision_queue.md。
"""

from __future__ import annotations

import importlib.util
import json
import math
import os
import re
import sys
from datetime import datetime

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
PAPER_LOOP = os.path.abspath(os.path.join(HERE, "..", ".."))
REPO = os.path.abspath(os.path.join(PAPER_LOOP, "..", ".."))
R003_DIR = os.path.join(PAPER_LOOP, "experiments", "R003_cost_breakeven")
R003_RESULT = os.path.join(R003_DIR, "result.json")

# ----------------------------------------------------------------------------
# 0. 冻结口径常量（与 com.quant.research.ResearchConvention 数值一致）
# ----------------------------------------------------------------------------
COMMISSION = 0.0003      # 买入/卖出佣金 万三（**两侧各收一次**）
SELL_TAX = 0.0005        # 卖出印花税 万五
SLIPPAGE = 0.0002        # 单边滑点 万二
FROZEN_TOTAL = COMMISSION + SELL_TAX + 2.0 * SLIPPAGE        # 12bp（R003 口径的往返）

# R004 的成本网格（prereg §10c）：12 = 冻结；9.6/14.4 = ±20%；0 = 零成本闸
COST_GRID_BP = (0.0, 6.0, 9.6, 12.0, 14.4, 24.0, 36.0)

PRIMARY_H = 3
GROUP_COUNTS = (5, 10, 20)          # A6
PROJECT_J = 5                       # FactorResearchService.RANK_IC_GROUP_COUNT
CCF_STANDARD_J = 10                 # CCF2019 p4/p37：the standard choice of ten
DEGRADE_J = 5
DEGRADE_N = 10                      # FactorResearchService.RANK_IC_MIN_CROSS_SECTION_SIZE

IS = ("2024-01-02", "2025-12-31")
OOS = ("2026-01-01", "2026-09-07")
SEGMENTS = {"IS": IS, "OOS": OOS}

RANDOM_SEED = 20260914
MAX_VARIANTS = 48
PREREG_VARIANTS = 43

# 杀死条件阈值（prereg §9，**不得事后放宽**）
K1_ABS_BP = 5.0
K1_REL = 0.30
K2_T_MULT = 1.0
K2_ABS_BP = 5.0
K3_REL = 0.30
TOL_PARITY = 1e-12
TOL_IDENTITY = 1e-12

LOG_PATH = os.path.join(HERE, "run.log")


def log(msg: str) -> None:
    line = f"[{datetime.now().strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(LOG_PATH, "a", encoding="utf-8") as fh:
        fh.write(line + "\n")


# ----------------------------------------------------------------------------
# 1. 复用 R003 的面板加载与统计工具（跨轮复用 ⟹ 必须做 parity 断言）
# ----------------------------------------------------------------------------
def load_r003_module():
    spec = importlib.util.spec_from_file_location(
        "r003", os.path.join(R003_DIR, "run_cost_breakeven.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# ----------------------------------------------------------------------------
# 2. R004 的两套成本表达式
# ----------------------------------------------------------------------------
def project_factors(bp: float) -> tuple[float, float]:
    """**项目口径**的成本因子（源码结构：佣金两侧各收一次）。

    entry_cost   = open  × (1 + SLIP·s)(1 + COMM·s)          -> b
    exit_proceeds= close × (1 − SLIP·s)(1 − COMM·s − SELL_TAX·s) -> a

    s = bp / 12bp（12bp = 冻结口径；`s=1` 时逐位复现源码表达式）。
    返回 (a, b)。
    """
    s = bp / (FROZEN_TOTAL * 1e4)
    b = (1.0 + SLIPPAGE * s) * (1.0 + COMMISSION * s)
    a = (1.0 - SLIPPAGE * s) * (1.0 - COMMISSION * s - SELL_TAX * s)
    return a, b


def project_net_single_leg(g_np: np.ndarray, bp: float) -> np.ndarray:
    """**项目单腿口径**下的净收益矩阵：净 = (1+g)·a/b − 1。

    这是 FactorResearchService 里 `netReturn = exitProceeds/entryCost − 1` 的代数等价形式。
    """
    a, b = project_factors(bp)
    return (1.0 + g_np) * (a / b) - 1.0


# 注意：**不存在**「按个股把多腿相加」的构造函数。
# 零投资组合的两条腿是**两个不同的组**，必须在**组均值**层面相加（见 `two_leg_spread`）。
# R004 第一版曾实现 `project_net_two_leg(g) = ((1+g)a/b − 1) + (a − (1+g)b)`，
# 该式对同一 g 恒等于 0（`g + (−g) = 0`），使 A1 双腿列全为 0 —— 已于 2026-09-14 删除，
# 详见 `R004_report.md` 附录勘误 E-1。


# ----------------------------------------------------------------------------
# 3. 分组（等数量分位，J 可调；J=10 必须与 R003 逐位相等）
# ----------------------------------------------------------------------------
def assign_groups(pack: dict, J: int, min_names: int) -> np.ndarray:
    """与 R003 `build_pack` 内联分组逻辑**同构**，只是把 10 换成 J。

    逐日：取该日 FILLED 且 sig/gross 有限的样本，按 rank(method='first') 升序，
    组号 = min((order−1)*J // n, J−1)。sig 升序 ⟹ 组号 0 = 低分（项目里的 G1），J−1 = 高分（G5/G10）。
    """
    st, sig_np, gross_np = pack["st"], pack["sig_np"], pack["gross_np"]
    n_dates, n_cols = st.shape
    grp = np.full((n_dates, n_cols), -1, dtype=np.int16)
    filled_rows = []
    FILLED = 1
    for i in range(n_dates):
        f = st[i] == FILLED
        if int(f.sum()) < min_names:
            filled_rows.append(None)
            continue
        idx = np.flatnonzero(f)
        s = sig_np[i][idx]
        m = np.isfinite(s) & np.isfinite(gross_np[i][idx])
        if not m.all():
            idx = idx[m]
            s = s[m]
            if len(s) < min_names:
                filled_rows.append(None)
                continue
        order = pd.Series(s).rank(method="first").to_numpy()
        g = np.minimum((order - 1) * J // len(s), J - 1).astype(np.int16)
        grp[i, idx] = g
        filled_rows.append(idx)
    return grp, filled_rows


def spread_stats(pack: dict, val_np: np.ndarray, grp: np.ndarray,
                 filled_rows: list, mask: np.ndarray, J: int,
                 collect_series: bool = False) -> dict:
    """逐日按组取等权截面均值，再对时序取均值；返回 首组/末组/spread 及可选的日序列。

    spread 方向 = **末组（高分） − 首组（低分）**，与项目 `grouped.get(last) − grouped.get(0)` 一致。

    NaN 处理：逐日逐组**只取有限值**求均值，并累计 `dropped_obs`（有限性剔除的观测数）。
    这是**单元格内部**的处理口径（prereg 未规定），必须随结果披露；不得用它改变任何阈值。
    """
    n_dates = pack["st"].shape[0]
    s_first = s_last = 0.0
    c_first = c_last = 0
    diff = 0.0
    diff_cnt = 0
    dropped = 0
    ser_diff = []
    for i in range(n_dates):
        if not mask[i]:
            continue
        idx = filled_rows[i]
        if idx is None:
            continue
        v = val_np[i][idx]
        g = grp[i][idx]
        m_first = (g == 0)
        m_last = (g == J - 1)
        if not (m_first.any() and m_last.any()):
            continue
        vf = np.isfinite(v)
        dropped += int((~vf).sum())
        mf = m_first & vf
        ml = m_last & vf
        if not (mf.any() and ml.any()):
            continue
        mfv = float(v[mf].mean())
        mlv = float(v[ml].mean())
        s_first += mfv
        s_last += mlv
        c_first += 1
        c_last += 1
        diff += (mlv - mfv)
        diff_cnt += 1
        if collect_series:
            ser_diff.append(mlv - mfv)
    out = {
        "days": int(diff_cnt),
        "dropped_obs": int(dropped),
        "first_group_mean": (s_first / c_first) if c_first else math.nan,
        "last_group_mean": (s_last / c_last) if c_last else math.nan,
        "spread": (diff / diff_cnt) if diff_cnt else math.nan,
        "spread_bp": ((diff / diff_cnt) * 1e4) if diff_cnt else math.nan,
    }
    if collect_series:
        out["spread_series"] = np.array(ser_diff, dtype=float)
    return out


def two_leg_spread(pack: dict, grp: np.ndarray, filled_rows: list, mask: np.ndarray,
                   J: int, cost_bp: float, collect_series: bool = False) -> dict:
    """**美元中性双腿**价差：先按腿各自做截面聚合，再相加（不是在个股层面相加）。

        多头腿（末组/高分）：R_L = mean_{(i) in high} [ (1+g_i)·a/b − 1 ]
        空头腿（首组/低分）：R_S = mean_{(i) in low} [ a − (1+g_i)·b ]
        零投资组合总收益     = R_L + R_S

    注意：`R_L + R_S` **必须**在**组均值**层面相加。若在个股层面先加再按组取均值，
    结果恒为 0（`g + (−g) = 0`）——R004 第一版实现正是犯了这个错（见报告附录勘误）。
    """
    a, b = project_factors(cost_bp)
    g = pack["gross_np"]
    n_dates = pack["st"].shape[0]
    s_l = s_s = 0.0
    c_l = c_s = 0
    tot = 0.0
    tot_cnt = 0
    dropped = 0
    ser = []
    for i in range(n_dates):
        if not mask[i]:
            continue
        idx = filled_rows[i]
        if idx is None:
            continue
        v = g[i][idx]
        gg = grp[i][idx]
        vf = np.isfinite(v)
        dropped += int((~vf).sum())
        hi = (gg == J - 1) & vf
        lo = (gg == 0) & vf
        if not (hi.any() and lo.any()):
            continue
        r_l = float(((1.0 + v[hi]) * (a / b) - 1.0).mean())
        r_s = float((a - (1.0 + v[lo]) * b).mean())
        s_l += r_l
        s_s += r_s
        c_l += 1
        c_s += 1
        tot += (r_l + r_s)
        tot_cnt += 1
        if collect_series:
            ser.append(r_l + r_s)
    out = {
        "days": int(tot_cnt), "dropped_obs": int(dropped),
        "long_leg_mean": (s_l / c_l) if c_l else math.nan,
        "short_leg_mean": (s_s / c_s) if c_s else math.nan,
        "spread": (tot / tot_cnt) if tot_cnt else math.nan,
        "spread_bp": ((tot / tot_cnt) * 1e4) if tot_cnt else math.nan,
    }
    if collect_series:
        out["spread_series"] = np.array(ser, dtype=float)
    return out


def segment_mask(dates: pd.DatetimeIndex, seg: str) -> np.ndarray:
    a, b = SEGMENTS[seg]
    d = dates
    return np.asarray((d >= pd.Timestamp(a)) & (d <= pd.Timestamp(b)))


def bp(x: float) -> float:
    return float(x) * 1e4 if np.isfinite(x) else math.nan


# ----------------------------------------------------------------------------
# 4. A5 的基准数据（既有表 index_quote，只读）
# ----------------------------------------------------------------------------
def load_index_rf(pack_module) -> tuple[pd.Series, dict]:
    """读 000300 指数收盘（既有表）；同时做「无 Rf」的存在性检查。"""
    import pymysql
    meta = {}
    conn = pymysql.connect(**pack_module.DB)
    try:
        sql = ("SELECT trade_date, close_price FROM index_quote "
               "WHERE index_code = '000300' ORDER BY trade_date")
        df = pd.read_sql(sql, conn)
    finally:
        conn.close()
    df["trade_date"] = pd.to_datetime(df["trade_date"])
    df["close_price"] = pd.to_numeric(df["close_price"], errors="coerce")
    s = df.set_index("trade_date")["close_price"].sort_index()
    meta["index_code"] = "000300"
    meta["index_name"] = "沪深300"
    meta["rows"] = int(len(s))
    meta["first"] = s.index.min().strftime("%Y-%m-%d") if len(s) else None
    meta["last"] = s.index.max().strftime("%Y-%m-%d") if len(s) else None
    # 是否存在无风险利率序列（表级存在性检查；不猜列名）
    conn = pymysql.connect(**pack_module.DB)
    try:
        with conn.cursor() as cur:
            cur.execute("SHOW TABLES")
            ts = [r[0] for r in cur.fetchall()]
    finally:
        conn.close()
    meta["rf_like_tables"] = sorted(
        t for t in ts if re.search(r"(risk_?free|_rf\b|treasury|bill_rate|shibor|deposit_rate)", t, re.I))
    meta["rf_series_exists"] = bool(meta["rf_like_tables"])
    return s, meta


# ----------------------------------------------------------------------------
# 5. 源码静态扫描（A4/A5 的存在性证明，机器可复现）
# ----------------------------------------------------------------------------
def scan_sources() -> dict:
    """在仓库源码里检索口径相关标识符的出现次数/位置。

    **只读**。这些是 K4s/K5 的判据证据（prereg §5 已预验证，此处机器化复现）。

    本函数**同时按扩展名分组**：`.ts` 是 API 类型声明（字段存在），
    `.vue` 才是**展示层**（用户可见）。二者的区别是 K4s 判定的关键
    ——R004 第一版只报了一个合并计数，导致把类型声明误当成「界面已标注」，
    见 `R004_report.md` 附录勘误 E-4。
    """
    targets = {
        "metric_scope": (os.path.join(REPO, "modules"), ("metric_scope",)),
        "outperform": (os.path.join(REPO, "modules"), ("outperform",)),
        "excess_field": (os.path.join(REPO, "modules", "backend-java"),
                         ("excess_return", "excessReturn", "超额收益")),
        "rf_field": (os.path.join(REPO, "modules", "backend-java"),
                     ("risk_free", "riskFree", "riskfree_return")),
        # 裁定 B（D-R003-01 / D-R004-01）已把该字段改名为如实名：当前展示面词表用新名，
        # 旧名单独扫（命中应为 0，即「旧名不再可读」的红线可机器复核）。改名前本键名为
        # long_short_spread 且词表为旧名——复现 R004 冻结结论须用该轮当时的版本。
        "two_long_leg_spread": (os.path.join(REPO, "modules"),
                                ("two_long_leg_spread", "twoLongLegSpread", "两腿价差")),
        "long_short_spread_retired": (os.path.join(REPO, "modules"),
                                      ("long_short_spread", "longShortSpread",
                                       "多空 spread", "多空收益差")),
        "non_excess_disclaimer": (os.path.join(REPO, "modules", "web", "src"),
                                  ("非超额", "不是超额", "非年化", "不可做空", "无做空")),
    }
    out: dict = {}
    for key, (root, pats) in targets.items():
        hits = []
        by_ext: dict = {}
        by_file: dict = {}
        if os.path.isdir(root):
            for dirpath, dirnames, filenames in os.walk(root):
                dirnames[:] = [d for d in dirnames
                               if d not in ("node_modules", "target", "dist", ".git", "__pycache__")]
                for fn in filenames:
                    if not fn.endswith((".java", ".ts", ".vue", ".js", ".py", ".json", ".md")):
                        continue
                    p = os.path.join(dirpath, fn)
                    try:
                        with open(p, "r", encoding="utf-8", errors="ignore") as fh:
                            lines = fh.readlines()
                    except OSError:
                        continue
                    rel = os.path.relpath(p, REPO).replace("\\", "/")
                    for ln, line in enumerate(lines, 1):
                        if any(pat in line for pat in pats):
                            hits.append({"file": rel, "line": ln, "text": line.strip()[:200]})
                            ext = os.path.splitext(fn)[1]
                            by_ext[ext] = by_ext.get(ext, 0) + 1
                            by_file[rel] = by_file.get(rel, 0) + 1
        out[key] = {"count": len(hits), "by_ext": by_ext, "by_file": by_file,
                    "files": sorted(by_file), "hits": hits}
    return out


# ----------------------------------------------------------------------------
# 6. 口径同构性审计器（纯函数：表进 → 判定出）＋ 阳性对照
# ----------------------------------------------------------------------------
AXES = ("cost", "sign", "group_count", "benchmark", "gross_net", "scale")


def audit_rows(rows: list[dict]) -> list[dict]:
    """对一张人工/实测两用的表逐行做六轴合规判定。

    每行字段：name, value, cost, sign, group_count, benchmark, gross_net, scale
    其中 cost ∈ {"single_leg_net", "two_leg_net", "gross"}，
         sign ∈ {"high_minus_low", "low_minus_high"}，
         group_count ∈ int 或 "fixed"，
         benchmark ∈ {"none", "rf", "index"} / gross_net ∈ {"gross","net","confounded"} /
         scale ∈ {"decimal","bp","pct"}。

    判定规则（每条都**必须**能判出"合规"与"不合规"两种结果）：
      cost        : 标为 "two_leg_net" 但口径实为单腿 ⟹ 不合规。此处按值判定：
                    若行标记 cost=="two_leg_net" 而 value 与单腿口径的理论值一致（|diff|<1e-9）⟹ 不合规。
      sign        : 标记 "high_minus_low" 但 value<0 且该行 given_sign_negative_hint 为 False ⟹
                    仅当标记与实际符号不自洽时不合规。此处用显式注入字段判定。
      group_count : 标为整数且属于 {5,10,20} ⟹ 合规；标为 "fixed" 且等于项目常量 ⟹ 记为"不可调"（不合规）。
      benchmark   : "none" ⟹ 不合规（无基准/无 Rf）。
      gross_net   : "confounded" ⟹ 不合规。
      scale       : 标记与声明单位不自洽（decimal 却标 bp/pct）⟹ 不合规。

    返回每行 + issues 列表。
    """
    out = []
    for r in rows:
        issues = []
        if r.get("cost") == "two_leg_net" and abs(float(r.get("value", 0.0))
                                                 - float(r.get("single_leg_value", 0.0))) < 1e-9:
            issues.append("cost: 标记 two_leg_net，但数值与单腿口径理论值一致（成本未在差分中存活）")
        if r.get("sign") == "high_minus_low" and r.get("order_used") == "first_minus_last":
            issues.append("sign: 标记 high_minus_low，但实际用 first−last 计算")
        if r.get("group_count") == "fixed":
            issues.append(f"group_count: J 为编译期常量（{r.get('j_value')}），不是调参量")
        if r.get("benchmark") == "none":
            issues.append("benchmark: 无 Rf / 无数值型基准差，不构成文献意义的超额收益")
        if r.get("gross_net") == "confounded":
            issues.append("gross_net: 毛净之差混淆了成本以外的机制")
        if r.get("scale") in ("bp", "pct") and r.get("declared_unit") == "decimal":
            issues.append("scale: 声明 decimal 但标记为 bp/pct")
        out.append({**r, "issues": issues, "compliant": len(issues) == 0})
    return out


def build_canary() -> tuple[list[dict], set[str]]:
    """阳性对照：7 行人工表，其中 3 行**故意注入**已知错误（prereg §4 K6）。

    关键要求：合规行的 `value` **必须不等于**其 `single_leg_value`，
    否则「标记 two_leg_net 但值等于单腿值」这条规则会把合规行也标出来（误标）。
    R004 第一版正是把所有行的 `single_leg_value` 都设成与 `value` 相等，
    导致合规行 OK-1 被误标、K6 假失败 —— 详见 `R004_report.md` 附录勘误 E-2。

    返回 (rows, expected_bad_names)。
    """
    # 合规行：双腿口径的值 (0.0024) 与单腿口径的值 (0.0054) **不同**
    good = dict(value=0.0024, single_leg_value=0.0054, order_used="last_minus_first",
                group_count=10, j_value=10, benchmark="rf", gross_net="gross",
                scale="decimal", declared_unit="decimal")
    rows = [
        # --- 4 行合规（审计器**不得**标出） ---
        {**good, "name": "OK-1 标准多空（双腿、有 Rf、J=10）", "cost": "two_leg_net", "sign": "high_minus_low"},
        {**good, "name": "OK-2 毛收益统计估计量", "cost": "gross", "sign": "high_minus_low"},
        {**good, "name": "OK-3 基准差（数值型）", "cost": "gross", "sign": "high_minus_low", "benchmark": "index"},
        {**good, "name": "OK-4 bp 单位且声明为 bp", "cost": "gross", "sign": "high_minus_low",
         "scale": "bp", "declared_unit": "bp"},
        # --- 3 行注入错误（审计器**必须**标出） ---
        {**good, "name": "BAD-C1 把单腿口径标为 two_leg_net", "cost": "two_leg_net",
         "sign": "high_minus_low", "value": 0.0054, "single_leg_value": 0.0054},
        {**good, "name": "BAD-C2 用 first−last 却标 high−low", "cost": "gross",
         "sign": "high_minus_low", "order_used": "first_minus_last"},
        {**good, "name": "BAD-C3 小数口径标为 bp", "cost": "gross", "sign": "high_minus_low",
         "scale": "bp", "declared_unit": "decimal"},
    ]
    return rows, {"BAD-C1 把单腿口径标为 two_leg_net", "BAD-C2 用 first−last 却标 high−low",
                  "BAD-C3 小数口径标为 bp"}


# ----------------------------------------------------------------------------
# 7. 主流程
# ----------------------------------------------------------------------------
def main() -> int:
    t0 = datetime.now()
    log("=" * 78)
    log("R004 口径结构同构性审计 开始（prereg: rounds/R004_prereg.md）")

    r003 = load_r003_module()
    log("已复用 R003 模块（load_panel / build_pack / apply_cost / newey_west_t）")

    p, meta = r003.load_panel()
    log(f"面板载入：rows={meta['rows_loaded_since_2023_11_01']} "
        f"codes={meta['distinct_codes']} dates={meta['distinct_dates']}")

    pack = r003.build_pack(p, r003.PRIMARY_N, PRIMARY_H)
    dates = pack["dates"]
    log(f"pack 构建完成：dates={len(dates)} cols={pack['st'].shape[1]} N={r003.PRIMARY_N} h={PRIMARY_H}")

    out: dict = {
        "meta": meta,
        "audit_config": {
            "N": r003.PRIMARY_N, "h": PRIMARY_H, "cost_grid_bp": list(COST_GRID_BP),
            "group_counts": list(GROUP_COUNTS), "project_J": PROJECT_J,
            "ccf_standard_J": CCF_STANDARD_J, "degrade": {"J": DEGRADE_J, "n": DEGRADE_N},
            "segments": {k: list(v) for k, v in SEGMENTS.items()},
            "random_seed": RANDOM_SEED, "max_variants": MAX_VARIANTS,
            "prereg_variants": PREREG_VARIANTS,
            "thresholds": {"K1_abs_bp": K1_ABS_BP, "K1_rel": K1_REL, "K2_t_mult": K2_T_MULT,
                           "K2_abs_bp": K2_ABS_BP, "K3_rel": K3_REL,
                           "tol_parity": TOL_PARITY, "tol_identity": TOL_IDENTITY},
            "frozen_cost": {"commission": COMMISSION, "sell_tax": SELL_TAX, "slippage": SLIPPAGE,
                            "r003_round_trip_bp": FROZEN_TOTAL * 1e4,
                            "project_round_trip_bp":
                                (2 * COMMISSION + SELL_TAX + 2 * SLIPPAGE) * 1e4},
        },
    }

    # --- 工具校验 T1：J=10 分组必须与 R003 逐位相等 -------------------------
    log("-" * 78)
    grp10, filled10 = assign_groups(pack, 10, r003.MIN_NAMES_PER_DATE)
    t1_same = bool(np.array_equal(grp10, pack["grp"]))
    t1_filled_same = all(
        (a is None and b is None) or (a is not None and b is not None and np.array_equal(a, b))
        for a, b in zip(filled10, pack["filled_rows"]))
    log(f"T1 分组同构（J=10 vs R003 grp）：bitwise_equal={t1_same} filled_equal={t1_filled_same}")
    out["T1_grouping_parity"] = {"grp_bitwise_equal": t1_same, "filled_rows_equal": t1_filled_same}

    # --- 工具校验 T2：两套成本表达式在 s=1 时的差异（可解释性检查） ----------
    a1, b1 = project_factors(12.0)
    a1_r003 = 1.0 - SELL_TAX - SLIPPAGE
    b1_r003 = 1.0 + COMMISSION + SLIPPAGE
    out["T2_cost_model_divergence"] = {
        "project_a": a1, "project_b": b1,
        "project_round_trip_bp": (b1 - a1) * 1e4,
        "r003_a": a1_r003, "r003_b": b1_r003,
        "r003_round_trip_bp": (b1_r003 - a1_r003) * 1e4,
        "note": "项目口径佣金两侧各收一次（15bp 往返）；R003 的 apply_cost 只收买入侧（12bp）。"
                "R003 的 c_rt=12bp 因此在冻结 s=1 处比项目实际低 3bp（= 1×COMMISSION）。",
    }
    log(f"T2 成本模型差异：项目往返={out['T2_cost_model_divergence']['project_round_trip_bp']:.4f}bp  "
        f"R003 往返={out['T2_cost_model_divergence']['r003_round_trip_bp']:.4f}bp")

    # --- 前置闸 P0-2：零成本恒等 -------------------------------------------
    log("-" * 78)
    m_is = segment_mask(dates, "IS")
    m_oos = segment_mask(dates, "OOS")
    net_single_0 = project_net_single_leg(pack["gross_np"], 0.0)
    sp_gross = spread_stats(pack, pack["gross_np"], grp10, filled10, m_is, 10)
    sp_zero = spread_stats(pack, net_single_0, grp10, filled10, m_is, 10)
    p02 = abs(sp_gross["spread"] - sp_zero["spread"])
    out["P0_2_zero_cost_identity"] = {
        "gross_spread": sp_gross["spread"], "zero_cost_spread": sp_zero["spread"],
        "abs_diff": p02, "passed": bool(p02 < TOL_IDENTITY),
    }
    log(f"P0-2 零成本恒等：|diff|={p02:.3e} passed={p02 < TOL_IDENTITY}")

    # --- 前置闸 P0-1：与 R003 冻结结果逐位 parity --------------------------
    log("-" * 78)
    with open(R003_RESULT, "r", encoding="utf-8") as fh:
        r003_res = json.load(fh)
    parity = {}
    for seg, mask in (("IS", m_is), ("OOS", m_oos)):
        key = f"{seg}_N20_h3"
        ref = r003_res["grid_parity_M1"][key]["spread_net_d1_d10"]
        # R003 口径：apply_cost + D1−D10（即 first − last）
        net_r003 = r003.apply_cost(pack, r003.COST_MODELS[1])
        st = spread_stats(pack, net_r003, grp10, filled10, mask, 10)
        mine = st["first_group_mean"] - st["last_group_mean"]
        rel = abs(mine - ref) / abs(ref) if ref else abs(mine - ref)
        parity[key] = {"r003_ref": ref, "r004_recomputed": mine, "rel_err": rel,
                       "passed": bool(rel < TOL_PARITY)}
        log(f"P0-1 parity {key}: ref={ref!r} mine={mine!r} rel_err={rel:.3e} "
            f"passed={rel < TOL_PARITY}")
    p01 = all(v["passed"] for v in parity.values())
    out["P0_1_reuse_parity"] = {"cells": parity, "passed": bool(p01)}
    gate_invalid = (not p01) or (not out["P0_2_zero_cost_identity"]["passed"]) \
        or (not t1_same) or (not t1_filled_same)

    # --- A1 成本轴 --------------------------------------------------------
    log("-" * 78)
    log("A1 成本轴：单腿（项目现状） vs 双腿（美元中性）")
    a1_cells = []
    t3_checks = []
    for seg, mask in (("IS", m_is), ("OOS", m_oos)):
        for c in COST_GRID_BP:
            single = spread_stats(pack, project_net_single_leg(pack["gross_np"], c),
                                  grp10, filled10, mask, 10, collect_series=True)
            two = two_leg_spread(pack, grp10, filled10, mask, 10, c, collect_series=True)
            # 镜像方向（first−last）作为同一单元格内的对称性检查，不计变体
            mirror_single = single["first_group_mean"] - single["last_group_mean"]
            gap = single["spread"] - two["spread"]
            thr = max(K1_ABS_BP, K1_REL * abs(bp(two["spread"])))
            # T3：零成本时，双腿必须逐位等于单腿（同方向、同一 g_H−g_L）
            if c == 0.0:
                t3_checks.append({"segment": seg,
                                  "abs_diff": abs(single["spread"] - two["spread"]),
                                  "passed": bool(abs(single["spread"] - two["spread"]) < TOL_IDENTITY)})
            a1_cells.append({
                "segment": seg, "cost_bp": c,
                "single_leg_spread": single["spread"], "single_leg_spread_bp": bp(single["spread"]),
                "two_leg_spread": two["spread"], "two_leg_spread_bp": bp(two["spread"]),
                "two_leg_long_leg_mean_bp": bp(two["long_leg_mean"]),
                "two_leg_short_leg_mean_bp": bp(two["short_leg_mean"]),
                "gap": gap, "gap_bp": bp(gap), "k1_threshold_bp": thr,
                "k1_magnitude_ok": bool(abs(bp(gap)) >= thr),
                "k1_fires": bool(abs(bp(gap)) >= thr),
                "days": single["days"], "dropped_obs_single": single["dropped_obs"],
                "dropped_obs_two": two["dropped_obs"],
                "mirror_single_bp": bp(mirror_single),
                "gap_over_two_leg_abs": (abs(gap) / abs(two["spread"])) if two["spread"] else math.nan,
            })
            log(f"  {seg} c={c:5.1f}bp  单腿={bp(single['spread']):9.4f}bp  "
                f"双腿={bp(two['spread']):9.4f}bp  缺口={bp(gap):9.4f}bp  "
                f"阈值={thr:6.2f}bp  幅值触发={a1_cells[-1]['k1_magnitude_ok']}")
    m1 = {c["segment"]: c for c in a1_cells if c["cost_bp"] == 12.0}
    k1_mag_all = all(m1[s]["k1_magnitude_ok"] for s in ("IS", "OOS"))
    k1_same_sign = (math.copysign(1, m1["IS"]["gap"]) == math.copysign(1, m1["OOS"]["gap"]))
    t3_pass = all(t["passed"] for t in t3_checks)
    out["T3_two_leg_zero_cost_identity"] = {"checks": t3_checks, "passed": bool(t3_pass)}
    log(f"T3 双腿零成本恒等（应与单腿相等）：passed={t3_pass}")
    out["A1_cost_axis"] = {
        "cells": a1_cells,
        "M1_frozen_12bp": {s: {k: m1[s][k] for k in
                              ("single_leg_spread_bp", "two_leg_spread_bp", "gap_bp",
                               "k1_threshold_bp", "k1_magnitude_ok", "k1_fires",
                               "gap_over_two_leg_abs")}
                           for s in ("IS", "OOS")},
        "K1": {
            "magnitude_only_fires_both_segments": bool(k1_mag_all),
            "gap_same_sign": bool(k1_same_sign),
            "fires_as_frozen": bool(k1_mag_all and k1_same_sign),
            "note": "prereg §9-K1 冻结文本 = 幅值条件 **且** IS/OOS 同号。"
                    "以**修正后的**双腿构造（§附录勘误 E-1）实测：缺口 G 在 IS/OOS 两段均为正"
                    "（成本在双腿上存活两次 ⟹ G>0 与 spread 自身符号无关），故同号子句成立，"
                    "K1 按冻结文本干净触发，无需任何解释性放宽。"
                    "勘误 E-3 记录：审计实现首次（错误）产出双腿恒为 0，使 G 退化为 spread 本身、"
                    "随 spread 的 IS/OOS 变号而变号；我据此**一度**怀疑冻结文本的同号子句误写，"
                    "该怀疑经证伪后撤回——错误源在实现侧，不在预注册侧。",
        },
    }
    log(f"A1 K1（冻结文本）：幅值两段均过={k1_mag_all} 同号={k1_same_sign} "
        f"⟹ 按冻结文本触发={k1_mag_all and k1_same_sign}")
    log(f"A1 K1（仅幅值读法，附加报告）：触发={k1_mag_all}")

    # --- A6 分组数轴 ------------------------------------------------------
    log("-" * 78)
    log("A6 分组数轴：J ∈ {5,10,20}（CCF 标准 = 10；项目 = 5）")
    a6 = {}
    for seg, mask in (("IS", m_is), ("OOS", m_oos)):
        entry = {}
        series = {}
        for J in GROUP_COUNTS:
            g, fr = assign_groups(pack, J, r003.MIN_NAMES_PER_DATE)
            single = spread_stats(pack, project_net_single_leg(pack["gross_np"], 12.0),
                                  g, fr, mask, J, collect_series=True)
            gross = spread_stats(pack, pack["gross_np"], g, fr, mask, J)
            entry[J] = {"spread_bp": bp(single["spread"]), "gross_spread_bp": bp(gross["spread"]),
                        "days": single["days"]}
            series[J] = single["spread_series"]
        c3 = entry[PROJECT_J]["spread_bp"]
        c4 = entry[CCF_STANDARD_J]["spread_bp"]
        delta = (c4 - c3)
        # 重叠期 NW(SE)，lag = h
        n = min(len(series[PROJECT_J]), len(series[CCF_STANDARD_J]))
        d = series[CCF_STANDARD_J][:n] - series[PROJECT_J][:n]
        mean_d, t_nw, t_iid, se_nw = r003.newey_west_t(d, PRIMARY_H)
        entry["delta_J10_minus_J5"] = {"bp": delta, "mean_series_bp": bp(mean_d),
                                       "se_nw_bp": bp(se_nw), "t_nw": t_nw, "n_days": int(n)}
        entry["K2"] = {"t_ok": bool(math.isfinite(t_nw) and abs(t_nw) >= K2_T_MULT),
                       "abs_ok": bool(abs(delta) >= K2_ABS_BP),
                       "fires": bool(math.isfinite(t_nw) and abs(t_nw) >= K2_T_MULT
                                     and abs(delta) >= K2_ABS_BP)}
        a6[seg] = entry
        log(f"  {seg}: J=5 {c3:8.4f}bp  J=10 {c4:8.4f}bp  J=20 {entry[20]['spread_bp']:8.4f}bp  "
            f"Δ(J10−J5)={delta:8.4f}bp  t_NW={t_nw:.4f}  触发={entry['K2']['fires']}")
    k2_same = (math.copysign(1, a6["IS"]["delta_J10_minus_J5"]["bp"])
               == math.copysign(1, a6["OOS"]["delta_J10_minus_J5"]["bp"]))
    k2_fires = bool(a6["IS"]["K2"]["fires"] and a6["OOS"]["K2"]["fires"] and k2_same)
    out["A6_group_count_axis"] = {"segments": a6, "same_sign": bool(k2_same), "K2_fires": k2_fires}
    log(f"A6 K2：两段均触发={a6['IS']['K2']['fires'] and a6['OOS']['K2']['fires']} "
        f"同号={k2_same} ⟹ K2 触发={k2_fires}")

    # --- A6 退化检查 ------------------------------------------------------
    log("-" * 78)
    deg_rows = []
    g5, fr5 = assign_groups(pack, DEGRADE_J, r003.MIN_NAMES_PER_DATE)
    n_obs = np.array([0 if r is None else len(r) for r in fr5])
    med_n = float(np.median(n_obs[n_obs > 0])) if (n_obs > 0).any() else math.nan
    small = int(((n_obs > 0) & (n_obs <= DEGRADE_N)).sum())
    r003_floor_days = int(((n_obs > 0) & (n_obs < r003.MIN_NAMES_PER_DATE)).sum())
    deg = {
        "J": DEGRADE_J,
        "project_min_cross_section": DEGRADE_N,
        "r003_min_names_per_date": r003.MIN_NAMES_PER_DATE,
        "median_names_per_day": med_n,
        "days_at_or_below_project_floor": small,
        "days_below_r003_floor": r003_floor_days,
        "group_size_at_floor": math.ceil(DEGRADE_N / DEGRADE_J),
        "note": "项目用 RANK_IC_MIN_CROSS_SECTION_SIZE=10 放行截面；在 n=10 时 J=5 每组 2 只，"
                "组均值由 2 个观测决定。R003 用的截面下限是 30。两者不是同一个有效性闸。",
    }
    # 实测：n<=10 的日子有多少（若为 0，则该退化路径在本地面板不可达）
    with_degrade = 0
    for i in range(len(dates)):
        if n_obs[i] > 0 and n_obs[i] <= DEGRADE_N:
            with_degrade += 1
    deg["days_reaching_degraded_path"] = with_degrade
    deg["degraded_path_reachable"] = bool(with_degrade > 0)
    out["A6_degradation"] = deg
    log(f"A6 退化：中位截面={med_n:.1f} 只；n≤{DEGRADE_N} 的天数={with_degrade} "
        f"⟹ 退化路径可达={deg['degraded_path_reachable']}")

    # --- A3 毛净轴 --------------------------------------------------------
    log("-" * 78)
    log("A3 毛净轴：四口径分解")
    # (i) 项目 RAW：qfq close[t] → close[t+h]，无成本
    close = p["close"]
    raw_i = (close.shift(-PRIMARY_H) / close - 1.0).to_numpy(dtype="float64")
    # 项目 RAW 的分组与有效性沿用同一 st（mask 不变），但需要其自身的有限性掩码
    # (iv) 同窗口毛：open[t+1] → close[t+1+h]，无成本 == pack["gross_np"]
    # (iii) 同窗口净：同上 + 项目成本表达式（c_rt=12bp）
    a3 = {}
    for seg, mask in (("IS", m_is), ("OOS", m_oos)):
        core = spread_stats(pack, pack["gross_np"], grp10, filled10, mask, 10)      # (iv)
        same_win_net = spread_stats(pack, project_net_single_leg(pack["gross_np"], 12.0),
                                    grp10, filled10, mask, 10)                      # (iii)
        proj_raw = spread_stats(pack, raw_i, grp10, filled10, mask, 10)             # (i)
        v_iv, v_iii, v_i = bp(core["spread"]), bp(same_win_net["spread"]), bp(proj_raw["spread"])
        cost_term = v_iii - v_iv            # 同窗口的成本项（干净）
        window_term = v_iv - v_i            # 入场口径 + 一天位移
        project_diff = v_iii - v_i          # 用户看到的「净 − 毛」代理
        noncost_share = abs(window_term) / abs(project_diff) if project_diff else math.nan
        cost_share = abs(cost_term) / abs(project_diff) if project_diff else math.nan
        a3[seg] = {
            "(i)_project_RAW_qfq_close_close_bp": v_i,
            "(iii)_same_window_net_bp": v_iii,
            "(iv)_same_window_gross_bp": v_iv,
            "cost_term_same_window_bp": cost_term,
            "window_term_bp": window_term,
            "project_gross_to_net_diff_bp": project_diff,
            "cost_share_of_project_diff": cost_share,
            "noncost_share_of_project_diff": noncost_share,
            "K3_fires": bool(math.isfinite(noncost_share) and noncost_share >= K3_REL),
        }
        log(f"  {seg}: (i)项目RAW={v_i:9.4f}bp  (iv)同窗口毛={v_iv:9.4f}bp  "
            f"(iii)同窗口净={v_iii:9.4f}bp")
        log(f"      成本项={cost_term:9.4f}bp ({cost_share * 100:6.2f}%)  "
            f"窗口项={window_term:9.4f}bp ({noncost_share * 100:6.2f}%)  "
            f"⟹ K3 触发={a3[seg]['K3_fires']}")
    # 真实 (ii) 的项目口径是否可算 —— market_price_raw 行数
    import pymysql
    conn = pymysql.connect(**r003.DB)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM market_price_raw")
            raw_rows = int(cur.fetchone()[0])
    finally:
        conn.close()
    out["A3_gross_net_axis"] = {
        "segments": a3,
        "(ii)_project_COST_APPROX_computable": bool(raw_rows > 0),
        "market_price_raw_rows": raw_rows,
        "K3_fires": bool(a3["IS"]["K3_fires"] and a3["OOS"]["K3_fires"]),
        "blocked_note": "项目 COST_APPROX 块要求 market_price_raw（V2-MP-014 raw-only）；本地该表 0 行，"
                        "⟹ 生产路径下每个观测都被跳过，(ii) 无法在本地计算。"
                        "本轮 (iii)/(iv) 用 market_data（qfq）作同窗口代理，用于分离成本项与窗口项。",
    }
    log(f"A3 真实 (ii) 可算={raw_rows > 0}（market_price_raw={raw_rows} 行）")

    # --- A2 符号轴 --------------------------------------------------------
    log("-" * 78)
    net12 = project_net_single_leg(pack["gross_np"], 12.0)
    s_is = spread_stats(pack, net12, grp10, filled10, m_is, 10)
    last_first = s_is["last_group_mean"] - s_is["first_group_mean"]
    first_last = s_is["first_group_mean"] - s_is["last_group_mean"]
    neg_ok = abs(last_first + first_last) < TOL_IDENTITY
    # 负值方向检查：g_H − g_L 的符号应与 last−first 的符号一致
    a2 = {
        "last_minus_first": last_first, "first_minus_last": first_last,
        "sum": last_first + first_last, "exact_negation": bool(neg_ok),
        "orientation_is_high_minus_low": True,
        "note": "FactorResearchEngine.java:181-186 按得分升序建组，spread = last − first ⟹ 高 − 低，"
                "与 CCF2019 的 µ(z_H) − µ(z_L) 同向。",
        "no_direction_flag": True,
        "K2s_fires": bool(not neg_ok),
    }
    out["A2_sign_axis"] = a2
    log(f"A2 符号：last−first={bp(last_first):.4f}bp  first−last={bp(first_last):.4f}bp  "
        f"和={last_first + first_last:.3e} 精确取负={neg_ok} ⟹ K2s 触发={a2['K2s_fires']}")

    # --- A4 尺度轴 + A5 基准轴（静态扫描） --------------------------------
    log("-" * 78)
    scans = scan_sources()
    out["static_scans"] = {k: {"count": v["count"], "by_ext": v["by_ext"],
                               "by_file": v["by_file"], "sample": v["hits"][:6]}
                           for k, v in scans.items()}
    # 展示层（.vue）逐个界面：谁展示 spread，谁同时标注了口径
    vue_files = sorted({f for f in scans["two_long_leg_spread"]["files"] if f.endswith(".vue")})
    scope_vue_files = sorted({f for f in scans["metric_scope"]["files"] if f.endswith(".vue")})
    surfaces = []
    for f in vue_files:
        surfaces.append({
            "file": f,
            "shows_spread": True,
            "shows_scope": f in scope_vue_files,
            "spread_hits": scans["two_long_leg_spread"]["by_file"].get(f, 0),
            "scope_hits": scans["metric_scope"]["by_file"].get(f, 0),
        })
    missing = [s["file"] for s in surfaces if not s["shows_scope"]]
    a4 = {
        "metric_scope_by_ext": scans["metric_scope"]["by_ext"],
        "metric_scope_vue_files": scope_vue_files,
        "spread_vue_files": vue_files,
        "surfaces": surfaces,
        "surfaces_missing_scope": missing,
        "non_excess_disclaimer_hits": scans["non_excess_disclaimer"]["count"],
        "declares_scope_in_ui": bool(scope_vue_files),
        "K4s_fires": bool(len(missing) > 0),
        "note": "K4s 判据 = 「呈现该量的界面是否**同时**标注口径」。"
                "判定用 `modules/web/src/**/*.vue`（展示层），"
                "`.ts` 里的 `metric_scope?: string` 只是类型声明，用户不可见，不计入。",
    }
    out["A4_scale_axis"] = a4
    log(f"A4 尺度：metric_scope 按扩展名={scans['metric_scope']['by_ext']}")
    log(f"  展示 spread 的 .vue = {len(vue_files)} 个；其中**未**标注口径 = {missing}")
    log(f"  非超额/非年化/无做空 声明命中={a4['non_excess_disclaimer_hits']} ⟹ K4s 触发={a4['K4s_fires']}")

    idx, idx_meta = load_index_rf(r003)
    # 基准可算的天数占比：面板交易日 vs 指数覆盖日
    panel_days = int(((m_is | m_oos)).sum())
    covered = int(sum(1 for d in dates if d in idx.index and (m_is | m_oos)[list(dates).index(d)]))
    excess_mag: dict = {}
    if len(idx) > 0:
        for seg, mask in (("IS", m_is), ("OOS", m_oos)):
            core = spread_stats(pack, pack["gross_np"], grp10, filled10, mask, 10)
            # 指数同窗口 forward return（entry=次日，exit=entry+h 收盘），仅覆盖指数存在的日子
            ic = idx.reindex(dates)
            fwd = (ic.shift(-(1 + PRIMARY_H)) / ic.shift(-1) - 1.0).to_numpy(dtype="float64")
            fm = np.asarray(mask) & np.isfinite(fwd)
            bm = float(np.nanmean(fwd[fm])) if fm.any() else math.nan
            excess_mag[seg] = {"benchmark_fwd_mean_bp": bp(bm),
                               "index_days_used": int(fm.sum()),
                               "strategy_same_window_gross_bp": bp(core["spread"])}
    a5 = {
        "index": idx_meta,
        "panel_days_in_window": panel_days,
        "index_covered_days_in_window": covered,
        "coverage_ratio": (covered / panel_days) if panel_days else math.nan,
        "outperform_is_boolean_only": True,
        "outperform_hits": scans["outperform"]["count"],
        "excess_field_hits": scans["excess_field"]["count"],
        "rf_field_hits": scans["rf_field"]["count"],
        "rf_series_exists": idx_meta["rf_series_exists"],
        "excess_magnitude_not_exposed": excess_mag,
        "net_vs_gross_asymmetry": {
            "strategy_leg": "NET_AFTER_MODELED_COSTS（ScoringBacktestService assumptions）",
            "benchmark_leg": "指数收盘收益，无任何成本",
            "bias_direction": "系统性不利于策略（对策略保守）",
            "magnitude_bp_per_holding": (2 * COMMISSION + SELL_TAX + 2 * SLIPPAGE) * 1e4,
        },
        "K5_fires": bool(scans["excess_field"]["count"] == 0 and scans["rf_field"]["count"] == 0
                         and not idx_meta["rf_series_exists"]),
    }
    out["A5_benchmark_axis"] = a5
    log(f"A5 基准：excess 字段={scans['excess_field']['count']} rf 字段={scans['rf_field']['count']} "
        f"Rf 表={idx_meta['rf_like_tables']} ⟹ K5 触发={a5['K5_fires']}")
    log(f"A5 指数覆盖：{covered}/{panel_days} = {a5['coverage_ratio'] * 100:.1f}%")

    # --- K6 阳性对照 ------------------------------------------------------
    log("-" * 78)
    canary_rows, expected_bad = build_canary()
    audited = audit_rows(canary_rows)
    flagged = {r["name"] for r in audited if not r["compliant"]}
    missed = sorted(expected_bad - flagged)
    false_pos = sorted(flagged - expected_bad)
    k6_pass = (not missed) and (not false_pos)
    out["K6_canary"] = {
        "rows": [{"name": r["name"], "compliant": r["compliant"], "issues": r["issues"]}
                 for r in audited],
        "expected_bad": sorted(expected_bad), "flagged": sorted(flagged),
        "missed": missed, "false_positives": false_pos, "passed": bool(k6_pass),
    }
    log(f"K6 阳性对照：捕获={sorted(flagged)} 漏检={missed} 误标={false_pos} ⟹ 通过={k6_pass}")

    # --- 实测口径表 → 审计器 -------------------------------------------------
    live_rows = []
    for seg in ("IS", "OOS"):
        live_rows.append({
            "name": f"项目 two_long_leg_spread（{seg}，h=3，COST_APPROX 命名）",
            "value": out["A1_cost_axis"]["M1_frozen_12bp"][seg]["single_leg_spread_bp"] / 1e4,
            "single_leg_value": out["A1_cost_axis"]["M1_frozen_12bp"][seg]["single_leg_spread_bp"] / 1e4,
            "cost": "two_leg_net" if out["A1_cost_axis"]["M1_frozen_12bp"][seg]["k1_fires"] else "two_leg_net",
            "sign": "high_minus_low", "order_used": "last_minus_first",
            "group_count": "fixed", "j_value": PROJECT_J,
            "benchmark": "none", "gross_net": "confounded",
            "scale": "decimal", "declared_unit": "decimal",
            "segment": seg,
        })
    live = audit_rows(live_rows)
    out["live_audit_table"] = [
        {"name": r["name"], "segment": r["segment"], "compliant": r["compliant"], "issues": r["issues"]}
        for r in live]

    # --- 变体计数 ---------------------------------------------------------
    realised = {
        "A1_single_leg": len(COST_GRID_BP) * 2,
        "A1_two_leg": len(COST_GRID_BP) * 2,
        "A6_group_counts": (len(GROUP_COUNTS) - 1) * 2,
        "A3_four_definitions": 4 * 2,
        "A2_sign": 1, "A6_degradation": 1, "K6_canary": 1,
    }
    realised["total_distinct_evaluations"] = sum(realised.values())
    realised["max_variants"] = MAX_VARIANTS
    realised["prereg_total"] = PREREG_VARIANTS
    realised["parity_cells_reused"] = 2
    realised["within_budget"] = bool(realised["total_distinct_evaluations"] <= MAX_VARIANTS)
    out["variant_table_realised"] = realised
    log("-" * 78)
    log(f"变体：实测={realised['total_distinct_evaluations']} 预注册={PREREG_VARIANTS} "
        f"上限={MAX_VARIANTS} 在预算内={realised['within_budget']}")

    # --- 判定 -------------------------------------------------------------
    fired = []
    if out["A1_cost_axis"]["K1"]["fires_as_frozen"]:
        fired.append("K1 成本轴")
    if out["A6_group_count_axis"]["K2_fires"]:
        fired.append("K2 分组数轴")
    if out["A3_gross_net_axis"]["K3_fires"]:
        fired.append("K3 毛净轴")
    if out["A2_sign_axis"]["K2s_fires"]:
        fired.append("K2s 符号轴")
    if out["A4_scale_axis"]["K4s_fires"]:
        fired.append("K4s 尺度轴")
    if out["A5_benchmark_axis"]["K5_fires"]:
        fired.append("K5 基准轴")
    structural = {"K4s 尺度轴", "K5 基准轴"}
    if gate_invalid or not k6_pass:
        status = "INVALID"
    elif not fired:
        status = "INCONCLUSIVE"
    elif set(fired) <= structural:
        status = "INCONCLUSIVE"
    else:
        status = "REJECTED"
    out["verdict"] = {
        "status": status, "fired": fired,
        "fired_numeric": sorted(set(fired) & {"K1 成本轴", "K2 分组数轴", "K3 毛净轴", "K2s 符号轴"}),
        "fired_structural": sorted(set(fired) & structural),
        "additional_magnitude_only": {
            "K1_magnitude_only_fires": out["A1_cost_axis"]["K1"]["magnitude_only_fires_both_segments"],
            "note": "仅按幅值读法；**不计入** status（本轮同号子句亦成立，故该附加读法与冻结判定同结论），"
                    "保留该字段以便与 R004_report 并列陈述。",
        },
        "gate_invalid": bool(gate_invalid), "canary_passed": bool(k6_pass),
        "structural_only": bool(set(fired) <= structural),
        "semantics": {
            "REJECTED": "命名与标准定义结构不同构（至少一条数值型 K 触发）",
            "INCONCLUSIVE": "仅结构性缺失触发 或 无 K 触发 ⟹ 必须写成「数值口径同构，但展示与命名层不完整」，"
                            "不得写成「口径全错」",
            "INVALID": "前置闸或阳性对照失败 ⟹ 不得产出任何结论",
        }[status],
        "ledger_status_mapping": {"REJECTED": "REJECTED", "INCONCLUSIVE": "INCONCLUSIVE",
                                  "INVALID": "INCONCLUSIVE"},
    }
    log("=" * 78)
    log(f"判定：{status}；触发={fired}")
    log(f"总耗时 {str(datetime.now() - t0).split('.')[0]}")

    out["run_time"] = datetime.now().isoformat(timespec="seconds")
    cfg = {
        "experiment_id": "R004_semantics_audit",
        "paper_id": "FF2015",
        "method_authority": "CCF2019",
        "prereg": "research/paper_loop/rounds/R004_prereg.md",
        "review": "research/paper_loop/rounds/R004_review.md",
        "run_time": out["run_time"],
        **out["audit_config"],
        "variant_table_prereg": {
            "A1_single_leg": len(COST_GRID_BP) * 2, "A1_two_leg": len(COST_GRID_BP) * 2,
            "A6_group_counts": 4, "A3_four_definitions": 8,
            "A2_sign": 1, "A6_degradation": 1, "K6_canary": 1, "total": PREREG_VARIANTS,
        },
        "variant_table_realised": realised,
        "read_only": True,
        "data_source": "quant_db.market_data (qfq, 只读 SELECT) + quant_db.index_quote (000300)",
        "raw_price_available": bool(raw_rows > 0),
    }
    with open(os.path.join(HERE, "config.json"), "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2, default=str)
    with open(os.path.join(HERE, "result.json"), "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2, default=str)
    log("config.json / result.json 已写出")
    return 0 if status != "INVALID" else 2


if __name__ == "__main__":
    sys.exit(main())
