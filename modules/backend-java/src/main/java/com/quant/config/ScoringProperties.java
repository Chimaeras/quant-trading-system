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
