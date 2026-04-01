package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "data_update_task")
public class DataUpdateTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String taskId;

    @Column(nullable = false, length = 20)
    private String mode; // SINGLE / ALL / LIST

    @Column(nullable = false, length = 20)
    private String status; // PENDING / RUNNING / SUCCESS / PARTIAL / FAILED

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Lob
    private String stockList;

    private Integer totalStocks = 0;
    private Integer processedStocks = 0;
    private Integer successStocks = 0;
    private Integer failedStocks = 0;

    private Integer insertedCount = 0;
    private Integer skippedCount = 0;
    private Integer fetchedCount = 0;

    @Column(length = 500)
    private String message;

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
