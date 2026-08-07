import request, { unwrap } from './request'

export interface WatchlistItem {
  id?: number
  user_id: number
  stock_code: string
  exchange_type: string
  stock_name?: string
  note?: string
  created_at?: string
}

export async function listWatchlist(userId?: number): Promise<WatchlistItem[]> {
  const res = await request.get<WatchlistItem[]>('/watchlist/list', { params: userId ? { userId } : {} })
  return unwrap(res) ?? []
}

export async function addWatchlist(
  stockCode: string,
  exchangeType: string,
  note?: string,
  userId?: number
): Promise<WatchlistItem> {
  const res = await request.post<WatchlistItem>('/watchlist/add', {
    stockCode,
    exchangeType,
    note,
    ...(userId ? { userId } : {})
  })
  return unwrap(res)
}

export interface BatchAddResult {
  user_id: number
  added: number
  skipped: number
  errors: Array<{ stock_code: string; exchange_type: string; error: string }>
  note: string
}

export async function batchAddWatchlist(text: string, userId?: number): Promise<BatchAddResult> {
  const res = await request.post<BatchAddResult>('/watchlist/batch-add', {
    text,
    ...(userId ? { userId } : {})
  })
  return unwrap(res)
}

export async function removeWatchlist(
  stockCode: string,
  exchangeType: string,
  userId?: number
): Promise<{ deleted: boolean }> {
  const res = await request.post<{ deleted: boolean }>('/watchlist/remove', {
    stockCode,
    exchangeType,
    ...(userId ? { userId } : {})
  })
  return unwrap(res)
}

export interface WatchlistAlert {
  stock_code: string
  exchange_type: string
  stock_name?: string
  signal_date: string
  latest_close: number | null
  pct_change: number
  volume_ratio: number
  signals: string[]
}

export interface WatchlistAlertScan {
  user_id: number
  basis: string
  signal_date: string
  total_scanned: number
  total_alerted: number
  alerts: WatchlistAlert[]
  note: string
}

export async function scanWatchlistAlerts(params?: {
  userId?: number
  pctThreshold?: number
  volumeRatioThreshold?: number
  lookbackDays?: number
}): Promise<WatchlistAlertScan> {
  const res = await request.get<WatchlistAlertScan>('/watchlist/alerts', { params: params || {} })
  return unwrap(res)
}

export interface SignalHit {
  strategy: string
  rank_no: number
  final_score?: number
}

export interface SignalCoverageItem {
  stock_code: string
  exchange_type: string
  stock_name?: string
  hit_strategies: SignalHit[]
  hit_count: number
}

export interface SignalCoverageResponse {
  user_id: number
  trade_date: string
  total: number
  hit_count: number
  items: SignalCoverageItem[]
  note: string
}

export async function getWatchlistSignalCoverage(userId?: number): Promise<SignalCoverageResponse> {
  const res = await request.get<SignalCoverageResponse>('/watchlist/signal-coverage', {
    params: userId ? { userId } : {}
  })
  return unwrap(res)
}
