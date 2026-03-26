package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.repository.MarketDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class StockAnalysisService {
    private final MarketDataRepository marketDataRepository;

    public Map<String, Object> analyze(Map<String, Object> request) {
        String rawCode = String.valueOf(request.getOrDefault("stockCode", request.getOrDefault("symbol", ""))).trim();
        if (rawCode.isBlank()) return Map.of("message", "stockCode 不能为空");
        String stockCode = rawCode.split("\\.")[0];
        String exchange = normalizeExchange(String.valueOf(request.getOrDefault("exchange", "")), stockCode);
        LocalDate asOf = parseDate(request.get("asOfDate"), LocalDate.now());
        int holdingQty = parseInt(request.get("holdingQty"), 0);
        double avgCost = parseDouble(request.get("avgCost"), 0);
        String riskProfile = String.valueOf(request.getOrDefault("riskProfile", "balanced")).toLowerCase();

        LocalDate latest = marketDataRepository.findLatestTradeDateOnOrBefore(asOf).orElse(asOf);
        LocalDate start = latest.minusDays(220);
        List<MarketData> bars = marketDataRepository.findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
                stockCode, exchange, start, latest);
        if (bars.size() < 70) {
            return Map.of(
                    "stock_code", stockCode,
                    "exchange", exchange,
                    "as_of_date", latest.toString(),
                    "action", "HOLD",
                    "confidence", 0,
                    "message", "历史数据不足，无法分析"
            );
        }

        List<Double> close = new ArrayList<>();
        List<Double> high = new ArrayList<>();
        List<Double> low = new ArrayList<>();
        List<Double> volume = new ArrayList<>();
        for (MarketData b : bars) {
            if (b.getClosePrice() == null) continue;
            close.add(b.getClosePrice().doubleValue());
            high.add(b.getHighPrice() != null ? b.getHighPrice().doubleValue() : b.getClosePrice().doubleValue());
            low.add(b.getLowPrice() != null ? b.getLowPrice().doubleValue() : b.getClosePrice().doubleValue());
            volume.add(b.getVolume() != null ? b.getVolume().doubleValue() : 0d);
        }

        double lastClose = close.get(close.size() - 1);
        double ma20 = avg(close, close.size() - 20, close.size() - 1);
        double ma60 = avg(close, close.size() - 60, close.size() - 1);
        double rsi14 = rsi(close, 14);
        double macdHist = macdHistogram(close);
        double atr14 = atr(high, low, close, 14);
        double volatility20 = stdPct(close, 20) * 100;
        double support20 = low.subList(Math.max(0, low.size() - 20), low.size()).stream().mapToDouble(Double::doubleValue).min().orElse(lastClose);
        double resistance20 = high.subList(Math.max(0, high.size() - 20), high.size()).stream().mapToDouble(Double::doubleValue).max().orElse(lastClose);
        double volumeRatio = volume.get(volume.size() - 1) / Math.max(1, avg(volume, volume.size() - 20, volume.size() - 1));

        double trendScore = clamp((lastClose > ma20 ? 30 : 10) + (ma20 > ma60 ? 30 : 10) + clamp((lastClose / ma20 - 1) * 200, -10, 20), 0, 100);
        double momentumScore = clamp(clamp((rsi14 - 45) * 1.8, 0, 40) + clamp(macdHist * 500, 0, 35) + clamp(volumeRatio * 12, 0, 25), 0, 100);
        double riskPenalty = clamp(volatility20 * 1.2 + (atr14 / Math.max(1, lastClose)) * 100 * 1.6, 0, 100);
        double score = 0.45 * trendScore + 0.35 * momentumScore - 0.2 * riskPenalty;
        int confidence = (int) Math.round(clamp(score, 0, 100));

        String action = "HOLD";
        if (score >= 60 && lastClose > ma20 && ma20 > ma60 && macdHist > 0) action = "ADD";
        if (score < 35 || lastClose < ma20 * 0.98 || rsi14 < 35) action = "REDUCE";

        String targetPosition = switch (action) {
            case "ADD" -> "60%-80%";
            case "REDUCE" -> "10%-30%";
            default -> "30%-60%";
        };
        if ("conservative".equals(riskProfile) && "ADD".equals(action)) targetPosition = "40%-60%";
        if ("aggressive".equals(riskProfile) && "ADD".equals(action)) targetPosition = "70%-90%";

        double stopLoss = Math.max(support20 * 0.98, lastClose - 1.5 * atr14);
        double takeProfit = Math.max(resistance20 * 1.02, lastClose + 2.2 * atr14);
        double pnlPct = avgCost > 0 ? (lastClose / avgCost - 1) * 100 : 0;

        List<String> reasons = new ArrayList<>();
        reasons.add(lastClose > ma20 ? "现价在MA20上方，短期趋势偏强" : "现价跌破MA20，短期趋势转弱");
        reasons.add(ma20 > ma60 ? "MA20位于MA60上方，中期结构偏多" : "MA20低于MA60，中期结构偏弱");
        reasons.add("RSI14=" + round2(rsi14) + "，MACD柱=" + round4(macdHist));
        reasons.add("20日波动=" + round2(volatility20) + "%，量比=" + round2(volumeRatio));
        if (holdingQty > 0) reasons.add("当前持仓 " + holdingQty + " 股，成本 " + round2(avgCost) + "，浮动收益 " + round2(pnlPct) + "%");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stock_code", stockCode);
        out.put("exchange", exchange);
        out.put("as_of_date", latest.toString());
        out.put("action", action);
        out.put("target_position", targetPosition);
        out.put("confidence", confidence);
        out.put("risk_lines", Map.of(
                "support", round2(support20),
                "resistance", round2(resistance20),
                "stop_loss", round2(stopLoss),
                "take_profit", round2(takeProfit)
        ));
        out.put("indicator_snapshot", Map.of(
                "close", round2(lastClose),
                "ma20", round2(ma20),
                "ma60", round2(ma60),
                "rsi14", round2(rsi14),
                "macd_hist", round4(macdHist),
                "atr14", round4(atr14),
                "volatility20", round2(volatility20),
                "volume_ratio", round2(volumeRatio)
        ));
        out.put("reasons", reasons);
        return out;
    }

    private String normalizeExchange(String exchange, String code) {
        String ex = exchange == null ? "" : exchange.toUpperCase();
        if (ex.endsWith(".SH") || "SSE".equals(ex) || "SH".equals(ex)) return "SSE";
        if (ex.endsWith(".BJ") || "BSE".equals(ex) || "BJ".equals(ex)) return "BSE";
        if (ex.endsWith(".SZ") || "SZ".equals(ex) || "SZSE".equals(ex)) return "SZSE";
        if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) return "SSE";
        if (code.startsWith("8") || code.startsWith("4")) return "BSE";
        return "SZSE";
    }

    private LocalDate parseDate(Object v, LocalDate def) {
        if (v == null) return def;
        try {
            return LocalDate.parse(String.valueOf(v));
        } catch (Exception ignore) {
            return def;
        }
    }

    private int parseInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignore) {
            return def;
        }
    }

    private double parseDouble(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ignore) {
            return def;
        }
    }

    private double avg(List<Double> list, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(list.size() - 1, to);
        if (f > t) return 0;
        double s = 0;
        int n = 0;
        for (int i = f; i <= t; i++) {
            s += list.get(i);
            n++;
        }
        return n > 0 ? s / n : 0;
    }

    private double stdPct(List<Double> close, int period) {
        if (close.size() < period + 1) return 0;
        List<Double> ret = new ArrayList<>();
        for (int i = close.size() - period; i < close.size(); i++) {
            if (i <= 0 || close.get(i - 1) <= 0) continue;
            ret.add(close.get(i) / close.get(i - 1) - 1);
        }
        if (ret.isEmpty()) return 0;
        double mean = ret.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = ret.stream().mapToDouble(x -> (x - mean) * (x - mean)).average().orElse(0);
        return Math.sqrt(var);
    }

    private double ema(List<Double> values, int period) {
        if (values.isEmpty()) return 0;
        double k = 2.0 / (period + 1);
        double e = values.get(0);
        for (int i = 1; i < values.size(); i++) e = values.get(i) * k + e * (1 - k);
        return e;
    }

    private double macdHistogram(List<Double> close) {
        if (close.size() < 35) return 0;
        List<Double> macdLine = new ArrayList<>();
        for (int i = 0; i < close.size(); i++) {
            List<Double> sub = close.subList(0, i + 1);
            macdLine.add(ema(sub, 12) - ema(sub, 26));
        }
        double dif = macdLine.get(macdLine.size() - 1);
        double dea = ema(macdLine, 9);
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
        if (loss == 0) return 100;
        double rs = gain / loss;
        return 100 - 100 / (1 + rs);
    }

    private double atr(List<Double> high, List<Double> low, List<Double> close, int period) {
        if (close.size() < period + 1) return 0;
        List<Double> tr = new ArrayList<>();
        for (int i = close.size() - period; i < close.size(); i++) {
            double h = high.get(i), l = low.get(i), pc = close.get(i - 1);
            tr.add(Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc))));
        }
        return tr.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
