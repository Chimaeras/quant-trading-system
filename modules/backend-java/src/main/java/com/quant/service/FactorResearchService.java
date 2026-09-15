package com.quant.service;

import com.quant.entity.FeatureSnapshotManifest;
import com.quant.entity.FeatureSnapshotRow;
import com.quant.entity.LabelSnapshot;
import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.FeatureSnapshotManifestRepository;
import com.quant.repository.FeatureSnapshotRowRepository;
import com.quant.repository.LabelSnapshotRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.research.FeatureSchemaRegistry;
import com.quant.research.ResearchConvention;
import com.quant.service.DataSnapshotService;
import com.quant.service.backtest.FactorResearchEngine;
import com.quant.service.backtest.FactorResearchEngine.CrossSectionObservation;
import com.quant.service.backtest.FactorResearchEngine.DailyRankIc;
import com.quant.service.backtest.FactorResearchEngine.FactorResearchSummary;
import com.quant.service.backtest.FactorResearchEngine.GroupReturnStat;
import com.quant.service.scoring.UnifiedScoringStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 因子研究面板：得分覆盖率、截面分布分位数、Rank IC 与分组收益。
 *
 * <p>两条数据路径（V2-MP-022 起并存，来源必须显式标注，禁止混淆）：
 * <ul>
 *   <li><b>snapshotResearch（权威，FULL_FEATURE_SNAPSHOT）</b>：输入 MP-016 feature snapshot
 *       完整 PIT 候选截面（feature_snapshot_row，topN=0 全截面），join label_v2 执行状态；
 *       forward return 只读 market_price_raw / label.netReturn，不读 selector_result、不读 qfq。
 *       metric_scope 的取值域为 <b>5 值</b>：RAW / COST_APPROX / EXECUTABLE / FILLED_SAMPLE / UNAVAILABLE
 *       （前三个为「口径类型」，UNAVAILABLE 为「该口径本面不可用」，FILLED_SAMPLE 为「仅 FILLED 样本子集」）；
 *       EXECUTABLE 仅对 FILLED 样本给出。注意同名 metric_scope=RAW 在两个面指向**不同价格基准**（见 price_basis：
 *       legacy = QFQ_CLOSE 前复权，snapshot = RAW_CLOSE 不复权）。</li>
 *   <li><b>research / unifiedReport（LEGACY_SELECTION_BIASED）</b>：sparse selector_result 取样，
 *       存在选择偏差，仅保留供历史对照，响应必须标注 LEGACY_SELECTION_BIASED。</li>
 * </ul>
 *
 * <p>共通口径：
 * <ul>
 *   <li>覆盖率 = final_score 非空且 > 0 的候选数 / 总候选数。</li>
 *   <li>分布分位数：min / p25 / median / p75 / max（线性插值）。</li>
 *   <li>Rank IC：信号日得分与 forward return 的 Spearman 秩相关；
 *       forward return = close[t+h] / close[t] - 1（t 为信号日，h 为持仓交易日数）。</li>
 *   <li>毛收益不计佣金/印花税/滑点/涨跌停可成交性/停牌，与回测成交收益口径不同，
 *       仅用于因子方向预测力评价；禁止据此直接调整因子权重。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorResearchService {

    private static final int DEFAULT_RANK_IC_HOLDING_DAYS = 5;
    private static final int RANK_IC_GROUP_COUNT = 5;
    private static final int RANK_IC_MIN_CROSS_SECTION_SIZE = 10;
    private static final String RANK_IC_UNIVERSE_NOTE =
            "LEGACY_SELECTION_BIASED：sparse selector_result 取样（非完整 PIT 截面），存在选择/幸存者偏差；"
                    + "完整截面 IC 请走 snapshot-research（V2-MP-022，输入 snapshotId 读取 MP-016 feature snapshot）";
    private static final String RANK_IC_BASIS_NOTE =
            "毛收益 IC（metric_scope=RAW）：forward return = close[t+h]/close[t]-1，未计费用/滑点/涨跌停可成交性；"
                    + "与回测成交收益口径不同，仅用于因子方向预测力评价；"
                    + "本面（legacy selector）价格基准 = **qfq 前复权** close（price_basis=QFQ_CLOSE）——"
                    + "与 snapshot 面同名 metric_scope=RAW 的 **rawClose 不复权**（price_basis=RAW_CLOSE）不是同一基准，"
                    + "同名不同基准，跨面比较前必须先看 price_basis";
    private static final String RANK_IC_NET_BASIS_NOTE =
            "成本后近似净收益 IC（metric_scope=COST_APPROX）：entry = rawOpen[t+1]×(1+佣金+滑点)，"
                    + "exit = rawClose[t+1+h]×(1-佣金-印花税-滑点)，成交价只读 market_price_raw（V2-MP-014），"
                    + "缺 raw 即跳过该观测；与 ResearchConvention / LabelFactory 成交口径一致；"
                    + "不含涨跌停/停牌拒单过滤，不得称为完整可执行 IC；"
                    + "**记法声明**：上式 ASCII 写法为方便阅读的**加性**写法，实现为两级**乘性**——"
                    + "entry = rawOpen[t+1]×(1+滑点)×(1+佣金)，exit = rawClose[t+1+h]×(1−滑点)×(1−佣金−印花税)；"
                    + "两者差为费率的二阶小量，但成本量级一律以乘性实现为准（见 ResearchConvention 与 docs/研究口径规范.md）";
    private static final String RANK_IC_EMBARGO_NOTE =
            "信号日接近数据末端（forward 区间未形成）自动跳过，不外推、不使用未来数据；"
                    + "重叠持有期 IC 序列的显著性以 HAC 标准误与 block bootstrap CI 为准；"
                    + "**估计量边界**：HAC 滞后阶只由序列长度决定（lag = max(1, min(floor(n^(1/3)), n-1))），"
                    + "与持有期 h **结构上解耦**；bootstrap 有效块长 = min(h, floor(√n), n−1)，n 小时有效块长 < h；"
                    + "区间为**名义** 95% 水平，未做多重检验校正，重叠持有期下实测覆盖率可能显著低于 95%";
    /**
     * 统计量构造规则声明（Q-R006-02 / Q-R014-01 / Q-R014-09 披露项，**只声明不改数值**）。
     */
    private static final String RANK_IC_STD_BASIS = "POPULATION_STD_DIV_T";
    private static final String RANK_IC_STD_BASIS_NOTE =
            "rank_ic_std / rank_ic_ir 的分母为**总体标准差 ÷ T**，非样本标准差 ÷(T−1)；"
                    + "两种约定的比值恒为 sqrt(T/(T−1))（T=5 时约 +11.8%）。本键只声明现状，未改任何数值；"
                    + "rank_ic_ir 在 std = 0（含 T < 2）时无定义，序列化为 null（0 的语义是「IR 恰为 0」）";
    /**
     * 并列披露的样本标准差口径（Q-R006-02）。主口径 {@link #RANK_IC_STD_BASIS} 不改，
     * 本约定只作为并列键 {@code rank_ic_std_sample} / {@code rank_ic_ir_sample} 的分母声明，
     * 供读者在重叠持有期下按自由度修正复核；两口径比值恒为 sqrt(T/(T−1))。
     */
    private static final String RANK_IC_STD_SAMPLE_BASIS = "SAMPLE_STD_DIV_T_MINUS_1";
    private static final String RANK_IC_STD_SAMPLE_NOTE =
            "并列键 rank_ic_std_sample / rank_ic_ir_sample 的分母为**样本标准差 ÷(T−1)**"
                    + "（无偏估计量），用于与主口径对照；T < 2 或值非有限时序列化为 null（无定义，不等于 0）；"
                    + "rank_ic_std_sample_exact 给出未取整值";
    private static final String GROUP_BOUNDARY_RULE = "INDEX_EQUAL_SPLIT_STABLE_SORT";
    private static final String GROUP_BOUNDARY_RULE_NOTE =
            "分桶规则：组内按 score 升序**稳定排序**（并列项保持输入行序）+ 下标等分 bucket = min(g−1, i·g/n)；"
                    + "并列 score 的相对次序**不作保证**（取决于上游查询返回的行序），故分组收益在并列行序变化时"
                    + "可能微幅改变（rank_ic 不受影响）；载荷同时给出未取整的 *_exact 值以便复核标签是否可重建";
    private static final String ROUNDING_RULE = "ROUND_HALF_UP_4DP";
    private static final String ROUNDING_RULE_NOTE =
            "载荷数值为 Math.round(v×10000)/10000 的四舍五入（4 位小数）；同名的 *_exact 键给出未取整原值；"
                    + "非有限值（NaN/±Inf）一律序列化为 **null** —— 原实现会把 NaN 写成 0.0、把 ±Inf 写成 "
                    + "9.223372036854776E14，把「不可定义」伪装成具体数";
    private static final String LONG_SHORT_SPREAD_BASIS =
            "TWO_LONG_LEG_NET_DIFFERENCE";
    private static final String LONG_SHORT_SPREAD_BASIS_NOTE =
            "long_short_spread = 最高分组均值 − 最低分组均值，是**两个多头腿净收益之差**，"
                    + "**不是**美元中性多空（本字段名沿用历史契约）：成本在两腿差分中结构上抵消，"
                    + "故该值不承担空头腿成本、不减基准、不减无风险利率；按论文口径的真双腿美元中性理解会"
                    + "系统性高估成本承受力（样本外符号可能翻转）。只读披露，未改字段语义与数值";
    /**
     * 两腿价差的**成本基准**（Q-R003-01 / Q-R004-01）：同名 {@code long_short_spread}
     * 在不同口径块里取自不同收益序列——本键把「是否已扣费」变成可读事实。
     */
    static final String SPREAD_COST_BASIS_GROSS = "GROSS_BEFORE_COSTS";
    static final String SPREAD_COST_BASIS_NET = "NET_AFTER_MODELED_COSTS";
    static final String SPREAD_COST_BASIS_NONE = "NOT_APPLICABLE_NO_OBSERVATIONS";
    private static final String SPREAD_COST_BASIS_NOTE =
            "GROSS_BEFORE_COSTS：本块收益为价格直接算出的毛收益（qfq/raw close-to-close 或 next-open→close），"
                    + "**未扣任何费用** ⟹ 美元中性口径需再减 2×单腿往返成本；"
                    + "NET_AFTER_MODELED_COSTS：本块收益已按冻结费率逐腿扣费 ⟹ "
                    + "块内 long_short_spread **本身已是真双腿美元中性净价差**，不得再减一次（否则双重扣费）；"
                    + "NOT_APPLICABLE_NO_OBSERVATIONS：本块无观测，两种口径都无定义";
    private static final String NEUTRAL_SPREAD_BASIS_GROSS_MINUS_TWO_LEGS = "GROSS_SPREAD_MINUS_TWO_LEG_ROUND_TRIPS";
    private static final String NEUTRAL_SPREAD_BASIS_ALREADY_NET = "NOT_APPLICABLE_ALREADY_NET_OF_COSTS";
    private static final String NEUTRAL_SPREAD_NOTE =
            "long_short_spread_neutral = 真双腿（多最高分组 + 空最低分组）美元中性净价差："
                    + "毛价差 − 2×单腿往返成本。**与 long_short_spread 并列**，不替换它；"
                    + "两者之差 = 两腿成本（冻结费率下约 30.0bp per holding period，见 cost_per_leg_round_trip）。"
                    + "注意 A 股不能融券做空，本值只用于**按论文口径评估成本承受力**，不是可实现的策略收益";
    private static final String PER_LEG_COST_BASIS = "MULTIPLICATIVE_TWO_SIDED";
    private static final String PER_LEG_COST_NOTE =
            "cost_per_leg_round_trip = 1 − (1−滑点)(1−佣金−印花税)/((1+滑点)(1+佣金))，"
                    + "与冻结口径的乘性实现同构（佣金万三 / 印花税万五 / 单边滑点万二）⟹ 约 15.0bp/腿。"
                    + "**记法陷阱**：常见的加法式 12bp（2×滑点+佣金+印花税）漏掉卖出腿佣金，"
                    + "与实现差 −3.00bp（主项 = −佣金）；因此「两腿扣 24bp」与「扣 30bp」两种候选读法的分歧"
                    + "不是建模选择，而是加法式/乘性之差（同 D-R010-01 的公式勘误）";
    private static final String PEARSON_IC_MEAN_NOTE =
            "pearson_ic_mean = 线性（Pearson）IC 的日均值；无任何 Pearson 支撑日时为 **null**"
                    + "（见 pearson_support_days），不再写 0.0——0 是合法的 IC 取值，"
                    + "「线性 IC 恰为 0」与「线性 IC 不可定义」不可混同（Q-R009-02）。"
                    + "另注：秩相关有限**不蕴含** Pearson 有限（小量级输入下秩侧有限而 Pearson 为 NaN，"
                    + "Q-R009-03），故两个支撑日数可以不同";
    private static final String PRECISION_POLICY = "PAYLOAD_ROUNDED_4DP_WITH_EXACT_COMPANION";
    private static final String PRECISION_POLICY_NOTE =
            "两面取整策略（Q-R014-08）：**本载荷**的标量指标取 4 位小数，并在同键名后加 `_exact` 给出未取整原值"
                    + "（逐日行原本已有 rank_ic_exact / avg_return_exact）；**DTO 面**（ScoringBacktestResponse）"
                    + "承载**未取整**原值。⟹ 同一量在两面的差异可逐键复核：载荷 `X_exact` 必须与 DTO 的 `X` 逐位相等；"
                    + "展示层负责按需取整。取整函数与位数见 rounding_rule";
    private static final String RANK_IC_SAMPLE_STD_NOTE =
            "并列披露（Q-R006-02，**不改主口径**）：rank_ic_std_sample = 样本标准差 ÷(T−1)、"
                    + "rank_ic_ir_sample = rank_ic_mean / rank_ic_std_sample。"
                    + "主口径 rank_ic_std / rank_ic_ir 仍为总体标准差 ÷T（rank_ic_std_basis）；"
                    + "两者比值恒为 sqrt(T/(T−1))（T=5 时约 +11.8%，即 ÷(T−1) 侧系统性更大）。"
                    + "本组键只让另一条约定可被直接复核，未改任何既有数值；无定义（T<2 或无离散度）时为 null";
    private static final String KEY_ALIAS_NOTE =
            "键别名（同一变量两个键名，为向后兼容保留，**不是**两个量）："
                    + "feature_valid_count ≡ valid_observations（同源变量 validObservations）；"
                    + "selector_item_count ≡ total_observations（同源变量 totalObservations）";
    private static final String METRIC_SCOPE_INHERITED_NOTE =
            "本块 metric_scope 是**家族继承值**（无条件写 COST_APPROX），块内并**无**成本口径数值："
                    + "coverage / 各计数字段是**样本覆盖口径**（候选行数、有效观测数），不得按成本近似解读。"
                    + "同名 metric_scope 在不同面上是不同语义面（顶层与 rank_ic 块的 RAW 指价格基准，"
                    + "snapshot funnel 块另有成本近似计数）。只读声明，未改取值域";
    private static final String MIN_CROSS_SECTION_SIZE_BASIS = "INPUT_ROWS";
    /**
     * Q-R014-03：聚合面 {@code grouped_returns[].observations} 的单位声明（**只声明不改数值**）。
     */
    private static final String GROUPED_RETURN_OBSERVATIONS_BASIS = "DAY_LEVEL_GROUP_MEANS_PER_GROUP";
    private static final String GROUPED_RETURN_OBSERVATIONS_BASIS_NOTE =
            "grouped_returns[].observations = 参与该组均值聚合的**截面日数**（= 组内已聚合的日级组均值个数），"
                    + "不是成分股数；日级分组的成分股数在引擎内部产生，不经本载荷发射。"
                    + "同名字段在别的上下文可能指成分股数，引用前须先看本键；只读披露，未改字段语义与数值";
    private static final String PRICE_BASIS_QFQ = "QFQ_CLOSE";
    private static final String PRICE_BASIS_RAW = "RAW_CLOSE";
    private static final String STATUS_OK = "OK";
    private static final String STATUS_NO_OBSERVATIONS = "NO_OBSERVATIONS";
    private static final String METRIC_SCOPE_RAW = "RAW";
    private static final String METRIC_SCOPE_COST_APPROX = "COST_APPROX";
    private static final String METRIC_SCOPE_EXECUTABLE = "EXECUTABLE";
    private static final String SOURCE_FULL_SNAPSHOT = "FULL_FEATURE_SNAPSHOT";
    private static final String SOURCE_LEGACY_SELECTION_BIASED = "LEGACY_SELECTION_BIASED";
    private static final String SNAPSHOT_UNIVERSE_NOTE =
            "完整 PIT feature snapshot 全截面（V2-MP-016，topN=0 无上限），非 selector TopN 子集；"
                    + "forward return 只读 market_price_raw / label_v2，不读 selector/qfq";
    private static final String FEATURE_SNAPSHOT_NOTE =
            "完整 PIT feature snapshot IC 请用 snapshot-research（V2-MP-022，输入 snapshotId 读取 MP-016 全截面）；"
                    + "本路径沿用 sparse selector_result，标注 LEGACY_SELECTION_BIASED";
    private static final List<Integer> UNIFIED_HORIZONS = List.of(1, 2, 3, 5);
    private static final int UNIFIED_MAX_HORIZON = 5;

    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final FeatureSnapshotManifestRepository featureSnapshotManifestRepository;
    private final FeatureSnapshotRowRepository featureSnapshotRowRepository;
    private final LabelSnapshotRepository labelSnapshotRepository;
    private final FeatureSchemaRegistry featureSchemaRegistry;
    private final DataSnapshotService dataSnapshotService;

    @Transactional(readOnly = true)
    public Map<String, Object> research(String strategy, LocalDate startDate, LocalDate endDate) {
        return research(strategy, startDate, endDate, DEFAULT_RANK_IC_HOLDING_DAYS);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> research(String strategy, LocalDate startDate, LocalDate endDate, Integer holdingDays) {
        int holding = resolveHoldingDays(holdingDays);
        List<SelectorResult> results = selectorResultRepository
                .findByTradeDateBetweenOrderByCreatedAtDesc(startDate, endDate);
        if (strategy != null && !strategy.isBlank()) {
            final String f = strategy;
            results = results.stream().filter(r -> f.equals(r.getStrategyType())).toList();
        }
        // 同策略同日取最新
        Map<String, SelectorResult> latestPerKey = new LinkedHashMap<>();
        for (SelectorResult r : results) {
            latestPerKey.putIfAbsent(r.getStrategyType() + "|" + r.getTradeDate(), r);
        }

        List<String> resultIds = latestPerKey.values().stream().map(SelectorResult::getResultId).toList();
        List<SelectorResultItem> allItems = resultIds.isEmpty()
                ? List.of()
                : selectorResultItemRepository.findByResultIdInOrderByRankNo(resultIds);

        List<Double> scores = new ArrayList<>();
        int validCount = 0;
        Map<String, List<SelectorResultItem>> itemsByResultId = new LinkedHashMap<>();
        for (SelectorResultItem it : allItems) {
            itemsByResultId.computeIfAbsent(it.getResultId(), k -> new ArrayList<>()).add(it);
            if (it.getFinalScore() != null && it.getFinalScore() > 0) {
                scores.add(it.getFinalScore());
                validCount++;
            }
        }
        Collections.sort(scores);

        // 行情一次性预加载（close+open），供 Rank IC 复用，避免逐股/逐持仓期重复查询
        LocalDate calEnd = endDate.plusDays(holding * 2L);
        PriceCache prices = loadPriceCache(itemsByResultId, startDate, calEnd);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", SOURCE_LEGACY_SELECTION_BIASED);
        out.put("strategy", strategy != null ? strategy : "ALL");
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());
        out.put("cross_section_days", latestPerKey.size());
        out.put("total_observations", allItems.size());
        out.put("valid_observations", validCount);
        out.put("coverage", allItems.isEmpty() ? 0.0
                : Math.round(validCount * 10000.0 / allItems.size()) / 100.0);
        out.put("distribution", buildDistribution(scores));
        out.put("group_bands", buildGroupBands(scores, 5));
        out.put("rank_ic", buildRankIc(latestPerKey, itemsByResultId, startDate, endDate, holding, prices));
        out.put("funnel", buildResearchFunnel(latestPerKey, allItems.size(), validCount));
        out.put("metric_scopes", List.of(METRIC_SCOPE_RAW, METRIC_SCOPE_COST_APPROX));
        out.put("metric_scope", METRIC_SCOPE_COST_APPROX);
        out.put("executable_metric_scope", "UNAVAILABLE");
        out.put("feature_snapshot_note", FEATURE_SNAPSHOT_NOTE);
        out.put("universe_note", RANK_IC_UNIVERSE_NOTE);
        out.put("note", "仅作因子研究评价（覆盖率/分布/分组/Rank IC）；毛收益=RAW、净收益=COST_APPROX；"
                + "EXECUTABLE 需成交规则过滤，当前不可用；禁止据此直接调整因子权重");
        return out;
    }

    /**
     * QP-010：统一因子研究报告——对 T+1/T+2/T+3/T+5 持有期并列输出
     * Pearson IC / Rank IC / ICIR / 正 IC 比例 / 分组单调性 / Top-bottom spread。
     *
     * <p>合并因子研究页与回测的 Rank IC 为统一报告，供统计研究基线评价。
     * 每个 holding 独立计算 forward return，不混用不同持有期。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> unifiedReport(String strategy, LocalDate startDate, LocalDate endDate) {
        // 复用 research 的数据加载逻辑，对每个 holding 调用 buildRankIc
        List<SelectorResult> results = selectorResultRepository
                .findByTradeDateBetweenOrderByCreatedAtDesc(startDate, endDate);
        if (strategy != null && !strategy.isBlank()) {
            final String f = strategy;
            results = results.stream().filter(r -> f.equals(r.getStrategyType())).toList();
        }
        Map<String, SelectorResult> latestPerKey = new LinkedHashMap<>();
        for (SelectorResult r : results) {
            latestPerKey.putIfAbsent(r.getStrategyType() + "|" + r.getTradeDate(), r);
        }
        List<String> resultIds = latestPerKey.values().stream().map(SelectorResult::getResultId).toList();
        List<SelectorResultItem> allItems = resultIds.isEmpty()
                ? List.of()
                : selectorResultItemRepository.findByResultIdInOrderByRankNo(resultIds);
        Map<String, List<SelectorResultItem>> itemsByResultId = new LinkedHashMap<>();
        for (SelectorResultItem it : allItems) {
            itemsByResultId.computeIfAbsent(it.getResultId(), k -> new ArrayList<>()).add(it);
        }

        // 行情按最大持仓期一次性预加载，供所有 holding 复用（消除 4× 重复拉取）
        LocalDate calEnd = endDate.plusDays(UNIFIED_MAX_HORIZON * 2L);
        PriceCache prices = loadPriceCache(itemsByResultId, startDate, calEnd);

        List<Map<String, Object>> byHorizon = new ArrayList<>();
        for (int h : UNIFIED_HORIZONS) {
            byHorizon.add(buildRankIc(latestPerKey, itemsByResultId, startDate, endDate, h, prices));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", SOURCE_LEGACY_SELECTION_BIASED);
        out.put("strategy", strategy != null ? strategy : "ALL");
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());
        out.put("horizons", UNIFIED_HORIZONS);
        out.put("by_horizon", byHorizon);
        out.put("convention", ResearchConvention.labelDefinition());
        out.put("funnel", buildResearchFunnel(latestPerKey, allItems.size(),
                (int) allItems.stream().filter(it -> it.getFinalScore() != null && it.getFinalScore() > 0).count()));
        out.put("metric_scopes", List.of(METRIC_SCOPE_RAW, METRIC_SCOPE_COST_APPROX));
        out.put("metric_scope", METRIC_SCOPE_COST_APPROX);
        out.put("executable_metric_scope", "UNAVAILABLE");
        out.put("feature_snapshot_note", FEATURE_SNAPSHOT_NOTE);
        out.put("universe_note", RANK_IC_UNIVERSE_NOTE);
        out.put("note", "统一因子研究报告：Pearson/Rank IC 为 RAW；净收益 IC 为 COST_APPROX（成本后近似，不含涨跌停/停牌拒单）；"
                + "EXECUTABLE 暂不可用；sparse selector_result 存在选择偏差；禁止据此直接调整因子权重");
        return out;
    }

    /**
     * V2-MP-022：完整 PIT 因子截面与 raw forward return IC（权威路径）。
     *
     * <p>输入 MP-016 feature snapshot（feature_snapshot_row 全截面，topN=0），不读 selector_result、不读 qfq。
     * forward return 分三口径：
     * <ul>
     *   <li><b>RAW</b>：rawClose[t+h]/rawClose[t]-1（market_price_raw 不复权，含分红/除权缺口，仅方向预测力评价）；</li>
     *   <li><b>COST_APPROX</b>：rawOpen[t+1]×(1+佣金+滑点) 入场 → rawClose[t+1+h]×(1-佣金-印花税-滑点) 退出，
     *       无涨跌停/停牌拒单过滤，不得称为完整可执行 IC；</li>
     *   <li><b>EXECUTABLE</b>：join label_v2（label_snapshot）execStatus=FILLED 取 netReturn（统一执行政策产物），
     *       仅 FILLED 样本计入；NOT_BUYABLE/SUSPENDED/NOT_SELLABLE/DELISTED 分别计入拒绝漏斗。</li>
     * </ul>
     *
     * <p>交易日均值由实读 market_price_raw 日期构造，不借用 MarketData/qfq 兜底；
     * schema 由 {@link FeatureSchemaRegistry} 代码派生，禁止调用方自报版本。
     *
     * @param strategy       CLASSIC_SELECTOR / OVERSOLD_REBOUND（FeatureSchemaRegistry 已注册策略）
     * @param snapshotId     MP-016 数据快照 ID（须已存在）
     * @param startDate      信号日起始（含）
     * @param endDate        信号日截止（含）
     * @param holdingDays    持仓交易日数（1~60）
     * @param labelVersion   EXECUTABLE 口径所需 label_v2 版本（可为空，空则 executable 不可用）
     * @param featureVersion 可选特征版本；空则用注册的 feature_matrix_v1
     */
    @Transactional(readOnly = true)
    public Map<String, Object> snapshotResearch(
            String strategy, String snapshotId, LocalDate startDate, LocalDate endDate,
            Integer holdingDays, String labelVersion, String featureVersion) {
        int holding = resolveHoldingDays(holdingDays);
        String strategyName = normalizeSnapshotStrategy(strategy);
        String snapId = snapshotId == null ? "" : snapshotId.trim();
        if (snapId.isBlank()) {
            throw new IllegalArgumentException("snapshotId 必填");
        }
        dataSnapshotService.requireSnapshot(snapId);
        FeatureSchemaRegistry.FeatureSet feature = featureSchemaRegistry
                .resolveFeatureVersion(featureVersion, strategyName);
        String featureVer = feature.version();

        // 1) 读取区间内可复用分区（VERIFIED/PUBLISHED/OK/SUMMARY；FAILED/RUNNING 不参与研究）
        List<FeatureSnapshotManifest> manifests = featureSnapshotManifestRepository
                .findBySnapshotIdAndStrategyVersionAndFeatureSetVersionAndSignalDateBetweenOrderBySignalDateAsc(
                        snapId, strategyName, featureVer, startDate, endDate);
        List<FeatureSnapshotManifest> reusable = new ArrayList<>();
        List<String> skippedDates = new ArrayList<>();
        for (FeatureSnapshotManifest m : manifests) {
            if (isReusableManifest(m)) {
                reusable.add(m);
            } else {
                skippedDates.add(m.getSignalDate().toString() + "(" + m.getStatus() + ")");
            }
        }
        Map<LocalDate, List<FeatureSnapshotRow>> rowsByDate = new LinkedHashMap<>();
        int rowsTotal = 0;
        for (FeatureSnapshotManifest m : reusable) {
            List<FeatureSnapshotRow> rows = featureSnapshotRowRepository.listPartition(
                    snapId, m.getSignalDate(), strategyName, featureVer);
            rowsByDate.put(m.getSignalDate(), rows);
            rowsTotal += rows.size();
        }

        // 2) EXECUTABLE 口径：label_v2 全部执行状态 join（不删除拒单，保留每类 reject）
        Map<String, LabelSnapshot> labelByKey = new HashMap<>();
        Map<String, Object> execBreakdown = new LinkedHashMap<>();
        for (String s : List.of(ResearchConvention.EXEC_STATUS_FILLED, ResearchConvention.EXEC_STATUS_NOT_BUYABLE,
                ResearchConvention.EXEC_STATUS_SUSPENDED, ResearchConvention.EXEC_STATUS_NOT_SELLABLE,
                ResearchConvention.EXEC_STATUS_DELISTED)) {
            execBreakdown.put(s, 0);
        }
        boolean executableAvailable = labelVersion != null && !labelVersion.isBlank();
        if (executableAvailable) {
            List<LabelSnapshot> labels = labelSnapshotRepository
                    .findByLabelVersionAndHorizonAndSignalDateBetween(labelVersion, holding, startDate, endDate);
            for (LabelSnapshot l : labels) {
                labelByKey.put(labelKey(l.getSignalDate(), l.getStockCode(), l.getExchangeType()), l);
            }
        }

        // 3) raw 行情预加载（只读 market_price_raw；日序由实读 raw 日期构造，不读 qfq/MarketData）
        LocalDate calEnd = endDate.plusDays(holding * 2L);
        SnapshotPriceCache prices = loadRawPriceCache(startDate, calEnd);

        // 4) 逐信号日构建三口径截面观测
        Map<String, List<CrossSectionObservation>> rawObsByDate = new LinkedHashMap<>();
        Map<String, List<CrossSectionObservation>> costObsByDate = new LinkedHashMap<>();
        Map<String, List<CrossSectionObservation>> execObsByDate = new LinkedHashMap<>();
        List<Map<String, Object>> dailyFunnel = new ArrayList<>();
        int scoredValid = 0;
        for (Map.Entry<LocalDate, List<FeatureSnapshotRow>> e : rowsByDate.entrySet()) {
            LocalDate signalDate = e.getKey();
            Integer baseIdx = prices.dayIndex().get(signalDate);
            List<CrossSectionObservation> rawObs = new ArrayList<>();
            List<CrossSectionObservation> costObs = new ArrayList<>();
            List<CrossSectionObservation> execObs = new ArrayList<>();
            int validRows = 0;
            int labelsMatched = 0;
            int labelsMissing = 0;
            int rawCount = 0;
            int costCount = 0;
            int execCount = 0;
            for (FeatureSnapshotRow row : e.getValue()) {
                Double score = row.getFinalScore();
                if (score == null || score <= 0) {
                    continue;
                }
                validRows++;
                String stockKey = row.getStockCode() + "|" + row.getExchangeType();
                // RAW：rawClose[t+h]/rawClose[t]-1
                if (baseIdx != null && baseIdx + holding < prices.days().size()) {
                    LocalDate exitDate = prices.days().get(baseIdx + holding);
                    BigDecimal closeSignal = prices.close().get(stockKey + "|" + signalDate);
                    BigDecimal closeExit = prices.close().get(stockKey + "|" + exitDate);
                    if (closeSignal != null && closeExit != null
                            && closeSignal.doubleValue() > 0 && closeExit.doubleValue() > 0) {
                        rawObs.add(new CrossSectionObservation(
                                score, closeExit.doubleValue() / closeSignal.doubleValue() - 1.0));
                        rawCount++;
                    }
                }
                // COST_APPROX：rawOpen[t+1]×(1+滑点)×(1+佣金) 入场 → rawClose[t+1+h]×(1−滑点)×(1−佣金−印花税) 退出
                // （乘性两级，与 ResearchConvention / LabelFactory 成交口径一致；原注释把滑点与卖出印花税
                //  共用同一个符号 s，且写成加性式，与实现不符 —— Q-R010-03）
                if (baseIdx != null && baseIdx + 1 + holding < prices.days().size()) {
                    LocalDate entryDate = prices.days().get(baseIdx + 1);
                    LocalDate exitDate = prices.days().get(baseIdx + 1 + holding);
                    BigDecimal openEntry = prices.open().get(stockKey + "|" + entryDate);
                    BigDecimal closeExit = prices.close().get(stockKey + "|" + exitDate);
                    if (openEntry != null && closeExit != null
                            && openEntry.doubleValue() > 0 && closeExit.doubleValue() > 0) {
                        double entryPrice = openEntry.doubleValue()
                                * (1 + ResearchConvention.DEFAULT_SLIPPAGE_RATE);
                        double entryCost = entryPrice * (1 + ResearchConvention.DEFAULT_COMMISSION_RATE);
                        double exitPrice = closeExit.doubleValue()
                                * (1 - ResearchConvention.DEFAULT_SLIPPAGE_RATE);
                        double exitProceeds = exitPrice
                                * (1 - ResearchConvention.DEFAULT_COMMISSION_RATE - ResearchConvention.DEFAULT_SELL_TAX_RATE);
                        costObs.add(new CrossSectionObservation(score, exitProceeds / entryCost - 1.0));
                        costCount++;
                    }
                }
                // EXECUTABLE：仅 FILLED 标签，取 label.netReturn（统一执行政策产物）
                LabelSnapshot label = executableAvailable
                        ? labelByKey.get(labelKey(signalDate, row.getStockCode(), row.getExchangeType()))
                        : null;
                if (label != null) {
                    labelsMatched++;
                    incrementCount(execBreakdown, label.getExecStatus());
                    if (ResearchConvention.EXEC_STATUS_FILLED.equals(label.getExecStatus())
                            && label.getNetReturn() != null) {
                        execObs.add(new CrossSectionObservation(score, label.getNetReturn().doubleValue()));
                        execCount++;
                    }
                } else {
                    labelsMissing++;
                }
            }
            scoredValid += validRows;
            if (!rawObs.isEmpty()) {
                rawObsByDate.put(signalDate.toString(), rawObs);
            }
            if (!costObs.isEmpty()) {
                costObsByDate.put(signalDate.toString(), costObs);
            }
            if (!execObs.isEmpty()) {
                execObsByDate.put(signalDate.toString(), execObs);
            }
            Map<String, Object> daily = new LinkedHashMap<>();
            daily.put("signal_date", signalDate.toString());
            daily.put("rows_total", e.getValue().size());
            daily.put("scored_valid", validRows);
            daily.put("raw_observations", rawCount);
            daily.put("cost_approx_observations", costCount);
            daily.put("executable_observations", execCount);
            daily.put("labels_matched", labelsMatched);
            daily.put("labels_missing", labelsMissing);
            dailyFunnel.add(daily);
        }

        // 5) 三口径分别汇总（口径边界诚实标注）
        String rawNote = "RAW：rawClose[t+h]/rawClose[t]-1，只读 market_price_raw 不复权（含分红/除权缺口），"
                + "不计费用/滑点/涨跌停可成交性；全截面来自 feature_snapshot_row（V2-MP-016），不读 selector/qfq；"
                + "仅方向预测力评价，禁止据此直接调整因子权重";
        String costNote = "COST_APPROX：next-open 入场 + 持有期收盘退出 + 扣费（ResearchConvention），成交价只读 market_price_raw；"
                + "不含涨跌停/停牌拒单过滤，不得称为完整可执行 IC";
        String execNote = executableAvailable
                ? "EXECUTABLE：join label_v2（label_snapshot）execStatus=FILLED，forward return = label.netReturn（"
                + "入场 next-open×成本、出场持有期收盘×扣费，按统一执行政策）；NOT_BUYABLE/SUSPENDED/NOT_SELLABLE/DELISTED"
                + " 不计入可执行 IC，分别计入拒绝漏斗"
                : "EXECUTABLE：未提供 labelVersion，无法 join label_v2 执行状态，口径不可用";

        Map<String, Object> scopes = new LinkedHashMap<>();
        scopes.put(METRIC_SCOPE_RAW,
                buildSnapshotScopeBlock(METRIC_SCOPE_RAW, rawNote, holding, rawObsByDate));
        scopes.put(METRIC_SCOPE_COST_APPROX,
                buildSnapshotScopeBlock(METRIC_SCOPE_COST_APPROX, costNote, holding, costObsByDate));
        scopes.put(METRIC_SCOPE_EXECUTABLE,
                executableAvailable
                        ? buildSnapshotScopeBlock(METRIC_SCOPE_EXECUTABLE, execNote, holding, execObsByDate)
                        : unavailableScopeBlock(METRIC_SCOPE_EXECUTABLE, execNote));

        // 6) 响应：必含 snapshot/schema/label/metricScope/sample funnel
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", SOURCE_FULL_SNAPSHOT);
        out.put("snapshot_id", snapId);
        out.put("strategy", strategyName);
        out.put("feature_version", featureVer);
        out.put("schema_hash", feature.schemaHash());
        out.put("label_version", executableAvailable ? labelVersion : null);
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());
        out.put("holding_days", holding);
        out.put("universe_source", "feature_snapshot_row");
        out.put("cross_section_days", rowsByDate.size());
        out.put("partitions_reusable", reusable.size());
        out.put("partitions_skipped", skippedDates);
        out.put("rows_total", rowsTotal);
        out.put("scored_valid", scoredValid);
        out.put("coverage", rowsTotal == 0 ? 0.0
                : Math.round(scoredValid * 10000.0 / rowsTotal) / 100.0);
        out.put("metric_scopes", executableAvailable
                ? List.of(METRIC_SCOPE_RAW, METRIC_SCOPE_COST_APPROX, METRIC_SCOPE_EXECUTABLE)
                : List.of(METRIC_SCOPE_RAW, METRIC_SCOPE_COST_APPROX));
        out.put("executable_metric_scope", executableAvailable ? METRIC_SCOPE_EXECUTABLE : "UNAVAILABLE");
        out.put("funnel", buildSnapshotFunnel(rowsTotal, scoredValid, rowsByDate.size(), reusable.size(),
                skippedDates, execBreakdown, executableAvailable, labelVersion));
        out.put("daily_funnel", dailyFunnel);
        out.put("scopes", scopes);
        out.put("note", "V2-MP-022 权威路径：完整 PIT feature snapshot 全截面 IC；EXECUTABLE 仅对 FILLED 样本；"
                + "不读 selector_result / 不读 qfq；禁止把 COST_APPROX 命名 executable");
        return out;
    }

    private static boolean isReusableManifest(FeatureSnapshotManifest m) {
        String s = m.getStatus();
        return FeatureSnapshotManifest.STATUS_VERIFIED.equals(s)
                || FeatureSnapshotManifest.STATUS_PUBLISHED.equals(s)
                || FeatureSnapshotManifest.STATUS_OK.equals(s)
                || FeatureSnapshotManifest.STATUS_SUMMARY.equals(s);
    }

    private static String normalizeSnapshotStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            throw new IllegalArgumentException("strategy 必填：CLASSIC_SELECTOR|OVERSOLD_REBOUND");
        }
        return UnifiedScoringStrategy.parse(strategy).name();
    }

    private static String labelKey(LocalDate signalDate, String stockCode, String exchangeType) {
        return signalDate + "|" + stockCode + "|" + exchangeType;
    }

    private static void incrementCount(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Integer i) {
            m.put(key, i + 1);
        } else {
            m.put(key, 1);
        }
    }

    /** 快照路径行情缓存：只读 market_price_raw（不复权），日序由实读 raw 日期构造（升序）。 */
    private record SnapshotPriceCache(
            Map<LocalDate, Integer> dayIndex,
            List<LocalDate> days,
            Map<String, BigDecimal> close,
            Map<String, BigDecimal> open) {}

    private SnapshotPriceCache loadRawPriceCache(LocalDate calStart, LocalDate calEnd) {
        List<MarketPriceRaw> allRaw = marketPriceRawRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(calStart, calEnd);
        LinkedHashSet<LocalDate> daySet = new LinkedHashSet<>();
        Map<String, BigDecimal> close = new HashMap<>();
        Map<String, BigDecimal> open = new HashMap<>();
        if (allRaw != null) {
            for (MarketPriceRaw raw : allRaw) {
                daySet.add(raw.getTradeDate());
                String key = raw.getStockCode() + "|" + raw.getExchangeType() + "|" + raw.getTradeDate();
                if (raw.getClosePrice() != null) {
                    close.put(key, raw.getClosePrice());
                }
                if (raw.getOpenPrice() != null) {
                    open.put(key, raw.getOpenPrice());
                }
            }
        }
        List<LocalDate> days = new ArrayList<>(daySet);
        Collections.sort(days);
        Map<LocalDate, Integer> dayIndex = new HashMap<>();
        for (int i = 0; i < days.size(); i++) {
            dayIndex.put(days.get(i), i);
        }
        return new SnapshotPriceCache(dayIndex, days, close, open);
    }

    /**
     * 三口径族共用的**标准数值键集**：保证「键集不随数据可用性变化」，消费方可静态声明类型
     * （Q-R007-03 / Q-R009-05 / Q-R009-06 修复）。键名、语义与数值口径均沿用既有定义。
     */
    private static void putSummaryMetricKeys(Map<String, Object> block, FactorResearchSummary s) {
        block.put("cross_section_days", s.crossSectionDays());
        block.put("total_observations", s.totalObservations());
        block.put("rank_ic_mean", round4(s.rankIcMean()));
        block.put("rank_ic_std", round4(s.rankIcStd()));
        block.put("rank_ic_ir", round4(s.rankIcIr()));
        block.put("rank_ic_positive_rate", round4(s.rankIcPositiveRate()));
        block.put("pearson_ic_mean", round4(s.pearsonIcMean()));
        block.put("monotonicity", s.monotonicity());
        block.put("hac_std", round4(s.hacStd()));
        block.put("ci_lower", round4(s.ciLower()));
        block.put("ci_upper", round4(s.ciUpper()));
        block.put("long_short_spread", round4(s.longShortSpread()));
        block.put("daily_rank_ic", dailyIcList(s));
        block.put("grouped_returns", groupedReturnList(s));
        // 未取整并列键（Q-R014-08）：使「载荷 4dp 值 + DTO 原值」两面可逐位复核，展示层负责取整
        block.put("rank_ic_mean_exact", s.rankIcMean());
        block.put("rank_ic_std_exact", s.rankIcStd());
        block.put("rank_ic_ir_exact", s.rankIcIr());
        block.put("rank_ic_positive_rate_exact", s.rankIcPositiveRate());
        block.put("pearson_ic_mean_exact", s.pearsonIcMean());
        block.put("hac_std_exact", s.hacStd());
        block.put("ci_lower_exact", s.ciLower());
        block.put("ci_upper_exact", s.ciUpper());
        block.put("long_short_spread_exact", s.longShortSpread());
        // 并列披露 ÷(T−1) 那条分母约定（Q-R006-02）：主口径 ÷T 未动，本组只让另一读法可复核
        Double sampleStd = FactorResearchEngine.sampleStdDivTMinusOne(dailyIcSeries(s));
        block.put("rank_ic_std_sample", round4(sampleStd));
        // 注意：不可写成 `sampleStd == null ? (Double) null : mean / sampleStd`——
        // 三元表达式一支是 double、一支是 Double 时结果类型退化为 double，null 会被拆箱成 NPE。
        // rankIcMean 亦为包装类型：无可用截面日时它本身即 null，此时样本口径 ICIR 同样无定义。
        Double sampleIr = (sampleStd == null || sampleStd == 0 || s.rankIcMean() == null)
                ? null
                : s.rankIcMean() / sampleStd;
        block.put("rank_ic_ir_sample", round4(sampleIr));
        block.put("rank_ic_std_sample_exact", sampleStd);
    }

    private static List<Double> dailyIcSeries(FactorResearchSummary s) {
        return s.dailyRankIc().stream().map(DailyRankIc::rankIc).toList();
    }

    private static void putSummaryDisclosure(Map<String, Object> block, FactorResearchSummary s, String priceBasis) {
        putSummaryDisclosure(block, s, priceBasis, SPREAD_COST_BASIS_GROSS);
    }

    /**
     * 三口径族共用的**披露键**（纯增量）：样本漏斗计数、统计量构造规则、字段口径边界。
     * 不改任何既有键的名字/语义/数值。
     *
     * @param spreadCostBasis 本块的收益序列是否已扣费（{@link #SPREAD_COST_BASIS_GROSS} /
     *                        {@link #SPREAD_COST_BASIS_NET} / {@link #SPREAD_COST_BASIS_NONE}）：
     *                        决定 {@code long_short_spread_neutral} 是「毛价差 − 2×单腿成本」
     *                        还是「不适用（本块已是净价差）」（Q-R003-01 / Q-R004-01）
     */
    private static void putSummaryDisclosure(
            Map<String, Object> block, FactorResearchSummary s, String priceBasis, String spreadCostBasis) {
        block.put("price_basis", priceBasis);
        block.put("group_count", s.groupCount());
        block.put("min_cross_section_size", RANK_IC_MIN_CROSS_SECTION_SIZE);
        block.put("min_cross_section_size_basis", MIN_CROSS_SECTION_SIZE_BASIS);
        block.put("input_cross_section_days", s.inputDays());
        block.put("days_dropped_below_min_cross_section", s.daysBelowMinCrossSection());
        block.put("days_dropped_non_finite_ic", s.daysNonFiniteIc());
        block.put("excluded_non_finite_observations", s.excludedNonFiniteObservations());
        block.put("groups_with_observations", s.groupsWithObservations());
        block.put("pearson_support_days", s.pearsonSupportDays());
        block.put("rank_ic_std_basis", RANK_IC_STD_BASIS);
        block.put("rank_ic_std_basis_note", RANK_IC_STD_BASIS_NOTE);
        block.put("rank_ic_std_sample_basis", RANK_IC_STD_SAMPLE_BASIS);
        block.put("rank_ic_std_sample_note", RANK_IC_STD_SAMPLE_NOTE);
        block.put("group_boundary_rule", GROUP_BOUNDARY_RULE);
        block.put("group_boundary_rule_note", GROUP_BOUNDARY_RULE_NOTE);
        block.put("rounding_rule", ROUNDING_RULE);
        block.put("rounding_rule_note", ROUNDING_RULE_NOTE);
        block.put("ci_status", ciStatus(s));
        block.put("long_short_spread_basis", LONG_SHORT_SPREAD_BASIS);
        block.put("long_short_spread_basis_note", LONG_SHORT_SPREAD_BASIS_NOTE);
        block.put("long_short_spread_cost_basis", spreadCostBasis);
        block.put("long_short_spread_cost_basis_note", SPREAD_COST_BASIS_NOTE);
        putNeutralSpreadKeys(block, s, spreadCostBasis);
        block.put("pearson_ic_mean_note", PEARSON_IC_MEAN_NOTE);
        block.put("precision_policy", PRECISION_POLICY);
        block.put("precision_policy_note", PRECISION_POLICY_NOTE);
        block.put("grouped_returns_observations_basis", GROUPED_RETURN_OBSERVATIONS_BASIS);
        block.put("grouped_returns_observations_basis_note", GROUPED_RETURN_OBSERVATIONS_BASIS_NOTE);
    }

    /**
     * 两腿价差的成本并列键：单腿往返成本 + 真双腿美元中性净价差。
     * 净收益块的价差**本身已是净价差** ⟹ 中性值写 {@code null} 并声明「不适用」，
     * 而不是把同一数值换个名字再发一遍（那会让同名不同量的歧义重生）。
     */
    private static void putNeutralSpreadKeys(
            Map<String, Object> block, FactorResearchSummary s, String spreadCostBasis) {
        double perLegCost = FactorResearchEngine.roundTripCostPerLeg(
                ResearchConvention.DEFAULT_COMMISSION_RATE,
                ResearchConvention.DEFAULT_SELL_TAX_RATE,
                ResearchConvention.DEFAULT_SLIPPAGE_RATE);
        block.put("cost_per_leg_round_trip", round4(perLegCost));
        block.put("cost_per_leg_round_trip_exact", Double.isFinite(perLegCost) ? perLegCost : null);
        block.put("cost_per_leg_round_trip_basis", PER_LEG_COST_BASIS);
        block.put("cost_per_leg_round_trip_note", PER_LEG_COST_NOTE);
        boolean gross = SPREAD_COST_BASIS_GROSS.equals(spreadCostBasis);
        Double neutral = gross
                ? FactorResearchEngine.dollarNeutralSpread(s.longShortSpread(), perLegCost)
                : null;
        block.put("long_short_spread_neutral", round4(neutral));
        block.put("long_short_spread_neutral_exact", neutral);
        block.put("long_short_spread_neutral_basis",
                gross ? NEUTRAL_SPREAD_BASIS_GROSS_MINUS_TWO_LEGS
                        : (SPREAD_COST_BASIS_NET.equals(spreadCostBasis)
                                ? NEUTRAL_SPREAD_BASIS_ALREADY_NET
                                : SPREAD_COST_BASIS_NONE));
        block.put("long_short_spread_neutral_note", NEUTRAL_SPREAD_NOTE);
    }

    /**
     * 区间状态：{@code INSUFFICIENT_N}（T &lt; 2，HAC 标准误与区间均无定义，载荷写 {@code null}）、
     * {@code UNAVAILABLE}（区间端点缺失/无定义，**不**谎报零宽区间）、
     * {@code ZERO_WIDTH}（下界不小于上界，即零宽区间，不是「精确估计」）、否则 {@code OK}。
     */
    private static String ciStatus(FactorResearchSummary s) {
        if (s.crossSectionDays() < 2) {
            return "INSUFFICIENT_N";
        }
        Double lower = s.ciLower();
        Double upper = s.ciUpper();
        // 区间端点为 null ⟹ 区间不可用；不得把 null 拆箱成 NPE，也不得回退成「零宽区间」的结论
        if (lower == null || upper == null) {
            return "UNAVAILABLE";
        }
        if (!(upper > lower)) {
            return "ZERO_WIDTH";
        }
        return STATUS_OK;
    }

    /** 某口径的截面 IC 汇总块（含 HAC 标准误与 block bootstrap 置信区间）。 */
    private Map<String, Object> buildSnapshotScopeBlock(
            String scope, String basisNote, int holding,
            Map<String, List<CrossSectionObservation>> obsByDate) {
        FactorResearchSummary summary = FactorResearchEngine.summarize(
                obsByDate, holding, RANK_IC_GROUP_COUNT, RANK_IC_MIN_CROSS_SECTION_SIZE, SNAPSHOT_UNIVERSE_NOTE);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("metric_scope", scope);
        block.put("status", summary.totalObservations() > 0 ? STATUS_OK : STATUS_NO_OBSERVATIONS);
        block.put("basis_note", basisNote);
        block.put("embargo_note", RANK_IC_EMBARGO_NOTE);
        block.put("universe_note", SNAPSHOT_UNIVERSE_NOTE);
        putSummaryMetricKeys(block, summary);
        putSummaryDisclosure(block, summary, PRICE_BASIS_RAW, SPREAD_COST_BASIS_GROSS);
        if (summary.totalObservations() == 0) {
            block.put("note", "该口径无可用观测（forward 区间未形成、raw 缺失或无可执行标签），不给出 IC");
        }
        return block;
    }

    /** 口径不可用块（如未提供 labelVersion 时 EXECUTABLE 为 UNAVAILABLE，不允许造假全截面）。 */
    private Map<String, Object> unavailableScopeBlock(String scope, String basisNote) {
        FactorResearchSummary summary = FactorResearchEngine.summarize(
                Map.of(), 0, RANK_IC_GROUP_COUNT, RANK_IC_MIN_CROSS_SECTION_SIZE, SNAPSHOT_UNIVERSE_NOTE);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("metric_scope", scope);
        block.put("status", "UNAVAILABLE");
        block.put("basis_note", basisNote);
        putSummaryMetricKeys(block, summary);
        putSummaryDisclosure(block, summary, PRICE_BASIS_RAW, SPREAD_COST_BASIS_NONE);
        block.put("note", basisNote);
        return block;
    }

    private static List<Map<String, Object>> dailyIcList(FactorResearchSummary summary) {
        List<Map<String, Object>> daily = new ArrayList<>();
        for (DailyRankIc d : summary.dailyRankIc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trade_date", d.tradeDate());
            row.put("rank_ic", round4(d.rankIc()));
            row.put("sample_size", d.sampleSize());
            // 纯增量披露：该日被剔除的非有限观测数 + 未取整 IC（供外部复核标签是否可重建）
            row.put("excluded_observations", d.excludedObservations());
            row.put("rank_ic_exact", d.rankIc());
            daily.add(row);
        }
        return daily;
    }

    private static List<Map<String, Object>> groupedReturnList(FactorResearchSummary summary) {
        List<Map<String, Object>> grouped = new ArrayList<>();
        for (GroupReturnStat g : summary.groupedReturns()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group_no", g.groupNo());
            row.put("label", g.label());
            row.put("avg_return", round4(g.avgReturn()));
            row.put("observations", g.observations());
            // 纯增量披露：空组标记（avg_return = null = 无观测，不参与方向断言与极差）+ 未取整均值
            row.put("empty", g.observations() == 0);
            row.put("avg_return_exact", g.avgReturn());
            grouped.add(row);
        }
        return grouped;
    }

    private Map<String, Object> buildSnapshotFunnel(
            int rowsTotal, int scoredValid, int crossSectionDays,
            int reusableDays, List<String> skippedDates,
            Map<String, Object> execBreakdown, boolean executableAvailable, String labelVersion) {
        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("universe_source", "feature_snapshot_row");
        funnel.put("partitions_reusable", reusableDays);
        funnel.put("partitions_skipped", skippedDates);
        funnel.put("cross_section_days", crossSectionDays);
        funnel.put("rows_total", rowsTotal);
        funnel.put("scored_valid", scoredValid);
        funnel.put("coverage", rowsTotal == 0 ? 0.0
                : Math.round(scoredValid * 10000.0 / rowsTotal) / 100.0);
        funnel.put("label_version", executableAvailable ? labelVersion : null);
        funnel.put("exec_status_breakdown", execBreakdown);
        funnel.put("tradable_status", executableAvailable ? METRIC_SCOPE_EXECUTABLE : "UNAVAILABLE");
        funnel.put("tradable_note", executableAvailable
                ? "EXECUTABLE 样本 = label_v2 execStatus=FILLED（统一执行政策产物）；其余状态分别计入拒绝漏斗"
                : "未提供 labelVersion，无法按执行状态过滤，tradable 不可用");
        funnel.put("metric_scope", METRIC_SCOPE_COST_APPROX);
        // 纯增量披露（Q-R011-01 / Q-R014-07 / Q-R014-09）：样本闸与取整规则原先无法从载荷读出
        funnel.put("group_count", RANK_IC_GROUP_COUNT);
        funnel.put("min_cross_section_size", RANK_IC_MIN_CROSS_SECTION_SIZE);
        funnel.put("min_cross_section_size_basis", MIN_CROSS_SECTION_SIZE_BASIS);
        funnel.put("rounding_rule", ROUNDING_RULE);
        funnel.put("rounding_rule_note", ROUNDING_RULE_NOTE);
        funnel.put("group_boundary_rule", GROUP_BOUNDARY_RULE);
        funnel.put("note", "完整 PIT feature snapshot 漏斗：rows_total = feature_snapshot_row 全截面行数；"
                + "scored_valid = final_score>0；各口径观测数见 daily_funnel；FAILED/RUNNING 分区不参与");
        return funnel;
    }

    /**
     * Rank IC 研究块：复用 {@link FactorResearchEngine} 计算信号日得分与 forward return 的 Spearman 秩相关。
     *
     * <p>毛收益口径：forward return = close[t+h] / close[t] - 1（t 为信号日，h 为持仓交易日数）。
     * 成本后近似净收益口径（与 {@link ResearchConvention} 对齐）：entry = open[t+1]×(1+佣金+滑点)，
     * exit = close[t+1+h]×(1-佣金-印花税-滑点)，即次日开盘入场、持有 h 个交易日收盘退出并扣费；
     * 不含涨跌停/停牌拒单过滤，不得称为完整可执行 IC。
     * 两个口径各自独立汇总输出，供并列对比；信号日接近数据末端（forward 区间未形成）自动跳过，不外推。
     *
     * <p>行情由调用方通过 {@code prices} 一次性预加载（close+open 查找表），本方法不再逐股查询。
     */
    private Map<String, Object> buildRankIc(
            Map<String, SelectorResult> latestPerKey,
            Map<String, List<SelectorResultItem>> itemsByResultId,
            LocalDate startDate,
            LocalDate endDate,
            int holdingDays,
            PriceCache prices) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("holding_days", holdingDays);
        block.put("group_count", RANK_IC_GROUP_COUNT);
        block.put("basis_note", RANK_IC_BASIS_NOTE);
        block.put("net_basis_note", RANK_IC_NET_BASIS_NOTE);
        block.put("embargo_note", RANK_IC_EMBARGO_NOTE);
        block.put("universe_note", RANK_IC_UNIVERSE_NOTE);
        block.put("metric_scope", METRIC_SCOPE_RAW);
        block.put("metric_scopes", List.of(METRIC_SCOPE_RAW, METRIC_SCOPE_COST_APPROX));
        block.put("executable_metric_scope", "UNAVAILABLE");
        block.put("feature_snapshot_note", FEATURE_SNAPSHOT_NOTE);

        if (latestPerKey.isEmpty()) {
            // 该分支与正常路径发射**同一组键**（原实现只发射 10 键、缺 pearson_ic_mean /
            // monotonicity / hac_std / ci_lower / ci_upper，而前端把 pearson_ic_mean 声明为必需，
            // 造成类型契约层面的未定义读取；Q-R007-03 / Q-R009-05）
            FactorResearchSummary empty = FactorResearchEngine.summarize(
                    Map.of(), holdingDays, RANK_IC_GROUP_COUNT, RANK_IC_MIN_CROSS_SECTION_SIZE,
                    RANK_IC_UNIVERSE_NOTE);
            block.put("status", STATUS_NO_OBSERVATIONS);
            putSummaryMetricKeys(block, empty);
            putSummaryDisclosure(block, empty, PRICE_BASIS_QFQ, SPREAD_COST_BASIS_GROSS);
            block.put("cost_net_ic", emptyNetIcBlock());
            return block;
        }

        // 交易日历：覆盖 [startDate, endDate + holdingDays*2] 以留足 forward 区间
        LocalDate calStart = startDate;
        LocalDate calEnd = endDate.plusDays(holdingDays * 2L);
        List<LocalDate> tradingDays = marketDataRepository.findDistinctTradeDatesBetween(calStart, calEnd);
        Map<LocalDate, Integer> dayIndex = new HashMap<>();
        for (int i = 0; i < tradingDays.size(); i++) {
            dayIndex.put(tradingDays.get(i), i);
        }

        // 逐信号日构建截面观测（毛收益 + 成本后近似净收益，两个独立口径）
        Map<String, List<CrossSectionObservation>> grossObsByDate = new LinkedHashMap<>();
        Map<String, List<CrossSectionObservation>> netObsByDate = new LinkedHashMap<>();
        for (SelectorResult result : latestPerKey.values()) {
            LocalDate signalDate = result.getTradeDate();
            Integer baseIdx = dayIndex.get(signalDate);
            if (baseIdx == null) {
                continue;
            }
            int grossExitIdx = baseIdx + holdingDays;
            int netEntryIdx = baseIdx + 1;
            int netExitIdx = netEntryIdx + holdingDays;

            List<SelectorResultItem> items = itemsByResultId.getOrDefault(result.getResultId(), List.of());
            List<CrossSectionObservation> grossObs = new ArrayList<>();
            List<CrossSectionObservation> netObs = new ArrayList<>();
            for (SelectorResultItem it : items) {
                if (it.getFinalScore() == null || it.getFinalScore() <= 0) {
                    continue;
                }
                String stockKey = it.getStockCode() + "|" + it.getExchangeType();
                double score = it.getFinalScore();

                // 毛收益：close[t+h] / close[t] - 1
                if (grossExitIdx < tradingDays.size()) {
                    LocalDate exitDate = tradingDays.get(grossExitIdx);
                    BigDecimal closeSignal = prices.close().get(stockKey + "|" + signalDate);
                    BigDecimal closeExit = prices.close().get(stockKey + "|" + exitDate);
                    if (closeSignal != null && closeExit != null
                            && closeSignal.doubleValue() > 0 && closeExit.doubleValue() > 0) {
                        double forwardReturn = closeExit.doubleValue() / closeSignal.doubleValue() - 1.0;
                        grossObs.add(new CrossSectionObservation(score, forwardReturn));
                    }
                }

                // 成本后近似净收益：open[t+1]×(1+佣金+滑点) 入场 → close[t+1+h]×(1-佣金-印花税-滑点) 退出（无涨跌停/停牌拒单过滤）
                // V2-MP-014：成交价只读 market_price_raw；缺 raw 即跳过该观测（禁止用 qfq 前复权价兜底成交样本）
                if (netExitIdx < tradingDays.size()) {
                    LocalDate entryDate = tradingDays.get(netEntryIdx);
                    LocalDate exitDate = tradingDays.get(netExitIdx);
                    BigDecimal openEntry = prices.rawOpen().get(stockKey + "|" + entryDate);
                    BigDecimal closeExit = prices.rawClose().get(stockKey + "|" + exitDate);
                    if (openEntry != null && closeExit != null
                            && openEntry.doubleValue() > 0 && closeExit.doubleValue() > 0) {
                        double entryPrice = openEntry.doubleValue()
                                * (1 + ResearchConvention.DEFAULT_SLIPPAGE_RATE);
                        double entryCost = entryPrice * (1 + ResearchConvention.DEFAULT_COMMISSION_RATE);
                        double exitPrice = closeExit.doubleValue()
                                * (1 - ResearchConvention.DEFAULT_SLIPPAGE_RATE);
                        double exitProceeds = exitPrice
                                * (1 - ResearchConvention.DEFAULT_COMMISSION_RATE - ResearchConvention.DEFAULT_SELL_TAX_RATE);
                        double netReturn = exitProceeds / entryCost - 1.0;
                        netObs.add(new CrossSectionObservation(score, netReturn));
                    }
                }
            }
            if (!grossObs.isEmpty()) {
                grossObsByDate.put(signalDate.toString(), grossObs);
            }
            if (!netObs.isEmpty()) {
                netObsByDate.put(signalDate.toString(), netObs);
            }
        }

        FactorResearchSummary summary = FactorResearchEngine.summarize(
                grossObsByDate,
                holdingDays,
                RANK_IC_GROUP_COUNT,
                RANK_IC_MIN_CROSS_SECTION_SIZE,
                RANK_IC_UNIVERSE_NOTE
        );

        block.put("status", summary.totalObservations() > 0 ? STATUS_OK : STATUS_NO_OBSERVATIONS);
        putSummaryMetricKeys(block, summary);
        // 价格基准：本面为 qfq 前复权 close（与 snapshot 面同名 metric_scope=RAW 的 rawClose 不同基准）
        putSummaryDisclosure(block, summary, PRICE_BASIS_QFQ, SPREAD_COST_BASIS_GROSS);

        // 成本后近似净收益 IC（与标签/回测成交口径对齐的对照列；不含涨跌停/停牌拒单过滤）
        block.put("cost_net_ic", buildNetIcBlock(
                FactorResearchEngine.summarize(
                        netObsByDate,
                        holdingDays,
                        RANK_IC_GROUP_COUNT,
                        RANK_IC_MIN_CROSS_SECTION_SIZE,
                        RANK_IC_UNIVERSE_NOTE)));
        return block;
    }

    private Map<String, Object> emptyNetIcBlock() {
        FactorResearchSummary empty = FactorResearchEngine.summarize(
                Map.of(), DEFAULT_RANK_IC_HOLDING_DAYS, RANK_IC_GROUP_COUNT,
                RANK_IC_MIN_CROSS_SECTION_SIZE, RANK_IC_UNIVERSE_NOTE);
        Map<String, Object> net = new LinkedHashMap<>();
        net.put("basis_note", RANK_IC_NET_BASIS_NOTE);
        net.put("metric_scope", METRIC_SCOPE_COST_APPROX);
        net.put("status", STATUS_NO_OBSERVATIONS);
        // Q-R004-03：无 raw 行情时本块曾只以 metric_scope = COST_APPROX + long_short_spread = 0.0 表达，
        // 「本口径无数据」与「成本后恰为 0」不可区分；此键为纯增量显式标志。
        // 统计量本身由 emptySummary 起即为 null（rank_ic_* / long_short_spread 不再发 0.0），与 data_available 并存。
        net.put("data_available", false);
        putSummaryMetricKeys(net, empty);
        putSummaryDisclosure(net, empty, PRICE_BASIS_QFQ, SPREAD_COST_BASIS_NET);
        net.put("note", "成本后近似净收益 IC（COST_APPROX；next-open 入场 + 持有期收盘退出 + 扣费；不含涨跌停/停牌拒单）；"
                + "EXECUTABLE 暂不可用；与毛收益 IC 窗口差一日，并列仅作口径对照");
        return net;
    }

    private Map<String, Object> buildNetIcBlock(FactorResearchSummary s) {
        Map<String, Object> net = new LinkedHashMap<>();
        net.put("basis_note", RANK_IC_NET_BASIS_NOTE);
        net.put("metric_scope", METRIC_SCOPE_COST_APPROX);
        net.put("status", s.totalObservations() > 0 ? STATUS_OK : STATUS_NO_OBSERVATIONS);
        net.put("data_available", s.totalObservations() > 0);
        // 本块的 summary 实际使用 RANK_IC_GROUP_COUNT / RANK_IC_MIN_CROSS_SECTION_SIZE，
        // 原实现不发射这两者，读者只能从兄弟块反推（Q-R013-07）
        putSummaryMetricKeys(net, s);
        putSummaryDisclosure(net, s, PRICE_BASIS_QFQ, SPREAD_COST_BASIS_NET);
        net.put("note", "成本后近似净收益 IC（COST_APPROX；next-open 入场 + 持有期收盘退出 + 扣费；不含涨跌停/停牌拒单）；"
                + "EXECUTABLE 暂不可用；与毛收益 IC 窗口差一日，并列仅作口径对照");
        return net;
    }

    /**
     * 研究样本漏斗（sparse selector_result）：universe / feature_valid / tradable。
     * 完整 PIT feature snapshot 漏斗依赖 MP-005；tradable 在未接入 TradabilityPolicy 时标 UNAVAILABLE。
     */
    private Map<String, Object> buildResearchFunnel(
            Map<String, SelectorResult> latestPerKey,
            int totalObservations,
            int validObservations
    ) {
        int universeCount = 0;
        int daysWithUniverse = 0;
        for (SelectorResult r : latestPerKey.values()) {
            Integer cross = extractCrossSectionCount(r);
            if (cross != null && cross > 0) {
                universeCount += cross;
                daysWithUniverse++;
            }
        }
        boolean universeFallback = universeCount == 0;
        if (universeFallback) {
            universeCount = totalObservations;
        }
        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("universe_count", universeCount);
        funnel.put("universe_days_with_snapshot", daysWithUniverse);
        funnel.put("cross_section_days", latestPerKey != null ? latestPerKey.size() : 0);
        funnel.put("feature_valid_count", validObservations);
        funnel.put("valid_observations", validObservations);
        funnel.put("selector_item_count", totalObservations);
        funnel.put("total_observations", totalObservations);
        funnel.put("tradable_count", null);
        funnel.put("tradable_status", "UNAVAILABLE");
        funnel.put("tradable_note", "EXECUTABLE tradable funnel unavailable without feature snapshot + TradabilityPolicy");
        funnel.put("universe_source", "sparse_selector_result");
        // 纯增量披露（Q-R015-01）：universe_count 在回退路径下已被替换为 totalObservations，
        // 而 universe_source 是常量、原 note 也不提回退 ⟹ 只看 universe_source 读不出替换
        funnel.put("universe_count_source",
                universeFallback ? "FALLBACK_TOTAL_OBSERVATIONS" : "CROSS_SECTION_COUNT_SUM");
        funnel.put("universe_count_fallback", universeFallback);
        funnel.put("universe_count_note", universeFallback
                ? "本行 universe_count **已回退**为 total_observations（无任何 cross_section_count 快照可用），"
                + "不得读作真实候选池规模"
                : "universe_count = 各信号日 cross_section_count 之和");
        funnel.put("key_alias_note", KEY_ALIAS_NOTE);
        funnel.put("rounding_rule", ROUNDING_RULE);
        funnel.put("metric_scope", METRIC_SCOPE_COST_APPROX);
        // Q-R015-02：本块的 metric_scope 是**家族继承值**（无条件写 COST_APPROX），而块内并无成本口径数值。
        // 只加声明、不改取值域（是否统一属协议 §7 record-only）。
        funnel.put("metric_scope_note", METRIC_SCOPE_INHERITED_NOTE);
        funnel.put("note", "tradable_count 需统一 TradabilityPolicy；当前仅披露 selector 候选漏斗");
        return funnel;
    }

    private static Integer extractCrossSectionCount(SelectorResult r) {
        if (r == null || r.getFactorSnapshot() == null) {
            return null;
        }
        Object v = r.getFactorSnapshot().get("cross_section_count");
        if (v instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    /**
     * 一次性预加载所有候选证券在 [calStart, calEnd] 的 close/open 查找表（key = "code|ex|date"）。
     * 供各持仓期复用，避免逐股/逐持仓期重复查询。
     *
     * <p>单次批量拉取区间内全部行情后按 stock key 过滤建表，消除逐股 N+1 查询
     * （全市场研究时 N 可达数千，逐股查询显著拖慢因子研究面板）。
     *
     * <p>V2-MP-014：raw* 为 {@code market_price_raw} 不复权行情，仅供成本后近似净收益（成交口径）
     * 使用；qfq close/open 仅供毛收益 IC（价格收益度量）。缺 raw 的观测一律跳过，禁止 qfq 兜底。
     */
    private PriceCache loadPriceCache(Map<String, List<SelectorResultItem>> itemsByResultId,
                                      LocalDate calStart, LocalDate calEnd) {
        Map<String, BigDecimal> close = new HashMap<>();
        Map<String, BigDecimal> open = new HashMap<>();
        Map<String, BigDecimal> rawClose = new HashMap<>();
        Map<String, BigDecimal> rawOpen = new HashMap<>();
        java.util.Set<String> stockKeys = new java.util.LinkedHashSet<>();
        for (List<SelectorResultItem> items : itemsByResultId.values()) {
            for (SelectorResultItem it : items) {
                stockKeys.add(it.getStockCode() + "|" + it.getExchangeType());
            }
        }
        if (stockKeys.isEmpty()) {
            return new PriceCache(close, open, rawClose, rawOpen);
        }
        List<MarketData> allBars = marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(calStart, calEnd);
        for (MarketData bar : allBars) {
            String stockKey = bar.getStockCode() + "|" + bar.getExchangeType();
            if (!stockKeys.contains(stockKey)) {
                continue;
            }
            String key = stockKey + "|" + bar.getTradeDate();
            if (bar.getClosePrice() != null) {
                close.put(key, bar.getClosePrice());
            }
            if (bar.getOpenPrice() != null) {
                open.put(key, bar.getOpenPrice());
            }
        }
        // V2-MP-014：净收益成交价只读 market_price_raw（不复权真实成交价）
        List<MarketPriceRaw> allRaw = marketPriceRawRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(calStart, calEnd);
        if (allRaw != null) {
            for (MarketPriceRaw raw : allRaw) {
                String stockKey = raw.getStockCode() + "|" + raw.getExchangeType();
                if (!stockKeys.contains(stockKey)) {
                    continue;
                }
                String key = stockKey + "|" + raw.getTradeDate();
                if (raw.getClosePrice() != null) {
                    rawClose.put(key, raw.getClosePrice());
                }
                if (raw.getOpenPrice() != null) {
                    rawOpen.put(key, raw.getOpenPrice());
                }
            }
        }
        return new PriceCache(close, open, rawClose, rawOpen);
    }

    /** 预加载的行情查找表（qfq close/open 供毛收益；rawClose/rawOpen 供成本后净收益成交价）。 */
    private record PriceCache(
            Map<String, BigDecimal> close, Map<String, BigDecimal> open,
            Map<String, BigDecimal> rawClose, Map<String, BigDecimal> rawOpen) {}

    private static int resolveHoldingDays(Integer holdingDays) {
        if (holdingDays == null || holdingDays < 1) {
            return DEFAULT_RANK_IC_HOLDING_DAYS;
        }
        return Math.min(holdingDays, 60);
    }

    /** 按数量等分为 groupCount 组，返回每组的得分范围与数量，展示得分集中度。 */
    private List<Map<String, Object>> buildGroupBands(List<Double> sortedScores, int groupCount) {
        List<Map<String, Object>> bands = new ArrayList<>();
        int n = sortedScores.size();
        if (n == 0 || groupCount <= 0) {
            return bands;
        }
        int effectiveGroups = Math.min(groupCount, n);
        int baseSize = n / effectiveGroups;
        int remainder = n % effectiveGroups;
        int idx = 0;
        for (int g = 0; g < effectiveGroups; g++) {
            int size = baseSize + (g < remainder ? 1 : 0);
            double lo = sortedScores.get(idx);
            double hi = sortedScores.get(idx + size - 1);
            double avg = 0;
            for (int i = idx; i < idx + size; i++) {
                avg += sortedScores.get(i);
            }
            avg /= size;
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("group_no", g + 1);
            b.put("min_score", round2(lo));
            b.put("max_score", round2(hi));
            b.put("avg_score", round2(avg));
            b.put("count", size);
            bands.add(b);
            idx += size;
        }
        return bands;
    }

    private Map<String, Object> buildDistribution(List<Double> sortedScores) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (sortedScores.isEmpty()) {
            d.put("min", 0.0);
            d.put("p25", 0.0);
            d.put("median", 0.0);
            d.put("p75", 0.0);
            d.put("max", 0.0);
            return d;
        }
        int n = sortedScores.size();
        d.put("min", round2(sortedScores.get(0)));
        d.put("p25", round2(percentile(sortedScores, 0.25)));
        d.put("median", round2(percentile(sortedScores, 0.50)));
        d.put("p75", round2(percentile(sortedScores, 0.75)));
        d.put("max", round2(sortedScores.get(n - 1)));
        return d;
    }

    /** 线性插值分位数 */
    private double percentile(List<Double> sorted, double q) {
        int n = sorted.size();
        if (n == 1) return sorted.get(0);
        double pos = q * (n - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted.get(lo);
        double frac = pos - lo;
        return sorted.get(lo) * (1 - frac) + sorted.get(hi) * frac;
    }

    private static Double round2(Double v) {
        if (v == null || !Double.isFinite(v)) {
            return null;
        }
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 4 位小数四舍五入；非有限值（NaN/±Inf）返回 {@code null}。
     *
     * <p>原实现 `Math.round(v * 10000.0) / 10000.0` 对 NaN 得 `0.0`、对 ±Inf 得
     * `9.223372036854776E14`（`Math.round(±Inf)` = `Long.MAX_VALUE`），把「无数据/不可定义」
     * 写成具体数；本项目已有同类约定的先例（{@code LabelFactoryService} 对 maxFav/maxAdv
     * 刻意用包装类型而非 0.0）。
     */
    private static Double round4(Double v) {
        if (v == null || !Double.isFinite(v)) {
            return null;
        }
        return Math.round(v * 10000.0) / 10000.0;
    }
}
