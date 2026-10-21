# R012 报告：研究载荷声明性文本字段族的『真值 / 覆盖 / 跨块跨面同义』审计

- 预注册：`rounds/R012_prereg.md`，sha256 `3336f070741bea65092eb3dafa0e7a7552d9d8c498b93970ecf57a0c8ec301fb`（**零字节未改**）
- 冻结时基线 commit：`942714fb`
- 判据来源（**仅作判据，不采纳任何结论或数值**）：Hou, Xue, Zhang — *Replicating Anomalies*，NBER WP 23394（May 2017）/ *RFS* 33(5):2019–2133 (2020)，A 档
- 实验装置：`experiments/R012_declaration_truth_coverage_audit/`（`make_config.py` / `config.json` / `panel_R012.txt` / `run_R012_declaration_audit.py` / `probe/R012Probe.java`）
- 生产代码改动：**零**（§7 逐项证据）

---

## 0. 一句话结论

冻结的 H0 三条子句里，**(i)「真」未被证伪**，**(ii)「全」与 (iii)「一」被证伪**：

| 子句 | 判定 | 决定性证据 |
|---|---|---|
| **(i) 真**：声明性文本字段的可证伪主张与实现一致 | **未被证伪** | 24/24 文本字段 `TRUE_CLAIM`；A2 轴 22 条主张中 21 条 `TRUE_CLAIM` + 1 条**记法类** `UNFALSIFIABLE`；**零**条进入 K1 的 `FALSE_CLAIM` |
| **(ii) 全**：每个承载数值结果的块都有样本域 + 口径声明 | **被证伪** | 14 块中 **3 个**（`B12`/`B13`/`B14`）在「自身 ∪ 祖先 ∪ 直接子」口径下**无**样本域声明；三者**都是**承载数值结果的块 ⟹ **K2 触发** |
| **(iii) 一**：同一语义跨块跨面不矛盾 | **被证伪** | 同一次运行、**同一个** `FactorResearchSummary`：`toFactorAnalysisDto` 写出 `.universeNote(...)`（`B11` 有），`toFactorIcSummaryDto` 构造的 DTO **无任何** universe 字段（`B12`，并被 `B14` 复用）⟹ **K3 触发** |

短路（冻结 §9.2，**先算仪器门**）：

```
K = {K1: false, K2: true, K3: true, K4: false, K5: false, K6: false, K7: false}
K5|K6|K7 = false  →  不进 INCONCLUSIVE 分支
K1|K2|K3|K4 = true  →  total_verdict = REJECTED，h0_alive = False
```

仪器门**全部通过**：金丝雀 22/22（`CLN`/`INJ` 全过）、表外词 `[]`、正面对照 `V20` `TRUE_CLAIM`、反向锚点 `V21` `TRUE_CLAIM`、`G3` 门通过、两次运行**逐位相等**、`P` 表 15/16。

> **本轮结论的边界（必读）**：被杀死的是「项目自己的**声明完备性**假设」（H0），**不是**任何关于策略收益、因子有效性、IC 显著性的主张。本轮**不产生、不比较、不引用**任何收益/因子数值，全部证据是**源码文本与只读探针输出**。因此「REJECTED」的读法是「载荷的样本域声明不完备」，而**不是**「策略无效」。

本轮**零生产代码改动**；产出为 **2 个登记项** `D-R012-01`/`D-R012-02` + **1 个阻塞项** `B-R012-01`（§8 台账联动）。

---

## 1. 口径、单元与边界

### 1.1 被审计对象（`subject`）

`subject = 研究载荷声明性文本字段族的『真值 / 覆盖 / 跨块跨面同义』审计`
`subject_kind = 只读审计（论文只作判据来源，不采纳任何结论/数值）`

审计对象是**项目自己的契约主张**，不是论文。HXZ2020 只提供四条判据（J-1..J-4），其中 J-4 明确标 **X（不可移植）**，且论文的**全部数值**进入禁用清单（§1.3），本轮不得出现。

### 1.2 H0 的边界（`R012_prereg.md` §1 **强制复述**）

- H0 只谈**声明性文本字段**与**实现**的关系，不谈数值正确性、不谈策略表现、不谈数据质量。
- 「真」的判定对象是**可证伪主张**（§6.1 四类 F1–F4）；**规范/记法类**文本（§6.2）判 `UNFALSIFIABLE`，**不计入**「假声明」。
- 「全」的判定口径是**自身 ∪ 祖先 ∪ 直接子块**（§4.3），不是「自身字段存在」——单块自证不算覆盖。
- 「一」的判定要求**同面同层**参照块 + **无正当作用域差异**；有正当作用域差异者判 `CONSISTENT_DISTINCT_SOURCES`（`V18` 即此类）。
- H0 **不**包含「载荷端到端形状」（需 Spring 容器 + DB）⟹ 登记 `B-R012-01`，且**不得**被读成「缺陷不存在」。

### 1.3 判据来源与禁用数值

| 项 | 值 |
|---|---|
| 载体 PDF | `papers/hxz2020_rfs_replicating_anomalies.pdf`，821,858 B，sha256 `8d00169a4cf6b4895587b054b41fe934b600ea501f210f02433cfe4db316f163` |
| 载体 TXT | 401,306 字符 / 418,945 B，sha256 `8ab941bf1f37b287d5bfa35526e1ece551bb9432fefa71c7dd538fd08164e2d8`，130 页 |
| J-1 | 样本域是第一位口径选择；不同样本域下定性结论翻转（L） |
| J-2 | 口径选择必须逐项显式声明（L） |
| J-3 | 未声明的关键选择 ⟹ 结果不可重建（L） |
| J-4 | 论文样本（美国、1967–2014）**不可移植**（X） |
| 禁用数值 | `t>=1.96`、`t>=3`、`0.64`、`0.85`、`0.0328`、`1.32`、`1.03`、`20th percentile`、`447`、`452`、`57`、`68`、`38`、`79`、`103`、`102` |

禁数自检（§11.5 / R011 教训 64）：全量扫描 `config.json` 与执行器产物，**排除** `judgment_source` 子树与执行器**自身 denylist 字面量**后，命中 = **0**（`forbidden_scan.numeric_hits = []`）。

### 1.4 判定单元与冻结自检

| 项 | 冻结值 | 实测 | 通过 |
|---|---|---|---|
| 块清单 `BLOCK_COUNT` | 14 | 14（锚点 mismatch = **0**） | ✅ |
| 文本字段 `TEXT_FIELD_COUNT` | 24 | 24（锚点缺失 = **0**） | ✅ |
| 变体 `MAX_VARIANTS` / `CANARY_PAIRS` | 22 / 22 | 22 / 22 | ✅ |
| `row_sum` / `axis_total` / `grid_mismatch_count` | 22 / 22 / 0 | 22 / 22 / 0 | ✅ |
| 预注册 sha256 | `3336f070…01fb` | 逐字节相同 | ✅ |

---

## 2. 机器化事实

### 2.1 静态侧（源码文本，机械抽取）

冻结扫描根 §3 R1–R6：`FactorResearchService.java`、`ScoringBacktestService.java`、`ScoringBacktestResponse.java`、`FactorResearchEngine.java`、`modules/web/src/api/factor.ts`、`FactorResearch.vue`。
**明确排除**：生产数据库、真实交易接口、`modules/web/src/api/backtest.ts`（后者仅作**旁证**，见 `A-04`）。

14 块（`B01`–`B14`，含 `load_bearing` 标记）与 24 条文本（`T01`–`T24`）的全部锚点均命中，逐块键集、逐条锚点、双读数结果见 `result.json`（`blocks` / `text_fields` / `sensitivity_reading`）。

### 2.2 运行时侧（Java 探针直接调用生产 `FactorResearchEngine`）

`probe/R012Probe.java` 编译进 `modules/backend-java/target/classes`（G3 门：`g3_code_source` 以 `modules/backend-java/target/classes` 结尾 ⟹ `g3_pass = true`），运行**两次**，输出逐位相等（`det_probe_bytes_equal = true`），运行结束**清理字节码**（`PROBE_CLEANUP=true`，实测删除 `R012Probe.class` + `R012Probe$PanelSpec.class`）。

| 场景 | 面板 | `cross_section_days` | `grouped_len` | `monotonicity` | `hac_std`（十进制 / 十六进制） | `ci_lower` | `ci_upper` |
|---|---|---|---|---|---|---|---|
| `PA` | 3 天 12/10/9 非退化 | 3 | 5 | `NON_MONOTONIC` | `0.036239303374449736` / `0x1.28df53da56b46p-5` | `0.25` | `0.44242424242424244` |
| `PB` | 空 | 0 | 0 | `N/A` | `0.0` / `0x0.0p0` | `0.0` | `0.0` |
| `PC` | 平坦（`0.01`×2 天） | 0 | 0 | `N/A` | `0.0` / `0x0.0p0` | `0.0` | `0.0` |

- **正面对照 `V20`**：`universe_note_input` → `engine_note` 逐字符透传（`passthrough_ok = true`），证明 T01 类常量确实**到达**载荷，而非只写在源码里。
- **反向锚点 `V21`**：`hac_std` / `ci_lower` / `ci_upper` 由**同一条** IC 序列产出（`ENG:170-192` 的 `icSeries` → `neweyWestHacStd` + `blockBootstrapCi`），三者有限且 `ci_lower ≤ ci_upper` ⟹ 仪器能产生可判定的数值。
- `PC` 平坦序列落 `N/A`（而非 `MONOTONIC_INCREASING`）与 `R006` 登记的形状不同，原因见 `A-06`（**只作仪器读数锚点，不参与任何判定**）。

---

## 3. H0 三条子句逐条判定

### 3.1 (i)「真」— 24 条文本字段逐项真值表

判定链：文本字段 → 可证伪主张（`V05`–`V10`，共 22 条）→ 代码证据 → 判定词。

| 字段 | 归属块 | 文本键 | 判定来源 | 判定词 |
|---|---|---|---|---|
| `T01` | `B01`/`B02`/`B07` | `RANK_IC_UNIVERSE_NOTE` | `V07-a` | `TRUE_CLAIM` |
| `T02` | `B07` | `RANK_IC_BASIS_NOTE` | `V05-a`/`-b` | `TRUE_CLAIM` |
| `T03` | `B08`/`B09` | `RANK_IC_NET_BASIS_NOTE` | `V06-a..e` | `TRUE_CLAIM`（含 1 条 `UNFALSIFIABLE` 记法子句） |
| `T04` | `B07` | `RANK_IC_EMBARGO_NOTE` | `V08-a..c` | `TRUE_CLAIM` |
| `T05` | `B04` | `SNAPSHOT_UNIVERSE_NOTE` | `V07-b`/`-c` | `TRUE_CLAIM` |
| `T06` | `B01`/`B02`/`B07` | `FEATURE_SNAPSHOT_NOTE` | 锚点 | `TRUE_CLAIM` |
| `T07` | `B04`(RAW) | `rawNote` | `V10-a`/`-b` | `TRUE_CLAIM` |
| `T08` | `B04`(COST_APPROX) | `costNote` | `V10-c`/`-d` | `TRUE_CLAIM` |
| `T09` | `B04`/`B05`(EXECUTABLE) | `execNote` | `V10-e..g` | `TRUE_CLAIM` |
| `T10`/`T11`/`T12` | `B01`/`B02`/`B03` | 顶层 `note` | 锚点 | `TRUE_CLAIM` |
| `T13` | `B04` | `note`（0 观测退化） | 条件守卫 | `TRUE_CLAIM` |
| `T14` | `B05` | `note`（= `basisNote`） | 等值 | `TRUE_CLAIM` |
| `T15` | `B06` | `note` | 锚点 | `TRUE_CLAIM` |
| `T16`/`T17` | `B08`/`B09` | `note`（「与毛收益 IC 窗口差一日」） | `V09-a`/`-b` | `TRUE_CLAIM` |
| `T18`/`T19` | `B10` | `tradable_note` / `note` | 锚点 | `TRUE_CLAIM` |
| `T20` | `B06` | `tradable_note`（二分支） | 条件守卫 | `TRUE_CLAIM` |
| `T21` | `B13`/`B12`/`B14` | `FACTOR_UNIVERSE_NOTE` | 锚点 | `TRUE_CLAIM` |
| `T22` | `B13` | `metric_scope` = `FILLED_SAMPLE` | 锚点 | `TRUE_CLAIM` |
| `T23` | `B14` | 调用处 note 构造（`SBS:448`） | 锚点 | `TRUE_CLAIM` |
| `T24` | 全 | 类 Javadoc scope 词表 | 词表 | `TRUE_CLAIM` |

**A2 轴 22 条主张的分布**：`TRUE_CLAIM` 21 + `UNFALSIFIABLE` 1 + `FALSE_CLAIM` **0**。

**唯一的 `UNFALSIFIABLE`（`V06-e`）及其理由**（必答项，不得算作假声明）：
`T03` 把成本记法写作**单级乘性**「`×(1+佣金+滑点)`」，而实现是**两级乘性**（`entryPrice = rawOpen×(1+滑点)`，再 `×(1+佣金)`，见 `FRS:360-362`）。两者在**一阶**意义上是同一记账选择的分式写法差异，**不构成**关于样本域/口径/时点/成本是否计入的可证伪主张，属 §6.2 **记法类** ⟹ `UNFALSIFIABLE`。

**几条关键的真主张（点名）**：
- `T16`/`T17`「与毛收益 IC **窗口差一日**」**为真**：`FRS:692-694` `grossExitIdx = baseIdx + holdingDays` vs `netEntryIdx = baseIdx + 1`、`netExitIdx = netEntryIdx + holdingDays`。
- `T03` 的四件事**全真**：raw 成交价（`rawOpen`/`rawClose`）、缺 raw 即跳过该观测（`FRS:718-737` 守卫在前、`netObs.add` 在后）、扣费（佣金/印花税/滑点）、**无**涨跌停/停牌拒单过滤（净收益路径无任何可成交性过滤）。
- `T04` 的两条**全真**：信号日近末端自动跳过（`FRS:707` 边界守卫）、显著性以 `neweyWestHacStd` + `blockBootstrapCi` 为准（`ENG:170-192`）。
- `T01`/`T05` 两条样本域声明**各自为真且描述不同数据路径** ⟹ `CONSISTENT_DISTINCT_SOURCES`（`V18`），**不是**矛盾。

### 3.2 (ii)「全」— 覆盖完备性

**构造规则（谁算「承载数值结果的块」）**：按 §4.1 冻结列 `load_bearing`，判据是「该块是否把数值结果送进载荷」。
14 块中 `load_bearing = 是` 的 **13** 块：`B01 B02 B03 B04 B06 B07 B08 B09 B10 B11 B12 B13 B14`；`B05`（口径不可用退化块）**否**。

**覆盖口径**：`自身键 ∪ 祖先 ∪ 直接子块`。

`V11`（样本域声明）实测缺失清单 = **`['B12','B13','B14']`**，三者**全部** `load_bearing = 是` ⟹ **K2 触发**。
`V15` 单独复核 `B12`/`B14`/`B13`/`B11`：前三者 `COVERAGE_MISSING_UNIVERSE`，`B11` `COVERAGE_OK`。

逐块「仅自身字段」口径（`V13`）缺失 = `['B12','B13','B14']`，**描述性，不作 kill 依据**。

**口径声明覆盖**（`V12`，非 kill 轴）= `COVERAGE_MISSING_SCOPE`，缺失 = `['B11','B12']`。

对照 `P-12`：冻结预测「缺失清单非空（**≥5 块**）」，实测 **3 块** ⟹ **预测未中**，进附录 `A-05`（预测表勘误，**不**影响 K2 判定——K2 只要求「≥1 个承载数值结果的块缺失」）。

### 3.3 (iii)「一」— 跨块跨面一致性

| 语义 | 出现面 | 状态 |
|---|---|---|
| `universeNote`（样本域声明） | `FactorAnalysis`（`B11`，`RESP:79-91`） | **有**，且 `SBS:1047` `.universeNote(summary.universeNote())` 写出 |
| `universeNote`（同语义） | `FactorIcSummary`（`B12`，`RESP:64-72`），被 `B14` 复用 | **无**任何 universe 类字段；`SBS:1051-1065` `toFactorIcSummaryDto` 只映射 8 个数值字段 |

**为何构成 `INCONSISTENT` 而非 `NOT_APPLICABLE`**：
1. 两处产自**同一次运行、同一个** `FactorResearchSummary` 对象——同一次 `summarize(...)` 调用返回的 `summary`（`SBS:431-437` 传入 `FACTOR_UNIVERSE_NOTE`），随后分派给两个映射器（`SBS:439` → `toFactorIcSummaryDto`，`SBS:475` → `toFactorAnalysisDto`）。
2. 因此两面的**样本域完全相同**，**不存在**正当作用域差异——不是「一个面不适用该语义」，而是「同一语义在一面存在、在另一面缺失」。
3. 冻结 §6.3 明文把「一方存在、另一方缺失且无正当作用域差异」列入 `INCONSISTENT` 的定义 ⟹ 判定 `INCONSISTENT`（`V16`），**K3 触发**。
4. 旁证（**冻结扫描根之外**，仅登记不判定，见 `A-04`）：前端 `ScoringBacktest.vue:366` 只渲染 `factorAnalysis.universeNote`，`FactorIcSummary` 面无字段可渲染。

**不构成矛盾的两组（点名，避免误读）**：
- `V17` **`SCOPE_VOCAB_UNRECONCILED`**：`metric_scope` 取值域 = `{RAW, COST_APPROX, EXECUTABLE, FILLED_SAMPLE, UNAVAILABLE}`（5 值），而 `T24` 词表只列 **3** 值 ⟹ 不可调和的是**词表**（`FILLED_SAMPLE`/`UNAVAILABLE` 未枚举）。**注册项 `D-R012-01`，不作 kill。**
- `V18` **`CONSISTENT_DISTINCT_SOURCES`**：`T01`（`sparse selector_result 取样`）与 `T21`（`persisted 全截面候选`）**各自为真**且**描述不同数据路径** ⟹ 不矛盾。
- `V19` **`CONSISTENT`**：`B01`/`B02`/`B03` 三个顶层 note 与各自块内 `metric_scope` 无自相矛盾（`contradictions = []`）。

### 3.4 短路路径（逐条）

```
K1 = false   无进入 K1 的 A2 FALSE_CLAIM（V07-c 因 §12.2 降级为描述性，见 A-02）
K2 = true    B12/B13/B14 承载数值结果却无样本域声明
K3 = true    V16 = INCONSISTENT（同面同层、无正当作用域差异）
K4 = false   块 14 = 冻结 14；T01–T24 无 CLAIM_ABSENT
K5 = false   row_sum = axis_total = 22；grid_mismatch = 0；BLOCK/TEXT 计数相符
K6 = false   金丝雀 22/22、表外词 []、V20/V21 通过、G3 通过、instrument_alerts []
K7 = false   预注册逐字节未改；两次运行逐位相等
⇒ (K5|K6|K7) = false，(K1|K2|K3|K4) = true ⇒ total_verdict = REJECTED，h0_alive = False
```

---

## 4. 报告必答问题（`R012_review.md` §9 的 13 问，逐条）

**问 1｜H0 三子句逐条判定 + 证据位置 + 总判定与短路路径** — 见 §3（`3.1`/`3.2`/`3.3`/`3.4`）。每条子句都给了代码行号级证据；短路路径按冻结顺序先判仪器门。

**问 2｜每条声明性文本字段的逐项真值表（含 `UNFALSIFIABLE` 及其理由）** — 见 §3.1 的 24 行表。唯一的 `UNFALSIFIABLE` 是 `V06-e`（成本记法的单级/两级乘性写法差异：`T03` 写 `×(1+佣金+滑点)`，实现 `FRS:360-362` 为两级乘性），属**记法类**，**不计入**「假声明」。24/24 字段最终判 `TRUE_CLAIM`，其中 `T03` 的 `TRUE_CLAIM` 由「全真 ∨ 记法类」的 rollup 得到。

**问 3｜块清单的构造规则与逐块声明覆盖矩阵** — 见 §3.2。构造规则 = §4.1 冻结的 `load_bearing` 列（「该块是否把数值结果送进载荷」），13/14 块承重；覆盖 = 自身 ∪ 祖先 ∪ 直接子。逐块矩阵在 `result.json` 的 `variants[V11].detail.cells` 与 `variants[V12].detail.cells`。

**问 4｜同名语义字段的对照表（含 `FactorIcSummary` 缺 `universeNote`）与「一面缺失」为何是 `INCONSISTENT`** — 见 §3.3 的四点论证。核心：同一 `summary` 对象、两映射器、一面写出一面丢弃，无正当作用域差异。

**问 5｜N = ？轴线数、`row_sum`、`axis_total` 自检** — `N = 22`；轴计数 `A1=4, A2=6, A3=5, A4=4, A5=3`；`row_sum = 22 = axis_total = MAX_VARIANTS = 22`；`grid_mismatch_count = 0`；金丝雀对数 `22 = CANARY_PAIRS`。全部在运行时通过（`K5 = false`）。

**问 6｜±20% 敏感性（无参数网格 ⟹ 等价替代：三种读数）** — 本轮**无**参数网格，故按冻结 §12 做**等价替代**，同一批主张三种读数各测一次：

| 读数 | 定义 | 结果 |
|---|---|---|
| (a) 朴素子串 | 块区间内小写无关的 `universe` 子串 / `\bnote\b` 计数 | 子串 `note` 命中 9 处 |
| (b) **按块 kind 收紧** | `map` 块 = 带引号键声明 `.put("…universe…"`；`dto` 块 = 字段声明 `private <T> universeXxx;`；`code` 块 = 真实传递语句（setter/常量引用） | 键声明 26 处；**14 块的命中结论与 (a) 完全一致**（`blocks_where_reading_matters = []`，`reading_stable = true`） |
| (c) 人工逐字 | 直接读源码行核对冻结字面量 | 已核对 `FRS:72-74`（T01）、`FRS:78`（T02）、`FRS:83`（T03）、`SBS:60-61`（T21）、`SBS:1051-1065`（B12 映射器无 universe 字段）、`RESP:79-91`/`RESP:64-72` |

**是否有判定被读数方式改变？** 有**一处**，且已按 §12.2 **降级为描述性**：`V07-c`（`T05`「forward return 只读 `market_price_raw`/`label_v2`，不读 `selector`/`qfq`」）在 (a) 下判 `FALSE_CLAIM`、在 (b) 下判 `TRUE_CLAIM` ⟹ `reading_dependent = true` ⟹ **不得驱动 K1**。成因与处置见 `A-02`。除该条外，无判定随读数改变。

**问 7｜`研究口径规范.md` §5 的前视/幸存者偏差影响** — **不适用**：本轮不产生任何收益/因子数值，也不测量偏差大小。按要求**逐字引用**被审计载荷自身对偏差的声明（**只引用，不测量**）：
- `FRS:72-74`（`T01`）：「`LEGACY_SELECTION_BIASED：sparse selector_result 取样（非完整 PIT 截面），存在选择/幸存者偏差；完整截面 IC 请走 snapshot-research（V2-MP-022，输入 snapshotId 读取 MP-016 feature snapshot）`」
- `SBS:60-61`（`T21`）：「`基于 persisted 全截面候选（通过质量闸的全部条目，非 TopN 子集）；未复现历史时点股票池，存在幸存者偏差`」

两者经 `V18` 判为 `CONSISTENT_DISTINCT_SOURCES`（各自为真、描述不同路径）。§5 中「前复权会被后续除权改写」「`stock_info` 非 PIT」「信号日稀疏」三项限制本轮**未测量**。

**问 8｜运行史（`run.log` append-only，保留全部运行含失败）与失败归类** — 共 **8** 次运行，全部保留在 `run.log`；逐次归因见 §6 的 `A-01`..`A-06` 与 §6 末表。**6 次失败全部是仪器缺陷**（子串假阳性、Text 锚点缺失、读数方式实现错、V04 越界窗口、P 表编码不忠实），**0 次**是「假设被证伪」。

**问 9｜偏离与勘误逐条进附录 `A-xx`，绝不改写冻结预注册** — 见 §6（`A-01`..`A-06`）。预注册 sha256 运行时逐字节核验，`frozen_intact = true`。

**问 10｜不得出现关于「项目策略是否赚钱 / 因子是否有效」的表述** — 本轮**未出现**此类表述。全部判定词限定在「声明 / 覆盖 / 一致性」三类结构属性上；`REJECTED` 明确界定为「声明完备性假设被杀死」（§0 与 §5.10）。

**问 11｜未覆盖/阻塞** — `B-R012-01`（BLOCKED）：**载荷端到端形状**的观测需 Spring 容器 + DB + MP-016 snapshot，本轮环境不可端到端观测 ⟹ 所有 `CLAIM_UNDECIDABLE_STATIC` 型主张（若有）与端到端形状**未覆盖**。**此项不得被读成「缺陷不存在」**：本轮 3 个缺失块是**静态可判**的，`B-R012-01` 覆盖的是**另外一块**未观测范围。另：`V22` 的 `canary` 变体在静态-only 模式下判 `NOT_REACHED_STATIC_ONLY`（本轮完整运行已执行，无此状态残留）。

**问 12｜五份台账逐份说明** — 见 §8。

**问 13｜零生产代码改动的逐项证据** — 见 §7。

---

## 5. P 表核对（`R012_prereg.md` §10，逐条）

**结果：15 / 16 通过，不符 1 条（`P-12`）。** 「预期全中不是能力证据」（冻结纪律）；`P-5`/`P-14` 是**对项目有利**的预期，**未**被弱化。

| P | 冻结预期 | 实测 | 通过 |
|---|---|---|---|
| `P-1` | 块 14、锚点 mismatch 0 | 14 / 0 | ✅ |
| `P-2` | `B07` 两分支键集差集非空 | 非空 | ✅ |
| `P-3` | `B08`/`B09` 差集 = `{monotonicity}` | `{monotonicity}` | ✅ |
| `P-4` | `B12` 无 universe 类字段；`B11` 有 `universeNote` | 扫描根内 4/4 事实为真（`B12` 两条 `absent` 全中、`B11` 字段+接线 `present` 全中） | ✅ |
| `P-5` | `T03` 四件事全真；记法判 `UNFALSIFIABLE` | `V06-a..d` = `TRUE_CLAIM`、`V06-e` = `UNFALSIFIABLE`、`T03` = `TRUE_CLAIM` | ✅ |
| `P-6` | `T04` 两条主张全真 | `V08-a..c` 全真、`T04` = `TRUE_CLAIM` | ✅ |
| `P-7` | `T16`/`T17` 窗口差一日为真 | 两条 `TRUE_CLAIM` | ✅ |
| `P-8` | `V15` → `COVERAGE_MISSING_UNIVERSE` ⟹ K2 触发 | `COVERAGE_MISSING_UNIVERSE` | ✅ |
| `P-9` | `V16` → `INCONSISTENT` ⟹ K3 触发 | `INCONSISTENT` | ✅ |
| `P-10` | `V17` → `SCOPE_VOCAB_UNRECONCILED`（注册项） | 同 | ✅ |
| `P-11` | `V18` → `CONSISTENT_DISTINCT_SOURCES` | 同 | ✅ |
| `P-12` | **`V13` 缺失清单非空（≥5 块）** | **非空，但只有 3 块**（`B12`/`B13`/`B14`） | ❌ |
| `P-13` | `total_verdict = REJECTED`、`h0_alive = false` | 同 | ✅ |
| `P-14` | `K1` 不触发（`FALSE_CLAIM` 计 0） | 计 0 | ✅ |
| `P-15` | 表外词 `[]`；两次运行逐位相等 | `[]` / `static_equal = true` / `probe_bytes_equal = true` | ✅ |
| `P-16` | `T24` 词表不含 `FILLED_SAMPLE` ⟹ 与 `T21`/`T22` 不可调和 | `unreconciled = ['FILLED_SAMPLE','UNAVAILABLE']`、`T24` = `TRUE_CLAIM` | ✅ |

`P-12` 的处置见 `A-05`：**预测表勘误**，不影响 `K2`（K2 只要求「≥1 个承载数值结果的块缺失」）。**未**因不符而修改冻结文本，**未**放宽任何 kill 条件。

---

## 6. 附录：偏离与勘误（`A-01`..`A-06`）

### `A-01` `T14` 锚点缺失（仪器校准）
- **现象**：首轮运行 `文本抽取：24 条；锚点缺失 = ['T14']`。
- **成因**：冻结 `anchors` 写成散文「`note = basisNote`」而非字面串，机械匹配无法命中实际代码 `block.put("note", basisNote)`。
- **处置**：把锚点改为字面串（**只改仪器参数**；冻结的 `TEXT_FIELD_COUNT = 24`、`T14` 的语义、K 表、判定词表**均未改**）。校准后 `锚点缺失 = []`。
- **归类**：**仪器缺陷**（不是假设被证伪）。

### `A-02` `V07-c` 子串假阳性 ⟹ §12.2 降级（本轮最重要的仪器修复）
- **现象**：首轮 `A2 FALSE_CLAIM = ['V07-c']` ⟹ `K1 = true`。
- **成因**：谓词用朴素子串 `qfq|Qfq|QFQ` 在 `FRS:257-460` 上测「不读 qfq」，命中了 `FRS:311` 的**否定注释**「不读 qfq/MarketData」——把项目**声明「不读 qfq」这句话本身**当成了「读了 qfq」的证据。这是 R011 教训 65（子串假阳性须先做检查）的教科书式复发。
- **处置（按冻结纪律，未改判定词、未改 K 表）**：
  1. 精确读数改为**标识符级** `loadPriceCache|marketDataRepository|qfqClose|qfqOpen|QfqPrice`；
  2. 新增冻结的 `READING_NAIVE` 双读数表，`eval_claim` 同时产出 `naive_verdict`；
  3. 冻结 §12.2 规定「判定被读数方式改变者必须降级为描述性，**不得**驱动 K1」⟹ `V07-c` 标记 `reading_dependent = true`，从 **K1 的候选集中排除**（`kill_evidence.A2_reading_dependent_claims = ['V07-c']`）。
- **后果（如实披露）**：**K1 由此变为 `false`**。这不是「因为算不过就把 K1 关掉」——K2/K3 独立成立且**未**受此修复影响（首轮与末轮 `K2`/`K3` 均为 `true`），总判定仍为 `REJECTED`，短路走的是**同一支**。若把 `V07-c` 计回 K1，`total_verdict` 不变。

### `A-03` 读数敏感性按块 kind 实现错误（仪器缺陷）
- **现象**：前几轮 `读数敏感性：stable=False`、`blocks_where_reading_matters = ['B11','B14']`。
- **成因**：把「词边界收紧读数」实现成「必须带引号的键声明」，对 `dto` 块（`private String universeNote;`）与 `code` 块（`FACTOR_UNIVERSE_NOTE` 常量引用）无效；其中 `B14` 的差异纯粹是我把朴素读数写成大小写敏感所致。
- **处置**：收紧读数**按块 kind 分派**（`map`/`dto`/`code` 各自模式），朴素读数改大小写无关。结果 `blocks_where_reading_matters = []`、`reading_stable = true`。
- **归类**：**仪器缺陷**。

### `A-04` `V04` 使用冻结扫描根之外的文件（越界窗口）
- **现象**：某轮 `V04` 判 `FALSE_CLAIM`，而预期 `TRUE_CLAIM`。
- **成因**：`V04` 的 `absent(TSB, 62, 70, ...)` 窗口（`modules/web/src/api/backtest.ts`）**跨过了两个 interface 的边界**——`universeNote` 在 `FactorAnalysis` 的 `:64`，而 `FactorIcSummary` 实际在 `:67-77`，窗口 62–70 把邻块字段算作 `FactorIcSummary` 的字段 ⟹ 假阳性。**且该文件根本不在冻结扫描根 §3 R1–R6 内**。
- **处置**：① 判定只用冻结扫描根内事实（`RESP` + `SBS`）；② `backtest.ts` 的两条事实修正窗口（`52-65` / `67-77`）后降级为 `corroboration`（**旁证，不参与判定**）；③ 新增 `instrument_alerts` 通道：旁证失败只登记、并作为 **K6 触发条件**，**绝不**驱动任何变体的判定词。
- **归类**：**仪器缺陷**（且暴露了一条纪律缺口，已用 (2)(3) 补上）。

### `A-05` `P-12` 预测未中（预测表勘误）
- **现象**：冻结 `P-12` 预测「`V13` 缺失清单非空（**≥5 块**）」，实测 **3 块**（`B12`/`B13`/`B14`）。
- **处置**：**改写预注册？不。** 勘误只写在本附录；`P-12` 的**实质部分**（「缺失清单非空」）成立，**量级部分（≥5）预测错误**。`P-12` 不参与任何 K 判定（K2 的门槛是「≥1 个承载数值结果的块缺失」），故**不影响**总判定。
- **归类**：**预测失误**（既非仪器缺陷也非假设被证伪——它测的是「我猜有几个块」，不是 H0 本身）。

### `A-06` `PC` 平坦序列落 `N/A` 而非 `MONOTONIC_INCREASING`
- **现象**：`R006` 登记过「平坦序列被判 `MONOTONIC_INCREASING`」，本轮 `PC` 实测 `cross_section_days = 0`、`monotonicity = N/A`。
- **成因**：`PC` 的 2 天面板因**收益全等**（方差为 0）无法形成有效截面 IC，日截面计数为 0，故走 `N/A` 分支，**未**到达 `R006` 那条单调性判定路径。
- **处置**：`PC` 在本轮**只作仪器读数锚点**（证明探针能区分退化与空面板），**不参与任何判定**；`R006` 的登记**不**被本轮推翻或被引用为反证。差异原因见上方成因（未复现 R006 面板形状，而非 R006 结论有误）。
- **归类**：**面板设计差异**（描述性登记）。

### 运行史汇总（`run.log` append-only，共 8 次，全部保留）

| 次序 | 模式 | 失败/异常 | 归类 |
|---|---|---|---|
| 1 | `--static-only` | `T14` 锚点缺失；`K1 = true`（`V07-c` 子串假阳性）；`stable = False` | 仪器（`A-01`/`A-02`/`A-03`） |
| 2 | `--static-only` | 同上（修正进行中） | 仪器 |
| 3 | `--static-only` | `K1 = false`；`stable = False`（`B14` 大小写假差异） | 仪器（`A-03`） |
| 4 | `--static-only` | `K6 = true`（static-only 下探针缺失，**按设计**）| **非缺陷**（仪器门按设计拒绝下结论） |
| 5 | 完整 | `V04` 越界窗口 `FALSE_CLAIM` | 仪器（`A-04`） |
| 6 | 完整 | `P-4`/`P-12` 不符（`P-4` 为编码不忠实） | 仪器（`A-04`/`A-05`） |
| 7 | 完整 | `P-12` 编码不忠实（丢失「≥5」量级） | 仪器（`A-05`） |
| 8 | 完整 | **无**（`K1..K7 = {F,T,T,F,F,F,F}`、`REJECTED`） | 收口 |

**6 次失败 = 仪器缺陷；0 次 = 假设被证伪。**第 4 次的 `K6 = true` 是 `--static-only` 下仪器门「拿不到运行时证据就拒绝下结论」的**设计行为**，**不是**缺陷，也**不**参与最终判定。

---

## 7. 零生产代码改动：逐项证据

| 证据 | 结果 |
|---|---|
| `git status --porcelain`（**收口前实测**） | **6** 项，**全部**落在 `research/paper_loop/**` 内：`?? experiments/R012_declaration_truth_coverage_audit/`、`?? rounds/R012_report.md`，以及 **4** 个台账文件的 `M`（`blocked_queue.md` / `decision_queue.md` / `paper_ledger.csv` / `progress.md`）。**无任何** `modules/`、`docs/`、`scripts/` 下文件的 `M`/`A`/`D` |
| 探针产物清理 | 运行结束删除 `modules/backend-java/target/classes/com/quant/service/backtest/R012Probe.class` 与 `R012Probe$PanelSpec.class`（`PROBE_CLEANUP = true`），实测**已删除** |
| 两次运行的原始输出 | `_scratch/p1.json` 与 `_scratch/p2.json`（各 2,839 B）**随装置一并入库**，它们是 `determinism.probe_bytes_equal = true` 的**逐字节原始证据**，可由任意第三方直接 `cmp` / 比对 sha256 复核（该目录由执行器每次运行重写） |
| `git diff --check` | **空**（无空白错误、无冲突标记） |
| 密钥/凭证扫描 | 本轮不读取、不生成、不写入任何交易凭证；装置内无 token/账号/连接串（`grep` 无命中） |
| 大文件 | 未新增 PDF/TXT 大文件；论文载体沿用 `papers/` 既有文件（仅核 sha256，未修改） |
| 冻结文件 | `R001`–`R011` 全部冻结文件本次**零字节变更**；`R012_prereg.md` 运行时 sha256 核验 `frozen_intact = true` |
| `record-only` 清单（§7） | 本轮**未**改变默认策略、**未**改打分权重/因子口径/质量闸阈值、**未**改交易/风控/仓位逻辑、**未**改 `ResearchConvention`、**未**改公开 API/前端契约/数据库结构、**未**做影子→正式提升、**未**引入新数据源、**未**启用真实交易 |

---

## 8. 台账联动（五份逐份）

| 台账 | 改了什么 |
|---|---|
| `paper_ledger.csv` | 追加 R012 一行：论文 HXZ2020、A 档、载体 sha256、`total_verdict = REJECTED`、杀死的是 **H0（声明完备性）**、判据 J-1..J-4、禁用数值清单、论文**未被**采纳任何结论/数值 |
| `shadow_registry.md` | **不改**。理由：本轮**不提出任何新因子**，不走影子流程；H0 是审计假设，不是可交易假设 |
| `decision_queue.md` | 新增两项（**只记录、不自动执行**，均为 §7 `record-only` tier）：<br>`D-R012-01` — `metric_scope` 取值域 5 值 vs `T24` 词表 3 值（`FILLED_SAMPLE`/`UNAVAILABLE` 未枚举）⟹ `SCOPE_VOCAB_UNRECONCILED`，**注册项不杀**，需用户决定是否统一词表。<br>`D-R012-02` — `metric_scope = RAW` 在 legacy 面（`FRS:74` 毛收益用 `prices.close()`，qfq）与 snapshot 面（`FRS:340-350` 用 `snapshotPriceCache.close()`，rawClose 不复权）**同名不同价格基准**；按冻结 K3 措辞不构成 kill（不同数据路径、各自声明为真），**仅登记** |
| `blocked_queue.md` | 新增 `B-R012-01`（BLOCKED，与 `B-R011-01` 同型）：**载荷端到端形状**的观测需 Spring 容器 + DB + MP-016 snapshot；本轮环境不可端到端观测 ⟹ 该范围**未覆盖**，且**不得**读成「缺陷不存在」 |
| `progress.md` | §1 轮次表追加 R012 行（`REJECTED`/H0 被杀/K2·K3 触发/零生产改动）；§5 追加本轮纪律条目：**`A-02` 的 §12.2 降级流程**、**`A-04` 的 `instrument_alerts` 通道**、**`A-05` 的预测表勘误只进附录** |

---

## 9. 本轮**不**引用、**不**使用的东西

- 论文的**任何数值**（含 t 值、异象计数、分位阈值、样本区间统计）——全部在禁用清单内，`forbidden_scan` 命中 0。
- 论文的**结论**（哪些异象可复现/不可复现）——本轮**不**评价、**不**转述。
- 论文的**美国样本**（1967–2014）在 A 股的**可移植性主张**——判据 J-4 明确标 **X**，本轮只借其「样本域优先 + 逐项显式声明 + 未声明即不可重建」的**方法论要求**。
- 生产数据库、真实交易接口、真实证券账户、任何交易凭证。
- `modules/web/src/api/backtest.ts` 的判定效力（仅作旁证，见 `A-04`）。
- 任何关于「策略是否赚钱 / 因子是否有效」的表述（本轮**零**处）。
