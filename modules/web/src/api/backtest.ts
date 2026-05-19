import request from './request'

export interface ScoringBacktestRequest {
  strategyType?: string
  startDate?: string
  endDate?: string
  topN?: number
  holdingDays?: number[]
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
  forwardReturns: Record<number, number | null>
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
  }
}
