package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 事件驱动组合回测运行（MP-015 / V2-MP-020）：summary 单独存，订单/NAV/持仓落子表。
 *
 * <p>V2-MP-020 变更：新增 prediction_run_id（已发布预测闭环信号源）、track、top_n、progress、
 * error_message、updated_at；移除单行 LONGTEXT nav_json/orders_json（详见 V20 迁移）。
 */
@Data
@Entity
@Table(name = "portfolio_backtest_run", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"run_id"})
})
public class PortfolioBacktestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String runId;

    @Column(length = 32)
    private String strategyType;

    /** 信号源：已发布 prediction run（V2-MP-020 闭环）；空表示 selector_result 区间模式。 */
    @Column(length = 40)
    private String predictionRunId;

    @Column(length = 32)
    private String track;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private Integer horizon = 2;

    @Column(nullable = false)
    private Double initialCash;

    @Column(nullable = false)
    private Integer maxPositions;

    /** 每日信号截断数（prediction run 模式由 run 派生，区间模式用户可配）。 */
    private Integer topN;

    @Column(nullable = false, length = 20)
    private String weighting = "EQUAL";

    @Column(nullable = false)
    private Double commissionRate;

    @Column(nullable = false)
    private Double sellTaxRate;

    @Column(nullable = false)
    private Double slippageRate;

    @Column(nullable = false, length = 40)
    private String costVersion;

    @Column(nullable = false, length = 40)
    private String executionVersion;

    private Double finalNav;
    private Double totalReturn;
    private Double maxDrawdown;
    private Integer totalTrades;
    private Integer rejectCount;

    @Column(columnDefinition = "LONGTEXT")
    private String summaryJson;

    @Column(nullable = false, length = 20)
    private String status = "COMPLETED";

    /** 0-100，异步执行进度（RUNNING 期间按已落 NAV 点数/预期交易日估算）。 */
    private Integer progress = 0;

    @Column(length = 500)
    private String errorMessage;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
