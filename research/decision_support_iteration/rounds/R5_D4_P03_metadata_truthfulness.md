# 第 5 轮报告：修复 D-4 与 P0-3（覆盖率适用范围 + 数据日/状态码如实性）

- 日期：2026-10-13
- 代码版本：`34098346` + 工作区改动（本轮改动**不涉及任何打分/阈值/闸门灵敏度**）
- 性质：可解释性与元数据如实性修复 + 最小必要验证
- 脚本：`scripts/check_monitor_view.mjs`、`scripts/negative_control_p03_java.py`
- 上接：第 4 轮（D-2）

---

## 1. 发现

### 1.1 D-4：覆盖率一栏在 T1 模式下恒为固定字符串

`MonitorView.vue:493-496`（修复前）：

```ts
const marketWideCoverage = computed(() => {
  const raw = (lastCycle.value?.detail as any)?.metrics?.market_wide_coverage
  return raw == null ? '未扫描全市场' : `${(Number(raw) * 100).toFixed(2)}%`
})
```

后端 `loop.py:2632`（修复前）：

```py
"market_wide_coverage": round(quote_coverage, 6) if self._universe_source == "full_market"
    and not any(...) else None,
```

`_universe_source` 只在 `watch_universe_mode == "FULL"` 时为 `"full_market"`
（`universe.py:248-261`）。**默认 T1 模式下恒为 `None`** ⇒ UI 永远显示
「未扫描全市场」——一个不含数值的占位。

**两个具体危害**：
1. 同一轮的真实覆盖率（`quote_coverage`，口径 = 本轮候选池）其实**已算出**，
   却没有出现在这一栏，用户被引向一个无信息量的字符串。
2. 「未扫描全市场」易被读成「全市场数据缺失 = 数据缺陷」，而池内模式
   本来**就不打算**扫描全市场，属**口径不符**而非故障。这是错误的归因方向。

### 1.2 P0-3：状态码把「休市」与「数据缺失」混为一谈，且存在恒假元数据

`T1CandidateService.java:152-155`（修复前）：

```java
LocalDate actualDate = tradeDate;
if (todayByKey.isEmpty()) {
    return emptyDecision(tradeDate, "NO_DATA", "所选信号日无行情，暂不判断；请选择有数据的交易日");
}
```

`dailyBarClosed`（`:895-899`）是**纯挂钟**判断（`day < 今天` 或 `今天且已过 15:00`），
**不看交易日历**。因此请求周六/节假日时：判定「已收盘」→ 查询无数据 →
返回 `NO_DATA`。使用者无法区分「那天不是交易日」与「那天是交易日但数据缺失」。

**实测数据背景（只读）**：`trading_calendar` 仅 485 行、覆盖 **2024-08-19..2026-08-19**，
而 `market_data` 到 **2026-10-12**。日历比行情**晚覆盖 54 天**。
这使问题更严重：2026-09-08 之后行情断崖（每日仅 247–249 只），
若把区间外一律当作休市，会把**真实的数据断崖说成「非交易日」**。

**恒假元数据**：`actualDate` 在 `:152` 赋值为 `tradeDate` 后**再无重新赋值**
（11 处引用，`grep` 实测），故
- `:315`/`:334` 的 `data_date_fallback = !actualDate.isEqual(tradeDate)` **恒为 false**
- `emptyDecision` 里更是**硬编码** `data_date_fallback = false`

它向使用者暗示「存在回退能力、且本次未触发」，而本服务**从无回退路径**
（无行情时走的是阻断，不是替换数据日）。这不是缺少功能，而是**虚假的诚实信号**。

> 注：现有测试 `missingSignalDayDoesNotFallBackOrWriteSnapshot` 明确断言**不得**回退
> （并 verify 从未调用 `findLatestTradeDateOnOrBefore`）。
> 设计意图「不回退、只阻断」是**正确**的；缺陷在于元数据与状态码没有如实表达。

---

## 2. 假设

1. D-4 的根因是**口径未显式化**：后端用 `None` 兼表「不适用」与「不可用」两种含义，
   前端只能把它渲染成一句与数值无关的话。显式输出 `coverage_scope` 与
   `na_reason` 即可让「口径不符」与「取数失败」分开，且**不必**放宽任何闸门。
2. P0-3 的根因是**日历未被用于区分语义**，而不是缺少回退。
   引入交易日历判定即可分离「休市」与「缺失」；再对日历覆盖范围做边界判断，
   可避免把区间外误判成休市。
3. 恒假的 `data_date_fallback` 应当**删除**而非保留：保留一个永远为 false、
   却命名成「回退」的字段，本身就是在传递错误的能力信号。

---

## 3. 最小行动

### 3.1 D-4

**后端（`loop.py`）**：新增两个如实字段，并把三态显式化

```
coverage_scope                 = "FULL_MARKET" | "OBSERVATION_POOL"
market_wide_coverage           = 数值（仅 full_market 且池构建成功）否则 None
market_wide_coverage_na_reason = None | "UNIVERSE_NOT_FULL_MARKET" | "FULL_UNIVERSE_UNAVAILABLE"
```

**前端（`MonitorView.vue`）**：按 `na_reason` 渲染，并附口径说明
- `UNIVERSE_NOT_FULL_MARKET` → 「不适用（池内模式）」
- `FULL_UNIVERSE_UNAVAILABLE` → 「不可用（全市场池构建失败）」← **故障不得被说成不适用**
- 有数值 → 百分比 + 「口径=全市场」
- 提示行同时给出本轮真实覆盖（口径=候选池），使数值不再丢失
- 旧快照无该字段 → 「不适用（口径未标注）」，**不撒谎**

### 3.2 P0-3

**Java（`T1CandidateService.java`）**：

- `calendarKnowsTradingDay(day)`：包住 `isTradingDay`，异常时保守返回 `false`
  （不得把「查不到」当成「非交易日」）
- `calendarCoversDay(day)`：用 `coverageWindow()` 判断该日是否在日历覆盖区间内
- `todayByKey.isEmpty()` 时三态分流：

| 条件 | status | 含义 |
|------|--------|------|
| 日历覆盖且判定为交易日 | `NO_DATA` | 确定是**数据缺失/滞后** |
| 日历覆盖但判定为非交易日 | `NOT_A_TRADING_DAY` | 确定是**休市** |
| 该日不在日历覆盖区间内 | `UNKNOWN` | **不得妄断**，提示补齐日历 |

- 删除 `data_date_fallback`（`:334`、`:358`、`emptyDecision`、按日回溯白名单）
- `emptyDecision` 新增 `calendar_trading_day` 与 `data_cutoff`（均为如实字段）

**新增基础设施**：`TradingCalendarService.coverageWindow()` 返回 {最早, 最晚} 交易日。

**前端**：`research.ts` 删除 `data_date_fallback` 类型、新增 `calendar_trading_day`；
`T1Candidate.vue` 删除永不可达的 `data_date_fallback` 渲染分支，
改为按 `calendar_trading_day` + `status` 显示「该日非交易日」或「日历未覆盖该日」。

> 全程**未**放宽 0.95 覆盖率闸、**未**改变任何打分逻辑、**未**新增回退、
> **未**执行任何写库操作。

---

## 4. 验证

| 验证 | 命令 | 结果 |
|------|------|------|
| Java 受影响用例 | `mvn -o -Dquant.skipFrontendBuild=true -Dtest=T1CandidateServiceTest test` | **18/18 通过**（原 15，+3） |
| Java 全量 | `mvn -o -Dquant.skipFrontendBuild=true test` | **452 tests, 0 failures, BUILD SUCCESS** |
| Python 全量 | `python -m pytest -q`（`modules/compute-python`） | **354 passed**（基线 350，+4） |
| 反向对照（D-1/D-2/D-3/D-4） | `python research/.../negative_control_loop_fixes.py` | 原始 `loop.py` 下 **6 failed**；恢复后**字节一致** |
| 反向对照（P0-3，Java） | `python research/.../negative_control_p03_java.py` | 原始实现下 **4 failures**（行为断言失败，非仅编译失败）；恢复后**字节一致** |
| Vue 双文件可编译 | `node research/.../check_monitor_view.mjs` | MonitorView + T1Candidate 的 SFC parse / compileScript / TS / 模板编译**全 OK**；D-2/D-4/P0-3 断言通过 |

**P0-3 反向对照的口径说明（重要）**：首版脚本把 `TradingCalendarService.java` 一并回退，
得到的是**编译错误**（缺 `coverageWindow()`）。这只证明「方法不存在」，
不能证明用例捕获了缺陷。因此改为**只回退 `T1CandidateService.java`**，
保留纯基础设施新增，使原始实现仍可编译——此时失败来自**真实行为差异**
（断言 `expected:"NOT_A_TRADING_DAY"/"UNKNOWN"` 但 `was:"NO_DATA"`）。
这才是有效的捕获证据。

---

## 5. 未做的验证（不得当作已通过）

1. **未执行全量前端构建**（`vite build`）：属高资源操作且会覆盖 `dist`。
   只做了「两个 .vue 可被 Vue 官方编译器解析 + 关键绑定存在」的最小编译校验。
2. **未在浏览器做端到端目视确认**：因此「用户实际能看到新文案/新状态」**尚未**真实验证。
3. **`TradingCalendarService` 新增的 `coverageWindow()` 未单独写单测**，
   其正确性由 `T1CandidateServiceTest` 的间接验证与既有
   `TradingCalendarServiceTest`（7 例，仍全通过）覆盖。
4. **日历仅到 2026-08-19 这一数据缺口未修**（需写库，属"补全 `trading_calendar`"，
   须先确认写库范围）。因此 2026-09-08 之后的候选生成仍不可用——
   本轮只是让它**如实报告** `UNKNOWN`，而不是修好数据。

---

## 6. 判定

| 问题 | 判定 |
|------|------|
| D-4 是否修复？ | **是**（后端三态字段 + 前端按原因渲染，有正/反向回归与编译校验） |
| P0-3 是否修复？ | **是**（状态码三态分离、删除恒假字段，Java 全量 452 例通过） |
| 是否在真实界面确认？ | **否**（未构建前端、未见浏览器） |
| 是否构成准确性改善？ | **否** —— 纯可解释性/如实性修复，与收益或排序能力无关 |
| 生产链路是否已可生成候选？ | **否** —— 2026-09-08 后行情断崖未修，仍无法生成 |
| 策略准确性是否已改善？ | **目前无法证明策略准确性改善** |

**决策**：**保留**两项修复。它们把「系统说自己做了什么」与「系统实际做了什么」
对齐——这正是 §1 第 2/7 项与 §2 对「不得用口径变化冒充改善」的要求。
但**不**改变任何评估结论。

**下一轮优先级**：
1. **前端端到端确认**（补第 4/5 轮共同缺口）——需先确认是否允许执行构建；
2. **补全 `trading_calendar` 至 2026-10**（幂等插入，需先确认写库范围），
   这是让 2026-09/10 候选生成真正可用的**前置条件**；
3. D-6（提醒级去重/上限）与 D-5（`strategy_state` 只写不读）。
