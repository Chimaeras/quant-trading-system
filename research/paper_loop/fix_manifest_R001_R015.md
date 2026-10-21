# fix_manifest：R001–R015 决策队列 85 项的落地清单

> 对象：`research/paper_loop/decision_queue.md` 汇总表的 **85 行**（`D-R001-01` … `D-R015-05`）。
> 本清单**逐行**给出：落点（文件:键名）、改法、**是否改变了既有值**、验收测试、状态。
> 生成时间：2026-09-15。基线：`HEAD = 65c80402`（本清单所在提交见 §5）。

---

## §0 授权口径与执行原则（本轮凭什么可以动代码）

**用户授权（两问两答）**

1. 开工范围：「**按 P0 全量排队一次做完**」。
2. 公开 API / 前端契约：「**允许加字段，不改既有键语义（推荐）**」。

**由此推出的可执行原则（本清单每一行都按它分类）**

| 类别 | 是否允许 | 说明 |
|---|---|---|
| **新增键 / 新增 DTO 字段 / 新增前端类型声明** | 允许 | 用户明确授权「加字段」 |
| **纯数值纠正**（同键、同含义、同类型、同值域） | 允许，但**必须披露 + 有测试** | 例如「非有限值不再伪装成 0」「计数只统计有限观测」 |
| **声明文本纠正**（javadoc / 载荷 note / 前端 label / docs 公式文字） | 允许 | 声明与实现不一致时，改文本对齐实现（行为零变化） |
| **改既有键名 / 类型 / 值域 / 语义** | **禁止** | 须改为"新增键 + 保留旧键" |
| **需域判断的口径选择**（哪个口径为准、分母用谁、单位是元还是万元…） | **禁止自行决定** | 排入 §2 待用户拍板 |
| 数据库结构 / 迁移 / 数据源引入 / 冻结成本口径变更 / 真实交易 | **禁止** | 协议 §7 record-only |

**红线自查（协议 §8）**：本轮未删除或放宽任何既有测试断言、未用 Mock 掩盖错误、未把全样本最优当结论、未把 `rule_score` 表述为上涨概率、未引入做空/日内/高频、未操作本仓库以外的任何仓库、未启用真实交易或索取真实凭证、未因"多轮无产出"降低标准。测试**只增不减**（Java 350 → 375）。

---

## §1 逐行落地清单（85 行，按队列顺序）

**状态词表**：
- `已修复` = 本轮落地了该项建议动作中的**可行子集**（多数是"披露/声明/最小动作"那一条），行为对既有消费方向后兼容；
- `部分修复` = 已落地披露或最小动作，但**数值/语义的那一半需要你拍板**；
- `待拍板` = 本轮**未动**，理由与候选方案见 §2；
- `不做` = 已明确判定为「反例登记」或「用户已决策无需动作」。

| ID | 落点（文件 · 键） | 改法要点 | 改既有值？ | 状态 |
|---|---|---|---|---|
| D-R001-01 | `market_data.amount/volume` 单位 | 未动（须先定真实单位才能写断言） | — | 待拍板 |
| D-R001-02 | 生产打分权重 `er1 −0.15` | 未动（属改权重，§7） | — | 待拍板 |
| D-R001-03 | 论文版本 DOI | 未动（研究载体标注，无紧急动作） | — | 待拍板 |
| D-R001-04 | — | 本轮无因子进影子，无请求 | — | 不做（RESOLVED） |
| D-R002-01 | 入库覆盖度监控 | 未动（新增监控需你定阈值与告警面） | — | 待拍板 |
| D-R002-02 | PIT 池 / 退市收益 | 未动（引入新数据源，§7） | — | 待拍板 |
| D-R003-01 | `FactorResearchService.LONG_SHORT_SPREAD_BASIS(+_NOTE)` | 新增 `long_short_spread_basis="TWO_LONG_LEG_NET_DIFFERENCE"` + note 声明"两多头腿净差、非美元中性、成本结构性抵消"；前端标签改「多空 spread（高−低）」 | **否**（纯新增键；旧字段语义与数值零变化） | 部分修复（建议①已落地；②真双腿字段待拍板） |
| D-R003-02 | 有效价差一跳下界 | 未动（须你裁定是否作为研究文档显式对照量） | — | 待拍板 |
| D-R004-01 | 真双腿中性 spread | 未动（与 D-R003-01 合并评审；两条候选公式 24bp/30bp 不一致） | — | 待拍板 |
| D-R004-02 | `ScoringBacktest.vue` 多空卡片 | 补「口径」四项标注：持有 N 日 · 单期非年化 · 成本后近似 · 未减基准 | 否（纯展示层） | 已修复 |
| D-R004-03 | `FactorResearchService.emptyNetIcBlock()` | 新增 `status="NO_OBSERVATIONS"` + `data_available=false`；占位 `0.0` **保留** | 否（只加键） | 已修复 |
| D-R005-01 | `selector_result` 运行权威性 | 未动（需你选运行标记字段 or 业务键唯一约束） | — | 待拍板 |
| D-R005-02 | `selector_result_item.avg_amount20` | 未动（占位 0 改 `NULL` 是数据迁移） | — | 待拍板 |
| D-R005-03 | `selector_result_item.min_avg_amount` | 未动（与 D-R001-01 同源量纲，须一并裁定） | — | 待拍板 |
| D-R006-01 | `FactorResearch.vue` / `ScoringBacktest.vue` IC 标签 | 3 处标签补 `Rank` 限定（Rank IC 均值 / Rank IC 标准差 / ICIR 保留 + 无定义 tooltip） | 否（展示文本） | 已修复（建议①） |
| D-R006-02 | `FactorResearchService.RANK_IC_STD_BASIS(+_NOTE)` | 新增 `rank_ic_std_basis="POPULATION_STD_DIV_T"` + note 声明与 ÷(T−1) 的比值恒为 √(T/(T−1))；**数值未改** | 否 | 部分修复（建议①；改口径待拍板） |
| D-R006-03 | `RankIcSummary` / 载荷 `rank_ic` | 新增 `input_cross_section_days` / `days_dropped_below_min_cross_section` / `days_dropped_non_finite_ic` | 否 | 已修复 |
| D-R006-04 | `FactorResearchEngine.groupedAverageReturns` 等 | 空组不参与 spread 与方向断言，组行加 `empty` 标记；前端显示「空组（无观测）」 | **是**（仅退化输入路径下 spread 数值由错误的 `0−G1` 变为只用非空组） | 已修复 |
| D-R006-05 | 引擎 + 载荷 + `FactorIcSummary.rankIcIr` + DTO + 前端 | ICIR 无定义（std=0，含 T<2）时 `0.0 → null`（引擎、`rank_ic_ir`、DTO 字段改可空 `Double`、前端 `fmtIcIr` 渲染「不可用（IC 无离散度）」） | **是**（无定义情境下 `0.0` → `null`，含 DTO 面） | 已修复 |
| D-R006-06 | `selector_result.ic_value` 死列 | 未动（补文档 or 破坏性 DDL 须你选） | — | 待拍板 |
| D-R006-07 | `FactorResearchEngine.assessMonotonicity` | 三守卫 + 新增枚举值 `NO_DISPERSION`（全相等 = 零区分度，不是任何方向） | **是**（该情境下标签由 `MONOTONIC_INCREASING` 变 `NO_DISPERSION`） | 已修复 |
| D-R007-01 | 载荷 `ci_status` | 新增 `ci_status`：`OK` / `INSUFFICIENT_N`（n<2）/ `ZERO_WIDTH`；前端加标记 | 否（`hac_std`/`ci_*` 数值保留，只是加了状态） | 已修复 |
| D-R007-02 | `FactorResearch.vue` CI tooltip | 声明「SE 为 HAC（滞后阶由 n 定）、区间来自 block bootstrap、为**名义**水平」 | 否（展示文本） | 已修复（建议③） |
| D-R007-03 | `FactorResearchService.buildRankIc` 早返回分支 | 早返回分支发射与正常路径**同键集**（补 `pearson_ic_mean`/`monotonicity`/`hac_std`/`ci_lower`/`ci_upper` + `status`） | **是**（空输入时这些键由"缺失"变为"存在且为 null"，键集不再随数据可用性变化） | 已修复 |
| D-R007-04 | `RANK_IC_EMBARGO_NOTE` | 补「HAC 滞后阶只由 n 定、与 h 解耦；区间为名义水平、未做多重检验校正」 | 否（声明文本） | 已修复（建议②） |
| D-R007-05 | `FactorResearchEngine.blockBootstrapCi` javadoc | javadoc 改为实际有效块长 `bs = max(1, min(min(h, ⌊√n⌋), n−1))` | 否 | 已修复（建议①） |
| D-R007-06 | `FactorResearch.vue.exportUnifiedCsv()` | 表头 token 改 `cost_net_rank_ic_mean`，其后三列加 `gross_` 前缀，并加口径说明行 | 否（导出列名属展示面；值来源未变） | 已修复 |
| D-R007-07 | 载荷 note + 前端 tooltip + CSV 说明行 | 显式声明 95% 区间为**名义**水平、未做多重检验校正；未引入 3.0/3.9 门槛 | 否 | 已修复 |
| D-R007-08 | 前端区间说明 | 加「重叠持有期下实际覆盖率可能显著低于 95%（名义水平）」；改统计口径未动 | 否 | 部分修复（披露已落地；口径变更待拍板） |
| D-R007-09 | `FactorResearchService.round4` (+ `ROUNDING_RULE_NOTE`) | 非有限值（NaN/±Inf）一律序列化为 `null`（原为 `NaN→0.0`、`±Inf→9.223372036854776E14`），并在 note 中明写 | **是**（非有限值路径；有限值不变） | 已修复 |
| D-R008-01 | `BacktestTurnoverService` 分母 | 新增 `denominator="REQUEST_PARAM_TOP_N"` + `denominator_note`，并逐日暴露 `intersection_count` 使分母可复算；**分母未改** | 否（数值零变化，纯披露） | 部分修复（建议③；改分母待拍板） |
| D-R008-02 | `BacktestTurnoverService` javadoc | javadoc 改为承认短员日照旧输出（去掉"未被强制的前置条件"），并指向 `prev_size`/`curr_size` 披露键 | 否 | 已修复（建议①） |
| D-R008-03 | 换手率载荷 `turnover_status` | 新增机器可读 `turnover_status`：`OK` / `INSUFFICIENT_SIGNAL_DAYS`；占位 `0.0` **保留**（未改成 null） | 否（只加键） | 已修复（建议①的前半） |
| D-R008-04 | 换手率载荷 `strategy_filter` / `grouped_by_strategy` / `strategy_filter_note` | 声明 `strategy` 为空时**不按策略过滤、也不分组**（同日跨策略会互相配对）；并**人工确认**：前端唯一调用点 `ScoringBacktest.vue:runTurnover` 默认 `form.strategyType='' → undefined` ⟹ 该路径是**界面默认路径** | 否（行为未改；只加了声明） | 部分修复（建议③；改必填/改分组待拍板） |
| D-R008-05 | 同上 `strategy_filter_note` | 明写 `""` 与 `null` 走同一分支、计算逐位相同、仅回显不同（`"ALL"` vs `""`） | 否 | 部分修复（建议②；归一待拍板） |
| D-R008-06 | `daily_turnover[]` 逐日行 | 新增 `prev_size` / `curr_size` / `intersection_count` / `short_handed`；前端加「前日/当日候选」列（短员日标黄） | 否（纯新增键） | 已修复 |
| D-R008-07 | 换手率载荷 `turnover_basis` / `avg_turnover_unit` / `rounding_rule` (+notes) | 新增结构化口径键：`turnover_basis="ONE_WAY_REPLACEMENT_RATIO"`（并声明双向口径约为 2 倍、**不主张**单向有误）、`avg_turnover_unit="RATIO_0_1"`、`rounding_rule="ROUND_HALF_UP_4DP"` | 否 | 已修复 |
| D-R008-08 | 换手率 vs `RollingExperimentService` | 未动（须人工定稿"两个换手率不是同一个量"的命名与文档口径） | — | 待拍板 |
| D-R008-09 | `BacktestTurnoverService` + `DataService` + 前端标签 | 两面各新增单位键：回测面 `avg_turnover_unit="RATIO_0_1"`（候选替换率 0–1）、个股行情面 `avg_turnover_unit="PERCENT"`（市场换手率%）；回测面标签改「平均换手（单向替换率）」 | 否（纯新增键 + 展示文本） | 部分修复（建议②；统一改名待拍板） |
| D-R008-10 | `selector_result_item.rankNo` | 未动（唯一约束 = DDL 变更；显式排序 = 改并列行为） | — | 待拍板 |
| D-R009-01 | `FactorResearchEngine.pearson` 退化判据 | 守卫由**绝对**量 `Σ(x−x̄)² > 1e-18` 改为**相对**判据 `maxAbsDx <= 1e-12 · maxAbsX`；常数输入仍返回 NaN | **是**（极小量级输入下由 NaN 变为正确相关系数，含既有测试字面输入 ×1e-10 的情形） | 已修复 |
| D-R009-02 | 载荷 `pearson_support_days` | 新增"Pearson IC 可定义的截面日数"计数键；`pearson_ic_mean` 的 `? 0` 占位**保留未改** | 否（只加键） | 部分修复（建议③最小动作；占位改 null 待拍板） |
| D-R009-03 | 引擎 javadoc / 勘误 | 代码侧明写「秩侧有限性与 Pearson 侧有限性**不等价**」；**未**改历史 `R006_report.md`（A4b 是"等价"还是"单向蕴含"须你裁定） | 否 | 部分修复（报告勘误待拍板） |
| D-R009-04 | 载荷 `pearson_support_days` | 同 D-R009-02，使混合支撑集面板的 `support_gap` 可直接读出 | 否 | 已修复 |
| D-R009-05 | 后端早返回 + 前端 `factor.ts` | 后端补齐同键集；前端 5 键改**可选**并在消费处加缺测分支（`—` 显示） | 否 | 已修复 |
| D-R009-06 | `emptyNetIcBlock` / `unavailableScopeBlock` | 统一为**显式** `status` 键（`NO_OBSERVATIONS` / `UNAVAILABLE`），不再靠"缺键"表达不可用 | 否（新增 `status`，旧键全保留） | 已修复（建议①） |
| D-R009-07 | `exportUnifiedCsv()` | 表头加 `gross_`/`cost_` 前缀 + 取值 null-safe（`row.rank_ic_ir ?? ''`）+ `cost_net_ic.data_available===false` 时留空而非写 0.0 | 否 | 已修复 |
| D-R009-08 | `FactorResearchEngineTest` | 新增尺度用例（`scores` 取 `1e-11` 量级 → 断言 ρ 仍为 1.0）+ 常数输入仍 NaN 用例 | 否（测试） | 已修复 |
| D-R010-01 | `ResearchConvention` javadoc + `labelDefinition()` + `docs/研究口径规范.md` + `docs/API文档.md` + 前端提示 | 统一改为与实现同构的**两级乘性**：`(1−slippage)(1−commission−sell_tax) / (1+slippage)(1+commission)`；并修正"卖出因子不含佣金"那条**可被实测证伪**的归属声明 | **是**（仅**声明文本**；成本口径本身是冻结量，未改任何数值/历史结论） | 已修复（方案①） |
| D-R010-02 | `RANK_IC_NET_BASIS_NOTE`（进载荷 `basis_note`） | 改写为两级乘性式 + 记法声明（"本节记法为两级乘性，与实现同构"） | 否（载荷文本，数值不变） | 已修复 |
| D-R010-03 | `FactorResearchService:352` 注释 | 符号唯一化（滑点与卖出印花税不再共用 `s`）+ 改为乘性式 | 否 | 已修复（建议①） |
| D-R010-04 | `ScoringBacktestService` 入场费两副本 | 未动（两处算术完全相同；是否有意删一份须你确认） | — | 待拍板 |
| D-R010-05 | `run_reversal_ic.py` / `run_cost_breakeven.py` | 未动（重算历史净收益会改既有报告数值，须你裁定） | — | 待拍板 |
| D-R010-06 | `boundedRate` 钳位标记 | 未动（端到端证据受 `B-R010-01` 阻塞；且"计算取钳位值、持久化取原始值"的统一方向属改行为） | — | 待拍板 |
| D-R010-07 | `ResearchConventionTest` | 新增算术守卫：断言两级乘性卖出因子与加法记法之差 **= −0.00029984**（主项 −c = −3.00bp of E，与 §48 登记值逐位一致）+ 方向断言 + 文本子串守卫（回退加法式/漏佣金即失败） | 否（测试） | 已修复 |
| D-R011-01 | 三族载荷 + `FactorResearchService` 常量 | 三族发射 `min_cross_section_size`(+`_basis="INPUT_ROWS"`)，snapshot 族补 `group_count`；抽共享常量消除结构层分歧 | 否（纯新增键） | 已修复（建议①+②） |
| D-R011-02 | 冻结词表 A7 匹配方式 | 未动（改冻结词表须你确认） | — | 待拍板 |
| D-R011-03 | `CLN`/`INJ` 仪器语义（事后修改） | 未动（须你复核认可或回退） | — | 待拍板 |
| D-R011-04 | "备选集合"定义 | 未动（提升为项目级规范 = 改研究口径） | — | 待拍板 |
| D-R012-01 | `FactorResearchService` 类 javadoc | 词表补齐 **5 值**（RAW / COST_APPROX / EXECUTABLE / FILLED_SAMPLE / UNAVAILABLE）+ 说明"同名 RAW 在两个面指不同价格基准" | 否 | 已修复 |
| D-R012-02 | 载荷 `price_basis` + 前端标签 | 两面各加 `price_basis`（`QFQ_CLOSE` / `RAW_CLOSE`）+ note；前端「价格基准」标签 | 否（纯新增键） | 已修复（建议②） |
| D-R013-01 | 同 D-R006-07 | 零斜率不再断言方向 | **是**（同 D-R006-07） | 已修复 |
| D-R013-02 | 同 D-R006-04 | 空组占位零不再参与方向断言（`factorGroupCount=20 / n=10` 的场景） | **是**（同 D-R006-04） | 已修复 |
| D-R013-03 | `ScoringBacktestRequest.factorGroupCount` | 未动（加 `@Min/@Max` 会拒绝既有合法输入，须你决定上限与是否钳位标记） | — | 待拍板 |
| D-R013-04 | 载荷 `avg_return_exact` / `rank_ic_exact` | 新增未取整键，使"标签可由载荷自身重建"；标签本身未改 | 否（纯新增键） | 已修复 |
| D-R013-05 | `factor.ts` | `monotonicity: string` → 字面量联合类型 `MonotonicityLabel`（5 值，含 `NO_DISPERSION`） | 否（类型更严，运行期无变化） | 已修复 |
| D-R013-06 | `ScoringBacktestResponse.FactorAnalysis` | 新增 `monotonicity` 字段（`FactorIcSummary.rankIcIr` 同步改可空）；服务映射链补 `.monotonicity(...)` | 否（纯新增字段） | 已修复 |
| D-R013-07 | `buildNetIcBlock` | 补 `group_count` + `grouped_returns`（与另两块披露粒度对齐） | 否（纯新增键） | 已修复 |
| D-R013-08 | 载荷 `group_count` + 前端 | 标签依赖组数参数这一点改为**可读出**（`group_count`/`min_cross_section_size` 已进载荷，前端 tooltip 说明）；默认组数未改 | 否 | 已修复（建议①） |
| D-R013-09 | `assessMonotonicity` 非有限守卫 | 组均值非有限时标签为 `N/A`（不再因 NaN 比较恒假而输出方向） | **是**（非有限路径下标签变化） | 已修复 |
| D-R014-01 | 载荷 `group_boundary_rule`(+note) | 声明 `INDEX_EQUAL_SPLIT_STABLE_SORT`（下标等分 + score 升序稳定排序） | 否 | 已修复 |
| D-R014-02 | `group_boundary_rule_note` | 声明"并列 score 的相对次序不作保证（取决于上游行序），分组收益可能微变、`rank_ic` 不受影响" | 否 | 已修复（建议②；钉住行序待拍板） |
| D-R014-03 | 载荷 `grouped_returns_observations_basis`(+note) + DTO javadoc | 声明聚合面 `observations` = **参与聚合的截面日数**，日级面同名字段 = **成分观测数**；数值与键名均未改 | 否 | 已修复（建议③） |
| D-R014-04 | `FactorResearch.vue` / `ScoringBacktest.vue` | 两面统一「截面日数」+ 限定词 + tooltip（"参与该组均值聚合的截面日数，不是成分股数"） | 否（展示文本） | 已修复 |
| D-R014-05 | `round4`（同 D-R007-09） | `NaN` 组均值由 `0.0` 改为 `null`（因子面与 DTO 面策略一致） | **是**（非有限路径） | 已修复 |
| D-R014-06 | 引擎计数 + 载荷计数键 | 非有限 `forwardReturn` 不计入 `sample_size`/`total_observations`，并暴露 `excluded_observations` / `excluded_non_finite_observations` | **是**（含脏数据面板的计数字段变准） | 已修复 |
| D-R014-07 | 载荷 `days_dropped_below_min_cross_section` + `min_cross_section_size` | 规模不足丢弃日与阈值不再静默 | 否（纯新增键） | 已修复 |
| D-R014-08 | 载荷 `rounding_rule` + `*_exact` | 取整规则与未取整值可读，两面数值差异可复核；"DTO 承载原始值"未改 | 否 | 部分修复（建议③；统一策略待拍板） |
| D-R014-09 | 载荷 `rounding_rule`(+note) | 声明 4 位小数四舍五入 + `*_exact` 键 + 非有限→null | 否 | 已修复 |
| D-R014-10 | 冻结表 reachability 等级 | 未动（装置层，须你决定是否纳入预注册冻结表） | — | 待拍板 |
| D-R015-01 | 载荷 `universe_count_source` / `_fallback` / `_note` | 回退改为**具名标记**：`FALLBACK_TOTAL_OBSERVATIONS` + 布尔 + note「不得读作真实候选池规模」 | 否（纯新增键） | 已修复 |
| D-R015-02 | `funnel.metric_scope_note` | 声明本块 `COST_APPROX` 是**家族继承值**、块内只有样本覆盖口径数值、同名 metric_scope 在不同面是不同语义面 | 否（取值域未改） | 已修复 |
| D-R015-03 | `funnel.key_alias_note` | 声明两对别名键是"同一变量两个键名"，不是两个量 | 否 | 已修复 |
| D-R015-04 | `factor.ts:FactorResearchFunnel` | 补齐 `universe_days_with_snapshot` / `cross_section_days` / `universe_count_source` / `universe_count_fallback` / `universe_count_note` / `universe_source` / `valid_observations` / `total_observations` / `key_alias_note` / `rounding_rule` / `tradable_note` 等键声明 + `coverage` 单位说明 | 否（类型声明） | 已修复 |
| D-R015-05 | — | **反例登记**：`coverage` 阈值与百分比刻度一致，**无**阈值错配。本轮未把它写成任何"发现" | — | 不做（防重复提交） |

**计数**：已修复 **52** · 部分修复 **10** · 待拍板 **21** · 不做 **2** = **85**。

---

## §2 待拍板事项的**处置**（2026-09-15 第二批：裁定后全量落地）

> 用户裁定：**Q1「按 P0 全量排队一次做完」**、**Q2「允许加字段，不改既有键语义」**，
> 并指示「按你的分析取最合适的做法去进行改动」⟹ §2.1 的 12 组待定项**已全部选定方案并落地**。
> 完整逐项记录（含**未落地项与原因**）见 **`decisions/round_closeout_R001_R015.md`**。

### §2.0 选定方案与落地证据（对应 §2.1 的每一行）

| ID | **选定方案** | 落地证据 |
|---|---|---|
| D-R001-01 / D-R005-03 | ① 先写**单位断言测试**，再按实测把阈值改为 `30_000d`（万元口径）；**回填历史数据不做** | `ScoringUtils.DEFAULT_MIN_AVG_AMOUNT`、`AMOUNT_UNIT`、`liquidityGateMagnitudeDiagnosis`、`ScoringUtilsMinAvgAmountTest`（7 例）；DDL 仅改注释 |
| D-R003-01 / D-R004-01 | ② **新增并行字段**（美元中性净价差 + 毛等价物 + 基准声明），**旧字段语义零改动** | 载荷 `long_short_spread_neutral` 族；DTO `longShortSpreadCostBasis`/`longShortSpreadGrossEquivalent`；`FactorResearchEngineCostBasisTest` |
| D-R006-02 | ① **维持 `÷T`**（主口径未动），并列发射 `÷(T−1)` 读法 | `rank_ic_std_sample`/`rank_ic_ir_sample` 族 + `rank_ic_std_sample_basis(_note)` |
| D-R008-01 | ① **保持请求参数分母不变**，并列发射「按当日实际规模归一」口径 + 逐日可复算三元组 | `turnover_actual_denominator`、`denominator_actual_value`、聚合 avg/max/min、`pairs_dropped_undefined_actual_denominator` |
| D-R008-04 / D-R008-05 | ③ **维持计算行为**，回显归一为 `strategy_requested`/`strategy_effective`（旧 `strategy` 兼容保留） | `BacktestTurnoverServiceTest` 追加 6 例 |
| D-R008-08 | ① **命名 + 文档口径**：定稿「四个『换手率』不是同一个量」 | `docs/研究口径规范.md` §8.1（含四行对照表与分母系统性高估说明） |
| D-R008-09 | ② **不改键名**（避免动 `×100` 换算点），补单位键 + 文档命名规则 | `avg_turnover_unit=RATIO_0_1`、`denominator`、`denominator_basis`；§8.1 第 3/4 条 |
| D-R009-02 | ① **改可空**（`0.0 → null`）+ 并列发射支撑日数 | `pearson_ic_mean`（`Double`）、`pearson_support_days`、`pearson_ic_mean_note`；DTO `pearsonIcMean` 可空 |
| D-R009-03 | ① 按本轮实测**写勘误**（等价为假），并**精确界定**「只证伪等价主张、未证伪 A4b 判定」；方向裁定留给用户 | `rounds/R006_report.md` **附录 A-6 / E-6** |
| D-R013-03 | ② **DTO 层钳位 + 显式标记**（避免「算用钳位值、存用原始值」） | `clampFactorGroupCount` + `factor_group_count_requested/_effective/_clamped` |
| D-R010-06 | ① **加 `*_clamped` 标记**，并 ② **统一取生效值**（缺一仍会两面不一致） | `EffectiveExecutionParams`、`commission_rate_requested` 等 3+3 键、`persistRun` 改存生效值 |
| D-R014-08 | ① **DTO/载荷承载原值 + 展示层取整**；载荷并列 `*_exact` 并声明取整规则 | `precision_policy` + `rounding_rule` + `*_exact` 键族 |

### §2.1 原始待定问题清单（保留，供追溯当时的分歧点）

| ID | 待定问题 | 候选方案 | 影响面 |
|---|---|---|---|
| D-R001-01 / D-R005-03 | `amount` 究竟是**元**还是**万元**？`min_avg_amount` 的 30000 与 30000000 是否同一阈值的两种量纲 | ①先写单位断言测试再改阈值；②以 DDL 注释为准；③以数据自洽（千元）为准 | 质量闸阈值、`avg_amount20` 口径 |
| D-R003-01 / D-R004-01 | `long_short_spread` 是否要新增并行"真双腿美元中性"字段？若加，用哪条公式（两条候选差 24bp/30bp） | ①只保留现有披露；②新增并行字段（不动旧字段） | 载荷 + 前端 + 报告结论（OOS 符号可能翻转） |
| D-R006-02 | ICIR 分母取 `÷T` 还是 `÷(T−1)` | ①维持 `÷T`（已声明）；②改 `÷(T−1)`（须迁移评审，量级 +3%～+5%） | 全部历史 ICIR 数值 |
| D-R008-01 | 短员日换手率分母取**请求参数** `top_n` 还是**当日实际规模** | ①维持请求参数（已披露）；②改 `min(prev,curr)`；③不适用时输出 `UNAVAILABLE` | 全部历史换手率数值（实测偏差可达 0.70） |
| D-R008-04 / D-R008-05 | `strategy` 是否改必填 / `null` 时是否按策略分组 / `""` 是否归一为 `null` | ①改必填（改契约）；②按策略分组分别算（改行为）；③维持 + 现有声明 | 换手率默认路径行为 |
| D-R008-08 | "两个换手率不是同一个量"的命名与文档定稿 | ①命名区分 + 文档口径；②统一到某一侧 | 全仓"换手率"表述 |
| D-R008-09 | 是否把 `avg_turnover` 统一改名（`_pct` / `_ratio`） | ①改名（改契约 + 前端换算点）；②维持 + 已加单位键 | 两个生产面 + 前端 |
| D-R009-02 | `pearson_ic_mean` 占位 `0` 是否改 `null`（与 `round4` 的 NaN 语义联动） | ①改可空；②维持 + 已有 `pearson_support_days` | 载荷 + 前端 |
| D-R009-03 | `R006 A4b` 的"秩侧有限 ⟺ Pearson 有限"是**等价**还是**单向蕴含**？ | ①按本轮实测写勘误（等价为假）；②按单向蕴含处理（未被证伪） | `R006_report.md` 勘误 |
| D-R013-03 | `factorGroupCount` 是否加 `@Min/@Max` 上限 | ①加上限（会拒绝既有合法输入）；②DTO 钳位 + 标记；③维持现状 | 直接 API 调用方 |
| D-R010-06 | `boundedRate` 钳位是否加显式标记 / 是否统一计算与持久化取同一值 | ①加 `*_clamped` 标记；②统一取钳位值（改行为） | 回测请求-响应契约 |
| D-R014-08 | 取整策略统一到哪一侧 | ①DTO/载荷承载原始值 + 展示层取整；②两面同用 `round4` | DTO 面 65 个被截断的值 |

### §2.2 属协议 §7 record-only —— 第二批的处置结果

> 第二批在 Q1/Q2 授权下**逐条重走**了本节。结论：**声明类全部落地**，**破坏性/裁定类仍然不做**。
> 逐项状态见 `decisions/round_closeout_R001_R015.md` 的 §2（record-only 已处置）、§4（需裁定）、§5（阻断）、§6（SQL 草案未执行）。

| 族 | 状态 |
|---|---|
| **数据库结构**：D-R005-01（唯一约束）、D-R005-02（占位→`NULL`）、D-R006-06（死列）、D-R008-10（`rankNo` 唯一） | **仍未动**（破坏性 DDL / 数据迁移）。D-R006-06 **未完全落地**（2026-09-15 更正）：建议①的「保留」侧成立、「**补文档声明**」侧**只写在研究记录里**（本文件、`progress.md`、`decision_queue.md`），**项目级文档与 DDL 均无此声明**——已核实 `V2__research_tables.sql:183` 的 `ic_value` 无注释、`docs/` 下无「死列」字样。若要按建议①收口，需在项目级文档补一条；其余给草案（dossier §6.2/6.3/6.4），**一条未执行** |
| **数据源 / 数据基建**：D-R002-01、D-R002-02 | **仍未动**（需先定监控口径 / 本地无 PIT 与退市收益数据） |
| **研究口径 / 冻结词表**：D-R011-02、D-R011-04、D-R012-01 | **已落地**：D-R011-04→`docs/研究口径规范.md` §8.2；D-R011-02→§8.3（**规范层**强制词边界/子串假阳性检查，**未**回改 R011 冻结词表与 `V17` 判定词）；D-R012-01→类 Javadoc 补全 5 值 + §8.4 |
| **装置层（元项）**：D-R011-03、D-R014-10 | D-R014-10→§8.6 **已落地**；D-R011-03 **仍需裁定**（决定总判定是 `REJECTED` 还是回到 `INCONCLUSIVE`，**agent 不得自行改判**） |
| **历史产物重算**：D-R010-05 | **仍未动**（不重算任何历史结论；仅加勘误说明偏乐观方向与量级 ≈ `3.00 bp of E`） |
| **有意/遗留判定**：D-R010-04 | **仍未动**（需人工确认两份同构副本是有意还是遗留） |
| **研究载体**：D-R001-03 | **已落地**：`rounds/R001_report.md` §1 结论处**同时标注** WP 24458（B 级，实读）与 JFE 2019（A 级，DOI） |
| **打分权重**：D-R001-02 | **仍未动权重**（影子先行）；D-R001-04 无提升请求 |
| **新增落地（不在原 85 项）** | 价差毛/净同名不同量的基准声明；两个成本模型**非互逆**的闭式差；乘性 vs 加性成本主项差 |

---

## §3 本轮明确"不做"的两项

| ID | 为什么不做 |
|---|---|
| D-R001-04 | 队列状态已是 `RESOLVED`：本轮无因子进入影子，无提升请求。 |
| D-R015-05 | **反例登记**：`coverage` 的百分比刻度与前端阈值 80/50 **一致**，不存在阈值错配。台账要求"明确登记为不得写成发现"，故本轮既未改代码也未把它当新发现。 |

---

## §4 验证证据

### §4.1 测试增量（只增不减）

| 套件 | 第一批改前 | 第一批改后 | **第二批（本次）** | 命令 |
|---|---|---|---|---|
| Java（`mvn -o -B test`） | 350 | 375（+25） | **404 passed / 0 failed / 0 error**（+29：`ScoringUtilsMinAvgAmountTest` 7 + `FactorResearchEngineCostBasisTest` 10 + 既有两测试类追加 11 + 第一批其余） | `cd modules/backend-java && mvn -o -B test` |
| Python（`pytest -q`） | 343 | 343 | **343 passed / 3 warnings**（未动） | `cd modules/compute-python && python -m pytest tests paper/tests -q` |
| 前端类型检查 | clean | clean | **clean（退出码 0，零错误）** | `cd modules/web && npx vue-tsc --noEmit` |
| 前端构建 | ok | ok | 见第一批记录（第二批未改构建配置） | `cd modules/web && npm run build` |

> **第二批新增测试的双向证明（`git stash`）**：对 `modules/backend-java/src/main/java` 执行
> `git stash push -- modules/backend-java/src/main/java` 后跑四个测试类 ⟹ **改前**：
> `ScoringUtilsMinAvgAmountTest` 报 `AMOUNT_UNIT`/`MIN_AVG_AMOUNT_UNIT`/`DEFAULT_MIN_AVG_AMOUNT`/
> `LIQUIDITY_GATE_*`/`liquidityGateMagnitudeDiagnosis`/`liquidityGateDiagnosisNote` 符号不存在；
> `FactorResearchEngineCostBasisTest` 同理；`ScoringBacktestServiceTest` **21 处**符号不存在；
> `BacktestTurnoverServiceTest` **6 处**符号不存在（含 `round4` 仍为 `private`）。
> **改后**：四类合计 **53 passed / 0 failed**。
> ⚠️ 如实标注：新测试断言的是**新键与新方法**，故其「改前失败」表现为 **testCompile 失败**而非断言失败——
> 这是**更强**的编译期守卫，但性质不同，**不得**叙述成「断言级失败」。

> ⚠️ **第二批的真实回归（已修复，如实登记）**：`pearsonIcMean` 改可空后，
> `FactorResearchService.putSummaryMetricKeys` 的三元表达式
> `sampleStd == null ? (Double) null : mean / sampleStd` 因**结果类型退化为 `double`** 而拆箱 `null`，
> 触发 7 处 NPE（`FactorResearchServiceTest`）。已改为显式 `Double` 局部变量修复 ⟹ 既有测试**确实守住了**这条链路。

### §4.2 新增测试清单（对应本清单的落点）

- `FactorResearchEngineTest`（+9）：`pearsonCorrelationIsInvariantUnderInputScale`、`pearsonCorrelationStillReturnsNaNForConstantInput`、`monotonicityReturnsNoDispersionWhenAllGroupsEqual`、`monotonicityIgnoresEmptyGroups`、`monotonicityReturnsNaWhenGroupMeanIsNonFinite`、`groupedReturnsExcludeNonFiniteObservations`、`summarizeExcludesNonFiniteObservationsAndKeepsTheDay`、`summarizeSpreadIgnoresEmptyGroupsBeyondSampleSize`、`summarizeLeavesIcIrUndefinedWhenIcSeriesHasNoDispersion`。
- `FactorResearchEngineDisclosureTest`（新建，5）：`summarizeReportsTheWholeSampleFunnel`、`allDaysDroppedStillReportsWhyTheyWereDropped`、`allDaysNonFiniteIcAreCountedEvenWhenNothingIsComputable`、`emptyInputReportsZeroCountersInsteadOfGuessing`、`dailyRowsCarryDropCountsAndSummaryGroupCountsAreDayCounts`。
- `FactorResearchServiceTest`（+3）：`rankIcEmptyBranchEmitsTheSameKeySetAsThePopulatedBranch`、`rankIcBlockDisclosesConstructionRulesAndUndefinedIr`、`legacyFunnelDisclosesUniverseFallbackAliasesAndInheritedMetricScope`。
- `ScoringBacktestServiceTest`（+1）：`factorIcSummarySerializesUndefinedIcIrAsJsonNull`。
- `BacktestTurnoverServiceTest`（+6）：`shortHandedDaysExposeActualSizesAndTheRequestParamDenominator`、`fullRosterDaysAreNotFlaggedShortHanded`、`disclosureKeysAreEmittedInTheUndefinedBranchToo`、`definedAndUndefinedBranchesEmitTheSameDisclosureKeySet`、`strategyFilterEchoReflectsTheActualFilteringMode`、`crossStrategyPairingIsDisclosedWhenStrategyIsNull`。
- `ResearchConventionTest`（+1）：`netReturnDeclarationMatchesTheMultiplicativeImplementationArithmetic`。

### §4.3 「真守卫」双向证明（`git stash`）

**方向一：把主代码改动 stash 掉、保留新测试 → 新测试必须失败。**
在 `HEAD=65c80402` 的旧代码上运行上述测试（并把新测试文件临时移出以让旧代码可编译）：

- `ResearchConventionTest.netReturnDeclarationMatchesTheMultiplicativeImplementationArithmetic` —— **1 失败**；
- `BacktestTurnoverServiceTest` —— **6 失败**（全部为新增的 6 条）；
- `FactorResearchServiceTest` —— 3 失败，其中新漏斗用例给出：`expected: "FALLBACK_TOTAL_OBSERVATIONS" but was: null`（证明 `universe_count_source` 等键确为新增）；
- 早前已证明：引擎层 10 条新测试在旧代码上全部运行期失败，证据包括 `expected: 4 but was: 6`（非有限观测被计入）、`expected "MONOTONIC_DECREASING" but was "NON_MONOTONIC"`（空组污染方向）、`expected "NO_DISPERSION" but was "MONOTONIC_INCREASING"`（零区分度被断言方向）、`expected "N/A" but was "MONOTONIC_INCREASING"`（NaN 组均值）、`expected: null but was: 0.0`（ICIR 占位）、Pearson `NaN`。

**方向二：新测试文件在新代码上才可编译。**
`FactorResearchEngineDisclosureTest` 在旧代码上**编译失败**（`找不到符号`：`FactorResearchSummary.crossSectionDays()` 等），证明这 6 个披露组件为**新增**而非改语义。

### §4.4 本轮**会改变既有值**的项（逐条点明，便于你复核）

1. `rank_ic_ir` / `rankIcIr`：IC 序列无离散度（std=0，含 T<2）时 **`0.0 → null`**（引擎 + 载荷 + DTO 字段改 `Double` + 前端渲染「不可用（IC 无离散度）」）。
2. `monotonicity`：新增取值 **`NO_DISPERSION`**（全相等）与 **`N/A`**（组均值非有限），该两情境下原先分别输出 `MONOTONIC_INCREASING`。
3. **退化/脏数据路径下的数值纠正**：非有限观测不再计入 `sample_size`/`total_observations`；`long_short_spread` 不再把空组当 0 收益参与；`round4` 不再把 `NaN` 写成 `0.0`、`±Inf` 写成 `9.22e14`；`pearson` 在小量级输入下不再误返 `NaN`。
4. **声明文本变更**：`convention` / `basis_note` / `labelDefinition()` / 两份 docs / 前端提示的成本公式由**加法式**改为与实现同构的**两级乘性**（数值与历史结论零变化）。
5. 换手率载荷：退化分支新增 `turnover_status`，逐日行新增 4 键，顶层新增 8 个披露键（**既有键与数值零变化**）。

---

## §5 变更文件与提交

**后端主代码（7）**：`FactorResearchEngine.java`、`FactorResearchService.java`、`ScoringBacktestService.java`、`ScoringBacktestResponse.java`、`ResearchConvention.java`、`BacktestTurnoverService.java`、`DataService.java`。
**前端（4）**：`api/factor.ts`、`api/backtest.ts`、`views/factor/FactorResearch.vue`、`views/backtest/ScoringBacktest.vue`。
**文档（2）**：`docs/研究口径规范.md`、`docs/API文档.md`。
**测试（6）**：`FactorResearchEngineTest.java`、`FactorResearchEngineDisclosureTest.java`（新建）、`FactorResearchServiceTest.java`、`ScoringBacktestServiceTest.java`、`BacktestTurnoverServiceTest.java`、`ResearchConventionTest.java`。

> 提交时**不**包含 `modules/web/src/types/components.d.ts`（`npm run build` 触发的构建副产品，`git diff --numstat` 为空、无内容差异）。

**commit**：`3c90b056`（`fix(R001-R015): 决策队列 85 项落地（52 已修复 / 10 部分 / 21 待拍板 / 2 不做）`）。
**push**：普通 `git push` **成功**（`65c80402..3c90b056  master -> master`），**无 `PUSH_PENDING`**；回填提交的 push 结果见 `progress.md` §5。

---

## §6 第二批（21 项待拍板 + 10 项部分修复的**数值侧**）提交记录

**后端主代码（11）**：`FactorResearchService.java`、`ScoringBacktestService.java`、`ScoringBacktestResponse.java`、`FactorResearchEngine.java`、`BacktestTurnoverService.java`、`ScoringUtils.java`、`UnifiedScoringService.java`、`ComprehensiveScoringService.java`、`StockSelectorService.java`、`StockTrendScoringService.java`、`T1CandidateService.java`、`MultiDimScoringService.java`、`OversoldReboundScoringService.java`（含量纲阈值与诊断族、生效值持久化）。
**DDL（2，仅注释）**：`db/migration/V1__baseline_core.sql`、`modules/database/01_init_schema.sql`（`成交额(元)` → `成交额(万元)`，**无结构变更、无数据回填**）。
**脚本（2）**：`scripts/daily_scoring_pipeline.py`、`.ps1`（跟随万元口径）。
**前端（5）**：`api/factor.ts`、`api/backtest.ts`、`views/factor/FactorResearch.vue`、`views/backtest/ScoringBacktest.vue`、`views/market/StockPicker.vue`。
**文档（4）**：`docs/研究口径规范.md`（新增 §8）、`research/paper_loop/rounds/R001_report.md`（双版本标注）、`rounds/R006_report.md`（附录 A-6 / E-6）、本文件 + `progress.md` §9。
**决策档案（1，新建）**：`research/paper_loop/decisions/round_closeout_R001_R015.md`。
**测试（4）**：`ScoringUtilsMinAvgAmountTest.java`（新建，7 例）、`FactorResearchEngineCostBasisTest.java`（新建，10 例）、`ScoringBacktestServiceTest.java`（+5）、`BacktestTurnoverServiceTest.java`（+6）。

> 提交时**同样不**包含 `modules/web/src/types/components.d.ts`（构建副产品，`git diff --numstat` 为空）。

**commit**：`d70120b6`（`fix(R001-R015): 第二批 — 21 项待拍板 + 10 项部分修复的数值侧全量落地`）。
**push**：普通 `git push` **成功**（`32dcf76c..d70120b6  master -> master`），**无 `PUSH_PENDING`**。

**本批仍**未启用真实交易、未索取真实凭证、未向真实账户下单；**未执行**任何破坏性 DDL 或历史数据回填（草案见 `decisions/round_closeout_R001_R015.md` §6）。
