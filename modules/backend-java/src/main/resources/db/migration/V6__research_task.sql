-- MP-024: lightweight async research task infrastructure
CREATE TABLE IF NOT EXISTS research_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(32) NOT NULL,
    type            VARCHAR(32) NOT NULL COMMENT 'REPLAY|LABELS|CALIBRATION|LOGISTIC',
    status          VARCHAR(20) NOT NULL COMMENT 'PENDING|RUNNING|COMPLETED|FAILED|CANCELLED',
    progress        INT NOT NULL DEFAULT 0,
    params_json     LONGTEXT DEFAULT NULL,
    result_json     LONGTEXT DEFAULT NULL,
    error_message   VARCHAR(2000) DEFAULT NULL,
    published       BIT(1) NOT NULL DEFAULT 0,
    created_at      DATETIME(6) DEFAULT NULL,
    updated_at      DATETIME(6) DEFAULT NULL,
    heartbeat_at    DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_research_task_id (task_id),
    INDEX idx_research_task_status (status),
    INDEX idx_research_task_type_created (type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='研究异步任务';
