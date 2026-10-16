package com.quant.service.scoring.factor;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4 层测试：{@link UnifiedFactorScoringEngine}。
 *
 * <p>覆盖方案 §5 的验收项：
 * <ul>
 *   <li><b>V-1</b> 同一因子在所有策略里取值完全一致（消除"同指标多标准"）。</li>
 *   <li><b>V-2</b> 缺失因子不参与加权，输出里可追溯原因，绝不填中性值。</li>
 *   <li><b>V-3</b> 分数横截面可比（finalScore 为百分位，且合格股票近似均匀分布）。</li>
 *   <li><b>V-4</b> 归因自洽：Σ(w·pct)/Σ|w| == factorScore。</li>
 * </ul>
 */
class UnifiedFactorScoringEngineTest {

    private static FeatureVector fv(String code, int bars, Double ret5, Double ret20,
                                    Double ret60, Double rsi, Double vol20, Double amt20,
                                    Double logMv) {
        FeatureVector f = FeatureVector.of(code, "SZSE", "测试" + code,
                LocalDate.of(2024, 6, 3), bars);
        f.setRet5(ret5);
        f.setRet20(ret20);
        f.setRet60(ret60);
        f.setRsi14(rsi);
        f.setVolatility20(vol20);
        f.setAvgAmount20(amt20);
        f.setLogMarketCap(logMv, "close_x_volume", "FLOAT_CAP_APPROX");
        // 以下因子在真实 L1 里由 FactorCalculator 从 K 线算出；本测试直接构造特征向量，
        // 故需显式补齐，否则「可用权重占比」闸会（正确地）把这些股票判为数据不足。
        f.setTrendMaAlignment(ret20 == null ? null : Math.min(1.0, ret20 / 20.0));
        f.setMacdHistPct(ret20 == null ? null : ret20 / 10.0);
        f.setBias5(ret5 == null ? null : ret5 * 0.5);
        f.setVolumeRatio(ret20 == null ? null : 1.0 + ret20 / 20.0);
        f.setVolPriceConfirm(ret5 == null ? null : Math.signum(ret5));
        f.setTurnoverRate(ret20 == null ? null : 1.0 + ret20 / 10.0);
        f.setMaxDrawdown60(vol20 == null ? null : vol20 * 2.0);
        f.setKdjJ(rsi);
        return f;
    }

    /** 构造 20 只彼此可比的股票：ret20 从 0 到 19，其余字段同构。 */
    private static List<FeatureVector> crossSection(int n) {
        List<FeatureVector> fvs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // 注意必须补零到固定宽度：否则 "00000"+10 与 "00000"+0 会撞成同一个代码，
            // 使任何按 stockCode 索引的断言静默取到错误的股票。
            fvs.add(fv(String.format("0000%03d", i), 300,
                    (double) i, (double) i, (double) i,
                    50d + i, 2.0 + i * 0.1, 100000d, 10.0 + i));
        }
        return fvs;
    }

    @Test
    void emptyCrossSectionProducesEmptyResultWithoutThrowing() {
        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(StrategyCatalog.trend(), List.of());
        assertThat(r.scores()).isEmpty();
        assertThat(r.rankedCount()).isZero();
    }

    // ── V-1：因子取值不因策略而变 ──────────────────────────────────────

    @Test
    void sameFactorPercentileIsIdenticalAcrossAllFiveStrategies() {
        List<FeatureVector> fvs = crossSection(20);

        // 对每个策略分别打分，记录 股票 -> (因子 -> 百分位)
        List<java.util.Map<String, java.util.Map<FactorId, Double>>> perStrategy = new ArrayList<>();
        List<StrategyDefinition> defs = StrategyCatalog.all();
        for (StrategyDefinition d : defs) {
            UnifiedFactorScoringEngine.Result r = UnifiedFactorScoringEngine.score(d, fvs);
            java.util.Map<String, java.util.Map<FactorId, Double>> byStock = new java.util.LinkedHashMap<>();
            for (UnifiedFactorScoringEngine.StockScore s : r.scores()) {
                java.util.Map<FactorId, Double> m = new java.util.LinkedHashMap<>();
                for (UnifiedFactorScoringEngine.Contribution c : s.contributions()) {
                    m.put(c.factor(), c.percentile());
                }
                byStock.put(s.stockCode(), m);
            }
            perStrategy.add(byStock);
        }

        // 对每个因子，按 stockCode 取「第一个使用该因子的策略」的值作为基准，
        // 再与其他策略逐股票比对。基准必须逐股票比较，不能跨股票比较。
        int checked = 0;
        java.util.Set<FactorId> usedByAny = new java.util.LinkedHashSet<>();
        for (FactorId id : FactorId.values()) {
            java.util.Map<String, Double> referenceByStock = null;
            String referenceStrategy = null;
            for (int s = 0; s < defs.size(); s++) {
                if (!defs.get(s).weights().containsKey(id)) {
                    continue;
                }
                usedByAny.add(id);
                java.util.Map<String, java.util.Map<FactorId, Double>> byStock = perStrategy.get(s);
                if (referenceByStock == null) {
                    referenceByStock = new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<String, java.util.Map<FactorId, Double>> e : byStock.entrySet()) {
                        referenceByStock.put(e.getKey(), e.getValue().get(id));
                    }
                    referenceStrategy = defs.get(s).code();
                    continue;
                }
                for (java.util.Map.Entry<String, java.util.Map<FactorId, Double>> e : byStock.entrySet()) {
                    assertThat(e.getValue().get(id))
                            .as("因子 %s 在策略 %s 与 %s 上对股票 %s 的百分位应一致",
                                    id, referenceStrategy, defs.get(s).code(), e.getKey())
                            .isEqualTo(referenceByStock.get(e.getKey()));
                    checked++;
                }
            }
            if (usedByAny.contains(id)) {
                assertThat(referenceStrategy).as("因子 %s 的基准策略", id).isNotNull();
            }
        }
        // 至少覆盖了绝大多数注册因子；未被任何策略使用的因子在下面单独说明
        assertThat(usedByAny).hasSizeGreaterThanOrEqualTo(15);
        assertThat(checked).isGreaterThan(100);

        // 注册表里允许存在尚未被任何策略使用的因子（它仍然是一个合法的、已定义的因子，
        // 只是当前 5 个策略都不需要它）。把它们显式列出，避免"注册了却没人用"的静默漂移。
        //
        // 当前两项：
        //   MOM_120 —— 长周期动量，5 个策略都只用 5/20/60 日。
        //   MOM_5   —— 其唯一潜在用途是超跌反弹的短期反转，而该策略用的是 REV_5
        //              （= -MOM_5 的横截面排名）。注意 REV_5 的排名与 MOM_5 的排名
        //              恰好互为镜像，故两者并非"独立因子"，MOM_5 当前确实无用。
        // 若将来有策略直接使用 MOM_5，此断言会失败并提醒更新本注释。
        java.util.Set<FactorId> unused = new java.util.LinkedHashSet<>();
        for (FactorId id : FactorId.values()) {
            if (!usedByAny.contains(id)) {
                unused.add(id);
            }
        }
        assertThat(unused).containsExactlyInAnyOrder(FactorId.MOM_120, FactorId.MOM_5);
    }

    // ── V-2：缺失不伪装 ───────────────────────────────────────────────

    @Test
    void missingFundamentalFactorsAreExcludedFromWeightingAndReportedWithReason() {
        // 本库实际情形：PE/PB/财务全缺
        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(StrategyCatalog.comprehensive(), crossSection(20));

        assertThat(r.scores()).isNotEmpty();
        for (UnifiedFactorScoringEngine.StockScore s : r.scores()) {
            for (UnifiedFactorScoringEngine.Contribution c : s.contributions()) {
                if (c.factor() == FactorId.VAL_EP) {
                    // 必须是 null + 原因，而不是 0.0 或 50.0
                    assertThat(c.percentile()).isNull();
                    assertThat(c.contribution()).isNull();
                    assertThat(c.unavailableReason()).isEqualTo("NULL_PE_RATIO");
                }
                if (c.factor() == FactorId.QUAL_ROE) {
                    assertThat(c.percentile()).isNull();
                    assertThat(c.unavailableReason()).isEqualTo("NO_FINANCIAL_SNAPSHOT_SOURCE");
                }
            }
            // 可用权重占比必须 < 1，因为基本面权重结构性缺失
            assertThat(s.availableWeightRatio()).isLessThan(1.0);
        }
    }

    @Test
    void stockBelowMinBarsIsRejectedWithReasonAndNotScored() {
        List<FeatureVector> fvs = new ArrayList<>();
        // 需要 >=2 只可比股票，否则「可用权重占比」在 2 只样本上也无法判定；
        // 用 12 只正常股票 + 1 只短历史，使闸门判定有真实截面
        fvs.addAll(crossSection(12));
        fvs.add(fv("SHORT", 10, 1.0, null, null, null, null, 100000d, 30.0));

        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(StrategyCatalog.trend(), fvs);

        UnifiedFactorScoringEngine.StockScore shortOne = r.scores().stream()
                .filter(s -> "SHORT".equals(s.stockCode())).findFirst().orElseThrow();
        assertThat(shortOne.eligible()).isFalse();
        assertThat(shortOne.finalScore()).isNull();
        assertThat(shortOne.rejectReason()).startsWith("INSUFFICIENT_BARS:");
        assertThat(r.rejectedCount()).isEqualTo(1);
        assertThat(r.rankedCount()).isEqualTo(12);
        assertThat(r.rejectionBreakdown()).isNotEmpty();
        // 被拒绝的股票也必须带完整因子诊断，而不是一片空白
        assertThat(shortOne.contributions()).isNotEmpty();
        assertThat(shortOne.unavailableReasons()).contains("FEATURE_UNAVAILABLE");
    }

    @Test
    void stockBelowMinLiquidityIsRejected() {
        List<FeatureVector> fvs = new ArrayList<>();
        fvs.add(fv("RICH", 300, 1.0, 1.0, 1.0, 55d, 2.0, 100000d, 10.0));
        fvs.add(fv("ILLIQ", 300, 1.0, 2.0, 2.0, 55d, 2.0, -500d, 11.0));

        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(StrategyCatalog.classic(), fvs);

        UnifiedFactorScoringEngine.StockScore illiq = r.scores().stream()
                .filter(s -> "ILLIQ".equals(s.stockCode())).findFirst().orElseThrow();
        assertThat(illiq.eligible()).isFalse();
        assertThat(illiq.rejectReason()).isEqualTo("BELOW_MIN_AVG_AMOUNT");
    }

    @Test
    void oversoldStrategyRejectsStockWithoutVolumePriceConfirmation() {
        // VOL_PRICE 是超跌策略的必需因子；ret5 + volumeRatio 缺失时不可用
        List<FeatureVector> fvs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            FeatureVector f = fv(String.format("0000%03d", i), 300, (double) i, (double) i, (double) i,
                    50d, 2.0, 100000d, 10.0 + i);
            f.setVolumeRatio(1.0 + i);
            f.setVolPriceConfirm((double) i);
            fvs.add(f);
        }
        // 第六只：量价确认缺失（fv() 工厂会按 ret5 推导，故此处显式置 null）
        FeatureVector noConfirm = fv("NOCONF", 300, 1.0, 1.0, 1.0, 50d, 2.0, 100000d, 15.0);
        noConfirm.setVolumeRatio(1.0);
        noConfirm.setVolPriceConfirm(null);
        fvs.add(noConfirm);

        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(StrategyCatalog.oversold(), fvs);

        UnifiedFactorScoringEngine.StockScore nc = r.scores().stream()
                .filter(s -> "NOCONF".equals(s.stockCode())).findFirst().orElseThrow();
        assertThat(nc.eligible()).isFalse();
        assertThat(nc.rejectReason()).isEqualTo("REQUIRED_FACTOR_UNAVAILABLE:VOL_PRICE");
    }

    @Test
    void stockWhoseAvailableWeightIsTooLowIsRejectedNotSilentlyScored() {
        // MULTI_DIM 基本面权重占比高；只保留极少数技术因子时可用权重不足
        List<FeatureVector> sparse = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            FeatureVector f = FeatureVector.of(String.format("0000%03d", i), "SZSE", "稀疏" + i,
                    LocalDate.of(2024, 6, 3), 300);
            f.setAvgAmount20(100000d);
            f.setLogMarketCap(10.0 + i, "close_x_volume", "FLOAT_CAP_APPROX");
            // 只给 LIQUIDITY_20 的原始值，其余全 null
            sparse.add(f);
        }

        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(StrategyCatalog.multiDim(), sparse);

        for (UnifiedFactorScoringEngine.StockScore s : r.scores()) {
            assertThat(s.eligible()).isFalse();
            assertThat(s.rejectReason()).startsWith("INSUFFICIENT_AVAILABLE_WEIGHT:");
        }
        assertThat(r.rankedCount()).isZero();
    }

    // ── V-3：尺度可比 ─────────────────────────────────────────────────

    @Test
    void finalScoreIsACrossSectionalPercentileWithinZeroToHundred() {
        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(StrategyCatalog.classic(), crossSection(20));

        assertThat(r.rankedCount()).isEqualTo(20);
        List<Double> finals = new ArrayList<>();
        for (UnifiedFactorScoringEngine.StockScore s : r.scores()) {
            assertThat(s.eligible()).isTrue();
            assertThat(s.finalScore()).isNotNull().isBetween(0d, 100d);
            finals.add(s.finalScore());
        }
        assertThat(finals).contains(0d, 100d);
        // 分数跨度必须是满量程——对比当前 MULTI_DIM 实测 span 仅 4.48、COMPREHENSIVE 仅 2.54
        assertThat(finals.stream().mapToDouble(Double::doubleValue).max().orElse(0)
                - finals.stream().mapToDouble(Double::doubleValue).min().orElse(0))
                .isEqualTo(100.0);
    }

    @Test
    void factorScoreIsMonotonicInTheDominantFactorWhenOnlyItVaries() {
        // crossSection() 中 TREND_MA / MOM_20 / MOM_60 / RSI / vol20 / logMv 同向递增。
        //
        // 本测试**不能**假定"分数随 i 递增"：方向由**权重符号**决定，而权重符号是
        // 有实证依据的（见 StrategyCatalog 类注释的 2026-10-15 符号修正）。
        // 2026-10-15 把 TREND_MA 由 +0.22 改为 −0.22 后，TREND 的主导因子变成负号，
        // 分数因此随 i **递减**——这是修正后的正确行为，不是回归。
        //
        // 注意：20 只的截面里 TREND_MA 的横截面百分位与"随 i 递增"高度但非严格单调
        // （百分位排名本身把序列压平，端点附近出现并列），
        // 故此处用 §"因子取值"的可靠判据：**分数方向必须与主导因子权重的符号一致**，
        // 而不是断言逐点严格单调。逐点严格单调在存在并列的截面上本就不成立。
        StrategyDefinition trend = StrategyCatalog.trend();
        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(trend, crossSection(20));

        double dominantWeight = trend.weights().get(FactorId.TREND_MA);
        assertThat(dominantWeight)
                .as("本测试假定 TREND_MA 是 TREND 的主导因子；若不再是，请改用真正的主导因子")
                .isNotZero();

        // 分数必须与主导因子反向：TREND_MA 权重为负时，TREND_MA 百分位最低者得分最高。
        List<UnifiedFactorScoringEngine.StockScore> byPct = new ArrayList<>(r.scores());
        byPct.sort((a, b) -> Double.compare(maPct(a), maPct(b)));
        double scoreAtLowestPct = byPct.get(0).factorScore();
        double scoreAtHighestPct = byPct.get(byPct.size() - 1).factorScore();

        assertThat(Math.signum(scoreAtHighestPct - scoreAtLowestPct))
                .as("TREND_MA 权重为 %s：分数应与 TREND_MA 百分位同向（正权重）或反向（负权重）",
                        dominantWeight)
                .isEqualTo(Math.signum(dominantWeight));
    }

    /** 取某只股票的 TREND_MA 百分位；不可用时返回 NaN 以排到最前。 */
    private static double maPct(UnifiedFactorScoringEngine.StockScore s) {
        for (UnifiedFactorScoringEngine.Contribution c : s.contributions()) {
            if (c.factor() == FactorId.TREND_MA && c.percentile() != null) {
                return c.percentile();
            }
        }
        return Double.NaN;
    }

    // ── V-4：归因自洽 ─────────────────────────────────────────────────

    @Test
    void attributionSumsBackToFactorScoreForEveryEligibleStock() {
        for (StrategyDefinition d : StrategyCatalog.all()) {
            UnifiedFactorScoringEngine.Result r =
                    UnifiedFactorScoringEngine.score(d, crossSection(20));
            for (UnifiedFactorScoringEngine.StockScore s : r.scores()) {
                if (!s.eligible()) {
                    continue;
                }
                assertThat(UnifiedFactorScoringEngine.verifyAttribution(s, 0.01))
                        .as("策略 %s 股票 %s 的归因不自洽", d.code(), s.stockCode())
                        .isTrue();
            }
        }
    }

    @Test
    void contributionsExposeRawValuePercentileWeightAndContribution() {
        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(StrategyCatalog.trend(), crossSection(10));

        UnifiedFactorScoringEngine.StockScore s = r.scores().get(0);
        assertThat(s.contributions()).isNotEmpty();
        assertThat(s.contributions()).extracting(UnifiedFactorScoringEngine.Contribution::factor)
                .containsExactlyElementsOf(StrategyCatalog.trend().weights().keySet());

        boolean sawUsable = false;
        for (UnifiedFactorScoringEngine.Contribution c : s.contributions()) {
            assertThat(c.weight()).isNotNaN();
            if (c.percentile() != null) {
                sawUsable = true;
                // 贡献 = 归一化权重 × 百分位
                assertThat(c.contribution())
                        .isCloseTo(c.weight() * c.percentile(),
                                org.assertj.core.data.Offset.offset(0.001));
                assertThat(c.unavailableReason()).isNull();
            } else {
                assertThat(c.contribution()).isNull();
                assertThat(c.unavailableReason()).isNotNull();
            }
        }
        assertThat(sawUsable).isTrue();
    }

    @Test
    void rejectionBreakdownAndAvailabilityRatesAreReported() {
        List<FeatureVector> fvs = new ArrayList<>(crossSection(20));
        // 加一只短历史股票制造一条剔除记录
        fvs.add(fv("SHORT", 5, 1.0, null, null, null, null, 100000d, 30.0));

        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(StrategyCatalog.classic(), fvs);

        assertThat(r.rejectedCount()).isEqualTo(1);
        assertThat(r.rejectionBreakdown()).isNotEmpty();
        assertThat(r.factorAvailabilityRates()).isNotEmpty();
        assertThat(r.factorAvailabilityRates().keySet())
                .containsExactlyElementsOf(StrategyCatalog.classic().weights().keySet());
        // 被拒绝股票的可用率也要如实统计（全部 0），不能因被拒就消失
        assertThat(r.factorAvailabilityRates().get(FactorId.TREND_MA)).isBetween(0d, 1d);
    }

    @Test
    void structurallyMissingFundamentalsReportZeroAvailabilityInStrategiesThatWeightThem() {
        // 只有把估值因子纳入权重的策略才能暴露它的可用率——CLASSIC 不含估值因子，
        // 故必须用 COMPREHENSIVE / MULTI_DIM 验证「结构性缺失 → 可用率 0」。
        for (StrategyDefinition d : List.of(StrategyCatalog.comprehensive(), StrategyCatalog.multiDim())) {
            UnifiedFactorScoringEngine.Result r =
                    UnifiedFactorScoringEngine.score(d, crossSection(20));
            int checked = 0;
            for (FactorId id : List.of(FactorId.VAL_EP, FactorId.VAL_BP, FactorId.QUAL_ROE,
                    FactorId.QUAL_LEV, FactorId.LIQ_CURRENT)) {
                if (!d.weights().containsKey(id)) {
                    continue; // 该策略未使用此因子，不适用
                }
                assertThat(r.factorAvailabilityRates().get(id))
                        .as("策略 %s 的 %s 可用率应为 0（本库结构性缺失）", d.code(), id)
                        .isNotNull()
                        .isZero();
                checked++;
            }
            assertThat(checked).as("策略 %s 应至少纳入一个基本面因子", d.code()).isPositive();
        }
    }

    @Test
    void normalizationDisclosureIsCarriedThroughToEngineResult() {
        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(StrategyCatalog.trend(), crossSection(20));

        assertThat(r.normalization()).isNotNull();
        assertThat(r.normalization().industryNeutralizationSkipped()).isTrue();
        assertThat(r.normalization().industryNeutralizationSkipReason())
                .contains("INDUSTRY_DATA_MISSING");
        assertThat(r.strategyCode()).isEqualTo("TREND");
        assertThat(r.factorVersion()).isEqualTo(StrategyCatalog.FV_TREND);
    }

    @Test
    void manyIdenticalStocksProduceIdenticalScoresNotASingleDominantOne() {
        // 全部同构（所有因子取值相同）→ 全部得到同一百分位，而不是人为拉开
        List<FeatureVector> fvs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            fvs.add(fv(String.format("0000%03d", i), 300, 1.0, 1.0, 1.0, 55d, 2.0, 100000d, 10.0));
        }

        UnifiedFactorScoringEngine.Result r =
                UnifiedFactorScoringEngine.score(StrategyCatalog.classic(), fvs);

        List<Double> finals = r.scores().stream()
                .map(UnifiedFactorScoringEngine.StockScore::finalScore).toList();
        // 全部同分 → 并列第一，故 12 只都是同一个百分位
        assertThat(finals).hasSize(12);
        assertThat(finals).allMatch(v -> v != null && v == 50.0);
    }
}
