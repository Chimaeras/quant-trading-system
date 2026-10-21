# R011 报告：承重分析分叉的「载荷可恢复性」与「备选可枚举性」审计

> 轮次：**R011**（审计轮）　基线 commit：`a8681459`（预注册冻结）　
> 预注册：`rounds/R011_prereg.md`　论文审查：`rounds/R011_review.md`　
> 实验：`experiments/R011_path_declaration_audit/`（`config.json` / `panel_R011.txt` / `probe/R011Probe.java` / `run_path_declaration_audit.py` / `result.json` / `run.log`）
> 判据来源：Menkveld, Dreber, Holzmeister, Huber, Johannesson, Kirchler, Neusüß, Razen, Weitzel 等（2024）
> *Nonstandard Errors*, **The Journal of Finance** 79(3):2339–2390，DOI `10.1111/jofi.13337`（**A 档**，CC-BY 开放获取）

---

> ## ⚠️ 事后裁定（2026-09-15，用户裁定；本报告**当前口径**以此为准）
>
> **总判定由 `REJECTED` 更正为 `INCONCLUSIVE`，`h0_alive` 记为「未判定」**（不再是 `false`）。
>
> **裁定内容**（`D-R011-03` 选项②，用户 2026-09-15 明示「**允许回到 `INCONCLUSIVE`**」）：
> 要求 `CLN` 保持「**谓词必须为真**」的**强语义**。据此，本轮在**看到结果之后**对仪器语义的修改（D9）
> **不被认可为一次合法的仪器修复** ⟹ 仪器门 `K8` 不得据此置为假 ⟹ 按冻结短路（§6.2）总判定**必须**回到
> `INCONCLUSIVE`（「本轮断言全部作废」），**H0 未判定**。
>
> **本裁定不推翻任何实测**：`K1–K5` 全部为真的**测量结果**、`V01–V20` 的判定、金丝雀 20/20、
> 三次仪器失败的运行史 **一律不变**，也**未**编辑任何冻结文件（`R011_prereg.md` 自 `a8681459` 起逐字未改，
> `run.log` 仍为 append-only 的 5 次运行）。变的是**仪器语义的可采纳性**，不是测量。
>
> **本文正文中所有 `REJECTED` 字样均为「按已废止仪器语义得出的历史记录」**，保留原文以存证，不再逐一改写；
> 完整裁定记录、影响范围与残留风险见 **附录 A-4**。

---

## 0. 一句话结论

> **本节为 2026-09-15 裁定后的当前口径。** 下列 `K1–K5` 实测与结论叙述**全部成立**，
> 但**总判定**因仪器语义裁定已改为 `INCONCLUSIVE`（见页首裁定块与附录 A-4）。
>
> **总判定 = `INCONCLUSIVE`，`h0_alive = 未判定`**（事后续正；按已废止仪器语义的原始记录为 `REJECTED` / `false`）。
> `K1/K2/K3/K4/K5` 五条杀死条件**全部为真**（该**实测**不受裁定影响），
> `K6–K10` 全部为假（仪器门全绿）。**但因本轮仪器语义系「看到结果之后」修改，仪器门不得据此判绿**，
> 故按冻结短路，本轮断言作废、H0 未判定。
> 被审计的 H0（项目对每个承重分析分叉都满足「载荷可恢复 + 备选可枚举 + 跨族一致」）
> **已观测到五处反例**：`min_cross_section_size` 在**全部 5 个生产载荷调用点**上**零发射**（`V04`），
> `group_count` 在 `F-SNAPSHOT` 族**缺键**（`V03`：该族**存在** `holding_days` 却**没有** `group_count`，
> 是**族内不对称**而非整体缺失），两条分叉在项目内**都没有任何备选集合**（`V07`/`V08`），
> `FRS:70` 与 `SBS:78` 给同一分叉**两个不同的定义来源**（`V09`），
> `docs`/`contracts` 对二者的取值与备选**零规定**（`V18`，扫描 4 个文件命中 0）。
> **这些反例不得因本次裁定而被叙述为「不存在」或「已被推翻」——它们只是本轮不再被采纳为可判决结论。**

**本轮不产出任何因子、不改一行生产代码、不提出任何提升请求**；`ADOPTED = 0`，
`shadow = 0`。与协议 §1 相反立场一致：这是一次**按预注册杀死了项目自身契约主张**的审计，不是又一篇被证伪的论文。

---

## 1. 口径、单元与边界

### 1.1 被审计对象（`subject`）

`subject_kind = project_contract_claim`。论文**降级为判据来源**（S1–S4，**全 L 档**），
审计对象是**项目自己**的契约主张 H0/H1。判据只支持「做法种类」，**不支持任何阈值**——
本轮**没有**把论文的 `NSE 1.2%/6.7%`、IQR、AD 检验、多重检验因子 `13–91`、`t ≥ 2.9` 下界
移植成任何阈值、容差或门（`forbidden_number_hits = []`，见 §8.4）。

### 1.2 H0 的边界（`R011_prereg.md` §1.3 **强制复述**）

H0 **不是**正确性命题：它**不**声称项目的因子研究口径是对的、**不**声称数值可信、
**不**声称应当做 multiverse 分析。H0 只声称三件事，对每个**承重分析分叉**（直接决定已发布研究量数值的方法选择）：

1. **可恢复**：仅凭该族载荷的**结构化字段**就能唯一确定该分叉的取值；
2. **可枚举**：项目**某一处**给出了该分叉的**合理备选集合**；
3. **跨族一致**：同一分叉不会在两族中以**不同定义来源**取值、而两族载荷**都不**恢复实际值。

H1（承重反例）= 至少存在一个承重分叉，在至少两族中**既**不可从载荷恢复、**也**未在项目任何一处被枚举为备选集合 ⟹ H0 被杀。
被杀死的是 **H0**，不是「项目很糟」；`REJECTED` 只表示上述三条不成立。

### 1.3 判定单元、变体数与冻结自检

| 项 | 冻结值 | 实测 | 一致 |
|---|---|---|---|
| 判定单元 | `variant` | `variant` | ✅ |
| 变体总数 `MAX_VARIANTS` | **20** | `variants_total = 20` | ✅ |
| 行和自检 | **20** | `row_sum = 20` | ✅ |
| 每轴计数 | `A1=1, A2=4, A3=3, A4=3, A5=1, A6=3, A7=2, A8=1, A9=2` | 逐项相等，`axis_total = 20` | ✅ |
| `grid_mismatch_count` | **0** | `0`（`len(checkers)==len(variant)` 亦通过） | ✅ |
| 承重变体（8 条） | `{V03,V04,V07,V08,V09,V10,V11,V18}` | 同 | ✅ |
| 非杀死轴 | `{A5,A6,A7}`；变体 `{V12,V13,V14,V15,V16,V17}` | 同 | ✅ |

---

## 2. 机器化事实（两侧都必须实测，不得互相替代 —— 协议 §5.1）

### 2.1 静态侧（源码文本，机械正则抽取）

| 观测量 | 实测 | 与冻结算术 |
|---|---|---|
| `FactorResearchEngine.summarize(` 生产调用点 | **5**：`FactorResearchService:529 / :747 / :791`、`ScoringBacktestService:431 / :443` | 等于 P-1 |
| 同上，测试调用点 | **3**：`FactorResearchEngineTest:73 / :113 / :204` | 等于 P-1 |
| `min_cross_section_size` **发射点** | **0**（键值为 0 处；`min_size_like_key_sites` 亦 0） | 等于 P-4 |
| `group_count` **发射点** | **1**（仅 `FRS:650 block.put("group_count", RANK_IC_GROUP_COUNT)`） | 等于 P-3 |
| `holding_days` **发射点** | **2**（`FRS:441` 顶层、`FRS:649` 块内） | 等于 P-2 |
| `universe_note` **发射点** | **4**（`FRS:169 / :227 / :535 / :654`） | `V05` 仅需 ≥3 |
| `horizons` 枚举处 | **1**（`FRS:97 UNIFIED_HORIZONS = List.of(1,2,3,5)` → `FRS:218` 发射） | 等于 P-5 |
| `group_count` 后端枚举处 | **0** | 等于 P-6 |
| 测试层 `summarize` 实参元组 | **1 个不同元组**：3 处**全部** `(h=5, J=5, ms=10)` | 等于 P-7 |
| `min_cross_section_size` 常量声明 | **2 处**：`FRS:71 = 10`、`SBS:58 = 10`；**跨文件引用 0 处** | 等于 P-8 |
| 前端 `group_count` 取值域 | **1 处**：`ScoringBacktest.vue:36` `:min="2" :max="10" :step="1"`（默认 `5` @ `:585`，传参 @ `:1099`） | 等于 P-6 |
| `docs` / `contracts` 对 J / min-size 的取值与备选规定 | **0 处**（扫描 `docs/API文档.md`、`docs/研究口径规范.md`、`contracts/feature_manifest.schema.json`、`contracts/model_manifest.schema.json`） | 等于 P-12 |
| note 文本敏感性/偏离/备选声明命中（**冻结词表**：`敏感`/`敏感度`/`robust`/`multiverse`/`备选`/`alternative`/`分叉`） | **2 处**（`SBS:654 robustZ(...)`、`SBS:699 robust Z on residuals`）——**均为方法名子串假阳性** | **≠ P-11**（见附录 A-2） |
| 行锚点校准 | **30 条全部 0 mismatch** | ✅ |

**方向性说明（必须写清）**：`holding_days` 在 `F-SNAPSHOT` 族**是发射的**（`FRS:441`，逐族覆盖 X04 实测 3/3 族可确定），
而 `group_count` 在**同一个** Service、**同一个**常量下，`F-SNAPSHOT` 族的 `buildSnapshotScopeBlock`（`:526–554`）**不发射**。
**族内不对称成立**（同一 Service、同一常量、两个族里只有一个发射）——这正是 `V03` 的承重面。

### 2.2 运行时侧（Java 探针直接调用生产 `FactorResearchEngine`，零外部依赖）

| 观测量 | 实测 |
|---|---|
| 网格格数 | **27**（`holding∈{1,3,5}` × `J∈{3,5,10}` × `ms∈{5,10,20}`） |
| 面板 | `n = [12,10,9,20,6]`（D1..D5），值以**字面量**写入 `panel_R011.txt`（探针运行时不重算） |
| `cross_section_days`（按 ms） | ms=5 → **5 日**；ms=10 → **3 日**；ms=20 → **1 日**，**27 格逐格**等于冻结 (A-1) 表 |
| `ms=10` 纳入日 | `D1, D2, D4`（D3=9、D5=6 被静默跳过 ⟹ 整日不进样本） |
| 池化块发射行数 | `grouped_rows ∈ {3,5,10}` ⟹ **恒定发射 `groupCount` 行**（空桶也占一行） |
| `J=10, ms=5` 的第 10 桶 | `top_bucket_observations = 3` ⟹ **只由 D1/D2/D4 三天贡献**（等于冻结 (A-2b)） |
| **空桶计数** | **0**（27 格全部 `empty_buckets = 0`）——**空桶分支在本轮面板上未 materialize**，如实报告，**不**声称它被验证 |
| `monotonicity` 取值分布 | **全部 `NON_MONOTONIC`**（27/27） |
| `long_short_spread` **符号翻转**（两两配对）| `flip_pairs = 0`；`sign_zero_cells = 0`；与基准格 `(5,5,10)` 比 **0** 格翻号 |
| `rank_ic_mean` 离散度（27 格） | min `0.375212331528121`，max `0.39916340968972547`，**IQR `0.023951078161604467`** |
| `long_short_spread` 离散度（27 格） | min `0.0043235858669974699`，max `0.0059317188835220211`，**IQR `0.00063419086284726128`** |
| 反向锚点①（正向） | `spearman = 1.0`（`0x1.0p0`）、`pearson_ic_mean = 1.0`、`MONOTONIC_INCREASING`、`spread = 9.5` ✅ |
| 反向锚点②（负向） | `spearman = -1.0`、`pearson_ic_mean = -1.0`、`MONOTONIC_DECREASING`、`spread = -9.5` ✅ |
| 反向锚点③（线性 3/5/10 组） | 三档全 `MONOTONIC_INCREASING`，spread 全为正 ✅ |
| `grid_selfcheck` | 27 格键集与计数自检 `mismatch_count = 0`；`bucket10_contributors_ok = True` |
| `G3` 类加载来源 | `file:/E:/Project/quant-trading-system/modules/backend-java/target/classes/`（后缀匹配通过） |
| 确定性 | `probe_bytes_equal = True`、`bits_equal = True`、`static_equal = True`（`float.hex()` 逐位） |

---

## 3. 20 条断言的实测判定（`R011_prereg.md` §4 逐条对齐）

> 记法：`DEV` = 与冻结期望**偏离**（必须进报告，**不得**回写冻结文本）。

| 变体 | 轴 | 冻结期望 | **实测判定** | 一致 | `raw_measured` 关键量 |
|---|---|---|---|---|---|
| V01 | A1 | `NOT_VIOLATED` | `NOT_VIOLATED` | ✅ | prod=5, test=3 |
| V02 | A2 | `RECOVERABLE` | `RECOVERABLE` | ✅ | `holding_days` 覆盖族数=3 |
| V03 | A2 | `NOT_RECOVERABLE_ABSENT_KEY` | `NOT_RECOVERABLE_ABSENT_KEY` | ✅ | 发射点=1；`snapshot_block_has_group_count=false`；`scoring_payload_has_group_count=true` |
| V04 | A2 | `NOT_RECOVERABLE_ABSENT_KEY` | `NOT_RECOVERABLE_ABSENT_KEY` | ✅ | min-size 发射点=0 |
| V05 | A2 | `RECOVERABLE` | `RECOVERABLE` | ✅ | `universe_note` 发射点=4（**自由文本层**，不补偿 V03/V04） |
| V06 | A3 | `ENUMERABLE_SINGLE_SITE` | `ENUMERABLE_SINGLE_SITE` | ✅ | `horizons` 枚举=1、发射=1 |
| V07 | A3 | `NOT_ENUMERABLE` | `NOT_ENUMERABLE` | ✅ | 后端枚举=0；测试不同元组=1；前端取值域覆盖族=1（**反对性证据**，见 §5.6） |
| V08 | A3 | `NOT_ENUMERABLE` | `NOT_ENUMERABLE` | ✅ | 枚举=0 / 取值域=0 / 测试变异=0 |
| V09 | A4 | `CONVENTION_DIVERGENCE` | `CONVENTION_DIVERGENCE` | ✅ | 来源数=2（编译期常量 vs 请求参数）；`sbs_request_param_wired=true` |
| V10 | A4 | `CONVENTION_DIVERGENCE` | `CONVENTION_DIVERGENCE` | ✅ | 声明文件=2；**共享声明=0**；**值相等=true**（两条分开写，见 §5.4） |
| V11 | A4 | `NOT_RECOVERABLE_ABSENT_KEY` | **`RECOVERABLE`** | **DEV** | `ScoringBacktestResponse` 含 `groupCount` 字段 **1** 个（`:81`）且**已接线**（`SBS:1024`）⟹ 勘误 **A-1** |
| V12 | A5 | `REGISTERED_ONLY` | `REGISTERED_ONLY` | ✅ | `J=10` 钳位日数=2；载荷发射的是**常量** |
| V13 | A6 | `NOT_VIOLATED` | `NOT_VIOLATED` | ✅ | 27 格；`spread_iqr = 0.000634190863` |
| V14 | A6 | `NOT_VIOLATED` | `NOT_VIOLATED` | ✅ | `grid_mismatch_count=0`；日数与 (A-1) 逐格相等；第 10 桶贡献者一致 |
| V15 | A6 | `NOT_VIOLATED` | `NOT_VIOLATED` | ✅ | `flip_pairs=0`（**如实写 0**）、`sign_zero_cells=0`、单调性取值=1 种 |
| V16 | A7 | `REGISTERED_ONLY` | `REGISTERED_ONLY` | ✅ | `SnapshotScopeIc` 两键皆无；`FactorRankIc.group_count` 为**必需**（**R009 `D-R009-05` 的同型不同实例**） |
| V17 | A7 | `REGISTERED_ONLY` | **`NOT_VIOLATED`** | **DEV** | 机械命中=2（均为 `robust` 子串假阳性）⟹ 勘误 **A-2** |
| V18 | A8 | `NOT_ENUMERABLE` | `NOT_ENUMERABLE` | ✅ | 文档规定=0（扫描 4 文件） |
| V19 | A9 | `NOT_VIOLATED` | `NOT_VIOLATED` | ✅ | 三项确定性全真 |
| V20 | A9 | `NOT_VIOLATED` | `NOT_VIOLATED` | ✅ | 20 对金丝雀全过；锚点真；G3 过 |

**偏离仅 2 条（V11、V17），且两条都是「预注册的期望写错」，不是仪器失效**——按协议**未改动冻结文本**，
勘误只写在**附录 A**（见 §12）。**两条偏离都使项目显得更好，而不是更差**，这一点如实标注。

**V18 计数口径（冻结要求）**：`V07`（备选不可枚举）、`V08`（同上）、`V18`（文档无规定）
**事实同源、计数分开**——**三项不得相加成三条独立发现**。

---

## 4. 预测核对（`R011_prereg.md` §8 的 P-1…P-16，逐条）

| # | 预测 | 实测 | 结论 |
|---|---|---|---|
| P-1 | 调用点 生产 5 / 测试 3 | 5 / 3，名单逐点一致（见 §2.1） | ✅ |
| P-2 | `holding_days` 发射点 = 2 | 2（`FRS:441`、`:649`） | ✅ |
| P-3 | `group_count` 发射点 = 1；因参数缺失 F-SNAPSHOT 与 F-SCORING 均不发射 | 发射点 = **1**；F-SNAPSHOT **确不发射**；**但 F-SCORING 经持久化 DTO 接线而发射** | **部分 ❌**（折算入勘误 **A-1**） |
| P-4 | min-size 发射点 = 0 | 0 | ✅ |
| P-5 | `horizons` 枚举恰好 1 处 | 1 | ✅ |
| P-6 | `group_count` 无项目级备选；唯一取值域是前端 `[2,10]`（F-SCORING 单族） | 后端枚举 0；前端取值域 1 处、只覆盖 1/4 族 | ✅ |
| P-7 | min-size 无任何枚举/取值域/测试变异 | 全 0；3 处测试全 `(5,5,10)` | ✅ |
| P-8 | 两处 min-size 常量互不引用 | 共享声明 0、跨文件引用 0（值同 `10`） | ✅ |
| P-9 | `ScoringBacktestResponse` 及其 `FactorIcSummary` **不含** group-count 字段 | `FactorAnalysis:81 private int groupCount;` **存在** | **❌**（勘误 **A-1**） |
| P-10 | `SnapshotScopeIc` 两键皆无；`FactorRankIc.group_count` 必需 | 同 | ✅ |
| P-11 | note 文本敏感性/偏离/备选声明命中 = 0 | 机械命中 **2**（子串假阳性）；词边界收紧后 = **0** | **❌**（勘误 **A-2**） |
| P-12 | `docs`/`contracts` 对 J / min-size 无任何规定 | 0 处命中（4 文件） | ✅ |
| P-13 | 27 格 `cross_section_days` 与 (A-1) 逐格相等 | 逐格相等，`days_match_frozen=True` | ✅ |
| P-14 | `J=10` 在 n=9/6 上钳位 9/6；池化块恒定发射 10 行；第 10 桶只由 D1/D2/D4 贡献 | 钳位日数 = 2；`grouped_rows=10`；第 10 桶 `observations = 3` | ✅ |
| P-15 | 反向锚点①–③全真 | 全真（§2.2） | ✅ |
| P-16 | 总判定 `REJECTED`、`h0_alive=false`（K1–K5 全真） | `REJECTED` / `false`，K1–K5 全真 | ✅ |

> **T1–T10/P-13…P-15 是「读码/算术决定」的预测**（预注册已如实标注），
> 本表**不**把它们当能力证据。真正待测的 P-11/P-12：**P-12 成立、P-11 被证伪**（且证伪原因是**测量学的**，见附录 A-2）。
> **P-16 的读法（2026-09-15 裁定后补注）**：该行的「实测」栏记的是**在已废止的仪器语义下**得到的读数，
> ✅ 只表示「当时与预注册预测一致」。裁定后**采纳的总判定是 `INCONCLUSIVE`**（见页首裁定块与附录 **A-4**）
> ——**不是** `REJECTED`。这一行**不得**被引用为「本轮 H0 被证伪」的证据。

---

## 5. 报告必答问题（`R011_review.md` §9 的 11 问）

### 5.1（问 1）调用点计数与名单是否逐点一致？

**是。** 生产 **5** / 测试 **3**，逐点名单：`FactorResearchService:529`（snapshot scope，毛）、
`:747`（rank-ic 毛）、`:791`（rank-ic 成本后）、`ScoringBacktestService:431`（主口径）、`:443`（分持有期子口径）；
测试 `FactorResearchEngineTest:73 / :113 / :204`。`V01 = NOT_VIOLATED`，`K10 = false`。

### 5.2（问 2）承重分叉有几条？每条在**每族**的可恢复状态？

从 5 个生产调用点的**实参**可机器抽取到 **3 条**随调用而变的承重分叉，另有 2 条非实参分叉（`horizons`/`universe_note`，由 V06/V05 判）：

| 分叉 | F-LEGACY | F-UNIFIED | F-SNAPSHOT | F-SCORING / F-PERSIST |
|---|---|---|---|---|
| `holding_days` | `RECOVERABLE`（`FRS:441`） | `RECOVERABLE`（`:441`/`:649`） | `RECOVERABLE`（`:441`） | `RECOVERABLE`（DTO `holdingDays`，`SBS:1023/1056`）⟹ 合计 **3/3 覆盖族** |
| `group_count` | `RECOVERABLE`（`FRS:650`） | `RECOVERABLE`（`FRS:650`） | **`NOT_RECOVERABLE_ABSENT_KEY`**（`:526–554` 块内无该键） | `RECOVERABLE`（`FactorAnalysis:81` + `SBS:1024`，**经持久化回读 `SBS:555`**） |
| `min_cross_section_size` | **`NOT_RECOVERABLE_ABSENT_KEY`** | **同左** | **同左** | **同左**（**4/4 族全缺**） |

`NOT_RECOVERABLE_INDIRECT` 本轮**未出现**：三族对该分叉要么有结构化键（可恢复），要么**完全没有键**（缺键），
不存在「只能从自由文本间接推断」的中间态。

### 5.3（问 3）min-size 在**全部**生产载荷中是否有对应键？发射点 0 还是 >0？

**0。** 键名字面量命中 **0**、类键名（`minCrossSection|min_cross_section|crossSectionMin|factorMinCross`）命中 **0**。
两处常量 `FRS:71`、`SBS:58` 只在**同文件内**被引用（跨文件引用 0 处），**从未进入任何载荷**。
消费方因此**无法**从任何已发布载荷知道 min-size 用了 10 ⟹ `V04 = NOT_RECOVERABLE_ABSENT_KEY`（承重，`K1 = true`）。

### 5.4（问 4）同一分叉在两个 Service 中定义来源是否不同？两族载荷是否**都不**发射实际取值？

**来源不同：是**（`FRS:70` 编译期常量、无入参）；`SBS:78` 是请求参数（`req.getFactorGroupCount()`，默认 5，前端 `:min=2 :max=10`）。

**「两族都不发射实际取值」：否，必须分开写**（`R011_prereg.md` §4 A4 的冻结要求）：

- `group_count`：**F-LEGACY/F-UNIFIED 发射**（`:650` 常量 5），**F-SCORING/F-PERSIST 发射**（DTO `groupCount`），
  **只有 F-SNAPSHOT 不发射**。所以真实缺陷不是「都不发射」，而是**「同一个 Service、同一个常量、两个族里只有一个发射」**
  ⟹ 消费方看到 F-SNAPSHOT 的报告时**无从知道**那一份用的是哪个 J、更无从知道它与 F-UNIFIED 用的是**同一个** J。
  判定 `V09 = CONVENTION_DIVERGENCE`（承重，`K3 = true`）。
- `min_cross_section_size`：**4/4 族全部不发射**（发射点 0）⟹ 这一条才是真正的「都不发射」。
- `V10` 的**值层与结构层必须分开写**：**值层一致**（`10 = 10`、`min_size_values_equal = true`）；
  **结构层无共享声明**（`min_size_shared_declaration_sites = 0`，两个私有常量各自独立）
  ⟹ 结构层 `CONVENTION_DIVERGENCE`。**一句话「一致」或一句话「分歧」都是错的。**

### 5.5（问 5）项目内是否存在**任何一处**枚举了 J 或 min-size 的合理备选集合？逐类给出，不合并

| 类别 | `group_count`（J） | `min_cross_section_size` |
|---|---|---|
| ① 枚举列表（`List.of(...)`/数组常量） | **未命中**（后端枚举 0 处） | **未命中** |
| ② 配置项（`application*.yml` / `@Value` / 配置类） | **未命中**（`config_sites = 0`） | **未命中** |
| ③ 文档 / 契约（`docs/*.md`、`contracts/*.json`） | **未命中**（`docs_spec_sites = 0`） | **未命中** |
| ④ 测试用例参数表 | **未命中**（3 处调用**同一**元组 `(5,5,10)`，不同元组数 = **1**） | **未命中**（测试变异 = 0） |
| ⑤ 显式取值域（请求参数 / 前端控件边界） | **命中 1 处**：`ScoringBacktest.vue:36` `:min="2" :max="10"`（**仅覆盖 1/4 族**） | **未命中**（前端 `:min="2" :max="10"` 是 **group-count** 输入框的 prop，**不是** min-size 的取值域） |

**结论**：J 有**唯一一处**族级取值域，按冻结口径（「备选集合须为该分叉在项目内的**合理备选**」）
**不构成**项目级备选集合 ⟹ `V07 = NOT_ENUMERABLE`。min-size **五类全空** ⟹ `V08 = NOT_ENUMERABLE`。`K2 = true`。

### 5.6（问 6）`holding_days` 与 `group_count` 的发射面各覆盖几族？族内不对称是否成立？

`holding_days` 覆盖 **3/3 可确定族**（含 `F-SNAPSHOT` ⟹ 也含 F-SCORING，见 §5.2 表）；
`group_count` 覆盖 **3/4 族**，缺 `F-SNAPSHOT`（`:526–554` 无 `"group_count"` 字面量，而**同一块内**有 `"holding_days"`）。
**族内不对称成立**：同一个 `FactorResearchService`、同一个 `RANK_IC_GROUP_COUNT`，两个族里只有一个发射。
`K4 = true`（由 **`V03` 单独**支撑）。

### 5.7（问 7）multiverse 复算的离散度、符号翻转计数？两次运行是否逐位相同？

- `rank_ic_mean`：min `0.375212331528121` / max `0.39916340968972547` / **IQR `0.023951078161604467`**；
- `long_short_spread`：min `0.0043235858669974699` / max `0.0059317188835220211` / **IQR `0.00063419086284726128`**；
- **符号翻转计数 = 0**（`flip_pairs = 0`，与基准格比亦 0 格翻号；`sign_zero_cells = 0`）；
- `monotonicity`：**27/27 全 `NON_MONOTONIC`**（单一取值）；
- 两次独立运行：`probe_bytes_equal = True`、`bits_equal = True`、`static_equal = True` ⟹ **逐位相同**（`K9 = false`）。

**`flip_pairs = 0` 如实写出，未改面板、未改网格凑触发**（`R011_prereg.md` §4 A6 的冻结承诺）。

### 5.8（问 8）±20% 网格是否改变结论的**存在性/符号**，还是只**平移**数值？

±20% 敏感性网格（`J∈{4,5,6}` × `ms∈{8,10,12}`，`h=5`，**9 格**）实测：

| J | ms | `cross_section_days` | `rank_ic_mean` | `long_short_spread` | 符号 vs 基准 |
|---|---|---|---|---|---|
| 4 | 8 | 4 | 0.36187255726729411 | 0.0048514041819573018 | 同号 |
| 4 | 10 | 3 | 0.39916340968972547 | 0.0045906399208387686 | 同号 |
| 4 | 12 | 2 | 0.37753299332246704 | 0.0040496198492502968 | 同号 |
| 5 | 8 | 4 | 0.36187255726729411 | 0.0059897828619852739 | 同号 |
| 5 | 10 | 3 | 0.39916340968972547 | 0.0052308169586672204 | 同号（基准格） |
| 5 | 12 | 2 | 0.37753299332246704 | 0.0054062355150818163 | 同号 |
| 6 | 8 | 4 | 0.36187255726729411 | 0.0060749141391017916 | 同号 |
| 6 | 10 | 3 | 0.39916340968972547 | 0.0053443253281559083 | 同号 |
| 6 | 12 | 2 | 0.37753299332246704 | 0.0053556509015013614 | 同号 |

**结论（按 R009 教训 51 的措辞）**：`spread_sign_changed_vs_base = 0/9` ⟹ **不存在存在性/符号层面的改变**；
`ic_mean_changed_vs_base = 6/9` ⟹ 只是**数值平移**。具体地，`rank_ic_mean` 在固定 ms 下**完全不随 J 变**
（480/10、4\|5\|6 三档同值），只随 ms 变三档；`long_short_spread` 随 J 与 ms 双双变化但**符号恒为正**。
**注意**：本轮**不**用任何阈值判定「平移是否算稳健」——这属于 R011 命题面之外的口径问题。

### 5.9（问 9）载荷/文档中是否有关于分叉取值或分叉敏感性的**声明**（预期 0 处）？

- **结构化载荷**：有 J 的取值（`FRS:650` 常量 5 与 DTO），**无**任何「这是分叉、备选是什么、敏感性多大」的声明；
- **自由文本 note 层**：机械命中 **2 处**（`SBS:654 robustZ(residual,MAD)`、`SBS:699 robust Z on residuals`），
  逐条查看后确认二者**都是方法名/流程名里的 `robust` 子串**，**不是**敏感性声明（词边界收紧后命中 = **0**）；
- **文档/契约**：`docs_spec_sites = 0`。

⟹ **有效声明 = 0 处**。**这**不**构成违约，只登记**（`V16`/`V17 = REGISTERED_ONLY`）：
项目**从未承诺**披露敏感性或偏离对账，按 `R011_review.md` §2.4 的强制提醒，
**不得**把「没做 multiverse」读成违约，更**不得**据此增加杀点。

### 5.10（问 10）本轮是否出现「为了产出而放水」的迹象？

**没有**，逐条自查（体例对齐 `progress.md` §7）：

| 自检项 | 结论 |
|---|---|
| 预注册是否在实验代码之前落盘？ | 是：`a8681459` 冻结在**任何** `experiments/R011_*/` 代码写入之前 |
| 杀死条件是否被事后放宽？ | **否**：`R011_prereg.md` §6.1 的 K1–K10 与 §6.2 的短路**逐字未改**，本轮**未编辑**冻结文件（见 §8.3 的字节级证据） |
| 冻结期望被实测证伪时怎么处理？ | **照实写 + 附录勘误**（V11/P-9、V17/P-11），**未**回写冻结文本、**未**改动判定词 |
| 有没有把「没做某事」当违约？ | 没有：`V16`/`V17` 一律 `REGISTERED_ONLY` |
| 有没有用单点最优当结论？ | 没有：27 格 + 9 格 ±20% 全表披露，符号翻转写 0 |
| 有没有删/跳过/放宽测试断言？ | 没有：Java 350（0F/0E/0S）+ Python 343 **全绿**，本轮**零**生产代码改动 |
| 有没有把 `rule_score` 说成上涨概率？ | 没有（本轮不涉及打分语义） |
| 有没有引入做空/日内/高频？ | 没有（面板为日频 5 日截面） |
| **仪器修复是否改变了结论？** | **是，且已单独披露**：见 §8.2/§8.3 与 `D-R011-03`，**这是本轮最需要用户复核的一项** |

### 5.11（问 11）零生产代码改动是否成立？三套测试是否与基线逐项一致？

**成立。** `git status --porcelain` 在闭环结束后只显示 `?? research/paper_loop/experiments/R011_path_declaration_audit/`
（另加本轮台账/报告文件），**无任何** `modules/**` 或 `docs/**` 改动；探针编译产物 `R011Probe.class`
在每次运行末尾被 `cleanup_probe_classes()` 删除（`run.log` 有记录）。
测试复测：**Java 350**（64 个 surefire 报告文件，`Failures 0 / Errors 0 / Skipped 0`）、
**Python 343 passed**（3 warnings，与基线同）——与 `progress.md` 记录的基线**逐项一致**。

---

## 6. `R011_prereg.md` §10 的补充 6 问（12–17）

### 6.1（问 12）`CLN` 覆写了每个 checker 的哪些键？

**每个 checker 覆盖其声明的**全部**键，且值取自 `raw_measured`（实测字典）**——逐条如下（`result.json` → `canaries[*].keys_overwritten`）：

| 变体 | `CLN` 覆写的键 |
|---|---|
| V01 | `summarize_prod_call_sites`, `summarize_test_call_sites` |
| V02 | `holding_days_families_covered` |
| V03 | `group_count_emission_sites`, `snapshot_block_has_group_count`, `scoring_payload_has_group_count` |
| V04 | `min_size_emission_sites`, `min_size_like_key_sites` |
| V05 | `universe_note_emission_sites` |
| V06 | `horizons_enumeration_sites`, `horizons_emission_sites` |
| V07 | `group_count_backend_enum_sites`, `distinct_test_tuples`, `frontend_domain_covers_families` |
| V08 | `min_size_enum_sites`, `min_size_domain_sites`, `min_size_test_variants` |
| V09 | `group_count_distinct_sources`, `frs_source_kind`, `sbs_source_kind`, `sbs_request_param_wired` |
| V10 | `min_size_declaring_files`, `min_size_shared_declaration_sites`, `min_size_values_equal` |
| V11 | `response_group_count_fields`, `response_ic_summary_group_count_fields` |
| V12 | `clamp_days_J10`, `emitted_value_kind` |
| V13 | `cells`, `has_grouped_returns`, `spread_iqr` |
| V14 | `grid_mismatch_count`, `days_match_frozen`, `bucket10_contributors_matches` |
| V15 | `flip_pairs`, `sign_zero_cells`, `monotonicity_values` |
| V16 | `snapshot_scope_has_group_count`, `snapshot_scope_has_holding_days`, `rank_ic_group_count_required` |
| V17 | `sensitivity_term_hits` |
| V18 | `docs_spec_sites`, `contracts_files_scanned` |
| V19 | `det_probe_bytes_equal`, `det_static_equal`, `det_bits_equal` |
| V20 | `canary_pairs`, `canary_all_pass`, `anchors_pass`, `g3_pass` |

**必须披露（冻结要求）**：`CLN` 覆写上述键 ⟹ 它就是这些 checker 的**纯单元测试**
（谓词是**其所声明键集合**的确定性函数）。
它**不**构成「这些断言在生产数据上有效」的证据——生产数据的有效性由 §2 的**实测抽取**单独证明。

### 6.2（问 13）20 对金丝雀中是否有 FAIL？逐次披露时刻与成因，是否改变了判定？

**有。** 按 `run.log`（append-only，现存 5 次运行）：

| 运行 | 时刻 | 总判定 | 金丝雀状态 | 成因 |
|---|---|---|---|---|
| 1 | 00:10:04 | `INCONCLUSIVE` | `V03` 金丝雀未过 | `V03` 的 `judge` **方向写反**（缺键被写成「正常」）⟹ `K4 = false` |
| 2 | 00:11:58 | `INCONCLUSIVE` | `V03/V11/V17/V20` 的 `INJ` 腿未过 | `INJ` 的注入值按**期望状态**而非**实测状态**选取 ⟹ 注入后谓词**不翻转** |
| 3 | 00:13:34 | `INCONCLUSIVE` | 同上 | `V20` 另有**自指**：`canary_pairs == 20` 含它**自己那一对** |
| 4 | 00:13:53 | **`REJECTED`** | **20/20 全过** | 修 `INJ` 取值 + 拆分 `V20` 执行次序 |
| 5 | 00:14:24 | **`REJECTED`** | **20/20 全过** | 另修「禁数自匹配」（§8.4），判定不变 |

**金丝雀是否改变了判定？改了，但改的是「仪器门」而不是「杀点」**：
`K1–K5` 在**运行 2** 就已**全部为真**（杀点从未依赖金丝雀）；
被金丝雀影响的是 `K8`（仪器门），它在运行 1–3 为真、运行 4–5 为假。
按冻结短路（§6.2），`K8` 为真时总判定**必须**是 `INCONCLUSIVE`（「本轮断言全部作废」），
所以**在仪器修好之前，本轮不能宣布 `REJECTED`**——这一点已如实执行，**没有**在仪器未通过时抢跑结论。

### 6.3（问 14）`run.log` 中共有几次运行？其中几次是**我自己的仪器缺陷**？逐次成因与修复

**`run.log` 现存 5 次完整闭环运行**（append-only），其中 **3 次因仪器缺陷失败**（运行 1–3），2 次成功（运行 4–5）。
**诚实边界**：`run.log` 是本轮唯一的运行记录文件；**仪器搭建期（00:10 之前）**的若干次运行
**未**落在该文件内（该文件在搭建后期才建立），因此**本报告不声明一个精确的总运行次数**——
只列**可追溯的缺陷**及其修复（下表），并给出「由金丝雀抓出」与「由崩溃/断言抓出」的区分。

| # | 缺陷 | 抓出者 | 修复 |
|---|---|---|---|
| D1 | `REPO` 相对层级算错（3 级→实际需 4 级） | 崩溃（`FileNotFoundError …\research\modules\…`） | 两个脚本都改 4 级 |
| D2 | JDK 21 stdout 用平台代码页（GBK），中文分组标签导致 Python `UnicodeDecodeError: 0xb5` | 崩溃 | 探针改 `Files.writeString(..., UTF_8)` 落盘，stdout 只留 ASCII 摘要 |
| D3 | Git-Bash 的 `/tmp` 与 native Python 的 `/tmp` 解析不一致 | 崩溃（`FileNotFoundError`） | 中间产物改放仓库内 `_scratch/` |
| D4 | 探针 `jsIntList(new ArrayList<>(byDay.keySet()))`（String 集合塞 int 列表） | 编译失败 | 改 `jsStrList(...)` |
| D5 | `V08` 把前端 `:min="2"`（**group-count** 输入框）当成 min-size 取值域 | 读码复核 | 只匹配 min-size 同名标识符；判定不变（仍 `NOT_ENUMERABLE`） |
| D6 | `V03` 的 `judge` **方向写反** | **金丝雀（运行 1）** | 改为 `verdict_if(snapshot 缺 or scoring 缺, NOT_RECOVERABLE_ABSENT_KEY, …)` |
| D7 | `V03` 的 `scoring_payload_has_group_count` 只找 `put("group_count"`，漏掉 F-SCORING 经 **DTO builder** 的接线 | 读码复核 | 改查 `toFactorAnalysisDto(...).groupCount(...)`（实测 `true`）⟹ 直接导致勘误 **A-1** |
| D8 | `V05` 量的是「键是否出现过」(0/1) 而非**发射点数** | 读码复核 | 改为发射点计数（实测 **4**） |
| D9 | `CLN` 语义写成「谓词必须为真」⟹ **假设被证伪会被误报成仪器失效**，把诚实负结论系统性转成 `INCONCLUSIVE` | 读码复核 | 改为「谓词是**所声明键集合**的确定性函数」+「未声明哨兵键不改变结果」（`run_canaries` 文档串已注明理由） |
| D10 | `INJ` 注入值按**期望状态**选取（`V03/V11/V17/V20`），实测状态下**不翻转** | **金丝雀（运行 2）** | 注入值改按**实测状态**选取 |
| D11 | `V20` **自指**：其判据含 `canary_pairs == 20`，而它自己那一对也在 20 之内 ⟹ 填「已跑完数」则为 19，谓词恒假 | **金丝雀（运行 3）** | 分两步执行（先 V01–V19，回填后再跑 V20）；填**声明数** `len(C)`，并**断言** `len(C) == CANARY_PAIRS` 且跑完后 `len(canaries)` 相等 |
| D12 | 「禁数」自检匹配到**自己 denylist 描述串**里的 `t >= 2.9` | 自检输出 | 改描述文字（**不**改匹配规则，见 §8.4） |
| D13 | 预注册 3 处行锚点写错（`:139→:140`、`:171→:174`、hacStd 守卫需写明 `:325-327`） | 冻结前用 Python 逐条核验 | 在**冻结提交之前**修正；现已 30 条 0 mismatch |
| D14 | 预注册 (A-2) 算术不完整（漏「池化块恒定发射 `groupCount` 行、空桶 `avg=0`、第 10 桶贡献日不齐」） | 冻结前自查 | 扩写为 (A-2)/(A-2b)/(A-2c)，并同步 P-14 与 V13 |
| D15 | `F-UНIFIED` 混入西里尔 `Н`（U+041D） | 冻结前自查 | 改回 ASCII |

**必须写明的区分**：D1–D5、D7、D8、D12–D15 是**实现/文档缺陷**（不改变任何判定）；
**D6、D9、D10、D11 是「会改变输出」的仪器缺陷**，全部由**金丝雀**或读码复核抓出，
且**全部在冻结文本之外**修复（未编辑任何冻结文件）。

### 6.4（问 15）是否有「为了产出而放水」的迹象？

见 §5.10 的九项自查，**结论：没有**。三项需要额外强调的**反面证据**：

1. **杀死条件未被放宽**：K1–K5、K6–K10 与短路逻辑在 `R011_prereg.md` 中**逐字未改**；
   本轮**没有**因为「连续轮次无产出」而降低任何门槛。
2. **`flip_pairs = 0` 与 `empty_buckets = 0` 都照实写**，
   **没有**为了「让披露轴好看」去改面板或扩大网格制造触发。
3. **仪器修复的方向是让「仪器失效」的假信号消失，而不是让「假设」更容易存活**——
   修好之后总判定从 `INCONCLUSIVE` **变成 `REJECTED`**（对项目**更不利**）。
   反过来说，**如果我不修，本轮就会以「仪器失效」收尾**；这一取舍已在 `D-R011-03` 交用户复核。

### 6.5（问 16）`result.json` 的 `raw_measured` 与 `expected` 是否逐项可比对？有无「未测量却给了判定」的条目？

**逐项可比对，且无未测量条目。** 机械核验：把每个 checker 的 `keys`（= `CLN` 覆写键集合）
与 `raw_measured[variant]` 的键集合求差，**差集为空（20/20）**——
即**每条断言用到的每个键都有实测值**，不存在「凭常量/凭直觉给判定」的条目。
`expected` 取自 `config.json`（由 `make_config.py` 生成，**冻结期望的唯一来源**），
`verdicts` 由 `judge` 在 `raw_measured` 上算出，两者的比对即 `deviations`（本轮 2 条）。

### 6.6（问 17）V07 的反对性证据（前端 `[2,10]`）出现在哪里？是否与判定理由同段？

**同段出现**（`R011_prereg.md` §4 A3 冻结要求逐字满足）。原文照录如下：

> **V07 = `NOT_ENUMERABLE`。判定理由**：`group_count` 分叉在后端**无**枚举列表（后端枚举处 = 0）、
> 测试层 3 处调用**全部**是同一元组 `(5,5,10)`（不同元组数 = 1），
> 唯一看起来像「备选集合」的东西是前端 `ScoringBacktest.vue:36` 的取值域 `:min="2" :max="10" :step="1"`。
> **反对性证据（必须同时列出）**：该取值域**确实存在**，且**确实是**一个显式离散取值域。
> **为什么仍判 `NOT_ENUMERABLE`**：它只覆盖 **1/4 族**（仅 F-SCORING），
> 且**不**覆盖 F-LEGACY/F-UNIFIED/F-SNAPSHOT；按冻结口径「备选集合须为该分叉在项目内的**合理备选**」，
> **单族 UI 输入框的取值范围不构成项目级备选集合**（它约束的是「用户能输入什么」，
> 不是「项目研究过哪些 J」）。**两条并列，不合并、不省略。**

---

## 7. 杀死条件、短路与总判定

### 7.1 K 表实测

| 编号 | 条件 | 实测 | 是否触发 |
|---|---|---|---|
| **K1** | `V04 == NOT_RECOVERABLE_ABSENT_KEY` | `V04` = 同值 | **✅ 真** |
| **K2** | `V07 == NOT_ENUMERABLE` **且** `V08 == NOT_ENUMERABLE` | 二者皆同值 | **✅ 真** |
| **K3** | `V09 == CONVENTION_DIVERGENCE` | 同值 | **✅ 真** |
| **K4** | `V03 == NOT_RECOVERABLE_ABSENT_KEY` **或** `V11 == NOT_RECOVERABLE_ABSENT_KEY` | `V03` = 同值 ⟹ 真；**`V11` 实测 `RECOVERABLE`，不贡献** | **✅ 真（仅由 V03 支撑）** |
| **K5** | `V18 == NOT_ENUMERABLE` | 同值 | **✅ 真** |
| **K6** | 承重变体 ∈ {`REGISTERED_ONLY`, `NOT_REACHABLE`} | `k6_triggered_by = []` | ❌ 假 |
| **K7** | 仪器门（行和 / config 与 variant 表 / 网格自检） | `grid_mismatch_count = 0`、行和 20、`axis_total` 20 | ❌ 假 |
| **K8** | 仪器门（金丝雀 / 反向锚点 / `G3` / 依赖 / 表外词） | 20/20 金丝雀过、锚点全真、`G3` 过、依赖钉死、`out_of_vocab = []` | ❌ 假 |
| **K9** | 确定性门 | 三项全真 | ❌ 假 |
| **K10** | `V01` 计数 =（5, 3） | 5 / 3 | ❌ 假 |

**`K4` 必须显式标注**：它由 **`V03` 单独**支撑；冻结期望里 `V11` 原本也会触发 `K4`，
但**实测把 `V11` 证伪**（`RECOVERABLE`）⟹ **`K4` 的触发面比预注册预期窄**，
不得表述为「`V03` 与 `V11` 双杀」。

### 7.2 短路与总判定（逐字照用冻结逻辑）

```
K7|K8|K9|K10      -> INCONCLUSIVE    本次：全假，不进入
K1|K2|K3|K4|K5    -> REJECTED        本次：全真，命中
else              -> NOT_VIOLATED
```

**总判定 = `INCONCLUSIVE`　`h0_alive = 未判定`　`unit_of_judgment = variant`　`out_of_vocab = []`。**

> **此处的 `INCONCLUSIVE` 是 2026-09-15 用户裁定后的当前口径**（事后续正；上方短路段落与 `K1–K5` 的全真实测为历史记录）。
> 冻结短路本身**逐字未改**：变的是「`K8` 是否可判为假」——本轮仪器语义系看到结果后修改，按强语义（`CLN` = 谓词必须为真）
> 不得据此判绿，故 `K8` 仍须按**未修复**状态读作真 ⟹ 强制 `INCONCLUSIVE`。完整记录见附录 A-4。

### 7.3 强制披露（`R011_prereg.md` §6.3 冻结措辞，逐条照写）

1. **「本轮 20 条断言的判定词在冻结时就已由读码与机械抽取确定」**——审计轮的固有性质
   ⟹ 本报告**不**把命中率包装成预测能力（P-16 的 ✅ **不**是「预测成功」的证据）。
2. **「K6 未改变总判定」**——`K6 = false`，故本轮不适用；承重变体无一落入 `REGISTERED_ONLY`/`NOT_REACHABLE`。
3. **「V05 的自由文本 `universe_note` 不补偿 V03/V04」**——`note` 类不计入结构化字段（判据 P1）；
   `universe_note` 发射点 = 4 **只**说明「口径被声明过」，**不**恢复 J 或 min-size 的取值。
4. **「V07 的判定理由含一条反对性证据」**（前端确有 `:min=2 :max=10` 取值域）——已**逐字**写入 §6.6。
5. **「V12/V16/V17 只登记」**——其中 `V16` 是 R009 `D-R009-05` 的**同型不同实例**（不同键、不同分支）。
6. **「本轮零生产代码改动」**——三套测试与基线逐项一致（**Java 350 / Python 343**，见 §5.11）。

---

## 8. 运行史、仪器修复与自我复核

### 8.1 运行次数与失败原因

`run.log`（append-only）现存 **5 次**完整闭环运行：**3 次 `INCONCLUSIVE`（仪器缺陷）→ 2 次 `REJECTED`**。
仪器搭建期更早的运行未落在该文件内 ⟹ **本报告不为它们编造次数**（见 §6.3）。

### 8.2 仪器修复是否改变了结论？（**本报告最重要的一项披露**）

**改了。** 逐条说明：

1. **修复前**（运行 1–3）：`K8 = true` ⟹ 冻结短路**强制**总判定为 `INCONCLUSIVE`，即「本轮断言全部作废」。
2. **修复内容**（全部在**冻结文本之外**，未编辑任何冻结文件）：
   `CLN` 语义（D9）、`INJ` 注入值方向（D10）、`V20` 自指拆分（D11）、`V03` 判据方向（D6）、
   `V03`/`V05` 的测量方法（D7/D8）、禁数自匹配（D12）。
3. **修复后**（运行 4–5）：`K8 = false` ⟹ 总判定 `REJECTED`。
4. **关键事实（反放水证据）**：**杀点 `K1–K5` 在运行 2 就已全部为真**，
   修复**没有**触碰任何一个判定词、任何一个 `judge`、任何一条杀死条件；
   修复只是**移除了一个假阳性仪器失效信号**。
5. **同时必须承认的风险**：这是一次**看到结果之后**的仪器修改。若用户认为 `CLN` 应保持
   「谓词必须为真」的更强语义，则本轮应回到 `INCONCLUSIVE`。
   ⟹ **该分支已由用户裁定**（2026-09-15，`D-R011-03` 选项②）：**要求强语义**，故**本轮总判定已更正为 `INCONCLUSIVE`**，
   `h0_alive` 记为**未判定**。逐条记录与残留风险见**附录 A-4**。
   ⟹ 已登记 **`D-R011-03`（PENDING_USER）**，请用户复核该仪器语义选择。
   **未**在数据上做任何取舍，**未**删除任何一次失败运行（`run.log` 保留全部 5 次）。

### 8.3 冻结文件是否被改动？

**未改动。** `rounds/R011_prereg.md` 与 `rounds/R011_review.md` 自 `a8681459` 冻结后
**零字节变更**（本轮的所有更正写入**本报告附录 A**）。
本轮实验代码 `experiments/R011_path_declaration_audit/**` 与台账文件为**新增/追加**。

### 8.4 「输入产物不含论文数值」自检

`check_forbidden_numbers()` 在**首次**实现时报告 1 处命中，逐条查看后确认是
**自匹配（false positive）**：命中的是**该自检自己的 denylist 描述串**中的 `t >= 2.9` 文本，
而非任何实验产物、容差或阈值。处理方式是**改描述文字**（`"t >= 2.9"` → `"paper t-stat lower bound"`），
**匹配规则未放宽**、**检索面未缩小**（仍扫全脚本 + `config.json` 的 `tolerances`）。
修复后 `forbidden_number_hits = []`。

---

## 9. §6.5 区间下界条款：逐条说明为何不适用（复述冻结结论）

本轮**不采用**任何区间下界形式（`R007` 教训 40）。理由逐条：`V04`/`V07`/`V08`/`V18` 是**计数型**
（0 处发射、0 处枚举），不存在「下界」概念；`V03`/`V11` 是**键存在性型**（有/无），同样无下界；
`V09`/`V10` 是**来源同一性型**（常量 vs 请求参数、声明是否共享）；
唯一的「区间」出现在**披露轴** `V15`（`flip_pairs ≥ 0`），且冻结时就写明
「**若实测为 0，必须如实写 0，不得改面板或改网格凑触发**」——实测确为 **0**，已如实写。

---

## 10. 强制披露：变体数、±20% 敏感性与偏差影响

| 披露项 | 内容 |
|---|---|
| **变体数 N** | **20**（= `MAX_VARIANTS`，行和 20，`row_sum = axis_total = 20`） |
| **±20% 敏感性** | **9 格**（`J∈{4,5,6}` × `ms∈{8,10,12}`，`h=5`）：**符号改变 0/9**、数值改变 6/9 ⟹ **只平移**（§5.8 全表） |
| **前视偏差影响** | **不适用**：面板 `panel_R011.txt` 的 57 行全为**手写确定性数值**，无时间序列依赖、无 PIT 依赖、无未来信息；本轮**不**构造任何信号或收益 |
| **幸存者偏差影响** | **不适用**：本轮**不构造任何标的池**。`F-LEGACY` 族自身的 `LEGACY_SELECTION_BIASED` 已由 R002/R005 审过，本轮**不重开、不引用其结论** |
| **口径单位** | 探针测量的是 `FactorResearchEngine` 的**引擎级原始 `double`**；生产载荷仍会经 `round4` 二次舍入（属 **R010** 命题面，本轮**只登记**、不判定） |

---

## 11. 本轮**不**做的事（与旧轮边界）

- **不**修改 `ResearchConvention` / 任何冻结口径；**不**改打分权重、因子、质量闸、交易/风控/仓位逻辑。
- **不**改公开 API / 前端契约 / 数据库结构（`V09/V10/V11` 暴露的契约问题**只登记** ⟹ `decision_queue.md`）。
- **不**引入新数据源、**不**启用真实交易、**不**触碰真实凭证与账户。
- **不**重开 R004 的 `Δ_J`、R007 的 HAC/bootstrap、R010 的 `Δbp`；`R007` 已审的
  `neweyWestHacStd` 滞后（`FactorResearchEngine:324`）与 `blockBootstrapCi`（`:358`，调用式 `:191`）
  在 `excluded_forks` 中**显式排除**，本轮**不抽取、不判定、不引用其数值**。
- **不**改 R004/R006/R007/R009/R010 的任何冻结文件；本轮**未推翻**旧轮的任何事实
  （`V11` 的证伪只涉及**本轮**预注册的期望，不构成对旧轮的勘误）。

---

## 12. 附录 A：勘误（**只在此处更正，绝不重写冻结文本**）

### A-1（勘误）：`V11` 的冻结期望被**实测证伪**（`P-9` ❌）

- **冻结文本**（`R011_prereg.md` §4 A4 `V11`、§8 `P-9`）声称：
  「`ScoringBacktestResponse` 与其 `FactorIcSummary` 内部类**是否含任何 group-count 字段** ⟹ **不含**」。
- **实测**：`dto/ScoringBacktestResponse.java` 嵌套类 **`FactorAnalysis`（`:79-92`）** 含
  `:81 private int groupCount;`，且该字段**已被真正接线**——
  `ScoringBacktestService:1024 .groupCount(summary.groupCount())`，
  并经 `:478 .factorAnalysis(toFactorAnalysisDto(factorSummary))` 挂到持久化响应上；
  `:555 objectMapper.readValue(..., ScoringBacktestResponse.class)` 回读即为该结构。
  实测 `response_group_count_fields = 1`、`response_ic_summary_group_count_fields = 0`。
- **判定变化**：`V11` = **`RECOVERABLE`**（而非冻结期望的 `NOT_RECOVERABLE_ABSENT_KEY`）。
- **对杀点的影响**：`K4` 因此**只由 `V03` 支撑**（见 §7.1）；
  **`K4` 仍为真**，总判定**不变**（`REJECTED`）。
- **成因（诚实归因）**：预注册写 P-9/V11 时我只查了 `ScoringBacktestResponse` 的**顶层**与 `FactorIcSummary`，
  **漏看了嵌套类 `FactorAnalysis`**；仪器首次实现也**只找 `put("group_count"`**（D7），
  是**读码复核**才纠正过来。两处都是**我的测量/读码错误**，不是项目行为的变化。
- **未做的事**：**未**修改 `R011_prereg.md` 的任何一个字节；**未**放宽 `K4`。

### A-2（勘误）：`V17` 的冻结期望被**实测证伪**，成因是**词表子串假阳性**（`P-11` ❌）

- **冻结文本**（§4 A7 `V17`、§8 `P-11`）声称：在 note 类自由文本中检索
  `敏感`/`敏感度`/`robust`/`multiverse`/`备选`/`alternative`/`分叉`，**命中 = 0**。
- **实测**：机械命中 **2 处**，均在同一文件：
  - `ScoringBacktestService:654` `v.put("neutralization_pipeline", "winsorize→OLS(lnMV+industry)→robustZ(residual,MAD)")`
  - `ScoringBacktestService:699` `assumptions.put("codex_neutralization", "v2: winsorize → OLS(lnMV+industry dummies) → robust Z on residuals")`
- **诊断**：两处命中的都是 **`robust` 在方法名/流程名里的子串**（`robustZ`、`robust Z on residuals`），
  **不是**关于分叉或敏感性的**声明**。为**判别**（而**不是**为改判定），
  另加一条**补充测量** `extract_sensitivity_terms_strict()`（词边界收紧）：
  **命中 = 0**（`evidence_extra.X02_sensitivity_hits_word_boundary = []`）。
- **判定**：`V17` = **`NOT_VIOLATED`**（而非冻结期望的 `REGISTERED_ONLY`）。
  **注意**：该变体属**非杀死轴 A7**，**不参与任何杀点**，故总判定**不受影响**。
- **未做的事**：**未**改冻结词表、**未**改 `V17` 的判定词（`REGISTERED_ONLY` 仍然只用于「有效声明 = 0」的登记场景）；
  词表改词边界**属**修改已冻结口径，**只建议、不执行** ⟹ `D-R011-02`。

### A-3（澄清）：金丝雀 `CLN`/`INJ` 语义与「**未放宽任何杀死条件**」

- **澄清 1（`CLN`）**：`CLN` 的合格标准被实现为
  「（i）谓词是**所声明键集合**的确定性函数；（ii）塞入**未声明的哨兵键**后结果不变」。
  **不**实现为「谓词必须为真」——后者会把**假设被证伪**误报成**仪器失效**，
  从而把诚实的负结论**系统性**转成 `INCONCLUSIVE`（D9）。
  > **⚠️ 本条已被 `A-4` 裁定推翻（2026-09-15）**：用户取**强语义**，本处描述的是**当时**的实现，
  > **不再是**本轮的合格标准，**也不得**作为下一轮照抄的实现要求。下一轮的 `CLN` 语义见
  > `preregistration_template.md` §10。
- **澄清 2（`INJ`）**：`INJ` 只改**一个**声明键，并要求谓词**翻转**；
  注入值按**实测状态**方向选取（`pred(cln)` 为真则注入违反值、为假则注入满足值），
  以保持「该键被真正约束」的检验力（D10）。
- **澄清 3（`V20` 自指）**：`V20` 的判据含 `canary_pairs`，而它自己那一对也在计数内 ⟹
  必须**分两步执行**（先 V01–V19，回填后单独跑 V20），并以**声明数** `len(C)` 填入、
  同时**断言** `len(C) == CANARY_PAIRS` 与 `len(canaries)` 相等（D11）。
- **最关键的一条**：**本轮未放宽任何杀死条件**。`R011_prereg.md` §6.1（K1–K10）、§6.2（短路）
  与 §4 的全部判定词自 `a8681459` 起**逐字未改**；
  上述三处修改**只涉及仪器**（金丝雀怎么算、注入值怎么取、执行次序怎么排），
  **不涉及**任何 `judge`、任何 `expected`、任何杀点。
  **修复的净效果是让总判定从 `INCONCLUSIVE` 变为 `REJECTED`——对项目更不利，而非更有利。**
  该仪器语义选择已登记 `D-R011-03` 请用户复核。

### A-4（裁定执行）：用户裁定 `CLN` 取**强语义** ⟹ 总判定**回到 `INCONCLUSIVE`**（2026-09-15）

**裁定来源**：`decision_queue.md` 的 `D-R011-03`，用户 2026-09-15 明示：**「允许回到 `INCONCLUSIVE`」**
＝采纳**选项②**（要求回到「`CLN` = **谓词必须为真**」的更强语义）。选项③同时采纳，见本文末「下轮防重犯」。

#### A-4.1 裁定推得的判定链（不是重新测量，是**重新判定**）

| 步 | 内容 |
|---|---|
| 1 | 冻结短路**逐字未改**（§6.2）：`K7\|K8\|K9\|K10` 任一为真 ⟹ **强制** `INCONCLUSIVE`（「本轮断言全部作废」） |
| 2 | `K8` = 仪器门（金丝雀 / 反向锚点 / `G3` / 依赖 / 表外词）。它在运行 1–3 为**真**、运行 4–5 为**假** |
| 3 | 运行 4–5 之所以为假，靠的是 **D9 对 `CLN` 语义的事后修改**（「谓词是所声明键集合的确定性函数」） |
| 4 | 强语义下该修改**不被认可为合法仪器修复** ⟹ `K8` 必须以**未修复**状态读作**真** |
| 5 | ⟹ 按第 1 步，总判定**必须**是 `INCONCLUSIVE`，**H0 未判定** |

#### A-4.2 本裁定的**影响范围**（哪些变、哪些不变）

| 属于 | 项 |
|---|---|
| **变**（仅此两项） | 总判定 `REJECTED` → **`INCONCLUSIVE`**；`h0_alive` `false` → **未判定** |
| **不变** | `K1–K5` 的**全真实测**、`V01–V20` 全部判定、金丝雀 20/20、`out_of_vocab = []`、`grid_mismatch_count = 0`、三次仪器失败的运行史、`run.log` 的 5 次运行、附录 A-1/A-2/A-3 的三条勘误 |
| **零字节变更** | `R011_prereg.md`（自 `a8681459` 起逐字未改）、`experiments/R011_path_declaration_audit/**`（含 `config.json`、`result.json`、`run.log`——**一律未编辑**） |

> **⚠️ 不得误读（三条）**：
> 1. **不得**把本裁定读成「五处反例被推翻了」。`V03`/`V04`/`V07`/`V08`/`V09`/`V18` 的实测**一条未变**，
>    变的只是它们**能否被本轮采纳为可判决结论**。
> 2. **不得**把本裁定读成「项目更干净了」。裁定方向使**总判定从 `REJECTED` 退回 `INCONCLUSIVE`**——
>    形式上从「H0 被证伪」变成「未判定」，但这**不是**对项目的正面结论：它是**「本轮没有合格仪器去下判决」**。
> 3. **不得**据此认为「只要结论对项目不利就可以事后改仪器」。本裁定恰恰相反：**正因为该修改改变了对项目不利的结论、
>    且发生在看到结果之后，才不予认可。**

#### A-4.3 残留风险（**必须留给下一轮，不得口头消化**）

强语义 `CLN`（「谓词必须为真」）有一个**结构性代价**，本轮已如实记录：
**它会把「假设被证伪」误报成「仪器失效」，从而把诚实的负结论系统性转成 `INCONCLUSIVE`**（这正是 D9 当初的动机）。
⟹ 取强语义后，**只要杀点为真，仪器门就会报失效**，审计轮将**无法**给出 `REJECTED`。
**该代价不由本轮消化，也不得据此豁免任何一轮**，而应由**下一轮在预注册里先行声明**仪器语义与合格标准，
在**看到结果之前**定好，从而同时满足「强语义」与「可判决」。该要求已写入 `preregistration_template.md`
**§10 仪器合格标准**（`D-R011-03` 选项③，2026-09-15 落地：`CLN` 强语义与四项必填、`INJ` 按实测状态取向、
测量方法四道预检、事后修改的处置流程）。

#### A-4.4 台账联动（本次裁定一并同步）

| 台账 | 变更 |
|---|---|
| `paper_ledger.csv`（`MENKVELD2024` 行） | `当前状态` `REJECTED` → **`INCONCLUSIVE`**；失败原因分类同步改写 |
| `shadow_registry.md`（R011 段） | 判定同步为 `INCONCLUSIVE` |
| `progress.md` | §1 轮次表 R011 行、§2 论文表 `MENKVELD2024` 行、`REJECTED` 计数 **16 → 15**（`INCONCLUSIVE` **2 → 3**，总数 18 不变）；§9.5 与「R011 收口记」的裁定登记；教训 61 标注为**已被本裁定推翻** |
| `stage_summary_R011_R015.md` §2.2 | 「项目自己的 H0 被证伪（`REJECTED`）」**5 → 4**；`INCONCLUSIVE` **0 → 1** |
| `decision_queue.md` | `D-R011-03` 记为**已裁定**（本文件 A-4 为裁定执行记录） |
| `docs` / 生产代码 / 契约 / DB | **零改动**（本裁定只涉及研究记录） |

---

## 13. 附录 B：台账联动

| 台账 | 变更 |
|---|---|
| `paper_ledger.csv` | 追加 `MENKVELD2024`（13 → **14** 篇） |
| `shadow_registry.md` | 追加 R011 空轮条目（本轮新增影子因子 **0**，无提升请求） |
| `blocked_queue.md` | 新增 `B-R011-01`、`B-R011-02`（22 → **24** 行） |
| `decision_queue.md` | 新增 `D-R011-01`…`D-R011-04`（55 → **59** 行），**全部 `PENDING_USER`** |
| `progress.md` | 更新 §1/§2/§5/§6/§7 与计数（轮次 12、REJECTED 12、实验 11、变体合计 261） |

**负知识（本轮新增）**：
1. 「载荷里有一个同族键」**不等于**「该族的所有分叉都可恢复」——`F-SNAPSHOT` 有 `holding_days` 却无 `group_count`。
2. 「两处常量值相同」**等于**值层一致，**不等于**结构层有共享声明；二者必须分开报告。
3. 「有一个前端输入框的取值范围」**不等于**项目级备选集合——族级/UI 级 ≠ 研究级。
4. 「结构化字段缺失」不能用**自由文本** `note` 补偿（判据 P1）。
5. 词表式文本检索**必须**做子串假阳性检查（`robust` ⊂ `robustZ`），否则**测量学错误会被误读成事实**。
