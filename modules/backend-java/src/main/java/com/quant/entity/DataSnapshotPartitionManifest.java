package com.quant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数据快照分区内容指纹（MP-004）。
 *
 * <p>每个 snapshot 对各表写入一行不可变指纹；只允许 insert，禁止 update。
 */
@Data
@Entity
@Table(name = "data_snapshot_partition_manifest", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"snapshot_id", "table_name"})
})
public class DataSnapshotPartitionManifest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String snapshotId;

    @Column(nullable = false, length = 64)
    private String tableName;

    private Long rowCount;

    private LocalDate minTradeDate;

    private LocalDate maxTradeDate;

    /** 稳定指纹串：count|minDate|maxDate|sums|maxIngestedAt。 */
    @Column(nullable = false, length = 512)
    private String fingerprint;

    /** fingerprint 的 SHA-256。 */
    @Column(nullable = false, length = 80)
    private String contentHash;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
