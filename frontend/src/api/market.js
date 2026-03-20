import request from './request'

export const getKline = (params) => request.get('/market/kline', { params })
