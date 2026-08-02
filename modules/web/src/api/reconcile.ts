import request from './request'

export interface ReconcileStock {
  stock_code: string
  exchange_type: string
  stock_name?: string
  rank_no?: number
  final_score?: number
  volume?: number
  available_volume?: number
  avg_cost?: number
  current_price?: number
  profit_rate?: number
}

export interface ReconcileResponse {
  user_id: number
  account_id: number
  result_id: string
  strategy: string
  trade_date: string
  top_n: number
  target_count: number
  actual_count: number
  to_buy: ReconcileStock[]
  to_sell: ReconcileStock[]
  held_in_candidates: ReconcileStock[]
  note: string
}

/** 持仓与选股信号对账 */
export function reconcilePosition(
  resultId: string,
  topN = 10,
  userId?: number
): Promise<ReconcileResponse> {
  return request.get('/reconcile/position', {
    params: { resultId, topN, ...(userId ? { userId } : {}) }
  })
}
