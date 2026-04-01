/** 股票信息 */
export interface StockInfo {
  id?: number
  stock_code: string
  stock_name: string
  exchange_type: string
  exchange_type_name?: string
  industry?: string
  sector?: string
  list_date?: string
  status?: number
}

/** 行情数据 OHLCV */
export interface MarketData {
  stock_code: string
  exchange_type: string
  trade_date: string
  open_price: number
  high_price: number
  low_price: number
  close_price: number
  volume?: number
  amount?: number
}

/** K线数据(前端用) */
export interface KLineData {
  date: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

/** 数据概览 */
export interface DataOverview {
  stock_count: number
  earliest_date: string
  latest_date: string
  completeness: number
  storage_size: string
}

/** 策略指标参数 */
export interface StrategyParams {
  short_period?: number
  long_period?: number
  period?: number
  std_dev?: number
  fast?: number
  slow?: number
  signal?: number
}

/** 策略信息 */
export interface StrategyInfo {
  id?: number
  strategy_code?: string
  strategy_name?: string
  name?: string
  strategy_type?: number
  language?: string
  content?: string
  params?: StrategyParams
  status?: number
  total_return?: number
  sharpe_ratio?: number
  max_drawdown?: number
  last_backtest?: string
  created_at?: string
}

/** 回测结果 */
export interface BacktestResult {
  total_return: number
  annual_return: number
  sharpe_ratio: number
  max_drawdown: number
  total_trades: number
  win_rate: number
  total_cost?: number
  result_id?: string | null
  equity_curve: Array<{ date: string; equity: number }>
}

/** 回测历史记录 */
export interface BacktestHistoryItem {
  result_id: string
  strategy_id?: number
  strategy_name?: string
  start_date?: string
  end_date?: string
  initial_capital?: number
  final_capital?: number
  total_return?: number
  annual_return?: number
  sharpe_ratio?: number
  max_drawdown?: number
  total_trades?: number
  win_rate?: number
  status?: number
  run_time?: number
  created_at?: string
  params?: Record<string, unknown>
}

/** 交易记录 */
export interface TradeRecord {
  id?: number
  order_id: string
  stock_code: string
  stock_name: string
  trade_type: number // 1-买入 2-卖出
  trade_price: number
  trade_volume: number
  trade_amount: number
  profit_loss?: number
  trade_time: string
  strategy_name?: string
}

/** 持仓信息 */
export interface PositionInfo {
  stock_code: string
  stock_name: string
  volume: number
  avg_cost: number
  current_price: number
  market_value: number
  profit_loss: number
  profit_rate: number
}
