import request from './request'

export interface WindowStat {
  window_no: number
  start_date: string
  end_date: string
  signal_days: number
  avg_return: number
  win_rate: number
  observations: number
}

export interface WalkForwardResponse {
  strategy: string
  start_date: string
  end_date: string
  windows: number
  top_n: number
  holding_day: number
  window_stats: WindowStat[]
  note: string
}

export function runWalkForward(params: {
  strategy: string
  startDate: string
  endDate: string
  windows?: number
  topN?: number
  holdingDay?: number
}): Promise<WalkForwardResponse> {
  return request.get('/api/backtest/walk-forward', {
    params: {
      strategy: params.strategy,
      startDate: params.startDate,
      endDate: params.endDate,
      windows: params.windows,
      topN: params.topN,
      holdingDay: params.holdingDay
    }
  })
}
