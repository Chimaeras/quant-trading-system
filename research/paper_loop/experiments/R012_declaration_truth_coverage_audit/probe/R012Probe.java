package com.quant.service.backtest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R012 只读探针：在**冻结面板**上对 {@link FactorResearchEngine#summarize} 做三件事：
 * <ol>
 *   <li>正面对照：{@code universeNote} 形参 → {@code summary.universeNote()} 是否**逐字符透传**
 *       （证明"声明确实到达载荷"，而不是死代码）；</li>
 *   <li>反向锚点：{@code hac_std / ci_lower / ci_upper} 是否由同一条 IC 序列产出、是否有限、
 *       {@code ci_lower <= ci_upper}（证明判定器不会把"真"系统性误判为"假"）；</li>
 *   <li>逐位确定性：用十六进制表示输出，供两次运行逐位比较。</li>
 * </ol>
 *
 * <p>**不写任何生产状态、不读数据库、不发网络请求**。输出由探针自己以 UTF-8 写文件
 * （Windows 上 JVM stdout 使用平台代码页，中文会被写成 GBK）。stdout 只留一行 ASCII 摘要。
 *
 * <p>用法：{@code java -cp <target/classes> com.quant.service.backtest.R012Probe <panel_csv> <out_json>}
 */
public final class R012Probe {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("R012Probe: 需要参数 <panel_csv> <out_json>");
            System.exit(2);
        }
        Map<String, Map<String, List<FactorResearchEngine.CrossSectionObservation>>> panel =
                loadPanel(Path.of(args[0]));
        Map<String, PanelSpec> specs = PanelSpec.frozen();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"probe\": \"R012Probe\",\n");
        sb.append("  \"code_source\": ").append(js(codeSource())).append(",\n");
        sb.append("  \"panel_scenarios\": ").append(jsStrList(new ArrayList<>(panel.keySet()))).append(",\n");
        sb.append("  \"scenarios\": {");

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, PanelSpec> e : specs.entrySet()) {
            String id = e.getKey();
            PanelSpec sp = e.getValue();
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDay =
                    panel.getOrDefault(id, new LinkedHashMap<>());
            FactorResearchEngine.FactorResearchSummary s = FactorResearchEngine.summarize(
                    byDay, sp.holding, sp.group, sp.minSize, sp.universeNote);

            boolean noteLiteralOk = sp.universeNote.equals(s.universeNote());
            boolean noteEqualPassed = s.universeNote() != null && sp.universeNote.equals(s.universeNote());

            StringBuilder b = new StringBuilder();
            b.append("\n    ").append(js(id)).append(": {");
            b.append("\n      \"universe_note\": ").append(js(s.universeNote())).append(",");
            b.append("\n      \"universe_note_input\": ").append(js(sp.universeNote)).append(",");
            b.append("\n      \"universe_note_literal_ok\": ").append(noteLiteralOk).append(",");
            b.append("\n      \"universe_note_equal_passed\": ").append(noteEqualPassed).append(",");
            b.append("\n      \"days\": ").append(byDay.size()).append(",");
            b.append("\n      \"cross_section_days\": ").append(s.crossSectionDays()).append(",");
            b.append("\n      \"total_observations\": ").append(s.totalObservations()).append(",");
            b.append("\n      \"group_count\": ").append(s.groupCount()).append(",");
            b.append("\n      \"grouped_len\": ").append(s.groupedReturns().size()).append(",");
            b.append("\n      \"daily_len\": ").append(s.dailyRankIc().size()).append(",");
            b.append("\n      \"monotonicity\": ").append(js(s.monotonicity())).append(",");
            b.append("\n      \"hac_std\": ").append(dec(s.hacStd())).append(",");
            b.append("\n      \"hac_hex\": ").append(js(hex(s.hacStd()))).append(",");
            b.append("\n      \"ci_lower\": ").append(dec(s.ciLower())).append(",");
            b.append("\n      \"ci_upper\": ").append(dec(s.ciUpper())).append(",");
            b.append("\n      \"ci_lower_hex\": ").append(js(hex(s.ciLower()))).append(",");
            b.append("\n      \"ci_upper_hex\": ").append(js(hex(s.ciUpper()))).append(",");
            b.append("\n      \"rank_ic_hex\": ").append(js(hex(s.rankIcMean()))).append(",");
            b.append("\n      \"hac_finite\": ").append(Double.isFinite(s.hacStd())).append(",");
            b.append("\n      \"ci_finite\": ")
                    .append(Double.isFinite(s.ciLower()) && Double.isFinite(s.ciUpper())).append(",");
            b.append("\n      \"ci_order_ok\": ").append(s.ciLower() <= s.ciUpper()).append(",");
            b.append("\n      \"grouped_len_ok\": ")
                    .append(s.crossSectionDays() == 0 || s.groupedReturns().size() == s.groupCount())
                    .append(",");
            b.append("\n      \"long_short_hex\": ").append(js(hex(s.twoLongLegSpread()))).append(",");
            b.append("\n      \"holding_days\": ").append(s.holdingDays());
            b.append("\n    }");
            parts.add(b.toString());
        }
        sb.append(String.join(",", parts));
        sb.append("\n  },\n");
        sb.append("  \"det_bits\": ").append(js(detBits(panel, specs))).append("\n");
        sb.append("}\n");

        Files.writeString(Path.of(args[1]), sb.toString(), StandardCharsets.UTF_8);
        System.out.println("R012Probe ok scenarios=" + specs.size()
                + " code_source=" + codeSource());
    }

    /** 冻结的面板参数（与 config.json 的 panel.scenarios 对应）。 */
    private record PanelSpec(int holding, int group, int minSize, String universeNote) {
        static Map<String, PanelSpec> frozen() {
            Map<String, PanelSpec> m = new LinkedHashMap<>();
            m.put("PA", new PanelSpec(2, 5, 5, "R012-PROBE-PA-UNIVERSE-NOTE"));
            m.put("PB", new PanelSpec(2, 5, 5, "R012-PROBE-PB-UNIVERSE-NOTE"));
            m.put("PC", new PanelSpec(2, 5, 5, "R012-PROBE-PC-UNIVERSE-NOTE"));
            return m;
        }
    }

    private static String detBits(
            Map<String, Map<String, List<FactorResearchEngine.CrossSectionObservation>>> panel,
            Map<String, PanelSpec> specs) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, PanelSpec> e : specs.entrySet()) {
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDay =
                    panel.getOrDefault(e.getKey(), new LinkedHashMap<>());
            FactorResearchEngine.FactorResearchSummary s = FactorResearchEngine.summarize(
                    byDay, e.getValue().holding, e.getValue().group, e.getValue().minSize,
                    e.getValue().universeNote);
            b.append(e.getKey()).append(':').append(hex(s.rankIcMean())).append(':')
                    .append(hex(s.hacStd())).append(':').append(hex(s.ciLower())).append(':')
                    .append(hex(s.ciUpper())).append(':').append(hex(s.twoLongLegSpread()))
                    .append(':').append(s.monotonicity()).append(':')
                    .append(s.universeNote()).append(';');
        }
        return b.toString();
    }

    private static Map<String, Map<String, List<FactorResearchEngine.CrossSectionObservation>>> loadPanel(
            Path p) throws IOException {
        Map<String, Map<String, List<FactorResearchEngine.CrossSectionObservation>>> out =
                new LinkedHashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] f = t.split(",");
            if (f.length != 4) {
                throw new IOException("panel 行格式错误（应为 scenario,day,score,fwd）：" + t);
            }
            out.computeIfAbsent(f[0], k -> new LinkedHashMap<>())
                    .computeIfAbsent(f[1], k -> new ArrayList<>())
                    .add(new FactorResearchEngine.CrossSectionObservation(
                            Double.parseDouble(f[2]), Double.parseDouble(f[3])));
        }
        return out;
    }

    private static String codeSource() {
        try {
            var cs = R012Probe.class.getProtectionDomain().getCodeSource();
            return cs == null || cs.getLocation() == null ? "null" : cs.getLocation().getPath();
        } catch (Throwable t) {
            return "unavailable:" + t.getClass().getSimpleName();
        }
    }

    private static String hex(double v) {
        return Double.toHexString(v);
    }

    private static String dec(double v) {
        return Double.isFinite(v) ? Double.toString(v) : "null";
    }

    private static String jsStrList(List<String> xs) {
        List<String> out = new ArrayList<>();
        for (String x : xs) {
            out.add(js(x));
        }
        return "[" + String.join(", ", out) + "]";
    }

    private static String js(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
