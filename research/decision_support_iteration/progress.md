# progress.md — 当前结论、已完成工作、下一步

- 项目：`E:\Project\quant-trading-system`
- 核查时 HEAD：`34098346`（工作区干净，无未提交改动、无 stash）
- 迭代目录：`research/decision_support_iteration/`（本轮新建；**未触碰** `research/paper_loop/`）

---

## 本轮（第 1 轮）问题

**核实 34098346 的修复是否真正进入生产链路，并识别仍会改变结论的现存缺陷；冻结评估合同与基线。**

---

## 一、基线核对结果（8 项逐条）

| # | 核查项 | 判定 | 证据 |
|---|--------|------|------|
| 1 | 日频候选是否准确对应请求日期 | **部分成立，含死代码缺陷** | 见 P0-3 |
| 2 | 未收盘/缺失/覆盖不足是否阻断或明确降级 | **阻断已实现，但覆盖闸不可通过** | 见 P0-1 |
| 3 | 候选快照是否固定、重复生成是否改变结果 | **成立** | `T1CandidateService.java:114-120`；测试 `repeatedGenerationPreservesOriginalCandidatesAndSettlement` |
| 4 | 历史股票池与样本来源是否具备 PIT 证据 | **不成立（数据缺失）** | 见 P0-2 |
| 5 | 训练/测试/概率校准是否通过各自证据门 | **链路存在，但生产无任何模型** | 见 P0-2 |
| 6 | T1 候选是否实际传入盘中观察池 | **成立** | 见「链路 B 核查」 |
| 7 | 盘中是否显示数据时间/覆盖率/策略版本/失效状态 | **部分成立（有缺口）** | 见「链路 B 核查」D-2/D-4 |
| 8 | 默认运行是否为仅观察 SHADOW | **成立** | 见「链路 B 核查」 |

---

## 一之二、链路 B（盘中观察）核查结果

由独立只读审计完成，逐项结论（file:line 证据见各条）：

| # | 项目 | 判定 |
|---|------|------|
| 1 | T1 → 观察池传参 | **已修复**：`cli.py:387→395` → `loop.py:283,304` → `universe.py:262-271` → `candidates.py:126` HTTP `GET /api/research/t1/candidates/{date}`；来源为空/过期时显式 `T1_UNAVAILABLE`/`NO_UNIVERSE`，**不会**静默换成热门/手动词池 |
| 2 | 页面展示字段 | **部分**：已有覆盖率、逐笔时效(`freshness_seconds`)、策略版本(`intraday_rules_v1:<hash>`)、bar 时间、客户端过期标签；**缺失**：`signal_status` 从未渲染、`holding_horizon`/`invalidation_conditions` 无写入者、T1 `threshold_version` 不可见 |
| 3 | 默认 SHADOW | **已修复**：启动脚本 `:7,39-40`、`config.py:91,102,226`，两道运行时闸 `loop.py:1313-1315,1387-1394`，仅两处下单调用点且均要求 `actual_live` |
| 4 | 重复提醒去重 | **缺陷**：只有订单级指纹（`oms.py:135-157`，UNIQUE `store.py:323`），**无提醒级去重/上限**；仅 UI 显示上限 80 行 |
| 5 | 重启恢复 | **部分**：状态/bar/forming/OMS 可恢复；`strategy_state` 表**只写不读**（`store.py:51,754`），无信号级重放守卫 |
| 6 | 「无信号」vs「数据不足」 | **已修复**：`WARMUP`(`loop.py:2206-2209`)、`PARTIAL_COVERAGE`(`:2178,2567`)、`NO_UNIVERSE` 可机器区分 |
| 7 | 预热/已收盘 bar | **已修复**：`required_bars()`=21，`bar_store.py:53` 拒绝未收盘 bar，只对 `update.new_closed` 出信号 |

**本轮新增修复的两个缺陷（均已加回归用例 + 反向对照验证）**

- **D-1（`loop.py:295`）运算符优先级缺陷**：原式 `not A or not B and not C or D` 等价于 `not A or D`，
  「必须使用正式日历」是**死代码**，节假日会把上一交易日的陈旧候选当观察池。
  修复时发现：**仅加括号不足以修复** —— 构造函数传入默认 `TradingCalendarService()` 时
  `_calendar_explicit` 仍为 `True`，而其行为仍是周一至周五回退（`trading_calendar.py:26-28`）。
  判据改为「由文件加载（`source` 前缀 `file:`）或显式提供日历文件路径」。
- **D-3（`loop.py:2194-2209`）过期审计被抹除**：warmup 分支用
  `signal.reason_codes = ["WARMUP","BAR_CLOSED"]` **整体替换**，抹掉刚写入的 `EXPIRED_BAR`。
  修复改为前缀追加，并保持「先过期、后 warmup」的顺序
  （顺序不可颠倒：warmup 会把 action 压成 hold，导致过期分支永不可达）。

**反向对照（negative control）**：`scripts/negative_control_loop_fixes.py`
把 `loop.py` 临时还原为 git 原始版本后运行两个新用例 → **2 failed**；
恢复修复版后 → **2 passed**。证明用例确实捕获缺陷，且恢复字节一致。

---

## 二、已确认的现存缺陷（本轮实测证据）

### P0-1 —— 日频候选被「覆盖率闸」永久阻断（**严重：整条链路恒不可用**）

**证据（本地只读实测，`scripts/db_inventory.py`）**

- 代码分母：`expected` = `stock_info` 中 `status=1` 的行数 = **5,835**（实测；且 5,835 行**全部** `status=1`，无退市/停牌区分）。
- 代码门槛：`MIN_DAILY_COVERAGE = 0.95`（`T1CandidateService.java:105`）→ 需要 **5,544** 根当日 bar。
- 实测历史**最大值**：单日 `COUNT(DISTINCT stock_code)` 全历史最高 = **5,477**（2026-04 附近）。
- 正常期（2026-03-01..2026-08-31，121 个交易日）单日 bar 数：min **5,427**、max **5,477**、mean **5,463.6**。
- 最大可达覆盖率 = 5,477 / 5,835 = **0.93865 < 0.95**。

**结论**：`dailyCoverage < 0.95` 对**每一个**信号日恒成立，缺口恒为 **67 根 bar**。
→ 当前代码下 `generateCandidates` **必然**返回 `INCOMPLETE_DAILY_DATA`，**永远不可能产出任何候选**，也永远走不到 D-T1-01 修的阈值逻辑。

**根因已定位（比"分母含退市股"更具体）**

`git log -L 141,156` 显示：**这个覆盖率闸是 34098346 自己新增的**（该提交之前不存在）。
分母被放大的直接构成（实测）：`stock_info` 中有 **284 个 `stock_code` 同时存在两个
`exchange_type` 行**（北交所 `920xxx` 代码迁移遗留：既有一行 `BSE:920xxx`，又有一行
`SSE:920xxx`，两行都 `status=1`，且**两行在 market_data 里都有 bar**）。
因此纯生命周期条件（`status=1 且 list_date<=T`）无法剔除它们，分母被系统性放大。

**已实施的修复（用户裁定：方案 A —— 只修分母口径，0.95 阈值不变）**

- 分母改为「**回看窗口内真的在交易**的键」：该 `(code, exchange)` 在
  `[T-ACTIVE_WINDOW_DAYS, T]` 内至少有一根 bar（`ACTIVE_WINDOW_DAYS = 60`）。
  新增常量 `T1CandidateService.ACTIVE_WINDOW_DAYS`，分母构造用既有仓库方法
  `MarketDataRepository.findDistinctStockPairsInRange`，**不使用 >T 的任何数据（无前视）**。
- `MIN_DAILY_COVERAGE` **保持 0.95 不变**，未放宽任何可信度保护。
- 标定证据（`scripts/coverage_window_validation.py`，2026-01-05..2026-08-31 共 155 交易日）：
  W=60 → min=0.9596、median=0.9958，**155/155 通过**；W=120 → 130/155；W=180 → 96/155；W=250 → 47/155。
- **仍保留对真实断崖的检出**：2026-09-08 起当日 bar 数由 5,456 掉到 **249** →
  coverage = **0.0455** → 正确 BLOCKED；2026-10-12 = 0.0453 → 正确 BLOCKED。
- 该标定**属待验证假设**（单一数据快照的 155 个交易日，未经样本外验证），已在代码 javadoc 中如实标注。

**生产快照佐证**

`t1_candidate` 仅 **8 行**，且：

| trade_date | topn | settled | candidates_json 长度 | ecology 阈值版本 |
|------------|------|---------|----------------------|------------------|
| 2026-06-03 | 10 | 0 | 2 (`[]`) | 无 eligibility |
| 2026-06-17 | 10 | 0 | 2 | 无 eligibility |
| 2026-07-22 | 10 | 0 | 2 | 无 eligibility |
| 2026-08-03 | 10 | 0 | 2 | 无 eligibility |
| 2026-08-04 | 10 | 0 | 2 | 无 eligibility |
| 2026-08-11 | 10 | 0 | 2 | `t1_rule_v1`, 阈值 40.0 |
| 2026-08-18 | 10 | 0 | 2 | `t1_rule_v1`, 阈值 40.0 |
| 2026-08-24 | 10 | 0 | 2 | `t1_rule_v1`, 阈值 40.0 |

- 全部 8 行 `candidates_json == "[]"`（长度 2）→ **生产从未产出过任何候选**。
- 全部由 `t1_rule_v1`（阈值 40.0）产生，而当前代码是 `t1_rule_v2`（阈值 35.0）→ **34098346 之后的代码从未在生产运行过**。
- 全部 `created_at` ≤ 2026-08-24 17:41 → 之后无新快照。
- 现有 ecology 无 `generated_at`/`requested_trade_date`/`data_date_fallback` 等新字段 → 进一步证明新代码未运行。

**附带的次级缺陷（同源）**

`expected` 从 `PointInTimeUniverseProvider` 回退截面取得（`stock_info` 全量），包含**已退市、长期停牌、以及从未交易**的标的，而 `covered` 是「当日真实有合格 bar 的标的数」。两者口径不同，导致闸门天然偏高——这是分母口径缺陷，不是「数据不够」。

**影响**：用户工作流第 1 步（日频筛选）完全不可用；且**任何**声称「准确性改善」的实验在当前代码下都无法在生产链路复现。

**处理状态**：已按用户裁定（方案 A）修复分母口径，0.95 阈值不变。见上「已实施的修复」。

---

### P0-2 —— 概率轨在生产恒不可达；PIT 主数据为空（**严重：可解释性与概率证据缺失**）

**证据（本地只读实测）**

- `security_master_history` 行数 = **0**。
- 因此 `PointInTimeUniverseProvider.loadAsOf()` 每次都走回退分支 → `pitLimited = true`、`master_source = stock_info_fallback`、`master_reliability = limited`（`PointInTimeUniverseProvider.java:108-141`）。
- `T1CandidateService.java:164-165`：`universe.pitLimited()` 为真时**直接拒绝加载概率模型**，`fallbackReason = "PIT_LIMITED"`。
- `model_registry` 行数 = **0**；`calibrator_registry` 行数 = **0**。
- `DataQualityGateService.checkSecurityMaster`：`rows == 0` → **BLOCKED**（`DataQualityGateService.java:258-261`）；且该门禁对 `PROBABILITY_TRAIN`/`PROBABILITY_PROMOTE`/`DAILY_PUBLISH` 要求 `PASS`，`DEGRADED` 也拒绝（`DataQualityGateService.java:152-155`）。

**结论**

1. 生产链路**只可能输出 `RULE_SCORE`**，永远不可能输出 `CALIBRATED_PROBABILITY`。
2. 不存在任何已校准概率模型 → 按任务书，**禁止报告 Brier/log loss/可靠性曲线为已验证**，只能报告规则分数与排序能力。
3. 样本来源不具 PIT 证据：`stock_info` 是**当前**截面，已退市标的整体缺失（**幸存者偏差**），`status` 也无历史（全为 1）。历史候选池因此**不可信**。
4. 本项目历史选股研究（含 `research/paper_loop` 的既有结论）在 PIT 口径上均带此偏差——**不覆盖、不修订既有结论**，仅记录。

**最小必要动作（属需确认的高影响操作）**：回填 `security_master_history`（含 `valid_from/valid_to/delist_date/reliability=official`），并回填 `stock_info.status` 的历史。这是**数据库迁移/大数据回填**，按任务书须先说明风险并取得用户确认。

---

### P0-3 —— 生效日回退元数据是死代码（**中等：可追溯性失真**）

**证据**

- `T1CandidateService.java:136`：`LocalDate actualDate = tradeDate;`
- 全文件仅 11 处引用 `actualDate`，**无任何一处对 `actualDate` 重新赋值**（`grep actualDate` 实测）。
- 因此 `T1CandidateService.java:274` 与 `:298` 的 `data_date_fallback = !actualDate.isEqual(tradeDate)` **恒为 `false`**。
- 同时 `emptyDecision` 在 `T1CandidateService.java:874` **硬编码** `data_date_fallback = false`。
- 结论：`data_date_fallback` 是**永远为 false 的死元数据**。它向使用者暗示「存在回退、且本次没回退」，实际上**根本没有回退逻辑**。

**相关的真实语义缺口**

- `dailyBarClosed`（`:854-858`）是**纯挂钟时间**判断：`tradeDate < 今天` 即为「已收盘」，**不校验交易日历**。
- 因此请求**周六/节假日**时：`dailyBarClosed` 返回 `true` → 查询无数据 → 返回 `NO_DATA`（`:137-139`）。使用者无法区分「那天不是交易日」与「那天是交易日但数据缺失/过期」。
- `TradingCalendarService.isTradingDay` 存在（`TradingCalendarService.java:212-214`）但 `generateCandidates` **从不调用**。
- `trading_calendar` 表实测范围：**2024-08-19 .. 2026-08-19**（485 行），而 `market_data` 到 2026-10-12 → 日历比行情**晚覆盖 54 天**；2026-08-19 之后的信号日无法核销（`findTradingDays` 查不到次日 → `no_next_day`）。

**注**：现有测试 `missingSignalDayDoesNotFallBackOrWriteSnapshot` 明确断言**不得**回退（`:354-362`，并 verify 从未调用 `findLatestTradeDateOnOrBefore`）。
→ 设计意图是「不回退、只阻断」，这是**正确的**；缺陷在于**元数据与状态码没有如实表达**，而非缺少回退。

---

## 三、已完成的实际工作

1. 核对 HEAD = `34098346`，工作区干净，无未提交改动。
2. 建立只读数据盘点脚本 `scripts/db_inventory.py`（仅 `SELECT/SHOW`，对写语句 fail-closed），实测 56 张表的数据资产。
3. 逐条核查 8 项基线要求，实测确认 3 个现存缺陷（P0-1/P0-2/P0-3）。
4. 独立审计链路 B（盘中观察）7 项，确认 4 项已修复、2 项部分、1 项缺陷。
5. **修复 P0-1**（覆盖率闸分母口径，阈值不变）+ 回归用例 + 标定脚本。
6. **修复 D-1**（`loop.py` 运算符优先级导致正式日历校验成为死代码）+ 正/反向回归用例。
7. **修复 D-3**（`EXPIRED_BAR` 审计被 warmup 分支抹除）+ 反向对照验证。
8. 冻结评估合同 `DS-EC-1` 与基线 → [baseline.md](baseline.md)。
9. 建立 [experiments.csv](experiments.csv) 实验台账（含失败与未完成实验）。
10. **第 2 轮**：建立离线样本外评价全链路（B0/B1/B2 对照 + 回归分解 + 时间切分
    + 阈值迁移 + 脆弱性检验）→ [rounds/R2_offline_eval.md](rounds/R2_offline_eval.md)。
11. **第 3 轮**：多窗口 walk-forward（9 折 9/9 为正）+ 持有期敏感性
    → [rounds/R3_walkforward_holding.md](rounds/R3_walkforward_holding.md)。
12. **第 4 轮**：修复 D-2（盘中信号状态/持有期口径/失效条件的可解释性）
    → [rounds/R4_D2_signal_explainability.md](rounds/R4_D2_signal_explainability.md)。
13. **第 5 轮**：修复 D-4（覆盖率适用范围与不适用原因）+ P0-3（状态码如实性、
    删除恒假 `data_date_fallback`）
    → [rounds/R5_D4_P03_metadata_truthfulness.md](rounds/R5_D4_P03_metadata_truthfulness.md)。
14. **第 6 轮**：**推翻**自家两条缺陷主张（D-5 重启重复播报、D-6 重复提醒），
    并完成第 4/5 轮修复字段的**生产链路贯通验证**（7 环节）
    → [rounds/R6_D5_D6_negative_and_chain_verification.md](rounds/R6_D5_D6_negative_and_chain_verification.md)。
15. **第 7 轮**：覆盖率闸**真实数据回放**（旧口径 9/9 BLOCK、新口径正常期 PASS 且断崖
    仍 BLOCK）；证明该闸**从未在生产链路上运行**（无 `@Scheduled` + 落库快照无痕迹）；
    界定两项可信度缺口（**前视 ST 过滤**、幸存者偏差**不可量化**）
    → [rounds/R7_coverage_gate_replay_and_chain_gap.md](rounds/R7_coverage_gate_replay_and_chain_gap.md)。
    > **第 10 轮更新**：「从未运行」是**当时**的事实，已被改变 ——
    > 第 10 轮把后端真正跑起来后，该闸**首次在生产链路执行**并留下
    > `coverage_denominator_rule` 字段证据。见 [六之十](#六之十第-10-轮把-p0-修复真正送进生产链路--交易日历回填--定时生成--roundsr10_production_wiring_and_schedulermd)。

### 验证结果（仅与本轮风险相关的最小集）

| 验证 | 命令 | 结果 |
|------|------|------|
| Java 受影响用例 | `mvn -o -Dquant.skipFrontendBuild=true -Dtest=T1CandidateServiceTest test` | **BUILD SUCCESS**，15/15 通过（新增 1 例 `staleUniverseKeysAreExcludedFromCoverageDenominator`） |
| Python 全量 | `cd modules/compute-python && python -m pytest -q` | **355 passed**（见第 6 轮；基线 350） |
| Java 全量 | `mvn -o -Dquant.skipFrontendBuild=true test` | **452 tests, 0 failures, BUILD SUCCESS**（见第 5 轮） |
| 反向对照（D-1/D-2/D-3/D-4） | `python research/decision_support_iteration/scripts/negative_control_loop_fixes.py` | 原始代码下 **6 failed**；恢复修复版后字节一致 |
| 反向对照（D-5 主张） | `python research/decision_support_iteration/scripts/diag_restart_signal_replay.py` | 两会话信号交集 = **空**（原缺陷主张不成立） |
| 反向对照（P0-3, Java） | `python research/decision_support_iteration/scripts/negative_control_p03_java.py` | 原始实现下 **4 例行为断言失败**；恢复后字节一致 |
| Vue 双文件可编译 | `node research/decision_support_iteration/scripts/check_monitor_view.mjs` | SFC parse / compileScript / TS / 模板编译**全 OK** |

> 说明：策略有效性**不能**由单元测试证明。上表只证明代码行为符合预期，不构成任何准确性改善证据。

---

## 四、已确认但**本轮未修**的缺陷（留待后续轮次）

| 编号 | 缺陷 | 影响 | 证据 |
|------|------|------|------|
| **D-2** | ~~`WatchSignal.signal_status` 从未在 UI 渲染；`holding_horizon`/`invalidation_conditions` 无写入者/读取者~~ | **已修复（第 4 轮）**：见第六之四节 | `loop.py:2239-2272`；`MonitorView.vue` 新增「信号状态」「持有期口径」「触发 / 失效条件」列与抽屉列 |
| **D-4** | ~~`market_wide_coverage` 仅当 `_universe_source == "full_market"` 才计算~~ | **已修复（第 5 轮）**：见第六之五节 | `loop.py` 新增 `coverage_scope`/`market_wide_coverage_na_reason`；`MonitorView.vue` 按原因渲染 |
| **D-5** | ~~`strategy_state` 表只写不读 ⇒ 重启后可重复播报同一根 bar~~ | **原主张已推翻（第 6 轮）**：重复播报**不成立**（实测 + 结构性论证）。仍成立的是「写后从不读的死存储」 | 见第六之六节；`store.py:152-167,709`；`bar_store.py:179-188` |
| **D-6** | ~~盘中**提醒级**去重/上限缺失 ⇒ 震荡市可无限重复提醒~~ | **原主张已推翻（第 6 轮）**：未观察到重复（上游输入侧重复已被结构性排除）。「条数线性增长」是另一问题，属产品取舍 | 见第六之六节；`loop.py:2239`；`events.py:195-221` |
| **D-7** | `trading_calendar` 仅覆盖到 2026-08-19，比行情晚 54 天 | 2026-08-19 之后的信号日**无法核销**（`findTradingDays` 查不到次日 → `no_next_day`）；候选生成仍不可用（第 5 轮只让它如实报告 `UNKNOWN`，未修数据） | 见 DS-R007；第 5 轮 |

### P0-3（元数据如实性）→ 已在第 5 轮修复

原判定：**中等，已追踪**。`actualDate` 恒等于 `tradeDate` ⇒ `data_date_fallback` 恒为 false；
`dailyBarClosed` 纯挂钟 ⇒ 周六/节假日返回 `NO_DATA`，与「交易日但数据缺失」不可区分。

**第 5 轮修复**：状态码三态分离（`NOT_A_TRADING_DAY` / `NO_DATA` / `UNKNOWN`）、
删除恒假 `data_date_fallback`、新增 `calendar_trading_day` 与 `TradingCalendarService.coverageWindow()`。
详见第六之五节与 `rounds/R5_D4_P03_metadata_truthfulness.md`。

---

## 五、未完成的核查（不得当作已通过）

- **第 5 项「训练/测试/概率校准是否通过各自证据门」**：代码层证据门存在（`DataQualityGateService`、
  `LogisticModelTrainer` 的 schema/embargo 门），但因 `model_registry=0`、`calibrator_registry=0`
  **无任何实际产物**可核 → 判定「**目前无法证明**」。
- `RULE_SCORE_THRESHOLD=35.0` 的标定仅 5 个信号日，**样本外有效性未验证**。
- `MIN_DAILY_COVERAGE` 的 `ACTIVE_WINDOW_DAYS=60` 标定仅覆盖单一数据快照的 155 个交易日。

---

## 六、下一步

**当前阻塞（P0-2，用户已裁定暂不授权）**：`security_master_history` 为空 →
所有历史 PIT 查询走 `stock_info` 当前截面回退 → 幸存者偏差 + 概率轨恒不可达。
用户裁定「暂不授权 PIT 回填，先出离线证据」。

### 第 2 轮已完成：离线样本外评价 → [rounds/R2_offline_eval.md](rounds/R2_offline_eval.md)

评价合同 DS-EC-1 落地，脚本链可复现：
`offline_eval.py` → `offline_metrics.py` → `offline_diagnostics.py`
→ `offline_decisive.py` → `offline_threshold_transfer.py`。

**主结果**（2026-01-05..2026-08-31，155 个信号日，248,337 个观测，扣费 11.2bp）：

| 策略 | 有候选日 | 选数 | 日均净收益 | 95% CI (block) |
|------|---------|------|-----------|----------------|
| B0 `scoreT1≥35` Top10 | 109/155 | 215 | **+1.530%** | [+0.771%, +2.216%] |
| B1 同池等权 | 155/155 | 248,337 | +0.017% | — |
| B2a 纯 ret5 Top10 | 155/155 | 1,550 | +0.851% | — |
| B2b 纯量比 Top10 | 155/155 | 1,550 | +0.292% | — |

- B0−B1 同日配对差 **+1.493%**，CI_block [+0.840%, +2.176%]（不含 0）
- 控制 `ret5`/`volume_ratio`/`ma_pos` + 日期固定效应后，`scoreT1≥35` 增量
  **+1.017%**，t=**+4.47**（n=246,939）
- 未见区间（6-8 月）B0 = +1.966%，CI [+0.632%, +3.038%]；阈值方向单调比例 0.71

**重要修正**：此前用「全池 Rank IC」判断规则无预测力是**错的**。
`scoreT1` 在三项为负时被 `clamp(0,50)` 压成 0，产生大量并列值，使 Spearman IC 失效
（实测仅 +0.0041）。改用并列稳健的十分位价差后：第9档 − 第0档 = **+0.316%/日**，
CI_block [+0.102%, +0.534%]。**真实区分力只存在于最尾部，中段近似无效。**

**脆弱性（必须与主结果同读）**：

- 剔除贡献最大的 **20** 只标的（占 171 只的 12%）后，日均降到 +0.606%，
  CI_block [−0.114%, +1.236%] → **显著性消失**（剔除 top-1 只掉 0.12pp，非异常值污染）
- 未见区间 B0 − B2a（纯 ret5 Top10）= +0.945%，CI_block **[−0.282%, +2.160%]（含 0）**
  → **不能声称生产规则优于简单动量规则**
- B0 有 **46/155 天（29.7%）无任何候选**，有候选时平均仅 **1.97 只**
  → 覆盖率损失是真实的可用性损失，未被计入收益

**本轮判定**：规则尾部**确实**含动量/量能之外的信息（回归增量显著、未见区间方向一致），
但**不足以替换生产参数**。**结论仍为「目前无法证明策略准确性改善」。**

**新增（本轮补充）：与旧生产阈值 40.0 的同口径反事实对照**

| 阈值 | 有候选日 / 选数 | 全区间日均 | holdout 日均 | holdout 配对差 CI |
|------|---------------|-----------|-------------|------------------|
| 新 35.0（`t1_rule_v2`） | 109 / 215 | +1.530% | +1.966% | **−0.948%**，CI_block [−1.910%, −0.251%] |
| 旧 40.0（`t1_rule_v1`） | 56 / 74 | **+2.169%** | **+4.021%** | — |

→ **40.0→35.0 是选择性取舍（多交易、低单笔质量），不是准确性改善**；
未见区间的差异方向甚至与「提升」相反。

**不可比之处（必须披露）**：`32ae86f6` 的 `scoreT1` 与当前不是同一公式 ——
旧式含 `if(is_limit_up) score += 30` 与 `min(15, lianban*5)`，满分 100；
`dc2f8701` 删除这两项并令 `RULE_SCORE_MAX = 50`。候选池预过滤已排除收盘涨停
（`T1CandidateService.java:242-243`），故旧式的 30 分加分在池内**恒不触发** →
旧 40/100 等价于新尺度 70/100，与新的 35/50（=70/100）占满分比例恰好相当。
**本次对照是「两个不同公式各自选择的阈值」之比，不是对阈值这一单一变量的受控检验。**

> 第 2 轮当时列出的行动 1（多窗口 walk-forward）与行动 2（持有期敏感性）
> **已在第 3 轮完成**，行动 5（修复 D-2）**已在第 4 轮完成**。
> 最新行动清单见「第 5 轮候选行动」。

---

## 六之二、第 3 轮：多窗口 walk-forward + 持有期 → [rounds/R3_walkforward_holding.md](rounds/R3_walkforward_holding.md)

**结论：第 2 轮的单次切分结论经多窗口复核后成立。**

- 固定冻结阈值 35.0，验证段互不重叠：**9 折 9/9 为正**，
  折均值 **+1.915%**（sd 1.03%，min +0.432%，max +3.275%）；相对 B1 **9/9 折为正**
- 池化（并集去重）2026-04-08..08-10（80 日，B0 有候选 62 日）：
  B0 = +1.826%，CI_block [+0.897%, +2.780%]；
  **B0−B1 = +1.868%，CI_block [+1.105%, +2.796%]（不含 0）**
- 把起点提前：配置 (40,20) 与 (50,20) **各 5/5 为正**，最早验证段 **2026-03-10**
- **覆盖缺口**：1-2 月至今**没有**样本外 walk-forward 证据（只能作早期训练窗口）

**持有期敏感性（关键新增限制）**：

| 持有 k | B0 日均 | B0 CI (block) | B0−B2a CI |
|-------|--------|--------------|-----------|
| 1 日 | +1.530% | [+0.771%, +2.216%] | 含 0 |
| **2 日** | **+1.548%** | [+0.197%, +3.083%] | **[+0.247%, +2.659%]** 不含 0 |
| 3 日 | +0.269% | [−1.680%, +2.117%] | 含 0 |
| 5 日 | +0.530% | [−2.473%, +3.172%] | 含 0 |
| 10 日 | −0.131% | [−4.238%, +4.297%] | 含 0 |

→ 优势**集中在 T+1~T+2**，k≥3 后不显著；这不是持续漂移而是**短周期效应**，
与规则由「5 日动量 + 量能 + 20 日趋势」构成一致。

**阈值搜索的样本外行为（负面发现）**：每折在训练窗口内择优，
9 折挑出 38.0(6)/42.0(2)/35.0(1)，**挑中冻结阈值的比例仅 0.11**，
fitted 折均值 +3.924% 明显高于 fixed 的 +1.915% —— 属**选择偏差**
（择优倾向挑更极端、候选更少的阈值）。
→ 冻结阈值 35.0 是**保守的一端**而非数据最优；阈值的**最优点在折间不稳定**
（38 与 42 之间跳），**方向**稳定而**最优点**不稳定。
**不得**据此类搜索回填生产参数。

**多重比较披露**：2026-01..08 已被本会话反复读取（R2 全区间/切分/阈值迁移、
R3 walk-forward/持有期），**该区间已不再是"未被看过的数据"**。
上述显著性应视为**区间内稳健性**，而非对未来的独立确认。
真正的独立确认需要**新时间区间**（依赖行情修复到 2026-09-08 之后）
或 **PIT 数据**（依赖用户授权回填）。

**第 3 轮判定**：多窗口稳健性 **达成**；但无 PIT、生产链路未复现、
1-2 月未覆盖、k≥3 不显著、剔除 top-20 标的后不显著
→ **仍然「目前无法证明策略准确性改善」**。

---

## 六之三、第 3 轮之后的下一步（已完成项见六之四）

**不再在同一区间上继续挖掘指标**（边际信息已耗尽，且加重多重比较）。
第 3 轮列出的行动中，**「修复 D-2」已在第 4 轮完成**（见下）。
第 4 轮完成后的最新行动清单见「第 5 轮候选行动」。

---

### 第 7 轮候选行动（按优先级）

> **第 7 轮已完成**：覆盖率闸真实数据回放 + 「闸从未在生产运行」取证 +
> 两项可信度缺口界定（见第六之七节）。
> **不需要新数据授权的工作已基本清空**，剩余各项**均需使用者确认**。

1. **让闸真正跑起来**：种子生成候选目前只有手动 `POST`，全仓无 `@Scheduled`。
   任何修复都无法转化为可用候选，除非接入调度或明确「人工每日手动触发」为既定流程。
   **需先确认**采用哪种。
2. **前端端到端确认**：第 8 轮已用 DOM-free 执行级 harness 补上
   「数据→文案」的正确性（17/17 通过，见六之八）。
   **仍未做**的是**视觉**确认：构建前端并目视排版/折叠/颜色/滚动。
   属高资源构建且会覆盖 dist，**需先确认**。
3. **补全 `trading_calendar` 至 2026-10**（幂等插入，**需先确认写库范围**）——
   D-7 正解，解除 2026-09/10 候选生成的天花板。
4. （需数据授权）**回填 `security_master_history`** —— 唯一能消除 L1/L2 的途径，
   也是概率轨可达的前提（`model_registry`/`calibrator_registry` 均为 0）。
5. （可选，需取舍）「提醒条数线性增长」是否改为提醒级节流——会改变用户可见信息量。
6. ~~（文档，无需授权）修正 `T1CandidateService` 注释中把 `主表∖活跃`≈358
   只归因于「284 个双 exchange 代码」的不完整归因（L4）~~ —— **已在第 7 轮完成**。
7. ~~（无需授权）成交约束证据：量化「一字板」约束的实际影响 + 入场滑点敏感度~~
   —— **已在第 9 轮完成**（见六之九）。结论：一字板约束近乎无影响（3/215）；
   **保本入场滑点仅约 1.51%**，真实滑点因缺盘中数据**无法估计**。

---

## 六之四、第 4 轮：修复 D-2 → [rounds/R4_D2_signal_explainability.md](rounds/R4_D2_signal_explainability.md)

**问题**：监控页无法区分「预热不足」「bar 已过期」「正常观察」。
`signal_status` 由后端正确写入但前端**零引用**；
`holding_horizon`/`invalidation_conditions` 只是与原因码无关的静态模板。

**修复（数据层 + 展示层必须同时改，只改一侧无效）**：

- `loop.py:2239-2272`：在两分支判定后写入权威值
  - `holding_horizon` = `INTRADAY_EOD`（可执行）/ `UNVALIDATED`（不可执行）
  - `invalidation_conditions`：可执行时给四条真实失效条件；不可执行时**必须给出去因**
    （WARMUP / INVALIDATED / 覆盖不足 / hold）
- `MonitorView.vue`：新增「信号状态」三态列、「持有期口径」列；
  「触发」→「触发 / 失效条件」；抽屉内新增「状态 / 持有期」「失效条件」两列；
  判定顺序改为 **有效期 → `signal_status` → 数据质量**

**验证**：

| 验证 | 命令 | 结果 |
|------|------|------|
| D-2 正/反向用例 | `python -m pytest paper/tests/test_decision_audit.py -q` | **9 passed** |
| Python 全量 | `python -m pytest -q` | **352 passed**（基线 350，新增 2） |
| 反向对照（D-1/D-2/D-3） | `negative_control_loop_fixes.py` | 原始代码 **4 failed**；恢复后**字节一致** |
| Vue SFC 可编译 | `node research/.../check_monitor_view.mjs` | SFC parse / compileScript / TS / 模板编译 **全 OK** |

两个新用例分别防住两种退化：不可执行却不说为什么 / 一切都标成不可执行。

**未做的验证（不得当作已通过）**：未执行全量 `vite build`（高资源、会覆盖 dist）；
**未在浏览器做端到端目视确认**，故「用户实际能看到新列」尚未真实验证。
另：`market_wide_coverage` 恒为 `None`（**D-4**）仍未修。

**判定**：D-2 修复 **保留**；属纯可解释性修复，**不**改变任何评估结论。

---

## 六之五、第 5 轮：修复 D-4 + P0-3 → [rounds/R5_D4_P03_metadata_truthfulness.md](rounds/R5_D4_P03_metadata_truthfulness.md)

### D-4：覆盖率「适用范围」不可解释

**缺陷**：`market_wide_coverage` 仅当 `_universe_source == "full_market"`（即
`watch_universe_mode=FULL`）才计算，默认 T1 模式下**恒为 `None`**；
前端于是把 `None` 渲染成固定字符串「未扫描全市场」——不含数值，
且易被误读成「全市场数据缺失」，而池内模式本就不扫描全市场（属**口径不符**）。

**修复**：
- 后端新增 `coverage_scope`（`FULL_MARKET`/`OBSERVATION_POOL`）与
  `market_wide_coverage_na_reason`（`UNIVERSE_NOT_FULL_MARKET`/`FULL_UNIVERSE_UNAVAILABLE`）
- 前端按原因渲染，并区分「不适用（池内模式）」与「不可用（全市场池构建失败）」——
  **故障不得被说成不适用**
- 提示行同时给出本轮真实覆盖（`quote_coverage`，口径=候选池），数值不再丢失

### P0-3：状态码混淆休市与数据缺失 + 恒假元数据

**缺陷 1**：`dailyBarClosed` 是纯挂钟判断，不看日历 ⇒ 周六/节假日返回 `NO_DATA`，
与「交易日但数据缺失」不可区分。
**缺陷 2**：`actualDate` 恒等于 `tradeDate`（无重新赋值）⇒ `data_date_fallback`
**恒为 false**，且 `emptyDecision` 里硬编码；它暗示存在回退能力，而本服务从无回退路径。

**修复**（不放宽、不回退、不落库）：

| 条件 | status |
|------|--------|
| 日历覆盖且判定为交易日 | `NO_DATA`（确定是数据缺失） |
| 日历覆盖但判定为非交易日 | `NOT_A_TRADING_DAY`（确定是休市） |
| 不在日历覆盖区间内 | `UNKNOWN`（**不得妄断**） |

第三种态是必需的：实测日历仅到 **2026-08-19** 而行情到 **2026-10-12**，
若把区间外当作休市，会把 2026-09/10 的真实数据断崖说成「非交易日」。

同时：删除 `data_date_fallback`（后端 3 处 + 按日回溯白名单 + 前端类型与页面），
新增 `calendar_trading_day`；`TradingCalendarService` 新增 `coverageWindow()`。

### 验证

| 验证 | 结果 |
|------|------|
| Java 受影响用例 | **18/18**（原 15，+3） |
| Java 全量 | **452 tests, 0 failures, BUILD SUCCESS** |
| Python 全量 | **354 passed**（基线 350，+4） |
| 反向对照（D-1/D-2/D-3/D-4） | 原始 `loop.py` → **6 failed**；恢复后字节一致 |
| 反向对照（P0-3, Java） | 原始实现 → **4 例行为断言失败**；恢复后字节一致 |
| Vue 双文件可编译 | MonitorView + T1Candidate 全 OK |

**反向对照的口径修正（重要）**：P0-3 首版脚本把 `TradingCalendarService.java` 一并回退，
只得到**编译错误**（缺 `coverageWindow()`）——这只能证明「方法不存在」。
改为**只回退 `T1CandidateService.java`**、保留纯基础设施新增后，原始实现可编译，
失败来自真实行为差异（`expected NOT_A_TRADING_DAY/UNKNOWN` 但 `was NO_DATA`），
这才是有效的缺陷捕获证据。

**未做的验证（不得当作已通过）**：未执行全量 `vite build`；**未在浏览器目视确认**；
`coverageWindow()` 未单独写单测；日历数据缺口（D-7）**仍未修**——
2026-09-08 之后只是如实报告 `UNKNOWN`，候选生成依然不可用。

**判定**：两项修复 **保留**；均属可解释性/如实性修复，**不**构成准确性改善证据。

**下一轮优先级**：
1. **前端端到端确认**（补第 4/5 轮共同缺口）——需先确认是否允许执行构建；
2. **补全 `trading_calendar` 至 2026-10**（幂等插入，**需先确认写库范围**）——
   这是让 2026-09/10 候选生成真正可用的前置条件；
3. D-6（提醒级去重/上限）与 D-5（`strategy_state` 只写不读）。

---

## 六之六、第 6 轮：D-5/D-6 反证 + 链路贯通 → [rounds/R6_D5_D6_negative_and_chain_verification.md](rounds/R6_D5_D6_negative_and_chain_verification.md)

### 本轮最重要的产出：推翻两条**自己先前记录**的缺陷主张

第 1 轮把 D-5（`strategy_state` 只写不读 ⇒ 重启可重复播报）与
D-6（提醒级去重/上限缺失 ⇒ 无限重复提醒）记为缺陷。
本轮用真实 `SqliteWatchStore` 构造重启场景**实测**，**两者均不成立**。
按 §6「禁止伪造结论」，如实记录**反证**，不为保留原判断找理由。

### D-5 核查：重启**不会**重复播报同一根 bar

诊断实测（`scripts/diag_restart_signal_replay.py`）：
会话1 信号 `11:10`，会话2 信号 `11:15` —— **不同 bar**，交集为空。

**结构性原因（不是偶然）**：

| 机制 | 位置 | 效果 |
|------|------|------|
| `closed_bar` 主键 `(code, period_min, start_ts)` | `store.py:152-167` | 同一根 bar **至多一行** |
| 写入 `ON CONFLICT ... DO UPDATE` | `store.py:709` | 重复写入是 upsert，不产生第二行 |
| bar 仅在**跨桶**时关闭一次 | `bar_store.py:179-188` | 每根 bar 只进 `new_closed` 一次 |
| 信号只由 `new_closed` 产生 | `loop.py:2189-2196` | 重建的 bar 只进预热序列，不触发判定 |

**修正后的 D-5**：重复播报**不成立**；仍成立的是
「`strategy_state` 是写后从不读的死存储，暗示存在并不存在的恢复/去重机制」。

**处置：不修生产逻辑。** 在重复播报已被反证的前提下，去实现「读 `last_processed_bar_ts` 并跳过」
会与 `closed_bar` 主键约束**功能重叠**，且该表主键不含 `trade_date`，跨日复用会造成**新的误判**
——属于增加复杂度而不增加正确性（§6「不得为凑数而修改」）。

新增回归用例固化反证：
`test_restart_does_not_rebroadcast_the_same_closed_bar`（断言 bar 落库、无重复主键行、
两会话交集为空、`strategy_state` 确有写入）。

### D-6 核查：未观察到重复提醒

`emit("SIGNAL")`（`loop.py:2239`）确实无条件写入且 `events.py` 不去重，
但**输入侧不存在重复的 bar**（同上结构性原因），故「同一根 bar 反复提醒」不会发生。

仍成立但**属另一问题**：提示条数可随时间**线性增长**（「条数多」而非「重复」）。
把展示上限升级为提醒节流会**改变用户可见信息量**，属产品取舍，需使用者确认（§5/§6）。

### 正向产出：修复字段的生产链路贯通验证

第 4/5 轮字段此前只验证到单元测试层。本轮把链路从计算端追到浏览器（7 环节）：
`loop.py:2622-2651`（写 metrics）→ `_finish`（进 CYCLE detail）→
`mx_http.py:46-49`（**脱敏不丢字段**，源码级确认）→ `events.emit`（JSONL）→
`serve.py:1141-1158`（HTTP）→ `MonitorView.vue:472-478`（取最近 CYCLE end）→
`MonitorView.vue:506-524`（渲染）。

第 ③ 步是此前未验证、且**最可能悄悄丢字段**的一环，现确认不会丢。
⇒ 新字段**确实能到达浏览器**（但仍不等于「已在浏览器看到」）。

### 验证

| 验证 | 结果 |
|------|------|
| 新增 D-5 反向核查用例 | **12 passed**（+1） |
| Python 全量 | **355 passed**（基线 350） |
| 诊断脚本输出 | 已存档 `diag_restart_signal_replay.txt` |
| 生产链路 | 7 环节核对通过 |

**未做的验证**：未执行 `vite build`、未浏览器目视确认；
D-6「条数增长」未量化；`strategy_state` 是否可安全删除未决（删表属 schema 变更，需确认）。

**判定**：本轮为**核查轮**，未改生产逻辑，**不构成准确性改善证据**。

**下一轮优先级**（不需要新数据授权的部分已基本做完）：
1. **前端端到端确认**——需先确认是否允许执行构建；
2. **补全 `trading_calendar` 至 2026-10**——需先确认写库范围（D-7 正解）；
3. 覆盖率闸在**真实信号日**上的生产链路复现（依赖 1 或 2 解除阻塞）。

---

## 六之七、第 7 轮：覆盖率闸真实回放 + 闸从未在生产运行 → [rounds/R7_coverage_gate_replay_and_chain_gap.md](rounds/R7_coverage_gate_replay_and_chain_gap.md)

### 结论一：修正后的闸在**真实**信号日确实放行（此前只有单测证据）

`scripts/coverage_gate_replay.py` 逐行重算闸门，在真实 `quant_db` 上：

| 组 | 日期 | OLD 覆盖率 | OLD | NEW 覆盖率 | NEW | 当日 bar |
|----|------|-----------|-----|-----------|-----|---------|
| 生产快照日 | 2026-08-11 | 0.9361 | **BLOCK** | 0.9973 | **PASS** | 5,462 |
| 生产快照日 | 2026-08-18 | 0.9357 | **BLOCK** | 0.9969 | **PASS** | 5,460 |
| 生产快照日 | 2026-08-24 | 0.9359 | **BLOCK** | 0.9978 | **PASS** | 5,461 |
| 断崖 | 2026-09-08 | 0.0427 | BLOCK | 0.0455 | **BLOCK** | 249 |
| 断崖 | 2026-10-12 | 0.0425 | BLOCK | 0.0454 | **BLOCK** | 248 |

⇒ 旧口径**每个信号日恒 0.935–0.937 < 0.95 ⇒ 候选恒空**（在真实数据上复现）；
新口径正常期放行、断崖仍阻断 ⇒ **恢复灵敏度而非放宽标准**（阈值 0.95 未动）。
与 Java 注释标定（155/155、min=0.9596、断崖 0.0455）**独立吻合**。

**注释归因不全（L4，本轮已修正）**：`主表∖活跃`≈358，而注释只归因于「284 个双 exchange 代码」。
本轮回放表明 `expected_NEW ≈ 当日 bar 数`（2026-08-11：5,462 = 5,462），
正因如此 `coverage_NEW ≈ 0.997 < 1.0`；而 284 个迁移遗留码**两个键都有 bar**，
属于 `covered`，**不构成**这 358 的缺口。注释已改为如实区分两部分（纯注释改动，
回归 **18/18、BUILD SUCCESS**）。

### 结论二（关键）：**这个闸从未在生产链路上运行过**

| 证据 | 结果 |
|------|------|
| `INCOMPLETE_DAILY_DATA` 在哪个提交出现 | **仅 `34098346`**（10-13 11:10） |
| 落库快照 `eligibility` 的键形态 | 恰好 6 键，对应 `9f0863f0`(8-13)/`32ae86f6`(8-09)/`d70120b6`(9-15)，**不含** `daily_coverage` |
| 全仓 `@Scheduled` | **0 个方法**；`ScoringConfiguration.java:11-15` 明确记载已移除 `@EnableScheduling` |
| 唯一入口 | 手动 `POST /api/research/t1/candidates`（`T1CandidateController.java:24-29`） |
| 8 条快照日期 | 6-03、6-17、7-22、8-03、8-04、8-11、8-18、8-24 —— 稀疏，与手动触发一致 |

⇒ 「34098346 的修复是否进入生产链路」的答案是 **没有进入**——
不是「没有生效」，而是**从未被执行**。
8 条历史快照的空结果由**更早版本**产生，**不是**覆盖率闸造成的。

### 结论三：两项可信度缺口的界定

**L1 前视 ST 过滤（已确认的自身方法学缺陷）**：`offline_eval.py:205` 用
**当前**股票名（`stock_info`）过滤历史全窗口 ⇒ 用未来信息剔除未来输家，方向**乐观**。
量级已界定：窗口内中途停牌的 23 个代码中 **20 个当前为 `*ST`/`ST`**，
它们因「名字含 ST」+「`bars_in_window < 30`」被双重排除，任何一天都不可选。
`stock_info` 无 `status` 历史 ⇒ **本地无法修复**，不做猜测性改动。

**L2 幸存者偏差不可量化**：`market_data` 5,839 键全部是当前在市标的
（仅 4 个键不在 `stock_info`，即 `001257|SZSE` 等 BSE 迁移遗留）；
`stock_info.status` 全为 1、**无退市列** ⇒ 期内退市标的行情**整体不存在**。
这解释了为何 `drop_reasons` 中 `NOT_IN_UNIVERSE`/`NOT_YET_LISTED` 均为 0。

**L5 不可成交剔除影响极小**：B0 为 `total_picks=215, total_unfillable=3`（1.40%），
对均值影响 < 0.02%。

### 验证

| 验证 | 结果 |
|------|------|
| 覆盖率闸回放 | 9 日期；旧 9/9 BLOCK，新正常期 PASS/断崖 BLOCK |
| 幸存者探针 | 5839 vs 5835 键，缺 4；status 全 1；无退市列 |
| 中途停牌定性 | 23 个，20 个当前 `ST`/`*ST` |
| 本轮生产代码改动 | **无**（`git diff --stat modules/backend-java` 与第 5 轮末一致） |

**判定**：本轮为**核查轮**，**不构成准确性改善证据**。

---

## 六之八、第 8 轮：MonitorView 装配的**可执行**验证 → [rounds/R8_monitorview_execution_harness.md](rounds/R8_monitorview_execution_harness.md)

### 本轮要解决的问题：此前对前端的「验证」都不是执行级

第 4/5/6 轮复核 `MonitorView.vue` 靠的是 `check_monitor_view.mjs` —— 一个
**关键字存在性检查**（去掉注释后看代码里有没有某个字段）。它能证明「代码里写了」，
**不能**证明「后端 JSON 经 `refresh()` → 计算属性 → 模板绑定后，真的产生了预期文案」。
本轮补上执行级证据。

### 方法：DOM-free 逻辑 harness（不改生产代码）

在 Node 里**真实执行**组件的 `setup()` 与**真实编译**的模板，只替换外部依赖：

```
parse + compileScript（真实 setup）
compileTemplate(ssr)（真实渲染函数）
typescript.transpileModule（纯语法擦除）
loader 替换 @/api/watch、@/utils/security、element-plus、*.vue
→ 真实执行 onMounted → refresh() → 断言计算属性与渲染结果
```

> 关键点：**没有活动组件实例时 `onMounted` 只警告、不注册，`refresh()` 永不执行**，
> 所有断言会拿到空值（harness 第一版正是如此，一度看似「功能没实现」）。
> 解决方式是把编译产物里 `vue` 的 `onMounted` 改写为本地包装、由 harness 手动执行回调。
> 这里的教训值得记下：**「拿到空值」不等于「功能缺失」，必须先排除 harness 自身的问题。**

### 结果：17/17 通过，退出码 0

| 场景 | 断言 | 实测 |
|------|------|------|
| A 正常全市场 | 覆盖百分比 / 口径提示 | `99.83%` / `口径=全市场` |
| A | 状态 / 持有期 / 三条失效条件 | 观察中 / `INTRADAY_EOD` / 三条齐全 |
| B 池内模式 | 不适用（池内模式）+ 池内真实覆盖 | ✅ / `本栏不适用；本轮实际覆盖（口径=候选池）97.00%` |
| C 取数失败 | 不可用（全市场池构建失败） | ✅ **且未串味成池内模式** |
| D 字段缺失 | 不适用（口径未标注） | ✅ |
| E 状态映射 | WARMUP / INVALIDATED / OBSERVE / 空值 | 预热不足 / 已失效 / 观察中 / 观察中 |
| F 缺省 | WARMUP 无失效条件 | `预热不足（未达所需 bar 数）→ 不可执行`，持有期 `UNVALIDATED` |

**本轮最有价值的证据是 B/C/D 一组**：三种「不适用/不可用/未标注」在运行时文案
**互不串味** ⇒ 失败**不会**被静默显示成一种正常口径。这是第 5 轮声称修复的行为，
此前从未被执行验证过。

场景 E 的空值 → 「观察中」也值得指出：**缺省状态不谎报为「已失效」**。

### 验证

| 验证 | 结果 |
|------|------|
| 新增执行级 harness | **17/17 通过，退出码 0** |
| Python 全量 | **355 passed** |
| Java 受影响用例 | **18/18，BUILD SUCCESS** |
| Vue 存在性检查（旧） | `ALL CHECKS PASSED`（保留，作为补充） |
| experiments 台账 | **36 行 × 21 列**，字段数校验 OK |
| 本轮生产代码改动 | **无** |
| 仓库污染 | 无（生成物写入 `%TEMP%\dsh-vue-harness`） |

### 仍然不能证明的事

1. **不是界面目视确认**：只验证数据→文案正确，**未**在浏览器看过排版/折叠/颜色/滚动；
   视觉层仍需（需授权的）`vite build` + 人工确认。
2. `T1Candidate.vue` 依赖较多未纳入本 harness，其 P0-3 标签仍只有文本级证据。
3. 本 harness 依赖 `modules/web/node_modules` 的 `vue`/`@vue/compiler-sfc`/`typescript`，
   升级后需重跑。
4. 它证明的是**前端把后端字段显示对了**，**不能**证明后端字段本身可信
   （那见第 7 轮：覆盖率闸、PIT、幸存者缺口）。

**判定**：D-2 与 D-4 的前端装配 **执行级通过**；本轮未改生产代码，
**不构成准确性改善证据**。

---

## 六之九、第 9 轮：成交约束证据 + 入场滑点敏感度 → [rounds/R9_fill_constraint_and_slippage.md](rounds/R9_fill_constraint_and_slippage.md)

### 本轮要解决的问题

任务书 §1 要求「覆盖率/成交约束证据」。覆盖率第 7 轮已回放，但**成交约束**只有
`offline_eval.py:244-249` 的一句实现说明（`next_open >= 涨停价 → unfillable`），
且**以 T+1 开盘价零滑点成交**——这个假设**从未被量化**。

### 口径校验：先证明复现忠实

| 校验项 | 冻结基线 B0 | 本轮复现 |
|--------|------------|---------|
| `total_picks` | 215 | 215 |
| `rows_scored` | 248,337 | 248,337 |
| `unfillable` | 3 | 3 |
| 组合日均净收益 | `0.015303550444946206` | `0.015303550444946206`（`<1e-12`）|

> 踩坑记录：第一版误用**逐笔均值**得 +1.905%，与基线不符。聚合必须**先按日取均值、
> 再跨日统计**（`offline_metrics.py:121`）——逐笔均值给「入选多的日子」更高权重，
> 与冻结合同的组合口径不同。已修正并在脚本内注释固化。

### 结论一：现有「一字板」约束**近乎无影响**

| 指标 | 数值 |
|------|------|
| `unfillable`（T+1 开盘 ≥ 涨停价） | **3/215（1.40%）** |
| 真一字板（T+1 最低价也 ≥ 涨停价） | **1（0.47%）** |
| 开盘价/涨停价 最大值 | **0.9934（无一够到涨停）** |

⇒ 涨停不可成交**不是**这套规则的主要威胁；第 7 轮「< 0.02%」结论被独立复现并加强。

### 结论二（主要发现）：优势对**入场滑点**高度敏感

| 入场滑点 | 日均净收益 | 95% CI | 日胜率 |
|---------|-----------|--------|--------|
| 0.0% | **+1.530%** | [+0.767%, +2.196%] | 58.3% |
| 0.5% | +1.025% | [+0.266%, +1.686%] | 56.5% |
| 1.0% | +0.524% | **[−0.231%, +1.182%]（含 0）** | 52.8% |
| 2.0% | −0.463% | [−1.211%, +0.189%] | 45.4% |
| 3.0% | −1.430% | [−2.171%, −0.785%] | 38.0% |

**保本入场滑点 ≈ 1.51%。**

> 口径纪律：滑点序列是**机械恒等式**（entry 同乘 `(1+s)`），其配对 CI 退化到极窄是
> **结构性**的，**不可**读成「显著侵蚀」。可读的是数值大小与保本点。

⇒ 在零滑点假设之上，**约 1% 的不利成交滑点就使优势与 0 不可区分**。
这比「一字板」重要得多，而此前所有报告均未提及。

### 结论三：但 T+1 开盘**平均低开**，故统一滑点惩罚偏保守

T+1 开盘跳空：q25 −3.96%、**中位 −1.37%**、q75 +0.34%、**均值 −1.95%**。

入选标的**平均低开 1.95%**，不是「高开买不到」的形态 ⇒ 对入场统一施加不利滑点常数，
对本策略其实是**偏保守**的惩罚。真实滑点需盘中撮合数据估，**本地无此数据**
⇒ 与 L2 同性质：不是「算出来是 0」，而是**本地没有可估的量**。

### 结论四：剔除高开标的**不带来收益**

| 剔除条件 | 剔除笔数 | 日均净收益 | 同批日配对差 |
|---------|---------|-----------|-------------|
| 跳空 ≥ 3% | 21 | +1.465% | +0.033% [−0.231%, +0.252%] 含 0 |
| 跳空 ≥ 5% | 10 | +1.501% | −0.027% [−0.253%, +0.160%] 含 0 |
| 跳空 ≥ 7% | 4 | +1.491% | −0.057% [−0.199%, +0.028%] 含 0 |
| 跳空 ≥ 9% | 2 | +1.548% | +0.000% 含 0 |

⇒ 只是**减少样本**，不改善收益。按 §6 不采纳为规则改动，仅作稳健性证据。

### 验证

| 验证 | 结果 |
|------|------|
| 复现与基线逐位一致 | ✅ |
| Python 全量 | **355 passed** |
| Java 受影响用例 | **18/18, BUILD SUCCESS** |
| 本轮生产代码改动 | **无** |

**判定**：本轮为**核查轮**。它**缩小**而非扩大可声称的空间 ——
即使接受零滑点假设，优势也只在一个**很窄的成交质量区间**内成立，
而该区间的实际位置因缺少盘中数据而**未知**。**不构成准确性改善证据**。

---

## 七、本评价**不能**证明什么（必须与结论同读）

1. **幸存者偏差未消除且不可量化**：`security_master_history` 为空、`stock_info` 无退市列、
   `market_data` 只含当前在市标的 → 期内退市标的行情**整体不存在**，
   不是「被过滤掉」而是「从未存在」，因此**无法用本地数据界定其量级**（L2）。
   收益**可能被高估**。
   > 第 7 轮补充：**已量化的相邻缺口**是前视 ST 过滤（用**当前**股票名过滤历史全窗口，
   > 方向乐观，L1）——其可影响范围已被限制在当前 ST 名单内（窗口内停牌的 23 个代码中
   > 20 个当前为 `*ST`/`ST`，且因 `bars_in_window < 30` 本已不可选）。
2. **不是生产链路验证**：评价只读 `market_data`，**绕过**了覆盖率闸、PIT 加载与快照写入；
   它验证的是**规则本身**，不是**生产行为**。
   > 第 7 轮补充：经源码历史与落库快照形态核对，覆盖率闸**从未在生产链路上运行过**
   > （`INCOMPLETE_DAILY_DATA` 仅存在于 `34098346`，全仓无 `@Scheduled`，
   > 8 条快照均由更早版本以 6 键形态生成）。故「生产行为」目前**不存在可供验证的运行痕迹**。
   > **第 10 轮更新**：此缺口**已补**。第 10 轮真实启动后端并发出真实请求，
   > 覆盖率闸首次在生产链路执行（2026-08-19 PASS 0.9967 / 2026-09-08 BLOCK 0.0455），
   > 阻断响应含 `coverage_denominator_rule` 字段，且与第 7 轮离线回放逐位吻合。
   > 但**注意**：这只补上了「链路行为」的证据，**没有**改变本项原本要表达的
   > 「离线评估绕过链路」这一事实 —— 前述所有收益结论依然**不是**生产链路验证。
3. **单区间**：155 个交易日全处于无断崖期；2026-09-08 起行情断崖使该期间不可用，
   且数据无法向前延伸以构造第二个独立区间。
4. **单次切分**，非多窗口 walk-forward。
5. **未论证退出时点**（固定 T+1 收盘）。
6. **阈值 35 的标定来源仍是旧流水线**（`t1_rule_v1`/40.0），本轮只做检验、未做优化。
7. **成交质量区间未知**（第 9 轮新增）：已报告优势建立在「T+1 开盘零滑点成交」上。
   实测该假设的**保本入场滑点仅约 1.51%**，1% 不利滑点即与 0 不可区分；
   而真实滑点需要**盘中撮合数据**，本地不存在 ⇒ **无法估计**。
   所幸入选标的 T+1 开盘**平均低开 1.95%**，故统一不利滑点惩罚对本策略偏保守；
   但「偏保守」不等于「已验证」，该区间的实际位置仍然未知。
   另：「一字板」约束对这套规则**近乎无影响**（3/215），此前的担忧可排除。
8. **交易日历覆盖仍是「派生」而非官方**（第 10 轮新增）：已回填到 2026-10-12，
   但 `source=eastmoney_derived`、`reliability=limited`（由上证指数日 K 反推）。
   极端情况下指数缺一日即漏判一个交易日；接入 `Tushare trade_cal` 才会变成官方口径。
9. **幸存者偏差依旧未消除，且 `security_master_history` 仍为 0 行**（第 10 轮结论）：
   生产链路目前走 `stock_info_fallback`，快照里如实标注
   `master_source=stock_info_fallback` / `pit_limited=True` / `quality_status=PIT_LIMITED`
   —— **系统没有把降级伪装成权威**，但降级本身依然存在，且 `PIT_LIMITED` 会**阻断概率模型加载**
   （快照 `score_model=None`、`fallback_reason=PIT_LIMITED`）。
   经代码核查，内置的 `initializeFromStockInfoSnapshot()` **无法**消除该降级：
   它写入 `source=current_snapshot`、`reliability=limited`，而
   `PointInTimeUniverseProvider:80-84` 明确把 `current_snapshot` 强制判为 `limited`，
   于是 `pitLimited` 仍为 `true`、概率模型仍被阻断。
   ⇒ **回填当前快照没有意义**；要真正解锁必须引入**带版本证明的历史主数据源**
   （含退市日/改名历史），而本地不存在这样的源。**禁止编造历史**，故暂不执行。
10. **生产数据当前不完整：全市场入库管线部分失败**（第 11 轮结论）。
    2026-09-08 起只有 **249 只 SZSE** 标的在更新（BSE/SSE 停在 2026-09-08 10:30），
    其余 5,216 只停在 2026-09-07；根因是 `data_update_task` id 48/49（今天）
    在跑到第 252/153 只时中断，且最后一次**全市场成功**是 id 47（2026-09-08 10:30）。
    ⇒ **日频选股在当前时点不可用**（不是规则不行，是数据不够），
    断崖窗口按生产规则实测 **19 天、池内 64–69 只、过阈值 0、入选 0**。
    该项是**后续所有工作的前置依赖**：必须先有一次成功的全市场回填（覆盖到 2026-10-12）。
    该回填属需先确认的动作（全市场下载），本轮未执行。
11. **入库任务失败时的可观测性缺口**（第 11 轮附带发现，未修）：
    `DataUpdateTaskRepository:20-21` 的启动恢复路径把孤儿任务改成 `FAILED`
    却**不写 `message`/`finished_at`**，导致事后无法从表里直接读出中断原因
    （只能如本轮一样靠四路径对照排除）。这是诊断能力缺口，不是数据错误。

> 任何"准确性改善"的结论，只有在固定合同 + 冻结基线 + 独立时间区间/多 walk-forward 区间支持下才可给出；
> 否则最终结论必须是「**目前无法证明策略准确性改善**」。

---

## 六之十、第 10 轮：把 P0 修复真正送进生产链路 + 交易日历回填 + 定时生成 → [rounds/R10_production_wiring_and_scheduler.md](rounds/R10_production_wiring_and_scheduler.md)

### 本轮要解决的问题

前 9 轮的评估**全部绕过生产链路**（直接读 `market_data` 自己算分）。第 7 轮已查明：
覆盖率闸 `INCOMPLETE_DAILY_DATA` 只存在于 `34098346`，而**存量 8 条快照全部由更早版本以 6 键形态生成**
⇒ 「修复进没进生产」这件事**当时没有任何运行痕迹可验**。本轮的目标只有一句话：
**让后端真正跑起来，用真实 HTTP 请求证明/否证 P0 修复在生产链路里生效。**

### 途中排掉的三个"环境坑"（都不是项目代码缺陷，但都会让结论失真）

1. **Maven 离线无法解析任何插件**。`_remote.repositories` 记录了 artifactory 来源，
   离线模式下连 `maven-jar-plugin`（jar 其实就在本地）都拒绝使用。
   `-llr` 是 Maven 2 语法，3.9 已移除。
   ⇒ 改用 **javac + `@argfile`** 直接编译，并扫描本地仓库拼运行 classpath
   （脚本 `build_runtime_classpath.py` / `recompile_backend.py`）。
2. **`.m2` 里的 `quant-core-java` 是 JDK 23 字节码（major 67）**，
   而 Spring Boot 3.2 自带 ASM 9.6 最高只认 major 66 ⇒ 启动即
   `Unsupported class file major version 67`。
   **为何此前从不暴露**：`mvn test` 只跑单测，**从不启动 Spring 上下文**，
   组件扫描根本没发生。只有真正 `java -cp ... QuantBackendApplication` 才会撞上。
   ⇒ 用 JDK 21 把这 14 个源文件重编译到独立目录（不改上游源码、不覆盖 `.m2`）。
   注意：**仅仅把 Java 21 版本排在 classpath 前面不够** —— Spring 的组件扫描会遍历
   **所有** jar 条目，陈旧 jar 只要在场就会被读到，必须**整条排除**。
3. **手工 classpath 会拖进共享 `.m2` 里存在但本项目未声明的 starter**。
   `spring-boot-starter-security` 一在场，Spring Boot 就自动装配
   `DefaultSecurityFilterChain` + httpBasic，把所有请求挡成
   `401 WWW-Authenticate: Basic realm="Realm"`，与项目自身的 `AuthInterceptor` 冲突。
   项目只声明了 `spring-security-crypto`（BCrypt 用）⇒ 必须排除该 starter。

> 这三个坑的含义：**「在我机器上跑不起来」不等于「功能坏了」**。
> 三处都不是项目代码问题，是离线环境 + 手工 classpath 的产物。

### 发现一（决定性）：P0-1 覆盖率闸**首次真正在生产链路里执行**

先重编译 `target/classes`（离线 Maven 做不到，用 javac），确保「源码已修」= 「跑的确实是修复后的代码」。
然后用真实 HTTP 请求验证：

| 请求日期 | 返回 status | daily_coverage | daily_expected / covered | calendar_trading_day |
|---------|------------|---------------|--------------------------|---------------------|
| 2026-08-19 | `OK` | **0.9967** | 5477 / 5459 | true |
| 2026-09-07 | `NO_ELIGIBLE_CANDIDATE` | **0.9974** | 5470 / 5456 | true |
| **2026-09-08** | `INCOMPLETE_DAILY_DATA` | **0.0455** | 5469 / 249 | true |
| 2026-10-12 | `INCOMPLETE_DAILY_DATA` | **0.0454** | 5469 / 249 | true |

- **闸门确实在跑**：阻断响应里出现了第 5 轮新增的
  `coverage_denominator_rule = "该键在 [T-60d, T] 内有 bar 且 status=1 且 list_date<=T"`。
  该字段**只可能**由修复后的代码产生，是修复进入生产链路的**直接证据**。
- **数值与第 7 轮离线回放逐位吻合**（离线 0.9963–0.9978 / 0.0455 / 0.0454，在线 0.9967 / 0.0455 / 0.0454）。
  离线结论第一次在真实链路上得到确认。
- **阈值仍是 0.95，未放宽**；真实断崖依然被正确阻断。
- 同时验证了 P0-3：`calendar_trading_day=true` 与 `UNKNOWN` 语义分离生效
  （回填前日历止于 2026-08-19，2026-09/10 会被误判为「非交易日」）。

### 发现二：v2 口径首次产出真实候选（并如实标注降级）

`2026-08-19` 落库了**第一条 v2 口径候选快照**（此前 8 条全是 `t1_rule_v1` + 阈值 40.0）：

| 字段 | 值 |
|------|-----|
| `threshold_version` | `t1_rule_v2` |
| `rule_score_threshold` | 35.0 |
| `total_scored` | 1314 |
| `eligible_count` / `returned_count` | 1 / 1 |
| `rejected_by_reason` | `{"BELOW_RULE_SCORE_THRESHOLD": 1313}` |
| 候选 | `300684 中石科技 SZSE rule_score=40.2628 close=86.59` |
| `daily_coverage` | 0.9967（5459/5477） |
| `master_source` / `pit_limited` | `stock_info_fallback` / **true** |
| `quality_status` / `fallback_reason` | `PIT_LIMITED` / `PIT_LIMITED` |
| `score_type` / `score_model` | `RULE_SCORE` / `None` |

**要点**：系统**没有**把降级伪装成权威 —— `pit_limited=true` 被写进快照，
概率模型按设计被阻断，只输出规则分。这符合「证据不足时明确降级」的要求。
但降级本身依然是本轮**未解决**的问题（见 §七.9）。

### 发现三：「零候选」是市场状态，不是闸门或规则退化

对 2026-09-07 追查（只读）：池内 1342 行，`rule_score` **最大仅 33.10 < 35.0**，过线 0 只。
对比 2026-08-11/08-18 最大 42.4/42.5（过线各 3 只）。
⇒ 该日**确实无人过线**，`NO_ELIGIBLE_CANDIDATE` 是真话，不是闸门误杀。
「零候选日」属于策略的**选择性**特征，应如实报告，不计入覆盖率改善。

### 发现四：`security_master_history` 回填**被证否**（不做）

「全部授权」包含回填 `security_master_history`。核查后**决定不执行**，理由是技术上无效而非无授权：

- 内置 `SecurityMasterHistoryService.initializeFromStockInfoSnapshot()` 写入的是
  `source=current_snapshot` + `reliability=limited`，并在 `note` 里自陈
  「不覆盖退市证券…无历史 ST/改名/行业变更记录」；
- `PointInTimeUniverseProvider:80-84` 把 `current_snapshot` **强制**判为 `limited`
  ⇒ `pitLimited` 仍为 `true`，概率模型仍被阻断，幸存者偏差一点没少；
- 本地**不存在**任何带版本证明的历史源：`stock_info` 只有当前截面（5835 行、`status` 全为 1、
  无退市列、无改名史），全库仅 5 张表名匹配 `history|master|name|cal|delist|suspend`，均不可用。

⇒ 唯一能真正解锁的是**外部官方历史源**（Tushare `trade_cal` / 退市名单等），本地不可得。
**编造历史属明令禁止**，故本轮**不写入任何 `security_master_history` 行**，并把该结论记入 §七.9。

### 本轮落地：定时生成（DS-R030）

此前候选清单**只能人工调** `POST /api/research/t1/candidates`，全仓无 `@Scheduled`，
无人值守时链路永不产出。本轮新增：

- `config/SchedulerConfiguration.java`：`@EnableScheduling` + **池大小 1** 的
  `ThreadPoolTaskScheduler`（宁可排队也不并发，避免同一信号日被两线程同时生成）
  + `Clock` 统一为 `Asia/Shanghai`。
  > `@EnableScheduling` 此前被移除过，原因记在 `ScoringConfiguration`；
  > 其注释明确写了「若后续新增 `@Scheduled` 任务，请随该任务一并加回」——本类即据此独立装配，
  > 不再把「打分装配」与「调度基础设施」混在一起。
- `scheduler/T1CandidateScheduler.java`：
  - `@Scheduled(cron="0 10 15 * * MON-FRI", zone="Asia/Shanghai")`；
  - **漏跑自愈**：在最近 `lookback-days`(=10) 个自然日内找最近一个
    「日历确认是交易日」**且**「当日已有行情」的日期。两者都必需——
    只看日历会把「今天还没收盘」选中而每天跑出空结果；只看行情可能把非交易日当交易日。
    找不到就**明确 SKIPPED，不猜日期**。
  - 委托给既有 `generateCandidates`（本身幂等：有快照则复用不改写、未收盘/覆盖率不足不落库）；
  - 异常**显式兜住**并记 `FAILED`：`@Scheduled` 抛异常只会进日志，会变成「静默不产出」。
- `GET /api/research/t1/schedule/status`（只读）+ `POST /api/research/t1/schedule/trigger`（手动复用同一段逻辑）。
- `quant.t1.schedule.enabled` **默认 false** —— 与「默认观察、不自动产生动作」的既有约定一致。

**在线验证**（真实调用，非推论）：

```
POST /api/research/t1/schedule/trigger
→ signal_date = 2026-10-12      （今天是 2026-10-13 且当日无 bar ⇒ 自愈回退到上一个有数据的交易日）
  status      = INCOMPLETE_DAILY_DATA
  daily_coverage = 0.04538      （正确阻断断崖）
GET /api/research/t1/schedule/status → present=true, schedule_enabled=false,
  cron="0 10 15 * * MON-FRI", zone="Asia/Shanghai"
```

### 验证

| 验证 | 结果 |
|------|------|
| 新增调度单测 | **6/6 通过, BUILD SUCCESS** |
| 生产链路覆盖率闸 | ✅ 首次真实执行，含 `coverage_denominator_rule` 字段证据 |
| 在线值 vs 第 7 轮离线回放 | ✅ 逐位吻合 |
| 真实候选落库 | ✅ 2026-08-19，`t1_rule_v2`/35.0，1 只，降级字段如实标注 |
| 交易日历 | ✅ 485 → **517** 行，2024-08-19..**2026-10-12**，`eastmoney_derived` |
| `security_master_history` | ⛔ **刻意未写入**（技术无效，理由见上） |
| 阈值 / 覆盖率保护 | ✅ 0.95 未放宽、`ACTIVE_WINDOW_DAYS=60` 未改 |
| 是否触达下单接口 | ✅ 未触碰，SHADOW 观察态不变 |
| 是否提交/推送/部署 | ✅ 未提交、未推送、未部署 |

### 判定

本轮的成果是**工程与证据链**层面的，**不是策略准确性**层面的：
把「修复是否进了生产链路」从**无法验证**变成**已用真实请求验证**，
并顺手补上了自动生成能力。**策略准确性依然没有改善的证据。**
`security_master_history` 被证否反而收紧了结论边界 ——
概率模型在当前数据条件下**不可能**被合法解锁。

---

## 六之十一、第 11 轮：「数据断崖」根因取证 —— 入库管线部分失败 → [rounds/R11_data_cliff_root_cause.md](rounds/R11_data_cliff_root_cause.md)

### 本轮要解决的问题

第 7 轮发现 `market_data` 在 2026-09-08 之后每日 bar 数由 ~5,456 掉到 **249**，
当时只把它当成一个**日期边界**。但「正好 249 只、且一个多月不变」这个形态很可疑：

> 是「行情源没数据」，还是「**我们自己的入库管线坏了**」？

两者后果完全不同：前者只能接受并披露；后者是**自家缺陷**，会让日频选股**当前完全不可用**，
而且未经修复前，「覆盖率不足」的阻断**不能**被解读成「保护生效了、系统健康」。

### 发现一：断崖后只剩 **SZSE 249 只**，且是原集合的子集

| 检查 | 结果 |
|------|------|
| 断崖后交易所分布 | **只有 SZSE**（BSE/SSE 各 0） |
| 与断崖前集合关系 | 交集 249、仅断崖后 **0**、仅断崖前 5,216 |
| 是否在 `stock_info` | 249 只全部在、`status` 全为 1、16 只带 ST 命名 |

「只剩一个交易所 + 是原集合子集 + 每天数量几乎不变」＝**管线选择性失败**的典型形态；
行情源故障会按标的随机缺失，不会按交易所整齐切开。

抽验 `000001`（SZSE）：断崖窗口内**每个交易日都有且仅有一根 bar**，连续覆盖到 2026-10-12
⇒ 这批数据本身是完整的，只是**覆盖面只剩 249 只**。

### 发现二（决定性）：入库任务表里的时间线完全吻合

`data_update_task`（49 行）+ `data_update_task_item`（144,108 行）。

| id | status | range | total | processed | finished_at | message |
|----|--------|-------|-------|-----------|-------------|---------|
| **47** | `SUCCESS` | 2026-08-31..**2026-09-08** | 5484 | **5484** | 2026-09-08 **10:30:11** | 处理5484只，成功5484，失败0 |
| **48** | `FAILED` | 2026-07-22..2026-10-13 | 5484 | **252** | **null** | **null** |
| **49** | `FAILED` | 2026-07-22..2026-10-13 | 5484 | **153** | **null** | **null** |

- 任务 47 的完成时刻（09-08 10:30:11）与 `market_data` 里 08-31..09-07 那批数据的
  最后入库时间**完全相同** ⇒ 这是**最后一次把全市场补齐到 2026-09-07** 的任务。
- 之后的 48/49（今天 10:31 / 10:34 启动）都在**刚开头就中断**，且标的级明细
  **全部是 SZSE**、单项状态全是 `SUCCESS`（不是取数报错）——正是那 249 只的来源。
- 交易所级 `last_insert` 指纹吻合：BSE `2026-09-08 10:30:11`、SSE `2026-09-08 10:28:55`、
  SZSE **`2026-10-13 10:35:10`**（只有 SZSE 被今天的失败任务碰到过一部分）。

历史模式：49 个任务里 `FAILED`/`STOPPED` 共 14 个，形态一致——处理数远小于 5,484 就中断
（378 / 280 / 1,152 / 4,693…）。⇒ 全市场入库**长期不稳定**，需反复重跑才成功；
断崖是这种不稳定在 2026-09-08 之后**再没被成功重跑覆盖**的结果。

### 发现三：`FAILED` 的来源是**启动恢复**，而不是正常失败路径

任务 48/49 的状态组合是 `status=FAILED` + `finished_at=null` + `message=null`。
把 `HistoricalDataUpdateService` 里所有会写 `FAILED` 的路径列出来对照：

| 源码位置 | 会写 `finished_at` | 会写 `message` |
|---------|------------------|---------------|
| `:337-338` 正常跑完但有单项失败 | ✅ | ✅ |
| `:128-129` executor 拒绝提交 | ❌ | ✅「executor rejected task: …」 |
| `:341-353` `runTask` 抛异常 | ✅ | ✅「任务异常终止: …」 |
| `DataUpdateTaskRepository:20-21` **启动恢复**批量改 FAILED | ❌ | ❌ |

**只有启动恢复会同时留下两者为 null。** 任务 47（成功）三者齐全，48/49 只满足孤儿形态。
`updated_at` 分别为 `10:32:25` / `10:35:10`，即最后一次**进度写库**的时刻 ⇒
中断发生在跑到第 252 / 153 只时，收尾代码根本没走到。

> 「是哪一次重启触发的孤儿恢复」**无法定论**：现存日志只有我自己那次启动（13:35:22），
> 而 48/49 的 `created_at` 是今天 10:31/10:34，说明当时另有一个 JVM 在跑这些任务，
> 那个 JVM 的日志**已不存在**。能确证的只是「中断后由启动恢复兜底改了状态」，
> 至于是谁在 10:31–10:36 之间重启/结束了那个 JVM，**证据不足，不猜**。

### 发现四：断崖窗口按生产规则**一笔都产不出**

用与第 9 轮**完全相同**的打分/过滤口径外推（`cliff_impact.py`）：

| 窗口 | 信号日 | rows_scored | 每日池内规模 | 过阈值 | 入选 |
|------|-------|-------------|-------------|-------|------|
| A 冻结 2026-01-05..08-31 | 155 | **248,337** | min 1,064 / median 1,600 / max 2,141 | 215 | **215** |
| B 断崖 2026-09-08..10-12 | 19 | **1,269** | min 64 / median **67** / max 69 | **0** | **0** |

**冻结基线复现校验 3/3 逐位一致**（`total_picks=215`、`rows_scored=248337`、
组合日均净收益 `0.015303550444946206` < 1e-12）⇒ 口径未被改坏，窗口 B 结论可采信。

窗口 B 逐日：19 天池内仅 64–69 只，**过阈值全部为 0**，最高分 10.30–33.06。

> **更正第 10 轮的表述边界**：第 10 轮把 2026-09-07 的零候选归因为「市场状态」，
> 这对**那一天**仍成立（当日池内 1,342 只、最高 33.10）。但 09-08 之后**不适用** ——
> 09-08 之后是**数据缺失**，不是市场状态。两者必须分开表述。

### 附带发现：启动恢复路径**不记录失败原因**（诊断缺口，本轮未修）

同上表：四个 `FAILED` 路径里有两个不留 `message`，其中「启动恢复」恰是最常见的中断形态
（崩溃/重启），却把原因留成 null，导致事后只能靠排除法推断。
`HistoricalDataUpdateService:69-73` 目前只打一行聚合日志。**本轮未修**：
修它要改生产写路径的批量 JPQL，而本轮任务是取证、且当务之急是让全市场回填成功。

### 验证

| 验证 | 结果 |
|------|------|
| 冻结基线复现 | ✅ 3/3 逐位一致 |
| 断崖根因 | ✅ 任务表时间线 + 交易所指纹 + 集合重叠 三重吻合 |
| `FAILED` 来源 | ✅ 四路径源码对照，仅启动恢复符合 |
| Python 全量 | **355 passed** |
| Java 全量 | **458 tests, 0 failures, BUILD SUCCESS** |
| 本轮是否改生产代码 | **否**（纯取证） |
| 本轮是否触发全市场下载 | **否**（§6 需先确认；且今天已失败两次） |
| 是否提交/推送/部署 | ✅ 本轮内未提交、未推送、未部署 |

### 判定

断崖**不是市场休市，而是自家全市场入库管线部分失败**。这把一个含糊现象变成了
有任务表证据的自家缺陷，并确立了后续前置依赖：

1. 日频选股**当前时点不可用**——不是规则不行，是数据不够；
   覆盖率闸的阻断是**正确的**（它拦住的正是「用 249 只冒充全市场」这种静默失真）。
   这反过来是对闸门价值的一次**正面**验证。
2. **修复需要一次成功的全市场回填**（`MAIN_BOARD`，覆盖到 2026-10-12）。
   本轮**未执行**：属 §6 须先说明理由与风险并取得确认的动作。
3. 断崖窗口**不能**用于任何策略评价（19 天、64–69 只、0 笔）。

**策略准确性依然没有任何改善证据。**
