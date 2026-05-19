/** 股票信息 */
export interface StockInfo {
  id?: number
  stock_code: string
  stock_name: string
  exchange_type: string
  exchange_type_name?: string
  industry?: string
  sector?: string
  list_date?: string
  status?: number
}

/** 行情数据 OHLCV */
export interface MarketData {
  stock_code: string
  exchange_type: string
  trade_date: string
  open_price: number
  high_price: number
  low_price: number
  close_price: number
  volume?: number
  amount?: number
}

/** K线数据(前端用) */
export interface KLineData {
  date: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

/** 数据概览 */
export interface DataOverview {
  stock_count: number
  earliest_date: string
  latest_date: string
  completeness: number
  storage_size: string
}
