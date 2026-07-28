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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 因子研究面板：得分覆盖率、截面分布分位数。
 *
 * <p>口径：
 * <ul>
 *   <li>数据来源：selector_result_item（已通过质量闸的全部候选，非 TopN 子集）。</li>
 *   <li>覆盖率 = final_score 非空且 > 0 的候选数 / 总候选数。</li>
 *   <li>分布分位数：min / p25 / median / p75 / max（线性插值）。</li>
 *   <li>仅作研究评价，禁止据此直接调整因子权重。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorResearchService {

    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> research(String strategy, LocalDate startDate, LocalDate endDate) {
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

        List<String> resultIds = latestPerKey.values().stream().map(SelectorResult::getResultId).toList();
        List<SelectorResultItem> allItems = resultIds.isEmpty()
                ? List.of()
                : selectorResultItemRepository.findByResultIdInOrderByRankNo(resultIds);

        List<Double> scores = new ArrayList<>();
        int validCount = 0;
        for (SelectorResultItem it : allItems) {
            if (it.getFinalScore() != null && it.getFinalScore() > 0) {
                scores.add(it.getFinalScore());
                validCount++;
            }
        }
        Collections.sort(scores);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", strategy != null ? strategy : "ALL");
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());
        out.put("cross_section_days", latestPerKey.size());
        out.put("total_observations", allItems.size());
        out.put("valid_observations", validCount);
        out.put("coverage", allItems.isEmpty() ? 0.0
                : Math.round(validCount * 10000.0 / allItems.size()) / 100.0);
        out.put("distribution", buildDistribution(scores));
        out.put("note", "仅作因子研究评价（覆盖率/分布），禁止据此直接调整因子权重");
        return out;
    }

    private Map<String, Object> buildDistribution(List<Double> sortedScores) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (sortedScores.isEmpty()) {
            d.put("min", 0.0);
            d.put("p25", 0.0);
            d.put("median", 0.0);
            d.put("p75", 0.0);
            d.put("max", 0.0);
            return d;
        }
        int n = sortedScores.size();
        d.put("min", round2(sortedScores.get(0)));
        d.put("p25", round2(percentile(sortedScores, 0.25)));
        d.put("median", round2(percentile(sortedScores, 0.50)));
        d.put("p75", round2(percentile(sortedScores, 0.75)));
        d.put("max", round2(sortedScores.get(n - 1)));
        return d;
    }

    /** 线性插值分位数 */
    private double percentile(List<Double> sorted, double q) {
        int n = sorted.size();
        if (n == 1) return sorted.get(0);
        double pos = q * (n - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted.get(lo);
        double frac = pos - lo;
        return sorted.get(lo) * (1 - frac) + sorted.get(hi) * frac;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
