package com.quant.service.scoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.entity.StockTrendScore;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.StockTrendScoreRepository;
import com.quant.service.scoring.DataQuality;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 综合三维度打分服务
 *
 * 根据《打分机制》文档实现完整的三维度100分制打分：
 * - 技术分析维度(30分)：均线排列(10分)、RSI指标(10分)、MACD指标(10分)
 * - 基本面分析维度(40分)：估值水平(15分)、盈利能力(15分)、财务健康度(10分)
 * - 市场情绪分析维度(30分)：新闻舆情(15分)、社交媒体讨论热度(8分)、机构观点(7分)
 *
 * 重构版本：使用统一的工具类 TechnicalIndicators、ScoringRules、ScoringUtils
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComprehensiveScoringService {

    public static final String FACTOR_VERSION = ScoringConstants.VERSION_COMPREHENSIVE;

    private final MarketDataRepository marketDataRepository;
    private final StockInfoRepository stockInfoRepository;
    private final StockTrendScoreRepository stockTrendScoreRepository;
    private final EnhancedMarketSentimentService enhancedMarketSentimentService;
    private final FinancialSnapshotLoader financialSnapshotLoader;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    /**
     * 综合评分结果
     */
    public record ComprehensiveResult(
            double technicalScore,
            double fundamentalScore,
            double sentimentScore,
            double compositeScore,
            String investmentAdvice,
            String riskControlMessage,
            TechnicalDetail technicalDetail,
            FundamentalDetail fundamentalDetail,
            SentimentDetail sentimentDetail,
            DynamicWeightingService.DynamicWeights weights
    ) {}

    public record TechnicalDetail(
            double maScore,
            double rsiScore,
            double macdScore,
            String maStatus,
            String rsiStatus,
            String macdStatus
    ) {}

    public record FundamentalDetail(
            double valuationScore,
            double profitabilityScore,
            double financialHealthScore,
            String valuationStatus,
            String profitabilityStatus,
            String financialHealthStatus
    ) {}

    public record SentimentDetail(
            double newsScore,
            double socialScore,
            double institutionalScore,
            String newsStatus,
            String socialStatus,
            String institutionalStatus
    ) {}

    /**
     * 对单只股票进行综合评分
     */
    public ComprehensiveResult scoreStock(List<MarketData> bars, StockInfo stockInfo,
                                           Double pe, Double pb, Double roe,
                                           Double debtRatio, Double currentRatio, String industry) {
        if (bars == null || bars.size() < ScoringConstants.MIN_BARS_COMPREHENSIVE) {
            return createEmptyResult("数据不足，需要至少" + ScoringConstants.MIN_BARS_COMPREHENSIVE + "根K线");
        }

        int n = bars.size();
        int L = n - 1;

        // 提取收盘价数组
        double[] close = extractClosePrices(bars);
        if (close == null) {
            return createEmptyResult("收盘价数据无效");
        }

        // 1. 技术分析维度 (30分) - 使用统一工具类
        TechnicalDetail technicalDetail = scoreTechnicalDimension(close, L);
        double technicalScore = technicalDetail.maScore() + technicalDetail.rsiScore() + technicalDetail.macdScore();

        // 2. 基本面分析维度 (40分) - 使用统一评分规则
        FundamentalDetail fundamentalDetail = scoreFundamentalDimension(pe, pb, roe, debtRatio, currentRatio, industry);
        double fundamentalScore = fundamentalDetail.valuationScore() + fundamentalDetail.profitabilityScore()
                + fundamentalDetail.financialHealthScore();

        // 3. 市场情绪分析维度 (30分)
        EnhancedMarketSentimentService.SentimentAnalysisResult sentimentResult =
                enhancedMarketSentimentService.analyzeSentiment(bars, pe, industry);
        SentimentDetail sentimentDetail = new SentimentDetail(
                sentimentResult.newsSentimentScore(),
                sentimentResult.socialMediaScore(),
                sentimentResult.institutionalViewScore(),
                sentimentResult.newsStatus(),
                sentimentResult.socialStatus(),
                sentimentResult.institutionStatus()
        );
        double sentimentScore = sentimentResult.totalScore();

        // 4. 动态权重计算
        String marketEnvironment = inferMarketEnvironment(bars);
        String stockType = inferStockType(pe, pb, roe);
        DynamicWeightingService.MarketEnvironment marketWeights =
                DynamicWeightingService.getMarketEnvironmentWeights(marketEnvironment);
        DynamicWeightingService.StockCharacteristics stockWeights =
                DynamicWeightingService.getStockCharacteristicsWeights(stockType);
        DynamicWeightingService.DynamicWeights weights =
                DynamicWeightingService.calculateDynamicWeights(marketWeights, stockWeights, 0.6, 0.4);

        // 5. 计算加权综合得分
        double compositeScore = calculateWeightedScore(
                technicalScore, fundamentalScore, sentimentScore, weights, 30, 40, 30
        );

        // 6. 行业适配修正
        double industryAdjustment = ScoringRules.getIndustryAdjustment(industry);
        compositeScore = ScoringUtils.clampScore(compositeScore + industryAdjustment);

        // 7. 投资建议
        String investmentAdvice = ScoringRules.getInvestmentAdvice(compositeScore);

        // 8. 风险控制提示
        String riskControlMessage = CompositeScoringService.getRiskControlMessage(
                compositeScore, fundamentalScore, technicalScore, fundamentalDetail.financialHealthScore()
        );

        return new ComprehensiveResult(
                technicalScore, fundamentalScore, sentimentScore, compositeScore,
                investmentAdvice, riskControlMessage,
                technicalDetail, fundamentalDetail, sentimentDetail, weights
        );
    }

    /**
     * 提取收盘价数组
     */
    private double[] extractClosePrices(List<MarketData> bars) {
        int n = bars.size();
        double[] close = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = bars.get(i).getClosePrice() != null ? bars.get(i).getClosePrice().doubleValue() : Double.NaN;
        }
        return Arrays.stream(close).anyMatch(Double::isNaN) ? null : close;
    }

    /**
     * 技术分析维度评分 (30分)
     */
    private TechnicalDetail scoreTechnicalDimension(double[] close, int L) {
        // 均线排列评分 (10分)
        String maStatus = TechnicalIndicators.classifyMaArrangement(close, L);
        double maScore = ScoringRules.scoreMaArrangement(maStatus);

        // RSI评分 (10分)
        double rsi = TechnicalIndicators.rsiAt(close, L, ScoringConstants.RSI_PERIOD);
        String rsiStatus = TechnicalIndicators.classifyRsi(rsi);
        double rsiScore = ScoringRules.scoreRsi(rsi);

        // MACD评分 (10分)
        TechnicalIndicators.MacdResult macd = TechnicalIndicators.macd(close);
        String macdStatus = TechnicalIndicators.classifyMacd(macd, L);
        double macdScore = ScoringRules.scoreMacd(macdStatus);

        return new TechnicalDetail(maScore, rsiScore, macdScore, maStatus, rsiStatus, macdStatus);
    }

    /**
     * 基本面分析维度评分 (40分)
     */
    private FundamentalDetail scoreFundamentalDimension(Double pe, Double pb, Double roe,
                                                         Double debtRatio, Double currentRatio, String industry) {
        // 估值水平评分 (15分)
        double valuationScore = scoreValuation(pe, pb);
        String valuationStatus = classifyValuation(pe, pb);

        // 盈利能力评分 (15分)
        double profitabilityScore = ScoringRules.scoreROE(roe);
        String profitabilityStatus = classifyProfitability(roe);

        // 财务健康度评分 (10分)
        double financialHealthScore = ScoringRules.scoreDebtRatio(debtRatio) * 0.5 +
                ScoringRules.scoreCurrentRatio(currentRatio) * 0.5;
        String financialHealthStatus = classifyFinancialHealth(debtRatio, currentRatio);

        return new FundamentalDetail(
                valuationScore, profitabilityScore, financialHealthScore,
                valuationStatus, profitabilityStatus, financialHealthStatus
        );
    }

    // ==================== 基本面评分辅助方法 ====================

    private double scoreValuation(Double pe, Double pb) {
        double score = 7.5;
        if (pe != null) {
            if (pe < 15) score += 3;
            else if (pe < 25) score += 1.5;
            else if (pe > 40) score -= 2;
            else if (pe > 60) score -= 4;
        }
        if (pb != null) {
            if (pb < 1) score += 2;
            else if (pb < 1.5) score += 1;
            else if (pb > 3) score -= 1;
        }
        return ScoringUtils.clamp(score, 0, 15);
    }

    private String classifyValuation(Double pe, Double pb) {
        if (pe == null) return "中等估值";
        if (pe < 20 && (pb == null || pb < 1.5)) return "低估值";
        if (pe > 40 || (pb != null && pb > 3)) return "高估值";
        return "中等估值";
    }

    private String classifyProfitability(Double roe) {
        if (roe == null) return "一般";
        if (roe > 15) return "优秀";
        if (roe > 10) return "良好";
        if (roe > 5) return "一般";
        return "较差";
    }

    private String classifyFinancialHealth(Double debtRatio, Double currentRatio) {
        if (debtRatio == null) return "中等";
        if (debtRatio < 50 && (currentRatio == null || currentRatio > 1.5)) return "健康";
        if (debtRatio > 70 || (currentRatio != null && currentRatio < 1)) return "潜在风险";
        return "中等";
    }

    // ==================== 综合评分方法 ====================

    private double calculateWeightedScore(double techScore, double fundScore, double sentScore,
                                           DynamicWeightingService.DynamicWeights weights,
                                           double techMax, double fundMax, double sentMax) {
        double techNorm = techScore / techMax * 100;
        double fundNorm = fundScore / fundMax * 100;
        double sentNorm = sentScore / sentMax * 100;

        return techNorm * weights.technicalWeight()
                + fundNorm * weights.fundamentalWeight()
                + sentNorm * weights.sentimentWeight();
    }

    private String inferMarketEnvironment(List<MarketData> bars) {
        if (bars.size() < 60) return "震荡市";
        int n = bars.size();
        double closeNow = bars.get(n - 1).getClosePrice() != null ? bars.get(n - 1).getClosePrice().doubleValue() : 0;
        double close60dAgo = bars.get(n - 60).getClosePrice() != null ? bars.get(n - 60).getClosePrice().doubleValue() : closeNow;
        double change = close60dAgo > 1e-9 ? (closeNow - close60dAgo) / close60dAgo * 100 : 0;

        if (change > 20) return "牛市";
        if (change < -20) return "熊市";
        return "震荡市";
    }

    private String inferStockType(Double pe, Double pb, Double roe) {
        if (pe != null && pe < 20 && pb != null && pb < 1.5) return "价值股";
        if (roe != null && roe > 15) return "成长股";
        return "普通股";
    }

    private ComprehensiveResult createEmptyResult(String reason) {
        return new ComprehensiveResult(
                0, 0, 0, 0, "观望", reason,
                new TechnicalDetail(0, 0, 0, "数据不足", "数据不足", "数据不足"),
                new FundamentalDetail(0, 0, 0, "数据不足", "数据不足", "数据不足"),
                new SentimentDetail(0, 0, 0, "数据不足", "数据不足", "数据不足"),
                DynamicWeightingService.getDefaultWeights()
        );
    }

    /**
     * 批量运行综合打分
     */
    @Transactional
    public Map<String, Object> runBatch(Map<String, Object> request) {
        long t0 = System.nanoTime();
        Map<String, Object> req = ScoringUtils.nvl(request, Map.of());
        LocalDate reqDate = ScoringUtils.parseDate(req.get("tradeDate"));
        LocalDate tradeDate = reqDate != null
                ? marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate)
                : marketDataRepository.findMaxTradeDate().orElse(LocalDate.now());

        boolean persist = ScoringUtils.parseBool(req.get("persist"), true);
        int lookbackDays = ScoringUtils.parseInt(req.get("lookbackDays"), ScoringConstants.DEFAULT_LOOKBACK_DAYS, 60, 2000);
        int threads = ScoringUtils.parseInt(req.get("threads"),
                Math.max(1, Runtime.getRuntime().availableProcessors()), 1, 64);
        boolean includeItems = ScoringUtils.parseBool(req.get("includeItems"), false);

        LocalDate rangeStart = tradeDate.minusDays(lookbackDays);
        List<Object[]> pairs = marketDataRepository.findDistinctStockPairsInRange(rangeStart, tradeDate);
        if (pairs.isEmpty()) {
            return emptyResult(tradeDate, "无可用行情数据");
        }

        // 预加载数据
        List<MarketData> allBars = marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate);
        Map<String, List<MarketData>> grouped = new LinkedHashMap<>();
        for (MarketData m : allBars) {
            String key = m.getStockCode() + "|" + m.getExchangeType();
            grouped.computeIfAbsent(key, k -> new ArrayList<>(600)).add(m);
        }

        Map<String, StockInfo> infoMap = new HashMap<>();
        for (StockInfo s : stockInfoRepository.findAll()) {
            infoMap.put(s.getStockCode() + "|" + s.getExchangeType(), s);
        }
        Map<String, com.quant.entity.StockFinancialSnapshot> snapshotMap =
                financialSnapshotLoader.loadLatestByStockKey();

        List<StockTrendScore> rows = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger skipped = new AtomicInteger(0);

        List<String> keys = new ArrayList<>();
        for (Object[] pair : pairs) {
            if (pair == null || pair.length < 2) continue;
            keys.add(String.valueOf(pair[0]) + "|" + String.valueOf(pair[1]));
        }

        if (threads <= 1) {
            for (String key : keys) {
                processStock(key, grouped.getOrDefault(key, List.of()), infoMap.get(key),
                        snapshotMap.get(key), tradeDate, rows, skipped);
            }
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r);
                t.setName("comprehensive-score-" + t.getId());
                t.setDaemon(true);
                return t;
            });
            CompletionService<Void> cs = new ExecutorCompletionService<>(pool);
            for (String key : keys) {
                cs.submit(() -> {
                    processStock(key, grouped.getOrDefault(key, List.of()), infoMap.get(key),
                            snapshotMap.get(key), tradeDate, rows, skipped);
                    return null;
                });
            }
            for (int i = 0; i < keys.size(); i++) {
                try {
                    cs.take().get();
                } catch (Exception e) {
                    skipped.incrementAndGet();
                }
            }
            ExecutorLifecycle.shutdownGracefully(pool);
        }

        rows.sort((a, b) -> Double.compare(b.getTotalScore(), a.getTotalScore()));
        List<StockTrendScore> toSave = dedupe(rows);

        if (persist && !toSave.isEmpty()) {
            new TransactionTemplate(transactionManager).execute(status -> {
                stockTrendScoreRepository.deleteByTradeDate(tradeDate);
                stockTrendScoreRepository.flush();
                stockTrendScoreRepository.saveAll(toSave);
                return null;
            });
        }

        long t1 = System.nanoTime();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("trade_date", tradeDate.toString());
        res.put("factor_version", FACTOR_VERSION);
        res.put("persisted", persist && !toSave.isEmpty());
        res.put("scored", toSave.size());
        res.put("skipped", skipped.get());
        res.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(t1 - t0));
        res.put("include_items", includeItems);

        if (includeItems) {
            List<Map<String, Object>> items = new ArrayList<>();
            int rank = 1;
            for (StockTrendScore s : toSave) {
                items.add(toMap(s, rank++));
            }
            res.put("items", items);
        } else {
            res.put("items", List.of());
        }
        return res;
    }

    private void processStock(String key, List<MarketData> bars, StockInfo info,
                               com.quant.entity.StockFinancialSnapshot snapshot,
                               LocalDate tradeDate, List<StockTrendScore> rows, AtomicInteger skipped) {
        if (bars.size() < ScoringConstants.MIN_BARS_COMPREHENSIVE) {
            skipped.incrementAndGet();
            return;
        }

        LocalDate lastBarDate = bars.get(bars.size() - 1).getTradeDate();
        if (!tradeDate.equals(lastBarDate)) {
            skipped.incrementAndGet();
            return;
        }

        int p = key.indexOf('|');
        String stockCode = p > 0 ? key.substring(0, p) : key;
        String exchangeType = p > 0 ? key.substring(p + 1) : "";

        MarketData todayBar = bars.get(bars.size() - 1);
        Double pe = todayBar.getPeRatio() != null ? todayBar.getPeRatio().doubleValue() : null;
        Double pb = todayBar.getPbRatio() != null ? todayBar.getPbRatio().doubleValue() : null;
        Double roe = null;
        Double debtRatio = null;
        Double currentRatio = null;
        if (snapshot != null) {
            roe = FinancialSnapshotLoader.toDouble(snapshot.getRoe());
            debtRatio = FinancialSnapshotLoader.toDouble(snapshot.getDebtRatio());
            currentRatio = FinancialSnapshotLoader.toDouble(snapshot.getCurrentRatio());
            if (pb == null) {
                pb = FinancialSnapshotLoader.toDouble(snapshot.getPbRatio());
            }
        }
        String industry = info != null ? info.getIndustry() : null;

        ComprehensiveResult result = scoreStock(bars, info, pe, pb, roe, debtRatio, currentRatio, industry);

        int nullFundCount = 0;
        if (pb == null) nullFundCount++;
        if (roe == null) nullFundCount++;
        if (debtRatio == null) nullFundCount++;
        if (currentRatio == null) nullFundCount++;
        DataQuality quality = nullFundCount == 0 ? DataQuality.FULL
                : nullFundCount >= 2 ? DataQuality.PARTIAL_FUNDAMENTALS : DataQuality.TECHNICAL_ONLY;

        StockTrendScore e = new StockTrendScore();
        e.setStockCode(stockCode);
        e.setExchangeType(exchangeType);
        e.setStockName(info != null ? info.getStockName() : "");
        e.setTradeDate(tradeDate);
        e.setTotalScore(ScoringUtils.round2(result.compositeScore()));
        e.setScoreTrend(ScoringUtils.round2(result.technicalScore()));
        e.setScoreBias(ScoringUtils.round2(result.fundamentalScore()));
        e.setScoreVolume(ScoringUtils.round2(result.sentimentScore()));
        e.setScoreSupport(ScoringUtils.round2(result.technicalDetail().maScore()));
        e.setScoreMacd(ScoringUtils.round2(result.technicalDetail().macdScore()));
        e.setScoreRsi(ScoringUtils.round2(result.technicalDetail().rsiScore()));
        e.setTrendStatus(result.technicalDetail().maStatus());
        e.setVolumeStatus(result.sentimentDetail().newsStatus());
        e.setMacdStatus(result.technicalDetail().macdStatus());
        e.setRsiStatus(result.technicalDetail().rsiStatus());
        e.setBuySignal(result.investmentAdvice());
        e.setFactorVersion(FACTOR_VERSION);

        try {
            Map<String, Object> reasons = new LinkedHashMap<>();
            reasons.put("data_quality", quality.name());
            reasons.put("technical_score", result.technicalScore());
            reasons.put("fundamental_score", result.fundamentalScore());
            reasons.put("sentiment_score", result.sentimentScore());
            reasons.put("technical_detail", Map.of(
                    "ma_score", result.technicalDetail().maScore(),
                    "rsi_score", result.technicalDetail().rsiScore(),
                    "macd_score", result.technicalDetail().macdScore()
            ));
            reasons.put("fundamental_detail", Map.of(
                    "valuation_score", result.fundamentalDetail().valuationScore(),
                    "profitability_score", result.fundamentalDetail().profitabilityScore(),
                    "financial_health_score", result.fundamentalDetail().financialHealthScore()
            ));
            reasons.put("sentiment_detail", Map.of(
                    "news_score", result.sentimentDetail().newsScore(),
                    "social_score", result.sentimentDetail().socialScore(),
                    "institutional_score", result.sentimentDetail().institutionalScore()
            ));
            reasons.put("weights", Map.of(
                    "technical", result.weights().technicalWeight(),
                    "fundamental", result.weights().fundamentalWeight(),
                    "sentiment", result.weights().sentimentWeight()
            ));
            reasons.put("risk_control", result.riskControlMessage());
            e.setReasonsJson(objectMapper.writeValueAsString(reasons));
        } catch (JsonProcessingException ex) {
            e.setReasonsJson("{}");
        }

        rows.add(e);
    }

    private Map<String, Object> toMap(StockTrendScore s, int rank) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank_no", rank);
        m.put("stock_code", s.getStockCode());
        m.put("exchange_type", s.getExchangeType());
        m.put("stock_name", s.getStockName());
        m.put("trade_date", s.getTradeDate().toString());
        m.put("total_score", s.getTotalScore());
        m.put("score_technical", s.getScoreTrend());
        m.put("score_fundamental", s.getScoreBias());
        m.put("score_sentiment", s.getScoreVolume());
        m.put("investment_advice", s.getBuySignal());
        m.put("factor_version", s.getFactorVersion());
        return m;
    }

    private static List<StockTrendScore> dedupe(List<StockTrendScore> rows) {
        Map<String, StockTrendScore> m = new LinkedHashMap<>();
        for (StockTrendScore s : rows) {
            String k = s.getStockCode() + "|" + s.getExchangeType();
            m.put(k, s);
        }
        return new ArrayList<>(m.values());
    }

    private Map<String, Object> emptyResult(LocalDate tradeDate, String msg) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("trade_date", tradeDate.toString());
        res.put("factor_version", FACTOR_VERSION);
        res.put("error", msg);
        res.put("scored", 0);
        res.put("items", List.of());
        return res;
    }
}
