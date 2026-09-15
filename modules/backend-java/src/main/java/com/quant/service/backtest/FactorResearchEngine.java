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

    public record GroupReturnStat(
            int groupNo,
            String label,
            double avgReturn,
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
     * @param rankIcIr ICIR；当 IC 序列无离散度（std = 0，含 T &lt; 2）时**无定义**，为 {@code null}，
     *                 不得写成 0（0 的语义是「IR 恰为 0」，两者不可混同）
     */
    public record FactorResearchSummary(
            int holdingDays,
            int groupCount,
            int crossSectionDays,
            int totalObservations,
            double rankIcMean,
            double rankIcStd,
            Double rankIcIr,
            double rankIcPositiveRate,
            double pearsonIcMean,
            String monotonicity,
            double hacStd,
            double ciLower,
            double ciUpper,
            List<DailyRankIc> dailyRankIc,
            List<GroupReturnStat> groupedReturns,
            double longShortSpread,
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
            double avg = rets.isEmpty() ? 0 : rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
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
                bucketReturns.computeIfAbsent(gs.groupNo(), k -> new ArrayList<>()).add(gs.avgReturn());
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
        double pearsonIcMean = dailyPearson.isEmpty() ? 0
                : dailyPearson.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        List<GroupReturnStat> grouped = new ArrayList<>();
        for (int g = 1; g <= groupCount; g++) {
            List<Double> rets = bucketReturns.getOrDefault(g, List.of());
            double avg = rets.isEmpty() ? 0 : rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            grouped.add(new GroupReturnStat(g, groupLabel(g, groupCount), avg, rets.size()));
        }
        // 空组（observations = 0）不得参与方向断言与极差：其占位 0.0 不是观测，
        // 参与比较会制造出并不存在的上升/下降（Q-R006-04 / Q-R013-02）。
        List<GroupReturnStat> nonEmpty = grouped.stream().filter(gs -> gs.observations() > 0).toList();
        double spread = nonEmpty.isEmpty() ? 0
                : nonEmpty.get(nonEmpty.size() - 1).avgReturn() - nonEmpty.get(0).avgReturn();
        String monotonicity = assessMonotonicity(grouped);

        // QP-011：HAC 标准误 + block bootstrap 置信区间（处理重叠持有期序列相关）
        double hacStd = neweyWestHacStd(icSeries);
        double[] ci = blockBootstrapCi(icSeries, Math.max(1, holdingDays), 1000, 42L);

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
                ci[0],
                ci[1],
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
                holdingDays, groupCount, 0, 0,
                0, 0, null, 0, 0, "N/A", 0, 0, 0,
                List.of(), List.of(), 0,
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
     *   <li>{@code observations == 0} 的空组**不参与**断言：其 {@code avgReturn = 0.0} 是占位而非观测，
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
        List<GroupReturnStat> usable = grouped.stream()
                .filter(gs -> gs != null && gs.observations() > 0)
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
