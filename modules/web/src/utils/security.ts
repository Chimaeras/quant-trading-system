import { marketApi } from '@/api/market'

const nameCache = new Map<string, string>()
const pendingRequests = new Map<string, Promise<string>>()

/** 根据 A 股代码前缀推断交易所，供名称查询使用。 */
export function inferExchangeType(stockCode: string): string {
  const code = stockCode.trim().split('.')[0]
  if (code.startsWith('6') || code.startsWith('5') || code.startsWith('9')) return 'SSE'
  if (code.startsWith('8') || code.startsWith('4')) return 'BSE'
  return 'SZSE'
}

/** 查询证券名称并做前端缓存；查不到时返回空字符串，由页面统一显示 --。 */
export function resolveSecurityName(stockCode: string, exchangeType?: string): Promise<string> {
  const code = stockCode.trim()
  if (!code) return Promise.resolve('')
  const exchange = exchangeType?.trim() || inferExchangeType(code)
  const key = `${code}|${exchange}`
  const cached = nameCache.get(key)
  if (cached) return Promise.resolve(cached)

  const pending = pendingRequests.get(key)
  if (pending) return pending

  const request = marketApi.getStockDetail(code, exchange)
    .then((detail) => {
      const name = String(detail?.stock_name ?? '').trim()
      // 后端对未知证券会以代码作为兜底名称，不把它误认为证券名称。
      if (!name || name === code) return ''
      nameCache.set(key, name)
      return name
    })
    .catch(() => '')
    .finally(() => pendingRequests.delete(key))

  pendingRequests.set(key, request)
  return request
}
