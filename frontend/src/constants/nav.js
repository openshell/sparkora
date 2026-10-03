/**
 * 模块导航唯一真源。
 * - `icon` 为 @element-plus/icons-vue 组件对象(模板经 <component :is> 渲染,不能放字符串)
 * - `require` 映射 store/user.js:  loggedIn → isLoggedIn, editor → isEditorOrAbove
 * - `matches` 用于高亮与路由一致性断言: 前缀匹配的路径族(如 /projects/* /car/*)
 * - 集合必须与 router/index.js 的 meta.auth 路由一一对应,AppShell 启动时 dev-only 校验
 */
import {
  Files, Picture, Brush, Reading, Setting
} from '@element-plus/icons-vue'

export const NAV_MODULES = [
  { name: '项目',     to: '/',          icon: Files,        require: 'loggedIn', matches: ['/projects'] },
  { name: '图库',     to: '/images',    icon: Picture,      require: 'loggedIn' },
  { name: '风格库',   to: '/styles',    icon: Brush,        require: 'editor' },
  // 知识中心承载车型/知识库/新闻/检索问答四个 tab；/car/sync 与 /car/:id 语义归属知识中心，
  // 由 matches 前缀覆盖（满足 AppShell dev-only「auth 路由必须被导航覆盖」校验）。
  { name: '知识中心', to: '/knowledge', icon: Reading,      require: 'loggedIn', matches: ['/car'] }
]

export const NAV_SETTINGS = { name: '设置', to: '/settings', icon: Setting, require: 'editor' }

/** 路由路径 → 命中的 nav to(模块级高亮);未命中返回 null */
export function navKeyFor(path) {
  if (!path) return null
  const hit = [...NAV_MODULES, NAV_SETTINGS].find((m) => path === m.to || (m.matches || []).some((p) => path.startsWith(p)))
  return hit ? hit.to : null
}