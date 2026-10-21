#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R009 冻结配置生成器 —— 只读。

把 rounds/R009_prereg.md §3.5/§4/§5/§6/§7 的冻结期望落成 config.json，
供 run_pearson_audit.py 运行期逐项比对（任一不等 ⟹ K8 ⟹ INCONCLUSIVE）。

本文件**不**测量任何东西、**不**改任何生产代码；它只是冻结文本的机械转录。
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------
# §4 轴 → 变体映射（冻结）
# ---------------------------------------------------------------------------
AXES = {
    "A1": ["A1a", "A1b"],
    "A2": ["A2a", "A2b", "A2c", "A2d"],
    "A3": ["A3a", "A3b", "A3c"],
    "A4": ["A4a", "A4b", "A4c"],
    "A5": ["A5a", "A5b"],
    "A6": ["A6a", "A6b"],
    "A7": ["A7a", "A7b"],
}
VARIANT = {
    "A1a": "V01", "A1b": "V02",
    "A2a": "V03", "A2b": "V04", "A2c": "V05", "A2d": "V06",
    "A3a": "V07", "A3b": "V08", "A3c": "V09",
    "A4a": "V10", "A4b": "V11", "A4c": "V12",
    "A5a": "V13", "A5b": "V14",
    "A6a": "V15", "A6b": "V16",
    "A7a": "V17", "A7b": "V18",
}
FROZEN_AXIS_COUNTS = {k: len(v) for k, v in AXES.items()}
FROZEN_TOTAL = sum(FROZEN_AXIS_COUNTS.values())          # 18
NON_KILLING_AXES = ["A2c", "A2d", "A3c", "A7a"]

VERDICT_VOCAB = [
    "NOT_VIOLATED", "VIOLATED", "CONVENTION_DIVERGENCE", "CONTRACT_AMBIGUOUS",
    "CONTRACT_MISLABEL", "REGISTERED_ONLY", "NOT_REACHABLE", "INCONCLUSIVE",
]

# ---------------------------------------------------------------------------
# §5 阈值与常数（冻结）
# ---------------------------------------------------------------------------
THRESHOLDS = {
    "TOL_BIT": 1e-12,
    "TOL_RHO": 1e-12,
    "GUARD_T": 1e-18,
    "CONTROL_PLUS_INF": 9.223372036854776e14,
    "SCALE_C": 1e-10,
    "SCALE_GRID": [8e-11, 1e-10, 1.2e-10],
    "N_GRID": [8, 9, 10, 11, 12],
    "N_PANEL_MIN": 10,
    "RHO_LO": -1.0,
    "RHO_HI": 1.0,
    "EXISTING_TEST_TOL": 1e-12,
    "MIN_SCORE_GAP_ASSUMED": 1e-4,
}

# §4 冻结的精确算术值（panel_design_selfcheck 用）
ARITH = {
    "var_seq10": 82.5,
    "var_seq10_scaled_c": 8.25e-19,          # 82.5 * (1e-10)^2
    "var_seq11_scaled_c": 1.1000000000000003e-18,
    "var_seq12_scaled_c": 1.43e-18,
    "rank_var_seq10": 82.5,
}

# ---------------------------------------------------------------------------
# §6 K 表（冻结；逐字转录 prereg §6.1）
# ---------------------------------------------------------------------------
KILL_CONDITIONS = {
    "K1": {"cond": "A2a == VIOLATED or A2b == VIOLATED", "result": "REJECTED"},
    "K2": {"cond": "A4a == VIOLATED", "result": "REJECTED"},
    "K3": {"cond": "A3a == VIOLATED", "result": "REJECTED"},
    "K4": {"cond": "A5a == CONTRACT_MISLABEL", "result": "CONTRACT_MISLABEL"},
    "K5": {"cond": "A5b == VIOLATED", "result": "CONTRACT_MISLABEL"},
    "K6": {"cond": "A6a == CONTRACT_AMBIGUOUS", "result": "CONTRACT_AMBIGUOUS"},
    "K7": {"cond": "any of {A3b, A4b, A4c, A6b, A7b} not in {NOT_VIOLATED}",
           "result": "REGISTERED_ONLY"},
    "K8": {"cond": "row-sum / variant-map / config freeze mismatch", "result": "INCONCLUSIVE"},
    "K9": {"cond": "positive-control or probe self-check failed, or out-of-vocab verdict",
           "result": "INCONCLUSIVE"},
    "K10": {"cond": "determinism gate failed", "result": "INCONCLUSIVE"},
}
SHORT_CIRCUIT = [
    ["K8", "K9", "K10"],        # -> INCONCLUSIVE
    ["K1", "K2", "K3"],         # -> REJECTED
    ["K4", "K5"],               # -> CONTRACT_MISLABEL
    ["K6"],                     # -> CONTRACT_AMBIGUOUS
    ["K7"],                     # -> REGISTERED_ONLY
]
H0_ALIVE_COND = "not K1 and not K2 and not K3"

# ---------------------------------------------------------------------------
# §7 计数断言 C1–C20（冻结期望；由预注册时的只读扫描取得）
# 口径：src 排除 target/dist/node_modules/__pycache__/build/out/.git；raw 不排除构建产物。
# ---------------------------------------------------------------------------
COUNT_EXPECTATIONS = {
    "C1": {"src": 11, "raw": 17, "pattern": "pearson_ic_mean",
           "scope": "modules", "exts": "ALL", "by_file": {
               "FactorResearchService.java": 5, "factor.ts": 3, "FactorResearch.vue": 3}},
    "C2": {"src": 5, "raw": None, "pattern": "pearson_ic_mean",
           "scope": "modules/backend-java/src", "exts": (".java",),
           "by_file": {"FactorResearchService.java": 5}},
    "C3": {"src": 6, "raw": None, "pattern": "pearson_ic_mean",
           "scope": "modules/web/src", "exts": (".vue", ".ts"),
           "by_file": {"factor.ts": 3, "FactorResearch.vue": 3}},
    "C4": {"src": 41, "raw": 53, "pattern": "(?i)pearson", "scope": "modules", "exts": "ALL",
           "by_file": {"FactorResearchEngine.java": 13, "FactorResearchService.java": 10,
                       "FactorResearchEngineTest.java": 7, "FactorResearch.vue": 6,
                       "factor.ts": 4, "FactorResearchController.java": 1}},
    "C5": {"src": 7, "raw": 7, "pattern": "pearsonCorrelation", "scope": "modules", "exts": "ALL",
           "by_file": {"FactorResearchEngineTest.java": 5, "FactorResearchEngine.java": 2}},
    "C6": {"src": 5, "raw": None, "pattern": "pearsonCorrelation",
           "scope": "modules/backend-java/src/test", "exts": (".java",),
           "by_file": {"FactorResearchEngineTest.java": 5}},
    "C7": {"src": 4, "raw": None, "pattern": "dailyPearson", "scope": "modules", "exts": "ALL",
           "by_file": {"FactorResearchEngine.java": 4}},
    "C8": {"src": 15, "raw": None, "pattern": "DailyRankIc", "scope": "modules", "exts": "ALL",
           "by_file": {"FactorResearchEngine.java": 5, "FactorResearchService.java": 3,
                       "factor.ts": 3, "ScoringBacktestResponse.java": 2,
                       "ScoringBacktestService.java": 2}},
    "C9": {"src": 6, "raw": None, "pattern": "spearmanRankCorrelation", "scope": "modules",
           "exts": "ALL", "by_file": {"FactorResearchEngine.java": 4,
                                      "FactorResearchEngineTest.java": 2}},
    "C10": {"src": 19, "raw": 27, "pattern": "monotonicity", "scope": "modules", "exts": "ALL",
            "by_file": {"FactorResearchService.java": 7, "FactorResearch.vue": 4,
                        "FactorResearchEngine.java": 3, "FactorResearchEngineTest.java": 3,
                        "factor.ts": 2}},
    "C11": {"src": 0, "raw": None,
            "pattern": "pearson_(sample|count|n|observations|support|days|size)",
            "scope": "modules", "exts": "ALL", "by_file": {}},
    "C12": {"src": 0, "raw": 0, "pattern": "(?i)pearson", "scope": "modules", "exts": (".sql",),
            "by_file": {}, "raw_scope": "modules"},
    "C13": {"src": 2, "raw": None, "pattern": "1e-18",
            "scope": "modules/backend-java/src", "exts": (".java",),
            "by_file": {"FactorResearchEngine.java": 2}},
    "C14": {"src": 0, "raw": None, "pattern": "(?i)pearson", "scope": ".", "exts": (".sql",),
            "by_file": {}},
    "C15": {"src": 2, "raw": None, "pattern": "方差为 0",
            "scope": "modules/backend-java/src", "exts": (".java",),
            "by_file": {"FactorResearchEngine.java": 2}},
    "C16": {"src": 1, "raw": None, "pattern": "pearson_ic(?!_mean)", "scope": "modules",
            "exts": "ALL", "by_file": {"FactorResearch.vue": 1}},
    "C17": {"src": 12, "raw": None, "pattern": "Double\\.isFinite",
            "scope": "modules/backend-java/src", "exts": (".java",),
            "by_file": {"ScoringBacktestService.java": 3, "CrossSectionNeutralizer.java": 3,
                        "FactorResearchEngine.java": 2, "CodexShortCycleScoringEngine.java": 2,
                        "AdjustFactorDerivationService.java": 1, "T1CandidateService.java": 1}},
    "C18": {"src": 23, "raw": None, "pattern": "1e-1[28]",
            "scope": "modules/backend-java/src", "exts": (".java",),
            "by_file": {"ProbabilityCalibrationService.java": 5, "TechnicalIndicators.java": 4,
                        "FactorResearchEngine.java": 3, "FactorCollinearityChecker.java": 3,
                        "CalibratorService.java": 2, "LogisticModelTrainer.java": 2,
                        "PredictionService.java": 1, "T1CandidateService.java": 1,
                        "CrossSectionNeutralizer.java": 1, "DynamicWeightingService.java": 1}},
    "C19": {"src": 22, "raw": None, "pattern": "pearson",
            "scope": "modules/backend-java/src", "exts": (".java",),
            "by_file": {"FactorResearchService.java": 8, "FactorResearchEngine.java": 8,
                        "FactorResearchEngineTest.java": 6}},
    "C20": {"src": 15, "raw": None, "pattern": "NaN",
            "scope": "modules/backend-java/src/main/java/com/quant/service/backtest",
            "exts": (".java",),
            "by_file": {"StrategyBacktestEngine.java": 9, "FactorResearchEngine.java": 6}},
}

# C1/C4/C10 之外不测 raw（prereg §7 冻结声明）
RAW_MEASURED = ["C1", "C4", "C5", "C10", "C12"]

# ---------------------------------------------------------------------------
# §3.6 金丝雀期望（冻结：clean / inj 期望词在运行前写死）
# ---------------------------------------------------------------------------
CANARIES = {
    "A1a": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "CLN：三个自检门全绿；INJ：sentinel_defect_present=0（探针没跑到带守卫的实现）"},
    "A1b": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "CLN：panel_design_selfcheck 全通过；INJ：把 V03 的面板结构谓词改成不符"},
    "A2a": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "CLN：rho_p1s 写成 1.0（尺度不变性成立）；INJ：恢复实测 NaN"},
    "A2b": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "CLN：rho_n10 写成 1.0（与 n12 一致）；INJ：恢复实测 NaN"},
    "A2c": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "CLN：判据来源摘录存在；INJ：摘录缺失（判据来源不支持该定义）"},
    "A2d": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "CLN：网格 15 格齐全；INJ：只报 14 格"},
    "A3a": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "CLN：support_gap=0（支撑集无缺口）；INJ：恢复实测 gap=1"},
    "A3b": {"clean": "NOT_VIOLATED", "inj": "REGISTERED_ONLY",
            "intent": "CLN：C11 命中 1（存在支撑计数字段）；INJ：C11 命中 0（无字段）"},
    "A3c": {"clean": "REGISTERED_ONLY", "inj": "NOT_REACHABLE",
            "intent": "CLN：DB 可达且有行；INJ：DB 不可达"},
    "A4a": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "CLN：两份载荷不同（不可定义与真 0 可区分）；INJ：两份载荷逐字符相同"},
    "A4b": {"clean": "NOT_VIOLATED", "inj": "NOT_REACHABLE",
            "intent": "CLN：round4(NaN) != 0.0；INJ：round4(NaN)=0.0 且该键有限（NaN 不可达）"},
    "A4c": {"clean": "NOT_VIOLATED", "inj": "CONVENTION_DIVERGENCE",
            "intent": "CLN：两个降级分支的标记方式一致；INJ：一个用 status、另一个靠缺键"},
    "A5a": {"clean": "NOT_VIOLATED", "inj": "CONTRACT_MISLABEL",
            "intent": "CLN：javadoc 含量级限定词；INJ：无限定词且 A2 已 VIOLATED"},
    "A5b": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "CLN：legacy 空分支不缺失声明的键；INJ：缺失且可达"},
    "A6a": {"clean": "NOT_VIOLATED", "inj": "CONTRACT_AMBIGUOUS",
            "intent": "CLN：各发射点口径一致或有结构化区分字段；INJ：两处口径不同且都缺区分字段"},
    "A6b": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "CLN：秩有限 ⟹ Pearson 有限（R006 A4b 的等价成立）；INJ：秩有限而 Pearson NaN"},
    "A7a": {"clean": "NOT_VIOLATED", "inj": "REGISTERED_ONLY",
            "intent": "CLN：既有测试含尺度维度用例；INJ：只覆盖尺度 1"},
    "A7b": {"clean": "NOT_VIOLATED", "inj": "REGISTERED_ONLY",
            "intent": "CLN：导出列含缺测分支；INJ：纯取值"},
}
CHECKERS = sorted(CANARIES.keys())

# ---------------------------------------------------------------------------
# §3.6.4 panel_design_selfcheck 的冻结结构谓词
# ---------------------------------------------------------------------------
PANEL_DESIGN = {
    "V03": [
        {"path": ["probe", "V03", "var_p1"], "expect": ARITH["var_seq10"], "tol": 1e-9},
        {"path": ["probe", "V03", "var_p1s"], "expect": ARITH["var_seq10_scaled_c"], "rel": 1e-9},
        {"path": ["probe", "V03", "rank_p1"], "expect": 1.0, "tol": 1e-12},
        {"path": ["probe", "V03", "rank_p1s"], "expect": 1.0, "tol": 1e-12},
        {"path": ["probe", "V03", "control_rank_variance_n10"], "expect": ARITH["rank_var_seq10"],
         "tol": 1e-12},
        {"path": ["probe", "V03", "var_p1s_positive"], "expect": 1, "tol": 0},
    ],
    "V04": [
        {"path": ["probe", "V04", "var_n10"], "expect": ARITH["var_seq10_scaled_c"], "rel": 1e-9},
        {"path": ["probe", "V04", "var_n11"], "expect": ARITH["var_seq11_scaled_c"], "rel": 1e-9},
        {"path": ["probe", "V04", "var_n12"], "expect": ARITH["var_seq12_scaled_c"], "rel": 1e-9},
        {"path": ["probe", "V04", "rank_n10"], "expect": 1.0, "tol": 1e-12},
        {"path": ["probe", "V04", "rank_n11"], "expect": 1.0, "tol": 1e-12},
        {"path": ["probe", "V04", "rank_n12"], "expect": 1.0, "tol": 1e-12},
    ],
    "V06": [
        {"path": ["probe", "V06", "cells"], "expect": 15, "tol": 0},
    ],
    "V07": [
        {"path": ["probe", "V07", "cross_section_days"], "expect": 2, "tol": 0},
        {"path": ["probe", "V07", "daily_size"], "expect": 2, "tol": 0},
        {"path": ["probe", "V07", "rank_support_days"], "expect": 2, "tol": 0},
        {"path": ["probe", "V07", "pearson_support_days"], "expect": 1, "tol": 0},
        {"path": ["probe", "V07", "total_observations"], "expect": 20, "tol": 0},
    ],
    "V10": [
        {"path": ["probe", "V10", "var_p4z"], "expect": ARITH["var_seq10"], "tol": 1e-9},
        {"path": ["probe", "V10", "var_p4u"], "expect": ARITH["var_seq10_scaled_c"], "rel": 1e-9},
        {"path": ["probe", "V10", "cross_days_p4z"], "expect": 1, "tol": 0},
        {"path": ["probe", "V10", "cross_days_p4u"], "expect": 1, "tol": 0},
        {"path": ["probe", "V10", "total_obs_p4z"], "expect": 10, "tol": 0},
        {"path": ["probe", "V10", "total_obs_p4u"], "expect": 10, "tol": 0},
        {"path": ["probe", "V10", "rank_days_p4z"], "expect": 1, "tol": 0},
        {"path": ["probe", "V10", "rank_days_p4u"], "expect": 1, "tol": 0},
        {"path": ["probe", "V10", "pearson_support_days_p4z"], "expect": 1, "tol": 0},
        {"path": ["probe", "V10", "pearson_support_days_p4u"], "expect": 0, "tol": 0},
    ],
    "V11": [
        {"path": ["probe", "V11", "round4_pinf"], "expect": THRESHOLDS["CONTROL_PLUS_INF"],
         "rel": 1e-12},
        {"path": ["probe", "V11", "round4_ninf"], "expect": -THRESHOLDS["CONTROL_PLUS_INF"],
         "rel": 1e-12},
    ],
    "V12": [
        {"path": ["probe", "V12", "snapshot_obsy_n_keys"], "expect": 18, "tol": 0},
        {"path": ["probe", "V12", "snapshot_empty_n_keys"], "expect": 19, "tol": 0},
        {"path": ["probe", "V12", "unavailable_n_keys"], "expect": 18, "tol": 0},
        {"path": ["probe", "V12", "empty_net_n_keys"], "expect": 14, "tol": 0},
        {"path": ["probe", "V12", "net_n_keys"], "expect": 15, "tol": 0},
        {"path": ["probe", "V12", "empty_net_has_monotonicity"], "expect": 0, "tol": 0},
        {"path": ["probe", "V12", "net_has_monotonicity"], "expect": 1, "tol": 0},
        {"path": ["probe", "V12", "unavailable_has_status"], "expect": 1, "tol": 0},
        {"path": ["probe", "V12", "empty_net_has_status"], "expect": 0, "tol": 0},
    ],
    "V15": [
        {"path": ["probe", "V15", "snapshot_metric_scope"], "expect": "RAW", "tol": 0},
        {"path": ["probe", "V15", "net_metric_scope"], "expect": "COST_APPROX", "tol": 0},
        {"path": ["probe", "V15", "empty_net_metric_scope"], "expect": "COST_APPROX", "tol": 0},
        {"path": ["probe", "V15", "snapshot_has_universe_note"], "expect": 1, "tol": 0},
        {"path": ["probe", "V15", "net_has_universe_note"], "expect": 0, "tol": 0},
    ],
}

# ---------------------------------------------------------------------------
# §4 A3c：唯一一条只读普查 SQL（冻结）
# ---------------------------------------------------------------------------
A3C_SQL = (
    "SELECT signal_date,"
    " COUNT(*) AS n_rows,"
    " COUNT(final_score) AS n_scored,"
    " SUM(final_score*final_score)"
    "   - SUM(final_score)*SUM(final_score)/COUNT(final_score) AS sxx"
    " FROM feature_snapshot_row"
    " WHERE final_score IS NOT NULL"
    " GROUP BY signal_date"
    " ORDER BY sxx ASC"
)
A3C_EXPECTED_COLUMNS = ["signal_date", "n_rows", "n_scored", "sxx"]

# ---------------------------------------------------------------------------
# 文本测量的冻结位置（行号 1-based，闭区间）
# ---------------------------------------------------------------------------
SRC_LOCATIONS = {
    "engine": "modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java",
    "service": "modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java",
    "engine_test": "modules/backend-java/src/test/java/com/quant/service/backtest/FactorResearchEngineTest.java",
    "factor_ts": "modules/web/src/api/factor.ts",
    "vue": "modules/web/src/views/factor/FactorResearch.vue",
    "review": "research/paper_loop/rounds/R009_review.md",
}
SRC_RANGES = {
    "pearson_javadoc": ["engine", 49, 51],
    "spearman_javadoc": ["engine", 66, 68],
    "guard_lines": ["engine", 314, 316],
    "variance_lines": ["engine", 304, 313],
    "summarize_gate": ["engine", 149, 158],
    "pearson_mean_lines": ["engine", 176, 177],
    "round4_lines": ["service", 1030, 1032],
    "legacy_empty_branch": ["service", 660, 672],
    "legacy_normal_branch": ["service", 655, 797],
    "ts_pearson_required_1": ["factor_ts", 100, 120],
    "ts_pearson_required_2": ["factor_ts", 190, 215],
    # prereg §4 A5b 写的是 ":206-215 的 cost_net_ic?"，实测该块为 :208-217（`cost_net_ic?: {` 起、
    # 闭合 `}` 止）⟹ 预注册行号偏差记报告附录 A；此处用**实测块范围**做该块的字段检查，
    # 冻结的 190-215 仍用于「外层的 pearson_ic_mean 是否必需」这一断言。
    "ts_costnet_block": ["factor_ts", 208, 217],
    "csv_export": ["vue", 495, 520],
    "engine_test_all": ["engine_test", 1, 200],
}

CONFIG = {
    "round": "R009",
    "baseline_commit": "f139fbfb",
    "frozen_at": "2026-09-14 22:35 (+0800)",
    "subject": "pearson_ic_mean / pearsonCorrelation / dailyPearson",
    "verdict_vocab": VERDICT_VOCAB,
    "axes": AXES,
    "variant": VARIANT,
    "frozen_axis_counts": FROZEN_AXIS_COUNTS,
    "frozen_total": FROZEN_TOTAL,
    "max_variants": 18,
    "non_killing_axes": NON_KILLING_AXES,
    "thresholds": THRESHOLDS,
    "arith": ARITH,
    "kill_conditions": KILL_CONDITIONS,
    "short_circuit": SHORT_CIRCUIT,
    "h0_alive_condition": H0_ALIVE_COND,
    "count_expectations": COUNT_EXPECTATIONS,
    "raw_measured": RAW_MEASURED,
    "canaries": CANARIES,
    "checkers": CHECKERS,
    "panel_design": PANEL_DESIGN,
    "a3c_sql": A3C_SQL,
    "a3c_expected_columns": A3C_EXPECTED_COLUMNS,
    "src_locations": SRC_LOCATIONS,
    "src_ranges": SRC_RANGES,
}


def main():
    axes_total = sum(len(v) for v in AXES.values())
    assert axes_total == FROZEN_TOTAL == 18, axes_total
    assert len(VARIANT) == 18, len(VARIANT)
    assert len(CHECKERS) == 18, len(CHECKERS)
    out = os.path.join(HERE, "config.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(CONFIG, f, ensure_ascii=False, indent=2, sort_keys=False)
    print("wrote %s (axes=%d total=%d checkers=%d)" % (out, axes_total, FROZEN_TOTAL, len(CHECKERS)))


if __name__ == "__main__":
    main()
