import axios, { type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'

/** 后端统一响应包装 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  /** V2-MP-028：仅错误响应携带，供页面展示与日志关联。 */
  errorId?: string
}

/** V2-MP-028：统一错误对象，带 HTTP status、业务 code 与 errorId，页面不得吞掉错误为空态。 */
export interface RequestError extends Error {
  status?: number
  code?: number
  errorId?: string
}

/** 组装可展示错误文本：message（+ errorId）。 */
export function errorText(e: unknown): string {
  const err = e as Partial<RequestError> | null
  if (!err) return '未知错误'
  const msg = err.message || '请求失败'
  return err.errorId ? `${msg}（${err.errorId}）` : msg
}

interface ApiClient {
  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<ApiResponse<T>>
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<ApiResponse<T>>
  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<ApiResponse<T>>
  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<ApiResponse<T>>
}

const TOKEN_KEY = 'quant_token'

/** 供其他传输通道（/pyapi 独立客户端）复用同一 token 键。 */
export const AUTH_TOKEN_KEY = TOKEN_KEY

/** 给请求配置装上当前 token；axios 请求拦截器与 /pyapi 客户端共用。 */
export function attachAuthHeader(config: InternalAxiosRequestConfig): InternalAxiosRequestConfig {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}

const axiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000
})

axiosInstance.interceptors.request.use(attachAuthHeader)

/**
 * 401 统一处理：清 token 并跳登录页（携带 redirect）。
 *
 * 导出供 /pyapi 客户端复用——盯盘页此前用裸 fetch，token 过期后既不清态也不跳转，
 * 页面永久停在「服务不可用」，用户没有重新登录的入口。
 */
export function redirectToLoginOn401() {
  localStorage.removeItem(TOKEN_KEY)
  const path = `${window.location.pathname}${window.location.search}`
  if (path.startsWith('/login')) {
    return
  }
  const redirect = encodeURIComponent(path || '/')
  window.location.href = `/login?redirect=${redirect}`
}

export function toRequestError(message: string, status?: number, code?: number, errorId?: string): RequestError {
  const error = new Error(message) as RequestError
  error.status = status
  error.code = code
  error.errorId = errorId
  return error
}

axiosInstance.interceptors.response.use(
  (res) => {
    const { code, message, errorId } = res.data
    if (code === 200) {
      // 返回完整 ApiResponse，由各 API 模块通过 unwrap 取业务 data
      return res.data
    }
    // 业务码 401（部分网关/包装）同样清 token 并跳转登录
    if (code === 401) {
      redirectToLoginOn401()
    }
    // V2-MP-028：非 200 业务码不再退化成纯 message，携带 code/errorId 供页面展示
    return Promise.reject(toRequestError(message || '请求失败', 200, code, errorId))
  },
  (err) => {
    const status = err.response?.status
    if (status === 401) {
      redirectToLoginOn401()
    }
    const msg = err.response?.data?.message || err.message || '网络错误'
    const code = err.response?.data?.code
    const errorId = err.response?.data?.errorId
    return Promise.reject(toRequestError(msg, status, code, errorId))
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
