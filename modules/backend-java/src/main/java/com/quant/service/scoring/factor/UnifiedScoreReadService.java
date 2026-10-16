package com.quant.service.scoring.factor;

import com.quant.service.scoring.ScoringConstants;
import com.quant.service.scoring.ScoringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次打分，5 个策略入口全部就绪（重构目标的落点）。
 *
 * <p><b>它到底省掉了什么——必须说准</b>：
 * 省掉的是<b>行情装载与 L1 特征计算</b>。此前 5 个策略各读一次全量行情、
 * 各自算一遍均线/MACD/RSI/波动率，同一只股票在不同策略里可能算出不同特征。
 * 本服务用 {@link UnifiedFactorMarketLoader} 装载并计算<b>一次</b>，
 * 5 个策略共享同一份 {@link FeatureVector}。
 *
 * <p><b>它没有省掉什么</b>：L2 横截面标准化与 L4 排名<b>每个策略各做一次</b>。
 * 这是设计使然而非妥协——各策略权重不同，横截面百分位虽取自同一份原始因子值，
 * 但加权后的排序必须独立计算。任何声称"5 个策略共享一次标准化结果"的说法都是错的：
 * 那样 5 个策略会得到完全相同的排序，也就没有存在意义。
 *
 * <p><b>与旧落库结构的兼容</b>：结果经 {@link LegacyItemProjector} 投影成
 * 既有 {@code selector_result_item} 形状，因此前端 5 个 tab 无需改动即可消费。
 * 投影的语义边界（{@code trend_score} 等字段含义变化）在
 * {@link LegacyItemProjector#SCORE_SEMANTICS} 中标注，并随每条结果下发。
 */
@Service
public class UnifiedScoreReadService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedScoreReadService.class);

    /** 落库时写入 factor_version，区别于旧实现的 trend_signal_v1 等版本号。 */
    public static final String FACTOR_VERSION = "unified_factor_all_strategies_v1";

    private final UnifiedFactorMarketLoader marketLoader;
    private final LegacyResultRowWriter rowWriter;
    private final FactorCrossSectionWriter crossSectionWriter;
    private final com.quant.repository.SelectorResultRepository selectorResultRepository;
    private final com.quant.repository.SelectorResultItemRepository selectorResultItemRepository;

    public UnifiedScoreReadService(
            UnifiedFactorMarketLoader marketLoader,
            LegacyResultRowWriter rowWriter,
            FactorCrossSectionWriter crossSectionWriter,
            com.quant.repository.SelectorResultRepository selectorResultRepository,
            com.quant.repository.SelectorResultItemRepository selectorResultItemRepository) {
        this.marketLoader = marketLoader;
        this.rowWriter = rowWriter;
        this.crossSectionWriter = crossSectionWriter;
        this.selectorResultRepository = selectorResultRepository;
        this.selectorResultItemRepository = selectorResultItemRepository;
    }

    /**
     * 一次装载 + 特征计算，然后为全部 5 个策略产出结果。
     *
     * @param request 透传参数：{@code tradeDate / lookbackDays / excludeST / minListingDays /
     *                minAvgAmount / topN}。{@code includeContributions} 控制是否下发逐因子明细。
     *                {@code persist} 为 true 时写入既有结构（默认 false）。
     *                {@code includeCrossSection} 为 true 时把**全截面**写入
     *                {@code factor_cross_section_score}（默认 false），
     *                这是 Rank IC 验证通路的前置条件——详见该参数处注释。
     *
     * <p><b>事务语义</b>：本方法<b>不加</b> {@code readOnly = true}，因为它在 {@code persist=true}
     * 或 {@code includeCrossSection=true} 时要写库。（曾经加了，导致写路径被 Hikari 拒绝：
     * {@code Connection is read-only}。只读由 {@link UnifiedFactorMarketLoader#load} 自己声明，
     * 那边只读且确实只读。）
     */
    @Transactional
    public Map<String, Object> runAllStrategies(Map<String, Object> request) {
        Map<String, Object> req = request != null ? request : Map.of();

        // ── 只装载一次 ─────────────────────────────────────────────────
        UnifiedFactorMarketLoader.Outcome loaded = marketLoader.load(req);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("trade_date", loaded.tradeDate().toString());
        out.put("load_count", loaded.loadCount());
        out.put("single_load", true);

        if (!loaded.dataAvailable() || loaded.features().isEmpty()) {
            out.put("message", loaded.dataAvailable() ? "无可交易标的" : "无可用行情数据");
            out.put("candidate_count", loaded.candidateCount());
            out.put("tradability_rejected", loaded.tradabilityRejected());
            out.put("listing_days_rejected", loaded.listingRejected());
            out.put("rejection_breakdown", loaded.tradabilityCounts());
            out.put("strategies", List.of());
            return out;
        }

        int topN = ScoringUtils.parseInt(req.get("topN"), 200, 1, 5000);
        double reqMinAvgAmount = ScoringUtils.parseDouble(req.get("minAvgAmount"),
                ScoringUtils.DEFAULT_MIN_AVG_AMOUNT, 0d, 1_000_000_000_000d);
        boolean includeContributions = ScoringUtils.parseBool(req.get("includeContributions"), true);
        // 落库默认关闭。开启后写的是**既有** selector_result / selector_result_item 结构，
        // 前端 5 个 tab 无需改动即可读到。默认关闭的原因见类注释与方案文档 §8.10.3。
        boolean persist = ScoringUtils.parseBool(req.get("persist"), false);
        boolean dedupe = ScoringUtils.parseBool(req.get("dedupe"), true);
        // 全截面落库（研究用，默认关闭）。开启后写 factor_cross_section_score，
        // 使 Rank IC 可在全截面上计算——这是打分体系此前缺失的验证通路。
        // 体量约 2.7 万行/日，故必须显式开启，避免日常打分产生大量写入。
        boolean includeCrossSection = ScoringUtils.parseBool(req.get("includeCrossSection"), false);
        String fingerprint = fingerprint(loaded.tradeDate(), req);

        List<Map<String, Object>> strategyBlocks = new ArrayList<>();
        for (StrategyDefinition definition : StrategyCatalog.all()) {
            // 策略级最小成交额与请求参数取较严者，与单策略路径口径一致
            double effectiveMinAvgAmount = Math.max(reqMinAvgAmount, definition.minAvgAmount());
            StrategyDefinition effective = withMinAvgAmount(definition, effectiveMinAvgAmount);

            // L2 + L4：每个策略各做一次（权重不同 → 排序必须独立计算）
            UnifiedFactorScoringEngine.Result result =
                    UnifiedFactorScoringEngine.score(effective, loaded.features());

            Map<String, Object> block = projectStrategy(definition, result, topN, includeContributions);
            if (includeCrossSection) {
                int written = crossSectionWriter.replace(loaded.tradeDate(), definition, result);
                block.put("cross_section_rows", written);
            }
            if (persist) {
                block.put("persist_outcome", persistStrategy(
                        definition, block, loaded.tradeDate(), fingerprint, dedupe));
            }
            strategyBlocks.add(block);
        }

        out.put("candidate_count", loaded.features().size());
        out.put("universe_size", loaded.universe().universeSize());
        out.put("tradability_rejected", loaded.tradabilityRejected());
        out.put("listing_days_rejected", loaded.listingRejected());
        out.put("rejection_breakdown", loaded.tradabilityCounts());
        out.put("universe_lineage", universeLineage(loaded));
        out.put("strategies", strategyBlocks);
        out.put("persisted", persist);
        out.put("cross_section_persisted", includeCrossSection);
        if (includeCrossSection) {
            out.put("cross_section_note",
                    "已写入 factor_cross_section_score（全截面，含 eligible=false 的行）。"
                            + "Rank IC 需要全截面，selector_result_item 只有头部，故独立建表；"
                            + "研究侧按 (trade_date, strategy_code) 取全截面并与 market_data 前向收益 JOIN。");
        }
        if (persist) {
            out.put("persist_note",
                    "已写入既有 selector_result / selector_result_item 结构，strategy_type 为旧口径策略名，"
                            + "前端 5 个 tab 可直接读取。每个策略一条 selector_result。");
        }
        out.put("compatibility_note",
                "各策略结果已投影为既有 selector_result_item 字段名，前端 5 个 tab 可直接消费；"
                        + "但 trend_score/momentum_score/volume_score 在统一口径下含义为「因子类别加权贡献」，"
                        + "与原实现的同名绝对子分数不同，详见每条结果的 extra_metrics.score_semantics。");

        log.info("统一一次打分完成 date={} 装载次数={} 候选={} 策略数={}",
                loaded.tradeDate(), loaded.loadCount(), loaded.features().size(), strategyBlocks.size());
        return out;
    }

    private static Map<String, Object> projectStrategy(
            StrategyDefinition definition,
            UnifiedFactorScoringEngine.Result result,
            int topN,
            boolean includeContributions) {

        // 只保留通过资格闸的股票，按 finalScore 降序（null 视为最低，并列按代码稳定排序）
        List<UnifiedFactorScoringEngine.StockScore> eligible = new ArrayList<>();
        for (UnifiedFactorScoringEngine.StockScore s : result.scores()) {
            if (s.eligible() && s.finalScore() != null) {
                eligible.add(s);
            }
        }
        eligible.sort((a, b) -> {
            int c = Double.compare(b.finalScore(), a.finalScore());
            return c != 0 ? c : String.valueOf(a.stockCode()).compareTo(String.valueOf(b.stockCode()));
        });

        List<Map<String, Object>> items = new ArrayList<>();
        int rank = 1;
        for (UnifiedFactorScoringEngine.StockScore s : eligible) {
            Map<String, Object> item = LegacyItemProjector.project(s, definition, rank++);
            if (!includeContributions) {
                Object extra = item.get("extra_metrics");
                if (extra instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    cast.remove("factor_contributions");
                }
            }
            items.add(item);
        }
        List<Map<String, Object>> sliced = items.size() > topN
                ? new ArrayList<>(items.subList(0, topN))
                : items;

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("legacy_strategy_type", definition.legacyStrategyType());
        block.put("legacy_ui_mode", definition.legacyUiMode());
        block.put("display_name", definition.displayName());
        block.put("factor_version", definition.factorVersion());
        block.put("ranked_count", result.rankedCount());
        block.put("rejected_count", result.rejectedCount());
        block.put("returned_count", sliced.size());
        block.put("rejection_breakdown", result.rejectionBreakdown());
        block.put("normalization_mode", result.normalization().neutralizationMode());
        block.put("industry_neutralization_skipped",
                result.normalization().industryNeutralizationSkipped());
        block.put("market_cap_neutralization_skipped",
                result.normalization().marketCapNeutralizationSkipped());
        block.put("items", sliced);
        return block;
    }

    private static Map<String, Object> universeLineage(UnifiedFactorMarketLoader.Outcome loaded) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("universe_as_of", loaded.tradeDate().toString());
        m.put("universe_size", loaded.universe().universeSize());
        return m;
    }

    // ── 落库（与既有结构兼容）─────────────────────────────────────────

    /**
     * 把一个策略块写进既有 {@code selector_result} / {@code selector_result_item}。
     *
     * <p><b>每个策略一条 {@code selector_result}</b>：因为 {@code strategy_type} 是旧表的分组键，
     * 前端按它取各 tab 的数据；5 个策略合并成一条会让旧读接口取不到。
     *
     * <p><b>去重按 (strategy_type, fingerprint, trade_date, SUCCESS)</b>：
     * 与批次 D 在 {@code UnifiedScoringService} 里修掉 41% 重复率的做法同源。
     * 命中已有成功结果时**返回既有 result_id 且不写任何行**，
     * 因此本方法可以安全地重复调用（幂等）。
     *
     * <p><b>为什么默认不落库</b>：写库需要"哪条运行是权威"的语义
     * （{@code authoritative_run}/{@code authority_source} 至今全为 NULL），
     * 在权威语义确定之前，影子模式必须保持只读。
     */
    private Map<String, Object> persistStrategy(
            StrategyDefinition definition,
            Map<String, Object> block,
            java.time.LocalDate tradeDate,
            String fingerprint,
            boolean dedupe) {

        Map<String, Object> outcome = new LinkedHashMap<>();
        String legacyType = definition.legacyStrategyType();

        if (dedupe) {
            com.quant.entity.SelectorResult dup = selectorResultRepository
                    .findFirstByStrategyTypeAndRequestFingerprintAndTradeDateAndRunStatusOrderByCreatedAtAsc(
                            legacyType, fingerprint, tradeDate, "SUCCESS")
                    .orElse(null);
            if (dup != null) {
                outcome.put("result_id", dup.getResultId());
                outcome.put("deduplicated", true);
                outcome.put("inserted_items", 0);
                outcome.put("note", "命中同日同参数的既有成功结果，未写入任何行");
                return outcome;
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) block.get("items");
        if (items == null) {
            items = List.of();
        }

        String resultId = java.util.UUID.randomUUID().toString().replace("-", "");
        com.quant.entity.SelectorResult sr = new com.quant.entity.SelectorResult();
        sr.setResultId(resultId);
        sr.setTradeDate(tradeDate);
        sr.setStrategyType(legacyType);
        sr.setRequestFingerprint(fingerprint);
        sr.setFactorVersion(FACTOR_VERSION);
        sr.setRunStatus("RUNNING");
        sr.setTotalCandidates(block.get("ranked_count") instanceof Number n ? n.intValue() : items.size());
        sr.setProcessedCandidates(0);
        sr.setSuccessCandidates(0);
        sr.setFailedCandidates(0);
        sr.setLastProcessedKey("");

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("entry", "unified_all_strategies");
        snapshot.put("unified_strategy", definition.code());
        snapshot.put("legacy_strategy_type", legacyType);
        snapshot.put("normalization_mode", block.get("normalization_mode"));
        snapshot.put("fingerprint", fingerprint);
        sr.setFactorSnapshot(snapshot);
        selectorResultRepository.save(sr);

        int rank = 1;
        for (Map<String, Object> item : items) {
            selectorResultItemRepository.save(rowWriter.toRow(item, resultId, rank++, legacyType));
        }
        sr.setProcessedCandidates(items.size());
        sr.setSuccessCandidates(items.size());
        sr.setFailedCandidates(0);
        sr.setRunStatus("SUCCESS");
        selectorResultRepository.save(sr);

        outcome.put("result_id", resultId);
        outcome.put("deduplicated", false);
        outcome.put("inserted_items", items.size());
        return outcome;
    }

    /**
     * 请求指纹。只纳入**影响打分结果**的参数，避免无关字段制造"新指纹"而绕过去重。
     *
     * <p>{@code persist}/{@code dedupe}/{@code includeContributions}/{@code topN}
     * 刻意<b>不</b>参与指纹：它们只影响输出多少、写不写库，不影响算出来的分数。
     * 若把它们纳入，一次"只改 topN"的调用会被当成新请求而重复落库——
     * 那正是 41% 重复率的成因模式。
     */
    static String fingerprint(java.time.LocalDate tradeDate, Map<String, Object> req) {
        String raw = String.join("|",
                "unified_all",
                tradeDate != null ? tradeDate.toString() : "",
                String.valueOf(ScoringUtils.parseInt(req.get("lookbackDays"),
                        ScoringConstants.DEFAULT_LOOKBACK_DAYS, 60, 2000)),
                String.valueOf(ScoringUtils.parseBool(req.get("excludeST"), true)),
                String.valueOf(ScoringUtils.parseDouble(req.get("minAvgAmount"),
                        ScoringUtils.DEFAULT_MIN_AVG_AMOUNT, 0d, 1_000_000_000_000d)),
                String.valueOf(ScoringUtils.parseInt(req.get("minListingDays"), 60, 0, 3650)));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 必然存在；若真不可用，退回原始串而不是抛错中断打分
            return raw;
        }
    }

    /** 返回与入参等价、但最小成交额被覆盖的策略定义（引擎从定义读取该闸）。 */
    private static StrategyDefinition withMinAvgAmount(StrategyDefinition base, double minAvgAmount) {
        if (Math.abs(base.minAvgAmount() - minAvgAmount) < 1e-9) {
            return base;
        }
        StrategyDefinition.Builder b = StrategyDefinition.builder(
                        base.code(), base.displayName(), base.factorVersion())
                .minBars(base.minBars())
                .minAvgAmount(minAvgAmount)
                .minListingDays(base.minListingDays())
                .missingDataPolicy(base.missingDataPolicy())
                .minAvailableWeightRatio(base.minAvailableWeightRatio())
                .legacyStrategyType(base.legacyStrategyType())
                .legacyUiMode(base.legacyUiMode());
        for (Map.Entry<FactorId, Double> e : base.weights().entrySet()) {
            b.weight(e.getKey(), e.getValue());
        }
        if (base.requireMa20AboveMa60()) {
            b.requireMa20AboveMa60();
        }
        if (!base.requiredFactors().isEmpty()) {
            b.require(base.requiredFactors().toArray(new FactorId[0]));
        }
        return b.build();
    }
}
