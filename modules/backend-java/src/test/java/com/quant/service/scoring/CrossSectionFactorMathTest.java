package com.quant.service.scoring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossSectionFactorMathTest {

    @Test
    void winsorizeClipsExtremeValues() {
        List<Double> raw = List.of(1.0, 2.0, 3.0, 4.0, 100.0);
        List<Double> w = CrossSectionFactorMath.winsorize(raw, 2.5, 97.5);
        assertThat(w.get(4)).isLessThan(100.0);
        assertThat(w.get(0)).isEqualTo(1.0);
    }

    @Test
    void robustZScoresCenterAroundZero() {
        List<Double> values = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        List<Double> z = CrossSectionFactorMath.robustZScores(values);
        assertThat(z).hasSize(5);
        double avg = z.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        assertThat(avg).isCloseTo(0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void percentileScoresSpanZeroToHundred() {
        List<Double> values = List.of(10.0, 20.0, 30.0, 40.0);
        List<Double> scores = CrossSectionFactorMath.percentileScores(values);
        assertThat(scores.get(0)).isEqualTo(0.0);
        assertThat(scores.get(3)).isEqualTo(100.0);
    }

    @Test
    void robustZScoresOnResidualsDoesNotShrinkOutliersLikeDoubleWinsorize() {
        List<Double> residuals = List.of(-2.0, -0.5, 0.0, 0.5, 2.0);
        List<Double> z = CrossSectionFactorMath.robustZScoresOnResiduals(residuals);
        assertThat(z.get(0)).isLessThan(-1.0);
        assertThat(z.get(4)).isGreaterThan(1.0);
    }

    @Test
    void robustZScoresByGroupUsesIndustryWhenLargeEnough() {
        List<Double> values = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 10.0, 11.0, 12.0, 13.0, 14.0);
        List<String> groups = List.of("A", "A", "A", "A", "A", "B", "B", "B", "B", "B");
        List<Double> z = CrossSectionFactorMath.robustZScoresByGroup(values, groups);
        assertThat(z).hasSize(10);
        assertThat(z.stream().mapToDouble(Double::doubleValue).max()).isLessThanOrEqualTo(3.0);
    }
}
