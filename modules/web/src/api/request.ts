import axios, { type AxiosRequestConfig } from 'axios'

/** 后端统一响应包装 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

interface ApiClient {
  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<ApiResponse<T>>
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<ApiResponse<T>>
  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<ApiResponse<T>>
  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<ApiResponse<T>>
}

const TOKEN_KEY = 'quant_token'

const axiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000
})

axiosInstance.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function redirectToLoginOn401() {
  localStorage.removeItem(TOKEN_KEY)
  const path = `${window.location.pathname}${window.location.search}`
  if (path.startsWith('/login')) {
    return
  }
  const redirect = encodeURIComponent(path || '/')
  window.location.href = `/login?redirect=${redirect}`
}

axiosInstance.interceptors.response.use(
  (res) => {
    const { code, message } = res.data
    if (code === 200) {
      // 返回完整 ApiResponse，由各 API 模块通过 unwrap 取业务 data
      return res.data
    }
    // 业务码 401（部分网关/包装）同样清 token 并跳转登录
    if (code === 401) {
      redirectToLoginOn401()
    }
    return Promise.reject(new Error(message || '请求失败'))
  },
  (err) => {
    const status = err.response?.status
    if (status === 401) {
      redirectToLoginOn401()
    }
    const msg = err.response?.data?.message || err.message || '网络错误'
    const error = new Error(msg) as Error & { status?: number; code?: number }
    error.status = status
    error.code = err.response?.data?.code
    return Promise.reject(error)
  }
)

const request = axiosInstance as unknown as ApiClient

/**
 * 从 ApiResponse 响应中解包 data 字段。
 * 拦截器返回 {code, message, data}，这里取 .data；API 模块一律对页面返回业务 T。
 */
export function unwrap<T>(res: ApiResponse<T>): T {
  return res.data
}

export default request
