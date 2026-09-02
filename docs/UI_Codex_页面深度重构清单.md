# Web 页面深度重构清单（Codex Dark + 逐页问题挖掘）

> 执行文档：在 Phase 1-6 已完成的全局 Codex 换皮基础上，对 `modules/web/src/views/**` 逐页做**深度重构**
> 约束（不可突破）：不改 `src/api/**` 契约、不改 `src/router/index.ts` path/name/守卫、不改页面 script 中的业务算法/轮询/reqSeq/下单行为、不改 ECharts series/option 装配逻辑、不引入亮色主题。
> 每页验收：加载/空/错/刷四态反馈完整、布局与全局 Codex 一致、冗余 scoped 样式收敛、无硬编码色值、可访问性达标、`npm run build` 通过。

## 一、页面清单（23 个页面 + 1 布局）

| # | 路由 path | 视图文件 | 当前行数 | 复杂度 | 状态 |
|---|-----------|----------|---------|--------|------|
| 1 | /login | views/auth/Login.vue | 150 | 低 | 已完成 Codex 壳层 |
| 2 | /data/overview | views/data/DataManagement.vue | 753 | 高 | 已完成 |
| 3 | /data/quality | views/data/DataQuality.vue | 810 | 高 | 已完成 |
| 4 | /data/stocks | views/data/StockBrowser.vue | 86 | 低 | 已完成 |
| 5 | /report/daily | views/report/DailyReport.vue | 516 | 中 | 已完成 |
| 6 | /compare/strategies | views/compare/StrategyCompare.vue | 156 | 低 | 已完成 |
| 7 | /watchlist | views/watchlist/WatchlistView.vue | 443 | 中 | 已完成 |
| 8 | /market/stock | views/market/StockDetailView.vue | 532 | 高 | 已完成 |
| 9 | /market/kline | views/market/KLineView.vue | 172 | 低 | 已完成 |
| 10 | /market/stock-picker | views/market/StockPicker.vue | 1554 | 高 | 已完成 |
| 11 | /market/t1-candidate | views/market/T1Candidate.vue | 349 | 中 | 已完成 |
| 12 | /screener | views/screener/ScreenerView.vue | 244 | 中 | 已完成 |
| 13 | /factor/research | views/factor/FactorResearch.vue | 594 | 高 | 已完成 |
| 14 | /research/probability | views/research/ProbabilityResearch.vue | 942 | 高 | 已完成 |
| 15 | /research/tasks | views/research/ResearchTasks.vue | 1012 | 高 | 已完成 |
| 16 | /research/rolling-walk-forward | views/research/RollingWalkForward.vue | 360 | 中 | 已完成 |
| 17 | /backtest/scoring | views/backtest/ScoringBacktest.vue | 1504 | 高 | 待重构 |
| 18 | /backtest/strategy | views/backtest/StrategyBacktest.vue | 241 | 中 | 已完成 |
| 19 | /backtest/portfolio | views/backtest/PortfolioBacktest.vue | 639 | 高 | 已完成 |
| 20 | /backtest/walk-forward | views/backtest/WalkForward.vue | 190 | 低 | 已完成 |
| 21 | /account/overview | views/account/AccountView.vue | 479 | 中 | 已完成 |
| 22 | /reconcile/position | views/reconcile/ReconcileView.vue | 173 | 低 | 已完成 |
| 23 | /watch/monitor | views/watch/MonitorView.vue | 1151 | 极高 | 待重构(优先保行为) |

## 二、通用问题（跨页共性，逐页落实）

1. **无 PageShell/PageHeader 统一壳**：页面根容器 padding/宽度不统一，未用 `page-shell` 居中到 1520px。
2. **卡片外壳不统一**：手写 `el-card` + 零散 div，未用 `SectionCard`/`PageHeader`/`EmptyState`。
3. **四态反馈不全**：加载只有普通 `v-loading`，无骨架屏；空数据多是 "No Data"，无语义 EmptyState；错误提示散落。
4. **scoped CSS 冗余**：大量 `.page-wrap/.glass/.stat-card` 等与全局 tokens 重复的定义未清理。
5. **硬编码色值/圆角**：混杂 `8px/4px`、旧色值。
6. **可访问性**：`<button>` 缺 `type`、`el-tooltip` 裸 span、缺 aria-label、文本对比度。
7. **性能**：图表实例未在 `onBeforeUnmount` dispose（K 线已做）、未用 `requestAnimationFrame` 节流 resize。
8. **响应式**：grid/窄屏 padding 不统一。

## 三、逐页问题明细与重构方案

> 每完成一页，更新状态为 `已重构`，并记录该页具体改动。

---
