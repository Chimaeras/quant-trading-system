package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "quant.scoring")
public class ScoringProperties {

    private CrossSection crossSection = new CrossSection();
    private AdviceThresholds advice = new AdviceThresholds();
    private Map<String, Double> industryAdjustments = defaultIndustryAdjustments();
    private Gdelt gdelt = new Gdelt();
    private News news = new News();
    private Decay decay = new Decay();
    private DynamicWeightConfig dynamicWeights = new DynamicWeightConfig();
    private RotationConfig rotation = new RotationConfig();
    private ClassicFactorConfig classicFactor = new ClassicFactorConfig();

    @Data
    public static class CrossSection {
        private boolean enabled = false;
        private double minStd = 1e-6;
    }

    @Data
    public static class AdviceThresholds {
        private double strongBuy = 85;
        private double buy = 70;
        private double hold = 55;
        private double watch = 30;
    }

    @Data
    public static class Gdelt {
        private double riskRed = 0.4;
        private double riskYellow = 0.2;
        private double lambda = 0.1;
        private Map<String, Double> eventWeights = defaultEventWeights();
    }

    @Data
    public static class News {
        private List<String> preferredDomains = List.of(
                "xinhuanet.com", "news.cn", "people.com.cn", "cctv.com", "cgtn.com",
                "globaltimes.cn", "stcn.com", "cnr.cn", "eastmoney.com", "10jqka.com.cn",
                "sina.com.cn", "jjw.com.cn"
        );
        private double preferredBoost = 1.5;
    }

    @Data
    public static class Decay {
        private int technicalHalfLifeDays = 5;
        private int newsHalfLifeDays = 2;
        private int fundamentalHalfLifeDays = 90;
    }

    /** 经典多因子选股权重配置 */
    @Data
    public static class ClassicFactorConfig {
        private double trendWeight = 0.35;
        private double momentumWeight = 0.20;
        private double volumeWeight = 0.20;
        private double riskPenaltyWeight = 0.25;
    }

    /** 板块轮动配置 */
    @Data
    public static class RotationConfig {
        private double inflowThreshold = 10.0;
        private double outflowThreshold = -5.0;
        private int topRankBonus = 3;
        private int bottomRankPenalty = 3;
    }

    /** 动态权重配置：市场环境 + 股票特性权重，支持运行时调整 */
    @Data
    public static class DynamicWeightConfig {
        private Map<String, double[]> marketWeights = defaultMarketWeights();
        private Map<String, double[]> stockTypeWeights = defaultStockTypeWeights();
        private double marketEnvRatio = 0.6;
        private double stockCharRatio = 0.4;
    }

    private static Map<String, double[]> defaultMarketWeights() {
        Map<String, double[]> m = new LinkedHashMap<>();
        // [技术面, 基本面, 情绪面]
        m.put("牛市", new double[]{0.3, 0.5, 0.2});
        m.put("熊市", new double[]{0.4, 0.3, 0.3});
        m.put("震荡市", new double[]{0.35, 0.30, 0.35});
        return m;
    }

    private static Map<String, double[]> defaultStockTypeWeights() {
        Map<String, double[]> m = new LinkedHashMap<>();
        // [技术面, 基本面, 情绪面]
        m.put("价值股", new double[]{0.3, 0.5, 0.2});
        m.put("成长股", new double[]{0.35, 0.45, 0.2});
        m.put("周期股", new double[]{0.4, 0.3, 0.3});
        m.put("防御股", new double[]{0.25, 0.4, 0.35});
        m.put("普通股", new double[]{0.33, 0.34, 0.33});
        return m;
    }

    private static Map<String, Double> defaultIndustryAdjustments() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("银行", 5.0);
        m.put("保险", 3.0);
        m.put("房地产", -2.0);
        m.put("新能源", 4.0);
        m.put("科技", 3.0);
        return m;
    }

    private static Map<String, Double> defaultEventWeights() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("01", 0.6);
        m.put("04", 0.4);
        m.put("12", 0.3);
        m.put("default", 0.1);
        return m;
    }
}
