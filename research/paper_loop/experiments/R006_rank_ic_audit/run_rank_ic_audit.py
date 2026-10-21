#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R006 命名族审计（rank_ic 载荷族）—— 只读编排器。

判据来源：AlphaEval（arXiv:2508.13174v2，KDD 2026）的 IC / RankIC / ICIR / AR 逐式定义。
判据、阈值、网格、判定词表、杀死条件在 rounds/R006_prereg.md 中于本文件创建之前冻结。

硬约束（协议 §7 + 本轮 §9）：
  * 只读：DB 仅 SELECT；不 INSERT/UPDATE/DELETE/DDL。
  * 不改任何生产代码 / 测试 / 前端 / 契约 / ResearchConvention。
  * 发现一律只登记队列，不修。
  * 判据对象必须是 modules/backend-java/target/classes 下的**生产类**（门 G3）。
"""
import os
import re
import copy
import json
import math
import shutil
import subprocess
import sys
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
PROBE_SRC = os.path.join(HERE, "probe", "R006Probe.java")
PROBE_OUT = os.path.join(HERE, "probe", "out")
CLASSES = os.path.join(REPO, "modules", "backend-java", "target", "classes")
PROBE_MAIN = "com.quant.service.backtest.R006Probe"

DB = {
    "host": os.environ.get("QUANT_DB_HOST", "localhost"),
    "port": int(os.environ.get("QUANT_DB_PORT", "3306")),
    "user": os.environ.get("QUANT_DB_USER", "root"),
    "password": os.environ.get("QUANT_DB_PASSWORD", os.environ.get("DB_PASSWORD", "")),
    "database": os.environ.get("QUANT_DB_NAME", "quant_db"),
    "charset": "utf8mb4",
}

# ---------------------------------------------------------------------------
# 冻结常数（R006_prereg.md §3 / §4 / §6 / §8）—— 运行期不得修改
# ---------------------------------------------------------------------------
TOL = 1e-12
EQ8_TIE_TOL = 1e-6
RATIO_TOL = 1e-9
BH = 252

VARIANT = {
    "A1a": "V01", "A1b": "V02", "A1c": "V03",
    "A2a": "V04", "A2b": "V05",
    "A3a": "V06", "A3b": "V07", "A3c": "V08",
    "A4a": "V09", "A4b": "V10",
    "A5a": "V11", "A5b": "V12", "A5c": "V13",
    "A6a": "V14", "A6b": "V15", "A6c": "V16", "A6d": "V17",
    "A7a": "V18", "A7b": "V19", "A8": "V20",
}
BY_AXIS = {
    "A1": ["A1a", "A1b", "A1c"],
    "A2": ["A2a", "A2b"],
    "A3": ["A3a", "A3b", "A3c"],
    "A4": ["A4a", "A4b"],
    "A5": ["A5a", "A5b", "A5c"],
    "A6": ["A6a", "A6b", "A6c", "A6d"],
    "A7": ["A7a", "A7b"],
    "A8": ["A8"],
}
# 冻结的指定变体预算（prereg §3 / §4）
FROZEN_AXIS_COUNTS = {"A1": 3, "A2": 2, "A3": 3, "A4": 2, "A5": 3, "A6": 4, "A7": 2, "A8": 1}
FROZEN_TOTAL = 20

# 冻结的杀死条件表（prereg §6 的逐字副本；A7b/A8 的检查器会校验它没有被扩大）
FROZEN_K = [
    {"id": "K1", "condition": "A2a VIOLATED（无并列输入上实现 ≠ 式 (8)）", "outcome": "REJECTED", "refs": ["A2a"]},
    {"id": "K2", "condition": "A3a VIOLATED ⟹ CONVENTION_DIVERGENCE", "outcome": "CONVENTION_DIVERGENCE", "refs": ["A3a"]},
    {"id": "K3", "condition": "A4a VIOLATED（静默丢日且无丢弃计数字段）", "outcome": "REJECTED", "refs": ["A4a"]},
    {"id": "K4", "condition": "A5b VIOLATED（空组伪造 0 进入 spread）", "outcome": "REJECTED", "refs": ["A5b"]},
    {"id": "K5", "condition": "A6b VIOLATED（std=0 且 mean<0 时 rank_ic_ir == 0.0）", "outcome": "REJECTED", "refs": ["A6b"]},
    {"id": "K6", "condition": "A1b 或 A1c 成立", "outcome": "CONTRACT_AMBIGUOUS", "refs": ["A1b", "A1c"]},
    {"id": "K7", "condition": "A6d 成立", "outcome": "DEAD_COLUMN", "refs": ["A6d"]},
    {"id": "K8", "condition": "A7a 成立", "outcome": "REGISTERED_ONLY", "refs": ["A7a"]},
    {"id": "K9", "condition": "行和交叉校验失败", "outcome": "INCONCLUSIVE", "refs": []},
    {"id": "K10", "condition": "阳性对照未通过", "outcome": "INCONCLUSIVE", "refs": []},
]

# 冻结的计数期望（prereg §8）
EXPECT = {
    "c1_prod_ic_value_lines": 1,
    "db_total": 67,
    "db_ic_value_non_null": 0,
    "min_cross_section_size": 10,
    "rank_ic_group_count": 5,
}

SKIP_DIRS = {".git", "node_modules", "target", "dist", "__pycache__", ".idea", ".vscode", "out"}

_log_fh = None


def log(msg):
    line = "[%s] %s" % (datetime.now().strftime("%H:%M:%S"), msg)
    print(line)
    if _log_fh:
        _log_fh.write(line + "\n")
        _log_fh.flush()


# ===========================================================================
# 1) 编译并运行 Java 探针（门 G3：必须是仓库自己的生产类）
# ===========================================================================
def compile_and_run_probe():
    if not os.path.isdir(CLASSES):
        log("FAIL: 生产类目录不存在：%s（需先构建 backend-java）" % CLASSES)
        return None
    if os.path.isdir(PROBE_OUT):
        shutil.rmtree(PROBE_OUT)
    os.makedirs(PROBE_OUT, exist_ok=True)

    cp_compile = CLASSES
    cp_run = PROBE_OUT + os.pathsep + CLASSES
    jc = subprocess.run(
        ["javac", "-encoding", "UTF-8", "-cp", cp_compile, "-d", PROBE_OUT, PROBE_SRC],
        capture_output=True, text=True, errors="replace")
    log("javac rc=%d" % jc.returncode)
    if jc.stdout.strip():
        log("javac stdout: " + jc.stdout.strip())
    if jc.stderr.strip():
        log("javac stderr: " + jc.stderr.strip())
    if jc.returncode != 0:
        return None

    jr = subprocess.run(
        ["java", "-Dfile.encoding=UTF-8", "-cp", cp_run, PROBE_MAIN],
        capture_output=True, text=True, errors="replace")
    log("java rc=%d stdout_lines=%d" % (jr.returncode, len(jr.stdout.splitlines())))
    if jr.stderr.strip():
        log("java stderr: " + jr.stderr.strip())
    if jr.returncode != 0:
        return None

    probe = {}
    raw_lines = []
    for ln in jr.stdout.splitlines():
        ln = ln.strip()
        if not ln.startswith("{"):
            continue
        raw_lines.append(ln)
        try:
            o = json.loads(ln)
        except Exception as ex:
            log("WARN: 无法解析探针行：%r (%s)" % (ln, ex))
            continue
        probe[(o["probe"], o["key"], o.get("i"))] = o.get("value")
    log("probe lines parsed=%d keys=%d" % (len(raw_lines), len(probe)))
    return probe


def _num(v):
    if isinstance(v, (int, float)):
        return float(v)
    if v == "NaN":
        return float("nan")
    if v == "+Inf":
        return float("inf")
    if v == "-Inf":
        return float("-inf")
    if isinstance(v, str):
        try:
            return float(v)
        except ValueError:
            return float("nan")
    return float("nan")


def pv(ctx, probe, key, i=None):
    """取探针值；probe 名形如 'V01'，i 为 None 表示无索引。"""
    return ctx["probe"].get((probe, key, i))


def pn(ctx, probe, key, i=None):
    return _num(pv(ctx, probe, key, i))


def indices(ctx, probe, key):
    return sorted([k[2] for k in ctx["probe"].keys() if k[0] == probe and k[1] == key and k[2] is not None])


def close(a, b, tol=TOL):
    try:
        return abs(float(a) - float(b)) <= tol
    except (TypeError, ValueError):
        return False


# ===========================================================================
# 2) 文本语料（Python 走文件，不依赖外部 grep 是否在 PATH 上）
# ===========================================================================
def walk_lines(roots, exts, pattern=None, exclude_prefix=()):
    hits = []
    rx = re.compile(pattern) if pattern else None
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for fn in filenames:
                if exts and not fn.endswith(exts):
                    continue
                full = os.path.join(dirpath, fn)
                rel = os.path.relpath(full, REPO).replace("\\", "/")
                if any(rel.startswith(p) for p in exclude_prefix):
                    continue
                try:
                    with open(full, "r", encoding="utf-8", errors="replace") as fh:
                        for i, line in enumerate(fh, 1):
                            if rx is None or rx.search(line):
                                hits.append("%s:%d:%s" % (rel, i, line.rstrip("\n")))
                except OSError:
                    continue
    return hits


def build_text_corpora():
    mods = os.path.join(REPO, "modules")
    src_main = os.path.join(REPO, "modules", "backend-java", "src", "main")
    web = os.path.join(REPO, "modules", "web", "src")
    c = {}
    # C1：ic_value 全集（冻结命令口径）与生产口径（排除 research/ 自指）
    c["c1_all"] = walk_lines([REPO], (".java", ".sql", ".vue", ".js", ".ts", ".py"), r"ic_value")
    c["c1_prod"] = walk_lines([mods], (".java", ".sql", ".vue", ".js", ".ts"), r"ic_value")
    # C2：rank_ic_ir 全集（生产面）
    c["c2"] = walk_lines([mods], (".java", ".vue", ".js", ".ts"), r"rank_ic_ir")
    # C3：前端展示标签（rank 族 + Pearson 族）
    c["c3"] = walk_lines([web], (".vue",), r"rank_ic_ir|rank_ic_std|rank_ic_mean|pearson_ic_mean")
    # 丢弃计数字段是否存在（A4a）
    c["c_drop"] = walk_lines([mods], (".java", ".vue", ".js", ".ts"),
                             r"dropped_days|skipped_days|excluded_days|filtered_days|nan_days|"
                             r"invalid_days|dropped_count|drop_count|discarded")
    # A5c：DTO 是否导出每组观测数 / 前端是否渲染
    c["c_dto_obs"] = walk_lines([src_main], (".java",), r"\.observations\(")
    c["c_fe_obs"] = walk_lines([web], (".vue",), r'prop="observations"')
    c["c_fe_grouped"] = walk_lines([web], (".vue",), r"groupedReturns|grouped_returns")
    return c


def fetch_db():
    out = {"available": False, "total": None, "non_null": None, "error": None}
    try:
        import pymysql
        conn = pymysql.connect(**DB)
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) AS total, COUNT(ic_value) AS non_null FROM selector_result")
                row = cur.fetchone()
                out.update({"available": True, "total": int(row[0]), "non_null": int(row[1])})
        finally:
            conn.close()
        log("DB: selector_result total=%s ic_value_non_null=%s" % (out["total"], out["non_null"]))
    except Exception as ex:
        out["error"] = "%s: %s" % (type(ex).__name__, ex)
        log("DB unavailable/failed: %s" % out["error"])
    return out


# ===========================================================================
# 3) 检查器注册表（20 个，与 VARIANT 一一对应）
# ===========================================================================
def ck_A1a(ctx):
    ic_ir = pn(ctx, "V01", "rank_ic_ir")
    ir_rank_T = pn(ctx, "V01", "ir_rank_div_T")
    ir_pear_T = pn(ctx, "V01", "ir_pearson_div_T")
    gap = pn(ctx, "V01", "mean_abs_rank_vs_pearson_diff")
    m_rank = close(ic_ir, ir_rank_T)
    m_pear = close(ic_ir, ir_pear_T)
    discriminative = not close(ir_rank_T, ir_pear_T, 1e-9)
    ev = {"rank_ic_ir": ic_ir, "ir_rank_div_T": ir_rank_T, "ir_pearson_div_T": ir_pear_T,
          "match_rank_series": m_rank, "match_pearson_series": m_pear,
          "series_discriminative": discriminative,
          "mean_abs_rank_vs_pearson_gap": gap,
          "bound_key": "FactorResearchService:540 block.put(\"rank_ic_ir\", round4(summary.rankIcIr()))"}
    if m_rank and not m_pear:
        return "NOT_VIOLATED", ev
    if m_rank and m_pear:
        return "INCONCLUSIVE", ev
    return "VIOLATED", ev


_LABEL_RX = re.compile(r">\s*([^<>{}:：]{1,24}?)\s*[:：]\s*\{\{")
_KEY_RX = re.compile(r"\b(rank_ic_ir|rank_ic_std|rank_ic_mean|pearson_ic_mean)\b")


def _labels_for(corpus, keys):
    """从 .vue 行中抽出「标签：{{ 表达式 }}」并绑定到表达式里出现的键。"""
    out = []
    for line in corpus:
        m = _LABEL_RX.search(line)
        k = _KEY_RX.search(line)
        if m and k and k.group(1) in keys:
            out.append({"label": m.group(1).strip(), "key": k.group(1), "line": line})
    return out


def ck_A1b(ctx):
    labels = _labels_for(ctx["text"]["c3"], {"rank_ic_ir", "rank_ic_std", "rank_ic_mean"})
    lacking = [l for l in labels if ("rank" not in l["label"].lower() and "秩" not in l["label"])]
    ev = {"labels_checked": len(labels), "labels_lacking_rank_qualifier": lacking,
          "all_labels": labels,
          "frozen_criterion": "Table 1 将 IC/ICIR 与 RankIC/RankICIR 并列区分为不同指标"}
    if not labels:
        return "INCONCLUSIVE", ev
    return ("VIOLATED" if lacking else "NOT_VIOLATED"), ev


def ck_A1c(ctx):
    labels = _labels_for(ctx["text"]["c3"], {"rank_ic_ir", "pearson_ic_mean", "rank_ic_std"})
    has_pearson_label = any(l["key"] == "pearson_ic_mean" for l in labels)
    raw_icir = [l for l in labels if l["key"] == "rank_ic_ir"]
    bare_icir = [l for l in raw_icir if "rank" not in l["label"].lower()]
    rank_icir = [l for l in labels if "rankicir" in l["label"].lower().replace(" ", "")]
    ev = {"pearson_displayed": has_pearson_label, "rank_based_ir_displayed_as_bare_icir": bare_icir,
          "explicit_rankicir_labels": rank_icir,
          "risk": "同一张卡片同时展示 Pearson IC 与（实为 rank 序列的）ICIR ⟹ 可被读成一对；"
                  "论文 Table 1 中 ICIR 的分子是 IC(Pearson) 序列，项目未提供 Pearson 版 ICIR"}
    if not has_pearson_label or not bare_icir:
        return "NOT_VIOLATED", ev
    if rank_icir:
        return "NOT_VIOLATED", ev
    return "VIOLATED", ev


def ck_A2a(ctx):
    max_def = pn(ctx, "V04", "max_abs_engine_vs_def")
    max_eq8 = pn(ctx, "V04", "max_abs_engine_vs_eq8")
    cases = pn(ctx, "V04", "cases")
    anchor = pn(ctx, "V04", "sweep_anchor_reverse")
    checksum = pn(ctx, "V04", "engine_output_checksum")
    ev = {"cases": cases, "max_abs_engine_vs_def": max_def, "max_abs_engine_vs_eq8": max_eq8,
          "sweep_anchor_reverse_spearman": anchor, "engine_output_checksum": checksum,
          "criterion": "文献式 (8) 1 − 6Σd²/(N(N²−1))；无并列时应与生产实现一致"}
    if cases is None or cases < 200:
        return "INCONCLUSIVE", ev
    if max_def > TOL:
        return "VIOLATED", ev
    if max_eq8 > TOL:
        return "VIOLATED", ev
    if not close(anchor, -1.0):
        return "INCONCLUSIVE", ev
    return "NOT_VIOLATED", ev


def ck_A2b(ctx):
    max_def = pn(ctx, "V05", "max_abs_engine_vs_def")
    max_eq8 = pn(ctx, "V05", "max_abs_eq8_vs_def")
    cases = pn(ctx, "V05", "cases")
    ev = {"cases": cases, "max_abs_engine_vs_def": max_def, "max_abs_eq8_vs_def": max_eq8,
          "criterion_tier": "A（算术推导，非论文主张：论文全文未讨论并列）",
          "criterion": "有并列时式 (8) 不再等于 Spearman；定义值 = 平均秩的 Pearson"}
    if cases is None or cases < 5:
        return "INCONCLUSIVE", ev
    if max_def > TOL:
        return "VIOLATED", ev
    return "NOT_VIOLATED", ev


def ck_A3a(ctx):
    grid = ["grid_rank_1_div_T", "grid_rank_1_div_Tm1", "grid_rank_sqrt252_div_T",
            "grid_rank_sqrt252_div_Tm1", "grid_pearson_1_div_T", "grid_pearson_1_div_Tm1",
            "grid_pearson_sqrt252_div_T", "grid_pearson_sqrt252_div_Tm1"]
    per_panel = []
    primary_everywhere = True
    sample_everywhere = True
    for i in indices(ctx, "V06", "rank_ic_ir"):
        target = pn(ctx, "V06", "rank_ic_ir", i)
        matched = [g for g in grid if close(pn(ctx, "V06", g, i), target, 1e-9)]
        per_panel.append({"panel_index": i, "T": pn(ctx, "V06", "T", i), "rank_ic_ir": target,
                          "matched_cells": matched})
        if "grid_rank_1_div_T" not in matched:
            primary_everywhere = False
        if "grid_rank_1_div_Tm1" not in matched:
            sample_everywhere = False
    ev = {"grid": "分母 {T, T−1} × 年化 {×1, ×sqrt(252)} × 序列 {rank IC, Pearson IC} = 8 单元",
          "per_panel": per_panel,
          "criterion": "论文式 (18) 分母 ÷(T−1)（且式 (22) 的 σ_r 同为 T−1 ⟹ 论文内部一致）",
          "magnitude_note": "T=17 ⟹ sqrt(17/16)−1 = +3.08%；T=10 ⟹ +5.41%（量级微弱）"}
    if not per_panel:
        return "INCONCLUSIVE", ev
    if primary_everywhere:
        return "CONVENTION_DIVERGENCE", ev
    if sample_everywhere:
        return "NOT_VIOLATED", ev
    return "INCONCLUSIVE", ev


def ck_A3b(ctx):
    diffs = []
    for i in indices(ctx, "V07", "ratio_observed"):
        obs = pn(ctx, "V07", "ratio_observed", i)
        exp = pn(ctx, "V07", "ratio_expected", i)
        diffs.append({"panel_index": i, "observed": obs, "expected": exp,
                      "abs_diff": abs(obs - exp) if (obs == obs and exp == exp) else None})
    ev = {"per_panel": diffs, "tol": RATIO_TOL,
          "criterion": "两口径之比必须恰为 sqrt(T/(T−1))"}
    if not diffs:
        return "INCONCLUSIVE", ev
    if any(d["abs_diff"] is None or d["abs_diff"] > RATIO_TOL for d in diffs):
        return "VIOLATED", ev
    return "NOT_VIOLATED", ev


def ck_A3c(ctx):
    idxs = indices(ctx, "V08", "rank_ic_std")
    per = []
    matches_pop = True
    matches_sample = True
    for i in idxs:
        std = pn(ctx, "V08", "rank_ic_std", i)
        pop = pn(ctx, "V08", "std_pop", i)
        smp = pn(ctx, "V08", "std_sample", i)
        mp = close(std, pop)
        ms = close(std, smp)
        per.append({"panel_index": i, "rank_ic_std": std, "std_pop_div_T": pop,
                    "std_sample_div_Tm1": smp, "match_pop": mp, "match_sample": ms})
        matches_pop = matches_pop and mp
        matches_sample = matches_sample and ms
    ev = {"per_panel": per, "matches_population_T": matches_pop, "matches_sample_Tm1": matches_sample}
    if not per:
        return "INCONCLUSIVE", ev
    if matches_pop and not matches_sample:
        return "CONVENTION_DIVERGENCE", ev
    if matches_sample and not matches_pop:
        return "NOT_VIOLATED", ev
    return "INCONCLUSIVE", ev


def ck_A4a(ctx):
    in_dates = pn(ctx, "V09", "input_dates")
    csd = pn(ctx, "V09", "cross_section_days")
    dsize = pn(ctx, "V09", "daily_size")
    tot = pn(ctx, "V09", "total_observations")
    good_obs = pn(ctx, "V09", "good_observations")
    drop_hits = ctx["text"]["c_drop"]
    dropped = (in_dates - csd) if (in_dates is not None and csd is not None) else None
    ev = {"input_dates": in_dates, "input_good_dates": pn(ctx, "V09", "input_good_dates"),
          "input_degenerate_dates": pn(ctx, "V09", "input_degenerate_dates"),
          "cross_section_days": csd, "daily_size": dsize, "dropped_dates": dropped,
          "total_observations": tot, "good_observations": good_obs,
          "positive_rate_denominator_is_daily_size": True,
          "disclosure_field_hits": drop_hits,
          "codec": "FactorResearchEngine.java:150-152 if (!Double.isFinite(ic)) { continue; }",
          "criterion": "论文式 (5)/(7) 的 1/T Σ_{t=1}^{T} 是对全部 T 期取均值；"
                       "丢弃发生在观测到结果之后 ⟹ 对结果取选择"}
    if dropped is None:
        return "INCONCLUSIVE", ev
    if dropped > 0 and dsize != csd:
        return "INCONCLUSIVE", ev
    if dropped > 0 and not close(tot, good_obs, 0):
        return "INCONCLUSIVE", ev
    if dropped > 0 and len(drop_hits) == 0:
        return "VIOLATED", ev
    return "NOT_VIOLATED", ev


def ck_A4b(ctx):
    ic_only = pn(ctx, "V10", "n_finite_ic_only")
    pic_only = pn(ctx, "V10", "n_finite_pic_only")
    pm_kept = pn(ctx, "V09", "pearson_mean_over_kept")
    pm_load = pn(ctx, "V09", "pearson_ic_mean")
    kept = pn(ctx, "V09", "pearson_kept_days")
    dsize = pn(ctx, "V09", "daily_size")
    ev = {"sweep_cases": pn(ctx, "V10", "sweep_cases"),
          "n_finite_both": pn(ctx, "V10", "n_finite_both"), "n_nan_both": pn(ctx, "V10", "n_nan_both"),
          "n_finite_ic_only": ic_only, "n_finite_pic_only": pic_only,
          "pearson_kept_days": kept, "daily_size_on_degenerate_panel": dsize,
          "pearson_mean_over_kept": pm_kept, "pearson_ic_mean_from_payload": pm_load,
          "predicted_algebra": "var(scores)=0 或 var(rets)=0 ⟺ 两处均非有限 ⟹ 两侧分母恒同"}
    if ic_only is None or pic_only is None:
        return "INCONCLUSIVE", ev
    if ic_only > 0 or pic_only > 0:
        return "VIOLATED", ev
    if kept is None or dsize is None or kept != dsize:
        return "VIOLATED", ev
    if not close(pm_kept, pm_load, 1e-9):
        return "VIOLATED", ev
    return "NOT_VIOLATED", ev


def ck_A5a(ctx):
    gsize = pn(ctx, "V11", "grouped_size")
    gcount = pn(ctx, "V11", "groupCount")
    empty = pn(ctx, "V11", "empty_groups")
    ev = {"n": pn(ctx, "V11", "n"), "groupCount": gcount, "grouped_size": gsize,
          "empty_groups": empty, "long_short_spread": pn(ctx, "V11", "long_short_spread"),
          "monotonicity": pv(ctx, "V11", "monotonicity"),
          "codec": "FactorResearchEngine.java:102 Math.min(groupCount, n) 单日只产 g 组；"
                   ":180-183 却按 1..groupCount 全建，空桶 avg=0 且 observations=0"}
    if gsize is None or gcount is None or empty is None:
        return "INCONCLUSIVE", ev
    if gsize != gcount:
        return "INCONCLUSIVE", ev
    return ("VIOLATED" if empty > 0 else "NOT_VIOLATED"), ev


def ck_A5b(ctx):
    spread = pn(ctx, "V11", "long_short_spread")
    recomputed = pn(ctx, "V11", "spread_recomputed_0_minus_g1")
    g1 = pn(ctx, "V11", "g1_avg")
    glast_obs = pn(ctx, "V11", "glast_obs")
    glast = pn(ctx, "V11", "glast_avg")
    agree = close(spread, recomputed, 1e-9)
    ev = {"long_short_spread": spread, "g1_avg": g1, "glast_avg": glast, "glast_obs": glast_obs,
          "recomputed_0_minus_g1": recomputed, "spread_equals_0_minus_g1": agree,
          "codec": "FactorResearchEngine.java:185-186 spread = grouped.last − grouped.first",
          "criterion_tier": "L（论文式 (19) 要求每腿恰 K 只：w=±1/K；空腿使该定义不成立）"}
    if spread is None or recomputed is None or glast_obs is None:
        return "INCONCLUSIVE", ev
    if glast_obs == 0 and close(glast, 0.0) and agree:
        return "VIOLATED", ev
    return "NOT_VIOLATED", ev


def ck_A5c(ctx):
    dto = ctx["text"]["c_dto_obs"]
    fe = ctx["text"]["c_fe_obs"]
    grouped = ctx["text"]["c_fe_grouped"]
    ev = {"dto_exports_group_observations": dto, "frontend_renders_observations": fe,
          "frontend_has_group_table": grouped,
          "caveat": "分组表里「观测数 0」可区分空组；但 long_short_spread 这个标量在别处单独展示，"
                    "无「存在空组」标注 ⟹ 标量仍可被误读"}
    if not dto or not grouped:
        return "INCONCLUSIVE", ev
    return ("NOT_VIOLATED" if fe else "VIOLATED"), ev


def ck_A6a(ctx):
    zeros = {k: pn(ctx, "V14", k) for k in
             ("rank_ic_mean", "rank_ic_std", "rank_ic_ir", "rank_ic_positive_rate",
              "pearson_ic_mean", "long_short_spread")}
    dsize = pn(ctx, "V14", "daily_size")
    ev = {"fields": zeros, "daily_size": dsize, "grouped_size": pn(ctx, "V14", "grouped_size"),
          "monotonicity": pv(ctx, "V14", "monotonicity"),
          "universe_note": pv(ctx, "V14", "universe_note"),
          "partial_distinguishers": "monotonicity='N/A' 与 universe_note 提供了部分可区分信息 ⟹ "
                                    "不得叙述为「完全不可区分」",
          "criterion_tier": "P（工程契约：定义失效时不得输出看似估计值的具体实数）"}
    if dsize is None:
        return "INCONCLUSIVE", ev
    all_zero = all(close(v, 0.0) for v in zeros.values())
    if dsize == 0 and all_zero:
        return "VIOLATED", ev
    return "NOT_VIOLATED", ev


def ck_A6b(ctx):
    idxs = indices(ctx, "V15", "rank_ic_ir")
    cases = []
    firing = []
    for i in idxs:
        m = pn(ctx, "V15", "rank_ic_mean", i)
        sd = pn(ctx, "V15", "rank_ic_std", i)
        ir = pn(ctx, "V15", "rank_ic_ir", i)
        t = pn(ctx, "V15", "t", i)
        rec = {"case_index": i, "T": t, "rank_ic_mean": m, "rank_ic_std": sd, "rank_ic_ir": ir}
        cases.append(rec)
        if m < 0 and close(sd, 0.0) and close(ir, 0.0):
            firing.append(rec)
    ev = {"cases": cases, "firing_cases": firing,
          "codec": "FactorResearchEngine.java:174 icIr = icStd > 1e-12 ? icMean / icStd : 0",
          "asymmetry": "mean>0 且 std=0 时写成 0 属偏保守；mean<0 且 std=0 时真极限为 −∞，写成 0 属偏乐观",
          "reachability": "T=1（仅一个参与统计的截面日）即得 std=0，经正常 API 可达"}
    if not cases:
        return "INCONCLUSIVE", ev
    return ("VIOLATED" if firing else "NOT_VIOLATED"), ev


def ck_A6c(ctx):
    ic_only = pn(ctx, "V10", "n_finite_ic_only")
    pic_only = pn(ctx, "V10", "n_finite_pic_only")
    dsize = pn(ctx, "V14", "daily_size")
    ev = {"n_finite_ic_only": ic_only, "n_finite_pic_only": pic_only,
          "daily_size_on_empty_input": dsize,
          "codec": ":176 pearsonIcMean = dailyPearson.isEmpty() ? 0 : ...；"
                   "该行位于 :166-168 的 if (daily.isEmpty()) return emptySummary 之后",
          "reasoning": "到达 :176 ⟹ daily 非空；若两处 NaN 集合相同（见 A4b）⟹ dailyPearson 非空 "
                       "⟹ 三元表达式的 0 分支恒不执行"}
    if ic_only is None or pic_only is None:
        return "INCONCLUSIVE", ev
    if ic_only > 0 or pic_only > 0:
        return "INCONCLUSIVE", ev
    return "NOT_REACHABLE", ev


def ck_A6d(ctx):
    prod = ctx["text"]["c1_prod"]
    allhits = ctx["text"]["c1_all"]
    db = ctx["db"]
    ev = {"ic_value_lines_production": prod, "ic_value_lines_all_incl_research": allhits,
          "db_total": db.get("total"), "db_non_null": db.get("non_null"), "db_error": db.get("error"),
          "ddl": "V2__research_tables.sql:183 ic_value DOUBLE DEFAULT NULL",
          "note": "全仓唯一的 ic_value 出现处是 DDL 本身，无任何写入路径"}
    if not db.get("available"):
        return "INCONCLUSIVE", ev
    if len(prod) != EXPECT["c1_prod_ic_value_lines"]:
        return "INCONCLUSIVE", ev
    return ("DEAD_COLUMN" if db.get("non_null") == 0 else "INCONCLUSIVE"), ev


def ck_A7a(ctx):
    gap = pn(ctx, "V18", "group_avg_max_gap")
    mono = pv(ctx, "V18", "monotonicity")
    dsize = pn(ctx, "V18", "daily_size")
    var_gt0 = pv(ctx, "V18", "return_variance_gt_0")
    ev = {"group_avg_max_gap": gap, "monotonicity": mono, "daily_size": dsize,
          "return_variance_gt_0": var_gt0, "group_avgs": [
              {"group_no": i, "avg": pn(ctx, "V18", "group_avg", i)}
              for i in indices(ctx, "V18", "group_avg")],
          "codec": "FactorResearchEngine.java:226-249 全相等时 increasing 与 decreasing 同真，"
                   "先判 increasing ⟹ MONOTONIC_INCREASING",
          "criterion_tier": "P（项目自建；论文不涉及）"}
    if gap is None or dsize is None:
        return "INCONCLUSIVE", ev
    if close(gap, 0.0) and dsize >= 1 and mono == "MONOTONIC_INCREASING":
        return "VIOLATED", ev
    return "NOT_VIOLATED", ev


def _k_refs(ctx):
    refs = set()
    for k in ctx["k"]:
        refs.update(k.get("refs", []))
    return refs


def ck_A7b(ctx):
    refs = _k_refs(ctx)
    ev = {"frozen_outcome_for_A7a": "REGISTERED_ONLY（不进总判定）",
          "k_refs": sorted(refs), "k_table": [{"id": k["id"], "refs": k.get("refs", [])} for k in ctx["k"]],
          "frozen_rule": "prereg §6 K8 明确 A7a 成立不构成 REJECTED"}
    if "A7a" in refs:
        k = [x for x in ctx["k"] if "A7a" in x.get("refs", [])]
        if any(x["outcome"] == "REJECTED" for x in k):
            return "VIOLATED", ev
    return "REGISTERED_ONLY", ev


def ck_A8(ctx):
    refs = _k_refs(ctx)
    ev = {"scope_note": "式 (1) 的 close→close 标签口径与项目冻结口径不同，仅记差异 D-2，"
                        "不得作为任何 kill 轴（prereg §3 A8 / §9.6）",
          "k_refs": sorted(refs)}
    if "A8" in refs:
        k = [x for x in ctx["k"] if "A8" in x.get("refs", [])]
        if any(x["outcome"] == "REJECTED" for x in k):
            return "VIOLATED", ev
    return "REGISTERED_ONLY", ev


CHECKERS = {
    "A1a": ck_A1a, "A1b": ck_A1b, "A1c": ck_A1c,
    "A2a": ck_A2a, "A2b": ck_A2b,
    "A3a": ck_A3a, "A3b": ck_A3b, "A3c": ck_A3c,
    "A4a": ck_A4a, "A4b": ck_A4b,
    "A5a": ck_A5a, "A5b": ck_A5b, "A5c": ck_A5c,
    "A6a": ck_A6a, "A6b": ck_A6b, "A6c": ck_A6c, "A6d": ck_A6d,
    "A7a": ck_A7a, "A7b": ck_A7b,
    "A8": ck_A8,
}

# ---------------------------------------------------------------------------
# 金丝雀：每个检查器都必须对「原始字段」的扰动产生可观测的判定变化
# 结构：{"<axis>": {"clean": <原始判定>, "inj": <扰动后判定>, "op": (...), "intent": "..."}}
# op 形式：
#   ("probe_set",   probe, key, idx, value)
#   ("probe_clone", dst_probe, dst_key, src_probe, src_key)   # 逐索引克隆
#   ("text_set",    corpus_name, [lines])
#   ("db_set",      field, value)
#   ("k_add",       {...})
# ---------------------------------------------------------------------------
CANARY = {
    "A1a": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "op": ("probe_clone", "V01", "rank_ic_ir", "V01", "ir_pearson_div_T"),
            "intent": "让 rank_ic_ir 改跟 Pearson 序列走 ⟹ 检查器必须报 VIOLATED"},
    "A1b": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "op": ("text_set", "c3", ["modules/web/src/views/factor/FactorResearch.vue:1:<span>Rank ICIR：{{ fmt4(blk.rank_ic_ir) }}</span>"]),
            "intent": "把裸 ICIR 标签改成带 Rank 限定 ⟹ 检查器必须不再报缺陷"},
    "A1c": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "op": ("text_set", "c3", ["modules/web/src/views/factor/FactorResearch.vue:1:<span>Pearson IC：{{ fmt4(blk.pearson_ic_mean) }}</span>",
                                      "modules/web/src/views/factor/FactorResearch.vue:2:<span>RankICIR：{{ fmt4(blk.rank_ic_ir) }}</span>"]),
            "intent": "同时提供 Pearson IC 与显式 RankICIR 标签 ⟹ 混合配对风险消失"},
    "A2a": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "op": ("probe_set", "V04", "max_abs_engine_vs_eq8", None, 0.1234),
            "intent": "把「实现 vs 式(8)」的最大偏差改成 0.1234 ⟹ 检查器必须报 VIOLATED"},
    "A2b": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "op": ("probe_set", "V05", "max_abs_engine_vs_def", None, 0.01),
            "intent": "把「实现 vs 定义值」的偏差改成 0.01（有并列时实现不再等于定义）"},
    "A3a": {"clean": "CONVENTION_DIVERGENCE", "inj": "NOT_VIOLATED",
            "op": ("probe_clone", "V06", "rank_ic_ir", "V06", "grid_rank_1_div_Tm1"),
            "intent": "把 rank_ic_ir 改成等于 ÷(T−1) 单元（假装项目用样本标准差）⟹ "
                      "检查器必须识别为「与论文一致」"},
    "A3b": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "op": ("probe_set", "V07", "ratio_observed", 0, 1.0),
            "intent": "把两口径之比改成 1.0（不再等于 sqrt(T/(T−1))）"},
    "A3c": {"clean": "CONVENTION_DIVERGENCE", "inj": "NOT_VIOLATED",
            "op": ("probe_clone", "V08", "rank_ic_std", "V08", "std_sample"),
            "intent": "把 rank_ic_std 改成等于样本标准差 ⟹ 检查器必须识别为「与论文一致」"},
    "A4a": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "op": ("text_set", "c_drop", ["modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java:1:block.put(\"dropped_days\", dropped);"]),
            "intent": "注入一行「公开丢弃天数」的字段 ⟹ 丢弃已被披露，检查器必须不再报缺陷"},
    "A4b": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "op": ("probe_set", "V10", "n_finite_pic_only", None, 2),
            "intent": "注入 2 例「pic 有限而 ic 非有限」⟹ 我的代数被证伪"},
    "A5a": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "op": ("probe_set", "V11", "empty_groups", None, 0),
            "intent": "把空组数改成 0 ⟹ 检查器必须不再报缺陷"},
    "A5b": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "op": ("probe_set", "V11", "glast_obs", None, 2),
            "intent": "让顶端组非空 ⟹ 式 (19) 的 K 只腿条件成立，检查器必须不再报缺陷"},
    "A5c": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "op": ("text_set", "c_fe_obs", []),
            "intent": "去掉前端「观测数」列 ⟹ 空组在下游不可区分"},
    "A6a": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "op": ("probe_set", "V14", "long_short_spread", None, 0.02),
            "intent": "让零观测载荷的 spread 非 0 ⟹ 占位零的形态不再成立"},
    "A6b": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "op": ("probe_set", "V15", "rank_ic_ir", 1, -1.0),
            "intent": "让强负 IC 那一例的 ir 变成 −1.0（不再是 0）⟹ 伪造消失"},
    "A6c": {"clean": "NOT_REACHABLE", "inj": "INCONCLUSIVE",
            "op": ("probe_set", "V10", "n_finite_pic_only", None, 3),
            "intent": "证伪「两处 NaN 集合恒同」⟹ 死分支结论撤回为 INCONCLUSIVE"},
    "A6d": {"clean": "DEAD_COLUMN", "inj": "INCONCLUSIVE",
            "op": ("db_set", "non_null", 5),
            "intent": "假装 ic_value 有 5 行非空 ⟹ 死列结论必须撤回"},
    "A7a": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "op": ("probe_set", "V18", "monotonicity", None, "NON_MONOTONIC"),
            "intent": "把退化单调性标签改掉 ⟹ 检查器必须不再报缺陷"},
    "A7b": {"clean": "REGISTERED_ONLY", "inj": "VIOLATED",
            "op": ("k_add", {"id": "KX", "condition": "A7a 成立", "outcome": "REJECTED", "refs": ["A7a"]}),
            "intent": "把 A7a 非法扩入 REJECTED 杀判据 ⟹ 范围冻结被破坏，必须报 VIOLATED"},
    "A8": {"clean": "REGISTERED_ONLY", "inj": "VIOLATED",
            "op": ("k_add", {"id": "KY", "condition": "A8 标签口径差异", "outcome": "REJECTED", "refs": ["A8"]}),
            "intent": "把 A8（范围声明）非法扩入杀判据 ⟹ 必须报 VIOLATED"},
}


def apply_op(ctx, op):
    ctx2 = copy.deepcopy(ctx)
    kind = op[0]
    if kind == "probe_set":
        _, probe, key, idx, val = op
        ctx2["probe"][(probe, key, idx)] = val
    elif kind == "probe_clone":
        _, dp, dk, sp, sk = op
        for k in list(ctx2["probe"].keys()):
            if k[0] == sp and k[1] == sk:
                ctx2["probe"][(dp, dk, k[2])] = ctx2["probe"][k]
    elif kind == "text_set":
        _, name, lines = op
        ctx2["text"][name] = list(lines)
    elif kind == "db_set":
        _, field, val = op
        ctx2["db"][field] = val
    elif kind == "k_add":
        ctx2["k"] = list(ctx2["k"]) + [op[1]]
    else:
        raise ValueError("unknown op: %r" % (op,))
    return ctx2


# ===========================================================================
def verify_config_frozen():
    """config.json 是本轮判据的冻结副本：脚本内常数若与之漂移，则行和校验失败（K9）。"""
    path = os.path.join(HERE, "config.json")
    try:
        with open(path, "r", encoding="utf-8") as fh:
            cfg = json.load(fh)
    except Exception as ex:
        return False, {"error": "%s: %s" % (type(ex).__name__, ex)}
    checks = {
        "variants": cfg.get("variants") == VARIANT,
        "by_axis": cfg.get("by_axis") == BY_AXIS,
        "frozen_axis_counts": cfg.get("frozen_axis_counts") == FROZEN_AXIS_COUNTS,
        "frozen_total": cfg.get("frozen_total") == FROZEN_TOTAL,
        "kill_conditions": cfg.get("kill_conditions") == FROZEN_K,
        "count_expectations": cfg.get("count_expectations") == EXPECT,
        "tolerances": cfg.get("tolerances") == {"TOL": TOL, "EQ8_TIE_TOL": EQ8_TIE_TOL,
                                                "RATIO_TOL": RATIO_TOL, "BH": BH},
    }
    return all(checks.values()), checks


def main():
    global _log_fh
    started = datetime.now()
    _log_fh = open(os.path.join(HERE, "run.log"), "a", encoding="utf-8")
    log("=" * 78)
    log("R006 命名族审计（rank_ic 载荷族）—— 只读；判据冻结于 rounds/R006_prereg.md")
    log("=" * 78)

    result = {"round": "R006", "started_at": started.isoformat(),
              "prereg": "rounds/R006_prereg.md",
              "review": "rounds/R006_review.md",
              "criteria_source": "AlphaEval arXiv:2508.13174v2 (KDD 2026)",
              "readonly_attestation": {"statements_executed": "SELECT only",
                                       "writes_attempted": 0,
                                       "note": "本脚本不含 INSERT/UPDATE/DELETE/DDL；仅 SELECT。"}}

    probe = compile_and_run_probe()
    if probe is None:
        result["fatal"] = "探针编译或运行失败 ⟹ 无法执行任何轴"
        result["total_verdict"] = "INCONCLUSIVE"
        json.dump(result, open(os.path.join(HERE, "result.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=2, default=str)
        log("FATAL: 探针失败，整轮 INCONCLUSIVE")
        return

    # ---- 门 G3：判据对象必须是仓库自己的生产类 ----
    src = str(pv({"probe": probe}, "G3", "engine_code_source"))
    norm = src.replace("\\", "/").rstrip("/")
    want = CLASSES.replace("\\", "/").rstrip("/")
    gate_g3 = norm.lower().endswith(want.lower())
    g3 = {"engine_code_source": src, "expected": CLASSES, "is_repo_classes": gate_g3,
          "class": pv({"probe": probe}, "G3", "engine_class"),
          "classloader": pv({"probe": probe}, "G3", "engine_classloader"),
          "sentinel_spearman_reverse": pn({"probe": probe}, "G3", "sentinel_spearman_reverse"),
          "sentinel_spearman_perm": pn({"probe": probe}, "G3", "sentinel_spearman_perm"),
          "sentinel_pearson_reverse": pn({"probe": probe}, "G3", "sentinel_pearson_reverse")}
    log("G3 engine_code_source = %s  (is_repo_classes=%s)" % (src, gate_g3))
    result["gate_G3"] = g3

    text = build_text_corpora()
    for k, v in text.items():
        log("corpus %s: %d hit(s)" % (k, len(v)))
    db = fetch_db()

    ctx = {"probe": probe, "text": text, "db": db, "k": FROZEN_K}

    # ---- 行和交叉校验（K9）----
    by_axis = {ax: len(axes) for ax, axes in BY_AXIS.items()}
    total_variants = sum(by_axis.values())
    rowsum = {"by_axis": by_axis, "frozen_axis_counts": FROZEN_AXIS_COUNTS,
              "total_variants": total_variants, "frozen_total": FROZEN_TOTAL,
              "declared_checkers": len(CHECKERS),
              "axis_keys_match": sorted(BY_AXIS.keys()) == sorted(FROZEN_AXIS_COUNTS.keys()),
              "variant_map_complete": all(
                  a in VARIANT for axes in BY_AXIS.values() for a in axes) and len(VARIANT) == FROZEN_TOTAL,
              "variant_ids_unique": len(set(VARIANT.values())) == FROZEN_TOTAL}
    rowsum["ok"] = (rowsum["axis_keys_match"] and by_axis == FROZEN_AXIS_COUNTS
                    and total_variants == FROZEN_TOTAL == len(CHECKERS)
                    and rowsum["variant_map_complete"] and rowsum["variant_ids_unique"])
    cfg_ok, cfg_detail = verify_config_frozen()
    rowsum["config_frozen_match"] = cfg_ok
    rowsum["config_frozen_detail"] = cfg_detail
    rowsum["ok"] = rowsum["ok"] and cfg_ok
    log("config.json frozen-match: %s" % cfg_ok)
    log("row-sum check: %s (by_axis=%s total=%d)" % (rowsum["ok"], by_axis, total_variants))
    result["variant_budget"] = rowsum

    # ---- 金丝雀（INJ/CLN）----
    canary_rows = []
    for axis, spec in CANARY.items():
        fn = CHECKERS[axis]
        clean_v, clean_ev = fn(ctx)
        inj_ctx = apply_op(ctx, spec["op"])
        inj_v, inj_ev = fn(inj_ctx)
        ok = (clean_v == spec["clean"] and inj_v == spec["inj"])
        canary_rows.append({"axis": axis, "variant": VARIANT[axis],
                            "clean_expected": spec["clean"], "clean_actual": clean_v,
                            "inj_expected": spec["inj"], "inj_actual": inj_v,
                            "passed": ok, "op": spec["op"][0], "intent": spec["intent"],
                            "clean_evidence_digest": str(clean_ev)[:300],
                            "inj_evidence_digest": str(inj_ev)[:300]})
        log("canary %s(%s): clean %s/%s  inj %s/%s  -> %s"
            % (axis, VARIANT[axis], clean_v, spec["clean"], inj_v, spec["inj"],
               "PASS" if ok else "FAIL"))
    no_canary = [a for a in CHECKERS if a not in CANARY]
    all_canary_pass = all(r["passed"] for r in canary_rows)
    result["canaries"] = {"rows": canary_rows, "checkers": len(CHECKERS),
                          "canaries": len(canary_rows), "checkers_without_canary": no_canary,
                          "all_passed": all_canary_pass}

    # ---- 逐断言判定 ----
    verdicts, evidences = {}, {}
    for axis in CHECKERS:
        v, ev = CHECKERS[axis](ctx)
        verdicts[axis] = v
        evidences[axis] = ev
        log("axis %s (%s): %s" % (axis, VARIANT[axis], v))

    result["axis_verdicts"] = verdicts
    result["axis_variants"] = {a: VARIANT[a] for a in CHECKERS}
    result["axis_evidence"] = evidences

    # ---- 杀死条件 ----
    k_fired = {
        "K1": verdicts["A2a"] == "VIOLATED",
        "K2": verdicts["A3a"] == "CONVENTION_DIVERGENCE",
        "K3": verdicts["A4a"] == "VIOLATED",
        "K4": verdicts["A5b"] == "VIOLATED",
        "K5": verdicts["A6b"] == "VIOLATED",
        "K6": verdicts["A1b"] == "VIOLATED" or verdicts["A1c"] == "VIOLATED",
        "K7": verdicts["A6d"] == "DEAD_COLUMN",
        "K8": verdicts["A7a"] == "VIOLATED",
        "K9": not rowsum["ok"],
        "K10": (not all_canary_pass) or (not gate_g3) or bool(no_canary),
    }
    log("k_fired: %s" % k_fired)

    # ---- 总判定（prereg §6 冻结的合成规则）----
    if k_fired["K9"] or k_fired["K10"]:
        total = "INCONCLUSIVE"
        reason = "K9/K10 触发：行和校验或阳性对照未通过 ⟹ 其余断言结果作废"
    elif k_fired["K1"] or k_fired["K3"] or k_fired["K4"] or k_fired["K5"]:
        total = "REJECTED"
        pairs = [("K1", "A2a"), ("K3", "A4a"), ("K4", "A5b"), ("K5", "A6b")]
        firing = ["%s(%s=%s)" % (k, a, verdicts[a]) for k, a in pairs if k_fired[k]]
        reason = "H0 被杀：%s 触发" % "/".join(firing)
    elif k_fired["K2"]:
        total = "INCONCLUSIVE"
        reason = "仅 K2（ICIR 分母口径差，量级 +3.1%~+5.4%）触发 ⟹ 有偏离但量级不足，不足以判定"
    else:
        total = "NOT_VIOLATED"
        reason = "K1/K3/K4/K5 全部未触发 ⟹ H0 存活（不得叙述为「项目有问题」）"

    result["kill_conditions_fired"] = k_fired
    result["total_verdict"] = total
    result["total_verdict_reason"] = reason
    result["finished_at"] = datetime.now().isoformat()
    log("TOTAL VERDICT = %s" % total)
    log("reason: %s" % reason)

    # ---- 清理编译产物（不留 .class 进仓库）----
    if os.path.isdir(PROBE_OUT):
        shutil.rmtree(PROBE_OUT)
        log("cleaned compiled probe output: %s" % PROBE_OUT)

    with open(os.path.join(HERE, "result.json"), "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2, default=str)
    log("wrote result.json")


if __name__ == "__main__":
    main()
