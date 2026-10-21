# R003 论文审查：NMV2016《A Taxonomy of Anomalies and their Trading Costs》

> 审查日期：2026-09-14 ｜ 审查人：论文驱动量化研究 Agent
> 前置：`baselines/probe_cost_feasibility.py` → `B003_cost_feasibility.json`（**本文件之前落盘**）
> 说明：本文件只做**论文审查**，不含任何本地判定。判定与杀死条件写在 `R003_prereg.md`。

---

## 1. 论文身份

| 项 | 值 |
|---|---|
| paper_id | `NMV2016` |
| 标题 | A Taxonomy of Anomalies and their Trading Costs |
| 作者 | Robert Novy-Marx, Mihail Velikov |
| 发表 | **The Review of Financial Studies 29(1):104–147（2016）**；本轮全文为 NBER WP 20721（2014-12，61 页） |
| 来源级别 | **A**（RFS 正刊）；载体为 NBER WP（工作论文版） |
| DOI | `10.1016/…` 不适用；正刊 DOI `10.1093/rfs/hhv063`，WP：`10.3386/w20721` |
| 全文获取 | `papers/nmv2016_rfs_trading_costs.pdf`（2,099,323 字节，`%PDF-1.6`，61 页，抽出 103,919 字符）——**NBER 渠道可直取** |
| 访问日期 | 2026-09-14 |
| 研究市场 | 美国（CRSP × COMPUSTAT，NYSE/AMEX/NASDAQ 普通股） |
| 样本区间 | 1963-07 – 2013-12（全期）；1973-07 – 2012-12（近期期） |
| 频率 | 月度调仓（另含年度/季度变体） |
| 是否提供代码 | 否 |
| 是否提供数据 | 否（成本估计依赖 Hasbrouck 2009 的估计量） |
| 与项目对应模块 | 冻结口径 `ResearchConvention` 的成本三参数（`DEFAULT_COMMISSION_RATE` / `DEFAULT_SELL_TAX_RATE` / `DEFAULT_SLIPPAGE_RATE`）、`ScoringBacktestService` 与所有回测路径 |

---

## 2. 论文的核心主张（逐条摘录，标注位置）

1. **成本量级**：「Round trip transaction costs for typical value-weighted strategies average in
   **excess of 50 bps**, and though these have fallen over the last decade they can be significantly
   higher…」（Introduction）
2. **换手率门槛（本文最可检验的一条）**：抽象页原文——「Most of the anomalies that we consider with
   **one-sided monthly turnover lower than 50%** continue to generate statistically significant net
   spreads, at least when designed to mitigate transaction costs. **Few of the strategies with higher
   turnover do.**」正文重复一次：「only **two** of the strategies that have more than 50% one-sided
   monthly turnover have significant net spreads, even when these strategies are designed with trading
   costs mitigation in mind.」
3. **换手 × 成本的可加结构**：「if the long side of a strategy turns over 20% per month, the realized
   long/short spread will be **at least 20 bps per month lower** than the gross spread, and the
   statistical significance of the spread will be reduced proportionately.」
   → 隐含：**多空价差的成本拖累 ≈ 2 × 单边换手 × 双边成本**（20% × 2 腿 × ~50bp ≈ 20bp/月）。
4. **等权组合被点名**：「equal-weighted portfolio results … are **misleading and should be viewed
   skeptically**. Because equal-weighted strategies are more expensive to trade, equal-weighting often
   results in a deterioration of net performance.」另：「Transaction costs for equal-weighted strategies
   are generally **two to three times as high**」。
5. **成本模型**：使用 **Hasbrouck (2009)** 的 effective bid-ask spread（在 Roll 1984 模型上的贝叶斯
   Gibbs 估计），且**明确声明它不含价格冲击、假设全部为市价单** → 是**下界**：
   「even though the effective bid-ask spread measure we use **does not account for price impact** it
   assumes market orders for all trades」。
6. **为什么不能直接用日频自协方差估**（原文，对本轮至关重要）：
   「Earlier empirical studies used the sample autocovariances of daily price changes to estimate
   transactions costs but, as noted by Hasbrouck (2009) and discussed in detail by Harris (1990),
   such an estimation is **infeasible due to the relatively high proportion of positive autocovariances
   between daily changes in stock prices** in the data.」
7. **多重检验警告**：「In all cases transaction costs reduce the strategies’ profitability and its
   associated statistical significance, **increasing concerns related to data snooping**.」

---

## 3. 维度 A：严谨性

| 项 | 评述 | 分 |
|---|---|---|
| 样本长度 | 50 年（1963–2013），远超协议 2.2 的 5 年门槛 | 高 |
| 样本外/滚动 | 含 1973–2012 近期期与全期两套；含分段市值分析 | 中高 |
| 成本是否内置 | **是，且是论文的核心**——逐策略估计成本 | 高 |
| 换手是否报告 | **是**，逐一报告双边平均换手 | 高 |
| 多重检验处理 | 报告「23 个异常 × 3 种缓解策略」的完整矩阵；讨论功效衰减与数据窥探，但未做 formal 多重检验校正（如 Harvey-Liu-Zhu 的 haircut） | 中 |
| 可复现性 | 无代码、无数据；成本估计量依赖 Hasbrouck (2009) 的实现 | 中 |
| 结论稳健性 | 结论在 value-weight / equal-weight、不同市值段、不同调仓频率下都报告；**未做参数敏感性**（成本的 ±20%） | 中高 |
| 与项目口径冲突处 | (a) 论文用 value-weight 为主、明确否定 equal-weight；**本项目 R001/R002 用的是等权十分位**；(b) 论文按 NYSE 断点分位；本项目为全池十分位；(c) 论文成本 ~50bp/双边；本项目冻结口径 **12bp/双边** | — |

---

## 4. 维度 B：适配性七问

| # | 问题 | 答案 | 依据 |
|---|---|---|---|
| 1 | 是否给出**可交易信号**？ | **是** | 论文直接给出多空十分位组合的净价差 |
| 2 | 是否报告**成本与换手**？ | **是** | 这是论文主题 |
| 3 | 样本期是否 ≥5 年？ | **是（原文）／否（本地）** | 原文 50 年；本地可用横截面仅 1.7 年（B-R001-01） |
| 4 | 是否有**样本外/滚动**验证？ | 部分 | 全期/近期期分段，非严格 walk-forward |
| 5 | 结论是否依赖**特殊制度/样本期**？ | **否（但成本数字依赖美国市场制度）** | 论点（成本吃掉高换手异常）是机制的，不是某段行情的；但「>50bp」是美国做市商/价差制度的产物 |
| 6 | 是否依赖本项目**不具备的数据**？ | **是（部分）** | 需要**有效价差**数据或可用的价差估计量。本地 `high/low` 齐全但两条估计路线都被探针证否（见 §5）→ 只能改用**模型自由的一跳下界** |
| 7 | 是否需要**卖空/日内/HFT**？ | 论文是**多空**；本项目**无做空能力** | 本轮的检验对象是「成本量级」，可以只对**多头腿**（D1）做判定，不需要卖空；价差（长空）只作为参考量报告 |

**七问结论**：**第 6 问命中（部分）**。论文的机制可适配，但其**成本量级无法在本地复现估计**。
按协议「任一问为『是』即归档」的规则，本轮**不把论文的绝对成本数字当作本地依据**，
只把它当作**换手门槛（第 2 条主张）**与**成本拖累结构（第 3 条主张）**的来源。

---

## 5. 与本地环境的偏离（含探针证据）

| 维度 | 论文 | 本地 | 探针证据（`B003_cost_feasibility.json`） |
|---|---|---|---|
| 双边成本 | **>50bp**（美国） | 冻结口径 **12bp**；佣金 3bp + 印花税 5bp + 滑点 2×2bp | `frozen_cost_bp.round_trip_total = 12.0` |
| 价差可估性 | Hasbrouck(2009) 贝叶斯 Gibbs | **两条路线都被证否** | CS 逐股日均价差中位数 **95.01bp**，折合 **13.0 跳**（86.8% 的股票 >4 跳）→ 量级不可信；Roll 月度 naive 估计中位数 **781.87bp** → 高出任何可信价差 1–2 个数量级 |
| 为什么 Roll 不可用 | 论文**自己**警告日频自协方差不可行（§2 第 6 条） | 本地 73.7% 的股票月度一阶自协方差为负，但量级被收益反转主导 | `ac1_median = −0.122`，`ac1_negative_share = 73.7%` |
| 可用的替代量 | — | **模型自由的「一跳」下界**：0.01 元 / 价格 | 价格中位数 **14.09 元** → 一跳双边下界中位数 **7.10bp**，p90 **23.04bp** |
| 换手率 | 门槛 50%/月（单边） | **747%/月**（单边），年化 **86.1×** | `one_sided_turnover_monthly_D1 = 7.47` |
| 权重 | value-weight（并否定 equal-weight） | **等权**十分位 | R001/R002 均为等权，论文称其成本高 2–3 倍 → 本轮的「成本上界」不能沿用论文的 value-weight 数字 |
| 做空 | 多空 | **无做空** | 判定只对 D1 多头腿与长空价差**参考量**做，不假设可卖空 |

---

## 6. 预声明：本轮**预期**的失败模式（在看到结果前写下，不得事后改口）

R001 已冻结的毛价差为 D1−D10 **53.7bp / 3 日**（`R001_reversal/result.json`），
等权、无成本时其量级很大；而本项目冻结成本只有 12bp。据此**事前判断**：

- 论文第 2 条主张的**门槛**（单边月换手 ≤50%）在本策略上**不满足**（实测 747%/月）→
  论文的「高换手难存活」结论**形式上适用**；
- 但论文的**成本量级（>50bp）不可外推到 A 股**（A 股佣金+印花税仅 8bp，且有 1 跳的价格档约束），
  所以论文**预测**（成本吃掉价差）在本地**很可能不成立**；
- 因此**预期判定**：**INCONCLUSIVE**——本地既能算出「价差要多大成本才会归零」，
  又**无法**证明真实成本达到该水平（价差不可估，B-R003-01）。
- **若最终判出 REJECTED 或 ADOPTED，必须能在预注册的判据链上逐条对上；不得在看到结果后改口径。**

---

## 7. 结论

论文的**机制**（高换手 × 成本吃掉异常）值得在本项目检验，且它有一条**极硬的本地可用形态**：
「使价差归零所需的双边成本 = X bp」 vs 「本地**可证**的成本下界」。
论文的**绝对数字**（>50bp、Hasbrouck 估计量）在本项目**不可用**（第 6 问命中 + 探针证否），
故本轮：**检验论文的换手门槛是否适用、成本拖累结构是否足以消除本地价差**，
并把「价差不可估」这一数据缺口登记为新 BLOCKED。
