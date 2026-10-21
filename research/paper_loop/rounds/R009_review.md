# R009 论文审查：Shin 等（arXiv:2401.02710v2, 2024）——公式化 alpha 挖掘中的 IC / Rank IC 定义

> 本轮**不是**策略复现轮，而是**审计轮**（与 R004 / R005 / R006 / R007 / R008 同型）：
> 论文作**判据（criteria）来源**，被检验的对象是**项目自己的 Pearson IC 载荷族**
> （`FactorResearchEngine.pearsonCorrelation` / `FactorResearchSummary.pearsonIcMean`
> / 输出键 `pearson_ic_mean` / 前端 `FactorResearch.vue` 与 `factor.ts` 的消费面）。
> 预注册：`rounds/R009_prereg.md`（**先于任何实验代码冻结**）｜ 报告：`rounds/R009_report.md`

---

## 1. 身份核验

| 项 | 实测值 | 证据 |
|---|---|---|
| 标题 | *Synergistic Formulaic Alpha Generation for Quantitative Trading based on Reinforcement Learning* | arXiv abs 页 `<h1 class="title mathjax">`；本地 PDF 首页 |
| 作者 | Hong-Gi Shin, Sukhyun Jeong, Eui-Yeon Kim, Sungho Hong, Young-Jin Cho, Yong-Hoon Choi | abs 页 `Authors:` |
| 版本 | **arXiv:2401.02710v2** | abs 页 `[Submitted on 5 Jan 2024 (v1), last revised 8 Jul 2024 (this version, v2)]` |
| 提交日期 | v1 **2024-01-05**；v2 **2024-07-08** | 同上 |
| 学科 | 主 `cs.CE`（Computational Engineering, Finance, and Science）；交叉 `cs.AI` | abs 页 `Subjects:` |
| 发表载体 | abs 页 `Comments: Accepted by ICOIN 2024`；**无** `Journal ref`、**无** `DOI`（两栏实测 ABSENT） | abs 页三栏实测 |
| 来源级别（协议 §5） | **C**——**不是** q-fin 预印本，也不是同行评审顶刊；载体为 arXiv + 已接收会议论文（ICOIN 2024） | 依协议来源分级表逐字对照 |
| 本地载体 | `papers/sjk2024_arxiv_2401.02710.{pdf,txt}` | PDF **1,855,081 B**；`sha256 = 06560480c51782e748e25857da8293fbaf060b68cb36dc3b792dbe28417179fd` |
| `.txt` | PyMuPDF 抽取，**25,429** 字符（UTF-8） | 本轮由审查者现场抽取，非仓库既有产物 |
| 页数 | **5** | PyMuPDF `page_count` = 5 |
| 访问日期 | **2026-09-14** | 经 `http://127.0.0.1:7897` 代理访问（协议 §2 的海外源前提） |

**载体性质的一句话提醒（对判据强度有直接影响）**：这是一篇 **5 页的短会议论文**，
内容是「用 RL 扩大公式化 alpha 的搜索空间并用预训练 alpha 集做初始化」，
**不是**一篇讨论 IC 估计量性质、退化截面处理或报表规范的论文。
它对本轮的价值**仅**在于：它在 A 股（**CSI300**）实证里**逐字写出**了 IC / Rank IC 的定义与报告惯例。
**它的任何实证结论（因子、回测参数、收益）本轮一律不采纳。**

---

## 2. 论文提供的判据（逐字摘录 + 行号，行号指本地 `.txt`）

> 摘录规则（沿用 R004–R008）：`[...]` 为省略，其余**逐字**；行号取本地 `.txt` 的行号。

### 2.1 判据甲（L 档）：IC 就是**Pearson** 相关系数，Rank IC 是 **Spearman** 秩相关

> 「As a performance metric, we use the Pearson correlation coefficient to mea-
> sure the correlation between the target variable and alpha factor. Additionally,
> we use the Spearman rank correlation coefficient to measure rank-based correlations.」（`.txt:365` 起）

> 「To calculate the correlation between the alpha values and the real stock trend
> `y ∈ Rⁿ`, we used IC as an metric.」（`.txt:152` 起）

> 「We employ information coefficient (IC) and rank information coefficient (Rank IC)
> as performance evaluation metrics for the model.」（`.txt:22` 起）

**这三句合起来冻结了被审计载荷族的定义**：IC ≡ 截面上的**线性（Pearson）相关系数**，
Rank IC ≡ **Spearman 秩相关**，二者是**两个不同的量**（论文把它们并列为两列，
正说明二者不可互相替代）。

### 2.2 判据乙（L 档）：报均值的表必须**同时**给出支撑集与离散度

> 「TABLE III: Main results on CSI 300. **Values outside parentheses are the means,
> and values inside parentheses are the standard deviations across 5 runs.**」（`.txt:261` 起）

**档位边界（必须写在前面）**：这条只支持「做法种类」——**均值必须伴随其支撑集/离散度**；
它**不**支持任何具体阈值、也不规定字段名或格式。因此本轮的
「支撑集必须以**机器可读字段**出现」这一条**只能**判为 **P 档（项目自建判据）**，
**不得**写成「违反了论文的报表规范」（R007 教训第 44 条的直接应用）。

### 2.3 判据丙（L 档）：IC 是**被用来排序/择因子**的评价指标

> 「Formulaic alpha factor mining [...] involves the process of generating formulas
> with high correlations to future returns from raw features associated with stock trading.
> Typically, it calculates correlations between alpha factor values and future returns
> and identifies factors showing high correlations as 'alpha factors' [...]」（`.txt` §I 引言）

Table III 表头为 `IC(↑)` / `Rank IC(↑)`——**带方向箭头**，说明该数值会被**读作**一个可比较的大小。
⟹ 一个**与真实测量逐位不可区分的占位 `0.0`** 会被真实地当作「算出来是 0」参与比较。
这条支持的是**材料性**（为什么占位值有害），**不**支持任何阈值。

### 2.4 判据丁（L 档）：`y` 是**前瞻**收益，截面是**逐日**的

> 「The target variable is the stock price change percentage in 20 days later.」（`.txt:316` 起）

⟹ IC 是**逐日截面**上算出来、再沿时间聚合的量；「把哪几天聚合进来」是一个**真实存在**的语义问题
（这正是本轮 A3/A4 轴的来由）。

### 2.5 论文**没有**提供的东西（对判据强度的影响，必须写在前面）

逐词检索本地 `.txt`（大小写不敏感，`str.count` 子串口径；**子串命中已逐条核对上下文**，不得只看数字）：

| 检索词 | 原始命中 | 逐条核对后的**真实**含义 |
|---|---|---|
| `cost` | **0** | 全篇**未**提及任何交易成本 |
| `commission` / `slippage` | **0 / 0** | 同上，未提及佣金/滑点 |
| `transaction` | **1** | **不是**交易成本——唯一命中是参考文献里的期刊名 `IEEE Transactions on Intelligent Systems and Technology` |
| `undefined` | **0** | 未讨论「定义失效」 |
| `nan` | **3** | **不是** `NaN` 字面量——3 次全部是 `fi**nan**cial` / `Fi**nan**ce` 的子串 |
| `missing` | **0** | 未讨论缺测 |
| `zero variance` | **0** | 未讨论零方差截面 |
| `constant` | **2** | **不涉及**退化截面——两次都是算子/参数：Table II 的 `Constant` 算子名与「a constant range of {5, 60, 120, 252}」 |
| `survivorship` | **2** | **两处，且两处读法相反**（见下） |
| `standard deviation` | **3** | 支撑判据乙（报均值伴离散度） |

**`survivorship` 的两处命中（必须逐字列全，不得只引对自己有利的那一处）**：

> ①「To prevent survivorship bias, the listing date of each stock was used as its index
> inclusion date.」（§III 数据与特征，`.txt` 约 402 行**之前**）

> ② Table V「Backtest parameters」：`Survivorship bias  Not included`（`.txt:402`）

**诚实标注**：①说的是「用上市日期作为指数纳入日期」这一**构造措施**，②说的是回测参数表里的
一个**勾选状态**。二者是否真的矛盾，取决于 ② 指的是「指数成分构造」还是「回测样本」——
论文**没有**给出消歧文字。⟹ 本轮**不得**断言「论文未处理生存者偏差」（① 明确处理了），
也**不得**断言「论文处理得很好」（② 写着 `Not included`）；
**只能**如实记：**论文内部存在两处读法相反的表述，且无消歧**。

**结论（对判据强度的影响）**：
「定义不适用时**不输出**看起来像测量值的实数」以及「支撑集必须可见」这两条，
**论文都没有说**。它们在本轮**只能**判为 **P 档（项目自建判据 / 项目自己的声明契约）**。
本轮**不**把任何一条 P 档判据包装成文献要求。

---

## 3. 为什么这篇论文能当「判据来源」

三条理由（与 R006 用 DCH2026 的理由同型，但载体的档位更低，必须如实标注）：

1. **它给出的是定义，不是结论**：判据甲/丁是**术语定义**（IC = Pearson、y = 前瞻收益），
   属于领域内无可争议的用法；判据乙/丙是**报告惯例**（均值伴支撑集；IC 被用于排序）。
   用它们去审「项目的 `pearson_ic_mean` 是否忠实于它自己的声明」，不需要相信论文的实证结果。
2. **它是 A 股实证**：CSI300，`y` = 20 日后涨跌幅 ⟹ 与项目的市场/频率（A 股、日频、前瞻收益 IC）**同域**，
   比拿美股论文当 IC 定义来源更贴。
3. **它把 Rank IC 与 IC 并列为两列** ⟹ 直接支持「Pearson 侧与 Spearman 侧是**两个不同的量**，
   不能共用一套退化守卫」这一本轮核心疑点（A2 轴）。

**反向声明（防误引）**：本论文**未**被检验、**未**被复现、**未**被采纳；
它的因子、RL 流水线、Qlib 回测参数、`TopK=50 / Swap N=5 / H=20d` 一律不进入本项目。
`paper_ledger.csv` 中它的「当前状态 = `REJECTED`」指的是**本轮的判定结果**，**不是**该论文被证伪。

---

## 4. 适配性七问（协议 §六）—— 逐问作答，含「不适用」的诚实标注

> **口径声明**：七问是用来判断「**能否作为实现候选立项**」的。本轮**不实现**该论文，
> 但仍逐问作答，因为协议把它列为必答项，且答案决定本轮是否只能把它当判据来源。

| # | 问题 | 答案 | 证据 |
|---|---|---|---|
| 1 | 依赖做空/对冲工具？ | **否** | 组合为 TopK 多头（`Top K 50`），无空头腿 |
| 2 | 依赖日内/高频/T+0？ | **否** | `y` = 20 日后涨跌幅；`Minimum number of holding days H = 20 days`，日频 |
| 3 | 依赖做市/流动性假设或小票低滑点假设？ | **否（但《未披露》）** | 论文**未给出任何**成本/滑点/容量假设（`cost` 命中 0）；这是「沉默」而非「假设低滑点」，故记在 Q7 而非此处 |
| 4 | 使用本项目不具备的数据？ | **是** ⟹ **命中** | 需 RL 挖掘流水线 + `Qlib` 回测平台 + Alpha101 表达式算子集 + 预训练种子 alpha 集（§II、Table II）——本项目均不具备 |
| 5 | 结论来自特殊样本期？ | **是** ⟹ **命中** | 训练 2009-01-01..2018-12-31、验证 2019-01-01..**2019-12-31**、测试 **2020-01-01..2021-12-31**（2 年，含 COVID 冲击与 2021 结构行情）；且 Table V 自述 `Survivorship bias  Not included` |
| 6 | 所需数据在现有数据湖中是否存在且 PIT 可用？ | **部分**（IC 侧可用，挖掘侧不可用）⟹ 记为数据阻塞 | IC 所需（截面得分 + 前瞻收益）本项目有 `feature_snapshot_row.final_score` 与 `market_price_raw`；RL 挖掘侧不具备 ⟹ **不硬实现**，登记阻塞 |
| 7 | 换手率 × 成本后是否仍有正期望？ | **无法判断（论文未报告任何成本）** ⟹ **命中** | `cost|commission|transaction` 命中 **0/0/0**；`Swap N=5`、`H=20d`、`TopK=50` 隐含不低的换手，却无成本披露 |

**结论**：Q4 / Q5 / Q7 **命中** ⟹ 作为**实现候选**按协议 §5/§6 直接归档，
状态 `REJECTED`，失败原因分类 **`适配性`**。

**但**：判据甲/乙/丙/丁是**定义与报告惯例**，与「该论文能否立项」相互独立 ⟹
本轮**仍**把它用作**判据来源**，且**只**用这四条，边界见 §3 的反向声明。

### 4.1 初筛门槛（协议 §5）逐条核对（同样按「实现候选」口径）

| 门槛 | 实测 | 结论 |
|---|---|---|
| 有明确的可交易信号定义 | IC 是**中间指标**；可交易信号需 `TopK/Swap/H` 组合规则（Table V 给了参数但非策略规格） | **不满足**（IC 本身不是可交易信号） |
| 报告了交易成本或给出换手率 | `cost/commission/transaction` **0 命中**；`Swap N=5` 是参数不是换手率 | **不满足** |
| 样本期不短于 5 年且非单一特殊环境 | 训练+验证 11 年；**测试仅 2 年** | 部分满足（测试段偏短） |
| 有样本外或滚动窗口验证 | 有固定 hold-out 测试段（2020–2021），**非** walk-forward | 部分满足 |
| 年化换手率在容量下有意义 | 未报告 | **不满足（未披露）** |

⟹ 初筛**不通过**（三条不满足）⟹ 与 §4 的七问结论一致：**只作判据来源，不立项**。

---

## 5. 候选方向与初筛记录（协议 §5：≥3 个候选方向）

本轮**候选方向**（不是候选论文）按项目优先级排序，先定「要审哪个载荷族」，再找判据来源：

| # | 候选方向 | 目标载荷族 | 决定 |
|---|---|---|---|
| 1 | **Pearson 侧 IC** | `pearson_ic_mean` / `FactorResearchEngine.pearsonCorrelation` | **选中**（`progress.md` §6 登记的剩余未审计族之首） |
| 2 | `factor_return` 侧面（`daily_rank_ic` / `grouped_returns`） | 同上两键 | **本轮不选**：其**逐日序列**与**分组收益**已在 R006（A4a/A5）审计过，重复度高 |
| 3 | `monotonicity` | `assessMonotonicity` | **本轮不选**：R006 的 A7a 已判 `MONOTONIC_INCREASING` 退化标签（`REGISTERED_ONLY`），**禁止重复研究**（见 §6） |

**判据来源的检索**（≥3 个候选论文，经 `127.0.0.1:7897` 代理）：

| 候选 | 载体 | 结果 |
|---|---|---|
| arXiv `"information coefficient" "rank IC"` 检索 | arXiv 搜索页 | 命中 3 篇，见下 |
| ↳ **2401.02710** Shin 等（2024） | arXiv cs.CE + ICOIN 2024 | **选中**（A 股 CSI300；逐字定义 IC=Pearson / RankIC=Spearman；无成本披露） |
| ↳ 2401.02710 之外：`1910.01491`（2019, cs.LG 横截面选股） | arXiv | 未选：无逐字 IC/RankIC 定义对举，且样本为日/美市场 |
| ↳ `2608.28060`（2026, 由原始 intraday bars 排序） | arXiv | **未选（快速筛掉）**：适配性七问第 2 问命中（日内/高频），与项目 T+1 日频不符 |
| 另有 `arXiv API` 与 `Semantic Scholar API` 两条通道 | — | **均返回 HTTP 429（Rate exceeded / Too Many Requests）**，故改用 arXiv HTML 搜索页取回结果；该失败**如实记录**，不影响本轮取到判据来源 |

> **代理/工具失败披露**：本轮在论文检索阶段遇到两次 `429`（`export.arxiv.org/api` 与
> `api.semanticscholar.org`），**没有**因此停止循环，而是改走 arXiv 搜索页并成功取回原文；
> 见 `blocked_queue.md` 的 `B-R009-03`。

---

## 6. 本轮**不**做的事（负面清单，写在这里以防后续轮次误引）

1. **不**检验、**不**复现、**不**采纳该论文的任何实证结论（因子、RL 流水线、回测参数、收益）。
2. **不**因为论文「Accepted by ICOIN 2024」就把它当 A 档来源——按协议来源分级表，它是 **C 档**。
3. **不**引用该论文支持任何**阈值**（判据乙只支持「做法种类」）。
4. **不**重开 R001–R008 的任何冻结结论；**不**修改 `R005/R006/R007/R008_prereg.md` 的任何字节。
5. **不**把 R006 的 `A6c = NOT_REACHABLE` 直接改写成「R006 错了」——若本轮实测证伪它，
   只以「**R009 独立构造 + 新轮次结论**」的形式记录，并同时声明 R006 冻结原文不改写（见 §8）。
6. **不**把本轮判 `REJECTED` 叙述成「项目的 Pearson IC 算错了」——
   若 A1 轴通过，则数值实现是忠实的，争议在**守卫/退化/支撑集**，不在算术。
7. **不**在报告里出现「必然盈利 / 确定有效 / 无风险 / 可以直接实盘」等表达；
   `rule_score` / IC **一律不得**被表述为上涨概率。
8. **不**修改任何生产代码 / 测试 / 前端 / 契约 / `ResearchConvention`（本轮**只**观察它们）。

---

## 7. 判据 → 断言映射（预告，逐条判定词与杀死条件见预注册）

| 判据 | 轴 | 一句话 |
|---|---|---|
| 甲（IC = Pearson） | **A1** | 生产 `pearsonIcMean` 是否等于独立实现的「逐日 Pearson 的均值」 |
| 甲 + A（算术） | **A2** | `pearsonCorrelation` 的 NaN 守卫是否与它**自己的 javadoc 声明**一致（声明「方差为 0」，实现是 `vx < 1e-18` 的**未归一化平方和** ⟹ 破坏相关系数的**尺度不变性**，且与 Spearman 侧守卫**不等价**） |
| 乙（均值伴支撑集）+ P | **A3** | 当 Pearson 侧支撑 < `cross_section_days` 时，载荷里**有没有**任何一个字段能告诉读者这件事；以及 `pearson_ic_mean` 能否从导出值复算 |
| 乙 + P | **A4** | 全部合格日的 Pearson 都非有限时，`pearson_ic_mean` 输出什么；它是否与「真的算出来是 0」不可区分 |
| 乙 + P | **A5** | 同一载荷族的三处块（正常 / `unavailable` / `cost_net_ic` 退化）是否有**一致**的键集与**机器可读**的不可用标记 |
| 丙（IC 被用于排序） | **A6** | 同一键在 RAW / COST_APPROX / `cost_net_ic` 三处是否来自**同一取值路径**（阴性对照） |
| 丁（逐日截面聚合） | **A7** | 同一输入的两次调用是否逐位一致；`Map` 迭代顺序变化是否改变输出 |

---

## 8. 与既有轮次的关系（防回溯改写）

1. **未重开** R001 / R002 / R003 / R004 / R005 / R007 / R008 的任何结论。
2. **与 R006 的关系（必须写清，否则会变成重复研究）**：
   R006 的被审计对象是 **`rank_ic_*` 命名族**；其 `A4b`（`pearson_ic_mean` 与 `rank_ic_mean`
   的分母是否恒同）与 `A6c`（`pearsonIcMean` 的 `? 0` 分支是否可达）**触及**了 Pearson 侧，
   但**没有**把它作为 H0，也**没有**审计「守卫与声明的一致性」「支撑集可见性」「可复算性」
   「量纲与 n 依赖」这四条。`progress.md` §6 亦已把 Pearson 侧登记为**剩余未审计族**。
   ⟹ 本轮以**新判据集**重审该族，**不构成**重复研究。
3. **若本轮实测证伪 R006 的 `A6c`**（R006 判 `NOT_REACHABLE`，依据是它当时的 A 档等价
   `ic 有限 ⟺ pearson 有限`）：本轮**只**以「新轮次独立构造 + 登记」记录，
   **不改写** `R006_prereg.md` 与 `R006_report.md` 的任何字节，并在报告中**同时**给出
   R006 的原判词与本轮的反例数值（两者并列，由读者自行判断）。
4. **与 R007 的关系（区间下界条款）**：R007 的冻结条款要求「覆盖率/命中率类断言必须用
   区间下界（如 Clopper–Pearson）且等价变形写进预注册」。本轮**逐条说明为何不适用**（见预注册 §5.4），
   并对本轮唯一的真实数据计数给出**穷举口径**（全表日 = 总体本身，非抽样）。
5. **与 R008 的关系**：R008 的被审计族是**换手率**，与本族**无交集**；
   R008 的 `B-R008-01`（短员日占比不可测）与本轮的 A3c（真实库得分离散度）是**不同**的统计量，
   本轮**不**以 A3c 的结论去替代或重估 `B-R008-01`。

---

## 9. 本轮报告必须回答的问题（冻结清单，写报告时逐条对齐）

1. `H0` 是否存活（`¬K1 ∧ ¬K2 ∧ ¬K3`），以及 `total_verdict`。
2. A1 的数值忠实度（生产 vs 独立对照的最大绝对偏差，逐位）。
3. A2 的**精确算术**结果：把项目**既有测试**的输入
   （`scores = [1,2,3,4,5]`, `returns = [0.01..0.05]`）整体缩放 `1e-10` 后，
   `pearsonCorrelation` 返回什么？两侧方差是否 > 0？`spearmanRankCorrelation` 是否仍有限？
4. A2 的 **n 依赖**：固定相对尺度 `δ = 1e-10` 时，`n = 10` 与 `n = 12` 的结果是否不同？
5. A3 的支撑集反解：`cross_section_days` 与「Pearson 实际参与平均的日数」是否相等；差多少。
6. A4 的退化值：全部合格日 Pearson 非有限时 `pearson_ic_mean` 的具体数值、`total_observations`、
   以及该块的键集里**有没有**任何「不可用」标记。
7. A5 三处块的键集差（逐键列出差集）。
8. 真实库（只读、描述性、**不进入任何判定**）：`feature_snapshot_row` 的逐日得分离散度
   `Sxx = Σ(score − mean)²` 的分布，以及 `Sxx < 1e-18` 的真实日数。
9. 本轮**实际运行次数**与每次失败原因（R007/R008 同型，禁止只报成功那一次）。
10. 新增 `PENDING_USER` / `BLOCKED` 事项，以及**零生产代码改动**的证明（测试复测）。
