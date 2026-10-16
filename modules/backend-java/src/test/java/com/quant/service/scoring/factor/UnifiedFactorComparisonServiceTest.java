package com.quant.service.scoring.factor;

import com.quant.service.scoring.UnifiedScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次 C 测试：{@link UnifiedFactorComparisonService} 新旧对账。
 *
 * <p>验证要点：
 * <ul>
 *   <li>秩相关的取值与方向正确（完全同序 = 1、完全反序 = −1）。</li>
 *   <li>TopN 交并比计算正确，且与秩相关是不同的信息维度。</li>
 *   <li>只出现在单侧的股票被如实列出。</li>
 *   <li><b>不输出优劣结论</b>——这是刻意的，避免把"更像"误读成"更准"。</li>
 * </ul>
 */
class UnifiedFactorComparisonServiceTest {

    private UnifiedScoringService legacyService;
    private UnifiedFactorScoringService unifiedService;
    private UnifiedFactorComparisonService comparison;

    @BeforeEach
    void setUp() {
        legacyService = mock(UnifiedScoringService.class);
        unifiedService = mock(UnifiedFactorScoringService.class);
        comparison = new UnifiedFactorComparisonService(legacyService, unifiedService);
    }

    private static Map<String, Object> item(String code, double score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stock_code", code);
        m.put("total_score", score);
        return m;
    }

    private static Map<String, Object> result(List<Map<String, Object>> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        return m;
    }

    private void given(List<Map<String, Object>> legacyItems, List<Map<String, Object>> unifiedItems) {
        when(legacyService.run(any())).thenReturn(result(legacyItems));
        when(unifiedService.run(any())).thenReturn(result(unifiedItems));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> comparisonOf(Map<String, Object> out) {
        return (Map<String, Object>) out.get("comparison");
    }

    /** 把响应里的列表取成 List<String>，便于 AssertJ 做元素级断言。 */
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    @Test
    void identicalRankingsYieldPerfectCorrelationAndFullOverlap() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(item(String.format("00000%d", i), 100 - i * 5));
        }
        given(items, new ArrayList<>(items));

        Map<String, Object> cmp = comparisonOf(comparison.compare("TREND", Map.of("topN", 5)));

        assertThat(((Number) cmp.get("rank_correlation")).doubleValue()).isEqualTo(1.0);
        assertThat(cmp.get("common_count")).isEqualTo(10);
        assertThat(cmp.get("top_n_intersection")).isEqualTo(5);
        assertThat(((Number) cmp.get("top_n_overlap_ratio")).doubleValue()).isEqualTo(1.0);
        assertThat((List<?>) cmp.get("only_in_legacy")).isEmpty();
        assertThat((List<?>) cmp.get("only_in_unified")).isEmpty();
    }

    @Test
    void exactlyReversedRankingsYieldMinusOne() {
        List<Map<String, Object>> legacy = new ArrayList<>();
        List<Map<String, Object>> unified = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            legacy.add(item(String.format("00000%d", i), 100 - i * 5));
            unified.add(item(String.format("00000%d", i), 50 + i * 5));
        }
        given(legacy, unified);

        Map<String, Object> cmp = comparisonOf(comparison.compare("TREND", Map.of("topN", 5)));

        assertThat(((Number) cmp.get("rank_correlation")).doubleValue()).isEqualTo(-1.0);
        // 完全反序 → TopN 交集为 0
        assertThat(cmp.get("top_n_intersection")).isEqualTo(0);
        assertThat(((Number) cmp.get("top_n_overlap_ratio")).doubleValue()).isZero();
    }

    @Test
    void highRankCorrelationCanCoexistWithLowTopNOverlap() {
        // 这是最需要关注的情形：整体排序几乎一致，但头部换人。
        // 构造：只交换前两名的次序（其余完全不动）。
        // n=10 时，交换相邻两名的 Σd² = 2，故
        //   ρ = 1 − 6·2/(10·(100−1)) = 1 − 12/990 = 0.987879...
        // 即秩相关极高。但 legacy top3 = {A,B,C}，unified top3 = {B,A,C} → 交集 3。
        // 为得到「头部换人」还要让 TopN 交集变小，故同时把 C 与 D 交换，
        // 使 unified top3 = {B,A,D}，而 legacy top3 = {A,B,C} → 交集 {A,B} = 2。
        String[] codes = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J"};
        List<Map<String, Object>> legacy = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            legacy.add(item(codes[i], 100 - i));
        }
        List<Map<String, Object>> unified = new ArrayList<>();
        // A,B 互换；C,D 互换
        double[] scores = {99, 100, 97, 98, 95, 94, 93, 92, 91, 90};
        for (int i = 0; i < codes.length; i++) {
            unified.add(item(codes[i], scores[i]));
        }
        given(legacy, unified);

        Map<String, Object> cmp = comparisonOf(comparison.compare("TREND", Map.of("topN", 3)));

        // 两次相邻互换 → Σd² = 2 + 2 = 4 → ρ = 1 − 24/990
        assertThat(((Number) cmp.get("rank_correlation")).doubleValue())
                .isCloseTo(1.0 - 24.0 / 990.0, org.assertj.core.data.Offset.offset(1e-4));
        // legacy top3 = A,B,C；unified top3 = B,A,D → 交集 {A,B} = 2，并集 4
        assertThat(cmp.get("top_n_intersection")).isEqualTo(2);
        assertThat(((Number) cmp.get("top_n_overlap_ratio")).doubleValue())
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void nearPerfectCorrelationIsReportedForASingleAdjacentSwap() {
        // 只交换第 1、2 名：Σd² = 2 → ρ = 1 − 12/990，精确可验算
        List<Map<String, Object>> legacy = new ArrayList<>();
        List<Map<String, Object>> unified = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            legacy.add(item(String.format("S%02d", i), 100 - i));
            unified.add(item(String.format("S%02d", i), i == 0 ? 99 : (i == 1 ? 100 : 100 - i)));
        }
        given(legacy, unified);

        Map<String, Object> cmp = comparisonOf(comparison.compare("TREND", Map.of("topN", 10)));

        // 服务按 4 位小数舍入 → 0.9879，故容差取 1e-4（比舍入精度宽一档）
        assertThat(((Number) cmp.get("rank_correlation")).doubleValue())
                .isCloseTo(1.0 - 12.0 / 990.0, org.assertj.core.data.Offset.offset(1e-4));
        // TopN=10 覆盖全部 → 交集满
        assertThat(cmp.get("top_n_intersection")).isEqualTo(10);
    }

    @Test
    void stocksPresentOnOnlyOneSideAreReported() {
        List<Map<String, Object>> legacy = List.of(
                item("ONLY_LEGACY", 90.0), item("BOTH", 80.0), item("L2", 70.0));
        List<Map<String, Object>> unified = List.of(
                item("ONLY_UNIFIED", 95.0), item("BOTH", 85.0), item("U2", 60.0));
        given(legacy, unified);

        Map<String, Object> cmp = comparisonOf(comparison.compare("TREND", Map.of("topN", 10)));

        assertThat(cmp.get("legacy_count")).isEqualTo(3);
        assertThat(cmp.get("unified_count")).isEqualTo(3);
        assertThat(cmp.get("common_count")).isEqualTo(1);
        assertThat(stringList(cmp.get("only_in_legacy")))
                .containsExactlyInAnyOrder("ONLY_LEGACY", "L2");
        assertThat(stringList(cmp.get("only_in_unified")))
                .containsExactlyInAnyOrder("ONLY_UNIFIED", "U2");
        // 共同股票只有 1 只 → 秩相关不可定义，必须是 null 而不是 0 或 1
        assertThat(cmp.get("rank_correlation")).isNull();
    }

    @Test
    void scoreDistributionStatsRevealScaleDifferences() {
        // 旧路径：分差极小（模拟实测 MULTI_DIM stdev 0.993 / span 4.48）
        List<Map<String, Object>> legacy = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            legacy.add(item(String.format("%03d", i), 50 + i * 0.2));
        }
        // 新路径：横截面百分位，满量程
        List<Map<String, Object>> unified = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            unified.add(item(String.format("%03d", i), i * 100.0 / 19.0));
        }
        given(legacy, unified);

        Map<String, Object> cmp = comparisonOf(comparison.compare("MULTI_DIM", Map.of("topN", 5)));

        @SuppressWarnings("unchecked")
        Map<String, Object> ls = (Map<String, Object>) cmp.get("legacy_score_stats");
        @SuppressWarnings("unchecked")
        Map<String, Object> us = (Map<String, Object>) cmp.get("unified_score_stats");
        assertThat(((Number) ls.get("span")).doubleValue()).isLessThan(5.0);
        assertThat(((Number) us.get("span")).doubleValue()).isEqualTo(100.0);
        assertThat(((Number) ls.get("count")).intValue()).isEqualTo(20);
        assertThat(((Number) us.get("unique_values")).intValue()).isEqualTo(20);
    }

    @Test
    void comparisonNeverAssertsWhichSideIsBetter() {
        given(List.of(item("A", 1.0)), List.of(item("A", 2.0)));

        Map<String, Object> cmp = comparisonOf(comparison.compare("TREND", Map.of()));

        assertThat(cmp.get("verdict")).isEqualTo("NOT_EVALUATED");
        assertThat(String.valueOf(cmp.get("verdict_note")))
                .contains("不判定优劣")
                .contains("不构成质量证据");
        assertThat(String.valueOf(cmp.get("rank_correlation_note")))
                .contains("不衡量预测力");
    }

    @Test
    void emptyResultsAreHandledWithoutThrowing() {
        given(List.of(), List.of());

        Map<String, Object> out = comparison.compare("TREND", Map.of("topN", 5));

        assertThat(out.get("success")).isEqualTo(true);
        Map<String, Object> cmp = comparisonOf(out);
        assertThat(cmp.get("common_count")).isEqualTo(0);
        assertThat(cmp.get("rank_correlation")).isNull();
        assertThat(cmp.get("top_n_overlap_ratio")).isNull();
    }

    @Test
    void compositeScoreKeyIsPickedUpWhenTotalScoreIsAbsent() {
        // MULTI_DIM 旧路径用 composite_score 而非 total_score，取值必须能兼容
        Map<String, Object> legacyItem = new LinkedHashMap<>();
        legacyItem.put("stock_code", "000001");
        legacyItem.put("composite_score", 77.5);
        given(List.of(legacyItem), List.of(item("000001", 60.0)));

        Map<String, Object> cmp = comparisonOf(comparison.compare("MULTI_DIM", Map.of()));

        assertThat(cmp.get("legacy_count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> ls = (Map<String, Object>) cmp.get("legacy_score_stats");
        assertThat(((Number) ls.get("max")).doubleValue()).isEqualTo(77.5);
    }

    @Test
    void duplicateRowsForSameStockDoNotDoubleCount() {
        given(List.of(item("000001", 10.0), item("000001", 90.0), item("000002", 50.0)),
                List.of(item("000001", 60.0), item("000002", 55.0)));

        Map<String, Object> cmp = comparisonOf(comparison.compare("TREND", Map.of("topN", 5)));

        // 重复行被合并为一只股票（取较大分数），不会让计数虚高
        assertThat(cmp.get("legacy_count")).isEqualTo(2);
        assertThat(cmp.get("common_count")).isEqualTo(2);
    }

    @Test
    void compareDoesNotPersistEitherPath() {
        given(List.of(item("A", 1.0)), List.of(item("A", 1.0)));

        comparison.compare("TREND", Map.of("topN", 5));

        // 两条路径都必须被强制 persist=false
        org.mockito.ArgumentCaptor<Map<String, Object>> legacyCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(legacyService).run(legacyCaptor.capture());
        assertThat(legacyCaptor.getValue().get("persist")).isEqualTo(false);

        org.mockito.ArgumentCaptor<Map<String, Object>> unifiedCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(unifiedService).run(unifiedCaptor.capture());
        assertThat(unifiedCaptor.getValue().get("topN")).isEqualTo(5);
        assertThat(unifiedCaptor.getValue().get("unifiedStrategy")).isEqualTo("TREND");
    }
}
