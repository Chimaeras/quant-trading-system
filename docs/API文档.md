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

基于历史选股 TopN 的持有期 forward return 统计。信号日为 `tradeDate`，实际模拟买入为**下一交易日开盘**；出场为持有 h 个交易日后**收盘**（遵守 A 股 T+1，最早卖出日为入场日后第 h 个交易日）。

`GET /backtest/scoring/strategies` — 可用策略类型列表

**请求体**

```json
{
  "strategyType": "CLASSIC_SELECTOR",
  "startDate": "2024-01-01",
  "endDate": "2025-01-01",
  "topN": 10,
  "holdingDays": [1, 3, 5, 10, 20],
  "commissionRate": 0.0003,
  "sellTaxRate": 0.0005,
  "slippageRate": 0.0002
}
```

| 字段 | 说明 | 默认 |
|------|------|------|
| `strategyType` | 与 `selector_result.strategy_type` 一致 | — |
| `startDate` / `endDate` | 信号日区间（含） | — |
| `topN` | 每日取排名前 N | 10 |
| `holdingDays` | 持有期列表（交易日） | [1,3,5,10,20] |
| `commissionRate` | 买入佣金率 | 0.0003 |
| `sellTaxRate` | 卖出印花税率 | 0.0005 |
| `slippageRate` | 单边滑点率 | 0.0002 |

**响应 `data` 主要字段**

| 字段 | 说明 |
|------|------|
| `executionAssumptions` | 本次收益计算成交与费用假设（见下表） |
| `holdingPeriodStats` | key=持仓天数，value=该周期汇总（胜率、均值、中位数等） |
| `dailyDetails[].tradeDate` | 信号生成日 |
| `dailyDetails[].topStocks[].entryDate` | 实际模拟买入日（信号日下一交易日） |
| `dailyDetails[].topStocks[].entryPrice` | 含买入侧滑点的模拟成交价；未入场为 null |
| `dailyDetails[].topStocks[].forwardReturns` | key=持仓天数，value=净收益率；无法计算为 null |
| `dailyDetails[].topStocks[].skipReason` | 入场跳过原因；成功入场为 null |
| `dailyDetails[].topStocks[].periodSkipReasons` | key=持仓天数，value=该周期出场跳过原因 |

**`executionAssumptions`（打分回测）**

| 键 | 含义 |
|----|------|
| `entry` | 固定为 `NEXT_TRADING_DAY_OPEN` |
| `exit` | 固定为 `HOLDING_PERIOD_CLOSE` |
| `t_plus_one_sell` | `true`；A 股 T+1，不在入场日收盘卖出 |
| `holding_period_semantics` | 持有 h 个交易日后在收盘出场 |
| `commission_rate` / `sell_tax_rate` / `slippage_rate_each_side` | 本次请求使用的费率 |
| `limit_up_entry_blocked` / `limit_down_exit_blocked` | 涨停开盘不买、跌停收盘不卖 |
| `limit_pct_source` | `BY_BOARD_AND_ST`（主板/创业板/科创板/ST 差异化涨跌幅） |
| `skip_reason_codes` | 全部跳过原因枚举 |
| `return_type` | `NET_AFTER_MODELED_COSTS` |

**`skipReason` / `periodSkipReasons` 枚举**

| 代码 | 含义 |
|------|------|
| `NO_NEXT_TRADING_DAY` | 信号日后无下一交易日 |
| `NO_QUOTE_ON_ENTRY_DAY` | 入场日无行情 |
| `INVALID_ENTRY_OPEN` | 入场日开盘价无效 |
| `ZERO_VOLUME_ON_ENTRY` | 入场日成交量为 0（疑似停牌），无法买入 |
| `LIMIT_UP_AT_OPEN` | 入场日涨停开盘，无法买入 |
| `BEYOND_CALENDAR` | 出场日超出预加载交易日历 |
| `NO_QUOTE_ON_EXIT_DAY` | 出场日无行情 |
| `INVALID_EXIT_CLOSE` | 出场日收盘价无效 |
| `ZERO_VOLUME_ON_EXIT` | 出场日成交量为 0（疑似停牌），无法按收盘价卖出 |
| `LIMIT_DOWN_AT_CLOSE` | 出场日跌停收盘，无法按收盘价卖出 |

---

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

**响应 `data.executionAssumptions`（单股策略回测）**

| 键 | 含义 |
|----|------|
| `signal_timing` | `PREVIOUS_DAY_CLOSE`（信号基于前一日收盘指标） |
| `entry` / `exit` | `SAME_DAY_OPEN`（信号日当日开盘成交） |
| `t_plus_one_sell` | `true`（买入后下一 bar 才可卖） |
| `commission_rate` / `stamp_duty_rate` / `slippage_rate_each_side` | 费用假设 |
| `limit_up_entry_blocked` / `limit_down_exit_blocked` | 涨跌停成交约束 |
| `limit_pct` / `limit_pct_source` | 涨跌幅限制及来源 |
| `return_type` | `NET_AFTER_MODELED_COSTS` |

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
