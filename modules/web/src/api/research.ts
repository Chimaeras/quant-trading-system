import request from './request'

export interface CalibrationResponse {
  status?: string
  label_version?: string
  horizon?: number
  evaluation?: string
  sample_size?: number
  base_rate?: number
  fit_window?: { start_date?: string; end_date?: string; sample_size?: number }
  eval_window?: { start_date?: string; end_date?: string; sample_size?: number }
  sigmoid_calibration?: {
    brier?: number
    log_loss?: number
    ece?: number
    brier_skill_score?: number
    params?: { a?: number; b?: number }
    evaluation_note?: string
  }
  isotonic_calibration?: Record<string, unknown>
  in_sample_reference?: { brier?: number; log_loss?: number; ece?: number }
  reliability_bins?: Array<{ bin: number; range_low: number; range_high: number; count: number; avg_predicted: number; actual_rate: number }>
  message?: string
}

export interface LogisticTrainResponse {
  status?: string
  model_version?: string
  algorithm?: string
  horizon?: number
  train_samples?: number
  test_samples?: number
  weights?: number[]
  bias?: number
  feature_names?: string[]
  preprocessor?: { type?: string; fit_window?: string; min?: number[]; max?: number[] }
  train_metrics?: Record<string, unknown>
  test_metrics?: Record<string, unknown>
  message?: string
}

export interface ModelRegistryRow {
  model_version?: string
  algorithm?: string
  feature_version?: string
  label_version?: string
  horizon?: number
  train_start?: string
  train_end?: string
  test_start?: string
  test_end?: string
  stage?: string
  metrics?: string
  created_at?: string
}

export function getCalibration(params: {
  startDate: string
  endDate: string
  labelVersion?: string
  horizon?: number
}): Promise<CalibrationResponse> {
  return request.get('/research/calibration', { params })
}

export function trainLogistic(params: {
  trainStart: string
  trainEnd: string
  testStart: string
  testEnd: string
  labelVersion?: string
  horizon?: number
  modelVersion?: string
}): Promise<LogisticTrainResponse> {
  return request.post('/research/logistic/train', null, { params })
}

export function getModelRegistry(): Promise<ModelRegistryRow[]> {
  return request.get('/research/models')
}

/** 生成 T+1 次日方向标签（close-to-close）。 */
export function buildT1Labels(params: {
  startDate: string
  endDate: string
  labelVersion?: string
}): Promise<{ status?: string; generated?: number; label_type?: string; convention?: string; message?: string }> {
  return request.post('/research/labels/build-t1', null, { params })
}

export interface T1CandidateItem {
  stock_code: string
  exchange_type: string
  stock_name: string
  score: number
  score_type: 'rule_score' | 'probability'
  is_probability: boolean
  close?: number
  change_pct?: number
  is_limit_up?: boolean
  lianban_days?: number
  volume_ratio?: number
  turnover_rate?: number
  ret1?: number
  ret5?: number
  ma_pos?: number
  reasons?: string[]
  buyability?: '可买' | '高开追高谨慎' | '大概率买不进'
  risk_tags?: string[]
  next_ret?: number | null
  next_high_ret?: number | null
  gap?: number | null
  next_close?: number
  next_is_limit_up?: boolean
  hit?: boolean
  settled?: boolean
  skip?: string
}

export interface T1SettleSummary {
  top_n: number
  settled: number
  hit_count: number
  hit_rate: number
  avg_next_ret: number
  avg_gap: number
  high_open_count: number
}

export interface T1CandidatesResponse {
  trade_date: string
  top_n: number
  total_scored?: number
  candidates: T1CandidateItem[]
  settled?: boolean
  ecology?: Record<string, unknown>
  summary?: T1SettleSummary
  results?: T1CandidateItem[]
  score_type?: 'rule_score' | 'probability'
  score_model?: string
  note?: string
  found?: boolean
}

export function generateT1Candidates(params: { tradeDate: string; topN?: number }): Promise<T1CandidatesResponse> {
  return request.post('/research/t1/candidates', null, { params })
}

export function settleT1Candidates(params: { tradeDate: string }): Promise<T1CandidatesResponse> {
  return request.post('/research/t1/settle', null, { params })
}

export function getT1Candidates(tradeDate: string): Promise<T1CandidatesResponse> {
  return request.get(`/research/t1/candidates/${tradeDate}`)
}

export function getT1Recent(): Promise<Array<{ trade_date: string; top_n: number; settled: boolean }>> {
  return request.get('/research/t1/recent')
}

export interface T1StatsResponse {
  start_date: string
  end_date: string
  days: number
  total_candidates?: number
  total_settled?: number
  hit_count?: number
  hit_rate?: number
  avg_next_ret?: number
  avg_gap?: number
  daily_series?: Array<{ trade_date: string; settled: number; hit_count: number; hit_rate: number; avg_next_ret: number }>
  benchmark?: {
    available: boolean
    index_code?: string
    index_name?: string
    index_return?: number
    index_avg_daily_return?: number
    outperform?: boolean
  }
  note?: string
}

export function getT1Stats(params: { startDate: string; endDate: string }): Promise<T1StatsResponse> {
  return request.get('/research/t1/stats', { params })
}
