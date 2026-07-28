package com.quant.service.backtest;

import com.quant.entity.MarketData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
            double open = 30 + i;
            bars.get(i).setOpenPrice(BigDecimal.valueOf(open));
        }

        StrategyBacktestEngine.BacktestMetrics metrics = StrategyBacktestEngine.run(
                bars,
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
            assertThat(buy.price()).isCloseTo(open * 1.001, org.assertj.core.data.Offset.offset(1e-6));
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
                copyBars(bars), StrategySignalType.MA_CROSS, 100_000, 5, 20, 20, 2.0
        ).trades().stream().filter(t -> "buy".equals(t.action())).count();
        assertThat(normalBuys).isGreaterThan(0);

        List<MarketData> limited = copyBars(bars);
        MarketData execBar = limited.get(31);
        double preClose = limited.get(30).getClosePrice().doubleValue();
        execBar.setPreClose(BigDecimal.valueOf(preClose));
        execBar.setOpenPrice(BigDecimal.valueOf(preClose * 1.10));

        long limitedBuys = StrategyBacktestEngine.run(
                limited, StrategySignalType.MA_CROSS, 100_000, 5, 20, 20, 2.0
        ).trades().stream().filter(t -> "buy".equals(t.action())).count();
        assertThat(limitedBuys).isLessThan(normalBuys);
    }

    @Test
    void skipsSellWhenLimitDownAtOpen() {
        List<MarketData> bars = macdOscillationSeries();
        var baseline = StrategyBacktestEngine.run(
                copyBars(bars), StrategySignalType.MACD, 100_000, 2, 5, 20, 2.0
        );
        long normalSells = baseline.trades().stream().filter(t -> "sell".equals(t.action())).count();
        assertThat(normalSells).isGreaterThan(0);

        List<MarketData> limited = copyBars(bars);
        for (StrategyBacktestEngine.TradeRecord trade : baseline.trades()) {
            if (!"sell".equals(trade.action())) {
                continue;
            }
            int idx = indexOf(limited, LocalDate.parse(trade.tradeDate()));
            double preClose = limited.get(idx - 1).getClosePrice().doubleValue();
            MarketData execBar = limited.get(idx);
            execBar.setPreClose(BigDecimal.valueOf(preClose));
            execBar.setOpenPrice(BigDecimal.valueOf(preClose * 0.90));
            break;
        }

        long limitedSells = StrategyBacktestEngine.run(
                limited, StrategySignalType.MACD, 100_000, 2, 5, 20, 2.0
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

    /** 前 30 日横盘、第 31 日放量拉升触发 MA 金叉，第 32 日开盘买入 */
    private static List<MarketData> maCrossGoldenCrossSeries() {
        List<MarketData> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 2);
        for (int i = 0; i < 35; i++) {
            double close = i < 30 ? 10.0 : 10.0 + (i - 29) * 40.0;
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
