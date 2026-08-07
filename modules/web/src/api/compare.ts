import request, { unwrap } from './request'

export interface CompareTopItem {
  rank_no: number
  stock_code: string
  exchange_type: string
  stock_name?: string
  final_score?: number
}

export interface CompareStrategySection {
  strategy: string
  factor_version?: string
  result_id: string
  returned_items: number
  top_items: CompareTopItem[]
}

export interface OverlapStock {
  stock_code: string
  exchange_type: string
  stock_name?: string
  hit_strategies: string[]
  hit_count: number
}

export interface CompareResponse {
  trade_date: string
  strategy_count: number
  strategies: CompareStrategySection[]
  overlap_stocks: OverlapStock[]
  overlap_summary: {
    unique_count: number
    overlap_count: number
    overlap_ratio: number
    verdict: string
  }
}

export async function compareStrategies(tradeDate: string, topN = 10): Promise<CompareResponse> {
  const res = await request.get<CompareResponse>('/compare/strategies', {
    params: { tradeDate, topN }
  })
  return unwrap(res)
}
