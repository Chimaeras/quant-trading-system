# 个人量化交易系统

> 定位：个人开发者友好 · 快速上手 · 易扩展 · 美观界面

基于 **Java(后端服务) + Python(量化计算) + Vue3(前端)** 架构的个人量化交易系统。

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│  前端: Vue3 + Element Plus + ECharts + TypeScript           │
├─────────────────────────────────────────────────────────────┤
│  API网关: Spring Boot 3.x + JWT认证                         │
├─────────────────────────────────────────────────────────────┤
│  业务服务: 用户/账户/订单/持仓/行情/数据管理/选股打分        │
│  量化引擎: 策略回测/信号生成/趋势打分/多因子选股              │
├─────────────────────────────────────────────────────────────┤
│  存储: MySQL 8.0 + Redis 7.x                                │
└─────────────────────────────────────────────────────────────┘
```

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 前端 | Vue3 + Element Plus + ECharts + TypeScript | - |
| 后端 | Spring Boot | 3.x |
| 量化 | Python + pandas/numpy | 3.9+ |
| 数据库 | MySQL | 8.0 |
| 缓存 | Redis | 7.x |

## 快速开始

### 环境要求

- Docker 20.10+ (部署)
- Node.js 18+ (本地开发)
- Python 3.9+ (本地开发)
- JDK 17+ (本地开发)

### 一键启动 (Docker)

```bash
# 克隆项目
git clone https://github.com/yourname/quant-trading-system.git
cd quant-trading-system

# 启动所有服务
cd docker
docker-compose up -d

# 查看服务状态
docker-compose ps
```

### 访问地址

| 服务 | 地址 |
|------|------|
| **前端+API（统一入口）** | **http://localhost:8080** |

### 本地开发

```bash
# 启动后端（会自动构建前端并托管，前后端同端口）
cd modules/backend-java && mvn spring-boot:run

# 访问 http://localhost:8080 即可使用全部功能
```

> 无需单独运行 `npm run dev`。Spring Boot 启动时会构建前端并服务静态资源，所有页面统一在 8080 端口。

### 前端修改后未生效？

1. 重新构建后端：
   ```bash
   cd modules/backend-java && mvn clean compile
   ```

2. 浏览器强制刷新：`Ctrl + Shift + R`（Windows）或 `Cmd + Shift + R`（Mac）

## 项目结构

```
quant-trading-system/
├── modules/
│   ├── web/              # 前端 Web（Vue3 + TypeScript）
│   ├── backend-java/     # Java 后端（Spring Boot）
│   ├── compute-python/   # Python 量化计算模块
│   └── database/         # 数据库脚本
├── docker/               # Docker 配置
├── docs/                 # 文档
└── README.md
```

## 核心功能

### 行情中心
- 股票列表浏览、搜索筛选
- 股票详情信息展示
- K线图查看与技术指标叠加
- 实时行情监控（自选股盯盘）

### 数据管理
- 数据概览统计（股票数量、日期范围、完整度）
- 历史行情更新任务管理（单只/清单/全量/主板枚举）
- 任务执行状态追踪与重试

### 策略回测
- 内置策略选择（均线交叉/布林带/MACD）
- 回测参数配置（标的、日期范围、初始资金）
- 回测结果展示（收益率、夏普比率、最大回撤等）
- 回测历史记录查看

### 选股打分
- **趋势打分榜**：基于技术指标的综合评分（趋势/乖离/量能/支撑/MACD/RSI）
- **经典多因子选股**：趋势+动量+量价得分-风险惩罚
- **超跌反弹选股**：趋势确认+成交量验证+超跌程度
- **政策/新闻选股**：多因子打分+政策新闻偏置-地缘风险惩罚
- 选股模板管理与结果导出

### 账户交易
- 账户资产概览（总资产、可用资金、持仓市值、今日盈亏）
- 持仓列表与收益展示
- 下单交易

## 内置策略

- 均线交叉策略 (MA_Cross)
- 布林带策略 (BOLL)
- MACD 策略

## API 概览

| 模块 | 接口路径 | 功能说明 |
|------|----------|----------|
| 认证 | `POST /api/auth/login` | 用户登录 |
| 账户 | `GET /api/account/info` | 账户信息 |
| 持仓 | `GET /api/position/list` | 持仓列表 |
| 订单 | `POST /api/order/place` | 下单交易 |
| 行情 | `GET /api/market/stock/list` | 股票列表 |
| 行情 | `GET /api/market/stock/detail` | 股票详情 |
| 行情 | `GET /api/market/realtime` | 实时行情 |
| 行情 | `GET /api/market/kline` | K线数据 |
| 数据 | `GET /api/data/overview` | 数据概览 |
| 数据 | `POST /api/data/update/run` | 创建更新任务 |
| 回测 | `POST /api/backtest/run` | 运行回测 |
| 回测 | `GET /api/backtest/results` | 回测历史 |
| 分析 | `POST /api/analysis/stock` | 单股技术分析 |
| 打分 | `POST /api/scoring/trend/run` | 趋势打分计算 |
| 打分 | `GET /api/scoring/trend/scores` | 打分结果查询 |
| 打分 | `POST /api/scoring/multi-dim/run` | 多维度打分 |
| 选股 | `POST /api/selector/run` | 经典选股 |
| 选股 | `POST /api/selector/oversold-rebound/run` | 超跌反弹选股 |
| 选股 | `POST /api/selector/geopolicy-news/run` | 政策新闻选股 |
| 选股 | `GET /api/selector/result` | 选股结果查询 |
| 选股 | `GET /api/selector/templates` | 选股模板管理 |

## 文档

- [安装部署指南](docs/安装指南.md)
- [策略开发指南](docs/策略开发指南.md)
- [API 文档](docs/API文档.md)
- [开发规范](docs/开发规范.md)
- [多维度打分口径说明](docs/多维度打分口径说明.md)
- [综合三维度打分系统说明](docs/综合三维度打分系统说明.md)

## 许可证

MIT License