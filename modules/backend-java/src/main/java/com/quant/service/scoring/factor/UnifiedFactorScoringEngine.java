package com.quant.service.scoring.factor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L4 层：统一因子打分引擎。
 *
 * <p>把 {@link FeatureVector}（L1）+ {@link CrossSectionNormalizer}（L2）+
 * {@link StrategyDefinition}（L3）组装成分数，并产出<b>逐因子归因</b>。
 *
 * <p>输出契约（对应方案 §2.2 的 L4）：
 * <ul>
 *   <li>{@code factorScore} = Σ(wᵢ · pctᵢ) / Σ|wᵢ|（仅对可用因子求和）——横截面可比。</li>
 *   <li>{@code finalScore} = 对 {@code factorScore} 再做<b>横截面百分位排名 [0,100]</b>。
 *       为什么还要排名：加权求和的结果尺度依赖权重分布，且极端组合会挤压中段；
 *       分位使「同一策略在不同日期的分数」以及「不同策略的分数」都落在同一尺度上，
 *       这正是当前 5 套策略（中位数 42.56 / 67.17 / 56.36 / 66.12 / 56.15）所缺失的。</li>
 *   <li>{@code contributions} = 每个因子的 (原始值, 百分位, 权重, 贡献分)，使分数<b>可归因</b>。</li>
 * </ul>
 *
 * <p>缺失数据：按 {@link StrategyDefinition.MissingDataPolicy} 处理，
 * <b>绝不用中性值填补</b>。可用权重占比低于阈值时该股标记为不合格并说明原因。
 */
public final class UnifiedFactorScoringEngine {

    private UnifiedFactorScoringEngine() {}

    /** 单只股票的因子贡献。 */
    public record Contribution(
            FactorId factor,
            /** L1 原始取值（可为 null，表示不可用）。 */
            Double rawValue,
            /** L2 横截面百分位 [0,100]；null 表示不可用。 */
            Double percentile,
            /** 归一化权重（含符号）。 */
            double weight,
            /** 该因子对最终得分的贡献 = weight × percentile（百分位量纲）。 */
            Double contribution,
            /** 不可用原因；null 表示可用。 */
            String unavailableReason
    ) {}

    /** 单只股票的评分结果。 */
    public record StockScore(
            String stockCode,
            String exchangeType,
            String stockName,
            /** 加权求和（Σw·pct / Σ|w|），仅对可用因子求和；量纲 [0,100]。 */
            double factorScore,
            /** 横截面百分位排名 [0,100]；未参与排名时为 null。 */
            Double finalScore,
            /** 可用权重的绝对值之和 / 全部权重的绝对值之和。 */
            double availableWeightRatio,
            /** 是否通过资格闸（最小 bar / 流动性 / 必需因子 / 可用权重占比）。 */
            boolean eligible,
            /** 不合格原因（eligible=false 时非空）。 */
            String rejectReason,
            List<Contribution> contributions,
            /**
             * 原始行情指标，直接取自 L1 {@link FeatureVector}。
             *
             * <p>放进评分结果是为了让 {@link LegacyItemProjector} 能把既有
             * {@code selector_result_item} 的 {@code ret20/ret60/volatility20/max_drawdown60/avg_amount20}
             * 列填上真实值。这些字段是<b>既有落库结构的兼容要求</b>，
             * 而不是本引擎的评分输入——引擎评分只用百分位。
             *
             * <p>缺失一律为 {@code null}（「未计算」），绝不用 0 顶替。
             */
            Double ret20,
            Double ret60,
            Double volatility20,
            Double maxDrawdown60,
            Double avgAmount20
    ) {
        /** 本次评分实际用到的因子数。 */
        public int availableFactorCount() {
            int c = 0;
            for (Contribution x : contributions) {
                if (x.percentile() != null) {
                    c++;
                }
            }
            return c;
        }

        /** 不可用因子的去重原因。 */
        public Set<String> unavailableReasons() {
            Set<String> s = new LinkedHashSet<>();
            for (Contribution x : contributions) {
                if (x.unavailableReason() != null) {
                    s.add(x.unavailableReason());
                }
            }
            return s;
        }
    }

    /** 整个截面的评分结果。 */
    public record Result(
            String strategyCode,
            String factorVersion,
            List<StockScore> scores,
            /** L2 的中性化模式与跳过披露。 */
            CrossSectionNormalizer.Result normalization,
            /** 参与最终排名的股票数（= 合格股票数）。 */
            int rankedCount,
            /** 因资格闸被剔除的股票数。 */
            int rejectedCount,
            /** 因策略权重全部不可用而无法打分的股票数。 */
            int unscoredCount,
            /** 按原因聚合的剔除分布。 */
            Map<String, Integer> rejectionBreakdown,
            Map<FactorId, Double> factorAvailabilityRates
    ) {}

    /**
     * 执行一次统一打分。
     *
     * @param strategy 策略声明
     * @param features 本次截面的特征向量（顺序任意，但需与调用方的股票列表一致）
     */
    public static Result score(StrategyDefinition strategy, List<FeatureVector> features) {
        List<FeatureVector> fvs = features != null ? features : List.of();
        List<FactorId> factorIds = new ArrayList<>(strategy.weights().keySet());
        Map<FactorId, Double> normWeights = strategy.normalizedWeights();

        CrossSectionNormalizer.Result norm =
                CrossSectionNormalizer.normalize(fvs, factorIds);

        List<StockScore> scores = new ArrayList<>(fvs.size());
        Map<String, Integer> rejections = new LinkedHashMap<>();
        int rejected = 0;
        int unscored = 0;

        for (int i = 0; i < fvs.size(); i++) {
            FeatureVector fv = fvs.get(i);
            Map<FactorId, Double> pcts = norm.factorScores().get(i);
            Map<FactorId, String> unavail = new LinkedHashMap<>();

            // 先算逐因子贡献（不依赖闸门结果），使「被拒绝」的股票也带完整诊断——
            // 否则排障时只能看到"被拒"，看不到"为什么数据不足"。
            double weightedSum = 0d;
            double availableAbs = 0d;
            double totalAbs = strategy.totalAbsWeight();
            for (FactorId id : factorIds) {
                double w = normWeights.getOrDefault(id, 0d);
                Double raw = CrossSectionNormalizer.rawValue(fv, id);
                Double pct = pcts != null ? pcts.get(id) : null;
                if (pct == null) {
                    unavail.put(id, reasonOf(raw, fv, id));
                } else {
                    availableAbs += Math.abs(strategy.weights().getOrDefault(id, 0d));
                    weightedSum += w * pct;
                }
            }
            double availableRatio = totalAbs == 0 ? 0d : availableAbs / totalAbs;
            double denom = 0d;
            for (FactorId id : factorIds) {
                if (pcts != null && pcts.get(id) != null) {
                    denom += Math.abs(normWeights.getOrDefault(id, 0d));
                }
            }
            double factorScore = denom == 0d ? 0d : weightedSum / denom;

            // 依次判定：资格闸 → 必需因子 → 可用权重占比 → 是否完全无可用因子
            String reason = checkGates(strategy, fv);
            if (reason == null) {
                for (FactorId req : strategy.requiredFactors()) {
                    if (pcts == null || pcts.get(req) == null) {
                        reason = "REQUIRED_FACTOR_UNAVAILABLE:" + req.name();
                        break;
                    }
                }
            }
            if (reason == null && availableRatio < strategy.minAvailableWeightRatio()) {
                reason = "INSUFFICIENT_AVAILABLE_WEIGHT:" + round4(availableRatio);
            }
            if (reason == null && availableAbs == 0d) {
                unscored++;
                reason = "ALL_WEIGHTED_FACTORS_UNAVAILABLE";
            }

            boolean eligible = reason == null;
            if (!eligible) {
                rejections.merge(reason, 1, Integer::sum);
                rejected++;
            }
            scores.add(build(fv, strategy, normWeights, pcts, unavail, eligible, reason,
                    availableRatio, factorScore));
        }

        // ── 最终分数 = factorScore 的横截面百分位排名 ─────────────────────
        List<Double> eligibleScores = new ArrayList<>();
        // 索引 -> 在 eligibleScores 中的位置（避免 O(n²) 的 indexOf）
        Map<Integer, Integer> positionOf = new LinkedHashMap<>();
        for (int i = 0; i < scores.size(); i++) {
            StockScore s = scores.get(i);
            if (s.eligible()) {
                positionOf.put(i, eligibleScores.size());
                eligibleScores.add(s.factorScore());
            }
        }
        List<Double> ranked = CrossSectionNormalizer.percentileRanking(eligibleScores);
        List<StockScore> finalScores = new ArrayList<>(scores.size());
        for (int i = 0; i < scores.size(); i++) {
            StockScore s = scores.get(i);
            Integer pos = positionOf.get(i);
            Double finalScore = (pos != null && pos < ranked.size()) ? round4(ranked.get(pos)) : null;
            finalScores.add(new StockScore(
                    s.stockCode(), s.exchangeType(), s.stockName(),
                    round4(s.factorScore()), finalScore,
                    round4(s.availableWeightRatio()), s.eligible(), s.rejectReason(), s.contributions(),
                    s.ret20(), s.ret60(), s.volatility20(), s.maxDrawdown60(), s.avgAmount20()));
        }

        return new Result(strategy.code(), strategy.factorVersion(), finalScores, norm,
                eligibleScores.size(), rejected, unscored, rejections,
                CrossSectionNormalizer.availabilityRates(norm));
    }

    // ── 资格闸 ────────────────────────────────────────────────────────

    /**
     * 资格闸。只做「数据是否足以打分」的判断，
     * <b>不含</b>可交易性（ST/涨跌停/停牌）——那由 {@code ScoringUtils.evaluateTradabilityStrict}
     * 在更外层、拿得到价格与状态时判断。
     */
    private static String checkGates(StrategyDefinition strategy, FeatureVector fv) {
        if (fv == null) {
            return "NULL_FEATURE_VECTOR";
        }
        if (fv.barCount() < strategy.minBars()) {
            return "INSUFFICIENT_BARS:" + fv.barCount() + "<" + strategy.minBars();
        }
        if (strategy.requireMa20AboveMa60()) {
            Double ma20 = fv.ma20();
            Double ma60 = fv.ma60();
            if (ma20 == null || ma60 == null || ma20 <= ma60) {
                return "MA20_NOT_ABOVE_MA60";
            }
        }
        if (strategy.minAvgAmount() > 0) {
            Double amt = fv.avgAmount20();
            if (amt == null || amt < strategy.minAvgAmount()) {
                return "BELOW_MIN_AVG_AMOUNT";
            }
        }
        return null;
    }

    private static String reasonOf(Double raw, FeatureVector fv, FactorId id) {
        if (raw == null) {
            // 估值/财务类因子的缺失是本库结构性的，给出精确原因而不是笼统的 UNKNOWN
            if (id.isFundamental()) {
                return switch (id) {
                    case VAL_EP -> fv.peRatio() == null ? "NULL_PE_RATIO" : "NON_POSITIVE_PE";
                    case VAL_BP -> fv.pbRatio() == null ? "NULL_PB_RATIO" : "NON_POSITIVE_PB";
                    default -> "NO_FINANCIAL_SNAPSHOT_SOURCE";
                };
            }
            return "FEATURE_UNAVAILABLE";
        }
        return "NOT_RANKABLE_IN_CROSS_SECTION";
    }

    private static StockScore build(FeatureVector fv, StrategyDefinition strategy,
                                    Map<FactorId, Double> normWeights,
                                    Map<FactorId, Double> pcts,
                                    Map<FactorId, String> unavail,
                                    boolean eligible, String rejectReason,
                                    double availableRatio, Double factorScore) {
        List<Contribution> contributions = new ArrayList<>();
        for (FactorId id : strategy.weights().keySet()) {
            double w = normWeights.getOrDefault(id, 0d);
            Double raw = CrossSectionNormalizer.rawValue(fv, id);
            Double pct = pcts != null ? pcts.get(id) : null;
            Double contrib = pct == null ? null : round4(w * pct);
            contributions.add(new Contribution(id, raw, pct == null ? null : round4(pct), round4(w),
                    contrib, pct == null ? unavail.getOrDefault(id, "FEATURE_UNAVAILABLE") : null));
        }
        return new StockScore(
                fv != null ? fv.stockCode() : null,
                fv != null ? fv.exchangeType() : null,
                fv != null ? fv.stockName() : null,
                factorScore != null ? factorScore : 0d,
                null,
                availableRatio,
                eligible,
                rejectReason,
                Collections.unmodifiableList(contributions),
                fv != null ? fv.ret20() : null,
                fv != null ? fv.ret60() : null,
                fv != null ? fv.volatility20() : null,
                fv != null ? fv.maxDrawdown60() : null,
                fv != null ? fv.avgAmount20() : null);
    }

    /** 归因校验：Σ贡献 应等于 factorScore（容差 0.01）。 */
    public static boolean verifyAttribution(StockScore s, double tolerance) {
        if (!s.eligible() || s.contributions().isEmpty()) {
            return true;
        }
        double denom = 0d;
        double sum = 0d;
        for (Contribution c : s.contributions()) {
            if (c.percentile() != null) {
                sum += c.weight() * c.percentile();
                denom += Math.abs(c.weight());
            }
        }
        if (denom == 0d) {
            return true;
        }
        return Math.abs(sum / denom - s.factorScore()) <= tolerance;
    }

    private static double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0d;
        }
        return Math.round(v * 10000d) / 10000d;
    }
}
