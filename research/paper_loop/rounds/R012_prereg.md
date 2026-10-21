# R012 预注册（**冻结**）：研究载荷**声明性文本字段族**的「真值 / 覆盖 / 跨块跨面同义」审计

- **冻结时间**：2026-09-14（**先于本轮任何实验代码**）
- **判据来源论文**：**HXZ2020**（Hou, Xue, Zhang；本地载体 = NBER WP 23394, May 2017；
  发表版 *RFS* 33(5):2019–2133, 2020）⟹ 审查见 `rounds/R012_review.md`
- **预注册的效力**：本文件的**一切阈值、词表、块清单、文本清单、轴数、预期值一经写下**
  **不得事后放宽**。事后的更正**只能**写入 `Rounds/R012_report.md` 的附录（`A-xx`），
  **绝不**回写本文件的字节。**本文件的 sha256 在报告 §8.3 中记录**（供"零字节变更"核验）。

---

## 1. 本轮性质与被审计对象

### 1.1 形态

**只读审计轮**（与 R004–R011 同型）。论文**只作判据来源**（L 档做法种类），
**不采纳其任何结论/数值**。被审计对象是**项目自己**的契约主张。

### 1.2 H0（被杀死的是它）

> **H0**：项目「因子研究 / 打分回测」载荷面中的**声明性文本字段**
> （`universe_note` / `basis_note` / `net_basis_note` / `embargo_note` / `feature_snapshot_note` /
> `note` / `tradable_note` / `coverage_note` 类，以及 `metric_scope` / `metric_scopes` /
> `executable_metric_scope` 三键；下称 **`D` 族**）构成一份**自证文档**，满足三条：
>
> - **(i) 真（truth）**：`D` 族中每一条**可证伪的事实主张**与**实现**一致
>   （不存在「声称做了而没做」「声称不读而读了」「声称的值与实现不符」）；
> - **(ii) 全（coverage）**：每个**承载研究结果数值**的载荷块都带有**样本域声明**与**口径声明**
>   （判定规则见 §4.3/§6.3：**自身 ∪ 祖先 ∪ 直接子块**）；
> - **(iii) 一（consistency）**：同一语义的声明在不同块 / 不同载荷面之间**不矛盾**
>   （不得同名不同义，也不得一方**缺失**而另一方存在）。

> **H1**：三条子句中**至少一条**在**至少两个相互独立**的证据面上被证伪 ⟹ **H0 被杀**。

### 1.3 H0 **不是**什么（边界，防误读）

- H0 **不是**「文本写得好看/完整」的文体命题；只判**可证伪主张**（§6.1 的四类）与非空声明。
- H0 **不是**「口径正确性」命题：**不**判项目该不该用 RAW 还是 COST_APPROX、该不该剔 microcap、
  该不该改权重（那些是协议 §7 保留权限）。**只**判「声明是否与实现一致、是否覆盖、是否同义」。
- H0 **不是**「端到端可观测性」命题：载荷的**端到端**形状（真跑 Spring + DB）在本轮**不可观测**
  ⟹ 登记 **`B-R012-01`**（BLOCKED），且**不得**被读成「缺陷不存在」。

---

## 2. 判据映射（论文 → 本轮断言）

| 判据（`R012_review.md` §2.4） | 档 | 本轮承接为 |
|---|---|---|
| **J-1** 样本域是第一位口径选择；不同样本域下定性结论翻转 | L | **A3 覆盖面**（样本域声明的存在性）＋ **A4** （跨块跨面同义） |
| **J-2** 口径选择必须**逐项显式**声明（`consistent set of replication procedures`） | L | **A3 口径覆盖**＋ **A1 声明清单**（发射点计数） |
| **J-3** 未声明的关键选择 ⟹ 结果不可重建（门槛变化改定性结论） | L | **A2 真值面**（声称 vs 实现）＋ **A3** |
| **J-4** 论文样本（1967–2014 美国）不可移植 | X | **不进任何轴**（负面清单） |

**禁令（写在此处，防事后误引）**：`t ≥ 1.96` / `t ≥ 3` / `64%` / `85%` / `3%` / `60%` /
`61%` / `3.28%` / `1.32%` / `1.03%` / `20 分位` / `447` / `452` / `57·68·38·79·103·102`
**一律不得**成为本轮的阈值、容差、合格线或判据。

---

## 3. 冻结作用域（**扫什么、不扫什么，为什么**）

### 3.1 扫描根（**全部**为仓库内相对路径，冻结）

| # | 扫描根 | 为什么 |
|---|---|---|
| R1 | `modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java` | 本轮主载荷（legacy 研究 + snapshot 研究 + `cost_net_ic` 子块） |
| R2 | `modules/backend-java/src/main/java/com/quant/service/ScoringBacktestService.java` | 第二个载荷面（打分回测） |
| R3 | `modules/backend-java/src/main/java/com/quant/dto/ScoringBacktestResponse.java` | SBS 的 DTO（`FactorAnalysis` / `FactorIcSummary` 字段清单） |
| R4 | `modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java` | 引擎（`universeNote` 透传 + 退化块），**只读不提判据** |
| R5 | `modules/web/src/api/factor.ts` | TS 声明面（字段存在性/可选性） |
| R6 | `modules/web/src/views/factor/FactorResearch.vue` | 消费面（哪些 `D` 族字段被渲染） |

### 3.2 **明确排除**（并给出理由，防「扫得少 = 结论强」的误读）

| 排除项 | 理由 |
|---|---|
| `com.quant.research.*`（`ExperimentService` / `RollingExperimentService` / `ProbabilityCalibrationService` …）与 `WatchlistController` 等**其它模块的 `note` 字段** | **作用域冻结**：本轮审计对象是 **因子研究/打分回测** 载荷面（本循环 R004–R011 的一贯作用域）。**不得**把「本轮未覆盖其它模块」读成「其它模块无问题」；该缺口在本轮**登记为作用域边界**（报告 §11） |
| 前端其它页面（`ScoringBacktest.vue` 等） | 本轮只取 `FactorResearch.vue` 作为**一个**消费面样本；`ScoringBacktest.vue` 的 `D` 族渲染在 R004 已被登记，**不重复** |
| `R001`–`R011` 的一切冻结文件 | **零字节变更**（§13） |
| 生产数据库 / 真实交易接口 | **禁止**（协议 §8 红线；且 `B-R012-01`） |

### 3.3 计数断言的**可复算命令**（冻结）

```bash
# 块清单（§4.1）锚点命中
grep -n "private Map<String, Object> build\|private Map<String, Object> empty\|public Map<String, Object> research\|Map<String, Object> out = new LinkedHashMap" \
  modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java
# 声明性文本常量（§5 T01–T06）
grep -n "_NOTE =\|METRIC_SCOPE_" modules/backend-java/src/main/java/com/quant/service/FactorResearchService.java
# SBS 侧（§5 T21–T23）
grep -n "FACTOR_UNIVERSE_NOTE\|metric_scope\|tradable_note\|\"note\"" \
  modules/backend-java/src/main/java/com/quant/service/ScoringBacktestService.java
```

---

## 4. 冻结**块清单**（14 块）与块**构造规则**

### 4.1 块清单（`B01`–`B14`；行号为**冻结锚点**，允许 §11.4 的**仪器校准**）

| 块 | 位置（`file:line`，冻结锚点） | 是否承载**数值结果** | 说明 |
|---|---|---|---|
| `B01` | `FactorResearchService.java:150-171`（`research()` 顶层 `out`） | **是** | legacy 研究顶层；含 `rank_ic` 子块与 `funnel` |
| `B02` | `FactorResearchService.java:214-231`（统一报告顶层 `out`） | **是** | `by_horizon` + 顶层口径声明 |
| `B03` | `FactorResearchService.java:432-462`（`snapshotResearch` 顶层 `out`） | **是** | V2-MP-022 权威路径 |
| `B04` | `FactorResearchService.java:525-553`（`buildSnapshotScopeBlock`） | **是** | 三口径块（RAW / COST_APPROX / EXECUTABLE） |
| `B05` | `FactorResearchService.java:556-578`（`unavailableScopeBlock`） | **否**（退化块） | 口径不可用块 |
| `B06` | `FactorResearchService.java:605-627`（`buildSnapshotFunnel`） | **是**（计数） | snapshot 漏斗 |
| `B07` | `FactorResearchService.java:641-797`（`buildRankIc`） | **是** | legacy `rank_ic` 块（含 `cost_net_ic` 子块） |
| `B08` | `FactorResearchService.java:800-818`（`emptyNetIcBlock`） | **是**（空路径） | `cost_net_ic` 空路径生产者 |
| `B09` | `FactorResearchService.java:820-839`（`buildNetIcBlock`） | **是** | `cost_net_ic` 非空生产者 |
| `B10` | `FactorResearchService.java:845-877`（`buildResearchFunnel`） | **是**（计数） | legacy 漏斗 |
| `B11` | `ScoringBacktestResponse.java:79-91`（`FactorAnalysis`，接线 `ScoringBacktestService.java:1024-1048`） | **是** | SBS 因子分析块（**有** `universeNote`） |
| `B12` | `ScoringBacktestResponse.java:64-72`（`FactorIcSummary`，接线 `ScoringBacktestService.java:431-434`/`:443-450`） | **是** | SBS 逐持有期 IC 汇总（**无** universe 字段） |
| `B13` | `ScoringBacktestService.java:370-378`（漏斗）+ `:466-467`（`assumptions`） | **是**（计数） | SBS 执行漏斗与口径声明 |
| `B14` | `ScoringBacktestService.java:436-450`（Codex 子因子 3 个 `FactorIcSummary`） | **是** | Codex 子因子摘要（复用 `B12` 的 DTO） |

### 4.2 块构造规则（冻结）

1. 每个块 = 一个**具体的载荷容器**（`Map` 字面量 / DTO 字段清单）；`B14` 与 `B12` **共用 DTO**
   但**分别**登记（因为它们的 `universeNote` 来源不同：`B14` 传 `"Codex 子因子 " + key + "；" + FACTOR_UNIVERSE_NOTE`）。
2. **不可**把 `scopes` 这个 `Map` 本身算作块（它是容器，不是载荷块）；其三个子块由 `B04`/`B05` 承载。
3. 块清单**冻结为 14**；若实测与 14 不符 ⟹ 走 `A-xx` 勘误，并**不改**本表。

### 4.3 覆盖判定规则（冻结；**这是本轮唯一允许的覆盖口径**）

对块 `B` 与语义键集合 `K_universe = {universe_note, universeNote, universe_source}`、
`K_scope = {metric_scope, metric_scopes, executable_metric_scope, basis_note, note, tradable_note}`：

- `COVERAGE_OK` ⟺ `B` 的**自身字段**、或**任一祖先块**、或**任一直接子块**中出现 `K` 中至少一个键；
- `COVERAGE_MISSING_UNIVERSE` / `COVERAGE_MISSING_SCOPE` ⟺ 上述三处**全部**不出现；
- **同时**单独输出「**仅自身字段**」口径下的缺失清单（**描述性**，**不**作 kill 依据）。

---

## 5. 冻结**声明性文本清单**（24 条）

| ID | 位置（冻结锚点） | 字段/键 | 备注 |
|---|---|---|---|
| `T01` | `FactorResearchService.java:72-74` | `RANK_IC_UNIVERSE_NOTE` | LEGACY_SELECTION_BIASED |
| `T02` | `:75-77` | `RANK_IC_BASIS_NOTE` | RAW 公式 |
| `T03` | `:78-82` | `RANK_IC_NET_BASIS_NOTE` | COST_APPROX 公式 |
| `T04` | `:83-85` | `RANK_IC_EMBARGO_NOTE` | 跳过 + 显著性 |
| `T05` | `:91-93` | `SNAPSHOT_UNIVERSE_NOTE` | 全 PIT 截面 |
| `T06` | `:94-96` | `FEATURE_SNAPSHOT_NOTE` | 指引 |
| `T07` | `:409-411` | `rawNote` | RAW（snapshot 侧） |
| `T08` | `:412-413` | `costNote` | COST_APPROX（snapshot 侧） |
| `T09` | `:414-419` | `execNote` | EXECUTABLE（条件式，二分支） |
| `T10` | `:170-171` | `B01.note` | 顶层① |
| `T11` | `:228-229` | `B02.note` | 顶层② |
| `T12` | `:458-459` | `B03.note` | V2-MP-022 |
| `T13` | `:551` | `B04.note`（无观测分支） | 「不给出 IC」 |
| `T14` | `:576` | `B05.note` | **等于 `basisNote`** |
| `T15` | `:625-626` | `B06.note` | 漏斗口径 |
| `T16` | `:815-816` | `B08.note` | 空路径 note |
| `T17` | `:836-837` | `B09.note` | 非空 note |
| `T18` | `:872` | `B10.tradable_note` | 旧漏斗 |
| `T19` | `:875` | `B10.note` | 旧漏斗口径 |
| `T20` | `:621-623` | `B06.tradable_note` | 条件式，二分支 |
| `T21` | `ScoringBacktestService.java:60-61` | `FACTOR_UNIVERSE_NOTE` | SBS 样本域 |
| `T22` | `ScoringBacktestService.java:377` | `B13.note` | FILLED_SAMPLE |
| `T23` | `ScoringBacktestService.java:448` | `B14.universeNote` | 条件拼接 |
| `T24` | `FactorResearchService.java:49` | 类 Javadoc 的 scope 词表 | 枚举 `RAW / COST_APPROX / EXECUTABLE` |

**规则**：`T01`–`T24` **每一条**都必须出现在报告的逐项表里（**24/24，不得省略**），
判定词必须取自 §7 词表。

---

## 6. 冻结**真值判定规则**

### 6.1 只有四类主张算「可证伪」（冻结）

- **(F1) 公式/窗口主张**：含 `[t+h]`、`×`、`/`、`-1`、`close`、`open` 等可对照代码的算式；
- **(F2) 数据源主张**：「只读 X」「不读 Y」「来自 Z」「join W」；
- **(F3) 行为主张**：「跳过」「不计入」「不可用」「不给出」「自动」等**可执行行为**；
- **(F4) 枚举主张**：给出的取值集合/清单（如 `RAW / COST_APPROX / EXECUTABLE`）。

### 6.2 **不算**可证伪（必须显式判 `UNFALSIFIABLE` 并给理由）

- **规范/意图类**：「仅作…评价」「禁止据此直接调整因子权重」「不得称为完整可执行 IC」
  （**规范性禁令**，非事实主张）；
- **记法差异类**：同一算术的**书写形式**不同（如 `×(1+佣金+滑点)` 与两级乘性实现）——
  该类**一律**判 `UNFALSIFIABLE` 并**交叉引用** `D-R010-01`（R010 已登记）；
  **本轮不得**把它判成 `FALSE_CLAIM`，**不得**据此触发 K1。

### 6.3 判定词（**词表冻结**）

| 词 | 含义 |
|---|---|
| `TRUE_CLAIM` | 该主张与实现一致（有代码证据） |
| `FALSE_CLAIM` | 该主张与实现**不一致**（有代码证据证伪） |
| `UNFALSIFIABLE` | 不属于 §6.1 四类（§6.2 情形），**并给出理由** |
| `CLAIM_ABSENT` | 该字段在该块**不存在** |
| `CLAIM_UNDECIDABLE_STATIC` | 属四类，但**静态不可判**（需运行/DB）⟹ 计入 `B-R012-01` |
| `COVERAGE_OK` / `COVERAGE_MISSING_UNIVERSE` / `COVERAGE_MISSING_SCOPE` | §4.3 |
| `CONSISTENT` | 同一语义在不同块/面**同义** |
| `INCONSISTENT` | 同一语义在不同块/面**矛盾**（含「一方存在、另一方缺失」且无正当作用域差异） |
| `SCOPE_VOCAB_UNRECONCILED` | 同一键名（`metric_scope`）的**取值域**跨面不重合且**仓库内无统一词表** ⟹ **注册项**（**不**作 kill） |
| `CONSISTENT_DISTINCT_SOURCES` | 两条 `universe` 声明**描述不同数据路径**且各自为真 ⟹ **不矛盾** |
| `NOT_REACHED_STATIC_ONLY` | 该变体因静态-only 未执行 |

**表外词 ⟹ 直接触发 `K6`（仪器门失败）⟹ `INCONCLUSIVE`**（R007 教训 44 的机械落实）。

---

## 7. 冻结**轴与变体表**（**N = 22**，`MAX_VARIANTS = 22`）

| 轴 | 变体 | 内容 | 判定依据 |
|---|---|---|---|
| **A1 块与结构面**（4） | `V01` | 块清单 = **14**，冻结锚点全部命中（0 mismatch） | §4.1 |
| | `V02` | `B07` 键集：**空/非空两分支的键集差集非空**（报告实测差集） | §3.1 R1 |
| | `V03` | `B08` vs `B09` 键集差集（**预期含 `monotonicity`**）；**B-R009-A5b 交叉引用，不作 kill** | R1 |
| | `V04` | `B11` 有 `universeNote` 字段 / `B12` **无** universe 类字段（DTO 级字段清单） | §3.1 R2/R3 |
| **A2 真值面**（6） | `V05` | `T02`（RAW 公式主张）逐条 | §6.1 (F1) |
| | `V06` | `T03`（COST_APPROX：raw 成交价 / 缺 raw 跳过 / 扣费 / **无**拒单过滤）四件事 | §6.1 (F1)(F2)(F3) |
| | `V07` | `T01` + `T05`（两条样本域主张） | §6.1 (F2) |
| | `V08` | `T04`（「自动跳过/不外推」+「显著性以 HAC/CI 为准」） | §6.1 (F3)(F4) |
| | `V09` | `T16`+`T17`（「窗口差一日」+「EXECUTABLE 暂不可用」） | §6.1 (F1)(F3) |
| | `V10` | `T07`+`T08`+`T09`（snapshot 三口径 note） | §6.1 (F1)(F2)(F3)(F4) |
| **A3 覆盖面**（5） | `V11` | 14 块 × 样本域声明的覆盖矩阵（**自身 ∪ 祖先 ∪ 直接子**） | §4.3 |
| | `V12` | 14 块 × 口径声明的覆盖矩阵 | §4.3 |
| | `V13` | 「**仅自身字段**」口径下的缺失清单（**描述性**） | §4.3 末条 |
| | `V14` | 「承载数值结果的块」判定（按 §4.1 冻结列） | §4.1 |
| | `V15` | `B12` = `COVERAGE_MISSING_UNIVERSE`？（候选 **K2**） | §4.3 |
| **A4 一致性面**（4） | `V16` | `B11` vs `B12` 的样本域声明**不对称**（候选 **K3**） | §6.3 |
| | `V17` | `metric_scope` 取值域跨面（`RAW/COST_APPROX/EXECUTABLE` vs `FILLED_SAMPLE`）与 `T24` 词表的可调和性 | `SCOPE_VOCAB_UNRECONCILED` |
| | `V18` | `T01` 与 `T21` 两条样本域声明的同义性（**各自是否描述不同路径**） | `CONSISTENT_DISTINCT_SOURCES` / `INCONSISTENT` |
| | `V19` | `T10`/`T11`/`T12` 三个顶层 note 与各自块内 `metric_scope` 的表述是否自相矛盾 | §6.3 |
| **A5 仪器/对照面**（3） | `V20` | **正面对照**：`T01` 常量确实**到达**载荷（发射点 + 引擎 `universeNote` 透传） | §11.1 |
| | `V21` | **反向锚点**：一条**必须为真**的主张（`hac_std`/`ci_*` 来自同一 IC 序列） | §11.2 |
| | `V22` | 金丝雀（CLN/INJ）+ 逐位确定性 + 行和/轴计数自检 | §11.3/§9.2 |

**轴计数**：`A1=4, A2=6, A3=5, A4=4, A5=3`，和 = **22** = `variants_total`；
`row_sum = axis_total = 22` 与 `grid_mismatch_count = 0` **三条自检**必须在运行时通过（§11.3）。

**承载 kill 的轴**：`A2`（K1）、`A3`（K2）、`A4`（K3）。
**非 kill 轴**：`A1`（结构/描述）、`A5`（仪器）。非 kill 轴的判定**不得**事后被当成 kill 依据。

---

## 8. 冻结**容差与常量**

| 常量 | 冻结值 | 用途 |
|---|---|---|
| `TOL` | `1e-15` | 浮点逐位对照 |
| `TOL_INT` | `0` | 整数计数 |
| `DET_BITS` | `64` | 确定性（两次运行逐位相等） |
| `G3_SUFFIX` | `modules/backend-java/target/classes` | 探针必须编译进生产引擎自己的包（R010 教训 59） |
| `BLOCK_COUNT` | `14` | §4.1 |
| `TEXT_FIELD_COUNT` | `24` | §5 |
| `MAX_VARIANTS` | `22` | §7 |
| `CANARY_PAIRS` | `22` | 每个变体一对（CLN + INJ），个数 = 变体数 |
| `PROBE_CLEANUP` | `true` | 探针字节码运行结束**必须**清理（R010 教训 59） |

---

## 9. 冻结**判定表（K 表）**与短路

### 9.1 K 表

| K | 内容（**冻结措辞**） | 触发条件 |
|---|---|---|
| **K1** | **(i) 真** 被证伪 | 存在 **≥1** 条 `FALSE_CLAIM`（且该主张属 §6.1 四类、非 §6.2 记法/规范类） |
| **K2** | **(ii) 全** 被证伪 | 存在 **≥1** 个 `承载数值结果 = 是` 的块为 `COVERAGE_MISSING_UNIVERSE`（按 §4.3 的三处口径），**且**该块的数值依赖非平凡样本域选择（由代码证据支持） |
| **K3** | **(iii) 一** 被证伪 | 存在 **≥1** 组 `INCONSISTENT`（同一语义键在一个块存在、在**同面同层**的对应块**缺失**，且**无**正当作用域差异） |
| **K4** | 兜底 | 块清单抽取失败（实测 ≠ 14 且无法校准）**或** `T01`–`T24` 抽取缺项 **≥1** |
| **K5** | 仪器门（计数） | `row_sum ≠ 22` 或 `axis_total ≠ 22` 或 `grid_mismatch_count ≠ 0` 或 `BLOCK_COUNT ≠ 14` 或 `TEXT_FIELD_COUNT ≠ 24` |
| **K6** | 仪器门（仪器自身） | 金丝雀 `CLN`/`INJ` 任一失败、出现**表外词**、正面对照 `V20` 或反向锚点 `V21` 失败、`G3` 门失败 |
| **K7** | 仪器门（确定性/冻结） | 两次运行**非逐位相等**，或 `R001`–`R011` 冻结文件出现**非零字节变更** |

### 9.2 短路（冻结，**先算仪器门**）

```
if (K5 or K6 or K7):  total_verdict = INCONCLUSIVE      # 仪器不可信 ⟹ 不下结论
elif (K1 or K2 or K3 or K4):  total_verdict = REJECTED   # 假设被杀死
else:  total_verdict = NOT_VIOLATED
```

- `K1`–`K4` **不得**因「只有一条证据」而被降级，也**不得**因「证据多」而被升级为别的词。
- 短路**先**判仪器门：仪器不可信时**不得**输出 `REJECTED`（防止把仪器故障当成发现）。

---

## 10. 冻结**预期值**（`P` 表；**先写下，事后不符即勘误**）

| P | 内容 | 预期 |
|---|---|---|
| `P-1` | 块清单实测 = **14**，锚点 `mismatch` = 0 | ✅ |
| `P-2` | `B07` **两个分支（空/非空）的键集差集非空** | ✅ |
| `P-3` | `B08`/`B09` 键集差集 = **{`monotonicity`}**（仅描述，不作 kill） | ✅ |
| `P-4` | `B12` **无** `universe` 类字段；`B11` **有** `universeNote` | ✅ |
| `P-5` | `T03` 的四件事（raw 成交价 / 缺 raw 跳过 / 扣费 / 无拒单过滤）**全真**；其**记法**判 `UNFALSIFIABLE` | ✅ |
| `P-6` | `T04` 的两条主张（自动跳过 + 显著性以 HAC/CI 为准）**全真** | ✅ |
| `P-7` | `T16`/`T17` 的「与毛收益 IC 窗口差一日」**为真** | ✅ |
| `P-8` | `V15` → `B12 = COVERAGE_MISSING_UNIVERSE` ⟹ **K2 触发** | ✅ |
| `P-9` | `V16` → **`INCONSISTENT`** ⟹ **K3 触发** | ✅ |
| `P-10` | `V17` → `SCOPE_VOCAB_UNRECONCILED`（**注册项，不杀**） | ✅ |
| `P-11` | `V18` → `CONSISTENT_DISTINCT_SOURCES`（**不杀**） | ✅ |
| `P-12` | `V13`（仅自身字段）缺失清单**非空**（≥5 块） | ✅ |
| `P-13` | `total_verdict = REJECTED`、`h0_alive = false` | ✅ |
| `P-14` | **`K1` 不触发**（`FALSE_CLAIM` 计 **0**） | ✅ |
| `P-15` | 表外词 = `[]`；两次运行**逐位相等** | ✅ |
| `P-16` | `T24` 的枚举**不含** `FILLED_SAMPLE` ⟹ `T21`/`T22` 的 scope 与 `T24` 词表**不可调和** | ✅ |

**预期全中不是能力证据**（同 R006 §4 第 33 条、R008 §4、R009 §4.1、R010 §4 的纪律）；
`P-5`/`P-14` 是**对项目有利**的预期，**不得**事后弱化成「顺便一提」。

---

## 11. 冻结**仪器自检**

### 11.1 正面对照（`V20`，必须**通过**）

- `T01`（`RANK_IC_UNIVERSE_NOTE`）必须能在 `B01`/`B02`/`B07` 的**发射点**被抽到，
  **且**引擎 `summarize(..., universeNote)` 的**透传**在探针中可观测（`summary.universeNote()` 逐字符等于冻结字面量）；
- 若**不通过** ⟹ `K6` ⟹ `INCONCLUSIVE`（说明抽取器/探针失效，而非「声明不存在」）。

### 11.2 反向锚点（`V21`，必须**通过**）

- 一条**已知为真**的主张：「`hac_std` / `ci_lower` / `ci_upper` 由**同一条** IC 序列算出」——
  探针在冻结面板上验证三者**同时存在且有限**，且 `ci_lower ≤ ci_upper`；
- 若**不通过** ⟹ `K6`（说明判定器**恒假**，会系统性地把「真」误判为「假」）。

### 11.3 金丝雀、计数与确定性

- **金丝雀（`CANARY_PAIRS = 22`）**：
  - `CLN`（每个变体）：把该变体**声明的键**全部覆写为其**实测值**后，谓词结果**不变**；
    再塞入**未声明的哨兵键**后结果**仍不变**（证明谓词是所声明键集合的**确定性函数**）
    —— **`CLN` 不得实现成「谓词必须为真」**（R011 教训 61：否则**诚实的负结论**会被误报成仪器失效）；
  - `INJ`（每个变体）：只改**一个**声明键，谓词结果**必须翻转**；注入值**按实测状态取向**（R011 教训 62）；
  - 判据**不得**读取「由该变体自身结果算出」的键（R011 教训 63：`CANARY_PAIRS` 以**声明数**填入并断言等于实测数）。
- **行和/轴计数自检**：`row_sum = 22`、`axis_total = 22`、`grid_mismatch_count = 0`。
- **确定性**：同一输入运行**两次**，`result.json` 的数值块**逐位相等**（`DET_BITS = 64`）。
- **`G3` 门**：探针字节码的 `getCodeSource().getLocation()` 必须**以 `G3_SUFFIX` 结尾**；
  运行结束**清理**探针字节码。

### 11.4 行锚点**校准**（R009 教训 54）

冻结的行号区间可能与实测块范围有偏差 ⟹ 运行前做**实测校准**；
**校准只改仪器**（抽取器的 `scope`），**绝不**改本文件的冻结数字；
校准结果若导致任何判定变化 ⟹ 进报告附录并在 §8.2（运行史）中**显式披露**。

### 11.5 禁数自检（R011 教训 64）

本执行器与其产物**不得**把论文数值当作阈值/容差；
自检必须**排除自身的 denylist 字面量**（否则会匹配到自己的描述串）。
`FORBIDDEN_PATTERNS` 至少含：`1.96`、`"t >= 3"`、`0.64`、`0.85`、`3.28`、`20th percentile`。

### 11.6 文本检索（R011 教训 65）

文本类判定**必须先做子串假阳性检查**（如 `note` 会命中 `notes`、`universe_note` 会命中
`universe_note_extra`）；处置：**不改**冻结词表，另加**词边界收紧测量**作判别，并在报告中**逐条列出**命中。

### 11.7 「有无字段」类判定（R011 教训 66）

查「某块是否含某字段」**必须同时**查三件事：`put("key"` / builder setter / **挂载点接线**
（如 `.universeNote(...)`），**并查嵌套类型**（本轮 `B12` 就在**嵌套类**里）。

---

## 12. 必答披露（报告必须逐条写出）

1. **变体数 `N`**：`22`（`A1=4, A2=6, A3=5, A4=4, A5=3`），`MAX_VARIANTS = 22`，三自检结果；
2. **±20% 敏感性**：本轮**无参数网格** ⟹ **等价替代**：同一批主张用
   **(a) 子串匹配 / (b) 词边界收紧匹配 / (c) 人工逐字核对** 三种读数各测一次，
   报告**是否有判定被读数方式改变**（若改变 ⟹ 该判定在报告中**降级**为描述性）；
3. **`研究口径规范.md` §5 的前视/幸存者偏差影响**：本轮**不产生**收益/因子数值 ⟹ 写「**不适用**」，
   并**逐字引用** `T01` 自身对 `LEGACY_SELECTION_BIASED` 的声明，但**不测量其大小**（属 R002 范围）；
4. **零生产代码改动**的逐项证据（`git status --porcelain`、探针产物清理、`git diff --check`、密钥/大文件扫描）；
5. **运行史**：`run.log` append-only，保留**全部**运行（含失败），并说明每次失败是**仪器缺陷**还是**假设被证伪**；
6. **偏离**：实测与 `P` 表/本文件不符者，逐条进报告附录 `A-xx`，**不回写**本文件。

---

## 13. 负面清单与运行纪律（**冻结**）

- **不得**放宽任何 K；**不得**在看到结果后修改词表、块清单、文本清单、轴数或容差；
- **不得**把 `UNFALSIFIABLE`（记法/规范类）计入 `FALSE_CLAIM`（§6.2）；
- **不得**把 `COVERAGE_MISSING_*` 与「项目做错了」混为一谈：它只说**声明缺失**，
  且「是否应补声明」属协议 §7 保留权限（**只登记** `D-R012-*`，**不**改代码）；
- **不得**修改 `R001`–`R011` 的任何冻结文件；**不得**重开已登记项（只可**交叉引用**）；
- **不得**重审 R002 的样本域偏差**大小**、R004 的口径结构轴、R006 的退化行为、
  R009 A5b 的 TS 键集、R010 的记法/成本公式、R011 的三条承重分叉（`progress.md` §6 逐字禁令）；
- **不得**启用真实交易 / 索取真实凭证 / 向真实账户下单；**不得**操作本仓库以外的仓库；
- 每轮 **commit + 普通 push**；push 失败记 `PUSH_PENDING`，**不得** force push；
- `run.log` **append-only**；探针字节码运行结束**必须清理**。
