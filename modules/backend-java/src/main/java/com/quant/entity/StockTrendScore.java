package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 趋势交易模型（daily_stock_analysis）选股打分结果，按交易日持久化。
 */
@Data
@Entity
@Table(name = "stock_trend_score", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"stock_code", "exchange_type", "trade_date"})
})
public class StockTrendScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(length = 100)
    private String stockName;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private Double totalScore;

    @Column(nullable = false)
    private Double scoreTrend;

    @Column(nullable = false)
    private Double scoreBias;

    @Column(nullable = false)
    private Double scoreVolume;

    @Column(nullable = false)
    private Double scoreSupport;

    @Column(nullable = false)
    private Double scoreMacd;

    @Column(nullable = false)
    private Double scoreRsi;

    @Column(nullable = false)
    private Double scoreTurnover;

    @Column(nullable = false)
    private Double scoreDelta;

    @Column(nullable = false)
    private Double scoreAmplitude;

    /** 原始换手率（%），用于结果展示和调试 */
    @Column
    private Double turnoverRate;

    /** 连续放量天数 */
    @Column
    private Integer consecutiveVolumeDays;

    @Column(nullable = false, length = 32)
    private String trendStatus;

    @Column(nullable = false, length = 32)
    private String volumeStatus;

    @Column(nullable = false, length = 32)
    private String macdStatus;

    @Column(nullable = false, length = 32)
    private String rsiStatus;

    @Column(nullable = false, length = 32)
    private String buySignal;

    /** 计算时使用的乖离阈值快照（趋势打分专用） */
    @Column
    private Double biasThreshold;

    @Column(nullable = false, length = 32)
    private String factorVersion;

    /** 详细评分原因JSON */
    @Column(columnDefinition = "TEXT")
    private String reasonsJson;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
