package com.quant.service.scoring;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 行业-宏观事件敏感度矩阵：地缘/政策因子对不同行业产生差异化影响。
 */
@Component
public class IndustryEventSensitivity {

    private final Map<String, Map<String, Double>> matrix = buildDefaultMatrix();

    public double resolve(String industry, String riskLevel) {
        if (industry == null || industry.isBlank()) {
            return defaultForRisk(riskLevel);
        }
        String ind = industry.trim();
        Map<String, Double> row = matrix.get(ind);
        if (row == null) {
            return defaultForRisk(riskLevel);
        }
        return row.getOrDefault(normalizeRisk(riskLevel), defaultForRisk(riskLevel));
    }

    private static double defaultForRisk(String riskLevel) {
        return switch (normalizeRisk(riskLevel)) {
            case "RED" -> -0.3;
            case "YELLOW" -> -0.1;
            default -> 0.0;
        };
    }

    private static String normalizeRisk(String riskLevel) {
        if (riskLevel == null) return "GREEN";
        return riskLevel.trim().toUpperCase();
    }

    private static Map<String, Map<String, Double>> buildDefaultMatrix() {
        Map<String, Map<String, Double>> m = new HashMap<>();
        put(m, "国防军工", "RED", 0.8, "YELLOW", 0.4, "GREEN", 0.2);
        put(m, "军工", "RED", 0.8, "YELLOW", 0.4, "GREEN", 0.2);
        put(m, "旅游", "RED", -0.9, "YELLOW", -0.5, "GREEN", 0.1);
        put(m, "酒店餐饮", "RED", -0.85, "YELLOW", -0.45, "GREEN", 0.1);
        put(m, "出口", "RED", -0.7, "YELLOW", -0.3, "GREEN", 0.0);
        put(m, "外贸", "RED", -0.7, "YELLOW", -0.3, "GREEN", 0.0);
        put(m, "内需消费", "RED", -0.2, "YELLOW", 0.1, "GREEN", 0.5);
        put(m, "食品饮料", "RED", -0.15, "YELLOW", 0.15, "GREEN", 0.45);
        put(m, "新能源", "RED", -0.4, "YELLOW", -0.1, "GREEN", 0.35);
        put(m, "银行", "RED", -0.5, "YELLOW", -0.2, "GREEN", 0.25);
        return m;
    }

    private static void put(Map<String, Map<String, Double>> m, String industry,
                            String r1, double v1, String r2, double v2, String r3, double v3) {
        Map<String, Double> row = new HashMap<>();
        row.put(r1, v1);
        row.put(r2, v2);
        row.put(r3, v3);
        m.put(industry, row);
    }
}
