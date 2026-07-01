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

export default request
