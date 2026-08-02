package com.quant.service.scoring;

import com.quant.entity.MarketData;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.service.TradingCalendarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
 * 涨停基因 / 短线情绪生态：涨停标记、连板天数、市场涨停家数、次日溢价。
 */
@ExtendWith(MockitoExtension.class)
class LimitUpEcologyServiceTest {

    @Mock
    private MarketDataRepository marketDataRepository;
    @Mock
    private StockInfoRepository stockInfoRepository;
    @Mock
    private TradingCalendarService tradingCalendarService;

    @InjectMocks
    private LimitUpEcologyService service;

    private static MarketData bar(String code, String ex, LocalDate date, double close, double preClose, long vol) {
        MarketData m = new MarketData();
        m.setStockCode(code);
        m.setExchangeType(ex);
        m.setTradeDate(date);
        m.setClosePrice(BigDecimal.valueOf(close));
        m.setPreClose(BigDecimal.valueOf(preClose));
        m.setOpenPrice(BigDecimal.valueOf(close));
        m.setHighPrice(BigDecimal.valueOf(close));
        m.setLowPrice(BigDecimal.valueOf(close * 0.98));
        m.setVolume(vol);
        return m;
    }

    @Test
    void stockLimitUpFeaturesDetectsLimitUpAndLianban() {
        LocalDate d0 = LocalDate.of(2025, 1, 2);
        LocalDate d1 = LocalDate.of(2025, 1, 3);
        // 000001 主板：前收 10 → 收盘 11（+10%）→ 涨停；前日 10/9.5 非涨停 → 连板=1
        List<MarketData> bars = List.of(
                bar("000001", "SZSE", d0, 10.0, 9.5, 1000),
                bar("000001", "SZSE", d1, 11.0, 10.0, 1500));
        Map<String, Object> f = service.stockLimitUpFeatures(bars, bars.get(1), "测试");

        assertThat(f.get("is_limit_up")).isEqualTo(true);
        assertThat(f.get("lianban_days")).isEqualTo(1);
        assertThat(f.get("volume_ratio")).isEqualTo(1.5);
    }

    @Test
    void ecologyCountsLimitUpStocksAndMaxLianban() {
        LocalDate d0 = LocalDate.of(2025, 1, 2);
        LocalDate d1 = LocalDate.of(2025, 1, 3);
        // 000001 涨停（+10%）；600000 +5% 非涨停
        List<MarketData> rangeBars = List.of(
                bar("000001", "SZSE", d0, 10.0, 9.5, 1000),
                bar("000001", "SZSE", d1, 11.0, 10.0, 1500),
                bar("600000", "SSE", d0, 20.0, 19.0, 800),
                bar("600000", "SSE", d1, 21.0, 20.0, 900));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(rangeBars);
        when(marketDataRepository.findByTradeDate(d1))
                .thenReturn(List.of(
                        bar("000001", "SZSE", d1, 11.0, 10.0, 1500),
                        bar("600000", "SSE", d1, 21.0, 20.0, 900)));
        when(stockInfoRepository.findAll()).thenReturn(List.of());
        when(tradingCalendarService.findTradingDays(d1.minusDays(7), d1.minusDays(1))).thenReturn(List.of());

        Map<String, Object> eco = service.ecology(d1, 30);

        assertThat(eco.get("limit_up_count")).isEqualTo(1);
        assertThat(eco.get("max_lianban")).isEqualTo(1);
        assertThat((Number) eco.get("promotion_rate")).isEqualTo(0.0);
    }
}
