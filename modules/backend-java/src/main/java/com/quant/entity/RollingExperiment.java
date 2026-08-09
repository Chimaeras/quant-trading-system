package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 真正滚动 purged Walk-Forward 实验（V2-MP-023）。
 *
 * <p>独立于旧 {@code WalkForwardService} 的「分段稳定性」（SEGMENT_STABILITY，非样本外）。
 * 每个窗口按 train（拟合预处理/base 模型）→ valid（校准 fit）→ purge/embargo → test（冻结 OOS 评估）
 * 推进；每窗 base model + 校准器均唯一并持久化，具备 window/model/calibrator 三元 lineage。
 * 仅作研究，evaluation_kind=rolling_wfo_oos，不参与任何晋级 gate。
 */
@Data
@Entity
@Table(name = "rolling_experiment")
public class RollingExperiment {

    /** 本实验唯一可识别的评估 kind：晋级 gate 只认 oos_holdout，绝不会误收本 kind。 */
    public static final String EVALUATION_KIND = "rolling_wfo_oos";

    public static final String MODE_EXPANDING = "EXPANDING";
    public static final String MODE_ROLLING = "ROLLING";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String experimentId;

    @Column(nullable = false, length = 32)
    private String evaluationKind;

    @Column(length = 40)
    private String strategyType;

    @Column(length = 40)
    private String snapshotId;

    @Column(length = 40)
    private String labelVersion;

    @Column(length = 64)
    private String featureVersion;

    private Integer horizon;

    @Column(length = 16)
    private String windowMode;

    private Integer trainDays;
    private Integer validDays;
    private Integer testDays;
    private Integer embargoDays;
    private Integer stepDays;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false, length = 20)
    private String status;

    private Integer windowsTotal;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String summaryJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String paramsJson;

    @Column(length = 2000)
    private String errorMessage;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
