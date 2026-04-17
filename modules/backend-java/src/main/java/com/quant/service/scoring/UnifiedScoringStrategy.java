package com.quant.service.scoring;

/**
 * 统一打分入口支持的策略类型。
 */
public enum UnifiedScoringStrategy {
    TREND,
    COMPREHENSIVE,
    MULTI_DIM,
    CLASSIC_SELECTOR,
    OVERSOLD_REBOUND,
    GEOPOLICY_NEWS;

    public static UnifiedScoringStrategy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CLASSIC_SELECTOR;
        }
        String s = raw.trim().toUpperCase();
        return switch (s) {
            case "TREND" -> TREND;
            case "COMPREHENSIVE" -> COMPREHENSIVE;
            case "MULTI_DIM", "MULTI-DIM", "MULTIDIM" -> MULTI_DIM;
            case "CLASSIC", "CLASSIC_SELECTOR", "SELECTOR" -> CLASSIC_SELECTOR;
            case "OVERSOLD", "OVERSOLD_REBOUND", "OVERSOLD-REBOUND" -> OVERSOLD_REBOUND;
            case "GEOPOLICY", "GEOPOLICY_NEWS", "GEOPOLICY-NEWS" -> GEOPOLICY_NEWS;
            default -> throw new IllegalArgumentException("不支持的策略类型: " + raw);
        };
    }
}
