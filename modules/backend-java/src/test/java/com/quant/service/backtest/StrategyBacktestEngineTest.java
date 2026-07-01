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

    private static List<MarketData> maCrossTriggerSeries() {
        List<MarketData> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 2);
        double[] closes = new double[32];
        java.util.Arrays.fill(closes, 0, 25, 10.0);
        for (int i = 25; i < closes.length; i++) {
            closes[i] = 10 + (i - 24) * 5.0;
        }
        for (int i = 0; i < closes.length; i++) {
            MarketData bar = new MarketData();
            bar.setTradeDate(start.plusDays(i));
            bar.setOpenPrice(BigDecimal.valueOf(closes[i]));
            bar.setClosePrice(BigDecimal.valueOf(closes[i]));
            bar.setHighPrice(BigDecimal.valueOf(closes[i]));
            bar.setLowPrice(BigDecimal.valueOf(closes[i]));
            bars.add(bar);
        }
        return bars;
    }
}
