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
        assertThat(TradingLimitRules.resolveLimitPct("301578", "SZSE", "注册制创业板"))
                .isEqualTo(TradingLimitRules.CHINEXT_STAR_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("302132", "SZSE", "注册制创业板B"))
                .isEqualTo(TradingLimitRules.CHINEXT_STAR_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("301578", "SZSE", "注册制创业板"))
                .isNotEqualTo(TradingLimitRules.MAIN_BOARD_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("688001", "SSE", "华兴源创"))
                .isEqualTo(TradingLimitRules.CHINEXT_STAR_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("689009", "SSE", "九号公司"))
                .isEqualTo(TradingLimitRules.CHINEXT_STAR_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("830001", "BSE", "测试北交所"))
                .isEqualTo(TradingLimitRules.BSE_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("920000", null, "新代码北交所"))
                .isEqualTo(TradingLimitRules.BSE_LIMIT_PCT);
        // 主板 ST/*ST 才适用 5%；注册制板块 ST 仍按板块幅度
        assertThat(TradingLimitRules.resolveLimitPct("000001", "SZSE", "*ST测试"))
                .isEqualTo(TradingLimitRules.ST_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("600004", "SSE", "ST测试"))
                .isEqualTo(TradingLimitRules.ST_LIMIT_PCT);
    }

    @Test
    void normalizeCodeIsDeterministicForPrefixedAndZeroPaddedForms() {
        assertThat(TradingLimitRules.normalizeCode("000001")).isEqualTo("000001");
        assertThat(TradingLimitRules.normalizeCode("SZ000001")).isEqualTo("000001");
        assertThat(TradingLimitRules.normalizeCode("sh600000")).isEqualTo("600000");
        assertThat(TradingLimitRules.normalizeCode("0000001")).isEqualTo("000001"); // 多余前导零 → 取末尾 6 位
        assertThat(TradingLimitRules.normalizeCode("300001")).isEqualTo("300001");
        assertThat(TradingLimitRules.normalizeCode(null)).isEmpty();
        assertThat(TradingLimitRules.normalizeCode("abc")).isEmpty();
        // 确定性代码参与板块推断：带前缀的创业板代码仍判 20%
        assertThat(TradingLimitRules.resolveLimitPct("SZ300001", "SZSE", "特锐德"))
                .isEqualTo(TradingLimitRules.CHINEXT_STAR_LIMIT_PCT);
    }

    @Test
    void stOnRegistrationBoardsKeepsBoardLimitNotFivePct() {
        // 创业板（300/301/302）ST 涨跌停仍为 20%
        assertThat(TradingLimitRules.resolveLimitPct("300001", "SZSE", "*ST创业板"))
                .isEqualTo(TradingLimitRules.CHINEXT_STAR_LIMIT_PCT);
        assertThat(TradingLimitRules.resolveLimitPct("301578", "SZSE", "ST创业板"))
                .isEqualTo(TradingLimitRules.CHINEXT_STAR_LIMIT_PCT);
        // 科创板（688/689）ST 涨跌停仍为 20%
        assertThat(TradingLimitRules.resolveLimitPct("688001", "SSE", "*ST科创板"))
                .isEqualTo(TradingLimitRules.CHINEXT_STAR_LIMIT_PCT);
        // 北交所 ST 涨跌停仍为 30%
        assertThat(TradingLimitRules.resolveLimitPct("830001", "BSE", "*ST北交所"))
                .isEqualTo(TradingLimitRules.BSE_LIMIT_PCT);
    }
}
