import request from './request'

export interface ScreenerItem {
  stock_code: string
  exchange_type: string
  stock_name?: string
  industry?: string
  list_date?: string
  listing_days?: number
}

export interface ScreenerResponse {
  reference_date: string
  page: number
  size: number
  total_before_filter: number
  returned: number
  items: ScreenerItem[]
  filters: {
    keyword: string
    exchange: string
    industry: string
    min_listing_days: number
  }
  note: string
}

export function runScreener(params: {
  keyword?: string
  exchange?: string
  industry?: string
  minListingDays?: number
  page?: number
  size?: number
}): Promise<ScreenerResponse> {
  return request.get('/api/screener/run', { params })
}
