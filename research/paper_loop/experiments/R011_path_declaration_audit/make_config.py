#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R011 冻结配置生成器：把 `rounds/R011_prereg.md` 的期望值写成机器可读的 config.json。

设计纪律（对齐 R007/R008/R009/R010）：
  1. 本文件**只承载预注册里已经写死的期望**，不新增任何判据、阈值或容差。
  2. 面板值在此**一次性算成字面量**并写入 config.json 与 panel_R011.txt；
     探针**不得**在运行时重新按公式生成（防「公式与实现不一致」的静默偏差）。
  3. 任何与 R011_prereg.md 不一致之处，以 R011_prereg.md 为准。

用法：python make_config.py   （幂等；重复运行结果逐字节相同）
"""
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))

# ── 冻结常量（R011_prereg.md §3.3） ─────────────────────────────────────────────
TOL = 1e-15
TOL_INT = 0
TOL_IC = 1e-12
DET_BITS = 64
G3_SUFFIX = "modules/backend-java/target/classes"
PANEL_DAYS = 5
GRID_CELLS = 27
CANARY_PAIRS = 20

# ── 冻结面板（R011_prereg.md §5.4） ────────────────────────────────────────────
# score_i = i（i=1..n，整数、无并列）；fwd_i = A*x^2 + B*x + C*sin(3i)，x=(i-(n+1)/2)/n
PANEL_N = [12, 10, 9, 20, 6]
PANEL_A = 0.040
PANEL_B = 0.006
PANEL_C = 0.0015


def build_panel():
    """返回 [{day, n, rows:[(score, fwd), ...]}, ...]，fwd 为字面量。"""
    panel = []
    for idx, n in enumerate(PANEL_N):
        rows = []
        for i in range(1, n + 1):
            x = (i - (n + 1) / 2.0) / n
            fwd = PANEL_A * x * x + PANEL_B * x + PANEL_C * math.sin(3.0 * i)
            rows.append((float(i), float(fwd)))
        panel.append({"day": "D%d" % (idx + 1), "n": n, "rows": rows})
    return panel


# ── 冻结网格（R011_prereg.md §5.4） ────────────────────────────────────────────
GRID_HOLDING = [1, 3, 5]
GRID_GROUP_COUNT = [3, 5, 10]
GRID_MIN_SIZE = [5, 10, 20]

# ── 冻结的算术期望（R011_prereg.md §3.4） ──────────────────────────────────────
# (A-1) 进入统计的日数与 minCrossSectionSize 的关系
EXPECTED_DAYS_BY_MIN_SIZE = {5: 5, 10: 3, 20: 1}
EXPECTED_INCLUDED_DAYS = {
    5: ["D1", "D2", "D3", "D4", "D5"],
    10: ["D1", "D2", "D4"],
    20: ["D4"],
}
# (A-2) 每日实际组数 g = min(J, n)
EXPECTED_GROUPS_PER_DAY = {
    "D1": {3: 3, 5: 5, 10: 10},
    "D2": {3: 3, 5: 5, 10: 10},
    "D3": {3: 3, 5: 5, 10: 9},
    "D4": {3: 3, 5: 5, 10: 10},
    "D5": {3: 3, 5: 5, 10: 6},
}
# (A-2b) J=10 时第 10 桶的参与日（只有 n>=10 的日才产得出第 10 桶）
EXPECTED_BUCKET10_DAYS_J10 = ["D1", "D2", "D4"]
# (A-2c) 静默钳位的日数（J=10）
EXPECTED_CLAMP_DAYS_J10 = 2

# ── 冻结的静态计数期望（R011_prereg.md §3.4 A-4 / §8） ─────────────────────────
EXPECTED_COUNTS = {
    "summarize_prod_call_sites": 5,
    "summarize_test_call_sites": 3,
    "holding_days_emission_sites": 2,
    "group_count_emission_sites": 1,
    "min_size_emission_sites": 0,
    "horizons_enumeration_sites": 1,
    "group_count_backend_enum_sites": 0,
    "group_count_distinct_definition_sources": 2,
    "min_size_declaring_files": 2,
    "min_size_shared_declaration_sites": 0,
    "min_size_config_sites": 0,
    "response_group_count_fields": 0,
    "grouped_returns_emission_sites": 2,
}

# ── 冻结的锚点（R011_prereg.md §3.4 A-3） ─────────────────────────────────────
ANCHORS = {
    "A1_spearman_perfect_positive": 1.0,
    "A2_spearman_perfect_negative": -1.0,
    "A3_monotonic_increasing": "MONOTONIC_INCREASING",
    "A3_spread_sign": "positive",
}

# ── 冻结的变体表（R011_prereg.md §3.2 / §4） ───────────────────────────────────
# (variant, axis, expected_verdict)  —— 期望值逐条来自预注册 §4 的表格
VARIANTS = [
    ("V01", "A1", "NOT_VIOLATED"),
    ("V02", "A2", "RECOVERABLE"),
    ("V03", "A2", "NOT_RECOVERABLE_ABSENT_KEY"),
    ("V04", "A2", "NOT_RECOVERABLE_ABSENT_KEY"),
    ("V05", "A2", "RECOVERABLE"),
    ("V06", "A3", "ENUMERABLE_SINGLE_SITE"),
    ("V07", "A3", "NOT_ENUMERABLE"),
    ("V08", "A3", "NOT_ENUMERABLE"),
    ("V09", "A4", "CONVENTION_DIVERGENCE"),
    ("V10", "A4", "CONVENTION_DIVERGENCE"),
    ("V11", "A4", "NOT_RECOVERABLE_ABSENT_KEY"),
    ("V12", "A5", "REGISTERED_ONLY"),
    ("V13", "A6", "NOT_VIOLATED"),
    ("V14", "A6", "NOT_VIOLATED"),
    ("V15", "A6", "NOT_VIOLATED"),
    ("V16", "A7", "REGISTERED_ONLY"),
    ("V17", "A7", "REGISTERED_ONLY"),
    ("V18", "A8", "NOT_ENUMERABLE"),
    ("V19", "A9", "NOT_VIOLATED"),
    ("V20", "A9", "NOT_VIOLATED"),
]

VERDICT_VOCAB = [
    "NOT_VIOLATED", "RECOVERABLE", "NOT_RECOVERABLE_ABSENT_KEY",
    "NOT_RECOVERABLE_INDIRECT", "ENUMERABLE_SINGLE_SITE", "NOT_ENUMERABLE",
    "CONVENTION_DIVERGENCE", "REGISTERED_ONLY", "NOT_REACHABLE", "INCONCLUSIVE",
]

LOAD_BEARING = ["V03", "V04", "V07", "V08", "V09", "V10", "V11", "V18"]
NON_KILLING_AXES = ["A5", "A6", "A7"]
NON_KILLING_VARIANTS = ["V12", "V13", "V14", "V15", "V16", "V17"]

# 冻结的 ±20% 网格（协议强制披露；仅作披露，不改变任何判定）
SENSITIVITY_TOLERANCE = 0.20
SENSITIVITY_GRID = {
    "group_count": {"base": 5, "values": [4, 5, 6]},
    "min_cross_section_size": {"base": 10, "values": [8, 10, 12]},
    "holding_days": {"base": 5, "values": [4, 5, 6]},
}

# ── 论文数值禁用清单（R011_prereg.md §2） ──────────────────────────────────────
FORBIDDEN_PAPER_NUMBERS = [
    "NSE 1.2%", "NSE 6.7%", "IQR as correct metric", "AD test",
    "multiple-testing factor 13-91", "t >= 2.9 lower bound",
    "sampling frequency / statistical model ranking",
]


def main():
    panel = build_panel()
    cfg = {
        "round": "R011",
        "subject": "承重分析分叉的载荷可恢复性与备选可枚举性审计",
        "subject_kind": "只读审计（论文只作判据来源）",
        "baseline_commit": "a8681459",
        "judgment_source": {
            "citation": "Menkveld, Dreber, Holzmeister, Huber, Johannesson, Kirchler, Neususs, "
                        "Razen, Weitzel et al. (2024) Nonstandard Errors, "
                        "The Journal of Finance 79(3):2339-2390",
            "doi": "10.1111/jofi.13337",
            "grade": "A",
            "carrier_pdf": "papers/menkveld2024_jf_nonstandard_errors.pdf",
            "carrier_pdf_bytes": 1220922,
            "carrier_pdf_sha256": "9d9268f97a0876403cd21a814a9017fa366e96a9f6e4045787d8850916d86ebe",
            "carrier_txt": "papers/menkveld2024_jf_nonstandard_errors.txt",
            "carrier_txt_bytes": 170400,
            "carrier_txt_sha256": "b0213178dbe16a7402ef8bbe4bd91f3e132c6f9e5d76b061203e1639c81cfa09",
            "criteria": {
                "S1": {"text": "分析路径必须被指名", "grade": "L"},
                "S2": {"text": "关键分叉 + 该分叉的预定义备选集合必须存在", "grade": "L"},
                "S3": {"text": "敏感性 = 在备选上重算得到的分布", "grade": "L"},
                "S4": {"text": "路径先声明、偏离须显式对账", "grade": "L"},
            },
            "forbidden_numbers": FORBIDDEN_PAPER_NUMBERS,
        },
        "tolerances": {
            "TOL": TOL, "TOL_INT": TOL_INT, "TOL_IC": TOL_IC,
            "DET_BITS": DET_BITS, "G3_SUFFIX": G3_SUFFIX,
            "PANEL_DAYS": PANEL_DAYS, "GRID_CELLS": GRID_CELLS,
            "CANARY_PAIRS": CANARY_PAIRS,
        },
        "panel": {
            "design": "score_i = i（整数、无并列）；fwd_i = A*x^2 + B*x + C*sin(3i)，x=(i-(n+1)/2)/n",
            "A": PANEL_A, "B": PANEL_B, "C": PANEL_C,
            "n_by_day": {p["day"]: p["n"] for p in panel},
            "days": panel,
            "note": "手写确定性数值；不含真实行情、不含时间序列依赖、无 PIT 依赖",
        },
        "grid": {
            "holding_days": GRID_HOLDING,
            "group_count": GRID_GROUP_COUNT,
            "min_cross_section_size": GRID_MIN_SIZE,
            "cells": GRID_CELLS,
        },
        "expected_arith": {
            "days_by_min_size": {str(k): v for k, v in EXPECTED_DAYS_BY_MIN_SIZE.items()},
            "included_days": {str(k): v for k, v in EXPECTED_INCLUDED_DAYS.items()},
            "groups_per_day": EXPECTED_GROUPS_PER_DAY,
            "bucket10_days_J10": EXPECTED_BUCKET10_DAYS_J10,
            "clamp_days_J10": EXPECTED_CLAMP_DAYS_J10,
        },
        "expected_counts": EXPECTED_COUNTS,
        "anchors": ANCHORS,
        "variants": [{"variant": v, "axis": a, "expected": e} for v, a, e in VARIANTS],
        "verdict_vocab": VERDICT_VOCAB,
        "load_bearing": LOAD_BEARING,
        "non_killing_axes": NON_KILLING_AXES,
        "non_killing_variants": NON_KILLING_VARIANTS,
        "sensitivity": {
            "tolerance": SENSITIVITY_TOLERANCE,
            "grid": SENSITIVITY_GRID,
            "role": "协议强制披露；不改变任何判定",
        },
        "excluded_forks": {
            "reason": "已在 R007 审过，本轮不抽取、不判定、不引用其数值",
            "items": [
                "neweyWestHacStd lag = floor(n^(1/3))（FactorResearchEngine:324）",
                "blockBootstrapCi blockSize/iterations=1000/seed=42L（FactorResearchEngine:358, 调用式 :191）",
            ],
        },
        "reject_forks_owned_by_R010": [
            "成本费率", "convention/executionAssumptions 忠实性", "labelDefinition() 文本", "min_net_return",
        ],
    }
    cfg_path = os.path.join(HERE, "config.json")
    with open(cfg_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2, sort_keys=False)
        f.write("\n")

    # 面板字面量：CSV，供 Java 探针读取（无需 JSON 依赖）
    panel_path = os.path.join(HERE, "panel_R011.txt")
    lines = ["# R011 frozen panel: day,score,forward_return"]
    for p in panel:
        for score, fwd in p["rows"]:
            lines.append("%s,%s,%s" % (p["day"], repr(score), repr(fwd)))
    with open(panel_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")

    print("wrote %s (%d bytes)" % (cfg_path, os.path.getsize(cfg_path)))
    print("wrote %s (%d bytes, %d rows)" % (panel_path, os.path.getsize(panel_path),
                                            sum(p["n"] for p in panel)))
    print("panel n by day: %s" % json.dumps(cfg["panel"]["n_by_day"]))
    print("grid cells: %d" % (len(GRID_HOLDING) * len(GRID_GROUP_COUNT) * len(GRID_MIN_SIZE)))
    print("variants: %d  axes: %d" % (len(VARIANTS), len(set(a for _, a, _ in VARIANTS))))
    print("row_sum=%d" % len(VARIANTS))


if __name__ == "__main__":
    main()
