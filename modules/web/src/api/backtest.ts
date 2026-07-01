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
}

export interface PeriodStat {
  winRate: number
  avgReturn: number
  medianReturn: number
  maxReturn: number
  minReturn: number
  totalObservations: number
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

export interface ScoringBacktestResponse {
  strategyType: string
  startDate: string
  endDate: string
  topN: number
  totalTradingDays: number
  executionAssumptions: Record<string, unknown>
  holdingPeriodStats: Record<number, PeriodStat>
  dailyDetails: DailyDetail[]
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
