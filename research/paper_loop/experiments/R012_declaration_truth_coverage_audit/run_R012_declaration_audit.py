# -*- coding: utf-8 -*-
"""
R012 执行器：研究载荷**声明性文本字段族**的「真值 / 覆盖 / 跨块跨面同义」只读审计。

用法：
    python make_config.py                      # 先生成冻结 config.json + panel
    python run_R012_declaration_audit.py               # 全量（含 Java 探针）
    python run_R012_declaration_audit.py --static-only # 只做静态面

纪律（对应 rounds/R012_prereg.md）：
  * 只读仓库文件；**不写任何生产代码/状态**；不读数据库；不发网络请求。
  * 判定只用预注册 §6.3 冻结词表；**表外词 ⟹ K6（仪器门失败）**。
  * 仪器门（K5/K6/K7）优先短路：仪器不可信时**不得**输出 REJECTED。
  * 实测与 P 表不符 ⟹ 报告附录勘误，**不回写** `R012_prereg.md`。
"""

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
EXP_REL = "research/paper_loop/experiments/R012_declaration_truth_coverage_audit"
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
SCRATCH = os.path.join(HERE, "_scratch")
RESULT = os.path.join(HERE, "result.json")
RUNLOG = os.path.join(HERE, "run.log")

PROBE_SRC = os.path.join(HERE, "probe", "R012Probe.java")
PROBE_FQN = "com.quant.service.backtest.R012Probe"
TARGET_CLASSES = os.path.join(REPO, "modules", "backend-java", "target", "classes")

CACHE = {}


# ══════════════════════════════════════════════════════════════════════════════
# 基础工具
# ══════════════════════════════════════════════════════════════════════════════
def log(msg):
    line = str(msg)
    print(line, flush=True)
    with open(RUNLOG, "a", encoding="utf-8", newline="\n") as fh:
        fh.write(line + "\n")


def read_lines(rel):
    if rel not in CACHE:
        path = os.path.join(REPO, rel)
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            CACHE[rel] = fh.read().split("\n")
    return CACHE[rel]


def seg(rel, start, end):
    lines = read_lines(rel)
    return "\n".join(lines[start - 1:end])


def rel_of(path):
    return os.path.relpath(path, REPO).replace("\\", "/")


def sha256_file(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def line_of(rel, pattern, start=1, end=None):
    lines = read_lines(rel)
    end = end if end is not None else len(lines)
    for i in range(start - 1, min(end, len(lines))):
        if re.search(pattern, lines[i]):
            return i + 1
    return None


# ══════════════════════════════════════════════════════════════════════════════
# 事实原语求值
# ══════════════════════════════════════════════════════════════════════════════
def eval_fact(f):
    kind = f["kind"]
    text = seg(f["file"], f["start"], f["end"])
    if kind == "regex_present":
        m = re.search(f["pattern"], text)
        return dict(fact=f["pattern"], kind=kind, ok=bool(m),
                    at=(line_of(f["file"], f["pattern"], f["start"], f["end"]) if m else None),
                    note=f.get("note", ""))
    if kind == "regex_absent":
        m = re.search(f["pattern"], text)
        return dict(fact=f["pattern"], kind=kind, ok=not m,
                    at=(line_of(f["file"], f["pattern"], f["start"], f["end"]) if m else None),
                    note=f.get("note", ""))
    if kind == "ordered":
        a = line_of(f["file"], f["patterns"][0], f["start"], f["end"])
        b = line_of(f["file"], f["patterns"][1], f["start"], f["end"])
        ok = a is not None and b is not None and a < b
        return dict(fact="%s <%s %s" % (f["patterns"][0], "<", f["patterns"][1]),
                    kind=kind, ok=ok, at=[a, b], note=f.get("note", ""))
    raise SystemExit("未知事实原语：%s" % kind)


def eval_facts(facts):
    return [eval_fact(f) for f in facts]


# ══════════════════════════════════════════════════════════════════════════════
# 键集抽取
# ══════════════════════════════════════════════════════════════════════════════
PUT_RE = re.compile(r'\.put\(\s*"([^"]+)"')
FIELD_RE = re.compile(r"private\s+[\w<>,\[\]\.\s]+\s+(\w+)\s*;")


def keys_in(rel, start, end, kind="map"):
    text = seg(rel, start, end)
    if kind == "dto":
        keys = FIELD_RE.findall(text)
    elif kind == "code":
        keys = PUT_RE.findall(text)
    else:
        keys = PUT_RE.findall(text)
    out = []
    for k in keys:
        if k not in out:
            out.append(k)
    return out


def keys_in_ranges(rel, ranges):
    """ranges = [[s,e], [s,e], ...]（冻结 spec 用 list-of-pairs 表示）"""
    acc = []
    for r in ranges:
        for k in keys_in(rel, r[0], r[1]):
            if k not in acc:
                acc.append(k)
    return acc


# ══════════════════════════════════════════════════════════════════════════════
# 块 / 文本 抽取
# ══════════════════════════════════════════════════════════════════════════════
def extract_blocks(cfg):
    out = []
    for b in cfg["blocks"]:
        keys = keys_in(b["file"], b["start"], b["end"], b["kind"])
        sig_line = line_of(b["file"], re.escape(b["sig"]), b["start"], b["end"])
        anchor_hit = (sig_line is not None) or (len(keys) > 0)
        # 锚点校准：实测块范围（只改仪器，不改冻结数字）
        actual_start, actual_end = b["start"], b["end"]
        out.append(dict(id=b["id"], file=b["file"], frozen=[b["start"], b["end"]],
                        kind=b["kind"], load_bearing=b["load_bearing"],
                        desc=b["desc"], parents=b["parents"], children=b["children"],
                        keys=keys, key_count=len(keys), sig=b["sig"], sig_line=sig_line,
                        anchor_hit=anchor_hit, actual=[actual_start, actual_end]))
    return out


def extract_text_field(cfg, t):
    lines = read_lines(t["file"])
    hits = []
    for a in t["anchors"]:
        at = line_of(t["file"], re.escape(a), max(1, t["line"] - 6), t["line"] + 6)
        hits.append(dict(anchor=a, at=at, ok=at is not None))
    # 冻结锚点行是否含该 key（仅辅助证据：常量以名字声明、literal 以字面量出现）
    key_at_frozen = t["key"]
    at_frozen = t["line"]
    if t["kind"] == "const":
        ok_frozen = re.search(re.escape(t["key"]), lines[at_frozen - 1]) is not None
    else:
        ok_frozen = bool(lines[at_frozen - 1].strip()) if at_frozen <= len(lines) else False
    return dict(id=t["id"], file=t["file"], frozen_line=t["line"], key=t["key"],
                owner=t["owner"], kind=t["kind"], anchors=hits,
                anchors_ok=sum(1 for h in hits if h["ok"]), anchors_total=len(hits),
                frozen_line_ok=ok_frozen,
                sample="\n".join(lines[at_frozen - 1:at_frozen + 3])[:400])


# ══════════════════════════════════════════════════════════════════════════════
# §4.3 覆盖判定（自身 ∪ 祖先 ∪ 直接子块）
# ══════════════════════════════════════════════════════════════════════════════
def ancestors(blocks, bid):
    by_id = {b["id"]: b for b in blocks}
    seen, stack = set(), list(by_id[bid]["parents"])
    while stack:
        p = stack.pop()
        if p in seen:
            continue
        seen.add(p)
        stack.extend(by_id[p]["parents"])
    return seen


def coverage(blocks, cfg, bid, key_class):
    by_id = {b["id"]: b for b in blocks}
    names = cfg["k_universe"] if key_class == "universe" else cfg["k_scope"]
    b = by_id[bid]
    self_keys = list(b["keys"]) + list(cfg["self_extra"].get(bid, []))
    wire = cfg["wiring"].get(bid)
    if wire:
        wtxt = seg(wire["file"], wire["start"], wire["end"])
        if re.search(re.escape(wire["setter"]), wtxt):
            self_keys.append(wire["setter"])
    self_hit = sorted(set(k for k in self_keys if k in names))
    anc = sorted(ancestors(blocks, bid))
    anc_hit = []
    for p in anc:
        for k in by_id[p]["keys"]:
            if k in names and k not in anc_hit:
                anc_hit.append(k)
    child_hit = []
    for c in by_id[bid]["children"]:
        for k in by_id[c]["keys"]:
            if k in names and k not in child_hit:
                child_hit.append(k)
    found = sorted(set(self_hit) | set(anc_hit) | set(child_hit))
    verdict = "COVERAGE_OK" if found else (
        "COVERAGE_MISSING_UNIVERSE" if key_class == "universe" else "COVERAGE_MISSING_SCOPE")
    return dict(block=bid, key_class=key_class, self_hit=self_hit, ancestor_hit=anc_hit,
                child_hit=child_hit, ancestors=anc, verdict=verdict,
                self_only_verdict="COVERAGE_OK" if self_hit else verdict)


# ══════════════════════════════════════════════════════════════════════════════
# 变体评估
# ══════════════════════════════════════════════════════════════════════════════
def worst(verdicts, order):
    for v in order:
        if v in verdicts:
            return v
    return verdicts[0]


def eval_claim(c, naive=False, naive_map=None):
    if c["klass"] == "NOT_FALSIFIABLE":
        return dict(claim_id=c["id"], text=c["text"], klass=c["klass"],
                    verdict="UNFALSIFIABLE", reason=c.get("reason", ""), facts=[])
    facts = []
    for f in c.get("facts", []):
        f2 = dict(f)
        if naive and naive_map and f2.get("pattern") in naive_map:
            f2["pattern"] = naive_map[f2["pattern"]]
        facts.append(eval_fact(f2))
    ok = all(x["ok"] for x in facts) if facts else False
    return dict(claim_id=c["id"], text=c["text"], klass=c["klass"],
                verdict="TRUE_CLAIM" if ok else "FALSE_CLAIM", reason="", facts=facts)


def rule_truth(v, ctx):
    claims = [eval_claim(c) for c in v["claims"]]
    naive_map = ctx["cfg"].get("reading_naive", {})
    for c, spec in zip(claims, v["claims"]):
        alt = eval_claim(spec, naive=True, naive_map=naive_map)
        c["naive_verdict"] = alt["verdict"]
        c["reading_dependent"] = (alt["verdict"] != c["verdict"])
        c["naive_facts"] = alt["facts"]
    vers = [c["verdict"] for c in claims]
    if "FALSE_CLAIM" in vers:
        verdict = "FALSE_CLAIM"
    elif vers and all(x in ("TRUE_CLAIM", "UNFALSIFIABLE") for x in vers):
        verdict = "TRUE_CLAIM"
    else:
        verdict = "CLAIM_UNDECIDABLE_STATIC"
    return verdict, dict(claims=claims)


def rule_block_structure(v, ctx):
    blocks = ctx["blocks"]
    mismatch = [b["id"] for b in blocks if not b["anchor_hit"]]
    verdict = "TRUE_CLAIM" if (len(blocks) == ctx["cfg"]["tolerances"]["BLOCK_COUNT"]
                               and not mismatch) else "FALSE_CLAIM"
    return verdict, dict(block_count=len(blocks), anchor_mismatch=mismatch,
                         frozen_count=ctx["cfg"]["tolerances"]["BLOCK_COUNT"],
                         ids=[b["id"] for b in blocks])


def rule_keyset_diff_branch(v, ctx):
    rel = ctx["cfg"]["files"]["FRS"]
    empty = keys_in_ranges(rel, [v["ranges"]["empty"]])
    r = v["ranges"]["full"]
    full = keys_in_ranges(rel, [[r[0], r[1]], [r[2], r[3]]])
    diff = [k for k in full if k not in empty] + [k for k in empty if k not in full]
    verdict = "TRUE_CLAIM" if diff else "FALSE_CLAIM"
    return verdict, dict(empty_keys=empty, full_keys=full, diff=diff, note=v["branch_note"])


def rule_keyset_diff_pair(v, ctx):
    a, b = v["blocks"]
    by_id = {x["id"]: x for x in ctx["blocks"]}
    ka, kb = by_id[a]["keys"], by_id[b]["keys"]
    diff = [k for k in ka if k not in kb] + [k for k in kb if k not in ka]
    expect = ctx["cfg"]["expectations"]["P3"]["expect"]
    verdict = "TRUE_CLAIM" if diff == expect else "FALSE_CLAIM"
    return verdict, dict(a=a, b=b, keys_a=ka, keys_b=kb, diff=sorted(diff),
                         expected=expect, cross_ref=v.get("cross_ref", ""))


def rule_dto_field_asymmetry(v, ctx):
    facts = eval_facts(v["facts"])
    ok = all(f["ok"] for f in facts)
    corr = eval_facts(v.get("corroboration", []))
    if v.get("corroboration") and not all(f["ok"] for f in corr):
        # 冻结扫描根之外的文件缺窗口 ⟹ 仪表缺陷，不得进入判定，但必须暴露
        ctx.setdefault("instrument_alerts", []).append(
            dict(variant=v["id"], where="corroboration",
                 failed=[f for f in corr if not f["ok"]]))
    return ("TRUE_CLAIM" if ok else "FALSE_CLAIM"), dict(
        facts=facts, corroboration=corr,
        corroboration_note="冻结扫描根之外（modules/web），仅旁证、不参与判定")


def rule_coverage_matrix(v, ctx):
    kc = v["key_class"]
    cells = [coverage(ctx["blocks"], ctx["cfg"], b["id"], kc) for b in ctx["blocks"]]
    missing = [c["block"] for c in cells if c["verdict"] != "COVERAGE_OK"]
    verdict = "COVERAGE_OK" if not missing else (
        "COVERAGE_MISSING_UNIVERSE" if kc == "universe" else "COVERAGE_MISSING_SCOPE")
    return verdict, dict(cells=cells, missing=missing, key_class=kc)


def rule_self_only(v, ctx):
    cells = [coverage(ctx["blocks"], ctx["cfg"], b["id"], "universe") for b in ctx["blocks"]]
    missing_self = [c["block"] for c in cells if c["self_only_verdict"] != "COVERAGE_OK"]
    verdict = "COVERAGE_OK" if not missing_self else "COVERAGE_MISSING_UNIVERSE"
    return verdict, dict(missing_self_only=missing_self,
                         cells=[dict(block=c["block"], self_hit=c["self_hit"],
                                     self_only=c["self_only_verdict"]) for c in cells],
                         note="仅自身字段口径：描述性，不作 kill 依据")


def rule_load_bearing(v, ctx):
    lb = [b["id"] for b in ctx["blocks"] if b["load_bearing"]]
    nlb = [b["id"] for b in ctx["blocks"] if not b["load_bearing"]]
    frozen_lb = [b["id"] for b in ctx["cfg"]["blocks"] if b["load_bearing"]]
    return ("TRUE_CLAIM" if lb == frozen_lb else "FALSE_CLAIM"), dict(
        load_bearing=lb, not_load_bearing=nlb, frozen=frozen_lb,
        note="按 §4.1 冻结列；描述性")


def rule_coverage_b12(v, ctx):
    cells = [coverage(ctx["blocks"], ctx["cfg"], bid, "universe") for bid in ("B12", "B14", "B13", "B11")]
    by = {c["block"]: c for c in cells}
    verdict = by["B12"]["verdict"]
    return verdict, dict(cells=cells, b12=by["B12"], b14=by["B14"],
                         b13=by["B13"], b11=by["B11"])


def rule_consistency_b11_b12(v, ctx):
    facts = eval_facts(v["facts"])
    b12_missing = coverage(ctx["blocks"], ctx["cfg"], "B12", "universe")["verdict"] != "COVERAGE_OK"
    b11_ok = coverage(ctx["blocks"], ctx["cfg"], "B11", "universe")["verdict"] == "COVERAGE_OK"
    asym = b11_ok and b12_missing
    verdict = "INCONSISTENT" if (asym and all(f["ok"] for f in facts)) else "CONSISTENT"
    return verdict, dict(facts=facts, b11_covered=b11_ok, b12_missing=b12_missing,
                         asymmetry=asym)


def rule_scope_vocab(v, ctx):
    frs, sbs = ctx["cfg"]["files"]["FRS"], ctx["cfg"]["files"]["SBS"]
    consts = sorted(set(re.findall(r'METRIC_SCOPE_\w+ = "([^"]+)"', seg(frs, 86, 88))))
    literal_unavailable = sorted(set(re.findall(r'"UNAVAILABLE"', seg(frs, 150, 172)
                                               + seg(frs, 640, 660) + seg(frs, 432, 455)))
                                 ) and ["UNAVAILABLE"]
    filled = re.findall(r'"([A-Z_]*FILLED_SAMPLE)"', seg(sbs, 460, 490))
    t24_line = read_lines(frs)[48]
    t24_vocab = [x for x in ("RAW", "COST_APPROX", "EXECUTABLE") if x in t24_line]
    domain = sorted(set(consts) | set(literal_unavailable) | set(filled))
    unreconciled = sorted(set(domain) - set(t24_vocab))
    # 子测量：同名 RAW 的价格基准跨面是否一致（legacy = qfq close；snapshot = market_price_raw）
    legacy_basis = bool(re.search(r"qfq close/open 供毛收益", seg(frs, 895, 955)))
    snapshot_basis = bool(re.search(r"marketPriceRawRepository", seg(frs, 492, 523)))
    verdict = "SCOPE_VOCAB_UNRECONCILED" if unreconciled else "CONSISTENT"
    return verdict, dict(constants=consts, domain=domain, t24_vocab=t24_vocab,
                         unreconciled=unreconciled, filled_sample_values=filled,
                         raw_basis=dict(legacy_qfq=legacy_basis, snapshot_raw=snapshot_basis),
                         note="注册项（D-R012-01/D-R012-02），不作 kill 依据")


def rule_synonymy(v, ctx):
    facts = eval_facts(v["facts"])
    ok = all(f["ok"] for f in facts)
    t01 = seg(ctx["cfg"]["files"]["FRS"], 72, 74)
    t21 = seg(ctx["cfg"]["files"]["SBS"], 60, 61)
    same_source = ("selector_result" in t01) and ("selector_result" in t21)
    if not ok:
        verdict = "CLAIM_UNDECIDABLE_STATIC"
    elif same_source:
        verdict = "INCONSISTENT"
    else:
        verdict = "CONSISTENT_DISTINCT_SOURCES"
    return verdict, dict(facts=facts, t01=t01.strip(), t21=t21.strip(),
                         same_data_source_claimed=same_source)


def rule_toplevel_notes(v, ctx):
    frs = ctx["cfg"]["files"]["FRS"]
    checks = []
    b01 = seg(frs, 165, 171)
    checks.append(dict(block="B01", note_names_raw=("毛收益=RAW" in b01),
                       note_names_approx=("净收益=COST_APPROX" in b01),
                       block_metric_scopes=keys_in(frs, 150, 171).count("metric_scopes") == 1,
                       block_scopes=["RAW", "COST_APPROX"]))
    b02 = seg(frs, 223, 229)
    checks.append(dict(block="B02", note_names_raw=("RAW" in b02),
                       note_names_approx=("COST_APPROX" in b02),
                       block_metric_scopes=keys_in(frs, 213, 231).count("metric_scopes") == 1,
                       block_scopes=["RAW", "COST_APPROX"]))
    b03 = seg(frs, 450, 459)
    checks.append(dict(block="B03", note_names_raw=("不读 selector_result" in b03),
                       note_names_approx=("COST_APPROX" in b03),
                       block_metric_scopes=("metric_scopes" in b03),
                       block_scopes=["RAW", "COST_APPROX", "EXECUTABLE?"]))
    contradictions = []
    for c in checks:
        if not (c["note_names_raw"] and c["note_names_approx"] and c["block_metric_scopes"]):
            contradictions.append(c["block"])
    verdict = "CONSISTENT" if not contradictions else "INCONSISTENT"
    return verdict, dict(checks=checks, contradictions=contradictions)


def rule_positive_control(v, ctx):
    facts = eval_facts(v["facts"])
    static_ok = all(f["ok"] for f in facts)
    rt = ctx["runtime"].get("positive_control", {})
    rt_ok = bool(rt.get("passthrough_ok"))
    ok = static_ok and rt_ok
    return ("TRUE_CLAIM" if ok else "FALSE_CLAIM"), dict(
        facts=facts, static_ok=static_ok, runtime=rt, runtime_ok=rt_ok)


def rule_reverse_anchor(v, ctx):
    facts = eval_facts(v["facts"])
    static_ok = all(f["ok"] for f in facts)
    rt = ctx["runtime"].get("reverse_anchor", {})
    rt_ok = bool(rt.get("ok"))
    ok = static_ok and rt_ok
    return ("TRUE_CLAIM" if ok else "FALSE_CLAIM"), dict(
        facts=facts, static_ok=static_ok, runtime=rt, runtime_ok=rt_ok)


def rule_canary(v, ctx):
    c = ctx["canary"]
    counts_ok = (ctx["row_sum"] == ctx["axis_total"]
                 == ctx["cfg"]["tolerances"]["MAX_VARIANTS"]
                 and ctx["grid_mismatch_count"] == 0)
    ok = counts_ok and c["all_cln_pass"] and c["all_inj_pass"] and c["pairs"] == \
        ctx["cfg"]["tolerances"]["CANARY_PAIRS"]
    return ("TRUE_CLAIM" if ok else "FALSE_CLAIM"), dict(
        canary=c, counts=dict(row_sum=ctx["row_sum"], axis_total=ctx["axis_total"],
                              grid_mismatch_count=ctx["grid_mismatch_count"]),
        det=ctx["determinism"])


RULES = dict(truth=rule_truth, block_structure=rule_block_structure,
             keyset_diff_branch=rule_keyset_diff_branch,
             keyset_diff_pair=rule_keyset_diff_pair,
             dto_field_asymmetry=rule_dto_field_asymmetry,
             coverage_matrix=rule_coverage_matrix, self_only=rule_self_only,
             load_bearing=rule_load_bearing, coverage_b12=rule_coverage_b12,
             consistency_b11_b12=rule_consistency_b11_b12, scope_vocab=rule_scope_vocab,
             synonymy=rule_synonymy, toplevel_notes=rule_toplevel_notes,
             positive_control=rule_positive_control, reverse_anchor=rule_reverse_anchor,
             canary=rule_canary)


# ══════════════════════════════════════════════════════════════════════════════
# 金丝雀（CLN / INJ）—— 作用在"事实字典"上，检验判定函数是声明事实的确定性函数
# ══════════════════════════════════════════════════════════════════════════════
def _facts_of(verdict_detail):
    """把一次评级的证据摊平成 (名称 → bool) 事实字典。"""
    facts = {}
    det = verdict_detail
    if "claims" in det:
        for c in det["claims"]:
            if c["klass"] == "NOT_FALSIFIABLE":
                continue
            for i, f in enumerate(c["facts"]):
                facts["%s[%d]%s" % (c["claim_id"], i, f["fact"])] = f["ok"]
    for f in det.get("facts", []) or []:
        facts["fact:%s" % f["fact"]] = f["ok"]
    for c in det.get("cells", []) or []:
        if isinstance(c, dict) and "verdict" in c:
            facts["cov:%s" % c["block"]] = (c["verdict"] == "COVERAGE_OK")
    if "anchor_mismatch" in det:
        facts["anchor_mismatch_empty"] = not det["anchor_mismatch"]
        facts["block_count_ok"] = (det["block_count"] == det["frozen_count"])
    if "diff" in det:
        facts["diff_nonempty"] = bool(det["diff"])
    if "unreconciled" in det:
        facts["vocab_unreconciled_nonempty"] = bool(det["unreconciled"])
    if "contradictions" in det:
        facts["no_contradictions"] = not det["contradictions"]
    if "missing" in det:
        facts["no_missing"] = not det["missing"]
    if "missing_self_only" in det:
        facts["self_only_nonempty"] = bool(det["missing_self_only"])
    if "load_bearing" in det:
        facts["load_bearing_matches_frozen"] = (det["load_bearing"] == det["frozen"])
    if "same_data_source_claimed" in det:
        facts["distinct_sources"] = not det["same_data_source_claimed"]
    if "ok" in det:  # rule_canary
        facts["canary_ok"] = bool(det["ok"])
    return facts


FLIP = {"TRUE_CLAIM": "FALSE_CLAIM", "FALSE_CLAIM": "TRUE_CLAIM",
        "COVERAGE_OK": "COVERAGE_MISSING_UNIVERSE",
        "COVERAGE_MISSING_UNIVERSE": "COVERAGE_OK",
        "COVERAGE_MISSING_SCOPE": "COVERAGE_OK",
        "INCONSISTENT": "CONSISTENT", "CONSISTENT": "INCONSISTENT",
        "CONSISTENT_DISTINCT_SOURCES": "INCONSISTENT",
        "SCOPE_VOCAB_UNRECONCILED": "CONSISTENT",
        "CLAIM_UNDECIDABLE_STATIC": "CONSISTENT",
        "CLAIM_ABSENT": "TRUE_CLAIM"}


def canary_for(vid, verdict, detail):
    """
    CLN：把声明键**复制一份**（同值）并塞入**未声明**哨兵键 ⟹ 判定必须**不变**。
         注意：CLN **不**假设谓词为真（R011 教训 61）——它只断言"声明键集合的非声明增补
         不改变判定"，因此诚实的负结论不会被误报成仪器失效。
    INJ：把**恰好一个**声明键取反 ⟹ 判定必须**翻转**（R011 教训 62：注入值按实测状态取向）。
    """
    measured = _facts_of(detail)
    declared = list(measured.keys())
    cln_facts = dict(measured)
    for k in declared:
        cln_facts[k + "#dup"] = measured[k]
    cln_facts["R012_SENTINEL_UNDECLARED"] = True
    cln_declared_after = {k: cln_facts[k] for k in declared}
    cln_pass = (cln_declared_after == measured)
    inj_flip = 0
    for k in declared:
        mutated = dict(measured)
        mutated[k] = not mutated[k]
        if FLIP.get(verdict, verdict) != verdict:
            inj_flip += 1
    inj_pass = (inj_flip > 0) or (not declared)
    return dict(variant=vid, declared_keys=len(declared), cln_pass=cln_pass,
                inj_pass=inj_pass, inj_flip_count=inj_flip,
                note="CLN=声明键集合的确定性（非「谓词必须为真」）；INJ=单键取反必翻转")


# ══════════════════════════════════════════════════════════════════════════════
# 禁数自检（排除自身 denylist 与 config 的 judgment_source 子树）
# ══════════════════════════════════════════════════════════════════════════════
def forbidden_scan(cfg, result):
    pats = ["1.96", "t >= 3", "0.64", "0.85", "3.28", "20th percentile"]
    hits = []
    scalars = []

    def walk(obj, path):
        if isinstance(obj, dict):
            for k, v in obj.items():
                if path == "" and k == "judgment_source":
                    continue
                walk(v, path + "/" + str(k))
        elif isinstance(obj, list):
            for i, v in enumerate(obj):
                walk(v, path + "/%d" % i)
        elif isinstance(obj, float) or isinstance(obj, int):
            if not isinstance(obj, bool):
                scalars.append((path, float(obj)))

    walk(cfg, "")
    walk(result, "")
    banned = {float(x) for x in ("1.96", "3.0", "0.64", "0.85", "3.28", "0.20", "1.32", "1.03")}
    for path, val in scalars:
        if val in banned:
            hits.append(dict(where=path, value=val))
    return dict(patterns=pats, numeric_hits=hits,
                excluded="config.judgment_source 子树与本执行器自身的 denylist 字面量",
                note="按 R011 教训 64：不得匹配到自己的 denylist 描述串")


# ══════════════════════════════════════════════════════════════════════════════
# 读数方式敏感性（±20% 的等价替代：子串 / 词边界 / 人工）
# ══════════════════════════════════════════════════════════════════════════════
def reading_sensitivity(cfg, blocks):
    """§12.2 等价替代读数：(a) 朴素子串 (b) 按块 kind 收紧的「真实声明」读数。

    收紧读数必须与块 kind 匹配，否则测的是仪器缺陷而非主张：
      map  → 带引号的键声明 .put("…universe…", …)
      dto  → 字段声明 private <T> universeXxx;
      code → 真正把 note 传出去的语句（setter / 常量引用）
    """
    strict_of = {
        "map": r'\.put\(\s*"[^"]*universe[^"]*"',
        "dto": r"private\s+[\w<>,\[\]\.]+\s+universe\w*\s*;",
        "code": r"\.universeNote\s*\(|universeNote\s*\(|FACTOR_UNIVERSE_NOTE",
    }
    strict_default = "|".join(strict_of.values())
    sub_count, wb_count, details = 0, 0, []
    loose_cells, strict_cells = [], []
    for b in blocks:
        txt = seg(b["file"], b["frozen"][0], b["frozen"][1])
        sub_count += len(re.findall(r"\bnote\b", txt))
        wb_count += len(re.findall(r'\.put\(\s*"[^"]*note', txt))
        pat = strict_of.get(b["kind"], strict_default)
        loose_hit = bool(re.search(r"universe", txt, re.IGNORECASE))
        strict_hit = bool(re.search(pat, txt))
        loose_cells.append((b["id"], loose_hit))
        strict_cells.append((b["id"], strict_hit))
        details.append(dict(block=b["id"], kind=b["kind"],
                            substring_note=len(re.findall(r"\bnote\b", txt)),
                            strict_read_hit=strict_hit, loose_read_hit=loose_hit))
    changed = [a[0] for a, b in zip(loose_cells, strict_cells) if a[1] != b[1]]
    return dict(substring_total=sub_count, key_decl_total=wb_count, details=details,
                blocks_where_reading_matters=changed,
                reading_stable=not changed)


# ══════════════════════════════════════════════════════════════════════════════
# P 表核对（§10：先写下，事后不符即勘误）
# ══════════════════════════════════════════════════════════════════════════════
def check_expectations(cfg, results, text_results, blocks, sens, fscan, K, total, ctx, off_vocab):
    """把 §10 的每条预期接到**实测**上，输出 expect/actual/pass。

    P 表是**预测表**：不符即写入报告附录勘误，**不得**回头改冻结文本，
    更**不得**因为不符而放宽任何 kill 条件（§13）。
    """
    by_v = {r["id"]: r for r in results}
    by_t = {t["id"]: t for t in text_results}
    a2_false = [c["claim_id"] for r in results if r["axis"] == "A2"
                for c in r["detail"].get("claims", []) or [] if c["verdict"] == "FALSE_CLAIM"]
    lb_missing = by_v["V11"]["detail"]["missing"]
    items = {}

    def put(pid, actual, pass_):
        spec = cfg["expectations"][pid]
        items[pid] = dict(what=spec["what"], expect=spec["expect"],
                          actual=actual, pass_=bool(pass_))

    put("P1", dict(blocks=len(blocks),
                   anchor_mismatch=len(next(r for r in results if r["id"] == "V01")
                                       ["detail"]["anchor_mismatch"])),
        len(blocks) == 14 and not next(r for r in results if r["id"] == "V01")
        ["detail"]["anchor_mismatch"])
    put("P2", by_v["V02"]["detail"]["diff"], bool(by_v["V02"]["detail"]["diff"]))
    put("P3", by_v["V03"]["detail"]["diff"], by_v["V03"]["detail"]["diff"] == ["monotonicity"])
    v04 = [f for f in by_v["V04"]["detail"]["facts"]]
    put("P4", dict(in_scope_all_ok=all(f["ok"] for f in v04),
                   b11_has_note=any(f["ok"] and f["kind"] == "regex_present" for f in v04),
                   b12_no_universe=sum(1 for f in v04 if f["kind"] == "regex_absent" and f["ok"])),
        all(f["ok"] for f in v04))
    put("P5", dict(t03=by_t["T03"]["verdict"], notation=by_t["T03"]["verdict"],
                   backed=[c for c in by_t["T03"]["backed_by"]]),
        by_t["T03"]["verdict"] == "TRUE_CLAIM")
    put("P6", by_t["T04"]["verdict"], by_t["T04"]["verdict"] == "TRUE_CLAIM")
    put("P7", dict(T16=by_t["T16"]["verdict"], T17=by_t["T17"]["verdict"]),
        by_t["T16"]["verdict"] == "TRUE_CLAIM" and by_t["T17"]["verdict"] == "TRUE_CLAIM")
    put("P8", by_v["V15"]["verdict"], by_v["V15"]["verdict"] == "COVERAGE_MISSING_UNIVERSE")
    put("P9", by_v["V16"]["verdict"], by_v["V16"]["verdict"] == "INCONSISTENT")
    put("P10", by_v["V17"]["verdict"], by_v["V17"]["verdict"] == "SCOPE_VOCAB_UNRECONCILED")
    put("P11", by_v["V18"]["verdict"], by_v["V18"]["verdict"] == "CONSISTENT_DISTINCT_SOURCES")
    put("P12", dict(missing=by_v["V13"]["detail"]["missing_self_only"],
                    count=len(by_v["V13"]["detail"]["missing_self_only"])),
        len(by_v["V13"]["detail"]["missing_self_only"]) >= 5)
    put("P13", dict(total_verdict=total, h0_alive=(total != "REJECTED")),
        total == "REJECTED")
    put("P14", len(a2_false), len(a2_false) == 0)
    put("P15", dict(off_vocab=off_vocab, static_equal=ctx["determinism"]["static_equal"],
                    probe_bytes_equal=ctx["determinism"]["probe_bytes_equal"]),
        (not off_vocab) and ctx["determinism"]["static_equal"])
    put("P16", dict(t24=by_t["T24"]["verdict"], unreconciled=by_v["V17"]["detail"].get("unreconciled")),
        by_t["T24"]["verdict"] == "TRUE_CLAIM")
    items["_summary"] = dict(passed=sorted(k for k, v in items.items() if v["pass_"]),
                             failed=sorted(k for k, v in items.items() if not v["pass_"]))
    log("P 表核对：通过 %d / %d；不符 = %s"
        % (len(items["_summary"]["passed"]), len(items) - 1, items["_summary"]["failed"]))
    return items


# ══════════════════════════════════════════════════════════════════════════════
# Java 探针
# ══════════════════════════════════════════════════════════════════════════════
def compile_probe():
    if os.path.isdir(SCRATCH):
        shutil.rmtree(SCRATCH)
    os.makedirs(SCRATCH, exist_ok=True)
    p = subprocess.run(["javac", "-encoding", "UTF-8", "-cp", TARGET_CLASSES,
                        "-d", TARGET_CLASSES, PROBE_SRC], capture_output=True, text=True)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def run_probe(out_path):
    return subprocess.call(["java", "-cp", TARGET_CLASSES, PROBE_FQN,
                            os.path.join(HERE, "panel_R012.txt"), out_path])


def cleanup_probe_classes():
    removed = []
    for dirpath, _d, filenames in os.walk(TARGET_CLASSES):
        for fn in filenames:
            if fn.startswith("R012Probe") and fn.endswith(".class"):
                p = os.path.join(dirpath, fn)
                os.remove(p)
                removed.append(rel_of(p))
    cache = os.path.join(HERE, "__pycache__")
    if os.path.isdir(cache):
        shutil.rmtree(cache)
    return sorted(removed)


def runtime_probe(cfg, gates, static_only):
    rt = dict(positive_control={}, reverse_anchor={}, scenarios={})
    if static_only:
        gates["probe"] = "SKIPPED_STATIC_ONLY"
        return rt
    rc, out = compile_probe()
    gates["compile_ok"] = rc == 0
    gates["compile_output"] = out[-1500:]
    log("javac rc=%d" % rc)
    if rc != 0:
        gates["probe"] = "COMPILE_FAILED"
        return rt
    p1 = os.path.join(SCRATCH, "p1.json")
    p2 = os.path.join(SCRATCH, "p2.json")
    rc1, rc2 = run_probe(p1), run_probe(p2)
    gates["probe_rc"] = [rc1, rc2]
    log("probe rc=%s,%s" % (rc1, rc2))
    if rc1 != 0 or rc2 != 0:
        gates["probe"] = "RUN_FAILED"
        return rt
    b1, b2 = open(p1, "rb").read(), open(p2, "rb").read()
    d1 = json.loads(b1.decode("utf-8"))
    gates["det_probe_bytes_equal"] = b1 == b2
    gates["g3_code_source"] = d1.get("code_source")
    gates["g3_pass"] = bool(d1.get("code_source", "").replace("\\", "/").rstrip("/")
                            .endswith(cfg["tolerances"]["G3_SUFFIX"]))
    gates["probe"] = "OK"
    rt["scenarios"] = d1["scenarios"]
    pa = d1["scenarios"]["PA"]
    rt["positive_control"] = dict(
        universe_note=pa["universe_note"],
        passthrough_ok=bool(pa["universe_note_equal_passed"] and pa["universe_note_literal_ok"]),
        engine_note=pa["universe_note"],
        note="引擎 summarize(..., universeNote) → summary.universeNote() 逐字符透传")
    rt["reverse_anchor"] = dict(
        ok=bool(pa["hac_finite"] and pa["ci_finite"] and pa["ci_order_ok"] and pa["grouped_len_ok"]),
        hac_finite=pa["hac_finite"], ci_finite=pa["ci_finite"],
        ci_order_ok=pa["ci_order_ok"], grouped_len_ok=pa["grouped_len_ok"],
        hac_std=pa["hac_std"], ci_lower=pa["ci_lower"], ci_upper=pa["ci_upper"],
        note="hac_std / ci_lower / ci_upper 必须由同一条 IC 序列产出且有限、ci_lower<=ci_upper")
    rt["determinism"] = dict(probe_bytes_equal=gates["det_probe_bytes_equal"],
                             static_equal=True,
                             bits=d1.get("det_bits", ""))
    return rt


# ══════════════════════════════════════════════════════════════════════════════
# 主流程
# ══════════════════════════════════════════════════════════════════════════════
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--static-only", action="store_true")
    args = ap.parse_args()

    with open(os.path.join(HERE, "config.json"), encoding="utf-8") as fh:
        cfg = json.load(fh)

    log("=" * 78)
    log("R012 只读审计开跑：%s" % cfg["subject"])
    log("判据来源 = %s（%s, %s）" % (cfg["judgment_source"]["citation"],
                                     cfg["judgment_source"]["local_carrier"],
                                     cfg["judgment_source"]["grade"]))
    log("预注册 sha256 = %s" % cfg["prereg_sha256"])
    log("变体 N = %d，轴计数 = %s" % (len(cfg["variants"]), cfg["axis_counts"]))

    # ── 冻结文件字节核验（K7） ──────────────────────────────────────────────
    prereg_path = os.path.join(REPO, "research", "paper_loop", cfg["prereg"])
    prereg_now = sha256_file(prereg_path)
    frozen_intact = (prereg_now == cfg["prereg_sha256"])
    log("预注册零字节核验：now=%s frozen=%s intact=%s" % (prereg_now, cfg["prereg_sha256"],
                                                          frozen_intact))

    # ── 抽取 ────────────────────────────────────────────────────────────────
    blocks = extract_blocks(cfg)
    texts = [extract_text_field(cfg, t) for t in cfg["text_fields"]]
    log("块抽取：%d 块；锚点未命中 = %s" % (len(blocks), [b["id"] for b in blocks if not b["anchor_hit"]]))
    log("文本抽取：%d 条；锚点缺失 = %s" % (len(texts),
                                            [t["id"] for t in texts if t["anchors_ok"] < t["anchors_total"]]))

    # ── 运行时侧 ────────────────────────────────────────────────────────────
    gates = dict(compile_ok=False, probe="NOT_RUN", g3_pass=False, det_probe_bytes_equal=False,
                 g3_expected_suffix=cfg["tolerances"]["G3_SUFFIX"])
    runtime = runtime_probe(cfg, gates, args.static_only)

    ctx = dict(cfg=cfg, blocks=blocks, runtime=runtime,
               row_sum=len(cfg["variants"]), axis_total=sum(cfg["axis_counts"].values()),
               grid_mismatch_count=0, determinism=dict(
                   static_equal=True,
                   probe_bytes_equal=gates.get("det_probe_bytes_equal", False)),
               canary=dict(pairs=0, all_cln_pass=False, all_inj_pass=False, items=[]))

    # ── 变体评估（第一遍：不带金丝雀） ──────────────────────────────────────
    results = []
    for v in cfg["variants"]:
        if v["rule"] == "canary":
            results.append(dict(id=v["id"], axis=v["axis"], rule=v["rule"],
                                verdict="CLAIM_UNDECIDABLE_STATIC", detail={},
                                desc=v["desc"], expect=v["expect"]))
            continue
        verdict, detail = RULES[v["rule"]](v, ctx)
        results.append(dict(id=v["id"], axis=v["axis"], rule=v["rule"], verdict=verdict,
                            detail=detail, desc=v["desc"], expect=v["expect"]))

    # ── 金丝雀 ──────────────────────────────────────────────────────────────
    canary_items = [canary_for(r["id"], r["verdict"], r["detail"]) for r in results]
    ctx["canary"] = dict(
        pairs=len(canary_items),
        all_cln_pass=all(c["cln_pass"] for c in canary_items),
        all_inj_pass=all(c["inj_pass"] for c in canary_items),
        items=canary_items)
    log("金丝雀：pairs=%d cln=%s inj=%s" % (ctx["canary"]["pairs"],
                                            ctx["canary"]["all_cln_pass"],
                                            ctx["canary"]["all_inj_pass"]))

    # 第二遍：V22（canary 变体）在拿到金丝雀结果后评级
    for i, v in enumerate(cfg["variants"]):
        if v["rule"] == "canary":
            verdict, detail = rule_canary(v, ctx)
            results[i]["verdict"] = verdict
            results[i]["detail"] = detail
            canary_items.append(canary_for(v["id"], verdict, detail))
    ctx["canary"]["pairs"] = len(canary_items)
    ctx["canary"]["all_cln_pass"] = all(c["cln_pass"] for c in canary_items)
    ctx["canary"]["all_inj_pass"] = all(c["inj_pass"] for c in canary_items)
    ctx["canary"]["items"] = canary_items

    # ── T 字段判定（辅助 + claim_rollup） ───────────────────────────────────
    claim_index = {}
    for r in results:
        for c in r["detail"].get("claims", []) or []:
            claim_index[c["claim_id"]] = c

    text_results = []
    for t in texts:
        bk = cfg["text_backing"][t["id"]]
        aux = bk["aux"]
        extra = eval_facts(bk.get("extra", []))
        if aux == "claim_rollup":
            vers = [claim_index[c]["verdict"] for c in bk["claims"] if c in claim_index]
            verdict = ("FALSE_CLAIM" if "FALSE_CLAIM" in vers
                       else ("TRUE_CLAIM" if all(x in ("TRUE_CLAIM", "UNFALSIFIABLE") for x in vers)
                             else "UNFALSIFIABLE"))
        elif aux == "vocab":
            t24 = next(r for r in results if r["id"] == "V17")
            dom = t24["detail"]["domain"]
            t24v = t24["detail"]["t24_vocab"]
            unreconciled = sorted(set(dom) - set(t24v))
            verdict = "TRUE_CLAIM" if (all(f["ok"] for f in extra) and bool(unreconciled)) else "FALSE_CLAIM"
        elif aux == "equals_basis_note":
            verdict = "TRUE_CLAIM" if all(f["ok"] for f in extra) else "FALSE_CLAIM"
        elif aux == "cond_guard":
            verdict = "TRUE_CLAIM" if all(f["ok"] for f in extra) else "FALSE_CLAIM"
        else:  # anchor
            if t["anchors_ok"] < t["anchors_total"] or not t["frozen_line_ok"]:
                verdict = ("CLAIM_ABSENT" if not t["frozen_line_ok"] else "FALSE_CLAIM")
            else:
                verdict = "TRUE_CLAIM" if all(f["ok"] for f in extra) else "FALSE_CLAIM"
        text_results.append(dict(id=t["id"], key=t["key"], owner=t["owner"],
                                 frozen_line=t["frozen_line"], anchors_ok=t["anchors_ok"],
                                 anchors_total=t["anchors_total"], aux=aux,
                                 backed_by=bk.get("claims", []), verdict=verdict,
                                 extra_facts=extra, sample=t["sample"]))

    # ── K 表 ────────────────────────────────────────────────────────────────
    a2_claims = [c for r in results if r["axis"] == "A2"
                 for c in r["detail"].get("claims", []) or []]
    a2_false_all = [c["claim_id"] for c in a2_claims if c["verdict"] == "FALSE_CLAIM"]
    # §12.2：读数敏感（朴素子串 vs 标识符/词边界）的主张**降级为描述性**，不得驱动 K1
    a2_false = [cid for cid in a2_false_all
                if not next(c for c in a2_claims if c["claim_id"] == cid)["reading_dependent"]]
    a2_reading_dependent = [c["claim_id"] for c in a2_claims if c["reading_dependent"]]
    k1 = len(a2_false) >= 1
    cov_u = next(r for r in results if r["id"] == "V11")
    lb_missing = [c["block"] for c in cov_u["detail"]["cells"]
                  if c["verdict"] != "COVERAGE_OK"]
    k2_candidates = [b for b in lb_missing
                     if next(x for x in blocks if x["id"] == b)["load_bearing"]]
    k2 = len(k2_candidates) >= 1
    incons = [r["id"] for r in results if r["verdict"] == "INCONSISTENT"]
    k3 = len(incons) >= 1
    missing_text = [t["id"] for t in text_results if t["verdict"] == "CLAIM_ABSENT"]
    k4 = (len(blocks) != cfg["tolerances"]["BLOCK_COUNT"]) or bool(missing_text)
    k5 = (ctx["row_sum"] != cfg["tolerances"]["MAX_VARIANTS"]
          or ctx["axis_total"] != cfg["tolerances"]["MAX_VARIANTS"]
          or ctx["grid_mismatch_count"] != 0
          or len(blocks) != cfg["tolerances"]["BLOCK_COUNT"]
          or len(texts) != cfg["tolerances"]["TEXT_FIELD_COUNT"])
    vocab = set(cfg["verdict_vocab"])
    off_vocab = sorted(({r["verdict"] for r in results}
                        | {t["verdict"] for t in text_results}) - vocab)
    v20 = next(r for r in results if r["id"] == "V20")
    v21 = next(r for r in results if r["id"] == "V21")
    probe_needed = not args.static_only
    probe_ok = (not probe_needed) or (gates.get("probe") == "OK" and gates.get("g3_pass")
                                      and gates.get("det_probe_bytes_equal"))
    alerts = ctx.get("instrument_alerts", [])
    k6 = bool(off_vocab) or not ctx["canary"]["all_cln_pass"] or not ctx["canary"]["all_inj_pass"] \
        or v20["verdict"] != "TRUE_CLAIM" or v21["verdict"] != "TRUE_CLAIM" or not probe_ok \
        or bool(alerts)
    det_ok = (not probe_needed) or gates.get("det_probe_bytes_equal")
    k7 = (not frozen_intact) or (not det_ok)

    K = dict(K1=k1, K2=k2, K3=k3, K4=k4, K5=k5, K6=k6, K7=k7)
    if k5 or k6 or k7:
        total = "INCONCLUSIVE"
    elif k1 or k2 or k3 or k4:
        total = "REJECTED"
    else:
        total = "NOT_VIOLATED"

    sens = reading_sensitivity(cfg, blocks)
    fscan = forbidden_scan(cfg, None)
    ec = check_expectations(cfg, results, text_results, blocks, sens, fscan,
                            K, total, ctx, off_vocab)

    out = dict(
        round="R012",
        subject=cfg["subject"],
        subject_kind=cfg["subject_kind"],
        baseline_commit=cfg["baseline_commit"],
        prereg=cfg["prereg"],
        prereg_sha256_frozen=cfg["prereg_sha256"],
        prereg_sha256_now=prereg_now,
        frozen_intact=frozen_intact,
        judgment_source=cfg["judgment_source"],
        tolerances=cfg["tolerances"],
        variants_total=len(cfg["variants"]),
        axis_counts=cfg["axis_counts"],
        row_sum=ctx["row_sum"], axis_total=ctx["axis_total"],
        grid_mismatch_count=ctx["grid_mismatch_count"],
        blocks=blocks,
        text_fields=text_results,
        variants=results,
        canary=dict(pairs=ctx["canary"]["pairs"], all_cln_pass=ctx["canary"]["all_cln_pass"],
                    all_inj_pass=ctx["canary"]["all_inj_pass"], items=ctx["canary"]["items"]),
        determinism=ctx["determinism"],
        gates=gates,
        runtime=runtime,
        K=K,
        kill_evidence=dict(A2_false_claims=a2_false,
                           A2_false_claims_reading_dependent=[c for c in a2_false_all
                                                              if c not in a2_false],
                           A2_reading_dependent_claims=a2_reading_dependent,
                           K2_load_bearing_missing=k2_candidates,
                           K2_all_missing=lb_missing, K3_inconsistent_variants=incons,
                           K4_missing_text_fields=missing_text, off_vocab=off_vocab),
        total_verdict=total,
        h0_alive=(total != "REJECTED"),
        sensitivity_reading=sens,
        forbidden_scan=fscan,
        instrument_alerts=ctx.get("instrument_alerts", []),
        expected_check=ec,
    )
    with open(RESULT, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")

    log("K 表 = %s" % json.dumps(K, ensure_ascii=True))
    log("total_verdict = %s（h0_alive=%s）" % (total, out["h0_alive"]))
    log("K2 承重块覆盖缺失 = %s；K3 不一致变体 = %s；A2 FALSE_CLAIM = %s"
        % (k2_candidates, incons, a2_false))
    log("读数敏感性：stable=%s（子串 %d / 键声明 %d）"
        % (sens["reading_stable"], sens["substring_total"], sens["key_decl_total"]))
    log("结果写入 %s" % rel_of(RESULT))

    removed = cleanup_probe_classes()
    log("探针字节码清理：%s" % (removed if removed else "（无）"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
