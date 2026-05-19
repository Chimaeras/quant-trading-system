import axios from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.response.use(
  (res) => {
    const { code, data, message } = res.data
    if (code === 200) {
      return res.data
    }
    return Promise.reject(new Error(message || '请求失败'))
  },
  (err) => {
    return Promise.reject(new Error(err.response?.data?.message || err.message || '网络错误'))
  }
)

export default request