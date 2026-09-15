package com.quant.service.scoring;

import com.quant.entity.SelectorResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流动性闸的单位守卫（D-R001-01 / D-R005-03）。
 *
 * <p>本测试防的是一类**静默**错误：{@code minAvgAmount} 的比较基准（{@code market_data.amount}）
 * 单位是**万元**，而历史默认值曾被写成 {@code 30_000_000}（把万元当元用）。两者相差 1000×，
 * 表现只是「筛不出票」，不抛异常、不报错。此处用三层守卫锁定：
 * <ol>
 *   <li>单位声明的**算术复核**：由原始三元组 (amount, volume, close_price) 可反推单位量级；</li>
 *   <li>默认常量与实体默认值、阈值调用面**同源**（常量被改而实体没改会立刻失败）；</li>
 *   <li>量级诊断：阈值高出候选中位数 ≥100× 时给出机器可读的错配标记（而不是静默过滤）。</li>
 * </ol>
 */
class ScoringUtilsMinAvgAmountTest {

    /** 活库实测行（贵州茅台 600519，2026-06-15，万元口径下的量级见下方算术）。 */
    private static final double OBSERVED_AMOUNT = 530_365.0;
    private static final double OBSERVED_VOLUME_HAND = 42_000.0;
    private static final double OBSERVED_CLOSE = 1_262.0;

    /**
     * 单位声明的算术复核：{@code amount / (volume × close_price)} 在「volume 以手（100 股）计」时，
     * 若 amount 与 volume×close 同单位则比值应为 100；观测到的比值应为 100 ± 量级误差。
     *
     * <p>本测试只用固定行做**量纲自洽性**检查（同一行的三个数来自同一次查询），
     * 不声称覆盖全部行情；它防的是「有人把 amount 当成元写进阈值」这一类回归。
     */
    @Test
    void amountColumnIsInTenThousandCnyNotCny() {
        double shares = OBSERVED_VOLUME_HAND * 100.0;
        double cnyTurnover = shares * OBSERVED_CLOSE;
        double impliedUnitScale = cnyTurnover / OBSERVED_AMOUNT;

        // 今日成交额（元）/ amount 列值 ≈ 1e4 ⟹ amount 列为万元（若为元则 ≈1，为千元则 ≈10）
        assertThat(impliedUnitScale).isBetween(9_000.0, 11_000.0);
        assertThat(ScoringUtils.AMOUNT_UNIT).isEqualTo("TEN_THOUSAND_CNY");
        assertThat(ScoringUtils.MIN_AVG_AMOUNT_UNIT).isEqualTo(ScoringUtils.AMOUNT_UNIT);
    }

    /**
     * 默认阈值必须是「同单位下的可满足值」：3e4 万元 = 3 亿元/日，落在实测候选量级（1e4–6e5 万元）之内；
     * 而 3e7（原值）比实测上界还高约 50×，任何候选都不可能通过。
     */
    @Test
    void defaultFloorIsReachableUnderTheTenThousandCnyConvention() {
        assertThat(ScoringUtils.DEFAULT_MIN_AVG_AMOUNT).isEqualTo(30_000d);
        // 与实测候选量级同档（同一把尺子）：量级诊断必须在「阈值 = 默认值 + 实测量级样本」下判定为一致
        List<Double> realistic = List.of(1.2e4, 3.0e4, 8.16e4, 2.5e5, 6.41e5);
        assertThat(ScoringUtils.liquidityGateMagnitudeDiagnosis(realistic, ScoringUtils.DEFAULT_MIN_AVG_AMOUNT))
                .isEqualTo(ScoringUtils.LIQUIDITY_GATE_CONSISTENT);
    }

    /** 实体默认值必须与常量一致（实体不能依赖 service 包，故写字面量——此断言就是那道锁）。 */
    @Test
    void selectorResultDefaultMinAvgAmountMatchesTheConstant() {
        assertThat(new SelectorResult().getMinAvgAmount()).isEqualTo(ScoringUtils.DEFAULT_MIN_AVG_AMOUNT);
    }

    /** 原缺陷形态：把万元当元用（3e7）时，诊断必须报错配而不是静默过滤。 */
    @Test
    void thousandFoldTooHighThresholdIsFlaggedAsSuspectedUnitMismatch() {
        List<Double> realistic = new ArrayList<>(List.of(1.2e4, 3.0e4, 8.16e4, 2.5e5, 6.41e5));
        assertThat(ScoringUtils.liquidityGateMagnitudeDiagnosis(realistic, 30_000_000d))
                .isEqualTo(ScoringUtils.LIQUIDITY_GATE_SUSPECTED_UNIT_MISMATCH);
    }

    @Test
    void smallOrMissingSamplesYieldNoMagnitudeJudgement() {
        assertThat(ScoringUtils.liquidityGateMagnitudeDiagnosis(List.of(1e4, 2e4, 3e4, 4e4), 1e9))
                .isEqualTo(ScoringUtils.LIQUIDITY_GATE_NO_OBSERVATIONS);
        assertThat(ScoringUtils.liquidityGateMagnitudeDiagnosis(null, 1e9))
                .isEqualTo(ScoringUtils.LIQUIDITY_GATE_NO_OBSERVATIONS);
        // 阈值 <= 0 表示闸关闭，恒为「一致」（不给出误导性的错配告警）
        assertThat(ScoringUtils.liquidityGateMagnitudeDiagnosis(List.of(1e4, 2e4, 3e4, 4e4, 5e4), 0d))
                .isEqualTo(ScoringUtils.LIQUIDITY_GATE_CONSISTENT);
    }

    /** 中位数取排序后的中间值，且非有限/非正值不参与（它们不是量级证据）。 */
    @Test
    void diagnosisIgnoresNonPositiveAndNonFiniteObservations() {
        List<Double> withNoise = new ArrayList<>(List.of(
                1e4, 2e4, 3e4, 4e4, 5e4, 0d, -1d, Double.NaN));
        withNoise.add(null); // List.of 不允许 null，显式加入以覆盖「缺失值」这一路
        // 有效样本 5 个（1e4..5e4），中位数 3e4；阈值 3e4 判为一致
        assertThat(ScoringUtils.liquidityGateMagnitudeDiagnosis(withNoise, 3e4))
                .isEqualTo(ScoringUtils.LIQUIDITY_GATE_CONSISTENT);
        // 阈值 100× 中位数（3e6）判为错配
        assertThat(ScoringUtils.liquidityGateMagnitudeDiagnosis(withNoise, 3e6))
                .isEqualTo(ScoringUtils.LIQUIDITY_GATE_SUSPECTED_UNIT_MISMATCH);
    }

    @Test
    void diagnosisNoteExplainsThatTheGateIsNotSilentlyDisabled() {
        assertThat(ScoringUtils.liquidityGateDiagnosisNote())
                .contains("不改任何阈值")
                .contains("SUSPECTED_UNIT_MISMATCH");
    }
}
