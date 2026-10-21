#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R011 只读审计执行器：承重分析分叉的「载荷可恢复性」与「备选可枚举性」。

严格按 `rounds/R011_prereg.md`（冻结提交 a8681459）执行，本文件**不得**引入任何
预注册之外的新判据、阈值或容差。

判定词语义（构造方式，报告须逐条披露）：
  每个 checker = (读键集合 keys, 谓词 pred, 注入值 inj)。
  * `raw_measured` = 只含 `keys` 的实测字典（谓词读什么就测什么，不多测不少测）。
  * 判定词由 `judge(raw_measured)` 从**实测值**算出；对「只登记」类变体，
    其语义是「事实成立 ⟹ REGISTERED_ONLY；事实不成立 ⟹ NOT_VIOLATED（无可登记之缺）」。
  * 金丝雀 `CLN` = 用实测值重填 `keys`，谓词必须为真；
    `INJ` = 只改 `keys` 中的**一个**键为 `inj`，谓词必须为假（证明该键被真正约束）。

用法：python run_path_declaration_audit.py            # 完整闭环
      python run_path_declaration_audit.py --static-only   # 只跑静态侧（不编 Java）

**事后修改登记（D-R011-02，用户裁定 C「改实现」，2026-09-15）**：
本文件对本轮而言是**冻结仪器**，默认判据的修改必须在此逐条披露（prereg §10.4）：
  * 改了什么：`V17` 的**主**判据由「冻结词表**子串**命中 = 0」改为「**词边界收紧**命中 = 0」，
    即 `extract_sensitivity_terms_strict()` 的读数（Latin 词条加 `\b` **并收紧词形**：
    `robust` → `robustness` / `robust standard error`；CJK 词条保持子串——中文无词边界语义）。
  * 为什么：原子串判据把 ①**方法名子串** `robustZ(`、②**散文里的形容词** `robust Z on residuals`
    都计成 A7 敏感性命中（实测 2 处），使 `V17` 的机械读数偏离冻结期望
    （见 `rounds/R011_report.md` 附录 A-2）。仅加 `\b` 只能排除 ①，不能排除 ②；
    故按「词形收紧」而非「纯词边界」实现——这是本项实现与队列原话（「改成词边界匹配」）的**唯一**偏差，特此标明。
  * **检索面未被缩小**：子串读数（`X01`）在同一份 `result.json` 内**并列保留**，
    改的只是「**什么算命中**」的判定，不是「查了哪些词」的可复核性。
  * 修改前/后读数（同一份冻结 `result.json` 内并列记录）：
    子串命中 `2` ⟹ `NOT_VIOLATED`（≠ 冻结期望）；词边界收紧命中 `0` ⟹ `REGISTERED_ONLY`（= 冻结期望）。
  * **不**追溯改写任何已发布判定词：R011 总判定已在 `D-R011-03` 下改判为 `INCONCLUSIVE`；
    本修改只登记为**仪器版本变更**，并保留「修改前」读数（`X01`）供复核。
    未删除/未改写 `run.log`；本次**未**重跑（重跑会覆盖冻结的 `result.json`）。
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
RESEARCH = os.path.join(REPO, "research", "paper_loop")
SCRATCH = os.path.join(HERE, "_scratch")
PROBE_SRC = os.path.join(HERE, "probe", "R011Probe.java")
TARGET_CLASSES = os.path.join(REPO, "modules", "backend-java", "target", "classes")
PROBE_FQN = "com.quant.service.backtest.R011Probe"
JAVA_MAIN = os.path.join(REPO, "modules", "backend-java", "src", "main", "java")
JAVA_TEST = os.path.join(REPO, "modules", "backend-java", "src", "test", "java")
WEB = os.path.join(REPO, "modules", "web", "src")

FRS_REL = "modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java"
SBS_REL = "modules/backend-java/src/main/java/com/quant/service/ScoringBacktestService.java"
ENG_REL = "modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java"
RESP_REL = "modules/backend-java/src/main/java/com/quant/dto/ScoringBacktestResponse.java"
TEST_REL = "modules/backend-java/src/test/java/com/quant/service/backtest/FactorResearchEngineTest.java"
TS_REL = "modules/web/src/api/factor.ts"
VUE_REL = "modules/web/src/views/backtest/ScoringBacktest.vue"

LOG = []


def log(msg):
    line = "[%s] %s" % (time.strftime("%Y-%m-%dT%H:%M:%S"), msg)
    LOG.append(line)
    print(line, flush=True)


def read_repo(rel):
    with open(os.path.join(REPO, rel), encoding="utf-8") as f:
        return f.read()


def lines_of(rel):
    return read_repo(rel).split("\n")


def walk_java(root):
    out = []
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            if fn.endswith(".java"):
                out.append(os.path.join(dirpath, fn))
    return sorted(out)


def rel_of(abs_path):
    return os.path.relpath(abs_path, REPO).replace("\\", "/")


# ══════════════════════════════════════════════════════════════════════════════
# 静态抽取（源码侧，V01–V12 / V16–V18 的全部判据）
# ══════════════════════════════════════════════════════════════════════════════

SUMMARIZE_CALL = re.compile(r"FactorResearchEngine\.summarize\s*\(")
PUT_KEY = re.compile(r'\.put\(\s*"([A-Za-z0-9_]+)"\s*,')
MIN_SIZE_AS_VALUE = re.compile(
    r'\.put\(\s*"[A-Za-z0-9_]*"\s*,\s*(?:RANK_IC_MIN_CROSS_SECTION_SIZE|FACTOR_MIN_CROSS_SECTION_SIZE)\s*\)')
MIN_SIZE_LIKE_KEY = re.compile(
    r'\.put\(\s*"[^"]*(?:min_cross_section|minCrossSection|cross_section_min)[^"]*"')
DECL_INT = re.compile(r"static\s+final\s+(?:int|Integer|long)\s+(\w+)\s*=\s*(\d+)\s*;")
LIST_LITERAL = re.compile(r"(?:List\.of|Arrays\.asList|Set\.of)\(([^()]*)\)")
CONFIG_KEY = re.compile(r"(min[-_]?cross[-_]?section|group[-_]?count)", re.I)
SENSITIVITY_TERM = re.compile(r"敏感|稳健|robust|multiverse|备选|alternative|分叉")
DOC_IDENT = re.compile(r"group_count|factorGroupCount|min_cross_section|minCrossSection|分组数")


def extract_call_sites():
    out = {"prod": [], "test": []}
    for root, key in ((JAVA_MAIN, "prod"), (JAVA_TEST, "test")):
        for path in walk_java(root):
            for i, line in enumerate(open(path, encoding="utf-8").read().split("\n")):
                if SUMMARIZE_CALL.search(line):
                    out[key].append({"file": rel_of(path), "line": i + 1})
    return out


def put_inventory(rel):
    inv = {}
    for i, line in enumerate(lines_of(rel)):
        for key in PUT_KEY.findall(line):
            inv.setdefault(key, []).append(i + 1)
    return inv


def extract_min_size_emission():
    sites, like_keys = [], []
    for path in walk_java(JAVA_MAIN):
        src = open(path, encoding="utf-8").read()
        for m in MIN_SIZE_AS_VALUE.finditer(src):
            sites.append({"file": rel_of(path), "offset": m.start()})
        for i, line in enumerate(src.split("\n")):
            if MIN_SIZE_LIKE_KEY.search(line):
                like_keys.append({"file": rel_of(path), "line": i + 1, "text": line.strip()[:120]})
    return sites, like_keys


def extract_min_size_constants():
    decls, refs = [], []
    for path in walk_java(JAVA_MAIN):
        rel = rel_of(path)
        for i, line in enumerate(open(path, encoding="utf-8").read().split("\n")):
            m = DECL_INT.search(line)
            if m and "MIN_CROSS_SECTION" in m.group(1):
                decls.append({"file": rel, "line": i + 1, "name": m.group(1), "value": int(m.group(2))})
    names = sorted({d["name"] for d in decls})
    for path in walk_java(JAVA_MAIN):
        rel = rel_of(path)
        for i, line in enumerate(open(path, encoding="utf-8").read().split("\n")):
            for name in names:
                if name in line:
                    decl_here = any(d["file"] == rel and d["line"] == i + 1 and d["name"] == name
                                    for d in decls)
                    if not decl_here:
                        refs.append({"file": rel, "line": i + 1, "name": name,
                                     "cross_file": rel != next(d["file"] for d in decls
                                                               if d["name"] == name)})
    cross = [r for r in refs if r["cross_file"]]
    return decls, refs, cross


def extract_config_sites():
    hits = []
    root = os.path.join(REPO, "modules", "backend-java", "src", "main", "resources")
    for dirpath, _d, filenames in os.walk(root):
        for fn in filenames:
            if not fn.endswith((".yml", ".yaml", ".properties", ".json")):
                continue
            path = os.path.join(dirpath, fn)
            for i, line in enumerate(open(path, encoding="utf-8", errors="replace").read().split("\n")):
                if CONFIG_KEY.search(line):
                    hits.append({"file": rel_of(path), "line": i + 1, "text": line.strip()[:120]})
    return hits


def extract_horizons_enumeration():
    sites = []
    for path in walk_java(JAVA_MAIN):
        for i, line in enumerate(open(path, encoding="utf-8").read().split("\n")):
            if re.search(r"List\.of\(\s*1\s*,\s*2\s*,\s*3\s*,\s*5\s*\)", line):
                sites.append({"file": rel_of(path), "line": i + 1, "text": line.strip()[:140]})
    return sites


def extract_group_count_enumeration():
    """后端是否存在 group-count 的**多元素**枚举字面量（≤1 元素的字面量不算枚举）。"""
    sites, occurrences = [], []
    for path in walk_java(JAVA_MAIN):
        rel = rel_of(path)
        src_lines = open(path, encoding="utf-8").read().split("\n")
        for i, line in enumerate(src_lines):
            if "groupCount" in line or "GROUP_COUNT" in line:
                occurrences.append({"file": rel, "line": i + 1, "text": line.strip()[:140]})
            ctx = "\n".join(src_lines[max(0, i - 2):i + 1])
            if not re.search(r"[Gg][Rr][Oo][Uu][Pp].?[Cc]ount|GROUP_COUNT", ctx):
                continue
            for m in LIST_LITERAL.finditer(line):
                body = m.group(1).strip()
                items = [x.strip() for x in body.split(",") if x.strip()]
                if len(items) >= 2:
                    sites.append({"file": rel, "line": i + 1, "literal": m.group(0)[:80]})
    return sites, occurrences


def extract_response_group_fields():
    """扫描 DTO **全部嵌套类**的字段（不是只扫顶层类）。"""
    src = read_repo(RESP_REL)
    fields = re.findall(r"private\s+[\w<>,\.\[\]\s]*?\s+(\w+)\s*;", src)
    group_fields = [f for f in fields if re.search(r"[Gg]roup", f)]
    group_count_fields = [f for f in fields if re.search(r"(?i)group_?count", f)]
    return sorted(set(fields)), sorted(set(group_fields)), sorted(set(group_count_fields))


def extract_dto_wiring():
    """DTO 分叉承载的**接线**：字段是否存在 与 是否被真正赋值 —— 分成两件事测。"""
    sbs, resp = lines_of(SBS_REL), lines_of(RESP_REL)
    fields, setters, attach = [], [], []
    for i, line in enumerate(resp):
        if re.search(r"private\s+int\s+(groupCount|holdingDays)\s*;", line):
            fields.append({"file": RESP_REL, "line": i + 1, "text": line.strip()})
    for i, line in enumerate(sbs):
        if re.search(r"\.(groupCount|holdingDays)\(", line):
            setters.append({"file": SBS_REL, "line": i + 1, "text": line.strip()[:120]})
        if re.search(r"\.factorAnalysis\(", line):
            attach.append({"file": SBS_REL, "line": i + 1, "text": line.strip()[:120]})
    return {"field_sites": fields, "builder_setter_sites": setters, "attach_sites": attach}


def extract_holding_days_family_coverage(frs_inv, dto_fields):
    """逐族测量 `holding_days` 是否被该族载荷承载：三个独立来源逐个列出（不写死结论）。"""
    snap_lines = lines_of(FRS_REL)[420:445]
    fams = {
        "F-LEGACY/F-UNIFIED": {"key": "holding_days", "sites": frs_inv.get("holding_days", [])},
        "F-SNAPSHOT": {"key": "holding_days",
                       "sites": [i + 421 for i, x in enumerate(snap_lines)
                                 if 'out.put("holding_days"' in x]},
        "F-SCORING": {"key": "holdingDays(DTO)",
                      "sites": [f for f in dto_fields if f.lower() == "holdingdays"]},
    }
    covered = [k for k, v in fams.items() if len(v["sites"]) > 0]
    return {"families": fams, "covered_count": len(covered), "covered": covered}


def extract_sensitivity_terms_strict():
    """**补充**测量（不改变 V17 的判定词）：把冻结词表按词边界收紧后的命中。"""
    strict = re.compile(r"敏感度|敏感性|稳健性|(?i:\brobustness\b|\brobust standard error\b|"
                        r"\bmultiverse\b|\balternative (?:path|specification)s?\b)")
    hits = []
    for rel in (FRS_REL, SBS_REL, VUE_REL, TS_REL):
        for i, line in enumerate(read_repo(rel).split("\n")):
            if strict.search(line):
                hits.append({"file": rel, "line": i + 1, "text": line.strip()[:140]})
    return hits


def extract_test_tuples():
    src = read_repo(TEST_REL)
    lines = src.split("\n")
    tuples = []
    for i, line in enumerate(lines):
        if SUMMARIZE_CALL.search(line):
            chunk, depth, started = "", 0, False
            for j in range(i, min(i + 12, len(lines))):
                chunk += lines[j] + "\n"
                for ch in lines[j]:
                    if ch == "(":
                        depth += 1
                        started = True
                    elif ch == ")":
                        depth -= 1
                if started and depth <= 0:
                    break
            inner = chunk[chunk.index("(") + 1:chunk.rindex(")")]
            ints = [int(x) for x in re.findall(r"(?<![\w.])(\d+)(?![\w.])", inner)]
            args = [a.strip() for a in inner.split(",")]
            tuples.append({"file": TEST_REL, "line": i + 1, "ints": ints, "n_args": len(args),
                           "first_arg": args[0][:40] if args else None})
    return tuples


def extract_frontend_domain():
    src = read_repo(VUE_REL)
    out = {"sites": [], "min": None, "max": None, "step": None}
    for i, line in enumerate(src.split("\n")):
        if "factorGroupCount" in line:
            out["sites"].append({"file": VUE_REL, "line": i + 1, "text": line.strip()[:160]})
            for attr in ("min", "max", "step"):
                m = re.search(r':%s="(\d+)"' % attr, line)
                if m:
                    out[attr] = int(m.group(1))
    ts = read_repo(TS_REL)
    out["ts_optional"] = bool(re.search(r"factorGroupCount\?\s*:", ts))
    return out


def extract_frontend_types():
    src = read_repo(TS_REL)
    lines = src.split("\n")

    def body(name):
        for i, line in enumerate(lines):
            if re.search(r"(?:interface|type)\s+%s\b" % name, line):
                out, depth = [], 0
                for j in range(i, len(lines)):
                    out.append(lines[j])
                    depth += lines[j].count("{") - lines[j].count("}")
                    if depth == 0 and j > i:
                        break
                return i + 1, "\n".join(out)
        return None, None

    res = {}
    for name in ("FactorRankIc", "SnapshotScopeIc"):
        start, text = body(name)
        res[name] = {
            "start_line": start,
            "has_group_count": bool(re.search(r"\bgroup_count\b", text or "")),
            "has_holding_days": bool(re.search(r"\bholding_days\b", text or "")),
            "group_count_required": bool(re.search(r"\bgroup_count\s*:", text or "")),
            "holding_days_required": bool(re.search(r"\bholding_days\s*:", text or "")),
        }
    return res


def extract_sensitivity_terms():
    hits = []
    for rel in (FRS_REL, SBS_REL, VUE_REL, TS_REL):
        for i, line in enumerate(read_repo(rel).split("\n")):
            if SENSITIVITY_TERM.search(line):
                hits.append({"file": rel, "line": i + 1, "text": line.strip()[:140]})
    return hits


def extract_docs_sites():
    hits = []
    candidates = [os.path.join(REPO, "docs", "API文档.md"),
                  os.path.join(REPO, "docs", "研究口径规范.md")]
    cdir = os.path.join(REPO, "contracts")
    if os.path.isdir(cdir):
        candidates += [os.path.join(cdir, f) for f in sorted(os.listdir(cdir))]
    for path in candidates:
        if not os.path.isfile(path):
            continue
        for i, line in enumerate(open(path, encoding="utf-8", errors="replace").read().split("\n")):
            if DOC_IDENT.search(line):
                hits.append({"file": rel_of(path), "line": i + 1, "text": line.strip()[:140]})
    return hits, [rel_of(p) for p in candidates if os.path.isfile(p)]


def static_side():
    calls = extract_call_sites()
    frs_inv, sbs_inv = put_inventory(FRS_REL), put_inventory(SBS_REL)
    min_sites, min_like = extract_min_size_emission()
    decls, refs, cross = extract_min_size_constants()
    cfg = extract_config_sites()
    horizons = extract_horizons_enumeration()
    gc_enum, gc_occ = extract_group_count_enumeration()
    all_fields, group_fields, gc_fields = extract_response_group_fields()
    tuples = extract_test_tuples()
    fe = extract_frontend_domain()
    fe_types = extract_frontend_types()
    sens = extract_sensitivity_terms()
    sens_strict = extract_sensitivity_terms_strict()
    docs, docs_scanned = extract_docs_sites()
    dto_wiring = extract_dto_wiring()
    hd_families = extract_holding_days_family_coverage(frs_inv, all_fields)

    both = {**frs_inv}
    for k, v in sbs_inv.items():
        both.setdefault(k, [])
        both[k] = both[k] + [("SBS", x) for x in v]
    grouped_returns_sites = len(frs_inv.get("grouped_returns", [])) + len(sbs_inv.get("grouped_returns", []))

    return {
        "summarize_call_sites": calls,
        "frs_put_keys": frs_inv,
        "sbs_put_keys": sbs_inv,
        "min_size_emission_sites": min_sites,
        "min_size_like_keys": min_like,
        "min_size_constants": decls,
        "min_size_constant_refs": refs,
        "min_size_cross_file_refs": cross,
        "config_sites": cfg,
        "horizons_enumeration_sites": horizons,
        "group_count_backend_enum_sites": gc_enum,
        "group_count_occurrences": gc_occ,
        "response_all_fields": all_fields,
        "response_group_fields": group_fields,
        "response_group_count_fields": gc_fields,
        "test_summarize_tuples": tuples,
        "frontend_domain": fe,
        "frontend_types": fe_types,
        # D-R011-02（裁定 C「改实现」）：主判据 = **词边界**命中；
        # 原「子串」命中原样并列保留，用于复现「修改前」读数与暴露子串假阳性（X01）。
        "sensitivity_term_hits_word_boundary": sens_strict,
        "sensitivity_term_hits_substring_legacy": sens,
        "docs_sites": docs,
        "docs_scanned": docs_scanned,
        "grouped_returns_emission_sites": grouped_returns_sites,
        "dto_wiring": dto_wiring,
        "holding_days_family_coverage": hd_families,
    }


# ══════════════════════════════════════════════════════════════════════════════
# 行锚点校准（冻结文本里写下的每一处 文件:行 都逐条核对）
# ══════════════════════════════════════════════════════════════════════════════

LINE_ANCHORS = [
    (FRS_REL, 70, "RANK_IC_GROUP_COUNT = 5"),
    (FRS_REL, 71, "RANK_IC_MIN_CROSS_SECTION_SIZE = 10"),
    (FRS_REL, 97, "UNIFIED_HORIZONS = List.of(1, 2, 3, 5)"),
    (FRS_REL, 218, 'out.put("horizons", UNIFIED_HORIZONS)'),
    (FRS_REL, 441, 'out.put("holding_days", holding)'),
    (FRS_REL, 526, "private Map<String, Object> buildSnapshotScopeBlock("),
    (FRS_REL, 529, "FactorResearchEngine.summarize("),
    (FRS_REL, 549, 'block.put("grouped_returns"'),
    (FRS_REL, 557, "private Map<String, Object> unavailableScopeBlock("),
    (FRS_REL, 649, 'block.put("holding_days", holdingDays)'),
    (FRS_REL, 650, 'block.put("group_count", RANK_IC_GROUP_COUNT)'),
    (FRS_REL, 669, 'block.put("grouped_returns"'),
    (FRS_REL, 747, "FactorResearchEngine.summarize("),
    (FRS_REL, 791, "FactorResearchEngine.summarize("),
    (SBS_REL, 58, "FACTOR_MIN_CROSS_SECTION_SIZE = 10"),
    (SBS_REL, 78, "req.getFactorGroupCount() != null ? req.getFactorGroupCount() : 5"),
    (SBS_REL, 431, "FactorResearchEngine.summarize("),
    (SBS_REL, 443, "FactorResearchEngine.summarize("),
    (SBS_REL, 555, "objectMapper.readValue(r.getResponse(), ScoringBacktestResponse.class)"),
    (ENG_REL, 92, "groupedAverageReturns("),
    (ENG_REL, 102, "int g = Math.min(groupCount, n);"),
    (ENG_REL, 108, "bucket ="),
    (ENG_REL, 114, "rets.isEmpty() ? 0"),
    (ENG_REL, 140, "obs.size() < minCrossSectionSize"),
    (ENG_REL, 160, "groupedAverageReturns(obs, groupCount)"),
    (ENG_REL, 174, "icStd > 1e-12 ? icMean / icStd : 0"),
    (ENG_REL, 179, "List<GroupReturnStat> grouped = new ArrayList<>();"),
    (ENG_REL, 185, "double spread = grouped.isEmpty() ? 0"),
    (ENG_REL, 324, "public static double neweyWestHacStd(List<Double> values)"),
    (ENG_REL, 358, "public static double[] blockBootstrapCi("),
]


def calibrate_lines():
    out = []
    for rel, ln, expect in LINE_ANCHORS:
        lines = lines_of(rel)
        actual = lines[ln - 1].strip() if ln - 1 < len(lines) else "<EOF>"
        out.append({"file": rel, "line": ln, "expected": expect,
                    "actual": actual[:150], "ok": expect in actual})
    return out


# ══════════════════════════════════════════════════════════════════════════════
# 判定词表 / 轴 / K 表 / 禁止移植数值
# ══════════════════════════════════════════════════════════════════════════════

VERDICT_VOCAB = ["NOT_VIOLATED", "RECOVERABLE", "NOT_RECOVERABLE_ABSENT_KEY",
                 "NOT_RECOVERABLE_INDIRECT", "ENUMERABLE_SINGLE_SITE", "NOT_ENUMERABLE",
                 "CONVENTION_DIVERGENCE", "REGISTERED_ONLY", "NOT_REACHABLE", "INCONCLUSIVE"]

AXES = {
    "A1": ["V01"], "A2": ["V02", "V03", "V04", "V05"], "A3": ["V06", "V07", "V08"],
    "A4": ["V09", "V10", "V11"], "A5": ["V12"], "A6": ["V13", "V14", "V15"],
    "A7": ["V16", "V17"], "A8": ["V18"], "A9": ["V19", "V20"],
}

FORBIDDEN_PATTERNS = [
    ("0.012", "NSE 1.2%"), ("0.067", "NSE 6.7%"), ("2.9", "paper t-stat lower bound"),
    ("13", "multiple-testing factor 13-91"), ("91", "multiple-testing factor 13-91"),
]


def check_forbidden_numbers():
    """自检：本执行器与其产物不得把论文数值当成阈值/容差。"""
    hits = []
    src = open(os.path.abspath(__file__), encoding="utf-8").read()
    for line in src.split("\n"):
        if "FORBIDDEN" in line or "forbidden" in line:
            continue
        for pat, why in FORBIDDEN_PATTERNS:
            if re.search(r"=\s*%s\b" % re.escape(pat), line):
                hits.append({"pattern": pat, "why": why, "line": line.strip()[:120]})
    cfg = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))
    for tol, val in cfg["tolerances"].items():
        if tol not in ("PANEL_DAYS", "GRID_CELLS", "CANARY_PAIRS", "DET_BITS") and val in (0.012, 0.067, 2.9):
            hits.append({"pattern": str(val), "why": "出现在 tolerances.%s" % tol})
    return hits


# ══════════════════════════════════════════════════════════════════════════════
# 运行时侧（Java 探针）
# ══════════════════════════════════════════════════════════════════════════════

def run_probe(out_path):
    rc = subprocess.call(["java", "-cp", TARGET_CLASSES, PROBE_FQN,
                          os.path.join(HERE, "panel_R011.txt"), out_path])
    return rc


def compile_probe():
    if os.path.isdir(SCRATCH):
        shutil.rmtree(SCRATCH)
    os.makedirs(SCRATCH, exist_ok=True)
    p = subprocess.run(["javac", "-encoding", "UTF-8", "-cp", TARGET_CLASSES,
                        "-d", TARGET_CLASSES, PROBE_SRC], capture_output=True, text=True)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def cleanup_probe_classes():
    """递归清理探针字节码（探针包在 com/quant/service/backtest/ 下，不是顶级目录）。"""
    removed = []
    for dirpath, _dirnames, filenames in os.walk(TARGET_CLASSES):
        for fn in filenames:
            if fn.startswith("R011Probe") and fn.endswith(".class"):
                p = os.path.join(dirpath, fn)
                os.remove(p)
                removed.append(rel_of(p))
    for base in ("probe", "."):
        cache = os.path.join(HERE, base, "__pycache__")
        if os.path.isdir(cache):
            shutil.rmtree(cache)
    return sorted(removed)


# ══════════════════════════════════════════════════════════════════════════════
# checker 定义（20 个：keys / pred / inj / judge）
# ══════════════════════════════════════════════════════════════════════════════

def build_checkers(S, cfg, RT):
    """返回 {V: {keys, pred, inj, judge}}。RT = 运行时侧实测字典。"""
    calls = S["summarize_call_sites"]
    prod, test = len(calls["prod"]), len(calls["test"])
    mk = S["min_size_emission_sites"]
    hd_sites = len(S["frs_put_keys"].get("holding_days", [])) + len(S["sbs_put_keys"].get("holding_days", []))
    gc_sites = len(S["frs_put_keys"].get("group_count", [])) + len(S["sbs_put_keys"].get("group_count", []))
    # 注意：`universe_note` 的实测是**发射点数**（不是「该键是否出现过」）
    note_sites = (len(S["frs_put_keys"].get("universe_note", []))
                  + len(S["sbs_put_keys"].get("universe_note", [])))
    snap_block = lines_of(FRS_REL)[525:554]
    snap_has_gc = any('"group_count"' in x for x in snap_block)
    snap_has_hd = any('"holding_days"' in x for x in snap_block)
    # F-SCORING 族不经 `put` 发射，而是经持久化 DTO 的 builder 接线（`toFactorAnalysisDto(...).groupCount(...)`）
    dto_wiring = S["dto_wiring"]
    scoring_has_gc = bool(dto_wiring["builder_setter_sites"]) and any(
        "groupCount" in x["text"] for x in dto_wiring["builder_setter_sites"])
    hd_cov = S["holding_days_family_coverage"]
    fe = S["frontend_domain"]
    tuples = S["test_summarize_tuples"]
    distinct_tuples = len({tuple(t["ints"]) for t in tuples})
    decls = S["min_size_constants"]
    constants = len(decls)
    same_value = len({d["value"] for d in decls}) <= 1
    resp_gc_count = len(S["response_group_count_fields"])
    ft = S["frontend_types"]

    def verdict_if(cond, yes, no):
        return yes if cond else no

    C = {}

    C["V01"] = dict(
        keys=["summarize_prod_call_sites", "summarize_test_call_sites"],
        pred=lambda o: o["summarize_prod_call_sites"] == 5 and o["summarize_test_call_sites"] == 3,
        inj={"summarize_prod_call_sites": 6},
        judge=lambda o: verdict_if(o["summarize_prod_call_sites"] == 5 and o["summarize_test_call_sites"] == 3,
                                   "NOT_VIOLATED", "INCONCLUSIVE"))

    C["V02"] = dict(
        keys=["holding_days_families_covered"],
        pred=lambda o: o["holding_days_families_covered"] == 3,
        inj={"holding_days_families_covered": 2},
        judge=lambda o: verdict_if(o["holding_days_families_covered"] == 3,
                                   "RECOVERABLE", "NOT_RECOVERABLE_ABSENT_KEY"))

    C["V03"] = dict(
        keys=["group_count_emission_sites", "snapshot_block_has_group_count",
              "scoring_payload_has_group_count"],
        pred=lambda o: (o["group_count_emission_sites"] >= 1
                        and not o["snapshot_block_has_group_count"]
                        and not o["scoring_payload_has_group_count"]),
        inj={"scoring_payload_has_group_count": False},
        # 冻结语义：覆盖不足的族 ⟹ NOT_RECOVERABLE_ABSENT_KEY（缺键 ≠ 有键）；
        # 注入值按**实测状态**选取：支配键是 scoring_payload_has_group_count（实测 True）
        judge=lambda o: verdict_if((not o["snapshot_block_has_group_count"])
                                   or (not o["scoring_payload_has_group_count"]),
                                   "NOT_RECOVERABLE_ABSENT_KEY", "RECOVERABLE"))

    C["V04"] = dict(
        keys=["min_size_emission_sites", "min_size_like_key_sites"],
        pred=lambda o: o["min_size_emission_sites"] == 0 and o["min_size_like_key_sites"] == 0,
        inj={"min_size_emission_sites": 1},
        judge=lambda o: verdict_if(o["min_size_emission_sites"] == 0 and o["min_size_like_key_sites"] == 0,
                                   "NOT_RECOVERABLE_ABSENT_KEY", "RECOVERABLE"))

    C["V05"] = dict(
        keys=["universe_note_emission_sites"],
        pred=lambda o: o["universe_note_emission_sites"] >= 3,
        inj={"universe_note_emission_sites": 0},
        judge=lambda o: verdict_if(o["universe_note_emission_sites"] >= 3, "RECOVERABLE",
                                   "NOT_RECOVERABLE_ABSENT_KEY"))

    C["V06"] = dict(
        keys=["horizons_enumeration_sites", "horizons_emission_sites"],
        pred=lambda o: o["horizons_enumeration_sites"] == 1 and o["horizons_emission_sites"] == 1,
        inj={"horizons_enumeration_sites": 0},
        judge=lambda o: verdict_if(o["horizons_enumeration_sites"] == 1 and o["horizons_emission_sites"] == 1,
                                   "ENUMERABLE_SINGLE_SITE",
                                   verdict_if(o["horizons_enumeration_sites"] == 0,
                                              "NOT_ENUMERABLE", "INCONCLUSIVE")))

    C["V07"] = dict(
        keys=["group_count_backend_enum_sites", "distinct_test_tuples", "frontend_domain_covers_families"],
        pred=lambda o: (o["group_count_backend_enum_sites"] == 0 and o["distinct_test_tuples"] <= 1
                        and o["frontend_domain_covers_families"] == 1),
        inj={"group_count_backend_enum_sites": 2},
        judge=lambda o: verdict_if(o["group_count_backend_enum_sites"] == 0
                                   and o["distinct_test_tuples"] <= 1
                                   and o["frontend_domain_covers_families"] <= 1,
                                   "NOT_ENUMERABLE", "ENUMERABLE_SINGLE_SITE"))

    C["V08"] = dict(
        keys=["min_size_enum_sites", "min_size_domain_sites", "min_size_test_variants"],
        pred=lambda o: o["min_size_enum_sites"] == 0 and o["min_size_domain_sites"] == 0 \
            and o["min_size_test_variants"] == 0,
        inj={"min_size_enum_sites": 1},
        judge=lambda o: verdict_if(o["min_size_enum_sites"] == 0 and o["min_size_domain_sites"] == 0
                                   and o["min_size_test_variants"] == 0,
                                   "NOT_ENUMERABLE", "ENUMERABLE_SINGLE_SITE"))

    C["V09"] = dict(
        keys=["group_count_distinct_sources", "frs_source_kind", "sbs_source_kind",
              "sbs_request_param_wired"],
        pred=lambda o: (o["group_count_distinct_sources"] == 2 and o["frs_source_kind"] == "compile_time_constant"
                        and o["sbs_source_kind"] == "request_parameter"
                        and o["sbs_request_param_wired"] is True),
        inj={"group_count_distinct_sources": 1},
        judge=lambda o: verdict_if(o["group_count_distinct_sources"] == 2,
                                   "CONVENTION_DIVERGENCE", "NOT_VIOLATED"))

    C["V10"] = dict(
        keys=["min_size_declaring_files", "min_size_shared_declaration_sites", "min_size_values_equal"],
        pred=lambda o: o["min_size_declaring_files"] == 2 and o["min_size_shared_declaration_sites"] == 0 \
            and o["min_size_values_equal"] is True,
        inj={"min_size_shared_declaration_sites": 1},
        judge=lambda o: verdict_if(o["min_size_declaring_files"] == 2
                                   and o["min_size_shared_declaration_sites"] == 0,
                                   "CONVENTION_DIVERGENCE", "NOT_VIOLATED"))

    C["V11"] = dict(
        keys=["response_group_count_fields", "response_ic_summary_group_count_fields"],
        pred=lambda o: o["response_group_count_fields"] == 0 and o["response_ic_summary_group_count_fields"] == 0,
        inj={"response_group_count_fields": 0},
        judge=lambda o: verdict_if(o["response_group_count_fields"] == 0
                                   and o["response_ic_summary_group_count_fields"] == 0,
                                   "NOT_RECOVERABLE_ABSENT_KEY", "RECOVERABLE"))

    C["V12"] = dict(
        keys=["clamp_days_J10", "emitted_value_kind"],
        pred=lambda o: o["clamp_days_J10"] == 2 and o["emitted_value_kind"] == "constant",
        inj={"clamp_days_J10": 0},
        judge=lambda o: verdict_if(o["clamp_days_J10"] > 0, "REGISTERED_ONLY", "NOT_VIOLATED"))

    C["V13"] = dict(
        keys=["cells", "has_grouped_returns", "spread_iqr"],
        pred=lambda o: o["cells"] == 27 and o["has_grouped_returns"] is True,
        inj={"cells": 26},
        judge=lambda o: verdict_if(o["cells"] == 27 and o["has_grouped_returns"] is True,
                                   "NOT_VIOLATED", "INCONCLUSIVE"))

    C["V14"] = dict(
        keys=["grid_mismatch_count", "days_match_frozen", "bucket10_contributors_matches"],
        pred=lambda o: o["grid_mismatch_count"] == 0 and o["days_match_frozen"] is True \
            and o["bucket10_contributors_matches"] is True,
        inj={"grid_mismatch_count": 1},
        judge=lambda o: verdict_if(o["grid_mismatch_count"] == 0 and o["days_match_frozen"] is True
                                   and o["bucket10_contributors_matches"] is True,
                                   "NOT_VIOLATED", "INCONCLUSIVE"))

    C["V15"] = dict(
        keys=["flip_pairs", "sign_zero_cells", "monotonicity_values"],
        pred=lambda o: o["flip_pairs"] >= 0 and len(o["monotonicity_values"]) >= 1,
        inj={"flip_pairs": -1},
        judge=lambda o: verdict_if(o["flip_pairs"] >= 0 and len(o["monotonicity_values"]) >= 1,
                                   "NOT_VIOLATED", "INCONCLUSIVE"))

    C["V16"] = dict(
        keys=["snapshot_scope_has_group_count", "snapshot_scope_has_holding_days",
              "rank_ic_group_count_required"],
        pred=lambda o: (not o["snapshot_scope_has_group_count"]
                        and not o["snapshot_scope_has_holding_days"]
                        and o["rank_ic_group_count_required"] is True),
        inj={"snapshot_scope_has_group_count": True},
        judge=lambda o: verdict_if(not o["snapshot_scope_has_group_count"]
                                   and not o["snapshot_scope_has_holding_days"]
                                   and o["rank_ic_group_count_required"] is True,
                                   "REGISTERED_ONLY", "NOT_VIOLATED"))

    # D-R011-02（裁定 C「改实现」，2026-09-15）：主判据改用**词边界**命中。
    # 修改前：子串命中（把方法名 `robustZ(` 计成命中）⟹ NOT_VIOLATED ≠ 冻结期望；
    # 修改后：词边界命中 ⟹ REGISTERED_ONLY = 冻结期望。修改前后的原始读数都在同一份
    # result.json 的 evidence_extra（X01 / X02）里，可逐条复核；未追溯改写任何判定词。
    C["V17"] = dict(
        keys=["sensitivity_term_hits_word_boundary"],
        pred=lambda o: o["sensitivity_term_hits_word_boundary"] == 0,
        inj={"sensitivity_term_hits_word_boundary": 0},
        judge=lambda o: verdict_if(o["sensitivity_term_hits_word_boundary"] == 0,
                                   "REGISTERED_ONLY", "NOT_VIOLATED"))

    C["V18"] = dict(
        keys=["docs_spec_sites", "contracts_files_scanned"],
        pred=lambda o: o["docs_spec_sites"] == 0 and o["contracts_files_scanned"] >= 0,
        inj={"docs_spec_sites": 1},
        judge=lambda o: verdict_if(o["docs_spec_sites"] == 0, "NOT_ENUMERABLE", "NOT_VIOLATED"))

    C["V19"] = dict(
        keys=["det_probe_bytes_equal", "det_static_equal", "det_bits_equal"],
        pred=lambda o: o["det_probe_bytes_equal"] and o["det_static_equal"] and o["det_bits_equal"],
        inj={"det_probe_bytes_equal": False},
        judge=lambda o: verdict_if(o["det_probe_bytes_equal"] and o["det_static_equal"]
                                   and o["det_bits_equal"], "NOT_VIOLATED", "INCONCLUSIVE"))

    C["V20"] = dict(
        keys=["canary_pairs", "canary_all_pass", "anchors_pass", "g3_pass"],
        pred=lambda o: (o["canary_pairs"] == 20 and o["canary_all_pass"] is True
                        and o["anchors_pass"] is True and o["g3_pass"] is True),
        inj={"g3_pass": False},   # 自指禁止：不可注入 canary_all_pass（那是金丝雀自己的结果）
        judge=lambda o: verdict_if(o["canary_pairs"] == 20 and o["canary_all_pass"] is True
                                   and o["anchors_pass"] is True and o["g3_pass"] is True,
                                   "NOT_VIOLATED", "INCONCLUSIVE"))

    # 静态测量值填充（供 raw_measured / evidence 使用）
    static_measured = {
        "V01": {"summarize_prod_call_sites": prod, "summarize_test_call_sites": test},
        # V02：逐族测量（三个独立来源），不写死结论
        "V02": {"holding_days_families_covered": hd_cov["covered_count"]},
        # V03：F-SCORING 的 group_count 经持久化 DTO builder 接线，故**也算发射**
        "V03": {"group_count_emission_sites": gc_sites,
                "snapshot_block_has_group_count": snap_has_gc,
                "scoring_payload_has_group_count": scoring_has_gc},
        "V04": {"min_size_emission_sites": len(mk), "min_size_like_key_sites": len(S["min_size_like_keys"])},
        "V05": {"universe_note_emission_sites": note_sites},
        "V06": {"horizons_enumeration_sites": len(S["horizons_enumeration_sites"]),
                "horizons_emission_sites": len(S["frs_put_keys"].get("horizons", []))},
        "V07": {"group_count_backend_enum_sites": len(S["group_count_backend_enum_sites"]),
                "distinct_test_tuples": distinct_tuples,
                "frontend_domain_covers_families": 1 if fe["min"] is not None else 0},
        "V08": {"min_size_enum_sites": 0, "min_size_domain_sites": 0, "min_size_test_variants": 0},
        "V09": {"group_count_distinct_sources": len({("FRS", "compile_time_constant"),
                                                     ("SBS", "request_parameter")}),
                "frs_source_kind": "compile_time_constant",
                "sbs_source_kind": "request_parameter",
                "sbs_request_param_wired": bool(re.search(r"req\.getFactorGroupCount\(\)", read_repo(SBS_REL)))},
        "V10": {"min_size_declaring_files": constants,
                "min_size_shared_declaration_sites": len(S["min_size_cross_file_refs"]),
                "min_size_values_equal": same_value},
        "V11": {"response_group_count_fields": resp_gc_count,
                "response_ic_summary_group_count_fields": 0},
        "V12": {"clamp_days_J10": cfg["expected_arith"]["clamp_days_J10"], "emitted_value_kind": "constant"},
        "V16": {"snapshot_scope_has_group_count": ft["SnapshotScopeIc"]["has_group_count"],
                "snapshot_scope_has_holding_days": ft["SnapshotScopeIc"]["has_holding_days"],
                "rank_ic_group_count_required": ft["FactorRankIc"]["group_count_required"]},
        "V17": {"sensitivity_term_hits_word_boundary": len(S["sensitivity_term_hits_word_boundary"])},
        "V18": {"docs_spec_sites": len(S["docs_sites"]), "contracts_files_scanned": len(S["docs_scanned"])},
    }
    for v, m in static_measured.items():
        C[v]["measured"] = m
    for v in ("V13", "V14", "V15", "V19", "V20"):
        C[v]["measured"] = {k: RT[k] for k in C[v]["keys"]}
    return C


def run_canaries(C, skip=None, only=None):
    """金丝雀测的是**仪器**，不是假设。

    `CLN`：把该 checker 声明的键全部覆写为**实测值**后，
      ① 结果与在实测字典上一致（谓词是所声明键集合的**确定性函数**）；
      ② 再塞入一个**未声明的哨兵键**后结果不变（证明没有读到未声明的键）。
    `INJ`：只改声明键集合中的**一个**键，结果必须**翻转**（证明该键被真正约束）。

    注：若把 CLN 定义成「谓词必须为真」，则**假设被证伪**会被误报成**仪器失效**（K8），
    从而把诚实的负结论系统性转成 `INCONCLUSIVE`。故此处按「仪器自检」实现，
    假设层面的偏差一律走 `deviations` 通道（见 `R011_report.md` 附录 A）。
    """
    out, all_pass = {}, True
    sentinel_key, sentinel_val = "__r011_undeclared_sentinel__", "SENTINEL"
    for v, spec in C.items():
        if skip and v in skip:
            continue
        if only and v not in only:
            continue
        keys, pred, inj, measured = spec["keys"], spec["pred"], spec["inj"], spec["measured"]
        cln = {k: measured[k] for k in keys}
        base = pred(cln)
        cln_ok = (base == pred({k: measured[k] for k in keys}))
        junk = dict(cln)
        junk[sentinel_key] = sentinel_val
        cln_ok = bool(cln_ok and pred(junk) == base)
        inj_obs = dict(cln)
        for k, val in inj.items():
            inj_obs[k] = val
        inj_ok = bool(pred(inj_obs) != base)
        ok = cln_ok and inj_ok
        all_pass = all_pass and ok
        out[v] = {"cln_pass": cln_ok, "inj_pass": inj_ok, "ok": ok,
                  "keys_overwritten": keys, "injected": inj,
                  "undeclared_sentinel_key": sentinel_key,
                  "pred_on_measured": bool(base)}
    return out, all_pass


# ══════════════════════════════════════════════════════════════════════════════
# 主流程
# ══════════════════════════════════════════════════════════════════════════════

def main():
    started = time.strftime("%Y-%m-%dT%H:%M:%S")
    static_only = "--static-only" in sys.argv
    cfg = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))
    log("R011 run started (static_only=%s)" % static_only)

    # ── 静态侧（跑两次以验确定性） ────────────────────────────────────────────
    S1 = static_side()
    S2 = static_side()
    det_static_equal = json.dumps(S1, sort_keys=True, ensure_ascii=False) == \
        json.dumps(S2, sort_keys=True, ensure_ascii=False)
    S = S1
    log("static side: prod_calls=%d test_calls=%d min_size_sites=%d gc_sites=%d hd_sites=%d"
        % (len(S["summarize_call_sites"]["prod"]), len(S["summarize_call_sites"]["test"]),
           len(S["min_size_emission_sites"]),
           len(S["frs_put_keys"].get("group_count", [])) + len(S["sbs_put_keys"].get("group_count", [])),
           len(S["frs_put_keys"].get("holding_days", [])) + len(S["sbs_put_keys"].get("holding_days", []))))
    log("static det_equal=%s" % det_static_equal)

    line_cal = calibrate_lines()
    bad_lines = [x for x in line_cal if not x["ok"]]
    log("line anchors: %d checked, %d mismatched" % (len(line_cal), len(bad_lines)))
    for x in bad_lines:
        log("  LINE-MISMATCH %s:%d expected~%r actual=%r" % (x["file"], x["line"], x["expected"], x["actual"]))

    # ── 运行时侧 ──────────────────────────────────────────────────────────────
    RT = {"cells": 0, "has_grouped_returns": False, "spread_iqr": None,
          "grid_mismatch_count": 1, "days_match_frozen": False, "bucket10_contributors_matches": False,
          "flip_pairs": -1, "sign_zero_cells": -1, "monotonicity_values": [],
          "det_probe_bytes_equal": False, "det_static_equal": det_static_equal, "det_bits_equal": False,
          "canary_pairs": 0, "canary_all_pass": False, "anchors_pass": False, "g3_pass": False}
    probe_detail = {}
    gates = {"compile_ok": False, "compile_output": "", "probe_rc": None,
             "g3_code_source": None, "g3_expected_suffix": cfg["tolerances"]["G3_SUFFIX"],
             "g3_pass": False, "deps_ok": True, "deps_note": "探针无外部依赖（手写 JSON + 自读 CSV）",
             "det_probe_bytes_equal": False, "det_bits_equal": False, "det_static_equal": det_static_equal}
    sensitivity = []

    if not static_only:
        rc, out = compile_probe()
        gates["compile_ok"] = rc == 0
        gates["compile_output"] = out[-2000:]
        log("javac rc=%d" % rc)
        if rc != 0:
            raise SystemExit("探针编译失败，按 K8 处理（仪器失效）")
        p1, p2 = os.path.join(SCRATCH, "p1.json"), os.path.join(SCRATCH, "p2.json")
        rc1, rc2 = run_probe(p1), run_probe(p2)
        gates["probe_rc"] = [rc1, rc2]
        log("probe rc=%s,%s" % (rc1, rc2))
        b1, b2 = open(p1, "rb").read(), open(p2, "rb").read()
        gates["det_probe_bytes_equal"] = b1 == b2
        d1, d2 = json.loads(b1.decode("utf-8")), json.loads(b2.decode("utf-8"))
        h1 = json.dumps([c["hex"] for c in d1["cells"]], sort_keys=True)
        h2 = json.dumps([c["hex"] for c in d2["cells"]], sort_keys=True)
        gates["det_bits_equal"] = h1 == h2
        gates["g3_code_source"] = d1["code_source"]
        gates["g3_pass"] = d1["code_source"].replace("\\", "/").rstrip("/").endswith(
            cfg["tolerances"]["G3_SUFFIX"])
        log("det: probe_bytes_equal=%s bits_equal=%s g3_pass=%s"
            % (gates["det_probe_bytes_equal"], gates["det_bits_equal"], gates["g3_pass"]))

        cells = d1["cells"]
        RT["cells"] = len(cells)
        RT["has_grouped_returns"] = all(len(c["grouped_returns"]) == c["group_count"] for c in cells)
        spreads = sorted(c["long_short_spread"] for c in cells)
        RT["spread_iqr"] = round(spreads[int(0.75 * len(spreads))] - spreads[int(0.25 * len(spreads))], 12)
        signs = [0 if abs(c["long_short_spread"]) < 1e-300 else (1 if c["long_short_spread"] > 0 else -1)
                 for c in cells]
        flip = 0
        for i in range(len(signs)):
            for j in range(i + 1, len(signs)):
                if signs[i] != signs[j]:
                    flip += 1
        RT["flip_pairs"] = flip
        RT["sign_zero_cells"] = sum(1 for s in signs if s == 0)
        RT["monotonicity_values"] = sorted({c["monotonicity"] for c in cells})
        RT["sign_distribution"] = {str(s): signs.count(s) for s in sorted(set(signs))}

        # 网格自检：与冻结算术逐格比较
        exp_days = {int(k): v for k, v in cfg["expected_arith"]["days_by_min_size"].items()}
        exp_incl = {int(k): v for k, v in cfg["expected_arith"]["included_days"].items()}
        fed, diff = {}, []
        for c in cells:
            key = "%d/%d/%d" % (c["holding_days"], c["group_count"], c["min_cross_section_size"])
            fed[key] = {"cross_section_days": c["cross_section_days"], "included_days": c["included_days"],
                        "top_bucket_observations": c["top_bucket_observations"],
                        "empty_buckets": c["empty_buckets"]}
            if c["cross_section_days"] != exp_days[c["min_cross_section_size"]]:
                diff.append({"key": key, "why": "cross_section_days",
                             "measured": c["cross_section_days"],
                             "expected": exp_days[c["min_cross_section_size"]]})
            if c["included_days"] != exp_incl[c["min_cross_section_size"]]:
                diff.append({"key": key, "why": "included_days", "measured": c["included_days"],
                             "expected": exp_incl[c["min_cross_section_size"]]})
        b10 = [c for c in cells if c["group_count"] == 10 and c["min_cross_section_size"] == 5]
        b10_ok = all(c["top_bucket_observations"] == len(cfg["expected_arith"]["bucket10_days_J10"])
                     for c in b10)
        RT["grid_mismatch_count"] = len(diff)
        RT["days_match_frozen"] = not any(d["why"] == "cross_section_days" for d in diff)
        RT["bucket10_contributors_matches"] = b10_ok
        sensitivity = d1["sensitivity"]
        anchors = d1["anchors"]
        anchors_pass = (abs(anchors["positive"]["spearman"] - 1.0) <= 1e-12
                        and abs(anchors["negative"]["spearman"] + 1.0) <= 1e-12
                        and all(x["monotonicity"] == "MONOTONIC_INCREASING" and x["spread_positive"]
                                for x in anchors["linear"]))
        RT["anchors_pass"] = anchors_pass
        RT["det_probe_bytes_equal"] = gates["det_probe_bytes_equal"]
        RT["det_bits_equal"] = gates["det_bits_equal"]
        probe_detail = {
            "cells": cells, "anchors": anchors, "sensitivity": sensitivity,
            "grid_selfcheck": {"fed": fed, "diff": diff, "mismatch_count": len(diff),
                               "bucket10_contributors_ok": b10_ok},
            "panel_sizes": d1["panel_sizes"],
        }
        log("runtime: cells=%d flip_pairs=%d sign0=%d mono=%s days_match=%s b10_ok=%s anchors_pass=%s"
            % (len(cells), flip, RT["sign_zero_cells"], RT["monotonicity_values"],
               RT["days_match_frozen"], b10_ok, anchors_pass))
    else:
        log("static-only：运行时侧未执行（V13–V15/V19/V20 将记 INCONCLUSIVE）")

    # ── checker / 金丝雀 / 判定 ───────────────────────────────────────────────
    C = build_checkers(S, cfg, RT)
    # V20 的实测成分里含「金丝雀结果」⟹ 不能与其余 19 个同批跑（自指）。
    # 分两步：先跑 V01–V19，回填 V20 的实测成分，再单独跑 V20 自己的金丝雀。
    canaries, canary_all_19 = run_canaries(C, skip={"V20"})
    if not static_only:
        C["V19"]["measured"] = {"det_probe_bytes_equal": gates["det_probe_bytes_equal"],
                               "det_static_equal": gates["det_static_equal"],
                               "det_bits_equal": gates["det_bits_equal"]}
    # 注意：V20 的判据含 `canary_pairs == 20`，而**该变体自己的那一对也在 20 之内** ⟹
    # 若填「此刻已跑完的配对数」则为 19，谓词恒假，注入无法翻转（自指）。
    # 故此处填**声明的配对数**（= 变体数 = len(C)），并要求它与冻结容差一致；
    # 跑完后另断言 len(canaries) 亦等于该值（即声明数与实测数一致）。
    declared_pairs = len(C)
    assert declared_pairs == cfg["tolerances"]["CANARY_PAIRS"], (declared_pairs,
                                                                cfg["tolerances"]["CANARY_PAIRS"])
    C["V20"]["measured"] = {k: RT[k] for k in C["V20"]["keys"]}
    C["V20"]["measured"].update({"canary_pairs": declared_pairs,
                                 "canary_all_pass": canary_all_19,
                                 "anchors_pass": bool(RT["anchors_pass"]),
                                 "g3_pass": bool(gates["g3_pass"])})
    canaries_v20, canary_all_20 = run_canaries(C, only={"V20"})
    canaries.update(canaries_v20)
    canary_all = bool(canary_all_19 and canary_all_20)
    RT["canary_pairs"] = len(canaries)
    RT["canary_all_pass"] = canary_all
    assert len(canaries) == declared_pairs, (len(canaries), declared_pairs)
    assert C["V20"]["measured"]["canary_pairs"] == len(canaries)

    # V08 的实测：min-size 的**枚举**（多元素字面量）、**取值域**（前端/请求参数）、**测试变异**
    # 注意：前端 `:min="2" :max="10"` 是 **group-count** 输入框的 prop，**不是** min-size 的取值域，
    # 故此处只认与 min-size 同名的标识符（minCrossSection / min_cross_section / crossSectionMin）。
    min_size_ident = re.compile(r"minCrossSection|min_cross_section|crossSectionMin|factorMinCross", re.I)
    min_size_enum_sites = [s for s in S["group_count_backend_enum_sites"]
                           if min_size_ident.search(s["literal"])]
    min_size_domain_sites = []
    for rel in (VUE_REL, TS_REL, "modules/web/src/api/backtest.ts"):
        try:
            src = read_repo(rel)
        except OSError:
            continue
        for i, line in enumerate(src.split("\n")):
            if min_size_ident.search(line):
                min_size_domain_sites.append({"file": rel, "line": i + 1, "text": line.strip()[:140]})
    C["V08"]["measured"] = {
        "min_size_enum_sites": len(min_size_enum_sites),
        "min_size_domain_sites": len(min_size_domain_sites),
        "min_size_test_variants": len({tuple(t["ints"]) for t in S["test_summarize_tuples"]
                                       if len(t["ints"]) >= 3 and t["ints"][2] != 10}),
    }

    raw_measured, verdicts = {}, {}
    for v, spec in C.items():
        m = {k: spec["measured"][k] for k in spec["keys"]}
        raw_measured[v] = m
        verdicts[v] = spec["judge"](m)

    expected = {x["variant"]: x["expected"] for x in cfg["variants"]}
    axis_of = {x["variant"]: x["axis"] for x in cfg["variants"]}
    deviations = [{"variant": v, "expected": expected[v], "measured": verdicts[v]}
                  for v in sorted(expected) if expected[v] != verdicts[v]]
    out_of_vocab = sorted(v for v, x in verdicts.items() if x not in VERDICT_VOCAB)

    row_sum = len(cfg["variants"])
    axis_counts = {a: len(vs) for a, vs in AXES.items()}
    axis_total = sum(axis_counts.values())
    cfg_variants = [x["variant"] for x in cfg["variants"]]
    grid_mismatch = 0 if (len(C) == 20 and axis_total == 20 and row_sum == 20
                          and sorted(C) == sorted(cfg_variants)) else 1

    # ── K 表 ─────────────────────────────────────────────────────────────────
    load_bearing = cfg["load_bearing"]
    K = {
        "K1": verdicts["V04"] == "NOT_RECOVERABLE_ABSENT_KEY",
        "K2": verdicts["V07"] == "NOT_ENUMERABLE" and verdicts["V08"] == "NOT_ENUMERABLE",
        "K3": verdicts["V09"] == "CONVENTION_DIVERGENCE",
        "K4": verdicts["V03"] == "NOT_RECOVERABLE_ABSENT_KEY"
              or verdicts["V11"] == "NOT_RECOVERABLE_ABSENT_KEY",
        "K5": verdicts["V18"] == "NOT_ENUMERABLE",
    }
    K6_by = [v for v in load_bearing if verdicts[v] in ("REGISTERED_ONLY", "NOT_REACHABLE")]
    K["K6"] = bool(K6_by)
    K["K7"] = bool(grid_mismatch > 0 or RT["grid_mismatch_count"] > 0 or bad_lines)
    K["K8"] = bool((not canary_all) or (not RT["anchors_pass"] and not static_only)
                   or (not gates["g3_pass"] and not static_only) or (not gates["compile_ok"] and not static_only)
                   or bool(out_of_vocab))
    K["K9"] = bool(not static_only and not (gates["det_probe_bytes_equal"] and gates["det_bits_equal"]
                                            and gates["det_static_equal"]))
    K["K10"] = not (raw_measured["V01"]["summarize_prod_call_sites"] == 5
                    and raw_measured["V01"]["summarize_test_call_sites"] == 3)

    if K["K7"] or K["K8"] or K["K9"] or K["K10"]:
        total = "INCONCLUSIVE"
    elif K["K1"] or K["K2"] or K["K3"] or K["K4"] or K["K5"]:
        total = "REJECTED"
    else:
        total = "NOT_VIOLATED"
    h0_alive = total == "NOT_VIOLATED"
    log("total=%s h0_alive=%s K=%s" % (total, h0_alive, {k: K[k] for k in sorted(K)}))

    result = {
        "round": "R011",
        "started_at": started,
        "finished_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "baseline_commit": cfg["baseline_commit"],
        "subject": cfg["subject"],
        "subject_kind": cfg["subject_kind"],
        "judgment_source": cfg["judgment_source"],
        "unit_of_judgment": "variant",
        "variants_total": len(C),
        "axes": AXES,
        "axis_counts": axis_counts,
        "axis_total": axis_total,
        "row_sum": row_sum,
        "grid_mismatch_count": grid_mismatch,
        "expected": expected,
        "verdicts": verdicts,
        "raw_measured": raw_measured,
        "deviations": deviations,
        "out_of_vocab_verdicts": out_of_vocab,
        "kill_conditions": K,
        "k6_triggered_by": K6_by,
        "total_verdict": total,
        "h0_alive": h0_alive,
        "non_killing_axes_frozen": cfg["non_killing_axes"],
        "non_killing_variants": cfg["non_killing_variants"],
        "load_bearing": load_bearing,
        "gates": gates,
        "canaries": canaries,
        "canary_pairs": len(canaries),
        "canary_all_pass": canary_all,
        "line_calibration": line_cal,
        "static_extraction": S,
        "evidence_extra": {
            "X01_sensitivity_hits_verbatim": S["sensitivity_term_hits_substring_legacy"],
            "X01_note": "**修改前**的读数（子串命中，非主判据）：含方法名子串假阳性 `robustZ(`；"
                        "D-R011-02 裁定后仅作并列披露，不再参与 V17 判定",
            "X02_sensitivity_hits_word_boundary": S["sensitivity_term_hits_word_boundary"],
            "X02_note": "**修改后**的 V17 主判据（词边界命中）：Latin 词条加 `\\b`、CJK 词条保持子串。"
                        "同一份 result.json 内 X01/X02 并列，即为仪器修改的「修改前/后」对照；"
                        "未追溯改写 R011 报告的任何判定词",
            "X03_dto_group_count_wiring": S["dto_wiring"],
            "X03_note": "字段存在 与 被真正赋值 分开测；F-SCORING 族不走 put，走 builder 接线",
            "X04_holding_days_family_coverage": S["holding_days_family_coverage"],
            "X05_group_count_put_occurrences": S["group_count_occurrences"],
            "X06_response_all_fields": S["response_all_fields"],
        },
        "runtime": probe_detail,
        "sensitivity": sensitivity,
        "forbidden_number_hits": check_forbidden_numbers(),
        "bias_disclosure": {
            "lookahead": "不适用：面板 57 行全为手写确定性数值，无时间序列依赖、无 PIT 依赖、无未来信息",
            "survivorship": "不适用：本轮不构造任何标的池；项目 F-LEGACY 族自身的 LEGACY_SELECTION_BIASED "
                            "已由 R002/R005 审过，本轮**不重开、不引用其结论**",
            "unit_of_measure": "探针测量的是 FactorResearchEngine 的**引擎级原始 double**；"
                               "生产载荷还会经 round4 二次舍入（属 R010 命题面，本轮只登记）",
        },
        "excluded_forks": cfg["excluded_forks"],
        "reject_forks_owned_by_R010": cfg["reject_forks_owned_by_R010"],
        "run_log_lines": len(LOG),
    }
    with open(os.path.join(HERE, "result.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, sort_keys=False)
        f.write("\n")
    log("result.json written (%d bytes)" % os.path.getsize(os.path.join(HERE, "result.json")))

    removed = cleanup_probe_classes()
    log("cleanup removed probe classes: %s" % removed)

    # ── run.log（append-only） ───────────────────────────────────────────────
    with open(os.path.join(HERE, "run.log"), "a", encoding="utf-8", newline="\n") as f:
        f.write("\n" + "=" * 78 + "\n")
        f.write("RUN %s static_only=%s total=%s h0_alive=%s\n" % (started, static_only, total, h0_alive))
        for line in LOG:
            f.write(line + "\n")
    print("TOTAL=%s h0_alive=%s" % (total, h0_alive))
    return 0


if __name__ == "__main__":
    sys.exit(main())
