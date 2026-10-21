# 第 8 轮报告：MonitorView 前端装配的**可执行**验证（DOM-free 逻辑 harness）

- 日期：2026-10-13
- 代码版本：`34098346` + 工作区改动（**本轮未改任何生产代码**）
- 性质：**验证轮**（新增验证工具 + 对既有修复做执行级复核）
- 新增工具：`scripts/harness-loader.mjs`、`scripts/register-harness.mjs`、
  `scripts/render_monitor_logic.mjs`
- 上接：第 4 轮（D-2 信号可解释性）、第 5 轮（D-4 覆盖率口径 + P0-3 交易日历标签）

---

## 0. 本轮要解决的问题

第 4/5/6 轮对 `MonitorView.vue` 的复核**都只做了文本/AST 层面的存在性检查**
（`check_monitor_view.mjs` 是「关键字是否在去掉注释的代码里」）。那种检查能证明
「代码里写了这个字段」，**不能证明**：

- 后端返回的 JSON 经 `refresh()` → 计算属性 → 模板绑定这条链，最终真的产生预期文案；
- 覆盖率口径的四种取值（全市场 / 池内 / 取数失败 / 字段缺失）**互不串味**；
- `signal_status` 到中文标签的映射在真实数据下正确。

也就是说：**此前的结论是「写了」，不是「跑通了」。** 本轮补上执行级证据。

## 1. 方法：DOM-free 逻辑 harness

浏览器端 E2E 需要完整 `vite build` + 真实后端，属高资源且需授权。改为在 Node 里
**真实执行组件的 `setup()` 与编译后的模板**，只替换外部依赖：

```
MonitorView.vue
  ├─ @vue/compiler-sfc parse + compileScript   → setup 代码（真实）
  ├─ @vue/compiler-sfc compileTemplate(ssr)    → SSR 渲染函数（真实）
  ├─ typescript transpileModule                → 擦除类型语法（纯语法，无语义改动）
  ├─ 模块解析钩子（loader）替换：@/api/watch、@/utils/security、element-plus、*.vue
  └─ 真实挂载 → onMounted → refresh() → 断言计算属性与渲染结果
```

设计要点（都是踩过的坑，记录以免重复）：

| 问题 | 原因 | 处理 |
|------|------|------|
| `compileScript` 保留 `import type` / `type X = ...` | `isProd:false` 不做类型擦除 | 交给 `typescript.transpileModule`（`module: ESNext`）纯语法擦除 |
| `compileTemplate({ssr:true, mode:'function'})` 产出裸 `return` | function 模式需 `new Function` 包裹 | 改用 `mode:'module'`，得到 `export function ssrRender` |
| 模板引用 `hostHeights` 时 undefined | 该绑定由 `onMounted` 里的 ResizeObserver 回填，SSR 不执行 | 断言用的状态对象上补默认值（仅布局辅助，与被测逻辑无关） |
| 所有断言拿到空值 | 无活动组件实例时 `onMounted` 只警告**不注册**，`refresh()` 从不执行 | 编译产物里把 `vue` 的 `onMounted` 改写为本地包装，回调交 harness 手动执行 |
| 场景 B/C/D 复用场景 A 的 fixture | ESM 按 URL 缓存 stub | 每个场景把 `?bust=N` 写进 stub 说明符，loader 剥查询串后查表并带回 |
| 脚本挂住 / `window is not defined` | 组件 5s 轮询定时器 + 定时回调访问 DOM | 提供最小 `document`/`window`/`ResizeObserver` 桩，定时器 `unref()` |

`vue` 与 `@vue/server-renderer` 在生成物目录里解析不到（生成物在系统临时目录），
由 loader 统一改写为绝对路径。

> 边界：本 harness 覆盖 `MonitorView.vue`。`T1Candidate.vue` 依赖较多
> （`@/api` 等），未纳入本轮；其 P0-3 标签目前仍只有文本级证据（见 §4）。

## 2. 断言结果（17/17 通过，退出码 0）

`RESULT: ALL LOGIC ASSERTIONS PASSED（逻辑装配已验证，非界面目视确认）`

### 场景 A：正常全市场信号

| 断言 | 实测 |
|------|------|
| SIGNAL(buy) 解析为 1 行建议 | `len=1` |
| `signal_status=OBSERVE` → 观察中 | 观察中 |
| 持有期口径 | `INTRADAY_EOD` |
| 三条失效条件均渲染 | 下一根 bar 收盘；行情过期或缺失；当日收盘 |
| 全市场覆盖 → 百分比 | `99.83%` |
| 覆盖提示口径 | `口径=全市场` |

### 场景 B/C/D：覆盖率口径的四种取值互不串味

| 场景 | 输入 | 期望 | 实测 |
|------|------|------|------|
| B | `UNIVERSE_NOT_FULL_MARKET` | 不适用（池内模式） | 不适用（池内模式） ✅ |
| B | 同上，提示行 | 给出池内真实覆盖率 | 本栏不适用；本轮实际覆盖（口径=候选池）97.00% ✅ |
| C | `FULL_UNIVERSE_UNAVAILABLE` | 不可用（全市场池构建失败） | 不可用（全市场池构建失败） ✅ |
| C | 同上 | **不得**伪装成池内模式 | 独立文案，未串味 ✅ |
| D | 指标缺覆盖率字段 | 不适用（口径未标注） | 不适用（口径未标注） ✅ |

**这一组是本轮最有价值的证据**：「池内模式」「取数失败」「字段缺失」三种情况
此前只在源码里看到分支，现在确认运行时文案确实区分开——即
**失败不会被静默显示成一种正常口径**。

### 场景 E/F：状态映射与缺省

| 断言 | 实测 |
|------|------|
| `WARMUP` → 预热不足 | 预热不足 ✅ |
| `INVALIDATED` → 已失效 | 已失效 ✅ |
| `OBSERVE` → 观察中 | 观察中 ✅ |
| `''`（空）→ 观察中 | 观察中 ✅（缺省不谎报为已失效） |
| F: WARMUP 且无失效条件 | 预热不足（未达所需 bar 数）→ 不可执行 ✅ |
| F: 持有期口径 | `UNVALIDATED` ✅（未验证就如实说未验证） |

## 3. 本轮判定

| 项 | 判定 |
|----|------|
| D-2（信号可解释性）前端装配 | **执行级通过**：状态标签、持有期口径、失效条件均正确落地 |
| D-4（覆盖率口径）前端装配 | **执行级通过**：四种口径互不串味，失败不被伪装成正常 |
| 是否构成准确性改善证据 | **否**（未改生产代码，只补验证） |

## 4. 仍然不能证明的事

1. **不是界面目视确认**：本 harness 验证的是数据→文案的正确性，**没有**在浏览器里
   看过真实排版、折叠、滚动、颜色。视觉层仍需（需授权的）`vite build` + 人工确认。
2. `T1Candidate.vue` 的 P0-3 标签（该日非交易日 / 交易日历未覆盖该日）仍只有
   文本级证据。
3. 本 harness 依赖 `modules/web/node_modules` 的 `vue`/`@vue/compiler-sfc`/
   `typescript`；仓库升级这些依赖后需重跑。
4. 它验证的是**前端**把后端字段显示对了，**不能**证明后端字段本身可信——
   后端可信度证据见第 7 轮（覆盖率闸、PIT、幸存者缺口）。

## 5. 复现命令

```powershell
cd E:\Project\quant-trading-system
$env:HARNESS_WEB_ROOT = "E:\Project\quant-trading-system\modules\web"
node --import "file:///E:/Project/quant-trading-system/research/decision_support_iteration/scripts/register-harness.mjs" `
  research\decision_support_iteration\scripts\render_monitor_logic.mjs
# 期望：RESULT: ALL LOGIC ASSERTIONS PASSED，退出码 0
```

生成物写入系统临时目录 `%TEMP%\dsh-vue-harness`，**不写入仓库**。
