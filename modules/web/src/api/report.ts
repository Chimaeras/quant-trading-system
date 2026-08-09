import request, { unwrap } from './request'

// ── 选股日报（V2-MP-012：prediction snapshot 不可变投影）────────────────────
// 契约来自 DailyReportController：GET /report/daily?tradeDate&horizon&track&publishVersion、
// GET /report/daily/versions、POST /report/daily/realize。无 PUBLISHED run 时报
// status=ABSTAIN|NOT_PUBLISHED，绝不回退昨日 selector。

/** 日报候选（= prediction_snapshot_item，不可变） */
export interface DailyReportItem {
  rank_no?: number
  stock_code: string
  exchange_type: string
  stock_name?: string
  score?: number
  score_type?: string
  reasons?: Record<string, unknown>
}

/** 固定 lineage（来自 prediction run，不含候选正文） */
export interface DailyReportLineage {
  convention_version?: string
  gate_status?: string
  gate_decision_id?: string
  snapshot_id?: string
  factor_version?: string
  strategy_type?: string
  model_version?: string
  output_kind?: string
  schema_hash?: string
  deploy_key?: string
  calibrator_version?: string
  calibrator_hash?: string
  inference_chain?: string
  source_result_id?: string
  source_trade_date?: string
}

/** 行业分布项 */
export interface IndustryConcentrationItem {
  industry: string
  count: number
  pct: number
}

/** 行业分布聚合（provenance=CURRENT_STOCK_INFO，MP-017 未落地前非 PIT） */
export interface IndustryConcentration {
  provenance?: string
  pit_note?: string
  total: number
  industries: IndustryConcentrationItem[]
  max_concentration_pct: number
  warning: string | null
  threshold_pct: number
}

/** realization 单标的 */
export interface RealizationStock {
  rank_no?: number
  stock_code: string
  exchange_type: string
  stock_name?: string
  entry_close?: number | null
  exit_close?: number | null
  raw_forward_return?: number | null
  label_exec_status?: string
  label_net_return?: number | null
  realization_status?: string
}

/** 到期后追加的 realization（追加不修改原候选） */
export interface Realization {
  status?: string
  signal_date?: string
  maturity_date?: string
  horizon?: number
  label_version?: string
  label_join_note?: string
  per_stock?: RealizationStock[]
  counts?: { filled?: number; raw_close_only?: number; unavailable?: number; total?: number }
  note?: string
  /** 幂等命中时后端回填 */
  reused?: boolean
  message?: string
}

/** 某轨道可发布版本（版本选择器） */
export interface DailyReportVersion {
  publish_version?: number
  status?: string
  abstain_reason?: string | null
  item_count?: number
  prediction_run_id?: string
  model_version?: string | null
}

/** 日报响应：status=PUBLISHED 含候选投影；ABSTAIN/NOT_PUBLISHED 仅 abstain 说明 */
export interface DailyReportResponse {
  status?: 'PUBLISHED' | 'ABSTAIN' | 'NOT_PUBLISHED'
  trade_date?: string | null
  horizon?: number
  track?: string
  publish_version?: number | null
  report_id?: string
  report_version?: number
  prediction_run_id?: string
  source_result_id?: string | null
  factor_version?: string | null
  content_hash?: string
  item_count?: number
  generated_at?: string
  abstain_reason?: string | null
  lineage?: DailyReportLineage
  items?: DailyReportItem[]
  industry_concentration?: IndustryConcentration
  realization?: Realization | null
  note?: string
}

/** 拉取日报投影；tradeDate 省略取最新日期，publishVersion 省略取该轨道最新 */
export async function getDailyReport(params: {
  tradeDate?: string
  horizon?: number
  track?: string
  publishVersion?: number
}): Promise<DailyReportResponse> {
  const res = await request.get<DailyReportResponse>('/report/daily', { params })
  return unwrap(res)
}

/** 某日某轨道全部可发布版本（版本选择器） */
export async function getDailyReportVersions(params: {
  tradeDate?: string
  horizon: number
  track: string
}): Promise<{ trade_date?: string; horizon?: number; track?: string; versions?: DailyReportVersion[] }> {
  const res = await request.get<{ trade_date?: string; horizon?: number; track?: string; versions?: DailyReportVersion[] }>(
    '/report/daily/versions',
    { params }
  )
  return unwrap(res)
}

/** 到期后追加 realization（幂等：已落库返回既有结果） */
export async function realizeDailyReport(params: {
  tradeDate?: string
  horizon: number
  track: string
  publishVersion?: number
}): Promise<Realization> {
  const res = await request.post<Realization>('/report/daily/realize', null, { params, timeout: 30000 })
  return unwrap(res)
}

// ── 多日信号趋势（独立的 selector 视角视图，不参与 projection）──────────────

/** 多日趋势单日 */
export interface TrendDay {
  trade_date: string
  strategies_with_opportunity: number
  total_candidates: number
  per_strategy: Record<string, number>
}

/** 多日趋势响应 */
export interface TrendReportResponse {
  start_date: string
  end_date: string
  strategies: string[]
  signal_days: number
  daily_trend: TrendDay[]
  note: string
}

/** 拉取多日信号趋势 */
export async function getTrendReport(startDate: string, endDate: string): Promise<TrendReportResponse> {
  const res = await request.get<TrendReportResponse>('/report/trend', {
    params: { startDate, endDate }
  })
  return unwrap(res)
}
