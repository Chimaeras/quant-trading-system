package com.quant.service.backtest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class FactorResearchEngineTest {

    @Test
    void spearmanReturnsOneForMonotonicIncreasingPairs() {
        List<Double> scores = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        List<Double> returns = List.of(0.01, 0.02, 0.03, 0.04, 0.05);
        assertThat(FactorResearchEngine.spearmanRankCorrelation(scores, returns)).isCloseTo(1.0, offset(1e-9));
    }

    @Test
    void spearmanReturnsMinusOneForMonotonicDecreasingPairs() {
        List<Double> scores = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        List<Double> returns = List.of(0.05, 0.04, 0.03, 0.02, 0.01);
        assertThat(FactorResearchEngine.spearmanRankCorrelation(scores, returns)).isCloseTo(-1.0, offset(1e-9));
    }

    @Test
    void groupedReturnsPutHigherScoresInHigherGroups() {
        List<FactorResearchEngine.CrossSectionObservation> obs = List.of(
                new FactorResearchEngine.CrossSectionObservation(10, 0.01),
                new FactorResearchEngine.CrossSectionObservation(20, 0.02),
                new FactorResearchEngine.CrossSectionObservation(30, 0.03),
                new FactorResearchEngine.CrossSectionObservation(40, 0.04),
                new FactorResearchEngine.CrossSectionObservation(50, 0.05)
        );
        List<FactorResearchEngine.DailyGroupReturnStat> groups =
                FactorResearchEngine.groupedAverageReturns(obs, 5);
        assertThat(groups).hasSize(5);
        assertThat(groups.get(0).avgReturn()).isLessThan(groups.get(4).avgReturn());
        assertThat(groups.get(0).label()).contains("低分");
        assertThat(groups.get(4).label()).contains("高分");
    }

    @Test
    void summarizeAggregatesDailyIcAndGroupSpread() {
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        byDate.put("2025-01-02", List.of(
                new FactorResearchEngine.CrossSectionObservation(10, 0.01),
                new FactorResearchEngine.CrossSectionObservation(20, 0.02),
                new FactorResearchEngine.CrossSectionObservation(30, 0.03),
                new FactorResearchEngine.CrossSectionObservation(40, 0.04),
                new FactorResearchEngine.CrossSectionObservation(50, 0.05),
                new FactorResearchEngine.CrossSectionObservation(60, 0.06),
                new FactorResearchEngine.CrossSectionObservation(70, 0.07),
                new FactorResearchEngine.CrossSectionObservation(80, 0.08),
                new FactorResearchEngine.CrossSectionObservation(90, 0.09),
                new FactorResearchEngine.CrossSectionObservation(100, 0.10)
        ));
        byDate.put("2025-01-03", List.of(
                new FactorResearchEngine.CrossSectionObservation(5, 0.02),
                new FactorResearchEngine.CrossSectionObservation(15, 0.03),
                new FactorResearchEngine.CrossSectionObservation(25, 0.04),
                new FactorResearchEngine.CrossSectionObservation(35, 0.05),
                new FactorResearchEngine.CrossSectionObservation(45, 0.06),
                new FactorResearchEngine.CrossSectionObservation(55, 0.07),
                new FactorResearchEngine.CrossSectionObservation(65, 0.08),
                new FactorResearchEngine.CrossSectionObservation(75, 0.09),
                new FactorResearchEngine.CrossSectionObservation(85, 0.10),
                new FactorResearchEngine.CrossSectionObservation(95, 0.11)
        ));

        FactorResearchEngine.FactorResearchSummary summary = FactorResearchEngine.summarize(
                byDate, 5, 5, 10, "test universe");

        assertThat(summary.crossSectionDays()).isEqualTo(2);
        assertThat(summary.rankIcMean()).isGreaterThan(0.9);
        assertThat(summary.rankIcPositiveRate()).isEqualTo(1.0);
        assertThat(summary.groupedReturns()).hasSize(5);
        assertThat(summary.longShortSpread()).isGreaterThan(0);
        assertThat(summary.universeNote()).isEqualTo("test universe");
    }

    @Test
    void pearsonCorrelationReturnsOneForPerfectLinear() {
        List<Double> scores = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        List<Double> returns = List.of(0.01, 0.02, 0.03, 0.04, 0.05);
        assertThat(FactorResearchEngine.pearsonCorrelation(scores, returns)).isCloseTo(1.0, offset(1e-9));
    }

    @Test
    void pearsonCorrelationReturnsNaNForInsufficientSamples() {
        assertThat(FactorResearchEngine.pearsonCorrelation(List.of(1.0), List.of(0.01))).isNaN();
        assertThat(FactorResearchEngine.pearsonCorrelation(null, null)).isNaN();
    }

    @Test
    void summarizeIncludesPearsonIcAndMonotonicity() {
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        byDate.put("2025-01-02", List.of(
                new FactorResearchEngine.CrossSectionObservation(10, 0.01),
                new FactorResearchEngine.CrossSectionObservation(20, 0.02),
                new FactorResearchEngine.CrossSectionObservation(30, 0.03),
                new FactorResearchEngine.CrossSectionObservation(40, 0.04),
                new FactorResearchEngine.CrossSectionObservation(50, 0.05),
                new FactorResearchEngine.CrossSectionObservation(60, 0.06),
                new FactorResearchEngine.CrossSectionObservation(70, 0.07),
                new FactorResearchEngine.CrossSectionObservation(80, 0.08),
                new FactorResearchEngine.CrossSectionObservation(90, 0.09),
                new FactorResearchEngine.CrossSectionObservation(100, 0.10)
        ));

        FactorResearchEngine.FactorResearchSummary summary = FactorResearchEngine.summarize(
                byDate, 5, 5, 10, "test");

        assertThat(summary.pearsonIcMean()).isCloseTo(1.0, offset(1e-6));
        assertThat(summary.monotonicity()).isEqualTo("MONOTONIC_INCREASING");
    }

    @Test
    void monotonicityDetectsNonMonotonic() {
        List<FactorResearchEngine.GroupReturnStat> nonMono = List.of(
                new FactorResearchEngine.GroupReturnStat(1, "G1", 0.01, 10),
                new FactorResearchEngine.GroupReturnStat(2, "G2", 0.05, 10),
                new FactorResearchEngine.GroupReturnStat(3, "G3", 0.02, 10),
                new FactorResearchEngine.GroupReturnStat(4, "G4", 0.04, 10),
                new FactorResearchEngine.GroupReturnStat(5, "G5", 0.06, 10)
        );
        assertThat(FactorResearchEngine.assessMonotonicity(nonMono)).isEqualTo("NON_MONOTONIC");
    }

    @Test
    void monotonicityDetectsDecreasing() {
        List<FactorResearchEngine.GroupReturnStat> decreasing = List.of(
                new FactorResearchEngine.GroupReturnStat(1, "G1", 0.05, 10),
                new FactorResearchEngine.GroupReturnStat(2, "G2", 0.04, 10),
                new FactorResearchEngine.GroupReturnStat(3, "G3", 0.03, 10),
                new FactorResearchEngine.GroupReturnStat(4, "G4", 0.02, 10),
                new FactorResearchEngine.GroupReturnStat(5, "G5", 0.01, 10)
        );
        assertThat(FactorResearchEngine.assessMonotonicity(decreasing)).isEqualTo("MONOTONIC_DECREASING");
    }

    @Test
    void neweyWestHacStdHandlesIndependentSeries() {
        // 独立同分布序列，HAC std 应接近普通 std/sqrt(n)
        List<Double> values = List.of(0.1, -0.05, 0.08, -0.03, 0.12, -0.07, 0.09, -0.02, 0.11, -0.04);
        double hacStd = FactorResearchEngine.neweyWestHacStd(values);
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        double expectedStd = Math.sqrt(var / values.size());
        assertThat(hacStd).isCloseTo(expectedStd, offset(0.01));
    }

    @Test
    void neweyWestHacStdReturnsZeroForInsufficientData() {
        assertThat(FactorResearchEngine.neweyWestHacStd(List.of(0.1))).isEqualTo(0);
        assertThat(FactorResearchEngine.neweyWestHacStd(null)).isEqualTo(0);
    }

    @Test
    void blockBootstrapCiProducesValidInterval() {
        List<Double> values = List.of(0.1, 0.12, 0.08, 0.11, 0.09, 0.13, 0.07, 0.10, 0.12, 0.08);
        double[] ci = FactorResearchEngine.blockBootstrapCi(values, 2, 1000, 42L);
        assertThat(ci[0]).isLessThanOrEqualTo(ci[1]);
        assertThat(ci[0]).isGreaterThan(0.05); // 均值约 0.10，下界应 > 0.05
        assertThat(ci[1]).isLessThan(0.15); // 上界应 < 0.15
    }

    @Test
    void blockBootstrapCiReturnsZerosForInsufficientData() {
        double[] ci = FactorResearchEngine.blockBootstrapCi(List.of(0.1), 2, 1000, 42L);
        assertThat(ci[0]).isEqualTo(0);
        assertThat(ci[1]).isEqualTo(0);
    }

    @Test
    void blockBootstrapCiDoesNotDegenerateWhenBlockSizeCloseToSampleSize() {
        // 块大小 ≥ 样本量时，块大小被压到 min(blockSize, floor(sqrt(n)), n-1)，CI 仍非点估计
        List<Double> values = List.of(0.10, 0.12, 0.08, 0.11, 0.09, 0.13, 0.07, 0.10, 0.12, 0.08);
        double[] ci = FactorResearchEngine.blockBootstrapCi(values, 10, 1000, 42L); // blockSize=10 == n
        assertThat(ci[0]).isLessThanOrEqualTo(ci[1]);
        assertThat(ci[1]).isGreaterThan(ci[0]);
        assertThat(ci[0]).isGreaterThan(0.05);
        assertThat(ci[1]).isLessThan(0.15);
    }

    @Test
    void singleDaySummaryLeavesHacStdAndConfidenceIntervalUndefined() {
        // 原用例只建了 1 个截面日，却断言 hac_std ≥ 0 与区间有序：T = 1 时两者在数值上都退化为
        // 0 / 零宽区间，会被读成「零标准误 = 精确估计」（D-R007-01）。现改为断言 null（无定义）。
        // 需要「HAC/区间被真正计算」的覆盖由紧随其后的两日用例承担。
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        byDate.put("2025-01-02", List.of(
                new FactorResearchEngine.CrossSectionObservation(10, 0.01),
                new FactorResearchEngine.CrossSectionObservation(20, 0.02),
                new FactorResearchEngine.CrossSectionObservation(30, 0.03),
                new FactorResearchEngine.CrossSectionObservation(40, 0.04),
                new FactorResearchEngine.CrossSectionObservation(50, 0.05),
                new FactorResearchEngine.CrossSectionObservation(60, 0.06),
                new FactorResearchEngine.CrossSectionObservation(70, 0.07),
                new FactorResearchEngine.CrossSectionObservation(80, 0.08),
                new FactorResearchEngine.CrossSectionObservation(90, 0.09),
                new FactorResearchEngine.CrossSectionObservation(100, 0.10)
        ));

        FactorResearchEngine.FactorResearchSummary summary = FactorResearchEngine.summarize(
                byDate, 5, 5, 10, "test");

        assertThat(summary.crossSectionDays()).isEqualTo(1);
        assertThat((Double) summary.hacStd()).isNull();
        assertThat((Double) summary.ciLower()).isNull();
        assertThat((Double) summary.ciUpper()).isNull();
    }

    @Test
    void twoDaySummaryComputesHacStdAndConfidenceInterval() {
        // T ≥ 2 时 HAC 标准误与区间真的有定义：不得被上一用例的置空逻辑波及（防止过度置空）
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        List<FactorResearchEngine.CrossSectionObservation> day1 = new ArrayList<>();
        List<FactorResearchEngine.CrossSectionObservation> day2 = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            day1.add(new FactorResearchEngine.CrossSectionObservation(i * 10.0, i * 0.01));
            day2.add(new FactorResearchEngine.CrossSectionObservation(i * 5.0, (11 - i) * 0.01));
        }
        byDate.put("2025-01-02", day1);
        byDate.put("2025-01-03", day2);

        FactorResearchEngine.FactorResearchSummary summary = FactorResearchEngine.summarize(
                byDate, 5, 5, 10, "test");

        assertThat(summary.crossSectionDays()).isEqualTo(2);
        assertThat(summary.hacStd()).isNotNull();
        assertThat(summary.hacStd()).isGreaterThanOrEqualTo(0);
        assertThat(summary.ciLower()).isNotNull();
        assertThat(summary.ciUpper()).isNotNull();
        assertThat(summary.ciLower()).isLessThanOrEqualTo(summary.ciUpper());
    }

    // ---------------------------------------------------------------------
    // Q-R006 / Q-R009 / Q-R013 / Q-R014 修复的守卫用例。
    // 每个用例都对应一条「退化输入 + 占位输出 + 仍标称语义」的缺陷：断言的是**可分辨性**，
    // 不是把结果改得更「好看」。这些用例在修复前全部失败。
    // ---------------------------------------------------------------------

    private static FactorResearchEngine.CrossSectionObservation obs(double score, double forwardReturn) {
        return new FactorResearchEngine.CrossSectionObservation(score, forwardReturn);
    }

    private static Map<String, List<FactorResearchEngine.CrossSectionObservation>> singleDay(
            String date, List<FactorResearchEngine.CrossSectionObservation> observations) {
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        byDate.put(date, observations);
        return byDate;
    }

    @Test
    void pearsonCorrelationIsInvariantUnderInputScale() {
        // Q-R009-01：修复前用**绝对**阈值 `vx < 1e-18` 判常量，量级 1e-11 的合法输入
        // （vx ≈ 8.25e-21）被判为常量 ⟹ ρ 由 1.0 变 NaN，破坏 Pearson 相关的尺度不变性。
        List<Double> scores = new ArrayList<>();
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            scores.add(i * 1e-11);
            returns.add(i * 2e-11);
        }
        assertThat(FactorResearchEngine.pearsonCorrelation(scores, returns)).isCloseTo(1.0, offset(1e-9));
    }

    @Test
    void pearsonCorrelationStillReturnsNaNForConstantInput() {
        // 反向守卫：尺度不变性不能把「真常量」也放行（一侧方差恰为 0 仍须判无定义）
        assertThat(FactorResearchEngine.pearsonCorrelation(
                List.of(1e-11, 1e-11, 1e-11, 1e-11, 1e-11),
                List.of(1e-11, 2e-11, 3e-11, 4e-11, 5e-11))).isNaN();
    }

    @Test
    void monotonicityReturnsNoDispersionWhenAllGroupsEqual() {
        List<FactorResearchEngine.GroupReturnStat> flat = List.of(
                new FactorResearchEngine.GroupReturnStat(1, "G1", 0.02, 10),
                new FactorResearchEngine.GroupReturnStat(2, "G2", 0.02, 10),
                new FactorResearchEngine.GroupReturnStat(3, "G3", 0.02, 10)
        );
        // 修复前 increasing 与 decreasing 同时为真、由分支顺序输出 MONOTONIC_INCREASING，
        // 等于用「零区分度」断言了方向（Q-R006-07 / Q-R013-01）
        assertThat(FactorResearchEngine.assessMonotonicity(flat)).isEqualTo("NO_DISPERSION");
    }

    @Test
    void monotonicityIgnoresEmptyGroups() {
        List<FactorResearchEngine.GroupReturnStat> grouped = new ArrayList<>();
        for (int g = 1; g <= 10; g++) {
            boolean empty = g <= 5;
            grouped.add(new FactorResearchEngine.GroupReturnStat(
                    g, "G" + g, empty ? null : 0.04 - (g - 6) * 0.01, empty ? 0 : 10));
        }
        // 空组现值 null（无观测）：修复前把占位 0.0 当观测：序列 0,0,0,0,0,0.04,0.03,0.02,0.01,0.00 ⟹ NON_MONOTONIC
        // （Q-R006-04 / Q-R013-02）
        assertThat(FactorResearchEngine.assessMonotonicity(grouped)).isEqualTo("MONOTONIC_DECREASING");
    }

    @Test
    void monotonicityReturnsNaWhenGroupMeanIsNonFinite() {
        List<FactorResearchEngine.GroupReturnStat> grouped = List.of(
                new FactorResearchEngine.GroupReturnStat(1, "G1", 0.01, 10),
                new FactorResearchEngine.GroupReturnStat(2, "G2", Double.NaN, 10),
                new FactorResearchEngine.GroupReturnStat(3, "G3", 0.03, 10)
        );
        // 修复前 NaN 与任何数比较恒假 ⟹ 「无严格反序对」⟹ 读成 MONOTONIC_INCREASING（Q-R013-09）
        assertThat(FactorResearchEngine.assessMonotonicity(grouped)).isEqualTo("N/A");
    }

    @Test
    void groupedReturnsExcludeNonFiniteObservations() {
        List<FactorResearchEngine.CrossSectionObservation> observations = List.of(
                obs(10, 0.01), obs(20, 0.02), obs(30, 0.03), obs(40, 0.04),
                obs(Double.NaN, 0.05), obs(60, Double.POSITIVE_INFINITY));
        List<FactorResearchEngine.DailyGroupReturnStat> groups =
                FactorResearchEngine.groupedAverageReturns(observations, 3);
        // 修复前 NaN / +Inf 参与排序（NaN 排到极值端）与均值 ⟹ 极值组均值 = NaN，且组内计数虚高（Q-R014-06）
        assertThat(groups).hasSize(3);
        assertThat(groups.stream().mapToInt(FactorResearchEngine.DailyGroupReturnStat::nameCount).sum())
                .isEqualTo(4);
        assertThat(groups).allMatch(g -> Double.isFinite(g.avgReturn()));
    }

    @Test
    void summarizeExcludesNonFiniteObservationsAndKeepsTheDay() {
        List<FactorResearchEngine.CrossSectionObservation> observations = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            observations.add(obs(i * 10.0, i * 0.01));
        }
        observations.add(obs(90.0, Double.NaN));
        observations.add(obs(100.0, Double.POSITIVE_INFINITY));

        // 规模闸按**输入行数**比较（10 ≥ 10），剔除 2 个非有限观测后仍有 8 个可用观测
        FactorResearchEngine.FactorResearchSummary summary = FactorResearchEngine.summarize(
                singleDay("2025-01-02", observations), 5, 5, 10, "test");

        // 修复前 NaN/Inf 直接进秩相关 ⟹ IC = NaN ⟹ 整日被丢弃（cross_section_days = 0）
        assertThat(summary.crossSectionDays()).isEqualTo(1);
        assertThat(summary.totalObservations()).isEqualTo(8);
    }

    @Test
    void summarizeSpreadIgnoresEmptyGroupsBeyondSampleSize() {
        // group_count = 20，但当日只有 10 个观测 ⟹ 只有 G1..G10 非空，G11..G20 无观测（avg_return = null）。
        // 修复前以最后一组（空组占位 0.0）当多头腿 ⟹ spread = 0.0 − 0.10 = −0.10；
        // 正确口径为高分组 − 低分组 = 0.01 − 0.10 = −0.09（Q-R006-04 / Q-R013-02）
        List<FactorResearchEngine.CrossSectionObservation> observations = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            observations.add(obs(10.0 * (i + 1), 0.10 - i * 0.01));
        }
        FactorResearchEngine.FactorResearchSummary summary = FactorResearchEngine.summarize(
                singleDay("2025-01-02", observations), 5, 20, 10, "test");

        assertThat(summary.longShortSpread()).isCloseTo(-0.09, offset(1e-12));
        assertThat(summary.groupedReturns()).hasSize(20);
        // 非空组严格递减；修复前空组占位 0.0 插在末尾 ⟹ NON_MONOTONIC
        assertThat(summary.monotonicity()).isEqualTo("MONOTONIC_DECREASING");
    }

    @Test
    void summarizeLeavesIcIrUndefinedWhenIcSeriesHasNoDispersion() {
        List<FactorResearchEngine.CrossSectionObservation> day1 = new ArrayList<>();
        List<FactorResearchEngine.CrossSectionObservation> day2 = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            day1.add(obs(i * 10.0, i * 0.01));
            day2.add(obs(i * 5.0, i * 0.02));
        }
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        byDate.put("2025-01-02", day1);
        byDate.put("2025-01-03", day2);

        FactorResearchEngine.FactorResearchSummary summary =
                FactorResearchEngine.summarize(byDate, 5, 5, 10, "test");

        assertThat(summary.crossSectionDays()).isEqualTo(2);
        assertThat(summary.rankIcStd()).isEqualTo(0.0);
        // 两日 IC 都是 1.0 ⟹ IC 序列无离散度 ⟹ ICIR 解析上趋于无穷，**无定义**。
        // 修复前硬写 0.0，与「IR 恰为 0」不可分辨（Q-R006-05）：显式装箱以便断言 null。
        Double rankIcIr = summary.rankIcIr();
        assertThat(rankIcIr).isNull();
    }

    // ---------------------------------------------------------------------
    // 裁定 A（D-R006-05 / D-R007-01 / D-R013-02）：不可用统计量一律 null，禁止用 0.0 冒充。
    // 以下守卫：改前失败、改后通过；其中「真零」用例同时防止过度置空。
    // ---------------------------------------------------------------------

    @Test
    void emptySummaryEmitsNullStatisticsNotPlaceholderZero() {
        // 无任何截面日 ⟹ 统计量全部无定义：写 null，不得写 0.0（0 是合法取值，含义相反）
        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(Map.of(), 5, 5, 10, "test");

        assertThat(s.crossSectionDays()).isZero();
        assertThat(s.totalObservations()).isZero();
        // 显式转 Double 以便在「改前仍是 double」的版本上也能编译：那时断言失败（实际 0.0），
        // 改后包装类型为 null，断言通过 —— 这正是本任务要求的双向证明。
        assertThat((Double) s.rankIcMean()).isNull();
        assertThat((Double) s.rankIcStd()).isNull();
        assertThat((Double) s.rankIcPositiveRate()).isNull();
        assertThat(s.rankIcIr()).isNull();
        assertThat(s.pearsonIcMean()).isNull();
        assertThat((Double) s.hacStd()).isNull();
        assertThat((Double) s.ciLower()).isNull();
        assertThat((Double) s.ciUpper()).isNull();
        assertThat((Double) s.longShortSpread()).isNull();
        assertThat(s.monotonicity()).isEqualTo("N/A");
        // 整数计数是真实计数（0 个日 / 0 个观测），照常给数值
        assertThat(s.inputDays()).isZero();
        assertThat(s.daysBelowMinCrossSection()).isZero();
        assertThat(s.groupsWithObservations()).isZero();
        assertThat(s.pearsonSupportDays()).isZero();
    }

    @Test
    void degenerateSummaryBelowMinCrossSectionEmitsNullStatisticsButRealDropCounters() {
        // 唯一截面日规模不足被丢弃 ⟹ 无可用 IC：统计量 null，但丢弃计数必须照常给真实数值
        List<FactorResearchEngine.CrossSectionObservation> small =
                List.of(obs(10, 0.01), obs(20, 0.02), obs(30, 0.03));
        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(singleDay("2025-01-02", small), 5, 5, 10, "test");

        assertThat(s.crossSectionDays()).isZero();
        assertThat((Double) s.rankIcMean()).isNull();
        assertThat((Double) s.rankIcStd()).isNull();
        assertThat((Double) s.rankIcPositiveRate()).isNull();
        assertThat((Double) s.hacStd()).isNull();
        assertThat((Double) s.ciLower()).isNull();
        assertThat((Double) s.ciUpper()).isNull();
        assertThat((Double) s.longShortSpread()).isNull();
        // 丢弃计数：入参 1 日、因规模不足丢 1 日（真实计数，不是占位）
        assertThat(s.inputDays()).isEqualTo(1);
        assertThat(s.daysBelowMinCrossSection()).isEqualTo(1);
    }

    @Test
    void genuineZeroStatisticsStayZeroAndAreDistinguishableFromNull() {
        // 两日 IC 均为 -1.0 ⟹ 真零离散度（std = 0.0）与真零正占比（0.0）；ICIR 无定义（null）。
        // 本用例是「防止过度置空」的守卫：真零必须仍是 0.0，不能与 null 混同。
        List<FactorResearchEngine.CrossSectionObservation> descending = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            descending.add(obs(i * 10.0, 0.11 - i * 0.01));
        }
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        byDate.put("2025-01-02", descending);
        byDate.put("2025-01-03", descending);

        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(byDate, 5, 5, 10, "test");

        assertThat(s.crossSectionDays()).isEqualTo(2);
        assertThat(s.rankIcMean()).isEqualTo(-1.0);
        assertThat(s.rankIcStd()).isEqualTo(0.0);
        assertThat(s.rankIcPositiveRate()).isEqualTo(0.0);
        assertThat(s.rankIcIr()).isNull();
        // T = 2 ⟹ HAC 与区间可计算（常数序列的真零标准误），不得置空
        assertThat(s.hacStd()).isNotNull();
        assertThat(s.hacStd()).isEqualTo(0.0);
        assertThat(s.ciLower()).isNotNull();
        assertThat(s.ciUpper()).isNotNull();
    }

    @Test
    void genuineZeroSpreadStaysZeroWhileUndefinedIntervalBecomesNull() {
        // groupCount = 2，四只股票：G1 = {0.01, 0.03}、G2 = {0.02, 0.02} ⟹ 两组均值都恰为 0.02，
        // 极差真的等于 0（不是「无分组」）。T = 1 ⟹ HAC/区间无定义 ⟹ null。
        // 一个输出 0.0、一个输出 null，恰好证明二者可区分。
        List<FactorResearchEngine.CrossSectionObservation> observations = List.of(
                obs(10.0, 0.01), obs(20.0, 0.03), obs(30.0, 0.02), obs(40.0, 0.02));
        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(singleDay("2025-01-02", observations), 5, 2, 2, "test");

        assertThat(s.crossSectionDays()).isEqualTo(1);
        assertThat(s.longShortSpread()).isEqualTo(0.0);
        assertThat((Double) s.hacStd()).isNull();
        assertThat((Double) s.ciLower()).isNull();
        assertThat((Double) s.ciUpper()).isNull();
    }

    @Test
    void groupWithoutObservationsReportsNullMeanAndZeroCount() {
        // groupCount = 5 但当日只有 2 个可用观测 ⟹ 只有 G1/G2 非空，G3..G5 无观测：
        // 均值必须是 null（不得是占位 0.0），计数必须是 0。
        List<FactorResearchEngine.CrossSectionObservation> observations = List.of(
                obs(10.0, 0.01), obs(20.0, 0.02));

        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(singleDay("2025-01-02", observations), 5, 5, 2, "test");

        assertThat(s.groupedReturns()).hasSize(5);
        List<FactorResearchEngine.GroupReturnStat> empty = s.groupedReturns().subList(2, 5);
        assertThat(empty).allMatch(g -> (Double) g.avgReturn() == null);
        assertThat(empty).allMatch(g -> g.observations() == 0);
        // 非空组仍有真实均值；spread 只用非空组，且不因空组被置为 null
        assertThat(s.groupedReturns().get(0).avgReturn()).isEqualTo(0.01);
        assertThat(s.groupedReturns().get(1).avgReturn()).isEqualTo(0.02);
        assertThat(s.longShortSpread()).isEqualTo(0.01);
        assertThat(s.groupsWithObservations()).isEqualTo(2);
        // 空组（null 均值）不参与方向断言
        assertThat(s.monotonicity()).isEqualTo("MONOTONIC_INCREASING");
    }

    @Test
    void groupMeanNullIsNotUsableForMonotonicityAssertions() {
        // 直接构造含 null 均值的组序列：空组等价于「无观测」，必须被判为不可用。
        // 用 Double 变量（而非裸 null）以便该用例在改前的「double 原语」契约上也能编译：
        // 那时构造/断言会因拆箱 null 而失败，改后为正常断言通过。
        Double nullMean = null;
        List<FactorResearchEngine.GroupReturnStat> grouped = List.of(
                new FactorResearchEngine.GroupReturnStat(1, "G1", 0.01, 10),
                new FactorResearchEngine.GroupReturnStat(2, "G2", nullMean, 0),
                new FactorResearchEngine.GroupReturnStat(3, "G3", 0.03, 10)
        );
        assertThat(FactorResearchEngine.assessMonotonicity(grouped)).isEqualTo("MONOTONIC_INCREASING");
    }
}
