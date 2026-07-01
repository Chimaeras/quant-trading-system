package com.quant.service.scoring;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortHorizonFactorScorerTest {

    @Test
    void rewardsOversoldRepairWithRsiTurningUp() {
        List<Double> close = new ArrayList<>();
        List<Double> amount = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            close.add(10.0 - i * 0.05);
            amount.add(2_000_000.0);
        }
        for (int i = 0; i < 5; i++) {
            close.add(8.6 + i * 0.08);
            amount.add(3_000_000.0);
        }
        double[] arr = close.stream().mapToDouble(Double::doubleValue).toArray();
        ShortHorizonFactorScorer.ShortHorizonResult r = ShortHorizonFactorScorer.score(close, amount, arr);
        assertThat(r.score()).isGreaterThan(25);
        assertThat(r.breakdown().get("repair_component")).isNotNull();
    }

    @Test
    void penalizesExtremeShortTermChase() {
        List<Double> close = new ArrayList<>();
        List<Double> amount = new ArrayList<>();
        for (int i = 0; i < 34; i++) {
            close.add(10.0);
            amount.add(2_000_000.0);
        }
        close.set(close.size() - 1, 12.5);
        close.set(close.size() - 2, 12.0);
        close.set(close.size() - 3, 11.5);
        amount.set(amount.size() - 1, 2_500_000.0);
        double[] arr = close.stream().mapToDouble(Double::doubleValue).toArray();
        ShortHorizonFactorScorer.ShortHorizonResult chased = ShortHorizonFactorScorer.score(close, amount, arr);

        assertThat(chased.inputs().ret3()).isGreaterThan(8);
        assertThat(chased.score()).isLessThan(45);
    }
}
