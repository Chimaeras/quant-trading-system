package com.quant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.ScoringBacktestRequest;
import com.quant.dto.ScoringBacktestResponse;
import com.quant.entity.BacktestRun;
import org.mockito.ArgumentCaptor;
import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.BacktestRunRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoringBacktestServiceTest {

    /** 带 JavaTimeModule 的 ObjectMapper（序列化 LocalDate 需要），与 Spring 注入行为一致。 */
    private static ObjectMapper objectMapper() {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .build();
    }

    @Test
    void entersAtNextTradingDayOpenInsteadOfSignalDayClose() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        com.quant.repository.MarketPriceRawRepository rawRepo = mock(com.quant.repository.MarketPriceRawRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                rawRepo,
                mock(BacktestRunRepository.class), objectMapper());

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
        when(rawRepo.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(rawBar(entryDate, 80, 88, "000001"), rawBar(exitDate, 88, 88, "000001")));

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
    void runPersistsBacktestResultAndSupportsRecallByResultId() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        BacktestRunRepository runRepository = mock(BacktestRunRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                        mock(com.quant.repository.MarketPriceRawRepository.class),
                        runRepository, objectMapper());

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

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(List.of(item));
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(List.of(signalDate, entryDate, exitDate));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(bar(signalDate, 100, 100), bar(entryDate, 80, 88), bar(exitDate, 88, 88)));
        when(marketDataRepository.findMaxTradeDate()).thenReturn(java.util.Optional.of(exitDate));

        ScoringBacktestRequest request = new ScoringBacktestRequest();
        request.setStrategyType("CLASSIC_SELECTOR");
        request.setStartDate(signalDate);
        request.setEndDate(signalDate);
        request.setTopN(5);

        ScoringBacktestResponse response = service.run(request);

        // 响应携带 resultId，且已落库
        assertThat(response.getResultId()).isNotNull().startsWith("BT-");
        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(runRepository).save(captor.capture());
        BacktestRun saved = captor.getValue();
        assertThat(saved.getStrategyType()).isEqualTo("CLASSIC_SELECTOR");
        assertThat(saved.getLatestDataDate()).isEqualTo(exitDate);
        assertThat(saved.getResponse()).contains("BT-");
        assertThat(saved.getSummary()).contains("totalTradingDays");

        // 按 resultId 回溯：mock 返回刚保存的 run，getRun 反序列化并回填 resultId
        when(runRepository.findByResultId(saved.getResultId())).thenReturn(java.util.Optional.of(saved));
        ScoringBacktestResponse recalled = service.getRun(saved.getResultId());
        assertThat(recalled).isNotNull();
        assertThat(recalled.getResultId()).isEqualTo(saved.getResultId());
        assertThat(recalled.getStrategyType()).isEqualTo("CLASSIC_SELECTOR");
    }

    /**
     * `D-R010-06`：每行落库都必须带**公式版本标记**。
     * 三个费率不足以判读历史净收益（加法式与两级乘性在库里长得一样），故版本位是必需列而非元数据。
     */
    @Test
    void persistedRunIsStampedWithNetCostConventionVersion() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        BacktestRunRepository runRepository = mock(BacktestRunRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                        mock(com.quant.repository.MarketPriceRawRepository.class),
                        runRepository, objectMapper());

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

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(List.of(item));
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(List.of(signalDate, entryDate, exitDate));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(bar(signalDate, 100, 100), bar(entryDate, 80, 88), bar(exitDate, 88, 88)));
        when(marketDataRepository.findMaxTradeDate()).thenReturn(java.util.Optional.of(exitDate));

        ScoringBacktestRequest request = new ScoringBacktestRequest();
        request.setStrategyType("CLASSIC_SELECTOR");
        request.setStartDate(signalDate);
        request.setEndDate(signalDate);
        request.setTopN(5);

        service.run(request);

        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getNetCostConventionVersion())
                .as("新落库的行必须标注公式版本，否则历史净收益无法判读（D-R010-06）")
                .isEqualTo(com.quant.research.ResearchConvention.NET_COST_CONVENTION_VERSION);
    }

    /**
     * 版本标记的三处一致性守卫：常量 ↔ V28 DDL 的列与注释字面量 ↔ 实体字段名。
     *
     * <p>三者任一漂移都会让「库里写着 v1、代码里是 v2」变成静默事实，而 `ddl-auto=validate`
     * 只校验列存在、不校验列注释里的版本字面量，故必须在此钉死。
     */
    @Test
    void netCostConventionVersionAgreesAcrossConstantDdlAndEntity() throws Exception {
        String ddl;
        try (java.io.InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V28__backtest_run_net_cost_convention_version.sql")) {
            assertThat(in).as("V28 迁移必须存在于 classpath（列与版本字面量的 DDL 侧真相源）").isNotNull();
            ddl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        assertThat(BacktestRun.class.getDeclaredField("netCostConventionVersion"))
                .as("实体字段名 netCostConventionVersion 是 JPA 默认命名策略映射到列 net_cost_convention_version 的唯一依据")
                .isNotNull();
        assertThat(ddl)
                .as("V28 必须真的新增 net_cost_convention_version 列")
                .contains("net_cost_convention_version");
        assertThat(ddl)
                .as("V28 的列注释必须写明本版常量字面量，否则库里的标记无权威解释")
                .contains(com.quant.research.ResearchConvention.NET_COST_CONVENTION_VERSION);
        assertThat(com.quant.research.ResearchConvention.NET_COST_CONVENTION_VERSION)
                .as("版本号长度须在 VARCHAR(40) 以内")
                .hasSizeLessThanOrEqualTo(40);
    }

    @Test
    void holdingPeriodOneExitsOnNextTradingDayCloseNotSameDay() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        com.quant.repository.MarketPriceRawRepository rawRepo = mock(com.quant.repository.MarketPriceRawRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                rawRepo,
                mock(BacktestRunRepository.class), objectMapper());

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
        when(rawRepo.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(rawBar(entryDate, 80, 999, "000001"), rawBar(exitDate, 88, 88, "000001")));

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
        com.quant.repository.MarketPriceRawRepository rawRepo = mock(com.quant.repository.MarketPriceRawRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                rawRepo,
                mock(BacktestRunRepository.class), objectMapper());

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
        // raw 成交价：open=110 ≥ limitUpPrice(100×1.1=110)，触发涨停开盘跳过
        MarketPriceRaw entryRaw = rawBar(entryDate, 110, 110, "000001");
        entryRaw.setPreClose(BigDecimal.valueOf(100));
        when(rawRepo.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(entryRaw));

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
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                mock(com.quant.repository.MarketPriceRawRepository.class),
                mock(BacktestRunRepository.class), objectMapper());

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
        com.quant.repository.MarketPriceRawRepository rawRepo = mock(com.quant.repository.MarketPriceRawRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                rawRepo,
                mock(BacktestRunRepository.class), objectMapper());

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
        // raw 成交价：300001 创业板 20% 涨停限制，110 < 100×1.2=120 → 不触发涨停，正常入场
        MarketPriceRaw entryRaw = rawBar(entryDate, 110, 112, "300001");
        entryRaw.setPreClose(BigDecimal.valueOf(100));
        when(rawRepo.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(entryRaw));

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
        com.quant.repository.MarketPriceRawRepository rawRepo = mock(com.quant.repository.MarketPriceRawRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                rawRepo,
                mock(BacktestRunRepository.class), objectMapper());

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
        // raw 成交价：entry@80 正常入场；exit close=81 ≤ 90×0.9=81 → 跌停收盘跳过
        MarketPriceRaw entryRaw = rawBar(entryDate, 80, 90, "000001");
        entryRaw.setPreClose(BigDecimal.valueOf(100));
        MarketPriceRaw exitRaw = rawBar(exitDate, 81, 81, "000001");
        exitRaw.setPreClose(BigDecimal.valueOf(90));
        when(rawRepo.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(entryRaw, exitRaw));

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
        com.quant.repository.MarketPriceRawRepository rawRepo = mock(com.quant.repository.MarketPriceRawRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                rawRepo,
                mock(BacktestRunRepository.class), objectMapper());

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
        // raw 成交价：entry 正常（vol=1M）；exit raw vol=0 → 零成交跳过
        when(rawRepo.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(rawBar(entryDate, 80, 90, "000001"), rawBarZeroVol(exitDate, 88, 88, "000001")));

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
        com.quant.repository.MarketPriceRawRepository rawRepo = mock(com.quant.repository.MarketPriceRawRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                rawRepo,
                mock(BacktestRunRepository.class), objectMapper());

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
        // raw 成交价 vol=0 → 零成交跳过
        when(rawRepo.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(rawBarZeroVol(entryDate, 10, 10, "000001")));

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
        com.quant.repository.MarketPriceRawRepository rawRepo = mock(com.quant.repository.MarketPriceRawRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                rawRepo,
                mock(BacktestRunRepository.class), objectMapper());

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
        List<MarketPriceRaw> rawBars = new ArrayList<>();
        bars.add(bar(signalDate, 100, 100));
        bars.add(bar(entryDate, 80, 88));
        for (SelectorResultItem item : items) {
            MarketData entry = bar(entryDate, 80, 88);
            entry.setStockCode(item.getStockCode());
            MarketData exit = bar(exitDate, 80 + item.getRankNo(), 80 + item.getRankNo());
            exit.setStockCode(item.getStockCode());
            bars.add(entry);
            bars.add(exit);
            // raw 成交价（topN=3 取前 3 只，但全截面也需 raw 用于退出收益）
            rawBars.add(rawBar(entryDate, 80, 88, item.getStockCode()));
            rawBars.add(rawBar(exitDate, 80 + item.getRankNo(), 80 + item.getRankNo(), item.getStockCode()));
        }

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(items);
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(tradingDays);
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(bars);
        when(rawRepo.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(rawBars);

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
        // 单截面日 ⟹ IC 序列无离散度 ⟹ ICIR 无定义（历史实现给 0.0，把「无定义」显示成「IR 恰为 0」）
        assertThat(response.getFactorAnalysis().getRankIcIr()).isNull();
        // 方向标签此前只存在于载荷 map 中，DTO 消费方拿不到（Q-R013-06）
        assertThat(response.getFactorAnalysis().getMonotonicity())
                .isIn("MONOTONIC_INCREASING", "MONOTONIC_DECREASING", "NON_MONOTONIC", "NO_DISPERSION", "N/A");
        assertThat(response.getFactorAnalysis().getHoldingDays()).isEqualTo(5);
        assertThat(response.getFactorAnalysis().getRankIcMean()).isGreaterThan(0);
        assertThat(response.getFactorAnalysis().getGroupedReturns()).hasSize(5);
        assertThat(response.getFactorAnalysis().getLongShortSpread()).isGreaterThan(0);
        assertThat(response.getFactorIcByHolding()).containsKey(5);
        assertThat(response.getFactorIcByHolding().get(5).getRankIcMean()).isGreaterThan(0);
        assertThat(response.getFormulaAudit()).isNotNull();
        assertThat(response.getFormulaAudit().get("strategy_type")).isEqualTo("CLASSIC_SELECTOR");

        // 胜率盈亏比：topN=3，3 个标的全部盈利（exit=80+rank，entry=80），应 avgWin>0、avgLoss=0。
        // 无亏损样本时盈亏比无定义，接口下发 null（历史实现给 0，会把"全胜"显示成"盈亏比 0"）。
        ScoringBacktestResponse.PeriodStat stat = response.getHoldingPeriodStats().get(5);
        assertThat(stat).isNotNull();
        assertThat(stat.getTotalObservations()).isEqualTo(3);
        assertThat(stat.getWinRate()).isEqualTo(1.0);
        assertThat(stat.getAvgWin()).isGreaterThan(0);
        assertThat(stat.getAvgLoss()).isEqualTo(0.0);
        assertThat(stat.getProfitLossRatio()).isNull();
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
        assertThat(v.get("codex_g_weighting")).isNotNull();
        assertThat(v.get("neutralization_pipeline")).isNotNull();
    }

    @Test
    void extractReasonNumberParsesNumericValues() {
        assertThat(ScoringBacktestService.extractReasonNumber(Map.of("T", 0.42), "T")).isEqualTo(0.42);
        assertThat(ScoringBacktestService.extractReasonNumber(Map.of("F", "0.15"), "F")).isEqualTo(0.15);
        assertThat(ScoringBacktestService.extractReasonNumber(Map.of(), "R")).isNull();
        assertThat(ScoringBacktestService.extractReasonNumber(null, "T")).isNull();
    }

    @Test
    void mergeCodexFactorValidationAddsSubFactorIc() {
        Map<String, Object> v = new LinkedHashMap<>();
        Map<Integer, Map<String, ScoringBacktestResponse.FactorIcSummary>> codex = Map.of(
                3, Map.of("T", ScoringBacktestResponse.FactorIcSummary.builder().rankIcMean(0.06).build()),
                5, Map.of("T", ScoringBacktestResponse.FactorIcSummary.builder().rankIcMean(0.04).build())
        );
        ScoringBacktestService.mergeCodexFactorValidation(v, codex);
        assertThat(v.get("codex_sub_factors_available")).isEqualTo(true);
        assertThat(v.get("codex_t_dominates")).isEqualTo(true);
    }

    @Test
    void selectorTradabilityReflectsResultFactorSnapshotNotHardcoded() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                mock(com.quant.repository.MarketPriceRawRepository.class),
                mock(BacktestRunRepository.class), objectMapper());

        LocalDate signalDate = LocalDate.of(2025, 1, 2);
        LocalDate entryDate = LocalDate.of(2025, 1, 3);
        SelectorResult result = new SelectorResult();
        result.setResultId("R1");
        result.setTradeDate(signalDate);
        result.setStrategyType("CLASSIC_SELECTOR");
        // 被回测结果在选股时用了 minListingDays=120、bulkFetch=false
        Map<String, Object> tradability = new LinkedHashMap<>();
        tradability.put("min_listing_days", 120);
        tradability.put("bulk_fetch", false);
        tradability.put("exclude_st", false);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("tradability", tradability);
        result.setFactorSnapshot(snapshot);

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
        @SuppressWarnings("unchecked")
        Map<String, Object> selectorTradability =
                (Map<String, Object>) response.getExecutionAssumptions().get("selector_tradability");

        // 应取自被回测结果的 factor_snapshot，而非硬编码 60/true
        assertThat(selectorTradability.get("min_listing_days")).isEqualTo(120);
        assertThat(selectorTradability.get("bulk_fetch")).isEqualTo(false);
        assertThat(selectorTradability.get("exclude_st")).isEqualTo(false);
        assertThat(selectorTradability.get("source")).isEqualTo("result_factor_snapshot");
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

    /**
     * 构造 MarketPriceRaw（成交价只读 raw，V2-MP-014）。
     * volume 默认 1_000_000（非零），表示当日正常成交。
     */
    private static MarketPriceRaw rawBar(LocalDate date, double open, double close, String code) {
        MarketPriceRaw raw = new MarketPriceRaw();
        raw.setStockCode(code);
        raw.setExchangeType("SZSE");
        raw.setTradeDate(date);
        raw.setOpenPrice(BigDecimal.valueOf(open));
        raw.setClosePrice(BigDecimal.valueOf(close));
        raw.setVolume(1_000_000L);
        return raw;
    }

    /** rawBar 的零成交量版本（测试停牌/零成交跳过场景）。 */
    private static MarketPriceRaw rawBarZeroVol(LocalDate date, double open, double close, String code) {
        MarketPriceRaw raw = rawBar(date, open, close, code);
        raw.setVolume(0L);
        return raw;
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }

    @Test
    void factorIcSummarySerializesUndefinedIcIrAsJsonNull() {
        // 与 PeriodStat.profitLossRatio 同一处理（无定义 ⟹ null）：DTO 字段不得是 primitive，
        // 否则引擎的 null 会在装箱处 NPE 或退回 0.0，把「无定义」伪装成「IR 恰为 0」（Q-R006-05）
        ScoringBacktestResponse.FactorIcSummary undefined = ScoringBacktestResponse.FactorIcSummary.builder()
                .holdingDays(2)
                .crossSectionDays(1)
                .totalObservations(10)
                .rankIcMean(1.0)
                .rankIcStd(0.0)
                .rankIcIr(null)
                .rankIcPositiveRate(1.0)
                .longShortSpread(0.01)
                .build();

        assertThat(undefined.getRankIcIr()).isNull();
        assertThat(objectMapper().valueToTree(undefined).toString()).contains("\"rankIcIr\":null");

        ScoringBacktestResponse.FactorAnalysis analysis = ScoringBacktestResponse.FactorAnalysis.builder()
                .holdingDays(2)
                .groupCount(5)
                .crossSectionDays(3)
                .totalObservations(30)
                .rankIcIr(0.5)
                .build();
        assertThat(analysis.getRankIcIr()).isEqualTo(0.5);
    }

    // ---- D-R013-03：因子分组数钳位（原实现把请求值原样透传） ----

    @Test
    void factorGroupCountIsClampedToAUsableRange() {
        // 下界 2：groupCount = 1 时「最高组」与「最低组」重合，long_short_spread 恒为 0 却被判为有效
        assertThat(ScoringBacktestService.clampFactorGroupCount(1)).isEqualTo(2);
        assertThat(ScoringBacktestService.clampFactorGroupCount(0)).isEqualTo(2);
        assertThat(ScoringBacktestService.clampFactorGroupCount(-7)).isEqualTo(2);
        // 上界 20：再多则组均值由单只标的代表
        assertThat(ScoringBacktestService.clampFactorGroupCount(21)).isEqualTo(20);
        assertThat(ScoringBacktestService.clampFactorGroupCount(500)).isEqualTo(20);
        // 区间内原样生效
        assertThat(ScoringBacktestService.clampFactorGroupCount(5)).isEqualTo(5);
        assertThat(ScoringBacktestService.clampFactorGroupCount(2)).isEqualTo(2);
        assertThat(ScoringBacktestService.clampFactorGroupCount(20)).isEqualTo(20);
        assertThat(ScoringBacktestService.DEFAULT_FACTOR_GROUP_COUNT)
                .isBetween(ScoringBacktestService.MIN_FACTOR_GROUP_COUNT,
                        ScoringBacktestService.MAX_FACTOR_GROUP_COUNT);
    }

    @Test
    void effectiveParamsRecordFlagsClampingWithoutTreatingDefaultAsClamped() {
        // 未指定（null）⟹ 走冻结默认，不算被钳位
        ScoringBacktestService.EffectiveExecutionParams defaults = new ScoringBacktestService.EffectiveExecutionParams(
                5, 5, null, 0.0003, null, 0.0005, null, 0.0002);
        assertThat(defaults.commissionRateClamped()).isFalse();
        assertThat(defaults.sellTaxRateClamped()).isFalse();
        assertThat(defaults.slippageRateClamped()).isFalse();
        assertThat(defaults.factorGroupCountClamped()).isFalse();

        // 用户显式指定 0：费率 0 是**合法**请求，不得被当成「未指定」而回落默认值
        ScoringBacktestService.EffectiveExecutionParams explicitZero =
                new ScoringBacktestService.EffectiveExecutionParams(
                        5, 5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        assertThat(explicitZero.commissionRateClamped()).isFalse();
        assertThat(explicitZero.commissionRate()).isEqualTo(0.0);

        // 负值/超上限/非有限值被改变 ⟹ 标记为已钳位
        ScoringBacktestService.EffectiveExecutionParams clamped =
                new ScoringBacktestService.EffectiveExecutionParams(
                        1, 2, -0.01, 0.0, 0.9, 0.05, Double.NaN, 0.0002);
        assertThat(clamped.commissionRateClamped()).isTrue();
        assertThat(clamped.sellTaxRateClamped()).isTrue();
        assertThat(clamped.slippageRateClamped()).isTrue();
        assertThat(clamped.factorGroupCountClamped()).isTrue();
    }

    /**
     * D-R010-06：回测计算、载荷与落库三处必须用**同一份生效值**。
     * 原实现落库写请求原值，导致同一次运行在历史列表里显示 0%、在载荷里显示 0.03%。
     */
    @Test
    void runPersistsEffectiveClampedRatesAndDisclosesRequestedValues() {
        SelectorResultRepository resultRepository = mock(SelectorResultRepository.class);
        SelectorResultItemRepository itemRepository = mock(SelectorResultItemRepository.class);
        MarketDataRepository marketDataRepository = mock(MarketDataRepository.class);
        BacktestRunRepository runRepository = mock(BacktestRunRepository.class);
        ScoringBacktestService service =
                new ScoringBacktestService(resultRepository, itemRepository, marketDataRepository,
                        mock(com.quant.repository.MarketPriceRawRepository.class),
                        runRepository, objectMapper());

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
        item.setFinalScore(80.0);

        when(resultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(result));
        when(itemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(List.of(item));
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenReturn(List.of(signalDate, entryDate, exitDate));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of(bar(signalDate, 100, 100), bar(entryDate, 80, 88), bar(exitDate, 88, 88)));
        when(marketDataRepository.findMaxTradeDate()).thenReturn(java.util.Optional.of(exitDate));

        ScoringBacktestRequest request = new ScoringBacktestRequest();
        request.setStrategyType("CLASSIC_SELECTOR");
        request.setStartDate(signalDate);
        request.setEndDate(signalDate);
        request.setTopN(5);
        request.setFactorGroupCount(1);          // 越界 → 钳到 2
        request.setCommissionRate(-0.01);        // 负值 → 钳到 0（≠ 默认 0.0003）
        request.setSellTaxRate(0.0005);          // 原样生效
        request.setSlippageRate(Double.NaN);     // 非有限 → 回落默认 0.0002

        ScoringBacktestResponse response = service.run(request);

        Map<String, Object> assumptions = response.getExecutionAssumptions();
        assertThat(assumptions.get("factor_group_count_requested")).isEqualTo(1);
        assertThat(assumptions.get("factor_group_count_effective")).isEqualTo(2);
        assertThat(assumptions.get("factor_group_count_clamped")).isEqualTo(true);
        assertThat(assumptions.get("factor_group_count_basis")).isEqualTo("CLAMPED_REQUEST_PARAM");
        assertThat(assumptions.get("commission_rate")).isEqualTo(0.0);
        assertThat(assumptions.get("commission_rate_requested")).isEqualTo(-0.01);
        assertThat(assumptions.get("commission_rate_clamped")).isEqualTo(true);
        assertThat(assumptions.get("sell_tax_rate_clamped")).isEqualTo(false);
        assertThat(assumptions.get("slippage_rate_each_side")).isEqualTo(0.0002);
        assertThat(assumptions.get("slippage_rate_each_side_clamped")).isEqualTo(true);
        assertThat(assumptions.get("rate_bounds_note")).isNotNull();

        // 落库值与载荷一致（历史实现落请求原值 ⟹ 两面对同一次运行给出不同费率）
        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(runRepository).save(captor.capture());
        BacktestRun saved = captor.getValue();
        assertThat(saved.getCommissionRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getSellTaxRate()).isEqualByComparingTo(BigDecimal.valueOf(0.0005));
        assertThat(saved.getSlippageRate()).isEqualByComparingTo(BigDecimal.valueOf(0.0002));
    }

    /**
     * D-R003-01 / D-R004-01：打分回测面的因子观测收益来自 {@code periodResult.ret()}（**已扣费**），
     * 故 {@code longShortSpread} 是**净**价差：必须声明基准、且不得再减一次两腿成本（重复扣费）。
     * 与因子研究载荷里同名的**毛**价差是「同名不同量」，跨面引用前先读基准键。
     */
    @Test
    void factorSpreadIsDeclaredAsNetAndExposesTheGrossEquivalentInstead() {
        double perLeg = ScoringBacktestService.perLegRoundTripCost();
        assertThat(perLeg).isCloseTo(0.0015, within(0.00005));

        ScoringBacktestResponse.FactorAnalysis net = ScoringBacktestResponse.FactorAnalysis.builder()
                .holdingDays(5)
                .groupCount(5)
                .crossSectionDays(10)
                .totalObservations(100)
                .rankIcMean(0.03)
                .rankIcStd(0.1)
                .rankIcIr(0.3)
                .rankIcPositiveRate(0.6)
                .longShortSpread(0.05)
                .longShortSpreadCostBasis(com.quant.service.FactorResearchService.SPREAD_COST_BASIS_NET)
                .longShortSpreadGrossEquivalent(ScoringBacktestService.grossEquivalentOfNetSpread(0.05))
                .costPerLegRoundTrip(perLeg)
                .build();

        assertThat(net.getLongShortSpreadCostBasis()).isEqualTo("NET_AFTER_MODELED_COSTS");
        assertThat(net.getLongShortSpreadGrossEquivalent()).isNotNull();
        assertThat(net.getLongShortSpreadGrossEquivalent() / 0.05).isCloseTo(1 / (1 - perLeg), within(1e-12));
        assertThat(net.getLongShortSpreadGrossEquivalent()).isGreaterThan(0.05);
        // 该键在 JSON 里可被消费方读到（不是只在载荷 map 中）
        assertThat(objectMapper().valueToTree(net).toString())
                .contains("longShortSpreadCostBasis")
                .contains("longShortSpreadGrossEquivalent");
    }

    /** 不可定义时序列化为 null（毛等价物的分母为 0/负、价差为 null 等），不得回落成 0.0。 */
    @Test
    void grossEquivalentIsNullWhenItCannotBeRecovered() {
        assertThat(ScoringBacktestService.grossEquivalentOfNetSpread(Double.NaN)).isNull();
        assertThat(com.quant.service.backtest.FactorResearchEngine
                .grossSpreadFromNet(0.05, 1.0)).isNull();
        // 正常输入下与净价差同号且绝对值更大（成本为正 ⟹ 毛 > 净）
        Double healthy = ScoringBacktestService.grossEquivalentOfNetSpread(-0.02);
        assertThat(healthy).isNotNull().isLessThan(-0.02);
    }

    /** 样本标准差口径的并列键：单截面日（T = 1）无离散度 ⟹ null，不是 0。 */
    @Test
    void sampleStdAndSampleIcIrAreNullWithoutDispersion() {
        com.quant.service.backtest.FactorResearchEngine.FactorResearchSummary singleDay =
                new com.quant.service.backtest.FactorResearchEngine.FactorResearchSummary(
                        5, 5, 1, 10, 0.03, 0.0, null, 1.0, null, "N/A",
                        0.0, 0.0, 0.0,
                        List.of(new com.quant.service.backtest.FactorResearchEngine.DailyRankIc(
                                "2025-01-02", 0.03, 10, 0)),
                        List.of(), 0.01, "note", 1, 0, 0, 0, 2, 1);

        assertThat(ScoringBacktestService.sampleStdDivTMinusOne(singleDay)).isNull();
        assertThat(ScoringBacktestService.sampleIcIr(singleDay)).isNull();
    }
}
