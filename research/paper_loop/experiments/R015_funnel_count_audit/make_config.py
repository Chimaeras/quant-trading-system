#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R015 装置配置生成器（**在预注册冻结之后运行**）。

产出：
  * panel_R015_main.txt        —— FULL/UNIFIED/SNAPSHOT 路径 + 触发域场景
  * panel_R015_random.txt      —— 300 组受控三重（V03 穷举 / V04 反向锚点 / 可分性矩阵）
  * panel_R015_b{8,10,12}.txt  —— ±20% 敏感性网格（3 规模 × 3 边界面板）
  * config.json                —— 冻结哈希、变体表、词表、禁数扫描串、容差、K 表

用法：python make_config.py
"""
import hashlib
import json
import os
import random
import sys
from datetime import date, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
ROUNDS = os.path.join(REPO, "research", "paper_loop", "rounds")

FROZEN = [
    ("F01", "modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java"),
    ("F02", "modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java"),
    ("F03", "modules/backend-java/src/main/java/com/quant/service/ScoringBacktestService.java"),
    ("F04", "modules/backend-java/src/main/java/com/quant/dto/ScoringBacktestResponse.java"),
    ("F05", "modules/backend-java/src/main/java/com/quant/dto/ScoringBacktestRequest.java"),
    ("F06", "modules/backend-java/src/main/java/com/quant/controller/FactorResearchController.java"),
    ("F07", "modules/backend-java/src/test/java/com/quant/service/backtest/FactorResearchEngineTest.java"),
    ("F08", "modules/web/src/api/factor.ts"),
    ("F09", "modules/web/src/api/backtest.ts"),
    ("F10", "modules/web/src/views/factor/FactorResearch.vue"),
    ("F11", "modules/web/src/views/backtest/ScoringBacktest.vue"),
    ("F12", "modules/backend-java/src/test/java/com/quant/service/FactorResearchServiceTest.java"),
    ("F13", "modules/backend-java/src/main/java/com/quant/research/ResearchConvention.java"),
]

# 期望哈希（冻结时写入 R015_prereg.md §3；生成时逐位比对，不一致即硬失败）
EXPECT_SHA = {
    "F01": "efbf0678aabb2780b95c893e715ece9c",
    "F02": "b7c93af0e03c0d8006c4d228e0047877",
    "F03": "a0e6cf738bb60d39145f90b43f975349",
    "F04": "40a7982b60c8d7cfd1391e5405e3b1bd",
    "F05": "01b85daef92b2cc5309fabb8e926f2d4",
    "F06": "79bb33f56b857532286d0863345816b3",
    "F07": "42aa831eea4b45bdcc220ca905ca4faa",
    "F08": "3f8fe7e80c55cfb219736594811a6337",
    "F09": "c3e4bfcd47c302c26cfbb541c79ffb4b",
    "F10": "c59c81fc3933b5ff58607695cb2f8b7a",
    "F11": "05427ebc02bf392ae935713bced71102",
    "F12": "33dca6899b16d871da11d5455ed5a424",
    "F13": "08e4823811aadc759fc531d8404fc5b8",
}

VOCAB = [
    "KEY_VALUE_UNIQUE", "KEY_VALUE_NOT_UNIQUE", "ALIAS_DECLARED", "ALIAS_UNDECLARED",
    "SAME_NAME_SAME_QUANTITY", "SAME_NAME_DIFF_QUANTITY",
    "FALLBACK_MARKER_EXACT", "FALLBACK_UNNAMED", "FALLBACK_UNDISCLOSED",
    "SCOPE_LABEL_FAITHFUL", "SCOPE_LABEL_UNFAITHFUL",
    "DEGENERATE_KEYS_CONSISTENT", "DEGENERATE_KEYS_INCONSISTENT",
    "INSTRUMENT_GREEN", "INSTRUMENT_RED", "NOT_REACHED_STATIC_ONLY",
]

# 判定单元（A1 轴）：6 个计数键，C(6,2)=15 个键对（prereg §5 / P-01）
JUDGED_KEYS = ["universe_count", "cross_section_days", "feature_valid_count",
               "valid_observations", "selector_item_count", "total_observations"]
MARKER_KEY = "universe_days_with_snapshot"

# BEGIN_FORBIDDEN_PATTERNS  —— 自指哨兵：仪器自扫描跳过本区块（R014 教训）
FORBIDDEN = ["24.09", "6.71", "72.15", "0.0025", "0.006", "Brandt", "1980", "2002",
             "180 bp", "100 bp", "60 bp", "35 bp", "bp/", "1/\u221aK", "theta",
             "\u591a\u7a7a\u5bf9\u51b2\u7ec4\u5408", "\u8054\u5408\u663e\u8457", "\u5b64\u7acb\u663e\u8457"]
# END_FORBIDDEN_PATTERNS

CAL_START = date(2026, 1, 5)
CAL_N = 30


def cal_dates(n=CAL_N):
    return [CAL_START + timedelta(days=i) for i in range(n)]


def main_panel():
    cal = cal_dates()
    L = ["# R015 main panel (frozen by make_config.py)",
         "CAL " + " ".join(d.isoformat() for d in cal)]
    # FULL 路径的合成 selector_result：6 键 × 每键 12 标的 / 10 有效
    for k in range(6):
        L.append("KEY %d NUM:%d %d %d" % (k, 1200, 12, 10))
    six = " ".join(["NUM:1200"] * 6)
    six600 = " ".join(["NUM:600"] * 6)
    L += [
        "DIRECT S1 M1 120 80 " + six,
        "DIRECT S2 M1 120 80 " + six600,
        "DIRECT S3 M2 120 80 " + " ".join(["NULLKEY"] * 6),
        "DIRECT S4 M2 120 80 " + " ".join(["NONE"] * 6),
        "DIRECT S5 MX 120 80 ZERO NEG STR NONE NULLKEY NUM:5",
        "DIRECT S6 M2 0 0",
        "DIRECT S7 MX 0 0 NUM:1200 NUM:1200 NUM:1200",
        "RUN full+unified+snapshot",
    ]
    return "panel_R015_main", "\n".join(L) + "\n"


def random_panel(n=300, seed=20260915):
    rnd = random.Random(seed)
    L = ["# R015 random triple panel (seed=%d, n=%d)" % (seed, n)]
    specs = ["NONE", "NULLKEY", "ZERO", "NEG", "STR"]
    for i in range(n):
        nkeys = rnd.randint(0, 8)
        total = rnd.choice([0, 1, 7, 23, 60, 120, 300, 600, 1200, 5000])
        valid = 0 if total == 0 else rnd.randint(0, total)
        crosses = []
        for _ in range(nkeys):
            r = rnd.random()
            if r < 0.45:
                crosses.append("NUM:%d" % rnd.choice([1, 5, 37, 120, 300, 600, 900, 1200, 2400]))
            elif r < 0.62:
                # 刻意制造 u == total 的反例（供 V12 单键依赖性检验）
                crosses.append("NUM:%d" % total)
            else:
                crosses.append(rnd.choice(specs))
        # 每 20 个场景插入一个「u == total」的确定性反例
        if i % 20 == 0 and total > 0:
            crosses = ["NUM:%d" % total]
        L.append(("DIRECT R%03d AUTO %d %d %s"
                  % (i, total, valid, " ".join(crosses))).rstrip())
    L.append("RUN none")
    return "panel_R015_random", "\n".join(L) + "\n"


def boundary_panels():
    out = []
    for items in (8, 10, 12):
        cal = cal_dates()
        L = ["# R015 sensitivity panel items_per_day=%d" % items,
             "CAL " + " ".join(d.isoformat() for d in cal)]
        for k in range(6):
            L.append("KEY %d NUM:%d %d %d" % (k, 1200, items, max(items - 2, 0)))
        for scale, tag in ((0.8, "s80"), (1.0, "s100"), (1.2, "s120")):
            total = int(round(120 * scale))
            valid = int(round(80 * scale))
            cross = int(round(1200 * scale))
            L.append("DIRECT B%d_%s M1 %d %d %s" % (items, tag, total, valid,
                                                    " ".join(["NUM:%d" % cross] * 6)))
            L.append("DIRECT B%d_%s M2 %d %d %s" % (items, tag, total, valid,
                                                    " ".join(["NULLKEY"] * 6)))
        L.append("RUN full")
        out.append(("panel_R015_b%d" % items, "\n".join(L) + "\n"))
    return out


def sha256_32(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()[:32], os.path.getsize(path)


def main():
    panels = [main_panel(), random_panel()] + boundary_panels()
    for pid, text in panels:
        with open(os.path.join(HERE, pid + ".txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("wrote %s.txt (%d lines)" % (pid, text.count("\n")))

    frozen = {}
    bad = []
    for tag, rel in FROZEN:
        p = os.path.join(REPO, rel.replace("/", os.sep))
        if not os.path.exists(p):
            bad.append("%s MISSING" % tag)
            continue
        h, size = sha256_32(p)
        frozen[tag] = {"path": rel, "bytes": size, "sha256_32": h}
        if EXPECT_SHA.get(tag) and EXPECT_SHA[tag] != h:
            bad.append("%s sha mismatch: %s != %s" % (tag, h, EXPECT_SHA[tag]))
    prereg = os.path.join(ROUNDS, "R015_prereg.md")
    review = os.path.join(ROUNDS, "R015_review.md")
    ph, psize = sha256_32(prereg)
    rh, rsize = sha256_32(review)

    cfg = {
        "round": "R015",
        "subject": "factor_return funnel count family",
        "prereg": {"path": "research/paper_loop/rounds/R015_prereg.md",
                   "bytes": psize, "sha256_32": ph},
        "review": {"path": "research/paper_loop/rounds/R015_review.md",
                   "bytes": rsize, "sha256_32": rh},
        "frozen_files": frozen,
        "frozen_count": len(frozen),
        "judged_keys": JUDGED_KEYS,
        "marker_key": MARKER_KEY,
        "vocab": VOCAB,
        "forbidden_scan": FORBIDDEN,
        "variants_total": 20,
        "canary_pairs": 20,
        "axes": {
            "A1": {"name": "key_value_uniqueness", "variants": ["V01", "V02", "V03", "V04", "V05"], "count": 5},
            "A2": {"name": "same_name_same_quantity", "variants": ["V06", "V07", "V08"], "count": 3},
            "A3": {"name": "fallback_discernibility", "variants": ["V09", "V10", "V11", "V12", "V13", "V14"], "count": 6},
            "A4": {"name": "scope_label_fidelity", "variants": ["V15", "V16", "V17", "V18"], "count": 4},
            "A5": {"name": "instrument", "variants": ["V19", "V20"], "count": 2},
        },
        "panels": [pid for pid, _ in panels],
        "recon_tol_cnt": 0,
        "recon_tol_fp": 1e-9,
        "k_table": {
            "K1": "(i) key_value_uniqueness falsified",
            "K2": "(iii) fallback_discernibility falsified (FALLBACK_UNDISCLOSED only)",
            "K3": "(ii) same_name_same_quantity falsified, outside pre-registered downgrades",
            "K4": "out-of-scope: load-bearing word with reachability < REACHABLE_PUBLIC_API",
            "K5": "count gate",
            "K6": "instrument gate",
            "K7": "freeze gate",
        },
        "reachability_frozen": {
            "V01": "REACHABLE_PUBLIC_API", "V02": "REACHABLE_PUBLIC_API", "V03": "REACHABLE_PUBLIC_API",
            "V04": "REACHABLE_PUBLIC_API", "V05": "REACHABLE_PUBLIC_API", "V06": "REACHABLE_PUBLIC_API",
            "V07": "SYNTHETIC_ONLY", "V08": "SYNTHETIC_ONLY", "V09": "REACHABLE_PUBLIC_API",
            "V10": "REACHABLE_PUBLIC_API", "V11": "REACHABLE_PUBLIC_API", "V12": "REACHABLE_PUBLIC_API",
            "V13": "SYNTHETIC_ONLY", "V14": "SYNTHETIC_ONLY", "V15": "REACHABLE_PUBLIC_API",
            "V16": "REACHABLE_PUBLIC_API", "V17": "REACHABLE_PUBLIC_API", "V18": "REACHABLE_PUBLIC_API",
            "V19": "REACHABLE_PUBLIC_API", "V20": "REACHABLE_PUBLIC_API",
        },
    }
    with open(os.path.join(HERE, "config.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2, sort_keys=False)
        f.write("\n")
    print("wrote config.json  prereg=%s(%dB) review=%s(%dB)" % (ph, psize, rh, rsize))
    if bad:
        print("FROZEN HASH PROBLEMS:")
        for b in bad:
            print("  " + b)
        return 1
    print("frozen files ok: %d/%d verified" % (len(frozen), len(FROZEN)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
