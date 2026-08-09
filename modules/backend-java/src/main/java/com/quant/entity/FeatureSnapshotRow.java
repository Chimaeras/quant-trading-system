package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 历史信号重放逐证券特征行（V2-MP-016）。
 *
 * <p>分区键与 {@link FeatureSnapshotManifest} 一致：(snapshotId, signalDate, strategyVersion,
 * featureSetVersion)。每个分区的完整候选截面逐证券落一行，供训练与因子 IC 消费，不再只存 TopN 摘要。
 *
 * <p>{@code filterStatus} 记录该证券在本分区漏斗中的位置（SIGNAL = 通过可交易/特征完整并进入候选）。
 * {@code schemaHash} 为代码派生 schema 的确定性哈希，禁止调用方自报 schema 版本。
 */
@Data
@Entity
@Table(name = "feature_snapshot_row", uniqueConstraints = {
        @UniqueConstraint(columnNames = {
                "snapshot_id", "signal_date", "strategy_version", "feature_set_version", "stock_code"
        })
})
public class FeatureSnapshotRow {

    public static final String FILTER_SIGNAL = "SIGNAL";
    public static final String FILTER_FEATURE_COMPLETE = "FEATURE_COMPLETE";
    public static final String FILTER_TRADABLE = "TRADABLE";
    public static final String FILTER_UNIVERSE = "UNIVERSE";

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

    @Column(nullable = false, length = 64)
    private String schemaHash;

    @Column(nullable = false, length = 16)
    private String stockCode;

    @Column(nullable = false, length = 8)
    private String exchangeType;

    /** 候选排序位（同分区内 1 起）。 */
    private Integer rankNo;

    /** 该证券在漏斗中的位置：UNIVERSE → TRADABLE → FEATURE_COMPLETE → SIGNAL。 */
    @Column(nullable = false, length = 24)
    private String filterStatus;

    /** 特征向量 JSON（列结构由 FeatureSchemaRegistry 派生，schemaHash 对齐）。 */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String featureJson;

    /** 最终得分（可选，未打分时为空）。 */
    private Double finalScore;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
