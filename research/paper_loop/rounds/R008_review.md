# R008 论文审查：Yao–Zheng（arXiv:2606.08285v1, 2026）

> 本轮**不是**策略复现轮，而是**审计轮**（与 R004 / R005 / R006 / R007 同型）：
> 论文作**判据（criteria）来源**，被检验的对象是**项目自己的换手率载荷族**
> （`BacktestTurnoverService.analyze` 的 `avg_turnover` / `max_turnover` / `min_turnover`
> / `daily_turnover[].{turnover,new_count,exit_count}`，及其前端消费者）。
> 预注册：`rounds/R008_prereg.md`（**先于任何实验代码冻结**）｜ 报告：`rounds/R008_report.md`

---

## 1. 身份核验

| 项 | 实测值 | 证据 |
|---|---|---|
| 标题 | *Beyond Agent Architecture: Execution Assumptions and Reproducibility in LLM-Based Trading Systems* | PDF 首页（页首印作全大写） |
| 作者 | Junyi Yao, Zihao Zheng | PDF 首页 |
| 版本 | **arXiv:2606.08285v1** | 首页页脚 + 全篇水印 `arXiv:2606.08285v1 [cs.AI] 6 Jun 2026`（本地 `.txt` 第 100 行） |
| 提交日期 | **2026-06-06**（v1；无后续版本） | arXiv abs 页 `[Submitted on 6 Jun 2026]` |
| 学科 | 主 `cs.AI`；交叉 `cs.CE` / `q-fin.CP` / `q-fin.TR` | arXiv abs 页 |
| 发表载体 | **无**。abs 页 `Comments` / `Journal ref` / `DOI` 三栏**均为空**；本地 PDF 全篇 `doi` 命中 **0** 次 | abs 页三栏实测为空；`grep -ai doi` = 0 |
| 排版模板 | IEEE Access（参考文献 [48] 直接引用 `ieeeaccess.ieee.org/authors/preparing-your-article/`） | PDF 末页参考文献 |
| 本地载体 | `papers/yao2026_execution_assumptions.{pdf,txt}` | PDF **392,141 B**；`sha256 = fc646957928fd1edae3c13c61a6004eea3a8bffc44d495d485b8f4996e78b865` |
| `.txt` | PyMuPDF 抽取，**81,178** 字符（UTF-8） | 本轮由审查者现场抽取，非仓库既有产物 |
| 页数 | **15** | PyMuPDF `page_count` = 15 |

**版本落差声明**：本论文**只存在 arXiv v1**，无期刊版、无 DOI，故**不存在** R005（arXiv v1 vs *Patterns* 2023）
与 R007（NBER WP vs 未核实发表版）那类版本落差。本轮**只**引用本载体的内容。

**载体性质的一句话提醒（对判据强度有直接影响）**：这是一篇**叙述性综述 + 30 篇文献编码审计 + 一个 10 只股票的方法学示例**，
**不是**一篇提出新因子或新策略的论文，**也没有**任何可复现的 A 股实证结论。
它在本轮里的角色**仅**是：提供「换手率报表应当满足什么」的**可引用判据**。

---

## 2. 论文提供的判据（逐字摘录 + 行号，行号指本地 `.txt`）

### 2.1 判据甲（L 档）：换手率定义在**组合权重**上，分母是**实际持仓**

> 原文 L1222–1228：
> 「Let `wi,t` denote the **strategy weight for asset i** after the day-t signal is formed, and let `ri,t+1` denote
> the next close-to-close return. Gross portfolio return is computed as `Rgross t+1 = Σi wi,t ri,t+1`. (2)
> **Turnover is computed as the absolute change in portfolio weights, `TOt = Σi |wi,t − wi,t−1|` (3)**」

紧接着 **L1228–1231** 把该量直接接进净收益：

> 「and net return applies a proportional trading-cost parameter `c`: `Rnet t+1 = Rgross t+1 − c·TOt`. (4)」

**这条判据给出的「做法种类」**（L 档**只**支持做法种类，**不**支持具体核/阈值）：
1. 换手率在**权重**上定义 —— 而权重按其构造属于**实际持有的组合**（`Σ wi = 1`），
   **不是**一个由调用方传入的**请求参数**；
2. 它是一个**被替换的比例**（"absolute change"），即归一的率量，不是计数；
3. 它与净收益**直接耦合**（Eq. 4 的 `c·TOt`）⟹ **换手率的量级错误会直接改变成本结论**。

**判据甲本轮的使用方式（关键，避免越界引用）**：
论文 Eq. (3) 是**双向**（`Σ|Δw|`，进出各计一次），本项目实现的是 `1 − |I|/n`（**单向**，只计被替换的比例）。
因此**不得**把 Eq. (3) 当作「本项目单向口径错误」的依据 —— 只可用它支撑
「**分母是实际持仓**」这一**做法种类**。
（单向/双向是并存的行业惯例；本项目 `note` 与前端也未声明单双向，这一点另作为**登记项**，
见预注册 A4c 与决策队列，**不**作为违反。）

### 2.2 判据乙（L 档）：载荷要让读者**能重建或能压力测试**

论文用一张「编码规则表」（Table VI）把可审计性降到操作层。逐字：

> (a) **Y**：`Transaction costs, spreads, slippage, or turnover are explicitly modeled or reported.`
> (b) **P**：`Costs or turnover are mentioned, or can be approximately inferred, but the implementation is incomplete.`
> (c) **NR**：`No recoverable cost, slippage, or turnover treatment.` （L583–587）
>
> 判 Y 的门槛（**承重句**）：「**A paper receives a full "Y" only when the manuscript gives enough information
> for an informed reader to reconstruct or stress-test the assumption.**」（L472–475）
>
> 同一段并声明：「Ambiguous cases are coded **conservatively**.」（L471–472）

**这条判据给出的做法种类**：对任何换手率报表，**存在一个「读者能否重建/压力测试」的档位判定**；
含糊情形**从低**计（conservative）。论文给出的整体结论是这项最弱：

> 「**only 14** provide cost or turnover treatment at a level that can be stress tested from the public record.」（L693–695）

论文自己的汇总表（Table VII/VIII）里 `Cost or turnover treatment` 一行是 **14† / 30**，
脚注 † 释作「`Recoverable includes partial reporting.`」（L666–667）。

### 2.3 判据丙（L 档）：报表必须**显式**到足以让读者判断摩擦是否会吃掉收益

> 「A high-turnover strategy **should report enough information for readers to judge whether commissions,
> spreads, and slippage could erase the observed edge**.」（L468–470）
>
> 结论段：「published systems should **report costs, timing, turnover, and split discipline clearly enough
> for readers to judge whether apparent gains survive realistic assumptions**.」（L1731–1736）
>
> 论文把「漏换手」与「时序不清」并列为使结论虚高的三类原因之一：
> 「…unclear timing, **ignores turnover**, or omits transaction costs.」（L67）

### 2.4 论文**没有**提供的东西（对判据强度的影响，必须写在前面）

| 论文没有给的 | 后果 |
|---|---|
| 任何**单向/双向**归一约定 | R008 **不**以「单双向」判死任何东西；只登记（A4c） |
| 任何**具体阈值**（多少算"可重建"、多少偏差算"材料性"） | R008 的材料性阈值（0.10）只能是 **P 档（项目自建）**，预注册冻结 |
| 任何**集合式（membership）换手率**的定义或讨论 | Eq. (3) 的**权重**语义**不能**直接套到「TopN 候选集合替换」上 ⟹ R008 对集合口径的判据以 **A 档算术推导**为主 |
| 任何 A 股 / 中国市场证据 | 与 §六「适配性七问」全部相关问题的答案都是「论文不涉及」⟹ 见 §4 |
| 30 篇被编码文献的名单以外的**原始数据** | 论文的 14/30 比例**不可**用于评价本项目（样本不同域） |

**明确否定一句**：本轮**不**使用、**不**引用 14/30 这个比例，**不**把本项目与那 30 篇 LLM 交易系统相提并论。

---

## 3. 为什么这篇论文能当「判据来源」

R005（KN2022 泄漏分类法）、R006（AlphaEval 评价协议字段）、R007（HLZ2014 多重检验/不确定性）
的先例是：**判据来源不必是策略论文，只需提供可引用的结构性判据**。本文满足：

1. 「换手率定义在权重/实际持仓上」是**明示的等式**（Eq. 3），可逐字引用 ⟹ **L 档**；
2. 「读者可重建或可压力测试」是**明示的可审计门槛**，且明确「含糊从低计」⟹ **L 档**；
3. 「换手率与成本耦合、量级错误会改结论」是明示的 ⟹ 支撑「换手率错报是 P 级问题而非命名洁癖」；
4. 论文**不含**具体数值阈值 ⟹ 本轮所有阈值一律标 **P 档**，并在预注册冻结。

---

## 4. 适配性七问（协议 §六）—— 逐问作答，含「不适用」的诚实标注

| # | 问题 | 作答 |
|---|---|---|
| 1 | 论文的市场/品种是否与 A 股可比？ | **不适用**。论文是综述 + 10 只美国大盘股示例；本轮**不**搬运任何结论，只借定义与审计门槛。 |
| 2 | 数据频率/样本期是否可获得？ | **不适用**。论文不提供数据。 |
| 3 | 论文的收益口径是否与本项目冻结口径（T+1、次日开盘入场、冻结费用）一致？ | **不适用**。论文的示例用 close-to-close 日频、成本 10/25 bps 压力设置；本项目口径**不改动**，也不与论文对齐。 |
| 4 | 论文的统计检验是否达到项目门槛？ | **不适用**。本轮无统计检验（全部为构造面板上的确定性测量）。 |
| 5 | 是否存在前视/幸存者偏差？ | **论文侧**：其示例用固定 10 只大盘股，作者自己在 §Threats to Validity 承认 external validity 受限。**本项目侧**：被审计的 `note` 已声明「存在幸存者偏差」。**本轮**：审计不产生收益序列，故 §5 前视/幸存者影响=**不适用（无收益、无选样）**。 |
| 6 | 交易成本/容量约束是否被处理？ | 论文侧：是（`c·TOt`）。本项目侧：**不在本轮范围**（本轮审的是换手率载荷本身）。 |
| 7 | 能否落到本项目的可验证产出一个？ | **能**：本轮产出 21 条断言在**本项目自己的生产类**上的判定（见预注册 §4）。 |

**结论**：七问中 5 项诚实标为「不适用」，原因是本轮形态为**审计轮**（论文=判据来源），
**不是**策略复现轮。这不是规避——R004–R007 四轮采用同一形态且已在前四轮报告中同型披露。

---

## 5. 候选方向与初筛记录（协议 §5：≥3 个候选方向）

| 候选 | 方向 | 处置 | 理由 |
|---|---|---|---|
| **D1** | NMV2016《A Taxonomy of Anomalies and their Trading Costs》（本地已有 PDF） | **筛除** | 已在 R003 处理并登记（`paper_ledger.csv` 状态 REJECTED，commit `6ee87cde`）。本轮为**审计轮**，重复使用同一论文作判据来源会使台账零增量；其换手率叙述（"one-sided turnover"、"average over the long and short side"）**确实**支持判据甲的做法种类，但**没有等式**，判据强度弱于 D3。 |
| **D2** | arXiv:2605.01176 *Decision-Induced Ranking Explains Prediction Inflation and Excessive Turnover in SPO-Based Portfolio Optimization*（2026-05，v4 2026-07） | **筛除** | 研究对象是 DFL/SPO 训练目标导致的**预测膨胀**与换手率偏高，属**建模层**问题；与本轮被审计对象（一个报表端点是否忠实于自己声明的口径、读者能否重建）不同域。 |
| **D3** | **arXiv:2606.08285 *Beyond Agent Architecture: Execution Assumptions and Reproducibility in LLM-Based Trading Systems***（2026-06） | **选中** | 提供 Eq. (3) 的**换手率等式**（判据甲）+ Table VI 的**可重建性门槛与"含糊从低计"规则**（判据乙）+ 明示的**成本耦合**（判据丙）；且其自身就是「审计可复现性」题材，与本轮形态同构。 |
| （D4） | arXiv:2606.09420 *Benchmarking Deep Time Series Models for Equity Portfolios*；arXiv:2607.21170 *TDA + News Sentiments 动态再平衡*；arXiv:2606.09478 *Volatility Forecasting…* | **筛除** | 三篇均只把 turnover 当作**优化约束/惩罚项或绩效指标列**，**未**给出定义等式与可审计门槛，判据强度不足。 |
| （D5） | NBER 检索 `portfolio turnover`（4,895 命中）/ `trading costs anomalies` | **筛除** | 相关性最高的即 NMV2016（w20721，见 D1）；其余命中集中在劳动经济学/税收的 "turnover"（人员流动）与 "electoral turnovers"，属同名不同义，**不可**作判据来源。此条同时是 A6a 那条「同名词」断言在**论文检索层面**的同型观察。 |

**检索路径与工具（可复核）**：arXiv API `export.arxiv.org/api/query` 经代理 `http://127.0.0.1:7897`
**返回 HTTP 429**（限流）⟹ 改用 arXiv **HTML 检索页** `arxiv.org/search/?searchtype=all&query=...`（HTTP 200）
与 NBER 官方检索端点 `nber.org/api/v1/working_page_listing/contentType/working_paper/_/_/search`（HTTP 200）。
**每一次 429 都不作为「无候选」的理由**（协议 §二：代理/源不可用不得使循环停止）。

---

## 6. 本轮**不**做的事（负面清单，写在这里以防后续轮次误引）

1. **不**复现论文的 10 只美股示例，**不**报告其 Final Cum. Return / Sharpe / Turnover 数值。
2. **不**引用 14/30 或任何论文汇总比例来评价本项目。
3. **不**把 Eq. (3) 的双向口径当作本项目单向口径的错误依据（单向/双向**并存**，见 §2.1）。
4. **不**提出、**不**注册任何新因子；本轮**零**影子因子。
5. **不**修改任何生产代码 / 测试 / 前端 / 契约 / `ResearchConvention`。
6. **不**访问数据库（本轮无 DB 断言）；发现一律只登记队列。
7. **不**声称论文有期刊版、DOI 或同行评审记录（实测三者皆无）。

---

## 7. 判据 → 断言映射（预告，逐条判定词与杀死条件见预注册）

| 判据 | 档 | 落到哪些断言 |
|---|---|---|
| 甲：换手率定义在**实际持仓**上，非请求参数 | L（做法种类）+ A（算术推导） | A1a、A2a、A2b、A2d、A4a、A4b |
| 乙：**读者可重建/可压力测试**，含糊从低计 | L（做法种类） | A5a、A5b、A3a（配对对象是否可知）、A3c |
| 丙：换手率与成本耦合，错报改结论 | L | A2c、A7a（量纲/值域）、A4c（聚合口径） |
| P 档（项目自建） | P | 材料性阈值 0.10、短员错报量级表、`strategy` 回显口径、同名歧义清单 |

---

## 8. 与既有轮次的关系

* R004 审「超额/spread 语义」、R005 审「时点契约」、R006 审「`rank_ic_*` 命名族」、R007 审「不确定性载荷族」，
  均**未**触及 `BacktestTurnoverService`；本轮是**该载荷族的首次审计**。
* 本轮与 R007 的两处**结构性差异**（须在报告中披露，便于跨轮比较）：
  1. R007 的断言对象是**引擎的纯函数**；本轮的对象是**一个 Spring `@Service`**，
     需用 `java.lang.reflect.Proxy` 伪造两个 Repository 才能在不连库的前提下调用**生产实现**
     （见预注册 §3.4；这也是本轮唯一新增的 harness 机制）。
  2. R007 含 Monte Carlo（覆盖率/钳位率，故需 Clopper–Pearson 区间下界）；
     **本轮无抽样统计量**，全部输入为构造面板 ⟹ **不含**区间下界判据，理由在预注册 §5.4 逐条写明。
* 本轮**承接** R007 沉淀的三条硬约束：CLN 必须写到检查器实际读的键；带阈值的断言必须写「在自设量级线下实测为 X」；
  判据强度逐轴标 L/A/P。**承接** R007 的一条负面教训：`round4` 的 `NaN→0.0` / `±Inf→饱和` 在本轮载荷上
  亦需实测（A7a），不得凭「输入有界所以安全」推断。
