package com.quant.service.scoring.factor;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 层测试：{@link CrossSectionNormalizer}。
 *
 * <p>验证方案 §5 的验收项：
 * <ul>
 *   <li><b>V-2</b> 缺失数据不再伪装成数值——不可用的因子百分位必须是 {@code null}，
 *       且<b>不参与</b>排名（若把 null 当 0 参与，会把"没数据"排成"最差"）。</li>
 *   <li><b>V-3</b> 分数尺度可比——百分位必须落在 [0,100] 且近似均匀。</li>
 *   <li>行业缺失时必须显式披露（本库 industry 填充率 0.0%）。</li>
 * </ul>
 */
class CrossSectionNormalizerTest {

    private static FeatureVector fv(String code, Double ret5, Double ret20, Double pe,
                                    Double logMv, String industry) {
        FeatureVector f = FeatureVector.of(code, "SZSE", "测试" + code,
                LocalDate.of(2024, 6, 3), 300);
        f.setRet5(ret5);
        f.setRet20(ret20);
        f.setPeRatio(pe);
        f.setLogMarketCap(logMv, "close_x_volume", "FLOAT_CAP_APPROX");
        f.setIndustry(industry);
        return f;
    }

    @Test
    void percentilesSpanZeroToHundredAndCoverTheFullRange() {
        List<FeatureVector> fvs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            fvs.add(fv("00000" + i, (double) i, (double) i, null, 10.0 + i, null));
        }

        CrossSectionNormalizer.Result r =
                CrossSectionNormalizer.normalize(fvs, List.of(FactorId.MOM_5));

        List<Double> pcts = new ArrayList<>();
        for (Map<FactorId, Double> m : r.factorScores()) {
            Double p = m.get(FactorId.MOM_5);
            assertThat(p).isNotNull();
            assertThat(p).isBetween(0d, 100d);
            pcts.add(p);
        }
        // 10 个互不相同的值 → 最小 0、最大 100
        assertThat(pcts).contains(0d, 100d);
        assertThat(r.factorResults().get(FactorId.MOM_5).availableCount()).isEqualTo(10);
        assertThat(r.factorResults().get(FactorId.MOM_5).unavailableCount()).isZero();
    }

    @Test
    void tiedValuesReceiveTheSameAveragePercentile() {
        List<FeatureVector> fvs = List.of(
                fv("A", 1.0, 1.0, null, 10.0, null),
                fv("B", 2.0, 2.0, null, 10.0, null),
                fv("C", 2.0, 2.0, null, 10.0, null),
                fv("D", 2.0, 2.0, null, 10.0, null),
                fv("E", 5.0, 5.0, null, 10.0, null));

        CrossSectionNormalizer.Result r =
                CrossSectionNormalizer.normalize(fvs, List.of(FactorId.MOM_5));

        Double b = r.factorScores().get(1).get(FactorId.MOM_5);
        Double c = r.factorScores().get(2).get(FactorId.MOM_5);
        Double d = r.factorScores().get(3).get(FactorId.MOM_5);
        assertThat(b).isEqualTo(c);
        assertThat(c).isEqualTo(d);
        // 并列三只占据 rank 1..3 → 平均秩 2 → 2/(5-1)*100 = 50
        assertThat(b).isEqualTo(50.0);
        assertThat(r.factorScores().get(0).get(FactorId.MOM_5)).isZero();
        assertThat(r.factorScores().get(4).get(FactorId.MOM_5)).isEqualTo(100.0);
    }

    // ── V-2：缺失不得伪装成数值 ───────────────────────────────────────

    @Test
    void unavailableFactorGetsNullPercentileAndIsExcludedFromRanking() {
        // PE 全缺（本库实际情形）→ VAL_EP 应对所有股票返回 null
        List<FeatureVector> fvs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fvs.add(fv("00000" + i, (double) i, (double) i, null, 10.0 + i, null));
        }

        CrossSectionNormalizer.Result r =
                CrossSectionNormalizer.normalize(fvs, List.of(FactorId.VAL_EP));

        for (Map<FactorId, Double> m : r.factorScores()) {
            // 关键断言：不能是 0.0、不能是 50.0，必须是 null
            assertThat(m.get(FactorId.VAL_EP)).isNull();
        }
        CrossSectionNormalizer.FactorResult fr = r.factorResults().get(FactorId.VAL_EP);
        assertThat(fr.availableCount()).isZero();
        assertThat(fr.unavailableCount()).isEqualTo(8);
        assertThat(fr.neutralizationMode()).isEqualTo("INSUFFICIENT_CROSS_SECTION");
    }

    @Test
    void partiallyAvailableFactorRanksOnlyTheAvailableSubset() {
        // 一半股票有 ret20，一半没有（模拟 120 日因子在短历史上不可用）
        List<FeatureVector> fvs = new ArrayList<>();
        fvs.add(fv("A", 1.0, 10.0, null, 10.0, null));
        fvs.add(fv("B", 1.0, 20.0, null, 10.0, null));
        fvs.add(fv("C", 1.0, null, null, 10.0, null));
        fvs.add(fv("D", 1.0, 30.0, null, 10.0, null));

        CrossSectionNormalizer.Result r =
                CrossSectionNormalizer.normalize(fvs, List.of(FactorId.MOM_20));

        assertThat(r.factorScores().get(0).get(FactorId.MOM_20)).isZero();     // 最低
        assertThat(r.factorScores().get(3).get(FactorId.MOM_20)).isEqualTo(100.0); // 最高
        assertThat(r.factorScores().get(2).get(FactorId.MOM_20)).isNull();     // 缺数据
        CrossSectionNormalizer.FactorResult fr = r.factorResults().get(FactorId.MOM_20);
        assertThat(fr.availableCount()).isEqualTo(3);
        assertThat(fr.unavailableCount()).isEqualTo(1);
    }

    @Test
    void nonFiniteValuesAreTreatedAsUnavailable() {
        List<FeatureVector> fvs = new ArrayList<>();
        fvs.add(fv("A", 1.0, 1.0, null, 10.0, null));
        fvs.add(fv("B", Double.NaN, 2.0, null, 10.0, null));
        fvs.add(fv("C", Double.POSITIVE_INFINITY, 3.0, null, 10.0, null));
        fvs.add(fv("D", 4.0, 4.0, null, 10.0, null));

        CrossSectionNormalizer.Result r =
                CrossSectionNormalizer.normalize(fvs, List.of(FactorId.MOM_5));

        assertThat(r.factorScores().get(1).get(FactorId.MOM_5)).isNull();
        assertThat(r.factorScores().get(2).get(FactorId.MOM_5)).isNull();
        assertThat(r.factorResults().get(FactorId.MOM_5).availableCount()).isEqualTo(2);
    }

    // ── 行业缺失披露 ──────────────────────────────────────────────────

    @Test
    void missingIndustryIsExplicitlyDisclosedRatherThanSilentlySkipped() {
        // 全空行业的极端情形（写本测试时本库即如此；数据补齐后填充率会 >0，故不应依赖常量）
        List<FeatureVector> fvs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            fvs.add(fv("00000" + i, (double) i, (double) i, null, 10.0 + i, null));
        }

        CrossSectionNormalizer.Result r =
                CrossSectionNormalizer.normalize(fvs, List.of(FactorId.MOM_5));

        assertThat(r.industryNeutralizationSkipped()).isTrue();
        assertThat(r.industryNeutralizationSkipReason())
                .isNotNull()
                .contains("INDUSTRY_DATA_MISSING");
        // 披露必须带**运行时观测到的**覆盖率，而不是写死的 "0%"——
        // 否则数据补齐后这句话会变成假话，误导排障。
        assertThat(r.industryNeutralizationSkipReason()).contains("0/10");
        // 市值中性化同样被跳过（isReliableLogMvSource 恒 false，无 OFFICIAL 可靠性）
        assertThat(r.marketCapNeutralizationSkipped()).isTrue();
        assertThat(r.marketCapNeutralizationSkipReason()).isNotNull();
    }

    @Test
    void partialIndustryCoverageProceedsWithNeutralizationRatherThanSkipping() {
        // 10 只里 2 只有行业：只要有**任何**非空行业，就不算"行业缺失"，
        // 中性化照做（覆盖率低只影响效果，不应被标成 skipped）。
        List<FeatureVector> fvs = new ArrayList<>();
        String[] inds = {"银行", null, null, "银行", null, null, null, null, null, null};
        for (int i = 0; i < 10; i++) {
            fvs.add(fv("00000" + i, (double) i, (double) i, null, 10.0 + i, inds[i]));
        }

        CrossSectionNormalizer.Result r =
                CrossSectionNormalizer.normalize(fvs, List.of(FactorId.MOM_5));

        assertThat(r.industryNeutralizationSkipped()).isFalse();
        assertThat(r.industryNeutralizationSkipReason()).isNull();
    }

    /**
     * 披露文案必须报告**运行时观测到的**覆盖率。
     *
     * <p>这条单独测字符串格式化函数，因为写死 "填充率 0%" 在数据补齐后会变成假话——
     * 我此前正是被旧文案误导过一次（以为中性化从未生效）。
     */
    @Test
    void industrySkipReasonTextReportsObservedCountsNotAHardcodedZero() {
        String zero = CrossSectionNormalizer.skipReasonIndustryMissing(0, 5200);
        assertThat(zero).contains("INDUSTRY_DATA_MISSING").contains("0/5200").contains("0.0%");

        String partial = CrossSectionNormalizer.skipReasonIndustryMissing(2, 10);
        assertThat(partial).contains("2/10").contains("20.0%");
    }

    @Test
    void presentIndustryIsNotReportedAsSkippedForMissingData() {
        List<FeatureVector> fvs = new ArrayList<>();
        String[] inds = {"银行", "银行", "银行", "保险", "保险", "科技", "科技", "医药", "医药", "能源"};
        for (int i = 0; i < 10; i++) {
            fvs.add(fv("00000" + i, (double) i, (double) i, null, 10.0 + i, inds[i]));
        }

        CrossSectionNormalizer.Result r =
                CrossSectionNormalizer.normalize(fvs, List.of(FactorId.MOM_5));

        // 有行业数据时不应因为"行业缺失"而标记跳过
        assertThat(r.industryNeutralizationSkipReason()).isNull();
    }

    // ── 估值因子变换 ──────────────────────────────────────────────────

    @Test
    void valEpIsInversePeSoLowerPeRanksHigher() {
        List<FeatureVector> fvs = List.of(
                fv("CHEAP", 1.0, 1.0, 5.0, 10.0, null),   // PE 5 → EP 0.20 最高
                fv("MID", 1.0, 1.0, 20.0, 10.0, null),   // PE 20 → EP 0.05
                fv("RICH", 1.0, 1.0, 100.0, 10.0, null)); // PE 100 → EP 0.01 最低

        CrossSectionNormalizer.Result r =
                CrossSectionNormalizer.normalize(fvs, List.of(FactorId.VAL_EP));

        assertThat(r.factorScores().get(0).get(FactorId.VAL_EP)).isEqualTo(100.0);
        assertThat(r.factorScores().get(2).get(FactorId.VAL_EP)).isZero();
        // 原始 PE 仍保留在特征里，便于诊断（未被 1/PE 覆盖）
        assertThat(fvs.get(0).peRatio()).isEqualTo(5.0);
    }

    @Test
    void emptyCrossSectionReturnsPassthroughWithoutThrowing() {
        CrossSectionNormalizer.Result r =
                CrossSectionNormalizer.normalize(List.of(), List.of(FactorId.MOM_5));
        assertThat(r.factorScores()).isEmpty();
        assertThat(r.neutralizationMode()).isEqualTo("PASSTHROUGH");
    }

    @Test
    void singleStockCrossSectionIsNotRankedAtHundred() {
        // 只有 1 只股票时给 50（中性），避免"唯一候选得满分"的假象
        List<Double> pct = CrossSectionNormalizer.percentileRanking(List.of(1.234));
        assertThat(pct).containsExactly(50.0);
    }
}
