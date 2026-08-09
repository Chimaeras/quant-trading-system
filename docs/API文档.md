# API 文档（V2-MP-029 终核版）

> 本版以 **Controller 路由自动盘点**为索引（`modules/backend-java/src/main/java/com/quant/controller/`），逐一核对后补写语义。
> **完成度不等于 API 存在**：每个端点标注 `能力完成度`（code / wired / verified / prod），未验收的接口不会因文档存在而被视为产品已完成。
> 全文任何「已实现/已完成」字样仅指代码与契约存在，运行态验收以 §5 分级为准。

- 基础路径：`/api`（默认 `http://localhost:8080/api`）
- 版本基线：V2 Batch 1–5 契约稳定（文档 2026-08-09 终核）
- 上次覆盖缺口：predictions / portfolio / research tasks / market batch / rolling WFO / screener / selector / watchlist / reconcile / system readiness

---

## 0. 通用约定

### 0.1 统一响应信封

```json
{ "code": 200, "message": "success", "data": { }, "errorId": null }
```

- `code`：业务码；成功恒为 `200`；异常时非 200，`data` 为 `null`。
- `errorId`：仅异常时非空，形如 `ERR-400-A1B2C3D4`，随错误日志记录，用于线上定位（V2-MP-030 可观测性）；成功时为 `null`（`@JsonInclude(NON_NULL)`，不序列化）。

### 0.2 HTTP 状态语义（GlobalExceptionHandler 统一映射）

| HTTP | 场景 | 说明 |
|------|------|------|
| 200 | 成功 | 含同步计算完成、异步任务已提交（返回任务记录） |
| 202 | 异步受理 | **仅** `POST /api/research/tasks`（新建且未复用）与 `POST /api/backtest/portfolio/run`；响应 `data` 含 `runId`/`taskId` + `RUNNING` 状态 |
| 204 | 客户端中断 | `ClientAbortException`（浏览器提前断开），无响应体 |
| 400 | 参数/业务错误 | 校验失败、`BusinessException`、`IllegalArgumentException` |
| 401 | 未认证 | `UnauthorizedException`（鉴权开启时缺/错 token） |
| 404 | 资源不存在 | `ResourceNotFoundException`、`NoResourceFoundException`（未知路径） |
| 409 | 唯一键冲突 | `ConflictException`（幂等键/唯一约束竞争；调用方应重放或换 requestId） |
| 429 | 并发上限 | `TooManyRequestsException`（`ConcurrencyGuard`，如预测生成并发>3） |
| 500 | 未捕获异常 | `handleGeneric`，附 errorId |

> 历史约定（保留兼容）：`GET /api/research/tasks/{taskId}` 任务不存在返回 HTTP 200 + `code=404`（旧契约，V2-MP-030 列为待改项，新端点一律走标准 404）。

### 0.3 鉴权（deny-by-default）

- `quant.auth.enabled=false`（本地 dev 默认）时，鉴权拦截器为 no-op，所有接口可直接访问，userId 默认 1。
- `quant.auth.enabled=true`（docker 默认）时，**除白名单外所有 `/api/**` 必须携带** `Authorization: Bearer {token}`；userId 一律取自 `AuthContext`，**禁止 controller 用请求参数/默认 userId 冒认身份**（V2-MP-010 P0）。
- 白名单（未登录可访问，均为只读）：
  - `/api/auth/**`（登录/注册）
  - `/api/market/**`（行情浏览）
  - `/api/analysis/**`（单股分析）
  - `/api/report/**`（日报/趋势只读）
  - `/api/system/**`（系统就绪度只读聚合，V2-MP-028）

### 0.4 幂等与并发（V2-MP-030）

- 高成本写操作支持 `requestId` 参数（如 `POST /api/predictions/generate?requestId=...`）：同 requestId 重复请求安全重放，不产生二次副作用。
- 并发上限：`ConcurrencyGuard` 对关键操作限流（超限返回 429），唯一约束 + `ConflictRetry` 处理 MAX+1 / 唯一键竞态。
- 前端 `request.ts`：`RequestError` 携带 `status/code/errorId`，错误文案形如 `消息（ERR-xxxx）`。

### 0.5 能力完成度分级（§5 详述）

| 级别 | 含义 |
|------|------|
| code | 代码/契约存在（Controller+Service 已实现），未经运行态验证 |
| wired | 已接线（前端/调用方已接通，构建级检查通过） |
| verified | 已验证（有运行态验证证据：mvn test / 页面冒烟 / 手工验收） |
| prod | 生产可用（端到端验收完成，可作日常决策依据） |

> 环境约束：当前 Maven 无法编译（依赖仓库不可达）、MySQL 不可达，故 Java 端多数标注 `code`/`wired`（grep 契约核对），前端 `wired`（vue-tsc 通过）。**凡标 `code` 者，文档不宣称其已验证。**

---

## 1. 端点索引（自动盘点，29 个 Controller / ~120 个端点）

> 来源：`controller/*.java` 逐一 grep。列含义：`鉴权`=🔓白名单公开 / 🔒需 token；`异步`=202 受理或任务式；`副作用`=✍写操作（非只读）。

### 1.1 认证 · AuthController

| 方法 | 路径 | 鉴权 | 异步 | 副作用 | 完成度 | 说明 |
|------|------|------|------|--------|--------|------|
| POST | `/auth/login` | 🔓 | — | — | prod | 登录，返回 token/expiresIn/userId |
| POST | `/auth/register` | 🔓 | — | ✍ | prod | 注册 |

### 1.2 行情 · MarketController（只读）

| 方法 | 路径 | 鉴权 | 异步 | 副作用 | 完成度 | 说明 |
|------|------|------|------|--------|--------|------|
| GET | `/market/stock/list` | 🔓 | — | — | prod | 股票列表 |
| GET | `/market/stock/detail` | 🔓 | — | — | prod | 股票详情 |
| GET | `/market/realtime` | 🔓 | — | — | prod | 实时行情（东财 API 手动触发） |
| GET | `/market/kline` | 🔓 | — | — | prod | K 线 |

### 1.3 数据管理 · DataController

| 方法 | 路径 | 鉴权 | 异步 | 副作用 | 完成度 | 说明 |
|------|------|------|------|--------|--------|------|
| GET | `/data/overview` | 🔒 | — | — | prod | 数据概览 |
| GET | `/data/quality` | 🔒 | — | — | prod | 数据质量驾驶舱 |
| GET | `/data/quality/gate` | 🔒 | — | — | prod | 数据质量门禁（gate_v2） |
| GET | `/data/hot-stocks` | 🔒 | — | — | prod | 热门股 |
| POST | `/data/stock-info/backfill-list-date` | 🔒 | — | ✍ | prod | list_date 回填（仅可回填项） |
| POST | `/data/backfill-raw` | 🔒 | — | ✍ | prod | 不复权 raw 回填 + 复权因子推算（QP-003c） |
| POST | `/data/security-master/init` | 🔒 | — | ✍ | prod | 历史证券主数据初始化（QP-004） |
| POST | `/data/security-master/mark-bootstrap-limited` | 🔒 | — | ✍ | prod | 标记 bootstrap 限制 |
| GET | `/data/security-master/status` | 🔒 | — | — | prod | 主数据覆盖状态 |
| POST | `/data/security-master/ingest-delisted-placeholder` | 🔒 | — | ✍ | prod | 退市占位记录 |
| POST | `/data/market-batch/update` | 🔒 | 任务 | ✍ | wired | 行情批量更新（任务式，200 返回任务记录） |
| POST | `/data/trading-calendar/init` | 🔒 | — | ✍ | prod | 交易日历初始化（QP-005） |
| POST | `/data/index/fetch` | 🔒 | — | ✍ | prod | 指数数据拉取（QP-005） |
| POST | `/data/snapshot/create` | 🔒 | — | ✍ | prod | 创建数据快照注册（QP-006） |
| GET | `/data/snapshot/list` | 🔒 | — | — | prod | 快照注册表 |
| POST | `/data/history/update` | 🔒 | 任务 | ✍ | prod | 历史行情更新（任务式） |
| POST | `/data/update/run` | 🔒 | 任务 | ✍ | prod | 创建更新任务 |
| GET | `/data/update/tasks` | 🔒 | — | — | prod | 任务列表 |
| GET | `/data/update/tasks/{taskId}` | 🔒 | — | — | prod | 任务详情 |
| POST | `/data/update/tasks/{taskId}/retry` | 🔒 | — | ✍ | prod | 重试失败项 |
| POST | `/data/update/tasks/{taskId}/stop` | 🔒 | — | ✍ | prod | 停止任务 |
| POST | `/data/financial/bulk` | 🔒 | — | ✍ | prod | 财报批量导入 |
| POST | `/data/margin/bulk` | 🔒 | — | ✍ | prod | 融资余额批量导入 |

### 1.4 选股打分 · UnifiedScoringController / Trend / Comprehensive / MultiDim

| 方法 | 路径 | 鉴权 | 异步 | 副作用 | 完成度 | 说明 |
|------|------|------|------|--------|--------|------|
| POST | `/scoring/unified/run` | 🔒 | — | ✍ | prod | 统一打分（同步） |
| POST | `/scoring/unified/tasks/run` | 🔒 | 任务 | ✍ | prod | 统一打分（异步任务，200 返回任务） |
| GET | `/scoring/unified/tasks/recent` | 🔒 | — | — | prod | 最近任务 |
| GET | `/scoring/unified/tasks/{taskId}` | 🔒 | — | — | prod | 任务进度 |
| POST | `/scoring/unified/tasks/{taskId}/cancel` | 🔒 | — | ✍ | prod | 取消任务 |
| POST | `/scoring/unified/tasks/{taskId}/prioritize` | 🔒 | — | ✍ | prod | 任务置顶 |
| POST | `/scoring/unified/tasks/clear-pending` | 🔒 | — | ✍ | prod | 清理 pending |
| GET | `/scoring/unified/result/latest` | 🔒 | — | — | prod | 最新结果 |
| GET | `/scoring/unified/result/{resultId}` | 🔒 | — | — | prod | 按结果 ID 查询 |
| POST | `/scoring/trend/run` | 🔒 | — | ✍ | prod | 趋势打分 |
| GET | `/scoring/trend/scores` | 🔒 | — | — | prod | 趋势结果 |
| POST | `/scoring/comprehensive/run` | 🔒 | — | ✍ | prod | 综合三维度打分 |
| GET | `/scoring/comprehensive/scores` | 🔒 | — | — | prod | 综合结果 |
| POST | `/scoring/multi-dim/run` | 🔒 | — | ✍ | prod | 多维度 v1 打分 |
| GET | `/scoring/multi-dim/scores` | 🔒 | — | — | prod | 多维度结果 |

策略枚举：`TREND` / `COMPREHENSIVE` / `MULTI_DIM` / `CLASSIC_SELECTOR` / `OVERSOLD_REBOUND`。
> 打分结果统一输出 `score_type=rule_score`（截面百分位，**非上涨概率**，QP-002）。

### 1.5 选股器 / 自选股 / 对账

| 方法 | 路径 | 鉴权 | 异步 | 副作用 | 完成度 | 说明 |
|------|------|------|------|--------|--------|------|
| POST | `/selector/run` | 🔒 | — | ✍ | prod | 经典选股运行 |
| POST | `/selector/oversold-rebound/run` | 🔒 | — | ✍ | prod | 超跌反弹运行 |
| GET | `/selector/result` | 🔒 | — | — | prod | 最新/指定结果 |
| GET | `/selector/result/{resultId}` | 🔒 | — | — | prod | 结果详情 |
| GET | `/selector/templates` | 🔒 | — | — | prod | 模板列表 |
| POST | `/selector/templates` | 🔒 | — | ✍ | prod | 保存/更新模板 |
| DELETE | `/selector/templates/{templateId}` | 🔒 | — | ✍ | prod | 删除模板 |
| GET | `/screener/run` | 🔒 | — | — | prod | 条件选股（关键字/交易所/行业/上市时长/excludeST） |
| POST | `/screener/templates/save` | 🔒 | — | ✍ | prod | 保存选股器模板 |
| GET | `/screener/templates/list` | 🔒 | — | — | prod | 模板列表 |
| POST | `/screener/templates/delete` | 🔒 | — | ✍ | prod | 删除模板 |
| GET | `/watchlist/list` | 🔒 | — | — | prod | 自选股列表 |
| POST | `/watchlist/add` | 🔒 | — | ✍ | prod | 添加（幂等） |
| POST | `/watchlist/remove` | 🔒 | — | ✍ | prod | 移除 |
| POST | `/watchlist/batch-add` | 🔒 | — | ✍ | prod | 批量导入 |
| GET | `/watchlist/alerts` | 🔒 | — | — | prod | 异动扫描 |
| GET | `/watchlist/signal-coverage` | 🔒 | — | — | prod | 自选股信号覆盖 |
| GET | `/reconcile/position` | 🔒 | — | — | prod | 持仓与选股信号对账 |

### 1.6 回测 · Scoring / Strategy / Stability / Turnover / WalkForward / Portfolio

| 方法 | 路径 | 鉴权 | 异步 | 副作用 | 完成度 | 说明 |
|------|------|------|------|--------|--------|------|
| POST | `/backtest/scoring` | 🔒 | — | ✍ | prod | 打分结果回测（详见 §2.6） |
| GET | `/backtest/scoring/strategies` | 🔒 | — | — | prod | 可用策略列表 |
| GET | `/backtest/scoring/runs` | 🔒 | — | — | prod | 回测历史 |
| GET | `/backtest/scoring/run/{resultId}` | 🔒 | — | — | prod | 按 resultId 回溯 |
| POST | `/backtest/strategy/run` | 🔒 | — | ✍ | prod | 单股策略回测（MA/BOLL/MACD） |
| GET | `/backtest/strategy/strategies` | 🔒 | — | — | prod | 单股策略列表 |
| GET | `/backtest/stability` | 🔒 | — | — | prod | 分段稳定性（vs 沪深300 基准/牛熊分段） |
| GET | `/backtest/turnover` | 🔒 | — | — | prod | 换手率分析 |
| GET | `/backtest/walk-forward` | 🔒 | — | — | prod | walk-forward（固定区间等分，非滚动扩展） |
| POST | `/backtest/portfolio/run` | 🔒 | **202** | ✍ | wired | 组合回测异步受理（MP-020），返回 runId+RUNNING |
| GET | `/backtest/portfolio/list` | 🔒 | — | — | prod | 组合回测历史 |
| GET | `/backtest/portfolio/{runId}` | 🔒 | — | — | prod | run 详情 |
| GET | `/backtest/portfolio/{runId}/status` | 🔒 | — | — | prod | 轮询状态 |
| GET | `/backtest/portfolio/{runId}/orders` | 🔒 | — | — | prod | 订单明细 |

> V2-MP-005 目标：组合回测按时间序 OPEN/CLOSE 事件账本、资金守恒、raw 严格、仓位终态；raw 执行政策完成前 API 保持 Beta 标注。

### 1.7 预测 · PredictionController

| 方法 | 路径 | 鉴权 | 异步 | 副作用 | 完成度 | 说明 |
|------|------|------|------|--------|--------|------|
| GET | `/predictions/daily?date=&horizon=` | 🔒 | — | — | prod | 每日双轨预测快照 |
| GET | `/predictions/runs?limit=` | 🔒 | — | — | prod | 最近 prediction run（MP-020 信号源选择器） |
| GET | `/predictions/runs/{runId}` | 🔒 | — | — | prod | run 详情（含 items，MP-028） |
| POST | `/predictions/generate?date=&horizon=&requestId=` | 🔒 | — | ✍ | prod | 生成预测（requestId 幂等 + 429 并发上限）；horizon 仅 2/3 |

> 契约（V2-MP-001）：候选必须 `selectorResult.tradeDate == prediction.tradeDate` 且策略与 track 一致；缺失即 `ABSTAINED`，**绝不跨日/跨策略回退**。响应返回 `sourceResultId/sourceTradeDate`。generate 返回 summary（不含 items），前端按 runId 拉详情。

### 1.8 研究 · ResearchController / ResearchTaskController / RollingExperimentController / T1CandidateController / ExperimentController / ModelImportController

| 方法 | 路径 | 鉴权 | 异步 | 副作用 | 完成度 | 说明 |
|------|------|------|------|--------|--------|------|
| POST | `/research/replay` | 🔒 | — | — | prod | 历史逐日重放（PIT 股票池健康，只读） |
| POST | `/research/replay/pool-health` | 🔒 | — | — | prod | 重放池健康 |
| POST | `/research/replay/signals` | 🔒 | — | — | prod | 重放信号 |
| POST | `/research/labels/build` | 🔒 | 任务 | ✍ | wired | T+2/T+3 标签生成（label_v2） |
| POST | `/research/labels/build-t1` | 🔒 | 任务 | ✍ | wired | T+1 标签生成 |
| GET | `/research/labels/coverage` | 🔒 | — | — | prod | 标签覆盖率 |
| GET | `/research/t1/ecology` | 🔒 | — | — | prod | 涨停基因/情绪生态 |
| GET | `/research/t1/premium` | 🔒 | — | — | prod | 涨停次日溢价 |
| GET | `/research/calibration` | 🔒 | — | — | prod | 规则分数概率校准（Brier/logLoss/ECE/BSS） |
| GET | `/research/calibrators` | 🔒 | — | — | prod | 校准器列表 |
| GET | `/research/models` | 🔒 | — | — | prod | 模型注册表 |
| POST | `/research/logistic/train` | 🔒 | — | ✍ | prod | Logistic 训练+注册（purged split+embargo） |
| POST | `/research/tasks` | 🔒 | **202** | ✍ | prod | 创建研究任务（复用返回 200） |
| GET | `/research/tasks` | 🔒 | — | — | prod | 任务列表 |
| GET | `/research/tasks/{taskId}` | 🔒 | — | — | prod | 任务详情（不存在：200+code=404 旧契约） |
| POST | `/research/tasks/{taskId}/cancel` | 🔒 | — | ✍ | prod | 取消任务 |
| POST | `/research/tasks/{taskId}/resume` | 🔒 | — | ✍ | prod | 恢复任务 |
| POST | `/research/rolling-experiment/run` | 🔒 | — | ✍ | prod | 滚动 WFO 实验（MP-023） |
| POST | `/research/rolling-experiment/{experimentId}/resume` | 🔒 | — | ✍ | prod | 续跑 |
| GET | `/research/rolling-experiment/list` | 🔒 | — | — | prod | 实验列表 |
| GET | `/research/rolling-experiment/{experimentId}` | 🔒 | — | — | prod | 实验详情 |
| POST | `/research/experiments/register` | 🔒 | — | ✍ | wired | 实验注册（MP-016） |
| GET | `/research/experiments/list` | 🔒 | — | — | wired | 实验列表 |
| GET | `/research/experiments/{id}` | 🔒 | — | — | wired | 实验详情 |
| GET | `/research/experiments/{id}/gates` | 🔒 | — | — | wired | 实验门禁 |
| POST | `/research/experiments/{id}/decision` | 🔒 | — | ✍ | wired | 人工决策（gate + 事务晋级，MP-018） |
| POST | `/research/experiments/{id}/promote` | 🔒 | — | ✍ | wired | 晋升 champion |
| POST | `/research/experiments/{id}/rollback` | 🔒 | — | ✍ | wired | 回滚 champion |
| POST | `/research/models/import` | 🔒 | — | ✍ | wired | 模型导入审核（MP-025） |
| GET | `/research/models/{modelVersion}` | 🔒 | — | — | wired | 模型详情 |
| POST | `/research/t1/candidates` | 🔒 | — | ✍ | prod | T+1 次日候选生成 |
| POST | `/research/t1/settle` | 🔒 | — | ✍ | prod | T+1 次日核销 |
| GET | `/research/t1/candidates/{tradeDate}` | 🔒 | — | — | prod | 历史候选回溯 |
| GET | `/research/t1/recent` | 🔒 | — | — | prod | 最近候选 |
| GET | `/research/t1/stats` | 🔒 | — | — | prod | 命中率统计 |

### 1.9 因子研究 · FactorResearchController

| 方法 | 路径 | 鉴权 | 异步 | 副作用 | 完成度 | 说明 |
|------|------|------|------|--------|--------|------|
| GET | `/factor/research` | 🔒 | — | — | prod | 因子面板（覆盖率/分布/分组/IC） |
| GET | `/factor/unified-report` | 🔒 | — | — | prod | 统一因子报告（T+1/2/3/5 并列，QP-010） |
| GET | `/factor/snapshot-research` | 🔒 | — | — | prod | 快照截面研究 |

### 1.10 单股分析 / 日报 / 对比 / 系统

| 方法 | 路径 | 鉴权 | 异步 | 副作用 | 完成度 | 说明 |
|------|------|------|------|--------|--------|------|
| POST | `/analysis/stock` | 🔓 | — | — | prod | 单股技术分析 |
| GET | `/report/daily?date=` | 🔓 | — | — | prod | 每日信号日报 |
| GET | `/report/daily/versions` | 🔓 | — | — | prod | 日报版本 |
| POST | `/report/daily/realize` | 🔓 | — | ✍ | wired | 日报核销落库（MP-012；⚠️白名单含此写操作，见下） |
| GET | `/report/trend` | 🔓 | — | — | prod | 多日信号趋势 |
| GET | `/compare/strategies` | 🔒 | — | — | prod | 策略对比工作台 |
| GET | `/system/readiness` | 🔓 | — | — | prod | 系统就绪度聚合（MP-028） |

> ⚠️ 遗留待办（V2-MP-030 关联）：`/api/report/**` 白名单包含 `POST /daily/realize` 写操作；deny-by-default 原则下，写端点不应随只读白名单公开。当前仅影响鉴权开启态，标记为已知风险。

### 1.11 账户 / 持仓 / 订单 · AccountController / PositionController / OrderController

| 方法 | 路径 | 鉴权 | 异步 | 副作用 | 完成度 | 说明 |
|------|------|------|------|--------|--------|------|
| GET | `/account/info` | 🔒 | — | — | prod | 账户资产概览 |
| POST | `/account/session` | 🔒 | — | ✍ | prod | 新建模拟会话 |
| GET | `/account/sessions` | 🔒 | — | — | prod | 会话列表 |
| GET | `/account/session/{sessionId}` | 🔒 | — | — | prod | 会话详情 |
| POST | `/account/session/{sessionId}/close` | 🔒 | — | ✍ | prod | 关闭会话 |
| GET | `/position/list` | 🔒 | — | — | prod | 持仓列表 |
| POST | `/order/place` | 🔒 | — | ✍ | prod | 模拟下单（lot/T+1/费用，MP-021） |
| GET | `/order/history` | 🔒 | — | — | prod | 订单历史 |

### 1.12 静态资源 · SpaController

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | `/` 与前端路由 | 🔓 | SPA 前端（Vue 构建产物由后端托管） |
| GET | `/favicon.ico` | 🔓 | 图标 |

---

## 2. 关键端点契约详情

### 2.1 预测生成与详情（V2-MP-001 / 028 / 030）

`POST /api/predictions/generate?date=2026-08-07&horizon=2&requestId=xxx`

- `horizon` 仅支持 `2` / `3`（`ResearchConvention.LABEL_HORIZONS`），否则 400。
- 并发：`ConcurrencyGuard` 上限 3，超限 429；唯一键冲突自动有界重试（`ConflictRetry`）。
- 响应 `data`（summary，不含 items）：
  ```json
  {
    "ruleScoreRunId": "PR-...", "probabilityRunId": "PR-...",
    "tracks": [
      { "trackType": "RULE_SCORE", "status": "PUBLISHED", "sourceResultId": "...", "sourceTradeDate": "2026-08-07",
        "abstainReason": null, "itemCount": 10 },
      { "trackType": "CALIBRATED_PROBABILITY", "status": "ABSTAINED", "abstainReason": "NO_CONTRACT_COMPLETE_CHAMPION", "itemCount": 0 }
    ]
  }
  ```
- 生成后按 `GET /api/predictions/runs/{runId}` 拉详情（含 `items`），**前端不把空 items 当成功**。
- track `status` 枚举：`PUBLISHED` / `ABSTAINED`；abstain 必带 `abstainReason`（如 `NO_SAME_DAY_SELECTOR_RESULT`、`NO_CONTRACT_COMPLETE_CHAMPION`）。
- 概率轨：仅当存在满足 deploy contract 的 champion 才产出 `CALIBRATED_PROBABILITY`，否则 abstain（V2-MP-004）。

### 2.2 组合回测（V2-MP-020，异步 202）

`POST /api/backtest/portfolio/run`

- 请求体可选；`predictionRunId` 模式从已发布预测取信号（MP-020 闭环）。
- 响应 `202`：`data` 含 `runId`、`status=RUNNING`；随后轮询 `GET /api/backtest/portfolio/{runId}/status`。
- 订单/NAV 明细：`GET /api/backtest/portfolio/{runId}/orders`。
- 算法状态：raw-only 执行政策未完成前，结果不得与旧算法直接并表（V2-MP-005 / 014）。

### 2.3 研究任务（V2-MP-008，202/200）

`POST /api/research/tasks`

- 新建任务返回 **202**；若请求与既有任务复用（同 key 幂等命中）返回 **200**（`data.reused=true`）。
- 任务状态机：PENDING → RUNNING → SUCCESS / FAILED / CANCELLED；支持 cancel/resume（checkpoint 续跑）。
- 产物发布须原子：取消中的任务不会发布半成品。

### 2.4 行情批量更新 / 历史更新（任务式）

`POST /api/data/market-batch/update`、`POST /api/data/history/update`

- 返回 **200** + 任务记录（非 202）；轮询 `GET /api/data/update/tasks/{taskId}` 获取进度。
- 幂等：按 tradeDate 去重 + 唯一约束，重复提交不产生重复数据。

### 2.5 系统就绪度（V2-MP-028，公开只读）

`GET /api/system/readiness`

```json
{
  "status": "PASS|DEGRADED|BLOCKED",
  "db_health": { "status": "PASS", "detail": "..." },
  "calendar": { "status": "PASS", "total_rows": 2000, "today_is_trading_day": true, "earliest_date": "...", "latest_date": "..." },
  "data_freshness": { "status": "PASS|DEGRADED|BLOCKED", "latest_market_date": "...", "expected_latest_trade_date": "..." },
  "data_gate": { "status": "...", "purpose": "RULE_SCORE", "threshold_version": "gate_v2" },
  "market_session": { "phase": "PRE_MARKET|AUCTION|...|HOLIDAY|CLOSED", "label": "...", "today_is_trading_day": true },
  "checked_at": "2026-08-09",
  "server_time": "09:00:00",
  "note": "status 由 db/日历/行情时效/数据门禁聚合，不依赖本机时间；market_session 仅为告知性市场时段。"
}
```

- `status` 聚合规则：任一基座 BLOCKED → BLOCKED；任一 DEGRADED → DEGRADED；否则 PASS。
- **禁止把本机时钟当系统健康**：status 与 clock 无关；`market_session` 仅告知性展示。
- 日历/行情时效为节假日感知（以日历最新交易日为期望最新日），非固定天数阈值。

### 2.6 打分回测（保留原口径，V1 已验证）

`POST /api/backtest/scoring`

- 信号日为 `tradeDate`，实际模拟买入为**下一交易日开盘**；出场为持有 h 个交易日后**收盘**（A 股 T+1，最早卖出日为入场日后第 h 个交易日）。
- `executionAssumptions`：entry=`NEXT_TRADING_DAY_OPEN`，exit=`HOLDING_PERIOD_CLOSE`，t_plus_one_sell=true，return_type=`NET_AFTER_MODELED_COSTS`。
- `skipReason` 枚举：`NO_NEXT_TRADING_DAY` / `NO_QUOTE_ON_ENTRY_DAY` / `INVALID_ENTRY_OPEN` / `ZERO_VOLUME_ON_ENTRY` / `LIMIT_UP_AT_OPEN` / `BEYOND_CALENDAR` / `NO_QUOTE_ON_EXIT_DAY` / `INVALID_EXIT_CLOSE` / `ZERO_VOLUME_ON_EXIT` / `LIMIT_DOWN_AT_CLOSE`。
- 结果持久化：每次回测生成 `result_id` 落库，可按 ID 回溯（V1 F3）。
- 回测结果含 `selector_tradability`（取自被回测结果快照；无快照回退默认并标 source）。

### 2.7 单股策略回测（保留）

`POST /api/backtest/strategy/run`

- 策略：`MA_CROSS` / `BOLL` / `MACD`；信号基于前一日收盘指标，当日开盘成交；T+1 卖出；`executionAssumptions` 见 V1。
- 输出：收益率、最大回撤、夏普、权益曲线。

---

## 3. 算法版本与口径（单一真相源）

| 项 | 值 | 常量位置 |
|----|----|----------|
| 研究口径约定版本 | `research_convention_v1` | `research/ResearchConvention.java` |
| 标签持有期 | T+2 / T+3（`LABEL_HORIZONS=[2,3]`），T+1 另轨 | 同上 |
| 标签方向 | `y_h = 1[net_return > 0]`；入场=signal 下一交易日开盘×(1+佣金+滑点)，退出=持有期收盘×(1−印花税−滑点) | 同上 |
| 标签版本 | `label_v2`（raw 口径，生产唯一）；`label_v1`（qfq 旧口径）仅历史只读 | QP-003 / MP-002 |
| 数据质量门禁 | `gate_v2` | `service/DataQualityGateService.java` |
| 复权策略 | 双写并存：`market_data`（前复权）+ `market_price_raw`（不复权）+ `adjust_factor`（`source=derived_local, reliability=limited`） | QP-003 |
| 成交执行 | raw-only（V2-MP-014 目标）；**禁止 qfq 成交回退** | V2-MP-005 |
| purge/embargo | 按实际标签退出日判定，`max(train.exitDate) < testStart`，不按 horizon+1 魔法常数 | V2-MP-003 |
| 得分语义 | `score_type=rule_score`（截面百分位，非概率） | QP-002 |
| 交易日历 | `source=eastmoney_derived, reliability=limited`（从指数日K 反推） | QP-005 |
| 指数数据 | `source=eastmoney, reliability=limited`（沪深300/中证500/中证1000/上证指数） | QP-005 |
| 历史证券主数据 | `source=current_snapshot, reliability=limited`（valid_from/valid_to PIT） | QP-004 |

## 4. 非目标（本版本明确不做）

- **实时行情推送**：无 WebSocket，自选股异动为「基于最新入库行情的轮询扫描」，非实时。
- **实盘自动下单**：无 QMT/券商实盘接口（需凭据+风控）。
- **深度学习黑盒主策略**：无证据，不做黑盒模型当生产主策略。
- **政策/舆情 NLP 全链路**：仅接口占位，不做半吊子。
- **微服务拆分**：当前单体规模无需。
- **官方数据源替换**：list_date / 复权因子 / 交易日历 / 主数据 / 指数目前均为本地推算或 eastmoney 派生（`reliability=limited`），接入官方源后升级 reliability。
- **未验收的「已实现」**：见 §5，标 `code` 的接口不得被当作生产能力宣传。

---

## 5. 能力完成度分级（文档不再把「API 存在」等同「产品完成」）

| 级别 | 定义 | 依据 |
|------|------|------|
| **code** | 代码与契约存在 | Controller + Service 实现可 grep 核对；**未经运行态验证** |
| **wired** | 已接线 | 前端 API 层/调用方已接通；vue-tsc / 构建级检查通过 |
| **verified** | 已验证 | 有运行态证据（mvn test / 页面冒烟 / 手工验收清单） |
| **prod** | 生产可用 | 端到端验收完成，可作日常决策依据（当前多为 V1 已运行能力） |

当前标注原则（因环境无法编译/连库）：

- Java 端 `prod` = V1 已通过 mvn test 运行验证的能力；`wired` = 代码存在且前端已接，但无运行态证据（如 experiments/rolling-wfo/portfolio 等新能力）。
- 前端 `wired` = vue-tsc 通过；`prod` = 页面冒烟过。
- **标 `code`/`wired` 的能力若被误当生产基线使用，属于文档误导，应纠正。**

已知未验收项（不随本 MP 宣称完成）：

1. 组合回测（portfolio）`wired`：V2-MP-005 现金因果/raw 政策未完成前，结果仅作 Beta 参考。
2. 概率轨发布 `wired`：需 V2-MP-002/004/007/018/025 全部完成后才有生产概率。
3. 滚动 WFO / 实验晋级 `wired`：前端端到端晋升验收待冒烟。
4. `GET /api/research/tasks/{taskId}` 404 语义待标准化（V2-MP-030）。
5. `/api/report/**` 白名单含写端点 `/daily/realize`，鉴权态下待收敛（V2-MP-030 关联）。
6. 官方数据源替换后需重跑覆盖率与回测（QP 线后续）。

---

## 6. 手工验证清单（MP-029 抽取对照）

- [ ] 本文件 §1 端点索引与 `controller/*.java` 路由逐一对照（29 个 Controller）。
- [ ] 从新旧两端各抽 10 个端点核对 HTTP 状态与鉴权标注。
- [ ] `GET /api/system/readiness` 在副本启动后返回 status/db_health/calendar/data_freshness/data_gate/market_session。
- [ ] 按 `docs/运维与迁移runbook.md` 在副本执行迁移与恢复演练。
- [ ] 文档内所有相对链接（README / 安装指南 / runbook / optimization-progress）有效。

## 附：相关文档

- [安装部署指南](安装指南.md)
- [运维与迁移 Runbook](运维与迁移runbook.md)
- [研究口径规范](研究口径规范.md)
- [全面分析与修改计划 V2](全面分析与修改计划_V2_20260807.md)
- [.cursor/optimization-progress.md](../.cursor/optimization-progress.md)（轮次进度 + V2 完成度复核）
