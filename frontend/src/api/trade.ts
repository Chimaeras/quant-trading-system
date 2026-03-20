/**
 * 交易管理 API - 离线模式
 */
import { getDB } from '@/utils/db'
import { generateMockTrades } from '@/utils/mock'
import type { TradeRecord, PositionInfo } from '@/types'

export const tradeApi = {
  async getTradeList(params: { page?: number; size?: number; startDate?: string; endDate?: string }) {
    const db = await getDB()
    let trades = await db.getAll('trades')
    if (trades.length === 0) {
      const mock = generateMockTrades(50)
      for (const t of mock) {
        await db.add('trades', t)
      }
      trades = mock
    }
    const { page = 1, size = 20, startDate, endDate } = params
    let list = trades as unknown as TradeRecord[]
    if (startDate) {
      list = list.filter(t => t.trade_time >= startDate)
    }
    if (endDate) {
      list = list.filter(t => t.trade_time <= endDate)
    }
    list.sort((a, b) => (b.trade_time || '').localeCompare(a.trade_time || ''))
    const total = list.length
    const start = (page - 1) * size
    return {
      list: list.slice(start, start + size),
      total,
      stats: {
        total_trades: total,
        win_count: list.filter(t => (t.profit_loss || 0) > 0).length,
        loss_count: list.filter(t => (t.profit_loss || 0) < 0).length,
        total_profit: list.reduce((s, t) => s + (t.profit_loss || 0), 0)
      }
    }
  },

  async getPositions(): Promise<PositionInfo[]> {
    const db = await getDB()
    const list = await db.getAll('positions')
    if (list.length === 0) {
      return [
        { stock_code: '000001', stock_name: '平安银行', volume: 1000, avg_cost: 10.5, current_price: 11.2, market_value: 11200, profit_loss: 700, profit_rate: 6.67 },
        { stock_code: '600000', stock_name: '浦发银行', volume: 2000, avg_cost: 8.2, current_price: 8.5, market_value: 17000, profit_loss: 600, profit_rate: 3.66 }
      ]
    }
    return list as unknown as PositionInfo[]
  }
}
