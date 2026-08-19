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
            return insufficientDataResponse(stockCode, exchange, latest);
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

        Map<String, String> signals = buildSignals(lastClose, ma20, ma60, macdHist, rsi14, volumeRatio);
        List<String> tags = buildTags(lastClose, ma20, ma60, macdHist, rsi14, volumeRatio);
        Map<String, Object> plan = buildPlan(action, targetPosition, lastClose, support20, resistance20, stopLoss, takeProfit);
        String summary = buildSummary(stockCode, action, confidence, lastClose, ma20, ma60, rsi14, macdHist, volatility20, volumeRatio);
        List<String> riskPoints = buildRiskPoints(lastClose, ma20, rsi14, volatility20, macdHist, avgCost, pnlPct);
        List<String> opportunityPoints = buildOpportunityPoints(lastClose, ma20, ma60, macdHist, rsi14, support20, resistance20);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stock_code", stockCode);
        out.put("exchange", exchange);
        out.put("as_of_date", latest.toString());
        out.put("action", action);
        out.put("target_position", targetPosition);
        out.put("confidence", confidence);
        out.put("data_insufficient", false);
        out.put("score_breakdown", Map.of(
                "trend", (int) Math.round(trendScore),
                "momentum", (int) Math.round(momentumScore),
                "riskPenalty", (int) Math.round(riskPenalty),
                "total", confidence
        ));
        out.put("signals", signals);
        out.put("tags", tags);
        out.put("plan", plan);
        out.put("summary", summary);
        out.put("risk_points", riskPoints);
        out.put("opportunity_points", opportunityPoints);
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

    private Map<String, Object> insufficientDataResponse(String stockCode, String exchange, LocalDate latest) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stock_code", stockCode);
        out.put("exchange", exchange);
        out.put("as_of_date", latest.toString());
        out.put("action", "HOLD");
        out.put("target_position", "--");
        out.put("confidence", 0);
        out.put("message", "历史数据不足，无法分析");
        out.put("data_insufficient", true);
        out.put("summary", "当前可用K线数据不足70个交易日，无法完成技术面综合评分与结构化建议。请先补充或同步历史行情后再试。");
        out.put("risk_points", List.of("数据样本不足，无法可靠评估波动率、止损位与回撤风险"));
        out.put("opportunity_points", List.of("历史数据补齐后，可重新生成趋势、动能与量价信号"));
        out.put("tags", List.of("数据不足"));
        out.put("signals", Map.of("ma", "neutral", "macd", "neutral", "rsi", "neutral", "volume", "normal"));
        out.put("score_breakdown", Map.of("trend", 0, "momentum", 0, "riskPenalty", 0, "total", 0));
        out.put("plan", Map.of(
                "entry", "--",
                "add", "--",
                "reduce", "--",
                "stopLoss", 0.0,
                "takeProfit", 0.0,
                "support", 0.0,
                "resistance", 0.0,
                "targetPosition", "--"
        ));
        out.put("risk_lines", Map.of(
                "support", 0.0,
                "resistance", 0.0,
                "stop_loss", 0.0,
                "take_profit", 0.0
        ));
        out.put("indicator_snapshot", Map.of());
        out.put("reasons", List.of("历史数据不足70条"));
        return out;
    }

    private Map<String, String> buildSignals(double lastClose, double ma20, double ma60, double macdHist, double rsi14, double volumeRatio) {
        Map<String, String> m = new LinkedHashMap<>();
        if (lastClose > ma20 && ma20 > ma60) m.put("ma", "bullish");
        else if (lastClose < ma20 && ma20 < ma60) m.put("ma", "bearish");
        else m.put("ma", "neutral");

        if (macdHist > 0.0001) m.put("macd", "bullish");
        else if (macdHist < -0.0001) m.put("macd", "bearish");
        else m.put("macd", "neutral");

        if (rsi14 >= 70) m.put("rsi", "overbought");
        else if (rsi14 <= 30) m.put("rsi", "oversold");
        else m.put("rsi", "neutral");

        if (volumeRatio >= 1.2) m.put("volume", "strong");
        else if (volumeRatio <= 0.8) m.put("volume", "weak");
        else m.put("volume", "normal");
        return m;
    }

    private List<String> buildTags(double lastClose, double ma20, double ma60, double macdHist, double rsi14, double volumeRatio) {
        List<String> t = new ArrayList<>();
        if (lastClose > ma20 && ma20 > ma60) t.add("MA20>MA60");
        else if (ma20 < ma60) t.add("MA20<MA60");
        if (macdHist > 0) t.add("MACD多头");
        else if (macdHist < 0) t.add("MACD空头");
        if (rsi14 >= 70) t.add("RSI偏热");
        else if (rsi14 <= 30) t.add("RSI偏冷");
        else t.add("RSI中性");
        if (volumeRatio >= 1.2) t.add("放量");
        else if (volumeRatio <= 0.8) t.add("缩量");
        else t.add("量能正常");
        return t;
    }

    private Map<String, Object> buildPlan(String action, String targetPosition, double lastClose,
                                          double support20, double resistance20, double stopLoss, double takeProfit) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("entry", "在 " + round2(support20) + "～" + round2(lastClose) + " 区间关注承接与回踩，结合量能确认");
        p.put("add", "ADD".equals(action)
                ? "放量突破 " + round2(resistance20) + " 且MACD柱走强时可考虑分批加仓"
                : "暂不加仓，等待趋势与动能转强");
        p.put("reduce", "REDUCE".equals(action)
                ? "跌破 " + round2(stopLoss) + " 或MA20持续走弱时减仓/止损"
                : "持仓者可设好止损，避免单点重仓");
        p.put("stopLoss", round2(stopLoss));
        p.put("takeProfit", round2(takeProfit));
        p.put("support", round2(support20));
        p.put("resistance", round2(resistance20));
        p.put("targetPosition", targetPosition);
        return p;
    }

    private String buildSummary(String code, String action, int confidence, double lastClose,
                                double ma20, double ma60, double rsi14, double macdHist,
                                double volatility20, double volumeRatio) {
        String actZh = switch (action) {
            case "ADD" -> "偏多/加仓倾向";
            case "REDUCE" -> "偏空/减仓倾向";
            default -> "观望/震荡整理";
        };
        String trend = lastClose > ma20 ? "短期位于MA20上方" : "短期位于MA20下方";
        String mid = ma20 > ma60 ? "中期均线呈多头排列" : "中期均线呈空头或纠缠";
        return String.format(
                "【%s】综合评分约 %d：%s。%s，%s；RSI14=%s，MACD柱=%s；20日波动约 %s%%，量比约 %s。以上为规则模板生成的技术面摘要，不构成投资建议。",
                code, confidence, actZh, trend, mid, round2(rsi14), round4(macdHist), round2(volatility20), round2(volumeRatio)
        );
    }

    private List<String> buildRiskPoints(double lastClose, double ma20, double rsi14, double volatility20, double macdHist,
                                         double avgCost, double pnlPct) {
        List<String> r = new ArrayList<>();
        if (volatility20 > 4) r.add("近期波动偏高（20日波动约 " + round2(volatility20) + "%），注意回撤与止损纪律");
        if (rsi14 >= 70) r.add("RSI处于偏热区，短线存在冲高回落风险");
        if (lastClose < ma20 * 0.98) r.add("价格明显弱于MA20，短期趋势转弱风险");
        if (macdHist < 0) r.add("MACD柱为负，动能偏弱");
        if (avgCost > 0 && pnlPct < -8) r.add("相对成本浮亏较大，注意仓位与止损");
        if (r.isEmpty()) r.add("未触发显著风险标签：仍建议关注大盘与个股消息面变化");
        return r;
    }

    private List<String> buildOpportunityPoints(double lastClose, double ma20, double ma60, double macdHist,
                                                double rsi14, double support20, double resistance20) {
        List<String> o = new ArrayList<>();
        if (lastClose > ma20 && ma20 > ma60) o.add("均线结构偏多，若量能配合可关注趋势延续机会");
        if (macdHist > 0) o.add("MACD柱为正，短线动能相对有利");
        if (rsi14 <= 45 && rsi14 >= 30) o.add("RSI未过热，存在回踩后再上攻的空间（需结合量能）");
        o.add("关键支撑参考 " + round2(support20) + "，压力参考 " + round2(resistance20));
        if (o.size() < 2) o.add("若突破压力并放量，可再评估加仓条件");
        return o;
    }

    private String normalizeExchange(String exchange, String code) {
        String ex = exchange == null ? "" : exchange.toUpperCase();
        if (ex.endsWith(".SH") || "SSE".equals(ex) || "SH".equals(ex)) return "SSE";
        if (ex.endsWith(".BJ") || "BSE".equals(ex) || "BJ".equals(ex)) return "BSE";
        if (ex.endsWith(".SZ") || "SZ".equals(ex) || "SZSE".equals(ex)) return "SZSE";
        if (code.startsWith("4") || code.startsWith("8") || code.startsWith("92")) return "BSE";
        if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) return "SSE";
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
