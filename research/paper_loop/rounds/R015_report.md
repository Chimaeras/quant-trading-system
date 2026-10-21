# R015 报告：研究样本漏斗**计数族**（`funnel`）的『键—量唯一 / 同名同义 / 退化可分辨 / 口径标注忠实』审计

> 本文件是 R015 的收口报告。R015 是**本轮循环的最后一轮**（用户指示：完成本轮后停止迭代研究）。
> 预注册冻结于 `R015_prereg.md`（sha256 前 32 位 `dacf03bf70cb71c7f20bc75ba9337880`，37,861 B，**运行前后一致**），
> 审前评审见 `R015_review.md`（`9a45b53a50dea4de4427809472ebe2ab`，29,637 B）。**本文件不得改写任何冻结文本。**

---

## 0. 一句话结论

**R015 判定 = `REJECTED`（`H0` 被证伪），承重杀点全部落在 `K1`（键—量唯一被证伪）。**

被审计对象是项目自己的一组**契约主张** `H0`：研究样本漏斗的 6 个计数键「一键一量、同名同义、退化可分辨、口径标注忠实」。
实测（冻结扫描根内，Java 探针**直接调用生产** `private buildResearchFunnel`，316 个受控场景 + 公开 API 端到端载荷）：

1. **`feature_valid_count` 与 `valid_observations` 永远承载同一个变量** `validObservations`（316/316 场景差 **0**，300 组受控三重差 **0**）；
2. **`selector_item_count` 与 `total_observations` 永远承载同一个变量** `totalObservations`（同上，差 **0**）；
3. 回退分支 `if (universeCount == 0) universeCount = totalObservations;` 使 **`universe_count` 在回退时变成 `selector_item_count` 的复本**
   ⟹ 6 个计数键在回退模式下**只承载 2 个独立数值**（`M2` 下 15 个键对中 **4 对**永不分离）。

即：**「两个按键名看似独立、实为同一变量」**——这是 R014 教训 68（同名不同量）的**反向形态（同量不同名）**，
在 R001–R014 十四轮中**零覆盖**（详见 §5 撞车全表）。

**同时必须写清的边界**（§1.2）：被指控的是**键名设计与披露**，**不是**「项目算错了两次」，**也不是**「因子无效」。
计数**数值本身在本装置下正确**（`value_faithful = true`，18/18 网格单元）；且 **`K2` 未触发**：
`universe_days_with_snapshot == 0` 与「回退已执行」在全部受测输入上**精确等价**（316/316）
⟹ 回退**可分辨**，只是**未具名**（`FALLBACK_UNNAMED`，披露不足 ≠ 不可分辨）。

---

## 1. 口径、单元与边界

### 1.1 被审计对象（`subject`）

| 面 | 载荷 | 键数 | 可达性 |
|---|---|---|---|
| **主对象** | `research()` 顶层 `funnel`（`buildResearchFunnel`，`F02:845–877`） | **13** | `REACHABLE_PUBLIC_API`（探针直接调用 + 端到端 `research()`） |
| 同层重复 | `research()` **顶层**键 `cross_section_days` / `total_observations` / `valid_observations` | 3 | `REACHABLE_PUBLIC_API` |
| 副对象 | snapshot 面 `funnel`（`buildSnapshotFunnel`，`F02:605–628`） | **13** | `buildSnapshotFunnel` 可反射直调（§2.2b）；`snapshotResearch` 端到端**不可达**（`B-R015-02`） |
| 副对象 | `daily_funnel[]` 行（`F02:397–406`） | 8 | `REACHABLE_PUBLIC_API`（随 `research()`） |

`judgement_scope`：**只判公开 API 返回的载荷内部**（键、量、值、文本），**不判**数据库真实分布，**不判**前端渲染。

### 1.2 `H0` 的边界（`R015_prereg.md` §1 **强制复述**）

1. 被指控的是**键名与披露的设计**；**不得**叙述为「项目算错两次」或「因子无效」——计数值在本装置下**正确**。
2. 本轮**只**在**合成面板**上测量；**不得**外推为真实库上的发生频率或影响面（`B-R015-02` 未测量）。
3. **`rule_score` 是 0–100 截面百分位，不是上涨概率**——本轮**不涉及**该口径，复述以备误读。
4. **冻结口径**（佣金万三 / 印花税万五 / 单边滑点万二 / 次日开盘入场 / 第 h 日收盘退出 / 拒单样本不得删除 / T+1）
   **一律不变**，本轮**不重算**任何成本、**不修改** `ResearchConvention`。

### 1.3 判据来源与禁用数值

- 判据来源 **DMNU2019**（*A Transaction-Cost Perspective on the Multitude of Firm Characteristics*, RFS 33(5):2180–2222，DOI `10.1093/rfs/hhz085`）
  只取 **J-2**（口径/参数必须显式挂在其所修饰的载荷上）与 **J-4**（成本覆盖的观测数必须**可读、可独立核验**）；
  **J-1/J-3 显式登记为「本轮不使用」**（其对象已被 R010 `K1` 判过 ⟹ 再判属重复研究）；**J-X 全部禁用**。
- 判据档位**全部为 `L`**：**只支持做法种类，不支持任何阈值**。本轮**未**据论文任何数值设阈值、容差或合格线。
- **`forbidden_scan`（§2.1）机器化核验**：扫描 `R015_report.md` 正文 + 全部 `_scratch` **读数文件**，18 个判别子串，
  **命中 0、豁免 0**（即报告未复现任何论文禁用数值，故**未启用** `forbidden_scan_exempt`）；
  另加**仪器自扫描**（`run_R015.py` / `make_config.py` / `R015Probe.java`，哨兵区块内跳过）**命中 0**。

> **扫描作用面的一处显式说明（防事后争议）**：论文源材料 `_scratch/dm2019.txt`（正文）与 `_scratch/oa_dm.json`（OpenAlex 元数据）
> **按定义排除**在扫描之外——它们是**源**（论文自己当然含自己的数值），不是本轮的**读数**文件；
> 若把它们纳入扫描，会得到 185 条命中，全部是**源材料自述**，与「结论泄漏」无关。
> 该排除**逐条**记录在 `result.json` 的 `gates.forbidden_excluded_source_material`，**不做静默**。

### 1.4 判定单元与冻结自检

| 项 | 值 |
|---|---|
| 冻结扫描根 | **13 个文件**，逐位 sha256-32 **全部一致**（`frozen_ok = true`，含本轮新增的 `F13 ResearchConvention.java`） |
| 预注册完整性 | `prereg_ok = true`（运行前后 sha256-32 相同） |
| 场景池 | **316**（`M1` 无回退 **251** / `M2` 回退 **65**；主面板 7 + 随机 300 + 边界 9） |
| 变体数 | **20**（5 轴：A1=5 / A2=3 / A3=6 / A4=4 / A5=2；行和 = 轴和 = 20） |
| 金丝雀 | **20 对**（每变体 1 对：洁净哨兵须不变 + 注入哨兵须变），**20/20 通过** |
| 确定性 | 5 个面板**各运行两次**，输出**逐字节相等** |
| 探针 | 编译进生产包、只读、末尾清理字节码（`git status` 复核无残留） |

---

## 2. 机器化事实

### 2.1 静态侧（源码文本，机械抽取）

- `F02:859–861` 回退分支：`if (universeCount == 0) { universeCount = totalObservations; }`
- `F02:866–869` **两键同变量**（逐字）：
  `funnel.put("feature_valid_count", validObservations);` / `funnel.put("valid_observations", validObservations);`
  `funnel.put("selector_item_count", totalObservations);` / `funnel.put("total_observations", totalObservations);`
- `F02:863–865` `universe_count = universeCount`（`Σ cross_section_count`，回退时被替换）；
  `cross_section_days = latestPerKey.size()`（回退下**不**被替换 ⟹ 与 `universe_count` 仍可分离）
- `F02:842–844` javadoc 具名**三个阶段** `universe / feature_valid / tradable`，而载荷把前两个阶段各拆成 2 个键 ⟹ 键数与阶段数不是一一对应
- `F02:870–875` `tradable_count = null` + `tradable_status = "UNAVAILABLE"`（**可分辨**）；
  `universe_source = "sparse_selector_result"`（**常量**）；`note` **不提回退**
- 冻结片段（**从 `F02` 逐字抽取，非手写**）：legacy `coverage` 分母 `allItems.size()` vs snapshot `coverage` 分母 `rowsTotal`；
  legacy `cross_section_days` = key 数 vs snapshot = `rowsByDate.size()`（`F02:156/159/613/616`）

### 2.2 运行时侧

#### (a) 探针**直接调用生产** `private buildResearchFunnel(Map, int, int)`（反射，只读）

| 观测 | 结果 |
|---|---|
| `feature_valid_count` ≡ `valid_observations` | **316/316 场景差 0**；300 组受控三重差 **0** |
| `selector_item_count` ≡ `total_observations` | **316/316 场景差 0**；300 组受控三重差 **0** |
| `M1`（无回退，251 场景）永不分离键对 | **恰 2 对**（上面两对）；不可测键对 **0** |
| `M2`（回退，65 场景）永不分离键对 | **恰 4 对**（上面两对 + `{universe_count, selector_item_count}` + `{universe_count, total_observations}`）；不可测 **0** |
| 值域非退化（**只在 300 组随机面板内**统计） | `total_observations` **10** 个不同值 / `valid_observations` **169** / `universe_count` **160** / `cross_section_days` **9** / marker **9** |
| 回退标记等价性 | `universe_days_with_snapshot == 0` **⟺** 回退已执行：**316/316 恒等价**（`marker_violations = 0`） |
| 回退分支代数一致性 | 回退 ⟹ `u == total`（0 违例）；非回退 ⟹ `u == Σcross`（0 违例）⟹ `branch_violations = 0` |
| 触发域 | `cross ∈ {null, 缺键, 0, 负数, 非 Number}` **全集合**（外来 spec 场景 207 个）上标记仍精确等价 |
| 退化输入 | 空输入 3 场景：6 键**全部为 0**、**不抛异常**；`total = 0` 而某 `cross > 0` **19 场景**：`u = Σcross > 0` 而 `si = 0`（两键由不同变量填充 ⟹ **反面对照**） |
| 单键依赖性（去掉 marker） | 用 `u == total` 冒充回退判定 ⟹ **29 个场景误分类**（见证 marker **不可省**） |
| 具名回退标记 | 键名扫描 `named_key_hits = []`；字符串值扫描 `text_token_hits = {}`；`universe_source` **仅在 1 个取值**上出现 ⟹ **未具名**（`FALLBACK_UNNAMED`） |

#### (b) 端到端公开 API `research()`（主面板）

顶层：`cross_section_days = 6`、`total_observations = 72`、`valid_observations = 60`、`coverage = 83.33`、`metric_scope = COST_APPROX`；
`funnel`：`universe_count = 7200`、`universe_days_with_snapshot = 6`、`feature_valid_count = 60`（= `valid_observations`）、
`selector_item_count = 72`（= `total_observations`）、`tradable_count = null`、`tradable_status = "UNAVAILABLE"`、`universe_source = "sparse_selector_result"`。
**两别名对在公开载荷上仍逐位相等** ⟹ 别名不是探针直调造成的假象。

#### (c) `top_vs_funnel`（同载荷内同名键）

4 个面板（`main` / `b8` / `b10` / `b12`）上，顶层与 `funnel` 的
`cross_section_days` / `total_observations` / `valid_observations` **逐元素相等** ⟹ `SAME_NAME_SAME_QUANTITY`。

#### (d) 副对象活读数（`buildSnapshotFunnel` 反射直调，**冻结后追加的仪器读数**，见 §6 `A-06`）

13 键实测：`universe_source = "feature_snapshot_row"`（**常量**）、`metric_scope = "COST_APPROX"`（**无条件**，与 `tradable` 是否可用无关）、
`tradable_status` 随 `executableAvailable` 在两值间切换、`coverage = 66.67`（`scoredValid/rowsTotal`）、`partitions_skipped = ["2026-01-09(RUNNING)"]`（结构）。
`daily_funnel[]` 行 8 键，其中 `raw_observations` / `cost_approx_observations` / `executable_observations` **逐一具名**。

### 2.3 端到端不可达点（如实记录）

`snapshotResearch` 在本装置上的真实失败点（**非**猜测）：
`java.lang.NullPointerException: Cannot invoke "com.quant.service.DataSnapshotService.requireSnapshot(String)" because "this.dataSnapshotService" is null`
—— `DataSnapshotService` 与 `FeatureSchemaRegistry` 是**具体类**，无法用 `Proxy` 桩（`F02:96–108` 九个 `private final` 字段 + Lombok 9 参构造器）
⟹ **snapshot 面端到端不可达**，`daily_funnel` 在 snapshot 面上的形状**未测量** ⟹ 记 `B-R015-02`。

### 2.4 ±20% 网格（冻结值：`RANK_IC_MIN_CROSS_SECTION_SIZE = 10`、`RANK_IC_GROUP_COUNT = 5`）

- **网格**：3（计数输入 `×0.8/×1.0/×1.2`）× 3（边界面板：每日标的数 **8/10/12**，即把同一常量踩在两侧）× 2（`M1`/`M2`）= **18 个单元**。
- **`RANK_IC_GROUP_COUNT` 不变动**：`buildResearchFunnel` **不读**该常量（键清单中无任何分组量）⟹ **静态不变性论证**，已在预注册 §10 写明。
- 每单元记录：6 个计数键值、15 键对中「**本单元内相等**」的键对数、marker 值、入参、`a1_flip`/`a3_flip`/`value_faithful`。
- **结果：18/18 单元 `ok = true`，`sensitivity_flips = []`，`parameter_dependent = false`**；
  跨网格「永不分离」键对数 = **4**（与全体场景一致）。
- **灵敏度门结论**：无任何单元把 V01–V04 从 `KEY_VALUE_NOT_UNIQUE` 翻成 `KEY_VALUE_UNIQUE`，
  也无任何单元把 V09/V10 从 `FALLBACK_MARKER_EXACT` 翻成 `FALLBACK_UNDISCLOSED`
  ⟹ **`K1` 不标参数依赖**。**本对象不是缩放驱动的边界现象。**

### 2.5 前视 / 幸存者偏差（`研究口径规范.md` §5）

- 本轮**不产生任何收益序列**、**不读任何真实行情/财务数据**、**不访问数据库**（`repo_calls` 只命中 4 个**只读**查询方法，
  数据由探针桩返回）⟹ **前视偏差影响 = 0、幸存者偏差影响 = 0**。
- **明确声明**：合成面板**不含** T+1 建仓、涨跌停/停牌拒单、`NOT_BUYABLE`/`SUSPENDED`/`NOT_SELLABLE`/`DELISTED` 语义
  ⟹ 本轮**不检验**这些口径，**不得**把结论外推为「真实库上的漏斗形状或影响面」。

---

## 3. `H0` 四子句逐条判定

### 3.1 (i) 键—量唯一 —— **被证伪**（`K1`）

- **V01/V02**（可分性矩阵）：`M1` **恰 2 对**、`M2` **恰 4 对**永不分离；不可测键对 0（值域非退化，排除「因量退化而假不分离」）。
- **V03**（300 组受控三重穷举）：两别名对差 **0**；`total`/`valid` 各自取到 **10 / 169** 个不同值。
- **V04**（反向锚点）：在 **316** 个场景中**不存在**任何输入使两别名对相异 ⟹ 反向锚点**失败**（确认不可分离，**不是**仪器假象）。
- **V05**（端到端）：公开 `research()` 载荷上两别名对仍逐位相等（见 §2.2b）；文本读数**有歧义** ⟹ `reading_dependent = true`，
  **不参与 `K1`**（详见 §6 `A-02`）。

**⟹ `K1` 触发：`KEY_VALUE_NOT_UNIQUE`（V01/V02/V03/V04 四个变体，全部 `REACHABLE_PUBLIC_API` 且非读数依赖）。**

### 3.2 (ii) 同名同义 —— **未被证伪**（`SAME_NAME_SAME_QUANTITY`）

`top_vs_funnel` 4/4 面板逐元素相等；跨面同名（`coverage` 分母不同、`cross_section_days` 定义不同）为**预注册降级项**（§4.4），
只作静态登记（`V07/V08 = NOT_REACHED_STATIC_ONLY`，可达性 `SYNTHETIC_ONLY`），**不升格**。

### 3.3 (iii) 退化可分辨 —— **未被证伪**（`FALLBACK_MARKER_EXACT`；**披露不足另计**）

- **V09/V10**：`universe_days_with_snapshot == 0` 与「回退已执行」**316/316 精确等价**（含全触发域）⟹ `K2`（`FALLBACK_UNDISCLOSED`）**不触发**。
- **V11/V12**：**未具名**（`FALLBACK_UNNAMED`）——`universe_source` 是常量、`note` 不提回退；
  去掉 marker 后用 `u == total` 冒充回退会**误分类 29 个场景** ⟹ marker **不可省**。
  **但**「未具名」≠「不可分辨」：`K2` **只**认后者（预注册 §14 第 15 条），本条**降级为描述性登记** `D-R015-01`。
- **V13/V14**：退化输入下 6 键一致（全 0 / 两键分属不同变量的反面对照）⟹ `DEGENERATE_KEYS_CONSISTENT`。

### 3.4 (iv) 口径标注忠实 —— **一面忠实、一面不忠实**（**预注册降级项**，只登记）

- **V17**（`tradable_count = null` 与 `tradable_status = "UNAVAILABLE"` 并列 ⟹ 「不可用」与「为 0」**可分辨**）= `SCOPE_LABEL_FAITHFUL`；
- **V18**（`daily_funnel` 行内三口径计数**逐一具名**）= `SCOPE_LABEL_FAITHFUL`；
- **V15**（legacy `funnel` 块内**无**任何成本后观测计数或成本参数，却带 `metric_scope = COST_APPROX`）= `SCOPE_LABEL_UNFAITHFUL`；
- **V16**（顶层 `metric_scope = COST_APPROX` 而 `rank_ic.metric_scope = RAW`）= `SCOPE_LABEL_UNFAITHFUL`。

**这两条按预注册 `§4.4` 是降级项**（`metric_scope` 跨面口径已由 **R004** 判过）⟹ **不作为本轮发现**，只登记 `D-R015-02` 并**点名 R004**。

### 3.5 短路路径（逐条）

`K4 = []`（无承重词落在非 `REACHABLE_PUBLIC_API` 变体上）· `K5 = false`（行和 20 = 轴和 20 = 声明 20；金丝雀对 20）·
`K6 = false`（表外词 0、20/20 金丝雀通过、5 面板两次逐字节相等、禁数扫描 0 命中、仪器自扫描 0 命中）·
`K7 = false`（13 文件冻结哈希一致 + 预注册未变）。
**⟹ 未短路；`K1` 触发 ⟹ `total_verdict = REJECTED`，`h0_alive = false`。**

---

## 4. 协议与冻结纪律要求的必答项

| 要求 | 本轮 |
|---|---|
| 变体数 `N` 披露 | **20**（5 轴），表外词 0 |
| 关键参数 ±20% 敏感性 | **见 §2.4**：18 单元，`parameter_dependent = false` |
| 前视 / 幸存者偏差影响（`研究口径规范.md` §5） | **0 / 0**（§2.5） |
| `rule_score` 表述 | 本轮**未**把 `rule_score` 表述为上涨概率（全文无此表述） |
| 冻结口径是否改动 | **未改动**（`ResearchConvention.java` 在扫描根内且哈希一致） |
| 生产代码改动 | **0**（全部改动落在 `research/paper_loop/**`，`git status` 复核） |
| 预注册是否事后修改 | **未修改**（sha256 前后一致） |
| 影子因子 | 本轮**不提出**任何新因子（新增 **0** 条、提升 **0** 条、累计 **0**）⟹ `shadow_registry.md` **加一段（0 条）留痕**，**未新增任何影子候选**（与预注册措辞的偏离见 §6 `A-08`） |

---

## 5. `P` 表核对（`R015_prereg.md` §9；**20 条，命中 19/20**）

| `P` | 预测 | 实测 | 判 |
|---|---|---|---|
| `P-01` | `M1` 恰 2 对永不分离 | 2 对（两别名对） | ✅ |
| `P-02` | `M2` 恰 4 对 | 4 对（+ `u` 与两计数键） | ✅ |
| `P-03` | 300 组差恒 0 且值域非退化 | 差 0/0；`total`=10、`valid`=169 个不同值 | ✅ |
| `P-04` | 不存在使别名对相异的输入 | 316 场景 0 反例 | ✅ |
| `P-05` | 别名**未声明**（`ALIAS_UNDECLARED`） | **`ALIAS_DECLARED`**（读数 b 命中 4 行） | ❌ **不中**（§6 `A-02`） |
| `P-06` | 顶层 vs `funnel` 逐元素相等 | 4/4 面板相等 | ✅ |
| `P-07` | `coverage` 跨面分母不同 ⟹ 静态登记 | `NOT_REACHED_STATIC_ONLY` | ✅ |
| `P-08` | `cross_section_days` 跨面定义不同 ⟹ 静态登记 | 同上 | ✅ |
| `P-09` | marker ⟺ 回退 | 316/316 等价 | ✅ |
| `P-10` | 全触发域上仍等价 | 5 类外来 spec、207 场景，0 违例 | ✅ |
| `P-11` | 无具名回退标记 | 键名 0 命中、文本 0 命中、`universe_source` 单值 | ✅ |
| `P-12` | 去 marker 后仅凭其余键无法判定 | 29 个误分类场景 | ✅ |
| `P-13` | 空输入 6 键全 0、不抛异常 | 3 场景一致 | ✅ |
| `P-14` | `total=0` 而 `cross>0` 时两键仍一致 | 19 场景一致（反面对照） | ✅ |
| `P-15` | legacy 块无成本内容 ⟹ 不忠实（**降级**） | 键集内 0 个成本类键 | ✅ |
| `P-16` | 顶层 `COST_APPROX` vs `rank_ic` `RAW`（**降级**） | 实测两值 | ✅ |
| `P-17` | `tradable` 可分辨 | `null` + `UNAVAILABLE` | ✅ |
| `P-18` | `daily_funnel` 三口径具名 ⟹ 更忠实 | 8 键，3 个 `*_observations` | ✅ |
| `P-19` | 20 对金丝雀全过 | 20/20 | ✅ |
| `P-20` | 两次逐字节相等 / 矩阵稳定 / 键名互换不变 | 全真 | ✅ |

> **`P-05` 不中已按预注册 §9 处置：只进附录 `A-02`，**未**修改 §9 表、**未**放宽 `K` 表。**

---

## 6. 附录：偏离与勘误（`A-01`…`A-10`）

### `A-01` 冻结前**更换审计对象**（旧对象作废，**已记录在 `R015_review.md` §1.5**）

R014 收口时列的四个候选（`cost_net_ic` / `long_short_spread` / `icir` / `sample_size`）在本次冻结前的撞车分析中**全部退休**：
`cost_net_ic` 的四个子句分别已被 **R004 `K3`**、**R004 §4.3/§6 差异 3**、**R012 `D-R012-02`**、**R007 `A8a`**
与 **R014 `B-07`** 覆盖 ⟹ 若照原计划冻结即构成**重复研究**。处置 = 换对象为 **`funnel` 计数族**（`R015_review.md` §1.5 如实登记）。
**本项不影响本轮判定**，只是选题沿革。

### `A-02` `V05` 的**读数歧义**（`P-05` 不中的原因；**标 `reading_dependent = true`**）

三种读数：(a) 子串（大小写无关）**同行共现两别名键名** ⟹ **0 命中**；
(b) 收紧读数（别名键名 + 定义/计数语义词）⟹ **4 命中**，全部在 `F10`（**界面标签**）：

| # | 文件:行 | 命中行（截断） |
|---|---|---|
| 1 | `F10:198` | `有效观测（得分>0）：{{ result.valid_observations }}` |
| 2 | `F10:197` | `总观测：{{ result.total_observations }}` |
| 3 | `F10:131` | `观测数：{{ blk.total_observations }}` |
| 4 | `F10:237` | `观测数：{{ result.rank_ic.total_observations }}` |

**诚实结论**：这 4 行是**单键的界面标签**，**不是**「两键区别的声明」⟹ 读数 (b) **把「有标签」误当「有声明」**，它并没有测到 `V05` 需要的性质。
按读数 (a) 应为 `ALIAS_UNDECLARED`，按读数 (b) 为 `ALIAS_DECLARED` ⟹ **判定被读数方式改变**，
按预注册 §12 第 3 条：**标 `reading_dependent = true`、排除出 `K1` 候选集、降级为描述性**（登记 `D-R015-03`，点名 **R013 `D-R013-07`** 披露粒度）。
**`K1` 的成立不依赖 `V05`**（V01/V02/V03/V04 已足够）。

### `A-03` 冻结后**追加的仪器读数**（`SNPF`，如实声明为**冻结后追加**）

`buildSnapshotFunnel` 的**活读数**（§2.2d）在预注册冻结之后加入装置。它**不参与任何 `K` 门、不参与任何变体判定**，
只把 `R015_prereg.md` §4.2 的 B 组锚点从「静态阅读」**补强**为「实测载荷」。冻结门 `K7` 只校验预注册与 13 文件哈希，二者均未变。
**声明：这是冻结后追加的**，不作「预注册内已包含」的事后叙述。

### `A-04` `V07/V08` 的片段由**硬编码改写为从冻结扫描根逐字抽取**（仪器修正）

首版把两条 `coverage` / `cross_section_days` 片段**手写**在 runner 里，违反预注册 §12 第 5 条（判定事实必须来自冻结扫描根内）。
已改为从 `F02` **逐字抽取**，并把「两条片段**确实取自冻结根且互不相同**」写成 `V07/V08` 的判定前提（前提缺失 ⟹ `INSTRUMENT_RED`）。
**修正后 `P-07`/`P-08` 命中**。

### `A-05` 首版**四处仪器缺陷**（金丝雀/可达性/网格/键序，全部修正后 20/20 通过）

1. `V01/V02/V05` 的注入哨兵**方向写反**（注入后仍返回原词）⟹ `canary_inj` 假失败；
2. 可达性比较**用字符串字典序**（`"SYNTHETIC_ONLY" < "REACHABLE_PUBLIC_API"` 为 `False`）⟹ 改 `REACH_RANK` 数值序；
3. ±20% 网格首版**静默丢弃**「模式与预期回退状态不符」的单元（`continue`）⟹ **不诚实**；
   改为**逐单元如实记 `a1_flip`/`a3_flip`/`value_faithful`**，不合格单元进 `sensitivity_flips`（实测为空）；
4. `V06` 的检查器**遍历了整个输入列表**（含洁净哨兵元组）⟹ 修参数形状。

> **仪器门纪律**：以上缺陷在**任何结论**之前修正；首版运行时 `K6 = true` ⟹ 判定为 `INCONCLUSIVE`，**未**据此下任何结论（预注册 §12 第 9 条）。

### `A-06` `V12` 指标首版**语义写错**（已修正）

首版 `no_marker_viol` 收集「非回退且 `u != total`」的场景（那是**正常情形**，不是误分类），会**高估**问题规模。
已改为 `(u == total) != fallback` 的**真误分类**集合（= 29 个，与非回退反例子集**恰好相同**）⟹ 与 `P-12` 一致。

### `A-07` 冻结锚点行号**逐条复核无偏移**（只登记）

`R015_prereg.md` §4.1/§4.2/§4.3 全部行号与实测一致（含 `:859–861` 回退、`:866–869` 两键同变量、`:605–628` snapshot 键集、`:397–406` `daily_funnel`）。

### `A-08` 与预注册的**一处措辞偏离**：`shadow_registry.md` **加了 R015 段（0 条）**（已登记理由）

`R015_prereg.md` §13 原文对该台账写的是「本轮**不提出任何新因子** ⟹ 预期**不改**（报告中显式说明理由）」。

**实际处置**：**加了**一段 `## R015（2026-09-15）：本轮**无**影子因子`（内容 = 0 条因子 + 判定 + 理由 + 累计 0 + 提升请求 0），
即「**因子条目数没变**（0→0），但**登记表多了一个轮次段**」。

**为什么仍然加**：本登记表的**自身惯例**（R012/R013/R014 段，其中 R012/R013 两段还是 R014 **补登**的）
是「**每轮一段**」。若 R015 不收段，则「累计影子因子 = 0」这句话在表内**没有本轮证据**
（读者无法区分「本轮真的零新增」与「本轮忘了写」）—— 这正是 R014 收口时才发现的 R012/R013 **漏登**教训。
两害相权取**留痕**：**加段**，并在本附录**如实登记为与预注册措辞的偏离**。

**边界（必须与结论一起读）**：该偏离**只涉及台账留痕**，
① **不新增任何影子因子**、② **不新增任何提升请求**、③ **不改任何 `K` 门**、④ **不改任何生产代码**（本轮 `git diff` 仍只有 `research/paper_loop/**`）。
报告 §4 与 §8 的表内说明已同步改为**实际动作**（不再写「不改」）。


### `A-09` 发现并补登 **R014 的一处索引缺口**：`progress.md` §3 缺 `CZ2022` 行（**只补索引，不改 R014 报告正文**）

**事实**：`R014` 在 `progress.md` §3「已归档论文与结论」表下的补记中写了一句
「R014 新增 `CZ2022` **一行**（§2 计数已同步为 17）· **本轮无漏登**」。
R015 收口时逐行复查该表：**表内 16 行**（`LSY2019` / `LR2018` / `NMV2016` / `LWZ2021` / 两篇初筛筛掉 + **11** 篇判据来源），
**没有** `CZ2022` 行 ⟹ **R014 的「本轮无漏登」这句自述与文件实际状态不符**。

**处置（与 R005 对 `B-R004-01`、R010 对 `B-R009-*`、R013 对 `MENKVELD2024`/`HXZ2020` 的处置同型）**：

1. R015 **补登 `CZ2022` 一行**（内容取自 `rounds/R014_report.md`，并在该行**显式注明**「索引勘误（R015 补登）」）；
2. R015 同时新增 **`DMNU2019` 一行**（本轮判据来源）；
3. **只补索引、不改 R014 任何报告正文**（冻结纪律：已收口轮次的正文不得事后重写）；
4. 补登后表内 **18** 行 = `progress.md` §2 计数 **18**（**5** 篇本地实验或初筛筛掉 + **13** 篇判据来源）。

**为什么这条不算「跑题」**：R015 的收口动作之一就是**核对「计数 = 表内条数」**（R005/R010/R013 三轮都因此补登过索引）。
本轮如实暴露了**上一轮的自述缺陷**，并把它写进附录而不是悄悄补上 —— 这正是本循环「诚信优先于整洁」的处置方式。


### `A-10` `forbidden_scan` 的**子串假阳性**：首版 `A-09` 因写入项目内部论文 id 触发表中第 **8** 条判别串（**如实登记；已把失败的运行留在 `run.log`**）

**事实（机器读数，逐条给出，不省略）**：

| 项 | 值 |
|---|---|
| 触发时点 | `A-09` 写成后**第一次重跑**（该次运行已**如实留在 `run.log`**，未删改） |
| 命中文件 | `rounds/R015_report.md` |
| 命中行 | `:325` 与 `:333`（**均为 `A-09` 正文中列举归档表行的那两句**） |
| `pattern_index` | **7**（0 起算），即 `R015_prereg.md` §2.1 表中第 8 条判别串 |
| 命中成因 | 该串是**四位年份**；我在句子中写入了项目**内部论文标识符** `FR` + 该年份（Fernald & Rogers 那篇**初筛即筛掉**的论文的 `paper_id`），**与论文数值无关** |
| 后果 | 该次运行 `K6 = true` ⟹ `total_verdict = INCONCLUSIVE`（**仪器门按设计拒绝下结论**） |
| 处置 | ①**只改我自己的措辞**，把该句里的论文 id 换成「两篇初筛筛掉」（**事实不变、信息不减**：缺 `CZ2022` 行这件事与行数 16/17/18 **全部保留**）；②重跑；③把这次命中与修法**写进本附录**；④**不**去改冻结的 §2.1 词表、**不**加豁免条目、**不**缩小扫描面（`rounds/` 仍在扫描面内） |

**为什么这不算「放宽杀死条件」**：被修的**不是**判据、阈值、`K` 门或扫描面，而是**我自己的行文**；
判别串表**一字未改**、扫描面**未缩小**、`K6` 的语义**未动**。失败的运行**保留在 `run.log`** 可供复核，
本附录也把它**当成仪器缺陷如实登记**（同型先例：**R011 `D-R011-02`** 判据词表的子串假阳性）。

**可复用结论**：凡「扫描面里包含**由 agent 新写的文本**」的装置，都必须预期**项目内部标识符**
（论文 id、常量名、年号）与**判据禁用串**发生子串碰撞；碰撞时**改自己的措辞 + 登记**，
**不得**通过删豁免、缩扫描面或改词表来「变干净」。

---

---

---

---

## 7. 描述性登记 `D-R015-*`（**只登记、不实施**；协议 §7 record-only）

| 编号 | 内容 | 前轮点名 | 处置 |
|---|---|---|---|
| `D-R015-01` | 回退**未具名**：`universe_source` 为常量、`note` 不提回退；去 marker 后有 29 个可误分类场景 | —（本轮首次） | **只登记**；加具名标记/改 `universe_source` 属 §7 record-only |
| `D-R015-02` | legacy `funnel` 带 `metric_scope = COST_APPROX` 但块内无成本内容；顶层与 `rank_ic` 的 `metric_scope` 不同 | **R004** | **降级**，不写作本轮发现 |
| `D-R015-03` | 两别名键（`feature_valid_count`/`valid_observations`、`selector_item_count`/`total_observations`）**无任何说明其区别的文本** | **R013 `D-R013-07`** | **仅描述性**（读数歧义，§6 `A-02`） |
| `D-R015-04` | 前端 `factor.ts:52–60` **只声明 7 键**，未声明 `universe_days_with_snapshot`/`valid_observations`/`total_observations`/`cross_section_days`/`universe_source`；`coverage: number` 无单位说明 | **R009 `A5b`**（`:830` 有 `:810` 无） | **降级**：类型缺键已在 R009 登记 |
| `D-R015-05` | `coverage` 是**百分比刻度**（`×100`），`FactorResearch.vue:408–419` 的阈值 `80/50` **与之一致** ⟹ **无阈值错配** | — | **明确登记为「不得写成发现」**（我此前的假设已被读数否证） |

**本轮登记 0 项新因子、0 项口径变更、0 项阈值变更、0 项 API/DB 变更。**

---

## 8. 台账联动（五份，逐份说明「改了什么 / 为什么不改」）

| 台账 | 动作 |
|---|---|
| `paper_ledger.csv` | **追加 1 行**：DMNU2019（载体哈希、判据档位 `L`、`X` 档禁用清单、本轮判定 `REJECTED (K1)`） |
| `shadow_registry.md` | **加一段（0 条）**：`## R015（2026-09-15）：本轮**无**影子因子`（判定 / 理由 / 累计 0 / 提升请求 0 / 零生产改动 / 收工登记）。**未新增任何影子候选**；与预注册「预期不改」的措辞偏离已在 §6 `A-08` 如实登记 |
| `blocked_queue.md` | **追加 2 行**：`B-R015-01`（海外检索单通道）、`B-R015-02`（真实库漏斗形状 / snapshot 面端到端未测量） |
| `decision_queue.md` | **追加 5 行**：`D-R015-01`…`D-R015-05`（全部 `record-only`，等用户决策） |
| `progress.md` | **追加 R015 行** + §1 **收工结论**（用户明确停止） |

---

## 9. 负面清单复核与本轮**不做**的事

### 9.1 `R015_review.md` §7 十二条（逐条）

全部遵守。重点复核：**未**修改生产代码/载荷键/DTO/前端/`ResearchConvention`/`docs/研究口径规范.md`；
**未**把「两键同变量」叙述成「算错两次」或「因子无效」；**未**据本轮量级设阈值；**未**启用真实交易或索取真实凭证；
**未**操作本仓库以外的任何仓库；**未**删除历史数据。

### 9.2 `R015_prereg.md` §14（第 13–19 条，逐条）

13 ✅ 未改任何键名/取值/发射位置（只登记）· 14 ✅ 逐字保留「数值正确 vs 键名设计」区分（§1.2、§3.1）·
15 ✅ 未把 `FALLBACK_UNNAMED` 升格为 `FALLBACK_UNDISCLOSED`（§3.3，`K2` 未触发）·
16 ✅ 降级项逐条点名 R004/R009/R011/R012/R013/R014（§7 表）· 17 ✅ 未设任何阈值/合格线 ·
18 ✅ 未把 `NOT_VIOLATED` 硬凑成 `REJECTED`（`K1` 有 4 个独立变体证据），也未因十四轮无采纳产出而放水 ·
19 ✅ 未把合成面板结论外推为真实库频率或影响面（§2.5、`B-R015-02`）。

### 9.3 与前几轮的重叠：**主动披露**（诚实归因）

`cost_net_ic` / `long_short_spread` / `icir` / `sample_size` 四个候选**全部退休**（`A-01`）；
`coverage`/`cross_section_days` 跨面、`metric_scope`、TS 缺键、min-size 丢日**全部标为降级并点名前轮**（§7）。
**本轮承重结论（`K1`）的 3 条机制在 R001–R014 中零覆盖**——这一点在冻结前已逐轮核对（`R015_review.md` §1.5 / §5 撞车全表）。

### 9.4 本轮**明确不做**的事

不改任何键名或披露文本；不给回退加具名标记；不把别名对合并；不改前端类型声明；
不据本轮结论调整任何打分/风控/仓位/交易逻辑；不引入新数据源；不启用真实交易。

---

## 10. 零生产代码改动：逐项证据

- `git status` 在本轮全部改动**只**出现在 `research/paper_loop/**`（`rounds/R015_report.md`、`experiments/R015_funnel_count_audit/**`）；
- 13 个冻结文件 sha256-32 **逐位一致**（`frozen_ok = true`），覆盖后端 `FactorResearchService` / `FactorResearchEngine` /
  `ScoringBacktest*` / 两个 Controller/Test / 前端 `factor.ts` / `backtest.ts` / `FactorResearch.vue` / `ScoringBacktest.vue` / `ResearchConvention.java`；
- 探针编译产物在运行末尾**已清理**（`cleanup bytecode: R015Probe.class, R015Probe$Stub.class, R015Probe$Fixture.class`），
  `target/` 无残留（且 `target/` 在 `.gitignore` 首行）；
- 探针**只读**：未调用任何写入方法，未触碰 `target/classes` 内既有 `.class`。

---

## 11. 收工说明（本轮特有）

1. R015 是**最后一轮**：用户明确指示「帮我完成本轮之后就停止迭代研究」⟹ 触发协议 §10 的**合法停止条件**「用户明确停止」。
2. 收工时**未放宽任何 `K` 门**，**未**为凑产出调整判定方向（预注册 §12 第 11 条）。
3. 阶段总结 `stage_summary_R011_R015.md` 与本报告同批提交，其中**如实登记** R005 的 5 轮检查点**未生成独立文件**这一历史缺口。
4. 本轮的**累积产出为零个新因子、零项策略变更**——这与「十五轮全部证伪」一致，**是正常结果**，不是失败；
   净产出是**负知识**（哪些「看起来独立」的键其实是同一个量）与**一份可复用的仪器**（316 场景 + 20 金丝雀 + 18 单元网格）。
