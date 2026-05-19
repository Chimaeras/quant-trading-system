package com.quant.service;

import com.quant.dto.ScoringBacktestRequest;
import com.quant.dto.ScoringBacktestResponse;
import com.quant.dto.ScoringBacktestResponse.DailyDetail;
import com.quant.dto.ScoringBacktestResponse.PeriodStat;
import com.quant.dto.ScoringBacktestResponse.StockReturn;
import com.quant.entity.MarketData;
import com.quant.entity.SelectorResult;
import com.quant.entity.SelectorResultItem;
import com.quant.repository.MarketDataRepository;
import com.quant.repository.SelectorResultItemRepository;
import com.quant.repository.SelectorResultRepository;
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

    private final SelectorResultRepository resultRepo;
    private final SelectorResultItemRepository itemRepo;
    private final MarketDataRepository marketDataRepo;

    public ScoringBacktestResponse run(ScoringBacktestRequest req) {
        log.info("开始打分回测: strategy={}, range=[{} -> {}], topN={}, holdingDays={}",
                req.getStrategyType(), req.getStartDate(), req.getEndDate(),
                req.getTopN(), req.getHoldingDays());

        List<Integer> holdingDays = req.getHoldingDays() != null ? req.getHoldingDays() : List.of(1, 3, 5, 10, 20);
        int maxHolding = holdingDays.stream().max(Integer::compareTo).orElse(20);
        int topN = req.getTopN() != null ? req.getTopN() : 10;

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

        if (dailyResults.isEmpty()) {
            log.warn("未找到符合条件的选股结果");
            return ScoringBacktestResponse.builder()
                    .strategyType(req.getStrategyType())
                    .startDate(req.getStartDate())
                    .endDate(req.getEndDate())
                    .topN(topN)
                    .totalTradingDays(0)
                    .holdingPeriodStats(Map.of())
                    .dailyDetails(List.of())
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
        Map<String, BigDecimal> priceMap = new HashMap<>();
        for (MarketData md : allMarketData) {
            String key = priceKey(md.getStockCode(), md.getExchangeType(), md.getTradeDate());
            priceMap.put(key, md.getClosePrice());
        }
        log.info("预加载 {} 条行情数据, {} 个交易日", allMarketData.size(), tradingDays.size());

        // 5. 逐日计算 forward returns
        List<DailyDetail> dailyDetails = new ArrayList<>();
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
                String baseKey = priceKey(item.getStockCode(), item.getExchangeType(), tradeDate);
                BigDecimal basePrice = priceMap.get(baseKey);
                if (basePrice == null || basePrice.compareTo(BigDecimal.ZERO) == 0) continue;

                Map<Integer, Double> forwardReturns = new LinkedHashMap<>();
                for (int h : holdingDays) {
                    int fwdIdx = baseIdx + h;
                    if (fwdIdx >= tradingDays.size()) {
                        forwardReturns.put(h, null);
                        continue;
                    }
                    LocalDate fwdDate = tradingDays.get(fwdIdx);
                    String fwdKey = priceKey(item.getStockCode(), item.getExchangeType(), fwdDate);
                    BigDecimal fwdPrice = priceMap.get(fwdKey);
                    if (fwdPrice == null || fwdPrice.compareTo(BigDecimal.ZERO) == 0) {
                        forwardReturns.put(h, null);
                        continue;
                    }
                    double ret = fwdPrice.subtract(basePrice)
                            .divide(basePrice, 6, java.math.RoundingMode.HALF_UP)
                            .doubleValue();
                    forwardReturns.put(h, ret);
                    periodReturns.get(h).add(ret);
                    dailyReturns.get(h).add(ret);
                }

                stockReturns.add(StockReturn.builder()
                        .stockCode(item.getStockCode())
                        .exchangeType(item.getExchangeType())
                        .stockName(item.getStockName())
                        .finalScore(item.getFinalScore())
                        .rankNo(item.getRankNo())
                        .forwardReturns(forwardReturns)
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

            holdingPeriodStats.put(h, PeriodStat.builder()
                    .winRate((double) wins / returns.size())
                    .avgReturn(avg)
                    .medianReturn(median)
                    .maxReturn(sorted.get(sorted.size() - 1))
                    .minReturn(sorted.get(0))
                    .totalObservations(returns.size())
                    .build());
        }

        log.info("打分回测完成: {} 个交易日, {} 个周期统计完毕", dailyDetails.size(), holdingPeriodStats.size());

        return ScoringBacktestResponse.builder()
                .strategyType(req.getStrategyType())
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .topN(topN)
                .totalTradingDays(dailyDetails.size())
                .holdingPeriodStats(holdingPeriodStats)
                .dailyDetails(dailyDetails)
                .build();
    }

    public List<String> getAvailableStrategies() {
        return resultRepo.findDistinctStrategyTypes();
    }

    private String priceKey(String stockCode, String exchangeType, LocalDate date) {
        return stockCode + "|" + exchangeType + "|" + date;
    }
}
