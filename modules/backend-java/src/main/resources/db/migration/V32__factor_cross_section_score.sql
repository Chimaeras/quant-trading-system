-- V32__factor_cross_section_score.sql
--
-- ── 为什么需要这张表：打分体系缺"验证通路"，IC 在结构上算不出来 ────────────────
--
-- 背景（2026-10-14 只读普查所得）：
--   仓库**已有**完整的因子研究引擎，但从未产出过有意义的 IC：
--     * `FactorResearchEngine` 提供 Rank IC / ICIR / Newey-West HAC 标准误 /
--       置换检验 / block bootstrap，口径声明完备（总体标准差÷T 与样本标准差÷(T−1) 并存）；
--     * `FactorResearchService.research(strategy, start, end, holdingDays)` 已有入口；
--     * 但 `factor_analysis` 仅 4 个截面日，其中 90.7% 观测来自同一天。
--
--   根因（**实测修正**，此前"只落 Top-N"的判断不准确，在此更正）：
--     `FactorResearchService.buildRankIc` 从 `selector_result_item` 读分数。
--     实测该表**有时含全截面、有时只有头部**——取决于走哪条代码路径（2026-10-14 只读实测）：
--       * 旧单策略路径 `StockSelectorService` 经 `replaceResultItems` 落 `candidates` 全量，
--         注释明写「写入通过质量闸的**全截面**候选…供打分回测 Rank IC 使用」；
--         实测存在 `topn=20` 但 `items=627` 的行，印证"展示 topN / 落库全截面"的原设计。
--       * **统一引擎路径 `UnifiedScoreReadService.projectStrategy` 末尾按 topN 切片**，
--         故统一路径**丢了全截面**，这是重构引入的**能力退化**。
--     但更硬的限制是**覆盖度**：`selector_result` 一共只有 **18 个不同 trade_date**、
--     82 个 result_id，且 `total_candidates` 约 5,800 而 `items` 最多 627。
--     ⟹ 无论哪条路径，都**凑不出一年 238 个截面日**，IC 无法覆盖回测所需区间。
--
--   实测印证（2026-10-14，238 个信号日 × 5 策略 × Top20 = 23,800 条信号的独立回测）：
--     T+1 超额 +0.17~+0.48pp（NW t 2.03~3.58），扣 30bp 往返成本后 3/5 策略转负；
--     持有到 T+5 起全部跑输基准。**头部信息不足以支撑验证，必须拿到全截面。**
--
-- ── 本表的作用 ──────────────────────────────────────────────────────────────
--   在信号日 t 落**全截面**（当日通过 L1 特征装载、参与 L2 标准化的所有候选），
--   使 `FactorResearchEngine.spearmanRankCorrelation` 能对全截面计算 Rank IC。
--
--   注意：本表**只存分数**，不存原始行情。前向收益由研究侧从 `market_data` 取，
--   与本表 JOIN 后即可算 IC（`FactorResearchService` 现有逻辑即此模式）。
--
-- ── 与既有表的关系（为什么不复用 selector_result_item）───────────────────────
--   `selector_result_item` 被前端 5 个 tab 直接消费，其行数即"展示条数"，
--   语义是**产物**而非**研究样本**。把全截面塞进去会：
--     (1) 让前端读到几千行（语义污染）；
--     (2) 与 V26 的 rank_no 重复诊断、V24 声明的唯一约束暂缓纠缠；
--     (3) 与"统一路径只落 topN"的既有语义冲突——同一张表两种行数语义，
--         下游按 result_id 取数时无法判断拿到的是全截面还是头部。
--   独立建表可让"行数"始终等于"样本数"，无需额外判别。
--   故新建独立研究表，落库默认**关闭**（见 service 侧 includeCrossSection 参数），
--   只在研究运行时开启，避免日常打分产生大量写入。
--
-- ── 体量估算（已实测，非估计）──────────────────────────────────────────────
--   单日候选约 5,500（2026-02-25 后；此前约 3,600）
--   单日 × 5 策略 ≈ 27,500 行；一年 238 日 × 5 策略 ≈ 654 万行量级。
--   ⟹ 默认关闭 + 按需补历史，是刻意的设计取舍。
--
-- ── 只读实测（本迁移编写时，仅 SELECT）─────────────────────────────────────
--   SELECT COUNT(*) FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA='quant_db' AND TABLE_NAME='factor_cross_section_score';
--      -> 0（表不存在，本迁移新建）
--   全库空表 30 张，其中 calibrator_registry / prediction_run / model_registry /
--   portfolio_backtest_run / feature_snapshot_row / label_snapshot 均为 0 行，
--   即"能力存在但从未使用"——本表是打通该链路的第一个载体。
--
-- ── 核实（迁移后执行，只读）───────────────────────────────────────────────
--   SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_TYPE FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='factor_cross_section_score'
--    ORDER BY ORDINAL_POSITION;
--   SELECT COUNT(*) FROM factor_cross_section_score;   -- 本迁移不动数据，必为 0
--
-- ── 回滚（注释保留）────────────────────────────────────────────────────────
--   DROP TABLE IF EXISTS factor_cross_section_score;
--
-- ── FOLLOW-UP（本轮不做，单独授权后实施）──────────────────────────────────
--   1) 逐因子全截面百分位表 `factor_cross_section_value`
--      （factor_id + percentile，21 因子 × 全截面 ≈ 单日 11.5 万行/策略）。
--      单因子 IC 诊断需要它；本轮先打通**合成分数**的 IC，再按需扩表。
--   2) 历史补算：一年 × 5 策略 ≈ 654 万行，需按日批量重跑（本表默认关闭）。
--   3) 落库量治理：评估按月分区或只保留最近 N 个月。

CREATE TABLE factor_cross_section_score (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    trade_date        DATE         NOT NULL COMMENT '信号日 t（只用 <= t 的 bar 计算）',
    factor_version    VARCHAR(64)  NOT NULL COMMENT '策略因子版本，如 unified_factor_classic_v1',
    strategy_code     VARCHAR(32)  NOT NULL COMMENT '统一引擎策略 code，如 CLASSIC_SELECTOR',
    legacy_strategy_type VARCHAR(32) NOT NULL COMMENT '旧口径策略名，与 selector_result.strategy_type 对齐',
    stock_code        VARCHAR(16)  NOT NULL COMMENT '冗余存储，使本表可独立查询（不依赖 item 表存活）',
    exchange_type     VARCHAR(16)  DEFAULT NULL,
    stock_name        VARCHAR(64)  DEFAULT NULL,
    raw_score         DOUBLE       DEFAULT NULL COMMENT '合成原始分（L2 横截面归一后的加权和，未做百分位）',
    final_score       DOUBLE       DEFAULT NULL COMMENT '最终分数（raw_score 在合格集内的百分位 0-100）',
    cross_section_rank INT         DEFAULT NULL COMMENT '在**全截面合格集**内的名次，1 起',
    available_weight_ratio DOUBLE  DEFAULT NULL COMMENT '可用因子权重占比，衡量数据完整度',
    eligible          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否通过资格闸（闸门不通过者分数为 NULL）',
    reject_reason     VARCHAR(128) DEFAULT NULL COMMENT '未通过闸门的原因',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 研究主查询：按日 + 策略取全截面
    KEY idx_css_lookup (trade_date, strategy_code),
    KEY idx_css_version (factor_version, trade_date),
    -- 幂等：同一 (日, 策略, 股票) 只应有一条；重跑时按此键替换
    UNIQUE KEY uk_css_day_strategy_stock (trade_date, strategy_code, stock_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='统一引擎全截面分数（研究用，落库默认关闭）。Rank IC 需要全截面，selector_result_item 只有 Top-N，故独立建表';
