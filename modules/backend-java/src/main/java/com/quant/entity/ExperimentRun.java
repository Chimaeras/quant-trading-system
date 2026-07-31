package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 实验运行注册表（QP-015）。
 *
 * <p>记录每次实验运行的配置、git commit、随机种子、数据集 ID（snapshot_id/label_version/
 * model_version）、训练/测试窗口、评估指标、状态，供完整追溯与复现。
 *
 * <p>与 {@link ModelVersion} 关联：一个实验可训练多个模型版本（T+2/T+3 分别建模）。
 *
 * <p>同一 experiment_id 唯一。
 */
@Data
@Entity
@Table(name = "experiment_run", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"experiment_id"})
})
public class ExperimentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 实验 ID：如 exp_20250731_001。 */
    @Column(nullable = false, length = 60)
    private String experimentId;

    /** 实验名称/描述。 */
    @Column(length = 200)
    private String name;

    /** 配置 JSON：如 {"algorithm":"logistic","l2":0.01,"lr":0.01,...}。 */
    @Column(columnDefinition = "TEXT")
    private String config;

    /** 代码版本（git commit hash），供复现。 */
    @Column(length = 40)
    private String gitCommit;

    /** 随机种子，供复现。 */
    private Long seed;

    /** 数据快照 ID（引用 data_snapshot.snapshot_id）。 */
    @Column(length = 40)
    private String snapshotId;

    /** 标签版本（引用 label_snapshot.label_version）。 */
    @Column(length = 40)
    private String labelVersion;

    /** 模型版本（引用 model_registry.model_version，逗号分隔多个）。 */
    @Column(length = 200)
    private String modelVersions;

    @Column(nullable = false)
    private LocalDate trainStart;

    @Column(nullable = false)
    private LocalDate trainEnd;

    private LocalDate testStart;
    private LocalDate testEnd;

    /** 评估指标 JSON：如 {"t2":{"brier":0.2,"auc":0.6},"t3":{...}}。 */
    @Column(columnDefinition = "TEXT")
    private String metrics;

    /** 状态：running / completed / failed / archived。 */
    @Column(nullable = false, length = 20)
    private String status = "completed";

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
