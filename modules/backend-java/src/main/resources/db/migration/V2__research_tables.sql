-- ============================================
-- Flyway V2: research / scoring / ops tables
-- Previously created only via Hibernate ddl-auto=update.
-- Column names follow Spring Boot camelCase -> snake_case naming
-- and explicit @Column / @Table on JPA entities.
-- ============================================

-- MarketPriceRaw
CREATE TABLE IF NOT EXISTS market_price_raw (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code      VARCHAR(20) NOT NULL,
    exchange_type   VARCHAR(20) NOT NULL,
    trade_date      DATE NOT NULL,
    open_price      DECIMAL(12,4) DEFAULT NULL,
    high_price      DECIMAL(12,4) DEFAULT NULL,
    low_price       DECIMAL(12,4) DEFAULT NULL,
    close_price     DECIMAL(12,4) DEFAULT NULL,
    pre_close       DECIMAL(12,4) DEFAULT NULL,
    volume          BIGINT DEFAULT NULL,
    amount          DECIMAL(20,4) DEFAULT NULL,
    turnover_rate   DECIMAL(8,4) DEFAULT NULL,
    source          VARCHAR(40) DEFAULT NULL,
    ingested_at     DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_mpr_code_date (stock_code, exchange_type, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不复权原始行情';

-- AdjustFactor
CREATE TABLE IF NOT EXISTS adjust_factor (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code      VARCHAR(20) NOT NULL,
    exchange_type   VARCHAR(20) NOT NULL,
    trade_date      DATE NOT NULL,
    factor          DECIMAL(18,8) NOT NULL,
    source          VARCHAR(40) NOT NULL,
    reliability     VARCHAR(20) DEFAULT NULL,
    ingested_at     DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_af_code_date (stock_code, exchange_type, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='复权因子';

-- SecurityMasterHistory
CREATE TABLE IF NOT EXISTS security_master_history (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code      VARCHAR(20) NOT NULL,
    exchange_type   VARCHAR(20) NOT NULL,
    stock_name      VARCHAR(50) DEFAULT NULL,
    industry        VARCHAR(50) DEFAULT NULL,
    list_date       DATE DEFAULT NULL,
    delist_date     DATE DEFAULT NULL,
    valid_from      DATE NOT NULL,
    valid_to        DATE DEFAULT NULL,
    source          VARCHAR(40) NOT NULL,
    reliability     VARCHAR(20) DEFAULT NULL,
    ingested_at     DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_smh_code_valid (stock_code, exchange_type, valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='历史证券主数据(PIT)';

-- TradingCalendarDay -> trading_calendar
CREATE TABLE IF NOT EXISTS trading_calendar (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date      DATE NOT NULL,
    is_trading_day  BIT(1) NOT NULL,
    source          VARCHAR(40) NOT NULL,
    reliability     VARCHAR(20) DEFAULT NULL,
    ingested_at     DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_tc_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易日历';

-- IndexQuote -> index_quote
CREATE TABLE IF NOT EXISTS index_quote (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    index_code      VARCHAR(20) NOT NULL,
    index_name      VARCHAR(50) DEFAULT NULL,
    trade_date      DATE NOT NULL,
    open_price      DECIMAL(38,2) DEFAULT NULL,
    close_price     DECIMAL(38,2) DEFAULT NULL,
    high_price      DECIMAL(38,2) DEFAULT NULL,
    low_price       DECIMAL(38,2) DEFAULT NULL,
    volume          BIGINT DEFAULT NULL,
    amount          DECIMAL(38,2) DEFAULT NULL,
    change_pct      DECIMAL(38,2) DEFAULT NULL,
    source          VARCHAR(40) NOT NULL,
    reliability     VARCHAR(20) DEFAULT NULL,
    ingested_at     DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_iq_code_date (index_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指数行情';

-- DataSnapshot
CREATE TABLE IF NOT EXISTS data_snapshot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id     VARCHAR(40) NOT NULL,
    cutoff_time     DATE NOT NULL,
    row_counts      TEXT DEFAULT NULL,
    source_versions TEXT DEFAULT NULL,
    quality_status  TEXT DEFAULT NULL,
    hash            VARCHAR(80) NOT NULL,
    created_by      VARCHAR(20) NOT NULL,
    created_at      DATETIME(6) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据快照注册表';

-- LabelSnapshot
CREATE TABLE IF NOT EXISTS label_snapshot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    label_version   VARCHAR(40) NOT NULL,
    signal_date     DATE NOT NULL,
    stock_code      VARCHAR(20) NOT NULL,
    exchange_type   VARCHAR(20) NOT NULL,
    horizon         INT NOT NULL,
    label_type      VARCHAR(20) NOT NULL,
    y_label         INT DEFAULT NULL,
    net_return      DECIMAL(38,2) DEFAULT NULL,
    entry_price     DECIMAL(38,2) DEFAULT NULL,
    exit_price      DECIMAL(38,2) DEFAULT NULL,
    entry_date      DATE DEFAULT NULL,
    exit_date       DATE DEFAULT NULL,
    max_favorable   DECIMAL(38,2) DEFAULT NULL,
    max_adverse     DECIMAL(38,2) DEFAULT NULL,
    exec_status     VARCHAR(20) NOT NULL,
    source          VARCHAR(40) NOT NULL,
    created_at      DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_ls_version_signal (label_version, signal_date, stock_code, exchange_type, horizon)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签快照';

-- ModelVersion -> model_registry
CREATE TABLE IF NOT EXISTS model_registry (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_version   VARCHAR(60) NOT NULL,
    algorithm       VARCHAR(40) NOT NULL,
    feature_version VARCHAR(40) DEFAULT NULL,
    label_version   VARCHAR(40) DEFAULT NULL,
    horizon         INT DEFAULT NULL,
    train_start     DATE NOT NULL,
    train_end       DATE NOT NULL,
    test_start      DATE DEFAULT NULL,
    test_end        DATE DEFAULT NULL,
    artifact        TEXT DEFAULT NULL,
    metrics         TEXT DEFAULT NULL,
    stage           VARCHAR(20) NOT NULL,
    created_at      DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_mr_model_version (model_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型注册表';

-- ExperimentRun
CREATE TABLE IF NOT EXISTS experiment_run (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    experiment_id   VARCHAR(60) NOT NULL,
    name            VARCHAR(200) DEFAULT NULL,
    config          TEXT DEFAULT NULL,
    git_commit      VARCHAR(40) DEFAULT NULL,
    seed            BIGINT DEFAULT NULL,
    snapshot_id     VARCHAR(40) DEFAULT NULL,
    label_version   VARCHAR(40) DEFAULT NULL,
    model_versions  VARCHAR(200) DEFAULT NULL,
    train_start     DATE NOT NULL,
    train_end       DATE NOT NULL,
    test_start      DATE DEFAULT NULL,
    test_end        DATE DEFAULT NULL,
    metrics         TEXT DEFAULT NULL,
    status          VARCHAR(20) NOT NULL,
    created_at      DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_er_experiment_id (experiment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实验运行注册表';

-- SelectorResult
CREATE TABLE IF NOT EXISTS selector_result (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    result_id               VARCHAR(32) NOT NULL,
    trade_date              DATE NOT NULL,
    lookback_days           INT NOT NULL,
    topn                    INT NOT NULL,
    min_avg_amount          DOUBLE NOT NULL,
    min_trend_score         DOUBLE NOT NULL,
    template_id             VARCHAR(64) DEFAULT NULL,
    factor_version          VARCHAR(32) DEFAULT NULL,
    strategy_type           VARCHAR(32) DEFAULT NULL,
    request_fingerprint     VARCHAR(64) DEFAULT NULL,
    run_status              VARCHAR(20) DEFAULT NULL,
    total_candidates        INT DEFAULT NULL,
    processed_candidates    INT DEFAULT NULL,
    success_candidates      INT DEFAULT NULL,
    failed_candidates       INT DEFAULT NULL,
    last_processed_key      VARCHAR(64) DEFAULT NULL,
    factor_snapshot         JSON DEFAULT NULL,
    ic_value                DOUBLE DEFAULT NULL,
    ir_value                DOUBLE DEFAULT NULL,
    sharpe_contribution     DOUBLE DEFAULT NULL,
    created_at              DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_sr_result_id (result_id),
    INDEX idx_selector_result_strategy_trade (strategy_type, trade_date),
    INDEX idx_selector_result_fingerprint (request_fingerprint),
    INDEX idx_selector_result_run_status (run_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选股结果';

-- SelectorResultItem
CREATE TABLE IF NOT EXISTS selector_result_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    result_id       VARCHAR(32) NOT NULL,
    rank_no         INT NOT NULL,
    strategy_type   VARCHAR(32) DEFAULT NULL,
    stock_code      VARCHAR(20) NOT NULL,
    exchange_type   VARCHAR(20) NOT NULL,
    stock_name      VARCHAR(100) DEFAULT NULL,
    final_score     DOUBLE NOT NULL,
    trend_score     DOUBLE NOT NULL,
    momentum_score  DOUBLE NOT NULL,
    volume_score    DOUBLE NOT NULL,
    risk_penalty    DOUBLE NOT NULL,
    ret20           DOUBLE NOT NULL,
    ret60           DOUBLE NOT NULL,
    volatility20    DOUBLE NOT NULL,
    max_drawdown60  DOUBLE NOT NULL,
    avg_amount20    DOUBLE NOT NULL,
    reasons         JSON DEFAULT NULL,
    extra_metrics   JSON DEFAULT NULL,
    UNIQUE KEY uk_selector_result_item_result_stock (result_id, stock_code, exchange_type),
    INDEX idx_selector_result_item_result_rank (result_id, rank_no),
    INDEX idx_selector_result_item_result_stock (result_id, stock_code, exchange_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选股结果明细';

-- SelectorTemplate
CREATE TABLE IF NOT EXISTS selector_template (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id     VARCHAR(64) NOT NULL,
    name            VARCHAR(100) NOT NULL,
    params          JSON DEFAULT NULL,
    description     VARCHAR(300) DEFAULT NULL,
    created_at      DATETIME(6) DEFAULT NULL,
    updated_at      DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_st_template_id (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选股模板';

-- T1Candidate
CREATE TABLE IF NOT EXISTS t1_candidate (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date      DATE NOT NULL,
    topn            INT NOT NULL,
    candidates_json LONGTEXT DEFAULT NULL,
    settled         BIT(1) NOT NULL,
    settled_json    LONGTEXT DEFAULT NULL,
    ecology_json    TEXT DEFAULT NULL,
    created_at      DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_t1_trade_date (trade_date),
    INDEX idx_t1_candidate_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='T+1候选清单';

-- BacktestRun (scoring backtest)
CREATE TABLE IF NOT EXISTS backtest_run (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    result_id           VARCHAR(40) NOT NULL,
    strategy_type       VARCHAR(40) NOT NULL,
    start_date          DATE NOT NULL,
    end_date            DATE NOT NULL,
    topn                INT NOT NULL,
    holding_days        TEXT DEFAULT NULL,
    commission_rate     DECIMAL(38,2) DEFAULT NULL,
    sell_tax_rate       DECIMAL(38,2) DEFAULT NULL,
    slippage_rate       DECIMAL(38,2) DEFAULT NULL,
    latest_data_date    DATE DEFAULT NULL,
    assumptions         TEXT DEFAULT NULL,
    summary             TEXT DEFAULT NULL,
    response            LONGTEXT DEFAULT NULL,
    created_at          DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_bt_run_result_id (result_id),
    INDEX idx_bt_run_strategy_date (strategy_type, start_date),
    INDEX idx_bt_run_result_id (result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打分回测运行记录';

-- StockTrendScore
CREATE TABLE IF NOT EXISTS stock_trend_score (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code                  VARCHAR(20) NOT NULL,
    exchange_type               VARCHAR(20) NOT NULL,
    stock_name                  VARCHAR(100) DEFAULT NULL,
    trade_date                  DATE NOT NULL,
    total_score                 DOUBLE NOT NULL,
    score_trend                 DOUBLE NOT NULL,
    score_bias                  DOUBLE NOT NULL,
    score_volume                DOUBLE NOT NULL,
    score_support               DOUBLE NOT NULL,
    score_macd                  DOUBLE NOT NULL,
    score_rsi                   DOUBLE NOT NULL,
    score_turnover              DOUBLE NOT NULL,
    score_delta                 DOUBLE NOT NULL,
    score_amplitude             DOUBLE NOT NULL,
    turnover_rate               DOUBLE DEFAULT NULL,
    consecutive_volume_days     INT DEFAULT NULL,
    trend_status                VARCHAR(32) NOT NULL,
    volume_status               VARCHAR(32) NOT NULL,
    macd_status                 VARCHAR(32) NOT NULL,
    rsi_status                  VARCHAR(32) NOT NULL,
    buy_signal                  VARCHAR(32) NOT NULL,
    bias_threshold              DOUBLE DEFAULT NULL,
    factor_version              VARCHAR(32) NOT NULL,
    reasons_json                TEXT DEFAULT NULL,
    created_at                  DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_sts_code_date (stock_code, exchange_type, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='趋势打分结果';

-- StockMultiDimScore
CREATE TABLE IF NOT EXISTS stock_multi_dim_score (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code              VARCHAR(20) NOT NULL,
    exchange_type           VARCHAR(20) NOT NULL,
    stock_name              VARCHAR(100) DEFAULT NULL,
    trade_date              DATE NOT NULL,
    factor_version          VARCHAR(32) NOT NULL,
    score_technical         DOUBLE DEFAULT NULL,
    score_fundamental       DOUBLE DEFAULT NULL,
    score_policy            DOUBLE DEFAULT NULL,
    score_rotation          DOUBLE DEFAULT NULL,
    score_industry_compare  DOUBLE DEFAULT NULL,
    score_sentiment         DOUBLE DEFAULT NULL,
    score_valuation         DOUBLE DEFAULT NULL,
    score_profitability     DOUBLE DEFAULT NULL,
    score_financial_health  DOUBLE DEFAULT NULL,
    composite_score         DOUBLE DEFAULT NULL,
    suggestion              VARCHAR(16) DEFAULT NULL,
    tech_macd               DOUBLE DEFAULT NULL,
    tech_rsi                DOUBLE DEFAULT NULL,
    tech_kdj                DOUBLE DEFAULT NULL,
    tech_volume             DOUBLE DEFAULT NULL,
    tech_ma                 DOUBLE DEFAULT NULL,
    macd_status             VARCHAR(32) DEFAULT NULL,
    rsi_status              VARCHAR(32) DEFAULT NULL,
    kdj_status              VARCHAR(32) DEFAULT NULL,
    volume_status           VARCHAR(32) DEFAULT NULL,
    ma_status               VARCHAR(32) DEFAULT NULL,
    reasons_json            VARCHAR(1000) DEFAULT NULL,
    created_at              DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_smds_code_date_ver (stock_code, exchange_type, trade_date, factor_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多维度打分结果';

-- ScreenerTemplate
CREATE TABLE IF NOT EXISTS screener_template (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    name                VARCHAR(100) NOT NULL,
    keyword             VARCHAR(50) DEFAULT NULL,
    exchange            VARCHAR(20) DEFAULT NULL,
    industry            VARCHAR(50) DEFAULT NULL,
    min_listing_days    INT DEFAULT NULL,
    exclude_st          BIT(1) DEFAULT NULL,
    created_at          DATETIME(6) DEFAULT NULL,
    INDEX idx_screener_tpl_user (user_id),
    UNIQUE KEY idx_screener_tpl_user_name (user_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='条件选股器模板';

-- WatchlistItem -> watchlist
CREATE TABLE IF NOT EXISTS watchlist (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    stock_code      VARCHAR(20) NOT NULL,
    exchange_type   VARCHAR(20) NOT NULL,
    stock_name      VARCHAR(50) DEFAULT NULL,
    note            VARCHAR(200) DEFAULT NULL,
    created_at      DATETIME(6) DEFAULT NULL,
    INDEX idx_watchlist_user (user_id),
    UNIQUE KEY idx_watchlist_user_stock (user_id, stock_code, exchange_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自选股池';

-- DataUpdateTask
CREATE TABLE IF NOT EXISTS data_update_task (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id             VARCHAR(32) NOT NULL,
    mode                VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    start_date          DATE NOT NULL,
    end_date            DATE NOT NULL,
    stock_list          LONGTEXT DEFAULT NULL,
    total_stocks        INT DEFAULT NULL,
    processed_stocks    INT DEFAULT NULL,
    success_stocks      INT DEFAULT NULL,
    failed_stocks       INT DEFAULT NULL,
    inserted_count      INT DEFAULT NULL,
    skipped_count       INT DEFAULT NULL,
    fetched_count       INT DEFAULT NULL,
    message             VARCHAR(500) DEFAULT NULL,
    created_at          DATETIME(6) DEFAULT NULL,
    updated_at          DATETIME(6) DEFAULT NULL,
    started_at          DATETIME(6) DEFAULT NULL,
    finished_at         DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_dut_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据更新任务';

-- DataUpdateTaskItem
CREATE TABLE IF NOT EXISTS data_update_task_item (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id             VARCHAR(32) NOT NULL,
    stock_code          VARCHAR(20) NOT NULL,
    exchange_type       VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    inserted_count      INT DEFAULT NULL,
    skipped_count       INT DEFAULT NULL,
    fetched_count       INT DEFAULT NULL,
    message             VARCHAR(500) DEFAULT NULL,
    created_at          DATETIME(6) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据更新任务明细';

-- UnifiedScoringTask
CREATE TABLE IF NOT EXISTS unified_scoring_task (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id                 VARCHAR(32) NOT NULL,
    strategy                VARCHAR(32) NOT NULL,
    status                  VARCHAR(20) NOT NULL,
    progress_pct            INT NOT NULL,
    total_items             INT DEFAULT NULL,
    returned_items          INT DEFAULT NULL,
    result_id               VARCHAR(32) DEFAULT NULL,
    request_fingerprint     VARCHAR(64) DEFAULT NULL,
    resume_from_result_id   VARCHAR(32) DEFAULT NULL,
    reused_result           BIT(1) DEFAULT NULL,
    message                 VARCHAR(500) DEFAULT NULL,
    error_message           VARCHAR(1000) DEFAULT NULL,
    request_json            LONGTEXT DEFAULT NULL,
    created_at              DATETIME(6) DEFAULT NULL,
    updated_at              DATETIME(6) DEFAULT NULL,
    started_at              DATETIME(6) DEFAULT NULL,
    finished_at             DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_ust_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一打分异步任务';

-- StockFinancialSnapshot
CREATE TABLE IF NOT EXISTS stock_financial_snapshot (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code          VARCHAR(20) NOT NULL,
    exchange_type       VARCHAR(20) NOT NULL,
    report_period       DATE NOT NULL,
    announcement_date   DATE DEFAULT NULL,
    revenue_yoy         DECIMAL(12,4) DEFAULT NULL,
    net_profit_yoy      DECIMAL(12,4) DEFAULT NULL,
    roe                 DECIMAL(12,4) DEFAULT NULL,
    debt_ratio          DECIMAL(12,4) DEFAULT NULL,
    pb_ratio            DECIMAL(12,4) DEFAULT NULL,
    current_ratio       DECIMAL(12,4) DEFAULT NULL,
    created_at          DATETIME(6) DEFAULT NULL,
    updated_at          DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_sfs_code_period (stock_code, exchange_type, report_period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='财报衍生指标快照';

-- StockMarginDaily
CREATE TABLE IF NOT EXISTS stock_margin_daily (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code      VARCHAR(20) NOT NULL,
    exchange_type   VARCHAR(20) NOT NULL,
    trade_date      DATE NOT NULL,
    margin_balance  DECIMAL(20,4) DEFAULT NULL,
    created_at      DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_smd_code_date (stock_code, exchange_type, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='融资余额日快照';
