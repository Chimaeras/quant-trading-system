/**
 * 数据中心 API - 优先调用后端，离线时使用 IndexedDB 真实统计
 */
import dayjs from 'dayjs'
import { getDB } from '@/utils/db'
import request, { unwrap, type ApiResponse, type RequestError } from './request'
import type { DataOverview } from '@/types'

/** Mock 数据仅在开发环境显式设置 VITE_ENABLE_MOCK_DATA=true 时启用。 */
export interface HotStockItem {
  stock_code: string
  stock_name: string
  exchange_type: string
  industry?: string
  trade_days: number
  avg_volume: number
  avg_amount: number
  avg_turnover_pct: number
  volume_ratio: number
  up_days: number
  latest_close: number
  latest_change_pct: number
  hot_score: number
  hot_level: string
}

/** 数据质量驾驶舱返回结构（与后端 DataQualityReport 对齐，从 DataQuality.vue 提升） */
export interface QualityReport {
  earliest_date: string
  latest_date: string
  trading_days: { expected: number; actual: number; missing_count: number; missing_dates: string[]; note: string }
  update_staleness: { today: string; latest_date?: string; days_behind: number | null; stale: boolean; threshold_days: number; warning: string | null }
  list_date: { missing_count: number; backfillable_count: number; note: string }
  adjust_factor: { current_basis: string; scope: string; warning: string }
  raw_and_adjust?: {
    market_price_raw: {
      total_rows: number; distinct_stocks: number; distinct_trade_dates: number
      earliest_date: string; latest_date: string; source: string; status: string
    }
    adjust_factor: {
      total_rows: number; distinct_stocks: number
      by_source?: { derived_local: number; official: number }
      by_reliability?: { limited: number }
      status: string; note: string
    }
  }
  security_master?: {
    total_rows: number; distinct_stocks: number
    by_source?: { current_snapshot: number; eastmoney: number; tushare: number }
    by_reliability?: { limited: number; official: number }
    status: string; note: string
  }
  trading_calendar?: {
    total_rows: number
    earliest_date: string; latest_date: string
    by_source?: { eastmoney_derived: number; tushare: number }
    by_reliability?: { limited: number; official: number }
    status: string; note: string
    diff_vs_market_data?: {
      calendar_days: number; market_data_days: number; missing_in_market_data: number
      missing_dates: string[]; note: string
    }
  }
  index_data?: {
    total_rows: number; distinct_indices: number
    indices?: Array<{ index_code: string; index_name: string; rows: number; earliest_date: string; latest_date: string }>
    by_source?: { eastmoney: number; tushare: number }
    by_reliability?: { limited: number; official: number }
    status: string; note: string
  }
  data_snapshot?: {
    total_snapshots: number
    status: string; note: string
    latest?: Array<{ snapshot_id: string; cutoff_time: string; hash: string; created_by: string; created_at: string; row_counts: string }>
  }
  health: { status: string; verdict: string }
}

export interface HotStocksResponse {
  limit: number
  days: number
  baseline_days: number
  start_date: string
  end_date: string
  items_count: number
  formula: string
  items: HotStockItem[]
}

const mockDataEnabled = import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCK_DATA === 'true'
if (mockDataEnabled) {
  // 醒目告警：Mock 数据开启时页面可能显示模拟行情而非真实后端数据，避免被误判为线上数据
  console.warn('⚠ Mock 数据已启用（VITE_ENABLE_MOCK_DATA=true，仅本地开发）。页面将显示模拟行情，非真实后端数据。')
}

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
  /**
   * 获取数据概览。
   *
   * 只有在「请求根本没到服务端」（无 HTTP 响应：离线/后端未启动）时才回退本地 IndexedDB 统计。
   * 5xx / 401 / 业务错误必须原样抛出：此前一律 catch 后回退，页面会把本地空库统计
   * （0 只股票、完整度 0%）当成真实概览展示，把后端故障伪装成「数据库是空的」。
   */
  async getOverview(): Promise<DataOverview> {
    let res: ApiResponse<DataOverview>
    try {
      res = await request.get<DataOverview>('/data/overview', {
        params: { _t: Date.now() },
        headers: { 'Cache-Control': 'no-cache', Pragma: 'no-cache' }
      })
    } catch (e) {
      const status = (e as Partial<RequestError> | undefined)?.status
      if (status != null) {
        throw e
      }
      return this.getOverviewFromIndexedDB()
    }
    const d = unwrap(res)
    if (!d || typeof d.stock_count !== 'number') {
      // 200 但结构不对同样是后端问题，回退本地统计只会掩盖它。
      throw new Error('后端数据概览响应缺少 stock_count 字段')
    }
    return {
      stock_count: d.stock_count,
      earliest_date: d.earliest_date ?? '-',
      latest_date: d.latest_date ?? '-',
      completeness: d.completeness ?? 0,
      storage_size: d.storage_size ?? '-',
      list_date_missing_count: d.list_date_missing_count ?? 0,
      list_date_backfillable_count: d.list_date_backfillable_count ?? 0
    }
  },

  /** 从 IndexedDB 计算真实数据概览 */
  async getHotStocks(params: {
    limit?: number
    days?: number
    baselineDays?: number
    endDate?: string
  } = {}): Promise<HotStocksResponse> {
    const res = await request.get<HotStocksResponse>('/data/hot-stocks', {
      params: {
        limit: params.limit ?? 50,
        days: params.days ?? 7,
        baselineDays: params.baselineDays ?? 20,
        endDate: params.endDate || undefined,
        _t: Date.now()
      },
      headers: { 'Cache-Control': 'no-cache', Pragma: 'no-cache' }
    })
    const d = unwrap(res)
    return {
      limit: d?.limit ?? params.limit ?? 50,
      days: d?.days ?? params.days ?? 7,
      baseline_days: d?.baseline_days ?? params.baselineDays ?? 20,
      start_date: d?.start_date ?? '-',
      end_date: d?.end_date ?? '-',
      items_count: d?.items_count ?? 0,
      formula: d?.formula ?? '',
      items: d?.items ?? []
    }
  },

  /** 数据质量驾驶舱 */
  async getQuality(): Promise<QualityReport> {
    const res = await request.get<QualityReport>('/data/quality')
    return unwrap(res)
  },

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
    const res = await request.post<{ inserted: number; skipped: number; total: number; message?: string }>(
      '/data/history/update',
      null,
      { params }
    )
    const d = unwrap(res)
    return {
      inserted: d?.inserted ?? 0,
      skipped: d?.skipped ?? 0,
      total: d?.total ?? 0,
      message: d?.message
    }
  },

  /** 任务化历史更新（single/all/list） */
  async runUpdateTask(params: {
    mode: 'SINGLE' | 'ALL' | 'LIST' | 'MAIN_BOARD'
    stockCode?: string
    exchange?: string
    startDate?: string
    endDate?: string
    stockList?: string[]
    stockListText?: string
    /** 股票之间请求间隔（毫秒），主板枚举默认由后端 150，其它模式默认 0 */
    delayMs?: number
    /** 仅截取前 N 只（用于试跑），0 或不传表示不限制 */
    maxStocks?: number
  }) {
    const res = await request.post<Record<string, unknown>>('/data/update/run', params)
    return unwrap(res) ?? {}
  },

  async getUpdateTask(taskId: string, page = 1, size = 100) {
    const res = await request.get<Record<string, unknown>>(`/data/update/tasks/${taskId}`, {
      params: { page, size }
    })
    return unwrap(res) ?? {}
  },

  async listUpdateTasks(page = 1, size = 20) {
    const res = await request.get<Record<string, unknown>>('/data/update/tasks', { params: { page, size } })
    return unwrap(res) ?? {}
  },

  async retryUpdateTask(taskId: string) {
    const res = await request.post<Record<string, unknown>>(`/data/update/tasks/${taskId}/retry`)
    return unwrap(res) ?? {}
  },

  async stopUpdateTask(taskId: string) {
    const res = await request.post<Record<string, unknown>>(`/data/update/tasks/${taskId}/stop`)
    return unwrap(res) ?? {}
  },

  /** 用 K 线最早交易日回填 stock_info.list_date（仅补空值） */
  async backfillListDate(): Promise<{
    missing_before: number
    updated: number
    missing_after: number
    skipped_no_match?: number
    no_market_data?: number
    note?: string
  }> {
    const res = await request.post<{
      missing_before: number
      updated: number
      missing_after: number
      skipped_no_match?: number
      no_market_data?: number
      note?: string
    }>('/data/stock-info/backfill-list-date')
    const d = unwrap(res)
    return {
      missing_before: d?.missing_before ?? 0,
      updated: d?.updated ?? 0,
      missing_after: d?.missing_after ?? 0,
      skipped_no_match: d?.skipped_no_match,
      no_market_data: d?.no_market_data,
      note: d?.note
    }
  },

  /** QP-003c：回填不复权行情并推算复权因子（单只证券） */
  async backfillRaw(params: {
    stockCode: string
    exchange: string
    startDate: string
    endDate: string
  }): Promise<{
    stock_code: string
    raw_inserted: number
    raw_skipped: number
    factor_generated: number
    factor_skipped: number
    data_source: string
    message: string
  }> {
    const res = await request.post<{
      stock_code: string
      raw_inserted: number
      raw_skipped: number
      factor_generated: number
      factor_skipped: number
      data_source: string
      message: string
    }>('/data/backfill-raw', null, { params })
    const d = unwrap(res)
    return {
      stock_code: d?.stock_code ?? params.stockCode,
      raw_inserted: d?.raw_inserted ?? 0,
      raw_skipped: d?.raw_skipped ?? 0,
      factor_generated: d?.factor_generated ?? 0,
      factor_skipped: d?.factor_skipped ?? 0,
      data_source: d?.data_source ?? '',
      message: d?.message ?? ''
    }
  },

  /** QP-004：从 stock_info 当前快照初始化历史证券主数据（幂等） */
  async initSecurityMaster(): Promise<{
    total_processed: number
    initialized: number
    skipped_existing: number
    skipped_no_listdate: number
    source: string
    reliability: string
    note: string
  }> {
    const res = await request.post<{
      total_processed: number
      initialized: number
      skipped_existing: number
      skipped_no_listdate: number
      source: string
      reliability: string
      note: string
    }>('/data/security-master/init')
    const d = unwrap(res)
    return {
      total_processed: d?.total_processed ?? 0,
      initialized: d?.initialized ?? 0,
      skipped_existing: d?.skipped_existing ?? 0,
      skipped_no_listdate: d?.skipped_no_listdate ?? 0,
      source: d?.source ?? '',
      reliability: d?.reliability ?? '',
      note: d?.note ?? ''
    }
  },

  /** QP-005：从上证指数日K反推真实交易日历（幂等） */
  async initTradingCalendar(params: {
    startDate: string
    endDate: string
  }): Promise<{
    total_fetched: number
    inserted: number
    skipped_existing: number
    source: string
    reliability: string
    note: string
  }> {
    const res = await request.post<{
      total_fetched: number
      inserted: number
      skipped_existing: number
      source: string
      reliability: string
      note: string
    }>('/data/trading-calendar/init', null, { params })
    const d = unwrap(res)
    return {
      total_fetched: d?.total_fetched ?? 0,
      inserted: d?.inserted ?? 0,
      skipped_existing: d?.skipped_existing ?? 0,
      source: d?.source ?? '',
      reliability: d?.reliability ?? '',
      note: d?.note ?? ''
    }
  },

  /** QP-005：拉取指数行情（沪深300/中证500/中证1000/上证指数，幂等） */
  async fetchIndexData(params: {
    indexCode?: string
    startDate: string
    endDate: string
  }): Promise<Record<string, unknown>> {
    const res = await request.post<Record<string, unknown>>('/data/index/fetch', null, { params })
    return unwrap(res)
  },

  /** QP-006：创建数据快照（行数/source/质量状态 + SHA-256 hash） */
  async createSnapshot(cutoff?: string): Promise<{
    snapshot_id: string
    cutoff_time: string
    hash: string
    row_counts: string
    source_versions: string
    quality_status: string
    created_by: string
    message: string
  }> {
    const res = await request.post<{
      snapshot_id: string
      cutoff_time: string
      hash: string
      row_counts: string
      source_versions: string
      quality_status: string
      created_by: string
      message: string
    }>('/data/snapshot/create', null, { params: cutoff ? { cutoff } : {} })
    const d = unwrap(res)
    return {
      snapshot_id: d?.snapshot_id ?? '',
      cutoff_time: d?.cutoff_time ?? '',
      hash: d?.hash ?? '',
      row_counts: d?.row_counts ?? '',
      source_versions: d?.source_versions ?? '',
      quality_status: d?.quality_status ?? '',
      created_by: d?.created_by ?? '',
      message: d?.message ?? ''
    }
  },

  /** QP-006：最近 N 条数据快照 */
  async listSnapshots(limit = 10): Promise<{
    total: number
    items: Array<{
      snapshot_id: string
      cutoff_time: string
      hash: string
      created_by: string
      created_at: string
      row_counts: string
      source_versions: string
    }>
  }> {
    const res = await request.get<{ total: number; items: any[] }>('/data/snapshot/list', {
      params: { limit }
    })
    const d = unwrap(res)
    return {
      total: d?.total ?? 0,
      items: d?.items ?? []
    }
  },

  /** QP-007：历史重放（PIT 股票池健康报告，幂等只读） */
  async runReplay(params: {
    startDate: string
    endDate: string
  }): Promise<Record<string, unknown>> {
    const res = await request.post<Record<string, unknown>>('/research/replay', null, { params })
    return unwrap(res)
  },

  /** QP-008：生成 T+2/T+3 标签（基于 PIT 股票池，保留拒单样本，幂等）。
   * V2-MP-002：新研究唯一生产口径 label_v2_raw_calendar；dataSnapshotId 必填（后端 HTTP 400 若缺）。 */
  async buildLabels(params: {
    startDate: string
    endDate: string
    labelVersion?: string
    dataSnapshotId?: string
  }): Promise<Record<string, unknown>> {
    const res = await request.post<Record<string, unknown>>('/research/labels/build', null, {
      params: { ...params, labelVersion: params.labelVersion || 'label_v2_raw_calendar' }
    })
    return unwrap(res)
  },

  /** QP-008：标签覆盖率统计（默认 label_v2_raw_calendar） */
  async getLabelCoverage(labelVersion = 'label_v2_raw_calendar'): Promise<Record<string, unknown>> {
    const res = await request.get<Record<string, unknown>>('/research/labels/coverage', {
      params: { labelVersion }
    })
    return unwrap(res)
  },

  /** 更新股票列表 */
  async updateStockList(): Promise<{ success: boolean; count: number }> {
    if (!mockDataEnabled) {
      throw new Error('未配置真实股票列表更新接口；Mock 数据默认关闭')
    }
    const db = await getDB()
    const { generateMockStocks } = await import('@/utils/mock')
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
    if (stocks.length === 0 && mockDataEnabled) {
      const { generateMockStocks } = await import('@/utils/mock')
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
    if (data.length === 0 && mockDataEnabled) {
      const { generateMockKLine } = await import('@/utils/mock')
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
    if (data.length === 0) return []
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
