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
        when(stockInfoRepo.findByStockCodeAndExchangeType("000001", "SZSE")).thenReturn(Optional.of(s1));

        StockInfo s2 = new StockInfo();
        s2.setStockCode("000002");
        s2.setExchangeType("SZSE");
        s2.setStockName("测试二");
        s2.setListDate(LocalDate.of(2015, 1, 1));
        when(stockInfoRepo.findByStockCodeAndExchangeType("000002", "SZSE")).thenReturn(Optional.of(s2));

        Map<String, Object> result = dataService.backfillListDateFromMarketData();

        assertThat(result.get("missing_before")).isEqualTo(2L);
        assertThat(result.get("updated")).isEqualTo(1);
        assertThat(result.get("skipped_no_match")).isEqualTo(1);
        assertThat(result.get("missing_after")).isEqualTo(1L);
        assertThat(result.get("source")).isEqualTo("market_data_earliest_trade_date");

        ArgumentCaptor<StockInfo> captor = ArgumentCaptor.forClass(StockInfo.class);
        verify(stockInfoRepo, times(1)).save(captor.capture());
        assertThat(captor.getValue().getListDate()).isEqualTo(LocalDate.of(2010, 6, 1));
    }

    @Test
    void backfillListDateFromMarketDataReturnsZeroWhenNothingToUpdate() {
        when(stockInfoRepo.countByListDateIsNull()).thenReturn(0L, 0L);
        when(marketDataRepo.findEarliestTradeDatesForMissingListDate()).thenReturn(List.of());

        Map<String, Object> result = dataService.backfillListDateFromMarketData();

        assertThat(result.get("updated")).isEqualTo(0);
        verify(stockInfoRepo, never()).save(any());
    }
}
