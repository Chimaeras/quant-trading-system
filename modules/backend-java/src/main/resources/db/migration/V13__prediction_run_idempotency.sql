-- V2-MP-030: prediction_run 幂等键。
-- publish_version 竞态（MAX+1）由既有唯一约束 uk (trade_date,horizon,track,publish_version) 兜底，
-- 配合 ConflictRetry 有界重试（见 service/ConflictRetry.java）。
ALTER TABLE prediction_run
    ADD COLUMN request_id VARCHAR(64) DEFAULT NULL COMMENT '幂等键：同 requestId 重复发布返回既有结果' AFTER selector_result_id,
    ADD UNIQUE KEY uk_prediction_run_request_id (request_id);
