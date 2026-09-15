-- V24__selector_result_run_authority.sql
-- 裁定来源：research/paper_loop/decisions/decision_batch3_2026-09-15.md §4 裁定 D「做」
-- 条目：D-R005-01（新增「运行权威性」标记列，纯加法，不动旧列语义；唯一约束暂缓）
--
-- 问题：同一 (trade_date, factor_version, topn) 下存在多份 result_id，载荷/下游无从判断哪一份「算数」。
--
-- 只读实测（本地 quant_db，2026-09-15，MySQL 8.0.41，仅 SELECT）：
--   SELECT COUNT(*) runs, COUNT(DISTINCT CONCAT(trade_date,'|',IFNULL(factor_version,'-'),'|',topn)) grp
--     FROM selector_result;                        -- 67 runs / 39 组
--   SELECT SUM(runs>1) multi_groups, MAX(runs) max_runs, SUM(runs) runs_in_multi
--     FROM (SELECT COUNT(*) runs FROM selector_result GROUP BY trade_date, factor_version, topn) t;
--                                                  -- 18 组多 run / 单组最多 4 run / 67 run 全部落在多 run 组内
-- 即：18/39 = 46% 的组无法凭 (trade_date, factor_version, topn) 唯一确定一行 ⟹ 标记列有实际承载对象。
--
-- 设计边界（与裁定一致）：
--   * 纯加法：不改任何既有列的类型/语义/默认值；
--   * 不自动指定权威运行：NULL = 未标记。凭「created_at 最新」自动指定等于伪造一次裁定
--     （与候选项③「运行输入快照」同根），故不写自动回填；
--   * 唯一约束**暂缓**：见 V26。
--
-- 回滚（注释保留；Flyway CE 无 undo）：
--   ALTER TABLE selector_result DROP INDEX idx_selector_result_run_key;
--   ALTER TABLE selector_result
--       DROP COLUMN authority_marked_at, DROP COLUMN authority_source, DROP COLUMN authoritative_run;

ALTER TABLE selector_result
    ADD COLUMN authoritative_run  TINYINT(1)   DEFAULT NULL
        COMMENT '运行权威性标记：1=本行 result_id 为本组(trade_date,factor_version,topn)的权威运行；NULL=未标记（本迁移不自动指定）'
        AFTER run_status,
    ADD COLUMN authority_source   VARCHAR(32)  DEFAULT NULL
        COMMENT '权威性来源：MANUAL_OPERATOR / RUN_INPUT_SNAPSHOT / RULE_* 等；NULL=未标记'
        AFTER authoritative_run,
    ADD COLUMN authority_marked_at DATETIME(6) DEFAULT NULL
        COMMENT '权威性标记时间；NULL=未标记'
        AFTER authority_source;

-- 支撑「按组查多 run」的只读核实查询与上层筛选：现有索引没有以这三列打头的组合索引。
ALTER TABLE selector_result
    ADD INDEX idx_selector_result_run_key (trade_date, factor_version, topn);

-- 只读核实（迁移后执行；只读，不改数据）：
--   按组列出多 run 与标记情况（本迁移后全部应为 NULL=未标记）：
--   SELECT r.trade_date, r.factor_version, r.topn, COUNT(*) runs,
--          SUM(r.authoritative_run = 1) marked,
--          GROUP_CONCAT(r.result_id ORDER BY r.id) result_ids,
--          GROUP_CONCAT(IFNULL(r.authority_source,'-') ORDER BY r.id) sources
--     FROM selector_result r
--    GROUP BY r.trade_date, r.factor_version, r.topn
--   HAVING runs > 1
--    ORDER BY r.trade_date DESC;
--
-- BLOCKED 回填（**已注释，禁止在无运行输入快照/运维裁定前取消注释**）：
--   BLOCKED: needs run-input snapshot / operator decision (D-R005-01 候选项③)。
--   回填必须逐组给出权威 result_id 及其 authority_source，禁止用启发式（最新 created_at、最大行数等）。
--   例（模板，勿直接执行）：
--   -- UPDATE selector_result SET authoritative_run = 0 WHERE authoritative_run IS NULL;
--   -- UPDATE selector_result SET authoritative_run = 1, authority_source = 'MANUAL_OPERATOR',
--   --        authority_marked_at = NOW(6)
--   --  WHERE result_id IN ('<逐组人工确认的 result_id>');
