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
import com.quant.service.scoring.ProgressiveResultWriter;
import com.quant.service.scoring.ScoringUtils;
import com.quant.service.scoring.TechnicalIndicators;
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
public class StockSelectorService {
    private final MarketDataRepository marketDataRepository;
    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final StockInfoRepository stockInfoRepository;
    private final SelectorTemplateRepository selectorTemplateRepository;
    private final ProgressiveResultWriter progressiveResultWriter;

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
        List<Candidate> candidates = new ArrayList<>();
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
                    resumeMode
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
        CompletionService<Candidate> cs = new ExecutorCompletionService<>(pool);
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
                return buildCandidate(stockCode, exchangeType, bars, minAvgAmount, minTrendScore, nameCache);
            });
        }

        try {
            for (int i = 0; i < submitted; i++) {
                ensureNotInterrupted();
                Candidate c;
                try {
                    c = cs.take().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("任务被取消（线程中断）", e);
                } catch (ExecutionException e) {
                    c = null;
                }
                processedCount++;
                if (c == null) {
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
                scoredCount++;
                String stockKey = c.stockCode + "|" + c.exchangeType;
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
            pool.shutdownNow();
        }

        candidates.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));
        List<Candidate> selected = candidates.stream().limit(topN).toList();

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
                false
        );
        List<SelectorResultItem> topItems = new ArrayList<>(selected.size());
        int rk = 1;
        for (Candidate c : selected) {
            topItems.add(toItem(finalResultId, rk++, c));
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
            boolean bulkFetch
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
            boolean resumeMode
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
        result.setFactorSnapshot(buildFactorSnapshot(minAvgAmount, minTrendScore, fullHistory, lookbackDaysRequested, rangeStart, bulkFetch));
        progressiveResultWriter.upsertRunningResult(
                rid,
                result,
                resultId != null && !resultId.isBlank() && !resumeMode
        );
        return rid;
    }

    private SelectorResultItem toItem(String resultId, int rankNo, Candidate c) {
        SelectorResultItem item = new SelectorResultItem();
        item.setResultId(resultId);
        item.setRankNo(rankNo);
        item.setStrategyType("CLASSIC_SELECTOR");
        item.setStockCode(c.stockCode);
        item.setExchangeType(c.exchangeType);
        item.setStockName(c.stockName);
        item.setFinalScore(round2(c.finalScore));
        item.setTrendScore(round2(c.trendScore));
        item.setMomentumScore(round2(c.momentumScore));
        item.setVolumeScore(round2(c.volumeScore));
        item.setRiskPenalty(round2(c.riskPenalty));
        item.setRet20(round2(c.ret20));
        item.setRet60(round2(c.ret60));
        item.setVolatility20(round2(c.volatility20));
        item.setMaxDrawdown60(round2(c.maxDrawdown60));
        item.setAvgAmount20(round2(c.avgAmount20));
        item.setReasons(c.reasons);
        return item;
    }

    private Candidate buildCandidate(String stockCode, String exchangeType, List<MarketData> bars, double minAvgAmount,
                                     double minTrendScore, Map<String, String> nameCache) {
        if (bars == null || bars.size() < 70) return null;
        List<Double> close = new ArrayList<>();
        List<Double> amount = new ArrayList<>();
        for (MarketData b : bars) {
            if (b.getClosePrice() == null || b.getClosePrice().doubleValue() <= 0) continue;
            close.add(b.getClosePrice().doubleValue());
            amount.add(b.getAmount() != null ? b.getAmount().doubleValue() : 0d);
        }
        if (close.size() < 70) return null;

        double ret20 = pct(close.get(close.size() - 1), close.get(close.size() - 21));
        double ret60 = pct(close.get(close.size() - 1), close.get(close.size() - 61));
        double ma20 = avg(close, close.size() - 20, close.size() - 1);
        double ma60 = avg(close, close.size() - 60, close.size() - 1);
        double avgAmount20 = avg(amount, amount.size() - 20, amount.size() - 1);
        double vol20 = stdPct(close, 20) * 100;
        double mdd60 = maxDrawdown(close, 60);
        double rsi14 = rsi(close, 14);
        double macdHist = macdHistogram(close);
        double volumeRatio = amount.get(amount.size() - 1) / Math.max(1d, avgAmount20);

        double trend = 0;
        trend += ma20 > ma60 ? 30 : 0;
        trend += clamp(ret20, -10, 20) + 10;
        trend += clamp(ret60, -20, 30) * 0.5 + 10;
        trend = clamp(trend, 0, 100);

        double momentum = 0;
        momentum += clamp((rsi14 - 40) * 1.5, 0, 35);
        momentum += clamp(macdHist * 400, 0, 35);
        momentum += clamp(volumeRatio * 12, 0, 30);
        momentum = clamp(momentum, 0, 100);

        double volumeScore = clamp((avgAmount20 / Math.max(1, minAvgAmount)) * 60 + volumeRatio * 20, 0, 100);
        double riskPenalty = clamp(vol20 * 1.5 + mdd60 * 1.2, 0, 100);
        double finalScore = 0.4 * trend + 0.25 * momentum + 0.2 * volumeScore - 0.15 * riskPenalty;

        boolean passMinAvgAmount = avgAmount20 >= minAvgAmount;
        boolean passMinTrendScore = trend >= minTrendScore;
        boolean passMaTrend = ma20 > ma60;
        boolean passRet20 = ret20 >= -5;

        Candidate c = new Candidate();
        c.stockCode = stockCode;
        c.exchangeType = exchangeType;
        c.stockName = nameCache.computeIfAbsent(stockCode + ":" + exchangeType, k -> getStockName(stockCode, exchangeType));
        c.finalScore = finalScore;
        c.trendScore = trend;
        c.momentumScore = momentum;
        c.volumeScore = volumeScore;
        c.riskPenalty = riskPenalty;
        c.ret20 = ret20;
        c.ret60 = ret60;
        c.volatility20 = vol20;
        c.maxDrawdown60 = mdd60;
        c.avgAmount20 = avgAmount20;
        Map<String, Object> reasons = new LinkedHashMap<>();
        reasons.put("trend", ma20 > ma60 ? "MA20 在 MA60 上方" : "趋势偏弱");
        reasons.put("ret20", round2(ret20) + "%");
        reasons.put("ret60", round2(ret60) + "%");
        reasons.put("rsi14", round2(rsi14));
        reasons.put("volume_ratio", round2(volumeRatio));
        reasons.put("pass_min_avg_amount", passMinAvgAmount);
        reasons.put("pass_min_trend_score", passMinTrendScore);
        reasons.put("pass_ma20_gt_ma60", passMaTrend);
        reasons.put("pass_ret20_floor", passRet20);
        c.reasons = reasons;
        c.passMinAvgAmount = passMinAvgAmount;
        c.passMinTrendScore = passMinTrendScore;
        c.passMaTrend = passMaTrend;
        c.passRet20 = passRet20;
        return c;
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

    private double pct(double now, double prev) {
        if (prev == 0) return 0;
        return (now / prev - 1) * 100;
    }

    private double avg(List<Double> list, int from, int to) {
        if (list == null || list.isEmpty()) return 0;
        int f = Math.max(0, from);
        int t = Math.min(list.size() - 1, to);
        if (f > t) return 0;
        double sum = 0;
        int n = 0;
        for (int i = f; i <= t; i++) {
            sum += list.get(i);
            n++;
        }
        return n > 0 ? sum / n : 0;
    }

    private double stdPct(List<Double> close, int period) {
        if (close.size() < period + 1) return 0;
        List<Double> rets = new ArrayList<>();
        for (int i = close.size() - period; i < close.size(); i++) {
            if (i <= 0) continue;
            double prev = close.get(i - 1);
            if (prev <= 0) continue;
            rets.add(close.get(i) / prev - 1);
        }
        if (rets.isEmpty()) return 0;
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = rets.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(var);
    }

    private double maxDrawdown(List<Double> close, int period) {
        if (close.size() < 2) return 0;
        int from = Math.max(0, close.size() - period);
        double peak = close.get(from);
        double mdd = 0;
        for (int i = from; i < close.size(); i++) {
            peak = Math.max(peak, close.get(i));
            if (peak <= 0) continue;
            double dd = (peak - close.get(i)) / peak * 100;
            mdd = Math.max(mdd, dd);
        }
        return mdd;
    }

    /**
     * 使用TechnicalIndicators工具类计算MACD柱状图
     */
    private double macdHistogram(List<Double> close) {
        int n = close.size();
        if (n < 35) return 0;
        double[] closeArr = close.stream().mapToDouble(Double::doubleValue).toArray();
        TechnicalIndicators.MacdResult macd = TechnicalIndicators.macd(closeArr);
        int lastIdx = n - 1;
        return macd.macd()[lastIdx];
    }

    /**
     * 使用TechnicalIndicators工具类计算RSI
     */
    private double rsi(List<Double> close, int period) {
        if (close.size() < period + 1) return 50;
        double[] closeArr = close.stream().mapToDouble(Double::doubleValue).toArray();
        return TechnicalIndicators.rsiAt(closeArr, closeArr.length - 1, period);
    }

    private double clamp(double v, double min, double max) {
        return ScoringUtils.clamp(v, min, max);
    }

    private double round2(double v) {
        return ScoringUtils.round2(v);
    }

    private void ensureNotInterrupted() {
        ScoringUtils.checkInterrupted();
    }

    private static class Candidate {
        String stockCode;
        String exchangeType;
        String stockName;
        double finalScore;
        double trendScore;
        double momentumScore;
        double volumeScore;
        double riskPenalty;
        double ret20;
        double ret60;
        double volatility20;
        double maxDrawdown60;
        double avgAmount20;
        Map<String, Object> reasons;
        boolean passMinAvgAmount;
        boolean passMinTrendScore;
        boolean passMaTrend;
        boolean passRet20;
    }
}
