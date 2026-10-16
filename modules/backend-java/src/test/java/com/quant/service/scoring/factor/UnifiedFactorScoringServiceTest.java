package com.quant.service.scoring.factor;

import com.quant.entity.MarketData;
import com.quant.entity.StockInfo;
import com.quant.repository.MarketDataRepository;
import com.quant.service.PointInTimeUniverseProvider;
import com.quant.service.scoring.FinancialSnapshotLoader;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.when;

/**
 * 批次 C 测试：{@link UnifiedFactorScoringService} 生产路径（影子模式）。
 *
 * <p>用 mock 仓储喂入受控 K 线，验证端到端链路：
 * {@code MarketData} → L1 {@link FactorCalculator} → L2 {@link CrossSectionNormalizer}
 * → L3/L4 {@link UnifiedFactorScoringEngine} → 输出结构（含逐因子归因）。
 *
 * <p>不连数据库：本测试只验证「数据进来、结构出去、披露正确」，
 * 真实库上的行为差异属于批次 E 的样本外验证范围。
 */
class UnifiedFactorScoringServiceTest {

    private MarketDataRepository marketDataRepository;
    private PointInTimeUniverseProvider universeProvider;
    private FinancialSnapshotLoader financialSnapshotLoader;
    private UnifiedFactorScoringService service;

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 14);

    @BeforeEach
    void setUp() {
        marketDataRepository = mock(MarketDataRepository.class);
        universeProvider = mock(PointInTimeUniverseProvider.class);
        financialSnapshotLoader = mock(FinancialSnapshotLoader.class);
        service = new UnifiedFactorScoringService(
                marketDataRepository, universeProvider, financialSnapshotLoader);

        when(marketDataRepository.findMaxTradeDate()).thenReturn(java.util.Optional.of(TRADE_DATE));
        when(marketDataRepository.findLatestTradeDateOnOrBefore(any()))
                .thenReturn(java.util.Optional.of(TRADE_DATE));
        when(financialSnapshotLoader.loadLatestByStockKey(any())).thenReturn(Map.of());
    }

    /** 造一只股票的 K 线序列：收盘价按斜率线性变化。 */
    private static List<MarketData> bars(String code, String exchange, int n, double slope, double start) {
        List<MarketData> out = new ArrayList<>(n);
        LocalDate d = TRADE_DATE.minusDays(n - 1L);
        double prev = start;
        for (int i = 0; i < n; i++) {
            double close = start + slope * i;
            MarketData m = new MarketData();
            m.setStockCode(code);
            m.setExchangeType(exchange);
            m.setTradeDate(d.plusDays(i));
            m.setOpenPrice(BigDecimal.valueOf(close));
            m.setClosePrice(BigDecimal.valueOf(close));
            m.setHighPrice(BigDecimal.valueOf(close * 1.01));
            m.setLowPrice(BigDecimal.valueOf(close * 0.99));
            m.setPreClose(BigDecimal.valueOf(prev));
            m.setChangePct(BigDecimal.valueOf(prev > 0 ? (close - prev) / prev * 100 : 0));
            m.setVolume(1_000_000L + i * 1000L);
            m.setAmount(BigDecimal.valueOf(1_000_000d * close));
            m.setTurnoverRate(BigDecimal.valueOf(1.5 + i * 0.001));
            prev = close;
            out.add(m);
        }
        return out;
    }

    private static StockInfo info(String code, String exchange, String name) {
        StockInfo s = new StockInfo();
        s.setStockCode(code);
        s.setExchangeType(exchange);
        s.setStockName(name);
        s.setStatus(1);
        s.setListDate(LocalDate.of(2015, 1, 1));
        return s;
    }

    /** 装配一个 20 只股票、全部可交易的 universe。 */
    private void givenUniverse(int count) {
        List<MarketData> all = new ArrayList<>();
        List<StockInfo> infos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = String.format("%06d", 100000 + i);
            all.addAll(bars(code, "SZSE", 300, 0.05 + i * 0.01, 10.0 + i));
            infos.add(info(code, "SZSE", "测试股" + i));
        }
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(all);        Map<String, StockInfo> byKey = new LinkedHashMap<>();
        for (StockInfo s : infos) {
            byKey.put(s.getStockCode() + "|" + s.getExchangeType(), s);
        }
        when(universeProvider.loadAsOf(any())).thenReturn(
                new PointInTimeUniverseProvider.UniverseView(
                        TRADE_DATE, infos, byKey, "stock_info_fallback", "limited", true, infos.size()));
    }

    // ── 端到端 ────────────────────────────────────────────────────────

    @Test
    void runProducesRankedItemsWithCrossSectionalScores() {
        givenUniverse(20);

        Map<String, Object> out = service.run(Map.of(
                "tradeDate", TRADE_DATE.toString(),
                "unifiedStrategy", "CLASSIC_SELECTOR",
                "topN", 50));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(out.get("success")).isEqualTo(true);
        assertThat(summary.get("strategy")).isEqualTo("UNIFIED");
        assertThat(summary.get("unified_strategy")).isEqualTo("CLASSIC_SELECTOR");
        assertThat(summary.get("scored_count")).isEqualTo(20);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).hasSize(20);

        // 分数必须是横截面百分位且跨度满量程（对比现状 MULTI_DIM span 仅 4.48）
        List<Double> scores = new ArrayList<>();
        for (Map<String, Object> it : items) {
            assertThat(it.get("total_score")).isNotNull();
            scores.add(((Number) it.get("total_score")).doubleValue());
            assertThat(it.get("strategy_type")).isEqualTo("CLASSIC_SELECTOR");
            assertThat(it.get("score_type")).isEqualTo("rule_score");
        }
        assertThat(scores).contains(0d, 100d);

        // 降序排列
        for (int i = 1; i < scores.size(); i++) {
            assertThat(scores.get(i)).isLessThanOrEqualTo(scores.get(i - 1));
        }
    }

    @Test
    void itemsCarryPerFactorAttribution() {
        givenUniverse(20);

        Map<String, Object> out = service.run(Map.of(
                "tradeDate", TRADE_DATE.toString(),
                "unifiedStrategy", "TREND",
                "topN", 5));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).isNotEmpty();

        Map<String, Object> first = items.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> extra = (Map<String, Object>) first.get("extra_metrics");
        assertThat(extra).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contributions =
                (List<Map<String, Object>>) extra.get("factor_contributions");
        assertThat(contributions).isNotEmpty();

        // 每个因子都必须带「是否可用」，不可用的必须带原因
        for (Map<String, Object> c : contributions) {
            assertThat(c.get("factor")).isNotNull();
            assertThat(c.get("label")).isNotNull();
            assertThat(c.get("weight")).isNotNull();
            if (Boolean.FALSE.equals(c.get("available"))) {
                assertThat(c.get("unavailable_reason")).isNotNull();
                assertThat(c.get("percentile")).isNull();
            }
        }
    }

    @Test
    void structuralMissingFundamentalsAreDisclosedInSummaryAndPerItem() {
        givenUniverse(20);

        Map<String, Object> out = service.run(Map.of(
                "tradeDate", TRADE_DATE.toString(),
                "unifiedStrategy", "COMPREHENSIVE",
                "topN", 3));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> rates = (Map<String, Object>) summary.get("factor_availability_rates");
        // 本库 PE/PB 全 NULL → 这两个因子的可用率必须是 0（而不是被填成中性值）
        assertThat(((Number) rates.get("VAL_EP")).doubleValue()).isZero();
        assertThat(((Number) rates.get("VAL_BP")).doubleValue()).isZero();
        assertThat(((Number) rates.get("QUAL_ROE")).doubleValue()).isZero();

        // 逐项也要披露缺了多少因子
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        for (Map<String, Object> it : items) {
            @SuppressWarnings("unchecked")
            Map<String, Object> extra = (Map<String, Object>) it.get("extra_metrics");
            assertThat(((Number) extra.get("missing_factor_count")).intValue()).isPositive();
            assertThat(((Number) extra.get("available_weight_ratio")).doubleValue()).isLessThan(1.0);
            assertThat(extra.get("data_quality")).isEqualTo("PARTIAL_FACTORS");
        }
    }

    @Test
    void industryNeutralizationSkipIsDisclosedBecauseIndustryIsEmptyInThisDatabase() {
        givenUniverse(20);

        Map<String, Object> out = service.run(Map.of(
                "tradeDate", TRADE_DATE.toString(),
                "unifiedStrategy", "TREND",
                "topN", 3));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary.get("industry_neutralization_skipped")).isEqualTo(true);
        assertThat(String.valueOf(summary.get("industry_neutralization_skip_reason")))
                .contains("INDUSTRY_DATA_MISSING");
        assertThat(summary.get("market_cap_neutralization_skipped")).isEqualTo(true);
    }

    @Test
    void weightsAndNormalizationModeAreDisclosedForReproducibility() {
        givenUniverse(20);

        Map<String, Object> out = service.run(Map.of(
                "tradeDate", TRADE_DATE.toString(),
                "unifiedStrategy", "OVERSOLD_REBOUND",
                "topN", 3));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> weights = (Map<String, Object>) summary.get("weights");
        assertThat(weights).isNotEmpty();
        // 归一化权重绝对值之和必须为 1
        double sum = weights.values().stream().mapToDouble(v -> Math.abs(((Number) v).doubleValue())).sum();
        assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(summary.get("normalization_mode")).isNotNull();
        assertThat(summary.get("universe_fingerprint")).isNotNull();
        assertThat(summary.get("missing_data_policy")).isNotNull();
    }

    // ── 过滤与拒绝 ────────────────────────────────────────────────────

    @Test
    void stStocksAreExcludedAndTheReasonIsCounted() {
        givenUniverse(20);
        // 追加一只 ST 股
        List<MarketData> all = new ArrayList<>(bars("600001", "SSE", 300, 0.05, 10.0));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(all);
        List<StockInfo> infos = new ArrayList<>(List.of(info("600001", "SSE", "ST退市风险")));
        Map<String, StockInfo> byKey = new LinkedHashMap<>();
        byKey.put("600001|SSE", infos.get(0));
        when(universeProvider.loadAsOf(any())).thenReturn(
                new PointInTimeUniverseProvider.UniverseView(
                        TRADE_DATE, infos, byKey, "stock_info_fallback", "limited", true, 1));

        Map<String, Object> out = service.run(Map.of(
                "tradeDate", TRADE_DATE.toString(),
                "unifiedStrategy", "TREND",
                "excludeST", true));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary.get("tradability_rejected")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> breakdown = (Map<String, Object>) summary.get("rejection_breakdown");
        assertThat(breakdown).containsKey("ST_NAME");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).isEmpty();
        assertThat(out.get("success")).isEqualTo(false);
    }

    @Test
    void blankStockNameIsRejectedRatherThanSilentlyBypassingTheStGate() {
        // P0-1 回归：名称为空时旧实现会跳过 ST 闸；现在必须拒绝
        givenUniverse(1);
        List<MarketData> all = new ArrayList<>(bars("600002", "SSE", 300, 0.05, 10.0));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(all);
        StockInfo blank = info("600002", "SSE", "");
        blank.setStockName(null);
        Map<String, StockInfo> byKey = new LinkedHashMap<>();
        byKey.put("600002|SSE", blank);
        when(universeProvider.loadAsOf(any())).thenReturn(
                new PointInTimeUniverseProvider.UniverseView(
                        TRADE_DATE, List.of(blank), byKey, "stock_info_fallback", "limited", true, 1));

        Map<String, Object> out = service.run(Map.of(
                "tradeDate", TRADE_DATE.toString(),
                "unifiedStrategy", "TREND"));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary.get("tradability_rejected")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> breakdown = (Map<String, Object>) summary.get("rejection_breakdown");
        assertThat(breakdown).containsKey("MISSING_STOCK_NAME");
    }

    @Test
    void stocksWithTooFewBarsAreReportedNotScored() {
        List<MarketData> all = new ArrayList<>();
        List<StockInfo> infos = new ArrayList<>();
        // 12 只足量 + 1 只只有 10 根
        for (int i = 0; i < 12; i++) {
            String code = String.format("%06d", 200000 + i);
            all.addAll(bars(code, "SZSE", 300, 0.05 + i * 0.01, 10.0 + i));
            infos.add(info(code, "SZSE", "足量" + i));
        }
        all.addAll(bars("300001", "SZSE", 10, 0.05, 10.0));
        infos.add(info("300001", "SZSE", "短历史"));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(all);
        Map<String, StockInfo> byKey = new LinkedHashMap<>();
        for (StockInfo s : infos) {
            byKey.put(s.getStockCode() + "|" + s.getExchangeType(), s);
        }
        when(universeProvider.loadAsOf(any())).thenReturn(
                new PointInTimeUniverseProvider.UniverseView(
                        TRADE_DATE, infos, byKey, "stock_info_fallback", "limited", true, infos.size()));

        Map<String, Object> out = service.run(Map.of(
                "tradeDate", TRADE_DATE.toString(),
                "unifiedStrategy", "TREND",
                "topN", 100));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary.get("scored_count")).isEqualTo(12);
        assertThat(((Number) summary.get("rejected_count")).intValue()).isPositive();
        @SuppressWarnings("unchecked")
        Map<String, Object> fb = (Map<String, Object>) summary.get("factor_rejection_breakdown");
        assertThat(fb.keySet().stream().anyMatch(k -> k.startsWith("INSUFFICIENT_BARS"))).isTrue();
    }

    @Test
    void emptyMarketDataReturnsFailureWithoutThrowing() {
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of());
        when(universeProvider.loadAsOf(any())).thenReturn(
                new PointInTimeUniverseProvider.UniverseView(
                        TRADE_DATE, List.of(), Map.of(), "stock_info_fallback", "limited", true, 0));

        Map<String, Object> out = service.run(Map.of("tradeDate", TRADE_DATE.toString()));

        assertThat(out.get("success")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary.get("message")).isEqualTo("无可用行情数据");
    }

    @Test
    void unknownUnifiedStrategyFallsBackToClassicInsteadOfThrowing() {
        givenUniverse(20);

        Map<String, Object> out = service.run(Map.of(
                "tradeDate", TRADE_DATE.toString(),
                "unifiedStrategy", "NO_SUCH_STRATEGY",
                "topN", 3));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary.get("unified_strategy")).isEqualTo("CLASSIC_SELECTOR");
    }

    @Test
    void topNLimitsReturnedItemsButNotScoredCount() {
        givenUniverse(20);

        Map<String, Object> out = service.run(Map.of(
                "tradeDate", TRADE_DATE.toString(),
                "unifiedStrategy", "CLASSIC_SELECTOR",
                "topN", 7));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertThat(summary.get("scored_count")).isEqualTo(20);
        assertThat(summary.get("returned_count")).isEqualTo(7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertThat(items).hasSize(7);
    }
}
