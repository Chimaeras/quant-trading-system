package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 打分回测运行记录（结果可追溯）。
 *
 * <p>持久化每次打分回测的参数、假设、数据截止日与完整结果 JSON，支持：
 * 按 {@code resultId} 回溯历史某次回测、历史运行列表、结果分享。
 *
 * <p>响应为内存计算，刷新即失；本表把「参数 + 假设 + 数据口径 + 结果」锁定为一快照，
 * 供复核与复现（复现时 latest_data_date 之后若行情更新，结果可能漂移，须以记录口径为准）。
 */
@Data
@Entity
@Table(name = "backtest_run", indexes = {
        @Index(name = "idx_bt_run_strategy_date", columnList = "strategyType,startDate"),
        @Index(name = "idx_bt_run_result_id", columnList = "resultId")
})
public class BacktestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 逻辑标识：BT-yyyyMMddHHmmss-随机后缀，供前端跳转/分享。 */
    @Column(nullable = false, unique = true, length = 40)
    private String resultId;

    @Column(nullable = false, length = 40)
    private String strategyType;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private Integer topN;

    /** 持仓天数 JSON，如 [1,3,5,10,20]。 */
    @Column(columnDefinition = "TEXT")
    private String holdingDays;

    private BigDecimal commissionRate;
    private BigDecimal sellTaxRate;
    private BigDecimal slippageRate;

    /** 本次计算使用的数据截止日（= market_data 最新交易日）。 */
    private LocalDate latestDataDate;

    /** 成交/费用假设 JSON（executionAssumptions）。 */
    @Column(columnDefinition = "TEXT")
    private String assumptions;

    /** 结果摘要 JSON（totalTradingDays + holdingPeriodStats），用于历史列表展示。 */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** 完整响应 JSON（ScoringBacktestResponse），用于按 ID 精确回溯。 */
    @Column(columnDefinition = "LONGTEXT")
    private String response;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
