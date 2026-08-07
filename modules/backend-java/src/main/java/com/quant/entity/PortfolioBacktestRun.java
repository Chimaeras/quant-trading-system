package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 事件驱动组合回测运行摘要（MP-015 MVP）：NAV / orders 以 JSON 持久化。
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

    @Column(columnDefinition = "LONGTEXT")
    private String navJson;

    @Column(columnDefinition = "LONGTEXT")
    private String ordersJson;

    @Column(nullable = false, length = 20)
    private String status = "COMPLETED";

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
