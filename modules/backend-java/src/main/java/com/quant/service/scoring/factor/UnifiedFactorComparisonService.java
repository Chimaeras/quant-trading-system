package com.quant.service.scoring.factor;

import com.quant.service.backtest.FactorResearchEngine;
import com.quant.service.scoring.UnifiedScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批次 C：旧路径 vs 统一因子引擎的<b>同日对账</b>。
 *
 * <p>为什么需要：影子模式的意义在于「在不改旧行为的前提下，观察新引擎与旧引擎的差异」。
 * 若只把新结果并排显示，人眼无法判断差异是"排序微调"还是"选出了完全不同的股票"。
 * 本服务把差异量化为可比较的指标。
 *
 * <p>输出指标：
 * <ul>
 *   <li>{@code rank_correlation} —— 共同股票上两套分数的 Spearman 秩相关
 *       （复用 {@link FactorResearchEngine#spearmanRankCorrelation}）。
 *       <b>这是关键指标</b>：接近 1 说明新引擎只是重新表达了旧逻辑；
 *       接近 0 或为负说明两者在选不同的东西，必须先解释原因再谈替换。</li>
 *   <li>{@code top_n_overlap} —— 双方 TopN 的交集数量与比例（Jaccard 形态）。
 *       秩相关高但 TopN 交集低，说明差异恰好集中在头部——那正是最要命的位置。</li>
 *   <li>{@code score_distribution} —— 双方分数的 min/median/max/stdev 与唯一值个数。
 *       当前实测旧 MULTI_DIM 的 span 仅 4.48、stdev 0.993，若新引擎 span 显著更大，
 *       需明确这是"信息量提升"还是"噪声放大"——单看分布无法判定，故本服务只报告不裁决。</li>
 *   <li>{@code only_in_*} —— 只出现在一侧的股票清单（截断），便于直接盯盘核对。</li>
 * </ul>
 *
 * <p><b>本服务不做任何自动判定</b>：不输出"新引擎更好/更差"的结论。
 * 排序相关性高不等于更准，只等于更像；准不准要靠 forward return（批次 E）。
 */
@Service
public class UnifiedFactorComparisonService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedFactorComparisonService.class);

    /** 只在一侧出现的股票最多列出多少只，避免响应体过大。 */
    private static final int MAX_EXCLUSIVE_LIST = 50;

    private final UnifiedScoringService unifiedScoringService;
    private final UnifiedFactorScoringService unifiedFactorScoringService;

    public UnifiedFactorComparisonService(
            UnifiedScoringService unifiedScoringService,
            UnifiedFactorScoringService unifiedFactorScoringService) {
        this.unifiedScoringService = unifiedScoringService;
        this.unifiedFactorScoringService = unifiedFactorScoringService;
    }

    /**
     * 在同一交易日、同一 universe 上跑旧策略与统一引擎，并对比。
     *
     * @param legacyStrategy 旧策略名（TREND / COMPREHENSIVE / MULTI_DIM / CLASSIC_SELECTOR / OVERSOLD_REBOUND）
     * @param request        透传参数（tradeDate / topN / excludeST / minAvgAmount 等）
     */
    public Map<String, Object> compare(String legacyStrategy, Map<String, Object> request) {
        Map<String, Object> req = request != null ? new LinkedHashMap<>(request) : new LinkedHashMap<>();
        int topN = intOf(req.get("topN"), 100);

        // ── 旧路径：保留其原有结构与打分口径 ──────────────────────────────
        Map<String, Object> legacyReq = new LinkedHashMap<>(req);
        legacyReq.put("strategy", legacyStrategy);
        legacyReq.put("topN", topN);
        legacyReq.put("persist", false);   // 对账不落库
        legacyReq.put("includeItems", true);
        Map<String, Object> legacyOut = unifiedScoringService.run(legacyReq);

        // ── 新路径：统一因子引擎，用同一风格配置 ──────────────────────────
        Map<String, Object> unifiedReq = new LinkedHashMap<>(req);
        unifiedReq.put("unifiedStrategy", legacyStrategy);
        unifiedReq.put("topN", topN);
        unifiedReq.put("includeContributions", false); // 对账不需要逐因子明细，减小响应
        Map<String, Object> unifiedOut = unifiedFactorScoringService.run(unifiedReq);

        List<Map<String, Object>> legacyItems = readItems(legacyOut);
        List<Map<String, Object>> unifiedItems = readItems(unifiedOut);

        Map<String, Double> legacyScores = extractScores(legacyItems);
        Map<String, Double> unifiedScores = extractScores(unifiedItems);

        Set<String> common = new LinkedHashSet<>(legacyScores.keySet());
        common.retainAll(unifiedScores.keySet());
        List<String> commonSorted = new ArrayList<>(common);
        java.util.Collections.sort(commonSorted);

        List<Double> xs = new ArrayList<>(common.size());
        List<Double> ys = new ArrayList<>(common.size());
        for (String code : commonSorted) {
            xs.add(legacyScores.get(code));
            ys.add(unifiedScores.get(code));
        }
        double rankCorr = common.size() >= 2
                ? FactorResearchEngine.spearmanRankCorrelation(xs, ys)
                : Double.NaN;

        // TopN 交集
        List<String> legacyTop = topCodes(legacyItems, topN);
        List<String> unifiedTop = topCodes(unifiedItems, topN);
        Set<String> legacyTopSet = new LinkedHashSet<>(legacyTop);
        Set<String> unifiedTopSet = new LinkedHashSet<>(unifiedTop);
        Set<String> topIntersection = new LinkedHashSet<>(legacyTopSet);
        topIntersection.retainAll(unifiedTopSet);
        Set<String> topUnion = new LinkedHashSet<>(legacyTopSet);
        topUnion.addAll(unifiedTopSet);

        // 仅在单侧出现的股票
        List<String> onlyLegacy = new ArrayList<>();
        for (String c : legacyScores.keySet()) {
            if (!unifiedScores.containsKey(c)) {
                onlyLegacy.add(c);
            }
        }
        List<String> onlyUnified = new ArrayList<>();
        for (String c : unifiedScores.keySet()) {
            if (!legacyScores.containsKey(c)) {
                onlyUnified.add(c);
            }
        }
        onlyLegacy.sort(null);
        onlyUnified.sort(null);

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("legacy_strategy", legacyStrategy);
        comparison.put("legacy_count", legacyScores.size());
        comparison.put("unified_count", unifiedScores.size());
        comparison.put("common_count", common.size());
        comparison.put("rank_correlation", finite(rankCorr));
        comparison.put("rank_correlation_note",
                "Spearman 秩相关，仅在双方共同出现的股票上计算；"
                        + "接近 1 = 新引擎只是换算法表达同一排序，偏离 1 或为负 = 两者在选不同的东西。"
                        + "本值不衡量预测力。");
        comparison.put("top_n", topN);
        comparison.put("top_n_intersection", topIntersection.size());
        comparison.put("top_n_overlap_ratio",
                topUnion.isEmpty() ? null : round4((double) topIntersection.size() / topUnion.size()));
        comparison.put("top_n_overlap_note",
                "Jaccard 交并比。秩相关高但 TopN 交集低时，差异集中在头部——最需要关注的情形。");
        comparison.put("legacy_score_stats", stats(legacyScores.values()));
        comparison.put("unified_score_stats", stats(unifiedScores.values()));
        comparison.put("only_in_legacy", truncate(onlyLegacy));
        comparison.put("only_in_unified", truncate(onlyUnified));
        comparison.put("legacy_source_summary", legacyOut.get("source_summary"));
        comparison.put("unified_source_summary", unifiedOut.get("summary"));
        comparison.put("verdict", "NOT_EVALUATED");
        comparison.put("verdict_note",
                "本对账只描述差异，不判定优劣。哪套更准必须用 forward return 做样本外验证"
                        + "（批次 E，依赖逐日重放）；排序相关性不构成质量证据。");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("comparison", comparison);
        log.info("新旧打分对账 legacy={} common={} rankCorr={} topN={} topNIntersection={}",
                legacyStrategy, common.size(), comparison.get("rank_correlation"),
                topN, topIntersection.size());
        return out;
    }

    // ── 辅助 ──────────────────────────────────────────────────────────

    private static List<Map<String, Object>> readItems(Map<String, Object> result) {
        if (result == null) {
            return List.of();
        }
        Object items = result.get("items");
        if (items instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    out.add(cast);
                }
            }
            return out;
        }
        // 部分策略把明细放在嵌套键下
        for (String key : List.of("data", "result", "rows")) {
            Object nested = result.get(key);
            if (nested instanceof Map<?, ?> nm) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> nested2 = readItems((Map<String, Object>) nm);
                if (!nested2.isEmpty()) {
                    return nested2;
                }
            }
        }
        return List.of();
    }

    /**
     * 抽取 股票代码 -> 分数。
     *
     * <p>分数键按优先级尝试：不同策略把分数放在不同列（{@code total_score} /
     * {@code composite_score} / {@code final_score}）。统一到此处的单一取值，
     * 否则「新旧分数」会因取错列而完全不可比——这正是当前多套打分各自命名的代价。
     */
    private static Map<String, Double> extractScores(List<Map<String, Object>> items) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            String code = str(it.get("stock_code"));
            if (code == null || code.isBlank()) {
                continue;
            }
            Double score = firstNumber(it, "total_score", "final_score", "composite_score", "score");
            if (score != null) {
                // 同一代码出现多次时取较大者：对账场景下保守，避免因重复行低估覆盖
                out.merge(code, score, Math::max);
            }
        }
        return out;
    }

    private static List<String> topCodes(List<Map<String, Object>> items, int topN) {
        Map<String, Double> scores = extractScores(items);
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort((a, b) -> {
            int c = Double.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < entries.size() && i < topN; i++) {
            out.add(entries.get(i).getKey());
        }
        return out;
    }

    private static Map<String, Object> stats(java.util.Collection<Double> values) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (values == null || values.isEmpty()) {
            m.put("count", 0);
            return m;
        }
        List<Double> sorted = new ArrayList<>();
        for (Double v : values) {
            if (v != null && !v.isNaN() && !v.isInfinite()) {
                sorted.add(v);
            }
        }
        if (sorted.isEmpty()) {
            m.put("count", 0);
            return m;
        }
        sorted.sort(null);
        int n = sorted.size();
        double min = sorted.get(0);
        double max = sorted.get(n - 1);
        double median = n % 2 == 1 ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        double mean = 0;
        for (double v : sorted) {
            mean += v;
        }
        mean /= n;
        double var = 0;
        for (double v : sorted) {
            var += (v - mean) * (v - mean);
        }
        var = n > 1 ? var / (n - 1) : 0;
        m.put("count", n);
        m.put("min", round4(min));
        m.put("median", round4(median));
        m.put("max", round4(max));
        m.put("mean", round4(mean));
        m.put("stdev", round4(Math.sqrt(var)));
        m.put("span", round4(max - min));
        m.put("unique_values", new LinkedHashSet<>(sorted).size());
        return m;
    }

    /** 非有限值（NaN/Inf）转为 null，避免 JSON 里出现 NaN。 */
    private static Double finite(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? null : round4(v);
    }

    private static List<String> truncate(List<String> in) {
        if (in.size() <= MAX_EXCLUSIVE_LIST) {
            return in;
        }
        List<String> out = new ArrayList<>(in.subList(0, MAX_EXCLUSIVE_LIST));
        out.add("...(共 " + in.size() + " 只，已截断)");
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Double firstNumber(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v != null) {
                try {
                    return Double.parseDouble(String.valueOf(v));
                } catch (NumberFormatException ignored) {
                    // 继续尝试下一个键
                }
            }
        }
        return null;
    }

    private static int intOf(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0d;
        }
        return Math.round(v * 10000d) / 10000d;
    }
}
