import request from './request'

export interface LoginResult {
  token: string
  expiresIn: number
  username: string
  userId: number
}

const TOKEN_KEY = 'quant_token'

export const authApi = {
  async login(username: string, password: string) {
    const res = await request.post<{ data: LoginResult }>('/auth/login', { username, password })
    const data = (res as { data?: LoginResult })?.data as LoginResult
    if (data?.token) {
      localStorage.setItem(TOKEN_KEY, data.token)
    }
    return data
  },

  async register(username: string, password: string) {
    const res = await request.post<{ data: LoginResult }>('/auth/register', { username, password })
    const data = (res as { data?: LoginResult })?.data as LoginResult
    if (data?.token) {
      localStorage.setItem(TOKEN_KEY, data.token)
    }
    return data
  },

  logout() {
    localStorage.removeItem(TOKEN_KEY)
  },

  getToken() {
    return localStorage.getItem(TOKEN_KEY)
  }
}
