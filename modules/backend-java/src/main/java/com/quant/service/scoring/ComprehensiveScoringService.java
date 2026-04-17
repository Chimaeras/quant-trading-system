package com.quant.service.scoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.entity.StockTrendScore;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.StockTrendScoreRepository;
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
 * 支持动态权重分配：
 * - 根据市场环境（牛市/熊市/震荡市）调整权重
 * - 根据股票特性（价值股/成长股/周期股/防御股）调整权重
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComprehensiveScoringService {

    public static final String FACTOR_VERSION = "comprehensive_v1";
    private static final int MIN_BARS = 60;

    private final MarketDataRepository marketDataRepository;
    private final StockInfoRepository stockInfoRepository;
    private final StockTrendScoreRepository stockTrendScoreRepository;
    private final EnhancedMarketSentimentService enhancedMarketSentimentService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    /**
     * 综合评分结果
     */
    public record ComprehensiveResult(
            double technicalScore,       // 技术分析得分 (0-30)
            double fundamentalScore,     // 基本面得分 (0-40)
            double sentimentScore,       // 情绪面得分 (0-30)
            double compositeScore,       // 综合得分 (0-100)
            String investmentAdvice,     // 投资建议
            String riskControlMessage,   // 风险控制提示
            TechnicalDetail technicalDetail,
            FundamentalDetail fundamentalDetail,
            SentimentDetail sentimentDetail,
            DynamicWeightingService.DynamicWeights weights
    ) {}

    public record TechnicalDetail(
            double maScore,              // 均线排列得分 (0-10)
            double rsiScore,             // RSI得分 (0-10)
            double macdScore,            // MACD得分 (0-10)
            String maStatus,
            String rsiStatus,
            String macdStatus
    ) {}

    public record FundamentalDetail(
            double valuationScore,       // 估值水平得分 (0-15)
            double profitabilityScore,   // 盈利能力得分 (0-15)
            double financialHealthScore, // 财务健康度得分 (0-10)
            String valuationStatus,
            String profitabilityStatus,
            String financialHealthStatus
    ) {}

    public record SentimentDetail(
            double newsScore,            // 新闻舆情得分 (0-15)
            double socialScore,          // 社交媒体得分 (0-8)
            double institutionalScore,   // 机构观点得分 (0-7)
            String newsStatus,
            String socialStatus,
            String institutionalStatus
    ) {}

    /**
     * 对单只股票进行综合评分
     */
    public ComprehensiveResult scoreStock(
            List<MarketData> bars,
            StockInfo stockInfo,
            Double pe,
            Double pb,
            Double roe,
            Double debtRatio,
            Double currentRatio,
            String industry
    ) {
        if (bars == null || bars.size() < MIN_BARS) {
            return createEmptyResult("数据不足，需要至少" + MIN_BARS + "根K线");
        }

        int n = bars.size();
        int L = n - 1;

        // 1. 技术分析维度 (30分)
        TechnicalDetail technicalDetail = scoreTechnicalDimension(bars, L);
        double technicalScore = technicalDetail.maScore() + technicalDetail.rsiScore() + technicalDetail.macdScore();

        // 2. 基本面分析维度 (40分)
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

        // 4. 动态权重计算（简化版，基于技术面推断市场环境）
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
        double industryAdjustment = CompositeScoringService.getIndustryAdjustment(industry);
        compositeScore = Math.max(0, Math.min(100, compositeScore + industryAdjustment));

        // 7. 投资建议
        String investmentAdvice = getInvestmentAdvice(compositeScore, technicalScore, fundamentalScore);

        // 8. 风险控制提示
        String riskControlMessage = getRiskControlMessage(
                compositeScore, fundamentalScore, technicalScore, fundamentalDetail.financialHealthScore()
        );

        return new ComprehensiveResult(
                technicalScore, fundamentalScore, sentimentScore, compositeScore,
                investmentAdvice, riskControlMessage,
                technicalDetail, fundamentalDetail, sentimentDetail, weights
        );
    }

    /**
     * 技术分析维度评分 (30分)
     */
    private TechnicalDetail scoreTechnicalDimension(List<MarketData> bars, int L) {
        double[] close = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            close[i] = bars.get(i).getClosePrice() != null
                    ? bars.get(i).getClosePrice().doubleValue() : Double.NaN;
        }

        // 均线排列评分 (10分)
        double ma5 = smaAt(close, L, 5);
        double ma10 = smaAt(close, L, 10);
        double ma20 = smaAt(close, L, 20);
        double ma60 = smaAt(close, L, 60);
        String maStatus = classifyMaArrangement(ma5, ma10, ma20, ma60, close, L);
        double maScore = scoreMaArrangement(maStatus);

        // RSI评分 (10分)
        double rsi = rsiRolling(close, L, 14);
        String rsiStatus = classifyRsi(rsi);
        double rsiScore = scoreRsi(rsi, rsiStatus);

        // MACD评分 (10分)
        MacdPack macd = macdLines(close);
        String macdStatus = classifyMacd(macd, L);
        double macdScore = scoreMacd(macdStatus);

        return new TechnicalDetail(maScore, rsiScore, macdScore, maStatus, rsiStatus, macdStatus);
    }

    /**
     * 基本面分析维度评分 (40分)
     */
    private FundamentalDetail scoreFundamentalDimension(
            Double pe, Double pb, Double roe, Double debtRatio, Double currentRatio, String industry) {

        // 估值水平评分 (15分)
        double valuationScore = scoreValuation(pe, pb);
        String valuationStatus = classifyValuation(pe, pb);

        // 盈利能力评分 (15分)
        double profitabilityScore = scoreProfitability(roe);
        String profitabilityStatus = classifyProfitability(roe);

        // 财务健康度评分 (10分)
        double financialHealthScore = scoreFinancialHealth(debtRatio, currentRatio);
        String financialHealthStatus = classifyFinancialHealth(debtRatio, currentRatio);

        return new FundamentalDetail(
                valuationScore, profitabilityScore, financialHealthScore,
                valuationStatus, profitabilityStatus, financialHealthStatus
        );
    }

    // ==================== 技术分析评分方法 ====================

    private String classifyMaArrangement(double ma5, double ma10, double ma20, double ma60, double[] close, int L) {
        if (Double.isNaN(ma5) || Double.isNaN(ma10) || Double.isNaN(ma20) || Double.isNaN(ma60)) {
            return "中性排列";
        }

        boolean isBullish = ma5 > ma10 && ma10 > ma20 && ma20 > ma60;
        boolean isBearish = ma5 < ma10 && ma10 < ma20 && ma20 < ma60;

        // 检查趋势方向
        double ma5Prev = smaAt(close, L - 1, 5);
        double ma10Prev = smaAt(close, L - 1, 10);
        boolean isTrendingUp = !Double.isNaN(ma5Prev) && !Double.isNaN(ma10Prev) && ma5 > ma5Prev && ma10 > ma10Prev;

        if (isBullish && isTrendingUp) return "多头排列";
        if (isBullish) return "弱势多头";
        if (isBearish) return "空头排列";
        return "中性排列";
    }

    private double scoreMaArrangement(String status) {
        return switch (status) {
            case "多头排列" -> 10;
            case "弱势多头" -> 7;
            case "中性排列" -> 5;
            case "空头排列" -> 2;
            default -> 5;
        };
    }

    private double rsiRolling(double[] close, int i, int period) {
        if (i < period) return 50.0;
        double gainSum = 0, lossSum = 0;
        for (int k = i - period + 1; k <= i; k++) {
            double delta = close[k] - close[k - 1];
            gainSum += Math.max(delta, 0);
            lossSum += Math.max(-delta, 0);
        }
        if (lossSum < 1e-12) return 100.0;
        if (gainSum < 1e-12) return 0.0;
        return 100.0 - 100.0 / (1.0 + gainSum / lossSum);
    }

    private String classifyRsi(double rsi) {
        if (rsi < 30) return "超卖";
        if (rsi <= 70) return "中性";
        return "超买";
    }

    private double scoreRsi(double rsi, String status) {
        if (rsi < 30) return 10;  // 超卖，可能反弹
        if (rsi <= 40) return 8;
        if (rsi <= 60) return 7;
        if (rsi <= 70) return 5;
        return 3;  // 超买
    }

    private record MacdPack(double[] dif, double[] dea) {}

    private MacdPack macdLines(double[] close) {
        double[] ema12 = ema(close, 12);
        double[] ema26 = ema(close, 26);
        double[] dif = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            dif[i] = ema12[i] - ema26[i];
        }
        double[] dea = ema(dif, 9);
        return new MacdPack(dif, dea);
    }

    private double[] ema(double[] values, int span) {
        double alpha = 2.0 / (span + 1);
        double[] out = new double[values.length];
        out[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }

    private String classifyMacd(MacdPack m, int L) {
        if (L < 1) return "中性";
        double difPrev = m.dif()[L - 1], difCurr = m.dif()[L];
        double deaPrev = m.dea()[L - 1], deaCurr = m.dea()[L];
        double prevDd = difPrev - deaPrev, currDd = difCurr - deaCurr;
        boolean golden = prevDd <= 0 && currDd > 0;
        boolean death = prevDd >= 0 && currDd < 0;

        if (golden && difCurr > 0 && deaCurr > 0) return "零轴上金叉";
        if (golden) return "金叉";
        if (death && difCurr < 0 && deaCurr < 0) return "零轴下死叉";
        if (death) return "死叉";
        if (difCurr > 0 && deaCurr > 0) return "多头";
        if (difCurr < 0 && deaCurr < 0) return "空头";
        return "中性";
    }

    private double scoreMacd(String status) {
        return switch (status) {
            case "零轴上金叉" -> 10;
            case "金叉" -> 8;
            case "多头" -> 6;
            case "中性" -> 5;
            case "空头" -> 3;
            case "死叉" -> 2;
            case "零轴下死叉" -> 0;
            default -> 5;
        };
    }

    // ==================== 基本面评分方法 ====================

    private double scoreValuation(Double pe, Double pb) {
        double score = 7.5; // 中等估值默认值

        // PE评分
        if (pe != null) {
            if (pe < 15) score += 3;
            else if (pe < 25) score += 1.5;
            else if (pe > 40) score -= 2;
            else if (pe > 60) score -= 4;
        }

        // PB评分
        if (pb != null) {
            if (pb < 1) score += 2;
            else if (pb < 1.5) score += 1;
            else if (pb > 3) score -= 1;
        }

        return Math.max(0, Math.min(15, score));
    }

    private String classifyValuation(Double pe, Double pb) {
        if (pe == null) return "中等估值";
        if (pe < 20 && (pb == null || pb < 1.5)) return "低估值";
        if (pe > 40 || (pb != null && pb > 3)) return "高估值";
        return "中等估值";
    }

    private double scoreProfitability(Double roe) {
        if (roe == null) return 7.5;
        if (roe > 20) return 15;
        if (roe > 15) return 12;
        if (roe > 10) return 9;
        if (roe > 5) return 6;
        return 3;
    }

    private String classifyProfitability(Double roe) {
        if (roe == null) return "一般";
        if (roe > 15) return "优秀";
        if (roe > 10) return "良好";
        if (roe > 5) return "一般";
        return "较差";
    }

    private double scoreFinancialHealth(Double debtRatio, Double currentRatio) {
        double score = 5;

        if (debtRatio != null) {
            if (debtRatio < 40) score += 3;
            else if (debtRatio < 60) score += 1;
            else if (debtRatio > 70) score -= 2;
        }

        if (currentRatio != null) {
            if (currentRatio > 2) score += 2;
            else if (currentRatio > 1.5) score += 1;
            else if (currentRatio < 1) score -= 2;
        }

        return Math.max(0, Math.min(10, score));
    }

    private String classifyFinancialHealth(Double debtRatio, Double currentRatio) {
        if (debtRatio == null) return "中等";
        if (debtRatio < 50 && (currentRatio == null || currentRatio > 1.5)) return "健康";
        if (debtRatio > 70 || (currentRatio != null && currentRatio < 1)) return "潜在风险";
        return "中等";
    }

    // ==================== 综合评分方法 ====================

    private double calculateWeightedScore(
            double techScore, double fundScore, double sentScore,
            DynamicWeightingService.DynamicWeights weights,
            double techMax, double fundMax, double sentMax) {

        // 归一化各维度得分到0-100
        double techNorm = techScore / techMax * 100;
        double fundNorm = fundScore / fundMax * 100;
        double sentNorm = sentScore / sentMax * 100;

        // 加权计算
        return techNorm * weights.technicalWeight()
                + fundNorm * weights.fundamentalWeight()
                + sentNorm * weights.sentimentWeight();
    }

    private String inferMarketEnvironment(List<MarketData> bars) {
        if (bars.size() < 60) return "震荡市";
        int n = bars.size();
        double closeNow = bars.get(n - 1).getClosePrice() != null
                ? bars.get(n - 1).getClosePrice().doubleValue() : 0;
        double close60dAgo = bars.get(n - 60).getClosePrice() != null
                ? bars.get(n - 60).getClosePrice().doubleValue() : closeNow;
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

    private String getInvestmentAdvice(double composite, double tech, double fund) {
        if (composite >= 85 && tech >= 20) return "强烈买入";
        if (composite >= 70) return "买入";
        if (composite >= 55) return "持有";
        if (composite >= 30) return "观望";
        return "强烈卖出";
    }

    private String getRiskControlMessage(double composite, double fund, double tech, double financialHealth) {
        StringBuilder msg = new StringBuilder();
        if (composite < 60 && financialHealth < 6) {
            msg.append("财务健康度不足，建议谨慎；");
        }
        if (composite < 50 && tech < 15) {
            msg.append("技术面弱势，避免追涨；");
        }
        if (composite < 40) {
            msg.append("综合表现较弱，建议观望；");
        }
        return msg.length() > 0 ? msg.toString() : "风险可控";
    }

    // ==================== 辅助方法 ====================

    private double smaAt(double[] close, int i, int period) {
        if (i < period - 1) return Double.NaN;
        double sum = 0;
        for (int j = i - period + 1; j <= i; j++) sum += close[j];
        return sum / period;
    }

    private ComprehensiveResult createEmptyResult(String reason) {
        return new ComprehensiveResult(
                0, 0, 0, 0, "观望", reason,
                new TechnicalDetail(0, 0, 0, "数据不足", "数据不足", "数据不足"),
                new FundamentalDetail(0, 0, 0, "数据不足", "数据不足", "数据不足"),
                new SentimentDetail(0, 0, 0, "数据不足", "数据不足", "数据不足"),
                new DynamicWeightingService.DynamicWeights(0.33, 0.34, 0.33)
        );
    }

    /**
     * 批量运行综合打分
     */
    @Transactional
    public Map<String, Object> runBatch(Map<String, Object> request) {
        long t0 = System.nanoTime();
        Map<String, Object> req = request != null ? request : Map.of();
        LocalDate reqDate = parseDate(req.get("tradeDate"));
        LocalDate tradeDate = reqDate != null
                ? marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate)
                : marketDataRepository.findMaxTradeDate().orElse(LocalDate.now());

        boolean persist = parseBool(req.get("persist"), true);
        int lookbackDays = (int) parseDouble(req.get("lookbackDays"), 520, 60, 2000);
        int threads = (int) parseDouble(req.get("threads"), Math.max(1, Runtime.getRuntime().availableProcessors()), 1, 64);
        boolean includeItems = parseBool(req.get("includeItems"), false);

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
                        tradeDate, rows, skipped);
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
                            tradeDate, rows, skipped);
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
            pool.shutdown();
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
            for (StockTrendScore s : toSave.stream().limit(200).toList()) {
                items.add(toMap(s, rank++));
            }
            res.put("items", items);
        } else {
            res.put("items", List.of());
        }
        return res;
    }

    private void processStock(
            String key,
            List<MarketData> bars,
            StockInfo info,
            LocalDate tradeDate,
            List<StockTrendScore> rows,
            AtomicInteger skipped) {

        if (bars.size() < MIN_BARS) {
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
        String industry = info != null ? info.getIndustry() : null;

        ComprehensiveResult result = scoreStock(bars, info, pe, null, null, null, null, industry);

        StockTrendScore e = new StockTrendScore();
        e.setStockCode(stockCode);
        e.setExchangeType(exchangeType);
        e.setStockName(info != null ? info.getStockName() : "");
        e.setTradeDate(tradeDate);
        e.setTotalScore(round2(result.compositeScore()));
        e.setScoreTrend(round2(result.technicalScore()));
        e.setScoreBias(round2(result.fundamentalScore()));
        e.setScoreVolume(round2(result.sentimentScore()));
        e.setScoreSupport(round2(result.technicalDetail().maScore()));
        e.setScoreMacd(round2(result.technicalDetail().macdScore()));
        e.setScoreRsi(round2(result.technicalDetail().rsiScore()));
        e.setTrendStatus(result.technicalDetail().maStatus());
        e.setVolumeStatus(result.sentimentDetail().newsStatus());
        e.setMacdStatus(result.technicalDetail().macdStatus());
        e.setRsiStatus(result.technicalDetail().rsiStatus());
        e.setBuySignal(result.investmentAdvice());
        e.setFactorVersion(FACTOR_VERSION);

        try {
            Map<String, Object> reasons = new LinkedHashMap<>();
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
        return "true".equals(s) || "1".equals(s);
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

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
