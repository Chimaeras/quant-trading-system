#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R009 Pearson 侧 IC 载荷族审计（pearson_ic_mean / pearsonCorrelation / dailyPearson）
—— 只读编排器。

判据来源：Shin et al., arXiv:2401.02710v2，见 rounds/R009_review.md。
判定词表、L/A/P 分档、阈值、网格、18 轴断言、杀死条件 K1–K10、计数断言 C1–C20
在 rounds/R009_prereg.md 中**于本文件创建之前冻结**；本文件只是冻结文本的可执行实现。

硬约束（协议 §7 + 本轮 prereg §3.1/§9）：
  * 只读：仅 1 条只读聚合 SELECT（A3c）；其余 17 个变体 0 条 SQL。
  * 不改任何生产代码 / 测试 / 前端 / 契约 / ResearchConvention / 数据库结构。
  * 发现一律只登记队列，不修。
  * 断言对象必须是 modules/backend-java/target/classes 下的**生产类**（门 G3）。
  * 探针里的 control_* 只作对照值，且与被审计量同输入并列打印。
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
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
PROBE_SRC = os.path.join(HERE, "probe", "R009Probe.java")
PROBE_OUT = os.path.join(HERE, "probe", "out")
CLASSES = os.path.join(REPO, "modules", "backend-java", "target", "classes")
PROBE_MAIN = "com.quant.service.backtest.R009Probe"
CONFIG_PATH = os.path.join(HERE, "config.json")
LOG_PATH = os.path.join(HERE, "run.log")
RESULT_PATH = os.path.join(HERE, "result.json")

M2_REPO = os.path.join(os.path.expanduser("~"), ".m2", "repository")
# 继承 R008 的 HARNESS 依赖钉死（版本取自 spring-boot-starter-parent 3.2.0 对应 BOM）
DEPS_PINNED = [
    ("org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar", "slf4j-api（@Slf4j 的 LoggerFactory）"),
    ("org/springframework/spring-core/6.1.1/spring-core-6.1.1.jar", "spring-core"),
    ("org/springframework/spring-beans/6.1.1/spring-beans-6.1.1.jar", "spring-beans"),
    ("org/springframework/spring-context/6.1.1/spring-context-6.1.1.jar", "spring-context（@Service）"),
    ("org/springframework/spring-tx/6.1.1/spring-tx-6.1.1.jar", "spring-tx"),
    ("org/springframework/spring-aop/6.1.1/spring-aop-6.1.1.jar", "spring-aop"),
    ("org/springframework/data/spring-data-commons/3.2.0/spring-data-commons-3.2.0.jar",
     "spring-data-commons（Repository/Page/Pageable）"),
    ("org/springframework/data/spring-data-jpa/3.2.0/spring-data-jpa-3.2.0.jar",
     "spring-data-jpa（JpaRepository）"),
    ("jakarta/persistence/jakarta.persistence-api/3.1.0/jakarta.persistence-api-3.1.0.jar",
     "jakarta.persistence-api（实体注解）"),
]

VERDICTS = ["NOT_VIOLATED", "VIOLATED", "CONVENTION_DIVERGENCE", "CONTRACT_AMBIGUOUS",
            "CONTRACT_MISLABEL", "REGISTERED_ONLY", "NOT_REACHABLE", "INCONCLUSIVE"]

EXTS_ALL = (".java", ".vue", ".ts", ".js", ".sql", ".py", ".md", ".json", ".yml")
SKIP_DIRS = {"target", "dist", "node_modules", "__pycache__", ".git", "build", "out"}


# ===========================================================================
# 0) 日志（append-only）
# ===========================================================================
def log(msg):
    line = "[%s] %s" % (datetime.now().strftime("%Y-%m-%d %H:%M:%S"), msg)
    print(line)
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(line + "\n")


# ===========================================================================
# 1) 只读文件走查（口径与 R007/R008 完全一致：斜杠归一化）
# ===========================================================================
def walk_files(root, exts=None, include_build=False):
    for dp, dns, fns in os.walk(root):
        if include_build:
            dns[:] = [d for d in dns if d != ".git"]
        else:
            dns[:] = [d for d in dns if d not in SKIP_DIRS]
        for fn in fns:
            if exts and not fn.endswith(tuple(exts)):
                continue
            if not exts and not fn.endswith(EXTS_ALL):
                continue
            yield os.path.join(dp, fn)


def read_lines(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


_TEXT_CACHE = {}


def _cached_texts(root, exts, include_build):
    """按 (root, exts, include_build) 缓存一次遍历 + 读取；语义与逐模式重走完全一致。"""
    key = (root, exts, include_build)
    if key not in _TEXT_CACHE:
        items = []
        for p in walk_files(root, exts, include_build):
            try:
                items.append((p.replace(os.sep, "/"),
                              open(p, encoding="utf-8", errors="replace").read()))
            except Exception:
                continue
        _TEXT_CACHE[key] = items
    return _TEXT_CACHE[key]


def count_pattern(root, pattern, exts=None, include_build=False):
    """返回 (总数, {归一化路径: 次数})。"""
    rx = re.compile(pattern)
    total, by_file = 0, {}
    for path, txt in _cached_texts(root, exts, include_build):
        c = len(rx.findall(txt))
        if c:
            by_file[path] = c
            total += c
    return total, by_file


def by_basename(by_file, name):
    return sum(v for k, v in by_file.items() if k.endswith("/" + name) or k == name)


def range_text(path, lo, hi):
    lines = read_lines(path)
    return "\n".join(lines[lo - 1:hi])


def range_hits(path, lo, hi, pattern):
    return len(re.findall(pattern, range_text(path, lo, hi)))


# ===========================================================================
# 2) 编译并运行探针
# ===========================================================================
DEP_CACHE = {"cp": "", "missing": [], "jars": []}


def resolve_deps():
    jars, missing = [], []
    for rel, why in DEPS_PINNED:
        p = os.path.join(M2_REPO, *rel.split("/"))
        if os.path.isfile(p):
            jars.append(p)
        else:
            missing.append("%s（%s）" % (rel, why))
    return jars, missing


def dep_classpath():
    if not DEP_CACHE["cp"]:
        jars, missing = resolve_deps()
        DEP_CACHE.update({"jars": jars, "missing": missing, "cp": os.pathsep.join(jars)})
    return DEP_CACHE["cp"]


def compile_and_run_probe():
    if not os.path.isdir(CLASSES):
        log("FAIL: 生产类目录不存在：%s（需先构建 backend-java）" % CLASSES)
        return None, ""
    dep_cp = dep_classpath()
    if DEP_CACHE["missing"]:
        for m in DEP_CACHE["missing"]:
            log("FAIL: 依赖 jar 缺失（钉死版本）：%s" % m)
        log("FAIL: 依赖解析失败 ⟹ 探针不运行（不得静默改用其它版本）")
        return None, ""
    log("harness deps=%d jars（钉死于 Spring Boot 3.2.0 BOM）" % len(DEP_CACHE["jars"]))
    rt_cp = os.pathsep.join([PROBE_OUT, CLASSES, dep_cp])
    cc_cp = os.pathsep.join([CLASSES, dep_cp])
    if os.path.isdir(PROBE_OUT):
        shutil.rmtree(PROBE_OUT)
    os.makedirs(PROBE_OUT, exist_ok=True)

    jc = subprocess.run(["javac", "-encoding", "UTF-8", "-cp", cc_cp, "-d", PROBE_OUT, PROBE_SRC],
                        capture_output=True, text=True, errors="replace")
    log("javac rc=%d" % jc.returncode)
    if jc.stdout.strip():
        log("javac stdout: " + jc.stdout.strip())
    if jc.stderr.strip():
        log("javac stderr: " + jc.stderr.strip())
    if jc.returncode != 0:
        return None, ""
    os.remove(jc.args[-1]) if False else None

    jr = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", rt_cp, PROBE_MAIN],
                        capture_output=True, text=True, errors="replace")
    log("java rc=%d stdout_lines=%d" % (jr.returncode, len(jr.stdout.splitlines())))
    if jr.stderr.strip():
        log("java stderr: " + jr.stderr.strip().splitlines()[0][:200])
    if jr.returncode != 0:
        return None, jr.stderr
    return parse_probe(jr.stdout), jr.stderr


def parse_probe(stdout):
    probe, probe_idx, probe_list = {}, {}, {}
    for line in stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            o = json.loads(line)
        except Exception:
            continue
        p, k = o.get("probe"), o.get("key")
        if p is None or k is None:
            continue
        if "items" in o:
            probe_list.setdefault(p, {})[k] = o["items"]
        elif "i" in o:
            probe_idx.setdefault(p, {}).setdefault(k, {})[o["i"]] = o["value"]
        else:
            probe.setdefault(p, {})[k] = o["value"]
    return {"probe": probe, "probe_idx": probe_idx, "probe_list": probe_list}


# ===========================================================================
# 3) 文本侧测量（只读）
# ===========================================================================
def text_measurements(cfg):
    loc = {k: os.path.join(REPO, v) for k, v in cfg["src_locations"].items()}
    rng = {k: (loc[v[0]], v[1], v[2]) for k, v in cfg["src_ranges"].items()}
    out = {}

    # A5a：javadoc 声明 + 量级限定词
    jd = range_text(*rng["pearson_javadoc"])
    sjd = range_text(*rng["spearman_javadoc"])
    out["javadoc_pearson_text"] = jd.replace("\n", " ")
    out["javadoc_declares_zero_variance"] = 1 if "样本不足或方差为 0" in jd else 0
    out["javadoc_spearman_same_sentence"] = 1 if "样本不足或方差为 0" in sjd else 0
    mag_pat = r"1e-18|量级|尺度|容差|tolerance|scale|magnitude"
    out["javadoc_has_magnitude_word"] = 1 if re.search(mag_pat, jd + sjd) else 0
    out["javadoc_magnitude_hits"] = len(re.findall(mag_pat, jd + sjd))

    # 守卫与方差行（用于 F2/F1 的文本复核）
    out["guard_line_text"] = range_text(*rng["guard_lines"]).replace("\n", " ")
    out["guard_has_1e18"] = 1 if "1e-18" in range_text(*rng["guard_lines"]) else 0
    out["variance_lines_has_unnormalized"] = 1 if "vx += dx * dx" in range_text(*rng["variance_lines"]) else 0
    out["summarize_gate_isFinite"] = range_hits(rng["summarize_gate"][0], rng["summarize_gate"][1],
                                                 rng["summarize_gate"][2], r"DEFAULT_ISFINITE_PLACEHOLDER".replace(
                                                     "DEFAULT_ISFINITE_PLACEHOLDER", r"Double\.isFinite"))
    out["pearson_mean_ternary"] = 1 if "dailyPearson.isEmpty()" in range_text(*rng["pearson_mean_lines"]) else 0
    out["round4_text"] = range_text(*rng["round4_lines"]).replace("\n", " ")

    # A5b：legacy 空分支 vs 正常分支的键集（源码文本口径）
    le = range_text(*rng["legacy_empty_branch"])
    ln = range_text(*rng["legacy_normal_branch"])
    keys_empty = set(re.findall(r'block\.put\("([a-z_]+)"', le)) | set(re.findall(r'block\.put\("([a-z_]+)"', le))
    keys_normal = set(re.findall(r'block\.put\("([a-z_]+)"', ln))
    out["legacy_empty_keys"] = sorted(keys_empty)
    out["legacy_normal_keys"] = sorted(keys_normal)
    out["legacy_empty_missing_pearson"] = 0 if "pearson_ic_mean" in keys_empty else 1
    out["legacy_empty_missing_monotonicity"] = 0 if "monotonicity" in keys_empty else 1
    out["legacy_empty_missing_hac"] = 0 if "hac_std" in keys_empty else 1
    out["legacy_normal_has_pearson"] = 1 if "pearson_ic_mean" in keys_normal else 0
    svc_all = open(loc["service"], encoding="utf-8", errors="replace").read()
    out["legacy_empty_reachable"] = 1 if "if (latestPerKey.isEmpty())" in svc_all else 0
    out["legacy_empty_reachable_note"] = (
        "latestPerKey 来自 findByTradeDateBetweenOrderByCreatedAtDesc(...) 的区间查询 ⟹ "
        "区间内无 selector_result 时为空，该分支可达（生产语义）")

    # A5b：前端 TS 契约
    t1 = range_text(*rng["ts_pearson_required_1"])
    t2 = range_text(*rng["ts_pearson_required_2"])
    ts = t1 + "\n" + t2
    out["ts_pearson_required"] = 1 if re.search(r"pearson_ic_mean\s*:\s*number", ts) else 0
    out["ts_pearson_optional"] = 1 if re.search(r"pearson_ic_mean\s*\?", ts) else 0
    cnet = range_text(*rng["ts_costnet_block"])
    out["ts_costnet_block_text"] = cnet.replace("\n", " ")
    out["ts_costnet_has_monotonicity"] = 1 if re.search(r"monotonicity", cnet) else 0
    out["ts_costnet_has_status"] = 1 if re.search(r"status", cnet) else 0
    out["ts_costnet_has_pearson_required"] = 1 if re.search(r"pearson_ic_mean\s*:\s*number", cnet) else 0
    out["ts_costnet_has_daily_rank_ic"] = 1 if re.search(r"daily_rank_ic", cnet) else 0

    # A2c：判据来源摘录（PDF 转写的断词 `mea-\nsure` 必须归一化后再匹配；否则会假阴性）
    raw_review = open(loc["review"], encoding="utf-8", errors="replace").read()
    # 逐字引用块的行首 `> ` 前缀与软换行一并归一
    norm_review = re.sub(r"^>\s?", "", raw_review, flags=re.M)
    norm_review = re.sub(r"-\s*\n\s*", "", norm_review)          # 合并 PDF 断词
    norm_review = re.sub(r"\s+", " ", norm_review)
    need = ("use the Pearson correlation coefficient to measure the correlation "
            "between the target variable and alpha factor")
    out["review_criteria_jia_quote_present"] = 1 if need in norm_review else 0
    out["review_criteria_jia_needed"] = need
    out["review_criteria_jia_raw_present"] = (
        1 if need in re.sub(r"\s+", " ", raw_review.replace(">\n", " ")) else 0)
    out["review_rankic_spearman_quote_present"] = (
        1 if "Spearman rank correlation coefficient to measure rank-based correlations" in norm_review else 0)
    out["project_computes_score_vs_forward_return"] = (
        1 if re.search(r"new CrossSectionObservation\(score,\s*forwardReturn\)", svc_all) else 0)
    out["project_forward_return_is_price_ratio"] = (
        1 if re.search(r"closeExit\.doubleValue\(\)\s*/\s*closeSignal\.doubleValue\(\)\s*-\s*1\.0", svc_all) else 0)

    # A7a：既有测试是否覆盖尺度维度。判据是**测试输入**里有没有 1e- 量级的 scores/returns；
    # 断言容差（offset(1e-9)/isCloseTo(x, 1e-6)）**不是**输入，必须排除（否则假阳性）。
    et = range_text(*rng["engine_test_all"])
    input_exprs = re.findall(r"List\.of\([^)]*\)|new CrossSectionObservation\([^)]*\)", et, flags=re.S)
    inp_txt = "\n".join(input_exprs)
    out["test_input_expr_count"] = len(input_exprs)
    out["test_scale_input_hits"] = len(re.findall(r"1e-\d", inp_txt))
    out["test_scale_scaling_hits"] = len(re.findall(r"\*\s*1e-\d", et))
    out["test_tolerance_hits"] = len(re.findall(r"offset\(1e-\d|isCloseTo\([^,]+,\s*1e-\d", et))
    out["test_has_scale_dimension"] = (
        1 if (out["test_scale_input_hits"] > 0 or out["test_scale_scaling_hits"] > 0) else 0)
    out["test_pearson_cases"] = len(re.findall(r"pearsonCorrelation", et))

    # A7b：CSV 导出的 `pearson_ic` 列**取值表达式**是否含缺测/不可用分支。
    # 只查该列自己的表达式；`||` 守卫子句与 `cost_net_ic` 的 `??` 不属于该列（否则假阳性）。
    csv = range_text(*rng["csv_export"])
    out["csv_has_pearson_column"] = 1 if "pearson_ic" in csv else 0
    pearson_lines = [ln for ln in csv.splitlines() if "pearson_ic_mean" in ln or "'pearson_ic'" in ln]
    out["csv_pearson_expr_text"] = " | ".join(x.strip() for x in pearson_lines)
    branched = 0
    for ln in pearson_lines:
        if re.search(r"pearson_ic_mean\s*(\?\?|\|\|)", ln) or re.search(r"isFinite\([^)]*pearson_ic_mean", ln):
            branched = 1
    out["csv_export_has_missing_branch"] = branched
    out["csv_export_branch_hits"] = branched
    out["csv_scope_branch_hits_other_columns"] = len(
        re.findall(r"\?\?|\|\||isFinite|N/A|—|UNAVAILABLE", csv))

    # A6a：各发射点的支撑集定义标签 + 结构化消歧字段
    sites = [
        {"site": ":542 snapshot scope block", "support": "毛收益 close[t+h]/close[t]; scope=RAW",
         "has_metric_scope": 1, "has_basis_note": 1, "source": "runtime(reflection)"},
        {"site": ":568 unavailable scope block", "support": "不可用（status=UNAVAILABLE）",
         "has_metric_scope": 1, "has_basis_note": 1, "source": "runtime(reflection)"},
        {"site": ":761 legacy normal branch", "support": "毛收益 close[t+h]/close[t]; scope=RAW; "
                                                         "SOURCE_LEGACY_SELECTION_BIASED",
         "has_metric_scope": 1, "has_basis_note": 1, "source": "source-text"},
        {"site": ":810 empty net block", "support": "净收益 open[t+1]→close[t+1+h] 扣费; scope=COST_APPROX",
         "has_metric_scope": 1, "has_basis_note": 1, "source": "runtime(reflection)"},
        {"site": ":830 net block", "support": "净收益 open[t+1]→close[t+1+h] 扣费; scope=COST_APPROX",
         "has_metric_scope": 1, "has_basis_note": 1, "source": "runtime(reflection)"},
    ]
    out["a6a_sites"] = sites
    out["a6a_distinct_supports"] = len({s["support"] for s in sites})
    out["a6a_sites_lacking_structured_field"] = sum(
        1 for s in sites if not s["has_metric_scope"] and not s["has_basis_note"])
    return out, loc


# ===========================================================================
# 4) 计数断言 C1–C20
# ===========================================================================
def count_measurements(cfg):
    loc = cfg["src_locations"]
    out = {}
    for cid, spec in cfg["count_expectations"].items():
        scope = os.path.join(REPO, spec["scope"]) if spec["scope"] != "." else REPO
        exts = None if spec["exts"] == "ALL" else tuple(spec["exts"])
        t_src, by_src = count_pattern(scope, spec["pattern"], exts, include_build=False)
        rec = {"src": t_src, "by_file": {k: v for k, v in by_src.items()},
               "by_basename": {}}
        for name in spec.get("by_file", {}):
            rec["by_basename"][name] = by_basename(by_src, name)
        if cid in cfg["raw_measured"]:
            raw_scope = spec.get("raw_scope", spec["scope"])
            raw_root = os.path.join(REPO, raw_scope) if raw_scope != "." else REPO
            t_raw, _ = count_pattern(raw_root, spec["pattern"], exts, include_build=True)
            rec["raw"] = t_raw
        else:
            rec["raw"] = None
        out[cid] = rec
    return out


# ===========================================================================
# 5) A3c：唯一一条只读普查 SQL
# ===========================================================================
def resolve_spring_placeholder(v):
    """把 Spring 的 ${ENV:default} 解析为环境变量值或默认值（application.yml:13 的写法）。"""
    m = re.fullmatch(r"\$\{([A-Za-z0-9_]+)(?::([^}]*))?\}", v.strip())
    if not m:
        return v.strip()
    return os.environ.get(m.group(1), m.group(2) if m.group(2) is not None else "")


def datasource_candidates():
    """优先读 application.yml 的 datasource，其次用文档化的开发默认值。"""
    cands = []
    yml = os.path.join(REPO, "modules", "backend-java", "src", "main", "resources", "application.yml")
    m = {}
    if os.path.isfile(yml):
        txt = open(yml, encoding="utf-8", errors="replace").read()
        mu = re.search(r"url:\s*jdbc:mysql://([^:/?]+):(\d+)/([A-Za-z0-9_]+)", txt)
        mnp = re.search(r"username:\s*(\S+)", txt)
        mwp = re.search(r"password:\s*(\S+)", txt)
        if mu:
            cands.append({"host": mu.group(1), "port": int(mu.group(2)), "db": mu.group(3),
                          "user": (mnp.group(1) if mnp else "root"),
                          "pwd": resolve_spring_placeholder(mwp.group(1) if mwp else ""),
                          "origin": "application.yml（password 已解析 ${ENV:default} 占位）"})
    cands.append({"host": "127.0.0.1", "port": 3306, "db": "quant_db", "user": "root",
                  "pwd": "123456", "origin": "docs/安装指南.md 的开发默认值"})
    return cands


def query_a3c(cfg):
    rec = {"reachable": 0, "days_with_data": 0, "min_sxx": None, "max_sxx": None,
           "days_below_guard": None, "margin_decades": None, "rows": [], "error": None,
           "ds_origin": None, "n_rows_total": None}
    try:
        import pymysql
    except Exception as e:
        rec["error"] = "pymysql 不可用: %s" % e
        return rec
    for c in datasource_candidates():
        try:
            conn = pymysql.connect(host=c["host"], port=c["port"], user=c["user"],
                                   password=c["pwd"], database=c["db"], charset="utf8mb4",
                                   cursorclass=pymysql.cursors.Cursor, connect_timeout=6)
        except Exception as e:
            rec["error"] = "connect(%s@%s:%s/%s via %s): %s" % (
                c["user"], c["host"], c["port"], c["db"], c["origin"], str(e)[:160])
            continue
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) FROM information_schema.columns "
                            "WHERE table_schema=%s AND table_name='feature_snapshot_row' "
                            "AND column_name IN ('signal_date','final_score')",
                            (c["db"],))
                ncols = cur.fetchone()[0]
                if ncols < 2:
                    rec["error"] = "feature_snapshot_row 缺少 signal_date/final_score（实测 %d 列命中）" % ncols
                    conn.close()
                    return rec
                cur.execute(cfg["a3c_sql"])
                rows = cur.fetchall()
                cols = [d[0] for d in cur.description]
            conn.close()
        except Exception as e:
            rec["error"] = "query: %s" % str(e)[:200]
            try:
                conn.close()
            except Exception:
                pass
            continue
        rec["reachable"] = 1
        rec["ds_origin"] = c["origin"]
        rec["columns"] = cols
        rec["rows"] = [[str(r[0]), int(r[1]), int(r[2]),
                        (None if r[3] is None else float(r[3]))] for r in rows]
        scored = [r for r in rec["rows"] if r[2] and r[2] > 1]
        rec["days_with_data"] = len(scored)
        rec["n_rows_total"] = sum(r[1] for r in rec["rows"])
        if scored:
            sxx_vals = [r[3] for r in scored if r[3] is not None]
            if sxx_vals:
                rec["min_sxx"] = min(sxx_vals)
                rec["max_sxx"] = max(sxx_vals)
                guard = cfg["thresholds"]["GUARD_T"]
                rec["days_below_guard"] = sum(1 for v in sxx_vals if v < guard)
                import math
                rec["margin_decades"] = (math.log10(rec["min_sxx"] / guard)
                                         if rec["min_sxx"] > 0 else None)
        return rec
    return rec


# ===========================================================================
# 6) 门与自检
# ===========================================================================
def gate_g3(ctx):
    src = str(ctx["probe"].get("V01", {}).get("engine_code_source", ""))
    norm = src.replace("\\", "/").rstrip("/")
    return norm.endswith("target/classes"), norm


def check_panel_design(cfg, ctx):
    rows = []
    ok_all = True
    for variant, preds in cfg["panel_design"].items():
        for p in preds:
            cur = ctx
            for step in p["path"]:
                if isinstance(cur, dict) and step in cur:
                    cur = cur[step]
                else:
                    cur = None
                    break
            exp = p["expect"]
            ok = False
            note = ""
            if cur is None:
                note = "MISSING"
            elif isinstance(exp, str):
                ok = (str(cur) == exp)
            else:
                try:
                    cv, ev = float(cur), float(exp)
                    tol = p.get("tol", 0)
                    rel = p.get("rel")
                    if rel is not None:
                        ok = abs(cv - ev) <= rel * max(abs(ev), 1e-300)
                    else:
                        ok = abs(cv - ev) <= tol
                except Exception:
                    note = "CAST_FAIL"
            if not ok:
                ok_all = False
            rows.append({"variant": variant, "path": ".".join(p["path"]),
                         "actual": cur, "expect": exp, "pass": ok, "note": note})
    return ok_all, rows


# ===========================================================================
# 7) 18 个检查器（每个都只读 ctx）
# ===========================================================================
def fin(v):
    if isinstance(v, str):
        return v not in ("NaN", "+Inf", "-Inf")
    try:
        f = float(v)
        return f == f and abs(f) != float("inf")
    except Exception:
        return False


def num(v):
    if isinstance(v, str):
        return float("nan") if v == "NaN" else float("inf") if v == "+Inf" else (
            float("-inf") if v == "-Inf" else float(v))
    return float(v)


def ck_A1a(ctx):
    p = ctx["probe"].get("V01", {})
    g3 = ctx["gate"]["g3"]
    if g3 and p.get("sentinel_defect_present") == 1 and p.get("sentinel_normal_equals") == 1 \
            and p.get("existing_test_input_ok") == 1:
        return "NOT_VIOLATED"
    return "VIOLATED"


def ck_A1b(ctx):
    if ctx["src"].get("panel_selfcheck_ok") == 1 and ctx["src"].get("determinism_ok") == 1:
        return "NOT_VIOLATED"
    return "VIOLATED"


def ck_A2a(ctx):
    p = ctx["probe"]["V03"]
    tol = ctx["cfg"]["thresholds"]["TOL_RHO"]
    if (fin(p["rho_p1"]) and abs(num(p["rho_p1"]) - 1.0) <= tol
            and fin(p["rho_p1t"]) and abs(num(p["rho_p1t"]) - 1.0) <= tol
            and not fin(p["rho_p1s"])
            and num(p["var_p1s"]) > 0
            and fin(p["rank_p1s"])):
        return "VIOLATED"
    return "NOT_VIOLATED"


def ck_A2b(ctx):
    p = ctx["probe"]["V04"]
    tol = ctx["cfg"]["thresholds"]["TOL_RHO"]
    if (not fin(p["rho_n10"])
            and fin(p["rho_n12"]) and abs(num(p["rho_n12"]) - 1.0) <= 1e-9
            and num(p["var_n10"]) > 0 and num(p["var_n12"]) > 0):
        return "VIOLATED"
    return "NOT_VIOLATED"


def ck_A2c(ctx):
    s = ctx["src"]
    if s.get("review_criteria_jia_quote_present") == 1 \
            and s.get("project_computes_score_vs_forward_return") == 1 \
            and s.get("project_forward_return_is_price_ratio") == 1:
        return "NOT_VIOLATED"
    return "VIOLATED"


def ck_A2d(ctx):
    if ctx["probe"]["V06"].get("cells") == 15:
        return "NOT_VIOLATED"
    return "VIOLATED"


def ck_A3a(ctx):
    p = ctx["probe"]["V07"]
    c11 = ctx["counts"]["C11"]["src"]
    if p["support_gap"] > 0 and p["total_observations"] > 0 and c11 == 0:
        return "VIOLATED"
    return "NOT_VIOLATED"


def ck_A3b(ctx):
    return "REGISTERED_ONLY" if ctx["counts"]["C11"]["src"] == 0 else "NOT_VIOLATED"


def ck_A3c(ctx):
    d = ctx["db"]
    if d.get("reachable") == 1 and (d.get("days_with_data") or 0) >= 1:
        return "REGISTERED_ONLY"
    return "NOT_REACHABLE"


def ck_A4a(ctx):
    p = ctx["probe"]["V10"]
    same = p["payload_p4z"] == p["payload_p4u"]
    if (same and num(p["pearson_mean_p4u"]) == 0.0
            and p["cross_days_p4u"] > 0 and p["total_obs_p4u"] > 0
            and p["pearson_support_days_p4u"] == 0):
        return "VIOLATED"
    return "NOT_VIOLATED"


def ck_A4b(ctx):
    p = ctx["probe"]["V11"]
    if p.get("round4_nan_is_zero") != 1:
        return "NOT_VIOLATED"
    if p.get("undef_pearson_key_is_finite") == 1:
        return "NOT_REACHABLE"
    return "VIOLATED"


def ck_A4c(ctx):
    p = ctx["probe"]["V12"]
    divergent = ((p["unavailable_has_status"] == 1 and p["empty_net_has_status"] == 0)
                 or (p["unavailable_has_monotonicity"] == 1 and p["empty_net_has_monotonicity"] == 0))
    return "CONVENTION_DIVERGENCE" if divergent else "NOT_VIOLATED"


def ck_A5a(ctx):
    s = ctx["src"]
    if s.get("javadoc_declares_zero_variance") == 1 and s.get("javadoc_has_magnitude_word") == 0 \
            and ctx["a2_violated"] == 1:
        return "CONTRACT_MISLABEL"
    return "NOT_VIOLATED"


def ck_A5b(ctx):
    s = ctx["src"]
    if s.get("ts_pearson_required") == 1 and s.get("legacy_empty_missing_pearson") == 1 \
            and s.get("legacy_empty_reachable") == 1:
        return "VIOLATED"
    return "NOT_VIOLATED"


def ck_A6a(ctx):
    s = ctx["src"]
    sites = s.get("a6a_sites", [])
    if not sites:
        return "NOT_VIOLATED"
    distinct = len({x["support"] for x in sites})
    lacking = sum(1 for x in sites if not x["has_metric_scope"] and not x["has_basis_note"])
    if distinct >= 2 and lacking >= 2:
        return "CONTRACT_AMBIGUOUS"
    return "NOT_VIOLATED"


def ck_A6b(ctx):
    p = ctx["probe"]["V03"]
    if fin(p["rank_p1s"]) and not fin(p["rho_p1s"]):
        return "VIOLATED"
    return "NOT_VIOLATED"


def ck_A7a(ctx):
    return "NOT_VIOLATED" if ctx["src"].get("test_has_scale_dimension") == 1 else "REGISTERED_ONLY"


def ck_A7b(ctx):
    return "NOT_VIOLATED" if ctx["src"].get("csv_export_has_missing_branch") == 1 else "REGISTERED_ONLY"


CHECKERS = {
    "A1a": ck_A1a, "A1b": ck_A1b,
    "A2a": ck_A2a, "A2b": ck_A2b, "A2c": ck_A2c, "A2d": ck_A2d,
    "A3a": ck_A3a, "A3b": ck_A3b, "A3c": ck_A3c,
    "A4a": ck_A4a, "A4b": ck_A4b, "A4c": ck_A4c,
    "A5a": ck_A5a, "A5b": ck_A5b,
    "A6a": ck_A6a, "A6b": ck_A6b,
    "A7a": ck_A7a, "A7b": ck_A7b,
}


# ===========================================================================
# 8) 金丝雀（CLN / INJ 成对；每个检查器恰一对）
# op: ("pset", probe, key, value) | ("pidx", probe, key, i, value)
#     ("src_set", name, value)    | ("fn", probe, key, fn(ctx))
#     ("multi", [op, ...])
# ===========================================================================
def build_canary_ops(ctx):
    ops = {}
    ops["A1a"] = {"cln": ("multi", [("pset", "V01", "sentinel_defect_present", 1),
                                    ("pset", "V01", "sentinel_normal_equals", 1),
                                    ("pset", "V01", "existing_test_input_ok", 1),
                                    ("gate", "g3", 1)]),
                  "inj": ("gate", "g3", 0)}
    ops["A1b"] = {"cln": ("multi", [("src_set", "panel_selfcheck_ok", 1),
                                    ("src_set", "determinism_ok", 1)]),
                  "inj": ("src_set", "panel_selfcheck_ok", 0)}
    ops["A2a"] = {"cln": ("pset", "V03", "rho_p1s", 1.0),
                  "inj": ("pset", "V03", "rho_p1s", "NaN")}
    ops["A2b"] = {"cln": ("pset", "V04", "rho_n10", 1.0),
                  "inj": ("pset", "V04", "rho_n10", "NaN")}
    ops["A2c"] = {"cln": ("src_set", "review_criteria_jia_quote_present", 1),
                  "inj": ("src_set", "review_criteria_jia_quote_present", 0)}
    ops["A2d"] = {"cln": ("pset", "V06", "cells", 15),
                  "inj": ("pset", "V06", "cells", 14)}
    ops["A3a"] = {"cln": ("pset", "V07", "support_gap", 0),
                  "inj": ("pset", "V07", "support_gap", 1)}
    ops["A3b"] = {"cln": ("counts_set", "C11", "src", 1),
                  "inj": ("counts_set", "C11", "src", 0)}
    ops["A3c"] = {"cln": ("multi", [("db_set", "reachable", 1), ("db_set", "days_with_data", 1)]),
                  "inj": ("db_set", "reachable", 0)}
    ops["A4a"] = {"cln": ("fn", "V10", "payload_p4u", lambda c: c["probe"]["V10"]["payload_p4z"] + "|CLN"),
                  "inj": ("fn", "V10", "payload_p4u", lambda c: c["probe"]["V10"]["payload_p4z"])}
    ops["A4b"] = {"cln": ("pset", "V11", "round4_nan_is_zero", 0),
                  "inj": ("multi", [("pset", "V11", "round4_nan_is_zero", 1),
                                    ("pset", "V11", "undef_pearson_key_is_finite", 1)])}
    ops["A4c"] = {"cln": ("multi", [("pset", "V12", "empty_net_has_status", 1),
                                    ("pset", "V12", "empty_net_has_monotonicity", 1)]),
                  "inj": ("multi", [("pset", "V12", "empty_net_has_status", 0),
                                    ("pset", "V12", "empty_net_has_monotonicity", 0)])}
    ops["A5a"] = {"cln": ("src_set", "javadoc_has_magnitude_word", 1),
                  "inj": ("multi", [("src_set", "javadoc_has_magnitude_word", 0),
                                    ("set", "a2_violated", 1)])}
    ops["A5b"] = {"cln": ("src_set", "legacy_empty_missing_pearson", 0),
                  "inj": ("multi", [("src_set", "legacy_empty_missing_pearson", 1),
                                    ("src_set", "legacy_empty_reachable", 1),
                                    ("src_set", "ts_pearson_required", 1)])}
    ops["A6a"] = {"cln": ("fn", "src", "a6a_sites",
                          lambda c: [dict(s, support="同一口径", has_metric_scope=1) for s in c["src"]["a6a_sites"]]),
                  "inj": ("fn", "src", "a6a_sites",
                          lambda c: [{"site": "s1", "support": "A", "has_metric_scope": 0, "has_basis_note": 0},
                                     {"site": "s2", "support": "B", "has_metric_scope": 0, "has_basis_note": 0}])}
    ops["A6b"] = {"cln": ("pset", "V03", "rho_p1s", 1.0),
                  "inj": ("pset", "V03", "rho_p1s", "NaN")}
    ops["A7a"] = {"cln": ("src_set", "test_has_scale_dimension", 1),
                  "inj": ("src_set", "test_has_scale_dimension", 0)}
    ops["A7b"] = {"cln": ("src_set", "csv_export_has_missing_branch", 1),
                  "inj": ("src_set", "csv_export_has_missing_branch", 0)}
    return ops


def apply_op(ctx, op):
    kind = op[0]
    if kind == "pset":
        ctx["probe"].setdefault(op[1], {})[op[2]] = op[3]
    elif kind == "pidx":
        ctx["probe_idx"].setdefault(op[1], {}).setdefault(op[2], {})[op[3]] = op[4]
    elif kind == "src_set":
        ctx["src"][op[1]] = op[2]
    elif kind == "counts_set":
        ctx["counts"].setdefault(op[1], {})[op[2]] = op[3]
    elif kind == "db_set":
        ctx["db"][op[1]] = op[2]
    elif kind == "set":
        ctx[op[1]] = op[2]
    elif kind == "gate":
        ctx["gate"][op[1]] = op[2]
    elif kind == "fn":
        if op[1] == "src":
            ctx["src"][op[2]] = op[3](ctx)
        else:
            ctx["probe"].setdefault(op[1], {})[op[2]] = op[3](ctx)
    elif kind == "multi":
        for o in op[1]:
            apply_op(ctx, o)
    else:
        raise ValueError("unknown op " + kind)


# ===========================================================================
# 9) 主流程
# ===========================================================================
def main():
    log("=" * 78)
    log("R009 运行开始（配置 %s）" % CONFIG_PATH)
    cfg = json.load(open(CONFIG_PATH, encoding="utf-8"))

    result = {"round": "R009", "started_at": datetime.now().isoformat(timespec="seconds"),
              "baseline_commit": cfg["baseline_commit"]}

    # ---- 9.1 config 冻结自检（K8 的一部分）----
    axes_total = sum(len(v) for v in cfg["axes"].values())
    k8_parts = {}
    k8_parts["axis_total_is_18"] = (axes_total == cfg["frozen_total"] == cfg["max_variants"] == 18)
    k8_parts["axis_counts_match"] = (
        {k: len(v) for k, v in cfg["axes"].items()} == cfg["frozen_axis_counts"])
    k8_parts["variant_map_complete"] = (len(cfg["variant"]) == 18
                                        and set(cfg["variant"]) == {a for v in cfg["axes"].values() for a in v})
    k8_parts["checkers_match_axes"] = (sorted(cfg["checkers"]) == sorted(cfg["variant"]))
    k8_parts["canaries_cover_checkers"] = (sorted(cfg["canaries"]) == sorted(cfg["checkers"]))
    k8_parts["non_killing_axes_declared"] = sorted(cfg["non_killing_axes"]) == ["A2c", "A2d", "A3c", "A7a"]
    log("K8 config 自检: %s" % k8_parts)

    # ---- 9.2 计数断言 ----
    counts = count_measurements(cfg)
    count_rows = []
    for cid in sorted(cfg["count_expectations"], key=lambda x: int(x[1:])):
        exp = cfg["count_expectations"][cid]
        act = counts[cid]
        by_ok = all(act["by_basename"].get(k, 0) == v for k, v in exp.get("by_file", {}).items())
        raw_ok = True if act["raw"] is None else (act["raw"] == exp["raw"])
        ok = (act["src"] == exp["src"]) and by_ok and raw_ok
        count_rows.append({"id": cid, "expect_src": exp["src"], "actual_src": act["src"],
                           "expect_raw": exp["raw"], "actual_raw": act["raw"],
                           "by_file_expected": exp.get("by_file", {}),
                           "by_file_actual": act["by_basename"], "match": ok})
        if not ok:
            log("COUNT MISMATCH %s: expect src=%s raw=%s, actual src=%s raw=%s"
                % (cid, exp["src"], exp["raw"], act["src"], act["raw"]))
    log("C1–C20 计数断言：%d/%d 匹配" % (sum(1 for r in count_rows if r["match"]), len(count_rows)))
    result["counts"] = {"rows": count_rows,
                        "all_match": all(r["match"] for r in count_rows)}
    # C1–C20 不匹配**不**改变任何判定词（prereg §7）

    # ---- 9.3 文本测量 ----
    src, loc = text_measurements(cfg)

    # ---- 9.4 探针 ----
    probe, jerr = compile_and_run_probe()
    if probe is None:
        log("FATAL: 探针未产出 ⟹ 整轮 INCONCLUSIVE（工具不可信，非载荷结论）")
        result["fatal"] = "probe_failed"
        json.dump(result, open(RESULT_PATH, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        return 2
    result["probe_raw"] = probe

    ctx = {"cfg": cfg, "probe": probe["probe"], "probe_idx": probe["probe_idx"],
           "probe_list": probe["probe_list"], "src": src, "counts": counts,
           "git": {"baseline": cfg["baseline_commit"]}, "gate": {}, "a2_violated": 0}

    # ---- 9.5 门 G3 + 反向锚点 ----
    g3_ok, g3_norm = gate_g3(ctx)
    ctx["gate"]["g3"] = 1 if g3_ok else 0
    anchors = {
        "g3_code_source": g3_norm,
        "g3_expected_suffix": "target/classes",
        "g3_pass": g3_ok,
        "sentinel_defect_present": probe["probe"].get("V01", {}).get("sentinel_defect_present"),
        "sentinel_normal_equals": probe["probe"].get("V01", {}).get("sentinel_normal_equals"),
        "existing_test_input_ok": probe["probe"].get("V01", {}).get("existing_test_input_ok"),
        "existing_test_input_rho": probe["probe"].get("V01", {}).get("existing_test_input_rho"),
    }
    anchors["reverse_anchors_pass"] = (anchors["sentinel_defect_present"] == 1
                                       and anchors["sentinel_normal_equals"] == 1
                                       and anchors["existing_test_input_ok"] == 1)
    log("G3 code_source=%s (pass=%s); 反向锚点 pass=%s"
        % (anchors["g3_code_source"], anchors["g3_pass"], anchors["reverse_anchors_pass"]))
    result["gates"] = anchors

    # ---- 9.6 A3c 只读普查 ----
    db = query_a3c(cfg)
    ctx["db"] = db
    log("A3c DB: reachable=%s days_with_data=%s min_sxx=%s days_below_guard=%s margin_decades=%s err=%s"
        % (db["reachable"], db["days_with_data"], db["min_sxx"], db["days_below_guard"],
           db["margin_decades"], db["error"]))
    result["a3c"] = db

    # ---- 9.7 确定性 ----
    p2 = probe["probe"].get("V02", {})
    det_ok = (p2.get("det_payload_1") == p2.get("det_payload_2")
              and p2.get("det_pearson_mean_1") == p2.get("det_pearson_mean_2")
              and p2.get("det_cross_days_1") == p2.get("det_cross_days_2")
              and p2.get("det_total_obs_1") == p2.get("det_total_obs_2")
              and p2.get("det_monotonicity_1") == p2.get("det_monotonicity_2")
              and p2.get("det_payload_1") is not None)
    ctx["src"]["determinism_ok"] = 1 if det_ok else 0
    result["determinism"] = {"ok": det_ok, "v02": p2}
    log("确定性门 det_ok=%s" % det_ok)

    # ---- 9.8 panel_design_selfcheck ----
    pd_ok, pd_rows = check_panel_design(cfg, ctx)
    ctx["src"]["panel_selfcheck_ok"] = 1 if pd_ok else 0
    result["panel_design_selfcheck"] = {"pass": pd_ok, "rows": pd_rows}
    for r in pd_rows:
        if not r["pass"]:
            log("PANEL DESIGN MISMATCH %s %s: actual=%s expect=%s (%s)"
                % (r["variant"], r["path"], r["actual"], r["expect"], r["note"]))
    log("panel_design_selfcheck pass=%s (%d 谓词)" % (pd_ok, len(pd_rows)))

    # ---- 9.9 判定（先算 A2，供 A5a 引用）----
    verdicts = {}
    verdicts["A2a"] = ck_A2a(ctx)
    verdicts["A2b"] = ck_A2b(ctx)
    ctx["a2_violated"] = 1 if (verdicts["A2a"] == "VIOLATED" or verdicts["A2b"] == "VIOLATED") else 0
    for axis in cfg["checkers"]:
        if axis not in verdicts:
            verdicts[axis] = CHECKERS[axis](ctx)
    bad_vocab = sorted({v for v in verdicts.values() if v not in VERDICTS})
    result["verdicts"] = verdicts
    log("判定词: %s" % json.dumps(verdicts, ensure_ascii=False, sort_keys=True))
    if bad_vocab:
        log("OUT-OF-VOCAB verdicts: %s ⟹ K9" % bad_vocab)

    # ---- 9.10 金丝雀 ----
    ops = build_canary_ops(ctx)
    base = copy.deepcopy(ctx)
    canary_rows = []
    for axis in cfg["checkers"]:
        spec = cfg["canaries"][axis]
        if axis not in ops:
            canary_rows.append({"axis": axis, "passed": False, "reason": "no canary ops"})
            continue
        c1 = copy.deepcopy(base)
        apply_op(c1, ops[axis]["cln"])
        v1 = CHECKERS[axis](c1)
        c2 = copy.deepcopy(base)
        apply_op(c2, ops[axis]["inj"])
        v2 = CHECKERS[axis](c2)
        passed = (v1 == spec["clean"]) and (v2 == spec["inj"]) and (spec["clean"] != spec["inj"])
        canary_rows.append({"axis": axis, "variant": cfg["variant"][axis],
                            "clean_got": v1, "clean_expect": spec["clean"],
                            "inj_got": v2, "inj_expect": spec["inj"],
                            "distinct": spec["clean"] != spec["inj"], "passed": passed,
                            "intent": spec["intent"]})
        if not passed:
            log("CANARY FAIL %s: CLN got=%s expect=%s; INJ got=%s expect=%s"
                % (axis, v1, spec["clean"], v2, spec["inj"]))
    all_canary = all(r["passed"] for r in canary_rows)
    log("金丝雀 %d/%d 通过" % (sum(1 for r in canary_rows if r["passed"]), len(canary_rows)))
    result["canaries"] = {"rows": canary_rows, "n": len(canary_rows), "all_passed": all_canary}

    # ---- 9.11 K 表 ----
    def nv(axis):
        return verdicts.get(axis) != "NOT_VIOLATED"

    K = {
        "K1": verdicts["A2a"] == "VIOLATED" or verdicts["A2b"] == "VIOLATED",
        "K2": verdicts["A4a"] == "VIOLATED",
        "K3": verdicts["A3a"] == "VIOLATED",
        "K4": verdicts["A5a"] == "CONTRACT_MISLABEL",
        "K5": verdicts["A5b"] == "VIOLATED",
        "K6": verdicts["A6a"] == "CONTRACT_AMBIGUOUS",
        "K7": any(nv(a) for a in ["A3b", "A4b", "A4c", "A6b", "A7b"]),
        "K8": not all(k8_parts.values()),
        "K9": (not all_canary) or (not g3_ok) or (not anchors["reverse_anchors_pass"])
              or bool(bad_vocab) or (not pd_ok),
        "K10": not det_ok,
    }
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
    result["k_table"] = K
    result["h0_alive"] = h0_alive
    result["total_verdict"] = total
    result["non_killing_axes"] = cfg["non_killing_axes"]
    log("K 表: %s" % json.dumps(K, ensure_ascii=False))
    log("h0_alive=%s  total_verdict=%s" % (h0_alive, total))

    result["finished_at"] = datetime.now().isoformat(timespec="seconds")
    json.dump(result, open(RESULT_PATH, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    log("结果写入 %s" % RESULT_PATH)

    # 清理编译产物（prereg §8：probe/out/* 运行后删除）
    if os.path.isdir(PROBE_OUT):
        shutil.rmtree(PROBE_OUT, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
