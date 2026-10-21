# 第 4 轮报告：修复 D-2（盘中信号状态的可解释性）

- 日期：2026-10-13
- 代码版本：`34098346` + 工作区改动（本轮新增 `MonitorView.vue` 改动，**不涉及任何打分/阈值**）
- 性质：可解释性缺陷修复 + 最小必要验证
- 脚本：`scripts/check_monitor_view.mjs`、`scripts/negative_control_loop_fixes.py`

---

## 1. 发现（缺陷复现）

**现象**：盘中监控页无法区分「预热不足」「bar 已过期」「正常观察」三类状态。

**证据（修复前）**

| 证据 | 位置 |
|------|------|
| `signal_status` 由后端正确写入 | `loop.py:2224`（INVALIDATED）、`:2237`（WARMUP） |
| 前端**零引用** `signal_status` | `grep signal_status modules/web` → 无命中 |
| 「参考状态」只由挂钟比较推导 | `MonitorView.vue:693-696`（`valid_until` / `quality_status`） |
| `holding_horizon` / `invalidation_conditions` 前端零引用 | `grep ... modules/web` → 无命中 |
| 二者只由**静态模板**写入 | `signal_adapter.py:116`（`holding_horizon` 默认恒为 `"UNVALIDATED"`） |

**影响**：用户看到的「已失效·仅供复核」只反映 `valid_until` 已过期，
无法区分成因；`holding_horizon="UNVALIDATED"` 这一诚实标注**到不了用户**，
与任务书 §1 第 7 项「盘中必须显示失效状态」直接冲突。

**确定性**：高（静态可验证，`grep` 零命中即为充分证据）。

---

## 2. 假设

前端不可解释的根因有两层，且**必须同时修**，只改一侧不能解决问题：

1. **数据层**：`holding_horizon` / `invalidation_conditions` 是与原因码无关的静态模板，
   即使渲染也传达不了信息。
2. **展示层**：`signal_status` 根本没被消费。

反之，若只补数据层，字段仍然不可见；只补展示层，字段仍是模板值。

---

## 3. 最小行动

### 3.1 Python（`loop.py:2239-2272`）

在两分支判定（过期 / warmup）**完成之后**写入权威值：

```
actionable = action ∈ {buy, sell} and ready_before and signal_status == "OBSERVE"
holding_horizon = "INTRADAY_EOD" if actionable else "UNVALIDATED"
invalidation_conditions =
    可执行 → ["下一根 bar 收盘（重新判定）", "行情过期或缺失",
              "策略或观察池变化", "当日收盘（本盘中信号到期）"]
    不可执行 → 必须给出去因，按 signal_status 映射：
              WARMUP      → "预热不足（未达所需 bar 数）→ 本信号不可执行"
              INVALIDATED → "bar 已过期（EXPIRED_BAR）→ 本信号不可执行"
              quality!=PASS → "行情覆盖不足（quality_status=...）→ 本信号不可执行"
              否则        → "当前无买卖动作（hold）→ 无可执行信号"
```

`INTRADAY_EOD` 的依据：`signal_adapter.py:114-115` 已用
`min(end + period, 当日 15:00)` 截断 `valid_until`，
因此可执行信号的有效期**上界就是当日收盘**，一个确定的口径。

### 3.2 前端（`MonitorView.vue`）

- 新增「信号状态」列（`观察中 / 预热不足 / 已失效` 三态标签）
- 新增「持有期口径」列
- 「触发」列 → 「触发 / 失效条件」（主行 + 次要说明行）
- 抽屉内新增「状态 / 持有期」「失效条件」两列
- 判定顺序改为：**有效期未知/已过期 → `signal_status` → 数据质量**
  （原先 `signal_status` 缺失，`quality_status` 排在第二位）

---

## 4. 验证

| 验证 | 命令 | 结果 |
|------|------|------|
| D-2 回归（正/反向） | `python -m pytest paper/tests/test_decision_audit.py -q` | **9 passed** |
| Python 全量 | `python -m pytest -q` | **352 passed**（基线 350，新增 2） |
| 反向对照（D-1/D-2/D-3） | `python research/.../negative_control_loop_fixes.py` | 还原原始 `loop.py` → **4 failed**；恢复修复版后**字节一致** |
| Vue SFC 可编译 | `node research/.../check_monitor_view.mjs` | SFC parse / `compileScript` / TS 语法 / 模板编译 **全部 OK**；三态标签与 D-2 绑定存在 |

**两个新增用例分别防住两种退化**：

- `test_warmup_signal_declares_why_it_is_not_actionable`
  → 防「不可执行却不说为什么」（断言失效条件含「预热不足」「不可执行」）
- `test_actionable_signal_declares_intraday_eod_horizon`
  → 防「一切都标成不可执行」（断言可执行信号为 `OBSERVE` + `INTRADAY_EOD` + 含「当日收盘」）

**反向对照证明**：4 个用例在原始代码上全部失败，说明它们确实捕获缺陷，
而不是同义反复。

---

## 5. 未做的验证（不得当作已通过）

1. **未执行全量前端构建**（`vite build`）：属高资源操作且会覆盖 `dist`，
   本轮只做「单文件可被 Vue 官方编译器解析 + 绑定存在」的最小必要校验。
2. **未在浏览器做端到端目视确认**：因此「用户实际能看到新列」这一断言
   **尚未**被真实验证。若要确认，需要构建前端并刷新页面。
3. **`market_wide_coverage` 恒为 `None`（D-4）仍未修**：
   默认 T1 模式下「全市场覆盖」一栏无意义，属同一「可解释性」主题的另一缺口。

---

## 6. 判定

| 问题 | 判定 |
|------|------|
| D-2 是否修复？ | **是**（数据层 + 展示层同时修复，有正/反向回归与编译校验） |
| 是否在真实界面确认？ | **否**（未构建前端、未见浏览器） |
| 是否构成准确性改善？ | **否** —— 纯可解释性修复，与收益/排序能力无关 |
| 策略准确性是否已改善？ | **目前无法证明策略准确性改善** |

**决策**：**保留**该修复。它是「可解释性与决策可信度」目标的直接进展，
但**不**改变任何评估结论。下一轮优先补**未修的可解释性缺口（D-4）**
与 **P0-3 元数据如实性**，两者都不依赖数据授权。
