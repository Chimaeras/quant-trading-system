import request, { unwrap } from './request'

export interface PredictionItem {
  stock_code?: string
  exchange_type?: string
  stock_name?: string
  rank_no?: number
  score?: number
  score_type?: string
  track?: string
  reasons?: Record<string, unknown>
}

export interface PredictionTrack {
  prediction_run_id?: string
  trade_date?: string
  horizon?: number
  track?: string
  publish_version?: number
  status?: string
  abstain_reason?: string | null
  model_version?: string | null
  factor_version?: string | null
  item_count?: number
  quality_gate?: Record<string, unknown>
  lineage?: Record<string, unknown>
  items?: PredictionItem[]
}

export interface PredictionDailyResponse {
  trade_date?: string
  horizon?: number
  published?: boolean
  tracks?: PredictionTrack[]
  message?: string
  note?: string
  quality_gate?: Record<string, unknown>
  rule_score_run_id?: string
  probability_run_id?: string
}

export async function getDailyPredictions(params: {
  date: string
  horizon?: number
}): Promise<PredictionDailyResponse> {
  const res = await request.get<PredictionDailyResponse>('/predictions/daily', { params })
  return unwrap(res)
}

export async function generatePredictions(params: {
  date: string
  horizon?: number
}): Promise<PredictionDailyResponse> {
  const res = await request.post<PredictionDailyResponse>('/predictions/generate', null, { params })
  return unwrap(res)
}
