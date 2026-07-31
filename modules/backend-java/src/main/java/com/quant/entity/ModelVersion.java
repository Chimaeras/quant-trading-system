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

    /** 状态：champion / challenger / archived。 */
    @Column(nullable = false, length = 20)
    private String stage = "challenger";

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
