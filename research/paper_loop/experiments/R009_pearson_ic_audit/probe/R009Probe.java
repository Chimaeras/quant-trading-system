package com.quant.service.backtest;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R009 审计探针（Java 侧）—— Pearson 侧 IC 载荷族
 * （{@code pearson_ic_mean} / {@code pearsonCorrelation} / {@code dailyPearson}）。
 *
 * <p>唯一职责：<b>调用仓库自己的</b> {@link FactorResearchEngine} 与
 * {@code com.quant.service.FactorResearchService}（由
 * {@code -cp modules/backend-java/target/classes} 加载），把输入与输出按 flat JSON line
 * 打到 stdout，供 {@code run_pearson_audit.py} 判定。
 *
 * <p>本类<b>不重新实现</b>生产量：
 * <ul>
 *   <li>相关系数一律来自 {@code FactorResearchEngine.pearsonCorrelation} /
 *       {@code spearmanRankCorrelation}（{@code public static}，直接调用）；</li>
 *   <li>载荷一律来自 {@code FactorResearchService} 的私有 block builder（反射调用，
 *       观测<b>真实发射</b>的键集与占位值）；</li>
 *   <li>「Pearson 支撑天数」用<b>同一组生产函数</b>在同一面板上逐日复算有限性得到
 *       （仍是对生产函数的调用，不是重实现）。</li>
 * </ul>
 *
 * <p>探针里自行实现的只有<b>对照侧</b>的量（一律只作对照值、与被审计量同输入并列打印）：
 * <ul>
 *   <li>{@code controlRhoAffine = 1.0}（A 档：Pearson ρ 对正仿射变换不变，本面板真值就是 1.0）；</li>
 *   <li>{@code controlPearsonVariance(x) = Σ(x−mean(x))²}（A 档：生产守卫实际比较的<b>未归一化</b>平方和）；</li>
 *   <li>{@code controlRankVariance(n) = Σ(rank−mean(rank))²}（A 档：秩空间的同型量）。</li>
 * </ul>
 *
 * <p>不连数据库：全部输入为构造面板；A3c 的只读普查由 Python 侧编排器单独执行。
 */
public final class R009Probe {

    // ===================== 冻结常数（R009_prereg.md §4 §5）=====================

    /** 生产常量 RANK_IC_MIN_CROSS_SECTION_SIZE —— 每日样本数低于此值整日被 summarize 跳过。 */
    static final int N_PANEL_MIN = 10;
    /** summarize 的入参（与生产调用点一致：holdingDays=RANK_IC_HOLDING_DAYS、groups=RANK_IC_GROUP_COUNT）。 */
    static final int HOLDING = 5;
    static final int GROUPS = 5;
    /** A2a 的缩放/平移因子（P 档自设量级线，非阈值）。 */
    static final double SCALE_C = 1e-10;
    static final double SHIFT_B = 1000.0;
    /** A2d 的 ±20% 网格。 */
    static final double[] SCALE_GRID = {8e-11, 1e-10, 1.2e-10};
    static final int[] N_GRID = {8, 9, 10, 11, 12};

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

    private static void emitList(String probe, String key, List<?> values) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"probe\":\"").append(esc(probe)).append("\",\"key\":\"").append(esc(key))
                .append("\",\"items\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(esc(String.valueOf(values.get(i)))).append('"');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ===================== 面板构造 =====================

    /** [1..n]。 */
    private static List<Double> seq(int n) {
        List<Double> xs = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            xs.add((double) i);
        }
        return xs;
    }

    /** [0.01, 0.02, ... , 0.01*n] —— 与既有测试 pearsonCorrelationReturnsOneForPerfectLinear 同型。 */
    private static List<Double> rets01(int n) {
        List<Double> xs = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            xs.add(0.01 * i);
        }
        return xs;
    }

    /** [1,2,3,4,5,5,4,3,2,1]（n=10，关于均值对称 ⟹ Σdev_x·dev_y = 0 精确）。 */
    private static List<Double> retsSym10() {
        List<Double> xs = new ArrayList<>(10);
        for (double v : new double[] {1, 2, 3, 4, 5, 5, 4, 3, 2, 1}) {
            xs.add(v);
        }
        return xs;
    }

    private static List<Double> scaled(List<Double> xs, double c) {
        List<Double> out = new ArrayList<>(xs.size());
        for (Double x : xs) {
            out.add(x * c);
        }
        return out;
    }

    private static List<Double> shifted(List<Double> xs, double b) {
        List<Double> out = new ArrayList<>(xs.size());
        for (Double x : xs) {
            out.add(x + b);
        }
        return out;
    }

    /** A 档对照：未归一化平方和 Σ(x−mean)²（= 生产守卫实际比较的量）。 */
    private static double controlVariance(List<Double> xs) {
        double m = 0;
        for (Double x : xs) {
            m += x;
        }
        m /= xs.size();
        double v = 0;
        for (Double x : xs) {
            v += (x - m) * (x - m);
        }
        return v;
    }

    /** A 档对照：秩空间的未归一化平方和（恒为 n(n²−1)/12，与输入量纲无关）。 */
    private static double controlRankVariance(int n) {
        return n * ((double) n * n - 1) / 12.0;
    }

    /** 把观测列表装成 summarize 的入参。 */
    private static Map<String, List<FactorResearchEngine.CrossSectionObservation>> obsMap(
            String date, List<Double> scores, List<Double> rets) {
        List<FactorResearchEngine.CrossSectionObservation> obs = new ArrayList<>(scores.size());
        for (int i = 0; i < scores.size(); i++) {
            obs.add(new FactorResearchEngine.CrossSectionObservation(scores.get(i), rets.get(i)));
        }
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> m = new LinkedHashMap<>();
        m.put(date, obs);
        return m;
    }

    private static void putAll(
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> m,
            String date, List<Double> scores, List<Double> rets) {
        List<FactorResearchEngine.CrossSectionObservation> obs = new ArrayList<>(scores.size());
        for (int i = 0; i < scores.size(); i++) {
            obs.add(new FactorResearchEngine.CrossSectionObservation(scores.get(i), rets.get(i)));
        }
        m.put(date, obs);
    }

    // ===================== 规范序列化（A4a 的载荷不可区分性比对）=====================

    private static String canonical(Object o) {
        StringBuilder sb = new StringBuilder(512);
        canon(sb, o);
        return sb.toString();
    }

    private static void canon(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(e.getKey()).append('=');
                canon(sb, e.getValue());
                sb.append(';');
            }
            sb.append('}');
        } else if (o instanceof List<?> l) {
            sb.append('[');
            for (Object x : l) {
                canon(sb, x);
                sb.append(',');
            }
            sb.append(']');
        } else if (o instanceof Double d) {
            sb.append(Double.toString(d));
        } else {
            sb.append(o.getClass().getSimpleName()).append(':').append(o);
        }
    }

    // ===================== 反射：FactorResearchService 的私有 block builder =====================

    private static Object service;
    private static Method mSnapshot;
    private static Method mUnavailable;
    private static Method mEmptyNet;
    private static Method mNet;

    private static void initService() throws Exception {
        Class<?> cls = Class.forName("com.quant.service.FactorResearchService");
        // @RequiredArgsConstructor 生成 9 参数全对象构造器（prereg F15）：全部传 null，
        // 只为调用不触碰任何依赖的私有 block builder。
        java.lang.reflect.Constructor<?>[] ctors = cls.getDeclaredConstructors();
        java.lang.reflect.Constructor<?> ctor = null;
        for (java.lang.reflect.Constructor<?> c : ctors) {
            if (c.getParameterCount() == 9) {
                ctor = c;
                break;
            }
        }
        if (ctor == null) {
            throw new IllegalStateException("未找到 FactorResearchService 的 9 参数构造器（prereg F15 失效）");
        }
        ctor.setAccessible(true);
        Object[] nulls = new Object[9];
        service = ctor.newInstance(nulls);
        mSnapshot = cls.getDeclaredMethod("buildSnapshotScopeBlock", String.class, String.class,
                int.class, Map.class);
        mSnapshot.setAccessible(true);
        mUnavailable = cls.getDeclaredMethod("unavailableScopeBlock", String.class, String.class);
        mUnavailable.setAccessible(true);
        mEmptyNet = cls.getDeclaredMethod("emptyNetIcBlock");
        mEmptyNet.setAccessible(true);
        mNet = cls.getDeclaredMethod("buildNetIcBlock", FactorResearchEngine.FactorResearchSummary.class);
        mNet.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> callSnapshot(
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> m) throws Exception {
        return (Map<String, Object>) mSnapshot.invoke(service, "RAW", "R009-probe-basis", HOLDING, m);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> callUnavailable() throws Exception {
        return (Map<String, Object>) mUnavailable.invoke(service, "EXECUTABLE", "R009-probe-basis");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> callEmptyNet() throws Exception {
        return (Map<String, Object>) mEmptyNet.invoke(service);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> callNet(FactorResearchEngine.FactorResearchSummary s) throws Exception {
        return (Map<String, Object>) mNet.invoke(service, s);
    }

    private static double round4(double v) throws Exception {
        Method m = Class.forName("com.quant.service.FactorResearchService")
                .getDeclaredMethod("round4", double.class);
        m.setAccessible(true);
        return (Double) m.invoke(null, v);
    }

    /**
     * Pearson 支撑天数：用**同一组生产函数**（{@code pearsonCorrelation} /
     * {@code spearmanRankCorrelation}）在同一面板上逐日复算有限性所得。
     * 这不是重实现——被调用的仍是生产静态方法，与 {@code summarize} 内部用的是同一份代码。
     */
    private static int pearsonSupportDays(
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> m) {
        int days = 0;
        for (Map.Entry<String, List<FactorResearchEngine.CrossSectionObservation>> e : m.entrySet()) {
            if (e.getValue().size() < N_PANEL_MIN) {
                continue;
            }
            List<Double> sc = new ArrayList<>();
            List<Double> rt = new ArrayList<>();
            for (FactorResearchEngine.CrossSectionObservation o : e.getValue()) {
                sc.add(o.score());
                rt.add(o.forwardReturn());
            }
            if (Double.isFinite(FactorResearchEngine.spearmanRankCorrelation(sc, rt))
                    && Double.isFinite(FactorResearchEngine.pearsonCorrelation(sc, rt))) {
                days++;
            }
        }
        return days;
    }

    private static void emitKeys(String probe, String prefix, Map<String, Object> block) {
        List<String> keys = new ArrayList<>(block.keySet());
        emitList(probe, prefix + "_keys_ordered", keys);
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        emitList(probe, prefix + "_keys_sorted", sorted);
        emit(probe, prefix + "_n_keys", keys.size());
        emit(probe, prefix + "_has_status", block.containsKey("status") ? 1 : 0);
        emit(probe, prefix + "_has_monotonicity", block.containsKey("monotonicity") ? 1 : 0);
        emit(probe, prefix + "_has_pearson_ic_mean", block.containsKey("pearson_ic_mean") ? 1 : 0);
        emit(probe, prefix + "_has_daily_rank_ic", block.containsKey("daily_rank_ic") ? 1 : 0);
        emit(probe, prefix + "_has_grouped_returns", block.containsKey("grouped_returns") ? 1 : 0);
        emit(probe, prefix + "_has_note", block.containsKey("note") ? 1 : 0);
        emit(probe, prefix + "_metric_scope", String.valueOf(block.get("metric_scope")));
    }

    // ===================== V01（A1a）探针自检 + 反向锚点 =====================

    private static void v01() {
        try {
            Object cs = FactorResearchEngine.class.getProtectionDomain().getCodeSource().getLocation();
            emit("V01", "engine_code_source", String.valueOf(cs));
            List<Double> s10 = seq(10);
            List<Double> r01 = rets01(10);
            double normal = FactorResearchEngine.pearsonCorrelation(s10, r01);
            double defect = FactorResearchEngine.pearsonCorrelation(scaled(s10, SCALE_C), r01);
            emit("V01", "sentinel_normal_rho", normal);
            emit("V01", "sentinel_defect_rho", defect);
            emit("V01", "sentinel_normal_equals",
                    (Double.isFinite(normal) && Math.abs(normal - 1.0) <= 1e-12) ? 1 : 0);
            emit("V01", "sentinel_defect_present", Double.isNaN(defect) ? 1 : 0);
            // 与既有测试同输入的一致性门（prereg §3.6.5）
            List<Double> s5 = seq(5);
            List<Double> r5 = rets01(5);
            double ex = FactorResearchEngine.pearsonCorrelation(s5, r5);
            emit("V01", "existing_test_input_rho", ex);
            emit("V01", "existing_test_input_ok",
                    (Double.isFinite(ex) && Math.abs(ex - 1.0) <= 1e-12) ? 1 : 0);
        } catch (Throwable t) {
            emit("V01", "error", t.toString());
        }
    }

    // ===================== V03（A2a）仿射尺度不变性 =====================

    private static void v03() {
        try {
            List<Double> s10 = seq(10);
            List<Double> r01 = rets01(10);
            double rhoP1 = FactorResearchEngine.pearsonCorrelation(s10, r01);
            double rhoP1s = FactorResearchEngine.pearsonCorrelation(scaled(s10, SCALE_C), r01);
            double rhoP1t = FactorResearchEngine.pearsonCorrelation(shifted(s10, SHIFT_B), r01);
            double rhoP1r = FactorResearchEngine.pearsonCorrelation(s10, scaled(r01, SCALE_C));
            double rankP1s = FactorResearchEngine.spearmanRankCorrelation(scaled(s10, SCALE_C), r01);
            double rankP1 = FactorResearchEngine.spearmanRankCorrelation(s10, r01);
            double varP1s = controlVariance(scaled(s10, SCALE_C));
            double varP1 = controlVariance(s10);
            emit("V03", "rho_p1", rhoP1);
            emit("V03", "rho_p1s", rhoP1s);
            emit("V03", "rho_p1t", rhoP1t);
            emit("V03", "rho_p1r", rhoP1r);
            emit("V03", "rank_p1", rankP1);
            emit("V03", "rank_p1s", rankP1s);
            emit("V03", "var_p1", varP1);
            emit("V03", "var_p1s", varP1s);
            emit("V03", "var_p1s_positive", varP1s > 0 ? 1 : 0);
            emit("V03", "control_rho_affine", 1.0);
            emit("V03", "control_rank_variance_n10", controlRankVariance(10));
            // 与守卫阈值的量级关系（披露用）
            emit("V03", "var_p1s_over_guard", varP1s / 1e-18);
        } catch (Throwable t) {
            emit("V03", "error", t.toString());
        }
    }

    // ===================== V04（A2b）固定相对离散度下的 n 依赖 =====================

    private static void v04() {
        try {
            for (int n : new int[] {10, 11, 12}) {
                List<Double> s = scaled(seq(n), SCALE_C);
                List<Double> r = seq(n);
                double rho = FactorResearchEngine.pearsonCorrelation(s, r);
                double rank = FactorResearchEngine.spearmanRankCorrelation(s, r);
                double var = controlVariance(s);
                String tag = "n" + n;
                emit("V04", "rho_" + tag, rho);
                emit("V04", "rank_" + tag, rank);
                emit("V04", "var_" + tag, var);
                emit("V04", "finite_" + tag, Double.isFinite(rho) ? 1 : 0);
                emit("V04", "var_over_guard_" + tag, var / 1e-18);
            }
        } catch (Throwable t) {
            emit("V04", "error", t.toString());
        }
    }

    // ===================== V06（A2d）±20% 敏感性网格（披露轴）=====================

    private static void v06() {
        try {
            int i = 0;
            for (double c : SCALE_GRID) {
                for (int n : N_GRID) {
                    List<Double> s = scaled(seq(n), c);
                    List<Double> r = seq(n);
                    double rho = FactorResearchEngine.pearsonCorrelation(s, r);
                    emitIdx("V06", "var", i, controlVariance(s));
                    emitIdx("V06", "rho", i, rho);
                    emitIdx("V06", "finite", i, Double.isFinite(rho) ? 1 : 0);
                    emitIdx("V06", "scale_c", i, c);
                    emitIdx("V06", "n", i, n);
                    i++;
                }
            }
            emit("V06", "cells", i);
        } catch (Throwable t) {
            emit("V06", "error", t.toString());
        }
    }

    // ===================== V07（A3a）混合支撑集面板 =====================

    private static void v07() {
        try {
            List<Double> s10 = seq(10);
            List<Double> r01 = rets01(10);
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> m = new LinkedHashMap<>();
            putAll(m, "2026-01-05", s10, r01);                       // Pearson 有限
            putAll(m, "2026-01-06", scaled(s10, SCALE_C), r01);       // rank 有限 / Pearson NaN
            FactorResearchEngine.FactorResearchSummary sum =
                    FactorResearchEngine.summarize(m, HOLDING, GROUPS, N_PANEL_MIN, "R009-universe");
            emit("V07", "cross_section_days", sum.crossSectionDays());
            emit("V07", "total_observations", sum.totalObservations());
            emit("V07", "daily_size", sum.dailyRankIc().size());
            emit("V07", "pearson_ic_mean", sum.pearsonIcMean());
            emit("V07", "monotonicity", sum.monotonicity());
            // Pearson 支撑天数：用**同一组生产函数**在同一面板上逐日复算有限性
            int pearsonDays = 0;
            int rankDays = 0;
            for (Map.Entry<String, List<FactorResearchEngine.CrossSectionObservation>> e : m.entrySet()) {
                List<Double> sc = new ArrayList<>();
                List<Double> rt = new ArrayList<>();
                for (FactorResearchEngine.CrossSectionObservation o : e.getValue()) {
                    sc.add(o.score());
                    rt.add(o.forwardReturn());
                }
                if (Double.isFinite(FactorResearchEngine.spearmanRankCorrelation(sc, rt))) {
                    rankDays++;
                }
                if (Double.isFinite(FactorResearchEngine.pearsonCorrelation(sc, rt))) {
                    pearsonDays++;
                }
            }
            emit("V07", "pearson_support_days", pearsonDays);
            emit("V07", "rank_support_days", rankDays);
            emit("V07", "support_gap", sum.crossSectionDays() - pearsonDays);
            // 载荷键集（含 pearson 支撑字段的存在性）
            Map<String, Object> block = callSnapshot(m);
            emitKeys("V07", "snapshot_normal", block);
        } catch (Throwable t) {
            emit("V07", "error", t.toString());
        }
    }

    // ===================== V10（A4a）不可定义 vs 真 0 的载荷不可区分性 =====================

    private static void v10() {
        try {
            List<Double> s10 = seq(10);
            List<Double> sym = retsSym10();
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> mz =
                    obsMap("2026-01-05", s10, sym);
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> mu =
                    obsMap("2026-01-05", scaled(s10, SCALE_C), sym);

            double rhoZ = FactorResearchEngine.pearsonCorrelation(s10, sym);
            double rhoU = FactorResearchEngine.pearsonCorrelation(scaled(s10, SCALE_C), sym);
            emit("V10", "rho_p4z", rhoZ);
            emit("V10", "rho_p4u", rhoU);
            emit("V10", "var_p4z", controlVariance(s10));
            emit("V10", "var_p4u", controlVariance(scaled(s10, SCALE_C)));

            FactorResearchEngine.FactorResearchSummary sz =
                    FactorResearchEngine.summarize(mz, HOLDING, GROUPS, N_PANEL_MIN, "R009-universe");
            FactorResearchEngine.FactorResearchSummary su =
                    FactorResearchEngine.summarize(mu, HOLDING, GROUPS, N_PANEL_MIN, "R009-universe");
            emit("V10", "rank_days_p4z", sz.dailyRankIc().size());
            emit("V10", "rank_days_p4u", su.dailyRankIc().size());
            emit("V10", "pearson_support_days_p4z", pearsonSupportDays(mz));
            emit("V10", "pearson_support_days_p4u", pearsonSupportDays(mu));
            emit("V10", "cross_days_p4z", sz.crossSectionDays());
            emit("V10", "cross_days_p4u", su.crossSectionDays());
            emit("V10", "total_obs_p4z", sz.totalObservations());
            emit("V10", "total_obs_p4u", su.totalObservations());
            emit("V10", "pearson_mean_p4z", sz.pearsonIcMean());
            emit("V10", "pearson_mean_p4u", su.pearsonIcMean());

            Map<String, Object> bz = callSnapshot(mz);
            Map<String, Object> bu = callSnapshot(mu);
            String cz = canonical(bz);
            String cu = canonical(bu);
            emit("V10", "payload_len_p4z", cz.length());
            emit("V10", "payload_len_p4u", cu.length());
            emit("V10", "payload_p4z", cz);
            emit("V10", "payload_p4u", cu);
        } catch (Throwable t) {
            emit("V10", "error", t.toString());
        }
    }

    // ===================== V11（A4b）round4 机制与 NaN 可达性 =====================

    private static void v11() {
        try {
            emit("V11", "round4_nan", round4(Double.NaN));
            emit("V11", "round4_pinf", round4(Double.POSITIVE_INFINITY));
            emit("V11", "round4_ninf", round4(Double.NEGATIVE_INFINITY));
            emit("V11", "round4_nan_is_zero", round4(Double.NaN) == 0.0 ? 1 : 0);
            // 可达性：全 Pearson 不可定义的面板，其发射的 pearson_ic_mean 是否仍为**有限**值
            List<Double> s10 = seq(10);
            List<Double> sym = retsSym10();
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> mu =
                    obsMap("2026-01-05", scaled(s10, SCALE_C), sym);
            Map<String, Object> bu = callSnapshot(mu);
            Object pm = bu.get("pearson_ic_mean");
            double pmv = pm instanceof Number ? ((Number) pm).doubleValue() : Double.NaN;
            emit("V11", "undef_pearson_key_value", pmv);
            emit("V11", "undef_pearson_key_is_nan", Double.isNaN(pmv) ? 1 : 0);
            emit("V11", "undef_pearson_key_is_finite", Double.isFinite(pmv) ? 1 : 0);
        } catch (Throwable t) {
            emit("V11", "error", t.toString());
        }
    }

    // ===================== V12 / V14 / V15 共享：4 个反射可达的 block 键集 =====================

    private static void v12v14v15() {
        try {
            List<Double> s10 = seq(10);
            List<Double> r01 = rets01(10);
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> mn =
                    obsMap("2026-01-05", s10, r01);
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> me = new LinkedHashMap<>();

            emitKeys("V12", "snapshot_obsy", callSnapshot(mn));
            emitKeys("V12", "snapshot_empty", callSnapshot(me));
            emitKeys("V12", "unavailable", callUnavailable());
            emitKeys("V12", "empty_net", callEmptyNet());
            FactorResearchEngine.FactorResearchSummary sn =
                    FactorResearchEngine.summarize(mn, HOLDING, GROUPS, N_PANEL_MIN, "R009-universe");
            emitKeys("V12", "net", callNet(sn));

            // V15（A6a）：各 block 的支撑集定义标签 + 是否存在结构化消歧字段
            Map<String, Object> snapshot = callSnapshot(mn);
            Map<String, Object> net = callNet(sn);
            Map<String, Object> emptyNet = callEmptyNet();
            for (String[] pair : new String[][] {
                    {"snapshot", "RAW"}, {"net", "COST_APPROX"}, {"empty_net", "COST_APPROX"}}) {
                String name = pair[0];
                Map<String, Object> b = switch (name) {
                    case "snapshot" -> snapshot;
                    case "net" -> net;
                    default -> emptyNet;
                };
                emit("V15", name + "_metric_scope", String.valueOf(b.get("metric_scope")));
                emit("V15", name + "_has_basis_note", b.containsKey("basis_note") ? 1 : 0);
                emit("V15", name + "_has_universe_note", b.containsKey("universe_note") ? 1 : 0);
                emit("V15", name + "_has_embargo_note", b.containsKey("embargo_note") ? 1 : 0);
            }
        } catch (Throwable t) {
            emit("V12", "error", t.toString());
        }
    }

    // ===================== V02（A1b 的确定性子检）同一输入同一顺序两次调用 =====================

    private static void v02() {
        try {
            // 两次调用**各自独立构造**输入列表（不同 List 实例），顺序相同
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> m1 =
                    obsMap("2026-01-05", seq(10), rets01(10));
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> m2 =
                    obsMap("2026-01-05", seq(10), rets01(10));
            FactorResearchEngine.FactorResearchSummary s1 =
                    FactorResearchEngine.summarize(m1, HOLDING, GROUPS, N_PANEL_MIN, "R009-universe");
            FactorResearchEngine.FactorResearchSummary s2 =
                    FactorResearchEngine.summarize(m2, HOLDING, GROUPS, N_PANEL_MIN, "R009-universe");
            emit("V02", "det_pearson_mean_1", s1.pearsonIcMean());
            emit("V02", "det_pearson_mean_2", s2.pearsonIcMean());
            emit("V02", "det_cross_days_1", s1.crossSectionDays());
            emit("V02", "det_cross_days_2", s2.crossSectionDays());
            emit("V02", "det_total_obs_1", s1.totalObservations());
            emit("V02", "det_total_obs_2", s2.totalObservations());
            emit("V02", "det_monotonicity_1", s1.monotonicity());
            emit("V02", "det_monotonicity_2", s2.monotonicity());
            emit("V02", "det_payload_1", canonical(callSnapshot(m1)));
            emit("V02", "det_payload_2", canonical(callSnapshot(m2)));
        } catch (Throwable t) {
            emit("V02", "error", t.toString());
        }
    }

    // ===================== main =====================

    public static void main(String[] args) throws Exception {
        try {
            initService();
            emit("HARNESS", "service_ctor_params", 9);
        } catch (Throwable t) {
            emit("HARNESS", "service_init_error", t.toString());
            return;
        }
        v01();
        v02();
        v03();
        v04();
        v06();
        v07();
        v10();
        v11();
        v12v14v15();
    }
}
