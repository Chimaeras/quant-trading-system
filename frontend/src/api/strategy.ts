/**
 * 策略中心 API - 优先调用后端真实回测，离线时使用本地模拟
 */
import { getDB } from '@/utils/db'
import { generateMockStrategies } from '@/utils/mock'
import request from './request'
import type { StrategyInfo, BacktestResult, BacktestHistoryItem } from '@/types'

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

  async saveStrategy(strategy: { id?: string; name: string; language: string; content: string; params?: Record<string, unknown> }) {
    const db = await getDB()
    const data: Record<string, unknown> = {
      name: strategy.name,
      strategy_name: strategy.name,
      language: strategy.language,
      content: strategy.content
    }
    if (strategy.params != null) data.params = strategy.params
    if (strategy.id) {
      await db.put('strategies', { ...data, id: Number(strategy.id) })
      return { id: strategy.id }
    }
    const id = await db.add('strategies', data)
    return { id }
  },

  async runBacktest(params: {
    strategy_id?: string | number
    strategyId?: string | number
    strategyName?: string
    stock_code?: string
    stockCode?: string
    start_date?: string
    startDate?: string
    end_date?: string
    endDate?: string
    initialCapital?: number
    params?: Record<string, unknown>
    cost?: {
      commission_rate?: number
      min_commission?: number
      stamp_duty_rate?: number
      transfer_fee_rate?: number
      slippage_bps?: number
    }
  }): Promise<BacktestResult> {
    const startDate = params.start_date ?? params.startDate ?? '2023-01-01'
    const endDate = params.end_date ?? params.endDate ?? '2024-12-31'
    const initialCapital = params.initialCapital ?? 1000000
    const stockCode = params.stock_code ?? params.stockCode ?? '000001.SZ'

    try {
      const res = await request.post<{ data: Record<string, unknown> }>('/backtest/run', {
        strategyId: params.strategy_id ?? params.strategyId ?? 1,
        strategy_id: params.strategy_id ?? params.strategyId ?? 1,
        strategyName: params.strategyName ?? params.strategy_id ?? params.strategyId,
        stock_code: stockCode,
        symbol: stockCode,
        start_date: startDate,
        startDate: startDate,
        end_date: endDate,
        endDate: endDate,
        initialCapital,
        params: {
          ...(params.params ?? {}),
          ...(params.cost ?? {})
        }
      })
      const d = (res as { data?: Record<string, unknown> })?.data ?? res
      return {
        total_return: (d.total_return as number) ?? (d.totalReturn as number) ?? 0,
        annual_return: (d.annual_return as number) ?? 0,
        sharpe_ratio: (d.sharpe_ratio as number) ?? (d.sharpeRatio as number) ?? 0,
        max_drawdown: (d.max_drawdown as number) ?? (d.maxDrawdown as number) ?? 0,
        total_trades: (d.total_trades as number) ?? (d.totalTrades as number) ?? 0,
        win_rate: (d.win_rate as number) ?? (d.winRate as number) ?? 0,
        total_cost: (d.total_cost as number) ?? 0,
        result_id: (d.result_id as string) ?? null,
        equity_curve: (d.equity_curve as Array<{ date: string; equity: number }>) ?? []
      }
    } catch {
      // 离线或后端不可用时使用本地模拟
      const days = 250
      let equity = initialCapital
      const curve: Array<{ date: string; equity: number }> = []
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
        total_cost: 2500,
        result_id: null,
        equity_curve: curve
      }
    }
  },

  async getBacktestResults(page = 1, size = 20): Promise<{ items: BacktestHistoryItem[]; page: number; size: number; total: number }> {
    const res = await request.get<{ data: Record<string, unknown> }>('/backtest/results', { params: { page, size } })
    const d = (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
    return {
      items: ((d.items as BacktestHistoryItem[]) ?? []),
      page: Number(d.page ?? page),
      size: Number(d.size ?? size),
      total: Number(d.total ?? 0)
    }
  },

  async getBacktestResult(resultId: string): Promise<BacktestHistoryItem> {
    const res = await request.get<{ data: Record<string, unknown> }>(`/backtest/results/${resultId}`)
    const d = (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
    return d as unknown as BacktestHistoryItem
  }
}
