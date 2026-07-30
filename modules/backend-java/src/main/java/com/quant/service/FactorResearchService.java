package com.quant.service;

import com.quant.entity.MarketData;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.service.backtest.FactorResearchEngine;
import com.quant.service.backtest.FactorResearchEngine.CrossSectionObservation;
import com.quant.service.backtest.FactorResearchEngine.DailyRankIc;
import com.quant.service.backtest.FactorResearchEngine.FactorResearchSummary;
import com.quant.service.backtest.FactorResearchEngine.GroupReturnStat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 因子研究面板：得分覆盖率、截面分布分位数、Rank IC 与分组收益。
 *
 * <p>口径：
 * <ul>
 *   <li>数据来源：selector_result_item（已通过质量闸的全部候选，非 TopN 子集）。</li>
 *   <li>覆盖率 = final_score 非空且 > 0 的候选数 / 总候选数。</li>
 *   <li>分布分位数：min / p25 / median / p75 / max（线性插值）。</li>
 *   <li>Rank IC：信号日得分与 forward return 的 Spearman 秩相关；
 *       forward return = close[t+h] / close[t] - 1（t 为信号日，h 为持仓交易日数）。</li>
 *   <li>IC 收益口径说明：close[t]→close[t+h] 收益，<b>未计</b>佣金/印花税/滑点/涨跌停可成交性/停牌，
 *       与 {@code ScoringBacktestService} 的可执行回测收益口径不同，仅用于因子方向预测力评价。</li>
 *   <li>未复现历史时点股票池，存在幸存者偏差，仅作研究评价，禁止据此直接调整因子权重。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorResearchService {

    private static final int DEFAULT_RANK_IC_HOLDING_DAYS = 5;
    private static final int RANK_IC_GROUP_COUNT = 5;
    private static final int RANK_IC_MIN_CROSS_SECTION_SIZE = 10;
    private static final String RANK_IC_UNIVERSE_NOTE =
            "基于 persisted 全截面候选（通过质量闸的全部条目，非 TopN 子集）；未复现历史时点股票池，存在幸存者偏差";
    private static final String RANK_IC_BASIS_NOTE =
            "forward return = close[t+h]/close[t]-1，未计费用/滑点/涨跌停可成交性；与回测可执行收益口径不同，仅用于因子方向预测力评价";

    private final SelectorResultRepository selectorResultRepository;
    private final SelectorResultItemRepository selectorResultItemRepository;
    private final MarketDataRepository marketDataRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> research(String strategy, LocalDate startDate, LocalDate endDate) {
        return research(strategy, startDate, endDate, DEFAULT_RANK_IC_HOLDING_DAYS);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> research(String strategy, LocalDate startDate, LocalDate endDate, Integer holdingDays) {
        int holding = resolveHoldingDays(holdingDays);
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
        Map<String, List<SelectorResultItem>> itemsByResultId = new LinkedHashMap<>();
        for (SelectorResultItem it : allItems) {
            itemsByResultId.computeIfAbsent(it.getResultId(), k -> new ArrayList<>()).add(it);
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
        out.put("group_bands", buildGroupBands(scores, 5));
        out.put("rank_ic", buildRankIc(latestPerKey, itemsByResultId, startDate, endDate, holding));
        out.put("note", "仅作因子研究评价（覆盖率/分布/分组/Rank IC），禁止据此直接调整因子权重");
        return out;
    }

    /**
     * Rank IC 研究块：复用 {@link FactorResearchEngine} 计算信号日得分与 forward return 的 Spearman 秩相关。
     *
     * <p>forward return 口径：close[t+h] / close[t] - 1，t 为信号日，h 为持仓交易日数；
     * 信号日 t 在交易日历中的位置 baseIdx，退出日 exitIdx = baseIdx + h，若 exitIdx 超出可用日历
     * （信号日过接近数据末端，forward 区间尚未形成）则该日跳过，不外推、不使用未来数据。
     */
    private Map<String, Object> buildRankIc(
            Map<String, SelectorResult> latestPerKey,
            Map<String, List<SelectorResultItem>> itemsByResultId,
            LocalDate startDate,
            LocalDate endDate,
            int holdingDays) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("holding_days", holdingDays);
        block.put("group_count", RANK_IC_GROUP_COUNT);
        block.put("basis_note", RANK_IC_BASIS_NOTE);
        block.put("universe_note", RANK_IC_UNIVERSE_NOTE);

        if (latestPerKey.isEmpty()) {
            block.put("cross_section_days", 0);
            block.put("total_observations", 0);
            block.put("rank_ic_mean", 0.0);
            block.put("rank_ic_std", 0.0);
            block.put("rank_ic_ir", 0.0);
            block.put("rank_ic_positive_rate", 0.0);
            block.put("long_short_spread", 0.0);
            block.put("daily_rank_ic", List.of());
            block.put("grouped_returns", List.of());
            return block;
        }

        // 交易日历：覆盖 [startDate, endDate + holdingDays*2] 以留足 forward 区间
        LocalDate calStart = startDate;
        LocalDate calEnd = endDate.plusDays(holdingDays * 2L);
        List<LocalDate> tradingDays = marketDataRepository.findDistinctTradeDatesBetween(calStart, calEnd);
        Map<LocalDate, Integer> dayIndex = new HashMap<>();
        for (int i = 0; i < tradingDays.size(); i++) {
            dayIndex.put(tradingDays.get(i), i);
        }

        // 收集候选涉及的证券，按证券批量拉取区间行情，构建 close 查找表（避免 N+1）
        Map<String, BigDecimal> closeMap = new HashMap<>();
        Map<String, Map<LocalDate, BigDecimal>> perStockDates = new HashMap<>();
        for (List<SelectorResultItem> items : itemsByResultId.values()) {
            for (SelectorResultItem it : items) {
                String stockKey = it.getStockCode() + "|" + it.getExchangeType();
                perStockDates.computeIfAbsent(stockKey, k -> new HashMap<>());
            }
        }
        for (String stockKey : perStockDates.keySet()) {
            int sep = stockKey.indexOf('|');
            String code = stockKey.substring(0, sep);
            String ex = stockKey.substring(sep + 1);
            List<MarketData> bars = marketDataRepository
                    .findByStockCodeAndExchangeTypeAndTradeDateBetweenOrderByTradeDateAsc(code, ex, calStart, calEnd);
            Map<LocalDate, BigDecimal> dates = perStockDates.get(stockKey);
            for (MarketData bar : bars) {
                if (bar.getClosePrice() != null) {
                    dates.put(bar.getTradeDate(), bar.getClosePrice());
                    closeMap.put(stockKey + "|" + bar.getTradeDate(), bar.getClosePrice());
                }
            }
        }

        // 逐信号日构建截面观测
        Map<String, List<CrossSectionObservation>> observationsByDate = new LinkedHashMap<>();
        for (SelectorResult result : latestPerKey.values()) {
            LocalDate signalDate = result.getTradeDate();
            Integer baseIdx = dayIndex.get(signalDate);
            if (baseIdx == null) {
                continue;
            }
            int exitIdx = baseIdx + holdingDays;
            if (exitIdx >= tradingDays.size()) {
                // forward 区间尚未形成，跳过该日（不外推、不用未来数据）
                continue;
            }
            LocalDate exitDate = tradingDays.get(exitIdx);
            List<SelectorResultItem> items = itemsByResultId.getOrDefault(result.getResultId(), List.of());
            List<CrossSectionObservation> obs = new ArrayList<>();
            for (SelectorResultItem it : items) {
                if (it.getFinalScore() == null || it.getFinalScore() <= 0) {
                    continue;
                }
                String stockKey = it.getStockCode() + "|" + it.getExchangeType();
                BigDecimal closeSignal = closeMap.get(stockKey + "|" + signalDate);
                BigDecimal closeExit = closeMap.get(stockKey + "|" + exitDate);
                if (closeSignal == null || closeExit == null) {
                    continue;
                }
                double cs = closeSignal.doubleValue();
                double ce = closeExit.doubleValue();
                if (cs <= 0 || ce <= 0) {
                    continue;
                }
                double forwardReturn = ce / cs - 1.0;
                obs.add(new CrossSectionObservation(it.getFinalScore(), forwardReturn));
            }
            if (!obs.isEmpty()) {
                observationsByDate.put(signalDate.toString(), obs);
            }
        }

        FactorResearchSummary summary = FactorResearchEngine.summarize(
                observationsByDate,
                holdingDays,
                RANK_IC_GROUP_COUNT,
                RANK_IC_MIN_CROSS_SECTION_SIZE,
                RANK_IC_UNIVERSE_NOTE
        );

        block.put("cross_section_days", summary.crossSectionDays());
        block.put("total_observations", summary.totalObservations());
        block.put("rank_ic_mean", round4(summary.rankIcMean()));
        block.put("rank_ic_std", round4(summary.rankIcStd()));
        block.put("rank_ic_ir", round4(summary.rankIcIr()));
        block.put("rank_ic_positive_rate", round4(summary.rankIcPositiveRate()));
        block.put("long_short_spread", round4(summary.longShortSpread()));

        List<Map<String, Object>> daily = new ArrayList<>();
        for (DailyRankIc d : summary.dailyRankIc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trade_date", d.tradeDate());
            row.put("rank_ic", round4(d.rankIc()));
            row.put("sample_size", d.sampleSize());
            daily.add(row);
        }
        block.put("daily_rank_ic", daily);

        List<Map<String, Object>> grouped = new ArrayList<>();
        for (GroupReturnStat g : summary.groupedReturns()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group_no", g.groupNo());
            row.put("label", g.label());
            row.put("avg_return", round4(g.avgReturn()));
            row.put("observations", g.observations());
            grouped.add(row);
        }
        block.put("grouped_returns", grouped);
        return block;
    }

    private static int resolveHoldingDays(Integer holdingDays) {
        if (holdingDays == null || holdingDays < 1) {
            return DEFAULT_RANK_IC_HOLDING_DAYS;
        }
        return Math.min(holdingDays, 60);
    }

    /** 按数量等分为 groupCount 组，返回每组的得分范围与数量，展示得分集中度。 */
    private List<Map<String, Object>> buildGroupBands(List<Double> sortedScores, int groupCount) {
        List<Map<String, Object>> bands = new ArrayList<>();
        int n = sortedScores.size();
        if (n == 0 || groupCount <= 0) {
            return bands;
        }
        int effectiveGroups = Math.min(groupCount, n);
        int baseSize = n / effectiveGroups;
        int remainder = n % effectiveGroups;
        int idx = 0;
        for (int g = 0; g < effectiveGroups; g++) {
            int size = baseSize + (g < remainder ? 1 : 0);
            double lo = sortedScores.get(idx);
            double hi = sortedScores.get(idx + size - 1);
            double avg = 0;
            for (int i = idx; i < idx + size; i++) {
                avg += sortedScores.get(i);
            }
            avg /= size;
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("group_no", g + 1);
            b.put("min_score", round2(lo));
            b.put("max_score", round2(hi));
            b.put("avg_score", round2(avg));
            b.put("count", size);
            bands.add(b);
            idx += size;
        }
        return bands;
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

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
