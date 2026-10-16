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

/** 策略目录里单个因子的权重条目。 */
export interface ScoringStrategyWeightMeta {
  factor: string
  /** 中文标签，直接用于展示 */
  label: string
  weight: number
  normalized_weight: number
  fundamental: boolean
  risk: boolean
}

/**
 * 策略目录元数据，来自后端 `GET /api/scoring/unified/strategies`。
 *
 * `legacy_ui_mode` 是前端 tab 键（classic / oversold / trend / comprehensive / multi_dim），
 * 由后端目录下发，前端据此挂载入口。
 */
export interface ScoringStrategyMeta {
  code: string
  display_name: string
  factor_version: string
  legacy_strategy_type: UnifiedScoringStrategy
  legacy_ui_mode: string | null
  min_bars: number
  min_avg_amount: number
  min_listing_days: number
  missing_data_policy: string
  min_available_weight_ratio: number
  require_ma20_above_ma60: boolean
  weights: ScoringStrategyWeightMeta[]
  required_factors: string[]
  /**
   * 非基本面因子在归一化权重中的占比。
   * 小于 1 表示该策略有部分权重依赖 PE/PB/财务快照——当前库这三者填充率为 0，
   * 因此该值就是「这个策略有多少权重是悬空的」的量化。
   */
  non_fundamental_weight_share: number
  fundamental_dependent: boolean
}

/**
 * 一次打分产出的单个策略结果块。
 *
 * `items` 里的字段名与既有 selector_result_item 兼容（final_score / trend_score /
 * momentum_score / volume_score / ret20 / ret60 / max_drawdown60），
 * 因此可直接喂给现有的 5 个表格。
 */
export interface StrategyResultBlock {
  legacy_strategy_type: UnifiedScoringStrategy
  legacy_ui_mode: string | null
  display_name: string
  factor_version: string
  ranked_count: number
  rejected_count: number
  returned_count: number
  rejection_breakdown: Record<string, number>
  normalization_mode: string
  industry_neutralization_skipped: boolean
  market_cap_neutralization_skipped: boolean
  items: Array<Record<string, unknown>>
}

/**
 * `POST /scoring/unified/engine/all-strategies` 的响应。
 *
 * `single_load` / `load_count` 是"只装载一次"的**可核对证据**：
 * 若某天 load_count 与调用次数不再对应，说明装载逻辑被改动。
 */
export interface AllStrategiesResult {
  success: boolean
  trade_date?: string
  load_count?: number
  single_load?: boolean
  message?: string
  candidate_count?: number
  universe_size?: number
  tradability_rejected?: number
  listing_days_rejected?: number
  rejection_breakdown?: Record<string, number>
  compatibility_note?: string
  persisted?: boolean
  persist_note?: string
  strategies: StrategyResultBlock[]
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

  /**
   * 一次打分，5 个策略入口全部就绪。
   *
   * 后端只装载一次行情、只算一次 L1 特征，5 个策略共享同一份特征向量；
   * 各策略各做一次横截面加权（权重不同 → 排序独立）。
   * 返回的 items 已投影为既有 selector_result_item 字段名，可直接喂给现有表格。
   *
   * **默认只读，不落库**：传 `persist: true` 才会写入既有 selector_result /
   * selector_result_item 结构（默认 false，因为"哪条运行是权威"的语义尚未确定）。
   */
  async runAllStrategies(body?: {
    tradeDate?: string
    lookbackDays?: number
    topN?: number
    excludeST?: boolean
    minAvgAmount?: number
    minListingDays?: number
    includeContributions?: boolean
    persist?: boolean
    dedupe?: boolean
  }) {
    const res = await request.post<AllStrategiesResult>(
      '/scoring/unified/engine/all-strategies',
      body ?? {},
      { timeout: 600000 }
    )
    return unwrap(res)
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

  /**
   * 策略目录（单一真相）。
   *
   * 后端 StrategyCatalog 是「有哪些策略、叫什么、对应哪个 tab」的唯一来源；
   * 前端不再自己硬编码这张映射表，避免两处各写一份后漂移。
   * 该接口只读且不查库，失败时应回退到内置目录而不是让页面空白。
   */
  async getScoringStrategies() {
    const res = await request.get<Array<ScoringStrategyMeta>>('/scoring/unified/strategies')
    return unwrap(res) ?? []
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
