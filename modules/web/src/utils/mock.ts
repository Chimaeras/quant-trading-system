/**
 * Mock 数据生成器 - 离线开发使用
 */
import dayjs from 'dayjs'

export function generateMockStocks(count = 100) {
  const names = ['平安银行', '万科A', '国农科技', '深振业A', '中国宝安', '南玻A', '深康佳A', '深中华A', '中金岭南', '农产品']
  const industries = ['银行', '房地产', '科技', '制造业', '消费', '医药', '新能源', '金融']
  const exchanges = [
    { type: 'SZSE', name: '深交所' },
    { type: 'SSE', name: '上交所' }
  ]
  const result = []
  for (let i = 0; i < count; i++) {
    const ex = exchanges[i % 2]
    result.push({
      stock_code: String(600000 + i).padStart(6, '0'),
      stock_name: names[i % names.length] + (i > 9 ? String(i) : ''),
      exchange_type: ex.type,
      exchange_type_name: ex.name,
      industry: industries[i % industries.length],
      status: 1,
      list_date: dayjs().subtract(i * 30, 'day').format('YYYY-MM-DD')
    })
  }
  return result
}

export function generateMockKLine(_stockCode: string, days = 100) {
  const data = []
  let price = 10 + Math.random() * 20
  for (let i = days; i >= 0; i--) {
    const date = dayjs().subtract(i, 'day').format('YYYY-MM-DD')
    const change = (Math.random() - 0.48) * 0.04
    const open = price
    price = price * (1 + change)
    const high = Math.max(open, price) * (1 + Math.random() * 0.01)
    const low = Math.min(open, price) * (1 - Math.random() * 0.01)
    const volume = Math.floor(1000000 + Math.random() * 5000000)
    data.push({
      date,
      open: Number(open.toFixed(2)),
      high: Number(high.toFixed(2)),
      low: Number(low.toFixed(2)),
      close: Number(price.toFixed(2)),
      volume
    })
  }
  return data
}
