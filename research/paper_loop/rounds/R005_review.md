# R005 论文审查：KN2022《Leakage and the Reproducibility Crisis in ML-based Science》

> 本轮为**结构审计轮**（沿用 R004 形态）：论文**不作为策略假设来源**，而作为
> **判据（criteria）来源**——它提供的是一套**可引用的泄漏分类法 + 逐类报告要求**。
> 因此本轮审计的是**项目自身的时点契约**，不是论文的可盈利主张。

---

## 1. 论文身份

| 项 | 值 |
|---|---|
| 标题 | *Leakage and the Reproducibility Crisis in ML-based Science* |
| 作者 | **Sayash Kapoor**, **Arvind Narayanan**（Princeton University, Dept. of Computer Science / CITP） |
| 载体（本轮采用） | **arXiv:2207.07048v1**，2022-07-14 |
| 载体（期刊版） | *Patterns*（Cell Press），2023，doi `10.1016/j.patter.2023.100804`（gold OA） |
| 本地文件 | `papers/kn2022_arxiv_2207.07048.pdf`（29 页）＋ `.txt`（PyMuPDF 抽取） |
| 身份核验 | PyMuPDF 读第 1 页：标题与两位作者逐字比对一致；正文含 `arXiv:2207.07048v1` 水印 |
| 版本落差 | ⚠️ **本轮用的是 arXiv v1（2022-07），不是 Patterns 2023 期刊版**；出版社 PDF 返回 **403**（`cell.com/action/showPdf` → 403），DOI 解析返回 200 但仅 2,971 B 的重定向页。落差登记见 §9 |

**与 R001 的同类问题**：`D-R001-03` 已登记过「立项依据的 DOI 指向正刊、实际读的是 WP」。
本轮**主动声明同一落差**，并按 R001 的处置方式在结论中同时标注两个版本，**不**声称读到过期刊版。

---

## 2. 论文的核心内容（逐字摘录，标注抽取来源）

### 2.1 「泄漏」这个词，论文**没有**给出形式化定义 —— 这一点必须说清

论文对泄漏的表述是**转引**，不是自建定义（`kn2022_arxiv_2207.07048.txt`）：

> "Leakage. Data leakage has long been recognized as a leading cause of errors in ML applications
> (Nisbet et al., 2009). In formative work on leakage, **Kaufman et al. (2012) provide an overview of
> different types of errors and give several recommendations for mitigating these errors**."

⟹ **本轮不得声称「KN2022 定义了泄漏」**。可引用的原始定义方是 Kaufman et al. (2012)
（*Leakage in Data Mining: Formulation, Detection, and Avoidance*, ACM TKDD）——**本篇我没有读到**。
所以本轮的判据权威性是**分类法 + 报告要求**，不是形式化定义。这是本轮最大的适配性弱点（§4 Q3）。

### 2.2 论文的实际贡献（三条，逐字）

> "Based on our survey, we present a **fine-grained taxonomy of 8 types of leakage** that range from
> textbook errors to open research problems (Section 2.4)."

> "2) **Model info sheets** to detect and prevent leakage. Current standards for reporting model
> performance in ML-based science often fall short in addressing issues due to leakage. …
> we introduce **model info sheets** to detect and prevent leakage in ML-based science (Section 3).
> They are inspired by the model cards in Mitchell et al. (2019)."

> "we find **17 fields where errors have been found, collectively affecting 329 papers** and in some
> cases leading to wildly overoptimistic conclusions."

### 2.3 八类泄漏 + 逐类**报告要求**（附录 C，逐字）

这是本轮**唯一直接使用的判据文本**。原文按「Model info sheet 必须回答什么」给出：

| 编号 | 论文名称（逐字） | 论文要求的**报告内容**（逐字要点） |
|---|---|---|
| **L1.1** | No test set | "require an explanation of how the train and test set is split **during all steps** in the modeling process (Q9-17)" |
| **L1.2** | Pre-processing on training and test set | "Details of how the train and test set are separated **during the preprocessing** selection step need to be included (Q12-13)" |
| **L1.3** | Feature selection on training and test set | "Details of how the train and test set are separated **during the feature selection** step need to be included (Q14-15)" |
| **L1.4** | Duplicates in datasets | "require details of **whether there are duplicates in the dataset, and if so, how they are handled** (Q10)" |
| **L2** | Model uses features that are not legitimate | "For each feature used in the model, researchers need to **argue why the feature is legitimate to be used for the modeling task at hand** (Q21). This addresses the leakage due to the use of **proxy variables** in Kaufman et al. (2019)." |
| **L3.1** | Temporal leakage | "researchers need to provide an explanation for **why the time windows used in the training and test set are separate, and why data in the test set is always a later timestamp compared to the data in the training set** (Q20)" |
| **L3.2** | Dependencies in training and test data | "Researchers need to **reason about the dependencies** that may exist in their dataset and **outline how dependencies across training and test sets are addressed** (Q11)" |
| **L3.3** | Sampling bias in test distribution | "Researchers need to **reason about the presence of selection bias** in their dataset and outline **how the rows included for data analysis were selected**, and **how the test set matches the distribution about which the scientific claims are made** (Q18-19)" |

### 2.4 论文对「为什么读论文抓不到泄漏」的立场（本轮阳性对照的依据）

> "**While none of these errors could have been caught by reading the papers**, model info sheets
> would enable the detection of leakage in each case."

⟹ 这正是本轮**必须自带可执行检测器 + 阳性对照**的理由：论文自己说"肉眼读不出来"，
所以本轮的每个判据都必须是**可执行、可注入、能被证伪**的，不能是文本断言。

### 2.5 论文对「可复现性」的定义（本轮 A9 轴的引用出处）

> "We **define a research finding as reproducible if the code and data used to obtain the finding
> are available and the data is correctly analyzed**. … One possible definition is computational
> reproducibility — when the results in a paper can be replicated using the exact code and dataset
> provided by the authors. We argue that **this definition is too narrow because even cases of
> outright bugs in the code would not be considered irreproducible under this definition**."

⟹ 论文明确主张：**bug 也算不可复现**。这给了本轮 A9 轴（工件无法复现其自身准入声明）
一条**可引用的**判据基础，而不需要把它硬塞进泄漏分类法。

---

## 3. 维度 A：严谨性

| 项 | 评价 |
|---|---|
| 样本量/证据规模 | **强**：20 篇 pitfall 论文综述 + 17 领域 / 329 篇受影响论文 + 一个完整复现案例研究（civil war prediction，4 篇顶刊论文全部复现失败） |
| 复现案例的因果强度 | **强**：他们**重新跑**了模型（RF vs LR），报告 reported vs corrected AUC 并给出图 1；不是转述 |
| 定义的形式化 | **弱**：泄漏的核心定义转引 Kaufman et al. (2012)，自身不给公式；无估计量、无统计检验 |
| 分类法的 MECE 性 | **中**：L1.x 是切分/预处理/特征选择/重复，L2 是特征合法性，L3.x 是时间/依赖/采样；类别之间有交叠（如 L1.2 与 L3.1 在时序数据上会同时成立），论文自己也承认是 "range from textbook errors to open research problems" |
| 可操作性 | **强**：每一类都配了 model info sheet 的具体问号（Q9–Q21），这是可直接映射成审计项的 |
| 外部效度 | **中→弱**：17 个领域里**没有金融/量化投资**。论文的案例是政治学、医疗、生物信息等。对本项目的适配属**跨域迁移**，不是同域证据 |

---

## 4. 维度 B：适配性七问（协议 §3；任一为「是」→ 归档，不实现）

| # | 问题 | 答 | 依据 |
|---|---|---|---|
| Q1 | 是否要求**真实交易/真实账户**？ | **否** | 全文是方法论与报告标准，无交易 |
| Q2 | 是否要求**引入新数据源或付费数据**？ | **否** | 判据只依赖本地已有的代码与表 |
| Q3 | 是否要求**无法获得的定义**？ | **部分** | 泄漏的形式化定义在 Kaufman et al. (2012)，**本地无该文**。⟹ 本轮**不引用形式化定义**，只用 §2.3 的**报告要求**作为判据；弱点已登记（§9 落差 3） |
| Q4 | 是否与**已有冻结口径冲突**？ | **否** | 本轮不改 `ResearchConvention`；反之，**用**论文的 L3.1 要求去检查项目**自己的**声明是否自洽 |
| Q5 | 是否要求**修改生产代码/契约/数据库**？ | **否** | 审计为**只读**；任何修复属 §7 保留权限，只登记队列 |
| Q6 | 样本期是否满足 5 年门槛？ | **否** | 仍是 `B-R001-01`（645 个交易日）。但本轮是**结构审计**，不依赖长样本——与 R004 同 |
| Q7 | 是否要求**未来函数/前视**作为方法的一部分？ | **是，但受控** | 阳性对照**故意注入**前视，但注入对象是**合成 canary 与真实行的副本**，不回写任何表、不影响任何生产路径 |

⟹ **不归档**：唯一命中的 Q7 是**受控注入**，属审计器自检，不是方法要求；
命中的 Q3 通过**降低引用强度**（只用报告要求，不用形式化定义）处置并登记。

---

## 5. 本论文给出的**判定标准**（本轮审计的判据来源，不是实现目标）

### 5.1 可直接映射的判据（本项目对象 → 论文要求）

| 论文要求 | 本项目审计对象 | 可判定性 |
|---|---|---|
| L1.1 训练/测试在**所有步骤**上分离 | IS（2024–2025）/ OOS（2026）切分是否按**时间**分离，且消费方是否知道 | 结构性 ✅ |
| L1.2 预处理阶段隔离 | 打分链路里是否存在**用全样本估计**的归一化/分位/标准化（`*_normalized` 版本） | 结构性 ✅ |
| L1.3 特征选择阶段隔离 | 因子权重是否在 OOS 上选过 | **不可判定**（无选择记录） |
| L1.4 重复与重复处置 | `(result_id, stock_code)`、`(trade_date, factor_version, topn)` 重复；持有期重叠 | **数值 ✅** |
| L2 逐特征论证合法性 | 每个持久化字段「在 `trade_date` 当天是否可得」 | 结构性 ✅ |
| L3.1 测试集时间戳**始终晚于**训练集 | ①`created_at` vs `trade_date` 的溯源滞后；②持久化特征能否由 **≤ `trade_date`** 的数据重算 | **数值（核心）✅** |
| L3.2 依赖的处置 | 同日截面依赖、重叠持有期的独立性 | 数值（部分）✅ |
| L3.3 测试分布匹配论断分布 | PIT 股票池缺失（`B-R001-02`）、板块断层（`B-R001-04`） | 已登记，本轮只登记 |

### 5.2 论文**不能**提供的东西（必须写明）

1. **阈值**。论文一个数值阈值都没给（没有 t 值、没有容忍度、没有"多少重复算违规"）。
   ⟹ 本轮所有阈值由我**自定并冻结**，并在报告中声明「阈值非论文所给」。
2. **金融域证据**。17 个领域里没有金融。⟹ 迁移性由本轮自行论证。
3. **形式化定义**（见 §2.1）。
4. **任何关于「A 股」「前复权价」「涨停不可买」的具体判断**。论文完全不涉及交易制度。

---

## 6. 与本地环境的偏离（含代码/数据证据）

| # | 偏离 | 证据 |
|---|---|---|
| D1 | 论文的审计对象是**单次建模任务的 train/test 一次性切分**；本项目是**滚动日频截面**，"训练/测试"边界由日历而非一次性划分决定 | `ScoringBacktestService.run` 用交易日历切片；`R001/R002` 的 IS/OOS 按年份切 |
| D2 | 论文的 L3.1 关注"测试集时间戳晚于训练集"；本项目多了一层**工件溯源**问题——同一条 `trade_date` 的记录可以由**迟到数日**的运行产生 | 实测 `DATEDIFF(created_at, trade_date)`：0d×24、1d×30、**≥2d×9（最大 20d）** |
| D3 | 论文假设特征表里"有值就是算了"；本项目存在**占位 0.0 与真实 0 不可区分**（`avg_amount20` 的 NULL 数为 **0**） | 见 `R005_prereg.md` §5 预验证记录 R-3/R-4/R-5 |
| D4 | 论文的 L1.x 关心 imputation；本项目**不做插补**，但做**退化输入占位** | `LabelFactoryService.buildSingleLabelV2` 对 10 种缺失情形逐条 `makeLabel(...)` 返回占位标签 |
| D5 | 论文无成本/制度约束；本项目有冻结成本口径与 T+1、涨跌停 | `ResearchConvention`（`T_PLUS_ONE_SELL=true`、万三/万五/万二） |

---

## 7. 结论：本轮论文提供的**标准**，以及不能提供的东西

**能提供（本轮使用）**：

- **8 类泄漏的报告要求**（§2.3）⟹ 直接作为本轮的 8 条审计轴与判据文本；
- **"论文读不出泄漏"这一立场**（§2.4）⟹ 论证本轮必须自带可执行检测器与阳性对照；
- **"bug 也算不可复现"的扩展定义**（§2.5）⟹ 给 A9 轴（工件无法复现自身准入声明）提供判据出处；
- **model info sheet 的问题清单形态** ⟹ 本轮产出的「时点契约表」按同形态组织（逐项：声明 / 实际 / 证据）。

**不能提供**：

- 任何阈值（全部自定并冻结）；
- 泄漏的形式化定义（转引 Kaufman 2012，本地未读到）；
- 金融域证据（17 领域无金融）；
- 任何 A 股制度相关的判断。

**因此本轮的判定强度必须自我降级**：判定的是「**本项目声明与实现是否自洽**」，
而**不是**「本项目是否盈利被前视污染」。前者可判定，后者在本地数据下大多 UNDETERMINABLE。
这一点在预注册里写死，防止事后把"契约违规"说成"策略被前视高估"。

---

## 8. 检索、获取与失败记录（负知识）

### 8.1 检索后端可用性（2026-09-14 实测）

| 后端 | 结果 |
|---|---|
| `api.openalex.org`（经 `127.0.0.1:7897`） | ✅ 可用（选题与 DOI/载体核验都走它） |
| 直连 `export.arxiv.org` | ❌ **301/空响应**（无条目返回） |
| 经代理 `export.arxiv.org/api` | ⚠️ **HTTP 429 `Rate exceeded.`** |
| 经代理 `arxiv.org/abs` 与 `arxiv.org/pdf` | ✅ 可用（本论文由此取得；注意 `v2/v3/v4` 均 **404**，只有 v1 可下） |
| `papers.ssrn.com` | ❌ **HTTP 000**（`B-R001-08` 仍然成立，代理也无法） |
| `cell.com` 出版社 PDF | ❌ **403** |
| `www.ams.org/notices/...pdf` | ❌ **403**（返回 HTML，非 PDF） |
| `people.duke.edu/~charvey/...` | ✅ 可用（Harvey 论文自存档） |
| `www.davidhbailey.com/dhbpapers/` | ✅ 可用（Bailey 论文自存档） |

### 8.2 三个候选方向被**实测证伪**（本轮的负知识，价值最高）

本轮先按直觉找了三个"回测协议 / 前视"方向的经典文献，**逐个下载后做词频核验**，
结果全部不承载本轮要的判据：

| 候选 | 取到载体 | 词频核验结果 | 处置 |
|---|---|---|---|
| Bailey, Borwein, López de Prado, Zhu — *The Probability of Backtest Overfitting*（34 页，`davidhbailey.com/dhbpapers/backtest-prob.pdf`） | ✅ PDF | `look-ahead` **0**、`point-in-time` **0**、`survivorship` **0**、`sins` **0**；`data snooping` 2 | **不采用**（是多重检验/PBO 论文，不是 PIT 论文） |
| Arnott, Harvey, Markowitz — *A Backtesting Protocol in the Era of Machine Learning*（11 页，`duke.edu` P138） | ✅ PDF | `look-ahead` **0**、`point-in-time` **0**、`survivorship` **0**、`timing` 1；`protocol` 18 | **不采用**（是研究流程协议，无时点判据） |
| Harvey & Liu — *Backtesting*（17 页，`duke.edu` P120） | ✅ PDF | `look-ahead` **0**、`point-in-time` **0**、`timing` **0** | **不采用**（是 haircut Sharpe / 多重检验） |

**这三条是本轮最重要的负知识**：文献里"回测协议"这一族**普遍不讨论前视偏差**；
"前视"这件事的判据实际落在**数据泄漏（leakage）**这一族，而不是"回测协议"这一族。
我最初的选族是错的，是词频核验把它纠正过来的——**不是**凭记忆认定。

### 8.3 我自己在本轮犯的错（必须记录，不得静默）

- **E-1（选族错误，已纠正）**：我一开始按"回测过拟合/回测协议"这一族去找判据，
  连续下载 3 篇、连续 3 篇词频为 0。**如果我不做词频核验、只凭论文标题与知名度**，
  本轮会拿一篇"看着很贴题、实际零判据"的论文来支撑审计——正是 R004 教训 14 的翻版。
  已登记为流程教训（`R005_report.md` §9）。

---

## 9. 可复现性

- 抽取命令：PyMuPDF `fitz` 逐页 `get_text()` 拼接后落盘 `.txt`（与 R001–R004 同口径）。
- 页数核验：`d.page_count == 29`。
- 第 1 页核验：标题 + `Sayash Kapoor` + `Arvind Narayanan` 逐字比对。
- 水印核验：正文含 `arXiv:2207.07048v1` ⟹ 确认是 **v1**，与元数据 `citation_date = 2022/07/14` 一致。
- 版本落差登记：①期刊版 `Patterns 2023` 的 PDF 不可得（403）；②`citation_doi` 在 arXiv 头部为 `None`。
  ⟹ 台账 `paper_ledger.csv` 的「载体」列写 `arXiv:2207.07048v1 (2022-07-14)`，
  「期刊版」列写 `Patterns 2023, doi:10.1016/j.patter.2023.100804（未读到）`。
