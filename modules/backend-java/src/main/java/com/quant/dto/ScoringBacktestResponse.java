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
        /**
         * ICIR；IC 序列无离散度（std = 0，含 T &lt; 2）时**无定义**，为 {@code null}。
         * 0 的语义是「IR 恰为 0」，两者不可混同（Q-R006-05）。
         */
        private Double rankIcIr;
        private double rankIcPositiveRate;
        /**
         * 两腿价差 = 最高分组均值 − 最低分组均值。本面（打分回测）的因子观测用
         * {@code periodResult.ret()}，**已扣费**，故本字段是**净价差**；同名字段在
         * {@code FactorResearchService} 载荷的毛收益块里是**毛价差**（同名不同量）。
         * 判定口径一律读 {@link #longShortSpreadCostBasis}（Q-R003-01 / Q-R004-01）。
         */
        private double longShortSpread;
        /**
         * 两腿价差的成本基准（纯增量）：本 DTO 面恒为 {@code NET_AFTER_MODELED_COSTS}；
         * 载荷毛收益块为 {@code GROSS_BEFORE_COSTS}。消费方**不必靠字段名猜口径**。
         */
        private String longShortSpreadCostBasis;
        /**
         * 净价差 → 毛价差的精确还原：{@code longShortSpread / (1 − costPerLegRoundTrip)}。
         * 用于与「毛收益面」的同名键对齐（冻结口径下费率为常数且逐笔乘性作用，故可精确还原）。
         * 美元中性值在本面**不适用**（价差已扣费，再减两腿成本即重复扣费），故不提供该字段。
         */
        private Double longShortSpreadGrossEquivalent;
        /** 单腿往返成本（乘性口径，佣金+印花税+双边滑点复合后的比例），纯增量。 */
        private Double costPerLegRoundTrip;
        /** 样本标准差口径（÷(T−1)）的 IC 标准差；主口径 {@link #rankIcStd} 为总体标准差 ÷T。 */
        private Double rankIcStdSample;
        /** 由 {@link #rankIcStdSample} 计算的 ICIR；样本离散度不存在时为 {@code null}。 */
        private Double rankIcIrSample;
        /**
         * Pearson（线性）IC 均值；样本不足或任一侧无区分度时**无定义**，为 {@code null}
         * （原实现在此处写 0.0，把「不可定义」伪装成「线性 IC 恰为 0」，Q-R009-02）。
         */
        private Double pearsonIcMean;
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
        /**
         * ICIR；IC 序列无离散度（std = 0，含 T &lt; 2）时**无定义**，为 {@code null}（Q-R006-05）。
         */
        private Double rankIcIr;
        private double rankIcPositiveRate;
        private List<DailyRankIc> dailyRankIc;
        /**
         * 分组收益；{@code observations} 为**参与聚合的截面日数**（不是成分股数，Q-R014-03），
         * 空组以 {@code observations = 0} 标记且其 {@code avgReturn = 0.0} 是占位、不参与极差。
         */
        private List<GroupReturnStat> groupedReturns;
        /**
         * 两腿价差 = 最高分组均值 − 最低分组均值，本面为**净价差**（观测收益已扣费）；
         * 与 {@code HoldingPeriodStat.ret()} 同属净收益家族，但**不是**美元中性多空。见
         * {@link #longShortSpreadCostBasis}。
         */
        private double longShortSpread;
        /** 两腿价差的成本基准（纯增量，Q-R003-01/Q-R004-01）：本面恒为 NET_AFTER_MODELED_COSTS。 */
        private String longShortSpreadCostBasis;
        /** 净价差 → 毛价差的精确还原（{@code longShortSpread / (1 − costPerLegRoundTrip)}）。 */
        private Double longShortSpreadGrossEquivalent;
        /** 单腿往返成本（乘性口径），纯增量。 */
        private Double costPerLegRoundTrip;
        /** 样本标准差口径（÷(T−1)）的 IC 标准差；主口径 {@link #rankIcStd} 为总体标准差 ÷T。 */
        private Double rankIcStdSample;
        /** 由 {@link #rankIcStdSample} 计算的 ICIR；无定义时为 {@code null}。 */
        private Double rankIcIrSample;
        /**
         * Pearson（线性）IC 均值；不可定义时为 {@code null}（Q-R009-02）。注意 Spearman 与 Pearson 的
         * 有限性**不等价**，且本字段在截面样本不足时恒为 null。
         */
        private Double pearsonIcMean;
        /**
         * 分组单调性标签（纯增量字段）：{@code MONOTONIC_INCREASING} / {@code MONOTONIC_DECREASING} /
         * {@code NON_MONOTONIC} / {@code NO_DISPERSION}（各组收益全相等 = 零区分度，不是任何方向）/
         * {@code N/A}（可用组不足 2 或组均值非有限）。此前标签只存在于载荷 map 中，DTO 消费方拿不到（Q-R013-06）。
         */
        private String monotonicity;
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
        /**
         * 盈亏比 = avgWin / |avgLoss|。
         * 无亏损样本（avgLoss == 0）时该比值在数学上无定义，返回 null 而非 0——
         * 返回 0 会被误读为"盈亏比为零/毫无优势"，与"样本内没有亏损"完全相反。
         * 前端遇到 null 渲染为 "--"。
         */
        private Double profitLossRatio;
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
