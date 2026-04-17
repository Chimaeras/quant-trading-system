package com.quant.service.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 《打分系统说明》动态权重分配系统：根据市场环境和股票特性调整各维度权重。
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

    // 市场环境判断
    public static String classifyMarketEnvironment(
            double shangzhengIndexChange,
            double vixIndex,
            double marginBalanceChange,
            double dailyTurnover
    ) {
        // 牛市判断
        if (shangzhengIndexChange > 20 && vixIndex < 20 && marginBalanceChange > 0 && dailyTurnover > 1500000000000L) {
            return "牛市";
        }
        
        // 熊市判断
        if (shangzhengIndexChange < -20 && vixIndex > 30 && marginBalanceChange < 0 && dailyTurnover < 500000000000L) {
            return "熊市";
        }
        
        // 震荡市判断
        return "震荡市";
    }

    // 市场环境权重分配
    public static MarketEnvironment getMarketEnvironmentWeights(String marketEnvironment) {
        return switch (marketEnvironment) {
            case "牛市" -> new MarketEnvironment(0.3, 0.5, 0.2);
            case "熊市" -> new MarketEnvironment(0.4, 0.3, 0.3);
            case "震荡市" -> new MarketEnvironment(0.4, 0.2, 0.4);
            default -> new MarketEnvironment(0.33, 0.34, 0.33);
        };
    }

    // 股票特性分类
    public static String classifyStockCharacteristics(
            double pePercentile,
            double pb,
            double dividendYield,
            double peg,
            double revenueGrowth,
            double rndRatio,
            double grossMarginVolatility,
            double pmiCorrelation,
            double volatility,
            double freeCashFlowStability
    ) {
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
        
        // 其他股票
        return "普通股";
    }

    // 股票特性权重分配
    public static StockCharacteristics getStockCharacteristicsWeights(String stockType) {
        return switch (stockType) {
            case "价值股" -> new StockCharacteristics(0.3, 0.5, 0.2);
            case "成长股" -> new StockCharacteristics(0.35, 0.45, 0.2);
            case "周期股" -> new StockCharacteristics(0.4, 0.3, 0.3);
            case "防御股" -> new StockCharacteristics(0.25, 0.4, 0.35);
            default -> new StockCharacteristics(0.33, 0.34, 0.33);
        };
    }

    // 综合动态权重计算
    public static DynamicWeights calculateDynamicWeights(
            MarketEnvironment marketEnv,
            StockCharacteristics stockChar,
            double marketEnvWeight,
            double stockCharWeight
    ) {
        // 确保权重和为1
        double normalizedMarketWeight = marketEnvWeight / (marketEnvWeight + stockCharWeight);
        double normalizedStockWeight = stockCharWeight / (marketEnvWeight + stockCharWeight);
        
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
                round2(technicalWeight),
                round2(fundamentalWeight),
                round2(sentimentWeight)
        );
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}