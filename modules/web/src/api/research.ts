import request, { unwrap } from './request'

export interface CalibrationResponse {
  status?: string
  evaluation_kind?: string
  label_version?: string
  horizon?: number
  strategy_type?: string
  factor_version?: string
  data_snapshot_id?: string
  evaluation?: string
  sample_size?: number
  base_rate?: number
  embargo_days?: number
  baseline_source?: string
  calibrator_hash?: string
  bin_counts?: number[]
  fit_window?: { start_date?: string; end_date?: string; sample_size?: number; base_rate?: number }
  eval_window?: { start_date?: string; end_date?: string; sample_size?: number }
  sigmoid_calibration?: {
    kind?: string
    brier?: number
    log_loss?: number
    ece?: number
    brier_skill_score?: number
    params?: { a?: number; b?: number }
    evaluation_note?: string
  }
  pav_isotonic_calibration?: Record<string, unknown>
  isotonic_calibration?: Record<string, unknown>
  in_sample_reference?: Record<string, unknown>
  reliability_bins?: Array<{ bin: number; range_low: number; range_high: number; count: number; avg_predicted: number; actual_rate: number; empty?: boolean }>
  sample_lineage?: Record<string, unknown>
  message?: string
  note?: string
}

export interface LogisticTrainResponse {
  status?: string
  model_version?: string
  algorithm?: string
  algorithm_note?: string
  feature_version?: string
  output_kind?: string
  strategy_type?: string
  factor_version?: string
  data_snapshot_id?: string
  horizon?: number
  embargo_days?: number
  result_ids?: string[]
  sample_lineage?: Record<string, unknown>
  train_samples?: number
  test_samples?: number
  weights?: number[]
  bias?: number
  feature_names?: string[]
  preprocessor?: { type?: string; fit_window?: string; min?: number[]; max?: number[] }
  train_metrics?: Record<string, unknown>
  test_metrics?: Record<string, unknown>
  promote_disabled?: boolean
  message?: string
  note?: string
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
  artifact?: string
  created_at?: string
}

export interface ExperimentRow {
  experiment_id?: string
  name?: string
  status?: string
  snapshot_id?: string
  label_version?: string
  model_versions?: string
  train_start?: string
  train_end?: string
  test_start?: string
  test_end?: string
  git_commit?: string
  created_at?: string
  metrics?: string
  gates?: Record<string, unknown>
}

// 重计算接口按需放宽 per-request timeout，覆盖 request.ts 全局 10s 上限。

export async function getCalibration(params: {
  fitStart: string
  fitEnd: string
  evalStart: string
  evalEnd: string
  strategyType: string
  factorVersion?: string
  dataSnapshotId?: string
  experimentId?: string
  labelVersion?: string
  horizon?: number
}): Promise<CalibrationResponse> {
  const res = await request.get<CalibrationResponse>('/research/calibration', { params, timeout: 60000 })
  return unwrap(res)
}

export async function trainLogistic(params: {
  trainStart: string
  trainEnd: string
  testStart: string
  testEnd: string
  strategyType: string
  factorVersion?: string
  dataSnapshotId?: string
  experimentId?: string
  labelVersion?: string
  horizon?: number
  modelVersion?: string
}): Promise<LogisticTrainResponse> {
  const res = await request.post<LogisticTrainResponse>('/research/logistic/train', null, {
    params,
    timeout: 120000
  })
  return unwrap(res)
}

export async function getModelRegistry(): Promise<ModelRegistryRow[]> {
  const res = await request.get<ModelRegistryRow[]>('/research/models')
  return unwrap(res) ?? []
}

export async function registerExperiment(params: {
  name: string
  config?: string
  gitCommit?: string
  seed?: number
  snapshotId?: string
  labelVersion?: string
  modelVersions?: string
  trainStart?: string
  trainEnd?: string
  testStart?: string
  testEnd?: string
  status?: string
}): Promise<ExperimentRow> {
  const res = await request.post<ExperimentRow>('/research/experiments/register', null, { params })
  return unwrap(res)
}

export async function listExperiments(limit = 20): Promise<ExperimentRow[]> {
  const res = await request.get<ExperimentRow[]>('/research/experiments/list', { params: { limit } })
  return unwrap(res) ?? []
}

export async function getExperiment(id: string): Promise<ExperimentRow> {
  const res = await request.get<ExperimentRow>(`/research/experiments/${id}`)
  return unwrap(res)
}

export async function experimentDecision(params: {
  id: string
  action: 'CHALLENGER' | 'REJECT'
  note?: string
  actor?: string
}): Promise<Record<string, unknown>> {
  const { id, ...rest } = params
  const res = await request.post<Record<string, unknown>>(`/research/experiments/${id}/decision`, null, {
    params: rest
  })
  return unwrap(res)
}

export async function getExperimentGates(id: string): Promise<Record<string, unknown>> {
  const res = await request.get<Record<string, unknown>>(`/research/experiments/${id}/gates`)
  return unwrap(res)
}

/** 生成 T+1 次日方向标签（close-to-close）。 */
export async function buildT1Labels(params: {
  startDate: string
  endDate: string
  labelVersion?: string
}): Promise<{ status?: string; generated?: number; label_type?: string; convention?: string; message?: string }> {
  const res = await request.post<{
    status?: string
    generated?: number
    label_type?: string
    convention?: string
    message?: string
  }>('/research/labels/build-t1', null, { params, timeout: 120000 })
  return unwrap(res)
}

export interface T1CandidateItem {
  stock_code: string
  exchange_type: string
  stock_name: string
  score: number
  score_type: 'rule_score' | 'probability' | 'RULE_SCORE' | 'CALIBRATED_PROBABILITY'
  is_probability: boolean
  fallback_reason?: string | null
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
  score_type?: 'rule_score' | 'probability' | 'RULE_SCORE' | 'CALIBRATED_PROBABILITY'
  score_model?: string
  fallback_reason?: string | null
  note?: string
  found?: boolean
  // 后端 getCandidates 把核销结果嵌套在 settle 字段下（含 next_trade_date/summary/results）
  settle?: { next_trade_date?: string; summary?: T1SettleSummary; results?: T1CandidateItem[] }
}

/** 生成 T+1 次日候选：后端需拉取全市场行情逐股打分，耗时较长，放宽至 120s。 */
export async function generateT1Candidates(params: {
  tradeDate: string
  topN?: number
}): Promise<T1CandidatesResponse> {
  const res = await request.post<T1CandidatesResponse>('/research/t1/candidates', null, {
    params,
    timeout: 120000
  })
  return unwrap(res)
}

/** 次日核销：扫描次日全市场行情回填候选表现，放宽至 60s。 */
export async function settleT1Candidates(params: { tradeDate: string }): Promise<T1CandidatesResponse> {
  const res = await request.post<T1CandidatesResponse>('/research/t1/settle', null, {
    params,
    timeout: 60000
  })
  return unwrap(res)
}

export async function getT1Candidates(tradeDate: string): Promise<T1CandidatesResponse> {
  const res = await request.get<T1CandidatesResponse>(`/research/t1/candidates/${tradeDate}`)
  return unwrap(res)
}

export async function getT1Recent(): Promise<Array<{ trade_date: string; top_n: number; settled: boolean }>> {
  const res = await request.get<Array<{ trade_date: string; top_n: number; settled: boolean }>>(
    '/research/t1/recent'
  )
  return unwrap(res) ?? []
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
  daily_series?: Array<{
    trade_date: string
    settled: number
    hit_count: number
    hit_rate: number
    avg_next_ret: number
  }>
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

export async function getT1Stats(params: { startDate: string; endDate: string }): Promise<T1StatsResponse> {
  const res = await request.get<T1StatsResponse>('/research/t1/stats', { params, timeout: 60000 })
  return unwrap(res)
}
