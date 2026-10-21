# R008 预注册（审计轮）：换手率载荷族 `avg_turnover` / `max_turnover` / `min_turnover` / `daily_turnover[]`

> **冻结时间：2026-09-14 21:41 (+0800)，冻结于基线 commit `7f493011`。**
> **本文件先于本轮任何实验代码（`experiments/R008_turnover_audit/**`）创建并冻结。**
> 判据来源：Yao & Zheng，arXiv:2606.08285v1（2026-06-06），审查见 `rounds/R008_review.md`。
> 报告：`rounds/R008_report.md`（写于测量之后）。
>
> **冻结条款**：本文件一经写下，**任何阈值、判定词、变体数、杀死条件不得事后放宽**。
> 事后发现的更正**只能**写入 `rounds/R008_report.md` 的**附录 A（勘误）**，**不得**回写本文件。

---

## 1. 被审计对象与 H0

### 1.1 被审计对象（载荷族，逐键列全）

生产入口：`modules/backend-java/src/main/java/com/quant/service/BacktestTurnoverService.java`（134 行）
`public Map<String,Object> analyze(String strategy, LocalDate startDate, LocalDate endDate, Integer topN)`
——由 `BacktestTurnoverController`（`GET /api/backtest/turnover`）暴露。

| 键 | 位置 | 类型/单位 | 消费方 |
|---|---|---|---|
| `strategy` | `:60` | 字符串（`null` → `"ALL"`） | `TurnoverResponse.strategy` |
| `start_date` / `end_date` / `top_n` | `:61-63` | 回显 + 钳位后的 n | 前端「信号日」旁 |
| `signal_days` | `:64` | 计数 | 前端 |
| `avg_turnover` / `max_turnover` / `min_turnover` | `:123-125`（退化分支 `:67-69`） | 分数（前端 ×100 显示为 %） | `ScoringBacktest.vue:484-486` |
| `daily_turnover[]` | `:126`（退化分支 `:70` 空数组） | 每元素 4 键 | `ScoringBacktest.vue:488-496` |
| ↳ `trade_date` | `:112` | 日期 | 表格列 |
| ↳ `turnover` | `:113` | 分数（`round4`） | 表格列「换手率」 |
| ↳ `new_count` / `exit_count` | `:114-115` | 集合差计数 | 表格列「新进数/退出数」 |
| `note` | `:127`（退化分支 `:71`） | 自由文本（人类字符串） | `ScoringBacktest.vue:498` |

**声明的口径（生产文件 `:24-31` 的 javadoc，逐字）**：

> 「换手率 = `1 - |相邻两日 TopN 交集| / topN`（**两日均有 topN 个候选时**）。」
> 「同策略同日取最新一条；按 trade_date 升序排列。」
> 「仅作候选稳定性研究，无未来函数；存在幸存者偏差（沿用打分回测口径）。」

实现的关键三行：`:90`（`.limit(n)` 取前 n）、`:103`（`1.0 - (double) intersection.size() / n`）、
`:52-55`（`latestPerKey.putIfAbsent(strategyType + "|" + tradeDate, r)`，键含策略）。

### 1.2 H0（本文件冻结，不得事后改写）

> **H0**：`BacktestTurnoverService.analyze` 输出的换手率载荷族，在**任何**输入下都忠实对应其声明的口径
> （换手率 = `1 − |相邻两日 TopN 交集| / topN`）；且在该口径**不适用**时
> ——（i）任一日实际候选数 < `topN`，或（ii）被配对的相邻两项**不是同一策略的相邻信号日**——
> **不输出看起来像换手率测量值的实数**。

**H0 的存活条件（冻结）**：`K1 与 K2 与 K3 全部未触发`（见 §6）。
**H0 存活 ≠ 总判定为 `NOT_VIOLATED`**（总判定见 §6 的按序短路规则）。

---

## 2. 判定词表（冻结 8 词，逐词定义，运行期不得增删改）

| 判定词 | 冻结含义 | 与 R004–R007 的一致性 |
|---|---|---|
| `NOT_VIOLATED` | 该轴在冻结输入上**通过**（实测与声明口径一致 / 无问题证据） | 同 |
| `VIOLATED` | 同一输入下，实测与**声明口径**不一致，且不一致**可复现** | 同 |
| `CONVENTION_DIVERGENCE` | 与判据来源或项目内**另一处**惯例不一致，但两处惯例各自都非「错」，不单方面判死 | 同 |
| `CONTRACT_AMBIGUOUS` | 契约允许两种以上读法，或**同名/同标签**指代不同量，读者无法单凭载荷消歧 | 同 |
| `CONTRACT_MISLABEL` | 契约**文本对自身行为的描述**与代码行为不符（含「声明了未被强制的前置条件」） | 同 |
| `REGISTERED_ONLY` | 只登记（不构成违反）：报告不完整、口径未声明、退化标记缺失等 | 同 |
| `NOT_REACHABLE` | 该轴在**本机可用条件下不可测**（无 DB、无样例数据） | 同 |
| `INCONCLUSIVE` | 该轴因 G3 门/金丝雀/确定性未通过而不可判 | 同 |

**判定词的合法集合在本轮即上表 8 词**；出现表外词 ⟹ K9 触发（§6）。

---

## 3. 测量机制（冻结）

### 3.1 只读红线

* **不访问任何数据库**（本轮 0 条 SQL）；仅读文件系统 + 在 JVM 内调用生产类。
* **不改**任何生产代码 / 测试 / 前端 / 契约 / `ResearchConvention`。
* 发现一律**只登记** `decision_queue.md` / `blocked_queue.md`，**不修**。
* 本实验目录下只写 `run.log`（append-only）、`result.json`、`config.json`、`probe/out/*`（运行后删除）。

### 3.2 断言对象必须是仓库自己的生产类（门 G3）

探针 `probe/R008Probe.java` 编译进 **`com.quant.service.backtest`** 包（与 R005–R007 相同做法），
以 `-cp <repo>/modules/backend-java/target/classes` 运行，并打印

```
BacktestTurnoverService.class.getProtectionDomain().getCodeSource().getLocation()
```

**要求**：该字符串归一化后必须**以** `target/classes` 结尾（`is_repo_classes=true`），否则 K9。

### 3.3 调用生产实现的唯一途径：JDK 动态代理伪造两个 Repository（本轮新增 harness 机制）

`BacktestTurnoverService` 的构造器实测为
`public BacktestTurnoverService(SelectorResultRepository, SelectorResultItemRepository)`
（`javap` 实测），两个参数均为 **Spring Data 接口**。本轮**不使用** Spring 容器，而是：

1. 用 `java.lang.reflect.Proxy` 为两个接口各生成一个代理，`InvocationHandler` 只处理
   生产代码实际调用的两个方法：
   `findByTradeDateBetweenOrderByCreatedAtDesc(LocalDate,LocalDate)`（返回**按给定顺序**的 `List<SelectorResult>`）
   与 `findByResultIdInOrderByRankNo(Collection<String>)`（返回**按给定顺序**的 `List<SelectorResultItem>`）；
2. 其余方法一律 `throw new UnsupportedOperationException(name)`——**一旦生产代码调用到未预期的方法，
   实验立即失败**，不得静默返回空值（这是防「测了个不是生产路径的东西」的硬门）；
3. 实体用 `new SelectorResult()` / `new SelectorResultItem()` + setter 构造（与既有测试
   `BacktestTurnoverServiceTest` 完全同构）。

**必须并列打印的对照值（禁止用对照值充当被审计量）**：
* `control_declared_full(n, k)` = `1 − k/n`（**A 档**：满员日下声明口径的算术值）；
* `control_oneway_defined(p, c, k)` = `0.5 * ((p−k)/p + (c−k)/c)`（**A 档**：判据来源 Eq.(3)「换手率定义在
  实际持仓权重上」在等权、实际规模 `p`/`c` 下的**单向**折算；`p=c=n` 时退化为 `1 − k/n`，故与 `control_declared_full` 自洽）；
* `control_within_strategy(...)` = 严格按「同策略、相邻信号日」配对的单向换手率均值。
* **所有对照值只能与被审计量同输入并列打印**（不得重实现生产量后拿来代替）。

### 3.4 支撑性反解（用于**证明**声明口径本身，而非作对照）

`n_recovered = k / (1 − reported_turnover)`，其中面板构造成 `|prev| = |curr| = k` 且 `|curr \ prev|` 已知。
该反解**只**用于 A4c「回显 `top_n` 是否等于实际分母」，是**载荷分析**，不是对照实现。

### 3.5 行和交叉校验（K8）与 config 冻结比对

`config.json` 冻结：`variants` / `by_axis` / `frozen_axis_counts` / `frozen_total` /
`kill_conditions` / `count_expectations` / `verdict_vocab` / `thresholds`；
运行期逐项 `==` 比对，任一不等 ⟹ K8 ⟹ 整轮 `INCONCLUSIVE`。

### 3.6 阳性对照（K9，三件套）

1. **G3 门**（§3.2）+ **反向锚点哨兵**：在 A2a 面板上，生产值 **必须不等于** 对照定义值
   （`sentinel_reverse_differs = true`）；在 A1a 面板上生产值 **必须逐位等于** 对照值
   （`sentinel_full_member_equals = true`）。两者都作为门，缺一 ⟹ K9。
2. **金丝雀（CLN / INJ 恰一对，共 21 对）**：每个检查器一对，`clean` 期望词与 `inj` 期望词
   **在运行前确定**，且要求 `clean != inj`。**CLN 必须写到该检查器实际读取的那个键**
   （R007 教训第 38 条，本轮显式声明遵守）。任一检查器缺对、或 `clean == inj`、或实测与期望不符 ⟹ K9。
3. **与既有测试的一致性门**：以既有测试 `BacktestTurnoverServiceTest.analyzeComputesTurnoverBetweenDays`
   （`:51-71`）的**同一输入**（topN=3，d1={A,B,C}，d2={A,B,D}，两日均满员）驱动**生产实现**，
   要求 `avg_turnover` 与 `1−2/3` 的 `round4` 值 `0.3333` 相差 `≤ EXISTING_TEST_TOL = 1e-12`
   （既有测试用 `isEqualTo(0.3333)` 精确相等，本轮容差更松，只会更容易通过）。不通过 ⟹ K9。

---

## 4. 逐轴断言（21 轴 = 21 变体，每轴恰 1 个变体；冻结后不得增删）

**判据档位**：`L` = 判据来源明示（**只**支持"做法种类"，**不**支持具体阈值）；`A` = 算术推导；
`P` = 项目自建（含一切**数值阈值**）。

### A1 声明忠实性与前置条件（2 轴）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 判定词 |
|---|---|---|---|---|---|---|
| **A1a** | V01 | `n=10`；d1 = `S01..S10`（rankNo 1..10）；d2 = `S01..S09` + `S11` ⟹ `|I|=9` | `daily[0].turnover`、`avg/max/min_turnover`；对照 `control_declared_full(10,9)=0.1`；另用 3 日全满员面板测 `avg/max/min` 的对照聚合（对 `round4` 后的逐日值重算） | 全部 |生产−对照| `≤ 1e-12` ⟹ `NOT_VIOLATED`；否则 `VIOLATED` | L+A | **NOT_VIOLATED** |
| **A1b** | V02 | `BacktestTurnoverService.java` 文本的 `analyze` 方法体（`method_range`，只认声明行） | `declared_precondition_lines` = javadoc 中含「两日均有」的行；`guard_lines` = 方法体内把**实际候选数**与 `n` 比较的行（正则：`\.size\(\)\s*[<>=!]` 命中且同行情含 `n`，**或**键名 `prev_size|curr_size|candidate_count|actual_size`） | `declared_precondition_lines ≠ ∅ 且 guard_lines = ∅` ⟹ **`CONTRACT_MISLABEL`**；两者同为空或同非空 ⟹ `NOT_VIOLATED` | L（判据乙）+P | **CONTRACT_MISLABEL** |

### A2 定义不适用时仍输出像测量值的实数（4 轴，含 K1/K2）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 判定词 |
|---|---|---|---|---|---|---|
| **A2a** | V03 | `n=10`；d1 = `{A,B,C}`（rankNo 1..3）；d2 = `{A,B,C}` ⟹ `|prev|=|curr|=3`、`|I|=3`、`new=exit=0` | 生产 `turnover`（预期 `1−3/10 = 0.7`）；对照 `control_oneway_defined(3,3,3) = 0.5*((3−3)/3+(3−3)/3) = 0.0`；`new_count`/`exit_count` | `reported > 0 且 new_count = 0 且 exit_count = 0 且 对照 = 0.0` ⟹ **`VIOLATED`**；否则 `NOT_VIOLATED` | L（判据甲）+A | **VIOLATED** |
| **A2b** | V04 | `n=10`；d1 = `S01..S10`（满员）；d2 = `S01,S02,S03`（3 条）⟹ `p=10,c=3,|I|=3` | 生产 `turnover`（预期 `1−3/10=0.7`）；对照 `control_oneway_defined(10,3,3) = 0.5*((10−3)/10+(3−3)/3) = 0.35`；`abs_diff` | `abs_diff ≥ TH_MAT`（`TH_MAT = 0.10`，**P 档**）⟹ **`VIOLATED`**；否则 `REGISTERED_ONLY` | L+A；阈值 P | **VIOLATED** |
| **A2c** | V06 | `signal_days = 1` 面板（单日）；以及 `signal_days = 0`（空）面板 | `avg/max/min_turnover`、`daily_turnover`、退化分支 `note`、**响应键集**、`signal_days` | 若存在机器可读的「不可用」标记键（`status`/`available`/`basis`）⟹ `NOT_VIOLATED`；若无该键**但** `signal_days < 2` 与空 `daily_turnover` 联合可推出「0.0 是占位」⟹ **`REGISTERED_ONLY`**；若连上述字段也不足以推出 ⟹ `CONTRACT_AMBIGUOUS` | L（判据丙+乙）+P | **REGISTERED_ONLY** |
| **A2d** | V05 | 参数敏感性网格（**协议要求的 ±20% 敏感性披露**）：双侧短员 `(n,p) ∈ {8,10,12} × {2,3,4}` 共 9 格；单侧短员 `n ∈ {8,10,12}, c ∈ {2,3,4}, |I| = c` 共 9 格 ⟹ 18 格 | 每格 `reported` 与 `defined`；`err = |reported − defined|` | 存在格 `err ≥ TH_MAT` ⟹ **`VIOLATED`**；否则 `REGISTERED_ONLY` | L+A；阈值 P | **VIOLATED** |

### A3 序列构造：被配对的到底是谁（3 轴，含 K3）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 判定词 |
|---|---|---|---|---|---|---|
| **A3a** | V07 | `strategy = null`（控制器 `required=false`，即**默认**路径）；面板含**两个策略** `P1/P2` × **两日** `d1/d2` 共 4 条 `SelectorResult`，代理**按** `[P1@d1, P2@d1, P1@d2, P2@d2]` 返回；各策略自身两日**集合完全相同**（`P1`: `SA01..SA10` 两日相同；`P2`: `SB01..SB10` 两日相同），且 `P1` 与 `P2` 的证券**完全不交** | `signal_days`（预期 **4**）、窗口内**不同**日期数（**2**）、`daily_turnover` 长度（预期 **3**）、各 pair 的 `trade_date`、`avg_turnover`（预期 **1.0**）；对照 `control_within_strategy = 0.0` | `signal_days > 窗口内不同日期数` **且** 存在相邻 pair 的 `trade_date` **相同**（同日不同策略被当作「相邻日」）**且** `avg_turnover ≠ control_within_strategy` ⟹ **`VIOLATED`**；否则 `NOT_VIOLATED` | L（判据甲：被配对者须是**同一组合的相邻持有**）+A | **VIOLATED** |
| **A3b** | V08 | 同一面板，分别以 `strategy = null` 与 `strategy = ""` 调用 | 两次的 `daily_turnover` 序列是否**逐位**相同；`out.strategy` 的两次取值 | 两次计算逐位相同**且** `out.strategy` 不同 ⟹ **`CONTRACT_AMBIGUOUS`**；两次计算也不同 ⟹ `VIOLATED`；都相同 ⟹ `NOT_VIOLATED` | P | **CONTRACT_AMBIGUOUS** |
| **A3c** | V09 | 同 `(strategyType, tradeDate)` 两条 `SelectorResult`（`createdAt` 不同），代理**按 createdAt DESC** 顺序返回 | 被选中者（由 `turnover` 反推的 `resultId`）；**源码**中 `getCreatedAt()` 的比较命中数 | 源码**不含** `createdAt` 比较（"取最新"完全依赖查询序，代码不自证）且本机**无法**构造 `createdAt` 并列（无 DB）⟹ **`NOT_REACHABLE`**；若源码含比较 ⟹ `NOT_VIOLATED` | P | **NOT_REACHABLE** |

### A4 载荷内部恒等式与分母反解（3 轴）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 判定词 |
|---|---|---|---|---|---|---|
| **A4a** | V10 | 3 日**全满员**面板（每日 10 条，rankNo 1..10），设计使每个 pair 的 `|I| ∈ {7,8,9}` ⟹ 逐日 `turnover ∈ {0.1,0.2,0.3}`（`round4` 下无精度损失） | 逐 pair：`lhs = turnover × n`、`rhs = (new_count + exit_count) / 2`、`gap = |lhs − rhs|` | 全部 pair `gap ≤ 1e-12` ⟹ **`NOT_VIOLATED`**；否则 `VIOLATED` | A（纯算术：满员等权下单向换手率 `= (new+exit)/(2n)`） | **NOT_VIOLATED** |
| **A4b** | V11 | 同 A2a 面板（`n=10`，两日均为 `{A,B,C}`） | `lhs = turnover × n`（预期 **7.0**）、`rhs = 0.0`、`gap`；**daily 行的键集** | `gap > 1e-12` **且** 键集**不含** `prev_size`/`curr_size`/`candidate_count`/`prev_n`/`curr_n` 中任一 ⟹ **`VIOLATED`**；若含 size 字段 ⟹ `REGISTERED_ONLY` | A+L（判据乙：读者须能察觉） | **VIOLATED** |
| **A4c** | V12 | `n` 固定的反解面板：`|prev| = |curr| = k = 4`，`|I| = 1` ⟹ 生产 `turnover = 0.75` ⟹ 反解 `n_recovered = 4/(1−0.75) = 16`；另测 `topN ∈ {null, 0, -5, 3, 10}` 的 `top_n` 回显 | `n_recovered` vs `out.top_n`（`null/0/-5` → 均回显 **10**；`3` → 3；`10` → 10） | 全部 `n_recovered == out.top_n` ⟹ **`NOT_VIOLATED`**（分母被载荷透明回显）；否则 ⟹ `VIOLATED` | A+P | **NOT_VIOLATED** |

### A5 可重建性（判据乙的核心）（3 轴）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 判定词 |
|---|---|---|---|---|---|---|
| **A5a** | V13 | 任意面板的 `daily_turnover[0]` **键集** | 重建分母所需的字段集 `{prev_size, curr_size}`（或等价「候选数」字段）是否全部存在 | 全部存在 ⟹ `NOT_VIOLATED`；否则 **`REGISTERED_ONLY`**（按判据乙只能记 `P` 档而非 `Y` 档：「mention 但 implementation incomplete」） | L（判据乙逐字） | **REGISTERED_ONLY** |
| **A5b** | V14 | 一次正常响应的**顶层键集**；`note` 文本内容 | 是否存在**结构化**口径字段（键名匹配 `basis|definition|metric_scope|convention|one_way|round_trip|口径`） | 存在 ⟹ `NOT_VIOLATED`；仅有人类自由文本 `note` 而无结构化字段 ⟹ **`REGISTERED_ONLY`** | L | **REGISTERED_ONLY** |
| **A5c** | V15 | 文本语料：`RollingExperimentService.java:473-474`（`turnover="UNAVAILABLE"` + 「换手需组合执行口径（MP-020/021 回测与模拟账户）才可评估，本研究不产生持仓序列」）**与** `BacktestTurnoverService` 的 `note`（`:127`：无持仓序列即给出换手率数值）同时存在 | 两处文本是否都在；两处对「无持仓序列时换手率可否评估」的结论是否相反 | 两处都在**且**结论相反 ⟹ **`CONVENTION_DIVERGENCE`**；仅存在一处或结论一致 ⟹ `NOT_VIOLATED` | L（判据乙：口径须声明到可判断）+P | **CONVENTION_DIVERGENCE** |

### A6 同名声明的歧义（2 轴）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 判定词 |
|---|---|---|---|---|---|---|
| **A6a** | V16 | 文本语料（4 个语义）：(i) `DataService.java` 的 SQL `AVG(COALESCE(m.turnover_rate,0)) AS avg_turnover`（**市场换手率**，单位 %）；(ii) `TurnoverResponse.avg_turnover`（**候选替换率**，单位**分数**，前端 ×100）；(iii) `CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER = "turnover_amount"` + 「amount/turnover 推算值应标 FLOAT_CAP_APPROX」（**市值推算来源**）；(iv) `RollingExperimentService` 的 `"UNAVAILABLE"`；另记 `[Tt]urnover` 在 `modules/**.java` 的**总命中数**（冻结期望 **114**，25 个文件） | 语义不同者计数；同名 `avg_turnover` 的**单位**是否一致 | ≥2 个语义共存**且**同名 `avg_turnover` 单位不同（% vs 分数）**且**均存在于生产面 ⟹ **`CONTRACT_AMBIGUOUS`**；否则 `NOT_VIOLATED` | P（判据来源不涉及命名） | **CONTRACT_AMBIGUOUS** |
| **A6b** | V17 | 文本语料（前端渲染）：`StockDetailView.vue:79`「换手率」`turnover_rate.toFixed(2)+'%'`；`ScoringBacktest.vue:484-486`「平均换手/最高/最低」`(avg_turnover*100).toFixed(2)+'%'`；`DataManagement.vue:293` `formatNumber(row.avg_turnover,2)+'%'`；另 `StockPicker.vue` 命中 1 | 含「换手」标签的渲染处数；各处底层量是否同一 | ≥2 处渲染含「换手」**且**底层量不同 ⟹ **`CONTRACT_AMBIGUOUS`**；否则 `NOT_VIOLATED` | P | **CONTRACT_AMBIGUOUS** |

### A7 量纲、值域与惯例声明（2 轴）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 判定词 |
|---|---|---|---|---|---|---|
| **A7a** | V18 | **全部 21 个变体**中被生产实现返回过的每一个 `turnover`/`avg|max|min_turnover` 值（含 `|I|=0 → 1.0`、`|I|=n → 0.0`、短员日的 `0.7/0.75/0.8/0.9`、以及异常面板） | 值域是否 ∈ `[0,1]`；是否出现 `NaN`/`±Inf`；`round4` 后是否仍在 `[0,1]`；`round4` 是否保序（单调） | 全部 ∈ `[0,1]` **且** 无 `NaN/±Inf` **且** 保序 ⟹ **`NOT_VIOLATED`**；否则 `VIOLATED`（R007 教训第 37 条：`round4` 的 `NaN→0.0`、`±Inf→饱和` 必须**实测**，不得凭「输入有界」推断） | A+P | **NOT_VIOLATED** |
| **A7b** | V19 | 响应顶层键集 + `note` 文本（两条分支）+ 前端三个标签文本；检索 `one-way|two-way|单向|双向|round-trip|双边|换手方向的归一约定` | 是否存在对**单向/双向**的声明 | 无命中 ⟹ **`REGISTERED_ONLY`**（判据来源**亦未给**该约定 ⟹ 只登记，**不**判死）；有命中 ⟹ `NOT_VIOLATED` | L（判据来源无约定）+P | **REGISTERED_ONLY** |

### A8 确定性（2 轴，含 K10 门）

| 轴 | 变体 | 冻结输入 | 测量 | 判定规则（冻结） | 档 | 判定词 |
|---|---|---|---|---|---|---|
| **A8a** | V20 | 同一面板、同一返回顺序、**新建两个独立代理实例**，各调用 `analyze` 一次 | 两次的全部键与值是否**逐位**相同 | 逐位相同 ⟹ **`NOT_VIOLATED`**；否则 `VIOLATED` ⟹ K10 ⟹ 整轮 `INCONCLUSIVE` | P | **NOT_VIOLATED** |
| **A8b** | V21 | `n=10`；d2 的 `rankNo` 为 `[1..9, 10, 10, 10]`（三只并列第 10，证券为 `X,Y,Z`），代理**按 `(X,Y,Z)`** 与**按 `(Z,Y,X)`** 两种顺序各返回一次（模拟 `ORDER BY rankNo` **不钉住**并列相对序） | 两种顺序下的 `turnover`；`changed` 标志 | `changed` ⟹ **`VIOLATED`**；否则 `NOT_VIOLATED`。**可达性不确定**（`ProgressiveResultWriter.replaceResultItems:72` 赋 `rank=1..N` **唯一**；其它插入路径 `OversaveReboundScoringService` / `UnifiedScoringService` / `saveItemIfAbsent` 是否会产生并列**本轮不判定**）⟹ 若 `changed`，报告须**同时**登记「可达性未定」并进决策队列 | P | **VIOLATED**（若 changed；否则 `NOT_VIOLATED`） |

**轴数合计（冻结）**：A1=2，A2=4，A3=3，A4=3，A5=3，A6=2，A7=2，A8=2 ⟹ **21**。

---

## 5. 阈值与常数（冻结，运行期不得修改）

| 名 | 冻结值 | 档 | 说明 |
|---|---|---|---|
| `TOL_BIT` | `1e-12` | A | 逐位一致容差 |
| `TH_MAT` | `0.10` | **P** | 材料性阈值（绝对偏差）。**在自设量级线下实测为 0.35**（A2b 单侧短员：生产 0.7 vs 定义 0.35），以及 **0.70**（A2a：生产 0.7 vs 定义 0.0）⟹ 阈值远低于实测，非卡边界 |
| `N_GRID` | `{8, 10, 12}` | P | `topN` 的 ±20% 敏感性（10 ± 2） |
| `P_GRID` | `{2, 3, 4}` | P | 短员日候选数的敏感性（3 ± 1） |
| `RANGE_LO/HI` | `0.0 / 1.0` | A | 换手率值域 |
| `EXISTING_TEST_TOL` | `1e-12` | A | 与既有测试 `isEqualTo(0.3333)` 的一致性门（既有测试为精确相等，本容差更松） |
| `CONTROL_ONE_WAY_FACTOR` | `0.5` | A | 判据来源 Eq.(3) 的 `Σ|Δw|`（双向）折算为**单向**的系数；**仅**用于构造对照，**不**用于判定「单双向孰对」 |

### 5.1 关键事实（预注册即冻结的技术前提，运行时逐条实测复核）

| # | 事实 | 位置 |
|---|---|---|
| F1 | 分母是**请求参数** `n`（`topN` 钳位后），**不是**当日实际候选数 | `:103`、`:42` |
| F2 | 每日候选数**不写入载荷** | `:111-116` 的 4 键 |
| F3 | `.limit(n)` 的前 n 名依赖 `rankNo` 排序；`rankNo` **无唯一约束**（`SelectorResultItem` 仅有普通索引 `idx_..._result_rank`） | `:85-91`、实体 `:14-29` |
| F4 | 序列 `daily` 按 `tradeDate` 单键排序，键含 `strategyType` ⟹ `strategy=null` 时序列**混合多个策略** | `:52-57` |
| F5 | `strategy` 过滤条件是 `strategy != null && !strategy.isBlank()`，而回显是 `strategy != null ? strategy : "ALL"` ⟹ 两处条件**不同** | `:46`、`:60` |
| F6 | 退化分支（`signal_days < 2`）输出 `avg/max/min = 0.0` + 空数组 + 无 `status` 键 | `:66-73` |
| F7 | `round4(v) = Math.round(v*10000.0)/10000.0`（`NaN→0.0`，`±Inf→±9.223372036854776E14`） | `:131-133` |
| F8 | 载荷**无**任何结构化口径字段（无 `basis`/`definition`/`metric_scope`） | `:59-128`，实测 0 命中 |
| F9 | `SelectorResultItem` 对 `(resultId, stockCode, exchangeType)` 有**唯一约束** ⟹ 同一 `resultId` 内集合大小 = 行数（去重不构成额外风险） | 实体 `uk_selector_result_item_result_stock` |
| F10 | `rankNo` 为 `nullable = false` ⟹ 实现中 `rankNo != null ? ... : MAX_VALUE` 是**未被数据库数据触发的防御分支** | `:86-88` vs 实体 |

### 5.2 前视 / 幸存者偏差影响（协议 §5 强制披露）

* 本轮**不产生任何收益序列、不做任何选样、不计算任何 IC/收益** ⟹ 前视偏差与幸存者偏差对本轮的测量**不适用**。
* 但被审计对象**自身**在生产 `note` 中声明「存在幸存者偏差（沿用打分回测口径）」——这是**被审计对象的声明**，
  本轮**只登记其存在**（进 A5b/A7b 的登记组），**不**评估其是否充分（那是另一族载荷的审计）。

### 5.3 变体预算与每轴变体数

**每轴恰 1 个变体，共 21 个（V01–V21）；冻结上限 21，不得超支。**
变体 = 「一组冻结输入 + 一次生产实现调用」；同一面板可复用于多个轴（复用时仍各自计数，见 A4b 复用 A2a 面板）。

### 5.4 区间下界类判据：本轮**不适用**的理由（逐条，承接 R007 教训第 40 条）

R007 的冻结条款要求「覆盖率/命中率类断言必须用区间下界（Clopper–Pearson）且等价变形写进预注册」。
**本轮没有任何抽样统计量**，故该条款**不被触发**。逐条理由：

1. 本轮**全部输入为构造面板**（确定性），**零** Monte Carlo、**零**随机种子、**零**重复抽样；
2. 因此每个测量都是**单点确定性值**，不存在「覆盖率」这一概念；
3. 本轮唯一的「计数类」量（A6a 的 `[Tt]urnover` 命中数、A8a 的两次调用一致性）都是**穷举枚举**，
   不是从总体中抽样，**不构成**需要区间下界的情形；
4. **真实的覆盖率类问题确实存在，但本机不可测**：短员日（`|prev|<topN` 或 `|curr|<topN`）在**真实数据**中的
   占比，决定了 A2a/A2b/A2d 的**实际暴露面**。本机**无 DB**（本轮 0 条 SQL）⟹ **不可测** ⟹
   按协议「遇到无法继续的分支：记录问题和证据 → 标记 BLOCKED → 写入待决策队列」，
   登记为 **`B-R008-01`**（见报告 §9），**不**以任何估计值替代，**不**因此停止循环。
5. **继承条款（对后续轮次有效）**：若未来任一轴引入抽样，必须回到**区间下界**形式并写出
   等价变形（如 R007 的 `L < 0.85 ⟺ P(X ≥ k | p = 0.85) > 0.01`），且等价变形**必须先于**测量写入预注册。

---

## 6. 杀死条件（冻结 K 表）与总判定合成规则

### 6.1 K 表（按 id 升序，逐字冻结；运行期只计算布尔值，不得改写条件文本）

| id | 触发条件（冻结） | 结果 |
|---|---|---|
| **K1** | `A2a == VIOLATED`（两侧短员且集合完全相同，载荷报 `0.7` 而定义值 `0.0`） | `REJECTED` |
| **K2** | `A2b == VIOLATED`（单侧短员，与定义值绝对偏差 `0.35 ≥ TH_MAT = 0.10`） | `REJECTED` |
| **K3** | `A3a == VIOLATED`（`strategy=null` 时把**同日不同策略**当作「相邻日」配对，产出无定义的量） | `REJECTED` |
| **K4** | `A1b == CONTRACT_MISLABEL`（声明了「两日均有 topN 个候选」这一前置条件，而代码无任何 guard） | `CONTRACT_MISLABEL` |
| **K5** | `A6a == CONTRACT_AMBIGUOUS` 或 `A6b == CONTRACT_AMBIGUOUS`（同名/同标签指代不同量） | `CONTRACT_AMBIGUOUS` |
| **K6** | `A3b == CONTRACT_AMBIGUOUS` 或 `A2c == CONTRACT_AMBIGUOUS` | `CONTRACT_AMBIGUOUS` |
| **K7** | `A2c / A4b / A5a / A5b / A5c / A7b / A8b` 中任一判定词 `∉ {NOT_VIOLATED}` | `REGISTERED_ONLY` |
| **K8** | 行和交叉校验失败（§3.5：轴数/变体映射/`config.json` 冻结比对任一不等） | `INCONCLUSIVE` |
| **K9** | 阳性对照未通过（§3.6 三件套任一失败：G3 门 / 反向锚点 / 21 对金丝雀 / 与既有测试一致性 / 出现表外判定词） | `INCONCLUSIVE` |
| **K10** | `A8a == VIOLATED`（同一输入同一顺序两次调用不一致） | `INCONCLUSIVE` |

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
**强制措辞规则**：`H0 存活` **不得**被叙述为「项目没问题」；`H0 被杀` **不得**被叙述为「项目不可用」。
报告必须**同时**给出：`h0_alive`、`total_verdict`、以及 K4–K7 的**布尔真值**（不得只写「未触发」）。

---

## 7. 计数断言 C1–C9（冻结期望；**不匹配只记报告附录 A，不改变任何判定词**）

命令口径：Python 内部 walker（**斜杠归一化**路径，排除 `target`/`dist`/`node_modules` 等），
与 R007 完全一致；`raw` 列 = 不排除 `target`/`dist` 的原始口径。
**冻结期望在预注册时由只读扫描取得**（扫描先于任何实验代码，且本轮**不**据计数改变任何判定）。

| id | 冻结命令（语义） | 冻结期望（src） | 冻结期望（raw） | by_file 期望 |
|---|---|---|---|---|
| **C1** | `avg_turnover` 于 `modules/**`（.java/.vue/.ts/.js/.sql/.py） | **13** | **17** | BTS 2 / DataService 4 / BTS-Test 3 / backtest.ts 1 / data.ts 1 / ScoringBacktest.vue 1 / DataManagement.vue 1 |
| **C2** | `daily_turnover` 于 `modules/**` | **6** | **8** | BTS 2 / BTS-Test 2 / backtest.ts 1 / ScoringBacktest.vue 1 |
| **C3** | `换手` 于 `modules/web/src/**` | **8** | **8** | ScoringBacktest.vue 4 / DataManagement.vue 2 / StockDetailView.vue 1 / StockPicker.vue 1 |
| **C4** | `BacktestTurnoverService` 于 `modules/**` | **6** | **6** | Controller 2 / BTS 1 / BTS-Test 3 |
| **C5** | `put("note"` 于 `BacktestTurnoverService.java` | **2** | **2** | 单文件 2（`:71`、`:127`） |
| **C6** | `turnover_rate` 于 `modules/**` | **21** | **26** | 见报告 §10 明细 |
| **C7** | `backtest/turnover` 于 `modules/**` | **2** | **4** | Controller 1 / backtest.ts 1（raw 多 2 = `target/classes/static` 与 `web/dist` 构建产物） |
| **C8** | `"turnover"` 于 `RollingExperimentService.java` | **1** | **1** | 单文件 1（`:473`）；`turnover_note` 另计 1 |
| **C9** | `turnover` 于 `**/*.sql` | **6** | **11** | V1 1 / V2 3 / V7 1 / `modules/database/01_init_schema.sql` 1 |

**附加冻结计数（供 A6a/A6b 引用，同为只读扫描取得）**：

| id | 命令（语义） | 冻结期望 |
|---|---|---|
| **C10** | `[Tt]urnover` 于 `modules/**/*.java` | **114**（**25** 个文件） |
| **C11** | `[Tt]urnover` 于 `modules/web/src/**`（.vue/.ts） | **9** |

> C10/C11 的**文件级明细**在报告 §10 全列（不得只写总数）。

---

## 8. 交付物清单（冻结）

| 交付物 | 路径 | 说明 |
|---|---|---|
| 论文审查 | `rounds/R008_review.md` | 已完成（**先于本文件**） |
| **本预注册** | `rounds/R008_prereg.md` | 冻结于 `7f493011` |
| 论文载体 | `papers/yao2026_execution_assumptions.{pdf,txt}` | sha256 见 review §1 |
| Java 探针 | `experiments/R008_turnover_audit/probe/R008Probe.java` | 编译进 `com.quant.service.backtest`；不重实现生产量 |
| 冻结配置 | `experiments/R008_turnover_audit/config.json` | 由 `make_config.py` 生成，运行期逐项比对上表 |
| 编排器 | `experiments/R008_turnover_audit/run_turnover_audit.py` | 只读；含 21 对金丝雀 + K8/K9/K10 门 |
| 运行产物 | `run.log`（append-only）/ `result.json` | `probe/out/*` 运行后删除 |
| 报告 | `rounds/R008_report.md` | 逐轴判定 + 量级表 + 负面清单 + 附录 A 勘误 |

**运行次数**：探针+编排器允许因**自身缺陷**多次运行；每次运行都在 `run.log` 留痕，
报告 §12 必须披露**实际运行次数**与每次的失败原因（R007 同型，不得只报成功那一次）。

---

## 9. 红线重申（协议 §七/§八/§十六）

* 本轮属**只读审计**：不启用真实自动交易、不向真实账户下单、不读取/生成真实凭证、
  不把任何真实交易接口改成默认启用、不删除历史数据、不做不可逆迁移。
* 不修改公开 API / 前端契约 / 数据库结构（本轮**只**观察它们）。
* 若本轮发现的问题属于 §7 record-only 档（改口径/改权重/改契约/改结构/影子→正式），
  **一律只登记 `decision_queue.md`，由用户决定**；agent **不得**自行修改。
* **零影子因子、零提升**（`shadow_registry.md` 本轮追加空轮记录）。

---

## 10. 冻结声明

本文件在基线 `7f493011` 上、于 **2026-09-14 21:41 (+0800)** 冻结。
自此刻起，`experiments/R008_turnover_audit/**` 的任何代码**只是**本文件的可执行实现；
实现的任何偏差**只能**记入 `rounds/R008_report.md` 附录 A（勘误），**不得**回写本文件。
