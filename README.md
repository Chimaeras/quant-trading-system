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

### 选股打分（5 种策略，统一工作台）
- **经典多因子**：趋势 + 动量 + 量价 - 风险惩罚
- **超跌反弹**：趋势确认 + 成交量验证 + 超跌程度
- **趋势打分**：趋势/乖离/量能/支撑/MACD/RSI 六维
- **综合三维度**：技术30 + 基本40 + 情绪30
- **多维度 v1**：纯库内 K 线 + PE + 行业聚合
- 异步任务队列、断点续跑、模板管理、结果导出

### 打分回测
- 基于历史选股结果的持有期收益统计（1/3/5/10/20 天）

### 经典策略回测
- 单股 MA 交叉 / 布林带 / MACD 策略回测
- 收益率、最大回撤、夏普比率、权益曲线

### 模拟账户（JWT 可选）
- 账户资产概览、持仓列表、模拟下单
- 默认 admin/admin123，鉴权默认关闭

## API 概览（已实现）

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
| 回测 | `POST /api/backtest/scoring/run` | 打分结果回测 |
| 回测 | `POST /api/backtest/strategy/run` | 经典策略回测 |
| 认证 | `POST /api/auth/login` | 登录（JWT 可选） |
| 账户 | `GET /api/account/info` | 模拟账户概览 |
| 持仓 | `GET /api/position/list` | 持仓列表 |
| 订单 | `POST /api/order/place` | 模拟下单 |

## 规划中

- 政策/新闻选股
- Redis 缓存 / WebSocket 自选股盯盘

## 文档

- [安装部署指南](docs/安装指南.md)
- [策略开发指南](docs/策略开发指南.md)
- [API 文档](docs/API文档.md)
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
