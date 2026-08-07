-- ============================================
-- Flyway V1: core schema baseline
-- Adapted from modules/database/01_init_schema.sql
-- + indexes from 02_additional_indexes.sql
-- + market_data.total_market_value (JPA MarketData)
-- DB must already exist; connect via JDBC URL (no CREATE DATABASE / USE).
-- ============================================

-- 2.1 股票信息表 (stock_info)
CREATE TABLE IF NOT EXISTS stock_info (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    stock_code          VARCHAR(20) NOT NULL COMMENT '股票代码，如 000001',
    stock_name          VARCHAR(50) NOT NULL COMMENT '股票名称，如 平安银行',
    exchange_type       VARCHAR(20) NOT NULL COMMENT '交易所类型，如 SZSE/SSE/US/HK',
    exchange_type_name  VARCHAR(50) DEFAULT NULL COMMENT '交易所类型名称，如 深交所/上交所',
    industry            VARCHAR(50) DEFAULT NULL COMMENT '所属行业',
    sector              VARCHAR(50) DEFAULT NULL COMMENT '所属板块',
    list_date           DATE DEFAULT NULL COMMENT '上市日期',
    status              TINYINT DEFAULT 1 COMMENT '状态 1-正常 0-停牌 -1-退市',
    total_shares        DECIMAL(18,4) DEFAULT NULL COMMENT '总股本',
    circulating_shares  DECIMAL(18,4) DEFAULT NULL COMMENT '流通股本',
    created_at          DATETIME(6) DEFAULT NULL COMMENT '创建时间',
    updated_at          DATETIME(6) DEFAULT NULL COMMENT '更新时间',

    UNIQUE KEY uk_stock_code (stock_code, exchange_type),
    INDEX idx_exchange_type (exchange_type),
    INDEX idx_industry (industry),
    INDEX idx_status (status),
    INDEX idx_stock_info_list_date (list_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票信息表';

-- 2.2 行情数据表 (market_data)
CREATE TABLE IF NOT EXISTS market_data (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    stock_code          VARCHAR(20) NOT NULL COMMENT '股票代码',
    exchange_type       VARCHAR(20) NOT NULL COMMENT '交易所类型',
    trade_date          DATE NOT NULL COMMENT '交易日期',
    open_price          DECIMAL(12,4) DEFAULT NULL COMMENT '开盘价',
    high_price          DECIMAL(12,4) DEFAULT NULL COMMENT '最高价',
    low_price           DECIMAL(12,4) DEFAULT NULL COMMENT '最低价',
    close_price         DECIMAL(12,4) DEFAULT NULL COMMENT '收盘价',
    pre_close           DECIMAL(12,4) DEFAULT NULL COMMENT '前收盘价',
    change_pct          DECIMAL(8,4) DEFAULT NULL COMMENT '涨跌幅%',
    volume              BIGINT DEFAULT NULL COMMENT '成交量(股)',
    amount              DECIMAL(20,4) DEFAULT NULL COMMENT '成交额(元)',
    turnover_rate       DECIMAL(8,4) DEFAULT NULL COMMENT '换手率%',
    pe_ratio            DECIMAL(12,4) DEFAULT NULL COMMENT '市盈率',
    pb_ratio            DECIMAL(12,4) DEFAULT NULL COMMENT '市净率',
    total_market_value  DECIMAL(24,4) DEFAULT NULL COMMENT '总市值(元)',
    created_at          DATETIME(6) DEFAULT NULL COMMENT '创建时间',
    updated_at          DATETIME(6) DEFAULT NULL COMMENT '更新时间',

    UNIQUE KEY uk_code_date (stock_code, exchange_type, trade_date),
    INDEX idx_trade_date (trade_date),
    INDEX idx_stock_code (stock_code),
    INDEX idx_exchange_date (exchange_type, trade_date),
    INDEX idx_market_data_query (exchange_type, trade_date, stock_code),
    INDEX idx_market_data_stock_date (stock_code, exchange_type, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行情数据表';

-- 2.3 技术指标表 (technical_indicators)
CREATE TABLE IF NOT EXISTS technical_indicators (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    stock_code          VARCHAR(20) NOT NULL COMMENT '股票代码',
    exchange_type       VARCHAR(20) NOT NULL COMMENT '交易所类型',
    trade_date          DATE NOT NULL COMMENT '交易日期',

    ma5                 DECIMAL(12,4) DEFAULT NULL COMMENT '5日均线',
    ma10                DECIMAL(12,4) DEFAULT NULL COMMENT '10日均线',
    ma20                DECIMAL(12,4) DEFAULT NULL COMMENT '20日均线',
    ma30                DECIMAL(12,4) DEFAULT NULL COMMENT '30日均线',
    ma60                DECIMAL(12,4) DEFAULT NULL COMMENT '60日均线',
    ma120               DECIMAL(12,4) DEFAULT NULL COMMENT '120日均线',
    ma250               DECIMAL(12,4) DEFAULT NULL COMMENT '250日均线',
    ema5                DECIMAL(12,4) DEFAULT NULL COMMENT '5日指数均线',
    ema12               DECIMAL(12,4) DEFAULT NULL COMMENT '12日指数均线',
    ema26               DECIMAL(12,4) DEFAULT NULL COMMENT '26日指数均线',

    macd                DECIMAL(12,6) DEFAULT NULL COMMENT 'MACD值',
    macd_signal         DECIMAL(12,6) DEFAULT NULL COMMENT 'MACD信号线',
    macd_hist           DECIMAL(12,6) DEFAULT NULL COMMENT 'MACD柱状图',
    dmi_plus            DECIMAL(8,4) DEFAULT NULL COMMENT '+DI',
    dmi_minus           DECIMAL(8,4) DEFAULT NULL COMMENT '-DI',
    adx                 DECIMAL(8,4) DEFAULT NULL COMMENT 'ADX趋势强度',

    rsi6                DECIMAL(8,4) DEFAULT NULL COMMENT 'RSI(6)',
    rsi12               DECIMAL(8,4) DEFAULT NULL COMMENT 'RSI(12)',
    rsi14               DECIMAL(8,4) DEFAULT NULL COMMENT 'RSI(14)',
    rsi24               DECIMAL(8,4) DEFAULT NULL COMMENT 'RSI(24)',
    kdj_k               DECIMAL(8,4) DEFAULT NULL COMMENT 'KDJ-K',
    kdj_d               DECIMAL(8,4) DEFAULT NULL COMMENT 'KDJ-D',
    kdj_j               DECIMAL(8,4) DEFAULT NULL COMMENT 'KDJ-J',

    boll_upper          DECIMAL(12,4) DEFAULT NULL COMMENT '布林带上轨',
    boll_middle         DECIMAL(12,4) DEFAULT NULL COMMENT '布林带中轨',
    boll_lower          DECIMAL(12,4) DEFAULT NULL COMMENT '布林带下轨',
    atr14               DECIMAL(12,4) DEFAULT NULL COMMENT 'ATR(14)波动率',

    obv                 DECIMAL(20,0) DEFAULT NULL COMMENT 'OBV能量潮',
    vr                  DECIMAL(8,4) DEFAULT NULL COMMENT 'VR成交量比率',

    cci14               DECIMAL(12,4) DEFAULT NULL COMMENT 'CCI(14)',
    williams_r          DECIMAL(8,4) DEFAULT NULL COMMENT '威廉指标',
    roc                 DECIMAL(8,4) DEFAULT NULL COMMENT 'ROC变化率',

    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_code_date (stock_code, exchange_type, trade_date),
    INDEX idx_trade_date (trade_date),
    INDEX idx_stock_code (stock_code),
    INDEX idx_rsi14 (rsi14),
    INDEX idx_macd (macd),
    INDEX idx_tech_query (trade_date, stock_code, rsi14)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技术指标表';

-- 2.4 交易记录表 (trading_log)
CREATE TABLE IF NOT EXISTS trading_log (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    order_id            VARCHAR(32) NOT NULL UNIQUE COMMENT '订单编号',
    stock_code          VARCHAR(20) NOT NULL COMMENT '股票代码',
    stock_name          VARCHAR(50) DEFAULT NULL COMMENT '股票名称',
    exchange_type       VARCHAR(20) NOT NULL COMMENT '交易所类型',

    trade_type          TINYINT NOT NULL COMMENT '交易类型 1-买入 2-卖出',
    trade_price         DECIMAL(12,4) NOT NULL COMMENT '成交价格',
    trade_volume        INT NOT NULL COMMENT '成交数量',
    trade_amount        DECIMAL(20,4) NOT NULL COMMENT '成交金额',
    commission          DECIMAL(12,4) DEFAULT 0 COMMENT '手续费',
    stamp_duty          DECIMAL(12,4) DEFAULT 0 COMMENT '印花税',

    order_type          TINYINT DEFAULT 1 COMMENT '订单类型 1-限价 2-市价',
    order_price         DECIMAL(12,4) DEFAULT NULL COMMENT '委托价格',
    order_time          DATETIME(6) DEFAULT NULL COMMENT '下单时间',
    trade_time          DATETIME(6) DEFAULT NULL COMMENT '成交时间',
    order_status        TINYINT DEFAULT 1 COMMENT '订单状态 1-已提交 2-部分成交 3-已成交 4-已撤销 5-废单',

    strategy_id         BIGINT DEFAULT NULL COMMENT '关联策略ID(逻辑关联 strategy_info.id)',
    strategy_name       VARCHAR(50) DEFAULT NULL COMMENT '策略名称',
    signal_type         VARCHAR(20) DEFAULT NULL COMMENT '信号类型',

    position_before     INT DEFAULT 0 COMMENT '交易前持仓',
    position_after      INT DEFAULT 0 COMMENT '交易后持仓',
    avg_cost            DECIMAL(12,4) DEFAULT NULL COMMENT '持仓成本',

    profit_loss         DECIMAL(20,4) DEFAULT 0 COMMENT '盈亏金额',
    profit_rate         DECIMAL(8,4) DEFAULT 0 COMMENT '盈亏比例%',

    account_id          BIGINT DEFAULT NULL COMMENT '账户ID(逻辑关联 user_account.id)',
    user_id             BIGINT DEFAULT NULL COMMENT '用户ID(逻辑关联 user.id)',

    remark              VARCHAR(500) DEFAULT NULL COMMENT '备注',
    created_at          DATETIME(6) DEFAULT NULL COMMENT '创建时间',
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_stock_code (stock_code),
    INDEX idx_trade_time (trade_time),
    INDEX idx_order_status (order_status),
    INDEX idx_strategy_id (strategy_id),
    INDEX idx_user_id (user_id),
    INDEX idx_trade_time_range (trade_time, order_status),
    INDEX idx_strategy_trades (strategy_id, trade_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易记录表';

-- 2.5 策略信息表 (strategy_info)
CREATE TABLE IF NOT EXISTS strategy_info (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    strategy_code       VARCHAR(50) NOT NULL UNIQUE COMMENT '策略代码',
    strategy_name       VARCHAR(100) NOT NULL COMMENT '策略名称',
    strategy_type       TINYINT DEFAULT 1 COMMENT '策略类型 1-趋势 2-震荡 3-套利 4-其他',
    description         VARCHAR(500) DEFAULT NULL COMMENT '策略描述',

    params              JSON DEFAULT NULL COMMENT '策略参数配置(JSON格式存储)',

    exchange_list       JSON DEFAULT NULL COMMENT '适用交易所列表',
    stock_pool          JSON DEFAULT NULL COMMENT '股票池配置',
    time_frame          VARCHAR(20) DEFAULT '1d' COMMENT '时间周期 1d/1h/30m等',

    max_position        DECIMAL(8,4) DEFAULT 100 COMMENT '最大仓位%',
    stop_loss           DECIMAL(8,4) DEFAULT NULL COMMENT '止损%',
    take_profit         DECIMAL(8,4) DEFAULT NULL COMMENT '止盈%',
    max_drawdown        DECIMAL(8,4) DEFAULT NULL COMMENT '最大回撤%',

    status              TINYINT DEFAULT 0 COMMENT '状态 0-停用 1-运行中 2-暂停',
    run_mode            TINYINT DEFAULT 1 COMMENT '运行模式 1-回测 2-模拟 3-实盘',

    total_trades        INT DEFAULT 0 COMMENT '总交易次数',
    win_rate            DECIMAL(8,4) DEFAULT 0 COMMENT '胜率%',
    total_profit        DECIMAL(20,4) DEFAULT 0 COMMENT '总盈亏',

    last_run_time       DATETIME DEFAULT NULL COMMENT '最后运行时间',
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_status (status),
    INDEX idx_strategy_type (strategy_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略信息表';

-- 2.6 回测结果表 (backtest_result)
CREATE TABLE IF NOT EXISTS backtest_result (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    result_id           VARCHAR(32) NOT NULL UNIQUE COMMENT '回测结果ID',
    strategy_id         BIGINT NOT NULL COMMENT '策略ID(逻辑关联 strategy_info.id)',
    strategy_name       VARCHAR(100) DEFAULT NULL COMMENT '策略名称',

    start_date          DATE NOT NULL COMMENT '回测开始日期',
    end_date            DATE NOT NULL COMMENT '回测结束日期',
    initial_capital     DECIMAL(20,4) NOT NULL COMMENT '初始资金',
    stock_pool          JSON DEFAULT NULL COMMENT '股票池',
    params              JSON DEFAULT NULL COMMENT '回测参数',

    final_capital       DECIMAL(20,4) DEFAULT NULL COMMENT '最终资金',
    total_return        DECIMAL(8,4) DEFAULT NULL COMMENT '总收益率%',
    annual_return       DECIMAL(8,4) DEFAULT NULL COMMENT '年化收益率%',
    benchmark_return    DECIMAL(8,4) DEFAULT NULL COMMENT '基准收益率%',
    alpha               DECIMAL(8,4) DEFAULT NULL COMMENT 'Alpha',
    beta                DECIMAL(8,4) DEFAULT NULL COMMENT 'Beta',

    sharpe_ratio        DECIMAL(8,4) DEFAULT NULL COMMENT '夏普比率',
    sortino_ratio       DECIMAL(8,4) DEFAULT NULL COMMENT '索提诺比率',
    max_drawdown        DECIMAL(8,4) DEFAULT NULL COMMENT '最大回撤%',
    max_drawdown_period VARCHAR(50) DEFAULT NULL COMMENT '最大回撤区间',
    volatility          DECIMAL(8,4) DEFAULT NULL COMMENT '波动率%',

    total_trades        INT DEFAULT 0 COMMENT '总交易次数',
    winning_trades      INT DEFAULT 0 COMMENT '盈利次数',
    losing_trades       INT DEFAULT 0 COMMENT '亏损次数',
    win_rate            DECIMAL(8,4) DEFAULT 0 COMMENT '胜率%',
    avg_profit          DECIMAL(12,4) DEFAULT 0 COMMENT '平均盈利',
    avg_loss            DECIMAL(12,4) DEFAULT 0 COMMENT '平均亏损',
    profit_factor       DECIMAL(8,4) DEFAULT 0 COMMENT '盈亏比',

    avg_holding_days    DECIMAL(8,2) DEFAULT 0 COMMENT '平均持仓天数',
    max_consecutive_win INT DEFAULT 0 COMMENT '最大连续盈利',
    max_consecutive_loss INT DEFAULT 0 COMMENT '最大连续亏损',

    status              TINYINT DEFAULT 1 COMMENT '状态 1-成功 0-失败',
    error_message       VARCHAR(500) DEFAULT NULL COMMENT '错误信息',
    run_time            INT DEFAULT 0 COMMENT '运行时间(秒)',

    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    INDEX idx_strategy_id (strategy_id),
    INDEX idx_start_date (start_date),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测结果表';

-- 2.7 用户表 (user)
CREATE TABLE IF NOT EXISTS `user` (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    username            VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password            VARCHAR(255) NOT NULL COMMENT '密码(加密存储)',
    email               VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    phone               VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    status              TINYINT DEFAULT 1 COMMENT '状态 1-正常 0-禁用',
    create_time         DATETIME(6) DEFAULT NULL COMMENT '创建时间',
    update_time         DATETIME(6) DEFAULT NULL COMMENT '更新时间',

    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2.8 用户账户表 (user_account)
CREATE TABLE IF NOT EXISTS user_account (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id             BIGINT NOT NULL COMMENT '用户ID(逻辑关联 user.id)',
    account_name        VARCHAR(50) DEFAULT NULL COMMENT '账户名称',
    account_type        TINYINT DEFAULT 1 COMMENT '账户类型 1-模拟 2-实盘',

    total_asset         DECIMAL(20,4) DEFAULT 0 COMMENT '总资产',
    available_cash      DECIMAL(20,4) DEFAULT 0 COMMENT '可用资金',
    frozen_cash         DECIMAL(20,4) DEFAULT 0 COMMENT '冻结资金',
    position_value      DECIMAL(20,4) DEFAULT 0 COMMENT '持仓市值',

    total_profit        DECIMAL(20,4) DEFAULT 0 COMMENT '累计盈亏',
    today_profit        DECIMAL(20,4) DEFAULT 0 COMMENT '今日盈亏',
    today_return        DECIMAL(8,4) DEFAULT 0 COMMENT '今日收益率%',
    total_return        DECIMAL(8,4) DEFAULT 0 COMMENT '总收益率%',

    status              TINYINT DEFAULT 1 COMMENT '状态 1-正常 0-禁用',
    broker              VARCHAR(50) DEFAULT NULL COMMENT '券商名称',
    account_no          VARCHAR(50) DEFAULT NULL COMMENT '资金账号',

    created_at          DATETIME(6) DEFAULT NULL COMMENT '创建时间',
    updated_at          DATETIME(6) DEFAULT NULL COMMENT '更新时间',

    UNIQUE KEY uk_user_account (user_id, account_name),
    INDEX idx_user_id (user_id),
    INDEX idx_account_type (account_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户账户表';

-- 2.9 持仓信息表 (position_info)
CREATE TABLE IF NOT EXISTS position_info (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    account_id          BIGINT NOT NULL COMMENT '账户ID(逻辑关联 user_account.id)',
    stock_code          VARCHAR(20) NOT NULL COMMENT '股票代码',
    stock_name          VARCHAR(50) DEFAULT NULL COMMENT '股票名称',
    exchange_type       VARCHAR(20) NOT NULL COMMENT '交易所类型',

    volume              INT DEFAULT 0 COMMENT '持仓数量',
    available_volume    INT DEFAULT 0 COMMENT '可用数量',
    frozen_volume       INT DEFAULT 0 COMMENT '冻结数量',
    avg_cost            DECIMAL(12,4) DEFAULT 0 COMMENT '持仓成本',
    current_price       DECIMAL(12,4) DEFAULT 0 COMMENT '当前价格',
    market_value        DECIMAL(20,4) DEFAULT 0 COMMENT '持仓市值',

    profit_loss         DECIMAL(20,4) DEFAULT 0 COMMENT '浮动盈亏',
    profit_rate         DECIMAL(8,4) DEFAULT 0 COMMENT '盈亏比例%',
    today_profit        DECIMAL(20,4) DEFAULT 0 COMMENT '今日盈亏',

    first_buy_date      DATE DEFAULT NULL COMMENT '首次买入日期',
    last_trade_date     DATETIME DEFAULT NULL COMMENT '最后交易日期',

    created_at          DATETIME(6) DEFAULT NULL COMMENT '创建时间',
    updated_at          DATETIME(6) DEFAULT NULL COMMENT '更新时间',

    UNIQUE KEY uk_account_stock (account_id, stock_code, exchange_type),
    INDEX idx_account_id (account_id),
    INDEX idx_stock_code (stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓信息表';
