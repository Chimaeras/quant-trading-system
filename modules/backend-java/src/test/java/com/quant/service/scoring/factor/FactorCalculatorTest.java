package com.quant.service.scoring.factor;

import com.quant.entity.MarketData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 层测试：{@link FactorCalculator}。
 *
 * <p>重点验证两件事：
 * <ol>
 *   <li>能算的因子算对（收益率 / 波动率 / 回撤 / 量比口径与既有实现一致）。</li>
 *   <li>不能算的因子<b>留 null 并给出原因</b>，绝不填 0 或中性值
 *       ——这正是「拿常数打分却宣称多维度」的根源。</li>
 * </ol>
 */
class FactorCalculatorTest {

    private static MarketData bar(LocalDate d, double close, double high, double low, long vol) {
        MarketData m = new MarketData();
        m.setStockCode("000001");
        m.setExchangeType("SZSE");
        m.setTradeDate(d);
        m.setClosePrice(BigDecimal.valueOf(close));
        m.setHighPrice(BigDecimal.valueOf(high));
        m.setLowPrice(BigDecimal.valueOf(low));
        m.setOpenPrice(BigDecimal.valueOf(close));
        m.setVolume(vol);
        m.setAmount(BigDecimal.valueOf(vol * close));
        m.setPreClose(BigDecimal.valueOf(close));
        return m;
    }

    /** 生成 n 根平稳上涨的 bar，收盘价从 10 起每日 +0.1。 */
    private static List<MarketData> series(int n) {
        List<MarketData> bars = new ArrayList<>(n);
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < n; i++) {
            double c = 10.0 + i * 0.1;
            bars.add(bar(d.plusDays(i), c, c + 0.05, c - 0.05, 1000L + i));
        }
        return bars;
    }

    @Test
    void noBarsLeavesEveryFactorUnavailableWithReason() {
        FactorCalculator.Outcome out = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 6, 1), List.of(), null);

        assertThat(out.available()).isEmpty();
        assertThat(out.unavailableReasons()).hasSize(FactorId.values().length);
        assertThat(out.unavailableReasons().values()).allMatch("NO_BARS"::equals);
        assertThat(out.features().barCount()).isZero();
    }

    @Test
    void insufficientBarsGateEachFactorIndependently() {
        // 只有 30 根：5/20 日动量可用，60/120 日不可用
        FactorCalculator.Outcome out = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 2, 1), series(30), null);

        assertThat(out.isAvailable(FactorId.MOM_5)).isTrue();
        assertThat(out.isAvailable(FactorId.MOM_20)).isTrue();
        assertThat(out.isAvailable(FactorId.MOM_60)).isFalse();
        assertThat(out.isAvailable(FactorId.MOM_120)).isFalse();
        assertThat(out.unavailableReasons().get(FactorId.MOM_60)).isEqualTo("INSUFFICIENT_BARS");
        // 30 根不足 31，量比不可用
        assertThat(out.isAvailable(FactorId.VOL_RATIO)).isFalse();
        assertThat(out.unavailableReasons().get(FactorId.VOL_RATIO)).isEqualTo("INSUFFICIENT_BARS");
    }

    @Test
    void ret20MatchesScoringUtilsPctConvention() {
        List<MarketData> bars = series(30);
        FactorCalculator.Outcome out = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 2, 1), bars, null);

        double last = bars.get(bars.size() - 1).getClosePrice().doubleValue();
        double base = bars.get(bars.size() - 21).getClosePrice().doubleValue();
        double expected = (last / base - 1) * 100.0;
        assertThat(out.features().ret20()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void volatilityAndDrawdownAreComputedConsistentlyForLongSeries() {
        FactorCalculator.Outcome out = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 12, 1), series(300), null);

        assertThat(out.isAvailable(FactorId.VOLATILITY_20)).isTrue();
        assertThat(out.isAvailable(FactorId.MAXDD_60)).isTrue();
        assertThat(out.features().volatility20()).isNotNull();
        assertThat(out.features().volatility20()).isGreaterThanOrEqualTo(0d);
        assertThat(out.features().maxDrawdown60()).isNotNull();
        assertThat(out.features().maxDrawdown60()).isGreaterThanOrEqualTo(0d);
    }

    // ── 本库结构性缺失：估值/财务因子必须如实标注不可用，不得填值 ────────────

    @Test
    void missingPeAndPbAreReportedAsUnavailableNotFilledWithNeutralValue() {
        // series() 不设置 peRatio/pbRatio —— 与生产库一致（实测填充率 0.0%）
        FactorCalculator.Outcome out = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 12, 1), series(300), null);

        assertThat(out.isAvailable(FactorId.VAL_EP)).isFalse();
        assertThat(out.isAvailable(FactorId.VAL_BP)).isFalse();
        assertThat(out.unavailableReasons().get(FactorId.VAL_EP)).isEqualTo("NULL_PE_RATIO");
        assertThat(out.unavailableReasons().get(FactorId.VAL_BP)).isEqualTo("NULL_PB_RATIO");
        // 关键：特征里也必须是 null，不能是 0 或某个中性常数
        assertThat(out.features().peRatio()).isNull();
        assertThat(out.features().pbRatio()).isNull();
    }

    @Test
    void financialFactorsWithoutAnySourceAreMarkedAsSuch() {
        FactorCalculator.Outcome out = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 12, 1), series(300), null);

        assertThat(out.isAvailable(FactorId.QUAL_ROE)).isFalse();
        assertThat(out.unavailableReasons().get(FactorId.QUAL_ROE))
                .isEqualTo("NO_FINANCIAL_SNAPSHOT_SOURCE");
        assertThat(out.features().roe()).isNull();
        assertThat(out.features().debtRatio()).isNull();
        assertThat(out.features().currentRatio()).isNull();
    }

    @Test
    void peIsUsedWhenPresentAndNonPositivePeIsRejected() {
        List<MarketData> bars = series(300);
        bars.get(bars.size() - 1).setPeRatio(BigDecimal.valueOf(12.5));
        bars.get(bars.size() - 1).setPbRatio(BigDecimal.valueOf(-1.0));

        FactorCalculator.Outcome out = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 12, 1), bars, null);

        assertThat(out.isAvailable(FactorId.VAL_EP)).isTrue();
        assertThat(out.features().peRatio()).isEqualTo(12.5);
        // 负 PB 视为不可用（而非取绝对值或填 0）
        assertThat(out.isAvailable(FactorId.VAL_BP)).isFalse();
        assertThat(out.unavailableReasons().get(FactorId.VAL_BP)).isEqualTo("NON_POSITIVE_PB");
    }

    @Test
    void invalidCloseInWindowMarksWindowFactorsUnavailableWithoutThrowing() {
        List<MarketData> bars = series(300);
        // 把窗口内一根 bar 的收盘价置为 0（脏数据代理）
        bars.get(150).setClosePrice(BigDecimal.ZERO);

        FactorCalculator.Outcome out = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 12, 1), bars, null);

        assertThat(out.isAvailable(FactorId.TREND_MA)).isFalse();
        assertThat(out.unavailableReasons().get(FactorId.TREND_MA))
                .isEqualTo("INVALID_CLOSE_IN_WINDOW");
        // 不抛异常，且仍返回了特征向量
        assertThat(out.features()).isNotNull();
        assertThat(out.features().barCount()).isEqualTo(300);
    }

    @Test
    void industryIsPreservedWhenPresentAndNulledWhenBlank() {
        FactorCalculator.Outcome withIndustry = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 12, 1), series(300), "银行");
        assertThat(withIndustry.features().industry()).isEqualTo("银行");

        FactorCalculator.Outcome blankIndustry = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 12, 1), series(300), "   ");
        assertThat(blankIndustry.features().industry()).isNull();
    }

    // ── 财务快照 → 基本面因子（回归测试：修一个真实缺陷）──────────────

    /**
     * 缺陷原文：{@code compute} 不接受财务快照，于是
     * {@code QUAL_ROE / QUAL_LEV / LIQ_CURRENT} 被**无条件**标为不可用，
     * 即使调用方已经把 {@code stock_financial_snapshot} 装进内存也用不上。
     * 后果是"综合"/"多维度"两个策略里 40% 的基本面权重结构性空转。
     */
    @Test
    void financialSnapshotMakesQualityAndLiquidityFactorsAvailable() {
        com.quant.entity.StockFinancialSnapshot snap = new com.quant.entity.StockFinancialSnapshot();
        snap.setStockCode("000001");
        snap.setExchangeType("SZSE");
        snap.setReportPeriod(LocalDate.of(2024, 9, 30));
        snap.setRoe(new BigDecimal("12.5"));
        snap.setDebtRatio(new BigDecimal("43.2"));
        snap.setCurrentRatio(new BigDecimal("1.85"));

        FactorCalculator.Outcome out = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 12, 1), series(300), "银行", snap);

        assertThat(out.isAvailable(FactorId.QUAL_ROE)).isTrue();
        assertThat(out.isAvailable(FactorId.QUAL_LEV)).isTrue();
        assertThat(out.isAvailable(FactorId.LIQ_CURRENT)).isTrue();
        assertThat(out.features().roe()).isEqualTo(12.5);
        assertThat(out.features().debtRatio()).isEqualTo(43.2);
        assertThat(out.features().currentRatio()).isEqualTo(1.85);
        // 已可用的因子不应残留在不可用原因表里
        assertThat(out.unavailableReasons()).doesNotContainKeys(
                FactorId.QUAL_ROE, FactorId.QUAL_LEV, FactorId.LIQ_CURRENT);
    }

    @Test
    void snapshotWithNullFieldsStillReportsPerFieldReason() {
        com.quant.entity.StockFinancialSnapshot snap = new com.quant.entity.StockFinancialSnapshot();
        snap.setRoe(new BigDecimal("9.0"));
        // debtRatio / currentRatio 留 null

        FactorCalculator.Outcome out = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 12, 1), series(300), null, snap);

        assertThat(out.isAvailable(FactorId.QUAL_ROE)).isTrue();
        assertThat(out.isAvailable(FactorId.QUAL_LEV)).isFalse();
        assertThat(out.isAvailable(FactorId.LIQ_CURRENT)).isFalse();
        assertThat(out.unavailableReasons().get(FactorId.QUAL_LEV))
                .isEqualTo("NULL_DEBT_RATIO_IN_SNAPSHOT");
        assertThat(out.unavailableReasons().get(FactorId.LIQ_CURRENT))
                .isEqualTo("NULL_CURRENT_RATIO_IN_SNAPSHOT");
    }

    /** 6 参旧重载必须保持"确实无快照"的语义，不能悄悄变成可用。 */
    @Test
    void legacyOverloadWithoutSnapshotKeepsFactorsUnavailable() {
        FactorCalculator.Outcome out = FactorCalculator.compute(
                "000001", "SZSE", "测试", LocalDate.of(2024, 12, 1), series(300), null);

        assertThat(out.isAvailable(FactorId.QUAL_ROE)).isFalse();
        assertThat(out.unavailableReasons().get(FactorId.QUAL_ROE))
                .isEqualTo("NO_FINANCIAL_SNAPSHOT_SOURCE");
    }

    // ── 纯函数单元测试 ────────────────────────────────────────────────

    @Test
    void maAlignmentStrengthIsPositiveForBullishStackAndNegativeForBearish() {
        // 多头：ma5 > ma10 > ma20 > ma60
        assertThat(FactorCalculator.maAlignmentStrength(10.4, 10.3, 10.2, 10.1)).isEqualTo(1.0);
        // 空头：完全反过来
        assertThat(FactorCalculator.maAlignmentStrength(10.1, 10.2, 10.3, 10.4)).isEqualTo(-1.0);
        // 全相等 → 0
        assertThat(FactorCalculator.maAlignmentStrength(10.0, 10.0, 10.0, 10.0)).isZero();
        // 非法输入 → NaN（由调用方转为不可用）
        assertThat(FactorCalculator.maAlignmentStrength(Double.NaN, 10.0, 10.0, 10.0)).isNaN();
        assertThat(FactorCalculator.maAlignmentStrength(0.0, 10.0, 10.0, 10.0)).isNaN();
    }

    @Test
    void volPriceDirectionEncodesHeavyAndShrinkCases() {
        // 放量上涨
        assertThat(FactorCalculator.volPriceDirection(2.0, 3.0)).isEqualTo(1.0);
        // 放量下跌
        assertThat(FactorCalculator.volPriceDirection(2.0, -3.0)).isEqualTo(-1.0);
        // 缩量上涨
        assertThat(FactorCalculator.volPriceDirection(0.5, 3.0)).isEqualTo(0.5);
        // 缩量下跌
        assertThat(FactorCalculator.volPriceDirection(0.5, -3.0)).isEqualTo(-0.5);
        // 中性量能
        assertThat(FactorCalculator.volPriceDirection(1.0, 3.0)).isZero();
        // 输入缺失 → null（不可用），而不是 0
        assertThat(FactorCalculator.volPriceDirection(1.0, null)).isNull();
        assertThat(FactorCalculator.volPriceDirection(Double.NaN, 3.0)).isNull();
    }
}
