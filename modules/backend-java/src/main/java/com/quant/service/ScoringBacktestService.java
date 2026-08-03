package com.quant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.quant.dto.ScoringBacktestRequest;
import com.quant.dto.ScoringBacktestResponse;
import com.quant.dto.ScoringBacktestResponse.DailyDetail;
import com.quant.dto.ScoringBacktestResponse.DailyRankIc;
import com.quant.dto.ScoringBacktestResponse.FactorAnalysis;
import com.quant.dto.ScoringBacktestResponse.FactorIcSummary;
import com.quant.dto.ScoringBacktestResponse.GroupReturnStat;
import com.quant.dto.ScoringBacktestResponse.PeriodStat;
import com.quant.dto.ScoringBacktestResponse.StockReturn;
import com.quant.entity.BacktestRun;
import com.quant.entity.MarketData;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.BacktestRunRepository;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
import com.quant.service.backtest.FactorResearchEngine;
import com.quant.service.backtest.FactorResearchEngine.CrossSectionObservation;
import com.quant.service.backtest.FactorResearchEngine.FactorResearchSummary;
import com.quant.service.backtest.StrategyBacktestEngine;
import com.quant.service.backtest.TradingLimitRules;
import com.quant.service.scoring.CodexShortCycleScoringEngine;
import com.quant.service.scoring.StrategyFormulaRegistry;
import com.quant.service.scoring.ScoringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringBacktestService {

    static final String SKIP_NO_QUOTE_ON_ENTRY = "NO_QUOTE_ON_ENTRY_DAY";
    static final String SKIP_INVALID_ENTRY_OPEN = "INVALID_ENTRY_OPEN";
    static final String SKIP_ZERO_VOLUME_ON_ENTRY = "ZERO_VOLUME_ON_ENTRY";
    static final String SKIP_LIMIT_UP_AT_OPEN = "LIMIT_UP_AT_OPEN";
    static final String SKIP_NO_NEXT_TRADING_DAY = "NO_NEXT_TRADING_DAY";
    static final String SKIP_BEYOND_CALENDAR = "BEYOND_CALENDAR";
    static final String SKIP_NO_QUOTE_ON_EXIT = "NO_QUOTE_ON_EXIT_DAY";
    static final String SKIP_INVALID_EXIT_CLOSE = "INVALID_EXIT_CLOSE";
    static final String SKIP_ZERO_VOLUME_ON_EXIT = "ZERO_VOLUME_ON_EXIT";
    static final String SKIP_LIMIT_DOWN_AT_CLOSE = "LIMIT_DOWN_AT_CLOSE";

    private static final int FACTOR_MIN_CROSS_SECTION_SIZE = 10;
    private static final List<String> CODEX_SUB_FACTOR_KEYS = List.of("T", "F", "R");
    private static final String FACTOR_UNIVERSE_NOTE =
            "基于 persisted 全截面候选（通过质量闸的全部条目，非 TopN 子集）；未复现历史时点股票池，存在幸存者偏差";

    private final SelectorResultRepository resultRepo;
    private final SelectorResultItemRepository itemRepo;
    private final MarketDataRepository marketDataRepo;
    private final BacktestRunRepository backtestRunRepository;
    private final ObjectMapper objectMapper;

    public ScoringBacktestResponse run(ScoringBacktestRequest req) {
        log.info("开始打分回测: strategy={}, range=[{} -> {}], topN={}, holdingDays={}",
                req.getStrategyType(), req.getStartDate(), req.getEndDate(),
                req.getTopN(), req.getHoldingDays());

        List<Integer> holdingDays = req.getHoldingDays() != null ? req.getHoldingDays() : List.of(1, 3, 5, 10, 20);
        int maxHolding = holdingDays.stream().max(Integer::compareTo).orElse(20);
        int topN = req.getTopN() != null ? req.getTopN() : 10;
        int factorGroupCount = req.getFactorGroupCount() != null ? req.getFactorGroupCount() : 5;
        int factorHoldingDay = resolveFactorAnalysisHoldingDay(holdingDays, req.getFactorAnalysisHoldingDay());
        double commissionRate = boundedRate(req.getCommissionRate(), 0.0003);
        double sellTaxRate = boundedRate(req.getSellTaxRate(), 0.0005);
        double slippageRate = boundedRate(req.getSlippageRate(), 0.0002);

        // 1. 查询日期范围内的选股结果，按日期取最新的一条
        List<SelectorResult> results = resultRepo
                .findByTradeDateBetweenOrderByCreatedAtDesc(req.getStartDate(), req.getEndDate());
        log.info("查询到 {} 条选股结果 (日期范围 {} ~ {})", results.size(), req.getStartDate(), req.getEndDate());

        if (req.getStrategyType() != null && !req.getStrategyType().isBlank()) {
            final String filterStrategy = req.getStrategyType();
            results = results.stream()
                    .filter(r -> filterStrategy.equals(r.getStrategyType()))
                    .collect(Collectors.toList());
            log.info("按策略类型 {} 过滤后剩余 {} 条", filterStrategy, results.size());
        }
        // 同一交易日取最新一条
        Map<LocalDate, SelectorResult> latestPerDate = new LinkedHashMap<>();
        for (SelectorResult r : results) {
            latestPerDate.putIfAbsent(r.getTradeDate(), r);
        }
        List<SelectorResult> dailyResults = new ArrayList<>(latestPerDate.values());
        dailyResults.sort(Comparator.comparing(SelectorResult::getTradeDate));

        Map<String, Object> assumptions = executionAssumptions(
                commissionRate, sellTaxRate, slippageRate, dailyResults);

        if (dailyResults.isEmpty()) {
            log.warn("未找到符合条件的选股结果");
            return ScoringBacktestResponse.builder()
                    .strategyType(req.getStrategyType())
                    .startDate(req.getStartDate())
                    .endDate(req.getEndDate())
                    .topN(topN)
                    .totalTradingDays(0)
                    .executionAssumptions(assumptions)
                    .holdingPeriodStats(Map.of())
                    .dailyDetails(List.of())
                    .factorAnalysis(null)
                    .factorIcByHolding(Map.of())
                    .formulaAudit(resolveFormulaAudit(req, List.of()))
                    .shortHorizonValidation(Map.of())
                    .codexFactorIcByHolding(Map.of())
                    .build();
        }

        // 2. 批量获取所有 result 的 top N items
        List<String> resultIds = dailyResults.stream()
                .map(SelectorResult::getResultId)
                .collect(Collectors.toList());
        List<SelectorResultItem> allItems = itemRepo.findByResultIdInOrderByRankNo(resultIds);

        Map<String, List<SelectorResultItem>> itemsByResultId = allItems.stream()
                .collect(Collectors.groupingBy(SelectorResultItem::getResultId, LinkedHashMap::new, Collectors.toList()));

        // 3. 获取交易日历
        LocalDate calStart = req.getStartDate();
        LocalDate calEnd = req.getEndDate().plusDays(maxHolding * 2L); // 留足余量
        List<LocalDate> tradingDays = marketDataRepo.findDistinctTradeDatesBetween(calStart, calEnd);
        Map<LocalDate, Integer> dayIndex = new HashMap<>();
        for (int i = 0; i < tradingDays.size(); i++) {
            dayIndex.put(tradingDays.get(i), i);
        }

        // 4. 批量拉取所有行情数据
        List<MarketData> allMarketData = marketDataRepo
                .findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(calStart, calEnd);
        // key = stockCode|exchangeType|tradeDate
        Map<String, MarketData> marketDataMap = new HashMap<>();
        for (MarketData md : allMarketData) {
            String key = priceKey(md.getStockCode(), md.getExchangeType(), md.getTradeDate());
            marketDataMap.put(key, md);
        }
        log.info("预加载 {} 条行情数据, {} 个交易日", allMarketData.size(), tradingDays.size());

        // 5. 逐日计算 forward returns
        List<DailyDetail> dailyDetails = new ArrayList<>();
        Map<Integer, Map<String, List<CrossSectionObservation>>> factorObservationsByHolding = new LinkedHashMap<>();
        Map<Integer, Map<String, Map<String, List<CrossSectionObservation>>>> codexFactorObsByHolding = new LinkedHashMap<>();
        for (int h : holdingDays) {
            factorObservationsByHolding.put(h, new LinkedHashMap<>());
            codexFactorObsByHolding.put(h, new LinkedHashMap<>());
            for (String fk : CODEX_SUB_FACTOR_KEYS) {
                codexFactorObsByHolding.get(h).put(fk, new LinkedHashMap<>());
            }
        }
        // 按持仓天数汇总所有收益率，用于最终统计
        Map<Integer, List<Double>> periodReturns = new LinkedHashMap<>();
        for (int h : holdingDays) {
            periodReturns.put(h, new ArrayList<>());
        }

        for (SelectorResult result : dailyResults) {
            LocalDate tradeDate = result.getTradeDate();
            List<SelectorResultItem> items = itemsByResultId.getOrDefault(result.getResultId(), List.of());
            List<SelectorResultItem> topItems = items.stream()
                    .sorted(Comparator.comparingInt(SelectorResultItem::getRankNo))
                    .limit(topN)
                    .collect(Collectors.toList());
            List<SelectorResultItem> factorItems = items.stream()
                    .sorted(Comparator.comparingInt(SelectorResultItem::getRankNo))
                    .collect(Collectors.toList());

            if (topItems.isEmpty()) continue;

            Integer baseIdx = dayIndex.get(tradeDate);
            if (baseIdx == null) {
                log.debug("交易日 {} 不在日历中，跳过", tradeDate);
                continue;
            }

            List<StockReturn> stockReturns = new ArrayList<>();
            Map<Integer, Double> dailyWinRate = new LinkedHashMap<>();
            Map<Integer, List<Double>> dailyReturns = new LinkedHashMap<>();
            for (int h : holdingDays) {
                dailyReturns.put(h, new ArrayList<>());
            }

            for (SelectorResultItem item : topItems) {
                int entryIdx = baseIdx + 1;
                if (entryIdx >= tradingDays.size()) {
                    addSkippedStock(stockReturns, item, null, SKIP_NO_NEXT_TRADING_DAY);
                    continue;
                }
                LocalDate entryDate = tradingDays.get(entryIdx);
                MarketData entryBar = marketDataMap.get(priceKey(item.getStockCode(), item.getExchangeType(), entryDate));
                if (entryBar == null) {
                    addSkippedStock(stockReturns, item, entryDate, SKIP_NO_QUOTE_ON_ENTRY);
                    continue;
                }
                BigDecimal rawEntryPrice = entryBar.getOpenPrice();
                if (rawEntryPrice == null || rawEntryPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    addSkippedStock(stockReturns, item, entryDate, SKIP_INVALID_ENTRY_OPEN);
                    continue;
                }
                if (isZeroVolume(entryBar)) {
                    log.debug("入场日 {} {} 无成交量（疑似停牌），跳过", entryDate, item.getStockCode());
                    addSkippedStock(stockReturns, item, entryDate, SKIP_ZERO_VOLUME_ON_ENTRY);
                    continue;
                }
                LocalDate prevTradeDate = tradingDays.get(entryIdx - 1);
                MarketData prevBar = marketDataMap.get(
                        priceKey(item.getStockCode(), item.getExchangeType(), prevTradeDate));
                double preClose = StrategyBacktestEngine.resolvePreClose(entryBar, prevBar);
                double limitPct = TradingLimitRules.resolveLimitPct(
                        item.getStockCode(), item.getExchangeType(), item.getStockName());
                if (StrategyBacktestEngine.isLimitUpAtOpen(entryBar, preClose, limitPct)) {
                    log.debug("入场日 {} {} 涨停开盘，跳过", entryDate, item.getStockCode());
                    addSkippedStock(stockReturns, item, entryDate, SKIP_LIMIT_UP_AT_OPEN);
                    continue;
                }
                double entryPrice = rawEntryPrice.doubleValue() * (1 + slippageRate);
                double entryCost = entryPrice * (1 + commissionRate);

                Map<Integer, Double> forwardReturns = new LinkedHashMap<>();
                Map<Integer, String> periodSkipReasons = new LinkedHashMap<>();
                for (int h : holdingDays) {
                    PeriodReturnResult periodResult = computePeriodReturn(
                            item, entryIdx, h, tradingDays, marketDataMap,
                            entryCost, commissionRate, sellTaxRate, slippageRate);
                    if (periodResult.ret() != null) {
                        forwardReturns.put(h, periodResult.ret());
                        periodReturns.get(h).add(periodResult.ret());
                        dailyReturns.get(h).add(periodResult.ret());
                    } else {
                        forwardReturns.put(h, null);
                        if (periodResult.skipReason() != null) {
                            periodSkipReasons.put(h, periodResult.skipReason());
                        }
                    }
                }

                stockReturns.add(StockReturn.builder()
                        .stockCode(item.getStockCode())
                        .exchangeType(item.getExchangeType())
                        .stockName(item.getStockName())
                        .finalScore(item.getFinalScore())
                        .rankNo(item.getRankNo())
                        .entryDate(entryDate)
                        .entryPrice(entryPrice)
                        .forwardReturns(forwardReturns)
                        .periodSkipReasons(periodSkipReasons.isEmpty() ? null : periodSkipReasons)
                        .build());
            }

            // 计算当日各周期的胜率
            for (int h : holdingDays) {
                List<Double> returns = dailyReturns.get(h);
                if (returns.isEmpty()) {
                    dailyWinRate.put(h, null);
                } else {
                    long wins = returns.stream().filter(r -> r > 0).count();
                    dailyWinRate.put(h, (double) wins / returns.size());
                }
            }

            dailyDetails.add(DailyDetail.builder()
                    .tradeDate(tradeDate)
                    .topStocks(stockReturns)
                    .dailyWinRate(dailyWinRate)
                    .build());

            for (SelectorResultItem item : factorItems) {
                EntryContext entry = resolveEntryContext(
                        item, baseIdx, tradingDays, marketDataMap,
                        commissionRate, slippageRate);
                if (entry == null) {
                    continue;
                }
                for (int h : holdingDays) {
                    PeriodReturnResult periodResult = computePeriodReturn(
                            item, entry.entryIdx(), h, tradingDays, marketDataMap,
                            entry.entryCost(), commissionRate, sellTaxRate, slippageRate);
                    if (periodResult.ret() == null) {
                        continue;
                    }
                    String dateKey = tradeDate.toString();
                    factorObservationsByHolding.get(h)
                            .computeIfAbsent(dateKey, k -> new ArrayList<>())
                            .add(new CrossSectionObservation(item.getFinalScore(), periodResult.ret()));
                    for (String factorKey : CODEX_SUB_FACTOR_KEYS) {
                        Double subScore = extractReasonNumber(item.getReasons(), factorKey);
                        if (subScore == null) {
                            continue;
                        }
                        codexFactorObsByHolding.get(h).get(factorKey)
                                .computeIfAbsent(dateKey, k -> new ArrayList<>())
                                .add(new CrossSectionObservation(subScore, periodResult.ret()));
                    }
                }
            }
        }

        // 6. 汇总各持仓周期的统计
        Map<Integer, PeriodStat> holdingPeriodStats = new LinkedHashMap<>();
        for (int h : holdingDays) {
            List<Double> returns = periodReturns.get(h);
            if (returns == null || returns.isEmpty()) {
                holdingPeriodStats.put(h, PeriodStat.builder()
                        .winRate(0).avgReturn(0).medianReturn(0)
                        .maxReturn(0).minReturn(0).totalObservations(0)
                        .build());
                continue;
            }
            List<Double> sorted = new ArrayList<>(returns);
            Collections.sort(sorted);
            double avg = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double median = sorted.size() % 2 == 1
                    ? sorted.get(sorted.size() / 2)
                    : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0;
            long wins = returns.stream().filter(r -> r > 0).count();

            // 胜率盈亏比：平均盈利 / |平均亏损|
            double avgWin = returns.stream().filter(r -> r > 0).mapToDouble(Double::doubleValue).average().orElse(0);
            double avgLoss = returns.stream().filter(r -> r < 0).mapToDouble(Double::doubleValue).average().orElse(0);
            double profitLossRatio = avgLoss < 0 ? avgWin / Math.abs(avgLoss) : 0;

            holdingPeriodStats.put(h, PeriodStat.builder()
                    .winRate((double) wins / returns.size())
                    .avgReturn(avg)
                    .medianReturn(median)
                    .maxReturn(sorted.get(sorted.size() - 1))
                    .minReturn(sorted.get(0))
                    .totalObservations(returns.size())
                    .avgWin(avgWin)
                    .avgLoss(avgLoss)
                    .profitLossRatio(profitLossRatio)
                    .build());
        }

        log.info("打分回测完成: {} 个交易日, {} 个周期统计完毕", dailyDetails.size(), holdingPeriodStats.size());

        Map<Integer, FactorIcSummary> factorIcByHolding = new LinkedHashMap<>();
        Map<Integer, Map<String, FactorIcSummary>> codexFactorIcByHolding = new LinkedHashMap<>();
        FactorResearchSummary factorSummary = null;
        for (int h : holdingDays) {
            FactorResearchSummary summary = FactorResearchEngine.summarize(
                    factorObservationsByHolding.getOrDefault(h, Map.of()),
                    h,
                    factorGroupCount,
                    FACTOR_MIN_CROSS_SECTION_SIZE,
                    FACTOR_UNIVERSE_NOTE
            );
            if (summary.crossSectionDays() > 0) {
                factorIcByHolding.put(h, toFactorIcSummaryDto(summary));
            }
            Map<String, FactorIcSummary> subMap = new LinkedHashMap<>();
            for (String factorKey : CODEX_SUB_FACTOR_KEYS) {
                FactorResearchSummary subSummary = FactorResearchEngine.summarize(
                        codexFactorObsByHolding.getOrDefault(h, Map.of()).getOrDefault(factorKey, Map.of()),
                        h,
                        factorGroupCount,
                        FACTOR_MIN_CROSS_SECTION_SIZE,
                        "Codex 子因子 " + factorKey + "；" + FACTOR_UNIVERSE_NOTE
                );
                if (subSummary.crossSectionDays() > 0) {
                    subMap.put(factorKey, toFactorIcSummaryDto(subSummary));
                }
            }
            if (!subMap.isEmpty()) {
                codexFactorIcByHolding.put(h, subMap);
            }
            if (h == factorHoldingDay) {
                factorSummary = summary;
            }
        }

        Map<String, Object> shortValidation = buildShortHorizonValidation(factorIcByHolding, factorSummary);
        mergeCodexFactorValidation(shortValidation, codexFactorIcByHolding);

        ScoringBacktestResponse response = ScoringBacktestResponse.builder()
                .strategyType(req.getStrategyType())
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .topN(topN)
                .totalTradingDays(dailyDetails.size())
                .executionAssumptions(assumptions)
                .holdingPeriodStats(holdingPeriodStats)
                .dailyDetails(dailyDetails)
                .factorAnalysis(toFactorAnalysisDto(factorSummary))
                .factorIcByHolding(factorIcByHolding)
                .formulaAudit(resolveFormulaAudit(req, dailyResults))
                .shortHorizonValidation(shortValidation)
                .codexFactorIcByHolding(codexFactorIcByHolding)
                .build();
        persistRun(req, response);
        return response;
    }

    /**
     * 持久化回测运行记录（参数+假设+数据截止日+完整结果），供按 resultId 回溯/分享。
     * 持久化失败不阻断回测主流程，仅告警。
     */
    private void persistRun(ScoringBacktestRequest req, ScoringBacktestResponse response) {
        try {
            if (response.getTotalTradingDays() == 0) {
                return; // 无有效回测数据不落库，避免污染历史列表
            }
            BacktestRun run = new BacktestRun();
            run.setResultId(generateResultId());
            run.setStrategyType(req.getStrategyType() != null ? req.getStrategyType() : "ALL");
            run.setStartDate(req.getStartDate());
            run.setEndDate(req.getEndDate());
            run.setTopN(response.getTopN());
            run.setHoldingDays(toJson(req.getHoldingDays()));
            run.setCommissionRate(BigDecimal.valueOf(req.getCommissionRate() != null ? req.getCommissionRate() : 0.0003));
            run.setSellTaxRate(BigDecimal.valueOf(req.getSellTaxRate() != null ? req.getSellTaxRate() : 0.0005));
            run.setSlippageRate(BigDecimal.valueOf(req.getSlippageRate() != null ? req.getSlippageRate() : 0.0002));
            run.setLatestDataDate(marketDataRepo.findMaxTradeDate().orElse(null));
            run.setAssumptions(toJson(response.getExecutionAssumptions()));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalTradingDays", response.getTotalTradingDays());
            summary.put("holdingPeriodStats", response.getHoldingPeriodStats());
            run.setSummary(toJson(summary));
            // 先回填 resultId 再序列化，保证存储 JSON 自包含（含 result_id）
            response.setResultId(run.getResultId());
            run.setResponse(toJson(response));
            backtestRunRepository.save(run);
        } catch (Exception e) {
            log.warn("回测结果持久化失败: {}", e.getMessage());
        }
    }

    /** 最近的历史回测运行列表（摘要，不含完整明细）。 */
    public List<Map<String, Object>> listRuns(int limit) {
        int n = Math.max(1, Math.min(limit > 0 ? limit : 20, 100));
        List<Map<String, Object>> out = new ArrayList<>();
        for (BacktestRun r : backtestRunRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, n))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("result_id", r.getResultId());
            m.put("strategy_type", r.getStrategyType());
            m.put("start_date", r.getStartDate());
            m.put("end_date", r.getEndDate());
            m.put("top_n", r.getTopN());
            m.put("latest_data_date", r.getLatestDataDate());
            m.put("created_at", r.getCreatedAt());
            try {
                m.put("summary", objectMapper.readValue(r.getSummary(), new TypeReference<Map<String, Object>>() {}));
            } catch (Exception ignore) {
                // summary 损坏时不影响列表
            }
            out.add(m);
        }
        return out;
    }

    /** 按 resultId 回溯完整回测结果；不存在返回 null。 */
    public ScoringBacktestResponse getRun(String resultId) {
        return backtestRunRepository.findByResultId(resultId).map(r -> {
            try {
                ScoringBacktestResponse resp = objectMapper.readValue(r.getResponse(), ScoringBacktestResponse.class);
                resp.setResultId(r.getResultId());
                return resp;
            } catch (Exception e) {
                log.warn("回溯回测结果反序列化失败 resultId={}: {}", resultId, e.getMessage());
                return null;
            }
        }).orElse(null);
    }

    private static String generateResultId() {
        String ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.LocalDateTime.now());
        return "BT-" + ts + "-" + String.format("%04d", java.util.concurrent.ThreadLocalRandom.current().nextInt(10000));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    static void mergeCodexFactorValidation(
            Map<String, Object> validation,
            Map<Integer, Map<String, FactorIcSummary>> codexFactorIcByHolding
    ) {
        if (codexFactorIcByHolding == null || codexFactorIcByHolding.isEmpty()) {
            validation.put("codex_sub_factors_available", false);
            return;
        }
        validation.put("codex_sub_factors_available", true);
        Map<String, Object> t3 = new LinkedHashMap<>();
        Map<String, FactorIcSummary> ic3 = codexFactorIcByHolding.get(3);
        Map<String, FactorIcSummary> ic5 = codexFactorIcByHolding.get(5);
        for (String key : CODEX_SUB_FACTOR_KEYS) {
            if (ic3 != null && ic3.get(key) != null) {
                t3.put(key + "_t3_ic", ic3.get(key).getRankIcMean());
            }
            if (ic5 != null && ic5.get(key) != null) {
                t3.put(key + "_t5_ic", ic5.get(key).getRankIcMean());
            }
        }
        validation.put("codex_sub_factor_ic", t3);
        double t3T = ic3 != null && ic3.get("T") != null ? ic3.get("T").getRankIcMean() : Double.NaN;
        validation.put("codex_t_dominates", Double.isFinite(t3T) && t3T > 0.02);
    }

    static Double extractReasonNumber(Map<String, Object> reasons, String key) {
        if (reasons == null || key == null) {
            return null;
        }
        Object v = reasons.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static boolean isCodexFactorVersion(String factorVersion) {
        return factorVersion != null && factorVersion.contains(CodexShortCycleScoringEngine.FORMULA_VERSION);
    }

    static Map<String, Object> buildShortHorizonValidation(
            Map<Integer, FactorIcSummary> factorIcByHolding,
            FactorResearchSummary primarySummary
    ) {
        Map<String, Object> v = new LinkedHashMap<>();
        FactorIcSummary ic3 = factorIcByHolding.get(3);
        FactorIcSummary ic5 = factorIcByHolding.get(5);
        double ic3Mean = ic3 != null ? ic3.getRankIcMean() : Double.NaN;
        double ic5Mean = ic5 != null ? ic5.getRankIcMean() : Double.NaN;
        boolean predictsT3 = ic3 != null && ic3.getRankIcMean() > 0.02 && ic3.getCrossSectionDays() >= 5;
        boolean predictsT5 = ic5 != null && ic5.getRankIcMean() > 0.02 && ic5.getCrossSectionDays() >= 5;
        v.put("t3_rank_ic_mean", finiteOrNull(ic3Mean));
        v.put("t5_rank_ic_mean", finiteOrNull(ic5Mean));
        v.put("t3_effective", predictsT3);
        v.put("t5_effective", predictsT5);
        v.put("primary_holding_day", primarySummary != null ? primarySummary.holdingDays() : null);
        v.put("primary_long_short_spread", primarySummary != null ? primarySummary.longShortSpread() : null);
        if (predictsT3 || predictsT5) {
            v.put("verdict", "得分与 forward return 在 T+3/T+5 呈正相关，可用于相对排序；不等于单票必涨");
            v.put("confidence", (predictsT3 && predictsT5) ? "中" : "低");
        } else if (ic3 != null || ic5 != null) {
            v.put("verdict", "当前样本下 T+3/T+5 Rank IC 不足，公式短周期预测力弱或样本不足");
            v.put("confidence", "低");
        } else {
            v.put("verdict", "无足够截面数据，请先扩大日期范围或检查选股结果");
            v.put("confidence", "未知");
        }
        v.put("disclaimer", "IC>0.02 且截面日≥5 仅作启发式阈值，非投资建议");
        v.put("neutralization_pipeline", "winsorize→OLS(lnMV+industry)→robustZ(residual,MAD)");
        v.put("codex_g_weighting", "exp(lnMV) value-weighted er20m5/maAlign/vol20 for G");
        return v;
    }

    private static Double finiteOrNull(double v) {
        return Double.isFinite(v) ? v : null;
    }

    public List<String> getAvailableStrategies() {
        return resultRepo.findDistinctStrategyTypes();
    }

    private static String priceKey(String stockCode, String exchangeType, LocalDate date) {
        return stockCode + "|" + exchangeType + "|" + date;
    }

    private double boundedRate(Double value, double defaultValue) {
        if (value == null || !Double.isFinite(value)) return defaultValue;
        return Math.max(0, Math.min(value, 0.05));
    }

    private Map<String, Object> executionAssumptions(
            double commissionRate,
            double sellTaxRate,
            double slippageRate,
            List<SelectorResult> dailyResults
    ) {
        Map<String, Object> assumptions = new LinkedHashMap<>();
        assumptions.put("entry", "NEXT_TRADING_DAY_OPEN");
        assumptions.put("exit", "HOLDING_PERIOD_CLOSE");
        assumptions.put("t_plus_one_sell", true);
        assumptions.put(
                "holding_period_semantics",
                "h trading days after entry open; earliest exit close is entry day + h (A-share T+1)"
        );
        assumptions.put("commission_rate", commissionRate);
        assumptions.put("sell_tax_rate", sellTaxRate);
        assumptions.put("slippage_rate_each_side", slippageRate);
        assumptions.put("return_type", "NET_AFTER_MODELED_COSTS");
        assumptions.put("limit_up_entry_blocked", true);
        assumptions.put("limit_down_exit_blocked", true);
        assumptions.put("limit_pct_source", "BY_BOARD_AND_ST");
        assumptions.put("selector_tradability", resolveSelectorTradability(dailyResults));
        assumptions.put("short_horizon_research_focus", "T+3/T+5 Rank IC 与分组 spread 用于验证 3～5 日预测有效性");
        assumptions.put("codex_neutralization", "v2: winsorize → OLS(lnMV+industry dummies) → robust Z on residuals");
        assumptions.put("skip_reason_codes", List.of(
                SKIP_NO_QUOTE_ON_ENTRY,
                SKIP_INVALID_ENTRY_OPEN,
                SKIP_ZERO_VOLUME_ON_ENTRY,
                SKIP_LIMIT_UP_AT_OPEN,
                SKIP_NO_NEXT_TRADING_DAY,
                SKIP_BEYOND_CALENDAR,
                SKIP_NO_QUOTE_ON_EXIT,
                SKIP_INVALID_EXIT_CLOSE,
                SKIP_ZERO_VOLUME_ON_EXIT,
                SKIP_LIMIT_DOWN_AT_CLOSE
        ));
        return assumptions;
    }

    /**
     * 回测假设里的 selector_tradability 应取自被回测结果的 factor_snapshot，
     * 反映选股时实际的可交易性口径（minListingDays/bulkFetch/excludeST 等），
     * 而非硬编码默认值；无 snapshot 时回退到保守默认。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveSelectorTradability(List<SelectorResult> dailyResults) {
        if (dailyResults != null) {
            for (SelectorResult r : dailyResults) {
                if (r == null || r.getFactorSnapshot() == null) {
                    continue;
                }
                Object trad = r.getFactorSnapshot().get("tradability");
                if (trad instanceof Map<?, ?> tradMap) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : tradMap.entrySet()) {
                        if (e.getKey() != null) {
                            out.put(String.valueOf(e.getKey()), e.getValue());
                        }
                    }
                    out.put("source", "result_factor_snapshot");
                    return out;
                }
            }
        }
        Map<String, Object> fallback = new LinkedHashMap<>(ScoringUtils.tradabilitySnapshot(true, true, 60));
        fallback.put("source", "default_no_snapshot_in_result");
        return fallback;
    }

    private static boolean isZeroVolume(MarketData bar) {
        return ScoringUtils.isExplicitZeroVolume(bar);
    }

    private static void addSkippedStock(
            List<StockReturn> stockReturns,
            SelectorResultItem item,
            LocalDate entryDate,
            String skipReason
    ) {
        stockReturns.add(StockReturn.builder()
                .stockCode(item.getStockCode())
                .exchangeType(item.getExchangeType())
                .stockName(item.getStockName())
                .finalScore(item.getFinalScore())
                .rankNo(item.getRankNo())
                .entryDate(entryDate)
                .entryPrice(null)
                .forwardReturns(null)
                .skipReason(skipReason)
                .build());
    }

    private record PeriodReturnResult(Double ret, String skipReason) {}

    private record EntryContext(int entryIdx, double entryCost) {}

    private static int resolveFactorAnalysisHoldingDay(List<Integer> holdingDays, Integer requested) {
        if (requested != null && holdingDays.contains(requested)) {
            return requested;
        }
        if (holdingDays.contains(3)) {
            return 3;
        }
        if (holdingDays.contains(5)) {
            return 5;
        }
        return holdingDays.getFirst();
    }

    private static Map<String, Object> resolveFormulaAudit(ScoringBacktestRequest req, List<SelectorResult> dailyResults) {
        String strategyType = req.getStrategyType();
        String factorVersion = "";
        String horizonProfile = StrategyFormulaRegistry.HORIZON_MEDIUM_TREND;
        if (dailyResults != null && !dailyResults.isEmpty()) {
            SelectorResult sample = dailyResults.get(0);
            if (strategyType == null || strategyType.isBlank()) {
                strategyType = sample.getStrategyType();
            }
            if (sample.getFactorVersion() != null) {
                factorVersion = sample.getFactorVersion();
            }
            Map<String, Object> snap = sample.getFactorSnapshot();
            if (snap != null && snap.get("thresholds") instanceof Map<?, ?> th) {
                Object hp = th.get("horizon_profile");
                if (hp != null) {
                    horizonProfile = String.valueOf(hp);
                }
            }
        }
        if (strategyType == null || strategyType.isBlank()) {
            strategyType = "CLASSIC_SELECTOR";
        }
        return StrategyFormulaRegistry.toMap(
                StrategyFormulaRegistry.audit(strategyType, factorVersion, horizonProfile));
    }

    private static EntryContext resolveEntryContext(
            SelectorResultItem item,
            int baseIdx,
            List<LocalDate> tradingDays,
            Map<String, MarketData> marketDataMap,
            double commissionRate,
            double slippageRate
    ) {
        int entryIdx = baseIdx + 1;
        if (entryIdx >= tradingDays.size()) {
            return null;
        }
        LocalDate entryDate = tradingDays.get(entryIdx);
        MarketData entryBar = marketDataMap.get(priceKey(item.getStockCode(), item.getExchangeType(), entryDate));
        if (entryBar == null) {
            return null;
        }
        BigDecimal rawEntryPrice = entryBar.getOpenPrice();
        if (rawEntryPrice == null || rawEntryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (isZeroVolume(entryBar)) {
            return null;
        }
        LocalDate prevTradeDate = tradingDays.get(entryIdx - 1);
        MarketData prevBar = marketDataMap.get(
                priceKey(item.getStockCode(), item.getExchangeType(), prevTradeDate));
        double preClose = StrategyBacktestEngine.resolvePreClose(entryBar, prevBar);
        double limitPct = TradingLimitRules.resolveLimitPct(
                item.getStockCode(), item.getExchangeType(), item.getStockName());
        if (StrategyBacktestEngine.isLimitUpAtOpen(entryBar, preClose, limitPct)) {
            return null;
        }
        double entryPrice = rawEntryPrice.doubleValue() * (1 + slippageRate);
        double entryCost = entryPrice * (1 + commissionRate);
        return new EntryContext(entryIdx, entryCost);
    }

    private static PeriodReturnResult computePeriodReturn(
            SelectorResultItem item,
            int entryIdx,
            int holdingDay,
            List<LocalDate> tradingDays,
            Map<String, MarketData> marketDataMap,
            double entryCost,
            double commissionRate,
            double sellTaxRate,
            double slippageRate
    ) {
        int fwdIdx = entryIdx + holdingDay;
        if (fwdIdx >= tradingDays.size()) {
            return new PeriodReturnResult(null, SKIP_BEYOND_CALENDAR);
        }
        LocalDate fwdDate = tradingDays.get(fwdIdx);
        MarketData exitBar = marketDataMap.get(
                priceKey(item.getStockCode(), item.getExchangeType(), fwdDate));
        if (exitBar == null) {
            return new PeriodReturnResult(null, SKIP_NO_QUOTE_ON_EXIT);
        }
        BigDecimal rawExitPrice = exitBar.getClosePrice();
        if (rawExitPrice == null || rawExitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return new PeriodReturnResult(null, SKIP_INVALID_EXIT_CLOSE);
        }
        if (isZeroVolume(exitBar)) {
            return new PeriodReturnResult(null, SKIP_ZERO_VOLUME_ON_EXIT);
        }
        double limitPct = TradingLimitRules.resolveLimitPct(
                item.getStockCode(), item.getExchangeType(), item.getStockName());
        if (fwdIdx > 0) {
            LocalDate prevFwdDate = tradingDays.get(fwdIdx - 1);
            MarketData prevExitBar = marketDataMap.get(
                    priceKey(item.getStockCode(), item.getExchangeType(), prevFwdDate));
            double exitPreClose = StrategyBacktestEngine.resolvePreClose(exitBar, prevExitBar);
            if (StrategyBacktestEngine.isLimitDownAtClose(exitBar, exitPreClose, limitPct)) {
                return new PeriodReturnResult(null, SKIP_LIMIT_DOWN_AT_CLOSE);
            }
        }
        double exitPrice = rawExitPrice.doubleValue() * (1 - slippageRate);
        double exitProceeds = exitPrice * (1 - commissionRate - sellTaxRate);
        return new PeriodReturnResult(exitProceeds / entryCost - 1, null);
    }

    private static FactorAnalysis toFactorAnalysisDto(FactorResearchSummary summary) {
        if (summary == null || summary.crossSectionDays() == 0) {
            return null;
        }
        return FactorAnalysis.builder()
                .holdingDays(summary.holdingDays())
                .groupCount(summary.groupCount())
                .crossSectionDays(summary.crossSectionDays())
                .totalObservations(summary.totalObservations())
                .rankIcMean(summary.rankIcMean())
                .rankIcStd(summary.rankIcStd())
                .rankIcIr(summary.rankIcIr())
                .rankIcPositiveRate(summary.rankIcPositiveRate())
                .dailyRankIc(summary.dailyRankIc().stream()
                        .map(d -> DailyRankIc.builder()
                                .tradeDate(d.tradeDate())
                                .rankIc(d.rankIc())
                                .sampleSize(d.sampleSize())
                                .build())
                        .toList())
                .groupedReturns(summary.groupedReturns().stream()
                        .map(g -> GroupReturnStat.builder()
                                .groupNo(g.groupNo())
                                .label(g.label())
                                .avgReturn(g.avgReturn())
                                .observations(g.observations())
                                .build())
                        .toList())
                .longShortSpread(summary.longShortSpread())
                .universeNote(summary.universeNote())
                .build();
    }

    private static FactorIcSummary toFactorIcSummaryDto(FactorResearchSummary summary) {
        if (summary == null || summary.crossSectionDays() == 0) {
            return null;
        }
        return FactorIcSummary.builder()
                .holdingDays(summary.holdingDays())
                .crossSectionDays(summary.crossSectionDays())
                .totalObservations(summary.totalObservations())
                .rankIcMean(summary.rankIcMean())
                .rankIcStd(summary.rankIcStd())
                .rankIcIr(summary.rankIcIr())
                .rankIcPositiveRate(summary.rankIcPositiveRate())
                .longShortSpread(summary.longShortSpread())
                .build();
    }
}
