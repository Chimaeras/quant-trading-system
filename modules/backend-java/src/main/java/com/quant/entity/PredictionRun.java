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
        @UniqueConstraint(columnNames = {"trade_date", "horizon", "track", "publish_version"}),
        // 一次 generate 写 RULE_SCORE + PROBABILITY 两条 run 且共用同一 requestId，
        // 故幂等键唯一性落在 (request_id, track) 上（Flyway V22）。
        @UniqueConstraint(columnNames = {"request_id", "track"})
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

    /** 幂等键：同 requestId 重复发布返回既有结果，不重复生成 publish_version（V2-MP-030）。
     *  唯一性为 (request_id, track) 复合——见类级 uniqueConstraints 与 Flyway V22。 */
    @Column(length = 64)
    private String requestId;

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
