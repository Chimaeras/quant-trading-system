-- V2-MP-023: 真正滚动 purged Walk-Forward
--
-- 目标：
--   * rolling_experiment：一次滚动实验的元数据/参数/汇总。evaluation_kind=rolling_wfo_oos，
--     与旧 SEGMENT_STABILITY（WalkForwardService）明确区分；不参与任何晋级 gate
--     （晋级 gate 只认 oos_holdout）；
--   * rolling_window_result：每窗 base model + 校准器 + artifact hash + 冻结 OOS 指标快照，
--     (experiment_id, window_no) 幂等；先逐窗落库再汇总 → 中断可恢复、改变未来测试数据
--     不改变先前窗口产物、失败窗不跳过。
-- 仅新增表，不改旧迁移。

CREATE TABLE IF NOT EXISTS rolling_experiment (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_id    VARCHAR(40)   NOT NULL COMMENT '滚动实验ID(WFO...)',
    evaluation_kind  VARCHAR(32)   NOT NULL DEFAULT 'rolling_wfo_oos' COMMENT '仅此 kind 的窗结果被本实验汇总',
    strategy_type    VARCHAR(40)   DEFAULT NULL,
    snapshot_id      VARCHAR(40)   DEFAULT NULL,
    label_version    VARCHAR(40)   DEFAULT NULL,
    feature_version  VARCHAR(64)   DEFAULT NULL,
    horizon          INT           DEFAULT NULL,
    window_mode      VARCHAR(16)   DEFAULT NULL COMMENT 'EXPANDING | ROLLING',
    train_days       INT           DEFAULT NULL,
    valid_days       INT           DEFAULT NULL,
    test_days        INT           DEFAULT NULL,
    embargo_days     INT           DEFAULT NULL,
    step_days        INT           DEFAULT NULL,
    start_date       DATE          DEFAULT NULL,
    end_date         DATE          DEFAULT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/COMPLETED/PARTIAL/FAILED',
    windows_total    INT           DEFAULT NULL,
    summary_json     LONGTEXT      DEFAULT NULL,
    params_json      LONGTEXT      DEFAULT NULL,
    error_message    VARCHAR(2000) DEFAULT NULL,
    created_at       DATETIME(6)   DEFAULT NULL,
    updated_at       DATETIME(6)   DEFAULT NULL,
    UNIQUE KEY uk_rolling_exp_id (experiment_id),
    KEY idx_rolling_exp_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='滚动Walk-Forward实验(V2-MP-023)';

CREATE TABLE IF NOT EXISTS rolling_window_result (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_id      VARCHAR(40)   NOT NULL,
    window_no          INT           NOT NULL,
    status             VARCHAR(20)   NOT NULL COMMENT 'OK/INSUFFICIENT_DATA/FAILED',
    train_start        DATE          DEFAULT NULL,
    train_end          DATE          DEFAULT NULL,
    valid_start        DATE          DEFAULT NULL,
    valid_end          DATE          DEFAULT NULL,
    test_start         DATE          DEFAULT NULL,
    test_end           DATE          DEFAULT NULL,
    model_version      VARCHAR(60)   DEFAULT NULL COMMENT '窗内 base logistic 唯一版本',
    artifact_hash      VARCHAR(64)   DEFAULT NULL COMMENT 'base model artifact SHA-256',
    calibrator_version VARCHAR(60)   DEFAULT NULL COMMENT '窗内校准器版本(绑定 base model)',
    calibrator_hash    VARCHAR(64)   DEFAULT NULL,
    evaluation_kind    VARCHAR(32)   DEFAULT NULL,
    oos_samples        INT           DEFAULT NULL,
    metrics_json       LONGTEXT      DEFAULT NULL,
    error_message      VARCHAR(2000) DEFAULT NULL,
    created_at         DATETIME(6)   DEFAULT NULL,
    UNIQUE KEY uk_rolling_window (experiment_id, window_no),
    KEY idx_rolling_window_exp (experiment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='滚动Walk-Forward单窗结果(V2-MP-023)';
