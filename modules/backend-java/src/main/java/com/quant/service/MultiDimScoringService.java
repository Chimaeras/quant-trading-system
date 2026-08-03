package com.quant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.entity.StockMultiDimScore;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.StockMultiDimScoreRepository;
import com.quant.service.scoring.FundamentalDocScoring;
import com.quant.service.scoring.ExecutorLifecycle;
import com.quant.service.scoring.RotationDocScoring;
import com.quant.service.scoring.TechnicalDocScoring;
import com.quant.service.scoring.DynamicWeightingService;
import com.quant.service.scoring.CompositeScoringService;
import com.quant.service.scoring.EnhancedMarketSentimentService;
import com.quant.service.scoring.MarketEnvironmentAnalyzer;
import com.quant.service.scoring.ScoringRules;
import com.quant.service.scoring.ScoringUtils;
import com.quant.service.scoring.SignalDecay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 多维度打分第一版（{@link StockMultiDimScore#FACTOR_VERSION}）：<br>
 * 仅使用库内可计算数据——K 线技术面、日线 PE 相对行业、板块 5 日强度排名、行业 5 日收益对比。<br>
 * 不调用外部行情/API，不使用政策抓取、资金流、融资、财报导入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiDimScoringService {

    private static final int MIN_BARS = 30;
    private static final String FV = StockMultiDimScore.FACTOR_VERSION;

    /** 技术面归一化到 0～10 的线性区间（与子项得分量级匹配） */
    private static final double TECH_NORM_LOW = -5;
    private static final double TECH_NORM_HIGH = 15;

    private final MarketDataRepository marketDataRepository;
    private final StockInfoRepository stockInfoRepository;
    private final StockMultiDimScoreRepository stockMultiDimScoreRepository;
    private final ObjectMapper objectMapper;
    private final EnhancedMarketSentimentService enhancedMarketSentimentService;
    private final MarketEnvironmentAnalyzer marketEnvironmentAnalyzer;

    @Transactional(readOnly = true)
    public Map<String, Object> listScores(LocalDate tradeDate, int page, int size) {
        LocalDate d;
        if (tradeDate == null) {
            d = stockMultiDimScoreRepository.findMaxTradeDate(FV).orElse(null);
        } else {
            d = stockMultiDimScoreRepository.findMaxTradeDateOnOrBefore(FV, tradeDate).orElse(null);
        }
        if (d == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("trade_date", "");
            empty.put("list", List.of());
            empty.put("total", 0L);
            empty.put("page", 0);
            empty.put("size", Math.min(500, Math.max(1, size)));
            return empty;
        }
        Pageable p = PageRequest.of(Math.max(0, page), Math.min(500, Math.max(1, size)));
        Page<StockMultiDimScore> pg = stockMultiDimScoreRepository.findByTradeDateAndFactorVersionOrderByCompositeScoreDesc(d, FV, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", d.toString());
        List<Map<String, Object>> list = new ArrayList<>();
        int rankBase = pg.getNumber() * pg.getSize();
        int idx = 0;
        for (StockMultiDimScore s : pg.getContent()) {
            list.add(toMap(s, rankBase + (++idx)));
        }
        out.put("list", list);
        out.put("total", pg.getTotalElements());
        out.put("page", pg.getNumber());
        out.put("size", pg.getSize());
        out.put("scoring_mode", "compute_v1");
        return out;
    }

    @Transactional
    public Map<String, Object> run(Map<String, Object> request) {
        Map<String, Object> req = request != null ? request : Map.of();
        LocalDate reqDate = parseDate(req.get("tradeDate"));
        LocalDate tradeDate = reqDate != null
                ? marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate)
                : marketDataRepository.findMaxTradeDate().orElse(LocalDate.now());

        boolean persist = parseBool(req.get("persist"), true);
        int lookbackDays = (int) parseDouble(req.get("lookbackDays"), 520, 60, 2000);
        int threads = (int) parseDouble(req.get("threads"), Math.max(1, Runtime.getRuntime().availableProcessors()), 1, 64);
        boolean bulkFetch = parseBool(req.get("bulkFetch"), true);
        boolean includeItems = parseBool(req.get("includeItems"), false);
        boolean excludeST = parseBool(req.get("excludeST"), true);
        double minAvgAmount = ScoringUtils.parseDouble(req.get("minAvgAmount"), 30_000_000d, 0d, 1_000_000_000_000d);
        int minListingDays = ScoringUtils.parseInt(req.get("minListingDays"), 60, 0, 3650);

        if (!bulkFetch) {
            return emptyResult(tradeDate, "multi_dim 当前仅支持 bulkFetch=true");
        }

        LocalDate rangeStart = tradeDate.minusDays(lookbackDays);
        List<Object[]> pairs = marketDataRepository.findDistinctStockPairsInRange(rangeStart, tradeDate);
        if (pairs.isEmpty()) {
            return emptyResult(tradeDate, "无可用行情数据");
        }

        List<MarketData> allOnDay = marketDataRepository.findByTradeDate(tradeDate);
        Map<String, MarketData> mdToday = new HashMap<>();
        for (MarketData m : allOnDay) {
            mdToday.put(m.getStockCode() + "|" + m.getExchangeType(), m);
        }

        Map<String, StockInfo> infoByKey = new HashMap<>();
        for (StockInfo s : stockInfoRepository.findAll()) {
            infoByKey.put(s.getStockCode() + "|" + s.getExchangeType(), s);
        }

        IndustryAvgs indPe = computeIndustryPeAvg(mdToday, infoByKey);

        List<MarketData> allBars = marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate);
        Map<String, List<MarketData>> grouped = new LinkedHashMap<>();
        for (MarketData m : allBars) {
            String key = m.getStockCode() + "|" + m.getExchangeType();
            grouped.computeIfAbsent(key, k -> new ArrayList<>(600)).add(m);
        }

        SectorIndustryMaps sim = buildSectorRankAndIndustryRet5(pairs, infoByKey, grouped);
        IndustryAvgs indAvgs = mergeIndustryRet(indPe, sim.industryAvgRet5());

        // 市场环境统一计算一次（全市场查询较重），传入每股打分线程复用，避免 O(股票数) 次全表加载
        MarketEnvironmentAnalyzer.MarketIndicators marketIndicators =
                marketEnvironmentAnalyzer.analyze(tradeDate, 60);

        List<StockMultiDimScore> rows = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger skipped = new AtomicInteger(0);

        List<String> keys = new ArrayList<>();
        for (Object[] pair : pairs) {
            if (pair == null || pair.length < 2) continue;
            keys.add(String.valueOf(pair[0]) + "|" + String.valueOf(pair[1]));
        }

        if (threads <= 1) {
            for (String key : keys) {
                int p = key.indexOf('|');
                String stockCode = p > 0 ? key.substring(0, p) : key;
                String exchangeType = p > 0 ? key.substring(p + 1) : "";
                scoreAndAdd(key, stockCode, exchangeType, tradeDate, grouped.getOrDefault(key, List.of()),
                        mdToday.get(key), infoByKey.get(key), indAvgs, sim, rows, skipped, excludeST, minListingDays, minAvgAmount, marketIndicators);
            }
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r);
                t.setName("multi-dim-" + t.getId());
                t.setDaemon(true);
                return t;
            });
            CompletionService<Void> cs = new ExecutorCompletionService<>(pool);
            final boolean finalExcludeST = excludeST;
            final int finalMinListingDays = minListingDays;
            final double finalMinAvgAmount = minAvgAmount;
            final MarketEnvironmentAnalyzer.MarketIndicators finalMarketIndicators = marketIndicators;
            for (String key : keys) {
                cs.submit(() -> {
                    int p = key.indexOf('|');
                    String stockCode = p > 0 ? key.substring(0, p) : key;
                    String exchangeType = p > 0 ? key.substring(p + 1) : "";
                    scoreAndAdd(key, stockCode, exchangeType, tradeDate, grouped.getOrDefault(key, List.of()),
                            mdToday.get(key), infoByKey.get(key), indAvgs, sim, rows, skipped, finalExcludeST, finalMinListingDays, finalMinAvgAmount, finalMarketIndicators);
                    return null;
                });
            }
            for (int i = 0; i < keys.size(); i++) {
                try {
                    cs.take().get();
                } catch (Exception e) {
                    skipped.incrementAndGet();
                    // 记录根因，避免打分任务整体失败原因不可见
                    log.warn("多维度打分个股任务失败: {} - {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e.getClass().getSimpleName());
                }
            }
            ExecutorLifecycle.shutdownGracefully(pool);
        }

        List<StockMultiDimScore> toSave = dedupe(rows);
        if (persist && !toSave.isEmpty()) {
            stockMultiDimScoreRepository.deleteByTradeDateAndFactorVersion(tradeDate, FV);
            stockMultiDimScoreRepository.saveAll(toSave);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("trade_date", tradeDate.toString());
        res.put("factor_version", FV);
        res.put("scoring_mode", "compute_v1");
        res.put("persisted", persist && !toSave.isEmpty());
        res.put("scored", toSave.size());
        res.put("skipped", skipped.get());
        res.put("include_items", includeItems);
        res.put("exclude_st", excludeST);
        res.put("min_avg_amount", minAvgAmount);
        if (includeItems) {
            List<Map<String, Object>> items = new ArrayList<>();
            int i = 0;
            for (StockMultiDimScore s : toSave.stream().sorted(Comparator.comparing(StockMultiDimScore::getCompositeScore).reversed()).toList()) {
                items.add(toMap(s, ++i));
            }
            res.put("items", items);
        } else {
            res.put("items", List.of());
        }
        return res;
    }

    private void scoreAndAdd(
            String key,
            String stockCode,
            String exchangeType,
            LocalDate tradeDate,
            List<MarketData> bars,
            MarketData todayBar,
            StockInfo info,
            IndustryAvgs indAvgs,
            SectorIndustryMaps sim,
            List<StockMultiDimScore> rows,
            AtomicInteger skipped,
            boolean excludeST,
            int minListingDays,
            double minAvgAmount,
            MarketEnvironmentAnalyzer.MarketIndicators marketIndicators) {

        if (bars == null || bars.size() < MIN_BARS) {
            skipped.incrementAndGet();
            return;
        }
        LocalDate lastBarDate = bars.get(bars.size() - 1).getTradeDate();
        if (lastBarDate == null || !tradeDate.equals(lastBarDate)) {
            skipped.incrementAndGet();
            return;
        }

        String stockName = info != null && info.getStockName() != null ? info.getStockName() : "";
        if (!ScoringUtils.hasMinListingTenure(
                info != null ? info.getListDate() : null, tradeDate, minListingDays, bars)) {
            skipped.incrementAndGet();
            return;
        }
        if (!ScoringUtils.isTradableForScoring(bars, stockCode, exchangeType, stockName,
                info != null ? info.getStatus() : null, excludeST, true, true)) {
            skipped.incrementAndGet();
            return;
        }
        if (!ScoringUtils.passesMinAvgAmount(bars, minAvgAmount)) {
            skipped.incrementAndGet();
            return;
        }

        // 技术分析
        TechnicalDocScoring.TechDocResult tech = TechnicalDocScoring.score(bars);
        double techW = tech.technicalWeighted();

        // 基本面分析（简化版，实际应使用完整的财务数据）
        String industry = info != null && info.getIndustry() != null ? info.getIndustry() : "";
        Double avgPe = indAvgs.avgPeByIndustry().get(industry);
        FundamentalDocScoring.FundamentalResult fr = 
                FundamentalDocScoring.fundamentalPeOnly(
                        todayBar != null ? todayBar.getPeRatio() : null, avgPe);

        // 计算行业对比
        int L = bars.size() - 1;
        double c0 = bars.get(L).getClosePrice() != null ? bars.get(L).getClosePrice().doubleValue() : 0;
        double c5 = bars.get(L - 5).getClosePrice() != null ? bars.get(L - 5).getClosePrice().doubleValue() : 0;
        Double ret5 = c5 > 1e-9 ? (c0 - c5) / c5 * 100.0 : null;
        Double indAvgRet = indAvgs.avgRet5ByIndustry().get(industry.isBlank() ? "_none_" : industry);

        int indCmp = FundamentalDocScoring.industryCompareFromPriceAndPe(
                todayBar != null ? todayBar.getPeRatio() : null,
                avgPe,
                ret5,
                indAvgRet);

        // 板块轮动
        String sector = (info != null && info.getSector() != null && !info.getSector().isBlank())
                ? info.getSector()
                : industry;
        int totalSec = sim.sectorRank().containsKey("_total_") ? sim.sectorRank().get("_total_") : 0;
        String secKey = sector.isBlank() ? "_none_" : sector;
        Integer rk = sim.sectorRank().get(secKey);
        double rotSec = (rk != null && totalSec >= 6)
                ? RotationDocScoring.scoreSectorRank(rk, totalSec)
                : 0;

        // 市场情绪分析（使用增强版服务，基于技术指标推导）
        EnhancedMarketSentimentService.SentimentAnalysisResult sentimentResult =
                enhancedMarketSentimentService.analyzeSentiment(bars,
                        todayBar != null && todayBar.getPeRatio() != null
                                ? todayBar.getPeRatio().doubleValue() : null,
                        industry);
        double sentimentScore = sentimentResult.totalScore();
        // 信号时间衰减：若最新 K 线日期与交易日期有偏差，对情绪面和技术面做衰减
        if (lastBarDate != null && !tradeDate.equals(lastBarDate)) {
            long daysStale = java.time.temporal.ChronoUnit.DAYS.between(lastBarDate, tradeDate);
            if (daysStale > 0) {
                sentimentScore = SignalDecay.apply(sentimentScore, lastBarDate, tradeDate, SignalDecay.newsHalfLife());
            }
        }

        // 市场环境由 run() 统一计算一次后传入（避免每股线程内重复全市场查询）
        String marketEnvironment = marketIndicators.marketEnvironment();
        DynamicWeightingService.MarketEnvironment marketWeights =
                DynamicWeightingService.getMarketEnvironmentWeights(marketEnvironment);

        // 使用真实股票特性分类
        Double peValue = todayBar != null && todayBar.getPeRatio() != null
                ? todayBar.getPeRatio().doubleValue() : null;
        String stockType = marketEnvironmentAnalyzer.classifyStock(bars, peValue, industry);
        DynamicWeightingService.StockCharacteristics stockWeights =
                DynamicWeightingService.getStockCharacteristicsWeights(stockType);

        // 计算动态权重
        DynamicWeightingService.DynamicWeights dynamicWeights =
                DynamicWeightingService.calculateDynamicWeights(marketWeights, stockWeights, 0.6, 0.4);

        // 各维度归一化到 0-100 分制后再加权
        double technicalNorm100 = normTechTo10(techW) * 10.0;
        double fundamentalNorm100 = Math.min(100, fr.weightedTotal() * 10.0);
        double sentimentNorm100 = sentimentScore / 30.0 * 100.0;
        double rotationNorm100 = normSectorRotTo10(rotSec, totalSec) * 10.0;
        double industryNorm100 = normIndCompareTo10(indCmp) * 10.0;

        // 权重归一化，确保总和为 1.0
        double techWeight = dynamicWeights.technicalWeight();
        double fundWeight = dynamicWeights.fundamentalWeight();
        double sentWeight = dynamicWeights.sentimentWeight();
        double rotationWeight = 0.05;
        double industryWeight = 0.05;
        double totalWeight = techWeight + fundWeight + sentWeight + rotationWeight + industryWeight;
        techWeight /= totalWeight;
        fundWeight /= totalWeight;
        sentWeight /= totalWeight;
        rotationWeight /= totalWeight;
        industryWeight /= totalWeight;

        // 行业适配修正：作为估值基准调整，而非直接加分
        double industryAdjustment = CompositeScoringService.getIndustryAdjustment(industry) * 0.5;

        double composite = ScoringUtils.clampScore(
                technicalNorm100 * techWeight
                + fundamentalNorm100 * fundWeight
                + sentimentNorm100 * sentWeight
                + rotationNorm100 * rotationWeight
                + industryNorm100 * industryWeight
                + industryAdjustment);

        double financialHealthScore = estimateFinancialHealthScore(todayBar, info);
        String investmentAdvice = ScoringRules.getInvestmentAdvice(composite);
        String riskControlMessage = CompositeScoringService.getRiskControlMessage(
                composite, fundamentalNorm100, technicalNorm100, financialHealthScore);

        StockMultiDimScore e = new StockMultiDimScore();
        e.setStockCode(stockCode);
        e.setExchangeType(exchangeType);
        e.setStockName(info != null ? info.getStockName() : "");
        e.setTradeDate(tradeDate);
        e.setFactorVersion(FV);
        e.setScoreTechnical(round2(techW));
        e.setScoreFundamental(fundamentalNorm100);
        e.setScorePolicy(0.0);
        e.setScoreRotation(round2(rotSec));
        e.setScoreIndustryCompare((double) indCmp);
        e.setScoreSentiment(round2(sentimentNorm100));
        e.setCompositeScore(composite);
        e.setSuggestion(investmentAdvice);
        e.setTechMacd(tech.macdScore());
        e.setTechRsi(tech.rsiScore());
        e.setTechKdj(tech.kdjScore());
        e.setTechVolume(tech.volumeScore());
        e.setTechMa(tech.maScore());
        e.setMacdStatus(tech.macdStatus());
        e.setRsiStatus(tech.rsiStatus());
        e.setKdjStatus(tech.kdjStatus());
        e.setVolumeStatus(tech.volumeStatus());
        e.setMaStatus(tech.maStatus());
        try {
            Map<String, Object> reasons = new LinkedHashMap<>();
            reasons.put("mode", "compute_v1");
            reasons.put("technical_raw_weighted", techW);
            reasons.put("technical_ma_score", tech.maScore());
            reasons.put("technical_ma_status", tech.maStatus());
            reasons.put("fundamental_score", fundamentalNorm100);
            reasons.put("sentiment_score", sentimentNorm100);
            reasons.put("technical_norm100", technicalNorm100);
            reasons.put("rotation_norm100", rotationNorm100);
            reasons.put("industry_compare_norm100", industryNorm100);
            reasons.put("sentiment_level", sentimentResult.sentimentLevel());
            reasons.put("sentiment_news_score", sentimentResult.newsSentimentScore());
            reasons.put("sentiment_news_status", sentimentResult.newsStatus());
            reasons.put("sentiment_social_score", sentimentResult.socialMediaScore());
            reasons.put("sentiment_social_status", sentimentResult.socialStatus());
            reasons.put("sentiment_institution_score", sentimentResult.institutionalViewScore());
            reasons.put("sentiment_institution_status", sentimentResult.institutionStatus());
            reasons.put("sentiment_data_source", sentimentResult.dataSource());
            reasons.put("rotation_sector_only", rotSec);
            reasons.put("industry_compare_pts", indCmp);
            reasons.put("ret5_pct", ret5);
            reasons.put("sector_rank", rk);
            reasons.put("market_environment", marketEnvironment);
            reasons.put("stock_type", stockType);
            reasons.put("dynamic_weights", "技术面: " + dynamicWeights.technicalWeight() + ", 基本面: " + dynamicWeights.fundamentalWeight() + ", 情绪面: " + dynamicWeights.sentimentWeight());
            reasons.put("industry_adjustment", industryAdjustment);
            reasons.put("risk_control_message", riskControlMessage);
            e.setReasonsJson(objectMapper.writeValueAsString(reasons));
        } catch (JsonProcessingException ignored) {
            e.setReasonsJson("{}");
        }
        rows.add(e);
    }

    /** 与子项量级对齐的综合分（各维已压到 0～10 再加权） */
    private static double normTechTo10(double techW) {
        double n = (techW - TECH_NORM_LOW) / (TECH_NORM_HIGH - TECH_NORM_LOW) * 10.0;
        return Math.max(0, Math.min(10, n));
    }

    private static double normSectorRotTo10(double rotSec, int totalSectors) {
        if (totalSectors < 6) {
            return 5.0;
        }
        double n = (rotSec + 3.0) / 9.0 * 10.0;
        return Math.max(0, Math.min(10, n));
    }

    private static double normIndCompareTo10(int indCmp) {
        double n = (indCmp + 4.0) / 8.0 * 10.0;
        return Math.max(0, Math.min(10, n));
    }

    private record IndustryAvgs(
            Map<String, Double> avgPeByIndustry,
            Map<String, Double> avgRet5ByIndustry
    ) {}

    private record SectorIndustryMaps(Map<String, Integer> sectorRank, Map<String, Double> industryAvgRet5) {}

    private IndustryAvgs computeIndustryPeAvg(Map<String, MarketData> mdToday, Map<String, StockInfo> infoByKey) {
        Map<String, List<Double>> peB = new HashMap<>();
        for (Map.Entry<String, MarketData> e : mdToday.entrySet()) {
            StockInfo si = infoByKey.get(e.getKey());
            if (si == null || si.getIndustry() == null || si.getIndustry().isBlank()) continue;
            String ind = si.getIndustry();
            if (e.getValue().getPeRatio() != null) {
                peB.computeIfAbsent(ind, k -> new ArrayList<>()).add(e.getValue().getPeRatio().doubleValue());
            }
        }
        return new IndustryAvgs(avg(peB), Map.of());
    }

    private SectorIndustryMaps buildSectorRankAndIndustryRet5(
            List<Object[]> pairs,
            Map<String, StockInfo> infoByKey,
            Map<String, List<MarketData>> grouped) {

        Map<String, List<Double>> sectorRets = new HashMap<>();
        Map<String, List<Double>> industryRets = new HashMap<>();
        for (Object[] pair : pairs) {
            if (pair == null || pair.length < 2) continue;
            String key = String.valueOf(pair[0]) + "|" + String.valueOf(pair[1]);
            List<MarketData> bars = grouped.get(key);
            if (bars == null || bars.size() < 6) continue;
            int L = bars.size() - 1;
            double c0 = bars.get(L).getClosePrice() != null ? bars.get(L).getClosePrice().doubleValue() : 0;
            double c5 = bars.get(L - 5).getClosePrice() != null ? bars.get(L - 5).getClosePrice().doubleValue() : 0;
            if (c5 < 1e-9) continue;
            double ret = (c0 - c5) / c5 * 100.0;
            StockInfo inf = infoByKey.get(key);
            String sec = (inf != null && inf.getSector() != null && !inf.getSector().isBlank())
                    ? inf.getSector()
                    : (inf != null && inf.getIndustry() != null ? inf.getIndustry() : "_none_");
            sectorRets.computeIfAbsent(sec, k -> new ArrayList<>()).add(ret);
            String ind = (inf != null && inf.getIndustry() != null && !inf.getIndustry().isBlank())
                    ? inf.getIndustry()
                    : "_none_";
            industryRets.computeIfAbsent(ind, k -> new ArrayList<>()).add(ret);
        }
        Map<String, Double> avgSector = avg(sectorRets);
        List<String> sorted = avgSector.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        int n = sorted.size();
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            rank.put(sorted.get(i), i + 1);
        }
        rank.put("_total_", n);

        Map<String, Double> avgIndRet = avg(industryRets);
        return new SectorIndustryMaps(rank, avgIndRet);
    }

    /** 合并行业 PE 与行业 5 日收益均值 */
    private IndustryAvgs mergeIndustryRet(IndustryAvgs peOnly, Map<String, Double> avgRet5ByIndustry) {
        return new IndustryAvgs(peOnly.avgPeByIndustry(), avgRet5ByIndustry);
    }

    private static Map<String, Double> avg(Map<String, List<Double>> b) {
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, List<Double>> e : b.entrySet()) {
            double v = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            out.put(e.getKey(), v);
        }
        return out;
    }

    private static List<StockMultiDimScore> dedupe(List<StockMultiDimScore> rows) {
        Map<String, StockMultiDimScore> m = new LinkedHashMap<>();
        for (StockMultiDimScore s : rows) {
            String k = s.getStockCode() + "|" + s.getExchangeType();
            m.putIfAbsent(k, s);
        }
        return new ArrayList<>(m.values());
    }

    private Map<String, Object> toMap(StockMultiDimScore s, int rank) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank_no", rank);
        m.put("stock_code", s.getStockCode());
        m.put("exchange_type", s.getExchangeType());
        m.put("stock_name", s.getStockName());
        m.put("trade_date", s.getTradeDate().toString());
        m.put("composite_score", s.getCompositeScore());
        m.put("score_technical", s.getScoreTechnical());
        m.put("score_fundamental", s.getScoreFundamental());
        m.put("score_policy", s.getScorePolicy());
        m.put("score_rotation", s.getScoreRotation());
        m.put("score_industry_compare", s.getScoreIndustryCompare());
        m.put("score_sentiment", s.getScoreSentiment());
        m.put("suggestion", s.getSuggestion());
        m.put("tech_macd", s.getTechMacd());
        m.put("tech_rsi", s.getTechRsi());
        m.put("tech_kdj", s.getTechKdj());
        m.put("tech_volume", s.getTechVolume());
        m.put("tech_ma", s.getTechMa());
        m.put("macd_status", s.getMacdStatus());
        m.put("rsi_status", s.getRsiStatus());
        m.put("kdj_status", s.getKdjStatus());
        m.put("volume_status", s.getVolumeStatus());
        m.put("ma_status", s.getMaStatus());
        m.put("reasons_json", s.getReasonsJson());
        m.put("factor_version", s.getFactorVersion());
        m.put("scoring_mode", "compute_v1");
        return m;
    }

    private Map<String, Object> emptyResult(LocalDate tradeDate, String msg) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("trade_date", tradeDate != null ? tradeDate.toString() : "");
        res.put("factor_version", FV);
        res.put("scoring_mode", "compute_v1");
        res.put("error", msg);
        res.put("scored", 0);
        res.put("items", List.of());
        return res;
    }

    private static LocalDate parseDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        try {
            return LocalDate.parse(o.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean parseBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = o.toString().trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s)) return false;
        return def;
    }

    private static double parseDouble(Object o, double def, double min, double max) {
        if (o == null) return def;
        try {
            double v = Double.parseDouble(o.toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 估算财务健康度得分
     */
    private double estimateFinancialHealthScore(MarketData todayBar, StockInfo info) {
        double score = 8.0; // 默认中等健康度

        if (todayBar != null) {
            // PE估值调整
            if (todayBar.getPeRatio() != null) {
                double pe = todayBar.getPeRatio().doubleValue();
                if (pe > 0 && pe < 15) score += 1.5;
                else if (pe >= 15 && pe < 30) score += 0.5;
                else if (pe > 60) score -= 1.0;
            }

            // PB估值调整
            if (todayBar.getPbRatio() != null) {
                double pb = todayBar.getPbRatio().doubleValue();
                if (pb > 0 && pb < 1.5) score += 0.5;
                else if (pb > 4) score -= 0.5;
            }
        }

        // 行业调整
        if (info != null && info.getIndustry() != null) {
            score += ScoringRules.getIndustryAdjustment(info.getIndustry()) * 0.1;
        }

        return ScoringUtils.clamp(score, 0, 10);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
