-- V2-MP-008: 研究异步任务持久化状态机（CAS/协作式取消/恢复/幂等）
-- 仅新增列与索引，不改旧迁移，不改已存在数据。
ALTER TABLE research_task
    ADD COLUMN error_code           VARCHAR(64)   DEFAULT NULL COMMENT '业务错误码(如 INSUFFICIENT_DATA/no_matches/no_calendar)' AFTER error_message,
    ADD COLUMN request_id           VARCHAR(64)   DEFAULT NULL COMMENT '幂等键：同 requestId 重复创建返回既有任务' AFTER published,
    ADD COLUMN cancel_requested     BIT(1)        NOT NULL DEFAULT 0 COMMENT '协作式取消请求：worker 发布前必须检查' AFTER request_id,
    ADD COLUMN version              INT           NOT NULL DEFAULT 0 COMMENT '乐观锁/版本号(CAS 更新)' AFTER cancel_requested,
    ADD COLUMN checkpoint           LONGTEXT      DEFAULT NULL COMMENT '分区 checkpoint JSON(随 V2-MP-016 使用)' AFTER version,
    ADD COLUMN staging_namespace    VARCHAR(64)   DEFAULT NULL COMMENT 'task-scoped 产物暂存命名空间' AFTER checkpoint,
    ADD UNIQUE KEY uk_research_task_request_id (request_id);
