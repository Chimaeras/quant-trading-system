package com.quant.service.backtest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradingLimitRulesTest {

    @Test
    void resolvesMainBoardChiNextStarStAndBse() {
        assertThat(TradingLimitRules.resolveLimitPct("000001", "SZSE", "平安银行"))
                .isEqualTo(TradingLimitRules.MAIN_BOARD_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("300001", "SZSE", "特锐德"))
                .isEqualTo(TradingLimitRules.CHINEXT_STAR_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("688001", "SSE", "华兴源创"))
                .isEqualTo(TradingLimitRules.CHINEXT_STAR_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("830001", "BSE", "测试北交所"))
                .isEqualTo(TradingLimitRules.BSE_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("000001", "SZSE", "*ST测试"))
                .isEqualTo(TradingLimitRules.ST_LIMIT_PCT);
    }
}
