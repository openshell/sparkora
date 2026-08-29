import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import router from './router'
import { useThemeStore } from './store/theme'
import './assets/main.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)

// 全局兜底:渲染/钩子层抛错不再静默白屏,给出可感知的错误提示。
// 业务请求错误仍走 http.js 拦截器;401 跳登录由其内部处理,这里不重复。
app.config.errorHandler = (err, _instance, info) => {
  // 异步组件加载失败(Chromium 偶发模块加载断连)等,提示用户刷新恢复
  if (/Maximum recursive|Failed to fetch|Loading chunk|error loading dynamically imported/i.test(String(err?.message || err))) {
    console.error('[sparkora] 模块加载失败:', info, err)
    import('element-plus').then(({ ElMessage }) => {
      ElMessage.error('页面资源加载失败，请刷新重试；若反复出现请清缓存后重启 dev server')
    }).catch(() => {})
    return
  }
  console.error('[sparkora] 未处理异常:', info, err)
}

app.mount('#app')

// 启动时恢复上次选择的主题(localStorage 记忆)
useThemeStore().apply()
