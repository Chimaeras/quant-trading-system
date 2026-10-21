# R009 报告：Pearson 侧 IC 载荷族审计（`pearson_ic_mean` / `pearsonCorrelation` / `dailyPearson`）

- **预注册**：`rounds/R009_prereg.md`（冻结于 `923c2763`，**先于**任何实验代码）｜ 论文审查：`rounds/R009_review.md`
- **被审计对象**：项目**自己**的契约主张（H0 = 「`pearson_ic_mean` 忠实对应其声明定义；定义不适用时不输出像测量值的实数；支撑集对读者可见」，见 prereg §1.2）
- **本轮性质**：**只读审计**（与 R004–R008 同型）。论文（Shin 等, arXiv:2401.02710v2）**只作判据来源**，其结论**一条未采纳**
- **总判定**：`REJECTED` ｜ `h0_alive = false` ｜ `K1/K2/K3` 同时触发（三条**互相独立**的杀点），`K4/K5` 亦触发（被 `K1∨K2∨K3` 短路）
- **金丝雀**：18/18 对 PASS（CLN ≠ INJ 且与运行前写下的期望一致）；`K8 = K9 = K10 = false`
- **生产/测试/前端/契约代码改动**：**零**（Java 350 / Python 343 与基线逐项一致）
- **运行次数**：`run_pearson_audit.py` 共 **5 次**（1 次 harness 依赖缺失中止、1 次金丝雀失败、2 次仪器口径缺陷、1 次完整）——见 §12

> **一句话**：`pearsonCorrelation` 的 NaN 守卫用的是**未归一化平方和** `Σ(x−x̄)²` 与**绝对**阈值 `1e-18`，
> 于是它破坏的是**尺度不变性**（不是 javadoc 声明的「方差为 0」）：把项目**自己既有测试的输入**整体乘 `1e-10`
> 就使 ρ 从 `1.0` 变 `NaN`，而两侧方差都 > 0、秩相关仍为 `1.0`；更严重的是
> `pearsonIcMean = dailyPearson.isEmpty() ? 0 : average(...)` 使「**完全不可定义**」与「**真 0 相关**」
> 产出**逐字符相同**的载荷。三条杀点全部落在**实测量**上，不是读码推断。

---

## §1 本轮怎么做的

### 1.1 判据来源（只借判据，不采纳其结论）

论文提供两条可引用判据（逐字摘录见 `rounds/R009_review.md` §2）：

- **判据甲（L 档）**：IC 就是**Pearson 相关系数**（α 因子值与目标变量之间），Rank IC 是**Spearman 秩相关**，
  二者被论文**并列成两列** ⟹ 直接支持「Pearson 侧与 Spearman 侧是**两个不同的量**」这一被审计前提。
- **判据乙（L 档）**：论文的绩效表按《定义 → 度量》逐个声明口径 ⟹ 支持「**口径必须声明到可判断的程度**」，
  以及「定义**不适用**时不得输出像测量值的实数」。

论文为 arXiv cs.CE（ICOIN 2024），A 股 CSI300 实证、**无成本披露**。**两条核心判据均为 P 档**：

| 判据 | 档 | 说明 |
|---|---|---|
| 「ρ 对正仿射变换不变」 | **A** | 算术：ρ 是标准化量的比值，平移与正尺度**必然**不变 |
| 「同一相对离散度下 ρ 的真值恒为 `1.0`」 | **A** | 算术 |
| `c = 1e-10` 这一**量级线**的选择 | **P** | 项目自建 |
| 「载荷不可区分 ⟹ 不冒充测量值」 | **P** | 项目自建判据 |
| 一切数值阈值（`TOL_BIT/TOL_RHO = 1e-12` 等） | **P** | 项目自建 |

> **不得**把 A 档算术结论包装成「文献要求」；文献只支持「做法种类」。R007 教训第 44 条（带阈值的断言必须写成
> 「在**自设量级线**下实测为 X」）在本轮的三条承重断言上逐条适用。

### 1.2 执行方式（防掩码）

- 探针 `probe/R009Probe.java` 编进 `com.quant.service.backtest`，**调用生产实现**
  （`FactorResearchEngine.pearsonCorrelation` / `spearmanRankCorrelation` / `summarize`
  以及 `FactorResearchService` 的 5 个载荷发射点，9 参数构造器 + 反射调用私有 block builder）。
- 探针里的 `control_*` 量（`controlVariance` = 未归一化平方和、`controlRankVariance(n) = n(n²−1)/12`）
  **只作对照值**，且与被审计量**同输入并列打印**；`pearsonSupportDays(...)` 逐日复算有限性时
  **仍调用生产静态方法**，不重实现生产量。
- **G3 门实测**：`engine_code_source = file:/E:/Project/quant-trading-system/modules/backend-java/target/classes/`
  ⟹ 归一化后以 `target/classes` 结尾，`is_repo_classes = true`。断言对象确为仓库自己的生产类。
- **反向锚点**（必须为真，否则仪器不可信）：`sentinel_normal_equals = 1`（正常面板 ρ = `1.0` 与对照一致）、
  `sentinel_defect_present = 1`（缺陷面板 ρ = `NaN`）、`existing_test_input_ok = 1`
  （用**项目自己既有测试的字面输入** `scores=[1..10]`/`returns=[0.01..0.10]` 驱动生产实现得 `1.0`，
  与 `FactorResearchEngineTest.java:88` 的 `isCloseTo(1.0, offset(1e-9))` 一致）。
- **对照值不充当被审计量**：A2a/A2b 的判定由「生产 ρ 的有限性」与「对照方差是否 > 0」共同决定；
  A4a 的判定由**载荷逐字符比对**决定，不由探针自报的布尔值决定。
- **harness 依赖钉死**（继承 R008）：`slf4j-api 2.0.9`；`spring-core/beans/context/tx/aop 6.1.1`；
  `spring-data-commons/jpa 3.2.0`；`jakarta.persistence-api 3.1.0` —— 共 **9 个 jar**，
  缺任一即**显式失败**（本段第 1 次运行即因此中止，见附录 A-02），**不得**静默换版本。
- **只读红线**：18 个变体中**仅 A3c 触真实库**，且只有**一条** `SELECT`（`GROUP BY` 聚合，无 `LIMIT`）；
  其余 17 个变体 **0 条 SQL**。

### 1.3 与 R008 的 harness 差异（诚实披露，另见附录 A）

| # | 差异 | 原因 |
|---|---|---|
| 1 | 首次出现**反射调用私有方法**（`buildSnapshotScopeBlock` 等 4 个 block builder + `round4`） | A4a/A4c/A6a 必须观测**真实发射的键集**；无 DB、无 Spring 容器下唯一可行路径 |
| 2 | 首次出现**单条只读 SQL**（A3c） | 分值离散度的**全表普查**必须是生产库而非构造面板（附录 A-03 披露口令解析缺陷） |
| 3 | 首次使用**载荷不可区分性比对**（A4a 的递归规范序列化） | 「不冒充测量值」必须变成可证伪的**逐字符**断言 |
| 4 | 新增 `panel_design_selfcheck`（44 条谓词，A1b） | 金丝雀**不能**发现「面板设计错」，须单独自检（R008 教训的延续） |
| 5 | `count_pattern` 引入**遍历缓存** | 20 个计数断言逐一重走全仓会使单次运行 > 6 分钟；缓存**不改变语义**（附录 A-09） |

---

## §2 逐轴结果（18 断言，7 组）

冻结判据：见 `rounds/R009_prereg.md` §4。**判定词表 8 词**，表外词 ⟹ `K9`（本轮 `0` 个表外词）。
**预注册预期偏差**：**1 条**（A3c，见 §4）。

| 轴 | 变体 | 判定词（实测） | 预注册预期 | 承重证据（实测值） |
|---|---|---|---|---|
| **A1a** | V01 | `NOT_VIOLATED` | `NOT_VIOLATED` ✅ | 反向锚点三项全真；`sentinel_normal_rho = 1.0`、`sentinel_defect_rho = NaN`、`existing_test_input_rho = 1.0` |
| **A1b** | V02 | `NOT_VIOLATED` | `NOT_VIOLATED` ✅ | 确定性：两次独立构造的面板 `pearsonIcMean = 1.0/1.0`、`crossSectionDays = 1/1`、`totalObservations = 10/10`、`monotonicity` 同值；两份载荷**规范序列化逐字符相同**（`payload_len = 1060`）；44/44 面板设计谓词 PASS |
| **A2a** | V03 | **`VIOLATED`** | `VIOLATED` ✅ | `ρ(P1) = 1.0`、`ρ(P1t) = 1.0`（平移 `+1000`）、**`ρ(P1s) = NaN`**（尺度 `c=1e-10`）、`var(P1s.scores) = 8.25e-19 > 0`、`spearmanRankCorrelation(P1s) = 1.0`（秩空间**有限**） |
| **A2b** | V04 | **`VIOLATED`** | `VIOLATED` ✅ | 同一**相对**离散度 `δ=1e-10`：`n=10` ⟹ `var = 8.25e-19` ⟹ **NaN**；`n=11` ⟹ `1.10e-18` ⟹ `0.9999999999999999`；`n=12` ⟹ `1.43e-18` ⟹ `1.0000000000000002`；三格 `var > 0`、秩相关全有限 |
| **A2c** | V05 | `NOT_VIOLATED` | `NOT_VIOLATED` ✅ | 判据来源逐字文本（**PDF 断词归一化后**）与项目计算对象同为「因子值 vs 未来收益的 Pearson 相关」：`review_criteria_jia_quote_present = 1`、`project_computes_score_vs_forward_return = 1`、`project_forward_return_is_price_ratio = 1` ⟹ **无选族错误** |
| **A2d** | V06 | `NOT_VIOLATED` | `NOT_VIOLATED` ✅ | 15 格网格全部实测（§3）⟹ 有限/非有限分界线**精确**落在 `var ≥ 1e-18` |
| **A3a** | V07 | **`VIOLATED`** | `VIOLATED` ✅ | 混合支撑集面板：`cross_section_days = 2`、`total_observations = 20`、`rank_ic_mean` 两日皆有，但**实际 Pearson 支撑天数 = 1** ⟹ `support_gap = 1`；且 `C11 = 0`（不存在任何 Pearson 支撑计数字段） |
| **A3b** | V08 | `REGISTERED_ONLY` | `REGISTERED_ONLY` ✅ | `C11` 冻结期望 **0**、实测 **0** ⟹ 支撑集**不可见**，登记 |
| **A3c** | V09 | **`NOT_REACHABLE`** | `REGISTERED_ONLY` ⚠️ | 库可连（`localhost:3306/quant_db`，MySQL 8.0.41）、表 `feature_snapshot_row` **存在**（`signal_date`/`final_score` 两列命中），但该表**当前 0 行** ⟹ `n_rows_total = 0` ⟹ 按冻结规则判 `NOT_REACHABLE` 并登记 `B-R009-03` |
| **A4a** | V10 | **`VIOLATED`** | `VIOLATED` ✅ | `P4z`（全**真 0**）与 `P4u`（全**不可定义**）经生产 `buildSnapshotScopeBlock` 的载荷**逐字符相同**（均 1060 字节）；两者 `pearsonIcMean` 均为 **`0.0`**；`cross_section_days = 1 > 0`、`total_observations = 10 > 0`；`P4u` 的 Pearson 支撑天数 **0**（无一日有限） |
| **A4b** | V11 | `NOT_REACHABLE` | `NOT_REACHABLE` ✅ | `round4(NaN) = 0.0`（占位机制**存在**）；但 `pearson_ic_mean` **无输入路径可达 NaN**（`:155` 的 `Double.isFinite(pic)` 过滤 + `:176-177` 的三元式）⟹ 机制存在但不构成可达违反 |
| **A4c** | V12 | `CONVENTION_DIVERGENCE` | `CONVENTION_DIVERGENCE` ✅ | 键集实测 18 / 19 / 18 / **14** / **15**；`unavailable` 用 `status="UNAVAILABLE"`，而 `emptyNetIcBlock` **无** `status`（靠缺键）；`empty_net` **无** `monotonicity` 而 `net` **有** ⟹ **同一「口径不可用」语义、两套惯例** |
| **A5a** | V13 | **`CONTRACT_MISLABEL`** | `CONTRACT_MISLABEL` ✅ | javadoc 逐字含「样本不足或方差为 0」（`javadoc_declares_zero_variance = 1`）、**量级/尺度/容差限定词命中 0**、且 A2a/A2b 均为 `VIOLATED` |
| **A5b** | V14 | **`VIOLATED`** | `VIOLATED` ✅ | legacy 空分支（`:660-672`）只发射 **10** 个键，**缺** `pearson_ic_mean`/`monotonicity`/`hac_std`/`ci_lower`/`ci_upper`（正常分支 19 键含之）；而该分支**可达**（`latestPerKey` 来自区间查询，区间内无 `selector_result` 即空）；前端 `factor.ts:110/201` 把 `pearson_ic_mean` 声明为**必需**（`: number`）⟹ 载荷与声明契约不一致 |
| **A6a** | V15 | `NOT_VIOLATED` | `NOT_VIOLATED` ✅ | 5 个发射点的支撑集定义有 **4** 种不同值，但**每处都有**结构化区分字段（`metric_scope`/`basis_note`；缺字段数 **0**）⟹ 不构成歧义 |
| **A6b** | V16 | **`VIOLATED`** | `VIOLATED` ✅ | 同一面板上 `isFinite(spearmanRankCorrelation(P1s)) = true`（`1.0`）而 `isFinite(pearsonCorrelation(P1s)) = false` ⟹ **R006 A4b 的 A 档等价被本轮新证据证伪**（处置见 §7） |
| **A7a** | V17 | `REGISTERED_ONLY` | `REGISTERED_ONLY` ✅ | 测试输入表达式 **20** 处，其中 `1e-` 量级的 `scores`/`returns` **0** 处（4 处 `1e-` 命中**全部**是断言容差 `offset(1e-9)`/`isCloseTo(1.0, 1e-6)`）⟹ 既有测试只覆盖尺度 1 的**一侧** |
| **A7b** | V18 | `REGISTERED_ONLY` | `REGISTERED_ONLY` ✅ | CSV 表头含 `pearson_ic`，取值表达式为**裸** `row.pearson_ic_mean`（**无** `??`/`||`/`isFinite` 分支；方法内另外 2 处 `??`/`||` 属于 `cost_net_ic` 列与守卫子句，不属该列）⟹ 导出面把占位 `0.0` 与真测量值写成同一列 |

**非杀死轴集合（prereg §4 冻结为 `{A2c, A2d, A3c, A7a}`）**：四轴判定词**已逐轴写入本表**，且**未**参与 K 表合成。
A7b 虽同属「跨面」，因其衡量**载荷消费面**（CSV 导出把占位值当真值输出），**已纳入** `K7`。

### 2.1 三条 K1/K2/K3 级断言的证据细节（本轮判定的承重部分）

**K1（A2a∨A2b）— 守卫破坏的是尺度不变性，不是「方差为 0」**

| 面板 | 输入 | `variance`（对照复算） | 生产 `ρ` | 秩相关 |
|---|---|---|---|---|
| `P1` | `scores=[1..10]`、`returns=0.01·[1..10]` | **82.5** | `1.0` | `1.0` |
| `P1t`（平移 `+1000`） | `scores=1001+[0..9]` | 82.5 | `1.0` | `1.0` |
| `P1s`（尺度 `×1e-10`） | `scores=1e-10·[1..10]` | **8.25e-19** | **`NaN`** | **`1.0`（有限）** |

- 平移不变、**尺度不变性被破坏**；同时 `var(P1s) = 8.25e-19 > 0` ⟹ **不是**「方差为 0」。
- 秩空间 `Σ(rank−mean)² = n(n²−1)/12 = 82.5`（n=10），比守卫高 **19 个数量级** ⟹ 同一个 `pearson` 被
  `spearmanRankCorrelation` 复用时**永不触发**守卫 —— 这就是 K1 的结构性来源。
- 冻结算术复算**逐位吻合**：`82.5e-20 = 8.25e-19`、`110e-20 = 1.1000000000000003e-18`、`143e-20 = 1.43e-18`。

**K2（A4a）— 「不可定义」与「真 0」产出逐字符相同的载荷**

- `P4z`（全**真 0**）：`scores=[1..10]`、`returns=[1,2,3,4,5,5,4,3,2,1]` ⟹ `Σdev_x·dev_y = 0` **精确** ⟹ `ρ = 0.0`。
- `P4u`（全**不可定义**）：`scores=1e-10·[1..10]`、**同一**收益向量 ⟹ `var = 8.25e-19` ⟹ **每一日** Pearson 皆 `NaN`
  ⟹ Pearson 支撑天数 **0**。
- 两者经生产 `buildSnapshotScopeBlock` 的**完整载荷**（递归规范序列化）**逐字符相同**（均 1060 字节），
  `pearson_ic_mean` 均为 **`0.0`** ⟹ 消费方**无法**从载荷区分「测到 0 相关」与「根本没测成」。
- 与 A3a 叠加：`daily_rank_ic` 的两天都在、`cross_section_days = 2`，但其中**一天的 Pearson 从未算出来**。

**K3（A3a）— 支撑集不可见**

- 混合面板：`d1 = P1`（Pearson 有限）、`d2 = P1s`（rank 有限 / Pearson 非有限）
  ⟹ `cross_section_days = 2`、`total_observations = 20`，而**实际 Pearson 支撑天数 = 1**、`support_gap = 1`。
- `C11 = 0`：**全仓不存在**任何键名匹配 `pearson_(sample|count|n|observations|support|days|size)` 的字段
  ⟹ 读者**无法**从载荷得知「ρ 只在 1/2 天上可定义」。

---

## §3 A2d 敏感性网格（协议强制的 ±20%，15 格，**描述性**）

`c ∈ {8e-11, 1e-10, 1.2e-10}`（`1e-10 ± 20%`）× `n ∈ {8,9,10,11,12}`；每格 `scores = c·[1..n]`、`returns = [1..n]`。

| `c` | `n` | `variance` | `variance / 1e-18` | 生产 `ρ` |
|---|---|---|---|---|
| 8e-11 | 8 | 2.6879999999999997e-19 | 0.269 | `NaN` |
| 8e-11 | 9 | 3.84e-19 | 0.384 | `NaN` |
| 8e-11 | 10 | 5.279999999999999e-19 | 0.528 | `NaN` |
| 8e-11 | 11 | 7.039999999999999e-19 | 0.704 | `NaN` |
| 8e-11 | 12 | 9.151999999999998e-19 | 0.915 | `NaN` |
| 1e-10 | 8 | 4.200000000000001e-19 | 0.420 | `NaN` |
| 1e-10 | 9 | 6.000000000000001e-19 | 0.600 | `NaN` |
| 1e-10 | 10 | 8.25e-19 | 0.825 | `NaN` |
| 1e-10 | 11 | 1.1000000000000003e-18 | **1.100** | 0.9999999999999999 |
| 1e-10 | 12 | 1.43e-18 | **1.430** | 1.0000000000000002 |
| 1.2e-10 | 8 | 6.0480000000000005e-19 | 0.605 | `NaN` |
| 1.2e-10 | 9 | 8.64e-19 | 0.864 | `NaN` |
| 1.2e-10 | 10 | 1.1880000000000001e-18 | **1.188** | 0.9999999999999998 |
| 1.2e-10 | 11 | 1.584e-18 | 1.584 | 1.0 |
| 1.2e-10 | 12 | 2.0591999999999998e-18 | 2.059 | 1.0 |

**分界线（实测）**：`ρ` 有限 **⟺** `variance ≥ 1e-18`，15 格**无一例外**；`variance/1e-18 < 1` 的 **8 格全部 NaN**、
`≥ 1` 的 **7 格全部有限**。±20% 的尺度扰动**不改变**缺陷的存在性，只**移动**它在 `n` 轴上的位置
（`c` 越小，需要越大的 `n` 才跨过守卫）⟹ 参数扰动**不能**救活该断言。

> 本轴**只作披露**，无独立断言，**不得**被用来支持或削弱 A2a/A2b 的判定（prereg §4 A2d 冻结条款）。

---

## §4 总判定与 K 触发

| K | 条件（prereg §6.1 冻结） | 布尔值 | 触发的轴 |
|---|---|---|---|
| `K1` | A2a∨A2b `VIOLATED` | **`true`** | A2a, A2b |
| `K2` | A4a `VIOLATED` | **`true`** | A4a |
| `K3` | A3a `VIOLATED` | **`true`** | A3a |
| `K4` | A5a `CONTRACT_MISLABEL` | **`true`** | A5a |
| `K5` | A5b `VIOLATED` | **`true`** | A5b |
| `K6` | A6a `CONTRACT_AMBIGUOUS` | `false` | — |
| `K7` | A3b∨A4b∨A4c∨A6b∨A7b 任一 ≠ `NOT_VIOLATED` | **`true`** | A3b `REGISTERED_ONLY`、A4b `NOT_REACHABLE`、A4c `CONVENTION_DIVERGENCE`、A6b `VIOLATED`、A7b `REGISTERED_ONLY` |
| `K8` | 行和 / 变体表 / config 冻结不一致 | `false` | — |
| `K9` | 阳性对照或探针自检失败，或表外判定词 | `false` | — |
| `K10` | 确定性门失败 | `false` | — |

按 prereg §6.2 的**按序短路**合成（`K8∨K9∨K10` → `INCONCLUSIVE`；`K1∨K2∨K3` → `REJECTED`；…）：

**`total_verdict = REJECTED`**、**`h0_alive = false`**（`¬K1∧¬K2∧¬K3` 被否）。

**强制披露（prereg §6.1 冻结措辞）**：

1. `K7` 在本轮的**期望触发概率极高**（只要 A3b/A7b 这类「登记型」轴按设计给出 `REGISTERED_ONLY` 即触发）
   ⟹ `REGISTERED_ONLY` 在本轮是**兜底地板值**，**不得**被叙述成「又发现一条缺陷」。
2. `total = REJECTED` 的**承重部分只有 `K1/K2/K3`**；`K4/K5` 虽亦为真，但按短路规则**未**改变总判定
   （它们的作用是把缺陷**定性**为「契约文本错标」与「载荷与声明契约不一致」，见 §2）。
3. `K7` 内部的 `A4b` 是 `NOT_REACHABLE`（机制存在、路径不可达），**不**等于违反。

### 4.1 预注册预测的检验（18 条，17 中 1 偏，**不得**包装成能力）

- **17/18 命中**，唯一偏差：

| 轴 | 预测 | 实测 | 偏差性质 |
|---|---|---|---|
| **A3c** | `REGISTERED_ONLY` | `NOT_REACHABLE` | **数据可用性偏差**：本机 `feature_snapshot_row` 表存在但 **0 行** ⟹ 冻结规则明确要求判 `NOT_REACHABLE`。**不**是判据偏差，也**不**削弱任何断言 |

- 预测**全部**写在 prereg 里、**先于**实现与运行；偏差按协议记入**附录 A**（A-08），**未**回写冻结文本。

---

## §5 量级与可达性的诚实标注（防夸大）

### 5.1 可达性表

| 断言 | 契约违反 | 生产可达性 | 依据 |
|---|---|---|---|
| **A2a/A2b**（守卫破坏尺度不变性） | **已证**（实测量） | **不可达** | 触发需分值离散度 < `3.2e-10`（`n=10`，即约 12 位有效数字的**相对**精度）；生产 `rule_score` 是 0–100 量级的截面百分位 ⟹ 实际 `variance` 比守卫高 **十多个数量级** |
| **A2b**（`n` 依赖的翻转） | **已证** | **不可达** | 同上；翻转点由 `variance` 跨 `1e-18` 决定，需同一相对离散度落在 `1e-10` 附近 |
| **A3a/A3b**（支撑集不可见） | **已证** | **可达** | 只要**任一日**的 Pearson 不可定义（`n < 2`、`var < 1e-18`、或分值全等）即产生 `support_gap ≥ 1`；`C11 = 0` 使读者无从得知 |
| **A4a**（不可定义 ⟹ 输出 `0.0`） | **已证** | **可达** | 只要**全部**截面日的 Pearson 皆不可定义即产出「像测量值的 0.0」；这正是 `pearsonIcMean = isEmpty() ? 0 : average(...)` 的语义 |
| **A4c**（两套不可用惯例） | **已证** | **可达** | 反射实测的**真实键集**，非读码推断 |
| **A5a**（javadoc 错标） | **已证** | **可达** | 文本断言，无运行时前提 |
| **A5b**（legacy 空分支缺必需键） | **已证** | **可达** | `latestPerKey.isEmpty()` 由区间查询决定，区间内无 `selector_result` 即空 |
| **A6b**（与 R006 A4b 等价被证伪） | **已证** | **不可达**（同 A2a） | 与 A2a 同源：同一 `c=1e-10` 构造 |

> **强制措辞（prereg §4 A3c 冻结条款）**：本表「生产可达性 = 不可达」**只**说明
> 「在**当前生产分值尺度**下暴露面为 0」；缺陷的**可证伪性**由 A2a/A2b 的**精确算术**建立（R007 教训第 44 条），
> **不得**据此削弱断言，也**不得**反向推出「缺陷不存在」。

### 5.2 描述性 vs 预测性（不得混同）

- 本轮**全部**数字都是**描述性**的：它们描述**载荷与它自己声明的定义**是否一致。
- 本轮**没有**测量「Pearson IC 在 A 股是否有效」；`rule_score` 是 **0–100 截面百分位，不是上涨概率**。
- A3c 的输出（本轮为空）**不得**被表述为「生产数据上的 Pearson IC 表现」——它**只**是**分值离散度**的普查。

---

## §6 前视 / 幸存者偏差影响（协议 §5 + prereg §5.2 强制披露）

| 变体 | 是否触真实库 / 真实市场数据 | 前视偏差 | 幸存者偏差 |
|---|---|---|---|
| V01–V08, V10–V18（17 个） | **否**（纯构造面板） | **不适用**：输入是构造的数值向量，无时间轴、无入场/出场 | **不适用**：无标的集合 |
| **V09（A3c）** | **是**（单条只读聚合 SQL） | **不适用**：`sxx` 是**同一信号日内分值的离散度**，不涉及未来收益 | **不适用**：全表 `GROUP BY`，**无 `LIMIT`**，无标的筛选 |
| （唯一间接涉及未来收益的**读码**断言） | 否 | A2c 与 A6a 陈述生产**代码如何定义**未来收益（`close[t+h]/close[t] − 1`，`t` = signal_date）⟹ 与冻结口径的**入场=signal_date 下一交易日开盘 / 退出=h-th 持有日收盘**存在**项目内部两套口径**，但本轮**未**对此下判定（不在 18 轴内） | 不适用 |

> A3c 的 `days_below_guard = 0`（本轮实际为**空表**）**不得**叙述为「该缺陷在生产上不存在」——见 §5.1 强制措辞。

### 6.1 区间下界类判据：本轮**不适用**的理由（prereg §5.4 复述）

本轮**零抽样**，故未使用 Clopper–Pearson 区间下界；逐轴等价变形条款：

| 轴 | 为何不适用 | 等价变形 |
|---|---|---|
| A2a/A2b/A2d | **构造面板 + 精确算术**（`Σ(i−x̄)²` 逐位复算），非抽样 | 断言形式为「**逐位**满足冻结算术」 |
| A3a | 构造面板，**穷举**两日 | 「每一天都分别复算」，无抽样 |
| A3c | **全表普查**（无 `LIMIT`） | 普查本身即总体，**不构成抽样**；若因数据缺失被迫抽样，**必须**改判 `NOT_REACHABLE`，**不得**以点估计冒充普查 ⟹ 本轮触发的正是这一条款 |
| A4a | 两份载荷**逐字符**比对，非抽样 | 「完全一致 / 不完全一致」二值 |
| A5a/A5b/A7a/A7b | **文本枚举**（键集、字段声明、表达式） | 「存在 / 不存在」二值 |
| A1a/A1b/A4b/A4c/A6a/A6b | 确定性复算 + 反射键集，非抽样 | 「两次相同」/「键集相同」二值 |

---

## §7 与既有轮次的关系（防回溯改写）

- **A6b 牵涉 R006 A4b**：R006 A4b 把「`ic` 有限 **⟺** `pearson` 有限」记为 **A 档等价**。
  本轮在**同一面板**上实测到 `isFinite(spearmanRankCorrelation(P1s)) = true` 而
  `isFinite(pearsonCorrelation(P1s)) = false` ⟹ **该 A 档等价被新证据证伪**。
- **处置（prereg §4 A6b 冻结条款，已遵守）**：
  1. 报告**已**写明「R006 A4b 的 A 档等价被新证据证伪」（本段）；
  2. 已登记 `decision_queue.md` 的 **`D-R009-03`**（含 **R006 报告勘误建议**）；
  3. **未**回写、改写或重新解释 `rounds/R006_prereg.md` / `rounds/R006_report.md` 的任何冻结文本；
  4. **未**据此叙述「R006 整轮失败」或「R006 结论不可用」。
- **与 R007/R008 的关系**：R007 审计换手率 `analyze` 的分数分支、R008 审计 `BacktestTurnoverService` 的换手率载荷族，
  与本轮的 `FactorResearchEngine` / `FactorResearchService` **无重叠代码**；三者的 `REGISTERED_ONLY`/`NOT_REACHABLE`
  处置口径**一致**（把「本机缺数据 / 无输入路径」类轴排除出 K 表）。
- 本轮**未**重复审计 `monotonicity`（R006 A7a 已判 `REGISTERED_ONLY`），prereg §1.1 已明确排除。

---

## §8 本轮不得被叙述成什么（负面清单）

1. **不得**说「Pearson IC 在 A 股无效/有效」——本轮**没有**测量任何策略表现。
2. **不得**说「生产环境正在输出错误的 Pearson IC」——A2a/A2b 的生产可达性为**不可达**（§5.1）；
   可达的是 A3a/A3b/A4a/A4c/A5a/A5b 六轴。
3. **不得**把 `REGISTERED_ONLY`（A3b/A7b）与 `NOT_REACHABLE`（A3c/A4b）算作「新发现的缺陷条数」——见 §4 披露第 1 条。
4. **不得**说「`round4(NaN) = 0.0` 导致生产载荷出现 0.0 占位」——A4b 判定为**路径不可达**；
   生产上的 `0.0` 占位来自 `pearsonIcMean` 的三元式（A4a），**不是** `round4`。
5. **不得**说「javadoc 撒谎」——判定词是 `CONTRACT_MISLABEL`（**文本用词与实现作用域不符**），
   实现本身在「方差为 0」这一**子集**上是正确的；错在把 `Σ(x−x̄)² < 1e-18` 描述成「方差为 0」。
6. **不得**把本轮的 A 档算术结论包装成「文献要求」（§1.1）。
7. **不得**由 A5b 推出「前端页面会崩」——契约不一致的后果是**类型层面的未定义读取**，本轮**未**做运行时验证。
8. **不得**说「R006 整轮失败」（§7）。
9. **不得**把 A2d 的 15 格数据当作 A2a/A2b 的**加强证据**——它是**披露轴**，无独立断言。
10. **不得**把 `C1–C20` 的 20/20 匹配当作「结论正确」的证据——计数与判定**无关**（prereg §7）。

---

## §9 台账与队列动作（**只登记，不修**）

### 9.1 `decision_queue.md` 新增条目（全部 `PENDING_USER`，均属协议 §7 record-only 档）

| id | 主题 | 为何属 record-only |
|---|---|---|
| `D-R009-01` | `pearsonCorrelation` 的 NaN 守卫与 javadoc 声明不一致（A2a/A2b/A5a） | 改守卫**即改打分/因子口径**（`pearson_ic_mean` 是研究面载荷）⟹ §7 档 |
| `D-R009-02` | `pearson_ic_mean` 把「不可定义」输出为 `0.0`，与真 0 不可区分（A4a） | 改占位语义**即改公开载荷契约**（前端 `factor.ts` 声明为 `number`）⟹ §7 档 |
| `D-R009-03` | **R006 A4b 的 A 档等价被证伪** + R006 报告勘误建议（A6b） | 改 R006 冻结文本**禁止**；仅登记勘误建议 |
| `D-R009-04` | Pearson 支撑集不可见（A3a/A3b） | 新增字段**即改公开载荷契约** ⟹ §7 档 |
| `D-R009-05` | legacy 空分支缺 `pearson_ic_mean` 等 5 键，与前端必需声明冲突（A5b） | 改发射键集**即改公开 API 契约** ⟹ §7 档 |
| `D-R009-06` | 两套「口径不可用」惯例并存（A4c） | 统一键集**即改公开载荷契约** ⟹ §7 档 |
| `D-R009-07` | CSV 导出把占位 `0.0` 与真测量值写成同一列（A7b） | 改导出面**即改前端行为** ⟹ §7 档 |
| `D-R009-08` | 既有测试缺**尺度维度**覆盖（A7a） | 加测试**本身安全**，但按 §7「测试代码修改」虽在允许档，**仍**先登记等用户排序 |

> 每条均含：事实（实测量）/ 触发与范围 / 可达性 / 保守处理 / **建议动作（需用户确认）**。

### 9.2 `blocked_queue.md` 新增条目

| id | 阻塞内容 |
|---|---|
| `B-R009-01` | 海外源检索受限：arXiv 导出接口本轮出现 **429**，Semantic Scholar 亦受限（代理 `127.0.0.1:7897` 已用）；本轮论文的**版本/DOI**核对只能依赖本地已下载载体 |
| `B-R009-02` | R006 A4b 勘误的**生效**需要用户确认（agent **不得**自行改写 R006 冻结文本） |
| `B-R009-03` | A3c 的 `NOT_REACHABLE`：本机 `feature_snapshot_row` 表**存在但 0 行** ⟹ 生产语义下的分值离散度普查**无数据可做**；解除需要造数或换库，**两者**都超出只读红线 |

### 9.3 `shadow_registry.md`

本轮**无**新因子、无影子登记（审计轮不产生因子）。仅登记一行「R009 空轮」。

---

## §10 计数断言 C1–C20（**只读扫描**，与判定无关）

命令口径见 prereg §7（斜杠归一化；`src` 排除 `target/dist/node_modules/__pycache__/build/out/.git`；
`raw` 不排除构建产物）。**20/20 与冻结期望一致**（含 `by_file` 逐文件核对）。

| id | 期望(src) | 实测(src) | 期望(raw) | 实测(raw) | 关键 by_file（期望→实测） |
|---|---|---|---|---|---|
| C1 | 11 | **11** | 17 | **17** | FactorResearchService 5→5 / factor.ts 3→3 / FactorResearch.vue 3→3 |
| C2 | 5 | **5** | — | — | FactorResearchService 5→5（= 5 个发射点） |
| C3 | 6 | **6** | — | — | factor.ts 3→3 / FactorResearch.vue 3→3 |
| C4 | 41 | **41** | 53 | **53** | engine 13→13 / Service 10→10 / Test 7→7 / Vue 6→6 / ts 4→4 / Controller 1→1 |
| C5 | 7 | **7** | 7 | **7** | Test 5→5 / engine 2→2 |
| C6 | 5 | **5** | — | — | Test 5→5 |
| C7 | 4 | **4** | — | — | engine 4→4（**从不离开引擎**） |
| C8 | 15 | **15** | — | — | engine 5 / Service 3 / ts 3 / ScoringBacktestResponse 2 / ScoringBacktestService 2 |
| C9 | 6 | **6** | — | — | engine 4→4 / Test 2→2 |
| C10 | 19 | **19** | 27 | **27** | Service 7 / Vue 4 / engine 3 / Test 3 / ts 2 |
| **C11** | **0** | **0** | — | — | — （**不存在任何 Pearson 支撑计数字段**） |
| C12 | 0 | **0** | 0 | **0** | —（数据库层无任何 Pearson 相关列/注释） |
| C13 | 2 | **2** | — | — | engine 2→2（`:314`） |
| C14 | 0 | **0** | — | — | —（全仓 `*.sql` 亦无） |
| C15 | 2 | **2** | — | — | engine 2→2（`:50`、`:67`） |
| C16 | 1 | **1** | — | — | FactorResearch.vue 1→1（CSV 表头 `pearson_ic`） |
| C17 | 12 | **12** | — | — | ScoringBacktestService 3 / CrossSectionNeutralizer 3 / engine 2 / CodexShortCycleScoringEngine 2 / AdjustFactorDerivationService 1 / T1CandidateService 1 |
| C18 | 23 | **23** | — | — | 10 个文件逐一致（ProbabilityCalibrationService 5 / TechnicalIndicators 4 / engine 3 / …） |
| C19 | 22 | **22** | — | — | FactorResearchService 8 / engine 8 / Test 6 |
| C20 | 15 | **15** | — | — | StrategyBacktestEngine 9 / engine 6 |

**口径差异披露（prereg §7 冻结）**：C4 大小写不敏感（含注释/javadoc 中的 `Pearson`），C19 **仅小写**；
两者**不可**相减推断「注释中的 Pearson 数」。报告**并列原始数字**：`C4 = 41`、`C19 = 22`（差额不可解读）。

---

## §11 测试验证与 hygiene

### 11.1 测试套件（零生产 / 零测试改动 ⟹ 与基线一致）

| 套件 | 命令 | 结果 |
|---|---|---|
| Java | `mvn -o -B test -pl modules/backend-java` | **Tests run: 350, Failures: 0, Errors: 0, Skipped: 0** ｜ BUILD SUCCESS |
| Python | `cd modules/compute-python && python -m pytest tests paper/tests -q` | **343 passed**, 3 warnings |

两套数字与基线（R33/Ja 350 / Py 343）**逐项一致**；本轮**未**改动任何生产、测试、前端、契约或数据库代码。

### 11.2 hygiene

- `probe/out/*`（`javac` 产物）由编排器在运行结束**自动删除**；**未**入库
  （提交前实测 `probe/` 目录内**只有** `R009Probe.java`）。
- **实际出现了** `experiments/R009_pearson_ic_audit/__pycache__/run_pearson_audit.cpython-313.pyc`
  （以脚本方式重复运行编排器时生成）⟹ **已删除**，且 `.gitignore:45/47` 已覆盖 `**/__pycache__/` 与 `*.pyc`
  ⟹ **未**入库。此处如实记录「曾出现」，不写成「未产生」。
- **临时文件**：编排/台账写入过程中在 `%TEMP%` 下用过 4 个 `r009_*.md` 中转文件（**不在仓库内**），**已全部删除**。
- `git diff --check` 结果见提交前检查（回填区）。

### 11.3 提交信息（回填区）

| 项 | 值 |
|---|---|
| 冻结提交（review + prereg，**先于**任何实验代码） | `923c2763` |
| 本报告 commit | **`2b56ab08`** |
| push 结果 | **成功**：`f139fbfb..2b56ab08  master -> master`（普通 push，**无** force；**无** `PUSH_PENDING`） |
| 本回填 commit 自身 id | **不自指**（递归边界，同 R007 的 `7f493011` / R008 的 `9e427bfb` 做法） |

---

## §12 运行次数与自证量

`run_pearson_audit.py` 共运行 **5 次**（`run.log` **append-only**，逐次可查）：

| # | 时刻 | 终止状态 | 原因（**仪器缺陷，非载荷结论**） |
|---|---|---|---|
| 1 | 22:33:51 | **中止**（`FATAL: 探针未产出 ⟹ 整轮 INCONCLUSIVE`） | 钉死依赖 `DEPS_PINNED` 中 `spring-data-commons` 的 m2 路径漏了 `data/` 段 ⟹ 依赖解析失败。**按设计**显式失败，未静默降级版本（附录 A-02） |
| 2 | 22:40:03 | 完成，但**金丝雀 17/18** | `A3c` 的 CLN 只写了 `reachable`，而检查器**还**读 `days_with_data` ⟹ R007 教训第 38 条的又一实例。**期望值未改**，改的是 CLN 的 op（附录 A-05）；同时发现 `application.yml` 口令是 Spring 占位 `${DB_PASSWORD:123456}` 未解析（附录 A-03） |
| 3 | 22:42:35 | 完成，**金丝雀 18/18** | 仍有 2 处**仪器口径**缺陷：A2c 的逐字引用匹配失败（PDF 断词 `mea-\nsure` 未归一，附录 A-06）；A7a/A7b 的正则过宽（把断言容差 `offset(1e-9)`、无关的 `||` 守卫与 `cost_net_ic` 的 `??` 当成命中，附录 A-07） |
| 4 | 22:45:40 | 完成，**18/18** | A2c 与 `cost_net_ic` 块范围修正生效（附录 A-06/A-04）；A7a/A7b 仍为过宽正则 |
| 5 | **22:50:10** | **完整（本轮定稿）** | 全部仪器缺陷修正完毕；**20/20 计数**、**18/18 金丝雀**、**44/44 面板谓词**、G3/反向锚点/确定性门全通过 |

**自证量**：18 个检查器 × (CLN + INJ) = **36** 次金丝雀运行；`panel_design_selfcheck` **44** 条谓词；
C1–C20 共 **20** 条断言、**44** 个逐文件子断言；探针一次性打印 **226** 行结构化 JSON。

> **披露**：第 3/4 次运行曾产出过 `A2c = VIOLATED`、`A7a = NOT_VIOLATED`、`A7b = NOT_VIOLATED`
> 三个**错误**判定词。它们**全部**源于仪器缺陷，**未**被写入任何台账，**未**进入定稿报告，
> **也不得**被引用为结论。定稿数字以第 5 次运行为准，勘误逐条记于附录 A。

---

## §13 本轮不该做的事（已遵守清单）

| 禁止项 | 状态 |
|---|---|
| 只操作 `E:\Project\quant-trading-system` | ✅ 仅此一仓 |
| 不操作 `E:\Project` 下其他项目 | ✅ |
| 删除用户已有修改 | ✅ 无 |
| `git reset --hard` / `git checkout --` / `git push --force` | ✅ 均未使用 |
| 创建 PR / 发布版本 | ✅ 未做 |
| 启用真实自动交易 / 向真实账户下单 / 生成真实凭证 | ✅ 未做（本轮**只读**） |
| 改打分权重 / 因子口径 / 质量闸阈值 | ✅ 未改 |
| 改交易/风控/仓位逻辑 | ✅ 未改 |
| 改 `ResearchConvention` 或任何冻结口径 | ✅ 未改 |
| 改公开 API / 前端契约 / 数据库结构 | ✅ 未改（A5b/A4c/A3b 的发现**只登记**） |
| 影子 → 正式提升 | ✅ 未做（本轮无因子） |
| 引入新数据源或付费数据 | ✅ 未做 |
| 删除历史数据 / 不可逆迁移 | ✅ 未做 |
| 预注册先于实验代码 | ✅ `R009_prereg.md` 冻结于 `923c2763`，**先于** `probe/`、`make_config.py`、`run_pearson_audit.py` |
| 杀死条件事后放宽 | ✅ 未放宽（A3c 的 `NOT_REACHABLE` 正是**冻结规则本身**要求的判定，**不是**事后放宽） |
| 事后更正只在报告附录 | ✅ 见附录 A（**未**回写 prereg） |

---

## 附录 A 勘误（**只在此处更正，绝不回写冻结的 `R009_prereg.md`**）

| # | 类别 | 内容 | 处置 |
|---|---|---|---|
| **A-01** | 探针实现偏差 | `v10()` 初版把 `sz.dailyRankIc().size()`（**秩**支撑天数）发射成了 `pearson_days_p4z/p4u`，键名与语义不符 | 新增 `pearsonSupportDays(map)`（**仍调用生产静态方法**，不重实现），改名并**同时**发射 `rank_days_*` 与 `pearson_support_days_*` 两组；A4a 的判定改用后者 |
| **A-02** | harness 缺陷 | `DEPS_PINNED` 中 `spring-data-commons` 的 m2 相对路径漏 `data/` 段 ⟹ 依赖解析失败、第 1 次运行中止 | 补正路径；**保持**「缺任一 jar 即显式失败」的行为不变 |
| **A-03** | 仪器缺陷 | A3c 取 `application.yml` 的 `password:` 时**未**解析 Spring 占位 `${DB_PASSWORD:123456}`，导致 `Access denied`（第 2 次运行误判为库不可达） | 新增 `resolve_spring_placeholder()`（环境变量优先、其次默认值）；库实际**可连** |
| **A-04** | 预注册行号偏差 | prereg §4 A5b 写 `cost_net_ic?` 在 `:206-215`；实测该块为 **`:208-217`**（`cost_net_ic?: {` 起、闭合 `}` 止） | 新增**实测块范围** `ts_costnet_block` 做该块字段检查；冻结的 `190-215` 仍用于「外层 `pearson_ic_mean` 是否必需」。**不改**冻结文本 |
| **A-05** | 金丝雀构造缺陷 | A3c 的 CLN 只写 `reachable`，而检查器**还**读 `days_with_data` ⟹ CLN 未落到「检查器实际读的那个键」（第 2 次运行 17/18） | 改 CLN op 为 `(reachable=1, days_with_data=1)`；**冻结的 clean/inj 期望值未改**（R007 教训第 38 条） |
| **A-06** | 文本匹配缺陷 | A2c 的逐字引用匹配在**未归一化 PDF 断词**时假阴性：原文转写为 `...to mea-\nsure...`（第 3/4 次运行曾错误产出 `A2c = VIOLATED`） | 归一化 `^> `、合并 `-\s*\n`、压缩空白后再匹配；同时保留 `review_criteria_jia_raw_present = 0` 作为**假阴性已被证实的证据** |
| **A-07** | 文本匹配缺陷 | A7a 的正则 `1e-\d` 过宽，把**断言容差**（`offset(1e-9)`、`isCloseTo(1.0, 1e-6)`，共 4 处）当成「尺度维度测试输入」；A7b 的正则把**无关**的 `||` 守卫子句与 `cost_net_ic` 列的 `??` 当成 `pearson_ic` 列的缺测分支（第 3/4 次运行曾错误产出 `A7a/A7b = NOT_VIOLATED`） | A7a 改为只在 `List.of(...)`/`new CrossSectionObservation(...)` 的**输入表达式**内匹配并单列容差命中数；A7b 改为只在含 `pearson_ic_mean` 的**取值表达式**内匹配。修正后两轴均回到冻结预期的 `REGISTERED_ONLY` |
| **A-08** | 预测偏差 | A3c 预测 `REGISTERED_ONLY`、实测 `NOT_REACHABLE`（本机该表 **0 行**） | 按冻结规则如实判 `NOT_REACHABLE`；登记 `B-R009-03`。**不**放宽、**不**改判 |
| **A-09** | 性能改动（语义中性） | `count_pattern` 引入 `(root, exts, include_build)` 维度的遍历+读取缓存 ⟹ 单次运行由 > 6 分钟降至 ~2 分钟 | 缓存**不改变任何计数语义**（同一批文件、同一正则）；20/20 期望在第 2–5 次运行中**均**匹配，可交叉验证 |

> 以上**全部**为**仪器/实现**层面的更正；**没有**任何一条改动 prereg 的断言文本、阈值、K 条件或短路规则。
