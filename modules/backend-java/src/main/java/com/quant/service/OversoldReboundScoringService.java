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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class OversoldReboundScoringService {

    /** 与前端、模板约定：超跌反弹因子版本 */
    public static final String FACTOR_VERSION = "oversold_rebound_v1";

    private final MarketDataRepository marketDataRepository;
    private final StockInfoRepository stockInfoRepository;
    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final SelectorTemplateRepository selectorTemplateRepository;

    /**
     * 计算超跌反弹打分；默认落库到 selector_result / selector_result_item（persist=true）。
     */
    @Transactional
    public Map<String, Object> run(Map<String, Object> request) {
        Map<String, Object> req = mergeTemplate(request != null ? request : Map.of());

        LocalDate reqDate = parseDate(req.get("tradeDate"), LocalDate.now());
        LocalDate tradeDate = marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate);

        int topN = parseInt(req.get("topN"), 50, 1, 300);
        double minTotalScore = parseDouble(req.get("minTotalScore"), 60, 0, 100);
        boolean excludeST = parseBool(req.get("excludeST"), true);
        boolean persist = parseBool(req.get("persist"), true);

        LocalDate rangeStart = tradeDate.minusDays(520);
        List<Object[]> pairs = marketDataRepository.findDistinctStockPairsInRange(rangeStart, tradeDate);
        if (pairs.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("message", "无可用行情数据");
            empty.put("trade_date", tradeDate.toString());
            empty.put("factor_version", FACTOR_VERSION);
            empty.put("items", List.of());
            return empty;
        }

        Map<String, String> nameCache = new HashMap<>();
        List<ScoreResult> passed = new ArrayList<>();
        int scanned = 0;

        for (Object[] pair : pairs) {
            if (pair == null || pair.length < 2) continue;
            String stockCode = String.valueOf(pair[0]);
            String exchangeType = String.valueOf(pair[1]);
            scanned++;

            List<MarketData> bars = marketDataRepository
                    .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(stockCode, exchangeType, rangeStart, tradeDate);
            ScoreResult r = scoreOne(stockCode, exchangeType, tradeDate, bars, nameCache, excludeST);
            if (r == null) continue;

            if (r.totalScore >= minTotalScore
                    && r.trendScore40 > 25
                    && r.volumeScore30 > 15
                    && r.oversoldScore30 > 15) {
                passed.add(r);
            }
        }

        passed.sort((a, b) -> Double.compare(b.totalScore, a.totalScore));
        List<ScoreResult> top = passed.stream().limit(topN).toList();

        String resultId = null;
        if (persist) {
            resultId = persistResult(tradeDate, req, top);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 1;
        for (ScoreResult r : top) {
            Map<String, Object> item = r.toMap();
            item.put("rank_no", rank++);
            out.add(item);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        if (resultId != null) res.put("result_id", resultId);
        res.put("trade_date", tradeDate.toString());
        res.put("factor_version", FACTOR_VERSION);
        res.put("scanned", scanned);
        res.put("passed", passed.size());
        res.put("lookback_days", 260);
        res.put("top_n", topN);
        res.put("min_total_score", minTotalScore);
        res.put("exclude_st", excludeST);
        res.put("persisted", persist);
        res.put("items", out);
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

    private String persistResult(LocalDate tradeDate, Map<String, Object> req, List<ScoreResult> top) {
        String resultId = genResultId();
        double minTotalScore = parseDouble(req.get("minTotalScore"), 60, 0, 100);
        boolean excludeST = parseBool(req.get("excludeST"), true);
        int topN = parseInt(req.get("topN"), 50, 1, 300);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("factor", FACTOR_VERSION);
        snapshot.put("minTotalScore", minTotalScore);
        snapshot.put("minTrendScore40", 25);
        snapshot.put("minVolumeScore30", 15);
        snapshot.put("minOversoldScore30", 15);
        snapshot.put("excludeST", excludeST);
        snapshot.put("weights", Map.of("trend", 0.4, "volume", 0.3, "oversold", 0.3));

        SelectorResult result = new SelectorResult();
        result.setResultId(resultId);
        result.setTradeDate(tradeDate);
        result.setLookbackDays(260);
        result.setTopN(topN);
        result.setMinAvgAmount(0.0);
        result.setMinTrendScore(25.0);
        result.setTemplateId(String.valueOf(req.getOrDefault("templateId", "")).trim().isBlank()
                ? null : String.valueOf(req.get("templateId")).trim());
        result.setFactorVersion(FACTOR_VERSION);
        result.setFactorSnapshot(snapshot);
        selectorResultRepository.save(result);

        selectorResultItemRepository.deleteByResultId(resultId);
        int rank = 1;
        for (ScoreResult r : top) {
            SelectorResultItem item = new SelectorResultItem();
            item.setResultId(resultId);
            item.setRankNo(rank++);
            item.setStockCode(r.stockCode);
            item.setExchangeType(r.exchangeType);
            item.setStockName(r.stockName);
            item.setFinalScore(r.totalScore);
            item.setTrendScore((double) r.trendScore40);
            item.setMomentumScore((double) r.oversoldScore30);
            item.setVolumeScore((double) r.volumeScore30);
            item.setRiskPenalty(0.0);
            item.setRet20(r.ret20);
            item.setRet60(r.ret60);
            item.setVolatility20(r.weightedScore);
            item.setMaxDrawdown60(0.0);
            item.setAvgAmount20(r.avgAmount20);

            Map<String, Object> reasons = new LinkedHashMap<>();
            reasons.put("factor", FACTOR_VERSION);
            reasons.put("trend_score_40", r.trendScore40);
            reasons.put("volume_score_30", r.volumeScore30);
            reasons.put("oversold_score_30", r.oversoldScore30);
            reasons.put("weighted_score", r.weightedScore);
            reasons.put("detail", r.detail != null ? r.detail : Map.of());
            item.setReasons(reasons);
            selectorResultItemRepository.save(item);
        }
        return resultId;
    }

    private String genResultId() {
        return "OR" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private ScoreResult scoreOne(String stockCode, String exchangeType, LocalDate tradeDate, List<MarketData> bars,
                                 Map<String, String> nameCache, boolean excludeST) {
        if (bars == null || bars.size() < 260) return null;

        List<Double> close = new ArrayList<>();
        List<Double> open = new ArrayList<>();
        List<Double> volume = new ArrayList<>();
        List<Double> amount = new ArrayList<>();
        for (MarketData b : bars) {
            if (b.getTradeDate() == null || b.getClosePrice() == null) continue;
            double c = b.getClosePrice().doubleValue();
            if (c <= 0) continue;
            close.add(c);
            open.add(b.getOpenPrice() != null ? b.getOpenPrice().doubleValue() : c);
            volume.add(b.getVolume() != null ? b.getVolume().doubleValue() : 0d);
            amount.add(b.getAmount() != null ? b.getAmount().doubleValue() : 0d);
        }
        if (close.size() < 260) return null;

        String stockName = nameCache.computeIfAbsent(stockCode + ":" + exchangeType, k -> getStockName(stockCode, exchangeType));
        if (excludeST && stockName != null && stockName.toUpperCase().contains("ST")) return null;

        int n = close.size();
        double lastClose = close.get(n - 1);

        // 60/120日收益率（按交易日近似）
        double ret60 = (n >= 61) ? pct(lastClose, close.get(n - 61)) : 0;
        double ret120 = (n >= 121) ? pct(lastClose, close.get(n - 121)) : 0;
        double ret20 = (n >= 21) ? pct(lastClose, close.get(n - 21)) : 0;
        double avgAmount20 = avg(amount, n - 20, n - 1);

        // 均线（20/60/120）
        double ma20 = avg(close, n - 20, n - 1);
        double ma60 = avg(close, n - 60, n - 1);
        double ma120 = avg(close, n - 120, n - 1);

        // 周K连续阴线：用“每周第一根开盘/最后一根收盘”近似
        int consecutiveBearWeeks = consecutiveBearWeeks(bars);

        // 成交量：5日均量 vs 30日均量
        double v5 = avg(volume, n - 5, n - 1);
        double v30 = avg(volume, n - 30, n - 1);
        double vRatio = v30 <= 0 ? 0 : v5 / v30;

        // 量价配合：下跌时放量/缩量、近期温和放量
        VolumePriceType vp = volumePriceType(close, volume);

        // RSI14
        double rsi14 = rsi(close, 14);

        // 布林带（20日）：下轨 = MA20 - 2*STD20
        double std20 = std(close, n - 20, n - 1);
        double bollMid = ma20;
        double bollLower = bollMid - 2 * std20;
        double bollLowerExtreme = bollMid - 3.5 * std20; // 低于下轨 1.5 倍标准差（按文字直译）

        // 1年分位（近252交易日）
        int from252 = Math.max(0, n - 252);
        double min252 = min(close, from252, n - 1);
        double max252 = max(close, from252, n - 1);
        double percentile = (max252 - min252) <= 0 ? 0.5 : (lastClose - min252) / (max252 - min252);

        // --- 趋势判断（40分）
        int trendScore = 0;
        // 价格趋势（20）
        trendScore += scoreRet60(ret60);
        trendScore += scoreRet120(ret120);
        // 均线系统（10）
        if (lastClose < ma20) trendScore += 3;
        if (lastClose < ma60) trendScore += 4;
        if (lastClose < ma120) trendScore += 3;
        // 周K形态（10）
        trendScore += scoreBearWeeks(consecutiveBearWeeks);

        // --- 成交量验证（30分）
        int volumeScore = 0;
        // 近期成交量（15）
        if (vRatio > 0.8) volumeScore += 15;
        else if (vRatio >= 0.5) volumeScore += 10;
        // 量价配合（15）
        volumeScore += switch (vp) {
            case DOWN_VOL_EXPAND -> 10;
            case DOWN_VOL_SHRINK_THEN_WARM_EXPAND -> 5;
            default -> 0;
        };

        // --- 超跌程度（30分）
        int oversoldScore = 0;
        // RSI（10）
        if (rsi14 < 30) oversoldScore += 10;
        else if (rsi14 < 40) oversoldScore += 5;
        // 布林带（10）
        if (lastClose < bollLowerExtreme) oversoldScore += 10;
        else if (lastClose < bollLower) oversoldScore += 8;
        // 历史分位（10）
        if (percentile < 0.10) oversoldScore += 10;
        else if (percentile < 0.20) oversoldScore += 5;
        else if (percentile < 0.30) oversoldScore += 2;

        double totalScore = trendScore + volumeScore + oversoldScore; // 0-100（40+30+30）
        double weightedScore = (trendScore / 40.0) * 100 * 0.4
                + (volumeScore / 30.0) * 100 * 0.3
                + (oversoldScore / 30.0) * 100 * 0.3;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ret20_pct", round2(ret20));
        detail.put("ret60_pct", round2(ret60));
        detail.put("ret120_pct", round2(ret120));
        detail.put("avg_amount20", round2(avgAmount20));
        detail.put("ma20", round2(ma20));
        detail.put("ma60", round2(ma60));
        detail.put("ma120", round2(ma120));
        detail.put("consecutive_bear_weeks", consecutiveBearWeeks);
        detail.put("vol_ratio_5_30", round2(vRatio));
        detail.put("volume_price_type", vp.name());
        detail.put("rsi14", round2(rsi14));
        detail.put("boll_mid", round2(bollMid));
        detail.put("boll_lower", round2(bollLower));
        detail.put("boll_lower_extreme", round2(bollLowerExtreme));
        detail.put("percentile_1y", round4(percentile));

        ScoreResult r = new ScoreResult();
        r.stockCode = stockCode;
        r.exchangeType = exchangeType;
        r.stockName = stockName != null ? stockName : stockCode;
        r.asOfDate = tradeDate.toString();
        r.trendScore40 = trendScore;
        r.volumeScore30 = volumeScore;
        r.oversoldScore30 = oversoldScore;
        r.totalScore = round2(totalScore);
        r.weightedScore = round2(weightedScore);
        r.ret20 = round2(ret20);
        r.ret60 = round2(ret60);
        r.avgAmount20 = round2(avgAmount20);
        r.detail = detail;
        return r;
    }

    private int scoreRet60(double ret60Pct) {
        if (ret60Pct <= -20) return 15;
        if (ret60Pct <= -10) return 10;
        if (ret60Pct <= -5) return 5;
        return 0;
    }

    private int scoreRet120(double ret120Pct) {
        if (ret120Pct <= -30) return 5;
        if (ret120Pct <= -20) return 3;
        if (ret120Pct <= -10) return 1;
        return 0;
    }

    private int scoreBearWeeks(int weeks) {
        if (weeks >= 5) return 10;
        if (weeks == 4) return 8;
        if (weeks == 3) return 5;
        return 0;
    }

    private int consecutiveBearWeeks(List<MarketData> bars) {
        // 聚合到周：key = ISO year-week
        record W(LocalDate firstDate, double open, LocalDate lastDate, double close) {}
        Map<Long, W> map = new LinkedHashMap<>();

        for (MarketData b : bars) {
            if (b.getTradeDate() == null || b.getClosePrice() == null) continue;
            LocalDate d = b.getTradeDate();
            int week = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            int year = d.get(IsoFields.WEEK_BASED_YEAR);
            long key = year * 100L + week;

            double o = b.getOpenPrice() != null ? b.getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
            double c = b.getClosePrice().doubleValue();

            W prev = map.get(key);
            if (prev == null) {
                map.put(key, new W(d, o, d, c));
            } else {
                // 更新首日/末日
                LocalDate first = prev.firstDate().isAfter(d) ? d : prev.firstDate();
                double openV = prev.firstDate().isAfter(d) ? o : prev.open();
                LocalDate last = prev.lastDate().isBefore(d) ? d : prev.lastDate();
                double closeV = prev.lastDate().isBefore(d) ? c : prev.close();
                map.put(key, new W(first, openV, last, closeV));
            }
        }

        // 按周顺序统计最新连续阴线周数
        List<W> weeks = new ArrayList<>(map.values());
        weeks.sort(Comparator.comparing(W::lastDate));

        int cnt = 0;
        for (int i = weeks.size() - 1; i >= 0; i--) {
            W w = weeks.get(i);
            if (w.close() < w.open()) cnt++;
            else break;
        }
        return cnt;
    }

    private enum VolumePriceType {
        DOWN_VOL_EXPAND,
        DOWN_VOL_SHRINK_THEN_WARM_EXPAND,
        DOWN_VOL_SHRINK
    }

    private VolumePriceType volumePriceType(List<Double> close, List<Double> volume) {
        int n = close.size();
        if (n < 40) return VolumePriceType.DOWN_VOL_SHRINK;

        int from = Math.max(0, n - 30);
        double start = close.get(from);
        double end = close.get(n - 1);
        boolean down = end < start;

        double vFirstHalf = avg(volume, from, from + 14);
        double vSecondHalf = avg(volume, from + 15, n - 1);

        double vLast5 = avg(volume, n - 5, n - 1);
        double vPrev5 = avg(volume, n - 10, n - 6);

        if (down) {
            if (vSecondHalf > vFirstHalf * 1.05) return VolumePriceType.DOWN_VOL_EXPAND;
            boolean shrink = vSecondHalf < vFirstHalf * 0.95;
            boolean warmExpand = vLast5 > vPrev5 * 1.05;
            if (shrink && warmExpand) return VolumePriceType.DOWN_VOL_SHRINK_THEN_WARM_EXPAND;
            if (shrink) return VolumePriceType.DOWN_VOL_SHRINK;
            return VolumePriceType.DOWN_VOL_SHRINK_THEN_WARM_EXPAND;
        }

        // 非下跌阶段：不加分（用 shrink 视作不满足）
        return VolumePriceType.DOWN_VOL_SHRINK;
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

    private boolean parseBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isBlank()) return def;
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "y".equals(s);
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

    private double toDouble(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ignore) {
            return def;
        }
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

    private double std(List<Double> list, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(list.size() - 1, to);
        if (f > t) return 0;
        double mean = avg(list, f, t);
        double var = 0;
        int n = 0;
        for (int i = f; i <= t; i++) {
            double d = list.get(i) - mean;
            var += d * d;
            n++;
        }
        return n > 1 ? Math.sqrt(var / n) : 0;
    }

    private double min(List<Double> list, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(list.size() - 1, to);
        double m = Double.POSITIVE_INFINITY;
        for (int i = f; i <= t; i++) m = Math.min(m, list.get(i));
        return m == Double.POSITIVE_INFINITY ? 0 : m;
    }

    private double max(List<Double> list, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(list.size() - 1, to);
        double m = Double.NEGATIVE_INFINITY;
        for (int i = f; i <= t; i++) m = Math.max(m, list.get(i));
        return m == Double.NEGATIVE_INFINITY ? 0 : m;
    }

    private double rsi(List<Double> close, int period) {
        if (close.size() < period + 1) return 50;
        double gain = 0, loss = 0;
        for (int i = close.size() - period; i < close.size(); i++) {
            double d = close.get(i) - close.get(i - 1);
            if (d >= 0) gain += d;
            else loss += -d;
        }
        if (gain == 0 && loss == 0) return 50;
        if (loss == 0) return 100;
        double rs = gain / loss;
        return 100 - 100 / (1 + rs);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static class ScoreResult {
        String stockCode;
        String exchangeType;
        String stockName;
        String asOfDate;
        int trendScore40;
        int volumeScore30;
        int oversoldScore30;
        double totalScore;
        double weightedScore;
        double ret20;
        double ret60;
        double avgAmount20;
        Map<String, Object> detail;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stock_code", stockCode);
            m.put("exchange_type", exchangeType);
            m.put("stock_name", stockName);
            m.put("as_of_date", asOfDate);
            m.put("trend_score_40", trendScore40);
            m.put("volume_score_30", volumeScore30);
            m.put("oversold_score_30", oversoldScore30);
            m.put("total_score", totalScore);
            m.put("final_score", totalScore);
            m.put("weighted_score", weightedScore);
            // 与 selector_result_item 列名对齐，便于前端表格共用
            m.put("trend_score", (double) trendScore40);
            m.put("volume_score", (double) volumeScore30);
            m.put("momentum_score", (double) oversoldScore30);
            m.put("volatility20", weightedScore);
            m.put("ret20", ret20);
            m.put("ret60", ret60);
            m.put("avg_amount20", avgAmount20);
            m.put("detail", detail != null ? detail : Map.of());
            return m;
        }
    }
}

