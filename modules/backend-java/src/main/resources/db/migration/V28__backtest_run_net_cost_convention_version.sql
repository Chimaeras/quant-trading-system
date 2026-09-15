-- V28__backtest_run_net_cost_convention_version.sql
-- 裁定来源：research/paper_loop/decisions/decision_batch3_2026-09-15.md §4 裁定 D「做」
-- 条目：D-R010-06（成本口径钳位加**显式标记键**，并落**公式版本标记**；不改旧字段语义）
--       + §3 C-2（生产库既有行不做全量回填 ⟹ 改为加公式版本标记）
--
-- 背景：`D-R010-06` 的「钳位标记键」已在前一批落地（`assumptions` JSON 内的钳位标记）；
--       缺的是**公式版本标记**——即「这行净收益是按哪一版成本口径算的」。
--       `backtest_run` 已存 commission_rate / sell_tax_rate / slippage_rate 三个费率，但没有版本位，
--       故两版口径（或钳位前后）算出的行在库里长得一样。
--
-- ── 只读实测（本地 quant_db，2026-09-15，仅 SELECT）────────────────────────────
--   SELECT COUNT(*) FROM backtest_run;                              -> 1 行
--   SELECT COUNT(*), commission_rate, sell_tax_rate, slippage_rate
--     FROM backtest_run GROUP BY commission_rate, sell_tax_rate, slippage_rate;
--        -> 1 行 / 三费率**全为 0**（即该行并非按冻结口径 3/5/2 跑的，是零成本或未设费率的历史行）
--   ⟹ 标记列对既有数据只可能有 NULL 一个取值（「早于标记」），**不做回填**（与 §3 C-2 一致；
--      也符合本地数据现实：唯一一行连费率都是 0，替它宣称某个公式版本等于编造）。
--
-- 写入侧（**同批**落地，故该列不是死列；写入方 `ScoringBacktestService` 属另一位执行者正在改的文件，
--   为避免同文件并发改写，写入侧改动在本迁移之后**紧接**的 commit 落地）：
--   `BacktestRun` 实体新增同名字段；`ScoringBacktestService` 落库时写
--   `net_cost_convention_v1`（新行必带），历史行保持 NULL = 早于标记。
--
-- ── 回滚 ─────────────────────────────────────────────────────────────────────
--   ALTER TABLE backtest_run DROP COLUMN net_cost_convention_version;

ALTER TABLE backtest_run
    ADD COLUMN net_cost_convention_version VARCHAR(40) DEFAULT NULL
        COMMENT '净收益成本口径版本：net_cost_convention_v1=钳位后生效费率+费率以E的bp计+NET_AFTER_MODELED_COSTS；NULL=早于标记（旧公式/未标注，不回填）'
        AFTER slippage_rate;

-- 只读核实（迁移后执行；只读，不改数据）：
--   SELECT IFNULL(net_cost_convention_version,'(NULL=早于标记)') ver, COUNT(*) rows_,
--          MIN(created_at) first_, MAX(created_at) last_
--     FROM backtest_run GROUP BY net_cost_convention_version;
--   -- 迁移后预期：仅一行 '(NULL=早于标记)'；新跑的回测应出现 net_cost_convention_v1。
--   SELECT r.result_id, r.net_cost_convention_version, r.commission_rate, r.sell_tax_rate, r.slippage_rate,
--          (r.assumptions LIKE '%clamp%') AS assumptions_has_clamp_marker
--     FROM backtest_run r ORDER BY r.created_at DESC LIMIT 20;
--
-- 相邻表未动（披露）：`portfolio_backtest_run` 已有 cost_version / execution_version（V9），
--   语义与命名不同，本批**不改**它；两套标记并存的事实记在 `docs/研究口径规范.md`。
