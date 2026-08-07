import request, { unwrap } from './request'

/** 选股日报中的候选摘要 */
export interface DailyReportTopItem {
  rank_no: number
  stock_code: string
  exchange_type: string
  stock_name?: string
  final_score?: number
}

/** 行业集中度项 */
export interface IndustryConcentrationItem {
  industry: string
  count: number
  pct: number
}

/** 行业集中度聚合 */
export interface IndustryConcentration {
  total: number
  industries: IndustryConcentrationItem[]
  max_concentration_pct: number
  warning: string | null
  threshold_pct: number
}

/** 单策略信号摘要 */
export interface DailyReportStrategySection {
  strategy: string
  factor_version?: string
  trade_date: string
  run_status: string
  total_candidates?: number
  success_candidates?: number
  returned_items: number
  has_opportunity: boolean
  message: string
  top_items: DailyReportTopItem[]
  industry_concentration: IndustryConcentration
}

/** 日报整体概览 */
export interface DailyReportOverallSummary {
  strategy_count: number
  strategies_with_opportunity: number
  strategies_without_opportunity: number
  verdict: string
}

/** 预测快照 lineage（MP-022） */
export interface DailyReportPredictionHorizon {
  horizon: number
  status: string
  prediction_run_id?: string
  probability_track?: string
  tracks?: Array<{
    prediction_run_id?: string
    track?: string
    status?: string
    publish_version?: number
    abstain_reason?: string | null
    model_version?: string | null
    item_count?: number
    lineage?: Record<string, unknown>
    quality_gate?: Record<string, unknown>
  }>
  note?: string
}

export interface DailyReportPrediction {
  status: string
  horizons?: DailyReportPredictionHorizon[]
  note?: string
}

/** 选股日报响应 */
export interface DailyReportResponse {
  trade_date: string
  generated_at: string
  data_source: string
  signal_execution_note: string
  has_any_result: boolean
  overall_summary: DailyReportOverallSummary
  strategies: DailyReportStrategySection[]
  message?: string
  prediction?: DailyReportPrediction
}

/** 拉取选股日报；tradeDate 省略时取各策略最新结果 */
export async function getDailyReport(tradeDate?: string): Promise<DailyReportResponse> {
  const res = await request.get<DailyReportResponse>('/report/daily', {
    params: tradeDate ? { tradeDate } : {}
  })
  return unwrap(res)
}

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
