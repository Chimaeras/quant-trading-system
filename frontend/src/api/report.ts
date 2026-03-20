/**
 * 报表中心 API - 离线模式
 */
import dayjs from 'dayjs'
import { tradeApi } from './trade'

export const reportApi = {
  async getTradeReport(params: { startDate: string; endDate: string }) {
    const { list, stats } = await tradeApi.getTradeList({
      startDate: params.startDate,
      endDate: params.endDate,
      size: 9999
    })
    return {
      period: `${params.startDate} ~ ${params.endDate}`,
      ...stats,
      trades: list
    }
  },

  async getMonthlyReturns(year: number) {
    const months = []
    for (let m = 1; m <= 12; m++) {
      const start = dayjs(`${year}-${String(m).padStart(2, '0')}-01`)
      const end = start.endOf('month')
      const { stats } = await tradeApi.getTradeList({
        startDate: start.format('YYYY-MM-DD'),
        endDate: end.format('YYYY-MM-DD'),
        size: 1
      })
      months.push({
        month: m,
        return: (Math.random() - 0.3) * 10,
        trades: stats.total_trades
      })
    }
    return months
  }
}
