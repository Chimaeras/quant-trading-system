package com.quant.service;

import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataServiceTest {

    @Mock
    private MarketDataRepository marketDataRepo;
    @Mock
    private StockInfoRepository stockInfoRepo;

    private DataService dataService;

    @BeforeEach
    void setUp() {
        dataService = new DataService(marketDataRepo, stockInfoRepo);
    }

    @Test
    void backfillListDateFromMarketDataUpdatesOnlyMissingValues() {
        when(stockInfoRepo.countByListDateIsNull()).thenReturn(2L, 1L);
        when(marketDataRepo.findEarliestTradeDatesForMissingListDate()).thenReturn(List.of(
                new Object[]{"000001", "SZSE", LocalDate.of(2010, 6, 1)},
                new Object[]{"000002", "SZSE", LocalDate.of(2012, 3, 15)}
        ));

        StockInfo s1 = new StockInfo();
        s1.setStockCode("000001");
        s1.setExchangeType("SZSE");
        s1.setStockName("测试一");
        StockInfo s2 = new StockInfo();
        s2.setStockCode("000002");
        s2.setExchangeType("SZSE");
        s2.setStockName("测试二");
        s2.setListDate(LocalDate.of(2015, 1, 1));
        // 批量查询一次返回全部候选（替代逐条 findByStockCodeAndExchangeType）
        when(stockInfoRepo.findByStockKeyIn(any())).thenReturn(List.of(s1, s2));

        Map<String, Object> result = dataService.backfillListDateFromMarketData();

        assertThat(result.get("missing_before")).isEqualTo(2L);
        assertThat(result.get("backfillable")).isEqualTo(2);
        assertThat(result.get("updated")).isEqualTo(1);
        assertThat(result.get("skipped_no_match")).isEqualTo(1);
        assertThat(result.get("missing_after")).isEqualTo(1L);
        assertThat(result.get("source")).isEqualTo("market_data_earliest_trade_date");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StockInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(stockInfoRepo, times(1)).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getListDate()).isEqualTo(LocalDate.of(2010, 6, 1));
    }

    @Test
    void backfillListDateFromMarketDataReturnsZeroWhenNothingToUpdate() {
        when(stockInfoRepo.countByListDateIsNull()).thenReturn(0L, 0L);
        when(marketDataRepo.findEarliestTradeDatesForMissingListDate()).thenReturn(List.of());

        Map<String, Object> result = dataService.backfillListDateFromMarketData();

        assertThat(result.get("updated")).isEqualTo(0);
        verify(stockInfoRepo, never()).save(any());
    }

    @Test
    void getOverviewReportsListDateMissingAndBackfillable() {
        when(marketDataRepo.count()).thenReturn(100L);
        when(marketDataRepo.countDistinctStocks()).thenReturn(10L);
        when(marketDataRepo.findMinTradeDate()).thenReturn(java.util.Optional.of(LocalDate.of(2020, 1, 1)));
        when(marketDataRepo.findMaxTradeDate()).thenReturn(java.util.Optional.of(LocalDate.of(2026, 1, 1)));
        when(stockInfoRepo.countByListDateIsNull()).thenReturn(5L);
        when(marketDataRepo.countBackfillableListDateCandidates()).thenReturn(3L);

        Map<String, Object> overview = dataService.getOverview();

        assertThat(overview.get("list_date_missing_count")).isEqualTo(5L);
        assertThat(overview.get("list_date_backfillable_count")).isEqualTo(3L);
    }
}
