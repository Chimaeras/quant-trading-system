-- R31-P1-1：prediction_run 幂等键修正。
--
-- 背景：V13 把 request_id 建成单列唯一索引，但一次 POST /api/predictions/generate
-- 会同时写入 RULE_SCORE 与 PROBABILITY 两条 run（PredictionService.persistTrack 调用两次，
-- 共用同一 requestId）。第二条 INSERT 必然违反单列唯一键，ConflictRetry 四次重试后
-- 抛 409——带 requestId 的发布路径实际不可用（幂等重放成为死代码）。
--
-- 修正：唯一性下沉到「同一 requestId 的每条 track 至多一条」，与
-- PredictionRunRepository.findByRequestId 返回 List（注释明示两条 run 共用 requestId）的契约一致。
ALTER TABLE prediction_run
    DROP KEY uk_prediction_run_request_id;

ALTER TABLE prediction_run
    ADD UNIQUE KEY uk_prediction_run_request_track (request_id, track);
