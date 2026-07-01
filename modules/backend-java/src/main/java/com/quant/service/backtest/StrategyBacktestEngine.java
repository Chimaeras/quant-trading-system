package com.quant.service.backtest;

import com.quant.entity.MarketData;
import com.quant.service.scoring.TechnicalIndicators;

import java.util.ArrayList;
import java.util.List;

/**
 * 单股经典策略信号引擎（MA 交叉 / 布林带 / MACD）。
 */
public final class StrategyBacktestEngine {

    private static final double COMMISSION = 0.0003;
    private static final double SLIPPAGE = 0.001;
    private static final double STAMP_DUTY = 0.001;
    /** 主板默认涨跌停幅度（10%），用于判断开盘是否封板不可成交 */
    private static final double DEFAULT_LIMIT_PCT = 0.10;
    /** 涨停/跌停价容差，覆盖四舍五入与最小报价单位 */
    private static final double LIMIT_PRICE_TOLERANCE = 0.001;

    private StrategyBacktestEngine() {}

    public record TradeRecord(
            String tradeDate,
            String action,
            double price,
            int volume,
            double amount,
            double commission,
            double cashAfter,
            double equityAfter
    ) {}

    public record BacktestMetrics(
            double initialCapital,
            double finalCapital,
            double totalReturn,
            double maxDrawdown,
            double sharpeRatio,
            int totalTrades,
            int winningTrades,
            double winRate,
            List<TradeRecord> trades,
            List<DailyEquity> equityCurve
    ) {}

    public record DailyEquity(String tradeDate, double equity, double cash, int position, double closePrice) {}

    public static BacktestMetrics run(
            List<MarketData> bars,
            StrategySignalType strategy,
            double initialCapital,
            int shortPeriod,
            int longPeriod,
            int bollPeriod,
            double bollStdDev
    ) {
        if (bars == null || bars.size() < 30) {
            throw new IllegalArgumentException("K 线数据不足，至少需要 30 根");
        }

        double[] close = bars.stream()
                .mapToDouble(b -> b.getClosePrice() != null ? b.getClosePrice().doubleValue() : 0)
                .toArray();
        TechnicalIndicators.MacdResult macd = TechnicalIndicators.macd(close);

        double cash = initialCapital;
        int position = 0;
        double avgCost = 0;
        List<TradeRecord> trades = new ArrayList<>();
        List<DailyEquity> curve = new ArrayList<>();
        double peakEquity = initialCapital;
        double maxDrawdown = 0;
        List<Double> dailyReturns = new ArrayList<>();
        double prevEquity = initialCapital;
        int winningTrades = 0;
        int lastBuyBarIndex = -1;

        for (int i = 1; i < bars.size(); i++) {
            MarketData bar = bars.get(i);
            MarketData prevBar = bars.get(i - 1);
            double markPrice = close[i];
            // 信号基于前一交易日收盘数据，在当日开盘成交（避免同日收盘价成交的未来函数）
            String signal = detectSignal(strategy, close, macd, i - 1, shortPeriod, longPeriod, bollPeriod, bollStdDev);
            double execPrice = bar.getOpenPrice() != null
                    ? bar.getOpenPrice().doubleValue()
                    : markPrice;
            double preClose = resolvePreClose(bar, prevBar);

            if ("buy".equals(signal) && position == 0 && cash > 0
                    && !isLimitUpAtOpen(bar, preClose, DEFAULT_LIMIT_PCT)) {
                double buyPrice = execPrice * (1 + SLIPPAGE);
                int volume = (int) (cash / buyPrice / 100) * 100;
                if (volume >= 100) {
                    double amount = buyPrice * volume;
                    double commission = amount * COMMISSION;
                    cash -= amount + commission;
                    position = volume;
                    avgCost = buyPrice;
                    trades.add(new TradeRecord(
                            bar.getTradeDate().toString(), "buy", buyPrice, volume, amount, commission, cash, cash + position * markPrice
                    ));
                    lastBuyBarIndex = i;
                }
            } else if ("sell".equals(signal) && position > 0 && i > lastBuyBarIndex
                    && !isLimitDownAtOpen(bar, preClose, DEFAULT_LIMIT_PCT)) {
                double sellPrice = execPrice * (1 - SLIPPAGE);
                double amount = sellPrice * position;
                double commission = amount * COMMISSION;
                double stamp = amount * STAMP_DUTY;
                double pnl = (sellPrice - avgCost) * position - commission - stamp;
                if (pnl > 0) {
                    winningTrades++;
                }
                cash += amount - commission - stamp;
                trades.add(new TradeRecord(
                        bar.getTradeDate().toString(), "sell", sellPrice, position, amount, commission + stamp, cash, cash
                ));
                position = 0;
                avgCost = 0;
                lastBuyBarIndex = -1;
            }

            double equity = cash + position * markPrice;
            curve.add(new DailyEquity(bar.getTradeDate().toString(), equity, cash, position, markPrice));
            peakEquity = Math.max(peakEquity, equity);
            if (peakEquity > 0) {
                maxDrawdown = Math.max(maxDrawdown, (peakEquity - equity) / peakEquity);
            }
            if (prevEquity > 0) {
                dailyReturns.add((equity - prevEquity) / prevEquity);
            }
            prevEquity = equity;
        }

        double lastPrice = close[close.length - 1];
        double finalCapital = cash + position * lastPrice;
        double totalReturn = initialCapital > 0 ? (finalCapital - initialCapital) / initialCapital : 0;
        int sellTrades = (int) trades.stream().filter(t -> "sell".equals(t.action())).count();
        double winRate = sellTrades > 0 ? (double) winningTrades / sellTrades : 0;
        double sharpe = calcSharpe(dailyReturns);

        return new BacktestMetrics(
                initialCapital,
                finalCapital,
                totalReturn,
                maxDrawdown,
                sharpe,
                trades.size(),
                winningTrades,
                winRate,
                trades,
                curve
        );
    }

    private static String detectSignal(
            StrategySignalType strategy,
            double[] close,
            TechnicalIndicators.MacdResult macd,
            int i,
            int shortPeriod,
            int longPeriod,
            int bollPeriod,
            double bollStdDev
    ) {
        return switch (strategy) {
            case MA_CROSS -> maCross(close, i, shortPeriod, longPeriod);
            case BOLL -> bollSignal(close, i, bollPeriod, bollStdDev);
            case MACD -> macdSignal(macd, i);
        };
    }

    private static String maCross(double[] close, int i, int shortPeriod, int longPeriod) {
        if (i < longPeriod) {
            return "hold";
        }
        double shortPrev = TechnicalIndicators.smaAt(close, i - 1, shortPeriod);
        double longPrev = TechnicalIndicators.smaAt(close, i - 1, longPeriod);
        double shortCurr = TechnicalIndicators.smaAt(close, i, shortPeriod);
        double longCurr = TechnicalIndicators.smaAt(close, i, longPeriod);
        if (Double.isNaN(shortPrev) || Double.isNaN(longPrev)) {
            return "hold";
        }
        if (shortPrev <= longPrev && shortCurr > longCurr) {
            return "buy";
        }
        if (shortPrev >= longPrev && shortCurr < longCurr) {
            return "sell";
        }
        return "hold";
    }

    private static String bollSignal(double[] close, int i, int period, double stdDev) {
        var curr = TechnicalIndicators.bollinger(close, i, period, stdDev);
        var prev = TechnicalIndicators.bollinger(close, i - 1, period, stdDev);
        if (Double.isNaN(curr.lower()) || Double.isNaN(prev.lower())) {
            return "hold";
        }
        if (close[i - 1] > prev.lower() && close[i] <= curr.lower()) {
            return "buy";
        }
        if (close[i - 1] < prev.upper() && close[i] >= curr.upper()) {
            return "sell";
        }
        return "hold";
    }

    private static String macdSignal(TechnicalIndicators.MacdResult macd, int i) {
        if (i < 1) {
            return "hold";
        }
        // macd.macd() 已是 2*(DIF-DEA) 柱，勿再减 DEA
        double histPrev = macd.macd()[i - 1];
        double histCurr = macd.macd()[i];
        if (histPrev <= 0 && histCurr > 0) {
            return "buy";
        }
        if (histPrev >= 0 && histCurr < 0) {
            return "sell";
        }
        return "hold";
    }

  /**
     * 解析前收盘价：优先使用行情字段，否则取上一根 K 线收盘价。
     */
    static double resolvePreClose(MarketData bar, MarketData prevBar) {
        if (bar.getPreClose() != null && bar.getPreClose().doubleValue() > 0) {
            return bar.getPreClose().doubleValue();
        }
        if (prevBar.getClosePrice() != null && prevBar.getClosePrice().doubleValue() > 0) {
            return prevBar.getClosePrice().doubleValue();
        }
        return 0;
    }

    /**
     * 涨停开盘：开盘价落在涨停价容差带内，买单无法成交（A 股封板买入排队）。
     * 口径：涨停价 = 前收 × (1 + limitPct)；仅当开盘价贴近涨停价时视为封板，避免把异常跳空误判为涨停。
     */
    static boolean isLimitUpAtOpen(MarketData bar, double preClose, double limitPct) {
        if (preClose <= 0 || bar.getOpenPrice() == null) {
            return false;
        }
        double open = bar.getOpenPrice().doubleValue();
        if (open <= 0) {
            return false;
        }
        double limitUpPrice = preClose * (1 + limitPct);
        return open >= limitUpPrice * (1 - LIMIT_PRICE_TOLERANCE)
                && open <= limitUpPrice * (1 + LIMIT_PRICE_TOLERANCE);
    }

    /**
     * 跌停开盘：开盘价落在跌停价容差带内，卖单无法成交（A 股封板卖出排队）。
     */
    static boolean isLimitDownAtOpen(MarketData bar, double preClose, double limitPct) {
        if (preClose <= 0 || bar.getOpenPrice() == null) {
            return false;
        }
        double open = bar.getOpenPrice().doubleValue();
        if (open <= 0) {
            return false;
        }
        double limitDownPrice = preClose * (1 - limitPct);
        return open <= limitDownPrice * (1 + LIMIT_PRICE_TOLERANCE)
                && open >= limitDownPrice * (1 - LIMIT_PRICE_TOLERANCE);
    }

    private static double calcSharpe(List<Double> dailyReturns) {
        if (dailyReturns.size() < 2) {
            return 0;
        }
        double mean = dailyReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = dailyReturns.stream().mapToDouble(r -> Math.pow(r - mean, 2)).average().orElse(0);
        double std = Math.sqrt(variance);
        if (std < 1e-9) {
            return 0;
        }
        return mean / std * Math.sqrt(252);
    }
}
