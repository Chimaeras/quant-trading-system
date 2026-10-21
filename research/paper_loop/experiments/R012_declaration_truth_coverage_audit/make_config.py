# -*- coding: utf-8 -*-
"""
R012 冻结配置生成器。

把 `rounds/R012_prereg.md` 的冻结内容（14 块 / 24 条文本 / N=22 变体 / 判据检查 /
容差 / 预期）下沉为**机器可读、可逐条审计**的 `config.json`，并生成 Java 探针用的
冻结面板 `panel_R012.txt`。

**纪律**：本文件只做"冻结内容的搬运与自检"，**不做任何测量**。所有实测发生在
`run_R012_declaration_audit.py`。任何对本文件的修改都等价于改冻结口径，
必须先落到 `rounds/R012_report.md` 的附录 `A-xx`。
"""

import hashlib
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))

FRS = "modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java"
SBS = "modules/backend-java/src/main/java/com/quant/service/ScoringBacktestService.java"
RESP = "modules/backend-java/src/main/java/com/quant/dto/ScoringBacktestResponse.java"
ENG = "modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java"
TSF = "modules/web/src/api/factor.ts"
TSB = "modules/web/src/api/backtest.ts"
VUE = "modules/web/src/views/factor/FactorResearch.vue"

# ══════════════════════════════════════════════════════════════════════════════
# §4.1 冻结块清单（14 块）
# ══════════════════════════════════════════════════════════════════════════════
BLOCKS = [
    dict(id="B01", file=FRS, start=150, end=171, kind="map", load_bearing=True,
         sig="out.put(\"rank_ic\"", parents=[], children=["B07", "B10"],
         desc="research() 顶层 out（legacy 研究）"),
    dict(id="B02", file=FRS, start=213, end=231, kind="map", load_bearing=True,
         sig="out.put(\"by_horizon\"", parents=[], children=["B07", "B10"],
         desc="unifiedReport() 顶层 out（统一报告）"),
    dict(id="B03", file=FRS, start=432, end=461, kind="map", load_bearing=True,
         sig="out.put(\"universe_source\"", parents=[], children=["B04", "B05", "B06"],
         desc="snapshotResearch() 顶层 out（V2-MP-022 权威路径；本层无 universe_note）"),
    dict(id="B04", file=FRS, start=525, end=553, kind="map", load_bearing=True,
         sig="private Map<String, Object> buildSnapshotScopeBlock", parents=["B03"],
         children=[], desc="buildSnapshotScopeBlock（三口径块）"),
    dict(id="B05", file=FRS, start=556, end=578, kind="map", load_bearing=False,
         sig="private Map<String, Object> unavailableScopeBlock", parents=["B03"],
         children=[], desc="unavailableScopeBlock（口径不可用退化块）"),
    dict(id="B06", file=FRS, start=605, end=627, kind="map", load_bearing=True,
         sig="private Map<String, Object> buildSnapshotFunnel", parents=["B03"],
         children=[], desc="buildSnapshotFunnel（snapshot 漏斗）"),
    dict(id="B07", file=FRS, start=641, end=797, kind="map", load_bearing=True,
         sig="private Map<String, Object> buildRankIc", parents=["B01", "B02"],
         children=["B08", "B09"], desc="buildRankIc（legacy rank_ic 块）"),
    dict(id="B08", file=FRS, start=800, end=818, kind="map", load_bearing=True,
         sig="private Map<String, Object> emptyNetIcBlock", parents=["B07"],
         children=[], desc="emptyNetIcBlock（cost_net_ic 空路径生产者）"),
    dict(id="B09", file=FRS, start=820, end=839, kind="map", load_bearing=True,
         sig="private Map<String, Object> buildNetIcBlock", parents=["B07"],
         children=[], desc="buildNetIcBlock（cost_net_ic 非空生产者）"),
    dict(id="B10", file=FRS, start=845, end=877, kind="map", load_bearing=True,
         sig="private Map<String, Object> buildResearchFunnel", parents=["B01", "B02"],
         children=[], desc="buildResearchFunnel（legacy 漏斗）"),
    dict(id="B11", file=RESP, start=79, end=91, kind="dto", load_bearing=True,
         sig="public static class FactorAnalysis", parents=[], children=[],
         desc="SBS FactorAnalysis（有 universeNote）"),
    dict(id="B12", file=RESP, start=64, end=72, kind="dto", load_bearing=True,
         sig="public static class FactorIcSummary", parents=[], children=[],
         desc="SBS FactorIcSummary（无 universe 类字段）"),
    dict(id="B13", file=SBS, start=465, end=467, kind="map", load_bearing=True,
         sig="assumptions.put(\"metric_scope\", \"FILLED_SAMPLE\")", parents=[],
         children=[], desc="SBS assumptions（口径声明）"),
    dict(id="B14", file=SBS, start=441, end=453, kind="code", load_bearing=True,
         sig="codexFactorObsByHolding.getOrDefault", parents=[], children=[],
         desc="SBS Codex 子因子摘要（复用 B12 DTO，note 在 :448 构造后被丢弃）"),
]

# SBS 漏斗块（B13 的计数部分）在 :370-378，作为 B13 的附加证据范围
B13_FUNNEL = dict(file=SBS, start=370, end=378)

# ══════════════════════════════════════════════════════════════════════════════
# §4.3 覆盖判定（冻结规则）：自身 ∪ 祖先 ∪ 直接子块
# ══════════════════════════════════════════════════════════════════════════════
K_UNIVERSE = ["universe_note", "universeNote", "universe_source"]
K_SCOPE = ["metric_scope", "metric_scopes", "executable_metric_scope", "basis_note",
           "note", "tradable_note"]

# DTO/块自身无键、但运行时由 setter 注入的字段（接线证据，用于"自身字段"判定）
WIRING = {
    "B11": dict(file=SBS, start=1018, end=1048, setter="universeNote"),
    "B12": dict(file=SBS, start=1051, end=1065, setter="universeNote"),
    "B14": dict(file=SBS, start=1051, end=1065, setter="universeNote"),
}

# 块自身字段之外的候选声明（用于"仅自身字段"描述性口径）
SELF_EXTRA = {
    "B03": ["universe_source"],
    "B13": ["metric_scope"],
    "B14": ["note"],
}

# ══════════════════════════════════════════════════════════════════════════════
# §5 冻结声明性文本清单（24 条）
# ══════════════════════════════════════════════════════════════════════════════
TEXT_FIELDS = [
    dict(id="T01", file=FRS, line=72, key="RANK_IC_UNIVERSE_NOTE", owner="B01/B02/B07",
         kind="const", anchors=["sparse selector_result", "LEGACY_SELECTION_BIASED", "幸存者偏差"]),
    dict(id="T02", file=FRS, line=75, key="RANK_IC_BASIS_NOTE", owner="B07",
         kind="const", anchors=["close[t+h]/close[t]-1", "未计费用/滑点/涨跌停可成交性"]),
    dict(id="T03", file=FRS, line=78, key="RANK_IC_NET_BASIS_NOTE", owner="B08/B09",
         kind="const", anchors=["rawOpen[t+1]", "rawClose[t+1+h]", "market_price_raw", "跳过"]),
    dict(id="T04", file=FRS, line=83, key="RANK_IC_EMBARGO_NOTE", owner="B07",
         kind="const", anchors=["自动跳过", "不外推", "HAC", "block bootstrap"]),
    dict(id="T05", file=FRS, line=91, key="SNAPSHOT_UNIVERSE_NOTE", owner="B04",
         kind="const", anchors=["完整 PIT feature snapshot 全截面", "topN=0", "market_price_raw"]),
    dict(id="T06", file=FRS, line=94, key="FEATURE_SNAPSHOT_NOTE", owner="B01/B02/B07",
         kind="const", anchors=["snapshot-research", "LEGACY_SELECTION_BIASED"]),
    dict(id="T07", file=FRS, line=410, key="rawNote", owner="B04(RAW)", kind="local",
         anchors=["rawClose[t+h]/rawClose[t]-1", "不复权", "不读 selector/qfq"]),
    dict(id="T08", file=FRS, line=413, key="costNote", owner="B04(COST_APPROX)", kind="local",
         anchors=["next-open 入场", "不含涨跌停/停牌拒单过滤"]),
    dict(id="T09", file=FRS, line=415, key="execNote", owner="B04/B05(EXECUTABLE)", kind="local_cond",
         anchors=["label_v2", "FILLED", "NOT_BUYABLE", "未提供 labelVersion"]),
    dict(id="T10", file=FRS, line=170, key="B01.note", owner="B01", kind="literal",
         anchors=["毛收益=RAW", "净收益=COST_APPROX", "禁止据此直接调整因子权重"]),
    dict(id="T11", file=FRS, line=228, key="B02.note", owner="B02", kind="literal",
         anchors=["RAW", "COST_APPROX", "sparse selector_result 存在选择偏差"]),
    dict(id="T12", file=FRS, line=458, key="B03.note", owner="B03", kind="literal",
         anchors=["V2-MP-022", "不读 selector_result / 不读 qfq", "禁止把 COST_APPROX 命名 executable"]),
    dict(id="T13", file=FRS, line=551, key="B04.note(0 观测)", owner="B04", kind="literal_cond",
         anchors=["该口径无可用观测", "不给出 IC"]),
    dict(id="T14", file=FRS, line=576, key="B05.note", owner="B05", kind="literal",
         anchors=['block.put("note", basisNote)']),
    dict(id="T15", file=FRS, line=625, key="B06.note", owner="B06", kind="literal",
         anchors=["tradable", "COST_APPROX"]),
    dict(id="T16", file=FRS, line=815, key="B08.note", owner="B08", kind="literal",
         anchors=["EXECUTABLE 暂不可用", "与毛收益 IC 窗口差一日"]),
    dict(id="T17", file=FRS, line=836, key="B09.note", owner="B09", kind="literal",
         anchors=["EXECUTABLE 暂不可用", "与毛收益 IC 窗口差一日"]),
    dict(id="T18", file=FRS, line=872, key="B10.tradable_note", owner="B10", kind="literal",
         anchors=["EXECUTABLE tradable funnel unavailable"]),
    dict(id="T19", file=FRS, line=875, key="B10.note", owner="B10", kind="literal",
         anchors=["TradabilityPolicy", "selector 候选漏斗"]),
    dict(id="T20", file=FRS, line=621, key="B06.tradable_note", owner="B06", kind="literal_cond",
         anchors=["tradable"]),
    dict(id="T21", file=SBS, line=60, key="FACTOR_UNIVERSE_NOTE", owner="B13/B12/B14", kind="const",
         anchors=["persisted 全截面候选", "非 TopN 子集", "幸存者偏差"]),
    dict(id="T22", file=SBS, line=466, key="B13.metric_scope", owner="B13", kind="literal",
         anchors=["FILLED_SAMPLE"]),
    dict(id="T23", file=SBS, line=448, key="B14.universeNote", owner="B14", kind="literal_cond",
         anchors=["Codex 子因子", "FACTOR_UNIVERSE_NOTE"]),
    dict(id="T24", file=FRS, line=49, key="类 Javadoc scope 词表", owner="全", kind="javadoc",
         anchors=["metric_scope 分 RAW / COST_APPROX / EXECUTABLE 三类"]),
]

# ══════════════════════════════════════════════════════════════════════════════
# 事实原语（引擎把 spec 变成 bool）
#   regex_present / regex_absent / ordered
# ══════════════════════════════════════════════════════════════════════════════
def present(file, start, end, pattern, note=""):
    return dict(kind="regex_present", file=file, start=start, end=end,
                pattern=pattern, note=note)


def absent(file, start, end, pattern, note=""):
    return dict(kind="regex_absent", file=file, start=start, end=end,
                pattern=pattern, note=note)


def ordered(file, start, end, patterns, note=""):
    return dict(kind="ordered", file=file, start=start, end=end,
                patterns=patterns, note=note)


# 冻结：T 字段的判定来源。
#   claim_rollup —— 由 A2 真值面的具名 claim 汇总（这些才可能驱动 K1）
#   anchor       —— 辅助判定：必须在冻结锚点行被逐字抽到，否则 CLAIM_ABSENT / FALSE_CLAIM
#   equals_basis_note / cond_guard / vocab —— 专项辅助检查
TEXT_BACKING = {
    "T01": dict(aux="claim_rollup", claims=["V07-a"]),
    "T02": dict(aux="claim_rollup", claims=["V05-a", "V05-b"]),
    "T03": dict(aux="claim_rollup", claims=["V06-a", "V06-b", "V06-c", "V06-d", "V06-e"]),
    "T04": dict(aux="claim_rollup", claims=["V08-a", "V08-b", "V08-c"]),
    "T05": dict(aux="claim_rollup", claims=["V07-b", "V07-c"]),
    "T06": dict(aux="anchor", extra=[present(FRS, 658, 658, r"FEATURE_SNAPSHOT_NOTE", "B07 发射")]),
    "T07": dict(aux="claim_rollup", claims=["V10-a", "V10-b"]),
    "T08": dict(aux="claim_rollup", claims=["V10-c", "V10-d"]),
    "T09": dict(aux="claim_rollup", claims=["V10-e", "V10-f", "V10-g"]),
    "T10": dict(aux="anchor", extra=[present(FRS, 169, 171, r"universe_note", "B01 发射点")]),
    "T11": dict(aux="anchor", extra=[present(FRS, 227, 229, r"universe_note", "B02 发射点")]),
    "T12": dict(aux="anchor", extra=[present(FRS, 458, 459, r"不读 selector_result", "B03 发射点")]),
    "T13": dict(aux="cond_guard", extra=[
        present(FRS, 525, 553, r"summary\.totalObservations\(\) == 0", "note 仅在 0 观测时发射"),
        present(FRS, 525, 553, r"该口径无可用观测"), ]),
    "T14": dict(aux="equals_basis_note", extra=[
        present(FRS, 556, 578, r"block\.put\(\"note\", basisNote\)", "B05 note = basisNote")]),
    "T15": dict(aux="anchor", extra=[present(FRS, 605, 627, r"buildSnapshotFunnel", "B06 自证")]),
    "T16": dict(aux="claim_rollup", claims=["V09-a", "V09-b"]),
    "T17": dict(aux="claim_rollup", claims=["V09-a", "V09-b"]),
    "T18": dict(aux="anchor", extra=[present(FRS, 872, 872, r"tradable_note", "B10 发射")]),
    "T19": dict(aux="anchor", extra=[present(FRS, 875, 875, r"selector 候选漏斗", "B10 发射")]),
    "T20": dict(aux="cond_guard", extra=[
        present(FRS, 605, 627, r"tradable_note", "B06 条件式发射"),
        present(FRS, 605, 627, r"executableAvailable", "条件变量")]),
    "T21": dict(aux="anchor", extra=[present(SBS, 60, 61, r"未复现历史时点股票池", "T21 逐字")]),
    "T22": dict(aux="anchor", extra=[present(SBS, 466, 466, r"FILLED_SAMPLE", "B13 口径值")]),
    "T23": dict(aux="anchor", extra=[
        present(SBS, 448, 448, r"\"Codex 子因子 \" \+ factorKey", "B14 条件拼接")]),
    "T24": dict(aux="vocab", extra=[
        present(FRS, 49, 49, r"metric_scope 分 RAW / COST_APPROX / EXECUTABLE 三类", "词表逐字")]),
}

#   regex_present / regex_absent / ordered / keys_have / keys_absent /
#   keyset_diff / dto_absent_via_wiring
# ══════════════════════════════════════════════════════════════════════════════
# ══════════════════════════════════════════════════════════════════════════════
# §12.2 读数敏感性（±20% 的等价替代）：同一主张的「朴素子串」读数 vs「标识符/词边界」读数
#   键 = 精确读数模式；值 = 朴素子串读数模式。
#   任何读出不同判定的主张，按 §12.2 **降级为描述性**（不得驱动 K1）。
# ══════════════════════════════════════════════════════════════════════════════
READING_NAIVE = {
    # 精确：仅匹配"作为价格来源被使用"的标识符；朴素：任何出现（含注释里的否定句）
    r"loadPriceCache|marketDataRepository|qfqClose|qfqOpen|QfqPrice": r"qfq|Qfq|QFQ|marketDataRepository",
    r"selectorResult": r"selector_result|selectorResult",
    r"topN|LIMIT": r"topN|LIMIT|top_n",
}

# ══════════════════════════════════════════════════════════════════════════════
# §7 冻结变体表（N=22：A1=4 / A2=6 / A3=5 / A4=4 / A5=3）
# ══════════════════════════════════════════════════════════════════════════════
VARIANTS = [
    # ── A1 块与结构面 ────────────────────────────────────────────────────────
    dict(id="V01", axis="A1", rule="block_structure", expect="TRUE_CLAIM",
         desc="块清单 = 14，冻结锚点全部命中（0 mismatch）"),
    dict(id="V02", axis="A1", rule="keyset_diff_branch", expect="TRUE_CLAIM",
         desc="B07 空/非空两分支键集差集非空",
         ranges=dict(empty=[660, 671], full=[649, 658, 755, 796]),
         branch_note="空分支 = :660-671；非空分支 = :649-658（公共头）+ :755-796（数值段）"),
    dict(id="V03", axis="A1", rule="keyset_diff_pair", expect="TRUE_CLAIM",
         desc="B08 与 B09 键集差集（预期 {monotonicity}）",
         blocks=["B08", "B09"], cross_ref="B-R009-A5b（不作 kill）"),
    dict(id="V04", axis="A1", rule="dto_field_asymmetry", expect="TRUE_CLAIM",
         desc="B11 有 universeNote / B12 无 universe 类字段（判定只用冻结扫描根内事实）",
         facts=[
             present(RESP, 79, 91, r"universeNote", "B11 Java DTO 字段"),
             present(SBS, 1018, 1048, r"\.universeNote\(summary\.universeNote\(\)\)", "B11 接线"),
             absent(RESP, 64, 72, r"universe", "B12 Java DTO 无 universe 类字段"),
             absent(SBS, 1051, 1065, r"universe", "B12 映射器未写 universe 字段"),
         ],
         # modules/web/src/api/backtest.ts 在 §3 冻结扫描根之外 ⟹ 仅作旁证，**不参与判定**
         corroboration=[
             present(TSB, 52, 65, r"universeNote", "TS FactorAnalysis 有 universeNote"),
             absent(TSB, 67, 77, r"universeNote", "TS FactorIcSummary 无 universeNote"),
         ]),
    # ── A2 真值面 ────────────────────────────────────────────────────────────
    dict(id="V05", axis="A2", rule="truth", expect="TRUE_CLAIM",
         desc="T02（RAW 公式 / 未计费用）逐条",
         claims=[
             dict(id="V05-a", klass="F1",
                  text="毛收益 forward return = close[t+h]/close[t]-1（t=信号日，h=持仓交易日数）",
                  facts=[present(FRS, 674, 716, r"grossExitIdx = baseIdx \+ holdingDays"),
                         present(FRS, 674, 716, r"// 毛收益：close\[t\+h\] / close\[t\] - 1"),
                         present(FRS, 674, 716,
                                 r"double forwardReturn = closeExit\.doubleValue\(\) / closeSignal\.doubleValue\(\) - 1\.0"),
                         present(FRS, 674, 716, r"BigDecimal closeSignal = prices\.close\(\)")]),
             dict(id="V05-b", klass="F3",
                  text="未计费用/滑点/涨跌停可成交性（毛收益路径）",
                  facts=[absent(FRS, 706, 716, r"ResearchConvention\."),
                         absent(FRS, 706, 716, r"SLIPPAGE|COMMISSION|SELL_TAX"),
                         absent(FRS, 706, 716, r"tradab|Tradab|LIMIT_UP|limitUp|SUSPEND|execStatus")]),
         ]),
    dict(id="V06", axis="A2", rule="truth", expect="TRUE_CLAIM",
         desc="T03（COST_APPROX 四件事 + 记法）逐条",
         claims=[
             dict(id="V06-a", klass="F1",
                  text="entry = rawOpen[t+1]×(1+佣金+滑点)",
                  facts=[present(FRS, 683, 740, r"netEntryIdx = baseIdx \+ 1"),
                         present(FRS, 683, 740, r"BigDecimal openEntry = prices\.rawOpen\(\)\.get"),
                         present(FRS, 683, 740, r"ResearchConvention\.DEFAULT_SLIPPAGE_RATE"),
                         present(FRS, 683, 740, r"ResearchConvention\.DEFAULT_COMMISSION_RATE")]),
             dict(id="V06-b", klass="F1",
                  text="exit = rawClose[t+1+h]×(1-佣金-印花税-滑点)",
                  facts=[present(FRS, 683, 740, r"netExitIdx = netEntryIdx \+ holdingDays"),
                         present(FRS, 683, 740, r"BigDecimal closeExit = prices\.rawClose\(\)\.get"),
                         present(FRS, 683, 740, r"ResearchConvention\.DEFAULT_SELL_TAX_RATE")]),
             dict(id="V06-c", klass="F2",
                  text="成交价只读 market_price_raw（V2-MP-014）；缺 raw 即跳过该观测",
                  facts=[present(FRS, 718, 737, r"V2-MP-014：成交价只读 market_price_raw"),
                         present(FRS, 718, 737, r"prices\.rawOpen\(\)"),
                         present(FRS, 718, 737, r"prices\.rawClose\(\)"),
                         ordered(FRS, 718, 737,
                                 [r"if \(openEntry != null && closeExit != null", r"netObs\.add"]),
                         absent(FRS, 718, 737, r"prices\.close\(\)|prices\.open\(\)|marketDataRepository")]),
             dict(id="V06-d", klass="F3",
                  text="不含涨跌停/停牌拒单过滤（净收益路径）",
                  facts=[absent(FRS, 718, 737,
                                r"LIMIT_UP|tradab|Tradab|SUSPEND|NOT_BUYABLE|NOT_SELLABLE|execStatus|execBreakdown")]),
             dict(id="V06-e", klass="NOT_FALSIFIABLE",
                  text="记法「×(1+佣金+滑点)」写作单级乘性，实现为两级乘性 entryPrice×(1+佣金)",
                  reason="§6.2 记法差异类：同一算术的书写形式不同，不构成事实主张；交叉引用 D-R010-01（R010 已登记）"),
         ]),
    dict(id="V07", axis="A2", rule="truth", expect="TRUE_CLAIM",
         desc="T01 + T05（两条样本域主张）",
         claims=[
             dict(id="V07-a", klass="F2",
                  text="T01：LEGACY_SELECTION_BIASED，sparse selector_result 取样（非完整 PIT 截面）",
                  facts=[present(FRS, 111, 232, r"selectorResultRepository"),
                         present(FRS, 111, 232, r"selectorResultItemRepository"),
                         present(FRS, 150, 172, r"SOURCE_LEGACY_SELECTION_BIASED"),
                         absent(FRS, 111, 232, r"featureSnapshotRowRepository"),
                         absent(FRS, 111, 232, r"loadRawPriceCache")]),
             dict(id="V07-b", klass="F2",
                  text="T05：完整 PIT feature snapshot 全截面（V2-MP-016，topN=0 无上限）",
                  facts=[present(FRS, 257, 292, r"featureSnapshotManifestRepository"),
                         present(FRS, 257, 292, r"featureSnapshotRowRepository\.listPartition"),
                         absent(FRS, 257, 292, r"topN|LIMIT")]),
             dict(id="V07-c", klass="F2",
                  text="T05：forward return 只读 market_price_raw / label_v2，不读 selector/qfq",
                  facts=[present(FRS, 257, 460, r"loadRawPriceCache"),
                         present(FRS, 257, 460, r"labelSnapshotRepository"),
                         absent(FRS, 257, 460, r"selectorResult"),
                         absent(FRS, 257, 460,
                                r"loadPriceCache|marketDataRepository|qfqClose|qfqOpen|QfqPrice")]),
         ]),
    dict(id="V08", axis="A2", rule="truth", expect="TRUE_CLAIM",
         desc="T04（自动跳过 / 不外推 / 显著性口径）逐条",
         claims=[
             dict(id="V08-a", klass="F3",
                  text="信号日接近数据末端（forward 区间未形成）自动跳过，不外推",
                  facts=[present(FRS, 674, 716, r"grossExitIdx < tradingDays\.size\(\)"),
                         present(FRS, 341, 351, r"baseIdx \+ holding < prices\.days\(\)\.size\(\)"),
                         present(FRS, 674, 677, r"endDate\.plusDays\(holdingDays \* 2L\)")]),
             dict(id="V08-b", klass="F3",
                  text="不外推（不合成/插值 forward 收益）",
                  facts=[absent(FRS, 333, 410, r"interpolat|extrapolat|forwardFill|ffill")]),
             dict(id="V08-c", klass="F4",
                  text="重叠持有期 IC 序列的显著性以 HAC 标准误与 block bootstrap CI 为准",
                  facts=[present(ENG, 185, 192, r"neweyWestHacStd\(icSeries\)"),
                         present(ENG, 185, 192, r"blockBootstrapCi\(icSeries"),
                         present(FRS, 763, 765, r"hac_std"),
                         present(FRS, 763, 765, r"ci_lower"),
                         present(FRS, 763, 765, r"ci_upper")]),
         ]),
    dict(id="V09", axis="A2", rule="truth", expect="TRUE_CLAIM",
         desc="T16/T17（窗口差一日 + EXECUTABLE 暂不可用）",
         claims=[
             dict(id="V09-a", klass="F1",
                  text="净收益 IC 与毛收益 IC 窗口差一日（t→t+h vs t+1→t+1+h）",
                  facts=[present(FRS, 692, 694,
                                 r"int grossExitIdx = baseIdx \+ holdingDays"),
                         present(FRS, 692, 694, r"int netEntryIdx = baseIdx \+ 1"),
                         present(FRS, 692, 694, r"int netExitIdx = netEntryIdx \+ holdingDays")]),
             dict(id="V09-b", klass="F3",
                  text="EXECUTABLE 暂不可用（legacy 路径无 labelVersion 入口）",
                  facts=[present(FRS, 150, 172, r"executable_metric_scope\"?,?\s*\"?UNAVAILABLE"),
                         absent(FRS, 641, 647, r"labelVersion")]),
         ]),
    dict(id="V10", axis="A2", rule="truth", expect="TRUE_CLAIM",
         desc="T07/T08/T09（snapshot 三口径 note）",
         claims=[
             dict(id="V10-a", klass="F2",
                  text="T07：RAW = rawClose[t+h]/rawClose[t]-1，只读 market_price_raw 不复权（含分红/除权缺口）",
                  facts=[present(FRS, 340, 350, r"// RAW：rawClose\[t\+h\]/rawClose\[t\]-1"),
                         present(FRS, 340, 350, r"baseIdx \+ holding"),
                         present(FRS, 492, 523, r"marketPriceRawRepository"),
                         present(FRS, 492, 523, r"new SnapshotPriceCache\(dayIndex, days, close, open\)"),
                         absent(FRS, 492, 523, r"marketDataRepository|qfq|adj")]),
             dict(id="V10-b", klass="F3",
                  text="T07：不计费用/滑点/涨跌停可成交性（snapshot RAW 路径）",
                  facts=[absent(FRS, 340, 351, r"ResearchConvention\.|tradab|LIMIT_UP|SUSPEND")]),
             dict(id="V10-c", klass="F1",
                  text="T08：next-open 入场 + 持有期收盘退出 + 扣费（ResearchConvention），成交价只读 market_price_raw",
                  facts=[present(FRS, 352, 369, r"baseIdx \+ 1"),
                         present(FRS, 352, 369, r"baseIdx \+ 1 \+ holding"),
                         present(FRS, 352, 369, r"prices\.open\(\)"),
                         present(FRS, 352, 369, r"prices\.close\(\)"),
                         present(FRS, 352, 369, r"ResearchConvention\.DEFAULT_COMMISSION_RATE"),
                         present(FRS, 352, 369, r"ResearchConvention\.DEFAULT_SLIPPAGE_RATE")]),
             dict(id="V10-d", klass="F3",
                  text="T08：不含涨跌停/停牌拒单过滤（snapshot COST_APPROX 路径）",
                  facts=[absent(FRS, 352, 370, r"LIMIT_UP|tradab|SUSPEND")]),
             dict(id="V10-e", klass="F3",
                  text="T09 分支A：join label_v2 execStatus=FILLED，forward return = label.netReturn",
                  facts=[present(FRS, 294, 309, r"labelSnapshotRepository"),
                         present(FRS, 294, 309, r"labelByKey"),
                         present(FRS, 371, 386, r"ResearchConvention\.EXEC_STATUS_FILLED\.equals"),
                         present(FRS, 371, 386, r"label\.getNetReturn\(\)"),
                         present(FRS, 371, 386, r"execObs\.add")]),
             dict(id="V10-f", klass="F3",
                  text="T09 分支A：四类拒单不计入可执行 IC，分别计入拒绝漏斗",
                  facts=[present(FRS, 294, 309, r"NOT_BUYABLE"),
                         present(FRS, 294, 309, r"EXEC_STATUS_SUSPENDED"),
                         present(FRS, 294, 309, r"EXEC_STATUS_NOT_SELLABLE"),
                         present(FRS, 294, 309, r"EXEC_STATUS_DELISTED"),
                         present(FRS, 371, 386, r"incrementCount\(execBreakdown"),
                         ordered(FRS, 371, 386,
                                 [r"ResearchConvention\.EXEC_STATUS_FILLED\.equals", r"execObs\.add"])]),
             dict(id="V10-g", klass="F3",
                  text="T09 分支B：未提供 labelVersion 时口径不可用",
                  facts=[present(FRS, 302, 303, r"labelVersion != null && !labelVersion\.isBlank\(\)"),
                         present(FRS, 426, 429,
                                 r"unavailableScopeBlock\(METRIC_SCOPE_EXECUTABLE, execNote\)")]),
         ]),
    # ── A3 覆盖面 ────────────────────────────────────────────────────────────
    dict(id="V11", axis="A3", rule="coverage_matrix", expect="COVERAGE_MISSING_UNIVERSE",
         key_class="universe", desc="14 块 × 样本域声明覆盖矩阵（自身 ∪ 祖先 ∪ 直接子）"),
    dict(id="V12", axis="A3", rule="coverage_matrix", expect=None,
         expect_note="§7 冻结行未写预期判定（只写「14 块 × 口径声明的覆盖矩阵」）⟹ 仅记录实测",
         key_class="scope", desc="14 块 × 口径声明覆盖矩阵"),
    dict(id="V13", axis="A3", rule="self_only", expect="COVERAGE_MISSING_UNIVERSE",
         desc="仅自身字段口径下的样本域缺失清单（描述性，不作 kill）"),
    dict(id="V14", axis="A3", rule="load_bearing", expect="TRUE_CLAIM",
         desc="承载数值结果的块判定（按 §4.1 冻结列）"),
    dict(id="V15", axis="A3", rule="coverage_b12", expect="COVERAGE_MISSING_UNIVERSE",
         desc="B12/B14 = COVERAGE_MISSING_UNIVERSE？（候选 K2）"),
    # ── A4 一致性面 ──────────────────────────────────────────────────────────
    dict(id="V16", axis="A4", rule="consistency_b11_b12", expect="INCONSISTENT",
         desc="B11 vs B12 样本域声明不对称（候选 K3）",
         facts=[present(SBS, 431, 437, r"FACTOR_UNIVERSE_NOTE", "B12 的 note 在调用处构造"),
                present(SBS, 443, 449, r"FACTOR_UNIVERSE_NOTE", "B14 的 note 在调用处构造"),
                present(RESP, 79, 91, r"universeNote", "B11 有字段"),
                absent(RESP, 64, 72, r"universe", "B12 无字段"),
                absent(SBS, 1051, 1065, r"universe", "B12 映射器丢弃 note")]),
    dict(id="V17", axis="A4", rule="scope_vocab", expect="SCOPE_VOCAB_UNRECONCILED",
         desc="metric_scope 取值域跨面 × T24 词表的可调和性（注册项，不杀）"),
    dict(id="V18", axis="A4", rule="synonymy", expect="CONSISTENT_DISTINCT_SOURCES",
         desc="T01 与 T21 两条样本域声明的同义性（各自是否描述不同数据路径）",
         facts=[present(FRS, 72, 74, r"sparse selector_result 取样", "T01 描述 FRS legacy 路径"),
                present(SBS, 60, 61, r"persisted 全截面候选", "T21 描述 SBS 候选路径"),
                present(FRS, 150, 172, r"SOURCE_LEGACY_SELECTION_BIASED", "FRS 来源常量"),
                present(SBS, 466, 467, r"FILLED_SAMPLE", "SBS 口径常量")]),
    dict(id="V19", axis="A4", rule="toplevel_notes", expect="CONSISTENT",
         desc="T10/T11/T12 三个顶层 note 与各自块内 metric_scope 的表述一致性"),
    # ── A5 仪器/对照面 ───────────────────────────────────────────────────────
    dict(id="V20", axis="A5", rule="positive_control", expect="TRUE_CLAIM",
         desc="正面对照：T01 常量确实到达载荷（发射点 + 引擎透传）",
         facts=[present(FRS, 747, 753, r"RANK_IC_UNIVERSE_NOTE", "legacy 传给 summarize"),
                present(FRS, 654, 654, r"universe_note\", RANK_IC_UNIVERSE_NOTE", "B07 发射"),
                present(FRS, 169, 169, r"universe_note\", RANK_IC_UNIVERSE_NOTE", "B01 发射"),
                present(FRS, 227, 227, r"universe_note\", RANK_IC_UNIVERSE_NOTE", "B02 发射"),
                present(SBS, 436, 436, r"FACTOR_UNIVERSE_NOTE", "SBS 传给 summarize"),
                present(SBS, 1047, 1047, r"\.universeNote\(summary\.universeNote\(\)\)", "引擎→DTO")]),
    dict(id="V21", axis="A5", rule="reverse_anchor", expect="TRUE_CLAIM",
         desc="反向锚点：hac_std/ci_* 来自同一 IC 序列（静态 + 运行时）",
         facts=[present(ENG, 170, 192, r"List<Double> icSeries"),
                present(ENG, 185, 192, r"neweyWestHacStd\(icSeries\)"),
                present(ENG, 185, 192, r"blockBootstrapCi\(icSeries")]),
    dict(id="V22", axis="A5", rule="canary", expect="TRUE_CLAIM",
         desc="金丝雀（CLN/INJ）+ 行和/轴计数/网格 + 逐位确定性"),
]

VERDICT_VOCAB = [
    "TRUE_CLAIM", "FALSE_CLAIM", "UNFALSIFIABLE", "CLAIM_ABSENT",
    "CLAIM_UNDECIDABLE_STATIC", "COVERAGE_OK", "COVERAGE_MISSING_UNIVERSE",
    "COVERAGE_MISSING_SCOPE", "SCOPE_VOCAB_UNRECONCILED", "CONSISTENT",
    "INCONSISTENT", "CONSISTENT_DISTINCT_SOURCES", "NOT_REACHED_STATIC_ONLY",
]

TOLERANCES = dict(TOL=1e-15, TOL_INT=0, DET_BITS=64,
                  G3_SUFFIX="modules/backend-java/target/classes",
                  BLOCK_COUNT=14, TEXT_FIELD_COUNT=24, MAX_VARIANTS=22,
                  CANARY_PAIRS=22, PROBE_CLEANUP=True)

JUDGMENT_SOURCE = dict(
    citation="Hou, Kewei; Xue, Chen; Zhang, Lu — Replicating Anomalies (NBER WP 23394 / RFS 2020)",
    local_carrier="NBER Working Paper 23394, May 2017",
    doi="10.3386/w23394",
    published="The Review of Financial Studies 33(5):2019-2133 (2020)",
    grade="A",
    carrier_pdf="papers/hxz2020_rfs_replicating_anomalies.pdf",
    carrier_pdf_bytes=821858,
    carrier_pdf_sha256="8d00169a4cf6b4895587b054b41fe934b600ea501f210f02433cfe4db316f163",
    carrier_txt="papers/hxz2020_rfs_replicating_anomalies.txt",
    carrier_txt_bytes=418945,
    carrier_txt_sha256="8ab941bf1f37b287d5bfa35526e1ece551bb9432fefa71c7dd538fd08164e2d8",
    carrier_txt_chars=401306,
    carrier_pages=130,
    criteria=dict(
        J1=dict(text="样本域是第一位口径选择；不同样本域下定性结论翻转", grade="L"),
        J2=dict(text="口径选择必须逐项显式声明（consistent set of replication procedures）", grade="L"),
        J3=dict(text="未声明的关键选择 ⟹ 结果不可重建", grade="L"),
        J4=dict(text="论文样本（1967-2014 美国、447 异象）不可移植", grade="X"),
    ),
    forbidden_numbers=["t >= 1.96", "t >= 3", "0.64", "0.85", "0.0328", "1.32", "1.03",
                       "20th percentile", "447", "452", "57", "68", "38", "79", "103", "102"],
)

FILES = dict(FRS=FRS, SBS=SBS, RESP=RESP, ENG=ENG, TSF=TSF, TSB=TSB, VUE=VUE)

EXPECT = dict(
    P1=dict(what="块清单实测 = 14，锚点 mismatch = 0", expect=14),
    P2=dict(what="B07 两分支键集差集非空", expect=True),
    P3=dict(what="B08/B09 键集差集 = {monotonicity}（仅描述）", expect=["monotonicity"]),
    P4=dict(what="B12 无 universe 字段 / B11 有 universeNote", expect=True),
    P5=dict(what="T03 四件事全真 + 记法 UNFALSIFIABLE", expect=True),
    P6=dict(what="T04 两条主张全真", expect=True),
    P7=dict(what="T16/T17 窗口差一日为真", expect=True),
    P8=dict(what="V15 -> COVERAGE_MISSING_UNIVERSE（K2 触发）", expect="COVERAGE_MISSING_UNIVERSE"),
    P9=dict(what="V16 -> INCONSISTENT（K3 触发）", expect="INCONSISTENT"),
    P10=dict(what="V17 -> SCOPE_VOCAB_UNRECONCILED（注册项，不杀）",
             expect="SCOPE_VOCAB_UNRECONCILED"),
    P11=dict(what="V18 -> CONSISTENT_DISTINCT_SOURCES（不杀）",
             expect="CONSISTENT_DISTINCT_SOURCES"),
    P12=dict(what="V13（仅自身字段）缺失清单非空（≥5 块）",
             expect=dict(nonempty=True, min_blocks=5)),
    P13=dict(what="total_verdict = REJECTED，h0_alive = false", expect="REJECTED"),
    P14=dict(what="K1 不触发（FALSE_CLAIM 计 0）", expect=0),
    P15=dict(what="表外词 = []；两次运行逐位相等", expect=True),
    P16=dict(what="T24 词表不含 FILLED_SAMPLE ⟹ 与 T21/T22 不可调和", expect=True),
)

# ══════════════════════════════════════════════════════════════════════════════
# 冻结面板（Java 探针用）
# ══════════════════════════════════════════════════════════════════════════════
def _panel_rows(n, A=0.04, B=0.006, C=0.0015):
    rows = []
    for i in range(1, n + 1):
        x = (i - (n + 1) / 2.0) / n
        fwd = A * x * x + B * x + C * math.sin(3.0 * i)
        rows.append([float(i), fwd])
    return rows


PANEL = dict(
    design="score_i = i（整数、无并列）；fwd_i = A*x^2 + B*x + C*sin(3i)，x=(i-(n+1)/2)/n；PA 非退化、PB 空、PC 全等收益",
    A=0.04, B=0.006, C=0.0015,
    scenarios=[
        dict(id="PA", kind="nondegenerate", holding=2, group=5, min_size=5,
             universe_note="R012-PROBE-PA-UNIVERSE-NOTE",
             days=[dict(day="D1", n=12, rows=_panel_rows(12)),
                   dict(day="D2", n=10, rows=_panel_rows(10)),
                   dict(day="D3", n=9, rows=_panel_rows(9))]),
        dict(id="PB", kind="empty", holding=2, group=5, min_size=5,
             universe_note="R012-PROBE-PB-UNIVERSE-NOTE", days=[]),
        dict(id="PC", kind="flat", holding=2, group=5, min_size=5,
             universe_note="R012-PROBE-PC-UNIVERSE-NOTE",
             days=[dict(day="D1", n=8, rows=[[float(i), 0.01] for i in range(1, 9)]),
                   dict(day="D2", n=8, rows=[[float(i), 0.01] for i in range(1, 9)])],
             note="R006 已登记「平坦序列判定为 MONOTONIC_INCREASING」，此处仅作仪器读数锚点"),
    ],
)


def build_config():
    # ── 自检 1：冻结计数 ─────────────────────────────────────────────────────
    assert len(BLOCKS) == TOLERANCES["BLOCK_COUNT"], len(BLOCKS)
    assert len(TEXT_FIELDS) == TOLERANCES["TEXT_FIELD_COUNT"], len(TEXT_FIELDS)
    assert len(VARIANTS) == TOLERANCES["MAX_VARIANTS"], len(VARIANTS)

    # ── 自检 2：轴计数与行和 ─────────────────────────────────────────────────
    axis_counts = {}
    for v in VARIANTS:
        axis_counts[v["axis"]] = axis_counts.get(v["axis"], 0) + 1
    assert axis_counts == {"A1": 4, "A2": 6, "A3": 5, "A4": 4, "A5": 3}, axis_counts
    assert sum(axis_counts.values()) == len(VARIANTS)

    # ── 自检 3：块 id 连续性 + 父子一致 ─────────────────────────────────────
    ids = [b["id"] for b in BLOCKS]
    assert ids == ["B%02d" % i for i in range(1, 15)], ids
    for b in BLOCKS:
        for c in b.get("children", []):
            assert c in ids, (b["id"], c)
        for p in b.get("parents", []):
            assert p in ids, (b["id"], p)

    # ── 自检 4：判定词表覆盖（expect 为 None 表示 §7 未冻结预期，仅记录实测） ──
    for v in VARIANTS:
        assert v["expect"] is None or v["expect"] in VERDICT_VOCAB, (v["id"], v["expect"])
        if v["expect"] is None:
            assert v.get("expect_note"), ("缺 expect_note", v["id"])

    # ── 自检 5：预注册 hash（冻结证据） ──────────────────────────────────────
    repo = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
    prereg = os.path.join(repo, "research", "paper_loop", "rounds", "R012_prereg.md")
    prereg_sha = hashlib.sha256(open(prereg, "rb").read()).hexdigest()

    # ── 自检 6：T 字段判定来源完备（24 条全部有 backing，claim 引用必须存在） ──
    claim_ids = set()
    for v in VARIANTS:
        for c in v.get("claims", []):
            claim_ids.add(c["id"])
    tids = [t["id"] for t in TEXT_FIELDS]
    assert tids == ["T%02d" % i for i in range(1, 25)], tids
    assert sorted(TEXT_BACKING.keys()) == tids, sorted(TEXT_BACKING.keys())
    for tid, bk in TEXT_BACKING.items():
        assert bk["aux"] in ("claim_rollup", "anchor", "equals_basis_note",
                             "cond_guard", "vocab"), (tid, bk["aux"])
        for cid in bk.get("claims", []):
            assert cid in claim_ids, (tid, cid)

    cfg = dict(
        round="R012",
        subject="研究载荷声明性文本字段族的『真值 / 覆盖 / 跨块跨面同义』审计",
        subject_kind="只读审计（论文只作判据来源，不采纳任何结论/数值）",
        baseline_commit="942714fb",
        prereg="rounds/R012_prereg.md",
        prereg_sha256=prereg_sha,
        judgment_source=JUDGMENT_SOURCE,
        tolerances=TOLERANCES,
        files=FILES,
        k_universe=K_UNIVERSE,
        k_scope=K_SCOPE,
        blocks=BLOCKS,
        b13_funnel=B13_FUNNEL,
        wiring=WIRING,
        self_extra=SELF_EXTRA,
        text_fields=TEXT_FIELDS,
        text_backing=TEXT_BACKING,
        variants=VARIANTS,
        axis_counts=axis_counts,
        verdict_vocab=VERDICT_VOCAB,
        reading_naive=READING_NAIVE,
        expectations=EXPECT,
        panel=PANEL,
    )
    return cfg


def write_panel(path):
    lines = []
    for sc in PANEL["scenarios"]:
        for d in sc["days"]:
            for score, fwd in d["rows"]:
                lines.append("%s,%s,%.17g,%.17g" % (sc["id"], d["day"], score, fwd))
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    return len(lines)


def main():
    cfg = build_config()
    with open(os.path.join(HERE, "config.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")
    rows = write_panel(os.path.join(HERE, "panel_R012.txt"))
    print("config.json 写好：blocks=%d text=%d variants=%d axis=%s"
          % (len(cfg["blocks"]), len(cfg["text_fields"]), len(cfg["variants"]),
             cfg["axis_counts"]))
    print("panel_R012.txt 写好：%d 行" % rows)
    print("prereg sha256 = %s" % cfg["prereg_sha256"])


if __name__ == "__main__":
    main()
