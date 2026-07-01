package com.quant.service.backtest;

public enum StrategySignalType {
    MA_CROSS,
    BOLL,
    MACD;

    public static StrategySignalType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MA_CROSS;
        }
        return switch (raw.trim().toUpperCase()) {
            case "MA", "MA_CROSS", "MACROSS" -> MA_CROSS;
            case "BOLL", "BOLLINGER" -> BOLL;
            case "MACD" -> MACD;
            default -> throw new IllegalArgumentException("不支持的策略类型: " + raw);
        };
    }
}
