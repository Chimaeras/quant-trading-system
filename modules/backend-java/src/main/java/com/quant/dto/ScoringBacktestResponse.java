package com.quant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoringBacktestResponse {
    private String strategyType;
    private LocalDate startDate;
    private LocalDate endDate;
    private int topN;
    private int totalTradingDays;
    /** 本次收益计算使用的成交与费用假设 */
    private Map<String, Object> executionAssumptions;

    /** key = 持仓天数，value = 该周期下的汇总统计 */
    private Map<Integer, PeriodStat> holdingPeriodStats;

    /** 每日明细（按 tradeDate 升序） */
    private List<DailyDetail> dailyDetails;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeriodStat {
        /** 胜率（收益率 > 0 的占比） */
        private double winRate;
        /** 平均收益率 */
        private double avgReturn;
        /** 中位数收益率 */
        private double medianReturn;
        /** 最大收益率 */
        private double maxReturn;
        /** 最小收益率 */
        private double minReturn;
        /** 有效观测数量 */
        private int totalObservations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyDetail {
        /** 信号生成日 */
        private LocalDate tradeDate;
        private List<StockReturn> topStocks;
        /** key = 持仓天数，value = 当日胜率 */
        private Map<Integer, Double> dailyWinRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockReturn {
        private String stockCode;
        private String exchangeType;
        private String stockName;
        private double finalScore;
        private int rankNo;
        /** 实际模拟买入日：信号日后的下一交易日 */
        private LocalDate entryDate;
        /** 含买入侧滑点、不含佣金的模拟成交价 */
        private Double entryPrice;
        /** key = 持仓天数，value = 该周期的 forward return；若缺失则为 null */
        private Map<Integer, Double> forwardReturns;
    }
}
