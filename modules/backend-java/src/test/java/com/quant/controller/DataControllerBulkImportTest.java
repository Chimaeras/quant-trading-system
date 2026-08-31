package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.entity.StockFinancialSnapshot;
import com.quant.entity.StockMarginDaily;
import com.quant.repository.StockFinancialSnapshotRepository;
import com.quant.repository.StockMarginDailyRepository;
import com.quant.service.ConcurrencyGuard;
import com.quant.service.ConflictRetry;
import com.quant.service.DataQualityGateService;
import com.quant.service.DataQualityService;
import com.quant.service.DataService;
import com.quant.service.DataSnapshotService;
import com.quant.service.HistoricalDataService;
import com.quant.service.HistoricalDataUpdateService;
import com.quant.service.IndexDataService;
import com.quant.service.MarketDataBatchService;
import com.quant.service.TradingCalendarService;
import com.quant.research.SecurityMasterHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
 * bulkFinancial / bulkMargin 的幂等 upsert 语义测试（P1-3 防护）。
 *
 * <p>EOD 导入天然重跑（重试/刷新同日），必须按唯一键 upsert：已存在的 key 走更新而非插入，
 * 否则撞唯一键抛 DataIntegrityViolationException（500）。
 */
@ExtendWith(MockitoExtension.class)
class DataControllerBulkImportTest {

    @Mock
    private DataService dataService;
    @Mock
    private HistoricalDataService historicalDataService;
    @Mock
    private HistoricalDataUpdateService historicalDataUpdateService;
    @Mock
    private StockFinancialSnapshotRepository stockFinancialSnapshotRepository;
    @Mock
    private StockMarginDailyRepository stockMarginDailyRepository;
    @Mock
    private DataQualityService dataQualityService;
    @Mock
    private DataQualityGateService dataQualityGateService;
    @Mock
    private SecurityMasterHistoryService securityMasterHistoryService;
    @Mock
    private TradingCalendarService tradingCalendarService;
    @Mock
    private IndexDataService indexDataService;
    @Mock
    private DataSnapshotService dataSnapshotService;
    @Mock
    private MarketDataBatchService marketDataBatchService;
    @Mock
    private ConflictRetry conflictRetry;
    @Mock
    private ConcurrencyGuard concurrencyGuard;

    @InjectMocks
    private DataController controller;

    private static final LocalDate PERIOD = LocalDate.of(2024, 3, 31);

    @Test
    void bulkFinancialInsertsNewRowsAndCountsSaved() {
        when(stockFinancialSnapshotRepository.findByStockCodeAndExchangeTypeAndReportPeriod("000001", "SZSE", PERIOD))
                .thenReturn(Optional.empty());

        ApiResponse<Map<String, Object>> resp = controller.bulkFinancial(List.of(financialItem("000001", "SZSE", PERIOD)));
        Map<String, Object> data = resp.getData();
        assertThat(data.get("saved")).isEqualTo(1);
        assertThat(data.get("updated")).isEqualTo(0);
        verify(stockFinancialSnapshotRepository).save(any(StockFinancialSnapshot.class));
    }

    @Test
    void bulkFinancialUpdatesExistingRowByUniqueKey() {
        StockFinancialSnapshot existing = new StockFinancialSnapshot();
        existing.setId(1L);
        existing.setStockCode("000001");
        existing.setExchangeType("SZSE");
        existing.setReportPeriod(PERIOD);
        existing.setRoe(new BigDecimal("5.0"));
        when(stockFinancialSnapshotRepository.findByStockCodeAndExchangeTypeAndReportPeriod("000001", "SZSE", PERIOD))
                .thenReturn(Optional.of(existing));

        ApiResponse<Map<String, Object>> resp = controller.bulkFinancial(List.of(financialItem("000001", "SZSE", PERIOD)));
        Map<String, Object> data = resp.getData();
        assertThat(data.get("saved")).isEqualTo(0);
        assertThat(data.get("updated")).isEqualTo(1);
        // 更新复用同一对象（含 id），不新建，从而不撞唯一键
        verify(stockFinancialSnapshotRepository).save(existing);
        assertThat(existing.getRoe()).isNull(); // 本次导入未提供 roe，覆盖为 null（刷新语义）
    }

    @Test
    void bulkFinancialSkipsRowsMissingKeyFields() {
        DataController.FinancialItem bad = new DataController.FinancialItem();
        bad.setStockCode("000002");
        bad.setExchangeType("SZSE");
        bad.setReportPeriod(null);

        ApiResponse<Map<String, Object>> resp = controller.bulkFinancial(List.of(bad));
        Map<String, Object> data = resp.getData();
        assertThat(data.get("saved")).isEqualTo(0);
        assertThat(data.get("skipped")).isEqualTo(1);
        verify(stockFinancialSnapshotRepository, never()).save(any(StockFinancialSnapshot.class));
    }

    @Test
    void bulkFinancialEmptyListReturnsZeroCounts() {
        ApiResponse<Map<String, Object>> resp = controller.bulkFinancial(List.of());
        Map<String, Object> data = resp.getData();
        assertThat(data.get("saved")).isEqualTo(0);
        assertThat(data.get("updated")).isEqualTo(0);
        assertThat(data.get("skipped")).isEqualTo(0);
        verify(stockFinancialSnapshotRepository, never()).save(any(StockFinancialSnapshot.class));
    }

    @Test
    void bulkMarginUpdatesExistingRowByUniqueKey() {
        StockMarginDaily existing = new StockMarginDaily();
        existing.setId(1L);
        existing.setStockCode("600000");
        existing.setExchangeType("SSE");
        existing.setTradeDate(PERIOD);
        when(stockMarginDailyRepository.findByStockCodeAndExchangeTypeAndTradeDate("600000", "SSE", PERIOD))
                .thenReturn(Optional.of(existing));

        ApiResponse<Map<String, Object>> resp = controller.bulkMargin(List.of(marginItem("600000", "SSE", PERIOD)));
        Map<String, Object> data = resp.getData();
        assertThat(data.get("saved")).isEqualTo(0);
        assertThat(data.get("updated")).isEqualTo(1);
        // 更新复用同一对象，不新建
        verify(stockMarginDailyRepository).save(existing);
        assertThat(existing.getMarginBalance()).isNull();
    }

    private static DataController.FinancialItem financialItem(String code, String ex, LocalDate period) {
        DataController.FinancialItem it = new DataController.FinancialItem();
        it.setStockCode(code);
        it.setExchangeType(ex);
        it.setReportPeriod(period);
        return it;
    }

    private static DataController.MarginItem marginItem(String code, String ex, LocalDate date) {
        DataController.MarginItem it = new DataController.MarginItem();
        it.setStockCode(code);
        it.setExchangeType(ex);
        it.setTradeDate(date);
        return it;
    }
}
