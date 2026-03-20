# API 文档

基础路径: `/api` (如部署在 8080 端口则为 `http://localhost:8080/api`)

## 1. 用户认证

### 1.1 登录

```
POST /auth/login
Content-Type: application/json
```

**请求体**

```json
{
  "username": "admin",
  "password": "123456"
}
```

**响应**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "<JWT_FROM_LOGIN>",
    "expiresIn": 86400
  }
}
```

## 2. 账户接口

### 2.1 获取账户信息

```
GET /account/info
Authorization: Bearer {token}
```

**响应**

```json
{
  "code": 200,
  "data": {
    "totalAsset": 500000,
    "cash": 150000,
    "positionValue": 350000,
    "todayPnl": 12500,
    "positionCount": 15
  }
}
```

## 3. 持仓接口

### 3.1 获取持仓列表

```
GET /position/list
Authorization: Bearer {token}
```

**响应**

```json
{
  "code": 200,
  "data": [
    {
      "symbol": "000001",
      "name": "平安银行",
      "volume": 1000,
      "avgPrice": 12.5,
      "currentPrice": 12.8
    }
  ]
}
```

## 4. 订单接口

### 4.1 下单

```
POST /order/place
Authorization: Bearer {token}
Content-Type: application/json
```

**请求体**

```json
{
  "symbol": "000001",
  "action": "buy",
  "volume": 1000,
  "price": 12.5,
  "type": "limit"
}
```

**响应**

```json
{
  "code": 200,
  "data": {
    "orderId": "ORD20260320001",
    "status": "submitted"
  }
}
```

## 5. 策略接口

### 5.1 获取策略列表

```
GET /strategy/list
Authorization: Bearer {token}
```

**响应**

```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "name": "MA_Cross",
      "status": "running",
      "pnl": 12500
    }
  ]
}
```

## 6. 回测接口

### 6.1 运行回测

```
POST /backtest/run
Authorization: Bearer {token}
Content-Type: application/json
```

**请求体**

```json
{
  "strategyId": 1,
  "symbol": "000001",
  "startDate": "2025-01-01",
  "endDate": "2026-03-20",
  "initialCash": 1000000
}
```

**响应**

```json
{
  "code": 200,
  "data": {
    "totalReturn": "15.3%",
    "sharpeRatio": 1.85,
    "maxDrawdown": "-8.2%",
    "totalTrades": 42,
    "winRate": 0.58
  }
}
```

## 7. 行情接口

### 7.1 获取 K 线数据

```
GET /market/kline?symbol=000001&interval=1d&limit=100
```

**响应**

```json
{
  "code": 200,
  "data": [
    {
      "time": "2026-03-20",
      "open": 12.3,
      "high": 12.8,
      "low": 12.2,
      "close": 12.5,
      "volume": 1000000
    }
  ]
}
```
