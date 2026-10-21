#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R007 统计不确定性载荷族审计（hac_std / ci_lower / ci_upper）—— 只读编排器。

判据来源：Harvey–Liu–Zhu，NBER WP 20592（2014），见 rounds/R007_review.md。
判据、阈值、网格、DGP、种子、判定词表、杀死条件在 rounds/R007_prereg.md 中
**于本文件创建之前冻结**；本文件只是冻结文本的可执行实现。

硬约束（协议 §7 + 本轮 §9）：
  * 只读：**不访问数据库**（本轮无任何 DB 断言），仅读文件系统 + 在本进程内调用生产类。
  * 不改任何生产代码 / 测试 / 前端 / 契约 / ResearchConvention。
  * 发现一律只登记队列，不修。
  * 断言对象必须是 modules/backend-java/target/classes 下的**生产类**（门 G3）；
    探针内的控制实现（NW 其它滞后、块 bootstrap 复刻、覆盖率统计）**只作对照值**，
    且与生产实现同输入并列打印。
  * posthoc_* 前缀的补充测量（V05p / V09p）**不参与** total verdict 合成（prereg §4）。
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
PROBE_SRC = os.path.join(HERE, "probe", "R007Probe.java")
PROBE_OUT = os.path.join(HERE, "probe", "out")
CLASSES = os.path.join(REPO, "modules", "backend-java", "target", "classes")
PROBE_MAIN = "com.quant.service.backtest.R007Probe"

FRS = os.path.join(REPO, "modules", "backend-java", "src", "main", "java", "com", "quant",
                   "service", "FactorResearchService.java")
ENGINE = os.path.join(REPO, "modules", "backend-java", "src", "main", "java", "com", "quant",
                      "service", "backtest", "FactorResearchEngine.java")
VUE = os.path.join(REPO, "modules", "web", "src", "views", "factor", "FactorResearch.vue")
ENGINE_TEST = os.path.join(REPO, "modules", "backend-java", "src", "test", "java", "com", "quant",
                           "service", "backtest", "FactorResearchEngineTest.java")

# ---------------------------------------------------------------------------
# 冻结常数（R007_prereg.md §3 / §4 / §5 / §6 / §7 / §8）—— 运行期不得修改
# ---------------------------------------------------------------------------
TOL = 1e-12              # A3b 逐位一致容差
TOL_CI = 1e-9            # A1a 逐位一致容差
TOL_REL_A5A = 0.05       # A5a 相对容差（冻结 5%）
TH_A2C = 0.05            # A2c 钳位发生率阈值（冻结 5%）
TH_A3C = 0.90            # A3c 比值材料性阈值（冻结 0.90）
TH_A5C_COV = 0.85        # A5c 覆盖率阈值（冻结 0.85）
TH_A5C_CP_ALPHA = 0.01   # Clopper–Pearson 单侧 99% ⟹ alpha = 0.01
EXISTING_TEST_TOL = 0.01  # 既有测试 FactorResearchEngineTest:145-153 的绝对容差（读码）

Z95 = 1.96
REPS_COVER = 1000
REPS_CLAMP = 1000
REPS_MEDIAN = 200
BOOT_ITERS = 1000
BOOT_SEED = 42

VARIANT = {
    "A1a": "V01", "A1b": "V02",
    "A2a": "V03", "A2b": "V04", "A2c": "V05", "A2d": "V06",
    "A3a": "V07", "A3b": "V08", "A3c": "V09", "A3d": "V10",
    "A4a": "V11", "A4b": "V12",
    "A5a": "V13", "A5b": "V14", "A5c": "V15",
    "A6a": "V16", "A6b": "V17",
    "A7a": "V18", "A7b": "V19",
    "A8a": "V20", "A8b": "V21",
}
BY_AXIS = {
    "A1": ["A1a", "A1b"],
    "A2": ["A2a", "A2b", "A2c", "A2d"],
    "A3": ["A3a", "A3b", "A3c", "A3d"],
    "A4": ["A4a", "A4b"],
    "A5": ["A5a", "A5b", "A5c"],
    "A6": ["A6a", "A6b"],
    "A7": ["A7a", "A7b"],
    "A8": ["A8a", "A8b"],
}
FROZEN_AXIS_COUNTS = {"A1": 2, "A2": 4, "A3": 4, "A4": 2, "A5": 3, "A6": 2, "A7": 2, "A8": 2}
FROZEN_TOTAL = 21

VERDICT_VOCAB = ["NOT_VIOLATED", "VIOLATED", "CONVENTION_DIVERGENCE", "CONTRACT_AMBIGUOUS",
                 "CONTRACT_MISLABEL", "REGISTERED_ONLY", "NOT_REACHABLE", "INCONCLUSIVE"]

# 冻结的杀死条件表（prereg §6 的逐字副本）
FROZEN_K = [
    {"id": "K1", "condition": "A2a VIOLATED（T=1 时零宽区间 [0,0] 与真实点估计并存且同块内无标记字段）",
     "outcome": "REJECTED", "refs": ["A2a"]},
    {"id": "K2", "condition": "A2c VIOLATED（存在 n>=10 单元钳位发生率 >= 5%）",
     "outcome": "REJECTED", "refs": ["A2c"]},
    {"id": "K3", "condition": "A5c VIOLATED（存在单元覆盖率 < 0.85 且 CP 单侧 99% 下界 < 0.85）",
     "outcome": "REJECTED", "refs": ["A5c"]},
    {"id": "K4", "condition": "A1b VIOLATED（ci_* 被标成 HAC 95%CI 而值来自 blockBootstrapCi）",
     "outcome": "CONTRACT_MISLABEL", "refs": ["A1b"]},
    {"id": "K5", "condition": "A3c 达材料性 且 A3d 成立", "outcome": "CONTRACT_MISLABEL",
     "refs": ["A3c", "A3d"]},
    {"id": "K6", "condition": "A4b 或 A2d 或 A8a 成立", "outcome": "CONTRACT_AMBIGUOUS",
     "refs": ["A4b", "A2d", "A8a"]},
    {"id": "K7", "condition": "A2b / A5b / A6a / A6b / A8b 成立，或 A3c 未达材料性但方向一致",
     "outcome": "REGISTERED_ONLY", "refs": ["A2b", "A5b", "A6a", "A6b", "A8b", "A3c"]},
    {"id": "K8", "condition": "行和交叉校验失败（§4）", "outcome": "INCONCLUSIVE", "refs": []},
    {"id": "K9", "condition": "阳性对照未通过（§7：G3 门 / 金丝雀对 / 与既有测试一致性）",
     "outcome": "INCONCLUSIVE", "refs": []},
    {"id": "K10", "condition": "A7a VIOLATED（同一输入两次运行 ci_* 不同）", "outcome": "INCONCLUSIVE",
     "refs": ["A7a"]},
]

# 冻结的计数期望（prereg §8）——计数不匹配**只**记附录 A，不改变任何判定词
EXPECT_COUNTS = {
    "C1": {"prod_src": 9, "research": 1,
           "note": "生产 9 = FRS 5 + factor.ts 2 + FactorResearch.vue 2；research 1 = R006 探针"},
    "C2": {"prod_src": 18, "research": 2, "note": "FRS 10 + factor.ts 4 + vue 4；research 2 = R006 探针"},
    "C3": {"web": 2, "note": "FactorResearch.vue:139 与 :322"},
    "C4": {"prod_call": 2, "prod_decl": 2, "test": 6, "note": "调用点 190/191；定义 324/358；测试 6 行"},
    "C5": {"frs_status": 1, "note": "FRS:560"},
    "C6": {"def": 1, "refs": 2, "fe": 1, "note": "定义 1 + 引用 534/653 + 前端绑定 329"},
    "C7": {"vue_net_ic_header": 1, "note": "vue:506 表头"},
    "C8": {"prod": 1, "test": 3, "note": "生产 1（Engine:191）；测试 3"},
    "C9": {"sql": 0, "note": "hac_std/ci_* 不落库"},
}

SKIP_DIRS = {".git", "node_modules", "target", "dist", "__pycache__", ".idea", ".vscode", "out",
             ".mypy_cache", ".pytest_cache"}
BUILD_DIRS = {"target", "dist"}   # 「原始命令无 --exclude-dir」时的额外披露口径

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

    jc = subprocess.run(
        ["javac", "-encoding", "UTF-8", "-cp", CLASSES, "-d", PROBE_OUT, PROBE_SRC],
        capture_output=True, text=True, errors="replace")
    log("javac rc=%d" % jc.returncode)
    if jc.stdout.strip():
        log("javac stdout: " + jc.stdout.strip())
    if jc.stderr.strip():
        log("javac stderr: " + jc.stderr.strip())
    if jc.returncode != 0:
        return None

    jr = subprocess.run(
        ["java", "-Dfile.encoding=UTF-8", "-cp", PROBE_OUT + os.pathsep + CLASSES, PROBE_MAIN],
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
    if isinstance(v, (int, float)) and not isinstance(v, bool):
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
    return ctx["probe"].get((probe, key, i))


def pn(ctx, probe, key, i=None):
    return _num(pv(ctx, probe, key, i))


def ind(ctx, probe, key):
    return sorted([k[2] for k in ctx["probe"].keys()
                   if k[0] == probe and k[1] == key and k[2] is not None])


def close(a, b, tol=TOL):
    try:
        return abs(float(a) - float(b)) <= tol
    except (TypeError, ValueError):
        return False


def parse_matched_bs(s):
    """探针 matched_bs 的解析：'none' -> []；'3' -> [3]；'1,2' -> [1, 2]。"""
    if s is None:
        return []
    txt = str(s).strip()
    if txt == "" or txt == "none":
        return []
    out = []
    for part in txt.split(","):
        part = part.strip()
        if part.lstrip("-").isdigit():
            out.append(int(part))
    return out


# ===========================================================================
# 2) 文本语料（Python 走文件，不依赖外部 grep 是否在 PATH 上）
# ===========================================================================
def walk_lines(roots, exts, pattern=None, include_build=False, exclude_substr=()):
    """
    在 roots（目录或**单个文件**）中扫描匹配行。
    返回 "rel_path:lineno:line"。目录遍历跳过 SKIP_DIRS；include_build 时额外纳入 target/dist。
    """
    hits = []
    rx = re.compile(pattern) if pattern else None

    def scan(full):
        rel = os.path.relpath(full, REPO).replace("\\", "/")
        if any(s in rel for s in exclude_substr):
            return
        if exts and not full.endswith(exts):
            return
        try:
            with open(full, "r", encoding="utf-8", errors="replace") as fh:
                for i, line in enumerate(fh, 1):
                    if rx is None or rx.search(line):
                        hits.append("%s:%d:%s" % (rel, i, line.rstrip("\n")))
        except OSError:
            return

    for root in roots:
        if os.path.isfile(root):
            scan(root)
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames
                           if d not in SKIP_DIRS or (include_build and d in BUILD_DIRS)]
            for fn in filenames:
                scan(os.path.join(dirpath, fn))
    return hits


def read_lines(path):
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read().split("\n")


_DECL_JAVA = r"\b(?:private|public|protected|package-private)\b[^;=]*\b%s\s*\("
_DECL_JS = r"\b(?:const|let|var|function)\s+%s\s*[=(]"


def method_range(lines, name):
    """
    按大括号配对返回**声明**体 [start, end]（0-based 闭区间）。找不到返回 None。
    必须先排除调用点：`foo(...)` 被调用处没有访问修饰符，而声明行有
    （Java: private/public/protected/static…；前端: const/let/var/function）。
    """
    cand = None
    for i, ln in enumerate(lines):
        if re.search(_DECL_JAVA % re.escape(name), ln) or re.search(_DECL_JS % re.escape(name), ln):
            cand = i
            break
    if cand is None:
        return None
    for i in ([cand] if cand is not None else []):
        depth, started = 0, False
        for j in range(i, len(lines)):
            for ch in lines[j]:
                if ch == '{':
                    depth += 1
                    started = True
                elif ch == '}':
                    depth -= 1
            if started and depth == 0:
                return i, j
    return None


def if_block_range(lines, start):
    """从 start 行起（含 `if (...) {`）按大括号配对返回该 if 块范围。"""
    depth, started = 0, False
    for j in range(start, len(lines)):
        for ch in lines[j]:
            if ch == '{':
                depth += 1
                started = True
            elif ch == '}':
                depth -= 1
        if started and depth == 0:
            return start, j
    return start, len(lines) - 1


def put_keys(lines, a, b, receiver="block"):
    """
    [a,b] 行内**指定接收者**的 `<receiver>.put("key"` 键清单（保持出现顺序，去重）。
    只统计该接收者，排除同范围内内联局部 Map（row / net / funnel / daily…）的键。
    """
    out = []
    rx = re.compile(r'\b%s\.put\(\s*"([A-Za-z_0-9]+)"' % re.escape(receiver))
    for j in range(a, min(b + 1, len(lines))):
        for m in rx.finditer(lines[j]):
            if m.group(1) not in out:
                out.append(m.group(1))
    return out


def put_keys_any(lines, a, b):
    """[a,b] 行内**任意接收者**的 `.put("key"` 键集合（披露早返回分支还缺哪些键）。"""
    out = []
    rx = re.compile(r'\.[A-Za-z_0-9]+\.put\(\s*"([A-Za-z_0-9]+)"')
    for j in range(a, min(b + 1, len(lines))):
        for m in rx.finditer(lines[j]):
            if m.group(1) not in out:
                out.append(m.group(1))
    return out


def build_text_corpora():
    mods = os.path.join(REPO, "modules")
    web = os.path.join(REPO, "modules", "web", "src")
    bj_src = os.path.join(REPO, "modules", "backend-java", "src")
    bj_main = os.path.join(bj_src, "main")
    bj_test = os.path.join(bj_src, "test")
    exts_all = (".java", ".sql", ".vue", ".js", ".ts", ".py")
    c = {}
    c["c1_prod"] = walk_lines([mods], exts_all, r"hac_std")
    c["c1_prod_raw"] = walk_lines([mods], exts_all, r"hac_std", include_build=True)
    c["c1_research"] = walk_lines([os.path.join(REPO, "research")], exts_all, r"hac_std")
    c["c2_prod"] = walk_lines([mods], exts_all, r"ci_lower|ci_upper")
    c["c2_prod_raw"] = walk_lines([mods], exts_all, r"ci_lower|ci_upper", include_build=True)
    c["c2_research"] = walk_lines([os.path.join(REPO, "research")], exts_all, r"ci_lower|ci_upper")
    c["c3"] = walk_lines([web], (".vue",), r"HAC 95%CI")
    c["c4"] = walk_lines([bj_src], (".java",), r"neweyWestHacStd\(|blockBootstrapCi\(")
    c["c8"] = walk_lines([bj_main, bj_test], (".java",), r"1000, 42L")
    c["c5"] = walk_lines([FRS], (".java",), r'"status"')
    c["c6"] = walk_lines([REPO], (".java",), r"RANK_IC_EMBARGO_NOTE")
    c["c6_fe"] = walk_lines([web], (".vue", ".ts"), r"embargo_note")
    c["c7"] = walk_lines([VUE], (".vue",), r"net_ic")
    c["c9"] = walk_lines([REPO], (".sql",), r"hac|ci_lower|ci_upper")
    c["c_labels_ci"] = walk_lines([web], (".vue", ".ts"), r"ci_lower|ci_upper")
    c["c_nominal_prod"] = walk_lines([mods], exts_all,
                                     r"nominal|多重检验|多重比较|bonferroni|benjamini|"
                                     r"\bFDR\b|adjusted p|multiple testing",
                                     include_build=False)
    c["c_nominal_all"] = walk_lines([REPO], exts_all,
                                    r"nominal|多重检验|多重比较|bonferroni|benjamini|"
                                    r"\bFDR\b|adjusted p|multiple testing")
    c["c_bb_javadoc"] = walk_lines([ENGINE], (".java",), r"块大小")
    c["c_bb_calls"] = walk_lines([bj_main], (".java",), r"blockBootstrapCi\(")
    return c


def build_src():
    """源码片段（供结构性断言使用；不修改任何文件）。"""
    eng = read_lines(ENGINE)
    frs = read_lines(FRS)
    vue = read_lines(VUE)
    s = {}
    s["engine_lines"] = eng
    s["frs_lines"] = frs
    s["vue_lines"] = vue
    # buildSnapshotScopeBlock：键集（只统计 block.put）+ note 守卫
    r = method_range(frs, "buildSnapshotScopeBlock")
    s["snapshot_range"] = r
    s["snapshot_keys"] = put_keys(frs, r[0], r[1]) if r else []
    s["snapshot_note_guard"] = [r[0] + i + 1 for i, ln in enumerate(frs[r[0]:r[1] + 1])
                                if "totalObservations() == 0" in ln] if r else []
    # unavailableScopeBlock：status 位置
    ru = method_range(frs, "unavailableScopeBlock")
    s["unavailable_range"] = ru
    s["unavailable_keys"] = put_keys(frs, ru[0], ru[1]) if ru else []
    # buildRankIc：早返回分支键集 vs 正常路径键集
    rb = method_range(frs, "buildRankIc")
    s["rankic_range"] = rb
    if rb:
        ei = [i for i in range(rb[0], rb[1] + 1) if "latestPerKey.isEmpty()" in frs[i]]
        if ei:
            e0, e1 = if_block_range(frs, ei[0])
            s["early_range"] = (e0, e1)
            s["early_keys"] = put_keys(frs, e0, e1)
            s["normal_keys"] = put_keys(frs, e1 + 1, rb[1])
            s["early_keys_any_receiver"] = put_keys_any(frs, e0, e1)
        else:
            s["early_range"] = None
            s["early_keys"] = []
            s["normal_keys"] = put_keys(frs, rb[0], rb[1])
            s["early_keys_any_receiver"] = []
    else:
        s["early_range"] = None
        s["early_keys"] = []
        s["normal_keys"] = []
        s["early_keys_any_receiver"] = []
    # RANK_IC_EMBARGO_NOTE 的字面内容
    emb = []
    for i, ln in enumerate(frs):
        if "RANK_IC_EMBARGO_NOTE" in ln and "=" in ln and "private static final" in ln:
            for j in range(i, min(i + 6, len(frs))):
                emb.append(frs[j])
                if ";" in frs[j]:
                    break
    s["embargo_decl"] = emb
    # 既有测试的一致性门输入（:145-153）
    try:
        s["engine_test_lines"] = read_lines(ENGINE_TEST)
    except OSError:
        s["engine_test_lines"] = []
    # 前端 CSV 导出
    rv = method_range(vue, "exportUnifiedCsv")
    s["csv_range"] = rv
    s["csv_lines"] = vue[rv[0]:rv[1] + 1] if rv else []
    return s


# ===========================================================================
# 3) Clopper–Pearson（单侧 99% 下界 < 0.85 的等价判定）
# ===========================================================================
def binom_tail_ge(k, n, p):
    """P(X >= k | X ~ Bin(n, p))；用对数阶乘以避免下溢。"""
    if k <= 0:
        return 1.0
    if k > n:
        return 0.0
    if p <= 0.0:
        return 0.0
    if p >= 1.0:
        return 1.0
    lp, lq = math.log(p), math.log1p(-p)
    tot = 0.0
    for i in range(k, n + 1):
        tot += math.exp(math.lgamma(n + 1) - math.lgamma(i + 1) - math.lgamma(n - i + 1)
                        + i * lp + (n - i) * lq)
    return min(1.0, tot)


def cp_lower_below(k, n, th):
    """
    单侧 99% Clopper–Pearson 下界是否 < th。
    等价变形（报告 §5 写出算术）：L 由 tail(k, L) = alpha 定义，tail 对 p 单调递增，
    故 L < th ⟺ P(X >= k | p = th) > alpha。
    """
    return binom_tail_ge(k, n, th) > TH_A5C_CP_ALPHA


# ===========================================================================
# 4) 检查器注册表（21 个，与 VARIANT 一一对应）
# ===========================================================================
def ck_A1a(ctx):
    """生产 ci_* 与 [mean ± 1.96·hac_std] 是否逐位一致（冻结：不等 ⟹ NOT_VIOLATED）。"""
    is_ = ind(ctx, "V01", "abs_diff_lo")
    ev = {"panels": [], "tol": TOL_CI}
    any_uneq = False
    for i in is_:
        # 偏差在编排侧由 ci_* 与 normal_* 重算（探针预算的 abs_diff_* 仅作并列对照）
        dlo = abs(pn(ctx, "V01", "ci_lower", i) - pn(ctx, "V01", "normal_lo", i))
        dhi = abs(pn(ctx, "V01", "ci_upper", i) - pn(ctx, "V01", "normal_hi", i))
        ev["panels"].append({
            "panel": i, "T": pv(ctx, "V01", "T", i), "holding": pv(ctx, "V01", "holding", i),
            "mean": pn(ctx, "V01", "mean", i), "hac_std": pn(ctx, "V01", "hac_std", i),
            "ci": [pn(ctx, "V01", "ci_lower", i), pn(ctx, "V01", "ci_upper", i)],
            "normal_ci": [pn(ctx, "V01", "normal_lo", i), pn(ctx, "V01", "normal_hi", i)],
            "abs_diff": [dlo, dhi],
            "abs_diff_emitted": [pn(ctx, "V01", "abs_diff_lo", i), pn(ctx, "V01", "abs_diff_hi", i)],
            "recomputed_matches_emitted": close(dlo, pn(ctx, "V01", "abs_diff_lo", i), 1e-15)
                                          and close(dhi, pn(ctx, "V01", "abs_diff_hi", i), 1e-15),
            "ci_width": pn(ctx, "V01", "ci_width", i),
            "normal_width": pn(ctx, "V01", "normal_width", i),
            "ci_contains_mean": pv(ctx, "V01", "ci_contains_mean", i),
        })
        if (dlo > TOL_CI) or (dhi > TOL_CI):
            any_uneq = True
    ev["any_panel_differs_beyond_tol"] = any_uneq
    ev["reading"] = ("ci_* 与 [mean ± 1.96·SE] 不是同一个数；两者都合法，"
                     "差值本身不是缺陷（断言问的是「是否逐位一致」）")
    return ("NOT_VIOLATED" if any_uneq else "INCONCLUSIVE"), ev


_LABEL_RX = re.compile(r"(?:label\s*=\s*\"([^\"]{1,40})\"|([^<>{}\n]{1,40}?)\s*[:：]\s*\{\{)")
_CI_RX = re.compile(r"\b(ci_lower|ci_upper)\b")


def ck_A1b(ctx):
    """绑定 ci_* 的展示标签是否指称了另一个估计量。"""
    labels = []
    for ln in ctx["text"]["c_labels_ci"]:
        if not _CI_RX.search(ln):
            continue
        for m in _LABEL_RX.finditer(ln):
            txt = (m.group(1) or m.group(2) or "").strip()
            if txt:
                labels.append(txt)
    # 表格列头（label="HAC 95%CI"）在另一行、不含 ci_* 标识符，故并列扫描 C3 语料
    col_labels = []
    for ln in ctx["text"]["c3"]:
        for m in _LABEL_RX.finditer(ln):
            txt = (m.group(1) or m.group(2) or "").strip()
            if txt:
                col_labels.append(txt)
    labels = labels + col_labels
    ev = {"label_hits": labels, "labels_from_ci_lines": ctx["text"]["c_labels_ci"],
          "labels_from_c3_corpus": col_labels,
          "says_hac": [t for t in labels if "HAC" in t.upper()],
          "says_bootstrap": [t for t in labels if "BOOT" in t.upper()],
          "value_source": "FactorResearchEngine:191 blockBootstrapCi(icSeries, max(1,holdingDays),1000,42L)",
          "hac_std_also_in_block": "hac_std" in (ctx["src"]["snapshot_keys"] or []),
          "reading": "标签指称 HAC 标准误，而 ci_lower/ci_upper 的值来自 block bootstrap"}
    if not labels:
        return "INCONCLUSIVE", ev
    if ev["says_hac"] and not ev["says_bootstrap"]:
        return "CONTRACT_MISLABEL", ev
    return "NOT_VIOLATED", ev


def ck_A2a(ctx):
    """T=1：零宽区间与真实点估计并存，且同块内无标记字段。"""
    ev = {
        "input_dates": pv(ctx, "V03", "input_dates"),
        "input_obs_on_that_day": pv(ctx, "V03", "input_obs_on_that_day"),
        "cross_section_days": pv(ctx, "V03", "cross_section_days"),
        "total_observations": pv(ctx, "V03", "total_observations"),
        "rank_ic_mean": pn(ctx, "V03", "rank_ic_mean"),
        "hac_std": pn(ctx, "V03", "hac_std"),
        "ci": [pn(ctx, "V03", "ci_lower"), pn(ctx, "V03", "ci_upper")],
        "ci_is_zero_width": pv(ctx, "V03", "ci_is_zero_width"),
        "ci_contains_mean": pv(ctx, "V03", "ci_contains_mean"),
        "mean_is_nonzero": pv(ctx, "V03", "mean_is_nonzero"),
        "rank_ic_std": pn(ctx, "V03", "rank_ic_std"),
        "rank_ic_ir": pn(ctx, "V03", "rank_ic_ir"),
        "snapshot_block_keys": ctx["src"]["snapshot_keys"],
        "snapshot_has_status_key": "status" in (ctx["src"]["snapshot_keys"] or []),
        "snapshot_has_note_key": "note" in (ctx["src"]["snapshot_keys"] or []),
        "note_guard_lines": ctx["src"]["snapshot_note_guard"],
        "engine_record_field_names": pv(ctx, "V03", "engine_record_field_names"),
        "engine_record_has_universe_note": pv(ctx, "V03", "engine_record_has_universe_note"),
        "payload_transform": ("FactorResearchService:544-546（scope 块）与 :764-766（buildRankIc）对 "
                              "hac_std/ci_* 套 round4(v)=Math.round(v*10000.0)/10000.0；"
                              "0.0 经 round4 仍为 0.0 ⟹ 本退化在 HTTP 载荷层同样成立"),
    }
    t1 = (ev["cross_section_days"] == 1 and ev["total_observations"] >= 10
          and abs(ev["rank_ic_mean"]) > 1e-9 and ev["hac_std"] == 0.0
          and ev["ci"] == [0.0, 0.0] and ev["ci_contains_mean"] == 0)
    ev["t1_zero_width_with_real_point_estimate"] = t1
    no_marker = (not ev["snapshot_has_status_key"]) and ev["snapshot_has_note_key"] \
        and len(ev["note_guard_lines"]) >= 1
    ev["no_degeneracy_marker_in_block"] = no_marker
    ev["marker_reading"] = ("note 仅在 totalObservations()==0 时写入；status 只出现在 "
                            "unavailableScopeBlock；T=1 且当日 n=10 时两者都不触发")
    return ("VIOLATED" if (t1 and no_marker) else "NOT_VIOLATED"), ev


def ck_A2b(ctx):
    """三条退化路径输出 0 的可区分性。"""
    ev = {
        "empty": {"cross_section_days": pv(ctx, "V04", "empty_cross_section_days"),
                  "total_observations": pv(ctx, "V04", "empty_total_observations"),
                  "hac_std": pn(ctx, "V04", "empty_hac_std"),
                  "ci": [pn(ctx, "V04", "empty_ci_lower"), pn(ctx, "V04", "empty_ci_upper")],
                  "rank_ic_mean": pn(ctx, "V04", "empty_rank_ic_mean"),
                  "monotonicity": pv(ctx, "V04", "empty_monotonicity"),
                  "universe_note": pv(ctx, "V04", "empty_universe_note")},
        "unavailable_scope_block_status_lines": ctx["text"]["c5"],
        "unavailable_block_keys": ctx["src"]["unavailable_keys"],
        "status_in_unavailable_block": "status" in (ctx["src"]["unavailable_keys"] or []),
        "snapshot_block_keys": ctx["src"]["snapshot_keys"],
        "partial_2dates": {"input_dates": pv(ctx, "V04", "partial_input_dates"),
                           "cross_section_days": pv(ctx, "V04", "partial_cross_section_days"),
                           "hac_std": pn(ctx, "V04", "partial_hac_std"),
                           "ci": [pn(ctx, "V04", "partial_ci_lower"),
                                  pn(ctx, "V04", "partial_ci_upper")]},
        "reading": ("三处都输出 0.0/0.0；弱可区分信号 = cross_section_days==0（全空）与 "
                    "status=='UNAVAILABLE'（口径不可用块）；"
                    "不得叙述为「完全不可区分」"),
    }
    signals = (ev["empty"]["cross_section_days"] == 0
               and ev["empty"]["hac_std"] == 0.0
               and ev["empty"]["ci"] == [0.0, 0.0]
               and ev["status_in_unavailable_block"])
    ev["weak_distinguishability_present"] = signals
    return ("REGISTERED_ONLY" if signals else "VIOLATED"), ev


def ck_A2c(ctx):
    """钳位发生率（hacVar<0 ⟹ SE=0）在冻结网格上的比例。"""
    is_ = ind(ctx, "V05", "clamp_rate")
    cells = []
    for i in is_:
        cells.append({"n": pv(ctx, "V05", "n", i), "dgp": pv(ctx, "V05", "dgp", i),
                      "reps": pv(ctx, "V05", "reps", i),
                      "var_positive_reps": pv(ctx, "V05", "var_positive_reps", i),
                      "clamp_zero_count": pv(ctx, "V05", "clamp_zero_count", i),
                      "clamp_rate": pn(ctx, "V05", "clamp_rate", i)})
    hits = [c for c in cells if c["n"] >= 10 and c["clamp_rate"] >= TH_A2C]
    post = [{"n": pv(ctx, "V05p", "n", i), "dgp": pv(ctx, "V05p", "dgp", i),
             "clamp_rate": pn(ctx, "V05p", "clamp_rate", i),
             "clamp_zero_count": pv(ctx, "V05p", "clamp_zero_count", i),
             "reps": pv(ctx, "V05p", "reps", i)}
            for i in ind(ctx, "V05p", "clamp_rate")]
    ev = {"threshold": TH_A2C, "cells": cells, "n_ge_10_cells_over_threshold": hits,
          "total_draws": sum(c["var_positive_reps"] for c in cells),
          "clamp_events_total": sum(c["clamp_zero_count"] for c in cells),
          "min_rate": min([c["clamp_rate"] for c in cells] or [None]),
          "max_rate": max([c["clamp_rate"] for c in cells] or [None]),
          "posthoc_negative_ar_cells": post,
          "posthoc_note": "V05p 是网格外补充（负自相关 DGP），prereg §4：不参与判定合成",
          "reading": ("钳位是否在网格上真实发生；0 发生率 ⟹ 该防御分支在本网格（含负自相关）上"
                      "从未被触发，退化零来自 n<2 守卫而非钳位")}
    return ("VIOLATED" if hits else "REGISTERED_ONLY"), ev


def ck_A2d(ctx):
    """同一端点的两种键集：早返回分支缺 hac_std / ci_*。"""
    e = set(ctx["src"]["early_keys"] or [])
    n = set(ctx["src"]["normal_keys"] or [])
    want = {"hac_std", "ci_lower", "ci_upper"}
    ev = {"early_range_lines": ctx["src"]["early_range"],
          "early_keys": sorted(e), "normal_keys": sorted(n),
          "early_keys_any_receiver": sorted(ctx["src"]["early_keys_any_receiver"] or []),
          "missing_in_early": sorted(want - e), "present_in_normal": sorted(want & n),
          "missing_in_early_all_keys": sorted(n - e),
          "reading": ("同一 block 在 latestPerKey 为空的早返回分支下不含统计不确定性三键"
                      "（也不含 pearson_ic_mean / monotonicity），正常路径含 ⟹ 键集随数据可用性变化")}
    ok = len(want - e) == 3 and len(want & n) == 3
    return ("CONTRACT_AMBIGUOUS" if ok else "NOT_VIOLATED"), ev


def ck_A3a(ctx):
    """签名事实：生产 hac_std 无持有期入参（反射，非读码）。"""
    ev = {"nw_param_count": pv(ctx, "V07", "nw_param_count"),
          "nw_param_types": pv(ctx, "V07", "nw_param_types"),
          "nw_has_integral_param": pv(ctx, "V07", "nw_has_integral_param"),
          "bb_param_count": pv(ctx, "V07", "bb_param_count"),
          "bb_param_types": pv(ctx, "V07", "bb_param_types"),
          "bb_has_integral_param": pv(ctx, "V07", "bb_has_integral_param"),
          "bb_found": pv(ctx, "V07", "bb_found"), "nw_found": pv(ctx, "V07", "nw_found"),
          "summarize_param_count": pv(ctx, "V07", "summarize_param_count"),
          "panels_prod_vs_lags": {"prod": pn(ctx, "V07", "panel_prod"),
                                  "lag1": pn(ctx, "V07", "panel_ctrl_lag1"),
                                  "lag2": pn(ctx, "V07", "panel_ctrl_lag2"),
                                  "lag3": pn(ctx, "V07", "panel_ctrl_lag3"),
                                  "cbrt": pn(ctx, "V07", "panel_ctrl_cbrt")},
          "reading": ("neweyWestHacStd 入参只有序列（无整型参数）⟹ 结构上不可能感知重叠阶数 h；"
                      "同引擎的 blockBootstrapCi 有整型块长入参")}
    ok = (pv(ctx, "V07", "nw_param_count") == 1
          and pv(ctx, "V07", "nw_has_integral_param") == 0
          and pv(ctx, "V07", "bb_has_integral_param") == 1)
    ev["signature_fact_established"] = ok
    return ("REGISTERED_ONLY" if ok else "INCONCLUSIVE"), ev


def ck_A3b(ctx):
    """生产 hac_std 是否逐位等于 cbrt 滞后规则的控制实现。"""
    is_ = ind(ctx, "V08", "abs_diff_cbrt")
    bad = []
    for i in is_:
        if pn(ctx, "V08", "abs_diff_cbrt", i) > TOL:
            bad.append({"cell": i, "n": pv(ctx, "V08", "n", i), "h": pv(ctx, "V08", "h", i),
                        "dgp": pv(ctx, "V08", "dgp", i),
                        "prod": pn(ctx, "V08", "prod", i),
                        "ctrl": pn(ctx, "V08", "ctrl_cbrt", i),
                        "diff": pn(ctx, "V08", "abs_diff_cbrt", i)})
    ev = {"cells": len(is_), "tol": TOL, "mismatching_cells": bad,
          "max_abs_diff": max([pn(ctx, "V08", "abs_diff_cbrt", i) for i in is_] or [0.0]),
          "cells_with_longer_h_window": sum(1 for i in is_
                                            if pn(ctx, "V08", "dom_hm1", i)
                                            > pn(ctx, "V08", "dom_cbrt", i)),
          "sentinel_prod_vs_lag3": [pn(ctx, "V07", "panel_prod"), pn(ctx, "V07", "panel_ctrl_lag3")],
          "reading": ("生产实现 == 滞后 floor(n^(1/3)) 的 Bartlett 控制实现（与 h 无关）⟹ "
                      "滞后阶只由 n 决定；同输入并列打印的对照值保证这不是断言对象")}
    return ("NOT_VIOLATED" if (is_ and not bad) else "VIOLATED"), ev


def ck_A3c(ctx):
    """窗口解耦的材料性：比值 SE(cbrt)/SE(h−1)（冻结统计量 = 各单元中位数之比）。"""
    is_ = ind(ctx, "V09", "ratio_cbrt_over_hm1")
    cells = []
    for i in is_:
        h = pv(ctx, "V08", "h", i)
        cells.append({"n": pv(ctx, "V08", "n", i), "h": h, "dgp": pv(ctx, "V08", "dgp", i),
                      "dom_cbrt": pv(ctx, "V08", "dom_cbrt", i),
                      "dom_hm1": pv(ctx, "V08", "dom_hm1", i),
                      "prod_median": pn(ctx, "V09", "prod", i),
                      "ctrl_hm1_median": pn(ctx, "V09", "ctrl_hm1", i),
                      "ratio": pn(ctx, "V09", "ratio_cbrt_over_hm1", i),
                      "hm1_window_longer": pv(ctx, "V09", "hm1_gt_cbrt", i)})
    hv = [c for c in cells if c["h"] >= 5]
    hits = [c for c in hv if c["ratio"] < TH_A3C]
    paired = [{"n": pv(ctx, "V09p", "n", i), "h": pv(ctx, "V09p", "h", i),
               "dgp": pv(ctx, "V09p", "dgp", i),
               "ratio_paired_median": pn(ctx, "V09p", "ratio_paired_median", i),
               "frac_ratio_lt_1": pn(ctx, "V09p", "frac_ratio_lt_1", i)}
              for i in ind(ctx, "V09p", "ratio_paired_median")]
    ev = {"statistic": "median(prod)/median(ctrl_hm1) 逐单元；中位数在 200 次重复上取",
          "threshold": TH_A3C, "cells": cells, "h_ge_5_cells": len(hv),
          "material_cells": hits,
          "min_ratio_h_ge_5": min([c["ratio"] for c in hv] or [None]),
          "cells_ratio_lt_1": sum(1 for c in hv if c["ratio"] < 1.0),
          "posthoc_paired_median_ratios": paired,
          "posthoc_note": ("V09p 把统计量换成「逐次配对比值的中位数」（同一种子重分析），"
                           "prereg §4：不参与判定合成"),
          "reading": ("比值 < 1 ⟹ 生产窗口比 (h−1) 窗口给出更小的标准误（低估不确定性）；"
                      "方向在各单元间并不一致（见 cells_ratio_lt_1）")}
    return ("CONVENTION_DIVERGENCE" if hits else "NOT_VIOLATED"), ev


def ck_A3d(ctx):
    """对外承诺（RANK_IC_EMBARGO_NOTE）与 A3c 材料性是否一致。"""
    # 读 text 语料（与 src 同源；金丝雀可替换），src 侧的声明行只作出处证据
    decl = " ".join(ctx["text"]["c_embargo"]).strip()
    says_hac = "HAC" in decl.upper()
    a3c_verdict, a3c_ev = ck_A3c(ctx)
    a3a_verdict, a3a_ev = ck_A3a(ctx)
    decoupled = a3a_ev.get("signature_fact_established") is True
    material = a3c_verdict == "CONVENTION_DIVERGENCE"
    ev = {"embargo_note_decl_lines": ctx["src"]["embargo_decl"],
          "embargo_text_from_corpus": ctx["text"]["c_embargo"],
          "corpus_equals_source": list(ctx["text"]["c_embargo"]) == list(ctx["src"]["embargo_decl"]),
          "embargo_note_text": decl,
          "note_says_hac_std": says_hac,
          "a3a_verdict": a3a_verdict, "a3c_verdict": a3c_verdict,
          "a3c_material_cells_top": a3c_ev.get("material_cells", [])[:3],
          "window_decoupled_from_h": decoupled,
          "reading": ("承诺「重叠持有期 IC 序列的显著性以 HAC 标准误为准」，而 HAC 窗口与 h 解耦、"
                      "且存在材料性单元")}
    if says_hac and decoupled and material:
        return "CONTRACT_MISLABEL", ev
    return "NOT_VIOLATED", ev


def ck_A4a(ctx):
    """实测块长（由生产 CI 与对照复刻逐一比对识别）是否等于规则值。"""
    is_ = ind(ctx, "V11", "matched_bs")
    panels = []
    for i in is_:
        panels.append({"h": pv(ctx, "V11", "h", i), "n": pv(ctx, "V11", "n", i),
                       "matched_bs": pv(ctx, "V11", "matched_bs", i),
                       "rule_expected_bs": pv(ctx, "V11", "rule_expected_bs", i),
                       "prod_ci": [pn(ctx, "V11", "prod_ci_lo", i),
                                   pn(ctx, "V11", "prod_ci_hi", i)],
                       "prod_ci_width": pn(ctx, "V11", "prod_ci_width", i)})
    for p in panels:
        p["matched_bs_list"] = parse_matched_bs(p["matched_bs"])
        p["rule_expected_in_matched"] = p["rule_expected_bs"] in p["matched_bs_list"]
    ok = all(p["rule_expected_in_matched"] for p in panels) and bool(panels)
    shorter = [{"h": p["h"], "n": p["n"], "matched_bs": p["matched_bs"],
                "effective_bs": max(p["matched_bs_list"])}
               for p in panels if p["matched_bs_list"] and max(p["matched_bs_list"]) < p["h"]]
    multi = [{"h": p["h"], "n": p["n"], "matched_bs": p["matched_bs"]}
             for p in panels if len(p["matched_bs_list"]) > 1]
    ev = {"panels": panels,
          "identification": "生产 CI 与按 bs=1..n-1 复刻的对照 CI 逐位（1e-15）相等者即为实际块长",
          "matched_bs_format": "逗号串联清单（探针原样输出 matched.toString()）；none = 无匹配",
          "ambiguous_identification_cells": multi,
          "bs_less_than_h": shorter, "bs_equals_rule": ok,
          "reading": ("实测有效块长 = min(h, floor(sqrt(n)), n−1)，故 h > floor(sqrt(n)) 时"
                      "实际块长 < h")}
    return ("REGISTERED_ONLY" if ok else "VIOLATED"), ev


def ck_A4b(ctx):
    """javadoc 声明「块大小 = 持有期 h」与实际块长是否一致。"""
    jd = ctx["text"]["c_bb_javadoc"]
    claims_h = any("块大小 = 持有期 h" in ln for ln in jd)
    a4a_v, a4a_ev = ck_A4a(ctx)
    shorter = a4a_ev.get("bs_less_than_h") or []
    ev = {"javadoc_lines": jd, "javadoc_claims_block_equals_h": claims_h,
          "a4a_verdict": a4a_v, "bs_less_than_h": shorter,
          "inline_comment_discloses_clamp": any(
              "避免块大小接近/等于样本量" in ln for ln in ctx["src"]["engine_lines"]),
          "reading": ("javadoc 说块长 = h，实现把块长钳到 floor(sqrt(n)) ⟹ 文档/实现漂移"
                      "（行内注释已披露理由）")}
    return ("CONTRACT_AMBIGUOUS" if (claims_h and shorter) else "NOT_VIOLATED"), ev


def ck_A5a(ctx):
    """hac_std 与 rank_ic_std/√T 的尺度关系（冻结相对容差 5%）。"""
    is_ = ind(ctx, "V13", "prod")
    panels = []
    for i in is_:
        prod = pn(ctx, "V13", "prod", i)
        exp = pn(ctx, "V13", "rank_ic_std_over_sqrtT", i)
        rel = abs(prod - exp) / exp if exp else float("nan")
        panels.append({"panel": i, "T": pv(ctx, "V13", "T", i), "kind": pv(ctx, "V13", "kind", i),
                       "prod": prod, "expected": exp, "rel_diff": rel,
                       "rel_diff_emitted": pn(ctx, "V13", "rel_diff", i)})
    ex_prod = pn(ctx, "V13", "ex_test_prod")
    ex_exp = pn(ctx, "V13", "ex_test_expected")
    ex_diff = abs(ex_prod - ex_exp)
    ev = {"tolerance_relative": TOL_REL_A5A, "panels": panels,
          "existing_test_input": {"prod": ex_prod, "expected": ex_exp, "abs_diff": ex_diff,
                                  "test_tolerance_abs": EXISTING_TEST_TOL,
                                  "test_assertion_holds": ex_diff < EXISTING_TEST_TOL,
                                  "test_tolerance_relative_pct": (100.0 * EXISTING_TEST_TOL / ex_exp)
                                  if ex_exp else None},
          "reading": "既有测试用绝对容差 0.01 断言同一不变式；本轮冻结用相对容差 5%"}
    bad = [p for p in panels if not (p["rel_diff"] <= TOL_REL_A5A)]
    if not bad:
        return "NOT_VIOLATED", ev
    if not ev["existing_test_input"]["test_assertion_holds"]:
        ev["disposition"] = "既有测试输入上的交叉核对不一致 ⟹ 按冻结处置规则记 INCONCLUSIVE（工具问题）"
        return "INCONCLUSIVE", ev
    ev["disposition"] = ("既有测试输入上的交叉核对一致（其绝对容差 0.01 满足）⟹ 记事实；"
                         "按冻结文本 A5a 不是杀死条件，不单独构成 REJECTED")
    return "VIOLATED", ev


def ck_A5b(ctx):
    """两个 std 的量级差（登记）。"""
    is_ = ind(ctx, "V14", "inv_sqrt_T")
    panels = []
    for i in is_:
        T = pv(ctx, "V14", "T", i)
        panels.append({"T": T, "inv_sqrt_T": pn(ctx, "V14", "inv_sqrt_T", i),
                       "rank_ic_std": pn(ctx, "V14", "rank_ic_std", i),
                       "hac_std": pn(ctx, "V14", "hac_std", i),
                       "ratio": pn(ctx, "V14", "ratio_rank_std_over_hac_std", i),
                       "frozen_expected_inv_sqrt_T": {10: 0.316, 17: 0.243}.get(T)})
    ok = all(abs(p["inv_sqrt_T"] - p["frozen_expected_inv_sqrt_T"]) <= 0.001 for p in panels
             if p["frozen_expected_inv_sqrt_T"] is not None) and bool(panels)
    ev = {"panels": panels, "frozen_anchor_match": ok,
          "reading": ("两个字段都叫 std：rank_ic_std 是日序列标准差，hac_std 是**均值**的标准误；"
                      "后者量级 ≈ 前者/√T；实测比值大于 √T，因为该关系只在 i.i.d. 下严格成立")}
    return ("REGISTERED_ONLY" if ok else "VIOLATED"), ev


def ck_A5c(ctx):
    """覆盖率模拟（80 单元 × 2 臂）。"""
    is_ = ind(ctx, "V15", "ok_count")
    cells = []
    for i in is_:
        reps = pv(ctx, "V15", "reps", i)
        k1 = pv(ctx, "V15", "ok_count", i)
        k2 = pv(ctx, "V15", "ok_count2", i)
        c1 = k1 / reps
        c2 = k2 / reps
        cells.append({
            "n": pv(ctx, "V15", "n", i), "h": pv(ctx, "V15", "h", i),
            "dgp": pv(ctx, "V15", "dgp", i), "reps": reps,
            "mu_true": pn(ctx, "V15", "mu_true", i),
            "arm": pv(ctx, "V15", "arm", i), "arm2": pv(ctx, "V15", "arm2", i),
            "boot": {"ok": k1, "coverage": c1, "cov_emitted": pn(ctx, "V15", "coverage", i),
                     "zero_width": pv(ctx, "V15", "zero_width_count", i),
                     "cp_lower_below_th": cp_lower_below(k1, reps, TH_A5C_COV),
                     "trigger": (c1 < TH_A5C_COV) and cp_lower_below(k1, reps, TH_A5C_COV)},
            "hac": {"ok": k2, "coverage": c2, "cov_emitted": pn(ctx, "V15", "coverage2", i),
                    "se_zero": pv(ctx, "V15", "se_zero_count", i),
                    "cp_lower_below_th": cp_lower_below(k2, reps, TH_A5C_COV),
                    "trigger": (c2 < TH_A5C_COV) and cp_lower_below(k2, reps, TH_A5C_COV)},
        })
    trig = [c for c in cells if c["boot"]["trigger"] or c["hac"]["trigger"]]
    ev = {"threshold_coverage": TH_A5C_COV, "cp_alpha": TH_A5C_CP_ALPHA,
          "cp_equivalence": "L<0.85 ⟺ P(X>=k | p=0.85) > 0.01（tail 对 p 单调递增）",
          "cells": cells, "n_cells": len(cells),
          "triggering_cells": [{"n": c["n"], "h": c["h"], "dgp": c["dgp"],
                                "boot_cov": c["boot"]["coverage"], "hac_cov": c["hac"]["coverage"]}
                               for c in trig],
          "n_triggering_cells": len(trig),
          "min_boot_cov": min([c["boot"]["coverage"] for c in cells] or [None]),
          "min_hac_cov": min([c["hac"]["coverage"] for c in cells] or [None]),
          "max_boot_cov": max([c["boot"]["coverage"] for c in cells] or [None]),
          "effective_cut_disclosed_in_prereg": "≈0.87（reps=1000）",
          "reading": ("名义 95% 区间在冻结 DGP 网格上的实测覆盖率；DGP 是重叠依赖的风格化表征，"
                      "不是对真实 IC 序列依赖结构的主张")}
    return ("VIOLATED" if trig else "NOT_VIOLATED"), ev


def ck_A6a(ctx):
    """是否存在名义/多重检验标注（生产面）。"""
    prod = ctx["text"]["c_nominal_prod"]
    allh = ctx["text"]["c_nominal_all"]
    ev = {"scan_root_production": "modules/（排除 node_modules / target / dist）",
          "prod_hits": prod, "repo_hits_including_research": allh,
          "research_hits_excluded_by_design": [h for h in allh if h.startswith("research/")],
          "reading": ("生产面 0 命中 ⟹ 区间未标注为「名义 95%」，也未提示多重检验；"
                      "research/ 下的命中是本轮自己的研究文本，按扫描根声明排除")}
    return ("REGISTERED_ONLY" if not prod else "NOT_VIOLATED"), ev


def ck_A6b(ctx):
    """登记性对照：mean/hac_std 与 1.96 / 3.0 / 3.9 的关系（禁止显著性叙述）。"""
    is_ = ind(ctx, "V17", "t_hac")
    panels = []
    for i in is_:
        m, se = pn(ctx, "V17", "mean", i), pn(ctx, "V17", "hac_std", i)
        t = pn(ctx, "V17", "t_hac", i)
        panels.append({"T": pv(ctx, "V17", "T", i), "mean": m, "hac_std": se, "t_hac": t,
                       "t_matches_ratio": close(t, m / se if se else float("nan"), 1e-9),
                       "hurdles": [pn(ctx, "V17", "hurdle_1_96", i),
                                   pn(ctx, "V17", "hurdle_3_0", i),
                                   pn(ctx, "V17", "hurdle_3_9", i)]})
    ok = all(p["t_matches_ratio"] for p in panels) and bool(panels)
    ev = {"panels": panels, "arithmetic_identity_holds": ok,
          "series_kind": "注入的**合成**固定序列，非项目真实 IC 序列",
          "narrative_ban": "禁止任何「因子显著性」叙述（prereg §9.7）；本项只登记对照",
          "reading": "仅登记 t 值与门槛的算术关系；两个序列均为探针合成输入"}
    return ("REGISTERED_ONLY" if ok else "VIOLATED"), ev


def ck_A7a(ctx):
    """同一输入两次调用的确定性。"""
    ev = {"ci_a": [pn(ctx, "V18", "ci_a_lo"), pn(ctx, "V18", "ci_a_hi")],
          "ci_b": [pn(ctx, "V18", "ci_b_lo"), pn(ctx, "V18", "ci_b_hi")],
          "hac_a": pn(ctx, "V18", "hac_a"), "hac_b": pn(ctx, "V18", "hac_b"),
          "ci_identical": pv(ctx, "V18", "ci_identical"),
          "hac_identical": pv(ctx, "V18", "hac_identical"),
          "summarize_ci_identical": pv(ctx, "V18", "summarize_ci_identical"),
          "summarize_hac_identical": pv(ctx, "V18", "summarize_hac_identical"),
          "reading": "固定种子 ⟹ 逐位可复现"}
    ok = all(pv(ctx, "V18", k) == 1 for k in ("ci_identical", "hac_identical",
                                             "summarize_ci_identical", "summarize_hac_identical"))
    return ("NOT_VIOLATED" if ok else "VIOLATED"), ev


def ck_A7b(ctx):
    """iterations < 100 ⟹ {0,0} 在 API 面是否可达。"""
    calls = ctx["text"]["c_bb_calls"]
    site = [ln for ln in calls if "blockBootstrapCi(" in ln and "public static" not in ln
            and "private " not in ln and "protected " not in ln]
    hard = [ln for ln in site if "1000, 42L" in ln]
    ev = {"call_sites": site, "call_sites_with_1000_42L": hard,
          "declaration_lines": [ln for ln in calls if "public static" in ln],
          "reading": "产品调用点硬编码 iterations=1000 ⟹ iterations<100 的退化分支在 API 面不可达"}
    ok = len(site) >= 1 and len(hard) == len(site)
    return ("NOT_REACHABLE" if ok else "VIOLATED"), ev


_CSV_TOKEN_RX = re.compile(r"'([A-Za-z_0-9]+)'")


def _csv_lines(ctx):
    """CSV 导出片段的解析：定位表头行（含 join(',')）与取值行（含 cost_net_ic）。"""
    lines = ctx["text"]["csv_lines"]
    header = [ln for ln in lines if "join(',')" in ln]
    value = [ln for ln in lines if "cost_net_ic" in ln and "row." in ln]
    header_line = header[0] if header else None
    value_line = value[0] if value else None
    return {"lines": lines, "header_line": header_line, "value_line": value_line,
            "header_tokens": _CSV_TOKEN_RX.findall(header_line) if header_line else [],
            "value_txt": value_line or ""}


def ck_A8a(ctx):
    """CSV 列名 net_ic 的值来源。"""
    csv = _csv_lines(ctx)
    tokens = csv["header_tokens"]
    ev = {"csv_method_lines": ctx["src"]["csv_range"], "header_line": csv["header_line"],
          "value_line": csv["value_line"], "header_tokens": tokens,
          "from_repo_source": csv["lines"] == ctx["src"]["csv_lines"],
          "header_has_plain_net_ic": "net_ic" in tokens,
          "value_from_cost_net_ic": "cost_net_ic?" in csv["value_txt"],
          "value_is_rank_ic_mean": "rank_ic_mean" in csv["value_txt"],
          "reading": ("表头 token 写 net_ic（无 cost_ 前缀、无 rank_ 限定），"
                      "值取 cost_net_ic.rank_ic_mean")}
    if csv["header_line"] is None or csv["value_line"] is None:
        return "INCONCLUSIVE", ev
    ok = (ev["header_has_plain_net_ic"] and ev["value_from_cost_net_ic"]
          and ev["value_is_rank_ic_mean"])
    return ("CONTRACT_MISLABEL" if ok else "NOT_VIOLATED"), ev


def ck_A8b(ctx):
    """紧跟 net_ic 的三列取自毛收益块（同排不同口径）。"""
    csv = _csv_lines(ctx)
    tokens = csv["header_tokens"]
    v_txt = csv["value_txt"]
    try:
        idx = tokens.index("net_ic")
        after = tokens[idx + 1: idx + 4]
    except ValueError:
        after = []
    used = [k for k in ("hac_std", "ci_lower", "ci_upper")
            if ("row.%s" % k) in v_txt]
    ev = {"header_tokens": tokens, "header_after_net_ic": after,
          "value_line_uses_row_scope": used,
          "reading": ("net_ic 列取成本后块（cost_net_ic.rank_ic_mean），紧随的 hac_std/ci_* 取 "
                      "row（毛收益块）⟹ 同一行内两种口径并列")}
    if not after:
        return "INCONCLUSIVE", ev
    ok = after == ["hac_std", "ci_lower", "ci_upper"] and len(used) == 3
    return ("REGISTERED_ONLY" if ok else "NOT_VIOLATED"), ev


CHECKERS = {
    "A1a": ck_A1a, "A1b": ck_A1b,
    "A2a": ck_A2a, "A2b": ck_A2b, "A2c": ck_A2c, "A2d": ck_A2d,
    "A3a": ck_A3a, "A3b": ck_A3b, "A3c": ck_A3c, "A3d": ck_A3d,
    "A4a": ck_A4a, "A4b": ck_A4b,
    "A5a": ck_A5a, "A5b": ck_A5b, "A5c": ck_A5c,
    "A6a": ck_A6a, "A6b": ck_A6b,
    "A7a": ck_A7a, "A7b": ck_A7b,
    "A8a": ck_A8a, "A8b": ck_A8b,
}


# ===========================================================================
# 5) 金丝雀（CLN / INJ 成对；每个检查器恰一对）
#    harness 说明（与 R006 的一处差异，已写进报告附录 A）：CLN 与 INJ 都作用在
#    **归一化后的上下文**上，使两条期望值在运行前即可确定；「检查器不是恒真/恒假」这一点不变。
# op 形式：
#   ("probe_set", probe, key, idx|None, value) | ("probe_map", probe, key, {idx: value})
#   ("probe_copy", probe, dst_key, src_key, None) | ("text_set", name, lines)
#   ("text_restore", name) | ("src_insert_early", [keys]) | ("src_restore", None)
#   ("multi", [op, ...])
# ===========================================================================
def build_canary(ctx):
    C = {}

    v01 = ind(ctx, "V01", "abs_diff_lo")
    C["A1a"] = {
        "cln_op": ("multi", [("probe_copy", "V01", "ci_lower", "normal_lo", None),
                             ("probe_copy", "V01", "ci_upper", "normal_hi", None)]),
        "clean": "INCONCLUSIVE", "inj": "NOT_VIOLATED",
        "inj_op": ("multi", [("probe_copy", "V01", "ci_lower", "normal_lo", None),
                             ("probe_copy", "V01", "ci_upper", "normal_hi", None),
                             ("probe_set", "V01", "ci_lower", v01[0], 0.0),
                             ("probe_set", "V01", "ci_upper", v01[0], 0.0)]),
        "intent": "CLN：ci_* 被改成与 normal-lo/hi 同源 ⟹ 无法支撑「值来自另一估计量」；"
                  "INJ：仅在面板 0 上人为偏移 ⟹ 差异重新出现"}

    C["A1b"] = {
        "cln_op": ("multi", [
            ("text_set", "c_labels_ci",
             ["modules/web/src/views/factor/FactorResearch.vue:139:"
              " <span>Bootstrap 95%CI：{{ fmt4(blk.ci_lower) }} ~ {{ fmt4(blk.ci_upper) }}</span>"]),
            ("text_set", "c3", [])]),
        "clean": "NOT_VIOLATED", "inj": "CONTRACT_MISLABEL",
        "inj_op": ("multi", [("text_restore", "c_labels_ci"), ("text_restore", "c3")]),
        "intent": "CLN：标签（含表格列头语料）改称 Bootstrap ⟹ 指称与实际估计量一致；"
                  "INJ：恢复原标签（HAC 95%CI）"}

    C["A2a"] = {
        "cln_op": ("probe_set", "V03", "cross_section_days", None, 4),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V03", "cross_section_days", None, 1),
        "intent": "CLN：截面日数改成 4（不再触发 T=1 退化并存）；INJ：恢复 T=1"}

    C["A2b"] = {
        "cln_op": ("multi", [("text_set", "c5", []),
                             ("probe_set", "V04", "empty_cross_section_days", None, 1)]),
        "clean": "VIOLATED", "inj": "REGISTERED_ONLY",
        "inj_op": ("multi", [("text_restore", "c5"),
                             ("probe_set", "V04", "empty_cross_section_days", None, 0)]),
        "intent": "CLN：抹掉 status 与 cross_section_days==0 两个信号 ⟹ 弱可区分性消失；"
                  "INJ：恢复两个信号（必须识别出弱可区分性存在）"}

    C["A2c"] = {
        "cln_op": ("probe_map", "V05", "clamp_rate",
                   {i: 0.0 for i in ind(ctx, "V05", "clamp_rate")}),
        "clean": "REGISTERED_ONLY", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V05", "clamp_rate",
                   [i for i in ind(ctx, "V05", "clamp_rate")
                    if pv(ctx, "V05", "n", i) >= 10][0], 0.10),
        "intent": "CLN：所有单元钳位率置 0；INJ：把一个 n>=10 单元置 0.10（越过 5% 阈值）"}

    C["A2d"] = {
        "cln_op": ("src_insert_early", ["hac_std", "ci_lower", "ci_upper"]),
        "clean": "NOT_VIOLATED", "inj": "CONTRACT_AMBIGUOUS",
        "inj_op": ("src_restore", None),
        "intent": "CLN：早返回分支补上 hac_std/ci_lower/ci_upper ⟹ 两种键集一致；INJ：恢复原状"}

    C["A3a"] = {
        "cln_op": ("probe_set", "V07", "nw_has_integral_param", None, 1),
        "clean": "INCONCLUSIVE", "inj": "REGISTERED_ONLY",
        "inj_op": ("probe_set", "V07", "nw_has_integral_param", None, 0),
        "intent": "CLN：反射结果说 HAC 函数有整型入参（窗口可随 h 变）⟹ 签名事实不成立；INJ：恢复"}

    C["A3b"] = {
        "cln_op": ("probe_map", "V08", "abs_diff_cbrt",
                   {i: 0.0 for i in ind(ctx, "V08", "abs_diff_cbrt")}),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V08", "abs_diff_cbrt", 0, 1e-6),
        "intent": "CLN：全部单元偏差置 0（逐位一致）；INJ：把单元 0 的偏差置 1e-6（越过 1e-12 容差）"}

    h5 = [i for i in ind(ctx, "V09", "ratio_cbrt_over_hm1") if pv(ctx, "V08", "h", i) >= 5]
    C["A3c"] = {
        "cln_op": ("probe_map", "V09", "ratio_cbrt_over_hm1", {i: 0.95 for i in h5}),
        "clean": "NOT_VIOLATED", "inj": "CONVENTION_DIVERGENCE",
        "inj_op": ("probe_map", "V09", "ratio_cbrt_over_hm1", {i: 0.85 for i in h5[:1]}),
        "intent": "CLN：所有 h>=5 单元比值置 0.95（未达 0.90 材料性）；INJ：把一个置 0.85"}

    C["A3d"] = {
        "cln_op": ("text_set", "c_embargo", ["probe: 显著性以 block bootstrap CI 为准"]),
        "clean": "NOT_VIOLATED", "inj": "CONTRACT_MISLABEL",
        "inj_op": ("multi", [("text_restore", "c_embargo"),
                             ("probe_map", "V09", "ratio_cbrt_over_hm1",
                              {i: 0.85 for i in h5[:1]})]),
        "intent": "CLN：承诺文本不再以 HAC 为基准 ⟹ 与窗口解耦无关；INJ：恢复承诺文本并让 A3c 达材料性"}

    v11 = ind(ctx, "V11", "matched_bs")
    C["A4a"] = {
        "cln_op": ("probe_map", "V11", "matched_bs",
                   {i: str(pv(ctx, "V11", "rule_expected_bs", i)) for i in v11}),
        "clean": "REGISTERED_ONLY", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V11", "matched_bs", v11[0], "none"),
        "intent": "CLN：识别结果 = 规则值；INJ：把面板 0 的识别结果置 none（识别失败）"}

    C["A4b"] = {
        "cln_op": ("text_set", "c_bb_javadoc",
                   ["probe: Block bootstrap 置信区间（块大小 = min(blockSize, floor(sqrt(n)), n-1)）"]),
        "clean": "NOT_VIOLATED", "inj": "CONTRACT_AMBIGUOUS",
        "inj_op": ("multi", [("text_restore", "c_bb_javadoc"),
                             ("probe_map", "V11", "matched_bs",
                              {i: "3" for i in v11 if pv(ctx, "V11", "h", i) >= 5})]),
        "intent": "CLN：javadoc 改成与实现一致的块长公式；INJ：恢复 javadoc 并给出 bs<h 的实测"}

    v13 = ind(ctx, "V13", "prod")
    C["A5a"] = {
        "cln_op": ("probe_map", "V13", "rank_ic_std_over_sqrtT",
                   {i: pn(ctx, "V13", "prod", i) for i in v13}),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_map", "V13", "rank_ic_std_over_sqrtT",
                   {i: pn(ctx, "V13", "rank_ic_std_over_sqrtTm1", i) for i in v13}),
        "intent": "CLN：期望值置为实测值（零偏差）；INJ：期望值改成 ÷√(T−1) 的口径（§7 INJ-3）"}

    v14 = ind(ctx, "V14", "inv_sqrt_T")
    C["A5b"] = {
        "cln_op": ("probe_map", "V14", "inv_sqrt_T",
                   {i: pn(ctx, "V14", "inv_sqrt_T", i) for i in v14}),
        "clean": "REGISTERED_ONLY", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V14", "inv_sqrt_T", v14[0], 0.999),
        "intent": "CLN：锚点保持不动；INJ：把 1/√T 锚点改成 0.999（与冻结期望 0.316 不符）"}

    v15 = ind(ctx, "V15", "ok_count")

    def cov_950(_i):
        return 950

    C["A5c"] = {
        "cln_op": ("multi", [("probe_map", "V15", "ok_count", {i: 950 for i in v15}),
                             ("probe_map", "V15", "coverage", {i: 0.95 for i in v15}),
                             ("probe_map", "V15", "ok_count2", {i: 950 for i in v15}),
                             ("probe_map", "V15", "coverage2", {i: 0.95 for i in v15})]),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("multi", [("probe_map", "V15", "ok_count", {i: 950 for i in v15}),
                             ("probe_map", "V15", "coverage", {i: 0.95 for i in v15}),
                             ("probe_map", "V15", "ok_count2", {i: 950 for i in v15}),
                             ("probe_map", "V15", "coverage2", {i: 0.95 for i in v15}),
                             ("probe_set", "V15", "ok_count", v15[0], 800),
                             ("probe_set", "V15", "coverage", v15[0], 0.80)]),
        "intent": "CLN：全部单元 950/1000（§7 CLN-4）；INJ：把一个单元改成 800/1000（§7 INJ-4）"}

    C["A6a"] = {
        "cln_op": ("text_set", "c_nominal_prod", []),
        "clean": "REGISTERED_ONLY", "inj": "NOT_VIOLATED",
        "inj_op": ("text_set", "c_nominal_prod",
                   ["modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java:1:"
                    "// nominal 95% CI（未做多重检验校正）"]),
        "intent": "CLN：生产面 0 命中；INJ：注入一行 nominal 标注 ⟹ 批评不再适用"}

    v17 = ind(ctx, "V17", "t_hac")
    C["A6b"] = {
        "cln_op": ("probe_map", "V17", "t_hac",
                   {i: pn(ctx, "V17", "mean", i) / pn(ctx, "V17", "hac_std", i) for i in v17}),
        "clean": "REGISTERED_ONLY", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V17", "t_hac", v17[0], pn(ctx, "V17", "t_hac", v17[0]) + 1.0),
        "intent": "CLN：t 值 = mean/hac_std（算术恒等成立）；INJ：人为把 t 值 +1（恒等式破裂）"}

    C["A7a"] = {
        "cln_op": ("multi", [("probe_copy", "V18", "ci_b_lo", "ci_a_lo", None),
                             ("probe_copy", "V18", "ci_b_hi", "ci_a_hi", None),
                             ("probe_set", "V18", "ci_identical", None, 1)]),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V18", "ci_identical", None, 0),
        "intent": "CLN：两次调用结果一致；INJ：置 ci_identical=0（不可复现）"}

    C["A7b"] = {
        "cln_op": ("text_set", "c_bb_calls", list(ctx["text"]["c_bb_calls"])),
        "clean": "NOT_REACHABLE", "inj": "VIOLATED",
        "inj_op": ("text_set", "c_bb_calls",
                   ["modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java:191:"
                    "        double[] ci = blockBootstrapCi(icSeries, Math.max(1, holdingDays), 50, 42L);"]),
        "intent": "CLN：调用点硬编码 1000（不可达退化分支）；INJ：注入 50 次迭代的调用点"}

    C["A8a"] = {
        "cln_op": ("text_copy", "csv_lines", "csv_lines_corrected"),
        "clean": "NOT_VIOLATED", "inj": "CONTRACT_MISLABEL",
        "inj_op": ("text_copy", "csv_lines", "csv_lines"),
        "intent": "CLN：表头改成带 cost_ 前缀、值来自 cost_net_ic ⟹ 命名与口径一致；INJ：恢复原表头"}

    C["A8b"] = {
        "cln_op": ("text_copy", "csv_lines", "csv_lines"),
        "clean": "REGISTERED_ONLY", "inj": "NOT_VIOLATED",
        "inj_op": ("text_copy", "csv_lines", "csv_lines_netonly"),
        "intent": "CLN：net_ic 之后紧随毛收益块三列（同排不同口径）；INJ：紧随三列也改成成本后块"}

    return C


def apply_op(ctx, op):
    ctx2 = copy.deepcopy(ctx)
    _apply_inplace(ctx2, op)
    return ctx2


def _apply_inplace(ctx2, op):
    kind = op[0]
    if kind == "multi":
        for sub in op[1]:
            _apply_inplace(ctx2, sub)
    elif kind == "probe_set":
        _, probe, key, idx, val = op
        ctx2["probe"][(probe, key, idx)] = val
    elif kind == "probe_map":
        _, probe, key, mapping = op
        for idx, val in mapping.items():
            ctx2["probe"][(probe, key, idx)] = val
    elif kind == "probe_copy":
        _, probe, dst, src, idx = op
        for k in list(ctx2["probe"].keys()):
            if k[0] == probe and k[1] == src and (idx is None or k[2] == idx):
                ctx2["probe"][(probe, dst, k[2])] = ctx2["probe"][k]
    elif kind == "text_set":
        _, name, lines = op
        ctx2["text"][name] = list(lines)
    elif kind == "text_restore":
        _, name = op
        ctx2["text"][name] = list(ctx2["text_pristine"][name])
    elif kind == "text_copy":
        _, dst, srcname = op
        ctx2["text"][dst] = list(ctx2["text_pristine"][srcname])
    elif kind == "src_insert_early":
        _, keys = op
        cur = list(ctx2["src"]["early_keys"])
        ctx2["src"]["early_keys"] = cur + [k for k in keys if k not in cur]
    elif kind == "src_restore":
        ctx2["src"]["early_keys"] = list(ctx2["text_pristine"]["__early_keys"])
    else:
        raise ValueError("unknown op: %r" % (kind,))


# ===========================================================================
# 6) config.json 冻结比对
# ===========================================================================
def verify_config_frozen():
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
        "count_expectations": cfg.get("count_expectations") == EXPECT_COUNTS,
        "verdict_vocab": cfg.get("verdict_vocab") == VERDICT_VOCAB,
        "thresholds": cfg.get("thresholds") == {
            "TOL": TOL, "TOL_CI": TOL_CI, "TOL_REL_A5A": TOL_REL_A5A, "TH_A2C": TH_A2C,
            "TH_A3C": TH_A3C, "TH_A5C_COV": TH_A5C_COV, "TH_A5C_CP_ALPHA": TH_A5C_CP_ALPHA,
            "EXISTING_TEST_TOL": EXISTING_TEST_TOL, "Z95": Z95},
        "simulation": cfg.get("simulation") == {
            "REPS_COVER": REPS_COVER, "REPS_CLAMP": REPS_CLAMP, "REPS_MEDIAN": REPS_MEDIAN,
            "BOOT_ITERS": BOOT_ITERS, "BOOT_SEED": BOOT_SEED},
    }
    return all(checks.values()), checks


# ===========================================================================
# 7) 计数断言 C1..C9（计数不匹配只记附录 A）
# ===========================================================================
def count_by_file(lines):
    d = {}
    for ln in lines:
        f = ln.split(":", 1)[0]
        d[f] = d.get(f, 0) + 1
    return d


def eval_counts(ctx):
    t = ctx["text"]
    out = {}

    def rec(cid, actual, expected, command, extra=None):
        row = {"actual": actual, "frozen_expected": expected, "match": actual == expected,
               "command": command}
        if extra:
            row.update(extra)
        out[cid] = row

    prod_src = [h for h in t["c1_prod"] if not any(s in h for s in ("target/", "dist/"))]
    rec("C1", len(prod_src), EXPECT_COUNTS["C1"]["prod_src"],
        'grep -rn "hac_std" --include=*.java --include=*.vue --include=*.ts --include=*.js '
        '--include=*.sql --include=*.py .',
        {"by_file_src": count_by_file(prod_src), "raw_incl_build_artifacts": len(t["c1_prod_raw"]),
         "research": len(t["c1_research"])})
    prod_src2 = [h for h in t["c2_prod"] if not any(s in h for s in ("target/", "dist/"))]
    rec("C2", len(prod_src2), EXPECT_COUNTS["C2"]["prod_src"],
        'grep -rn "ci_lower\\|ci_upper" --include=*.java --include=*.vue --include=*.ts '
        '--include=*.js --include=*.sql --include=*.py .',
        {"by_file_src": count_by_file(prod_src2), "raw_incl_build_artifacts": len(t["c2_prod_raw"]),
         "research": len(t["c2_research"])})
    rec("C3", len(t["c3"]), EXPECT_COUNTS["C3"]["web"],
        'grep -rn "HAC 95%CI" modules/web/src', {"lines": t["c3"]})
    call_sites = [h for h in t["c4"] if ":190:" in h or ":191:" in h]
    decls = [h for h in t["c4"] if ":324:" in h or ":358:" in h]
    tests = [h for h in t["c4"] if "/test/" in h]
    rec("C4", len(call_sites), EXPECT_COUNTS["C4"]["prod_call"],
        'grep -rn "neweyWestHacStd(\\|blockBootstrapCi(" modules/backend-java/src',
        {"prod_decl": len(decls), "prod_decl_expected": EXPECT_COUNTS["C4"]["prod_decl"],
         "test": len(tests), "test_expected": EXPECT_COUNTS["C4"]["test"],
         "all_hits": len(t["c4"])})
    rec("C5", len(t["c5"]), EXPECT_COUNTS["C5"]["frs_status"],
        'grep -n \'"status"\' .../FactorResearchService.java', {"lines": t["c5"]})
    c6_def = [h for h in t["c6"] if "private static final" in h]
    c6_refs = [h for h in t["c6"] if "private static final" not in h]
    c6_fe_vue = [h for h in t["c6_fe"] if h.endswith(".vue") or ".vue:" in h]
    c6_fe_ts = [h for h in t["c6_fe"] if ".ts:" in h]
    c6_match = (len(c6_def) == EXPECT_COUNTS["C6"]["def"]
                and len(c6_refs) == EXPECT_COUNTS["C6"]["refs"]
                and len(c6_fe_vue) == EXPECT_COUNTS["C6"]["fe"])
    out["C6"] = {"actual": len(t["c6"]), "frozen_expected": None, "match": c6_match,
                 "command": 'grep -rn "RANK_IC_EMBARGO_NOTE" --include=*.java . ; '
                            'grep -rn "embargo_note" modules/web/src',
                 "def": len(c6_def), "def_expected": EXPECT_COUNTS["C6"]["def"],
                 "refs": len(c6_refs), "refs_expected": EXPECT_COUNTS["C6"]["refs"],
                 "fe_bindings_vue": len(c6_fe_vue), "fe_expected": EXPECT_COUNTS["C6"]["fe"],
                 "fe_ts_declarations": len(c6_fe_ts),
                 "fe_note": "冻结期望记的是 vue:329 的绑定（1）；factor.ts 的两处是 TS 字段声明，另列",
                 "lines": t["c6"], "fe_lines": t["c6_fe"]}
    csv_header = [h for h in t["c7"] if "'net_ic'" in h]
    rec("C7", len(csv_header), EXPECT_COUNTS["C7"]["vue_net_ic_header"],
        'grep -n "net_ic" modules/web/src/views/factor/FactorResearch.vue',
        {"all_net_ic_mentions": len(t["c7"])})
    c8_prod = [h for h in t["c8"] if "/test/" not in h]
    c8_test = [h for h in t["c8"] if "/test/" in h]
    rec("C8", len(c8_prod), EXPECT_COUNTS["C8"]["prod"],
        'grep -rn "1000, 42L" --include=*.java modules/backend-java/src',
        {"test": len(c8_test), "test_expected": EXPECT_COUNTS["C8"]["test"]})
    rec("C9", len(t["c9"]), EXPECT_COUNTS["C9"]["sql"],
        'grep -rni "hac\\|ci_lower\\|ci_upper" --include=*.sql .')
    out["_all_match"] = all(v["match"] for k, v in out.items() if not k.startswith("_"))
    out["_note"] = ("计数不匹配按 prereg §8 只写入报告附录 A，不改变任何判定词；"
                    "raw_incl_build_artifacts = 未排除 target/dist 的原始命令口径"
                    "（冻结命令未加 --exclude-dir，故含前端构建产物）")
    return out


# ===========================================================================
# 8) 主流程
# ===========================================================================
def main():
    global _log_fh
    started = datetime.now()
    _log_fh = open(os.path.join(HERE, "run.log"), "a", encoding="utf-8")
    log("=" * 78)
    log("R007 统计不确定性载荷族审计 —— 只读；判据冻结于 rounds/R007_prereg.md")
    log("=" * 78)

    result = {
        "round": "R007", "started_at": started.isoformat(),
        "prereg": "rounds/R007_prereg.md", "review": "rounds/R007_review.md",
        "criteria_source": "Harvey-Liu-Zhu, NBER WP 20592 (2014)",
        "audited_target": ("hac_std / ci_lower / ci_upper（FactorResearchEngine → "
                           "FactorResearchService → 前端标签）"),
        "hypothesis_H0": ("载荷键忠实对应其名称与定义（HAC 标准误 / 同一均值的 95% 区间）；"
                          "定义失效时不输出看起来像估计值的具体实数"),
        "readonly_attestation": {
            "db_statements_executed": 0,
            "note": "本轮不访问任何数据库；仅读文件系统 + 在 JVM 内调用生产类（只读方法）",
            "files_written": ["run.log", "result.json", "probe/out/*（运行后删除）"],
            "production_files_modified": 0, "test_files_modified": 0},
    }

    probe = compile_and_run_probe()
    if probe is None:
        result["fatal"] = "探针编译或运行失败 ⟹ 无法执行任何轴"
        result["total_verdict"] = "INCONCLUSIVE"
        with open(os.path.join(HERE, "result.json"), "w", encoding="utf-8") as fh:
            json.dump(result, fh, ensure_ascii=False, indent=2, default=str)
        log("FATAL: 探针失败，整轮 INCONCLUSIVE")
        return

    wrap = {"probe": probe}
    # ---- 门 G3 ----
    src = str(pv(wrap, "G3", "engine_code_source"))
    norm = src.replace("\\", "/").rstrip("/")
    want = CLASSES.replace("\\", "/").rstrip("/")
    gate_g3 = norm.lower().endswith(want.lower())
    gate_reverse = not close(pn(wrap, "G3", "sentinel_nw_reverse"),
                             pn(wrap, "G3", "sentinel_nw_lag3_control"), 1e-9)
    # 探针键名叫 sentinel_nw_perm_control，实现的是 lag=2 对照（见报告附录 A 的口径更正）。
    # n=10 时 cbrt 规则 lag = floor(10^(1/3)) = 2 ⟹ 与生产值相等是**预期**行为，不是异常。
    gate_lag2 = close(pn(wrap, "G3", "sentinel_nw_reverse"),
                      pn(wrap, "G3", "sentinel_nw_perm_control"), 1e-12)
    g3 = {"engine_code_source": src, "expected": CLASSES, "is_repo_classes": gate_g3,
          "class": pv(wrap, "G3", "engine_class"),
          "classloader": pv(wrap, "G3", "engine_classloader"),
          "sentinel_nw_reverse": pn(wrap, "G3", "sentinel_nw_reverse"),
          "sentinel_nw_lag3_control": pn(wrap, "G3", "sentinel_nw_lag3_control"),
          "sentinel_nw_lag2_control_probe_key_perm": pn(wrap, "G3", "sentinel_nw_perm_control"),
          "sentinel_reverse_differs_from_lag3": gate_reverse,
          "sentinel_reverse_equals_lag2_control": gate_lag2,
          "sentinel_note": ("探针键名 ..._perm_control 实现的是 lag=2 对照；n=10 的 cbrt 规则 lag=2，"
                            "故生产值与它相等是预期；门只要求「与 lag3 对照不同」（反向锚点）"),
          "sentinel_t1_hac": pn(wrap, "G3", "sentinel_t1_hac"),
          "sentinel_t1_ci": [pn(wrap, "G3", "sentinel_t1_ci_lo"), pn(wrap, "G3", "sentinel_t1_ci_hi")]}
    log("G3 engine_code_source = %s  (is_repo_classes=%s, reverse_anchor_vs_lag3=%s, "
        "equals_lag2_control=%s)" % (src, gate_g3, gate_reverse, gate_lag2))

    # ---- 与既有测试的一致性门 ----
    ex_diff = pn(wrap, "G3", "ex_test_nw_diff")
    consistency_gate = {
        "existing_test": "FactorResearchEngineTest.neweyWestHacStd*:145-153",
        "probe_prod": pn(wrap, "G3", "ex_test_nw_prod"),
        "probe_expected": pn(wrap, "G3", "ex_test_nw_expected"),
        "abs_diff": ex_diff, "test_tolerance_abs": EXISTING_TEST_TOL,
        "passes": ex_diff < EXISTING_TEST_TOL}
    log("existing-test consistency gate: abs_diff=%.6g < tol=%.2f -> %s"
        % (ex_diff, EXISTING_TEST_TOL, consistency_gate["passes"]))
    result["gate_G3"] = g3
    result["gate_existing_test_consistency"] = consistency_gate

    text = build_text_corpora()
    srcinfo = build_src()
    for k in sorted(text):
        log("corpus %s: %d hit(s)" % (k, len(text[k])))
    # 合成语料（不来自文件）：A3d 的承诺文本 + A8 的 CSV 变体
    text["c_embargo"] = srcinfo["embargo_decl"] or []
    csv_orig = list(srcinfo["csv_lines"])
    csv_corrected = [re.sub(r"'net_ic'", "'cost_net_ic_rank_ic_mean'", ln) for ln in csv_orig]
    csv_netonly = [re.sub(r"row\.hac_std", "row.cost_net_ic?.hac_std", ln) for ln in csv_orig]
    csv_netonly = [re.sub(r"row\.ci_lower", "row.cost_net_ic?.ci_lower", ln) for ln in csv_netonly]
    csv_netonly = [re.sub(r"row\.ci_upper", "row.cost_net_ic?.ci_upper", ln) for ln in csv_netonly]
    text["csv_lines"] = csv_orig
    text["csv_lines_corrected"] = csv_corrected
    text["csv_lines_netonly"] = csv_netonly
    text_pristine = copy.deepcopy(text)
    text_pristine["__early_keys"] = list(srcinfo["early_keys"] or [])

    ctx = {"probe": probe, "text": text, "text_pristine": text_pristine, "src": srcinfo,
           "k": FROZEN_K, "meta": {"classes": CLASSES}}

    # ---- 行和交叉校验（K8）----
    by_axis = {ax: len(v) for ax, v in BY_AXIS.items()}
    rowsum = {"by_axis": by_axis, "frozen_axis_counts": FROZEN_AXIS_COUNTS,
              "total_variants": sum(by_axis.values()), "frozen_total": FROZEN_TOTAL,
              "declared_checkers": len(CHECKERS),
              "axis_keys_match": sorted(BY_AXIS.keys()) == sorted(FROZEN_AXIS_COUNTS.keys()),
              "variant_map_complete": all(a in VARIANT for axes in BY_AXIS.values() for a in axes)
              and len(VARIANT) == FROZEN_TOTAL,
              "variant_ids_unique": len(set(VARIANT.values())) == FROZEN_TOTAL}
    rowsum["ok"] = (rowsum["axis_keys_match"] and by_axis == FROZEN_AXIS_COUNTS
                    and sum(by_axis.values()) == FROZEN_TOTAL == len(CHECKERS)
                    and rowsum["variant_map_complete"] and rowsum["variant_ids_unique"])
    cfg_ok, cfg_detail = verify_config_frozen()
    rowsum["config_frozen_match"] = cfg_ok
    rowsum["config_frozen_detail"] = cfg_detail
    rowsum["ok"] = rowsum["ok"] and cfg_ok
    log("config.json frozen-match: %s" % cfg_ok)
    log("row-sum check: %s (by_axis=%s total=%d)" % (rowsum["ok"], by_axis, sum(by_axis.values())))
    result["variant_budget"] = rowsum

    # ---- 计数断言 ----
    counts = eval_counts(ctx)
    result["counts_C1_C9"] = counts
    for cid in ("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9"):
        log("count %s: actual=%s expected=%s match=%s"
            % (cid, counts[cid]["actual"], counts[cid]["frozen_expected"], counts[cid]["match"]))

    # ---- 金丝雀 ----
    canary_spec = build_canary(ctx)
    canary_rows = []
    for axis in CHECKERS:
        spec = canary_spec[axis]
        fn = CHECKERS[axis]
        cln_ctx = apply_op(ctx, spec["cln_op"])
        inj_ctx = apply_op(ctx, spec["inj_op"])
        cv, cev = fn(cln_ctx)
        iv, iev = fn(inj_ctx)
        ok = (cv == spec["clean"] and iv == spec["inj"] and cv != iv)
        canary_rows.append({"axis": axis, "variant": VARIANT[axis],
                            "clean_expected": spec["clean"], "clean_actual": cv,
                            "inj_expected": spec["inj"], "inj_actual": iv,
                            "passed": ok, "cln_op_kind": spec["cln_op"][0],
                            "inj_op_kind": spec["inj_op"][0], "intent": spec["intent"],
                            "clean_evidence_digest": str(cev)[:220],
                            "inj_evidence_digest": str(iev)[:220]})
        log("canary %s(%s): CLN %s/%s  INJ %s/%s -> %s"
            % (axis, VARIANT[axis], cv, spec["clean"], iv, spec["inj"],
               "PASS" if ok else "FAIL"))
    no_canary = [a for a in CHECKERS if a not in canary_spec]
    all_canary_pass = all(r["passed"] for r in canary_rows)
    result["canaries"] = {"rows": canary_rows, "checkers": len(CHECKERS),
                          "canaries": len(canary_rows), "checkers_without_canary": no_canary,
                          "harness_note": ("CLN 与 INJ 均作用于归一化上下文（两条期望值可在运行前确定）；"
                                           "成对存在且要求 clean != inj"),
                          "all_passed": all_canary_pass}

    # ---- 逐断言判定 ----
    verdicts, evidences = {}, {}
    for axis in CHECKERS:
        v, ev = CHECKERS[axis](ctx)
        verdicts[axis] = v
        evidences[axis] = ev
        log("axis %s (%s): %s" % (axis, VARIANT[axis], v))
    bad_vocab = [a for a, v in verdicts.items() if v not in VERDICT_VOCAB]
    result["axis_verdicts"] = verdicts
    result["axis_variants"] = {a: VARIANT[a] for a in CHECKERS}
    result["axis_evidence"] = evidences
    result["verdict_vocab_violations"] = bad_vocab

    # ---- 杀死条件 ----
    k_fired = {
        "K1": verdicts["A2a"] == "VIOLATED",
        "K2": verdicts["A2c"] == "VIOLATED",
        "K3": verdicts["A5c"] == "VIOLATED",
        # K4 的条件文本即「A1b 成立」，而 A1b 的判定词在 §5 冻结为 CONTRACT_MISLABEL
        # （§6 表头字面写 VIOLATED）⟹ 两者都算触发；调和写入报告附录 A
        "K4": verdicts["A1b"] in ("CONTRACT_MISLABEL", "VIOLATED"),
        "K5": (verdicts["A3c"] == "CONVENTION_DIVERGENCE")
              and (verdicts["A3d"] == "CONTRACT_MISLABEL"),
        "K6": (verdicts["A4b"] == "CONTRACT_AMBIGUOUS") or (verdicts["A2d"] == "CONTRACT_AMBIGUOUS")
              or (verdicts["A8a"] == "CONTRACT_MISLABEL"),
        "K7": any(verdicts[a] == "REGISTERED_ONLY" for a in ("A2b", "A5b", "A6a", "A6b", "A8b")),
        "K8": not rowsum["ok"],
        "K9": (not all_canary_pass) or (not gate_g3) or (not gate_reverse) or bool(no_canary)
              or (not consistency_gate["passes"]) or bool(bad_vocab),
        "K10": verdicts["A7a"] == "VIOLATED",
    }
    log("k_fired: %s" % k_fired)

    # ---- 总判定（prereg §6 冻结的按序短路规则）----
    h0_alive = not (k_fired["K1"] or k_fired["K2"] or k_fired["K3"])
    if k_fired["K8"] or k_fired["K9"] or k_fired["K10"]:
        total = "INCONCLUSIVE"
        reason = "K8/K9/K10 触发：行和校验、阳性对照或确定性未通过 ⟹ 其余断言结果作废"
    elif k_fired["K1"] or k_fired["K2"] or k_fired["K3"]:
        total = "REJECTED"
        reason = "H0 被杀：%s 触发" % "/".join([k for k in ("K1", "K2", "K3") if k_fired[k]])
    elif k_fired["K4"] or k_fired["K5"]:
        total = "CONTRACT_MISLABEL"
        reason = "H0 存活但契约指称错误：%s 触发" % "/".join([k for k in ("K4", "K5")
                                                     if k_fired[k]])
    elif k_fired["K6"]:
        refs = [a for a in ("A4b", "A2d", "A8a")
                if verdicts[a] in ("CONTRACT_AMBIGUOUS", "CONTRACT_MISLABEL")]
        total = "CONTRACT_AMBIGUOUS"
        reason = ("H0 存活但命名/文档存在歧义：K6 由 %s 触发；按 §6 规则 4 总判定写作 "
                  "CONTRACT_AMBIGUOUS（若触发源为 A8a，其断言词本身是 CONTRACT_MISLABEL）"
                  % "/".join(refs))
    else:
        total = "NOT_VIOLATED"
        reason = "K1/K2/K3 全部未触发 ⟹ H0 存活（不得叙述为「项目有问题」）"

    result["kill_conditions_fired"] = k_fired
    result["h0_alive"] = h0_alive
    result["h0_alive_definition"] = "K1 与 K2 与 K3 全部未触发（prereg §6 冻结）"
    result["total_verdict"] = total
    result["total_verdict_reason"] = reason
    result["verdict_synthesis_rule"] = ("按序短路：K8/K9/K10 -> INCONCLUSIVE；K1/K2/K3 -> REJECTED；"
                                        "K4/K5 -> CONTRACT_MISLABEL；K6 -> CONTRACT_AMBIGUOUS；"
                                        "否则 NOT_VIOLATED")
    result["finished_at"] = datetime.now().isoformat()
    log("H0 ALIVE = %s" % h0_alive)
    log("TOTAL VERDICT = %s" % total)
    log("reason: %s" % reason)

    if os.path.isdir(PROBE_OUT):
        shutil.rmtree(PROBE_OUT)
        log("cleaned compiled probe output: %s" % PROBE_OUT)

    with open(os.path.join(HERE, "result.json"), "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2, default=str)
    log("wrote result.json")


if __name__ == "__main__":
    main()
