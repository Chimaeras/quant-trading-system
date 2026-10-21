# 交接：裁定 A/B/D 已交付，剩余任务与提示词（2026-09-15）

> 用途：本会话过长，改由新会话按本文件逐项执行。**每项都给了一段可直接粘贴的提示词。**
> 权威依据（写代码前必读）：`research/paper_loop/decisions/decision_batch3_2026-09-15.md` §3（裁定 C）、
> §9.2（`D-R010-01` 待你一行确认）、§9.3（三条覆盖项的**执行内容与数值方向**）、§9.5（执行顺序）。
> 协议与红线：`docs/Codex论文驱动量化改进执行协议.md` §8（9 条硬红线）。

---

## §0 现状（工作树 / 远端实测，2026-09-15）

**已交付并推送**（`origin/master` = `b0825aca`）：

| commit | 内容 |
| --- | --- |
| `ffeba608` | `D-R011-02` 冻结仪器 V17 判据改词边界命中 |
| `568b2386` | `D-R014-02` 截面读取显式钉住行序（到唯一键） |
| `b306515c` | 裁定 D：V24–V28 加法迁移（**落在树里、未手工执行**） |
| `e4c832c1` | 裁定 A：算不出来不再装成 0（改 `null` + 显式不可用） |
| `439dcc73` | `D-R010-06` 每行回测标注净收益公式版本（V28 列不再是死列） |
| `2fe591b1` | `D-R010-05` 研究仪器成本口径改两级乘性 + 重算 + **只改数值**的勘误 |
| `23f97e69` | 裁定 B 组 1+3：换手率量纲后缀改名 + 策略回显去兼容层 |
| `57a9e40f` | 裁定 B 组 2：日级面/聚合面同名字段消歧（`nameCount` vs `observations`）+ 字段级单位 javadoc |
| `b0825aca` | 裁定 B 组 4：`long_short_spread`→`two_long_leg_spread`、中性面→`dollar_neutral_spread`，同批改全部消费方，删旧名兼容层 |

**当前门（`b0825aca` 后实测）**：Java **428** 绿 / Python **343** 绿 / `vue-tsc` exit 0。

**裁定 B 已完成**（4 组全交付，`Q2` 被 B 覆盖，覆盖关系登记在 batch3 §9.4）。

---

## §1 剩余任务清单

| ID | 任务 | 性质 | 需要你决定？ | 依赖 |
| --- | --- | --- | --- | --- |
| **T1** | 台账回填（含 batch3 §9.7.11 裁定 B 执行记录） | 纯文档 | 否 | 无，**建议最先做** |
| **T2** | 裁定 C 补差 5 项（`D-R006-02`、`D-R007-04/05`、`D-R007-08`、`D-R008-01`、`D-R010-02`） | 改实现 + 重算 + 勘误 | 否（已授权） | 无 |
| **T3** | `D-R010-01` 一行选择题（①维持乘性＝本条作废 / ②退回加法式） | 只有你能定 | **是** | 无 |
| **T4** | `B-R005-04`：2204 行派生特征**写入方写 0** ⟹ 重算 还是 标 `NULL` | 只有你能定 | **是** | 无 |
| **T5** | `B-R010-02`：`labels.py` + R002 仪器成本式对齐 + R002 勘误 | 改实现 + 重算 | 方向由 T3 决定 | **T3** |
| **T6** | `B-RENAME-01`：R004/R007/R008 三份**冻结仪器**重钉 + 勘误 | 研究记录 | **是**（补记改名 vs 作废该轴） | 无 |
| **T7** | 剩余 `PENDING_USER` 清单化（逐条一行选择题） | 文档 + 决策 | 是 | T1 |
| **T8** | 收口：确认 `origin/master`、门、计数一致 | 核对 | 否 | 全部 |

**运维侧（不属代码，须你或 DBA 执行）**：V24–V28 迁移**尚未 apply**；`modules/database/backfill_005_02_avg_amount20_null.sql`
已就位未执行（须待 V25 应用）。

---

## §2 公共约定（每项都适用，写进每轮提示词）

- **只允许操作** `E:\Project\quant-trading-system` 一个仓库；不得操作 `E:\Project` 下其他项目。
- 冻结口径不变：买入佣金 `0.0003`、卖出印花税 `0.0005`、单边滑点 `0.0002`；入场 = signal_date 下一交易日**开盘**；
  退出 = h-th 持有日**收盘**；`NOT_BUYABLE`/`SUSPENDED`/`NOT_SELLABLE`/`DELISTED` **不得删样本**；T+1；
  `rule_score` 是 0–100 截面百分位，**不是**上涨概率。
- 禁止：`git reset --hard`、`git checkout --` 丢弃修改、`git push --force`、建 PR、发版本、
  启用真实自动交易 / 下真实单 / 索取或生成真实交易凭证、读取真实凭证、**改冻结产物**（`result.json`/`run.log`/`config.json`/过往轮次报告正文）。
- 每轮：改完跑三门 → `git commit` → **普通 `git push`**（失败记 `PUSH_PENDING`，**不得** force）。
- 新测试**只增不减**，且必须证明「**改前失败、改后通过**」（`git stash` 或**变异测试**：改生产代码让守卫失败 → 撤销 → 恢复绿）。
- 口径/数值类改动：**只改数值，不改判定词**；改完**如实披露数字怎么变的**，禁止为了让数字变好而选口径。
- 门命令：
  - Java：`cd modules/backend-java && mvn -o -B test`
  - Python：`cd modules/compute-python && python -m pytest tests paper/tests -q`
  - 前端：`cd modules/web && npx vue-tsc --noEmit`
- **项目规则**：「已落地/未落地一律以**工作树**为准，不得照抄自述记录」；任何 delta 数字必须实跑复现后才可写入。

---

## §3 提示词

### T1 — 台账回填（建议第一个跑，不碰代码）

```
在 E:\Project\quant-trading-system（唯一可操作仓库）做一次纯文档的台账回填，不改任何代码与冻结产物。
先读 research/paper_loop/handoff_2026-09-15_remaining.md §0/§1 与
research/paper_loop/decisions/decision_batch3_2026-09-15.md §9.5/§9.7，然后：

1. 在 decision_batch3 追加 §9.7.11「裁定 B 改名」执行记录：4 组分别改了什么、
   3 个 commit（23f97e69 / 57a9e40f / b0825aca）、门（Java 428 / Python 343 / vue-tsc 0）、
   值中性论证（逆映射回旧名后生产文件与 HEAD 逐字节相同）、以及「旧名不可读」的两处守卫与变异测试证明。
2. §9.5 执行顺序表把第 3 行（裁定 B）与已完成的第 1/2/5 行状态改为实际状态。
3. research/paper_loop/decision_queue.md：已执行项的状态列改成 DONE + commit 号（A/B/D 与 §9.7 各项）。
4. 修掉两处过期断言：research/paper_loop/fix_manifest_R001_R015.md 第 145 行、
   research/paper_loop/progress.md 第 1814 行——「旧 strategy 兼容保留」已不成立（23f97e69 已删除旧键）。
5. progress.md §2 的 blocked_queue 计数 30 → 33（本批新增 B-R005-04 / B-R010-02 / B-RENAME-01），
   并在 .cursor/optimization-progress.md 与 README.md 里同步仍写旧字段名/「多空 spread」的地方。
6. 只改文档；改完 git commit（消息写清「台账回填，无代码与数值变化」）并普通 push。

约束与红线见 handoff §2。不要顺手改任何别的文件。
```

### T2a — `D-R006-02`：ICIR 分母改 `÷(T−1)`

```
在 E:\Project\quant-trading-system 执行裁定 C 的 D-R006-02（已由用户授权「改实现」）。
先读 handoff_2026-09-15_remaining.md §2、decision_batch3 §3 的 C-3 与 §9.3 表格第一行、
以及 decision_queue.md §15 的完整分析。

要做：主口径 ICIR 分母由总体标准差 ÷T 改为无偏样本标准差 ÷(T−1)，
并**同批**改 rank_ic_std 与 rank_ic_ir（两者共用同一分母，不能只改一个）。
预期数值方向（§9.3 已给）：std 变大、ICIR 绝对值变小（更保守）。**只改数值、不改判定词**。

必须做到：
- 同步改写 RANK_IC_STD_BASIS / 相关 note 的声明文本，使**声明与实现同构**（不得留「只声明现状」的旧句）；
- 新增算术守卫测试：断言 ÷(T−1) 的给定序列值（含 T=2 与无离散度边界），并证明「改前失败、改后通过」；
- 找出所有消费方（研究仪器 R006 等读该字段的地方）同批改完，编译器/解析器必须能报出遗漏；
- 若某轮冻结报告的数字因此变化：**不动机冻结产物**，改为在该轮 report 追加勘误附录（旧→新对照），
  或登记到 blocked_queue（若重算须用户决定）。
- 跑三门、commit、普通 push。

约束与红线见 handoff §2。
```

### T2b — `D-R007-04` + `D-R007-05`：HAC 滞后阶入参 + bootstrap 块长

```
在 E:\Project\quant-trading-system 执行裁定 C 的 D-R007-04 与 D-R007-05（已授权「改实现」）。
先读 handoff_2026-09-15_remaining.md §2、decision_batch3 §3 的 C-3 与 §9.3 表格第二行、
decision_queue.md §24 与 §25。

要做：
1. D-R007-04：neweyWestHacStd(List) **增加滞后阶入参**（改签名＝改契约），
   并在调用点传**持有期 h**（不再由 n 推 lag）；RANK_IC_EMBARGO_NOTE 的承诺随之与实现同构。
2. D-R007-05：blockBootstrapCi 的**有效块长**改为 max(1, min(h, n−1))（去掉 ⌊√n⌋ 上限，保留 n−1 防退化），
   javadoc 与实现对齐。
预期方向（§9.3）：区间**变宽**（更保守）。
**注意**：覆盖率**不得**作任何新声明（D-R007-09 要求另开预注册审计）。

必须做到：新增/改写守卫测试证明「改前失败、改后通过」（含 n<h 与 n=1 退化、h=1 边界）；
反射测签名（入参类型含 int）以钉住契约；跑三门、commit、普通 push。
若 R007 冻结结论受影响：不动机冻结产物，登记 blocked_queue §30 的 B-RENAME-01 同型条目或追加勘误附录。

约束与红线见 handoff §2。
```

### T2c — `D-R007-08`：区间覆盖率按**统计口径**修

```
在 E:\Project\quant-trading-system 推进裁定 C 的 D-R007-08（已授权）。
先读 handoff_2026-09-15_remaining.md §2、decision_batch3 §3 C-3 与 §9.3 表格第二行、
decision_queue.md §28（含 80 单元×2 臂的实测覆盖率与触发判据）。

要点：该轮实测名义 95% 区间覆盖率显著不足（BOOT 62/80、HAC 60/80 单元触发）。
裁定要求**按统计口径修**（块长与 h 对齐 / 窗口 / 依赖稳健估计量），**不是**只加前端说明。
T2b 已把块长与 h 对齐、滞后阶改由 h 决定 ⟹ 先**实跑 R007 仪器测量覆盖率是否改善**，
用实跑数字决定是否还需要进一步改估计量（**不得**先声明结论）。

硬性限制：**覆盖率的任何新声明都必须另开预注册审计（D-R007-09）**，本轮只允许如实报告实测值；
若实测仍不达标且需要换估计量（属口径变更），停下来向我报告并给出选项，**不要**自行换口径。
跑三门、commit、普通 push。

约束与红线见 handoff §2。
```

### T2d — `D-R008-01`：换手率分母改当日实际规模

```
在 E:\Project\quant-trading-system 执行裁定 C 的 D-R008-01（已授权「改实现」）。
先读 handoff_2026-09-15_remaining.md §2、decision_batch3 §3 C-3 与 §9.3 表格第三行、
decision_queue.md §30。

要做：短员日（当日候选数 < topN）的换手率分母由**请求参数 top_n** 改为**当日实际规模 min(prev, curr)**；
预期方向：短员日换手率**变大**（更保守）。**只改数值、不改判定词**。

必须做到：新增守卫测试覆盖三种形态（双侧短员、单侧短员、满员不变），并证明「改前失败、改后通过」；
检查该字段的所有消费方（载荷键、DTO、前端、导出）同批一致；
R008 的冻结 result.json / run.log **不动**（其重钉登记在 blocked_queue §30 / B-RENAME-01）；
跑三门、commit、普通 push。

约束与红线见 handoff §2。
```

### T2e — `D-R010-02`：`basis_note` 记法与实现同构

```
在 E:\Project\quant-trading-system 执行裁定 C 的 D-R010-02（已授权）。
先读 handoff_2026-09-15_remaining.md §2、decision_batch3 §3 C-1 第 2 条、
decision_queue.md §49。

要做：FactorResearchService 的 basis_note（原 :78-82）等**发射进载荷**的文本里仍是 ASCII 加法式记法，
而实现是两级乘性 ⟹ 把记法改为与实现**同构**（并顺带处理 batch3 §3 提到的 D-R010-03「公式注释符号唯一」）。
全仓同类「加法记法 + 乘性实现」同处命中 ×4，逐个改完（用同一记法写公式）。

必须做到：新增文本守卫测试断言载荷文本**不含**加法式特征串（例如 `-sell_tax` / `+commission` 的加性写法，
按实际文本定），并证明「改前失败、改后通过」；**不得**改任何数值；
跑三门、commit、普通 push（本条纯文本，理论上零数值影响 —— 但要用测试证明，不要只口头说）。

约束与红线见 handoff §2。
```

### T3 — `D-R010-01`：请你选一行（这是**提问**，不是执行）

```
不复述背景，直接回答：D-R010-01 选 ① 还是 ②（见 decision_batch3 §9.2）。
```

> 你若选 **①维持乘性** ⟹ 本条作废，无需动作，T5 按「对齐乘法式」执行。
> 你若选 **②退回加法式** ⟹ 4 个站点改加法式、重算全部历史净收益、并在所有报告登记「成本口径改为不含卖出佣金」，
> 且 **`2fe591b1`（`D-R010-05` 已改成乘性的仪器）要反向回退**——代价比看起来大，需单独一轮。

### T4 — `B-R005-04`：2204 行派生特征写 0

```
在 E:\Project\quant-trading-system 处理 B-R005-04（阻塞项，需先给方案不是先动手）。
先读 handoff_2026-09-15_remaining.md §2、research/paper_loop/blocked_queue.md §28 与汇总表该行、
以及 V25 迁移头注的 FOLLOW-UP。

先只做**取证与方案**（不改数据）：
1. 实测确认 2204 行 / 5 列（ret20/ret60/volatility20/max_drawdown60/avg_amount20）恒为 0 且底层 market_data 可用；
2. 给出两条路的代价对比：A 重算回填（用现有计算路径重算这些列，需幂等 + 可回滚 + 只读验证）；
   B 标 NULL（复用本批 V24–V28 的加法迁移形态，含回滚 SQL 与只读实测证据）；
3. 明确指出研究侧影响（`FeatureSchemaRegistry` 已登记这些列 ⟹ 0 会被当真实特征读走）。
把方案写成决策项文档交我选，**不要**自行执行数据变更、不要写生产数据。
```

### T5 — `B-R010-02`：成本式漏网 2 站点（**等 T3**）

```
在 E:\Project\quant-trading-system 处理 B-R010-02（先确认 T3 的选择，方向由它决定）。
先读 handoff_2026-09-15_remaining.md §2、blocked_queue.md §29 与汇总表该行、decision_queue.md 的 D-R010-05。

要做：把 modules/compute-python/research/labels.py:100-101 与
research/paper_loop/experiments/R002_universe/run_universe_bias.py:100-101,240-241
按与 D-R010-05 **同一裁定**对齐成本式（卖出腿不得漏 commission；买卖腿须同构）；
给 labels.py 补 Python 测试（断言买卖腿同构 + 真 0 边界，并证明「改前失败、改后通过」）；
R002 重算到独立目录（rerun_*）并写勘误（只改数值、不改判定词），冻结产物不动。
跑三门、commit、普通 push。

约束与红线见 handoff §2。若 T3 选了②，本条方向整体反转（这两处反倒「已符合」）⟹ 先停下问我。
```

### T6 — `B-RENAME-01`：三份冻结仪器重钉（**需你先定性质**）

```
在 E:\Project\quant-trading-system 处理 B-RENAME-01，但**先回答性质问题再动手**：
裁定 B 改名后，R008（读 strategy 回显键）、R007（engine_record_field_names 披露串）、
R004（static_scans 词表）三份**已冻结**仪器的 result.json / run.log 已无法由当前工作树逐字节复现。
勘误是把「改用新名」当作**补记**（追加附录、保留原结论），还是**作废该轴**（该轴判据重跑后重新判定）？
——请你选一个，我再执行。

选定后的执行口径：三份各跑到独立目录 rerun_renamed_20260915/（同 R001/R003 先例），
逐轴给旧→新对照与**是否翻转**（必须实跑复现，不得引用任何未经实跑的数字）；
R004 的 long_short_spread_retired 命中数作为「旧名已不可读」的机器证据；
三份冻结 result.json / run.log **保持不动**，勘误写进对应 rounds/*_report.md 附录；
任何轴翻转按协议处理（冲突时**数值→附录、判定词→预注册**，不得自我裁定判定词）。
跑三门、commit、普通 push。
```

### T7 — 剩余 `PENDING_USER` 清单化

```
在 E:\Project\quant-trading-system 把 research/paper_loop/decision_queue.md 里**尚未处置**的
PENDING_USER 项整理成一份大白话决策清单（原始 grep 命中 27 处，需按条目去重）：

对每一条输出四样东西，一行一条：
1. 一句话说清「现在发生了什么」（不用专业术语）；
2. 代价是什么（不改会怎样 / 改了会怎样，含数值方向）；
3. 一行选择题（①/②…），并标出你**推荐**哪个及理由；
4. 它阻塞了哪些后续任务（关联 T1–T8）。

只写文档、不改代码；写完 commit 并普通 push，然后在回复里把清单直接列给我。
```

### T8 — 收口核对

```
在 E:\Project\quant-trading-system 做一次收口核对（只读为主）：
1. `git status` 干净、`git log --oneline -1 origin/master` == 本地 HEAD；
2. 三门各跑一次并记录真实计数（Java / Python / vue-tsc）；
3. 计数一致性：blocked_queue 表内行数（awk 实测 33）与 progress.md §2 计数一致；
   decision_queue 状态列无「已做完但仍标 PENDING」的条目；
4. 红线条目逐条复核（未 force push、未动冻结产物、未删样本、未操作仓库外目录）；
5. 把结论写进 research/paper_loop/progress.md，commit + 普通 push。
```

---

## §4 一句话总结

裁定 A / B / D 与 `D-R010-05`、`D-R010-06`、`D-R011-02`、`D-R014-02` **已交付并推送**；
剩下的是 **T1 台账回填**（先做，纯文档）、**T2 裁定 C 补差 5 项**（改实现，已授权），
以及 **4 个必须你拍板的选择**（T3 `D-R010-01` 乘法/加法、T4 2204 行重算/NULL、
T5 的方向依赖 T3、T6 三份仪器勘误的性质）。
