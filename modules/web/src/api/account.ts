import request, { unwrap } from './request'

export interface AccountInfo {
  accountId: number
  totalAsset: number
  cash: number
  positionValue: number
  todayPnl: number
  totalProfit: number
  positionCount: number
  /** V2-MP-021：会话模式信息（在线账户为 0 / ONLINE）。 */
  sessionId?: number
  sessionName?: string
  mode?: string
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
  /** V2-MP-021：最早未卖批次建仓日 + 批次数量。 */
  firstBuyDate?: string | null
  lotCount?: number
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
  /** V2-MP-021：成交价来源与仿真会话。 */
  priceSource?: string | null
  sessionId?: number
}

/** V2-MP-021：历史演练会话。 */
export interface SimulationSession {
  sessionId: number
  name?: string
  mode?: string
  status?: string
  baseCash?: number
  cash?: number
  positionValue?: number
  totalProfit?: number
  startDate?: string
  endDate?: string | null
}

/** 订单批次消耗明细（V2-MP-021 订单契约）。 */
export interface LotBreakdownRow {
  orderId?: string
  acquisition_trade_date?: string
  volume?: number
  remaining_volume?: number
  cost_price?: number
}

/** 下单结果（V2-MP-021 契约：operation_date / price_source / lot_breakdown / fee_breakdown）。 */
export interface PlaceOrderResult {
  orderId?: string
  status?: string
  reject_reason?: string | null
  reject_code?: string
  action?: string
  price?: number
  volume?: number
  price_source?: string | null
  operation_date?: string | null
  trade_date?: string | null
  commission?: number
  stamp_duty?: number
  lot_breakdown?: LotBreakdownRow[]
  fee_breakdown?: Record<string, unknown>
  [k: string]: unknown
}

export interface SessionDetail extends SimulationSession {
  asOfDate?: string
  positions?: PositionRow[]
  orders?: OrderHistoryRow[]
}

function sessionParam(userId?: number, sessionId?: number) {
  const params: Record<string, number> = {}
  if (userId != null) params.userId = userId
  if (sessionId != null) params.sessionId = sessionId
  return Object.keys(params).length ? { params } : {}
}

export const accountApi = {
  async getInfo(userId?: number, sessionId?: number) {
    const res = await request.get<AccountInfo>('/account/info', sessionParam(userId, sessionId))
    return unwrap(res)
  },

  async listPositions(userId?: number, sessionId?: number) {
    const res = await request.get<PositionRow[]>('/position/list', sessionParam(userId, sessionId))
    return unwrap(res) ?? []
  },

  async getOrderHistory(userId?: number, sessionId?: number) {
    const res = await request.get<OrderHistoryRow[]>('/order/history', sessionParam(userId, sessionId))
    return unwrap(res) ?? []
  },

  async placeOrder(body: {
    symbol: string
    exchangeType?: string
    action: 'buy' | 'sell'
    volume: number
    price?: number
    tradeDate?: string
    /** V2-MP-021：历史演练会话 id（缺省 = 在线账户，tradeDate 必须为今天）。 */
    sessionId?: number
  }, userId?: number) {
    const res = await request.post<PlaceOrderResult>('/order/place', body, sessionParam(userId))
    return unwrap(res) ?? {}
  },

  /** V2-MP-021：创建历史演练会话。 */
  async createSimSession(body: { name?: string; startDate: string; cash?: number }, userId?: number) {
    const res = await request.post<SimulationSession>('/account/session', body, sessionParam(userId))
    return unwrap(res)
  },

  /** V2-MP-021：演练会话列表。 */
  async listSimSessions(userId?: number) {
    const res = await request.get<SimulationSession[]>('/account/sessions', sessionParam(userId))
    return unwrap(res) ?? []
  },

  /** V2-MP-021：演练会话详情（含该会话持仓与订单）。 */
  async getSimSession(sessionId: number, userId?: number) {
    const res = await request.get<SessionDetail>(`/account/session/${sessionId}`, sessionParam(userId))
    return unwrap(res)
  },

  /** V2-MP-021：关闭演练会话。 */
  async closeSimSession(sessionId: number, userId?: number) {
    const res = await request.post<SimulationSession>(`/account/session/${sessionId}/close`, null, sessionParam(userId))
    return unwrap(res)
  }
}
