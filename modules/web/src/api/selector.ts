import request from './request'

export interface SelectorRunParams {
  tradeDate?: string
  lookbackDays?: number
  topN?: number
  minAvgAmount?: number
  minTrendScore?: number
  templateId?: string
  factorVersion?: string
}

/** 与后端 OversoldReboundScoringService.FACTOR_VERSION 一致 */
export const OVERSOLD_REBOUND_FACTOR_VERSION = 'oversold_rebound_v1'

/** 与后端 GeopolicyNewsScoringService.FACTOR_VERSION 一致 */
export const GEOPOLICY_NEWS_FACTOR_VERSION = 'geopolicy_news_v1'

export interface OversoldReboundRunParams {
  tradeDate?: string
  topN?: number
  minTotalScore?: number
  excludeST?: boolean
  templateId?: string
  /** 是否写入 selector_result（默认 true） */
  persist?: boolean
}

export const selectorApi = {
  async run(params: SelectorRunParams) {
    const res = await request.post<{ data: Record<string, unknown> }>('/selector/run', params)
    return (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
  },

  /** 超跌反弹打分（落库 factor_version = oversold_rebound_v1） */
  async runOversoldRebound(params: OversoldReboundRunParams) {
    const res = await request.post<{ data: Record<string, unknown> }>('/selector/oversold-rebound/run', params)
    return (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
  },

  /** 政策/地缘政治/新闻(GDELT) 因子选股（落库 factor_version = geopolicy_news_v1） */
  async runGeopolicyNews(params: Record<string, unknown>) {
    // 该接口可能会进行全市场扫描与外部数据拉取，默认 10s 容易超时；这里单独拉长超时
    const res = await request.post<{ data: Record<string, unknown> }>(
      '/selector/geopolicy-news/run',
      params,
      { timeout: 600000 }
    )
    return (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
  },

  async getLatest(tradeDate?: string, factorVersion?: string) {
    const res = await request.get<{ data: Record<string, unknown> }>('/selector/result', {
      params: { tradeDate, factorVersion }
    })
    return (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
  },

  async getDetail(resultId: string) {
    const res = await request.get<{ data: Record<string, unknown> }>(`/selector/result/${resultId}`)
    return (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
  },

  async listTemplates() {
    const res = await request.get<{ data: Array<Record<string, unknown>> }>('/selector/templates')
    return (res as { data?: Array<Record<string, unknown>> })?.data ?? []
  },

  async saveTemplate(params: { templateId?: string; name: string; description?: string; params: Record<string, unknown> }) {
    const res = await request.post<{ data: Record<string, unknown> }>('/selector/templates', params)
    return (res as { data?: Record<string, unknown> })?.data ?? {}
  },

  async deleteTemplate(templateId: string) {
    const res = await request.delete<{ data: Record<string, unknown> }>(`/selector/templates/${templateId}`)
    return (res as { data?: Record<string, unknown> })?.data ?? {}
  }
}
