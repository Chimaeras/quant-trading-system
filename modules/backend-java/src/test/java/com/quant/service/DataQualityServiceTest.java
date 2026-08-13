package com.quant.service;

import com.quant.repository.AdjustFactorRepository;
import com.quant.repository.IndexQuoteRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.SecurityMasterHistoryRepository;
import com.quant.repository.StockInfoRepository;
import com.quant.repository.TradingCalendarDayRepository;
import com.quant.entity.DataSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
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
    private TradingCalendarDayRepository tradingCalendarDayRepository;
    @Mock
    private IndexQuoteRepository indexQuoteRepository;
    @Mock
    private StockInfoRepository stockInfoRepository;
    @Mock
    private DataSnapshotService dataSnapshotService;
    @Mock
    private TradingCalendarService tradingCalendarService;

    private DataQualityService service() {
        return new DataQualityService(marketDataRepository, marketPriceRawRepository,
                adjustFactorRepository, securityMasterHistoryRepository,
                tradingCalendarDayRepository, indexQuoteRepository, stockInfoRepository,
                dataSnapshotService, tradingCalendarService);
    }

    @BeforeEach
    void stubEmptySnapshotDefaults() {
        // 默认快照注册表为空，避免 buildQualityReport 调用时 NPE
        lenient().when(dataSnapshotService.count()).thenReturn(0L);
        lenient().when(dataSnapshotService.listLatest(5)).thenReturn(List.of());
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
        assertThat(sm.get("coverage_tag")).asString().isEqualTo("LIMITED_BOOTSTRAP");
        assertThat(sm.get("status")).asString().contains("LIMITED_BOOTSTRAP");
    }

    /**
     * QP-005：质量报告须披露真实交易日历覆盖与 market_data 差异。
     */
    @Test
    void qualityReportDisclosesTradingCalendarCoverage() {
        LocalDate min = LocalDate.of(2025, 1, 2);
        LocalDate max = LocalDate.of(2025, 1, 6);
        when(marketDataRepository.findMinTradeDate()).thenReturn(Optional.of(min));
        when(marketDataRepository.findMaxTradeDate()).thenReturn(Optional.of(max));
        when(marketDataRepository.findDistinctTradeDatesBetween(min, max))
                .thenReturn(List.of(min, max));  // market_data 只有 2 个交易日
        when(stockInfoRepository.countByListDateIsNull()).thenReturn(0L);

        when(tradingCalendarDayRepository.count()).thenReturn(3L);
        when(tradingCalendarDayRepository.countBySource("eastmoney_derived")).thenReturn(3L);
        when(tradingCalendarDayRepository.countBySource("tushare")).thenReturn(0L);
        when(tradingCalendarDayRepository.countByReliability("limited")).thenReturn(3L);
        when(tradingCalendarDayRepository.countByReliability("official")).thenReturn(0L);
        when(tradingCalendarDayRepository.findMinTradeDate()).thenReturn(Optional.of(min));
        when(tradingCalendarDayRepository.findMaxTradeDate()).thenReturn(Optional.of(max));
        // 真实交易日历 3 个交易日，market_data 只有 2 个 → 缺 1 个
        when(tradingCalendarDayRepository.findTradeDatesBetween(min, max))
                .thenReturn(List.of(min, LocalDate.of(2025, 1, 3), max));

        Map<String, Object> out = service().buildQualityReport();

        @SuppressWarnings("unchecked")
        Map<String, Object> tc = (Map<String, Object>) out.get("trading_calendar");
        assertThat(tc).isNotNull();
        assertThat(tc.get("total_rows")).isEqualTo(3L);
        assertThat(tc.get("status")).asString().contains("已启用");
        @SuppressWarnings("unchecked")
        Map<String, Object> diff = (Map<String, Object>) tc.get("diff_vs_market_data");
        assertThat(diff.get("calendar_days")).isEqualTo(3);
        assertThat(diff.get("market_data_days")).isEqualTo(2);
        assertThat(diff.get("missing_in_market_data")).isEqualTo(1);
    }

    /**
     * QP-005：质量报告须披露指数数据覆盖（4 指数）。
     */
    @Test
    void qualityReportDisclosesIndexCoverage() {
        when(marketDataRepository.findMinTradeDate()).thenReturn(Optional.empty());
        when(marketDataRepository.findMaxTradeDate()).thenReturn(Optional.empty());
        when(stockInfoRepository.countByListDateIsNull()).thenReturn(0L);

        when(indexQuoteRepository.count()).thenReturn(1000L);
        when(indexQuoteRepository.countDistinctIndices()).thenReturn(4L);
        when(indexQuoteRepository.countByIndex("000300")).thenReturn(400L);
        when(indexQuoteRepository.countByIndex("000905")).thenReturn(300L);
        when(indexQuoteRepository.countByIndex("000852")).thenReturn(200L);
        when(indexQuoteRepository.countByIndex("000001")).thenReturn(100L);
        when(indexQuoteRepository.findMinTradeDateByIndex("000300")).thenReturn(Optional.of(LocalDate.of(2023, 1, 3)));
        when(indexQuoteRepository.findMaxTradeDateByIndex("000300")).thenReturn(Optional.of(LocalDate.of(2025, 7, 31)));
        when(indexQuoteRepository.countBySource("eastmoney")).thenReturn(1000L);
        when(indexQuoteRepository.countBySource("tushare")).thenReturn(0L);
        when(indexQuoteRepository.countByReliability("limited")).thenReturn(1000L);
        when(indexQuoteRepository.countByReliability("official")).thenReturn(0L);

        Map<String, Object> out = service().buildQualityReport();

        @SuppressWarnings("unchecked")
        Map<String, Object> idx = (Map<String, Object>) out.get("index_data");
        assertThat(idx).isNotNull();
        assertThat(idx.get("total_rows")).isEqualTo(1000L);
        assertThat(idx.get("distinct_indices")).isEqualTo(4L);
        assertThat(idx.get("status")).asString().contains("已启用");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indices = (List<Map<String, Object>>) idx.get("indices");
        assertThat(indices).hasSize(4);
        assertThat(indices.get(0).get("index_code")).isEqualTo("000300");
        assertThat(indices.get(0).get("rows")).isEqualTo(400L);
        assertThat(indices.get(0).get("earliest_date")).isEqualTo("2023-01-03");
    }

    /**
     * QP-006：质量报告须披露数据快照注册表（最近快照 + 可复现性提示）。
     */
    @Test
    void qualityReportDisclosesSnapshotRegistry() {
        when(marketDataRepository.findMinTradeDate()).thenReturn(Optional.empty());
        when(marketDataRepository.findMaxTradeDate()).thenReturn(Optional.empty());
        when(stockInfoRepository.countByListDateIsNull()).thenReturn(0L);

        when(dataSnapshotService.count()).thenReturn(3L);
        DataSnapshot snap = new DataSnapshot();
        snap.setSnapshotId("20250731-001");
        snap.setCutoffTime(LocalDate.of(2025, 7, 31));
        snap.setHash("abc123");
        snap.setCreatedBy("manual");
        snap.setRowCounts("{\"market_data\":100}");
        when(dataSnapshotService.listLatest(5)).thenReturn(List.of(snap));

        Map<String, Object> out = service().buildQualityReport();

        @SuppressWarnings("unchecked")
        Map<String, Object> reg = (Map<String, Object>) out.get("data_snapshot");
        assertThat(reg).isNotNull();
        assertThat(reg.get("total_snapshots")).isEqualTo(3L);
        assertThat(reg.get("status")).asString().contains("已启用");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> latest = (List<Map<String, Object>>) reg.get("latest");
        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).get("snapshot_id")).isEqualTo("20250731-001");
        assertThat(latest.get(0).get("hash")).isEqualTo("abc123");
    }
}
