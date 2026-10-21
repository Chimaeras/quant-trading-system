# R008 报告：换手率载荷族审计（`BacktestTurnoverService.analyze` 的 `avg_turnover` / `max_turnover` / `min_turnover` / `daily_turnover[]`）

- **预注册**：`rounds/R008_prereg.md`（冻结于 `7f493011`，**先于**任何实验代码）｜ 论文审查：`rounds/R008_review.md`
- **被审计对象**：项目**自己**的契约主张（H0 = 「换手率载荷族忠实对应其声明定义，且定义不适用时不输出像测量值的实数」）
- **本轮性质**：**只读审计**（与 R004/R005/R006/R007 同型）。论文（Yao–Zheng, arXiv:2606.08285v1）**只作判据来源**，其结论**一条未采纳**
- **总判定**：`REJECTED` ｜ `h0_alive = false` ｜ `K1/K2/K3` 同时触发（三条**互相独立**的杀点）
- **金丝雀**：21/21 对 PASS（CLN ≠ INJ 且与运行前写下的期望一致）；`K8 = K9 = K10 = false`
- **生产/测试代码改动**：**零**（Java 350 / Python 343 与基线逐项一致）
- **运行次数**：`run_turnover_audit.py` 共 **5 次**（2 次 harness 自身缺陷失败、2 次面板/仪器缺陷、1 次完整）

> **一句话**：换手率载荷在**满员日**是忠实且自洽的（A1a 偏差 ~1e-17、A4a 恒等式逐位成立、A4c 分母对读者透明），
> 但在**短员日**（当日候选数 < `topN`）它输出的数值**没有任何定义**（分母是请求参数 `n`，不是当日实际持仓），
> 且 `strategy=null` 的**默认**路径会把同一天的不同策略当成「相邻日」配对。三条杀点全部落在**测量结果**上，不是读码推断。

---

## §1 本轮怎么做的

### 1.1 判据来源（只借判据，不采纳其结论）

论文提供三条可引用判据（逐字摘录见 `rounds/R008_review.md` §2）：

- **判据甲（L 档）**：换手率定义在**组合权重**上，分母是**实际持仓**（原文 L1222–1228，Eq.(3) `Σ|Δw|`）。
- **判据乙（L 档）**：「无持仓序列即无法评估换手」——口径必须**声明到可判断**的程度（原文 §执行假设清单）。
- **判据丙（L 档）**：定义**不适用**时不得输出像测量值的实数（原文对 `UNAVAILABLE` 的用法）。

论文本身是**叙述性综述 + 30 篇文献编码 + 10 只股票示例**，无 A 股实证、无 DOI、无期刊版 ⟹
本轮**不存在** R005/R007 那类版本落差，也**没有**把它的任何数字当本项目门槛。

### 1.2 判据的三档强度（L / A / P）

`L` = 判据来源明示（**只**支持「做法种类」，**不**支持具体阈值）；`A` = 算术推导；`P` = 项目自建（含一切数值阈值）。
本轮唯一阈值 `TH_MAT = 0.10` 是 **P 档**；实测偏差为 **0.35 / 0.70–0.83**（见 §2.2）⟹ 阈值**远低于**实测，非卡边界判定。

### 1.3 执行方式（防掩码）

- 探针 `probe/R008Probe.java` 编进 `com.quant.service.backtest`，**调用生产实现** `BacktestTurnoverService.analyze`；
  两个 Spring Data 仓库用 JDK `java.lang.reflect.Proxy` **伪造**，未预期方法一律
  `throw new UnsupportedOperationException`（防「测了个不是生产路径的东西」）；**零 SQL、零 DB**。
- **G3 门实测**：`engine_code_source = file:/E:/Project/quant-trading-system/modules/backend-java/target/classes/`、
  `engine_class = com.quant.service.BacktestTurnoverService` ⟹ 断言对象确为仓库自己的类。
- **反向锚点**：A2a 面板 生产 `0.7` vs 定义 `0.0` ⟹ `sentinel_reverse_differs = true`（**必须不同**）。
- **正向锚点**：满员 `|I|=9` 面板 生产 `0.1` vs 对照 `0.09999999999999998` ⟹ `sentinel_full_member_equals = true`（**必须逐位相同**）。
- **与既有测试一致性门**：同输入驱动生产实现得 `avg_turnover = 0.3333`，与测试字面 `isEqualTo(0.3333)`
  绝对差 `0.0 ≤ EXISTING_TEST_TOL = 1e-12` ⟹ PASS（**不重算** `1−2/3`，避免自证；编排器另抓测试文件里的 `0.3333` 字面行双重确认）。
- **对照值不充当被审计量**：`control_declared_full(n,k) = 1 − k/n`、`control_oneway_defined(p,c,k) = 0.5·((p−k)/p + (c−k)/c)`
  一律**与被审计量同输入并列打印**；A4a/A4b 的 gap 由**编排侧从生产载荷字段重算**，探针自报 gap 仅作交叉核对。
- **harness 依赖钉死**：被审计类初始化需要 `slf4j`（`@Slf4j`），注入的仓库接口需要 Spring Data，实体需要 `jakarta.persistence`。
  按 `modules/backend-java/pom.xml` 的 `spring-boot-starter-parent 3.2.0` **钉死 9 个 jar**
  （slf4j-api 2.0.9 / spring-core·beans·context·tx·aop 6.1.1 / spring-data-commons·jpa 3.2.0 / jakarta.persistence-api 3.1.0）；
  缺任一即显式失败，**不得**静默换版本。这些 jar **不参与任何被断言的量**。

### 1.4 与 R007 的 harness 差异（诚实披露，另见附录 A）

| # | 差异 | 原因 |
|---|---|---|
| 1 | 首次需要**第三方依赖**上 classpath（R007 探针不需要） | 被审计类有 `@Slf4j` 与 Spring Data 注入（附录 A-01） |
| 2 | 用 **JDK 动态代理**伪造仓库，而非纯构造对象 | 必须走生产 `analyze` 的查询调用路径 |
| 3 | 首次运行**未**通过；`run_turnover_audit.py` 共 **5 次**运行 | 见 §12（其中 2 次失败、2 次缺陷修正） |
| 4 | 新增 `panel_design_selfcheck`（A1a/A4a 的面板是否真实现了冻结的 `|I|` 集合） | 附录 A-07：金丝雀**不能**发现「面板设计错」，须单独自检 |

---

## §2 逐轴结果（21 断言，8 轴）

冻结判据：见 `rounds/R008_prereg.md` §4。**判定词表 8 词**，表外词 ⟹ `K9`（本轮 `0` 个表外词）。

| 轴 | 变体 | 判定词（实测） | 预注册预期 | 承重证据（实测值） |
|---|---|---|---|---|
| **A1a** | V01 | `NOT_VIOLATED` | `NOT_VIOLATED` ✅ | 3 日满员面板 pair `|I|` = [9, 8] ⟹ 生产 `0.1 / 0.2`，对照 `0.09999999999999998 / 0.2`；逐对最大偏差 **5.6e-17**；`avg/max/min` 对照聚合偏差 ≤ **2.8e-17**；冻结 2 日输入 `|I|=9` 的 panelB 偏差 **2.8e-17** |
| **A1b** | V02 | `CONTRACT_MISLABEL` | `CONTRACT_MISLABEL` ✅ | javadoc `:28` 逐字声明「（两日均有 topN 个候选时）」；`analyze:40–128` 方法体内**零**守卫行、**零** size 字段 ⟹ 声明了未被强制的前置条件 |
| **A2a** | V03 | `VIOLATED` | `VIOLATED` ✅ | 两日均为 `{A,B,C}` ⟹ `new = exit = 0`（自认无替换），载荷仍报 **0.7**；定义值 `control_oneway_defined(3,3,3) = 0.0`；偏差 **0.70** |
| **A2b** | V04 | `VIOLATED` | `VIOLATED` ✅ | 单侧短员 `p=10, c=3, |I|=3`：载荷 **0.7** vs 定义 **0.35**；偏差 **0.35 ≥ TH_MAT 0.10** |
| **A2c** | V06 | `REGISTERED_ONLY` | `REGISTERED_ONLY` ✅ | `signal_days = 1` 与 `0` 两个退化面板：`avg/max/min = 0.0` + 空 `daily_turnover` + 人类可读 `note`；**无**任何机器可读不可用键（`status`/`available`/`basis` 命中 0）；但 `signal_days < 2` 与空数组合起来可推出「0.0 是占位」 |
| **A2d** | V05 | `VIOLATED` | `VIOLATED` ✅ | **±20% 敏感性网格 18/18 格越线**；`err ∈ [0.25, 0.8333]`（双侧 = `1 − p/n`：0.5–0.8333；单侧 = `(n−c)/(2n)`：0.25–0.4167） |
| **A3a** | V07 | `VIOLATED` | `VIOLATED` ✅ | `strategy=null`（控制器 `required=false` 默认路径）+ 2 策略 × 2 日：`signal_days = 4`、窗口内不同日期 **2**、`daily_turnover` 长度 **3**、相邻 pair 日期 `2026-05-01, 2026-05-06, 2026-05-06`（**同日不同策略被配对**）、`avg_turnover = 1.0` vs 同策略相邻日对照 **0.0** |
| **A3b** | V08 | `CONTRACT_AMBIGUOUS` | `CONTRACT_AMBIGUOUS` ✅ | `strategy = null` vs `""` 的两次计算**逐位相同**（都走混合路径），但回显分别为 `"ALL"` 与 `""` ⟹ 读者无法从载荷判断两者是否同一口径 |
| **A3c** | V09 | `NOT_REACHABLE` | `NOT_REACHABLE` ✅ | 代理按 `ORDER BY createdAt DESC` 返回，选中 `R_Y`；源码**零** `createdAt` 比较（"取最新"完全依赖查询方法名）⟹ 载荷不自证；并列 `createdAt` 的取舍**本机无 DB 不可构造** |
| **A4a** | V10 | `NOT_VIOLATED` | `NOT_VIOLATED` ✅ | 满员面板 `|I|` = [9, 8, 7] ⟹ `turnover` = [0.1, 0.2, 0.3]（`round4` 无精度损失）；`turnover×n = (new+exit)/2` **三对 gap 全 0.0** |
| **A4b** | V11 | `VIOLATED` | `VIOLATED` ✅ | 同 A2a 面板：`lhs = 7.0`、`rhs = 0.0`、gap **7.0**；逐日行键集 `trade_date,turnover,new_count,exit_count` **不含**任何候选规模字段（`prev_size`/`curr_size`/`candidate_count`/`prev_n`/`curr_n` 命中 0） |
| **A4c** | V12 | `NOT_VIOLATED` | `NOT_VIOLATED` ✅ | `topN ∈ {null, 0, −5}` 被钳位到 10 并回显 `top_n = 10`，反解 `n_recovered = 10.000000000000002`（偏差 **1.8e-15**）；`topN = 10` 回显 10、`topN = 4` 回显 4（偏差 0.0）⟹ 分母对读者**透明** |
| **A5a** | V13 | `REGISTERED_ONLY` | `REGISTERED_ONLY` ✅ | 逐日行缺 `prev_size`/`curr_size`（缺失键实测 `prev_size,curr_size`）⟹ 读者只能**反解**分母，不能**直接看到**两日实际规模 |
| **A5b** | V14 | `REGISTERED_ONLY` | `REGISTERED_ONLY` ✅ | 顶层 10 键；结构化口径字段（`basis|definition|metric_scope|convention|one_way|round_trip|口径`）命中 **0**；只有 `note` 自由文本（58 字段，声明幸存者偏差） |
| **A5c** | V15 | `CONVENTION_DIVERGENCE` | `CONVENTION_DIVERGENCE` ✅ | `RollingExperimentService:473–474` 输出 `"UNAVAILABLE"` + 「换手需组合执行口径（MP-020/021）才可评估，本研究不产生持仓序列」；`BacktestTurnoverService` 在**同样没有持仓序列**的前提下输出数值（`:127`）⟹ 项目内两处惯例结论**相反** |
| **A6a** | V16 | `CONTRACT_AMBIGUOUS` | `CONTRACT_AMBIGUOUS` ✅ | 4 个语义共存：`DataService` 的 SQL `AVG(COALESCE(m.turnover_rate,0)) AS avg_turnover`（**市场**换手率，%）／`TurnoverResponse.avg_turnover`（**候选替换率**，分数）／`CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER`（市值推算来源）／`RollingExperimentService` 的 `UNAVAILABLE`；同名 `avg_turnover` 单位不同且均在**生产面** |
| **A6b** | V17 | `CONTRACT_AMBIGUOUS` | `CONTRACT_AMBIGUOUS` ✅ | 前端同一中文标签指代两种量：`StockDetailView:79`「换手率」= 个股 `turnover_rate`（%）／`ScoringBacktest:484–486`「平均换手」= 候选替换率 `avg_turnover × 100`（%）／`DataManagement:293`「换手率」= 市场换手率；`StockPicker` 命中 1 |
| **A7a** | V18 | `NOT_VIOLATED` | `NOT_VIOLATED` ✅ | 全部 21 变体返回过的 **46** 个 turnover 值：∈ [0,1]（min 0.0 / max 1.0），`NaN` **0** 个、`±Inf` **0** 个；`round4` 在 0..1 的 **10001** 点网格上**保序**（R007 教训 37：NaN→0.0 / ±Inf→饱和 是**实测项**） |
| **A7b** | V19 | `REGISTERED_ONLY` | `REGISTERED_ONLY` ✅ | 被审计面**无**任何对单向/双向归一约定的声明（同行情含 `turnover|换手` 的命中为 **0**）；判据来源（arXiv:2606.08285）**亦未给**该约定 ⟹ 按 prereg 只登记、不判死。载荷 `1 − |I|/n` 是**单向**，判据 Eq.(3) 的 `Σ|Δw|` 是**双向**，相差 2 倍 |
| **A8a** | V20 | `NOT_VIOLATED` | `NOT_VIOLATED` ✅ | 两个独立代理实例、各调一次：`identical = 1`（全部键与值逐位相同），`avg` 均 `0.0` ⟹ 无随机性、无时钟依赖 |
| **A8b** | V21 | `VIOLATED` | `VIOLATED`（若 changed） ✅ | `rankNo` 并列第 10 的三只（`X,Y,Z`）按 `(X,Y,Z)` 与 `(Z,Y,X)` 返回：`turnover` 从 **0.0** 变 **0.1**（`.limit(n)` 取到的成员随行序改变）⟹ 载荷取决于数据库**未钉住**的行序；**可达性未定**（见 §6、`D-R008-10`/`B-R008-02`） |

### 2.1 三条 K1/K2/K3 级断言的证据细节（本轮判定的承重部分）

1. **K1 = A2a（两侧短员、集合完全相同）**：面板 `n=10`，两日各 3 条且**集合相同**。
   生产载荷：`turnover = 0.7`、`new_count = 0`、`exit_count = 0`（载荷**自认**无替换）；
   定义值（判据甲：分母是实际持仓，等权） `0.5·((3−3)/3 + (3−3)/3) = 0.0`。
   **偏差 0.70，且方向是「凭空造出换手」**。根因代码：`:103` 的 `1.0 - intersection.size()/n`，`n` 是**请求参数**。
2. **K2 = A2b（单侧短员）**：`p=10`（满员）→ `c=3`，`|I|=3`。生产 `0.7` vs 定义 `0.35`，**偏差 0.35 ≫ TH_MAT 0.10**。
   ±20% 网格（`n∈{8,10,12} × p∈{2,3,4}`，18 格）**全部越线**，`err ∈ [0.25, 0.8333]` ⟹ 不是单点巧合。
3. **K3 = A3a（`strategy=null` 默认路径）**：4 条 `SelectorResult` = 2 策略 × 2 日。
   `signal_days = 4` 而窗口内只有 **2** 个日期；`daily_turnover` 长度 **3** = 策略数×日数 − 1；
   相邻 pair 的 `trade_date` 出现 `2026-05-06, 2026-05-06`（**同一天的两个不同策略被当成相邻日**）；
   各策略自身两日集合完全相同 ⟹ 严格同策略配对的换手率是 **0.0**，载荷报 **1.0**。
   控制器 `@RequestParam(required = false)` ⟹ 这是**默认**调用路径，不是构造出来的极端输入。

### 2.2 量级表（**描述性**，不得当作预测能力的证据）

| 量 | 实测 | 阈值/对照 | 说明 |
|---|---|---|---|
| 满员日声明口径偏差 | **≤ 5.6e-17** | `TOL_BIT = 1e-12` | 双精度量级，等价于逐位一致 |
| 短员日偏差（双侧，A2a） | **0.70** | `TH_MAT = 0.10` | 定义值 0.0，生产 0.7 |
| 短员日偏差（单侧，A2b） | **0.35** | `TH_MAT = 0.10` | 定义值 0.35，生产 0.7 |
| ±20% 网格偏差（A2d） | **0.25 – 0.8333**（18/18 越线） | `TH_MAT = 0.10` | 双侧格 = `1 − p/n`；单侧格 = `(n−c)/(2n)` |
| 满员恒等式 gap（A4a） | **0.0**（3/3 对） | `TOL_BIT = 1e-12` | `turnover×n = (new+exit)/2` |
| 短员恒等式 gap（A4b） | **7.0** | `TOL_BIT = 1e-12` | `lhs = 7.0`，`rhs = 0.0`（new/exit 都是 0） |
| 反解分母偏差（A4c） | **≤ 1.8e-15** | `TOL_BIT = 1e-12` | 分母被 `top_n` 透明回显 |
| `rankNo` 并列导致的变化（A8b） | **0.0 → 0.1** | — | 同输入、仅行序不同 |
| 值域 / NaN / ±Inf（A7a） | 46 值全 ∈ [0,1]，0 个 NaN，0 个 ±Inf | `RANGE_LO/HI = 0/1` | `round4` 在 10001 点网格上保序 |

---

## §3 总判定与 K 触发

**K 表布尔值（逐条，不省略）**：

| K | 条件（冻结） | 布尔 | 依据 |
|---|---|---|---|
| K1 | `A2a == VIOLATED` | **true** | 0.7 vs 0.0 |
| K2 | `A2b == VIOLATED` | **true** | 0.35 ≥ 0.10 |
| K3 | `A3a == VIOLATED` | **true** | 同日不同策略被配对 |
| K4 | `A1b == CONTRACT_MISLABEL` | **true** | 声明前置条件、零守卫 |
| K5 | `A6a ∨ A6b == CONTRACT_AMBIGUOUS` | **true** | 同名 `avg_turnover` 两种单位 |
| K6 | `A3b ∨ A2c == CONTRACT_AMBIGUOUS` | **true** | `A3b` 为 `CONTRACT_AMBIGUOUS` |
| K7 | `{A2c,A4b,A5a,A5b,A5c,A7b,A8b}` 任一 ≠ `NOT_VIOLATED` | **true** | 7/7 全部非 `NOT_VIOLATED` |
| K8 | 行和/映射/config 冻结比对失败 | **false** | 轴数 21 = 冻结 21；`by_axis` 逐项相等；`config.json` 逐项 `==` |
| K9 | 阳性对照未通过 | **false** | 21/21 金丝雀 PASS；G3/反向/正向/既有测试四门全过；表外判定词 0 |
| K10 | `A8a == VIOLATED` | **false** | 两次调用逐位相同 |

**按序短路（冻结）**：`K8∨K9∨K10` 未触发 ⟹ 不走 `INCONCLUSIVE`；`K1∨K2∨K3` 触发 ⟹
**`total = REJECTED`**、**`h0_alive = false`**（`¬K1∧¬K2∧¬K3` 被否）。

**强制措辞**：这是「H0 被杀」，**不是**「项目不可用」；`K4–K7` 为真只能说明「修法涉及契约/口径变更」，
按协议 §7 **一律只登记、不自行修改**（见 §9）。

---

## §4 预注册预测的检验（21 条，**全中，但不得包装成能力**）

预注册 §4 表格的「判定词」列即预测。实测 **21/21 与预测逐字一致**（含 `A7b = REGISTERED_ONLY`，见附录 A-06 的仪器修正）。

**这 21/21 不是「预测能力」的证据**：本轮是**读码 + 算术推导**型审计，判据在写预注册时已由源码结构确定
（例如 `:103` 的分母写法、`@RequestParam(required=false)`、`note` 无结构化字段都是**冻结时已读到的文本**）。
本轮的价值在于：把「读码推断」**换成生产实现上的实测**，并给出量级（0.35 / 0.70 / 0.8333 / gap 7.0）。
凡**不能**由读码确定的，本轮都实标为不可达或未定（`A3c = NOT_REACHABLE`、`A8b` 可达性未定、`B-R008-01`）。

**唯一一条被改写过的预测**是 `A7b`，且改写原因在**仪器**而不是判据（附录 A-06）：初版把
`ScoringBacktest.vue:67` 的「**双边**佣金、卖出税费和**双边**滑点」误命中成单双向口径声明。
按冻结文本，被测量的量是「是否存在对**换手率单向/双向**的声明」，故改为**命中行须同行含 `turnover|换手`** ⟹ 命中 0 ⟹ `REGISTERED_ONLY`。
冻结的判定规则**一字未改**；`run 3`/`run 4` 的 `A7b = NOT_VIOLATED` 是仪器假阳性，**不得引用**。

---

## §5 描述性 vs 预测性（不得混同）

- **本轮的「描述性」**：全部 21 条判定 + §2.2 量级表。它们描述的是**载荷当前的数学行为**（在给定构造输入下）。
- **本轮的「预测性」**：**零**。本轮不产生任何收益序列、不做选样、不计算 IC/收益 ⟹ 不含任何「这个量能否预测收益」的主张。
- **A2a/A2b/A2d 的暴露面**：偏差**只在短员日出现**。短员日在**真实数据**中的占比决定了实际暴露面，
  **本机无 DB ⟹ 不可测** ⟹ 登记 `B-R008-01`，**不以任何估计值替代**。
- **A3a 的暴露面**：只在调用方**不传** `strategy` 时出现。本机可确认控制器默认值（`required=false`）存在，
  但**真实前端是否总是传参**本轮未测（前端 `backtest.ts` 有 `strategy` 参数位，未见必填约束）⟹ 一并写入 `D-R008-04` 的「需人工确认项」。

---

## §6 量级与可达性的诚实标注（防夸大）

1. **A3c 判 `NOT_REACHABLE` 是「本机不可测」，不是「没问题」**：并列 `createdAt` 的取舍完全依赖 DB 返回序，
   无 DB 无法构造 ⟹ 该轴**零信息**，不得读作「确定性已确认」。相关登记 `D-R008-05`。
2. **A8b 判 `VIOLATED`，但可达性未定**：`ProgressiveResultWriter.replaceResultItems:72` 赋 `rank = 1..N`（唯一）；
   其它插入路径（`OversaveReboundScoringService` / `UnifiedScoringService` / `saveItemIfAbsent`）**是否会产生并列 `rankNo` 本轮不判定**。
   `rankNo` 在实体上**无唯一约束**（只有普通索引 `idx_..._result_rank`）⟹ 结构性风险存在、可达性未证。
   登记 `D-R008-10`（决策）+ `B-R008-02`（阻塞项）。
3. **A6a/A6b 的 `CONTRACT_AMBIGUOUS` 是命名/单位问题，不是数值错误**：`DataService` 的市场换手率（%）与
   `TurnoverResponse` 的候选替换率（分数）**各自都对**，问题在**同名**与**同一中文标签**。
   报告不得把它读成「某处算错了」。
4. **A5c 的 `CONVENTION_DIVERGENCE` 是两处惯例相反，不是「谁错」**：`RollingExperimentService` 认为
   无持仓序列则换手不可评估，`BacktestTurnoverService` 认为可评估。两者都可自洽（后者是**集合替换率**语义），
   但项目**没有**在任何地方写明这两个「换手率」不是同一个量 ⟹ 判分歧。
5. **A7b 判 `REGISTERED_ONLY` 的强度最低**：它只说「没写单双向约定」，而判据来源**也没写** ⟹
   本条**不得**与 K1/K2/K3 并列称「问题」。
6. **`round4` 的 NaN/±Inf 行为**：本轮实测 46 个取值全在 [0,1]、无 NaN/±Inf（R007 教训 37 要求的实测项已做），
   但这**不能**推广为「任何输入都不会出现 NaN」——本轮输入全部由构造保证 `|I| ≤ n`。

---

## §7 与既有轮次的关系（防回溯改写）

- R004（语义/分组口径）、R005（PIT 泄漏）、R006（`rank_ic` 族）、R007（不确定性族）之后，本轮是**第 5 个审计轮**，
  首次把被审计面扩到 **`BacktestTurnoverService`**（前四轮均未触及）。
- **R006 已经记录过「`selector_result` 侧的空组伪造」**；本轮的 A2c/A5b 是**同一类问题在换手率载荷上的独立实例**，
  **不得**合并计数，也**不得**说「R006 已发现过所以本轮不算新」。
- 本轮**未**推翻任何既有轮次的结论，**未**修改任何既有报告/预注册文本。

---

## §8 本轮不得被叙述成什么（负面清单）

- ❌「发现了换手率算错」——满员日它**逐位正确**（A1a/A4a）。
- ❌「论文证明了项目有问题」——论文只提供判据，被杀死的是**项目自己的 H0**。
- ❌「短员日的偏差在真实数据上造成了 X% 的错报」——真实短员日占比**不可测**（`B-R008-01`），**未**做任何估计。
- ❌「21/21 预测全中说明本 agent 的预测能力强」——见 §4。
- ❌「A3c/A8b 已经查清」——前者本机不可测，后者可达性未定。
- ❌「已经修好了」——本轮**零生产代码改动**（§9 只登记）。
- ❌「`REGISTERED_ONLY/CONTRACT_AMBIGUOUS` 也是 killer」——总判定由 `K1/K2/K3` 短路得出，其余 K 的布尔值只为完整性列出。

---

## §9 台账与队列动作（**只登记，不修**）

### 9.1 `decision_queue.md` 新增条目（全部 `PENDING_USER`，均属协议 §7 record-only 档）

| id | 事项 | 为何需用户决定 | 证据 |
|---|---|---|---|
| `D-R008-01` | 短员日分母：把 `n`（请求参数）当作分母 | 改法涉及**换手率口径定义**（改用当日实际规模/置 `UNAVAILABLE`），属冻结口径与公开语义 | A2a/A2b/A2d（0.70 / 0.35 / 18 格） |
| `D-R008-02` | javadoc 声明「两日均有 topN 个候选」但无守卫 | 二选一：改文档（去掉前置条件声明）或加 guard（行为变更） | A1b |
| `D-R008-03` | 退化分支 `0.0` 无机器可读不可用标记 | 加 `status`/`available` 键即**改公开 API 契约** | A2c |
| `D-R008-04` | `strategy=null` 默认路径混合多策略配对 | 改默认（必填/强制分组）即**改 API 契约**；另需人工确认前端是否总传参 | A3a |
| `D-R008-05` | `strategy = ""` 与 `null` 计算相同、回显不同 | 回显/过滤条件不一致，修法触契约 | A3b |
| `D-R008-06` | 逐日行缺 `prev_size`/`curr_size` | 加字段即**改契约** | A4b/A5a |
| `D-R008-07` | 无结构化口径字段（`basis`/`definition`/`metric_scope`）、无单双向约定声明 | 加字段即**改契约** | A5b/A7b |
| `D-R008-08` | `RollingExperimentService` 与 `BacktestTurnoverService` 的换手率结论相反 | 需人工定稿「两个换手率不是同一个量」的命名/文档口径 | A5c |
| `D-R008-09` | 同名 `avg_turnover` / 同标签「换手率」指代两种量（% vs 分数） | 统一命名或单位即**改契约**（含前端 ×100 换算） | A6a/A6b |
| `D-R008-10` | `rankNo` 并列使载荷依赖未钉住的行序；`rankNo` 无唯一约束 | 加唯一约束即**改数据库结构**；且可达性未定需先定论 | A8b + `B-R008-02` |

### 9.2 `blocked_queue.md` 新增条目

| id | 阻塞项 | 状态 |
|---|---|---|
| `B-R008-01` | **真实数据中短员日占比**（`|prev| < topN` 或 `|curr| < topN`）决定 A2a/A2b/A2d 的**实际暴露面**；本机无 DB（本轮 0 条 SQL）⟹ 不可测 | `BLOCKED`（预注册 §5.4 已冻结此登记） |
| `B-R008-02` | `rankNo` 并列在**真实插入路径**中是否会产生（`OversaveReboundScoringService`/`UnifiedScoringService`/`saveItemIfAbsent`）需 DB/运行时证据 | `BLOCKED` |

### 9.3 `shadow_registry.md`

本轮 **零影子因子、零提升**（`shadow_registry.md` 追加一条空轮记录）。

---

## §10 计数断言 C1–C11（**只读扫描**，与判定无关）

口径：Python 内部 walker（斜杠归一化、排除 `target/dist/node_modules/__pycache__`）；`raw` = 不排除构建产物。

| id | 语义 | 冻结期望(src) | 实测(src) | 实测(raw) | 一致 |
|---|---|---|---|---|---|
| C1 | `avg_turnover` 于 `modules/**` | 13 | **13** | 17 | ✅ |
| C2 | `daily_turnover` 于 `modules/**` | 6 | **6** | 8 | ✅ |
| C3 | `换手` 于 `modules/web/src/**` | 8 | **8** | 8 | ✅ |
| C4 | `BacktestTurnoverService` 于 `modules/**` | 6 | **6** | 6 | ✅ |
| C5 | `put("note"` 于 `BacktestTurnoverService.java` | 2 | **2** | 2 | ✅ |
| C6 | `turnover_rate` 于 `modules/**` | 21 | **21** | 26 | ✅ |
| C7 | `backtest/turnover` 于 `modules/**` | 2 | **2** | 4 | ✅ |
| C8 | `"turnover"` 于 `RollingExperimentService.java` | 1 | **1** | 1 | ✅ |
| C9 | `turnover` 于 `**/*.sql` | 6 | **6** | 11 | ✅ |
| C10 | `[Tt]urnover` 于 `modules/backend-java/**/*.java` | 114（25 文件） | **114**（25 文件） | — | ✅ |
| C11 | `[Tt]urnover` 于 `modules/web/src/**`（.vue/.ts） | 9 | **45**（9 文件） | — | ❌ 附录 A-08 |

**C1 文件级明细**（13）：`BacktestTurnoverService.java` 2 / `DataService.java` 4 /
`BacktestTurnoverServiceTest.java` 3 / `backtest.ts` 1 / `data.ts` 1 / `ScoringBacktest.vue` 1 / `DataManagement.vue` 1 ——
与冻结 `by_file` **逐文件一致**。

**C6 文件级明细**（21 / 13 文件）：`DataService` 1、`MarketService` 2、`StockTrendScoringService` 1、`T1CandidateService` 1、
`LimitUpEcologyService` 1、`V1__baseline_core.sql` 1、`V2__research_tables.sql` 2、`T1CandidateServiceTest` 3、
`01_init_schema.sql` 1、`migrate_stock_daily.py` 4、`market.ts` 2、`research.ts` 1、`StockDetailView.vue` 1。

**C9 文件级明细**（6 / 4 文件）：`V1__baseline_core.sql` 1、`V2__research_tables.sql` 3、
`V7__market_cap_reliability.sql` 1、`modules/database/01_init_schema.sql` 1 —— 与冻结 `by_file` 一致。

**C10 文件级明细**（114 / 25 文件，按命中数降序）：
`BacktestTurnoverService.java` 18、`HistoricalDataService.java` 13、`BacktestTurnoverServiceTest.java` 10、
`StockTrendScoringService.java` 9、`CrossSectionNeutralizerTest.java` 7、`ScoringRules.java` 6、
`BacktestTurnoverController.java` 5、`MarketEnvironmentAnalyzer.java` 5、`TechnicalIndicators.java` 5、
`DataService.java` 4、`CrossSectionNeutralizer.java` 4、`MarketData.java` 3、`DynamicWeightingService.java` 3、
`StrategyFormulaRegistry.java` 3、`T1CandidateServiceTest.java` 3、`RollingExperimentService.java` 2、
`StockTrendScore.java` 2、`MarketService.java` 2、`LimitUpEcologyService.java` 2、`HistoricalDataServiceTest.java` 2、
`CodexShortCycleScoringEngineTest.java` 2、`MarketPriceRaw.java` 1、`StockSelectorService.java` 1、
`T1CandidateService.java` 1、`HistoricalDataServiceRawParseTest.java` 1。

**C11 文件级明细**（45 / 9 文件）：`ScoringBacktest.vue` 24、`backtest.ts` 10、`research.ts` 3、
`market.ts` 2、`RollingWalkForward.vue` 2、`data.ts` 1、`DataManagement.vue` 1、`StockDetailView.vue` 1、`StockPicker.vue` 1
⟹ **冻结期望 9 实为文件数**（附录 A-08）。

---

## §11 测试验证与 hygiene

### 11.1 测试套件（零生产 / 零测试改动 ⟹ 与基线一致）

| 套件 | 命令 | 结果 |
|---|---|---|
| Java | `mvn -o -B test -pl modules/backend-java` | **350 passed, 0 failures, 0 errors, 0 skipped**（BUILD SUCCESS，25.2 s） |
| Python | `cd modules/compute-python && python -m pytest tests paper/tests -q` | **343 passed**（14.96 s，3 warnings 为既有 warning） |

⟹ 与 R33 基线（Java 350 / Python 343）**逐项一致**，证明本轮**未**改动生产/测试代码。

### 11.2 hygiene

- 改动范围：仅 `research/paper_loop/**`（`rounds/R008_report.md`、`experiments/R008_turnover_audit/**`、5 份台账）。
- 无 `.class`、无 `__pycache__`、无 `target/` 产物入库（`probe/out` 每次运行后删除；`__pycache__` 由 `.gitignore` 覆盖，提交前复核）。
- 无密钥/令牌/连接串；无 SQL 语句；无 DB 连接（本轮 0 条 SQL）。
- `git diff --check` 无空白错误。
- 未启用真实自动交易、未下单、未读取或生成真实凭证、未改任何真实交易接口默认值、未删历史数据、未做不可逆迁移。

### 11.3 提交信息（回填区）

- 主提交：`722c2c3d` ← 本轮产物（报告 + 实验 + 台账）；push **成功**（`7f493011..722c2c3d  master -> master`，无 `PUSH_PENDING`）
- 回填提交：`9e427bfb` ← 本行与台账里的 commit-id；push **成功**（`722c2c3d..9e427bfb  master -> master`）
- **递归边界（如实声明）**：`9e427bfb` **自身**的 commit-id **不**再被回填（同 R007 的 `7f493011` 做法），
  否则需要无限笔提交；两笔提交的 id 与 push 结果均可在 `git log` 与 `progress.md` §5 复核。

---

## §12 运行次数与自证量

**`run_turnover_audit.py` 实际运行 5 次**（`run.log` append-only，未裁剪；下列时间戳均取自 `run.log`）：

| # | 时刻 | 结果 | 失败/缺陷原因（全部在**我自己的** harness/仪器里） |
|---|---|---|---|
| 1 | 21:49:15 | **失败** → 写出 `total = INCONCLUSIVE` | `NoClassDefFoundError: org/slf4j/LoggerFactory`：生产类被 `@Slf4j` 初始化时需要 slf4j/Spring Data/jakarta.persistence，classpath 未含（附录 A-01） |
| 2 | 21:50:24 | **崩溃**（21:51:06，无 `result.json`） | `IndexError`：金丝雀 A4a 引用 `ind(ctx,"V10",...)`，而探针当时**没有** V10 段（A4a 的证据被错误地挂在 V01 下）（附录 A-02） |
| 3 | 21:53:04 | 完成（`REJECTED`）但**设备/面板有缺陷** | A7b 仪器假阳性（附录 A-06）；A1a/A4a 面板未实现冻结的 `|I|` 集合（附录 A-07） |
| 4 | 21:54:20 | 完成（`REJECTED`） | A7b 已修；A1a/A4a 面板缺陷**仍存在**（当时未被发现，见 A-07） |
| 5 | 21:57:14 | **完整**（21:57:54 结束） | 面板已修 + 新增 `panel_design_selfcheck`；21/21 金丝雀 PASS、四门全过、`total = REJECTED` |

**金丝雀门的实际战果（自证量的诚实说明）**：

- 21 对 CLN/INJ **全部在运行前**写下期望词，且**都作用于检查器实际读取的那个键**（R007 教训 38）。
- 但金丝雀**没有**发现 A-07 的面板缺陷：它检验的是「判定行为能否被翻转」，而 A1a/A4a 的判定在**任何满员 pair** 上都成立
  ⟹ 面板设计与金丝雀**正交**。这是本轮新增 `panel_design_selfcheck` 的直接动机（正面产出：仪器补强）。
- 判 `clean != inj`：21/21 成立；无「检查器缺对」；无表外判定词。

**本轮我自己的缺陷清单（5 条，全部已在最终运行前修掉）**：A-01（依赖）、A-02（键名错挂）、A-03（预注册字面公式口径）、
A-06（A7b 假阳性）、A-07（面板设计）。A-03/A-04 属**预注册文本自身的**不一致，只写附录、**不回写**冻结文本。

---

## §13 本轮不该做的事（已遵守清单）

- **未**修改任何生产代码、测试代码、公开 API、前端契约、数据库结构、`ResearchConvention` 或任何冻结口径。
- **未**启用真实交易、**未**下单、**未**读取/生成真实凭证、**未**把任何真实接口改成默认启用。
- **未**删除或改写任何历史数据/报告/预注册文本；**未**用估计值替代不可测的量（`B-R008-01`）。
- **未**因为「连续多轮无产出」而降低标准：本轮 `REJECTED` 由三条独立杀点支撑，另 7 条非杀点照实登记为「只登记」。
- **未**把 21/21 预测全中包装成能力（§4），**未**把 `REGISTERED_ONLY` 混入杀点（§8）。
- **未**在实验代码之后放宽任何杀死条件（冻结文本一字未改）。

---

## 附录 A 勘误（**只在此处更正，绝不回写冻结的 `R008_prereg.md`**）

| id | 类型 | 内容 | 处置 |
|---|---|---|---|
| **A-01** | harness 缺陷 | 缺 slf4j / Spring Data / jakarta.persistence 依赖，运行 1 因 `NoClassDefFoundError` 失败 | 按 BOM 钉死 9 个 jar；缺任一显式失败（§1.3） |
| **A-02** | harness 缺陷 | 金丝雀引用不存在的 `V10` 键（探针无 v10 段），运行 2 崩溃 | 补 `v10()` 段；A4a 证据改挂 `V10` |
| **A-03** | 预注册文本不一致 | §4 A4c 字面写 `n_recovered = 4/(1−0.75) = 16`（分子用「集合大小 k=4」）；算术上应为 `|I|/(1−turnover) = 1/0.25 = 4` | 探针**并列打印**两种口径（`n_recovered` 与 `n_recovered_frozen_literal`），判定用**算术正确者**（`|I|=1`）；本行即更正处 |
| **A-04** | 预注册文本不可实现 | A4c 的 `topN = 3` 格：`|I|/n = 1/3` 在 `round4` 的 1e-4 量化下**无有限小数展开**，不可精确反解（偏差 ~3e-4 > `TOL_BIT`） | 标 `exact_capable = false`，该格**只作描述**、不参与判定；避免自造假阳性 `VIOLATED` |
| **A-05** | 预注册文本不一致 | A4a 字面写「**3 日**全满员面板」，却又要求「每个 pair 的 `|I| ∈ {7,8,9}` ⟹ turnover ∈ {0.1,0.2,0.3}」——3 日只有 **2** 个 pair，取不到 3 个值 | 实现为 **4 日**（3 个 pair，`|I|` = 9/8/7 ⟹ turnover = 0.1/0.2/0.3），**满足**集合与取值要求；A1a 的「另用 3 日全满员面板」按字面实现（2 个 pair，`|I|` = 9/8） |
| **A-06** | **仪器**假阳性（改判定词的**非**判据部分） | 初版 A7b 把 `ScoringBacktest.vue:67` 的「**双边**佣金、卖出税费和**双边**滑点」当成单双向口径声明 ⟹ 假 `NOT_VIOLATED` | 改为**命中行须同行含 `turnover\|换手`**（被测量的量是「对换手单向/双向的声明」）；冻结规则未改；`run 3`/`run 4` 的该轴结论作废 |
| **A-07** | **面板设计缺陷**（`run 3`/`run 4`） | A1a 的 3 日面板相邻两日复用了 `S11` ⟹ 两个 pair 都是 `|I| = 9`；A4a 的 4 日面板同类错误 ⟹ 三个 pair 都是 `|I| = 9`。**未实现**冻结的 `|I|` 集合 | 已修正为逐日换成员（A1a：9/8；A4a：9/8/7）；新增 `panel_design_selfcheck`（A1a/A4a 证据内，`run 5` 全 `true`）。**判定词不变**（恒等式与口径对齐对任何满员 pair 都成立），但 `run 3`/`run 4` **不得**被引用为「A4a 覆盖了 `|I| ∈ {7,8,9}`」的证据 |
| **A-08** | 冻结计数勘误 | C11 冻结期望 `9` 实为**文件数**，命中数为 **45**（9 文件） | 计数不匹配按预注册 §7 **只记附录**、不改任何判定词；C11 不参与 K8 |
