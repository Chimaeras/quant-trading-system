# R006 预注册（冻结）

> **冻结声明**：本文件在**任何实验代码**、**任何生产代码**、**任何测试代码**之前落盘。
> 冻结后**不得**因看到结果而放宽或改写任何判据、阈值、网格、判定词表。
> 事后更正只能写入 `rounds/R006_report.md` 的**附录 A 勘误**，并明确标注是事后更正。
> **本文件一旦提交即视为冻结版本**；若必须修改，须在报告附录 A 记录「改了哪一行、为什么、何时」。

- 轮次：**R006**（审计轮，与 R004 / R005 同型）
- 判据来源论文：**AlphaEval**（arXiv:2508.13174v2，KDD 2026）⟹ 审查见 `rounds/R006_review.md`
- 载体与哈希见 `rounds/R006_review.md` §1 / §9
- 被审计对象：**项目自己命名的 `rank_ic` 载荷族**（`FactorResearchEngine` → `FactorResearchService` / `ScoringBacktestService` → 前端展示标签）

---

## §1 被检验的主张（H0）与立场

**H0（被检验主张，由代码与契约隐含持有，非论文提出）**：
> 载荷键 `rank_ic_mean` / `rank_ic_std` / `rank_ic_ir` / `rank_ic_positive_rate` / `daily_rank_ic` /
> `pearson_ic_mean` / `cross_section_days` 所命名的量，**忠实对应**其在量化文献中的定义：
> RankIC = 各期 Spearman 秩相关的均值；IC = 各期 Pearson 相关的均值；
> **ICIR = 均值 / 样本标准差（÷(T−1)）**；且当定义**失效**（标准差为 0、组为空、无有效观测）时，
> **不输出一个看起来像估计值的具体实数**。

**立场（承接协议「默认证伪」）**：**假设 H0 不成立**，本轮的默认动作是**杀死它**。
若能杀，判 `REJECTED`；若杀不掉，如实报 `NOT_VIOLATED` / `INCONCLUSIVE`，
**不得**为了产出正面结论而放宽判据，**也不得**因为「连续多轮无产出一致性的负面结果」而抬高或压低判据。

**本 H0 与论文的关系**：论文**不是** H0 的作者——H0 是**项目自己的命名**在文献定义下被检视。
论文只提供**判据**。这一点必须与 R005 同构地保持（R005 的判据来自 KN2022，H0 是项目自己的 PIT 约定）。

---

## §2 判据分档（本轮强制逐行标注）

> 承接 `R006_review.md` §8：判据表必须逐行标出来源档位，**严禁把自建的算术事实记成论文的主张**。

| 档 | 含义 | 本轮适用位置 |
|---|---|---|
| **L（文献判据）** | 论文正文有**式号或明示陈述**支持 | 式 (5)(6) IC=Pearson 均值；式 (7)(8)(9) RankIC=Spearman 均值；式 **(18) ICIR 分母 ÷(T−1)**（且式 (22) 的 σ_r 同用 T−1 ⟹ 论文**内部一致**）；Table 1 的 `IC, ICIR` 与 `RankIC, RankICIR` **并列区分**；式 (5)/(7) 的 `1/T Σ_{t=1}^{T}` 是对**全部 T 期**取均值 |
| **A（算术推导）** | 论文**未**说，是数学等价/不等价的事实 | 式 (8) 的 `1 − 6Σd²/(N(N²−1))` **仅当无并列**时等于 Spearman ρ；`ic` 有限 ⟺ `pearson` 有限（同为「零方差 ⟹ NaN」） |
| **P（项目自建）** | 项目契约/工程约定，与文献无关 | 「定义失效时不得输出具体实数」= H0 的后半句，属**工程契约**，非文献要求 |

**对 L 档的一条自我约束**：论文**不给任何数值阈值**（`R006_review.md` §2.5 / §6.1）。
⟹ 本轮**不允许**出现「IC 太小 / ICIR 不够高」这类判定。
所有可执行断言都是**结构型或算术型**（= 与 ≠、是否可见、分母是 T 还是 T−1、某组是否为空）。

---

## §3 轴清单

共 **8 条轴 / 20 个可执行断言**。断言 ID 与轴 ID 在下文固定，报告与 `result.json` 必须使用同一套 ID。

### A1 — 命名族归属：`rank_ic_ir` 是 RankICIR 还是 ICIR？（判据 L）

**事实基础（读码，非预测）**：`FactorResearchEngine.java:170-174`
`icSeries = daily.stream().map(DailyRankIc::rankIc)`（**rank IC 序列**）⟹ `icMean/icStd` **同源于 rank IC**。

| 断言 | 内容 | 判定口径 |
|---|---|---|
| **A1a** | 用**注入的**日序列（令 Pearson 与 rank 明显分歧）重算两种 IR，确认载荷 `rank_ic_ir` 等于**基于 rank 序列**的那一个 | 等于则 `NOT_VIOLATED`（数值自洽） |
| **A1b** | 该量被绑定的**展示标签**里是否含 `Rank` 限定词？扫描前端与载荷键名 | 无 `Rank` 限定 ⟹ `CONTRACT_AMBIGUOUS` |
| **A1c** | 是否存在把 `pearson_ic_mean` 当分子的**并行 ICIR**？（若 `ICIR = pearson_ic_mean / rank_ic_std` 被任何输出/文档表述，即为**混合口径**） | 存在 ⟹ `CONTRACT_AMBIGUOUS` |

**A1 不是数值 bug**：分子分母同源，数值自洽。A1 的结论只能是命名/文档类。

### A2 — Spearman 与式 (8) 的等价性（判据 L + A）

| 断言 | 内容 | 判定口径 | 预期 |
|---|---|---|---|
| **A2a** | **无并列**输入上，`spearmanRankCorrelation` 必须与式 (8) `1 − 6Σd²/(N(N²−1))` 一致 | 容差 **1e-12** | `NOT_VIOLATED` |
| **A2b** | **有并列**输入上，项目实现（平均秩 + Pearson，`ranks()` at `:271-292`）应**仍等于**真 Spearman，而**式 (8) 会偏离** | 报告式 (8) 的偏离量（绝对值） | `NOT_VIOLATED`（**正面发现**） |

**A2b 的性质声明（L/A 分离的示范）**：论文式 (8) **未加**任何并列限定语，且全文 `\bties?\b` 零处讨论并列
（`R006_review.md` §2.5 / §5.2）。因此 A2b 是 **A 档（算术推导）**，**不得**写成「论文要求」。

### A3 — ICIR 分母：T−1（文献） vs T（项目）（判据 L）

**事实基础（读码）**：`FactorResearchEngine.java:172`
`icVar = icSeries.stream()...average()` ⟹ **总体方差 ÷T**；`:174` `icIr = icMean / icStd`。

| 断言 | 内容 | 判定口径 |
|---|---|---|
| **A3a** | 用载荷自带的 `daily_rank_ic` 序列，分别以 ÷(T−1) 与 ÷T 重算 IR，判断 `rank_ic_ir` 等于**哪一个** | 等于 ÷T ⟹ `CONVENTION_DIVERGENCE` |
| **A3b** | 两口径之比必须**恰为** `sqrt(T/(T−1))` | 偏差 > 1e-9 ⟹ `INCONCLUSIVE`（我的代数错了） |
| **A3c** | `rank_ic_std` 同为总体标准差（分子同上） | 与 A3a 同判 |

**约定网格必须含「缩放/量纲」维（R005 教训 22）**：A3 的网格为
`分母 ∈ {T, T−1}` × `年化 ∈ {×1, ×sqrt(252)}` × `来源序列 ∈ {rank IC, Pearson IC}` = **8 个单元**，
必须**逐单元**计算并列出，**不得**只报最佳单元。

**量级诚实标注（冻结）**：T=17 时 `sqrt(17/16) = 1.030776…` ⟹ **+3.08%**；T=10 时 `+5.41%`。
**A3 的偏离量级微弱，单独绝不足以判 `REJECTED`。**

### A4 — 静默丢日与「对结果取选择」（判据 L）

**事实基础（读码）**：`FactorResearchEngine.java:149-152`
```java
double ic = spearmanRankCorrelation(scores, rets);
if (!Double.isFinite(ic)) { continue; }   // ← 该日整体消失
```
之后 `:193-211` 的 `daily.size()` 同时充当 `crossSectionDays`、`positive_rate` 的分母、以及
`icMean` / `icStd` / `icIr` 的 T。

| 断言 | 内容 | 判定口径 | 预期 |
|---|---|---|---|
| **A4a** | 注入 `K` 个**退化日**（分数在该截面恒定 ⟹ 秩零方差 ⟹ IC 非有限）+ `M` 个正常日，`cross_section_days == M`（**而非 M+K**），且**输出中不存在任何「被丢弃天数」字段** | 存在丢弃且无该字段 ⟹ `VIOLATED` | `VIOLATED` |
| **A4b** | `pearson_ic_mean` 与 `rank_ic_mean` 的**分母是否相同**（代码对 Pearson 另有 `isFinite(pic)` 过滤，`:155-157`） | 不同 ⟹ 报告；相同 ⟹ `NOT_VIOLATED` | **`NOT_VIOLATED`（我的算术预测：恒相同）** |

**A4b 的预注册算术预测（可被我自己的实验证伪）**：
`var(scores)=0` 或 `var(rets)=0` ⟺ 秩零方差 ⟺ `ic` 非有限 ⟺ `pic` 非有限。
⟹ 两个 `isFinite` 过滤**丢弃的集合相同**，`dailyPearson.size() == daily.size()` **恒成立**，
该双重过滤**冗余但无害**。
**若 A4b 检查器能找到反例，则我的上述代数错误**，必须作为**我的错误**记入报告附录 A，
而不是当成项目缺陷。

### A5 — 空组伪造 `0` 进入 `long_short_spread`（判据 L：式 (19) 的 K 只腿）

**事实基础（读码）**：
- `groupedAverageReturns` `:102` `int g = Math.min(groupCount, n);` ⟹ **单日**只产出 `g` 组；
- `summarize` `:134-136` 预建 `1..groupCount` 的桶；`:180-183` 对 `1..groupCount` **全部**建组，
  **空桶** `avg = 0`、`observations = 0`；
- `:185-186` `spread = grouped.last.avgReturn − grouped.first.avgReturn` ⟹ 顶端空组时
  `spread = 0 − G1.avgReturn`（**符号反转**，成为「负的底层组平均收益」）。

| 断言 | 内容 | 判定口径 |
|---|---|---|
| **A5a** | 构造 `n < groupCount` 的输入，检查 `grouped` 元素数 == `groupCount`，且尾部存在 `observations == 0 && avgReturn == 0.0` 的组 | 存在 ⟹ 记事实 |
| **A5b** | 此时 `spread` **逐位等于** `0 − G1.avgReturn`（用载荷自带的 `grouped` 重算，容差 1e-12） | 成立 ⟹ `VIOLATED` |
| **A5c** | 空组是否**可被下游区分**（`observations == 0` 是否出现在实际返回的载荷 / DTO / 前端） | 不可区分 ⟹ 加重判定 |

**可达性（冻结结论，读码所得）**：
- `FactorResearchService` 的**三处**调用点（`:529`、`:747`、`:791`）**全部**传常量
  `RANK_IC_GROUP_COUNT` ⟹ **组数不可由请求控制** ⟹ A5 在该端点**不可达**。
- `ScoringBacktestService` `:78` `req.getFactorGroupCount() != null ? ... : 5`，
  而 `ScoringBacktestRequest.java:38` 的 `factorGroupCount` **无 `@Min`/`@Max`**。
- 守卫 `obs.size() < minCrossSectionSize` 且常量 = **10**（两个 Service 各自持有常量 10）
  ⟹ 参与统计的日必有 `n ≥ 10` ⟹ 空组**只能**由 `groupCount ≥ 11` 触发。
- 前端控件 `ScoringBacktest.vue:36` `:min="2" :max="10"` ⟹ **经界面不可达**，
  **经直接 API 调用可达**。
⟹ A5 的判定词为 `VIOLATED`，并在报告与 `decision_queue` 中**必须**写明「仅直接 API 可达」，
**不得**写成「用户可见」。

### A6 — 定义失效时伪造具体实数（判据 P + 跨轮同型）

| 断言 | 内容 | 判定口径 |
|---|---|---|
| **A6a** | 零有效观测时 `emptySummary`（`:214-219`）输出 `rank_ic_mean=0, rank_ic_std=0, rank_ic_ir=0, positive_rate=0, pearson_ic_mean=0, spread=0`，与「IC 恰好为 0」**数值不可区分** | 记事实；**同时如实记录弱可区分性**：`monotonicity="N/A"` 与 `universe_note` 提供了部分可区分信息 ⟹ **不得**夸大为「完全不可区分」 |
| **A6b** | `:174` `icIr = icStd > 1e-12 ? icMean / icStd : 0` ⟹ **std 为 0 时 IR 被写成 0**。数学上 std=0 且 mean≠0 时 IR **未定义（±∞）**；`mean < 0` 时真极限为 `−∞`，写成 `0` **把最差的因子写成中性（偏乐观）** | 能构造 `mean<0 && std==0` 且 `rank_ic_ir == 0.0` ⟹ `VIOLATED` |
| **A6c** | `:176` `pearsonIcMean = dailyPearson.isEmpty() ? 0 : ...` —— 与 A6a 同族 | 记事实 |
| **A6d** | `selector_result.ic_value`（`V2__research_tables.sql:183`，`DOUBLE DEFAULT NULL`）：全仓**唯一**出现处是 DDL，**无任何代码写入**，实测**非空 0 行 / 全表 67 行** ⟹ **死列** | 记事实（`DEAD_COLUMN`） |

**A6b 的强可达性（本轮最有力的一条）**：只需**一个**参与统计的截面日（`T=1`）即得 `icStd = 0`，
而此时 `rank_ic_mean` 仍显示该日的真实 IC 值。
⟹ 可能出现**同一份载荷里 `rank_ic_mean = −0.50` 而 `rank_ic_ir = 0.00`** 的自相矛盾配对。
`T=1` 只需一个满足 `n ≥ 10` 的截面日 ⟹ **经正常 API 可达**（可能经界面），
这一点**必须**与 A5 的「仅直接 API 可达」区分开。

### A7 — 单调性退化：全相等判为 `MONOTONIC_INCREASING`（判据 **P**，`REGISTERED_ONLY`）

`assessMonotonicity` `:226-249`：全相等序列使 `increasing` 与 `decreasing` **同时**为真，
而 `if (increasing)` 在前 ⟹ 返回 `MONOTONIC_INCREASING`。

| 断言 | 内容 | 判定口径 |
|---|---|---|
| **A7a** | 构造**组均收益全相等**且 `daily` 非空的输入（需 `var(rets) > 0` 否则 IC 非有限 —— 见 A4b 的等价性），确认返回 `MONOTONIC_INCREASING` 且各组收益**完全相同**（零区分度） | 记事实 |
| **A7b** | 该轴**不进入** K 判据 | 冻结：**`REGISTERED_ONLY`** |

### A8 — 标签口径差异（**范围声明，非判据**）

式 (1) 的 `close_{t+ΔT}` 口径 ⟹ 记录差异 **D-2**，
**不改** `ResearchConvention`、**不构成任何 kill 轴**（`R006_review.md` §5.3 / §7.1）。

---

## §3.1 描述性 vs 预测性（独立小节 —— R005 教训 E-4 的直接落实）

> R005 的 E-4 教训：预注册必须**显式写出**「哪些内容不具预测性」，否则事后无法辩护。

| 轴/断言 | 冻结时的状态 | 性质 |
|---|---|---|
| A1a/A1b/A1c | 读码已知 `icSeries` 是 rank 序列 ⟹ 归属已知 | **描述性** |
| A2a | 未实测 | **预测性** |
| A2b | 未实测（但算术上必然） | **预测性**（我预测 `NOT_VIOLATED`） |
| A3a/A3b/A3c | 读码已知 `:172` 用 `.average()` ⟹ **÷T 已知** | **描述性**（数值大小未测） |
| A4a | 读码已知 `continue` 存在 ⟹ **丢日已知**；「无丢弃计数字段」读码可知 | **描述性** |
| A4b | 未实测（我预测恒相同） | **预测性** |
| A5a/A5b | 读码可推 ⟹ 事实已知；**数值逐位相等**未实测 | **描述性**（数值未测） |
| A5c | 未实测（需查载荷/DTO/前端） | **预测性** |
| A6a/A6c/A6d | 读码已知；`ic_value` 行数**冻结时未查** | **半描述性**（A6d 的行数是预测性） |
| A6b | 读码已知 `:174`；**自相矛盾配对的可构造性**未实测 | **预测性** |
| A7a | 读码可推 | **描述性**（构造是否成功未测） |

**⟹ 本轮真正的证伪内容 = A2a + A2b + A4b + A5c + A6b + A6d(行数)。**
其余为**已读码事实的规格化登记**，报告中必须与预测性内容**分栏**呈现。

---

## §4 变体预算与行和交叉校验（防「预算膨胀」，沿用 R005 做法）

- **预算上限：20 个变体**（= 断言数）。**只允许减少，不允许增加**。
- 变体编号 `V01..V20`，与断言一一对应并**双向映射**（每个断言恰一个变体；每个变体恰一个断言）。
- **行和交叉校验（强制）**：`result.json` 必须写出
  `by_axis` 各轴变体数之和 == `total_variants` == 台账计数，
  并写出**逐轴计数表**（A1=3, A2=2, A3=3, A4=2, A5=3, A6=4, A7=2, A8=1 ⟹ 合计 **20**）。
  校验失败 ⟹ 立即停止并记 `INCONCLUSIVE`。
- **A8 的变体（V20）只做文本差异记录，不计入 K 判定**（见 §3.1）。
- 预算外的任何探针必须命名 `posthoc_*` 且**不参与** total verdict 合成（R005 E-5 的处置方式）。

## §5 判定词表（冻结，不得新增）

| 词 | 含义 |
|---|---|
| `NOT_VIOLATED` | 断言被检验后**未**发现违反（**是有效结果，含 A2b/A4b 这类正面发现**） |
| `VIOLATED` | 断言成立且构成**偏离文献定义或自身契约** |
| `CONVENTION_DIVERGENCE` | 与文献口径**不同但自洽**，且量级微弱 ⟹ 只登记、不作杀 |
| `CONTRACT_AMBIGUOUS` | 数值自洽但**命名/文档/展示**存在歧义 |
| `DEAD_COLUMN` | 结构存在、全仓无写入路径、实测零非空 |
| `NOT_REACHABLE` | 断言在当前 API/UI 可达面上**无法**触发（须附可达性论证） |
| `REGISTERED_ONLY` | 只登记、**明确不进入**杀判据 |
| `INCONCLUSIVE` | 证据不足 / 我的代数或工具出错 |

---

## §6 杀死条件（K）（**一经写下不得事后放宽**）

| ID | 条件 | 触发后判定 |
|---|---|---|
| **K1** | A2a `VIOLATED`（无并列输入上实现 ≠ 式 (8)） | `REJECTED` |
| **K2** | A3a `VIOLATED` ⟹ 记 `CONVENTION_DIVERGENCE` | **不单独构成 `REJECTED`**（量级微弱，§3 A3） |
| **K3** | A4a `VIOLATED`（静默丢日且无丢弃计数字段） | `REJECTED` |
| **K4** | A5b `VIOLATED`（空组伪造 0 进入 spread） | `REJECTED` |
| **K5** | A6b `VIOLATED`（std=0 且 mean<0 时 `rank_ic_ir == 0.0`） | `REJECTED` |
| **K6** | A1b 或 A1c 成立 | `CONTRACT_AMBIGUOUS`（**非** `REJECTED`） |
| **K7** | A6d 成立 | `DEAD_COLUMN`（**非** `REJECTED`） |
| **K8** | A7a 成立 | `REGISTERED_ONLY`（**非** `REJECTED`，且**不得**写入泄漏轴栏位） |
| **K9** | 行和交叉校验失败（§4） | 立即停止，整轮 `INCONCLUSIVE` |
| **K10** | 阳性对照未通过（§7） | 整轮 `INCONCLUSIVE`，**所有 PASS 作废**（R005 教训 7） |

**总判定合成规则（冻结）**：
1. 若 **K9 或 K10** 触发 ⟹ 总判定 `INCONCLUSIVE`，其余断言结果全部作废；
2. 否则若 **K1 / K3 / K4 / K5 任一** 触发 ⟹ 总判定 **`REJECTED`**（H0 被杀）；
3. 否则若 **K2** 触发 ⟹ 总判定 **`INCONCLUSIVE`**（有偏离但量级不足）；
4. 否则总判定 **`NOT_VIOLATED`**（H0 存活，**不得**叙述为「项目有问题」）。

**H0 的完整存活条件（同样冻结）**：K1/K3/K4/K5 **全部**未触发。
**不得**在报告里用「部分成立」「基本符合」这类模糊表述替代上述四条的逐条结论。

---

## §7 阳性对照与防掩码门（R005 教训 7 / 21 的落实）

### G3 门：断言对象必须**就是**生产类产生的对象

- 探针必须**加载仓库自己的** `modules/backend-java/target/classes` 下的
  `com.quant.service.backtest.FactorResearchEngine`，并在启动时打印
  `FactorResearchEngine.class.getProtectionDomain().getCodeSource().getLocation()`；
  该路径**不包含**目标目录 ⟹ **立即中止**（`INCONCLUSIVE`）。
- **禁止**在探针或 Python 侧**重新实现** Spearman / Pearson / 分组 / IR。
  任何重实现都只能作为**对照值**出现，且**必须**与生产实现同输入并列打印，
  一旦发现「生产实现未被调用」即判 `K10` 失败。
  ⟹ 这是 R005 教训 7（`is` 断言）在本轮（跨语言）的等价物：
  **判据对象必须是同一个类加载器加载的同一个类**。

### 金丝雀对（INJ / CLN），必须**成对**存在

| 金丝雀 | 构造 | 期望 |
|---|---|---|
| **CLN-1** | 干净的、无并列的等比构造输入 | A2a 检查器**不**触发 |
| **INJ-1** | 把 A2a 检查器接到**人为改动一位**的对照值（模拟实现 bug） | A2a 检查器**必须**触发 |
| **CLN-2** | 全部组非空、`T ≥ 2`、IC 有限 | A5b / A6b 检查器**不**触发 |
| **INJ-2** | 手工构造 `grouped` 使顶端组为空 | A5b 检查器**必须**触发 |
| **INJ-3** | 手工构造 `mean<0 && std==0` 的日序列 | A6b 检查器**必须**触发 |

**检查器注册表（CHECKERS）**：每个断言 ID 在代码中**恰有一个**注册项
`CHECKERS["A2a"] = fn`，且**每个**检查器都必须有 **INJ + CLN 两条金丝雀**；
缺任一条 ⟹ `K10` 失败 ⟹ 整轮 `INCONCLUSIVE`。
`result.json` 必须写出「检查器数 / 金丝雀数 / 无金丝雀的检查器列表（必须为空）」。

---

## §8 计数断言与可复算命令（R005 教训 15 / 23）

> 规则：预注册里任何计数断言**必须**附**可复算命令**，
> 且命令**不得**使用 `head` / `tail` / `-c`（截断型命令会造成 R004-E-4 型误判）。

| ID | 计数断言 | 可复算命令（完整、无截断） |
|---|---|---|
| C1 | `ic_value` 在全仓**恰好 1 处**出现（即 DDL 行） | `grep -rn "ic_value" --include=*.java --include=*.sql --include=*.vue --include=*.js --include=*.ts --include=*.py .` |
| C2 | 全项目 `rank_ic_ir` 的出现**全集**（用于 A1b，禁止截断） | `grep -rn "rank_ic_ir" --include=*.java --include=*.vue --include=*.js --include=*.ts .` |
| C3 | `rank_ic_std` ⇄ `pearson_ic_mean` 的展示标签共现（用于 A1b/A1c） | `grep -rn "rank_ic_std\|pearson_ic_mean\|rank_ic_mean" --include=*.vue --include=*.java modules/web modules/backend-java/src/main` |
| C4 | `factorGroupCount` 的全部出现（用于 A5 可达性） | `grep -rn "roupCount" --include=*.java modules/backend-java/src` |
| C5 | `selector_result` 行数与非空 `ic_value` 行数 | `SELECT COUNT(*) AS total, COUNT(ic_value) AS non_null FROM selector_result;` |
| C6 | 三处 `FactorResearchService` 调用点传的组数常量 | `grep -n "RANK_IC_GROUP_COUNT\|RANK_IC_MIN_CROSS_SECTION_SIZE" modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java` |
| C7 | 两个 Service 的截面下限常量值必须**都是 10**（A5 可达性依赖此） | `grep -rn "MIN_CROSS_SECTION_SIZE *=" --include=*.java modules/backend-java/src/main` |
| C8 | 前端组数控件上下限（用于 A5「经界面不可达」） | `grep -n "factorGroupCount" modules/web/src/views/backtest/ScoringBacktest.vue` |

**冻结的期望值（供比对，事后不得改）**：C1 ⟹ 1 行（`V2__research_tables.sql:183`）；
C5 ⟹ total 67 / non_null 0；C7 ⟹ 两处均为 10；C8 ⟹ `:min="2" :max="10"`。

**计数与结论的关系（重要）**：C1/C5 的**行数**决定 A6d 的 `DEAD_COLUMN` 判定；
若实测非零，则 A6d 判 `INCONCLUSIVE` 并在报告附录 A 记「我的读码结论错误」。

---

## §9 明确不做的事（scope 与禁止项）

1. **不改任何生产代码**（`modules/backend-java/src/main/**`、`modules/web/**` 一行不改）。
2. **不改任何测试代码**；不新增进主测试套件的测试类
   （试验性执行只允许放在 `research/paper_loop/experiments/R006_rank_ic_audit/probe/` 下）。
3. **不改** `research/paper_loop/rounds/R005_prereg.md`（R005 已冻结）。
4. **不修** R005 的 A4b / A9 发现，**不重开** R001，**不翻** R002 的案，**不重估** R003 的 `r*`，
   **不回改** R004 的六轴（尤其 K2 的「半触发」不得改成单段读法）。
5. **不得**把 R005 的 `REJECTED` 简化叙述为「发现了未来函数」（R005 的 H1a **未被杀死**）。
6. **不得**用式 (1) 覆盖 `ResearchConvention`，**不得**用式 (19)(20) 翻 `D-R003-01` 的案（`R006_review.md` §7）。
7. **不做**任何 §7 保留权限内的改动：不改默认策略、不改权重、不改质量闸阈值、不改交易/风控/仓位、
   不改 `ResearchConvention`、**不改公开 API/前端契约/库表结构**、不提升影子因子、不引入新数据源、
   不启用真实交易、不删除历史数据。
8. 所有发现**只进** `decision_queue.md` / `blocked_queue.md` / `shadow_registry.md` 与报告，
   **不落地为代码修改**。
9. 本轮**不产生任何影子因子**（不适用：无新因子）。

---

## §10 预注册的预测（P）—— 供事后检验，不得事后修改

| ID | 预测 | 依据 |
|---|---|---|
| **P-1** | `A2a` = `NOT_VIOLATED` | 平均秩 + Pearson 在无并列时与式 (8) 等价 |
| **P-2** | `A2b` = `NOT_VIOLATED`，且式 (8) 在有并列时偏离量 **> 0** | 算术 |
| **P-3** | `A3a` = `CONVENTION_DIVERGENCE`（等于 ÷T） | `:172` 用 `.average()` |
| **P-4** | `A4a` = `VIOLATED`（`cross_section_days` 不含退化日，且无丢弃字段） | `:150-152` |
| **P-5** | `A4b` = `NOT_VIOLATED`（两侧分母恒相同） | 零方差等价性 |
| **P-6** | `A5b` = `VIOLATED`，且 `spread == 0 − G1.avgReturn` 逐位成立 | `:185-186` |
| **P-7** | `A6b` = `VIOLATED`，且由 `T=1` 输入**即可**构造出 `mean<0 && ir==0.0` | `:174` |
| **P-8** | `A6d` = `DEAD_COLUMN`（非空 0 行） | `V2__research_tables.sql:183` + 无写入路径 |
| **P-9** | 总判定 = **`REJECTED`**（因 K3 / K4 / K5 中至少一条触发） | 上表 |
| **P-10** | 测试套件计数**不变**（Java 350 / Python 343），因**零生产代码改动** | §9.1 |

**P-9 的风险声明（诚实前置）**：若实测 K3/K4/K5 **全部未触发**，则总判定为
`NOT_VIOLATED` 或 `INCONCLUSIVE`，**必须如实报告**，**不得**为了兑现 P-9 而放宽任何判据。

---

## §11 交付物

| 文件 | 内容 |
|---|---|
| `experiments/R006_rank_ic_audit/probe/R006Probe.java` | 只调用**生产类**的探针 `main`，输出 JSON 行 |
| `experiments/R006_rank_ic_audit/run_rank_ic_audit.py` | 编排：编译探针 → 运行 → CHECKERS 判定 → 金丝雀 → C1..C8 计数 → 写 `result.json` |
| `experiments/R006_rank_ic_audit/config.json` | 轴/断言/变体/网格定义（冻结副本） |
| `experiments/R006_rank_ic_audit/result.json` | 逐断言结果 + `by_axis` + 行和校验 + 金丝雀台账 |
| `experiments/R006_rank_ic_audit/run.log` | append 模式，保留**全部**运行（含失败运行，不删除） |
| `rounds/R006_report.md` | 报告 + **附录 A 勘误** + §12 提交信息回填 |
| `paper_ledger.csv` / `progress.md` / `decision_queue.md` / `blocked_queue.md` / `shadow_registry.md` | 台账更新 |

**冻结时间**：2026-09-14（本文件提交即生效）。
