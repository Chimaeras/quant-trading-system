package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.StockInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockSelectorService {
    private final MarketDataRepository marketDataRepository;
    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final StockInfoRepository stockInfoRepository;

    @Transactional
    public Map<String, Object> runSelection(Map<String, Object> request) {
        LocalDate reqDate = parseDate(request.get("tradeDate"), LocalDate.now());
        LocalDate tradeDate = marketDataRepository.findLatestTradeDateOnOrBefore(reqDate).orElse(reqDate);
        int lookbackDays = parseInt(request.get("lookbackDays"), 60, 20, 120);
        int topN = parseInt(request.get("topN"), 10, 1, 50);
        double minAvgAmount = parseDouble(request.get("minAvgAmount"), 30000000d, 1000000d, 500000000d);
        double minTrendScore = parseDouble(request.get("minTrendScore"), 40d, 0d, 100d);

        LocalDate rangeStart = tradeDate.minusDays(Math.max(lookbackDays + 30L, 90L));
        List<Object[]> pairs = marketDataRepository.findDistinctStockPairsInRange(rangeStart, tradeDate);
        if (pairs.isEmpty()) {
            return Map.of("message", "无可用行情数据", "trade_date", tradeDate.toString(), "items", List.of());
        }

        Map<String, String> nameCache = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();

        for (Object[] pair : pairs) {
            if (pair == null || pair.length < 2) continue;
            String stockCode = String.valueOf(pair[0]);
            String exchangeType = String.valueOf(pair[1]);
            List<MarketData> bars = marketDataRepository
                    .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(stockCode, exchangeType, rangeStart, tradeDate);
            Candidate c = buildCandidate(stockCode, exchangeType, bars, minAvgAmount, minTrendScore, nameCache);
            if (c != null) candidates.add(c);
        }

        candidates.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));
        List<Candidate> selected = candidates.stream().limit(topN).toList();

        SelectorResult result = new SelectorResult();
        result.setResultId(genResultId());
        result.setTradeDate(tradeDate);
        result.setLookbackDays(lookbackDays);
        result.setTopN(topN);
        result.setMinAvgAmount(minAvgAmount);
        result.setMinTrendScore(minTrendScore);
        selectorResultRepository.save(result);

        selectorResultItemRepository.deleteByResultId(result.getResultId());
        int rank = 1;
        for (Candidate c : selected) {
            SelectorResultItem item = new SelectorResultItem();
            item.setResultId(result.getResultId());
            item.setRankNo(rank++);
            item.setStockCode(c.stockCode);
            item.setExchangeType(c.exchangeType);
            item.setStockName(c.stockName);
            item.setFinalScore(round2(c.finalScore));
            item.setTrendScore(round2(c.trendScore));
            item.setMomentumScore(round2(c.momentumScore));
            item.setVolumeScore(round2(c.volumeScore));
            item.setRiskPenalty(round2(c.riskPenalty));
            item.setRet20(round2(c.ret20));
            item.setRet60(round2(c.ret60));
            item.setVolatility20(round2(c.volatility20));
            item.setMaxDrawdown60(round2(c.maxDrawdown60));
            item.setAvgAmount20(round2(c.avgAmount20));
            item.setReasons(c.reasons);
            selectorResultItemRepository.save(item);
        }

        return getResultById(result.getResultId());
    }

    public Map<String, Object> getLatestResult(LocalDate tradeDate) {
        LocalDate target = tradeDate != null ? tradeDate : marketDataRepository.findMaxTradeDate().orElse(LocalDate.now());
        var r = selectorResultRepository.findFirstByTradeDateOrderByCreatedAtDesc(target).orElse(null);
        if (r == null) return Map.of("trade_date", target.toString(), "items", List.of(), "message", "当日暂无选股结果");
        return toResultMap(r);
    }

    public Map<String, Object> getResultById(String resultId) {
        var r = selectorResultRepository.findByResultId(resultId).orElse(null);
        if (r == null) return null;
        return toResultMap(r);
    }

    private Map<String, Object> toResultMap(SelectorResult r) {
        List<SelectorResultItem> items = selectorResultItemRepository.findByResultIdOrderByRankNoAsc(r.getResultId());
        List<Map<String, Object>> outItems = new ArrayList<>();
        for (SelectorResultItem i : items) {
            outItems.add(Map.of(
                    "rank_no", i.getRankNo(),
                    "stock_code", i.getStockCode(),
                    "exchange_type", i.getExchangeType(),
                    "stock_name", i.getStockName() != null ? i.getStockName() : "",
                    "final_score", i.getFinalScore(),
                    "trend_score", i.getTrendScore(),
                    "momentum_score", i.getMomentumScore(),
                    "volume_score", i.getVolumeScore(),
                    "risk_penalty", i.getRiskPenalty(),
                    "ret20", i.getRet20(),
                    "ret60", i.getRet60(),
                    "volatility20", i.getVolatility20(),
                    "max_drawdown60", i.getMaxDrawdown60(),
                    "avg_amount20", i.getAvgAmount20(),
                    "reasons", i.getReasons() != null ? i.getReasons() : Map.of()
            ));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result_id", r.getResultId());
        out.put("trade_date", r.getTradeDate().toString());
        out.put("lookback_days", r.getLookbackDays());
        out.put("top_n", r.getTopN());
        out.put("min_avg_amount", r.getMinAvgAmount());
        out.put("min_trend_score", r.getMinTrendScore());
        out.put("created_at", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        out.put("items", outItems);
        return out;
    }

    private Candidate buildCandidate(String stockCode, String exchangeType, List<MarketData> bars, double minAvgAmount,
                                     double minTrendScore, Map<String, String> nameCache) {
        if (bars == null || bars.size() < 70) return null;
        List<Double> close = new ArrayList<>();
        List<Double> amount = new ArrayList<>();
        for (MarketData b : bars) {
            if (b.getClosePrice() == null || b.getClosePrice().doubleValue() <= 0) continue;
            close.add(b.getClosePrice().doubleValue());
            amount.add(b.getAmount() != null ? b.getAmount().doubleValue() : 0d);
        }
        if (close.size() < 70) return null;

        double ret20 = pct(close.get(close.size() - 1), close.get(close.size() - 21));
        double ret60 = pct(close.get(close.size() - 1), close.get(close.size() - 61));
        double ma20 = avg(close, close.size() - 20, close.size() - 1);
        double ma60 = avg(close, close.size() - 60, close.size() - 1);
        double avgAmount20 = avg(amount, amount.size() - 20, amount.size() - 1);
        double vol20 = stdPct(close, 20) * 100;
        double mdd60 = maxDrawdown(close, 60);
        double rsi14 = rsi(close, 14);
        double macdHist = macdHistogram(close);
        double volumeRatio = amount.get(amount.size() - 1) / Math.max(1d, avgAmount20);

        double trend = 0;
        trend += ma20 > ma60 ? 30 : 0;
        trend += clamp(ret20, -10, 20) + 10;
        trend += clamp(ret60, -20, 30) * 0.5 + 10;
        trend = clamp(trend, 0, 100);

        double momentum = 0;
        momentum += clamp((rsi14 - 40) * 1.5, 0, 35);
        momentum += clamp(macdHist * 400, 0, 35);
        momentum += clamp(volumeRatio * 12, 0, 30);
        momentum = clamp(momentum, 0, 100);

        double volumeScore = clamp((avgAmount20 / Math.max(1, minAvgAmount)) * 60 + volumeRatio * 20, 0, 100);
        double riskPenalty = clamp(vol20 * 1.5 + mdd60 * 1.2, 0, 100);
        double finalScore = 0.4 * trend + 0.25 * momentum + 0.2 * volumeScore - 0.15 * riskPenalty;

        if (avgAmount20 < minAvgAmount) return null;
        if (trend < minTrendScore) return null;
        if (ma20 <= ma60) return null;
        if (ret20 < -5) return null;

        Candidate c = new Candidate();
        c.stockCode = stockCode;
        c.exchangeType = exchangeType;
        c.stockName = nameCache.computeIfAbsent(stockCode + ":" + exchangeType, k -> getStockName(stockCode, exchangeType));
        c.finalScore = finalScore;
        c.trendScore = trend;
        c.momentumScore = momentum;
        c.volumeScore = volumeScore;
        c.riskPenalty = riskPenalty;
        c.ret20 = ret20;
        c.ret60 = ret60;
        c.volatility20 = vol20;
        c.maxDrawdown60 = mdd60;
        c.avgAmount20 = avgAmount20;
        c.reasons = Map.of(
                "trend", ma20 > ma60 ? "MA20 在 MA60 上方" : "趋势偏弱",
                "ret20", round2(ret20) + "%",
                "ret60", round2(ret60) + "%",
                "rsi14", round2(rsi14),
                "volume_ratio", round2(volumeRatio)
        );
        return c;
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

    private String genResultId() {
        return "SC" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
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

    private double stdPct(List<Double> close, int period) {
        if (close.size() < period + 1) return 0;
        List<Double> rets = new ArrayList<>();
        for (int i = close.size() - period; i < close.size(); i++) {
            if (i <= 0) continue;
            double prev = close.get(i - 1);
            if (prev <= 0) continue;
            rets.add(close.get(i) / prev - 1);
        }
        if (rets.isEmpty()) return 0;
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = rets.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(var);
    }

    private double maxDrawdown(List<Double> close, int period) {
        if (close.size() < 2) return 0;
        int from = Math.max(0, close.size() - period);
        double peak = close.get(from);
        double mdd = 0;
        for (int i = from; i < close.size(); i++) {
            peak = Math.max(peak, close.get(i));
            if (peak <= 0) continue;
            double dd = (peak - close.get(i)) / peak * 100;
            mdd = Math.max(mdd, dd);
        }
        return mdd;
    }

    private double ema(List<Double> values, int period) {
        if (values.isEmpty()) return 0;
        double k = 2.0 / (period + 1);
        double ema = values.get(0);
        for (int i = 1; i < values.size(); i++) ema = values.get(i) * k + ema * (1 - k);
        return ema;
    }

    private double macdHistogram(List<Double> close) {
        int n = close.size();
        if (n < 35) return 0;
        List<Double> macdSeries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Double> sub = close.subList(0, i + 1);
            macdSeries.add(ema(sub, 12) - ema(sub, 26));
        }
        double dif = macdSeries.get(macdSeries.size() - 1);
        double dea = ema(macdSeries, 9);
        return dif - dea;
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

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static class Candidate {
        String stockCode;
        String exchangeType;
        String stockName;
        double finalScore;
        double trendScore;
        double momentumScore;
        double volumeScore;
        double riskPenalty;
        double ret20;
        double ret60;
        double volatility20;
        double maxDrawdown60;
        double avgAmount20;
        Map<String, Object> reasons;
    }
}
