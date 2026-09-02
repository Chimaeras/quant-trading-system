# quant-trading-system UI Codex 风格 + 布局重构计划

> **文档用途**：交给 Agent 按阶段执行的 UI-only 改造说明书  
> **目标风格参考**：[170-carry/codex-tools](https://github.com/170-carry/codex-tools)（暗色主题 + 顶栏胶囊分段导航）  
> **前端根目录**：`modules/web`  
> **创建日期**：2026-09-02  
> **约束**：只改 UI（布局 / CSS / 展示组件），**禁止影响原有业务功能**

---

## 0. 决策摘要（不可偏离）

| 决策项 | 结论 |
|--------|------|
| 风格来源 | Codex Tools 桌面工具风（SaaS 控件感、胶囊导航、卡片内嵌高光） |
| 主题 | **仅暗色**；不做亮色主题；不做 `data-theme="light"` |
| 导航形态 | 参照 Codex Tools：**顶栏三列**（品牌 \| 一级胶囊分段 \| 右侧操作）+ **二级子导航**（当前分组下的页面 Tab） |
| UI 库 | **保留** Vue 3 + Element Plus + ECharts；**不换** React / Tailwind / shadcn |
| 业务边界 | API、路由 path/name、守卫、Pinia 业务 store、表单校验、请求参数、图表数据 series **一律不改** |

---

## 1. 改造目标

把 `modules/web` 从「深色玻璃终端风 + 水平 22 Tab 平铺」重构为：

1. **Codex Tools 暗色视觉**：蓝主色 `#60a5fa` / `#2563eb`、卡片 16px 圆角、内嵌高光、细边框、轻阴影  
2. **Codex Tools 布局壳**：`.shell` 全屏渐变 + 可选 ambient 网格 → `.panel` → 顶栏 → 内容区 `.viewStage`  
3. **两级导航**：一级 5 组胶囊切换；二级展示当前组内页面（仍走现有 `router.push`）  
4. **功能零回归**：登录、数据、行情、回测、账户、盯盘等行为与改造前一致  

---

## 2. 现状基线（Agent 改造前必读）

### 2.1 技术栈

| 项 | 值 |
|----|-----|
| 路径 | `modules/web` |
| 框架 | Vue 3.4 + TypeScript + Vite 6 |
| UI | Element Plus 2.5（按需自动导入） |
| 图表 | ECharts 5.4 + `src/utils/echartsTheme.ts` |
| 状态 | Pinia（业务 store 少，多数逻辑在页面内 `ref`） |
| 样式 | `src/style.css` → `tokens.css` / `base.css` / `utilities.css` / `element-bridge.css` |

### 2.2 关键路径

| 类别 | 路径 |
|------|------|
| 入口 | `src/main.ts`、`index.html` |
| 路由 | `src/router/index.ts` |
| 布局 | `src/layouts/MainLayout.vue` |
| 页面 | `src/views/**`（约 22 业务页 + Login） |
| 共享组件 | `src/components/`（极少） |
| 样式 | `src/styles/` |
| API | `src/api/`（**禁止改契约**） |

### 2.3 当前导航分组（数据源保留，仅改展示结构）

现有 `MainLayout.vue` 中 `navGroups` 大致为：

| 一级分组 | 页面 |
|----------|------|
| 数据 | 数据管理、数据质量、股票浏览器 |
| 日报 | 选股日报、策略对比、自选股池 |
| 行情 | 股票详情、K 线图、选股打分、T+1 候选、条件选股 |
| 回测 | 打分/策略/组合回测、Walk-Forward、因子研究、概率研究、滚动 WF、研究任务 |
| 账户 | 模拟账户、持仓对账、盘中盯盘、登录/退出相关入口 |

### 2.4 必须保留的业务语义

- A 股涨跌色：`--up` 红 / `--down` 绿（与操作 `--success`/`--danger` 分离）  
- 系统健康：`getSystemReadiness()` → PASS / DEGRADED / BLOCKED（`StatusBadge` 语义）  
- 盯盘页：`page-main-watch` / `route.name === 'WatchMonitor'` 禁用外层滚动  
- 路由 path / name / `beforeEach` 鉴权  
- IndexedDB 降级逻辑、图表 option 数据结构  

---

## 3. 硬性禁止清单

Agent **禁止**做以下事项：

```
❌ 修改 src/api/** 的请求 URL、参数、返回类型约定
❌ 修改 src/router/index.ts 的 path、name、meta、beforeEach 业务逻辑
❌ 为了换皮而改写页面 script 中的业务计算 / 校验 / 轮询 / reqSeq
❌ 引入亮色主题或系统跟随主题
❌ 替换 Element Plus / 换成 React / 引入 Tailwind（除非后续另开专项）
❌ 修改 Java 后端、Python 模块、数据库
❌ 删除或改名仍在使用的 CSS 变量（旧变量必须保留别名兼容）
❌ 改变 MonitorView 自动刷新、下单、盯盘行为
❌ 改动 ECharts series/data 装配逻辑（仅允许主题色与 wrapper）
```

**允许**：

```
✅ template 结构调整（在不改变事件绑定与条件渲染语义的前提下）
✅ class 名、scoped CSS、全局 CSS、element-bridge 覆写
✅ 新增纯展示组件（props + emit，不含 API 调用）
✅ MainLayout 导航 DOM 重构（仍调用原 handleNav / router.push）
✅ echartsTheme.ts 配色更新
✅ 从巨型页拆出纯 UI 子组件（逻辑用 props 传入，不改算法）
```

---

## 4. 目标视觉规格（Codex Dark）

### 4.1 核心色板（写入 tokens / themes/dark）

对齐 codex-tools `src/styles/themes/dark.css` 思路，映射到本项目：

| Token | 建议值 | 用途 |
|-------|--------|------|
| `--body-bg` / `--bg-0` | `#0b1120` | 应用底色 |
| `--bg-1` / `--card` | `#111827` | 卡片/面板 |
| `--bg-2` | `#1e293b` | 抬升面 |
| `--ink` / `--text-primary` | `#e5edf7` | 主文字 |
| `--subtle` / `--text-secondary` | `#94a3b8` | 次要文字 |
| `--muted` | `#64748b` | 弱化文字 |
| `--line` | `rgba(226,232,240,0.12)` | 边框 |
| `--brand` | `#60a5fa` | 品牌蓝 |
| `--brand-strong` | `#93c5fd` | 强调 |
| `--button-primary-bg` | `linear-gradient(180deg, #60a5fa, #2563eb)` | 主按钮 |
| `--ok` | `#4ade80` | 成功（操作） |
| `--warning` | `#fbbf24` | 警告 |
| `--danger` | `#fb7185` | 危险（操作） |
| `--up` | 保持现有红涨 | **业务强制** |
| `--down` | 保持现有绿跌 | **业务强制** |

### 4.2 布局与控件规格

| 元素 | 规格 |
|------|------|
| 顶栏 | 三列 Grid：`brand \| nav \| actions`；最小高度约 72–104px |
| 一级导航 | 胶囊容器 `border-radius: 999px`，内按钮 `999px`，激活态蓝字 + 实底抬升 |
| 二级导航 | 当前分组下页面 Tab（可略矮于一级）；激活态高亮 |
| 卡片圆角 | 16px（section）/ 12px（控件）/ 18px（对话框） |
| 阴影 | 外阴影轻 + `inset 0 1px rgba(255,255,255,0.06)` 内嵌高光 |
| 字体 | 系统栈：Segoe UI / SF Pro；字重 600–800 |
| 页面内容宽 | `min(1520px, 100%)` 居中 |
| 背景 | 纵向渐变 shell；可选 42px ambient 网格（低透明度，勿喧宾夺主） |
| 动效 | 入场 `riseIn`/`fadeIn` 160–220ms；克制使用 |

### 4.3 布局 DOM 目标结构

```
.shell
 └── .panel
      ├── .topbar（AppTopBar）
      │     ├── .brandLine（QUANT TERMINAL）
      │     ├── .topSegmentedNav（一级：数据/日报/行情/回测/账户）
      │     └── .topActions（健康 Chip、刷新、可选图标按钮）
      ├── .subNav（二级：当前一级组内的页面按钮）
      └── .viewStage
            └── <router-view />  （页面内部用 PageShell 包一层）
```

参考实现思路（源项目）：

- Codex：`src/styles/base.css`（`.shell` / `.topbar` / `.topSegmentedNav`）  
- Codex：`src/components/AppTopBar.tsx`  
- 本项目现有：`src/layouts/MainLayout.vue`（逻辑保留，DOM 重排）

---

## 5. 目标文件结构

```
modules/web/src/
├── styles/
│   ├── tokens.css                 # 扩展 Codex 暗色语义；保留旧变量别名
│   ├── themes/
│   │   └── dark.css               # NEW：Codex 暗色完整令牌（唯一主题）
│   ├── base.css                   # 改：shell / ambient / 全局 reset
│   ├── layout.css                 # NEW：topbar / segmented / subNav / viewStage / PageShell
│   ├── controls.css               # NEW：按钮/搜索框/对话框等自研 class（可选）
│   ├── utilities.css              # 改：stat-grid / glass 对齐新令牌
│   └── element-bridge.css         # 重写：EP 组件外观对齐 Codex Dark
├── layouts/
│   └── MainLayout.vue             # 重构 DOM；保留 readiness / handleNav / navGroups 数据
├── components/
│   ├── ui/                        # NEW：纯展示组件
│   │   ├── AppTopBar.vue
│   │   ├── SegmentedNav.vue
│   │   ├── SubNav.vue
│   │   ├── PageShell.vue
│   │   ├── PageHeader.vue
│   │   ├── SectionCard.vue
│   │   ├── StatCard.vue
│   │   ├── StatGrid.vue
│   │   └── EmptyState.vue
│   ├── StatusBadge.vue            # 保留语义，仅换皮
│   ├── ToastContainer.vue         # 可选换皮
│   └── WatchAccountPanel.vue      # 仅外壳换皮
├── utils/
│   └── echartsTheme.ts            # 仅配色对齐暗色蓝系
└── style.css                      # 更新 @import 顺序
```

`style.css` 建议导入顺序：

```css
@import "@/styles/tokens.css";
@import "@/styles/themes/dark.css";
@import "@/styles/base.css";
@import "@/styles/layout.css";
@import "@/styles/controls.css";
@import "@/styles/utilities.css";
@import "@/styles/element-bridge.css";
```

---

## 6. 分阶段执行计划（Agent 按序执行）

### Phase 0 — 基线与分支（0.5–1 天）

**目标**：可回滚、可验收。

| # | 任务 | 验收 |
|---|------|------|
| 0.1 | 从最新主分支拉 `feat/ui-codex-dark-reskin` | 分支存在 |
| 0.2 | 记录 5 页截图基线：Login、DataManagement、KLineView、StockPicker、MonitorView | 截图目录自备或注释在 PR |
| 0.3 | 确认 `npm run build` 当前可通过 | build OK |

**产出**：干净分支；本文档作为执行依据。

---

### Phase 1 — 设计系统（暗色令牌 + EP 桥接）（2–4 天）

**目标**：全局外观接近 Codex Dark，**尽量零页面 template 改动**。

| # | 任务 | 文件 | 规则 |
|---|------|------|------|
| 1.1 | 新增 `themes/dark.css`，写入 Codex 暗色令牌 | `src/styles/themes/dark.css` | 只 dark |
| 1.2 | 扩展 `tokens.css`：增加 `--ink/--brand/--line` 等别名，**旧变量继续有效** | `tokens.css` | 兼容别名 |
| 1.3 | 更新 `base.css`：body 背景、滚动条、`.shell` 基础 | `base.css` | |
| 1.4 | 新增 `layout.css` 骨架类（可先空壳） | `layout.css` | |
| 1.5 | 重写 `element-bridge.css`：card/button/table/input/tag/tabs/dialog 对齐 Codex Dark | `element-bridge.css` | **只改外观，不改 EP 行为** |
| 1.6 | 更新 `utilities.css` 中 `.glass` / `.stat-card` 等 | `utilities.css` | |
| 1.7 | `style.css` 接入新 import | `style.css` | |

**验收**：

- [ ] `npm run build` 通过  
- [ ] 任意已有页面打开：EP 按钮/卡片/表格已是 Codex 暗色观感  
- [ ] 涨跌色仍正确（红涨绿跌）  
- [ ] **无**亮色切换入口  

**禁止**：此阶段不要大改 `MainLayout` 导航结构（留给 Phase 2）。

---

### Phase 2 — 布局与导航重构（3–5 天）

**目标**：导航形态对齐 Codex Tools；路由与业务逻辑不变。

| # | 任务 | 说明 |
|---|------|------|
| 2.1 | 新增 `SegmentedNav.vue` | props：`items`、`activeKey`；emit：`select` |
| 2.2 | 新增 `SubNav.vue` | 二级页面 Tab |
| 2.3 | 新增 `AppTopBar.vue` | 组装 brand + SegmentedNav + actions 插槽 |
| 2.4 | 重构 `MainLayout.vue` DOM | 数据仍用现有 `navGroups`；抽出 `primaryGroups` + `currentSecondaryItems` |
| 2.5 | 一级切换逻辑 | 切换一级时：若当前路由不在该组，跳转到该组第一个 `path`；若已在该组则只更新二级高亮 |
| 2.6 | 保留健康状态 | `loadReadiness` / `healthClass` / tooltip **逻辑原样**；仅换 class |
| 2.7 | 保留盯盘布局 | `page-main-watch` 条件不变 |
| 2.8 | 登录/退出入口 | 仍通过原 `handleNav` / `authApi` |

**导航交互规格**：

1. 顶栏一级胶囊：`数据 | 日报 | 行情 | 回测 | 账户`  
2. 顶栏下方二级：仅渲染**当前一级**下的页面按钮（含可见性 `visible()`）  
3. 激活态：`route.path === item.path`  
4. 不在任何组的异常路由：回落「数据」组或默认首页  

**验收**：

- [ ] 全部 22 个业务路由可点达  
- [ ] 退出登录仍可用  
- [ ] 健康 Chip 刷新仍可用  
- [ ] Monitor 页无外层滚动行为与改造前一致  
- [ ] `script` 中 API 调用未改  

---

### Phase 3 — 页面壳与共享 UI 组件（3–5 天）

**目标**：统一页面容器，减少重复 markup；**组件不含 API**。

| # | 组件 | 职责 |
|---|------|------|
| 3.1 | `PageShell.vue` | 宽度居中、统一 padding、可选标题槽 |
| 3.2 | `PageHeader.vue` | 标题 + 右侧 actions 槽 |
| 3.3 | `SectionCard.vue` | 替代零散卡片外壳（可包 `el-card` 或纯 div） |
| 3.4 | `StatCard.vue` / `StatGrid.vue` | KPI 数字卡 |
| 3.5 | `EmptyState.vue` | 空数据态 |
| 3.6 | `StatusBadge.vue` | 仅样式对齐，状态映射表不动 |

**试点页（必须先做完再批量）**：

1. `Login.vue`  
2. `DataManagement.vue`  
3. `KLineView.vue`  

**验收**：三页视觉统一；功能操作与改造前一致。

---

### Phase 4 — ECharts 暗色主题同步（1–2 天）

| # | 任务 | 约束 |
|---|------|------|
| 4.1 | 更新 `echartsTheme.ts` 为 Codex 暗色蓝系 | **不改** series/data 生成逻辑 |
| 4.2 | （可选）新增 `QuantChart.vue` wrapper：init/dispose/resize | 页面只换挂载方式，option 函数保持 |
| 4.3 | 迁移 5 个图表页 | KLineView、StockDetailView、ScoringBacktest、StrategyBacktest、PortfolioBacktest |

**验收**：图表可渲染、resize 正常、涨跌色正确、数据来源逻辑不变。

---

### Phase 5 — 页面分批换皮（10–18 天）

**规则**：每个 PR / 提交尽量 **template + style only**；禁止顺手改业务 script。

#### Batch A — 低复杂度

- `Login.vue`  
- `StockBrowser.vue`  
- `WalkForward.vue`  
- `DailyReport.vue`  
- `StrategyCompare.vue`  
- `WatchlistView.vue`  
- `ReconcileView.vue`  

#### Batch B — 中复杂度

- `DataManagement.vue`（若 Phase 3 已试点则补齐）  
- `DataQuality.vue`  
- `KLineView.vue` / `StockDetailView.vue`  
- `AccountView.vue` + `WatchAccountPanel.vue`  
- `ScreenerView.vue`  
- `T1Candidate.vue`  

#### Batch C — 高复杂度（最后）

- `MonitorView.vue`（优先保布局与轮询）  
- `StockPicker.vue`  
- `ScoringBacktest.vue`  
- `StrategyBacktest.vue`  
- `PortfolioBacktest.vue`  
- `FactorResearch.vue`  
- `ProbabilityResearch.vue`  
- `ResearchTasks.vue`  
- `RollingWalkForward.vue`  

**单页 checklist**：

- [ ] 使用 `PageShell` / `SectionCard` / `PageHeader`  
- [ ] scoped 样式删除与全局重复的部分  
- [ ] `script` 无业务逻辑改动（或仅有为适配 class 的最小绑定）  
- [ ] 手动验证：加载、提交、空态、错误态、刷新  

---

### Phase 6 — 打磨（2–3 天）

| # | 任务 |
|---|------|
| 6.1 | 窄屏：一级导航可横向滚动或压缩字号，避免撑破 |
| 6.2 | Toast / Dialog / Overlay 对齐 Codex feedback 观感 |
| 6.3 | 统一滚动条、focus ring |
| 6.4 | 清理无用旧 class；保留兼容变量注释 |

---

### Phase 7 — 回归验收（2–3 天）

| 模块 | 必测项 |
|------|--------|
| 认证 | 登录、登出、401 跳转 |
| 导航 | 5 个一级 × 各组二级全覆盖 |
| 数据 | 数据管理刷新、质量页、股票浏览器 |
| 行情 | K 线、详情、选股、T+1、条件选股 |
| 回测 | 三类回测提交与结果展示 |
| 研究 | 因子/概率/任务/滚动 WF 可打开可操作 |
| 账户 | 模拟账户、对账 |
| 盯盘 | Monitor 自动刷新、布局、下单相关入口 |
| 构建 | `npm run build` 无错误 |
| 主题 | **仅暗色**；无亮色闪烁 |

---

## 7. Agent 执行协议

### 7.1 每次开工

1. 阅读本文档对应 Phase  
2. 只改本 Phase 列出的文件范围  
3. 改完执行：`cd modules/web && npm run build`  
4. 对触及页面做最小手工冒烟  
5. 在 PR / 汇报中写明：**改了哪些文件、未改哪些业务逻辑、验收结果**

### 7.2 Diff 纪律

- 优先小步提交：`Phase1` → `Phase2` → …  
- 巨型页换皮：可先拆 UI 子组件，再换 class；**同一提交不要混业务修复**  
- 若发现功能 bug：另开任务，**不要**塞进 UI PR  

### 7.3 回滚策略

- 样式问题：优先回滚 `element-bridge.css` / `themes/dark.css`  
- 导航问题：回滚 `MainLayout.vue`，保留 Phase 1 令牌亦可  

---

## 8. 验收标准（Definition of Done）

全部满足才算完成：

1. **视觉**：整体接近 Codex Tools 暗色桌面工具风（顶栏胶囊导航、卡片、主色蓝）  
2. **导航**：一级胶囊 + 二级页面 Tab，22 路由均可到达  
3. **主题**：仅暗色，无亮色主题代码路径  
4. **功能**：核心业务路径回归通过（见 Phase 7）  
5. **构建**：`modules/web` 下 `npm run build` 成功  
6. **边界**：`src/api/**` 与路由 path/name 无契约变更  

---

## 9. 工期参考（仅暗色 + 布局）

| 阶段 | 建议工期 |
|------|----------|
| Phase 0 | 0.5–1 天 |
| Phase 1 | 2–4 天 |
| Phase 2 | 3–5 天 |
| Phase 3 | 3–5 天 |
| Phase 4 | 1–2 天 |
| Phase 5 | 10–18 天 |
| Phase 6 | 2–3 天 |
| Phase 7 | 2–3 天 |
| **合计** | **约 5–7 周**（单 Agent 全职） |

若只做到 Phase 2（全局皮 + 新导航）即可先上线预览，再继续 Phase 5 逐页精修。

---

## 10. 建议 Agent 启动提示词（可直接复制）

```text
请按 docs/UI_Codex风格布局重构计划.md 执行 quant-trading-system 的 UI 重构。

范围：仅 modules/web 的布局与样式；对齐 Codex Tools 暗色风格 + 顶栏胶囊一级导航 + 二级子导航。
禁止：亮色主题；改 API/路由契约；改业务 script 逻辑。
请从 Phase 0 → Phase 1 开始，每完成一个 Phase 运行 npm run build 并汇报改动文件与验收结果。
不要一次做完全部 Phase。
```

---

## 11. 参考链接

- Codex Tools 仓库：https://github.com/170-carry/codex-tools  
- 主题参考文件：`src/styles/themes/dark.css`（上游仓库）  
- 布局参考文件：`src/styles/base.css`、`src/components/AppTopBar.tsx`（上游仓库）  
- 本项目前端：`modules/web`  
- 本项目现有令牌：`modules/web/src/styles/tokens.css`  
- 本项目现有布局：`modules/web/src/layouts/MainLayout.vue`  

---

## 12. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-09-02 | 初版：确认仅暗色 + Codex 导航形态；UI-only；供 Agent 分阶段执行 |
