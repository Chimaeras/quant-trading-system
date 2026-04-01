/**
 * 数据中心 API - 优先调用后端，离线时使用 IndexedDB 真实统计
 */
import dayjs from 'dayjs'
import { getDB } from '@/utils/db'
import { generateMockStocks, generateMockKLine } from '@/utils/mock'
import request from './request'
import type { DataOverview } from '@/types'

/** 计算两个日期之间的交易日数量（去除周末） */
function tradingDaysBetween(start: string, end: string): number {
  let d = dayjs(start)
  const endD = dayjs(end)
  let count = 0
  while (!d.isAfter(endD)) {
    const day = d.day()
    if (day !== 0 && day !== 6) count++
    d = d.add(1, 'day')
  }
  return Math.max(1, count)
}

/** 格式化存储大小 */
function formatStorage(bytes: number): string {
  if (bytes <= 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

export const dataApi = {
  /** 获取数据概览 - 优先后端，失败则用 IndexedDB 真实统计。每次调用强制重新计算 */
  async getOverview(): Promise<DataOverview> {
    try {
      const res = await request.get<{ data: DataOverview }>('/data/overview', {
        params: { _t: Date.now() },
        headers: { 'Cache-Control': 'no-cache', Pragma: 'no-cache' }
      })
      const d = (res as { data?: DataOverview })?.data ?? res
      if (d && typeof d.stock_count === 'number') {
        return {
          stock_count: d.stock_count,
          earliest_date: d.earliest_date ?? '-',
          latest_date: d.latest_date ?? '-',
          completeness: d.completeness ?? 0,
          storage_size: d.storage_size ?? '-'
        }
      }
    } catch {
      /* 后端不可用，使用 IndexedDB */
    }
    return this.getOverviewFromIndexedDB()
  },

  /** 从 IndexedDB 计算真实数据概览 */
  async getOverviewFromIndexedDB(): Promise<DataOverview> {
    try {
      const db = await getDB()
      const allData = (await db.getAll('market_data')) as Array<{ stock_code?: string; exchange_type?: string; trade_date?: string }>
      const stocks = await db.getAll('stocks')

      const stockCount = (() => {
        if (allData.length > 0) {
          const keys = new Set(allData.map(d => (d.exchange_type ? `${d.stock_code}-${d.exchange_type}` : d.stock_code) ?? '').filter(Boolean))
          return keys.size
        }
        return stocks.length
      })()

      const dates = [...new Set(allData.map(d => d.trade_date).filter(Boolean))].sort() as string[]
      const earliestDate = dates[0] ?? null
      const latestDate = dates.length ? dates[dates.length - 1] : null

      let completeness = 0
      if (stockCount > 0 && earliestDate && latestDate && allData.length > 0) {
        const tradingDays = tradingDaysBetween(earliestDate, latestDate)
        const expected = stockCount * tradingDays
        completeness = expected > 0 ? Math.min(100, (allData.length / expected) * 100) : 0
      }

      let storageSize = '-'
      if (typeof navigator !== 'undefined' && navigator.storage?.estimate) {
        const est = await navigator.storage.estimate()
        if (est.usage != null) storageSize = formatStorage(est.usage)
      }

      return {
        stock_count: stockCount,
        earliest_date: earliestDate ?? '-',
        latest_date: latestDate ?? dayjs().format('YYYY-MM-DD'),
        completeness: Math.round(completeness * 10) / 10,
        storage_size: storageSize
      }
    } catch {
      return {
        stock_count: 0,
        earliest_date: '-',
        latest_date: dayjs().format('YYYY-MM-DD'),
        completeness: 0,
        storage_size: '-'
      }
    }
  },

  /** 历史行情立即更新：按股票+日期范围拉取，仅补充缺失的交易日 */
  async updateHistoryData(params: {
    stockCode: string
    exchange?: string
    startDate: string
    endDate: string
  }): Promise<{ inserted: number; skipped: number; total: number; message?: string }> {
    const res = await request.post<{ data: { inserted: number; skipped: number; total: number; message?: string } }>(
      '/data/history/update',
      null,
      { params }
    )
    const d = (res as { data?: { inserted: number; skipped: number; total: number; message?: string } })?.data ?? res
    return {
      inserted: d?.inserted ?? 0,
      skipped: d?.skipped ?? 0,
      total: d?.total ?? 0,
      message: d?.message
    }
  },

  /** 任务化历史更新（single/all/list） */
  async runUpdateTask(params: {
    mode: 'SINGLE' | 'ALL' | 'LIST'
    stockCode?: string
    exchange?: string
    startDate?: string
    endDate?: string
    stockList?: string[]
    stockListText?: string
  }) {
    const res = await request.post<{ data: Record<string, unknown> }>('/data/update/run', params)
    return (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
  },

  async getUpdateTask(taskId: string) {
    const res = await request.get<{ data: Record<string, unknown> }>(`/data/update/tasks/${taskId}`)
    return (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
  },

  async listUpdateTasks(page = 1, size = 20) {
    const res = await request.get<{ data: Record<string, unknown> }>('/data/update/tasks', { params: { page, size } })
    return (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
  },

  async retryUpdateTask(taskId: string) {
    const res = await request.post<{ data: Record<string, unknown> }>(`/data/update/tasks/${taskId}/retry`)
    return (res as { data?: Record<string, unknown> })?.data ?? (res as Record<string, unknown>)
  },

  /** 更新股票列表 */
  async updateStockList(): Promise<{ success: boolean; count: number }> {
    const db = await getDB()
    const stocks = generateMockStocks(200)
    const tx = db.transaction('stocks', 'readwrite')
    for (const s of stocks) {
      await tx.store.put({ ...s, stock_code: s.stock_code, exchange_type: s.exchange_type })
    }
    await tx.done
    return { success: true, count: stocks.length }
  },

  /** 获取股票列表 */
  async getStockList(params: { page?: number; size?: number; keyword?: string; exchange_type?: string }) {
    const { page = 1, size = 20, keyword, exchange_type } = params
    const db = await getDB()
    let stocks = await db.getAll('stocks')
    if (stocks.length === 0) {
      stocks = generateMockStocks(500)
      const tx = db.transaction('stocks', 'readwrite')
      for (const s of stocks) {
        await tx.store.put(s)
      }
      await tx.done
    }
    if (keyword) {
      stocks = stocks.filter((s: Record<string, unknown>) =>
        String(s.stock_code ?? '').includes(keyword) || String(s.stock_name ?? '').includes(keyword)
      )
    }
    if (exchange_type) {
      stocks = stocks.filter((s: { exchange_type: string }) => s.exchange_type === exchange_type)
    }
    const total = stocks.length
    const start = (page - 1) * size
    return {
      list: stocks.slice(start, start + size),
      total
    }
  },

  /** 获取K线数据 */
  async getKLine(stockCode: string, _startDate?: string, _endDate?: string) {
    const db = await getDB()
    let data = await db.getAllFromIndex('market_data', 'by-stock', stockCode)
    if (data.length === 0) {
      const kline = generateMockKLine(stockCode, 250)
      const tx = db.transaction('market_data', 'readwrite')
      for (const k of kline) {
        await tx.store.put({
          stock_code: stockCode,
          trade_date: k.date,
          open_price: k.open,
          high_price: k.high,
          low_price: k.low,
          close_price: k.close,
          volume: k.volume
        })
      }
      await tx.done
      return kline
    }
    return (data as Array<Record<string, unknown>>)
      .sort((a, b) => String(a.trade_date).localeCompare(String(b.trade_date)))
      .map((d) => ({
        date: String(d.trade_date),
        open: Number(d.open_price),
        high: Number(d.high_price),
        low: Number(d.low_price),
        close: Number(d.close_price),
        volume: Number(d.volume) || 0
      }))
  },

  /** 导入CSV数据 */
  async importCSV(file: File): Promise<{ success: boolean; count: number; errors?: string[] }> {
    const text = await file.text()
    const lines = text.trim().split('\n')
    const headers = lines[0].split(/[,，]/).map(h => h.trim())
    const db = await getDB()
    const tx = db.transaction(['stocks', 'market_data'], 'readwrite')
    let count = 0
    for (let i = 1; i < lines.length; i++) {
      const vals = lines[i].split(/[,，]/).map(v => v.trim())
      const row: Record<string, string | number> = {}
      headers.forEach((h, idx) => { row[h] = vals[idx] || '' })
      const code = String(row['股票代码'] ?? row['stock_code'] ?? row['code'] ?? '')
      const date = String(row['日期'] ?? row['trade_date'] ?? row['date'] ?? '')
      if (code && date) {
        await tx.objectStore('market_data').put({
          stock_code: code,
          trade_date: date,
          open_price: Number(row['开盘'] ?? row['open'] ?? 0),
          high_price: Number(row['最高'] ?? row['high'] ?? 0),
          low_price: Number(row['最低'] ?? row['low'] ?? 0),
          close_price: Number(row['收盘'] ?? row['close'] ?? 0),
          volume: Number(row['成交量'] ?? row['volume'] ?? 0)
        })
        count++
      }
    }
    await tx.done
    return { success: true, count }
  }
}

