import request from './request'

export const runBacktest = (params) => request.post('/backtest/run', params)
