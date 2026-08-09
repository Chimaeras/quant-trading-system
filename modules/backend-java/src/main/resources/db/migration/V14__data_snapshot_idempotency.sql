-- V2-MP-030: data_snapshot 内容幂等唯一键（cutoff 日 + 内容 hash）。
-- 同 cutoff 同内容重复创建 → find-or-return 返回既有快照；并发竞态由该唯一键兜底 + ConflictRetry。
ALTER TABLE data_snapshot
    ADD UNIQUE KEY uk_data_snapshot_cutoff_hash (cutoff_time, hash);
