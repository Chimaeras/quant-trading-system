package com.quant.service.backtest;

import com.quant.research.ResearchConvention;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 统计量构造约定与成本口径的守卫（Q-R003-01 / Q-R004-01 / Q-R006-02）。
 *
 * <p>三点被本测试钉死：
 * <ol>
 *   <li><b>ICIR 分母</b>：主口径是总体标准差 ÷T（未改）；并列键用样本标准差 ÷(T−1)，
 *       两者比值恒为 {@code sqrt(T/(T−1))}——这个恒等式就是「另一种读法」的可复核入口。</li>
 *   <li><b>单腿往返成本</b>：必须是**乘性复合**（买价 ×(1+滑点)×(1+佣金)，卖价 ×(1−滑点)×(1−佣金−印花税)），
 *       不是把三档相加。加性读法漏掉卖出腿佣金，主项差 −3.00bp。</li>
 *   <li><b>净/毛互逆</b>：{@code grossSpreadFromNet} 是 {@code dollarNeutralSpread} 的逆运算，
 *       使「毛收益面」与「成本后近似净收益面」的同名价差可以对齐到同一把尺子。</li>
 * </ol>
 */
class FactorResearchEngineCostBasisTest {

    private static final double COMMISSION = ResearchConvention.DEFAULT_COMMISSION_RATE;
    private static final double SELL_TAX = ResearchConvention.DEFAULT_SELL_TAX_RATE;
    private static final double SLIPPAGE = ResearchConvention.DEFAULT_SLIPPAGE_RATE;

    // ---- 样本标准差（÷(T−1)）与恒等式 ----

    @Test
    void sampleStdUsesTMinusOneDenominator() {
        // 1..5：总体标准差 = sqrt(2) ≈ 1.4142136；样本标准差 = sqrt(2.5) ≈ 1.5811388
        List<Double> values = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        Double sample = FactorResearchEngine.sampleStdDivTMinusOne(values);

        assertThat(sample).isNotNull();
        assertThat(sample).isCloseTo(Math.sqrt(2.5), within(1e-12));
        assertThat(sample).isGreaterThan(Math.sqrt(2.0));
    }

    @Test
    void sampleStdIsUndefinedForFewerThanTwoObservationsOrNonFiniteInput() {
        assertThat(FactorResearchEngine.sampleStdDivTMinusOne(List.of(1.0))).isNull();
        assertThat(FactorResearchEngine.sampleStdDivTMinusOne(List.of())).isNull();
        assertThat(FactorResearchEngine.sampleStdDivTMinusOne(null)).isNull();
        List<Double> withNaN = new ArrayList<>(List.of(1.0, 2.0));
        withNaN.add(Double.NaN);
        assertThat(FactorResearchEngine.sampleStdDivTMinusOne(withNaN)).isNull();
        List<Double> withNull = new ArrayList<>(List.of(1.0, 2.0));
        withNull.add(null);
        assertThat(FactorResearchEngine.sampleStdDivTMinusOne(withNull)).isNull();
    }

    /** 两种分母约定的比值恒为 sqrt(T/(T−1))（T=5 时约 +11.8%）——「另一种读法」不是猜测，是可验证的恒等式。 */
    @Test
    void populationAndSampleStdDifferByTheSqrtTOverTMinusOneFactor() {
        List<Double> values = List.of(-0.02, 0.01, 0.03, -0.005, 0.012);
        int t = values.size();

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double sumSq = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
        double populationStd = Math.sqrt(sumSq / t);

        Double sampleStd = FactorResearchEngine.sampleStdDivTMinusOne(values);
        assertThat(sampleStd).isNotNull();
        assertThat(sampleStd / populationStd).isCloseTo(Math.sqrt((double) t / (t - 1)), within(1e-12));
        assertThat(sampleStd / populationStd).isCloseTo(1.1180, within(1e-4));
    }

    // ---- 单腿往返成本：乘性 vs 加性 ----

    @Test
    void perLegRoundTripCostIsMultiplicativeNotAdditive() {
        double perLeg = FactorResearchEngine.roundTripCostPerLeg(COMMISSION, SELL_TAX, SLIPPAGE);
        double netFactor = (1 - SLIPPAGE) * (1 - COMMISSION - SELL_TAX) / ((1 + SLIPPAGE) * (1 + COMMISSION));

        assertThat(perLeg).isCloseTo(1 - netFactor, within(1e-15));
        // 约 15.0bp/腿（两条腿 ≈ 30bp），不是把三档相加得到的 10bp 或 24bp
        assertThat(perLeg * 10000).isCloseTo(15.0, within(0.1));
        assertThat(perLeg).isGreaterThan(COMMISSION + SELL_TAX + 2 * SLIPPAGE);
    }

    /** 加性读法漏掉卖出腿佣金：差值主项恰为 −c（3.00bp），这正是文档勘误里那条被漏掉的项。 */
    @Test
    void additiveReadingOmitsTheSellLegCommissionByMainTerm() {
        double perLeg = FactorResearchEngine.roundTripCostPerLeg(COMMISSION, SELL_TAX, SLIPPAGE);
        // 「买入佣金+卖出印花税+双边滑点」这种加性读法
        double additive = COMMISSION + SELL_TAX + 2 * SLIPPAGE;

        double difference = additive - perLeg;
        // 主项为 −c（加性读法少了卖出腿的 3.00bp 佣金），残余项来自乘性交互（滑点×费率），量级 1e-2 bp 以下
        assertThat(difference).isCloseTo(-COMMISSION, within(1e-6));
    }

    @Test
    void perLegCostIsUndefinedForInvalidInputs() {
        assertThat(FactorResearchEngine.roundTripCostPerLeg(-0.001, SELL_TAX, SLIPPAGE)).isNaN();
        assertThat(FactorResearchEngine.roundTripCostPerLeg(COMMISSION, Double.NaN, SLIPPAGE)).isNaN();
    }

    // ---- 毛/净价差的互逆 ----

    @Test
    void neutralSpreadSubtractsBothLegsOfCost() {
        double perLeg = FactorResearchEngine.roundTripCostPerLeg(COMMISSION, SELL_TAX, SLIPPAGE);
        Double neutral = FactorResearchEngine.dollarNeutralSpread(0.05, perLeg);

        assertThat(neutral).isNotNull();
        assertThat(neutral).isCloseTo(0.05 - 2 * perLeg, within(1e-15));
        // 与「直接读成 24bp/2 腿」的差别：本口径两条腿约 30bp，比加性读法多约 6bp
        assertThat(0.05 - neutral).isCloseTo(0.0030, within(0.0002));
    }

    @Test
    void neutralSpreadIsUndefinedWithoutAGrossSpread() {
        double perLeg = FactorResearchEngine.roundTripCostPerLeg(COMMISSION, SELL_TAX, SLIPPAGE);
        assertThat(FactorResearchEngine.dollarNeutralSpread(null, perLeg)).isNull();
        assertThat(FactorResearchEngine.dollarNeutralSpread(Double.NaN, perLeg)).isNull();
        assertThat(FactorResearchEngine.dollarNeutralSpread(0.05, Double.NaN)).isNull();
    }

    /**
     * <b>两个成本模型不是互逆关系</b>——这是本组断言的核心：
     * <ul>
     *   <li>价格模型（回测执行面实际用法）：{@code 净 = 毛 × (1 − c)}，故 {@code grossSpreadFromNet} 精确还原；</li>
     *   <li>美元中性惯例（面 B 折算用）：{@code 中性净 = 毛 − 2c}，把成本当作对**名义本金**的扣减。</li>
     * </ul>
     * 两者在毛价差 5%、c≈15bp 时相差约 2.9bp（= c·|2 − 毛价差|）。任何一个公式都不覆盖另一个，
     * 载荷因此各自声明基准，而不是把两者当成同一个数的两种写法。
     */
    @Test
    void grossSpreadFromNetIsTheInverseOfThePriceModelNotOfTheDollarNeutralConvention() {
        double perLeg = FactorResearchEngine.roundTripCostPerLeg(COMMISSION, SELL_TAX, SLIPPAGE);
        double gross = 0.05;

        // 价格模型：净 = 毛 × (1 − c)，除法精确还原（1e-15 级）
        double priceModelNet = gross * (1 - perLeg);
        assertThat(FactorResearchEngine.grossSpreadFromNet(priceModelNet, perLeg))
                .isCloseTo(gross, within(1e-15));

        // 美元中性惯例：−2c。对它做除法**不能**还原毛价差，缺口闭式 = c(2 − G)/(1 − c)（此处约 2.93bp）
        Double dollarNeutral = FactorResearchEngine.dollarNeutralSpread(gross, perLeg);
        Double wrongRecovery = FactorResearchEngine.grossSpreadFromNet(dollarNeutral, perLeg);
        assertThat(wrongRecovery).isNotNull();
        assertThat(wrongRecovery).isNotCloseTo(gross, within(1e-6));
        assertThat(gross - wrongRecovery).isCloseTo(perLeg * (2 - gross) / (1 - perLeg), within(1e-15));
        assertThat(gross - wrongRecovery).isCloseTo(0.00293, within(1e-4));

        // 两个模型的缺口：毛−2c 与 毛×(1−c) 相差 c(2 − G)（此处约 2.92bp）
        assertThat(dollarNeutral).isCloseTo(priceModelNet - perLeg * (2 - gross), within(1e-15));
    }

    @Test
    void grossEquivalentIsUndefinedWhenItCannotBeInverted() {
        assertThat(FactorResearchEngine.grossSpreadFromNet(null, 0.0015)).isNull();
        assertThat(FactorResearchEngine.grossSpreadFromNet(0.01, Double.NaN)).isNull();
        assertThat(FactorResearchEngine.grossSpreadFromNet(0.01, -0.001)).isNull();
        // 单腿成本 = 1（或 >1）意味着 1 − c ≤ 0，商无意义
        assertThat(FactorResearchEngine.grossSpreadFromNet(0.01, 1.0)).isNull();
        assertThat(FactorResearchEngine.grossSpreadFromNet(0.01, 1.5)).isNull();
    }
}
