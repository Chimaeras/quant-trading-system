-- V34__stock_valuation_daily.sql
--
-- ── 为什么需要这张表 ────────────────────────────────────────────────────────
-- 打分体系验证闭环（阶段 1~6 + 数据层迭代第 1~8 轮）得出的核心结论是：
--   **现有 21 个因子全部被检验，合成 IC 稳定为负（t=−2.05~−7.83），
--     且可交易价差（Top10%−Bot10%）符号跨窗口翻转（W3/2026-07~08 毛价差 −4.61%）。**
--   ⟹ 结论是"需要重构因子集"（规则 52），而不是"继续调权重"。
--
-- 而"重构因子集"最直接的缺口是**估值维度**：
-- 现有 21 个因子全部来自价量（MOM/TREND/RSI/KDJ/VOL/MAXDD/LIQUIDITY）与
-- 少量财务比率（QUAL_ROE/QUAL_LEV/LIQ_CURRENT），**完全没有估值因子**。
--
-- ── 为什么之前判定为"数据阻塞"是错的 ─────────────────────────────────────
-- 第 2~8 轮我记录：`stock_financial_snapshot` 只有 13 个比率列、无绝对金额，
-- `stock_info.total_shares` 100% NULL，`market_data.pe_ratio/pb_ratio` 100% NULL
-- ⟹ 无法自算 TTM EP/BP ⟹ **数据阻塞**。
--
-- 第 9 轮更正：**这是误判**。
--   EP = 1 / PE_TTM      —— 上游直接给 PE_TTM
--   BP = 1 / PB_MRQ      —— 上游直接给 PB_MRQ
--   **根本不需要绝对金额。** 我只查了本地表，没问上游是否有这个量。
--   （教训：规则 65 —— "本地没有这个字段" ≠ "上游不可得"。）
--
-- 上游端点（第 9 轮实测，严格小批量）：
--   GET https://datacenter-web.eastmoney.com/api/data/v1/get
--       ?reportName=RPT_VALUEANALYSIS_DET
--       &pageSize=500&pageNumber=P&sortColumns=SECURITY_CODE&sortTypes=1
--       &filter=(TRADE_DATE='YYYY-MM-DD')
--   实测：HTTP 200；连续 5 次请求 0 失败；**按交易日 12 页 × 500 行 = 全市场**；
--         单只股票历史深度 533 页（约 2,665 个交易日）。
--
-- ── 为什么按交易日抓，而不是按标的 ─────────────────────────────────────────
--   按交易日：406 日 × 12 页  ≈ 4,900 次请求
--   按标的  ：5,500 只 × 533 页 ≈ 2,931,500 次请求
--   ⟹ 约 **600 倍**差距。（教训：规则 69。）
--   注：同一上游的 `push2his`（资金流）端点在第 9 轮被实测为**硬限流**
--   （重试 3 次恢复率 0/5，且先前成功的代码 10 分钟后也 3/3 失败），
--   与本端点的 `datacenter-web` 主机表现完全不同 —— **不同主机限流策略不同**。
--
-- ── 落库口径 ───────────────────────────────────────────────────────────────
--   范围：与 `factor_cross_section_score` 的截面日**逐日对齐**，
--         使估值因子能与现有 21 因子在完全相同的截面上比较 IC。
--   幂等：唯一键 (stock_code, trade_date)，抓取器用 ON DUPLICATE KEY UPDATE，
--         可断点续跑（状态文件 valuation_state.json）。
--   体量：实测 5,360~5,574 行/日（**比 market_data 还多 122 只**，
--         含当日停牌/未进入行情表的标的），16 列；
--         × 406 日 ≈ 227 万行，估算 < 500 MB，可长期保留（无需抽样）。
--
-- ── 数据合理性实测（2026-10-13 截面，5,574 只）────────────────────────────
--   PE_TTM / PB_MRQ / TOTAL_SHARES 非空率均 **100.0%**
--   EP = 1/PE_TTM : mean +0.00133  sd 0.14610  min −4.19111  max +3.08107
--   BP = 1/PB_MRQ : mean +0.46868  sd 0.50030  min −4.40341  max +19.91823
--   EP<0（亏损股）1,639 只 = **29.4%** —— 与 A 股亏损面相符，故 EP 均值≈0 合理；
--   BP<0 仅 54 只；|EP|>1（即 0<PE<1）仅 23 只 ⟹ **无异常堆积**。
--   覆盖对比：stock_valuation_daily 5,574 > market_data 5,452 > 打分池 5,237
--   ⟹ 与打分候选池对齐**无缺口**。
--
-- ── 只读实测（本迁移编写时，仅 SELECT）─────────────────────────────────────
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA='quant_db' AND TABLE_NAME='stock_valuation_daily';
--      -> 0（表不存在，本迁移新建）
--   SELECT MAX(version) FROM flyway_schema_history;   -- 33
--
-- ── 核实（迁移后执行，只读）───────────────────────────────────────────────
--   SELECT COUNT(*) FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_valuation_daily';   -- 17
--   SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)
--     FROM information_schema.STATISTICS
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='stock_valuation_daily'
--    GROUP BY INDEX_NAME;                               -- uk_val_code_date, idx_val_date
--
-- ── 回滚（注释保留）────────────────────────────────────────────────────────
--   DROP TABLE stock_valuation_daily;
--
-- ── FOLLOW-UP（本轮不做）───────────────────────────────────────────────────
--   1) 估值因子入库后需做**时间切分样本外检验**：单因子 IC + 分组单调性（规则 42）
--      + 对现有因子池的**增量**贡献 + **分段符号一致性**（规则 60，因为第 8 轮
--      已证明"秩 IC 稳定"不能外推为"价差稳定"）。
--   2) 两融/资金流若要入因子，需先扩表：
--      上游两融给 46 字段，而 `stock_margin_daily` 仅 1 个数据列 `margin_balance`
--      （规则 68：表结构会锁死可用信息量）。

CREATE TABLE IF NOT EXISTS stock_valuation_daily (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    stock_code            VARCHAR(20)   NOT NULL COMMENT '股票代码',
    trade_date            DATE          NOT NULL COMMENT '交易日',
    pe_ttm                DOUBLE        NULL COMMENT '市盈率 TTM（上游 PE_TTM）',
    pe_lar                DOUBLE        NULL COMMENT '市盈率 最新年报（上游 PE_LAR）',
    pb_mrq                DOUBLE        NULL COMMENT '市净率 最新报告期（上游 PB_MRQ）',
    ps_ttm                DOUBLE        NULL COMMENT '市销率 TTM（上游 PS_TTM）',
    pcf_ocf_ttm           DOUBLE        NULL COMMENT '市现率 经营现金流 TTM（上游 PCF_OCF_TTM）',
    total_market_cap      DECIMAL(22,4) NULL COMMENT '总市值（元）',
    notlimited_market_cap DECIMAL(22,4) NULL COMMENT '流通市值（元）',
    total_shares          DECIMAL(22,4) NULL COMMENT '总股本（股）—— 补 stock_info.total_shares 的 100% NULL 缺口',
    free_shares_a         DECIMAL(22,4) NULL COMMENT '流通A股（股）',
    close_price           DOUBLE        NULL COMMENT '收盘价（用于交叉核对行情表）',
    change_rate           DOUBLE        NULL COMMENT '涨跌幅（%）',
    ep_ttm                DOUBLE        NULL COMMENT '盈利收益率 = 1/pe_ttm，估值因子 VAL_EP 的原料',
    bp_mrq                DOUBLE        NULL COMMENT '账面市值比 = 1/pb_mrq，估值因子 VAL_BP 的原料',
    created_at            DATETIME(6)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_val_code_date (stock_code, trade_date),
    KEY idx_val_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='个股每日估值指标（上游 RPT_VALUEANALYSIS_DET），供估值因子 VAL_EP/VAL_BP 使用';
