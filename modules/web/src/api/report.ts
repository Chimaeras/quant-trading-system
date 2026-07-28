import request from './request'

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
}

/** 拉取选股日报；tradeDate 省略时取各策略最新结果 */
export function getDailyReport(tradeDate?: string): Promise<DailyReportResponse> {
  return request.get('/api/report/daily', { params: tradeDate ? { tradeDate } : {} })
}
