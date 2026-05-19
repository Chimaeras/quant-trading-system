package com.quant.service.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 《打分系统说明》市场情绪分析维度：新闻舆情、社交媒体讨论热度、机构观点。
 *
 * @deprecated 已由 {@link com.quant.service.EnhancedMarketSentimentService} 替代，将在后续版本移除。
 */
@Deprecated(forRemoval = true)
@Deprecated
public final class MarketSentimentScoring {

    private MarketSentimentScoring() {}

    public record NewsSentimentResult(double sentimentScore, String sentimentStatus, double weightedTotal) {}
    
    public record SocialMediaResult(double discussionVolumeScore, double sentimentScore, double weightedTotal) {}
    
    public record InstitutionalViewResult(double ratingScore, double priceTargetScore, double weightedTotal) {}
    
    public record MarketSentimentResult(
            double newsSentimentScore,
            double socialMediaScore,
            double institutionalViewScore,
            double weightedTotal
    ) {}

    // 新闻舆情评分（15分）
    public static double scoreNewsSentiment(Double sentimentScore) {
        if (sentimentScore == null) return 7.5;
        if (sentimentScore >= 80) return 15;
        if (sentimentScore >= 60) return 10;
        if (sentimentScore >= 40) return 5;
        return 2.5;
    }
    
    public static String classifyNewsSentiment(Double sentimentScore) {
        if (sentimentScore == null) return "中性";
        if (sentimentScore >= 80) return "强烈正面";
        if (sentimentScore >= 60) return "中性偏正";
        if (sentimentScore >= 40) return "中性";
        return "负面";
    }
    
    public static NewsSentimentResult scoreNewsSentiment(Double sentimentScore, boolean isTrendUp) {
        double baseScore = scoreNewsSentiment(sentimentScore);
        String status = classifyNewsSentiment(sentimentScore);
        
        // 趋势调整
        if (isTrendUp) baseScore += 2;
        
        double weightedTotal = Math.min(15, baseScore);
        return new NewsSentimentResult(baseScore, status, round2(weightedTotal));
    }

    // 社交媒体讨论热度评分（8分）
    public static double scoreDiscussionVolume(Double volumeRatio, Double industryAvgRatio) {
        if (volumeRatio == null || industryAvgRatio == null) return 4;
        if (volumeRatio > industryAvgRatio * 1.5) return 8;
        if (volumeRatio > industryAvgRatio * 0.8) return 5;
        return 2;
    }
    
    public static double scoreSocialMediaSentiment(Double sentimentScore) {
        if (sentimentScore == null) return 4;
        if (sentimentScore > 0.5) return 8;
        if (sentimentScore > -0.3) return 5;
        return 2;
    }
    
    public static SocialMediaResult scoreSocialMedia(Double volumeRatio, Double industryAvgRatio, Double sentimentScore) {
        double volumeScore = scoreDiscussionVolume(volumeRatio, industryAvgRatio);
        double sentimentScoreValue = scoreSocialMediaSentiment(sentimentScore);
        
        double weightedTotal = volumeScore * 0.6 + sentimentScoreValue * 0.4;
        return new SocialMediaResult(volumeScore, sentimentScoreValue, round2(weightedTotal));
    }

    // 机构观点评分（7分）
    public static double scoreInstitutionalRatings(Integer buyRatingCount, Integer totalRatingCount) {
        if (buyRatingCount == null || totalRatingCount == null || totalRatingCount == 0) return 3.5;
        double buyRatio = (double) buyRatingCount / totalRatingCount;
        if (buyRatio >= 0.8) return 7;
        if (buyRatio >= 0.5) return 5;
        if (buyRatio >= 0.3) return 3;
        return 1.5;
    }
    
    public static double scorePriceTargetChange(Double priceTargetChange) {
        if (priceTargetChange == null) return 3.5;
        if (priceTargetChange > 10) return 7;
        if (priceTargetChange > 0) return 5;
        if (priceTargetChange > -10) return 3;
        return 1.5;
    }
    
    public static InstitutionalViewResult scoreInstitutionalView(Integer buyRatingCount, Integer totalRatingCount, Double priceTargetChange) {
        double ratingScore = scoreInstitutionalRatings(buyRatingCount, totalRatingCount);
        double priceTargetScore = scorePriceTargetChange(priceTargetChange);
        
        double weightedTotal = ratingScore * 0.6 + priceTargetScore * 0.4;
        return new InstitutionalViewResult(ratingScore, priceTargetScore, round2(weightedTotal));
    }

    // 综合市场情绪评分
    public static MarketSentimentResult scoreMarketSentiment(
            NewsSentimentResult newsSentiment,
            SocialMediaResult socialMedia,
            InstitutionalViewResult institutionalView
    ) {
        double newsSentimentScore = newsSentiment.weightedTotal();
        double socialMediaScore = socialMedia.weightedTotal();
        double institutionalViewScore = institutionalView.weightedTotal();
        
        double weightedTotal = newsSentimentScore + socialMediaScore + institutionalViewScore;
        return new MarketSentimentResult(
                newsSentimentScore,
                socialMediaScore,
                institutionalViewScore,
                round2(weightedTotal)
        );
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}