package com.quant.service;

import com.quant.entity.MarketData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalDataServiceTest {

    @Test
    void acceptsValidTradingAndSuspendedBars() {
        MarketData trading = bar("10.10", "10.50", "9.90", "10.30", 1000L, "10300");
        MarketData suspended = bar("10.00", "10.00", "10.00", "10.00", 0L, "0");

        assertThat(HistoricalDataService.isValidDailyBar(trading)).isTrue();
        assertThat(HistoricalDataService.isValidDailyBar(suspended)).isTrue();
    }

    @Test
    void rejectsNonPositiveOrIncompleteOhlc() {
        MarketData zeroOpen = bar("0", "10.50", "9.90", "10.30", 1000L, "10300");
        MarketData missingClose = bar("10.10", "10.50", "9.90", null, 1000L, "10300");

        assertThat(HistoricalDataService.isValidDailyBar(zeroOpen)).isFalse();
        assertThat(HistoricalDataService.isValidDailyBar(missingClose)).isFalse();
    }

    @Test
    void rejectsInconsistentPriceRange() {
        MarketData highBelowClose = bar("10.10", "10.20", "9.90", "10.30", 1000L, "10300");
        MarketData lowAboveOpen = bar("10.10", "10.50", "10.20", "10.30", 1000L, "10300");

        assertThat(HistoricalDataService.isValidDailyBar(highBelowClose)).isFalse();
        assertThat(HistoricalDataService.isValidDailyBar(lowAboveOpen)).isFalse();
    }

    @Test
    void rejectsNegativeVolumeOrAmount() {
        MarketData negativeVolume = bar("10.10", "10.50", "9.90", "10.30", -1L, "10300");
        MarketData negativeAmount = bar("10.10", "10.50", "9.90", "10.30", 1000L, "-1");

        assertThat(HistoricalDataService.isValidDailyBar(negativeVolume)).isFalse();
        assertThat(HistoricalDataService.isValidDailyBar(negativeAmount)).isFalse();
    }

    private static MarketData bar(
            String open,
            String high,
            String low,
            String close,
            Long volume,
            String amount
    ) {
        MarketData bar = new MarketData();
        bar.setTradeDate(LocalDate.of(2026, 6, 30));
        bar.setOpenPrice(decimal(open));
        bar.setHighPrice(decimal(high));
        bar.setLowPrice(decimal(low));
        bar.setClosePrice(decimal(close));
        bar.setVolume(volume);
        bar.setAmount(decimal(amount));
        return bar;
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
