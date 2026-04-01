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

export function generateMockTrades(count = 50) {
  const stocks = generateMockStocks(20)
  const result = []
  for (let i = 0; i < count; i++) {
    const s = stocks[i % stocks.length]
    const price = 10 + Math.random() * 20
    const volume = (100 + Math.floor(Math.random() * 900)) * 100
    const tradeType = i % 2 === 0 ? 1 : 2
    const amount = price * volume
    result.push({
      order_id: 'ORD' + Date.now() + i,
      stock_code: s.stock_code,
      stock_name: s.stock_name,
      trade_type: tradeType,
      trade_price: Number(price.toFixed(2)),
      trade_volume: volume,
      trade_amount: Number(amount.toFixed(2)),
      profit_loss: tradeType === 2 ? (Math.random() - 0.3) * 2000 : undefined,
      trade_time: dayjs().subtract(i * 2, 'day').format('YYYY-MM-DD HH:mm:ss'),
      strategy_name: ['双均线', 'MACD', 'RSI'][i % 3]
    })
  }
  return result.sort((a, b) => (b.trade_time as string).localeCompare(a.trade_time as string))
}

export function generateMockStrategies() {
  return [
    { id: 1, name: 'MA_Cross', strategy_name: 'MA_Cross', strategy_type: 1, language: 'python', params: { short_period: 5, long_period: 20 }, status: 0, total_return: 0, sharpe_ratio: 0, max_drawdown: 0, last_backtest: '', created_at: '2026-03-01', content: '均线金叉死叉策略：短均线(5)上穿长均线(20)买入，下穿卖出' },
    { id: 2, name: 'BOLL_Strategy', strategy_name: 'BOLL_Strategy', strategy_type: 1, language: 'python', params: { period: 20, std_dev: 2 }, status: 0, total_return: 0, sharpe_ratio: 0, max_drawdown: 0, last_backtest: '', created_at: '2026-03-05', content: '布林带策略：价格触及下轨买入，触及上轨卖出' },
    { id: 3, name: 'MACD_Strategy', strategy_name: 'MACD_Strategy', strategy_type: 1, language: 'python', params: { fast: 12, slow: 26, signal: 9 }, status: 0, total_return: 0, sharpe_ratio: 0, max_drawdown: 0, last_backtest: '', created_at: '2026-03-10', content: 'MACD策略：金叉买入，死叉卖出' }
  ]
}
