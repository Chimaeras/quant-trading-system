-- V2-MP-015: 金融数值列精度修复
--
-- 原 DECIMAL(38,2) 会把 0.0003（佣金率）、0.55（概率阈值）、5.1234 等
-- 8 位小数的金融口径值截断为 2 位小数，导致：
--   * label_snapshot.net_return 等标签指标失真（净收益 0.0003 → 0.00）
--   * index_quote.change_pct 等涨跌幅失真
--   * backtest_run.commission_rate/sell_tax_rate/slippage_rate 费率失真（0.0003 → 0.00）
--
-- 修复原则：
--   * 价格类（open/close/high/low/entry/exit）→ DECIMAL(20,4)，足够容纳 A 股指数与个股价格
--   * 收益率/费率/涨跌幅类（net_return/max_favorable/max_adverse/change_pct/commission/tax/slippage）
--     → DECIMAL(20,8)，8 位小数覆盖到 0.00000001 级别的费率与净收益精度
--   * 成交额 amount（万元）→ DECIMAL(24,4)，指数日成交额最大约 10^8 万元，留足余量
--
-- 由窄变宽（scale 2→4/8）不会丢数据；precision 38→20/24 对真实金融数值（< 10^16）无影响。

ALTER TABLE index_quote
    MODIFY COLUMN open_price  DECIMAL(20,4) DEFAULT NULL,
    MODIFY COLUMN close_price DECIMAL(20,4) DEFAULT NULL,
    MODIFY COLUMN high_price  DECIMAL(20,4) DEFAULT NULL,
    MODIFY COLUMN low_price   DECIMAL(20,4) DEFAULT NULL,
    MODIFY COLUMN amount      DECIMAL(24,4) DEFAULT NULL,
    MODIFY COLUMN change_pct  DECIMAL(20,8) DEFAULT NULL;

ALTER TABLE label_snapshot
    MODIFY COLUMN net_return    DECIMAL(20,8) DEFAULT NULL,
    MODIFY COLUMN entry_price   DECIMAL(20,4) DEFAULT NULL,
    MODIFY COLUMN exit_price    DECIMAL(20,4) DEFAULT NULL,
    MODIFY COLUMN max_favorable DECIMAL(20,8) DEFAULT NULL,
    MODIFY COLUMN max_adverse   DECIMAL(20,8) DEFAULT NULL;

ALTER TABLE backtest_run
    MODIFY COLUMN commission_rate DECIMAL(20,8) DEFAULT NULL,
    MODIFY COLUMN sell_tax_rate   DECIMAL(20,8) DEFAULT NULL,
    MODIFY COLUMN slippage_rate   DECIMAL(20,8) DEFAULT NULL;
