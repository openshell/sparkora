/**
 * 模块导航唯一真源。
 * - `icon` 为 @element-plus/icons-vue 组件对象(模板经 <component :is> 渲染,不能放字符串)
 * - `require` 映射 store/user.js:  loggedIn → isLoggedIn, editor → isEditorOrAbove
 * - `matches` 用于高亮与路由一致性断言: 前缀匹配的路径族(如 /projects/* /car/*)
 * - 集合必须与 router/index.js 的 meta.auth 路由一一对应,AppShell 启动时 dev-only 校验
 */
import {
  Files, Picture, Brush, Reading, ChatDotRound, Van, Notebook, Setting
} from '@element-plus/icons-vue'

export const NAV_MODULES = [
  { name: '项目',     to: '/',          icon: Files,        require: 'loggedIn', matches: ['/projects'] },
  { name: '图库',     to: '/images',    icon: Picture,      require: 'loggedIn' },
  { name: '风格库',   to: '/styles',    icon: Brush,        require: 'editor' },
  { name: '知识中心', to: '/knowledge', icon: Reading,      require: 'loggedIn' },
  { name: '知识问答', to: '/qa',        icon: ChatDotRound, require: 'loggedIn' },
  { name: '车型库',   to: '/car',       icon: Van,          require: 'loggedIn', matches: ['/car'] },
  { name: '知识库',   to: '/kb',        icon: Notebook,     require: 'loggedIn' }
]

export const NAV_SETTINGS = { name: '设置', to: '/settings', icon: Setting, require: 'editor' }

/** 路由路径 → 命中的 nav to(模块级高亮);未命中返回 null */
export function navKeyFor(path) {
  if (!path) return null
  const hit = [...NAV_MODULES, NAV_SETTINGS].find((m) => path === m.to || (m.matches || []).some((p) => path.startsWith(p)))
  return hit ? hit.to : null
}