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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 策略对比工作台：同日多策略选股结果并排、重合度分析。
 *
 * <p>口径：
 * <ul>
 *   <li>取指定交易日各策略最新成功结果，按 TopN 截取候选。</li>
 *   <li>重合股票：被 ≥2 个策略同时选中的标的，按命中策略数降序。</li>
 *   <li>重合度 = 重合股票数 / 全部唯一候选数；高重合说明多策略共识强。</li>
 *   <li>仅作研究对比，不直接给出买卖建议。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyCompareService {

    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> compare(LocalDate tradeDate, int topN) {
        List<SelectorResult> results = selectorResultRepository
                .findSuccessByTradeDateOrderByStrategyAndCreatedAtDesc(tradeDate);

        // 同策略保留最新一条
        Map<String, SelectorResult> latestPerStrategy = new LinkedHashMap<>();
        for (SelectorResult r : results) {
            latestPerStrategy.putIfAbsent(r.getStrategyType(), r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", tradeDate.toString());
        out.put("strategy_count", latestPerStrategy.size());

        if (latestPerStrategy.isEmpty()) {
            out.put("strategies", List.of());
            out.put("overlap_stocks", List.of());
            out.put("overlap_summary", Map.of(
                    "unique_count", 0,
                    "overlap_count", 0,
                    "overlap_ratio", 0.0,
                    "verdict", "该交易日无成功选股结果"));
            return out;
        }

        // 批量拉取 items
        List<String> resultIds = latestPerStrategy.values().stream().map(SelectorResult::getResultId).toList();
        List<SelectorResultItem> allItems = selectorResultItemRepository.findByResultIdInOrderByRankNo(resultIds);
        Map<String, List<SelectorResultItem>> itemsByResultId = new LinkedHashMap<>();
        for (SelectorResultItem it : allItems) {
            itemsByResultId.computeIfAbsent(it.getResultId(), k -> new ArrayList<>()).add(it);
        }

        // 各策略 TopN
        List<Map<String, Object>> strategySections = new ArrayList<>();
        // stockKey -> 命中的策略列表
        Map<String, List<String>> hitStrategies = new TreeMap<>();
        Map<String, SelectorResultItem> itemByKey = new LinkedHashMap<>();

        for (SelectorResult r : latestPerStrategy.values()) {
            List<SelectorResultItem> items = itemsByResultId.getOrDefault(r.getResultId(), List.of());
            List<SelectorResultItem> top = items.stream()
                    .sorted((a, b) -> Integer.compare(
                            a.getRankNo() != null ? a.getRankNo() : Integer.MAX_VALUE,
                            b.getRankNo() != null ? b.getRankNo() : Integer.MAX_VALUE))
                    .limit(Math.max(1, topN))
                    .toList();

            List<Map<String, Object>> topList = new ArrayList<>();
            for (SelectorResultItem it : top) {
                String key = it.getStockCode() + "|" + it.getExchangeType();
                hitStrategies.computeIfAbsent(key, k -> new ArrayList<>()).add(r.getStrategyType());
                itemByKey.putIfAbsent(key, it);

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rank_no", it.getRankNo());
                m.put("stock_code", it.getStockCode());
                m.put("exchange_type", it.getExchangeType());
                m.put("stock_name", it.getStockName());
                m.put("final_score", it.getFinalScore());
                topList.add(m);
            }

            Map<String, Object> section = new LinkedHashMap<>();
            section.put("strategy", r.getStrategyType());
            section.put("factor_version", r.getFactorVersion());
            section.put("result_id", r.getResultId());
            section.put("returned_items", items.size());
            section.put("top_items", topList);
            strategySections.add(section);
        }

        // 重合股票：命中 ≥2 个策略
        List<Map<String, Object>> overlapStocks = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : hitStrategies.entrySet()) {
            if (e.getValue().size() < 2) continue;
            SelectorResultItem it = itemByKey.get(e.getKey());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stock_code", it.getStockCode());
            m.put("exchange_type", it.getExchangeType());
            m.put("stock_name", it.getStockName());
            m.put("hit_strategies", e.getValue());
            m.put("hit_count", e.getValue().size());
            overlapStocks.add(m);
        }
        overlapStocks.sort((a, b) -> Integer.compare(
                ((Number) b.get("hit_count")).intValue(),
                ((Number) a.get("hit_count")).intValue()));

        int uniqueCount = hitStrategies.size();
        int overlapCount = overlapStocks.size();
        double overlapRatio = uniqueCount > 0 ? Math.round(overlapCount * 10000.0 / uniqueCount) / 100.0 : 0.0;

        out.put("strategies", strategySections);
        out.put("overlap_stocks", overlapStocks);
        out.put("overlap_summary", Map.of(
                "unique_count", uniqueCount,
                "overlap_count", overlapCount,
                "overlap_ratio", overlapRatio,
                "verdict", overlapCount > 0
                        ? overlapCount + " 只股票被多策略同时选中（共识度 " + overlapRatio + "%）"
                        : "无多策略重合标的，各策略观点分散"));
        return out;
    }
}
