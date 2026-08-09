package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 滚动 Walk-Forward 单窗结果（V2-MP-023）。
 *
 * <p>每窗独立事务落库；同 (experimentId, windowNo) 幂等，重跑/resume 时已存在则复用，
 * 实现「改变未来测试数据不改变先前窗口产物」的不可变性。失败窗不跳过：以 status 标记
 * OK / INSUFFICIENT_DATA / FAILED，全部逐窗留存供审计。
 */
@Data
@Entity
@Table(name = "rolling_window_result")
public class RollingWindowResult {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String experimentId;

    @Column(nullable = false)
    private Integer windowNo;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "train_start")
    private LocalDate trainStart;
    @Column(name = "train_end")
    private LocalDate trainEnd;
    @Column(name = "valid_start")
    private LocalDate validStart;
    @Column(name = "valid_end")
    private LocalDate validEnd;
    @Column(name = "test_start")
    private LocalDate testStart;
    @Column(name = "test_end")
    private LocalDate testEnd;

    @Column(length = 60)
    private String modelVersion;

    @Column(length = 64)
    private String artifactHash;

    @Column(length = 60)
    private String calibratorVersion;

    @Column(length = 64)
    private String calibratorHash;

    @Column(length = 32)
    private String evaluationKind;

    private Integer oosSamples;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String metricsJson;

    @Column(length = 2000)
    private String errorMessage;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
