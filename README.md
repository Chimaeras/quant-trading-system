# 个人量化交易系统

> 定位：个人开发者友好 · A 股数据管理 + 多策略选股打分 · 易扩展

基于 **Java(Spring Boot 后端) + Vue3(前端) + Python(独立脚本)** 架构的个人量化选股系统。

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│  前端: Vue3 + Element Plus + ECharts + TypeScript           │
├─────────────────────────────────────────────────────────────┤
│  后端: Spring Boot 3.x 单体应用（API + 静态前端同端口）      │
├─────────────────────────────────────────────────────────────┤
│  业务: 行情查询 / 数据管理 / 多策略选股打分 / 打分回测       │
├─────────────────────────────────────────────────────────────┤
│  存储: MySQL 8.0                                            │
└─────────────────────────────────────────────────────────────┘
```

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 前端 | Vue3 + Element Plus + ECharts + TypeScript | - |
| 后端 | Spring Boot | 3.2 |
| 数据库 | MySQL | 8.0 |
| 量化脚本 | Python + pandas/numpy | 3.9+（独立，未接入运行时） |

## 快速开始

### 环境要求

- Docker 20.10+（部署）
- Node.js 18+（本地开发）
- JDK 21+（本地开发）

### 一键启动 (Docker)

```bash
cd docker
docker-compose up -d --build
```

访问 **http://localhost:8080**

### 本地开发

```bash
# 启动后端（Maven 会自动 npm build 前端并托管，前后端同端口）
cd modules/backend-java && mvn spring-boot:run

# 访问 http://localhost:8080
```

> 跳过后端启动时构建前端：`-Dquant.skipFrontendBuild=true`

### 前端修改后未生效？

```bash
cd modules/backend-java && mvn clean compile
```

浏览器强制刷新：`Ctrl + Shift + R`

## 项目结构

```
quant-trading-system/
├── modules/
│   ├── web/              # 前端 Web（Vue3 + TypeScript）
│   ├── backend-java/     # Java 后端（Spring Boot）
│   ├── compute-python/   # Python 量化脚本（独立）
│   └── database/         # 数据库脚本
├── docker/               # Docker 配置
├── docs/                 # 文档
└── README.md
```

## 已实现功能

### 数据管理
- 数据概览统计（股票数量、日期范围、完整度）
- 历史行情更新任务（单只/清单/全量/主板枚举）
- 任务执行状态追踪、重试、停止
- 财报快照 / 融资余额批量导入

### 行情中心
- 股票列表浏览、搜索筛选
- 股票详情 + 单股技术分析
- K 线图（ECharts）
- 单股实时行情（东财 API，手动触发）

### 选股打分（多策略，统一工作台）
- **经典多因子**：趋势 + 动量 + 量价 - 风险惩罚（支持短周期 profile）
- **超跌反弹**：趋势确认 + 成交量验证 + 超跌程度
- **趋势打分**：趋势/乖离/量能/支撑/MACD/RSI 六维
- **综合三维度**：技术30 + 基本40 + 情绪30
- **多维度 v1**：纯库内 K 线 + PE + 行业聚合
- **Codex 短周期**：截面中性化 + 稳健 Z 评分（`rule_score` 为截面百分位，非上涨概率）
- 异步任务队列、断点续跑、模板管理、结果导出

### T+1 次日选股闭环（核心工作流）
- **T+1 次日候选**：T 日收盘生成「次日上涨候选 TopN」（默认 Top10）。评分 = 短期动量 + 量能 + 趋势位置三项（`rule_score`，0–50，**非概率**）；**收盘涨停的标的在打分前即被排除**，故涨停/连板不计入候选评分（见下条情绪生态）
- **涨停基因 / 短线情绪生态**：涨停家数、跌停家数、最高连板、晋级率、炸板率、涨停次日溢价——全部从本地 K 线推导（无外部数据），作为**市场情绪快照**展示，不参与候选排序
- **次日核销**：T+1 结束后回填每只候选的次日涨幅/高开/涨停并聚合命中率，历史清单可回溯
- **T+1 概率研究**：T+1 方向标签（`close[t+1]/close[t]`）可生成，校准/Logistic 模型支持 horizon=1，与 T+2/T+3 并行

### 因子研究 / 概率选股研究（QP 线）
- **因子研究面板**：覆盖率 / 截面分布 / 分组 / Rank IC / Pearson IC / 单调性 / 多空 spread
- **统一因子报告**：T+1/T+2/T+3/T+5 并列输出毛收益 IC + 可执行净收益 IC（含 HAC 标准误与 block bootstrap CI），前端「多周期 IC 对比」tab 展示
- **概率研究页**（前端）：规则分数校准（Brier/ECE/BSS + 可靠性图）+ Logistic 训练 + 模型注册表
- **T+2/T+3 标签工厂**：按研究口径（次开入场 + 持有期收盘 + 扣费）生成标签，保留拒单样本
- **Logistic 模型**：L2 正则，版本化训练 + 注册（特征 min/max 标准化仅在训练窗拟合）
- **历史逐日重放**：PIT 股票池健康报告（含可交易性闸过滤）

### 打分回测
- 基于历史选股结果的持有期收益统计（1/3/5/10/20 天）
- **结果持久化**：每次回测生成 `result_id` 落库，可按 ID 回溯历史结果、浏览历史列表（结果随行情更新漂移时以记录口径为准）
- 分段稳定性报告含 **vs 沪深300 基准** 与 **牛/熊市场分段**（按指数当日涨跌划分）

### 经典策略回测
- 单股 MA 交叉 / 布林带 / MACD 策略回测（各策略参数可调）
- 收益率、最大回撤、夏普比率、权益曲线

### 模拟账户（JWT 可选）
- 账户资产概览（含今日盈亏）、持仓列表、模拟下单、**订单历史**
- 默认 admin/admin123，docker 部署默认开启鉴权（本地 dev 默认关闭）

## API 概览（已实现）

> **完整接口以 [docs/API文档.md](docs/API文档.md) 为准**（V2-MP-029 终核：按 Controller 自动盘点，含预测/组合回测/研究任务/行情批量/系统就绪度等 ~120 端点）。下表仅为常用入口的速览，**「API 存在 ≠ 产品已完成」**——新研究类能力（experiments / rolling WFO / portfolio 等）标注为「已接线待验收」，详见 API 文档 §5 完成度分级。

| 模块 | 接口路径 | 功能说明 |
|------|----------|----------|
| 行情 | `GET /api/market/stock/list` | 股票列表 |
| 行情 | `GET /api/market/stock/detail` | 股票详情 |
| 行情 | `GET /api/market/realtime` | 实时行情 |
| 行情 | `GET /api/market/kline` | K 线数据 |
| 数据 | `GET /api/data/overview` | 数据概览 |
| 数据 | `POST /api/data/update/run` | 创建更新任务 |
| 分析 | `POST /api/analysis/stock` | 单股技术分析 |
| 打分 | `POST /api/scoring/unified/tasks/run` | 统一打分（异步） |
| 打分 | `GET /api/scoring/unified/result/latest` | 最新打分结果 |
| 打分 | `POST /api/scoring/trend/run` | 趋势打分 |
| 打分 | `POST /api/scoring/comprehensive/run` | 综合三维度打分 |
| 选股 | `POST /api/selector/run` | 经典选股 |
| 选股 | `GET /api/selector/templates` | 选股模板管理 |
| 回测 | `POST /api/backtest/scoring` | 打分结果回测 |
| 回测 | `POST /api/backtest/strategy/run` | 经典策略回测 |
| 因子 | `GET /api/factor/research` | 因子研究面板 |
| 因子 | `GET /api/factor/unified-report` | 统一因子报告（多持仓期 IC） |
| 研究 | `POST /api/research/replay` | 历史逐日重放（PIT 股票池健康） |
| 研究 | `POST /api/research/labels/build` | 生成 T+2/T+3 标签 |
| 研究 | `GET /api/research/calibration` | 规则分数概率校准 |
| 研究 | `POST /api/research/logistic/train` | Logistic 模型训练 + 注册 |
| 认证 | `POST /api/auth/login` | 登录（JWT；docker 默认开启，本地 dev 默认关闭） |
| 账户 | `GET /api/account/info` | 模拟账户概览 |
| 持仓 | `GET /api/position/list` | 持仓列表 |
| 订单 | `POST /api/order/place` | 模拟下单 |

## 规划中

- 政策/新闻选股
- Redis 缓存 / WebSocket 自选股盯盘

## 文档

- [安装部署指南](docs/安装指南.md)
- [运维与迁移 Runbook](docs/运维与迁移runbook.md)（迁移/备份/恢复/副本验收）
- [策略开发指南](docs/策略开发指南.md)
- [API 文档](docs/API文档.md)（完整端点索引 + HTTP 语义 + 完成度分级）
- [多维度打分口径说明](docs/多维度打分口径说明.md)
- [综合三维度打分系统说明](docs/综合三维度打分系统说明.md)

### 持续优化（Cursor / Codex）

在另一台机器 `git clone` 本仓库后，可直接按下列文档继续迭代（规则均在仓库内）：

| 文档 | 用途 |
|------|------|
| [选股迭代优先方向](docs/选股迭代优先方向.md) | **业务主航道**：选股能力优先 |
| [Cursor 持续优化执行手册](docs/Cursor量化系统持续优化执行手册.md) | Cursor `/loop` 流程 |
| [Codex 持续优化执行协议](docs/Codex量化系统持续优化执行协议.md) | Codex 长期目标 / Automation |
| [.cursor/optimization-progress.md](.cursor/optimization-progress.md) | 轮次进度（共用） |
| [.cursor/rules/quant-optimization-loop.mdc](.cursor/rules/quant-optimization-loop.mdc) | Cursor 规则摘要 |

Cursor 示例：`/loop 30s` + `@docs/Cursor量化系统持续优化执行手册.md`  
Codex 示例：长期目标 + `@docs/Codex量化系统持续优化执行协议.md` + `@docs/选股迭代优先方向.md`


## 许可证

MIT License
