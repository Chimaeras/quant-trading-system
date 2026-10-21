# R014 预注册（**冻结**）

> **冻结声明**：本文件在**任何 R014 实验代码**（`make_config.py` / 探针 / 执行器）与**任何实测读数**之前写成。
> 冻结后**不得**修改本文件的任何判定条件、变体、词表、K 表、P 表与条件阈值；
> 实测不符只进 `rounds/R014_report.md` 附录 `A-xx`（**勘误**），**绝不**回写本文件。
> 判据来源：**CZ2022**（Chen & Zimmermann, *Open Source Cross-Sectional Asset Pricing*，
> FEDS Working Paper **2021-037**，DOI `10.17016/FEDS.2021.037`；期刊版 *Critical Finance Review* 11(2) 2022，
> DOI `10.1561/104.00000112` ⟹ **载体 A 档**；本轮核对版本 = **WP 版**，版本差异**未核验**）——
> 检索、初筛、适配性七问与判据抽取见 `rounds/R014_review.md`。
> 冻结时仓库 HEAD：`6b5cca7537225ea074d220ed2c786167a40cfaaf`。

---

## 1. 被审计对象（不是论文）

**被审计对象 = 项目载荷里的两个数组**（及其计数字段）：

| 数组 | 元素字段 | 生产者 | 映射/发射面 |
|---|---|---|---|
| `daily_rank_ic[]` | `trade_date` / `rank_ic` / `sample_size` | `FactorResearchEngine.summarize`（`F01:154`） | snapshot 面 `F02:548`、统一报告面 `F02:776`、DTO 面 `F03:1031-1037` |
| `grouped_returns[]` | `group_no` / `label` / `avg_return` / `observations` | `FactorResearchEngine.groupedAverageReturns`（`F01:92-116`）+ 聚合侧 `F01:180-183` | snapshot 面 `F02:549`、统一报告面 `F02:787`、DTO 面 `F03:1038-1046` |

**H0（四子句）**：

> **(i) 可重建**：每个元素的数值可由「**载荷自带的构造规格** + 同一输入**多重集**」**唯一重算**
> 到声明精度（含并列秩规则与分组边界规则）——**不得**依赖未声明的输入**行序**或未披露的中间变换；
> **(ii) 单位忠实**：计数字段（`sample_size` / `observations`）的单位在**实现 / 声明 / 展示**三处一致，
> 且**同名字段**在不同生产路径与不同面上**同义**；
> **(iii) 同量同值**：**同一个量**（日 rank IC / 组平均收益 / 计数）在**不同发射面**上数值一致；
> 取整/精度规则**一致**或在载荷中**显式声明**；
> **(iv) 计数忠实**：计数字段必须等于**实际参与该数值计算的有效观测数**——**不得**把非有限值或
> 未通过口径的行计入有效观测，**不得**让被排除的观测以**看不出差别**的方式消失。

**边界（先写死）**：
1. 本轮**不**评估这两个数组数值的统计性质（不判显著性/区间质量/因子有效性）；
2. 本轮**不**评估 `rank_ic_mean` / `rank_ic_std` / `rank_ic_ir` / `rank_ic_positive_rate` /
   `pearson_ic_mean` / `hac_std` / `ci_*` / `long_short_spread` 等**标量**（属 R006/R007/R009/R013）；
3. `(iii)` 只在不同面**都能产出该量**时判定；**某一面不携带该数组**仅作**描述性登记**（见 §4.4），
   **不**参与 `K3`。

---

## 2. 判据（**只支持做法种类**，不支持任何阈值）

| 判据 | 内容 | 档位 | 用于 |
|---|---|---|---|
| **J-1** | 「复算」= 同样本、**同声明的构造规格**产生同一结果 ⟹ 数值忠实性的判据是**可被独立重算** | **L** | A1 全轴 |
| **J-2** | 每个数值必须能追溯到**显式构造规格**（加权/再平衡/分位排序等口径维度与数值并列记录） | **L** | A1-V05、A3-V14 |
| **J-3** | **缺失必须保持可分辨**（置 missing，而非赋 0 或删除）；口径的**实施位置必须可见** | **L** | A4 全轴、A1-V05 |
| **J-4** | **不得静默丢弃**；偏差必须能**归因到口径/实现层**；发现错误须**公开勘误并重算** | **L** | A4-V17（描述性） |
| **J-5** | **方向/符号约定必须显式**；无直接证据时**宁可不签** | **L** | **只作描述性登记**（见 §4.4），**不**参与任何 `K`（避免与 R004 的 spread 口径结论重叠） |
| **J-6** | **跨实现不要求逐位相等**：完美复现几乎不可能 ⟹ 判据是**披露可分辨的偏差**，不是逐位相同 | **L** | §8 的容差设定（`RECON_TOL_R4` / `RECON_TOL_FP` 只用于**算术重建比较**，不是经济显著性阈值） |
| **J-7** | 承载数值的字段必须有**定义溯源**（Data Dictionary 的 `Mnemonic`→`Description`） | **L** | A2-V09、A3-V14 |
| **J-X** | 论文全部复算率/计数/`t` 门槛/slope/`R²`/`30%`/`20 bps`/`98.9%`/`1%`/四分类体系/六类筛选/公开代码与数据要求 | **X（不可移植）** | **不使用** |

**禁用清单（先写死）**：`review §7` 八条**全部**沿用；另**禁止**把 J-1/J-2/J-3/J-4/J-7 升级为
「项目**应当**公开代码/数据集」「项目**应当**改口径披露」这类**要求**。

---

## 3. 冻结扫描根（**只读**；共 12 个文件）与冻结哈希

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

- **合计 12 个文件、267,473 字节**；`K7`（冻结门）要求 **12 个 sha256 全部不变**。
- **F01–F11 的 12 项中前 11 项哈希与 `R013_prereg.md` §3 逐位相同**（R013 冻结时同一批文件）
  ⟹ 这本身就是「R013→R014 之间**零生产漂移**」的证据（报告 §5 复核）。
- **冻结扫描根之外**的文件（若有）只能作 `corroboration`（**旁证**）：其失败走 `instrument_alerts`
  通道并**触发 `K6`**，**绝不**驱动任何变体的判定词（R012 教训 70 / R013 沿用）。
- 论文侧载体（`papers/cz2022_ossap_*`）**不属于**本扫描根，其哈希见 `R014_review.md` §1.4，
  只作**判据溯源**，**不**参与 `K7`。

---

## 4. 冻结锚点表

### 4.1 实现锚点（A 组）

| 锚 | 文件:行 | 内容（冻结摘要） |
|---|---|---|
| A-01 | F01:92-97 | `groupedAverageReturns`：`observations == null \|\| isEmpty \|\| groupCount < 2 → List.of()`（**不抛异常**） |
| A-02 | F01:100 | `sorted.sort(Comparator.comparingDouble(…::score))` —— **稳定排序** ⟹ 并列分数的相对次序 = **输入行序** |
| A-03 | F01:108 | `int bucket = Math.min(g - 1, (int) ((long) i * g / n));` —— 分组规则是**按排序后下标等分**（不是按分数取值定分位） |
| A-04 | F01:114-115 | 日级：`rets.isEmpty() ? 0 : …average().orElse(0)` + `new GroupReturnStat(i+1, groupLabel(i+1,g), avg, rets.size())` ⟹ **日级 `observations` = 该桶名字数** |
| A-05 | F01:140-142 | `if (obs == null \|\| obs.size() < minCrossSectionSize) continue;` —— **静默跳过**（无计数、无披露字段） |
| A-06 | F01:150-158 | `if (!Double.isFinite(ic)) continue;`（**早于**计数）→ `daily.add(new DailyRankIc(date, ic, obs.size()))` → `totalObs += obs.size();` ⟹ `sample_size` = 当日**原始行数**；**两者同集合** |
| A-07 | F01:160-164 | `bucketReturns.computeIfAbsent(gs.groupNo(), …).add(gs.avgReturn())` —— **每个被接受的日子对每组恰贡献一条** |
| A-08 | F01:179-183 | 聚合侧：`for (int g = 1; g <= groupCount; g++)` + `rets.isEmpty() ? 0 : …` + `new GroupReturnStat(g, groupLabel(g, groupCount), avg, rets.size())` ⟹ **聚合 `observations` = 该组贡献的「天数」** |
| A-09 | F01:271-288 | `ranks()`：`Arrays.sort(idx, Comparator.comparingDouble(i -> values[i]))` + 并列组取**平均秩** `(i + j + 2) / 2.0` |
| A-10 | F01:314 | `if (vx < 1e-18 \|\| vy < 1e-18) return Double.NaN;`（**R009 已审该项的量纲** ⟹ 本轮**只标注、不重判**） |
| A-11 | F02:580-588 | `dailyIcList`：`rank_ic = round4(d.rankIc())`、`sample_size`/`trade_date` **原样** |
| A-12 | F02:592-601 | `groupedReturnList`：`avg_return = round4(g.avgReturn())`、`observations`/`label` **原样** |
| A-13 | F02:1030-1032 | `round4(v) = Math.round(v * 10000.0) / 10000.0` |
| A-14 | F03:1018-1046 | `toFactorAnalysisDto`：`rankIc(d.rankIc())` / `avgReturn(g.avgReturn())` —— **不取整**；`sampleSize` / `observations` 原样 |
| A-15 | F02:70-71 | `RANK_IC_GROUP_COUNT = 5`、`RANK_IC_MIN_CROSS_SECTION_SIZE = 10` |

### 4.2 发射 / 映射锚点（B 组）

| 锚 | 文件:行 | 内容 |
|---|---|---|
| B-01 | F02:548 | snapshot 口径块：`block.put("daily_rank_ic", dailyIcList(summary))` |
| B-02 | F02:549 | snapshot 口径块：`block.put("grouped_returns", groupedReturnList(summary))` |
| B-03 | F02:574-575 | `unavailableScopeBlock`：两数组硬写 `List.of()`（`status = "UNAVAILABLE"`） |
| B-04 | F02:668-669 | `latestPerKey.isEmpty()` 早退块：两数组 `List.of()` |
| B-05 | F02:770-787 | 统一报告 `rank_ic` 块：**内联**构造两数组，规则与 A-11/A-12 相同（同一个 `round4`） |
| B-06 | F03:1031-1046 | DTO 两数组映射：**唯一不经 `round4` 的发射面** |
| B-07 | F02:826-838 | `buildNetIcBlock`（`cost_net_ic`）：**不携带**两数组（覆盖缺口 ⟹ §4.4 登记，**不计入 K**） |
| B-08 | F04:80-92 / F04:105-117 | `FactorAnalysis`（含 `List<DailyRankIc>` / `List<GroupReturnStat>`）与 `GroupReturnStat`（`groupNo`/`label`/`avgReturn`/`observations`）字段清单 |
| B-09 | F01:33 / F01:39 | `record GroupReturnStat(int groupNo, String label, double avgReturn, int observations)` / `record DailyRankIc(String tradeDate, double rankIc, int sampleSize)` —— **record 无 javadoc** |
| B-10 | F04:98-103 | `DailyRankIc` DTO（`tradeDate`/`rankIc`/`sampleSize`）—— **无字段级注释** |

### 4.3 声明 / 展示锚点（C 组）

| 锚 | 文件:行 | 内容 |
|---|---|---|
| C-01 | F08:19-23 | `FactorDailyRankIc { trade_date; rank_ic; sample_size }` —— **无单位/口径说明** |
| C-02 | F08:25-30 | `FactorGroupReturn { group_no; label; avg_return; observations }` —— **无单位/口径说明** |
| C-03 | F09:61-62 | `dailyRankIc: Array<{ tradeDate: string; rankIc: number; sampleSize: number }>` / `groupedReturns: Array<{ groupNo; label; avgReturn; observations }>` —— **内联匿名类型，无单位说明** |
| C-04 | F10:131 | `<span>观测数：{{ blk.total_observations }}</span>`（snapshot 面） |
| C-05 | F10:151 | `prop="observations" label="观测数"`（snapshot 面 `grouped_returns`） |
| C-06 | F10:161 | `prop="sample_size" label="样本数"`（snapshot 面 `daily_rank_ic`） |
| C-07 | F10:237 | `<span>观测数：{{ result.rank_ic.total_observations }}</span>`（统一报告面） |
| C-08 | F10:254 / F10:264 | `prop="observations" label="观测数"` / `prop="sample_size" label="样本数"`（统一报告面） |
| C-09 | F10:149 / F10:252 | `pct4(row.avg_return)` ⟹ `${(v*100).toFixed(2)}%`（**2 位小数百分号**；`pct4` 定义见 F10:404-406） |
| C-10 | F10:159 / F10:262 | `fmt4(row.rank_ic)` ⟹ `v.toFixed(4)`（**4 位小数**；`fmt4` 定义见 F10:396-398） |
| C-11 | F11:378 | `prop="observations" label="截面日观测"`（backtest 面 `groupedReturns`） |
| C-12 | F11:833 | tooltip `截面日：${g.observations}` |
| C-13 | F11:275 | `{{ crossSectionDays }} 个截面日 / {{ totalObservations }} 个观测` |
| C-14 | F11:374 | `{{ (row.avgReturn * 100).toFixed(2) }}%`（backtest 面显示，**2 位小数百分号**） |
| C-15 | F07:110-141 | 既有引擎测试三例（严格递增 / 非单调 / 递减） |
| C-16 | F12:305-317 | 既有服务测试：断言 `daily_rank_ic[0].rank_ic == 1.0`、`grouped_returns` **非空**；**未断言** `sample_size` / `observations` 的任何语义或数值 |
| C-17 | F10:498-517 | `exportUnifiedCsv` 的列 = `holding_days, rank_ic, pearson_ic, icir, positive_rate, monotonicity, spread, net_ic, hac_std, ci_lower, ci_upper` ⟹ **导出不含两数组** |
| C-18 | F10:131 / F10:151 / F10:237 / F10:254 | 「观测数」这一标签在 **F10 内出现于 4 处**：`:131` 与 `:237` 指向 `total_observations`，`:151` 与 `:254` 指向 `grouped_returns[].observations` ⟹ **同一页面内一个标签指向两个不同量**（**仅统计 F10 内、且仅统计本轮被审的这两个数组**；F11 其他表中同名字段不在本轮范围） |

### 4.4 撤回 / 不进变体的锚点（记录，防止事后回填）

- **B-07**（`cost_net_ic` 不携带两数组）：**描述性登记**（登记为 `D-R014-*`），**不**成为变体、
  **不**参与 `K3`——理由：这是**覆盖缺口**而非数值不一致，且与 R011/R012 的覆盖类结论相邻（防重复研究）。
- **J-5**（符号约定）：本轮**只**在报告中描述「`label`（`G1(低分)`…`G5(高分)`）与 `avg_return` 的符号关系」，
  **不**设判定词、**不**参与 `K`——理由：R004 已审 `long_short_spread` 的口径结构。
- **A-10**（`vx < 1e-18`）：**只标注**为「R009 已审」，**不**产生任何变体。
- **撤回的锚点**：**无**。冻结时 12 个文件全部命中（命中率见报告）。

---

## 5. 变体表（**20 个**，5 轴；`MAX_VARIANTS = 20`）

| 轴 | 轴名 | 变体 | 判定词（预期） |
|---|---|---|---|
| **A1** | **可重建**（5） | V01 无并列面板的重算一致性 / V02 **含并列**面板的重算一致性 / **V03 同日同一多重集、两种输入行序** / V04 `rank_ic` 的 `round4` 截断量 / V05 载荷是否携带**分组边界规则** | V01/V02 `RECONSTRUCTABLE`（预期）；**V03 `NOT_RECONSTRUCTABLE`（预期）**；V04 `LOSSY_PRECISION`（描述性）；V05 `RULE_UNDECLARED`（描述性） |
| **A2** | **单位忠实**（4） | V06 聚合 `observations` 的语义（天数）vs 日级生产者（名字数） / V07 两面**展示标签**（「观测数」vs「截面日观测」） / V08 同一页内 `total_observations` 与 `grouped_returns[].observations` **共用同一标签** / V09 是否存在**字段级单位声明** | **V06/V07/V08 `INCONSISTENT_UNIT`（预期）**；V09 `UNIT_UNDECLARED`（描述性） |
| **A3** | **同量同值**（5） | V10 两面的 `rankIc`（`round4` vs 原样） / V11 两面的 `avgReturn` / V12 两面的 `sampleSize`/`observations`（原样 int） / V13 两面**默认表格的显示精度** / V14 载荷是否声明**取整规则** | **V10/V11 `VALUE_DIVERGENT`（预期）**；V12 `VALUE_CONSISTENT`（预期·正面对照）；V13 `VALUE_CONSISTENT`（预期·正面）；V14 `RULE_UNDECLARED`（描述性） |
| **A4** | **计数忠实**（4） | V15 `Σ sample_size` vs `total_observations` / **V16 非有限值进入当日截面**（NaN 的秩位置与计数） / V17 `minCrossSectionSize` 未达标日的静默跳过 / V18 零观测组行（`avg_return = 0.0`, `observations = 0`） | V15 `COUNT_FAITHFUL`（预期·正面对照）；**V16 `COUNT_UNFAITHFUL`（预期）**；V17 `DROPPED_SILENTLY`（**描述性，不计 K**）；V18 `PLACEHOLDER_MARKED`（预期·正面对照） |
| **A5** | **仪器**（2） | V19 金丝雀总门 / V20 正面对照 + 反向锚点（确定性、置换可撤销、`round4` 移除后杀点消失） | 见 §7 |

- **轴数和**：5 + 4 + 5 + 4 + 2 = **20** = `variants_total` ⟹ `row_sum = axis_total = 20`，`grid_mismatch_count = 0`。
- `CANARY_PAIRS = 20`（每个变体一对 `CLN`/`INJ`）。

---

## 6. 判定词表（**16 词，冻结**；表外词 ⟹ `K6` ⟹ `INCONCLUSIVE`）

`RECONSTRUCTABLE` / `NOT_RECONSTRUCTABLE` / `LOSSY_PRECISION` / `RULE_UNDECLARED` /
`CONSISTENT_UNIT` / `INCONSISTENT_UNIT` / `UNIT_UNDECLARED` /
`VALUE_CONSISTENT` / `VALUE_DIVERGENT` /
`COUNT_FAITHFUL` / `COUNT_UNFAITHFUL` / `PLACEHOLDER_MARKED` / `PLACEHOLDER_UNMARKED` /
`DROPPED_SILENTLY` / `DROPPED_DISCLOSED` / `NOT_REACHED_STATIC_ONLY`

---

## 7. 金丝雀（§11.3 / R011 教训 61–63 / R012–R013 沿用）

- **`CLN`（语义 = 声明键集合的确定性函数）**：向该变体的判定**输入键集合**塞入**未声明哨兵键**
  `__sentinel_cln__`（值取该变体**实测**值的第一项），**判定必须不变**。
  **禁止**把 `CLN` 实现成「谓词必须为真」（否认负结论）。
- **`INJ`（单键取反必翻转）**：注入值按**实测状态**取向（不得按期望状态取向）；
  **必须显式按键表**注入，**禁止**泛型取反（R013 教训）。
- **自指禁令**：checker 不得读取「由该 checker 自身结果算出」的键。
- **`V19`（金丝雀总门）**：要求 `CANARY_PAIRS == 20` 且 20 对全部通过；
  该变体**不得**把自身结果计入读数 ⟹ 以**声明数**填入并断言与实测数相等。
- **V20 反向锚点（三查）**：① 两次运行探针输出**逐字节相等**；② **撤销** V03 的置换后
  `avg_return` 与首次运行**逐位相等**；③ 把 `round4` 从映射路径**移除**后 V10 的 `VALUE_DIVERGENT` **消失**
  ⟹ 证明杀点由**被测机制**引起，**不是**仪器造出来的。

---

## 8. `K` 表与短路（**冻结**）

| `K` | 触发条件 |
|---|---|
| `K1` | ∃ 变体判定为 `NOT_RECONSTRUCTABLE` **且** `reading_independent == true`（(i) 可重建被证伪） |
| `K2` | ∃ 变体判定为 `INCONSISTENT_UNIT`（(ii) 单位忠实被证伪） |
| `K3` | ∃ 变体判定为 `VALUE_DIVERGENT`（(iii) 同量同值被证伪） |
| `K4` | ∃ 变体判定为 `COUNT_UNFAITHFUL` **且** 可达性 ≥ `REACHABLE_PUBLIC_API`（(iv) 计数忠实被证伪） |
| `K5` | 计数门：`row_sum != axis_total ∨ axis_total != variants_total ∨ variants_total != 20 ∨ grid_mismatch_count != 0 ∨ CANARY_PAIRS != 20` |
| `K6` | 仪器门：`off_vocab` 非空 ∨ 任一 `CLN`/`INJ` 失败 ∨ 探针未运行/非确定性 ∨ 禁数扫描命中 ∨ **`instrument_alerts` 非空** |
| `K7` | 冻结门：`prereg_sha256` 变化 ∨ **12 个冻结文件任一 sha256 变化** ∨ 两次运行静态结果不逐字节相等 |

**可达性等级（冻结）**：`REACHABLE_PUBLIC_API`（经公开静态 API 或公开请求字段可达）
> `REACHABLE_CLIENT_INPUT`（仅经客户端可传字段可达） > `SYNTHETIC_ONLY`（仅合成面板可达） > `UNREACHABLE`。

**重算容差（冻结，只用于算术重建比较；`J-6` 依据）**：

| 记号 | 值 | 用途 |
|---|---|---|
| `RECON_TOL_R4` | `5e-5` | 与 **`round4` 之后**的载荷值重建比较（`round4` 的半步） |
| `RECON_TOL_FP` | `1e-9` | 与**未取整**（DTO 面）值、以及两次运行之间比较（浮点容差） |

> 这两个容差是**算术比较容差**，**不是**经济显著性阈值；**不得**被挪用为任何因子/策略的合格线。

**短路（冻结）**：

```
if K5 or K6 or K7:  total_verdict = INCONCLUSIVE
elif K1 or K2 or K3 or K4:  total_verdict = REJECTED
else:  total_verdict = NOT_VIOLATED
```

`h0_alive = (total_verdict != REJECTED)`。

---

## 9. `P` 预测表（**事前**；标「读码」者来自源码阅读，**不是**实测）

| `P` | 预测 | 依据 |
|---|---|---|
| `P-01` | 无并列面板：载荷 `rank_ic` / `avg_return` 与**独立 Python 重算**在 `RECON_TOL_R4` 内逐位一致 ⟹ `RECONSTRUCTABLE` | 读码（A-03/A-09/A-13） |
| `P-02` | 含并列面板：同样 `RECONSTRUCTABLE`（`ranks()` 用**平均秩**） | 读码（A-09） |
| `P-03` | **V03 置换输入行序 ⟹ 至少一组 `avg_return` 改变** | 读码（A-02 稳定排序 + A-03 下标等分） |
| `P-04` | V03 中 `daily_rank_ic[].rank_ic` 序列**不随行序改变**（分歧**特定于** `grouped_returns`） | 读码（秩与顺序无关） |
| `P-05` | V04：凡 `rankIc` 不被 `1e-4` 整除者，载荷值 == `round4(引擎值)` 且 ≠ 引擎值（差 ≤ `5e-5`） | 读码（A-11/A-13） |
| `P-06` | V05：载荷中**不存在**分组边界规则文本（下标等分）⟹ `RULE_UNDECLARED` | 读码（C-01/C-02/C-03 无口径说明） |
| `P-07` | V06：聚合 `observations` == 该组贡献**天数**；且在 `n ≥ groupCount` 时**各组相等** == `cross_section_days` | 读码（A-07/A-08） |
| `P-08` | V07：`F11:378` 的标签**含**「截面日」，`F10:151` 的标签为「观测数」⟹ 两字符串不同 | 读码（C-05/C-11） |
| `P-09` | V08：`F10:131` 与 `F10:151` 的标签**逐字符相同**（「观测数」）而所指**对象/单位不同** | 读码（C-04/C-05） |
| `P-10` | V09：DTO record（`F04:98-103`/`F04:105-117`）与 TS interface（`F08:19-30`/`F09:61-62`）**均无**字段级单位声明 ⟹ `UNIT_UNDECLARED` | 读码（C-01…C-03/B-10） |
| `P-11` | V10：存在至少一天使 `round4(rankIc) != rankIc` ⟹ 两面向量不同 ⟹ `VALUE_DIVERGENT` | 读码（A-11 vs A-14） |
| `P-12` | V11：同上（`avgReturn`）⟹ `VALUE_DIVERGENT` | 读码（A-12 vs A-14） |
| `P-13` | V12：两面的 `sampleSize` / `observations` **逐元素相等**（int 原样） | 读码（A-06/A-11/A-14） |
| `P-14` | V13：两面**默认表格**的 `avg_return` 显示字符串在 2 位小数百分号下**一致** | 读码（C-09/C-14） |
| `P-15` | V14：载荷**无**取整规则声明 ⟹ `RULE_UNDECLARED` | 读码（C-01/C-02/C-03/C-17） |
| `P-16` | V15：`Σ daily_rank_ic[].sample_size == total_observations` | 读码（A-06：同一集合、同一次累加） |
| `P-17` | V16：**含 NaN 的当日**仍产出 `rank_ic`（有限）且 `sample_size` == **原始行数（含 NaN）** ⟹ `COUNT_UNFAITHFUL` | 读码（A-06 计数 + A-09 `Double.compare` 把 NaN 排最大） |
| `P-18` | V17：被 `minCrossSectionSize` 过滤的**日数**不出现在载荷任何字段中（无可分辨披露）⟹ `DROPPED_SILENTLY` | 读码（A-05） |
| `P-19` | V18：零观测组行 == `{avg_return: 0.0, observations: 0}` ⟹ `PLACEHOLDER_MARKED`（`observations = 0` 使其**可分辨**） | 读码（A-08） |
| `P-20` | V20：两次运行**逐字节相等**；撤销置换后 `avg_return` 逐位恢复；移除 `round4` 后 V10 的 `VALUE_DIVERGENT` 消失 | 读码 + 仪器设计 |

> **预测可中亦可不中**；不中者只进报告附录 `A-xx`，**不得**修改本表、**不得**放宽 `K` 表。

---

## 10. 关键参数 ±20% 敏感性（**必做**）

| 参数 | 冻结值 | ±20% 取值 | 来源 |
|---|---|---|---|
| `factorGroupCount` | **5** | **{4, 5, 6}** | `F02:70`（`RANK_IC_GROUP_COUNT`） |
| `RANK_IC_MIN_CROSS_SECTION_SIZE` | **10** | **{8, 10, 12}** | `F02:71` |

- **网格**：3 × 3 = **9 个单元** × **2 个冻结面板**（① 无并列 ② 含并列）= **18 个单元**。
- 每个单元记录：组数、`Σ observations`、`Σ sample_size`、**置换前后 `avg_return` 的最大绝对差**、
  以及 V03 的判定是否翻转（**灵敏度门**：若在任一单元 V03 由 `NOT_RECONSTRUCTABLE` 变 `RECONSTRUCTABLE`，
  该事实必须进报告附录并据此把 `K1` 标为**参数依赖**）。

---

## 11. 前视 / 幸存者偏差披露（`研究口径规范.md` §5）

- 本轮**不产生任何收益序列**、**不读任何行情/财务数据**、**不访问数据库**
  ⟹ **前视偏差影响 = 0、幸存者偏差影响 = 0**（证据：装置只读源码文本 + 喂**合成**的
  `(score, forwardReturn)` 对 + 调用公开静态 Java API）。
- **明确声明**：合成面板**不**含 T+1 建仓、涨跌停/停牌拒单语义 ⟹ 本轮**不检验**这些口径，
  **不得**把本轮结论外推为「真实数据上这两个数组的数值质量」。
- 冻结口径（佣金万三 / 印花税万五 / 滑点万二 / 入场次日开盘 / 退出 h 日收盘 /
  `NOT_BUYABLE` 等状态**不得删除样本** / `rule_score` 是 **0–100 截面百分位、不是上涨概率**）
  **一律不变**，本轮**不重算**任何成本。

---

## 12. 冻结纪律

1. **本文件先于任何实验代码**；`R014_review.md` 先于本文件。
2. **偏离与勘误**：实测与预注册不符 ⟹ 逐条进 `R014_report.md` 附录 `A-xx`；**绝不**改写本文件。
3. **读数敏感性**：文本类主张须用 (a) 子串（**大小写无关**）/ (b) **按被测对象类型分派**的收紧读数 /
   (c) 人工逐字 三种读数各测一次；**判定被读数方式改变者**必须标 `reading_dependent = true`
   并**排除出 `K1` 候选集**，且在报告中**降级为描述性**（R012 教训 67/69 + R013 沿用）。
4. **否定式声明不得用被否定对象的子串来测**（R012 教训 68）。
5. **判定事实必须来自冻结扫描根内**（R012 教训 70）；根外文件只作 `corroboration`
   ⟹ `instrument_alerts` ⟹ `K6`。
6. **探针**：编译进生产引擎自己的包（`javac -d <repo>/modules/backend-java/target/classes`，
   必须带 `-cp target/classes` 与 `-encoding UTF-8`，R010 教训 59 / R013 教训）；
   运行**两次**并要求输出**逐字节相等**，运行末尾**清理字节码**（`git status` 复核无残留）。
7. **`run.log` 为 append-only**，**不得**删改任何一次运行（含失败）。
8. **零生产代码改动**：R014 的全部改动只允许落在 `research/paper_loop/**`。
9. **工程纪律（R013 教训逐条沿用）**：subprocess 读文本必须 `encoding="utf-8", errors="replace"`；
   合成面板的 `OBS` 行**必须自含日期字段**；跨面板缓存**必须按 panel id 分键**；
   探针输出解析**必须保留全部字段并数值强转**；**仪器门未全绿时不得下结论**。
10. **`_scratch` 产物可提交**（R013 先例：`panel_R014_cells.txt` / `probe_raw.txt` / `probe_err.txt`）。

---

## 13. 台账联动（收口时**逐份**说明改了什么、或**为什么**不改）

`paper_ledger.csv` / `shadow_registry.md` / `blocked_queue.md` / `decision_queue.md` / `progress.md` 五份。

- `shadow_registry.md`：本轮**不提出任何新因子** ⟹ 预期**不改**（报告中显式说明理由）。
- `blocked_queue.md`：登记 **`B-R014-01`**（arXiv API / Semantic Scholar 经代理 429 限流，海外检索单通道）
  与 **`B-R014-02`**（载荷端到端形状与 **DB 行序**的影响面需容器 + DB，未测量）。
- `decision_queue.md`：登记 `D-R014-*`（**只登记、不实施**：改分组规则/抓取并列秩/改 `round4`/
  改 DTO 字段/改前端列标签/加单位声明均属协议 §7 record-only）
- `paper_ledger.csv`：追加 **CZ2022** 一行（含载体哈希、判据档位、`X` 档禁用清单、本轮判定）。
- `progress.md`：§1–§7 按 R013 同样式回填。
- **另**：R014 收口时**顺带**以 `R013_report.md` **附录勘误**形式披露并订正 CCW2024 的档位笔误
  （`R013_prereg.md:7` 与 `R013_report.md:4/:368` 写「**A 档**」，而 `paper_ledger.csv`、
  `R013_review.md` §4.1、`progress.md` §1/§2/§3 为「**C 档**」）——**冻结文本 `R013_prereg.md` 不得改写**。

---

## 14. 负面清单（冻结，与 `review §7` 八条**并行生效**）

9. **不得**修改分组算法、`ranks()`、`round4`、DTO 字段、`factor.ts` 类型或前端列标签——**只登记**；
10. **不得**把「聚合 `observations` 是天数」叙述成「用户看到的因子结论是错的」或「因子无效」；
11. **不得**据本轮任何量级设定阈值、容差、合格线（`RECON_TOL_*` 只用于算术重建比较）；
12. **不得**把 `NOT_VIOLATED` 硬凑成 `REJECTED`（反向亦然）；**不得**因连续十三轮无采纳产出而放宽条件；
13. **不得**把 V03 的置换敏感性叙述成「真实数据上分组收益会随机跳变」——本轮只证明**未声明的行序依赖**存在，
    真实数据的行序来自 DB，其影响面登记为 `B-R014-02`（**未测量**）。
