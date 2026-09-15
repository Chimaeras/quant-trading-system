package com.quant.service;

import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.StockInfoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
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

    /** 裁定 B（D-R008-09）：市场换手率百分数入载荷用 `avg_turnover_pct`。 */
    private void injectEntityManager(EntityManager entityManager) throws Exception {
        Field field = DataService.class.getDeclaredField("entityManager");
        field.setAccessible(true);
        field.set(dataService, entityManager);
    }

    /**
     * 裁定 B（D-R008-09 改名）在个股行情汇总面的守卫：市场换手率**百分数**更名为
     * {@code avg_turnover_pct}（键名即量纲），与回测换手率面的候选替换率
     * {@code avg_turnover_ratio}（0–1 分数）不再同名。
     *
     * <p>用 mock 的 {@link EntityManager} 捕获真实发射的原生 SQL：别名与 `hot_score` 表达式
     * 必须引用**同一个**新别名（只改别名不改引用，会在真实库上直接报未知列）；载荷键亦然。
     * 改前 SQL 别名为 {@code avg_turnover} ⟹ 本用例改前失败（`doesNotContain("avg_turnover,")` 命中）。
     */
    @SuppressWarnings("unchecked")
    @Test
    void hotStocksNamesMarketTurnoverPercentAsPctInSqlAndPayload() throws Exception {
        EntityManager entityManager = org.mockito.Mockito.mock(EntityManager.class);
        Query query = org.mockito.Mockito.mock(Query.class);
        // 一行 15 列，取列顺序与 getHotStocks 一致
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of((Object) new Object[]{
                "000001", "平安银行", "SZSE", "银行", 20L,
                1.0e6, 2.0e7, 3.5, 1.2, 11L,
                10.5, 0.8, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5), 42.0}));
        injectEntityManager(entityManager);

        Map<String, Object> out = dataService.getHotStocks(10, 20, 20, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        String sqlText = sql.getValue();
        assertThat(sqlText).contains("AVG(COALESCE(m.turnover_rate, 0)) AS avg_turnover_pct");
        // 别名的两处引用（聚合列 + hot_score 表达式）必须与别名同步
        assertThat(sqlText).contains("ROUND(r.avg_turnover_pct, 2) AS avg_turnover_pct");
        assertThat(sqlText).contains("LEAST(GREATEST(r.avg_turnover_pct, 0) / 12, 1)");
        assertThat(sqlText).doesNotContain("avg_turnover,");
        assertThat(sqlText).doesNotContain("r.avg_turnover ");

        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).hasSize(1);
        Map<String, Object> item = items.get(0);
        assertThat(item).containsKeys("avg_turnover_pct", "avg_turnover_pct_unit", "avg_turnover_pct_unit_note");
        assertThat(item.get("avg_turnover_pct")).isEqualTo(3.5);
        assertThat(item.get("avg_turnover_pct_unit")).isEqualTo("PERCENT");
        // 旧名不得残留（破坏性契约变更，无兼容别名），且必须点明另一面是 _ratio
        assertThat(item).doesNotContainKeys("avg_turnover", "avg_turnover_unit", "avg_turnover_unit_note");
        assertThat(String.valueOf(item.get("avg_turnover_pct_unit_note"))).contains("avg_turnover_ratio");
    }
}
