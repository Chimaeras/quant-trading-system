# R001–R015 决策收口记录（2026-09-14）

> 对应：`research/paper_loop/decision_queue.md` 的 **85 项 `PENDING_USER`**
> （**2026-09-15 更新**：`D-R011-03` 经用户裁定记 `RESOLVED` ⟹ 现为 `PENDING_USER` **83** + `RESOLVED` **2**，总行数仍 85。
> 本文件的逐项处置**一字未改**——该裁定只改 R011 的**仪器合格性**，不改变任何实测，也**不解除**本文件 §8 列出的任何残留项。）
> 授权：用户裁定 **Q1「按 P0 全量排队一次做完」**、**Q2「允许加字段，不改既有键语义」**，
> 并指示 **「按你的分析取最合适的做法去进行改动」**。
> 协议：`docs/Codex论文驱动量化改进执行协议.md` §7（record-only 清单）/ §8（硬红线）。
> 口径：本文件**只记录已发生的处置**；凡未落地的项一律写明「未落地」与原因，不得当作已完成。

---

## §0 执行边界（本轮实际跨越了什么）

Q1+Q2 解锁了协议 §7 的 record-only 项，本轮的**自我约束**仍是：

1. **只加字段、只加声明键**，不改任何既有键的名字与语义；退化分支与正常分支**发射同一键集**。
2. **不改**冻结成本常数（买入佣金万三 / 卖出印花税万五 / 单边滑点万二）、不改入场-退出口径
   （signal_date 次日**开盘**入场、第 h 持有日**收盘**退出）、不改 `NOT_BUYABLE`/`SUSPENDED`/
   `NOT_SELLABLE`/`DELISTED` **不得删样本**规则、不把 `rule_score` 表述为上涨概率。
3. **未启用**任何真实交易、**未**索取真实凭证、**未**向真实账户下单。
4. **未执行**任何破坏性 DDL / 数据回填 / 不可逆迁移（见 §6，全部只给草案）。
5. `git` 只做 commit 与普通 push，不 force、不建 PR、不发版本。

---

## §1 本轮落地的**代码 / 契约**变更（逐项可核验）

| 决策项 | 采用的做法 | 落地位置（证据） |
|---|---|---|
| `D-R001-01` + `D-R005-03` | 质量闸阈值单位修正：`amount` 实为**万元**，阈值由 `30_000_000` 改为 `30_000d`（=3 亿元/日）；并列发射机器可读量纲诊断 | `ScoringUtils.DEFAULT_MIN_AVG_AMOUNT`、`AMOUNT_UNIT=TEN_THOUSAND_CNY`、`liquidityGateMagnitudeDiagnosis()`（≥100× 候选中位数时给 `SUSPECTED_UNIT_MISMATCH`）；DDL 注释 `成交额(元)`→`成交额(万元)`（仅注释，无结构变更） |
| `D-R001-02` + `D-R001-04` | **不提升**任何影子因子、不调权重（遵守影子先行） | 未改权重；留痕见 `shadow_registry.md` |
| `D-R003-01` + `D-R004-01` | **加法**：新增**美元中性净价差**并列字段与其基准声明，**不动**旧 `long_short_spread` 语义 | 载荷 `long_short_spread_neutral`/`_exact`/`_basis`/`_note`；DTO `FactorIcSummary`/`FactorAnalysis` 新增 `longShortSpreadCostBasis` + `longShortSpreadGrossEquivalent` |
| `D-R006-02` | 主口径 `÷T` **未动**；并列发射 **样本标准差 ÷(T−1)** 读法 | 载荷 `rank_ic_std_sample`/`rank_ic_ir_sample`/`_basis`/`_note`/`_exact`；DTO `rankIcStdSample`/`rankIcIrSample` |
| `D-R006-03` + `D-R014-07` | 新增**丢弃日计数 + 阈值**字段（不改丢弃行为） | `days_dropped_below_min_cross_section`、`days_dropped_non_finite_ic`、`input_cross_section_days`、`min_cross_section_size` + `_basis` |
| `D-R006-04` + `D-R013-03` | `factorGroupCount` **钳位到 [2,20]** 并**显式标记已钳位**（避免「计算取钳位值、持久化取原始值」同型问题） | `clampFactorGroupCount()`、`EffectiveExecutionParams`；载荷 `factor_group_count_requested/_effective/_clamped/_basis/_note` |
| `D-R006-07` + `D-R013-01` | 全相等（零斜率）返回**显式枚举** `NO_DISPERSION`（不再与「上升」混淆）；非有限组均值与可用组 <2 返回 `N/A` | `FactorResearchEngine` 单调性判定（`NO_DISPERSION` / `N/A` / `MONOTONIC_*`） |
| `D-R007-01` | 区间**状态显式化**，n<2 与零宽不再以数值冒充 | 载荷 `ci_status`（`INSUFFICIENT_N` / `ZERO_WIDTH` / …） |
| `D-R007-03` + `D-R009-05` + `D-R009-06` | 退化分支**补齐同键集**（`null` = 无定义，≠ 0） | `putSummaryMetricKeys` / `putSummaryDisclosure` 统一入口，三族块共用 |
| `D-R007-09` + `D-R013-04` + `D-R014-08` + `D-R014-09` | **载荷 4dp + `*_exact` 未取整并列**；DTO 面承载**原值**；并声明取整规则 | `precision_policy`（`PAYLOAD_ROUNDED_4DP_WITH_EXACT_COMPANION`）+ `rounding_rule` + `*_exact` 键族 |
| `D-R008-01` | **不改**旧 `avg_turnover` 数值；并列发射**按当日实际规模归一**的口径 | 逐日 `turnover_actual_denominator`、`denominator_actual_value`；聚合 `avg/max/min_turnover_actual_denominator`、`pairs_dropped_undefined_actual_denominator` |
| `D-R008-03` | 新增机器可读状态标记（不再只靠数值臆测） | `turnover_status`（`OK` / `INSUFFICIENT_SIGNAL_DAYS`） |
| `D-R008-04` + `D-R008-05` | 回显**归一**为三键：旧 `strategy`（兼容保留）+ `strategy_requested`（空白→null）+ `strategy_effective` | `BacktestTurnoverService` |
| `D-R008-06` + `D-R008-07` | 逐日行补 `prev_size`/`curr_size`/`intersection_count`/`short_handed`；补 `denominator`、`avg_turnover_unit` | 同上（读者可**精确复算**每日换手） |
| `D-R009-02` + `D-R009-04` | `pearson_ic_mean` 由硬写 `0.0` 改为 **`null`**（无支撑日时），并**并列发射支撑日数** | `pearson_ic_mean`(`Double`)/`pearson_support_days`/`pearson_ic_mean_note`；DTO `pearsonIcMean` 可空 |
| `D-R010-01` + `D-R010-02` + `D-R010-03` | 声明文本按**乘性实现**校正（卖出腿漏 `commission` 的加性式已勘误） | `docs/研究口径规范.md` §2 勘误（Q-R010-01）；`ResearchConvention.labelDefinition()` 文本 |
| `D-R010-06` | 费率**持久化取生效值**（原先持久化原始入参、计算取钳位值 ⟹ 两面不一致） | `persistRun(...)` 改存 `EffectiveExecutionParams` 的生效值；载荷并列 `commission_rate_requested` 等 3 个 `*_requested` + 3 个 `*_clamped` + `rate_bounds_note` |
| `D-R011-01` | 三个族的载荷各补承重键（组数、截面下限、每组样本量） | `min_cross_section_size` + `_basis`、`group_count`、`groups_with_observations`、`grouped_returns[].observations` |
| `D-R012-01` | `metric_scope` 词表**补全为 5 值**（`FILLED_SAMPLE`/`UNAVAILABLE` 曾缺失） | `FactorResearchService` 类 Javadoc；`docs/研究口径规范.md` §8.4 |
| `D-R012-02` | 两面**显式区分价格基准**（同名 `RAW` 不同基准） | `price_basis`（`RAW` / `QFQ_CLOSE`）+ 各块 `*_basis_note` |
| `D-R013-05` | 前端 `monotonicity` 由裸 `string` 改为**字面量联合类型** | `modules/web/src/api/factor.ts` `MonotonicityLabel`（含 `NO_DISPERSION`） |
| `D-R013-06` | DTO **按需增字段**（标签同时可从 DTO 读出） | `ScoringBacktestResponse.FactorAnalysis` / `FactorIcSummary` 新增 6 个字段 |
| `D-R013-07` | `cost_net_ic` 块**补 `group_count` 与 `grouped_returns`**（披露粒度对齐） | 三块共用 `putSummaryMetricKeys` + `putSummaryDisclosure` |
| `D-R013-08` | 展示面标注「标签依赖组数参数」并**给出所用组数** | `factor_group_count_effective` + `_basis`/`_note` |
| `D-R014-01` + `D-R014-02` | 载荷**自述分组边界规则**，并明确「并列行相对次序不作保证」 | `group_boundary_rule = INDEX_EQUAL_SPLIT_STABLE_SORT` + `_note`（写出依赖上游行序这一事实） |
| `D-R014-03` | DTO **字段级 javadoc 声明单位**（`observations` = 参与聚合的截面日数，非成分股数） | `ScoringBacktestResponse.java:124-127` |
| `D-R014-05` | `round4` 加**有限性守卫**（非有限 → `null`，不再显示成 `0.0` 收益） | `FactorResearchService.round4(Double)`：`!Double.isFinite(v) → null` |
| `D-R014-06` | 计数字段**只统计有限观测**，剔除量单独披露 | Javadoc 计数族语义 + `excluded_non_finite_observations` |
| `D-R015-01` | 回退加**具名标记**（可读出回退发生了） | `universe_count_source = FALLBACK_TOTAL_OBSERVATIONS` |

### 1.1 本轮**新发现**并一并声明的问题（不在原 85 项内）

| 发现 | 内容 | 处置 |
|---|---|---|
| **同名不同量：价差毛/净** | `ScoringBacktestResponse` 面的 `longShortSpread` 是**净**（观测取自 `periodResult.ret()`，已扣费），而 `FactorResearchService` 载荷毛块同名键是**毛** | 新增 `long_short_spread_cost_basis`（`GROSS_BEFORE_COSTS` / `NET_AFTER_MODELED_COSTS` / `NOT_APPLICABLE_NO_OBSERVATIONS`）+ DTO 侧 `longShortSpreadCostBasis`；**拒绝**在净面再叠加美元中性（会二次计费） |
| **两个成本模型不是互逆** | 价格模型 `net = gross×(1−c)`（可用 `net/(1−c)` 精确反解）与美元中性惯例 `neutral = gross − 2c`（按名义额计费）**不是同一个模型** | 闭式给出两个差额：**模型差** `c(2−G)`、**反解差** `c(2−G)/(1−c)`；`grossSpreadFromNet` 的 javadoc 明确写「**不是** `dollarNeutralSpread` 的逆」，测试断言按闭式钉死 |
| **成本率的乘性 vs 加性** | `roundTripCostPerLeg ≈ 15.0bp`（乘性、买卖两侧各含佣金）vs 加性读法 `c+t+2s = 12bp`（**漏卖方佣金**） | 并列发射 `cost_per_leg_round_trip`（+`_exact`/`_basis`/`_note`）；测试断言主项差 ≈ `−commission` |

---

## §2 判为 **record-only**（协议 §7）——已按「加法/声明」处置，不再需要代码动作

- **声明类**（本轮已写进 `docs/研究口径规范.md` §8 或载荷 `*_note`）：
  `D-R007-04`（HAC 滞后阶由窗口定）、`D-R007-05`（块长钳位）、`D-R007-06`（表头口径）、
  `D-R007-07`（区间为**名义**水平，未做多重检验校正；**不得**引入 HLZ2014 的 3.0/3.9 门槛）、
  `D-R007-08`（重叠持有期下实际覆盖率可能低于 95%）、`D-R010-04`（同构副本，非缺陷）、
  `D-R011-02`（**机械检索必须先做子串假阳性检查**）、`D-R011-04`（「备选集合」合格形态提升为项目级）、
  `D-R012-01`（`metric_scope` 5 值承重键）、`D-R013-09`（非有限组均值当前不可达）、
  `D-R014-04`（两面标签限定词）、`D-R014-09`（取整规则声明）、`D-R014-10`（逐变体 `reachability` 必须进预注册冻结表）、
  `D-R008-08`/`D-R008-09`（四个「换手率」不是同一个量 + 命名/单位规则）、`D-R001-03`（论文载体双版本标注）
- **沿用手册/点名前轮，不重复主张为本轮发现**：
  `D-R015-02`（点名 R004）、`D-R015-03`（点名 R013）、`D-R015-04`（点名 R009）、`D-R015-05`（**反例登记，不得写成发现**）
- **纯登记（无生产动作）**：`D-R003-02`（一跳下界仅作研究文档对照量）、`D-R006-06`（当前无写入路径的列 **首选保留 + 文档声明**）、
  `D-R010-05`（**不重算**任何历史结论；只加勘误说明偏乐观方向与量级 ≈ `3.00 bp of E`）、
  `D-R013-09`、`D-R014-01`/`D-R014-02` 的「规则设计无错、被审计的是载荷自述完备性」边界

## §3 已由**既有实现**满足（本轮核实，未重复改动）

| 决策项 | 核实点 | 证据标记 |
|---|---|---|
| `D-R006-07` / `D-R013-01` | 零离散度不再与「上升」混淆 | `NO_DISPERSION` 分支存在于单调性判定 |
| `D-R007-01` | n<2 / 零宽不再是数值冒充 | `ci_status` 三态 |
| `D-R009-02` / `D-R009-04` | Pearson 支撑集**可见** | `pearson_support_days` + `pearson_ic_mean` 可空 |
| `D-R014-05` | 非有限不再显示为 `0.0` 收益 | `round4` 有限性守卫 |
| `D-R014-03` | 单位语义写进 DTO 字段级 javadoc | `ScoringBacktestResponse.java:124-127` |
| `D-R013-05` | 值域可静态校验 | `MonotonicityLabel` 字面量联合 |
| `D-R013-07` | 三块披露粒度一致 | 共用 `putSummaryMetricKeys` + `putSummaryDisclosure` |
| `D-R012-01` | 词表 5 值与实际取值域对账 | 类 Javadoc 列全 5 值 |

## §4 **未落地 / 需用户裁定**（本轮**未**自行改判）

> **§4.0 事后文字更正（2026-09-15，随勘误提交）**
>
> 本表初稿把 `D-R009-01`、`D-R009-07`、`D-R009-08`、`D-R010-07` 记成「测试被阻断 / 当前会失败」，**该表述是错的**。
> 逐项**回工作树**核对后确认：这四项**第一批即已落地且通过**。证据：
>
> - `D-R009-01`（守卫定性）：`service/backtest/FactorResearchEngine.java:554-561` —— 判据已由**绝对**阈值 `vx < 1e-18` 改为**相对**判据（精确零 `vx == 0`，并列 `maxAbsDx <= 1e-12 * maxAbsX`），源码注释明写「原实现……会把量级 `1e-10` 的合法输入误判为常量」。即选定的是「让实现符合 javadoc 的尺度不变性声明」这一支。
> - `D-R009-08`（尺度不变性用例）：`service/backtest/FactorResearchEngineTest.java:231` `pearsonCorrelationIsInvariantUnderInputScale`，`scores = i * 1e-11` 断言 `ρ ≈ 1.0`（`offset(1e-9)`）；并配反向守卫 `pearsonCorrelationStillReturnsNaNForConstantInput`，防止被「放宽到永不返 NaN」蒙过去。
> - `D-R010-07`（算术守卫）：`service/research/ResearchConventionTest.java:66` `netReturnDeclarationMatchesTheMultiplicativeImplementationArithmetic`，断言乘性实现与加性读法之差 `-0.00029984`（`offset(1e-12)`）+ 方向 + 公式文本守卫。
> - `D-R009-07`（前端 `null` 不写成 `0.0`）：`modules/web/src/views/factor/FactorResearch.vue:633` `row.pearson_ic_mean ?? ''`。
>
> **更正原因**：初稿照**第一批的旧自述记录**转抄，未回工作树复核；而 `fix_manifest_R001_R015.md` §1 逐行表（第 87/88/95 行）当时写的就是「已修复」——两份档案自相矛盾，正确的一方是 manifest。
> **教训**：台账状态一律以**工作树实况**为准，自述记录都可能滞后。本表余下各行已按此重核一遍。

| 决策项 | 为何未动 | 需要用户做什么 |
|---|---|---|
| `D-R011-03` | **仪器语义裁定**：`CLN` 是「谓词必须为真」还是允许 `INCONCLUSIVE` ⟹ 直接决定本轮总判定是 `REJECTED` 还是回到 `INCONCLUSIVE` | 裁定；冻结文件已零字节变更、`run.log` 保留 5 次运行 |
| `D-R006-01` | 3 处标签补 `Rank` 限定属**前端文案**；Pearson 版 ICIR 需**新增字段**（不改旧语义）——`rank_ic_std_sample` 族已提供数据侧支撑 | 若要动标签，须与 `D-R007-02`/`D-R007-06`/`D-R014-04` 一次性评审 |
| `D-R013-02` | 空组 `avgReturn = 0.0` 仍是占位（已按建议③**文档披露**）；改成 `null` 会影响前端极差计算 | 若采纳建议①，须同时改前端消费点 |
| `D-R005-01` | 运行权威性标记需**新增持久化**（运行输入快照），属 §7 保留项 | 授权后可做 |
| `D-R002-02` | PIT 池与退市收益数据源**本地不具备**（引入新数据源） | 需先解决数据可得性 |
| `D-R011-02`（词表侧） | 把冻结词表改成**词边界匹配**属「修改已冻结口径」；本轮只落地了**规范层**要求（§8.3），**未**改 R011 冻结词表与 `V17` 判定词 | 若要回改冻结词表，须明确授权（会改变历史判定） |

## §5 判为**阻断**（`blocked_queue.md`）

`D-R002-01`（入库覆盖度监控需先定监控口径）、`D-R008-10`（并行 `rankNo` 是否在真实插入路径产生，须先解除 `B-R008-02`）、
`D-R011-03`（见 §4）。**均未**在生产代码中加约束或猜测性修复。

## §6 **未执行**的破坏性 / 需评审项 —— 只给草案，**本轮一律未应用**

> 以下**全部未执行**。给出的 SQL 仅供评审；任何一条执行前都必须先备份并确认回滚路径。

### 6.1 `amount` 单位（`D-R001-01`/`D-R005-03`）——**先取证，再决定是否回填**

本轮只改了 **DDL 注释**（`成交额(元)`→`成交额(万元)`），**未**触碰任何历史数值。核实查询（只读）：

```sql
-- 用「股数 × 收盘价 ÷ amount」判定量纲：≈1e4 倍 ⟹ amount 单位为万元
SELECT trade_date, stock_code, close_price, volume, amount, turnover_rate, source,
       (volume * close_price / NULLIF(amount, 0)) AS implied_shares_per_amount_unit
FROM market_data
WHERE stock_code = '600519' AND trade_date = '2026-06-15';
```

- **期望**：`implied_shares_per_amount_unit` 落在 `[9000, 11000]` ⟹ `amount` 为**万元**。
- 若实测**不**在该区间：说明该行量纲与结论不符，**必须**先查清再决定是否回填，**不得**按假设批量改数。
- 草案（**未执行**）：`UPDATE market_data SET amount = amount * 10000 WHERE ...` 与其逆
  `UPDATE market_data SET amount = amount / 10000 WHERE ...` —— **不可逆迁移**，须先全表备份。

### 6.2 唯一约束（`D-R005-01` / `D-R008-10`）

```sql
-- 执行前必须先跑重复检查；有重复则先定性成因（不得直接删重）
SELECT trade_date, strategy_type, COUNT(*) FROM selector_result GROUP BY 1,2 HAVING COUNT(*) > 1;
```

- 草案（**未执行**）：`ALTER TABLE selector_result ADD CONSTRAINT uk_selector_result UNIQUE (trade_date, strategy_type);`
- **回滚**：`ALTER TABLE selector_result DROP CONSTRAINT uk_selector_result;`
- **前置**：先解除 `B-R008-02`（`rankNo` 并列的真实成因），否则约束可能掩盖而非解决问题。

### 6.3 占位改 `NULL`（`D-R005-02`）

- 草案（**未执行**）：`UPDATE selector_result_item SET some_metric = NULL WHERE some_metric = 0 AND <未计算条件>;`
- **风险**：无法区分「真的是 0」与「未计算」⟹ 执行前必须先用 `NULL` 可判别列（或补「未计算」标志位）替代。
  **本轮选择了「补标志位/披露」路线，未动历史数据。**

### 6.4 无写入路径的列（`D-R006-06`）

- 建议①（**首选，已采纳为文档结论**）：保留并声明「当前无写入路径」。
- 建议②（移除列）为**破坏性 DDL**，**未执行**：`ALTER TABLE ... DROP COLUMN ...;`（回滚需从备份恢复）。

---

## §7 验证证据（三道门 + 双向证明）

| 门 | 命令 | 结果 |
|---|---|---|
| Java | `mvn -o -B test` | **404 passed / 0 failed / 0 error** |
| Python | `pytest tests paper/tests -q`（`modules/compute-python`） | **343 passed** |
| 前端 | `npx vue-tsc --noEmit`（`modules/web`） | **退出码 0，零错误** |
| 新测试双向证明 | 对 `modules/backend-java/src/main/java` 执行 `git stash push` 后跑四个测试类 | **改前：编译失败**（`ScoringUtils.AMOUNT_UNIT`/`liquidityGateMagnitudeDiagnosis`/`round4` 等符号不存在；`ScoringBacktestServiceTest` 21 处符号不存在；`BacktestTurnoverServiceTest` 6 处）⟹ 改后 **53/53 通过** |

> 说明：本轮新增的 17 个测试大量断言**新键与新方法**，其「改前失败」表现为 **testCompile 失败**而非断言失败——
> 这比断言失败是**更强**的守卫（编译期即不可绕过），但性质不同，如实标注，
> **不**把它叙述成「断言级失败」。`FactorResearchServiceTest` 的 7 处 NPE 是本轮改动的**真实回归**
> （`pearsonIcMean` 改可空后，`putSummaryMetricKeys` 的三元表达式把 `(Double)null` 拆箱），
> 已定位并修复为显式 `Double` 局部变量；该回归**恰好**证明既有测试在守这条链路。

### 7.1 新增测试清单（17 个）

| 测试类 | 数量 | 守护对象 |
|---|---|---|
| `ScoringUtilsMinAvgAmountTest` | 7 | `amount` 量纲（万元）、阈值 `30_000d`、量纲诊断四态、噪声容忍 |
| `FactorResearchEngineCostBasisTest` | 10 | 样本标准差 `÷(T−1)` 精确值、`sqrt(T/(T−1))` 恒等式、乘性 vs 加性成本主项差、两个成本模型**非互逆**的闭式差 |
| `BacktestTurnoverServiceTest`（追加） | 6 | 实际分母 vs 请求参数分母、同策略配对、回显归一、`null` 透明、退化分支**同键集** |
| `ScoringBacktestServiceTest`（追加） | 5 | 组数钳位、费率生效值持久化、净/毛价差基准声明、不可恢复时 `null`、无离散度时样本 ICIR `null` |

---

## §8 残留与下一步（按建议动作**未执行**的部分）

1. **~~两项测试被阻断~~（2026-09-15 更正：此条为误记，实际无阻断测试）**：`D-R009-01`（相对判据守卫，`FactorResearchEngine.java:554-561`）、`D-R009-07`（`FactorResearch.vue:633`）、`D-R009-08`（`FactorResearchEngineTest.java:231`）、`D-R010-07`（`ResearchConventionTest.java:66`）**四项均已在第一批落地并通过**，详见 §4.0 更正块。~~**真正需要裁定的只剩 `D-R011-03` 一项**（见 §4），它不涉及任何测试。~~ **2026-09-15 更新：`D-R011-03` 已由用户裁定并执行（选项②+③）⟹ 本清单已无纯裁定项**；执行记录见 `rounds/R011_report.md` 附录 A-4 与 `decision_queue.md` §57。**注意**：裁定**只改变仪器合格性**，不改变任何实测，也**不解决**下面第 2–6 条的任何一项。
2. **前端文案类**：`D-R006-01`（3 处 `ICIR` 标签补 `Rank` 限定——已核实工作树中 `FactorResearch.vue:134/273/372` 仍为裸 `ICIR`，未加限定）/ `D-R007-02` / `D-R007-06` / `D-R014-04` 建议**一次性评审**（同一「标签口径」家族）。
3. **持久化 / 运行权威性类**：`D-R005-01`——同一 `(trade_date, factor_version, topn)` 存在 2–4 个 `result_id`（18 组，覆盖 68.4% 的行），其中 6 组选择集不同、4 组近/完全不相交（2026-05-19、2026-06-30 的 jaccard = 0.000），且**没有任何字段能区分哪一次是权威运行**。三条候选：①新增「运行权威性」标记字段（加法，但队列自身约束写的是「不改任何代码/契约/约束」）、②加业务键唯一约束（须迁移评审，即 §5 的 DDL 草案）、③查明 6 组不相交的成因（需**运行输入快照**＝新增持久化，属 §7 保留权限）。**三条一条未动**。
4. **数据源类**：`D-R002-02`（PIT / 退市收益）依赖外部数据可得性。
5. **破坏性项**：§6 的 SQL 草案**一条都未执行**（`D-R005-01` 唯一约束、`D-R005-02` 占位→`NULL`、`D-R008-10` 并行 `rankNo` 唯一）。另两项同族但非 DDL：`D-R010-04`（`ScoringBacktestService:275-276` 与 `:969-970` 两份**算术完全相同**的入场费站点——有意还是历史遗留，需人工确认）、`D-R010-05`（历史产物是否重算；当前立场=**不重算**，仅登记偏乐观方向 ≈ `3.00 bp of E`）。
6. **两项「部分修复」的数值/口径侧未落地**（2026-09-15 补充登记，此前只写在 manifest §1 状态列，未进本清单）：
   - `D-R007-08`：名义 95% 区间**实测覆盖率显著低于名义**（BOOT 62/80、HAC 60/80、任一至 66/80 单元触发；`min boot=0.344`、`min hac=0.392`，均在 n=5/h=5）。披露侧已落地，**口径变更（是否据此调整区间方法）未动**。
   - `D-R014-02`：多重集相同（`panel_hash` 相同）而**仅把日内并列行序取逆**，组均值即改变（`0.0011333333333333334`），而 `rank_ic` 不变（`maxdiff = 0.0`）——即载荷披露的组均值**依赖未声明的输入行序**。**影响面未测量**（真实行序来自 DB）。是否在契约里**钉住行序**未定。
7. `D-R003-02`（一跳 `0.01/price` 下界是否作为研究文档的**显式对照量**进口径；全池 p50 = 7.0972bp、D1 池内 p50 = 9.2764bp）：**未落地**——已核实 `docs/研究口径规范.md` 无「一跳」字样。属「改冻结口径」类，需授权。
8. 本文件与 `progress.md`、`fix_manifest_R001_R015.md` §2 三处口径必须一致；若后续修订，三处同改。
