import request from './request'

export interface WatchlistItem {
  id?: number
  user_id: number
  stock_code: string
  exchange_type: string
  stock_name?: string
  note?: string
  created_at?: string
}

export function listWatchlist(userId?: number): Promise<WatchlistItem[]> {
  return request.get('/api/watchlist/list', { params: userId ? { userId } : {} })
}

export function addWatchlist(
  stockCode: string,
  exchangeType: string,
  note?: string,
  userId?: number
): Promise<WatchlistItem> {
  return request.post('/api/watchlist/add', {
    stockCode,
    exchangeType,
    note,
    ...(userId ? { userId } : {})
  })
}

export interface BatchAddResult {
  user_id: number
  added: number
  skipped: number
  errors: Array<{ stock_code: string; exchange_type: string; error: string }>
  note: string
}

export function batchAddWatchlist(text: string, userId?: number): Promise<BatchAddResult> {
  return request.post('/api/watchlist/batch-add', {
    text,
    ...(userId ? { userId } : {})
  })
}

export function removeWatchlist(
  stockCode: string,
  exchangeType: string,
  userId?: number
): Promise<{ deleted: boolean }> {
  return request.post('/api/watchlist/remove', {
    stockCode,
    exchangeType,
    ...(userId ? { userId } : {})
  })
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

export function scanWatchlistAlerts(params?: {
  userId?: number
  pctThreshold?: number
  volumeRatioThreshold?: number
  lookbackDays?: number
}): Promise<WatchlistAlertScan> {
  return request.get('/api/watchlist/alerts', { params: params || {} })
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

export function getWatchlistSignalCoverage(userId?: number): Promise<SignalCoverageResponse> {
  return request.get('/api/watchlist/signal-coverage', { params: userId ? { userId } : {} })
}
