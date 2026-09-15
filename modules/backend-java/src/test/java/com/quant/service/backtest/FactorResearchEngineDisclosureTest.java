package com.quant.service.backtest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 样本漏斗计数族与构造规则的**披露字段**守卫（Q-R006-03 / Q-R009-02 / Q-R009-04 /
 * Q-R014-06 / Q-R014-07）。
 *
 * <p>这些字段是纯增量披露：不参与任何统计量计算，只把「样本少了、但没人知道」显式化。
 * 修复前这些组件不存在，因此本文件的断言在修复前**无法编译**（修复前必然失败）。
 */
class FactorResearchEngineDisclosureTest {

    private static FactorResearchEngine.CrossSectionObservation obs(double score, double forwardReturn) {
        return new FactorResearchEngine.CrossSectionObservation(score, forwardReturn);
    }

    private static List<FactorResearchEngine.CrossSectionObservation> increasing(int n) {
        List<FactorResearchEngine.CrossSectionObservation> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            out.add(obs(i * 10.0, i * 0.01));
        }
        return out;
    }

    @Test
    void summarizeReportsTheWholeSampleFunnel() {
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        // A：10 个有限观测（规模闸通过、IC 与 Pearson 均可算）
        byDate.put("2025-01-02", increasing(10));
        // B：5 个观测 < min_cross_section_size = 10 ⟹ 整日丢弃
        byDate.put("2025-01-03", increasing(5));
        // C：10 个输入行，其中 2 个非有限 ⟹ 剔除 2、可用 8（规模闸按输入行数比 ⟹ 该日保留）
        List<FactorResearchEngine.CrossSectionObservation> dayC = new ArrayList<>(increasing(8));
        dayC.add(obs(90.0, Double.NaN));
        dayC.add(obs(100.0, Double.POSITIVE_INFINITY));
        byDate.put("2025-01-04", dayC);
        // D：10 个观测但 score 全相等 ⟹ 秩相关无定义 ⟹ 该日丢弃且原因可分辨
        List<FactorResearchEngine.CrossSectionObservation> dayD = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            dayD.add(obs(50.0, i * 0.01));
        }
        byDate.put("2025-01-05", dayD);

        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(byDate, 5, 5, 10, "test universe");

        // 入参 4 日 = 计入 2 日 + 规模不足 1 日 + IC 不可算 1 日（可分辨，不是「少了一天」）
        assertThat(s.inputDays()).isEqualTo(4);
        assertThat(s.crossSectionDays()).isEqualTo(2);
        assertThat(s.daysBelowMinCrossSection()).isEqualTo(1);
        assertThat(s.daysNonFiniteIc()).isEqualTo(1);
        assertThat(s.excludedNonFiniteObservations()).isEqualTo(2);
        assertThat(s.totalObservations()).isEqualTo(18);
        assertThat(s.groupsWithObservations()).isEqualTo(5);
        assertThat(s.pearsonSupportDays()).isEqualTo(2);
        assertThat(s.universeNote()).isEqualTo("test universe");
    }

    @Test
    void allDaysDroppedStillReportsWhyTheyWereDropped() {
        // 只有「规模不足」的日：daily 为空 ⟹ 走早返回路径，计数也必须保留（否则丢失原因）
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        byDate.put("2025-01-02", increasing(3));

        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(byDate, 5, 5, 10, "note");

        assertThat(s.crossSectionDays()).isZero();
        assertThat(s.inputDays()).isEqualTo(1);
        assertThat(s.daysBelowMinCrossSection()).isEqualTo(1);
        assertThat(s.daysNonFiniteIc()).isZero();
        assertThat(s.groupsWithObservations()).isZero();
        assertThat(s.pearsonSupportDays()).isZero();
        assertThat(s.monotonicity()).isEqualTo("N/A");
        Double rankIcIr = s.rankIcIr();
        assertThat(rankIcIr).isNull();
    }

    @Test
    void allDaysNonFiniteIcAreCountedEvenWhenNothingIsComputable() {
        // 只有「IC 不可算」的日（score 全相等）
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        List<FactorResearchEngine.CrossSectionObservation> flatScores = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            flatScores.add(obs(50.0, i * 0.01));
        }
        byDate.put("2025-01-02", flatScores);

        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(byDate, 5, 5, 10, "note");

        assertThat(s.crossSectionDays()).isZero();
        assertThat(s.inputDays()).isEqualTo(1);
        assertThat(s.daysBelowMinCrossSection()).isZero();
        assertThat(s.daysNonFiniteIc()).isEqualTo(1);
        assertThat(s.excludedNonFiniteObservations()).isZero();
    }

    @Test
    void emptyInputReportsZeroCountersInsteadOfGuessing() {
        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(Map.of(), 2, 5, 10, "note");

        assertThat(s.inputDays()).isZero();
        assertThat(s.crossSectionDays()).isZero();
        assertThat(s.daysBelowMinCrossSection()).isZero();
        assertThat(s.daysNonFiniteIc()).isZero();
        assertThat(s.excludedNonFiniteObservations()).isZero();
        assertThat(s.groupsWithObservations()).isZero();
        assertThat(s.pearsonSupportDays()).isZero();
        assertThat(s.monotonicity()).isEqualTo("N/A");
        Double rankIcIr = s.rankIcIr();
        assertThat(rankIcIr).isNull();
    }

    @Test
    void dailyRowsCarryDropCountsAndSummaryGroupCountsAreDayCounts() {
        List<FactorResearchEngine.CrossSectionObservation> day1 = new ArrayList<>(increasing(8));
        day1.add(obs(90.0, Double.NaN));
        day1.add(obs(100.0, Double.POSITIVE_INFINITY));
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        byDate.put("2025-01-02", day1);
        byDate.put("2025-01-03", increasing(10));

        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(byDate, 5, 5, 10, "note");

        // 日级面：sample_size = 参与计算的**有限**观测数，剔除数单独披露（Q-R014-06）
        assertThat(s.dailyRankIc()).hasSize(2);
        FactorResearchEngine.DailyRankIc first = s.dailyRankIc().get(0);
        assertThat(first.sampleSize()).isEqualTo(8);
        assertThat(first.excludedObservations()).isEqualTo(2);
        assertThat(s.dailyRankIc().get(1).excludedObservations()).isZero();

        // 聚合面：grouped_returns[].observations = 参与聚合的**截面日数**（每组的日级均值个数），
        // 不是成分股数。单位陷阱由载荷键 grouped_returns_observations_basis 显式声明（Q-R014-03）
        assertThat(s.groupedReturns()).hasSize(5);
        assertThat(s.groupedReturns().stream()
                .mapToInt(FactorResearchEngine.GroupReturnStat::observations)).containsOnly(2);
        assertThat(s.groupsWithObservations()).isEqualTo(5);
        // 逐日分组（引擎内部函数）的 name_count 才是成分股数：当日 8 个可用观测分 5 组
        // （裁定 B 改名：日级面字段名是 nameCount，聚合面才是 observations）
        List<FactorResearchEngine.DailyGroupReturnStat> dayGroups =
                FactorResearchEngine.groupedAverageReturns(day1, 5);
        assertThat(dayGroups.stream()
                .mapToInt(FactorResearchEngine.DailyGroupReturnStat::nameCount).sum()).isEqualTo(8);
    }

    /**
     * 裁定 B（D-R014-03）：日级面与聚合面的计数**字段名必须不同**，且各自的量纲可被判据复算。
     *
     * <p>面板取「3 个截面日 × 每组 2 只」：若两处同义，日级面读数应等于聚合面读数；
     * 实际日级面 = 2（只/日）、聚合面 = 3（天），**必须不相等**——这正是「同名不同量」的可判据形态。
     */
    @Test
    void dayLevelCountIsNameCountAndAggregateCountIsDayCountAndTheFieldNamesDiffer() {
        // 组名守卫：日级面只有 nameCount、聚合面只有 observations，任一侧出现对方的字段名即判据失败
        List<String> dayComponents = java.util.Arrays.stream(
                        FactorResearchEngine.DailyGroupReturnStat.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        List<String> aggregateComponents = java.util.Arrays.stream(
                        FactorResearchEngine.GroupReturnStat.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(dayComponents).contains("nameCount").doesNotContain("observations");
        assertThat(aggregateComponents).contains("observations").doesNotContain("nameCount");

        // 量纲守卫：3 个截面日 × 每组 2 只
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        byDate.put("2025-02-03", increasing(4));
        byDate.put("2025-02-04", increasing(4));
        byDate.put("2025-02-05", increasing(4));

        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(byDate, 1, 2, 4, "note");
        assertThat(s.crossSectionDays()).isEqualTo(3);

        List<Integer> aggregateCounts = s.groupedReturns().stream()
                .map(FactorResearchEngine.GroupReturnStat::observations).toList();
        List<Integer> dayNameCounts = FactorResearchEngine.groupedAverageReturns(increasing(4), 2).stream()
                .map(FactorResearchEngine.DailyGroupReturnStat::nameCount).toList();

        // 聚合面数「天」= 3；日级面数「只」= 2；两组读数必须不同（同名同义时会相等而判据失效）
        assertThat(aggregateCounts).containsOnly(3);
        assertThat(dayNameCounts).containsOnly(2);
        assertThat(aggregateCounts).isNotEqualTo(dayNameCounts);
    }
}
