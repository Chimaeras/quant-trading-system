package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.entity.StockTrendScore;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.StockTrendScoreRepository;
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
 * 趋势交易打分（与《选股打分算法说明》一致）：trend(30)+bias(20)+volume(15)+support(10)+macd(15)+rsi(10)。
 */
@Service
@RequiredArgsConstructor
public class StockTrendScoringService {

    public static final String FACTOR_VERSION = "trend_signal_v1";

    private static final double VOLUME_SHRINK_RATIO = 0.7;
    private static final double VOLUME_HEAVY_RATIO = 1.5;
    private static final double MA_SUPPORT_TOLERANCE = 0.02;
    /** 与文档一致：趋势/MACD/RSI 等同时满足所需最小 K 线数 */
    private static final int MIN_BARS = 26;

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
            // 关键修复：允许传入“非交易日/无打分日”，自动对齐到 <=tradeDate 的最近一个已落库打分日
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
        Map<String, Object> req = request != null ? request : Map.of();
        LocalDate reqDate = parseDate(req.get("tradeDate"));
        LocalDate tradeDate = reqDate != null
                ? marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate)
                : marketDataRepository.findMaxTradeDate().orElse(LocalDate.now());

        double biasThreshold = parseDouble(req.get("biasThreshold"), defaultBiasThreshold, 1.0, 50.0);
        boolean persist = parseBool(req.get("persist"), true);
        int threads = (int) parseDouble(req.get("threads"), Math.max(1, Runtime.getRuntime().availableProcessors()), 1, 64);
        // 预留：如果你想用更短的窗口提升速度，可通过 lookbackDays 下调；默认保持原逻辑 520 天以保证结果一致性
        int lookbackDays = (int) parseDouble(req.get("lookbackDays"), 520, 60, 2000);
        boolean bulkFetch = parseBool(req.get("bulkFetch"), true);
        /** 默认 false：全市场打分时 items 极大，序列化慢且易导致客户端超时；列表请用 GET /scores */
        boolean includeItems = parseBool(req.get("includeItems"), false);

        LocalDate rangeStart = tradeDate.minusDays(lookbackDays);
        // 二阶段优化：默认 bulkFetch=true，优先用单次区间拉取 + 本地分组，显著减少 DB 往返
        List<Object[]> pairs = marketDataRepository.findDistinctStockPairsInRange(rangeStart, tradeDate);
        if (pairs.isEmpty()) {
            return emptyResult(tradeDate, "无可用行情数据");
        }

        // 并发打分：仓库对象本身线程安全；每个线程独立执行查询+计算（不共享 EntityManager/事务）
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
            // 一次性拉取区间内所有行情：1 次 DB 查询替代 N 次
            List<MarketData> allBars = marketDataRepository
                    .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(rangeStart, tradeDate);
            barsFetched = allBars.size();

            // 预加载股票名称：避免每只股票单独查 stock_info
            Map<String, String> nameMap = new HashMap<>();
            for (var s : stockInfoRepository.findAll()) {
                nameMap.put(s.getStockCode() + "|" + s.getExchangeType(), s.getStockName());
            }
            nameCache.putAll(nameMap);

            // 分组：按 stockCode|exchangeType 聚合 K 线
            Map<String, List<MarketData>> grouped = new LinkedHashMap<>();
            for (MarketData m : allBars) {
                String key = m.getStockCode() + "|" + m.getExchangeType();
                grouped.computeIfAbsent(key, k -> new ArrayList<>(Math.min(lookbackDays, 600))).add(m);
            }

            // 使用 pairs 作为“需要评分的股票集合”（避免对 range 内出现但不在 pairs 的异常情况）
            List<String> keys = new ArrayList<>(pairs.size());
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
                        return scoreOne(
                                stockCode, exchangeType, tradeDate, bars, nameCache, biasThreshold, skippedNoBarOnTradeDate);
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
                pool.shutdown();
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
                    // barsFetched 统计仅用于粗略观测；这里不做原子累加避免争用
                    return scoreOne(
                            stockCode, exchangeType, tradeDate, bars, nameCache, biasThreshold, skippedNoBarOnTradeDate);
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
            pool.shutdown();
            scanned = s;
            skipped = k;
        }
        long tScore1 = System.nanoTime();

        rows.sort((a, b) -> Double.compare(b.getTotalScore(), a.getTotalScore()));
        // 同一批次内若出现重复股票（并发/数据源重复），只保留总分最高的一条，避免 uk(stock_code,exchange_type,trade_date) 冲突
        final List<StockTrendScore> rowsToPersist = dedupeByStock(rows);

        long tPersist0 = System.nanoTime();
        if (persist) {
            new TransactionTemplate(transactionManager).execute(status -> {
                stockTrendScoreRepository.deleteByTradeDate(tradeDate);
                // 确保先落库删除再插入，避免同事务内 INSERT 与旧行冲突（重复跑任务、或批处理顺序问题）
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
        res.put("scored_per_sec", Math.round(scoredPerSec * 100.0) / 100.0);
        res.put("include_items", includeItems);
        res.put("items", items);
        return res;
    }

    /** 已按总分降序时，每种股票只保留第一条（分数最高） */
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

    private Optional<StockTrendScore> scoreOne(
            String stockCode,
            String exchangeType,
            LocalDate tradeDate,
            List<MarketData> bars,
            Map<String, String> nameCache,
            double biasThreshold,
            AtomicInteger skippedNoBarOnTradeDate) {

        if (bars == null || bars.size() < MIN_BARS) {
            return Optional.empty();
        }

        // 仅当「最后一根 K 线」的交易日等于本次打分日时才参与评分，避免用更早交易日冒充当日
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
        double ma5 = smaAt(close, L, 5);
        double ma10 = smaAt(close, L, 10);
        double ma20 = smaAt(close, L, 20);
        if (Double.isNaN(ma5) || Double.isNaN(ma10) || Double.isNaN(ma20)) {
            return Optional.empty();
        }

        int prevIdx = n >= 5 ? n - 5 : L;
        double currSpread = spreadAt(close, L);
        double prevSpread = spreadAt(close, prevIdx);

        String trendStatus = classifyTrend(close, L, currSpread, prevSpread);
        int trendStrength = trendStrengthValue(trendStatus);
        double scoreTrend = scoreTrend(trendStatus);

        double biasMa5 = (close[L] - ma5) / ma5 * 100.0;
        double scoreBias = scoreBias(biasMa5, trendStatus, trendStrength, biasThreshold);

        String volumeStatus = classifyVolume(close, vol, L);
        double scoreVolume = scoreVolume(volumeStatus);

        boolean sup5 = maSupport(close[L], ma5);
        boolean sup10 = maSupport(close[L], ma10);
        double scoreSupport = (sup5 ? 5 : 0) + (sup10 ? 5 : 0);

        MacdPack macd = macdLines(close);
        String macdStatus = classifyMacd(macd, L);
        double scoreMacd = scoreMacd(macdStatus);

        double rsi12 = rsiRolling(close, L, 12);
        String rsiStatus = classifyRsi(rsi12);
        double scoreRsi = scoreRsi(rsiStatus);

        double total = scoreTrend + scoreBias + scoreVolume + scoreSupport + scoreMacd + scoreRsi;
        total = Math.max(0, Math.min(100, total));

        String buySignal = buySignal(total, trendStatus);

        String name = nameCache.computeIfAbsent(stockCode + "|" + exchangeType, k -> {
            // 优先精确匹配 code+exchange；若 stock_info 未维护 exchangeType，则退化为仅按 code 取第一条名称
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
        e.setTotalScore(round2(total));
        e.setScoreTrend(round2(scoreTrend));
        e.setScoreBias(round2(scoreBias));
        e.setScoreVolume(round2(scoreVolume));
        e.setScoreSupport(round2(scoreSupport));
        e.setScoreMacd(round2(scoreMacd));
        e.setScoreRsi(round2(scoreRsi));
        e.setTrendStatus(trendStatus);
        e.setVolumeStatus(volumeStatus);
        e.setMacdStatus(macdStatus);
        e.setRsiStatus(rsiStatus);
        e.setBuySignal(buySignal);
        e.setBiasThreshold(biasThreshold);
        e.setFactorVersion(FACTOR_VERSION);
        return Optional.of(e);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
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

    private static double smaAt(double[] close, int i, int period) {
        if (i < period - 1) return Double.NaN;
        double s = 0;
        for (int j = i - period + 1; j <= i; j++) {
            s += close[j];
        }
        return s / period;
    }

    /** 文档 4.1：多头/空头时的发散幅度；其余形态返回 0 */
    private static double spreadAt(double[] close, int idx) {
        double ma5 = smaAt(close, idx, 5);
        double ma10 = smaAt(close, idx, 10);
        double ma20 = smaAt(close, idx, 20);
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
        double ma5 = smaAt(close, L, 5);
        double ma10 = smaAt(close, L, 10);
        double ma20 = smaAt(close, L, 20);

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

    private static int trendStrengthValue(String trendStatus) {
        return switch (trendStatus) {
            case "强势多头" -> 90;
            case "多头排列" -> 75;
            case "弱势多头" -> 60;
            case "盘整" -> 50;
            case "弱势空头" -> 40;
            case "空头排列" -> 30;
            case "强势空头" -> 20;
            default -> 50;
        };
    }

    private static double scoreTrend(String trendStatus) {
        return switch (trendStatus) {
            case "强势多头" -> 30;
            case "多头排列" -> 26;
            case "弱势多头" -> 18;
            case "盘整" -> 12;
            case "弱势空头" -> 8;
            case "空头排列" -> 4;
            case "强势空头" -> 0;
            default -> 12;
        };
    }

    private static double scoreBias(double bias, String trendStatus, int trendStrength, double base) {
        boolean strongBull = "强势多头".equals(trendStatus) && trendStrength >= 70;
        double effective = strongBull ? base * 1.5 : base;

        if (Double.isNaN(bias)) {
            bias = 0;
        }
        if (bias < 0) {
            if (bias > -3) return 20;
            if (bias > -5) return 16;
            return 8;
        }
        if (bias < 2) return 18;
        if (bias < base) return 14;
        if (bias > effective) return 4;
        if (bias > base && strongBull) return 10;
        return 4;
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

        if (ratio >= VOLUME_HEAVY_RATIO) {
            return priceChangePct > 0 ? "放量上涨" : "放量下跌";
        }
        if (ratio <= VOLUME_SHRINK_RATIO) {
            return priceChangePct > 0 ? "缩量上涨" : "缩量回调";
        }
        return "量能正常";
    }

    private static double scoreVolume(String volumeStatus) {
        return switch (volumeStatus) {
            case "缩量回调" -> 15;
            case "放量上涨" -> 12;
            case "量能正常" -> 10;
            case "缩量上涨" -> 6;
            case "放量下跌" -> 0;
            default -> 10;
        };
    }

    private static boolean maSupport(double price, double ma) {
        if (ma <= 1e-9) return false;
        double d = Math.abs(price - ma) / ma;
        return d <= MA_SUPPORT_TOLERANCE && price >= ma;
    }

    private static double[] ema(double[] values, int span) {
        double alpha = 2.0 / (span + 1);
        double[] out = new double[values.length];
        out[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }

    private record MacdPack(double[] dif, double[] dea) {}

    private static MacdPack macdLines(double[] close) {
        double[] ema12 = ema(close, 12);
        double[] ema26 = ema(close, 26);
        double[] dif = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            dif[i] = ema12[i] - ema26[i];
        }
        double[] dea = ema(dif, 9);
        return new MacdPack(dif, dea);
    }

    private static String classifyMacd(MacdPack m, int L) {
        if (L < 1) return "多头";
        double difPrev = m.dif()[L - 1];
        double difCurr = m.dif()[L];
        double deaPrev = m.dea()[L - 1];
        double deaCurr = m.dea()[L];
        double prevDd = difPrev - deaPrev;
        double currDd = difCurr - deaCurr;

        boolean golden = prevDd <= 0 && currDd > 0;
        boolean death = prevDd >= 0 && currDd < 0;
        boolean crossUpZero = difPrev <= 0 && difCurr > 0;
        boolean crossDownZero = difPrev >= 0 && difCurr < 0;

        if (golden && difCurr > 0) {
            return "零轴上金叉";
        }
        if (crossUpZero) {
            return "上穿零轴";
        }
        if (golden) {
            return "金叉";
        }
        if (death) {
            return "死叉";
        }
        if (crossDownZero) {
            return "下穿零轴";
        }
        if (difCurr > 0 && deaCurr > 0) {
            return "多头";
        }
        if (difCurr < 0 && deaCurr < 0) {
            return "空头";
        }
        return "多头";
    }

    private static double scoreMacd(String macdStatus) {
        return switch (macdStatus) {
            case "零轴上金叉" -> 15;
            case "金叉" -> 12;
            case "上穿零轴" -> 10;
            case "多头" -> 8;
            case "空头" -> 2;
            case "下穿零轴" -> 0;
            case "死叉" -> 0;
            default -> 8;
        };
    }

    /** 文档 3.4：rolling SMA RSI，不足周期时按 50 */
    private static double rsiRolling(double[] close, int i, int period) {
        if (i < period) {
            return 50.0;
        }
        double gainSum = 0;
        double lossSum = 0;
        for (int k = i - period + 1; k <= i; k++) {
            double delta = close[k] - close[k - 1];
            gainSum += Math.max(delta, 0);
            lossSum += Math.max(-delta, 0);
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        if (avgLoss < 1e-12) {
            return 100.0;
        }
        if (avgGain < 1e-12) {
            return 0.0;
        }
        double rs = avgGain / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }

    private static String classifyRsi(double rsi12) {
        if (rsi12 > 70) return "超买";
        if (rsi12 > 60) return "强势买入";
        if (rsi12 >= 40) return "中性";
        if (rsi12 >= 30) return "弱势";
        return "超卖";
    }

    private static double scoreRsi(String rsiStatus) {
        return switch (rsiStatus) {
            case "超卖" -> 10;
            case "强势买入" -> 8;
            case "中性" -> 5;
            case "弱势" -> 3;
            case "超买" -> 0;
            default -> 5;
        };
    }

    private static String buySignal(double score, String trendStatus) {
        Set<String> strongBuyTrend = Set.of("强势多头", "多头排列");
        Set<String> buyTrend = Set.of("强势多头", "多头排列", "弱势多头");
        Set<String> strongSellTrend = Set.of("空头排列", "强势空头");

        if (score >= 75 && strongBuyTrend.contains(trendStatus)) {
            return "强烈买入";
        }
        if (score >= 60 && buyTrend.contains(trendStatus)) {
            return "买入";
        }
        if (score >= 45) {
            return "持有";
        }
        if (score >= 30) {
            return "观望";
        }
        if (strongSellTrend.contains(trendStatus)) {
            return "强烈卖出";
        }
        return "卖出";
    }

    private static LocalDate parseDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        return LocalDate.parse(s);
    }

    private static double parseDouble(Object o, double def, double min, double max) {
        if (o == null) return def;
        if (o instanceof Number n) {
            double v = n.doubleValue();
            return Math.max(min, Math.min(max, v));
        }
        try {
            double v = Double.parseDouble(String.valueOf(o).trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean parseBool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }
}
