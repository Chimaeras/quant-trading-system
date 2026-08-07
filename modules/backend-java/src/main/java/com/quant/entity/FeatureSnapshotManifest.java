package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 历史信号重放轻量 manifest（MP-005）。
 *
 * <p>幂等键：(snapshotId, signalDate, strategyVersion, featureSetVersion)。
 * 仅存覆盖率摘要与 contentHash，不存全市场特征矩阵。
 */
@Data
@Entity
@Table(name = "feature_snapshot_manifest", uniqueConstraints = {
        @UniqueConstraint(columnNames = {
                "snapshot_id", "signal_date", "strategy_version", "feature_set_version"
        })
})
public class FeatureSnapshotManifest {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SUMMARY = "SUMMARY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String snapshotId;

    @Column(nullable = false)
    private LocalDate signalDate;

    @Column(nullable = false, length = 64)
    private String strategyVersion;

    @Column(nullable = false, length = 64)
    private String featureSetVersion;

    private Integer stockCount;

    private Integer scoredCount;

    @Column(nullable = false, length = 80)
    private String contentHash;

    @Column(nullable = false, length = 32)
    private String status;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String filterSummaryJson;

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
