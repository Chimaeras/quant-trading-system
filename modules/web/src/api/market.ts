import request from './request'

export interface StockDetail {
  stock_code: string
  stock_name: string
  exchange_type: string
  exchange_type_name?: string
  industry?: string
  trade_date?: string
  open_price?: number
  high_price?: number
  low_price?: number
  close_price?: number
  pre_close?: number
  change_pct?: number
  volume?: number
  amount?: number
  turnover_rate?: number
  pe_ratio?: number
  pb_ratio?: number
}

export interface KLineItem {
  date: string
  open: number
  high: number
  low: number
  close: number
  volume: number
  amount?: number
  change_pct?: number
  turnover_rate?: number
}

export interface StockListItem {
  stock_code: string
  stock_name: string
  exchange_type: string
  exchange_type_name?: string
  industry?: string
  list_date?: string
}

export interface StockAnalysisResult {
  stock_code: string
  exchange: string
  as_of_date: string
  action: 'ADD' | 'REDUCE' | 'HOLD'
  target_position: string
  confidence: number
  risk_lines: {
    support: number
    resistance: number
    stop_loss: number
    take_profit: number
  }
  indicator_snapshot: Record<string, number>
  reasons: string[]
}

/** 后端扩展字段（规则模板）：与 StockAnalysisResult 兼容 */
export interface StockAnalysisSignals {
  ma: 'bullish' | 'bearish' | 'neutral'
  macd: 'bullish' | 'bearish' | 'neutral'
  rsi: 'overbought' | 'oversold' | 'neutral'
  volume: 'strong' | 'weak' | 'normal'
}

export interface StockAnalysisPlan {
  entry?: string
  add?: string
  reduce?: string
  stopLoss: number
  takeProfit: number
  support: number
  resistance: number
  targetPosition: string
}

export interface StockAnalysisReport extends StockAnalysisResult {
  data_insufficient?: boolean
  message?: string
  summary?: string
  risk_points?: string[]
  opportunity_points?: string[]
  tags?: string[]
  signals?: StockAnalysisSignals
  score_breakdown?: {
    trend: number
    momentum: number
    riskPenalty: number
    total: number
  }
  plan?: StockAnalysisPlan
}

export const marketApi = {
  /** 获取股票列表 */
  getStockList(params?: { keyword?: string; exchange?: string; page?: number; size?: number }) {
    return request.get<{ data: { list: StockListItem[]; total: number } }>('/market/stock/list', {
      params
    })
  },

  /** 获取股票详情 */
  getStockDetail(code: string, exchange?: string) {
    return request.get<{ data: StockDetail }>('/market/stock/detail', {
      params: { code, exchange }
    })
  },

  /** 获取实时行情（在线接口） */
  getRealtimeQuote(code: string, exchange?: string) {
    return request.get<{ data: StockDetail }>('/market/realtime', {
      params: { code, exchange },
      headers: { 'Cache-Control': 'no-cache' }
    })
  },

  /** 获取K线数据 */
  getKline(symbol: string, params?: {
    exchange?: string
    startDate?: string
    endDate?: string
    limit?: number
  }) {
    return request.get<{ data: KLineItem[] }>('/market/kline', {
      params: { symbol, ...params }
    })
  },

  /** 单股技术分析（持仓视角） */
  analyzeStock(params: {
    stockCode: string
    exchange?: string
    asOfDate?: string
    holdingQty?: number
    avgCost?: number
    riskProfile?: 'conservative' | 'balanced' | 'aggressive'
  }) {
    return request.post<{ data: StockAnalysisReport }>('/analysis/stock', params)
  }
}
