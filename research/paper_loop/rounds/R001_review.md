# R001 论文审查（维度 A 严谨性 + 维度 B 适配性七问）

> 审查日期：2026-09-14
> 审查人：paper_loop agent
> 默认立场：**尝试杀死它**，而不是证明它对。

---

## 0. 论文标识

| 项 | 值 |
|---|---|
| paper_id | `LSY2019` |
| 标题 | Size and Value in China |
| 作者 | Jianan Liu, Robert F. Stambaugh, Yu Yuan |
| 年份 | 2018（NBER WP 24458, March 2018, Revised December 2018）→ 2019 发表 |
| 原始来源 | 期刊：Journal of Financial Economics 134(1), 48–69, DOI 10.1016/j.jfineco.2019.03.008（**A 级**）<br>全文来源：NBER Working Paper 24458（**B 级**，未审稿版） |
| 全文获取 | 成功。`papers/lsy2018_nber_w24458.pdf`（57 页，654,889 bytes）+ 文本 `papers/lsy2018_nber_w24458.txt` |
| 访问日期 | 2026-09-14 |
| 引用数 | 871（OpenAlex，2026-09-14） |
| 是否有代码 | 未随 NBER WP 提供；因子构造步骤在正文 Table 1 有足够细节 |
| 是否有数据 | 无公开数据包；使用 CSMAR/WIND 类数据（本地不具备） |
| 外部复现 | 该文本身即对「直接用 FF(1993) 流程复刻到中国」的复现/批评；后续被大量中国因子文献引用 |

**注意**：本轮读的是 **NBER 工作论文版（2018-12 修订）**，不是最终 JFE 排版版。结论以工作论文版为准；若两者结论有差异，须以正式版复核。此项记入 `decision_queue.md`（D-R001-03）。

---

## 1. 论文核心主张（原文抽取，非转述）

从 `papers/lsy2018_nber_w24458.txt` 直接摘录：

1. **规模因子必须剔除最小 30%**：
   > "The size factor excludes the smallest 30% of firms, which are companies valued significantly as potential shells in reverse mergers that circumvent tight IPO constraints."
   > "83% of the reverse mergers in China involve shells coming from the smallest 30% of stocks. For a typical stock in the bottom 30%, we estimate that roughly 30% of its market value reflects its potential shell value in a reverse merger."
   > "Therefore, to avoid shell-value contamination when constructing any of our factors, we delete the bottom 30% of stocks, which account for 7% of the stock market's total capitalization."

2. **价值因子应用 EP 而非 BM**：
   > "The value factor is based on the earnings-price ratio, which subsumes the book-to-market ratio in capturing all Chinese value effects."

3. **无动量、全窗口反转（本轮立项所依据的主张）**：
   > "Also unlike the US, there is no momentum effect in China. There is, however, a reversal effect, as past losers significantly outperform past winners. Reversal effects in China are especially strong. Past performance over any length window tends to reverse in the future."
   > "In untabulated results, we find that past returns over windows of one, three, six, and twelve months, as well as five years, all negatively predict future returns, in monotonically weakening magnitudes."

4. **反转与换手/情绪高度耦合（对第 3 条的重要限定）**：
   > "CH-3 fails only with some of the reversal and turnover anomalies."
   > "The resulting four-factor model, CH-4, explains the turnover and reversal anomalies in addition to the anomalies explained by CH-3."

5. **论文样本期**：2000–2016（17 年），规模与价值因子月均溢价均超过 1%。

---

## 2. 维度 A：论文严谨性

| 维度 | 判断 | 证据/说明 |
|---|---|---|
| 来源可信度 | 高 | JFE 正刊；作者 Stambaugh/Yuan 为该领域权威；引用 871 |
| 数据描述完整性 | 中 | 未公开数据包；依赖 CSMAR/WIND，本地无法比对原始字段 |
| 数据时点控制 | 中 | 因子按月度构造，用上月末市值/财务；未见明确的 PIT 财务公告日处理说明 |
| 生存者偏差控制 | 中 | 论文使用全样本，但对退市股处理未在正文说明 |
| 未来函数控制 | 中 | 因子构造与检验分离；但反转结果是「untabulated」 |
| **交易成本处理** | **弱（关键缺陷）** | 全文为组合层面的多空 alpha 检验，**未见交易成本/换手率扣除**；反转类结果的换手极高 |
| 样本外验证 | 中 | 样本期内分月回归（Fama-MacBeth），非真正的样本外留出；无 walk-forward |
| 时间切分合理性 | 中 | 按月度横截面，无随机切分问题 |
| 参数敏感性 | 弱 | 反转的窗口长度（1/3/6/12 月）只在「untabulated results」中一句话带过，未给表 |
| 多重检验控制 | 弱 | 论文检视了 9 类异象、多个窗口、多个估值比率，未做多重检验校正（Harvey-Liu-Zhu 类） |
| 统计显著性 | 中 | 主表给了 t 值；**但本轮依赖的反转结论没有表格与 t 值** |
| 置信区间 | 无 | 未报告 |
| 可复现性 | 中 | 因子构造流程可复现；反转结论不可复现（无表） |
| 对 A 股适用性 | 高（本身就是 A 股研究） | 直接研究中国 A 股 |
| 对本地系统可接入性 | 中 | 反转信号可用纯 OHLCV 计算；**规模/EP 两腿需要市值与盈余，本地缺失** |
| 实现复杂度/维护成本 | 低 | 反转因子为已实现因子 `er1/er5/er20m5` 的同族 |

### 维度 A 结论

**有条件通过。** 但必须承认一项严重缺陷：**本轮立项所依据的「全窗口反转」结论在论文中属于 "untabulated results"，没有表格、没有 t 值、没有窗口敏感性**。也就是说，论文在此处**没有提供可审计的证据**。这本身构成本轮的关键风险，也正好符合「默认该结论在本地不成立」的立场。

处理方式：不把论文的数字当阈值，本轮全部阈值在本地自行测定（见 `R001_prereg.md` 第 8 节）。

---

## 3. 维度 B：适配性七问

| # | 问题 | 结论 | 依据 |
|---|---|---|---|
| 1 | 是否依赖做空或对冲工具？ | **否** | 论文以多空因子组合为主，但反转张本身可只做多方（买过去输家）。本轮只测长多可实现的形式 |
| 2 | 是否依赖日内/高频或 T+0？ | **否** | 论文按月/更长窗口；本轮映射到项目 T+1/T+2/T+3 口径，entry=次日开盘 |
| 3 | 是否依赖做市/流动性假设或小票低滑点？ | **否** | 反转横截面覆盖全池；本地按单边万二滑点 + 流动性闸（avgAmount20）过滤 |
| 4 | 是否使用本项目不具备的数据？ | **否（对反转腿）**<br>**是（对规模/EP 腿）** | 反转腿只需 OHLCV；EP 需要盈余（`stock_financial_snapshot` 空）、规模需要市值（`total_market_value` 仅 2026 年有 267,494 行）→ 规模/EP 两腿记「数据不具备」，不入本轮 |
| 5 | 结论是否来自特殊样本期？ | **否** | 2000–2016 覆盖牛熊、（相对）正常区间；但本地上测试期仅 2024–2026，含 2024-09 与 2025 的特殊行情，须在报告中披露 |
| 6 | 所需数据是否存在且 PIT 可用？ | **部分** | OHLCV 存在；但 `market_price_raw`/`adjust_factor`/`security_master_history` 均为 0 行 → 只能用前复权价、无法重建历史股票池 → **按第 6 问记数据阻塞项**，写入 `blocked_queue.md`，不硬实现 |
| 7 | 换手率 × 成本后是否仍有正期望？ | **未知 → 本轮核心待检验项** | 反转是高换手信号。日频反转的往返成本 12bp，年化换手可达 250 倍量级 → 极可能被成本吃光。**这正是本轮主要杀死路径** |

### 维度 B 结论

**不触发「任一为是即归档」**：七问中有 4 项明确为否；第 6 问部分不满足，但影响的是**规模/EP 两腿**（本轮不测），反转腿所需数据（OHLCV）存在；第 7 问是待检验项而非已知否定。

→ 允许立项，但**范围严格限定为反转腿**，且第 7 问作为主要杀死条件。

---

## 4. 论文假定与本地口径的差异（不得用论文假设覆盖项目口径）

| 项 | 论文 | 本项目口径（`研究口径规范.md`） | 本轮采用 |
|---|---|---|---|
| 交易成本 | 未扣除 | 买万三 / 卖万五 / 单边滑点万二 | **项目口径** |
| 入场 | 月末/月初 | `signal_date` 下一交易日开盘 | **项目口径** |
| 持有期 | 月 | T+2 / T+3（`LABEL_HORIZONS={2,3}`） | **项目口径**，exit = entry 后第 h 个交易日 |
| 组合 | 多空 long-short | 只做多、T+1 | **只测长多可实现形式** |
| 规模定义 | 流通市值 ME | 本地 2026 年才有市值 | 本轮不使用 |
| 股票池 | 全 A（含最小 30% 之外） | 可交易性闸（ST/停牌/涨跌停/上市时长/流动性） | **项目口径** |

---

## 5. 论文最可能在哪些情况下失效（事前登记）

1. **成本**：反转是高换手信号，日频/短周期反转的毛收益可能被 12bp 往返成本完全吃掉。
2. **样本期不匹配**：论文 2000–2016，本轮只有 2024-01–2026-09（645 交易日）；A 股投资者结构与监管在 2020 年后变化剧烈（注册制、涨跌幅改革、量化占比上升）。
3. **窗口差异**：论文的「弱化但不消失」结论主要在 1/3/6/12 月窗口；本项目持有期为 T+2/T+3，属论文未细究的极短周期。
4. **与现有因子重复**：项目 `CodexShortCycleScoringEngine` 已含 `er1`（负权重 −0.15）与 `er5/er20m5`（正权重 +0.20/+0.28）。即生产引擎已假定「1 日反转、5/20 日动量」，与论文「全窗口反转」直接冲突。若反转只存在于 1 日窗口，则属「与现有因子重复」。
5. **前视/幸存者偏差放大**：本地 `security_master_history` 为空，历史池用当前 `stock_info` 快照回填 → 已退市股全部缺失；反转策略天然偏好高波动小票，该缺口会**放大**毛收益（注意：是放大，不是削减）。

---

## 6. 审查结论

- **通过初审门槛**：论文侧样本期 17 年（≥5 年）；有明确可交易信号定义；报告了换手/异象类别（虽未扣成本）。**本地复现侧无法满足 5 年样本要求**（本地仅 1.7 年），此为本轮降级披露项，不是论文缺陷。
- **立项范围**：仅「过去收益 → 未来收益」的反转腿，**不含**规模/EP 腿。
- **主要杀死路径**：成本（第 7 问）、样本外衰减、与现有 `er1/er5/er20m5` 重复。
- **下一步**：写 `rounds/R001_prereg.md`（含杀死条件），落盘后再写实验代码。
