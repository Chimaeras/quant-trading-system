-- ============================================
-- Flyway V4: 存量库索引名收养（V2-MP-024 安全接管）
-- ============================================
-- 问题：历史存量库（Hibernate ddl-auto:update 建表）把 stock_trend_score 上
--   (stock_code, exchange_type, trade_date) 唯一索引命名为
--   `UK9mbimcvnq42ouxu7rcwwq62pf`，而 V5 依赖 V2 定义的 `uk_sts_code_date`
--   做 DROP INDEX。若直接对存量库跑 V5，会报 1091「Can't DROP uk_sts_code_date」。
--
-- 方案：在 V2（建表）之后、V5（DROP 该索引）之前，用条件化收养脚本把
--   旧名唯一索引改名为 `uk_sts_code_date`。
--   - 全新库（V2 已建 uk_sts_code_date）：本脚本为 no-op。
--   - 存量库（只有 Hibernate 旧名索引）：改名后 V5 可正常执行。
--   - 幂等：以「目标索引是否存在」为判定，重复执行无副作用。
--
-- 注意：MySQL 不支持 DROP INDEX IF EXISTS / RENAME INDEX IF EXISTS，
--   故用 information_schema 探测 + 动态 SQL 实现条件化。
-- 判定逻辑：某表上「目标唯一索引不存在但存在同列集合的唯一索引」时，把该旧名索引改名为目标名。
-- 覆盖两张表：
--   * stock_trend_score.(stock_code, exchange_type, trade_date) → uk_sts_code_date
--   * model_registry.model_version → uk_mr_model_version（存量库 Hibernate 命名为 UKoy7yh4mnwgy4t3vw4etqm2spv）
--     （无迁移引用后者，仅为两库形态 index 名收敛）
-- ============================================

-- stock_trend_score: 收养 uk_sts_code_date（V5 依赖该名做 DROP INDEX）
SET @old_idx := NULL;
SELECT index_name INTO @old_idx
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'stock_trend_score'
  AND index_name <> 'uk_sts_code_date'
  AND non_unique = 0
GROUP BY index_name
HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'stock_code,exchange_type,trade_date'
LIMIT 1;

SET @has_target := (SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = 'stock_trend_score'
                      AND index_name = 'uk_sts_code_date');

SET @sql := IF(@has_target > 0 OR @old_idx IS NULL,
               'SELECT 1',
               CONCAT('ALTER TABLE stock_trend_score RENAME INDEX `', @old_idx, '` TO `uk_sts_code_date`'));

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- model_registry: 收养 uk_mr_model_version（仅 index 名收敛，无迁移按名引用）
SET @old_idx_mr := NULL;
SELECT index_name INTO @old_idx_mr
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'model_registry'
  AND index_name <> 'uk_mr_model_version'
  AND non_unique = 0
GROUP BY index_name
HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'model_version'
LIMIT 1;

SET @has_target_mr := (SELECT COUNT(*) FROM information_schema.statistics
                       WHERE table_schema = DATABASE() AND table_name = 'model_registry'
                         AND index_name = 'uk_mr_model_version');

SET @sql := IF(@has_target_mr > 0 OR @old_idx_mr IS NULL,
               'SELECT 1',
               CONCAT('ALTER TABLE model_registry RENAME INDEX `', @old_idx_mr, '` TO `uk_mr_model_version`'));

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
