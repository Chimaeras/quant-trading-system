# -*- coding: utf-8 -*-
"""
从 run_uncertainty_audit.py 的模块常数生成 config.json（冻结副本）。
生成后 config.json 被提交；运行期 verify_config_frozen() 逐项比对，任何后续漂移都会被记为 K8/K9。
用法：python make_config.py
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run_uncertainty_audit as R  # noqa: E402  （main() 有 __main__ 守卫，import 不会执行）

cfg = {
    "_provenance": ("由 make_config.py 在冻结时从 run_uncertainty_audit.py 的模块常数导出；"
                    "运行期 verify_config_frozen() 逐项比对；常数本身的权威文本是 "
                    "rounds/R007_prereg.md（先于本文件与本脚本冻结）"),
    "round": "R007",
    "prereg": "rounds/R007_prereg.md",
    "variants": R.VARIANT,
    "by_axis": R.BY_AXIS,
    "frozen_axis_counts": R.FROZEN_AXIS_COUNTS,
    "frozen_total": R.FROZEN_TOTAL,
    "kill_conditions": R.FROZEN_K,
    "count_expectations": R.EXPECT_COUNTS,
    "verdict_vocab": R.VERDICT_VOCAB,
    "thresholds": {
        "TOL": R.TOL, "TOL_CI": R.TOL_CI, "TOL_REL_A5A": R.TOL_REL_A5A, "TH_A2C": R.TH_A2C,
        "TH_A3C": R.TH_A3C, "TH_A5C_COV": R.TH_A5C_COV, "TH_A5C_CP_ALPHA": R.TH_A5C_CP_ALPHA,
        "EXISTING_TEST_TOL": R.EXISTING_TEST_TOL, "Z95": R.Z95,
    },
    "simulation": {
        "REPS_COVER": R.REPS_COVER, "REPS_CLAMP": R.REPS_CLAMP, "REPS_MEDIAN": R.REPS_MEDIAN,
        "BOOT_ITERS": R.BOOT_ITERS, "BOOT_SEED": R.BOOT_SEED,
    },
    "probe_grids_documentation": {
        "_note": "以下网格与 DGP 冻结在 probe/R007Probe.java 内（BASE_SEED/NS/HS/HS_EXTRAP/DGPS）",
        "ns": [5, 10, 17, 27],
        "hs_main": [1, 2, 3, 5],
        "hs_extrapolation": [1, 2, 3, 5, 10, 20],
        "dgps": ["IID_NORMAL", "AR1_0.2", "AR1_0.5", "AR1_0.7", "MA_equal_hm1"],
        "dgps_posthoc_negative_ar": ["AR1_-0.3", "AR1_-0.5", "AR1_-0.7"],
        "base_seed": 20260914,
        "cells_v08_v09": 120,
        "cells_v15_coverage": 80,
        "coverage_grid_note": "80 单元 = n∈{5,10,17,27} × h∈{1,2,3,5} × 5 DGP；每单元 2 臂（block bootstrap / HAC）",
    },
    "posthoc_probes_not_in_verdict": {
        "V05p": "负自相关 DGP 下的钳位发生率（A2c 网格外补充）",
        "V09p": "配对比值中位数（A3c 统计量口径对照）",
        "_rule": "prereg §4：posthoc_* 一律不参与 total verdict 合成，只写入报告与附录",
    },
    "canary_mode": {
        "harness": "normalized-pair",
        "cln_and_inj_both_on_normalized_ctx": True,
        "rule": "每个检查器恰好一对 CLN/INJ；两条期望值在运行前确定；要求 clean != inj",
        "entries": 21,
    },
    "readonly": {
        "db_statements": 0,
        "production_files_modified": 0,
        "test_files_modified": 0,
        "files_written": ["run.log", "result.json", "probe/out/*（运行后删除）"],
    },
}

out = os.path.join(HERE, "config.json")
with io.open(out, "w", encoding="utf-8", newline="\n") as fh:
    json.dump(cfg, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print("wrote %s (%d bytes)" % (out, os.path.getsize(out)))
