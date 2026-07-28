package com.quant.service;

import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataQualityServiceTest {

    @Mock
    private MarketDataRepository marketDataRepository;
    @Mock
    private StockInfoRepository stockInfoRepository;

    private DataQualityService service() {
        return new DataQualityService(marketDataRepository, stockInfoRepository);
    }

    @Test
    void qualityReportMarksStaleWhenDataBehindThreshold() {
        // 最新行情是 10 天前
        LocalDate max = LocalDate.now().minusDays(10);
        when(marketDataRepository.findMinTradeDate()).thenReturn(Optional.of(max.minusDays(5)));
        when(marketDataRepository.findMaxTradeDate()).thenReturn(Optional.of(max));
        when(marketDataRepository.findDistinctTradeDatesBetween(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(max, max.minusDays(1)));
        when(stockInfoRepository.countByListDateIsNull()).thenReturn(0L);

        Map<String, Object> out = service().buildQualityReport();

        @SuppressWarnings("unchecked")
        Map<String, Object> staleness = (Map<String, Object>) out.get("update_staleness");
        assertThat(staleness.get("stale")).isEqualTo(true);
        assertThat(staleness.get("days_behind")).isEqualTo(10L);
        assertThat(staleness.get("warning")).asString().contains("滞后");

        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) out.get("health");
        assertThat(health.get("status")).isEqualTo("warn");
    }

    @Test
    void qualityReportDetectsMissingTradingDays() {
        // 区间内含周末，actual 只有 2 个交易日，预期 5 个（排除周末后）
        LocalDate min = LocalDate.of(2025, 1, 6);   // 周一
        LocalDate max = LocalDate.of(2025, 1, 10);  // 周五
        when(marketDataRepository.findMinTradeDate()).thenReturn(Optional.of(min));
        when(marketDataRepository.findMaxTradeDate()).thenReturn(Optional.of(max));
        when(marketDataRepository.findDistinctTradeDatesBetween(min, max))
                .thenReturn(List.of(min, max));  // 缺 3 个交易日
        when(stockInfoRepository.countByListDateIsNull()).thenReturn(0L);

        Map<String, Object> out = service().buildQualityReport();

        @SuppressWarnings("unchecked")
        Map<String, Object> tradingDays = (Map<String, Object>) out.get("trading_days");
        assertThat(tradingDays.get("expected")).isEqualTo(5);
        assertThat(tradingDays.get("actual")).isEqualTo(2);
        assertThat(tradingDays.get("missing_count")).isEqualTo(3);
    }

    @Test
    void qualityReportDisclosesAdjustFactorBasis() {
        when(marketDataRepository.findMinTradeDate()).thenReturn(Optional.empty());
        when(marketDataRepository.findMaxTradeDate()).thenReturn(Optional.empty());
        when(stockInfoRepository.countByListDateIsNull()).thenReturn(0L);

        Map<String, Object> out = service().buildQualityReport();

        @SuppressWarnings("unchecked")
        Map<String, Object> adjust = (Map<String, Object>) out.get("adjust_factor");
        assertThat(adjust.get("current_basis")).isEqualTo("前复权（qfq）");
        assertThat(adjust.get("warning")).asString().contains("前复权");
    }
}
