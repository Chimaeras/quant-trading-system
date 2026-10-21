# baseline.md — 冻结基线与评估合同

- 项目：`E:\Project\quant-trading-system`
- 代码版本（冻结基线）：`34098346d2266f467f3914e593a98b5fa1e21314`
- 工作区状态：HEAD 之上有 **10 个已修改的受跟踪文件**（第 1–5 轮修复 +
  第 10 轮新增 `application.yml` 的 `quant.t1.schedule` 段与
  `T1CandidateController` 两个端点）+ 新增未跟踪文件
  `config/SchedulerConfiguration.java`、`scheduler/T1CandidateScheduler.java`、
  `scheduler/T1CandidateSchedulerTest.java` + 未跟踪目录
  `research/decision_support_iteration/`。**未提交、未推送、未部署。**
  受跟踪文件清单与其改动依据见 [progress.md](progress.md) 各轮小结。
- 合同版本：`DS-EC-1`（decision-support evaluation contract v1）
- 建立时间：本轮（迭代目录首次建立）
- 数据快照（本地实测，只读）：`market_data` 2003-12-16 .. 2026-10-12，2,460,262 行
- **运行时事实（第 10 轮新增，已实测）**：
  - `trading_calendar` 已由 485 行回填至 **517 行**，覆盖 2024-08-19 .. **2026-10-12**，
    `source=eastmoney_derived`、`reliability=limited`（派生口径，非官方）。
  - `security_master_history` **仍为 0 行**，且已查明**内置 bootstrap 无法消除该缺口**
    （写入 `current_snapshot` 会被 `PointInTimeUniverseProvider:80-84` 强制判为 `limited`）。
  - 生产快照会如实写入 `master_source=stock_info_fallback` / `pit_limited=true` /
    `quality_status=PIT_LIMITED`，且概率模型因此被阻断（`score_model=None`）。
- **生产数据可用性（第 11 轮新增，关键前置依赖）**：
  - `market_data` 名义覆盖到 2026-10-12，但 **2026-09-08 起只有 249 只 SZSE 标的在更新**，
    其余 5,216 只停在 2026-09-07（BSE/SSE 的最后入库时间为 2026-09-08 10:28~10:30）。
  - 根因：**自家全市场入库管线部分失败** —— `data_update_task` id 47 是最后一次全市场成功
    （2026-09-08 10:30:11，5484/5484）；其后 id 48/49 在 252/153 只处中断。
  - 后果：按冻结基线口径实测，断崖窗口（2026-09-08..10-12，19 个交易日）
    **每日池内仅 64–69 只、过阈值 0、入选 0** ⇒ **日频选股在当前时点不可用**。
  - ⇒ **在完成一次成功的全市场回填（覆盖到 2026-10-12）之前，不应开展新的策略评价轮次。**

> 本文件只记录**当前生产口径的事实**与**评估合同**。任何指标在无样本外证据前不得称为「准确性提升」。
> `rule_score` 一律是**规则排序分**，不是上涨概率。

---

## 一、两条真实链路（源码事实）

### 链路 A：日频 T+1 次日候选

| 项目 | 事实 | 证据 |
|------|------|------|
| 入口 | `POST /api/research/t1/candidates?tradeDate=&topN=` | `T1CandidateController.java:24-29` |
| 核心服务 | `T1CandidateService.generateCandidates` | `T1CandidateService.java:109` |
| 股票池 | `PointInTimeUniverseProvider.loadAsOf(tradeDate)` → 优先 `security_master_history`，缺失则回退当前 `stock_info` 截面并标 `PIT_LIMITED` | `PointInTimeUniverseProvider.java:65-142` |
| 打分公式 | `scoreT1`：动量 `clamp(ret5*100*0.4, -15, 20)` + 量能 `clamp((vr-1)*10, -10, 15)` + 趋势 `clamp(maPos*15, -10, 15)`，再 clamp 到 `[0,50]` | `T1CandidateService.java:730-738` |
| 打分前预过滤 | 回看 60 自然日、窗口内 bars ≥ 30；`isTradableForScoring(..., excludeLimitUpAtClose=true)`（排除收盘涨停/ST/停牌/退市）；`passesMinAvgAmount`；`hasMinListingTenure(60)`；每根 bar 必须 `validDailyBar` | `T1CandidateService.java:67-70,174-197,860-868` |
| 入选门禁 | `rule_score >= 35.0`（`ELIGIBILITY_THRESHOLD_VERSION = "t1_rule_v2"`），若加载到 T+1 champion 概率模型再加 `prob >= 0.55`；排序后截断 topN（TopN 是上限非配额） | `T1CandidateService.java:74-92,358-395` |
| 概率轨 | 仅 `stage=champion` + schema/特征语义匹配才启用；`universe.pitLimited()` 为真时**直接拒绝加载**（`T1ProbLoadResult(null,"PIT_LIMITED")`） | `T1CandidateService.java:164-167,893-951` |
| 快照固定 | 已存在同日快照则只读复用，`snapshot_reused=true`，不重算不覆盖 | `T1CandidateService.java:114-120` |
| 未收盘阻断 | `dailyBarClosed`：`tradeDate < 今天` 或 `== 今天且本地时间 ≥ 15:00` | `T1CandidateService.java:854-858` |
| 覆盖率闸 | `dailyCoverage = covered / expected`；**分母 = 该键在 `[T-60d, T]` 内真实有 bar 且 `status=1` 且 `list_date<=T`**（`ACTIVE_WINDOW_DAYS=60`，剔除陈旧编码/长期休眠键，不使用 >T 数据）；`< 0.95` → `INCOMPLETE_DAILY_DATA` 硬阻断（**阈值自始未放宽**） | `T1CandidateService.java:105,121,196-227` |
| 闸门执行证据 | **第 10 轮首次在生产链路实测到执行**：2026-08-19 cov=0.9967 PASS；2026-09-08 cov=0.0455(5469/249) BLOCK；阻断响应含 `coverage_denominator_rule` 字段 | `rounds/R10_production_wiring_and_scheduler.md` §2 |
| 定时生成 | `@Scheduled(cron="0 10 15 * * MON-FRI", zone="Asia/Shanghai")`，**默认关闭**（`quant.t1.schedule.enabled=false`）；漏跑自愈回看 10 自然日；只写 `t1_candidate` 快照，不触碰下单接口 | `SchedulerConfiguration.java`、`scheduler/T1CandidateScheduler.java` |
| 定时可观测 | `GET /api/research/t1/schedule/status`（只读）、`POST /api/research/t1/schedule/trigger`（复用同一 `runOnce`） | `T1CandidateController.java` |

### 链路 B：盘中观察（SHADOW）

见 [progress.md](progress.md) 中「链路 B 核查」一节（由独立只读审计补充，逐项含 file:line）。

---

## 二、标签、成交口径与不可交易性（合同核心）

### 现有生产标签（链路 A 核销）
- 定义：`hit = (close[t+1] / close[t] - 1) > 0`，即**次日收盘对当日收盘的方向**。
- 证据：`T1CandidateService.java:445-459`。
- 该统计**已知不是可交易收益**：`close[t]` 是 T 日收盘，信号在 T 日收盘后才产生，**T 日收盘价不可成交**；未扣佣金/印花税/过户费/滑点；未处理涨停无法买入、停牌、一字板。
- 源码自身已如实标注（`T1CandidateService.java:496-497,686-689`）。**本项保留为辅助指标，禁止作为主指标**。

### 合同冻结的评估口径（`DS-EC-1`）
- **预测周期**：T+1（信号日 T 收盘 → 持有到 T+1 收盘）。
- **最早可成交时点**：T+1 开盘（次日开盘）。任何以 T 日收盘为入场价的统计**不得记为可交易收益**。
- **成交价口径**：主口径 = T+1 开盘买入、T+1 收盘卖出（`open[t+1] → close[t+1]`）；辅助口径 = T+1 收盘对 T 日收盘（仅方向统计，标注为不可交易）。
- **费用口径**：双边，买入佣金 + 卖出佣金 + 印花税（卖出）+ 过户费；参数需在报告中显式列出并写入 experiments.csv。
- **交易限制**：T+1 开盘涨停（一字板）不可买入 → 计入 `unfillable`；T+1 停牌 → `unfillable`；ST/退市 → 不入池。
- **滑点口径（第 9 轮补充，原合同缺项）**：主口径**假设零滑点**（以 T+1 开盘价成交）。
  该假设**必须与结果同屏披露**，因为实测其约束很紧：
  保本入场滑点仅约 **1.51%**（`fill_constraint.json`），1% 不利滑点即与 0 不可区分。
  真实滑点需要**盘中撮合数据**，本地不存在 ⇒ **无法估计**，不得填猜测值。
  允许并鼓励报告**滑点敏感度曲线**（0%/0.5%/1%/2%/3%）作为稳健性证据。
- **聚合口径（必须遵守）**：组合收益 = **先按日取均值、再跨日统计**
  （`offline_metrics.py:121`）。禁止用**逐笔均值**代替 —— 逐笔均值会给「入选多的日子」
  更高权重，与组合口径不同（实测 1.905% vs 1.530%）。
- **不可成交处理**：不删除、不静默丢弃；`unfillable` 单独计数并计入覆盖率分母。
- **无候选日处理**：`zero_candidate_days` 必须与命中率**同屏报告**，禁止把无候选日剔除后单独报命中率。
- **缺失数据**：`unsettled_days` 单列，禁止当作未命中，也禁止当作命中。
- **样本可用性前置条件（第 11 轮补充，原合同缺项）**：任何评估窗口必须满足
  **每日可评分池规模处于正常量级**（冻结窗口实测 median ≈ 1,600、min 1,064）。
  **2026-09-08 .. 2026-10-12 被明确排除在评估之外**：该窗口实测每日池内仅 64–69 只、
  过阈值 0、入选 0，属**入库管线部分失败**造成的数据缺失，**不是**可用于评价的市场区间。
  在完成一次成功的全市场回填（覆盖到 2026-10-12）之前，**不得**在该窗口上计算或报告任何指标；
  也不得把该窗口的「零候选」当作策略选择性或闸门效果的证据。

---

## 三、评估合同（主指标 / 辅助指标 / 覆盖率约束）

### 候选筛选（链路 A）
| 类别 | 指标 | 说明 |
|------|------|------|
| 主 | Top-K 排序质量（Rank IC / 分组单调性） | 固定 K、固定日期区间、固定股票池 |
| 主 | 命中率 vs **同股票池等权基准** 的超额 | 基准必须同池、同期、同成交口径 |
| 主 | 扣费后可交易净收益（`open[t+1]→close[t+1]`） | 同时报告 `unfillable` 比例 |
| 辅助 | 有效样本数、覆盖率、换手、回撤 | 必须与主指标同报 |
| 辅助 | 次日方向统计（现有生产口径） | 标注「不可交易」 |

### 盘中信号（链路 B）
| 类别 | 指标 |
|------|------|
| 主 | 按明确预测周期的命中率与误报率 |
| 辅助 | 信号延迟、失效比例、重复提醒数、有效覆盖率 |

### 概率类指标的使用前提
`Brier score` / `log loss` / 可靠性曲线 **仅在有样本外校准证据时**才可报告。
当前状态（实测）：`model_registry` = **0 行**，`calibrator_registry` = **0 行**。
→ **本项目当前不存在任何已校准的概率模型**，因此：
- 禁止报告任何概率类指标为「已验证」；
- 禁止把 `rule_score` 称为上涨概率；
- 概率轨在生产链路中恒不可达（见第四节 P0-2）。

---

## 四、冻结基线（不可在本轮被替换）

| 项 | 冻结值 |
|----|--------|
| 生产策略 | 链路 A 的 `scoreT1` + 阈值 35 (`t1_rule_v2`)；链路 B 的 SHADOW 观察 |
| 阈值 | **不得因短样本调低**；`RULE_SCORE_THRESHOLD=35.0` 属**待验证假设**（其标定仅 5 个信号日） |
| 可信度保护 | 覆盖率闸、PIT 闸、快照固定、概率 schema 门禁**一律保留**，不因「想多出候选」而放松 |
| 评估合同 | `DS-EC-1`，版本冻结后方可比较 |

### 已知未决的标定问题（记录，不擅自改）
1. `RULE_SCORE_THRESHOLD = 35.0` 的依据是 **5 个信号日**的实测可达分布（`T1CandidateService.java:78-85`），样本极小，**未通过样本外验证**。
2. `MIN_DAILY_COVERAGE = 0.95` 的分母用 `stock_info` 全量快照，**未剔除停牌/退市/未上市**（详见 progress.md P0-1）。

---

## 五、对照基准（本合同的比较对象）

| 编号 | 基准 | 状态 |
|------|------|------|
| B0 | 当前生产规则（`scoreT1`，阈值 35） | 冻结基线 |
| B1 | 同池等权（全候选池等权持有） | 待建立 |
| B2 | 简单透明技术规则（如纯 `ret5` 排序、纯量比排序） | 待建立 |
| B3 | 沪深300 指数 | 已有 `index_quote`，但**池不同**，仅作参考不作同池基准 |

> 数据不足时结论写「**目前无法证明**」，不得引用外部论文/策略结果替代本项目证据。
