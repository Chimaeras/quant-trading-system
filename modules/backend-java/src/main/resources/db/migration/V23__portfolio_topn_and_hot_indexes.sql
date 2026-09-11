-- R33-P1-1 / R33-P2-9
--
-- 1) 修正 portfolio_backtest_run 的 topN 列名不一致。
--    V20 用 `ADD COLUMN top_n`，但实体 PortfolioBacktestRun.topN 在 Hibernate 6
--    CamelCaseToUnderscoresNamingStrategy 下映射为 `topn`（该策略仅在「小写-大写-小写」
--    处插入下划线，`topN` 不满足），于是 ddl-auto: validate 在干净库上报
--    missing column [topn] 而无法启动。同仓 backtest_run / selector_result /
--    t1_candidate 三处 topN 字段在 V2 里都是 `topn`，此处对齐同一命名。
--    注意：对外 JSON key 仍为 `top_n`（各 Service 的 response map），不受影响。
--
--    用 information_schema 分支而不是裸 CHANGE COLUMN：早期由 Hibernate 建过表的库
--    同时存在 `topn`（实体在用）与 `top_n`（V20 后加、无人读写），裸重命名会撞
--    Duplicate column name。四种列组合都收敛到「只有 topn」。
--
-- 2) 四张热表的查询路径补索引（此前全表扫描）。

SET @has_top_n := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'portfolio_backtest_run'
      AND COLUMN_NAME = 'top_n'
);
SET @has_topn := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'portfolio_backtest_run'
      AND COLUMN_NAME = 'topn'
);
SET @ddl := CASE
    -- 干净库（Flyway 从 V1 建表）：只有 V20 加的 top_n，改名为实体期望的 topn
    WHEN @has_top_n > 0 AND @has_topn = 0
        THEN 'ALTER TABLE portfolio_backtest_run CHANGE COLUMN top_n topn INT DEFAULT NULL'
    -- Hibernate 先建过表的库：topn 已在用，top_n 是 V20 留下的无主冗余列，删除
    WHEN @has_top_n > 0 AND @has_topn > 0
        THEN 'ALTER TABLE portfolio_backtest_run DROP COLUMN top_n'
    -- 已修正过：幂等空操作
    WHEN @has_topn > 0
        THEN 'SELECT 1'
    -- 都没有（异常形态）：补上实体期望的列
    ELSE 'ALTER TABLE portfolio_backtest_run ADD COLUMN topn INT DEFAULT NULL AFTER max_positions'
END;
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 标签工厂逐交易日调用 findByTradeDate；uk_mpr_code_date 以 stock_code 为前导
-- 前缀，trade_date 单独过滤用不上该索引 → 每个交易日全表扫描。
ALTER TABLE market_price_raw
    ADD INDEX idx_mpr_trade_date (trade_date);

-- 分页查询按 (trade_date, factor_version) 过滤、composite_score 倒序；
-- 唯一键以 stock_code 为前导故过滤列走不了索引，且每页 filesort。
ALTER TABLE stock_multi_dim_score
    ADD INDEX idx_smds_date_version_score (trade_date, factor_version, composite_score);

-- 该表此前除主键外零索引：明细分页（按 id 排序）与失败列表（按 status 过滤）全表扫描。
ALTER TABLE data_update_task_item
    ADD INDEX idx_duti_task_status (task_id, status);

-- 订单历史 findTop50ByAccountIdAndSessionIdOrderByTradeTimeDesc：
-- 过滤 account_id + session_id、按 trade_time 倒序，LIMIT 50 无法提前终止；
-- trading_log 现有 7 个索引均不含 account_id（session_id 由 V21 新增后也未补索引）。
ALTER TABLE trading_log
    ADD INDEX idx_tlog_account_session_time (account_id, session_id, trade_time);
