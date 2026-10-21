# R007 论文审查：Harvey–Liu–Zhu（NBER WP 20592, 2014）

> 本轮**不是**策略复现轮，而是**审计轮**（与 R004 / R005 / R006 同型）：
> 论文作**判据来源**，被检验的对象是**项目自己命名的统计不确定性载荷族**
> （`hac_std` / `ci_lower` / `ci_upper`，及前端标签「HAC 95%CI」）。
> 预注册：`rounds/R007_prereg.md`（**先于任何实验代码冻结**）｜ 报告：`rounds/R007_report.md`

---

## 1. 身份核验

| 项 | 实测值 | 证据 |
|---|---|---|
| 标题 | *……and the Cross-Section of Expected Returns*（页首印作 `. . . AND THE CROSS-SECTION OF EXPECTED RETURNS`） | PDF 首页 |
| 作者 | Campbell R. Harvey（Duke / NBER）、Yan Liu（Texas A&M）、Heqing Zhu（University of Oklahoma） | PDF 首页 |
| 载体 | **NBER Working Paper No. 20592**，`http://www.nber.org/papers/w20592` | PDF 首页 |
| 日期 | **October 2014** | PDF 首页 |
| JEL | `C01, C58, G0, G1, G12, G3` | PDF 首页 |
| 发表版本 | **未在本地载体中核实**。本 PDF 全篇**无任何期刊卷期/DOI 信息**，只有 NBER WP 头。⟹ 本轮**只**引用本载体的内容，**不**引用任何「已发表版」表述 | 全文检索 `doi` / 期刊名均无 |
| 本地载体 | `papers/hlz2014_nber_w20592.{pdf,txt}` | PDF **677,490 B**；`sha256 = 15387ff6505c7eafdf18a89b63883b130a1497aaa41a9160d260a5ba045dfcef` |
| `.txt` | PyMuPDF 抽取，**267,919** 字符（UTF-8 带 BOM） | `sha256 = 3fe55b856694754cf693bcbb00d07027f1ad5b44679d7e125e8c0f6421a8fa45` |
| 页数 | **101**（`/Type /Page` 正则计数 101、PyMuPDF `page_count` 101，两者一致） | 与 R006 载体的 `/Count` 异常不同，**本载体无 `/Count` 键**，无计数分歧 |

**身份核验过程（含一次真实失误，如实记录）**：我先按**记忆**下载了 NBER WP **20455**，以为它是 Harvey–Liu 的
"Backtesting"；解出首页后发现是 *EXPORT MARKETS AND LABOR ALLOCATION IN A LOW-INCOME COUNTRY*（McCaig & Pavcnik，87 页），
与预期完全不符 ⟹ **删除错件**，改用 NBER 官方 JSON 检索端点
（`nber.org/api/v1/working_page_listing/contentType/working_paper/_/_/search?q=...`）定位到 20592，
并**从首页逐字核对标题/作者/编号**后才使用。
⟹ 记入本轮过程教训：**NBER 工作论文编号不得凭记忆使用，必须回首页核验**（与 `progress.md` §4 的载体勘误同族）。

**与 R005 / R006 的一条做法差异（主动披露）**：R005 载体只有 v1；R006 载体是 arXiv v2。
本轮是**工作论文版**（NBER WP），**未核对已发表版**，因此凡论文与已发表版可能有差异之处，
本轮一律以本载体的字符为准，并**不主张**「已发表版也是这样」。

---

## 2. 论文内容

### 2.1 核心主张

金融经济学中已有数百个「发现」的因子。给定如此大规模的数据挖掘，
**对「新发现因子」套用通常的显著性门槛（例如 t-ratio > 2.0）在经济与统计上都说不通**。
论文提出一个**多重检验框架**（FWER / FDR / BHY），给出 1967 年至今 t-ratio 门槛的历史序列，
并外推 20 年。结论：新因子需要 **t-ratio > 3.0**（摘要逐字），
且「金融经济学中大多数被声称的研究发现很可能是错的」。

### 2.2 论文的**分析单位**（本轮最关键的一条，必须先说清）

论文的**分析单位是「因子」的横截面**（316 个已发表因子的 t-ratio 样本），
它研究的是**t-ratio 的横截面分布**与其多重检验校正；
**不是**单个因子在**自己的时间序列上**的置信区间构造。

⟹ 这条决定了本轮判据的**上限**：本论文**不能**为本项目的「单个因子的 IC 均值置信区间」提供任何
逐字定义、任何估计量、任何区间算法。它能提供的只有 **§5 表中 L 档那五条**。
**任何人（包括我）不得把本论文当作「CI 构造规范」来用。**

### 2.3 与本轮直接相关的**逐字定义**

> **t-stat（5.1 节，PDF p.29–30；印本**未编号**，检索式号时须按 5.1 节定位）**：
> *"let the normalized strategy return in period t for the i-th discovered strategy be X_{i,t}.
> Then the t-stat for testing the significance of this strategy is:*
> `T_i = ( Σ_{t=1}^{N} X_{i,t} / N ) / ( σ / √N )`。
> *Assuming joint normality and **zero serial correlation** for strategy returns,
> this t-stat has a normal distribution* `T_i ∼ N( µ_i/(σ/√N), 1 )`"

> **σ 的性质（同节，必须先读）**：*"suppose researchers construct a corresponding long-short trading
> strategy and **normalize the return standard deviation to be σ = 15% per year**"*，
> 脚注 46：*"Notice that this assumption is not necessary for our approach. Fixing the standard deviations
> of different strategies eliminates the need to separately model them…"*
> ⟹ 该式里的 σ 是**归一到 15% 的已知常数**，**不是**从样本估出来的标准误。
> ⟹ **论文没有给出任何「估计标准误」的做法**（本轮 §6 第 3 条）。

> **1.96 ↔ 5%**：*"The overwhelming majority of t-ratios exceed the **1.96 benchmark for 5% significance**."*

> **对序列相关的要求（披露规范，逐字）**：*"we take published t-ratios as given. That is,
> we assume they are **econometrically sound with respect to the usual suspects
> (data errors, coding errors, misalignment, heteroskedasticity, autocorrelation, outliers, etc.)**."*
> 脚注 49：*"**To incorporate the serial correlation for individual strategies**,
> we can model them as simple autoregressive processes."*

> **多重检验门槛（逐字，三处）**：
> 摘要 *"it does not make any economic or statistical sense to use the usual significance criteria
> for a newly discovered factor, e.g., a t-ratio greater than 2.0"*；
> 摘要 *"a newly discovered factor needs to clear a much higher hurdle,
> with a **t-ratio greater than 3.0**"*；
> 5.2 节 *"our estimates show that in general **a t-ratio of 3.9 and 3.0** is needed to control
> **FWER at 5% and FDR at 1%**, respectively"*，并给出 *"3.78 (Holm adjustment that controls FWER at 5%)
> and 3.38 (BHY adjustment that controls FDR at 1%)"*。

> **对 bootstrap 的态度（逐字）**：*"recent research in finance explores **bootstrap procedures to assess
> the statistical significance of individual tests**. Most of these studies focus on mutual fund evaluation.
> They **bootstrap the time-series** of mutual fund returns and obtain an **empirical distribution for the t-ratio**."*
> ⟹ 论文承认「对时间序列做 bootstrap 以获得检验统计量的经验分布」是**既有做法**，
> 但它自己**不做**这件事（它自己 bootstrap 的是 t-ratio 样本，用于补缺失数据，属另一用途）。

### 2.4 论文的自我限制（必须同量级记录，否则会高估判据强度）

- **不给估计量**：σ 是归一化常数；论文不构造、不比较任何标准误估计量（HAC 与否均无）。
- **不定义置信区间**：全文 `confidence interval` 命中 **0**（大小写均 0）。
- **不提 Newey–West**：全文 `Newey` / `newey` 命中 **0**（⟹ 核函数、滞后规则、Bartlett 权重一律**无文献判据**）。
- **不做覆盖率研究**：不检验任何区间的实际覆盖率。
- **不涉及 A 股、不涉及 IC**：论文的 X 是「多空策略收益」，不是截面 IC；无涨跌停/停牌/T+1/成本。
- **门槛是横截面结论**：3.0 / 3.9 是「以 316 个因子为样本、估计多重检验模型」得出的，
  **不是**「任意一个因子的 IC 区间必须达到某个宽度」的阈值。**不得**把它当合格线套到单因子区间上。

### 2.5 关键词计数（可复算，冻结于本文件）

| 词 | 命中数 |
|---|---|
| `Newey` / `newey` | **0 / 0** |
| `confidence interval`（含首字母大写） | **0 / 0** |
| `zero serial correlation` | 1 |
| `autocorrelat`（不区分大小写） | 3 |
| `econometrically sound` | 1 |
| `1.96` | 27 |
| `t-ratio` | 222 |
| `t-statistic` | 34 |
| `bootstrap` / `Bootstrap` | 6 / 3 |
| `multiple testing` | 65 |
| `overlapping` | **1** |

复算命令（**必须显式声明扫描根**，R006 E-3 教训）：
```
cd E:/Project/quant-trading-system/research/paper_loop/papers
python -c "import re;t=open('hlz2014_nber_w20592.txt',encoding='utf-8',errors='replace').read();[print(repr(k),len(re.findall(re.escape(k),t))) for k in ['Newey','newey','confidence interval','Confidence interval','zero serial correlation','autocorrelat','econometrically sound','1.96','t-ratio','t-statistic','bootstrap','Bootstrap','multiple testing','overlapping']]"
```

---

## 3. 严谨性评估（沿用 R005/R006 的三档口径）

| 维度 | 评级 | 依据 |
|---|---|---|
| **证据强度** | **强** | 316 个已发表因子的 t-ratio 样本；三种多重检验校正（Bonferroni / Holm / BHY）并列；给出历史门槛序列与 20 年外推；NBER 工作论文 |
| **形式化** | **中** | t-stat 定义式**在印本中未编号**，且写在「σ 归一到 15%」的结构模型小节内；核心假设（zero serial correlation / joint normality）是**明示假设**而非结论 |
| **外部有效性** | **对本轮而言：低—中** | 论文是**美股因子横截面**研究，无 A 股、无 IC、无交易成本、无 T+1 ⟹ 第 5/6 问只能提供**方法论层面的**对齐，**不能**提供口径层面的对齐 |

**与 R006 的对照（诚实标注）**：R006 的 AlphaEval 直接是 A 股量化评估、直接定义 IC/RankIC/ICIR，
所以 R006 能拿到**公式级**判据。本轮载体是**方法论/统计推断**类论文，
提供的是**门槛与披露规范**，不是公式。
⟹ **本轮 L 档判据比 R006 更少、更弱**，这是选择本载体时就已知的代价；
换取的是：可以对 **1.96 / 多重检验 / 序列相关义务** 这三条给出真实的文献依据，
而这三条恰好是 R006 完全没有覆盖的。

---

## 4. 适配性七问

| # | 问题 | 结论 | 说明 |
|---|---|---|---|
| 1 | 是否依赖做空或对冲工具？ | **不命中（仅概念层）** | 论文用多空策略收益定义 t-stat，但本轮的判据**只**用其**统计推断**部分（1.96、多重检验、序列相关义务），**不**要求项目构造多空组合 |
| 2 | 是否依赖日内/高频或 T+0？ | **不命中** | 论文是月/年级别的因子检验 |
| 3 | 是否依赖做市/流动性假设或小票低滑点？ | **不命中** | 论文无成本与流动性设定 |
| 4 | 是否使用本项目不具备的数据？ | **不命中** | 本轮判据只需「一条日频 IC 序列 + 项目自己的估计量」 |
| 5 | 结论是否来自特殊样本期？ | **不命中（但须披露）** | 论文的 3.0/3.9 门槛来自其 1967–2012 美股因子样本与外推；**本项目不得把它当合格线**（§2.4 最后一条） |
| 6 | 所需数据是否在现有数据湖中存在且 PIT 可用？ | **不命中** | 本轮为**纯统计性质审计**，用**注入序列**驱动生产函数，不读数据湖 |
| 7 | 换手率 × 成本后是否仍有正期望？ | **不适用** | 本轮不产生任何策略、不做任何收益主张；**本轮不输出任何「因子好不好」的结论** |

**结论**：七问**无一致命命中** ⟹ 判据来源**可进入实现**。

---

## 5. 可映射判据表（本轮冻结的判据来源，**逐行标档**）

> 档位定义见 `R006_prereg.md` §2：**L** = 论文有明示陈述；**A** = 算术/统计推导（论文未说）；**P** = 项目自建契约。

| # | 论文侧内容 | 项目侧对应 | 可执行断言的形式 | 档 |
|---|---|---|---|---|
| 1 | t = (均值)/(σ/√N)：**均值的标准误**这一形式 | `hac_std` | 对 i.i.d.-like 序列，`hac_std` 必须≈`rank_ic_std/√T`（尺度不变式） | **L + A**（形式 L；不变式 A） |
| 2 | 5% 双侧 ↔ `1.96` | `ci_lower`/`ci_upper` 与 `hac_std` 同在**一个载荷块**内 | 由 `rank_ic_mean` 与 `hac_std` 反算 `[mean−1.96·SE, mean+1.96·SE]`，与 `ci_*` 比较 | **L + A**（1.96 L；比较 A） |
| 3 | t-ratio 须对 `autocorrelation` 等「usual suspects」**econometrically sound**；序列相关可用 AR 过程纳入 | `neweyWestHacStd` / `blockBootstrapCi` 的存在本身，以及 `RANK_IC_EMBARGO_NOTE` 的对外承诺 | ①「处理重叠」这一**设计意图**是否有文献依据 → **有**；②承诺与实现是否一致 → 独立审计 | **L**（意图）；**A/P**（实现） |
| 4 | 新因子的门槛是 **t > 3.0**（FWER 5% 需 3.9），而非 1.96 | 前端标签「**95%**CI」无任何 nominal / 多重检验修饰 | 全仓扫描：是否存在 nominal / 多重检验 / 校正 标注 | **L**（门槛存在）；**A**（「所以标签该改」是我的推论） |
| 5 | 对**时间序列**做 bootstrap 以取得检验统计量的经验分布是既有做法 | `blockBootstrapCi` 的**种类**（对 IC 时间序列做块重采样） | 覆盖率模拟：该区间的实际覆盖率 vs 名义 95% | **L**（做法种类）；**A**（覆盖率判据） |
| 6 | 论文假设 **σ 已知且跨策略相同**（归一到 15%） | 项目**逐因子、从样本估计** SE | 两者是**不同的问题设定**⟹ 记录差异，**不作判据** | **不适用** |
| 7 | 印本 t-stat 式**未编号** | — | 引文一律按 5.1 节定位，不得编造式号 | — |

### 5.1 判据强度的诚实标注（**本轮最重要的一段**）

- 第 3/5 行只支持「**做法种类**」：论文说序列相关**必须**被处理、说 bootstrap 时间序列是既有做法，
  但它**不**说用什么核、什么滞后、什么块长、区间怎么取。
- 第 1/2 行的 `1.96` 与 `σ/√N` 是**统计常识级**内容，即使没有本论文也成立；
  本论文的作用是**给出可引的 L 档出处**，而**不是**给出规范。
- 第 4 行是本轮**唯一**「论文明确主张、而项目没有对应物」的一条；
  但它的推论方向必须小心：论文说的是「**新因子的显著性门槛**」，
  **不**是「任何置信区间都必须做多重检验校正」。⟹ 该轴只能 `REGISTERED_ONLY`，
  **不得**作为杀死条件（见 `R007_prereg.md` §6 K 表）。

### 5.2 关于 `Math.max(hacVar/n, 0)` 与「负的长程方差」

`FactorResearchEngine.java:351` 的 `Math.max(..., 0)` 是**项目自建**的钳位。
论文对此**零字**（`Newey` 命中 0）。⟹ 该轴一律标 **A（算术）**：
「Bartlett 核的样本长程方差可以为负」是教科书级的算术事实，
「钳位为 0 会把负方差伪装成 SE=0」是本轮**我的**推论。
**不得**写成「论文要求 SE 必须为正」。

### 5.3 关于「95%」这个词

论文里的「5%」只出现在**检验显著性**语境（`1.96 benchmark for 5% significance`），
**从不**出现在置信区间语境（`confidence interval` 命中 0）。
⟹ 把「95%CI」与论文的「5% 显著性」视为同义是**我的推论**，
必须标 **A**，且必须承认：名义 95% 区间与 5% 检验在正态近似下**数值等价**，
所以「标签写 95% 而实际是 bootstrap 百分位区间」这一点，
**不**是「区间算错了」，而是「**标签指称的估计量与实际使用的估计量不一致**」。

---

## 6. 论文**不能**提供什么（防止把判据用超）

1. **不能提供 HAC / Newey–West 的任何规范**（`Newey` 命中 **0**）——
   核函数、滞后阶数规则、Bartlett 权重是否该用 `1 − l/(L+1)`，**全部无判据**。
2. **不能提供置信区间的定义或构造法**（`confidence interval` 命中 **0**）——
   bootstrap percentile 区间是否恰当，本论文**不表态**。
3. **不能提供「估计标准误」的做法**——论文的 σ 是**归一到 15% 的已知常数**（§2.3）⟹
   论文**没有**区分总体标准差与均值标准误的问题意识。
4. **不能提供覆盖率标准**——论文不做区间覆盖率研究。
5. **不能提供 A 股制度口径**（涨跌停 / 停牌 / T+1 / 成本）——论文完全不涉及。
6. **不能把 3.0 / 3.9 当作本项目的合格线**——那是**横截面**结论（316 个因子、美股、1967–2012），
   跨到「本项目单个因子的区间宽度」上是**量纲与设定双重越界**。
7. **不能判定任何既有 `D-Rxxx` 项**——尤其不能翻 `D-R006-02`（÷T vs ÷(T−1)）的案（见 §7.2）。

---

## 7. 与既有轮次的关系（防回溯改写）

### 7.1 与 R006 的边界（**必须写明，否则会被读成重复登记**）

- **R006 审的是 `rank_ic_*` 载荷族的命名与定义**：`rank_ic_mean/std/ir/positive_rate`、
  `pearson_ic_mean`、`daily_rank_ic`、`cross_section_days`，以及 `monotonicity`、`grouped_returns`、`spread`。
- **R007 审的是统计不确定性载荷族**：`hac_std`、`ci_lower`、`ci_upper`，
  以及**前端把 `ci_*` 标成「HAC 95%CI」这一指称**。
- 两者**字段不重叠**。但存在**两处必须按「同根因新实例」处理的交界**：
  1. **空/无效输入的伪造常量**：R006 的 `A6a`（`emptySummary` 六个 0）与
     R007 的「零宽区间 `[0, 0]`」出自**同一族退化路径**（`emptySummary` / `unavailableScopeBlock` /
     `emptyNetIcBlock`）。R007 **只登记新增部分**（`ci_*` 与 `hac_std` 三列），
     并明确写「与 `D-R006-03` 同族、建议合并评审」。
  2. **除以 T 还是 T−1**：R006 的 `A3` 已就 `rank_ic_std` 登记为 `D-R006-02`；
     R007 若发现 `hac_std` 与 `rank_ic_std` 的**约定不一致**，则属**新事实**（两条路径的约定是否同源）；
     若一致，则**只是同一约定的第二次确认**，**不得**重复登记为新决策项。
     冻结口径：R007 对「÷T」只做**一致性确认**，`D-R006-02` **不重开、不改写**。
- **R006 §7.3 的先例照搬**：R006 的 A5 与 R004 的 A6 是「同一函数、不同问题」；
  R007 与 R006 是**同一载荷、不同字段族**，须在报告中同句写明。

### 7.2 不得用本论文翻 `D-R006-02` 的案

`D-R006-02` 的主张是 **`rank_ic_ir` 的分母用 ÷T 而非文献的 ÷(T−1)**（判据来自 AlphaEval 式 (18)）。
本论文**没有**任何关于「样本标准差分母」的陈述，且其 σ 是归一化常数。
⟹ 本轮**只能**得出「本轮新审的两个量之间的约定是否自洽」，
**不得**写任何「R006 判错了 / 可以按 WP20592 改成 ÷(T−1)」的表述。

### 7.3 不得把本轮的「名义 95% vs 3.0」写成「项目因子不显著」

本论文的 3.0 是**新因子发现门槛**（横截面、多重检验）。
本项目**没有**任何「宣布发现新因子」的产品行为；`rule_score` 是 0–100 截面**百分位**（**非概率**）。
⟹ 本轮**禁止**出现以下任何叙述（负面清单，冻结）：
1. 「项目的因子不够显著」；
2. 「项目的 CI 太窄所以因子是假的」；
3. 「按 3.0 门槛，项目的因子都不成立」；
4. 「CI 覆盖不到 0 才算有效因子」。

### 7.4 不得把 R005 / R006 的结论改写

- **R005**：`REJECTED` 的成因是自洽性（`H1b`），**不是**「发现了未来函数」——本轮照旧不得改写。
- **R006**：`REJECTED` 的成因是**项目自己的命名主张 H0**被三条独立触发条件杀死；
  本轮**不得**写成「IC 算错了 / IC 太低」。

---

## 8. 结论

**判据来源成立，但强度**低于 **R006**。

- **成立**：本论文对 (i) 均值标准误的**形式** `σ/√N`、(ii) `1.96 ↔ 5%` 双侧映射、
  (iii) 检验统计量必须对 `autocorrelation` 等保持 **econometrically sound** 的披露规范、
  (iv) 多重检验下**新因子门槛 ~3.0 / 3.9**、(v) 对时间序列做 bootstrap 属既有做法——**五条均有逐字出处**。
- **受限**：论文**零字**涉及 Newey–West、置信区间定义、估计标准误、覆盖率、A 股制度；
  且其**分析单位是因子横截面**，不是单因子时间序列。
  ⟹ 本轮凡涉及 **HAC 核/滞后规则、块长、区间构造、覆盖率** 的断言，
  一律标 **A（算术）或 P（项目契约）**，**不得**标 L。
- **可产出**：本轮能做的是**结构型 / 算术型 / 覆盖率型**判定
  （两个估计量是否同源、窗口是否与承诺一致、退化输入输出什么、区间实测覆盖率是否达到名义值），
  **不能**做任何「够不够好」的判定。

**下一轮起应沿用的一条规则**：当载体是**方法论/统计推断**类论文时，
判据表必须**逐行**写明「该行支持的是**做法种类**还是**具体规范**」——
本轮第 3/5 行就是「只支持种类、不支持规范」的典型，这类行**不得**进入杀死条件。

---

## 9. 可复现性

- 载体：`papers/hlz2014_nber_w20592.pdf`（677,490 B，
  `sha256 = 15387ff6505c7eafdf18a89b63883b130a1497aaa41a9160d260a5ba045dfcef`）与
  同名 `.txt`（PyMuPDF 抽取，267,919 字符，
  `sha256 = 3fe55b856694754cf693bcbb00d07027f1ad5b44679d7e125e8c0f6421a8fa45`）。
- 取用路径：NBER JSON 检索端点（定位）→ `nber.org/system/files/working_papers/w20592/w20592.pdf`（全文），
  均经代理 `http://127.0.0.1:7897`。
- **被删除的错件**：`papers/hl2014_nber_w20455.pdf`（首页为 McCaig & Pavcnik，与本轮无关），已删除，
  **未**进入本轮任何判据。
- 本轮所有引文均可按 §2.3 / §2.5 的关键词在 `.txt` 中检索复核；
  式号缺失问题见 §3「形式化 = 中」与 §5 第 7 行。
