package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.entity.SelectorTemplate;
import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.SelectorTemplateRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.service.scoring.ClassicFactorCalculator;
import com.quant.service.scoring.ExecutorLifecycle;
import com.quant.service.scoring.FactorCollinearityChecker;
import com.quant.service.scoring.ProgressiveResultWriter;
import com.quant.service.scoring.ScoringUtils;
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
    private final SelectorTemplateRepository selectorTemplateRepository;
    private final ProgressiveResultWriter progressiveResultWriter;
    private final ClassicFactorCalculator classicFactorCalculator;

    public Map<String, Object> runSelection(Map<String, Object> request) {
        LocalDate reqDate = parseDate(request.get("tradeDate"), LocalDate.now());
        LocalDate tradeDate = marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate);
        boolean fullHistory = parseBool(request.get("fullHistory"), true);
        int lookbackDaysRequested = parseInt(request.get("lookbackDays"), 60, 20, 3650);
        int topN = parseInt(request.get("topN"), 10, 1, 500);
        double minAvgAmount = parseDouble(request.get("minAvgAmount"), 30000000d, 0d, 1_000_000_000_000d);
        double minTrendScore = parseDouble(request.get("minTrendScore"), 40d, 0d, 100d);
        String templateId = String.valueOf(request.getOrDefault("templateId", "")).trim();
        String factorVersion = String.valueOf(request.getOrDefault("factorVersion", "v1")).trim();
        boolean excludeST = parseBool(request.get("excludeST"), true);
        boolean progressivePersist = parseBool(request.get("progressivePersist"), false);
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
            return Map.of("message", "无可用行情数据", "trade_date", tradeDate.toString(), "items", List.of());
        }

        Map<String, List<MarketData>> bulkByKey = null;
        if (bulkFetch && !pairs.isEmpty()) {
            List<MarketData> allBars = marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate);
            bulkByKey = new HashMap<>(Math.max(16, pairs.size() * 2));
            for (MarketData m : allBars) {
                if (m.getStockCode() == null || m.getExchangeType() == null) continue;
                bulkByKey.computeIfAbsent(m.getStockCode() + "|" + m.getExchangeType(), k -> new ArrayList<>()).add(m);
            }
        }

        Map<String, String> nameCache = new HashMap<>();
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
                    excludeST
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
                        k -> nameCache.computeIfAbsent(k, kk -> getStockName(stockCode, exchangeType)),
                        excludeST);
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
        List<CandidateScore> selected = candidates.stream().limit(topN).toList();

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
                excludeST
        );
        List<SelectorResultItem> topItems = new ArrayList<>(selected.size());
        int rk = 1;
        for (CandidateScore c : selected) {
            topItems.add(c.toSelectorResultItem(finalResultId, rk++, "CLASSIC_SELECTOR"));
        }
        if (progressiveResultId != null) {
            progressiveResultWriter.replaceResultItems(finalResultId, topItems);
        } else {
            selectorResultItemRepository.deleteByResultId(finalResultId);
            for (SelectorResultItem item : topItems) {
                selectorResultItemRepository.save(item);
            }
        }
        int totalTargets = targetsSize(pairs);
        int finalScoredCount = scoredCount;
        progressiveResultWriter.finishResult(
                finalResultId,
                "SUCCESS",
                totalTargets,
                totalTargets,
                finalScoredCount,
                Math.max(0, totalTargets - finalScoredCount)
        );
        return getResultById(finalResultId);
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
        List<SelectorResultItem> items = selectorResultItemRepository.findByResultIdOrderByRankNoAsc(r.getResultId());
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
            boolean excludeST
    ) {
        Map<String, Object> weights = Map.of(
                "trend", 0.4,
                "momentum", 0.25,
                "volume", 0.2,
                "risk", -0.15
        );
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("min_avg_amount", minAvgAmount);
        thresholds.put("min_trend_score", minTrendScore);
        thresholds.put("must_ma20_gt_ma60", true);
        thresholds.put("full_history", fullHistory);
        thresholds.put("lookback_days_requested", lookbackDaysRequested);
        thresholds.put("range_start", rangeStart != null ? rangeStart.toString() : "");
        thresholds.put("bulk_fetch", bulkFetch);
        thresholds.put("exclude_st", excludeST);
        thresholds.put("ranking", "all_stocks_by_final_score_then_top_n");
        return Map.of("weights", weights, "thresholds", thresholds);
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
            boolean excludeST
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
                minAvgAmount, minTrendScore, fullHistory, lookbackDaysRequested, rangeStart, bulkFetch, excludeST));
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
