# R012 论文审查：Hou, Xue, Zhang（*The Review of Financial Studies*, 2020）——**复制失败**与「**口径选择必须显式化**」

- **本地载体**：`papers/hxz2020_rfs_replicating_anomalies.pdf`（**821,858 B**）
  ＋ `papers/hxz2020_rfs_replicating_anomalies.txt`（PyMuPDF 抽取，**401,306 字符 / 418,945 B**）
- **本轮性质**：**只读审计**（与 R004–R011 同型）。论文**只作判据来源**；其研究结论
  （447/452 个异象的复制成功率、64%/85% 不显著、`t ≥ 1.96` / `t ≥ 3` 门槛、microcap 的
  1.32%/3.28% 等**一切数值**）**一条不采纳**，**不**进入任何指标计算、**不**产生任何阈值。
- **被审计对象**：项目**自己**的契约主张 —— 「项目研究载荷中的**声明性文本字段**
  （`*_note` / `basis_note` / `universe_note` / `net_basis_note` / `embargo_note` /
  `feature_snapshot_note` / `metric_scope` / `metric_scopes` / `executable_metric_scope`）
  构成一份**自证文档**：其**可证伪主张为真**、其**覆盖完备**（每个承载数值结果的块都有样本域
  与口径声明）、其**跨块跨面同义**。」（H0/H1 与 K 表见 `R012_prereg.md` §1–§6）
- **来源级别**：**A**（同行评审顶刊 *RFS* 33(5):2019–2133；本地载体为 **NBER WP 23394**
  工作论文版，DOI `10.3386/w23394`；**发表版页码未核实**——与 R007 对 `HLZ2014` 的处置同型）。

---

## 0. 一句话

论文给出的是**「口径选择决定结论、因此必须被逐项显式声明」**这一**做法种类**（L 档）判据；
本轮用它审计项目**自己**的声明性文本字段**是否真、是否全、是否同义**。**这不是**对论文结论的复现，
**本轮不对项目任何策略、因子、收益给出任何评价**。

---

## 1. 身份核验

| 项 | 值 | 核验方式 |
|---|---|---|
| 标题 | *Replicating Anomalies* | PDF 第 1 页；`.txt` L1–L3 |
| 本地载体版次 | **NBER Working Paper 23394，May 2017** | PDF 第 1 页；NBER 页面 `nber.org/papers/w23394` |
| 工作论文 DOI | `10.3386/w23394` | NBER 页面元数据逐字（`"DOI 10.3386/w23394"`） |
| 作者（工作论文版） | Kewei Hou, Chen Xue, Lu Zhang | PDF 第 1 页 |
| 发表版 | *The Review of Financial Studies*, **vol 33(5), pages 2019–2133, 2020**；NBER 页面 `Published Versions` 栏将作者列为 `Kewei Hou & Chen Xue & Lu Zhang & Stijn Van Nieuwerburgh` | NBER 页面 `Published Versions` 栏逐字（**照录，未核实**） |
| 页数 | **130**（含大量附录） | `fitz.open(...).page_count` |
| 文本字符数 | **401,306**（存盘 `.txt` **418,945 B**） | PyMuPDF `get_text()` 逐页拼接长度 |
| 文件 sha256（PDF） | `8d00169a4cf6b4895587b054b41fe934b600ea501f210f02433cfe4db316f163` | `hashlib.sha256` |
| 文件 sha256（TXT） | `8ab941bf1f37b287d5bfa35526e1ece551bb9432fefa71c7dd538fd08164e2d8` | 同上 |
| JEL | `G12,G14` | PDF 第 1 页 / NBER 页面 |
| 检索路径 | arXiv HTML 检索（`arxiv.org/search`，经代理）返回**无该论文**；改用 NBER 开放 PDF（经代理 `http://127.0.0.1:7897`） | 本轮运行记录 |

**来源分级判定 = A**：同行评审顶刊（*RFS*）+ 可逐字核对全文（NBER 开放工作论文版）。
按协议 §5，A 档**仍有两条硬约束**：

1. **L 档只支持「做法种类」，不支持任何阈值**（R007 教训 44；R010/R011 同型禁令）。
   论文的一切数值 —— `t ≥ 1.96` / `t ≥ 3`、「64% / 85% 不显著」、microcap「61% 的数量 /
   3.28% 的市值」、「等权 1.32% vs 大市值 1.03%」、20 分位市值门槛 —— **一律不得**成为本项目的
   合格线、容差、阈值或「是否可接受」的判据。
2. **论文的样本是 1967–2014 的美国 NYSE/Amex/Nasdaq 全库**，与 A 股横截面研究**不同源**
   ⟹ 论文的**情境结论**（例如「microcap 是复制失败的首要原因」在**我们**数据上是否成立）
   **不得**被移植为本项目的结论。本轮**不测量**任何 microcap 暴露（该测量属 R002 已定案范围）。

---

## 2. 论文提供的判据（逐字摘录 + `.txt` 行锚点；行号指 `papers/hxz2020_rfs_replicating_anomalies.txt`）

### 2.1 判据甲（L 档）：**样本域（universe）是决定结论的第一位口径选择**

**`.txt` L28–L33**（摘要）逐字：

> "The anomalies literature is infested with widespread p-hacking. We replicate the entire anomalies
> literature in finance and accounting by compiling a largest-to-date data library that contains 447
> anomaly variables. **With microcaps alleviated via New York Stock Exchange breakpoints and
> value-weighted returns, 286 anomalies (64%)** including 95 out of 102 liquidity variables (93%)
> are insignificant at the conventional 5% level. **Imposing the cutoff t-value of three raises the
> number of insignificance to 380 (85%).**"

**`.txt` L65–L68** 逐字：

> "We use a consistent set of replication procedures
> throughout. **To control for microcaps that are smaller than the 20th percentile of market equity
> for New York Stock Exchange (NYSE) stocks, we form testing deciles with NYSE breakpoints and
> value-weighted returns.** We treat an anomaly as a replication success if the average return of its
> high-minus-low decile is significant at the 5% level (t ≥ 1.96)."

**`.txt` L107–L112** 逐字：

> "**Why does our replication differ so much from original studies? The key word is microcaps.**
> Fama and French (2008) show that microcaps represent only 3% of the total market capitalization of
> the NYSE-Amex-NASDAQ universe, but account for 60% of the number of stocks. Microcaps not only
> have the highest equal-weighted returns, but also the largest cross-sectional standard deviations in
> returns and anomaly variables among microcaps, small stocks, and big stocks. Many studies
> overweight microcaps with equal-…"

**`.txt` L349–L353** 逐字（Table 2 的面板数字，**只作「量级差异存在」的旁证，禁止移植**）：

> "on average, there are 2,406 microcaps, which account for 61% of the total number of firms, 3,938.
> However, microcaps represent only 3.28% of the total market capitalization, small stocks 6.77%,
> and big stocks 90%. With equal-weights, microcaps earn on average 1.32% per month relative to
> 1.03% for big stocks. In contrast, the value-weighted market return of 0.93% is close to 0.92% for
> big stocks."

**判据内容（做法种类，L 档）**：**同一份数据上的同一组信号，仅因样本域/权重/断点的选择不同，
「显著 / 不显著」会大规模翻转**（论文的 64%/85% 是**它自己样本上的读数**，本项目**不用**其数值）。
⟹ **推论（本轮的判据）**：一个承载研究结果数值的载荷，若**不能**让消费方**唯一判定样本域**，
则其数值**不可重建**；这不是命名洁癖，而是**实质性**问题（论文用整节篇幅证明量级差异存在）。

### 2.2 判据乙（L 档）：口径选择必须**逐项显式声明**（不得依赖读者默认）

**`.txt` L65** 逐字：> "We use a **consistent set of replication procedures** throughout."

**`.txt` L66–L68** 逐字（同上）把该「一致的程序集合」**逐项列出**：断点来源（NYSE）、
权重（value-weighted）、microcap 处理（20 分位以下剔除）、判定量（high-minus-low decile 的平均收益）、
门槛（5%，t ≥ 1.96）。

**判据内容（做法种类，L 档）**：论文**没有**把这些选择留给读者默认，而是**逐项写出**；
并且论文明确指出这些选择**并非无害**（见判据甲）。⟹ **推论（本轮的判据）**：
**声明性文本的完备性**本身是可审计的契约 ——
「承载结果的块必须能让人**判定**其样本域与口径」，而不是「作者心里知道」。

### 2.3 判据丙（L 档）：**高敏选择若未声明，则结果不可重建**

**`.txt` L32–L33** 逐字：> "**Imposing the cutoff t-value of three raises the number of
insignificance to 380 (85%).**"

**判据内容（做法种类，L 档）**：**同一批结果**在**不同的门槛/口径**下给出**不同的定性结论**
（「可复制」↔「不可复制」）。⟹ **推论（本轮的判据）**：声明性文本若把**关键选择**
（样本域、量纲、退化行为）留空，消费方**无法**知道自己在看哪一种读数。

### 2.4 分档表（**逐条标档，禁令写在表内**）

| # | 判据（逐字来源） | 档 | 支持什么 | **明确不支持什么（禁令）** |
|---|---|---|---|---|
| J-1 | 「With microcaps alleviated via NYSE breakpoints and value-weighted returns, 286 anomalies (64%) … insignificant」+「The key word is microcaps」（L28–L33 / L107） | **L** | **样本域必须可判定**；不同样本域下结论会翻转（做法种类） | ❌ **不得**把 64% / 85% / 3% / 60% / 3.28% / 61% / 1.32% / 1.03% / 20 分位 当作本项目任何阈值或合格线 |
| J-2 | 「We use a consistent set of replication procedures throughout」+ 逐项列出（L65–L68） | **L** | **口径选择必须逐项显式**；「一致的程序集合」是一种**可检查的契约** | ❌ **不得**据此要求本项目采用 NYSE 断点、value-weighting 或剔 microcap（那是**改口径**，属协议 §7 保留权限） |
| J-3 | 「Imposing the cutoff t-value of three raises the number of insignificance to 380 (85%)」（L32–L33） | **L** | **门槛/口径的选择会改定性结论** ⟹ 未声明 = 不可重建 | ❌ **不得**把 `t ≥ 1.96` 或 `t ≥ 3` 引入本项目（R007 `HLZ2014` 禁令同源：本轮**重申**，不新增） |
| J-4 | 论文的**样本**（1967–2014，美国 NYSE/Amex/Nasdaq，447 个异象变量） | **X**（不可移植） | **什么都不支持** | ❌ **不得**用其样本期、其异象清单、其分类计数（57/68/38/79/103/102）评价本项目 |

### 2.5 论文**没有**提供的东西（对判据强度的影响，必须写在前面）

| 论文不提供 | 后果 |
|---|---|
| 任何**「声明性字段」规范**（论文不是文档标准，不定义 note/metadata 的必填项） | 本轮**不得**把 J-1/J-2 表述成「论文要求载荷含 `universe_note`」；本轮只能说「论文证明**样本域不可判定 ⟹ 数值不可重建**」，**由项目自己**的载荷主张 H0 承接该要求 |
| 任何**A 股 / 中国市场证据** | 本轮**不**测量 microcap 暴露、**不**主张「本项目也有 microcap 问题」 |
| 任何**成本 / 换手率**处理 | 本轮**不**引入成本口径；`metric_scope` 的审计**只判「声明是否与实现一致」**，**不**重算成本（成本公式属 R010 已定案） |
| 任何**可执行的门槛**（除其自用 `t ≥ 1.96/3`，且该门槛被本表 J-3 禁移植） | 本轮全部判定为**结构型 / 真值型**，无「数值太小」类判定 |
| 对**中文文本**或**多语言声明**的任何讨论 | 本轮的文本判定**只**在项目自己的 6 个常量 + DTO 注释内做，**不**跨语言比较 |

---

## 3. 为什么这篇论文能当「判据来源」

R005（`KN2022` 泄漏分类法）、R006（`DCH2026` 评价协议字段）、R007（`HLZ2014` 多重检验/不确定性）、
R011（`MENKVELD2024` 分析路径分叉）的先例是：**判据来源不必是策略论文，只需提供可引用的结构性判据**。本文满足：

1. 「**样本域/权重/断点**是必须显式声明的选择」在论文中是**逐字明示**的（L65–L68）⟹ **L 档**；
2. 「这些选择会**翻转**定性结论」在论文中**有整节论证**（L107 起）⟹ 支撑「声明缺失是 **P 级**（实质）问题」；
3. 论文**不含**可直接移植到 A 股的阈值 ⟹ 本轮全部阈值一律**由预注册自行冻结**，并标注 `P` 档；
4. 论文与项目的关系是**「同一类选择集」**：项目的载荷也必须在
   「样本域 / 权重 / 量纲（毛 vs 净）」上让消费方判定，**只是项目用 `*_note` 文本承担这一职责**
   ⟹ 论文的判据**恰好**落在「文本字段是否完成其职责」这一问题上。

---

## 4. 适配性七问（协议 §3）—— 逐问作答，含「不适用」的诚实标注

> 本轮的「实现」是**只读审计器**（抽取器 + 静态真值核对 + 引擎探针），**不是策略**。

| # | 问题 | 答案 | 依据 |
|---|---|---|---|
| 1 | 是否依赖做空或对冲工具？ | **否** | 本轮**不**构建任何组合、**不**做多空、**不**使用 `long_short_spread` 作为策略（它是**被审计的载荷字段之一**，且其口径属 R004 已定案）。论文的 high-minus-low 读法**只作判据来源**，不落地。 |
| 2 | 是否依赖日内/高频或 T+0？ | **否** | 论文为月度频率；项目为日频 T+1。本轮**不**产生任何收益序列。 |
| 3 | 是否依赖做市/流动性假设，或在小票上假设低滑点？ | **否** | 本轮**不**引入任何滑点/流动性假设，**不**重算成本。 |
| 4 | 是否使用本项目不具备的数据？ | **否** | 本轮只用**仓库源码文本** + **本地论文载体** + **引擎探针的合成面板**；**不**读数据库、**不**新增数据源。 |
| 5 | 结论是否来自特殊样本期？ | **否（本轮无样本期）** | 本轮**不产生**任何收益/因子数值 ⟹ **无样本期**。论文一侧：1967–2014 美国数据（见 §2.4 J-4：**不可移植**）。 |
| 6 | 所需数据在现有数据湖中是否存在且 PIT 可用？ | **不适用** | 本轮判定对象是**源码中的声明性文本与实现**，不依赖数据湖。**但**：载荷**端到端**形状的观测需 Spring 容器 + DB ⟹ 该项登记为 **`B-R012-01`（BLOCKED）**，与 `B-R011-01` 同型。 |
| 7 | 换手率 × 成本后，是否仍有正期望？ | **不适用** | 本轮**不**构建策略、**不**新增持仓 ⟹ 无换手；审计中**不出现**任何成本用于评估期望。 |

**结论**：七问**无「是」导致归档**。

### 4.1 初筛门槛（协议 §5）逐条核对

| 门槛 | 本文是否满足 | 说明 |
|---|---|---|
| 有明确可交易信号定义 | **不适用（判据来源）** | 本文**不提出**信号；它复制**别人**的 447 个变量并给出统一的复制程序集合。按 R004/R006/R011 先例，判据来源论文**不要求**满足此项。**本轮不落地任何信号。** |
| 报告交易成本或换手率 | **否** | 本文为**毛**收益对照（无成本项）。⟹ 本轮**不**从本文取任何成本判据；`metric_scope` 审计只判**声明一致性**，不重算成本。 |
| 样本期不短于 5 年且不完全落在单一特殊市场环境 | **是（本文 1967–2014，48 年）** | 但**不影响**本轮（本轮无样本期，见七问第 5 条）；本文样本**不是**A 股 ⟹ 结论**不可移植**。 |
| 有样本外或滚动窗口验证 | **部分** | 本文以**统一程序集合**重放 + 面板稳健性为主，非「样本外预测」。⟹ 本轮**不**依赖其任何稳健性结论。 |
| 年化换手率在策略容量下有意义的约束 | **不适用** | 本文不涉及换手；本轮不构建策略。 |
| 来源分级 | **A** | 同行评审顶刊（*RFS* 2020 33(5):2019–2133）；本地载体为 NBER WP 版（发表版页码未核实，已披露）。 |

---

## 5. 候选方向与初筛记录（协议 §5：≥3 个候选方向）

| 候选 | 方向 | 处置 | 理由 |
|---|---|---|---|
| 1 | **声明性文本字段族审计**（`*_note` / `metric_scope*`）：真值 + 覆盖 + 跨块跨面同义 | **选中** | 该族是 `progress.md` §6 列出的**剩余未审族**（R008 首次暴露）；承载者是**项目自己**的契约主张，可机器化判定（源码文本 + 引擎探针） |
| 2 | `daily_rank_ic[]` 逐日行的忠实性/完备性 | **否决（重复）** | R006 `K3` 已审「静默丢退化截面日且无丢弃计数」；R011 已审 `min_cross_section_size` 分叉 ⟹ 属已覆盖维度 |
| 3 | `factor_return` 成组收益面（`grouped_returns` / `long_short_spread` / `monotonicity`）的口径与推断 | **否决（重复）** | R004 §5 已逐行记录「分组数轴 / 分位间距轴 / 符号轴 / 腿结构 / 权重 / 断点 / **推断**（项目对 `long_short_spread` 不做检验）」；R006 `K4/K8` 已审空组伪造与单调性退化 ⟹ 再审计即重复研究 |
| 4 | 重新挑一篇「策略类」论文做完整复现 | **否决（本轮不可行）** | 复现需生产库回溯区间与横截面数据管道；当前环境**不可端到端观测**（`B-R012-01`），强行做会把「数据不可得」写成「假设被证伪」 |

**边界声明（防止把本轮读成对已定案轮次的重述）**：见 §8。

---

## 6. 本轮**不**做的事（负面清单，写在这里以防后续轮次误引）

- **不**把本文的 `t ≥ 1.96` / `t ≥ 3`、「64% / 85%」、「3% / 60% / 3.28% / 61%」、「1.32% / 1.03% / 0.93% / 0.92%」、
  「20 分位」、异象计数（57/68/38/79/103/102 / 447）用作本项目的任何阈值、容差、合格线或评价语；
- **不**据此建议本项目改样本域、改权重、改断点、剔 microcap、改判定门槛（属协议 §7 保留权限，**只登记**）；
- **不**测量本项目的 microcap 暴露或样本域偏差**大小**（属 R002 已定案范围，见 §8）；
- **不**重算任何成本、**不**改 `ResearchConvention`、**不**触碰 `metric_scope` 的**取值**（只审**声明与实现是否一致**）；
- **不**把本文判为 `REJECTED` 时的对象说成「本文有问题」——**被杀死的是项目自己的 H0**（同 R004–R011 的表述纪律）；
- **不**修改任何已冻结文件（`R001`–`R011` 的 `prereg`/`review`/`report` 一律**零字节变更**）；
- **不**重开 `D-R004-*`、`D-R006-*`、`D-R009-*`、`D-R010-*`、`D-R011-*` 已登记项；本轮若复现同类事实，**只登记交叉引用**，不新增重复条目；
- **不**启用真实交易 / 索取真实凭证 / 向真实账户下单（协议 §8 红线）。

---

## 7. 判据 → 断言映射（预告，逐条判定词与杀死条件见 `R012_prereg.md`）

| 判据 | 映射到的断言轴（`R012_prereg.md` §4） | 判定词（§7 冻结词表） |
|---|---|---|
| J-1（样本域必须可判定） | A3（覆盖完备性）＋ A4（跨块跨面一致性） | `COVERAGE_OK` / `COVERAGE_MISSING_UNIVERSE`；`CONSISTENT` / `INCONSISTENT` |
| J-2（口径选择逐项显式） | A1（声明清单）＋ A3（口径声明覆盖） | `CLAIM_PRESENT` / `CLAIM_ABSENT` / `COVERAGE_MISSING_SCOPE` |
| J-3（未声明 ⟹ 不可重建） | A2（真值面）＋ A3 | `TRUE_CLAIM` / `FALSE_CLAIM` / `UNFALSIFIABLE` / `CLAIM_UNDECIDABLE_STATIC` |
| J-4（本文样本不可移植） | **不进任何轴**（负面清单 §6） | — |

---

## 8. 与既有轮次的关系（防回溯改写）

| 已定案轮次 | 它已经覆盖了什么 | **本轮不重复什么** |
|---|---|---|
| R002（`LR2018`，universe 偏差） | 宇宙/样本域**偏差的测量** | 本轮**不测量**偏差大小，**不**引用 `B002_universe_bias.json` 的任何数值 |
| R004（`FF2015`+`CCF2019`，多空口径结构） | 分组数轴 / 分位间距轴 / 符号轴 / 腿结构 / 权重 / 断点 / **推断**（含「项目对 `long_short_spread` 不做检验」） | 本轮**不**再审这些轴；本轮的被审计对象是**文本字段的自证职责**，不是口径结构 |
| R006（`DCH2026`） | 静默丢日、空组伪造 0、`std=0` 时 IR 写 0、单调性退化 | 本轮**不**再判这些退化行为；若文本声称与这些行为相关，**只作真值判定**（声称 vs 实现） |
| R009（`SJK2024`） | `cost_net_ic` 子块的**键集与 TS 声明**不一致（A5b） | 本轮**不**重判该 TS 键集问题；`V16` 只判「**两生产者的键对称性**是否被 TS 可选性吸收」，并在报告中**显式引用** R009 A5b |
| R010（`GONG2023`） | 加法式**记法**声明 vs 两级乘性实现（`D-R010-01`）；`basis_note` 记法不同构（`D-R010-02/03`） | `V06` 对 `RANK_IC_NET_BASIS_NOTE` 的**记法**差异一律判 `UNFALSIFIABLE` 并**交叉引用** `D-R010-01`，**不作** kill 依据 |
| R011（`MENKVELD2024`） | 承重分叉的**载荷可恢复性/备选可枚举性**（`holding_days`/`group_count`/`min_cross_section_size`） | 本轮**不**再问这三条分叉的可恢复性/可枚举性；`progress.md` §6 的禁令**逐字遵守** |

**本轮新增的边界主张**（本轮之后不得重复审计）：**6 个声明性文本常量 + 2 个 scope 常量 +
DTO 注释**这一族的「真值 / 覆盖 / 跨块跨面同义」三维度，**在本轮内一次性审完**；
后续轮次若要再碰这一族，必须**换维度**（例如：`note` 文本的**多语言/编码**、或**消费侧渲染保真**），
并须在预注册中显式引用本轮结论。

---

## 9. 本轮报告必须回答的冻结问题清单（写 `R012_report.md` 时逐条对齐）

1. H0 的三条子句（真 / 全 / 一）**逐条**给出判定与证据位置；总判定与短路路径逐条列出。
2. **每一条**声明性文本字段的逐项真值表（字段 → 可证伪主张 → 代码证据 → 判定词），
   **含**判为 `UNFALSIFIABLE` 的条目及其理由（不得把修辞类文本算作命中的「假声明」）。
3. **覆盖完备性**：块清单的**构造规则**（谁算「承载数值结果的块」）与逐块的声明覆盖矩阵。
4. **跨块/跨面一致性**：同名语义字段的对照表（含 `FactorIcSummary` 缺 `universeNote` 一项），
   并说明「一面缺失」为何构成 `INCONSISTENT` 而非 `NOT_APPLICABLE`。
5. **N = ？**：变体总数、轴计数、`row_sum` 与 `axis_total` 自检结果。
6. **±20% 敏感性**：本轮**没有**参数网格 ⟹ 必须给出**等价替代**：同一批主张用
   **（a）子串匹配 /（b）词边界收紧匹配 /（c）人工逐字核对**三种读数各测一次，
   报告**是否有任何判定被读数方式改变**（R011 教训 65 的直接应用）。
7. **`研究口径规范.md` §5 的前视/幸存者偏差影响**：本轮**不产生**收益/因子数值 ⟹ 应写
   「**不适用**」，并**逐字引用**被审计的 `universe_note` 自身对 `LEGACY_SELECTION_BIASED` 的声明
   （但**不**测量其大小）。
8. **运行史**：`run.log` 为 append-only；保留**全部**运行（含失败），并说明每次失败是**仪器缺陷**还是**假设被证伪**。
9. **偏离与勘误**：凡实测与预注册不符，逐条进报告附录（`A-xx`），**绝不**改写冻结的预注册文本。
10. **本轮**不得出现任何关于「项目策略是否赚钱 / 因子是否有效」的表述。
11. **未覆盖/阻塞**：显式列出 `B-R012-01`（端到端载荷观测需 Spring + DB）等，并说明它**不**被读成「缺陷不存在」。
12. **台账联动**：`paper_ledger.csv` / `shadow_registry.md` / `blocked_queue.md` / `decision_queue.md` / `progress.md`
    五份**逐份**说明改了什么（或**为什么**不改）。
13. **零生产代码改动**的**逐项**证据（`git status --porcelain`、探针产物清理、`git diff --check`、密钥/大文件扫描）。

---

## 10. 本轮**不**引用、**不**使用的东西（逐条列出，避免误引）

- 论文的 `t ≥ 1.96`、`t ≥ 3`、「五个百分点门槛」；
- 论文的复制成功率 `64%` / `85%` / 「286 / 380 / 447 / 452」等一切计数与比例；
- 论文的 microcap 统计量（`3%` / `60%` / `61%` / `3.28%` / `6.77%` / `90%` / `2,406` / `3,938`）；
- 论文的收益读数（`1.32%` / `1.03%` / `0.93%` / `0.92%`）；
- 论文的 20 分位（NYSE 市值）门槛与 NYSE 断点做法（**不得**建议本项目采用）；
- 论文的异象分类计数（`57 / 68 / 38 / 79 / 103 / 102`）与 6 个类别名；
- 论文的样本期 `1967–2014`（**不得**作为本项目样本期的参照）；
- 论文的发表版作者列表（NBER 页面照录，**未核实**）——报告中引用本文时一律注明**NBER WP 版**为本地载体。
