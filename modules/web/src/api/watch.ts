/**
 * 盘中盯盘监控 API（watch-serve 只读服务，经 /pyapi 代理到 127.0.0.1:8790）。
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

async function get<T>(path: string): Promise<T> {
  const token = localStorage.getItem('quant_token')
  const res = await fetch(`/pyapi${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined
  })
  if (!res.ok) {
    throw new Error(`watch-serve ${res.status}: ${path}`)
  }
  return (await res.json()) as T
}

export function fetchDays(): Promise<{ days: string[] }> {
  return get('/api/watch/days')
}

export function fetchEvents(date: string, afterSeq = 0, filters: { code?: string; type?: string; limit?: number } = {}): Promise<{ date: string; events: WatchEvent[] }> {
  const params = new URLSearchParams()
  if (afterSeq) params.set('after_seq', String(afterSeq))
  if (filters.code) params.set('code', filters.code)
  if (filters.type) params.set('type', filters.type)
  if (filters.limit) params.set('limit', String(filters.limit))
  const query = params.toString()
  return get(`/api/watch/${date}/events${query ? `?${query}` : ''}`)
}

export function fetchState(date: string): Promise<WatchCycleSummary> {
  return get(`/api/watch/${date}/state`)
}

export function fetchTicks(date: string): Promise<{ date: string; ticks: WatchTickSummary[] }> {
  return get(`/api/watch/${date}/ticks`)
}

export function checkHealth(): Promise<boolean> {
  return get('/api/watch/health')
    .then(() => true)
    .catch(() => false)
}
