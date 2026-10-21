#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R014 装置配置生成器：把 `rounds/R014_prereg.md` 的冻结值**转录**成机器可读配置，
并生成**合成面板**与**运行单元表**（探针的全部输入都来自这里，探针自身不含任何阈值/字面量）。

产出：
- `config.json`           冻结值转录（扫描根哈希、变体表、词表、K 表、P 表、网格、容差）
- `panel_R014.txt`        合成面板（**每行自含日期**，R013 教训）
- `_scratch/panel_R014_cells.txt`  探针运行单元表

用法：`python make_config.py`（幂等；生成后须与冻结文本核对）
"""
import hashlib
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
CONFIG = os.path.join(HERE, "config.json")
PANEL_FILE = os.path.join(HERE, "panel_R014.txt")
SCRATCH = os.path.join(HERE, "_scratch")
CELL_FILE = os.path.join(SCRATCH, "panel_R014_cells.txt")

PREREG = os.path.join(ROOT, "research", "paper_loop", "rounds", "R014_prereg.md")
REVIEW = os.path.join(ROOT, "research", "paper_loop", "rounds", "R014_review.md")

# --------------------------------------------------------------------------
# §3 冻结扫描根（12 文件）—— sha256-32 与冻结文本逐位相同（生成时断言）
# --------------------------------------------------------------------------
SCAN_ROOT_FILES = [
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
    ("F12", "modules/backend-java/src/test/java/com/quant/service/FactorResearchServiceTest.java",
     15356, "33dca6899b16d871da11d5455ed5a424"),
]

# R013 冻结表（F01–F11，用于「零生产漂移」核对；R014 生成时断言逐位相同）
R013_FROZEN = {
    "F01": "efbf0678aabb2780b95c893e715ece9c", "F02": "b7c93af0e03c0d8006c4d228e0047877",
    "F03": "a0e6cf738bb60d39145f90b43f975349", "F04": "40a7982b60c8d7cfd1391e5405e3b1bd",
    "F05": "01b85daef92b2cc5309fabb8e926f2d4", "F06": "79bb33f56b857532286d0863345816b3",
    "F07": "42aa831eea4b45bdcc220ca905ca4faa", "F08": "3f8fe7e80c55cfb219736594811a6337",
    "F09": "c3e4bfcd47c302c26cfbb541c79ffb4b", "F10": "c59c81fc3933b5ff58607695cb2f8b7a",
    "F11": "05427ebc02bf392ae935713bced71102",
}

# --------------------------------------------------------------------------
# §6 判定词表（16 词，冻结）
# --------------------------------------------------------------------------
VOCAB = [
    "RECONSTRUCTABLE", "NOT_RECONSTRUCTABLE", "LOSSY_PRECISION", "RULE_UNDECLARED",
    "CONSISTENT_UNIT", "INCONSISTENT_UNIT", "UNIT_UNDECLARED",
    "VALUE_CONSISTENT", "VALUE_DIVERGENT",
    "COUNT_FAITHFUL", "COUNT_UNFAITHFUL", "PLACEHOLDER_MARKED", "PLACEHOLDER_UNMARKED",
    "DROPPED_SILENTLY", "DROPPED_DISCLOSED", "NOT_REACHED_STATIC_ONLY",
]

# 会触发 K1–K4 的判定词（其余词不触发任何 K）
K_TRIGGER_WORDS = {
    "NOT_RECONSTRUCTABLE": "K1", "INCONSISTENT_UNIT": "K2",
    "VALUE_DIVERGENT": "K3", "COUNT_UNFAITHFUL": "K4",
}

# --------------------------------------------------------------------------
# §5 变体表（20 个，5 轴）
# --------------------------------------------------------------------------
AXES = [
    ("A1", "可重建", ["V01", "V02", "V03", "V04", "V05"]),
    ("A2", "单位忠实", ["V06", "V07", "V08", "V09"]),
    ("A3", "同量同值", ["V10", "V11", "V12", "V13", "V14"]),
    ("A4", "计数忠实", ["V15", "V16", "V17", "V18"]),
    ("A5", "仪器", ["V19", "V20"]),
]

VARIANTS = [
    # (id, axis, 名称, 预期判定词, 可达性, 参与 K1–K4 候选集, reading_dependent 初值)
    ("V01", "A1", "P1 无并列面板：载荷逐元素 vs 多重集重算", "RECONSTRUCTABLE",
     "REACHABLE_PUBLIC_API", True, False),
    ("V02", "A1", "P2 并列（桶内）面板：载荷逐元素 vs 多重集重算", "RECONSTRUCTABLE",
     "REACHABLE_PUBLIC_API", True, False),
    ("V03", "A1", "P3 并列跨桶面板：声明序 vs 逆序 vs 多重集重算", "NOT_RECONSTRUCTABLE",
     "REACHABLE_PUBLIC_API", True, False),
    ("V04", "A1", "round4 截断：载荷精度损失计数与幅度", "LOSSY_PRECISION",
     "REACHABLE_PUBLIC_API", True, False),
    ("V05", "A1", "载荷是否携带分组边界规则（下标等分）", "RULE_UNDECLARED",
     "REACHABLE_PUBLIC_API", True, False),
    ("V06", "A2", "聚合 observations（天数）vs 日级 observations（名字数）", "INCONSISTENT_UNIT",
     "REACHABLE_PUBLIC_API", True, False),
    ("V07", "A2", "两面展示标签：「观测数」vs「截面日观测」", "INCONSISTENT_UNIT",
     "REACHABLE_PUBLIC_API", True, False),
    ("V08", "A2", "同一页面内一个标签指向两个不同量（总观测 vs 组观测）", "INCONSISTENT_UNIT",
     "REACHABLE_PUBLIC_API", True, False),
    ("V09", "A2", "是否存在字段级单位声明（DTO / TS / note）", "UNIT_UNDECLARED",
     "REACHABLE_PUBLIC_API", True, False),
    ("V10", "A3", "同量同值：两面 rank_ic（round4 vs 原样）", "VALUE_DIVERGENT",
     "REACHABLE_PUBLIC_API", True, False),
    ("V11", "A3", "同量同值：两面 avg_return（round4 vs 原样）", "VALUE_DIVERGENT",
     "REACHABLE_PUBLIC_API", True, False),
    ("V12", "A3", "同量同值：两面 sample_size / observations（原样 int）", "VALUE_CONSISTENT",
     "REACHABLE_PUBLIC_API", True, False),
    ("V13", "A3", "两面默认表格的显示精度一致性", "VALUE_CONSISTENT",
     "REACHABLE_PUBLIC_API", True, False),
    ("V14", "A3", "载荷是否声明取整规则", "RULE_UNDECLARED",
     "REACHABLE_PUBLIC_API", True, False),
    ("V15", "A4", "Σ sample_size vs total_observations", "COUNT_FAITHFUL",
     "REACHABLE_PUBLIC_API", True, False),
    ("V16", "A4", "非有限值进入当日截面：秩位置与 sample_size", "COUNT_UNFAITHFUL",
     "REACHABLE_PUBLIC_API", True, False),
    ("V17", "A4", "minCrossSectionSize 未达标日的静默跳过", "DROPPED_SILENTLY",
     "REACHABLE_PUBLIC_API", False, False),
    ("V18", "A4", "零观测组行（avg_return=0.0, observations=0）", "PLACEHOLDER_MARKED",
     "SYNTHETIC_ONLY", True, False),
    # A5 仪器轴：词表取非触发词；失败路径走 instrument_alerts ⟹ K6（短路优先级更高）
    ("V19", "A5", "金丝雀总门（20 对）", "COUNT_FAITHFUL", "SYNTHETIC_ONLY", False, False),
    ("V20", "A5", "正面对照 + 反向锚点（确定性 / 置换可撤销 / 移除 round4 后杀点消失）",
     "VALUE_CONSISTENT", "SYNTHETIC_ONLY", False, False),
]

# --------------------------------------------------------------------------
# §9 P 预测表（事前 20 条；`expect` 为**冻结措辞**，不得事后修改）
# --------------------------------------------------------------------------
PREDICTIONS = [
    ("P-01", "无并列面板（P1）：载荷 rank_ic/avg_return 与多重集重算在 RECON_TOL_R4 内一致", "V01|RECONSTRUCTABLE"),
    ("P-02", "含并列（P2）：同样 RECONSTRUCTABLE（ranks() 用平均秩）", "V02|RECONSTRUCTABLE"),
    ("P-03", "P3 置换输入行序 ⟹ 至少一组 avg_return 改变", "V03|NOT_RECONSTRUCTABLE"),
    ("P-04", "置换不改变 daily_rank_ic[].rank_ic 序列（分歧特定于 grouped_returns）", "V03|rank_ic_invariant"),
    ("P-05", "V04：非 1e-4 整除的 rank_ic 被 round4 截断（差 ≤ 5e-5）", "V04|LOSSY_PRECISION"),
    ("P-06", "载荷中不存在分组边界规则文本 ⟹ RULE_UNDECLARED", "V05|RULE_UNDECLARED"),
    ("P-07", "聚合 observations == 贡献天数；n ≥ groupCount 时各组相等 == cross_section_days", "V06|agg_obs_is_days"),
    ("P-08", "F11:378 标签含「截面日」而 F10:151 标签为「观测数」⟹ 两字符串不同", "V07|INCONSISTENT_UNIT"),
    ("P-09", "F10:131 与 F10:151 标签逐字符相同而所指对象不同", "V08|INCONSISTENT_UNIT"),
    ("P-10", "DTO record 与 TS interface 均无字段级单位声明 ⟹ UNIT_UNDECLARED", "V09|UNIT_UNDECLARED"),
    ("P-11", "V10：存在至少一天使 round4(rankIc) != rankIc ⟹ 两面不同", "V10|VALUE_DIVERGENT"),
    ("P-12", "V11：同上（avgReturn）", "V11|VALUE_DIVERGENT"),
    ("P-13", "V12：两面的 sampleSize/observations 逐元素相等", "V12|VALUE_CONSISTENT"),
    ("P-14", "V13：两面默认表格 avg_return 显示字符串（2 位小数百分号）一致", "V13|VALUE_CONSISTENT"),
    ("P-15", "V14：载荷无取整规则声明 ⟹ RULE_UNDECLARED", "V14|RULE_UNDECLARED"),
    ("P-16", "V15：Σ sample_size == total_observations", "V15|COUNT_FAITHFUL"),
    ("P-17", "V16：含 NaN 当日仍产出有限 rank_ic 且 sample_size == 原始行数（含 NaN）", "V16|COUNT_UNFAITHFUL"),
    ("P-18", "V17：被 minCrossSectionSize 过滤的日数不出现在载荷任何字段中", "V17|DROPPED_SILENTLY"),
    ("P-19", "V18：零观测组行 == {avg_return: 0.0, observations: 0} ⟹ PLACEHOLDER_MARKED", "V18|PLACEHOLDER_MARKED"),
    ("P-20", "V20：两次运行逐字节相等；置换可撤销；移除 round4 后 V10 的杀点消失", "V20|VALUE_CONSISTENT"),
]

# --------------------------------------------------------------------------
# §8 容差 / §10 网格（冻结）
# --------------------------------------------------------------------------
RECON_TOL_R4 = 5e-5
RECON_TOL_FP = 1e-9
GRID_GROUPS = [4, 5, 6]
GRID_MINSIZE = [8, 10, 12]
GRID_PANELS = ["P1", "P3"]

# --------------------------------------------------------------------------
# 合成面板（全部为**合成数**；每行自含日期）
# --------------------------------------------------------------------------
def _obs(date, score, fwd):
    return (date, score, fwd)

def build_panels():
    panels = {}

    # P1：唯一分数，3 天 × 10；含 4dp 精确与不精确两类均值
    p1 = []
    for i in range(1, 11):
        p1.append(_obs("2026-01-05", float(i), round(0.001 * i, 10)))
    for i in range(1, 11):
        p1.append(_obs("2026-01-06", float(10 + i), round(0.0011 * (11 - i), 10)))
    perm = [1, 2, 3, 4, 5, 6, 7, 8, 10, 9]
    for i in range(1, 11):
        p1.append(_obs("2026-01-07", float(i), round(0.0011 * perm[i - 1], 10)))
    panels["P1"] = p1

    # P2：并列**桶内**（每桶 2 个元素，并列组恰在桶内）—— 3 天 × 10
    p2 = []
    tie_scores = [1.0, 1.0, 2.0, 2.0, 3.0, 3.0, 4.0, 4.0, 5.0, 5.0]
    for k, fwd_seq in enumerate([
        [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
        [10, 9, 8, 7, 6, 5, 4, 3, 2, 1],
        [2, 1, 4, 3, 6, 5, 8, 7, 10, 9],
    ]):
        date = f"2026-02-{10 + k:02d}"
        for j, s in enumerate(tie_scores):
            p2.append(_obs(date, s, round(0.0013 * fwd_seq[j], 10)))
    panels["P2"] = p2

    # P3：并列**跨桶**（3 个并列元素跨越 bucket 边界）—— 3 天 × 10
    p3 = []
    for k, tie_scores in enumerate([
        [1.0, 1.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0],
        [1.0, 2.0, 2.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0],
        [1.0, 2.0, 3.0, 3.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0],
    ]):
        date = f"2026-03-{10 + k:02d}"
        for j, s in enumerate(tie_scores):
            p3.append(_obs(date, s, round(0.0017 * (j + 1), 10)))
    panels["P3"] = p3

    # P4：第 2 天含两个 NaN forward return（非有限值）
    p4 = []
    for i in range(1, 11):
        p4.append(_obs("2026-04-10", float(i), round(0.0019 * i, 10)))
    for i in range(1, 11):
        fwd = float("nan") if i in (3, 7) else round(0.0019 * i, 10)
        p4.append(_obs("2026-04-13", float(i), fwd))
    panels["P4"] = p4

    # P5：每日 2 个观测（n < groupCount ⟹ 高位组零观测）
    p5 = []
    for k in range(3):
        date = f"2026-05-{10 + k:02d}"
        p5.append(_obs(date, 1.0, 0.01))
        p5.append(_obs(date, 2.0, 0.02))
    panels["P5"] = p5

    # P6：混合规模（12/12/4/12/4/12），minSize=10 时 4 个观测的日子应被静默丢弃
    p6 = []
    for k, n in enumerate([12, 12, 4, 12, 4, 12]):
        date = f"2026-06-{10 + k:02d}"
        for i in range(1, n + 1):
            p6.append(_obs(date, float(i), round(0.0021 * i, 10)))
    panels["P6"] = p6

    return panels


def write_panel_file(panels):
    lines = ["# R014 合成面板（**每行自含日期**；NaN 以字面量 NaN 表示；全部为合成数）"]
    for pid in sorted(panels):
        lines.append(f"=== PANEL {pid}")
        for date, score, fwd in panels[pid]:
            s = "NaN" if score != score else repr(float(score))
            f = "NaN" if fwd != fwd else repr(float(fwd))
            lines.append(f"O {date} {s} {f}")
    with open(PANEL_FILE, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    return len(lines)


def build_cells():
    cells = [
        # (tag, panel, order, groups, minsize, mode)
        ("P1_dec", "P1", "declared", 5, 10, "summarize"),
        ("P1_rev", "P1", "reversed", 5, 10, "summarize"),
        ("P1_dbl", "P1", "doubled", 5, 10, "summarize"),
        ("P2_dec", "P2", "declared", 5, 10, "summarize"),
        ("P2_rev", "P2", "reversed", 5, 10, "summarize"),
        ("P3_dec", "P3", "declared", 5, 10, "summarize"),
        ("P3_rev", "P3", "reversed", 5, 10, "summarize"),
        ("P3_dbl", "P3", "doubled", 5, 10, "summarize"),
        ("P4_dec", "P4", "declared", 5, 10, "summarize"),
        ("P5_dec_min2", "P5", "declared", 5, 2, "summarize"),
        ("P6_dec", "P6", "declared", 5, 10, "summarize"),
        ("P6_dec_min8", "P6", "declared", 5, 8, "summarize"),
        ("P1_dayg", "P1", "declared", 5, 10, "daygroups"),
        ("P5_dayg", "P5", "declared", 5, 2, "daygroups"),
    ]
    for g in GRID_GROUPS:
        for m in GRID_MINSIZE:
            for pid in GRID_PANELS:
                for order in ("declared", "reversed"):
                    cells.append((f"GRID_{pid}_{g}_{m}_{order[:3]}", pid, order, g, m, "summarize"))
    lines = [f"{t}|{p}|{o}|{g}|{m}|{mode}" for (t, p, o, g, m, mode) in cells]
    if not os.path.isdir(SCRATCH):
        os.makedirs(SCRATCH, exist_ok=True)
    with open(CELL_FILE, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    return cells


def sha256_32(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:32]


def main():
    panels = build_panels()
    n_panel_lines = write_panel_file(panels)
    cells = build_cells()

    # 扫描根核对（与冻结文本 + R013 冻结表逐位比对）
    files, drift, mismatch = [], [], []
    for fid, rel, size, h32 in SCAN_ROOT_FILES:
        path = os.path.join(ROOT, rel)
        actual_size = os.path.getsize(path)
        actual = sha256_32(path)
        files.append({"id": fid, "path": rel, "bytes": actual_size,
                      "sha256_32": actual, "frozen_sha256_32": h32, "frozen_bytes": size})
        if actual != h32 or actual_size != size:
            mismatch.append(fid)
        if fid in R013_FROZEN:
            if actual == R013_FROZEN[fid]:
                drift.append(fid)

    cfg = {
        "round": "R014",
        "prereg": os.path.relpath(PREREG, ROOT).replace("\\", "/"),
        "prereg_sha256": hashlib.sha256(open(PREREG, "rb").read()).hexdigest(),
        "review_sha256": hashlib.sha256(open(REVIEW, "rb").read()).hexdigest(),
        "root": ROOT,
        "scan_root": {"files": files, "count": len(files),
                      "total_bytes": sum(f["bytes"] for f in files),
                      "hash_mismatch": mismatch, "matches_r013_frozen": drift},
        "vocab": VOCAB,
        "k_trigger_words": K_TRIGGER_WORDS,
        "axes": [{"id": a, "name": n, "variants": v} for (a, n, v) in AXES],
        "variants": [{"id": i, "axis": a, "name": n, "expect": e, "reachability": r,
                      "in_k_candidates": k, "reading_dependent": rd}
                     for (i, a, n, e, r, k, rd) in VARIANTS],
        "max_variants": 20,
        "canary_pairs": 20,
        "predictions": [{"id": i, "text": t, "expect": e} for (i, t, e) in PREDICTIONS],
        "tolerances": {"recon_tol_r4": RECON_TOL_R4, "recon_tol_fp": RECON_TOL_FP},
        "grid": {"groups": GRID_GROUPS, "minsize": GRID_MINSIZE, "panels": GRID_PANELS,
                 "cells": len(GRID_GROUPS) * len(GRID_MINSIZE) * len(GRID_PANELS) * 2,
                 "declared_cells": 18},
        "panels_file": "panel_R014.txt",
        "cells_file": "_scratch/panel_R014_cells.txt",
        "panels": {pid: len(v) for pid, v in panels.items()},
        "cells_count": len(cells),
        "panel_line_count": n_panel_lines,
        "implementation_notes": {
            "instrument_axis_excluded_from_k": ["V19", "V20"],
            "reason": "A5 轴语义是仪器自检（§7）；其失败路径是 instrument_alerts ⟹ K6（短路优先级最高）。"
                      "把仪器轴计入 K1–K4 会把仪器故障误报成 H0 被证伪；两份的 reachability 均设为 "
                      "SYNTHETIC_ONLY，故按字面读也不会触发 K4。",
            "canonical_order": "重算序 = 多重集的确定性函数：按 (score, forward_return, 并列内出现序号) 稳定排序",
        },
    }
    with open(CONFIG, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2, sort_keys=False)
        fh.write("\n")

    print(f"config   -> {CONFIG}")
    print(f"prereg   sha256 {cfg['prereg_sha256']}")
    print(f"scanroot {len(files)} files / {cfg['scan_root']['total_bytes']} bytes / mismatch={mismatch}")
    print(f"r013     matched {len(drift)}/11 (F01-F11) => zero production drift = {len(drift) == 11}")
    print(f"panels   {cfg['panels']} lines={n_panel_lines}")
    print(f"cells    {len(cells)}")
    print(f"variants {len(VARIANTS)} (max {cfg['max_variants']}) axes={len(AXES)}")


if __name__ == "__main__":
    main()
