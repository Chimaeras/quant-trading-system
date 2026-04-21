package com.quant.service.scoring;

/**
 * 《打分系统说明》动态权重分配系统：根据市场环境和股票特性调整各维度权重。
 * 重构版本：使用统一的 ScoringUtils
 */
public final class DynamicWeightingService {

    private DynamicWeightingService() {}

    public record MarketEnvironment(double technicalWeight, double fundamentalWeight, double sentimentWeight) {}

    public record StockCharacteristics(double technicalWeight, double fundamentalWeight, double sentimentWeight) {}

    public record DynamicWeights(double technicalWeight, double fundamentalWeight, double sentimentWeight) {
        public boolean isValid() {
            return Math.abs(technicalWeight + fundamentalWeight + sentimentWeight - 1.0) < 0.01;
        }
    }

    // ==================== 市场环境判断 ====================

    /**
     * 判断市场环境类型
     */
    public static String classifyMarketEnvironment(double indexChange, double vixIndex,
                                                    double marginChange, double dailyTurnover) {
        // 牛市判断
        if (indexChange > 20 && vixIndex < 20 && marginChange > 0 && dailyTurnover > 1500000000000L) {
            return "牛市";
        }

        // 熊市判断
        if (indexChange < -20 && vixIndex > 30 && marginChange < 0 && dailyTurnover < 500000000000L) {
            return "熊市";
        }

        return "震荡市";
    }

    /**
     * 获取市场环境对应的权重配置
     */
    public static MarketEnvironment getMarketEnvironmentWeights(String marketEnvironment) {
        return switch (marketEnvironment) {
            case "牛市" -> new MarketEnvironment(0.3, 0.5, 0.2);
            case "熊市" -> new MarketEnvironment(0.4, 0.3, 0.3);
            case "震荡市" -> new MarketEnvironment(0.4, 0.2, 0.4);
            default -> new MarketEnvironment(
                    ScoringConstants.DEFAULT_TECH_WEIGHT,
                    ScoringConstants.DEFAULT_FUND_WEIGHT,
                    ScoringConstants.DEFAULT_SENTIMENT_WEIGHT
            );
        };
    }

    // ==================== 股票特性分类 ====================

    /**
     * 判断股票特性类型
     */
    public static String classifyStockCharacteristics(double pePercentile, double pb,
                                                       double dividendYield, double peg,
                                                       double revenueGrowth, double rndRatio,
                                                       double grossMarginVolatility, double pmiCorrelation,
                                                       double volatility, double freeCashFlowStability) {
        // 价值股判断
        if (pePercentile < 30 && pb < 1 && dividendYield > 3) {
            return "价值股";
        }

        // 成长股判断
        if (peg < 1 && revenueGrowth > 20 && rndRatio > 5) {
            return "成长股";
        }

        // 周期股判断
        if (grossMarginVolatility > 15 && pmiCorrelation > 0.7) {
            return "周期股";
        }

        // 防御股判断
        if (volatility < 15 && dividendYield > 4 && freeCashFlowStability > 0.8) {
            return "防御股";
        }

        return "普通股";
    }

    /**
     * 获取股票特性对应的权重配置
     */
    public static StockCharacteristics getStockCharacteristicsWeights(String stockType) {
        return switch (stockType) {
            case "价值股" -> new StockCharacteristics(0.3, 0.5, 0.2);
            case "成长股" -> new StockCharacteristics(0.35, 0.45, 0.2);
            case "周期股" -> new StockCharacteristics(0.4, 0.3, 0.3);
            case "防御股" -> new StockCharacteristics(0.25, 0.4, 0.35);
            default -> new StockCharacteristics(
                    ScoringConstants.DEFAULT_TECH_WEIGHT,
                    ScoringConstants.DEFAULT_FUND_WEIGHT,
                    ScoringConstants.DEFAULT_SENTIMENT_WEIGHT
            );
        };
    }

    // ==================== 综合动态权重计算 ====================

    /**
     * 计算综合动态权重
     */
    public static DynamicWeights calculateDynamicWeights(MarketEnvironment marketEnv,
                                                          StockCharacteristics stockChar,
                                                          double marketEnvWeight, double stockCharWeight) {
        // 归一化权重系数
        double totalWeight = marketEnvWeight + stockCharWeight;
        double normalizedMarketWeight = marketEnvWeight / totalWeight;
        double normalizedStockWeight = stockCharWeight / totalWeight;

        // 加权计算各维度权重
        double technicalWeight = marketEnv.technicalWeight() * normalizedMarketWeight +
                stockChar.technicalWeight() * normalizedStockWeight;
        double fundamentalWeight = marketEnv.fundamentalWeight() * normalizedMarketWeight +
                stockChar.fundamentalWeight() * normalizedStockWeight;
        double sentimentWeight = marketEnv.sentimentWeight() * normalizedMarketWeight +
                stockChar.sentimentWeight() * normalizedStockWeight;

        // 归一化确保权重和为1
        double total = technicalWeight + fundamentalWeight + sentimentWeight;
        technicalWeight /= total;
        fundamentalWeight /= total;
        sentimentWeight /= total;

        return new DynamicWeights(
                ScoringUtils.round2(technicalWeight),
                ScoringUtils.round2(fundamentalWeight),
                ScoringUtils.round2(sentimentWeight)
        );
    }

    /**
     * 获取默认动态权重
     */
    public static DynamicWeights getDefaultWeights() {
        return new DynamicWeights(
                ScoringConstants.DEFAULT_TECH_WEIGHT,
                ScoringConstants.DEFAULT_FUND_WEIGHT,
                ScoringConstants.DEFAULT_SENTIMENT_WEIGHT
        );
    }
}
