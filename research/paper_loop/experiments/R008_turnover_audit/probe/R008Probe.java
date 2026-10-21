package com.quant.service.backtest;

import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.service.BacktestTurnoverService;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R008 审计探针（Java 侧）—— 换手率载荷族
 * （{@code avg_turnover} / {@code max_turnover} / {@code min_turnover} / {@code daily_turnover[]}）。
 *
 * <p>唯一职责：<b>调用仓库自己的</b> {@link BacktestTurnoverService#analyze}
 * （由 {@code -cp modules/backend-java/target/classes} 加载），把输入与输出按 flat JSON line
 * 打到 stdout，供 {@code run_turnover_audit.py} 判定。
 *
 * <p>本类<b>不重新实现</b>生产量：所有被审计的换手率数值一律来自 {@code BacktestTurnoverService}
 * 的真实调用。探针里自行实现的只有<b>对照侧</b>的量：
 * <ul>
 *   <li>{@code controlDeclaredFull(n,k) = 1 - k/n}（A 档：满员日下声明口径的算术值）；</li>
 *   <li>{@code controlOneway(p,c,k) = 0.5*((p-k)/p + (c-k)/c)}（A 档：判据来源 Eq.(3)「换手率定义在
 *       实际持仓权重上」在等权、实际规模 p/c 下的<b>单向</b>折算）；</li>
 *   <li>{@code withinStrategy(...)}（严格按「同策略、相邻信号日」配对的单向换手率的<b>集合差</b>算术）。</li>
 * </ul>
 * 这些一律<b>只作对照值</b>出现，并与生产实现同输入并列打印。
 *
 * <p>不连数据库：两个 Spring Data 仓库接口用 {@link Proxy} 伪造；生产代码若调用到
 * 两个已实现方法之外的方法，探针<b>立即抛异常</b>（不允许静默退化）。
 */
public final class R008Probe {

    // ===================== 冻结常数（R008_prereg.md §4 §5）=====================

    static final double ZERO = 0.0;
    /** 单侧短员的材料性阈值（P 档，prereg §5）。 */
    static final double TH_MAT = 0.10;

    /** A7a：所有被生产实现返回过的换手率值，供值域/NaN/保序检查。 */
    static final List<Double> ALL_TURNOVERS = new ArrayList<>();
    static final List<String> ALL_TURNOVER_SRC = new ArrayList<>();

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

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ===================== 对照侧（非生产量）=====================

    /** A 档：满员日（两日均为 n 个候选）下声明口径的算术值。 */
    static double controlDeclaredFull(int n, int inter) {
        return 1.0 - (double) inter / (double) n;
    }

    /**
     * A 档：判据来源 Eq.(3) 的等权单向折算 —— 分母是**各自的实际持仓规模** p / c。
     * p=c=n 时退化为 {@code 1 - k/n}，故与 {@link #controlDeclaredFull} 自洽。
     */
    static double controlOneway(int p, int c, int inter) {
        if (p <= 0 || c <= 0) {
            return Double.NaN;
        }
        return 0.5 * ((double) (p - inter) / p + (double) (c - inter) / c);
    }

    // ===================== 代理仓库（不连库）=====================

    private static SelectorResultRepository resultRepo(final List<SelectorResult> results) {
        return (SelectorResultRepository) Proxy.newProxyInstance(
                R008Probe.class.getClassLoader(),
                new Class<?>[]{SelectorResultRepository.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("findByTradeDateBetweenOrderByCreatedAtDesc".equals(name)) {
                        return results;
                    }
                    if ("toString".equals(name)) {
                        return "R008Proxy(SelectorResultRepository, n=" + results.size() + ")";
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(
                            "R008Probe: 生产代码调用了未预期的仓库方法 " + name + " ⟹ 实验必须失败而非静默退化");
                });
    }

    private static SelectorResultItemRepository itemRepo(final List<SelectorResultItem> items) {
        return (SelectorResultItemRepository) Proxy.newProxyInstance(
                R008Probe.class.getClassLoader(),
                new Class<?>[]{SelectorResultItemRepository.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("findByResultIdInOrderByRankNo".equals(name)) {
                        return items;
                    }
                    if ("toString".equals(name)) {
                        return "R008Proxy(SelectorResultItemRepository, n=" + items.size() + ")";
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(
                            "R008Probe: 生产代码调用了未预期的仓库方法 " + name + " ⟹ 实验必须失败而非静默退化");
                });
    }

    // ===================== 实体构造 =====================

    static final LocalDate D1 = LocalDate.of(2026, 5, 1);
    static final LocalDate D2 = LocalDate.of(2026, 5, 6);
    static final LocalDate D3 = LocalDate.of(2026, 5, 7);
    static final LocalDate D4 = LocalDate.of(2026, 5, 8);
    static final LocalDate D0 = LocalDate.of(2026, 4, 30);

    static SelectorResult res(String resultId, String strategy, LocalDate date) {
        return res(resultId, strategy, date, LocalDateTime.of(2026, 5, 1, 9, 0));
    }

    static SelectorResult res(String resultId, String strategy, LocalDate date, LocalDateTime createdAt) {
        SelectorResult r = new SelectorResult();
        r.setResultId(resultId);
        r.setStrategyType(strategy);
        r.setTradeDate(date);
        r.setCreatedAt(createdAt);
        return r;
    }

    static SelectorResultItem item(String resultId, int rank, String code) {
        SelectorResultItem it = new SelectorResultItem();
        it.setResultId(resultId);
        it.setRankNo(rank);
        it.setStockCode(code);
        it.setExchangeType("SZSE");
        return it;
    }

    /** 顺序生成 rankNo 1..N 的 items（同 R007 的探针风格：显式、可复核）。 */
    static List<SelectorResultItem> itemsSeq(String resultId, String[] codes) {
        List<SelectorResultItem> out = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            out.add(item(resultId, i + 1, codes[i]));
        }
        return out;
    }

    static String[] codes(String prefix, int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = prefix + (i < 9 ? "0" : "") + (i + 1);
        }
        return out;
    }

    // ===================== 调用生产实现 =====================

    static Map<String, Object> run(String strategy, LocalDate s, LocalDate e, Integer topN,
                                   List<SelectorResult> results, List<SelectorResultItem> items) {
        BacktestTurnoverService svc = new BacktestTurnoverService(resultRepo(results), itemRepo(items));
        return svc.analyze(strategy, s, e, topN);
    }

    // ===================== 载荷读取小工具 =====================

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> daily(Map<String, Object> out) {
        Object v = out.get("daily_turnover");
        if (v instanceof List) {
            return (List<Map<String, Object>>) v;
        }
        return new ArrayList<>();
    }

    static double dbl(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return Double.NaN;
    }

    static String keysOf(Map<String, Object> m) {
        return String.join(",", m.keySet());
    }

    static void record(double v, String src) {
        ALL_TURNOVERS.add(v);
        ALL_TURNOVER_SRC.add(src);
    }

    // ===================== G3 门 + 阳性对照哨兵 =====================

    static void g3() {
        emit("G3", "engine_code_source",
                BacktestTurnoverService.class.getProtectionDomain().getCodeSource().getLocation().toString());
        emit("G3", "engine_class", BacktestTurnoverService.class.getName());
        emit("G3", "engine_classloader", String.valueOf(BacktestTurnoverService.class.getClassLoader()));

        // 反向锚点：A2a 面板（两日各 3 条、集合完全相同）。定义值 0.0，生产值必须不同。
        List<SelectorResult> rs = new ArrayList<>();
        rs.add(res("RA2", "S", D2));
        rs.add(res("RA1", "S", D1));
        List<SelectorResultItem> its = new ArrayList<>();
        its.addAll(itemsSeq("RA1", new String[]{"A", "B", "C"}));
        its.addAll(itemsSeq("RA2", new String[]{"A", "B", "C"}));
        Map<String, Object> o = run("S", D1, D2, 10, rs, its);
        double prodShort = dbl(daily(o).get(0).get("turnover_ratio"));
        double ctrlShort = controlOneway(3, 3, 3);
        emit("G3", "sentinel_short_prod", prodShort);
        emit("G3", "sentinel_short_control", ctrlShort);
        emit("G3", "sentinel_reverse_differs",
                Math.abs(prodShort - ctrlShort) > 1e-12 ? 1 : 0);

        // 正向锚点：满员日（|I|=9, n=10）。生产值必须逐位等于对照值。
        List<SelectorResult> rs2 = new ArrayList<>();
        rs2.add(res("RF2", "S", D2));
        rs2.add(res("RF1", "S", D1));
        List<SelectorResultItem> its2 = new ArrayList<>();
        its2.addAll(itemsSeq("RF1", codes("S", 10)));
        String[] d2codes = {"S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S11"};
        its2.addAll(itemsSeq("RF2", d2codes));
        Map<String, Object> o2 = run("S", D1, D2, 10, rs2, its2);
        double prodFull = dbl(daily(o2).get(0).get("turnover_ratio"));
        double ctrlFull = controlDeclaredFull(10, 9);
        emit("G3", "sentinel_full_prod", prodFull);
        emit("G3", "sentinel_full_control", ctrlFull);
        emit("G3", "sentinel_full_equals", Math.abs(prodFull - ctrlFull) <= 1e-12 ? 1 : 0);

        // 与既有测试 BacktestTurnoverServiceTest:51-71 同输入（topN=3, d1={A,B,C}, d2={A,B,D}）
        List<SelectorResult> rs3 = new ArrayList<>();
        rs3.add(res("R2", "CLASSIC_SELECTOR", LocalDate.of(2025, 1, 3)));
        rs3.add(res("R1", "CLASSIC_SELECTOR", LocalDate.of(2025, 1, 2)));
        List<SelectorResultItem> its3 = new ArrayList<>();
        its3.add(item("R1", 1, "A"));
        its3.add(item("R1", 2, "B"));
        its3.add(item("R1", 3, "C"));
        its3.add(item("R2", 1, "A"));
        its3.add(item("R2", 2, "B"));
        its3.add(item("R2", 3, "D"));
        Map<String, Object> o3 = run("CLASSIC_SELECTOR", LocalDate.of(2025, 1, 2),
                LocalDate.of(2025, 1, 3), 3, rs3, its3);
        double exProd = dbl(o3.get("avg_turnover_ratio"));
        // 既有测试 BacktestTurnoverServiceTest:51-71 断言的**字面**期望值（不重算 1−2/3，避免自证）
        double exExpected = 0.3333;
        emit("G3", "ex_test_prod_avg", exProd);
        emit("G3", "ex_test_expected", exExpected);
        emit("G3", "ex_test_diff", Math.abs(exProd - exExpected));
    }

    // ===================== V01 (A1a) 满员路径：声明口径 + 载荷内聚合自洽 =====================

    static void v01() {
        // prereg A1a 冻结：n=10；d1 = S01..S10；d2 = S01..S09 + S11 ⟹ |I|=9 → 0.1
        //   另用 3 日全满员面板测 avg/max/min 的对照聚合（对 round4 后的逐日值重算）
        String[] c10 = codes("S", 10);
        String[] d2c9 = {"S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S11"};
        // |d2c9 ∩ d3c8| 必须 = 8：丢掉 S09/S11，加入 S12/S13（不得复用 d2c9 的成员）
        String[] d3c8 = {"S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S12", "S13"};

        // ---- 面板 M：3 日全满员（10/10/10 条）⟹ pair(|I|=9 → 0.1)、pair(|I|=8 → 0.2) ----
        List<SelectorResult> rs = new ArrayList<>();
        rs.add(res("V1R3", "S", D3));
        rs.add(res("V1R2", "S", D2));
        rs.add(res("V1R1", "S", D1));
        List<SelectorResultItem> its = new ArrayList<>();
        its.addAll(itemsSeq("V1R1", c10));
        its.addAll(itemsSeq("V1R2", d2c9));
        its.addAll(itemsSeq("V1R3", d3c8));

        Map<String, Object> out = run("S", D1, D3, 10, rs, its);
        List<Map<String, Object>> rows = daily(out);
        emit("V01", "panelM_days", rows.size());
        double sum = 0.0;
        double mx = -Double.MAX_VALUE;
        double mn = Double.MAX_VALUE;
        int idx = 0;
        for (Map<String, Object> r : rows) {
            double t = dbl(r.get("turnover_ratio"));
            int nc = (int) dbl(r.get("new_count"));
            int ec = (int) dbl(r.get("exit_count"));
            int inter = 10 - nc;                                  // 满员日：|I| = n - new_count
            double ctrl = controlDeclaredFull(10, inter);
            record(t, "V01.pair" + idx);
            sum += t;
            mx = Math.max(mx, t);
            mn = Math.min(mn, t);
            emitIdx("V01", "turnover", idx, t);
            emitIdx("V01", "new_count", idx, nc);
            emitIdx("V01", "exit_count", idx, ec);
            emitIdx("V01", "intersection", idx, inter);
            emitIdx("V01", "control_declared", idx, ctrl);
            emitIdx("V01", "abs_diff", idx, Math.abs(t - ctrl));
            emitIdx("V01", "date", idx, String.valueOf(r.get("trade_date")));
            idx++;
        }
        double ctrlAvg = sum / rows.size();
        emit("V01", "avg_prod", dbl(out.get("avg_turnover_ratio")));
        emit("V01", "avg_ctrl_from_daily", ctrlAvg);
        emit("V01", "avg_abs_diff", Math.abs(dbl(out.get("avg_turnover_ratio")) - ctrlAvg));
        emit("V01", "max_prod", dbl(out.get("max_turnover_ratio")));
        emit("V01", "max_ctrl_from_daily", mx);
        emit("V01", "max_abs_diff", Math.abs(dbl(out.get("max_turnover_ratio")) - mx));
        emit("V01", "min_prod", dbl(out.get("min_turnover_ratio")));
        emit("V01", "min_ctrl_from_daily", mn);
        emit("V01", "min_abs_diff", Math.abs(dbl(out.get("min_turnover_ratio")) - mn));
        emit("V01", "signal_days", dbl(out.get("signal_days")));
        emit("V01", "top_n", dbl(out.get("top_n")));
        record(dbl(out.get("avg_turnover_ratio")), "V01.avg");
        record(dbl(out.get("max_turnover_ratio")), "V01.max");
        record(dbl(out.get("min_turnover_ratio")), "V01.min");

        // ---- 面板 B：prereg A1a 冻结的 2 日输入（d1 = S01..S10，d2 = S01..S09 + S11，|I| = 9）----
        List<SelectorResult> rs2 = new ArrayList<>();
        rs2.add(res("V1B2", "S", D2));
        rs2.add(res("V1B1", "S", D1));
        List<SelectorResultItem> its2 = new ArrayList<>();
        its2.addAll(itemsSeq("V1B1", c10));
        its2.addAll(itemsSeq("V1B2", d2c9));
        Map<String, Object> out2 = run("S", D1, D2, 10, rs2, its2);
        double t2 = dbl(daily(out2).get(0).get("turnover_ratio"));
        record(t2, "V01.panelB");
        emit("V01", "panelB_turnover", t2);
        emit("V01", "panelB_intersection", 10 - (int) dbl(daily(out2).get(0).get("new_count")));
        emit("V01", "panelB_control", controlDeclaredFull(10, 9));
        emit("V01", "panelB_abs_diff", Math.abs(t2 - controlDeclaredFull(10, 9)));
        emit("V01", "panelB_keys", keysOf(daily(out2).get(0)));
    }

    // ===================== V10 (A4a) 满员日载荷内恒等式 turnover×n = (new+exit)/2 =====================

    static void v10() {
        // prereg A4a 冻结：全满员面板（每日 10 条），设计使每个 pair 的 |I| ∈ {7,8,9}
        //   ⟹ turnover ∈ {0.1,0.2,0.3}（round4 下无精度损失）。
        // 相邻两日逐次换掉成员（不得让同一只出现在相邻两日而把 |I| 抬高）：
        // d1 = S01..S10；d2 = S01..S09 + S11（|I| = 9 → 0.1）；
        // d3 = S01..S08 + S12,S13（d2∩d3 = 8 → 0.2）；d4 = S01..S07 + S14,S15,S16（d3∩d4 = 7 → 0.3）
        String[] c10 = codes("S", 10);
        String[] d2c = {"S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S11"};
        String[] d3c = {"S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S12", "S13"};
        String[] d4c = {"S01", "S02", "S03", "S04", "S05", "S06", "S07", "S14", "S15", "S16"};
        List<SelectorResult> rs = new ArrayList<>();
        rs.add(res("V10R4", "S", D4));
        rs.add(res("V10R3", "S", D3));
        rs.add(res("V10R2", "S", D2));
        rs.add(res("V10R1", "S", D1));
        List<SelectorResultItem> its = new ArrayList<>();
        its.addAll(itemsSeq("V10R1", c10));
        its.addAll(itemsSeq("V10R2", d2c));
        its.addAll(itemsSeq("V10R3", d3c));
        its.addAll(itemsSeq("V10R4", d4c));

        Map<String, Object> out = run("S", D1, D4, 10, rs, its);
        List<Map<String, Object>> rows = daily(out);
        double n = dbl(out.get("top_n"));
        emit("V10", "panel_days", rows.size());
        int idx = 0;
        for (Map<String, Object> r : rows) {
            double t = dbl(r.get("turnover_ratio"));
            double nc = dbl(r.get("new_count"));
            double ec = dbl(r.get("exit_count"));
            double lhs = t * n;
            double rhs = (nc + ec) / 2.0;
            record(t, "V10.pair" + idx);
            emitIdx("V10", "turnover", idx, t);
            emitIdx("V10", "top_n", idx, n);
            emitIdx("V10", "new_count", idx, nc);
            emitIdx("V10", "exit_count", idx, ec);
            emitIdx("V10", "intersection", idx, n - nc);
            emitIdx("V10", "lhs", idx, lhs);
            emitIdx("V10", "rhs", idx, rhs);
            emitIdx("V10", "gap", idx, Math.abs(lhs - rhs));
            emitIdx("V10", "date", idx, String.valueOf(r.get("trade_date")));
            idx++;
        }
        emit("V10", "avg_turnover", dbl(out.get("avg_turnover_ratio")));
        record(dbl(out.get("avg_turnover_ratio")), "V10.avg");
    }

    // ===================== V03 (A2a) 两侧短员且集合完全相同 =====================

    static void v03() {
        List<SelectorResult> rs = new ArrayList<>();
        rs.add(res("V3R2", "S", D2));
        rs.add(res("V3R1", "S", D1));
        List<SelectorResultItem> its = new ArrayList<>();
        its.addAll(itemsSeq("V3R1", new String[]{"A", "B", "C"}));
        its.addAll(itemsSeq("V3R2", new String[]{"A", "B", "C"}));
        Map<String, Object> out = run("S", D1, D2, 10, rs, its);
        Map<String, Object> row = daily(out).get(0);
        double t = dbl(row.get("turnover_ratio"));
        record(t, "V03");
        emit("V03", "top_n", dbl(out.get("top_n")));
        emit("V03", "prev_size", 3);
        emit("V03", "curr_size", 3);
        emit("V03", "intersection", 3);
        emit("V03", "reported", t);
        emit("V03", "defined", controlOneway(3, 3, 3));
        emit("V03", "abs_diff", Math.abs(t - controlOneway(3, 3, 3)));
        emit("V03", "new_count", dbl(row.get("new_count")));
        emit("V03", "exit_count", dbl(row.get("exit_count")));
        emit("V03", "avg_turnover", dbl(out.get("avg_turnover_ratio")));
        emit("V03", "max_turnover", dbl(out.get("max_turnover_ratio")));
        emit("V03", "min_turnover", dbl(out.get("min_turnover_ratio")));
        record(dbl(out.get("avg_turnover_ratio")), "V03.avg");
        record(dbl(out.get("max_turnover_ratio")), "V03.max");
        record(dbl(out.get("min_turnover_ratio")), "V03.min");
    }

    // ===================== V04 (A2b) 单侧短员 =====================

    static void v04() {
        List<SelectorResult> rs = new ArrayList<>();
        rs.add(res("V4R2", "S", D2));
        rs.add(res("V4R1", "S", D1));
        List<SelectorResultItem> its = new ArrayList<>();
        its.addAll(itemsSeq("V4R1", codes("S", 10)));
        its.addAll(itemsSeq("V4R2", new String[]{"S01", "S02", "S03"}));
        Map<String, Object> out = run("S", D1, D2, 10, rs, its);
        double t = dbl(daily(out).get(0).get("turnover_ratio"));
        record(t, "V04");
        emit("V04", "top_n", dbl(out.get("top_n")));
        emit("V04", "p", 10);
        emit("V04", "c", 3);
        emit("V04", "intersection", 3);
        emit("V04", "reported", t);
        emit("V04", "defined", controlOneway(10, 3, 3));
        emit("V04", "abs_diff", Math.abs(t - controlOneway(10, 3, 3)));
        emit("V04", "threshold", TH_MAT);
    }

    // ===================== V05 (A2d) 参数敏感性网格（±20% n；p/c 网格）=====================

    static void v05() {
        int[] ns = {8, 10, 12};
        int[] ps = {2, 3, 4};
        int cell = 0;
        for (int n : ns) {
            for (int p : ps) {
                // 双侧短员：两日均为 p 条且集合完全相同
                List<SelectorResult> rs = new ArrayList<>();
                rs.add(res("G" + cell + "R2", "S", D2));
                rs.add(res("G" + cell + "R1", "S", D1));
                List<SelectorResultItem> its = new ArrayList<>();
                String[] cc = codes("G", p);
                its.addAll(itemsSeq("G" + cell + "R1", cc));
                its.addAll(itemsSeq("G" + cell + "R2", cc));
                Map<String, Object> out = run("S", D1, D2, n, rs, its);
                double t = dbl(daily(out).get(0).get("turnover_ratio"));
                record(t, "V05.both.n" + n + ".p" + p);
                double ctrl = controlOneway(p, p, p);
                emitIdx("V05", "kind", cell, "both");
                emitIdx("V05", "n", cell, n);
                emitIdx("V05", "p", cell, p);
                emitIdx("V05", "c", cell, p);
                emitIdx("V05", "inter", cell, p);
                emitIdx("V05", "reported", cell, t);
                emitIdx("V05", "defined", cell, ctrl);
                emitIdx("V05", "err", cell, Math.abs(t - ctrl));
                cell++;
            }
        }
        for (int n : ns) {
            for (int c : ps) {
                // 单侧短员：d1 满员 n 条；d2 只有 c 条且 d2 ⊂ d1 ⟹ |I| = c
                List<SelectorResult> rs = new ArrayList<>();
                rs.add(res("O" + cell + "R2", "S", D2));
                rs.add(res("O" + cell + "R1", "S", D1));
                List<SelectorResultItem> its = new ArrayList<>();
                its.addAll(itemsSeq("O" + cell + "R1", codes("S", Math.max(n, c))));
                String[] cc = codes("S", c);
                its.addAll(itemsSeq("O" + cell + "R2", cc));
                Map<String, Object> out = run("S", D1, D2, n, rs, its);
                double t = dbl(daily(out).get(0).get("turnover_ratio"));
                record(t, "V05.one.n" + n + ".c" + c);
                double ctrl = controlOneway(n, c, c);
                emitIdx("V05", "kind", cell, "one");
                emitIdx("V05", "n", cell, n);
                emitIdx("V05", "p", cell, n);
                emitIdx("V05", "c", cell, c);
                emitIdx("V05", "inter", cell, c);
                emitIdx("V05", "reported", cell, t);
                emitIdx("V05", "defined", cell, ctrl);
                emitIdx("V05", "err", cell, Math.abs(t - ctrl));
                cell++;
            }
        }
        emit("V05", "cells", cell);
        emit("V05", "threshold", TH_MAT);
    }

    // ===================== V06 (A2c) 定义不适用分支（signal_days < 2）=====================

    static void v06() {
        // 单日
        List<SelectorResult> rs = new ArrayList<>();
        rs.add(res("V6R1", "S", D1));
        List<SelectorResultItem> its = itemsSeq("V6R1", new String[]{"U01", "U02"});
        Map<String, Object> o1 = run("S", D1, D1, 10, rs, its);
        emit("V06", "one_signal_days", dbl(o1.get("signal_days")));
        emit("V06", "one_avg_turnover", dbl(o1.get("avg_turnover_ratio")));
        emit("V06", "one_max_turnover", dbl(o1.get("max_turnover_ratio")));
        emit("V06", "one_min_turnover", dbl(o1.get("min_turnover_ratio")));
        emit("V06", "one_daily_len", daily(o1).size());
        emit("V06", "one_keys", keysOf(o1));
        emit("V06", "one_note", String.valueOf(o1.get("note")));
        emit("V06", "one_has_status_key", hasUnavailableMarker(o1) ? 1 : 0);
        record(dbl(o1.get("avg_turnover_ratio")), "V06.one.avg");
        record(dbl(o1.get("max_turnover_ratio")), "V06.one.max");
        record(dbl(o1.get("min_turnover_ratio")), "V06.one.min");
        // 空
        Map<String, Object> o0 = run(null, D1, D1, null, new ArrayList<>(), new ArrayList<>());
        emit("V06", "zero_signal_days", dbl(o0.get("signal_days")));
        emit("V06", "zero_avg_turnover", dbl(o0.get("avg_turnover_ratio")));
        emit("V06", "zero_daily_len", daily(o0).size());
        emit("V06", "zero_keys", keysOf(o0));
        emit("V06", "zero_strategy_echo", String.valueOf(o0.get("strategy_effective")));
        emit("V06", "zero_has_status_key", hasUnavailableMarker(o0) ? 1 : 0);
        // 判定用的合并键（任一响应含结构化不可用标记即视为有标记）
        emit("V06", "has_status_key",
                (hasUnavailableMarker(o1) || hasUnavailableMarker(o0)) ? 1 : 0);
        emit("V06", "signal_days_max", Math.max(dbl(o1.get("signal_days")), dbl(o0.get("signal_days"))));
        emit("V06", "daily_len_min", Math.min(daily(o1).size(), daily(o0).size()));
    }

    static boolean hasUnavailableMarker(Map<String, Object> out) {
        for (String k : out.keySet()) {
            String lk = k.toLowerCase();
            if (lk.equals("status") || lk.equals("available") || lk.startsWith("basis")
                    || lk.contains("scope") || lk.contains("definition")) {
                return true;
            }
        }
        return false;
    }

    // ===================== V07 (A3a) strategy=null 时的序列构造 =====================

    static Map<String, Object> multiStrategyPanel(String strategy) {
        List<SelectorResult> rs = new ArrayList<>();
        rs.add(res("A1", "P1", D1));
        rs.add(res("B1", "P2", D1));
        rs.add(res("A2", "P1", D2));
        rs.add(res("B2", "P2", D2));
        List<SelectorResultItem> its = new ArrayList<>();
        its.addAll(itemsSeq("A1", codes("SA", 10)));
        its.addAll(itemsSeq("B1", codes("SB", 10)));
        its.addAll(itemsSeq("A2", codes("SA", 10)));
        its.addAll(itemsSeq("B2", codes("SB", 10)));
        return run(strategy, D1, D2, 10, rs, its);
    }

    static void v07() {
        Map<String, Object> out = multiStrategyPanel(null);
        List<Map<String, Object>> rows = daily(out);
        emit("V07", "signal_days", dbl(out.get("signal_days")));
        emit("V07", "distinct_dates", 2);
        emit("V07", "daily_len", rows.size());
        emit("V07", "strategy_echo", String.valueOf(out.get("strategy_effective")));
        StringBuilder dates = new StringBuilder();
        boolean adjacentSame = false;
        for (int i = 0; i < rows.size(); i++) {
            String d = String.valueOf(rows.get(i).get("trade_date"));
            double t = dbl(rows.get(i).get("turnover_ratio"));
            record(t, "V07.pair" + i);
            dates.append(i == 0 ? "" : ",").append(d);
            emitIdx("V07", "pair_date", i, d);
            emitIdx("V07", "pair_turnover", i, t);
            if (i > 0 && d.equals(String.valueOf(rows.get(i - 1).get("trade_date")))) {
                adjacentSame = true;
            }
        }
        emit("V07", "pair_dates", dates.toString());
        emit("V07", "adjacent_same_date", adjacentSame ? 1 : 0);
        emit("V07", "avg_turnover", dbl(out.get("avg_turnover_ratio")));
        emit("V07", "max_turnover", dbl(out.get("max_turnover_ratio")));
        // 对照：严格按同策略相邻信号日配对 —— P1 两日集合相同、P2 两日集合相同 ⟹ 0.0
        emit("V07", "within_control", controlOneway(10, 10, 10));
    }

    // ===================== V08 (A3b) strategy = "" vs null =====================

    static void v08() {
        Map<String, Object> a = multiStrategyPanel(null);
        Map<String, Object> b = multiStrategyPanel("");
        List<Map<String, Object>> ra = daily(a);
        List<Map<String, Object>> rb = daily(b);
        boolean same = ra.size() == rb.size();
        if (same) {
            for (int i = 0; i < ra.size(); i++) {
                if (!ra.get(i).get("turnover_ratio").equals(rb.get(i).get("turnover_ratio"))) {
                    same = false;
                    break;
                }
            }
        }
        emit("V08", "echo_null", String.valueOf(a.get("strategy_effective")));
        emit("V08", "echo_blank", String.valueOf(b.get("strategy_effective")));
        emit("V08", "turnovers_identical", same ? 1 : 0);
        emit("V08", "turnover_first_null", ra.isEmpty() ? Double.NaN : dbl(ra.get(0).get("turnover_ratio")));
        emit("V08", "turnover_first_blank", rb.isEmpty() ? Double.NaN : dbl(rb.get(0).get("turnover_ratio")));
        emit("V08", "signal_days_null", dbl(a.get("signal_days")));
        emit("V08", "signal_days_blank", dbl(b.get("signal_days")));
    }

    // ===================== V09 (A3c) 同策略同日多结果：取最新 =====================

    static void v09() {
        // d0: {Q01..Q09, Z1}；d1 有两条同策略同日结果：R_X {W01..W10}（较早）、R_Y {Q01..Q10}（较晚）
        List<SelectorResult> rs = new ArrayList<>();
        rs.add(res("R_Z", "P1", D0, LocalDateTime.of(2026, 4, 30, 9, 0)));
        rs.add(res("R_Y", "P1", D1, LocalDateTime.of(2026, 5, 1, 11, 0)));
        rs.add(res("R_X", "P1", D1, LocalDateTime.of(2026, 5, 1, 10, 0)));
        List<SelectorResultItem> its = new ArrayList<>();
        String[] zc = {"Q01", "Q02", "Q03", "Q04", "Q05", "Q06", "Q07", "Q08", "Q09", "Z01"};
        its.addAll(itemsSeq("R_Z", zc));
        its.addAll(itemsSeq("R_X", codes("W", 10)));
        its.addAll(itemsSeq("R_Y", codes("Q", 10)));
        Map<String, Object> out = run("P1", D0, D1, 10, rs, its);
        double t = dbl(daily(out).get(0).get("turnover_ratio"));
        record(t, "V09");
        String chosen;
        if (Math.abs(t - controlDeclaredFull(10, 9)) <= 1e-9) {
            chosen = "R_Y";                     // 与 R_Z 交集 9（Q01..Q09）
        } else if (Math.abs(t - 1.0) <= 1e-9) {
            chosen = "R_X";                     // 与 R_Z 交集 0
        } else {
            chosen = "UNKNOWN";
        }
        emit("V09", "signal_days", dbl(out.get("signal_days")));
        emit("V09", "n_results_same_strategy_same_date", 2);
        emit("V09", "turnover", t);
        emit("V09", "chosen_result_id", chosen);
        emit("V09", "expected_if_latest", "R_Y");
    }

    // ===================== V11 (A4b) 短员日的载荷内恒等式破裂 =====================

    static void v11() {
        List<SelectorResult> rs = new ArrayList<>();
        rs.add(res("V11R2", "S", D2));
        rs.add(res("V11R1", "S", D1));
        List<SelectorResultItem> its = new ArrayList<>();
        its.addAll(itemsSeq("V11R1", new String[]{"A", "B", "C"}));
        its.addAll(itemsSeq("V11R2", new String[]{"A", "B", "C"}));
        Map<String, Object> out = run("S", D1, D2, 10, rs, its);
        Map<String, Object> row = daily(out).get(0);
        double t = dbl(row.get("turnover_ratio"));
        int n = (int) dbl(out.get("top_n"));
        double nc = dbl(row.get("new_count"));
        double ec = dbl(row.get("exit_count"));
        double lhs = t * n;
        double rhs = (nc + ec) / 2.0;
        emit("V11", "turnover", t);
        emit("V11", "top_n", n);
        emit("V11", "new_count", nc);
        emit("V11", "exit_count", ec);
        emit("V11", "lhs_turnover_x_n", lhs);
        emit("V11", "rhs_new_plus_exit_over_2", rhs);
        emit("V11", "gap", Math.abs(lhs - rhs));
        String keys = keysOf(row);
        emit("V11", "daily_keys", keys);
        emit("V11", "has_size_field",
                (keys.contains("prev_size") || keys.contains("curr_size") || keys.contains("candidate_count")
                        || keys.contains("prev_n") || keys.contains("curr_n")) ? 1 : 0);
    }

    // ===================== V12 (A4c) 分母反解 vs 回显 top_n =====================

    static void v12() {
        // prereg A4c 冻结面板：|prev| = |curr| = 4，|I| = 1 ⟹ turnover = 1 - 1/n
        Integer[] topNs = {null, 0, -5, 3, 10, 4};
        String[] labels = {"null", "0", "-5", "3", "10", "4"};
        for (int i = 0; i < topNs.length; i++) {
            List<SelectorResult> rs = new ArrayList<>();
            rs.add(res("V12R2" + i, "S", D2));
            rs.add(res("V12R1" + i, "S", D1));
            List<SelectorResultItem> its = new ArrayList<>();
            its.addAll(itemsSeq("V12R1" + i, new String[]{"A", "B", "C", "D"}));
            its.addAll(itemsSeq("V12R2" + i, new String[]{"A", "E", "F", "G"}));
            Map<String, Object> out = run("S", D1, D2, topNs[i], rs, its);
            double t = dbl(daily(out).get(0).get("turnover_ratio"));
            record(t, "V12.topN=" + labels[i]);
            double echo = dbl(out.get("top_n"));
            double inter = 1.0;                          // |I| = 1（prev ∩ curr = {A}）
            double nRec = inter / (1.0 - t);
            // prereg §4 A4c 字面写 `n_recovered = 4/(1−0.75) = 16`，其中分子用了「集合大小 k=4」
            // 而非「交集大小 |I|=1」；算术上 n = |I|/(1−turnover)。两者并列打印，判定用算术正确者，
            // 偏差记报告附录 A（不回写冻结文本）。
            double nRecFrozenLiteral = 4.0 / (1.0 - t);
            boolean exactCapable = "null".equals(labels[i]) || "0".equals(labels[i]) || "-5".equals(labels[i])
                    || "10".equals(labels[i]) || "4".equals(labels[i]);
            emitIdx("V12", "input_topN", i, labels[i]);
            emitIdx("V12", "echo_top_n", i, echo);
            emitIdx("V12", "reported", i, t);
            emitIdx("V12", "intersection", i, inter);
            emitIdx("V12", "n_recovered", i, nRec);
            emitIdx("V12", "n_recovered_frozen_literal", i, nRecFrozenLiteral);
            emitIdx("V12", "n_rec_absdiff", i, Math.abs(nRec - echo));
            emitIdx("V12", "echo_match", i, Math.abs(echo - expectedEcho(topNs[i])) <= 1e-12 ? 1 : 0);
            emitIdx("V12", "exact_capable", i, exactCapable ? 1 : 0);
        }
        emit("V12", "cells", topNs.length);
        emit("V12", "exact_capable_note",
                "round4 在 1e-4 上量化：topN=3 时 |I|/n = 1/3 无 4 位小数展开 ⟹ 反解不可能精确"
                        + "（实测偏差见 n_rec_absdiff[3]）；该格只作描述，不参与判定。"
                        + "其余 5 格（null/0/-5/10/4）可精确反解，是判定集。");
    }

    static double expectedEcho(Integer topN) {
        return (topN == null || topN <= 0) ? 10.0 : topN.doubleValue();
    }

    // ===================== V13 (A5a) / V14 (A5b) 可重建性与结构化口径字段 =====================

    static void v13v14() {
        Map<String, Object> out = multiStrategyPanel("P1");
        Map<String, Object> row = daily(out).get(0);
        String keys = keysOf(row);
        emit("V13", "daily_keys", keys);
        String[] needed = {"prev_size", "curr_size"};
        List<String> missing = new ArrayList<>();
        for (String k : needed) {
            if (!keys.contains(k)) {
                missing.add(k);
            }
        }
        emit("V13", "needed_keys", String.join(",", needed));
        emit("V13", "missing_keys", String.join(",", missing));
        emit("V13", "top_keys", keysOf(out));
        emit("V14", "top_keys", keysOf(out));
        emit("V14", "note_len", String.valueOf(out.get("note")).length());
        emit("V14", "has_structured_basis",
                hasStructuredBasis(out) ? 1 : 0);
    }

    static boolean hasStructuredBasis(Map<String, Object> out) {
        for (String k : out.keySet()) {
            String lk = k.toLowerCase();
            if (lk.contains("basis") || lk.contains("definition") || lk.contains("metric_scope")
                    || lk.contains("convention") || lk.contains("one_way") || lk.contains("round_trip")
                    || lk.contains("口径")) {
                return true;
            }
        }
        return false;
    }

    // ===================== V18 (A7a) 值域 / NaN / round4 保序 =====================

    static void v18() {
        int idx = 0;
        int outOfRange = 0;
        int nan = 0;
        for (int i = 0; i < ALL_TURNOVERS.size(); i++) {
            double v = ALL_TURNOVERS.get(i);
            boolean inRange = !Double.isNaN(v) && !Double.isInfinite(v) && v >= 0.0 && v <= 1.0;
            if (!inRange) {
                outOfRange++;
            }
            if (Double.isNaN(v)) {
                nan++;
            }
            emitIdx("V18", "value", idx, v);
            emitIdx("V18", "in_range", idx, inRange ? 1 : 0);
            emitIdx("V18", "is_nan", idx, Double.isNaN(v) ? 1 : 0);
            emitIdx("V18", "is_inf", idx, Double.isInfinite(v) ? 1 : 0);
            emitIdx("V18", "src", idx, ALL_TURNOVER_SRC.get(i));
            idx++;
        }
        emit("V18", "values", idx);
        emit("V18", "out_of_range", outOfRange);
        emit("V18", "nan_count", nan);
        emit("V18", "min_value", ALL_TURNOVERS.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN));
        emit("V18", "max_value", ALL_TURNOVERS.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN));
        // round4 保序：对 0..1 的网格做单调性检查（不重实现生产量的语义，只检查其单调性）
        int monotone = 1;
        double prev = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= 10000; i++) {
            double v = i / 10000.0;
            double r = Math.round(v * 10000.0) / 10000.0;
            if (r < prev) {
                monotone = 0;
                break;
            }
            prev = r;
        }
        emit("V18", "round4_monotone_on_0_1", monotone);
        emit("V18", "round4_grid_values", 10001);
    }

    // ===================== V20 (A8a) 确定性 =====================

    static void v20() {
        Map<String, Object> a = multiStrategyPanel("P1");
        Map<String, Object> b = multiStrategyPanel("P1");
        boolean same = a.keySet().equals(b.keySet());
        if (same) {
            for (String k : a.keySet()) {
                if (!String.valueOf(a.get(k)).equals(String.valueOf(b.get(k)))) {
                    same = false;
                    break;
                }
            }
        }
        emit("V20", "identical", same ? 1 : 0);
        emit("V20", "run1_avg", dbl(a.get("avg_turnover_ratio")));
        emit("V20", "run2_avg", dbl(b.get("avg_turnover_ratio")));
        emit("V20", "run1_keys", keysOf(a));
        emit("V20", "run2_keys", keysOf(b));
    }

    // ===================== V21 (A8b) 并列 rankNo 下的行序依赖 =====================

    static void v21() {
        emit("V21", "turnover_a", tiePanel(new String[]{"X", "Y", "Z"}));
        emit("V21", "turnover_b", tiePanel(new String[]{"Z", "Y", "X"}));
        double a = tiePanel(new String[]{"X", "Y", "Z"});
        double b = tiePanel(new String[]{"Z", "Y", "X"});
        record(a, "V21.a");
        record(b, "V21.b");
        emit("V21", "changed", Math.abs(a - b) > 1e-12 ? 1 : 0);
        emit("V21", "tie_rank", 10);
        emit("V21", "tie_members", 3);
        emit("V21", "note", "两日 d1={S01..S09,X}；d2 的 rankNo 为 1..9(S01..S09) + 10(X) + 10(Y) + 10(Z)");
    }

    static double tiePanel(String[] tieOrder) {
        List<SelectorResult> rs = new ArrayList<>();
        rs.add(res("T2", "S", D2));
        rs.add(res("T1", "S", D1));
        List<SelectorResultItem> its = new ArrayList<>();
        String[] d1c = {"S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "X"};
        its.addAll(itemsSeq("T1", d1c));
        String[] d2head = {"S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09"};
        its.addAll(itemsSeq("T2", d2head));
        for (String code : tieOrder) {
            its.add(item("T2", 10, code));      // 并列 rankNo = 10，相对顺序由仓库返回顺序决定
        }
        Map<String, Object> out = run("S", D1, D2, 10, rs, its);
        return dbl(daily(out).get(0).get("turnover_ratio"));
    }

    // ===================== 文本侧测量（由 Python 侧读文件，探针不参与）=====================
    // A1b / A3c / A5c / A6a / A6b / A7b 的文本语料由 run_turnover_audit.py 的 walk_lines 扫描，
    // 以保证「同一条只读命令」既可复核又可在金丝雀里被替换。

    // ===================== main =====================

    public static void main(String[] args) throws Exception {
        g3();
        v01();
        v03();
        v04();
        v05();
        v06();
        v07();
        v08();
        v09();
        v10();
        v11();
        v12();
        v13v14();
        v18();
        v20();
        v21();
    }
}
