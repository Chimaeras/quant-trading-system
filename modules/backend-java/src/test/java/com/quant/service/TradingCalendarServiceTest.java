package com.quant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.TradingCalendarDay;
import com.quant.repository.TradingCalendarDayRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QP-005 交易日历：解析 + 幂等持久化 + 覆盖率统计测试。
 */
@ExtendWith(MockitoExtension.class)
class TradingCalendarServiceTest {

    @Mock
    private TradingCalendarDayRepository tradingCalendarDayRepository;

    @InjectMocks
    private TradingCalendarService service;

    /** 构造一个东方财富 K 线 JSON 样例（含 3 个交易日）。 */
    private String sampleKlineJson() {
        return """
                {"data":{"klines":[
                  "2025-01-02,3300,3310,3315,3295,100000,300000000,0.6,0.30,10,0.5",
                  "2025-01-03,3310,3305,3320,3300,90000,280000000,0.6,-0.15,-5,0.4",
                  "2025-01-06,3305,3318,3325,3302,110000,310000000,0.7,0.39,13,0.6"
                ]},"rc":0}
                """;
    }

    @Test
    void parseTradeDatesExtractsDatesFromKlines() throws Exception {
        List<LocalDate> dates = service.parseTradeDates(sampleKlineJson());
        assertThat(dates).containsExactly(
                LocalDate.of(2025, 1, 2),
                LocalDate.of(2025, 1, 3),
                LocalDate.of(2025, 1, 6));
    }

    @Test
    void parseTradeDatesHandlesEmptyOrNullData() throws Exception {
        assertThat(service.parseTradeDates("{\"data\":{}}")).isEmpty();
        assertThat(service.parseTradeDates("{\"data\":{\"klines\":[]}}")).isEmpty();
        assertThat(service.parseTradeDates("{}")).isEmpty();
    }

    @Test
    void parseTradeDatesSkipsUnparseableLines() throws Exception {
        String json = "{\"data\":{\"klines\":[\"2025-01-02,1,2,3,4,5,6\", \"bad-date,1,2\", \"\"]}}";
        List<LocalDate> dates = service.parseTradeDates(json);
        assertThat(dates).containsExactly(LocalDate.of(2025, 1, 2));
    }

    @Test
    void buildCoverageReportsSourceAndReliabilityBreakdown() {
        when(tradingCalendarDayRepository.count()).thenReturn(1000L);
        when(tradingCalendarDayRepository.countBySource(TradingCalendarService.SOURCE_EASTMONEY_DERIVED)).thenReturn(1000L);
        when(tradingCalendarDayRepository.countBySource(TradingCalendarService.SOURCE_TUSHARE)).thenReturn(0L);
        when(tradingCalendarDayRepository.countByReliability(TradingCalendarService.RELIABILITY_LIMITED)).thenReturn(1000L);
        when(tradingCalendarDayRepository.countByReliability(TradingCalendarService.RELIABILITY_OFFICIAL)).thenReturn(0L);
        when(tradingCalendarDayRepository.findMinTradeDate()).thenReturn(Optional.of(LocalDate.of(2023, 1, 3)));
        when(tradingCalendarDayRepository.findMaxTradeDate()).thenReturn(Optional.of(LocalDate.of(2025, 7, 31)));

        Map<String, Object> cov = service.buildCoverage();

        assertThat(cov.get("total_rows")).isEqualTo(1000L);
        assertThat(cov.get("earliest_date")).isEqualTo("2023-01-03");
        assertThat(cov.get("latest_date")).isEqualTo("2025-07-31");
        assertThat(cov.get("status")).asString().contains("已启用");
    }

    @Test
    void buildCoverageReportsUninitializedWhenEmpty() {
        when(tradingCalendarDayRepository.count()).thenReturn(0L);
        when(tradingCalendarDayRepository.countBySource(TradingCalendarService.SOURCE_EASTMONEY_DERIVED)).thenReturn(0L);
        when(tradingCalendarDayRepository.countBySource(TradingCalendarService.SOURCE_TUSHARE)).thenReturn(0L);
        when(tradingCalendarDayRepository.countByReliability(TradingCalendarService.RELIABILITY_LIMITED)).thenReturn(0L);
        when(tradingCalendarDayRepository.countByReliability(TradingCalendarService.RELIABILITY_OFFICIAL)).thenReturn(0L);
        when(tradingCalendarDayRepository.findMinTradeDate()).thenReturn(Optional.empty());
        when(tradingCalendarDayRepository.findMaxTradeDate()).thenReturn(Optional.empty());

        Map<String, Object> cov = service.buildCoverage();
        assertThat(cov.get("total_rows")).isEqualTo(0L);
        assertThat(cov.get("status")).asString().contains("未初始化");
    }

    @Test
    void isTradingDayReturnsTrueWhenDateExists() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        TradingCalendarDay day = new TradingCalendarDay();
        day.setTradeDate(d);
        when(tradingCalendarDayRepository.findByTradeDate(d)).thenReturn(Optional.of(day));

        assertThat(service.isTradingDay(d)).isTrue();
    }

    @Test
    void isTradingDayReturnsFalseWhenDateAbsent() {
        LocalDate d = LocalDate.of(2025, 1, 4); // 周六
        when(tradingCalendarDayRepository.findByTradeDate(d)).thenReturn(Optional.empty());

        assertThat(service.isTradingDay(d)).isFalse();
    }
}
