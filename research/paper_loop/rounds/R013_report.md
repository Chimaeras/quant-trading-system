# R013 报告：`monotonicity` 标签自身的『名实 / 非退化 / 可重建 / 不越界』审计

> 被审计对象**不是论文**，而是项目自己产出的一个**具名语义标签**。
> 判据来源：CCW2024（Cattaneo / Crump / Wang，*Beta-Sorted Portfolios*，arXiv:2208.10974v3，**C 档**载体〔**勘误**：本行原文误写「A 档」，见附录 `A-05`〕、**CC BY-NC-ND 4.0 ⟹ 只入库元数据、不复制正文**）。
> 预注册：`rounds/R013_prereg.md`（冻结提交 `dffd7691`，冻结时 HEAD `006baaca…`，`sha256 = 52b3b71e…`，本轮结束时**逐字节未改**）。
> 论文审查：`rounds/R013_review.md`。装置：`experiments/R013_monotonicity_label_audit/`。

---

## 0. 一句话结论

**H0 被杀死（四子句中被证伪三条）**：

| 子句 | 判定 | 承重证据 |
|---|---|---|
| **(i) 名实** | **被证伪** | 五组均值**逐位相等**（全为 `0.03125`）时，标签仍输出 `MONOTONIC_INCREASING`；且 `increasing` / `decreasing` **两个 flag 同时为 `true`** ⟹ 取值由**分支顺序**（`increasing` 在前）决定，而不是由数据决定 |
| **(ii) 非退化** | **被证伪** | `factorGroupCount = 20`、每日 10 样本时，**10 个 `observations == 0 && avgReturn == 0.0` 的占位组**参与方向断言：真实 10 组序列**严格递减且全为负**，标签却是 `NON_MONOTONIC`；`long_short_spread` 被污染成 **`+0.0078125`**（真序列全负）。可达性 = **`REACHABLE_PUBLIC_API`**（公开请求字段无服务端校验） |
| **(iii) 可重建** | **被证伪** | 五组均值 `1.4e-4 / 1.2e-4 / 1.0e-4 / 8e-5 / 6e-5` **严格递减**（引擎判 `MONOTONIC_DECREASING`），但经载荷层 `round4` 后五者**全部塌成 `1.0E-4`** ⟹ 由**载荷自身**重建的标签是 `MONOTONIC_INCREASING`，**与引擎标签不同**；且 `factor.ts` 只声明 `monotonicity: string`（**无值域**）、两个 DTO 均**不携带**该标签 |
| **(iv) 不越界** | **未被证伪**（如实登记，**不**包装成发现） | 声明面与展示面**均未声称**统计推断；但该判定**被读数方式改变** ⟹ `reading_dependent = true` ⟹ 按冻结 §12.3 **降级为描述性、排除出 `K1` 候选集** |

`K = {K1: true, K2: true, K3: true, K4: false, K5: false, K6: false, K7: false}`
⟹ **`total_verdict = REJECTED`**、**`h0_alive = False`**。

**边界声明（必须先读）**：
1. 被杀死的是「**项目对 `monotonicity` 标签自身的处理假设**」，**不是**任何策略/因子的收益主张。本轮**零**有效性结论、**零**收益结论。
2. 本轮**不**声称「项目应当做统计推断」——`(iv)` 只要求**不声称**；判定为「**未触发**」的负结论。
3. 本轮**未**读任何行情/财务数据、**未**访问数据库、**未**重算任何成本；结论**不得**外推为「真实数据上分组收益标签的质量」。
4. K1/K2/K3 是**三条互相独立**的杀点（详情见 §3）：K1 不依赖任何客户端输入（默认 `groupCount = 5` 即可触发）；K2 依赖客户端字段；K3 与可达性完全无关。**即便把 K2 整条剔除，`total_verdict` 仍为 `REJECTED`**。

**本轮同时如实登记 4 条勘误**（`A-01`…`A-04`，见 §6），其中 `A-01` 是一条**事前预测未中**——**不得**改写冻结的预注册文本，也**不得**据此放宽任何 `K`。

---

## 1. 口径、单元与边界

### 1.1 被审计对象（`subject`）

**生产者**：`modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java:226-249`（`assessMonotonicity`），唯一计算点 `:187`。

**三个面**（合同 / 载荷 / 展示）：

| 面 | 位置 | 是否携带标签 |
|---|---|---|
| 引擎合同（枚举来源） | `FactorResearchEngine.java:226-249` | 是（唯一产生点） |
| 载荷发射面 B-01 | `FactorResearchService.java:526-554`（`buildSnapshotScopeBlock`，`:543`） | 是（透传） |
| 载荷发射面 B-02 | `FactorResearchService.java:566-569`（`unavailableScopeBlock`，`:569`） | 是（**字面量** `"N/A"`） |
| 载荷发射面 B-03 | `FactorResearchService.java:641-798`（`buildRankIc`，`:762`） | 是（透传） |
| 载荷发射面 B-04 | `FactorResearchService.java:820-839`（`buildNetIcBlock`，`:831`） | 是（透传） |
| 前端类型声明 | `modules/web/src/types/factor.ts:111`、`:202` | 是（`monotonicity: string`，**无值域**） |
| DTO | `FactorAnalysis`（`FactorAnalysis.java:79+`）、`FactorIcSummary` | **均不携带** |

### 1.2 H0 的边界（`R013_prereg.md` §1 **强制复述**）

> **H0** = 项目对 `monotonicity` 标签的处理同时满足四条：
> **(i) 名实**：每个取值标签的**可证伪语义**与**实现判定**一致（标签所断言的「方向性证据」确实存在）；
> **(ii) 非退化**：退化域（空组占位、非有限组均值、组数 ≤ 1、组数超界）**不得**产出具名方向断言，且**不得**抛出未处理异常；
> **(iii) 可重建**：给定**载荷自身**所携带的信息，标签可被**唯一重建**（含并列/退化规则），且值的**定义域**在契约中声明；
> **(iv) 不越界**：任何声明或展示面**不得**把该**点估计标签**表述为**统计/条件期望层面的推断结论**。

**退化域枚举（冻结，四类）**：空组占位、非有限组均值、组数 ≤ 1、组数超界。

### 1.3 判据来源与禁用数值

| 判据 | 内容 | 档位 |
|---|---|---|
| J-1 | 分组收益的**排序统计量**须与其**名义语义**一致（分位组的方向声称须由组序支持） | **L**（只支持「做法种类」） |
| J-2 | 退化域（组数超过样本可支撑的粒度）**不得**作为方向性证据 | **L** |
| J-3 | 若载荷只给出**取整后**的分组统计量，则**基于该载荷**的方向性结论**不可重建** | **L** |
| J-4 | 该文的 Beta 排序构造（uniform inference / 逼近速率） | **X（不可移植）** |

**禁用数值**：CCW2024 的全部数值（逼近速率、实证收益、显著性水平、样本区间）**一条未采纳**；本轮**不存在**任何由该文引入的阈值/容差/合格线。
`forbidden_scan`（只扫 `panel_R013.txt` + 探针源码，含探针小数字面量守卫）命中 **0**；唯一豁免 = `10000.0`（冻结公式副本 B-06 `round4` 的比例，2 处命中同为该 token 的读数）。

### 1.4 判定单元与冻结自检

| 项 | 值 | 自检结果 |
|---|---|---|
| 冻结扫描根 | **11 文件 / 252,117 B**（逐文件 `sha256-32`） | **11/11 `match = true`**，`frozen_ok = true` |
| 预注册哈希 | `sha256 = 52b3b71e…`（冻结于 `dffd7691`） | `unchanged = true` |
| 冻结锚点 | A-01…A-10 / B-01…B-12 / C-01…C-09 共 **35** 项 | **35/35 命中**，0 未命中，0 撤回；**3 项区间不符**（只登记勘误，见 §6） |
| 变体 | **21**（A1=5 / A2=5 / A3=5 / A4=3 / A5=3），`MAX_VARIANTS = 21` | `row_sum = axis_total = variants_total = 21`，`grid_mismatch_count = 0` |
| 判定词表 | **16 词（冻结）** | `off_vocab = []` |
| 金丝雀 | `CANARY_PAIRS = 21` | **21/21 通过**，`canary_failures = 0` |
| 仪器告警 | — | `instrument_alerts = []`（**根外文件 0 个**） |
| 探针 | 编译进生产包 `target/classes` | 两次运行**逐字节相等**，`sha256 = 7894ed5d7ad4205b0f4184b40cccb078e72c18d8b458ef4e436049d648b40508`，23,482 B；字节码末尾清理 `R013Probe.class` / `R013Probe$PanelSpec.class` |
| 生产代码改动 | **0**（`zero_production_changes = true`） | 见 §7 |

---

## 2. 机器化事实

### 2.1 静态侧（源码文本，机械抽取）

| 事实 | 读数 |
|---|---|
| 引擎标签字面量词表 | `M01 = "MONOTONIC_INCREASING"` / `M02 = "MONOTONIC_DECREASING"` / `M03 = "NON_MONOTONIC"` / `M04 = "N/A"`（4 个，`assessMonotonicity` 内） |
| 发射点 | **4 面**（B-01/B-02/B-03/B-04）：**3 面透传** `summary.monotonicity()` / `s.monotonicity()` + **1 面字面量** `"N/A"`（`unavailableScopeBlock:569`）⟹ `V15 = CONSISTENT_VOCAB`、`V20 = TRUE_CLAIM` |
| `factor.ts` 值域 | `:111`、`:202` 均为 `monotonicity: string` ⟹ **无值域声明**（三读数一致，`V12 = CLAIM_ABSENT`、`reading_dependent = false`） |
| `FactorAnalysis` DTO | 字段 12 个、builder 方法 14 个：**有** `groupedReturns`、**无** `monotonicity`（`V13 = CLAIM_ABSENT`） |
| `FactorIcSummary` DTO | 字段 9 个、builder 方法 10 个：**既无** `groupedReturns`、**亦无** `monotonicity`（`V14 = CLAIM_ABSENT`） |
| `factorGroupCount` 校验链 | `ScoringBacktestRequest.java:37-38` `private Integer factorGroupCount = 5;` —— **3 个候选校验注解全部未命中**（`@Min`/`@Max`/`@Valid` ⟹ `validation_annotations_found = [false,false,false]`）；`backtest.ts:12` `factorGroupCount?: number`；`ScoringBacktest.vue:36` `:min="2" :max="10"`（**仅 UI 缓解**） ⟹ `V07 = DEGENERATE_ASSERTS`、`reachability = REACHABLE_PUBLIC_API` |
| 披露粒度（标签所在块） | **3 个标签块**：B-01（无 `group_count`、有每组样本量）、B-03（**有** `group_count`、有每组样本量）、B-04（**两者皆无**） ⟹ `blocks_with_group_count = 1`、`blocks_with_per_group_obs = 2`、`label_blocks = 3` ⟹ `V18 = FALSE_CLAIM` |
| 声明/展示面的推断词 | 16 词表逐条：**子串读数命中 0**、**类型分派收紧读数命中 0**、人工逐字命中 0；但整个词表对同一批文本的**朴素整仓读数**命中 `true` ⟹ 该主张 `reading_dependent = true`（见 §3.4） |
| `N/A` 的多义性 | **3 个来源**（`engine_size_lt_2` / `engine_empty_summary` / `service_unavailable_scope`），其**可区分键**分别为 `["total_observations","group_count"]` / `["total_observations","note"]` / `["status"]` ⟹ **可区分**（`V21 = TRUE_CLAIM`）；前端**零** `MONOTONIC_` 字面量 |
| 既有测试覆盖 | `FactorResearchEngineTest` 的 3 个单调性测试只覆盖**严格递增 / 严格递减 / 非单调** ⟹ 常量 / 非有限 / 空桶占位 / `groupCount > n` / `groupCount ≤ 0` **五类退化全部未覆盖**（`P-18` 成立） |

### 2.2 运行时侧（Java 探针**直接调用生产** `FactorResearchEngine.summarize` / `assessMonotonicity`）

探针以 `package com.quant.service.backtest;` 编译进 `modules/backend-java/target/classes`，对 **14 个冻结面板 + 3 个单元面板** 调用真实生产方法；`label` / `flags` / `copy_label` / `copyFlags` 均为**冻结锚点与分支顺序的读数副本**（不是重实现）。

| 面板 | 输入 | 标签 | 组数 / 组均值长度 | `obs==0` 组数 | `totalObservations` | `long_short_spread` |
|---|---|---|---|---|---|---|
| `Q01_strict_up` | 严格递增 | `MONOTONIC_INCREASING` | 5 / 5 | 0 | 10 | `0.0625` |
| `Q02_strict_down` | 严格递减 | `MONOTONIC_DECREASING` | 5 / 5 | 0 | 10 | `-0.0625` |
| `Q03_wave` | 波动 | `NON_MONOTONIC` | 5 / 5 | 0 | 10 | `0.0` |
| **`Q04_flat_groups`** | **5 组均值逐位相等（`0.03125`）** | **`MONOTONIC_INCREASING`** | 5 / 5 | 0 | 20 | `0.0` |
| `Q05_constant_returns` | 全部收益恒等 | `N/A` | 5 / **0** | 0 | **0** | `0.0` |
| **`Q06_overgroup`** | **`groupCount = 20`，每日 10 样本** | **`NON_MONOTONIC`** | **20 / 20** | **10** | 20 | **`+0.0078125`** |
| **`Q07_round_collision`** | **5 组均值 `1.4e-4…6e-5` 严格递减** | **`MONOTONIC_DECREASING`** | 5 / 5 | 0 | 5 | `-7.999999999999999E-5` |
| `Q08_zero_group` | `groupCount = 0` | `N/A` | 0 / 0 | 0 | **10** | `0.0` |
| `Q09_one_group` | `groupCount = 1` | `N/A` | 1 / 1 | **1** | **10** | `0.0` |
| `Q10_negative_group` | `groupCount = -1` | `N/A` | 0 / 0 | 0 | **10** | `0.0` |
| `Q11_nan_return`（aux） | 含非有限 `forwardReturn` | `MONOTONIC_INCREASING` | 5 / 5（含 `NaN` 组均值） | 0 | 10 | `0.0625` |

**三个单元面板**（直接构造 `increasing` / `decreasing` 两 flag 的读数）：

| 单元 | `flag_increasing` | `flag_decreasing` | 标签 |
|---|---|---|---|
| `U01_const5` | `true` | **`true`** | `MONOTONIC_INCREASING` |
| `U02_flat_pair` | `true` | **`true`** | `MONOTONIC_INCREASING` |
| `U03_nan_mid` | `true` | `false` | `MONOTONIC_INCREASING` |

`U01`/`U02` **两方向 flag 同时为 `true`** ⟹ 独占决定性：取值只能由 `if (increasing)` 在前的**分支顺序**决定（`P-05` 成立）。

### 2.3 ±20% 网格（冻结值 `factorGroupCount = 5`、`minSize = 10`；±20% 取 `{4,5,6}` × `{8,10,12}`）

**21 个网格单元行**（9 非退化 × 2 面板 + 3 短截面），其中**冻结口径的 18 单元**逐条记录：

| 面板 | `gc=4` | `gc=5`（**默认**） | `gc=6` |
|---|---|---|---|
| `G01_grid_up`（非退化） | `MONOTONIC_INCREASING` ×3（`ms=8/10/12`） | `MONOTONIC_INCREASING` ×3 | `MONOTONIC_INCREASING` ×3 |
| **`G02_grid_flat_pairs`**（周期 2 取值） | **`MONOTONIC_INCREASING`**（spread `0.0`） | **`NON_MONOTONIC`**（spread `0.0046875`） | **`MONOTONIC_INCREASING`**（spread `0.0`） |

⟹ **同一份数据，仅因 `groupCount` 在默认值附近移动 ±20%，标签由 `MONOTONIC_INCREASING` 变为 `NON_MONOTONIC`（再变为 `MONOTONIC_INCREASING`）**；`minSize` 在 `{8,10,12}` 上**不改变**任何标签。

**补充面板 `G03_short_section`**（每日 9 样本）：`ms=8` ⟹ 纳入（`totalObservations = 18`）且给 `MONOTONIC_INCREASING`；`ms=10`/`ms=12` ⟹ 全部丢弃（`totalObservations = 0`、`cross_section_days = 0`）⟹ 标签 `N/A`。**这不是缺陷登记，只是披露 minSize 的纳入/丢弃边界**。

---

## 3. H0 四子句逐条判定

### 3.1 (i) 名实 —— **被证伪**（`K1`）

**承重实测 ①（`V04`，`Q04_flat_groups`）**：五组平均收益**逐位相等**（`0.03125` / `0.03125` / `0.03125` / `0.03125` / `0.03125`），`slope_is_zero = true`，标签 = **`MONOTONIC_INCREASING`**。

- 触发条件**不需要任何客户端输入**：默认 `groupCount = 5`、默认研究端点的 `RANK_IC_GROUP_COUNT = 5`。
- 五组**完全无区分度**（`long_short_spread = 0.0`）时，标签却断言**正向**方向性证据。

**承重实测 ②（`V05`，`U01_const5` / `U02_flat_pair`）**：`increasing` 与 `decreasing` **同时为 `true`**，`label_equals_increasing_branch = true` ⟹ 取值**由分支顺序决定**，而不是由「哪一侧的证据更强」决定。

**两者合起来构成「名实不一致」的充分证据**：标签语义是「组收益按组序单调递增」，而实现只在「**不存在严格反序对**」时置 `increasing = true`（`:226-249` 的 falsifier 只用**严格不等**）⟹ **零斜率（以及全部相等）落进「递增」**。

**对照（如实登记，对项目有利）**：`V01` 严格递增 ⟹ `SEMANTICS_FAITHFUL`；`V02` 严格递减 ⟹ `SEMANTICS_FAITHFUL`；`V03` 波动 ⟹ `SEMANTICS_FAITHFUL`。**名实在三条正常路径上成立**，杀点是**零斜率边界**。

**`reading_dependent = false`**（三条主张的读数一致）⟹ 计入 `K1`。

### 3.2 (ii) 非退化 —— **被证伪**（`K2`）

**承重实测（`V06`，`Q06_overgroup`）**：`groupCount = 20`、每日 10 个样本。

| 项 | 实测 |
|---|---|
| `groupedReturns` 长度 | **20**（`real_groups = 10` + 占位 10） |
| 占位组 | **10 个**满足 `observations == 0 && avgReturn == 0.0` |
| 真实 10 组序列 | `-0.0078125, -0.015625, -0.0234375, -0.03125, -0.0390625, -0.046875, -0.0546875, -0.0625, -0.0703125, -0.078125` ⟹ **严格递减、全为负** |
| 标签 | **`NON_MONOTONIC`**（`label_ne_na = true`） |
| `long_short_spread` | **`+0.0078125`**（**正号**，而真实序列**全为负**） |

⟹ 占位零（`avg = 0.0`）被当作**第 11 组的观测值**参与方向比较：`-0.078125 → 0.0` 构成一次**上升**，与 `0.0 → 0.0` 的相等一起，**制造出一个并不存在的「非单调」**。**退化输入产出具名方向断言**（此处是反向断言），并且**污染了标量 `long_short_spread` 的符号**。

**可达性（`V07`，冻结阶梯口径）**：

| 路径 | 判定 | 依据 |
|---|---|---|
| 公开请求字段 `ScoringBacktestRequest.factorGroupCount` | **可达** | 字段默认 5、**3 个候选校验注解全部未命中** ⟹ **无服务端校验** |
| TS 类型 | 可达 | `backtest.ts:12` `factorGroupCount?: number`（无约束） |
| 前端控件 | **不可达** | `ScoringBacktest.vue:36` `:min="2" :max="10"`（**仅 UI 缓解**，非契约约束） |
| 研究端点 `FactorResearchService` | 不可达 | 三处 `summarize` 调用点全部传常量 `RANK_IC_GROUP_COUNT = 5` |

⟹ 可达性等级 = **`REACHABLE_PUBLIC_API`**（经公开请求字段可达），**满足** `K2` 要求的 `≥ REACHABLE_PUBLIC_API`。

**可选路径安全性（如实登记，对项目有利）**：`V08`（`groupCount = 1`）⟹ `N/A`（`DEGENERATE_SAFE`）；`V09`（`groupCount = 0`）⟹ `N/A`（`DEGENERATE_SAFE`）；`V10`（`groupCount = -1`）⟹ `N/A`、**未抛异常**（`DEGENERATE_SAFE`）——`groupedAverageReturns` 的 `groupCount < 2` 早退守卫（`:92-118`）先于任何 `ArrayList` 容量分配。

**`K2` 的可达性定义条款（必须写明）**：`K2` 只要求「存在一个 `DEGENERATE_ASSERTS` 变体，其可达性 ≥ `REACHABLE_PUBLIC_API`」。本轮的 `REACHABLE_PUBLIC_API` 含义是「**经公开请求字段**（即 HTTP 请求体可传的字段）可达」，**不是**「经项目自己的前端界面可达」。**反事实披露**：若把这条收窄成「**仅经客户端字段可达 ⟹ 只算 `REACHABLE_CLIENT_INPUT`，不计入 `K2`**」，则 `K2` 不触发——**但 `total_verdict` 仍为 `REJECTED`**（`K1`、`K3` 均不依赖 `K2`）。该反事实**不改变本报告的任何结论**，此处写明只为不夸大可达性。

**aux 观测（明确**不**作承重，如实登记）**：`Q11_nan_return`（含非有限 `forwardReturn`）经 `summarize` **未被 IC 有限性过滤**、且被判 `MONOTONIC_INCREASING`；`U03_nan_mid` 显示 `NaN` 与任何数比较**恒假** ⟹ `NaN` 既不产生严格反序对、也不产生递增对。**可达性 = `SYNTHETIC_ONLY`**：生产调用点的收益由受守卫算术产生（`ScoringBacktestService.java:955-1015` 的入场守卫链：`rawEntryPrice <= 0 → null`、零成交 → `null`、开盘涨停 → `null` ⟹ `entryCost > 0` ⟹ `exitProceeds / entryCost - 1` 有限），**已核验**。⟹ **本轮不把 NaN 组均值计为任何 `K` 的证据**，只登记 `D-R013-09`。

### 3.3 (iii) 可重建 —— **被证伪**（`K3`）

**承重实测（`V11`，`Q07_round_collision`）**：

| 项 | 值 |
|---|---|
| 引擎五组均值 | `1.4E-4, 1.2E-4, 1.0E-4, 8.0E-5, 6.0E-5`（**严格递减**） |
| 引擎标签 | `MONOTONIC_DECREASING` |
| 载荷层 `round4` 后（`Math.round(v * 10000.0) / 10000.0`） | `1.0E-4; 1.0E-4; 1.0E-4; 1.0E-4; 1.0E-4`（**全部塌成同一值**） |
| 由载荷重建的标签 | **`MONOTONIC_INCREASING`**（五者相等 ⟹ 走 §3.1 的同一条零斜率路径） |

⟹ **同一个载荷，读者按「载荷自身携带的组均值」重建得到的标签，与引擎给出的标签不同**。载荷是 `round4` 之后的 `avg_return`（`FactorResearchService.java:598`）+ 一个 4 位取整后的标量，**不含任何全精度信息** ⟹ 标签**在载荷层面不可唯一重建**。这正是 J-3（L 档）的用法：判据只要求「**取整后的载荷不足以支撑该方向性结论**」，**不**要求任何改进方案、**不**引入任何阈值。

**加固证据（同一子句，`V12`/`V13`/`V14`，均 `CLAIM_ABSENT`）**：

- `V12`：`factor.ts:111` / `:202` 只有 `monotonicity: string` ⟹ **值域未声明**（三读数一致、`reading_dependent = false`）。⟹ 「定义域在契约中声明」这一半也**不成立**。
- `V13`：`FactorAnalysis` DTO**有** `groupedReturns`（12 字段 / 14 builder 方法）但**无** `monotonicity` ⟹ 走该 DTO 的消费方**拿不到标签**。
- `V14`：`FactorIcSummary` **两者皆无**（9 字段 / 10 builder 方法）⟹ 更弱。

**可达性**：`V11` 的面板是**合成**的（`SYNTHETIC_ONLY`）——但 `K3` 的冻结触发条件**不含可达性要求**（`K3` = 「∃ 变体判定为 `NOT_RECONSTRUCTABLE`」）。此处**如实登记**：`V11` 证明的是「**载荷—标签的重建关系在数学上不成立**」（一个纯粹的载荷充分性问题），**不**声称生产库中已存在这样一组组均值。

### 3.4 (iv) 不越界 —— **未被证伪**（`K4` 未触发，如实披露为**描述性**）

**实测（`V16` / `V17`）**：声明面（31 行上下文）与展示面（C-03…C-09 各面）**均未**出现 16 词表中的推断类词；`inference_claimed = false`。

**但两条读数不一致**：对同一批文本的**朴素整仓读数**命中 `true`（`readings = [true, false, false]`），而**收紧读数 0 命中** ⟹ `reading_dependent = true` ⟹ 按冻结 §12.3 **必须排除出 `K1` 候选集并在报告中降级为描述性**。

**判定**：`(iv)` **未触发**（负结论）。**不**得包装成发现，**不**得据此声称「项目应当做推断」（`review §7` 第 2 条、H0 边界声明）。

### 3.5 短路路径（逐条）

```
K5 = false  （row_sum = axis_total = variants_total = 21，grid_mismatch_count = 0，CANARY_PAIRS = 21）
K6 = false  （off_vocab = []，21/21 金丝雀通过，探针编译且两次运行逐字节相等，禁数扫描 0 命中，instrument_alerts = []）
K7 = false  （prereg sha256 未变；11/11 冻结文件 match；两次运行静态结果逐字节相等）
⟹ 不走 INCONCLUSIVE
K1 = true ⟹ total_verdict = REJECTED，h0_alive = False
```

`K4 = false` 被**如实登记**（见 §3.4），**不**为凑出「四杀」而放宽其读数口径。

---

## 4. 协议与冻结纪律要求的必答项

### 4.1 变体数 N 与轴数自检（协议 §5 第 7 步）

**N = 21**（`A1 = 5` / `A2 = 5` / `A3 = 5` / `A4 = 3` / `A5 = 3`），`MAX_VARIANTS = 21` ⟹ **未超上限**；`row_sum = axis_total = variants_total = 21`、`grid_mismatch_count = 0`。21 个变体逐条判定见 §3 与 `result.json` 的 `variants`。

**结果分布**：`SEMANTICS_FAITHFUL` 3（V01–V03）、**`SEMANTICS_GAPPED` 2（V04–V05）**、**`DEGENERATE_ASSERTS` 1（V06）**、`DEGENERATE_SAFE` 3（V08–V10）、**`NOT_RECONSTRUCTABLE` 1（V11）**、`CLAIM_ABSENT` 3（V12–V14）、`CONSISTENT_VOCAB` 1（V15）、`INFERENCE_NOT_CLAIMED` 2（V16–V17）、**`FALSE_CLAIM` 1（V18）**、`TRUE_CLAIM` 4（V19/V20/V21 + V07 的静态侧单独记 `DEGENERATE_ASSERTS`）。

> 注：`V07` 是**可达性变体**（A2 轴），判定 `DEGENERATE_ASSERTS` 用于承载「无服务端校验」这一**可达性前置事实**；它与 `V06` 同轴同判定，`K2` 只要一个即触发，**不重复计数**。

### 4.2 关键参数 ±20% 敏感性（协议要求**必做**）

| 参数 | 冻结值 | ±20% 取值 | 结果 |
|---|---|---|---|
| `factorGroupCount` | **5** | `{4, 5, 6}` | **标签在 `G02_grid_flat_pairs` 上随参数改变**（`INCREASING` → `NON_MONOTONIC` → `INCREASING`）；在 `G01_grid_up` 上 9/9 不变 |
| `minSize` | **10** | `{8, 10, 12}` | **不改变**任何面板在非退化网格上的标签；仅改变 `G03` 的**纳入/丢弃**（`ms=8` 纳入 18 观测 ⟹ `MONOTONIC_INCREASING`；`ms≥10` 全丢 ⟹ `N/A`） |

⟹ **披露**：标签对 `groupCount` 的**非单调敏感**（同一数据在默认值附近就能翻转）是本轮 `±20%` 敏感性的主要发现，已登记 `D-R013-08`。**不得**把这些组数/样本量当作「行业标准」（`review §7` 第 6 条）。

### 4.3 前视 / 幸存者偏差（`研究口径规范.md` §5）

**影响 = 0 / 0**。证据：装置只读**源码文本** + 喂**合成** `(score, forwardReturn)` 对；**零**行情/财务读取、**零**数据库连接、**零**成本重算。**明确声明**：合成面板**不含** T+1 建仓、涨跌停/停牌拒单语义 ⟹ 本轮**不检验**这些口径，**不得**外推。

### 4.4 读数敏感性三读数（冻结纪律第 3 条）

| 主张 | (a) 子串（大小写无关） | (b) 类型分派收紧 | (c) 人工逐字 | `reading_dependent` |
|---|---|---|---|---|
| `V12` 值域未声明 | 未声明 | 未声明 | 未声明 | **false** |
| `V16`/`V17` 未声称推断 | **命中（true）** | **0 命中（false）** | 0 命中 | **true** ⟹ 排除出 `K1`、降级描述性 |
| `V18` 披露粒度 | 1/3 块 | 1/3 块 | 1/3 块 | **false** |
| `V04`/`V05` 零斜率 | 一致 | 一致 | 一致 | **false** |
| `V06` 占位组 | 一致 | 一致 | 一致 | **false** |
| `V11` 可重建 | 一致 | 一致 | 一致 | **false** |

### 4.5 否定式声明用标识符级（冻结纪律第 4 条）

`V16`/`V17` 的谓词**不**用被否定对象的子串（**不**用「显著」「推断」这类词的**存在性**做否定），而要求**标识符级**命中；收紧读数因此为 0 命中。**该收紧使判定从「越界」翻转为「未越界」** ⟹ 这正是 `reading_dependent = true` 的来源，也是 `K4` **不**触发的原因（R012 教训 68/69 的直接沿用）。

### 4.6 判定事实的来源边界（冻结纪律第 5 条）

`instrument_alerts = []` ⟹ **所有判定事实均来自 11 个冻结文件之内**；根外文件命中 **0**。`B-03` 的容器识别在 `max_back = 400` 下解析到**真实方法** `buildRankIc`（`641-798`），并由另一条独立读数交叉确认：`buildRankIc` 的 map `block`（声明于 `:648`）同时拥有 `:650 group_count` 与 `:762 monotonicity`，而 `:790` 的 `cost_net_ic` 子块用**同一个** `RANK_IC_GROUP_COUNT` 常量构造自己的 summary。

### 4.7 探针确定性（冻结纪律第 6 条）

两次独立 JVM 运行输出 **23,482 B 逐字节相等**（`sha256 = 7894ed5d…`）；运行末尾清理 `R013Probe.class` 与 `R013Probe$PanelSpec.class`（`all_removed = true`）。

---

## 5. `P` 表核对（`R013_prereg.md` §9，逐条；**18 条**）

| `P` | 预测 | 实测 | 结果 |
|---|---|---|---|
| `P-01` | 严格递增 ⟹ `MONOTONIC_INCREASING` | `MONOTONIC_INCREASING` | **中** |
| `P-02` | 严格递减 ⟹ `MONOTONIC_DECREASING` | `MONOTONIC_DECREASING` | **中** |
| `P-03` | 波动 ⟹ `NON_MONOTONIC` | `NON_MONOTONIC` | **中** |
| `P-04` | 常量 ⟹ 标签 ∈ {递增, 递减}（零斜率被断言方向） | `MONOTONIC_INCREASING`、`zero_slope_asserted_direction = true` | **中** |
| `P-05` | 常量下两 flag 同真 ⟹ 取值由分支顺序定 | `flags_both_true = true`、`value_determined_by_branch_order = true` | **中** |
| `P-06` | `gc=20`、每日 10 样本 ⟹ 20 组、恰 10 组 `obs==0 && avg==0.0` | `grouped_size = 20`、`zero_avg = 10`、`zero_obs = 10`、`real = 10` | **中** |
| `P-07` | 上述标签 ≠ `N/A` | `NON_MONOTONIC` | **中** |
| `P-08` | `gc=1` ⟹ `N/A` | `N/A` | **中** |
| `P-09` | `gc=0` ⟹ `N/A`、`groupedReturns` 空**而** `totalObservations > 0` | `N/A`、`grouped_size = 0`、`totalObservations = 10` | **中** |
| **`P-10`** | **`gc=-1` ⟹ 抛 `IllegalArgumentException`** | **未抛异常**（`exception = null`）、`N/A`、`ok = true` | **不中 ⟹ `A-01`** |
| `P-11` | `FactorAnalysis` 无 `monotonicity` builder、有 `groupedReturns` | `has_monotonicity_builder = false`、`has_grouped_returns_builder = true` | **中** |
| `P-12` | `FactorIcSummary` 两者皆无 | 两者皆 `false` | **中** |
| `P-13` | `monotonicity` 无值域声明（仅 `string`） | `value_domain_declared = false`（两处 `:111` / `:202`） | **中** |
| `P-14` | 4 个发射面同词表 | `emitter_faces = 4`、`same_vocabulary = true` | **中** |
| `P-15` | 声明与展示面均未声称推断 | `inference_claimed_display = false`、`inference_claimed_root = false` | **中** |
| `P-16` | 存在一组组均值使 `round4` 后相等、全精度不等 ⟹ 重建标签 ≠ 引擎标签 | 引擎 `MONOTONIC_DECREASING` vs 重建 `MONOTONIC_INCREASING` | **中** |
| `P-17` | ±20% 网格在非退化面板上标签不变 | `G01` 9/9 `MONOTONIC_INCREASING`、18 单元中 G01 全同；**但 `G02` 出现 `INCREASING`/`NON_MONOTONIC` 两值** | **中**（预测只覆盖 `G01`；`G02` 的翻转是**预测外**的新增观测 ⟹ `D-R013-08`） |
| `P-18` | 既有测试未覆盖 5 类退化 | 三类读数全为 `false`（五类均未覆盖） | **中** |

**合计：17 中 / 1 不中（`P-10`）**。`P-17` 的实测**含一条预测外观测**（`G02`），**不**记为「不中」（预测文本只约束非退化面板 `G01`），但**必须**如实披露。

---

## 6. 附录：偏离与勘误（`A-01`…`A-05`）

### `A-01` `P-10` 预测不中：`groupCount = -1` **不抛异常**（**勘误，不改冻结文本**）

- 预测：`{"throws": "IllegalArgumentException"}`；实测：`{"exception": null, "label": "N/A", "ok": true}`。
- 成因（读码）：`groupedAverageReturns`（`FactorResearchEngine.java:92-118`）在 `groupCount < 2` 时**早退**返回空列表 ⟹ 负 `groupCount` **不会**走到任何 `new ArrayList<>(negative)` 路径。
- 影响：**无**。该预测**不参与任何 `K`**；`V10` 的判定仍是 `DEGENERATE_SAFE`（对项目**有利**的方向）。**冻结的 `P` 表逐字保留**，本节只作事后勘误（协议：事后更正只能进附录）。

### `A-02` / `A-03` / `A-04` 冻结锚点区间与实测行号不符（**勘误，逐字保留冻结区间**）

| `A` | 锚点 | 冻结区间 | 实测 `found_at` | 处理 |
|---|---|---|---|---|
| `A-02` | `A-01e` `if (increasing)`（F01） | `[246, 248]` | `[242]` | 只登记 `range_mismatch`，**不**事后改写预注册 |
| `A-03` | `B-07` `toFactorAnalysisDto`（F03） | `[1024, 1048]` | `[478, 1018]` | 同上 |
| `A-04` | `B-09` `class FactorAnalysis`（F04） | `[80, 92]` | `[79]` | 同上 |

三条**均不影响任何判定**：命中判定按「模式在文件内出现」计，`35/35` 命中；`range_mismatch` 只记录「冻结时预估的行号偏晚」。

### `A-05` CCW2024 载体的**档位**笔误：「A 档」应为「**C 档**」（**跨轮勘误，就地订正 + 本附录登记；不改冻结文本**）

- **事实**：CCW2024 = Cattaneo / Crump / Wang, *Beta-Sorted Portfolios*, **arXiv:2208.10974v3**（2024-11-12）。
  其载体为 **arXiv 预印本**：**无**同行评审、**无**期刊版、**无** DOI ⟹ 按本项目档位定义应为 **C 档**。
- **笔误**：本报告第 **4** 行（抬头「判据来源」）与第 **368** 行（§8 台账联动表）**两处**把载体写成 **「A 档」**；
  同一轮的 **`paper_ledger.csv`** 该行写的是 **C 档**、**`R013_prereg.md`** 亦为 **C 档** ⟹
  **只有本报告正文的两处写错**，且**不影响任何判定**（档位不参与任何 `K`，判据 J-1/J-2/J-3 一律标 `L` 档、
  J-4 标 `X` 档，与档位无关）。
- **处理**：①**就地订正**上述**两处**为「**C 档**」并标「勘误，见附录 `A-05`」；
  ②**不改** `R013_prereg.md`（**已冻结**，冻结文本一字未改）；
  ③**不改** `paper_ledger.csv` 该行（原本就是 C 档，无需改）；
  ④**不改** `progress.md` §1/§3 的 R013 行（原本就是 C 档）。
- **影响**：**无**。§1.3 的档位声明、§2 的机器化事实、§3 的四子句判定、§5 的 `P` 表核对**均不受影响**。

### 运行史汇总（`run.log` append-only，共 **6** 次运行，**全部保留**）

| # | 模式 | 结果 | 说明 |
|---|---|---|---|
| 1 | `static-only` | `INCONCLUSIVE` | 锚点 `32/35`、`appendix = 12`、`canary_failures = 2`、**`alerts = 2`**（`anchor_missing` ×3 + `block_method_not_found`）⟹ **仪器缺陷** |
| 2 | `static-only` | `INCONCLUSIVE` | 锚点 `35/35`、`appendix = 3`、`canary_failures = 2` ⟹ 改 `range_mismatch` 登记后 `alerts = 0`，但金丝雀仍 2 失败（**仪器缺陷**） |
| 3 | `static-only` | `INCONCLUSIVE` | `canary_pairs = 7`、`failures = 0`（**仪器门按设计拒绝下结论，非缺陷**） |
| 4 | `full` | `REJECTED` | `appendix = 7`、`bytes_equal = True`；`V05 = SEMANTICS_FAITHFUL` ⟹ `INJ` 未翻转（**仪器缺陷**） |
| 5 | `full` | `REJECTED` | `appendix = 5`；`V05 = SEMANTICS_GAPPED`（显式 `INJ` 键表修好后） |
| 6 | `full` | **`REJECTED`（终态）** | `appendix = 4`、`canary_pairs = 21`、`failures = 0`、`alerts = 0`、`bytes_equal = True`、`probe_cleanup = True` |

**本轮仪器缺陷清单（7 类，全部已修，全部留痕）**：①探针 `javac` 缺 `-cp target/classes`；②Windows 默认 GBK 使 subprocess 文本读取抛 `UnicodeDecodeError`（改 `encoding="utf-8", errors="replace"`）；③面板 `OBS` 行漏写日期导致 `ArrayIndexOutOfBoundsException`；④`enclosing_method` 的 `max_back = 120` 不足 ⟹ `block_method_not_found`；⑤`lastGrouped` 取错面板（改 `GROUPED` map 按 panel id 缓存）；⑥金丝雀 `INJ` 用泛型取反不改变判定（`V18`/`V19`）⟹ 改**显式按键表**；⑦`parse_probe` 丢掉 `PANEL|…|flags|<inc>|<dec>` 的第二字段且未做数值强转 ⟹ `V05` 误判 `SEMANTICS_FAITHFUL`、`P-05/P-06/P-09` 假不符。

> **`run.log` 未删改任何一次运行**（冻结纪律第 7 条）；上表的「仪器缺陷」全部发生在**冻结文本之后**，未触碰 `R013_prereg.md`。

---

## 7. 零生产代码改动：逐项证据

| 项 | 证据 |
|---|---|
| 写入范围 | 本轮**只新增** `research/paper_loop/rounds/R013_report.md` 与 `research/paper_loop/experiments/R013_monotonicity_label_audit/**`（`make_config.py` / `config.json` / `panel_R013.txt` / `probe/R013Probe.java` / `run_R013_monotonicity_audit.py` / `result.json` / `run.log` / `_scratch/**`），以及五份台账 |
| 生产代码 | **0 处改动**（`zero_production_changes = true`，由执行器对 11 个冻结文件重算 `sha256-32` 并与预注册值比对得出，`11/11 match`） |
| 冻结文件 | 11/11 未变 ⟹ **没有**改动 `assessMonotonicity`、`factorGroupCount` 校验、`factor.ts` 类型、任何 DTO 或 Mapper（`prereg §14` 第 9 条） |
| 探针编译产物 | 编译进 `modules/backend-java/target/classes`（**不在源码树内**），运行末尾**清理**（`R013Probe.class` + `R013Probe$PanelSpec.class`，`all_removed = true`） |
| 测试 | **未新增、未修改、未跳过**任何测试；两套测试收口时**实跑复测**（非沿用上轮结论）：`mvn -o -q test`（`modules/backend-java`）⟹ 按 64 个 surefire XML 聚合 **350 tests / 0 failures / 0 errors / 0 skipped**，退出码 0；`python -m pytest -q`（`modules/compute-python`）⟹ **343 passed, 3 warnings in 45.75s**，退出码 0 ⟹ 与 R010/R011/R012 基线逐项一致（详见 `progress.md` §2/§5） |
| 禁数 | `forbidden_scan` 命中 **0**（探针小数字面量守卫仅豁免冻结公式副本的 `10000.0`） |

---

## 8. 台账联动（五份，逐份说明「改了什么 / 为什么不改」）

| 台账 | 动作 | 内容 |
|---|---|---|
| `paper_ledger.csv` | **追加 1 行** | `CCW2024`：**C 档**载体〔**勘误**：本行原文误写「A 档」，见附录 `A-05`〕（arXiv:2208.10974v3，**CC BY-NC-ND 4.0 ⟹ 只入库元数据、不复制正文**）、判据 J-1/J-2/J-3 为 **L 档**、J-4 为 **X 档不可移植**、结论 `REJECTED`、失败原因分类「口径结构（**被杀死的是项目自己的 H0**：`monotonicity` 标签的名实/非退化/可重建/不越界）」 |
| `decision_queue.md` | **追加 9 条** `D-R013-01`…`D-R013-09` + 汇总表 9 行 | 全部属协议 **§7 record-only tier**（改算法 / 扩值域 / 改 TS 类型 / 改 DTO / 加服务端校验 / 改展示披露），**只登记、不实施**；其中 `D-R013-01`（零斜率具名方向）与 `D-R013-03`（占位 0 参与形状与价差）**显式标注**与既有 `D-R006-07` / `D-R006-04` 是**同一代码事实的更强读法**（防重复研究 + 诚实归因） |
| `blocked_queue.md` | **追加 1 条** `B-R013-01` + 汇总表 1 行 | 载荷**端到端形状**的观测需 Spring 容器 + DB；同型先例 `B-R010-01` / `B-R011-01` / `B-R012-01` |
| `progress.md` | **回填 §1 / §2 / §4 / §5 / §6 / §7** | 轮次位置、计数（论文 16 / 已复现实验 13 / 变体 **283 → 304** / BLOCKED / PENDING_USER / 生产代码改动 0 / 新增测试 0）、R013 收口记、下一轮候选 |
| `shadow_registry.md` | **不改** | **理由（`prereg §13` 要求显式说明）**：本轮**不提出任何新因子**，`ADOPTED = 0`、`影子因子 = 0`；R013 是**只读审计轮**，其产出是负知识与登记项，**没有任何**可进入影子流程的因子口径 ⟹ 改该表会违反「影子先行」与「agent 不得自行提升」两条冻结约束 |

> **`paper_ledger.csv` 的诚实披露（本报告附录补充，不改历史行）**：该文件**第 16 行存在一个空行**
> （位于 `MENKVELD2024` 与 `HXZ2020` 之间，是 R012 追加两行时的格式瑕疵，此前未被发现，
> 本轮用 `csv.reader` 遍历时以 `IndexError` 暴露）。**本轮不修改历史行**，
> 因为「已归档记录」属既往轮的落盘产物，按本项目的冻结纪律（勘误只能追加、不得重写历史）。
> 追加 `CCW2024` 时改用 `csv.writer(quoting=QUOTE_MINIMAL, lineterminator='\n')` 并断言
> 「24 列 / 无 CRLF / 共 18 行」；该空行同时登记在 `progress.md` §3 表下的勘误说明中。
>
> **`progress.md` §3 索引勘误（R013 补记）**：`MENKVELD2024`（R011 判据来源）与 `HXZ2020`
> （R012 判据来源）**两轮均只写进各自 `report.md` 与 `paper_ledger.csv`、漏登进 §3 归档表**，
> 而 §2 计数已把它们算入「已归档论文」。R013 收口时**补登两行 + 新增 `CCW2024` 一行**
> （与 R005 对 `B-R004-01`、R010 对 `B-R009-*` 的处置同型）⟹ 表内 **16** 行 = 计数 **16**。
> **该勘误只补索引、不改任何报告正文。**

> **R013 不消耗「剩余候选族」清单**：本轮审计的 `monotonicity` 标签本就在 R012 §6 列出的剩余候选族内（`factor_return` 族），清单其余项**原样保留**。**新增已覆盖维度**：「一个具名语义标签的名实 / 退化契约 / 可重建性 / 推断边界」⟹ **未来轮次不得重复审计** `monotonicity` 的这四子句（防重复研究，协议 §4）。

---

## 9. 负面清单复核与本轮**不做**的事

### 9.1 `R013_review.md` §7 八条（逐条）

| # | 禁止项 | 本轮是否遵守 |
|---|---|---|
| 1 | 不得引用 CCW2024 任何数值作阈值/容差/合格线 | **遵守**（禁用清单 0 命中；本轮**不存在**任何阈值型判定） |
| 2 | 不得把 L 档判据升级为「项目必须做 uniform inference」 | **遵守**（`(iv)` 只要求**不声称**；`K4` 未触发且未包装） |
| 3 | 不得用 J-4（X 档）评价本项目 | **遵守**（J-4 全轮未使用） |
| 4 | 不得修改 `assessMonotonicity` / 校验 / TS 类型 / DTO ⟹ 只登记 | **遵守**（§7 零改动证据；9 条 `D-R013-*` 全部 record-only） |
| 5 | 不得把「标签在退化输入下不具名」写成「因子结论是错的」 | **遵守**（§0 边界声明第 1 条：本轮**零**有效性结论） |
| 6 | 不得把组数/样本量/网格当「行业标准」 | **遵守**（§4.2 显式声明） |
| 7 | 不得复用 R011 的 `group_count` 结论、R012 的 14 块清单与三子句 | **遵守**（`group_count` 仅作**参数网格**出现；未复用 R012 块清单；§6 未引用 R012 结论） |
| 8 | 不得因连续十二轮无产出而降低杀死条件，或把 `NOT_VIOLATED` 硬凑成 `REJECTED`（反之亦然） | **遵守**（`K4` 未触发被如实登记，**未**为凑四杀放宽读数；同时**未**把 `REJECTED` 弱化成 `INCONCLUSIVE`） |

### 9.2 `R013_prereg.md` §14 第 9–12 条（逐条）

| # | 禁止项 | 本轮是否遵守 |
|---|---|---|
| 9 | 不得修改 `assessMonotonicity` / `factorGroupCount` 校验 / `factor.ts` 类型 / DTO 字段或 Mapper | **遵守**（§7；已登记为 `D-R013-01`…`D-R013-07`） |
| 10 | 不得把常量序列情形叙述成「用户看到的因子是单调的」或反之 | **遵守**（§3.1 只断言**标签**在零斜率下具名，**未**对任何因子作有效性叙述） |
| 11 | 不得据本轮任何量级设定阈值/容差/合格线 | **遵守**（`panel_R013.txt` 的数值全部是合成面板输入，不是判定阈值） |
| 12 | 不得把 `NOT_VIOLATED` 硬凑成 `REJECTED`（反向亦然） | **遵守**（见 9.1 第 8 条） |

### 9.3 与前几轮的重叠：**主动披露**（诚实归因）

| R013 杀点 | 前几轮是否有同一代码事实 | 关系（如实说明） |
|---|---|---|
| **K1**（零斜率仍断言方向） | **有**：`D-R006-07`（R006，`A7a`，判定 `REGISTERED_ONLY`，**不进 R006 总判定**） | **同一代码事实**（`assessMonotonicity` 的 falsifier 只用严格不等）。R013 的差别是**被审计对象换成了标签自己的语义**，因此同一事实**首次成为承重杀点**（并新增两项证据：`slope_is_zero` 的读数、两 flag 同真的单元构造）。**不**声称这是首次发现。 |
| **K2**（`groupCount > n` 占位 0） | **有**：`D-R006-04`（R006，空组伪造 0 ⟹ `long_short_spread` 退化为 `0 − G1`） | **同一代码事实**（`:180-183`）。R013 的新增观测是**占位零参与方向断言**（真实递减序列被判 `NON_MONOTONIC`、spread 由负变正 `+0.0078125`），而 R006 测的是**spread 的构成**。可达性结论与 R006 一致。 |
| **K3**（`round4` 使标签不可重建） | **部分相邻**：`D-R007-09`（R007，`round4` 的 `NaN`/`Inf` 与伪零宽 CI，属**post-hoc 未预注册**） | **不同后果**：R007 测的是**区间**的伪零宽，R013 测的是**标签**在取整后**不可重建**（预注册的 `P-16` 事先预测了这一反例）。两轮都指向 `round4`，但**不同载荷族、不同子句**。 |
| `V18`（标签块披露粒度） | **部分相邻**：R012 `K2`（承重块缺**样本域**声明） | **不同键集**：R012 测样本域（universe），R013 测**组数 / 每组样本量**。R013 **未**复用 R012 的 14 块清单与三子句。 |

⟹ **结论**：K1/K2 是「**旧事实的新承重读法**」，K3 是「**新事实**」。三条杀点**互相独立**（K1 不需客户端输入；K3 与可达性无关），且都已按各自的冻结触发条件实测。

### 9.4 本轮**明确不做**的事

1. **不**改任何生产代码、**不**改任何 DTO / 前端 / 契约 / 数据库结构（全部登记）；
2. **不**提出任何新因子、**不**进影子流程、**不**提升任何因子；
3. **不**启用真实交易、**不**读写任何真实凭证、**不**访问任何真实账户；
4. **不**读行情/财务数据、**不**连数据库 ⟹ 本轮**零**前视/幸存者偏差影响；
5. **不**把「标签不具名」外推为「因子无效」；
6. **不**据本轮给出任何阈值、容差、合格线；
7. **不**操作 `E:\Project` 下本仓库以外的任何目录。
