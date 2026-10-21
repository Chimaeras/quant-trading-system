#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R010 编排器：成本后净收益（COST_APPROX）声明–实现一致性审计。

本文件**只**读 `config.json` 与仓库源码/产物，不修改任何生产、测试或前端文件。
判定逻辑逐条对应 `rounds/R010_prereg.md` §4（19 断言 / 11 轴）与 §6.1（K1–K10）。

设计要点（与 R009 同源，另有本轮新增）：
  * 判定单元 = **变体**（19 个 checker），轴只用于分组与行和（K8）。
  * 每个 checker 的「决定性量」必须来自**实测数值 / 源码文本计数 / Decimal 复算**，
    不得依赖探针自报的布尔值（prereg §5.2 末条 + 报告 Q13）。
  * 金丝雀 `CLN`/`INJ`（19 对）：CLN 把该 checker **实际读取的全部键**写成预注册期望值，
    检查器必须判出预注册词；INJ 把决定性键写成毒值，必须判出**不同**词。
    （R009 只覆写部分键；本轮的 CLN 覆写全部读取键 ⟹ 金丝雀成为 checker 逻辑的纯单元测试，
     不会因为别处真实违规而把结论掩成 INCONCLUSIVE。此为**实现层选择**，非判据变更。）
  * `run.log` append-only；失败运行保留并逐次披露。
"""
import json
import os
import re
import shutil
import subprocess
import sys
from decimal import Decimal, getcontext

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
CONFIG_PATH = os.path.join(HERE, "config.json")
LOG_PATH = os.path.join(HERE, "run.log")
PROBE_DIR = os.path.join(HERE, "probe")
PROBE_SRC = os.path.join(PROBE_DIR, "R010Probe.java")
PROBE_OUT = os.path.join(PROBE_DIR, "out")
PROBE_MAIN = "com.quant.service.backtest.R010Probe"
RESULT_PATH = os.path.join(HERE, "result.json")

getcontext().prec = 60

M2 = os.path.expanduser("~/.m2/repository")
DEPS_REL = [
    "org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar",
    "org/springframework/spring-core/6.1.1/spring-core-6.1.1.jar",
    "org/springframework/spring-beans/6.1.1/spring-beans-6.1.1.jar",
    "org/springframework/spring-context/6.1.1/spring-context-6.1.1.jar",
    "org/springframework/spring-tx/6.1.1/spring-tx-6.1.1.jar",
    "org/springframework/spring-aop/6.1.1/spring-aop-6.1.1.jar",
    "org/springframework/data/spring-data-commons/3.2.0/spring-data-commons-3.2.0.jar",
    "org/springframework/data/spring-data-jpa/3.2.0/spring-data-jpa-3.2.0.jar",
    "jakarta/persistence/jakarta.persistence-api/3.1.0/jakarta.persistence-api-3.1.0.jar",
    "com/fasterxml/jackson/core/jackson-databind/2.15.3/jackson-databind-2.15.3.jar",
    "com/fasterxml/jackson/core/jackson-core/2.15.3/jackson-core-2.15.3.jar",
    "com/fasterxml/jackson/core/jackson-annotations/2.15.3/jackson-annotations-2.15.3.jar",
]
CLASSES_DIR = os.path.join(ROOT, "modules", "backend-java", "target", "classes")

SKIP_DIRS = {".git", "node_modules", "target", "build", "dist", "__pycache__",
             ".venv", "venv", ".idea", ".mvn", ".gradle", "out"}

# 源码位置表（只读输入；供 SRC_RANGES 的 `file_key` 解析）
_LOC = json.load(open(CONFIG_PATH, encoding="utf-8"))["src_locations"]

VERDICTS = ["NOT_VIOLATED", "VIOLATED", "CONVENTION_DIVERGENCE", "CONTRACT_AMBIGUOUS",
            "CONTRACT_MISLABEL", "REGISTERED_ONLY", "NOT_REACHABLE", "INCONCLUSIVE"]

_TXT_CACHE = {}


# ---------------------------------------------------------------------------
# 日志（append-only）
# ---------------------------------------------------------------------------
def log(msg, echo=True):
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(msg.rstrip("\n") + "\n")
    if echo:
        print(msg)


def log_block(title, text):
    log("----- %s -----" % title)
    for line in str(text).splitlines():
        log("  | " + line)


# ---------------------------------------------------------------------------
# 只读走查
# ---------------------------------------------------------------------------
def walk_files(scope, exts):
    """走查 scope 下的文件；**排除**构建产物目录（与 R007–R009 同一 SKIP_DIRS 口径）。"""
    key = ("WALK", scope, tuple(exts))
    if key in _TXT_CACHE:
        return _TXT_CACHE[key]
    base = os.path.join(ROOT, scope)
    out = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if exts and not any(fn.endswith(e) for e in exts):
                continue
            out.append(os.path.join(dirpath, fn))
    out.sort()
    _TXT_CACHE[key] = out
    return out


def read_file(path):
    key = ("READ", path)
    if key in _TXT_CACHE:
        return _TXT_CACHE[key]
    txt = ""
    for enc in ("utf-8", "utf-8-sig", "gbk", "latin-1"):
        try:
            with open(path, encoding=enc) as f:
                txt = f.read()
            break
        except (UnicodeDecodeError, LookupError):
            continue
    _TXT_CACHE[key] = txt
    return txt


def count_pattern(scope, exts, pattern, flags=0):
    rx = re.compile(pattern, flags)
    total = 0
    by_file = {}
    for path in walk_files(scope, exts):
        t = read_file(path)
        n = len(rx.findall(t))
        if n:
            total += n
            by_file[os.path.basename(path)] = by_file.get(os.path.basename(path), 0) + n
    return total, dict(sorted(by_file.items()))


def range_lines(rng):
    """返回 (绝对路径, 行列表, 冻结起始行)。`rng[0]` 是 config 的 `src_locations` 键。"""
    path = os.path.join(ROOT, _LOC[rng[0]])
    lines = read_file(path).splitlines()
    a, b = rng[1], rng[2]
    return path, lines[a - 1:b], a


def range_text(rng):
    return "\n".join(range_lines(rng)[1])


def find_line(text, pattern, start=1):
    """首个匹配行号（1-based）。start 为起始行提示：同一文本在文件内出现多次时用它绑定**意图站点**。"""
    rx = re.compile(pattern)
    for i, line in enumerate(text.splitlines(), start=1):
        if i < start:
            continue
        if rx.search(line):
            return i
    return -1


# ---------------------------------------------------------------------------
# 依赖 / 编译 / 运行
# ---------------------------------------------------------------------------
def dep_paths():
    return [os.path.join(M2, d) for d in DEPS_REL]


def check_deps():
    missing = [d for d in DEPS_REL if not os.path.exists(os.path.join(M2, d))]
    return missing


def run(cmd, cwd=None, timeout=900):
    p = subprocess.run(cmd, cwd=cwd or ROOT, capture_output=True, timeout=timeout)
    out = p.stdout.decode("utf-8", "replace")
    err = p.stderr.decode("utf-8", "replace")
    return p.returncode, out, err


def maven_test():
    # Windows 下 `mvn` 是 mvn.cmd；subprocess 列表形式（无 shell）不解析 PATHEXT ⟹ 必须显式解析
    exe = shutil.which("mvn.cmd") or shutil.which("mvn.bat") or shutil.which("mvn")
    if not exe:
        return 127, "", "mvn not found on PATH"
    cmd = [exe, "-o", "-B", "test", "-pl", "modules/backend-java"]
    rc, out, err = run(cmd, timeout=1500)
    return rc, out, err


def surefire_summary():
    d = os.path.join(ROOT, "modules", "backend-java", "target", "surefire-reports")
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "classes": 0}
    per_class = {}
    if not os.path.isdir(d):
        return totals, per_class
    for fn in sorted(os.listdir(d)):
        if not (fn.startswith("TEST-") and fn.endswith(".xml")):
            continue
        xml = read_file(os.path.join(d, fn))
        m = re.search(r"<testsuite[^>]*>", xml)
        if not m:
            continue
        head = m.group(0)

        def attr(a):
            mm = re.search(r'%s="(\d+)"' % a, head)
            return int(mm.group(1)) if mm else 0
        name = re.search(r'name="([^"]+)"', head)
        cn = name.group(1) if name else fn
        per_class[cn] = {"tests": attr("tests"), "failures": attr("failures"),
                         "errors": attr("errors"), "skipped": attr("skipped")}
        for k in ("tests", "failures", "errors", "skipped"):
            totals[k] += per_class[cn][k]
        totals["classes"] += 1
    return totals, per_class


def compile_probe(cp):
    """prereg §5.2：探针必须**编译进** `modules/backend-java/target/classes`（生产引擎自己的包），
    这样 `R010Probe.class.getProtectionDomain().getCodeSource().getLocation()` 才指向仓库产物，
    G3 门才有意义（证明判据跑在仓库的生产字节码上，而非探针私有的副本上）。"""
    cleanup_probe()
    if os.path.isdir(PROBE_OUT):
        shutil.rmtree(PROBE_OUT, ignore_errors=True)
    cmd = ["javac", "-encoding", "UTF-8", "-d", CLASSES_DIR, "-cp", cp, PROBE_SRC]
    return run(cmd, timeout=600)


def cleanup_probe():
    """移除探针编译进 target/classes 的字节码（不触碰仓库自身的任何 class）。"""
    removed = []
    base = os.path.join(CLASSES_DIR, "com", "quant", "service", "backtest")
    if os.path.isdir(base):
        for fn in sorted(os.listdir(base)):
            if fn.startswith("R010Probe") and fn.endswith(".class"):
                p = os.path.join(base, fn)
                try:
                    os.remove(p)
                    removed.append(p)
                except OSError:
                    pass
    return removed


def run_probe(cp, tag):
    cmd = ["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
           "-cp", CLASSES_DIR + os.pathsep + cp, PROBE_MAIN]
    rc, out, err = run(cmd, timeout=600)
    return rc, out, err


def parse_probe(stdout):
    """探针每行一个 JSON 对象；返回 {probe: {key: value}} 与解析失败行数。"""
    data = {}
    bad = []
    for line in stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            o = json.loads(line)
        except ValueError:
            bad.append(line[:160])
            continue
        sec = o.get("probe")
        if sec is None:
            continue
        if "items" in o:
            data.setdefault(sec, {})[o["key"]] = o["items"]
        else:
            data.setdefault(sec, {})[o["key"]] = o.get("value")
    return data, bad


# ---------------------------------------------------------------------------
# 探针取值助手
# ---------------------------------------------------------------------------
def P(ctx, sec, key, default=None):
    return ctx["probe"].get(sec, {}).get(key, default)


def fnum(v, default=None):
    if v is None:
        return default
    if isinstance(v, str):
        s = v.strip()
        if s in ("", "null", "NaN"):
            return default if s != "NaN" else float("nan")
        try:
            return float(s)
        except ValueError:
            return default
    if isinstance(v, bool):
        return 1.0 if v else 0.0
    if isinstance(v, (int, float)):
        return float(v)
    return default


def num(ctx, sec, key, default=None):
    return fnum(P(ctx, sec, key), default)


def dec(ctx, sec, key, default=None):
    v = P(ctx, sec, key)
    if v is None or (isinstance(v, str) and v.strip() in ("", "null")):
        return default
    try:
        return Decimal(str(v).strip())
    except Exception:
        return default


def T(ctx, key, default=None):
    return ctx["txt"].get(key, default)


# ---------------------------------------------------------------------------
# 冻结算术（与 make_config.py 同一表达式顺序）
# ---------------------------------------------------------------------------
def _d(x):
    return Decimal(x)


def derive_block():
    P_ = _d("10.000000")
    E_ = _d("11.000000")
    c = _d("0.0003")
    t = _d("0.0005")
    s = _d("0.0002")
    fD = 1 - t - s
    gD = 1 + c + s
    fI = (1 - s) * (1 - c - t)
    gI = (1 + s) * (1 + c)
    nD = E_ * fD / (P_ * gD) - 1
    nI = E_ * fI / (P_ * gI) - 1
    A = fI / fD - 1
    B = gD / gI - 1
    M = (1 + nI) / (1 + nD)
    return {"P": P_, "E": E_, "c": c, "t": t, "s": s,
            "f_doc": fD, "g_doc": gD, "f_impl": fI, "g_impl": gI,
            "net_doc": nD, "net_impl": nI, "delta_net": nI - nD,
            "delta_bp": (nI - nD) * 10000, "A_bp": A * 10000, "B_bp": B * 10000,
            "A_plus_B_bp": (A + B) * 10000,
            "one_plus_A_one_plus_B_bp": ((1 + A) * (1 + B) - 1) * 10000,
            "M": M, "M_minus_1_bp": (M - 1) * 10000}


# ---------------------------------------------------------------------------
# 源码文本测量
# ---------------------------------------------------------------------------
def text_measurements(cfg):
    txt = {}
    counts = {}
    for name, spec in cfg["count_expectations"].items():
        n, byf = count_pattern(spec["scope"], spec["exts"], spec["pattern"])
        txt[name + "_src"] = n
        txt[name + "_by_file"] = byf
        counts[name] = {"src": n, "by_file": byf}
    ctx = {"txt": txt, "counts": counts, "cfg": cfg}
    R = cfg["src_ranges"]

    def rt(name):
        return range_text(R[name])

    # ---- 冻结**区间**测量（口径与整仓计数不同：作用域 = 冻结行区间）----
    for name, spec in cfg.get("range_expectations", {}).items():
        txt[name] = len(re.findall(spec["pattern"], rt(spec["range"])))

    # ---- 轴 A3：归属声明文本（V06）----
    rc_text = read_file(os.path.join(ROOT, cfg["src_locations"]["rc"]))
    txt["attr_phrase_count"] = len(re.findall(r"本标签（及 \{@code FactorResearchService\}）的", rc_text))
    txt["rc_javadoc_formula_text"] = rt("rc_javadoc_formula")
    txt["rc_labeldef_text"] = rt("rc_labeldef")

    # ---- 轴 A3/A4：实现侧「乘性费用步数」逐站点计数（V07）----
    def steps(range_name):
        t = rt(range_name)
        return len(re.findall(r"\*\s*\(1\s*\+", t)), len(re.findall(r"\*\s*\(1\s*[-−]", t))

    for site in ("frs_site_1", "frs_site_2", "lfs_site_1", "lfs_site_2"):
        e, x = steps(site)
        txt[site + "_entry_mult"] = e
        txt[site + "_exit_mult"] = x
    # 声明侧：每一侧只写一个「括号内加性」费用因子 ⟹ 1 步
    decl = txt["rc_javadoc_formula_text"] + "\n" + txt["rc_labeldef_text"]
    txt["declared_entry_additive_steps"] = 1 if re.search(r"\(1 ?\+ ?commission ?\+ ?slippage", decl) else 0
    txt["declared_exit_additive_steps"] = 1 if re.search(r"\(1 ?[-−] ?sell_tax ?[-−] ?slippage", decl) else 0

    # ---- 轴 A4：发射面（V08/V09）----
    txt["emitters_total"] = counts["C07"]["src"]
    multi_files = {"FactorResearchService.java": counts["C06"]["src"],
                   "LabelFactoryService.java": counts["C05"]["src"]}
    txt["emitters_with_colocated_multiplicative"] = sum(
        1 for f, n in multi_files.items() if n > 0)
    txt["pcs_samples_from_lfs"] = 1 if re.search(
        r"LabelSnapshotRepository", rt("collector_whole")) else 0
    txt["doc_api_additive_clause"] = counts["C11"]["src"]
    txt["vue_bilateral_commission"] = counts["C12"]["src"]

    # ---- 轴 A5：窗口原点（V11）----
    for name in ("rc_exit_freetext", "doc_conv_freetext"):
        txt["freetext_" + name] = rt(name)
    origin_rx = re.compile(r"entry day|原点|索引|index|偏移|第 ?h ?\+ ?1|next trading day|t\+1 \+ h")
    txt["free_text_origin_declared"] = sum(
        1 for name in ("rc_exit_freetext", "doc_conv_freetext")
        if origin_rx.search(txt["freetext_" + name]))
    txt["structured_origin_declared"] = 1 if "entry day + h" in rt("sbs_holding_semantics") else 0
    txt["holding_period_semantics_text"] = rt("sbs_holding_semantics")

    # ---- 轴 A7 / X02：原文备查 ----
    txt["sbs_persist_text"] = rt("sbs_persist_rates")
    txt["sbs_catch_text"] = rt("sbs_catch")
    txt["sbs_impl_entry_text"] = rt("sbs_impl_entry")
    txt["sbs_impl_exit_text"] = rt("sbs_impl_exit")
    txt["frs_note_text"] = rt("frs_note")
    txt["rctest_labeldef_text"] = rt("rctest_labeldef")
    txt["rctest_constants_text"] = rt("rctest_constants")

    # ---- 轴 A10：研究仪器口径（V18）----
    txt["r001_additive"] = counts["C13"]["src"]
    txt["r001_frozen_total"] = count_pattern(
        "research/paper_loop/experiments/R001_reversal", [".py"], r"FROZEN_TOTAL")[0]
    txt["r003_frozen_total"] = counts["C14"]["src"]
    txt["r003_additive"] = count_pattern(
        "research/paper_loop/experiments/R003_cost_breakeven", [".py"],
        r"1\.0 - SELL_TAX - SLIPPAGE")[0]
    return txt


# ---------------------------------------------------------------------------
# 门 / 锚点 / 网格自检
# ---------------------------------------------------------------------------
def gate_g3(ctx, cfg):
    src = str(P(ctx, "HARNESS", "class_code_source", ""))
    norm = src.replace("\\", "/")
    for pre in ("file:///", "file://", "file:/"):
        if norm.startswith(pre):
            norm = norm[len(pre):]
    norm = norm.rstrip("/")
    ok = norm.endswith("target/classes") and os.path.exists(CLASSES_DIR)
    ctx["g3_norm"] = norm
    return 1 if ok else 0


def probe_errors(ctx):
    out = []
    for sec, kv in ctx["probe"].items():
        for k in kv:
            if k == "error" or k.endswith("_error"):
                out.append("%s:%s=%s" % (sec, k, kv[k]))
    return out


def calibrate_lines(ctx, cfg):
    """冻结区间起点 vs 文本锚点实测算得的行号。锚点可带第三元素「起始行提示」以消解同文件重复文本。"""
    rows = []
    for name, anchors in cfg["line_anchors"].items():
        fkey, rx = anchors[0], anchors[1]
        hint = anchors[2] if len(anchors) > 2 else 1
        rng = cfg["src_ranges"][name]
        path = os.path.join(ROOT, cfg["src_locations"][fkey])
        actual = find_line(read_file(path), rx, hint)
        frozen = rng[1]
        rows.append({"range": name, "file": fkey, "frozen_start": frozen, "hint": hint,
                     "actual_start": actual, "delta": (actual - frozen) if actual > 0 else None})
    return rows


def grid_selfcheck(ctx, cfg):
    fed = P(ctx, "V19", "grid_fed", [])
    rows = cfg["price_grid"]["rows"]
    code, ex, vol = cfg["price_grid"]["code"], cfg["price_grid"]["exchange"], cfg["price_grid"]["volume"]
    frozen = ["%s|%s|%s|%s|%s|%s|%s" % (code, ex, r[0], r[1], r[2], r[3], vol) for r in rows]
    if not isinstance(fed, list):
        return {"fed": fed, "frozen": frozen, "match": 0, "mismatch": len(frozen), "diff": ["fed 非列表"]}
    match = sum(1 for a, b in zip(fed, frozen) if a == b)
    diff = [{"idx": i, "fed": a, "frozen": b} for i, (a, b) in enumerate(zip(fed, frozen)) if a != b]
    return {"fed": fed, "frozen": frozen, "match": match,
            "mismatch": len(frozen) - match + abs(len(fed) - len(frozen)), "diff": diff}


# ---------------------------------------------------------------------------
# 19 个 checker（每个返回 dict：word / fail_word / decider / keys）
# ---------------------------------------------------------------------------
TOL = 1e-15
TOL_INT = 0
TOL_BP = 1e-12
TH_BP = 1.0


def _res(name, word, fail_word, decider, keys):
    return {"name": name, "word": word, "fail_word": fail_word,
            "decider": decider, "keys": keys}


def ck_V01(ctx, cfg):
    keys = ["V01:residual_max", "V01:g_residual", "V01:f_residual_h2", "V01:f_residual_h3",
            "V01:skip_reason_h2", "V01:skip_reason_h3", "V01:ret_h2", "V01:ret_h3",
            "V01:price_source_h2", "V01:price_source_h3",
            "V01:g_impl_measured", "V01:f_impl_measured_h2"]
    rm = num(ctx, "V01", "residual_max")
    gr = num(ctx, "V01", "g_residual")
    fr2 = num(ctx, "V01", "f_residual_h2")
    fr3 = num(ctx, "V01", "f_residual_h3")
    sr2 = P(ctx, "V01", "skip_reason_h2")
    sr3 = P(ctx, "V01", "skip_reason_h3")
    r2 = num(ctx, "V01", "ret_h2")
    r3 = num(ctx, "V01", "ret_h3")
    ps2 = P(ctx, "V01", "price_source_h2")
    ps3 = P(ctx, "V01", "price_source_h3")
    gm = num(ctx, "V01", "g_impl_measured")
    fm2 = num(ctx, "V01", "f_impl_measured_h2")
    blk = derive_block()
    ok = (rm is not None and rm <= TOL and gr is not None and gr <= TOL
          and fr2 is not None and fr2 <= TOL and fr3 is not None and fr3 <= TOL
          and sr2 == "" and sr3 == "" and r2 is not None and r3 is not None
          and ps2 and ps3
          and gm is not None and abs(gm - float(blk["g_impl"])) <= TOL
          and fm2 is not None and abs(fm2 - float(blk["f_impl"])) <= TOL)
    return _res("V01", "NOT_VIOLATED" if ok else "VIOLATED", "VIOLATED",
                {"residual_max": rm, "g_residual": gr, "f_residual_h2": fr2, "f_residual_h3": fr3,
                 "skip_h2": sr2, "skip_h3": sr3, "ret_h2": r2, "ret_h3": r3,
                 "price_source_h2": ps2, "price_source_h3": ps3,
                 "g_impl_measured": gm, "g_impl_frozen": str(blk["g_impl"]),
                 "f_impl_measured_h2": fm2, "f_impl_frozen": str(blk["f_impl"]),
                 "TOL": TOL}, keys)


def ck_V02(ctx, cfg):
    keys = ["V02:residual_max", "V02:f_obs_minus_1", "V02:g_obs_minus_1"]
    rm = num(ctx, "V02", "residual_max")
    fm = num(ctx, "V02", "f_obs_minus_1")
    gm = num(ctx, "V02", "g_obs_minus_1")
    # 反事实「成本从收益率里减」⟹ 反解因子应为 1.0；此处要求二者都显著 ≠ 1
    ok = (rm is not None and rm <= TOL and fm is not None and gm is not None
          and abs(fm) > 1e-9 and abs(gm) > 1e-9)
    return _res("V02", "NOT_VIOLATED" if ok else "VIOLATED", "VIOLATED",
                {"residual_max": rm, "f_obs_minus_1": fm, "g_obs_minus_1": gm,
                 "counterfactual_excluded_by": "|f_obs-1|,|g_obs-1| > 1e-9 (编排器自算，不读探针布尔)"},
                keys)


def ck_V03(ctx, cfg):
    keys = ["V03:f_doc", "V03:f_impl"]
    fD = dec(ctx, "V03", "f_doc")
    fI = dec(ctx, "V03", "f_impl")
    blk = derive_block()
    c, t, s = blk["c"], blk["t"], blk["s"]
    ev = {"f_doc": str(fD), "f_impl": str(fI), "frozen_f_doc": str(blk["f_doc"]),
          "frozen_f_impl": str(blk["f_impl"])}
    if fD is None or fI is None:
        return _res("V03", "NOT_VIOLATED", "NOT_VIOLATED", dict(ev, missing=True), keys)
    fdiff = fI - fD
    identity = abs(fdiff - (-c + s * (c + t)))
    share = abs(-c) / abs(fdiff) if fdiff != 0 else Decimal(0)
    ev.update({"f_diff_runner": str(fdiff), "identity_residual": str(identity),
               "neg_c": str(-c), "cross_s_ct": str(s * (c + t)),
               "main_term_share_neg_c": str(share),
               "frozen_delta_bp": str(blk["delta_bp"])})
    ok = (fdiff == blk["f_impl"] - blk["f_doc"]
          and identity <= Decimal("1e-30")
          and fdiff < 0 and abs(fdiff) > Decimal("1e-15")
          and abs(share - 1) < Decimal("0.001"))
    return _res("V03", "VIOLATED" if ok else "NOT_VIOLATED", "NOT_VIOLATED", ev, keys)


def ck_V04(ctx, cfg):
    keys = ["V04:g_doc", "V04:g_impl", "V03:f_doc", "V03:f_impl"]
    gD = dec(ctx, "V04", "g_doc")
    gI = dec(ctx, "V04", "g_impl")
    fD = dec(ctx, "V03", "f_doc")
    fI = dec(ctx, "V03", "f_impl")
    blk = derive_block()
    ev = {"g_doc": str(gD), "g_impl": str(gI), "frozen_g_doc": str(blk["g_doc"]),
          "frozen_g_impl": str(blk["g_impl"])}
    if None in (gD, gI, fD, fI):
        return _res("V04", "NOT_VIOLATED", "NOT_VIOLATED", dict(ev, missing=True), keys)
    gdiff = gI - gD
    fdiff = fI - fD
    identity = abs(gdiff - blk["s"] * blk["c"])
    ev.update({"g_diff_runner": str(gdiff), "identity_residual": str(identity),
               "cross_s_c": str(blk["s"] * blk["c"]),
               "f_diff_runner": str(fdiff),
               "sign_f": ("+" if fdiff > 0 else "-" if fdiff < 0 else "0"),
               "sign_g": ("+" if gdiff > 0 else "-" if gdiff < 0 else "0")})
    ok = (identity <= Decimal("1e-30") and gdiff != 0 and abs(gdiff) > Decimal("1e-15")
          and fdiff != 0 and (fdiff > 0) != (gdiff > 0))
    return _res("V04", "VIOLATED" if ok else "NOT_VIOLATED", "NOT_VIOLATED", ev, keys)


def ck_V05(ctx, cfg):
    keys = ["V03:net_doc", "V03:net_impl"]
    nD = dec(ctx, "V03", "net_doc")
    nI = dec(ctx, "V03", "net_impl")
    blk = derive_block()
    ev = {"net_doc": str(nD), "net_impl": str(nI)}
    if nD is None or nI is None:
        return _res("V05", "NOT_VIOLATED", "NOT_VIOLATED", dict(ev, missing=True), keys)
    absp = abs((nI - nD) * 10000)
    margin = absp - Decimal(str(TH_BP))
    ev.update({"abs_delta_bp_runner": str(absp), "th_bp": str(TH_BP),
               "margin_bp": str(margin), "frozen_abs_delta_bp": str(abs(blk["delta_bp"]))})
    ok = absp > Decimal(str(TH_BP)) and margin > 0
    return _res("V05", "CONTRACT_MISLABEL" if ok else "NOT_VIOLATED", "NOT_VIOLATED", ev, keys)


def ck_V06(ctx, cfg):
    keys = ["attr_phrase_count"]
    n = T(ctx, "attr_phrase_count")
    return _res("V06", "NOT_VIOLATED" if n == 1 else "VIOLATED", "VIOLATED",
                {"attr_phrase_count": n, "expect": 1}, keys)


def ck_V07(ctx, cfg):
    """归属声明的**内容**实测：javadoc 的公式是否忠实描述被判归到它名下的那 4 个站点。

    判定量**全部**来自文本测量：
      声明侧 — 加性合成（`(1 + commission + slippage)`）且卖出因子写作 `(1 - sell_tax - slippage)`（**不含**佣金）；
      实现侧 — 4 个站点全部是**两级乘性**合成（measured `*_mult == 2`），且卖出因子 4/4 含佣金。
    `numeric_entry_residual` / `numeric_exit_residual`（+6e-8 / −2.9984e-4）由**冻结常量**算得，
    按 prereg §5.2「对照值不充当被审计量」只作旁证、**不**入判定式：它们在冻结后是常数，
    一旦入式 checker 就对注入免疫——R010 第 3 次运行正是被 §5.3 的金丝雀当场抓住这一点。
    """
    sites = ["frs_site_1", "frs_site_2", "lfs_site_1", "lfs_site_2"]
    keys = (["%s_entry_mult" % s for s in sites] + ["%s_exit_mult" % s for s in sites]
            + ["%s_exit_commission_step" % s for s in sites]
            + ["declared_entry_additive_steps", "declared_exit_additive_steps",
               "rc_declared_exit_factor_excludes_commission"])
    em = {s: T(ctx, s + "_entry_mult") for s in sites}
    xm = {s: T(ctx, s + "_exit_mult") for s in sites}
    ce = {s: T(ctx, s + "_exit_commission_step") for s in sites}
    de = T(ctx, "declared_entry_additive_steps")
    dx = T(ctx, "declared_exit_additive_steps")
    decl_excl = T(ctx, "rc_declared_exit_factor_excludes_commission")
    blk = derive_block()
    resid_e = abs(blk["g_impl"] - blk["g_doc"])
    resid_x = abs(blk["f_impl"] - blk["f_doc"])
    declared_multiplicative = (de == 0 and dx == 0)
    declared_exit_includes_commission = (decl_excl == 0)
    impl_multiplicative = all(v == 2 for v in list(em.values()) + list(xm.values()))
    impl_exit_includes_commission = all(v == 1 for v in ce.values())
    attribution_holds = bool(declared_multiplicative and declared_exit_includes_commission
                             and impl_multiplicative and impl_exit_includes_commission)
    ev = {"site_entry_mult": em, "site_exit_mult": xm, "site_exit_commission_step": ce,
          "declared_entry_additive_steps": de, "declared_exit_additive_steps": dx,
          "declared_exit_factor_excludes_commission": decl_excl,
          "declared_multiplicative": int(declared_multiplicative),
          "declared_exit_includes_commission": int(declared_exit_includes_commission),
          "impl_multiplicative": int(impl_multiplicative),
          "impl_exit_includes_commission": int(impl_exit_includes_commission),
          # 旁证（常量派生，非判定量）：见 docstring 与 prereg §5.2
          "numeric_entry_residual_supporting": str(resid_e),
          "numeric_exit_residual_supporting": str(resid_x),
          "numeric_supporting_is_decider": 0,
          "attribution_holds": int(attribution_holds),
          "frozen_f_impl": str(blk["f_impl"]), "frozen_f_doc": str(blk["f_doc"])}
    return _res("V07", "NOT_VIOLATED" if attribution_holds else "VIOLATED", "NOT_VIOLATED", ev, keys)


def ck_V08(ctx, cfg):
    keys = ["C07_src", "emitters_with_colocated_multiplicative", "pcs_samples_from_lfs"]
    n = T(ctx, "C07_src")
    col = T(ctx, "emitters_with_colocated_multiplicative")
    pcs = T(ctx, "pcs_samples_from_lfs")
    ok = (n == 3 and col == 2 and pcs == 1)
    return _res("V08", "VIOLATED" if ok else "NOT_VIOLATED", "NOT_VIOLATED",
                {"emission_sites": n, "emiters_total_expect": 3,
                 "colocated_multiplicative_files": col, "colocated_expect": 2,
                 "pcs_samples_via_label_snapshot": pcs,
                 "C07_by_file": T(ctx, "C07_by_file")}, keys)


def ck_V09(ctx, cfg):
    keys = ["doc_api_additive_clause", "vue_bilateral_commission"]
    a = T(ctx, "doc_api_additive_clause")
    b = T(ctx, "vue_bilateral_commission")
    ok = bool(a and a >= 1 and b and b >= 1)
    return _res("V09", "CONVENTION_DIVERGENCE" if ok else "NOT_VIOLATED", "NOT_VIOLATED",
                {"doc_api_additive_clause": a, "vue_bilateral_commission": b}, keys)


def ck_V10(ctx, cfg):
    keys = ["V10:entry_idx", "V10:fwd_idx_h2", "V10:fwd_idx_h3",
            "V10:grid_ambiguity_margin_h2", "V10:grid_ambiguity_margin_h3"]
    ei = num(ctx, "V10", "entry_idx")
    f2 = num(ctx, "V10", "fwd_idx_h2")
    f3 = num(ctx, "V10", "fwd_idx_h3")
    m2 = num(ctx, "V10", "grid_ambiguity_margin_h2")
    m3 = num(ctx, "V10", "grid_ambiguity_margin_h3")
    ev = {"entry_idx": ei, "fwd_idx_h2": f2, "fwd_idx_h3": f3,
          "h2_minus_entry": (None if None in (ei, f2) else f2 - ei),
          "h3_minus_entry": (None if None in (ei, f3) else f3 - ei),
          "grid_ambiguity_margin_h2": m2, "grid_ambiguity_margin_h3": m3,
          "close_recovered_h2": P(ctx, "V10", "close_recovered_h2"),
          "close_recovered_h3": P(ctx, "V10", "close_recovered_h3")}
    ok = (ei is not None and f2 == ei + 2 and f3 == ei + 3
          and m2 is not None and m2 > 0 and m3 is not None and m3 > 0)
    return _res("V10", "NOT_VIOLATED" if ok else "CONVENTION_DIVERGENCE",
                "CONVENTION_DIVERGENCE", ev, keys)


def ck_V11(ctx, cfg):
    keys = ["free_text_origin_declared", "structured_origin_declared"]
    a = T(ctx, "free_text_origin_declared")
    b = T(ctx, "structured_origin_declared")
    ok = (a == 0 and b == 1)
    return _res("V11", "CONVENTION_DIVERGENCE" if ok else "NOT_VIOLATED", "NOT_VIOLATED",
                {"free_text_origin_declared": a, "structured_origin_declared": b,
                 "free_text_samples": [T(ctx, "freetext_rc_exit_freetext"),
                                       T(ctx, "freetext_doc_conv_freetext")],
                 "structured_text": T(ctx, "holding_period_semantics_text")}, keys)


def ck_V12(ctx, cfg):
    keys = ["V12:rc_commission", "V12:rc_sell_tax", "V12:rc_slippage",
            "V12:req_commission_default", "V12:req_sell_tax_default", "V12:req_slippage_default",
            "sbs_defaults_rate_literals", "sbs_persist_rate_literals"]
    got = {k.split(":", 1)[1]: P(ctx, "V12", k.split(":", 1)[1]) for k in keys if k.startswith("V12:")}
    d1 = T(ctx, "sbs_defaults_rate_literals")
    d2 = T(ctx, "sbs_persist_rate_literals")
    want = {"rc_commission": "0.0003", "rc_sell_tax": "0.0005", "rc_slippage": "0.0002",
            "req_commission_default": "0.0003", "req_sell_tax_default": "0.0005",
            "req_slippage_default": "0.0002"}
    bad = [k for k, v in want.items() if str(got.get(k)) != v]
    ok = (not bad) and d1 == 3 and d2 == 3
    ev = dict(got)
    ev.update({"source_count": 4, "fallback_literal_counts": {"sbs_80_82": d1, "sbs_507_509": d2},
               "mismatches": bad})
    return _res("V12", "NOT_VIOLATED" if ok else "CONTRACT_AMBIGUOUS", "CONTRACT_AMBIGUOUS", ev, keys)


def ck_V13(ctx, cfg):
    keys = ["rctest_labeldef_call", "rctest_labeldef_has_arith", "rc_constants_pinned",
            "C15_src", "C16_src"]
    call = T(ctx, "rctest_labeldef_call")
    arith = T(ctx, "rctest_labeldef_has_arith")
    pinned = T(ctx, "rc_constants_pinned")
    c15 = T(ctx, "C15_src")
    c16 = T(ctx, "C16_src")
    ok = (pinned == 3 and c15 == 1 and arith == 0 and c16 == 0)
    return _res("V13", "REGISTERED_ONLY" if ok else "NOT_VIOLATED", "NOT_VIOLATED",
                {"constants_pinned_assertions": pinned, "tests_calling_labeldef": c15,
                 "deciding_test_has_arith": arith,
                 "tree_formula_arithmetic_literals": c16,
                 "rctest_labeldef_text": T(ctx, "rctest_labeldef_text")}, keys)


def ck_V14(ctx, cfg):
    cells = [("null", None, 0.0003), ("NaN", float("nan"), 0.0003),
             ("+Inf", float("inf"), 0.0003), ("-1.0", -1.0, 0.0),
             ("0.06", 0.06, 0.05), ("0.0001", 0.0001, 0.0001), ("0.05", 0.05, 0.05)]
    keys = ["V14:obs_%s" % c[0] for c in cells]
    rows = []
    allok = True
    for tag, arg, expect in cells:
        got = num(ctx, "V14", "obs_" + tag)
        # 编排器按**规则**独立判定（不读探针自报的 ok_* 布尔）
        ok = (got is not None and abs(got - expect) <= 0.0)
        allok = allok and ok
        rows.append({"cell": tag, "arg_repr": repr(arg), "expect": expect, "observed": got, "ok": ok})
    return _res("V14", "NOT_VIOLATED" if allok else "CONVENTION_DIVERGENCE",
                "CONVENTION_DIVERGENCE",
                {"cells": rows, "cells_n": len(cells), "rule": "[0,0.05] 且非有限⟹默认值"},
                keys)


def ck_V15(ctx, cfg):
    keys = ["V15:bounded_006_value", "sbs_persist_has_bounded", "V15:bigdecimal_006_str",
            "V15:bigdecimal_nan_throws", "sbs_catch_sets_resultid_null",
            "V15:assumptions_has_clamp_marker", "V15:assumptions_commission_value"]
    clamped = num(ctx, "V15", "bounded_006_value")
    persist_bounded = T(ctx, "sbs_persist_has_bounded")
    bd = P(ctx, "V15", "bigdecimal_006_str")
    nan_throws = num(ctx, "V15", "bigdecimal_nan_throws")
    swallow = T(ctx, "sbs_catch_sets_resultid_null")
    marker = num(ctx, "V15", "assumptions_has_clamp_marker")
    assump_c = fnum(P(ctx, "V15", "assumptions_commission_value"))
    bd_val = None
    try:
        bd_val = float(bd)
    except (TypeError, ValueError):
        pass
    leg1 = (clamped == 0.05)
    leg2 = (persist_bounded == 0)
    leg3 = (bd_val == 0.06 and bd_val != 0.05)
    leg4 = (nan_throws == 1 and swallow == 1)
    leg5 = (marker == 0 and assump_c == 0.05)
    ok = leg1 and leg2 and leg3 and leg4 and leg5
    ev = {"leg1_clamped_006_is_005": int(leg1), "bounded_006_value": clamped,
          "leg2_persist_path_has_no_boundedRate": int(leg2), "persist_boundedRate_count": persist_bounded,
          "leg3_jdk_bigdecimal_valueOf_006": bd, "leg3_value": bd_val,
          "leg4_bigdecimal_nan_throws": nan_throws, "leg4_catch_swallows_to_null": swallow,
          "leg5_payload_has_clamp_marker": marker, "leg5_payload_commission": assump_c,
          "honest_boundary": "不端到端观测 persistRun（需 Spring 容器与 DB）；由 1 次反射实测 + 1 次源码文本 + 1 次 JDK 行为推导",
          "assumptions_rate_entries": P(ctx, "V15", "assumptions_rate_entries")}
    return _res("V15", "VIOLATED" if ok else "NOT_VIOLATED", "NOT_VIOLATED", ev, keys)


def ck_V16(ctx, cfg):
    keys = ["V16:empty_net_n_keys", "V16:net_n_keys", "V16:empty_net_has_monotonicity",
            "V16:net_has_monotonicity"]
    e = num(ctx, "V16", "empty_net_n_keys")
    n = num(ctx, "V16", "net_n_keys")
    em = num(ctx, "V16", "empty_net_has_monotonicity")
    nm = num(ctx, "V16", "net_has_monotonicity")
    ok = (e == 14 and n == 15 and em == 0 and nm == 1)
    ev = {"empty_net_n_keys": e, "net_n_keys": n,
          "empty_net_has_monotonicity": em, "net_has_monotonicity": nm,
          "cross_round": "同 R009 A4c 的既存事实；本轮只登记跨轮可见",
          "net_keys_sorted": P(ctx, "V16", "net_keys_sorted"),
          "empty_net_keys_sorted": P(ctx, "V16", "empty_net_keys_sorted")}
    return _res("V16", "REGISTERED_ONLY" if ok else "NOT_VIOLATED", "NOT_VIOLATED", ev, keys)


def ck_V17(ctx, cfg):
    grid = cfg["arith"]["frozen_oat"]
    keys = ["V17:delta_bp_%s" % g[0] for g in grid] + ["V17:margin_%s" % g[0] for g in grid]
    blk = derive_block()
    neg = 0
    margins = []
    distinct = set()
    rows = []
    parse_ok = True
    for tag, c, t, s, _bp, _mg in grid:
        bp = dec(ctx, "V17", "delta_bp_" + tag)
        if bp is None:
            parse_ok = False
            continue
        cc, tt, ss = Decimal(c), Decimal(t), Decimal(s)
        lhs = ss * tt * (1 + 2 * cc + 2 * ss)
        rhs = cc * (1 + cc - ss - cc * ss)
        margin = rhs / lhs
        rows.append({"tag": tag, "delta_bp": str(bp), "margin": str(margin)})
        if bp < 0:
            neg += 1
        margins.append(margin)
        distinct.add(str(bp))
    min_margin = min(margins) if margins else None
    ok = (parse_ok and len(rows) == 9 and neg == 9 and len(distinct) == 7
          and min_margin is not None and min_margin > 1)
    ev = {"cells": len(rows), "negative_count": neg, "expect_negative": 9,
          "distinct_delta_bp": len(distinct), "expect_distinct": 7,
          "min_margin_ratio": (str(min_margin) if min_margin else None),
          "expect_min_margin": cfg["arith"]["frozen_min_margin"],
          "margin_minus_one": (str(min_margin - 1) if min_margin else None),
          "rows": rows, "baseline_delta_bp": str(blk["delta_bp"])}
    return _res("V17", "NOT_VIOLATED" if ok else "CONVENTION_DIVERGENCE",
                "CONVENTION_DIVERGENCE", ev, keys)


def ck_V18(ctx, cfg):
    keys = ["C13_src", "r001_frozen_total", "C14_src", "r003_additive"]
    a = T(ctx, "C13_src")
    af = T(ctx, "r001_frozen_total")
    b = T(ctx, "C14_src")
    ba = T(ctx, "r003_additive")
    ok = (a == 1 and af == 0 and b == 5 and ba == 0)
    return _res("V18", "REGISTERED_ONLY" if ok else "NOT_VIOLATED", "NOT_VIOLATED",
                {"r001_additive_sites": a, "r001_FROZEN_TOTAL": af,
                 "r003_FROZEN_TOTAL": b, "r003_additive_sites": ba,
                 "note": "只登记；不据此外推或重算 R001/R003 的任何数字"}, keys)


def ck_V19(ctx, cfg):
    keys = ["V19:det_equal", "V19:det_len_1", "V19:det_len_2", "V19:det_canonical_1",
            "V19:det_canonical_2", "gate:det_interprocess_ok", "gate:grid_mismatch_count",
            "gate:g3"]
    de = num(ctx, "V19", "det_equal")
    l1 = num(ctx, "V19", "det_len_1")
    l2 = num(ctx, "V19", "det_len_2")
    c1 = P(ctx, "V19", "det_canonical_1")
    c2 = P(ctx, "V19", "det_canonical_2")
    di = num(ctx, "V19", "det_first_diff_index", -1)
    inter = ctx["gate"].get("det_interprocess_ok")
    gm = ctx["gate"].get("grid_mismatch_count")
    g3 = ctx["gate"].get("g3")
    ok = (de == 1 and l1 is not None and l2 is not None and l1 == l2
          and c1 is not None and c1 == c2 and di is not None and di < 0
          and inter == 1 and gm == 0 and g3 == 1)
    return _res("V19", "NOT_VIOLATED" if ok else "INCONCLUSIVE", "INCONCLUSIVE",
                {"det_equal": de, "det_len_1": l1, "det_len_2": l2,
                 "canonical_equal": int(c1 == c2), "det_first_diff_index": di,
                 "det_interprocess_ok": inter, "grid_mismatch_count": gm, "g3": g3,
                 "det_canonical_len": (len(c1) if isinstance(c1, str) else None)}, keys)


CHECKERS = {"V01": ck_V01, "V02": ck_V02, "V03": ck_V03, "V04": ck_V04, "V05": ck_V05,
            "V06": ck_V06, "V07": ck_V07, "V08": ck_V08, "V09": ck_V09, "V10": ck_V10,
            "V11": ck_V11, "V12": ck_V12, "V13": ck_V13, "V14": ck_V14, "V15": ck_V15,
            "V16": ck_V16, "V17": ck_V17, "V18": ck_V18, "V19": ck_V19}


# ---------------------------------------------------------------------------
# 金丝雀（19 对）
# ---------------------------------------------------------------------------
def canary_ops(cfg):
    """每个变体：cln = 把 checker 实际读取的**全部键**写成预注册期望值；inj = 毒化决定性键。"""
    ops = {}
    ops["V01"] = {
        "cln": ("multi", [("p", "V01", "residual_max", 5e-17), ("p", "V01", "g_residual", 5e-17),
                          ("p", "V01", "f_residual_h2", 5e-17), ("p", "V01", "f_residual_h3", 5e-17),
                          ("p", "V01", "skip_reason_h2", ""), ("p", "V01", "skip_reason_h3", ""),
                          ("p", "V01", "ret_h2", 0.0983), ("p", "V01", "ret_h3", 0.0983),
                          ("p", "V01", "price_source_h2", "RAW"), ("p", "V01", "price_source_h3", "RAW"),
                          ("p", "V01", "g_impl_measured", 1.00050006),
                          ("p", "V01", "f_impl_measured_h2", 0.99900016)]),
        "inj": ("p", "V01", "residual_max", 1e-3)}
    ops["V02"] = {
        "cln": ("multi", [("p", "V02", "residual_max", 5e-17),
                          ("p", "V02", "f_obs_minus_1", -0.00099984),
                          ("p", "V02", "g_obs_minus_1", 0.00050006)]),
        "inj": ("p", "V02", "f_obs_minus_1", 0.0)}
    ops["V03"] = {
        "cln": ("multi", [("p", "V03", "f_doc", "0.9993"), ("p", "V03", "f_impl", "0.99900016")]),
        "inj": ("p", "V03", "f_impl", "0.9993")}
    ops["V04"] = {
        "cln": ("multi", [("p", "V04", "g_doc", "1.0005"), ("p", "V04", "g_impl", "1.00050006"),
                          ("p", "V03", "f_doc", "0.9993"), ("p", "V03", "f_impl", "0.99900016")]),
        "inj": ("p", "V04", "g_impl", "0.99899994")}
    ops["V05"] = {
        "cln": ("multi", [("p", "V03", "net_doc", "0.09868065967016491754122938530734632683658170914542728635682"),
                          ("p", "V03", "net_impl", "0.09835093463162810804828937241642944029408653908526502237291")]),
        "inj": ("multi", [("p", "V03", "net_doc", "0.1"), ("p", "V03", "net_impl", "0.1")])}
    ops["V06"] = {"cln": ("t", "attr_phrase_count", 1), "inj": ("t", "attr_phrase_count", 0)}
    ops["V07"] = {
        # CLN = checker 实际读取的**全部** 15 个键的现实值（4 站点 × 3 类 + 2 个声明侧 + 1 个 javadoc 卖出因子）
        "cln": ("multi", [("t", "frs_site_1_entry_mult", 2), ("t", "frs_site_1_exit_mult", 2),
                          ("t", "frs_site_2_entry_mult", 2), ("t", "frs_site_2_exit_mult", 2),
                          ("t", "lfs_site_1_entry_mult", 2), ("t", "lfs_site_1_exit_mult", 2),
                          ("t", "lfs_site_2_entry_mult", 2), ("t", "lfs_site_2_exit_mult", 2),
                          ("t", "frs_site_1_exit_commission_step", 1),
                          ("t", "frs_site_2_exit_commission_step", 1),
                          ("t", "lfs_site_1_exit_commission_step", 1),
                          ("t", "lfs_site_2_exit_commission_step", 1),
                          ("t", "declared_entry_additive_steps", 1),
                          ("t", "declared_exit_additive_steps", 1),
                          ("t", "rc_declared_exit_factor_excludes_commission", 1)]),
        # INJ：把声明侧改写成「与实现同构且卖出含佣金」⟹ 归属成立 ⟹ NOT_VIOLATED（≠ 期望 VIOLATED）
        "inj": ("multi", [("t", "declared_entry_additive_steps", 0),
                          ("t", "declared_exit_additive_steps", 0),
                          ("t", "rc_declared_exit_factor_excludes_commission", 0)])}
    ops["V08"] = {
        "cln": ("multi", [("t", "C07_src", 3),
                          ("t", "emitters_with_colocated_multiplicative", 2),
                          ("t", "pcs_samples_from_lfs", 1)]),
        "inj": ("t", "C07_src", 2)}
    ops["V09"] = {
        "cln": ("multi", [("t", "doc_api_additive_clause", 1),
                          ("t", "vue_bilateral_commission", 1)]),
        "inj": ("t", "vue_bilateral_commission", 0)}
    ops["V10"] = {
        "cln": ("multi", [("p", "V10", "entry_idx", 1), ("p", "V10", "fwd_idx_h2", 3),
                          ("p", "V10", "fwd_idx_h3", 4),
                          ("p", "V10", "grid_ambiguity_margin_h2", 0.4),
                          ("p", "V10", "grid_ambiguity_margin_h3", 0.4)]),
        "inj": ("p", "V10", "fwd_idx_h3", 5)}
    ops["V11"] = {
        "cln": ("multi", [("t", "free_text_origin_declared", 0),
                          ("t", "structured_origin_declared", 1)]),
        "inj": ("t", "free_text_origin_declared", 1)}
    ops["V12"] = {
        "cln": ("multi", [("p", "V12", "rc_commission", "0.0003"),
                          ("p", "V12", "rc_sell_tax", "0.0005"),
                          ("p", "V12", "rc_slippage", "0.0002"),
                          ("p", "V12", "req_commission_default", "0.0003"),
                          ("p", "V12", "req_sell_tax_default", "0.0005"),
                          ("p", "V12", "req_slippage_default", "0.0002"),
                          ("t", "sbs_defaults_rate_literals", 3),
                          ("t", "sbs_persist_rate_literals", 3)]),
        "inj": ("p", "V12", "req_commission_default", "0.0004")}
    ops["V13"] = {
        "cln": ("multi", [("t", "rctest_labeldef_call", 1), ("t", "rctest_labeldef_has_arith", 0),
                          ("t", "rc_constants_pinned", 3), ("t", "C15_src", 1), ("t", "C16_src", 0)]),
        "inj": ("t", "C16_src", 1)}
    ops["V14"] = {
        # 7 个键必须全部写成探针**实测**值：obs_null 的实测量是 0.0003（= 默认值），
        # 不是字符串 "null"（那会被 fnum 归一为 None ⟹ 判失败，第 3 次运行的 CLN 缺陷）
        "cln": ("multi", [("p", "V14", "obs_null", 0.0003), ("p", "V14", "obs_NaN", 0.0003),
                          ("p", "V14", "obs_+Inf", 0.0003), ("p", "V14", "obs_-1.0", 0.0),
                          ("p", "V14", "obs_0.06", 0.05), ("p", "V14", "obs_0.0001", 0.0001),
                          ("p", "V14", "obs_0.05", 0.05)]),
        "inj": ("p", "V14", "obs_0.06", 0.06)}
    ops["V15"] = {
        "cln": ("multi", [("p", "V15", "bounded_006_value", 0.05),
                          ("t", "sbs_persist_has_bounded", 0),
                          ("p", "V15", "bigdecimal_006_str", "0.06"),
                          ("p", "V15", "bigdecimal_nan_throws", 1),
                          ("t", "sbs_catch_sets_resultid_null", 1),
                          ("p", "V15", "assumptions_has_clamp_marker", 0),
                          ("p", "V15", "assumptions_commission_value", 0.05)]),
        "inj": ("p", "V15", "bounded_006_value", 0.06)}
    ops["V16"] = {
        "cln": ("multi", [("p", "V16", "empty_net_n_keys", 14), ("p", "V16", "net_n_keys", 15),
                          ("p", "V16", "empty_net_has_monotonicity", 0),
                          ("p", "V16", "net_has_monotonicity", 1)]),
        "inj": ("p", "V16", "empty_net_has_monotonicity", 1)}
    # CLN 必须逐格写**各自的**实测值：9 格里有 3 格同值（c/t/s 的 ×1.0 基线）⟹ distinct 恰为 7。
    # 第 3 次运行把所有格写同一个值 ⟹ distinct=1 ⟹ CLN 判失败。
    _cln_ops = [("p", "V17", "delta_bp_" + r["tag"], r["delta_bp"])
                for r in cfg["arith"]["oat_recomputed"]]
    ops["V17"] = {"cln": ("multi", _cln_ops), "inj": ("p", "V17", "delta_bp_c*0.8", "+0.0001")}
    ops["V18"] = {
        "cln": ("multi", [("t", "C13_src", 1), ("t", "r001_frozen_total", 0),
                          ("t", "C14_src", 5), ("t", "r003_additive", 0)]),
        "inj": ("t", "C13_src", 0)}
    ops["V19"] = {
        "cln": ("multi", [("p", "V19", "det_equal", 1), ("p", "V19", "det_len_1", 100),
                          ("p", "V19", "det_len_2", 100),
                          ("p", "V19", "det_canonical_1", "X"), ("p", "V19", "det_canonical_2", "X"),
                          ("p", "V19", "det_first_diff_index", -1),
                          ("g", "det_interprocess_ok", 1), ("g", "grid_mismatch_count", 0),
                          ("g", "g3", 1)]),
        "inj": ("g", "grid_mismatch_count", 1)}
    return ops


def apply_ops(ctx, op):
    kind = op[0]
    if kind == "multi":
        for sub in op[1]:
            apply_ops(ctx, sub)
    elif kind == "p":
        ctx["probe"].setdefault(op[1], {})[op[2]] = op[3]
    elif kind == "t":
        ctx["txt"][op[1]] = op[2]
    elif kind == "g":
        ctx["gate"][op[1]] = op[2]
    else:
        raise ValueError("未知 op 类型: %s" % kind)


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def main():
    cfg = json.load(open(CONFIG_PATH, encoding="utf-8"))
    log("")
    log("=" * 78)
    log("R010 运行开始  round=%s  baseline_commit=%s" % (cfg["round"], cfg["baseline_commit"]))
    log("=" * 78)

    ctx = {"cfg": cfg, "probe": {}, "probe2": {}, "txt": {}, "counts": {}, "gate": {}}

    # ---- 0) 依赖钉死 ----
    missing = check_deps()
    ctx["gate"]["deps_missing"] = missing
    ctx["gate"]["deps_ok"] = 0 if missing else 1
    log("[0] 依赖钉死 12 jar：缺失 %d" % len(missing))
    for m in missing:
        log("    缺失: " + m)

    # ---- 1) Java 测试套 + 反向锚点 3 ----
    rc, out, err = maven_test()
    log("[1] mvn -o -B test -pl modules/backend-java  rc=%d" % rc)
    log_block("maven stdout 尾部", "\n".join(out.splitlines()[-25:]))
    if err.strip():
        log_block("maven stderr 尾部", "\n".join(err.splitlines()[-15:]))
    totals, per_class = surefire_summary()
    rct = per_class.get("com.quant.research.ResearchConventionTest")
    ctx["gate"]["java_totals"] = totals
    ctx["gate"]["research_convention_test"] = rct
    sentinel3 = 1 if (rc == 0 and rct and rct["failures"] == 0 and rct["errors"] == 0
                      and rct["tests"] > 0) else 0
    ctx["gate"]["sentinel_existing_test_input_ok"] = sentinel3
    log("    surefire: classes=%d tests=%d failures=%d errors=%d skipped=%d ; "
        "ResearchConventionTest=%s ; sentinel3=%d"
        % (totals["classes"], totals["tests"], totals["failures"], totals["errors"],
           totals["skipped"], rct, sentinel3))

    # ---- 2) 编译探针 ----
    cp = os.pathsep.join(dep_paths() + [CLASSES_DIR])
    rc, out, err = compile_probe(cp)
    log("[2] javac 探针 rc=%d" % rc)
    if out.strip():
        log_block("javac stdout", out)
    if err.strip():
        log_block("javac stderr", err)
    if rc != 0:
        ctx["gate"]["compile_ok"] = 0
        log("!! 探针编译失败 ⟹ 无法继续（K9）")
    else:
        ctx["gate"]["compile_ok"] = 1

    # ---- 3) 两次独立 JVM 运行 ----
    rc1, so1, se1 = run_probe(cp, "1")
    log("[3] 探针 run#1 rc=%d stdout_lines=%d" % (rc1, len(so1.splitlines())))
    log_block("run#1 stdout (全量)", so1)
    if se1.strip():
        log_block("run#1 stderr", se1)
    rc2, so2, se2 = run_probe(cp, "2")
    log("[3] 探针 run#2 rc=%d stdout_lines=%d" % (rc2, len(so2.splitlines())))
    log_block("run#2 stdout (全量)", so2)
    if se2.strip():
        log_block("run#2 stderr", se2)

    ctx["probe"], bad1 = parse_probe(so1)
    ctx["probe2"], bad2 = parse_probe(so2)
    ctx["gate"]["probe_rc"] = [rc1, rc2]
    ctx["gate"]["probe_parse_bad"] = bad1 + bad2
    ctx["gate"]["det_interprocess_ok"] = 1 if (so1 == so2 and so1.strip()) else 0
    ctx["gate"]["det_interprocess_diff_index"] = (
        -1 if so1 == so2 else next((i for i, (a, b) in enumerate(zip(so1, so2)) if a != b),
                                   min(len(so1), len(so2))))
    log("    进程间逐字符比对: det_interprocess_ok=%d  len1=%d len2=%d  first_diff=%s"
        % (ctx["gate"]["det_interprocess_ok"], len(so1), len(so2),
           ctx["gate"]["det_interprocess_diff_index"]))

    # ---- 4) 源码文本测量 ----
    ctx["txt"] = text_measurements(cfg)
    ctx["counts"] = {k: {"src": v, "by_file": ctx["txt"].get(k + "_by_file")}
                     for k, v in cfg["count_expectations"].items()}
    log("[4] 文本测量（建议性计数 vs 冻结期望；不匹配**不**直接改变判定词）")
    for name in sorted(cfg["count_expectations"]):
        spec = cfg["count_expectations"][name]
        got = ctx["txt"].get(name + "_src")
        flag = "OK " if got == spec["src"] else "DIF"
        log("    %s %-4s expect=%s got=%s by_file=%s  %s"
            % (flag, name, spec["src"], got, ctx["txt"].get(name + "_by_file"), spec["desc"][:44]))
    log("    -- 冻结区间测量（作用域 = 冻结行区间）--")
    for name in sorted(cfg.get("range_expectations", {})):
        spec = cfg["range_expectations"][name]
        got = ctx["txt"].get(name)
        log("    %s %-30s expect=%s got=%s  %s"
            % ("OK " if got == spec["expect"] else "DIF", name, spec["expect"], got,
               spec["desc"][:40]))

    # ---- 5) 门 ----
    ctx["gate"]["g3"] = gate_g3(ctx, cfg)
    gs = grid_selfcheck(ctx, cfg)
    ctx["gate"]["grid_selfcheck"] = gs
    ctx["gate"]["grid_mismatch_count"] = gs["mismatch"]
    perr = probe_errors(ctx)
    ctx["gate"]["probe_errors"] = perr
    ctx["gate"]["a2_extra"] = 0
    ctx["gate"]["sentinel_impl_matches_prod"] = int(
        fnum(P(ctx, "V01", "g_residual"), 1) <= TOL
        and fnum(P(ctx, "V01", "f_residual_h2"), 1) <= TOL
        and fnum(P(ctx, "V01", "f_residual_h3"), 1) <= TOL
        and fnum(P(ctx, "V01", "residual_max"), 1) <= TOL)
    blk = derive_block()
    nd = dec(ctx, "V03", "net_doc")
    ctx["gate"]["sentinel_doc_recomputable"] = int(
        nd is not None and abs(nd - blk["net_doc"]) <= Decimal("1e-15"))
    ctx["gate"]["sentinel_clamp_visible"] = int(
        fnum(P(ctx, "V14", "obs_0.06")) == 0.05
        and fnum(P(ctx, "V14", "obs_0.06")) != 0.06)
    log("[5] 门：g3=%d(code_source=%s) | grid match=%d mismatch=%d | probe_errors=%d"
        % (ctx["gate"]["g3"], ctx.get("g3_norm"), gs["match"], gs["mismatch"], len(perr)))
    log("    反向锚点：impl_matches_prod=%d doc_recomputable=%d existing_test_input_ok=%d clamp_visible=%d"
        % (ctx["gate"]["sentinel_impl_matches_prod"], ctx["gate"]["sentinel_doc_recomputable"],
           ctx["gate"]["sentinel_existing_test_input_ok"], ctx["gate"]["sentinel_clamp_visible"]))
    for e in perr:
        log("    !! 探针错误: " + e)
    if gs["diff"]:
        log_block("grid 差异", json.dumps(gs["diff"], ensure_ascii=False, indent=1))

    # ---- 6) 行号校准（附录，不改判定）----
    cal = calibrate_lines(ctx, cfg)
    ctx["gate"]["line_calibration"] = cal
    log("[6] 行号校准（冻结 vs 实测锚点；不一致只记附录）")
    for row in cal:
        mark = "OK " if row["delta"] == 0 else ("n/a" if row["delta"] is None else "D%d" % row["delta"])
        log("    %-3s %-26s frozen=%s actual=%s delta=%s"
            % (mark, row["range"], row["frozen_start"], row["actual_start"], row["delta"]))

    # ---- 7) 判定 ----
    verdicts = {}
    evidence = {}
    for name in sorted(CHECKERS, key=lambda x: int(x[1:])):
        r = CHECKERS[name](ctx, cfg)
        verdicts[name] = r["word"]
        evidence[name] = r
    ctx["verdicts"] = verdicts
    ctx["expected"] = cfg["expected"]

    log("[7] 判定（实测）")
    dev = []
    for name in sorted(verdicts, key=lambda x: int(x[1:])):
        exp = cfg["expected"][name]
        mark = "OK " if verdicts[name] == exp else "DEV"
        if verdicts[name] != exp:
            dev.append(name)
        log("    %s %s 实测=%-22s 预注册=%-22s 决定量=%s"
            % (mark, name, verdicts[name], exp,
               json.dumps(evidence[name]["decider"], ensure_ascii=False, default=str)[:260]))

    # ---- 8) 金丝雀 ----
    ops = canary_ops(cfg)
    canary = {}
    log("[8] 金丝雀 CLN/INJ（19 对）")
    for name in sorted(ops, key=lambda x: int(x[1:])):
        row = {"intent": cfg["canaries"][name]["intent"]}
        for tag in ("cln", "inj"):
            c2 = json.loads(json.dumps(ctx, default=str)) if False else None
            # 深拷贝：probe/txt/gate 三层都需可回滚
            snap = (json.loads(json.dumps(ctx["probe"], default=str)),
                    dict(ctx["txt"]), dict(ctx["gate"]))
            apply_ops(ctx, ops[name][tag])
            r = CHECKERS[name](ctx, cfg)
            row[tag] = r["word"]
            ctx["probe"], ctx["txt"], ctx["gate"] = snap
        row["expect_cln"] = cfg["canaries"][name]["clean"]
        row["expect_inj"] = cfg["canaries"][name]["inj"]
        row["pass"] = int(row["cln"] == row["expect_cln"] and row["inj"] == row["expect_inj"]
                          and row["cln"] != row["inj"])
        canary[name] = row
        log("    %s %s CLN=%-22s(期望 %-22s) INJ=%-22s(期望 %-22s)"
            % ("OK " if row["pass"] else "FAIL", name, row["cln"], row["expect_cln"],
               row["inj"], row["expect_inj"]))
    ctx["gate"]["canary_fail"] = [k for k, v in canary.items() if not v["pass"]]

    # ---- 9) 杀死条件与短路 ----
    v = verdicts
    K = {}
    K["K1"] = (v["V03"] == "VIOLATED") or (v["V04"] == "VIOLATED")
    K["K2"] = (v["V07"] == "VIOLATED")
    K["K3"] = (v["V08"] == "VIOLATED")
    K["K4"] = (v["V05"] == "CONTRACT_MISLABEL")
    K["K5"] = (v["V15"] == "VIOLATED")
    K["K6"] = (v["V12"] == "CONTRACT_AMBIGUOUS")
    K["K7"] = any(w in ("REGISTERED_ONLY", "NOT_REACHABLE") for w in v.values())
    row_sum = sum(len(cfg["axes"][a]) for a in cfg["axes"])
    frozen_ok = (row_sum == cfg["frozen_total"] == 19
                 and {a: len(cfg["axes"][a]) for a in cfg["axes"]} == cfg["frozen_axis_counts"])
    K["K8"] = (not frozen_ok) or gs["mismatch"] > 0
    out_of_vocab = [k for k, w in v.items() if w not in VERDICTS]
    K["K9"] = bool(canary and ctx["gate"]["canary_fail"]) or not all(
        ctx["gate"][s] == 1 for s in ("sentinel_impl_matches_prod", "sentinel_doc_recomputable",
                                      "sentinel_existing_test_input_ok", "sentinel_clamp_visible")) \
        or ctx["gate"]["g3"] != 1 or bool(ctx["gate"]["deps_missing"]) \
        or bool(ctx["gate"]["probe_errors"]) or bool(out_of_vocab)
    K["K10"] = (v["V19"] != "NOT_VIOLATED")
    if not canary:
        K["K9"] = True
    if K["K8"] or K["K9"] or K["K10"]:
        total = "INCONCLUSIVE"
    elif K["K1"] or K["K2"] or K["K3"]:
        total = "REJECTED"
    elif K["K4"] or K["K5"]:
        total = "CONTRACT_MISLABEL"
    elif K["K6"]:
        total = "CONTRACT_AMBIGUOUS"
    elif K["K7"]:
        total = "REGISTERED_ONLY"
    else:
        total = "NOT_VIOLATED"
    h0_alive = not (K["K1"] or K["K2"] or K["K3"])
    log("[9] 杀死条件 " + json.dumps(K, ensure_ascii=False))
    log("    total_verdict=%s  h0_alive=%s  行和=%d frozen_ok=%s 表外词=%s"
        % (total, h0_alive, row_sum, frozen_ok, out_of_vocab))

    # ---- 10) result.json ----
    result = {
        "round": cfg["round"], "baseline_commit": cfg["baseline_commit"],
        "subject": cfg["subject"], "judgment_source": cfg["judgment_source"],
        "unit_of_judgment": "variant", "variants_total": len(v),
        "axes": cfg["axes"], "axis_counts": {a: len(cfg["axes"][a]) for a in cfg["axes"]},
        "row_sum": row_sum, "expected": cfg["expected"],
        "verdicts": verdicts, "deviations": dev,
        "kill_conditions": K, "total_verdict": total, "h0_alive": h0_alive,
        "non_killing_axes_frozen": cfg["non_killing_axes_frozen"],
        "non_killing_variants": cfg["non_killing_variants"],
        "k7_floor_triggered_by": [k for k, w in v.items() if w == "REGISTERED_ONLY"],
        "gates": {k: val for k, val in ctx["gate"].items() if k != "grid_selfcheck"},
        "grid_selfcheck": gs,
        "line_calibration": cal,
        "canaries": canary,
        "count_expectations_observed": {
            k: {"expect": cfg["count_expectations"][k]["src"],
                "got": ctx["txt"].get(k + "_src"),
                "by_file": ctx["txt"].get(k + "_by_file"),
                "match": ctx["txt"].get(k + "_src") == cfg["count_expectations"][k]["src"]}
            for k in cfg["count_expectations"]},
        "evidence": evidence,
        "evidence_extra": {
            "X01_additive_notation_sites": {
                "entry_additive_notation": ctx["txt"].get("C08_src"),
                "exit_additive_notation": ctx["txt"].get("C09_src"),
                "by_file_C08": ctx["txt"].get("C08_by_file"),
                "by_file_C09": ctx["txt"].get("C09_by_file")},
            "X02_ascii_additive_comment": ctx["txt"].get("frs_ascii_additive_comment"),
            "frs_note_text": ctx["txt"].get("frs_note_text"),
        },
    }
    with open(RESULT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, default=str)
    log("[10] 写出 %s" % RESULT_PATH)

    # ---- 11) 清理 ----
    if os.path.isdir(PROBE_OUT):
        shutil.rmtree(PROBE_OUT, ignore_errors=True)
    removed = cleanup_probe()
    log("[11] 已清理 probe/out；已从 target/classes 移除探针字节码 %d 个" % len(removed))
    log("R010 运行结束 total_verdict=%s h0_alive=%s" % (total, h0_alive))
    print("")
    print("total_verdict = %s" % total)
    print("h0_alive      = %s" % h0_alive)
    print("deviations    = %s" % dev)
    print("canary_fail   = %s" % ctx["gate"]["canary_fail"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
