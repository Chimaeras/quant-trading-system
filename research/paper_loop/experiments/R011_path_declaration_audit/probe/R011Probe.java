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
 * R011 只读探针：在**冻结面板**上对 {@link FactorResearchEngine#summarize} 做 multiverse 复算，
 * 并输出反向锚点与类加载来源。**不写任何生产状态、不读数据库、不发网络请求**。
 *
 * <p>输出：stdout 上的手写 JSON（**不引入任何外部依赖**，故不存在 deps_missing 风险）。
 * 浮点同时给出十进制（{@code Double.toString}）与十六进制（{@code Double.toHexString}），
 * 后者用于确定性门的逐位比较。
 *
 * <p>用法：{@code java -cp <target/classes> com.quant.service.backtest.R011Probe <panel_csv> <out_json>}
 * —— 结果**由探针自己以 UTF-8 写文件**（不经 stdout：Windows 上 JVM 的 stdout 使用平台 ANSI 代码页，
 * 会把中文分组标签写成 GBK 字节，破坏可比对性）。stdout 只留一行 ASCII 摘要供 run.log 使用。
 */
public final class R011Probe {

    private static final int[] HOLDING = {1, 3, 5};
    private static final int[] GROUPS = {3, 5, 10};
    private static final int[] MIN_SIZES = {5, 10, 20};

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("R011Probe: 需要参数 <panel_csv> <out_json>");
            System.exit(2);
        }
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDay = loadPanel(Path.of(args[0]));

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"probe\": \"R011Probe\",\n");
        sb.append("  \"code_source\": ").append(js(codeSource())).append(",\n");
        sb.append("  \"panel_days\": ").append(jsStrList(new ArrayList<>(byDay.keySet()))).append(",\n");
        sb.append("  \"panel_sizes\": ").append(sizeMap(byDay)).append(",\n");

        // ── 27 格 multiverse 复算 ────────────────────────────────────────────────
        List<String> cells = new ArrayList<>();
        int idx = 0;
        for (int h : HOLDING) {
            for (int j : GROUPS) {
                for (int ms : MIN_SIZES) {
                    FactorResearchEngine.FactorResearchSummary s =
                            FactorResearchEngine.summarize(byDay, h, j, ms, "R011_PROBE_UNIVERSE_NOTE");
                    cells.add(cell(idx++, h, j, ms, s));
                }
            }
        }
        sb.append("  \"cells\": [\n").append(String.join(",\n", cells)).append("\n  ],\n");

        // ── 反向锚点 ────────────────────────────────────────────────────────────
        sb.append("  \"anchors\": {\n");
        sb.append("    \"positive\": ").append(anchor(12, 1.0, 1)).append(",\n");
        sb.append("    \"negative\": ").append(anchor(12, -1.0, 1)).append(",\n");
        sb.append("    \"linear\": ").append(linearAnchor(byDay)).append("\n");
        sb.append("  },\n");

        // ── ±20% 敏感性网格（协议强制披露；不改变任何判定） ──────────────────────
        sb.append("  \"sensitivity\": [\n").append(String.join(",\n", sensitivity(byDay))).append("\n  ]\n");
        sb.append("}\n");
        Files.writeString(Path.of(args[1]), sb.toString(), StandardCharsets.UTF_8);
        System.out.println("R011Probe ok cells=" + cells.size()
                + " bytes=" + Files.size(Path.of(args[1]))
                + " code_source=" + codeSource());
    }

    // ── 面板 ───────────────────────────────────────────────────────────────────

    /** 读 CSV：{@code day,score,forward_return}；{@code #} 开头为注释。 */
    private static Map<String, List<FactorResearchEngine.CrossSectionObservation>> loadPanel(Path p)
            throws IOException {
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDay = new LinkedHashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] f = t.split(",");
            if (f.length != 3) {
                throw new IOException("panel 行格式错误（应为 day,score,fwd）：" + t);
            }
            byDay.computeIfAbsent(f[0], k -> new ArrayList<>())
                    .add(new FactorResearchEngine.CrossSectionObservation(
                            Double.parseDouble(f[1]), Double.parseDouble(f[2])));
        }
        return byDay;
    }

    private static String sizeMap(Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDay) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<FactorResearchEngine.CrossSectionObservation>> e : byDay.entrySet()) {
            parts.add(js(e.getKey()) + ": " + e.getValue().size());
        }
        return "{" + String.join(", ", parts) + "}";
    }

    // ── 单格 ───────────────────────────────────────────────────────────────────

    private static String cell(int idx, int h, int j, int ms,
                               FactorResearchEngine.FactorResearchSummary s) {
        List<String> groups = new ArrayList<>();
        int emptyBuckets = 0;
        int topObs = -1;
        List<Integer> emptyNos = new ArrayList<>();
        for (FactorResearchEngine.GroupReturnStat g : s.groupedReturns()) {
            groups.add("{\"group_no\": " + g.groupNo()
                    + ", \"label\": " + js(g.label())
                    + ", \"avg_return\": " + jsD(g.avgReturn())
                    + ", \"observations\": " + g.observations() + "}");
            if (g.observations() == 0) {
                emptyBuckets++;
                emptyNos.add(g.groupNo());
            }
            if (g.groupNo() == j) {
                topObs = g.observations();
            }
        }
        List<String> dailies = new ArrayList<>();
        for (FactorResearchEngine.DailyRankIc d : s.dailyRankIc()) {
            dailies.add("{\"trade_date\": " + js(d.tradeDate())
                    + ", \"rank_ic\": " + jsD(d.rankIc())
                    + ", \"sample_size\": " + d.sampleSize() + "}");
        }
        StringBuilder b = new StringBuilder();
        b.append("    {\"i\": ").append(idx)
                .append(", \"holding_days\": ").append(h)
                .append(", \"group_count\": ").append(j)
                .append(", \"min_cross_section_size\": ").append(ms)
                .append(", \"cross_section_days\": ").append(s.crossSectionDays())
                .append(", \"total_observations\": ").append(s.totalObservations())
                .append(", \"rank_ic_mean\": ").append(jsD(s.rankIcMean()))
                .append(", \"rank_ic_std\": ").append(jsD(s.rankIcStd()))
                .append(", \"rank_ic_ir\": ").append(jsD(s.rankIcIr()))
                .append(", \"rank_ic_positive_rate\": ").append(jsD(s.rankIcPositiveRate()))
                .append(", \"pearson_ic_mean\": ").append(jsD(s.pearsonIcMean()))
                .append(", \"monotonicity\": ").append(js(s.monotonicity()))
                .append(", \"hac_std\": ").append(jsD(s.hacStd()))
                .append(", \"ci_lower\": ").append(jsD(s.ciLower()))
                .append(", \"ci_upper\": ").append(jsD(s.ciUpper()))
                .append(", \"long_short_spread\": ").append(jsD(s.twoLongLegSpread()))
                .append(", \"grouped_rows\": ").append(s.groupedReturns().size())
                .append(", \"empty_buckets\": ").append(emptyBuckets)
                .append(", \"empty_bucket_nos\": ").append(jsIntList(emptyNos))
                .append(", \"top_bucket_observations\": ").append(topObs)
                .append(", \"included_days\": ").append(dailyDates(s))
                .append(", \"hex\": {\"rank_ic_mean\": ").append(jsH(s.rankIcMean()))
                .append(", \"long_short_spread\": ").append(jsH(s.twoLongLegSpread()))
                .append(", \"rank_ic_std\": ").append(jsH(s.rankIcStd()))
                .append(", \"hac_std\": ").append(jsH(s.hacStd()))
                .append(", \"ci_lower\": ").append(jsH(s.ciLower()))
                .append(", \"ci_upper\": ").append(jsH(s.ciUpper()))
                .append(", \"pearson_ic_mean\": ").append(jsH(s.pearsonIcMean()))
                .append("}")
                .append(", \"grouped_returns\": [").append(String.join(", ", groups)).append("]")
                .append(", \"daily_rank_ic\": [").append(String.join(", ", dailies)).append("]}");
        return b.toString();
    }

    private static String dailyDates(FactorResearchEngine.FactorResearchSummary s) {
        List<String> ds = new ArrayList<>();
        for (FactorResearchEngine.DailyRankIc d : s.dailyRankIc()) {
            ds.add(d.tradeDate());
        }
        return jsStrList(ds);
    }

    // ── 锚点 ───────────────────────────────────────────────────────────────────

    /**
     * 单日严格同向/反向锚点。
     *
     * @param n     截面样本数
     * @param sign  {@code +1} ⟹ fwd=+score（Spearman/Pearson 应为 +1）；{@code -1} ⟹ fwd=−score（应为 −1）
     */
    private static String anchor(int n, double sign, int h) {
        List<FactorResearchEngine.CrossSectionObservation> obs = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            obs.add(new FactorResearchEngine.CrossSectionObservation((double) i, sign * i));
        }
        Map<String, List<FactorResearchEngine.CrossSectionObservation>> m = new LinkedHashMap<>();
        m.put("A", obs);
        FactorResearchEngine.FactorResearchSummary s =
                FactorResearchEngine.summarize(m, h, 5, 5, "R011_ANCHOR");
        double ic = s.dailyRankIc().isEmpty() ? Double.NaN : s.dailyRankIc().get(0).rankIc();
        return "{\"n\": " + n + ", \"sign\": " + jsD(sign)
                + ", \"spearman\": " + jsD(ic)
                + ", \"spearman_hex\": " + jsH(ic)
                + ", \"pearson_ic_mean\": " + jsD(s.pearsonIcMean())
                + ", \"monotonicity\": " + js(s.monotonicity())
                + ", \"long_short_spread\": " + jsD(s.twoLongLegSpread()) + "}";
    }

    /** 严格递增线性锚点：对 J∈{3,5,10} 应恒为 MONOTONIC_INCREASING 且 spread>0。 */
    private static String linearAnchor(
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> panel) {
        List<String> parts = new ArrayList<>();
        for (int j : GROUPS) {
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> m = new LinkedHashMap<>();
            for (Map.Entry<String, List<FactorResearchEngine.CrossSectionObservation>> e : panel.entrySet()) {
                List<FactorResearchEngine.CrossSectionObservation> obs = new ArrayList<>();
                for (FactorResearchEngine.CrossSectionObservation o : e.getValue()) {
                    obs.add(new FactorResearchEngine.CrossSectionObservation(o.score(), 0.01 * o.score()));
                }
                m.put(e.getKey(), obs);
            }
            FactorResearchEngine.FactorResearchSummary s =
                    FactorResearchEngine.summarize(m, 5, j, 5, "R011_ANCHOR_LINEAR");
            parts.add("{\"group_count\": " + j
                    + ", \"monotonicity\": " + js(s.monotonicity())
                    + ", \"long_short_spread\": " + jsD(s.twoLongLegSpread())
                    + ", \"long_short_spread_hex\": " + jsH(s.twoLongLegSpread())
                    + ", \"spread_positive\": " + (s.twoLongLegSpread() > 0.0) + "}");
        }
        return "[" + String.join(", ", parts) + "]";
    }

    // ── ±20% 敏感性网格 ────────────────────────────────────────────────────────

    /**
     * 基准 {@code (h=5, J=5, ms=10)} 与 ±20% 邻域 {@code J∈{4,5,6}, ms∈{8,10,12}} 的 9 格复算。
     * 只报告「存在性/符号是否改变」，**不设阈值、不参与任何判定**。
     */
    private static List<String> sensitivity(
            Map<String, List<FactorResearchEngine.CrossSectionObservation>> byDay) {
        FactorResearchEngine.FactorResearchSummary base =
                FactorResearchEngine.summarize(byDay, 5, 5, 10, "R011_SENS_BASE");
        double baseIc = base.rankIcMean();
        int baseSign = Double.compare(base.twoLongLegSpread(), 0.0);
        List<String> parts = new ArrayList<>();
        for (int j : new int[]{4, 5, 6}) {
            for (int ms : new int[]{8, 10, 12}) {
                FactorResearchEngine.FactorResearchSummary s =
                        FactorResearchEngine.summarize(byDay, 5, j, ms, "R011_SENS");
                int sign = Double.compare(s.twoLongLegSpread(), 0.0);
                parts.add("{\"holding_days\": 5, \"group_count\": " + j
                        + ", \"min_cross_section_size\": " + ms
                        + ", \"is_base\": " + (j == 5 && ms == 10)
                        + ", \"cross_section_days\": " + s.crossSectionDays()
                        + ", \"rank_ic_mean\": " + jsD(s.rankIcMean())
                        + ", \"long_short_spread\": " + jsD(s.twoLongLegSpread())
                        + ", \"long_short_spread_hex\": " + jsH(s.twoLongLegSpread())
                        + ", \"spread_sign\": " + sign
                        + ", \"monotonicity\": " + js(s.monotonicity())
                        + ", \"top_bucket_observations\": " + topBucketObs(s, j)
                        + ", \"ic_exists\": " + (s.crossSectionDays() > 0)
                        + ", \"spread_sign_changed_vs_base\": " + (sign != baseSign)
                        + ", \"ic_mean_changed_vs_base\": " + (s.rankIcMean() != baseIc)
                        + "}");
            }
        }
        return parts;
    }

    private static int topBucketObs(FactorResearchEngine.FactorResearchSummary s, int j) {
        for (FactorResearchEngine.GroupReturnStat g : s.groupedReturns()) {
            if (g.groupNo() == j) {
                return g.observations();
            }
        }
        return -1;
    }

    // ── 工具 ───────────────────────────────────────────────────────────────────

    private static String codeSource() {
        try {
            var cs = R011Probe.class.getProtectionDomain().getCodeSource();
            return cs == null || cs.getLocation() == null ? "null" : cs.getLocation().toString();
        } catch (Throwable t) {
            return "ERROR:" + t.getClass().getName();
        }
    }

    /** 字符串 → JSON 字面量（含最小转义）。 */
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

    /** double → JSON：非有限值显式写为字符串，避免 JSON 非法字面量。 */
    private static String jsD(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return js(Double.toString(v));
        }
        return Double.toString(v);
    }

    /** double → JSON 字符串形式的十六进制位模式（确定性门用）。 */
    private static String jsH(double v) {
        return js(Double.toHexString(v));
    }

    private static String jsIntList(List<Integer> xs) {
        List<String> p = new ArrayList<>();
        for (int x : xs) {
            p.add(Integer.toString(x));
        }
        return "[" + String.join(", ", p) + "]";
    }

    private static String jsStrList(List<String> xs) {
        List<String> p = new ArrayList<>();
        for (String x : xs) {
            p.add(js(x));
        }
        return "[" + String.join(", ", p) + "]";
    }

    private R011Probe() {
    }
}
