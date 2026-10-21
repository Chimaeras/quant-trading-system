#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R013 装置：生成 config.json（含冻结扫描根、锚点、面板、21 变体、金丝雀规格、P 表）与 panel_R013.txt。

纪律（R013_prereg.md §12）：
- 本文件**只读**冻结扫描根；不改任何生产代码；
- 冻结值一律取自 `rounds/R013_prereg.md` 的**冻结文本**（含 sha256 前 32 位与字节数），
  本文件不"重算后当作冻结值"——重算结果只用于 K7 冻结门比对；
- `expect` 编码必须**忠实携带冻结量级**（R012 教训 71），不得退化为布尔。

用法：
    python make_config.py            # 生成 config.json + panel_R013.txt
"""
import hashlib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
PREREG = os.path.join(ROOT, "research", "paper_loop", "rounds", "R013_prereg.md")
CONFIG_PATH = os.path.join(HERE, "config.json")
PANEL_PATH = os.path.join(HERE, "panel_R013.txt")

FROZEN_HEAD = "006baaca4d7768ec15a933b0d76803d1a273ba28"

# --------------------------------------------------------------------------
# 冻结扫描根（11 文件）——值**逐字**抄自 R013_prereg.md §3 的冻结表
# --------------------------------------------------------------------------
FROZEN_FILES = [
    ("F01", "modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java",
     14429, "efbf0678aabb2780b95c893e715ece9c"),
    ("F02", "modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java",
     56806, "b7c93af0e03c0d8006c4d228e0047877"),
    ("F03", "modules/backend-java/src/main/java/com/quant/service/ScoringBacktestService.java",
     52264, "a0e6cf738bb60d39145f90b43f975349"),
    ("F04", "modules/backend-java/src/main/java/com/quant/dto/ScoringBacktestResponse.java",
     6348, "40a7982b60c8d7cfd1391e5405e3b1bd"),
    ("F05", "modules/backend-java/src/main/java/com/quant/dto/ScoringBacktestRequest.java",
     1401, "01b85daef92b2cc5309fabb8e926f2d4"),
    ("F06", "modules/backend-java/src/main/java/com/quant/controller/FactorResearchController.java",
     2822, "79bb33f56b857532286d0863345816b3"),
    ("F07", "modules/backend-java/src/test/java/com/quant/service/backtest/FactorResearchEngineTest.java",
     10960, "42aa831eea4b45bdcc220ca905ca4faa"),
    ("F08", "modules/web/src/api/factor.ts", 6591, "3f8fe7e80c55cfb219736594811a6337"),
    ("F09", "modules/web/src/api/backtest.ts", 13140, "c3e4bfcd47c302c26cfbb541c79ffb4b"),
    ("F10", "modules/web/src/views/factor/FactorResearch.vue", 27171, "c59c81fc3933b5ff58607695cb2f8b7a"),
    ("F11", "modules/web/src/views/backtest/ScoringBacktest.vue", 60185, "05427ebc02bf392ae935713bced71102"),
]

VOCAB = [
    "SEMANTICS_FAITHFUL", "SEMANTICS_GAPPED", "DEGENERATE_SAFE", "DEGENERATE_ASSERTS",
    "RECONSTRUCTABLE", "NOT_RECONSTRUCTABLE", "INFERENCE_NOT_CLAIMED", "INFERENCE_CLAIMED",
    "CLAIM_ABSENT", "CLAIM_UNDECIDABLE_STATIC", "UNFALSIFIABLE", "TRUE_CLAIM", "FALSE_CLAIM",
    "CONSISTENT_VOCAB", "INCONSISTENT_VOCAB", "NOT_REACHED_STATIC_ONLY",
]

# --------------------------------------------------------------------------
# 锚点（冻结，R013_prereg.md §4）；pattern 为**逐字**取自冻结摘要的可检索片段
# --------------------------------------------------------------------------
ANCHORS = [
    # A 组：实现锚点
    ("A-01", "F01", 226, 249, r"assessMonotonicity"),
    ("A-01b", "F01", 227, 227, r"grouped == null || grouped.size() < 2"),
    ("A-01c", "F01", 229, 230, r"boolean increasing = true",
     None),
    ("A-01d", "F01", 235, 240, r"if (cur < prev)",
     None),
    ("A-01e", "F01", 246, 248, r"if (increasing)"),
    ("A-02", "F01", 114, 114, r"rets.isEmpty() ? 0"),
    ("A-03", "F01", 182, 182, r"rets.isEmpty() ? 0"),
    ("A-04", "F01", 102, 103, r"Math.min(groupCount, n)"),
    ("A-05", "F01", 180, 180, r"for (int g = 1; g <= groupCount; g++)"),
    ("A-06", "F01", 187, 187, r"assessMonotonicity(grouped)"),
    ("A-07", "F01", 214, 221, r"emptySummary"),
    ("A-08", "F03", 78, 78, r"req.getFactorGroupCount() != null ? req.getFactorGroupCount() : 5"),
    ("A-09", "F03", 431, 437, r"FACTOR_MIN_CROSS_SECTION_SIZE"),
    ("A-10", "F05", 37, 38, r"private Integer factorGroupCount = 5"),
    # B 组：发射 / 映射锚点
    ("B-01", "F02", 543, 543, r'block.put("monotonicity", summary.monotonicity())'),
    ("B-02", "F02", 569, 569, r'block.put("monotonicity", "N/A")'),
    ("B-03", "F02", 762, 762, r'block.put("monotonicity", summary.monotonicity())'),
    ("B-04", "F02", 831, 831, r'net.put("monotonicity", s.monotonicity())'),
    ("B-05", "F02", 592, 600, r'"observations", g.observations()'),
    ("B-06", "F02", 1030, 1032, r"Math.round(v * 10000.0) / 10000.0"),
    ("B-07", "F03", 1024, 1048, r"toFactorAnalysisDto"),
    ("B-08", "F03", 1051, 1065, r"toFactorIcSummaryDto"),
    ("B-09", "F04", 80, 92, r"class FactorAnalysis"),
    ("B-10", "F04", 105, 117, r"class GroupReturnStat"),
    ("B-11", "F02", 70, 71, r"RANK_IC_GROUP_COUNT"),
    ("B-12", "F02", 650, 650, r'block.put("group_count", RANK_IC_GROUP_COUNT)'),
    # C 组：声明 / 展示锚点
    ("C-01", "F08", 111, 111, r"monotonicity"),
    ("C-02", "F08", 202, 202, r"monotonicity"),
    ("C-03", "F01", 224, 224, r"分组单调性判断"),
    ("C-04", "F10", 137, 137, r"单调性"),
    ("C-05", "F10", 313, 314, r"单调性"),
    ("C-06", "F10", 506, 509, r"monotonicity"),
    ("C-07", "F11", 360, 360, r"验证单调性"),
    ("C-08", "F06", 34, 34, r"单调性"),
    ("C-09", "F07", 110, 141, r"monotonicityDetects"),
]

# --------------------------------------------------------------------------
# 面板（合成；HERE 逐条冻结）。dyad(k) = k / 128（精确二进制小数）
# --------------------------------------------------------------------------
D = 128.0
NAN = float("nan")


def dyad(k):
    return k / D


def obs(scores_rets):
    return [(float(s), float(r)) for s, r in scores_rets]


def panel(pid, group_count, min_size, dates, note, variants, recon=False, aux=False):
    return {
        "id": pid, "group_count": group_count, "min_size": min_size,
        "dates": dates, "note": note, "variants": variants, "recon": recon, "aux": aux,
    }


PANELS = [
    panel("Q01_strict_up", 5, 10,
          [("2026-01-05", obs([(k + 1, dyad(k + 1)) for k in range(10)]))],
          "严格递增（组均值 1.5/3.5/5.5/7.5/9.5 ×2^-7）", ["V01"]),
    panel("Q02_strict_down", 5, 10,
          [("2026-01-05", obs([(k + 1, dyad(10 - k)) for k in range(10)]))],
          "严格递减（组均值 9.5/7.5/5.5/3.5/1.5 ×2^-7）", ["V02"]),
    panel("Q03_wave", 5, 10,
          [("2026-01-05", obs([(k + 1, dyad(v)) for k, v in
                               enumerate([9, 8, 1, 2, 3, 4, 5, 6, 7, 10])]))],
          "波动（组均值 8.5/1.5/3.5/5.5/8.5 ×2^-7）", ["V03"]),
    panel("Q04_flat_groups", 5, 10,
          [("2026-01-05", obs([(i + 1, dyad(v)) for i, v in enumerate(
              [1, 3, 5, 7, 3, 1, 7, 5, 5, 7, 1, 3, 7, 5, 3, 1, 1, 5, 3, 7])]))],
          "零斜率：5 组各为 {1,3,5,7}×2^-7 的置换 ⟹ 组均值**逐位相等** = 4×2^-7 = 0.03125", ["V04", "V05"]),
    panel("Q05_constant_returns", 5, 10,
          [("2026-01-05", obs([(k + 1, dyad(4)) for k in range(10)]))],
          "全常量收益（IC 侧退化；对照 Q04）", []),
    panel("Q06_overgroup", 20, 10,
          [("2026-01-05", obs([(k + 1, -dyad(k + 1)) for k in range(10)])),
           ("2026-01-06", obs([(k + 1, -dyad(k + 1)) for k in range(10)]))],
          "空桶占位：groupCount=20 > 每日 10 样本 ⟹ 真实 10 组（每组 1 只、严格递减且为负）+ 10 个占位组（obs=0, avg=0.0）",
          ["V06"]),
    panel("Q07_round_collision", 5, 5,
          [("2026-01-05", obs([(k + 1, v) for k, v in enumerate(
              [1.4e-4, 1.2e-4, 1.0e-4, 8e-5, 6e-5])]))],
          "严格递减但 5 个组均值经 round4 后**全部**相等（1e-4）⟹ 由载荷重建的标签与引擎标签可能不同",
          ["V11"], recon=True),
    panel("Q08_zero_group", 0, 10,
          [("2026-01-05", obs([(k + 1, dyad(k + 1)) for k in range(10)]))],
          "groupCount=0", ["V09"]),
    panel("Q09_one_group", 1, 10,
          [("2026-01-05", obs([(k + 1, dyad(k + 1)) for k in range(10)]))],
          "groupCount=1", ["V08"]),
    panel("Q10_negative_group", -1, 10,
          [("2026-01-05", obs([(k + 1, dyad(k + 1)) for k in range(10)]))],
          "groupCount=-1", ["V10"]),
    panel("Q11_nan_return", 5, 10,
          [("2026-01-05", obs([(k + 1, NAN if k == 3 else dyad(k + 1)) for k in range(10)]))],
          "含非有限收益（aux 描述性：检验 IC 侧过滤）", [], aux=True),
]

# 单元（直接喂 assessMonotonicity 的合成序列；aux 说明见 variant 表）
UNITS = [
    ("U01_const5", [0.02] * 5, "常量 5 组：两方向 flag 同时存活的直接构造", ["V05"]),
    ("U02_flat_pair", [0.02, 0.02], "常量 2 组（最小合法长度）", []),
    ("U03_nan_mid", [0.01, 0.02, NAN, 0.015, 0.03],
     "中段非有限：NaN 比较恒为假 ⟹ 不构成否证（aux 描述性）", []),
]

# ±20% 网格（R013_prereg.md §10）；18 单元 = 9 组合 × 2 冻结面板
GRID = {
    "group_counts": [4, 5, 6],
    "min_sizes": [8, 10, 12],
    "panels": ["G01_grid_up", "G02_grid_flat_pairs"],
    "cells": 18,
    "extras": ["G03_short_section"],
}

GRID_PANELS = [
    panel("G01_grid_up", None, None,
          [("2026-01-05", obs([(k + 1, dyad(k + 1)) for k in range(24)])),
           ("2026-01-06", obs([(k + 1, dyad(k + 1)) for k in range(24)]))],
          "非退化面板（24 样本/日 × 2 日，严格递增）", [], aux=True),
    panel("G02_grid_flat_pairs", None, None,
          [("2026-01-05", obs([(k + 1, dyad(1) if k % 2 == 0 else dyad(7)) for k in range(24)])),
           ("2026-01-06", obs([(k + 1, dyad(1) if k % 2 == 0 else dyad(7)) for k in range(24)]))],
          "退化面板：周期 2 取值 ⟹ 偶数长度分组下组均值逐位相等（= 0.03125）；奇数长度分组下不等",
          [], aux=True),
    panel("G03_short_section", 5, None,
          [("2026-01-05", obs([(k + 1, dyad(k + 1)) for k in range(9)])),
           ("2026-01-06", obs([(k + 1, dyad(k + 1)) for k in range(9)]))],
          "补充描述性（不入 18 单元）：每日 9 样本 ⟹ 展示 minSize 的真实作用", [], aux=True),
]

# --------------------------------------------------------------------------
# P 表（冻结，R013_prereg.md §9）——expect 必须忠实携带冻结量级
# --------------------------------------------------------------------------
P_TABLE = [
    {"id": "P-01", "panel": "Q01_strict_up", "expect": {"label": "MONOTONIC_INCREASING"}},
    {"id": "P-02", "panel": "Q02_strict_down", "expect": {"label": "MONOTONIC_DECREASING"}},
    {"id": "P-03", "panel": "Q03_wave", "expect": {"label": "NON_MONOTONIC"}},
    {"id": "P-04", "panel": "Q04_flat_groups",
     "expect": {"label_in": ["MONOTONIC_INCREASING", "MONOTONIC_DECREASING"],
                "zero_slope_asserted_direction": True}},
    {"id": "P-05", "panel": "Q04_flat_groups",
     "expect": {"flags_both_true": True, "value_determined_by_branch_order": "increasing_branch_first"}},
    {"id": "P-06", "panel": "Q06_overgroup",
     "expect": {"grouped_size": 20, "groups_with_zero_observations": 10,
                "groups_with_zero_avg": 10, "real_groups": 10}},
    {"id": "P-07", "panel": "Q06_overgroup", "expect": {"label_ne": "N/A"}},
    {"id": "P-08", "panel": "Q09_one_group", "expect": {"label": "N/A"}},
    {"id": "P-09", "panel": "Q08_zero_group",
     "expect": {"label": "N/A", "grouped_size": 0, "total_observations_gt": 0}},
    {"id": "P-10", "panel": "Q10_negative_group",
     "expect": {"throws": "IllegalArgumentException"}},
    {"id": "P-11", "panel": None,
     "expect": {"dto": "FactorAnalysis", "has_monotonicity_builder": False,
                "has_grouped_returns_builder": True}},
    {"id": "P-12", "panel": None,
     "expect": {"dto": "FactorIcSummary", "has_monotonicity": False, "has_grouped_returns": False}},
    {"id": "P-13", "panel": None, "expect": {"value_domain_declared": False}},
    {"id": "P-14", "panel": None, "expect": {"emitter_faces": 4, "same_vocabulary": True}},
    {"id": "P-15", "panel": None, "expect": {"inference_claimed": False}},
    {"id": "P-16", "panel": "Q07_round_collision",
     "expect": {"payload_rebuilt_label_differs_from_engine_label": True}},
    {"id": "P-17", "panel": None,
     "expect": {"grid": "G01_grid_up", "cells": 18, "label": "MONOTONIC_INCREASING",
                "all_cells_same_label": True}},
    {"id": "P-18", "panel": None,
     "expect": {"uncovered_degenerate_classes":
                ["constant", "non_finite", "empty_bucket_placeholder", "groupCount_gt_n",
                 "groupCount_le_0"]}},
]

# --------------------------------------------------------------------------
# 21 变体（冻结，R013_prereg.md §5）；decisive = 金丝雀 INJ 依赖的单键
# --------------------------------------------------------------------------
VARIANTS = [
    {"id": "V01", "axis": "A1", "axis_name": "名实", "rule": "严格递增面板 ⟹ 标签断言递增方向",
     "expect": "SEMANTICS_FAITHFUL", "panel": "Q01_strict_up", "decisive": "label"},
    {"id": "V02", "axis": "A1", "axis_name": "名实", "rule": "严格递减面板 ⟹ 标签断言递减方向",
     "expect": "SEMANTICS_FAITHFUL", "panel": "Q02_strict_down", "decisive": "label"},
    {"id": "V03", "axis": "A1", "axis_name": "名实", "rule": "波动面板 ⟹ 标签须为 NON_MONOTONIC",
     "expect": "SEMANTICS_FAITHFUL", "panel": "Q03_wave", "decisive": "label"},
    {"id": "V04", "axis": "A1", "axis_name": "名实",
     "rule": "零斜率（组均值逐位相等）⟹ 不得断出具名方向",
     "expect": "SEMANTICS_GAPPED", "panel": "Q04_flat_groups", "decisive": "slope_is_zero"},
    {"id": "V05", "axis": "A1", "axis_name": "名实",
     "rule": "零斜率下两方向 flag 同时存活 ⟹ 取值由分支顺序决定（不是数据）",
     "expect": "SEMANTICS_GAPPED", "panel": "Q04_flat_groups", "decisive": "flags_both_true"},
    {"id": "V06", "axis": "A2", "axis_name": "非退化",
     "rule": "空桶占位（obs=0 且 avg 硬写 0.0）不得参与形状断言",
     "expect": "DEGENERATE_ASSERTS", "panel": "Q06_overgroup", "decisive": "label_ne_na"},
    {"id": "V07", "axis": "A2", "axis_name": "非退化",
     "rule": "可达性链：factorGroupCount 由客户端可传且无服务端校验",
     "expect": "RECORD_REACHABILITY", "panel": None, "decisive": "has_server_validation"},
    {"id": "V08", "axis": "A2", "axis_name": "非退化", "rule": "groupCount=1 ⟹ 不得断出方向、不得抛异常",
     "expect": "DEGENERATE_SAFE", "panel": "Q09_one_group", "decisive": "size_lt_2"},
    {"id": "V09", "axis": "A2", "axis_name": "非退化", "rule": "groupCount=0 ⟹ 记录信息缺口",
     "expect": "DEGENERATE_SAFE", "panel": "Q08_zero_group", "decisive": "label"},
    {"id": "V10", "axis": "A2", "axis_name": "非退化", "rule": "groupCount=-1 ⟹ 不得抛未处理异常",
     "expect": "DEGENERATE_SAFE", "panel": "Q10_negative_group", "decisive": "threw"},
    {"id": "V11", "axis": "A3", "axis_name": "可重建",
     "rule": "avg_return 经 round4 后由载荷重建 ⟹ 是否与引擎标签一致",
     "expect": "NOT_RECONSTRUCTABLE", "panel": "Q07_round_collision", "decisive": "rebuilt_label"},
    {"id": "V12", "axis": "A3", "axis_name": "可重建", "rule": "monotonicity 是否声明值域",
     "expect": "CLAIM_ABSENT", "panel": None, "decisive": "value_domain_declared"},
    {"id": "V13", "axis": "A3", "axis_name": "可重建",
     "rule": "FactorAnalysis 是否携带标签（载荷面可重建性）",
     "expect": "CLAIM_ABSENT", "panel": None, "decisive": "has_monotonicity_builder"},
    {"id": "V14", "axis": "A3", "axis_name": "可重建",
     "rule": "FactorIcSummary 是否携带标签或分组",
     "expect": "CLAIM_ABSENT", "panel": None, "decisive": "has_monotonicity"},
    {"id": "V15", "axis": "A3", "axis_name": "可重建",
     "rule": "4 个发射面是否同词表（含 \"N/A\" 逐字符相同）",
     "expect": "CONSISTENT_VOCAB", "panel": None, "decisive": "vocab_consistent"},
    {"id": "V16", "axis": "A4", "axis_name": "推断边界",
     "rule": "声明性文本是否声称推断",
     "expect": "INFERENCE_NOT_CLAIMED", "panel": None, "decisive": "inference_claimed"},
    {"id": "V17", "axis": "A4", "axis_name": "推断边界", "rule": "展示面是否声称推断",
     "expect": "INFERENCE_NOT_CLAIMED", "panel": None, "decisive": "inference_claimed"},
    {"id": "V18", "axis": "A4", "axis_name": "推断边界",
     "rule": "披露粒度：标签所在块须同时给出组数与每组样本量（J-2）",
     "expect": "TRUE_CLAIM/FALSE_CLAIM", "panel": None, "decisive": "blocks_with_group_count"},
    {"id": "V19", "axis": "A5", "axis_name": "仪器", "rule": "金丝雀总门（以声明数填入）",
     "expect": "TRUE_CLAIM", "panel": None, "decisive": "pair_failures"},
    {"id": "V20", "axis": "A5", "axis_name": "仪器",
     "rule": "正面对照：引擎返回值**到达**载荷（逐字符透传 / 字面量相同）",
     "expect": "TRUE_CLAIM", "panel": None, "decisive": "observed_literals_subset_of_engine_vocab"},
    {"id": "V21", "axis": "A5", "axis_name": "仪器",
     "rule": "反向锚点：\"N/A\" 三来源可区分 + NON_MONOTONIC 仅内部产生",
     "expect": "TRUE_CLAIM", "panel": None, "decisive": "na_sources_are_distinguishable"},
]

AXIS_TOTAL = {}
for _v in VARIANTS:
    AXIS_TOTAL[_v["axis"]] = AXIS_TOTAL.get(_v["axis"], 0) + 1

# --------------------------------------------------------------------------
# 禁数/禁用词扫描（冻结口径：论文标识与"应当实现"类要求不得进装置）
# --------------------------------------------------------------------------
FORBIDDEN_PATTERNS = [
    "beta-sorted", "2208.10974", "cattaneo", "crump", "uniform inference",
    "论文应当", "应当实现", "最优化", "显著", "统计显著", "推断",
]

# 推断词表（V16/V17 用；冻结）
INFERENCE_WORDS = [
    "显著", "统计显著", "置信", "推断", "检验", "假设检验", "p值", "p 值",
    "significant", "confidence", "inference", "statistical",
]

EMITTER_FACES = [
    {"face": "B-01", "file": "F02", "line": 543, "container": "block",
     "expr": "summary.monotonicity()", "kind": "passthrough", "block_method": "buildSnapshotScopeBlock"},
    {"face": "B-02", "file": "F02", "line": 569, "container": "block",
     "expr": "\"N/A\"", "kind": "literal", "block_method": "unavailableScopeBlock"},
    {"face": "B-03", "file": "F02", "line": 762, "container": "block",
     "expr": "summary.monotonicity()", "kind": "passthrough", "block_method": "buildUnifiedReportBlock"},
    {"face": "B-04", "file": "F02", "line": 831, "container": "net",
     "expr": "s.monotonicity()", "kind": "passthrough", "block_method": "buildNetIcBlock"},
]

LABEL_BLOCKS = [
    {"face": "B-01", "file": "F02", "method_hint": "buildSnapshotScopeBlock"},
    {"face": "B-03", "file": "F02", "method_hint": "buildUnifiedReportBlock"},
    {"face": "B-04", "file": "F02", "method_hint": "buildNetIcBlock"},
]

DECL_SITES = [
    {"site": "C-01", "file": "F08", "line": 111},
    {"site": "C-02", "file": "F08", "line": 202},
]


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_prereg_frozen_table(text):
    """从冻结表里**读回**冻结值（K7 用冻结值，而不是重算值）。"""
    rows = {}
    for line in text.splitlines():
        m = re.match(r"^\|\s*(F\d\d)\s*\|\s*`([^`]+)`\s*\|\s*([\d,]+)\s*\|\s*`([0-9a-f]{32})`\s*\|", line)
        if m:
            rows[m.group(1)] = {"path": m.group(2), "bytes": int(m.group(3).replace(",", "")),
                                "sha256_32": m.group(4)}
    return rows


def fmt_num(x):
    if x != x:
        return "NaN"
    if x in (float("inf"), float("-inf")):
        return "Infinity" if x > 0 else "-Infinity"
    return repr(x)


def write_panel_file(path):
    lines = ["# R013 冻结面板（由 make_config.py 生成；单源 = config.json）", "VERSION|R013|1"]
    for p in PANELS + GRID_PANELS:
        gc = "GRID" if p["group_count"] is None else str(p["group_count"])
        ms = "GRID" if p["min_size"] is None else str(p["min_size"])
        lines.append("PANEL|%s|%s|%s" % (p["id"], gc, ms))
        for date, pairs in p["dates"]:
            lines.append("DATE|%s|%s" % (p["id"], date))
            for s, r in pairs:
                lines.append("OBS|%s|%s|%s|%s" % (p["id"], date, fmt_num(s), fmt_num(r)))
    for uid, seq, note, variants in UNITS:
        lines.append("UNIT|%s|%s" % (uid, ",".join(fmt_num(v) for v in seq)))
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")


def main():
    prereg_text = open(PREREG, encoding="utf-8").read()
    frozen_from_prereg = parse_prereg_frozen_table(prereg_text)
    files = []
    for fid, rel, frozen_bytes, frozen_sha32 in FROZEN_FILES:
        abs_path = os.path.join(ROOT, rel)
        actual_bytes = os.path.getsize(abs_path)
        actual_sha = sha256_of(abs_path)
        entry = {
            "id": fid, "path": rel,
            "frozen_bytes": frozen_bytes, "frozen_sha256_32": frozen_sha32,
            "actual_bytes": actual_bytes, "actual_sha256": actual_sha,
            "actual_sha256_32": actual_sha[:32],
            "bytes_match": actual_bytes == frozen_bytes,
            "sha_match": actual_sha[:32] == frozen_sha32,
            "prereg_table_bytes": frozen_from_prereg.get(fid, {}).get("bytes"),
            "prereg_table_sha32": frozen_from_prereg.get(fid, {}).get("sha256_32"),
        }
        files.append(entry)

    config = {
        "round": "R013",
        "generated_by": "make_config.py",
        "root": ROOT,
        "prereg": {
            "path": "research/paper_loop/rounds/R013_prereg.md",
            "sha256": sha256_of(PREREG),
            "frozen_head": FROZEN_HEAD,
            "frozen_at_commit": "dffd7691",
        },
        "scan_root": {
            "files": files,
            "file_count": len(files),
            "total_bytes_frozen": sum(f["frozen_bytes"] for f in files),
            "total_bytes_actual": sum(f["actual_bytes"] for f in files),
            "all_match": all(f["bytes_match"] and f["sha_match"] for f in files),
        },
        "vocabulary": VOCAB,
        "anchors": [
            {"id": a[0], "file": a[1], "start": a[2], "end": a[3], "pattern": a[4]} for a in ANCHORS
        ],
        "panels": PANELS,
        "grid_panels": GRID_PANELS,
        "units": [{"id": u[0], "values": u[1], "note": u[2], "variants": u[3]} for u in UNITS],
        "grid": GRID,
        "variants": VARIANTS,
        "max_variants": 21,
        "canary_pairs": 21,
        "p_table": P_TABLE,
        "forbidden_patterns": FORBIDDEN_PATTERNS,
        "inference_words": INFERENCE_WORDS,
        "emitter_faces": EMITTER_FACES,
        "label_blocks": LABEL_BLOCKS,
        "decl_sites": DECL_SITES,
        "engine_vocab": ["MONOTONIC_INCREASING", "MONOTONIC_DECREASING", "NON_MONOTONIC", "N/A"],
        "reachability_ladder": ["REACHABLE_PUBLIC_API", "REACHABLE_CLIENT_INPUT", "SYNTHETIC_ONLY",
                                "UNREACHABLE"],
    }

    with open(CONFIG_PATH, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(config, fh, ensure_ascii=False, indent=1, sort_keys=True)
        fh.write("\n")
    write_panel_file(PANEL_PATH)

    print("config.json  ->", CONFIG_PATH)
    print("panel_R013.txt ->", PANEL_PATH)
    print("frozen files:", len(files), "all_match =", config["scan_root"]["all_match"])
    print("total bytes (frozen/actual): %d / %d"
          % (config["scan_root"]["total_bytes_frozen"], config["scan_root"]["total_bytes_actual"]))
    print("variants:", len(VARIANTS), "axis_total:", AXIS_TOTAL, "sum:", sum(AXIS_TOTAL.values()))
    print("panels:", len(PANELS), "grid_panels:", len(GRID_PANELS), "units:", len(UNITS))
    print("prereg sha256:", config["prereg"]["sha256"])
    missing = [f["id"] for f in files if not (f["bytes_match"] and f["sha_match"])]
    if missing:
        print("MISMATCH:", missing)
    bad_table = [f["id"] for f in files
                 if f["prereg_table_bytes"] != f["frozen_bytes"]
                 or f["prereg_table_sha32"] != f["frozen_sha256_32"]]
    if bad_table:
        print("PREREG_TABLE_MISMATCH:", bad_table)
    return 0 if not missing and not bad_table else 1


if __name__ == "__main__":
    sys.exit(main())
