package com.quant.service.backtest;

import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.research.ResearchConvention;
import com.quant.service.scoring.ScoringConstants;
import com.quant.service.scoring.TechnicalIndicators;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单股经典策略信号引擎（MA 交叉 / 布林带 / MACD）。
 *
 * <p>成交成本统一引用 {@link ResearchConvention} 单一真相源（佣金万三 / 印花税万五 / 单边滑点万二），
 * 不再散落硬编码。本引擎为「前日收盘产生信号、当日开盘成交」的自洽交易模型，买卖均在当日开盘成交
 * （entry/exit = SAME_DAY_OPEN），与标签/持有期回测的 HOLDING_PERIOD_CLOSE 口径不同——两者各自成立，
 * 但在对外输出时必须标注本引擎的时序假设，避免与持有期收益口径混淆。
 *
 * <p>V2-MP-014：信号与技术指标使用 qfq 复权序列（SIGNAL_FEATURE，仅影响模型输入）；成交执行价
 * 一律读 {@code market_price_raw} 原始开盘价（EXECUTION），缺 raw 或零量（停牌）当日跳过成交，
 * 禁止用 qfq 前复权价"救活"成交样本。前收涨跌停判定优先 raw 实际前收，缺失时 qfq 仅作参考。
 */
public final class StrategyBacktestEngine {

    private static final double COMMISSION = ResearchConvention.DEFAULT_COMMISSION_RATE;
    private static final double SLIPPAGE = ResearchConvention.DEFAULT_SLIPPAGE_RATE;
    private static final double STAMP_DUTY = ResearchConvention.DEFAULT_SELL_TAX_RATE;
    /** 主板默认涨跌停幅度（10%），用于判断开盘是否封板不可成交 */
    private static final double DEFAULT_LIMIT_PCT = 0.10;
    /** 涨停/跌停价容差，覆盖四舍五入与最小报价单位 */
    private static final double LIMIT_PRICE_TOLERANCE = 0.001;

    private StrategyBacktestEngine() {}

    /** 回测成交与费用假设，供 API 与前端展示。 */
    public static Map<String, Object> executionAssumptions() {
        return executionAssumptions(DEFAULT_LIMIT_PCT);
    }

    public static Map<String, Object> executionAssumptions(double limitPct) {
        Map<String, Object> assumptions = new LinkedHashMap<>();
        assumptions.put("signal_timing", "PREVIOUS_DAY_CLOSE");
        assumptions.put("entry", "SAME_DAY_OPEN");
        assumptions.put("exit", "SAME_DAY_OPEN");
        assumptions.put("commission_rate", COMMISSION);
        assumptions.put("stamp_duty_rate", STAMP_DUTY);
        assumptions.put("slippage_rate_each_side", SLIPPAGE);
        assumptions.put("costs_source", "ResearchConvention." + ResearchConvention.CONVENTION_VERSION);
        assumptions.put("execution_price_source", "market_price_raw");
        assumptions.put("execution_note",
                "V2-MP-014：成交执行价只读 market_price_raw 原始开盘价；缺 raw 或零量（停牌）当日跳过成交，"
                        + "禁止用 qfq 前复权价兜底。qfq 仅用于信号/技术指标与净值标记。");
        assumptions.put("exit_note",
                "策略引擎自洽口径：信号于前日收盘产生，买卖均在当日开盘成交；"
                        + "不同于标签/持有期回测的 HOLDING_PERIOD_CLOSE 口径");
        assumptions.put("t_plus_one_sell", true);
        assumptions.put("limit_up_entry_blocked", true);
        assumptions.put("limit_down_exit_blocked", true);
        assumptions.put("limit_pct", limitPct);
        assumptions.put("limit_pct_source", "BY_BOARD_AND_ST");
        assumptions.put("return_type", "NET_AFTER_MODELED_COSTS");
        return assumptions;
    }

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
        return run(bars, null, strategy, initialCapital, shortPeriod, longPeriod, bollPeriod, bollStdDev,
                ScoringConstants.MACD_FAST, ScoringConstants.MACD_SLOW, DEFAULT_LIMIT_PCT);
    }

    public static BacktestMetrics run(
            List<MarketData> bars,
            StrategySignalType strategy,
            double initialCapital,
            int shortPeriod,
            int longPeriod,
            int bollPeriod,
            double bollStdDev,
            double limitPct
    ) {
        return run(bars, null, strategy, initialCapital, shortPeriod, longPeriod, bollPeriod, bollStdDev,
                ScoringConstants.MACD_FAST, ScoringConstants.MACD_SLOW, limitPct);
    }

    /** V2-MP-014：以 rawByDate 提供当日原始行情（成交执行价）。缺 raw 当日跳过成交。 */
    public static BacktestMetrics run(
            List<MarketData> bars,
            Map<LocalDate, MarketPriceRaw> rawByDate,
            StrategySignalType strategy,
            double initialCapital,
            int shortPeriod,
            int longPeriod,
            int bollPeriod,
            double bollStdDev
    ) {
        return run(bars, rawByDate, strategy, initialCapital, shortPeriod, longPeriod, bollPeriod, bollStdDev,
                ScoringConstants.MACD_FAST, ScoringConstants.MACD_SLOW, DEFAULT_LIMIT_PCT);
    }

    public static BacktestMetrics run(
            List<MarketData> bars,
            Map<LocalDate, MarketPriceRaw> rawByDate,
            StrategySignalType strategy,
            double initialCapital,
            int shortPeriod,
            int longPeriod,
            int bollPeriod,
            double bollStdDev,
            double limitPct
    ) {
        return run(bars, rawByDate, strategy, initialCapital, shortPeriod, longPeriod, bollPeriod, bollStdDev,
                ScoringConstants.MACD_FAST, ScoringConstants.MACD_SLOW, limitPct);
    }

    public static BacktestMetrics run(
            List<MarketData> bars,
            Map<LocalDate, MarketPriceRaw> rawByDate,
            StrategySignalType strategy,
            double initialCapital,
            int shortPeriod,
            int longPeriod,
            int bollPeriod,
            double bollStdDev,
            int macdFast,
            int macdSlow,
            double limitPct
    ) {
        // 过滤缺收盘价/收盘价非正的 K 线：避免把 null 当 0 纳入均线/MACD，污染技术信号
        List<MarketData> clean = (bars == null || bars.isEmpty())
                ? bars
                : bars.stream()
                        .filter(b -> b.getClosePrice() != null && b.getClosePrice().doubleValue() > 0)
                        .toList();
        if (clean == null || clean.size() < 30) {
            throw new IllegalArgumentException("K 线数据不足，至少需要 30 根");
        }

        double[] close = clean.stream()
                .mapToDouble(b -> b.getClosePrice().doubleValue())
                .toArray();
        TechnicalIndicators.MacdResult macd = TechnicalIndicators.macd(close, macdFast, macdSlow, ScoringConstants.MACD_SIGNAL);

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

        for (int i = 1; i < clean.size(); i++) {
            MarketData bar = clean.get(i);
            MarketData prevBar = clean.get(i - 1);
            double markPrice = close[i];
            // 信号基于前一交易日收盘数据（qfq，SIGNAL_FEATURE），在当日开盘成交（避免同日收盘价成交的未来函数）
            String signal = detectSignal(strategy, close, macd, i - 1, shortPeriod, longPeriod, bollPeriod, bollStdDev);

            // V2-MP-014：成交执行价只读 market_price_raw 原始开盘价；缺 raw 或零量（停牌）当日跳过成交，禁止 qfq 兜底。
            MarketPriceRaw raw = rawByDate != null ? rawByDate.get(bar.getTradeDate()) : null;
            double execPrice = Double.NaN;
            if (raw != null && raw.getOpenPrice() != null && raw.getOpenPrice().doubleValue() > 0
                    && (raw.getVolume() == null || raw.getVolume() > 0)) {
                execPrice = raw.getOpenPrice().doubleValue();
            }
            MarketPriceRaw prevRaw = rawByDate != null ? rawByDate.get(prevBar.getTradeDate()) : null;
            double preClose = resolvePreCloseExec(raw, prevRaw, bar, prevBar);

            if ("buy".equals(signal) && position == 0 && cash > 0
                    && !Double.isNaN(execPrice)
                    && !isLimitUpAtExecOpen(execPrice, preClose, limitPct)) {
                double buyPrice = execPrice * (1 + SLIPPAGE);
                // 买入可负担股数须预留佣金，否则 cash -= amount + commission 可能使现金为负
                int volume = (int) (cash / (buyPrice * (1 + COMMISSION)) / 100) * 100;
                if (volume >= 100) {
                    double amount = buyPrice * volume;
                    double commission = amount * COMMISSION;
                    cash -= amount + commission;
                    position = volume;
                    // 持仓成本基线含买入佣金，使卖出 pnl 直接反映净盈亏（否则胜率偏乐观）。
                    avgCost = buyPrice * (1 + COMMISSION);
                    trades.add(new TradeRecord(
                            bar.getTradeDate().toString(), "buy", buyPrice, volume, amount, commission, cash, cash + position * markPrice
                    ));
                    lastBuyBarIndex = i;
                }
            } else if ("sell".equals(signal) && position > 0 && i > lastBuyBarIndex
                    && !Double.isNaN(execPrice)
                    && !isLimitDownAtExecOpen(execPrice, preClose, limitPct)) {
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
    public static double resolvePreClose(MarketData bar, MarketData prevBar) {
        if (bar.getPreClose() != null && bar.getPreClose().doubleValue() > 0) {
            return bar.getPreClose().doubleValue();
        }
        if (prevBar != null
                && prevBar.getClosePrice() != null
                && prevBar.getClosePrice().doubleValue() > 0) {
            return prevBar.getClosePrice().doubleValue();
        }
        return 0;
    }

    /**
     * V2-MP-014：成交口径前收。优先 raw 实际前收，其次 raw 上一交易日收盘（真实成交价），
     * 最后 qfq 复权前收仅作参考——前收只用于涨跌停判定，不参与成交定价。
     */
    public static double resolvePreCloseExec(
            MarketPriceRaw raw, MarketPriceRaw prevRaw, MarketData bar, MarketData prevBar) {
        if (raw != null && raw.getPreClose() != null && raw.getPreClose().doubleValue() > 0) {
            return raw.getPreClose().doubleValue();
        }
        if (prevRaw != null && prevRaw.getClosePrice() != null
                && prevRaw.getClosePrice().doubleValue() > 0) {
            return prevRaw.getClosePrice().doubleValue();
        }
        return resolvePreClose(bar, prevBar);
    }

    /**
     * 涨停开盘判定（成交口径）：对 raw 原始开盘价判定封板，避免用 qfq 复权开盘价误判。
     */
    public static boolean isLimitUpAtExecOpen(double execOpen, double preClose, double limitPct) {
        if (Double.isNaN(execOpen) || execOpen <= 0 || preClose <= 0) {
            return false;
        }
        double limitUpPrice = preClose * (1 + limitPct);
        return execOpen >= limitUpPrice * (1 - LIMIT_PRICE_TOLERANCE)
                && execOpen <= limitUpPrice * (1 + LIMIT_PRICE_TOLERANCE);
    }

    /**
     * 跌停开盘判定（成交口径）：对 raw 原始开盘价判定封板。
     */
    public static boolean isLimitDownAtExecOpen(double execOpen, double preClose, double limitPct) {
        if (Double.isNaN(execOpen) || execOpen <= 0 || preClose <= 0) {
            return false;
        }
        double limitDownPrice = preClose * (1 - limitPct);
        return execOpen <= limitDownPrice * (1 + LIMIT_PRICE_TOLERANCE)
                && execOpen >= limitDownPrice * (1 - LIMIT_PRICE_TOLERANCE);
    }

    /**
     * 涨停开盘：开盘价落在涨停价容差带内，买单无法成交（A 股封板买入排队）。
     * 口径：涨停价 = 前收 × (1 + limitPct)；仅当开盘价贴近涨停价时视为封板，避免把异常跳空误判为涨停。
     */
    public static boolean isLimitUpAtOpen(MarketData bar, double preClose, double limitPct) {
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
    public static boolean isLimitDownAtOpen(MarketData bar, double preClose, double limitPct) {
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

    /**
     * 涨停收盘：收盘价落在涨停价容差带内，信号日视为封板，次日买入难以成交。
     */
    public static boolean isLimitUpAtClose(MarketData bar, double preClose, double limitPct) {
        if (preClose <= 0 || bar.getClosePrice() == null) {
            return false;
        }
        double close = bar.getClosePrice().doubleValue();
        if (close <= 0) {
            return false;
        }
        double limitUpPrice = preClose * (1 + limitPct);
        return close >= limitUpPrice * (1 - LIMIT_PRICE_TOLERANCE)
                && close <= limitUpPrice * (1 + LIMIT_PRICE_TOLERANCE);
    }

    /**
     * 跌停收盘：收盘价落在跌停价容差带内，卖单无法按收盘价成交。
     */
    public static boolean isLimitDownAtClose(MarketData bar, double preClose, double limitPct) {
        if (preClose <= 0 || bar.getClosePrice() == null) {
            return false;
        }
        double close = bar.getClosePrice().doubleValue();
        if (close <= 0) {
            return false;
        }
        double limitDownPrice = preClose * (1 - limitPct);
        return close <= limitDownPrice * (1 + LIMIT_PRICE_TOLERANCE)
                && close >= limitDownPrice * (1 - LIMIT_PRICE_TOLERANCE);
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
