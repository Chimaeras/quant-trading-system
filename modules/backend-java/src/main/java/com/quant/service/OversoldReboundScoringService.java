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
import com.quant.service.scoring.ScoringConstants;
import com.quant.service.scoring.ScoringRules;
import com.quant.service.scoring.ScoringUtils;
import com.quant.service.scoring.TechnicalIndicators;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 超跌反弹打分服务
 * 重构版本：使用统一的工具类 TechnicalIndicators、ScoringRules、ScoringUtils
 */
@Service
@RequiredArgsConstructor
public class OversoldReboundScoringService {

    public static final String FACTOR_VERSION = ScoringConstants.VERSION_OVERSOLD;

    private final MarketDataRepository marketDataRepository;
    private final StockInfoRepository stockInfoRepository;
    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final SelectorTemplateRepository selectorTemplateRepository;

    /**
     * 计算超跌反弹打分
     */
    @Transactional
    public Map<String, Object> run(Map<String, Object> request) {
        Map<String, Object> req = mergeTemplate(request != null ? request : Map.of());

        LocalDate reqDate = ScoringUtils.parseDate(req.get("tradeDate"), LocalDate.now());
        LocalDate tradeDate = marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate);

        int topN = ScoringUtils.parseInt(req.get("topN"), 50, 1, 300);
        double minTotalScore = ScoringUtils.parseDouble(req.get("minTotalScore"), 60, 0, 100);
        boolean excludeST = ScoringUtils.parseBool(req.get("excludeST"), true);
        boolean persist = ScoringUtils.parseBool(req.get("persist"), true);
        boolean progressivePersist = ScoringUtils.parseBool(req.get("progressivePersist"), false);
        String fixedResultId = ScoringUtils.parseString(req.get("resultId"), "");
        boolean resumeMode = ScoringUtils.parseBool(req.get("resumeMode"), false);

        LocalDate rangeStart = tradeDate.minusDays(520);
        List<Object[]> pairs = marketDataRepository.findDistinctStockPairsInRange(rangeStart, tradeDate);
        String targetStockCode = ScoringUtils.parseString(req.get("stockCode"), "");
        String targetExchangeType = ScoringUtils.parseString(req.get("exchangeType"), "");
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
        int scored = 0;
        String progressiveResultId = null;
        int progressiveRank = 1;
        Set<String> existingStockKeys = new HashSet<>();
        if (persist && progressivePersist) {
            progressiveResultId = ensureResultSkeleton(fixedResultId, tradeDate, req, topN, resumeMode);
            if (resumeMode && ScoringUtils.isNotEmpty(progressiveResultId)) {
                existingStockKeys = selectorResultItemRepository.findStockKeysByResultId(progressiveResultId);
                progressiveRank = existingStockKeys.size() + 1;
            }
        }

        for (Object[] pair : pairs) {
            ScoringUtils.checkInterrupted();
            if (pair == null || pair.length < 2) continue;
            String stockCode = String.valueOf(pair[0]);
            String exchangeType = String.valueOf(pair[1]);
            scanned++;

            List<MarketData> bars = marketDataRepository
                    .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(stockCode, exchangeType, rangeStart, tradeDate);
            ScoreResult r = scoreOne(stockCode, exchangeType, tradeDate, bars, nameCache, excludeST);
            if (r == null) continue;
            scored++;
            if (resumeMode && existingStockKeys.contains(r.stockCode + "|" + r.exchangeType)) continue;

            if (progressiveResultId != null) {
                selectorResultItemRepository.save(toItem(progressiveResultId, progressiveRank++, r));
            }
            if (r.totalScore >= minTotalScore && r.trendScore40 > 25 && r.volumeScore30 > 15 && r.oversoldScore30 > 15) {
                passed.add(r);
            }
        }

        passed.sort((a, b) -> Double.compare(b.totalScore, a.totalScore));
        List<ScoreResult> top = passed.stream().limit(topN).toList();

        String resultId = null;
        if (persist) {
            resultId = persistResult(tradeDate, req, top, progressiveResultId, resumeMode, progressivePersist, scanned, scored);
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
        String templateId = ScoringUtils.parseString(request.get("templateId"), "");
        if (templateId.isEmpty()) return base;

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
            List<ScoreResult> top,
            String fixedResultId,
            boolean resumeMode,
            boolean progressivePersist,
            int scanned,
            int scored
    ) {
        String resultId = ensureResultSkeleton(fixedResultId, tradeDate, req, ScoringUtils.parseInt(req.get("topN"), 50, 1, 300), resumeMode);
        double minTotalScore = ScoringUtils.parseDouble(req.get("minTotalScore"), 60, 0, 100);
        boolean excludeST = ScoringUtils.parseBool(req.get("excludeST"), true);
        int topN = ScoringUtils.parseInt(req.get("topN"), 50, 1, 300);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("factor", FACTOR_VERSION);
        snapshot.put("minTotalScore", minTotalScore);
        snapshot.put("minTrendScore40", 25);
        snapshot.put("minVolumeScore30", 15);
        snapshot.put("minOversoldScore30", 15);
        snapshot.put("excludeST", excludeST);
        snapshot.put("weights", Map.of("trend", 0.4, "volume", 0.3, "oversold", 0.3));

        SelectorResult result = selectorResultRepository.findByResultId(resultId).orElse(new SelectorResult());
        result.setResultId(resultId);
        result.setTradeDate(tradeDate);
        result.setLookbackDays(260);
        result.setTopN(topN);
        result.setMinAvgAmount(0.0);
        result.setMinTrendScore(25.0);
        result.setTemplateId(ScoringUtils.parseString(req.get("templateId"), "").isEmpty() ? null : ScoringUtils.parseString(req.get("templateId"), ""));
        result.setFactorVersion(FACTOR_VERSION);
        result.setStrategyType("OVERSOLD_REBOUND");
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
            for (ScoreResult r : top) {
                SelectorResultItem item = toItem(resultId, rank++, r);
                selectorResultItemRepository.save(item);
            }
        }
        return resultId;
    }

    private String ensureResultSkeleton(String fixedResultId, LocalDate tradeDate, Map<String, Object> req, int topN, boolean resumeMode) {
        String resultId = ScoringUtils.isNotEmpty(fixedResultId) ? fixedResultId : genResultId();
        SelectorResult result = selectorResultRepository.findByResultId(resultId).orElse(new SelectorResult());
        result.setResultId(resultId);
        result.setTradeDate(tradeDate);
        result.setLookbackDays(260);
        result.setTopN(topN);
        result.setMinAvgAmount(0.0);
        result.setMinTrendScore(25.0);
        result.setTemplateId(ScoringUtils.parseString(req.get("templateId"), "").isEmpty() ? null : ScoringUtils.parseString(req.get("templateId"), ""));
        result.setFactorVersion(FACTOR_VERSION);
        result.setStrategyType("OVERSOLD_REBOUND");
        result.setRunStatus("RUNNING");
        result.setTotalCandidates(0);
        result.setProcessedCandidates(0);
        result.setSuccessCandidates(0);
        result.setFailedCandidates(0);
        result.setLastProcessedKey("");
        result.setFactorSnapshot(Map.of("factor", FACTOR_VERSION, "progressive", true));
        selectorResultRepository.save(result);
        if (ScoringUtils.isNotEmpty(fixedResultId) && !resumeMode) {
            selectorResultItemRepository.deleteByResultId(resultId);
        }
        return resultId;
    }

    private SelectorResultItem toItem(String resultId, int rankNo, ScoreResult r) {
        SelectorResultItem item = new SelectorResultItem();
        item.setResultId(resultId);
        item.setRankNo(rankNo);
        item.setStrategyType("OVERSOLD_REBOUND");
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
        return item;
    }

    private String genResultId() {
        return "OR" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private ScoreResult scoreOne(String stockCode, String exchangeType, LocalDate tradeDate, List<MarketData> bars,
                                 Map<String, String> nameCache, boolean excludeST) {
        if (bars == null || bars.size() < ScoringConstants.MIN_BARS_OVERSOLD) return null;

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
        if (close.size() < ScoringConstants.MIN_BARS_OVERSOLD) return null;

        String stockName = nameCache.computeIfAbsent(stockCode + ":" + exchangeType, k -> getStockName(stockCode, exchangeType));
        if (excludeST && stockName != null && stockName.toUpperCase().contains("ST")) return null;

        int n = close.size();
        double lastClose = close.get(n - 1);

        // 收益率计算
        double ret60 = (n >= 61) ? ScoringUtils.pct(lastClose, close.get(n - 61)) : 0;
        double ret120 = (n >= 121) ? ScoringUtils.pct(lastClose, close.get(n - 121)) : 0;
        double ret20 = (n >= 21) ? ScoringUtils.pct(lastClose, close.get(n - 21)) : 0;
        double avgAmount20 = TechnicalIndicators.avg(amount, n - 20, n - 1);

        // 均线计算 - 使用统一工具类
        double[] closeArr = close.stream().mapToDouble(Double::doubleValue).toArray();
        double ma20 = TechnicalIndicators.smaAt(closeArr, n - 1, 20);
        double ma60 = TechnicalIndicators.smaAt(closeArr, n - 1, 60);
        double ma120 = TechnicalIndicators.smaAt(closeArr, n - 1, 120);

        // 周K连续阴线
        int consecutiveBearWeeks = consecutiveBearWeeks(bars);

        // 成交量比率
        double v5 = TechnicalIndicators.avg(volume, n - 5, n - 1);
        double v30 = TechnicalIndicators.avg(volume, n - 30, n - 1);
        double vRatio = v30 <= 0 ? 0 : v5 / v30;

        // 量价配合
        VolumePriceType vp = volumePriceType(close, volume);

        // RSI14 - 使用统一工具类
        double rsi14 = TechnicalIndicators.rsiAt(closeArr, n - 1, ScoringConstants.RSI_PERIOD);

        // 布林带
        double std20 = TechnicalIndicators.stdDev(closeArr, n - 20, n - 1);
        double bollMid = ma20;
        double bollLower = bollMid - 2 * std20;
        double bollLowerExtreme = bollMid - 3.5 * std20;

        // 1年分位
        int from252 = Math.max(0, n - 252);
        double min252 = TechnicalIndicators.min(close, from252, n - 1);
        double max252 = TechnicalIndicators.max(close, from252, n - 1);
        double percentile = (max252 - min252) <= 0 ? 0.5 : (lastClose - min252) / (max252 - min252);

        // --- 趋势判断（40分）---
        int trendScore = 0;
        trendScore += ScoringRules.scoreRet60(ret60);
        trendScore += ScoringRules.scoreRet120(ret120);
        // 均线系统
        if (lastClose < ma20) trendScore += 3;
        if (lastClose < ma60) trendScore += 4;
        if (lastClose < ma120) trendScore += 3;
        // 周K形态
        trendScore += ScoringRules.scoreBearWeeks(consecutiveBearWeeks);

        // --- 成交量验证（30分）---
        int volumeScore = 0;
        if (vRatio > 0.8) volumeScore += 15;
        else if (vRatio >= 0.5) volumeScore += 10;
        volumeScore += switch (vp) {
            case DOWN_VOL_EXPAND -> 10;
            case DOWN_VOL_SHRINK_THEN_WARM_EXPAND -> 5;
            default -> 0;
        };

        // --- 超跌程度（30分）---
        int oversoldScore = 0;
        oversoldScore += ScoringRules.scoreRsiOversold(rsi14);
        oversoldScore += ScoringRules.scoreBollingerOversold(lastClose, bollLower, bollLowerExtreme);
        oversoldScore += ScoringRules.scorePercentile(percentile);

        double totalScore = trendScore + volumeScore + oversoldScore;
        double weightedScore = (trendScore / 40.0) * 100 * 0.4
                + (volumeScore / 30.0) * 100 * 0.3
                + (oversoldScore / 30.0) * 100 * 0.3;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ret20_pct", ScoringUtils.round2(ret20));
        detail.put("ret60_pct", ScoringUtils.round2(ret60));
        detail.put("ret120_pct", ScoringUtils.round2(ret120));
        detail.put("avg_amount20", ScoringUtils.round2(avgAmount20));
        detail.put("ma20", ScoringUtils.round2(ma20));
        detail.put("ma60", ScoringUtils.round2(ma60));
        detail.put("ma120", ScoringUtils.round2(ma120));
        detail.put("consecutive_bear_weeks", consecutiveBearWeeks);
        detail.put("vol_ratio_5_30", ScoringUtils.round2(vRatio));
        detail.put("volume_price_type", vp.name());
        detail.put("rsi14", ScoringUtils.round2(rsi14));
        detail.put("boll_mid", ScoringUtils.round2(bollMid));
        detail.put("boll_lower", ScoringUtils.round2(bollLower));
        detail.put("boll_lower_extreme", ScoringUtils.round2(bollLowerExtreme));
        detail.put("percentile_1y", ScoringUtils.round4(percentile));

        ScoreResult r = new ScoreResult();
        r.stockCode = stockCode;
        r.exchangeType = exchangeType;
        r.stockName = stockName != null ? stockName : stockCode;
        r.asOfDate = tradeDate.toString();
        r.trendScore40 = trendScore;
        r.volumeScore30 = volumeScore;
        r.oversoldScore30 = oversoldScore;
        r.totalScore = ScoringUtils.round2(totalScore);
        r.weightedScore = ScoringUtils.round2(weightedScore);
        r.ret20 = ScoringUtils.round2(ret20);
        r.ret60 = ScoringUtils.round2(ret60);
        r.avgAmount20 = ScoringUtils.round2(avgAmount20);
        r.detail = detail;
        return r;
    }

    private int consecutiveBearWeeks(List<MarketData> bars) {
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
                LocalDate first = prev.firstDate().isAfter(d) ? d : prev.firstDate();
                double openV = prev.firstDate().isAfter(d) ? o : prev.open();
                LocalDate last = prev.lastDate().isBefore(d) ? d : prev.lastDate();
                double closeV = prev.lastDate().isBefore(d) ? c : prev.close();
                map.put(key, new W(first, openV, last, closeV));
            }
        }

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

        double vFirstHalf = TechnicalIndicators.avg(volume, from, from + 14);
        double vSecondHalf = TechnicalIndicators.avg(volume, from + 15, n - 1);

        double vLast5 = TechnicalIndicators.avg(volume, n - 5, n - 1);
        double vPrev5 = TechnicalIndicators.avg(volume, n - 10, n - 6);

        if (down) {
            if (vSecondHalf > vFirstHalf * 1.05) return VolumePriceType.DOWN_VOL_EXPAND;
            boolean shrink = vSecondHalf < vFirstHalf * 0.95;
            boolean warmExpand = vLast5 > vPrev5 * 1.05;
            if (shrink && warmExpand) return VolumePriceType.DOWN_VOL_SHRINK_THEN_WARM_EXPAND;
            if (shrink) return VolumePriceType.DOWN_VOL_SHRINK;
            return VolumePriceType.DOWN_VOL_SHRINK_THEN_WARM_EXPAND;
        }

        return VolumePriceType.DOWN_VOL_SHRINK;
    }

    private String getStockName(String stockCode, String exchangeType) {
        Optional<StockInfo> exact = stockInfoRepository.findByStockCodeAndExchangeType(stockCode, exchangeType);
        if (exact.isPresent()) return exact.get().getStockName();
        return stockInfoRepository.findFirstByStockCode(stockCode).map(StockInfo::getStockName).orElse(stockCode);
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
