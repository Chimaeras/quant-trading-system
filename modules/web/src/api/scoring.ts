import request, { unwrap } from './request'

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
  /** 与后端 include_items 对应；为 false 时 items 为空，请用 GET /scores 拉列表 */
  include_items?: boolean
  items: TrendScoreRow[]
}

export interface ComprehensiveScoreRow {
  rank_no?: number
  stock_code: string
  exchange_type: string
  stock_name?: string
  trade_date: string
  total_score: number
  score_technical: number
  score_fundamental: number
  score_sentiment: number
  investment_advice: string
  factor_version?: string
}

export interface ComprehensiveRunResult {
  trade_date: string
  factor_version: string
  persisted: boolean
  scored: number
  skipped: number
  elapsed_ms: number
  include_items?: boolean
  items: ComprehensiveScoreRow[]
}

/** GET /scoring/comprehensive/scores 分页响应（对应后端 ComprehensiveScoringService.listScores） */
export interface ComprehensiveScoresPage {
  score_type: string
  factor_version: string
  trade_date: string
  list: ComprehensiveScoreRow[]
  total: number
  page: number
  size: number
}

export type UnifiedScoringStrategy =
  | 'TREND'
  | 'COMPREHENSIVE'
  | 'MULTI_DIM'
  | 'CLASSIC_SELECTOR'
  | 'OVERSOLD_REBOUND'

export interface UnifiedScoringRunRequest {
  strategy: UnifiedScoringStrategy
  topN?: number
  persist?: boolean
  [key: string]: unknown
}

export interface UnifiedScoringRunResult {
  result_id?: string
  strategy: UnifiedScoringStrategy
  factor_version?: string
  trade_date?: string
  persisted: boolean
  total_items: number
  returned_items: number
  qualified_items?: number
  min_total_score?: number
  total_scored_pool?: number
  tradability?: Record<string, unknown>
  items: Record<string, unknown>[]
  source_summary?: Record<string, unknown>
  /** QP-002：分数性质——当前为 rule_score（规则分数/截面百分位），非上涨概率 */
  score_type?: 'rule_score' | 'probability'
  score_type_note?: string
}

export interface UnifiedScoringResultItem extends Record<string, unknown> {
  rank_no?: number
  stock_code?: string
  exchange_type?: string
  stock_name?: string
  final_score?: number
  total_score?: number
}

export interface UnifiedScoringResultPage {
  result_id?: string
  strategy?: string
  factor_version?: string
  trade_date?: string
  lookback_days?: number
  top_n?: number
  min_avg_amount?: number
  min_trend_score?: number
  template_id?: string
  created_at?: string
  factor_snapshot?: Record<string, unknown>
  run_status?: string
  request_fingerprint?: string
  processed_candidates?: number
  total_candidates?: number
  success_candidates?: number
  failed_candidates?: number
  last_processed_key?: string
  min_total_score?: number
  qualified_items?: number
  returned_items?: number
  total_scored_pool?: number
  /** QP-002：分数性质——当前为 rule_score（规则分数/截面百分位），非上涨概率 */
  score_type?: 'rule_score' | 'probability'
  score_type_note?: string
  list: UnifiedScoringResultItem[]
  /** 兼容任务阶段结果接口 */
  items?: UnifiedScoringResultItem[]
  total: number
  page: number
  size: number
  total_pages?: number
}

export interface UnifiedScoringTaskRunResult {
  task_id: string
  strategy: UnifiedScoringStrategy
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
  progress_pct: number
  message?: string
  pending_ahead?: number
  running_count?: number
  request_fingerprint?: string
  resume_result_id?: string
  reused_result?: boolean
  result_id?: string
}

export interface UnifiedScoringTaskStatus {
  task_id: string
  strategy: UnifiedScoringStrategy
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
  progress_pct: number
  total_items?: number
  returned_items?: number
  result_id?: string
  message?: string
  error_message?: string
  created_at?: string
  started_at?: string
  finished_at?: string
  updated_at?: string
  pending_ahead?: number
  running_count?: number
  request_fingerprint?: string
  resume_from_result_id?: string
  reused_result?: boolean
  processed_candidates?: number
  total_candidates?: number
  success_candidates?: number
  failed_candidates?: number
  result_run_status?: string
}

export interface UnifiedScoringTaskActionResult {
  task_id: string
  ok?: boolean
  cancelled?: boolean
  cancelled_pending?: number
  cancelled_running?: number
  status?: string
  message?: string
}

export const scoringApi = {
  /** 全量打分耗时长、响应体大：默认不传明细 + 延长超时，避免 ClientAbort / axios 超时 */
  async runTrendScores(
    body?: { tradeDate?: string; biasThreshold?: number; persist?: boolean; includeItems?: boolean }
  ) {
    const res = await request.post<TrendRunResult>(
      '/scoring/trend/run',
      { includeItems: false, ...body },
      { timeout: 600000 }
    )
    return unwrap(res)
  },

  async getTrendScores(params?: { tradeDate?: string; page?: number; size?: number }) {
    const res = await request.get<TrendScoresPage>('/scoring/trend/scores', { params })
    return unwrap(res)
  },

  /** 综合三维度打分（技术面30分 + 基本面40分 + 情绪面30分） */
  async runComprehensiveScores(
    body?: { tradeDate?: string; persist?: boolean; includeItems?: boolean; lookbackDays?: number }
  ) {
    const res = await request.post<ComprehensiveRunResult>(
      '/scoring/comprehensive/run',
      { includeItems: false, persist: true, ...body },
      { timeout: 600000 }
    )
    return unwrap(res)
  },

  async getComprehensiveScores(params?: { tradeDate?: string; page?: number; size?: number }) {
    const res = await request.get<ComprehensiveScoresPage>('/scoring/comprehensive/scores', { params })
    return unwrap(res) ?? ({} as ComprehensiveScoresPage)
  },

  /** 统一打分入口：通过 strategy 选择不同打分策略并统一落库。 */
  async runUnifiedScoring(body: UnifiedScoringRunRequest) {
    const res = await request.post<UnifiedScoringRunResult>(
      '/scoring/unified/run',
      body,
      { timeout: 600000 }
    )
    return unwrap(res)
  },

  /** 统一查询：按策略获取最新结果（支持分页）。 */
  async getUnifiedLatest(params: {
    strategy: UnifiedScoringStrategy
    tradeDate?: string
    page?: number
    size?: number
  }) {
    const res = await request.get<UnifiedScoringResultPage>('/scoring/unified/result/latest', { params })
    return unwrap(res)
  },

  /** 统一查询：按 result_id 获取结果明细（支持分页）。 */
  async getUnifiedResult(
    resultId: string,
    params?: { page?: number; size?: number }
  ) {
    const res = await request.get<UnifiedScoringResultPage>(`/scoring/unified/result/${resultId}`, { params })
    return unwrap(res)
  },

  /** 异步执行统一打分任务，立即返回 task_id。 */
  async runUnifiedScoringTask(body: UnifiedScoringRunRequest) {
    const res = await request.post<UnifiedScoringTaskRunResult>(
      '/scoring/unified/tasks/run',
      body,
      { timeout: 60000 }
    )
    return unwrap(res)
  },

  /** 查询异步任务进度。 */
  async getUnifiedScoringTask(taskId: string) {
    const res = await request.get<UnifiedScoringTaskStatus>(`/scoring/unified/tasks/${taskId}`)
    return unwrap(res)
  },

  /** 取消指定任务（PENDING/RUNNING）。 */
  async cancelUnifiedScoringTask(taskId: string) {
    const res = await request.post<UnifiedScoringTaskActionResult>(`/scoring/unified/tasks/${taskId}/cancel`)
    return unwrap(res)
  },

  /** 抢占执行：取消前序任务并优先当前任务。 */
  async prioritizeUnifiedScoringTask(taskId: string) {
    const res = await request.post<UnifiedScoringTaskActionResult>(`/scoring/unified/tasks/${taskId}/prioritize`)
    return unwrap(res)
  },

  /** 一键清空待执行队列（PENDING）。 */
  async clearPendingUnifiedScoringTasks() {
    const res = await request.post<UnifiedScoringTaskActionResult>('/scoring/unified/tasks/clear-pending')
    return unwrap(res)
  },

  /** 近期打分任务列表（供「历史结果」tab）。 */
  async getRecentUnifiedTasks() {
    const res = await request.get<Array<{ task_id: string; strategy: string; status: string; result_id?: string; created_at?: string }>>('/scoring/unified/tasks/recent')
    return unwrap(res) ?? []
  }
}
