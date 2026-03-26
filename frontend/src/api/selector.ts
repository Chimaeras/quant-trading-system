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

export const selectorApi = {
  async run(params: SelectorRunParams) {
    const res = await request.post<{ data: Record<string, unknown> }>('/selector/run', params)
    return (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
  },

  async getLatest(tradeDate?: string) {
    const res = await request.get<{ data: Record<string, unknown> }>('/selector/result', { params: { tradeDate } })
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
