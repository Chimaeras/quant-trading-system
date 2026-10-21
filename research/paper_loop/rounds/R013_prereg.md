# R013 预注册（**冻结**）

> **冻结声明**：本文件在**任何 R013 实验代码**（`make_config.py` / 探针 / 执行器）与**任何实测读数**之前写成。
> 冻结后**不得**修改本文件的任何判定条件、变体、词表、K 表、P 表与条件阈值；
> 实测不符只进 `rounds/R013_report.md` 附录 `A-xx`（**勘误**），**绝不**回写本文件。
> 判据来源：**CCW2024**（Cattaneo, Crump, Wang, *Beta-Sorted Portfolios*, arXiv:2208.10974v3，
> **A 档**，**CC BY-NC-ND 4.0**）——检索、初筛、适配性七问与判据抽取见 `rounds/R013_review.md`。
> 冻结时仓库 HEAD：`006baaca4d7768ec15a933b0d76803d1a273ba28`。

---

## 1. 被审计对象（不是论文）

**被审计对象 = 项目自己的 `monotonicity` 标签**：生产者 `FactorResearchEngine.assessMonotonicity`
（`FactorResearchEngine.java:226-249`），发射面 4 处（`FactorResearchService.java:543` / `:569` / `:762` / `:831`），
声明面 `modules/web/src/api/factor.ts:111` / `:202`，展示面 `FactorResearch.vue:137` / `:313-314` / `:506-509`。

**H0（四子句）**：

> **(i) 名实**：每个取值标签的**可证伪语义**与**实现判定**一致——即标签所断言的「**方向性证据**」确实存在；
> **(ii) 非退化**：退化域（空组占位、非有限组均值、组数 ≤ 1、组数超界）**不得**产出具名方向断言，
> 且**不得**抛出未处理异常；
> **(iii) 可重建**：给定**载荷自身**携带的信息，标签可被**唯一重建**（含并列/退化规则），
> 且值的**定义域**在契约中声明；
> **(iv) 不越界**：任何声明或展示面**不得**把该**点估计标签**表述为**统计/条件期望层面的推断结论**。

**边界（先写死）**：`(iv)` **不**要求项目做推断，只要求**不声称**；若各面均未声称，`(iv)` 记为**未触发**。

---

## 2. 判据（**只支持做法种类**，不支持任何阈值）

| 判据 | 内容 | 档位 | 用于 |
|---|---|---|---|
| **J-1** | 排序组合收益是**两步非参估计量**的产物 ⟹ 一组点估计序列本身**不构成**形状/单调性结论的证据 | **L** | A4（推断边界） |
| **J-2** | 分组边界由估计值决定、**逐期随机** ⟹ 披露粒度必须是「**构造 + 每组样本量**」，而不是「只给一个标签」 | **L** | A3-V18、A4 |
| **J-3** | **单期**差分收益被一个**额外项污染** ⟹ 不得据短窗组收益做「条件期望时间序列性质」的推断 | **L** | A4 |
| **J-4** | 论文的 uniform inference / FM 方差性质 / PI 构造 / 速率与假设 / **全部实证数值** | **X（不可移植）** | **不使用** |

**禁用清单（先写死）**：`review §7` 八条**全部**沿用；另**禁止**在本轮任何产物中出现
「项目**应当**实现 `J` 的最优化」「项目**应当**做 uniform inference」这类**要求**表述。

---

## 3. 冻结扫描根（**只读**；共 11 个文件）与冻结哈希

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

- **合计 11 个文件、252,117 字节**；`K7`（冻结门）要求 **11 个 sha256 全部不变**。
- **冻结扫描根之外**的文件（若有）只能作 `corroboration`（**旁证**）：其失败走 `instrument_alerts`
  通道并**触发 `K6`**，**绝不**驱动任何变体的判定词（R012 教训 70，本轮沿用）。

---

## 4. 冻结锚点表

### 4.1 实现锚点（A 组）

| 锚 | 文件:行 | 内容（冻结摘要） |
|---|---|---|
| A-01 | F01:226-249 | `assessMonotonicity` 全体：`size < 2 → "N/A"`；两个 flag 皆初始 `true`；falsifier 仅 `cur < prev`（increasing）/ `cur > prev`（decreasing）；**`increasing` 分支在前** |
| A-02 | F01:114 | 空桶占位：`double avg = rets.isEmpty() ? 0 : rets.stream()...average().orElse(0)` |
| A-03 | F01:182 | 聚合侧同样占位：`rets.isEmpty() ? 0 : …`（`bucketReturns` 缺键 = 该组当日**从未**产生） |
| A-04 | F01:102-103 | `int g = Math.min(groupCount, n); List<List<Double>> buckets = new ArrayList<>(g);`（`g < 0` ⟹ 负容量） |
| A-05 | F01:180-183 | `for (int g = 1; g <= groupCount; g++)` 的组号遍历（与 A-04 的 `g` **不同源**） |
| A-06 | F01:187 | `String monotonicity = assessMonotonicity(grouped);`（**唯一的标签计算点**） |
| A-07 | F01:214-221 | `emptySummary(...)` 硬写 `"N/A"`（`daily.isEmpty()` 时） |
| A-08 | F03:78 | `int factorGroupCount = req.getFactorGroupCount() != null ? req.getFactorGroupCount() : 5;` |
| A-09 | F03:431-437 | SBS 调用 `summarize(..., factorGroupCount, FACTOR_MIN_CROSS_SECTION_SIZE, FACTOR_UNIVERSE_NOTE)` |
| A-10 | F05:37-38 | `/** 因子分组数（截面 IC / 分组收益研究），默认 5 */ private Integer factorGroupCount = 5;`（**无校验注解**） |

### 4.2 发射 / 映射锚点（B 组）

| 锚 | 文件:行 | 内容 |
|---|---|---|
| B-01 | F02:543 | `block.put("monotonicity", summary.monotonicity())`（snapshot 口径块） |
| B-02 | F02:569 | `block.put("monotonicity", "N/A")`（`unavailableScopeBlock` 硬写） |
| B-03 | F02:762 | `block.put("monotonicity", summary.monotonicity())`（统一报告块） |
| B-04 | F02:831 | `net.put("monotonicity", s.monotonicity())`（成本后净收益块） |
| B-05 | F02:592-600 | `groupedReturnList`：`group_no` / `label` / **`avg_return = round4(...)`** / `observations` |
| B-06 | F02:1030-1032 | `round4(v) = Math.round(v * 10000.0) / 10000.0` |
| B-07 | F03:1024-1048 | `toFactorAnalysisDto`：映射 `groupedReturns`（**含 `observations`**，`avgReturn` **不取整**）——**无** `monotonicity` |
| B-08 | F03:1051-1065 | `toFactorIcSummaryDto`：映射 9 个标量 + `longShortSpread`——**既无** `monotonicity` **也无** `groupedReturns` |
| B-09 | F04:80-92 | `FactorAnalysis` 字段清单（**无** `monotonicity`） |
| B-10 | F04:105-117 | `GroupReturnStat` 字段清单（`groupNo` / `label` / `avgReturn` / `observations`） |
| B-11 | F02:70-71 | `RANK_IC_GROUP_COUNT = 5`、`RANK_IC_MIN_CROSS_SECTION_SIZE = 10` |
| B-12 | F02:650 | `block.put("group_count", RANK_IC_GROUP_COUNT)`（该面**披露组数**） |

### 4.3 声明 / 展示锚点（C 组）

| 锚 | 文件:行 | 内容 |
|---|---|---|
| C-01 | F08:111 | `monotonicity: string`（`SnapshotScopeIc`） |
| C-02 | F08:202 | `monotonicity: string`（`UnifiedReportHorizonRow`） |
| C-03 | F01:224 | javadoc 「分组单调性判断：G1→G5 收益是否单调递增（正向单调）或递减（负向单调）。」 |
| C-04 | F10:137 | `<span>单调性：{{ blk.monotonicity }}</span>`（**原样渲染**） |
| C-05 | F10:313-314 | `<el-table-column label="单调性" width="150">` + 原样 `{{ row.monotonicity }}` |
| C-06 | F10:506-509 | CSV 导出列序含 `monotonicity`（原样导出） |
| C-07 | F11:360 | 「按得分分组的表现（验证单调性：分数越高是否收益越高）」 |
| C-08 | F06:34 | 控制器注释「…并列 Pearson IC/Rank IC/ICIR/正率/**单调性**/spread」 |
| C-09 | F07:110-141 | 既有测试 3 个：`…Increasing`（严格递增）/ `…NonMonotonic` / `…Decreasing` |

### 4.4 撤回的锚点（记录，防止事后回填）

- **无**。冻结时 11 个文件全部命中（见 `R013_report.md` 的锚点命中率）。

---

## 5. 变体表（**21 个**，5 轴；`MAX_VARIANTS = 21`）

| 轴 | 轴名 | 变体 | 判定词（预期） |
|---|---|---|---|
| **A1** | **名实**（4→5） | V01 严格递增 / V02 严格递减 / V03 波动 / **V04 常量（零斜率）** / **V05 常量下两方向 flag 同时存活 ⟹ 取值由分支顺序决定** | V01–V03 `SEMANTICS_FAITHFUL`；V04–V05 `SEMANTICS_GAPPED` |
| **A2** | **非退化**（5） | V06 空桶占位面板 / V07 可达性链（客户端可传 `factorGroupCount`、无校验） / V08 `groupCount=1` / V09 `groupCount=0` / V10 `groupCount=-1` | V06 `DEGENERATE_ASSERTS`（预期）；V07 记录可达性等级；V08/V10 `DEGENERATE_SAFE`（预期）；V09 记录信息缺口 |
| **A3** | **可重建**（5） | V11 `avg_return` 4 位取整 vs 全精度重建 / V12 `monotonicity` 是否声明值域 / V13 `FactorAnalysis` 是否携带标签 / V14 `FactorIcSummary` 是否携带标签或分组 / V15 四个发射面是否**同词表** | V11 `NOT_RECONSTRUCTABLE`（预期）；V12 `CLAIM_ABSENT`（预期）；V13/V14 `CLAIM_ABSENT`（预期）；V15 `CONSISTENT_VOCAB`/`INCONSISTENT_VOCAB` |
| **A4** | **推断边界**（3） | V16 声明性文本是否声称推断 / V17 展示面是否声称推断 / V18 披露粒度（组数 + 每组样本量） | V16/V17 `INFERENCE_NOT_CLAIMED`（预期）；V18 `TRUE_CLAIM`/`FALSE_CLAIM` |
| **A5** | **仪器**（3） | V19 金丝雀总门 / V20 正面对照（常量到达载荷） / V21 反向锚点（`N/A` 的两义性 + `NON_MONOTONIC` 仅内部产生） | 见 §7 |

- **轴数和**：5 + 5 + 5 + 3 + 3 = **21** = `variants_total` ⟹ `row_sum = axis_total = 21`，`grid_mismatch_count = 0`。
- `CANARY_PAIRS = 21`（每个变体一对 `CLN`/`INJ`）。

---

## 6. 判定词表（**16 词，冻结**；表外词 ⟹ `K6` ⟹ `INCONCLUSIVE`）

`SEMANTICS_FAITHFUL` / `SEMANTICS_GAPPED` / `DEGENERATE_SAFE` / `DEGENERATE_ASSERTS` /
`RECONSTRUCTABLE` / `NOT_RECONSTRUCTABLE` / `INFERENCE_NOT_CLAIMED` / `INFERENCE_CLAIMED` /
`CLAIM_ABSENT` / `CLAIM_UNDECIDABLE_STATIC` / `UNFALSIFIABLE` / `TRUE_CLAIM` / `FALSE_CLAIM` /
`CONSISTENT_VOCAB` / `INCONSISTENT_VOCAB` / `NOT_REACHED_STATIC_ONLY`

---

## 7. 金丝雀（§11.3 / R011 教训 61–63 / R012 沿用）

- **`CLN`（语义 = 声明键集合的确定性函数）**：向该变体的判定**输入键集合**塞入**未声明哨兵键**
  `__sentinel_cln__`（值取该变体**实测**值的第一项），**判定必须不变**。
  **禁止**把 `CLN` 实现成「谓词必须为真」（否认负结论）。
- **`INJ`（单键取反必翻转）**：注入值按**实测状态**取向（不得按期望状态取向）。
- **自指禁令**：checker 不得读取「由该 checker 自身结果算出」的键。
- **`V19`（金丝雀总门）**：要求 `CANARY_PAIRS == 21` 且 21 对全部通过；
  该变体**不得**把自身结果计入读数（R011 教训 63）⟹ 以**声明数**填入并断言与实测数相等。

---

## 8. `K` 表与短路（**冻结**）

| `K` | 触发条件 |
|---|---|
| `K1` | ∃ 变体判定为 `SEMANTICS_GAPPED` **且** `reading_independent == true`（(i) 名实被证伪） |
| `K2` | ∃ 变体判定为 `DEGENERATE_ASSERTS` **且** 可达性 ≥ `REACHABLE_PUBLIC_API`（(ii) 非退化被证伪） |
| `K3` | ∃ 变体判定为 `NOT_RECONSTRUCTABLE`（(iii) 可重建被证伪） |
| `K4` | ∃ 变体判定为 `INFERENCE_CLAIMED`（(iv) 不越界被证伪） |
| `K5` | 计数门：`row_sum != axis_total ∨ axis_total != variants_total ∨ variants_total != 21 ∨ grid_mismatch_count != 0 ∨ CANARY_PAIRS != 21` |
| `K6` | 仪器门：`off_vocab` 非空 ∨ 任一 `CLN`/`INJ` 失败 ∨ 探针未运行/非确定性 ∨ 禁数扫描命中 ∨ **`instrument_alerts` 非空** |
| `K7` | 冻结门：`prereg_sha256` 变化 ∨ 11 个冻结文件任一 sha256 变化 ∨ 两次运行静态结果不逐字节相等 |

**可达性等级（冻结）**：`REACHABLE_PUBLIC_API`（经公开静态 API 或公开请求字段可达）
> `REACHABLE_CLIENT_INPUT`（仅经客户端可传字段可达） > `SYNTHETIC_ONLY`（仅合成面板可达） > `UNREACHABLE`。

**短路（冻结）**：

```
if K5 or K6 or K7:  total_verdict = INCONCLUSIVE
elif K1 or K2 or K3 or K4:  total_verdict = REJECTED
else:  total_verdict = NOT_VIOLATED
```

`h0_alive = (total_verdict != REJECTED)`。

---

## 9. `P` 预测表（**事前**；encoding 必须**忠实携带量级**，不得退化为布尔）

| `P` | 预测 | 依据 |
|---|---|---|
| `P-01` | 严格递增面板 ⟹ 标签 == `MONOTONIC_INCREASING` | 读码（A-01） |
| `P-02` | 严格递减面板 ⟹ 标签 == `MONOTONIC_DECREASING` | 读码 |
| `P-03` | 波动面板 ⟹ 标签 == `NON_MONOTONIC` | 读码 |
| `P-04` | 常量面板 ⟹ 标签 ∈ {`MONOTONIC_INCREASING`, `MONOTONIC_DECREASING`}（即**零斜率被断言方向**） | 读码（A-01 falsifier 仅严格不等） |
| `P-05` | 常量面板下 `increasing` 与 `decreasing` **两个 flag 同时为 `true`** ⟹ 取值由**分支顺序**（`increasing` 在前）决定 | 读码 |
| `P-06` | `groupCount = 20` 且每日 10 个样本 ⟹ `groupedReturns` 长度 == **20**，其中**恰好 10 个**组满足 `observations == 0` 且 `avgReturn == 0.0` | 读码（A-02/A-05） |
| `P-07` | 上述面板的标签 ≠ `"N/A"`（即**具名断言建立在占位零上**） | 读码 |
| `P-08` | `groupCount = 1` ⟹ 标签 == `"N/A"` | 读码（A-01 size<2） |
| `P-09` | `groupCount = 0` ⟹ 标签 == `"N/A"` 且 `groupedReturns` 为空**而** `totalObservations > 0`（信息缺口） | 读码 |
| `P-10` | `groupCount = -1` ⟹ 抛 `IllegalArgumentException`（未处理客户端输入） | 读码（A-04 负容量） |
| `P-11` | `F04` 的 `FactorAnalysis` **无** `monotonicity` 构建方法而 **有** `groupedReturns` | 读码（B-07/B-09） |
| `P-12` | `F04` 的 `FactorIcSummary` **既无** `monotonicity` **也无** `groupedReturns` | 读码（B-08） |
| `P-13` | `F08` 的 `monotonicity` **无值域声明**（无 union / enum / 字面量集合；仅 `string`） | 读码（C-01/C-02） |
| `P-14` | 4 个发射面（`B-01`/`B-02`/`B-03`/`B-04`）**同词表**（`N/A` 与引擎 `"N/A"` 逐字符相同） | 读码 |
| `P-15` | 声明与展示面**均未**声称推断 ⟹ `INFERENCE_NOT_CLAIMED`（**负结论**） | 读码（C-03…C-09 无推断词） |
| `P-16` | **`V11` 存在反例**：存在一组组均值使 `round4` 后相等、全精度不等 ⟹ 由载荷重建的标签与引擎标签**不同** | 读码（B-05/B-06 与 A-01） |
| `P-17` | **±20% 网格**：在**非退化**面板上 `groupCount ∈ {4,5,6}` × `minSize ∈ {8,10,12}` 的标签**不变** | 读码 |
| `P-18` | `F07` 既有测试**未覆盖** 常量 / 非有限 / 空桶占位 / `groupCount > n` / `groupCount ≤ 0` 五类退化 | 读码（C-09） |

> **预测可中亦可不中**；不中者只进报告附录 `A-xx`，**不得**修改本表、**不得**放宽 `K` 表。
> 标「读码」的预测来自**源码阅读**（R006 §5 先例：冻结时已读码确认），**不是**实测。

---

## 10. 关键参数 ±20% 敏感性（**必做**）

| 参数 | 冻结值 | ±20% 取值 | 来源 |
|---|---|---|---|
| `factorGroupCount` | **5** | **{4, 5, 6}** | `F05:38`（默认 5） |
| `FACTOR_MIN_CROSS_SECTION_SIZE` | **10** | **{8, 10, 12}** | `F03` 常量（R012 已核验） |

- **网格**：3 × 3 = **9 个单元** × **2 个冻结面板**（① 非退化面板 ② 触发空桶的面板）= **18 个单元**。
- 每个单元记录：标签字符串、`groupedReturns.size()`、`observations == 0` 的组数、`totalObservations`。

---

## 11. 前视 / 幸存者偏差披露（`研究口径规范.md` §5）

- 本轮**不产生任何收益序列**、**不读任何行情/财务数据**、**不访问数据库**
  ⟹ **前视偏差影响 = 0、幸存者偏差影响 = 0**（证据：装置只读源码文本 + 喂**合成**的
  `(score, forwardReturn)` 对）。
- **明确声明**：本轮合成面板**不**含 T+1 建仓、涨跌停/停牌拒单语义
  ⟹ 本轮**不检验**这些口径，**不得**把本轮结论外推为「真实数据上的分组收益标签质量」。
- 冻结口径（佣金万三 / 印花税万五 / 滑点万二 / 入场次日开盘 / 退出 h 日收盘 /
  `NOT_BUYABLE` 等状态**不得删除样本** / `rule_score` 是 **0–100 截面百分位、不是上涨概率**）
  **一律不变**，本轮**不重算**任何成本。

---

## 12. 冻结纪律

1. **本文件先于任何实验代码**；`R013_review.md` 先于本文件。
2. **偏离与勘误**：实测与预注册不符 ⟹ 逐条进 `R013_report.md` 附录 `A-xx`；**绝不**改写本文件。
3. **读数敏感性**：同一批主张须用 (a) 子串（**大小写无关**）/ (b) **按被测对象类型分派**的收紧读数 /
   (c) 人工逐字 三种读数各测一次；**判定被读数方式改变者**必须标 `reading_dependent = true`
   并**排除出 `K1` 候选集**，且在报告中**降级为描述性**（R012 教训 67/69 的直接沿用）。
4. **否定式声明不得用被否定对象的子串来测**（R012 教训 68）：如「不读 X」类文本，谓词必须收到**标识符级**。
5. **判定事实必须来自冻结扫描根内**（R012 教训 70）；根外文件只作 `corroboration` ⟹ `instrument_alerts` ⟹ `K6`。
6. **探针**：编译进生产引擎自己的包（`javac -d <repo>/modules/backend-java/target/classes`，R010 教训 59），
   运行**两次**并要求输出**逐字节相等**，运行末尾**清理字节码**。
7. **`run.log` 为 append-only**，**不得**删改任何一次运行（含失败）。
8. **零生产代码改动**：R013 的全部改动只允许落在 `research/paper_loop/**`。

---

## 13. 台账联动（收口时**逐份**说明改了什么、或**为什么**不改）

`paper_ledger.csv` / `shadow_registry.md` / `blocked_queue.md` / `decision_queue.md` / `progress.md` 五份。
- `shadow_registry.md`：本轮**不提出任何新因子** ⟹ 预期**不改**（须在报告 §8 显式说明理由）。
- 其余四份：登记 `D-R013-*`（**只登记、不实施**：改算法/扩值域/改 TS 类型/改 DTO/加校验均属 §7 record-only）
  与 `B-R013-01`（载荷端到端观测需容器 + DB）。

---

## 14. 负面清单（冻结，与 `review §7` 八条**并行生效**）

9. **不得**修改 `assessMonotonicity`、`factorGroupCount` 校验、`factor.ts` 类型、DTO 字段或 Mapper
   ——**只登记**；
10. **不得**把 `MONOTONIC_INCREASING` 的常量序列情形叙述成「用户看到的因子是单调的」或反之；
11. **不得**据本轮任何量级设定阈值、容差、合格线；
12. **不得**把 `NOT_VIOLATED` 硬凑成 `REJECTED`（反向亦然）；**不得**因连续十二轮无采纳产出而放宽条件。
