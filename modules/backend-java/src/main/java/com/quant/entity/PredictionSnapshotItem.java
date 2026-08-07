package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预测快照明细（MP-011）：唯一键含 date/horizon/track/version/stock，不可覆盖。
 */
@Data
@Entity
@Table(name = "prediction_snapshot_item", uniqueConstraints = {
        @UniqueConstraint(columnNames = {
                "trade_date", "horizon", "track", "publish_version", "stock_code", "exchange_type"
        })
})
public class PredictionSnapshotItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String runId;

    @Column(nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false)
    private Integer horizon;

    @Column(nullable = false, length = 32)
    private String track;

    @Column(nullable = false)
    private Integer publishVersion;

    @Column(nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String exchangeType;

    @Column(length = 100)
    private String stockName;

    private Integer rankNo;

    /** 规则分或校准概率。 */
    private Double score;

    @Column(length = 40)
    private String scoreType;

    @Column(columnDefinition = "LONGTEXT")
    private String reasonsJson;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
