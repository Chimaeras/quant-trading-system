package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.entity.StockTrendScore;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.StockTrendScoreRepository;
import com.quant.service.scoring.ExecutorLifecycle;
import com.quant.service.scoring.ScoringConstants;
import com.quant.service.scoring.ScoringRules;
import com.quant.service.scoring.ScoringUtils;
import com.quant.service.scoring.TechnicalIndicators;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 趋势交易打分
 * 重构版本：使用统一的工具类 TechnicalIndicators、ScoringRules、ScoringUtils
 */
@Service
@RequiredArgsConstructor
public class StockTrendScoringService {

    public static final String FACTOR_VERSION = ScoringConstants.VERSION_TREND;

    private final MarketDataRepository marketDataRepository;
    private final StockInfoRepository stockInfoRepository;
    private final PointInTimeUniverseProvider pointInTimeUniverseProvider;
    private final StockTrendScoreRepository stockTrendScoreRepository;
    private final PlatformTransactionManager transactionManager;

    @Value("${scoring.trend.bias-threshold:5.0}")
    private double defaultBiasThreshold;

    /** 局部打分线程池上限，与系统级异步线程池配置对齐 */
    @Value("${async.executor.max-pool-size:#{T(java.lang.Runtime).getRuntime().availableProcessors() * 2}}")
    private int maxScoringThreads;

    @Transactional(readOnly = true)
    public Map<String, Object> listScores(LocalDate tradeDate, int page, int size) {
        return listScores(tradeDate, page, size, StockTrendScore.SCORE_TYPE_TREND);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listScores(LocalDate tradeDate, int page, int size, String scoreType) {
        String type = scoreType != null && !scoreType.isBlank() ? scoreType : StockTrendScore.SCORE_TYPE_TREND;
        LocalDate d;
        if (tradeDate == null) {
            d = stockTrendScoreRepository.findMaxTradeDateByScoreType(type).orElse(null);
        } else {
            d = stockTrendScoreRepository.findMaxTradeDateOnOrBeforeByScoreType(type, tradeDate).orElse(null);
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
        Page<StockTrendScore> pg = stockTrendScoreRepository.findByTradeDateAndScoreTypeOrderByTotalScoreDesc(d, type, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score_type", type);
        out.put("trade_date", d.toString());
        List<Map<String, Object>> list = new ArrayList<>();
        int rankBase = pg.getNumber() * pg.getSize();
        int idx = 0;
        for (StockTrendScore s : pg.getContent()) {
            list.add(toMap(s, rankBase + (++idx)));
        }
        out.put("list", list);
        out.put("total", pg.getTotalElements());
        out.put("page", pg.getNumber());
        out.put("size", pg.getSize());
        return out;
    }

    public Map<String, Object> run(Map<String, Object> request) {
        long t0 = System.nanoTime();
        Map<String, Object> req = ScoringUtils.nvl(request, Map.of());
        LocalDate reqDate = ScoringUtils.parseDate(req.get("tradeDate"));
        LocalDate tradeDate = reqDate != null
                ? marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate)
                : marketDataRepository.findMaxTradeDate().orElse(LocalDate.now());
        PointInTimeUniverseProvider.UniverseView universe = pointInTimeUniverseProvider.loadAsOf(tradeDate);

        double biasThreshold = ScoringUtils.parseDouble(req.get("biasThreshold"), defaultBiasThreshold, 1.0, 50.0);
        boolean persist = ScoringUtils.parseBool(req.get("persist"), true);
        int threads = ScoringUtils.parseInt(req.get("threads"),
                Math.max(1, Runtime.getRuntime().availableProcessors()), 1, Math.max(1, maxScoringThreads));
        int lookbackDays = ScoringUtils.parseInt(req.get("lookbackDays"), ScoringConstants.DEFAULT_LOOKBACK_DAYS, 60, 2000);
        boolean bulkFetch = ScoringUtils.parseBool(req.get("bulkFetch"), true);
        boolean includeItems = ScoringUtils.parseBool(req.get("includeItems"), false);
        boolean excludeST = ScoringUtils.parseBool(req.get("excludeST"), true);
        double minAvgAmount = ScoringUtils.parseDouble(req.get("minAvgAmount"), ScoringUtils.DEFAULT_MIN_AVG_AMOUNT, 0d, 1_000_000_000_000d);
        int minListingDays = ScoringUtils.parseInt(req.get("minListingDays"), 60, 0, 3650);

        LocalDate rangeStart = tradeDate.minusDays(lookbackDays);
        List<Object[]> pairs = marketDataRepository.findDistinctStockPairsInRange(rangeStart, tradeDate);
        if (pairs.isEmpty()) {
            return emptyResult(tradeDate, "无可用行情数据", universe);
        }

        ConcurrentMap<String, String> nameCache = new ConcurrentHashMap<>();
        Map<String, LocalDate> listDateCache = new HashMap<>();
        Map<String, Integer> statusCache = new HashMap<>();
        for (StockInfo s : universe.stocks()) {
            if (s.getStockCode() == null || s.getExchangeType() == null) {
                continue;
            }
            String k = s.getStockCode() + "|" + s.getExchangeType();
            if (s.getStockName() != null && !s.getStockName().isBlank()) {
                nameCache.putIfAbsent(k, s.getStockName());
            }
            statusCache.put(k, s.getStatus());
            if (minListingDays > 0) {
                listDateCache.put(k, s.getListDate());
            }
        }
        final Map<String, LocalDate> listDateRef = listDateCache;
        final Map<String, Integer> statusRef = statusCache;
        final int minListingDaysFinal = minListingDays;
        final double minAvgAmountFinal = minAvgAmount;
        List<StockTrendScore> rows = Collections.synchronizedList(new ArrayList<>());
        int scanned;
        int skipped;

        long tScore0 = System.nanoTime();
        long barsFetched = 0;
        String dbMode;
        AtomicInteger skippedNoBarOnTradeDate = new AtomicInteger(0);

        if (bulkFetch) {
            dbMode = "bulk_range_fetch";
            List<MarketData> allBars = marketDataRepository
                    .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate);
            barsFetched = allBars.size();

            Map<String, List<MarketData>> grouped = new LinkedHashMap<>();
            for (MarketData m : allBars) {
                String key = m.getStockCode() + "|" + m.getExchangeType();
                grouped.computeIfAbsent(key, k -> new ArrayList<>(Math.min(lookbackDays, 600))).add(m);
            }

            List<String> keys = new ArrayList<>();
            for (Object[] pair : pairs) {
                if (pair == null || pair.length < 2) continue;
                keys.add(String.valueOf(pair[0]) + "|" + String.valueOf(pair[1]));
            }

            scanned = keys.size();
            if (threads <= 1) {
                int k = 0;
                for (String key : keys) {
                    int p = key.indexOf('|');
                    String stockCode = p > 0 ? key.substring(0, p) : key;
                    String exchangeType = p > 0 ? key.substring(p + 1) : "";
                    List<MarketData> bars = grouped.getOrDefault(key, List.of());
                    Optional<StockTrendScore> row = scoreOne(
                            stockCode, exchangeType, tradeDate, bars, nameCache, listDateRef, statusRef, minListingDaysFinal,
                            minAvgAmountFinal, biasThreshold, excludeST, skippedNoBarOnTradeDate);
                    if (row.isPresent()) rows.add(row.get());
                    else k++;
                }
                skipped = k;
            } else {
                ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
                    Thread t = new Thread(r);
                    t.setName("trend-score-bulk-" + t.getId());
                    t.setDaemon(true);
                    return t;
                });
                // finally 收敛线程池：submit 抛 RejectedExecutionException 或消费环节抛未捕获
                // 异常时，裸调用 shutdown 会被跳过，池与无界队列随请求泄漏（每请求一个池）。
                try {
                    CompletionService<Optional<StockTrendScore>> cs = new ExecutorCompletionService<>(pool);

                    for (String key : keys) {
                        cs.submit(() -> {
                            int p = key.indexOf('|');
                            String stockCode = p > 0 ? key.substring(0, p) : key;
                            String exchangeType = p > 0 ? key.substring(p + 1) : "";
                            List<MarketData> bars = grouped.getOrDefault(key, List.of());
                            return scoreOne(stockCode, exchangeType, tradeDate, bars, nameCache, listDateRef, statusRef, minListingDaysFinal,
                                    minAvgAmountFinal, biasThreshold, excludeST, skippedNoBarOnTradeDate);
                        });
                    }

                    int k = 0;
                    for (int i = 0; i < keys.size(); i++) {
                        try {
                            Optional<StockTrendScore> r = cs.take().get();
                            if (r.isPresent()) rows.add(r.get());
                            else k++;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            k++;
                        } catch (ExecutionException e) {
                            k++;
                        }
                    }
                    skipped = k;
                } finally {
                    ExecutorLifecycle.shutdownGracefully(pool);
                }
            }
        } else if (threads <= 1) {
            dbMode = "per_stock_queries_single_thread";
            int s = 0;
            int k = 0;
            for (Object[] pair : pairs) {
                if (pair == null || pair.length < 2) continue;
                String stockCode = String.valueOf(pair[0]);
                String exchangeType = String.valueOf(pair[1]);
                s++;

                List<MarketData> bars = marketDataRepository
                        .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
                                stockCode, exchangeType, rangeStart, tradeDate);
                barsFetched += bars.size();
                Optional<StockTrendScore> row = scoreOne(
                        stockCode, exchangeType, tradeDate, bars, nameCache, listDateRef, statusRef, minListingDaysFinal,
                        minAvgAmountFinal, biasThreshold, excludeST, skippedNoBarOnTradeDate);
                if (row.isEmpty()) {
                    k++;
                    continue;
                }
                rows.add(row.get());
            }
            scanned = s;
            skipped = k;
        } else {
            dbMode = "per_stock_queries_multi_thread";
            ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r);
                t.setName("trend-score-" + t.getId());
                t.setDaemon(true);
                return t;
            });
            // 同 bulk 分支：shutdown 必须走 finally，避免异常路径泄漏线程池。
            try {
                CompletionService<Optional<StockTrendScore>> cs = new ExecutorCompletionService<>(pool);

                int submitted = 0;
                for (Object[] pair : pairs) {
                    if (pair == null || pair.length < 2) continue;
                    String stockCode = String.valueOf(pair[0]);
                    String exchangeType = String.valueOf(pair[1]);
                    submitted++;
                    cs.submit(() -> {
                        List<MarketData> bars = marketDataRepository
                                .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
                                        stockCode, exchangeType, rangeStart, tradeDate);
                        return scoreOne(stockCode, exchangeType, tradeDate, bars, nameCache, listDateRef, statusRef, minListingDaysFinal,
                                minAvgAmountFinal, biasThreshold, excludeST, skippedNoBarOnTradeDate);
                    });
                }

                int s = submitted;
                int k = 0;
                for (int i = 0; i < submitted; i++) {
                    try {
                        Optional<StockTrendScore> r = cs.take().get();
                        if (r.isPresent()) {
                            rows.add(r.get());
                        } else {
                            k++;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        k++;
                    } catch (ExecutionException e) {
                        k++;
                    }
                }
                scanned = s;
                skipped = k;
            } finally {
                ExecutorLifecycle.shutdownGracefully(pool);
            }
        }
        long tScore1 = System.nanoTime();

        // ==================== P2: 板块内相对排名调整 ====================
        Map<String, String> industryMap = buildIndustryMap(universe.stocks());
        applyIntraSectorAdjustment(rows, industryMap);

        rows.sort((a, b) -> Double.compare(b.getTotalScore(), a.getTotalScore()));
        final List<StockTrendScore> rowsToPersist = dedupeByStock(rows);

        long tPersist0 = System.nanoTime();
        if (persist) {
            new TransactionTemplate(transactionManager).execute(status -> {
                stockTrendScoreRepository.deleteByTradeDateAndScoreType(tradeDate, StockTrendScore.SCORE_TYPE_TREND);
                stockTrendScoreRepository.flush();
                if (!rowsToPersist.isEmpty()) {
                    stockTrendScoreRepository.saveAll(rowsToPersist);
                }
                return null;
            });
        }
        long tPersist1 = System.nanoTime();

        List<Map<String, Object>> items = new ArrayList<>();
        if (includeItems) {
            int rank = 1;
            for (StockTrendScore s : rowsToPersist) {
                items.add(toMap(s, rank++));
            }
        }

        long t1 = System.nanoTime();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(t1 - t0);
        long scoreMs = TimeUnit.NANOSECONDS.toMillis(tScore1 - tScore0);
        long persistMs = TimeUnit.NANOSECONDS.toMillis(tPersist1 - tPersist0);
        double scoredPerSec = scoreMs > 0 ? (rowsToPersist.size() * 1000.0 / scoreMs) : 0;

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("trade_date", tradeDate.toString());
        res.put("factor_version", FACTOR_VERSION);
        res.put("bias_threshold", biasThreshold);
        res.put("persisted", persist && !rowsToPersist.isEmpty());
        res.put("threads_used", threads);
        res.put("lookback_days", lookbackDays);
        res.put("bulk_fetch", bulkFetch);
        res.put("db_mode", dbMode);
        res.put("bars_fetched", barsFetched);
        res.put("scanned", scanned);
        res.put("scored", rowsToPersist.size());
        res.put("skipped_insufficient", skipped);
        res.put("skipped_no_bar_on_trade_date", skippedNoBarOnTradeDate.get());
        res.put("elapsed_ms", elapsedMs);
        res.put("score_ms", scoreMs);
        res.put("persist_ms", persistMs);
        res.put("scored_per_sec", ScoringUtils.round2(scoredPerSec));
        res.put("include_items", includeItems);
        res.put("exclude_st", excludeST);
        res.put("min_avg_amount", minAvgAmount);
        res.put("items", items);
        res.putAll(universe.lineage());
        return res;
    }

    private static List<StockTrendScore> dedupeByStock(List<StockTrendScore> rows) {
        if (rows == null || rows.size() <= 1) {
            return rows;
        }
        List<StockTrendScore> out = new ArrayList<>(rows.size());
        Set<String> seen = new HashSet<>();
        for (StockTrendScore s : rows) {
            String key = s.getStockCode() + "|" + s.getExchangeType();
            if (seen.add(key)) {
                out.add(s);
            }
        }
        return out;
    }

    private Map<String, Object> emptyResult(LocalDate tradeDate, String message) {
        return emptyResult(tradeDate, message, null);
    }

    private Map<String, Object> emptyResult(LocalDate tradeDate, String message,
                                            PointInTimeUniverseProvider.UniverseView universe) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("message", message);
        res.put("trade_date", tradeDate.toString());
        res.put("factor_version", FACTOR_VERSION);
        res.put("items", List.of());
        if (universe != null) {
            res.putAll(universe.lineage());
        }
        return res;
    }

    private Optional<StockTrendScore> scoreOne(String stockCode, String exchangeType, LocalDate tradeDate,
                                                List<MarketData> bars, Map<String, String> nameCache,
                                                Map<String, LocalDate> listDateCache, Map<String, Integer> statusCache,
                                                int minListingDays, double minAvgAmount, double biasThreshold,
                                                boolean excludeST, AtomicInteger skippedNoBarOnTradeDate) {
        // 防御性按交易日去重（保留最后一条）：虽 market_data 有 (stock_code,exchange_type,trade_date) 唯一键，
        // 但跨渠道/历史脏数据下 bars 仍可能同一天两条，导致 close[L-1] 与 close[L] 取到同一天两根而失真。
        bars = dedupeByTradeDate(bars);
        if (bars == null || bars.size() < ScoringConstants.MIN_BARS_TECHNICAL) {
            return Optional.empty();
        }

        LocalDate lastBarDate = bars.get(bars.size() - 1).getTradeDate();
        if (lastBarDate == null || !tradeDate.equals(lastBarDate)) {
            if (skippedNoBarOnTradeDate != null) {
                skippedNoBarOnTradeDate.incrementAndGet();
            }
            return Optional.empty();
        }

        String stockName = nameCache.computeIfAbsent(stockCode + "|" + exchangeType, k -> {
            String nameValue = stockInfoRepository.findByStockCodeAndExchangeType(stockCode, exchangeType)
                    .map(StockInfo::getStockName)
                    .orElse("");
            if (nameValue == null || nameValue.isBlank()) {
                nameValue = stockInfoRepository.findFirstByStockCode(stockCode)
                        .map(StockInfo::getStockName)
                        .orElse("");
            }
            return nameValue == null ? "" : nameValue;
        });
        if (!ScoringUtils.hasMinListingTenure(
                listDateCache.get(stockCode + "|" + exchangeType), tradeDate, minListingDays, bars)) {
            return Optional.empty();
        }
        if (!ScoringUtils.isTradableForScoring(bars, stockCode, exchangeType, stockName,
                statusCache.get(stockCode + "|" + exchangeType), excludeST, true, true)) {
            return Optional.empty();
        }
        if (!ScoringUtils.passesMinAvgAmount(bars, minAvgAmount)) {
            return Optional.empty();
        }

        int n = bars.size();
        double[] close = new double[n];
        double[] vol = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] open = new double[n];
        double[] turnover = new double[n];
        for (int i = 0; i < n; i++) {
            MarketData m = bars.get(i);
            close[i] = m.getClosePrice() != null ? m.getClosePrice().doubleValue() : Double.NaN;
            high[i] = m.getHighPrice() != null ? m.getHighPrice().doubleValue() : Double.NaN;
            low[i] = m.getLowPrice() != null ? m.getLowPrice().doubleValue() : Double.NaN;
            open[i] = m.getOpenPrice() != null ? m.getOpenPrice().doubleValue() : Double.NaN;
            Long v = m.getVolume();
            vol[i] = v != null ? v.doubleValue() : 0;
            turnover[i] = m.getTurnoverRate() != null ? m.getTurnoverRate().doubleValue() : 0;
        }
        if (Arrays.stream(close).anyMatch(Double::isNaN)) {
            return Optional.empty();
        }

        int L = n - 1;

        // 预计算均线数组（一次性批量计算，后续 O(1) 取值，避免反复 smaAt）
        double[] ma5Arr = TechnicalIndicators.sma(close, 5);
        double[] ma10Arr = TechnicalIndicators.sma(close, 10);
        double[] ma20Arr = TechnicalIndicators.sma(close, 20);
        double[] ma60Arr = TechnicalIndicators.sma(close, 60);

        double ma5 = ma5Arr[L];
        double ma10 = ma10Arr[L];
        double ma20 = ma20Arr[L];
        if (Double.isNaN(ma5) || Double.isNaN(ma10) || Double.isNaN(ma20)) {
            return Optional.empty();
        }

        int prevIdx = n >= 5 ? n - 5 : L;
        double currSpread = spreadAt(ma5Arr, ma10Arr, ma20Arr, L);
        double prevSpread = spreadAt(ma5Arr, ma10Arr, ma20Arr, prevIdx);

        // === 核心趋势因子 (22分) ===
        String trendStatus = classifyTrend(ma5Arr, ma10Arr, ma20Arr, L, currSpread, prevSpread);
        double scoreTrend = ScoringRules.scoreTrend(trendStatus);
        // 调整到22分制：原30分制按比例缩放
        scoreTrend = scoreTrend * 22.0 / 30.0;

        // === 乖离率因子 (18分) ===
        double biasMa5 = (close[L] - ma5) / ma5 * 100.0;
        double scoreBias = ScoringRules.scoreBias(biasMa5, trendStatus, biasThreshold);
        scoreBias = scoreBias * 18.0 / 20.0;

        // === 量价因子 (10分) ===
        String volumeStatus = classifyVolume(close, vol, L);
        double scoreVolume = ScoringRules.scoreVolume(volumeStatus);
        scoreVolume = scoreVolume * 10.0 / 15.0;

        // === 均线支撑因子 (5分) ===
        boolean sup5 = maSupport(close[L], ma5);
        boolean sup10 = maSupport(close[L], ma10);
        double scoreSupport = (sup5 ? 2.5 : 0) + (sup10 ? 2.5 : 0);

        // === MACD因子 (10分) ===
        TechnicalIndicators.MacdResult macd = TechnicalIndicators.macd(close);
        String macdStatus = TechnicalIndicators.classifyMacd(macd, L);
        double scoreMacd = ScoringRules.scoreMacd(macdStatus);
        scoreMacd = scoreMacd * 10.0 / 15.0;

        // === RSI因子 (8分) ===
        double[] rsiArr = TechnicalIndicators.rsi(close, ScoringConstants.RSI_PERIOD);
        double rsi14 = rsiArr[L];
        String rsiStatus = classifyRsiDetailed(rsi14);
        double scoreRsi = ScoringRules.scoreRsiTrend(rsi14);
        scoreRsi = scoreRsi * 8.0 / 10.0;

        // ==================== P0: 换手率因子 (5分) ====================
        double turnoverRate = turnover[L];
        double priceChangeToday = close[L - 1] > 0 ? (close[L] - close[L - 1]) / close[L - 1] : 0;
        boolean isRising = priceChangeToday > 0;
        double scoreTurnover = ScoringRules.scoreTurnover(turnoverRate, priceChangeToday, isRising);

        // ==================== P0: 指标变化率因子 (10分) ====================
        double deltaRsi = rsi14 - rsiArr[L - 1];
        double scoreDeltaRsi = ScoringRules.scoreDeltaRsi(rsi14, deltaRsi);
        double macdHist = macd.macd()[L];
        double deltaMacdHist = macdHist - macd.macd()[L - 1];
        double scoreDeltaMacd = ScoringRules.scoreDeltaMacd(macdHist, deltaMacdHist, macdStatus);
        double scoreDelta = scoreDeltaRsi + scoreDeltaMacd; // 0-10

        // ==================== P1: 日内振幅与影线因子 (5分) ====================
        double amplitude = TechnicalIndicators.dailyAmplitudePct(high[L], low[L], close[L - 1]);
        double upperShadow = TechnicalIndicators.upperShadowRatio(open[L], close[L], high[L], low[L]);
        double lowerShadow = TechnicalIndicators.lowerShadowRatio(open[L], close[L], high[L], low[L]);
        double bodyRatio = TechnicalIndicators.bodyRatio(open[L], close[L], high[L], low[L]);
        double scoreAmplitude = ScoringRules.scoreCandlestick(amplitude, upperShadow, lowerShadow, bodyRatio, isRising);

        // ==================== P1: 连续量能状态因子 (3分) ====================
        int expandDays = TechnicalIndicators.consecutiveVolumeExpandDays(vol, L, ScoringConstants.VOLUME_HEAVY_RATIO);
        int shrinkDays = TechnicalIndicators.consecutiveVolumeShrinkDays(vol, L, ScoringConstants.VOLUME_SHRINK_RATIO);
        double scoreConsecutiveVol = ScoringRules.scoreConsecutiveVolume(expandDays, shrinkDays, isRising);

        // ==================== P1: 均线粘合发散因子 (2分) ====================
        double maConv = TechnicalIndicators.maConvergenceDegree(ma5, ma10, ma20, ma60Arr[L]);
        double maDivDir = TechnicalIndicators.maDivergenceDirection(ma5, ma20);
        double scoreMaConv = ScoringRules.scoreMaConvergence(maConv, maDivDir);

        // ==================== P2: 波动率收敛因子 (2分) ====================
        double volConv = TechnicalIndicators.volatilityConvergence(close, L, 10, 40);
        double volPct = TechnicalIndicators.volatilityPercentile(close, L, 60);
        boolean volExpanding = expandDays >= 1;
        double scoreVolConv = ScoringRules.scoreVolConvergence(volConv, volPct, volExpanding);

        // === 综合得分 ===
        double total = scoreTrend + scoreBias + scoreVolume + scoreSupport
                + scoreMacd + scoreRsi + scoreTurnover + scoreDelta
                + scoreAmplitude + scoreConsecutiveVol + scoreMaConv + scoreVolConv;
        total = ScoringUtils.clampScore(total);

        String buySignal = ScoringRules.getBuySignal(total, trendStatus);

        StockTrendScore e = new StockTrendScore();
        e.setStockCode(stockCode);
        e.setExchangeType(exchangeType);
        e.setStockName(stockName);
        e.setTradeDate(tradeDate);
        e.setTotalScore(ScoringUtils.round2(total));
        e.setScoreTrend(ScoringUtils.round2(scoreTrend));
        e.setScoreBias(ScoringUtils.round2(scoreBias));
        e.setScoreVolume(ScoringUtils.round2(scoreVolume));
        e.setScoreSupport(ScoringUtils.round2(scoreSupport));
        e.setScoreMacd(ScoringUtils.round2(scoreMacd));
        e.setScoreRsi(ScoringUtils.round2(scoreRsi));
        e.setScoreTurnover(ScoringUtils.round2(scoreTurnover));
        e.setScoreDelta(ScoringUtils.round2(scoreDelta));
        e.setScoreAmplitude(ScoringUtils.round2(scoreAmplitude));
        e.setTurnoverRate(ScoringUtils.round2(turnoverRate));
        e.setConsecutiveVolumeDays(expandDays);
        e.setTrendStatus(trendStatus);
        e.setVolumeStatus(volumeStatus);
        e.setMacdStatus(macdStatus);
        e.setRsiStatus(rsiStatus);
        e.setBuySignal(buySignal);
        e.setBiasThreshold(biasThreshold);
        e.setFactorVersion(FACTOR_VERSION);
        e.setScoreType(StockTrendScore.SCORE_TYPE_TREND);
        return Optional.of(e);
    }

    private Map<String, Object> toMap(StockTrendScore s, int rank) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank_no", rank);
        m.put("stock_code", s.getStockCode());
        m.put("exchange_type", s.getExchangeType());
        m.put("stock_name", s.getStockName());
        m.put("trade_date", s.getTradeDate().toString());
        m.put("total_score", s.getTotalScore());
        m.put("score_trend", s.getScoreTrend());
        m.put("score_bias", s.getScoreBias());
        m.put("score_volume", s.getScoreVolume());
        m.put("score_support", s.getScoreSupport());
        m.put("score_macd", s.getScoreMacd());
        m.put("score_rsi", s.getScoreRsi());
        m.put("score_turnover", s.getScoreTurnover());
        m.put("score_delta", s.getScoreDelta());
        m.put("score_amplitude", s.getScoreAmplitude());
        m.put("turnover_rate", s.getTurnoverRate());
        m.put("consecutive_volume_days", s.getConsecutiveVolumeDays());
        m.put("trend_status", s.getTrendStatus());
        m.put("volume_status", s.getVolumeStatus());
        m.put("macd_status", s.getMacdStatus());
        m.put("rsi_status", s.getRsiStatus());
        m.put("buy_signal", s.getBuySignal());
        m.put("factor_version", s.getFactorVersion());
        return m;
    }

    private static double spreadAt(double[] ma5Arr, double[] ma10Arr, double[] ma20Arr, int idx) {
        double ma5 = ma5Arr[idx];
        double ma10 = ma10Arr[idx];
        double ma20 = ma20Arr[idx];
        if (Double.isNaN(ma5) || Double.isNaN(ma10) || Double.isNaN(ma20)) {
            return 0;
        }
        if (ma5 > ma10 && ma10 > ma20) {
            return (ma5 - ma20) / ma20 * 100.0;
        }
        if (ma5 < ma10 && ma10 < ma20) {
            return (ma20 - ma5) / ma5 * 100.0;
        }
        return 0;
    }

    private static String classifyTrend(double[] ma5Arr, double[] ma10Arr, double[] ma20Arr,
                                         int L, double currSpread, double prevSpread) {
        double ma5 = ma5Arr[L];
        double ma10 = ma10Arr[L];
        double ma20 = ma20Arr[L];

        if (ma5 > ma10 && ma10 > ma20) {
            if (currSpread > prevSpread && currSpread > 5) {
                return "强势多头";
            }
            return "多头排列";
        }
        if (ma5 > ma10 && ma10 <= ma20) {
            return "弱势多头";
        }
        if (ma5 < ma10 && ma10 < ma20) {
            if (currSpread > prevSpread && currSpread > 5) {
                return "强势空头";
            }
            return "空头排列";
        }
        if (ma5 < ma10 && ma10 >= ma20) {
            return "弱势空头";
        }
        return "盘整";
    }

    private static String classifyVolume(double[] close, double[] vol, int L) {
        if (L < 6) return "量能正常";
        double volToday = vol[L];
        double sum = 0;
        int cnt = 0;
        for (int i = L - 5; i <= L - 2; i++) {
            sum += vol[i];
            cnt++;
        }
        double vol5dAvg = cnt > 0 ? sum / cnt : 0;
        if (vol5dAvg <= 1e-9) {
            return "量能正常";
        }
        double ratio = volToday / vol5dAvg;
        double prevClose = close[L - 1];
        double priceChangePct = prevClose != 0
                ? (close[L] - prevClose) / prevClose * 100.0
                : 0;

        if (ratio >= ScoringConstants.VOLUME_HEAVY_RATIO) {
            return priceChangePct > 0 ? "放量上涨" : "放量下跌";
        }
        if (ratio <= ScoringConstants.VOLUME_SHRINK_RATIO) {
            return priceChangePct > 0 ? "缩量上涨" : "缩量回调";
        }
        return "量能正常";
    }

    private static boolean maSupport(double price, double ma) {
        if (ma <= 1e-9) return false;
        double d = Math.abs(price - ma) / ma;
        return d <= ScoringConstants.MA_SUPPORT_TOLERANCE && price >= ma;
    }

    /** 按交易日去重（保序，保留最后一条），避免同一天两根 K 线导致 close[L-1]/close[L] 失真。 */
    static List<MarketData> dedupeByTradeDate(List<MarketData> bars) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, MarketData> byDate = new LinkedHashMap<>();
        for (MarketData bar : bars) {
            if (bar != null && bar.getTradeDate() != null) {
                byDate.put(bar.getTradeDate(), bar);
            }
        }
        return new ArrayList<>(byDate.values());
    }

    private static String classifyRsiDetailed(double rsi) {
        if (rsi > 70) return "超买";
        if (rsi > 60) return "强势买入";
        if (rsi >= 40) return "中性";
        if (rsi >= 30) return "弱势";
        return "超卖";
    }

    // ==================== P2: 板块内相对排名 ====================

    /**
     * 构建股票代码→行业映射
     */
    private Map<String, String> buildIndustryMap(List<StockInfo> stocks) {
        Map<String, String> map = new HashMap<>();
        for (var s : stocks) {
            if (s.getStockCode() == null || s.getExchangeType() == null) {
                continue;
            }
            String industry = s.getIndustry();
            if (industry != null && !industry.isBlank()) {
                map.put(s.getStockCode() + "|" + s.getExchangeType(), industry);
            }
        }
        return map;
    }

    /**
     * 板块内相对排名调整：同行业排名靠前的股票获得小幅加分
     */
    private void applyIntraSectorAdjustment(List<StockTrendScore> rows, Map<String, String> industryMap) {
        if (rows == null || rows.size() < 2 || industryMap.isEmpty()) return;

        // 按行业分组
        Map<String, List<StockTrendScore>> sectorGroups = new LinkedHashMap<>();
        for (StockTrendScore row : rows) {
            String key = row.getStockCode() + "|" + row.getExchangeType();
            String industry = industryMap.getOrDefault(key, "");
            sectorGroups.computeIfAbsent(industry, k -> new ArrayList<>()).add(row);
        }

        for (var entry : sectorGroups.entrySet()) {
            List<StockTrendScore> group = entry.getValue();
            if (entry.getKey().isBlank() || group.size() < 3) continue; // 无行业信息或样本太少，不做调整

            // 组内按总分排序
            group.sort((a, b) -> Double.compare(b.getTotalScore(), a.getTotalScore()));
            int size = group.size();

            // 按排名分位给予小幅调整
            for (int i = 0; i < size; i++) {
                StockTrendScore row = group.get(i);
                double percentile = (double) i / size;
                double adjustment;
                if (percentile <= 0.2) {
                    adjustment = 3.0;       // 行业前20%：+3
                } else if (percentile <= 0.4) {
                    adjustment = 1.5;       // 行业20-40%：+1.5
                } else if (percentile >= 0.8) {
                    adjustment = -2.0;      // 行业后20%：-2
                } else {
                    adjustment = 0;
                }
                if (adjustment != 0) {
                    row.setTotalScore(ScoringUtils.clampScore(
                            ScoringUtils.round2(row.getTotalScore() + adjustment)));
                }
            }
        }
    }
}
