package com.quant.service.scoring;

/**
 * 打分规则统一配置
 * 各维度评分标准、阈值、权重统一定义
 */
public final class ScoringRules {

    private ScoringRules() {}

    // ==================== 趋势打分规则 (30分) ====================

    public static double scoreTrend(String trendStatus) {
        return switch (trendStatus) {
            case "强势多头" -> 30;
            case "多头排列" -> 26;
            case "弱势多头" -> 18;
            case "盘整" -> 12;
            case "弱势空头" -> 8;
            case "空头排列" -> 4;
            case "强势空头" -> 0;
            default -> 12;
        };
    }

    public static int trendStrength(String trendStatus) {
        return switch (trendStatus) {
            case "强势多头" -> 90;
            case "多头排列" -> 75;
            case "弱势多头" -> 60;
            case "盘整" -> 50;
            case "弱势空头" -> 40;
            case "空头排列" -> 30;
            case "强势空头" -> 20;
            default -> 50;
        };
    }

    // ==================== 乖离率打分规则 (20分) ====================

    public static double scoreBias(double bias, String trendStatus, double threshold) {
        boolean strongBull = "强势多头".equals(trendStatus) && trendStrength(trendStatus) >= 70;
        double effective = strongBull ? threshold * 1.5 : threshold;

        if (Double.isNaN(bias)) bias = 0;

        if (bias < 0) {
            if (bias > -3) return 20;
            if (bias > -5) return 16;
            return 8;
        }
        if (bias < 2) return 18;
        if (bias < threshold) return 14;
        if (bias > effective) return 4;
        if (bias > threshold && strongBull) return 10;
        return 4;
    }

    // ==================== 成交量打分规则 (15分) ====================

    public static double scoreVolume(String volumeStatus) {
        return switch (volumeStatus) {
            case "缩量回调" -> 15;
            case "放量上涨" -> 12;
            case "量能正常" -> 10;
            case "缩量上涨" -> 6;
            case "放量下跌" -> 0;
            default -> 10;
        };
    }

    // ==================== MACD打分规则 (15分) ====================

    public static double scoreMacd(String macdStatus) {
        return switch (macdStatus) {
            case "零轴上金叉" -> 15;
            case "金叉" -> 12;
            case "上穿零轴" -> 10;
            case "多头" -> 8;
            case "空头" -> 2;
            case "下穿零轴" -> 0;
            case "死叉" -> 0;
            default -> 8;
        };
    }

    // ==================== RSI打分规则 (10分) ====================

    public static double scoreRsi(double rsi) {
        if (rsi < 30) return 10;       // 超卖，可能反弹
        if (rsi <= 40) return 8;
        if (rsi <= 60) return 7;
        if (rsi <= 70) return 5;
        return 3;                       // 超买
    }

    public static double scoreRsiByStatus(String rsiStatus) {
        return switch (rsiStatus) {
            case "超卖" -> 10;
            case "强势买入" -> 8;
            case "中性" -> 5;
            case "弱势" -> 3;
            case "超买" -> 0;
            default -> 5;
        };
    }

    // ==================== 均线排列打分规则 (10分) ====================

    public static double scoreMaArrangement(String maStatus) {
        return switch (maStatus) {
            case "多头排列" -> 10;
            case "弱势多头" -> 7;
            case "中性排列" -> 5;
            case "空头排列" -> 2;
            default -> 5;
        };
    }

    // ==================== KDJ打分规则 ====================

    public static double scoreKdj(String kdjStatus) {
        return switch (kdjStatus) {
            case "K低位向上" -> 12;
            case "K高位向下" -> -12;
            case "K上穿D" -> 8;
            case "K下穿D" -> -8;
            default -> 0;
        };
    }

    // ==================== 基本面打分规则 ====================

    /**
     * PE估值打分 (0-15分)
     */
    public static double scorePE(Double pe) {
        if (pe == null) return 7.5;
        if (pe < 15) return 15;
        if (pe < 25) return 12;
        if (pe < 40) return 8;
        if (pe < 60) return 5;
        return 3;
    }

    /**
     * PB估值打分 (0-15分)
     */
    public static double scorePB(Double pb) {
        if (pb == null) return 7.5;
        if (pb < 1) return 15;
        if (pb < 1.5) return 12;
        if (pb < 2.5) return 8;
        return 5;
    }

    /**
     * ROE盈利能力打分 (0-15分)
     */
    public static double scoreROE(Double roe) {
        if (roe == null) return 7.5;
        if (roe > 20) return 15;
        if (roe > 15) return 12;
        if (roe > 10) return 9;
        if (roe > 5) return 6;
        return 3;
    }

    /**
     * 资产负债率打分 (0-10分)
     */
    public static double scoreDebtRatio(Double debtRatio) {
        if (debtRatio == null) return 5;
        if (debtRatio < 40) return 10;
        if (debtRatio < 60) return 7;
        if (debtRatio < 70) return 4;
        return 2;
    }

    /**
     * 流动比率打分 (0-10分)
     */
    public static double scoreCurrentRatio(Double currentRatio) {
        if (currentRatio == null) return 5;
        if (currentRatio > 2) return 10;
        if (currentRatio > 1.5) return 8;
        if (currentRatio > 1) return 6;
        return 3;
    }

    // ==================== 超跌反弹打分规则 ====================

    /**
     * 60日收益率打分（超跌反弹策略）
     */
    public static int scoreRet60(double ret60Pct) {
        if (ret60Pct <= -20) return 15;
        if (ret60Pct <= -10) return 10;
        if (ret60Pct <= -5) return 5;
        return 0;
    }

    /**
     * 120日收益率打分（超跌反弹策略）
     */
    public static int scoreRet120(double ret120Pct) {
        if (ret120Pct <= -30) return 5;
        if (ret120Pct <= -20) return 3;
        if (ret120Pct <= -10) return 1;
        return 0;
    }

    /**
     * 周K阴线周数打分（超跌反弹策略）
     */
    public static int scoreBearWeeks(int weeks) {
        if (weeks >= 5) return 10;
        if (weeks == 4) return 8;
        if (weeks == 3) return 5;
        return 0;
    }

    /**
     * RSI超跌打分（超跌反弹策略，10分）
     */
    public static int scoreRsiOversold(double rsi) {
        if (rsi < 30) return 10;
        if (rsi < 40) return 5;
        return 0;
    }

    /**
     * 布林带超跌打分（10分）
     */
    public static int scoreBollingerOversold(double price, double lower, double extremeLower) {
        if (price < extremeLower) return 10;
        if (price < lower) return 8;
        return 0;
    }

    /**
     * 历史分位打分（10分）
     */
    public static int scorePercentile(double percentile) {
        if (percentile < 0.10) return 10;
        if (percentile < 0.20) return 5;
        if (percentile < 0.30) return 2;
        return 0;
    }

    // ==================== 投资建议规则 ====================

    /**
     * 根据综合得分给出投资建议
     */
    public static String getInvestmentAdvice(double compositeScore) {
        if (compositeScore >= 85) return "强烈买入";
        if (compositeScore >= 70) return "买入";
        if (compositeScore >= 55) return "持有";
        if (compositeScore >= 30) return "观望";
        return "强烈卖出";
    }

    /**
     * 根据综合得分和趋势给出买入信号
     */
    public static String getBuySignal(double score, String trendStatus) {
        java.util.Set<String> strongBuyTrend = java.util.Set.of("强势多头", "多头排列");
        java.util.Set<String> buyTrend = java.util.Set.of("强势多头", "多头排列", "弱势多头");
        java.util.Set<String> strongSellTrend = java.util.Set.of("空头排列", "强势空头");

        if (score >= 75 && strongBuyTrend.contains(trendStatus)) return "强烈买入";
        if (score >= 60 && buyTrend.contains(trendStatus)) return "买入";
        if (score >= 45) return "持有";
        if (score >= 30) return "观望";
        if (strongSellTrend.contains(trendStatus)) return "强烈卖出";
        return "卖出";
    }

    // ==================== 行业调整规则 ====================

    /**
     * 行业适配修正
     */
    public static double getIndustryAdjustment(String industry) {
        if (industry == null) return 0;
        return switch (industry) {
            case "银行" -> 5.0;
            case "保险" -> 3.0;
            case "房地产" -> -2.0;
            case "新能源" -> 4.0;
            case "科技" -> 3.0;
            default -> 0.0;
        };
    }
}