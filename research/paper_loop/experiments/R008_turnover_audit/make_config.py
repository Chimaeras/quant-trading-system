# -*- coding: utf-8 -*-
"""
从 run_turnover_audit.py 的模块常数生成 config.json（冻结副本）。
生成后 config.json 被提交；运行期 verify_config_frozen() 逐项比对，任何后续漂移都会被记为 K8。
用法：python make_config.py
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run_turnover_audit as R  # noqa: E402  （main() 有 __main__ 守卫，import 不会执行）

cfg = {
    "_provenance": ("由 make_config.py 在冻结时从 run_turnover_audit.py 的模块常数导出；"
                    "运行期 verify_config_frozen() 逐项比对；常数本身的权威文本是 "
                    "rounds/R008_prereg.md（先于本文件与本脚本冻结）"),
    "round": "R008",
    "prereg": "rounds/R008_prereg.md",
    "variants": R.VARIANT,
    "by_axis": R.BY_AXIS,
    "frozen_axis_counts": R.FROZEN_AXIS_COUNTS,
    "frozen_total": R.FROZEN_TOTAL,
    "kill_conditions": R.FROZEN_K,
    "count_expectations": R.EXPECT_COUNTS,
    "verdict_vocab": R.VERDICT_VOCAB,
    "thresholds": {
        "TOL_BIT": R.TOL_BIT, "TH_MAT": R.TH_MAT, "N_GRID": R.N_GRID, "P_GRID": R.P_GRID,
        "RANGE_LO": R.RANGE_LO, "RANGE_HI": R.RANGE_HI,
        "EXISTING_TEST_TOL": R.EXISTING_TEST_TOL,
        "CONTROL_ONE_WAY_FACTOR": R.CONTROL_ONE_WAY_FACTOR,
    },
    "probe_panels_documentation": {
        "_note": ("面板全部在 probe/R008Probe.java 内以构造方式冻结（无随机、无种子、无 DB）；"
                  "两个 Spring Data 仓库由 JDK 动态代理伪造，未预期方法一律抛异常"),
        "V01_4day_full_panel": {"d1": "S01..S10", "d2": "S01..S08,S11,S12",
                                "d3": "S01..S07,S11,S12,S13", "d4": "S01..S07,S14,S15,S16",
                                "absI": [8, 9, 7], "turnover": [0.2, 0.1, 0.3]},
        "V01_panelB_declared": {"d1": "S01..S10", "d2": "S01..S09,S11", "absI": 9},
        "V03_both_short": {"topN": 10, "sets": ["{A,B,C}", "{A,B,C}"], "absI": 3},
        "V04_one_short": {"topN": 10, "prev": 10, "curr": 3, "absI": 3},
        "V05_grid": {"n": [8, 10, 12], "p": [2, 3, 4], "cells": 18,
                     "kinds": ["both(prev=curr=p)", "one(prev=n, curr=p, absI=p)"]},
        "V06_degenerate": {"panels": ["signal_days=1", "signal_days=0(empty)"],
                           "expected_daily_len": 0},
        "V07_multi_strategy": {"strategies": ["P1", "P2"], "days": [1, 2],
                               "per_strategy_sets_identical_across_days": True,
                               "P1_vs_P2_disjoint": True},
        "V09_same_key_two_results": {"resultId": ["R_X(10:00)", "R_Y(11:00)"],
                                     "proxy_returns": "createdAt DESC（模拟查询方法名）"},
        "V12_reverse_solve": {"prev": "{A,B,C,D}", "curr": "{A,E,F,G}", "absI": 1,
                              "topN_grid": [None, 0, -5, 3, 10, 4],
                              "exact_capable": [None, 0, -5, 10, 4],
                              "note": "topN=3 在 round4 的 1e-4 量化下不可精确反解，只作描述"},
        "V18_value_universe": "全部变体中被生产实现返回过的每一个 turnover/avg/max/min 值",
        "V21_tie": {"d1": "S01..S09,X", "d2_rankNo": "[1..9, 10, 10, 10]",
                    "tie_members": ["X", "Y", "Z"], "orders": ["XYZ", "ZYX"]},
    },
    "canary_mode": {
        "harness": "normalized-pair",
        "cln_and_inj_both_on_normalized_ctx": True,
        "rule": ("每个检查器恰好一对 CLN/INJ；CLN 写到检查器实际读取的那个键；"
                 "两条期望值在运行前确定；要求 clean != inj"),
        "entries": 21,
    },
    "readonly": {
        "db_statements": 0,
        "production_files_modified": 0,
        "test_files_modified": 0,
        "files_written": ["run.log", "result.json", "probe/out/*（运行后删除）"],
    },
    "not_applicable_clauses": {
        "interval_lower_bound": ("prereg §5.4：本轮零抽样、零随机种子、全部为构造面板、"
                                 "计数为穷举枚举 ⟹ 区间下界类判据不适用；"
                                 "真实短员日占比不可测 ⟹ 登记 B-R008-01"),
        "lookahead_survivorship": ("prereg §5.2：本轮不产生收益序列、不做选样、不算 IC ⟹ "
                                   "前视/幸存者偏差对测量不适用；被审计对象自身 note 声明了"
                                   "幸存者偏差，本轮只登记其存在"),
    },
}

out = os.path.join(HERE, "config.json")
with io.open(out, "w", encoding="utf-8", newline="\n") as fh:
    json.dump(cfg, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print("wrote %s (%d bytes)" % (out, os.path.getsize(out)))
