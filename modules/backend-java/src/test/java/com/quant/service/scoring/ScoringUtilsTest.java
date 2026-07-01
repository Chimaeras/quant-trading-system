package com.quant.service.scoring;

import com.quant.entity.MarketData;
import org.junit.jupiter.api.Test;

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
