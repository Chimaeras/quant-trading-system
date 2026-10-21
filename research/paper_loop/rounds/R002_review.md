# R002 论文审查

> 轮次：R002 ｜ paper_id：`LR2018` ｜ 审查日期：2026-09-14
> 论文：Juhani T. Linnainmaa, Michael R. Roberts, *The History of the Cross-Section of Stock Returns*,
> Review of Financial Studies 31(7):2606–2649 (2018)，DOI `10.1093/rfs/hhy030`
> 全文载体：NBER Working Paper No. 22894（2016-12），74 页 / 125,533 字符，本地 `papers/lr2018_nber_w22894.pdf`
> 支持文献：Tyler Shumway, *The Delisting Bias in CRSP Data*, Journal of Finance 52(1):327–340 (1997)，
> DOI `10.1111/j.1540-6261.1997.tb03818.x`（**仅取到摘要级证据**，全文 PDF 未能下载，见 §5）
> 选题来源：R001 暴露的数据基建缺口（`blocked_queue.md` B-R001-01/04/05/09）

---

## 1. 论文身份与来源分级

| 项 | 值 |
|---|---|
| 来源 | **A**（RFS 正刊，同行评审，295 次引用；NBER WP 为 B 级载体） |
| 研究市场 | 美国（NYSE/AMEX/NASDAQ，1926–2015） |
| 研究频率 | 月度组合、年度会计特征 |
| 数据 | CRSP 收益 + Compustat 会计 + **手工收集 Moody's 手册（1918–1960s）** |
| 是否有代码 | 无公开代码仓库 |
| 是否被反驳 | 未检索到针对性反驳；该文已成为「异常样本外衰减」的标准引用 |

---

## 2. 核心主张（逐字提取）

以下引文均来自本地全文 `papers/lr2018_nber_w22894.txt`：

1. **摘要**："Using data spanning the 20th century, we show that **most accounting-based return
   anomalies are spurious**. When examined out-of-sample by moving either backward or forward in time,
   anomalies' average returns decrease, and volatilities and correlations with other anomalies increase."
2. "The data-snooping problem is so severe that **even the true asset pricing model is expected to be
   rejected when tested using in-sample data**."
3. 量级："The average anomaly's Sharpe ratio and the information ratios from the CAPM and the
   three-factor model, for example, **decrease between 60% to 77%**"（移出原样本期后）；
   "the out-of-sample alphas are between **40% to 60% of the in-sample alphas**"。
4. 机制（t 值通道）："An effect is deemed a return anomaly if its t-value is high... anomalies'
   **in-sample returns are "too high" and their volatilities "too low"** if data-snooping bias matters."
5. **数据库构造机制**（与本轮最相关）：Compustat "**back-filled information only for those firms
   that were deemed to be of the greatest interest** to these analysts (Ball and Watts 1977)"；
   "By 1965, which is the date by which Compustat is **survivorship-bias free**"。
6. **缺失退市收益的处理约定**（本轮直接采用）：
   "We take delisting returns from CRSP; **if a delisting return is missing and the delisting is
   performance-related, we impute a return of −30% (Shumway 1997)**."
7. 建议："asset pricing models should be **tested using out-of-sample data** or, when not feasible,
   by whether a model is able to explain **half of the in-sample alpha**."

---

## 3. 维度 A：论文严谨性

| 检查项 | 结论 |
|---|---|
| 样本期 | 1926–2015（约 90 年），远超 5 年门槛 ✅ |
| 样本外 | **双向**样本外（pre-discovery 与 post-discovery），是本轮最值得借鉴的设计 ✅ |
| 成本 | 组合层面未逐笔扣费（学术因子研究惯例）；但在讨论中引用 Hasbrouck (2009) 的交易成本趋势 ⚠️ |
| 换手 | 未报告换手率 ⚠️（不满足初筛门槛 2.2 第 2 条的字面要求） |
| 多重检验 | **正文明确定位为 data-snooping 问题**，并指出"difficult to correct test statistics even approximately" ✅ |
| 可复现性 | 数据需手工收集 Moody's 手册，**外部不可复现**；但方法（双向 OOS + 三期间对照）可移植 ✅ |
| 统计显著性 | 36 个异常的前/后样本期对比，报告标准误 ✅ |

**维度 A 判定：通过（有条件）。** 论文明确定位为「数据构造与数据窥探」的方法论研究，
其**方法**（双向样本外 + 数据构造对照）可本地移植；其**数据**不可复现，故本轮只移植方法与约定。

---

## 4. 维度 B：适配性七问

| # | 问题 | 结论 | 说明 |
|---|---|---|---|
| 1 | 依赖做空/对冲？ | 否 | 仅长多检验 |
| 2 | 依赖日内/高频/T+0？ | 否 | 月度组合 |
| 3 | 依赖做市/低滑点假设？ | 否 | — |
| 4 | 使用本项目不具备的数据？ | 否 | 本轮的本地检验只需 OHLCV + 交易日历（已具备） |
| 5 | 结论来自特殊样本期？ | 否 | 90 年横跨多个制度期 |
| 6 | 所需数据本地是否存在且 PIT 可用？ | **部分否** | 「历史时点股票池」本地仍无（`security_master_history` 0 行）→ **但本轮不需要它**：本轮的检验对象是**本地面板自身的构造**，只用面板内已有数据即可完成（见 §5） |
| 7 | 换手 × 成本后仍有正期望？ | 不适用 | 本轮不引入新策略，检验的是 R001 已有反转信号的**偏差来源**，成本沿用 R001 口径 |

**维度 B 判定：通过。** 无「是」项。

---

## 5. 论文主张与本地可测版本的差距（divergence 表）

| 论文 | 本地现实 | 本轮如何移植 |
|---|---|---|
| 用 Moody's 手册重建 1926–1960 的真实历史基本面 | 本地无任何历史基本面（财务三表 0 行）→ **不可移植** | 不移植。**放弃会计类异常**这一整块，只移植「数据构造如何影响异常强度」这一**逻辑** |
| CRSP 退市收益缺失时补 −30% | 本地 `market_data` 无退市标记；名字直接**停止出现**，退出价缺失的信号被 R001 **丢弃**（等价于假设收益为 0 或不存在） | **直接移植该约定**：对被丢弃的信号补 δ，并以 δ ∈ {−24%, −30%, −36%} 做 ±20% 敏感性（L&R 的 −30% 为中心） |
| Compustat 只回填「分析师最关心的公司」→ 幸存/回填偏差 | 本地面板存在**同类现象**：1,888 只代码（多为创业板/科创板）只在 2026-02 之后出现，占 OOS 截面 **29.9%**、IS 截面 **0%** | 构造「构成一致池」并做**安慰剂分布**检验 |
| 双向样本外（pre-discovery / post-discovery） | 本地面板仅 1.7 年，无法做 90 年双向 OOS | 降级为「IS 内 2024 vs 2025 构成对照 + 预注册 2026 OOS」，并**显式声明这不满足论文的样本期条件** |
| 36 个异常、平均效应 | 本地只检验**一个**已存在的信号（R001 的 20 日反转） | 本轮是关于**一个信号的偏差来源**，不是异常普查；不声称对「异常总体」的推论 |

---

## 6. 预注册前必须确认的可检验性（B002 探针实测）

`baselines/probe_universe_bias.py` → `B002_universe_bias.json`（**先于本预注册落盘**）：

| 通道 | 实测信号质量 | 是否可检验 |
|---|---|---|
| A 构成突变（2026-02 批量补录） | 1,888 只代码；占 **OOS 截面 29.9%**、**IS 截面 0%** | ✅ 质量充足 |
| B 缺失退市收益（退出价缺失被丢弃） | 丢弃信号 **18,692** 个，其中落在 attrited 名字内 **403** 个 | ✅ 非空，但相对 155 万信号**极小**（0.03%）→ 预注册需预判其量级可能不足 |
| C attrited 名字（样本期内停止交易） | **99** 只 / 5,555 只（1.78%）；其末 20 日收益中位数与大市不同 | ✅ 可测，但样本小 |

> 探针的作用正是**避免 R001 的 K2/K7 式空实验**：三条通道的信号质量均非 0，
> 故本轮三条都可以写进预注册，且每条都必须配一个**已知会触发**的阳性对照。

---

## 7. 本研究将采用的失败模式（预先声明）

1. **构成通道方向的先验与 R001 事后诊断相反**：R001 的事后诊断显示「构成一致池」的
   OOS IC 是 **−0.0291 (t −1.08)**，比全池的 **−0.0215 (t −0.70)** **更负**。
   即「剔除 batch 后衰减更明显」→ 若本轮复现该方向，则**构成通道解释不了衰减**，H1a 应被证伪。
   **本预注册明确期待这一结果，不得在看到结果后改口。**
2. **退市通道量级极小**（403/155万 = 0.03%），预期不足以解释 R001 的 +53.7bp 价差。
3. 因此本轮**预期判定为 REJECTED（负知识）**，这属于协议 §1「宁可无产出」的正常结果。

---

## 8. 结论

- 维度 A、B 均通过 → **允许立项**。
- 论文的**数据不可复现**，但**方法与一条具体约定（缺失退市收益补 −30%）可移植**。
- 本轮**不引入任何新因子、不改任何生产代码、不碰口径**，只检验 R001 已产生结果的偏差来源。
- 预注册见 `rounds/R002_prereg.md`（先于 `experiments/R002_universe/` 的任何代码）。
