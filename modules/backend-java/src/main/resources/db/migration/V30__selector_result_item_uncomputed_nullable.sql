-- V30__selector_result_item_uncomputed_nullable.sql
-- 裁定来源：V25__selector_result_item_avg_amount20_nullable.sql:62-65 的 FOLLOW-UP 第 1、2 条
--   「1) ret20 / ret60 / volatility20 / max_drawdown60 四列与 avg_amount20 同病（同一批 2204 行同 0），
--      且同列 NOT NULL 使写入方无法表达「未计算」；是否一并放开 + 回填须单独裁定（逐列各自的「整块」谓词不同）。
--    2) 写入方（TREND / MULTI_DIM / COMPREHENSIVE）应改为写 NULL 而非 0，属生产代码改，须单独 commit + 测试。」
-- 本迁移处理第 1 条（列定义放开 + 证据化回填），第 2 条由同批代码改动完成。
--
-- ── 第一步：0 的分布与「真·孤立 0」核实（V25 要求的先决条件，逐列独立核实）──────
-- 只读实测（本地 quant_db，2026-10-14，仅 SELECT；此时 avg_amount20 已由 V25 放开为 NULL）：
--
--   总行数 = 5972
--
--   (a) 「整块未计算」判据（四列同时为 0 ⟹ 写入方根本没算这批特征）：
--       SELECT COUNT(*) FROM selector_result_item
--        WHERE ret20=0 AND ret60=0 AND volatility20=0 AND max_drawdown60=0;          -> 2733
--       按策略分布：
--       SELECT r.strategy_type, COUNT(*) FROM selector_result_item i JOIN selector_result r ON r.result_id=i.result_id
--        WHERE i.ret20=0 AND i.ret60=0 AND i.volatility20=0 AND i.max_drawdown60=0
--        GROUP BY r.strategy_type;      -> TREND 2550 / MULTI_DIM 153 / COMPREHENSIVE 30
--       另有 3 个旧 run 的 strategy_type 为 NULL（旧 oversold 数据），其 max_drawdown60 另见 (c)。
--
--   (b) ret20 的「真·孤立 0」核实（**存在，必须排除**）：
--       SELECT COUNT(*) FROM selector_result_item
--        WHERE ret20=0 AND (ret60<>0 OR volatility20<>0 OR max_drawdown60<>0);        -> 4
--       4 行明细（全部 CLASSIC_SELECTOR）：
--         2026-07-07 688165  ret20=0 ret60=27.47 vol20=5.15 mdd60=19.00（该 run 重复 2 行）
--         2026-10-13 002589  ret20=0 ret60=23.02 vol20=4.23 mdd60=20.75
--         2026-10-13 000839  ret20=0 ret60=18.91 vol20=3.60 mdd60=14.97
--       用 market_data 复算（(close[t]/close[t-20]-1)*100）：
--         688165 -> 0.0000%   002589 -> 0.0000%   000839 -> 0.0000%
--       ⟹ **这 4 行的 0 是真实测量值**（个股恰好回到 20 个交易日前的价格），
--          故 ret20 的回填谓词**必须**是「整块」（含 ret60/volatility20/max_drawdown60 同为 0），
--          **不得**简化为 `WHERE ret20 = 0`。
--       ret60 / volatility20 的孤立 0 数量：
--         SELECT COUNT(*) ... WHERE ret60=0       AND (ret20<>0 OR volatility20<>0 OR max_drawdown60<>0); -> 0
--         SELECT COUNT(*) ... WHERE volatility20=0 AND (ret20<>0 OR ret60<>0 OR max_drawdown60<>0);      -> 0
--
--   (c) max_drawdown60 的「真·孤立 0」核实（**不存在，可整列回填**）：
--       SELECT COUNT(*) FROM selector_result_item
--        WHERE max_drawdown60=0 AND (ret20<>0 OR ret60<>0 OR volatility20<>0);       -> 120
--       分布：OVERSOLD_REBOUND 90 / strategy_type IS NULL 30（全部为旧 oversold 数据）；
--             两组的 ret20 全部 < 0（下跌中），且 ret60 全部非 0。
--       用 market_data 复算 60 根 bar 的最大回撤（抽样 5 行，全部 strategy_type IS NULL，2026-03-23）：
--         000004 -> 60.6918%   003010 -> 28.0284%   600570 -> 28.7219%
--         600599 -> 61.9091%   600476 -> 40.8853%
--       ⟹ 复算真值 28%~62%，stored 全部为 0.0 ⟹ **这 120 行的 0 是「未计算」，不是真实回撤**。
--          代码依据：OversoldReboundScoringService.java:503 `item.setMaxDrawdown60(0.0);` 为显式常量写入。
--          另：OVERSOLD_REBOUND 的 90 行 max_drawdown60 = 0 占该策略 90/90（100%），无一行非 0。
--       ⟹ max_drawdown60 可安全整列回填；但为保守起见，本迁移仍显式列出谓词，
--          并在下面的「排除条件」中保留 `max_drawdown60=0 AND 其他非 0` 这一组仅限 oversold 类的写法。
--
--   结论：ret20 / ret60 / volatility20 三列的回填谓词 = 「四列整块同 0」（排除 4 行真实 ret20=0）；
--         max_drawdown60 的 0 全部为未计算，但本迁移仍只回填 (a) 的整块行 + (c) 的 120 行，
--         不做无条件整列回填，以保留「真实回撤恰为 0」的将来可能性。
--
-- ── 影响行数核对（执行前 / 执行后各跑一次，只读）──────────────────────────────
--   SELECT SUM(ret20=0) z, SUM(ret20 IS NULL) n FROM selector_result_item;          -- 前 2737 / 0     后 4 / 2733
--   SELECT SUM(ret60=0) z, SUM(ret60 IS NULL) n FROM selector_result_item;          -- 前 2733 / 0     后 0 / 2733
--   SELECT SUM(volatility20=0) z, SUM(volatility20 IS NULL) n FROM selector_result_item; -- 前 2733 / 0  后 0 / 2733
--   SELECT SUM(max_drawdown60=0) z, SUM(max_drawdown60 IS NULL) n FROM selector_result_item; -- 前 2853 / 0 后 0 / 2853
--   -- 必须恒为 0（4 行真实 ret20=0 未被误改）：
--   SELECT COUNT(*) FROM selector_result_item WHERE ret20 IS NULL AND ret60 IS NOT NULL;  -- 期望 0
--   -- 保留校验：真实 ret20=0 的行仍在
--   SELECT COUNT(*) FROM selector_result_item WHERE ret20 = 0;                            -- 期望 4
--
-- ── 回滚（注释保留）──────────────────────────────────────────────────────────
--   ALTER TABLE selector_result_item
--       MODIFY COLUMN ret20            DOUBLE NOT NULL,
--       MODIFY COLUMN ret60            DOUBLE NOT NULL,
--       MODIFY COLUMN volatility20     DOUBLE NOT NULL,
--       MODIFY COLUMN max_drawdown60   DOUBLE NOT NULL;
--   （仅在任何 NULL 写入之前有效；已有 NULL 时必须先回填 0 才能恢复 NOT NULL）

-- ── 第二步：放开 NOT NULL（使写入方能表达「未计算」）──────────────────────────
ALTER TABLE selector_result_item
    MODIFY COLUMN ret20 DOUBLE DEFAULT NULL
        COMMENT '20日涨跌幅(%)；NULL=不可用/未计算（V30 起）；0 可为真实值（个股恰好回到20日前价格，本库实测有 4 行）',
    MODIFY COLUMN ret60 DOUBLE DEFAULT NULL
        COMMENT '60日涨跌幅(%)；NULL=不可用/未计算（V30 起）；本库实测无孤立 0',
    MODIFY COLUMN volatility20 DOUBLE DEFAULT NULL
        COMMENT '20日波动率(%)；NULL=不可用/未计算（V30 起）。注意：OVERSOLD_REBOUND 策略曾把「加权总分」写入本列（OversoldReboundScoringService.java:502），V30 同批代码已改为写真实波动率',
    MODIFY COLUMN max_drawdown60 DOUBLE DEFAULT NULL
        COMMENT '60日最大回撤(%)；NULL=不可用/未计算（V30 起）；本库 2853 个 0 经复算（真值 28%~62%）全部为未计算而非真实回撤';

-- ── 第三步：回填「整块未计算」的占位 0（2733 行，逐列独立谓词）────────────────
-- ret20：严格使用「整块」谓词，以保护 4 行真实 0
UPDATE selector_result_item
   SET ret20 = NULL
 WHERE ret20 = 0 AND ret60 = 0 AND volatility20 = 0 AND max_drawdown60 = 0;

-- ret60 / volatility20：本库无孤立 0，但为与 ret20 保持同一口径，仍用整块谓词
UPDATE selector_result_item
   SET ret60 = NULL
 WHERE ret60 = 0 AND ret20 = 0 AND volatility20 = 0 AND max_drawdown60 = 0;

UPDATE selector_result_item
   SET volatility20 = NULL
 WHERE volatility20 = 0 AND ret20 = 0 AND ret60 = 0 AND max_drawdown60 = 0;

-- max_drawdown60：整块行 + 已复算证实的 oversold 类孤立 0（共 2733 + 120 = 2853 行）
UPDATE selector_result_item
   SET max_drawdown60 = NULL
 WHERE max_drawdown60 = 0
   AND (   (ret20 = 0 AND ret60 = 0 AND volatility20 = 0)
        OR (ret20 <> 0 OR ret60 <> 0 OR volatility20 <> 0)   -- 即 (c) 的 120 行：均为 oversold 类，已复算证伪
       );

-- 说明：上面 max_drawdown60 的谓词等价于「max_drawdown60 = 0 的全部行」。
-- 之所以仍写成显式两分支，是为了让「哪些行被改、依据是什么」在 SQL 里可读；
-- 若将来出现「真实回撤恰为 0」的行，应改用更严的谓词（例如叠加 strategy_type 白名单）。

-- 只读核实（迁移后执行）：
--   SELECT IS_NULLABLE, COLUMN_TYPE, COLUMN_COMMENT FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='selector_result_item'
--      AND COLUMN_NAME IN ('ret20','ret60','volatility20','max_drawdown60');
--   SELECT COUNT(*) FROM selector_result_item WHERE ret20 = 0;                 -- 期望 4（真实值保留）
--   SELECT COUNT(*) FROM selector_result_item WHERE ret20 IS NULL;            -- 期望 2733
--   SELECT COUNT(*) FROM selector_result_item WHERE max_drawdown60 IS NULL;   -- 期望 2853

-- FOLLOW-UP（本轮不做）：
--   1) 这 2853 行的底层数据在 market_data 里是齐全的 ⟹ 理论上可**重算**而非标 NULL；
--      重算属研究数据改写（须版本化 + 前后对照），与 V25 FOLLOW-UP 第 3 条同性质。
--   2) 幂等性：selector_result 仍缺 (trade_date, factor_version, topn) 唯一约束（V24:19 声明暂缓），
--      本迁移不涉及。
