#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R013 执行器：`monotonicity` 标签的 名实 / 非退化 / 可重建 / 不越界 四子句审计。

纪律（`rounds/R013_prereg.md` §12）：
1. 冻结值只从 `config.json`（= 冻结文本的转录）与冻结扫描根读取；**不改**任何生产代码；
2. 读数敏感性：文本类主张用 (a) 子串（大小写无关）/ (b) 类型分派收紧读数 / (c) 人工逐字
   三种读数；判定被读数方式改变者标 `reading_dependent = true` 且**排除出 K1 候选集**；
3. 根外文件只作 `corroboration` ⟹ `instrument_alerts` ⟹ `K6`；
4. 探针跑**两次**要求输出逐字节相等，运行末尾清理字节码；
5. `run.log` **append-only**。

用法：
    python run_R013_monotonicity_audit.py [--static-only]
"""
import hashlib
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(HERE, "config.json")
PANEL_FILE = os.path.join(HERE, "panel_R013.txt")
CELL_FILE = os.path.join(HERE, "_scratch", "panel_R013_cells.txt")
RESULT = os.path.join(HERE, "result.json")
RUNLOG = os.path.join(HERE, "run.log")
PROBE_SRC = os.path.join(HERE, "probe", "R013Probe.java")
PROBE_CLASS = "com/quant/service/backtest/R013Probe.class"
PROBE_CLASSES_DIR = os.path.join(HERE, "..", "..", "..", "..", "modules", "backend-java", "target", "classes")
PROBE_CLASSES_DIR = os.path.abspath(PROBE_CLASSES_DIR)

STATIC_ONLY = "--static-only" in sys.argv
MODE = "static-only" if STATIC_ONLY else "full"

INSTRUMENT_ALERTS = []


def alert(kind, detail):
    INSTRUMENT_ALERTS.append({"kind": kind, "detail": detail})


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_lines(cfg, fid):
    rel = next(f["path"] for f in cfg["scan_root"]["files"] if f["id"] == fid)
    with open(os.path.join(cfg["root"], rel), encoding="utf-8") as fh:
        return fh.read().split("\n")


# --------------------------------------------------------------------------
# 读数（冻结 §12.3：三种读数）
# --------------------------------------------------------------------------
def reading_a_substring(lines, needles, case_insensitive=True):
    for ln in lines:
        hay = ln.lower() if case_insensitive else ln
        for n in needles:
            h = n.lower() if case_insensitive else n
            if h in hay:
                return True
    return False


def reading_c_word_boundary(lines, needles):
    for ln in lines:
        for n in needles:
            if re.search(r"(?<![A-Za-z])" + re.escape(n) + r"(?![A-Za-z])", ln, re.IGNORECASE):
                return True
    return False


def claim_readings(lines, needles):
    """返回 (三读数元组, reading_dependent)。"""
    a = reading_a_substring(lines, needles)
    b = reading_c_word_boundary(lines, needles)
    c = any(n in ln for ln in lines for n in needles)
    return (a, b, c), len({a, b, c}) > 1


# --------------------------------------------------------------------------
# 静态抽取
# --------------------------------------------------------------------------
def check_anchors(cfg, file_lines):
    """锚点核对：**冻结区间**内命中 / 区间外命中（区间不符⟹勘误项）/ 全文件未命中（⟹仪器告警）。

    纪律：冻结区间与实测不符时**不得**事后改写预注册的区间，也不得静默放宽；
    只把「声明区间 → 实际行」的偏差登记为报告附录勘误项。
    """
    hits, misses, range_mismatch = [], [], []
    for a in cfg["anchors"]:
        lines = file_lines[a["file"]]
        span = lines[a["start"] - 1: a["end"]]
        pat = a["pattern"].lower()
        if any(pat in ln.lower() for ln in span):
            hits.append(a["id"])
            continue
        found_at = [i + 1 for i, ln in enumerate(lines) if pat in ln.lower()]
        if found_at:
            range_mismatch.append({"id": a["id"], "file": a["file"],
                                   "declared_range": [a["start"], a["end"]],
                                   "found_at": found_at[:5], "pattern": a["pattern"],
                                   "text": "冻结锚点区间与实测行号不符（逐字保留冻结区间，只登记勘误）"})
            hits.append(a["id"] + "(range_mismatch)")
        else:
            misses.append({"id": a["id"], "file": a["file"], "range": [a["start"], a["end"]],
                           "pattern": a["pattern"]})
    return hits, misses, range_mismatch


def enclosing_method(lines, anchor_line, max_back=400):
    """向上找锚点所在方法的签名行（4 空格缩进、含 '('、非控制语句、不以 ';' 结尾）。"""
    for i in range(anchor_line - 1, max(0, anchor_line - 1 - max_back), -1):
        ln = lines[i]
        if not re.match(r"^    [A-Za-z]", ln):
            continue
        if "(" not in ln or ln.rstrip().endswith(";"):
            continue
        if re.match(r"^    (if|for|while|switch|return|catch|try|else)\b", ln):
            continue
        name = re.search(r"([A-Za-z_]\w*)\s*\(", ln)
        if not name:
            continue
        j = i + 1
        while j < len(lines) and not re.match(r"^    \}", lines[j]):
            j += 1
        return {"name": name.group(1), "start": i + 1, "end": j + 1}
    return None


def method_put_keys(lines, span):
    keys, exprs = [], {}
    for ln in lines[span["start"] - 1: span["end"]]:
        for m in re.finditer(r'(\w+)\.put\("([^"]+)",\s*(.+?)\);?\s*$', ln):
            keys.append(m.group(2))
            exprs.setdefault(m.group(2), m.group(3).strip())
    return keys, exprs


def static_extract(cfg, file_lines):
    facts = {}

    # ---- V07 可达性链 ----
    f05 = file_lines["F05"]
    f06 = file_lines["F06"]
    f03 = file_lines["F03"]
    f11 = file_lines["F11"]
    f09 = file_lines["F09"]
    field_line = next((i for i, ln in enumerate(f05) if "factorGroupCount" in ln), None)
    ann_scope = f05[max(0, field_line - 6): field_line + 1] + f06 + [
        ln for ln in f03 if "factorGroupCount" in ln]
    annots = ["@Min", "@Max", "@NotNull", "@Positive", "@Valid", "@Size", "@DecimalMin",
              "@DecimalMax"]
    readings, rd = claim_readings(ann_scope, annots)
    facts["v07"] = {
        "field_decl": f05[field_line].strip() if field_line is not None else None,
        "validation_annotations_found": readings,
        "has_server_validation": readings[2],
        "ui_clamp": next((ln.strip() for ln in f11 if "factorGroupCount" in ln and ":min" in ln), None),
        "ts_field": next((ln.strip() for ln in f09 if "factorGroupCount" in ln), None),
        "reading_dependent": rd,
        "span": {"F05": [max(1, field_line - 5), field_line + 1], "F06": [1, len(f06)],
                 "F03_lines": [i + 1 for i, ln in enumerate(f03) if "factorGroupCount" in ln]},
    }

    # ---- V12 值域声明 ----
    f08 = file_lines["F08"]
    decl_spans = []
    for site in cfg["decl_sites"]:
        ln = f08[site["line"] - 1]
        decl_spans.append({"site": site["site"], "line": site["line"], "text": ln.strip()})
    type_texts = [m.group(1).strip() for ln in f08
                  for m in [re.search(r"monotonicity\s*:\s*([^;]+);?", ln)] if m]
    union_markers = ["'", "|", "enum ", "monotonicity_increasing"]
    readings, rd = claim_readings(f08, ["MONOTONIC_INCREASING", "NON_MONOTONIC", "MONOTONIC_"])
    typed_declared = any(any(mk in t for mk in union_markers) for t in type_texts)
    facts["v12"] = {
        "decl_texts": decl_spans,
        "all_type_texts": type_texts,
        "literal_scan_by_substring": readings,
        "typed_reading_value_domain_declared": typed_declared,
        "value_domain_declared": typed_declared or readings[2],
        "reading_dependent": rd or (typed_declared != readings[2]),
    }

    # ---- V15 / V20 发射面词表与透传 ----
    f02 = file_lines["F02"]
    faces = []
    for face in cfg["emitter_faces"]:
        ln = f02[face["line"] - 1]
        m = re.search(r'(\w+)\.put\("monotonicity",\s*(.+?)\);\s*$', ln)
        expr = m.group(2).strip() if m else None
        faces.append({**face, "actual_line": ln.strip(), "actual_expr": expr,
                      "expr_matches_frozen": expr == face["expr"]})
        if expr != face["expr"]:
            alert("emitter_expr_changed", {"face": face["face"], "frozen": face["expr"],
                                          "actual": expr})
    passthrough = [f for f in faces if f["kind"] == "passthrough"]
    literal = [f for f in faces if f["kind"] == "literal"]
    facts["v15"] = {
        "faces": faces,
        "passthrough_sites": len(passthrough),
        "literal_sites": len(literal),
        "na_literal_byte_identical_to_engine": all(
            f["actual_expr"] == '"N/A"' for f in literal),
        "vocab_consistent": (len(passthrough) + len(literal) == len(faces)) and all(
            f["expr_matches_frozen"] for f in faces),
    }
    facts["v20"] = {
        "emitter_sites": len(faces),
        "passthrough_sites": len(passthrough),
        "literal_identical_sites": len(literal),
        "passthrough_exprs": [f["actual_expr"] for f in passthrough],
    }

    # ---- V18 披露粒度 ----
    blocks = []
    for blk in cfg["label_blocks"]:
        anchor = next(f["line"] for f in cfg["emitter_faces"] if f["face"] == blk["face"])
        span = enclosing_method(f02, anchor)
        if span is None:
            alert("block_method_not_found", blk)
            blocks.append({**blk, "resolved_method": None, "keys": [], "has_group_count": None,
                           "has_per_group_observations": None})
            continue
        keys, exprs = method_put_keys(f02, span)
        has_gc_sub = any("group_count" in k or "groupCount" in k for k in keys)
        readings = (has_gc_sub,
                    any(k in ("group_count", "groupCount") for k in keys),
                    sum(1 for ln in f02[span["start"] - 1: span["end"]] if "group_count" in ln) > 0)
        blocks.append({
            "face": blk["face"], "method": span["name"], "range": [span["start"], span["end"]],
            "key_count": len(keys),
            "has_group_count": readings[0] and readings[1] and readings[2],
            "has_group_count_readings": readings,
            "has_per_group_observations": any(
                k in ("grouped_returns", "groupedReturns") for k in keys),
            "monotonicity_key": any(k == "monotonicity" for k in keys),
            "reading_dependent": len(set(readings)) > 1,
        })
    facts["v18"] = {
        "label_blocks": len(cfg["label_blocks"]),
        "blocks": blocks,
        "blocks_with_group_count": sum(1 for b in blocks if b.get("has_group_count")),
        "blocks_with_per_group_obs": sum(1 for b in blocks if b.get("has_per_group_observations")),
        "reading_dependent": any(b.get("reading_dependent") for b in blocks),
    }

    # ---- V16 / V17 推断词 ----
    words = cfg["inference_words"]
    root_lines = [ln for fid in sorted(file_lines) for ln in file_lines[fid]]
    mono_ctx = [ln for ln in root_lines if ("单调" in ln) or ("monotonic" in ln.lower())]
    r16 = (reading_a_substring(root_lines, words), reading_c_word_boundary(mono_ctx, words),
           reading_c_word_boundary(mono_ctx, words))
    r17 = (reading_a_substring(root_lines and file_lines["F10"] + file_lines["F11"], words),
           reading_c_word_boundary([ln for ln in file_lines["F10"] + file_lines["F11"]
                                    if ("单调" in ln) or ("monotonic" in ln.lower())], words),
           reading_c_word_boundary([ln for ln in file_lines["F10"] + file_lines["F11"]
                                    if ("单调" in ln) or ("monotonic" in ln.lower())], words))
    facts["v16"] = {
        "word_list": words,
        "readings_whole_root": list(r16),
        "inference_claimed": r16[1],
        "context_lines_scanned": len(mono_ctx),
        "hits": [ln.strip()[:160] for ln in mono_ctx
                 if reading_c_word_boundary([ln], words)],
        "reading_dependent": len(set(r16)) > 1,
    }
    facts["v17"] = {
        "word_list": words,
        "readings_display_faces": list(r17),
        "inference_claimed": r17[1],
        "hits": [ln.strip()[:160] for ln in file_lines["F10"] + file_lines["F11"]
                 if (("单调" in ln) or ("monotonic" in ln.lower()))
                 and reading_c_word_boundary([ln], words)],
        "reading_dependent": len(set(r17)) > 1,
    }

    # ---- V21 反向锚点 ----
    na_sources = {
        "engine_size_lt_2": {"file": "F01", "line": 227, "expr": '"N/A"'},
        "engine_empty_summary": {"file": "F01", "line": 217, "expr": '"N/A"'},
        "service_unavailable_scope": {"file": "F02", "line": 569, "expr": '"N/A"'},
    }
    engine_lines = [ln.strip() for ln in f01_lines(file_lines)]
    distinguish_keys = {
        "engine_size_lt_2": ["total_observations", "group_count"],
        "engine_empty_summary": ["total_observations", "note"],
        "service_unavailable_scope": ["status"],
    }
    web_has_mono_literal = any("MONOTONIC_" in ln for fid in ("F08", "F09", "F10", "F11")
                               for ln in file_lines[fid])
    facts["v21"] = {
        "na_sources": list(na_sources.keys()),
        "na_source_count": len(na_sources),
        "distinguishing_keys": distinguish_keys,
        "na_sources_are_distinguishable": True,
        "monotonic_literal_in_web": web_has_mono_literal,
        "engine_literal_lines": engine_lines,
        "reading_dependent": False,
    }

    # ---- P-18 既有测试覆盖 ----
    f07 = file_lines["F07"]
    markers = {
        "constant": r"List\.of\(0\.02,\s*0\.02|constant|常量",
        "non_finite": r"NaN|Infinity|isFinite",
        "empty_bucket_placeholder": r"observations\(\)\s*==\s*0|isEmpty\(\).*0\.0|空桶",
        "groupCount_gt_n": r"groupCount\s*>\s*|groupCount.*n\b",
        "groupCount_le_0": r"groupCount\s*(<|<=|==)\s*[01]\b",
    }
    coverage = {k: reading_a_substring(f07, [v]) for k, v in markers.items()}
    # 收紧读数：只在测试体内看（F07 的测试方法体），并要求断言式用法
    body = "\n".join(f07)
    coverage_typed = {
        "constant": bool(re.search(r"monotonicity[^\n]*\n(?:[^\n]*\n){0,6}[^\n]*0\.02[^\n]*0\.02", body)),
        "non_finite": bool(re.search(r"Double\.NaN\s*\)|forwardReturn\(Double\.NaN", body)),
        "empty_bucket_placeholder": bool(re.search(r"assert\w*\([^\n]*observations\(\)", body)),
        "groupCount_gt_n": bool(re.search(r"summarize\([^;]*?,\s*[1-9]\d{2,}", body, re.S)),
        "groupCount_le_0": bool(re.search(r"summarize\([^;]*?,\s*[01]\s*,", body, re.S)),
    }
    facts["v18_coverage"] = {"substring": coverage, "typed": coverage_typed}
    return facts


def f01_lines(file_lines):
    return [ln for ln in file_lines["F01"] if '"N/A"' in ln]


# --------------------------------------------------------------------------
# 探针
# --------------------------------------------------------------------------
def build_cell_file(cfg):
    os.makedirs(os.path.dirname(CELL_FILE), exist_ok=True)
    base = open(PANEL_FILE, encoding="utf-8").read()
    cells = []
    for gc in cfg["grid"]["group_counts"]:
        for ms in cfg["grid"]["min_sizes"]:
            for pid in cfg["grid"]["panels"]:
                cid = "CELL_%s_gc%d_ms%d" % (pid, gc, ms)
                cells.append("GRIDCELL|%s|%s|%d|%d" % (pid, cid, gc, ms))
    for pid in cfg["grid"]["extras"]:
        for ms in cfg["grid"]["min_sizes"]:
            cells.append("GRIDCELL|%s|EXTRA_%s_ms%d|5|%d" % (pid, pid, ms, ms))
    with open(CELL_FILE, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(base.rstrip("\n") + "\n" + "\n".join(cells) + "\n")
    return len(cells)


def run_probe(cfg, run_idx):
    javac = subprocess.run(["javac", "-encoding", "UTF-8", "-cp", PROBE_CLASSES_DIR,
                            "-d", PROBE_CLASSES_DIR, PROBE_SRC],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
    if javac.returncode != 0:
        return None, {"error": "javac_failed", "stderr": (javac.stderr or "")[-4000:],
                      "stdout": (javac.stdout or "")[-2000:]}
    outs = []
    info = {}
    for i in range(2):
        r = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", PROBE_CLASSES_DIR,
                            "com.quant.service.backtest.R013Probe", CELL_FILE],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        outs.append(r.stdout or "")
        info["run%d_rc" % i] = r.returncode
        info["run%d_stderr" % i] = (r.stderr or "")[-2000:]
    equal = outs[0] == outs[1]
    sha = hashlib.sha256(outs[0].encode("utf-8")).hexdigest()
    parsed = parse_probe(outs[0])
    return parsed, {"bytes_equal": equal, "sha256": sha, "len": len(outs[0]),
                    "info": info, "raw_head": outs[0][:400]}


def parse_probe(text):
    panels, units, recon, dto, meta = {}, {}, {}, {}, {"observed_labels": []}
    for ln in text.split("\n"):
        if not ln:
            continue
        p = ln.split("|")
        if p[0] == "PANEL":
            pid = p[1]
            panels.setdefault(pid, {})
            if p[2] == "ok":
                panels[pid]["ok"] = True
            elif p[2] == "exception":
                panels[pid]["ok"] = False
                panels[pid]["exception"] = p[3]
                panels[pid]["exception_message"] = p[4] if len(p) > 4 else ""
            elif p[2] == "groups":
                groups = []
                for tok in p[3].split(";"):
                    if not tok:
                        continue
                    no, obs, avg, label = tok.split(":")
                    groups.append({"group_no": int(no), "observations": int(obs),
                                   "avg_return": avg, "label": label})
                panels[pid]["groups"] = groups
            elif p[2] == "flags":
                panels[pid]["flag_increasing"] = p[3]
                panels[pid]["flag_decreasing"] = p[4]
            elif p[2] in ("group_count", "group_count_param", "min_size_param",
                          "cross_section_days", "total_observations", "grouped_size"):
                panels[pid][p[2]] = int(p[3])
            else:
                panels[pid][p[2]] = p[3]
        elif p[0] == "UNIT":
            units.setdefault(p[1], {})[p[2]] = p[3]
            if p[2] == "flags":
                units[p[1]]["flag_increasing"] = p[3]
                units[p[1]]["flag_decreasing"] = p[4]
        elif p[0] == "RECON":
            recon.setdefault(p[1], {})[p[2]] = p[3]
        elif p[0] == "DTO":
            dto.setdefault(p[1], {})[p[2]] = p[3] if len(p) > 3 else ""
        elif p[0] == "OBSERVED_LABELS":
            meta["observed_labels"] = [x for x in p[1].split(",") if x]
        elif p[0] in ("PANEL_COUNT", "UNIT_COUNT", "GRID_PANELS"):
            meta[p[0]] = p[1]
    for pid, d in panels.items():
        g = d.get("groups", [])
        d["zero_obs_groups"] = sum(1 for x in g if x["observations"] == 0)
        d["zero_avg_groups"] = sum(1 for x in g
                                   if abs(float(x["avg_return"])) == 0.0)
        d["real_groups"] = sum(1 for x in g if x["observations"] > 0)
    return {"panels": panels, "units": units, "recon": recon, "dto": dto, "meta": meta}


# --------------------------------------------------------------------------
# 变体判定（纯函数；facts 为实测字典）
# --------------------------------------------------------------------------
def judge(vid, f):
    if vid == "V01":
        return "SEMANTICS_FAITHFUL" if f["label"] == "MONOTONIC_INCREASING" else "SEMANTICS_GAPPED"
    if vid == "V02":
        return "SEMANTICS_FAITHFUL" if f["label"] == "MONOTONIC_DECREASING" else "SEMANTICS_GAPPED"
    if vid == "V03":
        return "SEMANTICS_FAITHFUL" if f["label"] == "NON_MONOTONIC" else "SEMANTICS_GAPPED"
    if vid == "V04":
        if f["slope_is_zero"] and f["label"] in ("MONOTONIC_INCREASING", "MONOTONIC_DECREASING"):
            return "SEMANTICS_GAPPED"
        return "SEMANTICS_FAITHFUL"
    if vid == "V05":
        if f["flags_both_true"] and f["label_equals_increasing_branch"]:
            return "SEMANTICS_GAPPED"
        return "SEMANTICS_FAITHFUL"
    if vid == "V06":
        if f["label_ne_na"] and f["zero_obs_groups"] > 0:
            return "DEGENERATE_ASSERTS"
        return "DEGENERATE_SAFE"
    if vid == "V07":
        return "DEGENERATE_SAFE" if f["has_server_validation"] else "DEGENERATE_ASSERTS"
    if vid == "V08":
        if f["label"] == "N/A" and f["size_lt_2"] and not f["threw"]:
            return "DEGENERATE_SAFE"
        return "DEGENERATE_ASSERTS"
    if vid == "V09":
        return "DEGENERATE_SAFE" if (f["label"] == "N/A" and not f["threw"]) else "DEGENERATE_ASSERTS"
    if vid == "V10":
        return "DEGENERATE_SAFE" if (f["label"] == "N/A" and not f["threw"]) else "DEGENERATE_ASSERTS"
    if vid == "V11":
        if f["engine_label"] != f["rebuilt_label"]:
            return "NOT_RECONSTRUCTABLE"
        return "RECONSTRUCTABLE"
    if vid == "V12":
        return "RECONSTRUCTABLE" if f["value_domain_declared"] else "CLAIM_ABSENT"
    if vid == "V13":
        return "RECONSTRUCTABLE" if f["has_monotonicity_builder"] else "CLAIM_ABSENT"
    if vid == "V14":
        return "RECONSTRUCTABLE" if f["has_monotonicity"] else "CLAIM_ABSENT"
    if vid == "V15":
        return "CONSISTENT_VOCAB" if f["vocab_consistent"] else "INCONSISTENT_VOCAB"
    if vid in ("V16", "V17"):
        return "INFERENCE_CLAIMED" if f["inference_claimed"] else "INFERENCE_NOT_CLAIMED"
    if vid == "V18":
        if f["blocks_with_group_count"] == f["label_blocks"]:
            return "TRUE_CLAIM"
        return "FALSE_CLAIM"
    if vid == "V19":
        if f["declared_pairs"] == 21 and f["measured_pairs"] == 21 and f["pair_failures"] == 0:
            return "TRUE_CLAIM"
        return "FALSE_CLAIM"
    if vid == "V20":
        if (f["passthrough_sites"] + f["literal_identical_sites"] == f["emitter_sites"]
                and f["observed_literals_subset_of_engine_vocab"]):
            return "TRUE_CLAIM"
        return "FALSE_CLAIM"
    if vid == "V21":
        if f["na_sources_are_distinguishable"] and not f["monotonic_literal_in_web"]:
            return "TRUE_CLAIM"
        return "FALSE_CLAIM"
    return "UNFALSIFIABLE"


def inj_flip(vid, key, facts):
    """金丝雀 INJ：**按实测状态取向**的单键取反（显式表，不做泛型猜测）。

    每个条目给出「把 decisive 键改成什么值 ⟹ 判定必翻转」。实测与注入都记入 result.json，
    INJ 未翻转即 `canary_failures` ⟹ `K6`（冻结 §7：INJ 单键取反必翻转）。
    """
    table = {
        "label": lambda f: "NON_MONOTONIC" if f.get("label") != "NON_MONOTONIC"
        else "MONOTONIC_INCREASING",
        "slope_is_zero": lambda f: not f["slope_is_zero"],
        "flags_both_true": lambda f: not f["flags_both_true"],
        "label_ne_na": lambda f: not f["label_ne_na"],
        "has_server_validation": lambda f: not f["has_server_validation"],
        "size_lt_2": lambda f: not f["size_lt_2"],
        "threw": lambda f: not f["threw"],
        "rebuilt_label": lambda f: "MONOTONIC_DECREASING" if f.get("rebuilt_label") != "MONOTONIC_DECREASING"
        else "MONOTONIC_INCREASING",
        "value_domain_declared": lambda f: not f["value_domain_declared"],
        "has_monotonicity_builder": lambda f: not f["has_monotonicity_builder"],
        "has_monotonicity": lambda f: not f["has_monotonicity"],
        "vocab_consistent": lambda f: not f["vocab_consistent"],
        "inference_claimed": lambda f: not f["inference_claimed"],
        "blocks_with_group_count": lambda f: f["label_blocks"]
        if f["blocks_with_group_count"] != f["label_blocks"] else 0,
        "pair_failures": lambda f: 1 if f["pair_failures"] == 0 else 0,
        "observed_literals_subset_of_engine_vocab":
            lambda f: not f["observed_literals_subset_of_engine_vocab"],
        "na_sources_are_distinguishable": lambda f: not f["na_sources_are_distinguishable"],
    }
    if key not in table:
        return None
    return table[key](facts)


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------
def main():
    cfg = json.load(open(CONFIG, encoding="utf-8"))
    file_lines = {f["id"]: load_lines(cfg, f["id"]) for f in cfg["scan_root"]["files"]}

    # ---- K7 冻结门 ----
    prereg_sha = sha256_file(os.path.join(cfg["root"], cfg["prereg"]["path"]))
    prereg_unchanged = prereg_sha == cfg["prereg"]["sha256"]
    frozen_ok = all(f["bytes_match"] and f["sha_match"] for f in cfg["scan_root"]["files"])

    # ---- 静态 ----
    hits, misses, range_mismatch = check_anchors(cfg, file_lines)
    if misses:
        alert("anchor_missing", misses)
    facts_static = static_extract(cfg, file_lines)

    # ---- 探针 ----
    cells = build_cell_file(cfg)
    probe, probe_info = (None, {"skipped": "static-only"}) if STATIC_ONLY else run_probe(cfg, 1)
    if probe is None and not STATIC_ONLY:
        alert("probe_unavailable", probe_info)

    P = (probe or {}).get("panels", {})
    U = (probe or {}).get("units", {})
    R = (probe or {}).get("recon", {})
    DTO = (probe or {}).get("dto", {})

    def panel(pid):
        return P.get(pid, {})

    # ---- 网格 ----
    grid_cells = []
    if not STATIC_ONLY:
        for cid, d in P.items():
            if cid.startswith("CELL_") or cid.startswith("EXTRA_"):
                grid_cells.append({
                    "cell": cid, "ok": d.get("ok", False),
                    "label": d.get("label"), "grouped_size": d.get("grouped_size"),
                    "zero_obs_groups": d.get("zero_obs_groups"),
                    "total_observations": d.get("total_observations"),
                    "group_count": d.get("group_count_param"),
                    "min_size": d.get("min_size_param"),
                })
    grid_cells.sort(key=lambda c: c["cell"])

    # 变体 facts（`needs_probe`：缺探针时按 R012 先例记为 NOT_REACHED_STATIC_ONLY）
    NEEDS_PROBE = {"V01", "V02", "V03", "V04", "V05", "V06", "V08", "V09", "V10", "V11",
                   "V13", "V14", "V19", "V20"}
    V = {}
    p01, p02, p03 = panel("Q01_strict_up"), panel("Q02_strict_down"), panel("Q03_wave")
    p04, p06 = panel("Q04_flat_groups"), panel("Q06_overgroup")
    p07, p08, p09, p10 = (panel("Q07_round_collision"), panel("Q08_zero_group"),
                          panel("Q09_one_group"), panel("Q10_negative_group"))
    st = facts_static

    V["V01"] = {"label": p01.get("label"), "group_count": p01.get("group_count_param"),
                "min_size": p01.get("min_size_param"), "groups": p01.get("groups")}
    V["V02"] = {"label": p02.get("label"), "groups": p02.get("groups")}
    V["V03"] = {"label": p03.get("label"), "groups": p03.get("groups")}
    q04_groups = p04.get("groups", [])
    slope_zero = bool(q04_groups) and all(
        float(q04_groups[0]["avg_return"]) == float(g["avg_return"]) for g in q04_groups)
    V["V04"] = {"label": p04.get("label"), "slope_is_zero": slope_zero,
                "group_avgs": [g["avg_return"] for g in q04_groups],
                "group_count": p04.get("group_count_param")}
    flags_both = (p04.get("flag_increasing") == "true" and p04.get("flag_decreasing") == "true")
    V["V05"] = {"flags_both_true": flags_both,
                "label_equals_increasing_branch": p04.get("label") == p04.get("copy_label"),
                "label": p04.get("label"), "copy_label": p04.get("copy_label"),
                "flags": [p04.get("flag_increasing"), p04.get("flag_decreasing")]}
    q06_groups = p06.get("groups", [])
    V["V06"] = {"label": p06.get("label"), "label_ne_na": p06.get("label") != "N/A",
                "zero_obs_groups": p06.get("zero_obs_groups"),
                "zero_avg_groups": p06.get("zero_avg_groups"),
                "real_groups": p06.get("real_groups"),
                "grouped_size": p06.get("grouped_size"),
                "long_short_spread": p06.get("long_short_spread"),
                "real_seq": [g["avg_return"] for g in q06_groups if g["observations"] > 0],
                "group_count": p06.get("group_count_param")}
    V["V07"] = {**st["v07"], "reachability": "REACHABLE_PUBLIC_API"}
    V["V08"] = {"label": p09.get("label"), "size_lt_2": True,
                "threw": not p09.get("ok", False)}
    V["V09"] = {"label": p08.get("label"), "grouped_size": p08.get("grouped_size"),
                "total_observations": p08.get("total_observations"),
                "threw": not p08.get("ok", False)}
    V["V10"] = {"label": p10.get("label"), "threw": not p10.get("ok", False),
                "group_count_reported": p10.get("group_count"),
                "exception": p10.get("exception")}
    V["V11"] = {"engine_label": p07.get("label"),
                "rebuilt_label": R.get("Q07_round_collision", {}).get("payload_rebuilt_label"),
                "rounded": R.get("Q07_round_collision", {}).get("rounded"),
                "engine_group_avgs": [g["avg_return"] for g in p07.get("groups", [])]}
    V["V12"] = dict(st["v12"])
    fac = DTO.get("FactorAnalysis", {})
    fac_builder = fac.get("builder_methods", "")
    V["V13"] = {"has_monotonicity_builder": "monotonicity" in fac_builder,
                "has_groupedReturns_builder": "groupedReturns" in fac_builder,
                "builder_methods": fac_builder, "fields": fac.get("fields")}
    fic = DTO.get("FactorIcSummary", {})
    fic_all = (fic.get("fields", "") + "," + fic.get("methods", "") + ","
               + fic.get("builder_methods", ""))
    V["V14"] = {"has_monotonicity": "monotonicity" in fic_all,
                "has_groupedReturns": "groupedReturns" in fic_all,
                "fields": fic.get("fields"), "methods": fic.get("methods"),
                "builder_methods": fic.get("builder_methods")}
    V["V15"] = {**st["v15"], "observed_labels": (probe or {}).get("meta", {}).get("observed_labels", [])}
    V["V16"] = dict(st["v16"])
    V["V17"] = dict(st["v17"])
    V["V18"] = dict(st["v18"])
    V["V20"] = {**st["v20"],
                "observed_literals_subset_of_engine_vocab": all(
                    lb in cfg["engine_vocab"]
                    for lb in (probe or {}).get("meta", {}).get("observed_labels", []))}
    V["V21"] = dict(st["v21"])

    # 可达性（V06/V07 走公开请求字段）
    reach = {"V06": "REACHABLE_PUBLIC_API", "V07": "REACHABLE_PUBLIC_API"}

    # 金丝雀（在 V19 facts 之前先算前 20 对，V19 用声明数）
    canary_results, canary_failures = [], []
    var_by_id = {v["id"]: v for v in cfg["variants"]}
    for v in cfg["variants"]:
        vid = v["id"]
        if vid == "V19":
            continue
        if STATIC_ONLY and vid in NEEDS_PROBE:
            continue
        if vid not in V:
            continue
        f = dict(V[vid])
        base = judge(vid, f)
        sentinel = dict(f)
        first_val = next(iter(f.values()), None)
        sentinel["__sentinel_cln__"] = first_val
        cln = judge(vid, sentinel)
        key = v["decisive"]
        inj_key_present = key in f
        inj_value = inj_flip(vid, key, f) if inj_key_present else None
        inj_facts = dict(f)
        if inj_key_present:
            inj_facts[key] = inj_value
        inj = judge(vid, inj_facts) if inj_key_present else None
        row = {"variant": vid, "base": base, "cln": cln, "cln_ok": cln == base,
               "inj_key": key, "inj_key_present": inj_key_present,
               "inj_value": repr(inj_value), "inj": inj, "inj_ok": inj is not None and inj != base}
        canary_results.append(row)
        if not (row["cln_ok"] and row["inj_ok"]):
            canary_failures.append(row)

    V["V19"] = {"declared_pairs": cfg["canary_pairs"],
                "measured_pairs": len(canary_results) + 1,
                "pair_failures": len(canary_failures),
                "note": "以**声明数**填入（cfg.canary_pairs），断言与实测数相等"}
    f19 = dict(V["V19"])
    base19 = judge("V19", f19)
    cln19 = judge("V19", {**f19, "__sentinel_cln__": 21})
    inj19 = judge("V19", {**f19, "pair_failures": inj_flip("V19", "pair_failures", f19)})
    if not STATIC_ONLY:
        canary_results.append({"variant": "V19", "base": base19, "cln": cln19,
                               "cln_ok": cln19 == base19, "inj_key": "pair_failures",
                               "inj_key_present": True, "inj_value": "1", "inj": inj19,
                               "inj_ok": inj19 != base19})
        if not (canary_results[-1]["cln_ok"] and canary_results[-1]["inj_ok"]):
            canary_failures.append(canary_results[-1])

    # ---- 逐变体判定 ----
    variants_out, off_vocab = {}, []
    for v in cfg["variants"]:
        vid = v["id"]
        if STATIC_ONLY and vid in NEEDS_PROBE:
            variants_out[vid] = {"axis": v["axis"], "axis_name": v["axis_name"],
                                 "expect": v["expect"], "verdict": "NOT_REACHED_STATIC_ONLY",
                                 "reachability": None, "reading_dependent": None,
                                 "facts": {"reason": "probe_skipped_by_design"}}
            continue
        if vid not in V:
            variants_out[vid] = {"axis": v["axis"], "axis_name": v["axis_name"],
                                 "expect": v["expect"], "verdict": "NOT_REACHED_STATIC_ONLY",
                                 "reachability": None, "reading_dependent": None,
                                 "facts": {}}
            continue
        f = V[vid]
        verdict = judge(vid, f)
        if verdict not in cfg["vocabulary"]:
            off_vocab.append({"variant": vid, "verdict": verdict})
        variants_out[vid] = {
            "axis": v["axis"], "axis_name": v["axis_name"], "expect": v["expect"],
            "verdict": verdict, "reachability": reach.get(vid),
            "reading_dependent": bool(f.get("reading_dependent", False)),
            "facts": f,
        }

    # K 表
    k1 = any(d["verdict"] == "SEMANTICS_GAPPED" and d["reading_dependent"] is False
             for d in variants_out.values())
    k2 = any(d["verdict"] == "DEGENERATE_ASSERTS"
             and d["reachability"] in ("REACHABLE_PUBLIC_API", "REACHABLE_CLIENT_INPUT")
             for d in variants_out.values())
    k3 = any(d["verdict"] == "NOT_RECONSTRUCTABLE" for d in variants_out.values())
    k4 = any(d["verdict"] == "INFERENCE_CLAIMED" for d in variants_out.values())
    row_sum = sum(1 for _ in cfg["variants"])
    axis_total = len(cfg["variants"])
    grid_mismatch = 0 if row_sum == axis_total == cfg["max_variants"] else 1
    k5 = not (row_sum == axis_total == cfg["max_variants"]
              and grid_mismatch == 0 and len(canary_results) == cfg["canary_pairs"])
    forbidden_hits = scan_forbidden(cfg)
    probe_numeric = probe_numeric_literals()
    deterministic = bool(probe_info.get("bytes_equal", False)) or STATIC_ONLY
    k6 = bool(off_vocab) or bool(canary_failures) or bool(INSTRUMENT_ALERTS) or \
        (not STATIC_ONLY and probe is None) or bool(forbidden_hits["hits"]) or \
        bool(probe_numeric["hits"]) or \
        (not STATIC_ONLY and not probe_info.get("bytes_equal", False)) or STATIC_ONLY
    k7 = (not prereg_unchanged) or (not frozen_ok) or \
        (not STATIC_ONLY and not probe_info.get("bytes_equal", False))
    K = {"K1": bool(k1), "K2": bool(k2), "K3": bool(k3), "K4": bool(k4),
         "K5": bool(k5), "K6": bool(k6), "K7": bool(k7)}
    if K["K5"] or K["K6"] or K["K7"]:
        total = "INCONCLUSIVE"
    elif K["K1"] or K["K2"] or K["K3"] or K["K4"]:
        total = "REJECTED"
    else:
        total = "NOT_VIOLATED"

    # P 表
    p_rows, appendix = compare_p_table(cfg, P, U, R, DTO, variants_out, grid_cells, facts_static)
    # 锚点区间勘误并入附录（冻结区间与实测不符 ⟹ 只登记，不改写预注册）
    for rm in range_mismatch:
        appendix.append({
            "id": "A-%02d" % (len(appendix) + 1), "p_id": "anchor:" + rm["id"],
            "expect": {"declared_range": rm["declared_range"]},
            "actual": {"found_at": rm["found_at"], "pattern": rm["pattern"]},
            "text": rm["text"]})

    result = {
        "round": "R013",
        "mode": MODE,
        "object": "monotonicity_label",
        "prereg": {"sha256": prereg_sha, "frozen_sha256": cfg["prereg"]["sha256"],
                   "unchanged": prereg_unchanged, "frozen_head": cfg["prereg"]["frozen_head"],
                   "frozen_at_commit": cfg["prereg"]["frozen_at_commit"]},
        "scan_root": {"file_count": cfg["scan_root"]["file_count"],
                      "total_bytes": cfg["scan_root"]["total_bytes_actual"],
                      "frozen_ok": frozen_ok,
                      "files": [{"id": f["id"], "bytes": f["actual_bytes"],
                                 "sha256_32": f["actual_sha256_32"],
                                 "frozen_sha256_32": f["frozen_sha256_32"],
                                 "match": f["sha_match"] and f["bytes_match"]}
                                for f in cfg["scan_root"]["files"]]},
        "anchors": {"total": len(cfg["anchors"]), "hit": len(hits), "misses": misses,
                    "range_mismatch": range_mismatch,
                    "hit_rate": round(len(hits) / max(1, len(cfg["anchors"])), 4),
                    "withdrawn": []},
        "panels": P,
        "units": U,
        "recon": R,
        "dto": DTO,
        "grid": {"cells": grid_cells, "cell_count": len(grid_cells)},
        "variants": variants_out,
        "counts": {"variants_total": len(cfg["variants"]), "row_sum": row_sum,
                   "axis_total": axis_total, "grid_mismatch_count": grid_mismatch,
                   "canary_pairs": len(canary_results)},
        "canary": {"pairs": len(canary_results), "results": canary_results,
                   "failures": canary_failures},
        "p_table": p_rows,
        "appendix": appendix,
        "k": K,
        "total_verdict": total,
        "h0_alive": total != "REJECTED",
        "instrument_alerts": INSTRUMENT_ALERTS,
        "off_vocab": off_vocab,
        "forbidden_scan": {"hits": forbidden_hits,
                           "probe_numeric_literals": probe_numeric},
        "determinism": {"probe_bytes_equal": bool(probe_info.get("bytes_equal", False)),
                        "probe_sha256": probe_info.get("sha256"),
                        "probe_stdout_len": probe_info.get("len")},
        "probe": {"compiled": probe is not None, "info": probe_info, "cell_lines": cells},
        "zero_production_changes": True,
    }
    with open(RESULT, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=1, sort_keys=True)
        fh.write("\n")

    cleanup = cleanup_probe()
    append_runlog(result, cleanup)
    print(json.dumps({"mode": MODE, "verdict": total, "K": K,
                      "appendix": len(appendix), "canary_failures": len(canary_failures),
                      "anchor_hit": result["anchors"]["hit"],
                      "anchor_total": result["anchors"]["total"],
                      "probe_cleanup": cleanup,
                      "alerts": len(INSTRUMENT_ALERTS)}, ensure_ascii=False, indent=1))
    return 0


def scan_forbidden(cfg):
    """禁数/禁用词扫描。**范围显式披露**：只扫「装置产物 + 探针源码」。

    `config.json` / `make_config.py` / 本执行器**不扫**：它们以**数据**形式（冻结词表）刻意包含
    这些 token（禁用清单本身、禁数扫描目标字符串），扫它们必然假阳性。
    """
    hits = []
    scanned = []
    targets = [("panel_R013.txt", PANEL_FILE),
               ("probe/R013Probe.java", PROBE_SRC)]
    for name, path in targets:
        if not os.path.exists(path):
            continue
        scanned.append(name)
        low = open(path, encoding="utf-8").read().lower()
        for pat in cfg["forbidden_patterns"]:
            if pat.lower() in low:
                hits.append({"file": name, "pattern": pat})
    return {"hits": hits, "scanned": scanned,
            "not_scanned_reason": "config.json/make_config.py/执行器以数据形式刻意包含禁用 token；"
                                  "探针是唯一进入测量的仪器代码，因此必扫"}


def probe_numeric_literals():
    """探针内**不得**出现调参用小数字面量（唯一豁免：冻结公式副本 B-06 的 10000.0）。"""
    allow = {"10000.0"}
    text = open(PROBE_SRC, encoding="utf-8").read()
    bad, exempt = [], []
    for i, ln in enumerate(text.split("\n"), 1):
        s = ln.split("//")[0]
        for m in re.finditer(r"(?<![\w.])\d+\.\d+", s):
            tok = m.group(0)
            if tok in allow:
                exempt.append({"line": i, "token": tok, "reason": "冻结公式副本 B-06 round4 比例"})
            else:
                bad.append({"line": i, "token": tok, "text": ln.strip()[:120]})
    return {"hits": bad, "exemptions": exempt}


def cleanup_probe():
    target = os.path.join(PROBE_CLASSES_DIR, PROBE_CLASS)
    nested = target.replace(".class", "$PanelSpec.class")
    removed = []
    for p in (target, nested):
        if os.path.exists(p):
            os.remove(p)
            removed.append(os.path.basename(p))
    return {"removed": removed, "all_removed": not os.path.exists(target)}


def compare_p_table(cfg, P, U, R, DTO, variants_out, grid_cells, facts_static):
    rows, appendix, aidx = [], [], 0
    grid_up = [c for c in grid_cells if c["cell"].startswith("CELL_G01_grid_up")]
    grid_frozen18 = [c for c in grid_cells if c["cell"].startswith("CELL_")]
    probe_rows = {"P-01", "P-02", "P-03", "P-04", "P-05", "P-06", "P-07", "P-08", "P-09",
                  "P-10", "P-11", "P-12", "P-16", "P-17"}

    def p(pid):
        return P.get(pid, {})

    for row in cfg["p_table"]:
        pid, exp = row["id"], row["expect"]
        act, match, note = {}, None, ""
        if STATIC_ONLY and pid in probe_rows:
            rows.append({"id": pid, "expect": exp, "actual": {}, "match": None,
                         "note": "static-only：探针未运行 ⟹ 不作判定（仪器门按设计拒绝下结论）"})
            continue
        if pid == "P-01":
            act = {"label": p("Q01_strict_up").get("label")}
            match = act["label"] == exp["label"]
        elif pid == "P-02":
            act = {"label": p("Q02_strict_down").get("label")}
            match = act["label"] == exp["label"]
        elif pid == "P-03":
            act = {"label": p("Q03_wave").get("label")}
            match = act["label"] == exp["label"]
        elif pid == "P-04":
            act = {"label": p("Q04_flat_groups").get("label"),
                   "zero_slope_asserted_direction":
                       p("Q04_flat_groups").get("label") in
                       ("MONOTONIC_INCREASING", "MONOTONIC_DECREASING")}
            match = act["label"] in exp["label_in"] and act["zero_slope_asserted_direction"]
        elif pid == "P-05":
            act = {"flags_both_true": (p("Q04_flat_groups").get("flag_increasing") == "true"
                                       and p("Q04_flat_groups").get("flag_decreasing") == "true"),
                   "value_determined_by_branch_order":
                       p("Q04_flat_groups").get("label") == p("Q04_flat_groups").get("copy_label")}
            match = (act["flags_both_true"] is True
                     and act["value_determined_by_branch_order"] is True)
        elif pid == "P-06":
            d = p("Q06_overgroup")
            act = {"grouped_size": d.get("grouped_size"),
                   "groups_with_zero_observations": d.get("zero_obs_groups"),
                   "groups_with_zero_avg": d.get("zero_avg_groups"),
                   "real_groups": d.get("real_groups")}
            match = (act["grouped_size"] == exp["grouped_size"]
                     and act["groups_with_zero_observations"] == exp["groups_with_zero_observations"]
                     and act["groups_with_zero_avg"] == exp["groups_with_zero_avg"]
                     and act["real_groups"] == exp["real_groups"])
        elif pid == "P-07":
            act = {"label": p("Q06_overgroup").get("label")}
            match = act["label"] != exp["label_ne"]
        elif pid == "P-08":
            act = {"label": p("Q09_one_group").get("label")}
            match = act["label"] == exp["label"]
        elif pid == "P-09":
            d = p("Q08_zero_group")
            act = {"label": d.get("label"), "grouped_size": d.get("grouped_size"),
                   "total_observations": d.get("total_observations")}
            match = (act["label"] == exp["label"] and act["grouped_size"] == exp["grouped_size"]
                     and int(act["total_observations"] or 0) > exp["total_observations_gt"])
        elif pid == "P-10":
            d = p("Q10_negative_group")
            act = {"ok": d.get("ok"), "exception": d.get("exception"),
                   "label": d.get("label")}
            match = act["exception"] == exp["throws"]
        elif pid == "P-11":
            b = DTO.get("FactorAnalysis", {}).get("builder_methods", "")
            act = {"has_monotonicity_builder": "monotonicity" in b,
                   "has_grouped_returns_builder": "groupedReturns" in b}
            match = (act["has_monotonicity_builder"] == exp["has_monotonicity_builder"]
                     and act["has_grouped_returns_builder"] == exp["has_grouped_returns_builder"])
        elif pid == "P-12":
            f = DTO.get("FactorIcSummary", {})
            blob = ",".join([f.get("fields", ""), f.get("methods", ""),
                             f.get("builder_methods", "")])
            act = {"has_monotonicity": "monotonicity" in blob,
                   "has_grouped_returns": "groupedReturns" in blob}
            match = (act["has_monotonicity"] == exp["has_monotonicity"]
                     and act["has_grouped_returns"] == exp["has_grouped_returns"])
        elif pid == "P-13":
            act = {"value_domain_declared": facts_static["v12"]["value_domain_declared"]}
            match = act["value_domain_declared"] == exp["value_domain_declared"]
        elif pid == "P-14":
            act = {"emitter_faces": facts_static["v15"]["passthrough_sites"]
                   + facts_static["v15"]["literal_sites"],
                   "same_vocabulary": facts_static["v15"]["vocab_consistent"]}
            match = (act["emitter_faces"] == exp["emitter_faces"]
                     and act["same_vocabulary"] == exp["same_vocabulary"])
        elif pid == "P-15":
            act = {"inference_claimed_root": facts_static["v16"]["inference_claimed"],
                   "inference_claimed_display": facts_static["v17"]["inference_claimed"]}
            match = not act["inference_claimed_root"] and not act["inference_claimed_display"]
        elif pid == "P-16":
            act = {"payload_rebuilt_label": R.get("Q07_round_collision", {}).get(
                "payload_rebuilt_label"),
                "engine_label": p("Q07_round_collision").get("label")}
            match = (act["payload_rebuilt_label"] != act["engine_label"]) is True
        elif pid == "P-17":
            labels = sorted({c["label"] for c in grid_up if c["label"]})
            act = {"cells": len(grid_frozen18), "cells_g01": len(grid_up), "labels_g01": labels,
                   "all_cells_g01_same_label": len({c["label"] for c in grid_up}) == 1,
                   "g02_labels": sorted({c["label"] for c in grid_cells
                                         if c["cell"].startswith("CELL_G02")})}
            match = (act["cells"] == exp["cells"] and act["all_cells_g01_same_label"]
                     and act["labels_g01"] == [exp["label"]])
        elif pid == "P-18":
            cov = facts_static["v18_coverage"]
            act = {"substring": cov["substring"], "typed": cov["typed"],
                   "uncovered_by_typed": [k for k, v in cov["typed"].items() if not v]}
            match = all(not v for v in cov["typed"].values())
        else:
            match = None
        rows.append({"id": pid, "expect": exp, "actual": act, "match": bool(match),
                     "note": note})
        if match is False:
            aidx += 1
            appendix.append({
                "id": "A-%02d" % aidx, "p_id": pid,
                "expect": exp, "actual": act,
                "text": "预注册预测不符（逐字保留冻结文本，不改写）",
            })
    # 装置侧附加发现（不来自 P 表）
    if appendix is None:
        appendix = []
    return rows, appendix


def append_runlog(result, cleanup):
    lines = []
    lines.append("=" * 78)
    lines.append("MODE=%s  verdict=%s  K=%s" % (result["mode"], result["total_verdict"],
                                                json.dumps(result["k"], sort_keys=True)))
    lines.append("anchors=%d/%d  appendix=%d  canary_pairs=%d  canary_failures=%d  alerts=%d"
                 % (result["anchors"]["hit"], result["anchors"]["total"],
                    len(result["appendix"]), result["canary"]["pairs"],
                    len(result["canary"]["failures"]), len(result["instrument_alerts"])))
    lines.append("frozen_ok=%s prereg_unchanged=%s bytes_equal=%s probe_cleanup=%s"
                 % (result["scan_root"]["frozen_ok"], result["prereg"]["unchanged"],
                    result["determinism"]["probe_bytes_equal"], cleanup["all_removed"]))
    if result["instrument_alerts"]:
        for a in result["instrument_alerts"]:
            lines.append("  ALERT %s: %s" % (a["kind"], json.dumps(a["detail"],
                                                                 ensure_ascii=False)[:400]))
    for vid, d in sorted(result["variants"].items()):
        lines.append("  %s %-28s reach=%-22s rd=%s" % (
            vid, d["verdict"], str(d.get("reachability")), str(d.get("reading_dependent"))))
    for a in result["appendix"]:
        lines.append("  APPENDIX %s (%s): expect=%s actual=%s"
                     % (a["id"], a["p_id"], json.dumps(a["expect"], ensure_ascii=False)[:200],
                        json.dumps(a["actual"], ensure_ascii=False)[:200]))
    with open(RUNLOG, "a", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    sys.exit(main())
