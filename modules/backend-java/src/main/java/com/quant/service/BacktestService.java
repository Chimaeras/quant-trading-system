package com.quant.service;

import com.quant.config.BacktestCostProperties;
import com.quant.entity.BacktestResultEntity;
import com.quant.entity.MarketData;
import com.quant.repository.BacktestResultRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final UserRepository userRepository;
    private final MarketDataRepository marketDataRepo;
    private final BacktestResultRepository backtestResultRepository;
    private final BacktestCostProperties costProperties;

    public Map<String, Object> runBacktest(String username, Map<String, Object> request) {
        long startedAt = System.currentTimeMillis();
        if (username != null && !username.isBlank()) {
            userRepository.findByUsername(username).orElse(null);
        }

        Object strategyKey = request.getOrDefault("strategyName", request.getOrDefault("strategyId", request.getOrDefault("strategy_id", "MA_Cross")));
        String strategyName = resolveStrategyName(strategyKey);

        String symbol = (String) request.getOrDefault("stock_code", request.getOrDefault("symbol", "000001"));
        String startStr = (String) request.getOrDefault("startDate", request.getOrDefault("start_date", "2023-01-01"));
        String endStr = (String) request.getOrDefault("endDate", request.getOrDefault("end_date", "2024-12-31"));
        Number initialCap = (Number) request.getOrDefault("initialCash", request.getOrDefault("initialCapital", 1000000));
        double initialCash = initialCap != null ? initialCap.doubleValue() : 1000000;
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Collections.emptyMap());
        CostModel costModel = resolveCostModel(params);

        String[] parsed = parseStockCode(symbol);
        String stockCode = parsed[0];
        String exchangeType = parsed[1];

        LocalDate startDate = LocalDate.parse(startStr);
        LocalDate endDate = LocalDate.parse(endStr);

        List<MarketData> klines = marketDataRepo.findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(
                stockCode, exchangeType, startDate, endDate);

        if (klines.isEmpty()) {
            log.warn("无行情数据: {} {}, {}-{}", stockCode, exchangeType, startStr, endStr);
            return buildEmptyResult(initialCash);
        }

        List<Bar> bars = toBars(klines);
        BacktestResult result = runStrategy(strategyName, bars, initialCash, params, costModel);
        int runTimeSeconds = (int) ((System.currentTimeMillis() - startedAt) / 1000);
        String resultId = saveBacktestResult(strategyName, strategyKey, startDate, endDate, initialCash, params, result, runTimeSeconds);

        Map<String, Object> out = new HashMap<>();
        out.put("totalReturn", String.format("%.2f", result.totalReturnPct) + "%");
        out.put("total_return", Math.round(result.totalReturnPct * 100) / 100.0);
        out.put("annual_return", Math.round(result.annualReturnPct * 100) / 100.0);
        out.put("sharpeRatio", Math.round(result.sharpeRatio * 100) / 100.0);
        out.put("sharpe_ratio", Math.round(result.sharpeRatio * 100) / 100.0);
        out.put("maxDrawdown", String.format("%.2f", result.maxDrawdownPct) + "%");
        out.put("max_drawdown", Math.round(result.maxDrawdownPct * 100) / 100.0);
        out.put("totalTrades", result.totalTrades);
        out.put("total_trades", result.totalTrades);
        out.put("winRate", Math.round(result.winRate * 100) / 100.0);
        out.put("win_rate", Math.round(result.winRate * 100) / 100.0);
        out.put("total_cost", Math.round(result.totalCost * 100.0) / 100.0);
        out.put("finalEquity", Math.round(result.finalEquity));
        out.put("equity_curve", result.equityCurve);
        out.put("result_id", resultId);
        return out;
    }

    public Map<String, Object> listResults(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        var pageable = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        var pageData = backtestResultRepository.findAll(pageable);
        List<Map<String, Object>> items = pageData.getContent().stream().map(this::toResultSummary).toList();
        Map<String, Object> out = new HashMap<>();
        out.put("items", items);
        out.put("page", safePage);
        out.put("size", safeSize);
        out.put("total", pageData.getTotalElements());
        return out;
    }

    public Map<String, Object> getResultDetail(String resultId) {
        var entity = backtestResultRepository.findByResultId(resultId).orElse(null);
        if (entity == null) return null;
        return toResultSummary(entity);
    }

    /** 解析策略标识：支持 id(1,2,3)、name(MA_Cross 等) */
    private String resolveStrategyName(Object key) {
        if (key == null) return "MA_Cross";
        if (key instanceof Number n) {
            int id = n.intValue();
            return switch (id) {
                case 1 -> "MA_Cross";
                case 2 -> "BOLL_Strategy";
                case 3 -> "MACD_Strategy";
                default -> "MA_Cross";
            };
        }
        String s = String.valueOf(key).trim();
        String upper = s.toUpperCase();
        if (upper.contains("MA_CROSS") || s.contains("均线")) return "MA_Cross";
        if (upper.contains("BOLL") || s.contains("布林")) return "BOLL_Strategy";
        if (upper.contains("MACD")) return "MACD_Strategy";
        return "MA_Cross";
    }

    private String[] parseStockCode(String symbol) {
        if (symbol == null || symbol.isBlank()) return new String[]{"000001", "SZSE"};
        String code = symbol.split("\\.")[0].trim();
        String ex = "SZSE";
        if (symbol.toUpperCase().endsWith(".SH")) ex = "SSE";
        else if (symbol.toUpperCase().endsWith(".BJ")) ex = "BSE";
        else if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) ex = "SSE";
        else if (code.startsWith("8") || code.startsWith("4")) ex = "BSE";
        return new String[]{code, ex};
    }

    private List<Bar> toBars(List<MarketData> list) {
        List<Bar> bars = new ArrayList<>();
        for (MarketData m : list) {
            double close = m.getClosePrice() != null ? m.getClosePrice().doubleValue() : 0;
            if (close <= 0) continue;
            bars.add(new Bar(
                    m.getTradeDate() != null ? m.getTradeDate().toString() : "",
                    m.getOpenPrice() != null ? m.getOpenPrice().doubleValue() : close,
                    m.getHighPrice() != null ? m.getHighPrice().doubleValue() : close,
                    m.getLowPrice() != null ? m.getLowPrice().doubleValue() : close,
                    close,
                    m.getVolume() != null ? m.getVolume() : 0
            ));
        }
        return bars;
    }

    private BacktestResult runStrategy(String strategyName, List<Bar> bars, double initialCash, Map<String, Object> params, CostModel costModel) {
        double cash = initialCash;
        int position = 0;
        double avgCost = 0;
        double totalCost = 0;
        List<Trade> trades = new ArrayList<>();
        List<double[]> equityCurve = new ArrayList<>();

        List<Double> prices = new ArrayList<>();
        for (Bar b : bars) prices.add(b.close);

        Map<String, Object> p = params != null ? params : Collections.emptyMap();
        int i = 0;
        for (Bar bar : bars) {
            String signal = "hold";
            switch (strategyName) {
                case "MA_Cross" -> signal = signalMA_Cross(prices, i, position, p);
                case "BOLL_Strategy" -> signal = signalBOLL(prices, i, position, p);
                case "MACD_Strategy" -> signal = signalMACD(prices, i, position, p);
                default -> signal = signalMA_Cross(prices, i, position, p);
            }

            if ("buy".equals(signal) && cash > 0) {
                double buyPrice = bar.close * (1 + costModel.slippageBps / 10000.0);
                int vol = (int) (cash * 0.95 / buyPrice / 100) * 100;
                if (vol > 0) {
                    double amount = vol * buyPrice;
                    double fee = calcTradeCost(amount, false, costModel);
                    double cost = amount + fee;
                    cash -= cost;
                    totalCost += fee;
                    avgCost = (avgCost * position + buyPrice * vol) / (position + vol);
                    position += vol;
                    trades.add(new Trade("buy", bar.date, buyPrice, vol, fee));
                }
            } else if ("sell".equals(signal) && position > 0) {
                double sellPrice = bar.close * (1 - costModel.slippageBps / 10000.0);
                double amount = position * sellPrice;
                double fee = calcTradeCost(amount, true, costModel);
                cash += amount - fee;
                totalCost += fee;
                trades.add(new Trade("sell", bar.date, sellPrice, position, fee));
                position = 0;
                avgCost = 0;
            }

            double equity = cash + position * bar.close;
            equityCurve.add(new double[]{i, equity});
            i++;
        }

        double finalEquity = cash + position * (bars.isEmpty() ? 0 : bars.get(bars.size() - 1).close);
        return computeMetrics(initialCash, finalEquity, equityCurve, trades, bars, totalCost);
    }

    private String signalMA_Cross(List<Double> prices, int idx, int position, Map<String, Object> params) {
        int shortPeriod = getInt(params, "short_period", 5);
        int longPeriod = getInt(params, "long_period", 20);
        if (idx < longPeriod) return "hold";
        double shortMA = avg(prices, idx - shortPeriod + 1, idx);
        double longMA = avg(prices, idx - longPeriod + 1, idx);
        double prevShort = avg(prices, idx - shortPeriod, idx - 1);
        double prevLong = avg(prices, idx - longPeriod, idx - 1);
        if (prevShort <= prevLong && shortMA > longMA && position == 0) return "buy";
        if (prevShort >= prevLong && shortMA < longMA && position > 0) return "sell";
        return "hold";
    }

    /** 布林带：价格触及/突破下轨买入，触及/突破上轨卖出（允许 1% 容差以增加交易机会） */
    private String signalBOLL(List<Double> prices, int idx, int position, Map<String, Object> params) {
        int period = getInt(params, "period", 20);
        double stdMult = getDouble(params, "std_dev", 2.0);
        if (idx < period) return "hold";
        double ma = avg(prices, idx - period + 1, idx);
        double variance = 0;
        for (int j = idx - period + 1; j <= idx; j++) {
            double d = prices.get(j) - ma;
            variance += d * d;
        }
        double std = Math.sqrt(Math.max(0, variance / period));
        double upper = ma + stdMult * std;
        double lower = ma - stdMult * std;
        double close = prices.get(idx);
        if (close <= lower * 1.01 && position == 0) return "buy";   // 接近或跌破下轨
        if (close >= upper * 0.99 && position > 0) return "sell";   // 接近或突破上轨
        return "hold";
    }

    private String signalMACD(List<Double> prices, int idx, int position, Map<String, Object> params) {
        int fast = getInt(params, "fast", 12);
        int slow = getInt(params, "slow", 26);
        int signalPeriod = getInt(params, "signal", 9);
        if (idx < slow + signalPeriod + 1) return "hold";
        List<Double> macdLine = new ArrayList<>();
        for (int j = 0; j <= idx; j++) {
            if (j < slow) macdLine.add(0.0);
            else macdLine.add(ema(prices, 0, j, fast) - ema(prices, 0, j, slow));
        }
        List<Double> validMacd = new ArrayList<>(macdLine.subList(slow, macdLine.size()));
        if (validMacd.size() < signalPeriod + 2) return "hold";
        double currMacd = validMacd.get(validMacd.size() - 1);
        double prevMacd = validMacd.get(validMacd.size() - 2);
        double currSignal = ema(validMacd, 0, validMacd.size() - 1, signalPeriod);
        double prevSignal = ema(validMacd.subList(0, validMacd.size() - 1), 0, validMacd.size() - 2, signalPeriod);
        double hist = currMacd - currSignal;
        double prevHist = prevMacd - prevSignal;
        if (prevHist <= 0 && hist > 0 && position == 0) return "buy";
        if (prevHist >= 0 && hist < 0 && position > 0) return "sell";
        return "hold";
    }

    private int getInt(Map<String, Object> params, String key, int def) {
        Object v = params != null ? params.get(key) : null;
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private double getDouble(Map<String, Object> params, String key, double def) {
        Object v = params != null ? params.get(key) : null;
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private double avg(List<Double> list, int from, int to) {
        double sum = 0;
        int cnt = 0;
        for (int i = from; i <= to && i < list.size(); i++) {
            sum += list.get(i);
            cnt++;
        }
        return cnt > 0 ? sum / cnt : 0;
    }

    private double ema(List<Double> list, int from, int to, int period) {
        if (to - from + 1 < period) return 0;
        double k = 2.0 / (period + 1);
        double ema = avg(list, from, from + period - 1);
        for (int i = from + period; i <= to && i < list.size(); i++) {
            ema = list.get(i) * k + ema * (1 - k);
        }
        return ema;
    }

    private BacktestResult computeMetrics(double initialCash, double finalEquity,
                                          List<double[]> equityCurve, List<Trade> trades, List<Bar> bars,
                                          double totalCost) {
        double totalReturnPct = initialCash > 0 ? (finalEquity - initialCash) / initialCash * 100 : 0;
        int days = Math.max(1, bars.size());
        double annualReturnPct = initialCash > 0
                ? (Math.pow(finalEquity / initialCash, 252.0 / days) - 1) * 100
                : 0;

        double sharpeRatio = 0;
        if (equityCurve.size() > 1) {
            List<Double> returns = new ArrayList<>();
            for (int i = 1; i < equityCurve.size(); i++) {
                double r = (equityCurve.get(i)[1] - equityCurve.get(i - 1)[1]) / equityCurve.get(i - 1)[1];
                returns.add(r);
            }
            double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = returns.stream().mapToDouble(r -> (r - mean) * (r - mean)).average().orElse(0);
            double std = Math.sqrt(variance);
            if (std > 0) sharpeRatio = (mean / std) * Math.sqrt(252);
        }

        double maxDrawdownPct = 0;
        double peak = initialCash;
        for (double[] pt : equityCurve) {
            peak = Math.max(peak, pt[1]);
            double dd = (peak - pt[1]) / peak * 100;
            if (dd > maxDrawdownPct) maxDrawdownPct = dd;
        }

        double winRate = 0;
        int buyIdx = 0;
        int wins = 0, pairs = 0;
        for (int t = 0; t < trades.size(); t++) {
            if ("buy".equals(trades.get(t).type)) buyIdx = t;
            if ("sell".equals(trades.get(t).type) && buyIdx < t) {
                pairs++;
                if (trades.get(t).price > trades.get(buyIdx).price) wins++;
            }
        }
        winRate = pairs > 0 ? (double) wins / pairs * 100 : 0;

        List<Map<String, Object>> curve = new ArrayList<>();
        for (int j = 0; j < equityCurve.size() && j < bars.size(); j++) {
            curve.add(Map.of("date", bars.get(j).date, "equity", (int) equityCurve.get(j)[1]));
        }

        BacktestResult r = new BacktestResult();
        r.totalReturnPct = totalReturnPct;
        r.annualReturnPct = annualReturnPct;
        r.sharpeRatio = sharpeRatio;
        r.maxDrawdownPct = -maxDrawdownPct;
        r.totalTrades = trades.size();
        r.winRate = winRate;
        r.finalEquity = finalEquity;
        r.totalCost = totalCost;
        r.equityCurve = curve;
        return r;
    }

    private CostModel resolveCostModel(Map<String, Object> params) {
        return new CostModel(
                getDouble(params, "commission_rate", costProperties.getCommissionRate()),
                getDouble(params, "min_commission", costProperties.getMinCommission()),
                getDouble(params, "stamp_duty_rate", costProperties.getStampDutyRate()),
                getDouble(params, "transfer_fee_rate", costProperties.getTransferFeeRate()),
                getDouble(params, "slippage_bps", costProperties.getSlippageBps())
        );
    }

    private double calcTradeCost(double amount, boolean isSell, CostModel model) {
        double commission = Math.max(amount * model.commissionRate, model.minCommission);
        double transferFee = amount * model.transferFeeRate;
        double stampDuty = isSell ? amount * model.stampDutyRate : 0;
        return commission + transferFee + stampDuty;
    }

    private String saveBacktestResult(String strategyName, Object strategyKey, LocalDate startDate, LocalDate endDate,
                                      double initialCash, Map<String, Object> params, BacktestResult result, int runTimeSeconds) {
        try {
            BacktestResultEntity entity = new BacktestResultEntity();
            entity.setResultId(genResultId());
            entity.setStrategyId(parseStrategyId(strategyKey));
            entity.setStrategyName(strategyName);
            entity.setStartDate(startDate);
            entity.setEndDate(endDate);
            entity.setInitialCapital(BigDecimal.valueOf(initialCash));
            entity.setParams(params != null ? params : Collections.emptyMap());
            entity.setFinalCapital(BigDecimal.valueOf(result.finalEquity));
            entity.setTotalReturn(BigDecimal.valueOf(round4(result.totalReturnPct)));
            entity.setAnnualReturn(BigDecimal.valueOf(round4(result.annualReturnPct)));
            entity.setSharpeRatio(BigDecimal.valueOf(round4(result.sharpeRatio)));
            entity.setMaxDrawdown(BigDecimal.valueOf(round4(-result.maxDrawdownPct)));
            entity.setTotalTrades(result.totalTrades);
            entity.setWinRate(BigDecimal.valueOf(round4(result.winRate)));
            entity.setStatus(1);
            entity.setRunTime(Math.max(0, runTimeSeconds));
            backtestResultRepository.save(entity);
            return entity.getResultId();
        } catch (Exception e) {
            log.error("保存回测结果失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private Map<String, Object> toResultSummary(BacktestResultEntity e) {
        Map<String, Object> item = new HashMap<>();
        item.put("result_id", e.getResultId());
        item.put("strategy_id", e.getStrategyId());
        item.put("strategy_name", e.getStrategyName());
        item.put("start_date", e.getStartDate() != null ? e.getStartDate().toString() : null);
        item.put("end_date", e.getEndDate() != null ? e.getEndDate().toString() : null);
        item.put("initial_capital", e.getInitialCapital());
        item.put("final_capital", e.getFinalCapital());
        item.put("total_return", e.getTotalReturn());
        item.put("annual_return", e.getAnnualReturn());
        item.put("sharpe_ratio", e.getSharpeRatio());
        item.put("max_drawdown", e.getMaxDrawdown());
        item.put("total_trades", e.getTotalTrades());
        item.put("win_rate", e.getWinRate());
        item.put("status", e.getStatus());
        item.put("run_time", e.getRunTime());
        item.put("created_at", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
        item.put("params", e.getParams());
        return item;
    }

    private Long parseStrategyId(Object strategyKey) {
        if (strategyKey instanceof Number n) return Math.max(1, n.longValue());
        try {
            return Math.max(1, Long.parseLong(String.valueOf(strategyKey)));
        } catch (Exception ignore) {
            return switch (resolveStrategyName(strategyKey)) {
                case "BOLL_Strategy" -> 2L;
                case "MACD_Strategy" -> 3L;
                default -> 1L;
            };
        }
    }

    private String genResultId() {
        return "BT" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private Map<String, Object> buildEmptyResult(double initialCash) {
        Map<String, Object> out = new HashMap<>();
        out.put("totalReturn", "0.00%");
        out.put("total_return", 0.0);
        out.put("annual_return", 0.0);
        out.put("sharpeRatio", 0.0);
        out.put("sharpe_ratio", 0.0);
        out.put("maxDrawdown", "0.00%");
        out.put("max_drawdown", 0.0);
        out.put("totalTrades", 0);
        out.put("total_trades", 0);
        out.put("winRate", 0.0);
        out.put("win_rate", 0.0);
        out.put("total_cost", 0.0);
        out.put("finalEquity", initialCash);
        out.put("equity_curve", List.of());
        out.put("result_id", null);
        out.put("message", "无行情数据，请确认标的与日期范围");
        return out;
    }

    private record Bar(String date, double open, double high, double low, double close, long volume) {}
    private record Trade(String type, String date, double price, int volume, double fee) {}
    private record CostModel(double commissionRate, double minCommission, double stampDutyRate,
                             double transferFeeRate, double slippageBps) {}
    private static class BacktestResult {
        double totalReturnPct, annualReturnPct, sharpeRatio, maxDrawdownPct, winRate, finalEquity;
        double totalCost;
        int totalTrades;
        List<Map<String, Object>> equityCurve;
    }
}
