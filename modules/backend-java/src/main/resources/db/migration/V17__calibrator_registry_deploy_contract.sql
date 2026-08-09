-- V2-MP-004: 概率 deploy contract + 持久化校准器
--
-- 目标：
--   * model_registry 增 strategy_type：可发布概率版本原子绑定策略（不再只按 horizon/stage 猜）；
--   * 新表 calibrator_registry：持久化校准器（PAV/sigmoid knots/bins + norm + hash），
--     推理顺序 特征→base probability/score→持久化校准器→calibrated probability；
--   * stage=champion 的校准器 + 完整 lineage 的 champion base model 才产生概率 items。
-- 仅新增列/表，不改旧迁移；旧 champion 无 strategy_type/deploy_key 等 lineage 时，读取路径回退为 contract_incomplete 并停止概率发布。

ALTER TABLE model_registry
    ADD COLUMN strategy_type VARCHAR(40) DEFAULT NULL COMMENT '部署绑定策略(MP-004): CLASSIC_SELECTOR/OVERSOLD_REBOUND' AFTER algorithm;

CREATE TABLE IF NOT EXISTS calibrator_registry (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    calibrator_version  VARCHAR(60)  NOT NULL COMMENT '校准器版本(确定性, 唯一)',
    strategy_type       VARCHAR(40)  DEFAULT NULL COMMENT '绑定策略',
    horizon             INT          DEFAULT NULL COMMENT '持有期 T+2/T+3',
    factor_version      VARCHAR(64)  DEFAULT NULL COMMENT '因子版本',
    label_version       VARCHAR(40)  DEFAULT NULL COMMENT '标签版本',
    data_snapshot_id    VARCHAR(40)  DEFAULT NULL COMMENT 'fit/校准所绑快照',
    output_kind         VARCHAR(32)  DEFAULT NULL COMMENT 'pav_isotonic | platt_sigmoid',
    input_domain        VARCHAR(32)  DEFAULT NULL COMMENT 'rule_score | base_probability',
    base_model_version  VARCHAR(60)  DEFAULT NULL COMMENT '被校准的 base model(原子绑定)',
    base_output_kind    VARCHAR(32)  DEFAULT NULL COMMENT 'base model 输出类型',
    calibrator_json     TEXT         DEFAULT NULL COMMENT 'knots/bins + norm 自包含 JSON',
    calibrator_hash     VARCHAR(64)  DEFAULT NULL COMMENT 'calibrator_json SHA-256(完整性)',
    code_version        VARCHAR(64)  DEFAULT NULL COMMENT '校准代码版本',
    fit_start           DATE         DEFAULT NULL,
    fit_end             DATE         DEFAULT NULL,
    eval_start          DATE         DEFAULT NULL,
    eval_end            DATE         DEFAULT NULL,
    stage               VARCHAR(20)  NOT NULL DEFAULT 'challenger' COMMENT 'champion/challenger/archived/DRAFT',
    created_at          DATETIME(6)  DEFAULT NULL,
    UNIQUE KEY uk_cal_version (calibrator_version),
    KEY idx_cal_stage (strategy_type, horizon, stage),
    KEY idx_cal_base (strategy_type, horizon, base_model_version, stage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='概率校准器注册表(MP-004)';
