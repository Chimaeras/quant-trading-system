package com.quant.service;

import com.quant.dto.ScoringBacktestRequest;
import com.quant.dto.ScoringBacktestResponse;
import com.quant.entity.MarketData;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoringBacktestServiceTest {

    @Test
    void entersAtNextTradingDayOpenInsteadOfSignalDayClose() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository);

        LocalDate signalDate = LocalDate.of(2025, 1, 2);
        LocalDate entryDate = LocalDate.of(2025, 1, 3);
        SelectorResult result = new SelectorResult();
        result.setResultId("R1");
        result.setTradeDate(signalDate);
        result.setStrategyType("CLASSIC_SELECTOR");

        SelectorResultItem item = new SelectorResultItem();
        item.setResultId("R1");
        item.setRankNo(1);
        item.setStockCode("000001");
        item.setExchangeType("SZSE");
        item.setStockName("测试股票");
        item.setFinalScore(80.0);

        MarketData signalBar = bar(signalDate, 100, 100);
        MarketData entryBar = bar(entryDate, 80, 88);

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(List.of(item));
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(List.of(signalDate, entryDate));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(signalBar, entryBar));

        ScoringBacktestRequest request = new ScoringBacktestRequest();
        request.setStrategyType("CLASSIC_SELECTOR");
        request.setStartDate(signalDate);
        request.setEndDate(signalDate);
        request.setTopN(1);
        request.setHoldingDays(List.of(1));
        request.setCommissionRate(0.0);
        request.setSellTaxRate(0.0);
        request.setSlippageRate(0.0);

        ScoringBacktestResponse response = service.run(request);
        ScoringBacktestResponse.StockReturn stock =
                response.getDailyDetails().getFirst().getTopStocks().getFirst();

        assertThat(stock.getEntryDate()).isEqualTo(entryDate);
        assertThat(stock.getEntryPrice()).isEqualTo(80.0);
        assertThat(stock.getForwardReturns().get(1)).isCloseTo(0.10, within(1e-9));
    }

    @Test
    void skipsEntryWhenLimitUpAtOpen() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository);

        LocalDate signalDate = LocalDate.of(2025, 1, 2);
        LocalDate entryDate = LocalDate.of(2025, 1, 3);
        SelectorResult result = new SelectorResult();
        result.setResultId("R1");
        result.setTradeDate(signalDate);
        result.setStrategyType("CLASSIC_SELECTOR");

        SelectorResultItem item = new SelectorResultItem();
        item.setResultId("R1");
        item.setRankNo(1);
        item.setStockCode("000001");
        item.setExchangeType("SZSE");
        item.setStockName("测试股票");
        item.setFinalScore(80.0);

        MarketData signalBar = bar(signalDate, 100, 100);
        MarketData entryBar = bar(entryDate, 110, 110);
        entryBar.setPreClose(BigDecimal.valueOf(100));

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(List.of(item));
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(List.of(signalDate, entryDate));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(signalBar, entryBar));

        ScoringBacktestRequest request = new ScoringBacktestRequest();
        request.setStrategyType("CLASSIC_SELECTOR");
        request.setStartDate(signalDate);
        request.setEndDate(signalDate);
        request.setTopN(1);
        request.setHoldingDays(List.of(1));

        ScoringBacktestResponse response = service.run(request);

        assertThat(response.getDailyDetails()).hasSize(1);
        ScoringBacktestResponse.StockReturn skipped =
                response.getDailyDetails().getFirst().getTopStocks().getFirst();
        assertThat(skipped.getSkipReason()).isEqualTo(ScoringBacktestService.SKIP_LIMIT_UP_AT_OPEN);
        assertThat(skipped.getEntryPrice()).isNull();
        assertThat(response.getHoldingPeriodStats().get(1).getTotalObservations()).isZero();
        assertThat(response.getExecutionAssumptions().get("limit_up_entry_blocked")).isEqualTo(true);
    }

    @Test
    void recordsSkipReasonWhenNoQuoteOnEntryDay() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository);

        LocalDate signalDate = LocalDate.of(2025, 1, 2);
        LocalDate entryDate = LocalDate.of(2025, 1, 3);
        SelectorResult result = new SelectorResult();
        result.setResultId("R1");
        result.setTradeDate(signalDate);
        result.setStrategyType("CLASSIC_SELECTOR");

        SelectorResultItem item = new SelectorResultItem();
        item.setResultId("R1");
        item.setRankNo(1);
        item.setStockCode("000001");
        item.setExchangeType("SZSE");
        item.setStockName("测试股票");
        item.setFinalScore(80.0);

        MarketData signalBar = bar(signalDate, 100, 100);

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(List.of(item));
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(List.of(signalDate, entryDate));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(signalBar));

        ScoringBacktestRequest request = new ScoringBacktestRequest();
        request.setStrategyType("CLASSIC_SELECTOR");
        request.setStartDate(signalDate);
        request.setEndDate(signalDate);
        request.setTopN(1);
        request.setHoldingDays(List.of(1));

        ScoringBacktestResponse response = service.run(request);
        ScoringBacktestResponse.StockReturn skipped =
                response.getDailyDetails().getFirst().getTopStocks().getFirst();

        assertThat(skipped.getSkipReason()).isEqualTo(ScoringBacktestService.SKIP_NO_QUOTE_ON_ENTRY);
        assertThat(skipped.getEntryDate()).isEqualTo(entryDate);
    }

    @Test
    void skipsExitReturnWhenLimitDownAtClose() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository);

        LocalDate signalDate = LocalDate.of(2025, 1, 2);
        LocalDate entryDate = LocalDate.of(2025, 1, 3);
        SelectorResult result = new SelectorResult();
        result.setResultId("R1");
        result.setTradeDate(signalDate);
        result.setStrategyType("CLASSIC_SELECTOR");

        SelectorResultItem item = new SelectorResultItem();
        item.setResultId("R1");
        item.setRankNo(1);
        item.setStockCode("000001");
        item.setExchangeType("SZSE");
        item.setStockName("测试股票");
        item.setFinalScore(80.0);

        MarketData signalBar = bar(signalDate, 100, 100);
        MarketData entryBar = bar(entryDate, 80, 90);
        entryBar.setPreClose(BigDecimal.valueOf(100));

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(List.of(item));
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(List.of(signalDate, entryDate));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(signalBar, entryBar));

        ScoringBacktestRequest request = new ScoringBacktestRequest();
        request.setStrategyType("CLASSIC_SELECTOR");
        request.setStartDate(signalDate);
        request.setEndDate(signalDate);
        request.setTopN(1);
        request.setHoldingDays(List.of(1));
        request.setCommissionRate(0.0);
        request.setSellTaxRate(0.0);
        request.setSlippageRate(0.0);

        ScoringBacktestResponse response = service.run(request);
        ScoringBacktestResponse.StockReturn stock =
                response.getDailyDetails().getFirst().getTopStocks().getFirst();

        assertThat(stock.getEntryPrice()).isEqualTo(80.0);
        assertThat(stock.getForwardReturns().get(1)).isNull();
        assertThat(response.getHoldingPeriodStats().get(1).getTotalObservations()).isZero();
        assertThat(response.getExecutionAssumptions().get("limit_down_exit_blocked")).isEqualTo(true);
    }

    private MarketData bar(LocalDate date, double open, double close) {
        MarketData bar = new MarketData();
        bar.setStockCode("000001");
        bar.setExchangeType("SZSE");
        bar.setTradeDate(date);
        bar.setOpenPrice(BigDecimal.valueOf(open));
        bar.setClosePrice(BigDecimal.valueOf(close));
        return bar;
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
