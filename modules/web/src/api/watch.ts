/**
 * 盘中盯盘监控 API（交易数据只读，观察池可维护；经 /pyapi 代理到 127.0.0.1:8790）。
 * 返回为裸 JSON，不走后端 ApiResponse 包装，直接用 fetch。
 */

export interface WatchEvent {
  seq: number
  ts: string
  date: string
  cycle: number
  type: string
  code: string
  name?: string
  detail: Record<string, unknown>
}

export interface WatchCycleSummary {
  state: {
    trade_date?: string
    day_trade_count?: number
    cooldowns?: Record<string, unknown>
    open_order_ids?: string[]
    updated_at?: string
  }
  last_cycle: WatchEvent | null
}

export interface WatchTickSummary {
  run_id: string
  started_at: string
  finished_at: string
  live: boolean
  status: string
  bars: number
  signals: number
  submits: number
  errors: number
  account: Record<string, unknown>
}

export interface WatchMarketSnapshot {
  date: string
  available: boolean
  quotes: Array<Record<string, any>>
  active_pool: Array<Record<string, any>>
  batch: Record<string, any>
  error?: string
}

export interface WatchPoolItem {
  code: string
  name: string
  exchange: 'SH' | 'SZ' | 'BJ'
  source: 'hot_stocks' | 'manual' | string
  manual: number | boolean
  created_at: string
  updated_at: string
}

export interface WatchPoolResponse {
  available: boolean
  total: number
  max_size: number
  items: WatchPoolItem[]
  error?: string
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = localStorage.getItem('quant_token')
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body) headers.set('Content-Type', 'application/json')
  const res = await fetch(`/pyapi${path}`, {
    ...init,
    headers
  })
  if (!res.ok) {
    const detail = await res.json().catch(() => ({})) as { message?: string }
    throw new Error(detail.message || `watch-serve ${res.status}: ${path}`)
  }
  return (await res.json()) as T
}

function get<T>(path: string): Promise<T> {
  return call<T>(path)
}

export function fetchDays(): Promise<{ days: string[] }> {
  return get('/api/watch/days')
}

export function fetchEvents(date: string, afterSeq = 0, filters: { code?: string; type?: string; limit?: number; latest?: boolean } = {}): Promise<{ date: string; events: WatchEvent[] }> {
  const params = new URLSearchParams()
  if (afterSeq) params.set('after_seq', String(afterSeq))
  if (filters.code) params.set('code', filters.code)
  if (filters.type) params.set('type', filters.type)
  if (filters.limit) params.set('limit', String(filters.limit))
  if (filters.latest) params.set('latest', '1')
  const query = params.toString()
  return get(`/api/watch/${date}/events${query ? `?${query}` : ''}`)
}

export function fetchState(date: string): Promise<WatchCycleSummary> {
  return get(`/api/watch/${date}/state`)
}

export function fetchTicks(date: string): Promise<{ date: string; ticks: WatchTickSummary[] }> {
  return get(`/api/watch/${date}/ticks`)
}

export function fetchMarket(date: string, limit = 500): Promise<WatchMarketSnapshot> {
  return get(`/api/watch/${date}/market?limit=${limit}`)
}

export function fetchWatchPool(): Promise<WatchPoolResponse> {
  return get('/api/watch/pool')
}

export function addWatchPoolMember(payload: {
  code: string
  name?: string
  exchange?: 'SH' | 'SZ' | 'BJ'
}): Promise<{ item: WatchPoolItem; added: boolean }> {
  return call('/api/watch/pool', { method: 'POST', body: JSON.stringify(payload) })
}

export function removeWatchPoolMember(code: string): Promise<{ code: string; deleted: boolean }> {
  return call(`/api/watch/pool/${encodeURIComponent(code)}`, { method: 'DELETE' })
}

export function checkHealth(): Promise<boolean> {
  return get('/api/watch/health')
    .then(() => true)
    .catch(() => false)
}
