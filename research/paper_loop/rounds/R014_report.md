# R014 报告：`factor_return` 族两数组（`daily_rank_ic[]` / `grouped_returns[]`）的『可重建 / 单位忠实 / 同量同值 / 计数忠实』审计

> 被审计对象**不是论文**，而是项目自己产出的**两个数组载荷**的**逐元素数值与计数字段**。
> 判据来源：**CZ2022**（Andrew Y. Chen / Tom Zimmermann，*Open Source Cross-Sectional Asset Pricing*，FEDS Working Paper **2021-037**，DOI `10.17016/FEDS.2021.037`；期刊版 *Critical Finance Review* **11(2)** 2022，DOI `10.1561/104.00000112` ⟹ **载体 A 档**，本轮核对的是 **WP 版**，CFR 版差异**未核验**）。
> 预注册：`rounds/R014_prereg.md`（冻结提交 **`f5845a92`**，`sha256 = 283280988b1f3e8ee08a35f8fa1b1ba633fa24e7c3a8f1ca58a7c62acb1d5b6c`，**28,797 B**；本轮结束时**逐字节未改**）。
> 论文审查：`rounds/R014_review.md`。装置：`experiments/R014_rank_ic_fidelity_audit/`（`make_config.py` / `panel_R014.txt` / `probe/R014Probe.java` / `run_R014_rank_ic_fidelity_audit.py` / `result.json` / `run.log`）。

---

## 0. 一句话结论

**H0 被杀死（四子句**全部**被证伪；其中 (iv) 依赖一处**可达性读数**，见边界声明 3）**：

| 子句 | 判定 | 承重证据（全部可在冻结扫描根内复核） |
|---|---|---|
| **(i) 可重建** | **被证伪**（`K1`） | 同一多重集（`panel_hash` 相同）、**仅把同一交易日内并列行的行序取逆**，组均值即改变：G1 `0.00255 → 0.0036833333333333336`、G2 `0.00595 → 0.005383333333333334`、G3 `0.00935 → 0.008783333333333334`（`maxdiff = 0.0011333333333333334`），而 `rank_ic` **完全不变**（`maxdiff = 0.0`）；且**无任何声明**给出分组边界规则（`V05 = RULE_UNDECLARED`：载荷键集内**无** `group_boundary_rule`/`bucket`/`等分` 类字段）。分布规则实为 `FactorResearchEngine.java:100` 稳定排序 + `:108` `Math.min(g - 1, (int) ((long) i * g / n))` ⟹ **桶成员依赖未声明的输入行序**，载荷不发射逐行组属、也不发射行序 ⟹ 由载荷 + 原始截面**多重集**无法唯一重建 |
| **(ii) 单位忠实** | **被证伪**（`K2`） | 一处**同名不同量**、一处**同页同标签不同量**、一处**两面标签不一致**：① 聚合面 `grouped_returns[].observations = 3`（= `cross_section_days`，**天数**），日级面同名字段 `observations = 2`（**每组名字数**）；② 同一页面（`FactorResearch.vue`）`total_observations = 30`（Σ 名字数）与组 `observations = 3`（天数）**共用标签「观测数」**；③ 因子面标签「观测数」（`F10:151`）vs 回测面标签「截面日观测」（`F11:378`）。字段级单位声明**零**处（DTO 两 record 与两个 TS 接口均无字段级注释，`V09 = UNIT_UNDECLARED`） |
| **(iii) 同量同值** | **被证伪**（`K3`） | 同一引擎输出经**两面不同映射**后数值不等：因子面 `round4(引擎值)`（`F02:1030-1032`），DTO 面**原样**（`F03:1018-1046`）。实测 95 个值中 **65 个**被截断（`rank_ic` 19 个、`avg_return` 46 个），最大截断幅度 **`5.000000000000143e-05`**（= 4 位小数的半个刻度）；**另有 2 个**非有限值分歧：引擎 `NaN` 的组均值在因子面被写成 **`0.0`**（`Math.round(NaN) = 0`）、在 DTO 面保持 `NaN` ⟹ **把「无数据」显示成「0 收益」** |
| **(iv) 计数忠实** | **被证伪**（`K4`；**读数依赖，见边界声明 3**） | `NaN` 前向收益**不被过滤**：`P4` 日 2 共 10 行含 2 个 `NaN`，`sample_size = 10`（有限值仅 **8** 个），该日仍进入 `daily_rank_ic`（`rank_ic = 0.6139845991168652`）、`total_observations = 20`（**含** 2 个非有限观测），且 2 个组的 `avg_return` 为 `NaN`；`FactorResearchEngine` 全程**无有限性守卫**（`:140` 只按行数比较 `minCrossSectionSize`、`:158` 按 `obs.size()` 累加）。可达性 = **`REACHABLE_PUBLIC_API`**（**事前配置**；本报告 §3.4 给出反证与反事实） |

`K = {K1: true, K2: true, K3: true, K4: true, K5: false, K6: false, K7: false}`
⟹ **`total_verdict = REJECTED`**、**`h0_alive = False`**。

**边界声明（必须先读）**：
1. 被杀死的是「**项目对这两个数组载荷自身的处理假设**」，**不是**任何策略/因子的收益主张。本轮 **零**有效性结论、**零**收益结论、**零**「这个因子好不好」的结论。
2. 本轮**未**读任何行情/财务数据、**未**访问数据库、**未**重算任何成本、**未**产生任何收益序列。运行时侧的面板全部是**合成** `(score, forwardReturn)` 二元组；引擎是**生产**引擎（探针以 `package com.quant.service.backtest` 编译进生产包后直接调用公开静态 API）。结论**不得**外推为「真实数据上 IC / 分组收益的质量」。
3. **`K4` 的可达性读数是脆弱的，必须与结论一起读**：冻结文本只冻结了可达性**阶梯**（`R014_prereg.md` §8），**未逐变体冻结等级**；`config.json`（事前落盘）把 `V16` 标为 `REACHABLE_PUBLIC_API`。运行中在冻结扫描根内找到 **7 处**收益构造守卫（`F02:335` 得分 `null/≤0` 跳过、`F02:346`/`359`/`379` 快照路径价格与标签守卫、`F02:700`/`712`/`726` 旧路径同型守卫）⟹ 生产路径下 `NaN` 前向收益**当前不可达**；若按 `SYNTHETIC_ONLY` 读，**`K4 = false`**。**判定不变**：`K1`/`K2`/`K3` 三条杀点互相独立（见 4），`total_verdict` 仍为 `REJECTED`。为**不事后放宽**任何 `K`，本报告**保留** `K4 = true`（按事前配置测得），同时把该读数分歧、反证与反事实结论**完整披露**在 §3.4 与附录 `A-03`，并登记 `D-R014-10`。
4. **三条杀点的独立性**（逐条给出「剔除后仍成立」的理由）：`K1` 只由 `V03`（行序依赖）承重；`K2` 由 `V06`/`V07`/`V08` **三处**承重，其中 `V06` 与 `V08` 是**纯契约面静态事实**（一个字段名在两个面上语义不同、一个标签指向两个量），`V07` 是两面标签文本比对 —— **三处任一成立即 `K2` 成立**；`K3` 只由 `V10`/`V11`（`round4` 截断 + `NaN→0.0`）承重。**即便把 `K4` 整条剔除，`total_verdict` 仍为 `REJECTED`。**
5. **与既有轮次的重叠主动披露**（诚实归因，详见 §9.3）：本轮与 `R006`（`rank_ic_*` 命名族）、`R013`（`monotonicity` 标签）在 **`observations` 的单位问题**上**部分同源** —— `R006`/`R013` 已把该事实作为**承重证据之一**登记过。本轮**不声称首次发现**该事实，只给出**更新的量化读数**（聚合 `3` 天 vs 日级 `2` 名字数 vs 同页标签「观测数」）并把口径**收敛到两数组的逐元素层**；本轮与它们**不重叠**的部分是 `V01`–`V05`、`V10`–`V18` 中除单位问题外的全部变体（含 `round4` 双面分歧的**完整计数**与 `NaN→0.0` 的**机器标定**）。
6. 本轮**不**声称「项目应当修改分组算法 / 并列秩处理 / `round4` / DTO / 前端标签 / 单位声明」。这些全部按协议 §7 属 **record-only**，一律 `PENDING_USER`，**未**改一行生产代码（§7 给出逐项证据）。

**本轮同时如实登记 5 条勘误**（`A-01`…`A-05`，见 §6），其中 `A-01` 是**冻结锚点行号偏移**、`A-02`/`A-04`/`A-05` 是**仪器自身的伪命中/镜像偏差**、`A-03` 是**可达性读数分歧**。按冻结纪律：**不**改写任何冻结文本，**不**据此放宽任何 `K`。

---

## 1. 口径、单元与边界

### 1.1 被审计对象（`subject`）

**两个数组载荷**（**逐元素数值与计数字段**；标量族**全部排除**，属 `R006`/`R007`/`R009`/`R013`）：

| 载体 | 字段 | 元素字段 |
|---|---|---|
| `daily_rank_ic[]` | 逐日截面 IC | `trade_date` / `rank_ic` / `sample_size` |
| `grouped_returns[]` | 分组收益 | `group_no` / `label` / `avg_return` / `observations` |

**三个面**（实现 / 载荷 / 展示）：

| 面 | 文件·行 | 事实 |
|---|---|---|
| 计算（唯一） | `FactorResearchEngine.java:92-118`（`groupedAverageReturns`）、`:120-199`（`summarize`） | 稳定排序 `:100`；下标等分 `:108`；日级 `observations = rets.size()` `:114-115`（= 当日**名字数**）；聚合 `observations = rets.size()` `:179-183`（= 该组被聚合的**天数**） |
| 载荷·因子面 | `FactorResearchService.java:580-588`（`dailyIcList`）、`:592-601`（`groupedReturnList`）、`:1030-1032`（`round4`） | 数值经 `round4`；`sample_size`/`observations` **原样** |
| 载荷·DTO 面 | `ScoringBacktestService.java:1018-1046`（`toFactorAnalysisDto`） | 数值**原样**（**唯一不经 `round4` 的发射面**） |
| 展示（前端） | `FactorResearch.vue:130/131/144/149/151/159/161/236/237/247/252/254/262/264/396-411`、`ScoringBacktest.vue:275/374/378/833` | `pct4`/`fmt4` 展示 2/4 位；标签「观测数」vs「截面日观测」 |

### 1.2 H0 的边界（`R014_prereg.md` §1 **强制复述**）

> **H0**：`factor_return` 族的两数组载荷满足 **且仅满足**：(i)**可重建**（每个元素的数值与计数可由载荷自身 + 原始截面**多重集**唯一重建）∧ (ii)**单位忠实**（同名字段单位一致、且能被读者唯一确定）∧ (iii)**同量同值**（同一量在不同面上取值相同）∧ (iv)**计数忠实**（计数字段与其计的对象一一对应）。
> **杀死条件**：任一子句被**可达**证据证伪即 H0 被杀死；**默认立场 = 假设它不成立，任务是杀死它**。

### 1.3 判据来源与禁用数值

| 项 | 值 |
|---|---|
| 载体 | FEDS WP 2021-037（**A 档**：美联储理事会公开出版物；期刊版 *CFR* 11(2) 2022） |
| 本地载体哈希（**冻结用**，见 `R014_review.md` §1.4） | PDF `1,778,015 B` / `sha256 3a22b9878385aee49a411c42a8ce68feba2bb04bf00fa76d372bd63cff03b7bf`；抽取文本 `157,397 B` / `e0c1627f00daf35e684f08d1e9ced8ecda484fdb1586547c41fdfc8bab3cfeb0`；Data Dictionary PDF `201,839 B` / `ef128ef0d18b7e025168ce895036e2aa4f6d1782f989231e9a21d9a0ad2a94dc`、文本 `21,444 B` / `82399ffb103aaf08d8b7a2efe505365520bb6ce8a95b9e997e48d3bc35192a6f` |
| 判据 | **J-1…J-7 全部 L 档**（只支持**做法种类**）：J-1 逐条重建而非只报结论 / J-2 构造规格须显式（加权方式、分位、再平衡） / J-3 不满足条件的样本置 `missing` 而非赋 0 / J-4 **不得静默丢弃**、偏差必须可归因 / J-5 同一量在不同产物中须同值 / J-6 复算容差须事前声明 / J-7 口径差异须可追溯到实现层 |
| **X 档（禁用，一条未用）** | 论文全部数值与经验结论（长空 t 统计量、复算成功率、组合分位/加权方式的选择、美股样本区间、`t ≥ 1.96`/`t ≥ 3` 类临界值） |
| 禁用清单执行 | `forbidden_scan`（论文身份词 + 结论词）命中 **0**；`probe_numeric_literals` 探针字面量 = `{0,1,2,3,4,5,6}`（**无小数、无阈值**），`suspicious = []` |

### 1.4 判定单元与冻结自检

| 项 | 值 |
|---|---|
| 冻结扫描根 | **12** 文件 / **267,473 B**；逐文件 `sha256-32` 与冻结表**逐位相同**（`hash_mismatch = []`） |
| **零生产漂移** | `F01`–`F11` 的 `sha256-32` 与 **R013 冻结表逐位相同**（`matches_r013_frozen = 11/11`）⟹ **R013 → R014 之间生产代码零改动**（与 §7 的 `git` 证据互证） |
| 变体 | **20**（轴 **5**：A1 可重建 5 / A2 单位忠实 4 / A3 同量同值 5 / A4 计数忠实 4 / A5 仪器 2）；`row_sum = axis_total = variants_total = 20`，`grid_mismatch_count = 0` |
| 词表 | **16** 词（冻结）；表外词 ⟹ `K6`；实测 `off_vocab = []` |
| 金丝雀 | **20** 对 `CLN`/`INJ`（`pairs_declared = pairs_measured = 20`，`failures = []`） |
| 锚点 | **43** 条（A 15 / B 10 / C 18）：**40** 条在冻结区间内命中，**3** 条为**同文件内行号偏移**（`A-01`，**不计入**仪器门） |
| 容差（冻结） | `RECON_TOL_R4 = 5e-05`、`RECON_TOL_FP = 1e-09` |
| 单元 | 探针 **50** 个运行单元 = 6 个合成面板上的 **14** 个主单元（其中 **2** 个为日级模式：`P1_dayg`/`P5_dayg`）+ 网格 **36** 个（18 格 × `dec`/`rev`）⟹ 14 + 36 = **50** |
| 冻结自检 | `frozen_ok = True`、`prereg_unchanged = True`（`sha256` 未变）、两次静态抽取**逐字节相等**、探针两次运行**逐字节相等**、探针字节码**已清理**（`cleanup = True`） |

---

## 2. 机器化事实

> 全部数字来自 `experiments/R014_rank_ic_fidelity_audit/result.json`（`sha256-32 = e673f75202ec9c00dcdfd527a1d3513b`）与 `run.log`（append-only）。

### 2.1 静态侧（源码文本，机械抽取）

| 事实 | 读数 |
|---|---|
| `FactorResearchEngine.java:100` | `sorted.sort(Comparator.comparingDouble(...))` —— **稳定**排序，桶成员因此**依赖输入行序** |
| `FactorResearchEngine.java:108` | `int bucket = Math.min(g - 1, (int) ((long) i * g / n));` —— **下标等分**；载荷**无**该规则的任何声明 |
| `FactorResearchEngine.java:114-115` | 日级 `observations = rets.size()`（**名字数**） |
| `FactorResearchEngine.java:179-183` | 聚合 `observations = rets.size()`（**天数**）——**同一字段名，两种量** |
| `FactorResearchService.java:580-588` | `row.put("rank_ic", round4(d.rankIc())); row.put("sample_size", d.sampleSize());` |
| `FactorResearchService.java:592-601` | `row.put("avg_return", round4(g.avgReturn())); row.put("observations", g.observations());` |
| `FactorResearchService.java:1030-1032` | `round4(v) = Math.round(v * 10000.0) / 10000.0`（**无有限性守卫**） |
| `ScoringBacktestService.java:1018-1046` | `.rankIc(d.rankIc())` / `.sampleSize(d.sampleSize())` / `.avgReturn(g.avgReturn())` / `.observations(g.observations())` —— **原样** |
| DTO 字段级单位声明 | `ScoringBacktestResponse.java:98-103`（`DailyRankIc`）与 `:105-117`（`GroupReturnStat`）**均无字段级 javadoc**（其后的 `PeriodStat` **有**）⟹ `V09 = UNIT_UNDECLARED` |
| TS 类型 | `factor.ts:19-30`、`backtest.ts:61-62`：无单位说明、无 `toFixed` |
| 展示规格（两面**一致**） | 因子面 `pct4 = (v*100).toFixed(2)%`（`F10:396-411`）vs 回测面 `(row.avgReturn * 100).toFixed(2)`（`F11:374`）⟹ `V13 = VALUE_CONSISTENT`（**正面对照**） |
| 测试覆盖缺口（冻结扫描根内） | `FactorResearchServiceTest.java:305-317` 只断言 `daily_rank_ic[0].rank_ic == 1.0` 与 `grouped_returns` 非空，**未断言** `sample_size` / `observations` —— 本轮两条承重事实**在既有测试的断言面之外** |
| 读数敏感性（冻结 §12.3） | `V07`/`V13` 的判定**不随读数改变**（`reading_independent = true`）；`V05`/`V09`/`V14` 的三读数不一致 ⟹ 标 `reading_dependent = true`、**降级为描述性**（三者本就只给 `RULE_UNDECLARED`/`UNIT_UNDECLARED`，**不触发任何 `K`**） |

### 2.2 运行时侧（Java 探针**直接调用生产** `FactorResearchEngine.summarize` / `groupedAverageReturns`）

**A. 可重建（`V01`–`V05`）**

| 变体 | 结论 | 读数 |
|---|---|---|
| `V01`（无并列面板 P1） | `RECONSTRUCTABLE`（正面对照） | `rank_ic` `maxdiff = 0.0`；`avg_return` `maxdiff = 8.67e-19`（浮点噪声，远低于 `5e-5`）；`days_set_match = true` |
| `V02`（**含并列但恰在桶内** P2） | `RECONSTRUCTABLE`（正面对照） | 声明序 vs 逆序的组均值**逐位相同** ⟹ **并列本身不是问题**，「未声明的行序 + 并列跨桶边界」才是 |
| **`V03`（并列跨桶 P3）** | **`NOT_RECONSTRUCTABLE`** | 见下表 |
| `V04`（`round4` 截断量） | `LOSSY_PRECISION`（描述性） | 95 个值中 **65** 个被截断；最大幅度 `5.000000000000143e-05` |
| `V05`（分组边界规则声明） | `RULE_UNDECLARED`（描述性） | 载荷键集内**零**命中；`v05_adjacent_generic_text_hits = 3`（相邻文案仅出现「等数量」通用词，**不构成**规则声明） |

**`V03` 逐组读数（`P3`，同一多重集、`panel_hash` 相同）**：

| 组 | 声明序 | **逆序** | 差异 |
|---|---|---|---|
| G1 | `0.00255` | `0.0036833333333333336` | **+0.0011333** |
| G2 | `0.00595` | `0.005383333333333334` | **−0.0005667** |
| G3 | `0.00935` | `0.008783333333333334` | **−0.0005667** |
| G4 | `0.012750000000000003` | `0.012750000000000003` | 0（桶内全并列，不可分辨） |
| G5 | `0.01615` | `0.01615` | 0（同上） |
| `rank_ic`（三日，逐日） | `0.9878044218151566` | `0.9878044218151566` | **`maxdiff = 0.0`**（IC 对行序不敏感） |

- **置换可撤销**（正面对照）：`reversed` 再取逆（`P3_dbl`）与声明序**逐位相同**（`maxdiff = 0.0`）⟹ 差异**确由行序**造成，不是随机性；
- **诚实披露**：我的 Python「canonical 重算序」（按 `(score, fwd, 出现序号)` 稳定排序）与声明序 `maxdiff = 4.34e-19` —— 这**不算**独立证据：该重算序把**声明序本身**当作并列内的次序使用，属夹具自洽。承重证据是**置换**（`V03`）与**规则无声明**（`V05`），**不是**这条 canonical 臂。

**B. 单位忠实（`V06`–`V09`）**

| 变体 | 结论 | 读数 |
|---|---|---|
| **`V06`（聚合 vs 日级 `observations`）** | **`INCONSISTENT_UNIT`** | 聚合（`P1_dec`）：G1–G5 全 = **3** = `cross_section_days`；日级（`P1_dayg`）：G1–G5 全 = **2**（每组名字数）⟹ 同名不同量，`v06_unit_conflict = true` |
| **`V07`（两面展示标签）** | **`INCONSISTENT_UNIT`** | `FactorResearch.vue:151` `label="观测数"` vs `ScoringBacktest.vue:378` `label="截面日观测"`（`v07_labels_equal = false`） |
| **`V08`（同一页同标签两量）** | **`INCONSISTENT_UNIT`** | `F10:131`「观测数」→ `total_observations = 30`（Σ 名字数）；`F10:151`「观测数」→ 组 `observations = 3`（天数）⟹ `v08_same_label = true ∧ v08_quantities_differ = true` |
| `V09`（字段级单位声明） | `UNIT_UNDECLARED`（描述性） | DTO 无字段级注释（0）、TS 无单位（0）；`v09_ui_unit_hint_count = 1`（`F11:275`「个截面日 / …个观测」是**面向用户的自然语言**，**不构成**字段级声明） |

**C. 同量同值（`V10`–`V14`）**

| 变体 | 结论 | 读数 |
|---|---|---|
| **`V10`（两面 `rankIc`）** | **`VALUE_DIVERGENT`** | **19** 个逐日值被 `round4` 改变；例：`0.9878787878787879 → 0.9879`、`0.9878044218151566 → 0.9878` |
| **`V11`（两面 `avgReturn`）** | **`VALUE_DIVERGENT`** | **46** 个组值被 `round4` 改变；例：`0.00255 → 0.0026`、`0.00595 → 0.006`、`0.004533333333333334 → 0.0045`；**另加 2 个 `NaN → 0.0`**（见 §3.3） |
| `V12`（两面 `sampleSize`/`observations`） | `VALUE_CONSISTENT`（正面对照） | 两面**原样**传递（静态 `row.put("sample_size", d.sampleSize())` 等三处 + 运行层按面板逐日核对 `v12_runtime_counts_match = true`） |
| `V13`（两面显示精度） | `VALUE_CONSISTENT`（正面对照） | `pct4` 与 `.toFixed(2)` **同为 2 位小数百分号** |
| `V14`（取整规则声明） | `RULE_UNDECLARED`（描述性） | 载荷键集内**零**命中 |

**D. 计数忠实（`V15`–`V18`）**

| 变体 | 结论 | 读数 |
|---|---|---|
| `V15`（`Σ sample_size` vs `total_observations`） | `COUNT_FAITHFUL`（正面对照） | 全部单元 `maxdiff = 0` |
| **`V16`（非有限值进入当日截面）** | **`COUNT_UNFAITHFUL`** | `P4` 日 2：**10** 行含 **2** 个 `NaN` 前向收益（有限值 **8**）⟹ `sample_size = 10`（**≠ 8**）、该日仍有 `rank_ic = 0.6139845991168652`、`total_observations = 20`（含 2 个非有限）、**2** 个组的 `avg_return = NaN` |
| `V17`（`minCrossSectionSize` 丢日） | `DROPPED_SILENTLY`（描述性，不计 `K`） | `P6` 面板 **6** 天（规模 12,12,4,12,4,12）⟹ 载荷 `cross_section_days = 4`（**静默丢 2 天**；`min=8` 时仍为 4）；两发射方法（`F02:526-554` 与 `F02:605-628`）的键集**合计 27 个**中**零**个披露该丢弃；宽候选键仅 `partitions_skipped`（`F02:445` 与 `F02:612` 两处发射），其取值 `skippedDates` 可**溯源**到 `F02:279` 的 `isReusableManifest(...)`（**清单可复用性**，与最小截面过滤无关）⟹ 判为**语义无关**并**同时输出**（`v17_unrelated_drop_ish_keys`，含行号）以便复核 |
| `V18`（零观测组行） | `PLACEHOLDER_MARKED`（正面对照） | `P5`（每日 2 行）+ `groups = 5` ⟹ **3** 个组行 `{avg_return: 0.0, observations: 0}`（G3/G4/G5）；机制见 `F01:114`/`:181` 的 `rets.isEmpty() ? 0 : …average().orElse(0)`（**空桶直接给均值 `0.0`**）与 `rets.size()`（**给 `0`**）；日级最大组号为 **2** ⟹ 这三组在日级**从不出现**，占位语义**可区分**；可达性 = `SYNTHETIC_ONLY`（真实路径经 `min=10` 过滤后每日 ≥10 行 ⟹ `g = min(5, n) = 5`，**不产生**零观测组行） |

**E. 仪器（`V19`–`V20`）**

| 变体 | 结论 | 读数 |
|---|---|---|
| `V19`（金丝雀总门） | `COUNT_FAITHFUL` | `pairs_declared = pairs_measured = 20`、`failures = []` |
| `V20`（正面对照 + 反向锚点） | `VALUE_CONSISTENT` | ① 两次运行**逐字节相等**（`bytes_equal = true`）；② **置换可撤销**（`v03_doubled_identity_maxdiff = 0.0`）；③ **移除 `round4` 后杀点消失**：反事实映射下分歧数 = **0**（覆盖 95 个有限值 + 2 个非有限值）；④ `round4` 镜像对**真实 JVM 标定表**逐位一致（`v04_java_round_mirror_ok = true`） |

### 2.3 ±20% 网格（冻结值 `factorGroupCount = 5`、`minCrossSectionSize = 10`；±20% 取 `{4,5,6}` × `{8,10,12}`）

| 网格 | 结果 |
|---|---|
| 单元数 | **18**（3 × 3 × 2 面板：`P1` 无并列 / `P3` 并列跨桶） |
| **行序敏感单元** | **6**（`P3` × `{4,5,6}` × `{8,10}`）⟹ **`order_avg_maxdiff = 0.0011333333333333334` 在组数 ±20% 下**逐格复现、**不随 `factorGroupCount` 变化** |
| `P1` 全部单元 | `maxdiff = 0`（无并列 ⟹ 行序不影响桶成员）⟹ **杀点对参数移动稳健**，且**只在有并列跨桶边界时触发** |
| `minCrossSectionSize = 12` 的 6 个单元 | `cross_section_days = 0`、`Σ sample_size = 0` ⟹ 面板被**整体静默丢弃**（与 `V17` 同型：丢日无披露、且常量本身不可从载荷读出） |
| 网格敏感性对判定的影响 | **无**：`grid_mismatch_count = 0`；`V03` 的判定在 18/18 单元中与「是否有并列跨桶」一致 |

---

## 3. H0 四子句逐条判定

### 3.1 (i) 可重建 —— **被证伪**（`K1`）

1. 载荷只给**结果**（每组的 `avg_return` / `observations`），**不给**逐行的组属，也**不给**分组规则（`V05`）。读者若只有「载荷 + 原始截面多重集」，就必须自己决定**并列行在排序中的相对次序**——而库里**没有**任何地方声明这个次序（`F01:100` 只是**稳定**排序，稳定性只有在**已给定输入行序**时才有确定含义）。
2. 该「未声明自由度」**确实改变输出**：`V03` 的 `maxdiff = 0.0011333333333333334`，而 `rank_ic` 不变（`0.0`）⟹ 差异**只**来自分桶，不是统计量本身。
3. 该自由度**不可由载荷消解**：`reversed` 与 `declared` 的 `panel_hash` **相同**（同一多重集）⟹ 载荷层面**无法区分**这两种输入。
4. ⟹ **`V03 = NOT_RECONSTRUCTABLE`，且 `reading_independent = true`** ⟹ **`K1` 触发**。

**边界**：`V03` 只证明「**未声明的输入行序依赖**存在」；真实数据里行序来自 DB（`ORDER BY signal_date ASC` 后按分区读取），其**影响面未测量** ⟹ `B-R014-02`。本报告**不**据此声称「真实数据上分组收益会随机跳变」。

### 3.2 (ii) 单位忠实 —— **被证伪**（`K2`）

`V06`、`V07`、`V08` **三条独立**成立，且三者都是**契约面事实**（不依赖合成数据的具体数值）：

- `V06`：`observations` 在**聚合面**指**天数**（= `cross_section_days`）、在**日级面**指**名字数**（每组样本个数）。同一字段名，两种量，**无字段级声明**。
- `V08`：在**同一张表**（`FactorResearch.vue`）里，标签「观测数」同时指向 `total_observations`（Σ 名字数 = 30）与组 `observations`（天数 = 3）。
- `V07`：两个面的展示标签**不同**（「观测数」vs「截面日观测」）—— 一个用户从两面看到的「观测」不是同一个东西。

⟹ **`K2` 触发**（`reading_independent` 三者均为 `true`）。

### 3.3 (iii) 同量同值 —— **被证伪**（`K3`）

同一份 `FactorResearchSummary` 经**两套映射**写入两份载荷：

| 项 | 因子面（`F02`） | DTO 面（`F03`） | 结果 |
|---|---|---|---|
| `rank_ic` | `round4(v)` | `v` | **19** 个逐日值不等 |
| `avg_return` | `round4(v)` | `v` | **46** 个组值不等 |
| `avg_return = NaN` | `round4(NaN) = 0.0` | `NaN` | **2** 个组值：`P4` 日 2 的 G2/G4 —— 因子面显示 **`0.0`**、DTO 面显示 `NaN` |
| `sample_size` / `observations` | `v`（int） | `v`（int） | 一致（`V12` 正面对照） |

- `round4` 的三处语义已用**真实 JVM 标定**（`_scratch/RoundSemanticsProbe.java` → `_scratch/round_semantics.txt`）：`0.00255 → 0.0026`、`0.03125 → 0.0313`（半值向上）、`-0.00255 → -0.0026`、**`NaN → 0.0`**、`±∞ → ±9.223372036854776E14`（`Math.round` 的 `long` 饱和）。
- ⟹ **`K3` 触发**。最严重的一条不是「第 5 位小数被抹掉」，而是 **`NaN → 0.0`：把「无数据」显示成「0 收益」**，且这在两面**只有一面发生**。
- **反向锚点**（`V20`③）：把 `round4` 从映射中移除后，两面分歧数 = **0** ⟹ 杀点**可归因到该映射本身**，不是测量假象。

### 3.4 (iv) 计数忠实 —— **被证伪**（`K4`）；**附可达性读数分歧**

**事实**：`FactorResearchEngine` 对 `forwardReturn` **无有限性守卫**（`:140` 只比较行数、`:146-147` 直接取值、`:149` 只对**算出来的 IC** 做 `isFinite` 早退、`:158` 按 `obs.size()` 累加）。`V16` 的合成面板上：`sample_size`、`total_observations` 把 **`NaN` 观测计入**，且 `NaN` 组均值被写入载荷（并因 `round4` 变成 `0.0`）。⟹ **`V16 = COUNT_UNFAITHFUL`**。

**可达性读数分歧（必须与结论一起读）**：

| 读数 | 依据 | `V16` 等级 | `K4` | `total_verdict` |
|---|---|---|---|---|
| **事前配置（本报告的判定口径）** | `config.json`（冻结提交内落盘，先于运行） | `REACHABLE_PUBLIC_API` | **true** | `REJECTED` |
| **守卫算术读法（运行中发现的证据）** | 冻结扫描根内**七处**守卫：`F02:335`（得分 `null/≤0` 跳过）、`F02:346`/`359`/`379`（快照路径 RAW/COST_APPROX/EXECUTABLE 的价格/标签守卫）、`F02:700`/`712`/`726`（旧路径同型） | `SYNTHETIC_ONLY` | **false** | **`REJECTED`（不变）** |

- 冻结文本只冻结了可达性**阶梯**，**未**逐变体冻结等级 ⟹ 事前配置是唯一的等级来源；**为不事后放宽任何 `K`，本报告保留 `K4 = true`**。
- 同时**如实登记**：该等级**在运行中被反证**（生产路径下 `NaN` 前向收益不可达）；`K4` 是**潜在**（latent）失效——若未来引入新的收益构造路径（`D-R014-*` / `D-R013-*` 同型），它会立即变成真实失效。
- **反事实**：`K4 = false` 时 `total_verdict` **仍为 `REJECTED`**（`K1`/`K2`/`K3` 独立）⟹ **判定对这条读数分歧稳健**。

### 3.5 短路路径（逐条）

冻结短路：`if K5 or K6 or K7 → INCONCLUSIVE; elif K1 or K2 or K3 or K4 → REJECTED; else NOT_VIOLATED`。

| 门 | 实测 | 说明 |
|---|---|---|
| `K5` 计数门 | **false** | `row_sum = axis_total = variants_total = 20`、`grid_mismatch_count = 0`、`CANARY_PAIRS = 20 = pairs_measured` |
| `K6` 仪器门 | **false** | `off_vocab = []`；`CLN`/`INJ` **20/20** 通过；探针两次运行逐字节相等且**已运行**；禁数扫描 `suspicious = []`；**`instrument_alerts = []`**（含 `round4` 镜像自检在内的全部仪器自检通过） |
| `K7` 冻结门 | **false** | `prereg_sha256` 未变；12 个冻结文件 `sha256-32` 全部未变；两次静态抽取**逐字节相等** |
| ⟹ | **`REJECTED`** | `h0_alive = False` |

**仪器门与实质门的分离**（冻结 `implementation_notes`）：`A5` 轴（`V19`/`V20`）是**仪器自检**，其失败路径是 `instrument_alerts ⟹ K6`（短路优先级最高）；`A5` 不计入 `K1`–`K4` 候选集，且 `reachability = SYNTHETIC_ONLY`（按字面读也不会触发 `K4`）—— 避免把**仪器故障**误报成**H0 被证伪**。

---

## 4. 协议与冻结纪律要求的必答项

| 项 | 必答 | 本轮值 |
|---|---|---|
| **变体数 N**（协议 §5 第 7 步） | 披露 | **20**（5 轴：5/4/5/4/2）；`row_sum = axis_total = 20`；`grid_mismatch_count = 0` |
| **关键参数 ±20% 敏感性** | 必做（不可跳过） | `factorGroupCount ∈ {4,5,6}`（5 ±20%）× `minCrossSectionSize ∈ {8,10,12}`（10 ±20%）× 2 面板 = **18** 单元。结果：**6/18 行序敏感**（全部为 `P3` 并列跨桶 × `min ∈ {8,10}`），**`maxdiff` 在组数移动下逐格复现**（0.0011333333333333334），**判定不随参数移动而改变**；`min = 12` 的 6 个单元**面板被整体静默丢弃**（`cross_section_days = 0`） |
| **前视 / 幸存者偏差**（`研究口径规范.md` §5） | 披露 | **`0` / `0`** —— 装置只读源码文本 + 合成 `(score, forwardReturn)` 面板；**不产生收益序列、不读行情、不访问数据库**。本轮**无**样本区间、**无**收益构造，故不存在前视或幸存者偏差通道 |
| **读数敏感性（三读数）** | 逐变体给出 | `V05`/`V09`/`V14`：三读数**不一致** ⟹ `reading_dependent = true` ⟹ 按冻结 §12.3 **降级为描述性**（三者本就**不触发任何 `K`**）；`V07`/`V13`：三读数**一致** ⟹ `reading_independent = true`（`V07` 是 `K2` 的承重之一）。**未**为凑「四杀」而放宽或收紧任何读数 |
| **否定式声明用标识符级**（冻结纪律第 4 条） | 执行 | `V05`/`V17` 的**否定结论**（「载荷**没有**分组规则声明」「丢日**没有**披露」）采用**正向搜索**（在**限定作用面**内搜「本应出现的**标识符**」），**不**用被否定对象的子串去测；`V17` 的作用面与语义无关排除规则**事前写死**并**同时输出**排除清单（`v17_unrelated_drop_ish_keys`，含溯源行号） |
| **判定事实来源边界**（冻结纪律第 5 条） | 执行 | 全部判定事实来自**冻结扫描根 12 文件**（`sha256` 已核验）与**生产引擎**本身；根外文件（论文载体）只作判据来源，**不**作判定事实 |
| **探针确定性**（冻结纪律第 6 条） | 执行 | 探针 `javac -cp modules/backend-java/target/classes -encoding UTF-8`；**运行两次逐字节相等**（`bytes_equal = true`）；运行结束**清理字节码**（`cleanup = True`，`target/classes` 内无残留 `R014Probe*.class`）；**终态** `result.json`（**73,374 B**、`sha256-32 = e673f75202ec9c00dcdfd527a1d3513b`）与 `_scratch/result_full_run1.json` **逐字节相同**（**最后两次连续 full 运行**）；**修复前**的 full 快照 `_scratch/result_full_run2.json`（**72,595 B**、`sha256-32 = c3b86168b325907d08976bbaf9345384`）与终态**不同**，逐键比对后差异**恰为** `A-05` 修复引入的 **9 个键位**（**非**运行间不确定性；见附录 **`A-06`**） |
| **禁数扫描**（`K6` 条款） | 执行 | 探针数值字面量 = `{0,1,2,3,4,5,6}`，`suspicious = []`；另有**自加**的「仪器自污染扫描」（论文身份词 + 结论词）命中 **0**（`A-02`/`A-04` 记录它的两次仪器勘误） |
| **`run.log` append-only** | 执行 | **620** 行（`wc -l`），**全部 14 次运行**逐次追加、**无**覆盖 = **3** 次 `static-only`（2 次 `INCONCLUSIVE`〔`K6 = true` 仪器告警〕＋ 1 次 `REJECTED` 读数）＋ **11** 次 `full`（前 2 次 `INCONCLUSIVE`〔`K6 = true`〕＋ **9** 次 `REJECTED` 且 `K6 = false`）；**未删改任何一次**，**0 次为假设被证伪**（杀的是项目自己的契约主张）；见附录 **`A-06`**（首版此处误写「488 行 / 三轮 full 复核」，已订正） |
| **零生产代码改动** | 必须 | 见 §7 |

---

## 5. `P` 表核对（`R014_prereg.md` §9；**20 条，命中 20/20**）

| `P` | 事前预测（读码/实测来源） | 实测 | 命中 |
|---|---|---|---|
| `P-01` | `V01` = `RECONSTRUCTABLE` | `RECONSTRUCTABLE` | ✅ |
| `P-02` | `V02` = `RECONSTRUCTABLE`（并列**桶内**） | `RECONSTRUCTABLE` | ✅ |
| `P-03` | `V03` = `NOT_RECONSTRUCTABLE` | `NOT_RECONSTRUCTABLE` | ✅ |
| `P-04` | `V03` 的 `rank_ic` 对行序**不变** | `maxdiff = 0.0` | ✅ |
| `P-05` | `V04` = `LOSSY_PRECISION` | `LOSSY_PRECISION`（65/95） | ✅ |
| `P-06` | `V05` = `RULE_UNDECLARED` | `RULE_UNDECLARED` | ✅ |
| `P-07` | `V06` 聚合 `observations` = **天数** | `3 = cross_section_days` | ✅ |
| `P-08` | `V07` = `INCONSISTENT_UNIT` | `INCONSISTENT_UNIT` | ✅ |
| `P-09` | `V08` = `INCONSISTENT_UNIT` | `INCONSISTENT_UNIT` | ✅ |
| `P-10` | `V09` = `UNIT_UNDECLARED` | `UNIT_UNDECLARED` | ✅ |
| `P-11` | `V10` = `VALUE_DIVERGENT` | `VALUE_DIVERGENT`（19 日） | ✅ |
| `P-12` | `V11` = `VALUE_DIVERGENT` | `VALUE_DIVERGENT`（46 组 + 2 `NaN→0.0`） | ✅ |
| `P-13` | `V12` = `VALUE_CONSISTENT`（正面对照） | `VALUE_CONSISTENT` | ✅ |
| `P-14` | `V13` = `VALUE_CONSISTENT`（两面 2 位小数） | `VALUE_CONSISTENT` | ✅ |
| `P-15` | `V14` = `RULE_UNDECLARED` | `RULE_UNDECLARED` | ✅ |
| `P-16` | `V15` = `COUNT_FAITHFUL`（正面对照） | `COUNT_FAITHFUL`（`maxdiff = 0`） | ✅ |
| `P-17` | `V16` = `COUNT_UNFAITHFUL` | `COUNT_UNFAITHFUL` | ✅ |
| `P-18` | `V17` = `DROPPED_SILENTLY` | `DROPPED_SILENTLY`（修正仪器后命中；见 `A-04`） | ✅ |
| `P-19` | `V18` = `PLACEHOLDER_MARKED`（正面对照） | `PLACEHOLDER_MARKED` | ✅ |
| `P-20` | `V20` = `VALUE_CONSISTENT`（反向锚点全过） | `VALUE_CONSISTENT` | ✅ |

---

## 6. 附录：偏离与勘误（`A-01`…`A-06`）

> 冻结纪律：**不**改写任何冻结文本（`R014_prereg.md` 与 `R014_review.md` 逐字节未动），**不**事后放宽任何 `K`。

### `A-01` 冻结锚点行号偏移 **3 处**（只登记）

| 锚点 | 冻结区间 | 实测 | 性质 |
|---|---|---|---|
| `B-07` | `F02:826-838` | `buildNetIcBlock` 调用点 `F02:790`、**声明**在 `F02:820` | 偏移 −36（`find_line` 先命中调用点） |
| `B-08` | `F04:80-92` | `F04:79`（`public static class FactorAnalysis {`） | 偏移 −1 |
| `B-09` | `F01:33 / F01:39` | `F01:18`（`public record GroupReturnStat(`） | 偏移 −15 |

**读数规则（运行前写死，运行中据此裁决）**：冻结区间命中 ⟹ `hit`；**未**命中但**同一文件内**可定位 ⟹ `offset`（⟹ 本条勘误，**不计入**仪器门）；同一文件内**完全找不到** ⟹ `missing` ⟹ `instrument_alerts ⟹ K6`。
理由：`K6` 的 `instrument_alerts` 语义是**仪器完整性**（证据是否真实存在），**不是**冻结文本的行号精度；把行号偏移当仪器故障会**掩盖实质结论**（首版即因此误报 `K6`）。实测最终 `anchors = 40/43`（40 hit + 3 offset + 0 missing + 0 drift）。

### `A-02` 仪器自污染扫描的**两类伪命中**（修正后 0 命中）

首版命中 **22** 条，全部为伪命中：
1. **自指**（11 条）：模式**定义块自身**的行（`run_R014_rank_ic_fidelity_audit.py:884-885`）被计入命中；
2. **子串**（11 条）：ASCII 身份词 `chen` 命中 `FactorResear**chen**gine`（`make_config.py:31/43`、`R014Probe.java` 的 1–5/18/81/90/130 行）。

修正：① 用 `# BEGIN/END_FORBIDDEN_PATTERNS`**哨兵**界定并排除定义块（`self_excluded_lines` 落盘可复核）；② ASCII 身份词改用 `\b` **词界**匹配。修正后命中 **0**。**修正方向 = 去伪命中**（不放松任何实质检查：该扫描是**自加**的仪器，冻结条款的「禁数扫描」由 `probe_numeric_literals` 独立承担，两者互不替代）。

### `A-03` `V16` **可达性等级**在运行中被反证（**读数分歧**，见 §3.4）

冻结文本只冻结**阶梯**、未逐变体冻结等级；事前配置给 `V16 = REACHABLE_PUBLIC_API`（`K4` 因此为 `true`）。运行中在冻结扫描根内找到 **7 处**守卫（`F02:335`/`346`/`359`/`379`/`700`/`712`/`726`）⟹ 生产路径下 `NaN` 前向收益**不可达**，按守卫算术读法 `V16 = SYNTHETIC_ONLY` ⟹ `K4 = false`。
**处置**：① **保留** `K4 = true`（按事前配置测得），**不**改冻结文本、**不**改任何 `K`；② 本报告显式披露反证与反事实；③ 反事实显示 `total_verdict` **不变**；④ 登记 `D-R014-10`（下轮同类装置应把逐变体 `reachability` 纳入**冻结表**）。

### `A-04` `V17` 披露字段搜索的**作用面越界**（修正后 `P-18` 命中）

首版在**整份** `F02` 上按 `skip|drop|filter|exclud|min_cross` 搜键名 ⟹ 命中 `partitions_skipped`（`F02:445`/`612`）⟹ 误判 `DROPPED_DISCLOSED` ⟹ **`P-18` 预测不中**。复核发现该键取值 `skippedDates` 溯源到 `F02:277-284` 的 `isReusableManifest(...)`（**清单可复用性**，与 `minCrossSectionSize` **语义无关**）。
修正：① 作用面**限定**为「携带两数组的载荷块 ∪ funnel 块」（`v17_scope_note` 输出实际方法区间）；② 可溯源到 `isReusableManifest` 的键判为**语义无关**并**同时输出**（不静默过滤）；③ **保守读数**：宽候选但既不可溯源、也不符精确词者**仍计入披露面**（防「静默丢候选」）。修正后 `drop_disclosure_keys = []` ⟹ `DROPPED_SILENTLY` ⟹ `P-18` 命中。**修正方向 = 收紧**。

### `A-05` `round4` 镜像**非有限值偏差**（修正后**加强** `K3`）

首版镜像 `floor(v*10000 + 0.5)/10000` 对 `NaN` 直接返回 `NaN`（`_finite` 早退），**漏掉**生产实现 `Math.round(v * 10000.0) / 10000.0` 的两个行为：`NaN → 0.0`、`±∞ → ±9.223372036854776E14`（`Math.round` 的 `long` 饱和）。修正：改用 `java_round` 忠实镜像，并加 **JVM 标定表自检**（14 个锚点，`v04_java_round_mirror_ok = true`；标定产物 `_scratch/round_semantics.txt`，标定程序 `_scratch/RoundSemanticsProbe.java`，字节码已清理）。
**效果**：新增 **2** 个 `NaN → 0.0` 分歧实例（`P4` 日 2 的 G2/G4）⟹ **加强** `K3`（**不是**削弱），并暴露「把无数据当 0 收益」这一更严重的双面不一致。

### `A-06` 首版对 `run.log` 行数与「三轮逐字节相等」的**表述错误**（**勘误，收口后订正 + 本节登记**）

- **错误表述**（首版 §4 与 §8 相关行）：①`run.log` 写「**488 行**」；②`result.json` 写「在**三次**独立 full 运行间**逐字节相同**」。
- **实测事实（机器化核验）**：
  - `run.log` = **620** 行（`wc -l`；54,085 B），内含 **14** 次运行记录 = **3** 次 `static-only` + **11** 次 `full`。
    其中 **4** 次为 `INCONCLUSIVE` 且 `K6 = true`（run 1/2 为 `static-only`、run 3/4 为 `full`，均属**修复过程**中的仪器告警），
    run 9 为 `static-only` 读数 `REJECTED`，其余 **9** 次 `full` 为 `REJECTED` 且 `K6 = false`。
  - `result.json`（**73,374 B**、`sha256-32 = e673f75202ec9c00dcdfd527a1d3513b`）与 `_scratch/result_full_run1.json` **逐字节相同**
    ⟹ **只有最后两次连续 full 运行**被证明逐字节相等；**不是三次**。
  - `_scratch/result_full_run2.json`（**72,595 B**、`sha256-32 = c3b86168b325907d08976bbaf9345384`，写入时间早 4 分钟）
    与终态**不同**：逐键比对得 **9 个差异位**，全部是 `A-05` 修复**新增/扩展**的键
    （`v04_java_round_mirror_ok`、`v04_nan_to_zero_count`、`v04_nan_to_zero_examples`、`v04_nonfinite_values_total`、
    `v10_nan_to_zero_day_count`、`v11_nan_to_zero_group_count`、`v20_round4_removal_all_divergence_count` 七个 `None → 值`，
    加上 `v20_round4_removal_scope` 文本扩展与 `facts_detail/v04_nan_to_zero_examples[]` 新增数组）
    ⟹ 该差异是**修复的机器化证据**，**不是**运行间不确定性，也**不**影响任何 `K`。
- **成因**：首版把「探针**输出**两次逐字节相等」（该条**为真**，`bytes_equal = true`）与
  「`result.json` 多次运行逐字节相等」混为一谈；行数是**手写**而未用 `wc -l` 复核。
- **处理**：**就地订正**上述两处并指向本节；`result.json` / `run.log` / `_scratch/**` **一字未改**
  （三个快照全部入库，**含修复前那一个**，以便第三方独立复核上述逐键比对）；
  **冻结的 `R014_prereg.md` 与本报告的 §0–§3 判定、§5 `P` 表 20/20 均不受影响**。
- **纪律自评**：本条属「**报告层数字必须来自工具实测**」的反面教材；
  已在 `progress.md` §7 的 R014 自检中登记（**未**因为它对我方不利而删除 `_scratch/result_full_run2.json` ——
  恰恰相反，该文件正是本条勘误的**唯一证据**）。

### 与本轮无关但一并订正的一条（**R013 侧**）

`R013_report.md` 的 §0 抬头与 §8 台账行把 **CCW2024** 的载体写成「**A 档**」，与 `paper_ledger.csv` 中同一行的 **C 档**（arXiv 预印本，无期刊版、无 DOI）矛盾。已在该报告附录追加 **`A-05`（勘误）** 并就地订正两处；**冻结的** `R013_prereg.md:7` **未改写**（按冻结纪律以勘误披露）。

---

## 7. 零生产代码改动：逐项证据

| 证据 | 读数 |
|---|---|
| `git status --porcelain`（报告定稿时的**实测**终态） | `M` 5 份台账（`blocked_queue.md` / `decision_queue.md` / `paper_ledger.csv` / `progress.md` / `shadow_registry.md`）＋ `M rounds/R013_report.md`（**仅**跨轮勘误 `A-05`：档位笔误「A 档」→「C 档」，见下）＋ `?? rounds/R014_report.md` ＋ `?? experiments/R014_rank_ic_fidelity_audit/` ⟹ **全部位于 `research/paper_loop/**` 之内** |
| `git diff --name-only f5845a92..HEAD` | **空**（即冻结提交之后**未**产生任何生产代码提交） |
| 12 个冻结扫描文件 `sha256-32` | 与冻结表**逐位相同**（`hash_mismatch = []`） |
| `F01`–`F11` 与 **R013 冻结表** | **11/11 逐位相同** ⟹ R013 → R014 之间**零生产漂移** |
| 本轮改动范围 | 仅 `research/paper_loop/**`（`rounds/R014_*.md`、`rounds/R013_report.md` 附录勘误、`experiments/R014_*/**`、五份台账）⟹ 符合冻结纪律「零生产代码改动，只允许 `research/paper_loop/**`」 |
| 探针字节码 | 运行结束已清理（`target/classes` 内无 `R014Probe*.class`） |

> **跨轮勘误披露（`rounds/R013_report.md`，本轮一并落地）**：R013 报告第 **4** 行与第 **368** 行把 CCW2024 的
> 载体档位误写为「**A 档**」，实为 **C 档**（arXiv v3、无同行评审、无期刊版、无 DOI；
> 同轮 `paper_ledger.csv` 与 `R013_prereg.md` 原本就是 **C 档**）。本轮**就地订正两处**并在其附录新增
> `A-05` 登记；**冻结的 `R013_prereg.md` 一字未改**，`paper_ledger.csv` 与 `progress.md` 的 R013 行
> 原本就是 C 档故未动。**档位不参与任何 `K`** ⟹ R013 的任何判定不受影响；本条**不改变** R014 的任何判定。

---

## 8. 台账联动（五份，逐份说明「改了什么 / 为什么不改」）

| 台账 | 动作 | 内容 |
|---|---|---|
| `paper_ledger.csv` | **追加 1 行** | `CZ2022`：A 档载体（FEDS WP 2021-037 + 期刊版 *CFR* 11(2) 2022）、判据 J-1…J-7 全 **L 档**、**X 档禁用清单**（论文全部数值与经验结论一条未用）、`forbidden_scan` 命中 0、判定 `REJECTED`、失败原因分类「口径结构（**被杀死的是项目自己的 H0**：两数组载荷的可重建/单位忠实/同量同值/计数忠实）」 |
| `shadow_registry.md` | **追加 R014 段（本轮 0 影子因子）＋ 补登 R012/R013 两段历史漏登（各 0 影子）** | R014 是**审计轮**：不产生任何策略、不做任何收益主张、不新增/修改任何因子 ⟹ **0 新增、0 提升**，**无**提升请求（与 R002/R007–R013 的空轮处置一致；协议：影子→正式提升**必须**由用户确认）。**附带发现并如实登记**：该表此前**只到 R011**，**R012/R013 两轮段整体缺失**（历史漏登）⟹ 本轮按 R005/R010 的「只补索引、不改正文」先例**补登两段**，并在表内**显式披露**该勘误；**累计影子因子仍为 0/0，未因补登改动任何计数** |
| `blocked_queue.md` | **追加 2 条** `B-R014-01` / `B-R014-02` | ① **`B-R014-01`（工具限制）**：arXiv API 与 Semantic Scholar 经代理 **429 限流**，本轮检索实际只用 **OpenAlex** 单通道 ⟹ 文献覆盖不完整（同型先例 `B-R009-01`）；② **`B-R014-02`（能力边界）**：**载荷端到端形状**（HTTP/Spring 容器实际组装后的键集）与**真实 DB 行序**对分桶的影响面**均未测量** ⟹ 本轮只能证明「未声明依赖**存在**」，不能给出真实数据上的**发生频率**（同型先例 `B-R010-01`/`B-R011-01`/`B-R012-01`/`B-R013-01`） |
| `decision_queue.md` | **追加 10 条** `D-R014-01`…`D-R014-10` | 全部属协议 **§7 record-only tier**：披露分组边界规则 / 声明输入行序语义 / 改 `observations` 命名或加字段级说明 / 统一两面标签 / `NaN` 有限性守卫 / 计数排除非有限观测 / 披露被判丢日 / 统一两面取整策略 / 零观测组占位标记 / 逐变体 `reachability` 入冻结表。**只登记、不实施**；其中 `D-R014-05`（`NaN→0.0`）与 `D-R014-02`（行序）显式标注与 `D-R006-*`/`D-R013-*` 属**同一代码事实的不同读法**（防重复研究 + 诚实归因） |
| `progress.md` | **更新** §1 当前位置、§2 计数、§3 归档表补记、§4 新增第 66–70 条、§6 收口记与 R015 候选、§7 自检；§5 提交记录在**回填提交**中更新（避免 commit id 自指） | R014 收口；**下一轮 R015 = 协议 §14 的 5 轮检查点**（**必须**生成独立阶段总结文件 `stage_summary_R011_R015.md`） |
| `rounds/R013_report.md` | **跨轮勘误**：两处「A 档」→「C 档」＋ 附录新增 `A-05` | CCW2024 载体档位笔误（实为 C 档）；**冻结的 `R013_prereg.md` 一字未改**、`paper_ledger.csv` 原本即为 C 档故未动；**档位不参与任何 `K`** ⟹ R013 判定不受影响（详见 §7 之下的披露块） |

---

## 9. 负面清单复核与本轮**不做**的事

### 9.1 `R014_review.md` §7 八条（逐条）

| # | 条款 | 本轮执行 |
|---|---|---|
| 1 | 不得用「论文说有效」替代实测 | J-1…J-7 只以「做法种类」使用；**X 档一条未用**；判定全部来自实测/静态抽取 |
| 2 | 不得删除/跳过/放宽测试断言 | **未**改任何测试；反而**指出**既有测试的断言面**不覆盖** `sample_size`/`observations`（只登记） |
| 3 | 不得用 Mock 掩盖错误 | 探针**直接调用生产引擎**（编译进生产包），**无** mock、无桩 |
| 4 | 不得全样本最优充当结论 | 全部结论来自**事前写死的 20 变体**与 **±20% 网格**；`P` 表事前登记、逐条对账 |
| 5 | 不得为达阈值放宽杀死条件 | 杀死条件冻结于 `f5845a92`；`A-03` 的反证**未**用于放宽 `K4`（保留按事前配置的 `true`，只披露） |
| 6 | 不得把 `rule_score` 表述为上涨概率 | 本轮**零**概率/收益语言 |
| 7 | 不得引入做空/日内/高频 | 本轮无任何交易语义 |
| 8 | 不得操作本仓库以外的仓库 / 不得启用真实交易 / 不得索取真实凭证 / 不得向真实账户下单 | 只读本仓库 `research/paper_loop/**`；**零**交易、**零**凭证、**零**外部账户 |

### 9.2 `R014_prereg.md` §14 负面清单（第 9–13 条，逐条）

| # | 条款 | 执行 |
|---|---|---|
| 9 | 不得修改分组算法 / `ranks()` / `round4` / DTO 字段 / `factor.ts` 类型 / 前端列标签 | **一行未改**（全部登记为 `D-R014-*`） |
| 10 | 不得把「聚合 `observations` 是天数」叙述成「因子无效」 | 本报告**只**描述契约与量化读数；**无**任何因子有效性主张 |
| 11 | 不得据本轮量级设阈值 | **未**引入任何阈值；容差沿用冻结值（`5e-5`/`1e-9`）——它们只用于**算术重建比较**，不用于判定「大小」 |
| 12 | 不得把 `NOT_VIOLATED` 硬凑成 `REJECTED` | 本轮四子句**均有**承重证据；同时**如实**披露 `K4` 的读数分歧（若从严读 `K4` 为 `false` 也正是这条纪律的体现） |
| 13 | 不得把 `V03` 的置换敏感性叙述成「真实数据上分组收益会随机跳变」 | §3.1 末尾**明确**限定：只证明「未声明依赖**存在**」，影响面未测量 ⟹ `B-R014-02` |

### 9.3 与前几轮的重叠：**主动披露**（诚实归因）

| 既有轮次 | 重叠内容 | 本轮增量 |
|---|---|---|
| `R006`（`rank_ic_*` 命名族） | `observations` 的单位问题（已在 R006 作为承重证据登记过） | 本轮给出**更新的量化读数**（聚合 **3** 天 vs 日级 **2** 名字数 vs 同页标签「观测数」），并把口径收敛到**两数组逐元素层** |
| `R013`（`monotonicity` 标签） | ① 同型「占位 0 / 非有限值参与聚合」事实；② 同型「载荷层 `round4` 使结论不可重建」事实；② 同型「NaN 组均值」事实（R013 已登记为 `SYNTHETIC_ONLY` 并给出守卫链证据） | ① `round4` 的**逐值计数**（65/95）与**双面分歧**（`V10` 19 / `V11` 46）；② **`NaN → 0.0`** 的**机器标定**（`A-05`）与「把无数据当 0 收益」的**双面不一致**；③ `V01`–`V05`、`V12`–`V18` 中与本报告重叠部分之外的全部变体 |
| `R004`/`R012` | 可达性阶梯（同型定义） | 本轮**新增**：阶梯定义存在但**逐变体等级未冻结**这一装置层缺口（`A-03`/`D-R014-10`） |

**本轮与它们的差异**：R006/R013 审的是**标量**与**具名标签**，本轮审的是**两个数组载荷的逐元素数值与计数字段**；标量族**全部排除**（`rank_ic_mean`/`rank_ic_ir`/`pearson_ic_mean` 等属 R006/R007/R009）。

### 9.4 本轮**明确不做**的事

1. **不**修改任何生产代码（分组算法 / `ranks()` / `round4` / DTO / TS 类型 / 前端标签 / 单位声明）—— 全部 `D-R014-*` `PENDING_USER`；
2. **不**提出任何新因子，**不**做任何影子→正式提升（`shadow_registry.md` 本轮 0 条）；
3. **不**为凑「端到端」而启动 Spring 容器 / 建库 / 造数据（超范围，登记 `B-R014-02`）；
4. **不**把「未声明依赖存在」外推为「真实数据上会发生」；
5. **不**据本轮数值调整任何阈值、权重、质量闸或风险逻辑。
