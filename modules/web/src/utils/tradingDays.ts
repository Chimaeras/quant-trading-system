import type { Dayjs } from 'dayjs'
import dayjs from 'dayjs'

/** 是否为周一～周五（不含法定节假日） */
export function isWeekday(d: Dayjs): boolean {
  const n = d.day()
  return n !== 0 && n !== 6
}

/** 不晚于 d 的最近一个「工作日」：用于近似最近交易日（未剔除法定节假日） */
export function lastWeekdayOnOrBefore(d: Dayjs): Dayjs {
  let x = d
  while (!isWeekday(x)) {
    x = x.subtract(1, 'day')
  }
  return x
}

/**
 * 从 end（须为工作日）向前数，使闭区间 [start, end] 内恰好包含 n 个交易日（含 end）。
 * n >= 1。
 */
export function startOfTradingDaysWindowInclusive(end: Dayjs, n: number): Dayjs {
  if (n < 1) n = 1
  let cur = end
  let remaining = n - 1
  while (remaining > 0) {
    cur = cur.subtract(1, 'day')
    if (isWeekday(cur)) remaining--
  }
  return cur
}

/** 每个快捷选项对应的「交易日个数」（闭区间条数） */
export const PRESET_TRADING_DAY_COUNTS = {
  d7: 7,
  m1: 20,
  m3: 60,
  m6: 120,
  y1: 250,
  y2: 500
} as const

export type PresetKey = keyof typeof PRESET_TRADING_DAY_COUNTS

export function applyTradingDayPreset(key: PresetKey, now: Dayjs = dayjs()): { start: Dayjs; end: Dayjs } {
  const end = lastWeekdayOnOrBefore(now)
  const n = PRESET_TRADING_DAY_COUNTS[key]
  const start = startOfTradingDaysWindowInclusive(end, n)
  return { start, end }
}
