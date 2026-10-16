package com.quant.service.scoring.factor;

import com.quant.service.scoring.CrossSectionFactorMath;
import com.quant.service.scoring.CrossSectionNeutralizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L2 层：把 L1 的原始特征<b>在同一个横截面上</b>标准化为可比的因子分。
 *
 * <p>统一管道（对应方案 §2.2）：
 * <pre>
 *   winsorize(2.5/97.5)          ← 由 CrossSectionNeutralizer.neutralize 内部完成
 *     → 中性化（lnMV + 行业哑变量 OLS，ridge=1e-4）
 *     → robustZ(median + MAD, clip ±3)   ← 用 robustZScoresOnResiduals，避免二次 winsorize
 *     → 百分位排名 [0,100]
 * </pre>
 *
 * <p><b>复用既有实现而非重写</b>：{@link CrossSectionFactorMath} 与
 * {@link CrossSectionNeutralizer} 已经实现了上述全部数学，质量良好，
 * 但当前 5 套打分<b>没有任何一套在用它们</b>（只有 Codex 路径用了 {@code percentileScores}）。
 * 本类的作用就是把它接上。
 *
 * <p>方向处理：百分位天然单调递增，因此「负向因子」（如波动率、资产负债率）通过
 * {@link StrategyDefinition} 里的负权重表达，<b>不在此处翻转</b>——
 * 这样同一个因子的取值在所有策略里完全一致，权重差异才可解释。
 */
public final class CrossSectionNormalizer {

    /** 一个因子在本次截面上的标准化结果。 */
    public record FactorResult(
            FactorId id,
            /** 横截面百分位 [0,100]；{@code null} 表示该股此因子不可用。 */
            List<Double> percentiles,
            /** 可用于本次横截面标准化的股票数（>=2 才可排名）。 */
            int availableCount,
            /** 不可用的股票数。 */
            int unavailableCount,
            /** 本次该因子的中性化模式（见 CrossSectionNeutralizer.MODE_*）。 */
            String neutralizationMode
    ) {}

    /** 整个截面的标准化结果。 */
    public record Result(
            /** 与入参 stocks 同序的因子百分位：stocks[i] 的 factor -> 百分位（可为 null）。 */
            List<Map<FactorId, Double>> factorScores,
            /** 每个因子的元信息。 */
            Map<FactorId, FactorResult> factorResults,
            /**
             * 本次截面的中性化模式。可能值：
             * {@code LOG_MV_ONLY_REGRESSION}（推荐，但需官方市值源）、
             * {@code SKIPPED_MV_RELIABILITY}（当前实际值）、
             * {@code INDUSTRY_DEMEAN_FALLBACK}、{@code PASSTHROUGH}。
             */
            String neutralizationMode,
            /**
             * 行业中性化是否被跳过。由**本次截面实际的行业覆盖**决定，
             * 不再假定"填充率恒为 0%"——那在数据补齐后会变成一句假话。
             */
            boolean industryNeutralizationSkipped,
            String industryNeutralizationSkipReason,
            /**
             * 市值中性化是否被跳过。当前 {@code CrossSectionNeutralizer.isReliableLogMvSource}
             * 恒返回 false，且 {@code market_cap_reliability} 无 OFFICIAL 值，故必然为 {@code true}。
             */
            boolean marketCapNeutralizationSkipped,
            String marketCapNeutralizationSkipReason
    ) {}

    private CrossSectionNormalizer() {}

    /**
     * 无行业数据时的披露文案。
     *
     * <p>带实际观测到的覆盖率：{@code stock_info.industry} 的填充率是**会变的**，
     * 把它写死成"0%"在数据补齐后会误导排障（我曾据此误判中性化没有生效）。
     * 故改成运行时统计，披露真实覆盖情况。
     *
     * @param withIndustry 本次截面中有非空行业的股票数
     * @param total        本次截面股票总数
     */
    public static String skipReasonIndustryMissing(int withIndustry, int total) {
        double pct = total > 0 ? 100.0 * withIndustry / total : 0.0;
        return String.format(
                "INDUSTRY_DATA_MISSING: 本次截面行业覆盖 %d/%d = %.1f%%，行业中性化未生效"
                        + "（仅做了 winsorize + 稳健Z 的横截面标准化）",
                withIndustry, total, pct);
    }

    /**
     * 对一组股票的原始特征做统一横截面标准化。
     *
     * @param features 与 {@code stocks} 同序的特征向量
     * @param factorIds 需要标准化的因子
     */
    public static Result normalize(List<FeatureVector> features, List<FactorId> factorIds) {
        int n = features != null ? features.size() : 0;
        Map<FactorId, FactorResult> meta = new LinkedHashMap<>();
        if (n == 0 || factorIds == null || factorIds.isEmpty()) {
            return new Result(List.of(), meta, CrossSectionNeutralizer.MODE_PASSTHROUGH,
                    false, null, false, null);
        }

        // 行业与市值列（中性化设计矩阵）
        List<String> industries = new ArrayList<>(n);
        List<Double> logMv = new ArrayList<>(n);
        List<String> reliabilities = new ArrayList<>(n);
        boolean anyIndustryPresent = false;
        int industryCount = 0;
        for (FeatureVector f : features) {
            String ind = f != null ? f.industry() : null;
            industries.add(ind != null ? ind : "");
            if (ind != null && !ind.isBlank()) {
                anyIndustryPresent = true;
                industryCount++;
            }
            Double mv = f != null ? f.logMarketCap() : null;
            logMv.add(mv);
            reliabilities.add(f != null ? f.logMarketCapReliability() : null);
        }

        CrossSectionNeutralizer.Context ctx =
                CrossSectionNeutralizer.buildContext(industries, logMv, reliabilities);

        // 「行业中性化被跳过」只由「行业数据本身缺失」决定。
        // 不要把「市值可靠性不足导致整个回归降级为 PASSTHROUGH」（MP-025，见下）也算成行业缺失——
        // 两者是不同原因，混在一起会误导排障。
        boolean industrySkipped = !anyIndustryPresent;
        // 市值中性化：当前 CrossSectionNeutralizer.isReliableLogMvSource 恒返回 false，
        // 且 market_cap_reliability 无 OFFICIAL 值，故必然降级。
        boolean mvSkipped = CrossSectionNeutralizer.MODE_SKIPPED_MV_RELIABILITY.equals(ctx.mode())
                || CrossSectionNeutralizer.MODE_PASSTHROUGH.equals(ctx.mode());

        // 逐因子：抽列 → 中性化（含 winsorize）→ 残差稳健 Z → 百分位
        List<Map<FactorId, Double>> perStock = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            perStock.add(new LinkedHashMap<>());
        }

        String observedMode = ctx.mode();
        for (FactorId id : factorIds) {
            List<Double> raw = new ArrayList<>(n);
            List<Integer> validIdx = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Double v = rawValue(features.get(i), id);
                // 非有限值一律视为不可用，避免污染中位数/MAD
                boolean ok = v != null && !v.isNaN() && !v.isInfinite();
                raw.add(ok ? v : null);
                if (ok) {
                    validIdx.add(i);
                }
            }

            if (validIdx.size() < 2) {
                // 不足 2 个有效值无法排名：如实标为不可用
                meta.put(id, new FactorResult(id, null, validIdx.size(), n - validIdx.size(),
                        "INSUFFICIENT_CROSS_SECTION"));
                continue;
            }

            // 压缩到有效子集做标准化，再放回原位
            List<Double> compact = new ArrayList<>(validIdx.size());
            for (int i : validIdx) {
                compact.add(raw.get(i));
            }
            CrossSectionNeutralizer.Result neu = CrossSectionNeutralizer.neutralize(compact, buildSubContext(ctx, validIdx));
            if (neu.mode() != null && !CrossSectionNeutralizer.MODE_PASSTHROUGH.equals(neu.mode())) {
                observedMode = neu.mode();
            }
            List<Double> z = CrossSectionFactorMath.robustZScoresOnResiduals(neu.residuals());
            List<Double> pct = percentileRanking(z);

            for (int k = 0; k < validIdx.size(); k++) {
                perStock.get(validIdx.get(k)).put(id, pct.get(k));
            }
            meta.put(id, new FactorResult(id, null, validIdx.size(), n - validIdx.size(), neu.mode()));
        }

        return new Result(perStock, meta, observedMode,
                industrySkipped,
                industrySkipped
                        ? skipReasonIndustryMissing(industryCount, n)
                        : null,
                mvSkipped, mvSkipped ? CrossSectionNeutralizer.SKIP_REASON_MARKET_CAP : null);
    }

    /**
     * 子集中性化上下文。
     *
     * <p>为什么需要：某些因子的有效样本是全集子集（例如 120 日动量在只有 100 根 bar 的
     * 股票上不可用）。直接对含 null 的列做回归会得到错误系数，
     * 而 {@code CrossSectionNeutralizer} 要求 {@code values.size() == ctx.n()}。
     * 因此必须按有效索引重建上下文。
     */
    private static CrossSectionNeutralizer.Context buildSubContext(
            CrossSectionNeutralizer.Context parent, List<Integer> validIdx) {
        if (parent.n() == validIdx.size()) {
            return parent;
        }
        List<String> inds = new ArrayList<>(validIdx.size());
        List<Double> logMv = new ArrayList<>(validIdx.size());
        for (int i : validIdx) {
            inds.add(parent.industries().isEmpty() ? "" : parent.industries().get(i));
            logMv.add(parent.logMv().isEmpty() ? null : parent.logMv().get(i));
        }
        // 子集可能因样本变小而触发不同模式，这是正确行为（样本不足就该降级）
        return CrossSectionNeutralizer.buildContext(inds, logMv);
    }

    /**
     * 百分位排名 [0,100]，处理并列（平均秩），并把最低值映射到 0、最高值映射到 100。
     *
     * <p>与 {@link CrossSectionFactorMath#percentileScores} 的差别：本方法接受
     * {@code z} 已全部有限（调用方保证），不做 null 兜底；且不依赖调用方排序。
     */
    static List<Double> percentileRanking(List<Double> values) {
        int n = values.size();
        if (n == 0) {
            return List.of();
        }
        List<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            idx.add(i);
        }
        idx.sort((a, b) -> Double.compare(values.get(a), values.get(b)));
        List<Double> out = new ArrayList<>(Collections.nCopies(n, 0.0));
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && Double.compare(values.get(idx.get(j + 1)), values.get(idx.get(j))) == 0) {
                j++;
            }
            double avgRank = (i + j) / 2.0;
            // n-1 作分母：单只股票时给 50 而非 100，避免「唯一候选得满分」的假象
            double pct = n == 1 ? 50.0 : avgRank / (n - 1) * 100.0;
            for (int k = i; k <= j; k++) {
                out.set(idx.get(k), pct);
            }
            i = j + 1;
        }
        return out;
    }

    /**
     * 原始因子取值。方向不在此处处理。
     *
     * <p>估值因子需要一次变换：{@code VAL_EP = 1/PE}、{@code VAL_BP = 1/PB}，
     * 使「越大越好」成立（PE 越低则 EP 越高）。这一步放在这里而不是
     * {@link FeatureVector}，是为了让特征向量保留原始 PE/PB 便于诊断。
     */
    public static Double rawValue(FeatureVector f, FactorId id) {
        if (f == null) {
            return null;
        }
        return switch (id) {
            case MOM_5 -> f.ret5();
            case MOM_20 -> f.ret20();
            case MOM_60 -> f.ret60();
            case MOM_120 -> f.ret120();
            case REV_5 -> f.ret5() == null ? null : -f.ret5();
            case TREND_MA -> f.trendMaAlignment();
            case TREND_BIAS -> f.bias5();
            case TREND_MACD -> f.macdHistPct();
            case RSI_14 -> f.rsi14();
            case KDJ_J -> f.kdjJ();
            case VOL_RATIO -> f.volumeRatio();
            case VOL_TURNOVER -> f.turnoverRate();
            case VOL_PRICE -> f.volPriceConfirm();
            case LIQUIDITY_20 -> f.avgAmount20();
            case VOLATILITY_20 -> f.volatility20();
            case MAXDD_60 -> f.maxDrawdown60();
            case VAL_EP -> f.peRatio() == null || f.peRatio() <= 0 ? null : 1.0 / f.peRatio();
            case VAL_BP -> f.pbRatio() == null || f.pbRatio() <= 0 ? null : 1.0 / f.pbRatio();
            case QUAL_ROE -> f.roe();
            case QUAL_LEV -> f.debtRatio();
            case LIQ_CURRENT -> f.currentRatio();
        };
    }

    /** 供诊断：统计本次截面里各因子的可用率。 */
    public static Map<FactorId, Double> availabilityRates(Result result) {
        Map<FactorId, Double> out = new LinkedHashMap<>();
        for (Map.Entry<FactorId, FactorResult> e : result.factorResults().entrySet()) {
            FactorResult fr = e.getValue();
            int total = fr.availableCount() + fr.unavailableCount();
            out.put(e.getKey(), total == 0 ? 0d : (double) fr.availableCount() / total);
        }
        return out;
    }

    /** 不可用因子的去重原因集合（供输出披露）。 */
    public static Set<String> unavailableModes(Result result) {
        Set<String> modes = new LinkedHashSet<>();
        for (FactorResult fr : result.factorResults().values()) {
            if (fr.neutralizationMode() != null) {
                modes.add(fr.neutralizationMode());
            }
        }
        return modes;
    }
}
