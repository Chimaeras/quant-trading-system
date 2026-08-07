package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.entity.SelectorTemplate;
import com.quant.entity.StockFinancialSnapshot;
import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.SelectorTemplateRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.service.scoring.ClassicFactorCalculator;
import com.quant.service.scoring.CodexShortCycleScoringEngine;
import com.quant.service.scoring.CrossSectionNeutralizer;
import com.quant.service.scoring.ExecutorLifecycle;
import com.quant.service.scoring.FactorCollinearityChecker;
import com.quant.service.scoring.FinancialSnapshotLoader;
import com.quant.service.scoring.ProgressiveResultWriter;
import com.quant.service.scoring.ScoringUtils;
import com.quant.service.scoring.StrategyFormulaRegistry;
import com.quant.service.scoring.model.CandidateScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockSelectorService extends com.quant.service.scoring.AbstractScoringService {
    private final MarketDataRepository marketDataRepository;
    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final StockInfoRepository stockInfoRepository;
    private final PointInTimeUniverseProvider pointInTimeUniverseProvider;
    private final SelectorTemplateRepository selectorTemplateRepository;
    private final ProgressiveResultWriter progressiveResultWriter;
    private final ClassicFactorCalculator classicFactorCalculator;
    private final CodexShortCycleScoringEngine codexShortCycleScoringEngine;
    private final FinancialSnapshotLoader financialSnapshotLoader;

    public Map<String, Object> runSelection(Map<String, Object> request) {
        LocalDate reqDate = parseDate(request.get("tradeDate"), LocalDate.now());
        LocalDate tradeDate = marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate);
        PointInTimeUniverseProvider.UniverseView universe = pointInTimeUniverseProvider.loadAsOf(tradeDate);
        boolean fullHistory = parseBool(request.get("fullHistory"), true);
        int lookbackDaysRequested = parseInt(request.get("lookbackDays"), 60, 20, 3650);
        int topN = parseInt(request.get("topN"), 10, 1, 500);
        double minAvgAmount = parseDouble(request.get("minAvgAmount"), 30000000d, 0d, 1_000_000_000_000d);
        double minTrendScore = parseDouble(request.get("minTrendScore"), 40d, 0d, 100d);
        String templateId = String.valueOf(request.getOrDefault("templateId", "")).trim();
        String factorVersion = String.valueOf(request.getOrDefault("factorVersion", "v1")).trim();
        boolean excludeST = parseBool(request.get("excludeST"), true);
        int minListingDays = parseInt(request.get("minListingDays"), 60, 0, 3650);
        String horizonProfile = parseHorizonProfile(request.get("horizonProfile"));
        boolean useCodexEngine = StrategyFormulaRegistry.HORIZON_SHORT_3_5D.equals(horizonProfile);
        if (useCodexEngine) {
            minListingDays = Math.max(minListingDays, CodexShortCycleScoringEngine.MIN_LISTING_DAYS_DEFAULT);
            factorVersion = CodexShortCycleScoringEngine.FORMULA_VERSION;
        }
        double minCodexScore = useCodexEngine
                ? parseDouble(request.get("minCodexScore"), CodexShortCycleScoringEngine.DEFAULT_MIN_CODEX_SCORE, 0d, 100d)
                : 0d;
        boolean persist = parseBool(request.get("persist"), true);
        boolean progressivePersist = persist && parseBool(request.get("progressivePersist"), false);
        String fixedResultId = String.valueOf(request.getOrDefault("resultId", "")).trim();
        boolean resumeMode = parseBool(request.get("resumeMode"), false);
        boolean bulkFetch = parseBool(request.get("bulkFetch"), true);
        int threads = parseInt(request.get("threads"),
                Math.max(1, Runtime.getRuntime().availableProcessors()), 1, 64);

        LocalDate rangeStart = fullHistory
                ? marketDataRepository.findMinTradeDate().orElse(tradeDate.minusDays(3650L))
                : tradeDate.minusDays(Math.max(lookbackDaysRequested + 30L, 90L));
        int effectiveLookbackDays = capCalendarDays(rangeStart, tradeDate);
        List<Object[]> pairs = marketDataRepository.findDistinctStockPairsInRange(rangeStart, tradeDate);
        String targetStockCode = String.valueOf(request.getOrDefault("stockCode", "")).trim();
        String targetExchangeType = String.valueOf(request.getOrDefault("exchangeType", "")).trim();
        if (!targetStockCode.isBlank()) {
            List<Object[]> exactPairs = pairs.stream()
                    .filter(p -> p != null
                            && p.length >= 2
                            && targetStockCode.equalsIgnoreCase(String.valueOf(p[0]))
                            && (targetExchangeType.isBlank() || targetExchangeType.equalsIgnoreCase(String.valueOf(p[1]))))
                    .toList();
            if (!exactPairs.isEmpty()) {
                pairs = exactPairs;
            } else {
                pairs = pairs.stream()
                        .filter(p -> p != null
                                && p.length >= 2
                                && targetStockCode.equalsIgnoreCase(String.valueOf(p[0])))
                        .toList();
            }
        }
        if (pairs.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("message", "无可用行情数据");
            empty.put("trade_date", tradeDate.toString());
            empty.put("items", List.of());
            empty.putAll(universe.lineage());
            return empty;
        }

        Map<String, Integer> statusByKey = new HashMap<>();
        Map<String, LocalDate> listDateByKey = new HashMap<>();
        Map<String, String> nameCache = new ConcurrentHashMap<>();
        Map<String, String> industryCache = new ConcurrentHashMap<>();
        for (StockInfo info : universe.stocks()) {
            if (info.getStockCode() == null || info.getExchangeType() == null) {
                continue;
            }
            String infoKey = info.getStockCode() + "|" + info.getExchangeType();
            statusByKey.put(infoKey, info.getStatus());
            listDateByKey.put(infoKey, info.getListDate());
            if (info.getStockName() != null) {
                nameCache.putIfAbsent(infoKey, info.getStockName());
            }
            if (info.getIndustry() != null) {
                industryCache.putIfAbsent(infoKey, info.getIndustry());
            }
        }
        pairs = pairs.stream()
                .filter(p -> p != null && p.length >= 2
                        && ScoringUtils.isActiveStockStatus(
                        statusByKey.get(String.valueOf(p[0]) + "|" + String.valueOf(p[1]))))
                .toList();
        if (pairs.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("message", "过滤非正常交易状态后无可用标的");
            empty.put("trade_date", tradeDate.toString());
            empty.put("items", List.of());
            empty.putAll(universe.lineage());
            return empty;
        }

        Map<String, List<MarketData>> bulkByKey = null;
        Map<String, LocalDate> earliestBarDateByKey = Map.of();
        if (bulkFetch && !pairs.isEmpty()) {
            List<MarketData> allBars = marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate);
            bulkByKey = new HashMap<>(Math.max(16, pairs.size() * 2));
            for (MarketData m : allBars) {
                if (m.getStockCode() == null || m.getExchangeType() == null) continue;
                bulkByKey.computeIfAbsent(m.getStockCode() + "|" + m.getExchangeType(), k -> new ArrayList<>()).add(m);
            }
        } else if (minListingDays > 0 && !pairs.isEmpty()) {
            earliestBarDateByKey = ScoringUtils.mapEarliestTradeDatesByKey(
                    marketDataRepository.findEarliestTradeDatesInRange(rangeStart, tradeDate));
        }

        if (minListingDays > 0) {
            final int listingDaysThreshold = minListingDays;
            final Map<String, List<MarketData>> bulkRefForListing = bulkByKey;
            final Map<String, LocalDate> earliestRefForListing = earliestBarDateByKey;
            pairs = pairs.stream()
                    .filter(p -> {
                        if (p == null || p.length < 2) {
                            return false;
                        }
                        String key = String.valueOf(p[0]) + "|" + String.valueOf(p[1]);
                        List<MarketData> bars = ScoringUtils.listingTenureBars(
                                bulkRefForListing != null ? bulkRefForListing.get(key) : null,
                                earliestRefForListing.get(key));
                        return ScoringUtils.hasMinListingTenure(
                                listDateByKey.get(key), tradeDate, listingDaysThreshold, bars);
                    })
                    .toList();
            if (pairs.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("message", "过滤上市时长后无可用标的");
                empty.put("trade_date", tradeDate.toString());
                empty.put("min_listing_days", minListingDays);
                empty.put("items", List.of());
                empty.putAll(universe.lineage());
                return empty;
            }
        }

        if (useCodexEngine) {
            Map<String, Object> codexResult = runCodexShortCycleSelection(
                    tradeDate,
                    effectiveLookbackDays,
                    fullHistory,
                    lookbackDaysRequested,
                    rangeStart,
                    topN,
                    minAvgAmount,
                    minTrendScore,
                    templateId,
                    factorVersion,
                    bulkFetch,
                    excludeST,
                    minListingDays,
                    horizonProfile,
                    persist,
                    progressivePersist,
                    fixedResultId,
                    resumeMode,
                    pairs,
                    bulkByKey,
                    nameCache,
                    industryCache,
                    statusByKey,
                    listDateByKey,
                    minCodexScore
            );
            if (codexResult != null) {
                codexResult.putAll(universe.lineage());
            }
            return codexResult;
        }

        boolean crossSectionEnabled = ScoringUtils.parseBool(request.get("crossSectionNormalize"), false);
        List<CandidateScore> candidates = new ArrayList<>();
        int scoredCount = 0;
        String progressiveResultId = null;
        if (progressivePersist) {
            progressiveResultId = ensureResultSkeleton(
                    fixedResultId,
                    tradeDate,
                    effectiveLookbackDays,
                    fullHistory,
                    lookbackDaysRequested,
                    rangeStart,
                    topN,
                    minAvgAmount,
                    minTrendScore,
                    templateId,
                    factorVersion,
                    bulkFetch,
                    resumeMode,
                    excludeST,
                    minListingDays,
                    horizonProfile
            );
        }

        int processedCount = 0;
        int failedCount = 0;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r);
            t.setName("classic-selector-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        CompletionService<Optional<CandidateScore>> cs = new ExecutorCompletionService<>(pool);
        final Map<String, List<MarketData>> bulkRef = bulkByKey;
        final String horizonProfileFinal = horizonProfile;
        final Map<String, Integer> statusRef = statusByKey;
        final Map<String, LocalDate> listDateRef = listDateByKey;
        final int minListingDaysFinal = minListingDays;
        int submitted = 0;
        for (Object[] pair : pairs) {
            if (pair == null || pair.length < 2) continue;
            String stockCode = String.valueOf(pair[0]);
            String exchangeType = String.valueOf(pair[1]);
            submitted++;
            cs.submit(() -> {
                ScoringUtils.checkInterrupted();
                List<MarketData> bars;
                if (bulkRef != null) {
                    bars = bulkRef.getOrDefault(stockCode + "|" + exchangeType, List.of());
                } else {
                    bars = marketDataRepository
                            .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(stockCode, exchangeType, rangeStart, tradeDate);
                }
                return classicFactorCalculator.calculate(
                        stockCode, exchangeType, bars, minAvgAmount, minTrendScore,
                        k -> nameCache.computeIfAbsent(stockCode + "|" + exchangeType,
                                kk -> getStockName(stockCode, exchangeType)),
                        excludeST,
                        horizonProfileFinal,
                        tradeDate,
                        statusRef.get(stockCode + "|" + exchangeType),
                        listDateRef.get(stockCode + "|" + exchangeType),
                        minListingDaysFinal);
            });
        }

        try {
            for (int i = 0; i < submitted; i++) {
                ensureNotInterrupted();
                Optional<CandidateScore> opt;
                try {
                    opt = cs.take().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("任务被取消（线程中断）", e);
                } catch (ExecutionException e) {
                    opt = Optional.empty();
                }
                processedCount++;
                if (opt == null || opt.isEmpty()) {
                    failedCount++;
                    if (progressiveResultId != null) {
                        progressiveResultWriter.updateProgress(
                                progressiveResultId,
                                targetsSize(pairs),
                                processedCount,
                                scoredCount,
                                failedCount,
                                ""
                        );
                    }
                    continue;
                }
                CandidateScore c = opt.get();
                scoredCount++;
                String stockKey = c.getStockCode() + "|" + c.getExchangeType();
                candidates.add(c);
                if (progressiveResultId != null) {
                    progressiveResultWriter.updateProgress(
                            progressiveResultId,
                            targetsSize(pairs),
                            processedCount,
                            scoredCount,
                            failedCount,
                            stockKey
                    );
                }
            }
        } finally {
            ExecutorLifecycle.shutdownGracefully(pool);
        }

        if (crossSectionEnabled && candidates.size() >= 2) {
            classicFactorCalculator.normalizeCrossSection(candidates, 1e-6);
        }
        FactorCollinearityChecker.logVifReport(candidates);
        candidates.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));

        if (!persist) {
            Map<String, Object> ephemeral = buildEphemeralResult(
                    tradeDate, factorVersion, topN, candidates, targetsSize(pairs), "CLASSIC_SELECTOR");
            ephemeral.putAll(universe.lineage());
            return ephemeral;
        }

        String finalResultId = progressiveResultId != null
                ? progressiveResultId
                : ensureResultSkeleton(
                null,
                tradeDate,
                effectiveLookbackDays,
                fullHistory,
                lookbackDaysRequested,
                rangeStart,
                topN,
                minAvgAmount,
                minTrendScore,
                templateId,
                factorVersion,
                bulkFetch,
                false,
                excludeST,
                minListingDays,
                horizonProfile
        );
        List<SelectorResultItem> persistedItems = new ArrayList<>(candidates.size());
        int rk = 1;
        for (CandidateScore c : candidates) {
            persistedItems.add(c.toSelectorResultItem(finalResultId, rk++, "CLASSIC_SELECTOR"));
        }
        if (progressiveResultId != null) {
            progressiveResultWriter.replaceResultItems(finalResultId, persistedItems);
        } else {
            selectorResultItemRepository.deleteByResultId(finalResultId);
            for (SelectorResultItem item : persistedItems) {
                selectorResultItemRepository.save(item);
            }
        }
        enrichFactorSnapshot(finalResultId, persistedItems.size());
        int totalTargets = targetsSize(pairs);
        int finalScoredCount = candidates.size();
        progressiveResultWriter.finishResult(
                finalResultId,
                "SUCCESS",
                totalTargets,
                totalTargets,
                finalScoredCount,
                Math.max(0, totalTargets - finalScoredCount)
        );
        Map<String, Object> result = getResultById(finalResultId);
        if (result != null) {
            result.putAll(universe.lineage());
        }
        return result;
    }

    /** persist=false 时返回内存结果，不写 selector_result（供历史信号重放）。 */
    private Map<String, Object> buildEphemeralResult(
            LocalDate tradeDate,
            String factorVersion,
            int topN,
            List<CandidateScore> candidates,
            int scanned,
            String strategyType
    ) {
        List<Map<String, Object>> items = new ArrayList<>();
        int limit = Math.min(topN > 0 ? topN : candidates.size(), candidates.size());
        for (int i = 0; i < limit; i++) {
            CandidateScore c = candidates.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank_no", i + 1);
            item.put("stock_code", c.getStockCode());
            item.put("exchange_type", c.getExchangeType());
            item.put("stock_name", c.getStockName() != null ? c.getStockName() : "");
            item.put("final_score", c.effectiveFinalScore());
            item.put("trend_score", c.getTrendScore());
            item.put("momentum_score", c.getMomentumScore());
            item.put("volume_score", c.getVolumeScore());
            item.put("risk_penalty", c.getRiskPenalty());
            item.put("reasons", c.getReasons() != null ? c.getReasons() : Map.of());
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", tradeDate.toString());
        out.put("strategy_type", strategyType);
        out.put("factor_version", factorVersion);
        out.put("top_n", topN);
        out.put("scanned", scanned);
        out.put("scored", candidates.size());
        out.put("cross_section_count", candidates.size());
        out.put("persisted", false);
        out.put("items", items);
        return out;
    }

    private void enrichFactorSnapshot(String resultId, int crossSectionCount) {
        selectorResultRepository.findByResultId(resultId).ifPresent(result -> {
            Map<String, Object> snap = result.getFactorSnapshot() != null
                    ? new LinkedHashMap<>(result.getFactorSnapshot())
                    : new LinkedHashMap<>();
            snap.put("cross_section_count", crossSectionCount);
            snap.put("display_top_n", result.getTopN());
            result.setFactorSnapshot(snap);
            selectorResultRepository.save(result);
        });
    }

    private int targetsSize(List<Object[]> pairs) {
        return pairs == null ? 0 : pairs.size();
    }

    public Map<String, Object> getLatestResult(LocalDate tradeDate, String factorVersion) {
        LocalDate target = tradeDate != null ? tradeDate : marketDataRepository.findMaxTradeDate().orElse(LocalDate.now());
        SelectorResult r;
        if (factorVersion != null && !factorVersion.isBlank()) {
            r = selectorResultRepository.findFirstByTradeDateAndFactorVersionOrderByCreatedAtDesc(target, factorVersion.trim()).orElse(null);
        } else {
            r = selectorResultRepository.findFirstByTradeDateOrderByCreatedAtDesc(target).orElse(null);
        }
        if (r == null) {
            return Map.of("trade_date", target.toString(), "items", List.of(), "message", "当日暂无选股结果");
        }
        return toResultMap(r);
    }

    public Map<String, Object> getResultById(String resultId) {
        var r = selectorResultRepository.findByResultId(resultId).orElse(null);
        if (r == null) return null;
        return toResultMap(r);
    }

    private Map<String, Object> toResultMap(SelectorResult r) {
        List<SelectorResultItem> allItems = selectorResultItemRepository.findByResultIdOrderByRankNoAsc(r.getResultId());
        int displayTopN = r.getTopN() != null && r.getTopN() > 0 ? r.getTopN() : allItems.size();
        List<SelectorResultItem> items = allItems.size() <= displayTopN
                ? allItems
                : allItems.subList(0, displayTopN);
        List<Map<String, Object>> outItems = new ArrayList<>();
        String fv = r.getFactorVersion() != null ? r.getFactorVersion() : "";
        for (SelectorResultItem i : items) {
            // 注意：Map.of 最多只能接收 10 对 key/value，这里字段较多改用可变 Map。
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank_no", i.getRankNo());
            item.put("stock_code", i.getStockCode());
            item.put("exchange_type", i.getExchangeType());
            item.put("stock_name", i.getStockName() != null ? i.getStockName() : "");
            item.put("final_score", i.getFinalScore());
            item.put("trend_score", i.getTrendScore());
            item.put("momentum_score", i.getMomentumScore());
            item.put("volume_score", i.getVolumeScore());
            item.put("risk_penalty", i.getRiskPenalty());
            item.put("ret20", i.getRet20());
            item.put("ret60", i.getRet60());
            item.put("volatility20", i.getVolatility20());
            item.put("max_drawdown60", i.getMaxDrawdown60());
            item.put("avg_amount20", i.getAvgAmount20());
            item.put("reasons", i.getReasons() != null ? i.getReasons() : Map.of());
            if (fv.startsWith("oversold_rebound") && i.getReasons() instanceof Map<?, ?> rs) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rm = (Map<String, Object>) rs;
                item.put("trend_score_40", rm.get("trend_score_40"));
                item.put("volume_score_30", rm.get("volume_score_30"));
                item.put("oversold_score_30", rm.get("oversold_score_30"));
                item.put("weighted_score", rm.get("weighted_score"));
                if (rm.get("detail") != null) item.put("detail", rm.get("detail"));
            }
            outItems.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result_id", r.getResultId());
        out.put("trade_date", r.getTradeDate().toString());
        out.put("lookback_days", r.getLookbackDays());
        out.put("top_n", r.getTopN());
        out.put("cross_section_count", allItems.size());
        out.put("min_avg_amount", r.getMinAvgAmount());
        out.put("min_trend_score", r.getMinTrendScore());
        out.put("template_id", r.getTemplateId());
        out.put("factor_version", r.getFactorVersion());
        out.put("factor_snapshot", r.getFactorSnapshot() != null ? r.getFactorSnapshot() : Map.of());
        out.put("created_at", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        out.put("items", outItems);
        return out;
    }

    public List<Map<String, Object>> listTemplates() {
        return selectorTemplateRepository.findAll().stream().map(t -> Map.of(
                "template_id", t.getTemplateId(),
                "name", t.getName(),
                "params", t.getParams() != null ? t.getParams() : Map.of(),
                "description", t.getDescription() != null ? t.getDescription() : "",
                "updated_at", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : ""
        )).toList();
    }

    @Transactional
    public Map<String, Object> saveTemplate(Map<String, Object> request) {
        String templateId = String.valueOf(request.getOrDefault("templateId", "")).trim();
        if (templateId.isBlank()) templateId = "TPL" + System.currentTimeMillis();
        String name = String.valueOf(request.getOrDefault("name", "默认模板")).trim();
        String description = String.valueOf(request.getOrDefault("description", "")).trim();
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());

        SelectorTemplate t = selectorTemplateRepository.findByTemplateId(templateId).orElse(new SelectorTemplate());
        t.setTemplateId(templateId);
        t.setName(name.isBlank() ? templateId : name);
        t.setDescription(description);
        t.setParams(params);
        selectorTemplateRepository.save(t);
        return Map.of("template_id", t.getTemplateId(), "name", t.getName());
    }

    @Transactional
    public boolean deleteTemplate(String templateId) {
        var t = selectorTemplateRepository.findByTemplateId(templateId).orElse(null);
        if (t == null) return false;
        selectorTemplateRepository.delete(t);
        return true;
    }

    private Map<String, Object> buildFactorSnapshot(
            double minAvgAmount,
            double minTrendScore,
            boolean fullHistory,
            int lookbackDaysRequested,
            LocalDate rangeStart,
            boolean bulkFetch,
            boolean excludeST,
            int minListingDays,
            String horizonProfile
    ) {
        boolean shortProfile = StrategyFormulaRegistry.HORIZON_SHORT_3_5D.equals(horizonProfile);
        boolean codexProfile = shortProfile;
        Map<String, Object> weights = codexProfile
                ? Map.of(
                "T_weight", "0.55+0.10G",
                "F_weight", 0.20,
                "R_weight", "0.25-0.05G",
                "T_F_interaction", "0.05·T·tanh(F)")
                : Map.of("trend", 0.35, "momentum", 0.20, "volume", 0.20, "risk", -0.25);
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("min_avg_amount", minAvgAmount);
        thresholds.put("min_trend_score", minTrendScore);
        thresholds.put("must_ma20_gt_ma60", !shortProfile);
        thresholds.put("min_short_horizon_score", shortProfile && !codexProfile ? 35 : null);
        thresholds.put("codex_min_score", codexProfile ? CodexShortCycleScoringEngine.DEFAULT_MIN_CODEX_SCORE : null);
        thresholds.put("codex_min_f", codexProfile ? CodexShortCycleScoringEngine.MIN_F_FACTOR : null);
        thresholds.put("codex_min_r", codexProfile ? CodexShortCycleScoringEngine.MIN_R_FACTOR : null);
        thresholds.put("min_codex_score", codexProfile ? CodexShortCycleScoringEngine.DEFAULT_MIN_CODEX_SCORE : null);
        thresholds.put("horizon_profile", horizonProfile);
        thresholds.put("full_history", fullHistory);
        thresholds.put("lookback_days_requested", lookbackDaysRequested);
        thresholds.put("range_start", rangeStart != null ? rangeStart.toString() : "");
        thresholds.put("bulk_fetch", bulkFetch);
        thresholds.put("exclude_st", excludeST);
        thresholds.put("min_listing_days", minListingDays);
        thresholds.put("ranking", codexProfile
                ? "percentile_rank_S_raw_then_F_R_hard_filter"
                : "all_stocks_by_final_score_then_top_n");
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("weights", weights);
        snap.put("thresholds", thresholds);
        snap.put("tradability", ScoringUtils.tradabilitySnapshot(excludeST, true, minListingDays, bulkFetch));
        if (codexProfile) {
            Map<String, Object> preprocessing = new LinkedHashMap<>();
            preprocessing.put("pipeline", "winsorize(2.5/97.5) → OLS(lnMV+industry) → robustZ(MAD)");
            preprocessing.put("formula_version", CodexShortCycleScoringEngine.FORMULA_VERSION);
            preprocessing.put("log_mv_source", "turnover/amount proxy or avgAmount20 fallback");
            preprocessing.put("industry_er", "value-weighted industry index (§5.1)");
            preprocessing.put("g_market_weighting", "exp(lnMV) weighted er20m5/maAlign/vol20");
            preprocessing.put("min_sample_full_regression", CrossSectionNeutralizer.MIN_SAMPLE_FOR_FULL_REGRESSION);
            preprocessing.put("min_industries_regression", CrossSectionNeutralizer.MIN_INDUSTRIES_FOR_REGRESSION);
            preprocessing.put("fallback_mode", CrossSectionNeutralizer.MODE_INDUSTRY_DEMEAN);
            preprocessing.put("log_mv_only_mode", CrossSectionNeutralizer.MODE_LOG_MV_ONLY);
            preprocessing.put("full_regression_mode", CrossSectionNeutralizer.MODE_FULL);
            snap.put("preprocessing", preprocessing);
        }
        String auditFactorVersion = codexProfile
                ? CodexShortCycleScoringEngine.FORMULA_VERSION
                : ClassicFactorCalculator.FACTOR_VERSION;
        snap.put("formula_audit", StrategyFormulaRegistry.toMap(
                StrategyFormulaRegistry.audit("CLASSIC_SELECTOR", auditFactorVersion, horizonProfile)));
        return snap;
    }

    private Map<String, Object> runCodexShortCycleSelection(
            LocalDate tradeDate,
            int effectiveLookbackDays,
            boolean fullHistory,
            int lookbackDaysRequested,
            LocalDate rangeStart,
            int topN,
            double minAvgAmount,
            double minTrendScore,
            String templateId,
            String factorVersion,
            boolean bulkFetch,
            boolean excludeST,
            int minListingDays,
            String horizonProfile,
            boolean persist,
            boolean progressivePersist,
            String fixedResultId,
            boolean resumeMode,
            List<Object[]> pairs,
            Map<String, List<MarketData>> bulkByKey,
            Map<String, String> nameCache,
            Map<String, String> industryCache,
            Map<String, Integer> statusByKey,
            Map<String, LocalDate> listDateByKey,
            double minCodexScore
    ) {
        Map<String, StockFinancialSnapshot> finByKey =
                financialSnapshotLoader.loadLatestByStockKey(tradeDate);
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = new ArrayList<>(pairs.size());
        for (Object[] pair : pairs) {
            if (pair == null || pair.length < 2) {
                continue;
            }
            String stockCode = String.valueOf(pair[0]);
            String exchangeType = String.valueOf(pair[1]);
            String key = stockCode + "|" + exchangeType;
            List<MarketData> bars;
            if (bulkByKey != null) {
                bars = bulkByKey.getOrDefault(key, List.of());
            } else {
                bars = marketDataRepository
                        .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
                                stockCode, exchangeType, rangeStart, tradeDate);
            }
            contexts.add(new CodexShortCycleScoringEngine.StockBarContext(
                    stockCode,
                    exchangeType,
                    nameCache.getOrDefault(key, getStockName(stockCode, exchangeType)),
                    industryCache.getOrDefault(key, ""),
                    bars,
                    finByKey.get(key),
                    tradeDate,
                    listDateByKey.get(key),
                    statusByKey.get(key)
            ));
        }

        String progressiveResultId = null;
        if (progressivePersist) {
            progressiveResultId = ensureResultSkeleton(
                    fixedResultId,
                    tradeDate,
                    effectiveLookbackDays,
                    fullHistory,
                    lookbackDaysRequested,
                    rangeStart,
                    topN,
                    minAvgAmount,
                    minTrendScore,
                    templateId,
                    factorVersion,
                    bulkFetch,
                    resumeMode,
                    excludeST,
                    minListingDays,
                    horizonProfile
            );
        }

        List<CandidateScore> candidates = codexShortCycleScoringEngine.scoreUniverse(
                contexts, minAvgAmount, excludeST, minListingDays, minCodexScore, minTrendScore);
        FactorCollinearityChecker.logVifReport(candidates);
        candidates.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));

        if (!persist) {
            return buildEphemeralResult(
                    tradeDate, factorVersion, topN, candidates, targetsSize(pairs), "CLASSIC_SELECTOR");
        }

        String finalResultId = progressiveResultId != null
                ? progressiveResultId
                : ensureResultSkeleton(
                null,
                tradeDate,
                effectiveLookbackDays,
                fullHistory,
                lookbackDaysRequested,
                rangeStart,
                topN,
                minAvgAmount,
                minTrendScore,
                templateId,
                factorVersion,
                bulkFetch,
                false,
                excludeST,
                minListingDays,
                horizonProfile
        );
        List<SelectorResultItem> persistedItems = new ArrayList<>(candidates.size());
        int rk = 1;
        for (CandidateScore c : candidates) {
            persistedItems.add(c.toSelectorResultItem(finalResultId, rk++, "CLASSIC_SELECTOR"));
        }
        if (progressiveResultId != null) {
            progressiveResultWriter.replaceResultItems(finalResultId, persistedItems);
        } else {
            selectorResultItemRepository.deleteByResultId(finalResultId);
            for (SelectorResultItem item : persistedItems) {
                selectorResultItemRepository.save(item);
            }
        }
        enrichFactorSnapshot(finalResultId, persistedItems.size());
        int totalTargets = targetsSize(pairs);
        int scoredCount = candidates.size();
        progressiveResultWriter.finishResult(
                finalResultId,
                "SUCCESS",
                totalTargets,
                totalTargets,
                scoredCount,
                Math.max(0, totalTargets - scoredCount)
        );
        return getResultById(finalResultId);
    }

    private static int capCalendarDays(LocalDate start, LocalDate end) {
        if (start == null || end == null) return 0;
        long d = ChronoUnit.DAYS.between(start, end);
        if (d < 0) {
            d = 0;
        }
        if (d > Integer.MAX_VALUE - 2) {
            return Integer.MAX_VALUE - 2;
        }
        return (int) d;
    }

    private String ensureResultSkeleton(
            String resultId,
            LocalDate tradeDate,
            int effectiveLookbackDays,
            boolean fullHistory,
            int lookbackDaysRequested,
            LocalDate rangeStart,
            int topN,
            double minAvgAmount,
            double minTrendScore,
            String templateId,
            String factorVersion,
            boolean bulkFetch,
            boolean resumeMode,
            boolean excludeST,
            int minListingDays,
            String horizonProfile
    ) {
        String rid = (resultId != null && !resultId.isBlank()) ? resultId : genResultId();
        SelectorResult result = selectorResultRepository.findByResultId(rid).orElse(new SelectorResult());
        result.setResultId(rid);
        result.setTradeDate(tradeDate);
        result.setLookbackDays(effectiveLookbackDays);
        result.setTopN(topN);
        result.setMinAvgAmount(minAvgAmount);
        result.setMinTrendScore(minTrendScore);
        result.setTemplateId(templateId.isBlank() ? null : templateId);
        result.setFactorVersion(factorVersion.isBlank() ? "v1" : factorVersion);
        result.setStrategyType("CLASSIC_SELECTOR");
        result.setRunStatus("RUNNING");
        result.setTotalCandidates(0);
        result.setProcessedCandidates(0);
        result.setSuccessCandidates(0);
        result.setFailedCandidates(0);
        result.setLastProcessedKey("");
        result.setFactorSnapshot(buildFactorSnapshot(
                minAvgAmount, minTrendScore, fullHistory, lookbackDaysRequested, rangeStart, bulkFetch, excludeST, minListingDays, horizonProfile));
        progressiveResultWriter.upsertRunningResult(
                rid,
                result,
                resultId != null && !resultId.isBlank() && !resumeMode
        );
        return rid;
    }

    private String getStockName(String stockCode, String exchangeType) {
        Optional<StockInfo> exact = stockInfoRepository.findByStockCodeAndExchangeType(stockCode, exchangeType);
        if (exact.isPresent()) return exact.get().getStockName();
        return stockInfoRepository.findFirstByStockCode(stockCode).map(StockInfo::getStockName).orElse(stockCode);
    }

    private static String parseHorizonProfile(Object v) {
        if (v == null) {
            return StrategyFormulaRegistry.HORIZON_MEDIUM_TREND;
        }
        String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
        if ("SHORT_3_5D".equals(s) || "SHORT".equals(s) || "3_5D".equals(s)) {
            return StrategyFormulaRegistry.HORIZON_SHORT_3_5D;
        }
        return StrategyFormulaRegistry.HORIZON_MEDIUM_TREND;
    }

    private LocalDate parseDate(Object v, LocalDate def) {
        if (v == null) return def;
        try {
            return LocalDate.parse(String.valueOf(v));
        } catch (Exception ignore) {
            return def;
        }
    }

    private int parseInt(Object v, int def, int min, int max) {
        try {
            int n = v == null ? def : Integer.parseInt(String.valueOf(v));
            return Math.max(min, Math.min(max, n));
        } catch (Exception ignore) {
            return def;
        }
    }

    private double parseDouble(Object v, double def, double min, double max) {
        try {
            double d = v == null ? def : Double.parseDouble(String.valueOf(v));
            return Math.max(min, Math.min(max, d));
        } catch (Exception ignore) {
            return def;
        }
    }

    private boolean parseBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isBlank()) return def;
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "y".equals(s);
    }

    private String genResultId() {
        return "SC" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private void ensureNotInterrupted() {
        ScoringUtils.checkInterrupted();
    }
}
