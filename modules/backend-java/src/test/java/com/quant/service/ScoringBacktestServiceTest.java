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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        LocalDate exitDate = LocalDate.of(2025, 1, 6);
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
        MarketData exitBar = bar(exitDate, 88, 88);

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(List.of(item));
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(List.of(signalDate, entryDate, exitDate));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(signalBar, entryBar, exitBar));

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
        assertThat(response.getExecutionAssumptions().get("t_plus_one_sell")).isEqualTo(true);
    }

    @Test
    void holdingPeriodOneExitsOnNextTradingDayCloseNotSameDay() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository);

        LocalDate signalDate = LocalDate.of(2025, 1, 2);
        LocalDate entryDate = LocalDate.of(2025, 1, 3);
        LocalDate exitDate = LocalDate.of(2025, 1, 6);
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
        MarketData entryBar = bar(entryDate, 80, 999);
        MarketData exitBar = bar(exitDate, 88, 88);

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(List.of(item));
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(List.of(signalDate, entryDate, exitDate));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(signalBar, entryBar, exitBar));

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
        assertThat(response.getExecutionAssumptions().get("selector_tradability")).isInstanceOf(Map.class);
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
    void usesChiNextLimitPctForEntryCheck() {
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
        item.setStockCode("300001");
        item.setExchangeType("SZSE");
        item.setStockName("创业板测试");
        item.setFinalScore(80.0);

        MarketData signalBar = bar(signalDate, 100, 100);
        MarketData entryBar = bar(entryDate, 110, 112);
        signalBar.setStockCode("300001");
        entryBar.setStockCode("300001");
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

        assertThat(stock.getSkipReason()).isNull();
        assertThat(stock.getEntryPrice()).isEqualTo(110.0);
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
        LocalDate exitDate = LocalDate.of(2025, 1, 6);
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
        MarketData exitBar = bar(exitDate, 81, 81);
        exitBar.setPreClose(BigDecimal.valueOf(90));

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(List.of(item));
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(List.of(signalDate, entryDate, exitDate));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(signalBar, entryBar, exitBar));

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
        assertThat(stock.getPeriodSkipReasons().get(1))
                .isEqualTo(ScoringBacktestService.SKIP_LIMIT_DOWN_AT_CLOSE);
        assertThat(response.getHoldingPeriodStats().get(1).getTotalObservations()).isZero();
        assertThat(response.getExecutionAssumptions().get("limit_down_exit_blocked")).isEqualTo(true);
    }

    @Test
    void skipsExitReturnWhenZeroVolumeOnExitDay() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository);

        LocalDate signalDate = LocalDate.of(2025, 1, 2);
        LocalDate entryDate = LocalDate.of(2025, 1, 3);
        LocalDate exitDate = LocalDate.of(2025, 1, 6);
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
        entryBar.setVolume(1_000_000L);
        MarketData exitBar = bar(exitDate, 88, 88);
        exitBar.setVolume(0L);

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(List.of(item));
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(List.of(signalDate, entryDate, exitDate));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(signalBar, entryBar, exitBar));

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
        assertThat(stock.getPeriodSkipReasons().get(1))
                .isEqualTo(ScoringBacktestService.SKIP_ZERO_VOLUME_ON_EXIT);
    }

    @Test
    void skipsEntryWhenZeroVolumeOnEntryDay() {
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
        MarketData entryBar = bar(entryDate, 10, 10);
        entryBar.setVolume(0L);

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
        ScoringBacktestResponse.StockReturn skipped =
                response.getDailyDetails().getFirst().getTopStocks().getFirst();

        assertThat(skipped.getSkipReason()).isEqualTo(ScoringBacktestService.SKIP_ZERO_VOLUME_ON_ENTRY);
        assertThat(skipped.getEntryPrice()).isNull();
        assertThat(response.getExecutionAssumptions().get("skip_reason_codes"))
                .asList().contains(ScoringBacktestService.SKIP_ZERO_VOLUME_ON_ENTRY);
    }

    @Test
    void populatesFactorAnalysisFromFullCrossSection() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository);

        LocalDate signalDate = LocalDate.of(2025, 1, 2);
        LocalDate entryDate = LocalDate.of(2025, 1, 3);
        LocalDate exitDate = LocalDate.of(2025, 1, 8);
        List<LocalDate> tradingDays = new ArrayList<>();
        for (int d = 2; d <= 10; d++) {
            tradingDays.add(LocalDate.of(2025, 1, d));
        }
        SelectorResult result = new SelectorResult();
        result.setResultId("R1");
        result.setTradeDate(signalDate);
        result.setStrategyType("CLASSIC_SELECTOR");

        List<SelectorResultItem> items = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            SelectorResultItem item = new SelectorResultItem();
            item.setResultId("R1");
            item.setRankNo(i);
            item.setStockCode(String.format("%06d", i));
            item.setExchangeType("SZSE");
            item.setStockName("测试" + i);
            item.setFinalScore(i * 10.0);
            items.add(item);
        }

        List<MarketData> bars = new ArrayList<>();
        bars.add(bar(signalDate, 100, 100));
        bars.add(bar(entryDate, 80, 88));
        for (SelectorResultItem item : items) {
            MarketData entry = bar(entryDate, 80, 88);
            entry.setStockCode(item.getStockCode());
            MarketData exit = bar(exitDate, 80 + item.getRankNo(), 80 + item.getRankNo());
            exit.setStockCode(item.getStockCode());
            bars.add(entry);
            bars.add(exit);
        }

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(items);
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(tradingDays);
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(bars);

        ScoringBacktestRequest request = new ScoringBacktestRequest();
        request.setStrategyType("CLASSIC_SELECTOR");
        request.setStartDate(signalDate);
        request.setEndDate(signalDate);
        request.setTopN(3);
        request.setHoldingDays(List.of(5));
        request.setCommissionRate(0.0);
        request.setSellTaxRate(0.0);
        request.setSlippageRate(0.0);
        request.setFactorGroupCount(5);

        ScoringBacktestResponse response = service.run(request);

        assertThat(response.getFactorAnalysis()).isNotNull();
        assertThat(response.getFactorAnalysis().getCrossSectionDays()).isEqualTo(1);
        assertThat(response.getFactorAnalysis().getHoldingDays()).isEqualTo(5);
        assertThat(response.getFactorAnalysis().getRankIcMean()).isGreaterThan(0);
        assertThat(response.getFactorAnalysis().getGroupedReturns()).hasSize(5);
        assertThat(response.getFactorAnalysis().getLongShortSpread()).isGreaterThan(0);
        assertThat(response.getFactorIcByHolding()).containsKey(5);
        assertThat(response.getFactorIcByHolding().get(5).getRankIcMean()).isGreaterThan(0);
        assertThat(response.getFormulaAudit()).isNotNull();
        assertThat(response.getFormulaAudit().get("strategy_type")).isEqualTo("CLASSIC_SELECTOR");
    }

    @Test
    void shortHorizonValidationMarksPositiveIcAsEffective() {
        Map<Integer, ScoringBacktestResponse.FactorIcSummary> map = Map.of(
                3, ScoringBacktestResponse.FactorIcSummary.builder()
                        .holdingDays(3).crossSectionDays(10).rankIcMean(0.05).build(),
                5, ScoringBacktestResponse.FactorIcSummary.builder()
                        .holdingDays(5).crossSectionDays(10).rankIcMean(0.04).build()
        );
        Map<String, Object> v = ScoringBacktestService.buildShortHorizonValidation(map, null);
        assertThat(v.get("t3_effective")).isEqualTo(true);
        assertThat(v.get("t5_effective")).isEqualTo(true);
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
