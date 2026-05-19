package com.quant.service.scoring;

import com.quant.entity.MarketData;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 增强版市场情绪分析服务
 *
 * 根据《打分机制》文档实现市场情绪分析维度（30分）：
 * - 新闻舆情（15分）
 * - 社交媒体讨论热度（8分）
 * - 机构观点（7分）
 *
 * 当无法获取真实数据时，基于技术指标和市场行为推导合理的默认值。
 */
@Service
public class EnhancedMarketSentimentService {

    /** 当数据来自技术面推导时，情绪面权重打 6 折以减少循环论证影响 */
    public static final double DERIVED_DATA_DISCOUNT = 0.6;

    /** 当数据来自真实外部源时，不打折 */
    public static final double REAL_DATA_FACTOR = 1.0;

    /**
     * 完整的市场情绪分析结果
     */
    public record SentimentAnalysisResult(
            double newsSentimentScore,       // 新闻舆情得分 (0-15)
            double socialMediaScore,         // 社交媒体得分 (0-8)
            double institutionalViewScore,   // 机构观点得分 (0-7)
            double totalScore,               // 总分 (0-30)
            String sentimentLevel,           // 情绪等级：强/中/弱
            String newsStatus,               // 新闻状态描述
            String socialStatus,             // 社交状态描述
            String institutionStatus,        // 机构状态描述
            String dataSource,               // 数据来源：real/api/derived_from_technical
            double discountFactor            // 数据可信度折扣因子 (0-1)
    ) {}

    /**
     * 新闻舆情详细结果
     */
    public record NewsSentimentDetail(
            double score,
            String status,
            double sentimentValue,
            boolean trendUp
    ) {}

    /**
     * 社交媒体详细结果
     */
    public record SocialMediaDetail(
            double score,
            String status,
            double volumeRatio,
            double sentimentValue
    ) {}

    /**
     * 机构观点详细结果
     */
    public record InstitutionalDetail(
            double score,
            String status,
            int buyRatingCount,
            double priceTargetChange
    ) {}

    /**
     * 执行完整的市场情绪分析（默认从技术面推导）
     */
    public SentimentAnalysisResult analyzeSentiment(
            List<MarketData> bars, Double pe, String industry) {
        return analyzeSentiment(bars, pe, industry, null, null, null);
    }

    /**
     * 执行市场情绪分析（支持传入真实数据）。
     *
     * @param bars K线数据
     * @param pe PE值（可为null）
     * @param industry 行业（可为null）
     * @param realNewsSentiment 真实新闻舆情评分 0-100（null时从技术面推导）
     * @param realSocialVolume 真实社交讨论热度比例（null时从技术面推导）
     * @param realInstitutionalRating 真实机构评级数量（null时从技术面推导）
     * @return 情绪分析结果
     */
    public SentimentAnalysisResult analyzeSentiment(
            List<MarketData> bars, Double pe, String industry,
            Double realNewsSentiment, Double realSocialVolume, Integer realInstitutionalRating) {

        boolean hasRealData = realNewsSentiment != null || realSocialVolume != null
                || realInstitutionalRating != null;
        double discountFactor = hasRealData ? REAL_DATA_FACTOR : DERIVED_DATA_DISCOUNT;
        String dataSource = hasRealData ? "external_api" : "derived_from_technical";

        // 1. 新闻舆情分析
        NewsSentimentDetail news;
        if (realNewsSentiment != null) {
            double score = calculateNewsScore(realNewsSentiment, isPriceTrendUp(bars, 3));
            news = new NewsSentimentDetail(score, classifyNewsStatus(realNewsSentiment, isPriceTrendUp(bars, 3)),
                    realNewsSentiment, isPriceTrendUp(bars, 3));
        } else {
            news = analyzeNewsSentiment(bars, pe, industry);
        }

        // 2. 社交媒体分析
        SocialMediaDetail social;
        if (realSocialVolume != null) {
            double sentimentVal = Math.tanh((realSocialVolume - 1.0) * 2);
            double score = calculateSocialScore(realSocialVolume, sentimentVal);
            social = new SocialMediaDetail(score, classifySocialStatus(realSocialVolume, sentimentVal),
                    realSocialVolume, sentimentVal);
        } else {
            social = analyzeSocialMedia(bars);
        }

        // 3. 机构观点分析
        InstitutionalDetail institution;
        if (realInstitutionalRating != null) {
            double score = calculateInstitutionalScore(realInstitutionalRating, 0);
            String status = classifyInstitutionalStatus(realInstitutionalRating, 0);
            institution = new InstitutionalDetail(score, status, realInstitutionalRating, 0);
        } else {
            institution = analyzeInstitutionalView(bars, pe, industry);
        }

        double rawTotal = news.score() + social.score() + institution.score();
        double totalScore = rawTotal * discountFactor;
        String sentimentLevel = determineSentimentLevel(rawTotal);

        return new SentimentAnalysisResult(
                news.score(), social.score(), institution.score(),
                totalScore, sentimentLevel,
                news.status(), social.status(), institution.status(),
                dataSource, discountFactor);
    }

    /**
     * 新闻舆情分析（15分）
     *
     * 评分逻辑：
     * - 强烈正面(12-15分)：舆情评分≥80分，且连续3天评分上升
     * - 中性偏正(8-11分)：舆情评分60-80分，且评分趋势稳定
     * - 中性(4-7分)：舆情评分40-60分，且评分波动不大
     * - 负面(0-3分)：舆情评分<40分，且连续3天评分下降
     *
     * 当前实现：基于技术指标和市场行为推导
     */
    public NewsSentimentDetail analyzeNewsSentiment(
            List<MarketData> bars,
            Double pe,
            String industry
    ) {
        if (bars == null || bars.size() < 5) {
            return new NewsSentimentDetail(7.5, "中性", 50.0, false);
        }

        int n = bars.size();
        int L = n - 1;

        // 基于技术指标推导舆情分数
        double sentimentValue = deriveNewsSentimentFromTechnical(bars, pe, industry);

        // 判断趋势
        boolean trendUp = isPriceTrendUp(bars, 3);

        // 计算得分
        double score = calculateNewsScore(sentimentValue, trendUp);

        // 确定状态
        String status = classifyNewsStatus(sentimentValue, trendUp);

        return new NewsSentimentDetail(score, status, sentimentValue, trendUp);
    }

    /**
     * 从技术指标推导新闻舆情分数
     */
    private double deriveNewsSentimentFromTechnical(
            List<MarketData> bars,
            Double pe,
            String industry
    ) {
        int n = bars.size();
        int L = n - 1;

        // 基础分50分
        double baseScore = 50.0;

        // 1. 价格走势贡献 (-15 到 +15)
        double closeToday = bars.get(L).getClosePrice() != null
                ? bars.get(L).getClosePrice().doubleValue() : 0;
        double close5dAgo = bars.get(Math.max(0, L - 5)).getClosePrice() != null
                ? bars.get(Math.max(0, L - 5)).getClosePrice().doubleValue() : closeToday;
        double priceChange5d = close5dAgo > 1e-9 ? (closeToday - close5dAgo) / close5dAgo * 100 : 0;
        double priceContribution = Math.max(-15, Math.min(15, priceChange5d * 1.5));

        // 2. 成交量贡献 (-10 到 +10)
        long volToday = bars.get(L).getVolume() != null ? bars.get(L).getVolume() : 0;
        long vol5dSum = 0;
        int volCount = 0;
        for (int i = Math.max(0, L - 5); i < L; i++) {
            if (bars.get(i).getVolume() != null) {
                vol5dSum += bars.get(i).getVolume();
                volCount++;
            }
        }
        double vol5dAvg = volCount > 0 ? vol5dSum / (double) volCount : volToday;
        double volRatio = vol5dAvg > 1e-9 ? volToday / vol5dAvg : 1.0;
        double volContribution = volRatio > 1.5 ? 8 : (volRatio < 0.7 ? -5 : 0);

        // 3. PE相对位置贡献 (-5 到 +5)
        double peContribution = 0;
        if (pe != null) {
            if (pe < 15) peContribution = 5;
            else if (pe < 30) peContribution = 2;
            else if (pe > 50) peContribution = -5;
            else if (pe > 40) peContribution = -2;
        }

        // 4. 行业调整
        double industryContribution = getIndustrySentimentAdjustment(industry);

        // 计算最终分数
        double finalScore = baseScore + priceContribution + volContribution + peContribution + industryContribution;

        return Math.max(0, Math.min(100, finalScore));
    }

    /**
     * 计算新闻得分
     */
    private double calculateNewsScore(double sentimentValue, boolean trendUp) {
        double baseScore;
        if (sentimentValue >= 80) {
            baseScore = trendUp ? 15 : 13;
        } else if (sentimentValue >= 60) {
            baseScore = trendUp ? 11 : 9;
        } else if (sentimentValue >= 40) {
            baseScore = 6;
        } else {
            baseScore = trendUp ? 4 : 2;
        }
        return Math.max(0, Math.min(15, baseScore));
    }

    /**
     * 分类新闻状态
     */
    private String classifyNewsStatus(double sentimentValue, boolean trendUp) {
        if (sentimentValue >= 80 && trendUp) return "强烈正面";
        if (sentimentValue >= 80) return "正面";
        if (sentimentValue >= 60) return "中性偏正";
        if (sentimentValue >= 40) return "中性";
        if (trendUp) return "中性偏负";
        return "负面";
    }

    /**
     * 社交媒体讨论热度分析（8分）
     *
     * 评分逻辑：
     * - 高热度(6-8分)：讨论量高于行业均值50%，且讨论内容积极
     * - 中等热度(3-5分)：讨论量接近行业均值，讨论内容中性
     * - 低热度(0-2分)：讨论量低于行业均值，且讨论内容消极
     *
     * 当前实现：基于成交量和价格行为推导
     */
    public SocialMediaDetail analyzeSocialMedia(List<MarketData> bars) {
        if (bars == null || bars.size() < 10) {
            return new SocialMediaDetail(4.0, "中等热度", 1.0, 0.0);
        }

        int n = bars.size();
        int L = n - 1;

        // 成交量比率作为讨论热度的代理指标
        long volToday = bars.get(L).getVolume() != null ? bars.get(L).getVolume() : 0;
        long vol10dSum = 0;
        int volCount = 0;
        for (int i = Math.max(0, L - 10); i < L; i++) {
            if (bars.get(i).getVolume() != null) {
                vol10dSum += bars.get(i).getVolume();
                volCount++;
            }
        }
        double vol10dAvg = volCount > 0 ? vol10dSum / (double) volCount : volToday;
        double volumeRatio = vol10dAvg > 1e-9 ? volToday / vol10dAvg : 1.0;

        // 价格变化作为情绪的代理指标
        double closeToday = bars.get(L).getClosePrice() != null
                ? bars.get(L).getClosePrice().doubleValue() : 0;
        double closePrev = bars.get(L - 1).getClosePrice() != null
                ? bars.get(L - 1).getClosePrice().doubleValue() : closeToday;
        double priceChange = closePrev > 1e-9 ? (closeToday - closePrev) / closePrev : 0;
        double sentimentValue = Math.tanh(priceChange * 10); // 归一化到 -1 到 1

        // 计算得分
        double score = calculateSocialScore(volumeRatio, sentimentValue);

        // 确定状态
        String status = classifySocialStatus(volumeRatio, sentimentValue);

        return new SocialMediaDetail(score, status, volumeRatio, sentimentValue);
    }

    /**
     * 计算社交媒体得分
     */
    private double calculateSocialScore(double volumeRatio, double sentimentValue) {
        double volumeScore;
        if (volumeRatio > 1.5) {
            volumeScore = 6;
        } else if (volumeRatio > 1.0) {
            volumeScore = 4;
        } else {
            volumeScore = 2;
        }

        double sentimentScore;
        if (sentimentValue > 0.3) {
            sentimentScore = 2;
        } else if (sentimentValue > -0.3) {
            sentimentScore = 1;
        } else {
            sentimentScore = 0;
        }

        return Math.max(0, Math.min(8, volumeScore + sentimentScore));
    }

    /**
     * 分类社交媒体状态
     */
    private String classifySocialStatus(double volumeRatio, double sentimentValue) {
        if (volumeRatio > 1.5 && sentimentValue > 0.3) return "高热度积极";
        if (volumeRatio > 1.5) return "高热度中性";
        if (volumeRatio > 0.8) return "中等热度";
        if (sentimentValue < -0.3) return "低热度消极";
        return "低热度";
    }

    /**
     * 机构观点分析（7分）
     *
     * 评分逻辑：
     * - 积极(5-7分)：近5天"买入型"评级≥5家，且目标价上调幅度>10%
     * - 中性(3-4分)：近5天"买入型"评级3-4家，评级无明显变化
     * - 消极(0-2分)：近5天"买入型"评级≤2家，且目标价下调幅度<-10%
     *
     * 当前实现：基于技术指标和基本面推导
     */
    public InstitutionalDetail analyzeInstitutionalView(
            List<MarketData> bars,
            Double pe,
            String industry
    ) {
        if (bars == null || bars.size() < 20) {
            return new InstitutionalDetail(3.5, "中性", 3, 0.0);
        }

        // 基于技术面和基本面推导机构观点
        int estimatedBuyRatings = estimateInstitutionalBuyRatings(bars, pe, industry);
        double estimatedPriceTargetChange = estimatePriceTargetChange(bars, pe);

        // 计算得分
        double score = calculateInstitutionalScore(estimatedBuyRatings, estimatedPriceTargetChange);

        // 确定状态
        String status = classifyInstitutionalStatus(estimatedBuyRatings, estimatedPriceTargetChange);

        return new InstitutionalDetail(score, status, estimatedBuyRatings, estimatedPriceTargetChange);
    }

    /**
     * 估算机构买入评级数量
     */
    private int estimateInstitutionalBuyRatings(List<MarketData> bars, Double pe, String industry) {
        int baseRating = 3; // 默认中性

        int n = bars.size();
        int L = n - 1;

        // 1. 趋势因素
        double ma5 = calculateSMA(bars, L, 5);
        double ma10 = calculateSMA(bars, L, 10);
        double ma20 = calculateSMA(bars, L, 20);

        if (!Double.isNaN(ma5) && !Double.isNaN(ma10) && !Double.isNaN(ma20)) {
            if (ma5 > ma10 && ma10 > ma20) {
                baseRating += 1; // 多头排列加分
            } else if (ma5 < ma10 && ma10 < ma20) {
                baseRating -= 1; // 空头排列减分
            }
        }

        // 2. PE因素
        if (pe != null) {
            if (pe < 20) baseRating += 1;
            else if (pe > 40) baseRating -= 1;
        }

        // 3. 行业因素
        baseRating += getIndustryRatingAdjustment(industry);

        return Math.max(0, Math.min(7, baseRating));
    }

    /**
     * 估算目标价变化
     */
    private double estimatePriceTargetChange(List<MarketData> bars, Double pe) {
        int n = bars.size();
        int L = n - 1;

        // 基于20日价格变化
        double closeToday = bars.get(L).getClosePrice() != null
                ? bars.get(L).getClosePrice().doubleValue() : 0;
        double close20dAgo = bars.get(Math.max(0, L - 20)).getClosePrice() != null
                ? bars.get(Math.max(0, L - 20)).getClosePrice().doubleValue() : closeToday;
        double priceChange20d = close20dAgo > 1e-9 ? (closeToday - close20dAgo) / close20dAgo * 100 : 0;

        // PE调整
        double peAdjustment = 0;
        if (pe != null) {
            if (pe < 15) peAdjustment = 5;
            else if (pe < 25) peAdjustment = 2;
            else if (pe > 50) peAdjustment = -5;
        }

        return priceChange20d / 2 + peAdjustment;
    }

    /**
     * 计算机构观点得分
     */
    private double calculateInstitutionalScore(int buyRatings, double priceTargetChange) {
        double ratingScore;
        if (buyRatings >= 5) {
            ratingScore = 5;
        } else if (buyRatings >= 3) {
            ratingScore = 3.5;
        } else {
            ratingScore = 1.5;
        }

        double targetScore;
        if (priceTargetChange > 10) {
            targetScore = 2;
        } else if (priceTargetChange > 0) {
            targetScore = 1;
        } else if (priceTargetChange > -10) {
            targetScore = 0.5;
        } else {
            targetScore = 0;
        }

        return Math.max(0, Math.min(7, ratingScore + targetScore));
    }

    /**
     * 分类机构状态
     */
    private String classifyInstitutionalStatus(int buyRatings, double priceTargetChange) {
        if (buyRatings >= 5 && priceTargetChange > 10) return "积极";
        if (buyRatings >= 5) return "偏积极";
        if (buyRatings >= 3) return "中性";
        if (buyRatings <= 2 && priceTargetChange < -10) return "消极";
        if (buyRatings <= 2) return "偏消极";
        return "中性";
    }

    // ==================== 辅助方法 ====================

    private boolean isPriceTrendUp(List<MarketData> bars, int days) {
        if (bars.size() < days + 1) return false;
        int n = bars.size();
        int upDays = 0;
        for (int i = n - days; i < n; i++) {
            double closeToday = bars.get(i).getClosePrice() != null
                    ? bars.get(i).getClosePrice().doubleValue() : 0;
            double closePrev = bars.get(i - 1).getClosePrice() != null
                    ? bars.get(i - 1).getClosePrice().doubleValue() : closeToday;
            if (closeToday > closePrev) upDays++;
        }
        return upDays >= days / 2.0;
    }

    private double calculateSMA(List<MarketData> bars, int index, int period) {
        if (index < period - 1) return Double.NaN;
        double sum = 0;
        for (int i = index - period + 1; i <= index; i++) {
            if (bars.get(i).getClosePrice() != null) {
                sum += bars.get(i).getClosePrice().doubleValue();
            }
        }
        return sum / period;
    }

    private String determineSentimentLevel(double totalScore) {
        if (totalScore >= 25) return "强";
        if (totalScore >= 15) return "中";
        return "弱";
    }

    private double getIndustrySentimentAdjustment(String industry) {
        if (industry == null) return 0;
        return switch (industry) {
            case "科技", "新能源", "半导体" -> 5.0;
            case "医药", "消费" -> 3.0;
            case "银行", "保险" -> 2.0;
            case "房地产" -> -5.0;
            default -> 0.0;
        };
    }

    private int getIndustryRatingAdjustment(String industry) {
        if (industry == null) return 0;
        return switch (industry) {
            case "科技", "新能源", "半导体" -> 1;
            case "医药", "消费" -> 0;
            case "银行", "保险" -> 0;
            case "房地产" -> -1;
            default -> 0;
        };
    }
}