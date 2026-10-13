# 量化系统 · 准确性问题清单

> 目标：找出**可能影响策略信号或推荐结果准确性**的问题，逐轮累积，供后续统一修改。
> 本轮阶段：**只分析、不改代码**。

## 方法论（重要）

本清单区分两种证据等级，**不要把两者当成同等可信**：

| 标记 | 含义 |
|---|---|
| **已证实** | 用真实数据/真实代码路径复现过，附可重跑的探针脚本 |
| **仅阅读推断** | 只来自读代码，尚未复现。可能自信但错误，修改前必须先复现 |

复现脚本统一放在 `E:\Project\.tmp_quant_watch\`。**修改任何一条之前，先重跑它的复现脚本确认结论仍然成立。**

---

# 第 1 轮：信号计算链路（K 线解析 → bar_store → 策略适配器 → 决策）

范围：从数据进入系统到信号产出并判定可执行性的完整路径。

---

## R1-01 [高] 东财 K 线标签是「终点」却被当作「起点」，预热序列系统性滞后一根

**状态：已证实**

<!-- CODEX_INLINE_R1-01_BEGIN -->
**Codex 验证（2026-10-13）：✅ 确认存在 —— 待修复**
> 复现：`py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_bar_semantics.py'；py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_asof_loss.py'`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> 同标签一致 10 处 / 不一致 0 处
> as_of=10:32：采纳最后标签 10:25；实际已知最后标签 10:30
> 全日最后一根 start_ts=15:00:00 end_ts=15:05:00
> 验证范围 / 订正：真实东财 1/5 分钟 payload 交叉对照并运行真实解析器；时间错位和 as_of 多丢一根均复现。待修复，优先统一区间语义。
<!-- CODEX_INLINE_R1-01_END -->

**位置**
- `modules/compute-python/paper/watch/kline_client.py:80`（`start = _parse_time(parts[0])`）
- `modules/compute-python/paper/watch/kline_client.py:88`（`end = start + timedelta(minutes=period_min)`）
- `modules/compute-python/paper/watch/kline_client.py:89`（`if end > as_of ... continue`）

**现象**
东方财富 `push2his` 的 `klt=5` 返回的**标签时间是该 bar 的结束时刻**（标签 `09:35` 表示区间 09:30–09:35），
但代码把标签当成起始时刻，于是每根 bar 的 `start_ts` 被声明为真实结束时刻、`end_ts` 再往后推一根。

**证据（两条独立验证）**

1. 用 1 分钟线交叉验证标签语义（`probe_bar_semantics.py`）：

```
5 分钟线首标签 : 2026-10-13 09:35      1 分钟线首标签 : 2026-10-13 09:31
逐根比对 5min(T).close vs 1min(T).close：10/10 完全一致
=> T 是该区间的终点（若 T 是起点，应等于 1min(T+5min).close）
```

2. 量化过滤器损失（`probe_asof_loss.py`）——三个盘中时点全部落后恰好一根：

```
as_of=10:03  解析器采纳最后一根 09:55  实际已知最后一根 10:00  丢弃 [10:00]
as_of=10:32  解析器采纳最后一根 10:25  实际已知最后一根 10:30  丢弃 [10:30]
as_of=14:58  解析器采纳最后一根 14:50  实际已知最后一根 14:55  丢弃 [14:55]
```

3. 全日最后一根：`start_ts=15:00` / `end_ts=15:05`（真实区间 14:55–15:00），**`end_ts` 越出 15:00 收盘**。

**影响**
1. **盘中补拉预热的票，MA 序列稳定落后一根（5 分钟）**——金叉/死叉判定整体延后一根。
   因为过滤器等价于 `label > as_of - period`，比正确判据 `label > as_of` 多丢一根。
2. 每根 bar 的 `end_ts` 比真实区间结束晚一个周期；末日 bar 的 `end_ts` 落在交易时段之外。
3. 与实时快照聚合出来的 bar（`bar_store` 用**桶起点**做 `start_ts`）口径不一致，
   同一真实区间在两条来源下 `start_ts` 不同（详见 R1-05）。

**修复方向**
`start = label - period`、`end = label`；过滤器改为仅当 `label > as_of` 才丢弃。
注意与 R1-02 的联动（见该条"修复陷阱"）。

**验收条件建议**
- 任意 `as_of` 下，解析器采纳的最后一根 == `max(label <= as_of)`；
- 每根 bar 满足 `end_ts - start_ts == period_min`；
- 全日最后一根 `end_ts == 15:00`（不越出收盘）。

---

## R1-02 [高] 盘中重启会在 bar 序列里留下 2 根断层

**状态：已证实**（`repro_gap.py`，用真实东财 payload + 真实 `BarStore`）

<!-- CODEX_INLINE_R1-02_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B 'E:\Project\.tmp_quant_watch\repro_gap.py'；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> 最后种子 start_ts=2026-10-13 10:25:00
> 快照10:35:10 closed=[]；10:40:10 closed=['10:35']
> 相邻 start_ts 10:25 -> 10:35，10分钟
> corrected seed + discard True ['10:20', '10:25', '10:35'] duplicate_start_ts 0
> 验证范围 / 订正：10:32 场景确有两个真实区间未进入序列，待修复该场景。不是任意启动都恒缺两根。原“只修标签会产生重复 start_ts”的修复陷阱被反证：内存中同时改正 start/end 与 as_of 采纳，仍保留 discard=True，得到无重复序列，仅首个不完整实时桶缺失；不要据此错误陷阱设计工单。
<!-- CODEX_INLINE_R1-02_END -->

**位置**
- `kline_client.py:89`（错误过滤丢掉本已收盘的 `label == as_of` 那根）
- `loop.py:610 / 749 / 807 / 1047`（4 处 `bar_store.seed(..., discard_first_forming=True)`）
- `bar_store.py:180-183`（`_discard_next_closed` 无条件丢弃重启后第一个收盘桶）

**证据**

```
重启时刻 as_of=2026-10-13 10:32
  预热采纳 155 根，最后一根 start_ts=10:25
  快照 10:35:10 -> closed=[]        （10:30 桶被 discard_first_forming 丢弃）
  快照 10:40:10 -> closed=['10:35']
最终 history 末尾：
  10:20 -> 10:25  5 分钟
  10:25 -> 10:35  10 分钟   <-- 断层
缺失区间：10:25-10:30（被 as_of 过滤器丢）与 10:30-10:35（被 discard 丢）
```

**影响**
- 序列出现空洞，MA 窗口跨越断层（`sma` 无法感知缺失，只是把两侧价格接起来平均）；
- 落库的分钟线同样有洞，影响后续任何基于本地分钟线的统计或回测；
- **盘中重启是常规操作**（今天这个 session 就重启了多次），不是极端场景。

**修复陷阱（务必注意）**
R1-01 的 off-by-one 过滤器**恰好"意外地"避免了 `start_ts` 重复**：
因为它丢了 `label == as_of` 那根，所以种子序列的最后一根（`start_ts = as_of - period`）
不会与重启后第一个实时桶（`start_ts = floor(as_of)`）撞时间戳。
**如果只修 R1-01 而不动 `discard_first_forming`，断层会变成「同一 `start_ts` 两根 bar」
——从"缺数据"恶化为"重复且语义冲突的数据"，可能更糟。两条必须一起改。**

**修复方向**
1. 修正 R1-01 的标签语义与过滤器；
2. `discard_first_forming` 改为**条件判断**：仅当首个快照距桶起点足够远（即该桶确实只是残段）才丢弃；
3. 更稳妥：种子已覆盖到当前桶时，不丢首个实时桶，而是用种子值续接。

**验收条件建议**
对任意 `as_of` 做一次"重启仿真"：生成的 history 中相邻 `start_ts` 间隔恒为 `period_min`
（除真实休市间隔），且无重复 `start_ts`。

---

## R1-03 [中] 开盘前启动时，当日第一根**完整** bar 被无条件丢弃

**状态：已证实**（`probe_round1b.py`）

<!-- CODEX_INLINE_R1-03_BEGIN -->
**Codex 验证（2026-10-13）：✅ 确认存在 —— 待修复**
> 复现：`py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_round1b.py'`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> 快照09:30:05 closed=[]
> 快照09:35:05 closed=[]
> 快照09:40:05 closed=['09:35']
> 今日 history=['09:35']；首根完整桶未保留
> 验证范围 / 订正：运行真实 BarStore，seed(discard_first_forming=True)，从开盘首个快照跨桶推进，首桶确实丢弃。待修复；确认范围为该种 seed 路径，不覆盖恢复 checkpoint 或显式离线 seed 等免丢弃路径。
<!-- CODEX_INLINE_R1-03_END -->

**位置**
- `loop.py:610 / 749 / 807 / 1047`（`discard_first_forming=True`）
- `bar_store.py:180-183`

**现象**
`discard_first_forming` 的设计理由是注释所写"启动时的首个桶只代表从启动时刻到桶尾的残段"。
但循环通常在**开盘前**（如 09:25）就已启动，此时 09:30–09:35 这根桶是**完整的**，仍被丢弃。

**证据**

```
预热注入 25 根后，模拟 09:30 开盘起的实时快照：
  快照 09:30:05 -> closed=[]
  快照 09:35:05 -> closed=[]          （09:30 桶被丢弃）
  快照 09:40:05 -> closed=['09:35']
今日入 history 的 bar: ['09:35']
=> 09:30-09:35 这根完整 bar 未保留
```

**影响**
每个交易日、每只票稳定损失 1/48 根 bar。开盘首根信息量最大
（隔夜跳空后的第一个均线点），损失它对开盘后不久的交叉判定最不利。

**修复方向**
按"首个快照距桶起点的时间差"判定桶是否完整（例如差值小于若干个快照间隔即视为完整），
而不是无条件丢弃。

**验收条件建议**
开盘前启动的仿真中，当日 history 应从 09:30 开始且相邻间隔恒为 `period_min`。

---

## R1-04 [中] 当日最后一根 bar 的信号有效期恒为 0，一诞生即被判定失效

**状态：已证实**（`probe_round1b.py`）

<!-- CODEX_INLINE_R1-04_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_round1b.py'；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> bar_ts=2026-10-13T15:00:00+08:00
> valid_until=2026-10-13T15:00:00+08:00
> end15:00 action buy seconds0.0
> at15:00 SLEEPING signals []
> 验证范围 / 订正：末根 adapter 有效期为零确证。但真实 run_once 在15:00已因交易时段返回 SLEEPING，未到信号失效分支；原“尾盘信号被该分支彻底吞掉”的生产因果没有证明。代码现有 EXPIRED_BAR、INVALIDATED 和 SIGNAL 留痕也与原“无成因、彻底消失”不符（后两项仅阅读核对）。零窗口是否需改须明确收盘后是否仍产出不可执行分析信号；不得为此放行收盘后交易。
<!-- CODEX_INLINE_R1-04_END -->

**位置**
- `paper/watch/signal_adapter.py:114-115`（`valid_until=min(end + period, end.replace(hour=15, ...))`）
- 消费方 `paper/watch/loop.py:2367`（`if decision_time >= valid_until and action in {buy, sell}`）

**现象**
对 14:55–15:00 这根 bar：`end + period = 15:05`，`end.replace(15:00) = 15:00`，
取 min 得 `valid_until = 15:00`，**恰好等于 `bar_ts` 自身**。

**证据**

```
bar_ts      = 2026-10-13T15:00:00+08:00
valid_until = 2026-10-13T15:00:00+08:00
有效期长度 = 0 秒
```

**影响**
该 bar 上产生的任何买卖信号在 `loop.py:2367` 立刻被压成 `hold` 并标记 `INVALIDATED`，
**用户看不到尾盘信号**——不是"看到但标注不可执行"，而是彻底消失。

**说明**
"收盘后不可交易"本身是合理的产品决策，但当前实现把成因混在一起了：
用户无法区分"本来就没信号"和"有信号但被有效期吞掉"。
`loop.py:2384-2392` 的注释显示作者已经意识到"失效成因无法区分"这类问题，
但本条是它的另一个具体表现。

**修复方向**
末日 bar 的 `valid_until` 应显式表达"收盘即失效"的语义（例如直接取 15:00 但让判定使用
`decision_time > valid_until` 且容忍收盘后的处理延迟），或明确接受该行为并在信号里留痕。

**验收条件建议**
末日 bar 产出买卖信号时，若信号确实在 15:00 之后被判失效，`reason_codes` 必须含
`EXPIRED_BAR`，且监控页能呈现"有信号但因收盘失效"，而不是只显示 `hold`。

---

## R1-05 [中] 同一真实区间在主库里存在两种 `start_ts` 口径

**状态：口径不一致已证实；"重复行"后果为阅读推断，需实测确认**

<!-- CODEX_INLINE_R1-05_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"；py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_asof_loss.py'`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> eastmoney_kline 25040 行，start_ts 最大15:00
> eastmoney_batch 80 行，start_ts 最大14:50
> shared_code_day 0
> shifted_pairs 0
> 验证范围 / 订正：解析器/实时路径口径冲突有运行证据，统一口径待修复。但当前两个来源没有任何同股票同日交集，无法实测原断言的“同一真实区间在主库有两种口径/重复行”；唯一键无法隔离口径是推断，不是已发生重复。需来源重叠的只读历史快照或获准的隔离写入复现，才能确认重复/覆盖后果。
<!-- CODEX_INLINE_R1-05_END -->

**位置**
- 实时路径：`bar_store.py:112-113`（`start_ts` = 桶起点）→ `loop.py:2172-2173` 推送
- 预热/回填路径：`kline_client.py:80,88`（`start_ts` = 标签 = 真实终点）→ 回填脚本直接推送
- 落库表：`market_data_minute`，唯一键 `uk_mdm_code_period_start (stock_code, period_min, start_ts)`

**现象**
同一个真实区间 09:30–09:35：
- 实时 live bar 存为 `start_ts = 09:30`（起点口径）
- 东财 K 线存为 `start_ts = 09:35`（终点口径）

**影响**
1. 唯一键按 `start_ts` 去重，**同一区间两条口径互不覆盖 → 可能产生重复行**；
2. 读取方（含本轮之前的"本地优先预热"路径）无法判断一个 `start_ts` 到底代表哪个区间，
   任何"相邻两根间隔应为 5 分钟"的连续性校验都会被这种混用误伤。

**触发条件（阅读推断，待实测）**
需要同一天同时存在"实时写入"和"东财回填/预热写入"。
当前 `loop.py:2172` 只推 `new_closed`（实时），预热 bar 不进库，所以尚未触发；
但**"收盘后跑一次回填修复缺口"这个用法会直接触发**（回填写东财口径，与当日实时口径并存）。

**修复方向**
统一到一种口径（建议全部统一为"起点"口径，与实时路径一致），并在表上或读取侧做防御；
或改用 `end_ts` 作为唯一键成分。

**验收条件建议**
同一交易日的同一区间，无论经哪条路径写入，`start_ts` 唯一且相邻间隔恒为 `period_min`。

---

## 已核对为一致（无需处理，记录以免重复排查）

| 项 | 结论 |
|---|---|
| Python `utils.indicators.sma` vs Java `TechnicalIndicators.sma` | 口径一致：均为前 `period-1` 个 NaN + 完整窗口算术均值。Java 注释显示历史上曾返回原始价，已修正 |
| `BarStore.required_bars` vs `SignalAdapter.required_bars`（MA_CROSS） | 公式一致：`max(long+1, short+2)` = 21 |
| `MA_CrossStrategy` 的交叉判据 | 用 `dropna() >= 2` 守卫长均线仅 1 个有效值的情形，避免"交叉永不触发"，逻辑正确 |
| Python `ema` 的种子 | 显式用前 `span` 根 SMA 作种子（与 Java 一致），未误用 pandas `ewm(adjust=False)` |

---

## 第 1 轮小结

| 编号 | 严重度 | 状态 | 一句话 |
|---|---|---|---|
| R1-01 | 高 | 已证实 | 东财 K 线标签是终点却当起点 → 预热序列滞后一根 |
| R1-02 | 高 | 已证实 | 盘中重启 → bar 序列 2 根断层（且与 R1-01 有修复联动陷阱） |
| R1-03 | 中 | 已证实 | 开盘前启动时当日首根完整 bar 被无条件丢弃 |
| R1-04 | 中 | 已证实 | 末日 bar 信号有效期 0 秒，一诞生即失效 |
| R1-05 | 中 | 部分证实 | 主库中同一区间存在两种 `start_ts` 口径 |

**R1-01 + R1-02 应作为一个原子改动处理**，单独改任何一条都会恶化另一条。

---

# 第 2 轮：数据源正确性

范围：复权口径、停牌/涨跌停、快照新鲜度与 `quality_status`、行情时间与本地时钟、
成交量单位，以及**预热数据的可信度校验是否完备**。

---

## R2-01 [高] 网络抓取路径没有日期闸门，导致本地闸门被"绕过"

**状态：已证实**

<!-- CODEX_INLINE_R2-01_BEGIN -->
**Codex 验证（2026-10-13）：✅ 确认存在 —— 待修复**
> 复现：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_STALE_BEGIN\n(.*?)^# CODEX_PROBE_STALE_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> network result 30 dates ['2026-09-30']
> allowed ['2026-10-12', '2026-10-13'] usable False
> real prepare ready True errors [] local_hit 0
> 验证范围 / 订正：先真实抓取002388，再把完全相同返回值分别注入本地分钟线和网络客户端替身，运行真实 prepare：本地拒绝，网络接受并ready=True。文件状态与日志用内存替身隔离。待修复网络日期闸门；结论不依赖仅阅读推断。
<!-- CODEX_INLINE_R2-01_END -->

**位置**（`paper/watch/loop.py` 的 `prepare()`，三条预热来源的校验强度不一致）

| 来源 | 代码位置 | 是否有 `_series_is_usable` 校验 |
|---|---|---|
| SQLite 持久化 bar | `loop.py:505` | ✅ 有 |
| 主库本地分钟线 | `loop.py:555` | ✅ 有 |
| **外部网络抓取** | **`loop.py:594`**（`prepared_bars[code] = list(bars)`） | ❌ **没有** |

**现象**
本地闸门一旦拒绝一条陈旧序列（例如停牌股），代码就回退到外部 K 线补拉
（这本是设计意图："宁可多一次网络请求，不可用可疑序列"）。
**但外部数据源与本地同源**——本地那份数据当初就是从同一个接口抓来的——
所以回退拿到的是一模一样的陈旧序列，而这条路径**不做任何校验**，直接采信。

**结果：闸门对"最需要它拦截的那类标的"完全无效。**

**证据**（`probe_unguarded_fetch.py`）

```
=== 单只停牌股详查 002388 ===
  网络返回 30 根，日期集合 ['2026-09-30']
  时间范围 2026-09-30 11:05:00 .. 2026-09-30 15:00:00
  tail-21 日期 = ['2026-09-30']
  _series_is_usable(网络序列) = False   <-- 闸门会拒绝
  但 adapter.ready() = True             <-- 却被判为『预热就绪』（只看根数不看日期）

=== 全池扫描（200 只）===
  **尾部不在允许窗口**  : 1 只
      002388  30 根  tail 日期 ['2026-09-30']
```

**影响**
`adapter.ready()` 只判根数（`seeded_count >= required`），不判日期，
所以陈旧序列会被标为"预热就绪"，并据此进入信号判定。
当前直接暴露 1/200 只，但**任何停牌后复牌、或数据源短暂返回旧快照的标的都会走这条路**。

**修复方向**
1. 把 `_series_is_usable` 同样应用到网络抓取结果（第 594 行），不通过则**不进入 `prepared_bars`**，
   标记为不可预热并上报事件；
2. `adapter.ready()` 增加日期维度的就绪判定，或至少在 `ready` 为真但序列尾部不在允许窗口时降级；
3. 注意：拒绝后**不应再回退到同一个数据源**，否则会形成"拒绝→回退→采信"的死循环。

**验收条件建议**
构造一条尾部日期在允许窗口外的序列，走**三条来源**中的任意一条，
`adapter.ready()` 都必须为 False，且不产生任何 buy/sell 信号。

---

## R2-02 [高] 停牌股复牌首根 bar 会被伪造出买入信号

**状态：已证实**（真实 2026-09-30 数据 + 真实 `SignalAdapter`）

<!-- CODEX_INLINE_R2-02_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_STALE_BEGIN\n(.*?)^# CODEX_PROBE_STALE_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> synthetic resume 0.03 9.37 buy ma_short_prev=9.1 ma_long_prev=9.1
> synthetic resume 0.05 9.55 buy
> synthetic resume 0.08 9.83 buy
> fresh-date control action buy
> 验证范围 / 订正：真实旧种子加人工构造复牌 bar 确实产生buy，但复牌 bar 非真实成交；修改种子日期的新鲜对照同样buy。MA_CROSS只看价格，数值交叉符合策略定义，不能把它直接称为伪造或证明表现变差。停牌/陈旧数据资格问题归R2-01；真实复牌首根及风险影响尚缺实盘数据和信号有效性定义。
<!-- CODEX_INLINE_R2-02_END -->

**位置**
- 根因同 R2-01（陈旧序列被采信）；
- 机制位置 `strategies/ma_cross_strategy.py:55`（`s_prev <= l_prev and s_curr > l_curr`）

**现象**
停牌股的种子序列全部是停牌前最后一个交易日的数据，MA5 与 MA20 **都等于同一个旧价**（种子态"平坦"）。
复牌当日只要价格高于旧价，MA5 就比 MA20 涨得快 → `s_prev <= l_prev` 且 `s_curr > l_curr`
→ **在复牌第一根 bar 上直接触发金叉买入**。这个"金叉"完全由停牌缺口造成，不代表任何真实趋势。

**证据**（`probe_suspension_gap.py`）

```
002388 最后成交日 2026-09-30，收盘 9.1，共 30 根
复牌价 9.37 (+3%) -> action='buy'
    detail={'ma_short_prev': 9.1,   'ma_short_curr': 9.154,
            'ma_long_prev': 9.1,    'ma_long_curr': 9.1135}
复牌价 9.55 (+5%) -> action='buy'   (ma5_prev==ma20_prev==9.1)
复牌价 9.83 (+8%) -> action='buy'
```

`ma_short_prev == ma_long_prev == 9.1` 直接暴露了机制：判定基准线是平坦的旧种子态。

**影响**
复牌日是信号**最不可信**的时点（隔夜跳空 + 信息不对称 + 流动性异常），
系统却恰好在此时给出买入信号，且带上 `ready=True` 与正常信号的全部字段，
用户在监控页无法与真实金叉区分。

**修复方向**
同 R2-01 的修复（拒绝尾部不在允许窗口的序列）即可消除本条；
另建议显式识别"序列尾部交易日 != 上一交易日"的标的，标记为 `SUSPENDED` 并暂停其信号。

**验收条件建议**
对一条停滞 N 个交易日的序列 + 复牌首根 bar，`on_closed_bar` 必须不返回 buy/sell。

---

## R2-03 [低·已量化] `f124` 是服务端逐股刷新时间，不是成交时间

**状态：已证实**

<!-- CODEX_INLINE_R2-03_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_F124_BEGIN\n(.*?)^# CODEX_PROBE_F124_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> 取回60只；不同f124取值19个
> 1791876867=2026-10-13 15:34:27，覆盖8只
> 1791876843=2026-10-13 15:34:03，覆盖6只
> 1791876885=2026-10-13 15:34:45，覆盖5只
> 验证范围 / 订正：收盘后15:34时间值足以反对“普通A股最后成交时间”。但19个值/分组本身不能证明服务端的精确刷新机制；没有抓到盘中边界错桶。保留字段认知订正，不能把“40秒抖动导致准确性损失”标待修复。精确语义需官方字段定义或盘中同步成交对照。
<!-- CODEX_INLINE_R2-03_END -->

**位置**
- `paper/quote_client.py:60-71`（`_market_timestamp`）、`:178`（`market_time = _market_timestamp(f124)`）
- 消费方：`loop.py:2068`（用 `market_time` 分桶）、`parse_query.py:244`（`freshness = received_at - market_time`）

**现象**
`f124` 常被理解为"最后成交时间"，实测显示它是**服务端按批刷新的时间戳**。

**证据**（`probe_f124_volume.py`）

```
取回 60 只，不同 f124 取值的个数: 19
  f124=...867 (15:34:27) 覆盖 8 只
  f124=...843 (15:34:03) 覆盖 6 只
  f124=...885 (15:34:45) 覆盖 5 只
  ...
```

60 只标的只出现 19 个不同取值、按 2~8 只分组、跨度约 42 秒 ——
这是"分批刷新"的特征，而非逐笔成交时间（逐笔的话几乎每只都应不同）。

**影响**
1. `quote_stale_sec = 120`（`config.py:42`）度量的是**服务端数据刷新滞后**，不是行情新鲜度；
2. 分桶用的 `market_time` 带有最多约 40 秒的刷新抖动，
   在桶边界附近（如 09:34:58 vs 09:35:02）可能把同一快照归入相邻桶。

**严重度评估**
当前阈值 120s 远大于实测的 42s 抖动，**尚不构成实际问题**。
风险在于服务端高负载时抖动放大，或有人误以为 `freshness` 反映成交活跃度。
本条的登记目的是**纠正字段语义认知**，不建议现在改阈值。

**验收条件建议**
（暂不需要）若后续要改，应改为"校验分桶时间与本地时间的偏差上限"，
而不是简单调大 `quote_stale_sec`。

---

## 已核对为一致（无需处理，记录以免重复排查）

| 项 | 结论与证据 |
|---|---|
| 成交量单位 | 行情 `f5 × 100`（股）与 K 线 `f6 × 100`（股）实测一致。6 只抽样比值 0.999~1.000（`probe_f124_volume.py`） |
| 分桶时间口径 | `update_snapshot(..., quote.market_time or quote.received_at or current)`，**用交易所时间而非本地接收时间**，注释明确说明避免网络延迟错桶。设计正确 |
| 涨停硬闸是否生效 | `derive_limit_up_price` 是**原地修改** `quote` 对象并返回，因此 `decide_watch.py:66` 那行虽然把返回值赋给了局部变量，副作用仍然生效；`risk.py:106-108` 的涨停闸门（`limit_up_price is None → LIMIT_DATA_UNAVAILABLE`；`limit_price >= limit_up_price → 拒绝`）确实可达 |
| `price_jump_pct = 35%` | 高于所有板块的单日涨跌幅上限（主板 10% / 创业板科创板 20% / 北交所 30%），作为"不可能跳变"的判据合理 |
| 复权口径 `fqt=0` 跨除权日 | **风险当前未兑现**：200 只中 **0 只**在最近 40 根内存在 `fqt=0` 与 `fqt=1` 的收盘价分歧，即当前无标的跨除权日（`probe_fqt.py`）。除权高峰季需重跑该探针 |
| 预热序列内部跨交易日断层 | **阴性结果**：200 只中 **0 只**在抓取到的 30 根序列内存在跨交易日断层（`probe_suspension_gap.py` B/C 段）。即正常标的不存在这类断裂 |

---

## 第 2 轮小结

| 编号 | 严重度 | 状态 | 一句话 |
|---|---|---|---|
| R2-01 | 高 | 已证实 | 网络抓取路径无日期闸门，本地闸门被回退路径绕过 |
| R2-02 | 高 | 已证实 | 停牌股复牌首根 bar 伪造买入信号（+3% 即触发） |
| R2-03 | 低 | 已证实 | `f124` 是服务端刷新时间，非成交时间（当前未构成问题） |

**R2-01 与 R2-02 是同一根因的两个表现**：R2-01 是"闸门缺失"，
R2-02 是"闸门缺失的后果"。修 R2-01 即消除 R2-02。

**与第 1 轮的关联**：R1-01/R1-02（时间戳口径与断层）影响的是**所有标的**的信号时序，
R2-01/R2-02 影响的是**特定标的**（停牌复牌）的信号真伪。两类问题的修复位置不重叠，
可以并行处理，但都应在改数据源口径时一并设计验收条件。

---

# 第 3 轮：候选池与 T1 选股逻辑

范围：规则分构成与值域、`RULE_SCORE_THRESHOLD` 合理性、流动性闸门单位与量级、
T1 候选长期为空的根因、打分表子系统的数据新鲜度。

---

## R3-01 [高] 规则分阈值 35 与公式实际值域 0–50 不匹配，T1 候选结构上近乎恒为空

**状态：已证实**（33 个交易日 × 5,453 只的公式精确复现）

<!-- CODEX_INLINE_R3-01_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B 'E:\Project\.tmp_quant_watch\score_dist.py'；py -3.13 -B 'E:\Project\.tmp_quant_watch\gate_multi_day.py'；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> 最高43.84；>=35为6/5452（0.11%）
> 33日过滤交集为0：14/33；排除涨停后为0：19/33
> 排除涨停后最大2，中位0
> 当前接口 total_scored=1265，BELOW_RULE_SCORE_THRESHOLD=1265，status=NO_ELIGIBLE_CANDIDATE
> 验证范围 / 订正：稀疏和当日空候选确证，保存快照诊断能定位到阈值；不过35在0–50内可达，取值域缩减不自动要求等比例降为17.5。19/33也不是“恒空”，TopN为上限。阈值设计错误及调低后准确性改善未被验证，属于产品标定决策。原脚本复算的是数值过滤上界，不能冒称完整Java生成流程。
<!-- CODEX_INLINE_R3-01_END -->

**位置**
- `RULE_SCORE_THRESHOLD = 35.0`（`T1CandidateService.java:85`，硬编码）
- 分数公式 `scoreT1`（`T1CandidateService.java:806-814`），`RULE_SCORE_MAX = 50.0`（:90）
- 闸门应用点 `applyEligibilityGate`（`:440`）

**分数公式**（可精确复现，`ret5`/`maPos`/`vr` 口径见 `:854-870` 与 `LimitUpEcologyService:381-398`）

```
score = clamp(ret5*40, -15, 20)      # 动量
      + clamp((vr-1)*10, -10, 15)    # 量能
      + clamp(maPos*15, -10, 15)     # 趋势位置（相对 MA20）
score = clamp(score, 0, 50)
```

**实测分布**（2026-10-12，5,452 只全市场，**未施加任何预过滤——这是合格池的上界**）

| 分位 | 分数 |
|---|---|
| 最高 | 43.84 |
| p99.9 | 35.25 |
| p99 | 21.15 |
| p95 | 12.80 |
| p90 | 7.36 |
| **中位数** | **0.00** |
| 均值 | 2.11 |

**阈值 35 落在约 99.94 分位**：全市场仅 **6 只（0.11%）** 达到。

**33 个交易日的交集统计**（修正口径：必须取两个过滤器的**真正交集**）

| 交易日指标 | 中位 | 最大 | 为 0 的天数 |
|---|---|---|---|
| 可打分标的 | 5,453 | — | 0 |
| 通过流动性闸（≥3 亿/日） | 1,402 | — | 0 |
| 规则分 ≥ 35 | **3** | 12 | 1/33 |
| **交集（过闸 & ≥35）** | **1** | 3 | **14/33** |
| **再排除收盘涨停** | **0** | **2** | **19/33** |

而 `DEFAULT_TOP_N = 10`（`T1CandidateService.java:61`）。
**即：请求 TopN=10，合格数上界中位为 0、历史最好为 2。**

**单日完整实证（2026-10-12）**

| 代码 | 分数 | 20 日均额 | 当日涨幅 | 落点 |
|---|---|---|---|---|
| 002242 | 43.84 | 39,152 万 | +9.98% | **涨停收盘 → 被排除** |
| 600241 | 43.57 | 11,335 万 | +10.03% | **涨停收盘 → 被排除** |
| 600825 | 43.23 | 11,067 万 | +9.98% | **涨停收盘 → 被排除** |
| 002058 | 41.01 | 9,669 万 | +3.52% | 未过流动性闸 |
| 300530 | 39.48 | 17,614 万 | +2.28% | 未过流动性闸 |
| 920627 | 35.25 | 5,568 万 | −4.88% | 未过流动性闸 |

→ 最终合格 **0 只**，与库中 `t1_candidate.candidates_json = "[]"` 完全吻合。

**根因：阈值未随公式值域一起重标定**

`scoreT1` 的 javadoc（`:796-804`）自己写明：

> 已移除「涨停基因」项（D-T1-01）……保留它们会让「公式声称的可行域 0-100」与
> 「实际可行域 0-50」长期不一致，**并使按 0-100 设置的阈值既不可达也不可解释**。

公式已从 0–100 降为 0–50，**但 `RULE_SCORE_THRESHOLD` 仍停留在为 0–100 设定的 35**
（等价于 0–50 尺度上的 70% 分位）。这与已修复的流动性闸单位错配
（`30_000_000` → `30_000`，`ScoringUtils.java:47-56`）属于**同一类"阈值与量纲/值域不匹配"缺陷**。

**为何两个分项实际也拿不满**
- 动量要拿满 20 需 `ret5 ≥ 50%`（5 日涨 50%）
- 量能要拿满 15 需量比 ≥ 2.5
- 趋势要拿满 15 需 `maPos ≥ 100%`（价格是 MA20 的两倍）→ 实际最大仅 14.74

**修复方向**
1. **优先**：把 `RULE_SCORE_THRESHOLD` 按 0–50 值域重新标定（原 35/100 的严格度对应 17.5/50），
   并把它外置为可配置项而非硬编码常量；
2. 标定前先用本清单的复现脚本扫一遍历史分位，选一个"每日能出 5–20 只"的阈值；
3. 保留并暴露 `maxRuleScore`（`:458-460`）与 `rejectedByReason` 到监控页——
   当前这两个诊断字段已实现但只在接口响应里，日常看不到。

**验收条件建议**
用 ≥20 个历史交易日回放，每日合格数应为"个位数到几十"的正数；
且**不允许**出现连续 5 个交易日合格数为 0。

---

## R3-02 [中] 打分表子系统静默失效：一张空表、一张停在半年前，且读取无陈旧保护

**状态：已证实**（表数据状态与读取路径均已核实）

<!-- CODEX_INLINE_R3-02_BEGIN -->
**Codex 验证（2026-10-13）：✅ 确认存在 —— 待修复**
> 复现：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_API_BEGIN\n(.*?)^# CODEX_PROBE_API_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> multi_rows 0
> trend_rows 3345，MIN/MAX=2026-04-07
> GET trend/scores：trade_date=2026-04-07，total=3345
> GET multi-dim/scores：trade_date=''，total=0
> 验证范围 / 订正：数据库和真实GET同时证实旧/空数据被直接返回且无stale标识，待修复仍在暴露的读取接口或明确废弃定位。未证明这两个接口已影响当前WATCHLIST/T1推荐；不提升为推荐主因。
<!-- CODEX_INLINE_R3-02_END -->

**数据状态**（`ANALYZE`/直接 count 核实）

| 表 | 行数 | 最新 `trade_date` |
|---|---|---|
| `stock_multi_dim_score` | **0** | **无任何数据** |
| `stock_trend_score` | 3,345 | **2026-04-07**（仅 `TREND` 类型，**无 `COMPREHENSIVE` 行**）|
| `market_data` | 2,559,139 | 2026-10-12 ✅ 新鲜 |

即：行情数据是新的，**打分表是空的和半年前的**。

**读取路径无陈旧保护**（已证实）
- `StockTrendScoringService.listScores`（`:58-91`）：`d = findMaxTradeDateByScoreType(type).orElse(null)`，
  再 `out.put("trade_date", d.toString())`；
- `MultiDimScoringService.listScores`（`:61-92`）：同构。
- 两者**都没有相对当前日期的陈旧上限，也不返回 `stale` 标记**。
  不传 `tradeDate` 时，趋势打分接口会以「最新」的名义返回 **2026-04-07** 的数据。

**严重度评估（下调理由）**
`MultiDimScoringController` 与 `TrendScoringController` 确实已暴露为 API，
但**前端代码中对二者无任何引用**（已核实），且 T1 候选完全不依赖这两张表
（T1 直接从 `market_data` 现算）。因此**对当前推荐准确性的实际影响为 0**。
登记为**潜在陷阱**：任何后续把这些表接入推荐或展示的人，都会拿到空数据或半年前的数据，
而接口不会给出任何陈旧警告。

**修复方向**
1. 明确这两张表的定位：若已废弃，删接口或标注 deprecated；若仍要保留，补一个调度器并加陈旧断言；
2. 所有 `findMaxTradeDate()` 式回退都加一条"距当前交易日不得超过 N 个交易日"的断言，
   超限时返回空并附 `stale=true`，而不是静默返回旧数据。

---

## 已核对为一致 / 阴性结果（含一条被我自己证伪的假设）

| 项 | 结论与证据 |
|---|---|
| 流动性闸门单位与量级 | **正确，非问题**。`DEFAULT_MIN_AVG_AMOUNT = 30_000` 万元 = 3 亿/日，与 `market_data.amount` 实测单位（万元）自洽；过闸率 1,311/5,453 = **24%**，量级合理。该 bug 历史上已修（`30_000_000` → `30_000`），代码注释完整记录了论证过程 |
| **我的第一个假设被证伪** | 我最初推测"规则分与流动性天然反相关，导致交集必然为空"。实测 `corr(rule_score, log10(20日均额)) = **−0.021**`，**接近零，不构成反相关**。正确表述是：两个过滤器各自都在取自身分布的**极尾部**（0.055% 与 24%），尾部小样本的偶然重叠使交集近乎为空，**而非条件互斥**。此更正已反映在 R3-01 的表述中 |
| `avgAmount20` 口径 | 只累计最近 20 根**非零成交量** bar（`ScoringUtils.java:280-288`），停牌日不计入分母。口径正确 |
| TopN 语义 | "TopN 是上限不是配额，合格数不足时原样返回不补足"（D-T1-02 已修，`:461-464`）。行为正确——正是它让"合格数 0"如实透出为 `[]`，而不是伪造候选 |

---

## 第 3 轮小结

| 编号 | 严重度 | 状态 | 一句话 |
|---|---|---|---|
| R3-01 | 高 | 已证实 | 规则分阈值 35 未随值域 0–100→0–50 重标定，T1 候选 19/33 天为 0 |
| R3-02 | 中 | 已证实 | 打分表子系统静默失效（空表 + 半年前），读取路径无陈旧保护 |

**与前两轮的关系**：R1/R2 影响的是**已有信号的正确性**；R3-01 影响的是**根本产不出信号**——
性质不同，优先级更高（一个恒为空的推荐列表无法通过修 K 线口径来改善）。

**关于"用户说不改逻辑"的说明**：R3-01 的修复会改变推荐结果的数量级，
属于产品决策而非缺陷修复。本轮**只登记、给出标定方法论与验收条件，不提议直接改阈值**。

---

# 第 4 轮：风控、仓位与候选池装载

范围：`risk.py` 各硬闸口径、仓位与推荐金额、板块策略、候选池装载与兜底路径。

---

## R4-01 [高] T1 模式缺少当日候选快照时，观察池为空且**不报错、不兜底**（静默空转）

**状态：已证实**（实测接口 + 实测装载路径）

<!-- CODEX_INLINE_R4-01_BEGIN -->
**Codex 验证（2026-10-13）：❌ 证伪 —— 无需修复（原结论对当前代码/运行范围有误）**
> 反证：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_API_BEGIN\n(.*?)^# CODEX_PROBE_API_END',t)[1])"；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> universe T1 t1_unavailable 0 ['T1_UNAVAILABLE']
> R4-01 real loop BLOCKED
> errors包含T1_UNAVAILABLE
> errors包含DATA_SOURCE_BLOCKED:T1 观察池不可用，live 不允许空名单继续
> 原结论错在 / 适用范围：无需修复原工单中的静默空转问题。空池/无兜底属实，但已显式报错且live阻断；使用正确下一观察日2026-10-14复跑，排除了同日T1日期资格混淆。原结论遗漏当前错误传播和live阻断。不能仅因没有热门股兜底就要求把数据失败变成放行。
<!-- CODEX_INLINE_R4-01_END -->

**链路**
1. 启动脚本默认 `$UniverseMode = 'T1'`（`scripts/start_internal_paper_watch.ps1`）；
2. T1 模式走 `universe.py:262-269` 的 `else` 分支 → `load_candidates(...)`；
3. 当日无快照时接口返回 `{"found": false}` → `candidates.py:133-134` 抛
   `ValueError("T1 信号日没有已保存的候选快照")` → `:179-181` 返回 `([], "t1_unavailable")`；
4. `loop.py:397` 的兜底条件为 `source in {"t1", "t1_no_candidate", "t1_overflow"}`
   —— **不含 `t1_unavailable`** → 不加载默认观察池；
5. `universe.py:262-269` 对 `load_candidates` 的返回结果**不追加任何 error 条目**。

**实测证据**

```
GET /api/research/t1/candidates/2026-10-13
  -> HTTP 200 {"trade_date":"2026-10-13","found":false,"candidates":[]}

装载 load_watch_universe(watch_universe_mode="T1", signal_date="2026-10-13")
  -> source='t1_unavailable'   标的数=0   errors=[]

GET /api/research/t1/candidates/2026-10-12
  -> HTTP 200 {"trade_date":"2026-10-12","candidates":[]}     <- 行存在但为空
装载 -> source='t1_no_candidate' -> 兜底**生效**，池变为 200 只热门股
```

**影响**
观察池为空时 watch loop 会以"无标的"正常运行：不报错、不兜底、无信号。
由于 T1 调度器当前**关闭**（人工按需生成），任何**尚未生成当日 T1 快照**的交易日
都会落入这一状态。与 R3-01（T1 候选结构上近恒为空）叠加后：

- 快照**已生成但为空** → `t1_no_candidate` → 兜底到热门股（当前实盘即此情形，故在监控 200 只）
- 快照**尚未生成**（`found=false`）→ `t1_unavailable` → **0 标的静默空转**

即"是否在盯盘"取决于一个**未被任何错误信息暴露的**内部 source 值。

**修复方向**
1. `t1_unavailable` 也应触发默认观察池兜底（与 `t1_no_candidate` 同等对待）；
2. 若业务上确实要区分，则在 `_prepare_errors` 显式记录该状态，并在监控页
   把 `universe_source` 作为醒目字段展示（当前它只存在于内部摘要）；
3. 增加断言：`source == "t1_unavailable"` 且 `live=True` 时禁止以空池继续运行。

**验收条件建议**
在无 T1 快照的日期启动，观察池必须非空（兜底到热门股）**或**明确报错阻断，
不允许出现"0 标的同时无错误"的组合。

---

## R4-02 [高] V1 风控闸 `check_intent` 的 NEAR_LIMIT_UP 仍硬编码 9.0%

**状态：已证实**（同一输入下两闸结论相反的探针）

<!-- CODEX_INLINE_R4-02_BEGIN -->
**Codex 验证（2026-10-13）：✅ 确认存在 —— 待修复**
> 复现：`py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_risk_gate_mismatch.py'`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> 600519 +5%：V1 PASS，watch PASS
> 920627 +15%：板块涨停30%，正确近涨停阈值29%
> V1 NEAR_LIMIT_UP，watch PASS
> 验证范围 / 订正：同报价、同限价、同账户输入运行两真实闸门，北交所反向结论确证。待修复V1与watch板块阈值一致性；两创默认板块闸遮蔽差异，不以它们替代北交所对照。
<!-- CODEX_INLINE_R4-02_END -->

**位置与不一致**

| 闸 | 位置 | 「接近涨停」判据 |
|---|---|---|
| watch 路径 `check_watch_intent` | `risk.py:313-314` | `near_limit_up_threshold_pct(code, exchange)` ✅ 按板块 |
| **V1 路径 `check_intent`** | **`risk.py:111`** | **`quote.pct_chg >= 9.0`** ❌ **硬编码** |

`near_limit_up_threshold_pct`（`risk.py:45-47`）的注释本身就写明了正确口径：
「主板 10→9，创业板/科创板 20→19，北交所 30→29」。

**D-RISK-01 已识别该缺陷但只修了一半**。`risk.py:17-21` 的注释：

> D-RISK-01（2026-10-13）：此前 board_limit_pct 只存在于 decide.py，而 risk.py 的
> NEAR_LIMIT_UP 闸把「接近涨停」硬编码成 9.0%，两者口径不一致——创业板/科创板（20%）
> 下 9.8% 的正常标的会被误判为接近涨停并拒单……现在把板块涨跌幅判定收敛到风控侧作为唯一来源。

收敛只发生在 `check_watch_intent`，`check_intent` 的 9.0 未改。

**`check_intent` 仍在生产路径上**：`runner.py:189`（dry-run 预览，注释写明
"以 confirmed=True 模拟跑闸，**展示真实拦截原因**"）与 `runner.py:350`。

**实测证据**（`probe_risk_gate_mismatch.py`）

| 代码 | 板块 | 涨停幅 | 正确阈值 | 当日涨幅 | V1 闸 | watch 闸 |
|---|---|---|---|---|---|---|
| 600519 | 主板 | 10% | 9.0% | 5.0% | PASS | PASS |
| 300530 | 创业板 | 20% | 19.0% | 12.0% | NEAR_LIMIT_UP | BOARD_BLOCKED_CHINEXT |
| 688022 | 科创板 | 20% | 19.0% | 12.0% | NEAR_LIMIT_UP | BOARD_BLOCKED_STAR |
| **920627** | **北交所** | **30%** | **29.0%** | **15.0%** | **NEAR_LIMIT_UP** | **PASS** ← 结论相反 |
| 300750 | 创业板 | 20% | 19.0% | 9.5% | NEAR_LIMIT_UP | BOARD_BLOCKED_CHINEXT |

**影响**
V1 路径（消费 T1 候选 → dry-run 预览 → 下单）会把**距涨停尚有 8~21 个点**的正常标的
判为"接近涨停"并拒单；dry-run 预览展示给用户的**拒单原因是错的**。
创业板/科创板因默认板块禁买（见 R4-03）恰好掩盖了差异，但一旦打开开关，两路径立刻分歧。

**修复方向**
`check_intent` 改用 `near_limit_up_threshold_pct(quote.code, quote.exchange)`，
与 watch 路径统一到同一来源（正是 D-RISK-01 注释所声明的目标）。

**验收条件建议**
对每个板块各取一个"距涨停 1 个点内"与"距涨停 5 个点外"的样本，
两闸的判定必须一致。

---

## R4-03 [中] 默认禁买创业板+科创板（占热门股 36%），却无 `allow_bse` 开关

**状态：已证实**（量化）

<!-- CODEX_INLINE_R4-03_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_QUOTA_BEGIN\n(.*?)^# CODEX_PROBE_QUOTA_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> defaults False False；allow_bse exists False
> pool500：MAIN295 CHINEXT144 BSE25 STAR36
> blocked180（36%）
> 验证范围 / 订正：默认配置、池构成和无allow_bse可复现；R4-02也实际证明默认北交所买入可通过。该组合是否遗漏不能从风险不对称推定；默认禁买两创为已声明策略。未证明监控展示误导，也不能以资源消耗为由要求过滤观察池。需风险策略约定才可将新增BSE开关列为修复需求。
<!-- CODEX_INLINE_R4-03_END -->

**配置**
- `allow_chinext = False`、`allow_star = False`（`config.py:37-38`，env 默认 `"0"`）
- 启动脚本未设置 `PAPER_ALLOW_CHINEXT` / `PAPER_ALLOW_STAR` → 实盘即用默认禁买
- **没有 `allow_bse` 开关** → 北交所（30% 涨跌幅）默认**放行**

**热门股列表板块构成**（后端接口返回 500 只）

| 板块 | 只数 | 占比 |
|---|---|---|
| 主板 | 295 | 59.0% |
| 创业板 | 144 | 28.8% |
| 科创板 | 36 | 7.2% |
| 北交所 | 25 | 5.0% |

**创业板+科创板 = 180 只 = 36.0%**。

按 2026-10-12 成交额排名，两创合计在各区间稳定占 34.7%~42.0%
（前 100 名 35.0%、前 200 名 38.5%、201-500 名 34.7%），说明被排除的是市场**最活跃**的部分。

**两条路径的拦截位置不同，但结论一致（都不能买）**

| source | 出池阶段是否过滤 | 拦截位置 |
|---|---|---|
| `hot_stocks` / `watch_pool` | **不过滤**（`universe.py:286-290`，注释：不能在 universe 阶段把热门股删掉）| 被**监控**，在风控闸拒（`risk.py:235-238` → `BOARD_BLOCKED_CHINEXT/STAR`）|
| `t1` | **过滤**（`universe.py:291` 走 `board_blocked`）| 不进池 |

**影响**
1. 36% 的热门股处于"被监控但永不可成交"状态——消耗 K 线预热、行情轮询、信号计算与
   分钟线落库资源（本项目为这些标的做了 204 只回补）；
2. 监控面板会展示这些标的的信号，但它们在风控闸必然被拒，对使用者是误导；
3. **不对称**：波动更大、流动性更差的北交所默认可买，而两创默认禁买——
   这个组合看起来不像有意设计，更像遗漏。

**修复方向**
属产品/风险偏好决策，**不建议直接改默认值**。建议：
1. 补一个 `allow_bse` 开关，让三个板块的策略对称、可显式配置；
2. 若维持两创禁买，则在观察池装载阶段就把它们排除（或标记 `NOT_TRADABLE_BOARD`），
   避免"监控但不可交易"的资源浪费与展示误导。

---

## 已核对为一致 / 阴性结果（含两条被我自己证伪的假设）

| 项 | 结论与证据 |
|---|---|
| **证伪 1**：`PRICE_DECIMALS` 键不匹配 | 我曾怀疑 `PRICE_DECIMALS = {"SH","SZ","BJ"}` 与库中 `exchange_type`（`SSE`/`SZSE`/`BSE`）不匹配。**实测证伪**：`universe.py:147` 走 `_market_exchange()` 归一化、`candidates.py:44-54` 走 `_normalise_exchange()`，因此 `Candidate.exchange_type` **恒为 SH/SZ/BJ**，`PRICE_DECIMALS.get()` 每次都能命中。**不存在不匹配** |
| **证伪 2**：两创标的会被出池过滤 | 我曾据此推断"观察池会删掉两创"。**实测证伪**：`universe.py:286-290` 对 `full_market`/`watch_pool`/`hot_stocks` 三种 source **不做任何板块过滤**，注释明确"不能在 universe 阶段把热门股或人工标的删掉"。仅 `t1` 等其他 source 才过滤 |
| 零股（odd lot）处理 | **设计正确**。买单取整（`internal_broker.py:267`）、卖单**故意不取整**（`:268-270`，注释说明"再取整会把它归零或截断，零股永久滞留"）；`decide_watch.py:83` 在 `0 < available < LOT_SIZE` 时按 `odd_lot_sell` 直接下 available；`capacity` 由 `floor(volume × participation / 100) × 100` 生成，**恒为整手倍数**，故部分成交不会产生零股 |
| `buy_qty` 的价格口径 | `decide_watch.py:63` 用含滑点的**委托限价**而非最新价计算可买量与目标仓位。刻意为之，注释说明按最新价算会让名义金额穿过 `max_name_weight` 导致风控反拒。**口径正确** |
| 资金一致性 | `buy_qty` 预留 `min_cash_reserve=5000` 与 `MIN_COMMISSION=5.0`；闸 8 校验 `gross + max(gross*0.0003, 5) <= avail_balance`。**两者一致**，不会出现"算得出但过不了闸" |
| `board_limit_pct` 板块判定 | `risk.py:30-42`：300/301→20%、688/689→20%、BJ/BSE→30%、其余→10%，与 `models.board_blocked` 口径一致。**正确** |
| 涨停硬闸与近涨停辅助闸的分工 | 闸 7a 用 `limit_up_price` 硬闸（`risk.py:108`）已正确锚定真实涨停价，**未使用近似值**；问题仅在 7b 的板块口径（见 R4-02） |

---

## 第 4 轮小结

| 编号 | 严重度 | 状态 | 一句话 |
|---|---|---|---|
| R4-01 | 高 | 已证实 | T1 模式无当日快照时观察池为空，不报错不兜底（静默空转） |
| R4-02 | 高 | 已证实 | V1 闸 NEAR_LIMIT_UP 硬编码 9.0%，D-RISK-01 只修了 watch 路径 |
| R4-03 | 中 | 已证实 | 默认禁买两创（36% 热门股），却无 `allow_bse` 开关 |

**R4-01 与 R3-01 的叠加效应值得单独强调**：
R3-01 使 T1 候选近恒为空，R4-01 使"无快照日"整个不盯盘。
两者共同解释了「T1 候选长期为空」这一现象为何没有在运行期暴露为故障——
系统只是安静地什么都没做。

---

# 第 5 轮：信号去重、冷却、排序与配额

范围：冷却表语义、重复委托保护、信号排序与配额分配、信号丢失路径。

---

## R5-01 [高] 每轮 3 笔 / 每日 20 笔的配额永久丢弃 43%~80% 的有效买入信号，且保留哪 3 笔由观察池顺序决定

**状态：已证实**（用主库 `market_data_minute` 里我们自己沉淀的真实 5 分钟线实测两个交易日）

<!-- CODEX_INLINE_R5-01_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_cycle_quota2.py'；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_QUOTA_BEGIN\n(.*?)^# CODEX_PROBE_QUOTA_END',t)[1])"；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> 原导出探针：47/109=43.1%，56/87=64.4%
> 当前DB真实策略：2026-10-12 crosses147 above3=77 fraction0.5238
> 当前DB真实策略：2026-10-13 crosses91 above3=59 fraction0.6484
> missed_by_probe_extra_condition：39、4
> 4只金叉第四只MAX_TRADES_PER_CYCLE；倒序后换为另一只
> 下一bar全hold，没有补发
> 验证范围 / 订正：配额顺序依赖和事件不补发已用真实loop确认，可针对这个机制修复/明确策略。但原探针多了s_curr>l_prev，且缓存25119键与当前DB24208键不一致（912缓存键缺失），不能保留“生产有效信号损失43%~80%”。新复算是持仓=0的原始金叉，仍未过资金/板块/持仓/覆盖风控；above3不是实际拒单数。当前进程是SHADOW、Live=0，每日提交计数为0，日20对当前路径不按原文方式消耗。未证实策略收益变差、早盘平庸/尾盘更强、或需追逐已失效信号；完整量化需当时观察池顺序、状态、报价和实际风控回放。
<!-- CODEX_INLINE_R5-01_END -->

**位置**
- `max_trades_per_cycle = 3`（`config.py:121`）、`max_trades_per_day = 20`（`:120`）
- 闸门应用点 `risk.py:242-249`（`MAX_TRADES_PER_DAY` / `MAX_TRADES_PER_CYCLE`）
- 信号循环 **`loop.py:2328` `for candidate in self._candidates:`** —— **不排序**
- 策略信号性质 `ma_cross_strategy.py:55-57`（只在穿越那根 bar 返回 buy）

**三个事实叠加导致"永久丢弃 + 任意选择"**

1. **信号是单根 bar 事件**：`buy` 仅在 `s_prev <= l_prev and s_curr > l_curr` 那一根 bar 成立；
2. **没有任何重试队列**：已核实 `loop.py` 中所有 `pending` 语义只涉及 K 线取回、
   准备状态与订单状态恢复，**不存在待处理/延迟信号队列** → 被配额拒掉即永久丢失；
3. **保留哪几笔不可控**：信号循环按 `self._candidates` 的自然顺序迭代，
   即观察池装载顺序（热门榜顺序），与策略自身的判断强度无关。

**实测数据**（收紧口径：仅计当日 ≥30 根 bar 的标的，且从第 25 根起评估 MA，
以排除暖机边界的伪金叉）

| 交易日 | 参与标的 | 评估次数 | 金叉事件 | 单周期最多同时金叉 | 超 3 只的周期 | **因配额丢弃** |
|---|---|---|---|---|---|---|
| 2026-10-12 | 261 只 | 5,391 | 109 | **10 只** | 13/23 轮 (57%) | **47/109 = 43.1%** |
| 2026-10-13 | 261 只 | 6,208 | 87 | **39 只**（13:05） | 3/13 轮 (23%) | **56/87 = 64.4%** |

金叉数分布（2026-10-12）：`2只×7轮, 3只×3轮, 4只×1轮, 5只×2轮, 6只×4轮, 7只×3轮, 8只×1轮, 9只×1轮, 10只×1轮`
—— **中位数为 5 只/轮，而每轮只允许 3 笔**。

**叠加日配额后更严重**：两日金叉事件分别为 109 与 87 次，而 `max_trades_per_day = 20`
→ **日级约 77%~82% 的信号被丢弃**。

**关键偏差（这才是影响准确性的部分）**
日内配额是**按时间顺序消耗**的：
- 早盘一个平庸的金叉会占掉名额，**挤掉尾盘更强的信号**（信号没有强弱排序，只有先后）；
- 同一轮内保留哪 3 笔取决于热门榜顺序，而非策略判断；
- 被丢弃的信号**不会在后续轮次补发**。

即最终成交集合 ≈「当日最早出现的（且排位靠前的）× 20 笔」，
而不是「当日最好的 20 笔」。这对策略真实表现的衡量与推荐质量都有直接影响。

**修复方向**
1. 若要保留配额，应改为**按信号强度排序后再截断**（MA_CROSS 是二值信号，
   可用的排序依据至少包括：穿越幅度、量能确认、池内排名）；
2. 引入**延迟队列**：本轮被配额拒绝的信号在后续轮次（同日内、冷却允许时）重新参与竞争；
3. `max_trades_per_cycle=3` 相对 `max_observe_codes=200` 明显偏小，
   建议依据实测的"单周期并发金叉数分布"重新标定（本清单已给出分布）。

**验收条件建议**
回放 ≥20 个交易日，统计"被配额丢弃的信号占比"，应低于一个明确的可接受阈值
（当前实测 43%~80% 显然过高）；且不允许出现"零成交日 + 当日存在金叉"的组合。

---

## R5-02 [中] V1 路径对非买入意图一律 REJECT 且**不写原因码**，拒绝不可审计

**状态：已证实**（探针）

<!-- CODEX_INLINE_R5-02_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_v1_sell.py'；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_V1_BEGIN\n(.*?)^# CODEX_PROBE_V1_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> SELL_LIMIT：REJECT，reject_reason=''，reason_codes=[]
> NOOP：PASS，reject_reason=''，reason_codes=[]
> V1 position0：BUY_LIMIT
> V1 position1000：NOOP ALREADY_FILLED
> 验证范围 / 订正：显式注入SELL_LIMIT的空原因拒绝成立，可补充不支持类型原因码。但“非BUY一律拒”被NOOP反证；真实decide为入场路径，已有持仓不生成卖单。未证明生产卖出被V1误拒或当前系统无出场；watch有卖出，当前又是SHADOW。不要按“买得进卖不出”定级。
<!-- CODEX_INLINE_R5-02_END -->

**位置**：`risk.py:72-74`

```python
if intent.intent_type != IntentType.BUY_LIMIT:
    intent.risk_status = RiskStatus.PASS if intent.intent_type == IntentType.NOOP else RiskStatus.REJECT
    return intent
```

**实测**（`probe_v1_sell.py`）

| intent_type | risk_status | risk_reject_reason | reason_codes |
|---|---|---|---|
| BUY_LIMIT | REJECT | `'NAME_WEIGHT_EXCEEDED'` | `['NAME_WEIGHT_EXCEEDED']` |
| **SELL_LIMIT** | **REJECT** | **`''`（空）** | **`[]`** |
| NOOP | PASS | `''` | `[]` |

**影响**
1. **拒绝不可审计**：`runner.py:189` 的 dry-run 预览注释写明目的是"展示真实拦截原因"，
   但卖单意图得到的是**空原因**。外部只看到一次失败，无法判断为什么；
2. **V1 路径不具备出场能力**：结合 `decide.py:1` 的文档"昨日 T+1 候选 → 今日
   BUY_LIMIT / NOOP"，该路径是**纯入场**策略。对照 watch 路径对卖单有完整闸门
   （`NO_SELLABLE` / `SELL_QTY_EXCEEDS_AVAILABLE` / `LIMIT_DOWN_NOT_SELLABLE` /
   `NEAR_LIMIT_DOWN` / `DUPLICATE_SELL_ORDER`，`risk.py:259-296`），
   两条路径的出场能力严重不对称。

**待确认**：V1 路径是否真的会产生 `SELL_LIMIT` 意图（若从不产生，则第 1 条只是
防御性代码缺少原因码）；以及实盘持仓由哪条路径负责退出。需在下一轮确认后定级。

**修复方向**
无论是否会产生卖单，拒绝分支都应写入明确的原因码
（例如 `UNSUPPORTED_INTENT_TYPE_IN_V1`），使拒绝可审计。

---

## 已核对为一致 / 阴性结果

| 项 | 结论与证据 |
|---|---|
| 冷却表语义 | **正确**。`can_trade`（`cooldown.py:13-25`）只对**反向**信号生效——`if not previous_side or previous_side == side: return True`，同向信号直接放行，符合"反向信号冷却表"的命名本意（防抖动，不阻碍同向加仓；加仓另由 `ALREADY_POSITION_NO_ADD` 拦截） |
| 冷却的保守兜底 | **正确**。`previous_at` 无法解析或时区不匹配（naive/aware 相减抛 `TypeError`）时返回 `False` 即拦截，注释明确说明"无法判断冷却是否到期 → 保守拦截" |
| 冷却的持久化与跨日重置 | **正确**。状态按交易日分文件（`loop.py:289` `self._state_path(trade_date)`），跨日自然重置，不会把昨日冷却带到今天 |
| 重复委托保护（watch 路径） | **双向齐全**。买入 `DUPLICATE_BUY_ORDER`（`risk.py:383-392`）、卖出 `DUPLICATE_SELL_ORDER`（`:285-294`），均校验 `status in ACTIVE_ORDER_STATUSES and order_remaining_qty(order) > 0` |
| 重复委托保护（V1 路径） | 买入有（`risk.py:130-137`），卖出**无**——但因该路径对卖单一律 REJECT（见 R5-02），当前不构成实际风险 |
| 持仓去重 | **双重保护**。`decide_watch.py:46-47` 的 `ALREADY_POSITION_NO_ADD` + `ma_cross_strategy.py:56` 的 `if self.position == 0` |
| 未完成买单的敞口计算 | **正确**。`risk.py:341-343` 对活动买单中价格缺失/非法的情况直接拒绝（`OPEN_BUY_EXPOSURE_UNKNOWN`），避免用未知价格估算敞口 |
| watch 路径闸门顺序 | **合理**。先做廉价检查（确认/白名单/意图类型/行情有效性/价格），再做组合级检查（资金/权重/持仓数/熔断/重复），拒绝时写 `gate_trace` 可追溯 |

---

## 第 5 轮小结

| 编号 | 严重度 | 状态 | 一句话 |
|---|---|---|---|
| R5-01 | 高 | 已证实 | 配额永久丢弃 43%~80% 有效信号，保留哪 3 笔由观察池顺序决定，无重试 |
| R5-02 | 中 | 已证实 | V1 路径拒绝非买入意图且不写原因码，拒绝不可审计、无出场能力 |

**R5-01 在本次分析中的位置**：前四轮的问题多是"用错数据"或"该拦没拦"，
R5-01 是**"该做没做"**——策略算出了正确信号，却被配额与顺序任意丢弃。
它不改变单个信号的对错，但改变**最终成交集合的构成**，
因此对"推荐结果的准确性"是直接命中的。

---

# 第 6 轮：时间、日历、时区与调度

范围：两侧交易日历的数据模型与覆盖边界、判定状态的可审计性、T1 为空的因果次序修正。

---

## R6-01 [高] Java 日历表结构上无法回答「今天是否交易日」——`isTradingDay(今天)` 恒为 false，而 8 个调用点里 6 个裸用

**状态：已证实**（数据库实测 + 代码阅读）

<!-- CODEX_INLINE_R6-01_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> 517行，全交易日，覆盖2024-08-19..2026-10-12
> 今日行0；最新ingest=2026-10-13 13:21:50.386565
> calendar actual callsites6，裸用4、配套覆盖2
> calendar API today_is_trading_day=False
> market_session phase=HOLIDAY，label=休市（非交易日）
> 验证范围 / 订正：今天被误呈现为已知休市确证，待修复未知覆盖与休市的区分；4裸用点应审查。不是结构上不能加入今天，也不是每一天恒false，原8/6把非调用等混计；当前日历status=PASS，整体BLOCKED来自data_gate，不能归因日历闸。未调用会写库的会话创建/交易接口，因此“无法创建/回放”的后果仍仅阅读推断。
<!-- CODEX_INLINE_R6-01_END -->

**表的数据模型**

`TradingCalendarService.isTradingDay`（`TradingCalendarService.java:212-214`）：

```java
/** 判断某日是否为交易日（日历表存在该日即为交易日）。 */
public boolean isTradingDay(LocalDate date) {
    return tradingCalendarDayRepository.findByTradeDate(date).isPresent();
}
```

而 `trading_calendar` 表的实际内容（实测）：

| ingest 批次 | 行数 | trade_date 范围 |
|---|---|---|
| 2026-08-11 | 382 | 2025-01-13 .. 2026-08-11 |
| 2026-08-19 | 103 | 2024-08-19 .. 2026-08-19 |
| **2026-10-13（今天 13:21:50）** | **32** | 2026-08-20 .. **2026-10-12** |

- 全表 **517 行，`is_trading_day` 恒为 1**（`select is_trading_day+0 v, count(*) group by v` → `1, 517`），
  **零行非交易日** → 该列是**无信息列**；
- 数据源 `eastmoney_derived`，reliability `limited`；
- **最新 ingest 今天跑过，但最大日期止于昨天 2026-10-12**。

**因此该谓词的真实语义是「该日是已被 ingest 的历史交易日」**，而它
**结构上不可能对"今天"返回 true**（今天的行要等今天 EOD 数据存在后的下一次 ingest）。

**已证实**

```
select count(*) from trading_calendar where trade_date='2026-10-13'  ->  0
  => isTradingDay(2026-10-13) == false        （今天！）
对照：market_data            今日 0 行（EOD 未入库）
      market_data_minute     今日 12,552 行（我们自己沉淀的分钟线）
```

**代码自己知道并要求调用方处理**（`TradingCalendarService.java:222-226`）：

> 日历覆盖的日期区间（早/晚边界），用于区分「已知为非交易日」与「日历根本不覆盖」。
> P0-3 用途：本地实测日历仅覆盖到 2026-08-19，而行情已到 2026-10-12。
> 若只判断 `isTradingDay(LocalDate)`，2026-10-12 会被误报为「非交易日」，掩盖真实的数据缺失。
> **调用方必须结合本方法判断该日是否在覆盖范围内。**

**但 8 个调用点只有 2 个遵守**：

| 调用点 | 是否用 `coverageWindow()` |
|---|---|
| `T1CandidateScheduler.java:142` | ✅ |
| `T1CandidateService.java:975` | ✅ |
| `T1CandidateService.java:956`（封装为 `calendarKnowsTradingDay`，与 `calendarCoversDay` 配套） | ✅ |
| `SimAccountService.java:160` | ❌ 裸用 |
| `SimAccountService.java:264` | ❌ 裸用 |
| `SystemReadinessService.java:83` | ❌ 裸用 |
| `SystemReadinessService.java:151` | ❌ 裸用 |

**裸用点的实际后果**

1. `SimAccountService.java:160`（`startSession`，startDate=今天）
   → 抛 `BusinessException("起始日不是交易日（请先初始化交易日历，见 /api/data/trading-calendar/init）")`
   → **无法从今天开始任何演练会话**，且**给出的补救指引是错的**：日历早已初始化，
   问题是它永远不含今天。使用者按提示去"初始化日历"也不会好。
2. `SimAccountService.java:264`（历史模式，tradeDate=今天）
   → `reject("NOT_TRADING_DAY", "tradeDate=2026-10-13 不是交易日（交易日历未覆盖该日）")`
   → 无法回放今天的会话，**尽管我们手里就有今天的 12,552 根分钟线**。
   注意该消息自己写了"日历未覆盖该日"，却把它归类成 `NOT_TRADING_DAY`，
   正是 javadoc 禁止的语义混淆。
3. `SystemReadinessService.java:83` `m.put("today_is_trading_day", rows > 0 && isTradingDay(today))`
   → 恒为 false；`:151` 的就绪检查同样恒失败 → **每个交易日盘中，就绪状态都会报"今天不是交易日"**。

**这不是边界情形，而是每个交易日的常态。**

**修复方向**
1. 表必须同时存**非交易日**（或改为按"覆盖区间 + 显式休市集合"建模），
   让 `is_trading_day` 列恢复信息量；
2. 或把 `isTradingDay` 改为返回三态（`TRADING` / `NOT_TRADING` / `UNKNOWN`），
   强制调用方处理 `UNKNOWN`——从类型上消除裸用；
3. 4 个裸用点改为结合 `coverageWindow()` 判断，并把 `UNKNOWN` 与
   "已知休市"区分开（错误消息与拒绝码都要分开）。

**验收条件建议**
`isTradingDay(今天)` 在交易日盘中必须返回 true（或返回 UNKNOWN 并让调用方
走"未知"分支，不得返回"非交易日"）；`SystemReadinessService` 的
`today_is_trading_day` 在交易日盘中必须为 true。

---

## R6-02 [高] Python 侧日历与其数据覆盖脱钩、失败方向与 Java 侧相反，2027 年起整体失效

**状态：已证实**（文件实测 + 代码阅读）

<!-- CODEX_INLINE_R6-02_BEGIN -->
**Codex 验证（2026-10-13）：✅ 确认存在 —— 待修复**
> 复现：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_API_BEGIN\n(.*?)^# CODEX_PROBE_API_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> calendar available=True，holidays19，2026-01-01..2026-10-07，extra0
> 2026-10-13 True
> 2027-01-01 True
> 2027-02-17 True
> 验证范围 / 订正：真实文件加载和is_trading_day执行证实无覆盖保障，待修复有效年份/覆盖契约。明确仅确认默认日历服务；没有启动2027长循环，也没有验证届时新文件是否缺失。“2027-02-17是春节”未经权威日历证实，不用这个日期的节日归属作为证据；“2027整体失效”应改为旧文件/回退服务对超覆盖工作日无法识别休市。
<!-- CODEX_INLINE_R6-02_END -->

**文件内容（实测）**：`paper/trading-calendar.2026.json`，411 字节

- `holidays`：**19 条**，范围 **2026-01-01 .. 2026-10-07**
- `extra_workdays`：**0 条（空）**

**判定逻辑**（`trading_calendar.py:21-28`）

```python
def is_trading_day(self, day) -> bool:
    if key in self._extra_workdays:  return True    # 调休补班
    if key in self._holidays:        return False   # 法定节假日/临时休市
    return d.weekday() < 5                          # ← 未知日期一律当作交易日
```

**两个缺陷**

1. **覆盖校验缺失**：`load_calendar_from_file`（`:44-54`）**无条件设 `available=True`**，
   不记录也不校验数据覆盖范围。因此对任何日期它都宣称"可用"，即使该日期
   根本不在数据的权威区间内。
   → **`2026-10-07` 之后文件里再无任何节假日**。2027 年起（元旦、春节、清明、劳动、
   端午、中秋、国庆）**全部不会被识别**，所有周一至周五都被判为交易日。
   已证实示例：`2027-01-01`（周五，元旦）、`2027-02-17`（春节）→ 均返回 **True**。
2. **失败方向与 Java 侧相反**：
   - Python：未知日期 → **fail-open**（当作交易日）
   - Java：无行 → **fail-closed**（当作非交易日）
   → 同一天、同一系统内两条链路会给出**相反答案**。至少一侧必然是错的。

**启动脚本已部分加固（D-CAL-02，2026-10-13）**

`scripts/start_internal_paper_watch.ps1:67-105` 已实现：显式 `-TradingCalendarFile` 优先、
按 `$calendarYear`（信号日年，否则当前年）自动注入 `paper/trading-calendar.<year>.json`、
找不到时 `Write-Warning`、跨年信号日 `throw`、`EXTERNAL_PAPER` 必须注入否则 `throw`。

**但缺口仍在**：`INTERNAL_PAPER` 模式下文件缺失**只告警不阻断**
（`:79` 命中 → 注入；`:81` 仅 `Write-Warning`）。2027 年 `$calendarYear=2027`
→ 文件不存在 → 仅告警 → 回退到"周一至周五"→ **内部仿真在 2027 年会把法定节假日
当作交易日运行**（而 EXTERNAL_PAPER 会 throw，两者保护强度不一致）。

**已核对为正确的一点**：`extra_workdays` 为空对**股票市场是正确的**——
调休补班的周末交易所仍然休市。但这也埋了一个雷：若日后有人按政府调休表
填充该字段，会把周六变成"交易日"，需要显式加注释阻止。

**修复方向**
1. 日历文件增加 `coverage: {from, to}` 字段；`is_trading_day` 对区间外日期
   返回"未知"而非"交易日"（与 R6-01 的三态改造对齐）；
2. `available` 不再无条件为 True，应反映"该日是否在权威区间内"；
3. `INTERNAL_PAPER` 与 `EXTERNAL_PAPER` 的保护强度对齐（缺日历即阻断）；
4. 补齐 2027 年日历，或改为从后端日历表/API 取（消除双源）。

**验收条件建议**
对覆盖区间外的日期，两侧必须给出一致答案（都判 UNKNOWN 并阻断），
不得一侧说"是交易日"、另一侧说"非交易日"。

---

## R6-03 [中] 对 R3-01 的修正：T1 候选为空的首因是覆盖率闸门分母，阈值只是第二因

**状态：已证实**（代码注释记录了完整的历史实测数据）

<!-- CODEX_INLINE_R6-03_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> old_expected5835 needed5544 max_daily5477 max_ratio0.9386
> active_expected5465 active_covered5453
> 当前快照 daily_coverage=0.9978042086001829
> 当前快照 status=NO_ELIGIBLE_CANDIDATE
> september8_rows5456
> 验证范围 / 订正：旧分母与上界数字独立复算成立，但这不是当前待修问题：源码已按活跃键改，运行快照也通过覆盖率。原“已证实”的历史因果只引用注释，并非作者运行证据；不能据此给当前修复工单。9月8日当前5456行，无法用当前库重现原“249行断崖/155日标定”时点；原版本、当时DB快照与决策日志缺失，历史“首因/恒阻断”仍无法完整验证。
<!-- CODEX_INLINE_R6-03_END -->

我在第 3 轮把「T1 候选长期为空」归因于**规则分阈值 35.0 未随值域重标定**（R3-01）。
本轮发现该归因**不完整——阈值不是首因**。

**首因：覆盖率闸门分母被放大，导致闸门恒不可通过（D-T1-06）**

`T1CandidateService.java:181-221` 的注释完整记录了这次修正（2026-10-13）：

> 原实现的分母 = `stock_info` 中 status=1 且 list_date<=T 的**全部键**，与「当日真实
> 应有行情的键」不是同一个集合，导致闸门恒不可通过。实测（本地 quant_db，只读）：
> · 分母 5,835；门槛 0.95 需 5,544 根当日 bar；
> · 历史单日最大 bar 数仅 5,477（2026-03..2026-08 正常期 mean 5,463.6）；
> · 最大可达覆盖率 0.9386 < 0.95，缺口恒为 67 根 → **每个信号日都返回
>   `INCOMPLETE_DAILY_DATA`，候选恒空**，D-T1-01/D-T1-02 的阈值修复**从未真正生效**。

**所以因果次序是**：

1. **覆盖率闸门（D-T1-06，已修）** → 每个信号日恒返回 `INCOMPLETE_DAILY_DATA` → 候选恒空
2. **规则分阈值（R3-01，待修）** → 闸门修好后才会真正暴露出来
3. **R4-01 的空快照静默空转** → 连快照都没有时观察池为空

**这是对我 R3-01 的实质修正**：R3-01 指出的问题**本身仍然成立**
（我直接从 `market_data` 独立算出的分布：5,452 只中仅 6 只 ≥35，不依赖 T1 输出），
但它此前**根本没有机会被执行到**。原文中"T1 长期为空的根因"应读作
"闸门修复后的下一个瓶颈"。

**旁证（本轮实测）**：`t1_candidate` 全表**仅 5 行**
（2026-10-12、2026-09-07、2026-08-24、2026-08-19、2026-08-18），其中
`2026-08-19` 那行含**真实候选**：`rule_score: 40.2628`（`300684`）——
说明阈值确实偶尔能过（与 R3-01 的"5,452 只中 6 只 ≥35"一致），
其余 4 行均为 `[]`。

**分母被放大的构成**（注释归因，比早期版本更完整——此前只归因于北交所代码迁移）：

- 284 个 `stock_code` 同时存在 `BSE(920xxx)` 与 `SSE` 两个 `exchange_type` 行（迁移遗留），
  **两个键都有 bar**，这部分**不构成**缺口；
- 其余约 358 个键是「在主表中但 `[T-60d, T]` 内没有任何 bar」的键——
  长期停牌/已实质退市/陈旧编码，纯生命周期条件（status/list_date）无法剔除。

**修正后的标定证据（已核对为充分）**：分母改为「回看窗口内真的在交易的键」后，
2026-01-05..08-31 共 **155 个交易日**，覆盖率 **min=0.9596、median=0.9958，155/155 通过**；
且**仍能正确阻断真实数据断崖**（2026-09-08 起 bar 数由 5,456 掉到 249 → coverage 0.0455 → BLOCKED）。
阈值 `MIN_DAILY_COVERAGE=0.95` **未放宽**，且分母只用 `[T-60d, T]`，**无前视**。

---

## R6-04 [中] T1 判定状态不持久化、接口不返回 → 空候选不可归因，`load_from_t1` 的质量闸对存量快照恒不触发

**状态：已证实**（表结构 + 接口响应实测）

<!-- CODEX_INLINE_R6-04_BEGIN -->
**Codex 验证（2026-10-13）：❌ 证伪 —— 无需修复（原结论对当前代码/运行范围有误）**
> 反证：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_API_BEGIN\n(.*?)^# CODEX_PROBE_API_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> SQL ecology_json eligibility.status=NO_ELIGIBLE_CANDIDATE
> SQL ecology_json eligibility.daily_coverage=0.9978042086001828
> API status=NO_ELIGIBLE_CANDIDATE
> API threshold_version=t1_rule_v2，quality_status=PIT_LIMITED
> API daily_coverage=0.9978042086001829，probability_verified=False
> 原结论错在 / 适用范围：无需修复“全不持久化/不返回/恒死代码”工单：8列里ecology_json已经承载元数据，GET会提到顶层。部分更早legacy行无元数据、无快照日期只有found=false，不代表所有快照。status闸允许NO_ELIGIBLE_CANDIDATE并返回空是有效路径，不能因这个样本未拒绝就称死代码。数据当前11行，原文5行也已过时。
<!-- CODEX_INLINE_R6-04_END -->

**问题**

`T1CandidateService` 产生四种空决策：`NO_DATA` / `NOT_A_TRADING_DAY` /
`UNKNOWN` / `INCOMPLETE_DAILY_DATA`，各带不同含义与处置建议。但：

1. **没有持久化**：`t1_candidate` 表结构只有
   `id, trade_date, topn, candidates_json, ecology_json, settled, settled_json, created_at`
   ——**没有任何 status / quality_status / daily_coverage / threshold_version 字段**；
   全库也**没有** `%decision%` 表（`show tables like '%t1%'` 只有 `t1_candidate`）。
   → "为什么这天是空的"**事后无法回答**。
2. **接口读路径也不返回**：实测 `GET /api/research/t1/candidates/2026-10-12` 返回
   ```json
   {"trade_date":"2026-10-12","top_n":10,"candidates":[],"settled":false,"ecology":{...}}
   ```
   —— **无 `status`、无 `quality_status`、无 `daily_coverage`、无 `threshold_version`、
   无 `probability_verified`**。
3. **因此 `load_from_t1` 的质量校验恒不触发**（`candidates.py:135-149`）：
   - `:135` 校验 `trade_date`/`data_cutoff` 一致 → `data_cutoff` 缺失时默认取 `signal_date`，恒通过；
   - `:140` `if data.get("status") not in {None, "OK", "NO_ELIGIBLE_CANDIDATE"}: raise`
     → `status` 缺失即 `None` → **恒通过**（该质量门对存量快照是死代码）；
   - `:144` `if data.get("probability_verified") is not True:` → 缺失 → 恒走"未验证"分支；
   - `:148-149` `lineage` 六个字段**五个为 None** → 存量快照的**血缘为空**。

**影响**
- 一个空候选的交易日**无法区分**「确实没有合格标的」/「行情覆盖不足」/
  「日历判成休市」/「未知」——而这四者的处置完全不同（前者正常，后者要修数据）；
- 无法追溯某天的候选是用哪个阈值版本、多高的覆盖率生成的 → **策略迭代无法复盘**；
- 这也解释了我第 3、4 轮为何只能靠反推（算分布、查表）而无法直接读到原因。

**修复方向**
1. 新增或扩展表以持久化决策状态（`status`、`daily_coverage`、`threshold_version`、
   `quality_status`、`pit_limited`、`calendar_trading_day`、`generated_at`）；
2. 读路径接口把这些字段一并返回，让 `load_from_t1` 的质量门真正生效；
3. 至少在 dry-run/监控页把"当日空候选的原因"显式展示。

**验收条件建议**
任取一个空候选日，必须能从持久化数据或接口回答出它属于四种状态中的哪一种。

---

## 已核对为一致 / 阴性结果

| 项 | 结论与证据 |
|---|---|
| `coverageWindow()` 的设计 | **正确且必要**。显式提供覆盖区间以区分「已知非交易日」与「不覆盖」，javadoc（`TradingCalendarService.java:222-226`）把要求写清了 |
| `T1CandidateService` 的三态区分 | **正确**。`calendarKnowsTradingDay` + `calendarCoversDay`（`:951-980`）把 `false` 拆成「区间内确实休市」与「区间外未知」，分别返回 `NOT_A_TRADING_DAY` 与 `UNKNOWN`；`:957-961` 对日历异常也保守返回 false 而非误报休市。这正是 R6-01 建议其他调用点采纳的模式 |
| `extra_workdays` 为空 | **对股票市场正确**。调休补班的周末交易所仍休市，填了反而会造成"周六是交易日"的错误 |
| 启动脚本 D-CAL-02 加固 | **正确方向**。自动注入、跨年 `throw`、`EXTERNAL_PAPER` 强制注入，均已实现 |
| 覆盖率闸门的标定证据 | **充分**。155/155 通过 + 能阻断真实断崖（coverage 0.0455 → BLOCKED），阈值未放宽，分母无前视 |
| 北交所双 `exchange_type` 行的归因 | **已修正为完整**。注释已从早期单一归因（北交所迁移）更正为两种成因，且明确 284 个双键**不构成**缺口 |

---

## 第 6 轮小结

| 编号 | 严重度 | 状态 | 一句话 |
|---|---|---|---|
| R6-01 | 高 | 已证实 | Java 日历表结构上无法回答"今天是否交易日"，6/8 调用点裸用 |
| R6-02 | 高 | 已证实 | Python 日历与覆盖脱钩、fail-open，与 Java 侧方向相反，2027 年起失效 |
| R6-03 | 中 | 已证实 | **修正 R3-01**：T1 为空的首因是覆盖率闸门分母，阈值只是第二因 |
| R6-04 | 中 | 已证实 | T1 判定状态不持久化也不返回，空候选不可归因，质量闸恒不触发 |

**本轮的因果关系成形**：T1 候选长期为空，是**三层独立原因**叠加，
而不是单一缺陷——

| 层 | 编号 | 状态 |
|---|---|---|
| 1. 覆盖率闸门分母放大 → 闸门恒不通过 | D-T1-06 | 已修（2026-10-13） |
| 2. 规则分阈值 35.0 未随值域 0-100→0-50 重标定 | R3-01 | 待修 |
| 3. 无当日快照时观察池为空且不报错不兜底 | R4-01 | 待修 |

而**为什么这三层一直没被发现**，R6-04 给出了答案：
判定状态既不持久化也不出现在接口里，系统**只对外输出"空"，不输出"为什么空"**。

---

# 第 7 轮：跨日/跨月边界、`valid_until` 与收盘、时区盘点、调度现状

范围：信号有效期与交易日时段的关系、时区处理一致性、调度任务现状，
以及对本清单前几轮若干结论的**适用范围订正**。

---

## R7-01 [中] 调度现状：仅 1 个 Quant 任务且 `Last Result = 1`（非零），T1 调度器未注册

**状态：部分已证实**（任务状态实测；`Last Result` 含义**未决**）

<!-- CODEX_INLINE_R7-01_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`schtasks /Query /FO LIST /V /TN QuantWatchShadow；schtasks /Query /FO CSV | Select-String 'Quant|T1'；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> QuantWatchShadow Ready，NextRun2030/12/31 23:58:00
> LastRun2026/10/13 15:40:49，LastResult1
> schedule API present=False，schedule_enabled=False
> cron=0 10 15 * * MON-FRI，zone=Asia/Shanghai
> 验证范围 / 订正：Windows任务与非零码事实成立，日志无EXITCODE也成立；原因仍未决，不能判循环已死。Windows没有T1任务不等于Spring调度器未注册：后端状态接口存在，功能明确关闭，属既定手动策略，不能报遗漏注册缺陷。仅阅读配置/注册过程未作运行注册证明，准确表述是“调度关闭且未运行”。
<!-- CODEX_INLINE_R7-01_END -->

**已证实**

```
schtasks /Query /FO CSV  ->  仅一项匹配： "\QuantWatchShadow","2030/12/31 23:58:00","Ready"
```

| 字段 | 值 |
|---|---|
| TaskName | `\QuantWatchShadow` |
| Next Run Time | 2030/12/31 23:58:00（一次性触发，留作占位以免 `/Delete` 杀进程树） |
| Last Run Time | **2026/10/13 15:40:49** |
| **Last Result** | **1（非零！）** |
| Scheduled Task State | Enabled |
| Task To Run | `cmd /c E:\Project\.tmp_quant_watch\launch.cmd` |

**未决点**：`launch.cmd` 的日志
（`E:\Project\.tmp_quant_watch\launch.out.log`，仅 1065 字节）**没有出现
`==== EXITCODE=... ====` 这一行**，而该行是 `launch.cmd` 的最后一句。
即：启动器在日志落盘时**尚未退出**，但 Task Scheduler 却报告 Last Result = 1。
两者矛盾，`Last Result = 1` 的确切来源**未能确定**。

**为什么值得追**：若启动路径真的会以非零码退出，那么在"脚本以退出码决定是否
重启/告警"的任何用法下，都会造成**循环被静默判定为失败**。

**同时确认**：**没有任何 T1 调度任务存在**（`schtasks` 全量匹配只有上面一项），
与既定决策"T1 先不开、保持手动"一致。因此 R3-01/R6-03 讨论的 T1 候选
**当前不会每日自动生成**，`t1_candidate` 只有 5 行（见 R6-04）与此一致。

**进程现状（实测，本轮快照）**

| PID | 进程 | 启动时间 | 端口 |
|---|---|---|---|
| 34680 | python（watch loop） | 2026/10/13 15:40:55 | — |
| 37076 | python（watch-serve） | 2026/10/13 15:19:43 | 127.0.0.1:8790 |
| 42804 | java（后端） | 2026/10/13 15:11:09 | :8080 |

---

## R7-01b 对 R4-01 的**适用范围订正**（不是新问题，是纠错）

<!-- CODEX_INLINE_R7-01b_BEGIN -->
**Codex 验证（2026-10-13）：✅ 已核对为一致 —— 无需修复（适用范围订正）**
> 复现：`Get-Content -Encoding UTF8 'E:\Project\.tmp_quant_watch\launch.cmd'；Get-Content -Encoding UTF8 'E:\Project\.tmp_quant_watch\launch.out.log'；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_API_BEGIN\n(.*?)^# CODEX_PROBE_API_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> launch -ExecutionMode SHADOW -UniverseMode WATCHLIST
> 日志 Universe WATCHLIST，ExecutionMode SHADOW，Live0
> universe WATCHLIST hot_stocks 200 []
> 验证范围 / 订正：WATCHLIST范围订正确认一致，无需修复；这是运行中的SHADOW观察任务，不是实际提交交易。除此之外R4-01静默无错误的断言已被独立证伪，不能只保留旧条目并声称仅不影响本次。原节无**状态**行，验证块置于标题下，不补写或改写作者状态。
<!-- CODEX_INLINE_R7-01b_END -->

本轮读到 `E:\Project\.tmp_quant_watch\launch.cmd` 的实际内容：

```bat
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "...\start_internal_paper_watch.ps1"
  -ExecutionMode SHADOW -UniverseMode WATCHLIST -SessionId analysis-paper -SignalDate 2026-10-13
```

**实盘跑的是 `-UniverseMode WATCHLIST`，不是脚本参数的默认值 `T1`。**
而 WATCHLIST 模式在无人工观察池时会走 `hot_stocks` 兜底（见第 4 轮实测：
`source='hot_stocks'`、200 只候选）。

**因此 R4-01（T1 模式无当日快照 → 观察池为空且不报错不兜底）对当前这次运行
并不适用**，它是 **T1 路径的潜在风险**，只在改用（或回退到）`-UniverseMode T1` 时生效。
R4-01 的代码事实（`loop.py:397` 兜底条件不含 `t1_unavailable`、
`universe.py:262-269` 不追加 error）**仍然成立**，订正的只是它的**当前影响范围**。

同理，R6-03 中"三层因果"里的第 3 层（R4-01）对当前 WATCHLIST 运行不构成影响；
第 1、2 层（覆盖率闸门、阈值）针对的是 T1 生成侧，与 WATCHLIST 无关。

---

## R7-02 [中] `valid_until` 用 `end + period` 未考虑午休，11:30 bar 的信号有效窗口落在休市时段

**状态：已证实**（代码语义确定）

<!-- CODEX_INLINE_R7-02_BEGIN -->
**Codex 验证（2026-10-13）：⚠️ 部分成立 / 需修正 —— 仅对下述已复现子项处理**
> 复现：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> end11:30：valid_until11:35，seconds300.0
> end14:55：valid_until15:00，seconds300.0
> end15:00：valid_until15:00，seconds0.0
> in_trading_window11:30 False；11:30:01 False
> 验证范围 / 订正：午休valid_until不是下一根bar收盘确证，可修正文案/时段契约。14:55结束的bar有300秒，零窗口在15:00结束的末根；若14:55指起点，应写清。原“午休还会同根撮合”被R7-03和交易时段反证。原状态只有“代码语义确定”，本次才执行adapter，不能把原状态视为原作者探针已证实。
<!-- CODEX_INLINE_R7-02_END -->

**位置**：`paper/watch/signal_adapter.py:114-115`

```python
valid_until=min(end + timedelta(minutes=bar.period_min),
                end.replace(hour=15, minute=0, second=0, microsecond=0)).isoformat(),
```

**问题**：`end + period` 假设**下一根 bar 紧邻**，但 A 股一天有**两个不连续时段**
（09:30–11:30、13:00–15:00）。用 5 分钟 bar 推导：

| bar 结束时刻 | `end + 5min` | `end.replace(15:00)` | `min` | 该窗口内可否交易 |
|---|---|---|---|---|
| 10:00 | 10:05 | 15:00 | 10:05 | ✅ 可 |
| **11:30（上午最后一根）** | **11:35** | 15:00 | **11:35** | ❌ **11:30–13:00 休市** |
| 13:05 | 13:10 | 15:00 | 13:10 | ✅ 可 |
| 14:55 | 15:00 | 15:00 | **15:00** | ⚠️ 有效窗口为 **0 秒**（等于自身 bar 结束时刻） |

**影响**
1. **11:30 bar 的信号在可交易时段之外到期**（11:35 < 13:00），实际不可执行。
   若系统仍对它尝试下单，**内部撮合会按该 bar 的价格给出一个现实中不可能成交的成交价**
   （见 R7-03）；
2. **14:55 bar 的信号有效窗口为 0**：`valid_until` 恰好等于信号自身的 bar 结束时刻，
   而判定条件是 `decision_time >= valid_until` → 该信号**从产生那一刻即为过期**。
   这是否为设计意图（"尾盘不给新开仓机会"）需要确认；
3. 与 `invalidation_conditions` 里写的"下一根 bar 收盘"**口径不一致**：
   字段说的是"下一根 bar 收盘"，实现用的是"固定 +5 分钟"，跨午休时两者相差 90 分钟。

**修复方向**
用**交易日时段感知**的"下一根 bar 开始时刻"替代 `end + period`：
09:30–11:30 → 下一根即 +period；11:30 → 13:00（下午首根 bar 的开始）；
14:55/15:00 → 收盘。可复用已有的时段工具（若有）或显式编码两个时段边界。

**验收条件建议**
对 09:30–15:00 的每根 5 分钟 bar，`valid_until` 必须满足
`valid_until <= 下一根 bar 的开始时刻`，且对收盘 bar 给出明确语义（可执行 / 不可执行）。

---

## R7-03 [待验证·候选] 决策价与成交价同源：用刚收盘 bar 的 close 决策，并按同一价格成交

**状态：仅阅读推断，本轮**未**下结论，列为候选项交给验证**

<!-- CODEX_INLINE_R7-03_BEGIN -->
**Codex 验证（2026-10-13）：❌ 证伪 —— 无需修复（原结论对当前代码/运行范围有误）**
> 反证：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> R7-03 accepted True
> same-bar fills []
> next-bar volume500 fills []
> next-bar volume1000 fills [(100,9.519,'2026-10-13T10:05:00','NEXT_BAR_PARTICIPATION_CAP')]
> broker model NEXT_BAR
> 原结论错在 / 适用范围：无需修复本候选。用真实OMS/内部Broker、真实SQL schema的内存SQLite复现：同根拒撮合，下一根开盘9.5加20bps得到9.519（限价10），容量取下一根volume。代码/注释声明NEXT_BAR。限价夹取和参与率是仿真模型，不构成本断言的同根前视；未对模型所有成交偏差作保证。
<!-- CODEX_INLINE_R7-03_END -->

**线索**
- 决策：`decide_watch.py` 以当轮报价的 `last` 为基准，`limit_price = last * (1 ± slippage_bps/10000)`；
- 该 `last` 即刚收盘那根 5 分钟 bar 的收盘价；
- 内部撮合：`internal_broker.py:274-281` 按该委托限价加减滑点成交，
  容量由 `floor(bar.volume * participation / 100) * 100` 决定。

**若成立则意味着**：策略用"第 T 根 bar 的收盘价"做决策，并以**同一个价格**成交。
现实中 5 分钟 bar 的收盘价只有在 T 时刻才可知，此刻已无法以该价格成交——
真实可成交价应为**下一根 bar** 的价格。这会系统性高估策略表现。

**为什么本轮不下结论**：需要确认三件事，都属于验证范围：
1. 内部撮合实际使用的成交价是"委托限价"还是"下一根 bar 的价"；
2. 内部撮合是否使用**同一根 bar** 的 volume 作为容量（若是，则容量与前视同源）；
3. 该行为是否已在文档/代码注释中被声明为"仿真简化"（若是，则是已知取舍而非缺陷）。

---

## 时区盘点（已核对为一致）

| 项 | 结论与证据 |
|---|---|
| 取时入口 | **设计正确**。`paper/clock.py` 统一提供 `now()` / `from_timestamp()`，内部用 `CN_TZ` 换算后**显式抹掉 tzinfo** 返回 naive 时间（`:19-25`），注释说明了动机："宿主或容器的 TZ 不是 CST 时，`datetime.now()` / `datetime.fromtimestamp()` 取到的……都会整体平移，且没有任何 fail-closed 信号。" |
| 生产代码是否有裸 `datetime.now()` | **没有**。全 `paper/` 包内 `datetime.now()` 只出现在 `clock.py`（带 `CN_TZ`）与测试文件（`test_watch_account.py`），生产路径一律经 `clock` |
| 跨时区边界处理 | **正确**。需要在 JSON 里输出带时区的 ISO 时显式补 `CN_TZ`：`loop.py:2347-2358`（`quote.market_time` / `received_at` / `decision_time`）与 `signal_adapter.py:103-104`（`bar.end_ts`），且都用 `if ...tzinfo is None` 判定，不是无条件替换 |
| 时区契约有测试保护 | **有**。`test_p1_p2_fixes.py:336-357` 断言"调用方按 naive datetime 比较/序列化"（`:344`），把契约固定下来 |

## 冷却的时间类型风险（新增，需验证）

**状态：仅阅读推断**

<!-- CODEX_INLINE_COOLDOWN_BEGIN -->
**Codex 验证（2026-10-13）：❌ 证伪 —— 无需修复（原结论对当前代码/运行范围有误）**
> 反证：`py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"；py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"`
> 输出（本次实际运行的关键片段；字段值按原输出摘录）：
> default WatchLoop clock tzinfo None
> default loop clock roundtrip sell16min True
> 当前生产 day_trade_count0，cooldowns{}
> injected aware timestamp sell after1day False
> 原结论错在 / 适用范围：当前生产路径断言证伪，无需按生产永久锁卖故障修复。默认真实clock/loop为naive，写回和恢复同契约；当前SHADOW冷却表为空。人工注入aware坏值仍可阻断，属输入污染的条件风险，并未证实由当前生产入口产生；且按日状态重置，不是跨日永久。若提供真实含aware的历史状态/自定义now入口，应另验。
<!-- CODEX_INLINE_COOLDOWN_END -->

`cooldown.py:19-24`：

```python
try:
    elapsed = now - datetime.fromisoformat(previous_at)
except (ValueError, TypeError):
    # TypeError：now 为 naive 而 previous_at 为 aware（或反之），相减抛 TypeError。
    # 两者都视为「无法判断冷却是否到期」→ 保守拦截。
    return False
```

**推理链**：若某次运行把 **aware** 的 `now.isoformat()` 写进了 `last_signal`，
而后续运行传入 **naive** 的 `now` → `TypeError` → `can_trade` 返回 False →
**该标的的反向侧被持续拦截**。而 `record()` 只在成功提交时调用，
且 `state.py` 的 `CooldownBook.from_dict`（`cooldown.py:38-44`）对 `at` **只校验非空、
不做时区归一化** → 坏值不会被自动清除。

**后果**：可能出现"买得进、卖不出"的静默故障（同向信号可以覆盖坏值，但持仓后通常
只会有卖出信号）。`test_watch.py:117-122` 已在测试里构造过这个 naive/aware 组合，
说明该场景**被认为需要处理**。

**需要验证的**：`loop.py` 里传给 `can_trade` / `record` 的 `current`
是否**可能存在 aware** 的取值（若始终为 naive，则此风险不成立）。

---

## 第 7 轮小结

| 编号 | 严重度 | 状态 | 一句话 |
|---|---|---|---|
| R7-01 | 中 | 部分已证实 | 仅 1 个 Quant 调度任务且 `Last Result=1`（含义未决）；T1 调度器未注册 |
| R7-01b | — | 订正 | 实盘跑 WATCHLIST 而非默认 T1 → **R4-01 对当前运行不适用**（仅 T1 路径风险） |
| R7-02 | 中 | 已证实 | `valid_until = end + period` 未考虑午休；11:30 信号在休市时段到期，14:55 信号窗口为 0 |
| R7-03 | 待验证 | 仅阅读推断 | 决策价与成交价同源（同一根 bar 的 close），潜在前视 |
| — | 待验证 | 仅阅读推断 | 冷却表的 naive/aware 中毒可致反向侧永久拦截 |

**本清单到此暂停，转入验证阶段。** 累计 7 轮、
**39 条记录**（22 条问题 + 17 条阴性/一致或订正），
其中 6 条是我自己证伪或订正的结论。全程**未修改任何代码**。


<!-- CODEX_INDEPENDENT_SUMMARY_BEGIN -->
---

# Codex 独立验证结果（2026-10-13）

**重点反证：原标“已证实”的 R4-01（无错误静默空转）与 R6-04（判定状态不持久化、不返回、质量闸恒死代码）被当前真实装载、loop、SQL 和 GET 接口推翻。R7-03 的同根前视候选也被真实撮合反证。不能据原状态直接开修复工单。**

本次覆盖 24 个对象：用户列出的 23 个编号项（含 R7-01b 订正）+ 1 个原无 ID 的冷却候选。整体确认待修复 6 项、部分成立/需修正 13 项、证伪 4 项、订正确认一致 1 项。全部逐条回写，并附可复跑命令；整项无法验证 0 项，但下列部分项仍有未验证子断言，不能默认放行。证据快照取于北京时间 2026-10-13 收盘后，约 16:25–16:42；数据库和运行日志可能继续变化，后续重跑不应把时间差误判为原证据伪造。

只读约束：未修改业务代码/配置/SQL schema/日历；未启动生产循环、未提交交易、未执行生成/核销/初始化 POST；未 kill、重启或删除任务。Python 使用 -B 防止 pycache，loop 的文件状态/日志出口替换为内存替身；Broker 采用真实 schema 的内存 SQLite。唯一本次文件写入为本清单。原文保留，只有定点新增验证块和末尾追加。原文 SHA-256：`0cd4349cf6848514420deb1f8f9a243fd76593458039d50d72c592e6a9c1bb0f`，原始字节数 72337。

| ID | 原状态 | 验证结论 | 证据强度 |
|---|---|---|---|
| R1-01 | 已证实 | ✅ 确认存在，待修复 | 强 |
| R1-02 | 已证实 | ⚠️ 部分成立 / 需修正 | 强 |
| R1-03 | 已证实 | ✅ 确认存在，待修复 | 强 |
| R1-04 | 已证实 | ⚠️ 部分成立 / 需修正 | 强 |
| R1-05 | 口径不一致已证实；重复行后果为阅读推断 | ⚠️ 部分成立 / 需修正 | 中 |
| R2-01 | 已证实 | ✅ 确认存在，待修复 | 强 |
| R2-02 | 已证实 | ⚠️ 部分成立 / 需修正 | 中 |
| R2-03 | 已证实 | ⚠️ 部分成立 / 需修正 | 中 |
| R3-01 | 已证实 | ⚠️ 部分成立 / 需修正 | 强 |
| R3-02 | 已证实 | ✅ 确认存在，待修复 | 强 |
| R4-01 | 已证实 | ❌ 证伪，无需修复 | 强 |
| R4-02 | 已证实 | ✅ 确认存在，待修复 | 强 |
| R4-03 | 已证实 | ⚠️ 部分成立 / 需修正 | 强 |
| R5-01 | 已证实 | ⚠️ 部分成立 / 需修正 | 强 |
| R5-02 | 已证实 | ⚠️ 部分成立 / 需修正 | 强 |
| R6-01 | 已证实（数据库实测+代码阅读） | ⚠️ 部分成立 / 需修正 | 强 |
| R6-02 | 已证实（文件实测+代码阅读） | ✅ 确认存在，待修复 | 强 |
| R6-03 | 已证实（代码注释历史实测） | ⚠️ 部分成立 / 需修正 | 强 |
| R6-04 | 已证实（表结构+接口实测） | ❌ 证伪，无需修复 | 强 |
| R7-01 | 部分已证实 | ⚠️ 部分成立 / 需修正 | 强 |
| R7-01b | 订正（本节无状态行） | ✅ 订正确认一致，无需修复 | 强 |
| R7-02 | 已证实（代码语义确定） | ⚠️ 部分成立 / 需修正 | 强 |
| R7-03 | 仅阅读推断、候选 | ❌ 证伪，无需修复 | 强 |
| COOLDOWN（原无ID） | 仅阅读推断、候选 | ❌ 证伪，无需修复 | 中 |

## 一、确认存在（待修复）

按对信号/推荐准确性的影响建议处理：

1. **R1-01**：东财标签/区间和 as_of 过滤错位，影响分钟历史与信号时序；同时验收 R1-02 已复現场景和 R1-05 口径，不沿用已被反证的“修后重复时间戳”陷阱。
2. **R2-01**：本地拒绝陈旧序列却经网络回退重新接受。R2-02 合成复牌案例可作为资格测试，不能作为真实错误收益证据。
3. **R1-03**：完整开盘首桶被丢弃；与残段首桶/恢复 checkpoint 分别验收，避免笼统取消 discard。
4. **R4-02**：V1 近涨停阈值与 watch 不一致，真实风控结果分歧。
5. **R6-02**：日历服务超覆盖日期仍 available=True、工作日放行；先约定覆盖/未知契约，勿仅把假日集合最晚日期当有效截止。
6. **R3-02**：读取接口直接返回旧评分/空评分，补明确可用性或废弃定位；尚无当前推荐接入证据，优先级低于主链路。

另有已复现的局部问题留在“部分成立”内：**R6-01 的今天被标休市**影响用户对运行状态的判断，建议与 R6-02 一起优先处理；**R5-01 的顺序配额、不补发机制**确证，应先明确这是策略政策还是待改行为，不能据旧损失率直接放宽配额；**R5-02 的不支持卖单无拒因**可作防御性审计补全。对这些子项，修复范围必须限定于本次证据。

## 二、被证伪（无需修复）

- **R4-01**：真实返回 T1_UNAVAILABLE，live run_once 明确 BLOCKED；“静默无错误”不成立。
- **R6-04**：状态/覆盖率已在 ecology_json 持久化并由 GET 提到顶层，不能用“无独立列”证明未持久化。
- **R7-03**：同根 fills=[]，下一根开盘价+滑点、下一根 volume 参与撮合。
- **COOLDOWN**：默认 loop 时钟为 naive，写入/恢复后16分钟可卖，当前 SHADOW 冷却表为空；当前生产产生 aware 坏值/永久锁卖的链路不成立。人工注入坏值的条件风险另列，未证明不存在所有自定义入口风险。
- **R7-01b** 属订正确认一致：当前 WATCHLIST / SHADOW / Live=0，无需修复该运行模式。

## 三、部分成立 / 需修正

- **R1-02**：10:32 场景确有两个真实区间未进入序列，待修复该场景。不是任意启动都恒缺两根。原“只修标签会产生重复 start_ts”的修复陷阱被反证：内存中同时改正 start/end 与 as_of 采纳，仍保留 discard=True，得到无重复序列，仅首个不完整实时桶缺失；不要据此错误陷阱设计工单。
- **R1-04**：末根 adapter 有效期为零确证。但真实 run_once 在15:00已因交易时段返回 SLEEPING，未到信号失效分支；原“尾盘信号被该分支彻底吞掉”的生产因果没有证明。代码现有 EXPIRED_BAR、INVALIDATED 和 SIGNAL 留痕也与原“无成因、彻底消失”不符（后两项仅阅读核对）。零窗口是否需改须明确收盘后是否仍产出不可执行分析信号；不得为此放行收盘后交易。
- **R1-05**：解析器/实时路径口径冲突有运行证据，统一口径待修复。但当前两个来源没有任何同股票同日交集，无法实测原断言的“同一真实区间在主库有两种口径/重复行”；唯一键无法隔离口径是推断，不是已发生重复。需来源重叠的只读历史快照或获准的隔离写入复现，才能确认重复/覆盖后果。
- **R2-02**：真实旧种子加人工构造复牌 bar 确实产生buy，但复牌 bar 非真实成交；修改种子日期的新鲜对照同样buy。MA_CROSS只看价格，数值交叉符合策略定义，不能把它直接称为伪造或证明表现变差。停牌/陈旧数据资格问题归R2-01；真实复牌首根及风险影响尚缺实盘数据和信号有效性定义。
- **R2-03**：收盘后15:34时间值足以反对“普通A股最后成交时间”。但19个值/分组本身不能证明服务端的精确刷新机制；没有抓到盘中边界错桶。保留字段认知订正，不能把“40秒抖动导致准确性损失”标待修复。精确语义需官方字段定义或盘中同步成交对照。
- **R3-01**：稀疏和当日空候选确证，保存快照诊断能定位到阈值；不过35在0–50内可达，取值域缩减不自动要求等比例降为17.5。19/33也不是“恒空”，TopN为上限。阈值设计错误及调低后准确性改善未被验证，属于产品标定决策。原脚本复算的是数值过滤上界，不能冒称完整Java生成流程。
- **R4-03**：默认配置、池构成和无allow_bse可复现；R4-02也实际证明默认北交所买入可通过。该组合是否遗漏不能从风险不对称推定；默认禁买两创为已声明策略。未证明监控展示误导，也不能以资源消耗为由要求过滤观察池。需风险策略约定才可将新增BSE开关列为修复需求。
- **R5-01**：配额顺序依赖和事件不补发已用真实loop确认，可针对这个机制修复/明确策略。但原探针多了s_curr>l_prev，且缓存25119键与当前DB24208键不一致（912缓存键缺失），不能保留“生产有效信号损失43%~80%”。新复算是持仓=0的原始金叉，仍未过资金/板块/持仓/覆盖风控；above3不是实际拒单数。当前进程是SHADOW、Live=0，每日提交计数为0，日20对当前路径不按原文方式消耗。未证实策略收益变差、早盘平庸/尾盘更强、或需追逐已失效信号；完整量化需当时观察池顺序、状态、报价和实际风控回放。
- **R5-02**：显式注入SELL_LIMIT的空原因拒绝成立，可补充不支持类型原因码。但“非BUY一律拒”被NOOP反证；真实decide为入场路径，已有持仓不生成卖单。未证明生产卖出被V1误拒或当前系统无出场；watch有卖出，当前又是SHADOW。不要按“买得进卖不出”定级。
- **R6-01**：今天被误呈现为已知休市确证，待修复未知覆盖与休市的区分；4裸用点应审查。不是结构上不能加入今天，也不是每一天恒false，原8/6把非调用等混计；当前日历status=PASS，整体BLOCKED来自data_gate，不能归因日历闸。未调用会写库的会话创建/交易接口，因此“无法创建/回放”的后果仍仅阅读推断。
- **R6-03**：旧分母与上界数字独立复算成立，但这不是当前待修问题：源码已按活跃键改，运行快照也通过覆盖率。原“已证实”的历史因果只引用注释，并非作者运行证据；不能据此给当前修复工单。9月8日当前5456行，无法用当前库重现原“249行断崖/155日标定”时点；原版本、当时DB快照与决策日志缺失，历史“首因/恒阻断”仍无法完整验证。
- **R7-01**：Windows任务与非零码事实成立，日志无EXITCODE也成立；原因仍未决，不能判循环已死。Windows没有T1任务不等于Spring调度器未注册：后端状态接口存在，功能明确关闭，属既定手动策略，不能报遗漏注册缺陷。仅阅读配置/注册过程未作运行注册证明，准确表述是“调度关闭且未运行”。
- **R7-02**：午休valid_until不是下一根bar收盘确证，可修正文案/时段契约。14:55结束的bar有300秒，零窗口在15:00结束的末根；若14:55指起点，应写清。原“午休还会同根撮合”被R7-03和交易时段反证。原状态只有“代码语义确定”，本次才执行adapter，不能把原状态视为原作者探针已证实。

## 四、无法验证

无整项完全未获得运行证据的条目。**以下子断言仍无法验证，不能标为确认存在**：

- R1-04：最后信号实际被过期分支吞掉，需要收盘边界的真实信号/事件记录及收盘分析产品契约；本次 loop 在15:00先 SLEEPING。
- R1-05：同股票同日多来源重复/覆盖，当前库无交集；需来源重叠历史快照或获准的隔离写入复现。
- R2-02：真实复牌首根及“伪造/收益不准确”，需要实际复牌行情及信号有效性标准。
- R2-03：f124精确刷新机制、盘中抖动错桶，需官方字段定义或同步成交/接口采样。
- R3-01 / R4-03：阈值或板块政策是否设计错误、调整后是否更准确，需产品目标及独立历史验证；稀疏/不对称不等于错误。
- R5-01：生产风控后的损失比例、每日配额影响、排序能提升收益，需当时观察池、报价、账户、持仓、订单和决策日志；当前 SHADOW 不按实际提交消耗日额度。
- R5-02：生产卖出是否由 V1 生成并遭误拒，需真实意图日志；本次 decide 的已有持仓只产 NOOP。
- R6-01：当前日历一定无法入今日行、SimAccount 创建/回放真实失败，本次未执行写库接口；需可读错误日志或隔离 Java 测试。
- R6-02：2027真实启动路径和届时文件缺失，本次未启动；2027-02-17节日归属也不据原断言认定。
- R6-03：旧版本在当时数据库的“首因/恒阻断”、249行断崖、155日标定，需历史版本与当时数据快照；当前9月8日为5456行。
- R7-01：LastResult=1与启动日志缺EXITCODE的原因，需调度事件/父子退出码证据。没有擅自停止进程制造日志。

## 五、验证过程中新发现的问题

**无新增独立业务缺陷。** 发现的证据偏差（R5多余金叉条件、缓存912缺失键、R6状态实际已持久化、调用点计数、R7时刻混淆）均归入对应原条目，不重复开工单。

## 六、逐条验证记录（可直接作为审阅依据）

复现统一先在 PowerShell 执行以下设置；`py -3.13` 已实测指向可用 Python313。命令中的“；”分隔多个独立命令，请逐条执行；所有新增探针均内嵌在本文件末尾，不另建脚本。数据/接口输出仅摘关键值，完整返回体已在本次会话工具输出。

```powershell
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING='utf-8'
$env:PYTHONDONTWRITEBYTECODE='1'
```

### R1-01

原断言：东财 K 线的标签时间是该 bar 的结束时刻，但代码把标签当成起始时刻。

我的验证：真实东财 1/5 分钟 payload 交叉对照并运行真实解析器；时间错位和 as_of 多丢一根均复现。待修复，优先统一区间语义。

结果：✅ 确认存在，待修复（强证据）。

证据命令：

```powershell
py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_bar_semantics.py'
```

```powershell
py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_asof_loss.py'
```

实际输出摘录：

```text
同标签一致 10 处 / 不一致 0 处
as_of=10:32：采纳最后标签 10:25；实际已知最后标签 10:30
全日最后一根 start_ts=15:00:00 end_ts=15:05:00
```

### R1-02

原断言：盘中重启会在 bar 序列里留下 2 根断层。

我的验证：10:32 场景确有两个真实区间未进入序列，待修复该场景。不是任意启动都恒缺两根。原“只修标签会产生重复 start_ts”的修复陷阱被反证：内存中同时改正 start/end 与 as_of 采纳，仍保留 discard=True，得到无重复序列，仅首个不完整实时桶缺失；不要据此错误陷阱设计工单。

结果：⚠️ 部分成立 / 需修正（强证据）。

证据命令：

```powershell
py -3.13 -B 'E:\Project\.tmp_quant_watch\repro_gap.py'
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"
```

实际输出摘录：

```text
最后种子 start_ts=2026-10-13 10:25:00
快照10:35:10 closed=[]；10:40:10 closed=['10:35']
相邻 start_ts 10:25 -> 10:35，10分钟
corrected seed + discard True ['10:20', '10:25', '10:35'] duplicate_start_ts 0
```

### R1-03

原断言：开盘前启动时，当日第一根完整 bar 被无条件丢弃。

我的验证：运行真实 BarStore，seed(discard_first_forming=True)，从开盘首个快照跨桶推进，首桶确实丢弃。待修复；确认范围为该种 seed 路径，不覆盖恢复 checkpoint 或显式离线 seed 等免丢弃路径。

结果：✅ 确认存在，待修复（强证据）。

证据命令：

```powershell
py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_round1b.py'
```

实际输出摘录：

```text
快照09:30:05 closed=[]
快照09:35:05 closed=[]
快照09:40:05 closed=['09:35']
今日 history=['09:35']；首根完整桶未保留
```

### R1-04

原断言：当日最后一根 bar 的信号有效期恒为 0，一诞生即被判定失效。

我的验证：末根 adapter 有效期为零确证。但真实 run_once 在15:00已因交易时段返回 SLEEPING，未到信号失效分支；原“尾盘信号被该分支彻底吞掉”的生产因果没有证明。代码现有 EXPIRED_BAR、INVALIDATED 和 SIGNAL 留痕也与原“无成因、彻底消失”不符（后两项仅阅读核对）。零窗口是否需改须明确收盘后是否仍产出不可执行分析信号；不得为此放行收盘后交易。

结果：⚠️ 部分成立 / 需修正（强证据）。

证据命令：

```powershell
py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_round1b.py'
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"
```

实际输出摘录：

```text
bar_ts=2026-10-13T15:00:00+08:00
valid_until=2026-10-13T15:00:00+08:00
end15:00 action buy seconds0.0
at15:00 SLEEPING signals []
```

### R1-05

原断言：同一个真实区间，实时 live bar 存起点，东财 K 线存终点。

我的验证：解析器/实时路径口径冲突有运行证据，统一口径待修复。但当前两个来源没有任何同股票同日交集，无法实测原断言的“同一真实区间在主库有两种口径/重复行”；唯一键无法隔离口径是推断，不是已发生重复。需来源重叠的只读历史快照或获准的隔离写入复现，才能确认重复/覆盖后果。

结果：⚠️ 部分成立 / 需修正（中证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"
```

```powershell
py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_asof_loss.py'
```

实际输出摘录：

```text
eastmoney_kline 25040 行，start_ts 最大15:00
eastmoney_batch 80 行，start_ts 最大14:50
shared_code_day 0
shifted_pairs 0
```

### R2-01

原断言：网络抓取路径没有日期闸门，导致本地闸门被绕过。

我的验证：先真实抓取002388，再把完全相同返回值分别注入本地分钟线和网络客户端替身，运行真实 prepare：本地拒绝，网络接受并ready=True。文件状态与日志用内存替身隔离。待修复网络日期闸门；结论不依赖仅阅读推断。

结果：✅ 确认存在，待修复（强证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_STALE_BEGIN\n(.*?)^# CODEX_PROBE_STALE_END',t)[1])"
```

实际输出摘录：

```text
network result 30 dates ['2026-09-30']
allowed ['2026-10-12', '2026-10-13'] usable False
real prepare ready True errors [] local_hit 0
```

### R2-02

原断言：停牌股复牌首根 bar 会被伪造出买入信号（+3%即触发）。

我的验证：真实旧种子加人工构造复牌 bar 确实产生buy，但复牌 bar 非真实成交；修改种子日期的新鲜对照同样buy。MA_CROSS只看价格，数值交叉符合策略定义，不能把它直接称为伪造或证明表现变差。停牌/陈旧数据资格问题归R2-01；真实复牌首根及风险影响尚缺实盘数据和信号有效性定义。

结果：⚠️ 部分成立 / 需修正（中证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_STALE_BEGIN\n(.*?)^# CODEX_PROBE_STALE_END',t)[1])"
```

实际输出摘录：

```text
synthetic resume 0.03 9.37 buy ma_short_prev=9.1 ma_long_prev=9.1
synthetic resume 0.05 9.55 buy
synthetic resume 0.08 9.83 buy
fresh-date control action buy
```

### R2-03

原断言：f124 是服务端逐股刷新时间，不是成交时间。

我的验证：收盘后15:34时间值足以反对“普通A股最后成交时间”。但19个值/分组本身不能证明服务端的精确刷新机制；没有抓到盘中边界错桶。保留字段认知订正，不能把“40秒抖动导致准确性损失”标待修复。精确语义需官方字段定义或盘中同步成交对照。

结果：⚠️ 部分成立 / 需修正（中证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_F124_BEGIN\n(.*?)^# CODEX_PROBE_F124_END',t)[1])"
```

实际输出摘录：

```text
取回60只；不同f124取值19个
1791876867=2026-10-13 15:34:27，覆盖8只
1791876843=2026-10-13 15:34:03，覆盖6只
1791876885=2026-10-13 15:34:45，覆盖5只
```

### R3-01

原断言：规则分阈值35与公式实际值域0–50不匹配，T1候选结构上近乎恒为空。

我的验证：稀疏和当日空候选确证，保存快照诊断能定位到阈值；不过35在0–50内可达，取值域缩减不自动要求等比例降为17.5。19/33也不是“恒空”，TopN为上限。阈值设计错误及调低后准确性改善未被验证，属于产品标定决策。原脚本复算的是数值过滤上界，不能冒称完整Java生成流程。

结果：⚠️ 部分成立 / 需修正（强证据）。

证据命令：

```powershell
py -3.13 -B 'E:\Project\.tmp_quant_watch\score_dist.py'
```

```powershell
py -3.13 -B 'E:\Project\.tmp_quant_watch\gate_multi_day.py'
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"
```

实际输出摘录：

```text
最高43.84；>=35为6/5452（0.11%）
33日过滤交集为0：14/33；排除涨停后为0：19/33
排除涨停后最大2，中位0
当前接口 total_scored=1265，BELOW_RULE_SCORE_THRESHOLD=1265，status=NO_ELIGIBLE_CANDIDATE
```

### R3-02

原断言：打分表一张空、一张停在半年前，读取无陈旧保护。

我的验证：数据库和真实GET同时证实旧/空数据被直接返回且无stale标识，待修复仍在暴露的读取接口或明确废弃定位。未证明这两个接口已影响当前WATCHLIST/T1推荐；不提升为推荐主因。

结果：✅ 确认存在，待修复（强证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_API_BEGIN\n(.*?)^# CODEX_PROBE_API_END',t)[1])"
```

实际输出摘录：

```text
multi_rows 0
trend_rows 3345，MIN/MAX=2026-04-07
GET trend/scores：trade_date=2026-04-07，total=3345
GET multi-dim/scores：trade_date=''，total=0
```

### R4-01

原断言：T1无当日快照时，观察池为空，不报错、不兜底，静默空转。

我的验证：无需修复原工单中的静默空转问题。空池/无兜底属实，但已显式报错且live阻断；使用正确下一观察日2026-10-14复跑，排除了同日T1日期资格混淆。原结论遗漏当前错误传播和live阻断。不能仅因没有热门股兜底就要求把数据失败变成放行。

结果：❌ 证伪，无需修复（强证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_API_BEGIN\n(.*?)^# CODEX_PROBE_API_END',t)[1])"
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"
```

实际输出摘录：

```text
universe T1 t1_unavailable 0 ['T1_UNAVAILABLE']
R4-01 real loop BLOCKED
errors包含T1_UNAVAILABLE
errors包含DATA_SOURCE_BLOCKED:T1 观察池不可用，live 不允许空名单继续
```

### R4-02

原断言：V1风控闸NEAR_LIMIT_UP仍硬编码9.0%。

我的验证：同报价、同限价、同账户输入运行两真实闸门，北交所反向结论确证。待修复V1与watch板块阈值一致性；两创默认板块闸遮蔽差异，不以它们替代北交所对照。

结果：✅ 确认存在，待修复（强证据）。

证据命令：

```powershell
py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_risk_gate_mismatch.py'
```

实际输出摘录：

```text
600519 +5%：V1 PASS，watch PASS
920627 +15%：板块涨停30%，正确近涨停阈值29%
V1 NEAR_LIMIT_UP，watch PASS
```

### R4-03

原断言：默认禁买两创占热门股36%，无allow_bse开关，北交所默认放行。

我的验证：默认配置、池构成和无allow_bse可复现；R4-02也实际证明默认北交所买入可通过。该组合是否遗漏不能从风险不对称推定；默认禁买两创为已声明策略。未证明监控展示误导，也不能以资源消耗为由要求过滤观察池。需风险策略约定才可将新增BSE开关列为修复需求。

结果：⚠️ 部分成立 / 需修正（强证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_QUOTA_BEGIN\n(.*?)^# CODEX_PROBE_QUOTA_END',t)[1])"
```

实际输出摘录：

```text
defaults False False；allow_bse exists False
pool500：MAIN295 CHINEXT144 BSE25 STAR36
blocked180（36%）
```

### R5-01

原断言：每轮3/每日20配额永久丢弃43%~80%的有效买入信号，保留哪3笔由观察池顺序决定。

我的验证：配额顺序依赖和事件不补发已用真实loop确认，可针对这个机制修复/明确策略。但原探针多了s_curr>l_prev，且缓存25119键与当前DB24208键不一致（912缓存键缺失），不能保留“生产有效信号损失43%~80%”。新复算是持仓=0的原始金叉，仍未过资金/板块/持仓/覆盖风控；above3不是实际拒单数。当前进程是SHADOW、Live=0，每日提交计数为0，日20对当前路径不按原文方式消耗。未证实策略收益变差、早盘平庸/尾盘更强、或需追逐已失效信号；完整量化需当时观察池顺序、状态、报价和实际风控回放。

结果：⚠️ 部分成立 / 需修正（强证据）。

证据命令：

```powershell
py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_cycle_quota2.py'
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_QUOTA_BEGIN\n(.*?)^# CODEX_PROBE_QUOTA_END',t)[1])"
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"
```

实际输出摘录：

```text
原导出探针：47/109=43.1%，56/87=64.4%
当前DB真实策略：2026-10-12 crosses147 above3=77 fraction0.5238
当前DB真实策略：2026-10-13 crosses91 above3=59 fraction0.6484
missed_by_probe_extra_condition：39、4
4只金叉第四只MAX_TRADES_PER_CYCLE；倒序后换为另一只
下一bar全hold，没有补发
```

### R5-02

原断言：V1对非买入意图一律REJECT且不写原因码，拒绝不可审计、无出场能力。

我的验证：显式注入SELL_LIMIT的空原因拒绝成立，可补充不支持类型原因码。但“非BUY一律拒”被NOOP反证；真实decide为入场路径，已有持仓不生成卖单。未证明生产卖出被V1误拒或当前系统无出场；watch有卖出，当前又是SHADOW。不要按“买得进卖不出”定级。

结果：⚠️ 部分成立 / 需修正（强证据）。

证据命令：

```powershell
py -3.13 -B 'E:\Project\.tmp_quant_watch\probe_v1_sell.py'
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_V1_BEGIN\n(.*?)^# CODEX_PROBE_V1_END',t)[1])"
```

实际输出摘录：

```text
SELL_LIMIT：REJECT，reject_reason=''，reason_codes=[]
NOOP：PASS，reject_reason=''，reason_codes=[]
V1 position0：BUY_LIMIT
V1 position1000：NOOP ALREADY_FILLED
```

### R6-01

原断言：Java日历结构上无法回答今天是否交易日，今天恒false，8个调用点6个裸用。

我的验证：今天被误呈现为已知休市确证，待修复未知覆盖与休市的区分；4裸用点应审查。不是结构上不能加入今天，也不是每一天恒false，原8/6把非调用等混计；当前日历status=PASS，整体BLOCKED来自data_gate，不能归因日历闸。未调用会写库的会话创建/交易接口，因此“无法创建/回放”的后果仍仅阅读推断。

结果：⚠️ 部分成立 / 需修正（强证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"
```

实际输出摘录：

```text
517行，全交易日，覆盖2024-08-19..2026-10-12
今日行0；最新ingest=2026-10-13 13:21:50.386565
calendar actual callsites6，裸用4、配套覆盖2
calendar API today_is_trading_day=False
market_session phase=HOLIDAY，label=休市（非交易日）
```

### R6-02

原断言：Python日历不校验覆盖，未知日期按周一至周五放行。

我的验证：真实文件加载和is_trading_day执行证实无覆盖保障，待修复有效年份/覆盖契约。明确仅确认默认日历服务；没有启动2027长循环，也没有验证届时新文件是否缺失。“2027-02-17是春节”未经权威日历证实，不用这个日期的节日归属作为证据；“2027整体失效”应改为旧文件/回退服务对超覆盖工作日无法识别休市。

结果：✅ 确认存在，待修复（强证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_API_BEGIN\n(.*?)^# CODEX_PROBE_API_END',t)[1])"
```

实际输出摘录：

```text
calendar available=True，holidays19，2026-01-01..2026-10-07，extra0
2026-10-13 True
2027-01-01 True
2027-02-17 True
```

### R6-03

原断言：T1为空首因是覆盖率闸门分母，阈值第二因。

我的验证：旧分母与上界数字独立复算成立，但这不是当前待修问题：源码已按活跃键改，运行快照也通过覆盖率。原“已证实”的历史因果只引用注释，并非作者运行证据；不能据此给当前修复工单。9月8日当前5456行，无法用当前库重现原“249行断崖/155日标定”时点；原版本、当时DB快照与决策日志缺失，历史“首因/恒阻断”仍无法完整验证。

结果：⚠️ 部分成立 / 需修正（强证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"
```

实际输出摘录：

```text
old_expected5835 needed5544 max_daily5477 max_ratio0.9386
active_expected5465 active_covered5453
当前快照 daily_coverage=0.9978042086001829
当前快照 status=NO_ELIGIBLE_CANDIDATE
september8_rows5456
```

### R6-04

原断言：T1判定状态不持久化不返回，质量闸对存量快照恒不触发。

我的验证：无需修复“全不持久化/不返回/恒死代码”工单：8列里ecology_json已经承载元数据，GET会提到顶层。部分更早legacy行无元数据、无快照日期只有found=false，不代表所有快照。status闸允许NO_ELIGIBLE_CANDIDATE并返回空是有效路径，不能因这个样本未拒绝就称死代码。数据当前11行，原文5行也已过时。

结果：❌ 证伪，无需修复（强证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_API_BEGIN\n(.*?)^# CODEX_PROBE_API_END',t)[1])"
```

实际输出摘录：

```text
SQL ecology_json eligibility.status=NO_ELIGIBLE_CANDIDATE
SQL ecology_json eligibility.daily_coverage=0.9978042086001828
API status=NO_ELIGIBLE_CANDIDATE
API threshold_version=t1_rule_v2，quality_status=PIT_LIMITED
API daily_coverage=0.9978042086001829，probability_verified=False
```

### R7-01

原断言：仅1个Quant任务，Last Result=1，T1调度器未注册。

我的验证：Windows任务与非零码事实成立，日志无EXITCODE也成立；原因仍未决，不能判循环已死。Windows没有T1任务不等于Spring调度器未注册：后端状态接口存在，功能明确关闭，属既定手动策略，不能报遗漏注册缺陷。仅阅读配置/注册过程未作运行注册证明，准确表述是“调度关闭且未运行”。

结果：⚠️ 部分成立 / 需修正（强证据）。

证据命令：

```powershell
schtasks /Query /FO LIST /V /TN QuantWatchShadow
```

```powershell
schtasks /Query /FO CSV | Select-String 'Quant|T1'
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"
```

实际输出摘录：

```text
QuantWatchShadow Ready，NextRun2030/12/31 23:58:00
LastRun2026/10/13 15:40:49，LastResult1
schedule API present=False，schedule_enabled=False
cron=0 10 15 * * MON-FRI，zone=Asia/Shanghai
```

### R7-01b

原断言：实盘跑WATCHLIST而非默认T1，R4-01对当前运行不适用。

我的验证：WATCHLIST范围订正确认一致，无需修复；这是运行中的SHADOW观察任务，不是实际提交交易。除此之外R4-01静默无错误的断言已被独立证伪，不能只保留旧条目并声称仅不影响本次。原节无**状态**行，验证块置于标题下，不补写或改写作者状态。

结果：✅ 订正确认一致，无需修复（强证据）。

证据命令：

```powershell
Get-Content -Encoding UTF8 'E:\Project\.tmp_quant_watch\launch.cmd'
```

```powershell
Get-Content -Encoding UTF8 'E:\Project\.tmp_quant_watch\launch.out.log'
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_API_BEGIN\n(.*?)^# CODEX_PROBE_API_END',t)[1])"
```

实际输出摘录：

```text
launch -ExecutionMode SHADOW -UniverseMode WATCHLIST
日志 Universe WATCHLIST，ExecutionMode SHADOW，Live0
universe WATCHLIST hot_stocks 200 []
```

### R7-02

原断言：11:30信号在11:35休市时到期；14:55信号窗口0秒。

我的验证：午休valid_until不是下一根bar收盘确证，可修正文案/时段契约。14:55结束的bar有300秒，零窗口在15:00结束的末根；若14:55指起点，应写清。原“午休还会同根撮合”被R7-03和交易时段反证。原状态只有“代码语义确定”，本次才执行adapter，不能把原状态视为原作者探针已证实。

结果：⚠️ 部分成立 / 需修正（强证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"
```

实际输出摘录：

```text
end11:30：valid_until11:35，seconds300.0
end14:55：valid_until15:00，seconds300.0
end15:00：valid_until15:00，seconds0.0
in_trading_window11:30 False；11:30:01 False
```

### R7-03

原断言：用刚收盘bar的close决策，并按同一价格、同一根volume成交，潜在前视。

我的验证：无需修复本候选。用真实OMS/内部Broker、真实SQL schema的内存SQLite复现：同根拒撮合，下一根开盘9.5加20bps得到9.519（限价10），容量取下一根volume。代码/注释声明NEXT_BAR。限价夹取和参与率是仿真模型，不构成本断言的同根前视；未对模型所有成交偏差作保证。

结果：❌ 证伪，无需修复（强证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"
```

实际输出摘录：

```text
R7-03 accepted True
same-bar fills []
next-bar volume500 fills []
next-bar volume1000 fills [(100,9.519,'2026-10-13T10:05:00','NEXT_BAR_PARTICIPATION_CAP')]
broker model NEXT_BAR
```

### COOLDOWN（原无ID）

原断言：aware/naive中毒导致反向侧永久拦截，买得进卖不出。

我的验证：当前生产路径断言证伪，无需按生产永久锁卖故障修复。默认真实clock/loop为naive，写回和恢复同契约；当前SHADOW冷却表为空。人工注入aware坏值仍可阻断，属输入污染的条件风险，并未证实由当前生产入口产生；且按日状态重置，不是跨日永久。若提供真实含aware的历史状态/自定义now入口，应另验。

结果：❌ 证伪，无需修复（中证据）。

证据命令：

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_DB_BEGIN\n(.*?)^# CODEX_PROBE_DB_END',t)[1])"
```

```powershell
py -3.13 -B -c "import pathlib,re;t=pathlib.Path(r'E:\Project\quant-accuracy-issues.md').read_text(encoding='utf-8');exec(re.search(r'(?ms)^# CODEX_PROBE_CORE_BEGIN\n(.*?)^# CODEX_PROBE_CORE_END',t)[1])"
```

实际输出摘录：

```text
default WatchLoop clock tzinfo None
default loop clock roundtrip sell16min True
当前生产 day_trade_count0，cooldowns{}
injected aware timestamp sell after1day False
```

## 七、内嵌只读复现探针

这些是本次运行的内存探针，保留可复跑代码而不新增文件；CORE 包含文件写入审计拦截，其他探针仅读取数据库/HTTP/文件。CORE 的 _load_state / _event_log / _persist_state / _finish 是内存替身，策略、真实 loop 信号/风控流程、OMS、Broker 未改业务逻辑。数据库查询为 SELECT/DESCRIBE，SQLite 在 :memory:，禁止把路径换成生产运行库。

### 探针 CORE

```python
# CODEX_PROBE_CORE_BEGIN
import sys,sqlite3,json
from pathlib import Path
from datetime import datetime,timedelta
from unittest.mock import Mock
sys.path.insert(0,r'E:\Project\quant-trading-system\modules\compute-python')
from paper import clock
from paper.config import PaperConfig
from paper.models import Candidate,Quote,Balance,Intent,IntentType
from paper.watch.loop import WatchLoop
from paper.watch.models import WatchBar
from paper.watch.signal_adapter import SignalAdapter
from paper.watch.cooldown import CooldownBook
from paper.watch.state import WatchState
from paper.watch.internal_broker import InternalPaperBroker
from paper.watch.store import SqliteWatchStore
from paper.watch.oms import OmsService,OrderStatus
from paper.trading_calendar import load_calendar_from_file
def readonly(event,args):
 if event=='open' and ((isinstance(args[1],str) and any(c in args[1] for c in 'wax+')) or (args[2] and args[2] & (1|2|64|512|1024))): raise RuntimeError('Filesystem write blocked: '+str(args[0]))
 if event in {'os.mkdir','os.remove','os.rename','os.rmdir'}: raise RuntimeError('Filesystem mutation blocked')
sys.addaudithook(readonly)
T=datetime(2026,10,13,10,0)
def bars(code,prices,at=T):
 return [WatchBar(code=code,period_min=5,open=p,high=p,low=p,close=p,volume=1000,start_ts=at-timedelta(minutes=5*(len(prices)-i)),end_ts=at-timedelta(minutes=5*(len(prices)-i-1)),closed=True) for i,p in enumerate(prices)]
def memory_loop(cfg,quotes=None,seed=None,kline=None):
 loop=WatchLoop(cfg,quotes or Mock(),Mock(),log_base=Path(r'E:\Project\.tmp_quant_watch'),seed_bars=seed,kline_client=kline,now_fn=lambda:T)
 loop._event_log=lambda day:Mock()
 loop._load_state=lambda day:(WatchState(day),CooldownBook(15))
 loop._persist_state=lambda day:None
 loop._finish=lambda tick:tick
 return loop
print('R7-02/R1-04 boundaries')
for hh,mm in [(11,30),(14,55),(15,0)]:
 end=T.replace(hour=hh,minute=mm)
 a=SignalAdapter();a.seed('600519',bars('600519',[10.]*30))
 b=bars('600519',[10.3],at=end)[0];s=a.on_closed_bar('600519',b,0)
 print('end',end.time(),'action',s.action,'valid_until',s.valid_until,'seconds',(datetime.fromisoformat(s.valid_until)-datetime.fromisoformat(s.bar_ts)).total_seconds())
# Real loop: four simultaneous crosses, then a further bar. All filesystem sinks replaced.
class Quotes:
 def snapshot_many(self,candidates,*,run_id='adhoc',received_at=None):
  return {c.stock_code:(Quote(c.stock_code,'test','SH',10.3,pct_chg=3,volume=100000,market_time=received_at,received_at=received_at,source='eastmoney_batch',limit_up_price=11,limit_up_source='test'),None) for c in candidates}
codes=['600001','600002','600003','600004']
for order in [codes,codes[::-1]]:
 cfg=PaperConfig(api_key='k',max_name_weight=.1,max_total_weight=1,index_circuit_breaker=False,market_pipeline_v2=False)
 seed={c:bars(c,[10.]*30) for c in order}
 loop=memory_loop(cfg,Quotes(),seed)
 cands=[Candidate(c,'test','SH',source='watchlist') for c in order]
 ticks=[loop.run_once('2026-10-13',candidates_override=cands,now=t) for t in [T+timedelta(seconds=1),T+timedelta(minutes=5,seconds=1),T+timedelta(minutes=10,seconds=1)]]
 print('R5-01 order',order)
 for tick in ticks[1:]:
  print('signal',[(s['code'],s['action']) for s in tick.signals],'intent',[(x['stock_code'],x['risk_reject_reason']) for x in tick.intents])
 print('clock now tzinfo',loop.now_fn().tzinfo)
# Snapshot absent, correct observation day; actual loop must BLOCK.
cfg=PaperConfig(api_key='k',watch_universe_mode='T1',watch_live=True)
loop=memory_loop(cfg)
loop._calendar=load_calendar_from_file(Path(r'E:\Project\quant-trading-system\modules\compute-python\paper\trading-calendar.2026.json'))
tick=loop.run_once('2026-10-13',live=True,confirm=True,now=datetime(2026,10,14,10))
print('R4-01 real loop',tick.status,tick.errors)
# Broker and OMS backed by in-memory SQLite using real schema/methods.
store=SqliteWatchStore.__new__(SqliteWatchStore);store._conn=sqlite3.connect(':memory:',isolation_level=None);store._conn.row_factory=sqlite3.Row;store._migrate()
cfg=PaperConfig(paper_session_id='independent',internal_initial_cash=100000,internal_fill_participation=.1)
broker=InternalPaperBroker(store,cfg,now_fn=lambda:T)
oms=OmsService(store,session_id=cfg.paper_session_id,channel='INTERNAL_PAPER')
o,_=oms.create(run_id='probe',strategy='MA_CROSS',signal_bar_ts=T.isoformat(),code='600519',side='buy',qty=100,limit_price=10,ttl_sec=600,now=T)
o=oms.transition(o.client_order_id,OrderStatus.SUBMITTING);print('R7-03 accepted',broker.submit_local_order(o).ok)
b=WatchBar(code='600519',period_min=5,open=9.5,high=10,low=9,close=9.8,volume=1000,start_ts=T-timedelta(minutes=5),end_ts=T,closed=True)
print('same-bar fills',broker.process_bars([b],now=T))
b.start_ts=T;b.end_ts=T+timedelta(minutes=5);b.volume=500
print('next-bar volume500 fills',broker.process_bars([b],now=b.end_ts))
b.volume=1000
f=broker.process_bars([b],now=b.end_ts)
print('next-bar volume1000 fills',[(x['qty'],x['price'],x['bar_ts'],x['price_source']) for x in f])
print('broker model',cfg.internal_matching_model)
print('COOLDOWN')
now=clock.now();book=CooldownBook(15);book.record('600519','buy',now)
print('production now tzinfo',now.tzinfo,'stored',book.last_signal['600519']['at'],'sell after16min',book.can_trade('600519','sell',now+timedelta(minutes=16)))
bad=CooldownBook.from_dict({'600519':{'side':'buy','at':now.replace(tzinfo=clock.CN_TZ).isoformat()}},15)
print('injected aware timestamp sell after1day',bad.can_trade('600519','sell',now+timedelta(days=1)))

print('R1-02 repair-trap counterexample (in-memory timestamp correction)')
from paper.watch.bar_store import BarStore
from paper.watch.kline_client import _parse_eastmoney_klines
payload={'data':{'klines':['2026-10-13 10:25,10,10,10,10,10','2026-10-13 10:30,10,10,10,10,10']}}
raw=_parse_eastmoney_klines(payload,'600519',5,datetime(2026,10,13,10,32))
for x in raw:x.start_ts-=timedelta(minutes=5);x.end_ts-=timedelta(minutes=5)
# Correct interpretation also accepts the label 10:30, missing from original parser.
raw+=bars('600519',[10.],at=datetime(2026,10,13,10,30))
st=BarStore(5);st.seed('600519',raw,discard_first_forming=True)
for t in [datetime(2026,10,13,10,32),datetime(2026,10,13,10,35),datetime(2026,10,13,10,40)]:
 st.update_snapshot('600519',Quote('600519','test','SH',10,volume=1000,market_time=t,received_at=t),now=t)
ts=[b.start_ts.strftime('%H:%M') for b in st.history('600519')]
print('corrected seed + discard True',ts,'duplicate_start_ts',len(ts)-len(set(ts)))
print('R1-04 final close actual loop boundary')
loop=memory_loop(PaperConfig(api_key='k'),Quotes(),{'600519':bars('600519',[10.]*30)})
tick=loop.run_once('2026-10-13',candidates_override=[Candidate('600519','test','SH')],now=datetime(2026,10,13,15))
print('at15:00',tick.status,'signals',tick.signals)
from paper.decide import in_trading_window
print('in_trading_window11:30',in_trading_window(datetime(2026,10,13,11,30)),'11:30:01',in_trading_window(datetime(2026,10,13,11,30,1)))
# CODEX_PROBE_CORE_END
```

### 探针 STALE

```python
# CODEX_PROBE_STALE_BEGIN
import sys,json
from datetime import datetime
from pathlib import Path
from unittest.mock import Mock
sys.path.insert(0,r'E:\Project\quant-trading-system\modules\compute-python')
from paper.config import PaperConfig
from paper.watch.kline_client import KlineClient
from paper.watch.loop import WatchLoop,_series_is_usable,_allowed_bar_dates
from paper.watch.state import WatchState
from paper.watch.cooldown import CooldownBook
from paper.watch.models import WatchBar
from paper.watch.signal_adapter import SignalAdapter
from paper.models import Candidate
from paper.trading_calendar import load_calendar_from_file
cfg=PaperConfig(api_key='k',kline_source='eastmoney')
cal=load_calendar_from_file(Path(r'E:\Project\quant-trading-system\modules\compute-python\paper\trading-calendar.2026.json'))
T=datetime(2026,10,13,15,30)
bars,result=KlineClient(None,cfg).fetch_history('002388','',5,30,as_of=T,exchange='SZ')
print('network result',len(bars or []),'dates',sorted({b.start_ts.date().isoformat() for b in bars or []}))
if not bars: raise RuntimeError('no real bars')
allowed=_allowed_bar_dates(cal,'2026-10-13',2)
print('allowed',sorted(allowed),'usable',_series_is_usable(bars,21,allowed))
class Network:
 def fetch_history(self,*args,**kwargs):return bars,result
class Local:
 last_error=''
 def recent(self,*args,**kwargs):return {'002388':bars}
loop=WatchLoop(cfg,Mock(),Mock(),kline_client=Network(),minute_bar_client=Local(),log_base=Path(r'E:\Project\.tmp_quant_watch'),calendar=cal)
loop._load_state=lambda day:(WatchState(day),CooldownBook(15))
loop._event_log=lambda day:Mock()
errors=loop.prepare('2026-10-13',[Candidate('002388','test','SZ')],now=T)
print('real prepare ready',loop.adapter.ready('002388'),'errors',errors,'local_hit',loop._prepare_metrics.get('minute_bar_local_warmup',0))
for pct in [.03,.05,.08]:
 a=SignalAdapter();a.seed('002388',bars);p=round(bars[-1].close*(1+pct),2)
 b=WatchBar(code='002388',period_min=5,open=p,high=p,low=p,close=p,volume=1000,start_ts=datetime(2026,10,13,9,30),end_ts=datetime(2026,10,13,9,35),closed=True)
 s=a.on_closed_bar('002388',b,0);print('synthetic resume',pct,p,s.action,s.detail)
# Negative control: a recent flat seed generates the same cross. Gap is not necessary.
fresh=[WatchBar(code=b.code,period_min=b.period_min,open=b.open,high=b.high,low=b.low,close=b.close,volume=b.volume,start_ts=b.start_ts.replace(year=2026,month=10,day=12),end_ts=b.end_ts.replace(year=2026,month=10,day=12),closed=True) for b in bars]
a=SignalAdapter();a.seed('002388',fresh);s=a.on_closed_bar('002388',b,0)
print('fresh-date control action',s.action)
# CODEX_PROBE_STALE_END
```

### 探针 QUOTA

```python
# CODEX_PROBE_QUOTA_BEGIN
import sys,subprocess,json,collections,hashlib
from pathlib import Path
sys.path.insert(0,r'E:\Project\quant-trading-system\modules\compute-python')
from strategies.ma_cross_strategy import MACrossStrategy
from paper.config import PaperConfig
from paper.watch.universe import load_hot_stock_universe
from paper.risk import board_limit_pct
mysql=r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
sql="SELECT stock_code,trade_date,start_ts,close_price,source FROM market_data_minute WHERE period_min=5 AND trade_date IN ('2026-10-12','2026-10-13') ORDER BY stock_code,trade_date,start_ts"
out=subprocess.run([mysql,'--default-character-set=utf8mb4','-uroot','-p123456','quant_db','-B','-e',sql],capture_output=True,check=True).stdout.decode('utf-8')
rows=[x.split('\t') for x in out.splitlines()[1:]]
db={(c,d,t):float(p) for c,d,t,p,s in rows}
dump={}
for line in Path(r'E:\Project\.tmp_quant_watch\mdb.tsv').read_text(encoding='utf-8-sig').splitlines()[1:]:
 p=line.split('\t')
 if len(p)>=4:dump[tuple(p[:3])]=float(p[3])
print('minute dump vs DB','dump',len(dump),'DB',len(db),'missing_dump_keys',len(set(dump)-set(db)),'changed_close',sum(db.get(k)!=v for k,v in dump.items()))
groups=collections.defaultdict(list)
for c,d,t,p,s in rows:groups[(c,d)].append((t,float(p),s))
for d in ['2026-10-12','2026-10-13']:
 events=collections.Counter();extra=0;scount=0;evals=0
 for (c,day),rs in groups.items():
  if day!=d or len(rs)<30:continue
  scount+=1;strategy=MACrossStrategy();prices=[]
  for i,(t,p,s) in enumerate(rs):
   prices.append(p);action=strategy.on_bar({'close':p})
   if i<24:continue
   evals+=1
   if action=='buy':
    events[t[11:16]]+=1
    sp=sum(prices[i-5:i])/5;lp=sum(prices[i-20:i])/20;sc=sum(prices[i-4:i+1])/5
    if not (sc>lp):extra+=1
 total=sum(events.values());lost=sum(max(n-3,0) for n in events.values())
 print(d,'actual strategy','stocks',scount,'evals',evals,'crosses',total,'above3',lost,'fraction',round(lost/total,4),'missed_by_probe_extra_condition',extra)
 print('sources',dict(collections.Counter(s for (c,day),rs in groups.items() if day==d for t,p,s in rs)))
cfg=PaperConfig(api_key='k')
items=load_hot_stock_universe(cfg.t1_base,'2026-10-13',cfg.java_token,max_codes=500)
board=collections.Counter('CHINEXT' if x.stock_code.startswith(('300','301')) else 'STAR' if x.stock_code.startswith(('688','689')) else 'BSE' if board_limit_pct(x.stock_code,x.exchange_type)==30 else 'MAIN' for x in items)
print('R4-03 defaults',cfg.allow_chinext,cfg.allow_star,'allow_bse exists',hasattr(cfg,'allow_bse'),'pool',len(items),dict(board),'blocked',sum(x.board_blocked(cfg.allow_chinext,cfg.allow_star)!=None for x in items))
# CODEX_PROBE_QUOTA_END
```

### 探针 DB

```python
# CODEX_PROBE_DB_BEGIN
import subprocess,json,requests,sys,re
from pathlib import Path
from datetime import date
sys.path.insert(0,r'E:\Project\quant-trading-system\modules\compute-python')
from paper import clock
from paper.watch.cooldown import CooldownBook
from paper.config import PaperConfig
from paper.watch.loop import WatchLoop
from unittest.mock import Mock
m=r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
def sql(q):
 r=subprocess.run([m,'--default-character-set=utf8mb4','-uroot','-p123456','quant_db','-B','-e',q],capture_output=True,check=True)
 print(r.stdout.decode('utf-8').strip())
sql("SELECT COUNT(*) rows_n,MIN(trade_date),MAX(trade_date),SUM(is_trading_day=0) nontrading,MAX(ingested_at) last_ingest FROM trading_calendar; SELECT COUNT(*) today_rows FROM trading_calendar WHERE trade_date='2026-10-13'; SELECT source,COUNT(*) rows_n,MIN(start_ts),MAX(start_ts) FROM market_data_minute GROUP BY source; SELECT COUNT(*) shared_code_day FROM (SELECT stock_code,trade_date FROM market_data_minute GROUP BY stock_code,trade_date HAVING COUNT(DISTINCT source)>1) x; SELECT COUNT(*) shifted_pairs FROM market_data_minute a JOIN market_data_minute b ON a.stock_code=b.stock_code AND a.period_min=b.period_min AND b.start_ts=DATE_ADD(a.start_ts,INTERVAL 5 MINUTE) WHERE a.source='eastmoney_batch' AND b.source='eastmoney_kline'; SELECT COUNT(*) old_expected,CEILING(COUNT(*)*.95) needed,5477/COUNT(*) max_ratio FROM stock_info WHERE status=1 AND list_date<='2026-10-12'; SELECT MAX(n) max_daily FROM (SELECT trade_date,COUNT(*) n FROM market_data GROUP BY trade_date) x; SELECT COUNT(*) active_expected,SUM(EXISTS(SELECT 1 FROM market_data m WHERE m.stock_code=s.stock_code AND m.exchange_type=s.exchange_type AND m.trade_date='2026-10-12' AND m.open_price>0 AND m.close_price>0 AND m.high_price>=GREATEST(m.open_price,m.close_price) AND m.low_price>0 AND m.low_price<=LEAST(m.open_price,m.close_price))) active_covered FROM stock_info s WHERE s.status=1 AND s.list_date<='2026-10-12' AND EXISTS(SELECT 1 FROM market_data m WHERE m.stock_code=s.stock_code AND m.exchange_type=s.exchange_type AND m.trade_date BETWEEN DATE_SUB('2026-10-12',INTERVAL 60 DAY) AND '2026-10-12'); SELECT COUNT(*) september8_rows FROM market_data WHERE trade_date='2026-09-08'; SELECT COUNT(*) t1_rows FROM t1_candidate; SELECT trade_date,JSON_EXTRACT(ecology_json,'$.eligibility.status') decision_status,JSON_EXTRACT(ecology_json,'$.eligibility.daily_coverage') coverage FROM t1_candidate WHERE trade_date='2026-10-12'; DESCRIBE t1_candidate; SELECT COUNT(*) multi_rows FROM stock_multi_dim_score; SELECT COUNT(*) trend_rows,MIN(trade_date),MAX(trade_date) FROM stock_trend_score;")
base=Path(r'E:\Project\quant-trading-system\modules\backend-java\src\main\java')
calls=[]
for p in base.rglob('*.java'):
 for n,line in enumerate(p.read_text(encoding='utf-8').splitlines(),1):
  if re.search(r'tradingCalendarService\.isTradingDay\(',line):calls.append((p.name,n))
print('calendar actual callsites',len(calls),calls,'bare=4 guarded=2 (caller pairing separately reviewed)')
d=requests.get('http://127.0.0.1:8080/api/system/readiness',timeout=15).json()['data']
print('calendar API',d['calendar'],'market_session',d['market_session'])
d=requests.get('http://127.0.0.1:8080/api/research/t1/candidates/2026-10-12',timeout=15).json()['data']
print('T1 API',{k:d.get(k) for k in ['found','status','daily_coverage','threshold_version','quality_status','probability_verified']})
print('T1 API ecology status',d.get('ecology',{}).get('eligibility',{}).get('status'))
print('schedule API',requests.get('http://127.0.0.1:8080/api/research/t1/schedule/status',timeout=15).json()['data'])
p=Path(r'E:\Project\quant-trading-system\modules\compute-python\logs\watch\2026-10-13\state.json')
state=json.loads(p.read_text(encoding='utf-8'))
print('current production state', {k:state.get(k) for k in ['day_trade_count','cooldowns','updated_at']})
loop=WatchLoop(PaperConfig(),Mock(),Mock(),log_base=p.parent)
print('default WatchLoop clock tzinfo',loop.now_fn().tzinfo)
book=CooldownBook(15);n=loop.now_fn();book.record('600519','buy',n)
from datetime import timedelta
print('default loop clock roundtrip sell16min',book.can_trade('600519','sell',n+timedelta(minutes=16)))
# CODEX_PROBE_DB_END
```

### 探针 API

```python
# CODEX_PROBE_API_BEGIN
import sys,json,requests,subprocess
from pathlib import Path
sys.path.insert(0,r'E:\Project\quant-trading-system\modules\compute-python')
from paper.config import PaperConfig
from paper.trading_calendar import load_calendar_from_file
from paper.watch.universe import load_watch_universe
from paper.candidates import load_from_t1
for path in ['/api/system/readiness','/api/research/t1/candidates/2026-10-12','/api/research/t1/candidates/2026-10-13','/api/scoring/trend/scores?size=1','/api/scoring/multi-dim/scores?size=1']:
 r=requests.get('http://127.0.0.1:8080'+path,timeout=15);print(path,r.status_code,json.dumps(r.json(),ensure_ascii=False)[:5500])
for mode in ['T1','WATCHLIST']:
 c,s,e=load_watch_universe(PaperConfig(api_key='k',watch_universe_mode=mode),'2026-10-13',None,store=None);print('universe',mode,s,len(c),e)
cal=load_calendar_from_file(Path(r'E:\Project\quant-trading-system\modules\compute-python\paper\trading-calendar.2026.json'))
from datetime import date
print('calendar',cal.available,len(cal._holidays),min(cal._holidays),max(cal._holidays),len(cal._extra_workdays))
for d in ['2026-10-13','2027-01-01','2027-02-17']: print(d,cal.is_trading_day(date.fromisoformat(d)))
print('t1 loaded',len(load_from_t1('http://127.0.0.1:8080','2026-10-12')))
# CODEX_PROBE_API_END
```

### 探针 F124

```python
# CODEX_PROBE_F124_BEGIN
import sys
from pathlib import Path
sys.path.insert(0,r'E:\Project\quant-trading-system\modules\compute-python')
source=Path(r'E:\Project\.tmp_quant_watch\probe_f124_volume.py').read_text(encoding='utf-8')
exec(compile(source.split('print("\\n=== B.')[0],'<f124 read-only A>','exec'))
# CODEX_PROBE_F124_END
```

### 探针 V1

```python
# CODEX_PROBE_V1_BEGIN
import sys
from datetime import datetime
sys.path.insert(0,r'E:\Project\quant-trading-system\modules\compute-python')
from paper.decide import decide
from paper.models import Candidate,Quote,Balance
from paper.config import PaperConfig
c=Candidate('600519','test','SH',close=100,source='file')
q=Quote('600519','test','SH',100,pct_chg=1,volume=100000,limit_up_price=110,limit_up_source='probe')
for held in [0,1000]:
 out=decide([c],{'600519':q},Balance(total_assets=200000,avail_balance=200000),{'600519':held},[],PaperConfig(api_key='k'),datetime(2026,10,13,10))
 print('V1 position',held,[(x.intent_type.value,x.reason_codes) for x in out])
# CODEX_PROBE_V1_END
```

<!-- CODEX_INDEPENDENT_SUMMARY_END -->
