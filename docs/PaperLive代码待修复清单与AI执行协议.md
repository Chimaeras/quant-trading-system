# Paper Live 代码待修复清单与 AI 执行协议

## 1. 文档目的

本文档用于指导 Cursor、Codex 或其他编程 AI 修复 quant-trading-system 项目中 Paper Live（妙想模拟盘实时研判与模拟下单）第一版实现的逻辑问题和安全漏洞。

本文件不是重新设计方案，而是基于当前代码审查结果形成的可执行修复单。执行 AI 必须按照优先级逐项修复，并为每个问题补充自动化测试。

当前代码基线：

- 项目目录：E:/Project/quant-trading-system
- 主要模块：modules/compute-python/paper
- 当前版本提交：3795dae2 feat(paper-live): 妙想模拟盘Paper Live全量开发M1-M4
- 审查时离线测试结果：119 passed，3 warnings
- 本次审查未调用真实交易、撤单或其他有副作用的 API

结论：当前代码可以继续作为开发基础，但在完成 P0 修复前，不允许使用 run --confirm 执行任何真实模拟盘交易请求。当前测试全部通过，不代表交易链路安全，原因是现有测试没有覆盖妙想官方非空持仓、非空委托数据结构以及多项失败闭环。

## 2. 强制安全边界

执行修复时必须遵守以下规则：

1. 只修复 Paper Live 相关代码、测试、fixture 和必要文档，不做无关重构。
2. 单元测试、集成测试、回归测试禁止调用 trade、cancel 或任何有副作用的妙想接口。
3. 测试默认只使用本地 fixture、Mock HTTP、FakeBrokerClient 或 VCR 类离线录制数据。
4. 未完成全部 P0 修复并通过验收前，不得建议用户运行 run --confirm。
5. 不得把失败的持仓、委托、余额查询转换为空列表后继续下单。
6. 不得用固定的 9%、9.8% 等近似值替代可靠的涨停价数据。
7. 不得把官方接口返回的 malformed、UNKNOWN、空订单号或空证券代码当作正常数据。
8. 不得在日志、artifact、fixture、提交文件中输出 API Key、uid、accUID、accID、账户名称等敏感信息。
9. 不得为了让测试通过而放宽风控、忽略错误或吞掉异常。
10. 如果无法确认妙想接口字段含义，先保留原始响应并阻断下单，不得猜测字段后继续交易。

## 3. 总体优先级

| 优先级 | 含义 | 处理要求 |
|---|---|---|
| P0 | 会导致错误下单、重复下单、风险失控或无法可靠停止 | 必须全部完成后，才允许首次 confirm |
| P1 | 会导致状态判断错误、审计失真、异常场景不安全 | P0 后立即完成 |
| P2 | 可维护性、可观测性、长期稳定性问题 | 首次安全模拟盘闭环后完成 |

建议修复顺序：

1. P0-01 至 P0-06：先修数据结构、失败闭环、停止逻辑、确认一致性、仓位增量和涨停硬闸。
2. P1-01 至 P1-12：再修订单状态、时间、交易日、artifact、资金、对账、输入校验和 HTTP 错误处理。
3. P2-01 至 P2-04：最后修哈希、时区、查询展示和资源释放。
4. 每完成一个任务，先运行相关测试，再运行全量测试。
5. 每个优先级完成后，再进行一次人工代码审查和离线端到端回归。

## 4. 妙想接口数据结构基线

以下字段映射必须以官方响应结构为准，不能继续沿用当前代码中的猜测字段。

### 4.1 持仓 positions

| 官方字段 | 含义 | 代码目标字段 |
|---|---|---|
| secCode | 证券代码 | Position.code |
| secName | 证券名称 | Position.name，如模型支持 |
| count | 持仓数量 | Position.qty |
| availCount | 可卖数量 | Position.avail_qty |
| value | 持仓市值 | Position.market_value |
| costPrice | 成本价原始整数 | 结合 costPriceDec 还原 Position.cost |
| costPriceDec | 成本价小数位 | 成本价缩放因子 |
| price | 最新价原始整数 | 结合 priceDec 还原 Position.last |
| priceDec | 最新价小数位 | 最新价缩放因子 |
| profit | 浮动盈亏 | Position.profit，如模型支持 |

验收要求：

- 使用一个官方格式的非空持仓 fixture，断言证券代码、总持仓、可卖持仓、成本价、最新价和市值全部正确。
- 价格字段必须按小数位还原，不能直接把原始整数当作价格。
- 非空条目缺少证券代码、数量或关键价格字段时，返回解析失败并阻断，不得追加一个全是零值的 UNKNOWN 持仓。

### 4.2 委托 orders

| 官方字段 | 含义 | 代码目标字段 |
|---|---|---|
| id | 委托编号 | MxOrder.order_id |
| secCode | 证券代码 | MxOrder.code |
| drt | 买卖方向，1 买入，2 卖出 | MxOrder.side |
| price | 委托价原始整数 | 结合 priceDec 还原价格 |
| priceDec | 委托价小数位 | 价格缩放因子 |
| count | 委托数量 | MxOrder.qty |
| tradeCount | 已成交数量 | MxOrder.filled_qty |
| status | 委托状态 | MxOrder.status |
| time | 委托时间 | MxOrder.time，如模型支持 |

官方状态定义：

| 状态 | 含义 | 是否仍需视为 active |
|---|---|---|
| 1 | 未报 | 是 |
| 2 | 已报 | 是 |
| 3 | 部成 | 是，剩余数量仍有效 |
| 4 | 已成 | 否 |
| 5 | 部成待撤 | 是，直到最终状态明确 |
| 6 | 已报待撤 | 是，直到最终状态明确 |
| 7 | 部撤 | 通常否，但需结合剩余数量 |
| 8 | 已撤 | 否 |
| 9 | 废单 | 否 |
| 10 | 撤单失败 | 仍需作为异常委托关注，不能简单当作已完成 |

## 5. P0 必须修复项

### P0-01：修正 positions 和 orders 的官方字段解析

关联文件：

- modules/compute-python/paper/broker_client.py，约第 48 行
- modules/compute-python/paper/models.py
- modules/compute-python/paper/tests/

当前问题：

- positions 当前尝试读取 stockCode、secuCode、holdNum、quantity、availableQty、holdNum、maketValue、marketValue、lastPrice、newPrice 等字段。
- orders 当前尝试读取 orderId、orderNo、stockCode、secuCode、orderDrt、side、orderPrice、price、orderNum、quantity、dealNum、filledQty 等字段。
- 这些字段无法可靠解析妙想官方的 secCode、count、availCount、value、costPrice、price、id、drt、tradeCount、status 等字段。
- 已用官方形状的非空数据做只读验证，当前得到空证券代码、零数量和错误价格。

风险：

- 已持仓股票被当成未持仓，导致重复买入。
- 已有活动委托被当成无委托，导致重复下单。
- 卖出、订单超时匹配、账户展示和对账全部可能失真。

修复要求：

1. 以本文件第 4 节的官方字段表为唯一解析基线。
2. 为价格统一实现带小数位的还原函数，明确原始整数、缩放因子和最终 Decimal 或 float 的边界。
3. 将 drt=1 映射为 buy，drt=2 映射为 sell，其他值直接解析失败。
4. 对每个非空条目做严格校验：证券代码、数量、订单号或关键字段缺失时返回 PARSE_ERROR。
5. 禁止解析失败后追加 UNKNOWN 的零值对象再继续运行。
6. 保留原始条目用于 artifact 脱敏审计。
7. 为非空 positions、非空 orders、价格缩放、方向映射和 malformed 条目增加测试。

验收标准：

- 官方形状非空持仓 fixture 能还原所有关键字段。
- 官方形状非空订单 fixture 能还原订单号、代码、方向、价格、数量、成交数、状态。
- malformed 条目会让查询结果失败或进入 BLOCKED，不能被当成空结果。

### P0-02：持仓、委托、余额查询失败必须 fail-closed

关联文件：

- modules/compute-python/paper/runner.py，约第 96 行
- modules/compute-python/paper/runner.py，约第 202 行
- modules/compute-python/paper/quote_client.py
- modules/compute-python/paper/broker_client.py

当前问题：

- runner 在准备阶段获取 positions、orders 时忽略了查询结果错误。
- 查询失败时可能继续使用空列表计算策略并进入提交。
- 提交后再次查询 balance、positions、orders 时也忽略结果，可能在查询失败时仍返回成功的 account_after。

风险：

- 账户状态未知时继续买入。
- 真实持仓或活动委托不可见时重复下单。
- 提交成功但事后查询失败，系统却记录成成功，造成审计和资金状态失真。

修复要求：

1. confirm 前 balance、positions、orders 必须全部成功。
2. 任意一个查询失败、超时、解析失败或数据不可信，立即返回 BLOCKED，不提交 trade。
3. dry-run 可以展示 degraded 结果，但必须在结果中记录明确的失败类型，并且绝不提交。
4. 每次 trade 后必须成功查询余额、持仓和订单；任一失败，最终结果必须是 BLOCKED 或 UNKNOWN，不能是成功。
5. 结果中记录 query endpoint、错误码、错误类型、失败阶段和是否已提交。
6. 增加以下测试：positions 失败、orders 失败、balance 失败、提交后查询失败、提交前查询为空但实际是错误。

验收标准：

- 任意准备阶段查询失败时 trade_calls 等于 0。
- 任意提交后查询失败时结果不是 SUCCESS。
- artifact 能明确区分 NO_DATA、QUERY_ERROR、PARSE_ERROR 和 BLOCKED。

### P0-03：stop_run 必须立即终止剩余意图

关联文件：

- modules/compute-python/paper/runner.py，约第 174 行
- modules/compute-python/paper/runner.py，约第 239 行

当前问题：

- stop_run 只设置了 _run_stopped=True，但主循环没有检查该状态。
- 遇到额度不足、Key 无效、接口解绑等停止性错误后，后续 intent 仍可能继续调用 trade。

风险：

- 一个确定性错误触发多次交易请求。
- 额度或权限异常时重复消耗接口配额。
- 运行已经不可安全继续，但系统仍显示进行中。

修复要求：

1. stop_run 设置后，主循环立即 break 或 return。
2. 剩余未执行 intents 统一标记 SKIPPED_RUN_STOPPED。
3. 运行级错误中记录第一个停止原因和错误码。
4. 停止后不得再调用 trade、cancel、查询型重试以外的副作用接口。
5. 初始化并明确 _run_stopped 的生命周期，避免 runner 复用时继承旧状态。

验收标准：

- 两个 intent 的测试中，第一个 trade 返回 113、114、116 或 404 时，trade_calls 必须等于 1。
- 第二个 intent 必须是 SKIPPED_RUN_STOPPED。
- 运行结果必须包含 STOP_RUN 错误码和可读原因。

### P0-04：preview 与 confirm 必须确认同一份不可变计划

关联文件：

- modules/compute-python/paper/cli.py，约第 156 行
- modules/compute-python/paper/runner.py，约第 153 行
- modules/compute-python/paper/runner.py，约第 254 行
- modules/compute-python/paper/artifacts.py

当前问题：

- CLI preview 后调用 run_confirm，confirm 会重新执行 prepare。
- prepare 会重新查询行情、账户和候选股，并可能生成新的 intents。
- 当前确认只校验 confirm_ack 是否等于 config_hash(config)，没有绑定 preview run_id、候选快照、行情快照、账户快照和一次性确认凭证。

风险：

- 用户确认的不是预览时看到的计划。
- 预览和确认重复消耗查询配额。
- 同一个配置哈希可以重复使用，形成重放风险。
- 市场状态或候选股变化后，系统可能提交用户未预览过的订单。

推荐修复方案：

1. dry-run 生成并持久化不可变 PaperRun Preview。
2. Preview 至少包含 preview_run_id、config_hash、candidate_hash、quote_hash、account_hash、intents、created_at、confirm_expires_at。
3. confirm 必须传入 preview_run_id 和一次性 confirm_token。
4. confirm 校验 token 未使用、未过期、配置哈希一致、计划内容未被修改。
5. confirm 不得重新计算策略和重新生成 intents，只能重新做账户安全校验和交易时段校验。
6. 如果需要重新获取行情或账户，发现变化超过阈值时必须 BLOCKED 并要求重新 dry-run。
7. 成功提交或明确拒绝后，confirm_token 必须失效。
8. 记录 preview、confirm、submit 三个阶段的关联关系。

验收标准：

- preview 后修改行情、候选数据或配置，旧 token 不能提交新计划。
- preview 后重复使用同一 token，第二次必须 BLOCKED。
- preview 过期后 confirm 不得调用 trade。
- confirm 提交的 intents 必须与 preview artifact 完全一致。

### P0-05：按仓位增量下单，禁止把目标仓位当作新增数量

关联文件：

- modules/compute-python/paper/decide.py，约第 118 行
- modules/compute-python/paper/risk.py，约第 80 行
- modules/compute-python/paper/runner.py

当前问题：

- decide 根据可用现金计算 target_qty，但没有减去已有持仓数量。
- 例如目标 400 股、当前已有 100 股时，当前实现可能再次买入 400 股，最终持仓变成 500 股。
- risk 主要检查本次订单金额与总资产的比例，没有把已有持仓市值纳入目标仓位约束。

修复要求：

1. 明确定义 target_qty、current_qty、active_buy_qty、desired_qty。
2. 建议使用：
   desired_qty = max(target_qty - current_qty - active_buy_qty, 0)
3. 只对 desired_qty 生成买入意图。
4. 已有可卖持仓和总持仓分别参与买卖约束，不能混用。
5. 风控必须校验订单执行后的预估持仓市值占总资产比例。
6. 按 A 股 100 股整手约束取整，并对不足一手的增量给出 NO_ACTION 或 BLOCKED 原因。

验收标准：

- 当前 100 股、目标 400 股时，只生成 300 股买入。
- 已有 400 股或 active_buy_qty 已覆盖目标时，不生成买入。
- 多个相同标的意图不能突破目标仓位。

### P0-06：涨停买入必须使用可靠的涨停价硬闸

关联文件：

- modules/compute-python/paper/risk.py，约第 72 行
- modules/compute-python/paper/decide.py，约第 111 行
- modules/compute-python/paper/quote_client.py

当前问题：

- 当前主要用 quote.pct_chg >= 9.0 或 candidate.is_limit_up 且 pct_chg >= 9.8 的近似规则。
- 这不能处理不同证券类型、价格基准、特殊处理股票和接口已经返回真实涨停价的情况。
- 文档要求的 limit_up_price 硬闸还没有真正落地。

修复要求：

1. 优先使用可信来源提供的 limit_up_price，并记录来源、交易日和计算方式。
2. 买入价达到或超过 limit_up_price 时直接 BLOCKED，原因必须是 LIMIT_UP_NOT_BUYABLE。
3. 如果无法获得可靠涨停价，dry-run 可以告警，但 confirm 必须 BLOCKED，原因是 LIMIT_DATA_UNAVAILABLE。
4. 禁止把 9%、9.8% 固定阈值作为最终安全依据。
5. 明确是否允许卖出涨停股；当前要求只禁止买入涨停股。

验收标准：

- quote.pct_chg 低于 9% 但 limit_up_price 已知且买入价达到涨停价时，必须拒绝。
- pct_chg 较高但 limit_up_price 不可信或缺失时，confirm 必须拒绝。
- 非涨停且 limit_up_price 可信时，正常进入后续风控。

## 6. P1 必须修复项

### P1-01：只把真正有效的活动委托纳入重复下单判断

关联文件：

- modules/compute-python/paper/decide.py，约第 81 行
- modules/compute-python/paper/risk.py，约第 89 行

当前问题：

- 当前对所有 buy 委托都按活动委托处理。
- 已成、已撤、废单、部分撤等历史状态可能继续阻止或错误影响新的买入判断。

修复要求：

1. 将 status 映射为明确的枚举和剩余数量。
2. 1、2、3、5、6 默认按 active 处理；4、8、9 不应阻止新的买入；7、10 必须结合剩余数量和异常状态处理。
3. 只对同一证券、同一方向且 remaining_qty > 0 的委托做重复判断。
4. 将“历史订单存在”和“活动订单存在”分离记录。

验收标准：

- 4、8、9 状态不阻止新买入。
- 2、3、5、6 状态会阻止重复买入或只允许补足剩余目标。
- 10 状态不能静默忽略，必须进入异常审计。

### P1-02：拒绝未来时间戳和无效行情进入 BUY

关联文件：

- modules/compute-python/paper/parse_query.py，约第 178 行
- modules/compute-python/paper/decide.py，约第 100 行

当前问题：

- 当前 freshness 计算使用 max(received-market, 0)，未来时间会被压成 0 秒并判定为新鲜。
- decide 只明确拒绝 STALE 和 NOT_TRADABLE，没有把 INVALID 作为 BUY 的硬门槛。
- 已发现官方格式 fixture 的 market_time 晚于 received_at 时，当前可能被判定为 freshness=0、VALID。

修复要求：

1. market_time > received_at + allowed_clock_skew 时，返回 FUTURE_TIMESTAMP 或 INVALID。
2. 校验行情日期、交易时段和本地时区，不只计算秒数。
3. decide 只有 validity == VALID 且时间、交易日、字段完整时才允许 BUY。
4. INVALID、FUTURE_TIMESTAMP、PARSE_ERROR、STALE、NOT_TRADABLE 全部不得生成 BUY。

验收标准：

- 未来行情不会被判定为 freshness=0。
- 无效行情即使其他指标满足，也不会生成 BUY intent。
- 时区和日期跨日场景有测试。

### P1-03：接入交易日历，不得只用周一至周五判断交易日

关联文件：

- modules/compute-python/paper/decide.py，约第 24 行
- 新增或接入 TradingCalendarService

当前问题：

- 当前主要通过 weekday 判断交易日，无法识别法定节假日、调休和临时休市。

修复要求：

1. 注入可替换的 TradingCalendarService 或交易日历数据源。
2. 交易日历不可用时，confirm 必须阻断，不得默认按工作日放行。
3. 判断交易日、交易时段、午间休市和市场类型。
4. 为法定节假日、调休工作日、午间休市添加测试。

### P1-04：修复 run_id 碰撞和 raw artifact 覆盖

关联文件：

- modules/compute-python/paper/runner.py，约第 19 行
- modules/compute-python/paper/mx_http.py，约第 49 行
- modules/compute-python/paper/artifacts.py

当前问题：

- run_id 只有秒级时间精度，preview 和 confirm 可能使用相同的时间，导致目录覆盖。
- raw 文件名主要由 run_id 和 endpoint 组成，同一 endpoint 多次调用时会覆盖前一次响应，例如多个股票的 quotes。

修复要求：

1. run_id 使用 UUID、ULID 或微秒级时间戳，并增加目录存在时的碰撞检查。
2. raw 文件名加入单调递增 call_seq、证券代码、请求哈希或唯一请求编号。
3. artifact 中保存每次调用的相对路径、序号、endpoint、请求摘要和响应哈希。
4. 禁止通过覆盖旧 raw 文件来“去重”。

验收标准：

- 同一 runner 在同一秒内创建两个 run，不覆盖目录。
- 同一 endpoint 调用两次，两个 raw 文件都存在且可追溯。

### P1-05：可用资金扣减必须包含手续费预留

关联文件：

- modules/compute-python/paper/runner.py，约第 197 行
- modules/compute-python/paper/risk.py

当前问题：

- 当前本地 available_cash 只扣减 qty * price，没有扣除预计佣金等费用。
- 多笔订单连续执行时，可能低估实际资金占用。

修复要求：

1. 将订单资金占用定义为成交金额加预计佣金、最低佣金和其他适用费用。
2. 在多订单循环中累计扣减并保留安全 buffer。
3. 模拟成交和实际回报的费用字段分开记录。
4. 对费用规则不可知时，使用保守上限或直接 BLOCKED，不能按零费用放行。

验收标准：

- 多笔订单的可用资金不会低于零。
- 佣金使订单超过资金上限时，后续订单不提交。

### P1-06：增强提交后的对账和结果分类

关联文件：

- modules/compute-python/paper/reconcile.py，约第 34 行
- modules/compute-python/paper/runner.py，约第 202 行

当前问题：

- 当前对账主要按 order_id 判断，没有完整比较订单方向、数量、成交数量、成交价格、状态、持仓、余额和费用。
- 一个订单号匹配但状态为撤单、废单或部分成交时，仍可能被报告为成功。

修复要求：

1. 对每个提交请求关联对应订单号、代码、方向、委托数量和委托价。
2. 对账时检查 status、tradeCount、remaining_qty、成交价、成交金额和费用。
3. 同步比较提交前后 balance、positions、orders。
4. 明确 SUCCESS、PARTIAL、REJECTED、CANCELED、UNKNOWN、BLOCKED 等状态。
5. 任意查询失败、订单缺失或字段冲突时，不得判为 SUCCESS。
6. 记录 rejected_count、partial_count、canceled_count 和 query_error_count。

验收标准：

- 已撤或废单不能被判为成功。
- 部成订单应返回 PARTIAL，并记录剩余数量。
- 找不到订单或对账查询失败时返回 UNKNOWN/BLOCKED。

### P1-07：候选股输入必须严格校验并在主数据源失败时阻断

关联文件：

- modules/compute-python/paper/candidates.py，约第 99 行
- modules/compute-python/paper/runner.py

当前问题：

- T1 查询失败时当前可能返回空候选并继续流程，掩盖主数据源故障。
- 文件输入和 T1 解析缺少证券代码、交易所、日期、名称、重复项和数量上限校验。
- 同一证券可能重复出现，造成重复 intent。

修复要求：

1. dry-run 可以返回降级结果，但必须记录 T1_UNAVAILABLE。
2. confirm 在主 T1 源不可用时必须 BLOCKED，除非用户显式提供经过校验的候选文件覆盖。
3. 证券代码必须是六位数字，并正确映射 SH/SZ。
4. 校验名称非空、数据日期有效、候选不重复、数量不超过 max_observe_codes。
5. 重复代码按明确规则去重，规则必须可审计。
6. 禁止把空候选与“正常无选股结果”混为一谈。

验收标准：

- T1 网络失败时 confirm 不调用 trade。
- 非法代码、重复代码、超限代码会被拒绝或清晰标记。
- 候选为空但来源成功时是 NO_CANDIDATE；来源失败时是 DATA_SOURCE_BLOCKED。

### P1-08：每次提交后更新本地活动委托集合，避免同轮重复下单

关联文件：

- modules/compute-python/paper/runner.py
- modules/compute-python/paper/decide.py

当前问题：

- 同一轮多个相同候选可能生成重复意图。
- 提交一个买入后，本地 _open_orders 未必立即加入新订单，后续意图仍可能重复买入。

修复要求：

1. 候选输入先去重。
2. 每次 trade 成功返回 order_id 后，立即将新委托加入本地活动集合。
3. 如果无法确认是否接受，下一笔交易前必须重新查询委托或阻断。
4. 活动集合按 code、side、remaining_qty 和 status 管理。

验收标准：

- 重复候选只产生一个买入意图。
- 第一笔提交后，第二笔相同标的不会重复超配。

### P1-09：完善 HTTP 状态处理、解析异常和重试分类

关联文件：

- modules/compute-python/paper/mx_http.py，约第 80 行
- modules/compute-python/paper/mx_result.py

当前问题：

- 当前没有统一调用和检查 HTTP 状态码。
- HTTP 500 可能被当成业务错误返回，导致错误重试或不重试。
- 非字典 JSON 可能在 MxResult.from_json 中引发未分类异常。
- 查询和交易接口的重试边界不够清晰。

修复要求：

1. 保存 HTTP status、headers 摘要、请求耗时和响应摘要。
2. 4xx、5xx、超时、连接错误、JSON 解析错误分别分类。
3. 只允许对查询型接口的网络错误、超时、部分 5xx 做有限重试。
4. trade 和 cancel 默认禁止自动重试，避免重复副作用。
5. 非字典 payload 统一转为 PARSE_ERROR，不得抛出未捕获异常。
6. 对 401、403、Key 无效、额度不足、接口解绑等错误触发 stop_run 或明确阻断。

验收标准：

- 模拟 HTTP 500、超时、空响应、数组 JSON、非法 JSON 均有确定性结果。
- trade/cancel 失败不会自动重复提交。
- 结果中保留原始 HTTP 状态但不泄露敏感信息。

### P1-10：脱敏 API Key、账户标识和原始提交数据

关联文件：

- modules/compute-python/paper/mx_http.py，约第 22 行
- modules/compute-python/paper/artifacts.py，约第 45 行
- docs/paper-captures/2026-08-18_capture.json
- modules/compute-python/paper/tests/fixtures/

当前问题：

- 当前脱敏逻辑主要覆盖 uid 和 accUID，未完整覆盖 accID、账户标识和账户名称。
- submits.json 可能直接写入 SubmitRecord.raw，绕过 HTTP 层脱敏。
- 已提交的 capture 和 fixture 中可以看到 accID、uid、accName 等账户信息。

修复要求：

1. 在统一 artifact 写入层递归脱敏 uid、accUID、accID、accountId、accountNo、账户名称及同义字段。
2. 对 headers、URL、query、request body、response body、SubmitRecord.raw 全部脱敏。
3. 不得把 API Key 写入日志、异常、artifact、fixture 或测试快照。
4. 清理已提交 fixture/capture 中的账户标识，仅保留不可逆占位符。
5. 增加 artifact 内容扫描测试，发现敏感字段即失败。
6. 不要在日志中打印完整请求 URL，尤其是带 query 参数的 URL。

验收标准：

- 生成的所有 artifact 不包含真实账户标识和 API Key。
- fixture 只包含脱敏数据。
- 脱敏不会破坏非敏感业务字段。

### P1-11：补充配置范围和密钥输入校验

关联文件：

- modules/compute-python/paper/config.py，约第 52 行

当前问题：

- 当前主要做类型转换，没有完整校验数值范围、空白字符和互相矛盾的配置。

修复要求：

1. max_trades 必须大于 0。
2. max_name_weight 必须大于 0 且小于等于 1。
3. quote_stale_seconds 必须大于 0。
4. slippage_bps、max_retry 等不能为负数。
5. query_interval 必须大于 0。
6. max_observe_codes 必须在合理上限内。
7. API Key 必须非空、去除首尾空格后仍有效，禁止打印。
8. URL、时区、市场类型必须是允许值。
9. 配置错误必须在启动阶段失败，不要运行到下单阶段才报错。

验收标准：

- 每个非法配置都有独立测试。
- 配置错误不会发起任何 HTTP 请求。

### P1-12：严格校验 trade 和 cancel 输入

关联文件：

- modules/compute-python/paper/broker_client.py，约第 88 行
- modules/compute-python/paper/broker_client.py，约第 112 行
- modules/compute-python/paper/cli.py

当前问题：

- 当前 side 不是 buy 时可能被默认映射为 sell，非法值存在误卖风险。
- cancel_order 可以在 stock_code 为空时继续请求，但官方取消委托要求提供证券代码。
- CLI 单笔撤单入口没有与全局一致的明确确认门槛。

修复要求：

1. side 只允许 buy 或 sell，其他值直接拒绝。
2. 证券代码必须为六位数字且交易所映射合法。
3. 数量必须为正数，并满足 A 股整手约束。
4. 价格必须是正数，精度不能超过接口允许范围。
5. cancel 必须同时拥有 order_id 和经过查询验证的 stock_code；无法验证时 BLOCKED。
6. 所有副作用命令都要求显式确认，并记录 confirm token 或等价审计字段。
7. trade 和 cancel 的输入校验错误必须在调用 HTTP 前返回。

验收标准：

- 非法 side、空代码、零数量、非整手、非法价格均不会调用接口。
- cancel 缺少证券代码时不会调用接口。

## 7. P2 改进项

### P2-01：使用完整 SHA-256 或明确哈希碰撞策略

关联文件：

- modules/compute-python/paper/artifacts.py

当前问题：

- raw_hash 当前截取为 16 位十六进制，只有 64 bit，长期审计存在碰撞可能。

修复要求：

- 关键请求、响应、Preview 计划和提交记录使用完整 SHA-256。
- 如果为了展示保留短哈希，必须同时保存完整哈希。
- hash 输入必须包含规范化后的 payload、endpoint、method 和关键上下文。

### P2-02：统一时区、时钟偏差和可注入时间源

关联文件：

- modules/compute-python/paper/runner.py
- modules/compute-python/paper/parse_query.py
- modules/compute-python/paper/config.py

修复要求：

- 明确使用 Asia/Shanghai 作为市场业务时区。
- 所有时间使用带时区的 datetime。
- 注入 Clock，测试可以固定 now。
- 增加允许的时钟偏差配置，并对未来数据做硬校验。

### P2-03：改善行情查询展示和查询资源管理

关联文件：

- modules/compute-python/paper/quote_client.py
- modules/compute-python/paper/cli.py

修复要求：

- snapshot 查询应优先使用候选名称，避免展示为 code(code)。
- MxHttpClient 使用的 Session 应在生命周期结束时关闭。
- 查询日志展示 endpoint、股票代码、数据时间和 freshness，但不展示完整密钥或敏感 query。

### P2-04：补齐运行级状态机和可观测性

关联文件：

- modules/compute-python/paper/runner.py
- modules/compute-python/paper/models.py
- modules/compute-python/paper/artifacts.py

建议状态：

- CREATED
- PREPARING
- PREVIEW_READY
- CONFIRMING
- SUBMITTING
- RECONCILING
- SUCCESS
- PARTIAL
- BLOCKED
- UNKNOWN
- STOPPED

要求：

- 所有状态迁移有明确入口和原因。
- STOPPED、BLOCKED、UNKNOWN 必须与 NO_ACTION 区分。
- 每个意图记录 decision、risk、submit、reconcile 四段结果。
- 运行级指标至少包括 query_count、trade_count、cancel_count、blocked_count、stop_run_count、reconcile_error_count。

## 8. 建议新增的测试矩阵

### 8.1 数据解析

- 官方非空 positions 字段映射。
- 官方非空 orders 字段映射。
- costPriceDec、priceDec 的价格还原。
- drt 方向映射。
- 所有官方订单状态映射。
- 非空 malformed 条目阻断。
- 非字典 JSON、空 JSON、缺字段 JSON。

### 8.2 风控和决策

- 已有部分持仓时只买入增量。
- 已有活动买单时不重复下单。
- 已成、已撤、废单不错误阻断。
- 涨停价已知且达到涨停价时拒绝。
- 涨停价缺失时 confirm 拒绝。
- INVALID、STALE、未来时间行情不生成 BUY。
- 节假日、午间休市、非交易时段不生成 BUY。
- 资金扣除佣金后的余额不足时拒绝。

### 8.3 运行控制

- balance、positions、orders 任一查询失败时 trade_calls=0。
- trade 后任何账户查询失败时结果不是 SUCCESS。
- stop_run 后剩余 intents 全部跳过。
- 同一 run 在同一秒内不会覆盖 artifact。
- 同一 endpoint 多次调用不会覆盖 raw 文件。
- duplicate candidates 不会产生重复交易。

### 8.4 Preview/Confirm

- preview 和 confirm 使用不同行情时拒绝或要求重新 preview。
- preview 和 confirm 使用不同候选时拒绝。
- 配置被修改时旧 token 失效。
- token 过期不能提交。
- token 成功使用后不可重放。
- confirm 不重新生成新的 intents。

### 8.5 安全和输入校验

- 配置非法时不发 HTTP 请求。
- API Key 不出现在日志和 artifact。
- accID、uid、accUID、accName 等敏感字段会被递归脱敏。
- side、代码、数量、价格非法时不发 trade/cancel 请求。
- cancel 缺少证券代码时不发请求。

## 9. 测试命令

在 E:/Project/quant-trading-system/modules/compute-python 目录执行：

~~~text
python -m pytest -q
~~~

建议按模块执行：

~~~text
python -m pytest -q paper/tests/test_broker_client.py
python -m pytest -q paper/tests/test_decide.py paper/tests/test_risk.py
python -m pytest -q paper/tests/test_runner.py
python -m pytest -q paper/tests/test_artifacts.py paper/tests/test_reconcile.py
~~~

执行 AI 必须补充一个“副作用调用保护测试”：

- 测试环境中的 FakeBrokerClient 对 trade 和 cancel 默认抛出异常。
- 只有专门测试“允许提交”的测试，才可以使用计数型 FakeBrokerClient。
- 所有离线测试结束后，trade_calls 和 cancel_calls 必须为 0，除非测试本身明确验证了调用前置条件。

## 10. AI 执行协议

请执行 AI 按以下协议工作：

### 第一阶段：确认基线

1. 读取本文件。
2. 读取 docs 中的 Paper Live 合并开发文档和妙想模拟盘接口说明。
3. 读取 modules/compute-python/paper 当前实现和现有测试。
4. 运行全量测试并记录基线。
5. 不修改代码前先输出准备修复的任务编号。

### 第二阶段：按优先级修复

1. 先完成 P0-01 至 P0-06。
2. 每个任务必须同时修改实现和测试。
3. 测试失败时先修复根因，不得删除或弱化原有安全测试。
4. 不得跳过数据解析和失败闭环，直接修改 CLI 让流程“看起来成功”。
5. 每个任务完成后输出：修改文件、根因、修复方式、新增测试、测试结果、是否存在未解决风险。

### 第三阶段：回归与审查

1. 运行全量 pytest。
2. 搜索代码中所有 trade、cancel、run_confirm、_open_orders、positions、orders、limit_up、retry、raw、sanitize 的调用点。
3. 检查所有错误分支是否仍可能进入副作用 API。
4. 检查所有 artifact 是否脱敏。
5. 检查 git diff，确保没有无关文件和密钥。
6. 只有全量测试通过且 P0 验收全部满足，才允许给出“可以进入离线模拟盘联调”的结论。

### 第四阶段：输出修复报告

修复完成后必须输出：

- 已完成任务编号。
- 未完成任务编号及原因。
- 修改文件清单。
- 新增和修改的测试清单。
- 全量测试结果。
- trade/cancel 是否实际调用。
- 已知残余风险。
- 首次 dry-run 的安全运行命令。
- 明确说明在什么条件下仍然禁止 run --confirm。

## 11. 首次 confirm 前的最终门禁

只有同时满足以下条件，才允许用户考虑第一次 confirm：

1. P0-01 至 P0-06 全部完成并有自动化测试。
2. P1-01 至 P1-12 至少完成数据安全、输入校验、对账和脱敏相关项。
3. 全量 pytest 通过。
4. 所有官方非空 positions/orders fixture 解析正确。
5. balance、positions、orders 任一失败都会阻断。
6. stop_run 后不会产生后续 trade/cancel。
7. preview 与 confirm 使用同一不可变计划和一次性 token。
8. 涨停价缺失时 confirm 会阻断买入。
9. 运行 artifact 已脱敏，仓库中没有 API Key 或真实账户标识。
10. 先完成至少一轮纯离线 dry-run，再由用户明确确认是否进入模拟盘联调。

## 12. 本次审查结论

当前实现的基础模块、测试框架和离线流程已经具备，但交易安全闭环尚未完成。最严重的问题不是测试数量，而是：

- 官方持仓和委托非空数据当前无法正确解析。
- 查询失败可能被当成空数据继续下单。
- stop_run 标记不会真正停止后续 intent。
- preview 与 confirm 不是同一份不可变交易计划。
- 已有持仓未从目标数量中扣除。
- 涨停买入缺少可靠价格硬闸。

因此，当前版本适合作为“继续修复和离线回归”的开发基线，不适合作为可执行模拟盘交易版本。执行 AI 必须先完成 P0，再进行 P1 回归，最后才允许讨论首次 confirm。
