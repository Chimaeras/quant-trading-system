import request, { unwrap } from './request'

/** V2-MP-028：各就绪度子项（状态 + 说明 + 子字段）。 */
export interface ReadinessItem {
  status: string
  detail?: string
  [k: string]: unknown
}

/** V2-MP-028：系统就绪度。status 由 db/日历/行情时效/数据门禁聚合，与市场时段无关。 */
export interface SystemReadiness {
  status: 'PASS' | 'DEGRADED' | 'BLOCKED'
  db_health: ReadinessItem
  calendar: ReadinessItem & { today_is_trading_day?: boolean }
  data_freshness: ReadinessItem
  data_gate: ReadinessItem
  market_session: {
    today_is_trading_day?: boolean
    phase?: string
    label?: string
    session_window?: string
    note?: string
  }
  checked_at?: string
  server_time?: string
  note?: string
}

export async function getSystemReadiness(): Promise<SystemReadiness> {
  const res = await request.get<SystemReadiness>('/system/readiness')
  return unwrap(res)
}
