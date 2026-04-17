package com.quant.service.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 《打分系统说明》综合评分系统：计算综合得分并提供投资建议。
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

    // 计算综合得分
    public static double calculateCompositeScore(
            double technicalScore,
            double fundamentalScore,
            double sentimentScore,
            double technicalWeight,
            double fundamentalWeight,
            double sentimentWeight
    ) {
        return technicalScore * technicalWeight + 
               fundamentalScore * fundamentalWeight + 
               sentimentScore * sentimentWeight;
    }

    // 投资建议规则
    public static String getInvestmentAdvice(double compositeScore, double technicalScore, double fundamentalScore) {
        if (compositeScore >= 85) {
            return "强烈买入";
        } else if (compositeScore >= 70) {
            return "买入";
        } else if (compositeScore >= 55) {
            return "持有";
        } else if (compositeScore >= 30) {
            return "观望";
        } else {
            return "强烈卖出";
        }
    }

    // 风险控制规则
    public static String getRiskControlMessage(
            double compositeScore,
            double fundamentalScore,
            double technicalScore,
            double financialHealthScore
    ) {
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

    // 完整综合评分
    public static CompositeScoreResult scoreComposite(
            double technicalScore,
            double fundamentalScore,
            double sentimentScore,
            double financialHealthScore,
            double technicalWeight,
            double fundamentalWeight,
            double sentimentWeight
    ) {
        double compositeScore = calculateCompositeScore(
                technicalScore,
                fundamentalScore,
                sentimentScore,
                technicalWeight,
                fundamentalWeight,
                sentimentWeight
        );
        
        String investmentAdvice = getInvestmentAdvice(compositeScore, technicalScore, fundamentalScore);
        String riskControlMessage = getRiskControlMessage(compositeScore, fundamentalScore, technicalScore, financialHealthScore);
        
        return new CompositeScoreResult(
                round2(technicalScore),
                round2(fundamentalScore),
                round2(sentimentScore),
                round2(compositeScore),
                investmentAdvice,
                riskControlMessage
        );
    }

    // 行业适配修正
    public static double getIndustryAdjustment(String industry) {
        // 根据行业特性进行适当的分数调整
        return switch (industry) {
            case "银行" -> 5.0;
            case "保险" -> 3.0;
            case "房地产" -> -2.0;
            case "新能源" -> 4.0;
            case "科技" -> 3.0;
            default -> 0.0;
        };
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}