package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一打分异步任务。
 */
@Data
@Entity
@Table(name = "unified_scoring_task")
public class UnifiedScoringTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String taskId;

    @Column(nullable = false, length = 32)
    private String strategy;

    @Column(nullable = false, length = 20)
    private String status; // PENDING / RUNNING / SUCCESS / FAILED

    @Column(nullable = false)
    private Integer progressPct = 0;

    private Integer totalItems = 0;
    private Integer returnedItems = 0;

    @Column(length = 32)
    private String resultId;

    @Column(length = 500)
    private String message;

    @Column(length = 1000)
    private String errorMessage;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String requestJson;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

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
