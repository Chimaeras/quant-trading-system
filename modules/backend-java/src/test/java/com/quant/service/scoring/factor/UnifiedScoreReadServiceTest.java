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
 * 批次 D 测试：一次打分 → 5 个策略入口。
 *
 * <p><b>这个测试类要证明的唯一主张</b>：
 * 「5 个策略共用一次行情装载与一次特征计算，各自只做一次横截面加权」。
 *
 * <p>证明方式是**可执行的计数**而不是注释：{@link UnifiedFactorMarketLoader#loadCount()}
 * 记录累计装载次数，测试断言为 5 个策略产出结果后该值只增加 1。
 * 若将来有人把装载逻辑塞回策略循环，这个断言会失败。
 */
class UnifiedScoreReadServiceTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 14);

    private MarketDataRepository marketDataRepository;
    private UnifiedFactorMarketLoader marketLoader;
    private UnifiedScoreReadService service;
    private com.quant.repository.SelectorResultRepository selectorResultRepository;
    private com.quant.repository.SelectorResultItemRepository selectorResultItemRepository;
    private com.quant.repository.FactorCrossSectionScoreRepository crossSectionRepository;

    @BeforeEach
    void setUp() {
        marketDataRepository = mock(MarketDataRepository.class);
        selectorResultRepository = mock(com.quant.repository.SelectorResultRepository.class);
        selectorResultItemRepository = mock(com.quant.repository.SelectorResultItemRepository.class);
        crossSectionRepository = mock(com.quant.repository.FactorCrossSectionScoreRepository.class);
        PointInTimeUniverseProvider universeProvider = mock(PointInTimeUniverseProvider.class);
        FinancialSnapshotLoader snapshotLoader = mock(FinancialSnapshotLoader.class);
        marketLoader = new UnifiedFactorMarketLoader(
                marketDataRepository, universeProvider, snapshotLoader);
        service = buildService(marketLoader);

        when(marketDataRepository.findLatestTradeDateOnOrBefore(any()))
                .thenReturn(java.util.Optional.of(TRADE_DATE));
        when(marketDataRepository.findMaxTradeDate())
                .thenReturn(java.util.Optional.of(TRADE_DATE));
        when(snapshotLoader.loadLatestByStockKey(any())).thenReturn(Map.of());
        // 默认：无同日既有结果
        when(selectorResultRepository
                .findFirstByStrategyTypeAndRequestFingerprintAndTradeDateAndRunStatusOrderByCreatedAtAsc(
                        any(), any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
    }

    /** 唯一的构造点：5 个测试方法共用，避免构造签名变化时到处漏改。 */
    private UnifiedScoreReadService buildService(UnifiedFactorMarketLoader loader) {
        return new UnifiedScoreReadService(loader, new LegacyResultRowWriter(),
                new FactorCrossSectionWriter(crossSectionRepository),
                selectorResultRepository, selectorResultItemRepository);
    }

    // ── 夹具 ──────────────────────────────────────────────────────────

    /** 构造 n 根线性上涨的日线。必须真的把 bar 放进返回列表（此前这里踩过坑）。 */
    private static List<MarketData> bars(String code, String exchange, int n, double start, double step) {
        List<MarketData> out = new ArrayList<>(n);
        LocalDate d = TRADE_DATE.minusDays(n - 1L);
        double prev = start;
        for (int i = 0; i < n; i++) {
            double close = start + step * i;
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

    /** 装配 count 只可交易股票，每只 300 根日线（覆盖 oversold 的 260 根要求）。 */
    private void givenUniverse(int count, double stepBase) {
        List<MarketData> all = new ArrayList<>();
        List<StockInfo> infos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = String.format("%06d", 100000 + i);
            all.addAll(bars(code, "SZSE", 300, 10.0 + i, stepBase + i * 0.01));
            infos.add(info(code, "SZSE", "测试股" + i));
        }
        when(marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(all);

        Map<String, StockInfo> byKey = new LinkedHashMap<>();
        for (StockInfo s : infos) {
            byKey.put(s.getStockCode() + "|" + s.getExchangeType(), s);
        }
        // universe provider 需要在此重新注入
        PointInTimeUniverseProvider provider = mock(PointInTimeUniverseProvider.class);
        when(provider.loadAsOf(any())).thenReturn(new PointInTimeUniverseProvider.UniverseView(
                TRADE_DATE, infos, byKey, "stock_info_fallback", "limited", true, infos.size()));
        FinancialSnapshotLoader snapshotLoader = mock(FinancialSnapshotLoader.class);
        when(snapshotLoader.loadLatestByStockKey(any())).thenReturn(Map.of());
        marketLoader = new UnifiedFactorMarketLoader(marketDataRepository, provider, snapshotLoader);
        service = buildService(marketLoader);
    }

    private Map<String, Object> run(Map<String, Object> extra) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("tradeDate", TRADE_DATE.toString());
        req.put("topN", 50);
        req.putAll(extra);
        return service.runAllStrategies(req);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> strategiesOf(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("strategies");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> strategyBlock) {
        return (List<Map<String, Object>>) strategyBlock.get("items");
    }

    private static Map<String, Object> blockByType(List<Map<String, Object>> blocks, String type) {
        for (Map<String, Object> b : blocks) {
            if (type.equals(b.get("legacy_strategy_type"))) {
                return b;
            }
        }
        throw new IllegalStateException("未找到策略块: " + type);
    }

    // ── 测试 ──────────────────────────────────────────────────────────

    @Test
    void oneLoadProducesAllFiveStrategyBlocks() {
        givenUniverse(60, 0.05);

        Map<String, Object> out = run(Map.of());

        assertThat(out.get("success")).isEqualTo(true);
        List<Map<String, Object>> blocks = strategiesOf(out);
        assertThat(blocks).hasSize(5);
        assertThat(blocks).extracting(b -> b.get("legacy_strategy_type"))
                .containsExactly("CLASSIC_SELECTOR", "TREND", "OVERSOLD_REBOUND",
                        "COMPREHENSIVE", "MULTI_DIM");
        // 5 个策略都各自声明了 UI 入口 → 前端 5 个 tab 都有数据来源
        assertThat(blocks).extracting(b -> b.get("legacy_ui_mode"))
                .containsExactly("classic", "trend", "oversold", "comprehensive", "multi_dim");
    }

    @Test
    void marketDataIsLoadedExactlyOnceRegardlessOfStrategyCount() {
        givenUniverse(60, 0.05);
        long before = marketLoader.loadCount();

        run(Map.of());

        // 核心主张：5 个策略，装载只发生 1 次。
        // 若有人把装载塞回策略循环，这里会变成 5。
        assertThat(marketLoader.loadCount() - before).isEqualTo(1);
    }

    @Test
    void callingTwiceLoadsTwiceButStillOnlyOncePerCall() {
        givenUniverse(60, 0.05);
        long before = marketLoader.loadCount();

        run(Map.of());
        run(Map.of());

        assertThat(marketLoader.loadCount() - before).isEqualTo(2);
    }

    @Test
    void itemsCarryTheLegacyFieldNamesTheFrontendReads() {
        givenUniverse(60, 0.05);

        List<Map<String, Object>> blocks = strategiesOf(run(Map.of()));
        Map<String, Object> classic = blockByType(blocks, "CLASSIC_SELECTOR");
        assertThat(itemsOf(classic)).isNotEmpty();

        Map<String, Object> item = itemsOf(classic).get(0);
        // classic tab 用：rank_no / stock_code / stock_name / final_score
        assertThat(item).containsKeys("rank_no", "stock_code", "stock_name", "final_score");
        // 通用 tab 用
        assertThat(item).containsKeys("trend_score", "momentum_score", "volume_score",
                "ret20", "ret60", "volatility20", "max_drawdown60");
        // 旧结构里的 total_score / strategy_type 也要在
        assertThat(item).containsKeys("total_score", "strategy_type", "extra_metrics");
        assertThat(item.get("strategy_type")).isEqualTo("CLASSIC_SELECTOR");
    }

    @Test
    void oversoldTabFieldsArePresentAndDistinctFromClassic() {
        givenUniverse(60, 0.05);

        List<Map<String, Object>> blocks = strategiesOf(run(Map.of()));
        Map<String, Object> oversold = blockByType(blocks, "OVERSOLD_REBOUND");
        assertThat(itemsOf(oversold)).isNotEmpty();

        Map<String, Object> item = itemsOf(oversold).get(0);
        assertThat(item).containsKeys("trend_score", "volume_score", "momentum_score", "final_score");
        assertThat(item.get("strategy_type")).isEqualTo("OVERSOLD_REBOUND");
    }

    @Test
    void strategiesProduceTheirOwnRankingsRatherThanOneSharedOrder() {
        // 用会拉开差异的数据：部分股票单调上涨、部分先涨后跌，
        // 使动量类与风险类因子的排序相互冲突。
        List<MarketData> all = new ArrayList<>();
        List<StockInfo> infos = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String code = String.format("%06d", 200000 + i);
            double step = i % 2 == 0 ? 0.08 : -0.02;
            all.addAll(bars(code, "SZSE", 300, 10.0 + i * 0.5, step));
            infos.add(info(code, "SZSE", "差异股" + i));
        }
        when(marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(all);
        Map<String, StockInfo> byKey = new LinkedHashMap<>();
        for (StockInfo s : infos) {
            byKey.put(s.getStockCode() + "|" + s.getExchangeType(), s);
        }
        PointInTimeUniverseProvider provider = mock(PointInTimeUniverseProvider.class);
        when(provider.loadAsOf(any())).thenReturn(new PointInTimeUniverseProvider.UniverseView(
                TRADE_DATE, infos, byKey, "stock_info_fallback", "limited", true, infos.size()));
        FinancialSnapshotLoader snapshotLoader = mock(FinancialSnapshotLoader.class);
        when(snapshotLoader.loadLatestByStockKey(any())).thenReturn(Map.of());
        service = buildService(
                new UnifiedFactorMarketLoader(marketDataRepository, provider, snapshotLoader));

        List<Map<String, Object>> blocks = strategiesOf(run(Map.of()));

        List<String> classicTop = itemsOf(blockByType(blocks, "CLASSIC_SELECTOR")).stream()
                .limit(10).map(m -> String.valueOf(m.get("stock_code"))).toList();
        List<String> oversoldTop = itemsOf(blockByType(blocks, "OVERSOLD_REBOUND")).stream()
                .limit(10).map(m -> String.valueOf(m.get("stock_code"))).toList();

        // 每个策略独立做横截面加权 → 排序不应完全相同。
        // 若相同，说明"共享一次标准化"被错误实现成了"共享一个排序"，
        // 那样 5 个策略就退化成同一个策略。
        assertThat(classicTop).isNotEqualTo(oversoldTop);
    }

    @Test
    void marketMetricsArePassedThroughAndStayNullWhenUncomputed() {
        givenUniverse(60, 0.05);

        List<Map<String, Object>> blocks = strategiesOf(run(Map.of()));
        Map<String, Object> item = itemsOf(blockByType(blocks, "CLASSIC_SELECTOR")).get(0);

        // 本夹具数据充足 → ret20/ret60 应有真实值（不是 null 也不是 0）
        assertThat(item.get("ret20")).isInstanceOf(Number.class);
        assertThat(((Number) item.get("ret20")).doubleValue()).isNotZero();
        assertThat(item.get("ret60")).isInstanceOf(Number.class);
        // 波动率与回撤由真实序列算出，应为正数
        assertThat(((Number) item.get("volatility20")).doubleValue()).isGreaterThan(0);
    }

    @Test
    void contributionsCanBeSuppressedForSmallerResponses() {
        givenUniverse(60, 0.05);

        Map<String, Object> withContrib = run(Map.of("includeContributions", true));
        Map<String, Object> without = run(Map.of("includeContributions", false));

        Map<String, Object> a = itemsOf(blockByType(strategiesOf(withContrib), "CLASSIC_SELECTOR")).get(0);
        Map<String, Object> b = itemsOf(blockByType(strategiesOf(without), "CLASSIC_SELECTOR")).get(0);

        assertThat(extraOf(a)).containsKey("factor_contributions");
        assertThat(extraOf(b)).doesNotContainKey("factor_contributions");
        // 其余披露字段必须保留——关掉明细不等于关掉可解释性
        assertThat(extraOf(b)).containsKeys("unified_engine", "score_semantics", "data_quality");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extraOf(Map<String, Object> item) {
        return (Map<String, Object>) item.get("extra_metrics");
    }

    @Test
    void everyItemIsMarkedAsComingFromTheUnifiedEngine() {
        givenUniverse(60, 0.05);

        List<Map<String, Object>> blocks = strategiesOf(run(Map.of()));
        for (Map<String, Object> block : blocks) {
            for (Map<String, Object> item : itemsOf(block)) {
                Map<String, Object> extra = extraOf(item);
                assertThat(extra.get("unified_engine")).isEqualTo(true);
                // 语义边界必须随每条结果下发，避免"字段名相同含义不同"被误读
                assertThat(String.valueOf(extra.get("score_semantics"))).contains("不同");
                assertThat(extra).containsKeys("available_weight_ratio", "missing_factor_count");
            }
        }
    }

    @Test
    void itemsAreSortedByFinalScoreDescendingWithStableTieBreak() {        givenUniverse(60, 0.05);

        List<Map<String, Object>> items =
                itemsOf(blockByType(strategiesOf(run(Map.of())), "CLASSIC_SELECTOR"));

        List<Double> scores = items.stream()
                .map(m -> ((Number) m.get("final_score")).doubleValue())
                .toList();
        for (int i = 1; i < scores.size(); i++) {
            assertThat(scores.get(i)).as("第 %d 项不得高于前一项", i)
                    .isLessThanOrEqualTo(scores.get(i - 1));
        }
        // 排名连续且从 1 开始
        for (int i = 0; i < items.size(); i++) {
            assertThat(items.get(i).get("rank_no")).isEqualTo(i + 1);
        }
    }

    @Test
    void emptyMarketDataReturnsDisclosureInsteadOfThrowing() {
        when(marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(List.of());

        Map<String, Object> out = run(Map.of());

        assertThat(out.get("success")).isEqualTo(true);
        assertThat(strategiesOf(out)).isEmpty();
        assertThat(String.valueOf(out.get("message"))).isNotBlank();
        // 仍然只装载一次（早退不得导致重复装载）
        assertThat(marketLoader.loadCount()).isEqualTo(1);
    }

    @Test
    void blockedCandidatesAreDisclosedRatherThanSilentlyDropped() {
        List<MarketData> all = new ArrayList<>();
        List<StockInfo> infos = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String code = String.format("%06d", 300000 + i);
            all.addAll(bars(code, "SZSE", 300, 10.0 + i, 0.05));
            infos.add(info(code, "SZSE", "正常股" + i));
        }
        // 一只 ST 股：必须被排除且原因被计数
        String stCode = "399999";
        all.addAll(bars(stCode, "SZSE", 300, 20.0, 0.05));
        infos.add(info(stCode, "SZSE", "ST退市风险"));

        when(marketDataRepository
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(any(), any()))
                .thenReturn(all);
        Map<String, StockInfo> byKey = new LinkedHashMap<>();
        for (StockInfo s : infos) {
            byKey.put(s.getStockCode() + "|" + s.getExchangeType(), s);
        }
        PointInTimeUniverseProvider provider = mock(PointInTimeUniverseProvider.class);
        when(provider.loadAsOf(any())).thenReturn(new PointInTimeUniverseProvider.UniverseView(
                TRADE_DATE, infos, byKey, "stock_info_fallback", "limited", true, infos.size()));
        FinancialSnapshotLoader snapshotLoader = mock(FinancialSnapshotLoader.class);
        when(snapshotLoader.loadLatestByStockKey(any())).thenReturn(Map.of());
        service = buildService(
                new UnifiedFactorMarketLoader(marketDataRepository, provider, snapshotLoader));

        Map<String, Object> out = run(Map.of("excludeST", true));

        assertThat(((Number) out.get("tradability_rejected")).intValue()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> breakdown = (Map<String, Object>) out.get("rejection_breakdown");
        assertThat(breakdown).containsKey("ST_NAME");

        // ST 股不得出现在任何策略结果里
        for (Map<String, Object> block : strategiesOf(out)) {
            for (Map<String, Object> item : itemsOf(block)) {
                assertThat(item.get("stock_code")).isNotEqualTo(stCode);
            }
        }
    }

    // ── 落库兼容性（"与原落库结构兼容"这一硬约束的执行点）─────────────────

    @Test
    void runAllStrategiesIsNotReadOnlyTransactionalBecausePersistWrites() throws Exception {
        // 回归：本方法曾被标为 @Transactional(readOnly = true)，而 persist=true 时它要写库，
        // 于是真实运行时报 "Connection is read-only"（Hikari 拒绝写），
        // 单元测试全绿但 HTTP 500。这个断言把事务属性变成可测的契约。
        java.lang.reflect.Method m = UnifiedScoreReadService.class
                .getMethod("runAllStrategies", Map.class);
        org.springframework.transaction.annotation.Transactional tx =
                m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).as("runAllStrategies 必须声明 @Transactional").isNotNull();
        assertThat(tx.readOnly())
                .as("runAllStrategies 不得声明 readOnly=true —— persist=true 需要写库")
                .isFalse();
    }

    @Test
    void persistIsOffByDefaultSoShadowModeStaysReadOnly() {
        givenUniverse(60, 0.05);

        Map<String, Object> out = run(Map.of());

        assertThat(out.get("persisted")).isEqualTo(false);
        // 默认路径绝不能碰库——影子模式的全部意义就在这里
        org.mockito.Mockito.verify(selectorResultRepository, org.mockito.Mockito.never())
                .save(any(com.quant.entity.SelectorResult.class));
        org.mockito.Mockito.verify(selectorResultItemRepository, org.mockito.Mockito.never())
                .save(any(com.quant.entity.SelectorResultItem.class));
    }

    @Test
    void persistWritesOneSelectorResultPerStrategyWithLegacyStrategyType() {
        givenUniverse(60, 0.05);

        Map<String, Object> out = run(Map.of("persist", true));

        assertThat(out.get("persisted")).isEqualTo(true);
        // 每个策略一条 selector_result：strategy_type 是旧表的分组键，合并成一条前端取不到
        org.mockito.ArgumentCaptor<com.quant.entity.SelectorResult> cap =
                org.mockito.ArgumentCaptor.forClass(com.quant.entity.SelectorResult.class);
        org.mockito.Mockito.verify(selectorResultRepository, org.mockito.Mockito.atLeast(5)).save(cap.capture());

        List<String> types = cap.getAllValues().stream()
                .map(com.quant.entity.SelectorResult::getStrategyType).distinct().toList();
        assertThat(types).containsExactlyInAnyOrder(
                "CLASSIC_SELECTOR", "TREND", "OVERSOLD_REBOUND", "COMPREHENSIVE", "MULTI_DIM");
        // 落库的 factor_version 必须是统一版本号，能与旧版本区分
        assertThat(cap.getAllValues()).allSatisfy(sr ->
                assertThat(sr.getFactorVersion()).isEqualTo(UnifiedScoreReadService.FACTOR_VERSION));
    }

    @Test
    void persistedRowsCarryTheLegacyColumnsTheFrontendReads() {
        givenUniverse(60, 0.05);

        run(Map.of("persist", true));

        org.mockito.ArgumentCaptor<com.quant.entity.SelectorResultItem> cap =
                org.mockito.ArgumentCaptor.forClass(com.quant.entity.SelectorResultItem.class);
        org.mockito.Mockito.verify(selectorResultItemRepository, org.mockito.Mockito.atLeastOnce())
                .save(cap.capture());

        assertThat(cap.getAllValues()).isNotEmpty();
        com.quant.entity.SelectorResultItem first = cap.getAllValues().get(0);
        // 旧列必须有值，且策略名是旧口径
        assertThat(first.getStrategyType()).isNotBlank();
        assertThat(first.getStockCode()).isNotBlank();
        assertThat(first.getResultId()).isNotBlank();
        assertThat(first.getRankNo()).isNotNull();
        assertThat(first.getFinalScore()).isNotNull();
        // 统一引擎的可解释性必须一起落库，否则前端只能看到分数
        assertThat(first.getExtraMetrics()).containsKey("factor_contributions");
        assertThat(first.getExtraMetrics()).containsKey("unified_engine");
    }

    @Test
    void persistDeduplicatesInsteadOfCreatingTheOldDuplicateStorm() {
        givenUniverse(60, 0.05);
        // 模拟"同日同参数已有成功结果"——这正是旧实现漏掉的那一步
        com.quant.entity.SelectorResult existing = new com.quant.entity.SelectorResult();
        existing.setResultId("EXISTING_ALL");
        when(selectorResultRepository
                .findFirstByStrategyTypeAndRequestFingerprintAndTradeDateAndRunStatusOrderByCreatedAtAsc(
                        any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(existing));

        Map<String, Object> out = run(Map.of("persist", true));

        List<Map<String, Object>> blocks = strategiesOf(out);
        for (Map<String, Object> block : blocks) {
            @SuppressWarnings("unchecked")
            Map<String, Object> po = (Map<String, Object>) block.get("persist_outcome");
            assertThat(po.get("deduplicated")).isEqualTo(true);
            assertThat(po.get("result_id")).isEqualTo("EXISTING_ALL");
            assertThat(((Number) po.get("inserted_items")).intValue()).isZero();
        }
        // 命中既有结果时一行都不许写
        org.mockito.Mockito.verify(selectorResultItemRepository, org.mockito.Mockito.never())
                .save(any(com.quant.entity.SelectorResultItem.class));
    }

    @Test
    void dedupeFalseForcesAFreshWrite() {
        givenUniverse(60, 0.05);
        com.quant.entity.SelectorResult existing = new com.quant.entity.SelectorResult();
        existing.setResultId("EXISTING_ALL");
        when(selectorResultRepository
                .findFirstByStrategyTypeAndRequestFingerprintAndTradeDateAndRunStatusOrderByCreatedAtAsc(
                        any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(existing));

        Map<String, Object> out = run(Map.of("persist", true, "dedupe", false));

        for (Map<String, Object> block : strategiesOf(out)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> po = (Map<String, Object>) block.get("persist_outcome");
            assertThat(po.get("deduplicated")).isEqualTo(false);
            assertThat(po.get("result_id")).isNotEqualTo("EXISTING_ALL");
        }
    }

    @Test
    void fingerprintIgnoresOutputOnlyParametersButTracksScoringInputs() {
        java.time.LocalDate d = TRADE_DATE;

        String base = UnifiedScoreReadService.fingerprint(d, Map.of());
        // 只影响输出多少/写不写库的参数不得改变指纹，否则改一次 topN 就会重复落库
        assertThat(UnifiedScoreReadService.fingerprint(d, Map.of("topN", 999)))
                .isEqualTo(base);
        assertThat(UnifiedScoreReadService.fingerprint(d, Map.of("persist", true, "dedupe", false)))
                .isEqualTo(base);
        assertThat(UnifiedScoreReadService.fingerprint(d, Map.of("includeContributions", false)))
                .isEqualTo(base);

        // 真正影响打分口径的参数必须改变指纹
        assertThat(UnifiedScoreReadService.fingerprint(d, Map.of("lookbackDays", 120)))
                .isNotEqualTo(base);
        assertThat(UnifiedScoreReadService.fingerprint(d, Map.of("excludeST", false)))
                .isNotEqualTo(base);
        assertThat(UnifiedScoreReadService.fingerprint(d, Map.of("minListingDays", 0)))
                .isNotEqualTo(base);
        // 不同交易日必须不同指纹，防止跨日误判为同一请求
        assertThat(UnifiedScoreReadService.fingerprint(d.minusDays(1), Map.of()))
                .isNotEqualTo(base);
    }

    @Test
    void persistKeepsNullMarketMetricsAsNullRatherThanZero() {
        givenUniverse(60, 0.05);

        run(Map.of("persist", true));

        org.mockito.ArgumentCaptor<com.quant.entity.SelectorResultItem> cap =
                org.mockito.ArgumentCaptor.forClass(com.quant.entity.SelectorResultItem.class);
        org.mockito.Mockito.verify(selectorResultItemRepository, org.mockito.Mockito.atLeastOnce())
                .save(cap.capture());

        // 本夹具数据充足，这些列应为真实数值而非占位 0。
        // 特别断言 volatility20：实测其值为 0.0024 量级，
        // 若写入路径做了 2 位小数取整会被压成 0.0（与"未计算"不可区分）——这个断言就是防它。
        com.quant.entity.SelectorResultItem it = cap.getAllValues().get(0);
        assertThat(it.getRet20()).isNotNull();
        assertThat(it.getRet20()).isNotEqualTo(0.0);
        assertThat(it.getVolatility20()).isNotNull();
        assertThat(it.getVolatility20()).isNotEqualTo(0.0);
        assertThat(it.getVolatility20()).isLessThan(1.0);
        // 注：不再断言 max_drawdown60 != 0 —— 单调上涨序列的 60 日最大回撤**就是 0**，
        // 0 在这里是真实值而非占位。这恰好说明"用 0 判断未计算"本身不可靠，
        // 必须靠 NULL 才能区分（A-3 的立论依据）。
        assertThat(it.getMaxDrawdown60()).isNotNull();
    }

    // ── 全截面落库（Rank IC 验证通路的前置条件）────────────────────────

    @Test
    void crossSectionIsOffByDefaultSoOrdinaryScoringStaysCheap() {
        givenUniverse(60, 0.05);

        Map<String, Object> out = run(Map.of());

        assertThat(out.get("cross_section_persisted")).isEqualTo(false);
        // 默认路径不得产生任何全截面写入（体量约 2.7 万行/日）
        org.mockito.Mockito.verify(crossSectionRepository, org.mockito.Mockito.never())
                .saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void includeCrossSectionWritesEveryStrategyWithFullUniverseNotJustTopN() {
        givenUniverse(60, 0.05);

        // topN 故意设得很小：全截面行数必须**不受 topN 影响**，
        // 否则 Rank IC 又只能看到头部——这正是本参数存在的理由。
        Map<String, Object> out = run(Map.of("includeCrossSection", true, "topN", 3));

        assertThat(out.get("cross_section_persisted")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = strategiesOf(out);
        assertThat(blocks).hasSize(5);
        for (Map<String, Object> b : blocks) {
            assertThat(itemsOf(b)).hasSize(3);                   // 展示仍是 topN
            assertThat((Integer) b.get("cross_section_rows")).isGreaterThan(3); // 落库是全截面
        }

        // 5 个策略各一次删旧 + 一次 saveAll
        org.mockito.Mockito.verify(crossSectionRepository, org.mockito.Mockito.times(5))
                .deleteCrossSection(any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void crossSectionRowsAreRankedAcrossTheWholeUniverseAndKeepIneligibleRows() {
        givenUniverse(60, 0.05);

        run(Map.of("includeCrossSection", true, "topN", 3));

        org.mockito.ArgumentCaptor<List<com.quant.entity.FactorCrossSectionScore>> cap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(crossSectionRepository, org.mockito.Mockito.times(5))
                .saveAll(cap.capture());

        List<com.quant.entity.FactorCrossSectionScore> first = cap.getAllValues().get(0);
        // 60 只全部落库（含不合格者），不是 topN=3
        assertThat(first).hasSize(60);

        // 名次必须覆盖全截面且连续无重复
        List<Integer> ranks = first.stream()
                .map(com.quant.entity.FactorCrossSectionScore::getCrossSectionRank).toList();
        assertThat(ranks).doesNotHaveDuplicates();
        assertThat(ranks).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 60).boxed().toList());

        // 合格者在前且分数降序
        List<Double> eligibleScores = first.stream()
                .filter(r -> Boolean.TRUE.equals(r.getEligible()))
                .map(com.quant.entity.FactorCrossSectionScore::getFinalScore)
                .filter(java.util.Objects::nonNull)
                .toList();
        assertThat(eligibleScores).isSortedAccordingTo(java.util.Comparator.reverseOrder());

        // 元数据齐全，使研究侧可脱离 item 表独立查询
        for (com.quant.entity.FactorCrossSectionScore r : first) {
            assertThat(r.getTradeDate()).isEqualTo(TRADE_DATE);
            assertThat(r.getStrategyCode()).isEqualTo("CLASSIC_SELECTOR");
            assertThat(r.getLegacyStrategyType()).isNotBlank();
            assertThat(r.getFactorVersion()).isNotBlank();
            assertThat(r.getStockCode()).isNotBlank();
            assertThat(r.getRawScore()).isNotNull();
        }
    }

    /**
     * 逐因子百分位必须随全截面落库，否则阶段 1 的**单因子** IC 诊断无法进行。
     *
     * <p>同时锁定 V33 声明的区分语义：不可用因子**不写键**，
     * 而不是写 null——否则"因子不可用"会与"百分位恰为 0"混同，污染 IC 样本。
     */
    @Test
    @SuppressWarnings("unchecked")
    void crossSectionRowsCarryPerFactorPercentilesKeyedByFactorId() {
        givenUniverse(60, 0.05);

        run(Map.of("includeCrossSection", true, "topN", 3));

        org.mockito.ArgumentCaptor<List<com.quant.entity.FactorCrossSectionScore>> cap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(crossSectionRepository, org.mockito.Mockito.times(5))
                .saveAll(cap.capture());

        List<com.quant.entity.FactorCrossSectionScore> rows = cap.getAllValues().get(0);
        List<Map<String, Double>> withPercentiles = rows.stream()
                .map(com.quant.entity.FactorCrossSectionScore::getFactorPercentiles)
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(withPercentiles)
                .as("至少应有合格股票带上逐因子百分位")
                .isNotEmpty();

        Map<String, Double> sample = withPercentiles.get(0);
        // 键必须是 FactorId 的枚举名，阶段 1 的 Python 侧按此名索引
        assertThat(sample.keySet()).allMatch(k -> k.equals(k.toUpperCase()) && k.indexOf(' ') < 0);
        assertThat(sample.keySet()).isNotEmpty();
        for (Map.Entry<String, Double> e : sample.entrySet()) {
            assertThat(e.getValue())
                    .as("百分位必须在 [0,100]：因子 %s", e.getKey())
                    .isNotNull()
                    .isBetween(0.0, 100.0);
        }
        // CLASSIC 策略权重里含 MOM_20 等因子，至少应出现一个已知因子名
        assertThat(sample.keySet()).containsAnyOf("MOM_20", "MOM_5", "TREND_MA", "VOL_RATIO");
        // 不可用因子不得以 null 值出现（必须是键缺失）
        assertThat(sample.values()).doesNotContainNull();
    }

    /**
     * 重跑同一 (日, 策略) 时，bulk delete 必须先于 saveAll 到达数据库。
     *
     * <p><b>为什么用反射断言注解而不是行为断言</b>：删除与插入的**语句顺序**
     * 由 Hibernate 的 ActionQueue 与 flush 时机决定，mock 的 Repository 不经过
     * 持久化上下文，任何行为断言都碰不到这个顺序。而该顺序正是缺陷的全部内容：
     * 实测（2026-10-15 首次真实运行）首次写成功、**第二次写必报**
     * {@code Duplicate entry ... for key 'uk_css_day_strategy_stock'}。
     *
     * <p>因此这里锁定 {@code @Modifying(flushAutomatically = true)}。
     * 若有人把它删回默认值，本测试失败——这正是它存在的意义。
     */
    @Test
    void crossSectionDeleteMustFlushBeforeInsertOrRerunHitsDuplicateKey() throws Exception {
        java.lang.reflect.Method m = com.quant.repository.FactorCrossSectionScoreRepository.class
                .getMethod("deleteCrossSection", java.time.LocalDate.class, String.class);

        org.springframework.data.jpa.repository.Modifying mod =
                m.getAnnotation(org.springframework.data.jpa.repository.Modifying.class);

        assertThat(mod)
                .as("deleteCrossSection 必须标注 @Modifying，否则 Spring Data 会把它当查询执行")
                .isNotNull();
        assertThat(mod.flushAutomatically())
                .as("必须 flushAutomatically=true：否则 saveAll 的 INSERT 可能先于 bulk DELETE 到达数据库，"
                        + "重跑同一 (日, 策略) 时撞唯一键")
                .isTrue();
    }
}
