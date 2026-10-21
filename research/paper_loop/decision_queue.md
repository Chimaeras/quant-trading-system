# decision_queue：需要用户决策的事项

> 按 `docs/Codex论文驱动量化改进执行协议.md` §7，以下事项**只能记录、不得自动执行**。
> 本队列**不阻塞循环**：agent 记录后取最保守的可回退选项，继续下一轮。
> 状态：`PENDING_USER`（待用户决策） / `RESOLVED`（用户已决策） / `WITHDRAWN`（证据不足撤回）

| ID | 轮次 | 类型 | 事项 | 证据 | 保守处理 | 建议 | 状态 |
|---|---|---|---|---|---|---|---|
| D-R001-01 | R001 | 质量闸阈值 / 数据口径 | `market_data.amount` 与 `volume` 的实际单位与 DDL 注释、与 `ScoringUtils.avgAmount20` 的 Javadoc 不一致 | 见下 §1 | 不改任何生产代码 | 先做单位断言测试，再决定是否修阈值 **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R001-02 | R001 | 打分权重 | R001 观测到「短窗口反转（N=1）在合并 IS 上不显著、N=5/20 才显著」，与生产引擎 `er1 −0.15` 的方向相反 | 见下 §2 | 不改权重 | **仅**可提议加一个影子测量，不得据本轮改权重 | **PENDING_USER** |
| D-R001-03 | R001 | 研究载体 | 本轮全文用 NBER WP 24458（2018-12 修订），立项依据的 DOI 指向 JFE 2019 正刊版本 | 见下 §3 | 结论中同时标注两个版本 | 无紧急动作 | **PENDING_USER** |
| D-R001-04 | R001 | 影子因子提升 | 本轮**无任何因子进入影子**，故无提升请求 | `shadow_registry.md` 本轮为空 | 不提升 | 无 | **RESOLVED**（无请求） |
| D-R002-01 | R002 | 数据源 / 入库口径 | `market_data` 在 2026-02 **补录**了 1,888 个代码的历史行（含创业板最老的 `300001..300019`，2009 年即上市），2026-02 之前**一行都没有** | 见下 §4 | 不改任何入库/调度代码 | 加一条「入库覆盖度监控」：按代码统计首行日期，突增即告警 **RESOLVED**（2026-09-15 用户裁定 E「暂不做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R002-02 | R002 | 研究口径 / 数据基建 | 本地池含 99 个中途停止交易的名字，但**无末端/退市收益**；R002 以 δ=−30% 补录后对结论影响 0.10bp，量级不足 | 见下 §5 | 不改 `market_data`、不引入外部退市数据 | 若要严肃处理生存者偏差，需引入 PIT 池与退市收益数据源（属 §7「引入新数据源」） **RESOLVED**（2026-09-15 用户裁定 E「暂不做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R003-01 | R003 | 公开 API / 前端契约 | 生产代码中名为「多空收益差 / 多空 spread」的 `long_short_spread` 实为 `netTopQ − netBottomQ`（**两个多头腿净收益之差**），成本在差分中**结构上抵消**；论文口径的美元中性多空应额外扣 `2c`（冻结 12bp 下差 24bp，展示值高出 80.6%，t 由 2.6303 变 1.4567） | 见下 §6 | 不改任何生产代码、不改字段语义、不改前端文案 | 三选一：①只补文档与前端 tooltip 说明口径；②新增并行的「真多空」字段（不动旧字段）；③直接改旧字段语义（须做迁移与契约评审） **RESOLVED**（2026-09-15 用户裁定 B「改名字」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R003-02 | R003 | 冻结口径 / 成本模型 | 本地**无法估计有效价差**（见 `blocked_queue.md` B-R003-01），成本侧只有模型无关下界：一跳 = `0.01/price`，全池 p50 = 7.0972bp、D1 池内 p50 = 9.2764bp | 见下 §7 | 不改 `ResearchConvention` 的任何成本常数 | ①先把「一跳下界」仅作为研究文档中的显式对照量，不进口径；②补报价级/价差数据后再议；③若确要改口径，须与 D-R003-01 合并评审 | **PENDING_USER** |
| D-R004-01 | R004 | 公开 API / 前端契约 | `long_short_spread` 少扣**一条腿**的往返成本。用**项目自己的**成本表达式构造真双腿美元中性组合，与项目值之差 `G(12bp)`：IS **+30.1463bp**、OOS **+29.9893bp**（两段同号，均超阈值）⟹ **OOS 的 `+18.9129bp` 在真双腿口径下为 `−11.0764bp`，符号翻转**。R003 的 `c_rt=12bp` 比项目实际往返（15bp）低 3bp，故 R003 估的 24bp 偏差**低估**了实际偏差 | 见下 §8 | 不改任何生产代码、不改字段语义、不改前端文案 | 与 `D-R003-01` **合并评审**（同一字段同一问题）：①只补文档 + 前端 tooltip；②新增并行「真双腿」字段；③改旧字段语义（须迁移评审） **RESOLVED**（2026-09-15 用户裁定 B「改名字」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R004-02 | R004 | 前端契约 / 展示语义 | 展示层口径标注**不对称**：`FactorResearch.vue` 标 `metric_scope`（8 处，含「主口径：…（成本后近似）」），而 `ScoringBacktest.vue` 的「多空收益差」卡片（259–283 行）**0 处**，且全仓无「非超额 / 无基准」显式标注 | 见下 §9 | 不改任何前端文件 | 给 `ScoringBacktest.vue` 的该卡片补「口径 / 周期（h 日）/ 非年化 / 非超额」四项标注，与 `FactorResearch.vue` 对齐 | **PENDING_USER** |
| D-R004-03 | R004 | 公开 API / 退化输入契约 | 本地 `market_price_raw` **0 行** ⟹ 生产 `COST_APPROX` 路径**跳过全部观测**，但 `emptyNetIcBlock()` 仍写出 `metric_scope = COST_APPROX` 与 `long_short_spread = 0.0`，**无「无 raw 数据」标注** | 见下 §10 | 不改任何生产代码、不改 DTO | ①在该块加一个显式标志（如 `data_available: false` / `net_basis_unavailable`）；②或把占位值改为 `null`；③或至少在注释与文档中声明该歧义 **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R005-01 | R005 | 公开 API / 运行权威性 | 同一 `(trade_date, factor_version, topn)` 存在 **2–4 个 `result_id`**（**18 组**，覆盖 3528/5156 = **68.4%** 的行）。12 组为逐位相同的重跑，**6 组选择集不同**，**4 组近乎/完全不相交**（`2026-05-19`、`2026-06-30` 的 jaccard = **0.000**），另有 5 组零样本。**没有任何字段**可用于区分哪一次运行是权威运行 | 见下 §11 | 不改任何代码、不改契约、不加约束 | ①新增「运行权威性」标记字段（不动旧字段语义）；②或加业务键唯一约束（须迁移评审）；③查明 6 组不相交的成因——**需运行输入快照，属新增持久化（§7 保留权限），不得自行实现** **RESOLVED**（2026-09-15 用户裁定 D「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R005-02 | R005 | 数据口径 / 退化输入契约 | `selector_result_item` 的 **4 个版本整族**写 `avg_amount20 = 0`（**2154 行**，且 `NULL` 计数 = **0**），与同表自声明的 `min_avg_amount` **直接冲突**（0 不可能通过任何流动性闸）⟹ 占位 0 与真实 0 **不可区分**（触发 `K8`） | 见下 §12 | 不改任何生产代码、不改 DDL、不回填 | ①占位改用 `NULL`；②或补「未计算」标志位；③或补文档声明该列在部分版本不可用。**优先选①**：`NULL` 天然可与真实 0 区分 **RESOLVED**（2026-09-15 用户裁定 D「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R005-03 | R005 | 冻结口径 / 数据量纲 | `selector_result_item.min_avg_amount` **同一列**同时出现 `30000` 与 `30000000` 两档（相差恰好 **1000×** = 元/万元之比），而 `avg_amount20` 的量级（`8.16e4`–`6.41e5`）与「千元」自洽 ⟹ 两个数很可能是**同一逻辑阈值的两种量纲** | 见下 §13 | 不改任何口径、不改任何阈值 | 与 `D-R001-01` **合并评审**（**同源**：`amount` 实为万元而 DDL 注释写「元」）；合并后可用同一份单位断言测试统一收口 **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R006-01 | R006 | 公开 API / 前端契约 | `rank_ic_std` / `rank_ic_ir` 的展示标签**缺 `Rank` 限定**（`IC 标准差`、`ICIR`、`IC 均值`），且与同一张卡片里的 `Pearson IC` **并排形成混合配对**（读者会读成 `IC/ICIR` 一对，实为 rank 序列）；**全仓 0 处** `RankICIR` 标签，而论文 Table 1 把二者列为**不同指标** | 见下 §14 | 不改任何前端文件、不改任何字段名 | ①给 3 处标签补 `Rank` 限定（最小改动、不动契约）；②若要提供 Pearson 版 ICIR，须**新增字段**而非改旧字段语义（须契约评审） | **PENDING_USER** |
| D-R006-02 | R006 | 冻结口径 / 统计约定 | ICIR 分母用**总体标准差 ÷T**（`FactorResearchEngine.java:172-174`），论文式 (18) 为 **÷(T−1)**（论文内部一致：式 (22) 的 `σ_r` 同为 T−1）；偏差**恰为** `sqrt(T/(T−1))`，实测与理论值差 `0.0` / `2.2e-16` | 见下 §15 | 不改 `ResearchConvention` 的任何约定、不改代码 | ①**推荐**先只补文档声明分母口径；②若改口径须**同时**改 `rank_ic_std` 与 `rank_ic_ir` 并做迁移评审（量级仅 +3.08%/+5.41%，**不建议**为此单独动口径） **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R006-03 | R006 | 公开 API / 退化输入契约 | 退化截面日（IC 非有限）被**静默丢弃**（`FactorResearchEngine.java:150-152` `continue`），该日同时从均值/标准差/正率/`cross_section_days` 的**分母消失**，且**全仓 0 个丢弃计数字段**；注入 7 日面板丢 **3** 天（`total_observations = 48 = 4×12`） | 见下 §16 | 不改任何生产代码、不改 DTO | ①新增「丢弃日计数」字段（**新增字段**，不动旧字段语义）；②或在 `universe_note` 中显式追加丢弃说明；③**不要**改成保留退化日参与均值（会引入更差口径） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R006-04 | R006 | 公开 API / 退化输入契约 | `groupCount > n` 时空组被伪造为 `avg=0/obs=0`（`FactorResearchEngine.java:180-183`），使 `long_short_spread` 退化为 `0 − G1`（**逐位相等**、符号反转）；**仅直接 API 可达**（`ScoringBacktestRequest.java:38` 无 `@Min/@Max`，前端 `:max="10"`）；`observations` 有导出也有渲染 ⟹ **减缓项成立** | 见下 §17 | 不改任何生产代码、不改 DTO | ①给 `factorGroupCount` 补 `@Min`/`@Max` 之类约束（**改契约**，须评审）；②或在空组上标注「空」而非 0；③或至少补文档声明该退化（**经界面不可达**，优先级低于 `D-R006-03`/`D-R006-05`） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R006-05 | R006 | 退化输入契约（**须与 D-R004-03 / D-R005-02 合并评审**） | 定义失效时输出**具体实数**：`emptySummary` 六项全 0（`monotonicity="N/A"` 与 `universe_note` 提供**部分**可区分性）；**`std=0` 时 IR 硬写 0**（`:174`），`mean<0` 时真极限为 `−∞` 却写成中性 ⟹ **方向偏乐观**，`T=1` 即触发；`pearsonIcMean` 的 `? 0` 分支为**死分支** | 见下 §18 | 不改任何生产代码、不改 DTO | ①占位值改 `null`（与 `D-R005-02` 同一推荐）；②或补「未定义」标志位；③与 `D-R004-03` / `D-R005-02` **合并评审**：**同一「退化输入 + 占位输出 + 仍标称语义」形态的第三/第四个实例**，首次落在**运行时代码的 IC 路径** **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R006-06 | R006 | 库表结构 | `selector_result.ic_value` 是**死列**：全仓唯一出现处是 DDL 本身（`V2__research_tables.sql:183`），**无任何写入路径**；DB 实测 `COUNT(*) = 67`、**`COUNT(ic_value) = 0`** | 见下 §19 | 不改 DDL、不回填 | ①保留并补文档声明「当前无写入路径」；②或在确认无消费方后移除该列（**破坏性 DDL**，须迁移评审）；**首选①** **RESOLVED**（2026-09-15 用户裁定 D「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R006-07 | R006 | 展示语义 | 各组收益**全相等**（零区分度）被判 `MONOTONIC_INCREASING`：`assessMonotonicity`（`:226-249`）在全相等时 `increasing`/`decreasing` **同真**，代码先判 `increasing` | 见下 §20 | 不改任何生产代码、不改前端 | ①全相等时返回 `N/A` 或新增 `NO_DISPERSION` 枚举值（**改枚举 = 改契约**，须评审）；②至少补文档说明该情境；**不进总判定**（`K8` 只标 `REGISTERED_ONLY`） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R007-01 | R007 | 公开 API / 退化输入契约 | T = 1（`cross_section_days=1`、当日 n=10）时 `hac_std=0.0`、`ci=[0.0,0.0]`（**零宽**）与**真实点估计** `rank_ic_mean=-0.9878787878787879` **并存于同一 block**，且同块内**无任何标记字段**：`note` 仅由 `totalObservations()==0` 守卫（`FactorResearchService.java:550-551`），`status` 仅存在于 `unavailableScopeBlock`（`:560`）。载荷层经 `round4` 后 `0.0` 仍为 `0.0` ⟹ HTTP 响应同样成立 | 见下 §21 | 不改任何生产代码、不改 DTO、不改前端 | ①`hac_std`/`ci_*` 在 n<2 时改 `null`；②或加显式标志（如 `ci_status: "INSUFFICIENT_N"/"DEGENERATE"`）；③或至少在该块加 `note` 并把触发条件从 `totalObservations()==0` 扩到「不确定性估计不可形成」 **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R007-02 | R007 | 前端契约 / 展示语义 | 标签写 `HAC 95%CI`（`FactorResearch.vue:139` 行内、`:322` 列头），而 `ci_lower`/`ci_upper` 的值来自 `FactorResearchEngine.java:191 blockBootstrapCi(icSeries, max(1,holdingDays), 1000, 42L)`（**block bootstrap**，非 HAC 解析式）。同块内 `hac_std` 字段确实存在 ⟹ **两个不同估计量的名字与值被并置**，标签只指称其中一个 | 见下 §22 | 不改任何前端文件、不改字段名 | ①标签改 `bootstrap 95%CI`（值源）；②或双标签（如 `HAC SE / bootstrap CI`）；③或前端 tooltip 说明「SE 为 HAC，区间为 block bootstrap」 | **PENDING_USER** |
| D-R007-03 | R007 | 公开 API / 键稳定性 | 同一 `buildRankIc` 块在 `latestPerKey.isEmpty()` 的**早返回分支**（`:660-671`）**不含** `hac_std` / `ci_lower` / `ci_upper`（另缺 `pearson_ic_mean` / `monotonicity`），而正常路径（`:735-789`）含 ⟹ **键集随数据可用性变化**，消费方无法静态声明类型；前端 `row.ci_lower` 类绑定在该分支下取到 `undefined` | 见下 §23 | 不改任何生产代码 | ①早返回分支补齐同键并置 `null`/显式状态；②或统一改为「始终输出全部键 + 状态字段」；③若维持现状，须在 API 文档声明「键集可变」 | **PENDING_USER** |
| D-R007-04 | R007 | 文档 / 口径一致性 | `RANK_IC_EMBARGO_NOTE`（`:83-85`）对外承诺「重叠持有期 IC 序列的显著性**以 HAC 标准误**与 block bootstrap CI 为准」，而 `neweyWestHacStd(List<Double>)` 入参**只有序列、无整型参数**（反射实测 `nw_param_types=java.util.List`）⟹ 滞后阶只由 `n` 决定（`lag=max(1,min(floor(n^(1/3)),n-1))`），**与持有期 h 结构上解耦**；实测 h≥5 的 60 格中 1 格 ratio 0.8849（`MA_equal_hm1`, n=27/h=10） | 见下 §24 | 不改任何生产代码、不改承诺文本 | ①给 `neweyWestHacStd` 增加「滞后阶」入参并在调用点传 `h`（**改签名 = 改契约**，须评审）；②或只改承诺文本，说明窗口由 n 定；③或保留现状 + 文档披露解耦事实 **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R007-05 | R007 | 文档漂移 | `blockBootstrapCi` javadoc（`FactorResearchEngine.java:355`）写「块大小 = 持有期 h」，实现（`:363` 行内注释已披露）把有效块长钳为 `bs = max(1, min(min(blockSize, floor(sqrt(n))), n-1))`；实测 n=10 时 h=3/5/10 **都给 bs=3**（h=5/10 时 bs < h） | 见下 §25 | 不改任何生产代码 | ①javadoc 改为「块大小 = min(h, floor(√n), n−1)」；②或调用点传 `h` 时不再依赖钳位（即改统计口径，须评审）；③最低成本 = 只改注释/文档 **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R007-06 | R007 | 前端契约 / 一行内两种口径 | IC 明细 CSV 导出：表头 token 写 `net_ic`（**无 `cost_` 前缀、无 `rank_` 限定**），值取 `row.cost_net_ic?.rank_ic_mean`（**成本后块**）；同一行紧随的 `row.hac_std` / `row.ci_lower` / `row.ci_upper` 取 **`row`（毛收益块）**⟹ 同一行内成本后 IC 与毛收益 SE/CI **并列**且只有前者未标口径 | 见下 §26 | 不改任何前端文件 | ①表头改 `cost_net_ic_rank_ic`，并给后三列加 `gross_` 前缀或分组表头；②或后三列改取 `cost_net_ic` 块（**改展示口径**，须评审）；③至少加 tooltip | **PENDING_USER** |
| D-R007-07 | R007 | 口径标注缺失 | 载荷族输出 `hac_std` 与 95% 区间，但全仓**生产面**（`modules/`，排除 `node_modules`/`target`/`dist`）对 `nominal` / 多重检验 / 多重比较 / Bonferroni / Benjamini / FDR / adjusted p / multiple testing 的命中 = **0**；全仓 14 命中**全部**位于 `research/paper_loop/experiments/R007_uncertainty_audit/`（本轮自己的正则文本） | 见下 §27 | 不新增任何标注、不改任何口径 | ①在前端标签与 API 注释中声明区间为**名义**区间（未做多重检验校正）；②**不得**据本轮引入 3.0/3.9 门槛（该门槛是 HLZ2014 的美股横截面结论，与本项目不可比）；③可作为独立轮次再议 **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R007-08 | R007 | 展示语义 / 统计口径 | 在 80 单元 × 2 臂（n∈{5,10,17,27} × h∈{1,2,3,5,10} × 5 DGP，每单元 1000 次重复）上，名义 95% 区间的**实测覆盖率**显著低于名义：**BOOT 62/80、HAC 60/80、任一臂 66/80** 单元触发（触发判据：覆盖率 < 0.85 **且** Clopper–Pearson 单侧 99% 下界 < 0.85）；`min boot=0.344`、`min hac=0.392`（均 n=5/h=5/`MA_equal_hm1`）；按 DGP 平均（boot/hac）：IID 0.848/0.855、AR1_0.2 0.794/0.816、AR1_0.5 0.670/0.719、AR1_0.7 0.536/0.594、MA 0.734/0.748。**非退化所致**：80 格 `zero_width`/`se_zero` 全为 0 | 见下 §28 | 不改任何生产代码、不改阈值 | ①前端对区间加「名义水平，重叠持有期下实际覆盖率可能显著低于 95%」的说明；②若要真修，须改统计口径（块长与 h 对齐 / 更长窗口 / 依赖稳健估计量），属**统计口径变更**，须用户确认；③**不得**把上述覆盖率数字当作本项目真实覆盖率（DGP 是风格化表征，见 `B-R007-01`） **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R007-09 | R007 | 数值 / 载荷层（**post-hoc，未预注册**） | `round4(v)=Math.round(v*10000.0)/10000.0`：`NaN → 0.0`；`±Inf → 9.223372036854776E14`；当 `1.96·hac_std < 5e-5`（即 `hac_std < 2.55e-5`）时 `[mean ± 1.96·se]` 在 4 位小数下可**塌成零宽**（`ci_lower == ci_upper`）而 `mean` 非零 ⟹ **伪零宽**。**本项在预注册 21 断言之外，不参与总判定合成**（协议：预算外探针须命名 `posthoc_*`） | 见下 §29 | 不改任何生产代码 | ①区间与 SE 的序列化精度与 `mean` 解耦（或至少对 CI 三键不套 `round4`）；②或加「零宽但 SE>0」的载荷层标记；③**须另开一轮带预注册的审计**才能作为判定依据 | **PENDING_USER** |
| D-R008-01 | R008 | 冻结口径 / 换手率定义 | 短员日（**当日**候选数 < `topN`）把**请求参数** `n` 当作分母：`turnover = 1 − 交集大小/n`，而交集是当日实际候选集合的比较结果；判据来源（arXiv:2606.08285v1 L1222–1228 式 (3)）的分母是**实际持仓**、**不是**调用方传入的参数。实测双侧短员 `n=10` 两日各 3 条时载荷报 **0.7** 而定义值 **0.0**（偏差 **0.70**），单侧短员报 **0.7** 而定义值 **0.35**（偏差 **0.35**） | 见下 §30 | 不改任何生产代码、不改口径、不改前端 | 三选一：①分母改用当日实际规模（`min(prev,curr)` 或上一日实际持仓）；②定义不适用时输出 `UNAVAILABLE`/`null`（**改公开语义**）；③至少补文档声明分母是 `top_n` 请求参数。**均属协议 §7 record-only** **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R008-02 | R008 | 文档 / 行为契约 | javadoc 逐字声明前置条件「（两日均有 topN 个候选时）」，而 `analyze:40–128` 方法体内**零**守卫行、**零** size 字段 ⟹ 声明了**未被强制**的前置条件 | 见下 §31 | 不改任何生产代码、不改 javadoc | 二选一：①改 javadoc（去掉前置条件声明，承认短员日也输出）；②加 guard（**改行为**，须评审：抛异常 / 返回不可用 / 截断到两侧较小者）。与 `D-R007-05`（文档漂移）同型，最小成本解是① | **PENDING_USER** |
| D-R008-03 | R008 | 公开 API / 退化输入契约 | 定义**退化**时输出具体实数 `0.0`（`signal_days ∈ {1,0}`、空 `daily_turnover` 的两个面板实测 `avg/max/min = 0.0`），只有人类可读 `note`；全仓机器可读不可用键（`status`/`available`/`basis`）命中 **0** ⟹ 「无定义」与「真的 0」**不可区分** | 见下 §32 | 不改任何生产代码、不改 DTO | ①新增机器可读标记（如 `turnover_status`）或占位改 `null`（**改契约**）；②或仅在 `note` 中补说明。与 `D-R004-03` / `D-R005-02` / `D-R006-05` / `D-R007-01` **合并评审**（**同族第 5 个实例**：退化输入 + 占位输出 + 仍标称语义） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R008-04 | R008 | 公开 API / 默认路径 | `strategy = null`（控制器 `@RequestParam(required = false)`，**默认**调用路径）时**不按策略分组**，把**同一天不同策略**的行当作「相邻交易日」配对：实测 `signal_days = 4` 而窗口内不同日期 **2**、相邻 pair 日期 `2026-05-01, 2026-05-06, 2026-05-06`、`avg_turnover = 1.0` vs 严格同策略对照 **0.0** | 见下 §33 | 不改任何生产代码、不改契约 | 三选一：①`strategy` 改必填（**改契约**）；②`null` 时按 `strategy` 分组分别算（**改行为**）；③至少补文档声明 `null` 的语义。另需人工确认前端各调用点是否**总是**传 `strategy` | **PENDING_USER** |
| D-R008-05 | R008 | 公开 API / 回显一致性 | `strategy = ""` 与 `strategy = null` 的**计算结果逐位相同**（走同一条混合过滤分支），但**回显值不同**（`"ALL"` vs `""`）⟹ 读者无法从载荷判断两者是否同一口径 | 见下 §34 | 不改任何生产代码 | ①统一归一（`""` → `null` 或反之）并让回显与实际过滤条件一致；②或补文档声明二者等价。与 `D-R008-04` **同源**（同一分支），建议**合并评审** **RESOLVED**（2026-09-15 用户裁定 B「改名字」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R008-06 | R008 | 公开 API / 逐日行字段完整性 | `daily_turnover[]` 逐日行键集 = `trade_date,turnover,new_count,exit_count`，**缺** `prev_size`/`curr_size`（两日实际候选数）；全仓 `prev_size`/`curr_size`/`candidate_count`/`prev_n`/`curr_n` 命中 **0** ⟹ 读者只能**反解**分母，不能**直接看到**两日规模、也无法自行判断该日是否短员 | 见下 §35 | 不改任何生产代码、不改 DTO | ①新增 `prev_size`/`curr_size` 两字段（**改契约**，但为**纯增量**）；②或至少在 `note` 中披露短员日清单。推荐①：它同时使 `D-R008-01` 的任一修法**可被外部验证** **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R008-07 | R008 | 公开 API / 口径字段缺失 | 载荷族**无任何结构化口径字段**（`basis`/`definition`/`metric_scope`/`convention`/`one_way`/`round_trip`/`口径` 命中 **0**），只有 58 字段的 `note` 自由文本（声明幸存者偏差）；被审计面对 `one-way\|two-way\|单向\|双向\|round-trip\|双边\|归一约定` 的命中 **0** ⟹ **无**单向/双向归一约定声明。载荷的 `1 − 交集大小/n` 是**单向**替换率，判据来源式 (3) 的 `Σ\|Δw\|` 是**双向**，两者相差 **2 倍** | 见下 §36 | 不新增任何标注、不改任何口径 | ①新增 `turnover_basis`（如 `ONE_WAY_REPLACEMENT_RATIO`），或至少在前端标签/`note` 中声明；②**不得**据本轮断言「单向是错的」（单向/双向是并存的行业惯例，判据来源亦**未给**归一约定 ⟹ 本轮只判 `REGISTERED_ONLY`） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R008-08 | R008 | 文档 / 同名不同量 | `RollingExperimentService:473–474` 明确输出 `"UNAVAILABLE"` + 「换手需组合执行口径（MP-020/021）才可评估，本研究不产生持仓序列」，而 `BacktestTurnoverService` 在**同样没有持仓序列**的前提下输出具体数值（`:127`）⟹ 项目内两处惯例结论**相反**，而对外都叫「换手率」 | 见下 §37 | 不改任何生产代码、不改前端 | ①人工定稿「两个换手率不是同一个量」的命名与文档口径（最小成本）；②若确要统一，须先定哪一个为权威（**改契约**）。与 `D-R001-01`（`amount` 单位）**不同源**：本条是**同项目内两处惯例相反**，不是单位错配 **RESOLVED**（2026-09-15 用户裁定 B「改名字」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R008-09 | R008 | 公开 API / 前端契约 | 同名 `avg_turnover` 指代**两种量**且均在**生产面**：`DataService` 的 SQL `AVG(COALESCE(m.turnover_rate,0)) AS avg_turnover` 是**市场换手率（%）**，`TurnoverResponse.avg_turnover` 是**候选替换率（分数 0–1）**；另有 `CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER`（市值推算来源）与 `RollingExperimentService` 的 `UNAVAILABLE` 两个语义。前端同一中文标签亦分指两种量：`StockDetailView:79`「换手率」= 个股 `turnover_rate`(%)、`ScoringBacktest:484–486`「平均换手」= `avg_turnover × 100`(%)、`DataManagement:293`「换手率」= 市场换手率 | 见下 §38 | 不改任何生产代码、不改前端 | ①统一命名（如 `avg_turnover_pct` / `avg_turnover_ratio`）；②或标签补单位（`换手率(%)`）；③**改契约须评审**（含前端 `× 100` 换算点） **RESOLVED**（2026-09-15 用户裁定 B「改名字」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R008-10 | R008 | 数据库结构 / 确定性 | `selector_result_item.rankNo` **无唯一约束**，而载荷的 `new_count`/`exit_count` 由**集合差**得出、集合本身由查询返回的**行序**决定 ⟹ 并列 `rankNo` 时载荷依赖**未钉住**的行序：`rankNo` 并列第 10 的三只（`X,Y,Z`）按 `(X,Y,Z)` 与 `(Z,Y,X)` 返回，`turnover` 从 **0.0** 变 **0.1**（同输入、仅行序不同） | 见下 §39 | 不改任何生产代码、不改 DDL | ①加唯一约束（**改数据库结构**，须迁移评审）；②或让载荷显式排序（按 `rankNo` 再按稳定键）；③**先**解除 `B-R008-02`（并列 `rankNo` 在真实插入路径是否产生）再定论 **RESOLVED**（2026-09-15 用户裁定 D「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R009-01 | R009 | 公开 API / 守卫语义 | `pearsonCorrelation` 的 NaN 守卫用**未归一化**平方和 `Σ(x−x̄)²` 与**绝对**阈值 `1e-18`，而 javadoc 逐字写「方差为 0 时返回 NaN」且**零**量级限定词；把项目**自己既有测试的字面输入** `[1..10]` 整体乘 `1e-10` 使 `ρ` 由 `1.0` 变 `NaN`（对照方差 `8.25e-19 > 0`、秩相关仍 `1.0`、平移 `+1000` 不变）⟹ 破坏的是**尺度**不变性 | 见下 §40 | 不改任何生产代码、不改 javadoc、不改守卫常量 | ①先定性：「守卫比声明更严」⟹ 改 javadoc；「声明才是本意」⟹ 改守卫为标准化/相对量（**须同时检查** `spearmanRankCorrelation` 共用 `pearson` 的耦合）；②**不得**只看单个构造就改（`1e-18` 是绝对量，改正则口径）；③**最小**动作 = 只修文本（行为零变化） **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R009-02 | R009 | 公开 API / 退化输入契约 | `pearsonIcMean = dailyPearson.isEmpty() ? 0 : average(...)` ⟹ 「**完全不可定义**」与「**真 0 相关**」的**完整载荷**经递归规范序列化后**逐字符相同**（均 **1060** 字节）、`pearson_ic_mean` 均为 `0.0`，且 `cross_section_days = 1 > 0`、`total_observations = 10 > 0` | 见下 §41 | 不改任何发射键、不改 `0.0` 占位语义、不改前端必需声明 | ①契约层：`pearson_ic_mean` 改可空，或加 `pearson_support_days`；②行为层：`isEmpty() ? 0` 改 `null/NaN`——与 `round4(NaN) = 0.0` 冲突，**须先定 `round4` 的 NaN 语义**；③**最小**动作 = 只加一个无歧义计数键（向后兼容） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R009-03 | R009 | 跨轮勘误（**R006 A4b**） | R006 A4b 把「`ic` 有限 ⟺ `pearson` 有限」记为 **A 档等价**；本轮在**同一面板**上实测秩侧有限（`1.0`）而 Pearson 侧 `NaN` ⟹ **该等价为假**（秩空间平方和 `n(n²−1)/12 = 82.5`，比守卫高约 **19 个数量级**） | 见下 §42 | **只登记**，**不回写** R006 冻结文本、**不**叙述「R006 整轮失败」 | ①**建议**在 `R006_report.md` **附录**加一条勘误（格式照 R007/R008 惯例）；②若用户认为 A4b 是**单向蕴含**而非等价，则该方向**未被**证伪 ⟹ 请裁定，**agent 不自行改判** | **PENDING_USER** |
| D-R009-04 | R009 | 公开 API / 支撑集可见性 | 混合支撑集面板 `cross_section_days = 2`、`total_observations = 20`、`daily_rank_ic` 两天都在，而**实际 Pearson 支撑天数 = 1**（`support_gap = 1`）；`C11`（键名匹配 `pearson_(sample\|count\|n\|observations\|support\|days\|size)`）冻结期望 **0**、实测 **0** ⟹ **全仓不存在**任何 Pearson 支撑计数字段 | 见下 §43 | 不新增任何字段、不改 `FactorResearchSummary`、不改载荷键集 | ①block 加 `pearson_support_days` 或让 `pearson_ic_mean` 可空（**改公开载荷契约**）；②与 `D-R009-02` 的最小动作**可合并为一次契约变更**；③先用**只读**方式记录现状（本轮已做） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R009-05 | R009 | 公开 API / 前端契约 | `FactorResearchService.java:660-672` 的 `latestPerKey.isEmpty()` 早返回分支只发射 **10** 键，**缺** `pearson_ic_mean`/`monotonicity`/`hac_std`/`ci_lower`/`ci_upper`；而前端 `factor.ts:110`/`:201` 把 `pearson_ic_mean` 声明为**必需**（无 `?`）⟹ 类型契约层面的未定义读取；该分支经区间查询**可达** | 见下 §44 | 不改任何发射键、不改前端声明、不改查询逻辑 | ①**前端侧**（成本最低）：5 键改**可选**并在消费处加缺测分支；②**后端侧**：空分支补齐为同键集；③**先前端**，且**不要**同时改（会掩盖责任面） | **PENDING_USER** |
| D-R009-06 | R009 | 公开 API / 惯例不一致 | 同一「该口径不可用」语义，`unavailableScopeBlock` 用**显式键** `status="UNAVAILABLE"`，而 `emptyNetIcBlock` **靠缺键**（连 `monotonicity` 都没有）⟹ 两处惯例**各自非错**但读者须猜；实测键数分布：18/19/18/14/15/10 | 见下 §45 | 不统一键集、不改任何 block builder、不改前端 | ①统一为**显式** `status` 键（成本最低）；②或统一为**缺键**（需前端全面加 `?` ⟹ 与 `D-R009-05` 合并）；③先定方向，再连同 `D-R009-02`/`D-R009-04` 做**一次**契约变更 | **PENDING_USER** |
| D-R009-07 | R009 | 前端契约 / 导出面 | `FactorResearch.vue:499-517` 的 `exportUnifiedCsv()` 表头含 `pearson_ic`，取值表达式为**裸** `row.pearson_ic_mean`（**无** `??`/`\|\|`/`isFinite`）；与 `D-R009-02` 叠加 ⟹ 占位 `0.0` 与真 0 在**离仓文件**里同样不可区分 | 见下 §46 | 不改导出逻辑、不改表头 | ①与 `D-R009-02`/`D-R009-04` **合并**处理（载荷能表达「不可定义」后，导出面加一个分支）；②若暂不改载荷：按「支撑天数为 0 则写空」处理（需先有支撑字段）；③**不建议**在导出面**单独**修（会出现「页面 `0.0`、导出空」的新不一致） | **PENDING_USER** |
| D-R009-08 | R009 | 既有测试覆盖缺口 | `FactorResearchEngineTest.java` 的测试**输入表达式**共 **20** 处，含 `1e-` 量级 `scores`/`returns` 的为 **0** 处（文件内 4 处 `1e-` **全是**断言容差）⟹ `D-R009-01` 的尺度不变性缺陷**不会**被既有测试发现（`350/350` 全绿） | 见下 §47 | 本轮**未**新增任何测试（先登记等用户排序） | ①加**尺度**用例（`scores = 1e-10·[1..10]` 断言 `ρ == 1.0`）⟹ 该测试**当前会失败**，**必须**与 `D-R009-01` 的修法一并决定；②顺序：**先**定 `D-R009-01` 方向，**再**落测试 **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R010-01 | R010 | 冻结口径 / 契约文本（**本轮总判定的处置方式**） | 项目对成本口径的**对外声明**（`ResearchConvention` javadoc 公式 + `labelDefinition()` 字符串 + 两份 docs + 前端提示）与**实现**（4 站点均为 `(1−s)(1−c−t)` 两级乘性）**不一致**：`f_impl − f_doc = −0.00029984`（主项 `−c` = **−3.00 bp of E**）且 `\|Δbp\| = 3.2973 > TH_BP = 1.0`（⟹ **错标**而非舍入） | 见下 §48 | 不改任何生产代码、不改 javadoc、不改 docs、不改前端 | 三选一：①**只改文本**（javadoc + `labelDefinition()` + 两份 docs + 前端提示）对齐实现（行为零变化，成本最低）；②**只改实现**对齐文本（**改冻结成本口径**，须迁移评审 + 重算全部历史净收益）；③维持现状（则须在 docs 显式声明两套记法并存及其差异量级） **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） ⚠️ **2026-09-15 执行期更正：本项前提已消失、暂缓执行**——「声明＝加法式」的分歧已在第一批按**反方向**关闭（声明已改写为两级乘性、与实现之差 = 0），故「改实现对齐文本」今天已无可改对象；要产生动作只能把声明**退回**漏卖出腿佣金的加法式，而这会使全体净收益**偏乐观** ≈ `3.00 bp of E`（§48 方案②明写「**不建议**」）⟹ **待用户一行确认**（①维持乘性＝本条作废／②退回加法式＝改 4 站点并重算）。详见 `decisions/decision_batch3_2026-09-15.md` §9.2 |
| D-R010-02 | R010 | 文档 / 载荷文本（附加观测 X01） | 加法式记法文本与乘性代码**同处**：`FactorResearchService` 的 `basis_note`（`:78-82`）用 ASCII 加法式，而**该字段被发射进载荷**（`:220` 的 `convention` 同源），且同文件内即含乘性站点（`×5`）；全仓同类「加法记法 + 乘性实现」同处命中 **×4** | 见下 §49 | 不改任何生产代码、不改任何文本 | ①把 `basis_note` 改写为与实现同构的乘性式（**改公开载荷文本**，须评审）；②或在 `basis_note` 中加一句显式声明「本节记法为加性近似，实现为乘性」；③维持现状但登记 **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R010-03 | R010 | 文档 / 公式文本（附加观测 X02） | `FactorResearchService.java:352` 存在**第三种** ASCII 加法记法（既非 docs 的 `1−印花税−滑点`，也非 javadoc 的 `(1 - sell_tax - slippage)`），且其出场式把**同一个符号 `s` 用了两次**（滑点与卖出印花税混淆）⟹ 三套记法互不相同、其中一套**符号自相矛盾** | 见下 §50 | 不改任何生产代码、不改注释 | ①改该行注释使其符号唯一（最小改动、不动契约）；②或删除该注释（它是站点上方的口径说明，删掉会削弱可读性）；③与 `D-R010-01` **合并评审**（同一「声明—实现」家族） **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R010-04 | R010 | 文档 / 站点副本（附加观测 X03） | `ScoringBacktestService` 的**入场费站点有两份副本**：`:275-276`（主回测循环内联）与 `:969-970`（抽出的 `resolveEntryContext` 助手），两处**算术完全相同**且均为乘性；声明侧**未提及**存在两份 | 见下 §51 | 不改任何生产代码、不改注释 | ①人工确认两份副本是**有意**还是历史遗留（若是遗留，删一份 = **改生产逻辑**，须评审）；②若有意，在声明侧注明；③本轮**未**把两份副本叙述成缺陷（它们是**同构**的，只是校准锚点须用「起始行提示」区分） **RESOLVED**（2026-09-15 用户裁定 D「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R010-05 | R010 | 研究仪器 / 历史结论 | 项目**自己的两个研究仪器**（`run_reversal_ic.py`、`run_cost_breakeven.py`）走的是**加法式文档口径**（前者含加法式出场因子 `=1` 且 `FROZEN_TOTAL=0`；后者 `FROZEN_TOTAL=5` 且加法式 `=0`）⟹ 它们算出的**净收益比生产口径偏乐观** | 见下 §52 | **不重算**任何历史结论 | ①人工裁定是否需要用乘性口径**重算** R001/R003 的净收益数字（会改变既有报告的数值，**不改其判定词**）；②或只加一条勘误说明偏乐观的方向与量级（`≈ 3.00 bp of E` 量级）；③**不得**由 agent 自行重算并覆盖历史产物 **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R010-06 | R010 | 数据库结构 / 契约标记 | **计算路径**取 `boundedRate` **钳位后**的值（`boundedRate(0.06) = 0.05`），而**持久化路径**取**原始**请求值（`0.06`，该区间 `boundedRate` 命中 **0** 次）；载荷 `executionAssumptions` 写的是**钳位后**的值且**无** `clamped` 标记（`assumptions_has_clamp_marker = 0`） | 见下 §53 | 不改任何生产代码、不改 DDL、不改载荷键 | ①在该块加显式标记（如 `commission_rate_clamped: true` + `commission_rate_input`）**不动旧字段语义**；②或统一两路取同一值（**改行为**，须评审）；③端到端证据受 `B-R010-01` 阻塞 **RESOLVED**（2026-09-15 用户裁定 D「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R011-01 | R011 | 公开载荷面 / 契约 | **承重分叉「不可恢复」合并决策包**：①`min_cross_section_size` 在**全部 5 个生产 `summarize` 调用点**上**零发射**（`V04`，触发 `K1`）⟹ 任何已发布载荷都无法知道该族用的是 10；②`group_count` 在 `F-SNAPSHOT` 族**缺键**（`V03`，触发 `K4`）——**同一个** `FactorResearchService`、**同一个** `RANK_IC_GROUP_COUNT`，`F-UNIFIED` 发射而 `F-SNAPSHOT` 不发射（族内不对称）；③`FRS:70`（编译期常量、无入参）与 `SBS:78`（请求参数，默认 5，前端 `:min=2 :max=10`）是同一分叉的**两个定义来源**（`V09`，触发 `K3`）；两处 min-size 常量**值同（10 = 10）但声明不共享**（`V10`：值层一致、结构层分歧，**必须分开写**） | 见下 §55 | 不改任何生产代码、不改 DTO/载荷键、不改前端契约、不改 `ResearchConvention` | ①**最小改动**：在三个族的载荷各补一个结构化键（`min_cross_section_size`，并在 snapshot 块补 `group_count`）——**改公开载荷面**，须契约评审与前端同步；②或只抽出**共享常量**消除 `V10` 的结构层分歧（不动载荷，**不**恢复可恢复性）；③或仅补文档（`V18` 仍为 `NOT_ENUMERABLE`，**不**解决可恢复性）；④**四条同族，必须合并评审**，不得分四次改 | **PENDING_USER** |
| D-R011-02 | R011 | 冻结口径 / 判据词表 | 冻结词表（§4 A7）里的 `robust` 在 `ScoringBacktestService:654`（`robustZ(residual,MAD)`）与 `:699`（`robust Z on residuals`）命中**方法名子串假阳性** ⟹ `V17` 的机械判定被翻转（实测 `NOT_VIOLATED` ≠ 冻结期望 `REGISTERED_ONLY`）；另加的词边界收紧测量命中 = **0**（证明是测量学问题，不是事实问题） | 见下 §56 | **未**改词表、**未**改 `V17` 判定词（勘误只写入 `R011_report.md` 附录 A-2） | ①把 A7 词表改成**词边界匹配**（属**修改已冻结口径**，须用户确认）；②或明确「以逐条人工判别为准」并把判别过程写成规范；③后续任何轮次的文本检索**必须先做子串假阳性检查**（本轮负知识） **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R011-03 | R011 | 研究仪器 / **事后修改**（最重要） | 本轮在**看到结果之后**修改了金丝雀仪器语义：`CLN` 由「谓词必须为真」改为「谓词是**所声明键集合**的确定性函数 + 未声明哨兵键不改变结果」（D9）、`INJ` 注入值改按**实测状态**取向（D10）、`V20` 拆两步消除自指（D11） ⟹ 总判定由 `INCONCLUSIVE` 变为 `REJECTED` | 见下 §57 | 冻结文件**零字节变更**；`run.log` 保留全部 **5 次**运行（含 3 次仪器失败）；报告 §8.2 显式披露「仪器修复**改变了结论**」；杀点 `K1–K5` 在**运行 2** 就已全真 | ①复核并**认可**该仪器语义（→ 维持 `REJECTED`）；②或要求回到「`CLN` = 谓词必须为真」的更强语义（→ 本轮应回到 `INCONCLUSIVE`，H0 未判定）；③若认可，建议把「`CLN`/`INJ` 的判合格标准」写入 `preregistration_template.md`，避免下轮重犯 | **RESOLVED**（2026-09-15）：用户采纳**选项②**并同时采纳选项③ |
| D-R011-04 | R011 | 研究口径 / 定义提升 | 「备选集合」的合格形态（枚举列表 / 显式取值域，单一常量不算）与「**单族 UI 取值域不构成项目级备选集合**」这一口径，目前**只存在于本轮预注册**（`V06`/`V07`/`V08` 的判据来源） | 见下 §58 | 不改 `docs/研究口径规范.md`、不改任何冻结口径、不改 `ResearchConvention` | ①把该定义提升为项目级研究规范（属**改研究口径**，须用户确认）；②或维持「每轮预注册各自定义 + 本台账留痕」的现状 | **PENDING_USER** |
| D-R012-01 | R012 | 冻结口径 / 词表枚举 | `metric_scope` 的**实际取值域** = `{RAW, COST_APPROX, EXECUTABLE, FILLED_SAMPLE, UNAVAILABLE}`（5 值，`V17` 机械抽取 `domain`），而 `FactorResearchService` 类 Javadoc 的**词表**（`T24`，`FRS:49`）只列 `RAW / COST_APPROX / EXECUTABLE` **3 值** ⟹ `unreconciled = ['FILLED_SAMPLE','UNAVAILABLE']`，判 `SCOPE_VOCAB_UNRECONCILED`（**注册项，不作 kill**） | 见下 §59 | 不改任何生产代码、不改 Javadoc、不改词表、不改 `ResearchConvention` | ①把 `FILLED_SAMPLE`/`UNAVAILABLE` **补进**类 Javadoc 词表（最小改动，属**改已冻结口径的对外声明**，须用户确认）；②或明确「`metric_scope` 是**开放**键、Javadoc 只举例」并在 `docs/研究口径规范.md` 声明；③维持现状 + 本台账留痕；④**与 `D-R012-02` 合并评审**（同一个键、同一族问题） **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R012-02 | R012 | 冻结口径 / 价格基准语义 | **同名 `metric_scope = "RAW"` 在两个面指向不同价格基准**：legacy 面（`FRS:74` `RANK_IC_BASIS_NOTE` 声明 `forward return = close[t+h]/close[t]-1`，其 `prices.close()` 是 **qfq 前复权**，见 `FRS:898`/`:951` 注释）vs snapshot 面（`FRS:340-350` 用 `snapshotPriceCache.close()`，其缓存**只读** `marketPriceRawRepository`、日期由 raw 构造 ⟹ **rawClose 不复权**）。两者**各自声明为真、描述不同数据路径**，按冻结 K3 措辞**不构成 kill**（`V18` 同型判 `CONSISTENT_DISTINCT_SOURCES`） | 见下 §60 | 不改任何生产代码、不改常量值、不改 DTO/前端契约 | ①在两面的 `note` 里**显式区分价格基准**（如 legacy 写 `RAW(qfq_close)`、snapshot 写 `RAW(raw_close)`）——**改公开载荷文本**，须契约评审；②或抽出一个**面级基准键**（新增字段，不动旧字段语义）；③维持现状 + 本台账留痕（**不**主张这是缺陷，只主张「同名不同基准」需要读者警惕） **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R010-07 | R010 | 测试契约 / 零守卫 | `labelDefinition()` 的**净收益公式算术在测试层零守卫**：唯一触碰它的测试体 `=1`，其中算术断言 `=0`；测试树公式算术字面量 `C16 = 0`；`ResearchConventionTest:54-57` **只做子串断言**（`exit_price_h`/`min_net_return`/`h in {2,3}`） | 见下 §54 | 本轮**未**新增任何测试 | ①加算术守卫测试（断言 `labelDefinition()` 的公式与其伴随数值一致）⟹ **当前会失败**（这正是 `K3`），**必须**与 `D-R010-01` 的修法一并决定；②顺序：**先**定 `D-R010-01` 方向，**再**落测试（否则留长期红灯） **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R013-01 | R013 | 因子口径 / 标签语义（**与 `D-R006-07` 同一代码事实，R013 首次作为承重杀点**） | **零斜率仍断言方向**：五组平均收益**逐位相等**（全为 `0.03125`）时标签输出 `MONOTONIC_INCREASING`；且 `increasing`/`decreasing` **两 flag 同时为 `true`** ⟹ 取值由**分支顺序**（`increasing` 在前）决定，而非由数据决定。默认 `groupCount = 5` 即可触发，**不需任何客户端输入** | 见下 §61 | 不改任何生产代码、不改枚举、不改前端 | ①全相等（零斜率）时返回 `"N/A"`；②或新增枚举值 `NO_DISPERSION`（**改枚举 = 改契约**，须评审前端渲染与消费方）；③仅补文档说明该情境。**与 `D-R006-07` 合并评审**（同一代码事实，避免重复决策） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R013-02 | R013 | 公开 API / 退化输入契约（**与 `D-R006-04` 同一代码事实，R013 新增「形状被污染」后果**） | `groupCount > n` 时**占位零参与方向断言**：`factorGroupCount = 20`、每日 10 样本 ⟹ 10 个 `observations == 0 && avgReturn == 0.0` 的组被当作观测参与比较，真实 10 组**严格递减且全为负**却被判 `NON_MONOTONIC`（占位零制造出并不存在的上升），`long_short_spread` 被污染为 **`+0.0078125`**（真序列全负） | 见下 §62 | 不改任何生产代码、不改 DTO、不加校验 | ①空组输出「空」标记（或 `avg_return = null`）而非 `0.0`；②给 `factorGroupCount` 补服务端约束（**改契约**）；③至少补文档。**与 `D-R006-04` 合并评审**（同一代码事实；`D-R006-04` 测的是 spread 构成，R013 测的是**形状/方向断言**） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R013-03 | R013 | 公开 API / 输入校验可达性（**`D-R006-04` 建议①的证据补强**） | `factorGroupCount` **无服务端校验**：`ScoringBacktestRequest:37-38` 的 3 个候选注解（`@Min`/`@Max`/`@Valid`）**全部未命中**；`backtest.ts:12` 仅 `factorGroupCount?: number`；前端 `ScoringBacktest.vue:36` `:min="2" :max="10"` **仅 UI 缓解** ⟹ 冻结措辞下可达性 = **`REACHABLE_PUBLIC_API`** | 见下 §63 | 不新增任何校验、不改 TS 类型、不改前端 | ①补 `@Min(2)`/`@Max(上限)` 之类约束（**改契约**，须评审）；②或 DTO 层钳位 + 在载荷中**显式标记已钳位**（避免 `D-R010-06` 的「计算取钳位值、持久化取原始值」同型问题）；③维持现状 + 文档。**与 `D-R006-04` 合并评审** **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R013-04 | R013 | 公开 API / 载荷精度（**与 `D-R007-09` 相邻但后果不同**） | `round4` 使标签**在载荷层面不可重建**：五组均值 `1.4e-4/1.2e-4/1.0e-4/8e-5/6e-5` **严格递减**（引擎判 `MONOTONIC_DECREASING`），经 `round4` 后**全部塌成 `1.0E-4`** ⟹ 由**载荷自身**重建得 `MONOTONIC_INCREASING`，**与引擎标签不同** | 见下 §64 | 不改任何生产代码、不改精度口径 | ①载荷给出**未取整**的组均值（或至少给出判定所依赖的比较信息）；②或为标签补一条「由全精度序列计算」的显式标注；③维持现状 + 文档声明「载荷组均值不足以重建标签」。**均属改公开载荷面 ⟹ record-only**；与 `D-R007-09`（`round4` 的 `NaN`/`Inf` 与伪零宽区间）**同族但不同后果**，可合并评审 **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R013-05 | R013 | 前端契约 / 值域声明 | `factor.ts:111`、`:202` 均为 `monotonicity: string` ⟹ **无值域声明**（三读数一致）；消费方无法静态校验取值集合 | 见下 §65 | 不改任何前端文件、不改 TS 类型 | ①改为字面量联合类型（如 `'MONOTONIC_INCREASING' \| … \| 'N/A'`）——**改前端契约**，须评审；②或先补注释/tooltip；③维持现状 + 文档。**与 `D-R012-01`（词表未对账）合并评审**（同一族：取值域 vs 对外声明） | **PENDING_USER** |
| D-R013-06 | R013 | 公开 API / DTO 覆盖 | 两个 DTO **均不携带**标签：`FactorAnalysis`（12 字段 / 14 builder 方法）**有** `groupedReturns`、**无** `monotonicity`；`FactorIcSummary`（9 字段 / 10 builder 方法）**两者皆无** ⟹ 经 DTO 的消费方**拿不到**标签 | 见下 §66 | 不改任何 DTO | ①按需给 `FactorAnalysis` 增字段（**改 DTO/契约**，须评审 + 迁移）；②或明确声明「标签只存在于载荷 map 中、不在 DTO 中」并补文档；③维持现状 **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R013-07 | R013 | 公开 API / 披露粒度 | 3 个标签块披露粒度不一：`buildSnapshotScopeBlock`（无 `group_count`、**有**每组样本量）、`buildRankIc`（**有** `group_count`、有每组样本量）、`buildNetIcBlock`（**两者皆无**）；净收益块的 summary 实际仍用 `RANK_IC_GROUP_COUNT = 5` 与 `RANK_IC_MIN_CROSS_SECTION_SIZE = 10`（`FRS:790-796`），但读者**只能从兄弟块推断** | 见下 §67 | 不改任何载荷键 | ①给 `cost_net_ic` 块补 `group_count`（与 `grouped_returns`）；②或在 `note` 文本中声明组数与每组样本量口径；**改公开载荷面 ⟹ record-only**；与 R012 的「承重块补键」决策（`D-R012-01` 同族）**合并评审** **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R013-08 | R013 | 因子口径 / 参数敏感性（**披露项，不主张缺陷**） | **同一份数据因 `groupCount` 移动 ±20% 而改变标签**：`G02_grid_flat_pairs`（周期 2 取值）在 `gc = 4` 与 `gc = 6` 给 `MONOTONIC_INCREASING`（spread `0.0`），在**默认** `gc = 5` 给 `NON_MONOTONIC`（spread `0.0046875`）；`minSize ∈ {8,10,12}` 不改变任何非退化网格标签（只改变 `G03` 短截面的纳入/丢弃） | 见下 §68 | 不改任何生产代码、不改默认值 | ①在载荷/展示面标注「标签依赖组数参数」并**给出所用组数**；②或维持现状 + 文档披露；**不**建议改默认组数（属改因子口径，须用户确认） | **PENDING_USER** |
| D-R013-09 | R013 | 退化输入 / 非有限组均值（**明确不作 `K2` 承重**） | 含非有限 `forwardReturn` 的面板：组均值出现 `NaN`，**未被 IC 有限性过滤**，标签仍为 `MONOTONIC_INCREASING`（`NaN` 与任何数比较恒假 ⟹ 既不产生严格反序对、也不产生递增对）；**可达性 = `SYNTHETIC_ONLY`**（生产调用点收益由受守卫算术产生：`ScoringBacktestService:955-1015` 入场守卫链 ⟹ `entryCost > 0` ⟹ 收益有限，**已核验**） | 见下 §69 | 不改任何生产代码 | ①若未来收益来源变化，给组均值加有限性守卫（与 `D-R006-05` 的 `emptySummary` 占位族同型）；②当前**不可达**，最低成本 = 文档披露 + 本台账留痕 **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R014-01 | R014 | 公开 API / 载荷披露（`K1` 承重面） | 两数组载荷**不发射分组边界规则**：分布实为 `F01:100` 稳定排序 + `F01:108` `Math.min(g - 1, (int) ((long) i * g / n))`（下标等分），但载荷键集内**无**任何 `group_boundary_rule`/`bucket`/`等分` 类字段（`V05 = RULE_UNDECLARED`，`v05_adjacent_generic_text_hits = 3` 仅为相邻文案的通用词）⟹ 读者**只能从兄弟块或源码反推** | 见下 §70 | 不改任何载荷键 | ①在携带两数组的块（`F02:526-554`）中补一个规则标识键（如 `group_boundary_rule: "INDEX_EQUAL_SPLIT_STABLE_SORT"`）；②或至少在 `note` 文本中声明等分规则与并列处理；**改公开载荷面 ⟹ record-only**；与 `D-R013-07`（披露粒度）、`D-R012-01`（承重块补键）**同族合并评审** **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R014-02 | R014 | 公开 API / 行序语义（`K1` 承重面） | **桶成员依赖未声明的输入行序**：同一多重集（`panel_hash` 相同）仅把日内并列行序取逆，组均值即改变 `0.0011333333333333334`（G1 `0.00255→0.0036833333333333336`、G2 `0.00595→0.005383333333333334`、G3 `0.00935→0.008783333333333334`），而 `rank_ic` **不变**（`maxdiff = 0.0`）；两次取逆可**逐位复原** ⟹ 依赖真实且可撤销。**影响面未测量**（真实行序来自 DB ⟹ `B-R014-02`） | 见下 §71 | 不改任何生产代码；**不**据此声称「真实数据上分组收益会随机跳变」 | ①在读取截面处显式钉住行序（`ORDER BY` 到足够唯一的键）；②或在载荷中声明「并列行的相对次序不作保证」；③或让分桶对并列**不敏感**（按名次而非行序）；**均属改因子口径/公开契约 ⟹ record-only**；与 `D-R013-02`/`D-R006-04`（退化输入族）**不同根因**，**单列评审** **RESOLVED**（2026-09-15 用户裁定 C「改实现」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R014-03 | R014 | 公开 API / 单位语义（`K2` 承重面） | **同名不同量**：聚合面 `grouped_returns[].observations = 3`（= `cross_section_days`，**天数**）vs 日级面同名字段 `observations = 2`（**每组名字数**）；两者都由 `F01:114-115`/`:179-183` 的 `rets.size()` 产生，**含义随聚合层级改变而字段名不变** | 见下 §72 | 不改任何生产代码、不改 DTO、不改前端 | ①给日级面字段改名（如 `name_count`）并与聚合面区分；②或在 DTO 上补**字段级 javadoc** 声明单位（`ScoringBacktestResponse.java:98-117` 当前**无**字段级注释，而其后 `PeriodStat` **有**）；③或在载荷中增加显式单位键；**改契约 ⟹ record-only** **RESOLVED**（2026-09-15 用户裁定 B「改名字」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R014-04 | R014 | 前端契约 / 标签一致性（`K2` 承重面） | **两面标签不一致**：因子面 `FactorResearch.vue:151` 为「观测数」、回测面 `ScoringBacktest.vue:378` 为「截面日观测」（`v07_labels_equal = false`）⟹ 同一用户在两面看到的「观测」不是同一个量；**同一页面同标签指向两个量**：`F10:131`「观测数」→ `total_observations = 30`（Σ 名字数）与 `F10:151`「观测数」→ 组 `observations = 3`（天数） | 见下 §73 | 不改任何前端文件 | ①统一两面标签（与 `D-R006-01` 对 `rank_ic_*` 标签缺 `Rank` 限定的处置**同族**）；②给同页两处标签加限定词（如「截面日数」vs「成分股数」）；**改前端展示口径 ⟹ record-only** | **PENDING_USER** |
| D-R014-05 | R014 | 退化输入 / 非有限值表示（`K3` 承重面，**`D-R006-05`/`D-R013-09` 同型**） | 引擎 `NaN` 组均值在**因子面**被写成 **`0.0`**、在 **DTO 面**保持 `NaN` ⟹ **把「无数据」显示成「0 收益」**，且**只有一面发生**。根因：`F02:1030-1031` `round4(v) = Math.round(v * 10000.0) / 10000.0` **无有限性守卫**，`Math.round(NaN) = 0`（真实 JVM 标定，`_scratch/round_semantics.txt`）；实测 `P4` 日 2 的 G2/G4 两例 | 见下 §74 | 不改任何生产代码 | ①给 `round4` 加有限性守卫（`!Double.isFinite(v) → 原样返回或 null`）；②或统一两面策略（DTO 承载原始值 + 展示层取整）；**改冻结口径的取整行为 ⟹ record-only**；与 `D-R006-05`（`emptySummary` 占位 0）、`D-R013-09`（非有限组均值）**合并评审**：**同一「非有限值被写成具体数」形态的第三/第四个实例** **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R014-06 | R014 | 退化输入 / 计数语义（`K4` 承重面） | **计数字段把非有限观测计入**：`NaN` 前向收益**不被过滤**（`F01:140` 只按行数比较 `minCrossSectionSize`、`:158` 按 `obs.size()` 累加），`P4` 日 2 的 `sample_size = 10`（有限值仅 **8**）、该日仍有 `rank_ic = 0.6139845991168652`、`total_observations = 20`（含 2 个 `NaN`）、2 个组均值 `NaN`。**可达性读数分歧**：按事前配置 = `REACHABLE_PUBLIC_API`；运行中在冻结根内找到 **7** 处收益构造守卫（`F02:335`/`346`/`359`/`379`/`700`/`712`/`726`）⟹ 生产路径下**当前不可达**（`SYNTHETIC_ONLY`）；判定不变（`K1`/`K2`/`K3` 独立） | 见下 §75 | 不改任何生产代码；**不**据此改 `K4`（保留按事前配置的 `true`，只披露） | ①计数只统计**有限**观测（或另给 `finite_count`）；②或对含非有限值的日**早退**（与 `F01:150` 的 IC 有限性早退**同型**，但那发生在算完之后）；③与 `D-R013-09` **合并评审**（同一「非有限收益进引擎」根因，R013 后果=标签、R014 后果=计数） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R014-07 | R014 | 公开 API / 丢弃披露（`V17`，**描述性、不计 `K`**） | `minCrossSectionSize = 10` **静默丢弃**截面日：`P6` 面板 **6** 天（规模 12,12,4,12,4,12）⟹ 载荷 `cross_section_days = 4`（丢 **2** 天）；两发射方法键集合计 **27** 个中**零**个披露该丢弃；且常量本身**不可从载荷读出**（`FRS:70-71` 的 `RANK_IC_MIN_CROSS_SECTION_SIZE` **从不入载荷**）。宽候选键 `partitions_skipped` 语义**无关**（取值 `skippedDates` 溯源到 `F02:279` `isReusableManifest` ⟹ 清单可复用性） | 见下 §76 | 不改任何生产代码 | ①新增「丢弃日计数 + 阈值」字段（`D-R006-03` 对退化日丢弃的**同一建议**，可**合并**）；②或在 `universe_note` 中显式追加丢弃说明；③**不要**改成保留不足规模日（会引入更差口径） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R014-08 | R014 | 公开 API / 载荷精度（`K3` 承重面，**与 `D-R013-04` 同一 `round4` 根因、后果不同**） | **两面数值不等**：因子面 `round4(引擎值)` vs DTO 面**原样**（`F03:1018-1046`，唯一不经 `round4` 的发射面）。95 个值中 **65** 个被截断（`rank_ic` **19** / `avg_return` **46**），最大截断 **5.000000000000143e-05**（= 4 位小数的半个刻度）；实例 `rank_ic 0.9878787878787879→0.9879`、`0.9878044218151566→0.9878`、`avg_return 0.00255→0.0026`、`0.00595→0.006`、`0.004533333333333334→0.0045`。**反向锚点**：移除 `round4` 后两面分歧 = **0** | 见下 §77 | 不改任何生产代码、不改 DTO | ①统一为「DTO/载荷承载**原始值** + 展示层取整」；②或两面都用**同一**取整函数（最小改动，但仍有精度损失且需声明位数）；③或补精度声明键（`D-R014-09`）；与 `D-R007-09`、`D-R013-04` **合并评审**（同一 `round4`，三处不同后果：区间/标签/双面数值） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R014-09 | R014 | 公开 API / 取整声明（`V14`，**描述性、不计 `K`**） | 载荷**不声明**取整规则：键集内**零**命中（`V14 = RULE_UNDECLARED`）；而 `F02:1030-1031` 实际做了 4 位小数的 `Math.round` ⟹ 读者无法从载荷判断「这个数是原始值还是取整值」，也无法重建取整前的值（与 `D-R014-08` 互为表里） | 见下 §78 | 不改任何载荷键 | ①在载荷中补 `rounding_rule` / `precision` 声明键；②或改由展示层取整（则载荷无需声明）；**改公开载荷面 ⟹ record-only**；与 `D-R014-01`（分组规则声明）**同族**：都属于「**载荷不声明自己的构造规则**」 **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R014-10 | R014 | 装置层 / 冻结纪律（**元项，不改生产代码**） | **冻结表未逐变体冻结 `reachability` 等级**：`R014_prereg.md` §8 只冻结了可达性**阶梯**（`REACHABLE_PUBLIC_API > REACHABLE_CLIENT_INPUT > SYNTHETIC_ONLY > UNREACHABLE`），逐变体等级由**运行前**落盘的 `config.json` 给出 ⟹ `V16` 被标 `REACHABLE_PUBLIC_API` 使 `K4 = true`，但运行中在冻结根内找到 **7** 处守卫**反证**该等级（`A-03`）。本轮**保留** `K4 = true`、**未**放宽任何 `K`，但该结构缺口会在同类装置中**反复**出现 | 见下 §79 | 不改本轮已冻结的 `R014_prereg.md`（冻结文本一字未改）；不改 `config.json` | ①后续同类装置把逐变体 `reachability` 纳入**预注册冻结表**（而不是 `config.json`）；②或在大纲中要求「等级判定必须附**守卫清单**证据」；**属装置层改进 ⟹ record-only**（与 `B-R014-02` 互补：一个管**能测什么**、一个管**怎么冻结**） | **PENDING_USER** |
| D-R015-01 | R015 | 公开载荷契约 / 披露（**`K1` 承重的披露面**） | **回退未具名**：`universe_source` 是**常量**（实测取值为 `sparse_selector_result`，`snapshot` 面为 `feature_snapshot_row`）、`note` 也**不提**回退；去 marker 后有 **29** 个可误分类场景 ⟹ 若下游只看 `universe_source` 会读不出「本行的 universe 已被替换成 totalObservations」 | 见下 §80 | 不改生产代码；本轮**保留** `K1` 判定、**未**放宽 | ①给回退加**具名标记**（新键或把回退值写进 `universe_source`）；②或至少在 `note` 中说明回退 —— 均属协议 §7 record-only **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R015-02 | R015 | 口径披露（**降级登记，非本轮发现**） | legacy `funnel` 块带 `metric_scope = COST_APPROX`，但块内**无任何成本口径内容**；且顶层 `metric_scope` 与 `funnel` 内 `metric_scope` 语义面不同（顶层另有 `rank_ic` 的 `metric_scope = RAW`） | 见下 §81 | 只登记，不写作本轮发现 | 沿用 R004 的处置（**点名 R004**） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R015-03 | R015 | 键名设计 / 披露 | 两对**别名键**（`feature_valid_count`≡`valid_observations`、`selector_item_count`≡`total_observations`）在载荷、前端、后端**均无任何说明其区别的文本**（三读数实测：读数 (b) 命中的 4 行**全是界面标签**，不构成「声明」⟹ 该变体 `reading_dependent = true`） | 见下 §82 | 只登记；**不作** `K1` 承重（读数歧义） | 沿用 `D-R013-07` 的处置（**点名 R013**）：改键名或加说明 —— record-only **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |
| D-R015-04 | R015 | 前端契约 / 类型声明 | 前端 `factor.ts:52–60` **只声明 7 键**，未声明 `universe_days_with_snapshot` / `valid_observations` / `total_observations` / `cross_section_days` / `universe_source`；`coverage: number` **无单位说明**（实际是百分比刻度） | 见下 §83 | 只登记 | 沿用 R009 `A5b` 的处置（**点名 R009**）：`factor.ts:830` 有声明、`:810` 无 —— 属前端契约 record-only | **PENDING_USER** |
| D-R015-05 | R015 | **反例登记（明确不构成问题）** | `coverage` 是**百分比刻度**（`×100`，实测 `83.33` / `66.67`），而前端 `FactorResearch.vue:408–419` 的阈值 `80` / `50` **与该刻度一致** ⟹ **无阈值错配**；我此前「阈值可能按 0–1 刻度写」的假设**已被读数否证** | 见下 §84 | —（**不**登记为缺陷） | **明确登记为「不得写成发现」**（防止后续轮次把它当新发现重复提交） **RESOLVED**（2026-09-15 用户裁定 A「做」；实施见 `decisions/decision_batch3_2026-09-15.md`） |

> **本表索引勘误（R010 补记）**：R009 新增的 `D-R009-01…08` **只写进了 §40–§47，漏登进本汇总表**，
> 而 `progress.md` §2 的计数已把它们算入（48 项）。R010 收口时**补登八行**（与 `blocked_queue.md`
> 对 `B-R004-01`/`B-R009-*` 的处置同型），使「计数 = 表内条数」重新一致：
> 补登前表内 **40** 行（R001:4 + R002:2 + R003:2 + R004:3 + R005:3 + R006:7 + R007:9 + R008:10）、
> 而 `progress.md` §2 的计数是 **48**（差额恰为 R009 那 8 条）；补登八行后表内 **48** 行 = 计数 48；
> 再新增 `D-R010-01…07` 七行后表内 **55** 行 = `progress.md` §2 的新计数 **55**。
> **该勘误只补索引，不改 §40–§47 的正文内容。**
>
> **R014 补记（计数口径）**：R014 新增 `D-R014-01…10` **十行**（§70–§79）。
> 表内累计 **80** 行 = R001:4 + R002:2 + R003:2 + R004:3 + R005:3 + R006:7 + R007:9 + R008:10 + R009:8
> + R010:7 + R011:4 + R012:2 + R013:9 + **R014:10**（`D-R0xx-yy` 去重后无重复行）；
> R014 收口时已据此更新 `progress.md` §2 的计数。
> **本轮无索引勘误**（十行同时写进索引与 §70–§79 正文）。
> 另：附属于 `shadow_registry.md` 的 **R012/R013 漏登**已在 R014 收口时**补登**（该文件口径与本节独立）。
>
> **R015 补记（计数口径与收工登记）**：R015 新增 `D-R015-01…05` **五行**（§80–§84），**同时**写进本汇总表与正文 ⟹ **本轮无索引勘误**。
> 表内累计 **85** 行 = R001:4 + R002:2 + R003:2 + R004:3 + R005:3 + R006:7 + R007:9 + R008:10 + R009:8 + R010:7 + R011:4 + R012:2 + R013:9 + R014:10 + **R015:5**
> （`D-R0xx-yy` 去重后无重复行）；R015 收口时已据此更新 `progress.md` §2 的计数。
> **收工登记**：R015 是**最后一轮**（用户明确停止 ⟹ 协议 §10 第 1 种终止情形）。表内 **85** 项**全部保持 `PENDING_USER`**，
> **不因收工而撤回、降级或改写任何一项**；R015 的 5 项中 `D-R015-05` 是**反例登记**（明确「不得写成发现」），其余 4 项全部 `record-only`。
>
> **⚠️ 2026-09-15 状态更正（收工后，逐行核对本表得出）**：上句为**收工时**记录。此后
> `D-R011-03` 经用户**裁定**（选项②+③，见 §57）已记 **`RESOLVED`** ⟹ 现为
> **`PENDING_USER` 83 项 + `RESOLVED` 2 项**（`D-R001-04`、`D-R011-03`），**总行数仍 85**。
> 其余 83 项**一律未撤回、未降级、未改写**（与收工纪律一致）。

---

## §1 D-R001-01：`amount` / `volume` 单位错配（最重要）

### 事实

| 字段 | DDL / 文档声明 | 实测单位 | 证据 |
|---|---|---|---|
| `volume` | `成交量(股)` | **手（100 股）** | 600519 2026-06-15：库值 41,586；`turnover_rate=0.33%` × 流通股 12.56 亿股 ≈ 414 万股 = 41,586 手 ✓ |
| `amount` | `成交额(元)` | **万元** | 同股同日：库值 530,365.56；41,586 手 × 100 × 均价 1,275 元 ≈ 53.0 亿元 = 530,365.56 万元 ✓ |

全市场交叉验证：`amount / (volume × close_price) = 0.00996 ≈ 1/100`（2026-06-15，5,457 只）。
`000001`、`300750` 两只逐股核对同样吻合。

### 影响面（已分级）

1. **绝对阈值型用法受影响（潜在严重）**：
   `ScoringUtils.avgAmount20` 的 Javadoc 明写「日均成交额（**元**）」，并被默认值
   `minAvgAmount = 30_000_000`（元）直接比较。若 `amount` 实为万元，则该默认阈值
   = **300 亿元/日**，全市场无一只股票能通过 → 流动性闸会拒绝整个池。
   实测 `amount` 中位数 = **11,464**（万元）、p90 = **89,341**（万元），全部低于 3,000 万。
   调用点：`StockSelectorService:58`、`MultiDimScoringService:110`/`:291`、
   `OversoldReboundScoringService:66`/`:567`。
2. **相对型因子不受影响**：`volumeRatio`、`illiq20` 等经截面 z-score 的因子对常数缩放不敏感，
   分子分母同尺度抵消。
3. **本机当前实例是否真的被阻断，本轮未验证**（需要实跑选股接口并统计通过率）——
   这是**待补的下一步**，不做推测。

### 为什么是 PENDING_USER

修 `minAvgAmount` 默认值或改 `avgAmount20` 语义 = 改**质量闸阈值/口径**，
落在协议 §7 的「只能记录」清单内。且本轮属研究轮，改动范围被预注册限定为
`research/paper_loop/**`。

### 建议动作（按顺序）

1. 先补一个**单位断言测试**（不改行为）：用真实一行数据断言
   `amount / (volume × close_price) ≈ 0.01`，让错配在 CI 里可见；
2. 再实跑一次选股接口，统计 `passesMinAvgAmount` 的通过率；
3. 据 1+2 的结果决定是「改默认阈值」还是「统一改注释与语义」——**两者都需要用户确认**。

---

## §2 D-R001-02：反转窗口的跨窗口不一致（**不是**改权重的依据）

### 观测

合并 IS（2024-01-02–2025-12-31，h=3）：

| N | rank IC | HAC t | D1 净收益 | D10 净收益 |
|---|---|---|---|---|
| 1 | **−0.0108** | **−1.58（不显著）** | +0.150% | +0.031% |
| 5 | −0.0397 | −3.62 | +0.420% | −0.110% |
| 20 | −0.0605 | −7.29 | +0.658% | +0.121% |

分年看还有反转：2024 年 N=1 的 IC 是 **+0.0043**（正号，不显著），
2025 年 N=1 是 −0.0259（t −3.87，显著）。

生产引擎 `CodexShortCycleScoringEngine` 的权重为 `er20m5 +0.28`、`er5 +0.20`、`er1 −0.15`，
即对 1 日收益给**负**权重（反转方向），而本轮本地数据显示 **N=1 在合并 IS 上不显著**。

### 为什么这不是改权重的证据

1. 本轮判定是 **INCONCLUSIVE**，**整个主结果按预注册不得作为证据使用**（K7 触发）；
2. 窗口比较是**事后**读取的，不在预注册的判定路径内，属探索性观察；
3. R001 的样本只有 1.7 年、且发现板块断层与幸存者偏差，不足以支撑权重结论；
4. 硬红线 3：「不得用全样本最优参数充当结论」。

### 建议动作

若要跟进，唯一合规路径是**新开一轮**（R00x），按预注册流程把
「`er1` 方向在 A 股短周期是否应为正」当作**独立假设**来检验，且产物**只能是影子因子**
（零权重、并行计算、记录 IC），提升仍需用户确认。

---

## §3 D-R001-03：论文版本差异

- 立项依据（反转腿）与 DOI 指向 **JFE 134(1):48–69 (2019)**（A 级正刊）。
- 本轮实际可获取的全文是 **NBER WP 24458（2018-03 首发，2018-12 修订）**（B 级载体）。
- SSRN 在本地网络下不可达（curl exit 35 / SSL connect error，直连与代理均失败）→
  未能取得出版版全文与在线附录。
- 影响：**不改变判定**（因为判定依据是本地实测，不是论文表述），但若后续要复核
  「出版版是否补了反转腿的表格与 t 值」，需要用可访问的渠道再取一次。

无紧急动作；已在 `R001_review.md` 与 `R001_report.md` 中双标注。

---

## §4 D-R002-01：2026-02 的批量补录（**入库覆盖度**问题）

### 事实（全部来自本地盘查 `baselines/B002_universe_bias.json`）

| 证据 | 值 |
|---|---|
| 首次出现日在 2026-02 的代码数 | **1,888** |
| 占 OOS 截面单元格比例 | **29.9%** |
| 占 IS 截面单元格比例 | **0%** |
| 名单样例 | `300001 … 300019`（创业板最早一批代码段） |

这些代码在 2026-02 **之前一行行情都没有**，之后突然全量出现。
本地证据只能证明「首次出现日集中」，**不能**证明其真实上市日期；
外部常识（创业板 2009-10 首批）与本地缺口一致，但按硬红线 1，**外部常识不作为结论依据**。

### 影响

1. **研究侧**：任何跨 2024→2026 的因子对比都含**构成断层**。R002 已量化：
   构成一致化只能解释样本外衰减的 **17.0%**（K2 阈值 50%），故**不是**衰减主因；
   但这不等于该断层可以忽略——它是**已知混淆项**，后续每轮预注册都要显式处理。
2. **工程侧**：这是**可修复的入库覆盖缺口**，不是数据源本身不可得。
   若真实生产环境同样只在 2026-02 才补齐创业板/科创板历史，则任何
   使用 `minAvgAmount`、`avgAmount20`、历史波动等**需要历史窗口**的选股逻辑，
   在 2026-02 之前对这批股票会**静默跳过**（数据不足）而非报错。

### 为什么是 PENDING_USER

- 修改入库/调度/回补逻辑 = 改**数据源与入库口径**，落在协议 §7「只能记录」清单内；
- 「引入新数据源或付费数据」同样在清单内；
- 本轮（R002）为研究轮，改动范围被预注册限定为 `research/paper_loop/**`。

### 建议动作（按顺序，均需用户确认）

1. 加一条**只读**的覆盖度监控：按代码统计 `market_data` 首行日期，
   若某日新增代码数超过阈值（如 >500）即告警——**不改入库逻辑**；
2. 人工确认 2026-02 是否确有回补作业，以及**2026-02 之前的创业板/科创板数据是否本可获取**；
3. 若本可获取，再讨论是否补录；若不可获取，则把它写进研究口径的**永久限制项**。

---

## §5 D-R002-02：退市收益的缺失（**量级已验证为可忽略**）

### 事实

| 证据 | 值 |
|---|---|
| 面板内中途停止交易的名字（attrited） | **99 / 5,555 = 1.78%** |
| attrited 名字在 D1（输家分位）的占比 | **3.43%** |
| attrited 名字在 D10（赢家分位）的占比 | **1.59%** |
| 可用 δ 补末端收益的缺失退出信号 | **2,311**（其中 attrited 名字内 396） |
| 以 δ=−30% 补录后，D1 净收益变化 | **−0.10bp**（对照 R001 价差 +53.7bp，占 0.19%） |
| ±20% δ 敏感性（−24% / −36%） | 0.08bp / 0.12bp，单调，结论不变 |

**机制方向成立、量级不足**：退市名字确实更集中在输家分位（1.9 倍），
但对收益价差的影响可忽略。

### 为什么仍记入队列

- 本地**没有**退市收益字段，δ=−30% 是借用美国数据的约定值，
  「δ 在 A 股适用与否」**本地无法验证**（已在 R002 报告 §7.2 披露）；
- 若将来要做**严肃的**生存者偏差校正，必须引入 PIT 池 + 退市收益数据源，
  这属协议 §7 的「引入新数据源」，**agent 不得自行执行**。

### 保守处理

不做任何数据替换、不引入外部数据、不改 `market_data`。
R001 报告已加**附录 A 勘误**：池子并非纯存活样本，缺的是末端/退市收益与已退市代码。


---

## §6 D-R003-01：「多空收益差」的口径结构问题（**本轮最重要的跨代码发现**）

### 事实

生产代码里被命名为「多空」的量，是**两个多头腿净收益之差**：

| 位置 | 代码 | 事实 |
|---|---|---|
| `FactorResearchEngine.java:43` | `record ... longShortSpread` | 字段被命名为 long-short spread |
| `FactorResearchEngine.java:185-186` | `grouped.get(size-1).avgReturn() - grouped.get(0).avgReturn()` | 取的是**最高分组与最低分组**的差 |
| `FactorResearchService.java:727-735` | 逐股净收益 `net = exit(1−税−滑点)/(entry(1+佣金+滑点))−1` | 每条腿各自是**多头**净收益 |
| `FactorResearchService.java:835` | `net.put("long_short_spread", round4(s.longShortSpread()))` | 对外输出的就是它 |
| `FactorResearchService.java:70` | `RANK_IC_GROUP_COUNT = 5` | 生产侧用**五分位**（本轮研究用十分位，形状相同） |
| `ScoringBacktestService.java:1013-1015` | 同样的 `ret()` 净收益 | 同口径 |
| `ScoringBacktestService.java:353` | `new CrossSectionObservation(finalScore, ret())` | 用净收益建截面观测 |
| `ScoringBacktestService.java:1046` | `.longShortSpread(summary.longShortSpread())` | 同口径输出 |
| `ScoringBacktest.vue:262` | 「多空收益差」 | 前端按多空口径标注 |
| `FactorResearch.vue:138 / 242 / 316` | 「多空 spread」 | 同上 |

### 为什么这是结构问题（不是近似问题）

`net(D1) − net(D10) = κ(c) · [gross(D1) − gross(D10)]`，其中
`κ(c) = (1 − (印花税+滑点)·c/12) / (1 + (佣金+滑点)·c/12)`。
两条腿套**同一个** `κ` ⟹ **成本在差分里恒等抵消**，价差只被整体缩放。

R003 实验侧的直接证据（`experiments/R003_cost_breakeven/result.json`）：
成本从 0bp 拉到 36bp（3 倍冻结口径），IS h=3 的该价差**逐位不变**：

| 成本 | 0bp | 6bp | 12bp | 24bp | 36bp |
|---|---|---|---|---|---|
| 「多空价差」（读数 a） | 53.7874 | 53.7874 | 53.7874 | 53.7874 | 53.7874 |
| **美元中性**净价差（`毛价差 − 2c`） | 53.7874 | 41.7874 | **29.7874** | 5.7874 | **−18.2126** |
| 美元中性净价差的 NW t | 2.6303 | — | **1.4567** | 0.2830 | −0.8906 |

**量级**：冻结口径下展示值高出 **80.6%**；t 值从**显著**（2.6303）掉到**不显著**（1.4567）。

### 这不是 bug，是口径选择——但名字是错的

`daily_reversal` 这类**纯多头**策略的 `net(D1) − net(D10)` 在不能做空的 A 股语境下
有其自身意义（D10 往往不可做空，不是可交易的空头）。
**问题在于它被叫作「多空」**——任何按论文口径理解该字段的人（包括本协议此前的 agent）
都会**系统性高估**该策略的成本承受力。

同样地，R003 报告 §5 已说明：判定**不因**这一发现而改变（两读数同落 D1b），
但它是本轮**唯一具工程后果**的产出，且**不能由 agent 自行修正**——
字段语义是公开 API 契约的一部分。

### 保守处理

- **不改** `FactorResearchEngine` / `FactorResearchService` / `ScoringBacktestService`；
- **不改** `long_short_spread` 字段语义与四舍五入行为；
- **不改**两处前端文案；
- R003 报告如实写明该字段实际含义，避免后续轮次误用。

### 建议动作（三选一，均需用户确认）

1. **最小动作**：只补文档 + 前端 tooltip，明写「该值为最高分组与最低分组的**多头净收益之差**，
   非美元中性多空」。零契约变更。
2. **推荐**：**新增**一个字段（如 `long_short_neutral_spread` = 原值 − `2c`）并只在新字段上标注真多空；
   旧字段保留，前端可选择展示两者。**不改旧契约**，可回退。
3. **激进**：直接改旧字段语义。**不建议**——`FactorResearchService.java:835` 与
   `ScoringBacktestService.java:1046` 是既有输出，改语义会造成历史结果不可比，
   且需要前端与任何消费方同步评审。

---

## §7 D-R003-02：一对「一跳下界」要不要纳入成本口径

### 事实

R003 已实测证伪两条本地有效价差估计路线（详见 `blocked_queue.md` B-R003-01）：

| 路线 | 实测中位 | 折算跳数 | 判断 |
|---|---|---|---|
| Corwin–Schultz 逐股 | 95.01bp | ≈13.0 跳 | 不可信（86.8% 名字 >4 跳） |
| 月度 Roll | 781.87bp | ≈110 跳 | 不可信（高出 1–2 个数量级） |

论文用的 Hasbrouck (2009) 有效价差本地**不可得**，且论文自己就声明日频自协方差估计
*infeasible due to the relatively high proportion of positive autocovariances*；
本地独立实测 73.7% 的日频自协方差为正，与该声明吻合。

因此成本侧唯一可用的是**模型无关下界**：A 股最小报价单位 1 跳 = 0.01 元，
市价单穿越 ≥1 跳价差时每边至少付半跳 ⟹ 双边成本 ≥ `0.01/price`。

| 范围 | p10 | p50 | p90 |
|---|---|---|---|
| 全池一跳（bp） | — | **7.0972** | 23.0414 |
| D1 池内一跳（bp） | 2.9586 | **9.2764** | 32.1543 |
| D1 入场价（元） | 3.11 | 10.78 | 33.80 |

### 关键点：这是**下界**，不是估计量

R003 的 `r* = 26.8937bp`（美元中性读数）= **2.899 跳**（按 D1 一跳 p50 折算；
按全池 p50 折算为 3.789 跳），**落在**一跳成本的 p50 与 p90 之间。
也就是说：真实价差若普遍为 1 跳 ⟹ 论文主张不成立；若普遍 ≥3 跳 ⟹ 成立。
**本地没有数据回答「普遍几跳」**，这正是本轮判 INCONCLUSIVE 的原因。

**因此把「一跳下界」直接写进 `ResearchConvention` 是危险的**：它会把
「成本至少这么多」偷换成「成本就是这么多」，从而把本轮的 INCONCLUSIVE
伪造成一个确定结论。

### 保守处理

- **不改** `ResearchConvention.java:52-56` 的任何成本常数（佣金万三 / 印花税万五 / 单边滑点万二）；
- 「一跳下界」只在研究文档与 R003 预注册的模型网格中作为**显式对照量**出现（M2），
  不作为生产口径、不参与打分、不影响任何决策；
- 不引入任何外部或付费价差数据（属 §7 保留权限）。

### 建议动作（均需用户确认）

1. **推荐（零契约风险）**：维持现状。把「一跳下界」写成研究文档里的标准对照量，
   今后凡做成本类研究都同时报「跳跃下界」与「可能的真实价差区间」。
2. 若确要补数据：需引入**报价级或价差级**数据源 → 属「引入新数据源」，须单独立项评审。
3. 若确要改口径：必须与 **D-R003-01**（多空口径）**合并评审**——
   两者都触及 `ResearchConvention` / 输出语义，分开改会造成半新半旧的口径并存。

---

## §8 D-R004-01：`long_short_spread` **少扣一条腿的成本**，且偏差会**翻转样本外符号**（R004 新增；与 D-R003-01 同一字段，须合并评审）

### 事实

R004 用**项目自己的**成本表达式（`buy = (1+滑点+佣金)`、`sell = (1−滑点−佣金−印花税)`，
即往返 **14.9990bp**，见 `experiments/R004_semantics_audit/result.json` 的 `T2_cost_model_divergence`）
构造了真正的**双腿美元中性**组合，并在**组均值层面**分别聚合两条腿后相加：

```
R_L = mean_high[(1+g)·a/b − 1]        （多头腿：买入 → 卖出）
R_S = mean_low [a − (1+g)·b]          （空头腿：卖出 → 买回）
two_leg = R_L + R_S
```

与项目现行值（单腿口径：两条腿各乘同一个成本因子，在差分中相消）之差 `G`：

| 段 | 项目单腿值 | 真双腿净 | `G` = 单腿 − 双腿 | 阈值 `max(5bp, 0.30×\|S_twoleg\|)` | 触发 |
|---|---|---|---|---|---|
| IS | −53.7067bp | **−83.8530bp** | **+30.1463bp** | 25.1559bp | ✅ |
| OOS | **+18.9129bp** | **−11.0764bp** | **+29.9893bp** | 5.0000bp | ✅ |

**两条必须先看清的性质**（否则这个数会被误用）：

1. **`G` 的大小由成本常数决定，与策略收益水平无关。** 解析上
   `G = (a/b − 1 + a − b) + g_L·(a/b − b)`，第一项只含成本常数（`s=1` 时 ≈ −30bp），第二项是二阶小量。
   这解释了为什么 IS（−53bp）与 OOS（+19bp）的 `G` **几乎同一个数**。
   **`G(0) = 0` 精确成立**（`T3`：双腿零成本恒等，`abs_diff = 0.0`）。
2. **代价是符号级的，不是幅值级的。** IS 只是「更负一些」；**OOS 直接翻符号**。

### 与 D-R003-01 的关系（**必须一起评审**）

| 项 | R003 的口径 | R004 的口径 | 差异原因 |
|---|---|---|---|
| 成本量 | `c_rt = 12bp` | **项目实际往返 15bp** | R003 的 `apply_cost` 只在**买入侧**收佣金；项目表达式**两侧都收**（差 3bp = 1×佣金） |
| 美元中性应扣的量 | `2c = 24bp`（线性近似） | `G ≈ 30bp`（两腿各一整次往返） | 见上表 |
| 偏差是否翻符号 | 未测 | **测到 OOS 翻符号** | R003 未做 OOS 的双腿构造 |

⟹ **两轮结论方向一致、量级不同：R003 的 24bp 低估了实际偏差，且 R003 未发现符号翻转。**
两条都指向同一字段的同一问题，**分开改会造成半新半旧的口径并存**。

### 这不是 bug

`daily_reversal` 是**纯多头**策略，`net(D1) − net(D10)` 在不能做空的 A 股语境下有自身意义
（见 §6 同段论述）。**问题在于它被命名为「多空 spread / 多空收益差 / 超额」**，
而它**既不承担空头腿的成本、也不减 Rf**。任何按论文口径理解该字段的人都会**系统性高估**其成本承受力。

### 保守处理

- **不改** `FactorResearchEngine` / `FactorResearchService` / `ScoringBacktestService`；
- **不改** `long_short_spread` / `longShortSpread` / `primary_long_short_spread` 的字段语义与四舍五入；
- **不改**任何前端文案；
- R004 报告如实写明该字段的实际含义与偏差量级，避免后续轮次误用。

### 建议动作（与 D-R003-01 合并，三选一，均需用户确认）

1. **最小动作**：文档 + 前端 tooltip 明写「该值为**最高分组与最低分组的多头净收益之差**，
   非美元中性多空；真实双腿净口径下 OOS 符号会翻转」。零契约变更。
2. **推荐**：**新增**一个并行字段（如 `long_short_two_leg_spread`，用项目自己的成本表达式双腿构造），
   旧字段保留、语义不变，前端可选择同时展示两者。**不改旧契约，可回退。**
3. **激进**：直接改旧字段语义。**不建议**——会破坏历史结果可比性，且需前端与所有消费方同步评审。

---

## §9 D-R004-02：展示层口径标注**不对称**（R004 新增）

### 事实

全仓 `metric_scope` 命中 **47** 处：`.java` 24 / `.ts` 15 / `.vue` **8**
（`.ts` 全部是类型声明 `metric_scope?: string`，用户不可见）。

| 展示层界面（`modules/web/src/**/*.vue`） | 呈现该量 | 标注口径 | `metric_scope` 命中 |
|---|---|---|---|
| `views/factor/FactorResearch.vue` | 是 | **是**（292–295 行：`主口径：{{ unifiedResult.metric_scope \|\| 'COST_APPROX' }}（成本后近似）`，并标 `EXECUTABLE`） | **8** |
| `views/backtest/ScoringBacktest.vue` | **是**（「多空收益差」卡片，259–283 行） | **否** | **0** |

`ScoringBacktest.vue` 该卡片现有的全部内容是：标题「高分 vs 低分收益差」、`×100` 后的 `%` 数值
（按正负配 `success` / `danger` 色）、文案「高分组合收益高于低分组合，选股有效」、「样本覆盖」。
**没有**口径标注、**没有**周期（h 日）、**没有**「非年化非超额」声明。
另：全仓「非超额 / 无基准」类显式标注命中 **0** 处。

### 为什么这构成问题

同一个量在两个界面上**一个标口径、一个不标**。用户在 `ScoringBacktest` 页面看到 `+18.91%`
（`success` 绿/红配色 + 「选股有效」文案）时，无法知道它是**成本后近似**值、
是 **h 日非年化**值、还是**不减基准**的毛超额。
结合 §8（该值在真双腿口径下 OOS 为负），这个缺失的标注**不是风格问题**。

### 保守处理

- **不改**任何前端文件；本项**只登记**。

### 建议动作（均需用户确认）

1. **推荐**：给 `ScoringBacktest.vue` 的该卡片补四项最小标注——口径（`COST_APPROX` / `RAW`）、
   周期（h 日）、「非年化」、「非超额（未减基准 / 未减 Rf）」，与 `FactorResearch.vue` 对齐。
2. 可选：在 `FactorResearchService` 的 RAW 块说明中补一句「本块与 COST_APPROX 块**不是同一价格窗口**」
   （对应 R004 的 A3 发现，见 §10 与 R004 报告 §4.3）。

---

## §10 D-R004-03：`market_price_raw` 为空时，`metric_scope = COST_APPROX` 与 `long_short_spread = 0.0` 并存（R004 新增）

### 事实

`FactorResearchService` 的 `COST_APPROX` 块要求 `market_price_raw`（V2-MP-014 的 raw-only 口径）。
本地实测 **`market_price_raw` = 0 行**（见 `blocked_queue.md` B-R004-01）。

后果链（代码证据，本轮**只读**）：

1. 每个观测都因缺 raw 价而被**跳过**；
2. `emptyNetIcBlock()` 仍被写出，其中 `metric_scope` 被设为 `COST_APPROX`；
3. 同一块内 `long_short_spread` 为占位 **`0.0`**；
4. **没有任何字段或文案**说明「这是因为没有 raw 数据」。

⟹ 一个**公开契约**下的歧义：消费方**无法区分**
「成本后净收益恰好为 0」与「无 raw 数据、全部观测被跳过」。

### 为什么这比「(ii) 算不出」更重要

R004 的 A3 轴原计划比较 `RAW` 块与 `COST_APPROX` 块来分离成本项，
因该表为空而**只能改用同窗口代理**（R004 报告 §4.3、§7 第 6 条）。
也就是说：**一个空的输入 + 一个默认的占位输出 + 一个仍然标称的口径 = 一个会被误读的接口**。
这与研究优先级 #1（未来函数 / 数据时点）属**同一类**问题（契约在退化输入下的行为），
R004 报告 §9.7 已把它列为 R005 的首选方向。

### 保守处理

- **不改**任何生产代码、**不改** DTO 与任何字段默认值；
- R004 报告与 `blocked_queue.md` 双处声明该路径在本地**不可计算**。

### 建议动作（均需用户确认）

1. **推荐**：在 `emptyNetIcBlock()` 里加一个显式标志（如 `net_basis_available: false`
   或 `metric_scope_note: "NO_RAW_PRICE_DATA"`），使「无数据」与「恰好为 0」可区分。**新增字段，不改旧字段语义。**
2. 备选：把该处的占位值从 `0.0` 改为 `null`（**会触及前端渲染逻辑，风险更高**）。
3. 备选：仅补文档与注释，明确声明该歧义（零契约变更，但消费方仍可能误读）。

---

## §11 D-R005-01：同一业务键下存在多个 `result_id`，且无权威标记（R005 新增）

### 事实

`selector_result` 的**主键唯一**由 DB 约束保证（所以**行级重复不存在**）。但按**业务键**
`(trade_date, factor_version, topn)` 分组后：

| 项 | 数值 |
|---|---|
| 存在多个 `result_id` 的业务键组 | **18 组** |
| 每组 `result_id` 个数 | **2–4 个** |
| 受影响的 `selector_result_item` 行 | **3528 / 5156 = 68.4%** |
| 其中**逐位相同**的重跑组 | **12 组** |
| 其中**选择集不同**的组 | **6 组** |
| 其中**近乎/完全不相交**的组 | **4 组**（`2026-05-19`、`2026-06-30` 的 jaccard = **0.000**） |
| **零样本**的组 | **5 组** |

（`K3b` 因此触发；`DUPLICATE_NOISE = 0` 是预注册阈值，本轮为 0 容差。）

### 为什么这比「行级重复」严重

- 行级重复会被主键约束挡住，**这个形态不会被挡住**——它藏在**业务键**层。
  ⟹ 方法论规则：**审计「重复」必须查业务键，不能只查主键/整行。**
- 同一天同一参数有 2–4 次运行结果，其中 6 组**内容不同**、4 组**几乎毫无交集**
  ⟹ 任何「按 `trade_date` 复现历史选股」的研究**都无法确定该复现哪一次**。
- **没有任何字段**（时间戳之外的语义字段）可以用来判定哪一次是权威运行。
  运行时刻可以排序，但「最后一次 = 权威」只是一个**未经声明的约定**，
  不能作为研究口径使用。

### 保守处理

- **不改**任何代码、**不改**契约、**不加**唯一约束（加约束会**拒绝写入**，
  属行为变更 + 需迁移评审）；
- R005 本轮的 `A4b` 判定**只登记不修复**（报告 §4.2）。

### 建议动作（均需用户确认）

1. **推荐**：新增一个「运行权威性」标记（**新增字段，不动旧字段语义**），
   使消费方可以只取权威运行；并明确该标记的写入规则。
2. 备选：加业务键唯一约束（**须先评审：现有 18 组数据如何处置**；可能需迁移）。
3. 成因调查：**6 组选择集为何不同**——需要**运行时的输入快照**（当前不存在），
   属「新增持久化」的研究载体变更，见协议 §7 保留权限，**本轮不实现**。

---

## §12 D-R005-02：占位 `0` 与真实 `0` 不可区分（R005 新增）

### 事实

`selector_result_item` 的 **4 个版本整族**把 `avg_amount20` 写成 **`0`**：

| 项 | 数值 |
|---|---|
| `avg_amount20 = 0` 的行 | **2154 行** |
| `avg_amount20 IS NULL` 的行 | **0 行** |
| 涉及 `factor_version` 数 | **4 个整族** |
| 同表自声明的 `min_avg_amount` | 出现 `30000` / `30000000` 两档（见 §13） |

`avg_amount20 = 0` 与「日均成交额 0」在语义上是**极不可能**的（该名字当天有成交才能进池），
且 `0 < min_avg_amount` ⟹ 这些行**逐行都不满足该表自己声明的质量闸**。
⟹ 触发 **`K8`**（占位 0 污染语义 0 点）。

### 与既有发现的关系

| 既有项 | 关系 |
|---|---|
| `D-R004-03`（`market_price_raw` 空 ⟹ `long_short_spread = 0.0`） | **同一形态的第二个实例**：**退化输入 + 默认占位输出 + 仍标称的语义**。区别在于 R004 是在**运行时代码**里，R005 是在**已落库的数据**里 |
| `D-R001-01`（单位错配） | R005 的 `D-R005-03` 与它同源；本条（占位 0）**不同源**，是「缺值编码」问题 |
| `B-R005-02`（6–9% 收益无法复现） | 相邻但不同：那条是**收益列**的复现残差，本条是**成交额列**的缺值编码 |

### 保守处理

- **不改**任何生产代码、**不改** DDL、**不回填**任何历史数据
  （回填 = 覆盖已有数据，属破坏性操作，且无法从本地数据源唯一确定真值）；
- R005 报告与 `blocked_queue.md` 双处声明该列在部分版本**不可用**。

### 建议动作（均需用户确认）

1. **推荐**：占位值改用 **`NULL`**——`NULL` 天然可与真实 `0` 区分，
   且不需要新增字段。**须同时确认消费方对 `NULL` 的处理**（`SUM`/`AVG` 语义会变）。
2. 备选：补一个「`avg_amount20` 是否已计算」的标志位（**新增字段**）。
3. 备选：仅补文档，声明「这 4 个版本的该列**不表示成交额**」。

---

## §13 D-R005-03：`min_avg_amount` 同列出现元/千元两种量纲（R005 新增，与 D-R001-01 同源）

### 事实

`selector_result_item.min_avg_amount` **同一列**出现两档值：

| 档位 | 值 | 相对关系 |
|---|---|---|
| 低档 | `30000` | — |
| 高档 | `30000000` | 恰为低档的 **1000×** |

同时 `avg_amount20` 的实测量级为 **`8.16e4` – `6.41e5`**：
- 若其单位为「元」，则正常个股日均成交额在 **8 万–64 万元**——明显偏低；
- 若其单位为「**千元**」，则为 **0.82 亿–6.41 亿元**——与 A 股实际**自洽**。

结合 `D-R001-01`（`amount` 实为**万元**、DDL 注释写「元」）：
`30000` 与 `30000000` 相差恰好 1000×，**正是元/千元（或元/万元）之比**
⟹ 两者很可能是**同一逻辑阈值的两种量纲写法**，而不是两个不同的业务规则。

### 保守处理

- **不改**任何口径、**不改**任何阈值；
- R005 报告 §4.6 已将此列为 **`RX2` 交叉核对（只登记）**，未参与任何轴的判定。

### 建议动作（均需用户确认）

1. **推荐**：与 **`D-R001-01` 合并评审**——两者是同一个根因（单位约定与实际量纲不一致）的
   两处表现；合并后可以用**同一份单位断言测试**统一收口
   （先写测试断言真实单位，再决定是否修阈值）。
2. 备选：在 DDL 注释与本表文档中显式写明该列的量纲，**不改值**。
3. **不建议**：直接把小数改成与大数一致——在单位未确认前这属于猜测性回填。

---

## §14 D-R006-01：`rank_ic_*` 的展示标签缺 `Rank` 限定且与 `Pearson IC` 混合配对（R006 新增）

### 事实

`FactorResearch.vue` **同一个文件内部即不一致**：

| 行 | 标签 | 绑定字段 | 序列来源 | 是否含 `Rank` |
|---|---|---|---|---|
| `:132` | `Rank IC 均值` | `rank_ic_mean` | rank | ✅ |
| `:133` | `IC 标准差` | **`rank_ic_std`** | **rank** | ❌ **缺** |
| `:134` | `ICIR：{{ fmt4(blk.rank_ic_ir) }}` | **`rank_ic_ir`** | **rank** | ❌ **缺** |
| `:135` | `IC 正率` | `rank_ic_positive_rate` | rank | ❌ **缺** |
| `:136` | `Pearson IC` | `pearson_ic_mean` | Pearson | — |
| `:239` | `IC 均值` | `rank_ic_mean` | **rank** | ❌ **缺**（与 `:132` 同字段不同标签） |

- **混合配对**：同一张卡片同时展示 `Pearson IC`（Pearson 序列）与 `ICIR`（**rank 序列**）。
  论文 **Table 1** 把 `IC, ICIR` 与 `RankIC, RankICIR` 列为**不同指标**，
  且式 (18) 的 `ICIR` 分子是 **IC（Pearson）序列** ⟹ 读者会自然把 `Pearson IC` 与 `ICIR` 读成一对，
  但**项目未提供任何 Pearson 版 ICIR**（全仓 **0 处** `RankICIR` 标签）。
- 数值本身是**对的**（见 `rounds/R006_report.md` `A1a`：`rank_ic_ir` 与「rank 序列 ÷T」**逐位相等**）。

### 触发

预注册 `K6` ⟹ 判定词 `CONTRACT_AMBIGUOUS`（**不杀总判定**）。

### 保守处理

- **不改**任何前端文件、**不改**任何字段名；
- `rounds/R006_report.md` §8 已列入负面清单：本轮**不得**被叙述成「前端算错了」。

### 建议动作（均需用户确认）

1. **推荐（最小改动）**：给 `:133` / `:134` / `:135` / `:239` 四处标签补 `Rank` 限定（纯文案，不动契约）。
2. 备选：若要展示 Pearson 版 ICIR，须**新增字段**（不动旧字段语义）。
3. **不建议**：把字段名 `rank_ic_ir` 改成 `icir`——那会**反向**加重歧义，且属契约变更。

---

## §15 D-R006-02：ICIR 分母用 ÷T（项目） vs ÷(T−1)（论文式 (18)）（R006 新增）

### 事实

| 项 | 值 | 证据 |
|---|---|---|
| 项目实现 | `icVar` 用 `.average()`（**÷T**），`icIr = icMean / sqrt(icVar)` | `FactorResearchEngine.java:172-174` |
| 论文判据 | 式 (18) `ICIR = IC / sqrt((1/(T−1))Σ(IC_i − IC̄)²)`，且 "RankICIR is defined similarly"；式 (22) 的 `σ_r` 同为 `T−1` ⟹ 论文**内部一致** | `R006_review.md` §2.4 |
| 实测比值 | `1.0307764064044151`（T=17）、`1.0540925533894596`（T=10），与 `sqrt(T/(T−1))` 差 `0.0` / `2.2e-16` | `result.json` `A3b` |
| 8 单元网格 | 两个面板的匹配单元**都只有** `div_T`（网格含**缩放/年化维**，未只报最佳单元） | `result.json` `A3a` |

### 量级（必须如实标注）

T=17 ⟹ **+3.08%**；T=10 ⟹ **+5.41%**。项目 |IR| 比论文口径**大**这一因子。
**单独绝不足以判 `REJECTED`**（`K2` 只给 `CONVENTION_DIVERGENCE`）。

### 保守处理

- **不改** `ResearchConvention` 的任何约定、**不改**任何代码；
- 本轮**未**把该差异写成「项目 ICIR 虚高」——只写「**恰为该因子**的确定性偏差」。

### 建议动作（均需用户确认）

1. **推荐**：只在文档中声明分母口径（`÷T`），不动代码——量级太弱，不值得动已冻结口径。
2. 若确要改：须**同时**改 `rank_ic_std` 与 `rank_ic_ir`（两者同分母），并做迁移评审
   （历史 `selector_result` 的 `rank_ic_ir` 无法重算 ⟹ 会形成**跨期口径不一致**）。
3. **不建议**：为 3–5% 的差单独动冻结口径。

---

## §16 D-R006-03：退化截面日被静默丢弃且无任何丢弃计数字段（R006 新增）

### 事实

`FactorResearchEngine.summarize()` 对**非有限 IC 的截面日**直接跳过：

```java
double ic = spearmanRankCorrelation(scores, rets);
if (!Double.isFinite(ic)) { continue; }   // :150-152
```

后果（注入面板：**7** 个截面日 = 4 正常 + 3 退化，每截面 12 只，退化 = 得分在该截面恒定）：

| 输出量 | 实测 | 说明 |
|---|---|---|
| `cross_section_days` | **4** | 分母被静默缩小 |
| `daily_size` | **4** | — |
| `total_observations` | **48 = 4 × 12** | 退化日**零贡献**（丢 3 天） |
| `rank_ic_positive_rate` | 分母同为 `daily_size` | 正率随丢弃自动漂移 |
| 丢弃计数字段 | **全仓 0 处** | `dropped_days`/`skipped_days`/`excluded_days`/`filtered_days`/`nan_days`/`invalid_days`/`dropped_count`/`discarded` **一处都没有** |

### 为什么这是问题（判据档位说明）

- 论文式 (5)/(7) 的 `1/T Σ_{t=1}^{T}` 是对**全部 T 期**取均值 ⟹ **L 档**判据说「不该只对保留的日取均值」；
- 丢弃发生在**观测到结果之后**（先算 IC 才决定丢不丢）⟹ 属**对结果取选择**。
  **注意：这不是前视 / 未来函数**（列入报告 §8 负面清单）。

### 保守处理

- **不改**任何生产代码、**不改** DTO；
- **不**把它叙述成「数据被删」——原始数据未被修改，被丢的只是**参与统计的日**。

### 建议动作（均需用户确认）

1. **推荐**：新增「丢弃日计数」字段（如 `dropped_cross_section_days`），**不动旧字段语义**；
2. 备选：在 `universe_note` 中显式追加丢弃说明（不改契约，但语义较弱）；
3. **不建议**：把退化日按 IC=0 计入均值——那是另一种占位 0（与 `D-R006-05` 同类错误）。

---

## §17 D-R006-04：`groupCount > n` 时空组被伪造为 0 ⟹ `long_short_spread` 退化为 `0 − G1`（R006 新增）

### 事实

| 项 | 值 | 证据 |
|---|---|---|
| 输入 | `n = 12`、`groupCount = 20` | 注入面板 |
| 空组数 | **8**（`observations == 0 且 avgReturn == 0.0`） | `result.json` `A5a` |
| `long_short_spread` | `-0.012665740650684378` | — |
| `0 − g1_avg` | `-0.012665740650684378` | **逐位相等** ⟹ spread 完全由 `G1` 决定、**符号反转** |
| 根因 | `:102 Math.min(groupCount, n)` 单日只产 `g` 组，`:180-183` 却按 `1..groupCount` 全建并把空组刷成 `avg=0` | 读码 |

判据档位 **L**：论文式 (19) `w = ±1/K` 要求**每腿恰 K 只股票**，空腿使该定义不成立。

### 可达性（必须与 `D-R006-05` 区分，**两种不同等级**）

| 路径 | 能否触发 | 依据 |
|---|---|---|
| `FactorResearchService` 端点 | ❌ **不可** | 三处 `summarize` 调用点**全部**传常量 `RANK_IC_GROUP_COUNT = 5`（`:70`） |
| 直接 API `ScoringBacktestRequest.factorGroupCount` | ✅ **可达** | `ScoringBacktestRequest.java:38` **无 `@Min`/`@Max`** |
| 前端 `ScoringBacktest.vue:36` | ❌ **不可达** | `:min="2" :max="10"` |
| 触发的必要条件 | `groupCount ≥ 11` | 两个 Service 的截面下限常量**都是 10**（`FactorResearchService.java:71`、`ScoringBacktestService.java:58`）⟹ 参与统计的日必有 `n ≥ 10` |

**减缓项成立（`A5c` = `NOT_VIOLATED`）**：每组 `observations` **既有导出也有渲染**
（`FactorResearchService.java:599/:784`、`ScoringBacktestService.java:1043` 三处 `.observations(...)`；
`ScoringBacktest.vue:378`「截面日观测」、`:507`、`FactorResearch.vue:151/:254`「观测数」）
⟹ 空组在表格里显示为「**观测数 0**」。
**但** `long_short_spread` 这个**标量**在别处单独展示、无「存在空组」标注 ⟹ 标量仍可被误读。

### 保守处理

- **不改**任何生产代码、**不改** DTO、**不加**约束；
- 报告 §8 负面清单：❌「用户可见的 spread 会经常是负数」——**经界面不可达**。

### 建议动作（均需用户确认）

1. 给 `factorGroupCount` 补 `@Min`/`@Max` 类约束（**改契约**，须评审）；
2. 或让空组输出「空」标记而非 `avg=0`（**改 DTO**）；
3. 或仅补文档（优先级**低于** `D-R006-03`/`D-R006-05`，因经界面不可达）。

---

## §18 D-R006-05：定义失效时输出具体实数（运行时 IC 路径）（R006 新增，**须合并评审**）

### 事实（三个子项）

**① 零有效观测**（`summarize(∅)` ⟹ `emptySummary`）：
`rank_ic_mean` / `rank_ic_std` / `rank_ic_ir` / `rank_ic_positive_rate` / `pearson_ic_mean` / `long_short_spread`
**六项全为 `0.0`**（`daily_size = 0`、`grouped_size = 0`）。
**诚实边界**：`monotonicity = "N/A"` 与 `universe_note` 提供**部分**可区分信息
⟹ **不得**叙述为「完全不可区分」。判据档位 **P**（项目自建工程契约，非文献要求）。

**② `std = 0` 时 IR 硬写 0 —— 本轮最有力的一条**（代码位 `:174`）：

```java
double icIr = icStd > 1e-12 ? icMean / icStd : 0;
```

`T=1` 面板实测：case0 `mean=+1.0, std=0.0, ir=0.0`；**case1 `mean=−1.0, std=0.0, ir=0.0`**。

| 方向 | 真极限 | 写成 | 偏误方向 |
|---|---|---|---|
| `mean > 0` | `+∞` | `0.0` | **偏保守** |
| `mean < 0` | `−∞` | `0.0` | **偏乐观** |

⟹ 同一份载荷内出现 **`rank_ic_mean = −1.00` 与 `rank_ic_ir = 0.00` 的自相矛盾配对**。
**可达性**：只需**一个**参与统计的截面日（`n ≥ 10`）即得 `std = 0` ⟹ **经正常 API 可达**
（与 `D-R006-04` 的「仅直接 API 可达」是**两种不同等级**，不得混同）。

**③ `pearsonIcMean` 的 `? 0` 分支是死分支**：`:176` 位于 `:166-168 if (daily.isEmpty()) return emptySummary` 之后，
再由 `A4b` 的代数等价（`var(scores)=0 或 var(rets)=0 ⟺ 秩零方差 ⟺ 两处 `isFinite` 丢弃集合相同`，
300 例扫描 0 反例）⟹ `dailyPearson` 非空 ⟹ `0` 分支**恒不执行**。

### 与既有条目的关系（**合并评审的理由**）

| 条目 | 形态 |
|---|---|
| `D-R004-03` | `market_price_raw` 空 ⟹ `COST_APPROX` 跳过全部观测，仍写 `metric_scope` 与 `long_short_spread = 0.0` |
| `D-R005-02` | `selector_result_item` 4 个版本整族写 `avg_amount20 = 0`（2154 行） |
| **`D-R006-05`** | **运行时代码的 IC 路径**：零观测六项全 0 + `std=0 → ir=0` |

⟹ 三者是**同一「退化输入 + 占位输出 + 仍标称语义」形态的三个实例**
（前两例一个在 DB、一个在数据；本例**首次落在运行时代码的 IC 路径上**）。
**必须合并评审**，否则会被当成三个独立小问题分别打补丁。

### 保守处理

- **不改**任何生产代码、**不改** DTO；
- **不**建议把 `÷0` 改成 `NaN`/`±Inf` 直接输出（会污染 JSON 序列化），
  优先方向是**让「未定义」可被区分**（`null` 或标志位）。

### 建议动作（均需用户确认）

1. **推荐**：占位值改 `null`（与 `D-R005-02` 同一推荐）+ 补「未定义」标志位；
2. 备选：仅补文档声明「`T=1` 或 IC 恒定时 `rank_ic_ir` 无意义」；
3. **须与 `D-R004-03` / `D-R005-02` 合并评审**（同一形态第三次出现）。

---

## §19 D-R006-06：`selector_result.ic_value` 是死列（R006 新增）

### 事实

| 项 | 值 | 证据 |
|---|---|---|
| 全仓出现处 | **生产口径恰好 1 处** | `modules/backend-java/src/main/resources/db/migration/V2__research_tables.sql:183`（DDL 本身） |
| 写入路径 | **0 处** | 无任何 `INSERT`/`UPDATE`/`setIcValue` |
| DB 实测 | `COUNT(*) = 67`、**`COUNT(ic_value) = 0`** | 只读查询 |
| 计数口径披露 | 含 `research/` 的原始输出 **13 行 = 1 生产 + 12 本轮自指产物**（附录 `E-3`） | 两种口径均写入 `result.json` |

### 保守处理

- **不改** DDL、**不回填**、**不删列**（删列属不可逆迁移，协议 §7 明令禁止）；
- 同时登记 `blocked_queue.md` **`B-R006-01`**：任何以该列为输入的研究在本地**结构性不可行**。

### 建议动作（均需用户确认）

1. **推荐**：保留该列，在文档/DDL 注释中声明「当前无写入路径」（避免后续轮次把它当可用数据源）；
2. 备选：确认**无任何消费方**后再移除（**破坏性 DDL**，须迁移评审）；
3. 与 `D-R005-01`（`selector_result` 缺运行权威性字段）**同表**，可一并评审。

---

## §20 D-R006-07：零区分度被判 `MONOTONIC_INCREASING`（R006 新增）

### 事实

`assessMonotonicity`（`FactorResearchEngine.java:226-249`）在**各组收益全相等**时：
`cur < prev` 与 `cur > prev` **都不成立** ⟹ `increasing` 与 `decreasing` **同真** ⟹ 代码先判 `increasing`。

实测（单日、`n=10`、`groupCount=5`）：五组均值**全为 `0.01`**（`group_avg_max_gap = 0.0`），
而个股收益方差 `> 0`（否则 IC 非有限、走不到该分支）⟹ 返回 **`MONOTONIC_INCREASING`**，
即**零区分度被判成正向单调**。

### 触发与范围

预注册 `K8` ⟹ 判定词 `REGISTERED_ONLY`：**冻结 K 表内无任何 K 引用 `A7a`**，
故**不进总判定**（金丝雀 `INJ` 把 A7a 非法扩入 `REJECTED` 时检查器会报 `VIOLATED` ⟹ 范围冻结被守卫）。

### 保守处理

- **不改**任何生产代码、**不改**前端；
- 报告 §8 未把它列为「必须修」，但如果将来有 `K` 表引用它，应立即提升优先级。

### 建议动作（均需用户确认）

1. 全相等时返回 `"N/A"`（与 `emptySummary` 的取值一致，**语义更贴切**）；
2. 或新增枚举值 `NO_DISPERSION`（**改枚举 = 改契约**，须评审前端渲染与消费方）；
3. 或仅补文档说明该情境（**最低成本**）。

---

## §21 D-R007-01：样本量不足时输出「零宽区间 + 真实点估计」且无标记（R007 新增，**K1 触发源**）

### 事实

输入 1 个信号日（当日 10 只股票）驱动 `FactorResearchEngine.summarize`，
`FactorResearchService.buildSnapshotScopeBlock` 产出：

| 键 | 值 |
|---|---|
| `cross_section_days` | `1` |
| `total_observations` | `10` |
| `rank_ic_mean` | **`-0.9878787878787879`**（真实点估计） |
| `hac_std` | `0.0` |
| `ci_lower` / `ci_upper` | `0.0` / `0.0`（`ci_is_zero_width = 1`） |
| `ci_contains_mean` | **`0`**（区间**不含**它自己的均值） |

该 block 共 **19 键**，含 `note` 键，但 `note` 只在 `summary.totalObservations() == 0` 时写入
（`FactorResearchService.java:550-551`）；`status` 键只出现在 `unavailableScopeBlock()`（`:560`），
本路径**不经过**它（探针实测 `snapshot_has_status_key = false`）。
记录类型 `FactorResearchSummary` 的字段清单里也**没有**任何「退化/不可用」字段。
载荷层：`:544-546` 对三键套 `round4`，`0.0 → 0.0` ⟹ **HTTP 响应同样成立**（`payload_transform` 已记入 `result.json`）。

### 触发与范围

- 预注册 **K1** ⟹ 总判定 `REJECTED`（本轮**唯一**不依赖任何模拟的杀点）；
- 触发条件不限于 T=1：凡 `n < 2`（或不确定性估计量无法形成）都会走同一条路；
- 与 `D-R006-05`（`emptySummary` 六项全 0）**同族但不同因**：那条是「零观测」，
  本条是「**有真实点估计却给出失效区间**」。两者**不得**合并成一个问题。

### 保守处理

- **不改**任何生产代码、**不改** DTO、**不改**前端；
- 本轮只登记，不提出修复实现。

### 建议动作（均需用户确认）

1. **推荐**：`hac_std` / `ci_lower` / `ci_upper` 在 n<2 时改 `null`（与 `D-R005-02` 的「占位改 null」同向）；
2. 或新增显式状态字段（`ci_status: "INSUFFICIENT_N"` / `"DEGENERATE"`）；
3. 或最低成本：把 `note` 的触发条件从 `totalObservations()==0` 扩到「不确定性估计不可形成」。
---

## §22 D-R007-02：标签写 `HAC 95%CI` 而值来自 block bootstrap（R007 新增，**K4 触发源**）

### 事实

- 标签：`modules/web/src/views/factor/FactorResearch.vue:139`（行内 `<span>HAC 95%CI：…`）与
  `:322`（`<el-table-column label="HAC 95%CI" …>`），全前端 `HAC 95%CI` 命中 **2** 处（C3）；
- 值源：`modules/backend-java/src/main/java/com/quant/service/backtest/FactorResearchEngine.java:191`
  `blockBootstrapCi(icSeries, Math.max(1, holdingDays), 1000, 42L)`；
- 同块内**另有** `hac_std` 字段（真 HAC 标准误，`:544`）⟹ 「HAC 标准误」与「bootstrap 区间」
  两个不同估计量**并置同一块**，而标签只指称前者；
- 探针实测 `says_hac = ['HAC 95%CI', 'HAC 95%CI', 'HAC 95%CI']`、`says_bootstrap = []`。

### 触发与范围

- 预注册 **K4**（表头字面写 `VIOLATED`，§5 冻结词为 `CONTRACT_MISLABEL`；调和见报告附录 A-5）；
- 被指控的是**指称错误**，不是「没算不确定性」：A7a 证明区间可复现、A7b 证明参数硬编码、
  C4 证明生产侧有 2 处声明。**不得**叙述成「项目的 CI 是假的」。

### 保守处理

- **不改**前端、**不改**字段名、**不改** API。

### 建议动作（均需用户确认）

1. **推荐**：标签改 `bootstrap 95%CI`（与值源一致）；
2. 或双标签：`HAC SE / bootstrap CI`；
3. 或 tooltip 明确「SE 为 HAC 标准误，区间为 block bootstrap 分位区间」。
---

## §23 D-R007-03：同一 block 的键集随数据可用性变化（R007 新增，**K7 触发源**）

### 事实

`FactorResearchService.buildRankIc` 在 `latestPerKey.isEmpty()` 时**早返回**（`:660-671`）：

- 早返回分支键集（10 键）：`cross_section_days`、`total_observations`、`rank_ic_mean`、
  `rank_ic_std`、`rank_ic_ir`、`rank_ic_positive_rate`、`long_short_spread`、`daily_rank_ic`、
  `grouped_returns`、`cost_net_ic`；
- 正常路径键集（15 键）：上述 10 键 **加上** `hac_std`、`ci_lower`、`ci_upper`、
  `pearson_ic_mean`、`monotonicity`。

⟹ **同一 API 端点在不同数据状态下返回不同的键集**，且缺失的恰是统计不确定性三键。
前端 `row.ci_lower` 一类绑定在该状态下取到 `undefined`（渲染成空串或 `undefined`）。

### 触发与范围

- 预注册 **K7** ⟹ `REGISTERED_ONLY`（**不**单独构成 `REJECTED`）；
- 这是**契约形状**问题（键稳定性），与 §21 的**值**问题（零宽）是两件事，**不得**混为一谈。

### 保守处理

- **不改**任何生产代码、**不加**字段。

### 建议动作（均需用户确认）

1. **推荐**：早返回分支补齐同键、值置 `null` 并配状态字段；
2. 或统一成「始终输出全部键 + `status`」；
3. 若维持现状，须在 API 文档中显式声明「键集可变」，并让前端按可选键处理。
---

## §24 D-R007-04：`embargo_note` 承诺「以 HAC 标准误为准」，而 HAC 窗口与持有期 h 解耦（R007 新增，**K5 触发源之一**）

### 事实

- 承诺文本 `RANK_IC_EMBARGO_NOTE`（`FactorResearchService.java:83-85`）：
  「…重叠持有期 IC 序列的显著性**以 HAC 标准误与 block bootstrap CI 为准**」；
- 实现：`neweyWestHacStd(List<Double> values)` —— 反射实测 `nw_param_count = 1`、
  `nw_param_types = java.util.List`、`nw_has_integral_param = 0`
  ⟹ **结构上不可能感知重叠阶数 h**；滞后阶由 `lag = max(1, min(floor(n^(1/3)), n−1))` 只依赖 n；
- 同引擎的 `blockBootstrapCi(List,int,int,long)` 有整型块长入参（`bb_has_integral_param = 1`）
  ⟹ 两条路径对 h 的处理**不对称**；
- 材料性实测：h≥5 的 60 格中 **1 格** ratio < 0.90（`min ratio = 0.8848892907156888`，
  n=27/h=10/`MA_equal_hm1`；`dom_cbrt=3` vs `dom_hm1=9`），方向**混合**（10/60 格 < 1）。

### 触发与范围

- 预注册 **K5** = A3c 达材料性 **且** A3d 成立 ⟹ `CONTRACT_MISLABEL`（**非** `REJECTED`）；
- **材料性弱**：只有 1/60 格越过我自设的 0.90 线，方向还混合。
  ⟹ 本条的证据强度**明显低于** §21 / §28，报告与队列都不得把它们并列。
- A3b 证明生产 HAC 实现与 `cbrt` 滞后规则**逐位一致**（120 格 max 差 `5.55e-17`）
  ⟹ 争议在**规范选择**（窗口取 n 还是 h），**不在**算术。

### 保守处理

- **不改**任何生产代码、**不改**承诺文本、**不改**统计口径。

### 建议动作（均需用户确认）

1. 给 `neweyWestHacStd` 增加滞后阶入参，调用点传 `h`（**改签名 = 改契约**，须评审）；
2. 或只改承诺文本，明确「窗口由序列长度决定」；
3. 或保留现状 + 在文档中披露解耦事实与量级（1/60 格）。
---

## §25 D-R007-05：`blockBootstrapCi` javadoc 说「块大小 = h」，实现钳到 `floor(√n)`（R007 新增，**K6 触发源之一**）

### 事实

- javadoc（`FactorResearchEngine.java:355`）：`Block bootstrap 置信区间（处理序列相关，块大小 = 持有期 h）`；
- 实现（`:363` 行内注释已披露）：`bs = max(1, min(min(blockSize, floor(sqrt(n))), n-1))`；
- 探针实测（n=10 逐个 h 复刻对照 CI 并逐位比对以**识别**实际块长）：

| h | 实测有效块长 | 规则期望 | 是否 bs < h |
|---|---|---|---|
| 1 | 1 | 1 | 否 |
| 2 | 2 | 2 | 否 |
| 3 | 3 | 3 | 否 |
| **5** | **3** | 3 | **是** |
| **10** | **3** | 3 | **是** |

### 触发与范围

- 预注册 **K6** ⟹ `CONTRACT_AMBIGUOUS`（**非** `REJECTED`）；
- 实现侧**已在行内注释披露**钳位理由 ⟹ 这是**文档/实现漂移**，不是隐藏 bug。

### 保守处理

- **不改**任何生产代码、**不改**统计口径。

### 建议动作（均需用户确认）

1. **推荐（最低成本）**：javadoc 改为「块大小 = min(h, floor(√n), n−1)」；
2. 或去掉钳位、让块长严格等于 h（**改统计口径**，须评审：n 很小时块数会趋于 1）；
3. 若维持实现，须在 API 文档同步披露。
---

## §26 D-R007-06：CSV 表头 `net_ic` 与同排 `hac_std/ci_*` 属不同口径（R007 新增，**K6 触发源之一**）

### 事实

`modules/web/src/views/factor/FactorResearch.vue` 的 IC 明细 CSV 导出（方法体 `:498-519`）：

- 表头（`:506`）token 实测 = `[holding_days, rank_ic, pearson_ic, icir, positive_rate, monotonicity, spread, `**`net_ic`**`, `**`hac_std`**`, `**`ci_lower`**`, `**`ci_upper`**`]`
  —— `net_ic` **无 `cost_` 前缀、无 `rank_` 限定**；
- 值行（`:510`）：`row.cost_net_ic?.rank_ic_mean ?? ''` **接着** `row.hac_std, row.ci_lower, row.ci_upper`。

⟹ 同一行里，`net_ic` 取的是**成本后**块的 `rank_ic_mean`，而 `hac_std`/`ci_*` 取的是
`row`（**毛收益**块）⟹ **一行内两种口径并列**，且只有前者未标口径。

### 触发与范围

- 预注册 **K6**（A8a 成立）⟹ `CONTRACT_AMBIGUOUS`；A8a 自身的判定词是 `CONTRACT_MISLABEL`；
- 与 `D-R006-01`（`rank_ic_*` 标签缺 `Rank` 限定）**同族**，可合并评审。

### 保守处理

- **不改**任何前端文件、**不改**列名。

### 建议动作（均需用户确认）

1. **推荐**：表头改 `cost_net_ic_rank_ic`，后三列加 `gross_` 前缀或做分组表头；
2. 或后三列改取 `cost_net_ic` 块（**改展示口径**，须评审）；
3. 最低成本：tooltip 说明两列口径不同。
---

## §27 D-R007-07：载荷族输出名义 95% 区间，但全仓 0 处 nominal / 多重检验标注（R007 新增，**K7 触发源之一**）

### 事实

扫描根 `modules/`（排除 `node_modules` / `target` / `dist`），正则
`nominal|多重检验|多重比较|bonferroni|benjamini|\bFDR\b|adjusted p|multiple testing`：

- **生产面命中 = 0**；
- 全仓命中 **14** 处，**全部**位于 `research/paper_loop/experiments/R007_uncertainty_audit/run_uncertainty_audit.py`
  （即**本轮自己的正则文本**）⟹ 无任何真实标注。

判据来源 HLZ2014 第 4 行（**L 档：门槛存在**）：新因子的 `t` 门槛应为 ~3.0（FWER 5% 需 3.9），
而非 1.96。但论文说的是「**新因子的显著性门槛**」，
**不是**「任何置信区间都必须做多重检验校正」⟹ 该轴只能 `REGISTERED_ONLY`，**不得**作为杀死条件。

### 触发与范围

- 预注册 **K7** ⟹ `REGISTERED_ONLY`；
- **明确禁止**：不得据本轮引入 3.0 / 3.9 门槛。那是 **316 个美股因子、1967–2012** 的**横截面**结论，
  与本项目单因子、1.7 年、A 股面板**不可比**。

### 保守处理

- 不新增任何标注、不改任何口径。

### 建议动作（均需用户确认）

1. **推荐**：前端标签与 API 注释中声明「区间为**名义**水平，未做多重检验校正」；
2. **不做**：把 3.0/3.9 写进任何阈值或质量闸（属冻结口径变更）；
3. 可作为独立轮次再议（例如「项目是否需要多重检验框架」）。
---

## §28 D-R007-08：名义 95% 区间在重叠依赖下的实测覆盖率显著不足（R007 新增，**K3 触发源**）

### 事实

网格：**80 单元 × 2 臂**（n ∈ {5,10,17,27} × h ∈ {1,2,3,5,10} × 5 个 DGP），每单元 **1000** 次重复。
触发判据（**双重**）：实测覆盖率 < 0.85 **且** Clopper–Pearson 单侧 99% 下界 < 0.85
（等价变形 `L < 0.85 ⟺ P(X ≥ k | p = 0.85) > 0.01`；冻结文本已披露该规则在 reps=1000 下
近似等价于「覆盖率 ≲ 87%」）。

- **触发格数：BOOT 62/80、HAC 60/80、任一臂 66/80**；
- `min boot = 0.344`、`min hac = 0.392`（均为 n=5/h=5/`MA_equal_hm1`）；`max boot = 0.933`；
- 按 DGP 平均覆盖率（boot / hac）：`IID 0.848/0.855`、`AR1_0.2 0.794/0.816`、
  `AR1_0.5 0.670/0.719`、`AR1_0.7 0.536/0.594`、`MA_equal_hm1 0.734/0.748`；
- **非退化所致**：全部 80 格的 `zero_width = 0`、`se_zero = 0`；
- 与项目量级相近的单元（n=17）：h=3/`AR1_0.5` = `0.738/0.766`、h=5/`MA_equal_hm1` = `0.662/0.703`。

**机制上与 A3a/A3c/A3d（HAC 窗口与 h 解耦）和 A4a/A4b（块长钳到 `floor(√n)`）互相支撑**；
A2c 同时证明「钳位」这条路径**几乎不可达**（0/20000，post-hoc 负自相关又 0/12000）⟹ 覆盖率不足
**不能**归因于钳位。

### 触发与范围

- 预注册 **K3** ⟹ 总判定 `REJECTED`（与 K1 并列的第二个杀点）；
- **本条的模拟依赖性**：5 个 DGP 是**重叠持有期导致自相关**的风格化表征，
  **不是**对本项目真实 IC 序列依赖结构的主张 ⟹ 见 `blocked_queue.md` **`B-R007-01`**；
- **不得**把 `0.344 / 0.392 / 0.662` 读作「本项目真实覆盖率」。

### 保守处理

- 不改任何生产代码、不改任何阈值、不改统计口径。

### 建议动作（均需用户确认）

1. 前端对区间加说明：「名义水平；重叠持有期下实际覆盖率可能显著低于 95%」；
2. 若要真修，须改统计口径（块长与 h 对齐 / 更长窗口 / 依赖稳健估计量），属**统计口径变更**；
3. 修复前**不得**把该区间用于任何「显著性」叙述（协议 §7：改统计口径须用户确认）。
---

## §29 D-R007-09：`round4` 的 NaN→0、±Inf 饱和与「伪零宽」区间（R007 新增，**post-hoc，未预注册**）

### 事实（**post-hoc 观察，不参与 21 断言与总判定合成**）

`FactorResearchService.round4(v) = Math.round(v * 10000.0) / 10000.0`：

| 输入 | 输出 |
|---|---|
| `NaN` | `0.0` |
| `+Inf` / `-Inf` | `9.223372036854776E14` |
| 任意有限值 | 4 位小数 |

⟹ 当 `1.96 · hac_std < 5e-5`（即 `hac_std < 2.55e-5`）时，`[mean − 1.96·se, mean + 1.96·se]`
在 4 位小数下可**塌成零宽**（`ci_lower == ci_upper`）而 `mean` 非零 ⟹ **伪零宽**。

与 §21（A2a）**同族但不同因**：A2a 是 `n < 2` 使 SE 真实为 0；本项是**显示精度**使区间塌缩。

### 触发与范围

- **本项在预注册的 21 断言之外**（协议：预算外探针须命名 `posthoc_*`，不得参与总判定）；
- ⟹ **不**触发任何 K，**不**改变本轮总判定；
- 若将来要作为判定依据，须**另开一轮带预注册的审计**。

### 保守处理

- 不改任何生产代码、不改序列化精度。

### 建议动作（均需用户确认）

1. 对 `ci_lower`/`ci_upper`/`hac_std` 三键**不套** `round4`（或提高精度）；
2. 或加「零宽但 SE > 0」的载荷层标记；
3. 至少登记本观察，避免后续轮次把它与 A2a 混为一谈。

---

## §30 D-R008-01：短员日的分母是**请求参数** `n`，不是当日实际持仓（R008 新增，**K1/K2 触发源**）

### 事实

`BacktestTurnoverService.java:103` 的 `1.0 - intersection.size() / n`：`n` 是调用方传入的 `topN`
（`A4c` 实测经钳位后由 `top_n` 透明回显），而交集是**前一日与实际候选集合**与**当日**的比较结果。
判别口径（判据甲，L 档）：分母应当是**实际持仓**的规模，**不是**一个由调用方传入的参数。

当日候选数 < `topN`（**短员日**）时：

| 面板 | 输入 | 定义值（判据甲，等权、分母 = 实际持仓） | 生产载荷 | 偏差 |
|---|---|---|---|---|
| `A2a`（双侧短员、集合完全相同） | `n=10`，两日各 3 条且 `{A,B,C}` 相同 | `0.5·((3−3)/3 + (3−3)/3) = **0.0**` | **0.7** | **0.70** |
| `A2b`（单侧短员） | `prev=10`（满员）→ `curr=3`，交集 3 | **0.35** | **0.7** | **0.35 ≥ TH_MAT = 0.10** |
| `A2d`（±20% 敏感性网格） | `n ∈ {8,10,12} × p ∈ {2,3,4}`，18 格 | — | — | **18/18 越线**，err ∈ [0.25, 0.8333] |

偏差的**闭式**（报告 §2.2，`P` 档自建但只用于描述量级）：双侧格 `1 − p/n`、单侧格 `(n − c)/(2n)`。
`A2a` 面板里载荷**自认**无替换（`new_count = exit_count = 0`）却仍报 `0.7`
⟹ 方向是「**凭空造出换手**」，不是「量级偏大」。

### 触发与范围

- 触发条件是**生产默认路径**：`BacktestTurnoverController` 的 `topN` 有默认值，短员日
  在真实数据中是否常见**本机不可测**（`B-R008-01`）——但触发**不需要**短员日常见，
  只需要**存在**一个短员日，且载荷**没有任何**标记把它与满员日区分（见 `D-R008-03`/`D-R008-06`）。
- 影响面：`avg_turnover` / `max_turnover` / `min_turnover` / `daily_turnover[]` 全套，
  以及把换手率接进成本项的**任何下游**（判据甲 Eq.(4) `c·TOt` ⟹ 换手率量级错误直接改变成本结论）。

### 保守处理

- 不改任何生产代码、不改口径、不改前端、不改 `ResearchConvention`。

### 建议动作（均需用户确认）

1. 分母改用**当日实际规模**（如 `min(prev_size, curr_size)` 或上一日实际持仓）——**改公开语义**；
2. 或定义不适用时输出 `UNAVAILABLE`/`null`（与 `D-R008-03` 同一形态）——**改公开语义**；
3. 或至少补文档声明「分母 = `top_n` 请求参数」，并把该声明接入前端 tooltip；
4. **不得**据此轮引入任何新的换手率阈值（本轮无任何阈值来自论文）。

---

## §31 D-R008-02：javadoc 声明了**未被强制**的前置条件（R008 新增，**K4 触发源**）

### 事实

- `BacktestTurnoverService.java:28` javadoc 逐字写「（**两日均有 topN 个候选时**）」——
  即声明了「该口径只在满员日成立」这一**前置条件**；
- 而 `analyze` 方法体（`:40–128`）内**零**守卫行：无 `size()` 比较、无早返回、无告警、
  逐日行也**不含**任何 size 字段（`A5a` 实测缺失键 = `prev_size,curr_size`）。

⟹ 契约文本与行为**不一致**：文本承诺了一个条件，代码**不检查**该条件，载荷**不标注**该条件。

### 触发与范围

- 与 `D-R007-05`（`blockBootstrapCi` javadoc 说「块大小 = h」而实现钳到 `floor(√n)`）**同型**：
  都是「文档写的与实际做的不一致」，都只需改文档即可收口（最小成本解），
  但**真正的修法**（加 guard）都要动行为 ⟹ 属 `record-only`。
- 与 `D-R008-01` **互补**：`D-R008-01` 是「短员日算出来的数是错的」，
  本条是「文档自己说了短员日不适用，却既不检查也不标注」。

### 保守处理

- 不改任何生产代码、不改 javadoc（javadoc 的修改方向需用户先定：是删声明还是加 guard）。

### 建议动作（均需用户确认）

1. **首选**改 javadoc：删掉「两日均有 topN 个候选时」的前置条件声明，承认短员日也会输出，
   并在同一处指向 `D-R008-01` 的处理方式；
2. 或加 guard（抛异常 / 返回不可用 / 截断），但**属行为变更**，须与 `D-R008-01` 合并评审；
3. **不得**只加一行「调用方需自行保证」的免责声明而不做上述任一项。

---

## §32 D-R008-03：定义退化时输出**具体实数** `0.0`，且无机器可读标记（R008 新增，**K7 组成项**）

### 事实

`A2c` 的两个退化面板实测：

| 面板 | `signal_days` | `avg/max/min_turnover` | `daily_turnover` | 不可用标记 |
|---|---|---|---|---|
| `signal_days = 1` | 1 | `0.0 / 0.0 / 0.0` | **空数组** | 仅人类可读 `note`（`:71`、`:127` 两处 `put("note"`） |
| `signal_days = 0` | 0 | `0.0 / 0.0 / 0.0` | **空数组** | 同上 |

全仓机器可读不可用键（`status` / `available` / `basis`）命中 **0** ⟹
消费方**无法**区分「真的算出了 0」与「没有可算的东西」。

**部分可区分性（如实披露）**：`signal_days < 2` 与空 `daily_turnover` 两个信号**组合**起来
可以**推出**「`0.0` 是占位」⟹ 本条判 `REGISTERED_ONLY` 而**不是** `VIOLATED`，
且**不**参与 K1/K2/K3 的杀点。

### 触发与范围

- 与 `D-R004-03`（`metric_scope = COST_APPROX` 与 `long_short_spread = 0.0` 并存）、
  `D-R005-02`（占位 `0` 与真实 `0` 不可区分，2154 行）、`D-R006-05`（`emptySummary` 六项全 0、
  `std=0` 时 IR 硬写 0）、`D-R007-01`（零宽区间与真实点估计并存）**同族** ⟹ **第 5 个实例**。

### 保守处理

- 不改任何生产代码、不改 DTO、不改前端。

### 建议动作（均需用户确认）

1. 占位值改 `null`（与 `D-R005-02` 同一推荐：`null` 天然可与真实 0 区分）；
2. 或新增机器可读标记（如 `turnover_status: "UNAVAILABLE"`）；
3. **与 `D-R004-03` / `D-R005-02` / `D-R006-05` / `D-R007-01` 合并评审**——
   同一形态已出现 5 次，建议一次性定「退化输出的统一约定」，而非逐处打补丁。

---

## §33 D-R008-04：`strategy = null` 的默认路径把**同日不同策略**当作相邻日配对（R008 新增，**K3 触发源**）

### 事实

`A3a` 面板：4 条 `SelectorResult` = 2 策略 × 2 日；`strategy = null`
（控制器 `@RequestParam(required = false)` ⟹ **默认**路径）：

| 量 | 实测 |
|---|---|
| `signal_days` | **4** |
| 窗口内**不同日期**数 | **2** |
| `daily_turnover` 长度 | **3**（= 策略数 × 日数 − 1） |
| 相邻 pair 的 `trade_date` | `2026-05-01, 2026-05-06, **2026-05-06**` |
| `avg_turnover` | **1.0** |
| 严格同策略相邻日对照 | **0.0** |

⟹ 第 2、3 个 pair 是**同一天的两个不同策略**被当成「相邻交易日」；
各策略自身两日集合完全相同（严格同策略配对的换手率应为 `0.0`）⟹ 载荷报 `1.0`。

### 触发与范围

- 这是**默认**调用路径，不是构造出来的极端输入；
- 但「前端是否**总是**显式传 `strategy`」需要**人工确认**（本机无法验证运行时调用分布）——
  若前端总是传参，则本条的**经界面可达性**下降（但仍通过 API 直达）。

### 保守处理

- 不改任何生产代码、不改契约、不改前端。

### 建议动作（均需用户确认）

1. `strategy` 改**必填**（**改契约**，须评审前端全部调用点）；
2. 或 `null` 时**按 `strategy` 分组分别计算**并分行输出（**改行为**）；
3. 或至少补文档 + 前端 tooltip 声明 `null` 的语义是「混算，配对可能跨策略」；
4. 需人工确认：前端各调用点是否**总是**传 `strategy`（见 `D-R008-05`）。

---

## §34 D-R008-05：`strategy = ""` 与 `null` **算得相同、回显不同**（R008 新增，**K6 组成项**）

### 事实

`A3b` 实测：`strategy = null` 与 `strategy = ""` 两次调用的**全部数值逐位相同**
（两者都走同一条「混合」过滤分支），但**回显**分别为 `"ALL"` 与 `""`。

⟹ 载荷**自相矛盾**：数值上两者是同一口径，回显上却给出了两个不同的口径标识。
读者只依据回显无法判断这两次调用是否可比较。

### 触发与范围

- 与 `D-R008-04` **同源**（同一条分支）；判 `CONTRACT_AMBIGUOUS` ⟹ 触发 K6。
- 影响面：**下游缓存/按口径分组的消费方**会把两次调用当成两个口径。

### 保守处理

- 不改任何生产代码。

### 建议动作（均需用户确认）

1. 统一归一：`""` → `null`（或反之），并让**回显值**与实际过滤条件**一致**；
2. 或补文档声明「`""` 与 `null` 等价，回显差异仅表示入参原样」；
3. 与 `D-R008-04` **合并评审**（一次改动同时收口两条）。

---

## §35 D-R008-06：逐日行**缺** `prev_size`/`curr_size`，读者只能反解分母（R008 新增，**K7 组成项**）

### 事实

- 逐日行键集实测 = `trade_date,turnover,new_count,exit_count`；
- 缺失键实测 = **`prev_size,curr_size`**；全仓 `prev_size`/`curr_size`/`candidate_count`/`prev_n`/`curr_n` 命中 **0**；
- 因此 `A4b` 的恒等式检验只能在**满员**假设下成立：
  满员面板（`A4a`）`turnover × n = (new + exit)/2` **三对 gap 全 0.0**；
  同一恒等式在短员面板（`A4b`）上 `lhs = 7.0`、`rhs = 0.0`、**gap = 7.0** ⟹ 读者**无法**从载荷内部发现不一致。
- `A4c`（分母可反解）判 `NOT_VIOLATED`：`topN ∈ {null, 0, −5}` 被钳位到 10 并由 `top_n` 回显，
  反解偏差 ≤ **1.8e-15** ⟹ **分母是透明的**（这一点要如实承认，不得夸大）。

### 触发与范围

- 本条**不是**「项目藏着口径」，而是「缺少**自证字段**」：分母透明、**两日规模不透明**。

### 保守处理

- 不改任何生产代码、不改 DTO。

### 建议动作（均需用户确认）

1. **推荐**：新增 `prev_size` / `curr_size` 两个字段（**改契约，但为纯增量**，
   旧字段语义零变化）——它同时使 `D-R008-01` 的任一修法**可被外部验证**，
   并使 `A4b` 类恒等式检验在**短员日**也变得可做；
2. 或至少在 `note` 中披露短员日清单（信息量低、且仍不可机器读）；
3. 若维持现状，须在 API 文档声明「逐日行不含规模信息，读者无法自行判断短员」。

---

## §36 D-R008-07：无结构化口径字段、无单向/双向归一约定声明（R008 新增，**K7 组成项**）

### 事实

- 顶层键 10 个；结构化口径字段（`basis|definition|metric_scope|convention|one_way|round_trip|口径`）命中 **0**；
- 唯一的口径载体是 `note` **自由文本**（58 字段，声明的是**幸存者偏差**，不是换手率口径）；
- 被审计面（响应 + `backtest.ts` + 三个前端标签）对
  `one-way|two-way|单向|双向|round-trip|双边|归一约定` 的命中 = **0**
  （**同行情须含 `turnover|换手`**，即被测对象是**对换手的**单向/双向声明；
  原始命中数另行披露在报告附录 A-06）；
- 载荷的 `1 − 交集大小/n` 是**单向**替换率；判据来源式 (3) 的 `Σ|Δw|` 是**双向**，两者相差 **2 倍**。

### 触发与范围

- **不判违反**（判 `REGISTERED_ONLY`）：单向/双向是并存的行业惯例，
  判据来源**亦未给出**该约定 ⟹ 只有「未声明」这一事实成立，没有「声明错了」。
- 但有**真实后果**：任何把该值与**含成本的**净收益结论并列的读者，
  在单向/双向上会相差 2 倍（判据甲 Eq.(4) `c·TOt`）。

### 保守处理

- 不新增任何标注、不改任何口径、不改前端。

### 建议动作（均需用户确认）

1. 新增 `turnover_basis`（如 `ONE_WAY_REPLACEMENT_RATIO`）或至少在前端标签/tooltip 中声明；
2. **不得**据本轮断言「单向是错的」或「应当改用双向」（那属口径变更，须用户确认）；
3. 若后续要改口径，须**同时**评估它对成本项与既有研究结论的连锁影响。

---

## §37 D-R008-08：项目内两处「换手率」惯例结论**相反**（R008 新增，**K7 组成项**）

### 事实

| 位置 | 输出 | 理由文本 |
|---|---|---|
| `RollingExperimentService.java:473–474` | `"UNAVAILABLE"` | 「换手需组合执行口径（MP-020/021）才可评估，本研究不产生持仓序列」 |
| `BacktestTurnoverService.java:127` | **具体数值** | —（同样的「不产生持仓序列」前提下） |

⟹ **同一个项目内**，两处对「没有持仓序列时能不能算换手」给出了**相反**的结论，
而两者对外都叫「换手率」。

### 触发与范围

- 本条判 `CONVENTION_DIVERGENCE`（判据来源对「无持仓序列即无法评估换手」有明示：
  「**无持仓序列即无法评估换手**」是本轮判据乙，L 档）⟹ 属**惯例分歧**而非算术错误。
- 与 `D-R001-01`（`amount` 单位与 DDL 注释不一致）**不同源**：
  那是**单位/注释**错配，本条是**同项目内两处惯例相反**。
- 影响面：读者若同时消费两处，会得到「换手不可评估」与「换手 = 0.35」并存的结论。

### 保守处理

- 不改任何生产代码、不改前端、不改 `RollingExperimentService` 的 `UNAVAILABLE`。

### 建议动作（均需用户确认）

1. **最小成本**：人工定稿「两个换手率**不是同一个量**」的命名与文档口径
   （例如 `TURNOVER_REPLACEMENT_RATIO` vs `PORTFOLIO_TURNOVER`），并在两处互相指向；
2. 若确要统一，须先定**哪一个为权威**（**改契约/改口径**，须评审）；
3. **不得**直接把 `RollingExperimentService` 的 `UNAVAILABLE` 改成数值
   （那会掩盖「无持仓序列」这一事实）。

---

## §38 D-R008-09：同名 `avg_turnover` / 同标签「换手率」指代**两种量**（R008 新增，**K5 触发源**）

### 事实

**后端 4 个语义共存（全部在 `modules/` 生产面）**：

| 语义 | 位置 | 单位 |
|---|---|---|
| 市场换手率 | `DataService` SQL：`AVG(COALESCE(m.turnover_rate,0)) AS avg_turnover` | **百分数（%）** |
| 候选替换率 | `TurnoverResponse.avg_turnover` | **分数（0–1）** |
| 市值推算来源 | `CrossSectionNeutralizer.LOG_MV_FROM_TURNOVER` | 非换手率本身 |
| 不可用 | `RollingExperimentService` | `UNAVAILABLE` |

**前端同一中文标签分指两种量**：
`StockDetailView.vue:79`「换手率」= 个股 `turnover_rate`（%）；`ScoringBacktest.vue:484–486`
「平均换手」= 候选替换率 `avg_turnover × 100`（%）；`DataManagement.vue:293`「换手率」= 市场换手率。

### 触发与范围

- 判 `CONTRACT_AMBIGUOUS` ⟹ 触发 **K5**（与 `A6b` 合并，`K5 = A6a ∨ A6b`）；
- 与 `D-R008-01` **不同源**：`D-R008-01` 是「数错了」，
  本条是「**同一个名字**在两个地方指两个不同的量」；两者可独立修复。

### 保守处理

- 不改任何生产代码、不改前端、不改字段名。

### 建议动作（均需用户确认）

1. 统一命名（如 `avg_turnover_pct` / `avg_turnover_ratio`）——**改契约**；
2. 或标签补单位（`换手率(%)` / `平均换手(分数)`）——**改前端**；
3. 前端 `× 100` 换算点须一并纳入评审（它现在是**隐含**的单位换算）。

---

## §39 D-R008-10：`rankNo` 并列使载荷依赖**未钉住**的行序（R008 新增，**K7 组成项**）

### 事实

- `A8a`（确定性）判 `NOT_VIOLATED`：两个独立代理实例、各调一次，**全部键与值逐位相同**
  ⟹ 载荷本身**无随机性、无时钟依赖**。
- `A8b`（行序敏感性）判 `VIOLATED`：`rankNo` 并列第 10 的三只（`X,Y,Z`）
  按 `(X,Y,Z)` 与 `(Z,Y,X)` 两种返回顺序，
  `turnover` 从 **0.0** 变为 **0.1** ⟹ 载荷取决于数据库**未钉住**的行序；
- `A3c` 判 `NOT_REACHABLE`：代理按 `ORDER BY createdAt DESC` 返回，选中 `R_Y`；
  源码**零** `createdAt` 比较（「取最新」完全依赖查询方法名）⟹ **载荷不自证**；
  并列 `createdAt` 的取舍本机无 DB **不可构造**。

### 触发与范围

- **可达性未定**（报告 §6 已如实标注）：`rankNo` 并列在**真实插入路径**
  （`OversaveReboundScoringService` / `UnifiedScoringService` / `saveItemIfAbsent`）
  是否会产生，需 DB / 运行时证据 ⟹ 登记 **`B-R008-02`**；
- 唯一能彻底钉住它的手段是给 `rankNo` 加**唯一约束** ⟹ **改数据库结构**，属 §7 record-only。

### 保守处理

- 不改任何生产代码、不改 DDL、不加约束。

### 建议动作（均需用户确认）

1. **先**解除 `B-R008-02`（确认并列在真实插入路径中是否产生）再定论；
2. 若会产生：①加唯一约束（**改库结构**，须迁移评审）；
   ②或让载荷**显式排序**（按 `rankNo`，再按一个稳定键如代码）——**改行为**，成本最低且不动库；
3. 若不会产生：本条只登记为「未可达的确定性前提」，不改任何代码。

---

## §40 D-R009-01：`pearsonCorrelation` 的 NaN 守卫与 javadoc 声明不一致（R009 新增，**K1+K4 承重项**）

### 事实

- **实测（A2a/V03）**：用项目**自己既有测试的字面输入** `scores=[1..10]`、`returns=0.01·[1..10]`，
  生产 `pearsonCorrelation` 得 `ρ = 1.0`（对照复算方差 `82.5`）。
  把 `scores` 整体乘 `1e-10`（其余不变）⟹ `ρ = NaN`，而**对照方差 `8.25e-19 > 0`**、
  `spearmanRankCorrelation` **仍为 `1.0`（有限）**；把 `scores` 整体加 `1000`（平移）⟹ `ρ = 1.0` 不变。
- **实测（A2b/V04）**：固定**相对**离散度 `δ=1e-10` 时，可定义性随 `n` 翻转：
  `n=10` ⟹ `var=8.25e-19` ⟹ `NaN`；`n=11` ⟹ `1.10e-18` ⟹ `0.9999999999999999`；`n=12` ⟹ `1.43e-18` ⟹ `1.0000000000000002`。
- **实测（A2d/V06）**：15 格（`c ∈ 1e-10±20%` × `n ∈ {8..12}`）中，`ρ` 有限 **⟺** `var ≥ 1e-18`，**无一例外**。
- **源码**：`FactorResearchEngine.java:294-318` 的 `vx`/`vy` 是**未归一化平方和** `Σ(x−x̄)²`，
  守卫为 `if (vx < 1e-18 || vy < 1e-18) return Double.NaN;`（`:314`）。
- **文本（A5a/V13）**：`FactorResearchEngine.java:49-51` 的 javadoc 逐字写「样本不足**或方差为 0** 时返回 NaN」，
  且该 javadoc 与 `:66-68` 的 Spearman 说明**同句**（`C15 = 2`）；
  javadoc 中**量级/尺度/容差限定词命中 0** ⟹ 判定 `CONTRACT_MISLABEL`。

### 触发与范围

- 触发条件：某截面日分值（或收益）的**未归一化**平方和 `< 1e-18`，且 `n ≥ 2`、分值并非全等。
- **可达性（如实标注为不可达）**：生产 `rule_score` 是 0–100 量级的截面百分位，触发需约
  **12 位有效数字的相对精度**（`n=10` 时相对离散度约 `3.2e-10`）⟹ **当前生产尺度下暴露面为 0**。
- 覆盖范围：`pearson_ic_mean` 载荷族（研究面）；`spearmanRankCorrelation` **复用同一个 `pearson`**，
  但秩空间的平方和比守卫高约 **19 个数量级** ⟹ 同一面板上**秩有限而 Pearson NaN**（这就是 A6b）。

### 保守处理

- 不改任何生产代码、不改 javadoc、不改守卫常量、不改 `ResearchConvention`。

### 建议动作（均需用户确认）

1. **先定性**：这是「守卫**比**声明**更严**」（多返回 NaN）还是「声明**错标**」（文本用了错误的判据名）。
   两者修法不同：
   - 若认为守卫**故意**（防浮点噪声）：应**改 javadoc**，写明「未归一化平方和 `< 1e-18`」并给出量级限定；
   - 若认为声明**才是本意**：应**改守卫**为标准化量（如 `σx/|x̄| < tol` 或直接用 `σ` 的**相对**阈值），
     且**必须**同时检查 `spearmanRankCorrelation` 共用 `pearson` 带来的耦合。
2. **不得**只看 A2a 的单个构造就改守卫：`1e-18` 是**绝对**量，改成相对量会**改变**现有
   `pearson_ic_mean` 在极端面板上的取值 ⟹ 属「修改因子口径」（协议 §7 record-only）。
3. 建议的**最小**动作：先只**修文本**（动 javadoc，行为零变化），把行为变更留给单独轮次评估。

---

## §41 D-R009-02：`pearson_ic_mean` 把「不可定义」输出为 `0.0`，与「真 0 相关」**不可区分**（R009 新增，**K2 承重项**）

### 事实

- **实测（A4a/V10）**：两个**刻意同构**的面板（均 1 日、10 条观测、收益向量完全相同）：
  - `P4z`（全**真 0**）：`scores=[1..10]`、`returns=[1,2,3,4,5,5,4,3,2,1]` ⟹ `Σdev_x·dev_y = 0` **精确** ⟹ `ρ = 0.0`；
  - `P4u`（全**不可定义**）：`scores=1e-10·[1..10]`、**同一**收益向量 ⟹ `var = 8.25e-19` ⟹ **每一日** Pearson 皆 `NaN`。
- 两者经生产 `buildSnapshotScopeBlock`（反射调用）的**完整载荷**，经**递归规范序列化**后
  **逐字符相同**（均 **1060** 字节）；两者 `pearson_ic_mean` 均为 **`0.0`**；
  同时 `cross_section_days = 1 > 0`、`total_observations = 10 > 0`（⟹ 读者看不出「其实一天都没算成」）；
  `P4u` 的 Pearson 实际支撑天数 = **0**。
- **源码**：`FactorResearchEngine.java:176-177` 的 `pearsonIcMean = dailyPearson.isEmpty() ? 0 : average(...)`，
  配合 `:155` 的 `if (Double.isFinite(pic)) dailyPearson.add(pic)` ⟹ **空集与真 0 走同一条输出路径**。

### 触发与范围

- 触发条件：**全部**截面日的 Pearson 皆不可定义（`n < 2`、`var < 1e-18`、或分值全等）。
- **可达性：可达**（这是本轮六条可达缺陷之一）。消费方（前端、CSV 导出、任何下游脚本）
  无法从载荷区分「测到 0 相关」与「根本没测成」。
- 与 A3a/A3b 叠加放大：`daily_rank_ic` 两天都在、`cross_section_days = 2`，但其中一天 Pearson **从未算出**，
  且 `C11 = 0`（无任何支撑计数字段）⟹ 「不可定义」在载荷里**完全不可见**。

### 保守处理

- 不改任何发射键、不改 `0.0` 占位语义、不改前端 `factor.ts` 的 `pearson_ic_mean: number` 声明。

### 建议动作（均需用户确认）

1. **契约层**：把 `pearson_ic_mean` 从「必需 `number`」改为**可空**（`number | null`），
   或在 block 上增加 `pearson_support_days` 之类字段 ⟹ **改公开载荷契约**（协议 §7 record-only）。
2. **行为层**：`isEmpty() ? 0` 改为 `isEmpty() ? NaN / null` ⟹ 与 `round4(NaN) = 0.0` 的现状冲突
   （见 `D-R009-01` 与报告 §8 第 4 条），**必须先定 `round4` 的 NaN 语义**再动此处。
3. **最小**动作：只在 block 上加一个**不加歧义**的计数键（如 `pearson_support_days`），
   **不动**现有 `pearson_ic_mean` 的取值与类型 ⟹ 向后兼容，成本最低（但仍属改公开契约）。

---

## §42 D-R009-03：**R006 A4b 的 A 档等价被本轮新证据证伪**（R009 新增，**K7 组成项**；含 R006 报告勘误建议）

### 事实

- R006 A4b 把「`ic` 有限 **⟺** `pearson` 有限」记为 **A 档等价**。
- **本轮实测（A6b/V16）**：在**同一面板** `P1s` 上
  `isFinite(spearmanRankCorrelation(P1s)) = true`（值 `1.0`）而
  `isFinite(pearsonCorrelation(P1s)) = false`（`NaN`）⟹ **该等价为假**。
- 机制：`spearmanRankCorrelation` **复用同一个** `pearson`，但输入是**秩**；
  秩空间的 `Σ(rank−mean)² = n(n²−1)/12 = 82.5`（`n=10`），比守卫 `1e-18` 高约 **19 个数量级**
  ⟹ 秩侧**永不**触发守卫，而原始分值侧会（见 `D-R009-01`）。

### 触发与范围

- 只要存在一个截面日的分值平方和落在 `[数值下溢, 1e-18)`，两侧有限性即分离。
- **可达性：不可达**（与 `D-R009-01` 同源、同构造，需约 12 位有效数字的相对精度）。

### 处置条款（**预注册 §4 A6b 已冻结，已严格遵守**）

- **只**构成**本轮**的独立发现；
- **不得**回写、改写或重新解释 `rounds/R006_prereg.md` / `rounds/R006_report.md` 的任何冻结文本；
- **不得**据此叙述「R006 整轮失败」或「R006 结论不可用」；
- R006 A6c 若被牵连，同样只登记、不改写（本轮**未**发现牵连证据）。

### 建议动作（均需用户确认）

1. **R006 报告勘误建议**（**建议**在 `R006_report.md` 的**附录**加一条，格式照 R007/R008 的附录惯例）：
   > 附录 A-xx（R009 交叉复核）：A4b 的「`ic` 有限 ⟺ `pearson` 有限」在 R009 被实测证伪
   > （同一面板上秩侧有限而 Pearson 侧 `NaN`）；原因见 `rounds/R009_report.md` §2.1。
   > **本条不改变 R006 的任何其他判定**。
2. 若用户认为 R006 的 A4b **不是**等价主张而是**单向蕴含**（`pearson` 有限 ⟹ `ic` 有限），
   则该方向**未被**证伪 ⟹ 请裁定，**agent 不自行改判**。

---

## §43 D-R009-04：Pearson 支撑集在载荷中**不可见**（R009 新增，**K3+K7 承重项**）

### 事实

- **实测（A3a/V07）**：混合支撑集面板（`d1 = P1` 两日皆有 Pearson；`d2 = P1s` 仅秩侧可算）
  ⟹ `cross_section_days = 2`、`total_observations = 20`、`daily_rank_ic` 两天都在，
  而**实际 Pearson 支撑天数 = 1** ⟹ `support_gap = 1`。
- **实测（A3b/V08）**：`C11`（键名匹配 `pearson_(sample|count|n|observations|support|days|size)`）
  冻结期望 **0**、实测 **0** ⟹ **全仓不存在**任何 Pearson 支撑计数字段（`C7` 另证 `dailyPearson`
  **从不离开引擎**：无 getter、不进 `FactorResearchSummary`）。

### 触发与范围

- 触发条件：任一日的 Pearson 不可定义（`n < 2`、`var < 1e-18`、或分值全等）。
- **可达性：可达**（不依赖极端精度，`n < 2` 或分值全等即触发）。

### 保守处理

- 不新增任何字段、不改 `FactorResearchSummary` 的记录定义、不改载荷键集。

### 建议动作（均需用户确认）

1. 在 block 上增加 `pearson_support_days`（与 `cross_section_days` 并列）
   或让 `pearson_ic_mean` 变为可空 ⟹ **改公开载荷契约**（协议 §7 record-only）；
2. 加字段与 `D-R009-02` 的最小动作**可合并为一次契约变更**，建议合并评估；
3. 无论是否加字段，**都建议**先用**只读**方式记录现状（本轮已做），不改动任何发射点。

---

## §44 D-R009-05：legacy 空分支缺 `pearson_ic_mean` 等 5 键，与前端**必需**声明冲突（R009 新增，**K5 承重项**）

### 事实

- **源码（`FactorResearchService.java:660-672`）**：`if (latestPerKey.isEmpty())` 分支只发射 **10** 个键
  （`cross_section_days`/`total_observations`/`rank_ic_mean`/`rank_ic_std`/`rank_ic_ir`/
  `rank_ic_positive_rate`/`long_short_spread`/`daily_rank_ic`/`grouped_returns`/`cost_net_ic`），
  **缺** `pearson_ic_mean`、`monotonicity`、`hac_std`、`ci_lower`、`ci_upper`；
  正常分支（`:655-797`）发射 **19** 键且**含**上述全部。
- **前端（`modules/web/src/api/factor.ts:110` 与 `:201`）**：`pearson_ic_mean: number` ⟹ **必需**（无 `?`）。
- **可达性**：`latestPerKey` 来自 `findByTradeDateBetweenOrderByCreatedAtDesc(...)` 的**区间查询** ⟹
  区间内无 `selector_result` 时为空，**该分支可达**。

### 触发与范围

- 触发条件：查询区间内该策略/键无任何 `selector_result`（例如区间过窄、策略未跑、数据被清）。
- 后果：前端按 `number` 读取一个**不存在**的键 ⟹ 类型契约层面的未定义读取。

### 保守处理

- 不改任何发射键、不改前端声明、不改查询逻辑（**本轮未做运行时验证**，报告 §8 第 7 条已明示）。

### 建议动作（均需用户确认）

1. **前端侧**（成本最低、不动后端契约）：把 `pearson_ic_mean` / `monotonicity` / `hac_std` /
   `ci_lower` / `ci_upper` 改为**可选**（`?`）并在消费处加缺测分支 ⟹ 改前端契约；
2. **后端侧**：把空分支补齐为与正常分支**同键集**（值取 `0.0` / `"N/A"`）⟹ 改公开 API 载荷；
3. 两个方向都可，但**不要**同时改（会掩盖真正的责任面），建议**先前端**。

---

## §45 D-R009-06：两套「口径不可用」惯例并存（R009 新增，**K7 组成项**）

### 事实（反射实测的真实键集与标记方式）

| 分支 | 键数 | `status` | `monotonicity` | `note` | 表达不可用的方式 |
|---|---|---|---|---|---|
| `buildSnapshotScopeBlock`（正常） | 18 | **无** | 有 | 无 | 不适用 |
| 同上（`totalObservations == 0`） | **19** | **无** | 有 | **有** | 靠 `note` + 全 0 |
| `unavailableScopeBlock` | 18 | **有**（`"UNAVAILABLE"`） | **有**（`"N/A"`） | 有 | 靠**显式** `status` |
| `emptyNetIcBlock` | **14** | **无** | **无** | 有 | 靠**缺键**（缺 `daily_rank_ic`/`grouped_returns`/`monotonicity`） |
| `buildNetIcBlock`（正常） | **15** | **无** | **有** | 有 | 不适用 |
| legacy 空分支（`:660-672`） | **10** | **无** | **无** | 无 | 靠**缺键** |

- **实测（A4c/V12）**：同一「该口径不可用」语义，`unavailableScopeBlock` 用**显式键**
  `status="UNAVAILABLE"`，而 `emptyNetIcBlock` **靠缺键**（且连 `monotonicity` 都没有）
  ⟹ 判定 `CONVENTION_DIVERGENCE`（两处惯例**各自非错**，但读者须猜）。

### 触发与范围

- 触发条件：任何一次「该口径不可用」的降级路径。
- **可达性：可达**（反射实测的真实键集，非读码推断）。

### 保守处理

- 不统一键集、不改任何 block builder、不改前端。

### 建议动作（均需用户确认）

1. 统一为**显式** `status` 键（成本最低、读者最省力）：给 `emptyNetIcBlock` 与 legacy 空分支加 `status`；
2. 或统一为**缺键**（需前端全面加可选 `?` ⟹ 与 `D-R009-05` 合并处理）；
3. 建议**先定一个方向**（推荐显式 `status`），再连同 `D-R009-02`/`D-R009-04` 一起做**一次**契约变更。

---

## §46 D-R009-07：CSV 导出把占位 `0.0` 与真测量值写成**同一列**（R009 新增，**K7 组成项**）

### 事实

- **实测（A7b/V18）**：`FactorResearch.vue:499-517` 的 `exportUnifiedCsv()` 表头含 `pearson_ic`，
  其取值表达式为**裸** `row.pearson_ic_mean`（**无** `??`、**无** `||`、**无** `isFinite`）。
- 方法体内另有 2 处 `??`/`||`，但分别属于 `cost_net_ic` 列（`?? ''`）与前置守卫子句，
  **不属** `pearson_ic` 列（本轮的正则初版误把它们算作该列的分支，见 `R009_report.md` 附录 A-07）。
- 与 `D-R009-02` 叠加：占位 `0.0` 与真 0 在载荷里已不可区分，导出面**原样**写出 ⟹ 离仓文件同样不可区分。

### 触发与范围

- 触发条件：任何一次导出（与 `D-R009-02` 的触发条件同源）。
- **可达性：可达**。

### 保守处理

- 不改导出逻辑、不改表头。

### 建议动作（均需用户确认）

1. 与 `D-R009-02`/`D-R009-04` **合并**处理：一旦载荷能表达「不可定义」，
   导出面加一个分支（写空串或 `N/A`）即可；
2. 若暂不改载荷：可在导出面按「支撑天数为 0 则写空」处理 ⟹ 但**需要**载荷先有支撑天数字段（见 `D-R009-04`）；
3. **不建议**在导出面**单独**修，否则会出现「页面上是 `0.0`、导出是空」的新不一致。

---

## §47 D-R009-08：既有测试缺**尺度维度**覆盖（R009 新增，非杀死轴 A7a）

### 事实

- **实测（A7a/V17）**：`FactorResearchEngineTest.java` 的测试**输入表达式**共 **20** 处，
  其中含 `1e-` 量级 `scores`/`returns` 的为 **0** 处；
  文件内 4 处 `1e-` 命中**全部**是**断言容差**（`offset(1e-9)` ×3、`isCloseTo(1.0, offset(1e-6))` ×1）。
- 现有两个 Pearson 用例只覆盖**尺度 1** 的一侧：
  `pearsonCorrelationReturnsOneForPerfectLinear`（`:85-89`，`[1..5]` vs `0.01·[1..5]` ⟹ `1.0`）
  与 `pearsonCorrelationReturnsNaNForInsufficientSamples`（`:92-95`，`n=1` 与 `null`）。

### 触发与范围

- 这是**测试覆盖度**问题，不是载荷正确性问题 ⟹ 本轮**非杀死轴**，判定 `REGISTERED_ONLY`。
- 后果：`D-R009-01` 的尺度不变性缺陷**不会**被既有测试发现（`350/350` 全绿）。

### 保守处理

- 本轮**未**新增任何测试（测试代码虽在允许档，但按 §7 先登记等用户排序；
  且新增会失败的红测试属「改行为前置」，需用户确认）。

### 建议动作（均需用户确认）

1. 加一个**尺度**用例（`scores = 1e-10·[1..10]` 断言 `ρ == 1.0`）⟹ 该测试**当前会失败**，
   故必须与 `D-R009-01` 的**修法一并**决定（要么先改守卫、要么先写成 `@Disabled` 并注明原因）；
2. 建议顺序：**先**决定 `D-R009-01` 的方向，**再**落测试，避免留下长期红灯。

---

## §48 D-R010-01：「声明—实现」成本口径分歧的**处置方式**（R010 新增，**K1+K2+K3+K4 承重项**）

### 事实

- **声明侧（4 处对外材料）统一写作加法式**：
  - `ResearchConvention.java:83-84` javadoc：`g = 1 + commission + slippage`、`y_h = 1−sell_tax−slippage`；
  - `ResearchConvention.labelDefinition()`（`:95-97`）：`min_net_return` 的说明文字；
  - `docs/研究口径规范.md:38-39` 公式：`1 − sell_tax − slippage`；
  - `docs/API文档.md:377`：「`1−印花税−滑点`」（加法式子句命中 `=1`）。
- **实现侧（4 个站点）统一写作两级乘性**：
  `FactorResearchService:360-366` / `:727-733`、`LabelFactoryService:337-341` / `:439-443`，
  均为 `(1 − s) · (1 − c − t)`；本轮的文本测量证实 8/8 个 `*_mult` 计数 = **2**、
  4/4 个 `*_exit_commission_step` = **1**（卖出因子**含佣金**）。
- **差异量级（P 档自建算术，非策略收益）**：
  `f_impl − f_doc = −0.00029984`，主项恰为 `−c`（= **−3.00 bp of E**），归属份额 `1.00053`；
  合成后 `|Δbp| = 3.297250385368094929400128909169…`，`−TH_BP = 2.297250…` > 0 ⟹
  按 prereg §3 冻结判据判 `CONTRACT_MISLABEL`（**错标**），**不是**舍入误差。
- **归属声明**（`ResearchConvention.java:88`）实测**不成立**：javadoc 的卖出因子逐字为
  `(1 - sell_tax - slippage)`（**不含佣金**），而它名下宣称的 4 个站点 **4/4 含佣金**。
- **`labelDefinition()` 不描述其伴随数值**：发射点 **3** 个
  （`FactorResearchService:220`、`LabelFactoryService:707`、`ProbabilityCalibrationService:270`），
  其中 **2** 个文件同处含乘性实现，第 3 个（`ProbabilityCalibrationService`）的样本
  经 `LabelFactoryService` 产出（`pcs_samples_from_lfs = 1`）。

### 触发与范围

- **可达性：可达**（这是**文本与代码的静态分歧**，不需要极端输入、不需要特定数据）。
- 影响面：**任何**消费 `convention` / `executionAssumptions` / `min_net_return` /
  `labelDefinition()` / 前端「双边佣金」提示的读者或下游脚本，
  以及任何按 docs 公式手工复算净收益的人（会得到**偏乐观**约 3.00 bp of E 的数）。
- **不得**外推：`Δbp = 3.29725` **不得**被叙述成「策略真实收益比报告低 3.3bp」——
  本轮**没有测量任何策略的收益**（报告 §10）。

### 保守处理

- 不改任何生产代码、不改 `ResearchConvention`、不改两份 docs、不改前端 `.vue`、不改任何测试。
- 全部判定词由**实测**（生产字节码反射 / 源码文本计数 / `Decimal prec=60` 复算）决定，
  **不**依赖探针自报布尔值（报告 §1）。

### 建议动作（均需用户确认）

1. **方案 ①（成本最低、行为零变化）**：只**改文本** —— 把 javadoc 公式、`labelDefinition()` 字符串、
   `docs/研究口径规范.md` 与 `docs/API文档.md` 的口径表统一改写为**两级乘性**形式，
   并删掉 `ResearchConvention.java:88` 那条**不成立**的归属声明（或把它改成「与实现一致」的措辞）。
   ⟹ 不动任何数值、不动任何历史结论、不需迁移。
2. **方案 ②（改实现对齐文本）**：把 4 个站点改成加法式 ⟹ 属**修改冻结成本口径**，
   须迁移评审 + 重算全部历史净收益 + 与 `D-R003-01`/`D-R004-01` **合并评审**（同一个 `long_short_spread` 族）。
   **不建议**：加法式与乘性在 `s` 量级上系统性偏差（本轮的 `−3.00 bp` 主项来自**遗漏卖出佣金**，
   这本身是实现更正确的一侧）。
3. **方案 ③（维持现状）**：则须在 docs 显式声明「两套记法并存」及其差异量级与方向（偏乐观/偏保守），
   并把 `ResearchConvention.java:88` 的归属声明删掉（因为它**可被实测证伪**）。
4. **顺序建议**：`D-R010-01` **先于** `D-R010-07`（测试守卫）与 `D-R010-05`（历史仪器重算）——
   因为后两者的方向都取决于本条选①还是②。

---

## §49 D-R010-02：加法式记法文本与乘性代码**同处**，且其中一处**进了载荷**（R010 新增，附加观测 X01）

### 事实

| 站点 | 计数 | 是否进载荷 |
|---|---|---|
| 入场写法 `×(1+佣金+滑点)` | **5**（`FactorResearchService` 4 + `LabelSnapshot` 1） | — |
| 出场写法 `×(1-佣金-印花税-滑点)` | **4**（`FactorResearchService`） | — |
| `FactorResearchService:80` 的 `RANK_IC_NET_BASIS_NOTE` | 1 | **是**：该常量经 `:220` 发射进载荷 `basis_note` |

- **关键点**：这不只是「注释写得不一样」——`basis_note` 是**消费方会读到**的字段，
  而它的人类可读说明文字用的是加法式，同文件内的**代码**是乘性（`×5` 命中）。
- 与 §48 的关系：§48 是**对外契约材料**（javadoc/docs/前端）的口径分歧；
  本条是**载荷内部**的文本与代码分歧 ⟹ `K1/K3` 的**同一家族**，但**作用面不同**。

### 触发与范围

- **可达性：可达**（任何一次返回 `basis_note` 的调用）。
- **量级**：由 `D-R010-01` 的算术给出同一量级的偏乐观（≈ 3.00 bp of E），**不另立新算术**。

### 保守处理

- 不改 `RANK_IC_NET_BASIS_NOTE` 的任何字、不改发射点、不改前端对 `basis_note` 的渲染。

### 建议动作（均需用户确认）

1. 把 `basis_note` 的**公式记法**改写为与实现同构的两级乘性式 ⟹ **改公开载荷文本**（须评审，
   但**不改任何键名、不改任何数值、不改任何类型**，是四类改动里最小的一种）；
2. 或**保留记法、加一句声明**：「本节记法为加性近似，实现为两级乘性，差异见 R010 报告 §2」；
3. 与 `D-R010-01`/`D-R010-03` **合并评审**（同一「声明—实现」家族，一次改齐可避免第三次不一致）。

---

## §50 D-R010-03：`FactorResearchService:352` 的**第三种**加法记法，且符号自相矛盾（R010 新增，附加观测 X02）

### 事实

- 该行注释逐字：`// COST_APPROX：rawOpen[t+1]×(1+c+s) 入场 → rawClose[t+1+h]×(1-s-tax-s) 退出`。
- **它既不是** docs 的 `1−印花税−滑点`，**也不是** javadoc 的 `(1 - sell_tax - slippage)` ⟹
  全仓至少**三套互不相同**的加法式记法。
- **出场式把同一个符号 `s` 用了两次**：一处指**滑点**、一处本应是**卖出印花税** `t`。
  若按该行字面复算 `1 − 0.0002 − 0.0002 = 0.9996`，而代码实际是 `1 − c − t` 后
  再经卖出侧乘性 = `0.99900016`（本轮冻结值）⟹ **字面真值 `0.9996` vs 代码 `0.99900016`**。
- 该注释是**站点上方的口径说明**：本次行号校准把它作为 `frs_site_1` 的锚点，实测算得
  `:352`，而站点块起点在 `:360`（delta `−8`，见报告附录 A.1）。

### 触发与范围

- **可达性**：注释本身**进不了载荷**（它是 `//` 行），故**面向消费者的可达性 = 0**；
  可达的是**读源码/读文档的人**（工程师、评审者），以及任何按该注释手工复算的人。
- ⟹ 本条**不参与** `K1/K2/K3` 的合成（报告 §9 明标「只记录，不参与任何判定」）。

### 保守处理

- 不改该行注释、不改站点代码、不改任何文档。
- **未**把「同一符号两用」叙述成「实现用了错的费率」——实现侧是 `(1−s)(1−c−t)`，
  这里的矛盾**只在注释里**。

### 建议动作（均需用户确认）

1. **最小**动作：改这一行注释，使符号唯一（如 `×(1−s)(1−c−t)`），**不动任何代码与契约**；
2. 或删除该注释（它是站点上方的可读性说明，删掉会削弱该站点的自我说明能力，**不推荐**）；
3. 与 `D-R010-01`/`D-R010-02` **合并评审**：三处一起改，才能让全仓只剩**一套**记法。

---

## §51 D-R010-04：`ScoringBacktestService` 的入场费站点有**两份算术相同的副本**（R010 新增，附加观测 X03，**本轮首次发现**）

### 事实

| 站点 | 位置 | 状态 | 乘性证据 |
|---|---|---|---|
| 内联站点 | `ScoringBacktestService:275-276`（`perTradeDate` 主回测循环内） | **活跃路径**：紧接着 `:282-284` 调 `computePeriodReturn` | `sbs_inline_entry_multiplicative = 1` |
| 助手站点 | `ScoringBacktestService:969-970`（抽出的 `resolveEntryContext`） | 由 `:337` 调用 | 同式 `double entryCost = entryPrice * (1 + commissionRate);` |

- 两处**逐字相同**（同一行 `double entryCost = entryPrice * (1 + commissionRate);` 在文件内出现 **2** 次），
  算术也相同 ⟹ **不是**缺陷，是**同构副本**。
- **声明侧未提及**存在两份副本；`ResearchConvention.java:88` 的归属声明点名的是**助手**
  （V01 的措辞同样点名 `resolveEntryContext`）⟹ **判据不变**。
- 实现层影响（已披露）：行号校准的文本锚点会**恒命中 `:276`**，
  故 `LINE_ANCHORS` 新增「起始行提示」第三元素以消解歧义，并把**两个站点都**纳入校准表
  （报告附录 A.1 的 `sbs_inline_entry` / `sbs_impl_entry`，delta 均为 `+1`）。
- 本轮另测得：**四文件乘性净收益合成站点总数 = 11**（入场 6 + 出场 5；
  `FactorResearchService` 4 / `LabelFactoryService` 4 / `ScoringBacktestService` 3）。

### 触发与范围

- **可达性**：两份副本都可达（内联站点在**活跃回测路径**上）。
- 风险面：**未来只改一份**就会产生真正的口径分歧（当前是一致的）⟹ 这是**维护性风险**，
  **不是**当前缺陷。

### 保守处理

- 不改任何一份副本、不合并代码、不改注释。
- **未**把两份副本叙述成缺陷（报告 §9 明标「只记录」）。

### 建议动作（均需用户确认）

1. 人工确认两份副本是**有意保留**（如内联版为避免热路径上的方法调用开销）还是历史遗留；
2. 若为遗留：删一份属**改生产逻辑**（须评审），且**必须**同时确认两份的输入来源一致
   （内联版与助手版的 `commissionRate` 是否取自同一处）；
3. 若为有意：在 `ResearchConvention.java:88` 的归属声明或 docs 中注明「入场费公式有两份副本，算术相同」，
   以免后续维护者只改一份。

---

## §52 D-R010-05：项目**自己的两个研究仪器**走加法式文档口径 ⟹ 历史净收益**偏乐观**（R010 新增，`V18 = REGISTERED_ONLY`）

### 事实

| 仪器 | 加法式出场因子 | 冻结口径引用数 |
|---|---|---|
| `experiments/R001_reversal/run_reversal_ic.py` | **含 1 处** | `FROZEN_TOTAL = 0` |
| `experiments/R003_cost_breakeven/run_cost_breakeven.py` | 0 处 | `FROZEN_TOTAL = 5`、加法式 `= 0` |

- ⟹ 两者**都**按**文档的加法式口径**计算净收益（而非生产代码的乘性口径），
  故其净收益相对生产口径**偏乐观**；量级与 `D-R010-01` 同源（主项为**遗漏卖出佣金** `−c`）。
- `V18` 的判定词是 `REGISTERED_ONLY`（**兜底地板值**，`K7` 按设计触发），
  **不得**被叙述成「又发现一条缺陷」（报告 §6 第 1 条强制披露）。

### 触发与范围

- **可达性：可达**（已发生：R001/R003 的净收益数字**已经**按该口径算出并写进各自报告）。
- 影响面：R001（反转假设 IS/OOS 净收益）与 R003（成本 break-even）的**数值**，
  **不影响**它们的**判定词**（R001 = `INCONCLUSIVE`、R003 = `INCONCLUSIVE`，
  两者都不是靠净收益卡边界得出的）。

### 保守处理

- **不重算**、**不覆盖** R001/R003 的任何历史产物；
- **未**回写 `R001_prereg.md` / `R003_prereg.md`（报告 §11 第 3 条）。

### 建议动作（均需用户确认）

1. 人工裁定是否用**乘性口径**重算 R001/R003 的净收益数字 ⟹
   **会改变既有报告的数值**（但**不改其判定词**），须按「历史报告勘误」流程处理；
2. 或只加一条勘误说明**方向与量级**（偏乐观，主项 `−c` = 3.00 bp of E 量级），不重算；
3. **不得**由 agent 自行重算并覆盖历史产物（属「删除/改写历史数据」的相邻动作，协议 §7）。

---

## §53 D-R010-06：**计算路径取钳位值、持久化路径取原始值**，且载荷无钳位标记（R010 新增，`V15 = VIOLATED`）

### 事实

- **计算路径**取 `boundedRate(...)` 的返回值：区间 `[0, 0.05]`，非有限输入 ⟹ 默认值。
  实测 7 格全对（`V14`）：`null/NaN/+Inf → 0.0003`、`−1.0 → 0.0`、`0.06 → 0.05`、
  `0.0001 → 0.0001`、`0.05 → 0.05`。
- **持久化路径**（`:507-509`）取**原始请求值**：该区间内 `boundedRate` 命中 **0** 次
  （`sbs_persist_has_bounded = 0`），而默认值区间（`:80-82`）命中 **3** 次。
- **JDK 行为推导**：`BigDecimal.valueOf(0.06) == 0.06 ≠ 0.05` ⟹ 两路径**必然**取到**不同**值；
  `BigDecimal.valueOf(NaN)` 抛 `NumberFormatException`，被 `:520-524` 的 catch 吞掉并
  `response.setResultId(null)`（`=1`）。
- **载荷侧**：`executionAssumptions` 写的是**钳位后**的 `commission_rate = 0.05`，
  而**无** `clamped` 之类标记（`assumptions_has_clamp_marker = 0`）。
- **端到端边界**：`persistRun` 需要 Spring 容器与 DB，本机**无法观测** ⟹ `B-R010-01`；
  本条由「1 次反射实测 + 1 次源码文本 + 1 次 JDK 行为推导」得出（报告 §3 `V15` 的
  `honest_boundary` 字段已逐字照写）。

### 触发与范围

- **可达性：可达**（只要调用方传入 `> 0.05`、`< 0` 或非有限值即触发；默认值为 `0.0003`，
  而前端/API 未对入参加区间约束）。
- 后果方向：**计算**用钳位值、**入库**用原始值 ⟹ 事后按入库参数复算会**对不上**载荷里的净收益；
  且读者看到 `commission_rate = 0.05` 时**无从知道**输入其实是 `0.06`。

### 保守处理

- 不改任何生产代码、不改 DDL、不改载荷键、**未**启动 Spring 容器、**未**连数据库。
- `V15` 判 `VIOLATED` 的**五条腿**中只有第 ② 条依赖源码文本，其余四条由反射/JDK 行为独立支撑
  （见 `blocked_queue.md` §19）。

### 建议动作（均需用户确认）

1. **最小**动作：在 `executionAssumptions` 中加**纯增量**字段
   （如 `commission_rate_input` + `commission_rate_clamped: true/false`），**不动**旧字段语义与取值；
2. 或统一两路取**同一**值（计算与持久化都用钳位后值，或都用原始值）⟹ **改行为**，须评审，
   且会改变已入库行的语义；
3. 与 `D-R010-01` **合并评审**：同属「读者看到的与实际的不是一回事」家族；
4. 端到端复核依赖 `B-R010-01` 的解除（需运行环境），**不得**用推断冒充端到端证据。

---

## §54 D-R010-07：`labelDefinition()` 的净收益公式算术在**测试层零守卫**（R010 新增，`V13 = REGISTERED_ONLY`，非杀死轴 A6）

### 事实

- 常量被钉住 **3** 条（`ResearchConventionTest:20-25` 断言 `DEFAULT_COMMISSION_RATE` /
  `DEFAULT_SELL_TAX_RATE` / `DEFAULT_SLIPPAGE_RATE` 三个字面量）。
- **唯一**触碰 `labelDefinition()` 的测试体 **1** 个（`:54-57`），
  且其中**算术断言 = 0**（只做**子串**断言：`exit_price_h` / `min_net_return` / `h in {2,3}`）。
- **全测试树**内公式算术字面量 `C16 = 0` ⟹ 公式里的算术**没有任何**测试守卫。
- 后果（与 §48 直接耦合）：把 javadoc / `labelDefinition()` 的公式改成与实现同构的乘性式，
  **不会**被任何既有测试发现（Java 侧 `350/350` 全绿）；反之，
  若实现被改坏成与文本一致，**同样**不会被发现。

### 触发与范围

- 这是**测试覆盖度**问题，**不是**载荷正确性问题 ⟹ 本轮**非杀死轴**，判定 `REGISTERED_ONLY`。
- `V13` 是 `K7` 的三个组成项之一 ⟹ `K7` 是**地板值**，**不得**叙述成「又发现一条缺陷」。

### 保守处理

- 本轮**未**新增任何测试（先登记等用户排序）；
- **未**给 `ResearchConventionTest` 加算术守卫（报告 §11 第 2 条：那同样属协议 §7 保留权限）。

### 建议动作（均需用户确认）

1. 加一个**算术**用例：按 `labelDefinition()` 的公式与三个常量**独立复算** `min_net_return`，
   断言与实现一致 ⟹ 该测试在方案 ①（只改文本）**之后**才会绿；若先落测试会留长期红灯；
2. 建议顺序：**先**定 `D-R010-01` 的方向（改文本 / 改实现），**再**落测试，
   使测试断言的方向与最终口径一致（与 `D-R009-08` 的处置顺序**同型**）；
3. 若决定**不**改任何东西（方案 ③），则本测试**不应**写死「乘性」或「加法」，
   而应断言「`labelDefinition()` 的文本与其伴随数值**描述同一件事**」——
   这会**当前失败**，故仍须与 `D-R010-01` 一并决定。

---

## §55 D-R011-01：承重分析分叉在载荷中**不可恢复**、且**无备选集合**（R011 新增，`V03`/`V04`/`V09`/`V10`/`V18` 合并）

### 事实（全部机器抽取，可复现）

1. **`min_cross_section_size` 全族零发射**（`V04` ⟹ `K1`）：5 个生产 `summarize` 调用点
   （`FRS:529/747/791`、`SBS:431/443`）**无一**把该值写进载荷；键名字面量命中 0、类键名命中 0；
   两处常量（`FRS:71 = 10`、`SBS:58 = 10`）只在**同文件内**被引用（跨文件引用 0 处）。
   **后果**：消费方从任何已发布载荷都**无法知道**某份研究量是在 min-size = 10 下算出来的。
2. **`group_count` 在 `F-SNAPSHOT` 族缺键**（`V03` ⟹ `K4`）：`buildSnapshotScopeBlock`（`FRS:526–554`）
   内**有** `holding_days`、**没有** `group_count`；而 `F-UNIFIED` 在同一 Service、同一常量下发 `:650`。
   **后果**：同一个 Service 的两族报告里，只有一个能告诉消费方 J 是多少。
   （**注**：`F-SCORING`/持久化面**可恢复**——`ScoringBacktestResponse.FactorAnalysis:81` 有 `groupCount`
   且 `SBS:1024` 已接线；这一点**证伪**了本轮预注册的 P-9/V11，见报告附录 A-1。）
3. **两个定义来源**（`V09` ⟹ `K3`）：`FRS:70` 是编译期常量（**无入参**），
   `SBS:78` 是请求参数（`req.getFactorGroupCount() != null ? … : 5`，前端 `:min=2 :max=10`）。
   **后果**：同一分叉的取值由两条互不相同的因果链决定，且**都不**在缺键的那一族里可见。
4. **声明不共享**（`V10`，值层 vs 结构层**分开**）：值层一致（`10 = 10`、`min_size_values_equal = true`）；
   结构层无共享声明（`min_size_shared_declaration_sites = 0`，两个私有常量各自独立）。
5. **文档零规定**（`V18` ⟹ `K5`）：`docs/API文档.md`、`docs/研究口径规范.md`、`contracts/*.json`
   （扫描 4 个文件）对 J 与 min-size 的取值与备选**无任何**规定。

### 为什么需要用户决策（而不是我直接修）

补一个键 = **改公开载荷面**（`/api/factor/*`、`ScoringBacktestResponse`、前端类型），
属协议 §7 的「修改公开 API/前端契约」层级；抽共享常量 = 改生产代码结构；
把备选集合写进文档 = 改研究口径。**均为只记录、不得自动执行**。

### 建议（含顺序）

1. **先定范围**：是否承认「发布出去的研究量必须能自证其分叉取值」这条原则（= 是否接受 H0 的实质）。
2. 若接受：**一次**评审补键方案（三个族 + snapshot 块），并同步前端类型与契约测试。
3. 若不接受：**至少**在 `docs/研究口径规范.md` 里写明「本项目的 J 与 min-size 为固定值，
   载荷不发射它们是**已知约定**」——使「缺席」变成**声明过的缺席**，而不是静默缺席。
4. `V09`/`V10` 的两个来源与两份常量**应与第 2/3 步一并决定**（同族问题，避免反复改）。

---

## §56 D-R011-02：判据词表的**子串假阳性**（R011 新增，`V17` 的期望被证伪）

### 事实

冻结词表（`R011_prereg.md` §4 A7）为 `敏感`/`敏感度`/`robust`/`multiverse`/`备选`/`alternative`/`分叉`。
机械检索命中 **2 处**，逐条查看后确认**两处都是子串，不是声明**：

- `ScoringBacktestService:654`：`v.put("neutralization_pipeline", "winsorize→OLS(lnMV+industry)→robustZ(residual,MAD)")`
- `ScoringBacktestService:699`：`assumptions.put("codex_neutralization", "v2: winsorize → OLS(lnMV+industry dummies) → robust Z on residuals")`

两处命中的是**方法名/流程名里的 `robust`**（`robustZ`、`robust Z on residuals`），
说的是**中性化流程**，与「分叉敏感性声明」无关。
补充测量（词边界收紧，**只作判别、不改判定词**）命中 = **0**。

### 影响与边界

`V17` 属**非杀死轴 A7**，`NOT_VIOLATED` 与 `REGISTERED_ONLY` 都**不参与任何杀点** ⟹ 总判定不受影响。
但**测量学教训是真实的**：若不逐条查看，本轮会把「方法名里的 robust」当成「项目做了敏感性声明」。

### 建议

① 是否把词表改为**词边界匹配**（属改已冻结口径，须用户确认）；
② 若维持子串匹配，则须在每轮报告中**强制逐条列命中并人工判别**（本轮已如此做）；
③ 把「文本检索必须先做子串假阳性检查」写入 `preregistration_template.md`。

---

## §57 D-R011-03：**看到结果之后**的仪器修改改变了总判定（R011 新增，**2026-09-15 已裁定**）

### 事实（不得省略任何一项）

- **运行 1–3**（`run.log`）：`K8 = true`（仪器门）⟹ 按冻结短路**必须** `INCONCLUSIVE`。
- **修复内容**：`CLN` 语义（D9）、`INJ` 注入值方向（D10）、`V20` 自指拆分（D11），
  另含 `V03` 判据方向（D6）与两处测量方法（D7/D8）、禁数自匹配（D12）。
- **运行 4–5**：`K8 = false` ⟹ 总判定 `REJECTED`，`h0_alive = false`。
- **关键反放水事实**：杀点 `K1–K5` 在**运行 2**（修复之前）就**已全部为真**；
  修复**未**触碰任何判定词、任何 `judge`、任何一条杀死条件；
  `R011_prereg.md` 自 `a8681459` 起**零字节变更**。
- **同时必须承认的风险**：这是在**看到结果之后**改仪器。
  若认为 `CLN` 应保持「谓词必须为真」的更强语义，则本轮**应回到 `INCONCLUSIVE`**。

### 为什么当时必须修

原 `CLN` 语义会把**「假设被证伪」误报成「仪器失效」**：
`V03`/`V11`/`V17`/`V20` 的谓词在实测值上本就为假（这正是负结论），
要求「谓词必须为真」等于**系统性**把诚实的负结论转成 `INCONCLUSIVE`。
这是**仪器可靠性**问题，不是数据取舍问题。

### 请用户裁决

①**认可**该语义（维持 `REJECTED`）；②**要求更强语义**（本轮回到 `INCONCLUSIVE`，H0 未判定）；
③把 `CLN`/`INJ` 的判合格标准写进模板，避免下轮重犯。
**无论选哪一项，本轮的实测事实（§55）都不改变**——它们由独立于金丝雀的机械抽取与运行时复算支撑。

### 裁定（2026-09-15，**已执行**）

用户明示：**「允许回到 `INCONCLUSIVE`」** ⟹ 采纳**选项②**（`CLN` 取**强语义「谓词必须为真」**），
并**同时采纳选项③**（把 `CLN`/`INJ` 合格标准写进预注册模板）。

**裁定推得的判定链（是重新判定，不是重新测量）**：

| 步 | 内容 |
|---|---|
| 1 | 冻结短路**逐字未改**：`K7\|K8\|K9\|K10` 任一为真 ⟹ **强制** `INCONCLUSIVE`（本轮断言全部作废） |
| 2 | `K8` = 仪器门；它在运行 1–3 为**真**、运行 4–5 为**假** |
| 3 | 运行 4–5 之为假，靠的是 **D9 对 `CLN` 语义的事后修改** |
| 4 | 强语义下该修改**不被认可为合法仪器修复** ⟹ `K8` 须以**未修复**状态读作**真** |
| 5 | ⟹ 总判定**必须**为 `INCONCLUSIVE`，`h0_alive` **未判定** |

**执行结果（已落地）**：

| 项 | 变更 |
|---|---|
| R011 总判定 | `~~REJECTED~~` ⟹ **`INCONCLUSIVE`**（`h0_alive` `false` → **未判定**） |
| **实测层面（不变）** | `K1–K5` **全真**、`V01–V20` 全部判定、金丝雀 20/20、`out_of_vocab = []`、`run.log` 全部 **5** 次运行、五处反例（`V03`/`V04`/`V07`/`V08`/`V09`/`V18`）**一条未变** |
| **零字节变更** | `R011_prereg.md`、`experiments/R011_path_declaration_audit/**`（含 `config.json`/`result.json`/`run.log`） |
| 台账联动 | `rounds/R011_report.md`（附录 **A-4**）、`paper_ledger.csv`、`shadow_registry.md`、`progress.md`（§1/§2/§9.5/教训 61/R011 收口记）、`stage_summary_R011_R015.md` §0/§1/§2.2/§10 |
| 选项③落地 | `preregistration_template.md` **§10 仪器合格标准**（`CLN`/`INJ` 语义与通过判据、测量方法合格标准、事后修改的处置） |

> **⚠️ 不得误读**：本裁定**不是**对项目的正面结论、**不是**「五处反例被推翻」，也**不得**读成
> 「只要结论对项目不利就可以事后改仪器」——恰恰相反，**正因为该修改改变了对项目不利的结论、
> 且发生在看到结果之后，才不予认可**。
> **残留风险（留给下一轮）**：强语义下「假设被证伪」与「仪器失效」在门层不可区分 ⟹
> **只要杀点为真，审计轮就无法给出 `REJECTED`**；该代价的正解是**事先在预注册里声明**（选项③），
> **不是**事后改仪器。

---

## §58 D-R011-04：是否把「备选集合」的定义提升为项目级研究规范（R011 新增）

### 事实

本轮判定 `V06`（可枚举）/`V07`/`V08`（不可枚举）用到的「备选集合」合格形态是：

1. **枚举列表**（如 `UNIFIED_HORIZONS = List.of(1,2,3,5)`，`FRS:97`）；或
2. **显式取值域**（如请求参数 / 前端控件边界 `:min="2" :max="10"`）。

**单个常量**（`= 5`）与**单个默认值**（`= 5`）**不算**枚举。
进一步地（`V07` 的核心口径）：**族级/UI 级取值域不构成项目级备选集合**——
它约束的是「用户能输入什么」，不是「项目研究过哪些 J」。
因此 `ScoringBacktest.vue:36` 的 `[2,10]` **不能**让 `group_count` 变成 `ENUMERABLE`。

### 为什么要用户决策

这条口径目前**只写在本轮预注册**里。若它应当成为项目级规范（影响未来所有轮的判定），
那就是**修改研究口径**（协议 §7 只记录项）——需用户确认；
若维持「每轮各自定义」，则本条留档即可。

### 建议

倾向 ①（提升为规范）：否则同一分叉在不同轮次可能因「口径松紧不同」得到相反判定，
形成**判定漂移**；本轮与 R006（rank_ic 命名族）已各自定义过类似口径，宜统一。

---

## §59 D-R012-01：`metric_scope` 的取值域（5 值）与其对外词表（3 值）不可调和（R012 新增，`V17 = SCOPE_VOCAB_UNRECONCILED`，**注册项不杀**）

### 观测（机械抽取，冻结扫描根内）

| 侧 | 内容 | 位置 |
|---|---|---|
| **实际取值域** | `{RAW, COST_APPROX, EXECUTABLE, FILLED_SAMPLE, UNAVAILABLE}` | `V17` 的 `domain`：跨 `FactorResearchService` + `ScoringBacktestService` 机械抽取 |
| **对外词表** | `RAW / COST_APPROX / EXECUTABLE` **共 3 类** | `T24`，`FactorResearchService.java:49`（类 Javadoc） |
| **差集** | `unreconciled = ['FILLED_SAMPLE', 'UNAVAILABLE']` | `V17` |
| `FILLED_SAMPLE` 出现处 | `ScoringBacktestService.java:466`（`assumptions.put("metric_scope", "FILLED_SAMPLE")`）与 `:485`（`.metricScope("FILLED_SAMPLE")`） | 2 处，两张载荷面 |
| `UNAVAILABLE` 出现处 | `FactorResearchService` 的 `unavailableScopeBlock`（`B05`）口径不可用退化块 | 1 处 |

### 为什么本来可能构成「一」（K3）但**没有**

冻结 §7 明文把 `SCOPE_VOCAB_UNRECONCILED` 定义为**注册项**：「同一键名（`metric_scope`）的**取值域**跨面不重合且**仓库内无统一词表** ⟹ 注册项（**不**作 kill）」。
本轮**实测正是**「仓库内无统一词表」（Javadoc 3 值 vs 实际 5 值），因此判 `SCOPE_VOCAB_UNRECONCILED` 而**不是** `INCONSISTENT` ⟹ **不进入 K3**。

### 为什么要用户决策

「Javadoc 词表是否必须是穷举」是一个**对外声明的完整度口径**，不是代码 bug：
- 若口径是「穷举」⟹ `T24` 应补 `FILLED_SAMPLE`/`UNAVAILABLE`，属**改已冻结口径的对外文本**（协议 §7 记录项）；
- 若口径是「举例」⟹ 应在 `docs/研究口径规范.md` 写明 `metric_scope` 为**开放键**。

无论哪种，都会影响未来轮次里「词表类」判定的期望值，故需用户定调。

### 建议

倾向 ①（补进词表）：成本极低、消除读者歧义；且本条与 `D-R012-02` **同键同族**，**应合并评审**。

---

## §60 D-R012-02：同名 `metric_scope = "RAW"` 在两个面指向**不同价格基准**（R012 新增，`V17` 附带观测，**按冻结 K3 措辞不构成 kill**）

### 观测（只读源码事实）

| 面 | 声明 | 实现 | 价格基准 |
|---|---|---|---|
| **legacy 面** | `FRS:74` `RANK_IC_BASIS_NOTE`：「毛收益 IC（`metric_scope=RAW`）：`forward return = close[t+h]/close[t]-1`，未计费用/滑点/涨跌停可成交性」 | `FRS:706-716` 用 `prices.close()` | 其 `prices` 来自 `MarketData` 侧的前复权序列（`FRS:898`/`:951` 注释；`V07-c` 的精确读数确认此路径**不读** `market_price_raw`） ⟹ **qfq close** |
| **snapshot 面** | `FRS:340` 注释：「RAW：`rawClose[t+h]/rawClose[t]-1`」 | `FRS:340-350` 用 `prices.close()`，而 `prices` 由 `loadRawPriceCache` 构造（`FRS:492-523` 仅读 `marketPriceRawRepository`，日期由实读 raw 构造） | **rawClose 不复权** |

### 判定（严格按冻结措辞）

冻结 K3 要求 `INCONSISTENT` 须「同面同层 + **无**正当作用域差异」。
这两者**有**正当作用域差异（一个是被声明为 `LEGACY_SELECTION_BIASED` 的历史对照面，一个是 `V2-MP-022` 权威面），且**各自为真、描述不同数据路径** ⟹ 与 `V18` 同型，按冻结口径**不构成 kill**。

**但**：同一个字面量 `"RAW"` 在两面意味着不同的价格基准（qfq vs 不复权），**读者无法从键值本身分辨**。本轮**只登记**，**不**主张这是缺陷、**不**主张任何一方算错。

### 为什么要用户决策

若未来有轮次按 `metric_scope = "RAW"` **跨面聚合**数值，两面会被**静默混池**。是否要在载荷文本/键上显式区分价格基准，属**改公开载荷面或契约**（协议 §7 记录项）。

### 建议

倾向 ①（在两面 `note` 里显式写出价格基准）或 ②（新增一个面级基准键，不动旧字段语义）；
**不推荐**直接改 `"RAW"` 的语义（会造成历史载荷不可比）。与 `D-R012-01` **合并评审**。

---

## §61 D-R013-01：零斜率（各组均值全相等）仍产出具名方向标签（R013 新增，**`K1` 承重**）

### 事实（实测，冻结扫描根内）

| 项 | 值 | 证据 |
|---|---|---|
| 输入 | 单日、`n = 20`、`groupCount = 5`（**默认值**） | `Q04_flat_groups` |
| 五组平均收益 | `0.03125 / 0.03125 / 0.03125 / 0.03125 / 0.03125`（**逐位相等**） | `result.json` `panels.Q04_flat_groups` |
| 标签 | **`MONOTONIC_INCREASING`** | 同上 |
| 两方向 flag | `increasing = true` **且** `decreasing = true` | `U01_const5`、`U02_flat_pair` |
| 取值归因 | 由**分支顺序**决定（`if (increasing)` 在前） | `FactorResearchEngine.java:226-249`（读码，锚点 A-01e 实测于 `:242`） |
| `long_short_spread` | `0.0`（**无区分度**） | 同上 |

**根因（读码）**：`assessMonotonicity` 的两个 falsifier 只用**严格不等**（`cur < prev` / `cur > prev`），
「全部相等」时二者**同时不成立** ⟹ 两个 flag **同为 `true`** ⟹ 返回值由先判的 `increasing` 决定。

### 与前几轮的关系（**必须写明，避免重复决策与虚假新颖性**）

**同一代码事实**已在 **`D-R006-07`** 登记（R006，`A7a`，判定 `REGISTERED_ONLY`，**不进 R006 的总判定**，
因为 R006 的 `H0` 是「IC 命名族的定义忠实性」，而 `REGISTERED_ONLY` 只作登记）。
R013 的差别是**被审计对象换成了标签自身的语义**（`H0` 的 (i) 名实子句），
因此同一事实**首次成为承重杀点**（`K1`），并新增两项读数证据（`slope_is_zero = true`、两 flag 同真的**单元构造**）。

### 判定与可达性

- **可达性 = `REACHABLE_PUBLIC_API` 之上的「无输入依赖」**：`RANK_IC_GROUP_COUNT = 5` 是常量、
  面板是默认组数 ⟹ **不需要任何客户端输入**即可触发（比 `D-R013-02` 的触发面更广）。
- R013 判 `SEMANTICS_GAPPED`、`reading_dependent = false` ⟹ 计入 `K1`。

### 为什么不自动修

三种修法**全部**落在协议 **§7 record-only tier**：
①改标签算法（`assessMonotonicity`）＝改因子口径；②新增枚举值 `NO_DISPERSION` ＝改**公开契约**（前端与消费方需同步）；
③改前端展示语义。**agent 一律不得自行实施**。

### 建议

① **最小语义修**：全相等时返回 `"N/A"`（与 `emptySummary` 的取值一致，不新增枚举、不动契约）；
② 或新增 `NO_DISPERSION`（语义最准确，但**改枚举 = 改契约**）；
③ 或仅补文档（最低成本）。
**与 `D-R006-07` 合并评审**（同一代码事实，两轮不应各自决策）。

---

## §62 D-R013-02：`groupCount > n` 的占位零**参与方向断言**（R013 新增，**`K2` 承重**）

### 事实（实测）

| 项 | 值 |
|---|---|
| 输入 | `factorGroupCount = 20`、每日 10 个样本、2 个截面日 |
| `groupedReturns` 长度 | **20**（`real_groups = 10`） |
| 占位组 | **10 个**满足 `observations == 0 && avgReturn == 0.0` |
| 真实 10 组均值 | `-0.0078125 … -0.078125`（**严格递减**） |
| 标签 | **`NON_MONOTONIC`** |
| `long_short_spread` | **`+0.0078125`**（**正号**；真实序列**全为负**） |

**机理**：占位零被当作「第 11 组的观测值」参与相邻比较 ⟹ `-0.078125 → 0.0` 构成一次**上升**，
外加 `0.0 → 0.0` 的相等 ⟹ 引擎据此判 `NON_MONOTONIC`。
**即：退化输入不只是污染了标量 `spread`，而是制造出一个并不存在的「非单调」结论。**

### 与前几轮的关系（**必须写明**）

**同一代码事实**已在 **`D-R006-04`** 登记（R006 用 `n = 12`、`groupCount = 20` 测得 8 个空组、
`long_short_spread` 逐位等于 `0 − G1`、符号反转）。
R013 的**新增后果**是：占位零**参与方向断言**（R006 测的是 spread 的**构成**，R013 测的是**标签的形状判断**）。

### 可达性（冻结阶梯口径）

| 路径 | 判定 | 依据 |
|---|---|---|
| 公开请求字段 `factorGroupCount` | **可达** | 无服务端校验（见 `D-R013-03`） |
| 前端控件 | 不可达 | `ScoringBacktest.vue:36` `:min="2" :max="10"`（**仅 UI 缓解**） |
| 研究端点 | 不可达 | 三处 `summarize` 调用点全传常量 `RANK_IC_GROUP_COUNT = 5` |

**反事实披露**：若把可达性收窄为「仅经**客户端字段**可达 ⟹ 不计入 `K2`」，则 `K2` 不触发——
**但 `total_verdict` 仍为 `REJECTED`**（`K1`、`K3` 均不依赖 `K2`）。该反事实**不改变** R013 的任何结论。

### 建议

① 空组改输出「空」标记或 `avg_return = null`（**改 DTO**，须评审）；
② 给 `factorGroupCount` 补服务端约束（**改契约**，见 `D-R013-03`）；
③ 至少补文档。**与 `D-R006-04` 合并评审**。

---

## §63 D-R013-03：`factorGroupCount` 无服务端校验（R013 新增，**`D-R006-04` 建议①的证据补强**）

### 事实（机械抽取，冻结根内）

| 站点 | 内容 | 校验 |
|---|---|---|
| `ScoringBacktestRequest.java:37-38` | `private Integer factorGroupCount = 5;` | **3 个候选注解（`@Min`/`@Max`/`@Valid`）全部未命中** |
| `modules/web/src/types/backtest.ts:12` | `factorGroupCount?: number;` | 无约束 |
| `ScoringBacktest.vue:36` | `<el-input-number … :min="2" :max="10" :step="1" />` | **仅 UI 缓解**（非契约约束） |
| `FactorResearchService` 三处 `summarize` | 全传常量 `RANK_IC_GROUP_COUNT = 5` | 不经过该字段 |

⟹ 冻结可达性措辞下 = **`REACHABLE_PUBLIC_API`**（经公开请求字段可达）。

### 为什么单列（而不是并入 `D-R013-02`）

`D-R013-02` 是**后果**（形状被污染），本条是**可达性的证据链**（校验缺失）。
R006 的 `D-R006-04` 已把「补 `@Min`/`@Max`」列为建议①，但当时**未**给出「3 个候选注解全未命中」这一机械读数。
本轮补上该读数，并把 TS 层（`?: number`）与 UI 层（`:min/:max`）的**分级**一并留痕。

### 建议

① 补 `@Min(2)`/`@Max(上限)` 约束（**改契约**，须评审：上限取值本身需要口径决策）；
② 或 DTO 层钳位 + **在载荷中显式标记已钳位**（否则会重演 `D-R010-06` 的
「计算取钳位值、持久化取原始值且无标记」同型问题）；
③ 维持现状 + 文档。**与 `D-R006-04` 合并评审**。

---

## §64 D-R013-04：`round4` 使标签**在载荷层面不可重建**（R013 新增，**`K3` 承重**）

### 事实（实测）

| 项 | 值 |
|---|---|
| 引擎五组均值（全精度） | `1.4E-4, 1.2E-4, 1.0E-4, 8.0E-5, 6.0E-5`（**严格递减**） |
| 引擎标签 | `MONOTONIC_DECREASING` |
| 载荷层 `round4(v) = Math.round(v * 10000.0) / 10000.0` 后 | `1.0E-4; 1.0E-4; 1.0E-4; 1.0E-4; 1.0E-4`（**全部相等**） |
| 由**载荷自身**重建的标签 | **`MONOTONIC_INCREASING`** ⟹ **与引擎标签不同** |

**注**：该反例由 R013 的**预注册** `P-16` **事先预测**（「存在一组组均值使 `round4` 后相等、全精度不等」），
非事后发现。

### 与 `D-R007-09` 的关系（**同类根因、不同后果**）

`D-R007-09`（R007，**post-hoc 未预注册**）记录的是 `round4` 的另外两个后果：
`NaN → 0.0`、`±Inf → 9.223372036854776E14`，以及 `[mean ± 1.96·se]` 的**伪零宽**。
本条记录的是**标签不可重建**（消费方无法从载荷复算标签）。
两轮同指 `round4`，但**不同载荷族、不同子句**，可**合并评审**。

### 建议

① 载荷给出**未取整**的组均值（或至少给出判定所依赖的比较信息，如相邻组均值差的符号）；
② 或给标签补「由全精度序列计算、载荷组均值为展示精度」的显式标注；
③ 维持现状 + 文档声明。**均属改公开载荷面 ⟹ record-only**。

---

## §65 D-R013-05：`factor.ts` 的 `monotonicity: string` 无值域（R013 新增）

### 事实

`modules/web/src/types/factor.ts:111` 与 `:202` 均为 `monotonicity: string`；
三读数（子串 / 类型分派收紧 / 人工逐字）**一致**判 `value_domain_declared = false` ⟹ `CLAIM_ABSENT`、
`reading_dependent = false`。同批抽取还确认：前端**零** `MONOTONIC_` 字面量出现。

**后果**：消费方**无法静态**知道取值集合是 4 值枚举（且其中一值 `N/A` 有**三个**不同来源，
虽可由 `status` / `total_observations` / `note` 等键区分，见 `V21 = TRUE_CLAIM`）。

### 建议

① 改为字面量联合类型（**改前端契约**，须评审：若后端将来新增枚举值，前端会编译期报错——这既是优点也是耦合）；
② 或先补注释/tooltip；
③ 维持现状 + 文档。**与 `D-R012-01`（`metric_scope` 词表未对账）合并评审**（同族：取值域 vs 对外声明）。

---

## §66 D-R013-06：两个 DTO 均不携带 `monotonicity`（R013 新增）

### 事实（反射实测，冻结根内）

| DTO | 字段数 | builder 方法数 | `monotonicity` | `groupedReturns` |
|---|---|---|---|---|
| `FactorAnalysis` | 12 | 14 | **无** | **有** |
| `FactorIcSummary` | 9 | 10 | **无** | **无** |

⟹ 经 **DTO** 消费该研究结果的路径**拿不到标签**（标签只存在于载荷 map 中）。

### 边界（不夸大）

这**不**意味着「载荷缺失」——载荷 map 里确实有标签（`V15` 四发射面同词表）。
本条只登记：**DTO 面与 map 面的字段集合不一致**，其后果是「同一份研究结果的两种消费方式可得到不同信息量」。

### 建议

① 按需给 `FactorAnalysis` 增 `monotonicity` 字段（**改 DTO / 公开契约**，须评审 + 迁移）；
② 或明确声明「标签只在载荷 map 中、不在 DTO 中」并补文档；
③ 维持现状。

---

## §67 D-R013-07：标签所在块的**披露粒度不一致**（R013 新增）

### 事实（按「标签所在 map 的键集」机械抽取）

| 标签块 | 方法 | `monotonicity` | `group_count` | 每组样本量（经 `grouped_returns[].observations`） |
|---|---|---|---|---|
| B-01 | `buildSnapshotScopeBlock`（`FRS:526-554`，标签 `:543`） | **有** | **无** | **有**（`:549`） |
| B-03 | `buildRankIc`（`FRS:641-798`，标签 `:762`） | **有** | **有**（`:650`） | **有**（`:787`） |
| B-04 | `buildNetIcBlock`（`FRS:820-839`，标签 `:831`） | **有** | **无** | **无** |

读数：`blocks_with_group_count = 1`、`blocks_with_per_group_obs = 2`、`label_blocks = 3` ⟹ `V18 = FALSE_CLAIM`。

**可核验的上下文**：`cost_net_ic` 子块的 summary 用**同一个** `RANK_IC_GROUP_COUNT = 5` 与
`RANK_IC_MIN_CROSS_SECTION_SIZE = 10` 构造（`FRS:790-796`）⟹ 组数**事实上**可从**兄弟块**推断，
但**不能**从该块自身读出；且跨块推断在消费方只取 `cost_net_ic` 时不成立。

### 边界（不夸大）

B-03 的 map（声明于 `:648`）同时拥有 `:650 group_count` 与 `:762 monotonicity` ⟹ **不是**「全部块都不披露」。
本条的**精确主张**是：**3 个标签块中 1 个披露组数、1 个披露每组样本量**，其中 `cost_net_ic` 块**两者皆无**。

### 建议

① 给 `cost_net_ic` 块补 `group_count`（与 `grouped_returns`）——**改公开载荷面**，须契约评审；
② 或在其 `note` 文本中声明组数与截面下限口径；
③ 维持现状 + 文档。与 R012 的「承重块补键」决策（`D-R012-01` 同族）**合并评审**。

---

## §68 D-R013-08：同一数据因 `groupCount` 移动 ±20% 而改变标签（R013 新增，**披露项**）

### 事实（±20% 网格，21 个单元行，其中冻结口径 18 单元）

| 面板 | `gc = 4` | `gc = 5`（**默认**） | `gc = 6` |
|---|---|---|---|
| `G01_grid_up`（非退化） | `MONOTONIC_INCREASING` ×3 | `MONOTONIC_INCREASING` ×3 | `MONOTONIC_INCREASING` ×3 |
| **`G02_grid_flat_pairs`**（周期 2 取值） | **`MONOTONIC_INCREASING`**（spread `0.0`） | **`NON_MONOTONIC`**（spread `0.0046875`） | **`MONOTONIC_INCREASING`**（spread `0.0`） |

- `minSize ∈ {8,10,12}` **不改变**任何非退化网格单元上的标签；
  它只改变 `G03_short_section`（每日 9 样本）的**纳入/丢弃**：`ms = 8` 纳入（`totalObservations = 18`）⟹ `MONOTONIC_INCREASING`；
  `ms = 10/12` 全部丢弃（`totalObservations = 0`）⟹ `N/A`。
- 冻结预测 `P-17` 只覆盖**非退化**面板（`G01`）⟹ 实测 9/9 相同、预测**中**；
  `G02` 的翻转是**预测外观测**，本轮**如实登记**而非记为「预测不中」。

### 判定

**本轮不主张这是缺陷**：`groupCount` 本来就是分析路径分叉（R011 已覆盖其**可恢复性**），
不同组数给出不同形状结论在统计上并不自动异常。**本条的用途是披露**：
载荷中的标签**没有**随标签一起输出「它是用哪个组数算的」。

### 建议

① 在载荷/展示面标注「标签依赖组数参数」并**给出所用组数**（与 `D-R013-07` 建议①同向）；
② 或维持现状 + 文档披露；
③ **不**建议改默认组数（属改因子口径，须用户确认）。

---

## §69 D-R013-09：非有限组均值未被过滤且可给出方向标签（R013 新增，**明确不作 `K2` 承重**）

### 事实（实测）

| 项 | 值 |
|---|---|
| 面板 | `Q11_nan_return`（含非有限 `forwardReturn`） |
| 组均值 | 第 2 组为 **`NaN`**，其余 4 组有限 |
| 标签 | **`MONOTONIC_INCREASING`** |
| `total_observations` | 10（**含**该非有限观测） |
| 机理 | `summarize` 对该面板**未**做收益有限性过滤；`NaN` 与任何数比较**恒假** ⟹ 既不产生严格反序对、也不产生递增对 |
| 单元对照 | `U03_nan_mid`：`increasing = true`、`decreasing = false` ⟹ 不构成否证 |

### 可达性 = `SYNTHETIC_ONLY`（**已核验**）

生产调用点的收益由**受守卫算术**产生：`ScoringBacktestService.java:955-1015` 的入场守卫链
（`rawEntryPrice <= 0 → null`、零成交 → `null`、开盘涨停 → `null`）⟹ `entryCost > 0` ⟹
`ret = exitProceeds / entryCost - 1` **有限**。

### 判定

⟹ **本轮明确不把 NaN 组均值计为任何 `K` 的证据**（`K2` 只由 `Q06_overgroup` 的占位零承重）。
本条登记的意义是「**若未来收益来源变化**（例如引入新的收益构造路径），这条不再成立」。

### 建议

① 若未来收益来源变化，给组均值加有限性守卫（与 `D-R006-05` 的 `emptySummary` 占位族**同型**）；
② 当前**不可达** ⟹ 最低成本 = 文档披露 + 本台账留痕。

---

## §70 D-R014-01：两数组载荷**不发射分组边界规则**（R014 新增，**`K1` 承重面**）

### 事实（机械抽取 + 实测）

| 项 | 值 |
|---|---|
| 实际规则 | `FactorResearchEngine.java:100` `sorted.sort(Comparator.comparingDouble(CrossSectionObservation::score))`（**稳定**排序）+ `:108` `int bucket = Math.min(g - 1, (int) ((long) i * g / n));`（**下标等分**） |
| 载荷是否声明 | **否**：`V05 = RULE_UNDECLARED`（载荷键集内零命中 `group_boundary_rule`/`bucket`/`等分` 类字段） |
| 相邻文案 | `v05_adjacent_generic_text_hits = 3` —— 仅出现「等数量」这一**通用词**，**不构成**规则声明（按冻结 §12.2 收窄读数） |
| 后果 | 读者若只有「载荷 + 原始截面**多重集**」，必须自己决定并列行的相对次序与边界归属 ⟹ 见 `D-R014-02` |

### 边界（不夸大）

本条**不**主张「规则设计有错」——下标等分 + 稳定排序是**有效**做法；
被审计的是**载荷的自我描述完备性**（H0 子句 (i) 的「可重建」）。
`V01`/`V02`（无并列 / 并列恰在桶内）在**同规则**下是 `RECONSTRUCTABLE` ⟹ 规则本身**不是**缺陷来源。

### 建议

① 在携带两数组的块（`F02:526-554`）中补一个规则标识键（如 `group_boundary_rule: "INDEX_EQUAL_SPLIT_STABLE_SORT"`）；
② 或至少在 `note` 文本中声明等分规则与并列处理；
③ **改公开载荷面 ⟹ record-only**；与 `D-R013-07`（披露粒度）、`D-R012-01`（承重块补键）**同族合并评审**。

---

## §71 D-R014-02：桶成员依赖**未声明的输入行序**（R014 新增，**`K1` 承重面**；影响面未测量 ⟹ `B-R014-02`）

### 事实（实测，同一多重集）

| 组 | 声明序 | 逆序 | 差异 |
|---|---|---|---|
| G1 | `0.00255` | `0.0036833333333333336` | **+0.0011333** |
| G2 | `0.00595` | `0.005383333333333334` | **−0.0005667** |
| G3 | `0.00935` | `0.008783333333333334` | **−0.0005667** |
| G4 | `0.012750000000000003` | 同左 | 0（桶内全并列） |
| G5 | `0.01615` | 同左 | 0（同上） |
| `rank_ic`（三日） | `0.9878044218151566` | 同左 | **`maxdiff = 0.0`**（IC 对行序不敏感） |

- `panel_hash` **相同**（同一多重集）⟹ 载荷层面**无法区分**这两种输入；
- 两次取逆（`reversed` 再取逆）与声明序**逐位相同** ⟹ 依赖**真实且可撤销**；
- `P2`（并列**桶内**）声明序 vs 逆序**完全相同** ⟹ 把「并列本身」与「未声明行序依赖」**分开**。

### **不得**读成「真实数据上分组收益会随机跳变」

真实行序来自 DB 读取路径，本轮行序是**合成面板的行序** ⟹ **发生频率与影响幅度均未测量**（`B-R014-02`）。
本报告**明确拒绝**该外推（`R014_prereg.md` §14 第 13 条）。

### 建议

① 在读取截面处显式钉住行序（`ORDER BY` 到足够唯一的键）；
② 或在载荷中声明「并列行的相对次序不作保证」；
③ 或让分桶对并列**不敏感**（按名次而非行序）；
④ **均属改因子口径/公开契约 ⟹ record-only**；与 `D-R013-02`/`D-R006-04`（退化输入族）**不同根因**，**单列评审**。

---

## §72 D-R014-03：`observations` **同名不同量**（R014 新增，**`K2` 承重面**）

### 事实（实测 + 机械抽取）

| 面 | 字段 | 值 | 量 |
|---|---|---|---|
| 聚合（`P1_dec`） | `grouped_returns[].observations` | G1–G5 全 **3** | = `cross_section_days` = **天数** |
| 日级（`P1_dayg`） | 同名字段 | G1–G5 全 **2** | **每组名字数** |
| 产生点 | `F01:114-115`（日级）/ `:179-183`（聚合） | 两处都是 `rets.size()` | **含义随聚合层级改变而字段名不变** |

### 边界（不夸大）

两处都**忠于自己计的对象**（`V15` 正面对照：Σ `sample_size` ≡ `total_observations`，`maxdiff = 0`）。
被审计的是**同名字段的单位唯一性**（H0 子句 (ii)），**不是**计数本身算错了。

### 建议

① 给日级面字段改名（如 `name_count`）并与聚合面区分；
② 或在 DTO 上补**字段级 javadoc** 声明单位（`ScoringBacktestResponse.java:98-117` 当前**无**字段级注释，而其后的 `PeriodStat` **有** —— 说明这是**遗漏**而非约定）；
③ 或在载荷中增加显式单位键；**改契约 ⟹ record-only**。

---

## §73 D-R014-04：两面/同页**标签不一致**（R014 新增，**`K2` 承重面**）

### 事实

| 位置 | 标签 | 指向的量 |
|---|---|---|
| `FactorResearch.vue:151` | 「观测数」 | 组 `observations` = **天数** |
| `FactorResearch.vue:131` | 「观测数」 | `total_observations` = **Σ 名字数** |
| `ScoringBacktest.vue:378` | 「截面日观测」 | 组 `observations` = **天数** |

⟹ ① **同一页面同一标签**指向两个不同的量（`v08_same_label_two_quantities = true`）；
② **两面标签不同**（`v07_labels_equal = false`）。

### 建议

① 统一两面标签（与 `D-R006-01` 对 `rank_ic_*` 标签缺 `Rank` 限定的处置**同族**）；
② 给同页两处标签加限定词（如「截面日数」vs「成分股数」）；
③ **改前端展示口径 ⟹ record-only**。

---

## §74 D-R014-05：`round4` 把 `NaN` 写成 **`0.0`** —— 把「无数据」显示成「0 收益」（R014 新增，**`K3` 承重面**；`D-R006-05`/`D-R013-09` 同型）

### 事实（实测 + 真实 JVM 标定）

| 项 | 值 |
|---|---|
| 引擎输出 | `P4` 日 2 的 G2/G4 组均值为 **`NaN`** |
| 因子面（`F02:592-601` + `:1030-1031`） | `round4(NaN) = 0.0`（`Math.round(NaN) = 0`） |
| DTO 面（`F03:1018-1046`） | **`NaN`**（原样） |
| 标定 | `_scratch/RoundSemanticsProbe.java` 直接调用真实 JVM，产物 `_scratch/round_semantics.txt`：`round4(NaN) = 0.0`、`round4(0.00255) = 0.0026`、`round4(0.03125) = 0.0313`、`±∞ → ±9.223372036854776E14`（`long` 饱和） |
| 计数 | `v04_nan_to_zero_count = 2`（全部来自该日两组） |

**为什么这比「第 5 位小数被抹掉」更严重**：`0.0` 是一个**具体且可比较**的收益值，
读者无法把「无数据」与「真的 0 收益」区分开；而同一份数据的另一面（DTO）保留 `NaN` ⟹ **同一系统对同一事实给出两种表示**。

### 建议

① 给 `round4` 加有限性守卫（`!Double.isFinite(v) → 原样返回或 null`）；
② 或统一两面策略（DTO 承载原始值 + 展示层取整，见 `D-R014-08`）；
③ **改冻结口径的取整行为 ⟹ record-only**；
④ 与 `D-R006-05`（`emptySummary` 占位 0）、`D-R013-09`（非有限组均值）**合并评审**：
**同一「非有限值被写成具体数」形态的第三/第四个实例**。

---

## §75 D-R014-06：计数字段把**非有限观测**计入（R014 新增，**`K4` 承重面**；可达性读数分歧已披露）

### 事实（实测）

| 项 | 值 |
|---|---|
| 面板 | `P4` 日 2：10 行，其中 **2** 行 `forwardReturn = NaN`（有限值 **8**） |
| `sample_size` | **10**（≠ 8） |
| 该日是否进入 `daily_rank_ic` | **是**，`rank_ic = 0.6139845991168652` |
| `total_observations` | **20**（**含** 2 个非有限观测） |
| 组均值 | **2** 个组的 `avg_return = NaN` |
| 引擎守卫 | **无**有限性守卫：`F01:140` 只按**行数**比较 `minCrossSectionSize`、`:158` 按 `obs.size()` 累加；`:150` 的 `!Double.isFinite(ic)` 早退发生在**算完之后** |

### 可达性读数分歧（**与结论一起读**）

| 读数 | 依据 | 等级 | `K4` |
|---|---|---|---|
| **事前配置（本报告判定口径）** | `config.json`（冻结提交内落盘，先于运行） | `REACHABLE_PUBLIC_API` | **true** |
| **守卫算术读法** | 冻结根内 **7** 处守卫：`F02:335`（得分 `null/≤0` 跳过）、`F02:346`/`359`/`379`（快照路径价格/标签守卫）、`F02:700`/`712`/`726`（旧路径同型） | `SYNTHETIC_ONLY` | **false** |

**处置**：本轮**保留** `K4 = true`（按事前配置测得），**不**改冻结文本、**不**事后放宽任何 `K`；
反事实显示 `total_verdict` **不变**（`K1`/`K2`/`K3` 独立）⟹ 判定对这条读数分歧**稳健**。

### 建议

① 计数只统计**有限**观测（或另给 `finite_count`）；
② 或对含非有限值的日**早退**（与 `F01:150` 的 IC 有限性早退**同型**）；
③ 与 `D-R013-09` **合并评审**（同一「非有限收益进引擎」根因：R013 后果 = 标签、R014 后果 = 计数）。

---

## §76 D-R014-07：`minCrossSectionSize` **静默丢日**且常量不可从载荷读出（R014 新增，`V17` **描述性、不计 `K`**）

### 事实（实测）

| 项 | 值 |
|---|---|
| 面板 | `P6` **6** 天（规模 12,12,4,12,4,12） |
| 载荷 | `cross_section_days = 4` ⟹ **静默丢 2 天**（`min = 8` 时仍为 4） |
| 披露 | 两发射方法键集合计 **27** 个中**零**个披露该丢弃（`v17_disclosure_field_count = 0`） |
| 常量 | `FactorResearchService.java:70-71` 的 `RANK_IC_MIN_CROSS_SECTION_SIZE = 10` **从不入载荷** |
| 宽候选 | `partitions_skipped`（`F02:445`/`:612`）取值 `skippedDates` 溯源到 `F02:279` `isReusableManifest` ⟹ **语义无关**（清单可复用性），已**同时输出**含行号以便复核 |

### 建议

① 新增「丢弃日计数 + 阈值」字段（与 `D-R006-03` 对退化日丢弃的建议**同一形态**，可**合并**）；
② 或在 `universe_note` 中显式追加丢弃说明；
③ **不要**改成保留不足规模日（会引入更差口径）。

---

## §77 D-R014-08：两面数值不等（`round4` 截断 65/95）（R014 新增，**`K3` 承重面**；与 `D-R013-04`/`D-R007-09` 同一 `round4` 根因、后果不同）

### 事实（实测）

| 项 | 值 |
|---|---|
| 两面映射 | 因子面 `round4(引擎值)`（`F02:1030-1031`）；DTO 面**原样**（`F03:1018-1046`，**唯一**不经 `round4` 的发射面） |
| 被截断数 | **65 / 95**（`rank_ic` **19** / `avg_return` **46**） |
| 最大截断 | **5.000000000000143e-05**（= 4 位小数的半个刻度） |
| 实例 | `rank_ic 0.9878787878787879→0.9879`、`0.9878044218151566→0.9878`；`avg_return 0.00255→0.0026`、`0.00595→0.006`、`0.004533333333333334→0.0045` |
| 反向锚点 | **移除 `round4` 后两面分歧 = 0**（覆盖 95 个有限值 + 2 个非有限值）⟹ 分歧**可归因**到该映射本身 |

### 与既有项的关系

| 项 | 同一根因 | 后果 |
|---|---|---|
| `D-R007-09` | `round4` | 不确定性区间（`hac_std`/`ci_*`）被取整 |
| `D-R013-04` | `round4` | `monotonicity` 标签在载荷层面**不可重建** |
| **`D-R014-08`** | `round4` | **两面数值不等**（同一量在两份载荷中取值不同） |

### 建议

① 统一为「DTO/载荷承载**原始值** + 展示层取整」；
② 或两面都用**同一**取整函数（最小改动，但仍有精度损失且需声明位数 ⟹ `D-R014-09`）；
③ 与 `D-R007-09`、`D-R013-04` **合并评审**。

---

## §78 D-R014-09：载荷**不声明取整规则**（R014 新增，`V14` **描述性、不计 `K`**）

### 事实

- `V14 = RULE_UNDECLARED`：载荷键集内**零**命中；
- 实际行为：`F02:1030-1031` 做 4 位小数 `Math.round`（**有些值变、有些不变**：65/95）；
- ⟹ 读者从载荷**无法**判断「这个数是原始值还是取整值」，也**无法重建**取整前的值。

### 建议

① 在载荷中补 `rounding_rule` / `precision` 声明键；
② 或改由展示层取整（则载荷无需声明 ⟹ 与 `D-R014-08` 的推荐①同解）；
③ **改公开载荷面 ⟹ record-only**；与 `D-R014-01`（分组规则声明）**同族**：
都属于「**载荷不声明自己的构造规则**」。

---

## §79 D-R014-10：预注册**未逐变体冻结 `reachability` 等级**（R014 新增，**装置层元项**，不改生产代码）

### 事实

| 项 | 值 |
|---|---|
| 冻结文本（`R014_prereg.md` §8） | 只冻结可达性**阶梯**：`REACHABLE_PUBLIC_API > REACHABLE_CLIENT_INPUT > SYNTHETIC_ONLY > UNREACHABLE` |
| 逐变体等级来源 | **运行前**落盘的 `config.json`（非冻结表） |
| 实际后果 | `V16` 被标 `REACHABLE_PUBLIC_API` ⟹ `K4 = true`；运行中在冻结根内找到 **7** 处守卫**反证**该等级（`V16` 实为 `SYNTHETIC_ONLY`）⟹ 报告附录 `A-03` |
| 本轮处置 | **保留** `K4 = true`（按事前配置）、**未**放宽任何 `K`、冻结文本**一字未改**；反事实显示 `total_verdict` 不变 |

### 为什么单列

这不是「项目代码的问题」，而是**本轮装置的冻结纪律缺口**：
凡「某等级由运行前配置决定、而不由冻结文本决定」的装置，都会在**证据与该等级冲突**时
面临「按配置读」还是「按证据读」的两难。本轮选了**不回溯修改**（保守），但缺口会在同类装置中**反复**出现。

### 建议

① 后续同类装置把逐变体 `reachability` 纳入**预注册冻结表**（而不是 `config.json`）；
② 或在大纲中要求「等级判定必须附**守卫清单**证据」；
③ **属装置层改进 ⟹ record-only**；与 `B-R014-02` **互补**：一个管**能测什么**、一个管**怎么冻结**。

---

## §80 D-R015-01：回退**未具名**——`universe_count` 被静默替换为 `total_observations` 而载荷无标记（R015 新增，**`K1` 承重的披露面**）

### 事实（反射实测 + 机械抽取）

| 项 | 值 |
|---|---|
| 回退代码（冻结扫描根内） | `FactorResearchService.java:850–861`：`if (universeCount == 0) universeCount = totalObservations;` |
| 代数等价（实测 **316/316**） | `universe_days_with_snapshot == 0` ⟺ `universeCount == 0` ⟺ **回退已执行** ⟹ 退化在**本装置**下**可分辨** |
| 但**载荷**是否说了这件事 | **没有**。`universe_source` 是**常量**（实测域：`sparse_selector_result` / `feature_snapshot_row`），从不因回退改变；`note` 文本也不含回退语义（`named_key_hits = []`、`text_token_hits = {}`） |
| 去 marker 后的可误分类场景 | **29** 个（`u == total` 但 `days > 0` 的正常场景会被读成回退、或反之） |

### 为什么单列（与 `K2` 的关系必须写清）

- 本轮 `K2`（`FALLBACK_UNDISCLOSED` = 回退**不可分辨**）**未触发**：因为 marker 与回退**代数等价**，退化**可以**被分辨
  ⟹ 这条**不是**「不可分辨」，而是「**未被具名**（`FALLBACK_UNNAMED`）」，属**披露不足**；
- 本轮把该披露不足记在 **`K1`**（键名设计：两个键名承载同一变量的值）与**报告正文**，**没有**把它升格成 `K2` —— 这是事前预注册写死的边界（`R015_prereg.md` §14 第 15 条），**未放宽**。

### 建议（record-only）

① 给回退加**具名标记**（新键，或把回退状态写进 `universe_source`）；② 或至少在 `note` 中说明本次 universe 已被替换；
③ **改公开载荷面 ⟹ 协议 §7 record-only**，本轮**未**实施任何一项。

---

## §81 D-R015-02：legacy `funnel` 带 `metric_scope = COST_APPROX` 却**无成本口径内容**（R015 **降级登记**，非本轮发现）

### 事实（机械抽取）

| 面 | `metric_scope` | 块内是否有成本口径内容 |
|---|---|---|
| 顶层载荷 | `COST_APPROX` | 顶层另有 `rank_ic` 块的 `metric_scope = RAW`（两值是**不同语义面**） |
| legacy `funnel` 块 | `COST_APPROX`（**无条件写入**） | **无** |
| snapshot `funnel` 块 | `COST_APPROX`（同样无条件） | 有成本近似计数（`cost_approx_observations`） |

### 判定与点名

本轮**只登记**、**不写作发现**：该口径不一致在 **R004** 已被登记 ⟹ 此处**点名 R004**，
不在 R015 结论中主张新颖性（`R015_prereg.md` §14 第 16 条）。

### 建议

沿用 R004 的处置；若用户决定统一，应把「`metric_scope` 只表示**该块内数值**的口径」写成项目级约定 —— record-only。

---

## §82 D-R015-03：两对**别名键**没有任何说明其区别的文本（R015 新增，**读数依赖已披露**）

### 事实（三读数实测）

| 读数 | 结果 |
|---|---|
| (a) 子串（大小写无关）搜「alias / 别名 / 同义」类词 | 命中 **0** |
| (b) 按被测对象类型收窄（只在**声明性文本**中找，排除界面标签） | 命中 **4** 行，**全部**是前端界面标签（`F10:198`「有效观测（得分>0）：`{{ result.valid_observations }}`」/ `F10:197`「总观测」/ `F10:131`「观测数」/ `F10:237`「观测数」）⟹ **不构成「声明」** |
| (c) 人工逐字 | 同样**找不到**任何说明两键关系的文本 |

⟹ 该变体 (`V05`) 被标 **`reading_dependent = true`** 并**排除出 `K1` 候选集**、降级为**描述性**
（`R015_prereg.md` §12 第 3 条，**未事后放宽**）。本轮 `K1` 的承重由 **`V01/V02/V03/V04`** 承担（键—值唯一性，与读数无关）。

### 与前轮的关系（**必须写明**）

| 键对 | 前轮点名 |
|---|---|
| `feature_valid_count` ≡ `valid_observations` | **R013 `D-R013-07`**（标签所在块披露粒度不一致） |
| `selector_item_count` ≡ `total_observations` | 同上 |

⟹ 本轮**不声称首次发现**，只把口径**收敛到键—值的**唯一性（两台不同名键由**同一个变量**填充）并把读数依赖完整披露。

### 建议

① 改键名（去掉别名）；② 或加**字段级说明**；③ **属公开载荷面 ⟹ record-only**；与 `D-R014-01`/`D-R014-02`（载荷不声明自己的构造规则）**同族**。

---

## §83 D-R015-04：前端 `factor.ts` **只声明 7 键**且 `coverage` **无单位说明**（R015 **降级登记**，点名 R009）

### 事实（机械抽取）

| 项 | 值 |
|---|---|
| 前端类型声明 | `factor.ts:52–60` 声明 **7** 键 |
| 未声明 | `universe_days_with_snapshot` / `valid_observations` / `total_observations` / `cross_section_days` / `universe_source`（**5** 键） |
| `coverage: number` | **无单位说明**（实际实现为**百分比刻度**：`Math.round(validCount * 10000.0 / allItems.size()) / 100.0`，实测 `83.33`） |

### 判定与点名

该「类型缺键」在 **R009 `A5b`** 已登记（`factor.ts:830` 有声明、`:810` 无）⟹ **点名 R009**，本轮**降级登记**、不主张新颖性。

### 建议

补齐类型声明 + 给 `coverage` 加单位注释 ⟹ **前端契约 record-only**（协议 §7：修改公开 API/前端契约只登记）。

---

## §84 D-R015-05：**反例登记**——`coverage` 刻度与前端阈值**一致**，**不构成**缺陷（R015 **明确「不得写成发现」**）

### 事实

| 项 | 值 |
|---|---|
| `coverage` 刻度 | **百分比**（`×100`），实测 `83.33`（主面板）与 `66.67`（snapshot 面） |
| 前端阈值 | `FactorResearch.vue:408–419` 的 `coverageClass` 用 `80` / `50` 分档 |
| 结论 | `80` / `50` **与百分比刻度一致** ⟹ **无阈值错配** |

### 为什么单列

我在选题阶段曾把「`coverage` 可能是 0–1 刻度、而阈值按百分比写 ⟹ 显示分档恒为最差档」列为**候选杀点**；
**读数直接否证了它**（`×100` 在 `F02:156–160` 明写）。按 §12 纪律，**被证据否决的假设必须显式登记为「不得写成发现」**，
以免后续轮次把它当成新发现重复提交（`R015_prereg.md` §14 第 16 条）。

### 建议

**无建议**（不是缺陷）。本项**永久保留在本队列表内**作为**反例锚点**：任何后续轮次若要主张「`coverage` 刻度与阈值错配」，必须先解释本项证据。
