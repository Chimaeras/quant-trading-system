package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.entity.SelectorTemplate;
import com.quant.integration.gdelt.GdeltArticle;
import com.quant.integration.gdelt.GdeltClient;
import com.quant.integration.gdelt.GdeltEvent;
import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.SelectorTemplateRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.service.scoring.ClassicFactorCalculator;
import com.quant.service.scoring.ExecutorLifecycle;
import com.quant.service.scoring.IndustryEventSensitivity;
import com.quant.service.scoring.ProgressiveResultWriter;
import com.quant.service.scoring.DataQuality;
import com.quant.service.scoring.ScoringUtils;
import com.quant.service.scoring.model.CandidateScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.*;

/**
 * 政策/地缘政治/新闻（GDELT）因子选股（MVP：市场级指数 → 统一作用到所有股票）。
 *
 * 产出落库到 selector_result / selector_result_item，factor_version = geopolicy_news_v1。
 * - trend_score / momentum_score / volume_score / risk_penalty 复用 selector_result_item 字段
 * - reasons 里写入指数与 Top 事件/新闻摘要，便于前端展示与回测对齐
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeopolicyNewsScoringService {

    public static final String FACTOR_VERSION = "geopolicy_news_v1";

    private final MarketDataRepository marketDataRepository;
    private final StockInfoRepository stockInfoRepository;
    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final SelectorTemplateRepository selectorTemplateRepository;
    private final GdeltClient gdeltClient;
    private final ProgressiveResultWriter progressiveResultWriter;
    private final ClassicFactorCalculator classicFactorCalculator;
    private final IndustryEventSensitivity industryEventSensitivity;

    public Map<String, Object> run(Map<String, Object> request) {
        Map<String, Object> req = mergeTemplate(request != null ? request : Map.of());

        LocalDate reqDate = parseDate(req.get("tradeDate"), LocalDate.now());
        LocalDate tradeDate = marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate);

        int topN = parseInt(req.get("topN"), 50, 1, 200);
        int lookbackDays = parseInt(req.get("lookbackDays"), 60, 20, 260);
        double minAvgAmount = parseDouble(req.get("minAvgAmount"), 30000000d, 1000000d, 500000000d);
        double minTrendScore = parseDouble(req.get("minTrendScore"), 40d, 0d, 100d);
        boolean persist = parseBool(req.get("persist"), true);
        boolean excludeST = parseBool(req.get("excludeST"), true);
        boolean progressivePersist = parseBool(req.get("progressivePersist"), false);
        String fixedResultId = String.valueOf(req.getOrDefault("resultId", "")).trim();
        boolean resumeMode = parseBool(req.get("resumeMode"), false);

        // GDELT 参数（默认：CHN 相关）
        String eventsQuery = String.valueOf(req.getOrDefault("gdeltEventsQuery", "CHN")).trim();
        String newsQuery = String.valueOf(req.getOrDefault("gdeltNewsQuery", "CHN")).trim();
        int gdeltEventsLookbackDays = parseInt(req.get("gdeltEventsLookbackDays"), 14, 1, 60);
        int gdeltNewsLookbackDays = parseInt(req.get("gdeltNewsLookbackDays"), 3, 1, 30);
        int eventsMaxRecords = parseInt(req.get("gdeltEventsMaxRecords"), 250, 10, 250);
        int newsMaxRecords = parseInt(req.get("gdeltNewsMaxRecords"), 250, 10, 250);
        int topK = parseInt(req.get("topK"), 10, 3, 30);
        int threads = parseInt(req.get("threads"),
                Math.max(1, Runtime.getRuntime().availableProcessors()), 1, 64);

        // 新闻过滤（后处理，避免依赖 GDELT query 语法差异）
        Set<String> newsLangAllow = parseCsvSet(req.get("newsLangAllow"), Set.of("Chinese", "English"));
        Set<String> newsCountryAllow = parseCsvSet(req.get("newsSourceCountryAllow"), Set.of()); // 空=不过滤
        Set<String> newsDomainAllow = parseCsvSet(req.get("newsDomainAllow"), Set.of()); // 空=不过滤
        Set<String> newsDomainDeny = parseCsvSet(req.get("newsDomainDeny"), Set.of()); // 空=不过滤
        // 国内主流媒体优先（默认开启“优先排序+加权”，不强制过滤）
        Set<String> preferredDomains = parseCsvSet(req.get("newsPreferredDomains"), defaultPreferredDomains());
        double preferredBoost = parseDouble(req.get("newsPreferredBoost"), 1.5, 1.0, 5.0);

        // 风险阈值（报告口径）
        double riskRed = parseDouble(req.get("riskRed"), 0.4, 0.05, 0.95);
        double riskYellow = parseDouble(req.get("riskYellow"), 0.2, 0.01, 0.9);
        double lambda = parseDouble(req.get("lambda"), 0.1, 0.0, 1.0); // /day

        log.info(
                "[geopolicy_news] run start tradeDate={} topN={} lookbackDays={} minAvgAmount={} minTrendScore={} persist={} excludeST={} evQ='{}' newsQ='{}' evLB={} newsLB={} risk(Y/R)={}/{} lambda={}",
                tradeDate, topN, lookbackDays, round2(minAvgAmount), round2(minTrendScore), persist, excludeST,
                eventsQuery, newsQuery, gdeltEventsLookbackDays, gdeltNewsLookbackDays,
                round2(riskYellow), round2(riskRed), round2(lambda)
        );

        // 计算市场级因子
        LocalDate evStart = tradeDate.minusDays(gdeltEventsLookbackDays);
        LocalDate newsStart = tradeDate.minusDays(gdeltNewsLookbackDays);

        // 注意：GDELT Events API 在当前节点可能持续 404；客户端内部已做冷却。
        List<GdeltEvent> events = gdeltClient.fetchEvents(eventsQuery, evStart, tradeDate, eventsMaxRecords);
        List<GdeltArticle> articlesRaw = gdeltClient.fetchArticles(newsQuery, newsStart, tradeDate, newsMaxRecords);
        List<GdeltArticle> articles = filterArticles(articlesRaw, newsLangAllow, newsCountryAllow, newsDomainAllow, newsDomainDeny);
        // 优先把“主流域名”排在前面（稳定排序：同类仍保持 GDELT 原始 datedesc 顺序）
        articles = rankArticlesByPreference(articles, preferredDomains);
        if (articlesRaw.size() != articles.size()) {
            log.info(
                    "[geopolicy_news] news filtered raw={} kept={} langAllow={} countryAllow={} domainAllow={} domainDeny={}",
                    articlesRaw.size(), articles.size(),
                    newsLangAllow, newsCountryAllow, newsDomainAllow, newsDomainDeny
            );
        }
        if (!articles.isEmpty() && preferredDomains != null && !preferredDomains.isEmpty()) {
            int preferredHit = 0;
            for (GdeltArticle a : articles) {
                String d = a.domain() != null ? a.domain().trim().toLowerCase() : "";
                if (!d.isBlank() && preferredDomains.contains(d)) preferredHit++;
            }
            log.info("[geopolicy_news] news preferred domains hit={}/{} boost={} preferredDomains={}", preferredHit, articles.size(), round2(preferredBoost), preferredDomains);
        }

        // 如果 events 不可用，使用“地缘风险新闻”作为替代输入（从 doc/doc 抽取）
        String geoNewsQuery = String.valueOf(req.getOrDefault(
                "gdeltGeoNewsQuery",
                // 中英关键词混合，尽量覆盖“冲突/制裁/关税/打击/封锁/谈判”
                "(CHN) (war OR conflict OR sanction OR tariff OR missile OR invasion OR strike OR embargo OR negotiation OR 制裁 OR 冲突 OR 关税 OR 导弹 OR 进攻 OR 打击 OR 封锁 OR 谈判)"
        )).trim();
        List<GdeltArticle> geoArticlesRaw = gdeltClient.fetchArticles(geoNewsQuery, evStart, tradeDate, Math.min(250, newsMaxRecords));
        List<GdeltArticle> geoArticles = filterArticles(geoArticlesRaw, newsLangAllow, newsCountryAllow, newsDomainAllow, newsDomainDeny);
        geoArticles = rankArticlesByPreference(geoArticles, preferredDomains);

        GeoNewsFactor factor = computeFactor(
                tradeDate, events, articles, geoArticles,
                lambda, riskRed, riskYellow, topK,
                preferredDomains, preferredBoost
        );
        log.info(
                "[geopolicy_news] macro computed tradeDate={} events={} articles={} riskLevel={} recPos={} geoRisk01={} newsSent01={} policy01={}",
                tradeDate, events.size(), articles.size(), factor.riskLevel, round2(factor.recommendedPositionPct),
                round4(factor.geoRiskIndex01), round4(factor.newsSentIndex01), round4(factor.policyScore01)
        );

        // 选股：复用经典多因子 Candidate 逻辑（趋势+动量+量价-风险），再叠加宏观因子
        LocalDate rangeStart = tradeDate.minusDays(Math.max(lookbackDays + 30L, 90L));
        boolean bulkFetch = parseBool(req.get("bulkFetch"), true);
        List<Object[]> pairs = marketDataRepository.findDistinctStockPairsInRange(rangeStart, tradeDate);
        String targetStockCode = String.valueOf(req.getOrDefault("stockCode", "")).trim();
        String targetExchangeType = String.valueOf(req.getOrDefault("exchangeType", "")).trim();
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
            log.info("[geopolicy_news] no market_data pairs in range [{}..{}], tradeDate={}", rangeStart, tradeDate, tradeDate);
            return Map.of(
                    "message", "无可用行情数据",
                    "trade_date", tradeDate.toString(),
                    "factor_version", FACTOR_VERSION,
                    "items", List.of()
            );
        }

        Map<String, String> nameCache = new HashMap<>();
        Map<String, StockInfo> infoMap = new HashMap<>();
        for (StockInfo s : stockInfoRepository.findAll()) {
            if (s.getStockCode() != null && s.getExchangeType() != null) {
                infoMap.put(s.getStockCode() + "|" + s.getExchangeType(), s);
            }
        }
        boolean crossSectionEnabled = ScoringUtils.parseBool(req.get("crossSectionNormalize"), false);
        List<CandidateScore> candidates = new ArrayList<>();
        List<CandidateScore> passedCandidates = new ArrayList<>();
        int scanned = 0;
        int scored = 0;
        String progressiveResultId = null;
        int progressiveRank = 1;
        Set<String> existingStockKeys = new HashSet<>();
        if (persist && progressivePersist) {
            progressiveResultId = ensureResultSkeleton(
                    fixedResultId, tradeDate, req, lookbackDays, topN, minAvgAmount, minTrendScore, excludeST, factor, resumeMode
            );
            if (resumeMode && progressiveResultId != null && !progressiveResultId.isBlank()) {
                existingStockKeys = selectorResultItemRepository.findStockKeysByResultId(progressiveResultId);
                progressiveRank = existingStockKeys.size() + 1;
            }
        }

        Map<String, List<MarketData>> grouped = Map.of();
        if (bulkFetch) {
            List<MarketData> allBars = marketDataRepository
                    .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate);
            Map<String, List<MarketData>> tmp = new LinkedHashMap<>();
            for (MarketData m : allBars) {
                String key = m.getStockCode() + "|" + m.getExchangeType();
                tmp.computeIfAbsent(key, k -> new ArrayList<>(Math.min(lookbackDays + 40, 400))).add(m);
            }
            grouped = tmp;
            log.info("[geopolicy_news] bulkFetch=ON barsFetched={} groupedKeys={}", allBars.size(), grouped.size());
        } else {
            log.info("[geopolicy_news] bulkFetch=OFF, using per-stock queries");
        }

        record StockTaskResult(String stockKey, Optional<CandidateScore> candidate) {}
        int failed = 0;
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable);
            t.setName("geopolicy-score-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        CompletionService<StockTaskResult> cs = new ExecutorCompletionService<>(pool);
        int submitted = 0;
        for (Object[] pair : pairs) {
            if (pair == null || pair.length < 2) continue;
            String stockCode = String.valueOf(pair[0]);
            String exchangeType = String.valueOf(pair[1]);
            String key = stockCode + "|" + exchangeType;
            submitted++;
            Map<String, List<MarketData>> groupedRef = grouped;
            cs.submit(() -> {
                ensureNotInterrupted();
                List<MarketData> bars;
                if (bulkFetch) {
                    bars = groupedRef.getOrDefault(key, List.of());
                } else {
                    bars = marketDataRepository
                            .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(stockCode, exchangeType, rangeStart, tradeDate);
                }
                Optional<CandidateScore> c = classicFactorCalculator.calculate(
                        stockCode, exchangeType, bars, minAvgAmount, minTrendScore,
                        k -> nameCache.computeIfAbsent(k, kk -> getStockName(stockCode, exchangeType)),
                        excludeST);
                return new StockTaskResult(key, c);
            });
        }

        try {
            for (int i = 0; i < submitted; i++) {
                ensureNotInterrupted();
                StockTaskResult result;
                try {
                    result = cs.take().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("任务被取消（线程中断）", e);
                } catch (ExecutionException e) {
                    result = new StockTaskResult("", Optional.empty());
                }
                scanned++;
                if (result.candidate() == null || result.candidate().isEmpty()) {
                    failed++;
                    if (progressiveResultId != null) {
                        progressiveResultWriter.updateProgress(
                                progressiveResultId,
                                submitted,
                                scanned,
                                scored,
                                failed,
                                ""
                        );
                    }
                    continue;
                }
                CandidateScore c = result.candidate().get();
                scored++;
                String stockKey = c.getStockCode() + "|" + c.getExchangeType();
                if (!(resumeMode && existingStockKeys.contains(stockKey))) {
                    StockInfo info = infoMap.get(stockKey);
                    String industry = info != null && info.getIndustry() != null ? info.getIndustry() : "";
                    double sens = industryEventSensitivity.resolve(industry, factor.riskLevel);
                    c.applyMacroOverlay(factor.policyScore01, factor.geoRiskIndex01, factor.newsSentIndex01, sens);
                    c.setDataQuality(events.isEmpty() ? DataQuality.DEGRADED : DataQuality.FULL);
                    if (c.getReasons() != null) {
                        c.getReasons().put("industry_sensitivity", sens);
                        c.getReasons().put("industry", industry);
                        c.getReasons().put("data_quality", c.getDataQuality().name());
                    }
                    candidates.add(c);
                    if (c.isClassicPassed()) {
                        passedCandidates.add(c);
                    }
                    if (progressiveResultId != null) {
                        SelectorResultItem item = toItem(progressiveResultId, progressiveRank, c, factor);
                        boolean inserted = progressiveResultWriter.saveItemIfAbsent(item);
                        if (inserted) {
                            progressiveRank++;
                            existingStockKeys.add(stockKey);
                        }
                    }
                }
                if (progressiveResultId != null) {
                    progressiveResultWriter.updateProgress(
                            progressiveResultId,
                            submitted,
                            scanned,
                            scored,
                            failed,
                            stockKey
                    );
                }
            }
        } finally {
            ExecutorLifecycle.shutdownGracefully(pool);
        }

        if (crossSectionEnabled && candidates.size() >= 2) {
            classicFactorCalculator.normalizeCrossSection(candidates, 1e-6);
            for (CandidateScore c : candidates) {
                if (c.getMacroAdjustedFinal() > 0) {
                    String key = c.getStockCode() + "|" + c.getExchangeType();
                    StockInfo info = infoMap.get(key);
                    String industry = info != null && info.getIndustry() != null ? info.getIndustry() : "";
                    double sens = industryEventSensitivity.resolve(industry, factor.riskLevel);
                    c.applyMacroOverlay(factor.policyScore01, factor.geoRiskIndex01, factor.newsSentIndex01, sens);
                }
            }
        }
        passedCandidates.sort((a, b) -> Double.compare(b.effectiveFinalScore(), a.effectiveFinalScore()));
        List<CandidateScore> selected = passedCandidates.stream().limit(topN).toList();

        String resultId = null;
        if (persist) {
            resultId = persistResult(
                    tradeDate, req, selected, factor, lookbackDays, topN, minAvgAmount, minTrendScore, excludeST,
                    progressiveResultId, resumeMode, progressivePersist, scanned, scored
            );
        }
        log.info(
                "[geopolicy_news] run done tradeDate={} scanned={} candidates={} selected={} persisted={} resultId={}",
                tradeDate, scanned, candidates.size(), selected.size(), persist, resultId
        );

        List<Map<String, Object>> outItems = new ArrayList<>();
        int rank = 1;
        for (CandidateScore c : selected) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank_no", rank++);
            item.put("stock_code", c.getStockCode());
            item.put("exchange_type", c.getExchangeType());
            item.put("stock_name", c.getStockName());
            item.put("final_score", round2(c.effectiveFinalScore()));
            item.put("trend_score", round2(c.getTrendScore()));
            item.put("momentum_score", round2(c.getMomentumScore()));
            item.put("volume_score", round2(c.getVolumeScore()));
            item.put("risk_penalty", round2(c.getRiskPenalty()));
            item.put("ret20", round2(c.getRet20()));
            item.put("ret60", round2(c.getRet60()));
            item.put("volatility20", round2(c.getVolatility20()));
            item.put("max_drawdown60", round2(c.getMaxDrawdown60()));
            item.put("avg_amount20", round2(c.getAvgAmount20()));
            item.put("reasons", c.getReasons() != null ? c.getReasons() : Map.of());
            outItems.add(item);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        if (resultId != null) res.put("result_id", resultId);
        res.put("trade_date", tradeDate.toString());
        res.put("factor_version", FACTOR_VERSION);
        res.put("scanned", scanned);
        res.put("selected", selected.size());
        res.put("top_n", topN);
        res.put("persisted", persist);
        res.put("macro", factor.toMap());
        res.put("items", outItems);
        return res;
    }

    private Map<String, Object> mergeTemplate(Map<String, Object> request) {
        Map<String, Object> base = new LinkedHashMap<>(request);
        String templateId = String.valueOf(request.getOrDefault("templateId", "")).trim();
        if (templateId.isBlank()) return base;

        Optional<SelectorTemplate> opt = selectorTemplateRepository.findByTemplateId(templateId);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (opt.isPresent() && opt.get().getParams() != null) {
            merged.putAll(opt.get().getParams());
        }
        merged.putAll(base);
        return merged;
    }

    private String persistResult(
            LocalDate tradeDate,
            Map<String, Object> req,
            List<CandidateScore> selected,
            GeoNewsFactor factor,
            int lookbackDays,
            int topN,
            double minAvgAmount,
            double minTrendScore,
            boolean excludeST,
            String fixedResultId,
            boolean resumeMode,
            boolean progressivePersist,
            int scanned,
            int scored
    ) {
        String resultId = (progressivePersist && fixedResultId != null && !fixedResultId.isBlank())
                ? fixedResultId
                : ensureResultSkeleton(
                fixedResultId, tradeDate, req, lookbackDays, topN, minAvgAmount, minTrendScore, excludeST, factor, resumeMode
        );

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("factor", FACTOR_VERSION);
        snapshot.put("lookbackDays", lookbackDays);
        snapshot.put("topN", topN);
        snapshot.put("minAvgAmount", minAvgAmount);
        snapshot.put("minTrendScore", minTrendScore);
        snapshot.put("excludeST", excludeST);
        snapshot.put("gdeltEventsQuery", String.valueOf(req.getOrDefault("gdeltEventsQuery", "CHN")));
        snapshot.put("gdeltNewsQuery", String.valueOf(req.getOrDefault("gdeltNewsQuery", "CHN")));
        // 过滤/偏好参数在 run() 中解析，这里为避免变量作用域问题，按相同默认值重新归一化一份写入快照
        snapshot.put("newsLangAllow", parseCsvSet(req.get("newsLangAllow"), Set.of("Chinese", "English")));
        snapshot.put("newsSourceCountryAllow", parseCsvSet(req.get("newsSourceCountryAllow"), Set.of()));
        snapshot.put("newsDomainAllow", parseCsvSet(req.get("newsDomainAllow"), Set.of()));
        snapshot.put("newsDomainDeny", parseCsvSet(req.get("newsDomainDeny"), Set.of()));
        snapshot.put("newsPreferredDomains", parseCsvSet(req.get("newsPreferredDomains"), defaultPreferredDomains()));
        snapshot.put("newsPreferredBoost", parseDouble(req.get("newsPreferredBoost"), 1.5, 1.0, 5.0));
        snapshot.put("macro", factor.toMap());

        SelectorResult result = selectorResultRepository.findByResultId(resultId).orElse(new SelectorResult());
        result.setResultId(resultId);
        result.setTradeDate(tradeDate);
        result.setLookbackDays(lookbackDays);
        result.setTopN(topN);
        result.setMinAvgAmount(minAvgAmount);
        result.setMinTrendScore(minTrendScore);
        result.setTemplateId(String.valueOf(req.getOrDefault("templateId", "")).trim().isBlank()
                ? null : String.valueOf(req.get("templateId")).trim());
        result.setFactorVersion(FACTOR_VERSION);
        result.setStrategyType("GEOPOLICY_NEWS");
        result.setRunStatus("SUCCESS");
        result.setTotalCandidates(scanned);
        result.setProcessedCandidates(scanned);
        result.setSuccessCandidates(scored);
        result.setFailedCandidates(Math.max(0, scanned - scored));
        result.setLastProcessedKey("");
        result.setFactorSnapshot(snapshot);
        selectorResultRepository.save(result);

        if (!progressivePersist) {
            selectorResultItemRepository.deleteByResultId(resultId);
            int rank = 1;
            for (CandidateScore c : selected) {
                SelectorResultItem item = toItem(resultId, rank++, c, factor);
                selectorResultItemRepository.save(item);
            }
        }
        return resultId;
    }

    private String ensureResultSkeleton(
            String fixedResultId,
            LocalDate tradeDate,
            Map<String, Object> req,
            int lookbackDays,
            int topN,
            double minAvgAmount,
            double minTrendScore,
            boolean excludeST,
            GeoNewsFactor factor,
            boolean resumeMode
    ) {
        String resultId = (fixedResultId != null && !fixedResultId.isBlank()) ? fixedResultId : genResultId();
        SelectorResult result = selectorResultRepository.findByResultId(resultId).orElse(new SelectorResult());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("factor", FACTOR_VERSION);
        snapshot.put("lookbackDays", lookbackDays);
        snapshot.put("topN", topN);
        snapshot.put("minAvgAmount", minAvgAmount);
        snapshot.put("minTrendScore", minTrendScore);
        snapshot.put("excludeST", excludeST);
        snapshot.put("gdeltEventsQuery", String.valueOf(req.getOrDefault("gdeltEventsQuery", "CHN")));
        snapshot.put("gdeltNewsQuery", String.valueOf(req.getOrDefault("gdeltNewsQuery", "CHN")));
        snapshot.put("macro", factor.toMap());

        result.setResultId(resultId);
        result.setTradeDate(tradeDate);
        result.setLookbackDays(lookbackDays);
        result.setTopN(topN);
        result.setMinAvgAmount(minAvgAmount);
        result.setMinTrendScore(minTrendScore);
        result.setTemplateId(String.valueOf(req.getOrDefault("templateId", "")).trim().isBlank()
                ? null : String.valueOf(req.get("templateId")).trim());
        result.setFactorVersion(FACTOR_VERSION);
        result.setStrategyType("GEOPOLICY_NEWS");
        result.setRunStatus("RUNNING");
        result.setTotalCandidates(0);
        result.setProcessedCandidates(0);
        result.setSuccessCandidates(0);
        result.setFailedCandidates(0);
        result.setLastProcessedKey("");
        result.setFactorSnapshot(snapshot);
        progressiveResultWriter.upsertRunningResult(
                resultId,
                result,
                fixedResultId != null && !fixedResultId.isBlank() && !resumeMode
        );
        return resultId;
    }

    private SelectorResultItem toItem(String resultId, int rankNo, CandidateScore c, GeoNewsFactor factor) {
        SelectorResultItem item = c.toSelectorResultItem(resultId, rankNo, "GEOPOLICY_NEWS");
        item.setFinalScore(round2(c.effectiveFinalScore()));
        Map<String, Object> reasons = new LinkedHashMap<>();
        if (c.getReasons() != null) reasons.putAll(c.getReasons());
        reasons.put("factor", FACTOR_VERSION);
        reasons.put("macro", factor.toMap());
        reasons.put("macro_policy_score01", round4(c.getMacroPolicyScore01()));
        reasons.put("geo_risk_index01", round4(c.getGeoRiskIndex01()));
        reasons.put("news_sent_index01", round4(c.getNewsSentIndex01()));
        reasons.put("macro_adjusted_final", round2(c.getMacroAdjustedFinal()));
        item.setReasons(reasons);
        return item;
    }

    private GeoNewsFactor computeFactor(
            LocalDate tradeDate,
            List<GdeltEvent> events,
            List<GdeltArticle> articles,
            List<GdeltArticle> geoArticles,
            double lambda,
            double riskRed,
            double riskYellow,
            int topK,
            Set<String> preferredDomains,
            double preferredBoost
    ) {
        // GeoScore：
        // - 优先用 Events（GoldsteinScale）
        // - 若 Events 不可用（持续 404/空），则用 “地缘风险新闻” 的 tone 近似（tone 越负风险越高）
        double geoScore;
        double geoRisk01;
        List<Map<String, Object>> topEvents = new ArrayList<>();

        if (events != null && !events.isEmpty()) {
            double raw = 0.0;
            events.stream()
                    .sorted(Comparator.comparingDouble((GdeltEvent e) -> Math.abs(e.goldsteinScale())).reversed())
                    .limit(topK)
                    .forEach((GdeltEvent e) -> topEvents.add(e.toMap()));

            for (GdeltEvent e : events) {
                double wType = eventTypeWeight(e.eventCode());
                double decay = timeDecay(tradeDate, e.sqlDate(), lambda);
                raw += e.goldsteinScale() * wType * decay;
            }
            double avg = raw / events.size();
            geoScore = clamp(avg, -10.0, 10.0);
            geoRisk01 = clamp(((-geoScore) + 10.0) / 20.0, 0.0, 1.0);
        } else {
            // 使用 geoArticles 的 tone（加权）来替代：越负 -> 风险越高
            double toneWsum = 0.0;
            double wsum = 0.0;
            if (geoArticles != null) {
                for (GdeltArticle a : geoArticles) {
                    double w = 1.0;
                    if (preferredDomains != null && !preferredDomains.isEmpty()) {
                        String d = a.domain() != null ? a.domain().trim().toLowerCase() : "";
                        if (!d.isBlank() && preferredDomains.contains(d)) {
                            w = preferredBoost;
                        }
                    }
                    toneWsum += a.tone() * w;
                    wsum += w;
                }
            }
            double avgGeoTone = wsum > 0 ? toneWsum / wsum : 0.0;
            // 映射到 geoScore（保持同区间 [-10..10] 语义）
            geoScore = clamp(avgGeoTone, -10.0, 10.0);
            geoRisk01 = clamp(((-geoScore) + 10.0) / 20.0, 0.0, 1.0);
        }

        // 新闻情绪（0..1）：GDELT tone 常见在 [-10..10]，越高越偏正面
        // 对“主流域名”做加权（默认 boost=1.5），使指数更偏向主流媒体口径
        double toneWsum = 0.0;
        double wsum = 0.0;
        for (GdeltArticle a : articles) {
            double w = 1.0;
            if (preferredDomains != null && !preferredDomains.isEmpty()) {
                String d = a.domain() != null ? a.domain().trim().toLowerCase() : "";
                if (!d.isBlank() && preferredDomains.contains(d)) {
                    w = preferredBoost;
                }
            }
            toneWsum += a.tone() * w;
            wsum += w;
        }
        double avgTone = wsum > 0 ? toneWsum / wsum : 0.0;
        double newsSent01 = clamp((avgTone + 10.0) / 20.0, 0.0, 1.0);

        // 综合 policy score（0..1）：新闻更偏“情绪”，地缘风险更偏“风险折扣”
        double policy01 = clamp(0.6 * newsSent01 + 0.4 * (1.0 - geoRisk01), 0.0, 1.0);

        // 风险分级
        String level;
        double recPos;
        if (geoRisk01 > riskRed) {
            level = "RED";
            recPos = 0.0;
        } else if (geoRisk01 > riskYellow) {
            level = "YELLOW";
            recPos = 0.5;
        } else {
            level = "GREEN";
            recPos = 1.0;
        }

        // Top 新闻
        List<Map<String, Object>> topNews = new ArrayList<>();
        articles.stream()
                .limit(topK)
                .forEach(a -> topNews.add(a.toMap()));

        return new GeoNewsFactor(
                round4(geoScore),
                round4(geoRisk01),
                round4(avgTone),
                round4(newsSent01),
                round4(policy01),
                level,
                recPos,
                topEvents,
                topNews,
                events.size(),
                articles.size()
        );
    }

    private static double eventTypeWeight(String eventCode) {
        var props = com.quant.service.scoring.ScoringPropertiesBridge.get();
        Map<String, Double> weights = props != null && props.getGdelt().getEventWeights() != null
                ? props.getGdelt().getEventWeights()
                : Map.of("01", 0.6, "04", 0.4, "12", 0.3, "default", 0.1);
        if (eventCode == null) return weights.getOrDefault("default", 0.1);
        String c = eventCode.trim();
        if (c.startsWith("01")) return weights.getOrDefault("01", 0.6);
        if (c.startsWith("04")) return weights.getOrDefault("04", 0.4);
        if (c.startsWith("12")) return weights.getOrDefault("12", 0.3);
        return weights.getOrDefault("default", 0.1);
    }

    private static double timeDecay(LocalDate tradeDate, Long sqlDate, double lambda) {
        if (tradeDate == null || sqlDate == null) return 1.0;
        try {
            String s = String.valueOf(sqlDate);
            LocalDate d;
            if (s.length() == 8) {
                d = LocalDate.parse(s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8));
            } else {
                return 1.0;
            }
            int halfLife = com.quant.service.scoring.SignalDecay.newsHalfLife();
            double raw = 1.0;
            return com.quant.service.scoring.SignalDecay.apply(raw, d, tradeDate, halfLife);
        } catch (Exception ignored) {
            return 1.0;
        }
    }

    private String getStockName(String stockCode, String exchangeType) {
        Optional<StockInfo> exact = stockInfoRepository.findByStockCodeAndExchangeType(stockCode, exchangeType);
        if (exact.isPresent()) return exact.get().getStockName();
        return stockInfoRepository.findFirstByStockCode(stockCode).map(StockInfo::getStockName).orElse(stockCode);
    }

    private String genResultId() {
        return "GP" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private static LocalDate parseDate(Object v, LocalDate def) {
        if (v == null) return def;
        try {
            return LocalDate.parse(String.valueOf(v));
        } catch (Exception ignore) {
            return def;
        }
    }

    private static boolean parseBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isBlank()) return def;
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "y".equals(s);
    }

    private static int parseInt(Object v, int def, int min, int max) {
        try {
            int n = v == null ? def : Integer.parseInt(String.valueOf(v));
            return Math.max(min, Math.min(max, n));
        } catch (Exception ignore) {
            return def;
        }
    }

    private static double parseDouble(Object v, double def, double min, double max) {
        try {
            double d = v == null ? def : Double.parseDouble(String.valueOf(v));
            return Math.max(min, Math.min(max, d));
        } catch (Exception ignore) {
            return def;
        }
    }

    private static double clamp(double v, double min, double max) {
        return ScoringUtils.clamp(v, min, max);
    }

    private static double round2(double v) {
        return ScoringUtils.round2(v);
    }

    private static double round4(double v) {
        return ScoringUtils.round4(v);
    }

    private static Set<String> parseCsvSet(Object v, Set<String> def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        if (s.isBlank()) return def;
        String[] parts = s.split("[,;\\s]+");
        Set<String> out = new LinkedHashSet<>();
        for (String p : parts) {
            String t = p != null ? p.trim() : "";
            if (!t.isBlank()) out.add(t);
        }
        return out.isEmpty() ? def : out;
    }

    private static List<GdeltArticle> filterArticles(
            List<GdeltArticle> in,
            Set<String> langAllow,
            Set<String> countryAllow,
            Set<String> domainAllow,
            Set<String> domainDeny
    ) {
        if (in == null || in.isEmpty()) return List.of();
        List<GdeltArticle> out = new ArrayList<>(in.size());
        for (GdeltArticle a : in) {
            if (a == null) continue;
            String lang = a.language() != null ? a.language().trim() : "";
            String domain = a.domain() != null ? a.domain().trim().toLowerCase() : "";
            String country = a.sourceCountry() != null ? a.sourceCountry().trim() : "";

            if (langAllow != null && !langAllow.isEmpty() && !lang.isBlank() && !langAllow.contains(lang)) {
                continue;
            }
            if (countryAllow != null && !countryAllow.isEmpty() && !country.isBlank() && !countryAllow.contains(country)) {
                continue;
            }
            if (domainDeny != null && !domainDeny.isEmpty() && !domain.isBlank() && domainDeny.contains(domain)) {
                continue;
            }
            if (domainAllow != null && !domainAllow.isEmpty()) {
                if (domain.isBlank() || !domainAllow.contains(domain)) {
                    continue;
                }
            }
            out.add(a);
        }
        return out;
    }

    private static List<GdeltArticle> rankArticlesByPreference(List<GdeltArticle> in, Set<String> preferredDomains) {
        if (in == null || in.size() <= 1) return in != null ? in : List.of();
        if (preferredDomains == null || preferredDomains.isEmpty()) return in;
        List<GdeltArticle> out = new ArrayList<>(in);
        out.sort((a, b) -> {
            String da = a != null && a.domain() != null ? a.domain().trim().toLowerCase() : "";
            String db = b != null && b.domain() != null ? b.domain().trim().toLowerCase() : "";
            boolean pa = !da.isBlank() && preferredDomains.contains(da);
            boolean pb = !db.isBlank() && preferredDomains.contains(db);
            return Boolean.compare(!pa, !pb); // preferred 在前
        });
        return out;
    }

    private static Set<String> defaultPreferredDomains() {
        var props = com.quant.service.scoring.ScoringPropertiesBridge.get();
        if (props != null && props.getNews().getPreferredDomains() != null) {
            return new LinkedHashSet<>(props.getNews().getPreferredDomains());
        }
        return new LinkedHashSet<>(List.of(
                "xinhuanet.com", "news.cn", "people.com.cn", "cctv.com", "cgtn.com",
                "globaltimes.cn", "stcn.com", "cnr.cn", "eastmoney.com", "10jqka.com.cn",
                "sina.com.cn", "jjw.com.cn"
        ));
    }

    private void ensureNotInterrupted() {
        ScoringUtils.checkInterrupted();
    }

    private record GeoNewsFactor(
            double geoScore,
            double geoRiskIndex01,
            double newsAvgTone,
            double newsSentIndex01,
            double policyScore01,
            String riskLevel,
            double recommendedPositionPct,
            List<Map<String, Object>> topEvents,
            List<Map<String, Object>> topNews,
            int eventsCount,
            int newsCount
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("geo_score", geoScore);
            m.put("geo_risk_index01", geoRiskIndex01);
            m.put("news_avg_tone", newsAvgTone);
            m.put("news_sent_index01", newsSentIndex01);
            m.put("policy_score01", policyScore01);
            m.put("risk_level", riskLevel);
            m.put("recommended_position_pct", recommendedPositionPct);
            m.put("events_count", eventsCount);
            m.put("news_count", newsCount);
            m.put("top_events", topEvents != null ? topEvents : List.of());
            m.put("top_news", topNews != null ? topNews : List.of());
            return m;
        }
    }
}

