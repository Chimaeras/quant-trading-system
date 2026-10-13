package com.quant.service.scoring;

import com.quant.entity.MarketData;
import com.quant.service.backtest.StrategyBacktestEngine;
import com.quant.service.backtest.TradingLimitRules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 打分系统通用工具方法
 */
public final class ScoringUtils {

    private ScoringUtils() {}

    // ==================== 成交额单位与流动性闸 ====================

    /**
     * 成交额列（{@code market_data.amount} / {@code market_price_raw.amount}）的单位：<strong>万元</strong>。
     *
     * <p><b>证据（D-R001-01 / D-R005-03）</b>：R001 在真实行上实测
     * {@code amount / (volume × close) ≈ 1/100}（换手率交叉核对证明 {@code volume} 的单位是「手」），
     * 且 600519 单日 {@code amount ≈ 530,365} 与「万元」下的约 53 亿元/日自洽；若按「元」解读
     * 则为 53 万元/日，与任何 A 股流动性常识矛盾。{@code avg_amount20} 的实测量级
     * 8.16e4–6.41e5（万元）即 8 亿–64 亿元/日，同样自洽。
     *
     * <p><b>已知冲突</b>：{@code modules/database/01_init_schema.sql} 与 {@code V1__baseline_core.sql}
     * 的列注释写「成交额(元)」，前端 {@code StockPicker.vue} 的标签也写「(元)」——
     * 本轮把这批声明改为与实测一致；活库中的列注释需人工执行
     * {@code ALTER TABLE market_data MODIFY amount DECIMAL(20,4) COMMENT '成交额(万元)'}
     * （本仓库不自动执行 DDL）。
     *
     * <p><b>残余不确定</b>：R005 曾以「30000 与 30000000 恰差 1000×」推测单位为「千元」。
     * 两种读法（万元/千元）都落在同一量级档次（1e4–6e5），**都不支持** 3e7 这一档阈值，
     * 故本轮的阈值修正对「万元 vs 千元」之争是稳健的；单位声明采用证据更强的「万元」。
     */
    public static final String AMOUNT_UNIT = "TEN_THOUSAND_CNY";

    /**
     * 流动性闸默认下限，单位与 {@link #AMOUNT_UNIT} 相同（万元）⟹ 约 <strong>3 亿元/日</strong>。
     *
     * <p><b>为什么改回 3e4</b>：本常量原为 {@code 30_000_000d}（把「万元」当「元」用），
     * 在实测数据量级（1e4–6e5 万元）下是 **1000× 偏高**、任何候选都不可能满足，
     * 使 4 处硬过滤（{@code if (!passesMinAvgAmount(...)) continue;}）恒为真。
     * 阈值必须是「一个能被流动性正常的股票满足」的数：3e4 万元 = 3 亿元/日。
     * 该值同时与改动前的 {@code 30000}（旧流水线、活库低档）一致。
     */
    public static final double DEFAULT_MIN_AVG_AMOUNT = 30_000d;

    /** 流动性闸阈值的单位声明（与成交额列同单位）。 */
    public static final String MIN_AVG_AMOUNT_UNIT = AMOUNT_UNIT;

    /** 诊断取值：阈值高出候选量级 100× 以上 ⟹ 几乎不可能由任何候选满足（单位错配的典型形态）。 */
    public static final String LIQUIDITY_GATE_SUSPECTED_UNIT_MISMATCH = "SUSPECTED_UNIT_MISMATCH";
    /** 诊断取值：阈值与候选量级同档，闸可正常区分流动性。 */
    public static final String LIQUIDITY_GATE_CONSISTENT = "CONSISTENT";
    /** 诊断取值：候选不足 5 个，不作量级判断。 */
    public static final String LIQUIDITY_GATE_NO_OBSERVATIONS = "NO_OBSERVATIONS";

    private static final String LIQUIDITY_GATE_DIAGNOSIS_NOTE =
            "只读诊断（不改任何阈值）：把配置阈值与候选 avg_amount20 的中位数按同一单位比较。"
                    + "命中 SUSPECTED_UNIT_MISMATCH 表示阈值高出候选量级 ≥100×——这在单位被写错时"
                    + "（把万元当元用）是典型形态，此时闸恒为真、候选被全量过滤且不易察觉；"
                    + "不代表阈值本身一定错（极冷门样本亦可能整体低于一个合理阈值）";

    // ==================== 数值处理 ====================

    /**
     * 四舍五入保留两位小数
     */
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 四舍五入保留四位小数
     */
    public static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * 使用BigDecimal精确舍入
     */
    public static double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 计算涨跌幅百分比
     */
    public static double pct(double current, double previous) {
        if (Math.abs(previous) < 1e-9) return 0;
        return (current / previous - 1) * 100;
    }

    /**
     * 信号日成交量为 0 时视为不可交易（停牌代理）；volume 为 null 时不据此过滤。
     */
    public static boolean isExplicitZeroVolume(com.quant.entity.MarketData bar) {
        return bar != null && bar.getVolume() != null && bar.getVolume() <= 0;
    }

    /**
     * 信号日 K 线必须与 {@code tradeDate} 对齐，避免停牌/缺 bar 时用过期行情打分。
     * {@code tradeDate} 为 null 时跳过校验（仅单元测试场景；生产选股须传入信号日）。
     */
    public static boolean isSignalBarOnTradeDate(
            List<com.quant.entity.MarketData> bars,
            java.time.LocalDate tradeDate) {
        if (tradeDate == null) {
            return true;
        }
        if (bars == null || bars.isEmpty()) {
            return false;
        }
        java.time.LocalDate lastBarDate = bars.get(bars.size() - 1).getTradeDate();
        return lastBarDate != null && tradeDate.equals(lastBarDate);
    }

    /** 信号日可交易性：零成交量、ST、信号日涨跌停（默认均过滤）。 */
    public static boolean isTradableForScoring(com.quant.entity.MarketData signalBar, String stockName, boolean excludeST) {
        return isTradableForScoring(signalBar, null, null, null, stockName, excludeST, true, true);
    }

    /**
     * 基于 K 线序列判断信号日（最后一根）是否可纳入选股候选。
     *
     * @param excludeLimitUpAtClose   信号日涨停收盘时排除（买入难以成交）
     * @param excludeLimitDownAtClose 信号日跌停收盘时排除；超跌策略可设为 false
     */
    public static boolean isTradableForScoring(
            List<com.quant.entity.MarketData> bars,
            String stockCode,
            String exchangeType,
            String stockName,
            boolean excludeST,
            boolean excludeLimitUpAtClose,
            boolean excludeLimitDownAtClose) {
        return isTradableForScoring(
                bars, stockCode, exchangeType, stockName, null,
                excludeST, excludeLimitUpAtClose, excludeLimitDownAtClose);
    }

    public static boolean isTradableForScoring(
            List<com.quant.entity.MarketData> bars,
            String stockCode,
            String exchangeType,
            String stockName,
            Integer stockStatus,
            boolean excludeST,
            boolean excludeLimitUpAtClose,
            boolean excludeLimitDownAtClose) {
        if (!isActiveStockStatus(stockStatus)) {
            return false;
        }
        if (bars == null || bars.isEmpty()) {
            return false;
        }
        return isTradableForScoring(
                bars.get(bars.size() - 1),
                resolvePreClose(bars),
                stockCode,
                exchangeType,
                stockName,
                excludeST,
                excludeLimitUpAtClose,
                excludeLimitDownAtClose);
    }

    public static Double resolvePreClose(List<com.quant.entity.MarketData> bars) {
        if (bars == null || bars.size() < 2) {
            return null;
        }
        com.quant.entity.MarketData prev = bars.get(bars.size() - 2);
        if (prev.getClosePrice() == null) {
            return null;
        }
        double v = prev.getClosePrice().doubleValue();
        return v > 0 ? v : null;
    }

    public static boolean isTradableForScoring(
            com.quant.entity.MarketData signalBar,
            Double preClose,
            String stockCode,
            String exchangeType,
            String stockName,
            boolean excludeST,
            boolean excludeLimitUpAtClose,
            boolean excludeLimitDownAtClose) {
        if (isExplicitZeroVolume(signalBar)) {
            return false;
        }
        if (excludeST && stockName != null && stockName.toUpperCase().contains("ST")) {
            return false;
        }
        if (preClose == null || preClose <= 0 || stockCode == null || stockCode.isBlank()) {
            return true;
        }
        double limitPct = TradingLimitRules.resolveLimitPct(stockCode, exchangeType, stockName);
        if (excludeLimitUpAtClose
                && StrategyBacktestEngine.isLimitUpAtClose(signalBar, preClose, limitPct)) {
            return false;
        }
        if (excludeLimitDownAtClose
                && StrategyBacktestEngine.isLimitDownAtClose(signalBar, preClose, limitPct)) {
            return false;
        }
        return true;
    }

    /**
     * 上市满 N 个自然日才可纳入候选；listDate 缺失时不据此过滤（保守放行并依赖 min_listing_days 披露）。
     */
    public static boolean hasMinListingTenure(LocalDate listDate, LocalDate tradeDate, int minListingDays) {
        return hasMinListingTenure(listDate, tradeDate, minListingDays, null);
    }

    /**
     * listDate 缺失时，可选使用 K 线最早交易日作为上市日代理（仅当 bars 非空）。
     */
    public static boolean hasMinListingTenure(
            LocalDate listDate,
            LocalDate tradeDate,
            int minListingDays,
            List<MarketData> bars) {
        if (tradeDate == null) {
            return true;
        }
        LocalDate effectiveListDate = listDate;
        if (effectiveListDate == null && bars != null) {
            for (MarketData bar : bars) {
                if (bar == null || bar.getTradeDate() == null) {
                    continue;
                }
                if (effectiveListDate == null || bar.getTradeDate().isBefore(effectiveListDate)) {
                    effectiveListDate = bar.getTradeDate();
                }
            }
        }
        // D-PIT-03（2026-10-13 修正）：「asOf 当时尚未上市」是**时点有效性**约束，不是可关闭的
        // 偏好阈值。此前 minListingDays<=0 在上市日校验之前直接 return true，于是当调用方把
        // 上市时长闸配成 0（StockSelectorService 的 minListingDays 可由请求覆盖）时，
        // 未来才上市的标的会被放进历史截面。故先判上市日，再判时长。
        if (effectiveListDate != null && effectiveListDate.isAfter(tradeDate)) {
            return false;
        }
        if (minListingDays <= 0 || effectiveListDate == null) {
            return true;
        }
        return ChronoUnit.DAYS.between(effectiveListDate, tradeDate) >= minListingDays;
    }

    /** stock_info.status：1=正常，0=停牌，-1=退市；缺失时视为正常。 */
    public static boolean isActiveStockStatus(Integer status) {
        return status == null || status == 1;
    }

    /**
     * 最近 20 个<strong>有成交</strong>交易日的日均成交额，单位<strong>万元</strong>（= {@link #AMOUNT_UNIT}，
     * 与源列 {@code market_data.amount} 同单位；见该常量处的证据与冲突说明）。
     * 零成交量（停牌代理）日不计入样本；不足 20 个有效样本时按实际有效日数求平均，
     * 不再直接归零——避免停牌偏多的小票被 minAvgAmount 误杀（上市时长另由 minListingDays 闸把关）。
     */
    public static double avgAmount20(List<MarketData> bars) {
        if (bars == null || bars.isEmpty()) {
            return 0;
        }
        double sum = 0;
        int count = 0;
        for (int i = bars.size() - 1; i >= 0 && count < 20; i--) {
            MarketData bar = bars.get(i);
            if (isExplicitZeroVolume(bar)) {
                continue;
            }
            sum += bar.getAmount() != null ? bar.getAmount().doubleValue() : 0;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    /** 当 minAvgAmount &gt; 0 时，要求 avgAmount20 不低于该阈值。阈值单位必须是万元，见 {@link #AMOUNT_UNIT}。 */
    public static boolean passesMinAvgAmount(List<MarketData> bars, double minAvgAmount) {
        return minAvgAmount <= 0 || avgAmount20(bars) >= minAvgAmount;
    }

    /**
     * 流动性闸的<strong>单位自洽诊断</strong>（纯函数、只读、不改阈值）。
     *
     * <p>把配置阈值与候选 {@code avg_amount20} 的<strong>中位数</strong>按同一单位比较：
     * 阈值高出中位数 ≥ 100× 时，闸几乎不可能被任何候选满足——这正是单位被写错
     * （万元当元用）时的形态，且外部只看到「筛不出票」，难以察觉。
     *
     * <p>候选少于 5 个时返回 {@link #LIQUIDITY_GATE_NO_OBSERVATIONS}：中位数在小样本上不是量级证据。
     * 判据是**相对**的（阈值与中位数之比），故与具体单位无关。
     */
    public static String liquidityGateMagnitudeDiagnosis(List<Double> avgAmounts, double minAvgAmount) {
        if (minAvgAmount <= 0) {
            return LIQUIDITY_GATE_CONSISTENT;
        }
        if (avgAmounts == null) {
            return LIQUIDITY_GATE_NO_OBSERVATIONS;
        }
        List<Double> usable = new ArrayList<>();
        for (Double v : avgAmounts) {
            if (v != null && Double.isFinite(v) && v > 0) {
                usable.add(v);
            }
        }
        if (usable.size() < 5) {
            return LIQUIDITY_GATE_NO_OBSERVATIONS;
        }
        List<Double> sorted = new ArrayList<>(usable);
        sorted.sort(Double::compareTo);
        double median = sorted.get(sorted.size() / 2);
        return minAvgAmount >= 100.0 * median
                ? LIQUIDITY_GATE_SUSPECTED_UNIT_MISMATCH
                : LIQUIDITY_GATE_CONSISTENT;
    }

    /** 诊断说明文本（随诊断值一起发射，读者不必回查源码）。 */
    public static String liquidityGateDiagnosisNote() {
        return LIQUIDITY_GATE_DIAGNOSIS_NOTE;
    }

    /**
     * bulkFetch=false 时仅用窗口内最早交易日代理 list_date，供 pair 级上市时长预过滤。
     */
    public static List<MarketData> listingTenureBars(List<MarketData> bulkBars, LocalDate earliestInWindow) {
        if (bulkBars != null && !bulkBars.isEmpty()) {
            return bulkBars;
        }
        if (earliestInWindow == null) {
            return null;
        }
        MarketData proxy = new MarketData();
        proxy.setTradeDate(earliestInWindow);
        return List.of(proxy);
    }

    public static Map<String, LocalDate> mapEarliestTradeDatesByKey(List<Object[]> rows) {
        Map<String, LocalDate> out = new HashMap<>();
        if (rows == null) {
            return out;
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 3 || row[0] == null || row[1] == null || row[2] == null) {
                continue;
            }
            LocalDate d = coerceLocalDate(row[2]);
            if (d != null) {
                out.put(String.valueOf(row[0]) + "|" + String.valueOf(row[1]), d);
            }
        }
        return out;
    }

    private static LocalDate coerceLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.util.Date utilDate) {
            return new java.sql.Date(utilDate.getTime()).toLocalDate();
        }
        return null;
    }

    /**
     * 选股可交易性过滤假设，写入 factor_snapshot 供前端与审计追溯。
     */
    public static Map<String, Object> tradabilitySnapshot(boolean excludeST, boolean excludeLimitDownAtClose) {
        return tradabilitySnapshot(excludeST, excludeLimitDownAtClose, 0);
    }

    public static Map<String, Object> tradabilitySnapshot(
            boolean excludeST,
            boolean excludeLimitDownAtClose,
            int minListingDays) {
        return tradabilitySnapshot(excludeST, excludeLimitDownAtClose, minListingDays, true);
    }

    public static Map<String, Object> tradabilitySnapshot(
            boolean excludeST,
            boolean excludeLimitDownAtClose,
            int minListingDays,
            boolean bulkFetch) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("exclude_st", excludeST);
        t.put("exclude_zero_volume", true);
        t.put("exclude_limit_up_at_close", true);
        t.put("exclude_limit_down_at_close", excludeLimitDownAtClose);
        t.put("exclude_non_active_status", true);
        t.put("status_semantics", "1=active,0=suspended,-1=delisted,null=assume_active");
        t.put("min_listing_days", Math.max(0, minListingDays));
        t.put("listing_tenure_basis", "CALENDAR_DAYS_FROM_LIST_DATE_OR_EARLIEST_BAR");
        t.put("listing_tenure_bar_scope", bulkFetch
                ? "FULL_BULK_BARS_IN_LOOKBACK"
                : "LOOKBACK_WINDOW_EARLIEST_TRADE_DATE_ONLY");
        if (!bulkFetch && minListingDays > 0) {
            t.put("listing_tenure_warning",
                    "bulkFetch=false 时 pair 预过滤仅见 lookback 窗口内最早交易日，可能低估上市时长");
        }
        t.put("bulk_fetch", bulkFetch);
        t.put("limit_pct_source", "BY_BOARD_AND_ST");
        t.put("limit_price_basis", "PREV_CLOSE_TO_SIGNAL_CLOSE");
        t.put("avg_amount20_basis", "MEAN_AMOUNT_LAST_20_NON_ZERO_VOLUME_DAYS");
        t.put("min_avg_amount_gate", "avgAmount20 >= minAvgAmount when minAvgAmount > 0");
        return t;
    }

    /**
     * 按得分降序列表取 TopN，仅保留分数不低于 minScore 的候选；不足 TopN 时允许更少或为空。
     */
    public static List<Map<String, Object>> pickTopNByMinScore(
            List<Map<String, Object>> items,
            double minScore,
            int topN,
            String... scoreKeys) {
        if (items == null || items.isEmpty() || topN <= 0 || scoreKeys == null || scoreKeys.length == 0) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(Math.min(topN, items.size()));
        for (Map<String, Object> item : items) {
            if (out.size() >= topN) {
                break;
            }
            if (scoreFromMap(item, scoreKeys) >= minScore) {
                out.add(item);
            }
        }
        return out;
    }

    public static int countByMinScore(List<Map<String, Object>> items, double minScore, String... scoreKeys) {
        if (items == null || items.isEmpty() || scoreKeys == null || scoreKeys.length == 0) {
            return 0;
        }
        int n = 0;
        for (Map<String, Object> item : items) {
            if (scoreFromMap(item, scoreKeys) >= minScore) {
                n++;
            }
        }
        return n;
    }

    private static double scoreFromMap(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            Object v = item.get(key);
            if (v == null) {
                continue;
            }
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /**
     * 判断两个double是否近似相等
     */
    public static boolean nearlyEqual(double a, double b, double epsilon) {
        return Math.abs(a - b) < epsilon;
    }

    /**
     * 安全除法
     */
    public static double safeDivide(double numerator, double denominator, double defaultValue) {
        if (Math.abs(denominator) < 1e-9) return defaultValue;
        return numerator / denominator;
    }

    // ==================== 参数解析 ====================

    /**
     * 解析日期参数
     */
    public static LocalDate parseDate(Object value) {
        return parseDate(value, null);
    }

    /**
     * 解析日期参数（带默认值）
     */
    public static LocalDate parseDate(Object value, LocalDate defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof LocalDate d) return d;
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 解析布尔参数
     */
    public static boolean parseBool(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        String s = String.valueOf(value).trim().toLowerCase();
        if (s.isEmpty()) return defaultValue;
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "y".equals(s);
    }

    /**
     * 解析整数参数
     */
    public static int parseInt(Object value, int defaultValue, int min, int max) {
        try {
            int n = value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
            return Math.max(min, Math.min(max, n));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 解析整数参数（无范围限制）
     */
    public static int parseInt(Object value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 解析浮点数参数
     */
    public static double parseDouble(Object value, double defaultValue, double min, double max) {
        try {
            double d = value == null ? defaultValue : Double.parseDouble(String.valueOf(value));
            return Math.max(min, Math.min(max, d));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 解析浮点数参数（无范围限制）
     */
    public static double parseDouble(Object value, double defaultValue) {
        try {
            return value == null ? defaultValue : Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 解析字符串参数
     */
    public static String parseString(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? defaultValue : s;
    }

    // ==================== Map工具方法 ====================

    /**
     * 从Map中获取字符串
     */
    public static String getStr(Map<String, Object> map, String key, String defaultValue) {
        if (map == null || !map.containsKey(key)) return defaultValue;
        Object v = map.get(key);
        if (v == null) return defaultValue;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? defaultValue : s;
    }

    /**
     * 从Map中获取多个可能的key对应的字符串
     */
    public static String getStr(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    /**
     * 从Map中获取Double
     */
    public static Double getDouble(Map<String, Object> map, String key, Double defaultValue) {
        if (map == null || !map.containsKey(key)) return defaultValue;
        Object v = map.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 从Map中获取多个可能的key对应的第一个有效Double
     */
    public static double firstDouble(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object v = map.get(key);
            if (v == null) continue;
            if (v instanceof Number n) return round2(n.doubleValue());
            try {
                return round2(Double.parseDouble(String.valueOf(v)));
            } catch (Exception ignored) {}
        }
        return 0.0;
    }

    /**
     * 从Map中获取Integer
     */
    public static Integer getInt(Map<String, Object> map, String key, Integer defaultValue) {
        if (map == null || !map.containsKey(key)) return defaultValue;
        Object v = map.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 从Map中获取Boolean
     */
    public static Boolean getBool(Map<String, Object> map, String key, Boolean defaultValue) {
        if (map == null || !map.containsKey(key)) return defaultValue;
        return parseBool(map.get(key), defaultValue);
    }

    // ==================== 空值处理 ====================

    /**
     * 空值替换
     */
    public static <T> T nvl(T value, T defaultValue) {
        return value != null ? value : defaultValue;
    }

    /**
     * 检查字符串是否为空
     */
    public static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 检查字符串是否不为空
     */
    public static boolean isNotEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ==================== 验证方法 ====================

    /**
     * 确保值在指定范围内
     */
    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 确保值在0-100范围内（打分专用）
     */
    public static double clampScore(double score) {
        return clamp(score, 0, 100);
    }

    /**
     * 子维度打分钳制（钳制在0-max之间）
     */
    public static double clampSubScore(double score, double max) {
        return clamp(score, 0, max);
    }

    /**
     * 检查当前线程是否被中断
     */
    public static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("任务被取消（线程中断）");
        }
    }

    /**
     * 计算收益率序列标准差（小数）
     */
    public static double stdPct(java.util.List<Double> close, int period) {
        if (close == null || close.size() < period + 1) return 0;
        java.util.List<Double> rets = new java.util.ArrayList<>();
        for (int i = close.size() - period; i < close.size(); i++) {
            if (i <= 0) continue;
            double prev = close.get(i - 1);
            if (prev <= 0) continue;
            rets.add(close.get(i) / prev - 1);
        }
        if (rets.isEmpty()) return 0;
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = rets.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(var);
    }

    /**
     * 基于 ATR 计算动态止损建议。
     * @param currentPrice 当前价格
     * @param atr14 14日ATR值
     * @return [保守止损价, 激进止损价]，止损幅度以价格百分比表示
     */
    public static double[] atrStopLevels(double currentPrice, double atr14) {
        if (currentPrice <= 0 || atr14 <= 0) return new double[]{currentPrice * 0.95, currentPrice * 0.97};
        double conservativeStop = currentPrice - 2.5 * atr14;
        double aggressiveStop = currentPrice - 1.5 * atr14;
        return new double[]{
            Math.max(conservativeStop, currentPrice * 0.90),
            Math.max(aggressiveStop, currentPrice * 0.93)
        };
    }

    /**
     * 计算区间最大回撤（百分比）
     */
    public static double maxDrawdown(java.util.List<Double> close, int period) {
        if (close == null || close.size() < 2) return 0;
        int from = Math.max(0, close.size() - period);
        double peak = close.get(from);
        double mdd = 0;
        for (int i = from; i < close.size(); i++) {
            peak = Math.max(peak, close.get(i));
            if (peak <= 0) continue;
            double dd = (peak - close.get(i)) / peak * 100;
            mdd = Math.max(mdd, dd);
        }
        return mdd;
    }
}
