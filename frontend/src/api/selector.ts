import request from './request'

export interface SelectorRunParams {
  tradeDate?: string
  lookbackDays?: number
  topN?: number
  minAvgAmount?: number
  minTrendScore?: number
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
  }
}
