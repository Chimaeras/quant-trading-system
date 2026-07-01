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

    private static List<MarketData> sampleBars(int count, double close, double amount) {
        List<MarketData> bars = new ArrayList<>(count);
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < count; i++) {
            MarketData bar = new MarketData();
            bar.setStockCode("000001");
            bar.setExchangeType("SZSE");
            bar.setTradeDate(start.plusDays(i));
            bar.setClosePrice(BigDecimal.valueOf(close + i * 0.01));
            bar.setAmount(BigDecimal.valueOf(amount));
            bars.add(bar);
        }
        return bars;
    }
}
