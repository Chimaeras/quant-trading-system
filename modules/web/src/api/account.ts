import request, { unwrap } from './request'

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
  availableVolume?: number
  avgPrice: number
  currentPrice: number
  marketValue: number
  profitLoss: number
  profitRate: number
}

export interface OrderHistoryRow {
  orderId: string
  stockCode: string
  stockName?: string
  exchangeType: string
  action: 'buy' | 'sell'
  price: number
  volume: number
  amount: number
  commission?: number
  stampDuty?: number
  tradeTime: string
}

export const accountApi = {
  async getInfo(userId?: number) {
    const res = await request.get<AccountInfo>('/account/info', { params: userId ? { userId } : {} })
    return unwrap(res)
  },

  async listPositions(userId?: number) {
    const res = await request.get<PositionRow[]>('/position/list', { params: userId ? { userId } : {} })
    return unwrap(res) ?? []
  },

  async getOrderHistory(userId?: number) {
    const res = await request.get<OrderHistoryRow[]>('/order/history', { params: userId ? { userId } : {} })
    return unwrap(res) ?? []
  },

  async placeOrder(body: {
    symbol: string
    exchangeType?: string
    action: 'buy' | 'sell'
    volume: number
    price?: number
    tradeDate?: string
  }, userId?: number) {
    const res = await request.post<{
      orderId?: string
      status?: string
      reject_reason?: string | null
      reject_code?: string
      price_source?: string
      trade_date?: string
      [k: string]: unknown
    }>('/order/place', body, {
      params: userId ? { userId } : {}
    })
    return unwrap(res) ?? {}
  }
}
