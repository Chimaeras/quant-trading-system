# API 文档

基础路径：`/api`（默认 `http://localhost:8080/api`）

统一响应格式：

```json
{ "code": 200, "message": "success", "data": { } }
```

---

## 1. 认证（JWT，可选）

> 默认 `quant.auth.enabled=false`，账户/下单接口可直接访问（默认 userId=1）。  
> 生产环境请设置 `quant.auth.enabled=true` 并修改 `jwt-secret`。

### 登录
`POST /auth/login`

```json
{ "username": "admin", "password": "admin123" }
```

### 注册
`POST /auth/register`

```json
{ "username": "demo", "password": "123456" }
```

响应 `data` 含 `token`、`expiresIn`、`userId`。

受保护接口需 Header：`Authorization: Bearer {token}`

---

## 2. 模拟账户

### 账户概览
`GET /account/info`

### 持仓列表
`GET /position/list`

### 下单
`POST /order/place`

```json
{
  "symbol": "000001",
  "exchangeType": "SZSE",
  "action": "buy",
  "volume": 100,
  "price": 12.5
}
```

`price` 省略时使用库内最新收盘价。

---

## 3. 行情

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/market/stock/list` | 股票列表 |
| GET | `/market/stock/detail` | 股票详情 |
| GET | `/market/kline` | K 线 |
| GET | `/market/realtime` | 实时行情 |

---

## 4. 数据管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/data/overview` | 数据概览 |
| POST | `/data/update/run` | 创建更新任务 |
| GET | `/data/update/tasks` | 任务列表 |

---

## 5. 选股打分

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/scoring/unified/tasks/run` | 异步统一打分 |
| GET | `/scoring/unified/tasks/{taskId}` | 任务进度 |
| GET | `/scoring/unified/result/latest` | 最新结果 |
| POST | `/scoring/trend/run` | 趋势打分 |
| POST | `/scoring/comprehensive/run` | 综合三维度 |
| POST | `/selector/run` | 经典选股 |

策略枚举：`TREND` / `COMPREHENSIVE` / `MULTI_DIM` / `CLASSIC_SELECTOR` / `OVERSOLD_REBOUND`

---

## 6. 回测

### 打分结果回测
`POST /backtest/scoring`

基于历史选股 TopN 持有期收益统计。

### 经典策略回测（单股）
`POST /backtest/strategy/run`

```json
{
  "strategyType": "MA_CROSS",
  "stockCode": "000001",
  "exchangeType": "SZSE",
  "startDate": "2024-01-01",
  "endDate": "2025-01-01",
  "initialCapital": 100000,
  "shortPeriod": 5,
  "longPeriod": 20
}
```

策略类型：`MA_CROSS` / `BOLL` / `MACD`

`GET /backtest/strategy/strategies` — 策略列表

---

## 7. 单股分析

`POST /analysis/stock` — 技术分析建议

---

## 配置项

| 配置 | 说明 | 默认 |
|------|------|------|
| `quant.auth.enabled` | 是否启用 JWT | false |
| `quant.auth.default-admin-username` | 默认管理员 | admin |
| `quant.auth.default-sim-cash` | 模拟账户初始资金 | 1000000 |
| `skip.frontend.build` | Maven 跳过前端构建 | false |

---

## 默认账户

首次启动自动创建：`admin` / `admin123`，并分配 100 万模拟资金。
