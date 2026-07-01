package com.quant.service.scoring;

import com.quant.entity.MarketData;
import org.junit.jupiter.api.Test;

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
}
