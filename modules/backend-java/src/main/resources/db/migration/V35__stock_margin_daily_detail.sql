-- V35__stock_margin_daily_detail.sql
--
-- ── 为什么需要这张表 ────────────────────────────────────────────────────────
-- 目标(5) 要求补「资金流/两融」数据。第 9 轮实测结论是：
--   **两融上游完全可达（45 字段），但本地表装不下。**
--     `stock_margin_daily`     只有 6 列，唯一数据列 `margin_balance`（1 个字段）
--     `stock_money_flow_cache` 只有 6 列，唯一数据列 `main_net_inflow5d_yi`（1 个）
--   ⟹ 上游 45 字段 vs 本地 1 字段。**"把表填满"与"获得可用因子"是两件事。**
--   （教训：规则 68 —— 表结构会锁死可用信息量。）
--
-- 本迁移按上游真实字段扩表，使两融数据**可用于因子构建**。
--
-- ── 为什么不 ALTER 现有 stock_margin_daily ─────────────────────────────────
-- 1) 该表被 `DataController#marginBulk` 生产接口写入，ALTER 会同时影响生产路径；
-- 2) 其语义是"极简余额快照"，与"完整两融明细"是两种用途；
-- 3) 新建明细表可**零风险并存**，现有表保持不动（回滚只需 DROP 本表）。
--
-- ── 上游端点与实测（第 10 轮，严格遵守小批量口径）─────────────────────────
--   GET https://datacenter-web.eastmoney.com/api/data/v1/get
--       ?reportName=RPTA_WEB_RZRQ_GGMX
--       &columns=ALL&pageSize=500&pageNumber=P
--       &filter=(DATE='YYYY-MM-DD')
--
--   实测（本迁移编写时）：
--     · 按日期取全市场：**9 页 × 500 行 = 全部两融标的**（约 4,000~4,500 只/日）
--     · 5 只探针 5/5 成功；60 只限流耐受测 **HTTP 层 0 失败**，
--       平均 0.21s/请求（最快 0.09s，最慢 0.49s），**首只失败位置 = 无**
--     · 单只全历史 1,334 页；600000 自 2024-01-02 起 672 个交易日
--
--   ⚠️ **必须区分"限流"与"无数据"**：60 只里 16 只返回空结果，
--   那是**该股不是两融标的**（小盘股未纳入），不是端点失败。
--   把两者混为一谈会**把数据覆盖问题误读成限流问题**（本轮我自己先犯了这个错）。
--
--   ⚠️ **同主机家族≠同限流策略**：同一 Eastmoney 生态下，
--   `push2his.eastmoney.com`（.221，资金流）第 9 轮实测**硬限流**（重试 3 次恢复率 0/5），
--   而 `datacenter-web.eastmoney.com`（.19，两融/估值）实测**完全无限流**。
--   两者是**不同 IP**（已用 DNS 核实），必须分别评估。
--
-- ── 为什么按日期抓，而不是按标的 ───────────────────────────────────────────
--   按股票：5,500 只 × 224 页 ≈ 1,232,000 次请求
--   按日期：406 日 × ~8 页   ≈     3,248 次请求   ⟹ 约 **379 倍**差距
--   （教训：规则 69 —— 当接口支持按日期分页时优先按截面日取数。）
--
-- ── 字段量纲（均为上游原始口径）────────────────────────────────────────────
--   金额类（RZYE/RQYE/RZRQYE/RZMRE/RZCHE/RZJME/SZ 等）：**元**
--     （已核对：600000 于 2026-10-13 的 RZYE=3,348,387,945 元 ≈ 33.5 亿元，
--      其总市值 SZ 亦为元；与本项目 `market_data.amount`（万元）**不同量纲**，
--       跨表比较前必须换算。）
--   股数类（RQYL/RQMCL/RQCHL/RQJMG）：**股**
--   百分比类（RZYEZB/FIN_BALANCE_GR/RCHANGE*DCP）：**%**（已 ×100，如 1.7445 表示 1.74%）
--   价格类（SPJ）：**元**；涨跌幅（ZDF）：**%**
--
-- ── 关键因子原料：RZYEZB 已是"归一化"口径 ─────────────────────────────────
--   `RZYEZB` = 融资余额 / 流通市值 × 100
--   ⟹ **开箱即用**，无需绝对金额即可做市场中性 IC
--      （这正是目标(5) 需要的量纲；第 9 轮"缺绝对金额"的顾虑在此不成立。）
--
-- ── 只读实测（本迁移编写时，仅 SELECT）─────────────────────────────────────
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA='quant_db' AND TABLE_NAME='stock_margin_daily_detail';
--      -> 0（表不存在，本迁移新建）
--   SELECT COUNT(*) FROM stock_margin_daily;      -- 0（本地从未抓取，仅有推送接口）
--
-- ── 核实（迁移后执行，只读）───────────────────────────────────────────────
--   SELECT COUNT(*) FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_margin_daily_detail';
--      -- 43（实测：本脚本解析出 43，运行库亦 43，且两侧列名集合完全一致，无漂移）
--      -- ⚠️ 本迁移初稿在此写的是「44」，那是**我自己的计数错误**，不是 schema 漂移。
--      --    已用「解析 SQL 列名集合 vs information_schema 列名集合」双向差集核实：
--      --    V35 有而运行库无 = 空；运行库有而 V35 无 = 空。
--   SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)
--     FROM information_schema.STATISTICS
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_margin_daily_detail'
--    GROUP BY INDEX_NAME;              -- uk_margin_code_date, idx_margin_date
--
-- ── 回滚（注释保留）────────────────────────────────────────────────────────
--   DROP TABLE stock_margin_daily_detail;   -- 不影响 stock_margin_daily
--
-- ── FOLLOW-UP（本轮不做）───────────────────────────────────────────────────
--   1) 两融因子需做**时间切分样本外检验**（单因子 IC + 分组单调性 + 对现有
--      因子池的增量 + 分段符号一致性，规则 42/60）。
--   2) 注意交易制度切换（2026-02-27 池规模跳变）：任何跨该日的 IC 必须**分段报**。
--   3) 资金流（`push2his`）仍受硬限流阻塞，需换源或换端点。

CREATE TABLE IF NOT EXISTS stock_margin_daily_detail (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    stock_code          VARCHAR(20)   NOT NULL COMMENT '股票代码（上游 SCODE）',
    trade_date          DATE          NOT NULL COMMENT '交易日（上游 DATE）',
    trade_market        VARCHAR(32)   NULL COMMENT '交易板块（上游 TRADE_MARKET，如 上交所主板）',
    market              VARCHAR(32)   NULL COMMENT '两融市场（上游 MARKET，如 融资融券_沪证）',

    -- 余额（元）
    rz_balance          DECIMAL(22,4) NULL COMMENT '融资余额 RZYE（元）',
    rq_balance          DECIMAL(22,4) NULL COMMENT '融券余额 RQYE（元）',
    rzrq_balance        DECIMAL(22,4) NULL COMMENT '融资融券余额 RZRQYE（元）',
    rzrq_balance_net    DECIMAL(22,4) NULL COMMENT '融资融券余额差值 RZRQYECZ（元）',
    total_market_cap    DECIMAL(22,4) NULL COMMENT '总市值 SZ（元）—— 补 stock_info.total_shares 缺口',

    -- 融资交易（元）
    rz_buy              DECIMAL(22,4) NULL COMMENT '融资买入额 RZMRE（元）',
    rz_repay            DECIMAL(22,4) NULL COMMENT '融资偿还额 RZCHE（元）',
    rz_net              DECIMAL(22,4) NULL COMMENT '融资净买入 RZJME（元）',

    -- 融券交易（股）
    rq_sell_vol         DECIMAL(22,4) NULL COMMENT '融券卖出量 RQMCL（股）',
    rq_balance_vol      DECIMAL(22,4) NULL COMMENT '融券余量 RQYL（股）',
    rq_repay_vol        DECIMAL(22,4) NULL COMMENT '融券偿还量 RQCHL（股）',
    rq_net_sell_vol     DECIMAL(22,4) NULL COMMENT '融券净卖出 RQJMG（股）',

    -- 多日累计（上游直接给，避免自算口径不一致）
    rz_buy_3d           DECIMAL(22,4) NULL COMMENT '3日融资买入额 RZMRE3D（元）',
    rz_buy_5d           DECIMAL(22,4) NULL COMMENT '5日融资买入额 RZMRE5D（元）',
    rz_buy_10d          DECIMAL(22,4) NULL COMMENT '10日融资买入额 RZMRE10D（元）',
    rz_repay_3d         DECIMAL(22,4) NULL COMMENT '3日融资偿还额 RZCHE3D（元）',
    rz_repay_5d         DECIMAL(22,4) NULL COMMENT '5日融资偿还额 RZCHE5D（元）',
    rz_repay_10d        DECIMAL(22,4) NULL COMMENT '10日融资偿还额 RZCHE10D（元）',
    rz_net_3d           DECIMAL(22,4) NULL COMMENT '3日融资净买入 RZJME3D（元）',
    rz_net_5d           DECIMAL(22,4) NULL COMMENT '5日融资净买入 RZJME5D（元）',
    rz_net_10d          DECIMAL(22,4) NULL COMMENT '10日融资净买入 RZJME10D（元）',
    rq_sell_vol_3d      DECIMAL(22,4) NULL COMMENT '3日融券卖出量 RQMCL3D（股）',
    rq_sell_vol_5d      DECIMAL(22,4) NULL COMMENT '5日融券卖出量 RQMCL5D（股）',
    rq_sell_vol_10d     DECIMAL(22,4) NULL COMMENT '10日融券卖出量 RQMCL10D（股）',
    rq_repay_vol_3d     DECIMAL(22,4) NULL COMMENT '3日融券偿还量 RQCHL3D（股）',
    rq_repay_vol_5d     DECIMAL(22,4) NULL COMMENT '5日融券偿还量 RQCHL5D（股）',
    rq_repay_vol_10d    DECIMAL(22,4) NULL COMMENT '10日融券偿还量 RQCHL10D（股）',
    rq_net_sell_3d      DECIMAL(22,4) NULL COMMENT '3日融券净卖出 RQJMG3D（股）',
    rq_net_sell_5d      DECIMAL(22,4) NULL COMMENT '5日融券净卖出 RQJMG5D（股）',
    rq_net_sell_10d     DECIMAL(22,4) NULL COMMENT '10日融券净卖出 RQJMG10D（股）',

    -- 归一化（因子直接可用）
    rz_balance_pct      DOUBLE        NULL COMMENT '融资余额/流通市值 RZYEZB（%）—— 开箱即用，无需绝对金额',
    fin_balance_gr      DOUBLE        NULL COMMENT '融资余额增长率 FIN_BALANCE_GR（%）',
    rz_change_3d_pct    DOUBLE        NULL COMMENT '3日融资余额变化 RCHANGE3DCP（%）',
    rz_change_5d_pct    DOUBLE        NULL COMMENT '5日融资余额变化 RCHANGE5DCP（%）',
    rz_change_10d_pct   DOUBLE        NULL COMMENT '10日融资余额变化 RCHANGE10DCP（%）',

    -- 行情交叉核对
    close_price         DOUBLE        NULL COMMENT '收盘价 SPJ（元），用于与 market_data 交叉核对',
    change_pct          DOUBLE        NULL COMMENT '涨跌幅 ZDF（%）',

    created_at          DATETIME(6)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_margin_code_date (stock_code, trade_date),
    KEY idx_margin_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='个股每日两融明细（上游 RPTA_WEB_RZRQ_GGMX，45 字段），供两融因子构建';
