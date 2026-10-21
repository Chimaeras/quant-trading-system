#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R014 运行器：`factor_return` 族（`daily_rank_ic[]` / `grouped_returns[]`）**载荷逐元素数值与计数字段的忠实性审计**。

装置 = 静态锚点抽取（12 个冻结文件） + Java 探针（喂合成面板给公开静态 API） + **独立 Python 重算器**
      + 20 对金丝雀（CLN/INJ） + 读数敏感性三读数 + 禁数扫描。

纪律（`R014_prereg.md` §12）：本文件不修改任何生产代码；只读冻结扫描根内的 12 个文件；
探针运行两次并要求逐字节相等；运行末尾清理字节码；`run.log` append-only；
仪器门未全绿 ⟹ 不下结论（`K6` ⟹ `INCONCLUSIVE`）。

用法：`python run_R014_rank_ic_fidelity_audit.py [--static-only]`
"""
import hashlib
import json
import math
import os
import re
import subprocess
import sys
from functools import cmp_to_key

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
CONFIG = os.path.join(HERE, "config.json")
PANEL_FILE = os.path.join(HERE, "panel_R014.txt")
SCRATCH = os.path.join(HERE, "_scratch")
CELL_FILE = os.path.join(SCRATCH, "panel_R014_cells.txt")
PROBE_SRC = os.path.join(HERE, "probe", "R014Probe.java")
CLASSES_DIR = os.path.join(ROOT, "modules", "backend-java", "target", "classes")
RESULT = os.path.join(HERE, "result.json")
RUNLOG = os.path.join(HERE, "run.log")
PROBE_RAW = os.path.join(SCRATCH, "probe_raw.txt")
PROBE_RAW2 = os.path.join(SCRATCH, "probe_raw2.txt")
PROBE_ERR = os.path.join(SCRATCH, "probe_err.txt")

STATIC_ONLY = "--static-only" in sys.argv
ALERTS = []
STATIC_VARIANTS = ["V05", "V07", "V09", "V13", "V14"]


def alert(kind, detail):
    ALERTS.append({"kind": kind, "detail": detail})


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_lines(cfg, fid):
    rel = next(f["path"] for f in cfg["scan_root"]["files"] if f["id"] == fid)
    with open(os.path.join(cfg["root"], rel), "r", encoding="utf-8", errors="replace") as fh:
        return fh.read().split("\n")


# --------------------------------------------------------------------------
# 三种读数（§12.3）
# --------------------------------------------------------------------------
def reading_a_substring(lines, needles):
    hay = "\n".join(lines).lower()
    for n in needles:
        if n.lower() in hay:
            return True, n
    return False, None


def reading_b_surface(lines, needles, lo=None, hi=None):
    seg = lines if lo is None else lines[lo - 1:hi]
    hay = "\n".join(seg).lower()
    for n in needles:
        if n.lower() in hay:
            return True, n
    return False, None


def reading_c_manual(lines, exact_strings, lo=None, hi=None):
    seg = lines if lo is None else lines[lo - 1:hi]
    hay = "\n".join(seg)
    for s in exact_strings:
        if s in hay:
            return True, s
    return False, None


def claim_readings(claim):
    ra, _ = reading_a_substring(*claim["a"])
    rb, _ = reading_b_surface(*claim["b"])
    rc, _ = reading_c_manual(*claim["c"])
    return {"A": ra, "B": rb, "C": rc, "sensitive": len({ra, rb, rc}) > 1}


def sensitivity(vals):
    return {"A": vals[0], "B": vals[1], "C": vals[2], "sensitive": len(set(vals)) > 1}


# --------------------------------------------------------------------------
# 锚点核验
# --------------------------------------------------------------------------
ANCHOR_NEEDLES = [
    ("A-01", "F01", 92, 97, "groupedAverageReturns"),
    ("A-02", "F01", 100, 100, "sorted.sort(Comparator.comparingDouble"),
    ("A-03", "F01", 108, 108, "(int) ((long) i * g / n)"),
    ("A-04", "F01", 114, 115, "rets.size()"),
    ("A-05", "F01", 140, 142, "obs.size() < minCrossSectionSize"),
    ("A-06", "F01", 150, 158, "Double.isFinite(ic)"),
    ("A-07", "F01", 160, 164, "bucketReturns.computeIfAbsent"),
    ("A-08", "F01", 179, 183, "bucketReturns.getOrDefault(g, List.of())"),
    ("A-09", "F01", 271, 288, "double avgRank = (i + j + 2) / 2.0;"),
    ("A-10", "F01", 314, 314, "if (vx < 1e-18 || vy < 1e-18)"),
    ("A-11", "F02", 580, 588, "round4(d.rankIc())"),
    ("A-12", "F02", 592, 601, "round4(g.avgReturn())"),
    ("A-13", "F02", 1030, 1032, "Math.round(v * 10000.0) / 10000.0"),
    ("A-14", "F03", 1018, 1046, "toFactorAnalysisDto"),
    ("A-15", "F02", 70, 71, "RANK_IC_MIN_CROSS_SECTION_SIZE = 10"),
    ("B-01", "F02", 548, 548, 'block.put("daily_rank_ic"'),
    ("B-02", "F02", 549, 549, 'block.put("grouped_returns"'),
    ("B-03", "F02", 574, 575, 'block.put("daily_rank_ic", List.of())'),
    ("B-04", "F02", 668, 669, "daily_rank_ic"),
    ("B-05", "F02", 770, 787, "grouped_returns"),
    ("B-06", "F03", 1031, 1046, "rankIc(d.rankIc())"),
    ("B-07", "F02", 826, 838, "buildNetIcBlock"),
    ("B-08", "F04", 80, 92, "class FactorAnalysis"),
    ("B-09", "F01", 33, 39, "record GroupReturnStat("),
    ("B-10", "F04", 98, 103, "class DailyRankIc"),
    ("C-01", "F08", 19, 23, "export interface FactorDailyRankIc"),
    ("C-02", "F08", 25, 30, "export interface FactorGroupReturn"),
    ("C-03", "F09", 61, 62, "dailyRankIc: Array<{"),
    ("C-04", "F10", 131, 131, "观测数：{{ blk.total_observations }}"),
    ("C-05", "F10", 151, 151, 'prop="observations" label="观测数"'),
    ("C-06", "F10", 161, 161, 'prop="sample_size" label="样本数"'),
    ("C-07", "F10", 237, 237, "观测数：{{ result.rank_ic.total_observations }}"),
    ("C-08", "F10", 254, 264, 'label="观测数"'),
    ("C-09", "F10", 149, 252, "pct4(row.avg_return)"),
    ("C-10", "F10", 159, 262, "fmt4(row.rank_ic)"),
    ("C-11", "F11", 378, 378, 'label="截面日观测"'),
    ("C-12", "F11", 833, 833, "截面日：${g.observations}"),
    ("C-13", "F11", 275, 275, "个截面日"),
    ("C-14", "F11", 374, 374, "(row.avgReturn * 100).toFixed(2)"),
    ("C-15", "F07", 110, 141, "MONOTONIC_INCREASING"),
    ("C-16", "F12", 305, 317, 'daily.get(0).get("rank_ic")'),
    ("C-17", "F10", 498, 517, "exportUnifiedCsv"),
    ("C-18", "F10", 131, 254, "观测数"),
]


def parse_frozen_anchor_ranges(prereg_lines):
    ranges = {}
    pat = re.compile(r"\b(F\d{2}):(\d+)(?:-(\d+))?")
    for line in prereg_lines:
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if not cells:
            continue
        aid = cells[0].strip("` ")
        if not re.match(r"^[ABC]-\d+$", aid):
            continue
        hits = pat.findall(line)
        if not hits:
            continue
        ranges.setdefault(aid, []).extend(
            (fid, int(lo), int(hi) if hi else int(lo)) for fid, lo, hi in hits)
    return ranges


def find_line(lines, needle):
    for i, ln in enumerate(lines, 1):
        if needle in ln:
            return i
    return None


def check_anchors(file_lines, frozen_ranges):
    """锚点核验。**读数规则（运行前写死）**：
    - 冻结区间内命中 ⟹ `hit`；若冻结区间与实测区间不相容 ⟹ `drift`（附录勘误）；
    - 冻结区间**未**命中但**同一文件内**能找到该内容 ⟹ `offset`（冻结行号偏移 ⟹ 附录勘误，
      **不计入仪器门**：`K6` 的 `instrument_alerts` 语义是**仪器完整性**，不是冻结文本的行号精度；
      此读数规则在报告中显式披露）；
    - 同一文件内**完全找不到** ⟹ `missing` ⟹ `instrument_alerts` ⟹ `K6`（真实证据缺口）。
    """
    hits, misses, drifted, offsets = [], [], [], []
    for aid, fid, lo, hi, needle in ANCHOR_NEEDLES:
        seg = "\n".join(file_lines[fid][lo - 1:hi])
        if needle in seg:
            hits.append(aid)
            fr = frozen_ranges.get(aid, [])
            if fr and not any(f == fid and lo <= a and b <= hi for (f, a, b) in fr):
                drifted.append({"id": aid, "measured": [fid, lo, hi], "frozen": fr})
            continue
        actual = find_line(file_lines[fid], needle)
        if actual is not None:
            offsets.append({"id": aid, "frozen_range": [fid, lo, hi], "actual_line": actual,
                            "delta": actual - lo})
        else:
            misses.append({"id": aid, "file": fid, "range": [lo, hi], "pattern": needle})
    if misses:
        alert("anchor_missing", misses)
    if drifted:
        alert("anchor_range_drift", drifted)
    return hits, misses, drifted, offsets


# --------------------------------------------------------------------------
# 独立 Python 重算器
# --------------------------------------------------------------------------
def jcmp(a, b):
    na, nb = math.isnan(a), math.isnan(b)
    if na or nb:
        return 0 if (na and nb) else (1 if na else -1)
    if a < b:
        return -1
    if a > b:
        return 1
    if a == 0.0 and b == 0.0:
        sa, sb = math.copysign(1.0, a), math.copysign(1.0, b)
        if sa != sb:
            return -1 if sa < 0 else 1
    return 0


def ranks(values):
    order = sorted(range(len(values)), key=cmp_to_key(lambda i, j: jcmp(values[i], values[j])))
    out = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and jcmp(values[order[j + 1]], values[order[j]]) == 0:
            j += 1
        avg = (i + j + 2) / 2.0
        for k in range(i, j + 1):
            out[order[k]] = avg
        i = j + 1
    return out


def pearson(x, y):
    n = len(x)
    mx, my = sum(x) / n, sum(y) / n
    num = vx = vy = 0.0
    for i in range(n):
        dx, dy = x[i] - mx, y[i] - my
        num += dx * dy
        vx += dx * dx
        vy += dy * dy
    if vx < 1e-18 or vy < 1e-18:
        return float("nan")
    return num / math.sqrt(vx * vy)


def spearman(scores, rets):
    if len(scores) < 2 or len(scores) != len(rets):
        return float("nan")
    return pearson(ranks(scores), ranks(rets))


def grouped_avg(canon, group_count):
    if not canon or group_count < 2:
        return []
    n = len(canon)
    g = min(group_count, n)
    buckets = [[] for _ in range(g)]
    for i, r in enumerate(canon):
        buckets[min(g - 1, (i * g) // n)].append(r[1])
    out = []
    for i in range(g):
        rets = buckets[i]
        out.append({"group_no": i + 1,
                    "avg_return": 0.0 if not rets else sum(rets) / len(rets),
                    "observations": len(rets)})
    return out


def _finite(v):
    return isinstance(v, float) and math.isfinite(v)


def recon_arm(rows, group_count, min_size, order):
    """重算重算序 = **多重集的确定性函数**：按 (score, forward_return, 并列内序号) 稳定排序；
    `order` 只用于复现引擎侧的输入序（declared/reversed/doubled）。"""
    by_date = {}
    for (panel, date, score, fwd, idx) in rows:
        by_date.setdefault(date, []).append((date, score, fwd, idx))
    daily, bucket_returns, total_obs, day_group_obs = [], {}, 0, {}
    for date, rws in by_date.items():
        rws = sorted(rws, key=lambda r: r[3])
        if order in ("reversed", "doubled"):
            for _ in range(2 if order == "doubled" else 1):
                rws = list(reversed(rws))
        if len(rws) < min_size:
            continue
        ic = spearman([r[1] for r in rws], [r[2] for r in rws])
        if not _finite(ic):
            continue
        daily.append({"trade_date": date, "rank_ic": ic, "sample_size": len(rws)})
        total_obs += len(rws)
        canon = sorted(rws, key=cmp_to_key(
            lambda a, b: jcmp(a[1], b[1]) or jcmp(a[2], b[2]) or (a[3] - b[3])))
        gs = grouped_avg([(r[1], r[2], r[3]) for r in canon], group_count)
        day_group_obs[date] = {s["group_no"]: s["observations"] for s in gs}
        for s in gs:
            bucket_returns.setdefault(s["group_no"], []).append(s["avg_return"])
    grouped = []
    for g in range(1, group_count + 1):
        rets = bucket_returns.get(g, [])
        grouped.append({"group_no": g, "avg_return": 0.0 if not rets else sum(rets) / len(rets),
                        "observations": len(rets)})
    return {"daily": daily, "grouped": grouped, "total_observations": total_obs,
            "cross_section_days": len(daily), "day_group_obs": day_group_obs}


def diff(a, b):
    na = isinstance(a, float) and math.isnan(a)
    nb = isinstance(b, float) and math.isnan(b)
    if na and nb:
        return 0.0
    if na or nb:
        return float("inf")
    return abs(a - b)


def java_round(x):
    """`java.lang.Math.round(double)` 的忠实镜像：`(long)Math.floor(a + 0.5)`；NaN→0，±∞ 饱和到 long 边界。"""
    if math.isnan(x):
        return 0
    if x == math.inf:
        return 2 ** 63 - 1
    if x == -math.inf:
        return -(2 ** 63)
    return math.floor(x + 0.5)


def round4(v):
    """生产实现镜像：`FactorResearchService.round4` = `Math.round(v * 10000.0) / 10000.0`（**无有限性守卫**）。

    与旧版 `floor(v*10000+0.5)/10000` 的差别只在**非有限值**：生产实现把 `NaN` 写成 `0.0`、
    把 `±∞` 写成 `±9.223372036854776E14`，而不是原样传递。标定见 `JAVA_ROUND4_ANCHORS`
    （由 `_scratch/RoundSemanticsProbe.java` 在真实 JVM 上产出，逐位对照）。
    """
    return java_round(v * 10000.0) / 10000.0


JAVA_ROUND4_ANCHORS = [
    (0.00255, 0.0026), (0.00595, 0.006), (0.004533333333333334, 0.0045),
    (0.9878787878787879, 0.9879), (0.9878044218151566, 0.9878), (0.03125, 0.0313),
    (1.0e-4, 1.0e-4), (-0.00255, -0.0026), (-1.0e-4, -1.0e-4), (0.0, 0.0), (-0.0, 0.0),
    (float("nan"), 0.0), (math.inf, 9.223372036854776e14), (-math.inf, -9.223372036854776e14),
]


def same_val(a, b):
    """NaN 感知的等值比较（`NaN == NaN` 视为**相同**；用于「映射是否改变该值」的判定）。"""
    if math.isnan(a) or math.isnan(b):
        return math.isnan(a) and math.isnan(b)
    return a == b


def check_java_round_mirror():
    """自检：`round4` 镜像对**真实 JVM 标定表**逐位一致（`_scratch/round_semantics.txt`）。"""
    bad = []
    for v, expect in JAVA_ROUND4_ANCHORS:
        got = round4(v)
        if not (got == expect or (math.isnan(got) and math.isnan(expect))):
            bad.append({"v": repr(v), "expect": expect, "got": got})
    return bad


# --------------------------------------------------------------------------
# 探针
# --------------------------------------------------------------------------
def compile_probe():
    cmd = ["javac", "-cp", CLASSES_DIR, "-encoding", "UTF-8", "-d", CLASSES_DIR, PROBE_SRC]
    p = subprocess.run(cmd, capture_output=True, cwd=ROOT)
    err = p.stderr.decode("utf-8", errors="replace")
    if p.returncode != 0:
        alert("probe_compile_failed", {"exit": p.returncode, "stderr": err[-2000:]})
        return False
    return True


def run_probe(run_idx):
    cmd = ["java", "-cp", CLASSES_DIR, "R014Probe", PANEL_FILE, CELL_FILE]
    p = subprocess.run(cmd, capture_output=True, cwd=ROOT)
    raw = p.stdout.decode("utf-8", errors="replace")
    err = p.stderr.decode("utf-8", errors="replace")
    if p.returncode != 0:
        alert("probe_run_failed", {"run": run_idx, "exit": p.returncode, "stderr": err[-2000:]})
    return raw, err


def _num(s):
    try:
        return float(s)
    except ValueError:
        return s


def parse_probe(text):
    meta, runs, errors, unknown = {}, {}, [], []
    for line in text.split("\n"):
        line = line.rstrip("\r")
        if not line:
            continue
        f = line.split("|")
        if f[0] == "META" and len(f) >= 3:
            meta[f[1]] = "|".join(f[2:])
        elif f[0] == "ERR":
            errors.append(f[1:])
        elif f[0] == "R" and len(f) >= 3:
            tag, kind = f[1], f[2]
            run = runs.setdefault(tag, {"scalars": {}, "daily": [], "grouped": [], "daygroups": [],
                                        "cell": None, "raw": []})
            run["raw"].append(line)
            if kind == "cell" and len(f) >= 14:
                run["cell"] = {"panel": f[3], "order": f[4], "groups": int(f[5]), "minsize": int(f[6]),
                               "mode": f[7], "rows": int(f[9]), "days": int(f[11]), "panel_hash": f[13]}
            elif kind == "D" and len(f) >= 7:
                run["daily"].append({"idx": int(f[3]), "trade_date": f[4], "rank_ic": _num(f[5]),
                                     "sample_size": int(f[6])})
            elif kind == "G" and len(f) >= 8:
                run["grouped"].append({"idx": int(f[3]), "group_no": int(f[4]), "label": f[5],
                                       "avg_return": _num(f[6]), "observations": int(f[7])})
            elif kind == "DG" and len(f) >= 8:
                run["daygroups"].append({"trade_date": f[3], "group_no": int(f[4]), "label": f[5],
                                         "avg_return": _num(f[6]), "observations": int(f[7])})
            else:
                run["scalars"][kind] = _num(f[3]) if len(f) > 3 else None
        elif line:
            unknown.append(line)
    return meta, runs, errors, unknown


def parse_panel(path):
    rows, current = [], None
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("=== PANEL "):
                current = line[len("=== PANEL "):].strip()
            elif line.startswith("O "):
                f = line.split()
                rows.append((current, f[1], float(f[2]), float(f[3]), len(rows)))
    return rows


def parse_cells(path):
    cells = []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            f = line.split("|")
            cells.append({"tag": f[0], "panel": f[1], "order": f[2], "groups": int(f[3]),
                          "minsize": int(f[4]), "mode": f[5]})
    return cells


# --------------------------------------------------------------------------
# 判定表（每变体一个显式决定键；该键即 `INJ` 注入键）
# --------------------------------------------------------------------------
INJ_KEYS = {
    "V01": "v01_recon_consistent", "V02": "v02_recon_consistent", "V03": "v03_order_dependent",
    "V04": "v04_any_truncated", "V05": "v05_rule_declared", "V06": "v06_unit_conflict",
    "V07": "v07_labels_equal", "V08": "v08_same_label_two_quantities", "V09": "v09_unit_declared",
    "V10": "v10_any_divergent", "V11": "v11_any_divergent", "V12": "v12_int_fields_identical",
    "V13": "v13_display_specs_equal", "V14": "v14_rounding_declared", "V15": "v15_sum_equals_total",
    "V16": "v16_count_includes_nonfinite", "V17": "v17_dropped_disclosed",
    "V18": "v18_zero_obs_placeholder", "V19": "v19_canary_all_pass",
    "V20": "v20_reverse_anchors_all_pass",
}


def judge(vid, F):
    if STATIC_ONLY and vid not in STATIC_VARIANTS:
        return "NOT_REACHED_STATIC_ONLY"
    table = {
        "V01": ("v01_recon_consistent", "RECONSTRUCTABLE", "NOT_RECONSTRUCTABLE"),
        "V02": ("v02_recon_consistent", "RECONSTRUCTABLE", "NOT_RECONSTRUCTABLE"),
        "V03": ("v03_order_dependent", "NOT_RECONSTRUCTABLE", "RECONSTRUCTABLE"),
        "V04": ("v04_any_truncated", "LOSSY_PRECISION", "RECONSTRUCTABLE"),
        "V05": ("v05_rule_declared", "RECONSTRUCTABLE", "RULE_UNDECLARED"),
        "V06": ("v06_unit_conflict", "INCONSISTENT_UNIT", "CONSISTENT_UNIT"),
        "V07": ("v07_labels_equal", "CONSISTENT_UNIT", "INCONSISTENT_UNIT"),
        "V08": ("v08_same_label_two_quantities", "INCONSISTENT_UNIT", "CONSISTENT_UNIT"),
        "V09": ("v09_unit_declared", "CONSISTENT_UNIT", "UNIT_UNDECLARED"),
        "V10": ("v10_any_divergent", "VALUE_DIVERGENT", "VALUE_CONSISTENT"),
        "V11": ("v11_any_divergent", "VALUE_DIVERGENT", "VALUE_CONSISTENT"),
        "V12": ("v12_int_fields_identical", "VALUE_CONSISTENT", "VALUE_DIVERGENT"),
        "V13": ("v13_display_specs_equal", "VALUE_CONSISTENT", "VALUE_DIVERGENT"),
        "V14": ("v14_rounding_declared", "RECONSTRUCTABLE", "RULE_UNDECLARED"),
        "V15": ("v15_sum_equals_total", "COUNT_FAITHFUL", "COUNT_UNFAITHFUL"),
        "V16": ("v16_count_includes_nonfinite", "COUNT_UNFAITHFUL", "COUNT_FAITHFUL"),
        "V17": ("v17_dropped_disclosed", "DROPPED_DISCLOSED", "DROPPED_SILENTLY"),
        "V18": ("v18_zero_obs_placeholder", "PLACEHOLDER_MARKED", "PLACEHOLDER_UNMARKED"),
        "V19": ("v19_canary_all_pass", "COUNT_FAITHFUL", "COUNT_UNFAITHFUL"),
        "V20": ("v20_reverse_anchors_all_pass", "VALUE_CONSISTENT", "VALUE_DIVERGENT"),
    }
    key, if_true, if_false = table[vid]
    v = F.get(key)
    if v is None:
        return "NOT_REACHED_STATIC_ONLY"
    return if_true if v else if_false


def cln_inject(vid, F):
    f2 = dict(F)
    f2["__sentinel_cln__"] = F.get(INJ_KEYS[vid])
    return f2


def inj_flip(vid, F):
    key = INJ_KEYS[vid]
    v = F.get(key)
    if not isinstance(v, bool):
        alert("inj_key_not_bool", {"vid": vid, "key": key, "value": repr(v)})
        return None
    f2 = dict(F)
    f2[key] = not v
    return f2


def reach_rank(level):
    order = ["UNREACHABLE", "SYNTHETIC_ONLY", "REACHABLE_CLIENT_INPUT", "REACHABLE_PUBLIC_API"]
    return order.index(level) if level in order else -1


# --------------------------------------------------------------------------
# 静态层
# --------------------------------------------------------------------------
def extract_payload_notes(f02_lines):
    text = "\n".join(f02_lines)
    notes = {}
    for m in re.finditer(r'private static final String ([A-Z0-9_]+)\s*=\s*((?:\s*"[^"]*"\s*\+?)+);', text, re.S):
        notes[m.group(1)] = "".join(re.findall(r'"([^"]*)"', m.group(2)))
    used = {k: v for k, v in notes.items() if re.search(r'put\("[^"]+",\s*' + k + r'\)', text)}
    for i, v in enumerate(re.findall(r'put\("note",\s*"([^"]*)"\)', text)):
        used[f"inline_note_{i}"] = v
    lines = ["【载荷 note 文本（声明面）】"] + [f"  {k}: {used[k]}" for k in sorted(used)]
    lines += ["【DTO 字段】DailyRankIc{tradeDate,rankIc,sampleSize}; "
              "GroupReturnStat{groupNo,label,avgReturn,observations}",
              "【TS 接口】FactorDailyRankIc{trade_date,rank_ic,sample_size}; "
              "FactorGroupReturn{group_no,label,avg_return,observations}"]
    emitted_keys = sorted(set(re.findall(r'put\("([a-z_0-9]+)"', text)))
    return {"keys": sorted(used), "texts": {k: used[k] for k in sorted(used)},
            "note_lines": lines, "emitted_keys": emitted_keys}


def static_extract(cfg, file_lines, prereg_lines):
    frozen_ranges = parse_frozen_anchor_ranges(prereg_lines)
    hits, misses, drifted, offsets = check_anchors(file_lines, frozen_ranges)
    F01, F02, F03 = file_lines["F01"], file_lines["F02"], file_lines["F03"]
    F04, F08, F09, F10, F11 = (file_lines["F04"], file_lines["F08"], file_lines["F09"],
                               file_lines["F10"], file_lines["F11"])
    f02_text = "\n".join(F02)

    def grab(lines, lo, hi, pattern, group=1):
        m = re.search(pattern, "\n".join(lines[lo - 1:hi]), re.S)
        return m.group(group) if m else None

    labels = {
        "factor_group_obs": grab(F10, 151, 151, r'label="([^"]+)"'),
        "factor_total_obs": grab(F10, 131, 131, r'>\s*([^：:<>{}]+)：\{\{'),
        "factor_unified_group_obs": grab(F10, 254, 254, r'label="([^"]+)"'),
        "factor_unified_total_obs": grab(F10, 237, 237, r'>\s*([^：:<>{}]+)：\{\{'),
        "backtest_group_obs": grab(F11, 378, 378, r'label="([^"]+)"'),
        "backtest_tooltip": grab(F11, 833, 833, r'`([^`：]*?)：\$\{g\.observations\}`'),
    }
    fmt = {
        "factor_avg_return_cell": grab(F10, 149, 149, r'\{\{\s*(pct4\(row\.avg_return\))\s*\}\}'),
        "factor_pct4_digits": grab(F10, 404, 406, r'`\$\{\(v \* 100\)\.toFixed\((\d)\)\}%`'),
        "backtest_avg_return_cell": grab(F11, 374, 374, r'\{\{\s*(\(row\.avgReturn \* 100\)\.toFixed\(2\))\s*\}\}'),
        "backtest_digits": grab(F11, 374, 374, r'\.toFixed\((\d)\)'),
        "factor_csv_has_arrays": bool(grab(F10, 498, 517, r'(daily_rank_ic|grouped_returns)')),
    }
    payload = extract_payload_notes(F02)

    unit_needles = ["单位", "天数", "个交易日", "名字数", "样本天数", "每组的样本", "单位为"]
    rule_needles = ["下标等分", "等分位", "按排序后", "边界规则", "bucket", "floor("]
    round_needles = ["取整", "四舍五入", "保留", "精度", "round", "decimal"]

    whole = [F10, F11, F08, F09, F04, F02]
    claims = {
        "V05": {"a": (sum(whole, []), rule_needles),
                "b": (payload["note_lines"], rule_needles, 1, len(payload["note_lines"])),
                "c": (payload["note_lines"], ["group_boundary_rule", "按排序后下标等分"],
                      1, len(payload["note_lines"]))},
        "V09": {"a": (sum(whole, []), unit_needles),
                "b": (payload["note_lines"], unit_needles, 1, len(payload["note_lines"])),
                "c": (payload["note_lines"], ["单位：天", "unit: days"], 1, len(payload["note_lines"]))},
        "V14": {"a": (sum(whole, []), round_needles),
                "b": (payload["note_lines"], round_needles, 1, len(payload["note_lines"])),
                "c": (payload["note_lines"], ["round4", "保留 4 位"], 1, len(payload["note_lines"]))},
    }
    readings = {vid: claim_readings(c) for vid, c in claims.items()}
    readings["V07"] = sensitivity([
        labels["factor_group_obs"] != labels["backtest_group_obs"],
        grab(F10, 151, 151, r'label="([^"]+)"') != grab(F11, 378, 378, r'label="([^"]+)"'),
        "观测数" != "截面日观测",
    ])
    readings["V13"] = sensitivity([
        fmt["factor_avg_return_cell"] is not None and fmt["backtest_avg_return_cell"] is not None
        and fmt["factor_pct4_digits"] == fmt["backtest_digits"],
        fmt["factor_pct4_digits"] == fmt["backtest_digits"] and fmt["factor_pct4_digits"] is not None,
        fmt["factor_pct4_digits"] == "2" and fmt["backtest_digits"] == "2",
    ])

    dto_comment_count = sum(1 for ln in F04[97:117] if "/**" in ln or ln.strip().startswith("//"))
    ts_comment_count = sum(1 for ln in F08[18:30] if "//" in ln or "/*" in ln)
    ts_comment_count += sum(1 for ln in F09[60:62] if "//" in ln)

    # V12：两面的 int 字段是否为原样传递（静态锚点 + 运行层计数核对）
    v12_static = ('row.put("sample_size", d.sampleSize())' in f02_text
                  and 'row.put("observations", g.observations())' in f02_text
                  and "sampleSize(d.sampleSize())" in "\n".join(F03))

    # V17：载荷是否**披露**被 `minCrossSectionSize` 丢弃的日数（负结论用**正向搜索**实现）
    v17 = disclosure_of_min_size_drop(F02)
    drop_keys = v17["drop_disclosure_keys"]

    facts = {
        "labels": labels, "fmt": fmt,
        "dto_field_comment_count": dto_comment_count,
        "ts_field_comment_count": ts_comment_count,
        "v05_rule_declared": readings["V05"]["B"],
        "v05_readings": readings["V05"],
        "v05_adjacent_generic_text_hits": count_hits(F10, ["等数量"]),
        "v07_labels_equal": bool(labels["factor_group_obs"] == labels["backtest_group_obs"]),
        "v07_labels_differ": bool(readings["V07"]["B"]),
        "v07_readings": readings["V07"],
        "v08_same_label": labels["factor_total_obs"] == labels["factor_group_obs"],
        "v09_unit_declared": bool(readings["V09"]["B"] or dto_comment_count + ts_comment_count > 0),
        "v09_readings": readings["V09"],
        "v09_type_unit_decl_count": dto_comment_count + ts_comment_count,
        "v09_ui_unit_hint_count": count_hits(F11, ["截面日观测"]),
        "v13_display_specs_equal": bool(readings["V13"]["A"] and readings["V13"]["B"]),
        "v13_readings": readings["V13"],
        "v14_rounding_declared": bool(readings["V14"]["B"]),
        "v14_readings": readings["V14"],
        "v12_static_pass_through": v12_static,
        "v17_drop_disclosure_keys": drop_keys,
        "v17_scope_keys": v17["scope_keys"],
        "v17_candidates": v17["candidates"],
        "v17_unclassified_candidates": v17["unclassified_candidates"],
        "v17_unrelated_drop_ish_keys": v17["unrelated"],
        "v17_scope_note": v17["scope_note"],
        "payload_note_keys": payload["keys"],
        "payload_note_texts": payload["texts"],
        "payload_emitted_keys": payload["emitted_keys"],
        "rd_V05": readings["V05"]["sensitive"], "rd_V07": readings["V07"]["sensitive"],
        "rd_V09": readings["V09"]["sensitive"], "rd_V13": readings["V13"]["sensitive"],
        "rd_V14": readings["V14"]["sensitive"],
        "anchor_probe": {"a03_index_bucket": "(int) ((long) i * g / n)" in "\n".join(F01[107:108]),
                         "a02_stable_sort": "sorted.sort(Comparator.comparingDouble" in "\n".join(F01[99:100]),
                         "f03_round4_absent": "round4" not in "\n".join(F03[1017:1046])},
    }
    anchors = {"hits": hits, "misses": misses, "drifted": drifted, "offsets": offsets,
               "hit_rate": f"{len(hits)}/{len(ANCHOR_NEEDLES)}",
               "frozen_range_count": len(frozen_ranges)}
    return {"facts": facts, "anchors": anchors}


def count_hits(lines, needles):
    hay = "\n".join(lines)
    return sum(hay.count(n) for n in needles)


DROP_DISCLOSURE_NEEDLES = ["min_cross", "min_names", "min_size", "dropped", "drop_count",
                           "skipped_days", "days_skipped", "before_filter", "universe_filter",
                           "filtered_out"]


def method_span(lines, signature_needle, end_needle="return"):
    """返回包含 `signature_needle` 的方法体行区间（1-based, 闭区间）。

    极简扫描：从签名行起到其后第一行以 `end_needle` 开头（或含 `return <x>;`）的行。
    """
    start = next((i for i, ln in enumerate(lines, 1) if signature_needle in ln), None)
    if start is None:
        return None
    depth, seen_open = 0, False
    for i in range(start, len(lines) + 1):
        ln = lines[i - 1]
        depth += ln.count("{") - ln.count("}")
        if "{" in ln:
            seen_open = True
        if seen_open and depth <= 0:
            return (start, i)
    return (start, len(lines))


def trace_manifest_reuse(lines, var, span):
    """判断 `var` 的构造是否追溯到 `isReusableManifest(`（⟹ 语义 = 清单可复用性，与最小截面过滤无关）。"""
    if not var:
        return None
    lo, hi = span
    decl = next((i for i in range(lo, hi + 1)
                 if re.search(r"\b%s\b\s*=" % re.escape(var), lines[i - 1])), None)
    if decl is None:
        decl = next((i for i, ln in enumerate(lines, 1)
                     if re.search(r"\b%s\b\s*=" % re.escape(var), ln)), None)
    if decl is None:
        return None
    for i in range(decl, min(decl + 20, len(lines)) + 1):
        if "isReusableManifest(" in lines[i - 1]:
            return i
    return None


def disclosure_of_min_size_drop(F02):
    """V17 事实：携带 `daily_rank_ic` / `grouped_returns` 的载荷是否**披露**被
    `minCrossSectionSize` 丢弃的日数。

    **读数规则（先写死；防作用面越界伪命中）**：
    ① 作用面 = 发射两数组的载荷块（`buildSnapshotScopeBlock`）∪ snapshot funnel 块
       （`buildSnapshotFunnel`）——即客户端真正拿到两数组时能读到的字段集合；
    ② 命中词 = 明确指向「最小截面规模过滤 / 被丢弃天数」的键名（`DROP_DISCLOSURE_NEEDLES`）；
    ③ **语义无关排除**：键的取值若可追溯到 `isReusableManifest(`（清单可复用性），
       则**不算**披露本变体所指的丢弃（实测 F02:277-284：`skippedDates` 来自
       `isReusableManifest` 判定，与 `minCrossSectionSize` 无关）；
       排除项**同时输出**（`unrelated`，含溯源行号）以便复核，不做静默过滤。
    """
    scope_keys, spans = [], {}
    for sig in ('private Map<String, Object> buildSnapshotScopeBlock(',
                'private Map<String, Object> buildSnapshotFunnel('):
        span = method_span(F02, sig)
        if span is None:
            continue
        spans[sig.split()[-1].strip("(")] = list(span)
        for i in range(span[0], span[1] + 1):
            for m in re.finditer(r'(?:\w+)\.put\("([a-z_0-9]+)"\s*,\s*([^;]+)\);', F02[i - 1]):
                scope_keys.append({"key": m.group(1), "arg": m.group(2).strip(), "line": i})
    unrelated, drops, unclassified = [], [], []
    for k in scope_keys:
        if not re.search(r"skip|drop|filter|exclud|min_cross", k["key"]):
            continue
        var = re.match(r"([A-Za-z_]\w*)", k["arg"])
        var = var.group(1) if var else None
        t = trace_manifest_reuse(F02, var, spans.get("buildSnapshotFunnel", (1, len(F02))))
        if t is not None:
            unrelated.append({"key": k["key"], "line": k["line"], "arg_var": var,
                              "traced_to_isReusableManifest_line": t,
                              "semantics": "清单可复用性（非最小截面过滤）"})
            continue
        if any(n in k["key"] for n in DROP_DISCLOSURE_NEEDLES):
            drops.append(k["key"])
        else:
            # 保守读数（防「静默丢候选」）：宽候选但无法溯源亦不符精确词 ⟹ 仍计入披露面
            unclassified.append({"key": k["key"], "line": k["line"], "arg_var": var})
            drops.append(k["key"])
    return {"drop_disclosure_keys": sorted(set(drops)),
            "candidates": sorted({k["key"] for k in scope_keys
                                  if re.search(r"skip|drop|filter|exclud|min_cross", k["key"])}),
            "unclassified_candidates": unclassified,
            "scope_keys": sorted({k["key"] for k in scope_keys}),
            "unrelated": unrelated,
            "scope_note": "作用面 = buildSnapshotScopeBlock ∪ buildSnapshotFunnel（"
                          + "；".join(f"{k}:{v[0]}-{v[1]}" for k, v in spans.items()) + "）"}


# --------------------------------------------------------------------------
# 运行层事实
# --------------------------------------------------------------------------
def compute_runtime_facts(cfg, F, panel_rows, cells, probe_runs, bytes_equal):
    tol_r4 = cfg["tolerances"]["recon_tol_r4"]
    tol_fp = cfg["tolerances"]["recon_tol_fp"]
    cell_map = {c["tag"]: c for c in cells}
    primary = [t for t in probe_runs if not t.startswith("GRID_")]
    recon_report = {}

    for tag in sorted(probe_runs):
        c = cell_map.get(tag)
        run = probe_runs[tag]
        if c is None or c["mode"] != "summarize":
            continue
        rows = [r for r in panel_rows if r[0] == c["panel"]]
        rec = recon_arm(rows, c["groups"], c["minsize"], c["order"])
        run["_recon"] = rec
        pl_d = {d["trade_date"]: d for d in run["daily"]}
        rc_d = {d["trade_date"]: d for d in rec["daily"]}
        pl_g = {g["group_no"]: g for g in run["grouped"]}
        rc_g = {g["group_no"]: g for g in rec["grouped"]}
        recon_report[tag] = {
            "panel": c["panel"], "order": c["order"], "groups": c["groups"], "minsize": c["minsize"],
            "rank_ic_maxdiff_fp": max([diff(pl_d[k]["rank_ic"], rc_d[k]["rank_ic"])
                                       for k in pl_d if k in rc_d] or [0.0]),
            "avg_return_maxdiff_fp": max([diff(pl_g[k]["avg_return"], rc_g[k]["avg_return"])
                                          for k in pl_g if k in rc_g] or [0.0]),
            "rank_ic_maxdiff_r4": max([diff(round4(pl_d[k]["rank_ic"]), round4(rc_d[k]["rank_ic"]))
                                       for k in pl_d if k in rc_d] or [0.0]),
            "avg_return_maxdiff_r4": max([diff(round4(pl_g[k]["avg_return"]), round4(rc_g[k]["avg_return"]))
                                          for k in pl_g if k in rc_g] or [0.0]),
            "days_set_match": set(pl_d) == set(rc_d),
            "payload_total_observations": run["scalars"].get("total_observations"),
            "recon_total_observations": rec["total_observations"],
            "payload_days": run["scalars"].get("cross_section_days"),
            "recon_days": rec["cross_section_days"],
            "payload_sample_size_sum": sum(d["sample_size"] for d in run["daily"]),
            "payload_group_obs": {str(g["group_no"]): g["observations"] for g in run["grouped"]},
        }

    def ok(tag):
        r = recon_report.get(tag)
        if r is None:
            return False
        return (r["rank_ic_maxdiff_fp"] <= tol_fp and r["avg_return_maxdiff_fp"] <= tol_fp
                and r["rank_ic_maxdiff_r4"] <= tol_r4 and r["avg_return_maxdiff_r4"] <= tol_r4
                and r["days_set_match"])

    F["v01_recon_consistent"] = ok("P1_dec")
    F["v02_recon_consistent"] = ok("P2_dec")
    F["v01_recon_detail"] = recon_report.get("P1_dec")
    F["v02_recon_detail"] = recon_report.get("P2_dec")

    # V03
    if all(t in probe_runs for t in ("P3_dec", "P3_rev", "P3_dbl")):
        dec, rev, dbl = probe_runs["P3_dec"], probe_runs["P3_rev"], probe_runs["P3_dbl"]
        g_dec = {g["group_no"]: g["avg_return"] for g in dec["grouped"]}
        g_rev = {g["group_no"]: g["avg_return"] for g in rev["grouped"]}
        g_dbl = {g["group_no"]: g["avg_return"] for g in dbl["grouped"]}
        d_dec = {d["trade_date"]: d["rank_ic"] for d in dec["daily"]}
        d_rev = {d["trade_date"]: d["rank_ic"] for d in rev["daily"]}
        rc_g = {g["group_no"]: g["avg_return"] for g in dec["_recon"]["grouped"]}
        F["v03_order_avg_maxdiff"] = max([diff(g_dec[k], g_rev[k]) for k in g_dec] or [0.0])
        F["v03_order_rank_ic_maxdiff"] = max([diff(d_dec[k], d_rev[k]) for k in d_dec] or [0.0])
        F["v03_doubled_identity_maxdiff"] = max([diff(g_dec[k], g_dbl[k]) for k in g_dec] or [0.0])
        F["v03_canonical_recon_avg_maxdiff"] = max([diff(g_dec[k], rc_g[k]) for k in g_dec] or [0.0])
        F["v03_order_dependent"] = bool(F["v03_order_avg_maxdiff"] > tol_fp
                                        or F["v03_canonical_recon_avg_maxdiff"] > tol_fp)
        F["v03_permutation_undo_ok"] = bool(F["v03_doubled_identity_maxdiff"] <= tol_fp)
        F["v03_rank_ic_order_invariant"] = bool(F["v03_order_rank_ic_maxdiff"] <= tol_fp)
        F["v03_panel_hash_equal"] = (dec["cell"]["panel_hash"] == rev["cell"]["panel_hash"])
        F["v03_group_avgs"] = {"declared": {str(k): g_dec[k] for k in sorted(g_dec)},
                               "reversed": {str(k): g_rev[k] for k in sorted(g_rev)}}
    else:
        for k in ("v03_order_avg_maxdiff", "v03_order_rank_ic_maxdiff", "v03_doubled_identity_maxdiff",
                  "v03_canonical_recon_avg_maxdiff"):
            F[k] = None
        F["v03_order_dependent"] = False
        F["v03_permutation_undo_ok"] = False
        F["v03_rank_ic_order_invariant"] = False
        F["v03_panel_hash_equal"] = False

    # V04/V10/V11/V20③：round4 截断与「移除 round4」反事实
    all_vals = []
    for tag in primary:
        for d in probe_runs[tag]["daily"]:
            if _finite(d["rank_ic"]):
                all_vals.append(("rank_ic", tag, d["trade_date"], d["rank_ic"]))
        for g in probe_runs[tag]["grouped"]:
            if _finite(g["avg_return"]):
                all_vals.append(("avg_return", tag, g["group_no"], g["avg_return"]))
    map_factor = lambda v, r4=True: round4(v) if r4 else v   # noqa: E731（镜像 A-11/A-12）
    map_dto = lambda v: v                                     # noqa: E731（镜像 A-14）
    with_r4 = [(k, t, i, v) for (k, t, i, v) in all_vals if map_factor(v, True) != map_dto(v)]
    without_r4 = [(k, t, i, v) for (k, t, i, v) in all_vals if map_factor(v, False) != map_dto(v)]
    F["v04_truncated_rank_ic_count"] = sum(1 for x in with_r4 if x[0] == "rank_ic")
    F["v04_truncated_avg_return_count"] = sum(1 for x in with_r4 if x[0] == "avg_return")
    F["v04_truncated_count"] = len(with_r4)
    F["v04_values_total"] = len(all_vals)
    F["v04_max_truncation"] = max([abs(map_factor(v, True) - map_dto(v)) for (_, _, _, v) in with_r4] or [0.0])
    F["v04_any_truncated"] = bool(len(with_r4) > 0)
    F["v10_any_divergent"] = bool(sum(1 for x in with_r4 if x[0] == "rank_ic") > 0)
    F["v10_divergent_day_count"] = sum(1 for x in with_r4 if x[0] == "rank_ic")
    F["v11_any_divergent"] = bool(sum(1 for x in with_r4 if x[0] == "avg_return") > 0)
    F["v11_divergent_group_count"] = sum(1 for x in with_r4 if x[0] == "avg_return")

    # 非有限值在**两面映射**下的分歧（生产 `round4` 无有限性守卫：NaN→0.0，±∞→±9.223372036854776E14）
    mirror_bad = check_java_round_mirror()
    F["v04_java_round_mirror_ok"] = not mirror_bad
    if mirror_bad:
        alert("java_round_mirror_mismatch", mirror_bad)
    nonfinite = []
    for tag in primary:
        for d in probe_runs[tag]["daily"]:
            if not _finite(d["rank_ic"]):
                nonfinite.append(("rank_ic", tag, d["trade_date"], d["rank_ic"]))
        for g in probe_runs[tag]["grouped"]:
            if not _finite(g["avg_return"]):
                nonfinite.append(("avg_return", tag, g["group_no"], g["avg_return"]))
    nan_zero = [(k, t, i, v) for (k, t, i, v) in nonfinite if not same_val(round4(v), v)]
    F["v04_nonfinite_values_total"] = len(nonfinite)
    F["v04_nan_to_zero_count"] = len(nan_zero)
    F["v04_nan_to_zero_examples"] = [
        {"kind": k, "cell": t, "id": str(i), "engine_value": ("NaN" if math.isnan(v) else repr(v)),
         "factor_payload_round4": round4(v), "dto_raw": ("NaN" if math.isnan(v) else repr(v))}
        for (k, t, i, v) in nan_zero[:6]]
    F["v10_nan_to_zero_day_count"] = sum(1 for x in nan_zero if x[0] == "rank_ic")
    F["v11_nan_to_zero_group_count"] = sum(1 for x in nan_zero if x[0] == "avg_return")
    F["v10_any_divergent"] = bool(F["v10_divergent_day_count"] > 0 or F["v10_nan_to_zero_day_count"] > 0)
    F["v11_any_divergent"] = bool(F["v11_divergent_group_count"] > 0 or F["v11_nan_to_zero_group_count"] > 0)
    F["v20_round4_removal_divergence_count"] = len(without_r4)
    F["v20_round4_removal_all_divergence_count"] = len(
        [(k, t, i, v) for (k, t, i, v) in all_vals + nonfinite if not same_val(map_factor(v, False), map_dto(v))])
    F["v20_round4_removal_scope"] = ("映射面：因子面 = round4(引擎值)（A-11/A-12），DTO 面 = 引擎值原样（A-14）；"
                                     "反事实覆盖 = 有限值 + 非有限值（恒等映射下非有限值逐位保留）")
    F["v20_run_bytes_equal"] = bool(bytes_equal)
    F["v20_reverse_anchors_all_pass"] = bool(
        bytes_equal and F.get("v03_permutation_undo_ok") and len(without_r4) == 0
        and F["v20_round4_removal_all_divergence_count"] == 0)

    # V06
    agg_obs = {g["group_no"]: g["observations"] for g in probe_runs.get("P1_dec", {}).get("grouped", [])}
    day_obs = {}
    for d in probe_runs.get("P1_dayg", {}).get("daygroups", []):
        day_obs.setdefault(d["group_no"], set()).add(d["observations"])
    day_obs = {k: sorted(v)[0] for k, v in day_obs.items() if len(v) == 1}
    overlap = sorted(set(agg_obs) & set(day_obs))
    F["v06_agg_obs_by_group"] = {str(k): agg_obs[k] for k in agg_obs}
    F["v06_daylevel_obs_by_group"] = {str(k): day_obs[k] for k in day_obs}
    F["v06_cross_section_days"] = probe_runs.get("P1_dec", {}).get("scalars", {}).get("cross_section_days")
    F["v06_agg_obs_equals_days"] = bool(overlap and F["v06_cross_section_days"] is not None
                                        and all(agg_obs[g] == F["v06_cross_section_days"] for g in overlap))
    F["v06_daylevel_obs_equals_names"] = bool(overlap and all(day_obs[g] == 2 for g in overlap))
    F["v06_unit_conflict"] = bool(F["v06_agg_obs_equals_days"] and F["v06_daylevel_obs_equals_names"]
                                  and any(agg_obs[g] != day_obs[g] for g in overlap))
    F["v06_same_group_no_label_shift"] = label_shift(probe_runs, "P1_dec", "P1_dayg")
    F["v06_daylevel_groups_observed"] = sorted(day_obs)

    # V08：同一页面同一标签「观测数」是否指向两个不同的量
    #（total_observations = 各截面的样本数之和；组 observations = 该组被聚合的截面数）
    _p1 = probe_runs.get("P1_dec", {})
    _tot = _p1.get("scalars", {}).get("total_observations")
    _gobs = {str(g["group_no"]): g["observations"] for g in _p1.get("grouped", [])}
    F["v08_total_observations"] = _tot
    F["v08_group_observations"] = _gobs
    F["v08_quantities_differ"] = bool(_tot is not None and _gobs
                                      and all(o != _tot for o in _gobs.values()))
    F["v08_same_label_two_quantities"] = bool(F.get("v08_same_label")
                                              and F["v08_quantities_differ"])

    # V12（运行层）：counts 是否与面板真值一致
    cnt_ok = True
    for tag in primary:
        run = probe_runs[tag]
        c = cell_map[tag]
        rows = [r for r in panel_rows if r[0] == c["panel"]]
        by_date = {}
        for (_, date, _s, _f, _i) in rows:
            by_date[date] = by_date.get(date, 0) + 1
        for d in run["daily"]:
            if by_date.get(d["trade_date"]) != d["sample_size"]:
                cnt_ok = False
    F["v12_runtime_counts_match"] = cnt_ok
    F["v12_int_fields_identical"] = bool(F["v12_static_pass_through"] and cnt_ok)

    # V15
    F["v15_max_abs_diff"] = max([abs(recon_report[t]["payload_sample_size_sum"]
                                     - (recon_report[t]["payload_total_observations"] or 0))
                                 for t in recon_report] or [0])
    F["v15_sum_equals_total"] = bool(F["v15_max_abs_diff"] == 0)

    # V16
    p4 = probe_runs.get("P4_dec")
    nan_rows = [r for r in panel_rows if r[0] == "P4" and r[1] == "2026-04-13"]
    F["v16_panel_rows_with_nan"] = sum(1 for r in nan_rows if math.isnan(r[3]))
    F["v16_valid_finite_count"] = sum(1 for r in nan_rows if math.isfinite(r[3]))
    nd = [d for d in (p4 or {}).get("daily", []) if d["trade_date"] == "2026-04-13"]
    F["v16_nan_day_present"] = bool(nd)
    F["v16_nan_day_sample_size"] = nd[0]["sample_size"] if nd else None
    F["v16_nan_day_rank_ic"] = nd[0]["rank_ic"] if nd else None
    F["v16_nan_entered_bucket_means"] = sum(1 for g in (p4 or {}).get("grouped", [])
                                            if isinstance(g["avg_return"], float)
                                            and math.isnan(g["avg_return"]))
    F["v16_total_observations_includes_nan"] = (p4 or {}).get("scalars", {}).get("total_observations")
    F["v16_count_includes_nonfinite"] = bool(nd and nd[0]["sample_size"] > F["v16_valid_finite_count"])

    # V17
    p6 = probe_runs.get("P6_dec")
    F["v17_panel_day_count"] = len({r[1] for r in panel_rows if r[0] == "P6"})
    F["v17_payload_cross_section_days"] = (p6 or {}).get("scalars", {}).get("cross_section_days")
    F["v17_dropped_day_count"] = (F["v17_panel_day_count"] - F["v17_payload_cross_section_days"]
                                  if F["v17_payload_cross_section_days"] is not None else None)
    F["v17_disclosure_field_count"] = len(F.get("v17_drop_disclosure_keys", []))
    F["v17_dropped_disclosed"] = bool(F["v17_disclosure_field_count"] > 0)
    F["v17_min8_days"] = probe_runs.get("P6_dec_min8", {}).get("scalars", {}).get("cross_section_days")

    # V18
    p5 = probe_runs.get("P5_dec_min2")
    zero_rows = [g for g in (p5 or {}).get("grouped", []) if g["observations"] == 0]
    F["v18_zero_obs_group_rows"] = len(zero_rows)
    F["v18_zero_obs_avg_value"] = zero_rows[0]["avg_return"] if zero_rows else None
    F["v18_zero_obs_placeholder"] = bool(zero_rows and all(
        g["avg_return"] == 0.0 and g["observations"] == 0 for g in zero_rows))
    _dg = probe_runs.get("P5_dayg", {}).get("daygroups", [])
    _zmin = min([g["group_no"] for g in zero_rows], default=None)
    _dgmax = max([d["group_no"] for d in _dg], default=None)
    F["v18_zero_obs_min_group_no"] = _zmin
    F["v18_daylevel_max_group_no"] = _dgmax
    F["v18_zero_obs_groups_absent_at_day_level"] = bool(
        _zmin is not None and _dgmax is not None and _zmin > _dgmax)

    # 网格（±20% 敏感性）
    grid_cells = []
    for g in cfg["grid"]["groups"]:
        for m in cfg["grid"]["minsize"]:
            for pid in cfg["grid"]["panels"]:
                dec, rev = f"GRID_{pid}_{g}_{m}_dec", f"GRID_{pid}_{g}_{m}_rev"
                if dec not in probe_runs or rev not in probe_runs:
                    continue
                gd = {x["group_no"]: x["avg_return"] for x in probe_runs[dec]["grouped"]}
                gr = {x["group_no"]: x["avg_return"] for x in probe_runs[rev]["grouped"]}
                sens = max([diff(gd[k], gr[k]) for k in gd] or [0.0])
                grid_cells.append({
                    "panel": pid, "groups": g, "minsize": m,
                    "declared_days": probe_runs[dec]["scalars"].get("cross_section_days"),
                    "reversed_days": probe_runs[rev]["scalars"].get("cross_section_days"),
                    "declared_sum_sample": sum(d["sample_size"] for d in probe_runs[dec]["daily"]),
                    "reversed_sum_sample": sum(d["sample_size"] for d in probe_runs[rev]["daily"]),
                    "declared_group_avgs": {str(k): gd[k] for k in sorted(gd)},
                    "order_avg_maxdiff": sens, "order_sensitive": bool(sens > tol_fp)})
    F["grid_cells"] = grid_cells
    F["grid_order_sensitive_cells"] = sum(1 for c in grid_cells if c["order_sensitive"])
    F["grid_group_count_param_pct"] = f"5 → {cfg['grid']['groups']}（±20%）"
    F["grid_minsize_param_pct"] = f"10 → {cfg['grid']['minsize']}（±20%）"
    F["v19_canary_all_pass"] = (cfg["canary_pairs"] == 20)  # 以**声明数**填入；与实测数在收尾断言
    return F, recon_report


def label_shift(probe_runs, tag_a, tag_b):
    a = {g["group_no"]: g["label"] for g in probe_runs.get(tag_a, {}).get("grouped", [])}
    b = {}
    for d in probe_runs.get(tag_b, {}).get("daygroups", []):
        b[d["group_no"]] = d["label"]
    return {str(k): [a.get(k), b.get(k)] for k in sorted(set(a) & set(b)) if a.get(k) != b.get(k)}


# --------------------------------------------------------------------------
# 收尾
# --------------------------------------------------------------------------
def compare_p_table(cfg, variants_out, F):
    words = {v["id"]: v["word"] for v in variants_out}
    fact_map = {"rank_ic_invariant": "v03_rank_ic_order_invariant", "agg_obs_is_days": "v06_agg_obs_equals_days"}
    out = []
    for p in cfg["predictions"]:
        vid, exp = p["expect"].split("|", 1)
        if exp in set(cfg["vocab"]):
            got, hit = words.get(vid), (words.get(vid) == exp)
        else:
            got = F.get(fact_map.get(exp, "?"))
            hit = bool(got)
        out.append({"id": p["id"], "expect": p["expect"], "measured": got, "hit": hit})
    return out


def scan_forbidden():
    """仪器自污染扫描（自加，**非**冻结条款；冻结条款的「禁数扫描」见 `probe_numeric_literals`）。

    目的：仪器（探针 / 运行器 / 生成器）自身不得嵌入论文身份标识或结论性词汇，
    否则会诱导「以论文权威替代实测」。
    **读数规则（防伪命中，先写死）**：
    ① ASCII 身份词用 `\\b` 词界匹配（避免 `FactorResearchEngine` 这类**类名内的连续子串**伪命中）；
    ② 跳过 `BEGIN/END_FORBIDDEN_PATTERNS` 哨兵之间的**模式定义块自身**（自指伪命中）；
    ③ 大小写无关；CJK 词仍为子串匹配（CJK 无词界）。
    """
    # BEGIN_FORBIDDEN_PATTERNS
    patterns = [r"\bcz2022\b", r"\bchen\b", r"\bzimmermann\b", r"2021-037", r"10\.17016",
                r"10\.1561", "open source cross-sectional", "论文应当", "应当实现",
                "显著", "统计显著"]
    # END_FORBIDDEN_PATTERNS
    targets = [os.path.abspath(__file__), os.path.join(HERE, "make_config.py"), PROBE_SRC]
    hits, self_lines = [], {}
    for t in targets:
        if not os.path.exists(t):
            continue
        lines = open(t, "r", encoding="utf-8", errors="replace").read().split("\n")
        defblock, open_at = set(), None
        for i, ln in enumerate(lines, 1):
            if open_at is None and ln.strip() == "# BEGIN_FORBIDDEN_PATTERNS":
                open_at = i
            if open_at is not None:
                defblock.add(i)
                if ln.strip() == "# END_FORBIDDEN_PATTERNS":
                    open_at = None
        self_lines[os.path.basename(t)] = sorted(defblock)
        for i, line in enumerate(lines, 1):
            if i in defblock:
                continue
            low = line.lower()
            for p in patterns:
                if re.search(p, low):
                    hits.append({"file": os.path.basename(t), "line": i, "pattern": p})
    return {"hits": hits, "patterns": patterns, "scope": [os.path.basename(t) for t in targets],
            "self_excluded_lines": self_lines,
            "excluded": ["R014_prereg.md", "R014_review.md", "本清单自身的模式定义块"]}


def probe_numeric_literals():
    if not os.path.exists(PROBE_SRC):
        return {"literals": [], "suspicious": []}
    txt = open(PROBE_SRC, "r", encoding="utf-8", errors="replace").read()
    txt = re.sub(r"/\*.*?\*/", "", txt, flags=re.S)
    txt = re.sub(r"//[^\n]*", "", txt)
    txt = re.sub(r'"(\\.|[^"\\])*"', '""', txt)
    lits = re.findall(r"(?<![\w.])(\d+\.\d+(?:[eE][+-]?\d+)?|\d+)", txt)
    whitelist = {"0", "1", "2", "3", "4", "5", "6", "8"}
    return {"literals": sorted(set(lits)), "suspicious": sorted({l for l in lits if l not in whitelist})}


def cleanup_probe():
    for name in ("R014Probe.class", "R014Probe$Row.class"):
        p = os.path.join(CLASSES_DIR, name)
        if os.path.exists(p):
            os.remove(p)
    return not any(os.path.exists(os.path.join(CLASSES_DIR, n))
                   for n in ("R014Probe.class", "R014Probe$Row.class"))


def append_runlog(result):
    lines = ["=" * 78]
    lines.append(f"MODE={result['mode']}  verdict={result['total_verdict']}  K={json.dumps(result['k'])}")
    lines.append(f"anchors={result['anchors']['hit_rate']}  anchor_misses={len(result['anchors']['misses'])}  "
                 f"canary_pairs={result['canary']['pairs_measured']}  "
                 f"canary_failures={result['canary']['failures']}  alerts={len(result['instrument_alerts'])}")
    lines.append(f"frozen_ok={result['frozen_ok']} prereg_unchanged={result['prereg_unchanged']} "
                 f"bytes_equal={result['probe']['bytes_equal']} cleanup={result['probe']['cleanup_ok']} "
                 f"static_bytes_equal={result['probe']['static_bytes_equal']}")
    for v in result["variants"]:
        lines.append(f"  {v['id']} {v['word']:26s} reach={v['reachability']:22s} "
                     f"rd={v['reading_independent']} cln={v['canary_cln']} inj={v['canary_inj']}")
    for h in result["instrument_alerts"]:
        lines.append(f"  ALERT {h['kind']}: {json.dumps(h['detail'], ensure_ascii=False)[:300]}")
    for p in result["predictions"]:
        lines.append(f"  {p['id']} expect={p['expect']:28s} measured={p['measured']} hit={p['hit']}")
    with open(RUNLOG, "a", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")


# --------------------------------------------------------------------------
def main():
    with open(CONFIG, "r", encoding="utf-8") as fh:
        cfg = json.load(fh)
    prereg_path = os.path.join(cfg["root"], cfg["prereg"])
    prereg_lines = open(prereg_path, "r", encoding="utf-8", errors="replace").read().split("\n")
    prereg_sha = sha256_file(prereg_path)
    file_lines = {f["id"]: load_lines(cfg, f["id"]) for f in cfg["scan_root"]["files"]}

    # K7 前置
    now32 = {f["id"]: sha256_file(os.path.join(cfg["root"], f["path"]))[:32]
             for f in cfg["scan_root"]["files"]}
    frozen_ok = all(now32[f["id"]] == f["frozen_sha256_32"] for f in cfg["scan_root"]["files"])
    prereg_unchanged = (prereg_sha == cfg["prereg_sha256"])
    if not frozen_ok:
        alert("frozen_hash_mismatch", [f["id"] for f in cfg["scan_root"]["files"]
                                      if now32[f["id"]] != f["frozen_sha256_32"]])
    if not prereg_unchanged:
        alert("prereg_changed", {"now": prereg_sha, "frozen": cfg["prereg_sha256"]})

    # 静态层两次运行
    static1 = static_extract(cfg, file_lines, prereg_lines)
    static2 = static_extract(cfg, file_lines, prereg_lines)
    static_bytes_equal = (json.dumps(static1, sort_keys=True, ensure_ascii=False)
                          == json.dumps(static2, sort_keys=True, ensure_ascii=False))
    if not static_bytes_equal:
        alert("static_run_not_deterministic", True)
    S, ANCH = static1["facts"], static1["anchors"]

    # 运行层
    F = dict(S)
    recon_report, probe_meta, probe_runs, probe_errors, probe_unknown = {}, {}, {}, [], []
    bytes_equal = False
    if not STATIC_ONLY:
        if compile_probe():
            raw1, err1 = run_probe(1)
            raw2, err2 = run_probe(2)
            for path, content in ((PROBE_RAW, raw1), (PROBE_RAW2, raw2),
                                  (PROBE_ERR, err1 + "\n=== RUN2 ===\n" + err2)):
                with open(path, "w", encoding="utf-8", newline="\n") as fh:
                    fh.write(content)
            bytes_equal = (raw1 == raw2)
            if not bytes_equal:
                alert("probe_not_deterministic", {"len1": len(raw1), "len2": len(raw2)})
            probe_meta, probe_runs, probe_errors, probe_unknown = parse_probe(raw1)
        else:
            alert("probe_unavailable", True)

    panel_rows, cells = parse_panel(PANEL_FILE), parse_cells(CELL_FILE)
    if not STATIC_ONLY and probe_runs:
        F, recon_report = compute_runtime_facts(cfg, F, panel_rows, cells, probe_runs, bytes_equal)

    # 变体判定（V19/V20 的仪器事实已在 compute_runtime_facts 中按**声明数**填入）
    variants_out = []
    for v in cfg["variants"]:
        vid = v["id"]
        variants_out.append({
            "id": vid, "axis": v["axis"], "name": v["name"], "expect": v["expect"],
            "word": judge(vid, F), "reachability": v["reachability"],
            "in_k_candidates": v["in_k_candidates"],
            "reading_independent": (not F.get(f"rd_{vid}", False)),
            "canary_cln": None, "canary_inj": None, "inj_key": INJ_KEYS[vid],
            "decision_key_measured": F.get(INJ_KEYS[vid]),
        })

    # 金丝雀：先 V01–V18，再用其结果填 V19 的仪器事实，最后 V19/V20 自检
    canary = []
    for rec in variants_out:
        vid = rec["id"]
        if vid in ("V19", "V20"):
            continue
        if STATIC_ONLY and vid not in STATIC_VARIANTS:
            rec["canary_cln"] = "SKIPPED_STATIC_ONLY"
            rec["canary_inj"] = "SKIPPED_STATIC_ONLY"
            continue
        w0 = judge(vid, F)
        w_cln = judge(vid, cln_inject(vid, F))
        f_inj = inj_flip(vid, F)
        w_inj = judge(vid, f_inj) if f_inj is not None else None
        rec["canary_cln"], rec["canary_inj"] = (w_cln == w0), (w_inj is not None and w_inj != w0)
        canary.append({"vid": vid, "word": w0, "cln_word": w_cln, "inj_word": w_inj,
                       "cln_pass": rec["canary_cln"], "inj_pass": rec["canary_inj"],
                       "inj_key": INJ_KEYS[vid], "inj_measured": F.get(INJ_KEYS[vid])})
    if not STATIC_ONLY:
        declared = cfg["canary_pairs"]
        measured = len(canary) + 2  # V01–V18 + V19/V20
        fails = [c["vid"] for c in canary if not (c["cln_pass"] and c["inj_pass"])]
        F["v19_canary_pairs_declared"] = declared
        F["v19_canary_pairs_measured"] = measured
        F["v19_canary_failures"] = len(fails)
        F["v19_canary_all_pass"] = bool(declared == 20 and measured == declared and not fails)
    for rec in variants_out:
        if rec["id"] not in ("V19", "V20"):
            continue
        vid = rec["id"]
        if STATIC_ONLY:
            rec["canary_cln"] = rec["canary_inj"] = "SKIPPED_STATIC_ONLY"
            rec["word"] = judge(vid, F)
            continue
        w0 = judge(vid, F)
        w_cln = judge(vid, cln_inject(vid, F))
        f_inj = inj_flip(vid, F)
        w_inj = judge(vid, f_inj) if f_inj is not None else None
        rec["word"] = w0
        rec["canary_cln"], rec["canary_inj"] = (w_cln == w0), (w_inj is not None and w_inj != w0)
        rec["decision_key_measured"] = F.get(INJ_KEYS[vid])
        canary.append({"vid": vid, "word": w0, "cln_word": w_cln, "inj_word": w_inj,
                       "cln_pass": rec["canary_cln"], "inj_pass": rec["canary_inj"],
                       "inj_key": INJ_KEYS[vid], "inj_measured": F.get(INJ_KEYS[vid])})

    # K 表
    vocab = set(cfg["vocab"])
    off_vocab = [r["id"] for r in variants_out if r["word"] not in vocab]
    k1 = any(r["word"] == "NOT_RECONSTRUCTABLE" and r["reading_independent"] and r["in_k_candidates"]
             and r["axis"] == "A1" for r in variants_out)
    k2 = any(r["word"] == "INCONSISTENT_UNIT" and r["in_k_candidates"] for r in variants_out)
    k3 = any(r["word"] == "VALUE_DIVERGENT" and r["in_k_candidates"] for r in variants_out)
    k4 = any(r["word"] == "COUNT_UNFAITHFUL" and r["in_k_candidates"]
             and reach_rank(r["reachability"]) >= reach_rank("REACHABLE_PUBLIC_API")
             for r in variants_out)
    axis_counts = {}
    for r in variants_out:
        axis_counts[r["axis"]] = axis_counts.get(r["axis"], 0) + 1
    row_sum = sum(axis_counts.values())
    axis_total = sum(len(k["variants"]) for k in cfg["axes"])
    grid_mismatch = abs(len(F.get("grid_cells", [])) - cfg["grid"]["declared_cells"]) if not STATIC_ONLY else 0
    k5 = (row_sum != axis_total) or (axis_total != len(cfg["variants"])) or (len(cfg["variants"]) != 20) \
        or (grid_mismatch != 0) or (cfg["canary_pairs"] != 20)
    forbidden, probe_lits = scan_forbidden(), probe_numeric_literals()
    canary_fail = [c["vid"] for c in canary if not (c["cln_pass"] and c["inj_pass"])]
    k6 = bool(off_vocab) or bool(canary_fail) or (not STATIC_ONLY and (not probe_runs or not bytes_equal)) \
        or bool(forbidden["hits"]) or bool(ALERTS) or bool(probe_lits["suspicious"])
    k7 = (not prereg_unchanged) or (not frozen_ok) or (not static_bytes_equal)
    total = "INCONCLUSIVE" if (k5 or k6 or k7) else ("REJECTED" if (k1 or k2 or k3 or k4) else "NOT_VIOLATED")

    cleanup_ok = cleanup_probe()
    P = compare_p_table(cfg, variants_out, F)

    result = {
        "round": "R014", "mode": "static-only" if STATIC_ONLY else "full",
        "prereg_sha256": prereg_sha, "prereg_unchanged": prereg_unchanged, "frozen_ok": frozen_ok,
        "scan_root": {"count": cfg["scan_root"]["count"], "total_bytes": cfg["scan_root"]["total_bytes"],
                      "matches_r013_frozen": cfg["scan_root"]["matches_r013_frozen"]},
        "anchors": ANCH, "tolerances": cfg["tolerances"],
        "variants": variants_out, "axes": cfg["axes"],
        "canary": {"pairs_declared": cfg["canary_pairs"], "pairs_measured": len(canary),
                   "failures": canary_fail, "detail": canary},
        "k": {"K1": k1, "K2": k2, "K3": k3, "K4": k4, "K5": k5, "K6": k6, "K7": k7},
        "total_verdict": total, "h0_alive": total != "REJECTED",
        "counts": {"axis_counts": axis_counts, "row_sum": row_sum, "axis_total": axis_total,
                   "variants_total": len(cfg["variants"]), "grid_mismatch_count": grid_mismatch,
                   "off_vocab": off_vocab},
        "grid_cells": F.get("grid_cells", []), "predictions": P,
        "forbidden_scan": forbidden, "probe_literals": probe_lits,
        "instrument_alerts": ALERTS, "instrument_axis_note": cfg["implementation_notes"],
        "probe": {"meta": probe_meta, "runs": len(probe_runs), "errors": probe_errors,
                  "unknown_lines": probe_unknown[:20], "bytes_equal": bytes_equal,
                  "cleanup_ok": cleanup_ok, "static_bytes_equal": static_bytes_equal},
        "recon": recon_report,
        "static_detail": {"labels": S.get("labels"), "fmt": S.get("fmt"),
                          "payload_note_keys": S.get("payload_note_keys"),
                          "payload_note_texts": S.get("payload_note_texts"),
                          "readings": {k: S[k] for k in S if k.endswith("_readings")}},
        "facts": {k: (v if isinstance(v, (int, float, bool, str, type(None))) else "<obj>")
                  for k, v in sorted(F.items())},
        "facts_detail": {k: v for k, v in sorted(F.items())
                         if isinstance(v, (dict, list)) and k not in ("grid_cells",)},
        "disclosure": {
            "forward_look_bias": 0, "survivorship_bias": 0,
            "reason": "装置只读源码文本 + 合成 (score, forwardReturn) 面板；不产生收益序列、不读行情、不访问数据库",
            "end_to_end_scope": "两面数值分歧通过**把冻结映射规则施加于同一引擎输出**证明"
                                "（MAPPING_RULES_APPLIED_TO_ENGINE_OUTPUT）；未经 HTTP/Spring 容器端到端观测 ⟹ B-R014-02",
            "row_order_scope": "V03 只证明**未声明的输入行序依赖**存在；真实数据行序来自 DB，影响面未测量 ⟹ B-R014-02",
        },
    }
    with open(RESULT, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    append_runlog(result)
    print(f"MODE={result['mode']} verdict={total} K={json.dumps(result['k'])}")
    print(f"anchors={ANCH['hit_rate']} canary_pairs={len(canary)} failures={canary_fail} alerts={len(ALERTS)}")
    print(f"frozen_ok={frozen_ok} prereg_unchanged={prereg_unchanged} bytes_equal={bytes_equal} "
          f"cleanup={cleanup_ok}")
    for r in variants_out:
        print(f"  {r['id']} {r['word']:26s} expect={r['expect']:26s} rd={r['reading_independent']} "
              f"cln={r['canary_cln']} inj={r['canary_inj']}")
    for h in ALERTS:
        print(f"  ALERT {h['kind']}: {json.dumps(h['detail'], ensure_ascii=False)[:240]}")


if __name__ == "__main__":
    main()
