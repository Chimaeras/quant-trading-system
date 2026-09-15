import request, { unwrap } from './request'
import type { MonotonicityLabel } from './factor'

export interface ScoringBacktestRequest {
  strategyType?: string
  startDate?: string
  endDate?: string
  topN?: number
  holdingDays?: number[]
  commissionRate?: number
  sellTaxRate?: number
  slippageRate?: number
  factorGroupCount?: number
  factorAnalysisHoldingDay?: number
}

export interface PeriodStat {
  winRate: number
  /** 成交成功样本胜率（与 winRate 同义） */
  filledSampleWinRate?: number
  avgReturn: number
  medianReturn: number
  maxReturn: number
  minReturn: number
  totalObservations: number
  signalCount?: number
  skippedCount?: number
  avgWin: number
  avgLoss: number
  /** 盈亏比 = avgWin / |avgLoss|；无亏损样本时后端下发 null（无定义），UI 渲染为 "--" */
  profitLossRatio: number | null
}

export interface StockReturn {
  stockCode: string
  exchangeType: string
  stockName: string
  finalScore: number
  rankNo: number
  entryDate: string
  entryPrice: number
  forwardReturns: Record<number, number | null>
  skipReason?: string | null
  periodSkipReasons?: Record<number, string | null>
}

export interface DailyDetail {
  tradeDate: string
  topStocks: StockReturn[]
  dailyWinRate: Record<number, number | null>
}

export interface FactorAnalysis {
  holdingDays: number
  groupCount: number
  crossSectionDays: number
  totalObservations: number
  /** Rank IC 均值；无可用截面日时不可计算 ⟹ null（不得渲染成 0） */
  rankIcMean: number | null
  /** Rank IC 总体标准差（÷T）；无可用截面日时为 null；真零离散度仍为 0 */
  rankIcStd: number | null
  /** ICIR；IC 序列无离散度（截面日不足 2）时**无定义**⟹ null（≠「IR 恰为 0」） */
  rankIcIr: number | null
  /** IC 为正的截面日占比；无可用截面日时为 null；真为 0 时仍为 0 */
  rankIcPositiveRate: number | null
  dailyRankIc: Array<{ tradeDate: string; rankIc: number; sampleSize: number }>
  /**
   * groupedReturns[].observations = 参与聚合的**截面日数**（单位「天」，不是成分股数；D-R014-03 裁定 B：
   * 日级面的成分股数在引擎侧已改名为 `DailyGroupReturnStat.nameCount`，单位「只」）；空组 avgReturn = null
   */
  groupedReturns: Array<{ groupNo: number; label: string; avgReturn: number | null; observations: number }>
  /** 两腿价差 = 最高组均值 − 最低组均值，本面为**净价差**（观测收益已扣费）；口径看 longShortSpreadCostBasis；无非空分组时为 null */
  longShortSpread: number | null
  /** 价差成本基准：本面恒为 NET_AFTER_MODELED_COSTS；载荷毛收益块为 GROSS_BEFORE_COSTS（同名不同量） */
  longShortSpreadCostBasis?: string
  /** 净价差 → 毛价差的精确还原（longShortSpread / (1 − costPerLegRoundTrip)），跨面对齐用 */
  longShortSpreadGrossEquivalent?: number | null
  /** 单腿往返成本（乘性口径：佣金+印花税+双边滑点复合） */
  costPerLegRoundTrip?: number | null
  /** 并列口径：样本标准差（÷(T−1)）的 IC 标准差；主口径 rankIcStd 为总体标准差 ÷T */
  rankIcStdSample?: number | null
  /** 并列口径：由 rankIcStdSample 算出的 ICIR；无定义时为 null */
  rankIcIrSample?: number | null
  /** 线性（Pearson）IC 均值；不可定义时为 null（原实现写占位 0.0） */
  pearsonIcMean?: number | null
  /** 分组单调性标签（含 NO_DISPERSION = 各组收益全相等，非任何方向） */
  monotonicity?: MonotonicityLabel
  universeNote: string
}

export interface FactorIcSummary {
  holdingDays: number
  crossSectionDays: number
  totalObservations: number
  /** Rank IC 均值；无可用截面日时不可计算 ⟹ null（不得渲染成 0） */
  rankIcMean: number | null
  /** Rank IC 总体标准差（÷T）；无可用截面日时为 null；真零离散度仍为 0 */
  rankIcStd: number | null
  /** ICIR；IC 序列无离散度时**无定义**⟹ null（≠「IR 恰为 0」） */
  rankIcIr: number | null
  /** IC 为正的截面日占比；无可用截面日时为 null；真为 0 时仍为 0 */
  rankIcPositiveRate: number | null
  /** 两腿价差（本面为**净价差**；口径看 longShortSpreadCostBasis）；无非空分组时为 null */
  longShortSpread: number | null
  longShortSpreadCostBasis?: string
  longShortSpreadGrossEquivalent?: number | null
  costPerLegRoundTrip?: number | null
  rankIcStdSample?: number | null
  rankIcIrSample?: number | null
  pearsonIcMean?: number | null
}

export interface ScoringBacktestResponse {
  /** 回测运行标识（已持久化，可按此回溯历史结果） */
  resultId?: string
  strategyType: string
  startDate: string
  endDate: string
  topN: number
  totalTradingDays: number
  executionAssumptions: Record<string, unknown>
  holdingPeriodStats: Record<number, PeriodStat>
  dailyDetails: DailyDetail[]
  factorAnalysis?: FactorAnalysis | null
  factorIcByHolding?: Record<number, FactorIcSummary>
  formulaAudit?: Record<string, unknown>
  shortHorizonValidation?: Record<string, unknown>
  codexFactorIcByHolding?: Record<number, Record<string, FactorIcSummary>>
  /** MP-010 信号→成交漏斗 */
  executionFunnel?: {
    signal_count?: number
    buyable_count?: number
    filled_count?: number
    frozen_count?: number
    reject_reasons?: Record<string, number>
    price_source?: string
    metric_scope?: string
    note?: string
  }
  priceSource?: string
  metricScope?: string
}

/** 历史回测运行摘要（列表项） */
export interface BacktestRunSummary {
  result_id: string
  strategy_type: string
  start_date: string
  end_date: string
  top_n: number
  latest_data_date?: string | null
  created_at?: string | null
  summary?: { totalTradingDays?: number; holdingPeriodStats?: Record<number, PeriodStat> }
}

export const backtestApi = {
  async runBacktest(body: ScoringBacktestRequest) {
    const res = await request.post<ScoringBacktestResponse>(
      '/backtest/scoring',
      body,
      { timeout: 120000 }
    )
    return unwrap(res)
  },

  async getStrategies() {
    const res = await request.get<string[]>('/backtest/scoring/strategies')
    return unwrap(res) ?? []
  },

  async getRuns(limit = 20) {
    const res = await request.get<BacktestRunSummary[]>('/backtest/scoring/runs', {
      params: { limit }
    })
    return unwrap(res) ?? []
  },

  async getRun(resultId: string) {
    const res = await request.get<ScoringBacktestResponse>(`/backtest/scoring/run/${resultId}`)
    return unwrap(res)
  },

  async runStrategyBacktest(body: StrategyBacktestRequest) {
    const res = await request.post<StrategyBacktestResponse>(
      '/backtest/strategy/run',
      body,
      { timeout: 120000 }
    )
    return unwrap(res)
  },

  async getStrategyTypes() {
    const res = await request.get<string[]>('/backtest/strategy/strategies')
    return unwrap(res) ?? []
  }
}

export interface StrategyBacktestRequest {
  strategyType?: string
  stockCode?: string
  exchangeType?: string
  startDate?: string
  endDate?: string
  initialCapital?: number
  shortPeriod?: number
  longPeriod?: number
  bollPeriod?: number
  bollStdDev?: number
  macdFast?: number
  macdSlow?: number
}

export interface StrategyBacktestResponse {
  strategyType: string
  stockCode: string
  exchangeType: string
  stockName: string
  startDate: string
  endDate: string
  initialCapital: number
  finalCapital: number
  totalReturn: number
  maxDrawdown: number
  sharpeRatio: number
  totalTrades: number
  winningTrades: number
  winRate: number
  trades: Array<Record<string, unknown>>
  equityCurve: Array<Record<string, unknown>>
  executionAssumptions?: Record<string, unknown>
}

export interface TurnoverDaily {
  trade_date: string
  /** 候选替换率（比例 0–1，键名自带量纲后缀 _ratio）；渲染时 ×100 显示为百分数 */
  turnover_ratio: number
  new_count: number
  exit_count: number
  /** 纯增量披露：上一日/当日的实际候选规模（min 与 top_n 之比即短员判据） */
  prev_size?: number
  curr_size?: number
  /** 相邻两日 TopN 交集大小；1 - intersection_count / top_n 即本行 turnover_ratio 的未取整值 */
  intersection_count?: number
  /** 该日任一侧候选数少于请求 top_n（分母仍为请求参数，见 denominator_note） */
  short_handed?: boolean
}

export interface TurnoverResponse {
  /**
   * 原始请求的 strategy（null / 空串 / 纯空白一律归一为 null）。D-R008-05 裁定 B 后**只有归一后的两个
   * 回显键**：旧的单键 `strategy`（曾对 null 回 "ALL"、对 "" 回 ""）已删除。
   */
  strategy_requested: string | null
  /** 实际生效值：真正按策略过滤时即该策略名，未过滤时 "ALL"——回显与实际过滤条件一致 */
  strategy_effective: string
  start_date: string
  end_date: string
  top_n: number
  signal_days: number
  /**
   * 候选替换率（比例 0–1，**不是**市场换手率百分数）；D-R008-09 裁定 B 改名后量纲写在键名里：
   * 本面恒为 `_ratio`（0–1 分数，渲染时 ×100），市场换手率面是 `avg_turnover_pct`（百分数）。
   * INSUFFICIENT_SIGNAL_DAYS 时不可计算 ⟹ null（不得渲染成 0%）
   */
  avg_turnover_ratio: number | null
  /** 同上：不可计算时为 null */
  max_turnover_ratio: number | null
  min_turnover_ratio: number | null
  daily_turnover: TurnoverDaily[]
  note: string
  /** OK | INSUFFICIENT_SIGNAL_DAYS；退化输入时 avg/max/min 为 null，靠本键区分「无定义」与「真的 0」 */
  turnover_status?: 'OK' | 'INSUFFICIENT_SIGNAL_DAYS'
  /** 归一约定：当前恒为单向替换率 ONE_WAY_REPLACEMENT_RATIO */
  turnover_basis?: string
  turnover_basis_note?: string
  /** 分母来源：当前恒为 REQUEST_PARAM_TOP_N（不是当日实际规模） */
  denominator?: string
  denominator_note?: string
  avg_turnover_ratio_unit?: string
  avg_turnover_ratio_unit_note?: string
  rounding_rule?: string
  rounding_rule_note?: string
  /** EXACT_STRATEGY | NONE_ALL_STRATEGIES（后者不按策略分组，同日跨策略会互相配对） */
  strategy_filter?: string
  grouped_by_strategy?: boolean
  strategy_filter_note?: string
}

export async function analyzeTurnover(params: {
  strategy?: string
  startDate: string
  endDate: string
  topN?: number
}): Promise<TurnoverResponse> {
  const res = await request.get<TurnoverResponse>('/backtest/turnover', { params })
  return unwrap(res)
}

export interface StabilitySegment {
  label: string
  signal_days: number
  avg_return: number
  win_rate: number
  /** 盈亏比；该分段无亏损样本时为 null，UI 渲染为 "--" */
  profit_loss_ratio: number | null
  observations: number
}

export interface StabilityBenchmark {
  available: boolean
  index_code?: string
  index_name?: string
  index_return?: number
  strategy_avg_return?: number
  outperform?: boolean
  note?: string
}

export interface MarketSegment {
  days: number
  avg_win_rate: number
  index_return: number
}

export interface StabilityResponse {
  strategy: string
  start_date: string
  end_date: string
  split_date: string
  top_n: number
  holding_day: number
  first_half: StabilitySegment
  second_half: StabilitySegment
  full_period: StabilitySegment
  benchmark?: StabilityBenchmark
  market_segments?: { bull: MarketSegment; bear: MarketSegment }
  verdict: string
  note: string
}

export async function analyzeStability(params: {
  strategy: string
  startDate: string
  endDate: string
  topN?: number
  holdingDay?: number
}): Promise<StabilityResponse> {
  const res = await request.get<StabilityResponse>('/backtest/stability', { params })
  return unwrap(res)
}

// ── 事件驱动组合回测（MP-015 / V2-MP-020：异步 + 规范化子表 + 分页）──────────────

/** 组合回测提交请求。predictionRunId 模式下 start/end/horizon/track/topN 由已发布 run 派生。 */
export interface PortfolioBacktestRequest {
  /** 已发布 prediction run（闭环信号源）；省略则用 selector_result 区间模式。 */
  predictionRunId?: string
  startDate?: string
  endDate?: string
  strategyType?: string
  horizon?: number
  initialCash?: number
  maxPositions?: number
  topN?: number
  commissionRate?: number
  sellTaxRate?: number
  slippageRate?: number
}

/** 提交受理响应（202：立即返回 runId，轮询 status）。 */
export interface PortfolioSubmitResponse {
  run_id?: string
  status?: string
  prediction_run_id?: string | null
  strategy_type?: string
  start_date?: string
  end_date?: string
  horizon?: number
  track?: string | null
  top_n?: number
  signal_source?: string
  note?: string
}

/** 运行状态（轮询契约）。 */
export interface PortfolioRunStatus {
  run_id?: string
  status?: string
  progress?: number
  error_message?: string | null
  updated_at?: string | null
}

export interface PortfolioOrderItem {
  seq?: number
  action?: string
  trade_date?: string
  stock_code?: string
  exchange_type?: string
  stock_name?: string | null
  status?: string
  reject_reason?: string | null
  price?: number | null
  shares?: number | null
  amount?: number | null
  gross_amount?: number | null
  commission?: number | null
  stamp_tax?: number | null
  price_source?: string | null
  signal_date?: string | null
  exit_date?: string | null
  event_time?: string | null
}

export interface PortfolioNavPoint {
  trade_date: string
  cash?: number
  position_value?: number
  nav?: number
  positions?: number
}

export interface PortfolioPosition {
  stock_code: string
  exchange_type: string
  stock_name?: string | null
  shares?: number
  entry_price?: number
  mark_price?: number
  entry_date?: string | null
  exit_date?: string | null
  final_status?: string
  unrealized_pnl?: number
  price_source?: string
}

/** 资金守恒审计（V2-MP-020）：final_cash = initial - Σbuy + Σsell。 */
export interface PortfolioCashAudit {
  initial_cash?: number
  buy_cash_out?: number
  sell_cash_in?: number
  computed_final_cash?: number
  actual_final_cash?: number
  balanced?: boolean
  note?: string
}

export interface PortfolioRunDetail {
  run_id?: string
  status?: string
  progress?: number
  error_message?: string | null
  prediction_run_id?: string | null
  strategy_type?: string
  track?: string | null
  start_date?: string
  end_date?: string
  horizon?: number
  top_n?: number
  initial_cash?: number
  max_positions?: number
  weighting?: string
  storage_version?: string
  execution_version?: string
  cost_version?: string
  created_at?: string | null
  updated_at?: string | null
  final_nav?: number
  total_return?: number
  max_drawdown?: number
  total_trades?: number
  reject_count?: number
  reject_funnel?: Record<string, number>
  cash_audit?: PortfolioCashAudit
  prediction_lineage?: Record<string, unknown> | null
  signal_source?: string
  unrealized_pnl?: number
  final_position_count?: number
  nav?: PortfolioNavPoint[]
  nav_count?: number
  open_positions?: PortfolioPosition[]
  positions_count?: number
  orders_count?: number
}

export interface PortfolioRunSummaryItem {
  run_id?: string
  strategy_type?: string | null
  prediction_run_id?: string | null
  track?: string | null
  start_date?: string | null
  end_date?: string | null
  horizon?: number
  final_nav?: number | null
  total_return?: number | null
  max_drawdown?: number | null
  total_trades?: number | null
  reject_count?: number | null
  status?: string
  progress?: number
  error_message?: string | null
  created_at?: string | null
  updated_at?: string | null
}

export interface PortfolioOrderPage {
  run_id?: string
  page?: number
  size?: number
  total?: number
  total_pages?: number
  items?: PortfolioOrderItem[]
}

export interface PortfolioRunList {
  items?: PortfolioRunSummaryItem[]
  page?: number
  size?: number
  total?: number
  total_pages?: number
}

/** 异步受理组合回测（202 契约：ApiResponse.success 恒 code=200，axios 2xx 通过）。 */
export async function runPortfolioBacktest(
  body: PortfolioBacktestRequest
): Promise<PortfolioSubmitResponse> {
  const res = await request.post<PortfolioSubmitResponse>('/backtest/portfolio/run', body, {
    timeout: 30000
  })
  return unwrap(res)
}

/** 轻量状态轮询。 */
export async function getPortfolioBacktestStatus(runId: string): Promise<PortfolioRunStatus> {
  const res = await request.get<PortfolioRunStatus>(`/backtest/portfolio/${runId}/status`)
  return unwrap(res)
}

/** 运行详情（summary + NAV + 期末持仓 + 状态）。 */
export async function getPortfolioBacktest(runId: string): Promise<PortfolioRunDetail> {
  const res = await request.get<PortfolioRunDetail>(`/backtest/portfolio/${runId}`)
  return unwrap(res)
}

/** 订单分页（seq 升序）。 */
export async function getPortfolioOrders(
  runId: string,
  page = 1,
  size = 50
): Promise<PortfolioOrderPage> {
  const res = await request.get<PortfolioOrderPage>(`/backtest/portfolio/${runId}/orders`, {
    params: { page, size }
  })
  return unwrap(res)
}

/** 运行历史（列表分页）。 */
export async function listPortfolioBacktests(page = 1, size = 10): Promise<PortfolioRunList> {
  const res = await request.get<PortfolioRunList>('/backtest/portfolio/list', {
    params: { page, size }
  })
  return unwrap(res)
}
