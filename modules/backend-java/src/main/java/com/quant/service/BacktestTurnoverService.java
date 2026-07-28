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
 *   <li>换手率 = 1 - |相邻两日 TopN 交集| / topN（两日均有 topN 个候选时）。</li>
 *   <li>仅作候选稳定性研究，无未来函数；存在幸存者偏差（沿用打分回测口径）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestTurnoverService {

    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> analyze(String strategy, LocalDate startDate, LocalDate endDate, Integer topN) {
        int n = topN != null && topN > 0 ? topN : 10;

        List<SelectorResult> results = selectorResultRepository
                .findByTradeDateBetweenOrderByCreatedAtDesc(startDate, endDate);
        if (strategy != null && !strategy.isBlank()) {
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

        if (daily.size() < 2) {
            out.put("avg_turnover", 0.0);
            out.put("max_turnover", 0.0);
            out.put("min_turnover", 0.0);
            out.put("daily_turnover", List.of());
            out.put("note", "信号日不足 2 天，无法计算换手率");
            return out;
        }

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
            dailyTurnover.add(d);
        }

        double avg = turnovers.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double max = turnovers.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = turnovers.stream().mapToDouble(Double::doubleValue).min().orElse(0);

        out.put("avg_turnover", round4(avg));
        out.put("max_turnover", round4(max));
        out.put("min_turnover", round4(min));
        out.put("daily_turnover", dailyTurnover);
        out.put("note", "换手率 = 1 - 相邻日 TopN 交集 / topN；衡量候选集合变动程度，高换手意味候选不稳定；存在幸存者偏差");
        return out;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
