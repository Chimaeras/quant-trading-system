-- MP-005: lightweight feature/signal replay manifest (idempotent by snapshot+date+strategy+feature)
CREATE TABLE IF NOT EXISTS feature_snapshot_manifest (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id         VARCHAR(40) NOT NULL,
    signal_date         DATE NOT NULL,
    strategy_version    VARCHAR(64) NOT NULL,
    feature_set_version VARCHAR(64) NOT NULL,
    stock_count         INT DEFAULT NULL,
    scored_count        INT DEFAULT NULL,
    content_hash        VARCHAR(80) NOT NULL,
    status              VARCHAR(32) NOT NULL COMMENT 'OK|SKIPPED|FAILED|SUMMARY',
    filter_summary_json LONGTEXT DEFAULT NULL,
    created_at          DATETIME(6) DEFAULT NULL,
    updated_at          DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_fsm_identity (snapshot_id, signal_date, strategy_version, feature_set_version),
    INDEX idx_fsm_snapshot_date (snapshot_id, signal_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='历史信号重放轻量 manifest';
