package com.quant.service.scoring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 横截面缩尾、稳健 Z 分数与百分位排名（对应设计文档 §4.1）。
 */
public final class CrossSectionFactorMath {

    private CrossSectionFactorMath() {}

    public static List<Double> winsorize(List<Double> values, double lowerPct, double upperPct) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        double lo = percentileSorted(sorted, lowerPct);
        double hi = percentileSorted(sorted, upperPct);
        List<Double> out = new ArrayList<>(values.size());
        for (double v : values) {
            out.add(Math.max(lo, Math.min(hi, v)));
        }
        return out;
    }

    /** 稳健 Z：median + MAD，并 clip 到 [-3, 3]。 */
    public static List<Double> robustZScores(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Double> w = winsorize(values, 2.5, 97.5);
        double[] arr = w.stream().mapToDouble(Double::doubleValue).toArray();
        double med = median(arr);
        double mad = mad(arr, med);
        double denom = 1.4826 * mad + 1e-6;
        List<Double> z = new ArrayList<>(arr.length);
        for (double v : arr) {
            z.add(Math.max(-3, Math.min(3, (v - med) / denom)));
        }
        return z;
    }

    /**
     * 按行业分组做稳健 Z；组内样本 &lt; 5 时回退到全截面 Z（对应设计文档 §4.1 行业内标准化）。
     */
    public static List<Double> robustZScoresByGroup(List<Double> values, List<String> groupKeys) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int n = values.size();
        Map<String, List<Integer>> byGroup = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String g = "_none_";
            if (groupKeys != null && i < groupKeys.size() && groupKeys.get(i) != null && !groupKeys.get(i).isBlank()) {
                g = groupKeys.get(i);
            }
            byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(i);
        }
        List<Double> universeZ = robustZScores(values);
        List<Double> result = new ArrayList<>(Collections.nCopies(n, 0.0));
        for (Map.Entry<String, List<Integer>> e : byGroup.entrySet()) {
            List<Integer> idx = e.getValue();
            if (idx.size() >= 5) {
                List<Double> subVals = idx.stream().map(values::get).toList();
                List<Double> subZ = robustZScores(subVals);
                for (int j = 0; j < idx.size(); j++) {
                    result.set(idx.get(j), subZ.get(j));
                }
            } else {
                for (int i : idx) {
                    result.set(i, universeZ.get(i));
                }
            }
        }
        return result;
    }

    /** 映射到 [0, 100] 的百分位得分。 */
    public static List<Double> percentileScores(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int n = values.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(values.get(a), values.get(b)));
        List<Double> scores = new ArrayList<>(Collections.nCopies(n, 0.0));
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && Double.compare(values.get(idx[j + 1]), values.get(idx[j])) == 0) {
                j++;
            }
            double avgRank = (i + j) / 2.0;
            double pct = n == 1 ? 1.0 : avgRank / (n - 1);
            for (int k = i; k <= j; k++) {
                scores.set(idx[k], pct * 100.0);
            }
            i = j + 1;
        }
        return scores;
    }

    static double percentileSorted(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        double[] arr = sorted.stream().mapToDouble(Double::doubleValue).toArray();
        return percentile(arr, p);
    }

    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        double[] copy = sorted.clone();
        Arrays.sort(copy);
        double rank = (p / 100.0) * (copy.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) {
            return copy[lo];
        }
        double w = rank - lo;
        return copy[lo] * (1 - w) + copy[hi] * w;
    }

    static double median(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        int n = copy.length;
        if (n % 2 == 1) {
            return copy[n / 2];
        }
        return (copy[n / 2 - 1] + copy[n / 2]) / 2.0;
    }

    static double mad(double[] values, double med) {
        double[] dev = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            dev[i] = Math.abs(values[i] - med);
        }
        return median(dev);
    }
}
