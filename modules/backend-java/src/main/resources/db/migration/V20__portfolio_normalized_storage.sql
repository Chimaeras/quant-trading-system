-- V2-MP-020: 组合回测规范化存储 —— 订单/NAV/持仓落子表，去除单行 LONGTEXT nav_json/orders_json。
-- 说明：V9 阶段未产生生产级长区间数据，存量 nav_json/orders_json 直接随列删除、不做 JSON 回迁；
-- 若确需保留历史，需在升级前手工导出。summary 仍存 summary_json（含资金守恒审计/拒绝漏斗/lineage）。
ALTER TABLE portfolio_backtest_run
    ADD COLUMN track             VARCHAR(32)  DEFAULT NULL AFTER strategy_type,
    ADD COLUMN prediction_run_id VARCHAR(40)  DEFAULT NULL AFTER track,
    ADD COLUMN top_n             INT          DEFAULT NULL AFTER max_positions,
    ADD COLUMN progress          INT          DEFAULT 0 AFTER status,
    ADD COLUMN error_message     VARCHAR(500) DEFAULT NULL AFTER progress,
    ADD COLUMN updated_at        DATETIME(6)  DEFAULT NULL AFTER created_at;

ALTER TABLE portfolio_backtest_run
    DROP COLUMN nav_json,
    DROP COLUMN orders_json;

CREATE TABLE IF NOT EXISTS portfolio_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id        VARCHAR(40)  NOT NULL,
    seq           INT          NOT NULL,
    action        VARCHAR(8)   NOT NULL,
    trade_date    DATE         NOT NULL,
    stock_code    VARCHAR(20)  NOT NULL,
    exchange_type VARCHAR(20)  NOT NULL,
    stock_name    VARCHAR(100) DEFAULT NULL,
    status        VARCHAR(16)  NOT NULL,
    reject_reason VARCHAR(64)  DEFAULT NULL,
    price         DOUBLE       DEFAULT NULL,
    shares        INT          DEFAULT NULL,
    amount        DOUBLE       DEFAULT NULL,
    gross_amount  DOUBLE       DEFAULT NULL,
    commission    DOUBLE       DEFAULT NULL,
    stamp_tax     DOUBLE       DEFAULT NULL,
    price_source  VARCHAR(32)  DEFAULT NULL,
    signal_date   DATE         DEFAULT NULL,
    exit_date     DATE         DEFAULT NULL,
    event_time    VARCHAR(8)   DEFAULT NULL,
    UNIQUE KEY uk_portfolio_order_run_seq (run_id, seq),
    INDEX idx_portfolio_order_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组合回测订单明细（含拒单漏斗）';

CREATE TABLE IF NOT EXISTS portfolio_nav_point (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id         VARCHAR(40) NOT NULL,
    trade_date     DATE        NOT NULL,
    cash           DOUBLE      DEFAULT NULL,
    position_value DOUBLE      DEFAULT NULL,
    nav            DOUBLE      DEFAULT NULL,
    positions      INT         DEFAULT NULL,
    UNIQUE KEY uk_portfolio_nav_run_date (run_id, trade_date),
    INDEX idx_portfolio_nav_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组合回测 NAV 曲线（逐交易日）';

CREATE TABLE IF NOT EXISTS portfolio_position (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id         VARCHAR(40) NOT NULL,
    stock_code     VARCHAR(20) NOT NULL,
    exchange_type  VARCHAR(20) NOT NULL,
    stock_name     VARCHAR(100) DEFAULT NULL,
    shares         INT         DEFAULT NULL,
    entry_price    DOUBLE      DEFAULT NULL,
    mark_price     DOUBLE      DEFAULT NULL,
    entry_date     DATE        DEFAULT NULL,
    exit_date      DATE        DEFAULT NULL,
    final_status   VARCHAR(32) DEFAULT NULL,
    unrealized_pnl DOUBLE      DEFAULT NULL,
    price_source   VARCHAR(32) DEFAULT NULL,
    UNIQUE KEY uk_portfolio_position_run_stock (run_id, stock_code, exchange_type),
    INDEX idx_portfolio_position_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组合回测期末持仓（显式终态）';
