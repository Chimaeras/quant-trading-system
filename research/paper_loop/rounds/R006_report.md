# R006 报告：`rank_ic` 载荷族命名与定义一致性审计

| 项 | 值 |
|---|---|
| 轮次 | **R006**（审计轮，与 R004 / R005 同型） |
| 判据来源 | **AlphaEval**，arXiv:2508.13174v2（KDD 2026，doi 10.1145/3770855.3817727） |
| 论文审查 | `rounds/R006_review.md` |
| 预注册 | `rounds/R006_prereg.md`（**先于任何实验代码冻结**，本轮未修改） |
| 实验 | `experiments/R006_rank_ic_audit/{probe/R006Probe.java,run_rank_ic_audit.py,config.json,result.json,run.log}` |
| 被审计对象 | **项目自己命名的 `rank_ic` 载荷族**（`FactorResearchEngine` → 两个 Service → 前端标签） |
| 假设 H0 | 「`rank_ic_*` 所命名的量忠实对应文献定义，且定义失效时不输出看似估计值的具体实数」 |
| **总判定** | **`REJECTED`**（H0 被杀）——由 **K3 / K4 / K5** 三条独立触发 |
| 生产代码改动 | **0 行** |
| 新增测试 | **0 个**（试验性执行只在 `experiments/R006_rank_ic_audit/probe/` 下，编译产物已清理） |
| 新增影子因子 | **0 个**（审计轮不产生因子） |

---

## §1 本轮怎么做的

### 1.1 判据来源与「论文只当尺子」

论文**不是** H0 的作者。H0 是**项目自己的命名**在文献定义下被检视；论文只提供判据。
并按 `R006_review.md` §8 的强制要求，**逐行标注判据档位**：

- **L（文献判据）**：式 (5)(6) IC=Pearson 均值；式 (7)(8)(9) RankIC=Spearman 均值；
  式 **(18) ICIR 分母 ÷(T−1)**（式 (22) 的 σ_r 同为 T−1 ⟹ 论文**内部一致**）；
  Table 1 把 `IC, ICIR` 与 `RankIC, RankICIR` **并列区分为不同指标**；
  式 (5)/(7) 的 `1/T Σ_{t=1}^{T}` 是对**全部 T 期**取均值。
- **A（算术推导）**：式 (8) 的 `1 − 6Σd²/(N(N²−1))` **仅当无并列**时等于 Spearman ρ。
  论文全文 `\bties?\b` 命中**全部是人名 "Tie-Yan Liu"**，**零处**讨论并列 ⟹ 涉并列的断言一律**不得**写成「论文要求」。
- **P（项目自建）**：「定义失效时不得输出具体实数」是**工程契约**，非文献要求。

**论文不给任何阈值**（`R006_review.md` §2.5）⟹ 本轮全部判定都是**结构型/算术型**
（= 与 ≠、是否可见、分母是 T 还是 T−1、某组是否为空），**没有**任何「IC 太小」这类判定。

### 1.2 执行方式（跨语言，解决 R005 教训 7 的等价物）

R005 的教训是「阳性对照必须接到**同一函数对象**（`is` 断言），否则 PASS 只是掩码」。
本轮判据对象是 **Java 运行时行为**，Python 无法执行，因此：

- 探针 `probe/R006Probe.java` 编译进**引擎自己的包** `com.quant.service.backtest`，
  用 `-cp <repo>/modules/backend-java/target/classes` 运行 ⟹ **调用的就是生产类**；
- **门 G3**：启动即打印并校验 `FactorResearchEngine.class.getProtectionDomain().getCodeSource().getLocation()`。
  实测 `file:/E:/Project/quant-trading-system/modules/backend-java/target/classes/`，
  `is_repo_classes = true`；
- **防掩码哨兵**（证明真的被调用、不是常量桩）：
  `spearman([1..5],[5..1]) = -1.0`、`spearman([1,2,3,4,5],[1,3,2,5,4]) = 0.8`、`pearson([1..5],[5..1]) = -1.0`；
  再叠加 **203 例**输入扫描的输出校验和 `2896678`；
- **探针不重新实现任何生产量**。探针里自写的只有**判据侧对照值**
  （式 (8)、平均秩、÷(T−1) 标准差），且与生产实现**并列打印**。

### 1.3 阳性对照（金丝雀）与行和校验

- **20 个检查器 / 20 条金丝雀（INJ+CLN 成对）/ 0 个无金丝雀的检查器，全部 PASS**。
  金丝雀的 INJ 一律**扰动探针原始字段**（不是扰动派生量），所以检查器的推导链真的被走了一遍。
- **行和交叉校验**：`by_axis = {A1:3, A2:2, A3:3, A4:2, A5:3, A6:4, A7:2, A8:1}`，合计 **20**
  == `frozen_total` == 检查器数；变体映射**双向唯一**（每个断言恰一个变体、每个变体恰一个断言）。
- **额外加了 config 漂移守卫**：脚本内常数必须与冻结副本 `config.json` 逐项相等，
  否则行和校验失败 ⟹ K9。实测 7 项全 `True`。

---

## §2 逐轴结果（20 个断言）

判定词表冻结为 8 个（`NOT_VIOLATED` / `VIOLATED` / `CONVENTION_DIVERGENCE` / `CONTRACT_AMBIGUOUS` /
`DEAD_COLUMN` / `NOT_REACHABLE` / `REGISTERED_ONLY` / `INCONCLUSIVE`），未新增。

### A1 命名族归属

| 断言 | 判定 | 证据 |
|---|---|---|
| **A1a** 分子分母是否同源于 rank 序列 | `NOT_VIOLATED` | `rank_ic_ir = -2.153084275534054` 与「rank 序列 ÷T」**逐位相等**；而「Pearson 序列 ÷T」为 `-16.5438` ⟹ 该量**确为 RankICIR**。两序列平均绝对差 `0.4702` ⟹ 构造的输入确实能区分两者 |
| **A1b** 展示标签是否含 `Rank` 限定 | **`VIOLATED`** | 6 个标签中 **5 个缺限定**：`IC 标准差`（绑定 `rank_ic_std`）、`ICIR`（绑定 `rank_ic_ir`）、`IC 均值`（另一块里绑定 `rank_ic_mean`）——`FactorResearch.vue:133/:134/:239`。同一文件 `:132` 却写 `Rank IC 均值` ⟹ **文件内部即不一致** |
| **A1c** 是否与 `Pearson IC` 并排形成混合配对 | **`VIOLATED`** | 同一张卡片同时展示 `Pearson IC`（Pearson 序列）与 `ICIR`（**rank 序列**），且**全仓 0 处** `RankICIR` 标签。论文 Table 1 中 ICIR 的分子是 **IC(Pearson) 序列** ⟹ 读者会自然把它们读成一对，但**项目未提供 Pearson 版 ICIR** |

> A1a 是**正面发现**：数值自洽。A1b/A1c 是**命名/契约**问题（K6 ⟹ `CONTRACT_AMBIGUOUS`），**不是数值 bug**。

### A2 Spearman 与式 (8) 的等价性

| 断言 | 判定 | 证据 |
|---|---|---|
| **A2a** 无并列输入上实现 == 式 (8) | `NOT_VIOLATED` | 203 例（3 定长 + 200 随机 `n∈[2,40]`）；`max|实现 − 定义值| = 0.0`；`max|实现 − 式(8)| = 1.11e-16`（机器精度）；反向锚点 `−1.0` |
| **A2b** 有并列输入上实现仍 == 定义值，而式 (8) 偏离 | `NOT_VIOLATED`（**正面发现**） | 5 例重并列/全并列：`max|实现 − 定义值| = 0.0`，而 `max|式(8) − 定义值| = 0.2471` ⟹ 项目用**平均秩 + Pearson**，比论文印出的 `1 − 6Σd²/(N(N²−1))` 更严格 |

> A2b 的性质已在预注册中声明为 **A 档（算术推导）**：论文式 (8) **未加**并列限定语、全文不讨论并列。
> **不得**把 A2b 写成「论文要求平均秩」。

### A3 ICIR 分母：T−1（文献） vs T（项目）

| 断言 | 判定 | 证据 |
|---|---|---|
| **A3a** `rank_ic_ir` 落在 8 单元网格的哪一格 | **`CONVENTION_DIVERGENCE`** | 两个面板（T=17 / T=10）的匹配单元**都只有** `div_T` ⇒ 项目用**总体标准差 ÷T**；论文式 (18) 用 **÷(T−1)**。网格含**缩放/年化维**（R005 教训 E-2 的落实），未只报最佳单元 |
| **A3b** 两口径之比必须恰为 `sqrt(T/(T−1))` | `NOT_VIOLATED` | 实测 `1.0307764064044151` / `1.0540925533894596` 与理论值差 `0.0` / `2.2e-16` ⟹ 偏差**恰为**该因子 |
| **A3c** `rank_ic_std` 同判 | **`CONVENTION_DIVERGENCE`** | `rank_ic_std = 0.22622885` == `std_pop`；`std_sample = 0.23319136`（不匹配）。两面板同判 |

> **量级必须如实标注**：T=17 ⟹ **+3.08%**；T=10 ⟹ **+5.41%**。项目 |IR| 比论文口径**大**这一因子。
> **单独绝不足以判 `REJECTED`**（K2 只给 `CONVENTION_DIVERGENCE`）。

### A4 静默丢日与「对结果取选择」

| 断言 | 判定 | 证据 |
|---|---|---|
| **A4a** 退化日是否被静默丢弃且无披露字段 | **`VIOLATED`** | 输入 **7** 个截面日（4 正常 + 3 退化：得分在该截面恒定 ⟹ 秩零方差 ⟹ IC 非有限）⟹ `cross_section_days = 4`、`daily_size = 4`、`total_observations = 48 = 4×12`（退化日**零贡献**），**丢弃 3 天**；`rank_ic_positive_rate` 的分母同为 `daily_size`；而**披露字段命中 0**（`dropped_days|skipped_days|excluded_days|filtered_days|nan_days|invalid_days|dropped_count|discarded` 在生产代码中一处都没有）。代码位：`FactorResearchEngine.java:150-152` |
| **A4b** `pearson_ic_mean` 与 `rank_ic_mean` 的分母是否恒同 | `NOT_VIOLATED` | 300 例扫描：`finite(both)=100`、`NaN(both)=200`、**`finite(ic) only = 0`、`finite(pic) only = 0`**；退化面板上 `pearson_kept_days = daily_size = 4`，`pearson_mean_over_kept == pearson_ic_mean == -0.09538828375142752` ⟹ 代码的双重 `isFinite` 过滤**冗余但无害** |

> **A4b 是我预注册的算术预测**（`var(scores)=0 或 var(rets)=0 ⟺ 两处均非有限`），**实测未被证伪**。
> 它同时是 A6c 的基础。A4a 与论文式 (5)/(7) 的 `1/T Σ_{t=1}^{T}`（对**全部 T 期**取均值）冲突，
> 且丢弃发生在**观测到结果之后** ⟹ 属对结果取选择。

### A5 空组伪造 0 进入 `long_short_spread`

| 断言 | 判定 | 证据 |
|---|---|---|
| **A5a** 是否存在被伪造为 0 的空组 | **`VIOLATED`** | `n=12`、`groupCount=20` ⟹ `grouped_size=20`、**`empty_groups=8`**（`observations==0 且 avgReturn==0.0`）。根因：`:102 Math.min(groupCount, n)` 单日只产 `g` 组，`:180-183` 却按 `1..groupCount` 全建 |
| **A5b** `spread` 是否等于 `0 − G1` | **`VIOLATED`** | `long_short_spread = -0.012665740650684378`，`g1_avg = +0.012665740650684378`，`recomputed_0_minus_g1 = -0.012665740650684378` **逐位相等**；`glast_obs = 0` ⟹ 「多空 spread」退化为**负的底层组平均收益**。判据档位 **L**：论文式 (19) 要求每腿恰 K 只（`w = ±1/K`），空腿使该定义不成立 |
| **A5c** 下游能否区分空组 | `NOT_VIOLATED`（**减缓项**） | 每组 `observations` **有导出**（`FactorResearchService.java:599/:784`、`ScoringBacktestService.java:1043` 三处 `.observations(...)`）**且被渲染**（`ScoringBacktest.vue:378`「截面日观测」、`:507`、`FactorResearch.vue:151/:254`「观测数」）⟹ 空组在表格里显示为 `观测数 0`。**但** `long_short_spread` 这个**标量**在别处单独展示、无「存在空组」标注 ⟹ 标量仍可被误读 |

> **可达性（必须与 A6b 区分开）**：`FactorResearchService` 三处调用点**全部**传常量
> `RANK_IC_GROUP_COUNT = 5`（`:70`）⟹ 该端点组数**不可由请求控制**；
> `ScoringBacktestService:78` 用请求字段，而 `ScoringBacktestRequest.java:38` 的 `factorGroupCount`
> **无 `@Min`/`@Max`**；两个 Service 的截面下限常量**都是 10**（`FactorResearchService.java:71`、
> `ScoringBacktestService.java:58`）⟹ 参与统计的日必有 `n ≥ 10` ⟹ 空组**只能**由 `groupCount ≥ 11` 触发；
> 前端控件 `ScoringBacktest.vue:36` `:min="2" :max="10"` ⟹ **经界面不可达，经直接 API 调用可达**。
> A5a 面板的 `monotonicity = NON_MONOTONIC`（尾部零组打破单调性），与 A7a 是**两回事**。

### A6 定义失效时伪造具体实数

| 断言 | 判定 | 证据 |
|---|---|---|
| **A6a** 零有效观测是否输出貌似估计值的具体实数 | **`VIOLATED`** | `summarize(∅)` ⟹ `rank_ic_mean / rank_ic_std / rank_ic_ir / rank_ic_positive_rate / pearson_ic_mean / long_short_spread` **全为 `0.0`**，`daily_size = 0`、`grouped_size = 0`。判据档位 **P** |
| **A6b** `std=0` 且 `mean<0` 时 IR 是否写成 0 | **`VIOLATED`** | T=1 面板：case0 `mean=+1.0, std=0.0, ir=0.0`；**case1 `mean=-1.0, std=0.0, ir=0.0`**。代码位 `:174 icIr = icStd > 1e-12 ? icMean / icStd : 0` |
| **A6c** `pearsonIcMean` 的 `? 0` 分支是否可达 | `NOT_REACHABLE` | 该行（`:176`）位于 `:166-168 if (daily.isEmpty()) return emptySummary` **之后** ⟹ 到达它意味着 `daily` 非空；再由 A4b 的等价性 ⟹ `dailyPearson` 非空 ⟹ **`0` 分支恒不执行**（死分支） |
| **A6d** `selector_result.ic_value` 是否死列 | `DEAD_COLUMN` | 全仓 `ic_value` **生产口径恰好 1 处**（`V2__research_tables.sql:183` 的 DDL 本身），**无任何写入路径**；DB 实测 `COUNT(*) = 67`、**`COUNT(ic_value) = 0`** |

> **A6b 的可达性（本轮最有力的一条，与 A5 完全不同）**：只需**一个**参与统计的截面日即得 `std = 0`。
> 此时 `rank_ic_mean` 仍显示当日真实 IC，出现**同一份载荷内 `mean = −1.00` 而 `ir = 0.00`** 的自相矛盾配对。
> 方向性：`mean>0` 写成 0 属**偏保守**；**`mean<0` 写成 0 属偏乐观**（真极限为 `−∞`，却被写成中性）。
> 这与 A5 的「仅直接 API 可达」是**两种不同的可达性等级**，报告中不得混同。
>
> **A6a 的诚实边界**：`monotonicity = "N/A"` 与 `universe_note`（实测 `"R006-V14"`）提供了**部分**可区分信息
> ⟹ **不得**叙述为「完全不可区分」。A6a 与 `D-R004-03`、`D-R005-02` 是**同一形态的第三/第四个实例**
> （前两例在 DB 与治理层，本例首次落在**运行时代码的 IC 路径**上）。

### A7 单调性退化（项目自建，`REGISTERED_ONLY`）

| 断言 | 判定 | 证据 |
|---|---|---|
| **A7a** 各组收益全相等是否判 `MONOTONIC_INCREASING` | **`VIOLATED`**（但**不进**总判定） | 单日、`n=10`、`groupCount=5`：五组均值**全为 `0.01`**（`group_avg_max_gap = 0.0`），而个股收益方差 `> 0`（否则 IC 非有限、走不到该分支）⟹ 返回 **`MONOTONIC_INCREASING`**，即**零区分度被判成正向单调**。代码位 `:226-249`（全相等时 `increasing` 与 `decreasing` 同真，先判 `increasing`） |
| **A7b** 该轴不进入杀判据 | `REGISTERED_ONLY` | 冻结表内**无**任何 K 引用 A7a；金丝雀 INJ 把 A7a 非法扩入 `REJECTED` 时检查器会报 `VIOLATED` ⟹ 范围冻结真的被守卫 |

### A8 标签口径差异（范围声明）

| 断言 | 判定 | 证据 |
|---|---|---|
| **A8** 式 (1) 的 `close→close` 与项目冻结口径的差异 | `REGISTERED_ONLY` | 论文式 (1) 是**信号日收盘 → 未来收盘**的简化标签，**不含** T+1、涨跌停、停牌、执行状态；项目冻结口径为「signal_date **下一交易日开盘**入场 → 第 h 持有日**收盘**退出」。按协议「论文中的简化假设不得直接覆盖项目已有金融研究口径」⟹ 只记差异 **D-2**，**不构成任何 kill 轴** |

---

## §3 总判定与 K 触发

按预注册 §6 **冻结的合成规则**（不得事后放宽）：

| K | 内容 | 是否触发 |
|---|---|---|
| K1 | A2a `VIOLATED` | ✗ |
| K2 | A3a `VIOLATED` ⟹ `CONVENTION_DIVERGENCE` | **✓**（仅登记，不杀） |
| **K3** | A4a `VIOLATED`（静默丢日且无丢弃计数字段） | **✓ ⟹ REJECTED** |
| **K4** | A5b `VIOLATED`（空组伪造 0 进入 spread） | **✓ ⟹ REJECTED** |
| **K5** | A6b `VIOLATED`（`std=0` 且 `mean<0` 时 `rank_ic_ir == 0.0`） | **✓ ⟹ REJECTED** |
| K6 | A1b / A1c ⟹ `CONTRACT_AMBIGUOUS` | ✓（不杀） |
| K7 | A6d ⟹ `DEAD_COLUMN` | ✓（不杀） |
| K8 | A7a ⟹ `REGISTERED_ONLY` | ✓（不杀） |
| K9 | 行和交叉校验失败 | ✗ |
| K10 | 阳性对照未通过 | ✗ |

**总判定 = `REJECTED`**，理由（`result.json` 原文）：
> `H0 被杀：K3(A4a=VIOLATED)/K4(A5b=VIOLATED)/K5(A6b=VIOLATED) 触发`

⟹ H0（「`rank_ic_*` 命名族忠实于文献定义且失效时不伪造实数」）**被三条相互独立的判据杀死**。
**这是负面结果，不是「修好了」**：本轮**不改任何生产代码**，全部转为待决策事项（§9）。

---

## §4 预注册预测的检验（P-1..P-10）

| 预测 | 内容 | 实测 | 结果 |
|---|---|---|---|
| **P-1** | A2a = `NOT_VIOLATED` | `NOT_VIOLATED` | ✅ |
| **P-2** | A2b = `NOT_VIOLATED` 且式 (8) 偏离 `> 0` | `NOT_VIOLATED`；偏离 `0.2471 > 0` | ✅ |
| **P-3** | A3a = `CONVENTION_DIVERGENCE`（等于 ÷T） | `CONVENTION_DIVERGENCE` | ✅ |
| **P-4** | A4a = `VIOLATED` | `VIOLATED` | ✅ |
| **P-5** | A4b = `NOT_VIOLATED`（两侧分母恒同） | `NOT_VIOLATED`（0/300 反例） | ✅ |
| **P-6** | A5b = `VIOLATED` 且逐位等于 `0 − G1` | `VIOLATED`，逐位相等 | ✅ |
| **P-7** | A6b = `VIOLATED`，`T=1` 即可构造 | `VIOLATED`，`T=1` 构造成功 | ✅ |
| **P-8** | A6d = `DEAD_COLUMN`（非空 0 行） | `DEAD_COLUMN`，0/67 | ✅ |
| **P-9** | 总判定 = `REJECTED` | `REJECTED` | ✅ |
| **P-10** | 测试套件计数不变（Java 350 / Python 343） | 见 §10 | ✅（零生产/测试改动） |

**10/10 命中。** 但必须同时说明：**其中 5 条（P-3/P-4/P-6/P-8 等）在冻结时已被读码确认**，
不具预测性——见 §5 的分栏，**不得**把这 10/10 叙述成「模型预测能力」的证据。

---

## §5 描述性 vs 预测性（预注册 §3.1 的落实）

| 类别 | 断言 | 说明 |
|---|---|---|
| **真正的预测性内容（6 条）** | `A2a`、`A2b`、`A4b`、`A5c`、`A6b`（可构造性）、`A6d`（行数） | 冻结时**未实测**；其中 `A2a` 与 `A5c` 的实测结论**与我预期的方向不同**（见下） |
| **描述性（已读码事实的规格化登记）** | `A1a`、`A1b`、`A1c`、`A3a`、`A3c`、`A4a`、`A5a`、`A5b`（事实）、`A6a`、`A6c`、`A7a` | 冻结时判据与事实都已知，本轮只是**可执行化 + 留下证据** |

**两条方向出乎意料的地方（如实报告）**：
1. **A2a 的 `max|实现 − 式(8)| = 1.11e-16`** —— 我原本以为无并列时两者会有可见的浮点差异，
   实测是**机器精度级的一致**，说明项目的平均秩实现与论文印出的闭式在无并列时**完全等价**。
2. **A5c 判 `NOT_VIOLATED`** —— 我原本预期「空组在下游不可区分」，实测 `observations` **既有导出也有渲染**，
   空组在表格里可见。**减缓项成立**，我预期的缺陷形态**不成立**，必须照实报告。

---

## §6 量级与可达性的诚实标注（防夸大）

| 发现 | 量级 | 可达性等级 |
|---|---|---|
| A3 ICIR 分母口径差 | **+3.08%（T=17）/ +5.41%（T=10）** —— **微弱** | 任何 T≥2 的正常调用 |
| A4a 静默丢日 | 注入面板丢 3/7 天；**真实库的规模未测**（本轮未跑生产路径的退化统计） | 任何含恒定得分截面的输入 |
| A5 空组伪造 0 | `spread` 符号反转、数值完全由 `G1` 决定 | **仅直接 API 调用（`factorGroupCount ≥ 11`）；经界面不可达** |
| A6b `std=0 → ir=0` | 从 `±1.0` 变成 `0.0` —— 数值差异大，但仅在退化的 T=1/IC 恒定输入下 | **经正常 API 可达**（需 `n ≥ 10` 的单截面日） |
| A6d 死列 | 0/67 行非空 | 结构性，不涉及运行时 |
| A7a 单调性退化 | 标签错误（`MONOTONIC_INCREASING` vs 实际零区分度） | 经 API 可达；**但不进总判定** |

**合并口径声明**：本轮**不主张**任何「收益/成本/净值」层面的结论——那属于 R003/R004 的领域。

---

## §7 与既有轮次的关系（防回溯改写）

1. **未重开 R001、未翻 R002、未重估 R003 的 `r*`、未回改 R004 的六轴**（含 R004-K2 的「半触发」读法）。
2. **未修改 `R005_prereg.md`**；**未修** R005 的 `A4b`/`A9` 发现。
3. **未把 R005 的 `REJECTED` 叙述为「发现了未来函数」**：R005 的 H1a（未来函数）**未被杀死**，
   其 `REJECTED` 来自 K3b。本轮沿用该表述，未做任何简化。
4. **未用式 (1) 覆盖 `ResearchConvention`**（A8 只记差异 D-2）。
5. **未用式 (19)(20) 翻 `D-R003-01` 的案**：论文式 (19)-(21) 的 AR 建立在**毛收益**上、
   **全文无成本项**，因此它**既不证实也不证伪** R003 的「成本腿数」主张。
   本轮只得出「均值之差这个结构在文献里是标准的」这一条与 R003 同向的**结构**表述。
6. **与 R004 的分工**：R004 `A6` 问「分组数 J 是不是调参量」，本轮 `A5` 问「**组为空时输出什么**」——
   **同一函数、不同问题**，不是重复登记。
7. **与 R005 的分工**：R005 `A9` 是「**DB 里**的占位 0」（`selector_result.avg_amount20`），
   本轮 `A6a/A6b` 是「**运行时代码里**的占位 0（IC 路径）」——**同一根因的新实例**，报告如实如此表述。

---

## §8 本轮**不得**被叙述成什么（负面清单）

1. ❌「项目 IC 太低 / ICIR 不够高」——论文**不给阈值**，本轮不做任何数值合格线判定。
2. ❌「发现了未来函数」——本轮**不涉及** PIT/前视；A4a 是**选择性丢弃**，不是前视。
3. ❌「R003 判错了」——见 §7.5。
4. ❌「A2b 证明论文的式 (8) 是错的」——论文未讨论并列，这是**算术事实**（A 档），不是论文的错误主张。
5. ❌「用户可见的 spread 会经常是负数」——A5 需 `groupCount ≥ 11`，**经界面不可达**。
6. ❌「空组完全不可区分」——`observations` 有导出也有渲染（A5c 判 `NOT_VIOLATED`）。
7. ❌「零观测时完全不可区分」——`monotonicity="N/A"` 与 `universe_note` 提供部分可区分性。

---

## §9 台账与队列动作（**只登记，不修**）

新增待决策项（写入 `decision_queue.md`，全部 `PENDING_USER`）：

| ID | 类型 | 事项 | 关联 |
|---|---|---|---|
| `D-R006-01` | 公开 API/前端契约 | `rank_ic_std` / `rank_ic_ir` 的展示标签缺 `Rank` 限定，且与 `Pearson IC` 并排形成混合配对（读成 `IC/ICIR` 一对，实为 rank 序列）；全仓 0 处 `RankICIR` | A1b/A1c（K6） |
| `D-R006-02` | 冻结口径 / 统计约定 | ICIR 分母用总体标准差 ÷T，论文式 (18) 为 ÷(T−1)（论文内部一致）；偏差恰为 `sqrt(T/(T−1))` = +3.08%(T=17)/+5.41%(T=10) | A3a/A3b/A3c（K2） |
| `D-R006-03` | 公开 API / 退化输入契约 | 退化截面日（IC 非有限）被**静默丢弃**，同时从均值/标准差/正率/`cross_section_days` 的分母消失，且**无任何丢弃计数字段** | A4a（K3） |
| `D-R006-04` | 公开 API / 退化输入契约 | `groupCount > n` 时空组被伪造为 `avg=0/obs=0`，`long_short_spread` 退化为 `0 − G1`（符号反转）；**仅直接 API 可达**（DTO 无 `@Min/@Max`，前端上限 10） | A5a/A5b（K4）+ A5c 减缓 |
| `D-R006-05` | 退化输入契约（**须与 D-R004-03 / D-R005-02 合并评审**） | 定义失效时输出具体实数：`emptySummary` 六项全 0（部分可区分）；**`std=0` 时 IR 写成 0，`mean<0` 时方向偏乐观**（T=1 即触发）；`pearsonIcMean` 的 `?0` 分支为死分支 | A6a/A6b/A6c（K5） |
| `D-R006-06` | 库表结构 | `selector_result.ic_value` 是**死列**（全仓唯一出现处是 DDL，0/67 行非空）；同表另有 R005 `D-R005-01` 的权威字段缺失 | A6d（K7） |
| `D-R006-07` | 展示语义 | 各组收益全相等（零区分度）被判 `MONOTONIC_INCREASING` | A7a（K8，`REGISTERED_ONLY`） |

新增阻塞项（`blocked_queue.md`）：`B-R006-01` —— `selector_result.ic_value` **无写入路径**，
任何以该列为输入的研究（例如「存储 IC 值 vs 重算 IC 值」的一致性审计）在本地**结构性不可行**。

`shadow_registry.md`：本轮**无新因子、无影子提升请求**（审计轮）。

---

## §10 测试验证与 hygiene

### 10.1 测试套件（零生产 / 零测试改动，预期与基线一致）

| 套件 | 命令 | 结果 | 基线（R004/R005） |
|---|---|---|---|
| Java | `mvn -o -B test -pl modules/backend-java` | **BUILD SUCCESS**；逐类汇总 `surefire-reports/*.txt`：`classes = 64`、**`tests = 350`**、`failures = 0`、`errors = 0`、`skipped = 0` | Java **350** ✅ 一致 |
| Python | `python -m pytest tests paper/tests`（cwd = `modules/compute-python`） | **343 passed**，3 warnings（13.18s） | Python **343** ✅ 一致 |

Python 的 3 条 warning 均为**既有**告警，非本轮引入：
① `requests` / `urllib3 2.5.0` 版本不匹配；② `test_data_engine.py:45` pandas dtype FutureWarning；
③ `test_research_tools.py:25` 的 close-to-close 口径 UserWarning。

⟹ **本轮无任何生产代码或测试代码改动**（`R006` 的全部试验性执行都在
`experiments/R006_rank_ic_audit/probe/` 下，编译产物 `probe/out/` 运行后由编排器自动清理）。

### 10.2 hygiene

| 检查 | 命令 | 结果 |
|---|---|---|
| 空白 / 冲突标记 | `git diff --check` | **exit 0**（仅 LF→CRLF 提示，无 whitespace error、无 conflict marker） |
| 密钥扫描 | `grep -rInE "sk-[A-Za-z0-9]{16,}\|ghp_[A-Za-z0-9]{20,}\|AKIA[0-9A-Z]{12,}\|password\s*[:=]..."`（扫本轮全部新增/修改路径） | **0 命中** |
| 大文件（阈值 2,097,152 B） | `find research/paper_loop -type f -size +2097152c` | **2 个命中，均显式披露（见下）** |
| 编译产物残留 | `find . -name "*.class" -not -path "*/target/*"` | **0** |
| `__pycache__` | `find research scripts -name __pycache__ -type d` | 仅 `scripts/__pycache__`（**既有、已被 `.gitignore:45` 忽略**，本轮未产生新 `__pycache__`） |
| 改动范围 | `git status --short` | 全部落在 **`research/paper_loop/**`**（5 个修改 + 6 个新增） |

**大文件的两条例外（显式披露，不隐藏）**：

1. **`papers/alphaeval_arxiv_2508.13174.pdf` = 8,310,367 B（8.3 MB）——本轮新增，超阈值判据 4.0 倍。**
   这是论文**载体本身**（PDF 首页含 KDD 2026 与 ACM 信息，删掉就无法核验身份）。
   `sha256 = 94a7dde968d3965c07ffaac65f9aa2e265c802ceea5e2ac819b4c98ad72aad49`
   （与 `R006_review.md` §1 记录的值一致）。
   **已确认这是既有约定**：`git ls-files research/paper_loop/papers/` 显示 R001–R005 的
   **6 篇论文的 `.pdf` + `.txt` 均已入库**（`ff2015` / `ccf2019` / `kn2022` / `lr2018` / `lsy2018` / `nmv2016`）。
2. **`papers/nmv2016_rfs_trading_costs.pdf` = 2,099,323 B——R003 已提交的既有文件**，
   同样超阈值（1.001 倍）。**本轮未修改它**，仅在此一并登记，以免后续轮次把它当成新超限。

⟹ 结论：本轮**未引入新的超限类型**，只是把既有「论文 PDF 入库」约定继续沿用；
若后续要收紧，应作为**独立工作项**统一处理（属协议 §7 保留权限，本轮**只登记不做**）。

### 10.3 运行次数与失败运行保留

`run.log` 共 **5 次**运行（append，**未删除任何一次**）：

| 次 | 时刻 | 结果 | 说明 |
|---|---|---|---|
| 1 | 20:45:23 | （无判定） | `javac rc=0` 后崩溃（GBK 输出解码失败），未产出 result |
| 2 | 20:45:33 | **INCONCLUSIVE** | `A3a`/`A3b` **金丝雀 FAIL** ⟹ 阳性对照未过，判定无效（E-1/E-2） |
| 3–5 | 20:46:05 / 20:46:37 / 20:47:08 | REJECTED | 金丝雀 20/20 PASS；第 5 次为最终 `result.json` |

### 10.4 最终运行的四个自证量（`result.json` 实测）

| 量 | 值 |
|---|---|
| `gate_G3.is_repo_classes` | **true**（`file:/E:/Project/quant-trading-system/modules/backend-java/target/classes/`） |
| `canaries` | `checkers = 20`、`canaries = 20`、**`checkers_without_canary = []`**、`all_passed = true` |
| `variant_budget` | `total_variants = 20` = `frozen_total = 20` = `declared_checkers = 20`；`axis_keys_match = true`、`variant_map_complete = true` |
| `kill_conditions_fired` | `K3/K4/K5 = true`（三条独立触发）｜`K1/K9/K10 = false`｜`K2/K6/K7/K8 = true`（只登记不杀） |
| `total_verdict` | **`REJECTED`** |

---

## §11 本轮不该做的事（已遵守的自证）

- 生产代码 `modules/backend-java/src/main/**`、`modules/web/**`：**0 行改动**。
- 测试代码：**0 行改动**（探针放在 `experiments/` 下，编译产物 `probe/out/` 运行后删除，
  仓库内 `*.class` 计数 = **0**）。
- `rounds/R005_prereg.md`：**未改动**。
- 未启用真实交易、未下单、未读取真实凭证、未改公开 API/库表、未引入新数据源、未删除历史数据。

---

## §12 提交信息（已回填）

- commit id：**`aac01a56`**（`research(R006): rank_ic 命名族定义一致性审计（REJECTED，K3/K4/K5）+ 静默丢日/空组伪造0/std=0时IR写0`）
- push：**成功** —— `3f457646..aac01a56  HEAD -> master`（`git push origin HEAD`，**普通 push，无 `--force`**）；**无 PUSH_PENDING**
- 运行次数：`run.log` 为本轮**第 5 次**运行（append 保留全部 5 次，含 2 次失败运行，未删除）
- 测试：Java **350** passed / 0 failed（64 个 surefire 类）；Python **343** passed —— 与 R004/R005 基线**一致**
- hygiene：`git diff --check` exit 0（仅 LF→CRLF 提示）；密钥扫描 **0 命中**；大文件检查（阈值 2,097,152 B）
  **2 个命中均显式披露**（本轮新增 `alphaeval_arxiv_2508.13174.pdf` 8,310,367 B、
  R003 既有的 `nmv2016_rfs_trading_costs.pdf` 2,099,323 B）；`*.class` 残留 **0**；
  `__pycache__` 本轮未产生（`scripts/__pycache__` 为既有且已被 `.gitignore` 忽略）
- 本轮新增文件：`papers/alphaeval_arxiv_2508.13174.{pdf,txt}`、`rounds/R006_{review,prereg,report}.md`、
  `experiments/R006_rank_ic_audit/{probe/R006Probe.java,run_rank_ic_audit.py,config.json,result.json,run.log}`
- 修改文件：`paper_ledger.csv`、`progress.md`、`decision_queue.md`、`blocked_queue.md`、`shadow_registry.md`

---

## 附录 A 勘误

> 预注册 `R006_prereg.md` **未被重写**。以下为**我自己的**错误或表达不足，只在此登记。

**E-1（我的探针 bug —— 本轮唯一一次「判定值因修 bug 而改变」）**
`R006Probe.java` 的 V07 把「两口径之比」算成了 `ir_paper / ir_project`，
而冻结文本要求的是**项目 ÷ 论文**（= `sqrt(T/(T−1))`，即项目 |IR| 的**放大**因子）。
第 2 次运行据此判 `A3b = VIOLATED`（实为把 `0.9701` 当成 `1.0308`）。
修正方向后 `A3b = NOT_VIOLATED`，实测比值与理论值差 `0.0 / 2.2e-16`。
**冻结判据未修改**；两次运行的日志都保留在 `run.log`（append，未删除）。
与 R005 的 E-3 / R004 的 E-4 同类：**某轴判定值因修我自己的 bug 而改变**。

**E-2（我的金丝雀设计错误，未影响任何判据）**
A3a 的金丝雀 INJ 原本改的是 `grid_rank_1_div_T` 单元，导致 clean/inj 都落到 `INCONCLUSIVE`。
改为改 `rank_ic_ir` 本身（模拟「项目其实用 ÷(T−1)」）后通过。
属**阳性对照设计错误**，不改变 A3a 的真实判定。

**E-3（预注册 §8 的 C1 命令存在「自指污染」，但我在冻结时没预见到）**
C1 的冻结命令不排除 `research/`，而本轮自己的编排脚本里出现了 `ic_value` 字样
⟹ 原始输出 **13 行 = 1 行生产 DDL + 12 行本轮自指产物**。
判据按**生产口径**（`modules/**`）计 **1 行**，与冻结期望一致；
**冻结命令文本未改**，两种口径的计数都写入了 `result.json`。
教训（供下轮）：**冻结的计数命令必须显式声明扫描根**，否则本轮产物会污染下一轮的计数。

**E-4（预注册 §3 的判定词表达不足 —— 非结果驱动的放宽）**
预注册对 A5a/A6a/A7a 只写了「记事实」「加重判定」，**没有指定判定词**。
我在**实现期**把「事实成立」映射为 `VIOLATED`（A7b/A8 为 `REGISTERED_ONLY`）。
该映射**未在冻结时写明**，属预注册表达不足；但它**不改变总判定**——
K 表只认 A2a/A3a/A4a/A5b/A6b/A1b/A1c/A6d/A7a，其中 A5a/A6a 不在 K 表内。
下轮起：**每个断言在冻结时必须写明判定词**。

**E-5（判据侧实现的性质，主动披露）**
A2a/A2b 的「定义值」由探针内的独立实现（`avgRanks` + `defPearson`）给出，
即**一致性本身是被测对象**。防掩码依靠三件独立证据：G3 的类加载路径校验、
反向锚点哨兵（`−1.0` / `0.8`）、以及 **203 例**输入扫描的输出校验和。
这不是 `assertSame` 式的同一对象断言，属**跨语言场景下的等价物**，如实标注而非淡化。

**已知局限（非错误，供后续轮次使用）**
1. A1b/A1c 的标签抽取是**单行正则**（`>标签：{{ 表达式 }}`）⟹ 前端改成多行模板会**漏检**。
2. A5 的可达性论证基于**读码**（C4/C6/C7/C8），**未发真实 HTTP 请求**；若将来给 DTO 补 `@Max`，
   该轴应立即改判 `NOT_REACHABLE`。
3. A4a 的「真实库丢日规模」**本轮未测**（只测了注入面板），不得外推。
4. A6c 的 `NOT_REACHABLE` 与 A4b 的结论**互为依赖**：若 A4b 被证伪，A6c 应改判 `INCONCLUSIVE`。
5. 论文式 (7)(8) 的排版把求和号放错位（式 (7) 写 `RankIC = 1/T RankIC_t` 而把 `Σ_{t=1}^{T}` 印进式 (8)）
   ⟹ 判据按**语义**使用，**未照抄字符**。

---

### 附录 A-6（跨轮事后更正 · R009 交叉复核，2026-09-14 登记）

> 本条为**事后追加**，**不重写**本报告上文任何冻结文本，**不改变 R006 的任何其他判定**。
> 来源：`rounds/R009_prereg.md` §4（A6b 冻结判据）与 `rounds/R009_report.md` §2.1 / §7。

**E-6（A4b 内部引用的「A 档等价」被实测证伪 —— 但 A4b 本身的判定未被证伪）**

R006 A4b 的论证里把「`ic` 有限 **⟺** `pearson` 有限」当作 **A 档等价**使用。
R009 在**同一面板** `P1s` 上实测到该等价**为假**：

```text
isFinite(spearmanRankCorrelation(P1s)) = true   （值 1.0）
isFinite(pearsonCorrelation(P1s))      = false  （NaN）
```

机制：`spearmanRankCorrelation` **复用同一个** `pearson`，但喂进去的是**秩**；
秩空间平方和 `Σ(rank−mean)² = n(n²−1)/12 = 82.5`（`n = 10`），比守卫 `1e-18` 高约 **19 个数量级**
⟹ 秩侧**永不**触发守卫，而原始分值侧会。故两侧有限性可分离，`⟺` 不成立。

**本条证伪的范围（精确界定，不夸大）**

| 对象 | 是否被本条证伪 |
|---|---|
| 「`ic` 有限 ⟺ `pearson` 有限」这一**等价主张** | **是**（有反例面板） |
| R006 A4b 的**自身判定** `NOT_REACHABLE`（`round4(NaN)=0.0` 机制存在、路径不可达） | **否** —— R009 独立复测得同一判定 |
| R006 其余任何轴（A1/A2/A3/A5/A6/A7/A8） | **否** |
| `round4(NaN)=0.0` 在生产载荷上产生 `0.0` 占位 | **否** —— 该路径仍判不可达 |

**关于反方向（不自行改判）**

若 A4b 的本意**不是等价**而是**单向蕴含**（`pearson` 有限 **⟹** `ic` 有限），
则该方向**未被**本条证据证伪（`pearson` 有限意味着原始分值侧未触发守卫，分值并非常量，
秩侧亦非常量）⟹ **A4b 究竟主张等价还是蕴含，属用户裁定事项，本报告不代作判断**。

**与本节「已知局限」第 4 条的交叉影响（如实披露，未触发改判）**

第 4 条声明「若 A4b 被证伪，A6c 应改判 `INCONCLUSIVE`」。
本条的精确结论是**只**证伪了 A4b 内部的等价主张，**未**证伪 A4b 的判定本身
⟹ 依赖条件**未**被触发，故 R009 **未**改判 A6c（亦未发现其他牵连证据）。
若用户裁定的方向是「A4b 实为等价主张」，则第 4 条的触发条件成立，
A6c 应被重新评估——**该项留待用户裁定，agent 不自行改判**。

**后续实现侧更正（供复核，非本报告结论）**

R009 之后，`pearson_ic_mean` 在「无 Pearson 支撑日」时改为 **`null`**（此前硬写 `0.0`），
并在载荷中并列发射 `pearson_support_days`，使「线性 IC 恰为 0」与「线性 IC 不可定义」可区分
（`D-R009-02`/`D-R009-04`）。这属**生产侧的独立修复**，与本节对 A4b 的勘误无判定关系。
