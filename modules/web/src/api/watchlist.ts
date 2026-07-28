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
