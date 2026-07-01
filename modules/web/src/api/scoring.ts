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
  items: Record<string, unknown>[]
  source_summary?: Record<string, unknown>
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
    const res = await request.post<{ data: TrendRunResult }>(
      '/scoring/trend/run',
      { includeItems: false, ...body },
      { timeout: 600000 }
    )
    return (res as { data?: TrendRunResult })?.data as TrendRunResult
  },

  async getTrendScores(params?: { tradeDate?: string; page?: number; size?: number }) {
    const res = await request.get<{ data: TrendScoresPage }>('/scoring/trend/scores', { params })
    return (res as { data?: TrendScoresPage })?.data as TrendScoresPage
  },

  /** 综合三维度打分（技术面30分 + 基本面40分 + 情绪面30分） */
  async runComprehensiveScores(
    body?: { tradeDate?: string; persist?: boolean; includeItems?: boolean; lookbackDays?: number }
  ) {
    const res = await request.post<{ data: ComprehensiveRunResult }>(
      '/scoring/comprehensive/run',
      { includeItems: false, persist: true, ...body },
      { timeout: 600000 }
    )
    return (res as { data?: ComprehensiveRunResult })?.data as ComprehensiveRunResult
  },

  async getComprehensiveScores(params?: { tradeDate?: string; page?: number; size?: number }) {
    const res = await request.get<{ data: Record<string, unknown> }>('/scoring/comprehensive/scores', { params })
    return (res as { data?: Record<string, unknown> })?.data ?? {}
  },

  /** 统一打分入口：通过 strategy 选择不同打分策略并统一落库。 */
  async runUnifiedScoring(body: UnifiedScoringRunRequest) {
    const res = await request.post<{ data: UnifiedScoringRunResult }>(
      '/scoring/unified/run',
      body,
      { timeout: 600000 }
    )
    return (res as { data?: UnifiedScoringRunResult })?.data as UnifiedScoringRunResult
  },

  /** 统一查询：按策略获取最新结果（支持分页）。 */
  async getUnifiedLatest(params: {
    strategy: UnifiedScoringStrategy
    tradeDate?: string
    page?: number
    size?: number
  }) {
    const res = await request.get<{ data: UnifiedScoringResultPage }>('/scoring/unified/result/latest', { params })
    return (res as { data?: UnifiedScoringResultPage })?.data as UnifiedScoringResultPage
  },

  /** 统一查询：按 result_id 获取结果明细（支持分页）。 */
  async getUnifiedResult(
    resultId: string,
    params?: { page?: number; size?: number }
  ) {
    const res = await request.get<{ data: UnifiedScoringResultPage }>(`/scoring/unified/result/${resultId}`, { params })
    return (res as { data?: UnifiedScoringResultPage })?.data as UnifiedScoringResultPage
  },

  /** 异步执行统一打分任务，立即返回 task_id。 */
  async runUnifiedScoringTask(body: UnifiedScoringRunRequest) {
    const res = await request.post<{ data: UnifiedScoringTaskRunResult }>(
      '/scoring/unified/tasks/run',
      body,
      { timeout: 60000 }
    )
    return (res as { data?: UnifiedScoringTaskRunResult })?.data as UnifiedScoringTaskRunResult
  },

  /** 查询异步任务进度。 */
  async getUnifiedScoringTask(taskId: string) {
    const res = await request.get<{ data: UnifiedScoringTaskStatus }>(`/scoring/unified/tasks/${taskId}`)
    return (res as { data?: UnifiedScoringTaskStatus })?.data as UnifiedScoringTaskStatus
  },

  /** 取消指定任务（PENDING/RUNNING）。 */
  async cancelUnifiedScoringTask(taskId: string) {
    const res = await request.post<{ data: UnifiedScoringTaskActionResult }>(`/scoring/unified/tasks/${taskId}/cancel`)
    return (res as { data?: UnifiedScoringTaskActionResult })?.data as UnifiedScoringTaskActionResult
  },

  /** 抢占执行：取消前序任务并优先当前任务。 */
  async prioritizeUnifiedScoringTask(taskId: string) {
    const res = await request.post<{ data: UnifiedScoringTaskActionResult }>(`/scoring/unified/tasks/${taskId}/prioritize`)
    return (res as { data?: UnifiedScoringTaskActionResult })?.data as UnifiedScoringTaskActionResult
  },

  /** 一键清空待执行队列（PENDING）。 */
  async clearPendingUnifiedScoringTasks() {
    const res = await request.post<{ data: UnifiedScoringTaskActionResult }>('/scoring/unified/tasks/clear-pending')
    return (res as { data?: UnifiedScoringTaskActionResult })?.data as UnifiedScoringTaskActionResult
  }
}
