package com.quant.service.scoring;

import com.quant.entity.MarketData;
import com.quant.service.scoring.model.CandidateScore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodexShortCycleScoringEngineTest {

    private final CodexShortCycleScoringEngine engine = new CodexShortCycleScoringEngine();

    @Test
    void scoreCrossSectionProducesPercentileScores() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0.01, 0.03, 0.05, 0.02, -0.5, 0.1,
                        0.15, 0.10, 40, 1.5, 0.05, 0.3, 0.25, 0.01, 1e-8, 0.02, 50_000_000),
                raw("000002", "SZSE", "B", "银行", 0.02, 0.05, 0.08, 0.04, -0.3, 0.2,
                        0.20, 0.15, 35, 1.8, 0.06, 0.4, 0.20, 0.015, 2e-8, 0.03, 60_000_000),
                raw("000003", "SZSE", "C", "科技", -0.01, 0.01, 0.02, 0.01, -0.8, 0.05,
                        0.10, 0.05, 55, 1.2, 0.03, 0.2, 0.35, 0.30, 5e-8, 0.05, 40_000_000)
        );
        List<CodexShortCycleScoringEngine.ScoredRow> rows =
                CodexShortCycleScoringEngine.scoreCrossSection(
                        raws, 0.5, CodexShortCycleScoringEngine.buildNeutralizationContext(raws));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).breakdown()).containsKeys("T", "F", "R", "S_raw", "G_market", "neutralization_mode");
    }

    @Test
    void scoreUniverseFiltersWeakFundamentalAndRisk() {
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = List.of(
                context("000001", "SZSE", "强势", sampleBars(80, 10, 50_000_000, 0.02)),
                context("000002", "SZSE", "弱势", sampleBars(80, 10, 50_000_000, -0.01))
        );
        List<CandidateScore> scored = engine.scoreUniverse(contexts, 1_000_000, true, 60);
        assertThat(scored).isNotEmpty();
        for (CandidateScore c : scored) {
            assertThat(c.getReasons().get("scoring_model"))
                    .isEqualTo(CodexShortCycleScoringEngine.FORMULA_VERSION);
            assertThat(c.getReasons()).containsKeys("T", "F", "R");
        }
    }

    @Test
    void scoreUniverseRespectsMinCodexScoreThreshold() {
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String code = String.format("%06d", i + 1);
            contexts.add(new CodexShortCycleScoringEngine.StockBarContext(
                    code, "SZSE", "测试" + i, "银行",
                    sampleBars(80, 10 + i * 0.5, 50_000_000, 0.02 + i * 0.001),
                    null));
        }
        List<CandidateScore> strict = engine.scoreUniverse(contexts, 1_000_000, true, 60, 99);
        List<CandidateScore> relaxed = engine.scoreUniverse(contexts, 1_000_000, true, 60, 0);
        assertThat(relaxed.size()).isGreaterThanOrEqualTo(strict.size());
    }

    @Test
    void excludesLowLiquidityBars() {
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = List.of(
                context("000001", "SZSE", "低流动性", sampleBars(80, 10, 100_000))
        );
        List<CandidateScore> scored = engine.scoreUniverse(contexts, 1_000_000, true, 60);
        assertThat(scored).isEmpty();
    }

    @Test
    void imputeFinancialByIndustryFillsPlaceholderDebt() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0, 0.1, 0, 50, 1.5, 0, 0, 0, 0, 0, 0, 1),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0, 0.2, 0, 35, 1.8, 0, 0, 0, 0, 0, 0, 1)
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed = CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        assertThat(imputed.get(0).debtRatio()).isEqualTo(42.5);
    }

    @Test
    void estimateMarketStateUsesValueWeighting() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0.10, 0.10, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0.10, 25.0, "test"),
                raw("000002", "SZSE", "B", "银行", 0, 0.01, 0.01, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0.01, 18.0, "test")
        );
        double g = CodexShortCycleScoringEngine.estimateMarketState(raws);
        assertThat(g).isFinite();
    }

    @Test
    void applyIndustryRelativeReturnsSubtractsIndustryMeans() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0.10, 0.05, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0.10, 22.0),
                raw("000002", "SZSE", "B", "银行", 0.02, 0.01, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0.02, 22.1)
        );
        List<CodexShortCycleScoringEngine.RawFeatures> adj =
                CodexShortCycleScoringEngine.applyIndustryRelativeReturns(raws);
        assertThat(adj.get(0).er5()).isCloseTo(0.02, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void countImputedFinancialFieldsDetectsDebtImputation() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0, 0.1, 0, 50, 1.5, 0, 0, 0, 0, 0, 0, 1, 0, 22, "test"),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0, 0.2, 0, 35, 1.8, 0, 0, 0, 0, 0, 0, 1, 0, 22, "test")
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed = CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        assertThat(CodexShortCycleScoringEngine.countImputedFinancialFields(raws.get(0), imputed.get(0))).isGreaterThan(0);
    }

    @Test
    void scoreUniverseRecordsTurnoverLogMvSource() {
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = List.of(
                context("000001", "SZSE", "测试", sampleBars(80, 10, 50_000_000, 0.02))
        );
        // 单票无法截面打分，至少验证 extract 路径在多样本场景
        List<CodexShortCycleScoringEngine.StockBarContext> multi = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            multi.add(new CodexShortCycleScoringEngine.StockBarContext(
                    String.format("%06d", i + 1), "SZSE", "测试" + i, "银行",
                    sampleBars(80, 10 + i * 0.1, 50_000_000, 0.01), null));
        }
        List<CandidateScore> scored = engine.scoreUniverse(multi, 1_000_000, true, 60, 0);
        assertThat(scored).isNotEmpty();
        assertThat(scored.get(0).getReasons().get("log_mv_source"))
                .isEqualTo(CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER);
    }

    private static CodexShortCycleScoringEngine.StockBarContext context(
            String code, String ex, String name, List<MarketData> bars) {
        return new CodexShortCycleScoringEngine.StockBarContext(code, ex, name, "测试", bars, null);
    }

    private static CodexShortCycleScoringEngine.RawFeatures raw(
            String code, String ex, String name, String industry,
            double er1, double er5, double er20m5, double maAlign, double rsiShape, double volConfirm,
            double roe, double rev, double debt, double curr, double ey, double bm,
            double vol20, double downVol, double illiq, double maxGap, double avgAmt) {
        return raw(code, ex, name, industry, er1, er5, er20m5, maAlign, rsiShape, volConfirm,
                roe, rev, debt, curr, ey, bm, vol20, downVol, illiq, maxGap, avgAmt, er5 + er20m5, 22.0, "test");
    }

    private static CodexShortCycleScoringEngine.RawFeatures raw(
            String code, String ex, String name, String industry,
            double er1, double er5, double er20m5, double maAlign, double rsiShape, double volConfirm,
            double roe, double rev, double debt, double curr, double ey, double bm,
            double vol20, double downVol, double illiq, double maxGap, double avgAmt,
            double ret20, double logMarketCap, String logMvSource) {
        return new CodexShortCycleScoringEngine.RawFeatures(
                code + "|" + ex, code, ex, name, industry,
                er1, er5, er20m5, maAlign, rsiShape, volConfirm,
                roe, rev, debt, curr, ey, bm,
                vol20, downVol, illiq, maxGap, avgAmt, ret20, logMarketCap, logMvSource
        );
    }

    private static List<MarketData> sampleBars(int count, double close, double amount, double dailyStep) {
        List<MarketData> bars = new ArrayList<>(count);
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < count; i++) {
            MarketData bar = new MarketData();
            bar.setStockCode("000001");
            bar.setExchangeType("SZSE");
            bar.setTradeDate(start.plusDays(i));
            double c = close + i * dailyStep;
            bar.setClosePrice(BigDecimal.valueOf(c));
            bar.setOpenPrice(BigDecimal.valueOf(c * 0.995));
            bar.setAmount(BigDecimal.valueOf(amount));
            bar.setVolume(1_000_000L);
            bar.setTurnoverRate(BigDecimal.valueOf(2.5));
            bars.add(bar);
        }
        return bars;
    }
}
