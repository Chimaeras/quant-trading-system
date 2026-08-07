-- MP-011: Daily T+2/T+3 dual-track prediction snapshot
CREATE TABLE IF NOT EXISTS prediction_run (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id              VARCHAR(40)  NOT NULL,
    trade_date          DATE         NOT NULL,
    horizon             INT          NOT NULL COMMENT '2 or 3',
    track               VARCHAR(32)  NOT NULL COMMENT 'RULE_SCORE|PROBABILITY',
    publish_version     INT          NOT NULL DEFAULT 1,
    status              VARCHAR(20)  NOT NULL COMMENT 'PUBLISHED|ABSTAIN',
    abstain_reason      VARCHAR(500) DEFAULT NULL,
    model_version       VARCHAR(60)  DEFAULT NULL,
    factor_version      VARCHAR(40)  DEFAULT NULL,
    strategy_type       VARCHAR(32)  DEFAULT NULL,
    selector_result_id  VARCHAR(32)  DEFAULT NULL,
    quality_gate_json   LONGTEXT     DEFAULT NULL,
    lineage_json        LONGTEXT     DEFAULT NULL,
    item_count          INT          NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  DEFAULT NULL,
    UNIQUE KEY uk_prediction_run_id (run_id),
    UNIQUE KEY uk_prediction_run_publish (trade_date, horizon, track, publish_version),
    INDEX idx_prediction_run_date_horizon (trade_date, horizon)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日双轨预测运行（不可变发布）';

CREATE TABLE IF NOT EXISTS prediction_snapshot_item (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id              VARCHAR(40)  NOT NULL,
    trade_date          DATE         NOT NULL,
    horizon             INT          NOT NULL,
    track               VARCHAR(32)  NOT NULL,
    publish_version     INT          NOT NULL,
    stock_code          VARCHAR(20)  NOT NULL,
    exchange_type       VARCHAR(20)  NOT NULL,
    stock_name          VARCHAR(100) DEFAULT NULL,
    rank_no             INT          DEFAULT NULL,
    score               DOUBLE       DEFAULT NULL COMMENT 'rule_score or probability',
    score_type          VARCHAR(40)  DEFAULT NULL,
    reasons_json        LONGTEXT     DEFAULT NULL,
    created_at          DATETIME(6)  DEFAULT NULL,
    UNIQUE KEY uk_prediction_item_immutable (trade_date, horizon, track, publish_version, stock_code, exchange_type),
    INDEX idx_prediction_item_run (run_id),
    INDEX idx_prediction_item_date (trade_date, horizon, track)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预测快照明细（不可覆盖）';
