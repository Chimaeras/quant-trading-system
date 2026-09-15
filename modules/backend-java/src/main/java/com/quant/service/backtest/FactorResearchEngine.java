package com.quant.service.backtest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 截面因子研究：Rank IC 与分组收益（基于信号日得分与 forward return）。
 */
public final class FactorResearchEngine {

    public record CrossSectionObservation(double score, double forwardReturn) {}

    /**
     * 单个分组的收益统计。
     *
     * @param avgReturn 该组收益均值；<b>该组无观测时无定义，为 {@code null}</b>（{@code observations == 0}），
     *                  不得写成 0——0 的语义是「该组均值恰为 0」，两者不可混同（Q-R006-04 / Q-R013-02）
     * @param observations 参与该组均值聚合的观测数；0 表示无观测（与 {@code avgReturn = null} 并存）
     */
    public record GroupReturnStat(
            int groupNo,
            String label,
            Double avgReturn,
            int observations
    ) {}

    /**
     * 单日截面 Rank IC。
     *
     * @param sampleSize           参与本次计算的有限观测数（非有限 score/forwardReturn 已剔除）
     * @param excludedObservations 该日因 score/forwardReturn 非有限而被剔除的观测数（披露用，不参与计算）
     */
    public record DailyRankIc(String tradeDate, double rankIc, int sampleSize, int excludedObservations) {}

    /**
     * 截面因子研究汇总。
     *
     * <p>计数族语义（Q-R014-06 / Q-R014-07 修复）：
     * {@code crossSectionDays} / {@code totalObservations} 只统计**有限**观测；
     * 被剔除或丢弃的数量一律通过下列**只读披露字段**给出，不参与任何统计量计算：
     * {@code inputDays}（入参截面日数）、{@code daysBelowMinCrossSection}（规模不足丢弃日）、
     * {@code daysNonFiniteIc}（IC 不可计算丢弃日）、{@code excludedNonFiniteObservations}（非有限观测数）、
     * {@code groupsWithObservations}（有观测的分组数）、{@code pearsonSupportDays}（Pearson IC 支撑日数）。
     *
     * <p><b>单位陷阱（Q-R014-03）</b>：{@code groupedReturns} 里的 {@code observations} 是
     * **参与该组均值聚合的截面日数**（本类按日聚合「日级组均值」，每组每天贡献 1 个数），
     * 而 {@link #groupedAverageReturns} 返回的逐行 {@code observations} 是**该组的成分观测数**——
     * 同名字段、两处含义不同（载荷侧以 {@code grouped_returns_observations_basis} 声明）。
     *
     * @param rankIcMean Rank IC 均值；无任何可用截面日（{@code crossSectionDays = 0}）时**无定义**，为 {@code null}。
     *                   0 是合法的 IC 取值，不得用 0 表示「无数据」（Q-R006-05 / D-R009-02）
     * @param rankIcStd Rank IC 的**总体**标准差（÷T，见 {@code rank_ic_std_basis}）；无可用截面日时
     *                  **无定义**，为 {@code null}。T ≥ 2 且各组相同 IC 时 std 恰为 0，此时仍输出 0.0
     *                  （真零与不可定义必须可区分）
     * @param rankIcIr ICIR；当 IC 序列无离散度（std = 0，含 T &lt; 2）时**无定义**，为 {@code null}，
     *                 不得写成 0（0 的语义是「IR 恰为 0」，两者不可混同）
     * @param rankIcPositiveRate Rank IC 为正的截面日占比；无可用截面日时**无定义**，为 {@code null}。
     *                           占比恰为 0（所有可用日 IC ≤ 0）时输出 0.0（Q-R013-01）
     * @param pearsonIcMean 线性 IC 均值；当无任何 Pearson 支撑日（`pearsonSupportDays = 0`）时**无定义**，
     *                      为 {@code null}。原实现硬写 0.0，而 0 是合法的 IC 取值，
     *                      二者在载荷上不可区分（Q-R009-02）
     * @param hacStd Newey-West HAC 标准误；IC 序列点数不足 2（{@code crossSectionDays &lt; 2}）时
     *               **无定义**，为 {@code null}（原实现写 0.0，会被读成「零标准误 = 精确估计」）。
     *               与 {@code ci_status = INSUFFICIENT_N} 并存，供消费方区分成因（Q-R007-01）
     * @param ciLower block bootstrap 95% **名义**区间下界；IC 序列点数不足 2 时**无定义**，为 {@code null}
     *                （原实现与 ciUpper 同为 0，零宽区间会被误读作「精确估计」，Q-R007-01 / Q-R007-07）
     * @param ciUpper 区间上界；无定义时同样为 {@code null}
     * @param longShortSpread 高分组均值 − 低分组均值（两个多头腿之差）；无任何非空分组时**无定义**，
     *                        为 {@code null}（原实现写 0.0 会伪造「恰好中性」，Q-R006-04 / Q-R013-02）
     */
    public record FactorResearchSummary(
            int holdingDays,
            int groupCount,
            int crossSectionDays,
            int totalObservations,
            Double rankIcMean,
            Double rankIcStd,
            Double rankIcIr,
            Double rankIcPositiveRate,
            Double pearsonIcMean,
            String monotonicity,
            Double hacStd,
            Double ciLower,
            Double ciUpper,
            List<DailyRankIc> dailyRankIc,
            List<GroupReturnStat> groupedReturns,
            Double longShortSpread,
            String universeNote,
            int inputDays,
            int daysBelowMinCrossSection,
            int daysNonFiniteIc,
            int excludedNonFiniteObservations,
            int groupsWithObservations,
            int pearsonSupportDays
    ) {}

    private FactorResearchEngine() {}

    /**
     * Pearson 相关系数（线性 IC）。
     *
     * <p>返回 {@code NaN}（不可定义）当且仅当：样本不足 2、输入含非有限值，
     * 或某一侧**方差为 0 / 实质为常量**（常量判据是相对的，对输入整体缩放不变）。
     * 注意：Pearson 与 Spearman 的有限性**不等价**——秩空间的平方和量级远高于原空间，
     * 小量级输入可能秩相关有限而 Pearson 为 {@code NaN}（见载荷 {@code pearson_support_days}）。
     */
    public static double pearsonCorrelation(List<Double> scores, List<Double> returns) {
        if (scores == null || returns == null || scores.size() != returns.size() || scores.size() < 2) {
            return Double.NaN;
        }
        int n = scores.size();
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = scores.get(i);
            y[i] = returns.get(i);
        }
        return pearson(x, y);
    }

    /**
     * Spearman 秩相关；样本不足或方差为 0 时返回 NaN。
     */
    public static double spearmanRankCorrelation(List<Double> scores, List<Double> returns) {
        if (scores == null || returns == null || scores.size() != returns.size() || scores.size() < 2) {
            return Double.NaN;
        }
        int n = scores.size();
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = scores.get(i);
            y[i] = returns.get(i);
        }
        return spearmanRankCorrelation(x, y);
    }

    static double spearmanRankCorrelation(double[] x, double[] y) {
        if (x == null || y == null || x.length != y.length || x.length < 2) {
            return Double.NaN;
        }
        double[] rx = ranks(x);
        double[] ry = ranks(y);
        return pearson(rx, ry);
    }

    /**
     * 按分数升序等分入组并取组内 forward return 均值。
     *
     * <p>分桶规则：稳定排序（保序，并列项相对次序 = 入参次序）+ 下标等分
     * {@code bucket = min(g-1, i*g/n)}；载荷须同时披露该规则（见 {@code group_boundary_rule}）。
     *
     * <p>非有限（NaN/±Inf）的 score 或 forwardReturn 观测**不参与**均值与计数：
     * 排序会把 NaN 排到极值端，从而伪造出并不存在的极值组。此类观测被排除后
     * {@code observations} 即「参与了本次均值计算的观测数」。
     */
    public static List<GroupReturnStat> groupedAverageReturns(
            List<CrossSectionObservation> observations,
            int groupCount
    ) {
        if (observations == null || observations.isEmpty() || groupCount < 2) {
            return List.of();
        }
        List<CrossSectionObservation> usable = new ArrayList<>(observations.size());
        for (CrossSectionObservation o : observations) {
            if (o == null || !Double.isFinite(o.score()) || !Double.isFinite(o.forwardReturn())) {
                continue;
            }
            usable.add(o);
        }
        if (usable.isEmpty()) {
            return List.of();
        }
        List<CrossSectionObservation> sorted = usable;
        sorted.sort(Comparator.comparingDouble(CrossSectionObservation::score));
        int n = sorted.size();
        int g = Math.min(groupCount, n);
        List<List<Double>> buckets = new ArrayList<>(g);
        for (int i = 0; i < g; i++) {
            buckets.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            int bucket = Math.min(g - 1, (int) ((long) i * g / n));
            buckets.get(bucket).add(sorted.get(i).forwardReturn());
        }
        List<GroupReturnStat> out = new ArrayList<>(g);
        for (int i = 0; i < g; i++) {
            List<Double> rets = buckets.get(i);
            // 空组无观测 ⟹ 均值无定义，写 null 而不是占位 0.0（0 会被当成真实均值参与极差与方向断言，Q-R006-04）
            Double avg = rets.isEmpty() ? null
                    : rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            out.add(new GroupReturnStat(i + 1, groupLabel(i + 1, g), avg, rets.size()));
        }
        return out;
    }

    public static FactorResearchSummary summarize(
            Map<String, List<CrossSectionObservation>> observationsByDate,
            int holdingDays,
            int groupCount,
            int minCrossSectionSize,
            String universeNote
    ) {
        if (observationsByDate == null || observationsByDate.isEmpty()) {
            return emptySummary(holdingDays, groupCount, universeNote);
        }
        List<DailyRankIc> daily = new ArrayList<>();
        List<Double> dailyPearson = new ArrayList<>();
        Map<Integer, List<Double>> bucketReturns = new LinkedHashMap<>();
        int totalObs = 0;
        int inputDays = observationsByDate.size();
        int daysBelowMinCrossSection = 0;
        int daysNonFiniteIc = 0;
        int excludedNonFiniteObservations = 0;
        for (int g = 1; g <= groupCount; g++) {
            bucketReturns.put(g, new ArrayList<>());
        }

        for (Map.Entry<String, List<CrossSectionObservation>> e : observationsByDate.entrySet()) {
            List<CrossSectionObservation> obs = e.getValue();
            // 规模闸按**输入行数**比较（与冻结前的行为一致）：它是数据可用性闸，
            // 不是样本量统计，故不随非有限观测的剔除而改变纳入集合（载荷披露 min_cross_section_size_basis）。
            if (obs == null || obs.size() < minCrossSectionSize) {
                daysBelowMinCrossSection++;
                continue;
            }
            List<CrossSectionObservation> usable = new ArrayList<>(obs.size());
            int dropped = 0;
            for (CrossSectionObservation o : obs) {
                if (o == null || !Double.isFinite(o.score()) || !Double.isFinite(o.forwardReturn())) {
                    dropped++;
                    continue;
                }
                usable.add(o);
            }
            excludedNonFiniteObservations += dropped;
            // 有限观测不足 2 个（或剔除后空）时秩相关无定义，与原「IC 非有限」同一处置
            if (usable.size() < 2) {
                daysNonFiniteIc++;
                continue;
            }
            List<Double> scores = new ArrayList<>(usable.size());
            List<Double> rets = new ArrayList<>(usable.size());
            for (CrossSectionObservation o : usable) {
                scores.add(o.score());
                rets.add(o.forwardReturn());
            }
            double ic = spearmanRankCorrelation(scores, rets);
            if (!Double.isFinite(ic)) {
                daysNonFiniteIc++;
                continue;
            }
            double pic = pearsonCorrelation(scores, rets);
            daily.add(new DailyRankIc(e.getKey(), ic, usable.size(), dropped));
            if (Double.isFinite(pic)) {
                dailyPearson.add(pic);
            }
            totalObs += usable.size();

            List<GroupReturnStat> dayGroups = groupedAverageReturns(usable, groupCount);
            for (GroupReturnStat gs : dayGroups) {
                // 该组当日无观测 ⟹ 日级组均值为 null：不把 null 塞进聚合序列（null 参与求和会 NPE 且语义上
                // 等于「该日给该组贡献了一个值」，与「无观测」相悖）。
                if (gs.avgReturn() != null) {
                    bucketReturns.computeIfAbsent(gs.groupNo(), k -> new ArrayList<>()).add(gs.avgReturn());
                }
            }
        }

        if (daily.isEmpty()) {
            return emptySummary(holdingDays, groupCount, universeNote, inputDays,
                    daysBelowMinCrossSection, daysNonFiniteIc, excludedNonFiniteObservations);
        }

        List<Double> icSeries = daily.stream().map(DailyRankIc::rankIc).toList();
        double icMean = icSeries.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double icVar = icSeries.stream().mapToDouble(v -> (v - icMean) * (v - icMean)).average().orElse(0);
        double icStd = Math.sqrt(icVar);
        // ICIR 无定义（std = 0，含 T = 1）时为 null：解析上 |IR| → ∞，硬写 0 会把
        // 「无法定义」伪装成「中性」，方向偏乐观。判据用**精确零**而非绝对阈值 1e-12，
        // 以免把量级极小但真实有离散度的 IC 序列误判为无定义。
        Double icIr = icStd == 0 ? null : icMean / icStd;
        long icPos = icSeries.stream().filter(v -> v > 0).count();
        // 无 Pearson 支撑日 ⟹ 线性 IC 不可定义，写 null（0 是合法 IC 取值，不可用来表示「无数据」）
        Double pearsonIcMean = dailyPearson.isEmpty() ? null
                : dailyPearson.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        List<GroupReturnStat> grouped = new ArrayList<>();
        for (int g = 1; g <= groupCount; g++) {
            List<Double> rets = bucketReturns.getOrDefault(g, List.of());
            // 空组（observations = 0）无观测 ⟹ 均值无定义，写 null；不得用占位 0.0（Q-R006-04 / Q-R013-02）
            Double avg = rets.isEmpty() ? null
                    : rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            grouped.add(new GroupReturnStat(g, groupLabel(g, groupCount), avg, rets.size()));
        }
        // 空组（observations = 0）不得参与方向断言与极差：其均值是占位而非观测，
        // 参与比较会制造出并不存在的上升/下降（Q-R006-04 / Q-R013-02）。
        List<GroupReturnStat> nonEmpty = grouped.stream()
                .filter(gs -> gs.observations() > 0 && gs.avgReturn() != null)
                .toList();
        // 无非空分组 ⟹ 极差无定义，写 null 而不是 0.0（0.0 会伪造「高分组与低分组恰好持平」）
        Double spread = null;
        if (!nonEmpty.isEmpty()) {
            Double high = nonEmpty.get(nonEmpty.size() - 1).avgReturn();
            Double low = nonEmpty.get(0).avgReturn();
            spread = (high == null || low == null) ? null : high - low;
        }
        String monotonicity = assessMonotonicity(grouped);

        // QP-011：HAC 标准误 + block bootstrap 置信区间（处理重叠持有期序列相关）
        // IC 序列点数不足 2 时两者在数值上都退化为 0（/零宽区间），会被读成「零标准误 = 精确估计」
        // ⟹ 该情形下汇总里写 null，并保留 ci_status = INSUFFICIENT_N 说明成因（Q-R007-01）。
        // 注意：neweyWestHacStd / blockBootstrapCi 的签名与算术**不变**（另一次授权改动会动前者）。
        Double hacStd = null;
        Double ciLower = null;
        Double ciUpper = null;
        if (icSeries.size() >= 2) {
            hacStd = neweyWestHacStd(icSeries);
            double[] ci = blockBootstrapCi(icSeries, Math.max(1, holdingDays), 1000, 42L);
            ciLower = ci[0];
            ciUpper = ci[1];
        }

        return new FactorResearchSummary(
                holdingDays,
                groupCount,
                daily.size(),
                totalObs,
                icMean,
                icStd,
                icIr,
                (double) icPos / daily.size(),
                pearsonIcMean,
                monotonicity,
                hacStd,
                ciLower,
                ciUpper,
                daily,
                grouped,
                spread,
                universeNote,
                inputDays,
                daysBelowMinCrossSection,
                daysNonFiniteIc,
                excludedNonFiniteObservations,
                nonEmpty.size(),
                dailyPearson.size()
        );
    }

    private static FactorResearchSummary emptySummary(
            int holdingDays, int groupCount, String note, int inputDays,
            int daysBelowMinCrossSection, int daysNonFiniteIc, int excludedNonFiniteObservations) {
        return new FactorResearchSummary(
                // 无任何可用截面日 ⟹ 所有统计量均无定义，一律写 null（不得写 0.0 冒充）。
                // 整数计数是真实计数（0 个日 / 0 个观测），照常输出数值。
                holdingDays, groupCount, 0, 0,
                null, null, null, null, null, "N/A", null, null, null,
                List.of(), List.of(), null,
                note,
                inputDays, daysBelowMinCrossSection, daysNonFiniteIc,
                excludedNonFiniteObservations, 0, 0
        );
    }

    private static FactorResearchSummary emptySummary(int holdingDays, int groupCount, String note) {
        return emptySummary(holdingDays, groupCount, note, 0, 0, 0, 0);
    }

    /**
     * 分组单调性判断：G1→Gn 收益是否单调递增（正向单调）或递减（负向单调）。
     *
     * <p>三条守卫（Q-R006-04 / Q-R006-07 / Q-R013-01 / Q-R013-02 / Q-R013-09 修复）：
     * <ol>
     *   <li>{@code observations == 0}（或均值 {@code null}）的空组**不参与**断言：其均值是占位而非观测，
     *       参与比较会制造出并不存在的上升/下降；可用组少于 2 个时返回 {@code "N/A"}。</li>
     *   <li>全相等（零斜率）返回 {@code "NO_DISPERSION"}：原实现下 {@code increasing} 与
     *       {@code decreasing} 同时为真、由分支顺序决定输出，等于用「零区分度」断言方向。</li>
     *   <li>组均值非有限（NaN/±Inf）时返回 {@code "N/A"}：NaN 与任何数比较恒假，
     *       会把含 NaN 的序列读成「无严格反序对」。</li>
     * </ol>
     */
    static String assessMonotonicity(List<GroupReturnStat> grouped) {
        if (grouped == null || grouped.size() < 2) {
            return "N/A";
        }
        // 均值 null（空组）与 observations == 0 等价：该组「无观测」，不可用于方向断言
        List<GroupReturnStat> usable = grouped.stream()
                .filter(gs -> gs != null && gs.observations() > 0 && gs.avgReturn() != null)
                .toList();
        if (usable.size() < 2) {
            return "N/A";
        }
        for (GroupReturnStat gs : usable) {
            if (!Double.isFinite(gs.avgReturn())) {
                return "N/A";
            }
        }
        boolean increasing = true;
        boolean decreasing = true;
        boolean flat = true;
        for (int i = 1; i < usable.size(); i++) {
            double prev = usable.get(i - 1).avgReturn();
            double cur = usable.get(i).avgReturn();
            if (cur < prev) {
                increasing = false;
            }
            if (cur > prev) {
                decreasing = false;
            }
            if (cur != prev) {
                flat = false;
            }
        }
        if (flat) {
            return "NO_DISPERSION";
        }
        if (increasing) {
            return "MONOTONIC_INCREASING";
        }
        if (decreasing) {
            return "MONOTONIC_DECREASING";
        }
        return "NON_MONOTONIC";
    }

    /**
     * 样本标准差（÷(T−1)），供载荷并列披露 ICIR 的另一条分母约定（Q-R006-02）。
     *
     * <p>本项目的**主**口径是总体标准差 ÷T（{@code rank_ic_std_basis = POPULATION_STD_DIV_T}），
     * 本方法**不改**任何既有数值，只让 ÷(T−1) 读法可被复核：两者比值恒为
     * {@code sqrt(T/(T-1))}（T=5 时约 +11.8%）。
     *
     * @return 样本标准差；{@code T < 2} 或输入非有限时返回 {@code null}（无定义，不得写 0）
     */
    public static Double sampleStdDivTMinusOne(List<Double> values) {
        if (values == null || values.size() < 2) {
            return null;
        }
        int n = values.size();
        double mean = 0;
        for (Double v : values) {
            if (v == null || !Double.isFinite(v)) {
                return null;
            }
            mean += v;
        }
        mean /= n;
        double ss = 0;
        for (Double v : values) {
            ss += (v - mean) * (v - mean);
        }
        return Math.sqrt(ss / (n - 1));
    }

    /**
     * 单腿往返成本率（entry open → exit close 的乘性实现，与冻结口径同构）：
     * {@code 1 − (1−slippage)(1−commission−tax) / ((1+slippage)(1+commission))}。
     *
     * <p>冻结口径下（佣金万三、印花税万五、单边滑点万二）≈ {@code 0.0014992}，即约 <strong>15.0bp</strong>。
     * 常见的 <strong>12bp</strong> 加法式写法（{@code 2×滑点 + 佣金 + 印花税}）**漏掉卖出腿佣金**，
     * 与实现不符（差值主项 = −佣金 = −3.00bp）——这与 {@code D-R010-01} 的公式勘误同一根因；
     * 因此「两腿美元中性扣减 24bp」与「30bp」两种候选读法的分歧**不是建模选择**，而是加法式/乘性之差。
     *
     * @return 成本率；入参非有限或为负时返回 {@code NaN}（不可定义）
     */
    public static double roundTripCostPerLeg(double commissionRate, double sellTaxRate, double slippageRate) {
        if (!Double.isFinite(commissionRate) || !Double.isFinite(sellTaxRate) || !Double.isFinite(slippageRate)
                || commissionRate < 0 || sellTaxRate < 0 || slippageRate < 0) {
            return Double.NaN;
        }
        double netFactor = (1 - slippageRate) * (1 - commissionRate - sellTaxRate)
                / ((1 + slippageRate) * (1 + commissionRate));
        return 1 - netFactor;
    }

    /**
     * 美元中性（真双腿：多头最高分组 + 空头最低分组）净价差：
     * {@code 毛价差 − 2 × 单腿往返成本}。
     *
     * <p>{@code longShortSpread} 本身是**两个多头腿净收益之差**（成本在两腿差分中结构上抵消），
     * 不承担空头腿成本；本方法给出论文口径下的真双腿美元中性值，供并列对照，
     * **不替换**既有字段（Q-R003-01 / Q-R004-01）。
     *
     * @param grossSpread 毛收益（未扣费）两腿之差；为 {@code null} 时返回 {@code null}
     * @return 美元中性净价差；成本不可定义（非有限）时返回 {@code null}
     */
    public static Double dollarNeutralSpread(Double grossSpread, double roundTripCostPerLeg) {
        if (grossSpread == null || !Double.isFinite(grossSpread) || !Double.isFinite(roundTripCostPerLeg)) {
            return null;
        }
        return grossSpread - 2 * roundTripCostPerLeg;
    }

    /**
     * 净价差 → 毛价差的还原：{@code 毛价差 = 净价差 / (1 − 单腿往返成本)}。
     *
     * <p>这是**价格模型**（回测执行面实际用法）的逆运算：该面按价格乘性扣费，
     * 每个观测的净收益 = 毛收益 × (1 − 单腿成本)，组均值同比例缩放，故除法可精确还原。
     * 用来把「成本后近似净收益面」的同名 {@code longShortSpread} 对齐到「毛收益面」的同一把尺子上
     * （两面同名不同量，见载荷的 {@code long_short_spread_cost_basis}）。
     *
     * <p><b>注意：本方法不是 {@link #dollarNeutralSpread} 的逆运算</b>。后者用的是「美元中性」惯例
     * {@code 毛价差 − 2c}（两条腿各按名义本金付一次往返成本），与价格模型 {@code 毛价差 ×(1−c)} 是
     * **两个不同的成本模型**，差值闭式为 {@code c(2 − 毛价差)}（毛价差 5%、c≈15bp 时约 2.92bp）；
     * 把美元中性值代回本方法，还原缺口为 {@code c(2 − G)/(1 − c)}。
     * 两个都保留：净观测面用本方法还原，毛价差面用美元中性惯例折算，各自声明基准。
     *
     * @param netSpread 净（已扣费）两腿价差；{@code null} 时返回 {@code null}
     * @param roundTripCostPerLeg 单腿往返成本；为 1（或非有限/负）时分母为 0 或反号，返回 {@code null}
     */
    public static Double grossSpreadFromNet(Double netSpread, double roundTripCostPerLeg) {
        if (netSpread == null || !Double.isFinite(netSpread)
                || !Double.isFinite(roundTripCostPerLeg) || roundTripCostPerLeg < 0) {
            return null;
        }
        double factor = 1 - roundTripCostPerLeg;
        if (factor <= 0) {
            return null;
        }
        return netSpread / factor;
    }

    private static String groupLabel(int groupNo, int groupCount) {
        if (groupCount == 5) {
            return switch (groupNo) {
                case 1 -> "G1(低分)";
                case 2 -> "G2";
                case 3 -> "G3";
                case 4 -> "G4";
                case 5 -> "G5(高分)";
                default -> "G" + groupNo;
            };
        }
        if (groupNo == 1) {
            return "G1(低分)";
        }
        if (groupNo == groupCount) {
            return "G" + groupNo + "(高分)";
        }
        return "G" + groupNo;
    }

    private static double[] ranks(double[] values) {
        int n = values.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, Comparator.comparingDouble(i -> values[i]));
        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && Double.compare(values[idx[j + 1]], values[idx[j]]) == 0) {
                j++;
            }
            double avgRank = (i + j + 2) / 2.0;
            for (int k = i; k <= j; k++) {
                ranks[idx[k]] = avgRank;
            }
            i = j + 1;
        }
        return ranks;
    }

    private static double pearson(double[] x, double[] y) {
        int n = x.length;
        double mx = 0;
        double my = 0;
        double maxAbsX = 0;
        double maxAbsY = 0;
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(x[i]) || !Double.isFinite(y[i])) {
                // 非有限输入：直接判无定义，不让 NaN/Inf 进入求和（NaN 参与排序/比较会伪造极值）
                return Double.NaN;
            }
            mx += x[i];
            my += y[i];
            maxAbsX = Math.max(maxAbsX, Math.abs(x[i]));
            maxAbsY = Math.max(maxAbsY, Math.abs(y[i]));
        }
        mx /= n;
        my /= n;
        double num = 0;
        double vx = 0;
        double vy = 0;
        double maxAbsDx = 0;
        double maxAbsDy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            num += dx * dy;
            vx += dx * dx;
            vy += dy * dy;
            maxAbsDx = Math.max(maxAbsDx, Math.abs(dx));
            maxAbsDy = Math.max(maxAbsDy, Math.abs(dy));
        }
        // 退化判据（两条并列）：
        // ① 平方和**恰为** 0 ⟹ 常量输入；
        // ② 去中心后的波动相对输入量级已落在双精度舍入噪声内 ⟹ 实质常量。
        // 两者都是**相对**判据（②的两侧同尺度）⟹ 对输入整体缩放不变。
        // 原实现用绝对阈值 `vx < 1e-18`，会把量级 1e-10 的合法输入误判为常量（ρ 由 1.0 变 NaN），
        // 破坏的是 Pearson 相关的尺度不变性，而 javadoc 声明的只是「方差为 0」。
        if (vx == 0 || vy == 0
                || maxAbsDx <= 1e-12 * maxAbsX || maxAbsDy <= 1e-12 * maxAbsY) {
            return Double.NaN;
        }
        return num / Math.sqrt(vx * vy);
    }

    /**
     * Newey-West HAC 标准误（处理每日重叠持有期造成的序列相关）。
     * 滞后阶数 L = floor(n^(1/3))，权重 1 - l/(L+1)。
     */
    public static double neweyWestHacStd(List<Double> values) {
        if (values == null || values.size() < 2) {
            return 0;
        }
        int n = values.size();
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double[] dev = new double[n];
        for (int i = 0; i < n; i++) {
            dev[i] = values.get(i) - mean;
        }
        int lag = (int) Math.floor(Math.pow(n, 1.0 / 3.0));
        lag = Math.max(1, Math.min(lag, n - 1));
        double gamma0 = 0;
        for (double d : dev) {
            gamma0 += d * d;
        }
        gamma0 /= n;
        double hacVar = gamma0;
        for (int l = 1; l <= lag; l++) {
            double weight = 1.0 - (double) l / (lag + 1);
            double gammaL = 0;
            for (int i = l; i < n; i++) {
                gammaL += dev[i] * dev[i - l];
            }
            gammaL /= n;
            hacVar += 2 * weight * gammaL;
        }
        return Math.sqrt(Math.max(hacVar / n, 0));
    }

    /**
     * Block bootstrap 置信区间（处理序列相关），95% 名义区间。
     *
     * <p><b>有效块长</b>：{@code bs = max(1, min(min(blockSize, floor(sqrt(n))), n-1))}，
     * 即**不等于**传入的 {@code blockSize}（持有期 h）：当 {@code h > floor(sqrt(n))} 时
     * 有效块长被压到 {@code floor(sqrt(n))}，与 h 解耦。返回 {@code [ciLower, ciUpper]}。
     *
     * <p><b>覆盖率的诚实边界</b>：这是**名义**水平区间，未做多重检验校正；
     * 在重叠持有期序列相关下，实测覆盖率可能显著低于 95%（见 {@code blocked_queue.md} 与
     * {@code research/paper_loop/} 的不确定性审计），不得当作保证覆盖率。
     */
    public static double[] blockBootstrapCi(List<Double> values, int blockSize, int iterations, long seed) {
        if (values == null || values.size() < 2 || iterations < 100) {
            return new double[]{0, 0};
        }
        int n = values.size();
        // 块大小 = min(blockSize, floor(sqrt(n)), n-1)：避免块大小接近/等于样本量时
        // rng.nextInt(n-bs+1) 恒为 0，bootstrap 退化为对同一序列的点估计，输出伪收敛区间
        int sqrtBlock = (int) Math.floor(Math.sqrt(n));
        int bs = Math.max(1, Math.min(Math.min(blockSize, sqrtBlock), n - 1));
        java.util.Random rng = new java.util.Random(seed);
        double[] bootMeans = new double[iterations];
        for (int b = 0; b < iterations; b++) {
            double sum = 0;
            int count = 0;
            while (count < n) {
                int start = rng.nextInt(n - bs + 1);
                for (int j = 0; j < bs && count < n; j++) {
                    sum += values.get(start + j);
                    count++;
                }
            }
            bootMeans[b] = sum / n;
        }
        Arrays.sort(bootMeans);
        int lo = (int) Math.floor(iterations * 0.025);
        int hi = (int) Math.ceil(iterations * 0.975) - 1;
        lo = Math.max(0, Math.min(lo, iterations - 1));
        hi = Math.max(0, Math.min(hi, iterations - 1));
        return new double[]{bootMeans[lo], bootMeans[hi]};
    }
}
