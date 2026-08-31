package com.quant.service.scoring;

import com.quant.entity.MarketData;
import com.quant.service.scoring.model.CandidateScore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        assertThat(rows.get(0).breakdown()).containsKeys("T", "F", "R", "S_raw", "G_market", "neutralization_mode", "G_weighting");
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
    void scoreUniverseRespectsMinTrendScoreThreshold() {
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String code = String.format("%06d", i + 1);
            List<MarketData> bars = sampleBars(80, 10 + i * 0.5, 50_000_000, 0.02 + i * 0.001);
            contexts.add(new CodexShortCycleScoringEngine.StockBarContext(
                    code, "SZSE", "测试" + i, "银行", bars, null,
                    bars.get(bars.size() - 1).getTradeDate(),
                    bars.get(bars.size() - 1).getTradeDate().minusDays(200), 1));
        }
        List<CandidateScore> relaxed = engine.scoreUniverse(contexts, 1_000_000, true, 60, 0, 0);
        List<CandidateScore> strict = engine.scoreUniverse(contexts, 1_000_000, true, 60, 0, 99);
        assertThat(relaxed.size()).isGreaterThanOrEqualTo(strict.size());
        for (CandidateScore c : strict) {
            assertThat(c.getTrendScore()).isGreaterThanOrEqualTo(99);
            assertThat(c.isPassMinTrendScore()).isTrue();
            assertThat(c.getReasons()).containsEntry("pass_min_trend_score", true);
        }
    }

    @Test
    void toCandidateReflectsActualPassFlags() {
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = List.of(
                context("000001", "SZSE", "测试", sampleBars(80, 10, 50_000_000, 0.02))
        );
        List<CandidateScore> scored = engine.scoreUniverse(contexts, 1_000_000, true, 60, 0, 0);
        assertThat(scored).isNotEmpty();
        CandidateScore c = scored.get(0);
        assertThat(c.getReasons()).containsKeys(
                "pass_min_avg_amount", "pass_min_trend_score", "pass_ma_trend", "pass_ret20");
        assertThat(c.isPassMinAvgAmount()).isTrue();
    }

    @Test
    void toCandidateDisclosesSkippedSizeNeutralization() throws Exception {
        // R6: 市值可靠性不足时 size(lnMV) 中性化被跳过，须在 reasons 显式披露而非静默。
        CodexShortCycleScoringEngine.RawFeatures rf = raw(
                "000001", "SZSE", "测试", "银行",
                0.01, 0.03, 0.05, 0.02, -0.5, 0.1,
                0.15, 0.10, 40, 1.5, 0.05, 0.3,
                0.25, 0.01, 1e-8, 0.02, 50_000_000, 0.01, 22.0,
                CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER, CrossSectionNeutralizer.REL_UNKNOWN_LEGACY);
        Map<String, Object> breakdown = new java.util.LinkedHashMap<>();
        breakdown.put("neutralization_mode", CrossSectionNeutralizer.MODE_SKIPPED_MV_RELIABILITY);
        breakdown.put("mv_reliable_count", 0);
        breakdown.put("mv_total_count", 5);
        CodexShortCycleScoringEngine.ScoredRow row =
                new CodexShortCycleScoringEngine.ScoredRow(rf, 0.5, 0.4, -0.3, 40, 50, breakdown);

        var invoke = CodexShortCycleScoringEngine.class.getDeclaredMethod("toCandidate",
                CodexShortCycleScoringEngine.ScoredRow.class, double.class, double.class, double.class, int.class);
        invoke.setAccessible(true);
        CandidateScore c = (CandidateScore) invoke.invoke(null, row, 0.0, 0.0, 1_000_000.0, 0);

        assertThat(c.getReasons()).containsKey("size_neutralization_note");
        assertThat(c.getReasons().get("size_neutralization_note").toString()).contains("被跳过");

        // 非跳过时不应有该披露
        breakdown.put("neutralization_mode", CrossSectionNeutralizer.MODE_FULL);
        CodexShortCycleScoringEngine.ScoredRow fullRow =
                new CodexShortCycleScoringEngine.ScoredRow(rf, 0.5, 0.4, -0.3, 40, 50, breakdown);
        CandidateScore full = (CandidateScore) invoke.invoke(null, fullRow, 0.0, 0.0, 1_000_000.0, 0);
        assertThat(full.getReasons()).doesNotContainKey("size_neutralization_note");
    }

    @Test
    void scoreUniverseRespectsMinCodexScoreThreshold() {
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String code = String.format("%06d", i + 1);
            List<MarketData> bars = sampleBars(80, 10 + i * 0.5, 50_000_000, 0.02 + i * 0.001);
            LocalDate tradeDate = bars.get(bars.size() - 1).getTradeDate();
            contexts.add(new CodexShortCycleScoringEngine.StockBarContext(
                    code, "SZSE", "测试" + i, "银行", bars, null, tradeDate, tradeDate.minusDays(200), 1));
        }
        List<CandidateScore> strict = engine.scoreUniverse(contexts, 1_000_000, true, 60, 99);
        List<CandidateScore> relaxed = engine.scoreUniverse(contexts, 1_000_000, true, 60, 0);
        assertThat(relaxed.size()).isGreaterThanOrEqualTo(strict.size());
    }

    @Test
    void excludesLowLiquidityBars() {
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = List.of(
                context("000001", "SZSE", "低流动性", sampleBars(80, 10, 100_000, 0))
        );
        List<CandidateScore> scored = engine.scoreUniverse(contexts, 1_000_000, true, 60);
        assertThat(scored).isEmpty();
    }

    @Test
    void imputeFinancialByIndustryFillsMissingRoeWithIndustryMedian() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        0, 0, 0, 0, 1, 0, 22, "test"),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0,
                        12, 8, 35, 1.8, 0.05, 0.3, 0, 0, 0, 0, 1, 0, 22, "test")
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed =
                CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        assertThat(imputed.get(0).roe()).isEqualTo(12);
        assertThat(imputed.get(0).revenueYoY()).isEqualTo(8);
    }

    @Test
    void imputeFinancialPreservesRealZeroRoe() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0,
                        0, 5, 40, 1.5, 0.04, 0.2, 0, 0, 0, 0, 1, 0, 22, "test"),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0,
                        15, 10, 35, 1.8, 0.05, 0.3, 0, 0, 0, 0, 1, 0, 22, "test")
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed =
                CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        assertThat(imputed.get(0).roe()).isEqualTo(0);
    }

    @Test
    void imputeFinancialByIndustryFillsMissingDebtWithIndustryMedian() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0, 0.1, 0,
                        Double.NaN, 1.5, 0, 0, 0, 0, 0, 0, 1),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0, 0.2, 0, 35, 1.8, 0, 0, 0, 0, 0, 0, 1)
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed = CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        assertThat(imputed.get(0).debtRatio()).isEqualTo(35);
    }

    @Test
    void imputeFinancialByIndustryPreservesRealDebtRatio50() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0, 0.1, 0, 50, 1.5, 0, 0, 0, 0, 0, 0, 1),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0, 0.2, 0, 35, 1.8, 0, 0, 0, 0, 0, 0, 1)
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed = CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        assertThat(imputed.get(0).debtRatio()).isEqualTo(50);
    }

    @Test
    void estimateMarketStateLargeCapDominatesWithReliableLogMv() {
        // 市值可靠性须为 OFFICIAL，否则 estimateMarketState 退化为等权
        // （isReliableLogMv 仅放行 reliability==OFFICIAL）。
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "大", "银行", 0, 0.20, 0.20, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0.10, 0, 0, 25, 0.20, 25.0,
                        CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER, CrossSectionNeutralizer.REL_OFFICIAL),
                raw("000002", "SZSE", "小", "银行", 0, -0.20, -0.20, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0.30, 0, 0, 1, -0.20, 18.0,
                        CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER, CrossSectionNeutralizer.REL_OFFICIAL)
        );
        double gWeighted = CodexShortCycleScoringEngine.estimateMarketState(raws);
        assertThat(gWeighted).isGreaterThan(0);
    }

    @Test
    void estimateMarketStateUsesEqualWeightForWeakLogMvProxy() {
        List<CodexShortCycleScoringEngine.RawFeatures> weak = List.of(
                raw("000001", "SZSE", "大", "银行", 0, 0.20, 0.20, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0.10, 0, 0, 25, 0.20, 25.0,
                        CrossSectionNeutralizer.LOG_MV_FROM_AVG_AMOUNT),
                raw("000002", "SZSE", "小", "银行", 0, -0.20, -0.20, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0.30, 0, 0, 1, -0.20, 18.0,
                        CrossSectionNeutralizer.LOG_MV_FROM_AVG_AMOUNT)
        );
        double gEqual = CodexShortCycleScoringEngine.estimateMarketState(weak);
        assertThat(gEqual).isFinite();
    }

    @Test
    void estimateMarketStateUsesValueWeighting() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0.10, 0.10, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0.10, 25.0,
                        CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER),
                raw("000002", "SZSE", "B", "银行", 0, 0.01, 0.01, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0.01, 18.0,
                        CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER)
        );
        double g = CodexShortCycleScoringEngine.estimateMarketState(raws);
        assertThat(g).isFinite();
    }

    @Test
    void buildNeutralizationContextReplacesWeakLogMvWithReliableMedian() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 24.0,
                        CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 17.0,
                        CrossSectionNeutralizer.LOG_MV_FROM_AVG_AMOUNT)
        );
        CrossSectionNeutralizer.Context ctx = CodexShortCycleScoringEngine.buildNeutralizationContext(raws);
        assertThat(ctx.logMv().get(0)).isEqualTo(24.0);
        assertThat(ctx.logMv().get(1)).isEqualTo(24.0);
    }

    @Test
    void applyIndustryRelativeReturnsSubtractsIndustryMeans() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0.10, 0.05, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0.10, 22.0, "test"),
                raw("000002", "SZSE", "B", "银行", 0.02, 0.01, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0.02, 22.1, "test")
        );
        List<CodexShortCycleScoringEngine.RawFeatures> adj =
                CodexShortCycleScoringEngine.applyIndustryRelativeReturns(raws);
        assertThat(adj.get(0).er5()).isCloseTo(0.02, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void countImputedFinancialFieldsDetectsDebtImputation() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0, 0.1, 0,
                        Double.NaN, 1.5, 0, 0, 0, 0, 0, 0, 1, 0, 22, "test"),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0, 0.2, 0, 35, 1.8, 0, 0, 0, 0, 0, 0, 1, 0, 22, "test")
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed = CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        assertThat(CodexShortCycleScoringEngine.countImputedFinancialFields(raws.get(0), imputed.get(0))).isGreaterThan(0);
    }

    /** R20-P2 回归：全行业真实中位数为 0 时，缺失值应填 0（真实中位数）而非兜底常量。 */
    @Test
    void imputeFinancialUsesLegitimateZeroMedianNotFallback() {
        // 两只股票 debtRatio 都是真实 0（全债务豁免行业），第三只缺失 → 中位数 0 应被采纳
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0,
                        0.1, 5, 0, 1.5, 0.04, 0.2, 0, 0, 0, 0, 1, 0, 22, "test"),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0,
                        0.2, 6, 0, 1.6, 0.04, 0.2, 0, 0, 0, 0, 1, 0, 22, "test"),
                raw("000003", "SZSE", "C", "银行", 0, 0, 0, 0, 0, 0,
                        0.3, 7, Double.NaN, 1.7, 0.04, 0.2, 0, 0, 0, 0, 1, 0, 22, "test")
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed = CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        // 修复前：|0|>1e-9 为 false → 错误填兜底 50；修复后：isNaN 才走兜底 → 填真实中位数 0
        assertThat(imputed.get(2).debtRatio()).isEqualTo(0);
    }

    /** R20-P3 边界：全行业该字段全缺失 → 兜底常量。 */
    @Test
    void imputeFinancialAllMissingFallsBackToConstants() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        0, 0, 0, 0, 1, 0, 22, "test"),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        0, 0, 0, 0, 1, 0, 22, "test")
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed = CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        assertThat(imputed.get(0).debtRatio()).isEqualTo(50);
        assertThat(imputed.get(0).currentRatio()).isEqualTo(1.5);
        assertThat(imputed.get(0).roe()).isEqualTo(0);
        // P1-1：值因子（earningsYield/bookToMarket）全缺失时同样回退到兜底 0——记录该"估值因子退化"现状，
        // 供披露/后续接入官方 PE/PB 源时对比。
        assertThat(imputed.get(0).earningsYield()).isEqualTo(0);
        assertThat(imputed.get(0).bookToMarket()).isEqualTo(0);
    }

    /** R20-P3 边界：单股成组 → 中位数即自身；空行业名 → "_none_" 桶共享中位数。 */
    @Test
    void imputeFinancialSingleStockAndBlankIndustryBucket() {
        // 空行业名的两只股票须落入同一 "_none_" 桶，缺失 roe 用另一只的中位数填充
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "", 0, 0, 0, 0, 0, 0,
                        Double.NaN, 5, 40, 1.5, 0.04, 0.2, 0, 0, 0, 0, 1, 0, 22, "test"),
                raw("000002", "SZSE", "B", "", 0, 0, 0, 0, 0, 0,
                        11, 5, 40, 1.5, 0.04, 0.2, 0, 0, 0, 0, 1, 0, 22, "test")
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed = CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        assertThat(imputed.get(0).roe()).isEqualTo(11);
    }

    /** R20-P3 边界：偶数个非缺失值 → 中位数取中间两值均值。 */
    @Test
    void imputeFinancialEvenCountMedianAverages() {
        // 4 只股票中 2 只 roe 缺失，非缺失为 [10, 30] → 偶数中位数 (10+30)/2 = 20
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0,
                        Double.NaN, 5, 40, 1.5, 0.04, 0.2, 0, 0, 0, 0, 1, 0, 22, "test"),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0,
                        Double.NaN, 5, 40, 1.5, 0.04, 0.2, 0, 0, 0, 0, 1, 0, 22, "test"),
                raw("000003", "SZSE", "C", "银行", 0, 0, 0, 0, 0, 0,
                        10, 5, 40, 1.5, 0.04, 0.2, 0, 0, 0, 0, 1, 0, 22, "test"),
                raw("000004", "SZSE", "D", "银行", 0, 0, 0, 0, 0, 0,
                        30, 5, 40, 1.5, 0.04, 0.2, 0, 0, 0, 0, 1, 0, 22, "test")
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed = CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        assertThat(imputed.get(0).roe()).isEqualTo(20);
        assertThat(imputed.get(1).roe()).isEqualTo(20);
    }

    /** P1-1 回归：值因子缺失须用同行业真实中位数填充，不得退化为 0 参与排序（MISSING_FINANCIAL=NaN 被剔除）。 */
    @Test
    void valueFactorMissingImputedFromIndustryMedianNotZero() {
        List<CodexShortCycleScoringEngine.RawFeatures> raws = List.of(
                raw("000001", "SZSE", "A", "银行", 0, 0, 0, 0, 0, 0,
                        0.15, 0.10, 40, 1.5, Double.NaN, Double.NaN,
                        0, 0, 0, 0, 1),
                raw("000002", "SZSE", "B", "银行", 0, 0, 0, 0, 0, 0,
                        0.20, 0.15, 35, 1.8, 0.05, 0.30,
                        0, 0, 0, 0, 1)
        );
        List<CodexShortCycleScoringEngine.RawFeatures> imputed =
                CodexShortCycleScoringEngine.imputeFinancialByIndustry(raws);
        // 缺失的 earningsYield/bookToMarket 用同行业真实值 0.05/0.30 填充，而非退化为 0
        assertThat(imputed.get(0).earningsYield()).isEqualTo(0.05);
        assertThat(imputed.get(0).bookToMarket()).isEqualTo(0.30);
    }

    @Test
    void scoreUniverseRecordsTurnoverLogMvSource() {
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = List.of(
                context("000001", "SZSE", "测试", sampleBars(80, 10, 50_000_000, 0.02))
        );
        // 单票无法截面打分，至少验证 extract 路径在多样本场景
        List<CodexShortCycleScoringEngine.StockBarContext> multi = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            List<MarketData> bars = sampleBars(80, 10 + i * 0.1, 50_000_000, 0.01);
            multi.add(new CodexShortCycleScoringEngine.StockBarContext(
                    String.format("%06d", i + 1), "SZSE", "测试" + i, "银行",
                    bars, null, bars.get(bars.size() - 1).getTradeDate(),
                    bars.get(bars.size() - 1).getTradeDate().minusDays(200), 1));
        }
        List<CandidateScore> scored = engine.scoreUniverse(multi, 1_000_000, true, 60, 0);
        assertThat(scored).isNotEmpty();
        assertThat(scored.get(0).getReasons().get("log_mv_source"))
                .isEqualTo(CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER);
    }

    @Test
    void scoreUniverseExcludesStaleSignalBar() {
        List<MarketData> bars = sampleBars(80, 10, 50_000_000, 0.02);
        LocalDate staleTradeDate = bars.get(bars.size() - 1).getTradeDate().plusDays(15);
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = List.of(
                new CodexShortCycleScoringEngine.StockBarContext(
                        "000001", "SZSE", "过期", "测试", bars, null, staleTradeDate, null, 1),
                context("000002", "SZSE", "正常", sampleBars(80, 12, 50_000_000, 0.03))
        );
        List<CandidateScore> scored = engine.scoreUniverse(contexts, 1_000_000, true, 60, 0);
        assertThat(scored).hasSize(1);
        assertThat(scored.get(0).getStockCode()).isEqualTo("000002");
    }

    @Test
    void scoreUniverseExcludesInsufficientListingTenure() {
        List<MarketData> youngBars = sampleBars(80, 10, 50_000_000, 0.02);
        LocalDate tradeDate = youngBars.get(youngBars.size() - 1).getTradeDate();
        List<MarketData> matureBars = sampleBars(80, 12, 50_000_000, 0.03);
        LocalDate matureTradeDate = matureBars.get(matureBars.size() - 1).getTradeDate();
        List<CodexShortCycleScoringEngine.StockBarContext> contexts = List.of(
                new CodexShortCycleScoringEngine.StockBarContext(
                        "000001", "SZSE", "次新", "测试", youngBars, null, tradeDate, tradeDate.minusDays(30), 1),
                new CodexShortCycleScoringEngine.StockBarContext(
                        "000002", "SZSE", "成熟", "测试", matureBars, null, matureTradeDate,
                        matureTradeDate.minusDays(200), 1)
        );
        List<CandidateScore> scored = engine.scoreUniverse(contexts, 1_000_000, true, 60, 0);
        assertThat(scored).hasSize(1);
        assertThat(scored.get(0).getStockCode()).isEqualTo("000002");
    }

    private static CodexShortCycleScoringEngine.StockBarContext context(
            String code, String ex, String name, List<MarketData> bars) {
        LocalDate tradeDate = bars.isEmpty() ? null : bars.get(bars.size() - 1).getTradeDate();
        LocalDate listDate = tradeDate != null ? tradeDate.minusDays(200) : null;
        return new CodexShortCycleScoringEngine.StockBarContext(code, ex, name, "测试", bars, null, tradeDate, listDate, 1);
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
        return raw(code, ex, name, industry, er1, er5, er20m5, maAlign, rsiShape, volConfirm,
                roe, rev, debt, curr, ey, bm,
                vol20, downVol, illiq, maxGap, avgAmt, ret20, logMarketCap, logMvSource, null);
    }

    private static CodexShortCycleScoringEngine.RawFeatures raw(
            String code, String ex, String name, String industry,
            double er1, double er5, double er20m5, double maAlign, double rsiShape, double volConfirm,
            double roe, double rev, double debt, double curr, double ey, double bm,
            double vol20, double downVol, double illiq, double maxGap, double avgAmt,
            double ret20, double logMarketCap, String logMvSource, String logMvReliability) {
        return new CodexShortCycleScoringEngine.RawFeatures(
                code + "|" + ex, code, ex, name, industry,
                er1, er5, er20m5, maAlign, rsiShape, volConfirm,
                roe, rev, debt, curr, ey, bm,
                vol20, downVol, illiq, maxGap, avgAmt, ret20, logMarketCap, logMvSource, logMvReliability
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
