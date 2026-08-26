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

export interface WatchPosition {
  code: string
  name: string
  qty: number
  available_qty: number
  today_buy_qty: number
  avg_cost: number
  last_price: number
  market_value: number
  profit: number
  profit_rate: number | null
}

export interface WatchAccount {
  available: boolean
  session_id: string
  initial_cash: number
  cash: number
  frozen_cash: number
  market_value: number
  total_assets: number
  realized_pnl: number
  unrealized_pnl: number
  total_profit: number
  total_return_pct: number
  position_count: number
  open_order_count: number
  positions: WatchPosition[]
  error?: string
}

export interface WatchOrderRow {
  client_order_id: string
  session_id: string
  channel: string
  run_id: string
  strategy: string
  signal_bar_ts: string
  code: string
  name: string
  side: 'buy' | 'sell' | string
  order_type: string
  limit_price: number | null
  qty: number
  filled_qty: number
  avg_fill_price: number | null
  total_fee: number | null
  status: string
  broker_order_id: string
  created_at: string
  updated_at: string
  expires_at: string
  last_error_code: string
  last_error_message: string
  metadata: Record<string, unknown>
}

export interface WatchOrderList {
  available: boolean
  session_id: string
  count: number
  orders: WatchOrderRow[]
  error?: string
}

export interface WatchFillRow {
  fill_id: string
  client_order_id: string
  code: string
  name: string
  side: 'buy' | 'sell' | string
  qty: number
  price: number
  gross_amount: number
  commission: number
  stamp_tax: number
  total_fee: number
  filled_at: string
  bar_ts: string
  price_source: string
  metadata: Record<string, unknown>
}

export interface WatchFillList {
  available: boolean
  session_id: string
  count: number
  fills: WatchFillRow[]
  error?: string
}

export interface WatchPnlTrade {
  fill_id: string
  ts: string
  qty: number
  price: number
  fee: number
  avg_cost: number
  realized: number
}

export interface WatchPnlCode {
  code: string
  name: string
  qty: number
  avg_cost: number
  realized: number
  unrealized: number
  total: number
  trades: WatchPnlTrade[]
}

export interface WatchPnl {
  available: boolean
  session_id: string
  per_code: WatchPnlCode[]
  totals: {
    realized: number
    unrealized: number
    total: number
    realized_account: number
    consistent: boolean
  }
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

export function fetchAccount(session_id?: string): Promise<WatchAccount> {
  const params = session_id ? `?session_id=${encodeURIComponent(session_id)}` : ''
  return get(`/api/watch/account${params}`)
}

export function fetchOrders(filters: { session_id?: string; status?: string; code?: string; limit?: number } = {}): Promise<WatchOrderList> {
  const params = new URLSearchParams()
  if (filters.session_id) params.set('session_id', filters.session_id)
  if (filters.status) params.set('status', filters.status)
  if (filters.code) params.set('code', filters.code)
  if (filters.limit) params.set('limit', String(filters.limit))
  const query = params.toString()
  return get(`/api/watch/orders${query ? `?${query}` : ''}`)
}

export function fetchFills(filters: { session_id?: string; code?: string; limit?: number } = {}): Promise<WatchFillList> {
  const params = new URLSearchParams()
  if (filters.session_id) params.set('session_id', filters.session_id)
  if (filters.code) params.set('code', filters.code)
  if (filters.limit) params.set('limit', String(filters.limit))
  const query = params.toString()
  return get(`/api/watch/fills${query ? `?${query}` : ''}`)
}

export function fetchPnl(session_id?: string): Promise<WatchPnl> {
  const params = session_id ? `?session_id=${encodeURIComponent(session_id)}` : ''
  return get(`/api/watch/pnl${params}`)
}
