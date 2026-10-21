package com.quant.service.backtest;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * R013 只读探针（研究装置，**不是**生产代码）。
 *
 * <p>置于 {@code com.quant.service.backtest} 包内以调用**包私有**的
 * {@link FactorResearchEngine#assessMonotonicity(List)}（R010 教训 59：编译进生产引擎自己的
 * {@code target/classes}）。探针**不含任何阈值/字面量数字**：全部输入来自 panel 文件。
 *
 * <p>输出为确定性文本行（按面板 id 排序、无时间戳、无哈希序），供执行器两次运行做逐字节比对。
 *
 * <p>用法：{@code java com.quant.service.backtest.R013Probe <panel_file>}
 */
public final class R013Probe {

    private static final String VERSION = "R013Probe|v1";

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("ERROR|missing_panel_file");
            return;
        }
        List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);

        Map<String, PanelSpec> panels = new LinkedHashMap<>();
        Map<String, List<Double>> units = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\\|", -1);
            switch (p[0]) {
                case "PANEL" -> {
                    PanelSpec spec = new PanelSpec(p[1]);
                    spec.groupCountToken = p[2];
                    spec.minSizeToken = p[3];
                    panels.put(p[1], spec);
                }
                case "DATE" -> panels.get(p[1]).dates.add(p[2]);
                case "OBS" -> {
                    PanelSpec spec = panels.get(p[1]);
                    String date = p[2];
                    spec.byDate.computeIfAbsent(date, k -> new ArrayList<>())
                            .add(new FactorResearchEngine.CrossSectionObservation(
                                    parseNum(p[3]), parseNum(p[4])));
                }
                case "UNIT" -> {
                    List<Double> vals = new ArrayList<>();
                    for (String tok : p[2].split(",")) {
                        vals.add(parseNum(tok));
                    }
                    units.put(p[1], vals);
                }
                default -> { }
            }
        }

        List<String> out = new ArrayList<>();
        out.add(VERSION);
        out.add("PANEL_COUNT|" + panels.size());
        out.add("UNIT_COUNT|" + units.size());

        int gridPending = 0;
        for (PanelSpec spec : panels.values()) {
            if ("GRID".equals(spec.groupCountToken) || "GRID".equals(spec.minSizeToken)) {
                gridPending++;
            }
        }
        out.add("GRID_PANELS|" + gridPending);

        // ---- 固定参数面板 ----
        TreeSet<String> observedLabels = new TreeSet<>();
        for (PanelSpec spec : panels.values()) {
            if ("GRID".equals(spec.groupCountToken) || "GRID".equals(spec.minSizeToken)) {
                continue;
            }
            renderPanel(out, observedLabels, spec.id, spec.byDate,
                    Integer.parseInt(spec.groupCountToken), Integer.parseInt(spec.minSizeToken));
        }

        // ---- ±20% 网格（参数由 executor 提供的组合表决定；此处用 panel 文件里的 GRID 标记跳过） ----
        // 网格单元由 executor 通过 GRIDCELL 行追加（见下），保持单源 = config.json。
        for (String raw : lines) {
            String line = raw.trim();
            if (!line.startsWith("GRIDCELL|")) {
                continue;
            }
            String[] p = line.split("\\|", -1);
            PanelSpec base = panels.get(p[1]);
            String cellId = p[2];
            int gc = Integer.parseInt(p[3]);
            int ms = Integer.parseInt(p[4]);
            renderPanel(out, observedLabels, cellId, base.byDate, gc, ms);
        }

        // ---- 合成单元（直接喂包私有 assessMonotonicity） ----
        for (Map.Entry<String, List<Double>> e : units.entrySet()) {
            List<FactorResearchEngine.GroupReturnStat> stats = new ArrayList<>();
            int i = 1;
            for (Double v : e.getValue()) {
                stats.add(new FactorResearchEngine.GroupReturnStat(i, "G" + i, v, 1));
                i++;
            }
            boolean[] flags = copyFlags(stats);
            String label;
            try {
                label = FactorResearchEngine.assessMonotonicity(stats);
            } catch (Throwable t) {
                label = "EXCEPTION:" + t.getClass().getName();
            }
            observedLabels.add(label);
            out.add("UNIT|" + e.getKey() + "|label|" + label);
            out.add("UNIT|" + e.getKey() + "|flags|" + flags[0] + "|" + flags[1]);
            out.add("UNIT|" + e.getKey() + "|copy_label|" + copyLabel(stats));
        }

        // ---- 载荷重建（round4 后由真实判定函数重建标签） ----
        for (PanelSpec spec : panels.values()) {
            if (!spec.id.startsWith("Q07")) {
                continue;
            }
            List<FactorResearchEngine.GroupReturnStat> rounded = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            List<FactorResearchEngine.GroupReturnStat> src = GROUPED.get(spec.id);
            if (src == null) {
                out.add("RECON|" + spec.id + "|error|no_grouped_capture");
                continue;
            }
            for (FactorResearchEngine.GroupReturnStat g : src) {
                double r = round4(g.avgReturn());
                rounded.add(new FactorResearchEngine.GroupReturnStat(g.groupNo(), g.label(), r,
                        g.observations()));
                if (sb.length() > 0) {
                    sb.append(";");
                }
                sb.append(Double.toString(r));
            }
            out.add("RECON|" + spec.id + "|rounded|" + sb);
            out.add("RECON|" + spec.id + "|payload_rebuilt_label|"
                    + FactorResearchEngine.assessMonotonicity(rounded));
        }

        // ---- DTO 反射 ----
        out.addAll(reflect("FactorAnalysis",
                "com.quant.dto.ScoringBacktestResponse$FactorAnalysis"));
        out.addAll(reflect("FactorIcSummary",
                "com.quant.dto.ScoringBacktestResponse$FactorIcSummary"));

        // ---- 实测取值域 ----
        StringBuilder vocab = new StringBuilder();
        for (String s : observedLabels) {
            if (vocab.length() > 0) {
                vocab.append(",");
            }
            vocab.append(s);
        }
        out.add("OBSERVED_LABELS|" + vocab);

        out.add("END|R013Probe");
        for (String l : out) {
            System.out.println(l);
        }
    }

    private static final Map<String, List<FactorResearchEngine.GroupReturnStat>> GROUPED =
            new LinkedHashMap<>();

    private static void renderPanel(List<String> out, TreeSet<String> observedLabels,
                                    String panelId,
                                    Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate,
                                    int groupCount, int minSize) {
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> sorted = new TreeMap<>(byDate);
        String label;
        try {
            FactorResearchEngine.FactorResearchSummary s = FactorResearchEngine.summarize(
                    sorted, 1, groupCount, minSize, "R013_PROBE_UNIVERSE");
            label = s.monotonicity();
            GROUPED.put(panelId, s.groupedReturns());
            observedLabels.add(label);
            out.add("PANEL|" + panelId + "|ok");
            out.add("PANEL|" + panelId + "|group_count|" + s.groupCount());
            out.add("PANEL|" + panelId + "|group_count_param|" + groupCount);
            out.add("PANEL|" + panelId + "|min_size_param|" + minSize);
            out.add("PANEL|" + panelId + "|cross_section_days|" + s.crossSectionDays());
            out.add("PANEL|" + panelId + "|total_observations|" + s.totalObservations());
            out.add("PANEL|" + panelId + "|label|" + label);
            out.add("PANEL|" + panelId + "|long_short_spread|" + Double.toString(s.twoLongLegSpread()));
            out.add("PANEL|" + panelId + "|grouped_size|" + s.groupedReturns().size());
            boolean[] flags = copyFlags(s.groupedReturns());
            out.add("PANEL|" + panelId + "|flags|" + flags[0] + "|" + flags[1]);
            out.add("PANEL|" + panelId + "|copy_label|" + copyLabel(s.groupedReturns()));
            StringBuilder groups = new StringBuilder();
            for (FactorResearchEngine.GroupReturnStat g : s.groupedReturns()) {
                if (groups.length() > 0) {
                    groups.append(";");
                }
                groups.append(g.groupNo()).append(":").append(g.observations()).append(":")
                        .append(Double.toString(g.avgReturn())).append(":").append(g.label());
            }
            out.add("PANEL|" + panelId + "|groups|" + groups);
        } catch (Throwable t) {
            out.add("PANEL|" + panelId + "|exception|" + t.getClass().getName() + "|"
                    + String.valueOf(t.getMessage()).replace('|', '/').replace('\n', ' '));
        }
    }

    private static List<String> reflect(String tag, String className) {
        List<String> out = new ArrayList<>();
        try {
            Class<?> cls = Class.forName(className);
            TreeSet<String> fields = new TreeSet<>();
            for (Field f : cls.getDeclaredFields()) {
                fields.add(f.getName());
            }
            out.add("DTO|" + tag + "|fields|" + String.join(",", fields));
            TreeSet<String> methods = new TreeSet<>();
            for (Method m : cls.getDeclaredMethods()) {
                methods.add(m.getName());
            }
            out.add("DTO|" + tag + "|methods|" + String.join(",", methods));
            for (Class<?> nested : cls.getDeclaredClasses()) {
                if (nested.getSimpleName().toLowerCase().contains("builder")) {
                    TreeSet<String> bm = new TreeSet<>();
                    for (Method m : nested.getDeclaredMethods()) {
                        bm.add(m.getName());
                    }
                    out.add("DTO|" + tag + "|builder_methods|" + String.join(",", bm));
                }
            }
        } catch (Throwable t) {
            out.add("DTO|" + tag + "|error|" + t.getClass().getName());
        }
        return out;
    }

    /** 冻结 A-01 的 falsifier 规则**副本**（只用于读数：判定该序列下的两个方向 flag）。 */
    private static boolean[] copyFlags(List<FactorResearchEngine.GroupReturnStat> grouped) {
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
        return new boolean[]{increasing, decreasing};
    }

    /** 冻结 A-01 的**分支顺序**副本（increasing 在前）。 */
    private static String copyLabel(List<FactorResearchEngine.GroupReturnStat> grouped) {
        if (grouped == null || grouped.size() < 2) {
            return "N/A";
        }
        boolean[] f = copyFlags(grouped);
        if (f[0]) {
            return "MONOTONIC_INCREASING";
        }
        if (f[1]) {
            return "MONOTONIC_DECREASING";
        }
        return "NON_MONOTONIC";
    }

    /** 冻结 B-06 的读数副本（载荷侧 round4）。 */
    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double parseNum(String token) {
        if ("NaN".equals(token)) {
            return Double.NaN;
        }
        return Double.parseDouble(token);
    }

    private static final class PanelSpec {
        final String id;
        final Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDate =
                new LinkedHashMap<>();
        final List<String> dates = new ArrayList<>();
        String groupCountToken = "0";
        String minSizeToken = "0";

        PanelSpec(String id) {
            this.id = id;
        }
    }

    private R013Probe() {
    }
}
