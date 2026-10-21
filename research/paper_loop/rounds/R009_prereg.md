# R009 预注册（审计轮）：Pearson 侧 IC 载荷族 `pearson_ic_mean` / `pearsonCorrelation` / `dailyPearson`

> **冻结时间：2026-09-14 22:35 (+0800)，冻结于基线 commit `f139fbfb`。**
> **本文件先于本轮任何实验代码（`experiments/R009_pearson_ic_audit/**`）创建并冻结。**
> 判据来源：Shin, Jeong, Kim, Hong, Cho, Choi，arXiv:2401.02710v2（v1 2024-01-05 / v2 2024-07-08，
> `Comments: Accepted by ICOIN 2024`），审查见 `rounds/R009_review.md`。
> 报告：`rounds/R009_report.md`（写于测量之后）。
>
> **冻结条款**：本文件一经写下，**任何阈值、判定词、变体数、杀死条件不得事后放宽**。
> 事后发现的更正**只能**写入 `rounds/R009_report.md` 的**附录 A（勘误）**，**不得**回写本文件。
>
> **本轮性质**：与 R004–R008 同型的**审计轮**。判据来源（论文）在本轮**只**充当
> `rounds/R009_review.md` §2 所摘录的**判据来源**，**不**充当「论文说要怎么做」的处方；
> 被审计对象是**项目自己的契约主张**（`research/paper_loop/研究口径规范.md` §7 record-only 意义上的只读对象）。

---

## 1. 被审计对象与 H0

### 1.1 被审计对象（载荷族，逐键列全）

生产入口（两条）：

* `modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java`（388 行）
  * `public static double pearsonCorrelation(List<Double>, List<Double>)`（`:52-64`）
  * `static double spearmanRankCorrelation(double[], double[])`（`:83-90`，**复用** `pearson`，见 F4）
  * `private static double pearson(double[], double[])`（`:294-318`，**真实守卫所在**）
  * `public static FactorResearchSummary summarize(...)`（`:120-212`，Pearson 支撑集的**唯一**构建处）
* `modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java`（1033 行）
  * `private Map<String,Object> buildSnapshotScopeBlock(...)`（`:526-554`，载荷 `pearson_ic_mean` `:542`）
  * `private Map<String,Object> unavailableScopeBlock(...)`（`:557-578`，`:568`）
  * `:761`（legacy 正常分支）
  * `private Map<String,Object> emptyNetIcBlock()`（`:800-818`，`:810`）
  * `private Map<String,Object> buildNetIcBlock(FactorResearchSummary)`（`:820-839`，`:830`）
  * legacy 空分支（`:660-672`，**不发射** `pearson_ic_mean`，见 F12）

| 键 | 位置 | 类型/单位 | 消费方 |
|---|---|---|---|
| `pearson_ic_mean` | `:542` / `:568` / `:761` / `:810` / `:830` | 实数（`round4` 后），**理论值域 `[-1,1]`** | `factor.ts:109`/`:201`（**声明为必需**）、`:206-215`（`cost_net_ic?`）、`FactorResearch.vue:499-517` CSV 列 `pearson_ic` |
| `cross_section_days` | `:536` 等 | 计数（**秩 IC 的**支撑天数，见 F5） | 前端列 |
| `total_observations` | `:537` 等 | 计数（**秩 IC 支撑日**的样本总数，见 F6） | 前端列 |
| `daily_rank_ic[]` | `:548` / `:580-590` | 每元素 3 键：`trade_date` / `rank_ic` / `sample_size`（**无 Pearson 日度值**，见 F10） | 前端表 |
| `monotonicity` | `:543` 等 | 字符串（R006 A7a 已判定，**本轮不重复审计**） | 前端 |

**声明的口径（生产文件 `:49-51` 的 javadoc，逐字）**：

> 「Pearson 相关系数（线性 IC）；**样本不足或方差为 0 时返回 NaN**。」

（同句在 `:66-68` 的 Spearman javadoc 重复一次，故 C15 实测 2 命中。）

### 1.2 H0（本文件冻结，不得事后改写）

> **H0**：`pearson_ic_mean` 载荷族忠实对应其声明定义，**且**在该定义**不适用**时
> **不输出看起来像测量值的实数**，**并可见地报告支撑集**。具体为三条：
>
> * **H0-a（定义忠实）**：`pearsonCorrelation` 返回 NaN **当且仅当** javadoc 声明的两个条件
>   （样本不足 / 方差为 0）之一成立；等价地，在**方差 > 0** 的输入上不返回 NaN。
> * **H0-b（不冒充测量值）**：当 Pearson IC **不可定义**时，载荷**不得**输出一个与
>   「恰为 0 的 Pearson IC」**不可区分**的实数。
> * **H0-c（支撑集可见）**：载荷**必须**让读者能判断 `pearson_ic_mean` 是在**几天**上算出来的，
>   且该天数与 `cross_section_days` 的关系可被读者察觉。

**H0 的存活条件（冻结）**：`K1 与 K2 与 K3 全部未触发`（见 §6）。
**H0 存活 ≠ 总判定为 `NOT_VIOLATED`**（总判定见 §6 的按序短路规则）。
**H0 被杀 ≠ 项目不可用**（`研究口径规范.md` 的强制措辞规则）。

**本轮明确不审计的内容**（避免与既有轮次重复）：

* `monotonicity` 及其退化分支（`assessMonotonicity` 全等 ⟹ `MONOTONIC_INCREASING`）——
  **已由 R006 A7a 判定为 `REGISTERED_ONLY`（退化）**，本轮**不**再次判定，仅在 §5.1 F8 登记其存在；
* Spearman 秩 IC 本身（`rank_ic_mean` 的定义与支撑集）——属另一族载荷，本轮**不**判定；
* `long_short_spread` / `hac_std` / `ci_lower` / `ci_upper` 的统计口径——属另一族载荷。

---

## 2. 判定词表（冻结 8 词，逐词定义，运行期不得增删改）

| 判定词 | 冻结含义 | 与 R004–R008 的一致性 |
|---|---|---|
| `NOT_VIOLATED` | 该轴在冻结输入上**通过**（实测与声明定义/契约一致，或无问题证据） | 同 |
| `VIOLATED` | 同一输入下，实测与**声明定义或声明契约**不一致，且不一致**可复现** | 同 |
| `CONVENTION_DIVERGENCE` | 与判据来源或**项目内另一处**惯例不一致，但两处惯例各自都非「错」，不单方面判死 | 同 |
| `CONTRACT_AMBIGUOUS` | 契约允许两种以上读法，或**同名**键指代不同量，读者无法单凭载荷消歧 | 同 |
| `CONTRACT_MISLABEL` | 契约**文本对自身行为的描述**与代码行为不符（含「声明了一个未被强制的条件集」） | 同 |
| `REGISTERED_ONLY` | 只登记（不构成违反）：支撑集不可复算、占位标记缺失、导出面无缺测分支等 | 同 |
| `NOT_REACHABLE` | 该轴在**本机可用条件下**不可达或不可测（无 DB / 表空 / 无输入路径可达） | R008 定义域扩展：新增「无输入路径可达」分支 |
| `INCONCLUSIVE` | 该轴因 G3 门 / 金丝雀 / 确定性门未通过而不可判 | 同 |

**判定词的合法集合在本轮即上表 8 词**；出现表外词 ⟹ K9 触发（§6）。

---

## 3. 测量机制（冻结）

### 3.1 只读红线

* **不改**任何生产代码 / 测试 / 前端 / 契约 / `ResearchConvention` / 数据库结构。
* 发现一律**只登记** `decision_queue.md` / `blocked_queue.md`，**不修**。
* 本实验目录下只写 `run.log`（append-only）、`result.json`、`config.json`、`probe/out/*`（运行后删除）。
* **唯一的数据库访问**是 A3c 的**单条只读聚合 SELECT**（`COUNT/SUM/GROUP BY`，无 `LIMIT`、无写入、
  无 `INSERT/UPDATE/DELETE/DDL`）。其余 17 个变体 **0 条 SQL**。

### 3.2 断言对象必须是仓库自己的生产类（门 G3）

探针 `probe/R009Probe.java` 编译进 **`com.quant.service.backtest`** 包（与 R005–R008 相同做法），
以 `-cp <repo>/modules/backend-java/target/classes` 运行，并打印

```
FactorResearchEngine.class.getProtectionDomain().getCodeSource().getLocation()
```

**要求**：该字符串归一化后必须**以** `target/classes` 结尾（`is_repo_classes=true`），否则 K9。

### 3.3 调用生产实现的途径（两条，均不重实现生产量）

1. **静态直调**：`FactorResearchEngine.pearsonCorrelation(...)` / `spearmanRankCorrelation(...)` /
   `summarize(...)` —— 全部为 `public static`，直接调用；**不**复制其公式。
2. **反射调私有 block builder**：`FactorResearchService` 的构造器实测为
   `@RequiredArgsConstructor` 生成的 **9 参数全对象构造器**（`:100-108` 的 9 个 `private final`
   都是对象类型）⟹ `new FactorResearchService(null×9)` 可构造实例（**不触发**任何依赖），
   再用 `getDeclaredMethod` + `setAccessible(true)` 调
   `buildSnapshotScopeBlock` / `unavailableScopeBlock` / `emptyNetIcBlock` / `buildNetIcBlock`，
   **观测真实发射的键集与占位值**（这是 A4a / A4c / A5b / A6a 的核心测量手段）。

**必须并列打印的对照值（禁止用对照值充当被审计量）**：

* `control_rho_affine` = 1.0（**A 档**：`x → c·x + b`（`c > 0`）下 Pearson ρ 的**数学不变性**；
  本轮构造面板的 ρ 真值就是 `1.0`，缩放的对照值**必须**仍是 `1.0`）；
* `control_rank_variance(n)` = `Σ(rank − mean(rank))²`（**A 档**：秩空间的未归一化平方和，
  用于并列显示「同一守卫在秩空间为何不可触发」）；
* `control_pearson_variance(x)` = `Σ(x − mean(x))²`（**A 档**：原始空间的未归一化平方和，
  即生产守卫实际比较的量）。

### 3.4 载荷不可区分性比对（A4a 的专用机制）

A4a 不比较数值，而比较**两次生产的完整载荷**：
把 `buildSnapshotScopeBlock(scope, basis, holding, obsByDate)` 的输出**递归**序列化为
`LinkedHashMap` 保序的规范字符串（含所有键、所有嵌套、所有列表元素），
逐字符比对两个面板的输出。比对前双方**都**经生产代码路径（不手工拼装 JSON）。

### 3.5 行和交叉校验（K8）与 config 冻结比对

`config.json` 冻结：`variants` / `by_axis` / `frozen_axis_counts` / `frozen_total` /
`kill_conditions` / `count_expectations`（C1–C20）/ `verdict_vocab` / `thresholds` /
`snapshot_schema`（A3c 的 SQL 与预期列名）。
运行期逐项 `==` 比对，任一不等 ⟹ K8 ⟹ 整轮 `INCONCLUSIVE`。

### 3.6 阳性对照（K9）与探针自检（A1a）

1. **G3 门**（§3.2）。
2. **反向锚点（必须都成立，否则 K9）**：
   * `sentinel_defect_present`：`pearsonCorrelation(1e-10·[1..10], [0.01..0.10])` **必须**返回 NaN。
     若返回有限值 ⟹ 探针**没有**跑到带 `1e-18` 守卫的生产实现（跑错类 / 代码已改）⟹ K9。
   * `sentinel_normal_equals`：`pearsonCorrelation([1..10], [0.01..0.10])` 必须与 `1.0`
     相差 `≤ TOL_RHO = 1e-12`。若失败 ⟹ 正常路径被误伤 ⟹ K9。
3. **金丝雀（CLN / INJ 恰一对，共 18 对；每轴一对）**：每个检查器一对，
   `clean` 期望词与 `inj` 期望词**在运行前**写死在 `config.json`，且要求 `clean != inj`。
   **CLN 必须写到该检查器实际读取的那个键**（R007 教训第 38 条）。
   缺对、`clean == inj`、或实测与期望不符 ⟹ K9。
4. **`panel_design_selfcheck`（R008 新增条款的继承）**：金丝雀与面板设计**正交**，故必须
   额外实测每个轴所用面板的**结构谓词**（天数、每日样本数、秩 IC 有限性、Pearson 有限性、
   支撑天数），逐项与 §4 冻结期望比对。任一不等 ⟹ K9。
   （这是防「金丝雀在错误的面板上通过」的门。）
5. **与既有测试的一致性门**：以既有测试
   `FactorResearchEngineTest.pearsonCorrelationReturnsOneForPerfectLinear`（`:84-89`）的**同一输入**
   （`scores = [1,2,3,4,5]`、`returns = [0.01,0.02,0.03,0.04,0.05]`）驱动**生产实现**，
   要求返回值与 `1.0` 相差 `≤ EXISTING_TEST_TOL = 1e-12`（既有测试用 `1e-9` 容差，本轮更松）。
   不通过 ⟹ K9。

### 3.7 确定性门（K10）

同一面板、同一顺序，**两个独立构造的输入列表**各调用 `summarize` 一次，
全部输出（`pearsonIcMean`、`dailyRankIc` 全行、`crossSectionDays`、`totalObservations`）
必须**逐位**相同；否则 K10 ⟹ `INCONCLUSIVE`。

---

## 4. 逐轴断言（18 轴 = 18 变体，每轴恰 1 个变体；冻结后不得增删）

**判据档位**：`L` = 判据来源明示（**只**支持「做法种类」，**不**支持任何阈值）；
`A` = 算术推导；`P` = 项目自建（含一切**数值阈值**与自设量级线）。

**面板词汇（冻结）**：证券 `S01..S12`，`exchangeType = "SH"`；
交易日 `2026-01-05`（d1）/ `2026-01-06`（d2）；观测用
`new FactorResearchEngine.CrossSectionObservation(score, forwardReturn)` 直接构造。
**每日样本数必须 `≥ RANK_IC_MIN_CROSS_SECTION_SIZE = 10`**，否则该日被 `summarize` 整日跳过（`:140-142`）。

**「预期判定词」列的性质（冻结）**：该列是本轮**预注册的预测**，**不具约束力**；
实测一律以「判定规则」列为准。若实测与预期不一致，**必须**在报告附录 A 记录预测偏差，
**不得**修改判定规则、**不得**修改本列。

### A1 测量机制自检（2 轴；失败 ⟹ K9/K10，不产生业务判定）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 预期判定词 |
|---|---|---|---|---|---|---|
| **A1a** | V01 | 探针类路径 + §3.6 的两个反向锚点 | `is_repo_classes`、`sentinel_defect_present`、`sentinel_normal_equals` | 三项**全部**成立 ⟹ `NOT_VIOLATED`；任一失败 ⟹ **`VIOLATED`** ⟹ K9 ⟹ 整轮 `INCONCLUSIVE` | A | **NOT_VIOLATED** |
| **A1b** | V02 | 18 对金丝雀定义（`config.json`）+ 各轴面板 | 每对 `clean_verdict` / `inj_verdict` 与冻结期望是否相符；`panel_design_selfcheck` 每轴结构谓词；§3.7 两次调用逐位一致 | 18 对全部 `clean == 期望 ∧ inj == 期望 ∧ clean != inj` **且** `panel_design_selfcheck` 全通过 ⟹ `NOT_VIOLATED`；金丝雀/自检失败 ⟹ **`VIOLATED`** ⟹ K9；确定性失败 ⟹ **`VIOLATED`** ⟹ K10 | A | **NOT_VIOLATED** |

**注明**：A1a/A1b 是**门**，不是业务轴；它们若为 `VIOLATED`，其余 16 轴的判定词**一律**记
`INCONCLUSIVE`（整轮），且报告**必须**写明是「工具不可信」而非「载荷有问题」。

### A2 `pearsonCorrelation` 的定义域（4 轴，含 K1）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 预期判定词 |
|---|---|---|---|---|---|---|
| **A2a** | V03 | **复用项目自己的既有测试输入**：\n`P1: scores=[1..10]`、`returns=[0.01,0.02,…,0.10]`（n=10）\n`P1s:` `scores=1e-10·[1..10]`、`returns` 同 P1\n`P1t:` `scores=1001+[0..9]`、`returns` 同 P1\n`P1r:` `scores` 同 P1、`returns=1e-10·[0.01..0.10]` | `pearsonCorrelation(P1/P1s/P1t/P1r)`；`spearmanRankCorrelation(P1s)`；`control_pearson_variance(P1s.scores)`；`control_rho_affine = 1.0` | 若 `ρ(P1) = 1.0`（±TOL_RHO）**且** `ρ(P1t) = 1.0`（±TOL_RHO）**且** `ρ(P1s)` **非有限** **且** `variance(P1s.scores) > 0` **且** `spearmanRankCorrelation(P1s)` 有限 ⟹ **`VIOLATED`**（正尺度 `c=1e-10` 下同一 ρ 由 1.0 变为 NaN，而平移 `b=1000` 不变、秩空间不变 ⟹ 破坏的是**尺度**不变性，**不是**「方差为 0」）；否则 `NOT_VIOLATED` | **A**（Pearson ρ 对正仿射变换不变）**+P**（`c=1e-10` 这一量级线的选择） | **VIOLATED** |
| **A2b** | V04 | 固定**相对**离散度 `δ=1e-10`：\n`P2a: scores=1e-10·[1..10]`, `returns=[1..10]`（n=10）\n`P2b: scores=1e-10·[1..12]`, `returns=[1..12]`（n=12）\n`P2c: scores=1e-10·[1..11]`, `returns=[1..11]`（n=11） | 三个面板的 `ρ` 有限性；`control_pearson_variance`（预期 `8.25e-19` / `1.43e-18` / `1.10e-18`） | 若 `ρ(P2a)` 非有限 **且** `ρ(P2b)` 有限且 `≈1.0` **且** 两个面板的 `variance > 0` ⟹ **`VIOLATED`**（**同一相对离散度**下，ρ 的**可定义性**随 `n` 翻转，且翻转点是 `variance` 跨 `1e-18`，不是「方差为 0」）；否则 `NOT_VIOLATED`。`P2c` **只作披露**（预期 `ρ(P2c)` 有限），**不参与**判定 | **A**（同一相对离散度下 ρ 的真值恒为 1.0）**+P**（`δ=1e-10` 与 `n ∈ {10,11,12}` 的选择） | **VIOLATED** |
| **A2c** | V05 | 判据来源 `rounds/R009_review.md` §2 判据甲逐字文本 vs 本项目实现 | 判据来源对 Pearson IC 的定义是否就是「α 因子值与目标变量之间的 Pearson 相关」；本项目计算的是否是同一对象 | 若两处**同为**「因子值 vs 未来收益的 Pearson 相关」⟹ `NOT_VIOLATED`；否则 `VIOLATED`（⟹ 属**选族错误**，须在报告负面清单登记，**不**改变总判定） | **L** | **NOT_VIOLATED** |
| **A2d** | V06 | **协议强制的 ±20% 敏感性网格**（输入侧等价变形，**不改**生产代码）：\n尺度 \(c \in \{8e{-11}, 1e{-10}, 1.2e{-10}\}\) × \(n \in \{8, 9, 10, 11, 12\}\) = **15 格**；每格 `scores = c·[1..n]`、`returns = [1..n]` | 每格 `variance`、`ρ` 是否有限、`ρ` 值 | **不判定**（披露轴）：报告**必须**逐格列出 15 格的 `variance` 与有限性，并给出「有限/非有限」的分界线（由 `variance` 跨 `1e-18` 决定）；本轴判定词登记为 `NOT_VIOLATED`（无独立断言），但**其 15 格数据必须进报告**，且**不得**被用来支持或削弱 A2a/A2b 的判定 | A+P | **NOT_VIOLATED** |

**A2a/A2b 的精确算术（冻结，运行期必须复算并比对）**：

* `[1..10]`：`Σ(i − 5.5)² = 82.5`；`1e-10` 缩放下 `variance = 82.5e-20 = 8.25e-19 < 1e-18` ⟹ NaN。
* `[1..12]`：`Σ(i − 6.5)² = 143`；`variance = 143e-20 = 1.43e-18 ≥ 1e-18` ⟹ 有限，且 `ρ = 1.0`。
* `[1..11]`：`Σ(i − 6)² = 110`；`variance = 1.10e-18 ≥ 1e-18` ⟹ 有限（边界在 `n = 10` 与 `n = 11` 之间）。
* 秩空间：`ranks([1..n]) = [1..n]` ⟹ `Σ(rank − mean)² = n(n²−1)/12`，`n = 10` 时为 `82.5`，
  **比守卫高 19 个数量级** ⟹ F4 的「秩空间不可触发」由此获得算术支撑。

### A3 Pearson 支撑集（3 轴，含 K3）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 预期判定词 |
|---|---|---|---|---|---|---|
| **A3a** | V07 | **混合支撑集面板 `P3a`**：\n`d1 = P1`（Pearson 有限，`ρ = 1.0`）\n`d2 = P1s`（rank IC 有限，Pearson 非有限）\n两日均 10 条观测 | `daily.size()`（= `cross_section_days`）、`total_observations`、`dailyRankIc().size()`、**实际** Pearson 支撑天数（由探针在**同一面板**上分别调用两条计算路径获得，**不重实现**）、`pearsonIcMean` | 若 `cross_section_days > 实际 Pearson 支撑天数` **且** `total_observations > 0` **且** 载荷中**不存在**任何能表达「Pearson 只在 N 天上可定义」的字段（键名匹配 `pearson_(days\|count\|n\|sample\|support\|observations)` ⟹ C11） ⟹ **`VIOLATED`**；否则 `NOT_VIOLATED` | **A**（支撑集算术：`dailyPearson.size() ≤ daily.size()`，且 `cross_section_days := daily.size()`）**+P**（无字段即判定为不可见，属自设判据） | **VIOLATED** |
| **A3b** | V08 | 载荷发射点全集（§3.3 的反射调用 + 源码文本扫描） | 是否存在**任何**键名匹配 `pearson_(sample\|count\|n\|observations\|support\|days\|size)` 的字段（C11 冻结期望 **0**） | 存在 ⟹ `NOT_VIOLATED`；不存在 ⟹ **`REGISTERED_ONLY`**（登记：支撑集不可见） | **P** | **REGISTERED_ONLY** |
| **A3c** | V09 | **只读普查 SQL（唯一一条 SQL）**：\n```sql\nSELECT signal_date,\n       COUNT(*) AS n_rows,\n       COUNT(final_score) AS n_scored,\n       SUM(final_score*final_score)\n         - SUM(final_score)*SUM(final_score)/COUNT(final_score) AS sxx\nFROM feature_snapshot_row\nGROUP BY signal_date\nORDER BY sxx ASC\n```\n（列名依 `FeatureSnapshotRow` 实体：`signal_date`、`final_score`） | `days_with_data`、`min_sxx`、`days_below_guard`（`sxx < 1e-18` 的日数）、`margin_decades = log10(min_sxx / 1e-18)` | **不判定（非杀死轴）**：\n查询成功且 `n_scored > 0` 的日数 ≥ 1 ⟹ `REGISTERED_ONLY`（描述性登记）；\n无法连接 / 表或列不存在 / 表中无 `n_scored > 0` 的行 ⟹ **`NOT_REACHABLE`** 并登记 `B-R009-xx` | **P** | **REGISTERED_ONLY** |

**A3c 的强制措辞与解释边界（冻结）**：

* `days_below_guard = 0` **不得**被叙述为「该缺陷不存在」。它**只**说明
  「在**当前生产库的分值尺度**下，守卫的暴露面为 0」；缺陷的**可证伪性**由 A2a/A2b 的**精确算术**建立
  （R007 教训第 44 条）。
* A3c 的输出**不得**被表述为「生产数据上的 Pearson IC 表现」——它**只**是分值离散度的普查。
* 本轴**不参与** K 表（与 R008 对 `NOT_REACHABLE` 类轴的处置一致），
  以免「本机缺数据」污染总判定。

### A4 退化与占位（3 轴，含 K2）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 预期判定词 |
|---|---|---|---|---|---|---|
| **A4a** | V10 | **两个刻意同构的面板**（均 1 日、10 条观测、收益向量**完全相同**）：\n`P4z`（全**真 0**）：`scores=[1..10]`、`returns=[1,2,3,4,5,5,4,3,2,1]`（`Σdev_x·dev_y = 0` 精确，`ρ = 0.0`）\n`P4u`（全**不可定义**）：`scores=1e-10·[1..10]`、`returns` 同 `P4z` | 两个面板经 `buildSnapshotScopeBlock` 的**完整载荷**（§3.4 的递归规范序列化）；两者的 `pearsonIcMean`；两者的 `daily_rank_ic` | 若两份载荷**逐字符相同** **且** `pearsonIcMean == 0.0` **且** `cross_section_days > 0` **且** `total_observations > 0` **且** `P4u` 的 Pearson **完全不可定义**（无一日有限） ⟹ **`VIOLATED`**（载荷把「不可定义」输出为**看起来像测量值**的 `0.0`，且与「真 0 相关」不可区分）；否则 `NOT_VIOLATED` | **A**（`P4z` 的 `ρ = 0.0` 与 `P4u` 的不可定义均为精确算术结果）**+P**（「不可区分」作为判据） | **VIOLATED** |
| **A4b** | V11 | `FactorResearchService.round4`（`:1030-1032`，反射调私有静态方法） | 子检 1：`round4(NaN)`、`round4(+Inf)`、`round4(-Inf)`；子检 2：`pearson_ic_mean` 是否**可达** NaN（静态数据流：`:176-177` 的 `isEmpty() ? 0 : average(有限值)` + `:155` 的 `Double.isFinite(pic)` 过滤） | 子检 1 实测 `round4(NaN) == 0.0` **且** 子检 2 判定「无输入路径可达 NaN」 ⟹ **`NOT_REACHABLE`**（机制存在但不构成可达违反）；子检 1 实测 `round4(NaN) != 0.0` ⟹ `NOT_VIOLATED`；子检 2 发现可达路径 ⟹ `VIOLATED` | A（子检 1 为实测）+ **P**（「可达性」的静态判定） | **NOT_REACHABLE** |
| **A4c** | V12 | 4 个降级/正常分支的键集（反射实测）：\n`buildSnapshotScopeBlock`（正常 + `totalObservations==0` 时加 `note`）\n`unavailableScopeBlock`（`status="UNAVAILABLE"`）\n`emptyNetIcBlock`（**无** `status`、**无** `monotonicity`）\nlegacy 空分支（`:660-672`，**无** `pearson_ic_mean`） | 每个分支如何表达「该口径不可用」：用**显式键**（`status`）还是靠**缺键**；`monotonicity` 的占位值（`"N/A"` vs 缺键） | 若 ≥2 个降级分支对**同一不可用语义**采用**不同**标记方式（其中一个用 `status` 键、另一个靠缺键） ⟹ **`CONVENTION_DIVERGENCE`**（项目内两处惯例各自非「错」）；若全部一致 ⟹ `NOT_VIOLATED` | **P** | **CONVENTION_DIVERGENCE** |

### A5 契约文本与键集（2 轴，含 K4/K5）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 预期判定词 |
|---|---|---|---|---|---|---|
| **A5a** | V13 | `FactorResearchEngine.java` 的 javadoc 文本（`:49-51`，以及 `:66-68` 的 Spearman 同句）**与** A2a/A2b 的实测结果 | (i) javadoc 是否逐字含「样本不足或方差为 0」；(ii) 该文本是否含任何**量级/尺度/容差**限定词（正则 `1e-18\|量级\|尺度\|容差\|tolerance\|scale\|magnitude` 的命中数）；(iii) A2a 或 A2b 是否为 `VIOLATED` | 若 (i) 为真 **且** (ii) 的命中数为 **0** **且** (iii) 为真 ⟹ **`CONTRACT_MISLABEL`**（契约文本用「方差为 0」描述一个实际作用于「**未归一化**平方和 `< 1e-18`」的守卫，且**未**给出任何量级限定）；否则 `NOT_VIOLATED` | **A**（A2a/A2b 的算术）+ **L**（判据来源确实把 Pearson 相关系数当作与目标变量的相关度量，故「声明—行为一致」是本轮判据的合法对象）+ **P**（文本判据） | **CONTRACT_MISLABEL** |
| **A5b** | V14 | 前端 TS 契约 `modules/web/src/api/factor.ts` 的字段声明（`:109`/`:201` 的 `pearson_ic_mean`；`:206-215` 的 `cost_net_ic?`）**与** backend 各分支实测键集 | `pearson_ic_mean` 在 legacy 空分支（`:660-672`）是否**发射**；`monotonicity` 在 `cost_net_ic` 的**非空**分支（`:830`）是否发射、在**空**分支（`:810`）是否发射 | 若存在**可达**分支使「TS 声明为非可选的键」在后端**缺失**（`pearson_ic_mean` ∈ legacy 空分支缺失集） ⟹ **`VIOLATED`**（载荷与声明契约不一致）；否则 `NOT_VIOLATED`。**可达性必须实测/静态论证**（`latestPerKey.isEmpty()` 是否可达）并写入报告 | **P**（TS 接口是项目契约文本）+ **A**（键集枚举） | **VIOLATED** |

### A6 口径可消歧与跨轮复核（2 轴，含 K6）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 预期判定词 |
|---|---|---|---|---|---|---|
| **A6a** | V15 | 5 个发射点（`:542`/`:568`/`:761`/`:810`/`:830`）各自 block 的**兄弟键集**（反射实测） | 每处的支撑集定义（入场日 / 退出日 / 是否扣费）是否不同；该处是否存在能区分口径的**结构化**字段（键名匹配 `metric_scope\|basis_note\|status\|universe_note\|口径`） | 若存在两处 `pearson_ic_mean` 的支撑集定义**不同** **且** 该两处**都缺**结构化区分字段 ⟹ **`CONTRACT_AMBIGUOUS`**；否则 `NOT_VIOLATED` | **P**（判据来源不涉及键命名与载荷结构） | **NOT_VIOLATED** |
| **A6b** | V16 | **对 R006 冻结结论的实测复核**：R006 A4b 已把「`ic` 有限 ⟺ `pearson` 有限」记为 **A 档等价** | 是否存在输入使 `isFinite(spearmanRankCorrelation(·)) && !isFinite(pearsonCorrelation(·))`（A2a 的 `P1s` 即候选） | 存在 ⟹ **`VIOLATED`**（R006 的 A 档等价在本轮被**新证据**证伪）；否则 `NOT_VIOLATED` | **A** | **VIOLATED** |

**A6b 的处置条款（冻结，先于测量写下）**：

* 本轴若为 `VIOLATED`，**只**构成**本轮**的独立发现；**不得**回写、改写或重新解释
  `rounds/R006_prereg.md` / `rounds/R006_report.md` 的任何冻结文本。
* 报告**必须**（i）写明「R006 A4b 的 A 档等价被新证据证伪」；（ii）
  把该证伪登记进 `decision_queue.md`（含 R006 报告勘误建议）；（iii）
  **不得**据此叙述「R006 整轮失败」或「R006 结论不可用」。
* R006 A6c 若被本轮证据牵连，同样**只**登记，**不**改写。

### A7 跨面一致性（2 轴；A7a 为**非杀死轴**，A7b 纳入 K7）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 预期判定词 |
|---|---|---|---|---|---|---|
| **A7a** | V17 | 既有测试文件 `FactorResearchEngineTest.java`（`:84-95`）的文本 | 是否存在覆盖**分值量级/尺度**维度的用例（测试输入是否含 `1e-` 量级的 `scores`/`returns`） | 存在 ⟹ `NOT_VIOLATED`；不存在 ⟹ **`REGISTERED_ONLY`**（登记：既有测试只覆盖尺度 1 的一侧，缺尺度维度）。**非杀死轴**：本轴衡量的是**测试覆盖度**而非载荷正确性，故不参与 K 表，其结论只进报告与 `decision_queue.md` | **P** | **REGISTERED_ONLY** |
| **A7b** | V18 | `modules/web/src/views/factor/FactorResearch.vue` 的 `exportUnifiedCsv` 方法体（`:499-517`）文本 | CSV 列 `pearson_ic` 的取值表达式是否含**缺测/不可用**分支（`??`、`\|\|`、`isFinite`、`N/A`、`—`、`UNAVAILABLE` 任一） | 含分支 ⟹ `NOT_VIOLATED`；纯取值（无任何分支） ⟹ **`REGISTERED_ONLY`**（导出面把占位 `0.0` 与真测量值写成同一列） | **P** | **REGISTERED_ONLY** |

**轴数合计（冻结）**：A1=2，A2=4，A3=3，A4=3，A5=2，A6=2，A7=2 ⟹ **18**。
**非杀死轴集合（冻结）**：**{A2c, A2d, A3c, A7a}**。
非杀死轴**不参与** K 表合成，但其判定词**必须逐轴写入报告**（不得省略）。
非杀死轴的四条理由（逐条冻结）：

* **A2c**：若为 `VIOLATED`，说明**选族错误**（判据来源与本项目算的不是同一对象），
  这不是载荷缺陷，只能在报告负面清单登记；
* **A2d**：协议强制的**敏感性披露**轴，只有 15 格数据，无独立断言；
* **A3c**：唯一依赖本机数据库的轴，若把 `NOT_REACHABLE` 纳入 K 表，
  「本机缺数据」会污染总判定（与 R008 对 `NOT_REACHABLE` 类轴的处置一致）；
* **A7a**：衡量的是**测试覆盖度**而非载荷正确性。

（A7b 虽同属「跨面」，但因其衡量的是**载荷消费面**（CSV 导出把占位值当真值输出），故**纳入** K7。）

---

## 5. 阈值与常数（冻结，运行期不得修改）

| 名 | 冻结值 | 档 | 说明 |
|---|---|---|---|
| `TOL_BIT` | `1e-12` | A | 逐位/数值一致容差 |
| `TOL_RHO` | `1e-12` | A | ρ 与对照（`1.0`/`0.0`）的数值一致容差；两真值均为精确值 |
| `GUARD_T` | `1e-18` | **A** | 生产守卫阈值（**不是**本轮自设；来自 `FactorResearchEngine.java:314` 源码逐字） |
| `CONTROL_PLUS_INF` | `9.223372036854776E14` | A | `round4(±Inf)` 的实测值（R007 教训第 37 条） |
| `SCALE_C` | `1e-10` | **P** | 触发守卫的缩放因子（**量级线的自设值**，非阈值比较）；其选择不改变 A2a 的算术性质 |
| `SCALE_GRID` | `{8e-11, 1e-10, 1.2e-10}` | P | ±20% 敏感性网格（`1e-10 ± 20%`） |
| `N_GRID` | `{8, 9, 10, 11, 12}` | P | n 敏感性网格（`10 ± 2`） |
| `N_PANEL_MIN` | `10` | A | `RANK_IC_MIN_CROSS_SECTION_SIZE` 的生产常量值：**每日样本数低于此值整日被跳过**，故所有面板每日样本数 `≥ 10` |
| `RHO_LO` / `RHO_HI` | `-1.0` / `1.0` | A | Pearson ρ 的数学值域（用于 A4b 子检 2 的 `/` 结构与 A2d 的 ρ 值检查） |
| `EXISTING_TEST_TOL` | `1e-12` | A | 与既有测试 `pearsonCorrelationReturnsOneForPerfectLinear` 的一致性门（既有测试用 `1e-9`，本轮更松） |
| `MIN_SCORE_GAP_ASSUMED` | `1e-4` | **P** | F9 需要的最小分值间隔假设（**仅**用于「生产可达性」的量级说明，**不**参与任何判定词） |

### 5.1 关键事实（预注册即冻结的技术前提，运行期逐条实测复核）

| # | 事实 | 位置 |
|---|---|---|
| **F1** | `pearson` 的 `vx`/`vy` 是**未归一化平方和** `Σ(x−x̄)²`、`Σ(y−ȳ)²`，**不是**方差（未除以 `n`） | `FactorResearchEngine.java:304-313` |
| **F2** | 守卫是 `if (vx < 1e-18 \|\| vy < 1e-18) return Double.NaN;`（作用于 **F1 的未归一化量**） | `:314-316` |
| **F3** | 对正尺度 `x → c·x`：`vx → c²·vx`；对平移 `x → x+b`：`vx` **不变** ⟹ 守卫**破坏尺度不变性、保留平移不变性** | F1 + F2（算术） |
| **F4** | `spearmanRankCorrelation(double[],double[])` **复用同一个 `pearson`**，但输入是**秩**（量级 `O(n)`）⟹ 同一守卫在秩空间几乎不可触发（n=10 时秩空间平方和 `82.5`，比守卫高 **19** 个数量级） | `:83-90`、`:271-292` |
| **F5** | `summarize` 先算秩 IC 并以 `!Double.isFinite(ic) → continue` 过滤，**再**算 Pearson ⟹ `daily.size()`（= `cross_section_days`）是**秩 IC 的支撑天数**；`dailyPearson.size() ≤ daily.size()` | `:149-158`、`:196`（传 `daily.size()`） |
| **F6** | `totalObs += obs.size()` 与 `daily.add(...)` 在**同一** rank-finite 分支内（`pic` 是否有限**不影响**计数）⟹ `total_observations` 同样只覆盖 rank-finite 日 | `:154-158` |
| **F7** | `pearsonIcMean = dailyPearson.isEmpty() ? 0 : average(...)` ⟹ **「无支撑」与「真 0」在数值上不可区分** | `:176-177` |
| **F8** | `emptySummary` 把全部数值置 0、`monotonicity` 置 `"N/A"`；由 `daily.isEmpty()` 触发（R006 A7a 已判定退化单调性，本轮不重复） | `:214-221`、`:166-168` |
| **F9** | 触发守卫需要 `Σ(x−x̄)² < 1e-18`。加分值为 0–100 量级、最小非零间隔按 **P 档**自设 `1e-4` 计，则在**自设量级线下实测为不可达**：需同日内**全部**分值相对均值偏离 `< 3.2e-10`（`n=10`），即约 **12 位有效数字**的相对精度 ⟹ 生产数据**不可达**。该可达性结论**不削弱** A2a/A2b 的判定（它们由精确算术构造，与真实数据无关）；报告**必须**同时披露「可达性 = 生产不可达」与「契约违反 = 已证」（R007 教训第 44 条） | `:304-316` + `MIN_SCORE_GAP_ASSUMED` |
| **F10** | 载荷**不存在**任何 Pearson 支撑计数字段（C11 冻结期望 **0** 命中）；`daily_rank_ic[]` 行只有 `{trade_date, rank_ic, sample_size}`，**无** Pearson 日度值 | C11；`FactorResearchService.java:580-590` |
| **F11** | `round4(v) = Math.round(v*10000.0)/10000.0`：`round4(NaN) = 0.0`；`round4(±Inf) = ±9.223372036854776E14` | `FactorResearchService.java:1030-1032` |
| **F12** | `pearson_ic_mean` 共 **5** 个发射点：`:542`（scope block）/ `:568`（unavailable）/ `:761`（legacy 正常）/ `:810`（空 net）/ `:830`（net 正常）。**legacy 空分支（`:660-672`）不发射** `pearson_ic_mean`/`monotonicity`/`hac_std`/`ci_lower`/`ci_upper`；`emptyNetIcBlock` **不发射** `monotonicity`；`unavailableScopeBlock` **发射** `status="UNAVAILABLE"` | `FactorResearchService.java:542/568/660-672/761/810/830` |
| **F13** | 前端 TS 契约把 `pearson_ic_mean` 声明为**必需** `number`（两处），而 `cost_net_ic?` 的字段表**不含** `monotonicity` | `modules/web/src/api/factor.ts:109`、`:201`、`:206-215` |
| **F14** | 前端 CSV 导出的 `pearson_ic` 列直接取 `row.pearson_ic_mean` | `FactorResearch.vue:499-517` |
| **F15** | `FactorResearchService` 由 `@RequiredArgsConstructor` 生成 **9 参数全对象构造器** ⟹ `new FactorResearchService(null×9)` 可用，私有 block builder 可反射调用 | `FactorResearchService.java:26`、`:100-108` |
| **F16** | 判据来源（SJK2024）逐字：「we use the Pearson correlation coefficient to measure the correlation between the target variable and alpha factor. Additionally, we use the Spearman rank correlation coefficient to measure rank-based correlations.」——**只**支持「做法种类」 | `rounds/R009_review.md` §2 判据甲 |

### 5.2 前视 / 幸存者偏差影响（协议 §5 强制披露）

* 本轮 **17/18** 个变体**不产生任何收益序列、不做任何选样、不读任何数据库**；
  其输入是**构造面板** ⟹ 前视偏差与幸存者偏差对这些轴的测量**不适用**。
* **A3c 是唯一的真实数据轴**，它读的是生产库 `feature_snapshot_row` 的**分值离散度**：
  * 该表的上游（`selector_result` 抽样 vs `feature_snapshot_row` 全截面）是否受生存者偏差影响，
    **本轮不评估**，只登记（属另一族载荷的审计）；
  * A3c **不**计算任何 IC、**不**计算任何收益、**不**做任何排序统计 ⟹ 前视偏差对 A3c 的测量**不适用**；
  * A3c 的输出**不得**被表述为「生产数据上的 Pearson IC 表现」（见 §4 A3c 的解释边界）。
* **A6b 引用的 R006 A4b**：R006 的结论建立在其自身面板上；本轮**只**测「同一面板上两条路径的
  有限性是否等价」，**不**重测 R006 的其余结论。
* 被审计对象**自身**的 `universe_note` 声明了幸存者偏差（沿用打分回测口径）——本轮**只登记其存在**
  （属 A6a 的兄弟键集观测），**不**评估其是否充分。

### 5.3 变体预算与每轴变体数

**每轴恰 1 个变体，共 18 个（V01–V18）；冻结上限 18，不得超支。**
变体 = 「一组冻结输入 + 一次生产实现调用」。A3c 的**一条**只读聚合 SQL 计 1 个变体。
同一面板可复用于多个轴（复用时仍各自计数，如 `P1s` 同时服务 A2a 与 A6b）。

### 5.4 区间下界类判据：本轮**不适用**的理由（逐条，承接 R007 教训第 40 条）

R007 的冻结条款要求「覆盖率/命中率类断言必须用区间下界（Clopper–Pearson）且等价变形写进预注册」。
**本轮没有任何抽样统计量**，故该条款**不被触发**。逐条理由：

1. **17 个变体全部是构造面板**（确定性输入），**零** Monte Carlo、**零**随机种子、**零**重复抽样 ⟹
   每个测量都是**单点确定性值**，「覆盖率」概念不存在；
2. 本轮唯一的「计数类」量（C1–C20 的命中数、A5b/A6a 的键集、A3b 的字段存在性）都是**穷举枚举**
   （全文件走查 / 全键集枚举），**不是**从总体中抽样；
3. **A3c 是全表普查**：SQL 用 `COUNT/SUM ... GROUP BY signal_date`，**无 `LIMIT`、无 `OFFSET`、无抽样子句** ⟹
   结果就是总体本身（`feature_snapshot_row` 的**全部**行），普查**不构成**抽样 ⟹ Clopper–Pearson **不适用**；
4. **等价变形声明（冻结，本轮生效）**：若运行期发现表规模导致**必须**改为抽样
   （例如不得不加 `LIMIT`），则 **A3c 必须改判 `NOT_REACHABLE`** 并登记 `B-R009-xx`，
   **不得**以点估计冒充普查结论、**不得**给出任何置信区间；
5. **真实的覆盖率类问题确实存在，但本机不可回答**：真实数据中「同一截面内分值离散度 `< 1e-18` 的日数占比」
   决定 A2a/A2b 的**实际暴露面**。A3c 试图**普查**该占比（而非抽样估计），
   若不可达则按协议记 `NOT_REACHABLE` + `blocked_queue.md`，**不**以估计值替代、**不**停止循环；
6. **继承条款（对后续轮次有效）**：若未来任一轴引入抽样，必须回到**区间下界**形式并写出
   等价变形（如 R007 的 `L < 0.85 ⟺ P(X ≥ k | p = 0.85) > 0.01`），且等价变形**必须先于**测量写入预注册。

---

## 6. 杀死条件（冻结 K 表）与总判定合成规则

### 6.1 K 表（按 id 升序，逐字冻结；运行期只计算布尔值，不得改写条件文本）

| id | 触发条件（冻结） | 结果 |
|---|---|---|
| **K1** | `A2a == VIOLATED` **或** `A2b == VIOLATED`（方差 > 0、ρ 真值不变的输入上返回 NaN） | `REJECTED` |
| **K2** | `A4a == VIOLATED`（全部不可定义的 Pearson 与全部为 0 的 Pearson 产出**逐字符相同**的载荷） | `REJECTED` |
| **K3** | `A3a == VIOLATED`（`cross_section_days` 大于真实 Pearson 支撑天数，且无任何字段可表达该差） | `REJECTED` |
| **K4** | `A5a == CONTRACT_MISLABEL`（javadoc 用「方差为 0」描述未归一化平方和守卫，且无量级限定词） | `CONTRACT_MISLABEL` |
| **K5** | `A5b == VIOLATED`（可达分支缺失 TS 声明为必需的键） | `CONTRACT_MISLABEL` |
| **K6** | `A6a == CONTRACT_AMBIGUOUS`（同名键两处口径不同且都缺结构化区分字段） | `CONTRACT_AMBIGUOUS` |
| **K7** | `{A3b, A4b, A4c, A6b, A7b}` 中任一判定词 `∉ {NOT_VIOLATED}`（**载荷消费面上的登记类问题兜底**） | `REGISTERED_ONLY` |
| **K8** | 行和交叉校验失败（§3.5：轴数 / 变体映射 / `config.json` 冻结比对任一不等） | `INCONCLUSIVE` |
| **K9** | 阳性对照与探针自检失败（§3.6 任一：G3 门 / 两个反向锚点 / 18 对金丝雀 / `panel_design_selfcheck` / 与既有测试一致 / 出现表外判定词）**或** `A1a == VIOLATED` | `INCONCLUSIVE` |
| **K10** | 确定性门失败（§3.7：同一输入同一顺序两次调用不逐位一致）**或** `A1b` 的确定性子检失败 | `INCONCLUSIVE` |

### 6.2 总判定合成规则（按序短路，冻结）

```
若 K8 ∨ K9 ∨ K10            -> total = INCONCLUSIVE
否则若 K1 ∨ K2 ∨ K3          -> total = REJECTED
否则若 K4 ∨ K5               -> total = CONTRACT_MISLABEL
否则若 K6                    -> total = CONTRACT_AMBIGUOUS
否则若 K7                    -> total = REGISTERED_ONLY
否则                          -> total = NOT_VIOLATED
```

**H0 存活条件（冻结，独立于总判定）**：`¬K1 ∧ ¬K2 ∧ ¬K3`。
**强制措辞规则**：

* `H0 存活` **不得**被叙述为「项目没问题」；`H0 被杀` **不得**被叙述为「项目不可用」。
* 报告**必须同时**给出：`h0_alive`、`total_verdict`、以及 **K1–K10 的布尔真值**
  （不得只写「未触发」）。
* 报告**必须**披露：**K7 的期望触发概率很高**（其集合内的轴在预注册中就被预期为非 `NOT_VIOLATED`），
  因此 `total = REGISTERED_ONLY` 是本轮的**兜底地板值**，K7 单独触发**不具区分力**；
  真正有区分力的是 K1–K3 与 K4–K6。

---

## 7. 计数断言 C1–C20（冻结期望；**不匹配只记报告附录 A，不改变任何判定词**）

命令口径：Python 内部 walker（**斜杠归一化**路径；`src` 口径排除
`target`/`dist`/`node_modules`/`__pycache__`/`build`/`out`/`.git`；`raw` 口径**不排除**构建产物），
与 R007/R008 完全一致；扩展名集合 `.java/.vue/.ts/.js/.sql/.py/.md/.json/.yml`。
**冻结期望在预注册时由只读扫描取得**（扫描先于任何实验代码，且本轮**不**据计数改变任何判定）。
**`raw` 列只在 C1/C4/C10 三行实测**（其余行未测，故记为 `—`；不得据此推断）。

| id | 冻结命令（语义） | 冻结期望（src） | 冻结期望（raw） | by_file 期望 |
|---|---|---|---|---|
| **C1** | `pearson_ic_mean` 于 `modules/**` | **11** | **17** | FactorResearchService 5 / factor.ts 3 / FactorResearch.vue 3 |
| **C2** | `pearson_ic_mean` 于 `modules/backend-java/src/**/*.java` | **5** | — | FactorResearchService 5（**等于 5 个发射点**） |
| **C3** | `pearson_ic_mean` 于 `modules/web/src/**` | **6** | — | factor.ts 3 / FactorResearch.vue 3 |
| **C4** | `(?i)pearson` 于 `modules/**` | **41** | **53** | engine 13 / FactorResearchService 10 / Test 7 / FactorResearch.vue 6 / factor.ts 4 / Controller 1 |
| **C5** | `pearsonCorrelation` 于 `modules/**` | **7** | **7** | Test 5 / engine 2 |
| **C6** | `pearsonCorrelation` 于 `modules/backend-java/src/test/**` | **5** | — | Test 5 |
| **C7** | `dailyPearson` 于 `modules/**` | **4** | — | engine 4（**从不离开引擎**：无 getter、不进 `FactorResearchSummary`） |
| **C8** | `DailyRankIc` 于 `modules/**` | **15** | — | engine 5 / FactorResearchService 3 / factor.ts 3 / ScoringBacktestResponse 2 / ScoringBacktestService 2 |
| **C9** | `spearmanRankCorrelation` 于 `modules/**` | **6** | — | engine 4 / Test 2 |
| **C10** | `monotonicity` 于 `modules/**` | **19** | **27** | FactorResearchService 7 / FactorResearch.vue 4 / engine 3 / Test 3 / factor.ts 2 |
| **C11** | `pearson_(sample\|count\|n\|observations\|support\|days\|size)` 于 `modules/**` | **0** | — | — （**直接证据：不存在任何 Pearson 支撑计数字段**） |
| **C12** | `(?i)pearson` 于 `modules/**/*.sql`（**不排除**构建产物） | **0** | **0** | — （数据库层无任何 Pearson 相关列/注释） |
| **C13** | `1e-18` 于 `modules/backend-java/src/**/*.java` | **2** | — | engine 2（`:314` 的两个比较） |
| **C14** | `(?i)pearson` 于**全仓** `**/*.sql`（排除构建产物） | **0** | — | — |
| **C15** | `方差为 0` 于 `modules/backend-java/src/**` | **2** | — | engine 2（`:50`、`:67` 两句 javadoc） |
| **C16** | `pearson_ic(?!_mean)` 于 `modules/**` | **1** | — | FactorResearch.vue 1（CSV 表头 `pearson_ic`） |
| **C17** | `Double\.isFinite` 于 `modules/backend-java/src/**/*.java` | **12** | — | ScoringBacktestService 3 / CrossSectionNeutralizer 3 / engine 2 / CodexShortCycleScoringEngine 2 / AdjustFactorDerivationService 1 / T1CandidateService 1 |
| **C18** | `1e-1[28]` 于 `modules/backend-java/src/**/*.java` | **23** | — | ProbabilityCalibrationService 5 / TechnicalIndicators 4 / engine 3 / FactorCollinearityChecker 3 / CalibratorService 2 / LogisticModelTrainer 2 / PredictionService 1 / T1CandidateService 1 / CrossSectionNeutralizer 1 / DynamicWeightingService 1 |
| **C19** | `pearson`（**仅小写**）于 `modules/backend-java/src/**/*.java` | **22** | — | FactorResearchService 8 / engine 8 / Test 6 |
| **C20** | `NaN` 于 `modules/backend-java/src/main/java/com/quant/service/backtest/**/*.java` | **15** | — | StrategyBacktestEngine 9 / engine 6 |

> **口径差异必须披露（冻结）**：C4 大小写不敏感（含 `Pearson` 在 javadoc/注释中的出现），
> C19 仅小写；两者不可直接相减推断「注释中的 Pearson 数」，报告**必须**并列原始数字。

---

## 8. 交付物清单（冻结）

| 交付物 | 路径 | 说明 |
|---|---|---|
| 论文审查 | `rounds/R009_review.md` | 已完成（**先于本文件**） |
| **本预注册** | `rounds/R009_prereg.md` | 冻结于 `f139fbfb` |
| 论文载体 | `papers/sjk2024_arxiv_2401.02710.{pdf,txt}` | sha256 见 review §1 |
| Java 探针 | `experiments/R009_pearson_ic_audit/probe/R009Probe.java` | 编译进 `com.quant.service.backtest`；**不**重实现生产量 |
| 冻结配置 | `experiments/R009_pearson_ic_audit/config.json` | 由 `make_config.py` 生成，运行期逐项比对 §3.5 |
| 编排器 | `experiments/R009_pearson_ic_audit/run_pearson_audit.py` | 只读；含 18 对金丝雀 + `panel_design_selfcheck` + K8/K9/K10 门 + A3c 单条只读 SQL |
| 运行产物 | `run.log`（append-only）/ `result.json` | `probe/out/*` 运行后删除 |
| 报告 | `rounds/R009_report.md` | 逐轴判定 + 量级/可达性表 + 负面清单 + 附录 A 勘误 + 实际运行次数 + C1–C20 明细 |

**运行次数**：探针+编排器允许因**自身缺陷**多次运行；每次运行都在 `run.log` 留痕，
报告必须披露**实际运行次数**与每次的失败原因（R007 教训第 41 条，不得只报成功那一次）。

---

## 9. 红线重申（协议 §七/§八/§十六）

* 本轮属**只读审计**：不启用真实自动交易、不向真实账户下单、不读取/生成真实凭证、
  不把任何真实交易接口改成默认启用、不删除历史数据、不做不可逆迁移。
* 不修改公开 API / 前端契约 / 数据库结构（本轮**只**观察它们）。
* **数据库访问限于 A3c 的单条只读聚合 SELECT**；**禁止**任何 `INSERT/UPDATE/DELETE/DDL`、
  禁止读写生产业务表以外的对象、禁止导出明细行（**只**取聚合值）。
* 若本轮发现的问题属于 §7 record-only 档（改口径 / 改权重 / 改契约 / 改结构 / 影子→正式），
  **一律只登记 `decision_queue.md`，由用户决定**；agent **不得**自行修改。
* **零影子因子、零提升**（`shadow_registry.md` 本轮追加空轮记录）。
* A6b 对 R006 冻结结论的证伪**只**登记，**不**回写任何既有轮次的冻结文本。

---

## 10. 冻结声明

本文件在基线 `f139fbfb` 上、于 **2026-09-14 22:35 (+0800)** 冻结。
自此刻起，`experiments/R009_pearson_ic_audit/**` 的任何代码**只是**本文件的可执行实现；
实现的任何偏差**只能**记入 `rounds/R009_report.md` 附录 A（勘误），**不得**回写本文件。
