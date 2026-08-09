-- V2-MP-025: 显式模型 lineage / schema / 导入审核列
--
-- 目标：
--   * 把埋在 artifact/metrics JSON 里的 lineage 提升为显式列（快照/特征集/schema/代码/产物哈希）；
--   * 增加 deploy_key：推理按「精确 schema 键」查找，不再只按 horizon/stage 猜测；
--   * 增加 import_status + stage=DRAFT：导入产物只到 DRAFT 待审，导入时绝不直接设 champion。
-- 仅新增列，不改旧迁移，不动既有数据；旧行这些列留空（legacy，读取路径有兼容回退）。

ALTER TABLE model_registry
    ADD COLUMN data_snapshot_id    VARCHAR(40)   DEFAULT NULL COMMENT '训练数据快照 ID(显式 lineage, MP-025)' AFTER metrics,
    ADD COLUMN feature_set_version VARCHAR(64)   DEFAULT NULL COMMENT '特征矩阵 schema 版本(如 feature_matrix_v1/rule_score_v1)' AFTER data_snapshot_id,
    ADD COLUMN schema_hash         VARCHAR(64)   DEFAULT NULL COMMENT '代码派生列级 schemaHash(MP-025)' AFTER feature_set_version,
    ADD COLUMN code_version        VARCHAR(64)   DEFAULT NULL COMMENT '训练/导出代码版本(MP-025)' AFTER schema_hash,
    ADD COLUMN artifact_hash       VARCHAR(64)   DEFAULT NULL COMMENT 'artifact SHA-256(导入完整性校验)' AFTER code_version,
    ADD COLUMN output_kind         VARCHAR(32)   DEFAULT NULL COMMENT '输出类型: probability|rule_score|baseline_calibrator' AFTER artifact_hash,
    ADD COLUMN calibrator_hash     VARCHAR(64)   DEFAULT NULL COMMENT '校准器/契约哈希' AFTER output_kind,
    ADD COLUMN deploy_key          VARCHAR(120)  DEFAULT NULL COMMENT '推理精确查找键 algorithm:feature_set:schema_hash' AFTER calibrator_hash,
    ADD COLUMN import_source       VARCHAR(255)  DEFAULT NULL COMMENT '导入来源(受控目录/API/训练器)' AFTER deploy_key,
    ADD COLUMN import_status       VARCHAR(20)   DEFAULT NULL COMMENT 'DRAFT=导入待审; TRAINED=训练直写; REGISTERED=已审核' AFTER import_source,
    ADD COLUMN model_columns       TEXT          DEFAULT NULL COMMENT '有序特征列快照(JSON 数组)' AFTER import_status,
    -- V2-MP-024 修复：ALTER TABLE 内建索引必须用 ADD INDEX（裸 INDEX 在 MySQL 报 1064）
    ADD INDEX idx_mr_deploy_key (deploy_key);
