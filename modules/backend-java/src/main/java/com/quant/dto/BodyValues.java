package com.quant.dto;

/**
 * 请求体取值的容错解析（V2-MP-028）。
 *
 * <p>控制器里 `Long.valueOf(String.valueOf(body.get("userId")))` 这种写法有两个坑：
 * 非数字字符串（"abc"）和浮点字面量（1.0 —— 某些 HTTP 客户端会把整型 id 序列化成
 * 浮点）都会抛 {@code NumberFormatException}，而全局异常处理器没有它的映射，
 * 于是客户端拿到 500 而不是 400。这里统一按 400 语义（{@code IllegalArgumentException}）
 * 抛错，并由 {@code GlobalExceptionHandler} 映射。
 */
public final class BodyValues {

    private BodyValues() {
    }

    /** 可空 Long：null/空白 → null；非法数字 → 400。 */
    public static Long parseOptionalLong(Object value, String field) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            // 兼容 "1.0" 这类整数值的浮点字面量；非整值仍按非法处理。
            try {
                double d = Double.parseDouble(text);
                if (d == Math.rint(d) && !Double.isInfinite(d)) {
                    return (long) d;
                }
            } catch (NumberFormatException ignored) {
                // 落到下面的统一报错
            }
            throw new IllegalArgumentException(field + " 必须是整数，实际 " + value);
        }
    }

    /** 带默认值的 Long：null/空白 → fallback；非法数字 → 400。 */
    public static Long parseLongOrDefault(Object value, long fallback, String field) {
        Long parsed = parseOptionalLong(value, field);
        return parsed != null ? parsed : fallback;
    }

    /** 可空 Integer：null/空白 → null；非法数字 → 400。 */
    public static Integer parseOptionalInt(Object value, String field) {
        Long parsed = parseOptionalLong(value, field);
        if (parsed == null) {
            return null;
        }
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " 超出整数范围，实际 " + value);
        }
        return parsed.intValue();
    }
}
