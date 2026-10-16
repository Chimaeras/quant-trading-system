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
    /**
     * 统一因子引擎（批次 C 影子模式）。
     *
     * <p>与上面 5 个的区别：上面 5 个各自保留原有实现，行为完全不变；
     * {@code UNIFIED} 走「一次特征计算 + 一份横截面标准化 + 策略配置」的新路径，
     * 可与旧路径在同一交易日、同一 universe 上对比排名相关性。
     *
     * <p>具体使用哪套因子权重由请求参数 {@code unifiedStrategy} 决定
     * （缺省 {@code CLASSIC_SELECTOR}），故 {@code UNIFIED} 本身不代表某一种选股风格，
     * 而代表「用新引擎跑」。
     */
    UNIFIED;

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
            case "UNIFIED", "UNIFIED_FACTOR", "FACTOR" -> UNIFIED;
            default -> throw new IllegalArgumentException("不支持的策略类型: " + raw);
        };
    }

    /**
     * 该策略是否由统一因子引擎实现。
     * <p>用于区分「旧路径」（需各自返回原有结构）与「新路径」（返回统一归因结构），
     * 避免调用方靠字符串比较判断。
     */
    public boolean isUnifiedEngine() {
        return this == UNIFIED;
    }
}
