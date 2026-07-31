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

    public record DailyRankIc(String tradeDate, double rankIc, int sampleSize) {}

    public record FactorResearchSummary(
            int holdingDays,
            int groupCount,
            int crossSectionDays,
            int totalObservations,
            double rankIcMean,
            double rankIcStd,
            double rankIcIr,
            double rankIcPositiveRate,
            double pearsonIcMean,
            String monotonicity,
            List<DailyRankIc> dailyRankIc,
            List<GroupReturnStat> groupedReturns,
            double longShortSpread,
            String universeNote
    ) {}

    private FactorResearchEngine() {}

    /**
     * Pearson 相关系数（线性 IC）；样本不足或方差为 0 时返回 NaN。
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

    public static List<GroupReturnStat> groupedAverageReturns(
            List<CrossSectionObservation> observations,
            int groupCount
    ) {
        if (observations == null || observations.isEmpty() || groupCount < 2) {
            return List.of();
        }
        List<CrossSectionObservation> sorted = new ArrayList<>(observations);
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
        for (int g = 1; g <= groupCount; g++) {
            bucketReturns.put(g, new ArrayList<>());
        }

        for (Map.Entry<String, List<CrossSectionObservation>> e : observationsByDate.entrySet()) {
            List<CrossSectionObservation> obs = e.getValue();
            if (obs == null || obs.size() < minCrossSectionSize) {
                continue;
            }
            List<Double> scores = new ArrayList<>(obs.size());
            List<Double> rets = new ArrayList<>(obs.size());
            for (CrossSectionObservation o : obs) {
                scores.add(o.score());
                rets.add(o.forwardReturn());
            }
            double ic = spearmanRankCorrelation(scores, rets);
            if (!Double.isFinite(ic)) {
                continue;
            }
            double pic = pearsonCorrelation(scores, rets);
            daily.add(new DailyRankIc(e.getKey(), ic, obs.size()));
            if (Double.isFinite(pic)) {
                dailyPearson.add(pic);
            }
            totalObs += obs.size();

            List<GroupReturnStat> dayGroups = groupedAverageReturns(obs, groupCount);
            for (GroupReturnStat gs : dayGroups) {
                bucketReturns.computeIfAbsent(gs.groupNo(), k -> new ArrayList<>()).add(gs.avgReturn());
            }
        }

        if (daily.isEmpty()) {
            return emptySummary(holdingDays, groupCount, universeNote);
        }

        List<Double> icSeries = daily.stream().map(DailyRankIc::rankIc).toList();
        double icMean = icSeries.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double icVar = icSeries.stream().mapToDouble(v -> (v - icMean) * (v - icMean)).average().orElse(0);
        double icStd = Math.sqrt(icVar);
        double icIr = icStd > 1e-12 ? icMean / icStd : 0;
        long icPos = icSeries.stream().filter(v -> v > 0).count();
        double pearsonIcMean = dailyPearson.isEmpty() ? 0
                : dailyPearson.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        List<GroupReturnStat> grouped = new ArrayList<>();
        for (int g = 1; g <= groupCount; g++) {
            List<Double> rets = bucketReturns.getOrDefault(g, List.of());
            double avg = rets.isEmpty() ? 0 : rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            grouped.add(new GroupReturnStat(g, groupLabel(g, groupCount), avg, rets.size()));
        }
        double spread = grouped.isEmpty() ? 0
                : grouped.get(grouped.size() - 1).avgReturn() - grouped.get(0).avgReturn();
        String monotonicity = assessMonotonicity(grouped);

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
                daily,
                grouped,
                spread,
                universeNote
        );
    }

    private static FactorResearchSummary emptySummary(int holdingDays, int groupCount, String note) {
        return new FactorResearchSummary(
                holdingDays, groupCount, 0, 0,
                0, 0, 0, 0, 0, "N/A",
                List.of(), List.of(), 0,
                note
        );
    }

    /**
     * 分组单调性判断：G1→G5 收益是否单调递增（正向单调）或递减（负向单调）。
     */
    static String assessMonotonicity(List<GroupReturnStat> grouped) {
        if (grouped == null || grouped.size() < 2) {
            return "N/A";
        }
        boolean increasing = true;
        boolean decreasing = true;
        for (int i = 1; i < grouped.size(); i++) {
            double prev = grouped.get(i - 1).avgReturn();
            double cur = grouped.get(i).avgReturn();
            if (cur < prev) {
                increasing = false;
            }
            if (cur > prev) {
                decreasing = false;
            }
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
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double num = 0;
        double vx = 0;
        double vy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            num += dx * dy;
            vx += dx * dx;
            vy += dy * dy;
        }
        if (vx < 1e-18 || vy < 1e-18) {
            return Double.NaN;
        }
        return num / Math.sqrt(vx * vy);
    }
}
