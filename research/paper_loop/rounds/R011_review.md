# R011 论文审查：Menkveld, Dreber, Holzmeister, Huber, Johannesson, Kirchler, Neusüß, Razen, Weitzel 等（*The Journal of Finance*, 2024）——EGP、**非标准误差（NSE）**与**分析路径分叉**

- **本地载体**：`papers/menkveld2024_jf_nonstandard_errors.pdf`（1,220,922 B）
  ＋ `papers/menkveld2024_jf_nonstandard_errors.txt`（PyMuPDF 抽取，**168,745 字符 / 170,400 B**）
- **本轮性质**：**只读审计**（与 R004–R010 同型）。论文**只作判据来源**；其研究结论
  （164 个团队在 #fincap 上的估计离散度、质量与同行反馈的作用）**一条未采纳**，
  也**不**进入任何指标计算、**不**产生任何阈值。
- **被审计对象**：项目**自己**的契约主张 —— 「对一个**承重分析分叉**（直接决定已发布研究量数值的方法选择），
  消费方能否**仅凭该族载荷**恢复它取的值，以及项目在**某一处**是否枚举了它的**合理备选集合**」
  （H0/H1 见 `R011_prereg.md` §1.2）。
- **来源级别**：**A**（同行评审顶刊；*Journal of Finance* 79(3):2339–2390，DOI `10.1111/jofi.13337`，
  CC-BY OA，全文可得）。

---

## 1. 身份核验

| 项 | 值 | 核验方式 |
|---|---|---|
| 标题 | *Nonstandard Errors* | PDF 第 1 页 |
| 期刊/卷期/页码 | *The Journal of Finance*, **VOL. LXXIX, NO. 3, JUNE 2024**, pp. **2339–2390** | PDF 第 1 页页眉 + PDF `subject` 元数据 `The Journal of Finance 2024.79:2339-2390` |
| DOI | `10.1111/jofi.13337` | PDF 第 1 页脚注 |
| 版权/许可 | © 2024 The Authors；**CC-BY** open access | PDF 第 1 页脚注逐字 |
| 编辑 | Stefan Nagel, Philip Bond, Amit Seru, Wei Xiong | PDF 第 35 页（2373）末行 |
| 投稿/接收 | Initial submission: November 11, 2021; **Accepted: February 14, 2023** | PDF 第 35 页末行 |
| 页数 | **52** | `fitz.open(...).page_count` |
| 文本字符数 | **168,745**（存盘 `.txt` 170,400 B） | PyMuPDF `get_text()` 逐页拼接长度 |
| 文件 sha256（PDF） | `9d9268f97a0876403cd21a814a9017fa366e96a9f6e4045787d8850916d86ebe` | `hashlib.sha256` |
| 文件 sha256（TXT） | `b0213178dbe16a7402ef8bbe4bd91f3e132c6f9e5d76b061203e1639c81cfa09` | 同上 |
| 前 9 位作者 | 项目协调人（conceptualized and designed the project, managed it, conducted the meta-analyses, and wrote the paper） | PDF 第 3 页脚注逐字 |
| 余下作者 | 以「研究团队（RT）」或「同行评审人（PE）」身份参与 | 同上逐字 |

**来源分级判定 = A**：同行评审 + 顶刊 + 可逐字核对全文（CC-BY）。按协议 §5，
A 档是**门槛最高**的来源级别，但**仍有两条硬约束**：

1. **L 档只支持「做法种类」，不支持任何阈值**（R007 教训 44）。论文的一切数值
   （NSE = IQR 的取值 1.2% / 6.7%、AD 检验、多重检验调整因子 13–91、`t ≥ 2.9` 下界）
   **一律不得**成为本项目的合格线、容差或阈值。
2. 论文的**样本是 #fincap 实验（做市商/流动性研究的 164 个团队、同一份数据、六个假设）**，
   与 A 股横截面选股研究**不同源** ⟹ 论文的**情境结论**（例如「采样频率与统计模型是最强的两个分叉」）
   **不得**被移植为本项目「哪条分叉最重要」的判断。

---

## 2. 论文提供的判据（逐字摘录 + PDF 页码；页号指 PDF 页码，括号内为期刊页码）

### 2.1 判据甲（L 档）：同一份数据上，**分析路径**不唯一 ⟹ 路径之间会产生额外的不确定性

**第 4 页（2342）**逐字：

> "Researchers are less aware of the additional uncertainty due to there not being a standard
> analysis path. Researchers vary in what they deem to be the most reasonable path in the
> "garden of forking paths" (Gelman and Loken (2014)). Conditional on the path, there is a
> well-defined estimator and SE. Conditional on the sample, however, estimates may vary across
> researchers as they might pick different paths. We refer to this additional variation as
> nonstandard error (NSE). Note that the adjective nonstandard emphasizes the lack of a standard
> approach. In other words, **if all researchers agree on one path being the most reasonable one,
> NSE is zero.**"

**第 4 页（2342）**同一段对「路径」的定位逐字（含脚注 1）：

> "An important source of such variation is that researchers need to translate conceptual research
> questions into empirical research questions"

**这一条支持的「做法种类」只有一条**：

- **S1**：**路径（path）是一个必须被指名对象**；一条没有被指名的路径，其估计所携带的不确定性
  对读者**不可见**（NSE 的**大小**在论文里由实验测得，但「路径必须被指名」是**结构陈述**，
  与数值无关）。

### 2.2 判据乙（L 档）：**分叉与备选必须被显式离散化**，并对「结果对某个分叉的敏感性」做度量

**第 27 页（2365）**逐字：

> "We employ a multiverse analysis to address these questions."
>
> "Steegen et al. (2016) coined the term multiverse analysis to emphasize that data construction
> involves multiple decisions. The sample that enters the analysis, therefore, is a function of the
> set of reasonable choices, with the sample becoming a (p. 702) "many worlds or multiverse of
> data sets." A particular result of an analysis then becomes a distribution of results (because
> samples vary). We generalize this approach by adding decision forks for the part of the analysis
> that follows the sample construction (e.g., the choice of econometric model)."
>
> "The strength of a multiverse analysis is that it reveals **how sensitive an estimate is to a
> particular fork on the analysis path**. It does so by studying how much the estimates refract when
> varying across **all reasonable alternatives at the fork**."
>
> "To make the multiverse feasible, we **identify key forks on the analysis path** and, for each
> fork, we ask RTs to select the alternative they picked among a **set of predefined alternatives**."

**第 12 页（2350）**逐字（度量的作用域）：

> "Our main measure is the IQR, which we refer to as NSE. All hypotheses are stated as null
> hypotheses and tests are two-sided."

**这一条支持两条「做法种类」**：

- **S2**：**分叉（fork）要能被枚举**——「关键分叉」＋「该分叉上的**预定义备选集合**」两件东西
  都必须存在于某处，否则 multiverse **不可执行**；
- **S3**：**敏感性是可度量的量**——把一个估计在分叉的备选上重算，得到的是**分布**而不是单点，
  因此「单点估计 + 未指名的分叉」在方法论上是**信息不足**的。

### 2.3 判据丙（L 档）：路径**先声明**，偏离**显式对账**

**第 9 页（2347）**逐字：

> "Before starting the #fincap experiment, we filed a **pre-analysis plan (PAP)** with the Open
> Science Foundation (OSF; https://osf.io/h82aj/)."
>
> "**Relative to the PAP**, we now use robust methods to cope with unanticipated extreme outliers,
> we account for multiple testing, and we add a multiverse analysis to deepen insights.
> **Appendix B reconciles the current results with those in the original version.**"

**这一条支持一条「做法种类」**：

- **S4**：路径与实现之间**允许偏离**，但**偏离必须被显式命名并给出对账位置**（论文的做法是：
  偏离清单 + 一个专门的对账附录）。

### 2.4 分档表（**逐条标档，禁令写在表内**）

| 判据 | 档 | 理由 |
|---|---|---|
| **S1** 路径必须被指名；未指名的路径使不确定性不可见 | **L** | 第 4 页逐字明示「there not being a standard analysis path … we refer to this additional variation as NSE」 |
| **S2** 关键分叉 + 该分叉上的**预定义备选集合**必须存在 | **L** | 第 27 页逐字明示「identify key forks … a set of predefined alternatives」 |
| **S3** 敏感性 = 在备选上重算得到的**分布**（而非单点） | **L** | 第 27 页逐字「a distribution of results」「how sensitive an estimate is to a particular fork」 |
| **S4** 路径先声明；偏离须显式对账 | **L** | 第 9 页逐字（PAP + Appendix B reconciles） |
| 「IQR 是 NSE 的**正确**度量」「AD 检验**优于**其他比较」 | **无档**（论文只给自己的做法，未论证他法为错） | 论文**未**比较度量选择；本轮**不得**据此断言项目的任何度量「错了」 |
| NSE 数值（**1.2% / 6.7%**）、IQR 的具体取值、AD 检验、多重检验调整因子 **13–91**、`t ≥ 2.9` 下界 | **不适用（禁止移植）** | L 档**只支持做法种类、不支持任何阈值**（R007 教训 44）。这是 #fincap 情境下的测量结果，与本项目的研究量**不同源**；本轮**任何**阈值一律标 **P 档（项目自建）**，并在 `R011_prereg.md` 里明写「与论文数值无关」 |
| 「采样频率与统计模型是最强的两个分叉」 | **不适用（禁止移植）** | 第 35 页（2373）逐字 "Two particularly powerful ones are sampling frequency and the statistical model." 这是 #fincap 的**经验结论**，**不得**用来判断本项目哪条分叉重要 |

> **强制提醒**：S1–S4 只说明「存在这样一种做法」。项目**从未声称**自己要执行 multiverse，
> 也**没有**冻结过「路径必须被声明」的契约 ⟹ 本轮**不得**把「项目没做 multiverse」本身
> 叙述成「项目违约」。本轮要把判据**落到一个项目可以真正违约的命题上**：
> **项目自己已经发射的声明字段，是否足以让消费方**（前端、下游研究脚本、人）
> **恢复那条路径**；以及**该分叉的备选集合在项目内是否存在于任何一处**。
> 前者是**可判定的事实**（键在不在、值可不可能是别的），后者是**可枚举的事实**（有没有那份清单），
> 两者都**不需要**论文的数值。

### 2.5 论文**没有**提供的东西（对判据强度的影响，必须写在前面）

1. **没有**讨论任何软件/数据产品的「字段契约」。论文的读者是**论文读者**，不是**API 消费方**。
   ⟹ 本轮的「载荷可恢复性」是**把 S1 的做法种类向软件契约的一次迁移**，迁移本身**不产生阈值**、
   也**不产生**「项目必须照做」的规范力。
2. **没有**给出「一条分叉需要多少备选才算够」。⟹ 本轮**不设**备选数量门槛；只判定
   「**存在/不存在**预定义备选集合」这一二值事实。
3. **没有**给出可移植的阈值或容差。⟹ `R011_prereg.md` 的一切数值（如 ±20% 网格、离散度报告格式）
   **全部**标 **P 档**，仅作协议强制的**披露**。
4. **没有** A 股实证。⟹ 本轮的 multiverse 复算只用**项目自己的引擎 + 冻结面板**，
   与论文数据无任何交集；复算得到的离散度**不得**与论文的 1.2% / 6.7% 并列比较。

---

## 3. 为什么这篇论文能当「判据来源」

被审计对象是**项目自己**的路径声明面，不是论文。论文在此扮演三个角色：

1. **做法种类的参照系（S1–S4）**：回答一个**只能二值化**的问题——
   *「一个决定样本集合与方法选择的分叉，其取值能否从对外载荷里恢复？」*
   *「该分叉的合理备选集合在项目内是否存在于任何一处？」*
   这两个问题**不需要**论文的数值，只需要**存在一种把「路径/分叉/备选」显式化的公开做法**。
2. **工具性使用**：本轮的承重判据**不是**「项目偏离论文」，而是
   **「项目自己的载荷是否足以恢复项目自己取的那条路径」**。即便撤掉论文，
   「`RANK_IC_MIN_CROSS_SECTION_SIZE = 10` 在 5 个生产调用点全部生效、
   而在**任何**对外载荷中都无对应键」这条事实**仍然成立**（它由项目自己的代码结构定义）。
   论文的作用是**给这条事实一个为什么重要的外部理由**，而不是**产生**它。
3. **反向使用（协议默认立场）**：默认立场是把 H0（「路径可从载荷恢复、备选已被枚举」）
   当作**待杀**的假设去**找**反例，而不是去找一致性。本节 S1–S4 **不**给出任何
   「项目做对了」的可能——因为它们说的正是本轮要去**证伪**的那件事。

---

## 4. 适配性七问（协议 §六）—— 逐问作答，含「不适用」的诚实标注

> 口径：把「利用该论文在我项目上做研究/改动」作为候选。**任一为「是」则直接归档。**

| # | 问 | 答 | 说明 |
|---|---|---|---|
| 1 | 是否需要**真实交易接口/真实凭证**才能推进？ | **否** | 本轮全部为源码文本抽取 + 常量读取 + 引擎纯函数复算 |
| 2 | 是否需要**付费数据源**？ | **否** | 不需要任何行情数据；复算用冻结的手工/用例面板 |
| 3 | 是否需要**修改已冻结口径**？ | **否**（被审计对象本轮**不改**） | 本轮**只读**；发现的问题**只登记**（`decision_queue.md`） |
| 4 | 结论是否**依赖论文自身的策略/因子表现**？ | **否** | #fincap 的六个假设与全部数值**一条未采纳** |
| 5 | 是否**不可复现**？ | **否** | 全部断言可由 `experiments/R011_path_declaration_audit/` 独立重跑（Java 探针 + 冻结面板） |
| 6 | 是否与**已有轮次重复**？ | **否**（但**有 4 处必须显式划界**） | 见 §8：R004 的 A6 是「J 的政策敏感性」，R006 的 C6/C7 是「J 不可由请求控制」，R009 的 `D-R009-05` 是「前端必需键在早返回分支缺测」，R010 是「已声明字段是否忠实」；**本轮是第一个测「材料分叉的载荷可恢复性与备选可枚举性」的轮次** |
| 7 | 是否会**诱导前视/幸存者偏差**？ | **否** | 本轮不构造任何时间序列样本、不做任何 IC 的政策解读；复算面板的构造规则**在预注册里冻结**，且**不含**任何真实行情时序 |

**七问全为「否」⟹ 不归档，可进入审计流程。**

### 4.1 初筛门槛（协议 §5）逐条核对

| 门槛 | 结果 |
|---|---|
| 来源级别可判定 | ✅ **A**（同行评审顶刊 + DOI + 卷期页码） |
| 有可引用的、可逐字核对的原文 | ✅ 第 4 页（2342）、第 9 页（2347）、第 12 页（2350）、第 27 页（2365）、第 35 页（2373） |
| 判据能映射到项目内的**具体对象** | ✅ `FactorResearchService.java:70/71/97/98/649/650/526-553`、`ScoringBacktestService.java:58/75/78/431-436/443-448`、`ScoringBacktestRequest.java:38`、`FactorResearchEngine.java:120-126/139-141`、`modules/web/src/api/factor.ts:32-34/99-111` |
| 不依赖论文的策略结论 | ✅（§2.5 第 1–4 条） |
| 非「已归档论文的重复研究」 | ✅ 见 §8 的逐轮划界 |

---

## 5. 候选方向与初筛记录（协议 §5：≥3 个候选方向）

| # | 候选方向 | 结果 |
|---|---|---|
| **C1** | **承重分析分叉的「载荷可恢复性」与「备选可枚举性」审计** ← **本轮选定** | 初筛通过；判据来源 A 档；被审计对象是项目**自己**的载荷声明面（5 个生产 `summarize` 调用点 + 4 个载荷族） |
| C2 | 论文「同行反馈降低 NSE 减半」→ 项目内测试/评审对路径取值的**收敛约束**审计 | **并入 C1 的门轴 A9**（只登记：既有测试是否固定了分叉取值），不单列 |
| C3 | 直接用论文的 IQR 度量「项目研究量在分叉上的离散度」 | **并入 C1 的披露轴 A6**：只作**描述统计**（IQR/min-max/符号翻转计数），**不设阈值**、不参与总判定 |
| C4 | 复现 #fincap 多分析者实验（多团队同一数据同一假设） | **归档**：需 164 个独立团队与 #fincap 数据，本机不可行；且与 A 股横截面研究不同源 |
| C5 | 论文结论中的**多重检验**下界（`t ≥ 2.9`，调整因子 13–91）→ 审计项目的检验族 | **推迟**：本轮**不得**移植该数值（§2.4 明令）；且项目尚无「检验族」定义，属 R012+ 的独立题目 |
| C6 | 用论文的 AD 检验比较分叉下的分布 | **并入 C1 的 A6 描述统计**（只登记）——AD 是论文的工具选择，**不得**作为本轮的判定工具 |

---

## 6. 本轮**不**做的事（负面清单，写在这里以防后续轮次误引）

- **不做**任何代码修改。`FactorResearchService`、`ScoringBacktestService`、`FactorResearchEngine`、
  `factor.ts` **一律不动**（属协议 §7 保留权限：修改公开 API/前端契约、修改已冻结口径）。
- **不**把论文的 **1.2% / 6.7% / 13–91 / 2.9** 等任何数值用作合格线、容差或阈值（§2.4 末行）。
- **不**把「项目没做 multiverse」本身叙述成「项目违约」——项目**从未声称**要做（§2.4 强制提醒）。
- **不**把「`min_cross_section_size` 无载荷字段」叙述成「用户看到的 IC 是错的」——
  IC 的**数值**是按项目自己的口径正确算出来的；缺的是**可恢复性**，不是正确性。
- **不**重测 J 的政策含义（R004 已发布 `Δ_J` 的 IS/OOS 数值），**不**重算 R004 的结论。
- **不**回写 `R004_prereg.md`/`R006_prereg.md`/`R009_prereg.md`/`R010_*` 的任何冻结文本。
- **不**修改 `R011_prereg.md` 一经冻结的任何文本；事后更正只进 `R011_report.md` 附录 A。
- **不**触碰 §7 保留权限内的任何项目（口径、契约、权重、阈值、影子提升、真实交易）。
- **不**把本轮复算出的离散度当作**策略收益差异**或**因子有效性**的证据。

---

## 7. 判据 → 断言映射（预告，逐条判定词与杀死条件见 `R011_prereg.md`）

| 判据 | 档 | 落到哪条轴 | 断言的形态 |
|---|---|---|---|
| S1 路径必须被指名（未指名 ⟹ 不确定性不可见） | L | **A2**（分叉取值的载荷可恢复性）、**A4**（跨族定义来源分歧） | 「该分叉的取值出现在几个族的**结构化字段**里」——**计数型**断言 |
| S2 关键分叉 + **预定义备选集合**必须存在 | L | **A3**（备选集合的可枚举性）、**A8**（文档规范性） | 「项目内是否存在枚举该分叉备选的**单一处**」——**存在性**断言 |
| S3 敏感性是可度量的分布 | L | **A6**（multiverse 复算，**披露轴**） | 「在冻结分叉网格上重算，得到分叉间离散度」——**描述统计**，无阈值 |
| S4 路径先声明、偏离须对账 | L | **A5**（载荷是否含敏感性/偏离声明）、**A7**（note 文本层） | 「载荷/文档中是否有**任何**关于分叉取值或敏感性的声明」——**存在性**断言 |
| 论文**未**提供的：阈值、备选数量门槛、跨情境重要性排序 | 无档 | 全部轴的**阈值部分** | 一律 **P 档**（项目自建），且明写「与论文数值无关」 |

**关键区分（必须在报告中保留）**：
**A1/A6/A9/A10 是「清单与复算」（结论大多是描述性的、对项目有利或无褒贬）**，
**A2/A3/A4 是「可恢复性与可枚举性」（承重，结论可能对项目不利）**。
本轮**不得**因为 A6 复算跑通就把 A2/A3 淡化——**顺带跑通**与**可恢复**是**不同的对**。
反过来，**也不得**因为 A2/A3 不利就把 A6 省略——A6 的作用是把「不可恢复」的代价**量化成一个区间**，
不是用来定性严重程度。

---

## 8. 与既有轮次的关系（防回溯改写）

| 轮次 | 关系 |
|---|---|
| **R004**（分组数 J 的敏感性） | R004 的 `A6` 问的是「J 取不同值时**多空收益差**怎么变」（`Δ_J = −16.95bp (t=−3.15)` IS / `+4.61bp (t=0.34)` OOS）——一条**政策/数值**命题。本轮 V09 **不重测**该数值，只问「**载荷能不能让消费方恢复 J 的取值**」。R004 报告 §263-264 已记录 `min-size=10` 与「截面满 10 只时每组 2 只」的退化形态，本轮**只登记**该事实为背景，**不**重述为发现。 |
| **R005**（PIT/泄漏） | 无关（本轮不涉及任何 PIT 依赖）。 |
| **R006**（rank_ic 族） | R006 `prereg` 的 `C6/C7` 提到「三处调用点传组数常量」「两个 Service 的截面下限常量必须都是 10」——那是**可达性前置检查**，不是「可恢复性」命题。R006 的 `K3`/`A5` 覆盖的是**数值/键集**面。本轮与之**不重叠**，但**共用**判定词表与 K 表纪律。 |
| **R007**（统计不确定性族） | R007 已把 **HAC 滞后规则**（`floor(n^(1/3))` Bartlett）与 **block bootstrap**（`blockSize`、`iterations=1000`、`seed=42` 硬编码）审到「唯一调用点硬编码 ⟹ `iterations<100` 分支 API 面不可达」。⟹ **本轮把这些分叉显式排除**（`R011_prereg.md` §4.6 排除清单），**不**重审、**不**引用其数值。 |
| **R008**（换手率族） | 无关。 |
| **R009**（Pearson 族） | R009 的 `D-R009-05` 记录「`latestPerKey.isEmpty()` 早返回分支只发射 10 键，而 `factor.ts:110/201` 把 `pearson_ic_mean` 声明为必需 ⟹ 类型层未定义读取」——这是**分支内键集**命题。本轮 V15 若观察到「`SnapshotScopeIc` 缺 `group_count`」属**同型不同实例**（不同键、不同分支），按 R006/R007 对「同型第三/第四个实例」的既有处置，**只登记、不承重**，并在报告里显式标注为**同型实例**。 |
| **R010**（成本后净收益族） | R010 问的是「**已声明**口径是否**忠实**」（声明 vs 实现）。本轮问的是「**哪些**分叉**没有**声明」。**不同的对**：R010 的承重量（`Δbp = 3.29725…`）在本轮**不得**被引用；本轮的计数型断言也**不得**被用来重新解释 R010 的任何判定。 |
| **所有轮次** | 本轮**不**修改上述任何冻结文件，**不**重算任何历史数值，**不**把本轮的判定回溯写入旧轮报告。 |

**防重复声明**：R001–R011 **没有任何两轮**审计同一个**键名族**或同一条**契约文本**；
本轮首次把审计对象从「某个载荷族的数值/契约」提升到「**跨载荷族的路径可恢复性**」。
**事实级重叠（主动披露，1 处）**：`RANK_IC_MIN_CROSS_SECTION_SIZE = 10` 的**值**在 R004 报告 §263-264
与 R009 `prereg` §301 都出现过（前者作为背景、后者作为面板约束）。本轮对该常量提出的命题是
**「它在所有对外载荷中都不可恢复」**——这是一个**新的谓词**，不是对旧事实的重述；
但重叠的事实基础在此**显式登记**，以免后续被读成「本轮首次发现该常量」。

---

## 9. 本轮报告必须回答的问题（冻结清单，写报告时逐条对齐）

1. 生产侧 `FactorResearchEngine.summarize(` 调用点的**机器抽取计数**是否等于冻结期望
   （生产 **5** / 测试 **3**）？名单是否逐点一致？
2. **承重分叉清单**（从调用实参机器抽取）里有几条分叉？每条分叉在**每一族**载荷中的
   **可恢复状态**是什么（`RECOVERABLE` / `NOT_RECOVERABLE_ABSENT_KEY` / `NOT_RECOVERABLE_INDIRECT`）？
3. `min_cross_section_size`（`FactorResearchService.java:71` / `ScoringBacktestService.java:58`）
   在**全部**生产载荷中是否存在对应键？实测命中的发射点数是 **0** 还是 >0？
4. 同一分叉在两个 Service 中的**定义来源**是否不同（编译期常量 vs 请求参数）？若是，
   两族的载荷是否**都不**发射该分叉的实际取值（即消费方无法知道是哪一族、哪个值）？
5. 项目内是否存在**任何一处**枚举了 J 或 min-size 的**合理备选集合**（常量列表、配置、文档、
   测试用例参数表）？逐类给出命中/未命中，**不合并**为一句「没有」。
6. `holding_days` 与 `group_count` 的发射面各覆盖哪几族？`group_count` 是否在 `snapshot-research`
   族中缺失？**族内不对称**是否成立（同一个 Service、同一个常量，两个族里只有一个发射）？
7. 冻结面板上的 multiverse 复算：在 (h, J, minSize) 网格上，`rank_ic_mean` /
   `long_short_spread` / `monotonicity` 的离散度（IQR、min-max）与**符号翻转计数**各是多少？
   两次独立运行是否**逐位相同**？
8. ±20% 网格（J: 4/5/6；minSize: 8/10/12）是否改变结论的**存在性/符号**，还是只**平移**数值？
   （按 R009 教训 51 的措辞如实写。）
9. 载荷/文档中是否有**任何**关于分叉取值或分叉敏感性的**声明**（预期：0 处）？
   若有，逐处列出；若为 0，必须写明「这**不**构成违约，只登记」。
10. 本轮是否出现「为了产出而放水」的迹象？（对齐 `progress.md` §7 的自检体例）
11. 本轮**零生产代码改动**是否成立？三套测试是否与基线**逐项一致**（Java 350 / Python 343）？
