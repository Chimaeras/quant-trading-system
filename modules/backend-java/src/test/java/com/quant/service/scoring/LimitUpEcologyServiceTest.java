package com.quant.service.scoring;

import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.service.TradingCalendarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 涨停生态服务：涨停次日溢价统计的 preClose 回退正确性。
 */
@ExtendWith(MockitoExtension.class)
class LimitUpEcologyServiceTest {

    @Mock
    private MarketDataRepository marketDataRepository;
    @Mock
    private StockInfoRepository stockInfoRepository;
    @Mock
    private TradingCalendarService tradingCalendarService;

    private LimitUpEcologyService service() {
        return new LimitUpEcologyService(marketDataRepository, stockInfoRepository, tradingCalendarService);
    }

    private static MarketData bar(String code, String ex, LocalDate date, double close, BigDecimal preClose) {
        MarketData m = new MarketData();
        m.setStockCode(code);
        m.setExchangeType(ex);
        m.setTradeDate(date);
        m.setClosePrice(BigDecimal.valueOf(close));
        m.setOpenPrice(BigDecimal.valueOf(close));
        m.setHighPrice(BigDecimal.valueOf(close));
        m.setLowPrice(BigDecimal.valueOf(close));
        m.setPreClose(preClose);
        m.setVolume(1_000_000L);
        return m;
    }

    /**
     * preClose 缺失时，nextDayPremium 应回退到前一交易日收盘价判定涨停，
     * 而非因 preClose=null 直接漏判（返回 0 导致 isLimitUpAtClose=false）。
     */
    @Test
    void nextDayPremiumFallbacksToPrevCloseWhenPreCloseMissing() {
        LocalDate t = LocalDate.of(2025, 1, 2);
        LocalDate t1 = LocalDate.of(2025, 1, 3);
        LocalDate prev = LocalDate.of(2025, 1, 1);
        // 当日涨停：close=11.0，preClose=null → 须回退到前日收盘 10.0 才能判定涨停（10%）
        MarketData today = bar("000001", "SZSE", t, 11.0, null);
        // 前一交易日收盘 10.0
        MarketData prevBar = bar("000001", "SZSE", prev, 10.0, BigDecimal.valueOf(9.9));
        // 次日：开盘 11.5、收盘 11.3 → gap=+4.5%、ret=+2.7%
        MarketData next = bar("000001", "SZSE", t1, 11.3, BigDecimal.valueOf(11.0));
        next.setOpenPrice(BigDecimal.valueOf(11.5));
        next.setHighPrice(BigDecimal.valueOf(11.6));

        when(tradingCalendarService.findTradingDays(t, t.plusDays(7)))
                .thenReturn(List.of(t, t1));
        when(tradingCalendarService.findTradingDays(t.minusDays(7), t.minusDays(1)))
                .thenReturn(List.of(prev));
        when(marketDataRepository.findByTradeDate(t)).thenReturn(List.of(today));
        when(marketDataRepository.findByTradeDate(t1)).thenReturn(List.of(next));
        when(marketDataRepository.findByTradeDate(prev)).thenReturn(List.of(prevBar));
        StockInfo info = new StockInfo();
        info.setStockCode("000001");
        info.setExchangeType("SZSE");
        info.setStockName("测试股份");
        when(stockInfoRepository.findByStockKeyIn(any())).thenReturn(List.of(info));

        Map<String, Object> out = service().nextDayPremium(t, 50);

        assertThat(out.get("available")).isEqualTo(true);
        assertThat(((Number) out.get("sample_count")).intValue()).isEqualTo(1);
        // gap = next.open / today.close - 1 = 11.5/11.0 - 1 ≈ 0.0455
        assertThat(((Number) out.get("avg_gap")).doubleValue()).isCloseTo(0.0455, org.assertj.core.data.Offset.offset(1e-4));
        // high_open_rate = 1.0（gap > 0）
        assertThat(((Number) out.get("high_open_rate")).doubleValue()).isEqualTo(1.0);
    }

    /**
     * 无涨停股时返回 available=false。
     */
    @Test
    void nextDayPremiumReturnsUnavailableWhenNoLimitUp() {
        LocalDate t = LocalDate.of(2025, 1, 2);
        LocalDate t1 = LocalDate.of(2025, 1, 3);
        LocalDate prev = LocalDate.of(2025, 1, 1);
        // 未涨停：close=10.5，preClose=10.0 → 涨幅 5% < 10%
        MarketData today = bar("000001", "SZSE", t, 10.5, BigDecimal.valueOf(10.0));
        MarketData next = bar("000001", "SZSE", t1, 10.6, BigDecimal.valueOf(10.5));

        when(tradingCalendarService.findTradingDays(t, t.plusDays(7)))
                .thenReturn(List.of(t, t1));
        when(tradingCalendarService.findTradingDays(t.minusDays(7), t.minusDays(1)))
                .thenReturn(List.of(prev));
        when(marketDataRepository.findByTradeDate(t)).thenReturn(List.of(today));
        when(marketDataRepository.findByTradeDate(t1)).thenReturn(List.of(next));
        when(marketDataRepository.findByTradeDate(prev)).thenReturn(List.of());
        when(stockInfoRepository.findByStockKeyIn(any())).thenReturn(List.of());

        Map<String, Object> out = service().nextDayPremium(t, 50);

        assertThat(out.get("available")).isEqualTo(false);
        assertThat(((Number) out.get("sample_count")).intValue()).isEqualTo(0);
    }
}
