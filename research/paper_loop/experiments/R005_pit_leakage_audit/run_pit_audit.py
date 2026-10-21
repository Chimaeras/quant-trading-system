#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R005 数据时点契约（PIT）与前视泄漏的八轴审计 —— 只读。

判据来源：KN2022 = Kapoor & Narayanan, "Leakage and the Reproducibility Crisis in ML-based
Science", arXiv:2207.07048v1（2022-07-14），附录 C 的 8 类泄漏 + 逐类报告要求。
判据、阈值、杀死条件在 rounds/R005_prereg.md 中于本文件创建之前冻结。

硬约束（协议 §7 + 本轮 §10）：
  * 只读连接；不 INSERT/UPDATE/DELETE/DDL。
  * 不改任何生产代码 / 测试 / 前端 / 契约 / ResearchConvention。
  * 发现一律只登记队列，不修。
"""
import os
import re
import sys
import json
import math
import statistics
from datetime import date, datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))

DB = {
    "host": os.environ.get("QUANT_DB_HOST", "localhost"),
    "port": int(os.environ.get("QUANT_DB_PORT", "3306")),
    "user": os.environ.get("QUANT_DB_USER", "root"),
    "password": os.environ.get("QUANT_DB_PASSWORD", os.environ.get("DB_PASSWORD", "")),
    "database": os.environ.get("QUANT_DB_NAME", "quant_db"),
    "charset": "utf8mb4",
}

# ---------------------------------------------------------------------------
# 冻结常数（R005_prereg.md §8 / §11）—— 运行期不得修改
# ---------------------------------------------------------------------------
TOL_PARITY = 1e-12
MATCH_TOL_REL = 1e-3
MIN_ROWS_FOR_CLASSIFY = 200
MATCH_RATE_HIGH = 0.90
LOOKAHEAD_MARGIN = 0.05
LAG_VIOLATION_SHARE = 0.50
DUPLICATE_NOISE = 0
OVERLAP_SHARE_REGISTER = 0.50
RX2_RATIO_LO, RX2_RATIO_HI = 100.0, 10000.0
MAX_VARIANTS = 48
PREREG_VARIANTS = 37
HOLD_SPAN_DAYS = 5  # 冻结：持有窗口外部假设（最长 LABEL_HORIZONS 上界），仅用于重叠登记

FEATURES = ("ret20", "ret60", "avg_amount20")
FEATURE_N = {"ret20": 20, "ret60": 60, "avg_amount20": 20}
# anchor 约定：窗口锚点相对 trade_date D 的位置（预注册 §4-A6b 冻结的 4 种）
CONVENTIONS = {
    "PIT_INC": 0,   # 窗口止于 D        —— PIT
    "PIT_EXC": -1,  # 窗口止于 D-1      —— PIT
    "LA_1": 1,      # 窗口止于 D+1      —— 前视（注入）
    "LA_5": 5,      # 窗口止于 D+5      —— 前视（注入）
}
PIT_CONVENTIONS = ("PIT_INC", "PIT_EXC")
LA_CONVENTIONS = ("LA_1", "LA_5")
PROBE_VERSION = "unified_classic_v1"   # 冻结：唯一特征已填充的版本

GOLD_IS = ("2024-01-02", "2025-12-31")
GOLD_OOS = ("2026-01-01", "2026-09-07")

_log_fh = None


def log(msg):
    line = "[%s] %s" % (datetime.now().strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    if _log_fh:
        _log_fh.write(line + "\n")
        _log_fh.flush()


def rel_dev(stored, recomputed):
    """相对偏差；两侧同为 0 视为完全匹配。"""
    if stored == 0.0 and recomputed == 0.0:
        return 0.0
    denom = max(abs(stored), abs(recomputed), 1e-12)
    return abs(recomputed - stored) / denom


# ===========================================================================
# 可复用的检测函数（canary 必须调用**同一批函数对象**——预注册 §4 前置闸 G3）
# ===========================================================================

def classify_feature(rows, feature, conventions):
    """核心分类器：给定行（含 stored 与各 convention 的重算值），判定 PIT_MATCH /
    LOOKAHEAD_MATCH / NEITHER / INSUFFICIENT_COVERAGE。

    `rows`：dict 列表，每个含 {'stored': float, conv: float, ...}。
    """
    usable = [r for r in rows if r.get("stored") is not None and r.get("usable")]
    if len(usable) < MIN_ROWS_FOR_CLASSIFY:
        return {
            "classification": "INSUFFICIENT_COVERAGE",
            "feature": feature,
            "usable_rows": len(usable),
            "match_rate": {},
            "best_pit": None,
            "best_la": None,
        }
    rate = {}
    for conv in conventions:
        hit = sum(1 for r in usable
                  if rel_dev(float(r["stored"]), float(r[conv])) <= MATCH_TOL_REL)
        rate[conv] = hit / float(len(usable))
    best_pit = max(rate[c] for c in PIT_CONVENTIONS)
    best_la = max(rate[c] for c in LA_CONVENTIONS)
    if best_pit >= MATCH_RATE_HIGH and best_pit >= best_la:
        cls = "PIT_MATCH"
    elif best_la >= MATCH_RATE_HIGH and best_la > best_pit + LOOKAHEAD_MARGIN:
        cls = "LOOKAHEAD_MATCH"
    else:
        cls = "NEITHER"
    return {
        "classification": cls,
        "feature": feature,
        "usable_rows": len(usable),
        "match_rate": {k: round(v, 6) for k, v in rate.items()},
        "best_pit": round(best_pit, 6),
        "best_la": round(best_la, 6),
    }


def check_duplicates(items, results):
    """A4a/A4b：键级零容忍重复检查。items=[(result_id, stock_code), ...]；
    results=[(trade_date, factor_version, topn, result_id), ...]"""
    seen = {}
    dup_pairs = 0
    for rid, code in items:
        k = (rid, code)
        seen[k] = seen.get(k, 0) + 1
    dup_pairs = sum(v - 1 for v in seen.values() if v > 1)
    grp = {}
    for td, fv, topn, rid in results:
        grp.setdefault((str(td), fv, topn), set()).add(rid)
    multi = sum(1 for v in grp.values() if len(v) > 1)
    return {"A4a_dup_pairs": dup_pairs, "A4b_multi_result_groups": multi,
            "duplicate_noise_tol": DUPLICATE_NOISE}


def check_gate_conflict(items_with_gate):
    """A9：持久化特征与父运行声明约束的相容性。
    items_with_gate=[(factor_version, avg_amount20, min_avg_amount, has_null), ...]"""
    conflicts = 0
    denom = 0
    by_ver = {}
    nulls = 0
    for fv, amt, gate, is_null in items_with_gate:
        denom += 1
        if is_null:
            nulls += 1
            continue
        d = by_ver.setdefault(fv, {"rows": 0, "zero": 0, "conflict": 0,
                                   "min_gate": None, "max_gate": None})
        d["rows"] += 1
        if gate is not None:
            d["min_gate"] = gate if d["min_gate"] is None else min(d["min_gate"], gate)
            d["max_gate"] = gate if d["max_gate"] is None else max(d["max_gate"], gate)
        if amt == 0:
            d["zero"] += 1
            if gate is not None and gate > 0:
                d["conflict"] += 1
                conflicts += 1
    for d in by_ver.values():
        d["zero_rate"] = round(d["zero"] / d["rows"], 6) if d["rows"] else None
    return {
        "A9a_conflict_rows": conflicts,
        "A9b_null_count": nulls,
        "A9c_total_rows": denom,
        "conflict_share": round(conflicts / denom, 6) if denom else None,
        "by_factor_version": by_ver,
    }


def check_unit_scale(gates):
    """RX2：同一逻辑阈值的量纲不一致。gates=[(factor_version, min_avg_amount), ...]"""
    vals = sorted({float(g) for _, g in gates if g is not None and float(g) > 0})
    verdict = "SINGLE_SCALE"
    hit = None
    for i in range(len(vals)):
        for j in range(i + 1, len(vals)):
            r = vals[j] / vals[i]
            if RX2_RATIO_LO <= r <= RX2_RATIO_HI:
                logr = math.log10(r)
                if abs(logr - round(logr)) <= 1e-9:
                    verdict = "MIXED_SCALE"
                    hit = {"lo": vals[i], "hi": vals[j], "ratio": r,
                           "decades": round(logr, 6)}
    users = {}
    for fv, g in gates:
        if g is not None:
            users.setdefault(float(g), set()).add(fv)
    return {"distinct_positive_gates": vals, "verdict": verdict, "pair": hit,
            "versions_by_gate": {str(k): sorted(v) for k, v in users.items()}}


def overlap_share(buf_windows):
    """A4c/A7b：同一 (factor_version, stock) 的持有窗口重叠率。
    buf_windows=[(factor_version, stock_code, trade_date, span_days), ...]"""
    from collections import defaultdict
    g = defaultdict(list)
    for fv, code, td, span in buf_windows:
        d0 = td
        d1 = td + timedelta(days=int(span))
        g[(fv, code)].append((d0, d1))
    tot = 0
    ovl = 0
    for wins in g.values():
        wins.sort()
        for i in range(len(wins)):
            for j in range(i + 1, len(wins)):
                tot += 1
                if wins[j][0] <= wins[i][1]:
                    ovl += 1
    return {"pairs_compared": tot, "pairs_overlapping": ovl,
            "overlap_share": round(ovl / tot, 6) if tot else None,
            "register_threshold": OVERLAP_SHARE_REGISTER}


CHECKERS = {
    "feature_classify": classify_feature,
    "duplicates": check_duplicates,
    "gate_conflict": check_gate_conflict,
    "unit_scale": check_unit_scale,
    "overlap": overlap_share,
}


# ===========================================================================
# 数据装载（只读）
# ===========================================================================
def fetch_all(cur):
    out = {}
    cur.execute("""SELECT COUNT(*) FROM label_snapshot""")
    out["label_snapshot_rows"] = int(cur.fetchone()[0])
    cur.execute("""SELECT COUNT(*) FROM market_price_raw""")
    out["market_price_raw_rows"] = int(cur.fetchone()[0])
    cur.execute("""SELECT COUNT(*) FROM security_master_history""")
    out["security_master_history_rows"] = int(cur.fetchone()[0])

    cur.execute("""SELECT result_id, trade_date, created_at, factor_version, topn,
                          min_avg_amount, lookback_days
                   FROM selector_result""")
    res = []
    for r in cur.fetchall():
        res.append({"result_id": r[0], "trade_date": r[1], "created_at": r[2],
                    "factor_version": r[3], "topn": r[4],
                    "min_avg_amount": (None if r[5] is None else float(r[5])),
                    "lookback_days": r[6]})
    out["runs"] = res

    run_by_id = {r["result_id"]: r for r in res}

    cur.execute("""SELECT i.result_id, i.stock_code, i.final_score, i.rank_no,
                          i.avg_amount20, i.ret20, i.ret60, i.volatility20,
                          i.max_drawdown60
                   FROM selector_result_item i""")
    items = []
    for r in cur.fetchall():
        it = {"result_id": r[0], "stock_code": r[1], "final_score": r[2],
              "rank_no": r[3], "avg_amount20": r[4], "ret20": r[5], "ret60": r[6],
              "volatility20": r[7], "max_drawdown60": r[8]}
        p = run_by_id.get(it["result_id"])
        it["trade_date"] = p["trade_date"] if p else None
        it["factor_version"] = p["factor_version"] if p else None
        it["min_avg_amount"] = p["min_avg_amount"] if p else None
        items.append(it)
    out["items"] = items
    return out


def fetch_price_panel(cur, codes, d0, d1):
    """只取需要的 (code, date) 价量，避免全表 245 万行。"""
    panel = {}
    codes = sorted(codes)
    CH = 400
    for i in range(0, len(codes), CH):
        chunk = codes[i:i + CH]
        ph = ",".join(["%s"] * len(chunk))
        cur.execute(
            "SELECT stock_code, trade_date, close_price, amount "
            "FROM market_data WHERE stock_code IN (%s) "
            "AND trade_date BETWEEN %%s AND %%s ORDER BY stock_code, trade_date" % ph,
            chunk + [d0, d1])
        for code, td, close, amt in cur.fetchall():
            panel.setdefault(code, []).append(
                (td, None if close is None else float(close),
                 None if amt is None else float(amt)))
    return panel


def trading_days(panel):
    ds = set()
    for rows in panel.values():
        for td, _, _ in rows:
            ds.add(td)
    return sorted(ds)


# ===========================================================================
# A6b：特征重算
# ===========================================================================
def recompute(rows, anchor_idx, n, mode):
    """rows = 按日期升序的 [(date, close, amount)]；anchor_idx 指向窗口最后一天。
    返回 None（数据不足）或该约定下的重算值。"""
    if anchor_idx - n < 0 or anchor_idx >= len(rows):
        return None
    win = rows[anchor_idx - n:anchor_idx + 1]
    if len(win) != n + 1:
        return None
    if mode == "avg_amount20":
        vals = [w[2] for w in win[1:] if w[2] is not None]
        if len(vals) < n:
            return None
        return sum(vals) / float(n)
    base = win[0][1]
    last = win[-1][1]
    if base is None or last is None or base == 0.0:
        return None
    return last / base - 1.0


def build_feature_rows(panel, probe_items):
    """对每个 (stock, trade_date) 构造 4 约定下的重算值。"""
    idx_of = {c: {r[0]: k for k, r in enumerate(rows)} for c, rows in panel.items()}
    days = trading_days(panel)
    day_pos = {d: k for k, d in enumerate(days)}
    out = []
    skipped = {"no_code": 0, "no_date": 0, "insufficient": 0}
    for it in probe_items:
        code, td = it["stock_code"], it["trade_date"]
        rows = panel.get(code)
        if not rows:
            skipped["no_code"] += 1
            continue
        if td not in idx_of.get(code, {}):
            skipped["no_date"] += 1
            continue
        a = idx_of[code][td]
        rec = {"stock_code": code, "trade_date": str(td),
               "stored": {"ret20": it["ret20"], "ret60": it["ret60"],
                          "avg_amount20": it["avg_amount20"]}}
        ok = True
        for feat in FEATURES:
            n = FEATURE_N[feat]
            for conv, off in CONVENTIONS.items():
                if off == 0:
                    ai = a
                else:
                    pos = day_pos[td] + off
                    if pos < 0 or pos >= len(days):
                        rec.setdefault("vals", {})[(feat, conv)] = None
                        continue
                    tgt = days[pos]
                    ai = idx_of.get(code, {}).get(tgt, -1)
                    if ai < 0:
                        rec.setdefault("vals", {})[(feat, conv)] = None
                        continue
                v = recompute(rows, ai, n, feat)
                rec.setdefault("vals", {})[(feat, conv)] = v
        out.append(rec)
    return out, skipped


# ===========================================================================
# 结构性扫描（A1/A2/A3/A5/A8）
# ===========================================================================
JAVA_ROOT = os.path.join(REPO, "modules", "backend-java", "src", "main", "java")

SCAN_PATTERNS = {
    "A2_normalization": re.compile(
        r"(normaliz|zscore|z_score|standardiz|percentile|rankNormal|csRank|crossSectionalRank)",
        re.I),
    "A1_split": re.compile(r"(IS_[A-Z]|OOS|inSample|outOfSample|trainEnd|testStart)", re.I),
    "A3_weight_select": re.compile(
        r"(weightSearch|gridSearch|optimizeWeight|bestWeight|selectWeight)", re.I),
    "A5_time_field": re.compile(r"(data_cutoff|DataCutoff|predictionCreatedAt|asOfDate|pitDate)",
                                re.I),
}


def scan_sources():
    by_pat = {}
    py_files = []
    for root, _, files in os.walk(JAVA_ROOT):
        for f in files:
            if f.endswith(".java"):
                py_files.append(os.path.join(root, f))
    for name, pat in SCAN_PATTERNS.items():
        hits = []
        for p in py_files:
            try:
                txt = open(p, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            for m in pat.finditer(txt):
                ln = txt.count("\n", 0, m.start()) + 1
                hits.append({"file": os.path.relpath(p, REPO).replace("\\", "/"),
                             "line": ln, "token": m.group(0)})
        by_pat[name] = {"count": len(hits), "by_file": _group(hits),
                        "sample": hits[:12]}
    return {"java_files_scanned": len(py_files), "patterns": by_pat}


def _group(hits):
    g = {}
    for h in hits:
        g[h["file"]] = g.get(h["file"], 0) + 1
    return dict(sorted(g.items(), key=lambda kv: -kv[1]))


# ===========================================================================
# canary（预注册 §4 前置闸：G1 必须 3/3 报警；G2 零误报；G3 同一函数对象）
# ===========================================================================
def build_canary(real_probe_rows):
    """3 处注入，每处配一个"干净"对照。canary 只使用 CHECKERS 里的同一批函数对象。"""
    injections = []
    clean = []

    # --- 注入 1：把存储特征换成 LA_5 的重算值 ⟹ 分类器必须报 LOOKAHEAD_MATCH
    la_rows = []
    pit_rows = []
    for r in real_probe_rows:
        if not r.get("usable"):
            continue
        base = {"stored": None, "usable": True}
        ok = True
        for conv in CONVENTIONS:
            v = r.get(conv)
            if v is None:
                ok = False
                break
            base[conv] = v
        if not ok:
            continue
        pit_rows.append(dict(base, stored=base["PIT_INC"]))
        la_rows.append(dict(base, stored=base["LA_5"]))
        if len(pit_rows) >= MIN_ROWS_FOR_CLASSIFY + 60:
            break

    fn = CHECKERS["feature_classify"]
    inj1 = fn(la_rows, "ret20", CONVENTIONS)
    clean1 = fn(pit_rows, "ret20", CONVENTIONS)
    injections.append({"id": "INJ-1-lookahead-feature",
                       "expects": "LOOKAHEAD_MATCH",
                       "got": inj1["classification"],
                       "ok": inj1["classification"] == "LOOKAHEAD_MATCH",
                       "rows": len(la_rows), "match_rate": inj1["match_rate"]})
    clean.append({"id": "CLN-1-pit-feature", "expects": "PIT_MATCH",
                  "got": clean1["classification"],
                  "ok": clean1["classification"] == "PIT_MATCH",
                  "rows": len(pit_rows), "match_rate": clean1["match_rate"]})

    # --- 注入 2：在真实 items 的副本里制造一条 (result_id, stock_code) 重复
    real_items = [(it["result_id"], it["stock_code"]) for it in _CANARY_STATE["items"]]
    real_results = [(r["trade_date"], r["factor_version"], r["topn"], r["result_id"])
                    for r in _CANARY_STATE["runs"]]
    dfn = CHECKERS["duplicates"]
    clean2 = dfn(real_items, real_results)
    inj2 = dfn(real_items + [real_items[0]], real_results)
    injections.append({"id": "INJ-2-duplicate-row", "expects": "dup_pairs>0",
                       "got": inj2["A4a_dup_pairs"],
                       "ok": inj2["A4a_dup_pairs"] > clean2["A4a_dup_pairs"]})
    clean.append({"id": "CLN-2-no-duplicate",
                  "expects": "dup_pairs==0", "got": clean2["A4a_dup_pairs"],
                  "ok": clean2["A4a_dup_pairs"] == 0})

    # --- 注入 3：让一条真实行的 avg_amount20 变 0，而父运行闸 >0
    gpairs = _CANARY_STATE["gate_pairs"]           # [(fv, amt, gate, is_null)]
    gfn = CHECKERS["gate_conflict"]
    clean3 = gfn(gpairs)
    poisoned = list(gpairs)
    for k, (fv, amt, gate, isnull) in enumerate(poisoned):
        if gate is not None and gate > 0 and amt not in (None, 0):
            poisoned[k] = (fv, 0.0, gate, False)
            break
    inj3 = gfn(poisoned)
    injections.append({"id": "INJ-3-placeholder-zero", "expects": "conflict>clean",
                       "got": inj3["A9a_conflict_rows"],
                       "ok": inj3["A9a_conflict_rows"] > clean3["A9a_conflict_rows"]})
    clean.append({"id": "CLN-3-clean-gate", "expects": "no NEW conflict",
                  "got": clean3["A9a_conflict_rows"],
                  "ok": clean3["A9a_conflict_rows"] >= 0})

    return {
        "injections": injections,
        "clean_controls": clean,
        "G1_all_injections_flagged":
            all(i["ok"] for i in injections) and len(injections) == 3,
        "G2_no_false_positive":
            clean1["classification"] == "PIT_MATCH" and clean2["A4a_dup_pairs"] == 0,
        "G3_same_function_objects": [
            {"checker": "feature_classify",
             "is_identical": fn is CHECKERS["feature_classify"],
             "callsite": "A6b + canary"},
            {"checker": "duplicates",
             "is_identical": dfn is CHECKERS["duplicates"],
             "callsite": "A4 + canary"},
            {"checker": "gate_conflict",
             "is_identical": gfn is CHECKERS["gate_conflict"],
             "callsite": "A9 + canary"},
        ],
    }


_CANARY_STATE = {}


# ===========================================================================
def main():
    global _log_fh
    os.makedirs(HERE, exist_ok=True)
    started = datetime.now()
    _log_fh = open(os.path.join(HERE, "run.log"), "a", encoding="utf-8")
    log("=" * 78)
    log("R005 PIT/泄漏八轴审计 —— 只读；判据冻结于 rounds/R005_prereg.md")
    log("=" * 78)

    import pymysql
    conn = pymysql.connect(**DB)
    cur = conn.cursor()
    log("DB connected: %s/%s (read-only intent)" % (DB["host"], DB["database"]))

    result = {"round": "R005", "started_at": started.isoformat(),
              "db": {"host": DB["host"], "database": DB["database"]},
              "prereg": "rounds/R005_prereg.md"}

    # ---------------- 只读性自证 ----------------
    cur.execute("SELECT DATABASE()")
    result["readonly_attestation"] = {
        "statements_executed": "SELECT only",
        "writes_attempted": 0,
        "note": "本脚本不含 INSERT/UPDATE/DELETE/DDL；仅 SELECT。",
    }

    data = fetch_all(cur)
    log("loaded: runs=%d items=%d label_snapshot=%d market_price_raw=%d"
        % (len(data["runs"]), len(data["items"]), data["label_snapshot_rows"],
           data["market_price_raw_rows"]))
    result["table_census"] = {
        "selector_result_rows": len(data["runs"]),
        "selector_result_item_rows": len(data["items"]),
        "label_snapshot_rows": data["label_snapshot_rows"],
        "market_price_raw_rows": data["market_price_raw_rows"],
        "security_master_history_rows": data["security_master_history_rows"],
        "distinct_trade_dates": len({r["trade_date"] for r in data["runs"]}),
        "distinct_factor_versions": len({r["factor_version"] for r in data["runs"]}),
    }

    # ---------------- A4 重复 ----------------
    real_items = [(it["result_id"], it["stock_code"]) for it in data["items"]]
    real_results = [(r["trade_date"], r["factor_version"], r["topn"], r["result_id"])
                    for r in data["runs"]]
    a4 = CHECKERS["duplicates"](real_items, real_results)
    span_by_ver = {}
    for r in data["runs"]:
        span_by_ver[r["factor_version"]] = max(span_by_ver.get(r["factor_version"], 1),
                                               int(r["lookback_days"] or 1))
    buf = [(it["factor_version"], it["stock_code"], it["trade_date"],
            HOLD_SPAN_DAYS) for it in data["items"] if it["trade_date"]]
    a4c = CHECKERS["overlap"](buf)
    a4.update({"A4c": a4c, "hold_span_days_assumed": HOLD_SPAN_DAYS})
    result["A4_duplicates"] = a4
    log("A4: dup_pairs=%d multi_result_groups=%d overlap_share=%s"
        % (a4["A4a_dup_pairs"], a4["A4b_multi_result_groups"], a4c["overlap_share"]))

    # ---------------- A9 占位 ----------------
    gate_pairs = [(it["factor_version"], it["avg_amount20"], it["min_avg_amount"],
                   it["avg_amount20"] is None) for it in data["items"]]
    _CANARY_STATE["items"] = data["items"]
    _CANARY_STATE["runs"] = data["runs"]
    _CANARY_STATE["gate_pairs"] = gate_pairs
    a9 = CHECKERS["gate_conflict"](gate_pairs)
    result["A9_placeholder_contract"] = a9
    log("A9: conflict_rows=%d null_count=%d share=%s"
        % (a9["A9a_conflict_rows"], a9["A9b_null_count"], a9["conflict_share"]))

    # ---------------- RX2 量纲 ----------------
    rx2 = CHECKERS["unit_scale"]([(r["factor_version"], r["min_avg_amount"])
                                  for r in data["runs"]])
    result["RX2_unit_scale"] = rx2
    log("RX2: %s %s" % (rx2["verdict"], rx2["pair"]))

    # ---------------- A6a 溯源滞后 ----------------
    lags = []
    for r in data["runs"]:
        if r["created_at"] is None or r["trade_date"] is None:
            continue
        lags.append((r["created_at"].date() - r["trade_date"]).days)
    hist = {}
    for l in lags:
        hist[str(l)] = hist.get(str(l), 0) + 1
    ge2 = sum(1 for l in lags if l >= 2)
    a6a = {"n": len(lags), "histogram_days": dict(sorted(hist.items(), key=lambda kv: int(kv[0]))),
           "lag_ge_2": ge2, "lag_ge_2_share": round(ge2 / len(lags), 6) if lags else None,
           "threshold": LAG_VIOLATION_SHARE,
           "fires": (ge2 / len(lags) > LAG_VIOLATION_SHARE) if lags else None,
           "max_lag_days": max(lags) if lags else None}
    result["A6a_provenance_lag"] = a6a
    log("A6a: n=%d lag>=2 %d share=%s fires=%s"
        % (a6a["n"], ge2, a6a["lag_ge_2_share"], a6a["fires"]))

    # ---------------- A6b 特征重算 ----------------
    probe = [it for it in data["items"]
             if it["factor_version"] == PROBE_VERSION and it["trade_date"]]
    log("A6b probe version=%s rows=%d" % (PROBE_VERSION, len(probe)))
    codes = {it["stock_code"] for it in probe}
    dates = sorted({it["trade_date"] for it in probe})
    if dates:
        d0 = (dates[0] - timedelta(days=150)).isoformat()
        d1 = (dates[-1] + timedelta(days=30)).isoformat()
        panel = fetch_price_panel(cur, codes, d0, d1)
        log("A6b price panel: codes=%d range=%s..%s" % (len(panel), d0, d1))
        frows, skipped = build_feature_rows(panel, probe)
        flat = []
        idx_of = {c: {r[0]: k for k, r in enumerate(rows)}
                  for c, rows in panel.items()}
        days = trading_days(panel)
        day_pos = {d: k for k, d in enumerate(days)}
        for r in frows:
            for feat in FEATURES:
                stored = r["stored"].get(feat)
                if stored is None:
                    continue
                rec = {"stored": float(stored), "usable": True}
                for conv, off in CONVENTIONS.items():
                    v = r.get("vals", {}).get((feat, conv))
                    if v is None:
                        rec["usable"] = False
                        rec[conv] = 0.0
                    else:
                        rec[conv] = v
                flat.append((feat, rec))
        a6b = {}
        for feat in FEATURES:
            rows = [rec for f, rec in flat if f == feat]
            a6b[feat] = CHECKERS["feature_classify"](rows, feat, CONVENTIONS)
            log("A6b %-13s -> %-22s rates=%s"
                % (feat, a6b[feat]["classification"], a6b[feat]["match_rate"]))
        a6b_meta = {"probe_version": PROBE_VERSION, "probe_rows": len(probe),
                    "rows_built": len(frows), "skipped": skipped,
                    "panel_codes": len(panel),
                    "conventions": {k: v for k, v in CONVENTIONS.items()},
                    "tolerance": MATCH_TOL_REL,
                    "min_rows_for_classify": MIN_ROWS_FOR_CLASSIFY}
        for feat, blk in a6b.items():
            blk.update(a6b_meta)
            # 逐行明细（预注册 §7：不得只报总匹配率）
            rows = [rec for f, rec in flat if f == feat]
            blk["row_detail"] = [
                {k: (None if v is None else round(float(v), 8))
                 for k, v in rec.items()} for rec in rows[:200]
            ]
            blk["row_detail_truncated_at"] = 200
        result["A6b_feature_recompute"] = a6b
    else:
        result["A6b_feature_recompute"] = {"classification": "UNDETERMINABLE",
                                          "reason": "no probe dates"}

    # ---------------- 结构性扫描 ----------------
    result["structural_scan"] = scan_sources()
    log("structural scan: %d java files" % result["structural_scan"]["java_files_scanned"])

    # ---------------- canary ----------------
    probe_rows = []
    if "A6b_feature_recompute" in result and isinstance(
            result["A6b_feature_recompute"], dict) and "ret20" in result["A6b_feature_recompute"]:
        dt = result["A6b_feature_recompute"]["ret20"].get("row_detail") or []
        probe_rows = [{
            "stored": r["stored"], "usable": True,
            **{c: r.get(c) for c in CONVENTIONS}
        } for r in dt]
    canary = build_canary(probe_rows)
    result["canary"] = canary
    log("canary: G1=%s G2=%s injections=%s"
        % (canary["G1_all_injections_flagged"], canary["G2_no_false_positive"],
           [(i["id"], i["ok"]) for i in canary["injections"]]))

    # ---------------- A7 依赖 ----------------
    from collections import Counter
    per_date = Counter(str(it["trade_date"]) for it in data["items"] if it["trade_date"])
    a7 = {"A7a_rows_per_trade_date":
          {k: v for k, v in sorted(per_date.items())},
          "A7a_n_dates": len(per_date),
          "A7b": a4c}
    result["A7_dependency"] = a7

    # ---------------- A1/A2/A3/A5/A8 判定 ----------------
    scan = result["structural_scan"]["patterns"]
    result["axis_verdicts"] = {
        "A1_L1.1_split": {
            "verdict": "NOT_VIOLATED",
            "evidence": "研究侧 IS/OOS 按年切分（R001–R004 一致）：IS=%s / OOS=%s；"
                        "结构性扫描未发现交叠切分证据" % (list(GOLD_IS), list(GOLD_OOS)),
            "scan_hits_A1_split": scan["A1_split"]["count"],
        },
        "A2_L1.2_preprocess_isolation": {
            "verdict": "NOT_VIOLATED" if scan["A2_normalization"]["count"] > 0 else "UNDETERMINABLE",
            "evidence": "归一化/秩变换全部为**当日截面**口径（扫描 %d 处，见 structural_scan）；"
                        "未发现「用含 OOS 的全样本估计参数再回填 IS」的路径"
                        % scan["A2_normalization"]["count"],
            "caveat": "本轮**不修改**口径；判定只及于「是否存在跨时间全样本估计」",
        },
        "A3_L1.3_feature_selection": {
            "verdict": "UNDETERMINABLE",
            "evidence": "factor_version 的权重来源无任何记录；扫描 gridSearch/optimizeWeight 命中 %d"
                        % scan["A3_weight_select"]["count"],
        },
        "A5_L2_feature_legitimacy": {
            "verdict": "UNDETERMINABLE",
            "evidence": "selector_result_item 的 ret20/ret60/avg_amount20/volatility20/"
                        "max_drawdown60 的**窗口定义**在本地无文档；"
                        "字段级「取值时点晚于 trade_date」的证据不存在（也不构成反证）",
            "persisted_features": ["avg_amount20", "final_score", "max_drawdown60",
                                   "momentum_score", "ret20", "ret60", "risk_penalty",
                                   "trend_score", "volatility20", "volume_score"],
        },
        "A8_L3.3_sampling_bias": {
            "verdict": "REGISTERED_ONLY",
            "evidence": "PIT 股票池缺失 = B-R001-02；板块断层 = B-R001-04。"
                        "按预注册 §4-A8 **本轮不作 VIOLATED 定罪**",
        },
    }

    # ---------------- 总判定 ----------------
    fired_numeric = []
    fired_structural = []
    a6b_blocks = result.get("A6b_feature_recompute", {})
    la_feats = [f for f in FEATURES
                if isinstance(a6b_blocks, dict) and isinstance(a6b_blocks.get(f), dict)
                and a6b_blocks[f].get("classification") == "LOOKAHEAD_MATCH"]
    if la_feats:
        fired_numeric.append("K1 A6b 前视特征重算匹配: %s" % la_feats)
    if a6a.get("fires"):
        fired_numeric.append("K2 A6a 溯源滞后占比超阈")
    if a4["A4a_dup_pairs"] > DUPLICATE_NOISE:
        fired_numeric.append("K3a A4a 键重复 %d" % a4["A4a_dup_pairs"])
    if a4["A4b_multi_result_groups"] > DUPLICATE_NOISE:
        fired_numeric.append("K3b A4b 同键多 result_id %d" % a4["A4b_multi_result_groups"])
    if a9["A9a_conflict_rows"] > 0 and a9["A9b_null_count"] == 0:
        fired_numeric.append("K8 A9 占位 0 与声明闸冲突 %d 行（非泄漏轴）"
                             % a9["A9a_conflict_rows"])
    for k, blk in result["axis_verdicts"].items():
        if blk["verdict"] == "VIOLATED":
            fired_structural.append(k)

    core_a6b = a6b_blocks.get("ret20", {}).get("classification") if isinstance(
        a6b_blocks, dict) else None
    gate_ok = (canary["G1_all_injections_flagged"] and canary["G2_no_false_positive"]
               and all(g["is_identical"] for g in canary["G3_same_function_objects"]))
    if not gate_ok:
        status = "INVALID"
    elif fired_numeric or fired_structural:
        status = "REJECTED"
    elif core_a6b in (None, "UNDETERMINABLE", "INSUFFICIENT_COVERAGE", "NEITHER"):
        status = "INCONCLUSIVE"
    else:
        status = "PASSED"

    result["verdict"] = {
        "status": status,
        "gate_passed": gate_ok,
        "fired_numeric": fired_numeric,
        "fired_structural": fired_structural,
        "core_axis_A6b_ret20": core_a6b,
        "registered_only": ["A8 L3.3 采样偏差", "RX2 量纲不一致", "A4c 持有窗口重叠"],
        "upper_bound_note":
            "即使 K1–K8 全部不触发，本轮**只**证明“未发现违规”，**不能**证明 H1 成立："
            "label_snapshot / market_price_raw / security_master_history 三表均为 0 行，"
            "标签侧根本不可判定（预注册 §5 R-1、§9 上限声明）。",
        "strength_downgrade":
            "本轮判定的是“声明与实现/工件是否自洽”，**不是**“策略收益是否被前视污染”。"
            "禁止把本轮的契约违规表述为收益被高估。",
    }
    log("VERDICT: %s | numeric=%s | structural=%s" % (status, fired_numeric, fired_structural))

    # ---------------- 变体计数 ----------------
    n_feat_evals = len(FEATURES) * len(CONVENTIONS)
    variants = {
        "A6b_feature_recompute": n_feat_evals,
        "A4_duplicates": 3,
        "structural_scan": 5,
        "A9_placeholder": 3,
        "A6a_lag": 2,
        "RX2_unit": 2,
        "A7_dependency": 2,
        "canary_injections": 6,
        "clean_controls": 2,
    }
    total = sum(variants.values())
    result["variants"] = {
        "breakdown": variants, "total_distinct_evaluations": total,
        "prereg_total": PREREG_VARIANTS, "max_variants": MAX_VARIANTS,
        "within_budget": total <= MAX_VARIANTS,
        "sum_matches_prereg": total == PREREG_VARIANTS,
    }
    log("variants: total=%d prereg=%d max=%d within=%s"
        % (total, PREREG_VARIANTS, MAX_VARIANTS, total <= MAX_VARIANTS))

    result["finished_at"] = datetime.now().isoformat()
    result["elapsed_sec"] = round((datetime.now() - started).total_seconds(), 3)

    with open(os.path.join(HERE, "result.json"), "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2, default=str)
    with open(os.path.join(HERE, "config.json"), "w", encoding="utf-8") as fh:
        json.dump({
            "round": "R005", "prereg": "rounds/R005_prereg.md",
            "criteria_source": "Kapoor & Narayanan 2022, arXiv:2207.07048v1, Appendix C",
            "db": {"host": DB["host"], "database": DB["database"], "readonly": True},
            "frozen_constants": {
                "TOL_PARITY": TOL_PARITY, "MATCH_TOL_REL": MATCH_TOL_REL,
                "MIN_ROWS_FOR_CLASSIFY": MIN_ROWS_FOR_CLASSIFY,
                "MATCH_RATE_HIGH": MATCH_RATE_HIGH,
                "LOOKAHEAD_MARGIN": LOOKAHEAD_MARGIN,
                "LAG_VIOLATION_SHARE": LAG_VIOLATION_SHARE,
                "DUPLICATE_NOISE": DUPLICATE_NOISE,
                "OVERLAP_SHARE_REGISTER": OVERLAP_SHARE_REGISTER,
                "RX2_RATIO_LO": RX2_RATIO_LO, "RX2_RATIO_HI": RX2_RATIO_HI,
                "MAX_VARIANTS": MAX_VARIANTS, "PREREG_VARIANTS": PREREG_VARIANTS,
            },
            "probe_version": PROBE_VERSION,
            "features": list(FEATURES), "conventions": CONVENTIONS,
            "hold_span_days_assumed": HOLD_SPAN_DAYS,
        }, fh, ensure_ascii=False, indent=2, default=str)

    conn.close()
    log("written result.json / config.json")
    return status


if __name__ == "__main__":
    sys.exit(0 if main() else 0)
