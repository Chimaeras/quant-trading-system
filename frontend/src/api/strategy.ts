/**
 * 策略中心 API - 离线模式
 */
import { getDB } from '@/utils/db'
import { generateMockStrategies } from '@/utils/mock'
import type { StrategyInfo, BacktestResult } from '@/types'

export const strategyApi = {
  async getStrategyList(): Promise<StrategyInfo[]> {
    const db = await getDB()
    const list = await db.getAll('strategies')
    if (list.length === 0) {
      const mock = generateMockStrategies()
      for (const s of mock) {
        await db.add('strategies', s)
      }
      return mock as unknown as StrategyInfo[]
    }
    return list as unknown as StrategyInfo[]
  },

  async getStrategy(id: string | number): Promise<StrategyInfo | null> {
    const db = await getDB()
    const s = await db.get('strategies', Number(id))
    return (s as unknown as StrategyInfo) || null
  },

  async save(strategy: Partial<StrategyInfo & { code?: string; content?: string; params?: Record<string, unknown> }>) {
    const db = await getDB()
    const id = await db.add('strategies', strategy)
    return { id }
  },

  async saveStrategy(strategy: { id?: string; name: string; language: string; content: string }) {
    const db = await getDB()
    const data = { name: strategy.name, strategy_name: strategy.name, language: strategy.language, content: strategy.content }
    if (strategy.id) {
      await db.put('strategies', { ...data, id: Number(strategy.id) })
      return { id: strategy.id }
    }
    const id = await db.add('strategies', data)
    return { id }
  },

  async runBacktest(params: {
    strategy_id?: string
    strategyId?: number
    stock_code?: string
    stockCode?: string
    start_date?: string
    startDate?: string
    end_date?: string
    endDate?: string
    initialCapital?: number
  }): Promise<BacktestResult> {
    const startDate = params.start_date ?? params.startDate ?? '2023-01-01'
    void (params.end_date ?? params.endDate ?? '2024-12-31')
    const initialCapital = params.initialCapital ?? 1000000
    const days = 250
    let equity = initialCapital
    const curve = []
    const start = new Date(startDate).getTime()
    for (let i = 0; i < days; i++) {
      const date = new Date(start + i * 86400000).toISOString().slice(0, 10)
      equity *= 1 + (Math.random() - 0.48) * 0.01
      curve.push({ date, equity: Math.round(equity) })
    }
    return {
      total_return: ((equity - initialCapital) / initialCapital) * 100,
      annual_return: 25.6,
      sharpe_ratio: 1.85,
      max_drawdown: -12.5,
      total_trades: 42,
      win_rate: 58.3,
      equity_curve: curve
    }
  }
}
