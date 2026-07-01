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

        Map<String, Object> oversold = ScoringUtils.tradabilitySnapshot(true, false, 60);
        assertThat(oversold.get("exclude_limit_down_at_close")).isEqualTo(false);
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
}
