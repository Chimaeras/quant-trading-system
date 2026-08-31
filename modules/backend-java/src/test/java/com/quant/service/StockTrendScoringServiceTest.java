package com.quant.service;

import com.quant.entity.MarketData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StockTrendScoringService 对重复交易日的防御性去重（P1-4 防护）。
 *
 * <p>虽 market_data 有 (stock_code, exchange_type, trade_date) 唯一键，但跨渠道/历史脏数据下
 * bars 仍可能同一天两条，导致 close[L-1]/close[L] 取到同一天两根而失真。必须在取末两根前去重。
 * 语义：保序，同一交易日保留最后一条。
 */
class StockTrendScoringServiceTest {

    @Test
    void dedupeKeepsLastOccurrencePerDayAndPreservesOrder() {
        MarketData d1a = bar(LocalDate.of(2026, 6, 29), "10.00", "10.50", "9.90", "10.30", 1000L);
        MarketData d2 = bar(LocalDate.of(2026, 6, 30), "10.20", "10.60", "10.00", "10.40", 2000L);
        MarketData d1b = bar(LocalDate.of(2026, 6, 29), "10.05", "10.55", "9.95", "10.35", 1500L); // 同天两条（脏数据）

        List<MarketData> out = StockTrendScoringService.dedupeByTradeDate(List.of(d1a, d2, d1b));

        assertThat(out).hasSize(2);
        // 顺序按首次出现的日期（29、30）
        assertThat(out.get(0).getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 29));
        // 同一天保留最后一条（d1b）
        assertThat(out.get(0).getClosePrice()).isEqualByComparingTo("10.35");
        assertThat(out.get(1).getClosePrice()).isEqualByComparingTo("10.40");
    }

    @Test
    void dedupeHandlesNullAndEmptyList() {
        assertThat(StockTrendScoringService.dedupeByTradeDate(null)).isEmpty();
        assertThat(StockTrendScoringService.dedupeByTradeDate(List.of())).isEmpty();
    }

    @Test
    void dedupeSkipsBarsWithNullTradeDate() {
        MarketData noDate = bar(null, "10.00", "10.50", "9.90", "10.30", 1000L);
        MarketData normal = bar(LocalDate.of(2026, 6, 30), "10.20", "10.60", "10.00", "10.40", 2000L);

        List<MarketData> out = StockTrendScoringService.dedupeByTradeDate(List.of(noDate, normal));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    private static MarketData bar(LocalDate date, String open, String high, String low, String close, Long volume) {
        MarketData bar = new MarketData();
        bar.setTradeDate(date);
        bar.setOpenPrice(new BigDecimal(open));
        bar.setHighPrice(new BigDecimal(high));
        bar.setLowPrice(new BigDecimal(low));
        bar.setClosePrice(new BigDecimal(close));
        bar.setVolume(volume);
        return bar;
    }
}
