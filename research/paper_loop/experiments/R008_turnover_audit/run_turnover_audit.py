#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R008 换手率载荷族审计（avg_turnover / max_turnover / min_turnover / daily_turnover[]）
—— 只读编排器。

判据来源：Yao & Zheng，arXiv:2606.08285v1（2026-06-06），见 rounds/R008_review.md。
判定词表、L/A/P 分档、阈值、网格、21 轴断言、杀死条件 K1–K10 在 rounds/R008_prereg.md 中
**于本文件创建之前冻结**；本文件只是冻结文本的可执行实现。

硬约束（协议 §7 + 本轮 prereg §3.1/§9）：
  * 只读：**不访问数据库**（本轮 0 条 SQL），仅读文件系统 + 在 JVM 内调用生产类。
  * 不改任何生产代码 / 测试 / 前端 / 契约 / ResearchConvention。
  * 发现一律只登记队列，不修。
  * 断言对象必须是 modules/backend-java/target/classes 下的**生产类**（门 G3）；
    探针里的 control_declared_full / control_oneway_defined / control_within_strategy
    **只作对照值**，且与被审计量同输入并列打印。
  * 两个 Spring Data 仓库用 JDK 动态代理伪造；生产代码若调用到未预期的方法，探针立即抛异常
    （防「测了个不是生产路径的东西」）。
"""
import os
import re
import copy
import json
import shutil
import subprocess
import sys
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
# 裁定 B 改名后重跑用的产物目录覆盖（默认仍是本目录，冻结产物不得被静默覆盖）：
#   R008_OUT_DIR=<dir> python run_turnover_audit.py
OUT_DIR = os.environ.get("R008_OUT_DIR", HERE)
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
PROBE_SRC = os.path.join(HERE, "probe", "R008Probe.java")
PROBE_OUT = os.path.join(HERE, "probe", "out")
CLASSES = os.path.join(REPO, "modules", "backend-java", "target", "classes")
PROBE_MAIN = "com.quant.service.backtest.R008Probe"

# 被审计的生产实现在类初始化时要 slf4j（@Slf4j），其注入的仓库接口要 Spring Data，
# 实体要 jakarta.persistence。这些 jar 只提供「能链接」的条件，不参与任何被断言的量；
# 版本严格按 modules/backend-java/pom.xml 的 spring-boot-starter-parent 3.2.0 对应的
# Spring Boot 3.2.0 BOM 钉死，缺任何一个都显式失败（不得静默降级到别的版本）。
M2_REPO = os.path.join(os.path.expanduser("~"), ".m2", "repository")
DEPS_PINNED = [
    ("org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar", "slf4j-api（@Slf4j 的 LoggerFactory）"),
    ("org/springframework/spring-core/6.1.1/spring-core-6.1.1.jar", "spring-core"),
    ("org/springframework/spring-beans/6.1.1/spring-beans-6.1.1.jar", "spring-beans"),
    ("org/springframework/spring-context/6.1.1/spring-context-6.1.1.jar", "spring-context（@Service）"),
    ("org/springframework/spring-tx/6.1.1/spring-tx-6.1.1.jar", "spring-tx（@Transactional/org.springframework.dao）"),
    ("org/springframework/spring-aop/6.1.1/spring-aop-6.1.1.jar", "spring-aop"),
    ("org/springframework/data/spring-data-commons/3.2.0/spring-data-commons-3.2.0.jar",
     "spring-data-commons（Repository/Page/Pageable）"),
    ("org/springframework/data/spring-data-jpa/3.2.0/spring-data-jpa-3.2.0.jar",
     "spring-data-jpa（JpaRepository/@Query）"),
    ("jakarta/persistence/jakarta.persistence-api/3.1.0/jakarta.persistence-api-3.1.0.jar",
     "jakarta.persistence-api（实体注解；同时消除 javac 的 GenerationType 警告）"),
]

BJ = os.path.join(REPO, "modules", "backend-java", "src")
BJ_MAIN = os.path.join(BJ, "main", "java", "com", "quant")
BJ_TEST = os.path.join(BJ, "test", "java", "com", "quant")
BTS = os.path.join(BJ_MAIN, "service", "BacktestTurnoverService.java")
BTC = os.path.join(BJ_MAIN, "controller", "BacktestTurnoverController.java")
BTS_TEST = os.path.join(BJ_TEST, "service", "BacktestTurnoverServiceTest.java")
ROLLING = os.path.join(BJ_MAIN, "research", "RollingExperimentService.java")
CSN = os.path.join(BJ_MAIN, "service", "scoring", "CrossSectionNeutralizer.java")
DATASVC = os.path.join(BJ_MAIN, "service", "DataService.java")
PRW = os.path.join(BJ_MAIN, "service", "scoring", "ProgressiveResultWriter.java")
ENTITY_ITEM = os.path.join(BJ_MAIN, "entity", "SelectorResultItem.java")
WEB_SRC = os.path.join(REPO, "modules", "web", "src")
BACKTEST_TS = os.path.join(WEB_SRC, "api", "backtest.ts")
DATA_TS = os.path.join(WEB_SRC, "api", "data.ts")
SCORING_VUE = os.path.join(WEB_SRC, "views", "backtest", "ScoringBacktest.vue")
DATA_VUE = os.path.join(WEB_SRC, "views", "data", "DataManagement.vue")
STOCK_VUE = os.path.join(WEB_SRC, "views", "market", "StockDetailView.vue")

# ---------------------------------------------------------------------------
# 冻结常数（R008_prereg.md §4 / §5 / §6 / §7 / §8）—— 运行期不得修改
# ---------------------------------------------------------------------------
TOL_BIT = 1e-12                 # 逐位一致容差（A 档）
TH_MAT = 0.10                   # 材料性阈值（P 档）；实测暴露 0.35 / 0.70
N_GRID = [8, 10, 12]            # topN ±20%（P 档）
P_GRID = [2, 3, 4]              # 短员日候选数 ±1（P 档）
RANGE_LO = 0.0                  # A7a 值域下界（A 档）
RANGE_HI = 1.0                  # A7a 值域上界（A 档）
EXISTING_TEST_TOL = 1e-12       # 与既有测试 0.3333 的一致性门（A 档）
CONTROL_ONE_WAY_FACTOR = 0.5    # Eq.(3) 双向 → 单向折算系数（A 档，仅构造对照）

VARIANT = {
    "A1a": "V01", "A1b": "V02",
    "A2a": "V03", "A2b": "V04", "A2c": "V06", "A2d": "V05",
    "A3a": "V07", "A3b": "V08", "A3c": "V09",
    "A4a": "V10", "A4b": "V11", "A4c": "V12",
    "A5a": "V13", "A5b": "V14", "A5c": "V15",
    "A6a": "V16", "A6b": "V17",
    "A7a": "V18", "A7b": "V19",
    "A8a": "V20", "A8b": "V21",
}
BY_AXIS = {
    "A1": ["A1a", "A1b"],
    "A2": ["A2a", "A2b", "A2c", "A2d"],
    "A3": ["A3a", "A3b", "A3c"],
    "A4": ["A4a", "A4b", "A4c"],
    "A5": ["A5a", "A5b", "A5c"],
    "A6": ["A6a", "A6b"],
    "A7": ["A7a", "A7b"],
    "A8": ["A8a", "A8b"],
}
FROZEN_AXIS_COUNTS = {"A1": 2, "A2": 4, "A3": 3, "A4": 3, "A5": 3, "A6": 2, "A7": 2, "A8": 2}
FROZEN_TOTAL = 21

VERDICT_VOCAB = ["NOT_VIOLATED", "VIOLATED", "CONVENTION_DIVERGENCE", "CONTRACT_AMBIGUOUS",
                 "CONTRACT_MISLABEL", "REGISTERED_ONLY", "NOT_REACHABLE", "INCONCLUSIVE"]

# 冻结的杀死条件表（prereg §6.1 的逐字副本）
FROZEN_K = [
    {"id": "K1", "condition": "A2a == VIOLATED（两侧短员且集合完全相同，载荷报 0.7 而定义值 0.0）",
     "outcome": "REJECTED", "refs": ["A2a"]},
    {"id": "K2", "condition": "A2b == VIOLATED（单侧短员，与定义值绝对偏差 0.35 >= TH_MAT = 0.10）",
     "outcome": "REJECTED", "refs": ["A2b"]},
    {"id": "K3", "condition": "A3a == VIOLATED（strategy=null 时把同日不同策略当作「相邻日」配对）",
     "outcome": "REJECTED", "refs": ["A3a"]},
    {"id": "K4", "condition": "A1b == CONTRACT_MISLABEL（声明了「两日均有 topN 个候选」而代码无 guard）",
     "outcome": "CONTRACT_MISLABEL", "refs": ["A1b"]},
    {"id": "K5", "condition": "A6a == CONTRACT_AMBIGUOUS 或 A6b == CONTRACT_AMBIGUOUS",
     "outcome": "CONTRACT_AMBIGUOUS", "refs": ["A6a", "A6b"]},
    {"id": "K6", "condition": "A3b == CONTRACT_AMBIGUOUS 或 A2c == CONTRACT_AMBIGUOUS",
     "outcome": "CONTRACT_AMBIGUOUS", "refs": ["A3b", "A2c"]},
    {"id": "K7", "condition": "A2c / A4b / A5a / A5b / A5c / A7b / A8b 中任一判定词 ∉ {NOT_VIOLATED}",
     "outcome": "REGISTERED_ONLY",
     "refs": ["A2c", "A4b", "A5a", "A5b", "A5c", "A7b", "A8b"]},
    {"id": "K8", "condition": "行和交叉校验失败（§3.5：轴数/变体映射/config.json 冻结比对任一不等）",
     "outcome": "INCONCLUSIVE", "refs": []},
    {"id": "K9", "condition": "阳性对照未通过（§3.6：G3 门 / 反向锚点 / 21 对金丝雀 / 与既有测试一致性 / 表外判定词）",
     "outcome": "INCONCLUSIVE", "refs": []},
    {"id": "K10", "condition": "A8a == VIOLATED（同一输入同一顺序两次调用不一致）",
     "outcome": "INCONCLUSIVE", "refs": ["A8a"]},
]

# 冻结的计数期望（prereg §7）——计数不匹配**只**记附录 A，不改变任何判定词
EXPECT_COUNTS = {
    "C1": {"src": 13, "raw": 17,
           "by_file": {"BacktestTurnoverService.java": 2, "DataService.java": 4,
                       "BacktestTurnoverServiceTest.java": 3, "backtest.ts": 1, "data.ts": 1,
                       "ScoringBacktest.vue": 1, "DataManagement.vue": 1},
           "note": "avg_turnover 于 modules/**"},
    "C2": {"src": 6, "raw": 8,
           "by_file": {"BacktestTurnoverService.java": 2, "BacktestTurnoverServiceTest.java": 2,
                       "backtest.ts": 1, "ScoringBacktest.vue": 1},
           "note": "daily_turnover 于 modules/**"},
    "C3": {"src": 8, "raw": 8,
           "by_file": {"ScoringBacktest.vue": 4, "DataManagement.vue": 2, "StockDetailView.vue": 1,
                       "StockPicker.vue": 1},
           "note": "换手 于 modules/web/src/**"},
    "C4": {"src": 6, "raw": 6,
           "by_file": {"BacktestTurnoverController.java": 2, "BacktestTurnoverService.java": 1,
                       "BacktestTurnoverServiceTest.java": 3},
           "note": "BacktestTurnoverService 于 modules/**"},
    "C5": {"src": 2, "raw": 2, "by_file": {"BacktestTurnoverService.java": 2},
           "note": 'put("note" 于 BacktestTurnoverService.java（:71 / :127）'},
    "C6": {"src": 21, "raw": 26, "note": "turnover_rate 于 modules/**（明细见报告 §10）"},
    "C7": {"src": 2, "raw": 4,
           "by_file": {"BacktestTurnoverController.java": 1, "backtest.ts": 1},
           "note": "backtest/turnover 于 modules/**（raw 多 2 = 构建产物）"},
    "C8": {"src": 1, "raw": 1, "by_file": {"RollingExperimentService.java": 1},
           "note": '"turnover" 于 RollingExperimentService.java（:473）'},
    "C9": {"src": 6, "raw": 11, "note": "turnover 于 **/*.sql"},
    "C10": {"src": 114, "files": 25, "note": "[Tt]urnover 于 modules/**/*.java"},
    "C11": {"src": 9, "note": "[Tt]urnover 于 modules/web/src/**（.vue/.ts）"},
}

SKIP_DIRS = {".git", "node_modules", "target", "dist", "__pycache__", ".idea", ".vscode", "out",
             ".mypy_cache", ".pytest_cache"}
BUILD_DIRS = {"target", "dist"}

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
def resolve_deps():
    """解析钉死的依赖 jar。缺任何一个返回 (None, missing, note)，由调用方显式失败。"""
    jars, missing = [], []
    for rel, why in DEPS_PINNED:
        p = os.path.join(M2_REPO, *rel.split("/"))
        if os.path.isfile(p):
            jars.append(p)
        else:
            missing.append("%s（%s）" % (rel, why))
    return jars, missing


DEP_CACHE = {"cp": "", "missing": [], "jars": []}


def dep_classpath():
    if not DEP_CACHE["cp"]:
        jars, missing = resolve_deps()
        DEP_CACHE["jars"] = jars
        DEP_CACHE["missing"] = missing
        DEP_CACHE["cp"] = os.pathsep.join(jars)
    return DEP_CACHE["cp"]


def compile_and_run_probe():
    if not os.path.isdir(CLASSES):
        log("FAIL: 生产类目录不存在：%s（需先构建 backend-java）" % CLASSES)
        return None
    dep_cp = dep_classpath()
    if DEP_CACHE["missing"]:
        for m in DEP_CACHE["missing"]:
            log("FAIL: 依赖 jar 缺失（钉死版本）：%s" % m)
        log("FAIL: 依赖解析失败 ⟹ 探针不运行（不得静默改用其它版本）")
        return None
    log("harness deps=%d jars（钉死于 Spring Boot 3.2.0 BOM），m2=%s" % (len(DEP_CACHE["jars"]), M2_REPO))
    rt_cp = os.pathsep.join([PROBE_OUT, CLASSES, dep_cp])
    cc_cp = os.pathsep.join([CLASSES, dep_cp])
    if os.path.isdir(PROBE_OUT):
        shutil.rmtree(PROBE_OUT)
    os.makedirs(PROBE_OUT, exist_ok=True)

    jc = subprocess.run(
        ["javac", "-encoding", "UTF-8", "-cp", cc_cp, "-d", PROBE_OUT, PROBE_SRC],
        capture_output=True, text=True, errors="replace")
    log("javac rc=%d" % jc.returncode)
    if jc.stdout.strip():
        log("javac stdout: " + jc.stdout.strip())
    if jc.stderr.strip():
        log("javac stderr: " + jc.stderr.strip())
    if jc.returncode != 0:
        return None

    jr = subprocess.run(
        ["java", "-Dfile.encoding=UTF-8", "-cp", rt_cp, PROBE_MAIN],
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


def close(a, b, tol=TOL_BIT):
    try:
        return abs(float(a) - float(b)) <= tol
    except (TypeError, ValueError):
        return False


# ===========================================================================
# 2) 文本语料（Python 走文件，不依赖外部 grep 是否在 PATH 上）
# ===========================================================================
def walk_lines(roots, exts, pattern=None, include_build=False, exclude_substr=()):
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


_DECL_JAVA = r"\b(?:private|public|protected)\b[^;=]*\b%s\s*\("


def method_range(lines, name):
    """按大括号配对返回**声明**体 [start, end]（0-based 闭区间）。找不到返回 None。"""
    cand = None
    for i, ln in enumerate(lines):
        if re.search(_DECL_JAVA % re.escape(name), ln):
            cand = i
            break
    if cand is None:
        return None
    i = cand
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


def put_keys(lines, a, b, receiver="out"):
    """[a,b] 行内**指定接收者**的 `<receiver>.put("key"` 键清单（保序去重）。"""
    out = []
    rx = re.compile(r'\b%s\.put\(\s*"([A-Za-z_0-9]+)"' % re.escape(receiver))
    for j in range(a, min(b + 1, len(lines))):
        for m in rx.finditer(lines[j]):
            if m.group(1) not in out:
                out.append(m.group(1))
    return out


def put_keys_any(lines, a, b):
    out = []
    rx = re.compile(r'\b[A-Za-z_][A-Za-z_0-9]*\.put\(\s*"([A-Za-z_0-9]+)"')
    for j in range(a, min(b + 1, len(lines))):
        for m in rx.finditer(lines[j]):
            if m.group(1) not in out:
                out.append(m.group(1))
    return out


EXTS_ALL = (".java", ".sql", ".vue", ".js", ".ts", ".py")


def build_text_corpora():
    mods = os.path.join(REPO, "modules")
    c = {}
    # ---- 冻结计数 C1..C11 ----
    c["c1"] = walk_lines([mods], EXTS_ALL, r"avg_turnover")
    c["c1_raw"] = walk_lines([mods], EXTS_ALL, r"avg_turnover", include_build=True)
    c["c2"] = walk_lines([mods], EXTS_ALL, r"daily_turnover")
    c["c2_raw"] = walk_lines([mods], EXTS_ALL, r"daily_turnover", include_build=True)
    c["c3"] = walk_lines([WEB_SRC], (".vue",), r"换手")
    c["c3_raw"] = walk_lines([WEB_SRC], (".vue", ".ts"), r"换手", include_build=True)
    c["c4"] = walk_lines([mods], EXTS_ALL, r"BacktestTurnoverService")
    c["c4_raw"] = walk_lines([mods], EXTS_ALL, r"BacktestTurnoverService", include_build=True)
    c["c5"] = walk_lines([BTS], (".java",), r'put\("note"')
    c["c5_raw"] = c["c5"]
    c["c6"] = walk_lines([mods], EXTS_ALL, r"turnover_rate")
    c["c6_raw"] = walk_lines([mods], EXTS_ALL, r"turnover_rate", include_build=True)
    c["c7"] = walk_lines([mods], EXTS_ALL, r"backtest/turnover")
    c["c7_raw"] = walk_lines([mods], EXTS_ALL, r"backtest/turnover", include_build=True)
    c["c8"] = walk_lines([ROLLING], (".java",), r'"turnover"')
    c["c8_raw"] = c["c8"]
    c["c9"] = walk_lines([mods], (".sql",), r"turnover")
    c["c9_raw"] = walk_lines([mods], (".sql",), r"turnover", include_build=True)
    c["c10"] = walk_lines([os.path.join(mods, "backend-java")], (".java",), r"[Tt]urnover")
    c["c10_raw"] = walk_lines([os.path.join(mods, "backend-java")], (".java",), r"[Tt]urnover",
                              include_build=True)
    c["c11"] = walk_lines([WEB_SRC], (".vue", ".ts"), r"[Tt]urnover")
    c["c11_raw"] = walk_lines([WEB_SRC], (".vue", ".ts"), r"[Tt]urnover", include_build=True)
    # ---- 检查器用语义语料 ----
    c["c_rolling_unavail"] = walk_lines([ROLLING], (".java",), r"turnover")
    c["c_bts_note"] = walk_lines([BTS], (".java",), r'put\("note"')
    # A4c 反解语料不适用（分母由探针反解）；A5c 需 BTS 的 note 两条分支
    c["c_bts_javadoc_pre"] = walk_lines([BTS], (".java",), r"两日均有")
    # A6a 四个语义
    c["c_a6a_market_sql"] = walk_lines([DATASVC], (".java",), r"avg_turnover")
    c["c_a6a_candidate_ts"] = walk_lines([BACKTEST_TS], (".ts",), r"avg_turnover")
    c["c_a6a_candidate_vue_unit"] = walk_lines([SCORING_VUE], (".vue",), r"avg_turnover.*\* 100")
    c["c_a6a_mvcap"] = walk_lines([CSN], (".java",),
                                  r"LOG_MV_FROM_TURNOVER|FLOAT_CAP_APPROX|turnover_amount")
    c["c_a6a_unavail"] = walk_lines([ROLLING], (".java",), r"turnover")
    c["c_a6a_market_unit"] = walk_lines([DATA_VUE, STOCK_VUE], (".vue",),
                                        r"avg_turnover|turnover_rate")
    # A6b 三处渲染
    c["c_a6b_market_render"] = walk_lines([STOCK_VUE], (".vue",), r"换手")
    c["c_a6b_candidate_render"] = walk_lines([SCORING_VUE], (".vue",), r"换手")
    c["c_a6b_data_render"] = walk_lines([DATA_VUE], (".vue",), r"换手")
    c["c_a6b_picker_render"] = walk_lines([os.path.join(WEB_SRC, "views")], (".vue",), r"换手")
    # A7b 单双向声明：冻结扫描范围 = 被审计面（响应/note/前端三标签）
    #   被测量的量是「对**换手**单向/双向归一约定的声明」，故命中行必须同行情含换手语汇
    #   （否则「双边佣金/双边滑点」这类成本表述会被误当作口径声明 —— 见报告附录 A-06）
    ow_pat = r"one-way|two-way|单向|双向|round-trip|双边|归一约定"
    ow_turnover_pat = r"[Tt]urnover|换手"
    c["c_ow_tw_raw_hits"] = walk_lines([BTS, BACKTEST_TS, SCORING_VUE, STOCK_VUE, DATA_VUE],
                                       (".java", ".ts", ".vue"), ow_pat)
    c["c_ow_tw"] = [ln for ln in c["c_ow_tw_raw_hits"] if re.search(ow_turnover_pat, ln)]
    c["c_ow_tw_all_modules"] = walk_lines([mods], EXTS_ALL, ow_pat)
    # 既有测试的字面期望值
    c["c_ex_test_literal"] = walk_lines([BTS_TEST], (".java",), r"0\.3333")
    return c


def build_src():
    """源码片段（供结构性断言使用；不修改任何文件）。"""
    bts = read_lines(BTS)
    s = {"bts_lines": bts}
    r = method_range(bts, "analyze")
    s["analyze_range"] = r
    if r:
        # A1b：声明的前置条件（javadoc）vs 方法体内的实际候选数守卫
        s["declared_precondition_lines"] = [i + 1 for i, ln in enumerate(bts) if "两日均有" in ln]
        guards = []
        for i in range(r[0], r[1] + 1):
            ln = bts[i]
            if re.search(r"prev_size|curr_size|candidate_count|actual_size", ln):
                guards.append(i + 1)
            elif re.search(r"\.size\(\)\s*[<>=!]", ln) and re.search(r"\bn\b", ln):
                guards.append(i + 1)
        s["analyze_guard_lines"] = guards
        s["analyze_body_key_lines_any"] = put_keys_any(bts, r[0], r[1])
        s["top_level_keys"] = put_keys(bts, r[0], r[1], receiver="out")
        # 逐日行键集（接收者 d）
        s["daily_row_keys"] = put_keys(bts, r[0], r[1], receiver="d")
        # 退化分支（signal_days < 2）的键集
        ei = [i for i in range(r[0], r[1] + 1) if "daily.size() < 2" in bts[i].replace(" ", "") or
              "size()<2" in bts[i].replace(" ", "")]
        if ei:
            e0, e1 = if_block_range(bts, ei[0])
            s["early_range"] = (e0, e1)
            s["early_keys"] = put_keys(bts, e0, e1, receiver="out")
            s["early_daily_keys"] = put_keys(bts, e0, e1, receiver="d")
            s["normal_keys"] = put_keys(bts, e1 + 1, r[1], receiver="out")
        else:
            s["early_range"] = None
            s["early_keys"] = []
            s["early_daily_keys"] = []
            s["normal_keys"] = put_keys(bts, r[0], r[1], receiver="out")
    else:
        s.update({"declared_precondition_lines": [], "analyze_guard_lines": [],
                  "analyze_body_key_lines_any": [], "top_level_keys": [], "daily_row_keys": [],
                  "early_range": None, "early_keys": [], "early_daily_keys": [], "normal_keys": []})
    # A3c：源码是否自证「取最新」（createdAt 比较）
    s["createdat_lines"] = [i + 1 for i, ln in enumerate(bts) if "getCreatedAt" in ln]
    s["createdat_compare_lines"] = [i + 1 for i, ln in enumerate(bts)
                                    if "getCreatedAt" in ln and re.search(r"[<>]=?|compareTo|isAfter|isBefore", ln)]
    # 实体：rankNo 唯一约束
    item = read_lines(ENTITY_ITEM)
    s["rankno_unique_constraint_lines"] = [i + 1 for i, ln in enumerate(item)
                                           if "uniqueConstraints" in ln and "rankNo" in ln]
    s["rankno_column_lines"] = [i + 1 for i, ln in enumerate(item) if "rankNo" in ln]
    prw = read_lines(PRW)
    s["prw_rank_lines"] = [i + 1 for i, ln in enumerate(prw) if re.search(r"setRankNo|rank\+\+|rank\s*=", ln)]
    # 既有测试的输入（:51-71）
    try:
        s["bts_test_lines"] = read_lines(BTS_TEST)
    except OSError:
        s["bts_test_lines"] = []
    return s


# ===========================================================================
# 3) 检查器注册表（21 个，与 VARIANT 一一对应）
#    L = 判据来源明示（只支持做法种类）；A = 算术推导；P = 项目自建（含一切数值阈值）
# ===========================================================================
def ck_A1a(ctx):                                                     # L+A  NOT_VIOLATED
    """满员路径：生产值与声明口径 (1 − |I|/n) 逐位一致，且聚合值与逐日值自洽。"""
    is_ = ind(ctx, "V01", "abs_diff")
    rows = []
    for i in is_:
        t = pn(ctx, "V01", "turnover", i)
        ctrl = pn(ctx, "V01", "control_declared", i)
        rows.append({"pair": i, "date": pv(ctx, "V01", "date", i), "turnover": t,
                     "new_count": pv(ctx, "V01", "new_count", i),
                     "exit_count": pv(ctx, "V01", "exit_count", i),
                     "intersection": pv(ctx, "V01", "intersection", i),
                     "control_1_minus_k_over_n": ctrl,
                     "abs_diff": abs(t - ctrl),
                     "abs_diff_emitted": pn(ctx, "V01", "abs_diff", i),
                     "recomputed_matches_emitted": close(abs(t - ctrl),
                                                         pn(ctx, "V01", "abs_diff", i), 1e-15)})
    agg = {}
    for k in ("avg", "max", "min"):
        agg[k] = {"prod": pn(ctx, "V01", k + "_prod"),
                  "ctrl_from_daily": pn(ctx, "V01", k + "_ctrl_from_daily"),
                  "abs_diff": abs(pn(ctx, "V01", k + "_prod") - pn(ctx, "V01", k + "_ctrl_from_daily")),
                  "abs_diff_emitted": pn(ctx, "V01", k + "_abs_diff")}
    panelB = {"turnover": pn(ctx, "V01", "panelB_turnover"),
              "control": pn(ctx, "V01", "panelB_control"),
              "abs_diff": abs(pn(ctx, "V01", "panelB_turnover") - pn(ctx, "V01", "panelB_control")),
              "keys": pv(ctx, "V01", "panelB_keys")}
    pair_bad = [r for r in rows if r["abs_diff"] > TOL_BIT]
    agg_bad = [k for k, v in agg.items() if v["abs_diff"] > TOL_BIT]
    # 面板设计自检（仪器检查，不改动冻结判定规则）：冻结文本要求 3 日全满员面板（每日 10 条）
    # 且冻结输入面板的 |I| = 9。若交集不是 {9,8,10,10}，说明测的不是冻结的那个面板。
    isect = [r["intersection"] for r in rows]
    design = {"frozen_panelM": "3 日全满员（每日 10 条）⟹ 2 个 pair，|I| 期望 [9, 8]",
              "panelM_intersections": isect,
              "panelM_matches": isect == [9, 8],
              "panelB_frozen_input": "d1 = S01..S10；d2 = S01..S09 + S11 ⟹ |I| = 9",
              "panelB_intersection": pv(ctx, "V01", "panelB_intersection"),
              "panelB_matches": pv(ctx, "V01", "panelB_intersection") == 9}
    ok = bool(rows) and not pair_bad and not agg_bad and panelB["abs_diff"] <= TOL_BIT
    ev = {"tolerance_bit": TOL_BIT, "pairs": rows, "aggregates": agg,
          "panelB_declared_input_absI_9": panelB,
          "panel_design_selfcheck": design,
          "pair_abs_diff_max": max([r["abs_diff"] for r in rows] or [None]),
          "aggregate_abs_diff_max": max([v["abs_diff"] for v in agg.values()] or [None]),
          "reading": ("满员路径上生产值 == 1 − |I|/n（偏差只来自 round4 的 1e-4 量化前的双精度差，"
                      "量级 1e-17）；avg/max/min 与逐日值自洽；"
                      "该轴问的是「声明口径是否被忠实实现」，不涉及短员路径")}
    return ("NOT_VIOLATED" if ok else "VIOLATED"), ev


def ck_A1b(ctx):                                                     # L+P  CONTRACT_MISLABEL
    """javadoc 声明的「两日均有 topN 个候选」前置条件是否有代码守卫。"""
    declared = ctx["src"]["declared_precondition_lines"] or []
    guards = ctx["src"]["analyze_guard_lines"] or []
    ev = {"declared_precondition_lines": declared,
          "declared_text": [ctx["src"]["bts_lines"][i - 1].strip() for i in declared],
          "guard_lines": guards,
          "guard_text": [ctx["src"]["bts_lines"][i - 1].strip() for i in guards],
          "analyze_range": ctx["src"]["analyze_range"],
          "early_branch_keys": ctx["src"]["early_keys"] or [],
          "daily_row_keys": ctx["src"]["daily_row_keys"] or [],
          "top_level_keys": ctx["src"]["top_level_keys"] or [],
          "early_branch_has_size_key": any(k in (ctx["src"]["early_keys"] or [])
                                           for k in ("prev_size", "curr_size", "candidate_count")),
          "dividend_line": "BacktestTurnoverService:103  double turnover = n > 0 ? "
                           "1.0 - (double) intersection.size() / n : 0.0;",
          "reading": ("javadoc 声明前置条件「两日均有 topN 个候选」，方法体内没有任何把实际候选数"
                      "与 n 比较的守卫，也没有任何 size 字段 ⟹ 声明了未被强制的前置条件")}
    if declared and not guards:
        return "CONTRACT_MISLABEL", ev
    ev["disposition"] = "声明为空或存在守卫 ⟹ 不构成契约指称错误"
    return "NOT_VIOLATED", ev


def ck_A2a(ctx):                                                     # L+A  VIOLATED
    """两侧短员且集合完全相同：载荷报 0.7，定义值 0.0。"""
    rep = pn(ctx, "V03", "reported")
    nc = pv(ctx, "V03", "new_count")
    ec = pv(ctx, "V03", "exit_count")
    dfn = pn(ctx, "V03", "defined")
    ev = {"top_n": pv(ctx, "V03", "top_n"), "prev_size": pv(ctx, "V03", "prev_size"),
          "curr_size": pv(ctx, "V03", "curr_size"), "intersection": pv(ctx, "V03", "intersection"),
          "reported": rep, "defined_oneway_p_c_k": dfn, "abs_diff": abs(rep - dfn),
          "new_count": nc, "exit_count": ec,
          "avg_max_min": [pn(ctx, "V03", "avg_turnover"), pn(ctx, "V03", "max_turnover"),
                          pn(ctx, "V03", "min_turnover")],
          "reading": ("两日候选集合完全相同、new/exit 都是 0（自认无替换），载荷仍报 0.7 = 1 − 3/10；"
                      "定义值（换手率定义在实际持仓权重上）在等权下为 0.0 ⟹ 实数来自分母是**请求参数**")}
    ok = rep > 0 and nc == 0 and ec == 0 and dfn == 0.0
    return ("VIOLATED" if ok else "NOT_VIOLATED"), ev


def ck_A2b(ctx):                                                     # L+A；阈值 P  VIOLATED
    """单侧短员：生产 0.7 vs 定义 0.35。"""
    rep = pn(ctx, "V04", "reported")
    dfn = pn(ctx, "V04", "defined")
    d = abs(rep - dfn)
    ev = {"top_n": pv(ctx, "V04", "top_n"), "p": pv(ctx, "V04", "p"), "c": pv(ctx, "V04", "c"),
          "intersection": pv(ctx, "V04", "intersection"), "reported": rep,
          "defined_oneway_p_c_k": dfn, "abs_diff": d, "abs_diff_emitted": pn(ctx, "V04", "abs_diff"),
          "threshold_TH_MAT": TH_MAT, "threshold_magnitude_line": "实测 0.35 vs 阈值 0.10",
          "reading": ("实际候选数 3 < topN 10；载荷把分母钉在 10，定义值按实际规模 3/10 折算 ⟹ "
                      "偏差 0.35，远超阈值")}
    return ("VIOLATED" if d >= TH_MAT else "REGISTERED_ONLY"), ev


def ck_A2c(ctx):                                                     # L+P  REGISTERED_ONLY
    """定义不适用（signal_days < 2）时输出的 0.0 是否有机器可读标记。"""
    ev = {"one_signal_days": pv(ctx, "V06", "one_signal_days"),
          "one_avg_max_min": [pn(ctx, "V06", "one_avg_turnover"), pn(ctx, "V06", "one_max_turnover"),
                              pn(ctx, "V06", "one_min_turnover")],
          "one_daily_len": pv(ctx, "V06", "one_daily_len"),
          "one_keys": pv(ctx, "V06", "one_keys"), "one_note": pv(ctx, "V06", "one_note"),
          "one_has_status_key": pv(ctx, "V06", "one_has_status_key"),
          "zero_signal_days": pv(ctx, "V06", "zero_signal_days"),
          "zero_avg_turnover": pn(ctx, "V06", "zero_avg_turnover"),
          "zero_daily_len": pv(ctx, "V06", "zero_daily_len"),
          "zero_keys": pv(ctx, "V06", "zero_keys"),
          "zero_strategy_echo": pv(ctx, "V06", "zero_strategy_echo"),
          "has_status_key": pv(ctx, "V06", "has_status_key"),
          "signal_days_max": pv(ctx, "V06", "signal_days_max"),
          "daily_len_min": pv(ctx, "V06", "daily_len_min"),
          "marker_key_scan": "status / available / basis* / *scope* / *definition*",
          "reading": ("退化分支输出 avg/max/min = 0.0 + 空数组 + 人类可读 note，**无**任何机器可读的"
                      "不可用标记键；但 signal_days < 2 与空 daily_turnover 联合可推出「0.0 是占位」")}
    if pv(ctx, "V06", "has_status_key") == 1:
        return "NOT_VIOLATED", ev
    if (pn(ctx, "V06", "signal_days_max") < 2) and (pv(ctx, "V06", "daily_len_min") == 0):
        return "REGISTERED_ONLY", ev
    return "CONTRACT_AMBIGUOUS", ev


def ck_A2d(ctx):                                                     # L+A；阈值 P  VIOLATED
    """参数敏感性网格 18 格：短员日上生产值与定义值的绝对偏差。"""
    is_ = ind(ctx, "V05", "err")
    cells = []
    for i in is_:
        rep = pn(ctx, "V05", "reported", i)
        dfn = pn(ctx, "V05", "defined", i)
        cells.append({"cell": i, "kind": pv(ctx, "V05", "kind", i), "n": pv(ctx, "V05", "n", i),
                      "p": pv(ctx, "V05", "p", i), "c": pv(ctx, "V05", "c", i),
                      "intersection": pv(ctx, "V05", "inter", i),
                      "reported": rep, "defined": dfn, "err": abs(rep - dfn),
                      "err_emitted": pn(ctx, "V05", "err", i)})
    hits = [c for c in cells if c["err"] >= TH_MAT]
    ev = {"threshold_TH_MAT": TH_MAT, "grid_n": N_GRID, "grid_p": P_GRID,
          "cells": cells, "n_cells": len(cells),
          "cells_over_threshold": len(hits),
          "min_err": min([c["err"] for c in cells] or [None]),
          "max_err": max([c["err"] for c in cells] or [None]),
          "err_by_kind": {k: sorted({round(c["err"], 6) for c in cells if c["kind"] == k})
                          for k in ("both", "one")},
          "reading": ("±20% 的 topN 与 p/c 网格上，偏差只由「分母是请求参数」决定，对 n 的取值敏感度"
                      "远低于对短员规模的敏感度；双侧短员的偏差 = 1 − p/n，单侧 = (n−c)/(2n)")}
    return ("VIOLATED" if hits else "REGISTERED_ONLY"), ev


def ck_A3a(ctx):                                                     # L+A  VIOLATED
    """strategy=null（默认路径）时，被配对的是否同一策略的相邻信号日。"""
    sd = pn(ctx, "V07", "signal_days")
    dd = pv(ctx, "V07", "distinct_dates")
    adj = pv(ctx, "V07", "adjacent_same_date")
    avg = pn(ctx, "V07", "avg_turnover")
    ctrl = pn(ctx, "V07", "within_control")
    ev = {"signal_days": sd, "distinct_dates_in_window": dd, "daily_len": pv(ctx, "V07", "daily_len"),
          "strategy_echo": pv(ctx, "V07", "strategy_echo"),
          "pair_dates": pv(ctx, "V07", "pair_dates"),
          "pair_turnovers": [pn(ctx, "V07", "pair_turnover", i) for i in ind(ctx, "V07", "pair_turnover")],
          "adjacent_same_date": adj, "avg_turnover": avg,
          "control_within_strategy": ctrl,
          "controller_default": "BacktestTurnoverController: @RequestParam(required=false) String strategy "
                                "⟹ 默认路径 strategy=null",
          "reading": ("4 条 SelectorResult = 2 策略 × 2 日；载荷把「同一天的两个不同策略」当成相邻项配对，"
                      "len(daily) = 策略数×日数 − 1 = 3，而窗口内只有 2 个日期；"
                      "严格按同策略相邻日配对时各策略两日集合完全相同 ⟹ 换手率 0.0")}
    ok = (sd > dd) and (adj == 1) and (not close(avg, ctrl, 1e-9))
    return ("VIOLATED" if ok else "NOT_VIOLATED"), ev


def ck_A3b(ctx):                                                     # P  CONTRACT_AMBIGUOUS
    """strategy = null 与 strategy = "" 的两次计算是否相同、回显是否不同。"""
    same = pv(ctx, "V08", "turnovers_identical")
    en = pv(ctx, "V08", "echo_null")
    eb = pv(ctx, "V08", "echo_blank")
    ev = {"echo_null": en, "echo_blank": eb, "turnovers_identical": same,
          "turnover_first_null": pn(ctx, "V08", "turnover_first_null"),
          "turnover_first_blank": pn(ctx, "V08", "turnover_first_blank"),
          "signal_days_null": pv(ctx, "V08", "signal_days_null"),
          "signal_days_blank": pv(ctx, "V08", "signal_days_blank"),
          "source_conditions": "过滤 :46 strategy != null && !strategy.isBlank()；"
                               "回显 :60 strategy != null ? strategy : \"ALL\"",
          "reading": ("两者进入同一条混合策略路径（isBlank 与 null 都不过滤）⟹ 计算逐位相同；"
                      "但回显分别是 \"ALL\" 与 空串 ⟹ 读者无法从载荷判断这两次调用是同一口径还是不同口径")}
    if same == 1 and str(en) != str(eb):
        return "CONTRACT_AMBIGUOUS", ev
    if same == 0:
        return "VIOLATED", ev
    return "NOT_VIOLATED", ev


def ck_A3c(ctx):                                                     # P  NOT_REACHABLE
    """「取最新一条」是否由代码自证（createdAt 比较）。"""
    cmp_lines = ctx["src"]["createdat_compare_lines"] or []
    all_lines = ctx["src"]["createdat_lines"] or []
    ev = {"createdat_any_mention_lines": all_lines, "createdat_compare_lines": cmp_lines,
          "chosen_result_id": pv(ctx, "V09", "chosen_result_id"),
          "expected_if_latest": pv(ctx, "V09", "expected_if_latest"),
          "n_results_same_strategy_same_date": pv(ctx, "V09", "n_results_same_strategy_same_date"),
          "turnover": pn(ctx, "V09", "turnover"),
          "query_name": "findByTradeDateBetweenOrderByCreatedAtDesc",
          "reading": ("「同策略同日取最新」完全依赖查询方法名里的 ORDER BY createdAt DESC，"
                      "代码自身不做任何 createdAt 比较 ⟹ 载荷无法自证；"
                      "并列 createdAt 的取舍在无 DB 的本机不可构造，故该轴不可测")}
    return ("NOT_VIOLATED" if cmp_lines else "NOT_REACHABLE"), ev


def ck_A4a(ctx):                                                     # A  NOT_VIOLATED
    """满员日等权下的载荷内恒等式 turnover × n = (new + exit)/2（由生产载荷字段重算）。"""
    is_ = ind(ctx, "V10", "top_n")
    rows = []
    for i in is_:
        t = pn(ctx, "V10", "turnover", i)
        n = pn(ctx, "V10", "top_n", i)
        nc = pn(ctx, "V10", "new_count", i)
        ec = pn(ctx, "V10", "exit_count", i)
        lhs = t * n
        rhs = (nc + ec) / 2.0
        rows.append({"pair": i, "date": pv(ctx, "V10", "date", i), "turnover": t, "n": n,
                     "new_count": nc, "exit_count": ec, "lhs": lhs, "rhs": rhs,
                     "gap_recomputed": abs(lhs - rhs),
                     "gap_emitted": pn(ctx, "V10", "gap", i),
                     "emitted_consistent": close(abs(lhs - rhs), pn(ctx, "V10", "gap", i), 1e-15)})
    bad = [r for r in rows if r["gap_recomputed"] > TOL_BIT]
    isect = [r["n"] - r["new_count"] for r in rows]
    turns = [r["turnover"] for r in rows]
    ev = {"tolerance_bit": TOL_BIT, "pairs": rows,
          "max_gap_recomputed": max([r["gap_recomputed"] for r in rows] or [None]),
          "identity": "满员等权下 单向换手率 = (new + exit)/(2n) ⟹ turnover × n = (new + exit)/2",
          "panel_design_selfcheck": {
              "frozen_requirement": "全满员面板，每个 pair 的 |I| ∈ {7,8,9} ⟹ turnover ∈ {0.1,0.2,0.3}",
              "pair_intersections": isect, "pair_turnovers": turns,
              "intersections_in_frozen_set": all(x in (7, 8, 9) for x in isect),
              "turnovers_in_frozen_set": all(round(t, 4) in (0.1, 0.2, 0.3) for t in turns)},
          "judged_on": "由生产载荷字段 (turnover, top_n, new_count, exit_count) 在编排侧重算的 gap；"
                       "探针自报的 gap 仅作并列交叉核对",
          "reading": ("满员路径上恒等式逐位成立（|I| ∈ {7,8,9} ⟹ turnover ∈ {0.1,0.2,0.3}）"
                      "（A 档纯算术推论）")}
    return ("NOT_VIOLATED" if (rows and not bad) else "VIOLATED"), ev


def ck_A4b(ctx):                                                     # A+L  VIOLATED
    """短员日上恒等式破裂，且行键集不含候选规模字段。"""
    t = pn(ctx, "V11", "turnover")
    n = pn(ctx, "V11", "top_n")
    nc = pn(ctx, "V11", "new_count")
    ec = pn(ctx, "V11", "exit_count")
    gap = abs(t * n - (nc + ec) / 2.0)
    hsf = pv(ctx, "V11", "has_size_field")
    ev = {"turnover": t, "top_n": n, "new_count": nc, "exit_count": ec,
          "lhs_turnover_x_n": t * n, "rhs_new_plus_exit_over_2": (nc + ec) / 2.0,
          "gap_recomputed": gap, "gap_emitted": pn(ctx, "V11", "gap"),
          "emitted_consistent": close(gap, pn(ctx, "V11", "gap"), 1e-15),
          "generic_tolerance": TOL_BIT, "has_size_field": hsf,
          "daily_keys": pv(ctx, "V11", "daily_keys"),
          "size_key_candidates": ["prev_size", "curr_size", "candidate_count", "prev_n", "curr_n"],
          "reading": ("同一面板在短员日下 lhs = 7.0、rhs = 0.0（new/exit 都是 0）⟹ 恒等式破裂；"
                      "逐日行的 4 键不含任何候选规模字段 ⟹ 读者无法从载荷察觉分母不是当日集合大小")}
    if gap > TOL_BIT and hsf == 0:
        return "VIOLATED", ev
    if hsf == 1:
        return "REGISTERED_ONLY", ev
    return "NOT_VIOLATED", ev


def ck_A4c(ctx):                                                     # A+P  NOT_VIOLATED
    """分母反解 n_recovered = |I|/(1−turnover) 是否等于回显 top_n。"""
    is_ = ind(ctx, "V12", "n_recovered")
    cells = []
    for i in is_:
        cap = pv(ctx, "V12", "exact_capable", i)
        cells.append({"cell": i, "input_topN": pv(ctx, "V12", "input_topN", i),
                      "echo_top_n": pn(ctx, "V12", "echo_top_n", i),
                      "reported": pn(ctx, "V12", "reported", i),
                      "intersection": pn(ctx, "V12", "intersection", i),
                      "n_recovered": pn(ctx, "V12", "n_recovered", i),
                      "n_rec_absdiff": pn(ctx, "V12", "n_rec_absdiff", i),
                      "n_recovered_frozen_literal": pn(ctx, "V12", "n_recovered_frozen_literal", i),
                      "echo_match": pv(ctx, "V12", "echo_match", i),
                      "exact_capable": cap})
    judged = [c for c in cells if c["exact_capable"] == 1]
    desc = [c for c in cells if c["exact_capable"] != 1]
    bad = [c for c in judged if c["echo_match"] != 1 or c["n_rec_absdiff"] > TOL_BIT]
    ev = {"formula_used": "n_recovered = |I| / (1 − reported)，|I| = 1（面板 prev={A,B,C,D}, curr={A,E,F,G}）",
          "prereg_literal_formula": ("prereg §4 A4c 字面写 `n_recovered = 4/(1−0.75) = 16`，分子用了"
                                     "「集合大小 k=4」；算术上应为 |I| = 1 ⟹ 见附录 A-03"),
          "frozen_literal_values": [c["n_recovered_frozen_literal"] for c in cells],
          "tolerance_bit": TOL_BIT,
          "judged_cells": judged, "descriptive_cells_excluded": desc,
          "max_absdiff_judged": max([c["n_rec_absdiff"] for c in judged] or [None]),
          "excluded_reason": ("topN=3 时 |I|/n = 1/3 在 round4 的 1e-4 量化下无法精确反解 ⟹ "
                              "该格只作描述（附录 A-04），不参与判定"),
          "reading": ("null/0/-5 被钳位到 10 并回显 top_n = 10；反解出的分母与回显逐位一致 ⟹ "
                      "分母对读者透明")}
    return ("NOT_VIOLATED" if (judged and not bad) else "VIOLATED"), ev


def ck_A5a(ctx):                                                     # L  REGISTERED_ONLY
    """逐日行是否含重建分母所需的规模字段。"""
    missing = str(pv(ctx, "V13", "missing_keys") or "")
    ev = {"daily_keys": pv(ctx, "V13", "daily_keys"),
          "needed_keys": pv(ctx, "V13", "needed_keys"),
          "missing_keys": missing,
          "top_keys": pv(ctx, "V13", "top_keys"),
          "source_daily_row_keys": ctx["src"]["daily_row_keys"],
          "early_branch_daily_keys": ctx["src"]["early_daily_keys"],
          "reading": ("逐日行只有 trade_date / turnover / new_count / exit_count 四键；"
                      "缺少 prev_size / curr_size ⟹ 读者只能反解出分母（A4c），无法直接看到两日实际规模")}
    return ("NOT_VIOLATED" if missing == "" else "REGISTERED_ONLY"), ev


def ck_A5b(ctx):                                                     # L  REGISTERED_ONLY
    """是否存在结构化口径字段（而非仅人类自由文本）。"""
    hsb = pv(ctx, "V14", "has_structured_basis")
    ev = {"top_keys": pv(ctx, "V14", "top_keys"), "has_structured_basis": hsb,
          "note_len": pv(ctx, "V14", "note_len"),
          "source_top_level_keys": ctx["src"]["top_level_keys"],
          "source_early_branch_keys": ctx["src"]["early_keys"],
          "pattern": "basis|definition|metric_scope|convention|one_way|round_trip|口径",
          "reading": ("载荷只有 note 一段人类自由文本（声明幸存者偏差），"
                      "没有任何机器可读的口径/单位/方向字段 ⟹ 「口径须声明到可判断」的要求"
                      "只被部分满足（mention 但 implementation incomplete）")}
    return ("NOT_VIOLATED" if hsb == 1 else "REGISTERED_ONLY"), ev


def ck_A5c(ctx):                                                     # L+P  CONVENTION_DIVERGENCE
    """两处对「无持仓序列时换手率可否评估」的结论是否相反。"""
    rolling = ctx["text"]["c_rolling_unavail"]
    bts_note = ctx["text"]["c_bts_note"]
    rolling_unavail = [ln for ln in rolling if "UNAVAILABLE" in ln]
    rolling_reason = [ln for ln in rolling if "turnover_note" in ln or "组合执行口径" in ln]
    # BTS 侧：在**没有**任何持仓序列入参的方法上给出数值换手率（取 A2a/A4b 面板的生产载荷值）
    bts_numeric = pn(ctx, "V11", "turnover")
    bts_note_text = [ln.split(":", 2)[-1].strip() for ln in bts_note]
    bts_unavail_marker = [ln for ln in bts_note if "UNAVAILABLE" in ln]
    ev = {"rolling_unavailable_lines": rolling_unavail,
          "rolling_reason_lines": rolling_reason,
          "bts_note_lines": bts_note,
          "bts_note_text": bts_note_text,
          "bts_any_unavailable_marker": bts_unavail_marker,
          "analyze_signature": "analyze(String strategy, LocalDate startDate, LocalDate endDate, "
                               "Integer topN) —— 无任何持仓/组合入参",
          "bts_reports_numeric_turnover": bts_numeric,
          "reading": ("RollingExperimentService 明确写「换手需组合执行口径（MP-020/021）才可评估，"
                      "本研究不产生持仓序列」并输出 UNAVAILABLE；"
                      "BacktestTurnoverService 在同样没有持仓序列的前提下输出数值换手率 ⟹ "
                      "项目内两处惯例结论相反（两处各自都非「错」，只是口径不同）")}
    ok = bool(rolling_unavail) and bool(rolling_reason) and bool(bts_note) \
        and bts_numeric > 0 and not bts_unavail_marker
    return ("CONVENTION_DIVERGENCE" if ok else "NOT_VIOLATED"), ev


def ck_A6a(ctx):                                                     # P  CONTRACT_AMBIGUOUS
    """同名 avg_turnover 是否指代不同单位/不同量。"""
    g1 = ctx["text"]["c_a6a_market_sql"]
    g2 = ctx["text"]["c_a6a_candidate_ts"]
    g3 = ctx["text"]["c_a6a_mvcap"]
    g4 = ctx["text"]["c_a6a_unavail"]
    u_frac = ctx["text"]["c_a6a_candidate_vue_unit"]
    u_mkt = ctx["text"]["c_a6a_market_unit"]
    n_groups = sum(1 for g in (g1, g2, g3, g4) if g)
    ev = {"semantic_groups": {
              "market_turnover_sql": g1, "candidate_replacement_rate_ts": g2,
              "log_mv_from_turnover": g3, "rolling_unavailable": g4},
          "n_semantic_groups_present": n_groups,
          "unit_fraction_hits": u_frac, "unit_percent_hits": u_mkt,
          "turnover_java_hits_C10": ctx["counts"]["C10"]["actual"],
          "turnover_java_files_C10": ctx["counts"]["C10"].get("files"),
          "reading": ("同名 avg_turnover 至少两种量：DataService 的 SQL AVG(turnover_rate)（市场换手率，%）"
                      "与 TurnoverResponse.avg_turnover（候选替换率，分数，前端 ×100）；"
                      "另有 CrossSectionNeutralizer 把 turnover_amount 当市值推算来源")}
    ok = n_groups >= 2 and bool(u_frac) and bool(u_mkt)
    return ("CONTRACT_AMBIGUOUS" if ok else "NOT_VIOLATED"), ev


def ck_A6b(ctx):                                                     # P  CONTRACT_AMBIGUOUS
    """前端多处渲染「换手」是否指代不同量。"""
    g_mkt = ctx["text"]["c_a6b_market_render"]
    g_cand = ctx["text"]["c_a6b_candidate_render"]
    g_data = ctx["text"]["c_a6b_data_render"]
    picker = ctx["text"]["c_a6b_picker_render"]
    ev = {"market_render_lines": g_mkt, "candidate_render_lines": g_cand,
          "data_render_lines": g_data, "all_web_huanshou_hits": picker,
          "reading": ("StockDetailView 的「换手率」= 个股市场换手率 turnover_rate（%）；"
                      "ScoringBacktest 的「平均换手/最高/最低」= 候选替换率 avg_turnover（分数 ×100）；"
                      "DataManagement 的「换手率」= 市场换手率 ⟹ 同一中文标签指代两种量")}
    ok = bool(g_cand) and (bool(g_mkt) or bool(g_data))
    return ("CONTRACT_AMBIGUOUS" if ok else "NOT_VIOLATED"), ev


def ck_A7a(ctx):                                                     # A+P  NOT_VIOLATED
    """全部变体返回过的每一个换手率值的值域 / NaN / round4 保序。"""
    is_ = ind(ctx, "V18", "value")
    vals = []
    for i in is_:
        vals.append({"i": i, "src": pv(ctx, "V18", "src", i), "value": pn(ctx, "V18", "value", i),
                     "in_range": pv(ctx, "V18", "in_range", i), "is_nan": pv(ctx, "V18", "is_nan", i),
                     "is_inf": pv(ctx, "V18", "is_inf", i)})
    oob = [v for v in vals if v["in_range"] != 1]
    nan = [v for v in vals if v["is_nan"] == 1]
    inf = [v for v in vals if v["is_inf"] == 1]
    mono = pv(ctx, "V18", "round4_monotone_on_0_1")
    ev = {"range": [RANGE_LO, RANGE_HI], "n_values": len(vals),
          "min_value": pn(ctx, "V18", "min_value"), "max_value": pn(ctx, "V18", "max_value"),
          "out_of_range_values": oob, "out_of_range_count": pv(ctx, "V18", "out_of_range"),
          "nan_values": nan, "nan_count": pv(ctx, "V18", "nan_count"), "inf_values": inf,
          "round4_monotone_on_0_1": mono, "round4_grid_values": pv(ctx, "V18", "round4_grid_values"),
          "sources_covered": sorted({v["src"].split(".")[0] for v in vals}),
          "reading": ("值域由构造保证（|I|/n ∈ [0,1]），但 round4 的 NaN→0.0 / ±Inf→饱和 是"
                      "实测项：本轮全部取值实测落在 [0,1]、无 NaN/±Inf、round4 在 0..1 网格上保序")}
    ok = bool(vals) and not oob and not nan and not inf and mono == 1
    return ("NOT_VIOLATED" if ok else "VIOLATED"), ev


def ck_A7b(ctx):                                                     # L+P  REGISTERED_ONLY
    """是否存在对单向/双向归一约定的声明。"""
    hits = ctx["text"]["c_ow_tw"]
    raw_hits = ctx["text"]["c_ow_tw_raw_hits"]
    wide = ctx["text"]["c_ow_tw_all_modules"]
    ev = {"audited_surface_hits": hits,
          "audited_surface_raw_hits_before_cooccurrence_filter": raw_hits,
          "cooccurrence_requirement": "命中行须同行情含 [Tt]urnover|换手（被测量的是「对换手的单向/双向声明」）",
          "scan_scope": "被审计面：BacktestTurnoverService.java + backtest.ts + 三个前端标签",
          "pattern": "one-way|two-way|单向|双向|round-trip|双边|归一约定",
          "modules_wide_hits_disclosed": wide,
          "prereg_criterion_has_no_convention": ("判据来源（arXiv:2606.08285）亦未给出单向/双向的"
                                                 "归一约定 ⟹ 按 prereg 只登记，不判死"),
          "value_relation": ("载荷的 1 − |I|/n 是**单向**替换率；判据来源 Eq.(3) 的 Σ|Δw| 是**双向**；"
                             "两者相差 2 倍，而载荷与文档都没有声明采用哪一种")}
    return ("REGISTERED_ONLY" if not hits else "NOT_VIOLATED"), ev


def ck_A8a(ctx):                                                     # P  NOT_VIOLATED
    """同一面板、两个独立代理实例，两次调用的确定性。"""
    ident = pv(ctx, "V20", "identical")
    ev = {"identical": ident, "run1_avg": pn(ctx, "V20", "run1_avg"),
          "run2_avg": pn(ctx, "V20", "run2_avg"),
          "run1_keys": pv(ctx, "V20", "run1_keys"), "run2_keys": pv(ctx, "V20", "run2_keys"),
          "reading": "无随机性、无时钟依赖 ⟹ 逐位可复现"}
    return ("NOT_VIOLATED" if ident == 1 else "VIOLATED"), ev


def ck_A8b(ctx):                                                     # P  VIOLATED
    """并列 rankNo 下 .limit(n) 的成员依赖仓库返回顺序。"""
    changed = pv(ctx, "V21", "changed")
    ev = {"turnover_order_XYZ": pn(ctx, "V21", "turnover_a"),
          "turnover_order_ZYX": pn(ctx, "V21", "turnover_b"),
          "changed": changed, "tie_rank": pv(ctx, "V21", "tie_rank"),
          "tie_members": pv(ctx, "V21", "tie_members"), "note": pv(ctx, "V21", "note"),
          "schema_fact": {"rankno_column_lines": ctx["src"]["rankno_column_lines"],
                          "unique_constraint_on_rankno_lines": ctx["src"]["rankno_unique_constraint_lines"],
                          "writer_rank_lines": ctx["src"]["prw_rank_lines"]},
          "reachability_caveat": ("ProgressiveResultWriter 赋唯一 rank=1..N；其它插入路径"
                                  "（OversaveReboundScoringService / UnifiedScoringService / "
                                  "saveItemIfAbsent）是否产生并列 rankNo **本轮不判定** ⟹ "
                                  "若 changed，须同时登记「可达性未定」"),
          "reading": ("同 rankNo 三只证券的相对顺序由查询返回顺序决定，`.limit(n)` 取到的成员随之改变，"
                      "换手率从 0.0 变为 0.1 ⟹ 载荷取决于数据库未钉住的行序")}
    return ("VIOLATED" if changed == 1 else "NOT_VIOLATED"), ev


CHECKERS = {
    "A1a": ck_A1a, "A1b": ck_A1b,
    "A2a": ck_A2a, "A2b": ck_A2b, "A2c": ck_A2c, "A2d": ck_A2d,
    "A3a": ck_A3a, "A3b": ck_A3b, "A3c": ck_A3c,
    "A4a": ck_A4a, "A4b": ck_A4b, "A4c": ck_A4c,
    "A5a": ck_A5a, "A5b": ck_A5b, "A5c": ck_A5c,
    "A6a": ck_A6a, "A6b": ck_A6b,
    "A7a": ck_A7a, "A7b": ck_A7b,
    "A8a": ck_A8a, "A8b": ck_A8b,
}


# ===========================================================================
# 4) 金丝雀（CLN / INJ 成对；每个检查器恰一对）
#    CLN 与 INJ 都作用在**归一化后的上下文**上；**CLN 必须写到该检查器实际读取的那个键**。
# op 形式：
#   ("probe_set", probe, key, idx|None, value) | ("probe_map", probe, key, {idx: value})
#   ("probe_copy", probe, dst_key, src_key, None) | ("text_set", name, lines)
#   ("text_restore", name) | ("src_set", key, value) | ("src_restore", key) | ("multi", [op, ...])
# ===========================================================================
def build_canary(ctx):
    C = {}
    v01 = ind(ctx, "V01", "abs_diff")
    C["A1a"] = {
        "cln_op": ("multi", [("probe_map", "V01", "turnover",
                              {i: pn(ctx, "V01", "control_declared", i) for i in v01}),
                             ("probe_copy", "V01", "avg_prod", "avg_ctrl_from_daily", None),
                             ("probe_copy", "V01", "max_prod", "max_ctrl_from_daily", None),
                             ("probe_copy", "V01", "min_prod", "min_ctrl_from_daily", None),
                             ("probe_set", "V01", "panelB_turnover", None,
                              pn(ctx, "V01", "panelB_control"))]),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V01", "turnover", v01[0], 0.0),
        "intent": "CLN：逐对 turnover 直接写成对照值、聚合值同源、panelB 亦对齐 ⟹ 零偏差；"
                  "INJ：把 pair0 的 turnover 置 0.0（与对照 0.1 差 0.1）"}

    C["A1b"] = {
        "cln_op": ("src_set", "analyze_guard_lines", [103]),
        "clean": "NOT_VIOLATED", "inj": "CONTRACT_MISLABEL",
        "inj_op": ("src_restore", "analyze_guard_lines"),
        "intent": "CLN：给方法体注入一行候选数守卫 ⟹ 前置条件被强制；INJ：恢复无守卫的方法体"}

    C["A2a"] = {
        "cln_op": ("probe_set", "V03", "reported", None, 0.0),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V03", "reported", None, 0.7),
        "intent": "CLN：载荷值改成与定义值一致（0.0）；INJ：恢复实测的 0.7"}

    C["A2b"] = {
        "cln_op": ("probe_set", "V04", "reported", None, 0.35),
        "clean": "REGISTERED_ONLY", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V04", "reported", None, 0.7),
        "intent": "CLN：偏差降到 0（< 0.10）；INJ：恢复 0.7（偏差 0.35 ≥ 0.10）"}

    C["A2c"] = {
        "cln_op": ("probe_set", "V06", "has_status_key", None, 1),
        "clean": "NOT_VIOLATED", "inj": "REGISTERED_ONLY",
        "inj_op": ("probe_set", "V06", "has_status_key", None, 0),
        "intent": "CLN：响应含机器可读的不可用标记键；INJ：恢复无标记（signal_days<2 且空 daily）"}

    v05 = ind(ctx, "V05", "err")
    C["A2d"] = {
        "cln_op": ("probe_map", "V05", "reported",
                   {i: pn(ctx, "V05", "defined", i) for i in v05}),
        "clean": "REGISTERED_ONLY", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V05", "reported", v05[0], pn(ctx, "V05", "defined", v05[0]) + 0.5),
        "intent": "CLN：每格 recorded 值等于定义值（err=0）；INJ：把首格偏移 0.5（越过 0.10）"}

    C["A3a"] = {
        "cln_op": ("probe_set", "V07", "adjacent_same_date", None, 0),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V07", "adjacent_same_date", None, 1),
        "intent": "CLN：不存在「相邻 pair 同日」⟹ 未触发；INJ：恢复实测的 1（同日不同策略被配对）"}

    C["A3b"] = {
        "cln_op": ("probe_set", "V08", "echo_blank", None, "ALL"),
        "clean": "NOT_VIOLATED", "inj": "CONTRACT_AMBIGUOUS",
        "inj_op": ("probe_set", "V08", "echo_blank", None, ""),
        "intent": "CLN：两次回显相同（不存在歧义）；INJ：恢复回显 \"ALL\" vs 空串"}

    C["A3c"] = {
        "cln_op": ("src_set", "createdat_compare_lines", [58]),
        "clean": "NOT_VIOLATED", "inj": "NOT_REACHABLE",
        "inj_op": ("src_restore", "createdat_compare_lines"),
        "intent": "CLN：源码含 createdAt 比较 ⟹ 「取最新」自证；INJ：恢复无比较的源码"}

    v10n = ind(ctx, "V10", "new_count")
    v10t = {i: pn(ctx, "V10", "turnover", i) for i in v10n}
    v10nn = {i: pn(ctx, "V10", "top_n", i) for i in v10n}
    C["A4a"] = {
        "cln_op": ("multi", [("probe_map", "V10", "new_count",
                              {i: v10t[i] * v10nn[i] + 1 for i in v10n}),
                             ("probe_map", "V10", "exit_count",
                              {i: v10t[i] * v10nn[i] - 1 for i in v10n})]),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V10", "turnover", v10n[0], 0.9),
        "intent": "CLN：逐对把 (new, exit) 从满员实测值改成 (turnover×n+1, turnover×n−1) —— "
                  "状态确实改变（如 pair0 从 (1,1) 变 (2,0)），但 (new+exit)/2 = turnover×n "
                  "⟹ 恒等式仍成立；INJ：把 pair0 的 turnover 置 0.9 ⟹ 重算缺口 |9.0 − 1.0| = 8.0"}

    C["A4b"] = {
        "cln_op": ("probe_set", "V11", "has_size_field", None, 1),
        "clean": "REGISTERED_ONLY", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V11", "has_size_field", None, 0),
        "intent": "CLN：逐日行含规模字段 ⟹ 只登记；INJ：恢复不含（重算缺口 7.0 > 1e-12）"}

    v12 = [i for i in ind(ctx, "V12", "n_recovered") if pv(ctx, "V12", "exact_capable", i) == 1]
    C["A4c"] = {
        "cln_op": ("multi", [("probe_map", "V12", "echo_match", {i: 1 for i in v12}),
                             ("probe_map", "V12", "n_rec_absdiff", {i: 0.0 for i in v12})]),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V12", "n_rec_absdiff", v12[0], 1.0),
        "intent": "CLN：回显全对且反解零偏差；INJ：把首格反解偏差置 1.0"}

    C["A5a"] = {
        "cln_op": ("probe_set", "V13", "missing_keys", None, ""),
        "clean": "NOT_VIOLATED", "inj": "REGISTERED_ONLY",
        "inj_op": ("probe_set", "V13", "missing_keys", None, "prev_size,curr_size"),
        "intent": "CLN：逐日行含全部规模字段；INJ：恢复缺失两键"}

    C["A5b"] = {
        "cln_op": ("probe_set", "V14", "has_structured_basis", None, 1),
        "clean": "NOT_VIOLATED", "inj": "REGISTERED_ONLY",
        "inj_op": ("probe_set", "V14", "has_structured_basis", None, 0),
        "intent": "CLN：载荷含结构化口径字段；INJ：恢复只有 note 自由文本"}

    C["A5c"] = {
        "cln_op": ("text_set", "c_rolling_unavail", []),
        "clean": "NOT_VIOLATED", "inj": "CONVENTION_DIVERGENCE",
        "inj_op": ("text_restore", "c_rolling_unavail"),
        "intent": "CLN：抹掉 RollingExperimentService 的 UNAVAILABLE 结论 ⟹ 两处不再相反；"
                  "INJ：恢复两处相反的惯例"}

    C["A6a"] = {
        "cln_op": ("text_set", "c_a6a_candidate_vue_unit", []),
        "clean": "NOT_VIOLATED", "inj": "CONTRACT_AMBIGUOUS",
        "inj_op": ("multi", [("text_restore", "c_a6a_candidate_vue_unit"),
                             ("text_restore", "c_a6a_mvcap")]),
        "intent": "CLN：抹掉「候选替换率 ×100」这一处单位证据 ⟹ 无法证明同名量单位不同；"
                  "INJ：恢复（同名 avg_turnover 一为分数、一为百分数）"}

    C["A6b"] = {
        "cln_op": ("text_set", "c_a6b_candidate_render", []),
        "clean": "NOT_VIOLATED", "inj": "CONTRACT_AMBIGUOUS",
        "inj_op": ("text_restore", "c_a6b_candidate_render"),
        "intent": "CLN：抹掉候选替换率那处的「换手」渲染 ⟹ 同标签只指一种量；INJ：恢复三处并存"}

    v18 = ind(ctx, "V18", "value")
    C["A7a"] = {
        "cln_op": ("multi", [("probe_map", "V18", "in_range", {i: 1 for i in v18}),
                             ("probe_map", "V18", "is_nan", {i: 0 for i in v18}),
                             ("probe_map", "V18", "is_inf", {i: 0 for i in v18}),
                             ("probe_set", "V18", "round4_monotone_on_0_1", None, 1)]),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V18", "in_range", v18[0], 0),
        "intent": "CLN：全部取值标记为在域内、非 NaN/Inf、round4 保序；INJ：把首值标记为越界"}

    C["A7b"] = {
        "cln_op": ("text_set", "c_ow_tw", ["probe: 载荷的换手率按单向口径（不乘 2）"]),
        "clean": "NOT_VIOLATED", "inj": "REGISTERED_ONLY",
        "inj_op": ("text_set", "c_ow_tw", []),
        "intent": "CLN：被审计面存在单双向声明；INJ：恢复无任何声明"}

    C["A8a"] = {
        "cln_op": ("probe_set", "V20", "identical", None, 1),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V20", "identical", None, 0),
        "intent": "CLN：两次调用逐位相同；INJ：置为不可复现"}

    C["A8b"] = {
        "cln_op": ("probe_set", "V21", "changed", None, 0),
        "clean": "NOT_VIOLATED", "inj": "VIOLATED",
        "inj_op": ("probe_set", "V21", "changed", None, 1),
        "intent": "CLN：两种行序给出相同换手率；INJ：恢复行序依赖"}

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
        ctx2["text"][name] = copy.deepcopy(ctx2["text_pristine"][name])
    elif kind == "src_set":
        _, key, val = op
        ctx2["src"][key] = list(val) if isinstance(val, list) else val
    elif kind == "src_restore":
        _, key = op
        ctx2["src"][key] = copy.deepcopy(ctx2["text_pristine"]["__src_" + key])
    else:
        raise ValueError("unknown op: %r" % (kind,))


# ===========================================================================
# 5) config.json 冻结比对
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
            "TOL_BIT": TOL_BIT, "TH_MAT": TH_MAT, "N_GRID": N_GRID, "P_GRID": P_GRID,
            "RANGE_LO": RANGE_LO, "RANGE_HI": RANGE_HI,
            "EXISTING_TEST_TOL": EXISTING_TEST_TOL,
            "CONTROL_ONE_WAY_FACTOR": CONTROL_ONE_WAY_FACTOR},
    }
    return all(checks.values()), checks


# ===========================================================================
# 6) 计数断言 C1..C11（计数不匹配只记附录 A）
# ===========================================================================
def count_by_file(lines):
    d = {}
    for ln in lines:
        f = ln.split(":", 1)[0]
        d[f] = d.get(f, 0) + 1
    return d


def _src_only(lines):
    return [h for h in lines if not any(s in h for s in ("target/", "dist/"))]


def eval_counts(ctx):
    t = ctx["text"]
    out = {}

    def rec(cid, actual, expected, extra=None):
        row = {"actual": actual, "frozen_expected": expected, "match": actual == expected}
        if extra:
            row.update(extra)
        out[cid] = row

    c1 = _src_only(t["c1"])
    rec("C1", len(c1), EXPECT_COUNTS["C1"]["src"],
        {"by_file": count_by_file(c1), "raw": len(t["c1_raw"]),
         "raw_expected": EXPECT_COUNTS["C1"]["raw"],
         "by_file_expected": EXPECT_COUNTS["C1"]["by_file"]})
    c2 = _src_only(t["c2"])
    rec("C2", len(c2), EXPECT_COUNTS["C2"]["src"],
        {"by_file": count_by_file(c2), "raw": len(t["c2_raw"]),
         "raw_expected": EXPECT_COUNTS["C2"]["raw"],
         "by_file_expected": EXPECT_COUNTS["C2"]["by_file"]})
    c3 = _src_only(t["c3"])
    rec("C3", len(c3), EXPECT_COUNTS["C3"]["src"],
        {"by_file": count_by_file(c3), "raw": len(t["c3_raw"]),
         "raw_expected": EXPECT_COUNTS["C3"]["raw"],
         "by_file_expected": EXPECT_COUNTS["C3"]["by_file"]})
    c4 = _src_only(t["c4"])
    rec("C4", len(c4), EXPECT_COUNTS["C4"]["src"],
        {"by_file": count_by_file(c4), "raw": len(t["c4_raw"]),
         "raw_expected": EXPECT_COUNTS["C4"]["raw"],
         "by_file_expected": EXPECT_COUNTS["C4"]["by_file"]})
    rec("C5", len(t["c5"]), EXPECT_COUNTS["C5"]["src"], {"lines": t["c5"]})
    c6 = _src_only(t["c6"])
    rec("C6", len(c6), EXPECT_COUNTS["C6"]["src"],
        {"by_file": count_by_file(c6), "raw": len(t["c6_raw"]),
         "raw_expected": EXPECT_COUNTS["C6"]["raw"]})
    c7 = _src_only(t["c7"])
    rec("C7", len(c7), EXPECT_COUNTS["C7"]["src"],
        {"lines": c7, "raw": len(t["c7_raw"]), "raw_expected": EXPECT_COUNTS["C7"]["raw"],
         "raw_lines": t["c7_raw"]})
    rec("C8", len(t["c8"]), EXPECT_COUNTS["C8"]["src"], {"lines": t["c8"]})
    c9 = _src_only(t["c9"])
    rec("C9", len(c9), EXPECT_COUNTS["C9"]["src"],
        {"by_file": count_by_file(c9), "raw": len(t["c9_raw"]),
         "raw_expected": EXPECT_COUNTS["C9"]["raw"]})
    c10 = t["c10"]
    rec("C10", len(c10), EXPECT_COUNTS["C10"]["src"],
        {"files": len(count_by_file(c10)), "files_expected": EXPECT_COUNTS["C10"]["files"],
         "by_file": count_by_file(c10)})
    c11 = t["c11"]
    rec("C11", len(c11), EXPECT_COUNTS["C11"]["src"],
        {"by_file": count_by_file(c11), "raw": len(t["c11_raw"])})
    out["_all_match"] = all(v["match"] for k, v in out.items() if not k.startswith("_"))
    out["_note"] = ("计数不匹配按 prereg §7 只写入报告附录 A，不改变任何判定词；"
                    "raw = 不排除 target/dist 的原始口径")
    return out


# ===========================================================================
# 7) 主流程
# ===========================================================================
def main():
    global _log_fh
    started = datetime.now()
    os.makedirs(OUT_DIR, exist_ok=True)
    _log_fh = open(os.path.join(OUT_DIR, "run.log"), "a", encoding="utf-8")
    log("=" * 78)
    log("R008 换手率载荷族审计 —— 只读；判据/阈值/判定词冻结于 rounds/R008_prereg.md")
    log("=" * 78)

    result = {
        "round": "R008", "started_at": started.isoformat(),
        "prereg": "rounds/R008_prereg.md", "review": "rounds/R008_review.md",
        "criteria_source": "Yao & Zheng, arXiv:2606.08285v1 (2026-06-06)",
        "audited_target": ("avg_turnover / max_turnover / min_turnover / daily_turnover[]"
                           "（BacktestTurnoverService.analyze → GET /api/backtest/turnover → "
                           "ScoringBacktest.vue）"),
        "hypothesis_H0": ("载荷族在任何输入下忠实对应声明口径 换手率 = 1 − |相邻两日 TopN 交集| / topN；"
                          "且口径不适用时（任一日候选数 < topN，或配对的不是同策略相邻信号日）"
                          "不输出看起来像测量值的实数"),
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
        with open(os.path.join(OUT_DIR, "result.json"), "w", encoding="utf-8") as fh:
            json.dump(result, fh, ensure_ascii=False, indent=2, default=str)
        log("FATAL: 探针失败，整轮 INCONCLUSIVE")
        return

    wrap = {"probe": probe}
    src = str(pv(wrap, "G3", "engine_code_source"))
    norm = src.replace("\\", "/").rstrip("/")
    want = CLASSES.replace("\\", "/").rstrip("/")
    gate_g3 = norm.lower().endswith(want.lower())
    prod_short = pn(wrap, "G3", "sentinel_short_prod")
    ctrl_short = pn(wrap, "G3", "sentinel_short_control")
    prod_full = pn(wrap, "G3", "sentinel_full_prod")
    ctrl_full = pn(wrap, "G3", "sentinel_full_control")
    gate_reverse = abs(prod_short - ctrl_short) > TOL_BIT            # 反向锚点：必须不同
    gate_full = abs(prod_full - ctrl_full) <= TOL_BIT                # 正向锚点：必须逐位相等
    g3 = {"engine_code_source": src, "expected": CLASSES, "is_repo_classes": gate_g3,
          "class": pv(wrap, "G3", "engine_class"),
          "classloader": pv(wrap, "G3", "engine_classloader"),
          "sentinel_short_prod": prod_short, "sentinel_short_control": ctrl_short,
          "sentinel_reverse_differs": gate_reverse,
          "sentinel_full_prod": prod_full, "sentinel_full_control": ctrl_full,
          "sentinel_full_member_equals": gate_full,
          "sentinel_note": ("反向锚点 = A2a 面板（两日各 3 条、集合相同）：生产 0.7 vs 定义 0.0，必须不同；"
                            "正向锚点 = 满员 |I|=9 面板：生产值必须逐位等于 1 − 9/10")}
    log("G3 engine_code_source = %s  (is_repo_classes=%s, reverse_differs=%s, full_equals=%s)"
        % (src, gate_g3, gate_reverse, gate_full))

    text = build_text_corpora()
    ex_lit = [ln for ln in text["c_ex_test_literal"] if "0.3333" in ln]
    ex_prod = pn(wrap, "G3", "ex_test_prod_avg")
    ex_exp = pn(wrap, "G3", "ex_test_expected")
    ex_diff = abs(ex_prod - ex_exp)
    consistency_gate = {
        "existing_test": "BacktestTurnoverServiceTest.analyzeComputesTurnoverBetweenDays:51-71",
        "test_literal_lines": ex_lit,
        "test_asserts_0_3333": bool(ex_lit),
        "probe_prod": ex_prod, "probe_expected_literal": ex_exp, "abs_diff": ex_diff,
        "test_tolerance_abs": EXISTING_TEST_TOL,
        "passes": (ex_diff <= EXISTING_TEST_TOL) and bool(ex_lit)}
    log("existing-test consistency gate: prod=%.6g literal=%.6g abs_diff=%.3g < tol=%.1e -> %s"
        % (ex_prod, ex_exp, ex_diff, EXISTING_TEST_TOL, consistency_gate["passes"]))
    result["gate_G3"] = g3
    result["gate_existing_test_consistency"] = consistency_gate

    srcinfo = build_src()
    for k in sorted(text):
        log("corpus %s: %d hit(s)" % (k, len(text[k])))
    text_pristine = copy.deepcopy(text)
    for k, v in srcinfo.items():
        if isinstance(v, list):
            text_pristine["__src_" + k] = copy.deepcopy(v)

    # 计数先算（A6a 需要 C10）
    tmp_ctx = {"probe": probe, "text": text, "text_pristine": text_pristine, "src": srcinfo,
               "counts": {}}
    counts = eval_counts(tmp_ctx)
    tmp_ctx["counts"] = counts
    ctx = {"probe": probe, "text": text, "text_pristine": text_pristine, "src": srcinfo,
           "counts": counts, "k": FROZEN_K,
           "meta": {"classes": CLASSES,
                    "harness_deps": [os.path.relpath(p, M2_REPO).replace("\\", "/")
                                     for p in DEP_CACHE["jars"]],
                    "harness_deps_pinned_by": "modules/backend-java/pom.xml spring-boot-starter-parent 3.2.0"}}
    result["counts_C1_C11"] = counts
    for cid in ("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "C11"):
        log("count %s: actual=%s expected=%s match=%s"
            % (cid, counts[cid]["actual"], counts[cid]["frozen_expected"], counts[cid]["match"]))

    # ---- 行和交叉校验（K8）----
    by_axis = {ax: len(v) for ax, v in BY_AXIS.items()}
    rowsum = {"by_axis": by_axis, "frozen_axis_counts": FROZEN_AXIS_COUNTS,
              "total_variants": sum(by_axis.values()), "frozen_total": FROZEN_TOTAL,
              "declared_checkers": len(CHECKERS),
              "axis_keys_match": sorted(BY_AXIS.keys()) == sorted(FROZEN_AXIS_COUNTS.keys()),
              "variant_map_complete": all(a in VARIANT for axes in BY_AXIS.values() for a in axes)
              and len(VARIANT) == FROZEN_TOTAL,
              "variant_ids_unique": len(set(VARIANT.values())) == FROZEN_TOTAL,
              "variant_ids_all_used": sorted(VARIANT.values())
              == ["V%02d" % i for i in range(1, FROZEN_TOTAL + 1)]}
    rowsum["ok"] = (rowsum["axis_keys_match"] and by_axis == FROZEN_AXIS_COUNTS
                    and sum(by_axis.values()) == FROZEN_TOTAL == len(CHECKERS)
                    and rowsum["variant_map_complete"] and rowsum["variant_ids_unique"]
                    and rowsum["variant_ids_all_used"])
    cfg_ok, cfg_detail = verify_config_frozen()
    rowsum["config_frozen_match"] = cfg_ok
    rowsum["config_frozen_detail"] = cfg_detail
    rowsum["ok"] = rowsum["ok"] and cfg_ok
    log("config.json frozen-match: %s" % cfg_ok)
    log("row-sum check: %s (by_axis=%s total=%d)" % (rowsum["ok"], by_axis, sum(by_axis.values())))
    result["variant_budget"] = rowsum

    # ---- 金丝雀 ----
    canary_spec = build_canary(ctx)
    canary_rows = []
    for axis in CHECKERS:
        spec = canary_spec[axis]
        fn = CHECKERS[axis]
        cv, cev = fn(apply_op(ctx, spec["cln_op"]))
        iv, iev = fn(apply_op(ctx, spec["inj_op"]))
        ok = (cv == spec["clean"] and iv == spec["inj"] and cv != iv)
        canary_rows.append({"axis": axis, "variant": VARIANT[axis],
                            "clean_expected": spec["clean"], "clean_actual": cv,
                            "inj_expected": spec["inj"], "inj_actual": iv, "passed": ok,
                            "cln_op_kind": spec["cln_op"][0], "inj_op_kind": spec["inj_op"][0],
                            "intent": spec["intent"],
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
                                           "CLN 写到检查器实际读取的键；要求 clean != inj"),
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
        "K2": verdicts["A2b"] == "VIOLATED",
        "K3": verdicts["A3a"] == "VIOLATED",
        "K4": verdicts["A1b"] == "CONTRACT_MISLABEL",
        "K5": (verdicts["A6a"] == "CONTRACT_AMBIGUOUS") or (verdicts["A6b"] == "CONTRACT_AMBIGUOUS"),
        "K6": (verdicts["A3b"] == "CONTRACT_AMBIGUOUS") or (verdicts["A2c"] == "CONTRACT_AMBIGUOUS"),
        "K7": any(verdicts[a] != "NOT_VIOLATED"
                  for a in ("A2c", "A4b", "A5a", "A5b", "A5c", "A7b", "A8b")),
        "K8": not rowsum["ok"],
        "K9": (not all_canary_pass) or (not gate_g3) or (not gate_reverse) or (not gate_full)
              or bool(no_canary) or (not consistency_gate["passes"]) or bool(bad_vocab),
        "K10": verdicts["A8a"] == "VIOLATED",
    }
    log("k_fired: %s" % k_fired)

    # ---- 总判定（prereg §6.2 冻结的按序短路规则）----
    h0_alive = not (k_fired["K1"] or k_fired["K2"] or k_fired["K3"])
    if k_fired["K8"] or k_fired["K9"] or k_fired["K10"]:
        total = "INCONCLUSIVE"
        reason = "K8/K9/K10 触发：行和校验、阳性对照或确定性未通过 ⟹ 其余断言结果作废"
    elif k_fired["K1"] or k_fired["K2"] or k_fired["K3"]:
        total = "REJECTED"
        reason = "H0 被杀：%s 触发" % "/".join([k for k in ("K1", "K2", "K3") if k_fired[k]])
    elif k_fired["K4"] or k_fired["K5"]:
        total = "CONTRACT_MISLABEL"
        reason = "H0 存活但契约指称错误：%s 触发" % "/".join([k for k in ("K4", "K5") if k_fired[k]])
    elif k_fired["K6"]:
        total = "CONTRACT_AMBIGUOUS"
        reason = "H0 存活但契约存在歧义：K6 触发"
    elif k_fired["K7"]:
        refs = [a for a in ("A2c", "A4b", "A5a", "A5b", "A5c", "A7b", "A8b")
                if verdicts[a] != "NOT_VIOLATED"]
        total = "REGISTERED_ONLY"
        reason = "H0 存活、无契约错误，仅登记项：K7 由 %s 触发" % "/".join(refs)
    else:
        total = "NOT_VIOLATED"
        reason = "K1/K2/K3 全部未触发 ⟹ H0 存活（不得叙述为「项目有问题」）"

    result["kill_conditions_fired"] = k_fired
    result["h0_alive"] = h0_alive
    result["h0_alive_definition"] = "K1 与 K2 与 K3 全部未触发（prereg §6.2 冻结）"
    result["total_verdict"] = total
    result["total_verdict_reason"] = reason
    result["verdict_synthesis_rule"] = ("按序短路：K8/K9/K10 -> INCONCLUSIVE；K1/K2/K3 -> REJECTED；"
                                        "K4/K5 -> CONTRACT_MISLABEL；K6 -> CONTRACT_AMBIGUOUS；"
                                        "K7 -> REGISTERED_ONLY；否则 NOT_VIOLATED")
    result["finished_at"] = datetime.now().isoformat()
    log("H0 ALIVE = %s" % h0_alive)
    log("TOTAL VERDICT = %s" % total)
    log("reason: %s" % reason)

    if os.path.isdir(PROBE_OUT):
        shutil.rmtree(PROBE_OUT)
        log("cleaned compiled probe output: %s" % PROBE_OUT)

    with open(os.path.join(OUT_DIR, "result.json"), "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2, default=str)
    log("wrote result.json")


if __name__ == "__main__":
    main()
