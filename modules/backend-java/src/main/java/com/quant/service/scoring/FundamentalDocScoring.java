package com.quant.service.scoring;

import java.math.BigDecimal;

/**
 * 《打分系统说明》基本面分析维度：估值水平、盈利能力、财务健康度。
 * 重构版本：使用统一的 ScoringRules 和 ScoringUtils
 */
public final class FundamentalDocScoring {

    private FundamentalDocScoring() {}

    public record FundamentalResult(
            double revenueScore,
            double profitScore,
            double roeScore,
            double debtScore,
            double peScore,
            double weightedTotal
    ) {}

    public record ValuationResult(
            double peScore,
            double pbScore,
            double pegScore,
            double weightedTotal
    ) {}

    public record ProfitabilityResult(
            double roeScore,
            double netProfitMarginScore,
            double grossMarginScore,
            double revenueGrowthScore,
            double weightedTotal
    ) {}

    public record FinancialHealthResult(
            double zScore,
            double debtRatioScore,
            double currentRatioScore,
            double cashFlowScore,
            double weightedTotal
    ) {}

    // ==================== 估值水平评分（15分）====================

    public static double scorePEPercentile(Double pePercentile) {
        if (pePercentile == null) return 7.5;
        if (pePercentile < 30) return 15;
        if (pePercentile < 70) return 10;
        return 5;
    }

    public static double scorePB(Double pb) {
        if (pb == null) return 7.5;
        if (pb < 1) return 15;
        if (pb < 1.5) return 10;
        return 5;
    }

    public static double scorePEG(Double peg) {
        if (peg == null) return 7.5;
        if (peg < 1) return 15;
        if (peg < 1.5) return 10;
        return 5;
    }

    public static ValuationResult scoreValuation(Double pePercentile, Double pb, Double peg,
                                                  Double industryPE, Double industryPB, Double industryPEG) {
        double peScore = scorePEPercentile(pePercentile);
        double pbScore = scorePB(pb);
        double pegScore = scorePEG(peg);

        // 与行业对比调整
        if (industryPE != null && pePercentile != null) {
            if (pePercentile < 30) peScore += 2;
            else if (pePercentile > 70) peScore -= 2;
        }

        if (industryPB != null && pb != null) {
            if (pb < industryPB * 0.7) pbScore += 2;
            else if (pb > industryPB * 1.3) pbScore -= 2;
        }

        if (industryPEG != null && peg != null) {
            if (peg < industryPEG * 0.8) pegScore += 2;
            else if (peg > industryPEG * 1.2) pegScore -= 2;
        }

        double weightedTotal = peScore * 0.4 + pbScore * 0.3 + pegScore * 0.3;
        return new ValuationResult(peScore, pbScore, pegScore, ScoringUtils.round2(weightedTotal));
    }

    // ==================== 盈利能力评分（15分）====================

    public static double scoreROE(Double roe) {
        return ScoringRules.scoreROE(roe);
    }

    public static double scoreNetProfitMargin(Double margin) {
        if (margin == null) return 7.5;
        if (margin > 20) return 15;
        if (margin > 15) return 12;
        if (margin > 10) return 8;
        return 4;
    }

    public static double scoreGrossMargin(Double margin) {
        if (margin == null) return 7.5;
        if (margin > 40) return 15;
        if (margin > 30) return 12;
        if (margin > 20) return 8;
        return 4;
    }

    public static double scoreRevenueGrowth(Double growth) {
        if (growth == null) return 7.5;
        if (growth > 30) return 15;
        if (growth > 20) return 12;
        if (growth > 10) return 8;
        return 4;
    }

    public static ProfitabilityResult scoreProfitability(Double roe, Double netProfitMargin,
                                                          Double grossMargin, Double revenueGrowth, boolean roeTrendUp) {
        double roeScore = scoreROE(roe);
        double netProfitMarginScore = scoreNetProfitMargin(netProfitMargin);
        double grossMarginScore = scoreGrossMargin(grossMargin);
        double revenueGrowthScore = scoreRevenueGrowth(revenueGrowth);

        // ROE趋势调整
        if (roeTrendUp) roeScore += 2;

        double weightedTotal = roeScore * 0.4 + netProfitMarginScore * 0.2 +
                grossMarginScore * 0.2 + revenueGrowthScore * 0.2;
        return new ProfitabilityResult(roeScore, netProfitMarginScore, grossMarginScore,
                revenueGrowthScore, ScoringUtils.round2(weightedTotal));
    }

    // ==================== 财务健康度评分（10分）====================

    public static double scoreZScore(Double zScore) {
        if (zScore == null) return 5;
        if (zScore > 2.99) return 10;
        if (zScore > 1.81) return 7;
        return 3;
    }

    public static double scoreDebtRatio(Double debtRatio) {
        return ScoringRules.scoreDebtRatio(debtRatio);
    }

    public static double scoreCurrentRatio(Double currentRatio) {
        return ScoringRules.scoreCurrentRatio(currentRatio);
    }

    public static double scoreCashFlow(Double operatingCashFlow) {
        if (operatingCashFlow == null) return 5;
        if (operatingCashFlow > 0) return 10;
        return 5;
    }

    public static FinancialHealthResult scoreFinancialHealth(Double zScore, Double debtRatio,
                                                              Double currentRatio, Double operatingCashFlow) {
        double zScoreScore = scoreZScore(zScore);
        double debtRatioScore = scoreDebtRatio(debtRatio);
        double currentRatioScore = scoreCurrentRatio(currentRatio);
        double cashFlowScore = scoreCashFlow(operatingCashFlow);

        double weightedTotal = zScoreScore * 0.4 + debtRatioScore * 0.2 +
                currentRatioScore * 0.2 + cashFlowScore * 0.2;
        return new FinancialHealthResult(zScoreScore, debtRatioScore, currentRatioScore,
                cashFlowScore, ScoringUtils.round2(weightedTotal));
    }

    // ==================== BigDecimal版本方法（保持兼容）====================

    public static double scoreRevenueYoY(BigDecimal yoyPct) {
        if (yoyPct == null) return 5;
        double v = yoyPct.doubleValue();
        if (v > 30) return 10;
        if (v >= 15) return 8;
        if (v >= 5) return 6;
        return 3;
    }

    public static double scoreNetProfitYoY(BigDecimal yoyPct) {
        if (yoyPct == null) return 5;
        double v = yoyPct.doubleValue();
        if (v < 0) return 0;
        if (v > 50) return 10;
        if (v >= 20) return 8;
        if (v >= 0) return 6;
        return 0;
    }

    public static double scoreRoe(BigDecimal roePct) {
        if (roePct == null) return 5;
        double v = roePct.doubleValue();
        if (v > 15) return 10;
        if (v >= 10) return 8;
        if (v >= 5) return 6;
        return 3;
    }

    public static double scoreDebtRatio(BigDecimal debtPct) {
        if (debtPct == null) return 5;
        double v = debtPct.doubleValue();
        if (v < 30) return 10;
        if (v <= 50) return 8;
        if (v <= 70) return 6;
        return 0;
    }

    /**
     * PE相对行业评分
     */
    public static double scorePeVsIndustry(BigDecimal stockPe, Double industryAvgPe) {
        if (stockPe == null || industryAvgPe == null || industryAvgPe < 1e-6) return 6;
        double s = stockPe.doubleValue();
        double ratio = (s - industryAvgPe) / industryAvgPe;
        if (ratio < -0.3) return 10;
        if (ratio < 0) return 8;
        if (ratio <= 0.2) return 6;
        return 3;
    }

    public static FundamentalResult combine(BigDecimal revenueYoY, BigDecimal netProfitYoY,
                                             BigDecimal roe, BigDecimal debtRatio,
                                             BigDecimal stockPe, Double industryAvgPe) {
        double r = scoreRevenueYoY(revenueYoY);
        double p = scoreNetProfitYoY(netProfitYoY);
        double roeS = scoreRoe(roe);
        double d = scoreDebtRatio(debtRatio);
        double pe = scorePeVsIndustry(stockPe, industryAvgPe);
        double w = r * 0.25 + p * 0.25 + roeS * 0.2 + d * 0.15 + pe * 0.15;
        return new FundamentalResult(r, p, roeS, d, pe, ScoringUtils.round2(w));
    }

    /**
     * 行业对比评分
     */
    public static int industryCompareHits(BigDecimal revenueYoY, BigDecimal roe, BigDecimal stockPe,
                                           Double avgRevenueYoY, Double avgRoe, Double avgPe) {
        int pts = 0;
        if (revenueYoY != null && avgRevenueYoY != null) {
            pts += revenueYoY.doubleValue() >= avgRevenueYoY ? 2 : -2;
        }
        if (roe != null && avgRoe != null) {
            pts += roe.doubleValue() >= avgRoe ? 2 : -2;
        }
        if (stockPe != null && avgPe != null && avgPe > 1e-6) {
            pts += stockPe.doubleValue() <= avgPe ? 2 : -2;
        }
        return Math.max(-6, Math.min(6, pts));
    }

    /**
     * 纯计算版行业对比：PE相对行业 + 近5日涨幅相对行业
     */
    public static int industryCompareFromPriceAndPe(BigDecimal stockPe, Double industryAvgPe,
                                                     Double stockRet5Pct, Double industryAvgRet5Pct) {
        int pts = 0;
        if (stockPe != null && industryAvgPe != null && industryAvgPe > 1e-6) {
            pts += stockPe.doubleValue() <= industryAvgPe ? 2 : -2;
        }
        if (stockRet5Pct != null && industryAvgRet5Pct != null) {
            pts += stockRet5Pct >= industryAvgRet5Pct ? 2 : -2;
        }
        return Math.max(-4, Math.min(4, pts));
    }

    /**
     * 无财报时：基本面维度退化为「PE相对行业」单项
     */
    public static FundamentalResult fundamentalPeOnly(BigDecimal stockPe, Double industryAvgPe) {
        double pe = scorePeVsIndustry(stockPe, industryAvgPe);
        return new FundamentalResult(0, 0, 0, 0, pe, ScoringUtils.round2(pe));
    }

    /**
     * 完整基本面分析：估值水平 + 盈利能力 + 财务健康度
     */
    public static double scoreCompleteFundamental(ValuationResult valuation,
                                                   ProfitabilityResult profitability,
                                                   FinancialHealthResult financialHealth) {
        return ScoringUtils.round2(valuation.weightedTotal() + profitability.weightedTotal() + financialHealth.weightedTotal());
    }
}
