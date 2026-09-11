package com.quant.service;

import com.quant.dto.StrategyBacktestRequest;
import com.quant.dto.StrategyBacktestResponse;
import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.service.backtest.StrategyBacktestEngine;
import com.quant.service.backtest.StrategySignalType;
import com.quant.service.backtest.TradingLimitRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StrategyBacktestService {

    private final MarketDataRepository marketDataRepository;
    private final MarketPriceRawRepository marketPriceRawRepository;
    private final StockInfoRepository stockInfoRepository;

    public StrategyBacktestResponse run(StrategyBacktestRequest req) {
        if (req.getStockCode() == null || req.getStockCode().isBlank()) {
            throw new IllegalArgumentException("stockCode 不能为空");
        }
        LocalDate end = parseDate(req.getEndDate(), LocalDate.now());
        LocalDate start = parseDate(req.getStartDate(), end.minusYears(1));
        if (start.isAfter(end)) {
            LocalDate t = start;
            start = end;
            end = t;
        }

        String code = req.getStockCode().trim();
        String exchange = req.getExchangeType() != null ? req.getExchangeType().trim() : "SZSE";
        List<MarketData> bars = marketDataRepository
                .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(code, exchange, start, end);
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("指定区间无 K 线数据，请先更新历史行情");
        }

        // V2-MP-014：成交执行价只读 market_price_raw（按交易日建索引）；缺 raw 当日由引擎跳过成交，禁止 qfq 兜底。
        Map<LocalDate, MarketPriceRaw> rawByDate = new HashMap<>();
        for (MarketPriceRaw raw : marketPriceRawRepository
                .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(code, exchange, start, end)) {
            rawByDate.put(raw.getTradeDate(), raw);
        }

        StrategySignalType strategy = StrategySignalType.parse(req.getStrategyType());
        double initial = req.getInitialCapital() != null ? req.getInitialCapital().doubleValue() : 100_000;
        int shortP = req.getShortPeriod() != null ? req.getShortPeriod() : 5;
        int longP = req.getLongPeriod() != null ? req.getLongPeriod() : 20;
        int bollP = req.getBollPeriod() != null ? req.getBollPeriod() : 20;
        double bollStd = req.getBollStdDev() != null ? req.getBollStdDev() : 2.0;
        int macdFast = req.getMacdFast() != null ? req.getMacdFast() : 12;
        int macdSlow = req.getMacdSlow() != null ? req.getMacdSlow() : 26;

        String stockName = stockInfoRepository.findByStockCodeAndExchangeType(code, exchange)
                .map(StockInfo::getStockName)
                .orElse(code);
        double limitPct = TradingLimitRules.resolveLimitPct(code, exchange, stockName);

        StrategyBacktestEngine.BacktestMetrics metrics = StrategyBacktestEngine.run(
                bars, rawByDate, strategy, initial, shortP, longP, bollP, bollStd, macdFast, macdSlow, limitPct
        );

        List<Map<String, Object>> trades = metrics.trades().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trade_date", t.tradeDate());
            m.put("action", t.action());
            m.put("price", round(t.price()));
            m.put("volume", t.volume());
            m.put("amount", round(t.amount()));
            m.put("commission", round(t.commission()));
            m.put("cash_after", round(t.cashAfter()));
            m.put("equity_after", round(t.equityAfter()));
            return m;
        }).toList();

        List<Map<String, Object>> curve = metrics.equityCurve().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trade_date", e.tradeDate());
            m.put("equity", round(e.equity()));
            m.put("cash", round(e.cash()));
            m.put("position", e.position());
            m.put("close_price", round(e.closePrice()));
            return m;
        }).toList();

        return StrategyBacktestResponse.builder()
                .strategyType(strategy.name())
                .stockCode(code)
                .exchangeType(exchange)
                .stockName(stockName)
                .startDate(start.toString())
                .endDate(end.toString())
                .initialCapital(BigDecimal.valueOf(round(metrics.initialCapital())))
                .finalCapital(BigDecimal.valueOf(round(metrics.finalCapital())))
                .totalReturn(round(metrics.totalReturn()))
                .maxDrawdown(round(metrics.maxDrawdown()))
                .sharpeRatio(round(metrics.sharpeRatio()))
                .totalTrades(metrics.totalTrades())
                .winningTrades(metrics.winningTrades())
                .winRate(round(metrics.winRate()))
                .trades(trades)
                .equityCurve(curve)
                .executionAssumptions(StrategyBacktestEngine.executionAssumptions(limitPct))
                .build();
    }

    public List<String> getStrategies() {
        return List.of("MA_CROSS", "BOLL", "MACD");
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            // 未包装的 parse 异常不在 GlobalExceptionHandler 的映射里 → 500。
            // 这是用户可控的请求体字段，应为 400（同类接口已主动抛 IllegalArgumentException）。
            throw new IllegalArgumentException("日期格式非法，应为 yyyy-MM-dd：" + raw);
        }
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
