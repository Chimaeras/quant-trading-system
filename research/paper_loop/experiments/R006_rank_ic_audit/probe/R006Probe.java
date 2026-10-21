package com.quant.service.backtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * R006 审计探针（Java 侧）。
 *
 * <p>唯一职责：<b>调用仓库自己的</b> {@link FactorResearchEngine}（由 {@code -cp target/classes} 加载），
 * 把输入与输出按 flat JSON line 形式打到 stdout，供 run_rank_ic_audit.py 判定。
 *
 * <p>本类<b>不重新实现</b> Spearman / Pearson / 分组 / IR 这些<b>生产量</b>——
 * 生产量一律来自 {@code FactorResearchEngine} 的调用。
 * 探针里自行实现的只有<b>判据侧</b>的量（式 (8) 的 1 − 6Σd²/(N(N²−1))、平均秩、以及按
 * 论文口径的 ÷(T−1) 标准差），这些是「用于比对的对照值」，不冒充生产实现。
 */
public final class R006Probe {

    // ===================== JSON lines =====================

    private static void emit(String probe, String key, Object value) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"probe\":\"").append(esc(probe)).append("\",\"key\":\"").append(esc(key)).append('"');
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d)) {
                sb.append(",\"value\":\"NaN\"");
            } else if (Double.isInfinite(d)) {
                sb.append(",\"value\":\"").append(d > 0 ? "+Inf" : "-Inf").append('"');
            } else {
                sb.append(",\"value\":").append(Double.toString(d));
            }
        } else if (value instanceof Number) {
            sb.append(",\"value\":").append(value);
        } else {
            sb.append(",\"value\":\"").append(esc(String.valueOf(value))).append('"');
        }
        sb.append('}');
        System.out.println(sb);
    }

    private static void emitIdx(String probe, String key, int i, Object value) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"probe\":\"").append(esc(probe)).append("\",\"key\":\"").append(esc(key))
                .append("\",\"i\":").append(i);
        if (value instanceof Double) {
            double d = (Double) value;
            if (Double.isNaN(d)) {
                sb.append(",\"value\":\"NaN\"");
            } else if (Double.isInfinite(d)) {
                sb.append(",\"value\":\"").append(d > 0 ? "+Inf" : "-Inf").append('"');
            } else {
                sb.append(",\"value\":").append(Double.toString(d));
            }
        } else if (value instanceof Number) {
            sb.append(",\"value\":").append(value);
        } else {
            sb.append(",\"value\":\"").append(esc(String.valueOf(value))).append('"');
        }
        sb.append('}');
        System.out.println(sb);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ===================== 判据侧（对照值，非生产量） =====================

    /** 平均秩（并列取平均），独立实现，仅作对照。 */
    static double[] avgRanks(double[] v) {
        int n = v.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(v[a], v[b]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && Double.compare(v[idx[j + 1]], v[idx[j]]) == 0) {
                j++;
            }
            double avg = (i + j + 2) / 2.0;
            for (int k = i; k <= j; k++) {
                r[idx[k]] = avg;
            }
            i = j + 1;
        }
        return r;
    }

    /** 判据侧 Pearson（仅用于把平均秩变成相关系数，不冒充生产实现）。 */
    static double defPearson(double[] x, double[] y) {
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
        if (vx == 0 || vy == 0) {
            return Double.NaN;
        }
        return num / Math.sqrt(vx * vy);
    }

    /** 判据侧「按定义」的 Spearman = 平均秩的 Pearson（无并列时等于式 (8)）。 */
    static double defSpearman(double[] scores, double[] rets) {
        return defPearson(avgRanks(scores), avgRanks(rets));
    }

    /** 判据侧式 (8)：1 − 6 Σ d_i² / (N (N²−1))。 */
    static double eq8(double[] scores, double[] rets) {
        int n = scores.length;
        double[] rx = avgRanks(scores);
        double[] ry = avgRanks(rets);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double d = rx[i] - ry[i];
            sum += d * d;
        }
        return 1.0 - 6.0 * sum / (n * (n * n - 1.0));
    }

    /** 论文式 (18)：样本标准差 ÷(T−1)。 */
    static double sampleStd(List<Double> v) {
        int t = v.size();
        if (t < 2) {
            return Double.NaN;
        }
        double m = 0;
        for (double d : v) {
            m += d;
        }
        m /= t;
        double s = 0;
        for (double d : v) {
            s += (d - m) * (d - m);
        }
        return Math.sqrt(s / (t - 1));
    }

    /** 项目口径的总体标准差 ÷T。 */
    static double popStd(List<Double> v) {
        int t = v.size();
        if (t < 1) {
            return Double.NaN;
        }
        double m = 0;
        for (double d : v) {
            m += d;
        }
        m /= t;
        double s = 0;
        for (double d : v) {
            s += (d - m) * (d - m);
        }
        return Math.sqrt(s / t);
    }

    // ===================== 面板构造 =====================

    private static List<Double> box(double[] a) {
        List<Double> l = new ArrayList<>(a.length);
        for (double v : a) {
            l.add(v);
        }
        return l;
    }

    /** 正常截面：得分与收益都随机（IC 有限但接近 0）。 */
    static Map<String, List<FactorResearchEngine.CrossSectionObservation>> randomPanel(
            int dates, int n, long seed) {
        Random rng = new Random(seed);
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> m = new LinkedHashMap<>();
        for (int d = 0; d < dates; d++) {
            List<FactorResearchEngine.CrossSectionObservation> obs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                obs.add(new FactorResearchEngine.CrossSectionObservation(rng.nextGaussian(), rng.nextGaussian() * 0.02));
            }
            m.put(String.format("2026-01-%02d", d + 1), obs);
        }
        return m;
    }

    /** 让 rank 与 Pearson 明显分歧的截面：得分带重尾，收益与得分单调反向并叠加噪声。 */
    static Map<String, List<FactorResearchEngine.CrossSectionObservation>> divergentPanel(
            int dates, int n, long seed) {
        Random rng = new Random(seed);
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> m = new LinkedHashMap<>();
        for (int d = 0; d < dates; d++) {
            List<FactorResearchEngine.CrossSectionObservation> obs = new ArrayList<>(n);
            double[] sc = new double[n];
            for (int i = 0; i < n; i++) {
                // 重尾：多数值聚在 0 附近，少数离群值极大 ⇒ rank 与 Pearson 分歧
                sc[i] = (i == 0) ? 500.0 : (i == 1 ? -500.0 : rng.nextGaussian());
                obs.add(null);
            }
            double[] rt = new double[n];
            for (int i = 0; i < n; i++) {
                rt[i] = -0.01 * sc[i] / 100.0 + rng.nextGaussian() * 0.01;
            }
            for (int i = 0; i < n; i++) {
                obs.set(i, new FactorResearchEngine.CrossSectionObservation(sc[i], rt[i]));
            }
            m.put(String.format("2026-02-%02d", d + 1), obs);
        }
        return m;
    }

    /** 单日面板：得分升序、收益严格降序 ⇒ Spearman 恰为 −1。 */
    static Map<String, List<FactorResearchEngine.CrossSectionObservation>> singleDate(int n, double sign) {
        List<FactorResearchEngine.CrossSectionObservation> obs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double s = i + 1.0;
            double r = sign * (n - i);
            obs.add(new FactorResearchEngine.CrossSectionObservation(s, r));
        }
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> m = new LinkedHashMap<>();
        m.put("2026-03-01", obs);
        return m;
    }

    /** 含退化日的面板：degenerate 天的得分在该截面恒定 ⇒ 秩零方差 ⇒ IC 非有限。 */
    static Map<String, List<FactorResearchEngine.CrossSectionObservation>> panelWithDegenerate(
            int goodDates, int degenerateDates, int n, long seed) {
        Random rng = new Random(seed);
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> m = new LinkedHashMap<>();
        for (int d = 0; d < goodDates; d++) {
            List<FactorResearchEngine.CrossSectionObservation> obs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                obs.add(new FactorResearchEngine.CrossSectionObservation(rng.nextGaussian(), rng.nextGaussian() * 0.02));
            }
            m.put(String.format("2026-04-%02d", d + 1), obs);
        }
        for (int d = 0; d < degenerateDates; d++) {
            List<FactorResearchEngine.CrossSectionObservation> obs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                // 得分全等 ⇒ 秩全等 ⇒ IC 与 Pearson 同时非有限
                obs.add(new FactorResearchEngine.CrossSectionObservation(7.0, rng.nextGaussian() * 0.02));
            }
            m.put(String.format("2026-04-%02d", goodDates + d + 1), obs);
        }
        return m;
    }

    /** 单日均值全等的分组面板：n=10, groupCount=5，每组两人收益 [0.00, 0.02] ⇒ 每组均值相同且 var>0。 */
    static Map<String, List<FactorResearchEngine.CrossSectionObservation>> equalGroupMeansPanel() {
        int n = 10;
        List<FactorResearchEngine.CrossSectionObservation> obs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double s = i + 1.0;
            double r = (i % 2 == 0) ? 0.00 : 0.02;
            obs.add(new FactorResearchEngine.CrossSectionObservation(s, r));
        }
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> m = new LinkedHashMap<>();
        m.put("2026-05-01", obs);
        return m;
    }

    private static int minSize(int n) {
        return Math.min(10, n);
    }

    // ===================== 各探针 =====================

    private static void provenance() {
        emit("G3", "engine_code_source",
                String.valueOf(FactorResearchEngine.class.getProtectionDomain().getCodeSource().getLocation()));
        emit("G3", "engine_class", FactorResearchEngine.class.getName());
        emit("G3", "engine_classloader", String.valueOf(FactorResearchEngine.class.getClassLoader()));
        // 真实被调用的证据：同一函数在两种输入上必须给出不同结果（常量桩无法通过）
        double[] a = {1, 2, 3, 4, 5};
        double[] b = {5, 4, 3, 2, 1};
        double[] c = {1, 3, 2, 5, 4};
        emit("G3", "sentinel_spearman_reverse", FactorResearchEngine.spearmanRankCorrelation(a, b));
        emit("G3", "sentinel_spearman_perm", FactorResearchEngine.spearmanRankCorrelation(a, c));
        emit("G3", "sentinel_pearson_reverse", FactorResearchEngine.pearsonCorrelation(box(a), box(b)));
    }

    /** V01 → A1a：rank 序列与 Pearson 序列分歧时，icIr 跟哪一个走。 */
    private static void probeV01() {
        int t = 6;
        int n = 12;
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> panel = divergentPanel(t, n, 20260914L);
        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(panel, 5, 5, minSize(n), "R006-V01");

        emit("V01", "t", s.dailyRankIc().size());
        emit("V01", "rank_ic_mean", s.rankIcMean());
        emit("V01", "pearson_ic_mean", s.pearsonIcMean());
        emit("V01", "rank_ic_std", s.rankIcStd());
        emit("V01", "rank_ic_ir", s.rankIcIr());
        emit("V01", "mean_abs_rank_vs_pearson_diff", meanAbsRankPearsonGap(panel));

        List<Double> rankSeries = new ArrayList<>();
        for (FactorResearchEngine.DailyRankIc d : s.dailyRankIc()) {
            rankSeries.add(d.rankIc());
        }
        List<Double> pearsonSeries = pearsonSeries(panel);

        emit("V01", "ir_rank_div_T", ir(rankSeries, false));
        emit("V01", "ir_rank_div_Tm1", ir(rankSeries, true));
        emit("V01", "ir_pearson_div_T", ir(pearsonSeries, false));
        emit("V01", "ir_pearson_div_Tm1", ir(pearsonSeries, true));
    }

    private static double meanAbsRankPearsonGap(
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> panel) {
        double acc = 0;
        int c = 0;
        for (Map.Entry<String, List<FactorResearchEngine.CrossSectionObservation>> e : panel.entrySet()) {
            List<Double> sc = new ArrayList<>();
            List<Double> rt = new ArrayList<>();
            for (FactorResearchEngine.CrossSectionObservation o : e.getValue()) {
                sc.add(o.score());
                rt.add(o.forwardReturn());
            }
            double sp = FactorResearchEngine.spearmanRankCorrelation(sc, rt);
            double pe = FactorResearchEngine.pearsonCorrelation(sc, rt);
            if (Double.isFinite(sp) && Double.isFinite(pe)) {
                acc += Math.abs(sp - pe);
                c++;
            }
        }
        return c == 0 ? Double.NaN : acc / c;
    }

    private static double ir(List<Double> series, boolean sample) {
        if (series.isEmpty()) {
            return Double.NaN;
        }
        double m = 0;
        for (double d : series) {
            m += d;
        }
        m /= series.size();
        double sd = sample ? sampleStd(series) : popStd(series);
        if (!Double.isFinite(sd) || sd == 0) {
            return 0;
        }
        return m / sd;
    }

    private static List<Double> pearsonSeries(
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> panel) {
        List<Double> out = new ArrayList<>();
        for (Map.Entry<String, List<FactorResearchEngine.CrossSectionObservation>> e : panel.entrySet()) {
            List<Double> sc = new ArrayList<>();
            List<Double> rt = new ArrayList<>();
            for (FactorResearchEngine.CrossSectionObservation o : e.getValue()) {
                sc.add(o.score());
                rt.add(o.forwardReturn());
            }
            double p = FactorResearchEngine.pearsonCorrelation(sc, rt);
            if (Double.isFinite(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** V04 → A2a：无并列输入上，生产实现 vs 判据侧定义值 vs 式 (8)。含随机扫描（防掩码）。 */
    private static void probeV04() {
        double maxEngineVsDef = 0;
        double maxEngineVsEq8 = 0;
        int cases = 0;
        long checksum = 0;

        // 定长确定性子例（无并列）
        double[][] casesFixed = {
                {1, 2, 3, 4, 5},
                {1.5, 2.5, 3.5, 4.5, 5.5},
                {-3, -1, 0, 2, 7},
        };
        double[][] retsFixed = {
                {5, 4, 3, 2, 1},
                {1, 2, 3, 4, 5},
                {2, -1, 4, 0, 5},
        };
        for (int c = 0; c < casesFixed.length; c++) {
            double[] sc = casesFixed[c];
            double[] rt = retsFixed[c];
            double eng = FactorResearchEngine.spearmanRankCorrelation(sc, rt);
            double def = defSpearman(sc, rt);
            double e8 = eq8(sc, rt);
            emitIdx("V04", "fixed_engine", c, eng);
            emitIdx("V04", "fixed_def", c, def);
            emitIdx("V04", "fixed_eq8", c, e8);
            maxEngineVsDef = Math.max(maxEngineVsDef, Math.abs(eng - def));
            maxEngineVsEq8 = Math.max(maxEngineVsEq8, Math.abs(eng - e8));
            checksum += (long) (eng * 1e6);
            cases++;
        }

        // 随机扫描：n ∈ [2,40]，值互不相同（无并列）
        Random rng = new Random(4242L);
        for (int it = 0; it < 200; it++) {
            int n = 2 + rng.nextInt(39);
            double[] sc = new double[n];
            double[] rt = new double[n];
            for (int i = 0; i < n; i++) {
                sc[i] = rng.nextDouble() * 1000 + i * 1e-6;
                rt[i] = rng.nextGaussian() * 5 + i * 1e-6;
            }
            double eng = FactorResearchEngine.spearmanRankCorrelation(sc, rt);
            double def = defSpearman(sc, rt);
            double e8 = eq8(sc, rt);
            maxEngineVsDef = Math.max(maxEngineVsDef, Math.abs(eng - def));
            maxEngineVsEq8 = Math.max(maxEngineVsEq8, Math.abs(eng - e8));
            // 反向锚：降序收益必须给出 −1，常量桩无法同时满足
            if (it == 0) {
                double[] asc = new double[n];
                double[] desc = new double[n];
                for (int i = 0; i < n; i++) {
                    asc[i] = i;
                    desc[i] = n - i;
                }
                emit("V04", "sweep_anchor_reverse", FactorResearchEngine.spearmanRankCorrelation(asc, desc));
            }
            checksum += (long) (eng * 1e6);
            cases++;
        }
        emit("V04", "cases", cases);
        emit("V04", "max_abs_engine_vs_def", maxEngineVsDef);
        emit("V04", "max_abs_engine_vs_eq8", maxEngineVsEq8);
        emit("V04", "engine_output_checksum", checksum);
    }

    /** V05 → A2b：有并列输入上，生产实现 vs 定义值 vs 式 (8)。 */
    private static void probeV05() {
        double maxEngineVsDef = 0;
        double maxEq8VsDef = 0;
        int cases = 0;

        // 重并列子例：得分与收益都只有少数几个取值
        int[] ns = {12, 20, 8, 30};
        for (int c = 0; c < ns.length; c++) {
            int n = ns[c];
            double[] sc = new double[n];
            double[] rt = new double[n];
            Random rng = new Random(1000 + c);
            for (int i = 0; i < n; i++) {
                sc[i] = rng.nextInt(3);      // 取值 {0,1,2} ⇒ 大量并列
                rt[i] = rng.nextInt(3) - 1;  // 取值 {−1,0,1}
            }
            double eng = FactorResearchEngine.spearmanRankCorrelation(sc, rt);
            double def = defSpearman(sc, rt);
            double e8 = eq8(sc, rt);
            emitIdx("V05", "tie_engine", c, eng);
            emitIdx("V05", "tie_def", c, def);
            emitIdx("V05", "tie_eq8", c, e8);
            emitIdx("V05", "tie_scatter", c, Math.abs(e8 - def));
            if (Double.isFinite(eng) && Double.isFinite(def)) {
                maxEngineVsDef = Math.max(maxEngineVsDef, Math.abs(eng - def));
            }
            if (Double.isFinite(e8) && Double.isFinite(def)) {
                maxEq8VsDef = Math.max(maxEq8VsDef, Math.abs(e8 - def));
            }
            cases++;
        }

        // 全并例子例：得分为 {1,1,2,2,...}，收益为 {1,2,1,2,...}
        int n = 16;
        double[] sc = new double[n];
        double[] rt = new double[n];
        for (int i = 0; i < n; i++) {
            sc[i] = (i < n / 2) ? 1 : 2;
            rt[i] = (i % 2 == 0) ? 1 : 2;
        }
        double eng = FactorResearchEngine.spearmanRankCorrelation(sc, rt);
        double def = defSpearman(sc, rt);
        double e8 = eq8(sc, rt);
        emit("V05", "alltie_engine", eng);
        emit("V05", "alltie_def", def);
        emit("V05", "alltie_eq8", e8);
        if (Double.isFinite(eng) && Double.isFinite(def)) {
            maxEngineVsDef = Math.max(maxEngineVsDef, Math.abs(eng - def));
        }
        if (Double.isFinite(e8) && Double.isFinite(def)) {
            maxEq8VsDef = Math.max(maxEq8VsDef, Math.abs(e8 - def));
        }
        cases++;

        emit("V05", "cases", cases);
        emit("V05", "max_abs_engine_vs_def", maxEngineVsDef);
        emit("V05", "max_abs_eq8_vs_def", maxEq8VsDef);
    }

    /** V06 / V07 / V08 → A3：ICIR 分母口径（T vs T−1）× 年化 × 序列来源 的 8 单元网格。 */
    private static void probeV06() {
        int[] ts = {17, 10};
        for (int c = 0; c < ts.length; c++) {
            int t = ts[c];
            int n = 20;
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> panel = randomPanel(t, n, 777L + c);
            FactorResearchEngine.FactorResearchSummary s =
                    FactorResearchEngine.summarize(panel, 5, 5, minSize(n), "R006-V06");
            List<Double> rankSeries = new ArrayList<>();
            for (FactorResearchEngine.DailyRankIc d : s.dailyRankIc()) {
                rankSeries.add(d.rankIc());
            }
            List<Double> pearsonSeries = pearsonSeries(panel);

            emitIdx("V06", "T", c, s.dailyRankIc().size());
            emitIdx("V06", "rank_ic_ir", c, s.rankIcIr());
            emitIdx("V06", "rank_ic_std", c, s.rankIcStd());
            emitIdx("V06", "rank_ic_mean", c, s.rankIcMean());
            emitIdx("V06", "pearson_ic_mean", c, s.pearsonIcMean());

            String[][] labels = {
                    {"rank", "1", "div_T"},
                    {"rank", "1", "div_Tm1"},
                    {"rank", "sqrt252", "div_T"},
                    {"rank", "sqrt252", "div_Tm1"},
                    {"pearson", "1", "div_T"},
                    {"pearson", "1", "div_Tm1"},
                    {"pearson", "sqrt252", "div_T"},
                    {"pearson", "sqrt252", "div_Tm1"},
            };
            for (int g = 0; g < labels.length; g++) {
                List<Double> series = "rank".equals(labels[g][0]) ? rankSeries : pearsonSeries;
                boolean sample = "div_Tm1".equals(labels[g][2]);
                double base = ir(series, sample);
                double val = "sqrt252".equals(labels[g][1]) ? base * Math.sqrt(252.0) : base;
                emitIdx("V06", "grid_" + String.join("_", labels[g]), c, val);
            }

            // V07：项目口径 ÷ 论文口径 必须恰为 sqrt(T/(T−1))（项目用 ÷T ⇒ |IR| 更大）
            double irT = ir(rankSeries, false);
            double irTm1 = ir(rankSeries, true);
            emitIdx("V07", "ratio_observed", c, irTm1 == 0 ? Double.NaN : irT / irTm1);
            emitIdx("V07", "ratio_expected", c, Math.sqrt((double) t / (t - 1)));
            emitIdx("V07", "ir_project_div_T", c, irT);
            emitIdx("V07", "ir_paper_div_Tm1", c, irTm1);

            // V08：rank_ic_std 是 ÷T 还是 ÷(T−1)
            emitIdx("V08", "std_pop", c, popStd(rankSeries));
            emitIdx("V08", "std_sample", c, sampleStd(rankSeries));
            emitIdx("V08", "rank_ic_std", c, s.rankIcStd());
        }
    }

    /** V09 / V10 → A4：退化日是否被静默丢弃；Pearson 与 rank 的分母是否恒同。 */
    private static void probeV09() {
        int good = 4;
        int degen = 3;
        int n = 12;
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> panel =
                panelWithDegenerate(good, degen, n, 555L);
        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(panel, 5, 5, minSize(n), "R006-V09");

        emit("V09", "input_dates", panel.size());
        emit("V09", "input_good_dates", good);
        emit("V09", "input_degenerate_dates", degen);
        emit("V09", "cross_section_days", s.crossSectionDays());
        emit("V09", "daily_size", s.dailyRankIc().size());
        emit("V09", "total_observations", s.totalObservations());
        emit("V09", "good_observations", good * n);
        int i = 0;
        for (FactorResearchEngine.DailyRankIc d : s.dailyRankIc()) {
            emitIdx("V09", "survivor_date", i, d.tradeDate());
            i++;
        }
        // Pearson 侧分母：用生产函数在同一输入上逐日重建（只保留有限值），再看均值是否与载荷一致
        List<Double> pearsonKept = pearsonSeries(panel);
        emit("V09", "pearson_kept_days", pearsonKept.size());
        double pm = 0;
        for (double d : pearsonKept) {
            pm += d;
        }
        emit("V09", "pearson_mean_over_kept", pearsonKept.isEmpty() ? Double.NaN : pm / pearsonKept.size());
        emit("V09", "pearson_ic_mean", s.pearsonIcMean());
        emit("V09", "rank_ic_positive_rate", s.rankIcPositiveRate());

        // V10：随机扫描，统计「ic 有限 vs pic 有限」的不一致数
        long finIcNotPic = 0;
        long finPicNotIc = 0;
        long nanBoth = 0;
        long finBoth = 0;
        Random rng = new Random(31337L);
        for (int it = 0; it < 300; it++) {
            int m = 10 + rng.nextInt(21);
            List<Double> sc = new ArrayList<>();
            List<Double> rt = new ArrayList<>();
            int mode = it % 3;
            for (int k = 0; k < m; k++) {
                if (mode == 1) {
                    sc.add(3.0);                                   // 得分恒定
                } else {
                    sc.add(rng.nextGaussian());
                }
                if (mode == 2) {
                    rt.add(0.01);                                  // 收益恒定
                } else {
                    rt.add(rng.nextGaussian() * 0.02);
                }
            }
            double ic = FactorResearchEngine.spearmanRankCorrelation(sc, rt);
            double pic = FactorResearchEngine.pearsonCorrelation(sc, rt);
            boolean fi = Double.isFinite(ic);
            boolean fp = Double.isFinite(pic);
            if (fi && fp) {
                finBoth++;
            } else if (!fi && !fp) {
                nanBoth++;
            } else if (fi) {
                finIcNotPic++;
            } else {
                finPicNotIc++;
            }
        }
        emit("V10", "sweep_cases", 300);
        emit("V10", "n_finite_both", finBoth);
        emit("V10", "n_nan_both", nanBoth);
        emit("V10", "n_finite_ic_only", finIcNotPic);
        emit("V10", "n_finite_pic_only", finPicNotIc);
    }

    /** V11 / V12 → A5：n < groupCount 时的空组与 spread。 */
    private static void probeV11() {
        int n = 12;
        int groupCount = 20;   // > n，且远大于前端上限 10
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> panel = randomPanel(3, n, 909L);
        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(panel, 5, groupCount, minSize(n), "R006-V11");

        emit("V11", "n", n);
        emit("V11", "groupCount", groupCount);
        emit("V11", "grouped_size", s.groupedReturns().size());
        emit("V11", "long_short_spread", s.twoLongLegSpread());
        emit("V11", "monotonicity", s.monotonicity());
        int empty = 0;
        for (FactorResearchEngine.GroupReturnStat g : s.groupedReturns()) {
            emitIdx("V11", "group_avg", g.groupNo(), g.avgReturn());
            emitIdx("V11", "group_obs", g.groupNo(), g.observations());
            emitIdx("V11", "group_label", g.groupNo(), g.label());
            if (g.observations() == 0 && g.avgReturn() == 0.0) {
                empty++;
            }
        }
        emit("V11", "empty_groups", empty);
        FactorResearchEngine.GroupReturnStat g1 = s.groupedReturns().get(0);
        FactorResearchEngine.GroupReturnStat glast =
                s.groupedReturns().get(s.groupedReturns().size() - 1);
        emit("V11", "g1_avg", g1.avgReturn());
        emit("V11", "glast_avg", glast.avgReturn());
        emit("V11", "glast_obs", glast.observations());
        emit("V11", "spread_recomputed_0_minus_g1", 0.0 - g1.avgReturn());
    }

    /** V14 → A6a：零有效观测时的载荷。 */
    private static void probeV14() {
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> empty = new LinkedHashMap<>();
        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(empty, 5, 5, 10, "R006-V14");
        emit("V14", "rank_ic_mean", s.rankIcMean());
        emit("V14", "rank_ic_std", s.rankIcStd());
        emit("V14", "rank_ic_ir", s.rankIcIr());
        emit("V14", "rank_ic_positive_rate", s.rankIcPositiveRate());
        emit("V14", "pearson_ic_mean", s.pearsonIcMean());
        emit("V14", "long_short_spread", s.twoLongLegSpread());
        emit("V14", "hac_std", s.hacStd());
        emit("V14", "ci_lower", s.ciLower());
        emit("V14", "ci_upper", s.ciUpper());
        emit("V14", "monotonicity", s.monotonicity());
        emit("V14", "universe_note", s.universeNote());
        emit("V14", "cross_section_days", s.crossSectionDays());
        emit("V14", "total_observations", s.totalObservations());
        emit("V14", "daily_size", s.dailyRankIc().size());
        emit("V14", "grouped_size", s.groupedReturns().size());
    }

    /** V15 → A6b：T=1 时 std=0 ⇒ ir 被写成 0，即使均值为强负。 */
    private static void probeV15() {
        int n = 12;
        for (int c = 0; c < 2; c++) {
            double sign = (c == 0) ? -1.0 : 1.0;
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> panel = singleDate(n, sign);
            FactorResearchEngine.FactorResearchSummary s =
                    FactorResearchEngine.summarize(panel, 5, 5, minSize(n), "R006-V15");
            emitIdx("V15", "sign_case", c, sign);
            emitIdx("V15", "t", c, s.dailyRankIc().size());
            emitIdx("V15", "rank_ic_mean", c, s.rankIcMean());
            emitIdx("V15", "rank_ic_std", c, s.rankIcStd());
            emitIdx("V15", "rank_ic_ir", c, s.rankIcIr());
            emitIdx("V15", "rank_ic_positive_rate", c, s.rankIcPositiveRate());
        }
    }

    /** V18 → A7：各组均值全相等（零区分度）时的单调性标签。 */
    private static void probeV18() {
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> panel = equalGroupMeansPanel();
        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(panel, 5, 5, 10, "R006-V18");
        emit("V18", "monotonicity", s.monotonicity());
        emit("V18", "daily_size", s.dailyRankIc().size());
        emit("V18", "rank_ic_mean", s.rankIcMean());
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (FactorResearchEngine.GroupReturnStat g : s.groupedReturns()) {
            emitIdx("V18", "group_avg", g.groupNo(), g.avgReturn());
            emitIdx("V18", "group_obs", g.groupNo(), g.observations());
            min = Math.min(min, g.avgReturn());
            max = Math.max(max, g.avgReturn());
        }
        emit("V18", "group_avg_max_gap", max - min);
        // 组均相等但个股收益方差 > 0（否则 IC 非有限、daily 为空，走不到 monotonicity）
        List<Double> rets = new ArrayList<>();
        for (FactorResearchEngine.CrossSectionObservation o : panel.get("2026-05-01")) {
            rets.add(o.forwardReturn());
        }
        emit("V18", "return_variance_gt_0", popStd(rets) > 0);
    }

    public static void main(String[] args) throws Exception {
        provenance();
        probeV01();
        probeV04();
        probeV05();
        probeV06();
        probeV09();
        probeV11();
        probeV14();
        probeV15();
        probeV18();
        emit("DONE", "completed", 1);
    }
}
