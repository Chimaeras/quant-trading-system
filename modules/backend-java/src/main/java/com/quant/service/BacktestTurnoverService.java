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
 *       读者可据此自行判断该日是否短员并复算任何替代口径（只读披露，未改数值）。</li>
 *   <li>本值约定为<b>单向替换率</b>（1 − 交集/规模），即只数「被换掉的比例」；判据来源式 (3) 的
 *       Σ|Δw| 是双向口径，两者相差 2 倍。载荷以 {@code turnover_basis} 声明本约定的取值，
 *       但<b>不</b>据此断言单向有误（单向/双向并存于行业惯例，且来源未给归一约定）。</li>
 *   <li>仅作候选稳定性研究，无未来函数；存在幸存者偏差（沿用打分回测口径）。</li>
 *   <li>退化输入（信号日不足 2 天）时 {@code avg/max/min_turnover} 仍按历史行为输出具体实数 0.0，
 *       同时以 {@code turnover_status = INSUFFICIENT_SIGNAL_DAYS} 与「真的 0」区分（D-R008-03；占位
 *       语义未改，只加机器可读标记）。</li>
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
    /** D-R008-09：单位声明（同名 avg_turnover 在个股行情汇总面是百分数）。 */
    static final String TURNOVER_UNIT = "RATIO_0_1";
    static final String TURNOVER_UNIT_NOTE =
            "本载荷的 avg_turnover / turnover 是 0–1 的**候选替换率**（前端按 ×100 显示为百分数）；"
                    + "同名字段在另一生产面（个股行情汇总，DataService 的 avg_turnover）指**市场换手率百分数**，"
                    + "两者不是同一个量，跨面引用前须先看本键";
    static final String TURNOVER_ROUNDING_RULE = "ROUND_HALF_UP_4DP";
    static final String TURNOVER_ROUNDING_RULE_NOTE =
            "聚合值（avg/max/min_turnover）与逐日 turnover 均为四舍五入到 4 位小数；"
                    + "逐日行同时给出 prev_size/curr_size/intersection_count，读者可用 1 - intersection_count/top_n 精确复算";

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
        out.put("strategy", strategy != null ? strategy : "ALL");
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
        out.put("avg_turnover_unit", TURNOVER_UNIT);
        out.put("avg_turnover_unit_note", TURNOVER_UNIT_NOTE);
        out.put("rounding_rule", TURNOVER_ROUNDING_RULE);
        out.put("rounding_rule_note", TURNOVER_ROUNDING_RULE_NOTE);
        out.put("strategy_filter", strategyFiltered ? "EXACT_STRATEGY" : "NONE_ALL_STRATEGIES");
        out.put("grouped_by_strategy", strategyFiltered);
        out.put("strategy_filter_note", strategyFiltered
                ? "仅保留 strategyType 等于请求 strategy 的候选结果；相邻日配对在同策略内进行"
                : "strategy 为空或缺省：**不按策略过滤**，且**不按策略分组**——同一天不同策略的最新结果会被"
                        + "当作「相邻交易日」配对（Q-R008-04 登记，未改行为）。注意 strategy=\"\" 与 strategy=null "
                        + "走同一分支、计算结果逐位相同，但回显值不同（\"ALL\" vs \"\"，Q-R008-05）");

        if (daily.size() < 2) {
            out.put("turnover_status", "INSUFFICIENT_SIGNAL_DAYS");
            out.put("avg_turnover", 0.0);
            out.put("max_turnover", 0.0);
            out.put("min_turnover", 0.0);
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

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("trade_date", dates.get(i).toString());
            d.put("turnover", round4(turnover));
            d.put("new_count", newEnter.size());
            d.put("exit_count", exited.size());
            // D-R008-06：两日实际候选规模与交集大小（纯增量），使分母与短员日可被外部直接读出/复算
            d.put("prev_size", prev.size());
            d.put("curr_size", curr.size());
            d.put("intersection_count", intersection.size());
            d.put("short_handed", prev.size() < n || curr.size() < n);
            dailyTurnover.add(d);
        }

        double avg = turnovers.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double max = turnovers.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = turnovers.stream().mapToDouble(Double::doubleValue).min().orElse(0);

        out.put("avg_turnover", round4(avg));
        out.put("max_turnover", round4(max));
        out.put("min_turnover", round4(min));
        out.put("daily_turnover", dailyTurnover);
        out.put("note", "换手率 = 1 - 相邻日 TopN 交集 / top_n（分母为请求参数，短员日亦同，"
                + "两日实际规模见逐日 prev_size/curr_size）；衡量候选集合变动程度，高换手意味候选不稳定；"
                + "口径、单位、取整与策略过滤语义见 turnover_basis / denominator / avg_turnover_unit / "
                + "rounding_rule / strategy_filter 各键；存在幸存者偏差");
        return out;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
