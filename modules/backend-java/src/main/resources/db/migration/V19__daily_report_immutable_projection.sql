-- V2-MP-012: 选股日报 = prediction snapshot 的不可变投影头
-- 正文候选直接读 prediction_snapshot_item（不可变，唯一约束已保证 append-only），
-- 本表仅存投影头 + content_hash + 到期后追加的 realization。
CREATE TABLE IF NOT EXISTS daily_report (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id           VARCHAR(40)  NOT NULL,
    trade_date          DATE         NOT NULL,
    horizon             INT          NOT NULL COMMENT '2 or 3',
    track               VARCHAR(32)  NOT NULL COMMENT 'RULE_SCORE|PROBABILITY',
    publish_version     INT          NOT NULL,
    prediction_run_id   VARCHAR(40)  NOT NULL,
    source_result_id    VARCHAR(32)  DEFAULT NULL,
    factor_version      VARCHAR(40)  DEFAULT NULL,
    model_version       VARCHAR(60)  DEFAULT NULL,
    output_kind         VARCHAR(40)  DEFAULT NULL,
    schema_hash         VARCHAR(64)  DEFAULT NULL,
    deploy_key          VARCHAR(120) DEFAULT NULL,
    calibrator_version  VARCHAR(60)  DEFAULT NULL,
    calibrator_hash     VARCHAR(64)  DEFAULT NULL,
    snapshot_id         VARCHAR(40)  DEFAULT NULL,
    content_hash        VARCHAR(64)  NOT NULL COMMENT '候选集合确定性 SHA-256',
    item_count          INT          NOT NULL DEFAULT 0,
    report_version      INT          NOT NULL DEFAULT 1 COMMENT '= 所投影 publish_version',
    realization_json    LONGTEXT     DEFAULT NULL COMMENT '到期后追加，幂等落一次',
    generated_at        DATETIME(6)  DEFAULT NULL,
    UNIQUE KEY uk_daily_report_id (report_id),
    UNIQUE KEY uk_daily_report_snapshot (trade_date, horizon, track, publish_version),
    INDEX idx_daily_report_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选股日报（prediction snapshot 不可变投影，追加 realization 不覆盖）';
