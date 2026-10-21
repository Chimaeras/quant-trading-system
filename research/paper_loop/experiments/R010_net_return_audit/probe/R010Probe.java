package com.quant.service.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.dto.ScoringBacktestRequest;
import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.entity.SelectorResultItem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R010 审计探针（Java 侧）—— 成本后净收益（COST_APPROX）声明–实现一致性。
 *
 * <p>唯一职责：<b>调用仓库自己的生产实现</b>，把输入与输出按 flat JSON line 打到 stdout，
 * 供 {@code run_net_return_audit.py} 判定。本类<b>不重新实现</b>被审计量：
 *
 * <ul>
 *   <li><b>PROD</b>：{@code ScoringBacktestService} 的 {@code resolveEntryContext} /
 *       {@code computePeriodReturn}（{@code private static}，反射调用）、{@code boundedRate} /
 *       {@code executionAssumptions}（{@code private} 实例方法，{@code Unsafe.allocateInstance}
 *       取无字段实例后反射调用）、{@code ResearchConvention} 常量与 {@code labelDefinition()}、
 *       {@code ScoringBacktestRequest} 字段默认值、{@code FactorResearchService}
 *       的 {@code emptyNetIcBlock} / {@code buildNetIcBlock}。</li>
 *   <li><b>GOLD</b>：探针内以 {@link BigDecimal}（{@code MathContext(60, HALF_EVEN)}）
 *       独立实现的<b>声明口径</b>与<b>实现口径</b>对照式。费率取自**运行时反射读到的**
 *       {@code ResearchConvention} 常量（不是硬编码字面量），故 V12 证明了常数一致 ⟹
 *       GOLD 与 PROD 用的是同一组费率。</li>
 * </ul>
 *
 * <p>GOLD 只作对照值：V03/V04/V05/V09/V17 的判定由「生产侧因子」与「声明侧因子」的实测差额
 * 共同决定，**不由**探针自报的布尔值决定（prereg §5.2 末条）。
 *
 * <p>不连数据库：全部输入为 {@code PRICE_GRID}（冻结表）；qp 侧 {@code qfqMap} 留空。
 */
public final class R010Probe {

    // ===================== 冻结常数（R010_prereg.md §5.4）=====================

    static final String CODE = "600000";
    static final String EX = "SH";
    static final String NAME = "测试股";
    static final long VOL = 1000000L;
    /** 冻结价表格：{date, open, close, preClose}，索引 0..7 ⟹ 退出日 close = 10 + idx。 */
    static final String[][] GRID = {
            {"2026-01-05", "9.5", "10", "9.5"},
            {"2026-01-06", "10.5", "11", "10"},
            {"2026-01-07", "11.5", "12", "11"},
            {"2026-01-08", "12.5", "13", "12"},
            {"2026-01-09", "13.5", "14", "13"},
            {"2026-01-12", "14.5", "15", "14"},
            {"2026-01-13", "15.5", "16", "15"},
            {"2026-01-14", "16.5", "17", "16"},
    };
    static final int BASE_IDX = 0;
    static final int[] HORIZONS = {2, 3};

    /** 冻结精度（prereg §3.1）：60 位有效数字、HALF_EVEN —— 与冻结时 Python `decimal` prec=60 一致。 */
    static final MathContext MC = new MathContext(60, RoundingMode.HALF_EVEN);

    /** V14 的 7 格钳位枚举（prereg §4 A7 逐字列出）。 */
    static final String[][] RATE_CELLS = {
            {"null", "0.0003"},
            {"NaN", "0.0003"},
            {"+Inf", "0.0003"},
            {"-1.0", "0.0"},
            {"0.06", "0.05"},
            {"0.0001", "0.0001"},
            {"0.05", "0.05"},
    };

    /** V17 的 ±20% OAT 网格（prereg §3.4，9 格，顺序冻结）。 */
    static final String[][] OAT_GRID = {
            {"c*0.8", "0.00024", "0.0005", "0.0002"},
            {"c*1.0", "0.0003", "0.0005", "0.0002"},
            {"c*1.2", "0.00036", "0.0005", "0.0002"},
            {"t*0.8", "0.0003", "0.00040", "0.0002"},
            {"t*1.0", "0.0003", "0.00050", "0.0002"},
            {"t*1.2", "0.0003", "0.00060", "0.0002"},
            {"s*0.8", "0.0003", "0.0005", "0.00016"},
            {"s*1.0", "0.0003", "0.0005", "0.00020"},
            {"s*1.2", "0.0003", "0.0005", "0.00024"},
    };

    // ===================== JSON lines =====================

    private static void emit(String probe, String key, Object value) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"probe\":\"").append(esc(probe)).append("\",\"key\":\"").append(esc(key)).append('"');
        appendValue(sb, value);
        sb.append('}');
        System.out.println(sb);
    }

    private static void emitList(String probe, String key, List<?> values) {
        StringBuilder sb = new StringBuilder(128);
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

    private static void appendValue(StringBuilder sb, Object value) {
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

    // ===================== 面板构造（每次调用**重新**构造，供 run#1/run#2 独立性）=====================

    static final class Panel {
        final List<LocalDate> days = new ArrayList<>(GRID.length);
        final Map<String, MarketPriceRaw> rawMap = new LinkedHashMap<>();
        final Map<String, MarketData> qfqMap = new LinkedHashMap<>();
        final SelectorResultItem item = new SelectorResultItem();
    }

    static String priceKey(String code, String ex, LocalDate d) {
        return code + "|" + ex + "|" + d;
    }

    private static Panel buildPanel() {
        Panel p = new Panel();
        for (String[] row : GRID) {
            LocalDate d = LocalDate.parse(row[0]);
            p.days.add(d);
            MarketPriceRaw rpm = new MarketPriceRaw();
            rpm.setStockCode(CODE);
            rpm.setExchangeType(EX);
            rpm.setTradeDate(d);
            rpm.setOpenPrice(new BigDecimal(row[1]));
            rpm.setClosePrice(new BigDecimal(row[2]));
            rpm.setPreClose(new BigDecimal(row[3]));
            rpm.setVolume(VOL);
            p.rawMap.put(priceKey(CODE, EX, d), rpm);
        }
        p.item.setResultId("R010-probe-basis");
        p.item.setStockCode(CODE);
        p.item.setExchangeType(EX);
        p.item.setStockName(NAME);
        p.item.setRankNo(1);
        p.item.setFinalScore(50.0);
        return p;
    }

    private static List<String> fedGrid() {
        List<String> out = new ArrayList<>();
        for (String[] row : GRID) {
            out.add(CODE + "|" + EX + "|" + row[0] + "|" + row[1] + "|" + row[2] + "|" + row[3] + "|" + VOL);
        }
        return out;
    }

    // ===================== GOLD（BigDecimal，独立于生产实现）=====================

    private static BigDecimal cBd;
    private static BigDecimal tBd;
    private static BigDecimal sBd;

    /** 费率取**运行时反射读到的** ResearchConvention 常量；`stripTrailingZeros` 归一为冻结时的标度。 */
    private static BigDecimal norm(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros();
    }

    private static void loadRates() throws Exception {
        Class<?> rc = Class.forName("com.quant.research.ResearchConvention");
        cBd = norm(((Number) rc.getField("DEFAULT_COMMISSION_RATE").get(null)).doubleValue());
        tBd = norm(((Number) rc.getField("DEFAULT_SELL_TAX_RATE").get(null)).doubleValue());
        sBd = norm(((Number) rc.getField("DEFAULT_SLIPPAGE_RATE").get(null)).doubleValue());
    }

    private static final BigDecimal P_BD = new BigDecimal("10.000000");
    private static final BigDecimal E_BD = new BigDecimal("11.000000");

    static BigDecimal fDoc(BigDecimal c, BigDecimal t, BigDecimal s) {
        return BigDecimal.ONE.subtract(t).subtract(s);
    }

    static BigDecimal gDoc(BigDecimal c, BigDecimal t, BigDecimal s) {
        return BigDecimal.ONE.add(c).add(s);
    }

    static BigDecimal fImpl(BigDecimal c, BigDecimal t, BigDecimal s) {
        return BigDecimal.ONE.subtract(s).multiply(
                BigDecimal.ONE.subtract(c).subtract(t));
    }

    static BigDecimal gImpl(BigDecimal c, BigDecimal t, BigDecimal s) {
        return BigDecimal.ONE.add(s).multiply(BigDecimal.ONE.add(c));
    }

    /** 声明口径净收益：E·(1−t−s) / [P·(1+c+s)] − 1（逐式与 prereg §3.1 一致）。 */
    static BigDecimal netDoc(BigDecimal c, BigDecimal t, BigDecimal s) {
        BigDecimal exit = E_BD.multiply(fDoc(c, t, s));
        BigDecimal entry = P_BD.multiply(gDoc(c, t, s));
        return exit.divide(entry, MC).subtract(BigDecimal.ONE);
    }

    /** 实现口径净收益：E·(1−s)·(1−c−t) / [P·(1+s)·(1+c)] − 1。 */
    static BigDecimal netImpl(BigDecimal c, BigDecimal t, BigDecimal s) {
        BigDecimal exit = E_BD.multiply(fImpl(c, t, s));
        BigDecimal entry = P_BD.multiply(gImpl(c, t, s));
        return exit.divide(entry, MC).subtract(BigDecimal.ONE);
    }

    private static String str(BigDecimal v) {
        return v.toString();
    }

    private static double abs(double v) {
        return v < 0 ? -v : v;
    }

    // ===================== 反射句柄 =====================

    private static Object svc;
    private static String instanceMethod = "unavailable";
    private static Method mResolveEntry;
    private static Method mPeriodReturn;
    private static Method mBounded;
    private static Method mAssumptions;
    private static Method mEmptyNet;
    private static Method mNet;

    private static Object unsafeInstance(Class<?> cls) throws Exception {
        Class<?> u = Class.forName("sun.misc.Unsafe");
        Field f = u.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        return u.getMethod("allocateInstance", Class.class).invoke(unsafe, cls);
    }

    private static void initHandles() throws Exception {
        Class<?> svcCls = Class.forName("com.quant.service.ScoringBacktestService");
        Class<?> itemCls = Class.forName("com.quant.entity.SelectorResultItem");
        mResolveEntry = svcCls.getDeclaredMethod("resolveEntryContext",
                itemCls, int.class, List.class, Map.class, Map.class, double.class, double.class);
        mResolveEntry.setAccessible(true);
        mPeriodReturn = svcCls.getDeclaredMethod("computePeriodReturn",
                itemCls, int.class, int.class, List.class, Map.class, Map.class,
                double.class, double.class, double.class, double.class);
        mPeriodReturn.setAccessible(true);
        mBounded = svcCls.getDeclaredMethod("boundedRate", Double.class, double.class);
        mBounded.setAccessible(true);
        mAssumptions = svcCls.getDeclaredMethod("executionAssumptions",
                double.class, double.class, double.class, List.class);
        mAssumptions.setAccessible(true);
        // 实例：优先 Unsafe.allocateInstance（prereg §4 A7 冻结的方式），失败才退回全 null 构造器
        try {
            svc = unsafeInstance(svcCls);
            instanceMethod = "unsafe_allocateInstance";
        } catch (Throwable ue) {
            Constructor<?> ctor = null;
            for (Constructor<?> ct : svcCls.getDeclaredConstructors()) {
                ctor = ct;
                break;
            }
            if (ctor == null) {
                throw new IllegalStateException("既无 Unsafe 也无构造器: " + ue);
            }
            ctor.setAccessible(true);
            svc = ctor.newInstance(new Object[ctor.getParameterCount()]);
            instanceMethod = "reflective_null_ctor:" + ctor.getParameterCount();
        }

        Class<?> frs = Class.forName("com.quant.service.FactorResearchService");
        mEmptyNet = frs.getDeclaredMethod("emptyNetIcBlock");
        mEmptyNet.setAccessible(true);
        mNet = frs.getDeclaredMethod("buildNetIcBlock",
                Class.forName("com.quant.service.backtest.FactorResearchEngine$FactorResearchSummary"));
        mNet.setAccessible(true);
    }

    private static Object frService() throws Exception {
        Class<?> frs = Class.forName("com.quant.service.FactorResearchService");
        Constructor<?>[] ctors = frs.getDeclaredConstructors();
        Constructor<?> ctor = null;
        for (Constructor<?> ct : ctors) {
            if (ct.getParameterCount() == 9) {
                ctor = ct;
                break;
            }
        }
        if (ctor == null) {
            throw new IllegalStateException("未找到 FactorResearchService 的 9 参数构造器（R009 F15 失效）");
        }
        ctor.setAccessible(true);
        return ctor.newInstance(new Object[9]);
    }

    // ===================== PROD 调用包装 =====================

    private static final class EntryOut {
        int entryIdx;
        double entryCost;
    }

    private static final class PeriodOut {
        Double ret;
        String skipReason;
        String priceSource;
    }

    private static EntryOut callEntry(Panel p, double c, double s, Map<String, Object> sink) throws Exception {
        Object ctx = mResolveEntry.invoke(null, p.item, BASE_IDX, p.days, p.rawMap, p.qfqMap, c, s);
        if (ctx == null) {
            return null;
        }
        EntryOut eo = new EntryOut();
        Method mi = ctx.getClass().getDeclaredMethod("entryIdx");
        mi.setAccessible(true);
        eo.entryIdx = (Integer) mi.invoke(ctx);
        Method mc = ctx.getClass().getDeclaredMethod("entryCost");
        mc.setAccessible(true);
        eo.entryCost = (Double) mc.invoke(ctx);
        return eo;
    }

    private static PeriodOut callPeriod(Panel p, int entryIdx, int h, double entryCost,
                                        double c, double t, double s) throws Exception {
        Object pr = mPeriodReturn.invoke(null, p.item, entryIdx, h, p.days, p.rawMap, p.qfqMap,
                entryCost, c, t, s);
        if (pr == null) {
            return null;
        }
        PeriodOut po = new PeriodOut();
        Method mr = pr.getClass().getDeclaredMethod("ret");
        mr.setAccessible(true);
        po.ret = (Double) mr.invoke(pr);
        Method ms = pr.getClass().getDeclaredMethod("skipReason");
        ms.setAccessible(true);
        po.skipReason = (String) ms.invoke(pr);
        Method mp = pr.getClass().getDeclaredMethod("priceSource");
        mp.setAccessible(true);
        po.priceSource = (String) mp.invoke(pr);
        return po;
    }

    private static double rawOpen(int idx) {
        return Double.parseDouble(GRID[idx][1]);
    }

    private static double rawClose(int idx) {
        return Double.parseDouble(GRID[idx][2]);
    }

    // ===================== 场景（run#1 / run#2 各调一次，内部对象全新）=====================

    private static Map<String, Map<String, Object>> runScenario() throws Exception {
        Map<String, Map<String, Object>> snap = new LinkedHashMap<>();
        Panel p = buildPanel();
        double c = cBd.doubleValue();
        double t = tBd.doubleValue();
        double s = sBd.doubleValue();
        double gGold = gImpl(cBd, tBd, sBd).doubleValue();
        double fGold = fImpl(cBd, tBd, sBd).doubleValue();

        Map<String, Object> v01 = new LinkedHashMap<>();
        Map<String, Object> v02 = new LinkedHashMap<>();
        Map<String, Object> v03 = new LinkedHashMap<>();
        Map<String, Object> v04 = new LinkedHashMap<>();
        Map<String, Object> v05 = new LinkedHashMap<>();
        Map<String, Object> v10 = new LinkedHashMap<>();
        Map<String, Object> v12 = new LinkedHashMap<>();
        Map<String, Object> v14 = new LinkedHashMap<>();
        Map<String, Object> v15 = new LinkedHashMap<>();
        Map<String, Object> v16 = new LinkedHashMap<>();
        Map<String, Object> v17 = new LinkedHashMap<>();
        Map<String, Object> x01 = new LinkedHashMap<>();
        snap.put("V01", v01);
        snap.put("V02", v02);
        snap.put("V03", v03);
        snap.put("V04", v04);
        snap.put("V05", v05);
        snap.put("V10", v10);
        snap.put("V12", v12);
        snap.put("V14", v14);
        snap.put("V15", v15);
        snap.put("V16", v16);
        snap.put("V17", v17);
        snap.put("X01", x01);

        // ---------- V01 / V02 / V10：PROD 驱动 ----------
        try {
            EntryOut eo = callEntry(p, c, s, v01);
            if (eo == null) {
                v01.put("error", "resolveEntryContext 返回 null（面板被门拦下）");
            } else {
                v01.put("entry_idx", eo.entryIdx);
                v01.put("entry_cost", eo.entryCost);
                v01.put("raw_open", rawOpen(eo.entryIdx));
                double gMeasured = eo.entryCost / rawOpen(eo.entryIdx);
                v01.put("g_impl_measured", gMeasured);
                v01.put("g_impl_gold", gGold);
                double gRes = abs(gMeasured - gGold);
                v01.put("g_residual", gRes);

                double resMax = gRes;
                double fMeasuredH2 = Double.NaN;
                double gMinus1 = gMeasured - 1.0;
                for (int h : HORIZONS) {
                    PeriodOut po = callPeriod(p, eo.entryIdx, h, eo.entryCost, c, t, s);
                    int fwd = eo.entryIdx + h;
                    String hh = "h" + h;
                    v01.put("raw_close_" + hh, rawClose(fwd));
                    v01.put("skip_reason_" + hh, po == null || po.skipReason == null ? "" : po.skipReason);
                    v01.put("price_source_" + hh, po == null ? "" : String.valueOf(po.priceSource));
                    if (po != null && po.ret != null) {
                        double proceeds = (1.0 + po.ret) * eo.entryCost;
                        double fMeasured = proceeds / rawClose(fwd);
                        double fRes = abs(fMeasured - fGold);
                        v01.put("ret_" + hh, po.ret);
                        v01.put("exit_proceeds_" + hh, proceeds);
                        v01.put("f_impl_measured_" + hh, fMeasured);
                        v01.put("f_residual_" + hh, fRes);
                        resMax = Math.max(resMax, fRes);
                        if (h == 2) {
                            fMeasuredH2 = fMeasured;
                        }

                        // V10：由净收益反解退出日索引（网格间距 1.0，close[idx] = 10 + idx）
                        BigDecimal proceedsBd = BigDecimal.valueOf(1.0 + po.ret)
                                .multiply(BigDecimal.valueOf(eo.entryCost));
                        BigDecimal closeRec = proceedsBd.divide(fImpl(cBd, tBd, sBd), MC);
                        BigDecimal idxRaw = closeRec.subtract(BigDecimal.TEN);
                        int idx = (int) Math.round(idxRaw.doubleValue());
                        double idxRes = idxRaw.subtract(BigDecimal.valueOf(idx)).abs().doubleValue();
                        v10.put("entry_idx", eo.entryIdx);
                        v10.put("fwd_idx_" + hh, idx);
                        v10.put("close_recovered_" + hh, str(closeRec));
                        v10.put("close_rec_residual_" + hh, idxRes);
                        v10.put("grid_ambiguity_margin_" + hh, 0.5 - idxRes);
                    }
                }
                v01.put("f_impl_gold", fGold);
                v01.put("residual_max", resMax);
                v01.put("skip_reason_h2_len", String.valueOf(v01.get("skip_reason_h2")).length()
                        + String.valueOf(v01.get("skip_reason_h3")).length());

                // V02：反解出的因子与冻结实现口径逐位一致；且排除「成本从收益率里减」的反事实
                v02.put("residual_max", abs(fMeasuredH2 - fGold) > resMax ? abs(fMeasuredH2 - fGold) : resMax);
                v02.put("f_obs_minus_1", fMeasuredH2 - 1.0);
                v02.put("g_obs_minus_1", gMinus1);
                v02.put("f_obs_minus_1_abs", abs(fMeasuredH2 - 1.0));
                v02.put("g_obs_minus_1_abs", abs(gMinus1));
                v02.put("counterfactual_on_return_excluded",
                        (abs(fMeasuredH2 - 1.0) > 1e-9 && abs(gMinus1) > 1e-9) ? 1 : 0);
            }
        } catch (Throwable ex) {
            v01.put("error", ex.toString());
            v02.put("error", ex.toString());
            v10.put("error", ex.toString());
        }

        // ---------- V03 / V04 / V05：GOLD 精确算术 ----------
        try {
            BigDecimal fD = fDoc(cBd, tBd, sBd);
            BigDecimal fI = fImpl(cBd, tBd, sBd);
            BigDecimal gD = gDoc(cBd, tBd, sBd);
            BigDecimal gI = gImpl(cBd, tBd, sBd);
            BigDecimal fDiff = fI.subtract(fD);
            BigDecimal gDiff = gI.subtract(gD);
            BigDecimal negC = BigDecimal.ZERO.subtract(cBd);
            BigDecimal crossCT = sBd.multiply(cBd.add(tBd));
            BigDecimal crossSC = sBd.multiply(cBd);

            v03.put("f_doc", str(fD));
            v03.put("f_impl", str(fI));
            v03.put("f_diff", str(fDiff));
            v03.put("neg_c", str(negC));
            v03.put("cross_term_s_c_plus_t", str(crossCT));
            v03.put("f_identity_residual", str(fDiff.subtract(negC.add(crossCT)).abs()));
            v03.put("f_diff_abs_gt_tol", fDiff.abs().compareTo(new BigDecimal("1e-15")) > 0 ? 1 : 0);
            v03.put("f_diff_sign_negative", fDiff.signum() < 0 ? 1 : 0);
            v03.put("main_term_share", str(crossCT.abs().divide(fDiff.abs(), MC)));
            v03.put("doc_entry", str(P_BD.multiply(gD)));
            v03.put("doc_exit", str(E_BD.multiply(fD)));
            v03.put("net_doc", str(netDoc(cBd, tBd, sBd)));
            v03.put("impl_entry", str(P_BD.multiply(gI)));
            v03.put("impl_exit", str(E_BD.multiply(fI)));

            BigDecimal netD = netDoc(cBd, tBd, sBd);
            BigDecimal netI = netImpl(cBd, tBd, sBd);
            BigDecimal dNet = netI.subtract(netD);
            v03.put("net_impl", str(netI));
            v03.put("delta_net", str(dNet));
            v03.put("delta_bp", str(dNet.multiply(new BigDecimal("10000"))));
            v03.put("rel", str(dNet.divide(netD, MC)));

            v04.put("g_doc", str(gD));
            v04.put("g_impl", str(gI));
            v04.put("g_diff", str(gDiff));
            v04.put("cross_term_s_c", str(crossSC));
            v04.put("g_identity_residual", str(gDiff.subtract(crossSC).abs()));
            v04.put("g_diff_abs_gt_tol", gDiff.abs().compareTo(new BigDecimal("1e-15")) > 0 ? 1 : 0);
            // 「统一比例偏移」反事实：若 g_impl = k·g_doc 且 f_impl = k·f_doc，则两相对差应同号同值
            v04.put("rel_f_diff", str(fDiff.divide(fD, MC)));
            v04.put("rel_g_diff", str(gDiff.divide(gD, MC)));
            v04.put("uniform_ratio_residual", str(
                    fDiff.divide(fD, MC).subtract(gDiff.divide(gD, MC)).abs()));

            BigDecimal aBd = fI.divide(fD, MC).subtract(BigDecimal.ONE);
            BigDecimal bBd = gD.divide(gI, MC).subtract(BigDecimal.ONE);
            BigDecimal mBd = BigDecimal.ONE.add(netI).divide(BigDecimal.ONE.add(netD), MC);
            v04.put("A", str(aBd));
            v04.put("B", str(bBd));
            v04.put("A_plus_B_bp", str(aBd.add(bBd).multiply(new BigDecimal("10000"))));
            v04.put("one_plus_A_one_plus_B_bp", str(
                    BigDecimal.ONE.add(aBd).multiply(BigDecimal.ONE.add(bBd))
                            .subtract(BigDecimal.ONE).multiply(new BigDecimal("10000"))));
            v04.put("M", str(mBd));
            v04.put("M_minus_1_bp", str(mBd.subtract(BigDecimal.ONE).multiply(new BigDecimal("10000"))));
            v04.put("exit_side_bp", str(aBd.multiply(new BigDecimal("10000"))));
            v04.put("entry_side_bp", str(bBd.multiply(new BigDecimal("10000"))));

            BigDecimal absBp = dNet.multiply(new BigDecimal("10000")).abs();
            v05.put("delta_bp", str(dNet.multiply(new BigDecimal("10000"))));
            v05.put("abs_delta_bp", str(absBp));
            v05.put("th_bp", "1.0");
            v05.put("margin_bp", str(absBp.subtract(BigDecimal.ONE)));
        } catch (Throwable ex) {
            v03.put("error", ex.toString());
            v04.put("error", ex.toString());
            v05.put("error", ex.toString());
        }

        // ---------- V12：常量四处一致性 ----------
        try {
            Class<?> rc = Class.forName("com.quant.research.ResearchConvention");
            String rcC = str(norm(((Number) rc.getField("DEFAULT_COMMISSION_RATE").get(null)).doubleValue()));
            String rcT = str(norm(((Number) rc.getField("DEFAULT_SELL_TAX_RATE").get(null)).doubleValue()));
            String rcS = str(norm(((Number) rc.getField("DEFAULT_SLIPPAGE_RATE").get(null)).doubleValue()));
            ScoringBacktestRequest req = (ScoringBacktestRequest) Class
                    .forName("com.quant.dto.ScoringBacktestRequest").getDeclaredConstructor().newInstance();
            String rqC = str(norm(req.getCommissionRate()));
            String rqT = str(norm(req.getSellTaxRate()));
            String rqS = str(norm(req.getSlippageRate()));
            v12.put("rc_commission", rcC);
            v12.put("rc_sell_tax", rcT);
            v12.put("rc_slippage", rcS);
            v12.put("req_commission_default", rqC);
            v12.put("req_sell_tax_default", rqT);
            v12.put("req_slippage_default", rqS);
            boolean ok = rcC.equals("0.0003") && rcT.equals("0.0005") && rcS.equals("0.0002")
                    && rqC.equals("0.0003") && rqT.equals("0.0005") && rqS.equals("0.0002");
            v12.put("constants_all_equal", ok ? 1 : 0);
            v12.put("label_definition", (String) rc.getMethod("labelDefinition").invoke(null));
        } catch (Throwable ex) {
            v12.put("error", ex.toString());
        }

        // ---------- V14 / V15：boundedRate 边界 + executionAssumptions ----------
        try {
            int i = 0;
            int okCells = 0;
            for (String[] cell : RATE_CELLS) {
                Double arg = switch (cell[0]) {
                    case "null" -> null;
                    case "NaN" -> Double.NaN;
                    case "+Inf" -> Double.POSITIVE_INFINITY;
                    default -> Double.valueOf(cell[0]);
                };
                Double got = (Double) mBounded.invoke(svc, arg, 0.0003);
                double expect = Double.parseDouble(cell[1]);
                boolean ok = got != null && Math.abs(got - expect) <= 0.0;
                v14.put("obs_" + cell[0], got);
                v14.put("expect_" + cell[0], expect);
                v14.put("ok_" + cell[0], ok ? 1 : 0);
                okCells += ok ? 1 : 0;
                i++;
            }
            v14.put("cells", i);
            v14.put("ok_cells", okCells);
            v14.put("grid_ok", (i == RATE_CELLS.length && okCells == RATE_CELLS.length) ? 1 : 0);
            v14.put("instance_method", instanceMethod);

            // V15①：boundedRate(0.06) == 0.05 ≠ 0.06
            Double clamped = (Double) mBounded.invoke(svc, 0.06, 0.0003);
            v15.put("bounded_006_is_005", (clamped != null && clamped == 0.05) ? 1 : 0);
            v15.put("bounded_006_value", clamped);
            // V15③：JDK 行为
            BigDecimal bd006 = BigDecimal.valueOf(0.06);
            v15.put("bigdecimal_006_str", bd006.toString());
            v15.put("bigdecimal_006_equals_006", (bd006.doubleValue() == 0.06) ? 1 : 0);
            v15.put("bigdecimal_006_differs_from_005", (bd006.doubleValue() != 0.05) ? 1 : 0);
            String nanExc = "";
            int nanThrows = 0;
            try {
                BigDecimal.valueOf(Double.NaN);
            } catch (Throwable ex) {
                nanThrows = 1;
                nanExc = ex.getClass().getName();
            }
            v15.put("bigdecimal_nan_throws", nanThrows);
            v15.put("bigdecimal_nan_exception", nanExc);
            try {
                BigDecimal.valueOf(Double.POSITIVE_INFINITY);
                v15.put("bigdecimal_inf_throws", 0);
            } catch (Throwable ex) {
                v15.put("bigdecimal_inf_throws", 1);
            }
            // 载荷侧：executionAssumptions 的费率键与取值，是否存在「已钳位」标记
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> am = (Map<String, Object>) mAssumptions.invoke(
                        svc, 0.05, 0.0005, 0.0002, null);
                List<String> rateKeys = new ArrayList<>();
                int clampMarker = 0;
                for (Map.Entry<String, Object> e : am.entrySet()) {
                    if (e.getKey().contains("rate") || e.getKey().contains("tax")
                            || e.getKey().contains("slippage")) {
                        rateKeys.add(e.getKey() + "=" + e.getValue());
                    }
                    String k = e.getKey().toLowerCase();
                    if (k.contains("clamp") || k.contains("bound") || k.contains("applied")
                            || k.contains("effective")) {
                        clampMarker++;
                    }
                }
                v15.put("assumptions_rate_entries", rateKeys);
                v15.put("assumptions_n_keys", am.size());
                v15.put("assumptions_has_clamp_marker", clampMarker);
                v15.put("assumptions_commission_value", String.valueOf(am.get("commission_rate")));
                v15.put("assumptions_holding_semantics",
                        String.valueOf(am.get("holding_period_semantics")));
                v15.put("assumptions_return_type", String.valueOf(am.get("return_type")));
            } catch (Throwable ex) {
                v15.put("assumptions_error", ex.toString());
            }
        } catch (Throwable ex) {
            v14.put("error", ex.toString());
            v15.put("error", ex.toString());
        }

        // ---------- V16：净收益块键集（跨轮复核 R009 A4c）----------
        try {
            Object frs = frService();
            @SuppressWarnings("unchecked")
            Map<String, Object> empty = (Map<String, Object>) mEmptyNet.invoke(frs);
            List<Double> scores = new ArrayList<>();
            List<Double> rets = new ArrayList<>();
            for (int k = 1; k <= 10; k++) {
                scores.add((double) k);
                rets.add(0.01 * k);
            }
            Map<String, List<Object>> panel = new LinkedHashMap<>();
            List<Object> obs = new ArrayList<>();
            Class<?> obsCls = Class.forName("com.quant.service.backtest.FactorResearchEngine$CrossSectionObservation");
            Constructor<?> obsCtor = obsCls.getDeclaredConstructor(double.class, double.class);
            obsCtor.setAccessible(true);
            for (int k = 0; k < scores.size(); k++) {
                obs.add(obsCtor.newInstance(scores.get(k), rets.get(k)));
            }
            panel.put("2026-01-05", obs);
            Class<?> eng = Class.forName("com.quant.service.backtest.FactorResearchEngine");
            Method summ = eng.getMethod("summarize", Map.class, int.class, int.class, int.class, String.class);
            Object summary = summ.invoke(null, panel, 5, 5, 10, "R010-universe");
            @SuppressWarnings("unchecked")
            Map<String, Object> net = (Map<String, Object>) mNet.invoke(frs, summary);
            v16.put("empty_net_n_keys", empty.size());
            v16.put("net_n_keys", net.size());
            v16.put("empty_net_has_monotonicity", empty.containsKey("monotonicity") ? 1 : 0);
            v16.put("net_has_monotonicity", net.containsKey("monotonicity") ? 1 : 0);
            v16.put("empty_net_metric_scope", String.valueOf(empty.get("metric_scope")));
            v16.put("net_metric_scope", String.valueOf(net.get("metric_scope")));
            v16.put("empty_net_basis_note", String.valueOf(empty.get("basis_note")));
            v16.put("net_keys_sorted", new ArrayList<>(new java.util.TreeSet<>(net.keySet())));
            v16.put("empty_net_keys_sorted", new ArrayList<>(new java.util.TreeSet<>(empty.keySet())));
        } catch (Throwable ex) {
            v16.put("error", ex.toString());
        }

        // ---------- V17：±20% OAT 9 格（披露轴）----------
        try {
            int neg = 0;
            java.util.TreeSet<String> distinct = new java.util.TreeSet<>();
            BigDecimal minMargin = null;
            String minTag = "";
            for (String[] cell : OAT_GRID) {
                String tag = cell[0];
                BigDecimal cc = norm(Double.parseDouble(cell[1]));
                BigDecimal tt = norm(Double.parseDouble(cell[2]));
                BigDecimal ss = norm(Double.parseDouble(cell[3]));
                BigDecimal d = netImpl(cc, tt, ss).subtract(netDoc(cc, tt, ss));
                BigDecimal bp = d.multiply(new BigDecimal("10000"));
                BigDecimal lhs = ss.multiply(tt).multiply(
                        BigDecimal.ONE.add(cc.multiply(new BigDecimal("2")))
                                .add(ss.multiply(new BigDecimal("2"))));
                BigDecimal rhs = cc.multiply(
                        BigDecimal.ONE.add(cc).subtract(ss).subtract(cc.multiply(ss)));
                BigDecimal margin = rhs.divide(lhs, MC);
                v17.put("delta_bp_" + tag, str(bp));
                v17.put("margin_" + tag, str(margin));
                if (bp.signum() < 0) {
                    neg++;
                }
                distinct.add(bp.toString());
                if (minMargin == null || margin.compareTo(minMargin) < 0) {
                    minMargin = margin;
                    minTag = tag;
                }
            }
            v17.put("cells", OAT_GRID.length);
            v17.put("negative_count", neg);
            v17.put("distinct_delta_bp", distinct.size());
            v17.put("distinct_delta_bp_values", new ArrayList<>(distinct));
            v17.put("min_margin_ratio", str(minMargin));
            v17.put("min_margin_tag", minTag);
            v17.put("symbol_lhs", str(sBd.multiply(tBd).multiply(
                    BigDecimal.ONE.add(cBd.multiply(new BigDecimal("2")))
                            .add(sBd.multiply(new BigDecimal("2"))))));
            v17.put("symbol_rhs", str(cBd.multiply(
                    BigDecimal.ONE.add(cBd).subtract(sBd).subtract(cBd.multiply(sBd)))));
        } catch (Throwable ex) {
            v17.put("error", ex.toString());
        }

        // ---------- X01（附加观测，非预注册断言、不参与判定）----------
        try {
            x01.put("note", "additive-notation text co-located with multiplicative code —— "
                    + "由编排器做源码文本计数，此处仅留占位以免空块");
        } catch (Throwable ex) {
            x01.put("error", ex.toString());
        }

        return snap;
    }

    // ===================== 规范序列化（V19 逐字符比对）=====================

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
            sb.append(String.valueOf(o));
        }
    }

    private static String canonical(Object o) {
        StringBuilder sb = new StringBuilder(16384);
        canon(sb, o);
        return sb.toString();
    }

    // ===================== main =====================

    public static void main(String[] args) throws Exception {
        try {
            emit("HARNESS", "class_code_source",
                    String.valueOf(R010Probe.class.getProtectionDomain().getCodeSource().getLocation()));
        } catch (Throwable t) {
            emit("HARNESS", "class_code_source_error", t.toString());
        }
        try {
            emit("HARNESS", "jackson_code_source", String.valueOf(
                    ObjectMapper.class.getProtectionDomain().getCodeSource().getLocation()));
        } catch (Throwable t) {
            emit("HARNESS", "jackson_code_source_error", t.toString());
        }
        try {
            emit("HARNESS", "spring_core_code_source", String.valueOf(
                    Class.forName("org.springframework.context.annotation.AnnotationConfigApplicationContext")
                            .getProtectionDomain().getCodeSource().getLocation()));
        } catch (Throwable t) {
            emit("HARNESS", "spring_core_code_source_error", t.toString());
        }

        try {
            loadRates();
            emit("HARNESS", "rates_loaded", cBd + "/" + tBd + "/" + sBd);
        } catch (Throwable t) {
            emit("HARNESS", "rates_error", t.toString());
            return;
        }
        try {
            initHandles();
            emit("HARNESS", "instance_method", instanceMethod);
            emit("HARNESS", "svc_ctor_params", svcCountParams());
        } catch (Throwable t) {
            emit("HARNESS", "handles_error", t.toString());
        }

        try {
            emitList("V19", "grid_fed", fedGrid());
        } catch (Throwable t) {
            emit("V19", "grid_error", t.toString());
        }

        Map<String, Map<String, Object>> s1;
        Map<String, Map<String, Object>> s2;
        try {
            s1 = runScenario();
        } catch (Throwable t) {
            emit("HARNESS", "scenario1_error", t.toString());
            return;
        }
        try {
            s2 = runScenario();
        } catch (Throwable t) {
            emit("HARNESS", "scenario2_error", t.toString());
            return;
        }

        for (Map.Entry<String, Map<String, Object>> e : s1.entrySet()) {
            for (Map.Entry<String, Object> kv : e.getValue().entrySet()) {
                if (kv.getValue() instanceof List<?> l) {
                    emitList(e.getKey(), kv.getKey(), l);
                } else {
                    emit(e.getKey(), kv.getKey(), kv.getValue());
                }
            }
        }

        String c1 = canonical(s1);
        String c2 = canonical(s2);
        int firstDiff = -1;
        int n = Math.min(c1.length(), c2.length());
        for (int i = 0; i < n; i++) {
            if (c1.charAt(i) != c2.charAt(i)) {
                firstDiff = i;
                break;
            }
        }
        if (firstDiff < 0 && c1.length() != c2.length()) {
            firstDiff = n;
        }
        emit("V19", "det_len_1", c1.length());
        emit("V19", "det_len_2", c2.length());
        emit("V19", "det_equal", (firstDiff < 0) ? 1 : 0);
        emit("V19", "det_first_diff_index", firstDiff);
        emit("V19", "det_canonical_1", c1);
        emit("V19", "det_canonical_2", c2);
    }

    private static int svcCountParams() {
        try {
            Class<?> cls = Class.forName("com.quant.service.ScoringBacktestService");
            return cls.getDeclaredConstructors()[0].getParameterCount();
        } catch (Throwable t) {
            return -1;
        }
    }
}
