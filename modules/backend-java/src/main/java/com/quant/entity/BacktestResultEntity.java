package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Entity
@Table(name = "backtest_result")
public class BacktestResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String resultId;

    @Column(nullable = false)
    private Long strategyId;

    @Column(length = 100)
    private String strategyName;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal initialCapital;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> params;

    @Column(precision = 20, scale = 4)
    private BigDecimal finalCapital;

    @Column(precision = 8, scale = 4)
    private BigDecimal totalReturn;

    @Column(precision = 8, scale = 4)
    private BigDecimal annualReturn;

    @Column(precision = 8, scale = 4)
    private BigDecimal sharpeRatio;

    @Column(precision = 8, scale = 4)
    private BigDecimal maxDrawdown;

    @Column
    private Integer totalTrades = 0;

    @Column(precision = 8, scale = 4)
    private BigDecimal winRate = BigDecimal.ZERO;

    @Column
    private Integer status = 1;

    @Column(length = 500)
    private String errorMessage;

    @Column
    private Integer runTime = 0;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
