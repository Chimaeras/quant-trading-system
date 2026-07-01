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
}
