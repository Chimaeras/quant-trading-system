# R010 报告：成本后净收益「声明—实现」一致性审计

- **轮次**：R010
- **baseline commit**：`cd0c4d44`（= 本轮 review + prereg 冻结提交）
- **预注册**：[`R010_prereg.md`](R010_prereg.md)（**先于**任何实验代码落盘，冻结后一字未改）
- **判据来源**：[`R010_review.md`](R010_review.md)（来源级别 **C**，仅作判据来源，**不**作改动依据）
- **实验目录**：`research/paper_loop/experiments/R010_net_return_audit/`
- **总判定**：**`REJECTED`** ｜ **`h0_alive = false`** ｜ 预期偏差 **0 条** ｜ 金丝雀 **19/19 PASS**
- **生产/测试/前端代码改动**：**0**（`git status` 仅新增本实验目录）

---

## 0. 摘要（先给结论）

**H0（项目对成本口径的对外声明与实现一致）被杀死。**

三条承重条件全部成立，且**互不重复**：

| 承重 | 内容 | 实测判据 |
|---|---|---|
| **K1** | 声明侧公式与实现侧公式**不同** | `f_impl − f_doc = −0.00029984`，主项恰为 `−c`（`= −3.00 bp of E`），归属份额 `1.00053` |
| **K2** | `ResearchConvention.java:88` 的**归属声明**不成立 | javadoc 卖出因子逐字 `(1 - sell_tax - slippage)`（**不含佣金**）vs 4 个站点 4/4 含佣金 |
| **K3** | `labelDefinition()` 字符串**不描述**它所伴随的数值 | 发射点 3 个，其中 2 个同文件内含乘性实现，第 3 个（`ProbabilityCalibrationService`）的样本经 `LabelFactoryService` 产出 |

另有两条与承重条件**独立**成立、但按短路规则未改变总判定的定性条件：`K4`（`|Δbp| = 3.2973 > TH_BP = 1.0` ⟹ **错标**而非舍入）、`K5`（持久化值 ≠ 计算值）。`K7` 按设计触发（`V13/V16/V18` 三个 `REGISTERED_ONLY`），是**兜底地板值**，**不是**新发现。

**这轮没有一个字与论文的研究结论有关。** 被审计对象是**项目自己的契约主张**（`ResearchConvention` javadoc + `labelDefinition()` 字符串 + 两份 docs + 前端提示），论文只提供「两项成本应如何合成」的**做法种类**判据（`L` 档，只支持做法种类，**不支持任何阈值**）。`REJECTED` **不得**读成「项目违背了文献」。

---

## 1. 判据三档与本文档的措辞约定

| 档 | 含义 | 本轮实例 |
|---|---|---|
| **L** | 文献明示（**只**支持做法种类，**不**支持任何阈值） | S1/S2/S3：成本施加在价格上、佣金两侧、印花税仅卖出、乘性优于加性 |
| **A** | 算术推导（由冻结常量按冻结表达式树推出，可复算到任意位） | `Δbp`、A/B 分解、符号条件、`|Δbp| − TH_BP` |
| **P** | 项目自建（含**一切**数值阈值） | `TOL=1e-15`、`TOL_BP=1e-12`、`TH_BP=1.0`、`PRICE_GRID`、走查口径 |

**「实测」的定义（本轮严格执行）**：判定量必须来自 ①生产字节码的反射调用返回值，或 ②源码文本计数，或 ③`Decimal prec=60` 复算。
**不得**依赖探针自报的 `ok_*` 布尔值（报告 Q13）。`GOLD`（探针内独立 BigDecimal 实现）只作**对照**，
按 prereg §5.2「**对照值不充当被审计量**」。

---

## 2. 冻结算术复算（问题 1）

`make_config.py` 以 `decimal` `prec=60`、**按 prereg §3 的表达式顺序**复算全部冻结值：**24/24 全部匹配**，其中 18 条**逐字符相等**，6 条差在字面串末位所代表量的 1/2 以内（prereg 该表按有效位/小数位**四舍五入**呈现，见附录 A 勘误 A-1）。

```
doc_entry  = 10.0050000000                       impl_entry = 10.00500060000000
doc_exit   = 10.9923000000                       impl_exit  = 10.98900176000000
net_doc    = 0.09868065967016491754122938530734632683658170914542728635682
net_impl   = 0.09835093463162810804828937241642944029408653908526502237291
Δnet       = -0.00032972503853680949294001289091688654249517006016226398391
Δbp        = -3.29725038536809492940012890916886542495170060162263983910000
rel        = -0.00334133395174797830129122756368221397514856320462215249571519
M          = 0.999699890012958090756541867582432843912769468041090267627428
f_doc=0.9993   f_impl=0.99900016   f_diff=-0.00029984 = -c + s(c+t)
g_doc=1.0005   g_impl=1.00050006   g_diff=+6e-8      = +s·c
A bp = -3.00050035024517162013409386570599419593715600920644451116000   （退出侧：遗漏卖出佣金）
B bp = -0.000599700113961012656011235021814991195502776881392690770000  （入场侧：乘性/加性交叉项）
A+B bp            = -3.00110005035913263279010510072780918713265878608783720193000
(1+A)(1+B)-1 bp   = -3.00109987041909243458132417567156087230531958909732372571000
M-1 bp            = -3.00109987041909243458132417567156087230531958909732372572…（prereg 截断到 55sf）
符号条件 st(1+2c+2s) < c(1+c−s−cs):  lhs=1.00100E-7 < rhs=0.000300029982,  rhs/lhs = 2997.302517482517…
```

**答复问题 2**：是。`Δnet` 的分解与 §3.2b 冻结值吻合：退出侧 `−3.0005 bp` + 入场侧 `−0.0006 bp`，
对 `1+net` 而言合起来是 `M − 1 = −3.0011 bp`；而 `A+B` 与 `(1+A)(1+B)−1` 相差
`−1.7994e-11`（**绝对量**，≈ `1.8e-7 bp`，见附录 A 勘误 A-2）。

---

## 3. 逐轴判定（19/19，判定词全部由**实测**决定）

| 变体 | 轴 | 判定词 | 决定量（实测） |
|---|---|---|---|
| V01 | A1 | `NOT_VIOLATED` | `residual_max=2.22e-16`；`g_impl_measured=1.00050006`（冻结 `1.00050006`）；`f_impl_measured_h2=0.9990001600000002`；`price_source=market_price_raw`（h2/h3 皆然） |
| V02 | A1 | `NOT_VIOLATED` | `f_obs−1=−0.00099984`，`g_obs−1=+0.00050006`，二者皆显著 `≠1` ⟹ 排除了「成本从收益率里减」的反事实 |
| **V03** | A2 | **`VIOLATED`** | `f_diff=−0.00029984`；恒等式残差 `0E-8`；`−c` 的归属份额 `1.00053` |
| **V04** | A2 | **`VIOLATED`** | `g_diff=+6e-8`（正）vs `f_diff=−2.9984e-4`（负）⟹ **符号相反**，加性/乘性不是统一比例偏移 |
| **V05** | A2 | **`CONTRACT_MISLABEL`** | `\|Δbp\|=3.297250385368094929400128909169…`；`margin_bp = 2.297250385368094929400128909169… > 0` |
| V06 | A3 | `NOT_VIOLATED` | `attr_phrase_count=1`（归属短语确实存在） |
| **V07** | A3 | **`VIOLATED`** | 声明：`declared_entry_additive_steps=1`、`declared_exit_additive_steps=1`、javadoc 卖出因子逐字不含佣金（`=1`）；实现：4 站点 `entry_mult=2/2/2/2`、`exit_mult=2/2/2/2`、`exit_commission_step=1/1/1/1` ⟹ `attribution_holds=0` |
| **V08** | A4 | **`VIOLATED`** | 发射点 `=3`（`FactorResearchService:220`/`LabelFactoryService:707`/`ProbabilityCalibrationService:270`）；同文件含乘性实现的发射点文件 `=2`；`pcs_samples_from_lfs=1` |
| V09 | A4 | `CONVENTION_DIVERGENCE` | `docs/API文档.md:377` 加法式子句 `=1`；`ScoringBacktest.vue:67`「双边佣金」`=1` ⟹ 两套惯例相反，其中一套面向用户 |
| V10 | A5 | `NOT_VIOLATED` | 反解实测 `entry_idx=1`、`fwd_idx_h2=3`、`fwd_idx_h3=4` ⟹ `h2−entry=2`、`h3−entry=3`，与 `holding_period_semantics` 文本一致；网格二义性余量 `0.5/0.5` |
| V11 | A5 | `CONVENTION_DIVERGENCE` | 自由文本侧 `free_text_origin_declared=0`（`ResearchConvention:18` 与 `研究口径规范.md:44` 都**未**定原点）；结构化侧 `structured_origin_declared=1` |
| V12 | A6 | `NOT_VIOLATED` | 4 个来源 × 3 个费率：`rc_*=0.0003/0.0005/0.0002`、`req_*_default` 同；兜底字面量 `3` 与 `3`；`mismatches=[]` |
| V13 | A6 | `REGISTERED_ONLY` | 常量被钉住 `=3`；唯一触碰 `labelDefinition()` 的测试体 `=1`，其中**算术断言 `=0`**；测试树公式算术字面量 `=0` |
| V14 | A7 | `NOT_VIOLATED` | 7 格全部实测：`null/NaN/+Inf → 0.0003`，`−1.0 → 0.0`，`0.06 → 0.05`，`0.0001 → 0.0001`，`0.05 → 0.05` |
| **V15** | A7 | **`VIOLATED`** | ①`boundedRate(0.06)=0.05`；②持久化区间 `boundedRate` 计数 `=0`；③`BigDecimal.valueOf(0.06)=0.06≠0.05`；④`BigDecimal.valueOf(NaN)` 抛 `NumberFormatException` 且 catch 清空 `resultId`（`=1`）；⑤载荷 `assumptions_has_clamp_marker=0` 而 `commission_rate=0.05` |
| V16 | A8 | `REGISTERED_ONLY` | `emptyNetIcBlock` 键集 `14`（无 `monotonicity`）vs `buildNetIcBlock` `15`（有）；**同 R009 A4c 的既存事实，本轮只登记跨轮可见** |
| V17 | A9 | `NOT_VIOLATED` | 9 格：`negative_count=9/9`；`distinct_delta_bp=7/7`；`min_margin=2397.98565742146910718567660458796259291823195587882663256334`（`c×0.8`），`margin−1=2396.985657…>0` |
| V18 | A10 | `REGISTERED_ONLY` | `run_reversal_ic.py` 含加法式出场因子 `=1` 且 `FROZEN_TOTAL=0`；`run_cost_breakeven.py` 含 `FROZEN_TOTAL=5` 且加法式 `=0` ⟹ 两个研究仪器都走**加法式文档口径**，净收益比生产口径**偏乐观** |
| V19 | A11 | `NOT_VIOLATED` | 两次独立构造的运行：`det_len_1=det_len_2=7422`、`det_first_diff_index=−1`（逐字符相同）、`det_interprocess_ok=1`、`grid_mismatch_count=0`、`g3=1` |

**答复问题 4**：`ResearchConvention.java:88` 的归属声明**实测不成立**（V06 证明短语存在，V07 证明其内容为假）。
**答复问题 5**：`labelDefinition()` 发射进 **3** 个载荷；其伴随数值用的是**乘性含卖出佣金**口径（`(1−s)(1−c−t)`）；
前端**判不出**真口径——`ScoringBacktest.vue:67` 写「双边佣金」虽与实现同向，但它**没有**给出公式，而唯一的「单一真相源」表（`docs/API文档.md:377`）给的是**加法式**。
**答复问题 6**：`executionAssumptions` 的费率是**钳位后**的值（`commission_rate=0.05`），`boundedRate` 的钳位**在载荷里不可见**（无 `clamped` 标记）；而**持久化路径取的是原始请求值**（`0.06`）。
**答复问题 7**：实测 `fwdIdx − entryIdx = 2`（h=2）、`= 3`（h=3）；与 `holding_period_semantics` 文本**一致**，与自由文本**不可判**（它没定原点）。
**答复问题 8**：**否**。`ResearchConventionTest:54-57` **只做子串断言**（`exit_price_h`/`min_net_return`/`h in {2,3}`），公式算术在测试层**零守卫**（`rctest_labeldef_has_arith=0`、全测试树公式算术字面量 `C16_src=0`）。
**答复问题 9**：**不改变**。9 格 `Δbp` 全负（9/9），符号条件全成立（9/9），去重后 7 个不同值。

---

## 4. 预期 vs 实测（0 条偏差）

19 条预注册预期**全部命中**，`deviations = []`。

> **必须照写**：19 条里 **15 条是描述性**的（判定词在读码/算术阶段即已确定），只有 `V10`（反解索引）、`V17`（9 格）、`V19`（两次运行）是**预测性**的。
> **不得**把「预期全中」包装成任何预测能力（R006 教训 33 / R009 同类处理）。

---

## 5. 杀死条件与短路（问题 10 的一部分）

```
K1 = V03 ∨ V04 VIOLATED            → true    （承重）
K2 = V07 VIOLATED                  → true    （承重）
K3 = V08 VIOLATED                  → true    （承重）
K4 = V05 CONTRACT_MISLABEL         → true
K5 = V15 VIOLATED                  → true
K6 = V12 CONTRACT_AMBIGUOUS        → false
K7 = 任一轴 ∈ {REGISTERED_ONLY, NOT_REACHABLE} → true （V13/V16/V18，地板值）
K8 = 行和/config/grid_mismatch     → false （行和=19，frozen_ok=true，grid_mismatch=0）
K9 = 金丝雀/反向锚点/G3/依赖/表外词 → false （canary_fail=[]，4 锚点全真，g3=1，表外词=[]）
K10 = 确定性门                     → false （逐字符相同）

短路：K8∨K9∨K10 全假 → K1∨K2∨K3 真 → **total = REJECTED**，h0_alive = ¬K1∧¬K2∧¬K3 = false
```

---

## 6. 强制披露（prereg §6.3 冻结措辞，逐条照写）

1. **`K7` 的期望触发概率 ≈ 1**：`V13`（`REGISTERED_ONLY`）、`V16`、`V18` 按设计即触发。
   `REGISTERED_ONLY` 是**兜底地板值**，**不得**被叙述成「又发现一条缺陷」。
2. `total = REJECTED` 的**承重部分只有 `K1/K2/K3`**；`K4/K5` 虽亦为真，按短路规则**未**改变总判定——它们的作用是把缺陷**定性**为「契约文本错标」与「持久化/计算不一致」。
3. `K1` 与 `K2`/`K3` **不是**同一件事的重复：`K1` 是**文本公式**与**实现**不同；`K2` 是**归属声明**（适用对象）不成立；`K3` 是**载荷自相矛盾**。三条独立成立。
4. 本轮**没有**任何一条断言依赖论文的**研究结论**；三条 `L` 档判据只用于 `V01/V02` 的**做法种类**比对，**且**这两条的判定是 `NOT_VIOLATED`（**对项目有利**）。
   **不得**把 `REJECTED` 读成「项目违背了文献」——它与文献无关。

---

## 7. 区间下界条款：**逐条不适用**（问题 11 的前置）

本轮 **19 条断言无一条**是覆盖率/命中率/频率型，故 **Clopper–Pearson 区间下界不适用**（逐条理由见 prereg §6.5 表）。
唯一的两个 `≥` 处按 prereg 的**等价变形**处理，且**报告必须写出余量**（不是只写「通过」）：

```
V05:  |Δbp| − TH_BP = 3.297250385368094929400128909169… − 1.0
                     = 2.297250385368094929400128909169… > 0      （余量 2.2973 bp）

V17:  min_over_grid(rhs/lhs) − 1 = 2397.98565742146910718567660458796259291823195587882663256334 − 1
                                 = 2396.98565742146910718567660458796259291823195587882663256334 > 0
                                                                 （余量 2396.99×）
```

---

## 8. 金丝雀与仪器可信性（19 对，全 PASS）

- **19/19 对 PASS**（`canary_fail = []`）。
- **4 条反向锚点全为真**：`sentinel_impl_matches_prod=1`、`sentinel_doc_recomputable=1`、`sentinel_existing_test_input_ok=1`（`ResearchConventionTest` 7 tests / 0 fail）、`sentinel_clamp_visible=1`。
- **G3 门**：探针 `code_source = E:/Project/quant-trading-system/modules/backend-java/target/classes` ⟹ `g3=1`
  （证明判据跑在**仓库的生产字节码**上，而非探针私有副本上）。运行结束已从 `target/classes` 移除探针字节码 4 个文件。
- **确定性门**：两次**独立构造并运行**的探针（各 7422 字符规范序列化）**逐字符相同**，`det_first_diff_index=−1`。
- **诚实披露（实现层选择）**：`CLN` 把 checker **实际读取的全部键**写成现实值。
  这让金丝雀成为 checker 逻辑的**纯单元测试**（不会因别处真实违规把结论掩成 `INCONCLUSIVE`），
  代价是 `CLN` 不再检验「真实数据恰好如此」。真实值另由 §3 的实测列与 `result.json` 的 `evidence` 全程留痕。

---

## 9. 附加观测（**只记录，不参与任何判定**）

| 编号 | 观测 | 计数 | 处置 |
|---|---|---|---|
| **X01** | 加法式**记法**文本与乘性代码**同处一文件**：入场写法 `×(1+佣金+滑点)` ×5（`FactorResearchService` 4 + `LabelSnapshot` 1）、出场写法 `×(1-佣金-印花税-滑点)` ×4（`FactorResearchService`）。其中 `FactorResearchService:80` 的 `RANK_IC_NET_BASIS_NOTE` **被发射进载荷** `basis_note` | 5 / 4 | 登记为 `D-R010-02` |
| **X02** | `FactorResearchService:352` 的**第三种**加法记法（ASCII）：`rawOpen[t+1]×(1+c+s) 入场 → rawClose[t+1+h]×(1-s-tax-s) 退出`；其中出场式把 `s` 用了**两次**（字面真值 `0.9991` vs 代码 `0.99900016`） | 2 | 登记为 `D-R010-03` |
| **X03** | **新事实（本轮首次发现）**：`ScoringBacktestService` 内**存在两处算术相同的入场费站点** —— `:275-276` **内联**在 `perTradeDate` 主回测循环里（活跃路径，紧接着 `:282-284` 调 `computePeriodReturn`），`:969-970` 在抽出的 `resolveEntryContext` 助手里（`:337` 被调用）。两处均为乘性（`sbs_inline_entry_multiplicative=1`）；探针驱动的是**助手**（V01 措辞本就点名 `resolveEntryContext`，判据不变） | 站点 2 处 | 登记为 `D-R010-04`；**声明侧未提及「入场费公式有两份副本」** |

**四文件乘性净收益合成站点总数 = 11**（入场 6 + 出场 5）：`FactorResearchService` 4、`LabelFactoryService` 4、`ScoringBacktestService` 3。

---

## 10. 可达性表、前视偏差、幸存者偏差（协议强制披露）

| 项 | 本轮情况 |
|---|---|
| **变体数 N** | **19**（11 轴；轴计数 A1=2,A2=3,A3=2,A4=2,A5=2,A6=2,A7=2,A8=1,A9=1,A10=1,A11=1，**和=19**，`K8` 自检通过） |
| **关键参数 ±20% 敏感性** | 9 格（`c/t/s` 各 `×0.8/×1.0/×1.2`）**全部实测**：`Δbp<0` 计 **9/9**；符号条件成立 **9/9**，最小余量 **2397.985657…×**；去重后不同 `Δbp` **7** 个。**±20% 不改变存在性与符号** |
| **前视偏差** | **本轮不测量任何策略**，故不引入也不消除前视偏差。审计对象是公式与声明；`PRICE_GRID` 是合成网格；`fwdIdx` 由生产返回的净收益**反解**得到（V10），不依赖任何信号的预测能力 |
| **幸存者偏差** | **不适用**：单一合成标的（`600000|SH`），无标的池、无退市/停牌筛选、无股票池选择环节；`qfqMap` 留空 ⟹ 全部走 `market_price_raw` |
| **执行状态样本处理** | 本轮不涉及 `NOT_BUYABLE/SUSPENDED/NOT_SELLABLE/DELISTED`：网格 `volume=1000000>0`，入场 `+5.0%`、退出 `+8.33%/+7.69%`（均上涨）⟹ **不可能**触发跌停门，也**不做**任何样本删除 |
| **`rule_score` 口径** | 本轮**未**使用 `rule_score`，也**未**把它当上涨概率 |
| **论文简化假设** | 论文的 `ρ = 1‰`、`ε ≤ 1‰` **未**被用来评价项目费率（prereg §7 负面清单）；文献只提供**做法种类**判据（L 档，不支持任何阈值） |
| **可否外推** | **不可**。`Δbp = 3.29725` **不得**说成「策略真实收益比报告低 3.3bp」——本轮**没有**测量任何策略的收益 |

---

## 11. 负面清单遵守确认（prereg §7，12 条）

**全部遵守**，逐条核对：

1. **未**修改任何生产/测试/前端代码 —— `git status` 仅 `?? research/paper_loop/experiments/R010_net_return_audit/`，
   `ResearchConvention.java`、`docs/研究口径规范.md`、`docs/API文档.md`、`ScoringBacktest.vue`、四个 Service 的净收入公式**一律未动**。
2. **未**给 `ResearchConventionTest` 加算术守卫（那同样属 §7 保留权限）。
3. **未**回写 `R001_prereg.md` / `R003_prereg.md`（V18 的追溯发现只登记为 `D-R010-05`）。
4. **未**把 `Δbp` 说成策略收益差。
5. **未**把冻结价对 `(10.000000, 11.000000)` 当成某只股票的真实价格。
6. **未**用论文的 `ρ`/`ε` 评价项目费率。
7. `V01/V02` 的 `NOT_VIOLATED` **未**被淡化成「顺便一提」——它们在 §0 与 §3 都是 `K1` 的**对照面**。
8. `V16` **未**被当作新发现（明标「同 R009 A4c 的既存事实」）。
9. `V18` 的结果**未**被用来重算 R001/R003 的任何数字。
10. **未**据 `V15` 建议改 DB 结构或加唯一约束（登记为 `D-R010-06`）。
11. **未**触发任何真实交易接口、**未**读任何真实凭证。
12. **未**做影子→正式提升（本轮无新因子，见 §14）。

---

## 12. 逐次运行披露（问题 12）—— `run.log` append-only，5 次运行**全部保留**

`run.log` 每次运行**追加**一段（含时刻、探针 stdout **全量**、退出码、失败原因），**未**裁剪、删除或覆盖任何先前运行。
本轮共 **5** 次运行，其中 **3** 次失败，**全部是仪器缺陷**，**没有一次是对被审计对象的发现**：

| # | 结局 | 失败原因（根因） | 修复 |
|---|---|---|---|
| 1 | **崩溃**（无 `运行结束` 行） | `FileNotFoundError [WinError 2]`：Windows 下 `mvn` 实为 `mvn.cmd`，`subprocess` 列表形式（无 shell）不解析 `PATHEXT` | `maven_test()` 改用 `shutil.which("mvn.cmd")` 显式解析 |
| 2 | `INCONCLUSIVE` | ①探针把 `FactorResearchEngine` 写成 `com.quant.service.*`，真实包是 `com.quant.service.backtest`（`ClassNotFoundException` ×2）；②探针编译到私有 `probe/out` 而非仓库 `target/classes` ⟹ **G3 门 = 0**（违反 prereg §5.2 的仪器设计） | 修正 FQN；`javac -d <repo>/modules/backend-java/target/classes`，运行后清理探针字节码 |
| 3 | `INCONCLUSIVE` | 3 对金丝雀 FAIL，且**三种根因各不相同**：<br>①`V07` 的判定式含**常量项**（`6e-8`/`2.9984e-4` 由冻结常量算得）⟹ 对注入免疫，`INJ` 无法翻转判定；<br>②`V14` 的 `CLN` 把 `obs_null` 写成字符串 `"null"` 而非实测值 `0.0003` ⟹ 被 `fnum` 归一为 `None`；<br>③`V17` 的 `CLN` 把 9 格写成**同一个**值 ⟹ `distinct=1≠7` | ①重写 `V07`：判定量**全部**改为文本测量（4 站点 3 类计数 + 2 个声明侧 + javadoc 卖出因子），常量残差降为**旁证**（标注 `numeric_supporting_is_decider=0`）；②`CLN` 改用实测值；③`CLN` 逐格写各自实测值 |
| 4 | `REJECTED` | — （重跑前又发现 1 条**自设计数错误**） | 自设咨询计数 `C24` 期望 `8` 与实测 `9` 不符：我的手工算术漏了「FRS 的 `* (1 - …)` 在**续行**上」这一行折行效应。`C24` 改为口径明确的 `double (entryCost\|exitProceeds) = ` 站点计数，期望 `11`，实测 `11`（F/R/L/S = 4/4/3） |
| 5 | `REJECTED` | — （最终确认运行） | 全部门通过：`g3=1`、`grid_mismatch=0`、`probe_errors=[]`、`canary_fail=[]`、`deviations=[]` |

**答复问题 12**：`run.log` 是 append-only，含全部失败运行；失败 **3** 次，时刻与原因见上表与 `run.log` 段落边界（行 3 / 8→492 / 495→995 / 998→1503 / 1506→2011）。

---

## 13. 十四个问题逐条作答（prereg §10）

| # | 问题 | 答复 |
|---|---|---|
| 1 | §3.2 的 17 个冻结值是否逐位复现？ | **是**。24/24 匹配（含 `symbol_*` 与 OAT），18 条逐字符相等，6 条按字面串末位半量比较；见 §2 与附录 A-1 |
| 2 | `Δnet` 分解是否与 §3.2b 吻合？ | **是**。`A=−3.0005 bp`（退出侧遗漏佣金）+`B=−0.0006 bp`（入场侧交叉项）= `A+B=−3.0011 bp`；对 `1+net` 而言为 `M−1=−3.0011 bp` |
| 3 | 符号条件是否在 9 格恒成立？最小余量？ | **是，9/9**；最小余量 **2397.985657…×**（`c×0.8` 格） |
| 4 | `ResearchConvention.java:88` 的归属声明实测是否成立？ | **不成立**（V06 存在=1；V07 内容为假，`attribution_holds=0`） |
| 5 | `labelDefinition()` 发射进几个载荷？伴随数值用哪个口径？前端能否判出真口径？ | **3** 个载荷；伴随数值为**乘性含卖出佣金** `(1−s)(1−c−t)`；前端**判不出**（唯一「单一真相源」表给的是加法式） |
| 6 | `executionAssumptions` 的费率是请求值还是钳位值？钳位是否可见？ | **钳位值**（`commission_rate=0.05`）；**不可见**（无 clamp 标记，`assumptions_has_clamp_marker=0`）；持久化路径反过来用**原始请求值** |
| 7 | 实测 `fwdIdx − entryIdx`？与两套文本是否一致？ | **2**（h=2）、**3**（h=3）；与 `holding_period_semantics` **一致**，与自由文本**不可判** |
| 8 | `ResearchConventionTest:53-57` 是否钉住公式算术？ | **否**，只做子串断言；公式算术在测试层**零守卫** |
| 9 | ±20% 网格是否改变存在性与符号？ | **不改变**（9/9 负、9/9 符号成立、7 个不同值） |
| 10 | 本轮是否出现「为了产出而放水」的迹象？ | **没有**。反证有三：①3 次仪器失败**如实保留**在 append-only 日志并逐次披露；②`V13/V16/V18` 按设计触发 `REGISTERED_ONLY`，明写为**地板值**、**不得**当缺陷；③`V01/V02`（对项目**有利**）与 `V17`（披露轴）都按预期给出 `NOT_VIOLATED`，没有为了凑「有缺陷」而把它们改判 |
| 11 | `V15` 的推导链是否完整披露（含「非端到端」边界）？ | **是**。载荷 `honest_boundary` 字段逐字写明：**不**端到端观测 `persistRun`（需 Spring 容器与 DB），结论由「1 次反射实测 + 1 次源码文本抽取 + 1 次 JDK 行为实测」**推导**得出 |
| 12 | `run.log` 是否 append-only 且含全部失败运行？ | **是**，见 §12（5 次运行、3 次失败、含 1 次硬崩溃） |
| 13 | 5 条承重结论是否有任何一条依赖探针自报布尔值？ | **没有**。`V03/V04` 由探针 `GOLD` **数值** + 冻结常量复算 + 恒等式残差 `0E-8` 决定；`V05` 由 `Decimal` 复算的 `\|Δbp\|` 与阈值比较决定；`V07` 由**源码文本计数**决定；`V08` 由**发射点计数**决定。探针自报的 `ok_*` 布尔值**一个都没读** |
| 14 | `grid_mismatch_count` 是否为 0？行号冻结值与实测值是否一致？ | `grid_mismatch_count = 0`（8/8 逐项一致）。行号：**26** 个锚点中 **13 个 delta=0**，**13 个非零**（`−9…+7`），**逐条记入附录 A**，按 prereg §5.5 第 4 项**不**改判定 |

---

## 14. 影子因子登记（协议强制）

**本轮为只读审计轮，未产生任何新因子/新信号** ⟹ `shadow_registry.md` 追加 **R010 空轮**条目（无 `SHADOW-*`）。
本轮**未**做任何影子→正式提升（该动作按协议 §七/§8 保留给用户）。

---

## 附录 A：行号校准表（26 个锚点）与勘误

### A.1 行号校准（冻结区间起点 vs 文本锚点实测算得行号）

| 区间 | 冻结 | 实测 | delta | 说明 |
|---|---|---|---|---|
| rc_exit_freetext | 18 | 18 | 0 | |
| rc_javadoc_formula | 83 | 83 | 0 | |
| rc_labeldef | 95 | 95 | 0 | |
| frs_note | 78 | 78 | 0 | |
| frs_emission | 218 | 220 | +2 | 锚点是**发射语句**本身，落在冻结窗口（218-222）第 3 行 |
| frs_site_1 | 360 | 352 | −8 | 锚点是站点**上方的成本口径注释**，站点块起点在其后 8 行 |
| frs_site_2 | 727 | 718 | −9 | 同上（注释 → 站点） |
| lfs_site_1 | 337 | 340 | +3 | 锚点是 `exitProceeds` 行（站点块第 4 行） |
| lfs_site_2 | 439 | 442 | +3 | 同上 |
| lfs_emission | 705 | 707 | +2 | 同 `frs_emission` |
| pcs_emission | 268 | 270 | +2 | 同 |
| sbs_defaults | 80 | 80 | 0 | |
| sbs_bounded | 671 | 671 | 0 | |
| sbs_persist_rates | 507 | 507 | 0 | |
| sbs_catch | 520 | 523 | +3 | 锚点是 `response.setResultId(null);`（catch 块内） |
| sbs_holding_semantics | 687 | 687 | 0 | |
| sbs_inline_entry | 275 | 276 | +1 | **X03**：内联站点（无起始行提示时会与助手站点混淆） |
| sbs_impl_entry | 969 | 970 | +1 | 助手站点（**必须**用起始行提示 950 才能与 :276 区分） |
| sbs_impl_exit | 1013 | 1014 | +1 | |
| rctest_constants | 20 | 22 | +2 | 锚点是断言行而非方法签名行 |
| rctest_labeldef | 54 | 54 | 0 | |
| doc_conv_formula | 38 | 38 | 0 | |
| doc_conv_freetext | 43 | 44 | +1 | |
| doc_api_clause | 377 | 377 | 0 | |
| vue_hint | 60 | 67 | +7 | 锚点是「双边佣金」所在提示行，冻结区间起点是卡片容器行 |
| sbq_fields | 28 | 29 | +1 | |

**处置**：按 prereg §5.5 第 4 项，以上非零 delta **只记附录**（它们全部是「锚点落在冻结窗口内部的更窄行」，
不是漂移），**不**改任何判定、**不**回写 prereg。唯一的**真**歧义（`entryCost` 在 `ScoringBacktestService` 出现两次）
已用「起始行提示」消解，并把**两个站点都**纳入校准表（`sbs_inline_entry` / `sbs_impl_entry`）。

### A.2 勘误（**只写在本报告**，**不**回写冻结的 prereg）

| 编号 | prereg 原文 | 勘误 |
|---|---|---|
| **A-1** | §3.2 表中 `Δbp`/`rel`/`M` 等值按有效位呈现（如 `Δbp` 30 位有效数字、OAT 表余量 6 位小数） | 这些行是**四舍五入后的展示值**，不是我算错：`prec=60` 复算的精确值见 §2。判定按「字面串末位所代表量的 1/2」容差比较，24/24 通过；其中 18 条**逐字符相等** |
| **A-2** | §3.2b 末行：「`A+B` 与 `(1+A)(1+B)−1` 相差 `1.8e-11 bp`」 | **单位错 10⁴**。高精度复算：`A+B − [(1+A)(1+B)−1] = −1.7994e-11`（**绝对量**），即 `≈ 1.8e-7 bp`。该量只用于说明交叉项的二阶性，**不参与**任何判定 |
| **A-3** | §4 轴 A1 的 `V01` 措辞点名 `resolveEntryContext` | 实测发现 `ScoringBacktestService` 内**入場费站点有两份**（`:275-276` 内联 + `:969-970` 助手），算术相同且均为乘性。V01 驱动的是**助手**（措辞本就点名它），故**判据不变**；此项作为**新事实 X03** 登记（`D-R010-04`） |
| **A-4** | 预注册未规定 `SRC_RANGES` 的具体行区间（那是实现层选择） | 实现层发现 2 处区间须修正：①`sbs_holding_semantics` 必须覆盖**键名行 + 值行**（否则 V10 的文本判据读空）；②`entryCost` 锚点须带起始行提示。二者都是**实现层**修正，**不**触碰任何冻结判据 |

---

## 附录 B：计数与区间测量的观测值（19 条咨询计数 + 15 条区间测量，全 OK）

**咨询计数（`count_expectations`，19/19 匹配后 0 mismatch）**：C01=2、C02=1、C03=1、C04=1、C05=2、C06=2、C07=3、C08=5、C09=4、C10=1、C11=1、C12=1、C13=1、C14=5、C15=1、C16=0、C22=2、C23=2、C24=11。
（C15/C16/C22/C23/C24 为**咨询性**附加计数，**无** checker 读取，**不**参与判定；`C24` 的期望值修正见 §12 第 4 次运行。）

**区间测量（`range_expectations`，15/15 OK）**：`sbs_defaults_rate_literals=3`、`sbs_persist_rate_literals=3`、`sbs_persist_has_bounded=0`、`sbs_catch_sets_resultid_null=1`、`rc_constants_pinned=3`、`rctest_labeldef_call=1`、`rctest_labeldef_has_arith=0`、`frs_ascii_additive_comment=2`、`sbs_inline_entry_multiplicative=1`、`frs_site_1/2_exit_commission_step=1/1`、`lfs_site_1/2_exit_commission_step=1/1`、`rc_declared_exit_factor_excludes_commission=1`。

**其他关键文本测量**：`attr_phrase_count=1`、`emitters_total=3`、`emitters_with_colocated_multiplicative=2`、`pcs_samples_from_lfs=1`、`doc_api_additive_clause=1`、`vue_bilateral_commission=1`、`free_text_origin_declared=0`、`structured_origin_declared=1`、`declared_entry/exit_additive_steps=1/1`、`r001_additive=1`、`r001_frozen_total=0`、`r003_frozen_total=5`、`r003_additive=0`。

---

## 附录 C：复现命令

```bash
cd research/paper_loop/experiments/R010_net_return_audit
python make_config.py          # 生成/复算 config.json（24/24 冻结值 + 8 项结构自检，任一失败 exit 2）
python run_net_return_audit.py # 跑完整闭环（依赖→mvn→javac→两次 JVM→文本测量→门→校准→判定→金丝雀→K1-K10→result.json）
```

环境：`javac/java 21.0.10`、`python 3.13.5`、Java 侧 `mvn -o -B test -pl modules/backend-java`（**350 passed / 0 fail**）。
`~/.m2` 钉死 12 个 jar（缺任一 ⟹ `K9`）。运行结束自动清理 `probe/out` 与 `target/classes` 内的探针字节码。

---

## 附录 D：本轮的待决策/阻塞登记（**不由 agent 自行处理**）

| 编号 | 类型 | 内容 |
|---|---|---|
| `D-R010-01` | PENDING_USER | 「声明—实现」口径分歧的**处置方式**（改文本 / 改实现 / 只登记）属协议 §7 保留权限 |
| `D-R010-02` | PENDING_USER | X01：加法式记法文本与乘性代码同处，其中 `FactorResearchService:80` 的 `basis_note` **被发射进载荷** |
| `D-R010-03` | PENDING_USER | X02：`FactorResearchService:352` 第三种 ASCII 加法记法，出场式把 `s` 用了两次 |
| `D-R010-04` | PENDING_USER | X03：`ScoringBacktestService` 入场费站点**两份副本**，声明侧未提及 |
| `D-R010-05` | PENDING_USER | V18：两个研究仪器（R001/R003）走加法式文档口径，净收益偏乐观；**是否**重算属用户决定 |
| `D-R010-06` | PENDING_USER | V15：计算路径（钳位）与持久化路径（原始值）取不同费率，载荷无标记；**是否**改 DB/加约束属 §7 |
| `D-R010-07` | PENDING_USER | V13：净收益公式算术在**测试层零守卫**；**是否**加算术守卫属 §7（改测试契约） |
| `B-R010-01` | BLOCKED | `V15` **无法**端到端观测（`persistRun` 需 Spring 容器与 DB）——已按「推导 + 诚实边界」处理并披露，**若**要求端到端证据则阻塞 |
