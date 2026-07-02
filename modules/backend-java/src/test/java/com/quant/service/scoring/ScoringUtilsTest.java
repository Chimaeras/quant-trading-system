package com.quant.service.scoring;

import com.quant.entity.MarketData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringUtilsTest {

    @Test
    void explicitZeroVolumeOnlyWhenVolumeIsZeroNotNull() {
        MarketData zero = new MarketData();
        zero.setVolume(0L);
        MarketData missing = new MarketData();
        MarketData trading = new MarketData();
        trading.setVolume(1000L);

        assertThat(ScoringUtils.isExplicitZeroVolume(zero)).isTrue();
        assertThat(ScoringUtils.isExplicitZeroVolume(missing)).isFalse();
        assertThat(ScoringUtils.isExplicitZeroVolume(trading)).isFalse();
    }

    @Test
    void isSignalBarOnTradeDateRejectsStaleLastBar() {
        MarketData stale = new MarketData();
        stale.setTradeDate(LocalDate.of(2024, 3, 1));
        stale.setClosePrice(java.math.BigDecimal.TEN);
        assertThat(ScoringUtils.isSignalBarOnTradeDate(List.of(stale), LocalDate.of(2024, 6, 1)))
                .isFalse();
        assertThat(ScoringUtils.isSignalBarOnTradeDate(List.of(stale), LocalDate.of(2024, 3, 1)))
                .isTrue();
        assertThat(ScoringUtils.isSignalBarOnTradeDate(List.of(stale), null)).isTrue();
    }

    @Test
    void tradableForScoringRejectsZeroVolumeAndStWhenEnabled() {
        MarketData zeroVol = new MarketData();
        zeroVol.setVolume(0L);
        MarketData normal = new MarketData();
        normal.setVolume(1000L);

        assertThat(ScoringUtils.isTradableForScoring(zeroVol, "测试股份", true)).isFalse();
        assertThat(ScoringUtils.isTradableForScoring(normal, "*ST测试", true)).isFalse();
        assertThat(ScoringUtils.isTradableForScoring(normal, "*ST测试", false)).isTrue();
        assertThat(ScoringUtils.isTradableForScoring(normal, "测试股份", true)).isTrue();
    }

    @Test
    void tradableForScoringRejectsChiNext301LimitUpAtTwentyPercent() {
        MarketData prev = bar("301578", "SZSE", 10.0);
        MarketData limitUp20 = bar("301578", "SZSE", 12.0);
        MarketData notLimitUp = bar("301578", "SZSE", 11.0);

        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, limitUp20), "301578", "SZSE", "注册制创业板", true, true, true))
                .isFalse();
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, notLimitUp), "301578", "SZSE", "注册制创业板", true, true, true))
                .isTrue();
    }

    @Test
    void tradableForScoringRejectsLimitUpAtCloseAndHonorsOversoldLimitDownException() {
        MarketData prev = bar("000001", "SZSE", 10.0);
        MarketData limitUpClose = bar("000001", "SZSE", 11.0);
        MarketData limitDownClose = bar("000001", "SZSE", 9.0);
        MarketData normalClose = bar("000001", "SZSE", 10.5);

        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, limitUpClose), "000001", "SZSE", "测试股份", true, true, true))
                .isFalse();
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, limitDownClose), "000001", "SZSE", "测试股份", true, true, true))
                .isFalse();
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, limitDownClose), "000001", "SZSE", "测试股份", true, true, false))
                .isTrue();
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(prev, normalClose), "000001", "SZSE", "测试股份", true, true, true))
                .isTrue();
    }

    private static MarketData bar(String code, String exchange, double close) {
        MarketData signal = new MarketData();
        signal.setStockCode(code);
        signal.setExchangeType(exchange);
        signal.setClosePrice(java.math.BigDecimal.valueOf(close));
        signal.setVolume(1000L);
        return signal;
    }

    @Test
    void hasMinListingTenureHonorsThresholdAndMissingListDate() {
        LocalDate trade = LocalDate.of(2026, 6, 30);
        assertThat(ScoringUtils.hasMinListingTenure(null, trade, 60)).isTrue();
        assertThat(ScoringUtils.hasMinListingTenure(trade.minusDays(30), trade, 60)).isFalse();
        assertThat(ScoringUtils.hasMinListingTenure(trade.minusDays(90), trade, 60)).isTrue();
        assertThat(ScoringUtils.hasMinListingTenure(trade.plusDays(1), trade, 60)).isFalse();
    }

    @Test
    void tradabilitySnapshotReflectsOversoldLimitDownException() {
        Map<String, Object> normal = ScoringUtils.tradabilitySnapshot(true, true, 60);
        assertThat(normal.get("exclude_limit_down_at_close")).isEqualTo(true);
        assertThat(normal.get("min_listing_days")).isEqualTo(60);
        assertThat(normal.get("avg_amount20_basis"))
                .isEqualTo("MEAN_AMOUNT_LAST_20_NON_ZERO_VOLUME_DAYS");

        Map<String, Object> oversold = ScoringUtils.tradabilitySnapshot(true, false, 60);
        assertThat(oversold.get("exclude_limit_down_at_close")).isEqualTo(false);
    }

    @Test
    void tradabilitySnapshotDisclosesLookbackScopeWhenBulkFetchDisabled() {
        Map<String, Object> bulk = ScoringUtils.tradabilitySnapshot(true, true, 60, true);
        Map<String, Object> window = ScoringUtils.tradabilitySnapshot(true, true, 60, false);

        assertThat(bulk.get("listing_tenure_bar_scope")).isEqualTo("FULL_BULK_BARS_IN_LOOKBACK");
        assertThat(window.get("listing_tenure_bar_scope")).isEqualTo("LOOKBACK_WINDOW_EARLIEST_TRADE_DATE_ONLY");
        assertThat(window.get("listing_tenure_warning")).isNotNull();
        assertThat(window.get("bulk_fetch")).isEqualTo(false);
    }

    @Test
    void pickTopNByMinScoreAllowsFewerThanTopNWhenQualityGateFails() {
        List<Map<String, Object>> items = List.of(
                Map.of("stock_code", "A", "total_score", 80),
                Map.of("stock_code", "B", "total_score", 40),
                Map.of("stock_code", "C", "total_score", 60)
        );

        var out = ScoringUtils.pickTopNByMinScore(items, 55, 10, "total_score");

        assertThat(out).hasSize(2);
        assertThat(out.get(0).get("stock_code")).isEqualTo("A");
        assertThat(out.get(1).get("stock_code")).isEqualTo("C");
        assertThat(ScoringUtils.countByMinScore(items, 55, "total_score")).isEqualTo(2);
    }

    @Test
    void avgAmount20ExcludesZeroVolumeDaysFromLastTwentyTraded() {
        List<MarketData> bars = sampleAmountBars(25, 50_000_000);
        for (int i = 20; i < 25; i++) {
            bars.get(i).setVolume(0L);
            bars.get(i).setAmount(java.math.BigDecimal.ZERO);
        }

        assertThat(ScoringUtils.avgAmount20(bars)).isEqualTo(50_000_000.0);
        assertThat(ScoringUtils.passesMinAvgAmount(bars, 40_000_000)).isTrue();
    }

    @Test
    void passesMinAvgAmountHonorsTwentyDayAverageThreshold() {
        List<MarketData> low = sampleAmountBars(25, 500_000);
        List<MarketData> high = sampleAmountBars(25, 50_000_000);

        assertThat(ScoringUtils.passesMinAvgAmount(low, 1_000_000)).isFalse();
        assertThat(ScoringUtils.passesMinAvgAmount(high, 1_000_000)).isTrue();
        assertThat(ScoringUtils.passesMinAvgAmount(low, 0)).isTrue();
    }

    @Test
    void isActiveStockStatusAllowsNormalOrMissingOnly() {
        assertThat(ScoringUtils.isActiveStockStatus(1)).isTrue();
        assertThat(ScoringUtils.isActiveStockStatus(null)).isTrue();
        assertThat(ScoringUtils.isActiveStockStatus(0)).isFalse();
        assertThat(ScoringUtils.isActiveStockStatus(-1)).isFalse();
    }

    @Test
    void listingTenureBarsFallsBackWhenBulkBarsEmpty() {
        LocalDate earliest = LocalDate.of(2024, 1, 1);
        List<MarketData> out = ScoringUtils.listingTenureBars(List.of(), earliest);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getTradeDate()).isEqualTo(earliest);
    }

    @Test
    void listingTenureBarsUsesEarliestWhenBulkBarsMissing() {
        LocalDate trade = LocalDate.of(2024, 6, 1);
        LocalDate earliest = trade.minusDays(90);
        List<MarketData> proxy = ScoringUtils.listingTenureBars(null, earliest);
        assertThat(ScoringUtils.hasMinListingTenure(null, trade, 60, proxy)).isTrue();
        assertThat(ScoringUtils.hasMinListingTenure(null, trade, 60, null)).isTrue();
    }

    @Test
    void mapEarliestTradeDatesByKeyBuildsStockKeyIndex() {
        List<Object[]> rows = List.of(
                new Object[]{"000001", "SZSE", LocalDate.of(2010, 1, 1)},
                new Object[]{"000002", "SZSE", java.sql.Date.valueOf(LocalDate.of(2012, 3, 1))}
        );
        Map<String, LocalDate> map = ScoringUtils.mapEarliestTradeDatesByKey(rows);
        assertThat(map.get("000001|SZSE")).isEqualTo(LocalDate.of(2010, 1, 1));
        assertThat(map.get("000002|SZSE")).isEqualTo(LocalDate.of(2012, 3, 1));
    }

    @Test
    void hasMinListingTenureUsesEarliestBarWhenListDateMissing() {
        LocalDate trade = LocalDate.of(2024, 6, 1);
        List<MarketData> bars = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) {
            MarketData bar = new MarketData();
            bar.setTradeDate(trade.minusDays(79 - i));
            bars.add(bar);
        }
        assertThat(ScoringUtils.hasMinListingTenure(null, trade, 60, bars)).isTrue();
        List<MarketData> shortHistory = new java.util.ArrayList<>();
        for (int i = 0; i < 31; i++) {
            MarketData bar = new MarketData();
            bar.setTradeDate(trade.minusDays(30 - i));
            shortHistory.add(bar);
        }
        assertThat(ScoringUtils.hasMinListingTenure(null, trade, 60, shortHistory)).isFalse();
    }

    @Test
    void tradableForScoringRejectsSuspendedStatus() {
        MarketData normal = new MarketData();
        normal.setVolume(1000L);
        normal.setClosePrice(java.math.BigDecimal.TEN);
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(normal), "000001", "SZSE", "测试股份", 0, true, true, true)).isFalse();
        assertThat(ScoringUtils.isTradableForScoring(
                List.of(normal), "000001", "SZSE", "测试股份", 1, true, true, true)).isTrue();
    }

    private static List<MarketData> sampleAmountBars(int count, double amount) {
        List<MarketData> bars = new java.util.ArrayList<>(count);
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < count; i++) {
            MarketData bar = new MarketData();
            bar.setTradeDate(start.plusDays(i));
            bar.setAmount(java.math.BigDecimal.valueOf(amount));
            bar.setVolume(1000L);
            bars.add(bar);
        }
        return bars;
    }
}
