package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 研究异步任务（MP-024）：重放、标签、校准、Logistic 训练等长耗时操作。
 */
@Data
@Entity
@Table(name = "research_task")
public class ResearchTask {

    public static final String TYPE_REPLAY = "REPLAY";
    public static final String TYPE_REPLAY_SIGNALS = "REPLAY_SIGNALS";
    public static final String TYPE_LABELS = "LABELS";
    public static final String TYPE_CALIBRATION = "CALIBRATION";
    public static final String TYPE_LOGISTIC = "LOGISTIC";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 应用重启后旧 RUNNING/PENDING 任务的状态：需要人工决定恢复（V2-MP-008）。 */
    public static final String STATUS_RECOVERY_REQUIRED = "RECOVERY_REQUIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String taskId;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private Integer progress = 0;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String paramsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String resultJson;

    @Column(length = 2000)
    private String errorMessage;

    /** 业务错误码（如 INSUFFICIENT_DATA / no_matches / no_calendar），区分"异常失败"与"业务无产物"。 */
    @Column(length = 64)
    private String errorCode;

    @Column(nullable = false)
    private Boolean published = false;

    /** 幂等键：同一 requestId 重复创建返回既有任务（V2-MP-008/030）。 */
    @Column(unique = true, length = 64)
    private String requestId;

    /** 协作式取消请求：worker 在发布前必须检查；置位后不得发布为 COMPLETED。 */
    @Column(nullable = false)
    private Boolean cancelRequested = false;

    /** 乐观锁/版本号（CAS 更新用，避免长任务与取消/发布竞争）。 */
    @Column(nullable = false)
    private Integer version = 0;

    /** 分区 checkpoint JSON：resume 时从失败分区继续（完整实现随 V2-MP-016）。 */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String checkpoint;

    /** task-scoped 产物暂存命名空间：未发布产物只在此可见（完整实现随 V2-MP-008/019）。 */
    @Column(length = 64)
    private String stagingNamespace;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime heartbeatAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        heartbeatAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
