import request, { unwrap } from './request'

/**
 * 选股模板 API。
 *
 * 选股的「运行 / 查看结果」已迁移到统一打分异步任务 API（scoring.ts 的
 * runUnifiedScoringTask / getUnifiedScoringTask / getUnifiedLatest / getUnifiedResult，
 * 即 /scoring/unified/*）。此处不再保留旧的同步 /selector/run、/selector/oversold-rebound/run、
 * /selector/result、/selector/result/{resultId} 封装——迁移后已无任何调用方。
 */
export const selectorApi = {
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
