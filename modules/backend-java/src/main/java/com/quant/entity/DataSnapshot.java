package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数据快照注册表（QP-006 / MP-004）。
 *
 * <p>记录某一 cutoff 时点的数据底座状态（各表行数、source 版本、质量状态、内容指纹 hash），
 * 供历史重放与实验复现：同一 {@code snapshotId} 重跑应得到完全一致的数据和特征。
 *
 * <p>新快照（{@code metadataOnly=false}）hash 包含各表内容指纹（count/min/max/sum/max_ingested_at）；
 * 旧行 {@code metadataOnly=null/true} 仅为元数据快照。快照行只允许 insert，禁止 update。
 *
 * <p>同一 cutoffTime 可创建多次（每次重跑数据底座可能变化），按 createdAt 升序区分版本。
 */
@Data
@Entity
@Table(name = "data_snapshot")
public class DataSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 快照逻辑标识：cutoff_time(yyyyMMdd) + 短序号，如 20250731-001。 */
    @Column(nullable = false, length = 40, unique = true)
    private String snapshotId;

    /** 数据截止时点（最新交易日或人工指定）。 */
    @Column(nullable = false)
    private LocalDate cutoffTime;

    /** 各表行数 JSON：如 {"market_data":12345,"market_price_raw":678,...}。 */
    @Column(columnDefinition = "TEXT")
    private String rowCounts;

    /** 各表 source 分布 JSON：如 {"market_price_raw":{"eastmoney_fqt0":678},...}。 */
    @Column(columnDefinition = "TEXT")
    private String sourceVersions;

    /** 质量状态摘要 JSON：如 {"health":"warn","missing_trading_days":3,...}。 */
    @Column(columnDefinition = "TEXT")
    private String qualityStatus;

    /** 各表内容指纹 JSON（partition manifest）。 */
    @Column(columnDefinition = "TEXT")
    private String partitionManifest;

    /**
     * 是否仅为元数据快照。新快照默认 false；旧行 null 视为 true。
     */
    private Boolean metadataOnly;

    /** partition_manifest 的 SHA-256（可选冗余）。 */
    @Column(length = 80)
    private String manifestHash;

    /** content hash（SHA-256），含 row_counts + source_versions + 内容指纹。 */
    @Column(nullable = false, length = 80)
    private String hash;

    /** 快照生成方式：auto（系统自动）/ manual（人工触发）。 */
    @Column(nullable = false, length = 20)
    private String createdBy = "manual";

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /** 旧行 null → 视为 metadata-only。 */
    public boolean isMetadataOnlyEffective() {
        return metadataOnly == null || Boolean.TRUE.equals(metadataOnly);
    }
}
