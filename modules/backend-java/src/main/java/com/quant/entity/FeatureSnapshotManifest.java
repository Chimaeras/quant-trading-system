package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 历史信号重放分区 manifest（MP-005 / V2-MP-016）。
 *
 * <p>幂等键：(snapshotId, signalDate, strategyVersion, featureSetVersion)。
 * 一个分区 = 一个信号日的完整候选截面；逐证券特征存 {@code feature_snapshot_row}，
 * 本表仅存分区状态、漏斗与 hash。仅 VERIFIED/PUBLISHED 分区可复用；FAILED 可重算；发布后只读。
 */
@Data
@Entity
@Table(name = "feature_snapshot_manifest", uniqueConstraints = {
        @UniqueConstraint(columnNames = {
                "snapshot_id", "signal_date", "strategy_version", "feature_set_version"
        })
})
public class FeatureSnapshotManifest {

    /** V1 遗留状态（只读兼容）。 */
    public static final String STATUS_OK = "OK";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SUMMARY = "SUMMARY";
    /** MP-016 分区状态机：RUNNING → VERIFIED / FAILED；VERIFIED 可发布为 PUBLISHED。 */
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_VERIFIED = "VERIFIED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

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

    /** MP-016：代码派生 schema 的确定性哈希。 */
    @Column(length = 64)
    private String schemaHash;

    /** MP-016：本分区完整候选截面行数（feature_snapshot_row 计数）。 */
    private Integer rowCount;

    /** MP-016：universe→tradable→featureComplete→signal 漏斗摘要 JSON。 */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String funnelJson;

    /** MP-016：发布后置 true，分区只读（不再重算、不再清行）。 */
    private Boolean published = false;

    private LocalDateTime publishedAt;

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
