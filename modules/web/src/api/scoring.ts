import request from './request'

export interface TrendScoreRow {
  id?: number
  stock_code: string
  exchange_type: string
  stock_name?: string
  trade_date: string
  total_score: number
  score_trend: number
  score_bias: number
  score_volume: number
  score_support: number
  score_macd: number
  score_rsi: number
  trend_status: string
  volume_status: string
  macd_status: string
  rsi_status: string
  buy_signal: string
  factor_version?: string
}

export interface TrendScoresPage {
  trade_date: string
  list: TrendScoreRow[]
  total: number
  page: number
  size: number
}

export interface TrendRunResult {
  trade_date: string
  factor_version: string
  bias_threshold: number
  persisted: boolean
  scanned: number
  scored: number
  skipped_insufficient: number
  items: TrendScoreRow[]
}

export const scoringApi = {
  async runTrendScores(body?: { tradeDate?: string; biasThreshold?: number; persist?: boolean }) {
    const res = await request.post<{ data: TrendRunResult }>('/scoring/trend/run', body ?? {})
    return (res as { data?: TrendRunResult })?.data as TrendRunResult
  },

  async getTrendScores(params?: { tradeDate?: string; page?: number; size?: number }) {
    const res = await request.get<{ data: TrendScoresPage }>('/scoring/trend/scores', { params })
    return (res as { data?: TrendScoresPage })?.data as TrendScoresPage
  }
}
