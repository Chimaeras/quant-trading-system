#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R015 执行器：编译探针 → 每个面板跑两次（要求逐字节相等）→ 解析 → 20 个变体判定 + 20 对金丝雀
→ 写 result.json / _scratch / run.log（append-only）→ 清理字节码。

判定逻辑**只**读探针输出与冻结扫描根内的文本；预注册冻结于 rounds/R015_prereg.md（sha256 见 config.json）。
用法：python run_R015.py
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
BACKEND = os.path.join(REPO, "modules", "backend-java")
CLASSES = os.path.join(BACKEND, "target", "classes")
ROUNDS = os.path.join(REPO, "research", "paper_loop", "rounds")
SCRATCH = os.path.join(HERE, "_scratch")
FA = "com.quant.service.R015Probe"
PROBE_CLASSES = ["R015Probe.class", "R015Probe$Stub.class", "R015Probe$Fixture.class"]

F = {
    "F01": "modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java",
    "F02": "modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java",
    "F08": "modules/web/src/api/factor.ts",
    "F10": "modules/web/src/views/factor/FactorResearch.vue",
    "F12": "modules/backend-java/src/test/java/com/quant/service/FactorResearchServiceTest.java",
}

# 依赖 jar（**钉死版本**，与 R009/R011–R014 同表；缺失即不运行，不得静默改用其它版本）
M2_REPO = os.path.join(os.path.expanduser("~"), ".m2", "repository")
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
DEP_MISSING = []


def dep_classpath():
    jars = []
    for rel, why in DEPS_PINNED:
        p = os.path.join(M2_REPO, *rel.split("/"))
        if os.path.isfile(p):
            jars.append(p)
        else:
            DEP_MISSING.append("%s（%s）" % (rel, why))
    return os.pathsep.join(jars)


def read_text(rel, encoding="utf-8"):
    with open(os.path.join(REPO, rel.replace("/", os.sep)), "r", encoding=encoding, errors="replace") as f:
        return f.read()


def sha256_32(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()[:32]


# --------------------------------------------------------------------------- 运行

def compile_probe(log):
    src = os.path.join(HERE, "probe", "R015Probe.java")
    dep_cp = dep_classpath()
    if DEP_MISSING:
        for m in DEP_MISSING:
            log.append("FAIL: 依赖 jar 缺失（钉死版本）：" + m)
        raise SystemExit("dependency resolution failed: %d missing" % len(DEP_MISSING))
    log.append("harness deps=%d jars (pinned Spring Boot 3.2.0 BOM)" % len(DEPS_PINNED))
    cmd = ["javac", "-cp", os.pathsep.join([CLASSES, dep_cp]), "-encoding", "UTF-8",
           "-d", CLASSES, src]
    p = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    log.append("$ " + " ".join(cmd))
    log.append(p.stdout.strip())
    log.append(p.stderr.strip())
    if p.returncode != 0:
        raise SystemExit("javac failed rc=%d" % p.returncode)
    return dep_cp


def run_panel(panel_id, log, dep_cp):
    panel = os.path.join(HERE, panel_id + ".txt")
    cmd = ["java", "-cp", os.pathsep.join([CLASSES, dep_cp]), FA, panel, panel_id]
    outs = []
    for k in (1, 2):
        p = subprocess.run(cmd, cwd=REPO, capture_output=True,
                           encoding="utf-8", errors="replace")
        log.append("$ %s   # run%d rc=%d" % (" ".join(cmd), k, p.returncode))
        if p.stderr.strip():
            log.append("STDERR: " + p.stderr.strip()[:2000])
        if p.returncode != 0:
            raise SystemExit("probe rc=%d on %s run%d" % (p.returncode, panel_id, k))
        outs.append(p.stdout)
    same = outs[0] == outs[1]
    with open(os.path.join(SCRATCH, "probe_raw_%s.txt" % panel_id), "w",
              encoding="utf-8", newline="\n") as f:
        f.write(outs[0])
    return outs[0], outs[1], same


def cleanup_bytecode(log):
    removed = []
    for name in PROBE_CLASSES:
        p = os.path.join(CLASSES, "com", "quant", "service", name)
        if os.path.exists(p):
            os.remove(p)
            removed.append(name)
    log.append("cleanup bytecode: " + (",".join(removed) if removed else "none"))


# --------------------------------------------------------------------------- 解析

def parse_panel_inputs(panel_id):
    """返回 sid -> {'total':int,'valid':int,'cross':[...],'sum_cross':int,'any_valid_cross':bool,'mode':str}"""
    out = {}
    path = os.path.join(HERE, panel_id + ".txt")
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            t = line.split()
            if not t or t[0] != "DIRECT":
                continue
            sid, mode = t[1], t[2]
            total, valid = int(t[3]), int(t[4])
            specs = t[5:]
            sum_cross = 0
            any_valid = False
            for s in specs:
                if s.startswith("NUM:"):
                    v = int(s[4:])
                    if v > 0:
                        sum_cross += v
                        any_valid = True
            out[sid] = {"total": total, "valid": valid, "cross": specs, "nkeys": len(specs),
                        "sum_cross": sum_cross, "any_valid_cross": any_valid, "mode": mode}
    return out


def parse_probe(text, panel_id):
    res = {"panel": panel_id, "directs": {}, "full": None, "unified": None,
           "snapshot": None, "snpf": {}, "repo_calls": [], "raw": text}
    for line in text.splitlines():
        if line.startswith("DIRECT "):
            _, sid, mode, js = line.split(" ", 3)
            res["directs"][sid] = {"mode_token": mode, "funnel": json.loads(js)}
        elif line.startswith("FULL "):
            res["full"] = json.loads(line[5:])
        elif line.startswith("UNIFIED "):
            res["unified"] = json.loads(line[8:])
        elif line.startswith("SNPF "):
            body = line[len("SNPF "):]
            tag, js = body.split(" ", 1)
            res["snpf"][tag] = json.loads(js)
        elif line.startswith("SNAPSHOT_TRY "):
            res["snapshot"] = line[len("SNAPSHOT_TRY "):]
        elif line.startswith("REPO_CALLS "):
            res["repo_calls"] = [c for c in line[len("REPO_CALLS "):].split(",") if c]
    return res


# --------------------------------------------------------------------------- 分析工具

def sep_matrix(scenarios, keys):
    """返回 (nonseparable_pairs, untestable_pairs, detail)"""
    nonsep, untestable, detail = [], [], {}
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            a, b = keys[i], keys[j]
            va = {m[a] for m in scenarios}
            vb = {m[b] for m in scenarios}
            seps = [m for m in scenarios if m[a] != m[b]]
            detail["%s|%s" % (a, b)] = {"distinct_a": len(va), "distinct_b": len(vb),
                                        "differing": len(seps)}
            if not seps:
                if len(va) >= 2 and len(vb) >= 2:
                    nonsep.append((a, b))
                else:
                    untestable.append((a, b))
    return nonsep, untestable, detail


def classify(funnel, marker_key):
    """回退是否发生：标记键 == 0 ⟺ 回退（prereg §8/V09）。"""
    return funnel.get(marker_key) == 0


REACH_RANK = {"UNREACHABLE": 0, "SYNTHETIC_ONLY": 1, "REACHABLE_CLIENT_INPUT": 2,
              "REACHABLE_PUBLIC_API": 3}


def reach_below(r1, r2):
    return REACH_RANK.get(r1, 0) < REACH_RANK.get(r2, 0)


# --------------------------------------------------------------------------- 主流程

def main():
    log = ["", "=" * 78, "R015 run at %s (local)" % time.strftime("%Y-%m-%d %H:%M:%S")]
    os.makedirs(SCRATCH, exist_ok=True)
    with open(os.path.join(HERE, "config.json"), "r", encoding="utf-8") as f:
        cfg = json.load(f)

    # 冻结门（K7 的前置读数）
    frozen_now, frozen_ok = {}, True
    for tag, meta in cfg["frozen_files"].items():
        h = sha256_32(os.path.join(REPO, meta["path"].replace("/", os.sep)))
        frozen_now[tag] = h
        if h != meta["sha256_32"]:
            frozen_ok = False
    prereg_now = sha256_32(os.path.join(REPO, "research", "paper_loop", "rounds", "R015_prereg.md"))
    prereg_ok = prereg_now == cfg["prereg"]["sha256_32"]

    dep_cp = compile_probe(log)
    panels_out, deterministic = {}, {}
    for pid in cfg["panels"]:
        t1, t2, same = run_panel(pid, log, dep_cp)
        panels_out[pid] = parse_probe(t1, pid)
        deterministic[pid] = same
    cleanup_bytecode(log)

    judged = cfg["judged_keys"]
    marker = cfg["marker_key"]

    # ---------- 场景池 ----------
    scen = []   # 每条: (panel, sid, funnel, inputs)
    for pid in cfg["panels"]:
        pin = parse_panel_inputs(pid)
        for sid, d in panels_out[pid]["directs"].items():
            if sid not in pin:
                continue
            f = d["funnel"]
            scen.append({"panel": pid, "sid": sid, "f": f, "in": pin[sid],
                         "fallback": classify(f, marker)})

    main_scen = [s for s in scen if s["panel"] == "panel_R015_main"]
    rand_scen = [s for s in scen if s["panel"] == "panel_R015_random"]
    M1 = [s["f"] for s in scen if not s["fallback"]]
    M2 = [s["f"] for s in scen if s["fallback"]]

    nonsep1, untest1, det1 = sep_matrix(M1, judged)
    nonsep2, untest2, det2 = sep_matrix(M2, judged)

    alias_pairs = [("feature_valid_count", "valid_observations"),
                   ("selector_item_count", "total_observations")]

    # 别名对恒等（全语料，V03/V04）
    viol_fv = [s for s in scen if s["f"]["feature_valid_count"] != s["f"]["valid_observations"]]
    viol_si = [s for s in scen if s["f"]["selector_item_count"] != s["f"]["total_observations"]]
    distinct = {k: len({s["f"][k] for s in scen}) for k in judged + [marker]}

    # 回退标记等价性（V09/V10）
    marker_viol, branch_viol = [], []
    for s in scen:
        exp_fb = not s["in"]["any_valid_cross"]
        if s["fallback"] != exp_fb:
            marker_viol.append(s["sid"])
        if s["fallback"]:
            if s["f"]["universe_count"] != s["in"]["total"]:
                branch_viol.append(s["sid"] + ":fallback但u!=total")
        else:
            if s["f"]["universe_count"] != s["in"]["sum_cross"]:
                branch_viol.append(s["sid"] + ":非回退但u!=Σcross")

    # V12 单键依赖性：去掉 marker 后，用 `universe_count == total_observations` 冒充回退判定
    #   的**误分类场景**（见证 marker 不可省）；`counter` 是其中的非回退反例子集。
    counter = [s["sid"] for s in scen if (not s["fallback"]) and s["f"]["universe_count"] == s["f"]["total_observations"]]
    no_marker_viol = [s["sid"] for s in scen
                      if (s["f"]["universe_count"] == s["f"]["total_observations"]) != s["fallback"]]

    # ---------- 文本语料（冻结扫描根内） ----------
    f02 = read_text(F["F02"])
    f08 = read_text(F["F08"])
    f10 = read_text(F["F10"])
    f12 = read_text(F["F12"])
    corpus = {"F02": f02, "F08": f08, "F10": f10, "F12": f12}

    def lines_with(name):
        out = []
        for tag, txt in corpus.items():
            for i, ln in enumerate(txt.splitlines(), 1):
                if name in ln:
                    out.append({"file": tag, "line": i, "text": ln.strip()})
        return out

    alias_lines = {n: lines_with(n) for n in
                   ["feature_valid_count", "valid_observations", "selector_item_count",
                    "total_observations"]}
    # 读数 (a)：同一行同时出现两个别名键名（大小写无关子串读数）
    reading_a = [l for l in alias_lines["feature_valid_count"] + alias_lines["valid_observations"]
                 if ("valid_observations" in l["text"] and "feature_valid_count" in l["text"])]
    # 读数 (b)：收紧读数 —— 出现任一别名键名且带定义/计数语义词
    def_tokens = ["计数", "定义", "口径", "单位", "样本", "means", "count of", "观测"]
    reading_b = [l for n in ("valid_observations", "total_observations") for l in alias_lines[n]
                 if any(t in l["text"] for t in def_tokens)]
    reading_dependent_alias = (bool(reading_a) != bool(reading_b))

    # 具名回退标记扫描（V11）：枚举全部键名 + 全部文本值 + 语义 token
    funnel_keys = list(main_scen[0]["f"].keys()) if main_scen else []
    funnel_texts = {k: main_scen[0]["f"][k] for k in funnel_keys
                    if isinstance(main_scen[0]["f"][k], str)} if main_scen else {}
    fb_tokens = ["fallback", "回退", "兜底", "退化", "占位", "placeholder", "substitut", "default", "替代"]
    named_hits = [k for k in funnel_keys if any(t.lower() in k.lower() for t in fb_tokens)]
    text_hits = {k: [t for t in fb_tokens if t.lower() in v.lower()] for k, v in funnel_texts.items()}
    text_hits = {k: v for k, v in text_hits.items() if v}
    universe_source_vals = sorted({str(s["f"].get("universe_source")) for s in scen})

    # V15：legacy funnel 键集是否承载成本内容
    cost_tokens = ["cost", "net", "fee", "commission", "stamp", "slippage", "成本", "佣金", "印花", "滑点"]
    cost_like = [k for k in funnel_keys if any(t in k.lower() for t in cost_tokens)]

    # V18：daily_funnel 行的具名口径计数键（来自冻结扫描根 F02）
    daily_row_keys = re.findall(r'daily\.put\("([a-z_]+)"', f02)
    scope_counts = [k for k in daily_row_keys if k.endswith("_observations")]

    # V07/V08 静态片段：**从冻结扫描根 F02 逐字抽取**（不得手写；抽取失败即 INSTRUMENT_RED）
    def grab(lines, needle, span=2):
        """返回 {lineno, text} 逐字包含 needle 的相邻 span 行原文；找不到返回 None。"""
        ls = f02.splitlines()
        for i, ln in enumerate(ls):
            if needle in ln:
                return "\n".join(x.rstrip() for x in ls[i:i + span])
        return None

    snippet_coverage = {
        "legacy": grab(f02, 'out.put("coverage", allItems.isEmpty()'),
        "snapshot": grab(f02, 'funnel.put("coverage", rowsTotal == 0'),
    }
    snippet_csd = {
        "legacy_funnel": grab(f02, 'funnel.put("cross_section_days", latestPerKey != null'),
        "snapshot_funnel": grab(f02, 'funnel.put("cross_section_days", crossSectionDays)'),
    }
    for _tag, _sn in list(snippet_coverage.items()):
        if _sn is None:
            snippet_coverage[_tag] = ""
    for _tag, _sn in list(snippet_csd.items()):
        if _sn is None:
            snippet_csd[_tag] = ""

    # ---------- 变体判定（含金丝雀） ----------
    variants = {}

    def run_case(vid, axis, checker, inputs, inj, note=""):
        base = checker(inputs)
        cln_inputs = inputs + [("__sentinel_cln__", 1)]
        cln_word = checker(cln_inputs)
        inj_word = checker(inj(inputs))
        variants[vid] = {
            "axis": axis, "word": base, "canary_cln": cln_word == base,
            "canary_inj": inj_word != base, "inj_word": inj_word,
            "reachability": cfg["reachability_frozen"][vid],
            "reading_dependent": False, "note": note,
        }

    # V01：M1 可分性矩阵
    def chk_v01(mats):
        ns = mats[0]
        return "KEY_VALUE_NOT_UNIQUE" if ns else "KEY_VALUE_UNIQUE"
    run_case("V01", "A1", chk_v01, [nonsep1],
             lambda x: [[]],
             "M1 nonseparable=%s untestable=%s" % (nonsep1, untest1))

    # V02：M2 可分性矩阵
    run_case("V02", "A1", chk_v01, [nonsep2],
             lambda x: [[]],
             "M2 nonseparable=%s untestable=%s" % (nonsep2, untest2))

    # V03：穷举 300 组
    def chk_v03(pl):
        viol, distinct = pl[0], pl[1]
        return "KEY_VALUE_NOT_UNIQUE" if (not viol[0] and not viol[1]) else "KEY_VALUE_UNIQUE"

    # P-03 证据：**只在 300 组受控三重面板内**统计值域（不得用全体 316 场景冒充）
    p03 = {
        "scenarios": len(rand_scen),
        "alias_fv_diff_nonzero": len([s for s in rand_scen
                                      if s["f"]["feature_valid_count"] != s["f"]["valid_observations"]]),
        "alias_si_diff_nonzero": len([s for s in rand_scen
                                      if s["f"]["selector_item_count"] != s["f"]["total_observations"]]),
        "distinct": {k: len({s["f"][k] for s in rand_scen})
                     for k in ("total_observations", "valid_observations", "universe_count",
                               "cross_section_days", marker)},
    }
    run_case("V03", "A1", chk_v03, [[viol_fv, viol_si], distinct],
             lambda x: [[{"sid": "INJ"}], x[1]],
             "fv_violations=%d si_violations=%d distinct=%s" % (len(viol_fv), len(viol_si), distinct))

    # V04：反向锚点（全语料搜索相异）
    run_case("V04", "A1", chk_v03, [[viol_fv, viol_si], distinct],
             lambda x: [[{"sid": "INJ"}], x[1]],
             "corpus_scenarios=%d (M1=%d M2=%d)" % (len(scen), len(M1), len(M2)))

    # V05：端到端 + 别名声明
    full_main = panels_out["panel_R015_main"]["full"]
    alias_e2e = (full_main["funnel"]["feature_valid_count"] == full_main["funnel"]["valid_observations"]
                 and full_main["funnel"]["selector_item_count"] == full_main["funnel"]["total_observations"])

    def chk_v05(pl):
        e2e, ra, rb = pl[0], pl[1], pl[2]
        if not e2e:
            return "KEY_VALUE_UNIQUE"
        return "ALIAS_DECLARED" if (ra or rb) else "ALIAS_UNDECLARED"
    run_case("V05", "A1", chk_v05, [alias_e2e, bool(reading_a), bool(reading_b)],
             lambda x: [False, False, False],
             "e2e_alias_equal=%s reading_a_hits=%d reading_b_hits=%d" %
             (alias_e2e, len(reading_a), len(reading_b)))
    variants["V05"]["reading_dependent"] = reading_dependent_alias

    # V06：同载荷内同名键（顶层 vs funnel）
    def top_funnel_rows():
        rows = []
        for pid in ("panel_R015_main", "panel_R015_b8", "panel_R015_b10", "panel_R015_b12"):
            fu = panels_out[pid]["full"]
            if fu is None:
                continue
            rows.append({
                "panel": pid,
                "cross_section_days": [fu["cross_section_days"], fu["funnel"]["cross_section_days"]],
                "total_observations": [fu["total_observations"], fu["funnel"]["total_observations"]],
                "valid_observations": [fu["valid_observations"], fu["funnel"]["valid_observations"]],
            })
        return rows
    tf_rows = top_funnel_rows()

    def chk_v06(pl):
        for r in pl[0]:
            for k in ("cross_section_days", "total_observations", "valid_observations"):
                if r[k][0] != r[k][1]:
                    return "SAME_NAME_DIFF_QUANTITY"
        return "SAME_NAME_SAME_QUANTITY"
    run_case("V06", "A2", chk_v06, [tf_rows],
             lambda x: [[dict(r, total_observations=[r["total_observations"][0],
                                                     r["total_observations"][1] + 1]) for r in x[0]]],
             "top_vs_funnel_rows=%s" % json.dumps(tf_rows, ensure_ascii=False))

    # V07/V08：跨面同名（冻结为 NOT_REACHED_STATIC_ONLY）
    #  判定前提 = 两条片段**逐字取自冻结扫描根**且互不相同；前提缺失 ⟹ INSTRUMENT_RED（装置自证）
    def chk_static(pair):
        a, b = pair[0][0], pair[0][1]
        if not a or not b or a == b or a not in f02 or b not in f02:
            return "INSTRUMENT_RED"
        return "NOT_REACHED_STATIC_ONLY"
    run_case("V07", "A2", chk_static, [[snippet_coverage["legacy"], snippet_coverage["snapshot"]]],
             lambda x: [["NOT-IN-FROZEN-ROOT", x[0][1]]],
             "legacy_denom=allItems.size() snapshot_denom=rowsTotal")
    run_case("V08", "A2", chk_static, [[snippet_csd["legacy_funnel"], snippet_csd["snapshot_funnel"]]],
             lambda x: [["NOT-IN-FROZEN-ROOT", x[0][1]]],
             "legacy=key_count snapshot=rowsByDate.size()")

    # V09：标记等价性（全部场景）
    def chk_v09(pl):
        mv, bv = pl[0], pl[1]
        return "FALLBACK_MARKER_EXACT" if (not mv and not bv) else "FALLBACK_UNDISCLOSED"
    run_case("V09", "A3", chk_v09, [marker_viol, branch_viol],
             lambda x: [["INJ"], x[1]],
             "marker_violations=%d branch_violations=%d" % (len(marker_viol), len(branch_viol)))

    # V10：触发域（含非 NUM 规格）
    dom = sorted({c for s in scen for c in s["in"]["cross"] if not c.startswith("NUM:")})
    run_case("V10", "A3", chk_v09, [marker_viol, branch_viol],
             lambda x: [["INJ"], x[1]],
             "spec_domain=%s scenarios_with_exotic=%d" %
             (dom, len([s for s in scen if any(not c.startswith("NUM:") for c in s["in"]["cross"])])))

    # V11：具名标记扫描
    def chk_v11(pl):
        named, texts, keys = pl[0], pl[1], pl[2]
        if named or texts:
            return "FALLBACK_MARKER_EXACT"
        return "FALLBACK_UNNAMED" if marker in keys else "FALLBACK_UNDISCLOSED"
    run_case("V11", "A3", chk_v11, [named_hits, text_hits, funnel_keys],
             lambda x: [x[0], x[1], [k for k in x[2] if k != marker]],
             "named_keys=%s text_hits=%s universe_source_vals=%s" %
             (named_hits, {k: v for k, v in text_hits.items()}, universe_source_vals))

    # V12：单键依赖性
    def chk_v12(pl):
        counterex, keys = pl[0], pl[1]
        if not counterex:
            return "FALLBACK_MARKER_EXACT"
        return "FALLBACK_UNNAMED"
    run_case("V12", "A3", chk_v12, [counter, funnel_keys],
             lambda x: [[], [marker]],
             "counterexamples(u==total while days>0)=%d sample=%s" % (len(counter), counter[:5]))

    # V13：空输入退化
    empty = [s for s in scen if s["in"]["nkeys"] == 0 and s["in"]["total"] == 0]

    def chk_v13(pl):
        rows = pl[0]
        if not rows:
            return "DEGENERATE_KEYS_INCONSISTENT"
        for r in rows:
            if any(r.get(k, 0) != 0 for k in judged) or r.get(marker, 0) != 0:
                return "DEGENERATE_KEYS_INCONSISTENT"
        return "DEGENERATE_KEYS_CONSISTENT"
    run_case("V13", "A3", chk_v13, [[s["f"] for s in empty]],
             lambda x: [[dict(x[0][0], universe_count=1)] if x[0] else [{"universe_count": 1}]],
             "empty_scenarios=%d" % len(empty))

    # V14：退化不一致搜寻（total=0 而 cross>0）
    zero_total = [s for s in scen if s["in"]["total"] == 0 and s["in"]["sum_cross"] > 0]

    def chk_v14(pl):
        rows = pl[0]
        if not rows:
            return "DEGENERATE_KEYS_INCONSISTENT"
        bad = [r for r in rows
               if r.get("universe_count") == r.get("total_observations")]
        return "DEGENERATE_KEYS_INCONSISTENT" if bad else "DEGENERATE_KEYS_CONSISTENT"
    run_case("V14", "A3", chk_v14, [[s["f"] for s in zero_total]],
             lambda x: [[dict(x[0][0], total_observations=x[0][0]["universe_count"])] if x[0] else [{}]],
             "total0_with_cross>0 scenarios=%d" % len(zero_total))

    # V15：legacy funnel 口径标签忠实
    def chk_v15(pl):
        keys = pl[0]
        return "SCOPE_LABEL_FAITHFUL" if keys else "SCOPE_LABEL_UNFAITHFUL"
    run_case("V15", "A4", chk_v15, [cost_like],
             lambda x: [["cost_approx_observations"]],
             "metric_scope=%s cost_like_keys=%s" % (full_main["funnel"].get("metric_scope"), cost_like))

    # V16：顶层 vs rank_ic 口径
    def chk_v16(pl):
        top, ri = pl[0], pl[1]
        return "SCOPE_LABEL_FAITHFUL" if top == ri else "SCOPE_LABEL_UNFAITHFUL"
    run_case("V16", "A4", chk_v16, [full_main["metric_scope"], full_main["rank_ic_counts"]["metric_scope"]],
             lambda x: [x[0], x[0]],
             "top=%s rank_ic=%s" % (full_main["metric_scope"], full_main["rank_ic_counts"]["metric_scope"]))

    # V17：tradable null 可分辨
    def chk_v17(pl):
        cnt, st = pl[0], pl[1]
        return "SCOPE_LABEL_FAITHFUL" if (cnt is None and st == "UNAVAILABLE") else "SCOPE_LABEL_UNFAITHFUL"
    run_case("V17", "A4", chk_v17, [full_main["funnel"]["tradable_count"], full_main["funnel"]["tradable_status"]],
             lambda x: [0, x[1]],
             "tradable_count=%r tradable_status=%r note=%r" %
             (full_main["funnel"]["tradable_count"], full_main["funnel"]["tradable_status"],
              full_main["funnel"]["tradable_note"]))

    # V18：daily_funnel 行内具名口径计数
    def chk_v18(pl):
        return "SCOPE_LABEL_FAITHFUL" if len(pl[0]) >= 2 else "SCOPE_LABEL_UNFAITHFUL"
    run_case("V18", "A4", chk_v18, [scope_counts],
             lambda x: [["raw_observations"]],
             "daily_funnel_row_keys=%s scope_counts=%s" % (daily_row_keys, scope_counts))

    # V19/V20：仪器
    canary_all_ok = all(v["canary_cln"] and v["canary_inj"] for v in variants.values())
    pairs_done = len(variants)
    axis_total_declared = sum(a["count"] for a in cfg["axes"].values())

    def chk_v19(pl):
        declared, ok, axis = pl[0], pl[1], pl[2]
        return "INSTRUMENT_GREEN" if (declared == 20 and axis == 20 and ok) else "INSTRUMENT_RED"
    run_case("V19", "A5", chk_v19, [cfg["canary_pairs"], canary_all_ok, axis_total_declared],
             lambda x: [19, True, 20],
             "canary_pairs_declared=%d axis_variants_declared=%d pairs_checked_before_V19=%d all_ok=%s" %
             (cfg["canary_pairs"], axis_total_declared, pairs_done, canary_all_ok))

    matrix_stable = det1 == sep_matrix(M1, judged)[2] and det2 == sep_matrix(M2, judged)[2]
    swap_invariant = (sep_matrix(M1, judged)[0] == nonsep1)

    def chk_v20(pl):
        dets, stable, swap = pl[0], pl[1], pl[2]
        return "INSTRUMENT_GREEN" if (all(dets) and stable and swap) else "INSTRUMENT_RED"
    run_case("V20", "A5", chk_v20, [list(deterministic.values()), matrix_stable, swap_invariant],
             lambda x: [[False] + list(x[0][1:]), x[1], x[2]],
             "byte_equal_2nd_run=%s matrix_stable=%s keyname_swap_invariant=%s" %
             (deterministic, matrix_stable, swap_invariant))

    # 重新计算仪器门（此时 V19/V20 已入列）
    canary_all_ok = all(v["canary_cln"] and v["canary_inj"] for v in variants.values())

    # ---------- §2.1 forbidden_scan（R015_report.md 正文 + 全部 _scratch **读数文件**；命中 ⟹ K6） ----------
    #  ① 扫描结果**只记索引**（不落禁用子串原文），否则 result.json 会被自身污染成下一次运行的命中源；
    #  ② **论文源材料**（`dm2019.pdf/txt`、`oa1.json`、`oa_dm.json`）**不是「读数文件」**，按定义排除并逐条披露
    #     —— 论文正文当然含它自己的数值，扫它得到的是**源**而不是**结论泄漏**；
    #  ③ 另加**仪器自扫描**（runner/generator/probe 自身源码 + BEGIN/END_FORBIDDEN_PATTERNS 哨兵内跳过），
    #     沿用 R014 的做法：防「仪器里嵌了论文身份/结论词」。
    SOURCE_MATERIAL = {"dm2019.pdf", "dm2019.txt", "oa1.json", "oa_dm.json"}
    EXEMPT = "forbidden_scan_exempt"
    forbidden = cfg["forbidden_scan"]

    def scan_one(tag, path, skip_sentinel=False):
        hits, exempts = [], 0
        in_block = False
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for ln, text in enumerate(fh, 1):
                if skip_sentinel:
                    if "BEGIN_FORBIDDEN_PATTERNS" in text:
                        in_block = True
                        continue
                    if "END_FORBIDDEN_PATTERNS" in text:
                        in_block = False
                        continue
                    if in_block:
                        continue
                low = text.lower()
                if EXEMPT in low:
                    if any(s.lower() in low for s in forbidden):
                        exempts += 1
                    continue
                for idx, s in enumerate(forbidden):
                    if s.lower() in low:
                        hits.append({"file": tag, "line": ln, "pattern_index": idx})
        return hits, exempts

    scan_targets = []
    rep = os.path.join(ROUNDS, "R015_report.md")
    if os.path.exists(rep):
        scan_targets.append(("rounds/R015_report.md", rep, False))
    for fn in sorted(os.listdir(SCRATCH)):
        if fn.endswith((".txt", ".json", ".md")) and fn not in SOURCE_MATERIAL:
            scan_targets.append(("_scratch/" + fn, os.path.join(SCRATCH, fn), False))
    forbidden_hits, forbidden_exempts = [], 0
    for tag, path, sent in scan_targets:
        h, e = scan_one(tag, path, sent)
        forbidden_hits += h
        forbidden_exempts += e
    forbidden_scanned = [t for t, _, _ in scan_targets]
    # ③ 仪器自扫描（**非**冻结条款，沿用 R014：防仪器自身嵌入论文身份/结论词）
    self_targets = [("harness/run_R015.py", os.path.join(HERE, "run_R015.py")),
                    ("harness/make_config.py", os.path.join(HERE, "make_config.py")),
                    ("harness/probe/R015Probe.java", os.path.join(HERE, "probe", "R015Probe.java"))]
    self_hits, self_exempts = [], 0
    for tag, path in self_targets:
        h, e = scan_one(tag, path, skip_sentinel=True)
        self_hits += h
        self_exempts += e

    # ---------- 门 ----------
    axis_total = sum(a["count"] for a in cfg["axes"].values())
    row_sum = sum(a["count"] for a in cfg["axes"].values())
    grid_mismatch = 0 if (row_sum == axis_total == cfg["variants_total"]) else 1
    off_vocab = sorted({v["word"] for v in variants.values()} - set(cfg["vocab"]))
    off_vocab += sorted({v["inj_word"] for v in variants.values()} - set(cfg["vocab"]))
    off_vocab = sorted(set(off_vocab))
    K5 = (row_sum != axis_total or axis_total != cfg["variants_total"]
          or cfg["variants_total"] != 20 or grid_mismatch != 0 or cfg["canary_pairs"] != 20)
    K6 = (bool(off_vocab) or not canary_all_ok or not all(deterministic.values())
          or bool(forbidden_hits) or bool(self_hits))
    K7 = (not frozen_ok) or (not prereg_ok) or not all(deterministic.values())

    load_bearing = {"KEY_VALUE_NOT_UNIQUE", "FALLBACK_UNDISCLOSED", "SAME_NAME_DIFF_QUANTITY"}
    K4 = [vid for vid, v in variants.items()
          if v["word"] in load_bearing and reach_below(v["reachability"], "REACHABLE_PUBLIC_API")]

    def fires(word, allow_reading_dep=False):
        return [vid for vid, v in variants.items()
                if v["word"] == word and (allow_reading_dep or not v["reading_dependent"])
                and v["reachability"] == "REACHABLE_PUBLIC_API"]

    K1 = fires("KEY_VALUE_NOT_UNIQUE")
    K2 = fires("FALLBACK_UNDISCLOSED")
    K3 = fires("SAME_NAME_DIFF_QUANTITY")

    if K4 or K5 or K6 or K7:
        verdict = "INCONCLUSIVE"
    elif K1 or K2 or K3:
        verdict = "REJECTED"
    else:
        verdict = "NOT_VIOLATED"

    # ---------- ±20% 网格（3 面板 × 3 缩放 × 2 模式 = 18 单元；不合格单元**如实**记入 flips） ----------
    grid = []
    grid_flips = []
    equal_pair_sets = []
    for pid in ("panel_R015_b8", "panel_R015_b10", "panel_R015_b12"):
        pin = parse_panel_inputs(pid)
        for tag in ("s80", "s100", "s120"):
            sid = "B%s_%s" % (pid.replace("panel_R015_b", ""), tag)
            d = panels_out[pid]["directs"].get(sid)
            inp = pin.get(sid)
            for mode in ("M1", "M2"):
                if d is None or inp is None:
                    grid_flips.append({"panel": pid, "sid": sid, "mode": mode,
                                       "reason": "MISSING_DIRECT"})
                    continue
                f = d["funnel"]
                fb_executed = classify(f, marker)
                pairs = [(k1, k2) for i, k1 in enumerate(judged) for k2 in judged[i + 1:]]
                equal_pairs = ["%s=%s" % (k1, k2) for k1, k2 in pairs if f.get(k1) == f.get(k2)]
                equal_pair_sets.append(set(equal_pairs))
                cell = {"panel": pid, "sid": sid, "mode": mode, "fallback_executed": fb_executed,
                        "marker_value": f.get(marker), "counts": {k: f.get(k) for k in judged},
                        "equal_pairs_of_15": len(equal_pairs), "equal_pairs": equal_pairs,
                        "alias_fv_eq_vo": f.get("feature_valid_count") == f.get("valid_observations"),
                        "alias_si_eq_t": f.get("selector_item_count") == f.get("total_observations"),
                        "input_total": inp["total"], "input_valid": inp["valid"],
                        "input_sum_cross": inp["sum_cross"],
                        "input_any_valid_cross": inp["any_valid_cross"]}
                # A1 轴词翻转为 KEY_VALUE_UNIQUE 的**充分条件**（单元内可见）：两别名对不再相等
                cell["a1_flip"] = not (cell["alias_fv_eq_vo"] and cell["alias_si_eq_t"])
                # A3 轴词翻转为 FALLBACK_UNDISCLOSED：标记与「回退已执行」不再等价；或载荷值与入参不符
                cell["a3_flip"] = ((f.get(marker) == 0) != (not inp["any_valid_cross"]))
                cell["value_faithful"] = (f.get("valid_observations") == inp["valid"]
                                          and f.get("total_observations") == inp["total"])
                cell["ok"] = (not cell["a1_flip"]) and (not cell["a3_flip"]) and cell["value_faithful"]
                grid.append(cell)
                if not cell["ok"]:
                    grid_flips.append({"panel": pid, "sid": sid, "mode": mode,
                                       "a1_flip": cell["a1_flip"], "a3_flip": cell["a3_flip"],
                                       "value_faithful": cell["value_faithful"],
                                       "counts": cell["counts"], "marker_value": cell["marker_value"]})
    grid_nonsep = len(set.intersection(*equal_pair_sets)) if equal_pair_sets else 0
    parameter_dependent = bool(grid_flips)

    result = {
        "round": "R015",
        "prereg_sha256_32": cfg["prereg"]["sha256_32"], "prereg_now": prereg_now, "prereg_ok": prereg_ok,
        "frozen_now": frozen_now, "frozen_ok": frozen_ok, "frozen_count": len(frozen_now),
        "deterministic": deterministic,
        "panels": {pid: {"directs": len(panels_out[pid]["directs"]),
                         "has_full": panels_out[pid]["full"] is not None,
                         "has_unified": panels_out[pid]["unified"] is not None,
                         "snapshot": panels_out[pid]["snapshot"],
                         "repo_calls": panels_out[pid]["repo_calls"]} for pid in cfg["panels"]},
        "scenario_counts": {"total": len(scen), "M1": len(M1), "M2": len(M2),
                            "main": len(main_scen), "random": len(rand_scen)},
        "separability": {"M1_nonseparable": nonsep1, "M1_untestable": untest1,
                         "M2_nonseparable": nonsep2, "M2_untestable": untest2, "detail": det1},
        "alias": {"fv_violations": len(viol_fv), "si_violations": len(viol_si),
                  "distinct_values": distinct,
                  "e2e_alias_equal": alias_e2e,
                  "reading_a_hits": reading_a, "reading_b_hits": reading_b,
                  "reading_dependent": reading_dependent_alias,
                  "alias_lines": alias_lines},
        "fallback": {"marker_violations": marker_viol, "branch_violations": branch_viol,
                     "counterexamples_u_eq_total_while_days_gt0": counter,
                     "no_marker_misclass": no_marker_viol,
                     "named_key_hits": named_hits, "text_token_hits": text_hits,
                     "universe_source_values": universe_source_vals,
                     "exotic_spec_domain": dom},
        "funnel_view_main": full_main,
        "p03_evidence": p03,
        "snapshot_funnel_live": panels_out["panel_R015_main"]["snpf"],
        "snapshot_face_status": panels_out["panel_R015_main"]["snapshot"],
        "top_vs_funnel": tf_rows,
        "daily_funnel_row_keys": daily_row_keys,
        "scope_counts_in_daily_rows": scope_counts,
        "frozen_snippets": {"coverage": snippet_coverage, "cross_section_days": snippet_csd},
        "variants": variants,
        "gates": {"K1": K1, "K2": K2, "K3": K3, "K4": K4, "K5_K6_K7": [bool(K5), bool(K6), bool(K7)],
                  "off_vocab": off_vocab, "canary_all_ok": canary_all_ok,
                  "row_sum": row_sum, "axis_total": axis_total,
                  "variants_total": cfg["variants_total"], "grid_mismatch_count": grid_mismatch,
                  "canary_pairs": cfg["canary_pairs"],
                  "forbidden_hits": forbidden_hits, "forbidden_hits_n": len(forbidden_hits),
                  "forbidden_exempts": forbidden_exempts,
                  "forbidden_scanned": forbidden_scanned,
                  "forbidden_excluded_source_material": sorted(SOURCE_MATERIAL),
                  "forbidden_patterns_n": len(forbidden),
                  "instrument_self_scan": {"files": [t for t, _ in self_targets],
                                           "hits": self_hits, "hits_n": len(self_hits),
                                           "exempts": self_exempts}},
        "total_verdict": verdict,
        "h0_alive": verdict != "REJECTED",
        "sensitivity_grid": grid,
        "sensitivity_grid_cells": len(grid),
        "sensitivity_flips": grid_flips,
        "sensitivity_nonseparable_pairs_over_grid": grid_nonsep,
        "parameter_dependent": parameter_dependent,
    }
    with open(os.path.join(HERE, "result.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
        f.write("\n")
    with open(os.path.join(SCRATCH, "result_full.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")

    log.append("verdict=%s K1=%s K2=%s K3=%s K4=%s K5=%s K6=%s K7=%s off_vocab=%s"
               % (verdict, K1, K2, K3, K4, K5, K6, K7, off_vocab))
    log.append("forbidden_scan: targets=%d patterns=%d hits=%d exempts=%d (只记索引)"
               % (len(forbidden_scanned), len(forbidden), len(forbidden_hits), forbidden_exempts))
    for h in forbidden_hits:
        log.append("  FORBIDDEN_HIT %s:%d pattern_index=%d" % (h["file"], h["line"], h["pattern_index"]))
    log.append("instrument_self_scan: files=%d hits=%d exempts=%d"
               % (len(self_targets), len(self_hits), self_exempts))
    for h in self_hits:
        log.append("  SELF_HIT %s:%d pattern_index=%d" % (h["file"], h["line"], h["pattern_index"]))
    log.append("scenarios: " + json.dumps(result["scenario_counts"]))
    log.append("M1 nonseparable: %s" % nonsep1)
    log.append("M2 nonseparable: %s" % nonsep2)
    with open(os.path.join(HERE, "run.log"), "a", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(log) + "\n")

    print(json.dumps({k: result[k] for k in ("total_verdict", "gates", "scenario_counts",
                                             "separability")}, ensure_ascii=False, indent=2)[:6000])
    return 0


if __name__ == "__main__":
    sys.exit(main())
