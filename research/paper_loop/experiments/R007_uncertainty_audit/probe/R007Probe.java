package com.quant.service.backtest;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * R007 审计探针（Java 侧）—— 统计不确定性载荷族（hac_std / ci_lower / ci_upper）。
 *
 * <p>唯一职责：<b>调用仓库自己的</b> {@link FactorResearchEngine}
 * （由 {@code -cp modules/backend-java/target/classes} 加载），
 * 把输入与输出按 flat JSON line 打到 stdout，供 run_uncertainty_audit.py 判定。
 *
 * <p>本类<b>不重新实现</b>生产量：{@code hac_std}、{@code ci_lower/ci_upper}、{@code summarize} 的全部取值
 * 都来自 {@code FactorResearchEngine}。探针里自行实现的只有<b>判据/对照侧</b>的量：
 *  - {@code controlNwStd(x, lag)}：显式滞后阶的 Newey-West 标准误（对照值，用于识别生产实现用的滞后阶）；
 *  - {@code controlBlockCi(x, bs, iters, seed)}：块 bootstrap 的**逐行复刻**（对照值，用于识别实际块长）；
 *  - 覆盖率统计与钳位发生率统计（统计口径；不是生产量）。
 * 这些一律只作**对照值**出现，并与生产实现同输入并列打印。
 */
public final class R007Probe {

    // ===================== 冻结常数（R007_prereg.md §3 §4 §5 §8）=====================

    static final long BASE_SEED = 20260914L;
    static final int[] NS = {5, 10, 17, 27};
    static final int[] HS = {1, 2, 3, 5};
    static final int[] HS_EXTRAP = {1, 2, 3, 5, 10, 20};
    static final String[] DGPS = {"IID_NORMAL", "AR1_0.2", "AR1_0.5", "AR1_0.7", "MA_equal_hm1"};
    static final int REPS_CLAMP = 1000;
    static final int REPS_COVER = 1000;
    static final int REPS_MEDIAN = 200;
    static final int BOOT_ITERS = 1000;
    static final long BOOT_SEED = 42L;
    static final double Z95 = 1.96;

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
            sb.append(",\"value\":\"").append(esc(String.valueOf(value))).append("\"");
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
            sb.append(",\"value\":\"").append(esc(String.valueOf(value))).append("\"");
        }
        sb.append('}');
        System.out.println(sb);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ===================== 对照侧（非生产量）=====================

    private static List<Double> box(double[] x) {
        List<Double> out = new ArrayList<>(x.length);
        for (double v : x) {
            out.add(v);
        }
        return out;
    }

    /** 样本均值（算术）。 */
    static double mean(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return s / x.length;
    }

    /** 总体方差 ÷n（对照值）。 */
    static double varPop(double[] x) {
        double m = mean(x);
        double s = 0;
        for (double v : x) {
            s += (v - m) * (v - m);
        }
        return s / x.length;
    }

    /**
     * 对照实现：显式滞后阶 lag 的 Newey-West（Bartlett）标准误。
     * 仅用于与生产实现比对，识别生产实现实际使用的滞后阶。
     */
    static double controlNwStd(double[] x, int lag) {
        int n = x.length;
        if (n < 2) {
            return 0.0;
        }
        double m = mean(x);
        double[] dev = new double[n];
        for (int i = 0; i < n; i++) {
            dev[i] = x[i] - m;
        }
        int L = Math.max(1, Math.min(lag, n - 1));
        double g0 = 0;
        for (double d : dev) {
            g0 += d * d;
        }
        g0 /= n;
        double hv = g0;
        for (int l = 1; l <= L; l++) {
            double w = 1.0 - (double) l / (L + 1);
            double gl = 0;
            for (int i = l; i < n; i++) {
                gl += dev[i] * dev[i - l];
            }
            gl /= n;
            hv += 2 * w * gl;
        }
        return Math.sqrt(Math.max(hv / n, 0));
    }

    /** 生产实现（读码）的滞后规则，仅用于选择对照单元。 */
    static int cbrtLag(int n) {
        int lag = (int) Math.floor(Math.pow(n, 1.0 / 3.0));
        return Math.max(1, Math.min(lag, n - 1));
    }

    /** 生产实现（读码）的块长规则，仅用于选择对照单元。 */
    static int effectiveBlockSize(int n, int blockSize) {
        int sqrtBlock = (int) Math.floor(Math.sqrt(n));
        return Math.max(1, Math.min(Math.min(blockSize, sqrtBlock), n - 1));
    }

    /**
     * 对照实现：块 bootstrap 的逐行复刻（同 RNG 调用顺序），用于**识别**生产实现实际使用的块长。
     * 只作对照值。
     */
    static double[] controlBlockCi(double[] x, int bs, int iterations, long seed) {
        int n = x.length;
        Random rng = new Random(seed);
        double[] boot = new double[iterations];
        for (int b = 0; b < iterations; b++) {
            double sum = 0;
            int count = 0;
            while (count < n) {
                int start = rng.nextInt(n - bs + 1);
                for (int j = 0; j < bs && count < n; j++) {
                    sum += x[start + j];
                    count++;
                }
            }
            boot[b] = sum / n;
        }
        java.util.Arrays.sort(boot);
        int lo = (int) Math.floor(iterations * 0.025);
        int hi = (int) Math.ceil(iterations * 0.975) - 1;
        lo = Math.max(0, Math.min(lo, iterations - 1));
        hi = Math.max(0, Math.min(hi, iterations - 1));
        return new double[]{boot[lo], boot[hi]};
    }

    /** 冻结的 DGP。mu = 0、sigma = 1。MA_equal_hm1 在 h = 1 时按定义退化为 IID_NORMAL。 */
    static double[] dgp(String name, int n, int h, long seed) {
        Random rng = new Random(seed);
        double[] x = new double[n];
        if ("IID_NORMAL".equals(name) || ("MA_equal_hm1".equals(name) && h <= 1)) {
            for (int i = 0; i < n; i++) {
                x[i] = rng.nextGaussian();
            }
            return x;
        }
        if (name.startsWith("AR1_")) {
            double phi = Double.parseDouble(name.substring(4));
            double z0 = rng.nextGaussian();
            x[0] = z0 / Math.sqrt(1.0 - phi * phi);
            for (int i = 1; i < n; i++) {
                x[i] = phi * x[i - 1] + rng.nextGaussian();
            }
            return x;
        }
        if (name.startsWith("MA_equal_hm1")) {
            int q = h - 1;
            double[] eps = new double[n + q];
            for (int i = 0; i < eps.length; i++) {
                eps[i] = rng.nextGaussian();
            }
            double w = 1.0 / h;
            for (int t = 0; t < n; t++) {
                double s = 0;
                for (int j = 0; j < h; j++) {
                    s += eps[t + q - j];
                }
                x[t] = s * w;
            }
            return x;
        }
        throw new IllegalArgumentException("unknown dgp: " + name);
    }

    /** 固定面板用的确定性序列（T=17，两位小数，避免随机性进入 A1a）。 */
    static double[] panelSeries17() {
        return new double[]{0.12, -0.05, 0.08, 0.21, -0.13, 0.04, 0.17, -0.09, 0.02, 0.14,
                -0.21, 0.06, 0.11, -0.04, 0.19, -0.07, 0.03};
    }

    /** 固定面板用的确定性序列（T=10）。 */
    static double[] panelSeries10() {
        return new double[]{0.10, 0.12, 0.08, 0.11, 0.09, 0.13, 0.07, 0.10, 0.12, 0.08};
    }

    // ===================== G3 门 =====================

    static void g3() throws Exception {
        emit("G3", "engine_code_source",
                FactorResearchEngine.class.getProtectionDomain().getCodeSource().getLocation().toString());
        emit("G3", "engine_class", FactorResearchEngine.class.getName());
        emit("G3", "engine_classloader", String.valueOf(FactorResearchEngine.class.getClassLoader()));
        emit("G3", "sentinel_nw_reverse", FactorResearchEngine.neweyWestHacStd(
                box(new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10})));
        emit("G3", "sentinel_nw_perm_control", controlNwStd(new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 2));
        emit("G3", "sentinel_nw_lag3_control", controlNwStd(new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 3));
        emit("G3", "sentinel_t1_hac", FactorResearchEngine.neweyWestHacStd(box(new double[]{0.1})));
        double[] t1ci = FactorResearchEngine.blockBootstrapCi(box(new double[]{0.1}), 2, 1000, BOOT_SEED);
        emit("G3", "sentinel_t1_ci_lo", t1ci[0]);
        emit("G3", "sentinel_t1_ci_hi", t1ci[1]);
        // 与既有测试 FactorResearchEngineTest.neweyWestHacStdHandlesIndependentSeries 同输入（:146）
        double[] ex = {0.1, -0.05, 0.08, -0.03, 0.12, -0.07, 0.09, -0.02, 0.11, -0.04};
        emit("G3", "ex_test_nw_prod", FactorResearchEngine.neweyWestHacStd(box(ex)));
        emit("G3", "ex_test_nw_expected", Math.sqrt(varPop(ex) / ex.length));
        emit("G3", "ex_test_nw_diff",
                Math.abs(FactorResearchEngine.neweyWestHacStd(box(ex)) - Math.sqrt(varPop(ex) / ex.length)));
    }

    // ===================== V01 (A1a) ci_* 与 normal-approx 的差异 =====================

    static void v01() {
        double[][] panels = {panelSeries17(), panelSeries10()};
        int[] hold = {5, 3};
        for (int i = 0; i < panels.length; i++) {
            double[] x = panels[i];
            int h = hold[i];
            double m = mean(x);
            double se = FactorResearchEngine.neweyWestHacStd(box(x));
            double[] ci = FactorResearchEngine.blockBootstrapCi(box(x), h, BOOT_ITERS, BOOT_SEED);
            double nlo = m - Z95 * se;
            double nhi = m + Z95 * se;
            emitIdx("V01", "T", i, x.length);
            emitIdx("V01", "holding", i, h);
            emitIdx("V01", "mean", i, m);
            emitIdx("V01", "hac_std", i, se);
            emitIdx("V01", "ci_lower", i, ci[0]);
            emitIdx("V01", "ci_upper", i, ci[1]);
            emitIdx("V01", "normal_lo", i, nlo);
            emitIdx("V01", "normal_hi", i, nhi);
            emitIdx("V01", "abs_diff_lo", i, Math.abs(ci[0] - nlo));
            emitIdx("V01", "abs_diff_hi", i, Math.abs(ci[1] - nhi));
            emitIdx("V01", "ci_width", i, ci[1] - ci[0]);
            emitIdx("V01", "normal_width", i, nhi - nlo);
            emitIdx("V01", "ci_contains_mean", i, (ci[0] <= m && m <= ci[1]) ? 1 : 0);
        }
    }

    // ===================== V03 (A2a) T = 1 的退化载荷 =====================

    static Map<String, List<FactorResearchEngine.CrossSectionObservation>> oneDayPanel() {
        // 分数 1..10；forward return 为「近反向 + 两处交换」的排列 ⟹ IC ≠ 0
        double[] scores = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] rets = {0.10, 0.09, 0.07, 0.08, 0.06, 0.05, 0.04, 0.03, 0.02, 0.01};
        List<FactorResearchEngine.CrossSectionObservation> obs = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            obs.add(new FactorResearchEngine.CrossSectionObservation(scores[i], rets[i]));
        }
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = new LinkedHashMap<>();
        byDate.put("2026-05-19", obs);
        return byDate;
    }

    static void v03() {
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = oneDayPanel();
        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(byDate, 5, 5, 10, "probe-note");
        emit("V03", "input_dates", byDate.size());
        emit("V03", "input_obs_on_that_day", byDate.values().iterator().next().size());
        emit("V03", "cross_section_days", s.crossSectionDays());
        emit("V03", "total_observations", s.totalObservations());
        emit("V03", "daily_size", s.dailyRankIc().size());
        emit("V03", "rank_ic_mean", s.rankIcMean());
        emit("V03", "rank_ic_std", s.rankIcStd());
        emit("V03", "rank_ic_ir", s.rankIcIr());
        emit("V03", "hac_std", s.hacStd());
        emit("V03", "ci_lower", s.ciLower());
        emit("V03", "ci_upper", s.ciUpper());
        emit("V03", "ci_width", s.ciUpper() - s.ciLower());
        emit("V03", "ci_is_zero_width", (s.ciUpper() - s.ciLower()) == 0.0 ? 1 : 0);
        emit("V03", "ci_contains_mean", (s.ciLower() <= s.rankIcMean() && s.rankIcMean() <= s.ciUpper()) ? 1 : 0);
        emit("V03", "mean_is_nonzero", Math.abs(s.rankIcMean()) > 1e-9 ? 1 : 0);
        emit("V03", "engine_record_has_universe_note", s.universeNote() != null ? 1 : 0);
        emit("V03", "engine_record_field_names",
                String.join(",", "holdingDays", "groupCount", "crossSectionDays", "totalObservations",
                        "rankIcMean", "rankIcStd", "rankIcIr", "rankIcPositiveRate", "pearsonIcMean",
                        "monotonicity", "hacStd", "ciLower", "ciUpper", "dailyRankIc", "groupedReturns",
                        "twoLongLegSpread", "universeNote"));
    }

    // ===================== V04 (A2b) 三条退化路径（引擎侧）=====================

    static void v04() {
        FactorResearchEngine.FactorResearchSummary e =
                FactorResearchEngine.summarize(new LinkedHashMap<>(), 5, 5, 10, "probe-empty-note");
        emit("V04", "empty_cross_section_days", e.crossSectionDays());
        emit("V04", "empty_total_observations", e.totalObservations());
        emit("V04", "empty_hac_std", e.hacStd());
        emit("V04", "empty_ci_lower", e.ciLower());
        emit("V04", "empty_ci_upper", e.ciUpper());
        emit("V04", "empty_rank_ic_mean", e.rankIcMean());
        emit("V04", "empty_monotonicity", e.monotonicity());
        emit("V04", "empty_universe_note", e.universeNote());
        // 两日但只有一日合格（另一日观测数 < 10）⟹ 与「全空」在输出上是否可区分
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = oneDayPanel();
        byDate.put("2026-05-20", List.of(
                new FactorResearchEngine.CrossSectionObservation(1.0, 0.01),
                new FactorResearchEngine.CrossSectionObservation(2.0, 0.02)));
        FactorResearchEngine.FactorResearchSummary p =
                FactorResearchEngine.summarize(byDate, 5, 5, 10, "probe-partial-note");
        emit("V04", "partial_input_dates", byDate.size());
        emit("V04", "partial_cross_section_days", p.crossSectionDays());
        emit("V04", "partial_hac_std", p.hacStd());
        emit("V04", "partial_ci_lower", p.ciLower());
        emit("V04", "partial_ci_upper", p.ciUpper());
    }

    // ===================== V05 (A2c) 钳位发生率 =====================

    static void v05() {
        int cell = 0;
        for (int n : NS) {
            for (String dgp : DGPS) {
                int zeros = 0;
                int varPos = 0;
                int sePositive = 0;
                for (int r = 0; r < REPS_CLAMP; r++) {
                    long seed = BASE_SEED + 1000000L * (n * 100 + cell) + r;
                    double[] x = dgp(dgp, n, 5, seed);
                    double v = varPop(x);
                    double se = FactorResearchEngine.neweyWestHacStd(box(x));
                    if (v > 0) {
                        varPos++;
                        if (se == 0.0) {
                            zeros++;
                        } else {
                            sePositive++;
                        }
                    }
                }
                emitIdx("V05", "n", cell, n);
                emitIdx("V05", "dgp", cell, dgp);
                emitIdx("V05", "reps", cell, REPS_CLAMP);
                emitIdx("V05", "var_positive_reps", cell, varPos);
                emitIdx("V05", "clamp_zero_count", cell, zeros);
                emitIdx("V05", "clamp_rate", cell, varPos == 0 ? 0.0 : (double) zeros / varPos);
                emitIdx("V05", "se_positive_count", cell, sePositive);
                cell++;
            }
        }
        emit("V05", "cells", cell);
    }

    // ===================== V07 (A3a) 签名事实（反射）=====================

    static void v07() throws Exception {
        Method nw = null;
        Method bb = null;
        Method sm = null;
        for (Method m : FactorResearchEngine.class.getDeclaredMethods()) {
            if ("neweyWestHacStd".equals(m.getName())) {
                nw = m;
            }
            if ("blockBootstrapCi".equals(m.getName())) {
                bb = m;
            }
            if ("summarize".equals(m.getName())) {
                sm = m;
            }
        }
        emit("V07", "nw_found", nw != null ? 1 : 0);
        emit("V07", "nw_param_count", nw == null ? -1 : nw.getParameterCount());
        if (nw != null) {
            StringBuilder sb = new StringBuilder();
            boolean integral = false;
            for (Class<?> c : nw.getParameterTypes()) {
                sb.append(c.getName()).append(' ');
                if (c == int.class || c == long.class || c == Integer.class || c == Long.class) {
                    integral = true;
                }
            }
            emit("V07", "nw_param_types", sb.toString().trim());
            emit("V07", "nw_has_integral_param", integral ? 1 : 0);
        }
        emit("V07", "bb_found", bb != null ? 1 : 0);
        emit("V07", "bb_param_count", bb == null ? -1 : bb.getParameterCount());
        if (bb != null) {
            StringBuilder sb = new StringBuilder();
            boolean integral = false;
            for (Class<?> c : bb.getParameterTypes()) {
                sb.append(c.getName()).append(' ');
                if (c == int.class || c == long.class || c == Integer.class || c == Long.class) {
                    integral = true;
                }
            }
            emit("V07", "bb_param_types", sb.toString().trim());
            emit("V07", "bb_has_integral_param", integral ? 1 : 0);
        }
        emit("V07", "summarize_param_count", sm == null ? -1 : sm.getParameterCount());
        // 同一序列上：生产实现 vs cbrt 滞后对照（逐位）
        double[] x = {0.10, 0.12, 0.08, 0.11, 0.09, 0.13, 0.07, 0.10, 0.12, 0.08};
        double prod = FactorResearchEngine.neweyWestHacStd(box(x));
        emit("V07", "panel_prod", prod);
        emit("V07", "panel_ctrl_cbrt", controlNwStd(x, cbrtLag(x.length)));
        emit("V07", "panel_ctrl_lag1", controlNwStd(x, 1));
        emit("V07", "panel_ctrl_lag2", controlNwStd(x, 2));
        emit("V07", "panel_ctrl_lag3", controlNwStd(x, 3));
    }

    // ===================== V08 (A3b) / V09 (A3c) 滞后窗口网格 =====================

    static void v08_v09() {
        int cell = 0;
        for (int n : NS) {
            for (String dgp : DGPS) {
                for (int h : HS_EXTRAP) {
                    int lagCbrt = cbrtLag(n);
                    int lagHm1 = Math.max(1, Math.min(h - 1, n - 1));
                    double[] prodS = new double[REPS_MEDIAN];
                    double[] cbrtS = new double[REPS_MEDIAN];
                    double[] hm1S = new double[REPS_MEDIAN];
                    for (int r = 0; r < REPS_MEDIAN; r++) {
                        long seed = BASE_SEED + 2000000L + 100000L * n + 1000L * h + r;
                        double[] x = dgp(dgp, n, h, seed);
                        prodS[r] = FactorResearchEngine.neweyWestHacStd(box(x));
                        cbrtS[r] = controlNwStd(x, lagCbrt);
                        hm1S[r] = controlNwStd(x, lagHm1);
                    }
                    java.util.Arrays.sort(prodS);
                    java.util.Arrays.sort(cbrtS);
                    java.util.Arrays.sort(hm1S);
                    double medProd = prodS[REPS_MEDIAN / 2];
                    double medCbrt = cbrtS[REPS_MEDIAN / 2];
                    double medHm1 = hm1S[REPS_MEDIAN / 2];
                    emitIdx("V08", "n", cell, n);
                    emitIdx("V08", "dgp", cell, dgp);
                    emitIdx("V08", "h", cell, h);
                    emitIdx("V08", "reps", cell, REPS_MEDIAN);
                    emitIdx("V08", "dom_cbrt", cell, lagCbrt);
                    emitIdx("V08", "dom_hm1", cell, lagHm1);
                    emitIdx("V08", "prod", cell, medProd);
                    emitIdx("V08", "ctrl_cbrt", cell, medCbrt);
                    emitIdx("V08", "abs_diff_cbrt", cell, Math.abs(medProd - medCbrt));
                    emitIdx("V09", "prod", cell, medProd);
                    emitIdx("V09", "ctrl_hm1", cell, medHm1);
                    emitIdx("V09", "ratio_cbrt_over_hm1", cell, medHm1 == 0.0 ? -1.0 : medProd / medHm1);
                    emitIdx("V09", "hm1_gt_cbrt", cell, lagHm1 > lagCbrt ? 1 : 0);
                    cell++;
                }
            }
        }
        emit("V08", "cells", cell);
        emit("V09", "cells", cell);
    }

    // ===================== V11 (A4a) 块长识别 =====================

    static void v11() {
        double[] panels = panelSeries10();
        int[] hold = {1, 2, 3, 5, 10};
        for (int i = 0; i < hold.length; i++) {
            int h = hold[i];
            double[] ci = FactorResearchEngine.blockBootstrapCi(box(panels), h, BOOT_ITERS, BOOT_SEED);
            emitIdx("V11", "h", i, h);
            emitIdx("V11", "n", i, panels.length);
            emitIdx("V11", "prod_ci_lo", i, ci[0]);
            emitIdx("V11", "prod_ci_hi", i, ci[1]);
            emitIdx("V11", "prod_ci_width", i, ci[1] - ci[0]);
            emitIdx("V11", "rule_expected_bs", i, effectiveBlockSize(panels.length, h));
            StringBuilder matched = new StringBuilder();
            for (int bs = 1; bs <= panels.length - 1; bs++) {
                double[] c = controlBlockCi(panels, bs, BOOT_ITERS, BOOT_SEED);
                emitIdx("V11", "ctrl_ci_width_bs" + bs, i, c[1] - c[0]);
                if (Math.abs(c[0] - ci[0]) < 1e-15 && Math.abs(c[1] - ci[1]) < 1e-15) {
                    if (matched.length() > 0) {
                        matched.append(',');
                    }
                    matched.append(bs);
                }
            }
            emitIdx("V11", "matched_bs", i, matched.length() == 0 ? "none" : matched.toString());
        }
    }

    // ===================== V13 (A5a) 尺度不变式 =====================

    static void v13() {
        // 面板 0/1 = 项目固定序列（T=10 / T=17）；面板 2 = 冻结 DGP 上的 i.i.d. T=27（§7 CLN-3 用）
        double[] iid27 = dgp("IID_NORMAL", 27, 1, BASE_SEED + 91L);
        double[][] panels = {panelSeries10(), panelSeries17(), iid27};
        String[] kinds = {"fixed_panel", "fixed_panel", "dgp_IID_NORMAL_T27"};
        for (int i = 0; i < panels.length; i++) {
            double[] x = panels[i];
            double prod = FactorResearchEngine.neweyWestHacStd(box(x));
            double sd = Math.sqrt(varPop(x));
            double expected = sd / Math.sqrt(x.length);
            emitIdx("V13", "T", i, x.length);
            emitIdx("V13", "kind", i, kinds[i]);
            emitIdx("V13", "prod", i, prod);
            emitIdx("V13", "rank_ic_std", i, sd);
            emitIdx("V13", "rank_ic_std_over_sqrtT", i, expected);
            emitIdx("V13", "rank_ic_std_over_sqrtTm1", i, sd / Math.sqrt(x.length - 1));
            emitIdx("V13", "abs_diff", i, Math.abs(prod - expected));
            emitIdx("V13", "rel_diff", i, expected == 0 ? 0.0 : Math.abs(prod - expected) / expected);
        }
        // 与既有测试 FactorResearchEngineTest:145-153 完全同输入
        double[] ex = {0.1, -0.05, 0.08, -0.03, 0.12, -0.07, 0.09, -0.02, 0.11, -0.04};
        double prodEx = FactorResearchEngine.neweyWestHacStd(box(ex));
        double expEx = Math.sqrt(varPop(ex) / ex.length);
        emit("V13", "ex_test_prod", prodEx);
        emit("V13", "ex_test_expected", expEx);
        emit("V13", "ex_test_abs_diff", Math.abs(prodEx - expEx));
        emit("V13", "ex_test_offset_in_existing_test", 0.01);
    }

    // ===================== V14 (A5b) 两个 std 的尺度比 =====================

    static void v14() {
        double[][] panels = {panelSeries10(), panelSeries17()};
        for (int i = 0; i < panels.length; i++) {
            double[] x = panels[i];
            double prod = FactorResearchEngine.neweyWestHacStd(box(x));
            double sd = Math.sqrt(varPop(x));
            emitIdx("V14", "T", i, x.length);
            emitIdx("V14", "rank_ic_std", i, sd);
            emitIdx("V14", "hac_std", i, prod);
            emitIdx("V14", "ratio_rank_std_over_hac_std", i, prod == 0.0 ? -1.0 : sd / prod);
            emitIdx("V14", "inv_sqrt_T", i, 1.0 / Math.sqrt(x.length));
        }
    }

    // ===================== V15 (A5c) 覆盖率模拟 =====================

    static void v15() {
        int cell = 0;
        for (int n : NS) {
            for (int h : HS) {
                for (String dgp : DGPS) {
                    int okBoot = 0;
                    int okHac = 0;
                    int zeroWidthBoot = 0;
                    int seZero = 0;
                    for (int r = 0; r < REPS_COVER; r++) {
                        long seed = BASE_SEED + 3000000L + 100000L * n + 1000L * h + r;
                        double[] x = dgp(dgp, n, h, seed);
                        List<Double> boxed = box(x);
                        double m = mean(x);
                        double[] ci = FactorResearchEngine.blockBootstrapCi(boxed, Math.max(1, h),
                                BOOT_ITERS, BOOT_SEED);
                        if (ci[0] <= 0.0 && 0.0 <= ci[1]) {
                            okBoot++;
                        }
                        if (ci[1] - ci[0] == 0.0) {
                            zeroWidthBoot++;
                        }
                        double se = FactorResearchEngine.neweyWestHacStd(boxed);
                        if (se == 0.0) {
                            seZero++;
                        }
                        double lo = m - Z95 * se;
                        double hi = m + Z95 * se;
                        if (lo <= 0.0 && 0.0 <= hi) {
                            okHac++;
                        }
                    }
                    emitIdx("V15", "n", cell, n);
                    emitIdx("V15", "h", cell, h);
                    emitIdx("V15", "dgp", cell, dgp);
                    emitIdx("V15", "reps", cell, REPS_COVER);
                    emitIdx("V15", "mu_true", cell, 0.0);
                    emitIdx("V15", "arm", cell, "BOOT_CI");
                    emitIdx("V15", "ok_count", cell, okBoot);
                    emitIdx("V15", "coverage", cell, (double) okBoot / REPS_COVER);
                    emitIdx("V15", "zero_width_count", cell, zeroWidthBoot);
                    emitIdx("V15", "arm2", cell, "HAC_CI");
                    emitIdx("V15", "ok_count2", cell, okHac);
                    emitIdx("V15", "coverage2", cell, (double) okHac / REPS_COVER);
                    emitIdx("V15", "se_zero_count", cell, seZero);
                    cell++;
                }
            }
        }
        emit("V15", "cells", cell);
    }

    // ===================== V17 (A6b) 登记性 t 对照 =====================

    static void v17() {
        double[][] panels = {panelSeries17(), panelSeries10()};
        for (int i = 0; i < panels.length; i++) {
            double[] x = panels[i];
            double m = mean(x);
            double se = FactorResearchEngine.neweyWestHacStd(box(x));
            emitIdx("V17", "T", i, x.length);
            emitIdx("V17", "mean", i, m);
            emitIdx("V17", "hac_std", i, se);
            emitIdx("V17", "t_hac", i, se == 0.0 ? Double.NaN : m / se);
            emitIdx("V17", "hurdle_1_96", i, 1.96);
            emitIdx("V17", "hurdle_3_0", i, 3.0);
            emitIdx("V17", "hurdle_3_9", i, 3.9);
        }
        emit("V17", "registration_only", 1);
    }

    // ===================== V18 (A7a) 确定性 =====================

    static void v18() {
        double[] x = panelSeries17();
        double[] a = FactorResearchEngine.blockBootstrapCi(box(x), 5, BOOT_ITERS, BOOT_SEED);
        double[] b = FactorResearchEngine.blockBootstrapCi(box(x), 5, BOOT_ITERS, BOOT_SEED);
        double sa = FactorResearchEngine.neweyWestHacStd(box(x));
        double sb = FactorResearchEngine.neweyWestHacStd(box(x));
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate = oneDayPanel();
        FactorResearchEngine.FactorResearchSummary s1 = FactorResearchEngine.summarize(byDate, 5, 5, 10, "n1");
        FactorResearchEngine.FactorResearchSummary s2 = FactorResearchEngine.summarize(byDate, 5, 5, 10, "n1");
        emit("V18", "ci_a_lo", a[0]);
        emit("V18", "ci_a_hi", a[1]);
        emit("V18", "ci_b_lo", b[0]);
        emit("V18", "ci_b_hi", b[1]);
        emit("V18", "ci_identical", (a[0] == b[0] && a[1] == b[1]) ? 1 : 0);
        emit("V18", "hac_a", sa);
        emit("V18", "hac_b", sb);
        emit("V18", "hac_identical", sa == sb ? 1 : 0);
        emit("V18", "summarize_ci_identical",
                (s1.ciLower() == s2.ciLower() && s1.ciUpper() == s2.ciUpper()) ? 1 : 0);
        emit("V18", "summarize_hac_identical", s1.hacStd() == s2.hacStd() ? 1 : 0);
    }

    // ===================== post-hoc 补充测量（prereg §4：命名 posthoc_*，不参与 total verdict）=====

    /**
     * post-hoc（V09p）：同一批抽样（同种子）上，把 A3c 的统计量从「中位数之比」改成
     * 「逐次重复的配对比值再取中位数」。仅作补充披露，**不参与任何判定**。
     */
    static void posthoc_v09p() {
        int cell = 0;
        for (int n : NS) {
            for (String dgp : DGPS) {
                for (int h : HS_EXTRAP) {
                    int lagHm1 = Math.max(1, Math.min(h - 1, n - 1));
                    double[] ratios = new double[REPS_MEDIAN];
                    int lt1 = 0;
                    int usable = 0;
                    for (int r = 0; r < REPS_MEDIAN; r++) {
                        long seed = BASE_SEED + 2000000L + 100000L * n + 1000L * h + r;
                        double[] x = dgp(dgp, n, h, seed);
                        double a = FactorResearchEngine.neweyWestHacStd(box(x));
                        double b = controlNwStd(x, lagHm1);
                        if (b != 0.0) {
                            ratios[r] = a / b;
                            usable++;
                            if (a / b < 1.0) {
                                lt1++;
                            }
                        } else {
                            ratios[r] = 1.0;
                        }
                    }
                    double[] s = ratios.clone();
                    java.util.Arrays.sort(s);
                    emitIdx("V09p", "n", cell, n);
                    emitIdx("V09p", "dgp", cell, dgp);
                    emitIdx("V09p", "h", cell, h);
                    emitIdx("V09p", "reps", cell, REPS_MEDIAN);
                    emitIdx("V09p", "ratio_paired_median", cell, s[REPS_MEDIAN / 2]);
                    emitIdx("V09p", "frac_ratio_lt_1", cell, usable == 0 ? -1.0 : (double) lt1 / usable);
                    cell++;
                }
            }
        }
        emit("V09p", "cells", cell);
    }

    /**
     * post-hoc（V05p）：钳位（hacVar<0 ⟹ SE=0）在**负**自相关 DGP 上的发生率。
     * 冻结网格只含正自相关 DGP ⟹ 本测量是网格外补充，**不参与任何判定**。
     */
    static void posthoc_v05p() {
        String[] dgps = {"AR1_-0.3", "AR1_-0.5", "AR1_-0.7"};
        int cell = 0;
        for (int n : NS) {
            for (int di = 0; di < dgps.length; di++) {
                String dgp = dgps[di];
                int zeros = 0;
                int varPos = 0;
                for (int r = 0; r < REPS_CLAMP; r++) {
                    long seed = BASE_SEED + 4000000L + 1000000L * n + 100000L * di + r;
                    double[] x = dgp(dgp, n, 5, seed);
                    if (varPop(x) > 0) {
                        varPos++;
                        if (FactorResearchEngine.neweyWestHacStd(box(x)) == 0.0) {
                            zeros++;
                        }
                    }
                }
                emitIdx("V05p", "n", cell, n);
                emitIdx("V05p", "dgp", cell, dgp);
                emitIdx("V05p", "reps", cell, REPS_CLAMP);
                emitIdx("V05p", "var_positive_reps", cell, varPos);
                emitIdx("V05p", "clamp_zero_count", cell, zeros);
                emitIdx("V05p", "clamp_rate", cell, varPos == 0 ? 0.0 : (double) zeros / varPos);
                cell++;
            }
        }
        emit("V05p", "cells", cell);
    }

    // ===================== main =====================

    public static void main(String[] args) throws Exception {
        g3();
        v01();
        v03();
        v04();
        v05();
        v07();
        v08_v09();
        v11();
        v13();
        v14();
        v15();
        v17();
        v18();
        posthoc_v05p();
        posthoc_v09p();
    }
}
