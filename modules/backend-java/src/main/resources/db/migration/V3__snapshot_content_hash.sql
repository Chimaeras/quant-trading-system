-- ============================================
-- Flyway V3: content-immutable data snapshot (MP-004)
-- + label_snapshot lineage columns (MP-002)
-- ============================================

-- DataSnapshot: content fingerprint columns
ALTER TABLE data_snapshot
    ADD COLUMN partition_manifest TEXT NULL COMMENT 'per-table content fingerprints JSON' AFTER quality_status,
    ADD COLUMN metadata_only BIT(1) NULL COMMENT 'true=legacy metadata-only hash; false=content fingerprints' AFTER partition_manifest,
    ADD COLUMN manifest_hash VARCHAR(80) NULL COMMENT 'SHA-256 of partition_manifest' AFTER metadata_only;

-- Mark existing rows as metadata-only (null → treat as true)
UPDATE data_snapshot SET metadata_only = 1 WHERE metadata_only IS NULL;

CREATE UNIQUE INDEX uk_data_snapshot_snapshot_id ON data_snapshot (snapshot_id);

-- Per-partition manifest rows (immutable insert-only)
CREATE TABLE IF NOT EXISTS data_snapshot_partition_manifest (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id     VARCHAR(40) NOT NULL,
    table_name      VARCHAR(64) NOT NULL,
    row_count       BIGINT DEFAULT NULL,
    min_trade_date  DATE DEFAULT NULL,
    max_trade_date  DATE DEFAULT NULL,
    fingerprint     VARCHAR(512) NOT NULL,
    content_hash    VARCHAR(80) NOT NULL,
    created_at      DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_dspm_snapshot_table (snapshot_id, table_name),
    INDEX idx_dspm_snapshot_id (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据快照分区内容指纹(不可变)';

-- LabelSnapshot: raw price lineage (MP-002)
ALTER TABLE label_snapshot
    ADD COLUMN data_snapshot_id VARCHAR(40) NULL COMMENT 'optional data snapshot id' AFTER source,
    ADD COLUMN price_source VARCHAR(40) NULL COMMENT 'market_price_raw / market_data' AFTER data_snapshot_id;
