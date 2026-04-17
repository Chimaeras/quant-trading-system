package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Entity
@Table(name = "selector_result")
public class SelectorResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String resultId;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private Integer lookbackDays = 60;

    @Column(nullable = false)
    private Integer topN = 10;

    @Column(nullable = false)
    private Double minAvgAmount = 30000000.0;

    @Column(nullable = false)
    private Double minTrendScore = 40.0;

    @Column(length = 64)
    private String templateId;

    @Column(length = 32)
    private String factorVersion = "v1";

    /** 统一入口策略类型（TREND/COMPREHENSIVE/MULTI_DIM/CLASSIC_SELECTOR/OVERSOLD_REBOUND/GEOPOLICY_NEWS） */
    @Column(length = 32)
    private String strategyType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> factorSnapshot;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
