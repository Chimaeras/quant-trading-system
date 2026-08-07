import request, { unwrap } from './request'

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
  total_after_filter: number
  total_pages: number
  returned: number
  items: ScreenerItem[]
  filters: {
    keyword: string
    exchange: string
    industry: string
    min_listing_days: number
    exclude_st: boolean
  }
  note: string
}

export async function runScreener(params: {
  keyword?: string
  exchange?: string
  industry?: string
  minListingDays?: number
  excludeSt?: boolean
  page?: number
  size?: number
}): Promise<ScreenerResponse> {
  const res = await request.get<ScreenerResponse>('/screener/run', { params })
  return unwrap(res)
}

export interface ScreenerTemplate {
  id?: number
  user_id: number
  name: string
  keyword?: string
  exchange?: string
  industry?: string
  min_listing_days?: number
  exclude_st?: boolean
  created_at?: string
}

export async function saveScreenerTemplate(params: {
  name: string
  keyword?: string
  exchange?: string
  industry?: string
  minListingDays?: number
  excludeSt?: boolean
  userId?: number
}): Promise<ScreenerTemplate> {
  const res = await request.post<ScreenerTemplate>('/screener/templates/save', params)
  return unwrap(res)
}

export async function listScreenerTemplates(userId?: number): Promise<ScreenerTemplate[]> {
  const res = await request.get<ScreenerTemplate[]>('/screener/templates/list', {
    params: userId ? { userId } : {}
  })
  return unwrap(res) ?? []
}

export async function deleteScreenerTemplate(
  id: number,
  userId?: number
): Promise<{ deleted: boolean }> {
  const res = await request.post<{ deleted: boolean }>('/screener/templates/delete', {
    id,
    ...(userId ? { userId } : {})
  })
  return unwrap(res)
}
