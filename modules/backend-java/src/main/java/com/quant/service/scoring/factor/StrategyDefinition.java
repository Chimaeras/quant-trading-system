package com.quant.service.scoring.factor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L3 层：一个策略的<b>声明式配置</b>——只用「哪些因子 + 什么权重 + 什么闸门」描述，
 * 不含任何计算逻辑。
 *
 * <p>这是"5 套打分"重构后的形态：原先每套策略各有一份独立的打分代码
 * （{@code StockTrendScoringService} 12 个子项、{@code ComprehensiveScoringService}
 * 30/40/30 归一化、{@code MultiDimScoringService} 5 维度、{@code ClassicFactorCalculator}
 * 0.35/0.20/0.20/-0.25、{@code OversoldReboundScoringService} 40/30/30 加确认分），
 * 阈值与权重各写一遍；现在这些差异全部收敛到本类的配置值。
 *
 * <p>权重约定：
 * <ul>
 *   <li>正的权重 = 因子越大越好；负的权重 = 因子越大越差（如波动率、回撤、资产负债率、
 *       乖离在高位）。方向<b>不由因子层翻转</b>，这样同一因子在所有策略里取值一致。</li>
 *   <li>权重会以 <b>绝对值之和</b> 归一化，故各策略内部权重之和为 1
 *       （消除当前「MULTI_DIM 预归一化权重和 1.10」这类不一致）。</li>
 * </ul>
 */
public final class StrategyDefinition {

    /** 缺数据容忍策略。 */
    public enum MissingDataPolicy {
        /**
         * 保守：任一「必需因子」不可用则整只股票不计分。
         * 当前本库 PE/PB/财务全缺，若把估值因子设为必需则无任何股票可计分。
         */
        REQUIRE_ALL_WEIGHTED_FACTORS,
        /**
         * 允许部分因子缺失：只用可用的因子、权重就地重新归一化，并在输出里记录缺了什么。
         * <b>不</b>用中性值填补缺失因子（那正是"拿常数打分"的来源）。
         * 同时要求可用权重占比不低于 {@link #minAvailableWeightRatio()}，否则该股不计分。
         */
        RE_NORMALIZE_OVER_AVAILABLE
    }

    private final String code;
    private final String displayName;
    private final String factorVersion;
    private final Map<FactorId, Double> weights;
    private final int minBars;
    private final double minAvgAmount;
    private final int minListingDays;
    private final MissingDataPolicy missingDataPolicy;
    private final double minAvailableWeightRatio;
    private final boolean requireMa20AboveMa60;
    private final List<FactorId> requiredFactors;
    private final String legacyStrategyType;
    private final String legacyUiMode;

    private StrategyDefinition(Builder b) {
        this.code = b.code;
        this.displayName = b.displayName;
        this.factorVersion = b.factorVersion;
        this.weights = Collections.unmodifiableMap(new LinkedHashMap<>(b.weights));
        this.minBars = b.minBars;
        this.minAvgAmount = b.minAvgAmount;
        this.minListingDays = b.minListingDays;
        this.missingDataPolicy = b.missingDataPolicy;
        this.minAvailableWeightRatio = b.minAvailableWeightRatio;
        this.requireMa20AboveMa60 = b.requireMa20AboveMa60;
        this.requiredFactors = List.copyOf(b.requiredFactors);
        this.legacyStrategyType = b.legacyStrategyType;
        this.legacyUiMode = b.legacyUiMode;
        if (this.weights.isEmpty()) {
            throw new IllegalArgumentException("策略 " + code + " 未声明任何因子权重");
        }
    }

    public String code() { return code; }
    public String displayName() { return displayName; }
    public String factorVersion() { return factorVersion; }
    public Map<FactorId, Double> weights() { return weights; }
    public int minBars() { return minBars; }
    public double minAvgAmount() { return minAvgAmount; }
    public int minListingDays() { return minListingDays; }
    public MissingDataPolicy missingDataPolicy() { return missingDataPolicy; }
    public double minAvailableWeightRatio() { return minAvailableWeightRatio; }
    public boolean requireMa20AboveMa60() { return requireMa20AboveMa60; }
    public List<FactorId> requiredFactors() { return requiredFactors; }
    /** 与既有 {@code selector_result.strategy_type} 兼容的枚举名。 */
    public String legacyStrategyType() { return legacyStrategyType; }

    /**
     * 前端 {@code StockPicker.vue} 的 tab 键（classic / oversold / trend / comprehensive / multi_dim）。
     *
     * <p>把它放进目录，是为了让「有哪些策略、各自的展示名、对应哪个 UI 入口」
     * 只有<b>一处</b>定义。此前前端 {@code currentStrategy()} 自己硬编码了这张映射表，
     * 与后端目录各写一份——加策略或改策略名时必然漂移。
     *
     * <p>返回 {@code null} 表示该策略尚无 UI 入口（后端存在但前端未暴露）。
     */
    public String legacyUiMode() { return legacyUiMode; }

    /** 权重绝对值之和，用于归一化。 */
    public double totalAbsWeight() {
        double s = 0;
        for (double w : weights.values()) {
            s += Math.abs(w);
        }
        return s;
    }

    /** 归一化后的权重（绝对值之和 = 1）。 */
    public Map<FactorId, Double> normalizedWeights() {
        double total = totalAbsWeight();
        Map<FactorId, Double> out = new LinkedHashMap<>();
        for (Map.Entry<FactorId, Double> e : weights.entrySet()) {
            out.put(e.getKey(), total == 0 ? 0d : e.getValue() / total);
        }
        return out;
    }

    /** 归一化权重里，非基本面因子的权重占比。用于缺数据披露。 */
    public double nonFundamentalWeightShare() {
        double total = totalAbsWeight();
        if (total == 0) {
            return 0d;
        }
        double nonFund = 0;
        for (Map.Entry<FactorId, Double> e : weights.entrySet()) {
            if (!e.getKey().isFundamental()) {
                nonFund += Math.abs(e.getValue());
            }
        }
        return nonFund / total;
    }

    public static Builder builder(String code, String displayName, String factorVersion) {
        return new Builder(code, displayName, factorVersion);
    }

    public static final class Builder {
        private final String code;
        private final String displayName;
        private final String factorVersion;
        private final Map<FactorId, Double> weights = new LinkedHashMap<>();
        private int minBars = 260;
        private double minAvgAmount = 30000d;
        private int minListingDays = 60;
        private MissingDataPolicy missingDataPolicy = MissingDataPolicy.RE_NORMALIZE_OVER_AVAILABLE;
        private double minAvailableWeightRatio = 0.5;
        private boolean requireMa20AboveMa60 = false;
        private final List<FactorId> requiredFactors = new ArrayList<>();
        private String legacyStrategyType;
        private String legacyUiMode;

        Builder(String code, String displayName, String factorVersion) {
            this.code = code;
            this.displayName = displayName;
            this.factorVersion = factorVersion;
            this.legacyStrategyType = code;
        }

        public Builder weight(FactorId id, double w) {
            weights.put(id, w);
            return this;
        }

        public Builder minBars(int v) { this.minBars = v; return this; }
        public Builder minAvgAmount(double v) { this.minAvgAmount = v; return this; }
        public Builder minListingDays(int v) { this.minListingDays = v; return this; }
        public Builder missingDataPolicy(MissingDataPolicy v) { this.missingDataPolicy = v; return this; }
        public Builder minAvailableWeightRatio(double v) { this.minAvailableWeightRatio = v; return this; }
        public Builder requireMa20AboveMa60() { this.requireMa20AboveMa60 = true; return this; }
        public Builder require(FactorId... ids) {
            Collections.addAll(requiredFactors, ids);
            return this;
        }
        public Builder legacyStrategyType(String v) { this.legacyStrategyType = v; return this; }
        public Builder legacyUiMode(String v) { this.legacyUiMode = v; return this; }

        public StrategyDefinition build() {
            return new StrategyDefinition(this);
        }
    }
}
