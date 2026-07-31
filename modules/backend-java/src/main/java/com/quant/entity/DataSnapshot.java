package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数据快照注册表（QP-006）。
 *
 * <p>记录某一 cutoff 时点的数据底座状态（各表行数、source 版本、质量状态、content hash），
 * 供历史重放与实验复现：同一 {@code snapshotId} 重跑应得到完全一致的数据和特征。
 *
 * <p>hash 由各表行数与 source 分布拼接后 SHA-256 生成，用于检测数据底座是否发生变更；
 * 当前为「元数据快照」（行数/source/质量状态），非完整数据内容 hash。
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
    @Column(nullable = false, length = 40)
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

    /** content hash（SHA-256），由 row_counts + source_versions 拼接生成。 */
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
}
