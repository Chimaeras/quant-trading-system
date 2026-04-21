package com.quant.service.scoring;

/**
 * 《打分系统说明》综合评分系统：计算综合得分并提供投资建议。
 * 重构版本：使用统一的 ScoringRules 和 ScoringUtils
 */
public final class CompositeScoringService {

    private CompositeScoringService() {}

    public record CompositeScoreResult(
            double technicalScore,
            double fundamentalScore,
            double sentimentScore,
            double compositeScore,
            String investmentAdvice,
            String riskControlMessage
    ) {}

    /**
     * 计算综合得分
     */
    public static double calculateCompositeScore(double technicalScore, double fundamentalScore,
                                                  double sentimentScore, double technicalWeight,
                                                  double fundamentalWeight, double sentimentWeight) {
        return technicalScore * technicalWeight +
                fundamentalScore * fundamentalWeight +
                sentimentScore * sentimentWeight;
    }

    /**
     * 完整综合评分
     */
    public static CompositeScoreResult scoreComposite(double technicalScore, double fundamentalScore,
                                                       double sentimentScore, double financialHealthScore,
                                                       double technicalWeight, double fundamentalWeight,
                                                       double sentimentWeight) {
        double compositeScore = calculateCompositeScore(
                technicalScore, fundamentalScore, sentimentScore,
                technicalWeight, fundamentalWeight, sentimentWeight
        );

        String investmentAdvice = ScoringRules.getInvestmentAdvice(compositeScore);
        String riskControlMessage = getRiskControlMessage(compositeScore, fundamentalScore,
                technicalScore, financialHealthScore);

        return new CompositeScoreResult(
                ScoringUtils.round2(technicalScore),
                ScoringUtils.round2(fundamentalScore),
                ScoringUtils.round2(sentimentScore),
                ScoringUtils.round2(compositeScore),
                investmentAdvice,
                riskControlMessage
        );
    }

    /**
     * 风险控制规则
     */
    public static String getRiskControlMessage(double compositeScore, double fundamentalScore,
                                                double technicalScore, double financialHealthScore) {
        StringBuilder message = new StringBuilder();

        if (compositeScore < 60 && financialHealthScore < 8) {
            message.append("财务健康度不足，建议谨慎投资；");
        }

        if (compositeScore < 50 && technicalScore < 20) {
            message.append("技术面弱势，避免追涨；");
        }

        if (compositeScore < 40) {
            message.append("综合表现较弱，建议观望；");
        }

        return message.length() > 0 ? message.toString() : "风险可控";
    }

    /**
     * 行业适配修正
     */
    public static double getIndustryAdjustment(String industry) {
        return ScoringRules.getIndustryAdjustment(industry);
    }
}
