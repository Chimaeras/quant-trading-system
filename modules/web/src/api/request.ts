import axios, { type AxiosRequestConfig } from 'axios'

interface ApiClient {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T>
}

const axiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000
})

axiosInstance.interceptors.request.use((config) => {
  const token = localStorage.getItem('quant_token')
  if (token) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

axiosInstance.interceptors.response.use(
  (res) => {
    const { code, message } = res.data
    if (code === 200) {
      return res.data
    }
    return Promise.reject(new Error(message || '请求失败'))
  },
  (err) => {
    return Promise.reject(new Error(err.response?.data?.message || err.message || '网络错误'))
  }
)

// 响应拦截器已把 AxiosResponse 解包为后端 ApiResponse，向调用方暴露解包后的类型。
const request = axiosInstance as unknown as ApiClient

/**
 * 从 ApiResponse 响应中解包 data 字段。
 * 拦截器返回 {code, message, data}，这里取 .data；避免在各处散落裸 `as { data?: T }`。
 */
export function unwrap<T>(res: unknown): T {
  return (res as { data?: T })?.data as T
}

export default request
