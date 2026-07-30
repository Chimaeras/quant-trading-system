package com.quant.service;

import com.quant.entity.MarketPriceRaw;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QP-003b：不复权 kline 行解析的纯函数测试（不触网）。
 */
class HistoricalDataServiceRawParseTest {

    @Test
    void parseRawKlineLineMapsFields() {
        // 格式: 日期,开,收,高,低,成交量,成交额,振幅,涨跌幅,涨跌额,换手率
        String line = "2025-01-02,9.50,10.20,10.40,9.30,1000000,10200000.5,1.10,2.00,0.20,1.5";
        MarketPriceRaw r = HistoricalDataService.parseRawKlineLine("000001", "SZSE", line);

        assertThat(r).isNotNull();
        assertThat(r.getStockCode()).isEqualTo("000001");
        assertThat(r.getExchangeType()).isEqualTo("SZSE");
        assertThat(r.getTradeDate().toString()).isEqualTo("2025-01-02");
        assertThat(r.getOpenPrice()).isEqualByComparingTo(new BigDecimal("9.50"));
        assertThat(r.getClosePrice()).isEqualByComparingTo(new BigDecimal("10.20"));
        assertThat(r.getHighPrice()).isEqualByComparingTo(new BigDecimal("10.40"));
        assertThat(r.getLowPrice()).isEqualByComparingTo(new BigDecimal("9.30"));
        assertThat(r.getVolume()).isEqualTo(1000000L);
        assertThat(r.getAmount()).isEqualByComparingTo(new BigDecimal("10200000.5"));
        assertThat(r.getTurnoverRate()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(r.getSource()).isEqualTo("eastmoney_fqt0");
    }

    @Test
    void parseRawKlineLineRejectsInvalidBar() {
        // high < low → 无效
        String line = "2025-01-02,10.00,10.00,9.00,11.00,1000,10000,0,0,0,0.5";
        assertThat(HistoricalDataService.parseRawKlineLine("000001", "SZSE", line)).isNull();
    }

    @Test
    void parseRawKlineLineRejectsTooFewFields() {
        assertThat(HistoricalDataService.parseRawKlineLine("000001", "SZSE", "2025-01-02,10.00")).isNull();
    }

    @Test
    void parseRawKlineLineRejectsBadDate() {
        String line = "not-a-date,10.00,10.00,10.00,10.00,1000,10000,0,0,0,0.5";
        assertThat(HistoricalDataService.parseRawKlineLine("000001", "SZSE", line)).isNull();
    }

    @Test
    void rawSourceConstantIsFqt0() {
        assertThat(HistoricalDataService.RAW_PRICE_SOURCE).isEqualTo("eastmoney_fqt0");
    }
}
