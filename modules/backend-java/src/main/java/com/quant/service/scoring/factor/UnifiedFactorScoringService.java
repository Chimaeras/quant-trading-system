package com.quant.service.scoring.factor;

import com.quant.entity.MarketData;
import com.quant.entity.StockFinancialSnapshot;
import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.service.PointInTimeUniverseProvider;
import com.quant.service.scoring.FinancialSnapshotLoader;
import com.quant.service.scoring.ScoringConstants;
import com.quant.service.scoring.ScoringUtils;
import com.quant.service.scoring.TradabilityCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一因子打分的生产入口（批次 C：影子模式）。
 *
 * <p><b>影子模式的含义</b>：本服务<b>不替换</b>任何既有打分服务，而是新增一条并行路径。
 * 旧的 5 套（{@code StockTrendScoringService} / {@code ComprehensiveScoringService} /
 * {@code MultiDimScoringService} / {@code StockSelectorService} /
 * {@code OversoldReboundScoringService}）行为完全不变，可通过
 * {@code strategy=UNIFIED} 单独触发本路径，与旧结果做逐日排名相关性对比。
 *
 * <p><b>与旧路径共享的数据口径</b>：交易日解析、PIT universe、K 线分组、财务快照加载
 * 全部沿用既有实现（{@code MarketDataRepository} 区间查询 + {@code PointInTimeUniverseProvider}
 * + {@code FinancialSnapshotLoader}），确保新旧结果可在同一输入上对比——
 * 若数据口径不同，排名差异就无法归因于打分逻辑本身。
 *
 * <p><b>两层过滤，职责不重叠</b>：
 * <ol>
 *   <li><b>可交易性</b>（ST / 停牌 / 涨跌停 / 缺 preClose）用 {@link ScoringUtils#evaluateTradabilityStrict}，
 *       属于「这只股票今天能不能交易」，在任何打分之前。</li>
 *   <li><b>数据充分性</b>（最小 bar 数 / 最小成交额 / 可用因子权重占比）由
 *       {@link UnifiedFactorScoringEngine} 的资格闸处理，属于「这只股票的数据够不够打分」。</li>
 * </ol>
 * 两者都会把原因写进输出，便于区分「被风控挡掉」与「数据不足」。
 */
@Service
public class UnifiedFactorScoringService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedFactorScoringService.class);

    private final MarketDataRepository marketDataRepository;
    private final PointInTimeUniverseProvider pointInTimeUniverseProvider;
    private final FinancialSnapshotLoader financialSnapshotLoader;

    public UnifiedFactorScoringService(
            MarketDataRepository marketDataRepository,
            PointInTimeUniverseProvider pointInTimeUniverseProvider,
            FinancialSnapshotLoader financialSnapshotLoader) {
        this.marketDataRepository = marketDataRepository;
        this.pointInTimeUniverseProvider = pointInTimeUniverseProvider;
        this.financialSnapshotLoader = financialSnapshotLoader;
    }

    /** 本服务产出的因子版本号，供落库与前端识别。 */
    public static final String FACTOR_VERSION = "unified_factor_v1";

    /**
     * 运行一次统一因子打分。
     *
     * <p>支持的请求参数（与既有服务保持同名，便于复用）：
     * <ul>
     *   <li>{@code strategy} —— {@code UNIFIED} 时按 {@code unifiedStrategy} 选择策略配置，
     *       默认 {@code CLASSIC_SELECTOR}。</li>
     *   <li>{@code tradeDate} —— 目标交易日，缺省取库内最新交易日。</li>
     *   <li>{@code lookbackDays} —— K 线回看天数，默认 {@code ScoringConstants.DEFAULT_LOOKBACK_DAYS}。</li>
     *   <li>{@code topN} —— 返回条数上限。</li>
     *   <li>{@code excludeST} —— 是否剔除 ST，默认 true。</li>
     *   <li>{@code minAvgAmount} / {@code minListingDays} —— 流动性 / 上市时长闸。</li>
     *   <li>{@code includeContributions} —— 是否输出逐因子归因，默认 true。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> run(Map<String, Object> request) {
        long t0 = System.nanoTime();
        Map<String, Object> req = request != null ? request : Map.of();

        String unifiedStrategy = String.valueOf(
                req.getOrDefault("unifiedStrategy", req.getOrDefault("strategy", "CLASSIC_SELECTOR")));
        StrategyDefinition strategy = StrategyCatalog.byLegacyStrategyType(unifiedStrategy);
        if (strategy == null) {
            // 允许直接传策略代码（CLASSIC_SELECTOR / TREND / ...）
            strategy = StrategyCatalog.all().stream()
                    .filter(d -> d.code().equalsIgnoreCase(unifiedStrategy))
                    .findFirst()
                    .orElse(StrategyCatalog.classic());
        }

        LocalDate reqDate = ScoringUtils.parseDate(req.get("tradeDate"));
        LocalDate tradeDate = reqDate != null
                ? marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate)
                : marketDataRepository.findMaxTradeDate().orElse(LocalDate.now());

        PointInTimeUniverseProvider.UniverseView universe = pointInTimeUniverseProvider.loadAsOf(tradeDate);

        int lookbackDays = ScoringUtils.parseInt(req.get("lookbackDays"),
                ScoringConstants.DEFAULT_LOOKBACK_DAYS, 60, 2000);
        int topN = ScoringUtils.parseInt(req.get("topN"), 200, 1, 5000);
        boolean excludeST = ScoringUtils.parseBool(req.get("excludeST"), true);
        boolean includeContributions = ScoringUtils.parseBool(req.get("includeContributions"), true);
        double reqMinAvgAmount = ScoringUtils.parseDouble(req.get("minAvgAmount"),
                ScoringUtils.DEFAULT_MIN_AVG_AMOUNT, 0d, 1_000_000_000_000d);
        int minListingDays = ScoringUtils.parseInt(req.get("minListingDays"), 60, 0, 3650);

        // 策略级最小成交额与请求参数取较严者，避免请求参数把策略闸放松
        double effectiveMinAvgAmount = Math.max(reqMinAvgAmount, strategy.minAvgAmount());

        LocalDate rangeStart = tradeDate.minusDays(lookbackDays);
        List<MarketData> allBars = marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate);
        if (allBars.isEmpty()) {
            return emptyResult(strategy, tradeDate, universe, "无可用行情数据");
        }

        Map<String, List<MarketData>> grouped = new LinkedHashMap<>();
        for (MarketData m : allBars) {
            grouped.computeIfAbsent(key(m.getStockCode(), m.getExchangeType()),
                    k -> new ArrayList<>(600)).add(m);
        }

        Map<String, StockInfo> infoMap = universe.byKey();
        Map<String, StockFinancialSnapshot> snapshotMap =
                financialSnapshotLoader.loadLatestByStockKey(tradeDate);

        // ── 第一层：L1 特征计算（每只股票只算一次）──────────────────────────
        List<FeatureVector> features = new ArrayList<>(grouped.size());
        Map<String, String> tradabilityRejections = new LinkedHashMap<>();
        Map<String, Integer> tradabilityCounts = new ConcurrentHashMap<>();
        AtomicInteger tradabilityRejected = new AtomicInteger();
        AtomicInteger listingRejected = new AtomicInteger();

        for (Map.Entry<String, List<MarketData>> e : grouped.entrySet()) {
            String k = e.getKey();
            List<MarketData> bars = e.getValue();
            if (bars == null || bars.isEmpty()) {
                continue;
            }
            StockInfo info = infoMap.get(k);
            MarketData signalBar = bars.get(bars.size() - 1);

            // 上市时长闸（与既有服务同口径：用 list_date 与交易日比较）
            if (minListingDays > 0 && info != null && info.getListDate() != null) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(info.getListDate(), tradeDate);
                if (days < minListingDays) {
                    listingRejected.incrementAndGet();
                    tradabilityCounts.merge("LISTING_DAYS_TOO_SHORT", 1, Integer::sum);
                    continue;
                }
            }

            // 可交易性闸：沿用批次 A 的严格判定（含 ST / 停牌 / 涨跌停 / 缺 preClose）。
            // 用 8 参重载：它接收完整 bars 并由 ScoringUtils.resolvePreClose 从倒数第二根
            // 解析前收，避免此处重复实现「前收」口径（重复实现正是本次要消除的问题）。
            TradabilityCheck check = ScoringUtils.evaluateTradabilityStrict(
                    bars,
                    signalBar.getStockCode(),
                    signalBar.getExchangeType(),
                    info != null ? info.getStockName() : null,
                    info != null ? info.getStatus() : null,
                    excludeST,
                    true,
                    true);
            if (!check.isTradable()) {
                tradabilityRejected.incrementAndGet();
                tradabilityCounts.merge(check.code(), 1, Integer::sum);
                tradabilityRejections.putIfAbsent(k, check.code());
                continue;
            }

            String industry = info != null ? info.getIndustry() : null;
            // 财务快照传入 compute：基本面因子的可用性在 compute 内登记，
            // 事后 applyFinancials 只写值不登记（原实现即此缺陷）。
            StockFinancialSnapshot snap = snapshotMap.get(k);
            FactorCalculator.Outcome outcome = FactorCalculator.compute(
                    signalBar.getStockCode(),
                    signalBar.getExchangeType(),
                    info != null ? info.getStockName() : null,
                    tradeDate,
                    bars,
                    industry,
                    snap);

            features.add(outcome.features());
        }

        if (features.isEmpty()) {
            // 全部被拒时必须保留拒绝原因分布——这正是用户最需要看到「为什么没有结果」的时刻
            Map<String, Object> out = emptyResult(strategy, tradeDate, universe, "无可交易标的");
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) out.get("summary");
            summary.put("tradability_rejected", tradabilityRejected.get());
            summary.put("listing_days_rejected", listingRejected.get());
            summary.put("rejection_breakdown", new LinkedHashMap<>(tradabilityCounts));
            return out;
        }

        // ── 第二层：L2 + L3 + L4 横截面标准化与打分 ──────────────────────────
        // 注意：minAvgAmount 的「策略级」部分需要在进入引擎前重设到策略定义上，
        // 故这里用一个按本次请求覆盖了成交额闸的策略副本语义（引擎读 strategy.minAvgAmount()）。
        StrategyDefinition effective = withMinAvgAmount(strategy, effectiveMinAvgAmount);
        UnifiedFactorScoringEngine.Result result =
                UnifiedFactorScoringEngine.score(effective, features);

        List<Map<String, Object>> items = new ArrayList<>();
        for (UnifiedFactorScoringEngine.StockScore s : result.scores()) {
            if (!s.eligible()) {
                continue;
            }
            items.add(toItemMap(s, strategy, tradeDate, includeContributions));
        }

        // 排序：finalScore 降序（null 视为最低），并列时按代码稳定排序
        items.sort((a, b) -> {
            Double fa = asDouble(a.get("final_score"));
            Double fb = asDouble(b.get("final_score"));
            if (fa == null && fb == null) {
                return String.valueOf(a.get("stock_code")).compareTo(String.valueOf(b.get("stock_code")));
            }
            if (fa == null) return 1;
            if (fb == null) return -1;
            int c = Double.compare(fb, fa);
            return c != 0 ? c : String.valueOf(a.get("stock_code")).compareTo(String.valueOf(b.get("stock_code")));
        });

        List<Map<String, Object>> sliced = items.size() > topN ? new ArrayList<>(items.subList(0, topN)) : items;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("strategy", "UNIFIED");
        summary.put("unified_strategy", strategy.code());
        summary.put("display_name", strategy.displayName());
        summary.put("factor_version", strategy.factorVersion());
        summary.put("trade_date", tradeDate.toString());
        summary.put("universe_size", universe.universeSize());
        summary.put("candidate_count", features.size());
        summary.put("scored_count", result.rankedCount());
        summary.put("rejected_count", result.rejectedCount());
        summary.put("returned_count", sliced.size());
        summary.put("tradability_rejected", tradabilityRejected.get());
        summary.put("listing_days_rejected", listingRejected.get());
        summary.put("rejection_breakdown", new LinkedHashMap<>(tradabilityCounts));
        summary.put("factor_rejection_breakdown", result.rejectionBreakdown());
        summary.put("normalization_mode", result.normalization().neutralizationMode());
        summary.put("industry_neutralization_skipped",
                result.normalization().industryNeutralizationSkipped());
        summary.put("industry_neutralization_skip_reason",
                result.normalization().industryNeutralizationSkipReason());
        summary.put("market_cap_neutralization_skipped",
                result.normalization().marketCapNeutralizationSkipped());
        summary.put("market_cap_neutralization_skip_reason",
                result.normalization().marketCapNeutralizationSkipReason());
        summary.put("factor_availability_rates", toRateMap(result.factorAvailabilityRates()));
        summary.put("weights", toWeightMap(strategy));
        summary.put("missing_data_policy", strategy.missingDataPolicy().name());
        summary.put("min_available_weight_ratio", strategy.minAvailableWeightRatio());
        summary.put("min_bars", strategy.minBars());
        summary.put("min_avg_amount", effectiveMinAvgAmount);
        summary.put("exclude_st", excludeST);
        summary.put("universe_lineage", universe.lineage());
        signature(summary, strategy, result, tradeDate);
        summary.put("elapsed_ms", (System.nanoTime() - t0) / 1_000_000);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", summary);
        out.put("items", sliced);
        if (!tradabilityRejections.isEmpty()) {
            out.put("tradability_rejections", new LinkedHashMap<>(tradabilityCounts));
        }
        log.info("统一因子打分完成 strategy={} date={} candidates={} scored={} rejected={} topN={} elapsedMs={}",
                strategy.code(), tradeDate, features.size(), result.rankedCount(),
                result.rejectedCount(), sliced.size(), summary.get("elapsed_ms"));
        return out;
    }

    // ── 输出组装 ──────────────────────────────────────────────────────

    /**
     * 单只股票输出为一行。「字段名与旧路径对齐」是刻意的：
     * 前端与落库结构无需改动即可消费本路径的结果。
     */
    private Map<String, Object> toItemMap(UnifiedFactorScoringEngine.StockScore s,
                                          StrategyDefinition strategy,
                                          LocalDate tradeDate,
                                          boolean includeContributions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stock_code", s.stockCode());
        m.put("exchange_type", s.exchangeType());
        m.put("stock_name", s.stockName());
        m.put("trade_date", tradeDate.toString());
        m.put("strategy_type", strategy.legacyStrategyType());
        m.put("factor_version", strategy.factorVersion());
        // total_score 是前端与落库长期使用的列名，这里放横截面百分位（[0,100]，越大越好）
        m.put("total_score", s.finalScore());
        m.put("final_score", s.finalScore());
        m.put("factor_score", s.factorScore());
        m.put("available_weight_ratio", s.availableWeightRatio());
        m.put("available_factor_count", s.availableFactorCount());
        m.put("score_type", "rule_score");
        m.put("score_type_note", "rule_score 为规则打分，非上涨概率");

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("unified_strategy", strategy.code());
        extra.put("normalization_applied", true);
        extra.put("uses_cross_section", true);
        extra.put("missing_data_policy", strategy.missingDataPolicy().name());
        extra.put("available_weight_ratio", s.availableWeightRatio());
        if (!s.unavailableReasons().isEmpty()) {
            extra.put("unavailable_factor_reasons", new ArrayList<>(s.unavailableReasons()));
        }
        // 缺数据披露：本库 PE/PB/财务全缺，必须让下游一眼看到「哪些因子没参与」
        extra.put("missing_factor_count", s.contributions().size() - s.availableFactorCount());
        extra.put("data_quality", s.availableWeightRatio() >= 0.999 ? "FULL" : "PARTIAL_FACTORS");
        if (includeContributions) {
            extra.put("factor_contributions", toContributionList(s));
        }
        m.put("extra_metrics", extra);
        return m;
    }

    /** 逐因子归因：{factor, raw, pct, weight, contribution, unavailable_reason}。 */
    private List<Map<String, Object>> toContributionList(UnifiedFactorScoringEngine.StockScore s) {
        List<Map<String, Object>> list = new ArrayList<>(s.contributions().size());
        for (UnifiedFactorScoringEngine.Contribution c : s.contributions()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("factor", c.factor().name());
            cm.put("label", c.factor().label());
            cm.put("raw_value", c.rawValue());
            cm.put("percentile", c.percentile());
            cm.put("weight", c.weight());
            cm.put("contribution", c.contribution());
            cm.put("available", c.percentile() != null);
            if (c.unavailableReason() != null) {
                cm.put("unavailable_reason", c.unavailableReason());
            }
            list.add(cm);
        }
        return list;
    }

    private Map<String, Object> emptyResult(StrategyDefinition strategy, LocalDate tradeDate,
                                            PointInTimeUniverseProvider.UniverseView universe,
                                            String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("strategy", "UNIFIED");
        summary.put("unified_strategy", strategy.code());
        summary.put("factor_version", strategy.factorVersion());
        summary.put("trade_date", tradeDate.toString());
        summary.put("candidate_count", 0);
        summary.put("scored_count", 0);
        summary.put("returned_count", 0);
        summary.put("message", reason);
        summary.put("universe_lineage", universe.lineage());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("summary", summary);
        out.put("items", List.of());
        return out;
    }

    // ── 辅助 ──────────────────────────────────────────────────────────

    /**
     * 返回一个与入参策略等价、但最小成交额被覆盖的策略定义。
     * 引擎从 {@code StrategyDefinition} 读取该闸值，故必须以策略对象而非旁路参数传入。
     */
    private StrategyDefinition withMinAvgAmount(StrategyDefinition base, double minAvgAmount) {
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
                .legacyStrategyType(base.legacyStrategyType());
        if (base.requireMa20AboveMa60()) {
            b.requireMa20AboveMa60();
        }
        for (Map.Entry<FactorId, Double> e : base.weights().entrySet()) {
            b.weight(e.getKey(), e.getValue());
        }
        if (!base.requiredFactors().isEmpty()) {
            b.require(base.requiredFactors().toArray(new FactorId[0]));
        }
        return b.build();
    }

    private void signature(Map<String, Object> summary, StrategyDefinition strategy,
                           UnifiedFactorScoringEngine.Result result, LocalDate tradeDate) {
        summary.put("universe_fingerprint", String.join("|",
                "UNIFIED", strategy.code(), strategy.factorVersion(),
                tradeDate.toString(),
                "scored=" + result.rankedCount(),
                "rejected=" + result.rejectedCount(),
                "norm=" + result.normalization().neutralizationMode()));
    }

    private Map<String, Object> toRateMap(Map<FactorId, Double> rates) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<FactorId, Double> e : rates.entrySet()) {
            m.put(e.getKey().name(), e.getValue());
        }
        return m;
    }

    private Map<String, Object> toWeightMap(StrategyDefinition strategy) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<FactorId, Double> e : strategy.normalizedWeights().entrySet()) {
            m.put(e.getKey().name(), e.getValue());
        }
        return m;
    }

    private static String key(String stockCode, String exchangeType) {
        return stockCode + "|" + exchangeType;
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
