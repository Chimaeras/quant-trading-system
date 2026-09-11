import request, { AUTH_TOKEN_KEY, unwrap } from './request'
import { clearUserScopedState } from '@/utils/userScope'

export interface LoginResult {
  token: string
  expiresIn: number
  username: string
  userId: number
}

const TOKEN_KEY = AUTH_TOKEN_KEY

export const authApi = {
  async login(username: string, password: string) {
    const res = await request.post<LoginResult>('/auth/login', { username, password })
    const data = unwrap(res)
    if (data?.token) {
      localStorage.setItem(TOKEN_KEY, data.token)
    }
    return data
  },

  async register(username: string, password: string) {
    const res = await request.post<LoginResult>('/auth/register', { username, password })
    const data = unwrap(res)
    if (data?.token) {
      localStorage.setItem(TOKEN_KEY, data.token)
    }
    return data
  },

  logout() {
    localStorage.removeItem(TOKEN_KEY)
    // 只清 token 不够：任务跟踪键、快照键、名称缓存都是用户级状态，
    // 不清会让下一个登录者继承上一用户的上下文（Pinia 侧由调用方 reset）。
    clearUserScopedState()
  },

  getToken() {
    return localStorage.getItem(TOKEN_KEY)
  },

  isLoggedIn() {
    return !!localStorage.getItem(TOKEN_KEY)
  }
}
