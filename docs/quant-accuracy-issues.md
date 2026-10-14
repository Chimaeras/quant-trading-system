# 量化系统 · 准确性问题清单（最终结论版）

> **本文件只保留最终结论。** 原始 7 轮排查记录、Codex 逐条验证证据与内嵌复现探针，
> 完整留档在 `quant-accuracy-issues.archive-full.md`（2006 行）——需要追溯证据时看那一份。
>
> 结论来源：7 轮人工排查（下称"原排查"）+ Codex 独立验证（2026-10-13，24 个对象全覆盖）。
> **原排查有 5 处实质错误被 Codex 证伪或纠正，已在下方如实标注。**

## 状态图例

| 标记 | 含义 |
|---|---|
| **待修复** | 已用可复现证据确认存在，需要改代码 |
| **子项待修** | 原结论整体部分成立，仅其中已复现的子项需要处理 |
| **需产品决策** | 事实已确认，但"要不要改"取决于策略意图，改前须确认 |
| **已证伪** | 经独立复现推翻，**不要再排查、不要修** |
| **待查** | 现象确认，成因未决，暂不构成修复项 |

## 优先级总览

| 优先级 | 编号 | 一句话 |
|---|---|---|
| P0 | R1-01 | 东财 K 线标签是 bar 终点却被当起点 + as_of 过滤多丢一根 |
| P0 | R2-01 | 本地拒绝陈旧序列，网络回退路径却重新接受（无日期闸门） |
| P0 | R4-02 | V1 风控闸近涨停阈值硬编码 9.0%，与 watch 板块阈值不一致 |
| P0 | R6-02 | 日历对超出覆盖范围的日期仍 `available=True`，工作日一律放行 |
| P1 | R1-03 | 开盘前启动时首根完整 bar 被无条件丢弃 |
| P1 | R6-01 | "今天"被误呈现为已知休市（未知覆盖与休市未区分） |
| P1 | R1-02 | 盘中重启场景下有两个真实区间未进入序列 |
| P1 | R1-05 | 解析器与实时路径的 `start_ts` 口径冲突 |
| P2 | R3-02 | 打分读取接口直接返回半年前/空数据，无陈旧标识 |
| P2 | R5-02 | V1 对不支持意图类型的拒绝不写原因码 |
| P2 | R7-02 | 午休期间 `valid_until` 与"下一根 bar 收盘"口径不一致 |
| P2 | R5-01 | 配额顺序依赖 + 被拒信号不补发（机制确认，改为策略政策问题） |

---

## 修复进度（2026-10-13）

| 编号 | 状态 | 修复内容 | 验证证据 |
|---|---|---|---|
| **R1-01** | ✅ 已修复 | 东财标签按**终点**解释；`as_of` 采纳随之修正 | `as_of=10:32`：11 根/末根 close **1025** → **12 根/末根 close 1030**；末根由 `15:00~15:05` → **`14:55~15:00`** |
| **R2-01** | ✅ 已修复 | 网络回退路径复用 `_series_is_usable` 日期闸门 | `real prepare ready True errors []` → **`ready False`** + `KLINE_SERIES_OUT_OF_TRADING_WINDOW` |
| **R4-02** | ✅ 已修复 | `check_intent` 改用 `near_limit_up_threshold_pct`（按板块） | 北交所 `920627` +15%：`NEAR_LIMIT_UP` → **`PASS`**，与 watch 闸一致 |
| **R6-02** | ✅ 已修复 | 日历新增 `coverage` 契约，区间外 fail-closed | `2027-01-01`/`2027-02-17`：`True` → **`False`**；`2026-10-13` 仍 `True` |
| **R5-02** | ✅ 已修复 | V1 不支持的意图类型补原因码 | `SELL_LIMIT`：`''` → **`UNSUPPORTED_INTENT_TYPE_IN_V1`**；`NOOP` 仍 `PASS` |
| **R4-03** | ✅ 已修复 | 新增 `allow_bse`，**默认 False**（与两创一致） | 北交所默认 **`BOARD_BLOCKED_BSE`**；`PAPER_ALLOW_BSE=1` 解禁 |
| **R1-03** | ✅ 已修复 | 按「首快照距桶起点偏移」判定残段；≤60s 视为完整首桶 | 09:30:05 启动：当日 history `['09:35']` → **`['09:30','09:35']`**；对照：10:32 接入仍丢弃残段 |
| **R1-02** | ✅ 已修复 | `BarUpdate.discarded` + `insert_closed` 合并 + `loop._repair_bar_gaps` 补拉重放 | 丢弃被登记 → 下轮补回并重放；取不到保留待补；权威区间不被本地残段覆盖、无重复 `start_ts` |
| **R7-02** | ✅ 已修复 | 新增 `next_bar_end()`，午休处按 13:00 接续 | 11:30 那根：`valid_until` 11:35 → **13:05**（与「下一根 bar 收盘」语义一致） |
| **R1-05** | ✅ 已修复 | 妙想标签口径未取证 → 来源选择处 **fail-closed** | `fetch_history(source=mx_query)` → **`MX_KLINE_LABEL_SEMANTICS_UNVERIFIED`**，不发请求；`PAPER_MX_KLINE_LABEL_CONFIRMED=1` 放行 |
| **R6-01** | ✅ 已修复 | `DayVerdict` 三态，把「日历未覆盖」从「休市」拆出 | 区间外：`HOLIDAY` → **`phase=UNKNOWN`** + 覆盖率 `DEGRADED`；区间内非交易日仍 `HOLIDAY`；空日历/查询失败均 `UNKNOWN` |
| **R3-02** | ✅ 已修复 | 三个读取接口新增 `data_status`（OK/STALE/EMPTY）等可用性标记 | 半年前数据 → **`STALE` + `staleness_days=189`**；0 行 → **`EMPTY`**（不再只给 `trade_date=""`）；请求日被静默降级 → **`downgraded=true`** |
| **R3-01** | ✅ 已修复（2026-10-14 重标定） | 阈值 **35.0 → 30.0**，改为配置项 `quant.t1.eligibility.rule-score-threshold`，版本 `t1_rule_v2 → t1_rule_v3` | `2026-10-13` 真库重跑：候选 **0 → 4** 只；`max_rule_score` 落库；`limit_up_excluded=25` |

**测试**：基线 **316 passed** → **346 passed**（第一批 30 例）→ **358 passed**（第二批 12 例）→
**364 passed**（D-WATCH 第三批 6 例）→
Java **458 → 463 **（R6-01 新增 5 例）**→ 474 passed**（R3-02 新增 11 例）。零回归。

**改动文件**：`paper/risk.py`、`paper/models.py`、`paper/config.py`、`paper/decide.py`、
`paper/watch/loop.py`、`paper/watch/kline_client.py`、`paper/watch/universe.py`、
`paper/trading_calendar.py`、`paper/trading-calendar.2026.json`、
`paper/tests/test_accuracy_fixes_20261013.py`（新增）、`paper/tests/test_stale_bar_guard.py`、
`paper/tests/test_watch.py`

**待办提醒**：
- R1-01 改变了 bar 时间语义，**必须重启 watch 才生效**；重启后信号序列会与之前不同（这是修复的目的）。
- R4-03 使北交所**默认不可买**（热门股中约 5%）；若策略上要保留北交所，需显式设 `PAPER_ALLOW_BSE=1`。
- R1-05 只做到"东财侧口径修正"；**mx_query 路径的标签语义仍未取证**，已在
  `parse_kline_payload` 写明"启用前必须先比对 1 分钟线"，未凭猜测改动。

---

## 修复进度（2026-10-14）· T+1 候选链路与盘中信号可回溯

本轮起因：核查「实时盯盘是否正常、信号是否准确」时，实测发现 **T+1 候选信号链产出恒为空**，
且盘中信号**只有计数、没有明细**，导致"信号准不准"无法在事后回答。

| 编号 | 状态 | 修复内容 | 验证证据 |
|---|---|---|---|
| **R3-01** | ✅ 已修复 | 规则分阈值 **35.0 → 30.0**；提为配置项 `quant.t1.eligibility.rule-score-threshold`，版本 `t1_rule_v3`；新增 `effectiveRuleScoreThreshold()` 把非法配置夹到 `[15.0, 50.0]` 并告警 | `2026-10-13` 真库重跑：`status=OK`、`eligible_count` **0 → 4**、`max_rule_score=40.6653` |
| **D-T1-07** | ✅ 已修复 | `maxRuleScore` 与新增诊断 `limit_up_excluded` / `limit_up_excluded_max_score` **落库**（此前只在 API 响应里，快照里没有） | 旧快照（10-12/09-07/08-24）三个字段均为 `None`；新快照（10-13）为 `40.6653` / `25` / `32.4149` |
| **D-WATCH-01** | ✅ 已修复 | 新增 `signal_event` 表持久化盘中信号明细（标的/动作/策略/bar 时点/`valid_until`/原因码/指标明细），`SCHEMA_VERSION` **5 → 6**；按交易日保留最近 30 天 | 实证：6 轮推进产出 6 条信号 → `signal_event` **6 行**，金叉行为 `buy` + `reason_codes=MA_GOLDEN,BAR_CLOSED` + `detail_json` 含 `ma_short_curr/ma_long_curr` |
| **D-WATCH-02** | ✅ 已修复 | 长循环跨交易日时**自动校正** `signal_date` 为「当天的上一交易日」（`_resolve_signal_date`），同日稳定、显式传入不被改写 | 接入 2026 年正式日历：`now=10-14 10:00` → `10-13`；`10-15` → `10-14`；`10-19(周一)` → `10-16(周五)` |

**测试**：Python **358 → 364 passed**（新增 `paper/tests/test_signal_persistence_and_signal_date.py` 6 例，
覆盖信号明细落库/幂等/空轮次与信号日跨日校正/同日稳定/显式尊重）；
Java **474 passed**（`T1CandidateServiceTest` 由 35.0 断言改为 30.0、版本断言改为 `t1_rule_v3`）。零回归。

**改动文件**：`modules/backend-java/.../T1CandidateService.java`、
`modules/backend-java/.../resources/application.yml`、
`modules/backend-java/src/test/.../T1CandidateServiceTest.java`、
`paper/watch/store.py`、`paper/watch/loop.py`、
`paper/tests/test_signal_persistence_and_signal_date.py`（新增）

### 阈值 30.0 的标定依据（不是拍脑袋）

**取值域**：`score = clamp(ret5*40, -15, 20) + clamp((vr-1)*10, -10, 15) + clamp(maPos*15, -10, 15)`，
再 `clamp(0, 50)`。**它是 0–50 的规则分，不是概率**。

**日产出**（45 个信号日 2026-08-04..2026-10-13，复现生产评分池、排除收盘涨停）：

| 阈值 | 日均候选 | 零候选天数 |
|---|---|---|
| 35 | 3.38 | 4 / 45（8.9%） |
| **30** | **8.62** | **1 / 45（2.2%）** |
| 25 | 23.82 | 0 / 45 |

**预测力**（326,783 样本，2026-06-01..2026-10-13，次日开盘→次日收盘，剔除次日开盘涨停的不可买样本）：

| 分数区间 | 平均收益 |
|---|---|
| [20,25) | +0.322% |
| [25,30) | +0.562% |
| **[30,35)** | **+1.016%** |
| [35,50] | +1.179% |

市场同口径基准 **+0.181%**（胜率 51.00%）。收益随分数**单调递增**，30 分以上显著跑赢基准。
按阈值取每日 Top10：阈值 30 → 466 只 +1.165%；阈值 25 → 595 只 +1.328%；阈值 35 → 232 只 +1.160%。

**结论**：旧阈值 35 落在分布的**极端上尾**——2026-10-12 全市场 5,452 只中位数 0.00、均值 2.11、
p99 仅 21.15、≥35 只有 6 只（0.11%）。这才是候选列表长期为空的真因。

### 被推翻的假设（留档，避免重复排查）

- ❌ **「涨停预过滤造成死循环」不成立**。排除收盘涨停后，阈值 30 仍有 **8.6 只/日**、45 天里只有 1 天为零。
  故 `excludeLimitUpAtClose=true` **保持不变**（收盘涨停股次日多为一字板，确实不可买），
  只重标定阈值；同时新增 `limit_up_excluded` 对照诊断，使该主张今后可被持续检验。
- ❌ **代码注释里旧阈值 35 的标定记录（"命中 0/0/2/1/1"）经验上不成立**：11 份落库快照中有 10 份是
  `NO_ELIGIBLE_CANDIDATE`；2026-09-07 全部 1,340 只、2026-10-12 全部 1,265 只都低于阈值。
  历史上唯一非空快照是 2026-08-19（单只 `300684`）。**`maxRuleScore` 当时未落库，正是这次误标定没被发现的原因。**

### 盘中 `MA_CROSS(5,20)` 的有效性（结论：cross-day 无边际，继续观察）

373 个真实 5 分钟金叉，+3 bar 命中率 53.14%、净 +0.2658%（成本按单边 0.175% 计）。
**但按日符号翻转**：2026-10-12 命中 22.75% / 净 −0.3811%，2026-10-13 命中 80.77% / 净 +0.8605%。
只有 2 个有效样本日，**不足以判定存在跨日边际**；需累计 ≥20 个交易日再评估。

---

# 一、问题明细（均经独立验证确认存在；当前修复状态以上方「修复进度」表为准）

## P0 · R1-01 东财 K 线标签是「终点」却被当作「起点」，且 as_of 过滤多丢一根

**位置**：东财 K 线解析路径（`paper/` 内 K 线客户端与解析）、`paper/watch/bar_store.py` 的 as_of 过滤

**问题**：东财 K 线的时间标签是 bar 的**结束**时刻，代码把它当成**起始**时刻。
连带 as_of 过滤（"截至某时刻已知的最后一根 bar"）会因此**再少采纳一根**。

**证据**（Codex 用真实东财 1/5 分钟 payload 交叉对照 + 运行真实解析器）：

```text
同标签一致 10 处 / 不一致 0 处
as_of=10:32：采纳最后标签 10:25；实际已知最后标签 10:30
全日最后一根 start_ts=15:00:00 end_ts=15:05:00
```

即 `10:30` 那根 bar 在 10:32 时已经收盘可知，却被排除。

**修复方向**：统一区间语义——标签按**结束时刻**解释，`start_ts = 标签 - period`、
`end_ts = 标签`；as_of 过滤改用结束时刻比较。同时废止原排查提出的
"修标签会产生重复 start_ts"这一**已被反证**的顾虑（见 R1-02）。

**验收**：真实东财 payload 回放，`as_of=10:32` 必须采纳到 `10:30`；
全日最后一根 `start_ts=14:55 / end_ts=15:00`。

---

## P0 · R2-01 网络抓取路径没有日期闸门，导致本地闸门被「绕过」

**位置**：`paper/watch/loop.py` 网络取回分支（本地分钟线路径已有校验，网络回退未复用）

**问题**：完全相同的返回值，走**本地**路径会被拒绝，走**网络回退**路径会被接受并
`ready=True` → 策略用陈旧/停牌数据算出信号。

**证据**（Codex 先真实抓取 `002388`，再把同一返回值分别注入本地路径与网络客户端替身，
运行真实 `prepare`；文件状态与日志用内存替身隔离）：

```text
network result 30 dates ['2026-09-30']
allowed ['2026-10-12', '2026-10-13'] usable False
real prepare ready True errors [] local_hit 0
```

**修复方向**：把本地路径已有的**日期窗口校验**提取为共用函数，在网络取回结果
入库/进序列前**同样执行**；不通过则视为取回失败，而不是降级采用。

**验收**：注入 `002388` 陈旧返回值，网络路径必须 `ready=False` 且产生明确错误码；
与本地路径行为一致。

---

## P0 · R4-02 V1 风控闸 `NEAR_LIMIT_UP` 硬编码 9.0%，与 watch 闸不一致

**位置**：`paper/risk.py` —— `check_intent`（V1 路径）vs `check_watch_intent`（watch 路径）

**问题**：`check_watch_intent` 已按板块推导近涨停阈值
（`near_limit_up_threshold_pct`：主板 9、两创 19、北交所 29），
但 `check_intent` 仍是硬编码 `pct_chg >= 9.0`。
两闸对**同一标的、同一涨幅**给出相反结论。

**证据**（Codex 用同报价/同限价/同账户输入跑两个真实闸门）：

```text
600519 +5%：V1 PASS，watch PASS
920627 +15%：板块涨停30%，正确近涨停阈值29%
V1 NEAR_LIMIT_UP，watch PASS          ← 结论相反
```

影响面：`check_intent` 仍在 dry-run 预览路径上（用于展示真实拦截原因），
所以**展示给用户的拒单原因是错的**；北交所正常标的会被误拒。

**修复方向**：`check_intent` 改用 `near_limit_up_threshold_pct(code, exchange)`，
与 watch 路径统一到同一来源。

**验收**：每个板块各取"距涨停 1 个点内"与"距涨停 5 个点外"样本，两闸判定必须一致。

---

## P0 · R6-02 Python 日历对超出覆盖范围的日期仍宣称可用，工作日一律放行

**位置**：`paper/trading_calendar.py`（`is_trading_day` / `load_calendar_from_file`）、
`paper/trading-calendar.2026.json`

**问题**：日历加载后**无条件** `available=True`，且不记录/不校验数据覆盖范围；
未命中的日期回退为 `weekday() < 5`（**fail-open**，未知即当作交易日）。
实测该文件仅 19 条节假日、范围 `2026-01-01 .. 2026-10-07`，之后无任何节假日。

**证据**（Codex 真实加载文件并执行 `is_trading_day`）：

```text
calendar available=True，holidays19，2026-01-01..2026-10-07，extra0
2026-10-13 True
2027-01-01 True
2027-02-17 True
```

**修复方向**：界定"覆盖区间 / 未知"契约——`available` 应反映**该日是否在权威区间内**；
区间外日期返回"未知"而非"交易日"，并要求调用方按未知处理（fail-closed 或显式阻断）。
**注意**：不要简单把"假日集合的最晚日期"当作有效截止日来用。

**验收**：对覆盖区间外的日期返回未知并阻断；与 Java 侧对同一天的判定方向一致。

---

## P1 · R1-03 开盘前启动时，当日第一根**完整** bar 被无条件丢弃

**位置**：`paper/watch/loop.py` / `bar_store.py` 的 seed 路径（`discard_first_forming`）

**问题**：以 `discard_first_forming=True` 从开盘首个快照跨桶推进时，
**首个完整桶也被丢弃**，当日少一根 bar。

**证据**（Codex 运行真实 `BarStore`）：

```text
快照09:30:05 closed=[]
快照09:35:05 closed=[]
快照09:40:05 closed=['09:35']
今日 history=['09:35']；首根完整桶未保留
```

**修复范围限定**：仅该种 seed 路径。**不覆盖**恢复 checkpoint、显式离线 seed 等
本就免丢弃的路径——不要笼统取消 `discard`。

**验收**：上述快照序列下 `09:35` 首根完整桶必须保留；
恢复 checkpoint / 离线 seed 路径行为不变。

---

## P1 · R6-01 「今天」被误呈现为已知休市（未知覆盖与休市未区分）

**位置**：`TradingCalendarService.java` 的 `isTradingDay` 及其调用点

**问题**：`isTradingDay(date)` 实现为"表里存在该行即为交易日"，而 `trading_calendar`
表**只存交易日**（实测 517 行全部 `is_trading_day=1`，零行非交易日），
且 ingest 只覆盖到**前一天**。于是"今天"没有行 → 被判为**已知休市**，
而不是"日历尚未覆盖"。下游把这两种语义混为一谈。

**证据**（Codex 实测）：

```text
517行，全交易日，覆盖2024-08-19..2026-10-12
今日行0；最新ingest=2026-10-13 13:21:50
calendar API today_is_trading_day=False
market_session phase=HOLIDAY，label=休市（非交易日）
```

**对原排查的纠正（Codex 指出，原排查有误）**：
- ❌ 原排查断言"结构上无法回答今天是否交易日""`isTradingDay(今天)` **恒**为 false"
  —— 过度断言。不是结构上不能，也不是每天都恒 false。
- ❌ 原排查断言"8 个调用点里 6 个裸用" —— **计数错误**。实测真实调用点 6 个，
  其中**裸用 4 个**、配套覆盖判断 2 个。
- ❌ 原排查把当时的整体 BLOCKED 归因于日历闸 —— 实际来自 `data_gate`。

**修复方向**：把 `isTradingDay` 的返回语义改为三态（`TRADING` / `NOT_TRADING` / `UNKNOWN`），
或强制调用方结合 `coverageWindow()` 判断；审查那 **4 个裸用点**。

**验收**：交易日盘中，"今天"不得被判为**已知休市**（返回交易日或未知）。

---

## P1 · R1-02 盘中重启场景下有两个真实区间未进入序列

**位置**：`paper/watch/loop.py` 盘中重启 / 恢复路径

**问题**：`10:32` 重启场景下，确有**两个真实区间**没有进入 bar 序列。

**证据**（Codex 运行真实路径 + 内嵌探针）：

```text
最后种子 start_ts=2026-10-13 10:25:00
快照10:35:10 closed=[]；10:40:10 closed=['10:35']
相邻 start_ts 10:25 -> 10:35，10分钟
corrected seed + discard True ['10:20','10:25','10:35'] duplicate_start_ts 0
```

**对原排查的纠正**：
- ❌ 原排查断言"任意盘中重启都会留下 2 根断层" —— 不成立，只在该场景复现。
- ❌ 原排查提出的"只修标签会产生重复 `start_ts`"这一**修复陷阱被反证**：
  内存中同时改正 `start/end` 与 as_of 采纳、仍保留 `discard=True`，
  得到**无重复**序列，仅首个不完整实时桶缺失。**不要据此错误陷阱设计工单。**

**验收**：`10:32` 场景下两个缺失区间均进入序列；`duplicate_start_ts = 0`。

---

## P1 · R1-05 解析器与实时路径的 `start_ts` 口径冲突

**位置**：东财 K 线解析路径（存**终点**）vs 实时 live bar 路径（存**起点**）

**问题**：两个来源对同一真实区间使用不同的 `start_ts` 口径，需统一。

**证据**（Codex 实跑 DB 探针）：

```text
eastmoney_kline 25040 行，start_ts 最大15:00
eastmoney_batch 80 行，start_ts 最大14:50
shared_code_day 0
shifted_pairs 0
```

**对原排查的纠正**：
- ❌ 原排查断言"同一真实区间在主库存在两种口径 / 重复行" —— **未能实测**。
  当前两个来源**没有任何同股票同日交集**（`shared_code_day=0`），
  既无法复现重复，也无法证明"唯一键无法隔离口径"的实际后果。
- 保留的结论只有：**口径冲突本身有运行证据，需统一**。

**验收**：两来源口径统一；若有交集，同股票同日不得出现两种 `start_ts` 解释。

---

## P2 · R3-02 打分读取接口直接返回半年前 / 空数据，无陈旧标识

**位置**：`StockTrendScoringService.java`、`MultiDimScoringService.java` 的 `listScores`

**问题**：读取接口把旧数据与空数据**直接返回**，不带 `stale` 标识或可用性提示。

**证据**（Codex 数据库 + 真实 GET 双向确认）：

```text
multi_rows 0
trend_rows 3345，MIN/MAX=2026-04-07
GET trend/scores：trade_date=2026-04-07，total=3345
GET multi-dim/scores：trade_date=''，total=0
```

**修复范围**：只处理"仍在暴露的读取接口应带陈旧标识"或"明确定位为废弃"。
**未证明**这两个接口已影响当前 WATCHLIST/T1 推荐——**不要提升为推荐主因**。

**验收**：读取接口对陈旧/空数据返回明确的可用性状态。

**修复（2026-10-13，`e4e4fb8e`）**：新增 `com.quant.service.scoring.ScoreReadAvailability`，
接线到 `StockTrendScoringService` / `MultiDimScoringService` / `ComprehensiveScoringService`
三个 `listScores`，新增字段：

| 字段 | 含义 |
|---|---|
| `data_status` | `OK` / `STALE` / `EMPTY`（`EMPTY` = 该类型/因子版本**没有任何数据行**，与「结果为空页」区分） |
| `effective_trade_date` | 实际取到的打分日 |
| `requested_trade_date` | 调用方请求的日期（未指定为 `""`） |
| `expected_trade_date` | **库内最新行情日**，作为「本应到哪天」的参照（不靠本机时钟） |
| `staleness_days` | `effective` 落后 `expected` 的天数；无数据行为 `null` |
| `downgraded` | 调用方要了某天却取到更早一天（静默降级，绝对日期可能不旧也照样标出） |

**验证**：趋势表半年前数据 → `STALE` / `staleness_days=189`；0 行 → `EMPTY`
（`effective_trade_date=""`、`staleness_days=null`）；请求日被降级 → `downgraded=true`；
行情参照不可知 → 不凭空判定陈旧，返回 `OK`。取数逻辑未改动，只如实标注。
新增 11 条用例（纯函数判定 + 趋势/多维接口接线），Java 463 → 474 passed。

**仍未证明**（沿用原结论）：这两个接口是否影响当前 WATCHLIST/T1 推荐 —— 不要提升为推荐主因。

---

## P2 · R5-02 V1 对不支持意图类型的拒绝不写原因码

**位置**：`paper/risk.py` `check_intent` 的非 `BUY_LIMIT` 分支

**问题**：显式注入 `SELL_LIMIT` 意图时被 REJECT 且 `reject_reason=''`、`reason_codes=[]`
——拒绝**不可审计**。

**证据**（Codex 跑真实闸门）：

```text
SELL_LIMIT：REJECT，reject_reason=''，reason_codes=[]
NOOP：PASS，reject_reason=''，reason_codes=[]
V1 position0：BUY_LIMIT
V1 position1000：NOOP ALREADY_FILLED
```

**对原排查的纠正**：
- ❌ 原排查断言"非 BUY 一律 REJECT" —— 被 `NOOP` 反证。
- ❌ 原排查暗示"买得进卖不出、系统无出场能力" —— **未证实**。
  真实 `decide` 是**入场**路径，已有持仓只产 `NOOP`；watch 路径**有**卖出；
  当前又是 SHADOW。**不要按"买得进卖不出"定级。**

**修复方向**：仅作**防御性审计补全**——拒绝分支写入明确原因码
（如 `UNSUPPORTED_INTENT_TYPE_IN_V1`）。

**验收**：对 `SELL_LIMIT` 的拒绝携带非空 `reject_reason`。

---

## P2 · R7-02 午休期间 `valid_until` 与「下一根 bar 收盘」口径不一致

**位置**：`paper/watch/signal_adapter.py` 的 `valid_until` 计算

**问题**：`valid_until = min(end + period, end.replace(15:00))` 假设**下一根 bar 紧邻**，
但 A 股一天分两个不连续时段（09:30–11:30、13:00–15:00）。
`11:30` 那根 bar 的 `valid_until` 落在 `11:35`，而下一根 bar 实际是 `13:05`。
字段语义与 `invalidation_conditions` 里写的"下一根 bar 收盘"**不一致**。

**证据**（Codex 真实执行 adapter）：

```text
end11:30：valid_until11:35，seconds300.0
end14:55：valid_until15:00，seconds300.0
end15:00：valid_until15:00，seconds0.0
in_trading_window11:30 False；11:30:01 False
```

**对原排查的纠正（算术错误）**：
- ❌ 原排查断言"**14:55** bar 的信号有效窗口为 **0 秒**" —— **算错了**。
  `14:55` 结束的 bar 窗口是 **300 秒**；零窗口在 **15:00** 结束的末根。
- ❌ 原排查断言"午休还会同根撮合" —— 被 R7-03 与交易时段反证（见下）。

**修复方向**：把时段边界纳入计算（11:30 → 13:00），并**修正 `invalidation_conditions`
的文案**使语义一致。零窗口是否需要改，取决于"收盘后是否仍产出不可执行分析信号"的产品契约
——**不得为此放行收盘后交易**。

**验收**：各根 5 分钟 bar 的 `valid_until` ≤ 下一根 bar 的开始时刻；文案与实现一致。

---

## P2 · R5-01 配额顺序依赖 + 被拒信号不补发（机制确认，属策略政策）

**位置**：`paper/config.py`（`max_trades_per_cycle=3` / `max_trades_per_day=20`）、
`paper/watch/loop.py` 信号循环（按观察池顺序迭代，无排序）

**已确认的机制**（Codex 用真实 loop 复现）：

```text
4只金叉第四只MAX_TRADES_PER_CYCLE；倒序后换为另一只
下一bar全hold，没有补发
```

即：**名额按迭代顺序分配**，且**被拒信号不会在后续轮次补发**。

**对原排查的纠正（原探针有 bug）**：
- ❌ 原探针的金叉条件多写了 `s_curr > l_prev`，与策略定义
  （`s_prev <= l_prev and s_curr > l_curr`）不符。
- ❌ 原排查断言的"**生产有效信号损失 43%~80%**"**不能保留**：
  修正条件后当前 DB 复算为 `crosses=147 / above3=77 (0.5238)`（2026-10-12）、
  `crosses=91 / above3=59 (0.6484)`（2026-10-13），且这些金叉**尚未通过**
  资金/板块/持仓/覆盖率等风控，`above3` 不等于实际拒单数。
- ❌ 原排查所用缓存（25119 键）与当前 DB（24208 键）**不一致，缺 912 键**。
- 当前进程是 **SHADOW、Live=0**，每日提交计数为 0，日配额并未按原文方式消耗。
- **未证实**：策略收益变差、早盘信号更平庸、需追逐已失效信号。

**因此这是产品/策略政策问题**：先明确"配额按顺序消耗且不补发"是**有意策略**还是**待改行为**。
若确认为缺陷，可考虑按信号强度排序后再截断、或引入同日内延迟重试队列。
**不要依据已撤回的损失率直接放宽配额。**

---

# 二、需产品决策（事实已确认，改前须确认）

| 编号 | 事实 | 待决问题 |
|---|---|---|
| **R3-01** | 规则分阈值 35.0；值域 0–50，35 **可达**（实测最高 43.84，≥35 为 6/5452）；当前接口 `total_scored=1265`、`BELOW_RULE_SCORE_THRESHOLD=1265`、`status=NO_ELIGIBLE_CANDIDATE`；33 日过滤交集为 0 的有 14/33（排除涨停后 19/33） | 阈值该定多少？**取值域缩减不自动要求等比例降为 17.5**。属产品标定，调低后是否更准确**未被验证**。另注："19/33 为 0"不等于"恒空"，且 `TopN` 是上限不是配额 |
| **R4-03** | 默认禁买两创（`allow_chinext/allow_star=False`）；热门股 500 只中主板 295 / 创业板 144 / 科创板 36 / 北交所 25，两创合计 **180（36%）**；**无 `allow_bse` 开关**，北交所默认放行 | 该组合是遗漏还是有意？**不能仅从风险不对称推定**——默认禁两创是已声明策略。未证明"监控展示误导"，也不能以资源消耗为由要求过滤观察池。需风险策略约定后才可把"新增 BSE 开关"列为修复需求 |
| **R5-01** | 见上（配额机制已确认） | 是策略政策还是待改行为 |
| **R7-02 零窗口** | 15:00 末根 bar 的 `valid_until` 窗口为 0 秒 | 收盘后是否仍产出不可执行的分析信号 |
| **R1-04** | 末根 adapter 的 `valid_until` 为零**已确证**；但真实 `run_once` 在 15:00 已因交易时段返回 `SLEEPING`，**未走到信号失效分支** | 零窗口是否需改，取决于上一条的收盘契约。**原排查"尾盘信号被该分支彻底吞掉"的生产因果未被证明**；代码现有 `EXPIRED_BAR`/`INVALIDATED`/`SIGNAL` 留痕也与"无成因、彻底消失"不符 |

---

# 三、已证伪（无需修复，不要再排查）

| 编号 | 原排查断言 | 证伪证据 |
|---|---|---|
| **R4-01** | T1 无当日快照时观察池为空，**不报错、不兜底、静默空转** | 空池属实，但**已显式报错且 live 阻断**：`universe T1 t1_unavailable 0 ['T1_UNAVAILABLE']`、`R4-01 real loop BLOCKED`、errors 含 `DATA_SOURCE_BLOCKED`。**原排查遗漏了错误传播与 live 阻断**——根因是原排查读 `universe.py` 时漏看了紧随其后的 `errors.append("T1_UNAVAILABLE")` |
| **R6-04** | T1 判定状态**不持久化也不返回**，`load_from_t1` 质量闸是死代码 | 状态/覆盖率**已**存在 `ecology_json` 并由 GET 提到**顶层**：`status=NO_ELIGIBLE_CANDIDATE`、`threshold_version=t1_rule_v2`、`quality_status=PIT_LIMITED`、`daily_coverage=0.9978`。**原排查的 GET 探针把输出截断到 400 字符**，因而误判字段缺失 |
| **R7-03** | 用刚收盘 bar 的 close 决策并按**同一价格、同一根 volume** 成交，潜在前视 | 真实 OMS/Broker（真实 schema 的内存 SQLite）：**同根拒撮合**；下一根开盘 9.5 + 20bps = 9.519（限价 10），容量取**下一根** volume。代码/注释声明 `NEXT_BAR` |
| **COOLDOWN** | 冷却表 naive/aware 中毒 → 反向侧**永久**拦截（"买得进卖不出"） | 默认 clock/loop 为 **naive**，写入与恢复同契约；当前冷却表为空；默认 loop 时钟 `tzinfo=None`，往返后 16 分钟可卖。按日状态重置，**不是跨日永久**。（人工注入 aware 坏值仍可阻断，属输入污染的条件风险，未证实由生产入口产生） |

**另**：**R7-01b**（实盘跑 `-UniverseMode WATCHLIST` 而非默认 `T1`）经验证**订正确认一致**，无需修复。

---

# 四、待查（现象确认，成因未决；暂不构成修复项）

| 编号 | 现象 | 缺什么才能定论 |
|---|---|---|
| **R7-01** | `\QuantWatchShadow` 任务 `LastResult=1`（非零），而 launch 日志**无** `EXITCODE=` 行 | 需要调度事件/父子退出码证据。**不能据此判定循环已死**（进程实测存活）。另：Windows 无 T1 任务**不等于** Spring 调度器未注册——实测 `schedule API present=False`、`schedule_enabled=False`、`cron=0 10 15 * * MON-FRI`、`zone=Asia/Shanghai`，属**既定手动策略**，准确表述是"调度关闭且未运行" |
| **R1-04** | 见「需产品决策」 | 收盘边界的真实信号/事件记录 |
| **R1-05** | 同股票同日多来源重复/覆盖 | 来源重叠的历史快照（当前库无交集） |
| **R2-02** | 真实旧种子 + 人工构造复牌 bar 确实产生 `buy`；但**修改种子日期的新鲜对照同样 `buy`**，说明是 MA_CROSS 的数值交叉，**不能称为"伪造"**，也未证明表现变差 | 真实复牌首根行情 + 信号有效性标准。停牌/陈旧数据**资格**问题归 R2-01 |
| **R2-03** | `f124` 收盘后取值 15:34，足以反对"最后成交时间"；但 19 个取值/分组本身**不能证明**服务端精确刷新机制 | 官方字段定义或盘中同步成交对照。**保留字段认知订正，不把"40 秒抖动导致准确性损失"标为待修复** |
| **R6-01 后果** | "无法创建/回放会话" | 未调用会写库的会话接口，故该后果**仍仅阅读推断** |
| **R6-03** | 旧覆盖率闸门分母（5835 / 需 5544 / 单日最大 5477 / 最大比 0.9386）独立复算成立，但**源码已按活跃键修正**（`active_expected=5465`、`active_covered=5453`），当前快照 `daily_coverage=0.9978` **通过** | 这不是当前待修问题。原排查的"历史首因/恒阻断"仅引用代码注释，**非原作者运行证据**——引用注释不能算已证实 |

---

# 五、验证环境与复现方式

- **完整证据与内嵌探针**：`quant-accuracy-issues.archive-full.md`
  （含 Codex 的 `CODEX_PROBE_CORE / STALE / QUOTA / DB / API / F124 / V1` 七段可直接执行的探针）
- **原排查脚本**：`E:\Project\.tmp_quant_watch\`
- **运行前统一设置**：

  ```powershell
  [Console]::OutputEncoding=[System.Text.Encoding]::UTF8
  $env:PYTHONIOENCODING='utf-8'
  $env:PYTHONDONTWRITEBYTECODE='1'
  ```

- **证据时点**：北京时间 2026-10-13 收盘后 16:25–16:42。数据库与运行日志会持续变化，
  后续重跑**不应把时间差误判为原证据伪造**。
- **Codex 当时的只读约束**：未改业务代码/配置/schema/日历，未启动生产循环、未提交交易、
  未执行写库 POST、未 kill/重启/删除任务；loop 的文件状态与日志出口替换为内存替身，
  Broker 用真实 schema 的内存 SQLite。

---

# 六、方法学教训（记录以免重犯）

本轮排查最大的价值不在结论数量，而在**验证暴露的出错模式**。原排查 5 处实质错误中，
**4 处同源**：

| 出错模式 | 具体案例 |
|---|---|
| **用截断的输出下结论** | R6-04：GET 探针 `Substring(0,400)` 截断，据此断言"字段不存在"。实际有 37 个顶层字段 |
| **读代码时漏看紧随其后的几行** | R4-01：读了 `universe.py:262-269` 却漏看 `270-271` 的 `errors.append("T1_UNAVAILABLE")`，据此断言"不报错" |
| **把"我猜的"写成"已证实"** | R6-01 的"结构上无法""恒为 false"、R3-01 的"近乎恒空"——都是推断强度超过了证据 |
| **验证脚本本身有 bug 却未自检** | R5-01：金叉条件多写 `s_curr > l_prev`；缓存键与 DB 差 912 个却未核对 |
| **算术错误** | R7-02："14:55 窗口为 0 秒"实为 300 秒 |

**结论**：`已证实` 必须由**未经截断的完整输出**支撑，且脚本口径要与被测逻辑**逐条对齐**。
所有"仅阅读推断"的项都应显式标注，而不是与已证实项混排。
