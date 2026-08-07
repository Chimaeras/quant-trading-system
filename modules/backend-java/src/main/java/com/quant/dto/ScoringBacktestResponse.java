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
    /** 回测运行标识（已持久化，可按此回溯历史结果）；未持久化时为空。 */
    private String resultId;
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

    /** 截面 Rank IC 与分组收益研究摘要（主研究持有期，含每日 IC 与分组明细） */
    private FactorAnalysis factorAnalysis;

    /** 各持有期 Rank IC 摘要，用于 IC 衰减/多周期对比 */
    private Map<Integer, FactorIcSummary> factorIcByHolding;

    /** 策略公式审计（短周期预测适配性说明） */
    private Map<String, Object> formulaAudit;

    /** 基于 T+3/T+5 Rank IC 的公式有效性验证摘要 */
    private Map<String, Object> shortHorizonValidation;

    /** Codex T/F/R 子因子各持有期 Rank IC（key=持有期, inner key=T/F/R） */
    private Map<Integer, Map<String, FactorIcSummary>> codexFactorIcByHolding;

    /**
     * MP-010 信号→成交漏斗：signal/buyable/filled/frozen + reject_reasons。
     * metric_scope 标明汇总指标分母口径。
     */
    private Map<String, Object> executionFunnel;

    /** 价源：market_price_raw / qfq_legacy / mixed */
    private String priceSource;

    /** 指标口径：FILLED_SAMPLE（胜率等仅成功成交）等 */
    private String metricScope;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FactorIcSummary {
        private int holdingDays;
        private int crossSectionDays;
        private int totalObservations;
        private double rankIcMean;
        private double rankIcStd;
        private double rankIcIr;
        private double rankIcPositiveRate;
        private double longShortSpread;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FactorAnalysis {
        private int holdingDays;
        private int groupCount;
        private int crossSectionDays;
        private int totalObservations;
        private double rankIcMean;
        private double rankIcStd;
        private double rankIcIr;
        private double rankIcPositiveRate;
        private List<DailyRankIc> dailyRankIc;
        private List<GroupReturnStat> groupedReturns;
        private double longShortSpread;
        private String universeNote;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyRankIc {
        private String tradeDate;
        private double rankIc;
        private int sampleSize;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupReturnStat {
        private int groupNo;
        private String label;
        private double avgReturn;
        private int observations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeriodStat {
        /** 胜率（收益率 > 0 的占比）；与 filledSampleWinRate 同义，仅统计成交成功样本 */
        private double winRate;
        /** 成交成功样本胜率（正式名；winRate 为其别名） */
        private double filledSampleWinRate;
        /** 平均收益率 */
        private double avgReturn;
        /** 中位数收益率 */
        private double medianReturn;
        /** 最大收益率 */
        private double maxReturn;
        /** 最小收益率 */
        private double minReturn;
        /** 有效观测数量（成交成功样本数） */
        private int totalObservations;
        /** 信号样本数（含跳过，分母口径） */
        private int signalCount;
        /** 跳过/拒单样本数 */
        private int skippedCount;
        /** 平均盈利收益率（仅收益>0 样本） */
        private double avgWin;
        /** 平均亏损收益率（仅收益<0 样本，负数） */
        private double avgLoss;
        /** 胜率盈亏比 = avgWin / |avgLoss|；无亏损样本时为 0 */
        private double profitLossRatio;
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
        /** 入场未成交原因；成功入场时为 null */
        private String skipReason;
        /** key = 持仓天数，value = 该周期出场未成交原因 */
        private Map<Integer, String> periodSkipReasons;
    }
}
