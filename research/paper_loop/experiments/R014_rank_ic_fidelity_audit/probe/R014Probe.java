import com.quant.service.backtest.FactorResearchEngine;
import com.quant.service.backtest.FactorResearchEngine.CrossSectionObservation;
import com.quant.service.backtest.FactorResearchEngine.DailyRankIc;
import com.quant.service.backtest.FactorResearchEngine.FactorResearchSummary;
import com.quant.service.backtest.FactorResearchEngine.DailyGroupReturnStat;
import com.quant.service.backtest.FactorResearchEngine.GroupReturnStat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R014 只读探针：把**合成面板**喂给 {@link FactorResearchEngine} 的**公开静态 API**，
 * 把返回载荷的**逐元素数值与计数字段**原样（全精度）打印为行式事实。
 *
 * 探针**不含任何阈值、任何判定词、任何结论**——所有参数与运行单元来自外部文件。
 *
 * 用法：java R014Probe &lt;panelFile&gt; &lt;cellFile&gt;
 */
public final class R014Probe {

    private R014Probe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: R014Probe <panelFile> <cellFile>");
            System.exit(2);
        }
        Path panelPath = Path.of(args[0]);
        Path cellPath = Path.of(args[1]);

        Map<String, List<Row>> panels = readPanels(panelPath);
        List<String[]> cells = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(Files.newInputStream(cellPath), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", -1);
                if (f.length >= 6) {
                    cells.add(f);
                }
            }
        }

        out("META|probe_class|" + R014Probe.class.getName());
        out("META|code_source|" + codeSource());
        out("META|java_version|" + System.getProperty("java.version"));
        out("META|panel_file|" + panelPath.toAbsolutePath());
        out("META|cell_file|" + cellPath.toAbsolutePath());
        out("META|panels|" + panels.size());
        out("META|cells|" + cells.size());

        for (String[] c : cells) {
            String tag = c[0];
            String panelId = c[1];
            String order = c[2];
            int groups = Integer.parseInt(c[3]);
            int minSize = Integer.parseInt(c[4]);
            String mode = c[5];

            List<Row> rows = panels.get(panelId);
            if (rows == null) {
                out("ERR|" + tag + "|unknown_panel|" + panelId);
                continue;
            }
            Map<String, List<CrossSectionObservation>> byDate = byDate(rows, order);
            out("R|" + tag + "|cell|" + panelId + "|" + order + "|" + groups + "|" + minSize + "|" + mode
                    + "|rows|" + rows.size() + "|days|" + byDate.size() + "|panel_hash|" + panelHash(rows));

            if ("daygroups".equals(mode)) {
                for (Map.Entry<String, List<CrossSectionObservation>> e : byDate.entrySet()) {
                    List<DailyGroupReturnStat> gs = FactorResearchEngine.groupedAverageReturns(e.getValue(), groups);
                    for (DailyGroupReturnStat s : gs) {
                        out("R|" + tag + "|DG|" + e.getKey() + "|" + s.groupNo() + "|" + s.label()
                                + "|" + s.avgReturn() + "|" + s.nameCount());
                    }
                }
                continue;
            }

            FactorResearchSummary sum = FactorResearchEngine.summarize(
                    byDate, 1, groups, minSize, "R014_PROBE_SYNTHETIC");
            out("R|" + tag + "|holding_days|" + sum.holdingDays());
            out("R|" + tag + "|group_count|" + sum.groupCount());
            out("R|" + tag + "|cross_section_days|" + sum.crossSectionDays());
            out("R|" + tag + "|total_observations|" + sum.totalObservations());
            out("R|" + tag + "|rank_ic_mean|" + sum.rankIcMean());
            out("R|" + tag + "|rank_ic_std|" + sum.rankIcStd());
            out("R|" + tag + "|rank_ic_ir|" + sum.rankIcIr());
            out("R|" + tag + "|rank_ic_positive_rate|" + sum.rankIcPositiveRate());
            out("R|" + tag + "|pearson_ic_mean|" + sum.pearsonIcMean());
            out("R|" + tag + "|monotonicity|" + sum.monotonicity());
            out("R|" + tag + "|hac_std|" + sum.hacStd());
            out("R|" + tag + "|ci_lower|" + sum.ciLower());
            out("R|" + tag + "|ci_upper|" + sum.ciUpper());
            out("R|" + tag + "|long_short_spread|" + sum.twoLongLegSpread());
            out("R|" + tag + "|universe_note|" + sum.universeNote());
            out("R|" + tag + "|daily_len|" + sum.dailyRankIc().size());
            out("R|" + tag + "|grouped_len|" + sum.groupedReturns().size());
            int di = 0;
            for (DailyRankIc d : sum.dailyRankIc()) {
                out("R|" + tag + "|D|" + di + "|" + d.tradeDate() + "|" + d.rankIc() + "|" + d.sampleSize());
                di++;
            }
            int gi = 0;
            for (GroupReturnStat s : sum.groupedReturns()) {
                out("R|" + tag + "|G|" + gi + "|" + s.groupNo() + "|" + s.label() + "|" + s.avgReturn()
                        + "|" + s.observations());
                gi++;
            }
        }
        System.out.flush();
    }

    private static void out(String line) {
        System.out.println(line);
    }

    private static String codeSource() {
        try {
            return String.valueOf(FactorResearchEngine.class.getProtectionDomain()
                    .getCodeSource().getLocation());
        } catch (Exception ex) {
            return "UNAVAILABLE:" + ex.getClass().getSimpleName();
        }
    }

    /** 面板行（**自含日期**；引擎的 record 不含日期，日期由面板文件承载）。 */
    private record Row(String date, double score, double forwardReturn) {}

    /** 面板行的多重集指纹（与行序无关）：用于证明三种序喂入的是同一多重集。 */
    private static String panelHash(List<Row> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Row o : rows) {
            String k = o.date() + "|" + Double.toString(o.score()) + "," + Double.toString(o.forwardReturn());
            counts.merge(k, 1, Integer::sum);
        }
        List<String> uniq = new ArrayList<>(counts.keySet());
        uniq.sort(String::compareTo);
        StringBuilder sb = new StringBuilder();
        for (String k : uniq) {
            sb.append(k).append('x').append(counts.get(k)).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static Map<String, List<Row>> readPanels(Path path) throws Exception {
        Map<String, List<Row>> panels = new LinkedHashMap<>();
        String current = null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("=== PANEL ")) {
                    current = line.substring("=== PANEL ".length()).trim();
                    panels.put(current, new ArrayList<>());
                    continue;
                }
                if (!line.startsWith("O ")) {
                    continue;
                }
                String[] f = line.split("\\s+");
                if (f.length < 4 || current == null) {
                    continue;
                }
                panels.get(current).add(new Row(f[1], Double.parseDouble(f[2]), Double.parseDouble(f[3])));
            }
        }
        return panels;
    }

    /**
     * order 变换：declared = 原序；reversed = **每个交易日内**行序取逆；doubled = reversed 再取逆
     * （应为恒等 ⟹ 用于「置换可撤销」的正面对照）。
     */
    private static Map<String, List<CrossSectionObservation>> byDate(List<Row> rows, String order) {
        Map<String, List<Row>> grouped = new LinkedHashMap<>();
        for (Row r : rows) {
            grouped.computeIfAbsent(r.date(), k -> new ArrayList<>()).add(r);
        }
        boolean reversed = "reversed".equals(order) || "doubled".equals(order);
        int passes = "doubled".equals(order) ? 2 : 1;
        Map<String, List<CrossSectionObservation>> byDate = new LinkedHashMap<>();
        for (Map.Entry<String, List<Row>> e : grouped.entrySet()) {
            List<Row> day = new ArrayList<>(e.getValue());
            for (int p = 0; p < passes; p++) {
                if (reversed) {
                    java.util.Collections.reverse(day);
                }
            }
            List<CrossSectionObservation> obs = new ArrayList<>(day.size());
            for (Row r : day) {
                obs.add(new CrossSectionObservation(r.score(), r.forwardReturn()));
            }
            byDate.put(e.getKey(), obs);
        }
        return byDate;
    }
}
