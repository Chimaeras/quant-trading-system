-- ============================================
-- 高性能查询索引补充
-- 执行时机: 在 01_init_schema.sql 之后执行
-- ============================================

USE quant_db;

-- 1. 行情数据复合索引（常用查询组合）
-- 若索引已存在会报错，可忽略
CREATE INDEX idx_market_data_query ON market_data(exchange_type, trade_date, stock_code);

-- 2. 技术指标查询索引
CREATE INDEX idx_tech_query ON technical_indicators(trade_date, stock_code, rsi14);

-- 3. 交易记录时间范围查询
CREATE INDEX idx_trade_time_range ON trading_log(trade_time, order_status);

-- 4. 策略交易统计
CREATE INDEX idx_strategy_trades ON trading_log(strategy_id, trade_time);
