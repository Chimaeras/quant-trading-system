# 📈 个人量化交易系统

> 定位：个人开发者友好 · 快速上手 · 易扩展 · 美观界面

基于 **Python(量化核心) + Java(后端服务) + Vue3(前端) + Docker(部署)** 架构的个人量化交易系统。

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│  前端: Vue3 + Element Plus + ECharts (行情/策略/回测/实盘)   │
├─────────────────────────────────────────────────────────────┤
│  API网关: Spring Boot + JWT认证 + 限流                       │
├─────────────────────────────────────────────────────────────┤
│  后端: 用户/账户/订单/持仓/通知服务                          │
│  量化: 行情采集/策略引擎/回测引擎/信号生成/风控              │
├─────────────────────────────────────────────────────────────┤
│  存储: MySQL + Redis + InfluxDB + MinIO                     │
└─────────────────────────────────────────────────────────────┘
```

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 前端 | Vue3 + Element Plus + ECharts | - |
| 后端 | Spring Boot | 3.x |
| 量化 | Python + pandas/numpy | 3.9+ |
| 数据库 | MySQL | 8.0 |
| 缓存 | Redis | 7.x |
| 时序 | InfluxDB | 2.7 |

## 快速开始

### 环境要求

- Docker 20.10+
- Docker Compose 2.0+
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
| 账户/数据/行情等所有页面 | http://localhost:8080/account |
| InfluxDB | http://localhost:8086 |

### 本地开发（推荐：单端口 8080）

```bash
# 启动后端（会自动构建前端并托管，前后端同端口）
cd modules/backend-java && mvn spring-boot:run

# 访问 http://localhost:8080 即可使用全部功能
# 账户页: http://localhost:8080/account
```

> 无需单独运行 `npm run dev`。Spring Boot 启动时会构建前端并服务静态资源，所有页面统一在 8080 端口。

### 前端修改后未生效？强制刷新方法

修改前端代码后若未看到效果，常见原因与解决：

1. **仅执行了 npm run build**：后端从 `target/classes/static` 提供静态文件，该目录由 Maven 从 `frontend/dist` 复制。需重新构建后端：
   ```bash
   cd modules/backend-java && mvn clean compile   # 或 mvn spring-boot:run
   ```

2. **Vite / 浏览器缓存**：可先清缓存再构建：
   ```bash
   cd modules/web && npm run build:clean   # 清空 dist 与 Vite 缓存后构建
   cd ../backend-java && mvn compile
   ```

3. **浏览器强制刷新**：`Ctrl + Shift + R`（Windows）或 `Cmd + Shift + R`（Mac），或 DevTools 里勾选 “Disable cache” 后刷新。

4. **本地开发时**：建议用 `cd frontend && npm run dev` 启动前端（热更新），后端代理到 8080。

## 项目结构

```
quant-trading-system/
├── modules/
│   ├── web/            # 前端 Web（Vue3）
│   ├── backend-java/   # Java 后端（Spring Boot）
│   ├── compute-python/ # Python 计算/量化模块
│   └── database/       # 数据库基础脚本（SQL/导入脚本）
├── docker/            # Docker 配置
├── docs/              # 文档
└── README.md
```

## 核心功能

- 📊 **行情看板** - 实时行情、资产概览、持仓分布
- ⚙️ **策略管理** - 策略列表、启动/停止、参数配置
- 📈 **回测分析** - 历史回测、收益曲线、夏普/回撤
- 🖥️ **实盘监控** - 订单状态、持仓跟踪
- 💰 **账户资产** - 资金流水、资产报表

## 内置策略

- 均线交叉策略 (MA_Cross)
- 布林带策略 (BOLL)
- MACD 策略

## 文档

- [安装部署指南](docs/安装指南.md)
- [策略开发指南](docs/策略开发指南.md)
- [API 文档](docs/API文档.md)
- [开发规范](docs/开发规范.md)

## 许可证

MIT License
