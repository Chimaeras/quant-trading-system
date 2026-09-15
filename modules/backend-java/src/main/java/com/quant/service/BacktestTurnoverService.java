package com.quant.service;

import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 回测换手率分析：衡量相邻信号日 TopN 候选集合的变动程度。
 *
 * <p>口径：
 * <ul>
 *   <li>数据来源：selector_result + selector_result_item（已存选股结果）。</li>
 *   <li>同策略同日取最新一条；按 trade_date 升序排列。</li>
 *   <li>换手率 = 1 - |相邻两日 TopN 交集| / <b>请求参数 topN</b>。<b>分母是调用方传入的参数，
 *       不是当日实际候选规模</b>；当日候选数少于 topN（「短员日」，D-R008-01）时本方法<b>不加守卫</b>、
 *       也不截断，照旧用参数分母，因此短员日的数值与「按当日实际规模归一」的口径不同。
 *       逐日行以 {@code prev_size}/{@code curr_size}/{@code intersection_count} 暴露两日实际规模与交集大小，
 *       读者可据此自行判断该日是否短员并复算任何替代口径。并列键
 *       {@code turnover_ratio_actual_denominator}/{@code avg|max|min_turnover_ratio_actual_denominator}
 *       给出按 min(prev_size, curr_size) 归一的对照值（主口径数值与键名均未改，只增并列键）。
 *       <b>键名量纲（D-R008-09，裁定 B）</b>：本载荷所有取值键一律带 {@code _ratio} 后缀
 *       （0–1 分数），与另一生产面的市场换手率 {@code avg_turnover_pct}（百分数）不再同名，
 *       量纲由键名直接读出，不再依赖 {@code avg_turnover_ratio_unit} 这类声明键。</li>
 *   <li>配对：请求 strategy 为空时不按策略分组，主序列按 trade_date 升序，相邻元素可能是
 *       <b>同一天的两个策略</b>（D-R008-04）。逐日行披露 {@code prev_strategy}/{@code curr_strategy}/
 *       {@code same_strategy_pair}/{@code same_date_pair}，并把「同策略且不同交易日」的子集单独聚合到
 *       {@code avg_turnover_ratio_same_strategy_only}（主口径聚合值未改，只增并列键）。</li>
 *   <li>回显（D-R008-05，裁定 B）：<b>只有归一后的两个键</b>——{@code strategy_requested}
 *       （原始请求值，空白一律 {@code null}）与 {@code strategy_effective}
 *       （生效值：过滤时即该策略名，未过滤时 {@code "ALL"}）；判定「本次到底过滤了没有」读
 *       {@code strategy_effective} / {@code grouped_by_strategy}。
 *       旧的单键 {@code strategy} 已<b>删除</b>（它曾对 {@code null} 回 {@code "ALL"}、对 {@code ""}
 *       回 {@code ""}，同一分支两种回显，与实际过滤条件不总对应）。</li>
 *   <li>本值约定为<b>单向替换率</b>（1 − 交集/规模），即只数「被换掉的比例」；判据来源式 (3) 的
 *       Σ|Δw| 是双向口径，两者相差 2 倍。载荷以 {@code turnover_basis} 声明本约定的取值，
 *       但<b>不</b>据此断言单向有误（单向/双向并存于行业惯例，且来源未给归一约定）。</li>
 *   <li>仅作候选稳定性研究，无未来函数；存在幸存者偏差（沿用打分回测口径）。</li>
 *   <li>退化输入（信号日不足 2 天）时换手率**无定义**：{@code avg/max/min_turnover_ratio} 与并列分母族的
 *       聚合键一律为 {@code null}（裁定 A，原为占位 {@code 0.0}——0 会被读成「真的 0 换手」），
 *       并与机器可读的 {@code turnover_status = INSUFFICIENT_SIGNAL_DAYS} 并存（D-R008-03）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestTurnoverService {

    /** D-R008-07：换手率的归一约定声明（**只声明口径，不改数值**）。 */
    static final String TURNOVER_BASIS = "ONE_WAY_REPLACEMENT_RATIO";
    static final String TURNOVER_BASIS_NOTE =
            "本载荷 = 1 - |相邻两日 TopN 交集| / top_n，是**单向替换率**（只数被换掉的比例）；"
                    + "若按 Σ|Δw| 的**双向**口径（双边换手）计，同一集合变动约为本值的 2 倍。"
                    + "单向与双向并存于行业惯例，判据来源亦未规定归一约定，故本键只声明本载荷取单向，"
                    + "不主张任何一种口径有误";
    /** D-R008-01：分母取值声明（**只披露，不改分母**；改分母属协议 §7 record-only）。 */
    static final String TURNOVER_DENOMINATOR = "REQUEST_PARAM_TOP_N";
    static final String TURNOVER_DENOMINATOR_NOTE =
            "分母是调用方传入的 topN（缺省 10），**不是当日实际候选规模**；当日候选少于 topN 时数值会与"
                    + "「按当日实际规模归一」的口径不同（短员日）。逐日 prev_size/curr_size/intersection_count "
                    + "可直接读出两日规模与交集大小，用于识别短员日与复算替代口径；分母是否改为实际规模待定，本键未改数值";
    /**
     * D-R008-09（裁定 B 改名）：量纲后缀声明。本载荷的候选替换率一律带 {@code _ratio} 后缀
     * （0–1 分数），个股行情汇总面的市场换手率带 {@code _pct} 后缀（百分数）——两者**不再同名**，
     * 量纲写在键名里，本键与 {@code _note} 作为双保险保留。
     */
    static final String TURNOVER_UNIT = "RATIO_0_1";
    static final String TURNOVER_UNIT_NOTE =
            "本载荷的 avg_turnover_ratio / turnover_ratio 是 0–1 的**候选替换率**（前端按 ×100 显示为百分数）；"
                    + "另一生产面（个股行情汇总，DataService 的 avg_turnover_pct）是**市场换手率百分数**，"
                    + "两者不是同一个量。改名后两处键名已不相干（_ratio 对 _pct），本键保留作双保险";
    static final String TURNOVER_ROUNDING_RULE = "ROUND_HALF_UP_4DP";
    static final String TURNOVER_ROUNDING_RULE_NOTE =
            "聚合值（avg/max/min_turnover_ratio）与逐日 turnover_ratio 均为四舍五入到 4 位小数；"
                    + "逐日行同时给出 prev_size/curr_size/intersection_count，读者可用 1 - intersection_count/top_n 精确复算";
    /**
     * D-R008-01：按「当日实际候选规模」归一的并列口径（**只加并列键，不改 avg/max/min_turnover_ratio**）。
     * 分母取 min(prev_size, curr_size)，故短员日不再被 top_n 稀释。
     */
    static final String TURNOVER_DENOMINATOR_ACTUAL = "MIN_PREV_CURR_CANDIDATE_COUNT";
    static final String TURNOVER_DENOMINATOR_ACTUAL_NOTE =
            "request-param 分母（top_n）在短员日会把换手率**系统性抬高**：集合完全不变而两日各只有 m < top_n 只候选时，"
                    + "1 - m/top_n > 0，被记成「发生了替换」。本组并列键 turnover_ratio_actual_denominator（逐日）"
                    + "与 avg|max|min_turnover_ratio_actual_denominator（聚合）改用 "
                    + "min(prev_size, curr_size) 作分母（集合完全不变时恒为 0）；两种口径并存，主口径未改；"
                    + "min(prev_size, curr_size) = 0 的日（某日候选为空）无定义，序列化为 null";
    /**
     * D-R008-04：配对规则声明（**只声明 + 并列同策略归一结果，不改既有聚合值**）。
     * 请求 strategy 为空时，原实现把不同策略的结果放进同一条按日期排序的序列，
     * 于是「相邻两天」可能实际是**同一天的两个策略**。
     */
    static final String PAIRING_RULE = "SAME_STRATEGY_DISTINCT_TRADING_DAYS";
    static final String PAIRING_RULE_NOTE =
            "配对规则：仅在同一 strategyType 内、且交易日不同的相邻两条结果之间计算换手率。"
                    + "请求 strategy 为空时主序列按 trade_date 升序不分策略，相邻元素可能是同一天的两个策略"
                    + "（逐日行以 prev_strategy/curr_strategy/prev_trade_date/same_strategy_pair/same_date_pair "
                    + "逐对披露），此类对计入 cross_strategy_pairs 并从 *_same_strategy_only 聚合中剔除；"
                    + "主口径聚合值未改，两种口径并列可比";
    /** D-R008-05（裁定 B）：回显归一说明。旧的单键 {@code strategy} 已删除，只留归一后的两个键。 */
    static final String STRATEGY_ECHO_NOTE =
            "回显只有两个键：strategy_requested 为原始请求值（null / \"\" / 纯空白一律归一为 null），"
                    + "strategy_effective 为归一后生效值（真正过滤时即该策略名，未过滤时 \"ALL\"）——"
                    + "echo 与实际过滤条件一致，判定「本次到底过滤了没有」读 strategy_effective / grouped_by_strategy。"
                    + "旧键 strategy 已删除：它曾对 strategy=null 回 \"ALL\"、对 strategy=\"\" 回 \"\"，"
                    + "两者走同一分支、计算结果逐位相同却给出不同回显，故不再保留";

    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> analyze(String strategy, LocalDate startDate, LocalDate endDate, Integer topN) {
        int n = topN != null && topN > 0 ? topN : 10;
        boolean strategyFiltered = strategy != null && !strategy.isBlank();

        List<SelectorResult> results = selectorResultRepository
                .findByTradeDateBetweenOrderByCreatedAtDesc(startDate, endDate);
        if (strategyFiltered) {
            final String f = strategy;
            results = results.stream().filter(r -> f.equals(r.getStrategyType())).toList();
        }

        // 同策略同日取最新
        Map<String, SelectorResult> latestPerKey = new LinkedHashMap<>();
        for (SelectorResult r : results) {
            latestPerKey.putIfAbsent(r.getStrategyType() + "|" + r.getTradeDate(), r);
        }
        List<SelectorResult> daily = new ArrayList<>(latestPerKey.values());
        daily.sort((a, b) -> a.getTradeDate().compareTo(b.getTradeDate()));

        Map<String, Object> out = new LinkedHashMap<>();
        // D-R008-05（裁定 B）：不再发射旧键 strategy（去兼容层），回显只留归一后的两个键。
        out.put("strategy_requested", strategy == null || strategy.isBlank() ? null : strategy);
        out.put("strategy_effective", strategyFiltered ? strategy : "ALL");
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());
        out.put("top_n", n);
        out.put("signal_days", daily.size());

        // 纯增量披露（Q-R008-02/03/04/05/07/09）：口径、分母、单位、取整、策略过滤语义原先无法从载荷读出。
        // 这些键在两个分支都发射，保证退化输入时键集与正常分支一致（对齐 Q-R007-03 的同键集要求）。
        out.put("turnover_basis", TURNOVER_BASIS);
        out.put("turnover_basis_note", TURNOVER_BASIS_NOTE);
        out.put("denominator", TURNOVER_DENOMINATOR);
        out.put("denominator_note", TURNOVER_DENOMINATOR_NOTE);
        out.put("denominator_actual", TURNOVER_DENOMINATOR_ACTUAL);
        out.put("denominator_actual_note", TURNOVER_DENOMINATOR_ACTUAL_NOTE);
        out.put("pairing_rule", PAIRING_RULE);
        out.put("pairing_rule_note", PAIRING_RULE_NOTE);
        out.put("strategy_echo_note", STRATEGY_ECHO_NOTE);
        out.put("avg_turnover_ratio_unit", TURNOVER_UNIT);
        out.put("avg_turnover_ratio_unit_note", TURNOVER_UNIT_NOTE);
        out.put("rounding_rule", TURNOVER_ROUNDING_RULE);
        out.put("rounding_rule_note", TURNOVER_ROUNDING_RULE_NOTE);
        out.put("strategy_filter", strategyFiltered ? "EXACT_STRATEGY" : "NONE_ALL_STRATEGIES");
        out.put("grouped_by_strategy", strategyFiltered);
        out.put("strategy_filter_note", strategyFiltered
                ? "仅保留 strategyType 等于请求 strategy 的候选结果；相邻日配对在同策略内进行"
                : "strategy 为空或缺省：**不按策略过滤**，且**不按策略分组**——同一天不同策略的最新结果会被"
                        + "当作「相邻交易日」配对（主序列按 trade_date 升序、不分策略；该行为未改）。"
                        + "剔除此类对后的结果见 avg_turnover_ratio_same_strategy_only 与逐日 same_strategy_pair；"
                        + "strategy=\"\" 与 strategy=null 走同一分支、计算结果逐位相同，回显也归一为同一值"
                        + "（strategy_requested=null、strategy_effective=\"ALL\"，Q-R008-05）");

        if (daily.size() < 2) {
            out.put("turnover_status", "INSUFFICIENT_SIGNAL_DAYS");
            // 信号日不足 2 ⟹ 换手率不可计算：avg/max/min 写 null，不得写 0.0
            // （0.0 会被读成「真的 0 换手」，与「无定义」不可区分；与 turnover_status 并存）
            out.put("avg_turnover_ratio", null);
            out.put("max_turnover_ratio", null);
            out.put("min_turnover_ratio", null);
            // 并列口径：无配对 ⟹ 无定义（null，不是 0.0），与主口径的占位物理上可区分
            out.put("avg_turnover_ratio_actual_denominator", null);
            out.put("max_turnover_ratio_actual_denominator", null);
            out.put("min_turnover_ratio_actual_denominator", null);
            out.put("same_strategy_pairs", 0);
            out.put("cross_strategy_pairs", 0);
            out.put("avg_turnover_ratio_same_strategy_only", null);
            out.put("pairs_dropped_undefined_actual_denominator", null);
            out.put("daily_turnover", List.of());
            out.put("note", "信号日不足 2 天，无法计算换手率");
            return out;
        }
        out.put("turnover_status", "OK");

        List<String> resultIds = daily.stream().map(SelectorResult::getResultId).toList();
        List<SelectorResultItem> allItems = selectorResultItemRepository.findByResultIdInOrderByRankNo(resultIds);
        Map<String, List<SelectorResultItem>> itemsByResultId = allItems.stream()
                .collect(Collectors.groupingBy(SelectorResultItem::getResultId, LinkedHashMap::new, Collectors.toList()));

        // 每日的 TopN 标的集合
        List<LocalDate> dates = new ArrayList<>();
        List<Set<String>> dailySets = new ArrayList<>();
        for (SelectorResult r : daily) {
            List<SelectorResultItem> items = itemsByResultId.getOrDefault(r.getResultId(), List.of());
            Set<String> topSet = items.stream()
                    .sorted((a, b) -> Integer.compare(
                            a.getRankNo() != null ? a.getRankNo() : Integer.MAX_VALUE,
                            b.getRankNo() != null ? b.getRankNo() : Integer.MAX_VALUE))
                    .limit(n)
                    .map(it -> it.getStockCode() + "|" + it.getExchangeType())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            dates.add(r.getTradeDate());
            dailySets.add(topSet);
        }

        List<Map<String, Object>> dailyTurnover = new ArrayList<>();
        List<Double> turnovers = new ArrayList<>();
        List<Double> actualDenominatorTurnovers = new ArrayList<>();
        List<Double> sameStrategyTurnovers = new ArrayList<>();
        int sameStrategyPairs = 0;
        int crossStrategyPairs = 0;
        for (int i = 1; i < dailySets.size(); i++) {
            Set<String> prev = dailySets.get(i - 1);
            Set<String> curr = dailySets.get(i);
            Set<String> intersection = new LinkedHashSet<>(prev);
            intersection.retainAll(curr);
            double turnover = n > 0 ? 1.0 - (double) intersection.size() / n : 0.0;
            turnovers.add(turnover);

            Set<String> newEnter = new LinkedHashSet<>(curr);
            newEnter.removeAll(prev);
            Set<String> exited = new LinkedHashSet<>(prev);
            exited.removeAll(curr);

            SelectorResult prevResult = daily.get(i - 1);
            SelectorResult currResult = daily.get(i);
            boolean sameStrategyPair = prevResult.getStrategyType() != null
                    && prevResult.getStrategyType().equals(currResult.getStrategyType());
            boolean sameDatePair = prevResult.getTradeDate().equals(currResult.getTradeDate());
            if (sameStrategyPair && !sameDatePair) {
                sameStrategyPairs++;
                sameStrategyTurnovers.add(turnover);
            } else {
                crossStrategyPairs++;
            }

            // D-R008-01：按两日实际候选规模归一（分母 = min(prev_size, curr_size)）
            Double actualDenominatorTurnover = replacementRatio(intersection.size(), Math.min(prev.size(), curr.size()));
            if (actualDenominatorTurnover != null) {
                actualDenominatorTurnovers.add(actualDenominatorTurnover);
            }

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("trade_date", dates.get(i).toString());
            d.put("turnover_ratio", round4(turnover));
            d.put("new_count", newEnter.size());
            d.put("exit_count", exited.size());
            // D-R008-06：两日实际候选规模与交集大小（纯增量），使分母与短员日可被外部直接读出/复算
            d.put("prev_size", prev.size());
            d.put("curr_size", curr.size());
            d.put("intersection_count", intersection.size());
            d.put("short_handed", prev.size() < n || curr.size() < n);
            // D-R008-01：同一日的替代分母结果（主口径 turnover_ratio 未改）
            d.put("turnover_ratio_actual_denominator", round4(actualDenominatorTurnover));
            d.put("denominator_actual_value", Math.min(prev.size(), curr.size()));
            // D-R008-04：逐对披露配对双方身份，使「跨策略对 / 同日对」可被外部识别并剔除
            d.put("prev_trade_date", prevResult.getTradeDate().toString());
            d.put("prev_strategy", prevResult.getStrategyType());
            d.put("curr_strategy", currResult.getStrategyType());
            d.put("same_strategy_pair", sameStrategyPair);
            d.put("same_date_pair", sameDatePair);
            dailyTurnover.add(d);
        }

        double avg = turnovers.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double max = turnovers.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = turnovers.stream().mapToDouble(Double::doubleValue).min().orElse(0);

        out.put("avg_turnover_ratio", round4(avg));
        out.put("max_turnover_ratio", round4(max));
        out.put("min_turnover_ratio", round4(min));
        // 并列口径聚合：pairs 为空 ⟹ null（无定义），不写 0.0 冒充「恰好为零」
        out.put("same_strategy_pairs", sameStrategyPairs);
        out.put("cross_strategy_pairs", crossStrategyPairs);
        out.put("avg_turnover_ratio_actual_denominator", round4(avgOf(actualDenominatorTurnovers)));
        out.put("max_turnover_ratio_actual_denominator", round4(maxOf(actualDenominatorTurnovers)));
        out.put("min_turnover_ratio_actual_denominator", round4(minOf(actualDenominatorTurnovers)));
        out.put("avg_turnover_ratio_same_strategy_only", round4(avgOf(sameStrategyTurnovers)));
        out.put("pairs_dropped_undefined_actual_denominator",
                dailyTurnover.size() - actualDenominatorTurnovers.size());
        out.put("daily_turnover", dailyTurnover);
        out.put("note", "换手率 = 1 - 相邻日 TopN 交集 / top_n（分母为请求参数，短员日亦同，"
                + "两日实际规模见逐日 prev_size/curr_size）；衡量候选集合变动程度，高换手意味候选不稳定；"
                + "口径、单位、取整与策略过滤语义见 turnover_basis / denominator / avg_turnover_ratio_unit / "
                + "rounding_rule / strategy_filter 各键；实际规模分母与同策略配对见 denominator_actual / "
                + "pairing_rule 及其并列键；存在幸存者偏差");
        return out;
    }

    /** 单向替换率：1 − 交集/分母；分母 ≤ 0 时无定义（null，不等于 0）。 */
    static Double replacementRatio(int intersectionCount, int denominator) {
        if (denominator <= 0) {
            return null;
        }
        return 1.0 - (double) intersectionCount / denominator;
    }

    private static Double avgOf(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static Double maxOf(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    }

    private static Double minOf(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    }

    /** null 透传的 4 位取整（null = 无定义，必须与原样 0.0 区分）。 */
    static Double round4(Double v) {
        return v == null ? null : Math.round(v * 10000.0) / 10000.0;
    }
}
