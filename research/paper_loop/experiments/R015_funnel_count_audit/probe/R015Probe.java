package com.quant.service;

import com.quant.entity.FeatureSnapshotManifest;
import com.quant.entity.FeatureSnapshotRow;
import com.quant.entity.LabelSnapshot;
import com.quant.entity.MarketData;
import com.quant.entity.MarketPriceRaw;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.FeatureSnapshotManifestRepository;
import com.quant.repository.FeatureSnapshotRowRepository;
import com.quant.repository.LabelSnapshotRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.MarketPriceRawRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.research.FeatureSchemaRegistry;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R015 探针（**只读**）：驱动 FactorResearchService 的 public 入口与 private 漏斗生产者，
 * 输出确定性文本，供 research/paper_loop/experiments/R015_funnel_count_audit/run_R015.py 解析。
 *
 * <p>用法：{@code java -cp target/classes com.quant.service.R015Probe <panelFile> <panelId>}
 * <p>本类**不**修改任何生产代码、**不**写数据库、**不**读真实行情；仓库接口一律用 {@link Proxy} 打桩。
 * <p>面板文件格式（冻结，见 prereg §12.6 / §5）：
 * <pre>
 *   CAL &lt;yyyy-mm-dd&gt; ...            # 交易日历（升序）
 *   KEY &lt;idx&gt; &lt;crossSpec&gt; &lt;nItems&gt; &lt;nValid&gt;   # FULL 路径的合成 selector_result
 *   DIRECT &lt;sid&gt; &lt;mode&gt; &lt;totalObs&gt; &lt;validObs&gt; &lt;crossSpec&gt; ...  # 直接驱动 buildResearchFunnel
 *   RUN &lt;full|unified|snapshot|none&gt;         # FULL 路径要跑哪些公开入口
 * </pre>
 * crossSpec ∈ {NONE, NULLKEY, ZERO, NEG, STR, NUM:&lt;int&gt;}
 */
public class R015Probe {

    private static final List<String> REPO_CALLS = new ArrayList<>();
    private static final StringBuilder OUT = new StringBuilder();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: R015Probe <panelFile> <panelId>");
            System.exit(2);
        }
        Path panelFile = Path.of(args[0]);
        String panelId = args[1];
        List<String> lines = Files.readAllLines(panelFile, StandardCharsets.UTF_8);

        List<LocalDate> cal = new ArrayList<>();
        List<int[]> keySpecs = new ArrayList<>();   // [idx, nItems, nValid]
        List<String> keyCross = new ArrayList<>();
        List<String> directLines = new ArrayList<>();
        String runMode = "none";
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] t = line.split("\\s+");
            switch (t[0]) {
                case "CAL" -> {
                    for (int i = 1; i < t.length; i++) {
                        cal.add(LocalDate.parse(t[i]));
                    }
                }
                case "KEY" -> {
                    keySpecs.add(new int[]{Integer.parseInt(t[1]), Integer.parseInt(t[3]), Integer.parseInt(t[4])});
                    keyCross.add(t[2]);
                }
                case "DIRECT" -> directLines.add(line);
                case "RUN" -> runMode = t[1];
                default -> throw new IllegalArgumentException("unknown panel directive: " + t[0]);
            }
        }

        emit("PROBE v1");
        emit("PANEL_ID " + panelId);
        emit("CAL_SIZE " + cal.size());

        // ---------- 1) DIRECT：反射驱动 private buildResearchFunnel ----------
        FactorResearchService svc = null;
        Method funnelM = null;
        if (!directLines.isEmpty() || !"none".equals(runMode)) {
            svc = newService();
            funnelM = FactorResearchService.class
                    .getDeclaredMethod("buildResearchFunnel", Map.class, int.class, int.class);
            funnelM.setAccessible(true);
        }
        for (String dl : directLines) {
            String[] t = dl.split("\\s+");
            String sid = t[1];
            String mode = t[2];
            int totalObs = Integer.parseInt(t[3]);
            int validObs = Integer.parseInt(t[4]);
            Map<String, SelectorResult> map = new LinkedHashMap<>();
            for (int k = 5; k < t.length; k++) {
                String cross = t[k];
                map.put("S" + (k - 5) + "|2026-01-0" + ((k - 5) % 9 + 1), selectorResult(panelId, k - 5, cross, null));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> funnel = (Map<String, Object>) funnelM.invoke(svc, map, totalObs, validObs);
            emit("DIRECT " + sid + " " + mode + " " + json(funnel));
        }

        // ---------- 2) FULL：公开 research() / unifiedReport() ----------
        if (contains(runMode, "full") || contains(runMode, "unified")) {
            Fixture fx = buildFixture(panelId, cal, keySpecs, keyCross);
            if (contains(runMode, "full")) {
                Map<String, Object> full =
                        svc.research(null, fx.start, fx.end, null);
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("cross_section_days", full.get("cross_section_days"));
                view.put("total_observations", full.get("total_observations"));
                view.put("valid_observations", full.get("valid_observations"));
                view.put("coverage", full.get("coverage"));
                view.put("metric_scope", full.get("metric_scope"));
                view.put("metric_scopes", full.get("metric_scopes"));
                view.put("universe_note_present", full.get("universe_note") != null);
                view.put("funnel", full.get("funnel"));
                Object ri = full.get("rank_ic");
                if (ri instanceof Map<?, ?> m) {
                    Map<String, Object> rv = new LinkedHashMap<>();
                    rv.put("cross_section_days", m.get("cross_section_days"));
                    rv.put("total_observations", m.get("total_observations"));
                    rv.put("metric_scope", m.get("metric_scope"));
                    view.put("rank_ic_counts", rv);
                }
                emit("FULL " + json(view));
            }
            if (contains(runMode, "unified")) {
                Map<String, Object> uni = svc.unifiedReport(null, fx.start, fx.end);
                Map<String, Object> uview = new LinkedHashMap<>();
                uview.put("funnel", uni.get("funnel"));
                uview.put("metric_scope", uni.get("metric_scope"));
                List<Map<String, Object>> horizons = new ArrayList<>();
                Object bh = uni.get("by_horizon");
                if (bh instanceof List<?> l) {
                    for (Object o : l) {
                        if (o instanceof Map<?, ?> m) {
                            Map<String, Object> e = new LinkedHashMap<>();
                            e.put("holding_days", m.get("holding_days"));
                            e.put("cross_section_days", m.get("cross_section_days"));
                            e.put("total_observations", m.get("total_observations"));
                            e.put("metric_scope", m.get("metric_scope"));
                            horizons.add(e);
                        }
                    }
                }
                uview.put("by_horizon_counts", horizons);
                emit("UNIFIED " + json(uview));
            }
        }

        // ---------- 2b) SNPF：反射驱动 private buildSnapshotFunnel（副对象**活读数**） ----------
        //  冻结后追加的仪器读数：不参与任何 K 门 / 任何变体判定，只把 B 组锚点从「静态阅读」
        //  补强为「实测载荷」。snapshotResearch 端到端仍需 DataSnapshotService（具体类，不可 Proxy）。
        if (!directLines.isEmpty() || !"none".equals(runMode)) {
            Method snpfM = FactorResearchService.class.getDeclaredMethod("buildSnapshotFunnel",
                    int.class, int.class, int.class, int.class, List.class, Map.class,
                    boolean.class, String.class);
            snpfM.setAccessible(true);
            for (boolean execAvail : new boolean[]{true, false}) {
                Map<String, Object> breakdown = new LinkedHashMap<>();
                breakdown.put("FILLED", 40);
                breakdown.put("REJECTED", 7);
                List<String> skipped = new ArrayList<>(List.of("2026-01-09(RUNNING)"));
                Object sn = snpfM.invoke(svc, 120, 80, 6, 3, skipped, breakdown, execAvail, "label_v2");
                emit("SNPF exec=" + execAvail + " " + json(sn));
            }
        }

        // ---------- 3) SNAPSHOT：尽力而为（无 Spring 容器时记录真实失败原因） ----------
        if (contains(runMode, "snapshot")) {
            try {
                Map<String, Object> snap = svc.snapshotResearch("CLASSIC_SELECTOR", "snap-R015",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), 1, "label_v2", null);
                emit("SNAPSHOT_TRY ok funnel=" + json(snap.get("funnel")));
            } catch (Throwable e) {
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                emit("SNAPSHOT_TRY err " + root.getClass().getName() + " "
                        + String.valueOf(root.getMessage()).replaceAll("\\s+", " "));
            }
        }

        // ---------- 4) 仓库调用轨迹 ----------
        Set<String> called = new LinkedHashSet<>(REPO_CALLS);
        emit("REPO_CALLS " + String.join(",", called));

        System.out.print(OUT);
        System.out.flush();
    }

    // ------------------------------------------------------------------ fixture

    private static final class Fixture {
        LocalDate start;
        LocalDate end;
    }

    private static FactorResearchService newService() throws Exception {
        // 行情/结果的桩：MarketDataRepository 与 MarketPriceRawRepository 需要真实列表；
        // FeatureSchemaRegistry / DataSnapshotService 是具体类（不可 Proxy）⟹ 传 null，
        // 这条路只在 snapshotResearch 被调用时才会失败，失败原因如实记录。
        Object srRepo = proxy(SelectorResultRepository.class, "SelectorResultRepository");
        Object sriRepo = proxy(SelectorResultItemRepository.class, "SelectorResultItemRepository");
        Object mdRepo = proxy(MarketDataRepository.class, "MarketDataRepository");
        Object mprRepo = proxy(MarketPriceRawRepository.class, "MarketPriceRawRepository");
        Constructor<FactorResearchService> ctor = FactorResearchService.class.getDeclaredConstructor(
                SelectorResultRepository.class, SelectorResultItemRepository.class,
                MarketDataRepository.class, MarketPriceRawRepository.class,
                FeatureSnapshotManifestRepository.class, FeatureSnapshotRowRepository.class,
                LabelSnapshotRepository.class, FeatureSchemaRegistry.class, DataSnapshotService.class);
        ctor.setAccessible(true);
        return ctor.newInstance(srRepo, sriRepo, mdRepo, mprRepo, null, null, null, null, null);
    }

    private static Fixture buildFixture(String panelId, List<LocalDate> cal,
                                        List<int[]> keySpecs, List<String> keyCross) throws Exception {
        if (cal.isEmpty() || keySpecs.isEmpty()) {
            throw new IllegalStateException("panel needs CAL and KEY lines for FULL runs");
        }
        List<SelectorResult> results = new ArrayList<>();
        List<SelectorResultItem> items = new ArrayList<>();
        for (int k = 0; k < keySpecs.size(); k++) {
            int idx = keySpecs.get(k)[0];
            int nItems = keySpecs.get(k)[1];
            int nValid = keySpecs.get(k)[2];
            String resultId = panelId + "-R" + idx;
            SelectorResult r = selectorResult(panelId, idx, keyCross.get(k), resultId);
            r.setStrategyType("S" + idx);
            r.setTradeDate(cal.get(idx % cal.size()));
            results.add(r);
            for (int i = 0; i < nItems; i++) {
                SelectorResultItem it = new SelectorResultItem();
                it.setResultId(resultId);
                it.setRankNo(i + 1);
                it.setStockCode(code(idx, i));
                it.setExchangeType("SH");
                it.setFinalScore(i < nValid ? 10.0 + i : 0.0);
                items.add(it);
            }
        }
        Fixture fx = new Fixture();
        fx.start = cal.get(0);
        fx.end = cal.get(Math.min(keySpecs.size(), cal.size()) - 1);

        // 行情：所有出现过的 (code|ex) 在整段日历上给确定性的 open/close
        List<MarketData> bars = new ArrayList<>();
        List<MarketPriceRaw> raws = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        for (SelectorResultItem it : items) {
            codes.add(it.getStockCode() + "|" + it.getExchangeType());
        }
        int di = 0;
        for (LocalDate d : cal) {
            di++;
            for (String ck : codes) {
                String[] p = ck.split("\\|");
                BigDecimal close = BigDecimal.valueOf(10.0 + di * 0.1);
                BigDecimal open = close.subtract(new BigDecimal("0.05"));
                MarketData bar = new MarketData();
                bar.setStockCode(p[0]);
                bar.setExchangeType(p[1]);
                bar.setTradeDate(d);
                bar.setOpenPrice(open);
                bar.setClosePrice(close);
                bars.add(bar);
                MarketPriceRaw raw = new MarketPriceRaw();
                raw.setStockCode(p[0]);
                raw.setExchangeType(p[1]);
                raw.setTradeDate(d);
                raw.setOpenPrice(open);
                raw.setClosePrice(close);
                raws.add(raw);
            }
        }
        Stub.INSTANCE.put("SelectorResultRepository.findByTradeDateBetweenOrderByCreatedAtDesc", results);
        Stub.INSTANCE.put("SelectorResultItemRepository.findByResultIdInOrderByRankNo", items);
        Stub.INSTANCE.put("MarketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc", bars);
        Stub.INSTANCE.put("MarketDataRepository.findDistinctTradeDatesBetween", cal);
        Stub.INSTANCE.put("MarketPriceRawRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc", raws);
        return fx;
    }

    private static String code(int keyIdx, int itemIdx) {
        return String.format("A%03d%03d", keyIdx, itemIdx);
    }

    private static SelectorResult selectorResult(String panelId, int idx, String crossSpec, String resultId) {
        SelectorResult r = new SelectorResult();
        r.setResultId(resultId == null ? panelId + "-R" + idx : resultId);
        r.setStrategyType("S" + idx);
        r.setTradeDate(LocalDate.parse("2026-01-0" + (idx % 9 + 1)));
        switch (crossSpec) {
            case "NONE" -> r.setFactorSnapshot(null);
            case "NULLKEY" -> r.setFactorSnapshot(new LinkedHashMap<>());
            case "ZERO" -> r.setFactorSnapshot(snap(0));
            case "NEG" -> r.setFactorSnapshot(snap(-5));
            case "STR" -> r.setFactorSnapshot(snap("1200"));
            default -> {
                if (!crossSpec.startsWith("NUM:")) {
                    throw new IllegalArgumentException("bad crossSpec: " + crossSpec);
                }
                r.setFactorSnapshot(snap(Integer.parseInt(crossSpec.substring(4))));
            }
        }
        return r;
    }

    private static Map<String, Object> snap(Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cross_section_count", v);
        return m;
    }

    // ------------------------------------------------------------------ stub proxy

    /** 仓库桩：按 "接口名.方法名" 取预置返回值；未预置则返回空/零值并登记调用痕迹。 */
    private static final class Stub implements InvocationHandler {
        static final Stub INSTANCE = new Stub();
        private final Map<String, Object> canned = new LinkedHashMap<>();

        void put(String key, Object v) {
            canned.put(key, v);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("toString".equals(name)) {
                return "stub";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == (args == null ? null : args[0]);
            }
            REPO_CALLS.add(name);
            Object v = canned.get(method.getDeclaringClass().getSimpleName() + "." + name);
            if (v != null) {
                return v;
            }
            Class<?> rt = method.getReturnType();
            if (rt == List.class) {
                return new ArrayList<>();
            }
            if (rt == Set.class) {
                return new LinkedHashSet<>();
            }
            if (rt == int.class || rt == long.class) {
                return 0L;
            }
            if (rt == boolean.class) {
                return false;
            }
            return null;
        }
    }

    private static Object proxy(Class<?> iface, String label) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, Stub.INSTANCE);
    }

    // ------------------------------------------------------------------ emit

    private static void emit(String s) {
        OUT.append(s).append('\n');
    }

    private static boolean contains(String csv, String want) {
        return csv != null && List.of(csv.split("\\+")).contains(want);
    }

    private static String json(Object o) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, o);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object v : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJson(sb, v);
            }
            sb.append(']');
        } else if (o instanceof BigDecimal bd) {
            sb.append(bd.toPlainString());
        } else if (o instanceof Number n) {
            sb.append(n.toString());
        } else if (o instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else {
            String s = o.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", " ").replace("\r", " ");
            sb.append('"').append(s).append('"');
        }
    }

    /** 保留：便于以后按需展开 LabelSnapshot / Manifest（当前未被面板使用）。 */
    @SuppressWarnings("unused")
    private static LabelSnapshot unusedLabel() {
        return null;
    }

    @SuppressWarnings("unused")
    private static FeatureSnapshotManifest unusedManifest() {
        return null;
    }

    @SuppressWarnings("unused")
    private static FeatureSnapshotRow unusedRow() {
        return null;
    }
}
