# 实时量化监控交易系统批次 B：OMS 与内部 Paper 实施说明

日期：2026-08-19  
状态：核心功能已实施，外部模拟盘实盘时段联调待执行

## 1. 本地代码审计结论

现有 `BrokerClient` 已严格解析妙想 balance/positions/orders，并明确规定 trade/cancel 不自动重试；
`WatchLoop` 已有 UNKNOWN_SUBMIT 冻结、账户前后查询和活动委托风控。缺口是订单只存在于一次 tick
和 `state.json` 中，没有独立订单生命周期；dry-run 也不会形成跨轮资金和持仓。

项目文档已经冻结妙想订单状态：1 未报、2 已报、3 部成、4 已成、5 部成待撤、6 已报待撤、
7 部撤、8 已撤、9 废单、10 撤单失败。本批次按该映射实现，不臆测新的柜台状态。

## 2. 实施架构

```text
Signal -> Risk/Sizer -> Local OMS (idempotency_key)
                         |-- SHADOW: 只记录意图
                         |-- INTERNAL_PAPER: 冻结 -> NEXT_BAR 撮合 -> Fill -> Account
                         `-- EXTERNAL_PAPER: SUBMITTING -> 妙想 trade -> orders 对账
```

核心原则：本地订单必须先落库再调用外部 trade；网络/解析异常进入 UNKNOWN，禁止自动重试；
同一 session、策略、bar、代码、方向、数量和价格只能生成一个幂等订单。

## 3. 状态机

```text
CREATED -> SUBMITTING -> SUBMITTED -> PARTIAL -> FILLED
                   |          |          |
                   |          `-> CANCEL_PENDING -> CANCELLED
                   |-> REJECTED
                   `-> UNKNOWN -> 柜台唯一匹配后继续推进
```

终态不得倒退；成交数量只能增长；订单、成交、内部资金和持仓在同一 SQLite 事务更新。

## 4. 内部 Paper 口径

- 默认 `NEXT_BAR`：当前 bar 收盘产生的信号，最早下一根已收盘 bar 成交，避免未来函数。
- 限价触达后才可能成交；单根成交不超过 `bar.volume × participation`，按 100 股取整。
- 支持 PARTIAL，多根 bar 累计直至 FILLED 或超时撤单。
- 买单冻结限价金额和佣金；卖单冻结可卖数量。
- 买入当日计入 `today_buy_qty`，当日 `available_qty` 不增加；下一交易日才可卖。
- 佣金、最低佣金和印花税沿用项目 `PaperConfig`/TradingFeePolicy 口径。
- 分钟 bar 不具备盘口排队信息，因此结果只表示保守仿真，不宣称复现真实撮合队列。

## 5. 外部 Paper 口径

- `SUBMITTING` 成功落库后才调用妙想 trade。
- trade 的 HTTP/PARSE/无 orderId 结果统一进入 UNKNOWN，不重发。
- 每轮 orders 查询按 broker_order_id 或唯一的代码/方向/数量/价格证据消解 UNKNOWN。
- 妙想 orders 当前没有逐笔成交价与费用字段；聚合成交使用委托价作估算并标记
  `BROKER_ORDER_AGGREGATE_ESTIMATE` 和 `fee_unknown=true`，不得当作真实成交回报。
- TTL 到期先完成本轮 orders 同步，再发一次 cancel；外部撤单成功响应只进入
  `CANCEL_PENDING`，直到后续 orders 返回终态。撤单未知则冻结 live。
- 不实现追单和撤改，避免旧单未撤又创建新单。

## 6. 数据表

SQLite schema v2 新增：

- `local_order`：本地/柜台订单号、幂等键、状态、累计成交、TTL、错误。
- `fill`：成交数量、价格、费用、bar、价格来源和模型参数。
- `account_snapshot`：每轮提交前、撮合后、提交后的账户一致快照。
- `sim_account`：内部现金、冻结资金、已实现损益和交易日。
- `sim_position`：持仓、可卖、今日买入、成本和最新价。

## 7. 运行模式与配置

默认 `PAPER_WATCH_EXECUTION_MODE=SHADOW`。即使提供 `--live --confirm`，SHADOW 也会硬阻断。

内部模拟：

```text
PAPER_WATCH_EXECUTION_MODE=INTERNAL_PAPER
PAPER_WATCH_LIVE=1
PAPER_SESSION_ID=my-strategy-v1
python -m paper.cli watch --live --confirm
```

外部妙想模拟盘：

```text
PAPER_WATCH_EXECUTION_MODE=EXTERNAL_PAPER
PAPER_WATCH_LIVE=1
python -m paper.cli watch --live --confirm
```

其他参数：`PAPER_INTERNAL_INITIAL_CASH`、`PAPER_INTERNAL_FILL_PARTICIPATION`、
`PAPER_INTERNAL_MATCHING_MODEL=NEXT_BAR`、`PAPER_BUY_ORDER_TTL_SEC`、
`PAPER_SELL_ORDER_TTL_SEC`、`PAPER_AUTO_CANCEL_EXPIRED`。

## 8. 上线前仍需完成

1. 在妙想模拟盘交易时段用 100 股完成提交、部分成交、撤单和订单状态枚举实测。
2. 对数据库写失败、进程强杀、撤单超时和系统时钟跳变做专项故障注入。
3. 如妙想新增逐笔成交接口，替换外部聚合成交估算并补齐真实费用。
4. 在进入任何真实资金适配前重新独立安全审查；当前代码只面向内部/外部模拟盘。
