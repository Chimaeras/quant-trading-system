package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日双轨预测运行（MP-011）：规则轨 / 概率轨，按 publish_version 不可变发布。
 */
@Data
@Entity
@Table(name = "prediction_run", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"run_id"}),
        @UniqueConstraint(columnNames = {"trade_date", "horizon", "track", "publish_version"})
})
public class PredictionRun {

    public static final String TRACK_RULE_SCORE = "RULE_SCORE";
    public static final String TRACK_PROBABILITY = "PROBABILITY";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ABSTAIN = "ABSTAIN";

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
    private Integer publishVersion = 1;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String abstainReason;

    @Column(length = 60)
    private String modelVersion;

    @Column(length = 40)
    private String factorVersion;

    @Column(length = 32)
    private String strategyType;

    @Column(length = 32)
    private String selectorResultId;

    @Column(columnDefinition = "LONGTEXT")
    private String qualityGateJson;

    @Column(columnDefinition = "LONGTEXT")
    private String lineageJson;

    @Column(nullable = false)
    private Integer itemCount = 0;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
