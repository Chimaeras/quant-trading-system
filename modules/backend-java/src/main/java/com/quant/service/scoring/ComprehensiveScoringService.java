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
    private final MarketEnvironmentAnalyzer marketEnvironmentAnalyzer;
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
            double kdjScore,
            String maStatus,
            String rsiStatus,
            String macdStatus,
            String kdjStatus
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
            String institutionalStatus,
            String dataSource,
            double discountFactor
    ) {}

    /**
     * 对单只股票进行综合评分
     */
    public ComprehensiveResult scoreStock(List<MarketData> bars, StockInfo stockInfo,
                                           Double pe, Double pb, Double roe,
                                           Double debtRatio, Double currentRatio, String industry) {
        return scoreStock(bars, stockInfo, pe, pb, roe, debtRatio, currentRatio, industry, null);
    }

    /**
     * 对单只股票进行综合评分（可指定市场环境，避免重复计算）
     */
    public ComprehensiveResult scoreStock(List<MarketData> bars, StockInfo stockInfo,
                                           Double pe, Double pb, Double roe,
                                           Double debtRatio, Double currentRatio, String industry,
                                           String marketEnvironment) {
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
        TechnicalDetail technicalDetail = scoreTechnicalDimension(bars, close, L);
        double technicalScore = technicalDetail.maScore() + technicalDetail.rsiScore()
                + technicalDetail.macdScore() + technicalDetail.kdjScore();

        // 2. 基本面分析维度 (40分) - 使用统一评分规则
        FundamentalDetail fundamentalDetail = scoreFundamentalDimension(pe, pb, roe, debtRatio, currentRatio, industry);
        double fundamentalScore = fundamentalDetail.valuationScore() + fundamentalDetail.profitabilityScore()
                + fundamentalDetail.financialHealthScore();

        // 3. 市场情绪分析维度 (30分)
        EnhancedMarketSentimentService.SentimentAnalysisResult sentimentResult =
                enhancedMarketSentimentService.analyzeSentiment(bars, pe, industry);
        // 子分与 totalScore 保持一致（均已应用 discountFactor）
        double discount = sentimentResult.discountFactor();
        SentimentDetail sentimentDetail = new SentimentDetail(
                sentimentResult.newsSentimentScore() * discount,
                sentimentResult.socialMediaScore() * discount,
                sentimentResult.institutionalViewScore() * discount,
                sentimentResult.newsStatus(),
                sentimentResult.socialStatus(),
                sentimentResult.institutionStatus(),
                sentimentResult.dataSource(),
                sentimentResult.discountFactor()
        );
        double sentimentScore = sentimentResult.totalScore();

        // 4. 动态权重计算
        if (marketEnvironment == null) {
            marketEnvironment = inferMarketEnvironment(bars);
        }
        String stockType = inferStockType(bars, stockInfo != null ? stockInfo.getIndustry() : null, pe, pb, roe);
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

        // 6. 行业适配修正：乘以 0.5 避免行业偏差过大（与 MultiDim 保持一致）
        double industryAdjustment = ScoringRules.getIndustryAdjustment(industry) * 0.5;
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
     * 技术分析维度评分 (30分)：均线(8) + RSI(7) + MACD(7) + KDJ(8) = 30
     */
    private TechnicalDetail scoreTechnicalDimension(List<MarketData> bars, double[] close, int L) {
        // 均线排列评分 (8分)
        String maStatus = TechnicalIndicators.classifyMaArrangement(close, L);
        double maScore = ScoringRules.scoreMaArrangement(maStatus) * 0.8;

        // RSI评分 (7分)
        double rsi = TechnicalIndicators.rsiAt(close, L, ScoringConstants.RSI_PERIOD);
        String rsiStatus = TechnicalIndicators.classifyRsi(rsi);
        double rsiScore = ScoringUtils.clamp(ScoringRules.scoreRsi(rsi) * 0.7, 0, 7);

        // MACD评分 (7分)
        TechnicalIndicators.MacdResult macd = TechnicalIndicators.macd(close);
        String macdStatus = TechnicalIndicators.classifyMacd(macd, L);
        double macdScore = ScoringUtils.clamp(ScoringRules.scoreMacd(macdStatus) * 0.47, 0, 7);

        // KDJ评分 (8分)：缩放 [-12, +12] → [0, 8]，中性=4
        String kdjStatus = "中性";
        double kdjScore = 4.0;
        if (L >= ScoringConstants.KDJ_PERIOD && bars != null) {
            int n = bars.size();
            double[] high = new double[n];
            double[] low = new double[n];
            for (int i = 0; i < n; i++) {
                MarketData m = bars.get(i);
                high[i] = m.getHighPrice() != null ? m.getHighPrice().doubleValue() : close[i];
                low[i] = m.getLowPrice() != null ? m.getLowPrice().doubleValue() : close[i];
            }
            TechnicalIndicators.KdjResult kdj = TechnicalIndicators.kdj(high, low, close, ScoringConstants.KDJ_PERIOD);
            kdjStatus = TechnicalIndicators.classifyKdj(kdj, L);
            double rawKdj = ScoringRules.scoreKdj(kdjStatus);
            kdjScore = ScoringUtils.clamp(rawKdj / 12.0 * 4.0 + 4.0, 0, 8);
        }

        return new TechnicalDetail(maScore, rsiScore, macdScore, kdjScore,
                maStatus, rsiStatus, macdStatus, kdjStatus);
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

    /**
     * 估值水平评分（15分）：统一使用 ScoringRules 中的 PE/PB 评分规则。
     * PE 和 PB 各占 7.5 分，合计 15 分。
     */
    private double scoreValuation(Double pe, Double pb) {
        double peScore = ScoringRules.scorePE(pe);    // 0-15 分制
        double pbScore = ScoringRules.scorePB(pb);    // 0-15 分制
        // 标准化到 0-7.5 分 + 0-7.5 分 = 0-15 分
        return ScoringUtils.clamp(peScore / 2.0 + pbScore / 2.0, 0, 15);
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

        // 60日涨跌幅
        double closeNow = bars.get(n - 1).getClosePrice() != null ? bars.get(n - 1).getClosePrice().doubleValue() : 0;
        double close60dAgo = bars.get(n - 60).getClosePrice() != null ? bars.get(n - 60).getClosePrice().doubleValue() : closeNow;
        double change60 = close60dAgo > 1e-9 ? (closeNow - close60dAgo) / close60dAgo * 100 : 0;

        // 20日波动率 — 与 smaAt 保持一致，跳过 null 值
        double[] closeVals = new double[21];
        int validCount = 0;
        for (int i = 0; i < 21; i++) {
            var bar = bars.get(n - 21 + i);
            if (bar.getClosePrice() != null) {
                closeVals[i] = bar.getClosePrice().doubleValue();
                validCount++;
            } else {
                closeVals[i] = Double.NaN;
            }
        }
        double[] validClose = Arrays.stream(closeVals).filter(v -> !Double.isNaN(v)).toArray();
        if (validClose.length < 10) return "震荡市"; // 数据不足
        double mean = Arrays.stream(validClose).average().orElse(0);
        double var = Arrays.stream(validClose).map(v -> (v - mean) * (v - mean)).average().orElse(0);
        double vol = mean > 0 ? Math.sqrt(var) / mean * 100 : 0;

        // 均线位置：用最后 5 个有效值近似 MA5
        double ma5 = 0;
        int ma5Count = 0;
        for (int i = Math.max(0, validClose.length - 5); i < validClose.length; i++) {
            ma5 += validClose[i];
            ma5Count++;
        }
        ma5 = ma5Count > 0 ? ma5 / ma5Count : mean;
        double ma20 = mean;
        boolean isBearish = closeNow < ma20 && ma5 < ma20;

        if (change60 > 15 && vol < 25 && !isBearish) return "牛市";
        if (change60 < -15 || (isBearish && vol > 30)) return "熊市";
        return "震荡市";
    }

    /**
     * 股票类型分类，支持四种类型：价值股/成长股/周期股/防御股/普通股
     */
    private String inferStockType(List<MarketData> bars, String industry, Double pe, Double pb, Double roe) {
        // 计算 60 日波动率
        double vol60 = 0;
        if (bars != null && bars.size() >= 61) {
            int n = bars.size();
            double[] closeVals = new double[61];
            for (int i = 0; i < 61; i++) {
                MarketData m = bars.get(n - 61 + i);
                closeVals[i] = m.getClosePrice() != null ? m.getClosePrice().doubleValue() : 0;
            }
            double mean = Arrays.stream(closeVals).average().orElse(0);
            double var = Arrays.stream(closeVals).map(v -> (v - mean) * (v - mean)).average().orElse(0);
            vol60 = mean > 0 ? Math.sqrt(var) / mean * 100 : 0;
        }

        // 防御股：低波动 + 防御型行业（消费/医药/公用事业）
        boolean defensiveIndustry = industry != null && java.util.Set.of("医药", "消费", "食品饮料", "公用事业", "电力").contains(industry);
        if (vol60 < 15 && defensiveIndustry) return "防御股";
        if (vol60 < 12 && (pb != null && pb < 1.5)) return "防御股";

        // 周期股：高波动 + 周期型行业（钢铁/煤炭/有色/化工/汽车/房地产）
        boolean cyclicalIndustry = industry != null && java.util.Set.of("钢铁", "煤炭", "有色", "化工", "汽车", "房地产", "建筑材料").contains(industry);
        if (vol60 > 30 && cyclicalIndustry) return "周期股";
        if (vol60 > 35) return "周期股";

        // 价值股：低估值
        if (pe != null && pe < 20 && pb != null && pb < 1.5) return "价值股";

        // 成长股：高 ROE
        if (roe != null && roe > 15) return "成长股";

        return "普通股";
    }

    private ComprehensiveResult createEmptyResult(String reason) {
        return new ComprehensiveResult(
                0, 0, 0, 0, "观望", reason,
                new TechnicalDetail(0, 0, 0, 4.0, "数据不足", "数据不足", "数据不足", "数据不足"),
                new FundamentalDetail(0, 0, 0, "数据不足", "数据不足", "数据不足"),
                new SentimentDetail(0, 0, 0, "数据不足", "数据不足", "数据不足", "unavailable", 0.0),
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
        boolean excludeST = ScoringUtils.parseBool(req.get("excludeST"), true);
        double minAvgAmount = ScoringUtils.parseDouble(req.get("minAvgAmount"), 30_000_000d, 0d, 1_000_000_000_000d);
        int minListingDays = ScoringUtils.parseInt(req.get("minListingDays"), 60, 0, 3650);

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
                financialSnapshotLoader.loadLatestByStockKey(tradeDate);

        // 使用 MarketEnvironmentAnalyzer 统一计算市场环境（避免每只股票重复计算）
        MarketEnvironmentAnalyzer.MarketIndicators marketIndicators =
                marketEnvironmentAnalyzer.analyze(tradeDate, 60);
        String marketEnv = marketIndicators.marketEnvironment();

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
                        snapshotMap.get(key), tradeDate, rows, skipped, marketEnv, excludeST, minListingDays, minAvgAmount);
            }
        } else {
            final String finalMarketEnv = marketEnv;
            final boolean finalExcludeST = excludeST;
            final int finalMinListingDays = minListingDays;
            final double finalMinAvgAmount = minAvgAmount;
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
                            snapshotMap.get(key), tradeDate, rows, skipped, finalMarketEnv, finalExcludeST, finalMinListingDays, finalMinAvgAmount);
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
        res.put("exclude_st", excludeST);
        res.put("min_avg_amount", minAvgAmount);

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
                               LocalDate tradeDate, List<StockTrendScore> rows, AtomicInteger skipped,
                               String marketEnv, boolean excludeST, int minListingDays, double minAvgAmount) {
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

        ComprehensiveResult result = scoreStock(bars, info, pe, pb, roe, debtRatio, currentRatio, industry, marketEnv);

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
        // 注意：综合打分复用 StockTrendScore 实体，字段名与存储内容不同
        // scoreTrend → 技术面总分, scoreBias → 基本面总分, scoreVolume → 情绪面总分
        // 详细映射见 reasonsJson.score_field_mapping
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
            reasons.put("sentiment_data_source", result.sentimentDetail().dataSource());
            reasons.put("sentiment_discount_factor", result.sentimentDetail().discountFactor());
            if ("derived_from_technical".equals(result.sentimentDetail().dataSource())) {
                reasons.put("sentiment_disclaimer", "情绪面由技术指标推导，已应用可信度折扣，非真实舆情数据");
            }
            reasons.put("weights", Map.of(
                    "technical", result.weights().technicalWeight(),
                    "fundamental", result.weights().fundamentalWeight(),
                    "sentiment", result.weights().sentimentWeight()
            ));
            reasons.put("risk_control", result.riskControlMessage());
            Map<String, String> fieldMapping = new LinkedHashMap<>();
            fieldMapping.put("scoreTrend", "技术面总分");
            fieldMapping.put("scoreBias", "基本面总分");
            fieldMapping.put("scoreVolume", "情绪面总分");
            fieldMapping.put("scoreSupport", "均线排列得分");
            fieldMapping.put("scoreMacd", "MACD得分");
            fieldMapping.put("scoreRsi", "RSI得分");
            fieldMapping.put("trendStatus", "均线排列状态");
            fieldMapping.put("volumeStatus", "新闻情绪状态");
            fieldMapping.put("macdStatus", "MACD状态");
            fieldMapping.put("rsiStatus", "RSI状态");
            fieldMapping.put("buySignal", "投资建议");
            reasons.put("score_field_mapping", fieldMapping);
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
        // 入参已按得分降序排列，putIfAbsent 保留首个（得分最高）条目，避免重复键被低分覆盖
        Map<String, StockTrendScore> m = new LinkedHashMap<>();
        for (StockTrendScore s : rows) {
            String k = s.getStockCode() + "|" + s.getExchangeType();
            m.putIfAbsent(k, s);
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
