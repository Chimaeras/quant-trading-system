package com.quant.service.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

/**
 * 打分系统通用工具方法
 */
public final class ScoringUtils {

    private ScoringUtils() {}

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
     * 检查当前线程是否被中断
     */
    public static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("任务被取消（线程中断）");
        }
    }
}
