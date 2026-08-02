package com.quant.service.backtest;

import org.junit.jupiter.api.Test;

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
        List<FactorResearchEngine.GroupReturnStat> groups =
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
    void summarizeIncludesHacStdAndConfidenceInterval() {
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

        assertThat(summary.hacStd()).isGreaterThanOrEqualTo(0);
        assertThat(summary.ciLower()).isLessThanOrEqualTo(summary.ciUpper());
    }
}
