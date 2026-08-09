-- V2-MP-016: 分区化完整特征快照（逐证券特征行 + manifest 分区状态字段）
--
-- 目标：
--   * 不再只保存 TopN 摘要，而是按 (snapshot_id, signal_date, strategy_version, feature_set_version)
--     分区落库完整候选截面的逐证券特征行，供训练与因子 IC 消费；
--   * manifest 从「轻量摘要」升级为分区状态机：RUNNING → VERIFIED / FAILED，发布后 PUBLISHED 只读；
--   * FAILED 分区清行后可重试；仅 VERIFIED/PUBLISHED 分区可复用。
-- 仅新增表与列，不改旧迁移，不改已存在数据。

CREATE TABLE IF NOT EXISTS feature_snapshot_row (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id         VARCHAR(40) NOT NULL,
    signal_date         DATE NOT NULL,
    strategy_version    VARCHAR(64) NOT NULL,
    feature_set_version VARCHAR(64) NOT NULL,
    schema_hash         VARCHAR(64) NOT NULL COMMENT '代码派生 schema 的确定性哈希',
    stock_code          VARCHAR(16) NOT NULL,
    exchange_type       VARCHAR(8)  NOT NULL,
    rank_no             INT DEFAULT NULL COMMENT '分区内候选排序位(1 起)',
    filter_status       VARCHAR(24) NOT NULL COMMENT '漏斗位置: UNIVERSE|TRADABLE|FEATURE_COMPLETE|SIGNAL',
    feature_json        LONGTEXT DEFAULT NULL COMMENT '特征向量 JSON(结构由 FeatureSchemaRegistry 派生)',
    final_score         DOUBLE DEFAULT NULL,
    created_at          DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_fsr_identity (snapshot_id, signal_date, strategy_version, feature_set_version, stock_code),
    INDEX idx_fsr_partition (snapshot_id, signal_date, strategy_version, feature_set_version),
    INDEX idx_fsr_snapshot_date (snapshot_id, signal_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='历史信号重放逐证券特征行(完整截面)';

ALTER TABLE feature_snapshot_manifest
    ADD COLUMN schema_hash   VARCHAR(64) DEFAULT NULL COMMENT '代码派生 schema 哈希(MP-016)' AFTER filter_summary_json,
    ADD COLUMN row_count     INT         DEFAULT NULL COMMENT '本分区完整候选截面行数(MP-016)' AFTER schema_hash,
    ADD COLUMN funnel_json   LONGTEXT    DEFAULT NULL COMMENT 'universe→tradable→featureComplete→signal 漏斗(MP-016)' AFTER row_count,
    ADD COLUMN published     BIT(1)      NOT NULL DEFAULT 0 COMMENT '发布后置 1，分区只读(MP-016)' AFTER funnel_json,
    ADD COLUMN published_at  DATETIME(6) DEFAULT NULL COMMENT '发布时点(MP-016)' AFTER published;
