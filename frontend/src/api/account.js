import request from './request'

export const getAccountInfo = () => request.get('/account/info')
