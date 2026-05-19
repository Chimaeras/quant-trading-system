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
    private final StockTrendScoreRepository stockTrendScoreRepository;
    private final PlatformTransactionManager transactionManager;

    @Value("${scoring.trend.bias-threshold:5.0}")
    private double defaultBiasThreshold;

    @Transactional(readOnly = true)
    public Map<String, Object> listScores(LocalDate tradeDate, int page, int size) {
        LocalDate d;
        if (tradeDate == null) {
            d = stockTrendScoreRepository.findMaxTradeDate().orElse(null);
        } else {
            d = stockTrendScoreRepository.findMaxTradeDateOnOrBefore(tradeDate).orElse(null);
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
        Page<StockTrendScore> pg = stockTrendScoreRepository.findByTradeDateOrderByTotalScoreDesc(d, p);
        Map<String, Object> out = new LinkedHashMap<>();
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

        double biasThreshold = ScoringUtils.parseDouble(req.get("biasThreshold"), defaultBiasThreshold, 1.0, 50.0);
        boolean persist = ScoringUtils.parseBool(req.get("persist"), true);
        int threads = ScoringUtils.parseInt(req.get("threads"),
                Math.max(1, Runtime.getRuntime().availableProcessors()), 1, 64);
        int lookbackDays = ScoringUtils.parseInt(req.get("lookbackDays"), ScoringConstants.DEFAULT_LOOKBACK_DAYS, 60, 2000);
        boolean bulkFetch = ScoringUtils.parseBool(req.get("bulkFetch"), true);
        boolean includeItems = ScoringUtils.parseBool(req.get("includeItems"), false);

        LocalDate rangeStart = tradeDate.minusDays(lookbackDays);
        List<Object[]> pairs = marketDataRepository.findDistinctStockPairsInRange(rangeStart, tradeDate);
        if (pairs.isEmpty()) {
            return emptyResult(tradeDate, "无可用行情数据");
        }

        ConcurrentMap<String, String> nameCache = new ConcurrentHashMap<>();
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

            Map<String, String> nameMap = new HashMap<>();
            for (var s : stockInfoRepository.findAll()) {
                nameMap.put(s.getStockCode() + "|" + s.getExchangeType(), s.getStockName());
            }
            nameCache.putAll(nameMap);

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
                            stockCode, exchangeType, tradeDate, bars, nameCache, biasThreshold, skippedNoBarOnTradeDate);
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
                CompletionService<Optional<StockTrendScore>> cs = new ExecutorCompletionService<>(pool);

                for (String key : keys) {
                    cs.submit(() -> {
                        int p = key.indexOf('|');
                        String stockCode = p > 0 ? key.substring(0, p) : key;
                        String exchangeType = p > 0 ? key.substring(p + 1) : "";
                        List<MarketData> bars = grouped.getOrDefault(key, List.of());
                        return scoreOne(stockCode, exchangeType, tradeDate, bars, nameCache, biasThreshold, skippedNoBarOnTradeDate);
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
                ExecutorLifecycle.shutdownGracefully(pool);
                skipped = k;
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
                        stockCode, exchangeType, tradeDate, bars, nameCache, biasThreshold, skippedNoBarOnTradeDate);
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
                    return scoreOne(stockCode, exchangeType, tradeDate, bars, nameCache, biasThreshold, skippedNoBarOnTradeDate);
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
            ExecutorLifecycle.shutdownGracefully(pool);
            scanned = s;
            skipped = k;
        }
        long tScore1 = System.nanoTime();

        rows.sort((a, b) -> Double.compare(b.getTotalScore(), a.getTotalScore()));
        final List<StockTrendScore> rowsToPersist = dedupeByStock(rows);

        long tPersist0 = System.nanoTime();
        if (persist) {
            new TransactionTemplate(transactionManager).execute(status -> {
                stockTrendScoreRepository.deleteByTradeDate(tradeDate);
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
        res.put("items", items);
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
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("message", message);
        res.put("trade_date", tradeDate.toString());
        res.put("factor_version", FACTOR_VERSION);
        res.put("items", List.of());
        return res;
    }

    private Optional<StockTrendScore> scoreOne(String stockCode, String exchangeType, LocalDate tradeDate,
                                                List<MarketData> bars, Map<String, String> nameCache,
                                                double biasThreshold, AtomicInteger skippedNoBarOnTradeDate) {
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

        int n = bars.size();
        double[] close = new double[n];
        double[] vol = new double[n];
        for (int i = 0; i < n; i++) {
            MarketData m = bars.get(i);
            close[i] = m.getClosePrice() != null ? m.getClosePrice().doubleValue() : Double.NaN;
            Long v = m.getVolume();
            vol[i] = v != null ? v.doubleValue() : 0;
        }
        if (Arrays.stream(close).anyMatch(Double::isNaN)) {
            return Optional.empty();
        }

        int L = n - 1;

        // 使用统一工具类计算均线
        double ma5 = TechnicalIndicators.smaAt(close, L, 5);
        double ma10 = TechnicalIndicators.smaAt(close, L, 10);
        double ma20 = TechnicalIndicators.smaAt(close, L, 20);
        if (Double.isNaN(ma5) || Double.isNaN(ma10) || Double.isNaN(ma20)) {
            return Optional.empty();
        }

        int prevIdx = n >= 5 ? n - 5 : L;
        double currSpread = spreadAt(close, L);
        double prevSpread = spreadAt(close, prevIdx);

        String trendStatus = classifyTrend(close, L, currSpread, prevSpread);
        int trendStrength = ScoringRules.trendStrength(trendStatus);
        double scoreTrend = ScoringRules.scoreTrend(trendStatus);

        double biasMa5 = (close[L] - ma5) / ma5 * 100.0;
        double scoreBias = ScoringRules.scoreBias(biasMa5, trendStatus, biasThreshold);

        String volumeStatus = classifyVolume(close, vol, L);
        double scoreVolume = ScoringRules.scoreVolume(volumeStatus);

        boolean sup5 = maSupport(close[L], ma5);
        boolean sup10 = maSupport(close[L], ma10);
        double scoreSupport = (sup5 ? 5 : 0) + (sup10 ? 5 : 0);

        // 使用统一工具类计算MACD
        TechnicalIndicators.MacdResult macd = TechnicalIndicators.macd(close);
        String macdStatus = TechnicalIndicators.classifyMacd(macd, L);
        double scoreMacd = ScoringRules.scoreMacd(macdStatus);

        // 使用统一工具类计算RSI
        double rsi12 = TechnicalIndicators.rsiAt(close, L, 12);
        String rsiStatus = classifyRsiDetailed(rsi12);
        double scoreRsi = ScoringRules.scoreRsiByStatus(rsiStatus);

        double total = scoreTrend + scoreBias + scoreVolume + scoreSupport + scoreMacd + scoreRsi;
        total = ScoringUtils.clampScore(total);

        String buySignal = ScoringRules.getBuySignal(total, trendStatus);

        String name = nameCache.computeIfAbsent(stockCode + "|" + exchangeType, k -> {
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

        StockTrendScore e = new StockTrendScore();
        e.setStockCode(stockCode);
        e.setExchangeType(exchangeType);
        e.setStockName(name);
        e.setTradeDate(tradeDate);
        e.setTotalScore(ScoringUtils.round2(total));
        e.setScoreTrend(ScoringUtils.round2(scoreTrend));
        e.setScoreBias(ScoringUtils.round2(scoreBias));
        e.setScoreVolume(ScoringUtils.round2(scoreVolume));
        e.setScoreSupport(ScoringUtils.round2(scoreSupport));
        e.setScoreMacd(ScoringUtils.round2(scoreMacd));
        e.setScoreRsi(ScoringUtils.round2(scoreRsi));
        e.setTrendStatus(trendStatus);
        e.setVolumeStatus(volumeStatus);
        e.setMacdStatus(macdStatus);
        e.setRsiStatus(rsiStatus);
        e.setBuySignal(buySignal);
        e.setBiasThreshold(biasThreshold);
        e.setFactorVersion(FACTOR_VERSION);
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
        m.put("trend_status", s.getTrendStatus());
        m.put("volume_status", s.getVolumeStatus());
        m.put("macd_status", s.getMacdStatus());
        m.put("rsi_status", s.getRsiStatus());
        m.put("buy_signal", s.getBuySignal());
        m.put("factor_version", s.getFactorVersion());
        return m;
    }

    private static double spreadAt(double[] close, int idx) {
        double ma5 = TechnicalIndicators.smaAt(close, idx, 5);
        double ma10 = TechnicalIndicators.smaAt(close, idx, 10);
        double ma20 = TechnicalIndicators.smaAt(close, idx, 20);
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

    private static String classifyTrend(double[] close, int L, double currSpread, double prevSpread) {
        double ma5 = TechnicalIndicators.smaAt(close, L, 5);
        double ma10 = TechnicalIndicators.smaAt(close, L, 10);
        double ma20 = TechnicalIndicators.smaAt(close, L, 20);

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

    private static String classifyRsiDetailed(double rsi) {
        if (rsi > 70) return "超买";
        if (rsi > 60) return "强势买入";
        if (rsi >= 40) return "中性";
        if (rsi >= 30) return "弱势";
        return "超卖";
    }
}
