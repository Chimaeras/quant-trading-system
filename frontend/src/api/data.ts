/**
 * 数据中心 API - 离线模式使用 IndexedDB + Mock
 */
import dayjs from 'dayjs'
import { getDB } from '@/utils/db'
import { generateMockStocks, generateMockKLine } from '@/utils/mock'
import type { DataOverview } from '@/types'

export const dataApi = {
  /** 获取数据概览 */
  async getOverview(): Promise<DataOverview> {
    try {
      const db = await getDB()
      const stocks = await db.getAll('stocks')
      const allData = await db.getAll('market_data')
      const dates = [...new Set(allData.map((d: { trade_date: string }) => d.trade_date))].sort()
      return {
        stock_count: stocks.length || 5000,
        earliest_date: dates[0] || '2010-01-01',
        latest_date: (dates[dates.length - 1] as string) || dayjs().format('YYYY-MM-DD'),
        completeness: 98.5,
        storage_size: '2.5GB'
      }
    } catch {
      return {
        stock_count: 5000,
        earliest_date: '2010-01-01',
        latest_date: dayjs().format('YYYY-MM-DD'),
        completeness: 98.5,
        storage_size: '2.5GB'
      }
    }
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

