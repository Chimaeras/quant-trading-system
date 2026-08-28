package com.quant.service.backtest;

import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.research.ResearchConvention;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyBacktestEngineTest {

    @Test
    void enforcesTPlusOneBeforeSell() {
        List<StrategyBacktestEngine.TradeRecord> trades = StrategyBacktestEngine.run(
                macdOscillationSeries(),
                StrategySignalType.MACD,
                100_000,
                2,
                5,
                20,
                2.0
        ).trades();

        for (int i = 0; i < trades.size(); i++) {
            if (!"buy".equals(trades.get(i).action())) {
                continue;
            }
            LocalDate buyDate = LocalDate.parse(trades.get(i).tradeDate());
            for (int j = i + 1; j < trades.size(); j++) {
                if ("sell".equals(trades.get(j).action())) {
                    LocalDate sellDate = LocalDate.parse(trades.get(j).tradeDate());
                    assertThat(sellDate).isAfter(buyDate);
                    break;
                }
            }
        }
    }

    @Test
    void buyPriceMatchesExecutionDayOpenNotClose() {
        List<MarketData> bars = macdOscillationSeries();
        for (int i = 0; i < bars.size(); i++) {
            // 开盘贴近前收（真实 A 股日内正常跳动），否则 30+i 的虚假跳空远超涨停会被拒买，无法验证「按开盘成交」。
            double prevClose = i > 0 ? bars.get(i - 1).getClosePrice().doubleValue() : bars.get(i).getClosePrice().doubleValue();
            bars.get(i).setOpenPrice(BigDecimal.valueOf(prevClose));
        }
        // V2-MP-014：成交执行价只读 market_price_raw 原始开盘价，须提供 rawByDate 才能成交
        Map<LocalDate, MarketPriceRaw> rawByDate = rawFromBars(bars);

        StrategyBacktestEngine.BacktestMetrics metrics = StrategyBacktestEngine.run(
                bars,
                rawByDate,
                StrategySignalType.MACD,
                100_000,
                2,
                5,
                20,
                2.0
        );

        List<StrategyBacktestEngine.TradeRecord> buys = metrics.trades().stream()
                .filter(t -> "buy".equals(t.action()))
                .toList();
        assertThat(buys).isNotEmpty();

        for (StrategyBacktestEngine.TradeRecord buy : buys) {
            LocalDate tradeDate = LocalDate.parse(buy.tradeDate());
            MarketData bar = bars.stream()
                    .filter(b -> b.getTradeDate().equals(tradeDate))
                    .findFirst()
                    .orElseThrow();
            double open = bar.getOpenPrice().doubleValue();
            double close = bar.getClosePrice().doubleValue();
            double buyWithSlippage = open * (1 + ResearchConvention.DEFAULT_SLIPPAGE_RATE);
            assertThat(buy.price()).isCloseTo(buyWithSlippage, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(buy.price()).isNotEqualTo(close);
        }
    }

    @Test
    void macdSignalUsesHistogramNotDeaMixture() {
        double[] close = new double[40];
        for (int i = 0; i < close.length; i++) {
            close[i] = 10 + Math.sin(i / 3.0) * 2;
        }
        var macd = com.quant.service.scoring.TechnicalIndicators.macd(close);

        // 旧逻辑 hist = macd - dea 会把柱线压到错误区间；修复后应与柱线零轴穿越一致
        boolean foundCross = false;
        for (int i = 2; i < close.length; i++) {
            double histPrev = macd.macd()[i - 1];
            double histCurr = macd.macd()[i];
            if (histPrev <= 0 && histCurr > 0) {
                foundCross = true;
                break;
            }
        }
        assertThat(foundCross).isTrue();
    }

    @Test
    void executionAssumptionsDocumentsTradingModel() {
        var assumptions = StrategyBacktestEngine.executionAssumptions();
        assertThat(assumptions.get("t_plus_one_sell")).isEqualTo(true);
        assertThat(assumptions.get("limit_up_entry_blocked")).isEqualTo(true);
        assertThat(assumptions.get("entry")).isEqualTo("SAME_DAY_OPEN");
    }

    @Test
    void costConstantsMatchResearchConvention() {
        var assumptions = StrategyBacktestEngine.executionAssumptions();
        assertThat(assumptions.get("commission_rate"))
                .isEqualTo(ResearchConvention.DEFAULT_COMMISSION_RATE);
        assertThat(assumptions.get("stamp_duty_rate"))
                .isEqualTo(ResearchConvention.DEFAULT_SELL_TAX_RATE);
        assertThat(assumptions.get("slippage_rate_each_side"))
                .isEqualTo(ResearchConvention.DEFAULT_SLIPPAGE_RATE);
        assertThat(String.valueOf(assumptions.get("costs_source")))
                .contains(ResearchConvention.CONVENTION_VERSION);
    }

    private static List<MarketData> macdOscillationSeries() {
        List<MarketData> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 2);
        for (int i = 0; i < 40; i++) {
            double close = 10 + Math.sin(i / 3.0) * 2;
            MarketData bar = new MarketData();
            bar.setTradeDate(start.plusDays(i));
            bar.setOpenPrice(BigDecimal.valueOf(close));
            bar.setClosePrice(BigDecimal.valueOf(close));
            bar.setHighPrice(BigDecimal.valueOf(close + 0.5));
            bar.setLowPrice(BigDecimal.valueOf(close - 0.5));
            bars.add(bar);
        }
        return bars;
    }

    @Test
    void detectsLimitUpAndDownAtOpen() {
        MarketData bar = new MarketData();
        bar.setPreClose(BigDecimal.valueOf(10.0));

        bar.setOpenPrice(BigDecimal.valueOf(11.0));
        assertThat(StrategyBacktestEngine.isLimitUpAtOpen(bar, 10.0, 0.10)).isTrue();
        assertThat(StrategyBacktestEngine.isLimitDownAtOpen(bar, 10.0, 0.10)).isFalse();

        bar.setOpenPrice(BigDecimal.valueOf(9.0));
        assertThat(StrategyBacktestEngine.isLimitDownAtOpen(bar, 10.0, 0.10)).isTrue();
        assertThat(StrategyBacktestEngine.isLimitUpAtOpen(bar, 10.0, 0.10)).isFalse();

        bar.setOpenPrice(BigDecimal.valueOf(10.5));
        assertThat(StrategyBacktestEngine.isLimitUpAtOpen(bar, 10.0, 0.10)).isFalse();
        assertThat(StrategyBacktestEngine.isLimitDownAtOpen(bar, 10.0, 0.10)).isFalse();
    }

    @Test
    void execLimitUpBlocksBuyWhenOpenBeyondLimit() {
        // 成交口径：开盘价超过涨停价（脏数据/除权日 preClose 未更新）必须判为不可买入，
        // 不能因上界容差而放行买单。
        assertThat(StrategyBacktestEngine.isLimitUpAtExecOpen(11.5, 10.0, 0.10)).isTrue();
        assertThat(StrategyBacktestEngine.isLimitUpAtExecOpen(11.0, 10.0, 0.10)).isTrue();
        assertThat(StrategyBacktestEngine.isLimitUpAtExecOpen(10.5, 10.0, 0.10)).isFalse();
    }

    @Test
    void execLimitDownBlocksSellWhenOpenBeyondLimit() {
        // 成交口径：开盘价低于跌停价（脏数据/除权日 preClose 未更新）必须判为不可卖出，
        // 不能因下界容差而放行卖单。
        assertThat(StrategyBacktestEngine.isLimitDownAtExecOpen(8.5, 10.0, 0.10)).isTrue();
        assertThat(StrategyBacktestEngine.isLimitDownAtExecOpen(9.0, 10.0, 0.10)).isTrue();
        assertThat(StrategyBacktestEngine.isLimitDownAtExecOpen(9.5, 10.0, 0.10)).isFalse();
    }

    @Test
    void detectsLimitUpAndLimitDownAtClose() {
        MarketData bar = new MarketData();
        bar.setClosePrice(BigDecimal.valueOf(11.0));
        assertThat(StrategyBacktestEngine.isLimitUpAtClose(bar, 10.0, 0.10)).isTrue();
        assertThat(StrategyBacktestEngine.isLimitDownAtClose(bar, 10.0, 0.10)).isFalse();

        bar.setClosePrice(BigDecimal.valueOf(9.0));
        assertThat(StrategyBacktestEngine.isLimitDownAtClose(bar, 10.0, 0.10)).isTrue();
        assertThat(StrategyBacktestEngine.isLimitUpAtClose(bar, 10.0, 0.10)).isFalse();

        bar.setClosePrice(BigDecimal.valueOf(10.5));
        assertThat(StrategyBacktestEngine.isLimitUpAtClose(bar, 10.0, 0.10)).isFalse();
        assertThat(StrategyBacktestEngine.isLimitDownAtClose(bar, 10.0, 0.10)).isFalse();
    }

    @Test
    void skipsBuyWhenLimitUpAtOpen() {
        List<MarketData> bars = maCrossGoldenCrossSeries();
        long normalBuys = StrategyBacktestEngine.run(
                copyBars(bars), rawFromBars(bars), StrategySignalType.MA_CROSS, 100_000, 5, 20, 20, 2.0
        ).trades().stream().filter(t -> "buy".equals(t.action())).count();
        assertThat(normalBuys).isGreaterThan(0);

        List<MarketData> limited = copyBars(bars);
        MarketData execBar = limited.get(31);
        double preClose = limited.get(30).getClosePrice().doubleValue();
        execBar.setPreClose(BigDecimal.valueOf(preClose));
        execBar.setOpenPrice(BigDecimal.valueOf(preClose * 1.10));

        Map<LocalDate, MarketPriceRaw> limitedRaw = rawFromBars(limited);
        // V2-MP-014：成交执行价读 raw 开盘价；同步把封板日的 raw 开盘/前收设为涨停，
        // 否则引擎读到的 raw 开盘仍是原值，不会触发 isLimitUpAtExecOpen。
        MarketPriceRaw execRaw = limitedRaw.get(execBar.getTradeDate());
        execRaw.setPreClose(BigDecimal.valueOf(preClose));
        execRaw.setOpenPrice(BigDecimal.valueOf(preClose * 1.10));

        long limitedBuys = StrategyBacktestEngine.run(
                limited, limitedRaw, StrategySignalType.MA_CROSS, 100_000, 5, 20, 20, 2.0
        ).trades().stream().filter(t -> "buy".equals(t.action())).count();
        assertThat(limitedBuys).isLessThan(normalBuys);
    }

    @Test
    void skipsSellWhenLimitDownAtOpen() {
        List<MarketData> bars = macdOscillationSeries();
        var baseline = StrategyBacktestEngine.run(
                copyBars(bars), rawFromBars(bars), StrategySignalType.MACD, 100_000, 2, 5, 20, 2.0
        );
        long normalSells = baseline.trades().stream().filter(t -> "sell".equals(t.action())).count();
        assertThat(normalSells).isGreaterThan(0);

        List<MarketData> limited = copyBars(bars);
        Map<LocalDate, MarketPriceRaw> limitedRaw = rawFromBars(limited);
        for (StrategyBacktestEngine.TradeRecord trade : baseline.trades()) {
            if (!"sell".equals(trade.action())) {
                continue;
            }
            int idx = indexOf(limited, LocalDate.parse(trade.tradeDate()));
            double preClose = limited.get(idx - 1).getClosePrice().doubleValue();
            MarketData execBar = limited.get(idx);
            execBar.setPreClose(BigDecimal.valueOf(preClose));
            execBar.setOpenPrice(BigDecimal.valueOf(preClose * 0.90));
            // V2-MP-014：同步 raw 原始开盘价为跌停，引擎才能识别 isLimitDownAtExecOpen
            MarketPriceRaw execRaw = limitedRaw.get(execBar.getTradeDate());
            execRaw.setPreClose(BigDecimal.valueOf(preClose));
            execRaw.setOpenPrice(BigDecimal.valueOf(preClose * 0.90));
            break;
        }

        long limitedSells = StrategyBacktestEngine.run(
                limited, limitedRaw, StrategySignalType.MACD, 100_000, 2, 5, 20, 2.0
        ).trades().stream().filter(t -> "sell".equals(t.action())).count();
        assertThat(limitedSells).isLessThan(normalSells);
    }

    private static List<MarketData> copyBars(List<MarketData> source) {
        List<MarketData> copy = new ArrayList<>();
        for (MarketData bar : source) {
            MarketData c = new MarketData();
            c.setTradeDate(bar.getTradeDate());
            c.setOpenPrice(bar.getOpenPrice());
            c.setClosePrice(bar.getClosePrice());
            c.setHighPrice(bar.getHighPrice());
            c.setLowPrice(bar.getLowPrice());
            c.setPreClose(bar.getPreClose());
            copy.add(c);
        }
        return copy;
    }

    private static int indexOf(List<MarketData> bars, LocalDate date) {
        for (int i = 0; i < bars.size(); i++) {
            if (bars.get(i).getTradeDate().equals(date)) {
                return i;
            }
        }
        throw new IllegalArgumentException("date not found: " + date);
    }

    /**
     * V2-MP-014 成交口径：引擎只读 market_price_raw 原始开盘价作为成交执行价，
     * 缺 raw 当日跳过成交。本工具按 bars 的开盘价构造一份原始行情，使回测能正常成交。
     * preClose 默认取前一根 bar 收盘（首根用自身开盘），volume 默认 10000 避免被判停牌。
     */
    private static Map<LocalDate, MarketPriceRaw> rawFromBars(List<MarketData> bars) {
        Map<LocalDate, MarketPriceRaw> rawByDate = new LinkedHashMap<>();
        for (int i = 0; i < bars.size(); i++) {
            MarketData bar = bars.get(i);
            MarketPriceRaw raw = new MarketPriceRaw();
            raw.setTradeDate(bar.getTradeDate());
            raw.setOpenPrice(bar.getOpenPrice());
            raw.setClosePrice(bar.getClosePrice());
            raw.setHighPrice(bar.getHighPrice());
            raw.setLowPrice(bar.getLowPrice());
            BigDecimal preClose = (i > 0 && bars.get(i - 1).getClosePrice() != null)
                    ? bars.get(i - 1).getClosePrice()
                    : bar.getOpenPrice();
            raw.setPreClose(preClose);
            raw.setVolume(10_000L);
            rawByDate.put(bar.getTradeDate(), raw);
        }
        return rawByDate;
    }

    /** 前 30 日横盘、第 31 日起温和放量拉升触发 MA 金叉，第 32 日开盘买入（各日涨跌幅贴合 A 股 ±10% 限制） */
    private static List<MarketData> maCrossGoldenCrossSeries() {
        List<MarketData> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 2);
        // 0-29 横盘 10.0；30 起温和上涨（单日 ≤6% 且低于涨停），使 MA5 在 index30 上穿 MA20。
        double[] closes = {
                10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0,
                10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0,
                10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0,
                10.6, 11.2, 11.8, 12.4, 13.0
        };
        for (int i = 0; i < 35; i++) {
            double close = closes[i];
            MarketData bar = new MarketData();
            bar.setTradeDate(start.plusDays(i));
            bar.setOpenPrice(BigDecimal.valueOf(close));
            bar.setClosePrice(BigDecimal.valueOf(close));
            bar.setHighPrice(BigDecimal.valueOf(close));
            bar.setLowPrice(BigDecimal.valueOf(close));
            bars.add(bar);
        }
        return bars;
    }

    /**
     * 买入可负担股数须预留佣金：资金恰好可买 200 股（不含佣金）时，
     * 修复前会因未预留佣金导致 cashAfter 为负，修复后仅买 100 股并保持现金非负。
     */
    @Test
    void buyNeverMakesCashNegative() {
        List<MarketData> bars = maCrossGoldenCrossSeries();
        double openAtBuy = bars.get(31).getOpenPrice().doubleValue();
        double buyPrice = openAtBuy * (1 + ResearchConvention.DEFAULT_SLIPPAGE_RATE);
        double capital = Math.ceil(200 * buyPrice); // 恰好 200 股，不留佣金余量

        List<StrategyBacktestEngine.TradeRecord> trades = StrategyBacktestEngine.run(
                copyBars(bars), rawFromBars(bars), StrategySignalType.MA_CROSS, capital, 5, 20, 20, 2.0
        ).trades();

        List<StrategyBacktestEngine.TradeRecord> buys = trades.stream()
                .filter(t -> "buy".equals(t.action()))
                .toList();
        assertThat(buys).isNotEmpty();
        for (StrategyBacktestEngine.TradeRecord buy : buys) {
            assertThat(buy.cashAfter()).isGreaterThanOrEqualTo(0.0);
        }
    }

    @Test
    void resolvePreCloseReturnsZeroWhenPrevBarMissing() {
        MarketData bar = new MarketData();
        bar.setOpenPrice(BigDecimal.TEN);
        assertThat(StrategyBacktestEngine.resolvePreClose(bar, null)).isZero();
    }

    @Test
    void detectsStMainBoardFivePctLimitAtCloseAndOneWordBoard() {
        // 主板 ST 涨跌停 5%：10.50 落在 10×1.05 容差带内 → 涨停收盘
        MarketData limitUpClose = new MarketData();
        limitUpClose.setClosePrice(BigDecimal.valueOf(10.50));
        assertThat(StrategyBacktestEngine.isLimitUpAtClose(limitUpClose, 10.0, 0.05)).isTrue();
        assertThat(StrategyBacktestEngine.isLimitDownAtClose(limitUpClose, 10.0, 0.05)).isFalse();

        // 9.50 落在 10×0.95 容差带内 → 跌停收盘
        MarketData limitDownClose = new MarketData();
        limitDownClose.setClosePrice(BigDecimal.valueOf(9.50));
        assertThat(StrategyBacktestEngine.isLimitDownAtClose(limitDownClose, 10.0, 0.05)).isTrue();
        assertThat(StrategyBacktestEngine.isLimitUpAtClose(limitDownClose, 10.0, 0.05)).isFalse();

        // 10.4（+4%）未达 5% 涨停
        MarketData near = new MarketData();
        near.setClosePrice(BigDecimal.valueOf(10.40));
        assertThat(StrategyBacktestEngine.isLimitUpAtClose(near, 10.0, 0.05)).isFalse();

        // 一字板：开盘=收盘=涨停价，开盘与收盘均判涨停
        MarketData oneWord = new MarketData();
        oneWord.setOpenPrice(BigDecimal.valueOf(10.50));
        oneWord.setClosePrice(BigDecimal.valueOf(10.50));
        assertThat(StrategyBacktestEngine.isLimitUpAtOpen(oneWord, 10.0, 0.05)).isTrue();
        assertThat(StrategyBacktestEngine.isLimitUpAtClose(oneWord, 10.0, 0.05)).isTrue();
    }

    @Test
    void stLimitPctFlowsThroughLimitDetection() {
        // 主板 ST：resolveLimitPct=5%，10.50 收盘触发涨停
        double stMainBoardPct = TradingLimitRules.resolveLimitPct("000001", "SZSE", "*ST测试");
        assertThat(stMainBoardPct).isEqualTo(0.05);
        MarketData limitUp = new MarketData();
        limitUp.setClosePrice(BigDecimal.valueOf(10.50));
        assertThat(StrategyBacktestEngine.isLimitUpAtClose(limitUp, 10.0, stMainBoardPct)).isTrue();

        // 注册制创业板 ST：resolveLimitPct=20%，5% 涨幅不算涨停
        double stChiNextPct = TradingLimitRules.resolveLimitPct("301578", "SZSE", "*ST创业板");
        assertThat(stChiNextPct).isEqualTo(0.20);
        MarketData chiNextUp = new MarketData();
        chiNextUp.setClosePrice(BigDecimal.valueOf(10.50));
        assertThat(StrategyBacktestEngine.isLimitUpAtClose(chiNextUp, 10.0, stChiNextPct)).isFalse();
        // 20% 涨幅才算涨停
        MarketData chiNextLimit = new MarketData();
        chiNextLimit.setClosePrice(BigDecimal.valueOf(12.00));
        assertThat(StrategyBacktestEngine.isLimitUpAtClose(chiNextLimit, 10.0, stChiNextPct)).isTrue();
    }
}
