package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 模型注册表（QP-013/QP-015）。
 *
 * <p>记录模型版本、算法、特征/标签版本、训练区间、评估指标、状态，
 * 供实验追溯与复现。后续 QP-014 GBDT challenger 和 QP-016 rolling WFO 同样注册于此。
 *
 * <p>同一 model_version 唯一。
 */
@Data
@Entity
@Table(name = "model_registry", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"model_version"})
})
public class ModelVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模型版本：如 logistic_v1_t2 / logistic_v1_t3。 */
    @Column(nullable = false, length = 60)
    private String modelVersion;

    /** 算法：logistic / gbdt。 */
    @Column(nullable = false, length = 40)
    private String algorithm;

    /** 部署绑定策略（V2-MP-004）：CLASSIC_SELECTOR / OVERSOLD_REBOUND。 */
    @Column(length = 40)
    private String strategyType;

    /** 特征版本：如 rule_score_v1。 */
    @Column(length = 40)
    private String featureVersion;

    /** 标签版本：如 label_v1。 */
    @Column(length = 40)
    private String labelVersion;

    /** 持有期（2 或 3）。 */
    private Integer horizon;

    @Column(nullable = false)
    private LocalDate trainStart;

    @Column(nullable = false)
    private LocalDate trainEnd;

    private LocalDate testStart;
    private LocalDate testEnd;

    /** 模型权重 JSON：如 {"weights":[0.1,0.2],"bias":-0.5,"features":["rule_score"]}。 */
    @Column(columnDefinition = "TEXT")
    private String artifact;

    /** 评估指标 JSON：如 {"auc":0.6,"brier":0.2,"log_loss":0.5}。 */
    @Column(columnDefinition = "TEXT")
    private String metrics;

    /** 状态：champion / challenger / archived / DRAFT（导入待审）。 */
    @Column(nullable = false, length = 20)
    private String stage = "challenger";

    // ── V2-MP-025 显式 lineage / schema / 导入审核列 ─────────────────────────
    @Column(length = 40)
    private String dataSnapshotId;

    @Column(length = 64)
    private String featureSetVersion;

    @Column(length = 64)
    private String schemaHash;

    @Column(length = 64)
    private String codeVersion;

    @Column(length = 64)
    private String artifactHash;

    @Column(length = 32)
    private String outputKind;

    @Column(length = 64)
    private String calibratorHash;

    /** 推理精确查找键：algorithm:feature_set:schema_hash。 */
    @Column(length = 120)
    private String deployKey;

    @Column(length = 255)
    private String importSource;

    /** DRAFT=导入待审 / TRAINED=训练直写 / REGISTERED=已审核。 */
    @Column(length = 20)
    private String importStatus;

    /** 有序特征列快照（JSON 数组，导入时随 schema 固化）。 */
    @Column(columnDefinition = "TEXT")
    private String modelColumns;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
