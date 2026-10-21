# R015 预注册（**冻结**）

> **冻结声明**：本文件在**任何 R015 实验代码**（`make_config.py` / `R015Probe.java` / 执行器）与**任何实测读数**之前写成。
> 冻结后**不得**修改本文件的任何判定条件、变体、词表、`K` 表、`P` 表与条件阈值；
> 实测不符只进 `rounds/R015_report.md` 附录 `A-xx`（**勘误**），**绝不**回写本文件。
> 判据来源：**DMNU2019**（DeMiguel / Martín-Utrera / Nogales / Uppal，*A Transaction-Cost Perspective on the
> Multitude of Firm Characteristics*，*Review of Financial Studies* **33(5):2180–2222**，DOI `10.1093/rfs/hhz085`
> ⟹ **载体 A 档**、green OA、`is_retracted = false`）——检索、初筛、适配性七问、撞车分析与判据抽取见 `rounds/R015_review.md`。
> **本轮为收工轮**（用户于 2026-09-15 明确「完成本轮后停止迭代研究」）⟹ 协议 §10 的「用户明确停止」条件成立；
> 但**本轮的判定纪律不因收工而放宽**（协议 §8 硬红线第 9 条）。
> 冻结时仓库 HEAD：`c33ceabc2a82a03087a457e4b1eeab81b3b8a854`。

---

## 1. 被审计对象（不是论文）

**被审计对象 = 项目载荷里「样本漏斗」计数族的逐元素键值与口径标签**：

| 面 | 块 / 位置 | 键（冻结清单） | 生产者 |
|---|---|---|---|
| **主对象** | `factor_return.funnel`（legacy） | `universe_count` / `universe_days_with_snapshot` / `cross_section_days` / `feature_valid_count` / `valid_observations` / `selector_item_count` / `total_observations` / `tradable_count` / `tradable_status` / `tradable_note` / `universe_source` / `metric_scope` / `note` | `FactorResearchService.buildResearchFunnel`（`F02:845-877`），调用点 `F02:164` |
| 顶层同类键 | `factor_return` 顶层（**与 funnel 同名**） | `cross_section_days` / `total_observations` / `valid_observations` / `coverage` | `F02:156-160` |
| 第三面 | `unifiedReport.by_horizon[k].funnel` | 同上 13 键 | `F02:221-222` |
| **副对象** | `snapshot.funnel`（完整快照面） | `universe_source` / `partitions_reusable` / `partitions_skipped` / `cross_section_days` / `rows_total` / `scored_valid` / `coverage` / `label_version` / `exec_status_breakdown` / `tradable_status` / `tradable_note` / `metric_scope` / `note` | `F02:605-628`，调用点 `F02:454` |
| 副对象（逐日） | `snapshot.daily_funnel[]` 行 | `signal_date` / `rows_total` / `scored_valid` / `raw_observations` / `cost_approx_observations` / `executable_observations` / `labels_matched` / `labels_missing` | `F02:397-406` |

**H0（四子句，与 `review §6.2` 逐字一致）**：

> **(i) 键—量唯一**：每个**计数键名**各对应**恰一个独立统计量**；**不得**两个语义不同的键名由**同一个变量**填充，
> 从而使读者误以为得到了**两次独立核验**；
> **(ii) 同名同义**：**同名键**在**同一载荷内的不同块**、以及**不同发射面**上**同量同义**，或其差异被**显式标注**；
> **(iii) 退化可分辨**：当某计数的取值改由**替代量**（回退/兜底/占位）填入时，载荷必须**显式**使该退化**可分辨**；
> **不得**让替代值与被替代量在数值上**看起来自洽**，以致读者无法判断该键此刻统计的是什么；
> **(iv) 口径标注忠实**：块上的口径标签（`metric_scope` / `tradable_status`）必须**忠实于该块的量**。

**判定单元**：上述块在**合成面板**（与受控反射调用）上的**逐元素键值与标签**（不是论文的任何量）。

**`judgement_scope`（冻结）**：本轮**只**判「**键名—变量的对应关系**」与「**退化/占位是否可分辨**」，
以及**由这两面直接导出的**披露面（是否具名、是否有别名说明）。**不**判：任何数值的统计性质与统计显著性、
`rank_ic`/`pearson_ic`/`hac_std`/`ci_*`/`monotonicity`/`long_short_spread`/`cost_net_ic` 的任何字段、
两个数组的任何元素、任何阈值/容差/验收线、任何因子或策略的有效性。

**移植链披露（冻结，防越界解读）**：DMNU2019 的判据本体落在**成本/换手/组合**面上，与本对象**不是同一评价层级**；
本轮移植只到**做法种类**级（`J-2`「口径必须显式挂在载荷上」+ `J-4`「成本覆盖的观测数必须可数、可独立核验」⟹
「漏斗各层计数必须各对应一个独立统计量、退化时必须可分辨」）。**这是类比移植，不是论文的直接主张**；
报告中**不得**表述为「论文要求漏斗如此」。

**边界（先写死）**：
1. 本轮**不**评估任何数值的**正确性**——即使判定 `KEY_VALUE_NOT_UNIQUE`，被指控的是**键名设计与披露**
   （两个名字报了同一个量），**不是**「项目算错了」；该区分在报告与判定证据中**逐字**保留；
2. 本轮**不**判 TS 契约完整性（`factor.ts` 未声明 5 个后端键属 **R009 `A5b`** 对象族）⟹ 只作边界说明；
3. 本轮**不**判跨面同名键的价格基准/分母差异（属 **R012 `D-R012-02`**）与顶层口径标签（属 **R004 `D-R004-02`**）
   ⟹ 见 §4.4 的**预注册降级**；
4. 本轮**不**判最小截面过滤的丢弃日（属 **R011** 与 **R014 `V17`**）。

---

## 2. 判据（**只支持做法种类**，不支持任何阈值）

| 判据 | 内容 | 档位 | 用于 |
|---|---|---|---|
| **J-2** | **口径/参数必须显式挂在其所修饰的载荷上**：`κ_{i,t} = y_t·z_{i,t}`，取值来源要写清；换模型要单独做稳健性（式 (6)，PDF p11 / 印刷页 10；稳健性 PDF p66 / 印刷页 IA-3） | **L** | A1-V05（别名是否被声明）、A4 全轴 |
| **J-4** | **成本只能作用于可交易的观测；换手是成本传导的先决条件** ⟹ **成本实际覆盖的观测数必须可读、可独立核验**（PDF p4 / 印刷页 3；PDF p17 / 印刷页 16） | **L** | A1 全轴（键—量唯一）、A3 全轴（退化可分辨） |
| **J-1** | 成本施加在**交易**上、**逐期**（式 (7)(8)，PDF p12 / 印刷页 11） | **L** | **本轮不使用**（其对象是成本如何进入收益计算 ⟹ 已由 **R010 `K1`** 判过，再判属重复研究） |
| **J-3** | **净评估的对象是组合量**（PDF p11–p12 / 印刷页 10–11；PDF p24 / 印刷页 23） | **L** | **本轮不使用**（被审计对象**不含**任何组合量或净收益量） |
| **J-X** | 论文全部数值与构造（`y_t` 轨迹、`z_{i,t}` 参数化、成本量级 bp、换手与降幅百分比、显著性计数、估计量/检验/组合构造、样本区间与市场） | **X（不可移植）** | **不使用**（§2.1 的 `forbidden_scan` 机器化核验） |

**禁用清单（先写死）**：`review §7` 十二条**全部**沿用；另**禁止**把 `J-2`/`J-4` 升级为
「项目**应当**公开/补齐某字段」「项目**应当**改口径披露」这类**要求**（协议 §7 record-only）。

### 2.1 `forbidden_scan`（**机器化核验**：命中 ⟹ `K6` ⟹ `INCONCLUSIVE`）

在 `R015_report.md` 正文与全部 `_scratch` 读数文件中扫描以下**判别性子串**（大小写无关）：

`24.09` / `6.71` / `72.15` / `0.0025` / `0.006` / `Brandt` / `1980` / `2002` /
`180 bp` / `100 bp` / `60 bp` / `35 bp` / `bp/` / `1/√K` / `theta` / `多空对冲组合` /
`联合显著` / `孤立显著`

> 说明：以上是**论文特有**的数值/术语判别串；扫描**只**用于阻止论文数值被挪进本轮结论。
> 若命中的是**引用论文以声明其被禁用**的语境（如 §4.1 的 `X` 档清单本身），该命中在报告中**显式申明**为
> `forbidden_scan_exempt` 并给出该行出处；**`review`/`prereg` 两份文件不在扫描范围内**。

---

## 3. 冻结扫描根（**只读**；共 **13** 个文件）与冻结哈希

| # | 文件 | 字节 | sha256（前 32 位） |
|---|---|---|---|
| F01 | `modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java` | 14,429 | `efbf0678aabb2780b95c893e715ece9c` |
| F02 | `modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java` | 56,806 | `b7c93af0e03c0d8006c4d228e0047877` |
| F03 | `modules/backend-java/src/main/java/com/quant/service/ScoringBacktestService.java` | 52,264 | `a0e6cf738bb60d39145f90b43f975349` |
| F04 | `modules/backend-java/src/main/java/com/quant/dto/ScoringBacktestResponse.java` | 6,348 | `40a7982b60c8d7cfd1391e5405e3b1bd` |
| F05 | `modules/backend-java/src/main/java/com/quant/dto/ScoringBacktestRequest.java` | 1,401 | `01b85daef92b2cc5309fabb8e926f2d4` |
| F06 | `modules/backend-java/src/main/java/com/quant/controller/FactorResearchController.java` | 2,822 | `79bb33f56b857532286d0863345816b3` |
| F07 | `modules/backend-java/src/test/java/com/quant/service/backtest/FactorResearchEngineTest.java` | 10,960 | `42aa831eea4b45bdcc220ca905ca4faa` |
| F08 | `modules/web/src/api/factor.ts` | 6,591 | `3f8fe7e80c55cfb219736594811a6337` |
| F09 | `modules/web/src/api/backtest.ts` | 13,140 | `c3e4bfcd47c302c26cfbb541c79ffb4b` |
| F10 | `modules/web/src/views/factor/FactorResearch.vue` | 27,171 | `c59c81fc3933b5ff58607695cb2f8b7a` |
| F11 | `modules/web/src/views/backtest/ScoringBacktest.vue` | 60,185 | `05427ebc02bf392ae935713bced71102` |
| F12 | `modules/backend-java/src/test/java/com/quant/service/FactorResearchServiceTest.java` | 15,356 | `33dca6899b16d871da11d5455ed5a424` |
| **F13** | `modules/backend-java/src/main/java/com/quant/research/ResearchConvention.java` | **5,684** | **`08e4823811aadc759fc531d8404fc5b8`** |

- **合计 13 个文件**；`K7`（冻结门）要求 **13 个 sha256 全部不变**。
- **F01–F12 的 12 项哈希与 `R014_prereg.md` §3 逐位相同**（2026-09-15 实测）⟹ 这是「**R014→R015 之间零生产漂移**」的证据（报告 §5 复核）。
- **F13 为本轮新增**（因为「成本口径」出现于被审块的标签 `metric_scope = COST_APPROX`，冻结常量必须可核验）；
  它**不**进入任何变体的判定输入，只作**常量溯源**。
- **冻结扫描根之外**的文件只能作 `corroboration`（**旁证**）：其失败走 `instrument_alerts` 通道并**触发 `K6`**，
  **绝不**驱动任何变体的判定词（R012 教训 70 / R013–R014 沿用）。
- 论文侧载体（`experiments/R015_scratch/dm2019.pdf`）**不属于**本扫描根，哈希见 `R015_review.md` §1.4，只作**判据溯源**。

---

## 4. 冻结锚点表

### 4.1 实现锚点（A 组）——**主对象**

| 锚 | 文件:行 | 内容（冻结摘要） |
|---|---|---|
| A-01 | F02:845-849 | `buildResearchFunnel(Map<String,SelectorResult> latestPerKey, int totalObservations, int validObservations)`（`private`；三个入参名即「变量名」） |
| A-02 | F02:850-858 | `int universeCount = 0; int daysWithUniverse = 0;` + `for (r : latestPerKey.values()) { Integer cross = extractCrossSectionCount(r); if (cross != null && cross > 0) { universeCount += cross; daysWithUniverse++; } }` |
| **A-03** | **F02:859-861** | `if (universeCount == 0) { universeCount = totalObservations; }` ⟹ **静默回退**（承重面 `K2` 的机制） |
| **A-04** | **F02:866-867** | `funnel.put("feature_valid_count", validObservations);` 与 `funnel.put("valid_observations", validObservations);` ⟹ **两个键名、同一个变量**（承重面 `K1` 的机制） |
| **A-05** | **F02:868-869** | `funnel.put("selector_item_count", totalObservations);` 与 `funnel.put("total_observations", totalObservations);` ⟹ **同上**（承重面 `K1` 的第二对） |
| A-06 | F02:863-865 | `universe_count` / `universe_days_with_snapshot` / `cross_section_days = latestPerKey != null ? latestPerKey.size() : 0` |
| A-07 | F02:870-872 | `tradable_count = null` / `tradable_status = "UNAVAILABLE"` / `tradable_note = "EXECUTABLE tradable funnel unavailable without feature snapshot + TradabilityPolicy"` |
| A-08 | F02:873 | `universe_source = "sparse_selector_result"` —— **字面常量**（不随回退与否变化） |
| A-09 | F02:874-875 | `metric_scope = METRIC_SCOPE_COST_APPROX`；`note = "tradable_count 需统一 TradabilityPolicy；当前仅披露 selector 候选漏斗"` —— **`note` 不提回退** |
| A-10 | F02:842-844 | javadoc：「研究样本漏斗（sparse selector_result）：**universe / feature_valid / tradable**」—— **具名三阶段**（`feature_valid` 是**被命名的阶段**，而载荷为它发了**两个键**） |
| A-11 | F02:879-888 | `extractCrossSectionCount`：`r == null \|\| r.getFactorSnapshot() == null → null`；`v instanceof Number n → n.intValue()`；**否则 `null`**（非 Number 一律视为「无快照」） |
| A-12 | F02:156-160 | 顶层：`cross_section_days = latestPerKey.size()` / `total_observations = allItems.size()` / `valid_observations = validCount` / `coverage = allItems.isEmpty() ? 0.0 : Math.round(validCount * 10000.0 / allItems.size()) / 100.0` |
| A-13 | F02:136-144 | `validCount` 的计数规则：`it.getFinalScore() != null && it.getFinalScore() > 0` |
| A-14 | F02:164 / F02:221-222 | 两处调用点：`research()` 传 `(latestPerKey, allItems.size(), validCount)`；`unifiedReport()` 传**同型**实参（`validCount` 由 `filter(score>0).count()` 现算） |

### 4.2 实现锚点（B 组）——**副对象（snapshot 面）**

| 锚 | 文件:行 | 内容 |
|---|---|---|
| B-01 | F02:605-628 | `buildSnapshotFunnel(rowsTotal, scoredValid, crossSectionDays, reusableDays, skippedDates, execBreakdown, executableAvailable, labelVersion)` |
| B-02 | F02:610-613 | `universe_source = "feature_snapshot_row"`；`partitions_reusable = reusableDays`；`partitions_skipped = skippedDates`（**数组**）；`cross_section_days = crossSectionDays` |
| B-03 | F02:614-617 | `rows_total = rowsTotal`；`scored_valid = scoredValid`；`coverage = rowsTotal == 0 ? 0.0 : Math.round(scoredValid * 10000.0 / rowsTotal) / 100.0` |
| B-04 | F02:620-624 | `tradable_status = executableAvailable ? METRIC_SCOPE_EXECUTABLE : "UNAVAILABLE"`；`metric_scope = METRIC_SCOPE_COST_APPROX`（**无条件**） |
| B-05 | F02:625-626 | `note` 明写 `rows_total` / `scored_valid` 的**定义与计数规则**（`final_score>0`）⟹ **比 legacy 漏斗的 `note` 更具名** |
| B-06 | F02:397-406 | `daily_funnel[]` 行八键（含 `raw_observations` / `cost_approx_observations` / `executable_observations`）—— **逐口径观测数具名** |
| B-07 | F02:443-449 | snapshot 顶层：`cross_section_days = rowsByDate.size()` / `rows_total` / `scored_valid` / `coverage`（同一公式、同一分母） |
| B-08 | F02:454-456 | `funnel` / `daily_funnel` 两个键的发射点 |
| B-09 | F02:70-71 | `RANK_IC_GROUP_COUNT = 5`、`RANK_IC_MIN_CROSS_SECTION_SIZE = 10`（**本轮只用于静态不变性论证**，见 §10） |
| B-10 | F01:140-142 | `if (obs == null \|\| obs.size() < minCrossSectionSize) continue;`（**R011 / R014 `V17` 已审** ⟹ 本轮只作 `rank_ic` 侧对照） |

### 4.3 声明 / 契约 / 展示锚点（C 组）

| 锚 | 文件:行 | 内容 |
|---|---|---|
| C-01 | F08:52-60 | `FactorResearchFunnel { universe_count?; feature_valid_count?; selector_item_count?; tradable_count?: number\|null; tradable_status?; metric_scope?; note? }` ⟹ **未声明** `universe_days_with_snapshot` / `valid_observations` / `total_observations` / `cross_section_days` / `universe_source`；**未声明**任何两键之间的区别 |
| C-02 | F08:71 / F08:139 / F08:164 | `coverage: number`（**无单位/分母说明**，三处） |
| C-03 | F08:168 | `daily_funnel` 的 TS 声明（**存在的唯一逐口径观测计数族**） |
| C-04 | F10:185-188 | 前端 legacy 漏斗三格：`漏斗 universe：{{ result.funnel.universe_count ?? '—' }}` / `feature_valid：{{ result.funnel.feature_valid_count ?? '—' }}` / `tradable：…` |
| C-05 | F10:197-199 | 前端顶层：`总观测` / `有效观测` / 覆盖率（`coverageText`） |
| C-06 | F10:408-419 | `coverageText = \`覆盖率：${result.value.coverage}%\``（**直拼 `%`**）；`coverageClass` 阈值 `c >= 80` / `c >= 50` |
| C-07 | F10:89-96 | snapshot 漏斗展示（含 `rows_total` / `scored_valid` / `coverage`） |
| C-08 | F12:210-245 | 既有服务测试：断言 `funnel` 的若干键存在；**未断言** `feature_valid_count` 与 `valid_observations` 的关系，**未断言**回退行为 |

### 4.4 撤回 / 不进变体的锚点（**预注册降级**，记录以防事后回填）

| 降级项 | 出处（同源） | 处置 |
|---|---|---|
| `coverage` 跨面「同名不同分母」（legacy 分母 = `allItems.size()`；snapshot 分母 = `rowsTotal`） | **R012 `D-R012-02`** | **只登记为 `D-R015-*`**，**不**成为承重判定，`V07` 的判定词**冻结为 `NOT_REACHED_STATIC_ONLY`**（静态读数，不可升格为 `K3`） |
| `cross_section_days` 跨面同名（legacy = key 数；snapshot = `rowsByDate.size()`） | **R012 `D-R012-02`** | 同上，`V08` 冻结为 `NOT_REACHED_STATIC_ONLY` |
| 顶层 `metric_scope = COST_APPROX` 而 `rank_ic.metric_scope = RAW` | **R004 `D-R004-02`** | **只登记**，`V16` 判定词 `SCOPE_LABEL_UNFAITHFUL` 但**不参与任何 `K`**（A4 全轴冻结为描述性） |
| `funnel.metric_scope = COST_APPROX` 而 legacy 漏斗块内**无**任何成本后观测计数 | **R004 `D-R004-02`** | 同上（`V15`） |
| `cross_section_days` 顶层（key 数）vs `rank_ic.cross_section_days`（经 min-size 过滤） | **R011 / R014 `V17`** | **只登记**，**不**产生变体（理由：`min-size` 静默丢日族已由 R011/R014 判过） |
| TS 未声明 5 个后端键 | **R009 `A5b`** | **只作边界说明**，**不**产生变体 |
| **撤回的锚点**：**无**。冻结时 13 个文件全部命中（命中率见报告）。 |

---

## 5. 变体表（**20 个**，5 轴；`MAX_VARIANTS = 20`）

| 轴 | 轴名 | 变体 | 判定词（预期） |
|---|---|---|---|
| **A1** | **键—量唯一**（5，**承重**） | V01 `M1`（有快照）6 键**可分性矩阵** / V02 `M2`（回退）6 键可分性矩阵 / V03 **300 组受控三重穷举**（别名对恒 0 差 + 值域非退化） / V04 **反向锚点**：穷举搜索使别名对**相异**的输入 / V05 端到端 `research()` 公开载荷内别名对仍相等 **+** 两键的区别是否有**任何声明** | **V01/V02/V03/V04 `KEY_VALUE_NOT_UNIQUE`（预期）**；V05 `ALIAS_UNDECLARED`（**描述性**，不计 `K`） |
| **A2** | **同名同义**（3） | V06 **同载荷内**三个同名键（`total_observations` / `valid_observations` / `cross_section_days`）在**顶层 vs `funnel`** 是否同值 / V07 `coverage` 跨面（legacy vs snapshot）**静态** / V08 `cross_section_days` 跨面 **静态** | V06 `SAME_NAME_SAME_QUANTITY`（预期·正面对照）；**V07/V08 `NOT_REACHED_STATIC_ONLY`（冻结）** |
| **A3** | **退化可分辨**（6，**承重**） | V09 `universe_days_with_snapshot == 0` 与「回退是否执行」的**等价性穷举** / V10 **回退触发域穷举**（`cross` 取 `null`/`0`/负数/非 Number/缺键/floats） / V11 载荷中是否有**具名**回退标记（扫键名 + `note` + `universe_source` 字面量） / V12 **单键依赖性**：移除 `universe_days_with_snapshot` 后仅凭其余键能否判定回退 / V13 空输入退化（`latestPerKey` 空 + `total=0`） / V14 退化不一致搜寻（`total=0` 而某 `cross>0`） | **V09/V10 `FALLBACK_MARKER_EXACT`（预期）**；V11/V12 `FALLBACK_UNNAMED`（**描述性**，不计 `K`，须与 V09 同报）；V13/V14 `DEGENERATE_KEYS_CONSISTENT`（预期） |
| **A4** | **口径标注忠实**（4，**全轴描述性**） | V15 legacy `funnel.metric_scope` 与本块内容 / V16 顶层 `metric_scope` vs `rank_ic.metric_scope` / V17 `tradable_count = null` 是否可分辨（`status` 对照） / V18 `daily_funnel` 行内三口径是否**逐一具名** | V15/V16 `SCOPE_LABEL_UNFAITHFUL`（**降级·不计 `K`**）；V17/V18 `SCOPE_LABEL_FAITHFUL`（预期·正面对照） |
| **A5** | **仪器**（2） | V19 金丝雀总门 / V20 正面对照 + 反向锚点（确定性、置换可撤销、别名注入后杀点消失） | 见 §7 |

- **轴数和**：5 + 3 + 6 + 4 + 2 = **20** = `variants_total` ⟹ `row_sum = axis_total = 20`，`grid_mismatch_count = 0`。
- `CANARY_PAIRS = 20`（每个变体一对 `CLN`/`INJ`）。

---

## 6. 判定词表（**16 词，冻结**；表外词 ⟹ `K6` ⟹ `INCONCLUSIVE`）

`KEY_VALUE_UNIQUE` / `KEY_VALUE_NOT_UNIQUE` / `ALIAS_DECLARED` / `ALIAS_UNDECLARED` /
`SAME_NAME_SAME_QUANTITY` / `SAME_NAME_DIFF_QUANTITY` /
`FALLBACK_MARKER_EXACT` / `FALLBACK_UNNAMED` / `FALLBACK_UNDISCLOSED` /
`SCOPE_LABEL_FAITHFUL` / `SCOPE_LABEL_UNFAITHFUL` /
`DEGENERATE_KEYS_CONSISTENT` / `DEGENERATE_KEYS_INCONSISTENT` /
`INSTRUMENT_GREEN` / `INSTRUMENT_RED` / `NOT_REACHED_STATIC_ONLY`

> **未使用的 6 词**（`KEY_VALUE_UNIQUE` / `ALIAS_DECLARED` / `SAME_NAME_DIFF_QUANTITY` / `FALLBACK_UNDISCLOSED` /
> `DEGENERATE_KEYS_INCONSISTENT` / `INSTRUMENT_RED`）**保留在表内**：它们是各对的**另一侧**，
> 供**实测反向**时使用；`off_vocab` 检查只禁**表外**词。

---

## 7. 金丝雀（§11.3 / R011 教训 61–63 / R012–R014 沿用）

- **`CLN`（语义 = 判定输入键集合的确定性函数）**：向该变体的判定**输入键集合**塞入**未声明哨兵键**
  `__sentinel_cln__`（值取该变体**实测**值的第一项），**判定必须不变**。
  **禁止**把 `CLN` 实现成「谓词必须为真」（否认负结论）。
- **`INJ`（单键取反必翻转）**：注入值按**实测状态**取向（不得按期望状态取向）；
  **必须显式按键表**注入，**禁止**泛型取反（R013 教训）。本轮 `INJ` 的**按键表**：
  A1 轴注入 `feature_valid_count` 与 `valid_observations` **取反后相异**（模拟「两个独立统计量」）⟹ 判定必须由
  `KEY_VALUE_NOT_UNIQUE` 翻为 `KEY_VALUE_UNIQUE`；A3 轴注入 `universe_days_with_snapshot = 1`（模拟「有标记」）
  ⟹ 判定必须由 `FALLBACK_MARKER_EXACT` 翻为 `FALLBACK_UNDISCLOSED`。
- **自指禁令**：checker 不得读取「由该 checker 自身结果算出」的键。
- **`V19`（金丝雀总门）**：要求 `CANARY_PAIRS == 20` 且 20 对全部通过；该变体**不得**把自身结果计入读数
  ⟹ 以**声明数**填入并断言与实测数相等。
- **`V20` 反向锚点（三查）**：① 两次运行探针输出**逐字节相等**；② **撤销** §10 的计数缩放后
  A1 轴的可分性矩阵与首次运行**逐位相等**；③ 把别名对的**键名**在判定输入中**互换**（值不变）后
  A1 轴的判定**不变**（证明杀点由**键—值对应关系**引起，而不是键名字符串顺序）。

---

## 8. `K` 表与短路（**冻结**）

| `K` | 触发条件 |
|---|---|
| `K1` | ∃ 变体判定为 `KEY_VALUE_NOT_UNIQUE` **且** `reachability >= REACHABLE_PUBLIC_API` **且** `reading_independent == true`（**(i) 键—量唯一被证伪**） |
| `K2` | ∃ 变体判定为 `FALLBACK_UNDISCLOSED`（**(iii) 退化可分辨被证伪**）—— **冻结**：`FALLBACK_UNNAMED` **不**触发本门（「可分辨但未具名」只登记 `D-R015-*`） |
| `K3` | ∃ 变体判定为 `SAME_NAME_DIFF_QUANTITY` **且**该判定**不属于** §4.4 的预注册降级集（**(ii) 同名同义被证伪**） |
| `K4` | **越界门**：∃ 承重判定词（`KEY_VALUE_NOT_UNIQUE` / `FALLBACK_UNDISCLOSED` / `SAME_NAME_DIFF_QUANTITY`）其 `reachability < REACHABLE_PUBLIC_API` ⟹ 该判定**不得**计入 `K1`–`K3`（计为仪器违反 ⟹ `INCONCLUSIVE`） |
| `K5` | 计数门：`row_sum != axis_total ∨ axis_total != variants_total ∨ variants_total != 20 ∨ grid_mismatch_count != 0 ∨ CANARY_PAIRS != 20` |
| `K6` | 仪器门：`off_vocab` 非空 ∨ 任一 `CLN`/`INJ` 失败 ∨ 探针未运行/非确定性 ∨ **`forbidden_scan` 命中且未申明豁免** ∨ **`instrument_alerts` 非空** |
| `K7` | 冻结门：`prereg_sha256` 变化 ∨ **13 个冻结文件任一 sha256 变化** ∨ 两次运行静态结果不逐字节相等 |

**可达性等级（冻结）**：`REACHABLE_PUBLIC_API`（经**公开** Java 方法或公开 HTTP 字段可达）
> `REACHABLE_CLIENT_INPUT`（仅经客户端可传字段可达） > `SYNTHETIC_ONLY`（仅合成面板/反射私有方法可达） > `UNREACHABLE`。

**冻结的可达性分配（先写死，防事后挑选）**：

| 变体 | 通道 | `reachability` |
|---|---|---|
| V01–V04（A1） | 反射调用 `private buildResearchFunnel` + **`public research()`** 双通道 | **`REACHABLE_PUBLIC_API`**（因 `research()` 为 public 且其 `funnel` 键集合由同一生产者填充；V05 为该主张的端到端证据） |
| V05（A1） | `public research()` | `REACHABLE_PUBLIC_API` |
| V06（A2） | `public research()` | `REACHABLE_PUBLIC_API` |
| V07/V08（A2） | **源码静态**（F02/F08 文本） | `SYNTHETIC_ONLY`（⟹ 冻结为 `NOT_REACHED_STATIC_ONLY`） |
| V09–V14（A3） | 反射 + `public research()` 双通道（同 A1） | `REACHABLE_PUBLIC_API`（V13/V14 的**空载荷**场景为 `SYNTHETIC_ONLY`，见下） |
| V13/V14（A3） | 反射（空 `latestPerKey`） | `SYNTHETIC_ONLY` ⟹ 即使判定为**负面**也**不**计入 `K` |
| V15–V18（A4） | 静态 + `public research()` | `REACHABLE_PUBLIC_API`（但 A4 全轴**冻结为描述性**，不参与 `K`） |
| V19/V20（A5） | 仪器 | `REACHABLE_PUBLIC_API`（仪器门自身） |

**重算容差（冻结，只用于算术重建比较；`J-6` 依据）**：

| 记号 | 值 | 用途 |
|---|---|---|
| `RECON_TOL_CNT` | `0` | **计数键**的比较必须是**精确整数相等**（计数不是浮点量；不允许容差） |
| `RECON_TOL_FP` | `1e-9` | 两次运行之间、以及 `coverage` 类浮点量的比较 |

> 这两个容差是**算术比较容差**，**不是**经济显著性阈值；**不得**被挪用为任何因子/策略的合格线。

**短路（冻结）**：

```
if K4 or K5 or K6 or K7:  total_verdict = INCONCLUSIVE
elif K1 or K2 or K3:      total_verdict = REJECTED
else:                     total_verdict = NOT_VIOLATED
```

`h0_alive = (total_verdict != REJECTED)`。

---

## 9. `P` 预测表（**事前**；标「读码」者来自源码阅读，**不是**实测）

| `P` | 预测 | 依据 |
|---|---|---|
| `P-01` | V01：`M1` 下 6 键的 15 个键对中，**恰 2 对**永不分离（`{feature_valid_count, valid_observations}`、`{selector_item_count, total_observations}`）⟹ `KEY_VALUE_NOT_UNIQUE` | 读码（A-04/A-05） |
| `P-02` | V02：`M2`（回退）下**恰 4 对**永不分离（上述 2 对 + `{universe_count, selector_item_count}` + `{universe_count, total_observations}`）⟹ `KEY_VALUE_NOT_UNIQUE` | 读码（A-03/A-04/A-05） |
| `P-03` | V03：300 组受控三重下，两别名对的差**恒为 0**；且 `total_observations` 与 `valid_observations` 在 300 组中各自取到 **≥ 10 个不同值**（**值域非退化**证据） | 读码 + 仪器设计 |
| `P-04` | V04：**不存在**任何输入使两别名对相异 ⟹ 反向锚点失败（确认不可分离，非仪器假象） | 读码（同一变量） |
| `P-05` | V05：公开 `research()` 载荷中两别名对**仍逐位相等**；且 F08/F10/F02 中**不存在**任何说明两键区别的文本 ⟹ `ALIAS_UNDECLARED` | 读码（C-01/C-04 + A-10 只命名 `feature_valid` 阶段） |
| `P-06` | V06：`total_observations` / `valid_observations` / `cross_section_days` 三键在**顶层 vs `funnel`** 逐元素相等 ⟹ `SAME_NAME_SAME_QUANTITY` | 读码（A-12 vs A-04/A-06；同一实参） |
| `P-07` | V07：`coverage` 两面**同名、同单位（百分比）、公式同形**，但**分母定义**不同（`allItems.size()` vs `rowsTotal`）⟹ 静态登记（不升格） | 读码（A-12 vs B-03/B-07） |
| `P-08` | V08：`cross_section_days` 在 legacy funnel = key 数、snapshot funnel = `rowsByDate.size()` ⟹ 静态登记（不升格） | 读码（A-06 vs B-02/B-07） |
| `P-09` | V09：`universe_days_with_snapshot == 0` **⟺** 回退已执行（在全部受测输入上恒等价）⟹ `FALLBACK_MARKER_EXACT` | 读码（A-02/A-03 的代数等价） |
| `P-10` | V10：触发域 = `{cross ∈ {null, 缺键, 0, 负数, 非 Number}}` 全集合；该集合上标记仍等价 ⟹ `FALLBACK_MARKER_EXACT` | 读码（A-02 的 `cross != null && cross > 0` + A-11） |
| `P-11` | V11：载荷中**不存在**任何具名回退标记（`universe_source` 为**常量**、`note` 不提回退）⟹ `FALLBACK_UNNAMED` | 读码（A-08/A-09） |
| `P-12` | V12：移除 `universe_days_with_snapshot` 后，仅凭其余键**无法**判定回退（`universe_count == selector_item_count` 只是**间接**线索，且在 `total==Σcross` 的非回退情形同样成立）⟹ `FALLBACK_UNNAMED` | 读码（A-03/A-05） |
| `P-13` | V13：空输入下 6 键全部为 `0`（`universe_count` 回退为 `0`），载荷**不抛异常** ⟹ `DEGENERATE_KEYS_CONSISTENT` | 读码（A-02/A-03） |
| `P-14` | V14：`total = 0` 而某 `cross > 0` 时，`universe_count = Σcross > 0` 而 `selector_item_count = 0` ⟹ 仍一致（两键由**不同**变量填充，此情形恰为**反面对照**） | 读码（A-04/A-05） |
| `P-15` | V15：legacy 漏斗块内**无**任何成本后观测计数或成本参数 ⟹ `SCOPE_LABEL_UNFAITHFUL`（**降级**） | 读码（A-09 + 块键清单） |
| `P-16` | V16：顶层 `metric_scope = COST_APPROX` 而 `rank_ic.metric_scope = RAW` ⟹ `SCOPE_LABEL_UNFAITHFUL`（**降级**） | 读码（A-12/F02:166） |
| `P-17` | V17：`tradable_count = null` 与 `tradable_status = "UNAVAILABLE"` 并列 ⟹ 「不可用」与「为 0」**可分辨** ⟹ `SCOPE_LABEL_FAITHFUL` | 读码（A-07） |
| `P-18` | V18：`daily_funnel` 行内 `raw_observations` / `cost_approx_observations` / `executable_observations` **逐一具名** ⟹ 比 legacy 漏斗**更忠实** ⟹ `SCOPE_LABEL_FAITHFUL` | 读码（B-06） |
| `P-19` | V19：`CANARY_PAIRS == 20` 且 20 对全通过 ⟹ `INSTRUMENT_GREEN` | 仪器设计 |
| `P-20` | V20：两次运行**逐字节相等**；撤销缩放后 A1 矩阵逐位恢复；键名互换后 A1 判定不变 ⟹ `INSTRUMENT_GREEN` | 仪器设计 |

> **预测可中亦可不中**；不中者只进报告附录 `A-xx`，**不得**修改本表、**不得**放宽 `K` 表。

---

## 10. 关键参数 ±20% 敏感性（**必做**）

| 参数 | 冻结值 | 变动 | 变动方式 | 来源 |
|---|---|---|---|---|
| **计数输入规模**（`totalObservations` / `validObservations` / 各 `cross_section_count`） | 面板基准 | **×0.8 / ×1.0 / ×1.2**（取整） | **实测**（探针入参） | 本对象直接入参（A-01） |
| `RANK_IC_MIN_CROSS_SECTION_SIZE` | **10** | **{8, 10, 12}** | **不改常量**，而用**面板设计**把「每日标的数」分别设为 8 / 10 / 12 ⟹ 同一常量在边界两侧被踩到 | `F02:71` / `F01:140-142` |
| `RANK_IC_GROUP_COUNT` | **5** | 不适用 | **静态不变性论证**：`buildResearchFunnel` 不读该常量（A-01–A-09 的键清单中无任何分组量） | `F02:70` |

- **网格**：3（计数缩放）× 3（边界面板）= **9 个单元** × **2 个模式**（`M1` 有快照 / `M2` 回退）= **18 个单元**。
- 每个单元记录：6 个计数键的值、**可分性矩阵**（15 个键对中「永不分离」的键对数）、
  `universe_days_with_snapshot` 的取值、以及 **A1/A3 两轴的判定词是否翻转**。
- **灵敏度门（冻结）**：若在任一单元 V01–V04 由 `KEY_VALUE_NOT_UNIQUE` 变为 `KEY_VALUE_UNIQUE`，
  或 V09/V10 由 `FALLBACK_MARKER_EXACT` 变为 `FALLBACK_UNDISCLOSED` ⟹ 该事实必须进报告附录，
  并据此把对应的 `K1`/`K2` 标为**参数依赖**（`parameter_dependent = true`）。

---

## 11. 前视 / 幸存者偏差披露（`研究口径规范.md` §5）

- 本轮**不产生任何收益序列**、**不读任何真实行情/财务数据**、**不访问数据库**
  （行情/分数为**合成** fixture，只用于让 `research()` 的公开路径可跑通）
  ⟹ **前视偏差影响 = 0、幸存者偏差影响 = 0**。
- **明确声明**：合成面板**不**含 T+1 建仓、涨跌停/停牌拒单、`NOT_BUYABLE`/`SUSPENDED`/`NOT_SELLABLE`/`DELISTED`
  语义 ⟹ 本轮**不检验**这些口径，**不得**把本轮结论外推为「真实库上的漏斗形状或影响面」（`B-R015-02`）。
- 冻结口径（买入佣金万三 `0.0003` / 卖出印花税万五 `0.0005` / 单边滑点万二 `0.0002` / 入场 = 信号日**下一交易日开盘** /
  退出 = 第 h 持有日**收盘** / 拒单状态**不得删除样本** / `rule_score` 是 **0–100 截面百分位、不是上涨概率**）
  **一律不变**，本轮**不重算**任何成本、**不修改** `ResearchConvention`。
- **`B-R015-01`**（海外检索单通道）与 **`B-R015-02`**（真实库漏斗形状未测量）在收口时登记 `blocked_queue.md`。

---

## 12. 冻结纪律

1. **本文件先于任何实验代码**；`R015_review.md` 先于本文件（其 §1.5 记录了**冻结前**的选题变更）。
2. **偏离与勘误**：实测与预注册不符 ⟹ 逐条进 `R015_report.md` 附录 `A-xx`；**绝不**改写本文件。
3. **读数敏感性**：文本类主张须用 (a) 子串（**大小写无关**）/ (b) **按被测对象类型分派**的收紧读数 /
   (c) 人工逐字 三种读数各测一次；**判定被读数方式改变者**必须标 `reading_dependent = true`
   并**排除出 `K1` 候选集**，且在报告中**降级为描述性**（R012 教训 67/69 + R013/R014 沿用）。
4. **否定式声明不得用被否定对象的子串来测**（R012 教训 68）：如「载荷不含具名回退标记」**不得**用
   `"fallback"` 这一个子串的有无来判，必须**逐键读值 + 逐条读文本**并给出键清单与文本清单。
5. **判定事实必须来自冻结扫描根内**（R012 教训 70）；根外文件只作 `corroboration` ⟹ `instrument_alerts` ⟹ `K6`。
6. **探针**：编译进生产引擎自己的包（`javac -cp target/classes -encoding UTF-8 -d <repo>/modules/backend-java/target/classes`）；
   运行**两次**并要求输出**逐字节相等**；运行末尾**清理字节码**（`git status` 复核无残留）。
   探针**只读**：禁止调用任何写入方法、禁止触碰 `target/classes` 内的既有 `.class`。
7. **`run.log` 为 append-only**，**不得**删改任何一次运行（含失败）。
8. **零生产代码改动**：R015 的全部改动只允许落在 `research/paper_loop/**`（`git diff --stat` 复核）。
9. **工程纪律（R013/R014 教训逐条沿用）**：subprocess 读文本必须 `encoding="utf-8", errors="replace"`；
   合成面板的 `OBS` 行**必须自含日期字段**；跨面板缓存**必须按 panel id 分键**；
   探针输出解析**必须保留全部字段并数值强转**；**仪器门未全绿时不得下结论**。
10. **`_scratch` 产物可提交**（R013/R014 先例）。
11. **收工纪律（本轮特有）**：R015 是**最后一轮**；收口时须生成 `stage_summary_R011_R015.md`
    （含 R005 缺文件的历史缺口登记）并**明确写下收工结论**；**不得**因收工而把任何 `K` 门放宽，
    **亦不得**为凑产出把 `NOT_VIOLATED` 写成 `REJECTED`（反向亦然）。

---

## 13. 台账联动（收口时**逐份**说明改了什么、或**为什么**不改）

`paper_ledger.csv` / `shadow_registry.md` / `blocked_queue.md` / `decision_queue.md` / `progress.md` 五份。

- `shadow_registry.md`：本轮**不提出任何新因子** ⟹ 预期**不改**（报告中显式说明理由）。
- `blocked_queue.md`：登记 **`B-R015-01`**（arXiv API / Semantic Scholar 经代理 429 限流，海外检索单通道）
  与 **`B-R015-02`**（真实库上 `factorSnapshot.cross_section_count` 的**填充率**未测量；
  snapshot 面 `daily_funnel` 端到端形状需 Spring 容器 + DB，本轮只作静态锚点）。
- `decision_queue.md`：登记 `D-R015-*`（**只登记、不实施**：为两别名键改键名/加说明、
  回退加具名标记或改 `universe_source`、给 `coverage` 加分母说明、给 legacy `funnel` 补口径内容——均属协议 §7 record-only）。
- `paper_ledger.csv`：追加 **DMNU2019** 一行（含载体哈希、判据档位、`X` 档禁用清单、本轮判定）。
- `progress.md`：按 R014 同样式回填，并在 §1 写下**收工结论**（用户明确停止）。

---

## 14. 负面清单（冻结，与 `review §7` 十二条**并行生效**）

13. **不得**修改 `buildResearchFunnel` / `buildSnapshotFunnel` / `daily_funnel` 的任何键名、取值或发射位置——**只登记**；
14. **不得**把「两个键名由同一变量填充」叙述成「项目算错了两次」或「因子无效」；计数值本身在本装置下**正确**，
    被指控的是**键名设计与披露**；报告与判定证据中**逐字**保留该区分（§1 边界 1）；
15. **不得**把「回退未具名」（`FALLBACK_UNNAMED`）升格为「回退不可分辨」（`FALLBACK_UNDISCLOSED`）
    —— 前者是**披露不足**、后者是**不可分辨**；`K2` **只**认后者；
16. **不得**把 §4.4 的预注册降级项（`coverage`/`cross_section_days` 跨面、`metric_scope`、TS 缺键、min-size 丢日）
    改写成 R015 的新发现——只允许登记并**点名** R004/R009/R011/R012/R014；
17. **不得**据本轮任何量级设定阈值、容差、合格线（`RECON_TOL_*` 只用于算术比较）；
18. **不得**把 `NOT_VIOLATED` 硬凑成 `REJECTED`（反向亦然）；**不得**因连续十四轮无采纳产出而放宽条件；
19. **不得**把合成面板上的结构发现外推为真实库的发生频率或影响面（`B-R015-02` 未测量）。
