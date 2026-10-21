# R007 预注册（冻结）

> **冻结声明**：本文件在**任何实验代码**、**任何生产代码**、**任何测试代码**之前落盘。
> 冻结后**不得**因看到结果而放宽或改写任何判据、阈值、网格、判定词表。
> 事后更正只能写入 `rounds/R007_report.md` 的**附录 A 勘误**，并明确标注是事后更正。
> **本文件一旦提交即视为冻结版本**；若必须修改，须在报告附录 A 记录「改了哪一行、为什么、何时」。

- 轮次：**R007**（审计轮，与 R004 / R005 / R006 同型）
- 判据来源论文：**Harvey–Liu–Zhu，NBER WP 20592（2014）**⟹ 审查见 `rounds/R007_review.md`
- 载体与哈希见 `R007_review.md` §1 / §9
- 被审计对象：**项目自己命名的统计不确定性载荷族**
  `hac_std` / `ci_lower` / `ci_upper`（`FactorResearchEngine` → `FactorResearchService` → 前端标签「HAC 95%CI」）

---

## §1 被检验的主张（H0）与立场

**H0（被检验主张，由代码与契约隐含持有，非论文提出）**：
> 载荷键 `hac_std` / `ci_lower` / `ci_upper` 所命名的量，**忠实对应**其在量化文献中的定义：
> ① `hac_std` 是**日频 IC 序列均值的**异方差自相关一致（HAC）标准误；
> ② `[ci_lower, ci_upper]` 是**同一个均值**的 95% 置信区间；
> ③ 两者在**同一载荷块内互相一致**（前端标签把它们**并称为**「HAC 95%CI」）；
> ④ 当定义**失效**（序列长度 < 2、长程方差估计为负、输入为空/口径不可用）时，
> **不输出一个看起来像估计值的具体实数**。

**立场（承接协议「默认证伪」）**：**假设 H0 不成立**，本轮的默认动作是**杀死它**。
若能杀，判 `REJECTED`；若杀不掉，如实报 `NOT_VIOLATED` / `CONVENTION_DIVERGENCE` / `CONTRACT_*`，
**不得**为了产出正面结论而放宽判据，**也不得**因为「连续多轮负面结果」而抬高或压低判据。

**本 H0 与论文的关系**：论文**不是** H0 的作者。论文只提供 `R007_review.md` §5 表中 **L 档那五条**
（均值标准误的形式、`1.96 ↔ 5%`、序列相关的披露义务、多重检验门槛、bootstrap 时间序列属既有做法），
其余轴全部是 **A（算术）或 P（项目自建）**。

---

## §2 判据分档（本轮强制逐行标注）

| 档 | 含义 | 本轮适用位置 |
|---|---|---|
| **L（文献判据）** | 论文有明示陈述（**注意：本载体的 t-stat 式在印本中未编号**） | 均值标准误的**形式** `σ/√N`；`1.96 ↔ 5%` 双侧；t-ratio 须对 `autocorrelation` 等 **econometrically sound**；新因子门槛 **t > 3.0 / 3.9**；对**时间序列**做 bootstrap 属既有做法 |
| **A（算术推导）** | 论文**未**说，是统计/数学事实 | Bartlett 核的**样本长程方差可以为负**；`Math.max(·, 0)` 把负方差写成 SE=0；覆盖率与名义值的关系；`rank_ic_std/√T` 与 `hac_std` 的尺度关系；Clopper–Pearson 界限 |
| **P（项目自建）** | 项目契约/工程约定 | 「失效时不得输出具体实数」= H0 后半句；「95%CI」这一**展示标签**；CSV 表头命名；键集是否随数据可用性变化 |

**对 L 档的一条自我约束（承接 `R007_review.md` §5.1 / §6）**：
本论文**零字**涉及 Newey–West、置信区间定义、估计标准误、覆盖率、A 股制度。
⟹ 本轮**不允许**出现「某个核/滞后/块长不符合文献」这类**规范型**判定；
所有 HAC/块长/区间构造的断言一律标 **A 或 P**，并且**不得**作为杀死条件（见 §6）。

---

## §3 轴清单

共 **8 条轴 / 21 个可执行断言**。断言 ID 与轴 ID 在下文固定，报告与 `result.json` 必须使用同一套 ID。
**每个断言在冻结时即写明其判定词**（R006 E-4 教训）。

### A1 — `ci_*` 的指称：HAC 还是 bootstrap？（判据 L(1.96) + A + P）

**事实基础（读码，非预测）**：
- `FactorResearchEngine.java:190` `double hacStd = neweyWestHacStd(icSeries);`
- `FactorResearchEngine.java:191` `double[] ci = blockBootstrapCi(icSeries, Math.max(1, holdingDays), 1000, 42L);`
- `:204-206` 两者分别进入 `FactorResearchSummary.hacStd() / ciLower() / ciUpper()`
- `FactorResearchService.java:544-546`（snapshot 块）、`:763-765`（unified 块）、`:832-834`（net 块）三处**并列**写出
- 前端 `FactorResearch.vue:139`（卡片）与 `:322`（表格列）标签均为「**HAC 95%CI**」，绑定 `ci_lower` / `ci_upper`

| 断言 | 内容 | 冻结判定词 |
|---|---|---|
| **A1a** | 在同一注入序列上调用**生产** `neweyWestHacStd` 与 `blockBootstrapCi`，构造 `[mean − 1.96·SE, mean + 1.96·SE]`，与生产 `ci_*` **逐位**比较（容差 1e-9） | 若不等（**我的预测**）⟹ 记事实，判定词 **`NOT_VIOLATED`**（两个量各自定义明确、都合法；不等本身不是缺陷） |
| **A1b** | 标签指称审计：绑定 `ci_lower`/`ci_upper` 的展示标签是否含 `HAC`？同块内是否**同时**存在 `hac_std`？ | 标签含 `HAC` 而值来自 `blockBootstrapCi` ⟹ **`CONTRACT_MISLABEL`** |

**A1 的边界声明**：名义 95% 正态区间与 5% 检验在正态近似下**数值等价**，
所以 A1b **不是**「区间算错了」，而是「**标签指称的估计量与实际使用的估计量不一致**」。
报告与 `decision_queue` 必须逐字保持这个措辞。

### A2 — 退化与钳位：把「无信息」写成「确定」（判据 P + A + 跨轮同型）

**事实基础（读码）**：
- `neweyWestHacStd` `:325-327` `values.size() < 2` ⟹ `return 0;`
- `blockBootstrapCi` `:359-361` `values.size() < 2 || iterations < 100` ⟹ `return {0, 0};`
- `:351` `return Math.sqrt(Math.max(hacVar / n, 0));` ⟹ **负的长程方差被钳成 SE = 0**
- 三条退化路径分别输出 `0.0`：`emptySummary`（`:214-219`）、`unavailableScopeBlock`（`:557-578`）、
  `emptyNetIcBlock`（`:800-818`）

| 断言 | 内容 | 冻结判定词 | 预期 |
|---|---|---|---|
| **A2a** | **T = 1**（恰好一个满足 `n ≥ 10` 的截面日）：载荷出现 `rank_ic_mean ≠ 0`（真实点估计）**与** `hac_std == 0.0`、`ci_lower == ci_upper == 0.0`（**零宽区间且不以点估计为中心**）**并存**，且该块内**不存在**任何标记字段（无 `status`、无 `note`、`cross_section_days >= 1`） | 成立 ⟹ **`VIOLATED`** | **`VIOLATED`**（`K1`） |
| **A2b** | 三条退化路径各自输出 `hac_std = 0.0` / `ci = [0, 0]`；检查其**可区分性** | 记事实；**必须**如实写出**弱可区分性**：`cross_section_days == 0` 与 `status == "UNAVAILABLE"`（仅 `unavailableScopeBlock`）是可机读信号 ⟹ **不得**夸大为「完全不可区分」 | **`REGISTERED_ONLY`**（与 `D-R006-03` 同族） |
| **A2c** | **钳位发生率**：在冻结 DGP 网格上统计 `hac_std == 0.0` 而样本方差 `> 0` 的比例 | 若存在 `n ≥ 10` 的单元该比例 **≥ 5%** ⟹ **`VIOLATED`**；否则 `REGISTERED_ONLY` | **预测性**（我不预设方向） |
| **A2d** | **键集一致性**：`buildRankIc` 的早返回分支（`:663-674`）**不含** `hac_std` / `ci_lower` / `ci_upper`，而正常分支含 ⟹ 同一端点**两种键集** | 成立 ⟹ **`CONTRACT_AMBIGUOUS`** | 描述性（读码已知） |

**A2a 的既有测试披露（必须同量级记录）**：项目**已有**测试
`FactorResearchEngineTest.neweyWestHacStdReturnsZeroForInsufficientData`（`:156-159`）与
`blockBootstrapCiReturnsZerosForInsufficientData`（`:170-174`）**明确断言**了这两个 0 值。
⟹ A2a 的主张**不是**「发现了未预期的 bug」，而是**契约缺陷**：
「**无信息**」与「**恰好为 0 的确定结论**」在载荷上**同码**。
报告与队列必须逐字保持这个措辞，**不得**写成「项目有 bug」或「测试写错了」。

**A2a 的可达性（冻结结论）**：**引擎级确定可达**（一个只含 1 天的 map 即可）。
服务级需「单一合格截面日」，与 R006 `A6b` 的可达性论证**同一条**（`T = 1` 只要求一个 `n ≥ 10` 的截面日）。

### A3 — HAC 的「相关窗口」与持有期 h 解耦（判据 **A + P**，**无 L**）

**事实基础（读码）**：
- `:324` 签名 `neweyWestHacStd(List<Double> values)` —— **没有 `holdingDays` 参数** ⟹ 结构上**不可能**感知重叠阶数
- `:334-335` `lag = max(1, min(floor(n^(1/3)), n−1))`
- `:342-343` Bartlett 权重 `weight = 1 − l/(lag+1)`
- 与之对照：`:358` `blockBootstrapCi(List, blockSize, iterations, seed)` **有** `blockSize` 参数，调用点传 `holdingDays`
- 对外承诺（**用户可见**）：`FactorResearchService.java:83-85` `RANK_IC_EMBARGO_NOTE`
  = 「…**重叠持有期 IC 序列的显著性以 HAC 标准误与 block bootstrap CI 为准**」，
  经 `:534`（snapshot）/`:653`（unified）进入载荷，前端 `FactorResearch.vue:329` 渲染为 `Embargo：…`

| 断言 | 内容 | 冻结判定词 |
|---|---|---|
| **A3a** | 签名事实：生产 `hac_std` 的输入只有序列，**无 h** ⟹ 其相关窗口不可能随 h 变化 | 记事实（描述性） |
| **A3b** | 在冻结 `(n, h)` 网格上，生产 `hac_std` 与**控制实现** NW(lag = `max(1, min(floor(n^(1/3)), n−1))`) 逐位一致（容差 1e-12） | 一致 ⟹ **`NOT_VIOLATED`**（**我的预测**） |
| **A3c** | 同网格上，生产 `hac_std` 与**控制实现** NW(lag = `h−1`) 的比值 `r = SE(cbrt) / SE(h−1)`（在冻结 DGP 网格上取**中位数**） | 存在 `h ≥ 5` 单元使 `median r < 0.90` ⟹ **`CONVENTION_DIVERGENCE`**（**加重**条件见 K5）；否则 **`NOT_VIOLATED`** |
| **A3d** | 上述结果与 `RANK_IC_EMBARGO_NOTE` 的**对外承诺**是否一致（该字符串是本轮判据的 **P 档**依据：项目自己承诺了「以 HAC 标准误为准」） | 承诺称「以 HAC 标准误为准」而窗口与 h 解耦、且 A3c 达材料性 ⟹ **`CONTRACT_MISLABEL`**；未达材料性 ⟹ **`NOT_VIOLATED`** |

**A3 的 DGP 网格（冻结，R005 E-2 教训：必须含量纲/结构维度，且逐单元报告不得只报最佳单元）**：

| 维 | 取值 |
|---|---|
| `n`（序列长度 = 截面日数 T） | **5, 10, 17, 27**（含项目真实 T：R006 实测 10 与 17） |
| `h`（持有期） | **1, 2, 3, 5**（= `UNIFIED_HORIZONS = {1,2,3,5}`）+ **10, 20**（外推探测） |
| DGP | **AR1_0.2 / AR1_0.5 / AR1_0.7** / **MA_equal_{h−1}** |

**A3 的量级诚实前置**：我**在冻结时不知道** `median r` 会落到哪里；
由 Bull 式算术，φ = 0.5、h = 5 时理论比值约 **0.91**（紧贴阈值），φ = 0.7 时约 **0.86**。
⟹ A3c **可能触发也可能不触发**，我**不**预设它触发。这一点必须在报告中如实呈现。

### A4 — bootstrap 块长与 javadoc 声明不一致（判据 **P**）

**事实基础（读码）**：
- `:355` javadoc：*"Block bootstrap 置信区间（处理序列相关，**块大小 = 持有期 h**）。"*
- `:366` 实际：`bs = max(1, min(min(blockSize, floor(sqrt(n))), n−1))`
- `:363-364` 行内注释解释了上限的**理由**（避免块长接近/等于 n 时退化为点估计）

| 断言 | 内容 | 冻结判定词 |
|---|---|---|
| **A4a** | 网格上实测 `bs` 是否等于 `min(h, floor(sqrt(n)), n−1)`；并列出所有 `h > floor(sqrt(n))` 因而 **`bs < h`** 的单元 | 记事实（描述性） |
| **A4b** | 该实测与 `:355` javadoc 的「块大小 = 持有期 h」是否一致 | 不一致 ⟹ **`CONTRACT_AMBIGUOUS`**（文档/实现漂移；行内注释已披露） |

### A5 — 尺度 / 量纲 / 覆盖率（判据 L(形式) + A；R005 E-2 教训）

| 断言 | 内容 | 冻结判定词 |
|---|---|---|
| **A5a** | i.i.d. 序列上 `hac_std` ≈ `rank_ic_std / √T`（相对容差 **5%**） | 成立 ⟹ **`NOT_VIOLATED`** |
| **A5b** | 报告 T ∈ {10, 17} 的 `1/√T` 量级（**0.316 / 0.243**）⟹ 登记「两个字段都叫 `std`，但相差 √T ≈ 3.2–4.1 倍」 | **`REGISTERED_ONLY`** |
| **A5c** | **覆盖率模拟**：两臂 —— `ARM_BOOT` = 生产 `blockBootstrapCi(x, h, 1000, 42L)`；`ARM_HAC` = `[mean − 1.96·hacStd, mean + 1.96·hacStd]`（**本轮按 L(1.96) + A 构造，不是项目输出**）。μ = 0、σ = 1、每单元 **1000 次重复**、每格种子冻结。判定：**存在任一单元的实测覆盖率 < 0.85 且其 Clopper–Pearson 单侧 99% 下界亦 < 0.85** | 触发 ⟹ **`VIOLATED`**（`K3`）；否则 **`NOT_VIOLATED`** |

**A5a 的既有测试披露**：项目**已有**测试
`FactorResearchEngineTest.neweyWestHacStdHandlesIndependentSeries`（`:145-153`）
**逐字断言同一不变式**（`hacStd ≈ sqrt(var/n)`，offset 0.01）。
⟹ A5a 是**再确认**，不是新发现。**冻结处置规则**：若 A5a 触发 `VIOLATED`，
则**首先**判定为「我的探针或类加载出错」：以该既有测试的输入为准做交叉核对，
不一致 ⟹ A5a 记 **`INCONCLUSIVE`**（工具问题）；一致而仅在该测试之外的单元失败 ⟹ 记事实，
**不**单独构成 `REJECTED`。**A5a 不是杀死条件。**

**A5c 的覆盖率网格（冻结）**：`n ∈ {5,10,17,27}` × `h ∈ {1,2,3,5}` ×
DGP ∈ {IID_NORMAL, AR1_0.2, AR1_0.5, AR1_0.7, MA_equal_{h−1}} = **80 个单元 × 2 臂**。
`h = 1` 时 `MA_equal_{0}` **按定义退化为 IID_NORMAL**（冻结，避免重复计数）。
**有效阈值披露（算术）**：reps = 1000 时该规则近似等价于「实测覆盖率 ≲ 87%」，报告须写明这一点以便判断材料性。

**A5c 的抽象性披露（必须逐字保留）**：`MA_equal_{h−1}` 是**重叠持有期所致依赖的
一种风格化表征**（依赖跨度恰为 h−1，与项目字符串援引的跨度一致），
**不是**「IC 序列真实依赖结构」的主张。本轮**不**主张真实 IC 序列是 MA(h−1)。

### A6 — 名义 95% vs 多重检验门槛（判据 **L**，但只作登记）

| 断言 | 内容 | 冻结判定词 |
|---|---|---|
| **A6a** | 全仓扫描：是否存在任何 `nominal` / `多重检验` / `校正` / `adjusted` 标注（描述该区间为名义值） | 不存在 ⟹ **`REGISTERED_ONLY`**（**不构成**杀死条件） |
| **A6b** | 登记性对照：对注入序列报告 `mean / hac_std` 与 **1.96 / 3.0 / 3.9** 的关系 | **`REGISTERED_ONLY`**，且**禁止**任何「因子显著性」叙述（见 §9.7） |

### A7 — 确定性与不可达路径（判据 **P**）

| 断言 | 内容 | 冻结判定词 |
|---|---|---|
| **A7a** | 同一输入、同一进程内两次调用 `blockBootstrapCi`（种子 42）与两次 `summarize`，`ci_*` / `hac_std` 必须**逐位相同** | 相同 ⟹ **`NOT_VIOLATED`**；不同 ⟹ **`VIOLATED`**（`K10`） |
| **A7b** | `iterations < 100 ⟹ {0,0}`（`:359`）在 **API 面**是否可达 | 调用点（`:191`）硬编码 1000 ⟹ **`NOT_REACHABLE`** |

### A8 — 导出列名与口径相邻（判据 **P**）

**事实基础（读码）**：`FactorResearch.vue` `exportUnifiedCsv()` `:506` 表头
`…, 'net_ic', 'hac_std', 'ci_lower', 'ci_upper'`；`:510` 值
`row.cost_net_ic?.rank_ic_mean ?? '', row.hac_std, row.ci_lower, row.ci_upper`

| 断言 | 内容 | 冻结判定词 |
|---|---|---|
| **A8a** | CSV 列 `net_ic` 的值来源是 `cost_net_ic.rank_ic_mean`（**去掉了 `cost_` 前缀**，且该量是 **rank IC 均值**而非「IC」） | 成立 ⟹ **`CONTRACT_MISLABEL`** |
| **A8b** | 紧随其后三列取自 `row.*`（**毛收益块**），与 `net_ic`（**成本后块**）**同排不同口径** | 成立 ⟹ **`REGISTERED_ONLY`** |

---

## §3.1 描述性 vs 预测性（独立小节 —— R005 教训 E-4 的直接落实）

| 轴/断言 | 冻结时的状态 | 性质 |
|---|---|---|
| A1a | 未实测（不等性由「两条不同算法」保证，但我未实测差异量级） | **预测性** |
| A1b | 读码已知标签为 `HAC 95%CI`、值为 `ci_*` | **描述性** |
| A2a | 读码已知 T=1 ⟹ `{0,0}`；**「与真实点估计并存且无标记」未实测** | **预测性** |
| A2b | 读码已知三处 0 值 | **描述性** |
| A2c | **完全未实测**（钳位发生率未知） | **预测性** |
| A2d | 读码已知早返回分支键集不同 | **描述性** |
| A3a | 读码已知签名无 h | **描述性** |
| A3b | 未实测（我预测逐位一致） | **预测性** |
| A3c | **完全未实测**（比值未知，可能两侧都落空） | **预测性** |
| A3d | 读码已知对外字符串内容 | **描述性**（结论依赖 A3c） |
| A4a/A4b | 读码已知公式与 javadoc | **描述性** |
| A5a | 未实测（但与既有测试同断言 ⟹ 预期 `NOT_VIOLATED` 且**不构成新发现**） | **预测性（低信息量）** |
| A5b | 算术（`1/√T`） | **描述性** |
| A5c | **完全未实测** | **预测性（本轮最重）** |
| A6a | 未实测（预期 0 命中） | **预测性** |
| A6b | 算术 | **描述性** |
| A7a | 读码已知固定种子 | **预测性** |
| A7b | 读码已知调用点常量 | **描述性** |
| A8a/A8b | 读码已知 | **描述性** |

**⟹ 本轮真正的证伪内容 = A1a + A2a + A2c + A3b + A3c + A5a + A5c + A6a + A7a。**
其余为**已读码事实的规格化登记**，报告中必须与预测性内容**分栏**呈现。

---

## §4 变体预算与行和交叉校验（防「预算膨胀」，沿用 R005 / R006 做法）

- **预算上限：21 个变体**（= 断言数）。**只允许减少，不允许增加**。
- 变体编号 `V01..V21`，与断言一一对应并**双向映射**（每个断言恰一个变体；每个变体恰一个断言）。
- **行和交叉校验（强制）**：`result.json` 必须写出
  `by_axis` 各轴变体数之和 == `total_variants` == 台账计数，并写出**逐轴计数表**：
  **A1=2, A2=4, A3=4, A4=2, A5=3, A6=2, A7=2, A8=2 ⟹ 合计 21**。
  校验失败 ⟹ 立即停止并记 `INCONCLUSIVE`（`K8`）。
- **A6 的两个变体（V13/V14）只做登记，不计入 K 判定**（见 §3 A6）。
- **A5c 是一个变体但含 80 单元 × 2 臂**；网格单元级结果必须**逐单元**写入 `result.json`
  （`cells` 数组），**不得**只写汇总值（R005 E-2 教训）。
- 预算外的任何探针必须命名 `posthoc_*` 且**不参与** total verdict 合成（R005 E-5 的处置方式）。

## §5 判定词表（冻结，不得新增）

| 词 | 含义 |
|---|---|
| `NOT_VIOLATED` | 断言被检验后**未**发现违反（**是有效结果，含 A1a / A3b / A5a 这类正面或中性发现**） |
| `VIOLATED` | 断言成立且构成**偏离自身契约或统计定义失效** |
| `CONVENTION_DIVERGENCE` | 与文献/常识口径**不同但自洽**，且量级有限 ⟹ 只登记、不作杀 |
| `CONTRACT_AMBIGUOUS` | 数值自洽但**命名/文档/展示**存在歧义或文档漂移 |
| `CONTRACT_MISLABEL` | **命名断言了一个具体估计量/口径，而实际值来自另一个估计量/口径**（比 `AMBIGUOUS` 强） |
| `REGISTERED_ONLY` | 只登记、**明确不进入**杀判据 |
| `NOT_REACHABLE` | 断言在当前 API/UI 可达面上**无法**触发（须附可达性论证） |
| `INCONCLUSIVE` | 证据不足 / 我的代数、探针或类加载出错 |

**词表扩展披露**：`CONTRACT_MISLABEL` 是相对 R006 八词表的**第九个词**，
**在本文件冻结时一次性声明**（此时尚未运行任何实验），用于区分
「指称了错误的估计量」（本轮 A1b / A3d / A8a）与「只是有歧义」（A2d / A4b）。
**冻结后不得再新增词**。

---

## §6 杀死条件（K）（**一经写下不得事后放宽**）

| ID | 条件 | 触发后判定 |
|---|---|---|
| **K1** | A2a `VIOLATED`（T = 1 时「零宽区间 `[0,0]` + 真实点估计」并存且同块内无标记字段） | `REJECTED` |
| **K2** | A2c `VIOLATED`（存在 `n ≥ 10` 单元，钳位发生率 ≥ 5%） | `REJECTED` |
| **K3** | A5c `VIOLATED`（存在单元覆盖率 < 0.85 且 CP 单侧 99% 下界 < 0.85） | `REJECTED` |
| **K4** | A1b `VIOLATED`（`ci_*` 被标成 `HAC 95%CI` 而值来自 `blockBootstrapCi`） | **`CONTRACT_MISLABEL`**（**非** `REJECTED`） |
| **K5** | A3c 达材料性 **且** A3d 成立 | **`CONTRACT_MISLABEL`**（**非** `REJECTED`） |
| **K6** | A4b 或 A2d 或 A8a 成立 | **`CONTRACT_AMBIGUOUS`** / `CONTRACT_MISLABEL`（**非** `REJECTED`） |
| **K7** | A2b / A5b / A6a / A6b / A8b 成立，或 A3c 未达材料性但方向一致 | **`REGISTERED_ONLY`** / **`CONVENTION_DIVERGENCE`**（**非** `REJECTED`） |
| **K8** | 行和交叉校验失败（§4） | 立即停止，整轮 `INCONCLUSIVE` |
| **K9** | 阳性对照未通过（§7：G3 门 / 金丝雀对 / 与既有测试一致性） | 整轮 `INCONCLUSIVE`，**所有断言结果作废**（R005 教训 7） |
| **K10** | A7a `VIOLATED`（同一输入两次运行 `ci_*` 不同 ⟹ 载荷不可复现） | 整轮 `INCONCLUSIVE`（数值类结论全部降级） |

**总判定合成规则（冻结，按序短路）**：
1. 若 **K8 / K9 / K10** 任一触发 ⟹ 总判定 `INCONCLUSIVE`，其余断言结果**全部作废**；
2. 否则若 **K1 / K2 / K3** 任一触发 ⟹ 总判定 **`REJECTED`**（H0 被杀）；
3. 否则若 **K4 / K5** 任一触发 ⟹ 总判定 **`CONTRACT_MISLABEL`**；
4. 否则若 **K6** 触发 ⟹ 总判定 **`CONTRACT_AMBIGUOUS`**；
5. 否则总判定 **`NOT_VIOLATED`**（H0 存活，**不得**叙述为「项目有问题」）。

**H0 的完整存活条件（同样冻结）**：**K1 与 K2 与 K3 全部未触发**。
注意：**H0 存活 ≠ 总判定为 `NOT_VIOLATED`** —— 总判定可以是 `CONTRACT_MISLABEL` /
`CONTRACT_AMBIGUOUS` 而 H0 仍然存活。报告**必须**先把这两件事分开写，再给总判定。
**不得**用「部分成立」「基本符合」这类模糊表述替代逐条结论。

---

## §7 阳性对照与防掩码门（R005 教训 7 / 21 的落实）

### G3 门：断言对象必须**就是**生产类产生的对象

- 探针必须**加载仓库自己的** `modules/backend-java/target/classes` 下的
  `com.quant.service.backtest.FactorResearchEngine`，并在启动时打印
  `FactorResearchEngine.class.getProtectionDomain().getCodeSource().getLocation()`；
  该路径**不包含** `modules/backend-java/target/classes` ⟹ **立即中止**（`INCONCLUSIVE`）。
- **禁止**在探针或 Python 侧**重新实现** `neweyWestHacStd` / `blockBootstrapCi` / `summarize` 作为
  **断言对象**。控制实现（NW 的其它滞后、覆盖率统计、CP 界限）**只能**作为**对照值**出现，
  且**必须**与生产实现同输入并列打印；一旦发现「生产实现未被调用」即判 `K9` 失败。
- **反向锚点（R006 模板照搬）**：探针必须包含至少两条**已知答案**的哨兵，
  其中至少一条是「**必须不相等**」的哨兵（例如：生产 `hac_std` 在给定序列上
  **不得**等于 lag+1 的控制值），以证明比较逻辑没有恒真。

### 与既有测试的一致性门（本轮新增，因 A5a 与既有测试同断言）

- 探针必须复现 `FactorResearchEngineTest:145-153` 的输入，并核对
  `hacStd ≈ sqrt(var/n)`；不一致 ⟹ `K9` 失败（工具问题），而非项目缺陷。

### 金丝雀对（INJ / CLN），必须**成对**存在

| 金丝雀 | 构造 | 期望 |
|---|---|---|
| **CLN-1** | T ≥ 2、序列无退化、`ci_lower < ci_upper` | A2a 检查器**不**触发 |
| **INJ-1** | 手工构造 T = 1 的载荷（`mean ≠ 0`、`ci = [0,0]`、无标记字段） | A2a 检查器**必须**触发 |
| **CLN-2** | 两条完全同源的 SE（同一算法同一输入） | A1a 的「不等」检查器**不**触发 |
| **INJ-2** | 把 A1a 检查器接到人为偏移一位的对照值 | A1a 检查器**必须**触发 |
| **CLN-3** | i.i.d.、T = 27 | A5a 不变式**不**触发 |
| **INJ-3** | 把 A5a 的期望值改成 `rank_ic_std/√(T−1)` | A5a 检查器**必须**触发 |
| **CLN-4** | 覆盖率规则喂入 950/1000 | **不**触发 K3 |
| **INJ-4** | 覆盖率规则喂入 800/1000 | **必须**触发 K3 |

**检查器注册表（CHECKERS）**：每个断言 ID 在代码中**恰有一个**注册项
`CHECKERS["A2a"] = fn`，且**每个**检查器都必须有 **INJ + CLN 两条金丝雀**；
缺任一条 ⟹ `K9` 失败 ⟹ 整轮 `INCONCLUSIVE`。
`result.json` 必须写出「检查器数 / 金丝雀数 / 无金丝雀的检查器列表（必须为空）」。

---

## §8 计数断言与可复算命令（R005 教训 15 / 23 + R006 E-3）

> 规则：任何计数断言**必须**附**可复算命令**，命令**必须显式声明扫描根**，
> 且**不得**使用 `head` / `tail` / `-c` 等截断型命令（R004-E-4 型误判）。
> 下表的**期望值是我在冻结前实测的**，冻结后**不得**修改。

| ID | 计数断言 | 可复算命令（扫描根显式写出） |
|---|---|---|
| C1 | **snake_case** `hac_std` 的全部匹配行 | `cd E:/Project/quant-trading-system && grep -rn "hac_std" --include=*.java --include=*.vue --include=*.ts --include=*.js --include=*.sql --include=*.py .` |
| C2 | `ci_lower` 或 `ci_upper` 的全部匹配行 | `cd E:/Project/quant-trading-system && grep -rn "ci_lower\|ci_upper" --include=*.java --include=*.vue --include=*.ts --include=*.js --include=*.sql --include=*.py .` |
| C3 | 展示标签 `HAC 95%CI`（扫描根 = `modules/web/src`） | `cd E:/Project/quant-trading-system && grep -rn "HAC 95%CI" modules/web/src` |
| C4 | 生产调用点 `neweyWestHacStd(` / `blockBootstrapCi(`（扫描根 = `modules/backend-java/src`） | `cd E:/Project/quant-trading-system && grep -rn "neweyWestHacStd(\|blockBootstrapCi(" modules/backend-java/src` |
| C5 | `"status"` 在 `FactorResearchService` 中出现次数（A2b 可区分性依赖） | `cd E:/Project/quant-trading-system && grep -n '"status"' modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java` |
| C6 | `RANK_IC_EMBARGO_NOTE` 的定义与全部引用（A3d 依赖） | `cd E:/Project/quant-trading-system && grep -rn "RANK_IC_EMBARGO_NOTE" --include=*.java .` |
| C7 | CSV 表头 `net_ic`（A8a 依赖） | `cd E:/Project/quant-trading-system && grep -n "net_ic" modules/web/src/views/factor/FactorResearch.vue` |
| C8 | 硬编码种子调用 `1000, 42L`（A7 依赖） | `cd E:/Project/quant-trading-system && grep -rn "1000, 42L" --include=*.java modules/backend-java/src/main modules/backend-java/src/test` |
| C9 | `hac`/`ci_lower`/`ci_upper` 是否**落库**（扫描根 = 仓库根 `*.sql`） | `cd E:/Project/quant-trading-system && grep -rni "hac\|ci_lower\|ci_upper" --include=*.sql .` |

**冻结的期望值（供比对，事后不得改）**：

| ID | 冻结期望 |
|---|---|
| C1 | 生产 **9** 行（`FactorResearchService` 5 + `factor.ts` 2 + `FactorResearch.vue` 2）；research **1** 行（R006 探针） |
| C2 | 生产 **18** 行（`FactorResearchService` 10 + `factor.ts` 4 + `FactorResearch.vue` 4）；research **2** 行 |
| C3 | **2** 行（`FactorResearch.vue:139`、`:322`） |
| C4 | 生产 = 调用点 **1 + 1**（`FactorResearchEngine:190/:191`）、定义点 **1 + 1**（`:324/:358`）；`src/test` 内 **6** 行 |
| C5 | **1** 行（`:560`） |
| C6 | 定义 **1** + 引用 **2**（`:534`、`:653`）；前端绑定 `embargo_note` **1**（`FactorResearch.vue:329`） |
| C7 | **1** 行（`:506`） |
| C8 | 生产 **1**；测试 **3** |
| C9 | **0** 行（`hac_std`/`ci_*` **不落库**） |

**计数与结论的关系（重要）**：C1/C2 的**出现集合**决定 A1b/A2b 的扫描面；
若实测与期望不符，须在报告附录 A 记「我的冻结期望错误」并**不得**据此调整判定词。

---

## §9 明确不做的事（scope 与禁止项）

1. **不改任何生产代码**（`modules/backend-java/src/main/**`、`modules/web/**` 一行不改）。
2. **不改任何测试代码**；不新增进主测试套件的测试类
   （试验性执行只允许放在 `research/paper_loop/experiments/R007_uncertainty_audit/probe/` 下）。
3. **不改** `rounds/R005_prereg.md` / `rounds/R006_prereg.md`（均已冻结）。
4. **不修** R005 的 A4b / A9 与 R006 的 `D-R006-03` / `D-R006-04` / `D-R006-05`；
   **不重开** R001，**不重试** R002，**不重估** R003 的 `r*`，**不改写** R004 的六轴与 R006 的 20 个断言。
5. **不得**把 R005 的 `REJECTED` 叙述为「发现了未来函数」；**不得**把 R006 的 `REJECTED`
   叙述为「IC 算错了 / IC 太低」。
6. **不得**用本论文翻 `D-R006-02`（÷T vs ÷(T−1)）的案：本论文的 σ 是**归一到 15% 的常数**，
   对「样本标准差分母」零陈述（`R007_review.md` §7.2）。
7. **禁止**任何「因子显著性」叙述（`R007_review.md` §7.3 负面清单）：
   不得写「项目因子不显著」「CI 覆盖不到 0 才算有效因子」「按 3.0 门槛项目因子都不成立」。
   `rule_score` 是 **0–100 截面百分位，不是概率**。
8. **不做**任何 §7 保留权限内的改动：不改默认策略、不改权重、不改质量闸阈值、不改交易/风控/仓位、
   不改 `ResearchConvention`、**不改公开 API/前端契约/库表结构**、不提升影子因子、不引入新数据源、
   不启用真实交易、不删除历史数据。
9. 所有发现**只进** `decision_queue.md` / `blocked_queue.md` / `shadow_registry.md` 与报告，
   **不落地为代码修改**。
10. 本轮**不产生任何影子因子**（不适用：无新因子）。

---

## §10 预注册的预测（P）—— 供事后检验，不得事后修改

| ID | 预测 | 依据 |
|---|---|---|
| **P-1** | `A1a`：`[mean ± 1.96·hac_std]` ≠ 生产 `ci_*` ⟹ 判定词 `NOT_VIOLATED` | 两条不同算法 |
| **P-2** | `A1b` = `CONTRACT_MISLABEL` | `FactorResearch.vue:139/:322` |
| **P-3** | `A2a` = `VIOLATED`（`K1` 触发） | `:325-327` + `:359-361` + 无标记字段 |
| **P-4** | `A2b` = `REGISTERED_ONLY`，且 `cross_section_days == 0` 提供弱可区分性 | 读码 |
| **P-5** | `A2c`：**不预设**触发与否；我预期 `n = 5` 有非零发生率、`n = 17` 很低 | 算术直觉，**可被我自己的实验证伪** |
| **P-6** | `A2d` = `CONTRACT_AMBIGUOUS` | `:663-674` |
| **P-7** | `A3b` = `NOT_VIOLATED`（逐位等于 `cbrt` 滞后规则） | `:334-335` |
| **P-8** | `A3c`：**不预设**；若触发，最可能在 `MA_equal_{h−1}` 与 `AR1_0.7` 的 `h ≥ 5` 单元 | 上文的粗略算术 |
| **P-9** | `A4b` = `CONTRACT_AMBIGUOUS` | `:355` vs `:366` |
| **P-10** | `A5a` = `NOT_VIOLATED`（**且与既有测试同断言 ⟹ 不构成新发现**） | `FactorResearchEngineTest:145-153` |
| **P-11** | `A5c`：**不预设**；若触发，预期出现在 `ARM_HAC` 的小 `n` 单元（钳位）或 `MA_equal_{h−1}` 的 `h ≥ 5` 单元 | 算术 |
| **P-12** | `A6a` = `REGISTERED_ONLY`（0 处 nominal 标注） | 全仓未见 |
| **P-13** | `A7a` = `NOT_VIOLATED`；`A7b` = `NOT_REACHABLE` | `:191` 常量 |
| **P-14** | `A8a` = `CONTRACT_MISLABEL`；`A8b` = `REGISTERED_ONLY` | `:506/:510` |
| **P-15** | 总判定 **不是** `NOT_VIOLATED`（即至少 `CONTRACT_MISLABEL` 级） | P-2 与 P-14 |
| **P-16** | 测试套件计数**不变**（Java 350 / Python 343），因**零生产代码 / 零测试代码改动** | §9.1 / §9.2 |

**P-15 / P-16 的风险声明（诚实前置）**：若实测 K1/K2/K3 **全部未触发**，
则 H0 **存活**，必须如实报告，**不得**为了兑现 P-3 而放宽 A2a 的判据；
同理若 A1b 的标签在实测中出现 `Rank`/`bootstrap` 限定词，必须改判 `NOT_VIOLATED`。

---

## §11 交付物

| 文件 | 内容 |
|---|---|
| `experiments/R007_uncertainty_audit/probe/R007Probe.java` | 只调用**生产类**的探针 `main`（含覆盖率模拟），输出 JSON 行 |
| `experiments/R007_uncertainty_audit/run_uncertainty_audit.py` | 编排：编译探针 → 运行 → CHECKERS 判定 → 金丝雀 → C1..C9 计数 → 写 `result.json` |
| `experiments/R007_uncertainty_audit/config.json` | 轴/断言/变体/网格/DGP/种子定义（冻结副本） |
| `experiments/R007_uncertainty_audit/result.json` | 逐断言结果 + `by_axis` + 行和校验 + 金丝雀台账 + 覆盖率 80×2 **单元级**结果 |
| `experiments/R007_uncertainty_audit/run.log` | append 模式，保留**全部**运行（含失败运行，不删除） |
| `rounds/R007_report.md` | 报告 + **附录 A 勘误** + §10 测试与 hygiene + 提交信息回填 |
| `paper_ledger.csv` / `progress.md` / `decision_queue.md` / `blocked_queue.md` / `shadow_registry.md` | 台账更新 |

**冻结时间**：2026-09-14（本文件提交即生效）。
