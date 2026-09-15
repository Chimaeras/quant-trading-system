-- V27__selector_result_ic_value_dead_column_comment.sql
-- 裁定来源：research/paper_loop/decisions/decision_batch3_2026-09-15.md §4 裁定 D「做」
-- 条目：D-R006-06（死列 ic_value：**只补项目级文档声明**；**不删列**）
--
-- ⚠️ 与裁定的字面写法有一处**偏差，在此披露**：裁定写的是「`V2__research_tables.sql` 注释 + `docs/`」，
--    但 V2 是**已应用**的历史迁移，改它会让 Flyway checksum 校验失败
--    （见 `docs/运维与迁移runbook.md` §1.3：禁止手工改历史 V*.sql）。故改为
--    **新增一条迁移**（本文件）落列注释 + 在 `docs/研究口径规范.md` 落项目级声明——语义与裁定一致，形式合规。
--    （MySQL 8 无 `COMMENT ON COLUMN`，改注释只能用 `MODIFY COLUMN` 全量重述定义。）
--
-- ── 只读实测（本地 quant_db，2026-09-15，仅 SELECT）────────────────────────────
--   SELECT COUNT(*) total, SUM(ic_value IS NOT NULL) ic_nonnull,
--          SUM(ir_value IS NOT NULL) ir_nonnull, SUM(sharpe_contribution IS NOT NULL) sc_nonnull
--     FROM selector_result;
--        -> 67 行 / ic_value 非空 0 / ir_value 非空 0 / sharpe_contribution 非空 0
--   ⟹ 「死列」成立：67 行全部为 NULL，无任何写入路径，研究上不可用。
--
-- 项目级声明（同批落地）：`docs/研究口径规范.md` §8.7（死列、无写入路径、0/67 非空、
--   结构上无法做研究、需要 IC 请在研究侧重算而**不要**回填）。
--
-- ── 回滚 ─────────────────────────────────────────────────────────────────────
--   重发不带 COMMENT 的 MODIFY（类型与默认值均未改，故回滚只影响注释）：
--   ALTER TABLE selector_result MODIFY COLUMN ic_value DOUBLE DEFAULT NULL;

ALTER TABLE selector_result
    MODIFY COLUMN ic_value DOUBLE DEFAULT NULL
        COMMENT '死列：当前无任何写入路径（R006/D-R006-06，实测 67 行全 NULL）；保留仅为兼容旧 schema，禁止作为 IC 载体，需要 IC 请在研究侧重算';

-- 只读核实（迁移后执行）：
--   SELECT COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='selector_result'
--      AND COLUMN_NAME IN ('ic_value','ir_value','sharpe_contribution');
--   SELECT SUM(ic_value IS NOT NULL) FROM selector_result;   -- 必须仍为 0（本迁移不动数据）
--
-- 可选（本轮**不执行**，涉及改列注释的范围扩大）：ir_value / sharpe_contribution 同为 0/67 非空，
--   若一并声明需同批改动；本轮按条目范围只处理 ic_value。
