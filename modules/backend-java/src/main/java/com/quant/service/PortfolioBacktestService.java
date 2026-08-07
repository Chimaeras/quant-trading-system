package com.quant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.entity.PortfolioBacktestRun;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.exception.BusinessException;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.PortfolioBacktestRunRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.research.ResearchConvention;
import com.quant.service.backtest.StrategyBacktestEngine;
import com.quant.service.backtest.TradingLimitRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 事件驱动组合回测 MVP（MP-015）：现金、等权、仓位上限、T+1 卖出、ResearchConvention 成本，优先 raw 价。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioBacktestService {

    private static final String EXECUTION_VERSION = "portfolio_event_v1";
    private static final int LOT_SIZE = 100;

    private final PortfolioBacktestRunRepository portfolioBacktestRunRepository;
    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final TradingCalendarService tradingCalendarService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> run(Map<String, Object> request) {
        LocalDate startDate = parseDate(request.get("startDate"), "startDate");
        LocalDate endDate = parseDate(request.get("endDate"), "endDate");
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("endDate 不能早于 startDate");
        }
        String strategyType = str(request.get("strategyType"), "CLASSIC_SELECTOR");
        int horizon = intVal(request.get("horizon"), 2);
        if (!ResearchConvention.LABEL_HORIZONS.contains(horizon) && horizon != 1) {
            throw new BusinessException("horizon 仅支持 1/2/3");
        }
        double initialCash = doubleVal(request.get("initialCash"), 1_000_000.0);
        int maxPositions = Math.max(1, intVal(request.get("maxPositions"), 5));
        int topN = Math.max(1, intVal(request.get("topN"), maxPositions));
        double commissionRate = doubleVal(request.get("commissionRate"), ResearchConvention.DEFAULT_COMMISSION_RATE);
        double sellTaxRate = doubleVal(request.get("sellTaxRate"), ResearchConvention.DEFAULT_SELL_TAX_RATE);
        double slippageRate = doubleVal(request.get("slippageRate"), ResearchConvention.DEFAULT_SLIPPAGE_RATE);

        LocalDate priceEnd = endDate.plusDays(horizon * 3L + 30);
        List<LocalDate> tradingDays = tradingCalendarService.findTradingDays(startDate, priceEnd);
        if (tradingDays.isEmpty()) {
            tradingDays = marketDataRepository.findDistinctTradeDatesBetween(startDate, priceEnd);
        }
        if (tradingDays.isEmpty()) {
            throw new BusinessException("区间内无交易日");
        }

        Map<LocalDate, List<SelectorResultItem>> signalsByDate = loadSignals(strategyType, startDate, endDate, topN);

        Map<String, MarketPriceRaw> rawMap = new HashMap<>();
        for (MarketPriceRaw raw : marketPriceRawRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                        startDate.minusDays(5), priceEnd)) {
            rawMap.put(priceKey(raw.getStockCode(), raw.getExchangeType(), raw.getTradeDate()), raw);
        }
        Map<String, MarketData> qfqMap = new HashMap<>();
        for (MarketData md : marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                        startDate.minusDays(5), priceEnd)) {
            qfqMap.put(priceKey(md.getStockCode(), md.getExchangeType(), md.getTradeDate()), md);
        }

        double cash = initialCash;
        Map<String, Position> positions = new LinkedHashMap<>();
        Map<LocalDate, List<PendingBuy>> pendingBuys = new LinkedHashMap<>();
        List<Map<String, Object>> orders = new ArrayList<>();
        List<Map<String, Object>> navCurve = new ArrayList<>();
        int rejectCount = 0;
        int tradeCount = 0;
        double peakNav = initialCash;
        double maxDrawdown = 0;

        LocalDate lastSim = tradingDays.get(tradingDays.size() - 1);

        for (LocalDate day : tradingDays) {
            if (day.isBefore(startDate) || day.isAfter(lastSim)) {
                continue;
            }

            // --- sells (T+1: entryDate 当日不可卖) ---
            List<String> toClose = new ArrayList<>();
            for (Map.Entry<String, Position> e : positions.entrySet()) {
                Position p = e.getValue();
                if (!day.isBefore(p.exitDate) && day.isAfter(p.entryDate)) {
                    toClose.add(e.getKey());
                }
            }
            for (String key : toClose) {
                Position p = positions.remove(key);
                PriceBar bar = resolvePriceBar(p.stockCode, p.exchangeType, day, rawMap, qfqMap);
                if (bar == null || bar.close == null || bar.close <= 0 || bar.zeroVolume) {
                    orders.add(rejectOrder("SELL", day, p, bar == null ? "NO_QUOTE" : "NOT_SELLABLE"));
                    rejectCount++;
                    positions.put(key, p);
                    continue;
                }
                double preClose = resolvePreClose(bar, p.stockCode, p.exchangeType, day, rawMap, qfqMap, tradingDays);
                double limitPct = TradingLimitRules.resolveLimitPct(p.stockCode, p.exchangeType, p.stockName);
                MarketData md = new MarketData();
                md.setClosePrice(BigDecimal.valueOf(bar.close));
                if (StrategyBacktestEngine.isLimitDownAtClose(md, preClose, limitPct)) {
                    orders.add(rejectOrder("SELL", day, p, "LIMIT_DOWN"));
                    rejectCount++;
                    positions.put(key, p);
                    continue;
                }
                double exitPx = bar.close * (1 - slippageRate);
                double proceeds = exitPx * p.shares * (1 - commissionRate - sellTaxRate);
                cash += proceeds;
                Map<String, Object> fill = baseOrder("SELL", day, p);
                fill.put("status", "FILLED");
                fill.put("price", round4(exitPx));
                fill.put("shares", p.shares);
                fill.put("amount", round4(proceeds));
                fill.put("price_source", bar.source);
                orders.add(fill);
                tradeCount++;
            }

            // --- buys scheduled for today (signal 次日开盘) ---
            List<PendingBuy> buys = pendingBuys.remove(day);
            if (buys != null && !buys.isEmpty()) {
                List<PendingBuy> eligible = buys.stream()
                        .filter(b -> !positions.containsKey(b.key()))
                        .limit(Math.max(0, maxPositions - positions.size()))
                        .toList();
                int n = eligible.size();
                if (n > 0) {
                    double budgetEach = cash / n;
                    for (PendingBuy b : eligible) {
                        PriceBar bar = resolvePriceBar(b.stockCode, b.exchangeType, day, rawMap, qfqMap);
                        if (bar == null || bar.open == null || bar.open <= 0 || bar.zeroVolume) {
                            orders.add(rejectBuy(day, b, bar == null ? "NO_QUOTE" : "NOT_BUYABLE"));
                            rejectCount++;
                            continue;
                        }
                        double preClose = resolvePreClose(bar, b.stockCode, b.exchangeType, day, rawMap, qfqMap, tradingDays);
                        double limitPct = TradingLimitRules.resolveLimitPct(b.stockCode, b.exchangeType, b.stockName);
                        MarketData openBar = new MarketData();
                        openBar.setOpenPrice(BigDecimal.valueOf(bar.open));
                        if (StrategyBacktestEngine.isLimitUpAtOpen(openBar, preClose, limitPct)) {
                            orders.add(rejectBuy(day, b, "LIMIT_UP"));
                            rejectCount++;
                            continue;
                        }
                        double entryPx = bar.open * (1 + slippageRate);
                        double costPerShare = entryPx * (1 + commissionRate);
                        int shares = ((int) (budgetEach / costPerShare) / LOT_SIZE) * LOT_SIZE;
                        if (shares < LOT_SIZE) {
                            orders.add(rejectBuy(day, b, "INSUFFICIENT_CASH_FOR_LOT"));
                            rejectCount++;
                            continue;
                        }
                        double totalCost = costPerShare * shares;
                        if (totalCost > cash + 1e-6) {
                            orders.add(rejectBuy(day, b, "INSUFFICIENT_CASH"));
                            rejectCount++;
                            continue;
                        }
                        cash -= totalCost;
                        positions.put(b.key(), new Position(
                                b.stockCode, b.exchangeType, b.stockName,
                                shares, entryPx, day, b.exitDate, bar.source));
                        Map<String, Object> fill = new LinkedHashMap<>();
                        fill.put("action", "BUY");
                        fill.put("trade_date", day.toString());
                        fill.put("stock_code", b.stockCode);
                        fill.put("exchange_type", b.exchangeType);
                        fill.put("stock_name", b.stockName);
                        fill.put("status", "FILLED");
                        fill.put("price", round4(entryPx));
                        fill.put("shares", shares);
                        fill.put("amount", round4(totalCost));
                        fill.put("price_source", bar.source);
                        fill.put("signal_date", b.signalDate.toString());
                        fill.put("exit_date", b.exitDate.toString());
                        orders.add(fill);
                        tradeCount++;
                    }
                }
            }

            // --- schedule new signals ---
            if (!day.isAfter(endDate) && signalsByDate.containsKey(day)) {
                LocalDate entryDate = nextTradingDay(tradingDays, day);
                LocalDate exitDate = tradingDayOffset(tradingDays, entryDate, horizon);
                if (entryDate != null && exitDate != null) {
                    int pendingCount = pendingBuys.values().stream().mapToInt(List::size).sum();
                    int slots = maxPositions - positions.size() - pendingCount;
                    if (slots > 0) {
                        List<PendingBuy> batch = new ArrayList<>();
                        for (SelectorResultItem item : signalsByDate.get(day)) {
                            if (batch.size() >= slots) {
                                break;
                            }
                            String key = item.getStockCode() + "|" + item.getExchangeType();
                            if (positions.containsKey(key)) {
                                continue;
                            }
                            boolean alreadyPending = pendingBuys.values().stream()
                                    .flatMap(List::stream).anyMatch(p -> p.key().equals(key));
                            if (alreadyPending) {
                                continue;
                            }
                            batch.add(new PendingBuy(
                                    item.getStockCode(), item.getExchangeType(), item.getStockName(),
                                    day, entryDate, exitDate));
                        }
                        if (!batch.isEmpty()) {
                            pendingBuys.computeIfAbsent(entryDate, k -> new ArrayList<>()).addAll(batch);
                        }
                    }
                }
            }

            // --- NAV ---
            double posValue = 0;
            for (Position p : positions.values()) {
                PriceBar bar = resolvePriceBar(p.stockCode, p.exchangeType, day, rawMap, qfqMap);
                double px = bar != null && bar.close != null ? bar.close : p.entryPrice;
                posValue += px * p.shares;
            }
            double nav = cash + posValue;
            peakNav = Math.max(peakNav, nav);
            if (peakNav > 0) {
                maxDrawdown = Math.max(maxDrawdown, (peakNav - nav) / peakNav);
            }
            if (!day.isAfter(endDate) || !positions.isEmpty()) {
                Map<String, Object> navPoint = new LinkedHashMap<>();
                navPoint.put("trade_date", day.toString());
                navPoint.put("cash", round4(cash));
                navPoint.put("position_value", round4(posValue));
                navPoint.put("nav", round4(nav));
                navPoint.put("positions", positions.size());
                navCurve.add(navPoint);
            }
        }

        double finalNav = navCurve.isEmpty() ? cash
                : ((Number) navCurve.get(navCurve.size() - 1).get("nav")).doubleValue();
        double totalReturn = initialCash > 0 ? finalNav / initialCash - 1 : 0;

        String runId = "PBT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("strategy_type", strategyType);
        summary.put("start_date", startDate.toString());
        summary.put("end_date", endDate.toString());
        summary.put("horizon", horizon);
        summary.put("initial_cash", initialCash);
        summary.put("max_positions", maxPositions);
        summary.put("weighting", "EQUAL");
        summary.put("final_nav", round4(finalNav));
        summary.put("total_return", round4(totalReturn));
        summary.put("max_drawdown", round4(maxDrawdown));
        summary.put("total_trades", tradeCount);
        summary.put("reject_count", rejectCount);
        summary.put("cost_version", ResearchConvention.CONVENTION_VERSION);
        summary.put("execution_version", EXECUTION_VERSION);
        summary.put("commission_rate", commissionRate);
        summary.put("sell_tax_rate", sellTaxRate);
        summary.put("slippage_rate", slippageRate);
        summary.put("t_plus_one", ResearchConvention.T_PLUS_ONE_SELL);
        summary.put("note", "事件驱动组合回测 MVP：等权、仓位上限、T+1、整手、优先 raw 价；非分钟级撮合");

        PortfolioBacktestRun entity = new PortfolioBacktestRun();
        entity.setRunId(runId);
        entity.setStrategyType(strategyType);
        entity.setStartDate(startDate);
        entity.setEndDate(endDate);
        entity.setHorizon(horizon);
        entity.setInitialCash(initialCash);
        entity.setMaxPositions(maxPositions);
        entity.setWeighting("EQUAL");
        entity.setCommissionRate(commissionRate);
        entity.setSellTaxRate(sellTaxRate);
        entity.setSlippageRate(slippageRate);
        entity.setCostVersion(ResearchConvention.CONVENTION_VERSION);
        entity.setExecutionVersion(EXECUTION_VERSION);
        entity.setFinalNav(round4(finalNav));
        entity.setTotalReturn(round4(totalReturn));
        entity.setMaxDrawdown(round4(maxDrawdown));
        entity.setTotalTrades(tradeCount);
        entity.setRejectCount(rejectCount);
        entity.setSummaryJson(writeJson(summary));
        entity.setNavJson(writeJson(navCurve));
        entity.setOrdersJson(writeJson(orders));
        entity.setStatus("COMPLETED");
        portfolioBacktestRunRepository.save(entity);

        Map<String, Object> out = new LinkedHashMap<>(summary);
        out.put("nav", navCurve);
        out.put("orders", orders);
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(String runId) {
        PortfolioBacktestRun run = portfolioBacktestRunRepository.findByRunId(runId)
                .orElseThrow(() -> new BusinessException("组合回测不存在: " + runId));
        Map<String, Object> out = parseMap(run.getSummaryJson());
        if (out.isEmpty()) {
            out = new LinkedHashMap<>();
            out.put("run_id", run.getRunId());
            out.put("final_nav", run.getFinalNav());
            out.put("total_return", run.getTotalReturn());
        }
        out.put("nav", parseList(run.getNavJson()));
        out.put("orders", parseList(run.getOrdersJson()));
        out.put("status", run.getStatus());
        out.put("created_at", run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        return out;
    }

    private Map<LocalDate, List<SelectorResultItem>> loadSignals(
            String strategyType, LocalDate start, LocalDate end, int topN
    ) {
        List<SelectorResult> results = selectorResultRepository
                .findByTradeDateBetweenOrderByCreatedAtDesc(start, end).stream()
                .filter(r -> strategyType.equals(r.getStrategyType()))
                .filter(r -> "SUCCESS".equals(r.getRunStatus()))
                .toList();
        Map<LocalDate, SelectorResult> latest = new LinkedHashMap<>();
        for (SelectorResult r : results) {
            latest.putIfAbsent(r.getTradeDate(), r);
        }
        Map<LocalDate, List<SelectorResultItem>> out = new LinkedHashMap<>();
        if (latest.isEmpty()) {
            return out;
        }
        List<String> ids = latest.values().stream().map(SelectorResult::getResultId).toList();
        Map<String, List<SelectorResultItem>> itemsByResult = selectorResultItemRepository
                .findByResultIdInOrderByRankNo(ids).stream()
                .collect(Collectors.groupingBy(SelectorResultItem::getResultId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<LocalDate, SelectorResult> e : latest.entrySet()) {
            List<SelectorResultItem> items = itemsByResult.getOrDefault(e.getValue().getResultId(), List.of())
                    .stream()
                    .sorted(Comparator.comparingInt(i -> i.getRankNo() != null ? i.getRankNo() : Integer.MAX_VALUE))
                    .limit(topN)
                    .toList();
            out.put(e.getKey(), items);
        }
        return out;
    }

    private static LocalDate nextTradingDay(List<LocalDate> days, LocalDate day) {
        for (LocalDate d : days) {
            if (d.isAfter(day)) {
                return d;
            }
        }
        return null;
    }

    private static LocalDate tradingDayOffset(List<LocalDate> days, LocalDate from, int offset) {
        if (from == null) {
            return null;
        }
        int idx = days.indexOf(from);
        if (idx < 0) {
            for (int i = 0; i < days.size(); i++) {
                if (!days.get(i).isBefore(from)) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0) {
            return null;
        }
        int target = idx + offset;
        if (target >= days.size()) {
            return null;
        }
        return days.get(target);
    }

    private static PriceBar resolvePriceBar(
            String code, String exchange, LocalDate date,
            Map<String, MarketPriceRaw> rawMap, Map<String, MarketData> qfqMap
    ) {
        String key = priceKey(code, exchange, date);
        MarketPriceRaw raw = rawMap.get(key);
        if (raw != null && (raw.getOpenPrice() != null || raw.getClosePrice() != null)) {
            return new PriceBar(
                    raw.getOpenPrice() != null ? raw.getOpenPrice().doubleValue() : null,
                    raw.getClosePrice() != null ? raw.getClosePrice().doubleValue() : null,
                    raw.getPreClose() != null ? raw.getPreClose().doubleValue() : null,
                    raw.getVolume() != null && raw.getVolume() <= 0,
                    "market_price_raw");
        }
        MarketData md = qfqMap.get(key);
        if (md != null) {
            return new PriceBar(
                    md.getOpenPrice() != null ? md.getOpenPrice().doubleValue() : null,
                    md.getClosePrice() != null ? md.getClosePrice().doubleValue() : null,
                    md.getPreClose() != null ? md.getPreClose().doubleValue() : null,
                    md.getVolume() != null && md.getVolume() <= 0,
                    "qfq_legacy");
        }
        return null;
    }

    private static double resolvePreClose(
            PriceBar bar, String code, String exchange, LocalDate date,
            Map<String, MarketPriceRaw> rawMap, Map<String, MarketData> qfqMap,
            List<LocalDate> tradingDays
    ) {
        if (bar.preClose != null && bar.preClose > 0) {
            return bar.preClose;
        }
        LocalDate prev = null;
        for (LocalDate d : tradingDays) {
            if (d.isBefore(date)) {
                prev = d;
            } else {
                break;
            }
        }
        if (prev != null) {
            PriceBar prevBar = resolvePriceBar(code, exchange, prev, rawMap, qfqMap);
            if (prevBar != null && prevBar.close != null) {
                return prevBar.close;
            }
        }
        return 0;
    }

    private static String priceKey(String code, String exchange, LocalDate date) {
        return code + "|" + exchange + "|" + date;
    }

    private static Map<String, Object> baseOrder(String action, LocalDate day, Position p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("trade_date", day.toString());
        m.put("stock_code", p.stockCode);
        m.put("exchange_type", p.exchangeType);
        m.put("stock_name", p.stockName);
        return m;
    }

    private static Map<String, Object> rejectOrder(String action, LocalDate day, Position p, String reason) {
        Map<String, Object> m = baseOrder(action, day, p);
        m.put("status", "REJECTED");
        m.put("reject_reason", reason);
        return m;
    }

    private static Map<String, Object> rejectBuy(LocalDate day, PendingBuy b, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", "BUY");
        m.put("trade_date", day.toString());
        m.put("stock_code", b.stockCode);
        m.put("exchange_type", b.exchangeType);
        m.put("stock_name", b.stockName);
        m.put("status", "REJECTED");
        m.put("reject_reason", reason);
        m.put("signal_date", b.signalDate.toString());
        return m;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static LocalDate parseDate(Object raw, String field) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new BusinessException(field + " 必填");
        }
        return LocalDate.parse(String.valueOf(raw).trim());
    }

    private static String str(Object raw, String fallback) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return fallback;
        }
        return String.valueOf(raw).trim();
    }

    private static int intVal(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleVal(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private record PriceBar(Double open, Double close, Double preClose, boolean zeroVolume, String source) {}

    private static final class Position {
        final String stockCode;
        final String exchangeType;
        final String stockName;
        final int shares;
        final double entryPrice;
        final LocalDate entryDate;
        final LocalDate exitDate;

        Position(String stockCode, String exchangeType, String stockName, int shares,
                 double entryPrice, LocalDate entryDate, LocalDate exitDate, String priceSource) {
            this.stockCode = stockCode;
            this.exchangeType = exchangeType;
            this.stockName = stockName;
            this.shares = shares;
            this.entryPrice = entryPrice;
            this.entryDate = entryDate;
            this.exitDate = exitDate;
        }
    }

    private record PendingBuy(
            String stockCode, String exchangeType, String stockName,
            LocalDate signalDate, LocalDate entryDate, LocalDate exitDate
    ) {
        String key() {
            return stockCode + "|" + exchangeType;
        }
    }
}
