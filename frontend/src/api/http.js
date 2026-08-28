import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 请求拦截:带 token
http.interceptors.request.use(cfg => {
  const token = localStorage.getItem('sparkora_token')
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

// 响应拦截:统一处理 401
http.interceptors.response.use(
  resp => resp.data,
  err => {
    const status = err.response?.status
    if (status === 401) {
      localStorage.removeItem('sparkora_token')
      localStorage.removeItem('sparkora_user')
      // 带上当前路径,登录成功后回跳(与 router.beforeEach、LoginView 的 redirect 约定一致)
      const redirect = router.currentRoute.value.fullPath
      if (router.currentRoute.value.name !== 'login') {
        router.push({ name: 'login', query: redirect && redirect !== '/' ? { redirect } : {} })
      }
      ElMessage.error('登录已过期，请重新登录')
    } else {
      ElMessage.error(err.response?.data?.msg || err.message || '请求失败')
    }
    return Promise.reject(err)
  }
)

export default http
