package com.quant.service.scoring;

import com.quant.entity.MarketData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossSectionNeutralizerTest {

    @Test
    void fullRegressionRemovesLogMvTrend() {
        List<String> industries = new ArrayList<>();
        List<Double> logMv = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            industries.add(i % 4 == 0 ? "A" : i % 4 == 1 ? "B" : i % 4 == 2 ? "C" : "D");
            double lm = 20 + i * 0.05;
            logMv.add(lm);
            values.add(0.5 * lm + (i % 4) * 0.1);
        }
        CrossSectionNeutralizer.Context ctx = CrossSectionNeutralizer.buildContext(industries, logMv);
        assertThat(ctx.mode()).isEqualTo(CrossSectionNeutralizer.MODE_FULL);
        CrossSectionNeutralizer.Result res = CrossSectionNeutralizer.neutralize(values, ctx);
        double mean = res.residuals().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        assertThat(mean).isCloseTo(0, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void smallSampleFallsBackToIndustryDemean() {
        List<String> industries = List.of("A", "A", "B", "B");
        List<Double> logMv = List.of(20.0, 21.0, 20.5, 21.5);
        List<Double> values = List.of(1.0, 1.2, 0.5, 0.6);
        CrossSectionNeutralizer.Context ctx = CrossSectionNeutralizer.buildContext(industries, logMv);
        assertThat(ctx.mode()).isEqualTo(CrossSectionNeutralizer.MODE_INDUSTRY_DEMEAN);
        CrossSectionNeutralizer.Result res = CrossSectionNeutralizer.neutralize(values, ctx);
        assertThat(res.residuals()).hasSize(4);
    }

    @Test
    void neutralizeBatchReusesContext() {
        List<String> industries = new ArrayList<>();
        List<Double> logMv = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            industries.add(i % 3 == 0 ? "A" : i % 3 == 1 ? "B" : "C");
            logMv.add(21.0 + i * 0.01);
        }
        CrossSectionNeutralizer.Context ctx = CrossSectionNeutralizer.buildContext(industries, logMv);
        Map<String, List<Double>> cols = Map.of(
                "f1", logMv.stream().map(v -> v * 0.3).toList(),
                "f2", logMv.stream().map(v -> v * 0.1 + 1).toList()
        );
        Map<String, CrossSectionNeutralizer.Result> batch = CrossSectionNeutralizer.neutralizeBatch(cols, ctx);
        assertThat(batch).containsKeys("f1", "f2");
        assertThat(batch.get("f1").residuals()).hasSize(35);
    }

    @Test
    void logMarketCapProxyUsesTurnoverWhenAvailable() {
        MarketData bar = new MarketData();
        bar.setAmount(BigDecimal.valueOf(100_000_000));
        bar.setTurnoverRate(BigDecimal.valueOf(2.5));
        double logMv = CrossSectionNeutralizer.logMarketCapProxy(bar, 1_000_000);
        assertThat(logMv).isGreaterThan(20);
    }

    @Test
    void solveSymmetricRecoversKnownSystem() {
        double[][] a = {{2, 1}, {1, 3}};
        double[] b = {1, 2};
        double[] x = CrossSectionNeutralizer.solveSymmetric(a, b);
        assertThat(x).isNotNull();
        assertThat(x[0]).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.01));
    }
}
