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
        String code = normalizeCode(stockCode);
        String exchange = exchangeType != null ? exchangeType.trim().toUpperCase() : "";

        // 注册制板块（创业板 300/301/302、科创板 688/689、北交所）ST 不适用 5%，
        // 涨跌停幅度与普通股一致（20%/30%）；仅主板 ST/*ST 适用 5%。
        if ("BSE".equals(exchange) || code.startsWith("8") || code.startsWith("4")) {
            return BSE_LIMIT_PCT;
        }
        if ("SSE".equals(exchange) && isStarStockCode(code)) {
            return CHINEXT_STAR_LIMIT_PCT;
        }
        if ("SZSE".equals(exchange) && isChiNextStockCode(code)) {
            return CHINEXT_STAR_LIMIT_PCT;
        }
        if (isStStock(stockName)) {
            return ST_LIMIT_PCT;
        }
        return MAIN_BOARD_LIMIT_PCT;
    }

    /** 科创板：688 常规 + 689 等扩展段，涨跌停幅度均为 20%。 */
    static boolean isStarStockCode(String code) {
        return code.startsWith("688") || code.startsWith("689");
    }

    /** 创业板：300 存量 + 301/302 注册制新股，涨跌停幅度均为 20%。 */
    static boolean isChiNextStockCode(String code) {
        return code.startsWith("300") || code.startsWith("301") || code.startsWith("302");
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
        // A 股证券代码为 6 位数字：提取数字并取末尾 6 位。
        // 兼容 "000001"、"SZ000001"、"sh600000"、"0000001"(多余前导零) 等输入形式，
        // 使板块推断基于确定性的 6 位代码，不依赖前导零剥离的巧合。
        StringBuilder digits = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        if (digits.length() == 0) {
            return "";
        }
        if (digits.length() > 6) {
            return digits.substring(digits.length() - 6);
        }
        return digits.toString();
    }
}
