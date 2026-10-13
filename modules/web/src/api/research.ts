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
  persisted_calibrator_version?: string
  persisted_calibrator_hash?: string
  base_model_version?: string
  calibrator_code_version?: string
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
  strategy_type?: string
  feature_version?: string
  feature_set_version?: string
  label_version?: string
  horizon?: number
  train_start?: string
  train_end?: string
  test_start?: string
  test_end?: string
  stage?: string
  metrics?: string
  data_snapshot_id?: string
  schema_hash?: string
  code_version?: string
  artifact_hash?: string
  output_kind?: string
  calibrator_hash?: string
  deploy_key?: string
  import_status?: string
  has_artifact?: boolean
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
  dataSnapshotId: string
  experimentId?: string
  baseModelVersion?: string
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
  dataSnapshotId: string
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

export interface CalibratorRow {
  calibrator_version?: string
  strategy_type?: string
  horizon?: number
  factor_version?: string
  label_version?: string
  data_snapshot_id?: string
  output_kind?: string
  input_domain?: string
  base_model_version?: string
  base_output_kind?: string
  calibrator_hash?: string
  code_version?: string
  fit_start?: string
  fit_end?: string
  eval_start?: string
  eval_end?: string
  stage?: string
  has_calibrator_json?: boolean
  created_at?: string
}

export async function getCalibrators(): Promise<CalibratorRow[]> {
  const res = await request.get<CalibratorRow[]>('/research/calibrators')
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

export async function getExperimentGates(id: string, modelVersion?: string): Promise<Record<string, unknown>> {
  const res = await request.get<Record<string, unknown>>(`/research/experiments/${id}/gates`, {
    params: modelVersion ? { modelVersion } : undefined
  })
  return unwrap(res)
}

// V2-MP-018：人工 champion 晋级 / 回滚（原子事务 + 双人可审计；硬门禁 FAIL 或并发冲突 → HTTP 409）。
export async function promoteExperiment(params: {
  id: string
  modelVersion: string
  actor?: string
  confirmActor?: string
  note?: string
}): Promise<Record<string, unknown>> {
  const { id, ...rest } = params
  const res = await request.post<Record<string, unknown>>(`/research/experiments/${id}/promote`, null, {
    params: rest,
    timeout: 30000
  })
  return unwrap(res)
}

export async function rollbackExperiment(params: {
  id: string
  modelVersion?: string
  actor?: string
  confirmActor?: string
  note?: string
}): Promise<Record<string, unknown>> {
  const { id, ...rest } = params
  const res = await request.post<Record<string, unknown>>(`/research/experiments/${id}/rollback`, null, {
    params: rest,
    timeout: 30000
  })
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
  // ===== D-T1-03 可追溯性字段（生成与按日回溯都会返回）=====
  /** 实际使用的数据日（可能与 requested_trade_date 不同） */
  requested_trade_date?: string
  data_cutoff?: string
  /** true 表示请求日无行情、已回退到 trade_date */
  data_date_fallback?: boolean
  generated_at?: string
  threshold_version?: string
  rule_score_threshold?: number
  rule_score_max?: number
  eligible_count?: number
  returned_count?: number
  rejected_count?: number
  /** 当日打分池中的最高 rule_score：用于判断「完全没有信号」还是「就差一点」 */
  max_rule_score?: number
  rejected_by_reason?: Record<string, number>
  status?: string
  /** 本清单的有效期/失效条件说明 */
  candidate_invalidation?: string
  /** true 表示股票池来自当前截面回退，存在幸存者偏差 */
  pit_limited?: boolean
  master_reliability?: string
  universe_as_of?: string
  universe_size?: number
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

// ── 研究任务工作台（V2-MP-019）───────────────────────────────────────────────
// 契约来自 ResearchTaskController（MP-008）：
//   * 创建 POST /research/tasks → 202 Accepted + taskId（requestId 幂等去重）；
//   * 列表 GET /research/tasks?limit=、详情 GET /research/tasks/{taskId}（404 语义）；
//   * 取消/恢复 POST /research/tasks/{taskId}/cancel|resume。
// 任务状态机：PENDING → RUNNING → COMPLETED|FAILED|CANCELLED；启动恢复后 RUNNING 变
// RECOVERY_REQUIRED。published 仅由后端业务产物决定，前端绝不把 progress=100 判为发布。

export type ResearchTaskType = 'REPLAY' | 'REPLAY_SIGNALS' | 'LABELS' | 'CALIBRATION' | 'LOGISTIC'

export interface ResearchTask {
  task_id?: string
  type?: ResearchTaskType | string
  status?: string
  progress?: number
  published?: boolean
  error_message?: string
  error_code?: string
  request_id?: string
  cancel_requested?: boolean
  version?: number
  staging_namespace?: string
  checkpoint?: string
  params_json?: string
  created_at?: string
  updated_at?: string
  heartbeat_at?: string
  result?: Record<string, unknown>
  /** 幂等命中时后端回填 */
  message?: string
  reused?: boolean
}

/** 创建研究异步任务：返回 202 Accepted + taskId（契约见 ResearchTaskController）。 */
export async function createResearchTask(body: {
  type: ResearchTaskType | string
  requestId?: string
  params?: Record<string, unknown>
}): Promise<ResearchTask> {
  const payload: Record<string, unknown> = { type: body.type }
  if (body.requestId) payload.requestId = body.requestId
  if (body.params && Object.keys(body.params).length > 0) payload.params = body.params
  const res = await request.post<ResearchTask>('/research/tasks', payload, { timeout: 30000 })
  return unwrap(res)
}

/** 任务详情：轮询/重进恢复用。404 由后端抛 ResourceNotFoundException。 */
export async function getResearchTask(taskId: string): Promise<ResearchTask> {
  const res = await request.get<ResearchTask>(`/research/tasks/${taskId}`)
  return unwrap(res)
}

/** 最近任务列表（页面重进恢复的入口）。 */
export async function listResearchTasks(limit = 20): Promise<ResearchTask[]> {
  const res = await request.get<ResearchTask[]>('/research/tasks', { params: { limit } })
  return unwrap(res) ?? []
}

/** 协作式取消：RUNNING 先置 cancelRequested，worker 发布前看到即停止；终态任务返回 409。 */
export async function cancelResearchTask(taskId: string): Promise<ResearchTask> {
  const res = await request.post<ResearchTask>(`/research/tasks/${taskId}/cancel`)
  return unwrap(res)
}

/** 恢复：FAILED / CANCELLED / RECOVERY_REQUIRED → PENDING 重新排队（进度/产物重置）。 */
export async function resumeResearchTask(taskId: string): Promise<ResearchTask> {
  const res = await request.post<ResearchTask>(`/research/tasks/${taskId}/resume`)
  return unwrap(res)
}

// ── 滚动 purged Walk-Forward（V2-MP-023）────────────────────────────────────
// 独立于旧 WalkForwardService 的 SEGMENT_STABILITY（非样本外）。每窗 train→valid→
// purge/embargo→test 冻结 OOS；仅研究用，evaluation_kind=rolling_wfo_oos，不参与晋级 gate。
// 契约来自 RollingExperimentController：POST /research/rolling-experiment/run|resume、
// GET /list、GET /{experimentId}。窗口参数不可变，逐窗落库可中断恢复。

export type RollingWindowStatus = 'OK' | 'INSUFFICIENT_DATA' | 'FAILED'

export interface RollingWindowPlan {
  window_no: number
  train_start: string
  train_end: string
  valid_start: string
  valid_end: string
  test_start: string
  test_end: string
}

export interface RollingWindowResult {
  window_no?: number
  status?: RollingWindowStatus
  train_start?: string
  train_end?: string
  valid_start?: string
  valid_end?: string
  test_start?: string
  test_end?: string
  model_version?: string
  artifact_hash?: string
  calibrator_version?: string
  calibrator_hash?: string
  evaluation_kind?: string
  oos_samples?: number
  error_message?: string
  metrics?: Record<string, unknown>
}

export interface RollingExperimentSummary {
  evaluation_kind?: string
  windows_total?: number
  windows_ok?: number
  windows_insufficient?: number
  windows_failed?: number
  oos_windows?: number
  pooled_oos_samples?: number
  pooled_sigmoid_brier?: number | null
  pooled_pav_brier?: number | null
  mean_sigmoid_ece?: number | null
  mean_pav_ece?: number | null
  pooled_auc?: number | null
  coverage_pct?: number | null
  coverage_note?: string
  turnover?: string
  turnover_note?: string
  pooling_note?: string
  per_window?: Array<Record<string, unknown>>
}

export interface RollingExperimentRow {
  experiment_id?: string
  evaluation_kind?: string
  status?: string
  strategy_type?: string
  label_version?: string
  horizon?: number
  window_mode?: string
  windows_total?: number
  start_date?: string
  end_date?: string
  created_at?: string
  summary?: RollingExperimentSummary
}

export interface RollingExperimentRunResponse {
  experiment_id?: string
  evaluation_kind?: string
  status?: string
  resumed?: boolean
  mode?: string
  strategy_type?: string
  snapshot_id?: string
  label_version?: string
  feature_version?: string
  horizon?: number
  windows_total?: number
  window_schedule?: RollingWindowPlan[]
  summary?: RollingExperimentSummary
  windows?: RollingWindowResult[]
  note?: string
}

/** 运行（不带 experimentId）或幂等恢复（带 experimentId，参数须一致）。 */
export async function runRollingExperiment(body: {
  strategyType: string
  snapshotId?: string
  labelVersion: string
  featureVersion?: string
  horizon: number
  startDate: string
  endDate: string
  mode?: 'EXPANDING' | 'ROLLING'
  trainDays: number
  validDays?: number
  testDays: number
  embargoDays?: number
  stepDays?: number
  experimentId?: string
}): Promise<RollingExperimentRunResponse> {
  const res = await request.post<RollingExperimentRunResponse>('/research/rolling-experiment/run', body, {
    timeout: 180000
  })
  return unwrap(res)
}

export async function resumeRollingExperiment(experimentId: string): Promise<RollingExperimentRunResponse> {
  const res = await request.post<RollingExperimentRunResponse>(
    `/research/rolling-experiment/${experimentId}/resume`,
    null,
    { timeout: 180000 }
  )
  return unwrap(res)
}

export async function listRollingExperiments(): Promise<RollingExperimentRow[]> {
  const res = await request.get<RollingExperimentRow[]>('/research/rolling-experiment/list')
  return unwrap(res) ?? []
}

export async function getRollingExperiment(experimentId: string): Promise<RollingExperimentRunResponse> {
  const res = await request.get<RollingExperimentRunResponse>(`/research/rolling-experiment/${experimentId}`)
  return unwrap(res)
}
