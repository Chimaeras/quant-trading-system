package com.quant.service.scoring.factor;

import com.quant.entity.SelectorResultItem;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把投影后的条目写成既有 {@code selector_result_item} 行。
 *
 * <p><b>为什么单独抽出这一层</b>：这是"与原落库结构兼容"这一硬约束的**执行点**。
 * {@link LegacyItemProjector} 决定字段叫什么，本类决定它们如何落进旧列——
 * 两者合起来才构成"统一引擎的输出可以直接写进旧表"这一主张。
 * 把写库语义散落在服务里，就无法对它单独做往返（round-trip）测试。
 *
 * <p><b>数值语义与 {@code UnifiedScoringService} 的写入路径逐条对齐</b>：
 * <ul>
 *   <li>{@code final_score / trend_score / momentum_score / volume_score / risk_penalty}
 *       这五列在库中仍是 {@code NOT NULL}，故缺键时落 {@code 0.0}。</li>
 *   <li>{@code ret20 / ret60 / volatility20 / max_drawdown60 / avg_amount20}
 *       在 V30 后可为 NULL，故<b>缺键即 NULL</b>，绝不用 0 顶替——
 *       否则 A-3（「未计算」伪装成 0）会在落库这一层复活。</li>
 *   <li>非有限值（NaN/Infinity）一律视为缺失（NULL），不让脏值进库。</li>
 * </ul>
 *
 * <p>实现上是<b>有意复制</b>而非调用 {@code UnifiedScoringService} 的私有方法：
 * 那些方法是私有的，且改动已稳定的 578 测试规模的服务风险高于复制成本。
 * 复制带来的口径分化风险由 {@code LegacyResultRowWriterTest} 的逐列断言兜住。
 */
@Component
public class LegacyResultRowWriter {

    /**
     * 构造一条待落库的条目。
     *
     * @param item        {@link LegacyItemProjector#project} 的产物
     * @param resultId    所属 selector_result 的 result_id
     * @param rankNo      排名
     * @param legacyType  旧口径策略名（写进 {@code strategy_type}，保持前端分组可用）
     */
    public SelectorResultItem toRow(Map<String, Object> item, String resultId,
                                    int rankNo, String legacyType) {
        SelectorResultItem e = new SelectorResultItem();
        e.setResultId(resultId);
        e.setRankNo(rankNo);
        e.setStrategyType(legacyType);
        e.setStockCode(str(item, "stock_code", "code"));
        e.setExchangeType(str(item, "exchange_type", "exchange", "market"));
        e.setStockName(str(item, "stock_name", "name"));

        // NOT NULL 列：缺键落 0.0
        e.setFinalScore(firstDouble(item, "final_score", "total_score", "composite_score", "score"));
        e.setTrendScore(firstDouble(item, "trend_score", "score_trend", "score_technical"));
        e.setMomentumScore(firstDouble(item, "momentum_score", "score_bias"));
        e.setVolumeScore(firstDouble(item, "volume_score", "score_volume"));
        e.setRiskPenalty(firstDouble(item, "risk_penalty"));

        // V30 可空列：缺键即 NULL，不得兜底成 0。
        // 注意这里用 raw 版本（不 round2）：这些列是**原始行情度量**，量纲不是 0-100。
        // 20 日波动率典型量纲是「百分数」中的小数（如 0.002417 = 0.24%），
        // 若按 2 位小数取整会被压成 0.0 —— 那正是 A-3 要消灭的「真实值被伪装成占位 0」。
        // 这个缺陷是写本类的测试时实测发现的：`volatility20=0.002417` 落库后变成 0.0。
        e.setRet20(firstDoubleOrNullRaw(item, "ret20"));
        e.setRet60(firstDoubleOrNullRaw(item, "ret60"));
        e.setVolatility20(firstDoubleOrNullRaw(item, "volatility20"));
        e.setMaxDrawdown60(firstDoubleOrNullRaw(item, "max_drawdown60"));
        e.setAvgAmount20(firstDoubleOrNullRaw(item, "avg_amount20"));

        Map<String, Object> reasons = new LinkedHashMap<>();
        reasons.put("strategy", legacyType);
        reasons.put("unified", true);
        reasons.put("raw_item", item);
        e.setReasons(reasons);

        // extra_metrics 原样保留统一引擎的披露：factor_contributions / data_quality /
        // score_semantics / available_weight_ratio。落库不得丢掉可解释性——
        // 否则前端只能看到分数而看不到"为什么是这个分"，回到重构前的老问题。
        Object extra = item.get("extra_metrics");
        if (extra instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) {
                copy.put(String.valueOf(en.getKey()), en.getValue());
            }
            e.setExtraMetrics(copy);
        } else {
            e.setExtraMetrics(new LinkedHashMap<>());
        }
        return e;
    }

    // ── 与 UnifiedScoringService 对齐的取值助手 ────────────────────────

    static String str(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return "";
    }

    static double firstDouble(Map<String, Object> m, String... keys) {
        Double v = firstDoubleOrNull(m, keys);
        return v != null ? v : 0.0;
    }

    /** 键缺失时返回 null 而非 0.0（V30 语义）。非有限值同样视为缺失。 */
    static Double firstDoubleOrNull(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v == null) {
                continue;
            }
            if (v instanceof Number n) {
                double d = n.doubleValue();
                return (Double.isNaN(d) || Double.isInfinite(d)) ? null : round2(d);
            }
            try {
                double d = Double.parseDouble(String.valueOf(v).trim());
                return (Double.isNaN(d) || Double.isInfinite(d)) ? null : round2(d);
            } catch (Exception ignored) {
                // 该键存在但无法解析：继续尝试后续键
            }
        }
        return null;
    }

    /**
     * 键缺失时返回 null 而非 0.0，并<b>保留完整精度</b>（V30 语义）。
     *
     * <p>与 {@link #firstDoubleOrNull} 的唯一差别是不做 2 位小数取整。
     * 用于量纲不是 0-100 的原始行情列——对它们取整到 2 位小数会把真实的小数值压成 0.0。
     */
    static Double firstDoubleOrNullRaw(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v == null) {
                continue;
            }
            if (v instanceof Number n) {
                double d = n.doubleValue();
                return (Double.isNaN(d) || Double.isInfinite(d)) ? null : d;
            }
            try {
                double d = Double.parseDouble(String.valueOf(v).trim());
                return (Double.isNaN(d) || Double.isInfinite(d)) ? null : d;
            } catch (Exception ignored) {
                // 该键存在但无法解析：继续尝试后续键
            }
        }
        return null;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
