import request, { unwrap } from './request'

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
  /** 分析类型：当前实现仅为分段稳定性，非样本外 WFO */
  analysis_kind?: string
  is_oos?: boolean
}

export async function runWalkForward(params: {
  strategy: string
  startDate: string
  endDate: string
  windows?: number
  topN?: number
  holdingDay?: number
}): Promise<WalkForwardResponse> {
  const res = await request.get<WalkForwardResponse>('/backtest/walk-forward', {
    params: {
      strategy: params.strategy,
      startDate: params.startDate,
      endDate: params.endDate,
      windows: params.windows,
      topN: params.topN,
      holdingDay: params.holdingDay
    }
  })
  return unwrap(res)
}
