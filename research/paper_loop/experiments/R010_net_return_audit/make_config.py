#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R010 config.json 生成器（**先于** run_net_return_audit.py 运行；本身不含任何判定逻辑）。

职责有二：
  1. 用 `decimal`（prec=60）**独立重算** R010_prereg.md §3.2 / §3.2b / §3.4 的全部冻结值，
     并逐条与预注册文本里的**字面串**做数值比较（`Decimal` 比较，标度无关）。
     任一条不成立 ⟹ 生成器**拒绝写出 config.json**（`sys.exit(2)`），
     因为那意味着预注册冻结值与可复算实现不一致。
  2. 把预注册的 19 断言 / 11 轴 / K1–K10 / 阈值 / 网格 / 19 对金丝雀 / 源码位置
     结构化为 config.json，并做**冻结自检**（行和 = 19、轴计数、变体映射完备）。

本生成器**不**发明任何阈值：所有阈值与冻结值逐字取自 R010_prereg.md。
"""
import json
import os
import sys
from decimal import Decimal, getcontext

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")

getcontext().prec = 60


def D(s):
    return Decimal(s)


# ---------------------------------------------------------------------------
# 1) 冻结输入（逐字取自 R010_prereg.md §3.2 / §3.4）
# ---------------------------------------------------------------------------
P = D("10.000000")
E = D("11.000000")
C0 = D("0.0003")
T0 = D("0.0005")
S0 = D("0.0002")

FROZEN_LITERALS = {
    "doc_entry": "10.0050000000",
    "doc_exit": "10.9923000000",
    "net_doc": "0.09868065967016491754122938530734632683658170914542728635682",
    "impl_entry": "10.00500060000000",
    "impl_exit": "10.98900176000000",
    "net_impl": "0.09835093463162810804828937241642944029408653908526502237291",
    "delta_net": "-0.00032972503853680949294001289091688654249517006016226398391",
    "delta_bp": "-3.29725038536809492940012890916886542495170060162263983910",
    "rel": "-0.00334133395174797830129122756368221397514856320462215249571519",
    "M": "0.999699890012958090756541867582432843912769468041090267627428",
    "f_doc": "0.9993",
    "f_impl": "0.99900016",
    "f_diff": "-0.00029984",
    "g_doc": "1.0005",
    "g_impl": "1.00050006",
    "g_diff": "0.00000006",
    "A_bp": "-3.00050035024517162013409386570599419593715600920644451116000",
    "B_bp": "-0.000599700113961012656011235021814991195502776881392690770000",
    "A_plus_B_bp": "-3.00110005035913263279010510072780918713265878608783720193000",
    "one_plus_A_one_plus_B_bp":
        "-3.00109987041909243458132417567156087230531958909732372571000",
    "M_minus_1_bp": "-3.0010998704190924345813241756715608723053195890973237",
    "symbol_lhs": "0.00000010010000000000000000000000000000000000000000",
    "symbol_rhs": "0.00030002998200000000000000000000000000000000000000",
    "symbol_margin": "2997.30251748251748251748",
}

# §3.4 的 9 格（顺序冻结：c/OAT、t/OAT、s/OAT，各 ×{0.8,1.0,1.2}）
FROZEN_OAT = [
    ("c*0.8", "0.00024", "0.0005", "0.0002", "-2.637738666719915554139634624891", "2397.985657"),
    ("c*1.0", "0.0003", "0.0005", "0.0002", "-3.297250385368094929400128909169", "2997.302517"),
    ("c*1.2", "0.00036", "0.0005", "0.0002", "-3.956682975291185142795337575076", "3596.547607"),
    ("t*0.8", "0.0003", "0.00040", "0.0002", "-3.297470341343926146995581065894", "3746.628147"),
    ("t*1.0", "0.0003", "0.00050", "0.0002", "-3.297250385368094929400128909169", "2997.302517"),
    ("t*1.2", "0.0003", "0.00060", "0.0002", "-3.297030429392263711804676752444", "2497.752098"),
    ("s*0.8", "0.0003", "0.0005", "0.00016", "-3.297602353544357151339391865876", "3747.077509"),
    ("s*1.0", "0.0003", "0.0005", "0.00020", "-3.297250385368094929400128909169", "2997.302517"),
    ("s*1.2", "0.0003", "0.0005", "0.00024", "-3.296898424261347272784243450756", "2497.452571"),
]


def f_doc(c, t, s):
    return D(1) - t - s


def g_doc(c, t, s):
    return D(1) + c + s


def f_impl(c, t, s):
    return (D(1) - s) * (D(1) - c - t)


def g_impl(c, t, s):
    return (D(1) + s) * (D(1) + c)


def net_doc(c, t, s):
    return E * f_doc(c, t, s) / (P * g_doc(c, t, s)) - D(1)


def net_impl(c, t, s):
    return E * f_impl(c, t, s) / (P * g_impl(c, t, s)) - D(1)


def derive():
    """按 prereg 的**表达式顺序**复算全部冻结值（顺序本身是冻结的一部分）。"""
    fD, fI = f_doc(C0, T0, S0), f_impl(C0, T0, S0)
    gD, gI = g_doc(C0, T0, S0), g_impl(C0, T0, S0)
    nD, nI = net_doc(C0, T0, S0), net_impl(C0, T0, S0)
    dNet = nI - nD
    A = fI / fD - D(1)
    B = gD / gI - D(1)
    M = (D(1) + nI) / (D(1) + nD)
    lhs = S0 * T0 * (D(1) + D(2) * C0 + D(2) * S0)
    rhs = C0 * (D(1) + C0 - S0 - C0 * S0)
    return {
        "doc_entry": P * gD,
        "doc_exit": E * fD,
        "net_doc": nD,
        "impl_entry": P * gI,
        "impl_exit": E * fI,
        "net_impl": nI,
        "delta_net": dNet,
        "delta_bp": dNet * 10000,
        "rel": dNet / nD,
        "M": M,
        "f_doc": fD,
        "f_impl": fI,
        "f_diff": fI - fD,
        "g_doc": gD,
        "g_impl": gI,
        "g_diff": gI - gD,
        "A_bp": A * 10000,
        "B_bp": B * 10000,
        "A_plus_B_bp": (A + B) * 10000,
        "one_plus_A_one_plus_B_bp": ((D(1) + A) * (D(1) + B) - D(1)) * 10000,
        "M_minus_1_bp": (M - D(1)) * 10000,
        "symbol_lhs": lhs,
        "symbol_rhs": rhs,
        "symbol_margin": rhs / lhs,
    }


def at_literal_resolution(computed, lit):
    """
    冻结表把值**四舍五入**到某个十进制位（§3.2 多数行为全精度、§3.2 的 delta_bp 去掉尾零、
    §3.2b 全精度、§3.3 的余量取 20 位小数、§3.4 的 Δbp 取 30 位有效数字且余量取 6 位小数）。

    因此正确的比较是「差 ≤ 该字面串末位所代表量的 1/2」，而不是逐字符相等：
    字面串给到哪一位，就只对到那一位负责。返回 (是否成立, 末位分辨率)。
    """
    lit_d = D(lit)
    res = D(1).scaleb(lit_d.as_tuple().exponent)
    return abs(computed - lit_d) <= res / 2, res


def verify_frozen():
    got = derive()
    rows = []
    bad = []
    for k, lit in FROZEN_LITERALS.items():
        g = got[k]
        okv, res = at_literal_resolution(g, lit)
        if k == "M_minus_1_bp":
            # §3.2 表内该行在预注册文本里**带省略号截断**到 55 位有效数字，无末位分辨率可言
            # ⟹ 退化为「前缀匹配」，并与 §3.2b 的 60 位冻结值并列报告。
            ok = str(g).startswith(lit)
            res_txt = "prefix-truncated(55sf)"
        else:
            ok = okv
            res_txt = str(res)
        rows.append({"key": k, "literal": lit, "computed": str(g), "match": bool(ok),
                     "exact_string": str(g) == lit, "resolution": res_txt})
        if not ok:
            bad.append(k)

    oat_rows = []
    distinct = set()
    margins = []
    for tag, c, t, s, bp_lit, mg_lit in FROZEN_OAT:
        cc, tt, ss = D(c), D(t), D(s)
        d = net_impl(cc, tt, ss) - net_doc(cc, tt, ss)
        bp = d * 10000
        lhs = ss * tt * (D(1) + D(2) * cc + D(2) * ss)
        rhs = cc * (D(1) + cc - ss - cc * ss)
        margin = rhs / lhs
        ok_bp, bp_res = at_literal_resolution(bp, bp_lit)
        ok_mg, mg_res = at_literal_resolution(margin, mg_lit)
        oat_rows.append({"tag": tag, "delta_bp": str(bp), "frozen_bp": bp_lit,
                         "bp_match": bool(ok_bp), "bp_resolution": str(bp_res),
                         "margin": str(margin), "frozen_margin": mg_lit,
                         "margin_match": bool(ok_mg), "margin_resolution": str(mg_res)})
        if not (ok_bp and ok_mg):
            bad.append("OAT:" + tag)
        distinct.add(str(bp))
        margins.append((margin, tag))
    return {"baseline": rows, "oat": oat_rows,
            "exact_string_count": sum(1 for r in rows if r["exact_string"]),
            "distinct_delta_bp": len(distinct),
            "negative_count": sum(1 for r in oat_rows if r["delta_bp"].startswith("-")),
            "min_margin": str(min(margins)[0]), "min_margin_tag": min(margins)[1],
            "all_frozen_match": not bad, "mismatches": bad}


# ---------------------------------------------------------------------------
# 2) 轴 / 变体（逐字取自 prereg §4.2 与 §4.1）
# ---------------------------------------------------------------------------
AXES = {
    "A1": ["V01", "V02"],      # 实现 vs 判据来源 S1/S2/S3
    "A2": ["V03", "V04", "V05"],   # 声明口径 vs 实现口径（承重）
    "A3": ["V06", "V07"],      # ResearchConvention:88 的归属声明
    "A4": ["V08", "V09"],      # labelDefinition() 的发射面与公开声明面
    "A5": ["V10", "V11"],      # 持有日窗口原点
    "A6": ["V12", "V13"],      # 费率常量一致性与测试守卫
    "A7": ["V14", "V15"],      # 费率钳位与「持久化值 ≠ 计算值」
    "A8": ["V16"],             # 净收益块键集一致性（跨轮复核，只登记）
    "A9": ["V17"],             # ±20% 费率敏感性（披露轴）
    "A10": ["V18"],            # 研究仪器口径追溯（只登记）
    "A11": ["V19"],            # 确定性与价格网格自检（门轴）
}

FROZEN_AXIS_COUNTS = {"A1": 2, "A2": 3, "A3": 2, "A4": 2, "A5": 2, "A6": 2, "A7": 2,
                      "A8": 1, "A9": 1, "A10": 1, "A11": 1}

VARIANT = {}
for _a, _vs in AXES.items():
    for _v in _vs:
        VARIANT[_v] = _a

# prereg §4.1 逐字冻结的 7 项（混合了轴名与「轴(变体)」写法）
NON_KILLING_AXES_FROZEN = ["A1", "A5a(V10)", "A6a(V12)", "A8(V16)", "A9(V17)", "A10(V18)", "A11(V19)"]
NON_KILLING_VARIANTS = ["V01", "V02", "V10", "V12", "V16", "V17", "V18", "V19"]

# 预注册预期（prereg §8 逐条）
EXPECTED = {
    "V01": "NOT_VIOLATED", "V02": "NOT_VIOLATED",
    "V03": "VIOLATED", "V04": "VIOLATED", "V05": "CONTRACT_MISLABEL",
    "V06": "NOT_VIOLATED", "V07": "VIOLATED", "V08": "VIOLATED",
    "V09": "CONVENTION_DIVERGENCE",
    "V10": "NOT_VIOLATED", "V11": "CONVENTION_DIVERGENCE",
    "V12": "NOT_VIOLATED", "V13": "REGISTERED_ONLY",
    "V14": "NOT_VIOLATED", "V15": "VIOLATED",
    "V16": "REGISTERED_ONLY", "V17": "NOT_VIOLATED", "V18": "REGISTERED_ONLY",
    "V19": "NOT_VIOLATED",
}

# 金丝雀期望（CLN 必须判出预注册期望词；INJ 必须判出**不同**的词）
CANARIES = {
    "V01": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "把 residual_max 覆写为 0（≤TOL）⟹ 一致；覆写为 1e-3（>TOL）⟹ 不一致"},
    "V02": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "把 f_obs_minus_1 覆写为本质值（≠0）⟹ 排除「成本从收益率里减」；覆写为 0 ⟹ 反事实未被排除"},
    "V03": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "intent": "把 f_identity_residual 覆写为 0（恒等式成立）⟹ VIOLATED；覆写为 1e-6 ⟹ 恒等式不成立"},
    "V04": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "intent": "把 g_diff 覆写为 +6e-8（与 f_diff 反号）⟹ 结构不同；覆写为 −6e-8（同号）⟹ 可被单一比例解释"},
    "V05": {"clean": "CONTRACT_MISLABEL", "inj": "NOT_VIOLATED",
            "intent": "abs_delta_bp 覆写为本真值 3.297…（>TH_BP）⟹ 错标；覆写为 0.5（<TH_BP）⟹ 未越阈"},
    "V06": {"clean": "NOT_VIOLATED", "inj": "VIOLATED",
            "intent": "javadoc 归属短语计数 1 ⟹ 声明存在；覆写为 0 ⟹ 声明不存在"},
    "V07": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "intent": "attribution_holds 覆写为 0 ⟹ 归属不成立；覆写为 1 ⟹ 成立"},
    "V08": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "intent": "发射点计数 3 ⟹ 字符串伴随乘性数值；覆写为 2 ⟹ 计数不符"},
    "V09": {"clean": "CONVENTION_DIVERGENCE", "inj": "NOT_VIOLATED",
            "intent": "Vue 提示「双边佣金」计 1（与 API 文档加法式相反）；覆写为 0 ⟹ 无相反声明"},
    "V10": {"clean": "NOT_VIOLATED", "inj": "CONVENTION_DIVERGENCE",
            "intent": "fwd_idx_h3 覆写为 4（= entry+h）⟹ 与结构化文本一致；覆写为 5 ⟹ 差一"},
    "V11": {"clean": "CONVENTION_DIVERGENCE", "inj": "NOT_VIOLATED",
            "intent": "free_text_origin_declared 覆写为 0 ⟹ 自由文本未定原点；覆写为 1 ⟹ 已定"},
    "V12": {"clean": "NOT_VIOLATED", "inj": "CONTRACT_AMBIGUOUS",
            "intent": "constants_all_equal 覆写为 1 ⟹ 四来源一致；覆写为 0 ⟹ 不一致/不可判定"},
    "V13": {"clean": "REGISTERED_ONLY", "inj": "NOT_VIOLATED",
            "intent": "公式算术断言计数 0 ⟹ 测试层零守卫；覆写为 1 ⟹ 有守卫"},
    "V14": {"clean": "NOT_VIOLATED", "inj": "CONVENTION_DIVERGENCE",
            "intent": "grid_ok 覆写为 1 ⟹ 7 格全符合钳位规则；覆写为 0 ⟹ 有格不符"},
    "V15": {"clean": "VIOLATED", "inj": "NOT_VIOLATED",
            "intent": "bounded_006_is_005 覆写为 1 ⟹ 钳位发生；覆写为 0 ⟹ 未发生"},
    "V16": {"clean": "REGISTERED_ONLY", "inj": "NOT_VIOLATED",
            "intent": "empty_net_has_monotonicity 覆写为 0 ⟹ 键集分歧存在；覆写为 1 ⟹ 无分歧"},
    "V17": {"clean": "NOT_VIOLATED", "inj": "CONVENTION_DIVERGENCE",
            "intent": "negative_count 覆写为 9 ⟹ 9/9 全负；覆写为 8 ⟹ 有不成立格"},
    "V18": {"clean": "REGISTERED_ONLY", "inj": "NOT_VIOLATED",
            "intent": "breakeven_additive 覆写为 0 ⟹ R003 用冻结算式；覆写为 1 ⟹ 用加法式"},
    "V19": {"clean": "NOT_VIOLATED", "inj": "INCONCLUSIVE",
            "intent": "grid_selfcheck_ok 覆写为 1 ⟹ 网格自检通过；覆写为 0 ⟹ 不通过"},
}

COUNT_EXPECTATIONS = {
    "C01": {"desc": "生产乘性出场（FactorResearchService 乘性含卖出佣金）",
            "scope": "modules/backend-java/src/main/java", "exts": [".java"],
            "pattern": r"\* \(1 - ResearchConvention\.DEFAULT_COMMISSION_RATE - ResearchConvention\.DEFAULT_SELL_TAX_RATE\);",
            "src": 2, "raw": None,
            "by_file": {"FactorResearchService.java": 2}},
    "C02": {"desc": "LabelFactoryService 乘性出场（raw 出口）",
            "scope": "modules/backend-java/src/main/java", "exts": [".java"],
            "pattern": r"double exitProceeds = exitPriceRaw \* \(1 - commission - sellTax\);",
            "src": 1, "raw": None, "by_file": {"LabelFactoryService.java": 1}},
    "C03": {"desc": "LabelFactoryService 乘性出场（label 出口）",
            "scope": "modules/backend-java/src/main/java", "exts": [".java"],
            "pattern": r"double exitProceeds = exitPrice \* \(1 - commission - sellTax\);",
            "src": 1, "raw": None, "by_file": {"LabelFactoryService.java": 1}},
    "C04": {"desc": "ScoringBacktestService 乘性出场",
            "scope": "modules/backend-java/src/main/java", "exts": [".java"],
            "pattern": r"double exitProceeds = exitPrice \* \(1 - commissionRate - sellTaxRate\);",
            "src": 1, "raw": None, "by_file": {"ScoringBacktestService.java": 1}},
    "C05": {"desc": "LabelFactoryService 乘性入场",
            "scope": "modules/backend-java/src/main/java", "exts": [".java"],
            "pattern": r"double entryCost = entryPrice \* \(1 \+ commission\);",
            "src": 2, "raw": None, "by_file": {"LabelFactoryService.java": 2}},
    "C06": {"desc": "FactorResearchService 乘性入场",
            "scope": "modules/backend-java/src/main/java", "exts": [".java"],
            "pattern": r"double entryCost = entryPrice \* \(1 \+ ResearchConvention\.DEFAULT_COMMISSION_RATE\);",
            "src": 2, "raw": None, "by_file": {"FactorResearchService.java": 2}},
    "C07": {"desc": "labelDefinition() 的发射点（verdict-relevant 计数 ⇒ 已由 V08 逐字冻结为 3）",
            "scope": ".", "exts": [".java"],
            "pattern": r'out\.put\("convention", ResearchConvention\.labelDefinition\(\)\);',
            "src": 3, "raw": None,
            "by_file": {"FactorResearchService.java": 1, "LabelFactoryService.java": 1,
                        "ProbabilityCalibrationService.java": 1}},
    "C08": {"desc": "X01 附加观测：加法式入场写法（× (1+佣金+滑点)）与乘性代码共处",
            "scope": ".", "exts": [".java"], "pattern": r"×\(1\+佣金\+滑点\)",
            "src": 5, "raw": None,
            "by_file": {"LabelSnapshot.java": 1, "FactorResearchService.java": 4}},
    "C09": {"desc": "X01 附加观测：加法式出场写法（× (1-佣金-印花税-滑点)）与乘性代码共处",
            "scope": ".", "exts": [".java"], "pattern": r"×\(1-佣金-印花税-滑点\)",
            "src": 4, "raw": None, "by_file": {"FactorResearchService.java": 4}},
    "C10": {"desc": "docs/研究口径规范.md 的加法式公式",
            "scope": "docs", "exts": [".md"], "pattern": r"1 − sell_tax − slippage",
            "src": 1, "raw": None, "by_file": {"研究口径规范.md": 1}},
    "C11": {"desc": "docs/API文档.md 的加法式口径子句",
            "scope": "docs", "exts": [".md"], "pattern": r"1−印花税−滑点",
            "src": 1, "raw": None, "by_file": {"API文档.md": 1}},
    "C12": {"desc": "前端用户可见提示「双边佣金」",
            "scope": "modules/web/src", "exts": [".vue"], "pattern": r"双边佣金",
            "src": 1, "raw": None, "by_file": {"ScoringBacktest.vue": 1}},
    "C13": {"desc": "R001 研究仪器的加法式出场因子",
            "scope": "research/paper_loop/experiments/R001_reversal", "exts": [".py"],
            "pattern": r"1\.0 - SELL_TAX - SLIPPAGE", "src": 1, "raw": None,
            "by_file": {"run_reversal_ic.py": 1}},
    "C14": {"desc": "R003 研究仪器的冻结算式（加法式缺席）",
            "scope": "research/paper_loop/experiments/R003_cost_breakeven", "exts": [".py"],
            "pattern": r"FROZEN_TOTAL", "src": 5, "raw": None,
            "by_file": {"run_cost_breakeven.py": 5}},
    "C15": {"desc": "测试树中调用 labelDefinition() 的次数（唯一触碰点）",
            "scope": "modules/backend-java/src/test", "exts": [".java"],
            "pattern": r"labelDefinition\(\)", "src": 1, "raw": None,
            "by_file": {"ResearchConventionTest.java": 1}},
    "C16": {"desc": "测试树中的**净收益公式算术字面量**（V13 的零守卫判据；长串优先以免重复计数）",
            "scope": "modules/backend-java/src/test", "exts": [".java"],
            "pattern": r"1\.00050006|0\.99900016|0\.00029984|0\.9993|1\.0005|0\.9991",
            "src": 0, "raw": None, "by_file": {}},
    "C22": {"desc": "X03：ScoringBacktestService 内**乘性入场**站点数（内联 + 助手，算术相同）",
            "scope": "modules/backend-java/src/main/java", "exts": [".java"],
            "pattern": r"double entryCost = entryPrice \* \(1 \+ commissionRate\);",
            "src": 2, "raw": None, "by_file": {"ScoringBacktestService.java": 2}},
    "C23": {"desc": "X03：ScoringBacktestService 内同式滑点入场价站点数",
            "scope": "modules/backend-java/src/main/java", "exts": [".java"],
            "pattern": r"double entryPrice = rawEntryPrice\.doubleValue\(\) \* \(1 \+ slippageRate\);",
            "src": 2, "raw": None, "by_file": {"ScoringBacktestService.java": 2}},
    "C24": {"desc": "四文件乘性净收益合成**站点总数**（入场 6 + 出场 5 = 11；单行赋值口径，"
                    "换行写法不影响。C01+C04+C06 入场 6；C01(2)+C02+C03+C04 出场 5）",
            "scope": "modules/backend-java/src/main/java", "exts": [".java"],
            "pattern": r"double (entryCost|exitProceeds) = ",
            "src": 11, "raw": None,
            "by_file": {"FactorResearchService.java": 4, "LabelFactoryService.java": 4,
                        "ScoringBacktestService.java": 3}},
}

# 冻结**区间**测量（区间取自 SRC_RANGES，与整仓计数口径不同；供 V13/V15/V12/V07 使用）
RANGE_EXPECTATIONS = {
    "sbs_defaults_rate_literals": {"range": "sbs_defaults", "pattern": r"0\.0003|0\.0005|0\.0002",
                                   "expect": 3, "desc": "计算路径兜底字面量 3 个"},
    "sbs_persist_rate_literals": {"range": "sbs_persist_rates", "pattern": r"0\.0003|0\.0005|0\.0002",
                                  "expect": 3, "desc": "持久化路径兜底字面量 3 个"},
    "sbs_persist_has_bounded": {"range": "sbs_persist_rates", "pattern": r"boundedRate",
                                "expect": 0, "desc": "持久化路径**不含** boundedRate（V15②）"},
    "sbs_catch_sets_resultid_null": {"range": "sbs_catch", "pattern": r"setResultId\(null\)",
                                     "expect": 1, "desc": "catch 吞异常后清空 resultId（V15④）"},
    "rc_constants_pinned": {"range": "rctest_constants", "pattern": r"isEqualTo\(0\.000[352]\)",
                            "expect": 3, "desc": "三常量被钉住（V13）"},
    "rctest_labeldef_call": {"range": "rctest_labeldef", "pattern": r"labelDefinition\(\)",
                             "expect": 1, "desc": "唯一触碰 labelDefinition 的测试体一次调用"},
    "rctest_labeldef_has_arith": {"range": "rctest_labeldef", "pattern": r"isEqualTo\(\s*[\"']?y_h|0\.9993|1\.0005|1\.00050006|0\.99900016",
                                  "expect": 0, "desc": "该测试体**不含**公式算术断言（V13 零守卫）"},
    "frs_ascii_additive_comment": {"range": "frs_whole", "pattern": r"×\(1\+c\+s\)|×\(1-s-tax-s\)",
                                   "expect": 2, "desc": "X02：ASCII 加法记法注释（第三种记法）"},
    "sbs_inline_entry_multiplicative": {"range": "sbs_inline_entry", "pattern": r"\* \(1 \+ commissionRate\);",
                                        "expect": 1,
                                        "desc": "X03：内联站点的入场费合成**同样是乘性**（不构成 V01 的反例）"},
    # ---- V07 判定量：被判归到 javadoc 名下的 4 个站点，其卖出因子是否**含佣金**（文本测量，非常量） ----
    "frs_site_1_exit_commission_step": {
        "range": "frs_site_1", "expect": 1,
        "pattern": r"\* \(1 - ResearchConvention\.DEFAULT_COMMISSION_RATE - ResearchConvention\.DEFAULT_SELL_TAX_RATE\);",
        "desc": "站点 1 的卖出因子含佣金（乘性第二步）"},
    "frs_site_2_exit_commission_step": {
        "range": "frs_site_2", "expect": 1,
        "pattern": r"\* \(1 - ResearchConvention\.DEFAULT_COMMISSION_RATE - ResearchConvention\.DEFAULT_SELL_TAX_RATE\);",
        "desc": "站点 2 的卖出因子含佣金（乘性第二步）"},
    "lfs_site_1_exit_commission_step": {
        "range": "lfs_site_1", "expect": 1,
        "pattern": r"\* \(1 - commission - sellTax\);",
        "desc": "站点 1 的卖出因子含佣金（乘性第二步，raw 出口）"},
    "lfs_site_2_exit_commission_step": {
        "range": "lfs_site_2", "expect": 1,
        "pattern": r"\* \(1 - commission - sellTax\);",
        "desc": "站点 2 的卖出因子含佣金（乘性第二步，label 出口）"},
    "rc_declared_exit_factor_excludes_commission": {
        "range": "rc_javadoc_formula", "expect": 1,
        "pattern": r"\(1 - sell_tax - slippage\)",
        "desc": "V07 声明侧：javadoc 公式的卖出因子**逐字不含佣金**"},
}

SRC_LOCATIONS = {
    "rc": "modules/backend-java/src/main/java/com/quant/research/ResearchConvention.java",
    "frs": "modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java",
    "lfs": "modules/backend-java/src/main/java/com/quant/research/LabelFactoryService.java",
    "pcs": "modules/backend-java/src/main/java/com/quant/research/ProbabilityCalibrationService.java",
    "sbs": "modules/backend-java/src/main/java/com/quant/service/ScoringBacktestService.java",
    "sbq": "modules/backend-java/src/main/java/com/quant/dto/ScoringBacktestRequest.java",
    "rctest": "modules/backend-java/src/test/java/com/quant/research/ResearchConventionTest.java",
    "collector": "modules/backend-java/src/main/java/com/quant/research/StrategyBoundSampleCollector.java",
    "doc_conv": "docs/研究口径规范.md",
    "doc_api": "docs/API文档.md",
    "vue": "modules/web/src/views/backtest/ScoringBacktest.vue",
    "r001": "research/paper_loop/experiments/R001_reversal/run_reversal_ic.py",
    "r003": "research/paper_loop/experiments/R003_cost_breakeven/run_cost_breakeven.py",
    "labelsnap": "modules/backend-java/src/main/java/com/quant/entity/LabelSnapshot.java",
}

SRC_RANGES = {
    "rc_exit_freetext": ["rc", 18, 18],
    "rc_javadoc_formula": ["rc", 83, 84],
    "rc_labeldef": ["rc", 95, 98],
    "frs_note": ["frs", 78, 82],
    "frs_emission": ["frs", 218, 222],
    "frs_site_1": ["frs", 360, 366],
    "frs_site_2": ["frs", 727, 733],
    "frs_whole": ["frs", 1, 10 ** 9],
    "lfs_site_1": ["lfs", 337, 341],
    "lfs_site_2": ["lfs", 439, 443],
    "lfs_emission": ["lfs", 705, 709],
    "pcs_emission": ["pcs", 268, 272],
    "sbs_defaults": ["sbs", 80, 82],
    "sbs_bounded": ["sbs", 671, 674],
    "sbs_persist_rates": ["sbs", 507, 509],
    "sbs_catch": ["sbs", 520, 524],
    # 键名在 687，值串（含 "entry day + h"）在 688 ⟹ 区间必须覆盖两行，否则 V10 的文本判据读空
    "sbs_holding_semantics": ["sbs", 687, 689],
    # X03：同文件**第二处**乘性入场站点（内联在主回测循环内，算术与助手相同）
    "sbs_inline_entry": ["sbs", 275, 276],
    "sbs_impl_entry": ["sbs", 969, 971],
    "sbs_impl_exit": ["sbs", 1013, 1015],
    "rctest_constants": ["rctest", 20, 25],
    "rctest_labeldef": ["rctest", 54, 57],
    "doc_conv_formula": ["doc_conv", 38, 39],
    "doc_conv_freetext": ["doc_conv", 43, 44],
    "doc_api_clause": ["doc_api", 377, 377],
    "vue_hint": ["vue", 60, 72],
    "sbq_fields": ["sbq", 28, 35],
    "collector_whole": ["collector", 1, 10 ** 9],
}

# 行号校准锚点（prereg §5.5 第 4 项：冻结行号 vs 实测算得行号，不一致 ⟹ 附录，不改判定）
LINE_ANCHORS = {
    "rc_exit_freetext": ["rc", r"入场后第 h 个持有交易日收盘价"],
    "rc_javadoc_formula": ["rc", r"y_h = 1\[\(exit_price_h × \(1 - sell_tax - slippage\)"],
    "rc_labeldef": ["rc", r"public static String labelDefinition\(\)"],
    "frs_note": ["frs", r"RANK_IC_NET_BASIS_NOTE"],
    "frs_emission": ["frs", r'out\.put\("convention", ResearchConvention\.labelDefinition\(\)\);'],
    "frs_site_1": ["frs", r"COST_APPROX：rawOpen\[t\+1\]×\(1\+c\+s\)"],
    "frs_site_2": ["frs", r"成本后近似净收益：open\[t\+1\]×\(1\+佣金\+滑点\)"],
    "lfs_site_1": ["lfs", r"double exitProceeds = exitPriceRaw \* \(1 - commission - sellTax\);"],
    "lfs_site_2": ["lfs", r"double exitProceeds = exitPrice \* \(1 - commission - sellTax\);"],
    "lfs_emission": ["lfs", r'out\.put\("convention", ResearchConvention\.labelDefinition\(\)\);'],
    "pcs_emission": ["pcs", r'out\.put\("convention", ResearchConvention\.labelDefinition\(\)\);'],
    "sbs_defaults": ["sbs", r"double commissionRate = boundedRate\(req\.getCommissionRate\(\), 0\.0003\);"],
    "sbs_bounded": ["sbs", r"private double boundedRate\(Double value, double defaultValue\)"],
    "sbs_persist_rates": ["sbs", r"run\.setCommissionRate\(BigDecimal\.valueOf\(req\.getCommissionRate\(\)"],
    "sbs_catch": ["sbs", r"response\.setResultId\(null\);"],
    "sbs_holding_semantics": ["sbs", r"holding_period_semantics"],
    # 第三元素 = 起始行提示：`double entryCost = entryPrice * (1 + commissionRate);` 在本文件出现两次
    # （:276 内联主循环 / :970 抽出的助手），无提示时锚点恒命中 :276，校准测到的是**另一个站点**的漂移。
    "sbs_inline_entry": ["sbs", r"double entryCost = entryPrice \* \(1 \+ commissionRate\);", 260],
    "sbs_impl_entry": ["sbs", r"double entryCost = entryPrice \* \(1 \+ commissionRate\);", 950],
    "sbs_impl_exit": ["sbs", r"double exitProceeds = exitPrice \* \(1 - commissionRate - sellTaxRate\);"],
    "rctest_constants": ["rctest", r"DEFAULT_COMMISSION_RATE\)\.isEqualTo\(0\.0003\)"],
    "rctest_labeldef": ["rctest", r"void labelDefinitionMentionsHorizonsAndNetReturn\(\)"],
    "doc_conv_formula": ["doc_conv", r"1 − sell_tax − slippage"],
    "doc_conv_freetext": ["doc_conv", r"`exit_price_h` = 入场后第 h 个持有交易日收盘价"],
    "doc_api_clause": ["doc_api", r"1−印花税−滑点"],
    "vue_hint": ["vue", r"双边佣金"],
    "sbq_fields": ["sbq", r"private Double commissionRate = 0\.0003;"],
}


def main():
    v = verify_frozen()
    print("=" * 78)
    print("冻结值复算（Decimal prec=60，表达式顺序与 prereg 逐式一致）")
    for r in v["baseline"]:
        flag = "OK  " if r["match"] else "FAIL"
        extra = " (串亦逐字符相等)" if r["exact_string"] else " (数值相等@%s)" % r["resolution"]
        print("  %s %-26s %s%s" % (flag, r["key"], r["computed"], extra if r["match"] else ""))
    print("  逐字符相等 %d/%d" % (v["exact_string_count"], len(v["baseline"])))
    print("  OAT 9 格：负号 %d/9，去重 %d 个，最小余量 %s（%s）"
          % (v["negative_count"], v["distinct_delta_bp"], v["min_margin"], v["min_margin_tag"]))
    for r in v["oat"]:
        if not (r["bp_match"] and r["margin_match"]):
            print("  OAT FAIL %s: bp=%s margin=%s" % (r["tag"], r["delta_bp"], r["margin"]))
    print("  all_frozen_match = %s" % v["all_frozen_match"])

    # 结构自检（prereg §4.2）
    axis_total = sum(len(x) for x in AXES.values())
    checks = {
        "axis_total_is_19": axis_total == 19,
        "variant_map_complete": len(VARIANT) == 19 and set(VARIANT) == set(EXPECTED),
        "frozen_axis_counts_match": {k: len(x) for k, x in AXES.items()} == FROZEN_AXIS_COUNTS,
        "canaries_cover_variants": sorted(CANARIES) == sorted(EXPECTED),
        "canary_words_distinct": all(c["clean"] != c["inj"] for c in CANARIES.values()),
        "expected_words_in_vocab": all(w in [
            "NOT_VIOLATED", "VIOLATED", "CONVENTION_DIVERGENCE", "CONTRACT_AMBIGUOUS",
            "CONTRACT_MISLABEL", "REGISTERED_ONLY", "NOT_REACHABLE", "INCONCLUSIVE"]
            for w in EXPECTED.values()),
        "non_killing_axes_literal": NON_KILLING_AXES_FROZEN == [
            "A1", "A5a(V10)", "A6a(V12)", "A8(V16)", "A9(V17)", "A10(V18)", "A11(V19)"],
        "non_killing_variants_8": len(NON_KILLING_VARIANTS) == 8,
    }
    for k, ok in checks.items():
        print("  selfcheck %-28s %s" % (k, "OK" if ok else "FAIL"))

    if not v["all_frozen_match"] or not all(checks.values()):
        print("REFUSING to write config.json：冻结值复算或结构自检未通过")
        sys.exit(2)

    cfg = {
        "round": "R010",
        "baseline_commit": "cd0c4d44",
        "subject": "成本后净收益（COST_APPROX）声明–实现一致性审计",
        "judgment_source": "Gong, Liu, Sun, arXiv:2306.02764（来源级别 C；仅作判据来源）",
        "unit_of_judgment": "variant",
        "verdict_vocab": ["NOT_VIOLATED", "VIOLATED", "CONVENTION_DIVERGENCE",
                          "CONTRACT_AMBIGUOUS", "CONTRACT_MISLABEL", "REGISTERED_ONLY",
                          "NOT_REACHABLE", "INCONCLUSIVE"],
        "axes": AXES,
        "frozen_axis_counts": FROZEN_AXIS_COUNTS,
        "frozen_total": 19,
        "max_variants": 19,
        "variant": VARIANT,
        "expected": EXPECTED,
        "non_killing_axes_frozen": NON_KILLING_AXES_FROZEN,
        "non_killing_variants": NON_KILLING_VARIANTS,
        "thresholds": {"TOL": 1e-15, "TOL_INT": 0, "TOL_BP": 1e-12, "TH_BP": 1.0,
                       "TOL_RATE": 0.0, "TOL_MARGIN_TABLE": 1e-6},
        "arith": {
            "P": "10.000000", "E": "11.000000",
            "c": "0.0003", "t": "0.0005", "s": "0.0002",
            "prec": 60,
            "frozen_literals": FROZEN_LITERALS,
            "frozen_oat": [list(x) for x in FROZEN_OAT],
            "recomputed": {k: str(vv) for k, vv in derive().items()},
            "oat_recomputed": [{"tag": r["tag"], "delta_bp": r["delta_bp"], "margin": r["margin"]}
                               for r in v["oat"]],
            "frozen_distinct_delta_bp": 7,
            "frozen_negative_count": 9,
            "frozen_min_margin": "2397.985657",
        },
        "price_grid": {
            "code": "600000", "exchange": "SH", "volume": 1000000,
            "rows": [["2026-01-05", "9.5", "10", "9.5"],
                     ["2026-01-06", "10.5", "11", "10"],
                     ["2026-01-07", "11.5", "12", "11"],
                     ["2026-01-08", "12.5", "13", "12"],
                     ["2026-01-09", "13.5", "14", "13"],
                     ["2026-01-12", "14.5", "15", "14"],
                     ["2026-01-13", "15.5", "16", "15"],
                     ["2026-01-14", "16.5", "17", "16"]],
            "base_idx": 0, "horizons": [2, 3], "qfq_map": "EMPTY",
            "close_formula": "close[idx] = 10 + idx",
        },
        "kill_conditions": {
            "K1": "V03 VIOLATED or V04 VIOLATED",
            "K2": "V07 VIOLATED",
            "K3": "V08 VIOLATED",
            "K4": "V05 CONTRACT_MISLABEL",
            "K5": "V15 VIOLATED",
            "K6": "V12 CONTRACT_AMBIGUOUS",
            "K7": "any axis in {REGISTERED_ONLY, NOT_REACHABLE}",
            "K8": "variant row-sum != 19 or config frozen mismatch or grid_mismatch_count > 0",
            "K9": "any canary pair FAIL or reverse anchor false or G3 fail or dep missing or out-of-vocab",
            "K10": "determinism gate (V19) failed",
        },
        "short_circuit": ["K8|K9|K10 -> INCONCLUSIVE", "K1|K2|K3 -> REJECTED",
                          "K4|K5 -> CONTRACT_MISLABEL", "K6 -> CONTRACT_AMBIGUOUS",
                          "K7 -> REGISTERED_ONLY", "else -> NOT_VIOLATED"],
        "h0_alive_condition": "not (K1 or K2 or K3)",
        "count_expectations": COUNT_EXPECTATIONS,
        "range_expectations": RANGE_EXPECTATIONS,
        "raw_measured": [],
        "canaries": CANARIES,
        "checkers": sorted(EXPECTED),
        "src_locations": SRC_LOCATIONS,
        "src_ranges": SRC_RANGES,
        "line_anchors": LINE_ANCHORS,
        "g3_expected_suffix": "target/classes",
        "probe_main": "com.quant.service.backtest.R010Probe",
        "prereg_selfcheck": {"frozen_match": v["all_frozen_match"], "checks": checks,
                             "mismatches": v["mismatches"]},
    }
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print("wrote %s" % CONFIG_PATH)
    return 0


if __name__ == "__main__":
    sys.exit(main())
