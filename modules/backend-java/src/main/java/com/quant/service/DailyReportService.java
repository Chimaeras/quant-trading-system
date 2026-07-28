package com.quant.service;

import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.entity.StockInfo;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.repository.StockInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选股日报：聚合各策略最新选股结果，输出每日信号摘要。
 *
 * <p>口径：
 * <ul>
 *   <li>数据来源：已持久化的 selector_result（runStatus=SUCCESS），不重新计算，无未来函数。</li>
 *   <li>信号日 = 选股结果的 tradeDate；可成交时点 = 信号日次日开盘（由打分回测口径定义）。</li>
 *   <li>「今日无合格标的」：某策略 returned_items=0 视为无机会，不强行凑满。</li>
 *   <li>可回溯：每策略附 factor_version、trade_date、策略版本快照摘要。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReportService {

    private static final int TOP_ITEMS_IN_REPORT = 5;
    /** 单行业占比超过该阈值时提示集中度风险 */
    private static final double INDUSTRY_CONCENTRATION_WARN_PCT = 0.40;

    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final StockInfoRepository stockInfoRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> buildDailyReport(LocalDate tradeDate) {
        // 1. 取结果：指定交易日各策略成功结果；未指定时取各策略最新成功结果
        List<SelectorResult> results;
        LocalDate reportDate;
        if (tradeDate != null) {
            results = selectorResultRepository.findSuccessByTradeDateOrderByStrategyAndCreatedAtDesc(tradeDate);
            reportDate = tradeDate;
        } else {
            results = selectorResultRepository.findLatestSuccessPerStrategy();
            reportDate = results.stream().map(SelectorResult::getTradeDate).max(LocalDate::compareTo).orElse(null);
        }

        // 同策略同日可能多条（不同指纹），保留 createdAt 最新一条
        Map<String, SelectorResult> latestPerStrategy = new LinkedHashMap<>();
        for (SelectorResult r : results) {
            latestPerStrategy.putIfAbsent(r.getStrategyType(), r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trade_date", reportDate != null ? reportDate.toString() : "");
        out.put("generated_at", LocalDateTime.now().toString());
        out.put("data_source", "persisted_selector_result");
        out.put("signal_execution_note", "信号日为选股结果 tradeDate；可成交时点为信号日次日开盘（A 股 T+1）");

        if (latestPerStrategy.isEmpty()) {
            out.put("has_any_result", false);
            out.put("overall_summary", buildOverallSummary(List.of()));
            out.put("strategies", List.of());
            out.put("message", reportDate != null
                    ? "指定交易日无成功选股结果"
                    : "尚无任何选股结果，请先运行选股任务");
            return out;
        }

        // 2. 批量拉取各结果的 items（仅取 TopN 摘要）
        List<String> resultIds = latestPerStrategy.values().stream()
                .map(SelectorResult::getResultId)
                .toList();
        List<SelectorResultItem> allItems = selectorResultItemRepository.findByResultIdInOrderByRankNo(resultIds);
        Map<String, List<SelectorResultItem>> itemsByResultId = new LinkedHashMap<>();
        for (SelectorResultItem item : allItems) {
            itemsByResultId.computeIfAbsent(item.getResultId(), k -> new ArrayList<>()).add(item);
        }

        List<Map<String, Object>> strategySections = new ArrayList<>();
        for (SelectorResult r : latestPerStrategy.values()) {
            strategySections.add(buildStrategySection(
                    r, itemsByResultId.getOrDefault(r.getResultId(), List.of())));
        }

        out.put("has_any_result", true);
        out.put("overall_summary", buildOverallSummary(strategySections));
        out.put("strategies", strategySections);
        return out;
    }

    /**
     * 聚合单策略候选的行业分布与集中度提示。
     * 口径：按 stock_info.industry 统计；industry 缺失归入「未分类」。
     */
    private Map<String, Object> buildIndustryConcentration(List<SelectorResultItem> items) {
        if (items == null || items.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("total", 0);
            empty.put("industries", List.of());
            empty.put("max_concentration_pct", 0.0);
            empty.put("warning", null);
            return empty;
        }

        // 批量拉取行业信息
        List<String> keys = items.stream()
                .map(i -> i.getStockCode() + "|" + i.getExchangeType())
                .toList();
        Map<String, String> industryByKey = new LinkedHashMap<>();
        for (StockInfo info : stockInfoRepository.findByStockKeyIn(keys)) {
            String ind = info.getIndustry();
            industryByKey.put(info.getStockCode() + "|" + info.getExchangeType(),
                    ind != null && !ind.isBlank() ? ind : "未分类");
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SelectorResultItem item : items) {
            String key = item.getStockCode() + "|" + item.getExchangeType();
            String industry = industryByKey.getOrDefault(key, "未分类");
            counts.merge(industry, 1, Integer::sum);
        }

        int total = items.size();
        List<Map<String, Object>> industries = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("industry", e.getKey());
                    m.put("count", e.getValue());
                    m.put("pct", Math.round(e.getValue() * 10000.0 / total) / 100.0);
                    industries.add(m);
                });

        double maxPct = industries.isEmpty() ? 0.0 : ((Number) industries.get(0).get("pct")).doubleValue() / 100.0;
        String warning = null;
        if (maxPct >= INDUSTRY_CONCENTRATION_WARN_PCT) {
            warning = "候选集中在「" + industries.get(0).get("industry")
                    + "」(" + industries.get(0).get("pct") + "%)，注意单一行业集中风险";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("industries", industries);
        out.put("max_concentration_pct", Math.round(maxPct * 10000.0) / 100.0);
        out.put("warning", warning);
        out.put("threshold_pct", INDUSTRY_CONCENTRATION_WARN_PCT * 100);
        return out;
    }

    private Map<String, Object> buildStrategySection(SelectorResult r, List<SelectorResultItem> items) {
        List<SelectorResultItem> sorted = items.stream()
                .sorted((a, b) -> Integer.compare(
                        a.getRankNo() != null ? a.getRankNo() : Integer.MAX_VALUE,
                        b.getRankNo() != null ? b.getRankNo() : Integer.MAX_VALUE))
                .toList();

        List<Map<String, Object>> topItems = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_ITEMS_IN_REPORT, sorted.size()); i++) {
            SelectorResultItem item = sorted.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank_no", item.getRankNo());
            m.put("stock_code", item.getStockCode());
            m.put("exchange_type", item.getExchangeType());
            m.put("stock_name", item.getStockName());
            m.put("final_score", item.getFinalScore());
            topItems.add(m);
        }

        int returnedItems = sorted.size();
        boolean hasOpportunity = returnedItems > 0;

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("strategy", r.getStrategyType());
        section.put("factor_version", r.getFactorVersion());
        section.put("trade_date", r.getTradeDate() != null ? r.getTradeDate().toString() : "");
        section.put("run_status", r.getRunStatus());
        section.put("total_candidates", r.getTotalCandidates());
        section.put("success_candidates", r.getSuccessCandidates());
        section.put("returned_items", returnedItems);
        section.put("has_opportunity", hasOpportunity);
        section.put("message", hasOpportunity
                ? "共 " + returnedItems + " 个候选（展示前 " + topItems.size() + "）"
                : "今日无合格标的（未强行凑满 TopN）");
        section.put("top_items", topItems);
        section.put("industry_concentration", buildIndustryConcentration(sorted));
        return section;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildOverallSummary(List<Map<String, Object>> strategySections) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int strategyCount = strategySections.size();
        long withOpportunity = strategySections.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("has_opportunity")))
                .count();
        summary.put("strategy_count", strategyCount);
        summary.put("strategies_with_opportunity", withOpportunity);
        summary.put("strategies_without_opportunity", strategyCount - withOpportunity);
        summary.put("verdict", withOpportunity > 0
                ? "有 " + withOpportunity + " / " + strategyCount + " 个策略给出候选"
                : "所有策略均无合格标的，今日无机会");
        return summary;
    }

    /**
     * 多日信号趋势：给定日期范围，输出每日各策略候选数量与机会变化。
     *
     * <p>口径：
     * <ul>
     *   <li>数据来源：已持久化的 selector_result（SUCCESS），无未来函数。</li>
     *   <li>同策略同日取最新一条；按 trade_date 升序。</li>
     *   <li>仅统计 returned_items 与 has_opportunity，不展开候选明细。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildTrendReport(LocalDate startDate, LocalDate endDate) {
        List<SelectorResult> results = selectorResultRepository
                .findByTradeDateBetweenOrderByCreatedAtDesc(startDate, endDate);

        // 同策略同日取最新
        Map<String, SelectorResult> latestPerKey = new LinkedHashMap<>();
        for (SelectorResult r : results) {
            latestPerKey.putIfAbsent(r.getStrategyType() + "|" + r.getTradeDate(), r);
        }

        // 按 trade_date 分组
        Map<LocalDate, List<SelectorResult>> byDate = new LinkedHashMap<>();
        for (SelectorResult r : latestPerKey.values()) {
            byDate.computeIfAbsent(r.getTradeDate(), k -> new ArrayList<>()).add(r);
        }
        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        dates.sort(LocalDate::compareTo);

        // 收集所有策略名
        List<String> strategies = latestPerKey.values().stream()
                .map(SelectorResult::getStrategyType)
                .distinct()
                .sorted()
                .toList();

        List<Map<String, Object>> dailyTrend = new ArrayList<>();
        for (LocalDate d : dates) {
            List<SelectorResult> dayResults = byDate.get(d);
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("trade_date", d.toString());
            int totalCandidates = 0;
            int strategiesWithOpportunity = 0;
            Map<String, Integer> perStrategy = new LinkedHashMap<>();
            for (String s : strategies) {
                perStrategy.put(s, 0);
            }
            for (SelectorResult r : dayResults) {
                int returned = r.getSuccessCandidates() != null ? r.getSuccessCandidates() : 0;
                perStrategy.put(r.getStrategyType(), returned);
                totalCandidates += returned;
                if (returned > 0) strategiesWithOpportunity++;
            }
            day.put("strategies_with_opportunity", strategiesWithOpportunity);
            day.put("total_candidates", totalCandidates);
            day.put("per_strategy", perStrategy);
            dailyTrend.add(day);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("start_date", startDate.toString());
        out.put("end_date", endDate.toString());
        out.put("strategies", strategies);
        out.put("signal_days", dates.size());
        out.put("daily_trend", dailyTrend);
        out.put("note", "多日信号趋势：基于已持久化选股结果，无未来函数；returned_items=0 视为该策略当日无机会");
        return out;
    }
}
