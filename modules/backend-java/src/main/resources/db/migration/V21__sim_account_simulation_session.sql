-- V21：V2-MP-021 模拟账户历史时钟 / T+1 / 费用语义
--
-- 目标：
--   1) 引入 sim_session 历史演练会话，与在线账户（session_id=0 哨兵）资金/持仓隔离；
--   2) 引入 position_lot 逐批买入记录，T+1 以 acquisition_trade_date < 目标交易日 判定，
--      不再依赖 LocalDate.now() 与当天日志，不再用单一 first_buy_date 表示多批持仓；
--   3) trading_log 增加 session_id / price_source，历史订单不混入在线账户。

-- 1) 演练会话表（在线账户不建行，使用 session_id=0 哨兵）
CREATE TABLE IF NOT EXISTS sim_session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '会话ID',
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    account_id      BIGINT NOT NULL COMMENT '所属账户ID',
    session_name    VARCHAR(80) DEFAULT NULL COMMENT '会话名称',
    mode            VARCHAR(20) NOT NULL DEFAULT 'HISTORICAL' COMMENT 'ONLINE-在线 / HISTORICAL-历史演练',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE-进行中 / CLOSED-已关闭',
    base_cash       DECIMAL(20,4) NOT NULL DEFAULT 0 COMMENT '期初资金快照',
    available_cash  DECIMAL(20,4) NOT NULL DEFAULT 0 COMMENT '当前可用资金',
    position_value  DECIMAL(20,4) NOT NULL DEFAULT 0 COMMENT '持仓市值（按会话 as-of 交易日标记）',
    total_profit    DECIMAL(20,4) NOT NULL DEFAULT 0 COMMENT '累计盈亏',
    start_date      DATE NOT NULL COMMENT '演练起始交易日',
    end_date        DATE DEFAULT NULL COMMENT '演练结束交易日（CLOSED 时）',
    created_at      DATETIME(6) DEFAULT NULL,
    updated_at      DATETIME(6) DEFAULT NULL,
    INDEX idx_session_user (user_id),
    INDEX idx_session_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟账户仿真会话（历史演练与在线账户隔离）';

-- 2) position_info / trading_log 增加 session_id 哨兵（0=在线账户）
ALTER TABLE position_info
    ADD COLUMN session_id BIGINT NOT NULL DEFAULT 0 COMMENT '仿真会话ID(0=在线账户)' AFTER account_id;
ALTER TABLE position_info
    DROP INDEX uk_account_stock,
    ADD UNIQUE KEY uk_position_scope (account_id, session_id, stock_code, exchange_type);

ALTER TABLE trading_log
    ADD COLUMN session_id BIGINT NOT NULL DEFAULT 0 COMMENT '仿真会话ID(0=在线账户)' AFTER account_id;
ALTER TABLE trading_log
    ADD COLUMN price_source VARCHAR(30) DEFAULT NULL COMMENT '成交价来源(CLIENT_SPECIFIED/market_price_raw)' AFTER stamp_duty;

-- 3) position_lot：逐批买入记录（T+1 与摊薄成本按批次，卖出按 acquisition_trade_date FIFO 消耗）
CREATE TABLE IF NOT EXISTS position_lot (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '批次ID',
    account_id              BIGINT NOT NULL COMMENT '账户ID',
    session_id              BIGINT NOT NULL DEFAULT 0 COMMENT '仿真会话ID(0=在线账户)',
    stock_code              VARCHAR(20) NOT NULL COMMENT '股票代码',
    stock_name              VARCHAR(50) DEFAULT NULL,
    exchange_type           VARCHAR(20) NOT NULL,
    order_id                VARCHAR(32) NOT NULL COMMENT '关联 trading_log.order_id',
    acquisition_trade_date  DATE NOT NULL COMMENT '建仓仿真交易日',
    volume                  INT NOT NULL COMMENT '原始买入数量',
    remaining_volume        INT NOT NULL COMMENT '未卖出数量',
    cost_price              DECIMAL(12,4) NOT NULL COMMENT '含滑点成交价',
    price_source            VARCHAR(30) DEFAULT NULL COMMENT '成交价来源',
    created_at              DATETIME(6) DEFAULT NULL,
    updated_at              DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_lot_order (session_id, order_id),
    INDEX idx_lot_stock (account_id, session_id, stock_code, exchange_type),
    INDEX idx_lot_acquisition (session_id, acquisition_trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓批次（买入逐批记录，T+1 与成本按批次）';
