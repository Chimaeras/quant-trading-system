import request from './request'

export interface AccountInfo {
  accountId: number
  totalAsset: number
  cash: number
  positionValue: number
  todayPnl: number
  totalProfit: number
  positionCount: number
}

export interface PositionRow {
  symbol: string
  name: string
  exchangeType: string
  volume: number
  avgPrice: number
  currentPrice: number
  marketValue: number
  profitLoss: number
  profitRate: number
}

export const accountApi = {
  async getInfo(userId?: number) {
    const res = await request.get<{ data: AccountInfo }>('/account/info', { params: userId ? { userId } : {} })
    return (res as { data?: AccountInfo })?.data as AccountInfo
  },

  async listPositions(userId?: number) {
    const res = await request.get<{ data: PositionRow[] }>('/position/list', { params: userId ? { userId } : {} })
    return (res as { data?: PositionRow[] })?.data ?? []
  },

  async placeOrder(body: {
    symbol: string
    exchangeType?: string
    action: 'buy' | 'sell'
    volume: number
    price?: number
  }, userId?: number) {
    const res = await request.post<{ data: Record<string, unknown> }>('/order/place', body, {
      params: userId ? { userId } : {}
    })
    return (res as { data?: Record<string, unknown> })?.data ?? {}
  }
}
