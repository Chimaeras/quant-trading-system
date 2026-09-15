package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.FeatureSnapshotManifestRepository;
import com.quant.repository.FeatureSnapshotRowRepository;
import com.quant.repository.LabelSnapshotRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.research.FeatureSchemaRegistry;
import com.quant.service.DataSnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FactorResearchServiceTest {

    @Mock
    private SelectorResultRepository selectorResultRepository;
    @Mock
    private SelectorResultItemRepository selectorResultItemRepository;
    @Mock
    private MarketDataRepository marketDataRepository;
    @Mock
    private MarketPriceRawRepository marketPriceRawRepository;
    @Mock
    private FeatureSnapshotManifestRepository featureSnapshotManifestRepository;
    @Mock
    private FeatureSnapshotRowRepository featureSnapshotRowRepository;
    @Mock
    private LabelSnapshotRepository labelSnapshotRepository;
    @Mock
    private FeatureSchemaRegistry featureSchemaRegistry;
    @Mock
    private DataSnapshotService dataSnapshotService;

    private FactorResearchService service() {
        return new FactorResearchService(
                selectorResultRepository,
                selectorResultItemRepository,
                marketDataRepository,
                marketPriceRawRepository,
                featureSnapshotManifestRepository,
                featureSnapshotRowRepository,
                labelSnapshotRepository,
                featureSchemaRegistry,
                dataSnapshotService);
    }

    private static SelectorResult result(String resultId, String strategy, LocalDate tradeDate) {
        SelectorResult r = new SelectorResult();
        r.setResultId(resultId);
        r.setStrategyType(strategy);
        r.setTradeDate(tradeDate);
        return r;
    }

    private static SelectorResultItem item(String resultId, double score, String code, String ex) {
        SelectorResultItem it = new SelectorResultItem();
        it.setResultId(resultId);
        it.setFinalScore(score);
        it.setStockCode(code);
        it.setExchangeType(ex);
        return it;
    }

    private static MarketData bar(String code, String ex, LocalDate date, double close) {
        MarketData m = new MarketData();
        m.setStockCode(code);
        m.setExchangeType(ex);
        m.setTradeDate(date);
        m.setClosePrice(BigDecimal.valueOf(close));
        return m;
    }

    private static MarketData openBar(String code, String ex, LocalDate date, double open) {
        MarketData m = new MarketData();
        m.setStockCode(code);
        m.setExchangeType(ex);
        m.setTradeDate(date);
        m.setOpenPrice(BigDecimal.valueOf(open));
        return m;
    }

    private static MarketPriceRaw rawBar(String code, String ex, LocalDate date, double close) {
        MarketPriceRaw m = new MarketPriceRaw();
        m.setStockCode(code);
        m.setExchangeType(ex);
        m.setTradeDate(date);
        m.setClosePrice(BigDecimal.valueOf(close));
        return m;
    }

    private static MarketPriceRaw rawOpen(String code, String ex, LocalDate date, double open) {
        MarketPriceRaw m = new MarketPriceRaw();
        m.setStockCode(code);
        m.setExchangeType(ex);
        m.setTradeDate(date);
        m.setOpenPrice(BigDecimal.valueOf(open));
        return m;
    }

    @Test
    void researchComputesCoverageAndDistribution() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of(result("R1", "CLASSIC_SELECTOR", d)));
        // 4 个候选，1 个 score=0（视为无效），3 个有效：60/70/80
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 60.0, "000001", "SZSE"),
                        item("R1", 70.0, "000002", "SZSE"),
                        item("R1", 80.0, "600519", "SSE"),
                        item("R1", 0.0, "000003", "SZSE")
                ));
        // Rank IC：信号日 2025-01-02，holding=5，退出日为交易日历第 5 个交易日
        LocalDate calEnd = d.plusDays(5 * 2L);
        when(marketDataRepository.findDistinctTradeDatesBetween(eq(d), eq(calEnd)))
                .thenReturn(List.of(d, d.plusDays(1), d.plusDays(2), d.plusDays(3),
                        d.plusDays(4), d.plusDays(5)));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                eq(d), eq(calEnd)))
                .thenReturn(List.of());
        // loadPriceCache 也会拉取 market_price_raw；此处无 raw 行情
        when(marketPriceRawRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                eq(d), eq(calEnd)))
                .thenReturn(List.of());

        Map<String, Object> out = service().research("CLASSIC_SELECTOR", d, d);

        assertThat(out.get("strategy")).isEqualTo("CLASSIC_SELECTOR");
        assertThat(out.get("total_observations")).isEqualTo(4);
        assertThat(out.get("valid_observations")).isEqualTo(3);
        assertThat(out.get("coverage")).isEqualTo(75.0);

        @SuppressWarnings("unchecked")
        Map<String, Object> dist = (Map<String, Object>) out.get("distribution");
        assertThat(dist.get("min")).isEqualTo(60.0);
        assertThat(dist.get("max")).isEqualTo(80.0);
        assertThat(dist.get("median")).isEqualTo(70.0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bands = (List<Map<String, Object>>) out.get("group_bands");
        assertThat(bands).isNotEmpty();
        assertThat(bands.get(0).get("min_score")).isEqualTo(60.0);
        int totalBandCount = bands.stream().mapToInt(b -> (int) b.get("count")).sum();
        assertThat(totalBandCount).isEqualTo(3);

        // rank_ic 块存在但无可用 forward return（行情为空），截面日为 0
        @SuppressWarnings("unchecked")
        Map<String, Object> rankIc = (Map<String, Object>) out.get("rank_ic");
        assertThat(rankIc.get("holding_days")).isEqualTo(5);
        assertThat(rankIc.get("cross_section_days")).isEqualTo(0);
        assertThat(rankIc.get("basis_note")).asString().contains("close[t+h]");
    }

    @Test
    void unifiedReportComputesExecutableNetIc() {
        // 4 个交易日 t..t3；h=2 时毛收益退出日=t2，可执行净收益 next-open 入场=t1、退出日=t3
        LocalDate t = LocalDate.of(2025, 1, 2);
        LocalDate t1 = LocalDate.of(2025, 1, 3);
        LocalDate t2 = LocalDate.of(2025, 1, 4);
        LocalDate t3 = LocalDate.of(2025, 1, 5);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(t, t))
                .thenReturn(List.of(result("R1", "CLASSIC_SELECTOR", t)));

        List<SelectorResultItem> items = new ArrayList<>();
        List<String> codes = List.of("000001", "000002", "000003", "000004", "000005",
                "600519", "600518", "600517", "600516", "600515");
        for (int i = 0; i < 10; i++) {
            items.add(item("R1", 10.0 * (i + 1), codes.get(i), i < 5 ? "SZSE" : "SSE"));
        }
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(items);
        when(marketDataRepository.findDistinctTradeDatesBetween(any(), any()))
                .thenAnswer(inv -> List.of(t, t1, t2, t3));
        // 批量行情：10 只股票各 4 根 K 线（close@t / open@t1 / close@t2 / close@t3）
        List<MarketData> bulkBars = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String code = codes.get(i);
            String ex = i < 5 ? "SZSE" : "SSE";
            bulkBars.add(bar(code, ex, t, 10.0));             // close@t
            bulkBars.add(openBar(code, ex, t1, 10.0));        // open@t1 (净收益入场)
            bulkBars.add(bar(code, ex, t2, 10.0 + i * 0.01)); // close@t2 (毛收益退出)
            bulkBars.add(bar(code, ex, t3, 10.0 + i * 0.01)); // close@t3 (净收益退出)
        }
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                any(), any()))
                .thenReturn(bulkBars);
        // V2-MP-014：净收益成交价只读 market_price_raw（不复权），需提供 raw open@t1 / close@t3
        List<MarketPriceRaw> bulkRaw = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String code = codes.get(i);
            String ex = i < 5 ? "SZSE" : "SSE";
            bulkRaw.add(rawOpen(code, ex, t1, 10.0));              // open@t1 (净收益入场)
            bulkRaw.add(rawBar(code, ex, t3, 10.0 + i * 0.01));    // close@t3 (净收益退出)
        }
        when(marketPriceRawRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                any(), any()))
                .thenReturn(bulkRaw);

        Map<String, Object> out = service().unifiedReport("CLASSIC_SELECTOR", t, t);

        assertThat(out.get("horizons")).isEqualTo(List.of(1, 2, 3, 5));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byHorizon = (List<Map<String, Object>>) out.get("by_horizon");
        assertThat(byHorizon).hasSize(4);
        for (Map<String, Object> hb : byHorizon) {
            assertThat(hb.get("net_basis_note")).asString().contains("佣金");
            assertThat(hb.get("embargo_note")).asString().contains("不外推");
            @SuppressWarnings("unchecked")
            Map<String, Object> net = (Map<String, Object>) hb.get("cost_net_ic");
            // V2-MP-014：净收益成交价改读 raw，basis_note 文案为 rawOpen[t+1]
            assertThat(net.get("basis_note")).asString().contains("rawOpen[t+1]");
            if (Integer.valueOf(2).equals(hb.get("holding_days"))) {
                // h=2：netEntry=t1(open 有值)、netExit=t3(close 有值) → 有净收益观测
                assertThat((int) net.get("cross_section_days")).isGreaterThan(0);
            }
        }
    }

    @Test
    void researchReturnsZerosWhenNoData() {
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of());

        Map<String, Object> out = service().research(null, d, d);

        assertThat(out.get("total_observations")).isEqualTo(0);
        assertThat(out.get("coverage")).isEqualTo(0.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> dist = (Map<String, Object>) out.get("distribution");
        assertThat(dist.get("median")).isEqualTo(0.0);
    }

    @Test
    void rankIcComputedFromForwardReturns() {
        // 信号日 t=2025-01-02，holding=2，退出日 = 交易日历第 2 个交易日 = 2025-01-04
        // 10 只候选（满足 RANK_IC_MIN_CROSS_SECTION_SIZE=10）：得分与 forward return 完全同向 → IC=1.0
        LocalDate t = LocalDate.of(2025, 1, 2);
        LocalDate t1 = LocalDate.of(2025, 1, 3);
        LocalDate t2 = LocalDate.of(2025, 1, 4);
        LocalDate calEnd = t.plusDays(2 * 2L);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(t, t))
                .thenReturn(List.of(result("R1", "CLASSIC_SELECTOR", t)));

        List<SelectorResultItem> items = new ArrayList<>();
        List<String> codes = List.of("000001", "000002", "000003", "000004", "000005",
                "600519", "600518", "600517", "600516", "600515");
        for (int i = 0; i < 10; i++) {
            // score 随 i 递增，forward return 也随 i 递增（close[t]=10，close[t2]=10+i*0.01 → return=i*0.001）
            items.add(item("R1", 10.0 * (i + 1), codes.get(i), i < 5 ? "SZSE" : "SSE"));
        }
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(items);
        when(marketDataRepository.findDistinctTradeDatesBetween(eq(t), eq(calEnd)))
                .thenReturn(List.of(t, t1, t2));
        // 批量行情：10 只股票各 2 根 K 线（close@t / close@t2）
        List<MarketData> bulkBars = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String code = codes.get(i);
            String ex = i < 5 ? "SZSE" : "SSE";
            bulkBars.add(bar(code, ex, t, 10.0));
            bulkBars.add(bar(code, ex, t2, 10.0 + i * 0.01)); // return = i * 0.001
        }
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                eq(t), eq(calEnd)))
                .thenReturn(bulkBars);
        // 本用例只校验毛收益 IC，无需 raw 行情（loadPriceCache 仍会拉取 raw，返回空避免 NPE）
        when(marketPriceRawRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                eq(t), eq(calEnd)))
                .thenReturn(List.of());

        Map<String, Object> out = service().research("CLASSIC_SELECTOR", t, t, 2);

        @SuppressWarnings("unchecked")
        Map<String, Object> rankIc = (Map<String, Object>) out.get("rank_ic");
        assertThat(rankIc.get("holding_days")).isEqualTo(2);
        assertThat(rankIc.get("cross_section_days")).isEqualTo(1);
        assertThat(rankIc.get("total_observations")).isEqualTo(10);
        // 得分与 forward return 完全同向 → IC=1.0
        assertThat((double) rankIc.get("rank_ic_mean")).isEqualTo(1.0);
        assertThat((double) rankIc.get("rank_ic_positive_rate")).isEqualTo(1.0);
        // 两腿价差 = 高分组均值 − 低分组均值 > 0（两个多头腿之差，非美元中性多空）
        assertThat((double) rankIc.get("two_long_leg_spread")).isGreaterThan(0.0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) rankIc.get("daily_rank_ic");
        assertThat(daily).hasSize(1);
        assertThat(daily.get(0).get("trade_date")).isEqualTo(t.toString());
        assertThat((double) daily.get(0).get("rank_ic")).isEqualTo(1.0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grouped = (List<Map<String, Object>>) rankIc.get("grouped_returns");
        assertThat(grouped).isNotEmpty();
    }

    @Test
    void rankIcEmptyBranchEmitsTheSameKeySetAsThePopulatedBranch() {
        // Q-R007-03 / Q-R009-05：修复前空分支只发射 10 键，缺 pearson_ic_mean / monotonicity /
        // hac_std / ci_lower / ci_upper，而前端把 pearson_ic_mean 声明为必需 ⟹ 类型契约层面的未定义读取。
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of());

        Map<String, Object> out = service().research(null, d, d);

        @SuppressWarnings("unchecked")
        Map<String, Object> rankIc = (Map<String, Object>) out.get("rank_ic");
        assertThat(rankIc).containsKeys(
                "pearson_ic_mean", "monotonicity", "hac_std", "ci_lower", "ci_upper",
                "price_basis", "status", "min_cross_section_size", "min_cross_section_size_basis",
                "group_boundary_rule", "rounding_rule", "ci_status", "rank_ic_std_basis",
                "groups_with_observations", "pearson_support_days", "two_long_leg_spread_basis");
        assertThat(rankIc.get("status")).isEqualTo("NO_OBSERVATIONS");
        assertThat(rankIc.get("price_basis")).isEqualTo("QFQ_CLOSE");
        assertThat(rankIc.get("monotonicity")).isEqualTo("N/A");
        // 修复前恒为 0.0 ⟹ 与「IR 恰为 0」不可分辨
        assertThat(rankIc.get("rank_ic_ir")).isNull();
    }

    @Test
    void renamedSpreadKeysAreTheOnlyReadableNamesAndNoLegacyAliasSurvives() {
        // 裁定 B（D-R003-01 / D-R004-01）改名硬红线：旧名**不得以任何形式仍可读**。
        // 改前键是 long_short_spread / long_short_spread_neutral ⟹ 本用例失败；
        // 若有人把旧名以「旧名+后缀」的兼容别名塞回来（伪装成未改名）⟹ 本用例同样失败。
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of());

        Map<String, Object> out = service().research(null, d, d);

        Set<String> keys = collectKeysDeep(out);
        assertThat(keys).noneMatch(k -> k.toLowerCase().contains("long_short_spread"))
                .noneMatch(k -> k.toLowerCase().contains("longshortspread"));
        // 如实名必须真的在载荷里（是改名，不是删字段/改名后再发一份旧数据）
        assertThat(keys).contains(
                "two_long_leg_spread", "two_long_leg_spread_exact",
                "two_long_leg_spread_basis", "two_long_leg_spread_cost_basis",
                "two_long_leg_spread_cost_basis_note",
                "dollar_neutral_spread", "dollar_neutral_spread_exact",
                "dollar_neutral_spread_basis", "dollar_neutral_spread_note");
    }

    /** 递归收集载荷（含嵌套 map/list）里的全部键，供「旧名不可读」这类全量断言使用。 */
    private static Set<String> collectKeysDeep(Object root) {
        Set<String> keys = new LinkedHashSet<>();
        // 载荷里 null 是常态（不可定义值），且 ArrayDeque 不接受 null ⟹ 用允许 null 的 ArrayDeque 替代品
        List<Object> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            Object cur = stack.remove(stack.size() - 1);
            if (cur instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    keys.add(String.valueOf(e.getKey()));
                    if (e.getValue() != null) {
                        stack.add(e.getValue());
                    }
                }
            } else if (cur instanceof List<?> l) {
                l.stream().filter(java.util.Objects::nonNull).forEach(stack::add);
            }
        }
        return keys;
    }

    @Test
    void emptyBranchEmitsNullStatisticsNotPlaceholderZero() {
        // 裁定 A（D-R006-05 / D-R007-01 / D-R013-02）：无可用观测时统计量一律 null，不得发 0.0。
        // 改前 rank_ic_mean / rank_ic_std / hac_std / ci_* / two_long_leg_spread 均为 0.0 ⟹ 本用例失败。
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of());

        Map<String, Object> out = service().research(null, d, d);

        @SuppressWarnings("unchecked")
        Map<String, Object> rankIc = (Map<String, Object>) out.get("rank_ic");
        assertThat(rankIc.get("status")).isEqualTo("NO_OBSERVATIONS");
        assertThat(rankIc.get("rank_ic_mean")).isNull();
        assertThat(rankIc.get("rank_ic_std")).isNull();
        assertThat(rankIc.get("rank_ic_positive_rate")).isNull();
        assertThat(rankIc.get("hac_std")).isNull();
        assertThat(rankIc.get("ci_lower")).isNull();
        assertThat(rankIc.get("ci_upper")).isNull();
        assertThat(rankIc.get("two_long_leg_spread")).isNull();
        // 未取整并列键（*_exact）必须同样为 null，不得把取整差异当挡箭牌
        assertThat(rankIc.get("rank_ic_mean_exact")).isNull();
        assertThat(rankIc.get("hac_std_exact")).isNull();
        assertThat(rankIc.get("two_long_leg_spread_exact")).isNull();
        // 状态键与 null 并存：消费方可区分「不可用」与「恰为 0」
        assertThat(rankIc.get("ci_status")).isEqualTo("INSUFFICIENT_N");

        // 成本后净收益口径块同样不得发 0.0（D-R004-03）
        @SuppressWarnings("unchecked")
        Map<String, Object> net = (Map<String, Object>) rankIc.get("cost_net_ic");
        assertThat(net.get("status")).isEqualTo("NO_OBSERVATIONS");
        assertThat(net.get("data_available")).isEqualTo(false);
        assertThat(net.get("rank_ic_mean")).isNull();
        assertThat(net.get("two_long_leg_spread")).isNull();
    }

    @Test
    void rankIcBlockDisclosesConstructionRulesAndUndefinedIr() {
        // 与 rankIcComputedFromForwardReturns 同一 fixture：T = 1 ⟹ 范围不足、IC 序列无离散度
        LocalDate t = LocalDate.of(2025, 1, 2);
        LocalDate t1 = LocalDate.of(2025, 1, 3);
        LocalDate t2 = LocalDate.of(2025, 1, 4);
        LocalDate calEnd = t.plusDays(2 * 2L);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(t, t))
                .thenReturn(List.of(result("R1", "CLASSIC_SELECTOR", t)));

        List<SelectorResultItem> items = new ArrayList<>();
        List<String> codes = List.of("000001", "000002", "000003", "000004", "000005",
                "600519", "600518", "600517", "600516", "600515");
        for (int i = 0; i < 10; i++) {
            items.add(item("R1", 10.0 * (i + 1), codes.get(i), i < 5 ? "SZSE" : "SSE"));
        }
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any())).thenReturn(items);
        when(marketDataRepository.findDistinctTradeDatesBetween(eq(t), eq(calEnd)))
                .thenReturn(List.of(t, t1, t2));
        List<MarketData> bulkBars = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String code = codes.get(i);
            String ex = i < 5 ? "SZSE" : "SSE";
            bulkBars.add(bar(code, ex, t, 10.0));
            bulkBars.add(bar(code, ex, t2, 10.0 + i * 0.01));
        }
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                eq(t), eq(calEnd)))
                .thenReturn(bulkBars);
        when(marketPriceRawRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                eq(t), eq(calEnd)))
                .thenReturn(List.of());

        Map<String, Object> out = service().research("CLASSIC_SELECTOR", t, t, 2);

        @SuppressWarnings("unchecked")
        Map<String, Object> rankIc = (Map<String, Object>) out.get("rank_ic");
        assertThat(rankIc.get("status")).isEqualTo("OK");
        // 本面价格基准必须显式：与 snapshot 面同名 metric_scope=RAW 的 rawClose 不同基准（Q-R012-02）
        assertThat(rankIc.get("price_basis")).isEqualTo("QFQ_CLOSE");
        assertThat(rankIc.get("group_count")).isEqualTo(5);
        assertThat(rankIc.get("min_cross_section_size")).isEqualTo(10);
        assertThat(rankIc.get("min_cross_section_size_basis")).isEqualTo("INPUT_ROWS");
        assertThat(rankIc.get("group_boundary_rule")).isEqualTo("INDEX_EQUAL_SPLIT_STABLE_SORT");
        assertThat(rankIc.get("rounding_rule")).isEqualTo("ROUND_HALF_UP_4DP");
        assertThat(rankIc.get("rank_ic_std_basis")).isEqualTo("POPULATION_STD_DIV_T");
        assertThat(rankIc.get("two_long_leg_spread_basis")).isEqualTo("TWO_LONG_LEG_NET_DIFFERENCE");
        // 聚合面 observations 的单位声明：截面日数，不是成分股数（Q-R014-03）
        assertThat(rankIc.get("grouped_returns_observations_basis"))
                .isEqualTo("DAY_LEVEL_GROUP_MEANS_PER_GROUP");
        assertThat(rankIc.get("input_cross_section_days")).isEqualTo(1);
        assertThat(rankIc.get("days_dropped_below_min_cross_section")).isEqualTo(0);
        assertThat(rankIc.get("pearson_support_days")).isEqualTo(1);
        assertThat(rankIc.get("groups_with_observations")).isEqualTo(5);
        // T = 1 ⟹ HAC 标准误与区间均无定义（载荷为 null）⟹ 若只看数值会误读为「精确估计」
        assertThat(rankIc.get("ci_status")).isEqualTo("INSUFFICIENT_N");
        assertThat(rankIc.get("rank_ic_ir")).isNull();

        // 行级披露：当日剔除计数与未取整值、空组标记
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) rankIc.get("daily_rank_ic");
        assertThat(daily).hasSize(1);
        assertThat(daily.get(0).get("sample_size")).isEqualTo(10);
        assertThat(daily.get(0).get("excluded_observations")).isEqualTo(0);
        assertThat(daily.get(0)).containsKey("rank_ic_exact");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grouped = (List<Map<String, Object>>) rankIc.get("grouped_returns");
        assertThat(grouped).hasSize(5);
        assertThat(grouped).allMatch(row -> Boolean.FALSE.equals(row.get("empty")));
        assertThat(grouped).allMatch(row -> row.containsKey("avg_return_exact"));

        // 净收益口径块：无 raw 行情 ⟹ 必须显式声明「本口径无数据」，不得只给 0.0（Q-R004-03 / Q-R013-07）
        @SuppressWarnings("unchecked")
        Map<String, Object> net = (Map<String, Object>) rankIc.get("cost_net_ic");
        assertThat(net.get("status")).isEqualTo("NO_OBSERVATIONS");
        assertThat(net.get("data_available")).isEqualTo(false);
        assertThat(net.get("group_count")).isEqualTo(5);
        assertThat(net.get("min_cross_section_size")).isEqualTo(10);
        // 裁定 A：本口径无可用观测 ⟹ 统计量为 null（0.0 会被读成「成本后恰为 0」，与「无数据」不可区分）
        assertThat(net.get("two_long_leg_spread")).isNull();
        assertThat(net.get("rank_ic_mean")).isNull();
        assertThat(net).containsKey("grouped_returns");
    }

    @Test
    void legacyFunnelDisclosesUniverseFallbackAliasesAndInheritedMetricScope() {
        // Q-R015-01/02/03：回退未具名、别名键无说明、funnel 的 metric_scope 无成本内容 —— 均为纯增量披露
        LocalDate d = LocalDate.of(2025, 1, 2);
        when(selectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc(d, d))
                .thenReturn(List.of(result("R1", "CLASSIC_SELECTOR", d)));
        when(selectorResultItemRepository.findByResultIdInOrderByRankNo(any()))
                .thenReturn(List.of(
                        item("R1", 60.0, "000001", "SZSE"),
                        item("R1", 70.0, "000002", "SZSE")
                ));
        when(marketDataRepository.findDistinctTradeDatesBetween(eq(d), eq(d.plusDays(10))))
                .thenReturn(List.of(d));
        when(marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                eq(d), eq(d.plusDays(10))))
                .thenReturn(List.of());
        when(marketPriceRawRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                eq(d), eq(d.plusDays(10))))
                .thenReturn(List.of());

        Map<String, Object> out = service().research("CLASSIC_SELECTOR", d, d);

        @SuppressWarnings("unchecked")
        Map<String, Object> funnel = (Map<String, Object>) out.get("funnel");
        assertThat(funnel).isNotNull();
        // 无 cross_section_count 快照 ⟹ universe_count 被回退为 total_observations，必须可读出
        assertThat(funnel.get("universe_count_source")).isEqualTo("FALLBACK_TOTAL_OBSERVATIONS");
        assertThat(funnel.get("universe_count_fallback")).isEqualTo(true);
        assertThat((String) funnel.get("universe_count_note")).contains("不得读作真实候选池规模");
        // 别名键说明与 metric_scope 继承说明原先在载荷中不存在
        assertThat((String) funnel.get("key_alias_note")).contains("同一变量两个键名");
        assertThat(funnel.get("metric_scope")).isEqualTo("COST_APPROX");
        assertThat((String) funnel.get("metric_scope_note")).contains("家族继承值");
    }
}
