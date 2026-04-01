-- 量化交易系统数据库初始化脚本
-- MySQL 8.0+

CREATE DATABASE IF NOT EXISTS quant_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE quant_db;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `username` VARCHAR(50) NOT NULL UNIQUE,
    `password` VARCHAR(255) NOT NULL,
    `email` VARCHAR(100),
    `phone` VARCHAR(20),
    `status` TINYINT DEFAULT 1 COMMENT '1:正常 0:禁用',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 账户表
CREATE TABLE IF NOT EXISTS `account` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `broker` VARCHAR(50) COMMENT '券商',
    `account_no` VARCHAR(50) COMMENT '资金账号',
    `total_asset` DECIMAL(20,4) DEFAULT 0,
    `cash` DECIMAL(20,4) DEFAULT 0,
    `frozen` DECIMAL(20,4) DEFAULT 0,
    `position_value` DECIMAL(20,4) DEFAULT 0,
    `today_pnl` DECIMAL(20,4) DEFAULT 0,
    `total_pnl` DECIMAL(20,4) DEFAULT 0,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`),
    INDEX idx_user_id (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户表';

-- 持仓表
CREATE TABLE IF NOT EXISTS `position` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `account_id` BIGINT NOT NULL,
    `symbol` VARCHAR(20) NOT NULL,
    `name` VARCHAR(50),
    `volume` INT DEFAULT 0,
    `avg_price` DECIMAL(20,4),
    `current_price` DECIMAL(20,4),
    `market_value` DECIMAL(20,4),
    `pnl` DECIMAL(20,4),
    `pnl_ratio` DECIMAL(10,4),
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_account_symbol (`account_id`, `symbol`),
    INDEX idx_account_id (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓表';

-- 订单表
CREATE TABLE IF NOT EXISTS `order` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `order_id` VARCHAR(50) NOT NULL UNIQUE,
    `account_id` BIGINT NOT NULL,
    `symbol` VARCHAR(20) NOT NULL,
    `action` VARCHAR(10) NOT NULL COMMENT 'buy/sell',
    `type` VARCHAR(20) DEFAULT 'limit' COMMENT 'limit/market',
    `volume` INT NOT NULL,
    `price` DECIMAL(20,4),
    `amount` DECIMAL(20,4),
    `status` VARCHAR(20) DEFAULT 'pending' COMMENT 'pending/submitted/filled/cancelled',
    `strategy_id` BIGINT,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_account_id (`account_id`),
    INDEX idx_order_id (`order_id`),
    INDEX idx_create_time (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 策略表
CREATE TABLE IF NOT EXISTS `strategy` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `name` VARCHAR(100) NOT NULL,
    `type` VARCHAR(50) COMMENT 'MA_Cross/BOLL/MACD等',
    `params` JSON COMMENT '策略参数',
    `status` VARCHAR(20) DEFAULT 'stopped' COMMENT 'running/stopped',
    `symbol` VARCHAR(20),
    `pnl` DECIMAL(20,4) DEFAULT 0,
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略表';

-- 回测记录表
CREATE TABLE IF NOT EXISTS `backtest_record` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `strategy_id` BIGINT,
    `strategy_name` VARCHAR(100),
    `symbol` VARCHAR(20),
    `start_date` DATE,
    `end_date` DATE,
    `initial_cash` DECIMAL(20,4),
    `final_equity` DECIMAL(20,4),
    `total_return` VARCHAR(20),
    `sharpe_ratio` DECIMAL(10,4),
    `max_drawdown` VARCHAR(20),
    `total_trades` INT,
    `win_rate` DECIMAL(10,4),
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测记录表';

-- 默认管理员由应用启动时 DataInitializer 创建 (admin / 123456)
