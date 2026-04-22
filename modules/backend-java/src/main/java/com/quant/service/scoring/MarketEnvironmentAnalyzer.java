package com.quant.service.scoring;

import com.quant.entity.MarketData;
import com.quant.repository.MarketDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 市场环境分析服务
 * 基于真实市场数据计算市场环境参数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketEnvironmentAnalyzer {

    private final MarketDataRepository marketDataRepository;

    /**
     * 市场环境指标
     */
    public record MarketIndicators(
            double indexChange,          // 指数涨跌幅（%）
            double volatilityIndex,      // 波动率指数（模拟VIX）
            double marginChange,         // 融资余额变化率（%）
            long dailyTurnover,          // 日成交额
            String marketEnvironment,    // 市场环境类型
            double avgMarketReturn,      // 市场平均收益率
            double marketBreadth         // 市场广度（上涨股票占比）
    ) {}

    /**
     * 分析市场环境
     *
     * @param tradeDate 交易日期
     * @param lookbackDays 回溯天数
     * @return 市场环境指标
     */
    public MarketIndicators analyze(LocalDate tradeDate, int lookbackDays) {
        try {
            LocalDate rangeStart = tradeDate.minusDays(lookbackDays);

            // 获取全市场数据
            List<MarketData> allBars = marketDataRepository.findByTradeDateBetweenOrderByStockCodeAscExchangeTypeAscTradeDateAsc(
                    rangeStart, tradeDate);

            if (allBars.isEmpty()) {
                return getDefaultIndicators();
            }

            // 计算市场指标
            double indexChange = calculateIndexChange(allBars, tradeDate);
            double volatilityIndex = calculateVolatilityIndex(allBars, tradeDate);
            long dailyTurnover = calculateDailyTurnover(allBars, tradeDate);
            double marginChange = estimateMarginChange(indexChange, volatilityIndex);
            double avgMarketReturn = calculateAvgMarketReturn(allBars, tradeDate);
            double marketBreadth = calculateMarketBreadth(allBars, tradeDate);

            // 判断市场环境
            String marketEnvironment = DynamicWeightingService.classifyMarketEnvironment(
                    indexChange, volatilityIndex, marginChange, dailyTurnover);

            return new MarketIndicators(
                    indexChange, volatilityIndex, marginChange, dailyTurnover,
                    marketEnvironment, avgMarketReturn, marketBreadth
            );
        } catch (Exception e) {
            log.warn("市场环境分析失败: {}", e.getMessage());
            return getDefaultIndicators();
        }
    }

    /**
     * 计算指数涨跌幅（使用全市场平均）
     */
    private double calculateIndexChange(List<MarketData> allBars, LocalDate tradeDate) {
        double todaySum = 0;
        double prevSum = 0;
        int count = 0;

        for (int i = 0; i < allBars.size(); i++) {
            MarketData bar = allBars.get(i);
            if (bar.getTradeDate().equals(tradeDate) && bar.getClosePrice() != null) {
                todaySum += bar.getClosePrice().doubleValue();
                // 查找前一天的数据
                if (i > 0) {
                    MarketData prevBar = allBars.get(i - 1);
                    if (prevBar.getClosePrice() != null &&
                            prevBar.getStockCode().equals(bar.getStockCode())) {
                        prevSum += prevBar.getClosePrice().doubleValue();
                        count++;
                    }
                }
            }
        }

        if (count == 0 || prevSum == 0) return 0;
        return (todaySum - prevSum) / prevSum * 100;
    }

    /**
     * 计算波动率指数（模拟VIX）
     */
    private double calculateVolatilityIndex(List<MarketData> allBars, LocalDate tradeDate) {
        // 使用20日收益率标准差作为波动率代理
        double sum = 0;
        int count = 0;

        // 按股票分组计算波动率
        java.util.Map<String, List<MarketData>> grouped = new java.util.HashMap<>();
        for (MarketData bar : allBars) {
            String key = bar.getStockCode() + "|" + bar.getExchangeType();
            grouped.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(bar);
        }

        for (List<MarketData> bars : grouped.values()) {
            if (bars.size() < 20) continue;

            // 计算20日收益率标准差
            java.util.List<Double> returns = new java.util.ArrayList<>();
            for (int i = 1; i < Math.min(21, bars.size()); i++) {
                if (bars.get(i).getClosePrice() != null && bars.get(i-1).getClosePrice() != null) {
                    double ret = (bars.get(i).getClosePrice().doubleValue() -
                                  bars.get(i-1).getClosePrice().doubleValue()) /
                                  bars.get(i-1).getClosePrice().doubleValue() * 100;
                    returns.add(ret);
                }
            }

            if (returns.size() >= 10) {
                double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double variance = returns.stream()
                        .mapToDouble(r -> (r - mean) * (r - mean))
                        .average().orElse(0);
                sum += Math.sqrt(variance);
                count++;
            }
        }

        // 将日波动率转换为年化波动率，并映射到VIX范围（10-50）
        double avgDailyVol = count > 0 ? sum / count : 1.5;
        double annualizedVol = avgDailyVol * Math.sqrt(252);
        return Math.max(10, Math.min(50, annualizedVol));
    }

    /**
     * 计算日成交额
     */
    private long calculateDailyTurnover(List<MarketData> allBars, LocalDate tradeDate) {
        long total = 0;
        for (MarketData bar : allBars) {
            if (bar.getTradeDate().equals(tradeDate) && bar.getAmount() != null) {
                total += bar.getAmount().longValue();
            }
        }
        return total;
    }

    /**
     * 估算融资余额变化（基于指数变化和波动率）
     */
    private double estimateMarginChange(double indexChange, double volatilityIndex) {
        // 融资余额通常与市场走势正相关，与波动率负相关
        return indexChange * 0.5 - volatilityIndex * 0.1;
    }

    /**
     * 计算市场平均收益率
     */
    private double calculateAvgMarketReturn(List<MarketData> allBars, LocalDate tradeDate) {
        double sum = 0;
        int count = 0;

        java.util.Map<String, MarketData> todayBars = new java.util.HashMap<>();
        java.util.Map<String, MarketData> prevBars = new java.util.HashMap<>();

        LocalDate prevDate = null;
        for (MarketData bar : allBars) {
            if (bar.getTradeDate().equals(tradeDate)) {
                todayBars.put(bar.getStockCode() + "|" + bar.getExchangeType(), bar);
            } else if (bar.getTradeDate().isBefore(tradeDate)) {
                String key = bar.getStockCode() + "|" + bar.getExchangeType();
                if (!prevBars.containsKey(key)) {
                    prevBars.put(key, bar);
                    if (prevDate == null) prevDate = bar.getTradeDate();
                }
            }
        }

        for (String key : todayBars.keySet()) {
            MarketData today = todayBars.get(key);
            MarketData prev = prevBars.get(key);
            if (today != null && prev != null &&
                today.getClosePrice() != null && prev.getClosePrice() != null) {
                double ret = (today.getClosePrice().doubleValue() - prev.getClosePrice().doubleValue()) /
                             prev.getClosePrice().doubleValue() * 100;
                sum += ret;
                count++;
            }
        }

        return count > 0 ? sum / count : 0;
    }

    /**
     * 计算市场广度（上涨股票占比）
     */
    private double calculateMarketBreadth(List<MarketData> allBars, LocalDate tradeDate) {
        int up = 0, down = 0;

        java.util.Map<String, MarketData> todayBars = new java.util.HashMap<>();
        java.util.Map<String, MarketData> prevBars = new java.util.HashMap<>();

        for (MarketData bar : allBars) {
            if (bar.getTradeDate().equals(tradeDate)) {
                todayBars.put(bar.getStockCode() + "|" + bar.getExchangeType(), bar);
            } else if (bar.getTradeDate().isBefore(tradeDate)) {
                String key = bar.getStockCode() + "|" + bar.getExchangeType();
                if (!prevBars.containsKey(key)) {
                    prevBars.put(key, bar);
                }
            }
        }

        for (String key : todayBars.keySet()) {
            MarketData today = todayBars.get(key);
            MarketData prev = prevBars.get(key);
            if (today != null && prev != null &&
                today.getClosePrice() != null && prev.getClosePrice() != null) {
                if (today.getClosePrice().doubleValue() > prev.getClosePrice().doubleValue()) {
                    up++;
                } else if (today.getClosePrice().doubleValue() < prev.getClosePrice().doubleValue()) {
                    down++;
                }
            }
        }

        int total = up + down;
        return total > 0 ? (double) up / total : 0.5;
    }

    /**
     * 获取默认市场指标
     */
    private MarketIndicators getDefaultIndicators() {
        return new MarketIndicators(
                0, 20, 0, 1_000_000_000_000L,
                "震荡市", 0, 0.5
        );
    }

    /**
     * 根据股票特性计算股票类型
     */
    public String classifyStock(List<MarketData> bars, Double pe, String industry) {
        if (bars == null || bars.size() < 20) {
            return "普通股";
        }

        int n = bars.size();
        int L = n - 1;

        // 计算价格波动率
        double[] close = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = bars.get(i).getClosePrice() != null ?
                       bars.get(i).getClosePrice().doubleValue() : 0;
        }

        double avgClose = 0;
        for (int i = Math.max(0, L - 60); i <= L; i++) {
            avgClose += close[i];
        }
        avgClose /= Math.min(61, L + 1);

        double variance = 0;
        for (int i = Math.max(0, L - 60); i <= L; i++) {
            variance += (close[i] - avgClose) * (close[i] - avgClose);
        }
        double volatility = Math.sqrt(variance / Math.min(61, L + 1)) / avgClose * 100;

        // 计算估值分位
        double pePercentile = pe != null ? (pe < 15 ? 20 : (pe < 30 ? 50 : 80)) : 50;

        // 简化的PB估算（基于行业）
        double pb = estimatePb(industry);

        // 简化的PEG估算
        double peg = pe != null ? pe / 15.0 : 1.5;

        // 简化的营收增长率估算（基于价格趋势）
        double revenueGrowth = estimateRevenueGrowth(close, L);

        // 其他参数使用默认值
        double dividendYield = estimateDividendYield(industry);
        double rndRatio = estimateRndRatio(industry);
        double grossMarginVolatility = volatility;
        double pmiCorrelation = estimatePmiCorrelation(industry);
        double freeCashFlowStability = 0.7;

        return DynamicWeightingService.classifyStockCharacteristics(
                pePercentile, pb, dividendYield, peg, revenueGrowth, rndRatio,
                grossMarginVolatility, pmiCorrelation, volatility, freeCashFlowStability
        );
    }

    private double estimatePb(String industry) {
        if (industry == null) return 2.0;
        return switch (industry) {
            case "银行" -> 0.6;
            case "房地产" -> 1.0;
            case "科技", "新能源" -> 3.0;
            case "医药" -> 2.5;
            default -> 2.0;
        };
    }

    private double estimateRevenueGrowth(double[] close, int L) {
        if (L < 60) return 10;
        double ret60 = (close[L] - close[L - 60]) / close[L - 60] * 100;
        return ret60 * 0.5; // 假设收入增长约为价格涨幅的一半
    }

    private double estimateDividendYield(String industry) {
        if (industry == null) return 2.0;
        return switch (industry) {
            case "银行" -> 5.0;
            case "电力" -> 4.0;
            case "房地产" -> 3.5;
            default -> 2.0;
        };
    }

    private double estimateRndRatio(String industry) {
        if (industry == null) return 2.0;
        return switch (industry) {
            case "科技" -> 8.0;
            case "医药" -> 12.0;
            case "新能源" -> 6.0;
            default -> 2.0;
        };
    }

    private double estimatePmiCorrelation(String industry) {
        if (industry == null) return 0.5;
        return switch (industry) {
            case "钢铁", "煤炭", "有色" -> 0.85;
            case "化工" -> 0.75;
            case "汽车" -> 0.7;
            default -> 0.5;
        };
    }
}
