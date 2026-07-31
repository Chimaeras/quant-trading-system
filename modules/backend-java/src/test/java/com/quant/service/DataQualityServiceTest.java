package com.quant.service;

import com.quant.repository.AdjustFactorRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.SecurityMasterHistoryRepository;
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
    private MarketPriceRawRepository marketPriceRawRepository;
    @Mock
    private AdjustFactorRepository adjustFactorRepository;
    @Mock
    private SecurityMasterHistoryRepository securityMasterHistoryRepository;
    @Mock
    private StockInfoRepository stockInfoRepository;

    private DataQualityService service() {
        return new DataQualityService(marketDataRepository, marketPriceRawRepository,
                adjustFactorRepository, securityMasterHistoryRepository, stockInfoRepository);
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

    /**
     * QP-003c：质量报告须披露不复权行情与复权因子覆盖进度。
     */
    @Test
    void qualityReportDisclosesRawAndAdjustCoverage() {
        when(marketDataRepository.findMinTradeDate()).thenReturn(Optional.empty());
        when(marketDataRepository.findMaxTradeDate()).thenReturn(Optional.empty());
        when(stockInfoRepository.countByListDateIsNull()).thenReturn(0L);

        when(marketPriceRawRepository.countAll()).thenReturn(1500L);
        when(marketPriceRawRepository.countDistinctStocks()).thenReturn(30L);
        when(marketPriceRawRepository.countDistinctTradeDates()).thenReturn(50L);
        when(marketPriceRawRepository.findMinTradeDate()).thenReturn(Optional.of(LocalDate.of(2025, 1, 2)));
        when(marketPriceRawRepository.findMaxTradeDate()).thenReturn(Optional.of(LocalDate.of(2025, 3, 15)));

        when(adjustFactorRepository.countAll()).thenReturn(1200L);
        when(adjustFactorRepository.countDistinctStocks()).thenReturn(28L);
        when(adjustFactorRepository.countBySource("derived_local")).thenReturn(1200L);
        when(adjustFactorRepository.countBySource("official")).thenReturn(0L);
        when(adjustFactorRepository.countByReliability("limited")).thenReturn(1200L);

        Map<String, Object> out = service().buildQualityReport();

        @SuppressWarnings("unchecked")
        Map<String, Object> cov = (Map<String, Object>) out.get("raw_and_adjust");
        assertThat(cov).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) cov.get("market_price_raw");
        assertThat(raw.get("total_rows")).isEqualTo(1500L);
        assertThat(raw.get("distinct_stocks")).isEqualTo(30L);
        assertThat(raw.get("distinct_trade_dates")).isEqualTo(50L);
        assertThat(raw.get("status")).asString().contains("已启用");

        @SuppressWarnings("unchecked")
        Map<String, Object> factor = (Map<String, Object>) cov.get("adjust_factor");
        assertThat(factor.get("total_rows")).isEqualTo(1200L);
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) factor.get("by_source");
        assertThat(source.get("derived_local")).isEqualTo(1200L);
        assertThat(source.get("official")).isEqualTo(0L);
        assertThat(factor.get("status")).asString().contains("limited");
    }

    /**
     * QP-004：质量报告须披露历史证券主数据覆盖进度与 source/reliability 审计。
     */
    @Test
    void qualityReportDisclosesSecurityMasterCoverage() {
        when(marketDataRepository.findMinTradeDate()).thenReturn(Optional.empty());
        when(marketDataRepository.findMaxTradeDate()).thenReturn(Optional.empty());
        when(stockInfoRepository.countByListDateIsNull()).thenReturn(0L);

        when(securityMasterHistoryRepository.count()).thenReturn(4000L);
        when(securityMasterHistoryRepository.countDistinctStocks()).thenReturn(4000L);
        when(securityMasterHistoryRepository.countBySource("current_snapshot")).thenReturn(4000L);
        when(securityMasterHistoryRepository.countBySource("eastmoney")).thenReturn(0L);
        when(securityMasterHistoryRepository.countBySource("tushare")).thenReturn(0L);
        when(securityMasterHistoryRepository.countByReliability("limited")).thenReturn(4000L);
        when(securityMasterHistoryRepository.countByReliability("official")).thenReturn(0L);

        Map<String, Object> out = service().buildQualityReport();

        @SuppressWarnings("unchecked")
        Map<String, Object> sm = (Map<String, Object>) out.get("security_master");
        assertThat(sm).isNotNull();
        assertThat(sm.get("total_rows")).isEqualTo(4000L);
        assertThat(sm.get("distinct_stocks")).isEqualTo(4000L);
        @SuppressWarnings("unchecked")
        Map<String, Object> src = (Map<String, Object>) sm.get("by_source");
        assertThat(src.get("current_snapshot")).isEqualTo(4000L);
        assertThat(src.get("eastmoney")).isEqualTo(0L);
        @SuppressWarnings("unchecked")
        Map<String, Object> rel = (Map<String, Object>) sm.get("by_reliability");
        assertThat(rel.get("limited")).isEqualTo(4000L);
        assertThat(rel.get("official")).isEqualTo(0L);
        assertThat(sm.get("status")).asString().contains("已启用");
    }
}
