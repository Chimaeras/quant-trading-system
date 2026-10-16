-- V31__complete_v30_backfill_after_null_propagation.sql
--
-- ── 为什么需要这个迁移：V30 的回填在真实执行时自我失效 ────────────────────────
--
-- V30 用 4 条独立 UPDATE 回填四列的占位 0，但每条都重新测试 `ret20 = 0`。
-- V30 的第 1 条先把 ret20 置为 NULL，而 SQL 里 `NULL = 0` 的结果是 NULL（不是 TRUE），
-- 因此第 2~4 条的 WHERE 从第 1 条执行完的那一刻起就再也不可能为真。
--
-- 真实执行结果（本地 quant_db，2026-10-14，V30 应用后实测）：
--   ret20          0: 2737 -> 4     NULL: 0 -> 2733   ← 第 1 条生效，符合 V30 预期
--   ret60          0: 2733 -> 2733  NULL: 0 -> 0      ← 第 2 条完全失配
--   volatility20   0: 2733 -> 2733  NULL: 0 -> 0      ← 第 3 条完全失配
--   max_drawdown60 0: 2853 -> 2733  NULL: 0 -> 120    ← 第 4 条只命中 OR 分支的 120 行
-- 而 V30 文件第 57~60 行自述的预期是：
--   ret20 后 4/2733、ret60 后 0/2733、volatility20 后 0/2733、max_drawdown60 后 0/2853。
-- 即 V30 的执行结果与它自己的验收标准直接矛盾。
--
-- 更有害的是副作用：库因此处于**半迁移状态**——ret20 已是 NULL，
-- 另外三列仍是占位 0。这比迁移前更不一致（V30 第 62 行要求
-- `ret20 IS NULL AND ret60 IS NOT NULL` 的行数恒为 0，实测为 2733）。
--
-- ── 本迁移的修法：谓词不再依赖任何**已被本批改写过**的列 ─────────────────────
--
-- 关键教训：同一批迁移里，后续 UPDATE 的谓词**不得引用前面 UPDATE 已经改写过的列**。
-- 本次只用三列自身作为谓词（这三列在 V30 里都未被成功改写），
-- 因此不受 NULL 传播影响，且可安全重复执行。
--
-- ── 先决核实（本迁移编写时只读实测，2026-10-14）────────────────────────────
--   总行数 5972。
--   ret20 IS NULL 的行 = 2733，这 2733 行的 ret60/volatility20/max_drawdown60 **全部为 0**
--     （SELECT SUM(ret60=0), SUM(volatility20=0), SUM(max_drawdown60=0)
--        FROM selector_result_item WHERE ret20 IS NULL;  -> 2733 / 2733 / 2733）
--   ⟹ 目标行集与 V30 第 1 条命中的是同一批，回填后与 V30 自述预期完全一致。
--   ret60 的「真·孤立 0」= 0 行、volatility20 的「真·孤立 0」= 0 行（V30 文件第 35~36 行已核实）。
--   max_drawdown60 的 0 经复算（真值 28%~62%）全部为未计算（V30 文件第 38~50 行已核实）。
--   4 行真实 ret20=0（688165 ×2、002589、000839）**不在**本迁移的谓词范围内
--     （它们的 ret60 分别为 27.47/23.02/18.91，非 0），因此不受影响。
--
-- ── 影响行数核对（执行前后各跑一次，只读）──────────────────────────────────
--   SELECT SUM(ret20=0), SUM(ret20 IS NULL) FROM selector_result_item;           -- 应保持 4 / 2733
--   SELECT SUM(ret60=0), SUM(ret60 IS NULL) FROM selector_result_item;           -- 前 2733 / 0   后 0 / 2733
--   SELECT SUM(volatility20=0), SUM(volatility20 IS NULL) FROM selector_result_item; -- 前 2733 / 0 后 0 / 2733
--   SELECT SUM(max_drawdown60=0), SUM(max_drawdown60 IS NULL) FROM selector_result_item; -- 前 2733 / 120 后 0 / 2853
--   -- 必须恒为 0（V30 第 62 行的要求，V30 单独执行时实测为 2733）：
--   SELECT COUNT(*) FROM selector_result_item WHERE ret20 IS NULL AND ret60 IS NOT NULL;
--   -- 保留校验：4 行真实 ret20=0 仍在
--   SELECT COUNT(*) FROM selector_result_item WHERE ret20 = 0;                    -- 期望 4
--
-- ── 回滚（注释保留）──────────────────────────────────────────────────────────
--   回滚到 V30 之后、V31 之前的状态：
--     UPDATE selector_result_item SET ret60 = 0
--      WHERE ret60 IS NULL;
--     UPDATE selector_result_item SET volatility20 = 0
--      WHERE volatility20 IS NULL AND id NOT IN (<V30 已改写的那 120 个 id>);
--     UPDATE selector_result_item SET max_drawdown60 = 0
--      WHERE max_drawdown60 IS NULL;
--   更完整的回滚依据见执行前快照 .tmp_quant_watch/v30_pre_affected.csv（2853 行）。

-- ── 第一步：补齐 ret60 ──────────────────────────────────────────────────────
UPDATE selector_result_item
   SET ret60 = NULL
 WHERE ret60 = 0
   AND volatility20 = 0
   AND max_drawdown60 = 0;

-- ── 第二步：补齐 volatility20 ───────────────────────────────────────────────
-- 注意这里仍需引用 ret60，但**不能**写 `ret60 = 0`：上一条已把它改成 NULL。
-- 因此改用 `(ret60 = 0 OR ret60 IS NULL)`，即「本列尚未回填」的两种状态都接受。
-- 这是本迁移相对于 V30 的核心修正：谓词对「已被本批改写」的列使用 IS NULL 容错。
UPDATE selector_result_item
   SET volatility20 = NULL
 WHERE volatility20 = 0
   AND (ret60 = 0 OR ret60 IS NULL)
   AND max_drawdown60 = 0;

-- ── 第三步：补齐 max_drawdown60 ─────────────────────────────────────────────
-- 剩余未回填的 max_drawdown60=0 即 V30 第 4 条漏掉的 2733 行。
-- 全部经复算证伪为「未计算」（真值 28%~62%），故可安全置 NULL。
-- V30 已成功回填的 120 行此刻已是 NULL，不满足 `max_drawdown60 = 0`，不受影响。
UPDATE selector_result_item
   SET max_drawdown60 = NULL
 WHERE max_drawdown60 = 0;

-- ── 只读核实（迁移后执行；上面核对段即为验收标准）────────────────────────────
--   SELECT IS_NULLABLE, COLUMN_TYPE FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='selector_result_item'
--      AND COLUMN_NAME IN ('ret20','ret60','volatility20','max_drawdown60');  -- 期望全 YES

-- ── FOLLOW-UP（本轮不做，与 V30 FOLLOW-UP 一致）─────────────────────────────
--   1) 这 2853 行的底层数据在 market_data 里是齐全的 ⟹ 理论上可**重算**而非标 NULL；
--      重算属研究数据改写（须版本化 + 前后对照）。
--   2) 幂等性：selector_result 仍缺 (trade_date, factor_version, topn) 唯一约束（V24:19 声明暂缓）。
--   3) 迁移内多步 UPDATE 的谓词冲突问题应在评审清单里固化：
--      同一迁移里后续语句不得引用前面已改写的列；若必须引用，需用 IS NULL 容错。
