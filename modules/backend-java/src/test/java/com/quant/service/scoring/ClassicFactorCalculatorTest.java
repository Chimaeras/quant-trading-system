package com.quant.service.scoring;

import com.quant.entity.MarketData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClassicFactorCalculatorTest {

    private final ClassicFactorCalculator calculator = new ClassicFactorCalculator();

    @Test
    void excludesStaleBarsWhenTradeDateMisaligned() {
        List<MarketData> bars = sampleBars(80, 10, 50_000_000, 0.02);
        LocalDate signalDate = bars.get(bars.size() - 1).getTradeDate().plusDays(10);

        var result = calculator.calculate(
                "000001",
                "SZSE",
                bars,
                1_000_000,
                0,
                key -> "测试股份",
                true,
                ClassicFactorCalculator.HORIZON_MEDIUM,
                signalDate
        );

        assertThat(result).isEmpty();
    }

    @Test
    void excludesStStockWhenExcludeStEnabled() {
        List<MarketData> bars = sampleBars(75, 10, 50_000_000);

        var result = calculator.calculate(
                "000001",
                "SZSE",
                bars,
                1_000_000,
                0,
                key -> "*ST测试",
                true
        );

        assertThat(result).isEmpty();
    }

    @Test
    void allowsStStockWhenExcludeStDisabled() {
        List<MarketData> bars = sampleBars(75, 10, 50_000_000);

        var result = calculator.calculate(
                "000001",
                "SZSE",
                bars,
                1_000_000,
                0,
                key -> "*ST测试",
                false
        );

        assertThat(result).isPresent();
    }

    @Test
    void excludesCandidateBelowMinimumAverageAmount() {
        List<MarketData> bars = sampleBars(75, 10, 500_000);

        var result = calculator.calculate(
                "000001",
                "SZSE",
                bars,
                1_000_000,
                0,
                key -> "测试股份",
                true
        );

        assertThat(result).isEmpty();
    }

    @Test
    void excludesCandidateWhenStockStatusSuspended() {
        List<MarketData> bars = sampleBars(75, 10, 50_000_000);

        var result = calculator.calculate(
                "000001",
                "SZSE",
                bars,
                1_000_000,
                0,
                key -> "测试股份",
                true,
                ClassicFactorCalculator.HORIZON_MEDIUM,
                null,
                0
        );

        assertThat(result).isEmpty();
    }

    @Test
    void excludesCandidateBelowMinimumTrendScore() {
        List<MarketData> bars = sampleBars(75, 10, 50_000_000);

        var result = calculator.calculate(
                "000001",
                "SZSE",
                bars,
                1_000_000,
                100,
                key -> "测试股份",
                true
        );

        assertThat(result).isEmpty();
    }

    @Test
    void shortHorizonProfileAllowsNonMaUptrendWhenOversoldRepairStrong() {
        List<MarketData> bars = sampleBars(75, 12, 50_000_000, -0.08);
        for (int i = bars.size() - 5; i < bars.size(); i++) {
            double c = 8.5 + (i - (bars.size() - 5)) * 0.15;
            bars.get(i).setClosePrice(java.math.BigDecimal.valueOf(c));
            bars.get(i).setAmount(java.math.BigDecimal.valueOf(80_000_000));
        }

        var medium = calculator.calculate(
                "000001", "SZSE", bars, 1_000_000, 0, key -> "测试股份", true,
                ClassicFactorCalculator.HORIZON_MEDIUM);
        var shortP = calculator.calculate(
                "000001", "SZSE", bars, 1_000_000, 0, key -> "测试股份", true,
                ClassicFactorCalculator.HORIZON_SHORT);

        assertThat(medium).isEmpty();
        assertThat(shortP).isPresent();
        assertThat(shortP.get().getReasons().get("horizon_profile"))
                .isEqualTo(ClassicFactorCalculator.HORIZON_SHORT);
        assertThat(shortP.get().getReasons()).containsKey("short_horizon_score");
    }

    @Test
    void excludesCandidateWithoutUptrend_mediumOnly() {
        List<MarketData> bars = sampleBars(75, 20, 50_000_000, -0.10);

        var result = calculator.calculate(
                "000001",
                "SZSE",
                bars,
                1_000_000,
                0,
                key -> "测试股份",
                true,
                ClassicFactorCalculator.HORIZON_MEDIUM
        );

        assertThat(result).isEmpty();
    }

    @Test
    void excludesZeroVolumeOnSignalDay() {
        List<MarketData> bars = sampleBars(75, 10, 50_000_000);
        bars.get(bars.size() - 1).setVolume(0L);

        var result = calculator.calculate(
                "000001",
                "SZSE",
                bars,
                1_000_000,
                0,
                key -> "测试股份",
                true
        );

        assertThat(result).isEmpty();
    }

    @Test
    void excludesLimitUpAtCloseOnSignalDay() {
        List<MarketData> bars = sampleBars(75, 10, 50_000_000);
        MarketData prev = bars.get(bars.size() - 2);
        MarketData signal = bars.get(bars.size() - 1);
        double preClose = prev.getClosePrice().doubleValue();
        signal.setClosePrice(BigDecimal.valueOf(preClose * 1.10));

        var result = calculator.calculate(
                "000001",
                "SZSE",
                bars,
                1_000_000,
                0,
                key -> "测试股份",
                true
        );

        assertThat(result).isEmpty();
    }

    private static List<MarketData> sampleBars(int count, double close, double amount) {
        return sampleBars(count, close, amount, 0.01);
    }

    private static List<MarketData> sampleBars(int count, double close, double amount, double dailyStep) {
        List<MarketData> bars = new ArrayList<>(count);
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < count; i++) {
            MarketData bar = new MarketData();
            bar.setStockCode("000001");
            bar.setExchangeType("SZSE");
            bar.setTradeDate(start.plusDays(i));
            bar.setClosePrice(BigDecimal.valueOf(close + i * dailyStep));
            bar.setAmount(BigDecimal.valueOf(amount));
            bar.setVolume(1_000_000L);
            bars.add(bar);
        }
        return bars;
    }
}
