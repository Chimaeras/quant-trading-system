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

    @Column(nullable = false)
    private Boolean published = false;

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
