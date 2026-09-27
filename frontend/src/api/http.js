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
      // 401 恒定处理，不受调用方抑制影响（登录态失效必须无条件跳登录）
      localStorage.removeItem('sparkora_token')
      localStorage.removeItem('sparkora_user')
      // 带上当前路径,登录成功后回跳(与 router.beforeEach、LoginView 的 redirect 约定一致)
      const redirect = router.currentRoute.value.fullPath
      if (router.currentRoute.value.name !== 'login') {
        router.push({ name: 'login', query: redirect && redirect !== '/' ? { redirect } : {} })
      }
      ElMessage.error('登录已过期，请重新登录')
    } else if (!err.config?.skipGlobalErrorToast) {
      // 调用方要做自定义错误分层（取消 / 超时 / 传输层分流，见 AiImageDrawer 的 reportGenError）时，
      // 由该请求传 skipGlobalErrorToast:true 关掉这里的全局提示——否则会弹**两个** toast，且这里的
      // 文案是 axios 框架串（超时「timeout of 300000ms exceeded」、断网「Network Error」、取消「canceled」），
      // 会把「已取消」显示成红色英文错误（同 error-handling.md「禁止把框架内部异常串透给用户」）。
      ElMessage.error(err.response?.data?.msg || err.message || '请求失败')
    }
    return Promise.reject(err)
  }
)

export default http
