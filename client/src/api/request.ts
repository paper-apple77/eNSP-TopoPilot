import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

/**
 * Axios 封装
 *
 * 每个请求自动做两件事：
 *   1. 请求拦截器：从 localStorage 取 token，加到 Authorization 头
 *   2. 响应拦截器：统一处理错误
 *      - code !== 200 → 弹出错误提示
 *      - 401 → token 过期/失效 → 清除登录态 → 跳转登录页
 */
const request = axios.create({
  baseURL: 'http://localhost:8080/api',
  timeout: 300000,
})

// 请求拦截器：自动带 token
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：统一错误处理
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message))
    }
    return res
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('email')
      router.push('/login')
      ElMessage.error('登录已过期，请重新登录')
    } else {
      ElMessage.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default request
