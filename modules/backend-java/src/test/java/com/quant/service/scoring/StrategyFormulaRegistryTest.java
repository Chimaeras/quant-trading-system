package com.quant.service.scoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyFormulaRegistryTest {

    @Test
    void classicMediumProfileWarnsShortHorizonMisuse() {
        StrategyFormulaRegistry.FormulaAudit audit = StrategyFormulaRegistry.audit(
                "CLASSIC_SELECTOR", "v1", StrategyFormulaRegistry.HORIZON_MEDIUM_TREND);
        assertThat(audit.shortHorizonFitScore()).isLessThan(50);
        assertThat(audit.shortHorizonVerdict()).contains("3");
    }

    @Test
    void classicShortProfileUsesV2NeutralFormula() {
        StrategyFormulaRegistry.FormulaAudit audit = StrategyFormulaRegistry.audit(
                "CLASSIC_SELECTOR", CodexShortCycleScoringEngine.FORMULA_VERSION,
                StrategyFormulaRegistry.HORIZON_SHORT_3_5D);
        assertThat(audit.shortHorizonFitScore()).isGreaterThan(90);
        assertThat(audit.formulaSummary()).contains("OLS");
    }

    @Test
    void oversoldStrategyRatesHighForShortHorizon() {
        StrategyFormulaRegistry.FormulaAudit audit = StrategyFormulaRegistry.audit(
                "OVERSOLD_REBOUND", "v1", null);
        assertThat(audit.shortHorizonFitScore()).isGreaterThan(80);
    }
}
