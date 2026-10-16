package com.quant.service.scoring.factor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把统一引擎的 {@link UnifiedFactorScoringEngine.StockScore} 投影成
 * <b>既有 {@code selector_result_item} 形状</b>的条目。
 *
 * <p><b>为什么需要这一层</b>：重构目标是「一次打分、5 个策略入口」，但前端 5 个 tab
 * 与既有落库结构已经依赖一批固定字段名（{@code final_score} / {@code trend_score} /
 * {@code momentum_score} / {@code volume_score} / {@code ret20} / {@code ret60} /
 * {@code max_drawdown60}）。若让新引擎直接换一套字段名，就等于同时改前端与库结构——
 * 那不是重构，那是重写。
 *
 * <p>本类只做<b>投影</b>，不做计算、不查库、不写库。它让新引擎的输出成为旧结构的
 * <b>可直接替换来源</b>，从而满足"保留前端入口与原落库结构兼容"这一硬约束。
 *
 * <p><b>映射的语义边界（必须诚实标注）</b>：
 * <ul>
 *   <li>{@code trend_score} / {@code momentum_score} / {@code volume_score} 在旧实现里
 *       是<b>各自独立的子分数</b>（各有自己的绝对阈值与满分）。统一引擎没有这三项子分数，
 *       只有因子百分位与权重贡献。</li>
 *   <li>因此本类把<b>按类别聚合后的加权贡献</b>放进这三个字段：技术类因子归入
 *       {@code trend_score}、动量/反转类归入 {@code momentum_score}、量能/流动性类归入
 *       {@code volume_score}。</li>
 *   <li>这意味着这三个字段在统一口径下的<b>含义与原实现不同</b>：它们是
 *       「该类因子的截面百分位加权贡献（0-100）」，而<b>不是</b>旧口径的绝对子分数。
 *       前端展示时必须在 {@code extra_metrics} 里看到这个区别——
 *       下方写入的 {@code score_semantics} 与 {@code unified_engine} 标记就是为此。</li>
 * </ul>
 *
 * <p>把这件事写清楚，是因为"字段名相同但含义不同"正是最容易被后续维护者误读的地方。
 */
public final class LegacyItemProjector {

    /** 统一口径下三个兼容字段的含义说明，随每条结果一起下发。 */
    public static final String SCORE_SEMANTICS =
            "trend_score/momentum_score/volume_score 为「因子类别加权贡献(0-100 百分位口径)」，"
                    + "与旧实现中同名子分数的绝对阈值口径不同；仅供前端沿用既有列展示。";

    private LegacyItemProjector() {}

    /**
     * 把一条统一分数投影成旧条目。
     *
     * @param score      统一引擎的股票分数（含逐因子贡献）
     * @param definition 该策略定义（用于取权重、因子标签与策略名）
     * @param rankNo     排名序号（1 起）
     */
    public static Map<String, Object> project(
            UnifiedFactorScoringEngine.StockScore score,
            StrategyDefinition definition,
            int rankNo) {

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank_no", rankNo);
        m.put("stock_code", score.stockCode());
        m.put("exchange_type", score.exchangeType());
        m.put("stock_name", score.stockName());
        m.put("strategy", definition.legacyStrategyType());
        m.put("strategy_type", definition.legacyStrategyType());

        // 主分数：final_score 与 total_score 保持一致（旧结构两者并存）
        Double finalScore = score.finalScore();
        double fs = finalScore == null ? 0d : finalScore;
        m.put("final_score", fs);
        m.put("total_score", fs);
        m.put("composite_score", fs);

        // 三个兼容子分数 = 按因子类别聚合的加权贡献
        Map<String, Double> byCategory = categoryContributions(score, definition);
        double trendPart = byCategory.getOrDefault("TREND", 0d);
        double momentumPart = byCategory.getOrDefault("MOMENTUM", 0d);
        double volumePart = byCategory.getOrDefault("VOLUME", 0d);
        m.put("trend_score", trendPart);
        m.put("momentum_score", momentumPart);
        m.put("volume_score", volumePart);
        m.put("risk_penalty", byCategory.getOrDefault("RISK", 0d));

        // 兼容历史字段名（趋势/综合榜用）
        m.put("score_trend", trendPart);
        m.put("score_bias", momentumPart);
        m.put("score_volume", volumePart);
        m.put("score_technical", trendPart);

        // 原始行情指标：统一引擎的 FeatureVector 已算过这些，直接透出。
        // 缺失一律为 null（V30 语义），不得用 0 顶替——0 是真实值，两者必须可区分。
        m.put("ret20", score.ret20());
        m.put("ret60", score.ret60());
        m.put("volatility20", score.volatility20());
        m.put("max_drawdown60", score.maxDrawdown60());
        m.put("avg_amount20", score.avgAmount20());

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("unified_engine", true);
        extra.put("unified_strategy", definition.code());
        extra.put("factor_version", definition.factorVersion());
        extra.put("available_weight_ratio", score.availableWeightRatio());
        extra.put("available_factor_count", score.availableFactorCount());
        extra.put("missing_factor_count", score.unavailableReasons().size());
        // unavailableReasons() 是去重后的原因集合；这里转成带序号的 Map，
        // 前端可以按序号渲染，避免 JSON 里出现无意义的重复数组元素。
        Map<String, Object> unavailable = new LinkedHashMap<>();
        int reasonIdx = 0;
        for (String reason : score.unavailableReasons()) {
            unavailable.put("reason_" + (reasonIdx++), reason);
        }
        extra.put("unavailable_factor_reasons", unavailable);
        extra.put("score_semantics", SCORE_SEMANTICS);
        extra.put("data_quality", score.availableWeightRatio() >= 0.999 ? "FULL" : "PARTIAL_FACTORS");

        List<Map<String, Object>> contributions = new ArrayList<>();
        for (UnifiedFactorScoringEngine.Contribution c : score.contributions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("factor", c.factor().name());
            row.put("label", c.factor().label());
            row.put("raw_value", c.rawValue());
            row.put("percentile", c.percentile());
            row.put("weight", c.weight());
            row.put("contribution", c.contribution());
            row.put("available", c.percentile() != null);
            if (c.unavailableReason() != null) {
                row.put("unavailable_reason", c.unavailableReason());
            }
            contributions.add(row);
        }
        extra.put("factor_contributions", contributions);
        m.put("extra_metrics", extra);

        // 未参与排名的股票也带上拒绝原因，便于"为什么没有它"的排查
        if (score.rejectReason() != null) {
            Map<String, Object> reasons = new LinkedHashMap<>();
            reasons.put("reject_reason", score.rejectReason());
            m.put("reasons", reasons);
        } else {
            m.put("reasons", new LinkedHashMap<String, Object>());
        }
        return m;
    }

    /**
     * 按因子类别聚合加权贡献。
     *
     * <p>分类依据 {@link FactorId} 的语义而非名字前缀，避免后续新增因子时被漏掉：
     * 风险类（波动率/回撤，权重为负）单独一组，其余按技术/动量/量能归组。
     */
    static Map<String, Double> categoryContributions(
            UnifiedFactorScoringEngine.StockScore score,
            StrategyDefinition definition) {

        Map<String, Double> out = new LinkedHashMap<>();
        for (UnifiedFactorScoringEngine.Contribution c : score.contributions()) {
            if (c.contribution() == null) {
                continue;
            }
            String cat = categoryOf(c.factor());
            out.merge(cat, c.contribution(), Double::sum);
        }
        return out;
    }

    private static String categoryOf(FactorId f) {
        return switch (f) {
            case VOLATILITY_20, MAXDD_60 -> "RISK";
            case VOL_RATIO, VOL_TURNOVER, VOL_PRICE, LIQUIDITY_20 -> "VOLUME";
            case MOM_5, MOM_20, MOM_60, MOM_120, REV_5 -> "MOMENTUM";
            // 估值/质量/流动性等基本面因子：旧结构没有对应列，归入"其他"，
            // 不强行塞进 trend/momentum/volume，以免制造虚假的分类含义。
            case VAL_EP, VAL_BP, QUAL_ROE, QUAL_LEV, LIQ_CURRENT -> "OTHER";
            default -> "TREND";
        };
    }
}
