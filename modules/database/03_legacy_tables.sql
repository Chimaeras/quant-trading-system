-- ============================================
-- 兼容现有 Spring Boot 应用的表结构
-- 若已使用 01_init_schema.sql 中的 user/user_account/position_info，
-- 可根据需要选择是否执行本脚本补充 account/position/order 等表
-- ============================================

USE quant_db;

-- 账户表（简化版，与 user_account 二选一或并存）
CREATE TABLE IF NOT EXISTS `account` (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id             BIGINT NOT NULL COMMENT '用户ID(逻辑关联 user.id)',
    broker              VARCHAR(50) DEFAULT NULL COMMENT '券商名称',
    account_no          VARCHAR(50) DEFAULT NULL COMMENT '资金账号',
    total_asset         DECIMAL(20,4) DEFAULT 0 COMMENT '总资产',
    cash                DECIMAL(20,4) DEFAULT 0 COMMENT '可用资金',
    frozen              DECIMAL(20,4) DEFAULT 0 COMMENT '冻结资金',
    position_value      DECIMAL(20,4) DEFAULT 0 COMMENT '持仓市值',
    today_pnl           DECIMAL(20,4) DEFAULT 0 COMMENT '今日盈亏',
    total_pnl           DECIMAL(20,4) DEFAULT 0 COMMENT '累计盈亏',
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户表(兼容旧版)';

-- 持仓表（简化版）
CREATE TABLE IF NOT EXISTS `position` (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    account_id          BIGINT NOT NULL COMMENT '账户ID(逻辑关联 account.id)',
    symbol              VARCHAR(20) NOT NULL COMMENT '股票代码',
    name                VARCHAR(50) DEFAULT NULL COMMENT '股票名称',
    volume              INT DEFAULT 0 COMMENT '持仓数量',
    avg_price           DECIMAL(20,4) DEFAULT NULL COMMENT '持仓成本',
    current_price       DECIMAL(20,4) DEFAULT NULL COMMENT '当前价格',
    market_value        DECIMAL(20,4) DEFAULT NULL COMMENT '持仓市值',
    pnl                 DECIMAL(20,4) DEFAULT NULL COMMENT '浮动盈亏',
    pnl_ratio           DECIMAL(10,4) DEFAULT NULL COMMENT '盈亏比例%',
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    UNIQUE KEY uk_account_symbol (account_id, symbol),
    INDEX idx_account_id (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓表(兼容旧版)';

-- 订单表
CREATE TABLE IF NOT EXISTS `order` (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    order_id            VARCHAR(50) NOT NULL UNIQUE COMMENT '订单编号',
    account_id          BIGINT NOT NULL COMMENT '账户ID(逻辑关联 account.id)',
    symbol              VARCHAR(20) NOT NULL COMMENT '股票代码',
    action              VARCHAR(10) NOT NULL COMMENT '买卖方向 buy/sell',
    type                VARCHAR(20) DEFAULT 'limit' COMMENT '订单类型 limit/market',
    volume              INT NOT NULL COMMENT '委托数量',
    price               DECIMAL(20,4) DEFAULT NULL COMMENT '委托价格',
    amount              DECIMAL(20,4) DEFAULT NULL COMMENT '成交金额',
    status              VARCHAR(20) DEFAULT 'pending' COMMENT '订单状态',
    strategy_id         BIGINT DEFAULT NULL COMMENT '策略ID',
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_account_id (account_id),
    INDEX idx_order_id (order_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 策略表（简化版）
CREATE TABLE IF NOT EXISTS `strategy` (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    name                VARCHAR(100) NOT NULL COMMENT '策略名称',
    type                VARCHAR(50) DEFAULT NULL COMMENT '策略类型',
    params              JSON DEFAULT NULL COMMENT '策略参数',
    status              VARCHAR(20) DEFAULT 'stopped' COMMENT '状态',
    symbol              VARCHAR(20) DEFAULT NULL COMMENT '标的代码',
    pnl                 DECIMAL(20,4) DEFAULT 0 COMMENT '累计盈亏',
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略表(兼容旧版)';

-- 回测记录表（简化版）
CREATE TABLE IF NOT EXISTS backtest_record (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    strategy_id         BIGINT DEFAULT NULL COMMENT '策略ID',
    strategy_name       VARCHAR(100) DEFAULT NULL COMMENT '策略名称',
    symbol              VARCHAR(20) DEFAULT NULL COMMENT '标的代码',
    start_date          DATE DEFAULT NULL COMMENT '开始日期',
    end_date            DATE DEFAULT NULL COMMENT '结束日期',
    initial_cash        DECIMAL(20,4) DEFAULT NULL COMMENT '初始资金',
    final_equity        DECIMAL(20,4) DEFAULT NULL COMMENT '最终权益',
    total_return        VARCHAR(20) DEFAULT NULL COMMENT '总收益率',
    sharpe_ratio        DECIMAL(10,4) DEFAULT NULL COMMENT '夏普比率',
    max_drawdown        VARCHAR(20) DEFAULT NULL COMMENT '最大回撤',
    total_trades        INT DEFAULT NULL COMMENT '交易次数',
    win_rate            DECIMAL(10,4) DEFAULT NULL COMMENT '胜率',
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测记录表(兼容旧版)';
