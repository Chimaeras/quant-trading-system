import request, { unwrap } from './request'

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
    const res = await request.post<Record<string, unknown>>('/selector/run', params)
    return unwrap(res) ?? {}
  },

  /** 超跌反弹打分（落库 factor_version = oversold_rebound_v1） */
  async runOversoldRebound(params: OversoldReboundRunParams) {
    const res = await request.post<Record<string, unknown>>('/selector/oversold-rebound/run', params)
    return unwrap(res) ?? {}
  },

  async getLatest(tradeDate?: string, factorVersion?: string) {
    const res = await request.get<Record<string, unknown>>('/selector/result', {
      params: { tradeDate, factorVersion }
    })
    return unwrap(res) ?? {}
  },

  async getDetail(resultId: string) {
    const res = await request.get<Record<string, unknown>>(`/selector/result/${resultId}`)
    return unwrap(res) ?? {}
  },

  async listTemplates() {
    const res = await request.get<Array<Record<string, unknown>>>('/selector/templates')
    return unwrap(res) ?? []
  },

  async saveTemplate(params: { templateId?: string; name: string; description?: string; params: Record<string, unknown> }) {
    const res = await request.post<Record<string, unknown>>('/selector/templates', params)
    return unwrap(res) ?? {}
  },

  async deleteTemplate(templateId: string) {
    const res = await request.delete<Record<string, unknown>>(`/selector/templates/${templateId}`)
    return unwrap(res) ?? {}
  }
}
