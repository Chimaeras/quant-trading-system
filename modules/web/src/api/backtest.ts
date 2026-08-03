import request from './request'

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
  avgReturn: number
  medianReturn: number
  maxReturn: number
  minReturn: number
  totalObservations: number
  avgWin: number
  avgLoss: number
  profitLossRatio: number
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
  rankIcMean: number
  rankIcStd: number
  rankIcIr: number
  rankIcPositiveRate: number
  dailyRankIc: Array<{ tradeDate: string; rankIc: number; sampleSize: number }>
  groupedReturns: Array<{ groupNo: number; label: string; avgReturn: number; observations: number }>
  longShortSpread: number
  universeNote: string
}

export interface FactorIcSummary {
  holdingDays: number
  crossSectionDays: number
  totalObservations: number
  rankIcMean: number
  rankIcStd: number
  rankIcIr: number
  rankIcPositiveRate: number
  longShortSpread: number
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
    const res = await request.post<{ data: ScoringBacktestResponse }>(
      '/backtest/scoring',
      body,
      { timeout: 120000 }
    )
    return (res as { data?: ScoringBacktestResponse })?.data as ScoringBacktestResponse
  },

  async getStrategies() {
    const res = await request.get<{ data: string[] }>('/backtest/scoring/strategies')
    return (res as { data?: string[] })?.data ?? []
  },

  async getRuns(limit = 20) {
    const res = await request.get<{ data: BacktestRunSummary[] }>('/backtest/scoring/runs', {
      params: { limit }
    })
    return (res as { data?: BacktestRunSummary[] })?.data ?? []
  },

  async getRun(resultId: string) {
    const res = await request.get<{ data: ScoringBacktestResponse }>(`/backtest/scoring/run/${resultId}`)
    return (res as { data?: ScoringBacktestResponse })?.data as ScoringBacktestResponse
  },

  async runStrategyBacktest(body: StrategyBacktestRequest) {
    const res = await request.post<{ data: StrategyBacktestResponse }>(
      '/backtest/strategy/run',
      body,
      { timeout: 120000 }
    )
    return (res as { data?: StrategyBacktestResponse })?.data as StrategyBacktestResponse
  },

  async getStrategyTypes() {
    const res = await request.get<{ data: string[] }>('/backtest/strategy/strategies')
    return (res as { data?: string[] })?.data ?? []
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
  turnover: number
  new_count: number
  exit_count: number
}

export interface TurnoverResponse {
  strategy: string
  start_date: string
  end_date: string
  top_n: number
  signal_days: number
  avg_turnover: number
  max_turnover: number
  min_turnover: number
  daily_turnover: TurnoverDaily[]
  note: string
}

export function analyzeTurnover(params: {
  strategy?: string
  startDate: string
  endDate: string
  topN?: number
}): Promise<TurnoverResponse> {
  return request.get('/backtest/turnover', { params })
}

export interface StabilitySegment {
  label: string
  signal_days: number
  avg_return: number
  win_rate: number
  profit_loss_ratio: number
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

export function analyzeStability(params: {
  strategy: string
  startDate: string
  endDate: string
  topN?: number
  holdingDay?: number
}): Promise<StabilityResponse> {
  return request.get('/backtest/stability', { params })
}
