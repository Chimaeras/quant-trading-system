-- 分钟级行情沉淀（D-STALE-BAR-01 修复 4）。
--
-- 背景：盘中盯盘的 5 分钟 bar 此前只存在于 Python 侧的 sqlite 缓存
-- （logs/watch/runtime.sqlite3 的 closed_bar 表）。那张表的职责是「同一交易日
-- 重启恢复」，不是历史库——它既不清理也没有日期边界，最终跨月陈旧 bar 混入
-- 策略预热序列，把 8 月的旧价与当日实时价拼成假连续序列，在均线断层处伪造出
-- 金叉买入（实测 601579 的 ma5 被算成 22.866，真实应为 34.32）。
--
-- 本表把盯盘产生的 5 分钟 bar 沉淀到主库，使「日线 market_data + 分钟线本表」
-- 成为可回测、可复现的本地数据资产，并让预热优先读本地连续分钟线、东方财富
-- 退化为兜底。
--
-- 幂等键选 (stock_code, period_min, start_ts)：同一根 bar 在盯盘重启/重跑时会被
-- 反复提交，必须 upsert 而非插入，否则撞唯一键抛 500。该键同时服务
-- 「按代码批量取最近 N 根」这一预热主查询。
CREATE TABLE IF NOT EXISTS market_data_minute (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    stock_code  VARCHAR(20)   NOT NULL,
    period_min  INT           NOT NULL,
    start_ts    DATETIME      NOT NULL,
    end_ts      DATETIME      NOT NULL,
    trade_date  DATE          NOT NULL,
    open_price  DECIMAL(18,4) NOT NULL,
    high_price  DECIMAL(18,4) NOT NULL,
    low_price   DECIMAL(18,4) NOT NULL,
    close_price DECIMAL(18,4) NOT NULL,
    volume      BIGINT        NOT NULL DEFAULT 0,
    source      VARCHAR(32)   NOT NULL,
    created_at  DATETIME      NOT NULL,
    updated_at  DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mdm_code_period_start (stock_code, period_min, start_ts),
    -- 预热查询按 (period_min, trade_date>=?) 限定窗口后再逐代码取尾部，
    -- 唯一键以 stock_code 为前导故用不上日期过滤，需要本索引收敛扫描范围。
    KEY idx_mdm_period_trade_date (period_min, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
