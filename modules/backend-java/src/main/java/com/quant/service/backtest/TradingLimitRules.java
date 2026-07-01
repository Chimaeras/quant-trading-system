package com.quant.service.backtest;

/**
 * A 股涨跌停幅度规则（用于回测可成交性判断）。
 * 口径：按证券代码板块与 ST 状态推断，注册制板块采用 20%/30%，ST 采用 5%。
 */
public final class TradingLimitRules {

    public static final double MAIN_BOARD_LIMIT_PCT = 0.10;
    public static final double ST_LIMIT_PCT = 0.05;
    public static final double CHINEXT_STAR_LIMIT_PCT = 0.20;
    public static final double BSE_LIMIT_PCT = 0.30;

    private TradingLimitRules() {}

    /**
     * @param stockCode     证券代码（可含前缀 0）
     * @param exchangeType  交易所，如 SSE / SZSE / BSE
     * @param stockName     证券名称，用于识别 ST
     */
    public static double resolveLimitPct(String stockCode, String exchangeType, String stockName) {
        if (isStStock(stockName)) {
            return ST_LIMIT_PCT;
        }
        String code = normalizeCode(stockCode);
        String exchange = exchangeType != null ? exchangeType.trim().toUpperCase() : "";

        if ("BSE".equals(exchange) || code.startsWith("8") || code.startsWith("4")) {
            return BSE_LIMIT_PCT;
        }
        if ("SSE".equals(exchange) && code.startsWith("688")) {
            return CHINEXT_STAR_LIMIT_PCT;
        }
        if ("SZSE".equals(exchange) && code.startsWith("300")) {
            return CHINEXT_STAR_LIMIT_PCT;
        }
        return MAIN_BOARD_LIMIT_PCT;
    }

    static boolean isStStock(String stockName) {
        if (stockName == null || stockName.isBlank()) {
            return false;
        }
        return stockName.toUpperCase().contains("ST");
    }

    static String normalizeCode(String stockCode) {
        if (stockCode == null) {
            return "";
        }
        String code = stockCode.trim();
        return code.startsWith("0") && code.length() > 6 ? code.replaceFirst("^0+", "") : code;
    }
}
