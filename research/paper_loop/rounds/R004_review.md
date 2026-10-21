# R004 论文审查：FF2015《A Five-Factor Asset Pricing Model》＋ CCF2019《Characteristic-Sorted Portfolios》

> 本轮的**对象不是一篇可实现的策略论文，而是一组「定义」**。
> R004 是一次**专项审计轮**：把项目里所有被命名为「多空 / long-short / spread / 超额」的量，
> 逐个与文献标准定义做**结构同构性检查**。
> 因此本审查的任务是**逐字取出标准定义**，而不是评估某个策略能否移植。
> 主论文 = FF2015（长空价差与「超额收益」的标准定义来源）；
> 方法论依据 = CCF2019（分组数 J 是**调参量**、且是标准做法里的关键选择）。
> CCF2019 在本轮**不作为第二个假设**，只作为**口径权威**（协议「一轮一篇论文、一个假设」不被破坏）。
> 第三候选方向另有记录，全文不可得，见 §8.2。

---

## 1. 论文身份

| 项 | FF2015（主） | CCF2019（方法论依据） |
|---|---|---|
| paper_id | `FF2015` | `CCF2019` |
| 标题 | A Five-Factor Asset Pricing Model | Characteristic-Sorted Portfolios: Estimation and Inference |
| 作者 | Eugene F. Fama, Kenneth R. French | Matias D. Cattaneo, Richard K. Crump, Max H. Farrell, Ernst Schaumburg |
| 发表 | **Journal of Financial Economics**（Accepted Manuscript，`FINEC2486`；收稿 2014-05-12，修订 2014-08-13，接收 2014-09-11） | arXiv 预印本 2019-10-08（arXiv:1809.03584）；期刊版为 *Review of Economics and Statistics* |
| 来源级别 | **A**（JFE 正刊） | **A**（arXiv 载体；作者为计量经济学主流学者，方法论文） |
| DOI | `10.1016/j.jfineco.2014.10.010` | arXiv 无 DOI；载体 ID `arXiv:1809.03584` |
| 全文位置 | `papers/ff2015_jfe_manuscript.pdf`（776,867 字节，53 页，抽出 131,573 字符） | `papers/ccf2019_arxiv_180903584.pdf`（1,017,025 字节，46 页，抽出 99,464 字符） |
| 访问日期 | 2026-09-14 | 2026-09-14 |
| 研究市场 | 美国（CRSP × Compustat；NYSE/AMEX/NASDAQ，share code 10/11） | 美国（实证应用部分：1927–2015 美股） |
| 样本区间 | 1963-07 – 2013-12 | 理论无样本；实证 1927–2015 |
| 频率 | 月度（因子收益为月度） | 逐期截面（每期一个组合排序） |
| 是否提供代码 | 否 | 否（预印本） |
| 是否提供数据 | 否（因子数据在作者网站，本轮未取） | 否 |
| 与项目对应模块 | `FactorResearchEngine` 的 `longShortSpread`、`ScoringBacktestService.factorAnalysis.longShortSpread`、`BacktestStabilityService` 的 `outperform`、前端「多空收益差」标签 | 同上（分组数轴）＋ `FactorResearchService.RANK_IC_GROUP_COUNT` |

**版本声明**：`ff2015_jfe_manuscript.pdf` 是 **JFE 接收稿（Author's Accepted Manuscript）**，
非排版定稿；页内明示 "This is a PDF file of an unedited manuscript that has been accepted for publication."
本报告所有页码均指**该 PDF 的页序**，与 JFE 正刊页码不同。
`ccf2019_arxiv_180903584.pdf` 是 **2019-10-08 的 arXiv 版本**，非 REStat 排版版；
arXiv 正文首页右上角有水印 `arXiv: 1809.03584`（已逐字核验）。

---

## 2. 论文的核心定义（逐字摘录，标注页码）

### 2.1 「超额收益」的**标准定义**：收益减去一个月期国库券利率（FF2015 p7）

> "Panel A of Table 1 shows average monthly **excess returns (returns in excess of the one-month U.S. Treasury bill rate)** for 25 value-weight (VW) portfolios from independent sorts of stocks into five Size groups and five B/M groups."

⟹ **标准含义**：文献里的「excess return / 超额收益」= `R − Rf`，
其中 Rf 是**无风险利率**（此处一个月期美国国库券）。
**它减的是无风险利率，不是基准指数，也不是成本。**

### 2.2 「多空因子」的**构造定义**：组合平均收益相减（FF2015 p11）

> "The Size factor, SMB_BM, is **the average of the three small stock portfolio returns minus the average of the three big stock portfolio returns**. The value factor HML is **the average of the two high B/M portfolio returns minus the average of the two low B/M portfolio returns**."

### 2.3 同一构造在 9 组合上的写法（FF2015 p12）

> "SMB is **the average of the returns on the nine small stock portfolios of the three 2x3 sorts minus the average of the returns on the nine big stock portfolios**."

### 2.4 「多空」的**头寸含义**（FF2015 p6，回归式定义）

> "SMB_t is the return on a **diversified portfolio of small stocks minus the return on a diversified portfolio of big stocks**, HML_t is the difference between the returns on diversified portfolios of high and low B/M stocks"

### 2.5 **成本：FF2015 全文不谈交易成本**

对 `ff2015_jfe_manuscript.txt` 全文（53 页）做大小写不敏感检索：

| 检索词 | 命中页数 |
|---|---|
| `transaction cost` | **0** |
| `trading cost` | **0** |

⟹ **不得**把 FF2015 引用为「多空净收益的标准定义」。
FF2015 的多空因子是**毛收益、且仅定义在超额收益（R−Rf）意义上**的统计对象，
它**不主张可投资性**、也**不扣任何成本**。
「美元中性多空必须扣两条腿的成本」是**算术结论**（自融资头寸两条腿都要成交），
**不是** FF2015 的文字。本报告凡涉及该点，均标注为**推导**而非引用。

**推导（非引用）**：持 1 元多头腿 + 1 元空头腿，净投入 0，
毛收益 = `R_L − R_S`；成交时两腿各付出一次往返成本 `c_rt`，故净收益 = `R_L − R_S − 2·c_rt`。
成本项**不抵消**，因为它是**两条腿各扣一次**，而不是同一条腿扣两次。

### 2.6 分组数 J 是**调参量**（CCF2019 p3）

> "the choice of number of portfolios acts as **the tuning parameter** for the procedure and that **an appropriate choice is paramount for drawing valid empirical conclusions**"

### 2.7 估计量与检验统计量（CCF2019 p9）

> 被估量是价差 `µ(z_H) − µ(z_L)`；
> `T = [ˆµ(z_H) − ˆµ(z_L) − (µ(z_H) − µ(z_L))] / sqrt(ˆV(z_H) + ˆV(z_L)) →d N(0,1)`

⟹ 文献意义上的多空价差是一个**条件均值之差**的**非参数估计量**（组合排序 = 非参数估计）。
这一点与项目里的 `last − first` 在**形状上同构**，但**统计语义**（估计量 + 推断）在项目里缺失：
项目对该量**不做** `µ(z_H) − µ(z_L)` 的检验，只报数值。

### 2.8 分位间距的标准做法（CCF2019 p7）

> "we first form portfolios by partitioning the support of z into **quantile-spaced bins**. While it is possible to form portfolios in other ways, **quantile spacing is the standard technique in empirical finance**"

### 2.9 两条腿被分别视为有信息（CCF2019 p34）

> "**the short side of buying the spread portfolio**"

### 2.10 「标准选择是十分位」与「最优往往远大于十」（CCF2019 p4）

> "the optimal number of portfolios varies substantially over time and is **much larger than the standard choice of ten routinely used in** [the empirical finance literature]"

（同句在 p37 亦可检索到，属摘要与结论的重复表述。）

---

## 3. 维度 A：严谨性

| 项 | FF2015 | CCF2019 | 评述 |
|---|---|---|---|
| 样本长度 | 50.5 年（1963-07 – 2013-12） | 理论；实证 89 年 | 均远超协议 2.2 的 5 年门槛 |
| 样本外/滚动 | 有分段（1963–2013 全期 + 子期） | 无样本外概念（理论 + 两个实证应用） | FF 中高；CCF 不适用 |
| 成本是否内置 | **否**（全文 0 次提及） | **否**（统计推断框架，不涉及成本） | **两篇都不含成本口径** ⟹ 本轮的成本轴判据必须**自带算术推导**，不得引用 |
| 换手是否报告 | 否（因子构造为月度再平衡，但未报换手） | 否 | — |
| 多重检验处理 | 未做 formal 校正；讨论因子冗余（用 5 因子 vs 3 因子比较 GRS） | 部分是方法核心（J 的选择影响推断 → 等价于规格搜索惩罚） | FF 中；CCF 高（该文正是在纠正「J 随手取 10」的推断失真） |
| 可复现性 | 无代码无数据（因子数据在作者网站，需另行获取） | 无代码（预印本） | 中低 |
| 结论稳健性 | 对市值加权/等权、微盘股剔除有讨论（p7 提及 microcaps） | 对 J、对分位间距、对时间变化稳健性均有理论刻画 | 均高 |
| 与项目口径冲突处 | (a) FF 用 **value-weight**，项目全池为**等权**；(b) FF 的分位断点**只用 NYSE 股**，项目为**全池**；(c) FF 用 **R−Rf 的超额收益**，项目**无 Rf**；(d) FF 是**毛**收益，项目把同一字段标为 `NET_AFTER_MODELED_COSTS` | (a) CCF 的标准做法是 J 可调且常**远大于 10**，项目 **J 恒为 5** 且无入参；(b) CCF 把该量当**估计量**并给推断，项目只报点值 | 见 §5 |

---

## 4. 维度 B：适配性七问（协议 §3；任一为「是」→ 归档，不实现）

> 本轮的「实现」是**审计脚本**（只读、不改生产代码），不是策略。
> 下表针对「**能否把 FF2015/CCF2019 的标准定义当作本地判定标准**」作答。

| # | 问题 | 答案 | 依据 |
|---|---|---|---|
| 1 | 是否依赖做空或对冲工具？ | **是（对「可投资多空」这一读法）／否（对「统计估计量」这一读法）** | FF 的因子是零投资组合，需要做空；**本项目无做空能力**。但 CCF 的估计量 `µ(z_H)−µ(z_L)` 只需要排序与条件均值，**不需要真的做空**。⟹ 本轮审计**只用统计读法**，把「可投资读法」作为**被审计的错误读法**来处理。 |
| 2 | 是否依赖日内/高频或 T+0？ | **否** | 两篇均为月度/逐期截面；项目为 T+1，无冲突。 |
| 3 | 是否依赖做市/流动性假设，或在小票上假设低滑点？ | **否（本轮）** | FF 用 VW 且显式讨论 microcaps；CCF 不涉及。本轮不引入任何新滑点假设，成本一律沿用**冻结口径** 万三/万五/单边万二。 |
| 4 | 是否使用本项目不具备的数据？ | **否** | 本轮只用 `market_data`（既有）+ `index_quote`（既有，000300 共 485 行）；不引入新数据源。 |
| 5 | 结论是否来自特殊样本期？ | **否** | FF 50 年、CCF 理论 + 89 年。本轮审计对象是**项目代码的口径结构**，样本期只影响数值不影响结构判定。 |
| 6 | 所需数据在现有数据湖中是否存在且 PIT 可用？ | **部分是** | 口径结构审计只需代码 + 本地面板（存在）。但 **Rf（无风险利率）本地不存在**（见 §5），故「R−Rf 超额」这一轴**只能判定为结构性缺失**，不能判定数值大小 → 登记为口径缺口而非数据阻塞项（因为它是**定义缺口**，不是「买数据就能补」）。此外基准轴受 `index_quote` 覆盖限制（000300 仅 2024-08-19 起）。 |
| 7 | 换手率 × 成本后，是否仍有正期望？ | **不适用** | 本轮不构建新策略、不新增持仓，故无换手。审计中出现的成本仅用于**测量口径差异**，不用于评估任何策略的期望。 |

**结论**：七问中无「是」导致归档。第 1 问的「是」被**读法切换**化解：
本轮把可投资读法当作**被审计对象**而不是**被采纳方法**，因此不触发作空依赖。

---

## 5. 与本地环境的偏离（含代码证据）

| 维度 | 文献标准 | 项目实现 | 证据（文件:行） |
|---|---|---|---|
| **基准轴** | 超额 = `R − Rf`（FF2015 p7，Rf = 一个月期国库券） | **全仓库无 Rf**。`BacktestStabilityService` 唯一引入基准（沪深300/000300），但只输出**布尔** `outperform`，不输出超额**数值** | `BacktestStabilityService.java:36-37,143-152`；全仓 `risk_?free/无风险` 检索无实现命中；`excess`/`excessReturn` 字段检索命中 0 |
| **基准轴（覆盖度与可比性）** | 基准与策略必须同一时间窗、同一成本口径 | (a) `index_quote` 000300 共 485 行，落在面板窗口内的仅 **480 / 645 = 74.4%** 交易日；(b) 唯一的一次基准比较是**净策略 vs 毛指数**（策略侧扣 15bp 往返，指数侧不扣）⟹ 偏差方向**不利策略**（保守） | `index_quote` 本地实测；`BacktestStabilityService.java:143-152` |
| **成本轴** | FF2015 全程毛收益（0 次提及成本） | 字段命名走**净**（`return_type = NET_AFTER_MODELED_COSTS`），但 net 块内两腿成本**在差分中精确抵消**（同一条腿扣两次 → 相减为 0） | `ScoringBacktestService.java:696`；`FactorResearchService.java:727-737`；机制推导见 R003 报告 §5 |
| **毛/净轴** | 毛与净应当是**同一价格窗口**下的同一量，差值为成本 | RAW 块用 **qfq `close[t+h]/close[t]−1`**；COST_APPROX 块用 **raw `open[t+1] → raw close[t+1+h]`** ⟹ 两块之差**同时**混淆了 5 件事：成本 / 复权口径 / 入场时点 / 区间形状 / 样本集（net 缺 raw 即跳过） | `FactorResearchService.java:239-240,343-345,720-737,951-954` |
| **分组数轴** | J 是**调参量**，标准取 10，最优常**远大于 10**（CCF2019 p3/p4/p7） | `RANK_IC_GROUP_COUNT = 5` 为**编译期常量**，无入参、无配置 | `FactorResearchService.java:70`；无任何请求参数传递 J |
| **分位间距轴** | quantile-spaced（CCF2019 p7 = 标准做法） | `(int)((long) i * g / n)` 分组 ⟹ **等数量（≈等频）分位**，**与标准同构** ✅ | `FactorResearchEngine.java:108`（`i*g/n`）；`FactorResearchService.java:963`（`buildGroupBands` 注释「按数量等分」） |
| **符号轴** | 高价差腿 − 低价差腿（`µ(z_H) − µ(z_L)`） | `grouped.get(last).avgReturn − grouped.get(0).avgReturn`，组按得分**升序**排列 ⟹ **高 − 低**，**与标准同构** ✅ | `FactorResearchEngine.java:185-186,181-183` |
| **腿结构** | 每条腿是**多个组合的平均**（FF2015 p11/p12：3 个或 2 个或 9 个组合取平均） | 每条腿 **1 个组合**（J=5 时即首/末五分位）。这是 FF 构造的**最小退化情形**（"average of 1 portfolio"），**形状同构**但未做多条腿平均 | `FactorResearchEngine.java:185-186` |
| **权重** | VW（FF2015 p7 主表为 value-weight） | 等权（组内简单算术平均） | `FactorResearchEngine.java:112`；`ScoringBacktestService` 另有 `codex_g_weighting: exp(lnMV)` 值加权分支 |
| **断点** | 只用 NYSE 股票定分位断点（FF2015 p7） | 全池断点 | `FactorResearchEngine.java:100-103` |
| **推断** | CCF2019 给出 `T →d N(0,1)` 的检验 | 项目对 `long_short_spread` **不做检验**；推断只针对 Rank IC（HAC + block bootstrap） | `FactorResearchEngine.java:188-190`（`neweyWestHacStd`/`blockBootstrapCi` 作用于 IC 序列） |
| **单位/尺度** | 月度小数收益，不年化 | h 日小数收益，不年化；前端 `×100` 后加 `%` | `ScoringBacktest.vue:264`；`FactorResearch.vue:138,317` |
| **口径标注（尺度轴的展示层部分）** | 同一量必须能被读者识别为「毛/净」「何种窗口」，否则同名量会跨口径混用 | **同一量在两个界面上的标注不对称**：`FactorResearch.vue` 渲染 `metric_scope`（`主口径：…（成本后近似）`）并标 `EXECUTABLE`；`ScoringBacktest.vue` 的「多空收益差」卡片**不出现** `metric_scope`，也无「非超额/非年化」标注 | `FactorResearch.vue:292-295`（`metric_scope` 8 处命中）；`ScoringBacktest.vue:259-283`（`metric_scope` **0** 处命中）。全仓 `metric_scope` 命中数：`.java` 24 / `.ts` 15 / `.vue` 8（`.ts` 仅类型声明，用户不可见，不计入展示层判定） |
| **分组数落地的退化保护** | 不涉及 | J=5 常量，但截面保护阈值 `RANK_IC_MIN_CROSS_SECTION_SIZE = 10` ⟹ 截面满 10 只时每组 **2 只**，且 `g = min(groupCount, n)` 会把 J 压到 n | `FactorResearchService.java:71`；`FactorResearchEngine.java:96,102` |

---

## 6. 本论文给出的**判定标准**（本轮审计的判据来源，不是实现目标）

把 §2 的定义整理成**可执行的同构性判据**（每轴一条，均为**双向**判定，即能判「同构」也能判「不同构」）：

| 轴 | 判据（问句形式） | 同构的充分条件 |
|---|---|---|
| A1 成本项 | 该量的两条腿是否**各扣一次**往返成本？成本项是否在差分中**存活**？ | 把冻结成本从 `c` 改成 `2c` 时，该量应改变 `≈ −c_rt`（而不是 `κ·` 缩放或不变） |
| A2 符号 | 是否为「高分腿 − 低分腿」？是否存在方向标志未生效？ | 交换首末组的符号应为**精确取负**；且方向由因子语义显式给出 |
| A3 毛/净 | 毛块与净块是否为**同一量**在同一价格窗口下的两种口径？ | `净 − 毛` 应**只**等于成本项 |
| A4 尺度 | 单位、周期、是否年化是否被**显式标注**？ | 数值口径（小数）＋ 周期（h 日）＋ 是否年化，三者均在**展示层**可见 |
| A5 基准 | 是否 `R − Rf`（或显式声明的基准）？缺失时是否明说？ | 存在**数值型**超额字段，或有「无基准」的显式标注 |
| A6 分组数 | J 是否为**可调参量**？在当前 J 下的数值是否对 J 敏感？ | J 能作为参数传入，且 J 变化对结论的影响被报告 |

**关键设计约束（来自 §2.5）**：A1 的判据**不能引用 FF2015**（该文不谈成本），
只能引用**算术推导**。本报告已在 §2.5 显式标注推导与引用的边界。

---

## 7. 结论：本轮论文提供的**标准**，以及不能提供的东西

1. **能提供**：
   - 「超额收益」= `R − Rf` 的**逐字定义**（FF2015 p7）；
   - 「多空价差」= 组合平均收益相减的**逐字构造**（FF2015 p11/p12）与头寸含义（p6）；
   - 「分组数 J 是调参量、标准取 10、最优常远大于 10」的**方法论权威**（CCF2019 p3/p4/p7）；
   - 「分位间距是标准做法」的**逐字表述**（CCF2019 p7）；
   - 「价差是条件均值之差的非参数估计量 + 检验统计量」的**推断框架**（CCF2019 p9）。
2. **不能提供**：
   - **任何净成本口径**。FF2015 全文 0 次提及 transaction/trading cost ⟹
     凡涉及「多空净收益应扣 2c」的命题，**必须**标为算术推导，**不得**标为文献结论。
   - **任何 A 股适配性**。两篇均为美股。项目无做空能力 ⟹
     「可投资多空」这一读法在本地**结构性不成立**，只能作为被审计的**错误读法**。
   - **Rf 数据**。本地不存在无风险利率序列 ⟹ A5 轴只能判「结构性缺失」。
3. **对 R004 审计的直接后果**：
   审计的**默认立场**是「项目里这些量的命名与文献标准是同构的」，
   而本轮任务**尝试杀死它**。杀死条件的可触发性已在 §5 逐条预验证（每条都给了代码证据）；
   **数值型**的杀死条件（A1 的成本存活、A6 的 J 敏感性）留待预注册文中固定阈值后由实验判定。

---

## 8. 检索、获取与失败记录（负知识）

### 8.1 检索后端可用性（2026-09-14 实测）

| 后端 | 结果 |
|---|---|
| **OpenAlex** `api.openalex.org/works?search=...` | ✅ **唯一稳定可用**。正确 ID 均由它取得 |
| Semantic Scholar API | ❌ HTTP 429 |
| arXiv API | ❌ HTTP 429 / 000 |
| WebFetch（harness 内置抓取器） | ❌ Connect Timeout（不读本机代理） |
| NBER `/search` | ❌ JS 渲染，HTML 中 0 个 `/papers/w` 链接 |
| DuckDuckGo HTML | ⚠️ 首次成功，随后返回 HTTP 202 反爬页 |
| Mojeek | ❌ 返回约 370 字节的拦截页 |
| Bing | ❌ 返回无关中文词典结果 |

### 8.2 第三候选方向：全文不可得（登记为证据不足）

- 方向：**"Model Comparison with Transaction Costs"（Journal of Finance, 2023）** ——
  与 R003 的成本主题直接相关，本可作为 A1 的成本轴权威。
- 结果：**Wiley 全文被 Cloudflare "Just a moment..." 拦截**。
  下载得到的 `mcc2023.pdf` 实为 5,850 字节以内的 HTML 拦截页（`<!DOCTYPE html>`），**已删除**。
- 处理：**只做到摘要级别**，**不进入** `paper_ledger.csv` 的 A 级记录，
  在本轮**不用于任何判据**。⟹ 本轮 A1 轴的判据**只依赖算术推导 + 项目代码**，不依赖该文。
- 同类事件在 R002（`shumway1997_jf.pdf`，实为 Cloudflare 拦截页）已出现一次，
  本轮 `hks2010_jf_intraday.pdf` 之外又出现一次 ⟹ **共 3 次**。
  该失败模式已固化：`HTTP 200 + 合理体积` **不等于**拿到了正确的论文，**必须逐页读首屏核验**。

### 8.3 本轮我自己犯的错（必须记录，不得静默）

1. **凭记忆猜标识符下载了两份错误的论文**：
   - `ff2015_nber_w21254.pdf` → 实为 Jacob T. Levy 的政治学论文《Corps intermédiaires, Civil Society…》；
   - `ccf2020_arxiv_181003068.pdf` → 实为《Geometric Scattering for Graph Data Analysis》。
   - **根因**：用的是**回忆**的 ID，不是**检索**得到的 ID。两份文件**从未被任何文档引用过**，
     也**未冒充**为已读文献；发现问题后**立即删除**，并用 OpenAlex 检索到的正确 ID 重新获取，
     且**逐页核验首屏**后才落盘为 `ff2015_jfe_manuscript.pdf` / `ccf2019_arxiv_180903584.pdf`。
   - **教训（与 R003 A-6 同源）**：文件身份必须由**内容**证明，不能由**文件名或记忆**证明。
2. **文件名不得误导**：新落盘的两份文件**命名即真实载体**
   （`_jfe_manuscript` 对应 JFE 接收稿；`_arxiv_180903584` 对应 arXiv 版本），
   以避免重演 R003 的 A-6（文件名写 RFS、内容实为 NBER WP）。

---

## 9. 可复现性

- 两份 PDF 与抽出的纯文本均在 `papers/`；文本抽取用 PyMuPDF（`fitz`），逐页以
  `===== PAGE n =====` 分隔，**页码可一一对应**。
- 本报告所有引文均可由
  `grep -n "逐字短语" papers/ff2015_jfe_manuscript.txt papers/ccf2019_arxiv_180903584.txt` 复现。
- §3「FF2015 全文 0 次提及交易成本」由全文正则检索复现，
  检索词与结果已在 §2.5 列出，**属可反驳的断言**（任何人可重跑推翻）。
- 未使用任何需要凭证的资源；未修改任何生产文件。
