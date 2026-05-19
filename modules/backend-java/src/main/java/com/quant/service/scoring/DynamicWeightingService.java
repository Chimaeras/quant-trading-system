package com.quant.service.scoring;

import com.quant.config.ScoringProperties;

/**
 * 《打分系统说明》动态权重分配系统：根据市场环境和股票特性调整各维度权重。
 * 权重优先从配置读取（application.yml quant.scoring.dynamic-weights），
 * 配置缺失时使用硬编码默认值。
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

    public static String classifyMarketEnvironment(double indexChange, double vixIndex,
                                                    double marginChange, double dailyTurnover) {
        if (indexChange > 20 && vixIndex < 20 && marginChange > 0 && dailyTurnover > 1500000000000L) {
            return "牛市";
        }
        if (indexChange < -20 && vixIndex > 30 && marginChange < 0 && dailyTurnover < 500000000000L) {
            return "熊市";
        }
        return "震荡市";
    }

    /**
     * 获取市场环境权重：优先从配置读取，缺失时使用硬编码默认值
     */
    public static MarketEnvironment getMarketEnvironmentWeights(String marketEnvironment) {
        ScoringProperties props = ScoringPropertiesBridge.get();
        if (props != null && props.getDynamicWeights() != null
                && props.getDynamicWeights().getMarketWeights() != null) {
            double[] w = props.getDynamicWeights().getMarketWeights().get(marketEnvironment);
            if (w != null && w.length >= 3) {
                return new MarketEnvironment(w[0], w[1], w[2]);
            }
        }
        // 硬编码默认值
        return switch (marketEnvironment) {
            case "牛市" -> new MarketEnvironment(0.3, 0.5, 0.2);
            case "熊市" -> new MarketEnvironment(0.4, 0.3, 0.3);
            case "震荡市" -> new MarketEnvironment(0.35, 0.30, 0.35);
            default -> new MarketEnvironment(
                    ScoringConstants.DEFAULT_TECH_WEIGHT,
                    ScoringConstants.DEFAULT_FUND_WEIGHT,
                    ScoringConstants.DEFAULT_SENTIMENT_WEIGHT
            );
        };
    }

    // ==================== 股票特性分类 ====================

    public static String classifyStockCharacteristics(double pePercentile, double pb,
                                                       double dividendYield, double peg,
                                                       double revenueGrowth, double rndRatio,
                                                       double grossMarginVolatility, double pmiCorrelation,
                                                       double volatility, double freeCashFlowStability) {
        if (pePercentile < 30 && pb < 1 && dividendYield > 3) return "价值股";
        if (peg < 1 && revenueGrowth > 20 && rndRatio > 5) return "成长股";
        if (grossMarginVolatility > 15 && pmiCorrelation > 0.7) return "周期股";
        if (volatility < 15 && dividendYield > 4 && freeCashFlowStability > 0.8) return "防御股";
        return "普通股";
    }

    /**
     * 获取股票特性权重：优先从配置读取，缺失时使用硬编码默认值
     */
    public static StockCharacteristics getStockCharacteristicsWeights(String stockType) {
        ScoringProperties props = ScoringPropertiesBridge.get();
        if (props != null && props.getDynamicWeights() != null
                && props.getDynamicWeights().getStockTypeWeights() != null) {
            double[] w = props.getDynamicWeights().getStockTypeWeights().get(stockType);
            if (w != null && w.length >= 3) {
                return new StockCharacteristics(w[0], w[1], w[2]);
            }
        }
        // 硬编码默认值
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

    public static DynamicWeights calculateDynamicWeights(MarketEnvironment marketEnv,
                                                          StockCharacteristics stockChar,
                                                          double marketEnvWeight, double stockCharWeight) {
        double totalWeight = marketEnvWeight + stockCharWeight;
        double normalizedMarketWeight = marketEnvWeight / totalWeight;
        double normalizedStockWeight = stockCharWeight / totalWeight;

        double technicalWeight = marketEnv.technicalWeight() * normalizedMarketWeight +
                stockChar.technicalWeight() * normalizedStockWeight;
        double fundamentalWeight = marketEnv.fundamentalWeight() * normalizedMarketWeight +
                stockChar.fundamentalWeight() * normalizedStockWeight;
        double sentimentWeight = marketEnv.sentimentWeight() * normalizedMarketWeight +
                stockChar.sentimentWeight() * normalizedStockWeight;

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

    public static DynamicWeights getDefaultWeights() {
        return new DynamicWeights(
                ScoringConstants.DEFAULT_TECH_WEIGHT,
                ScoringConstants.DEFAULT_FUND_WEIGHT,
                ScoringConstants.DEFAULT_SENTIMENT_WEIGHT
        );
    }
}
