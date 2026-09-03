/**
 * 官方社区主题库(来源: wenyan 官方主题分享页 https://yuzhi.tech/docs/wenyan/themes,Apache-2.0 生态)。
 * 7 个 mdnice 社区主题 CSS 静态打包(import 后由 Vite 内联);id 带 custom: 前缀与内置主题区分。
 * 与 @wenyan-md/core 的 getTheme 解耦:resolve 时优先内置注册表,未命中查本库。
 */
// Vite 静态资源按字符串导入(?raw),构建期打进 chunk,无运行时请求
import chazi from '../assets/wenyan-themes/chazi.css?raw'
import mohei from '../assets/wenyan-themes/mohei.css?raw'
import nenqin from '../assets/wenyan-themes/nenqin.css?raw'
import hongfei from '../assets/wenyan-themes/hongfei.css?raw'
import lanqing from '../assets/wenyan-themes/lanqing.css?raw'
import shanchui from '../assets/wenyan-themes/shanchui.css?raw'
import quanzhanlan from '../assets/wenyan-themes/quanzhanlan.css?raw'

/** id 规范:custom:<themeId>(与原版 wenyan-ui 的持久化自定义主题前缀约定一致) */
export const CUSTOM_THEMES = [
  { id: 'custom:chazi', name: '姹紫', color: '#773098' },
  { id: 'custom:mohei', name: '墨黑', color: '#5c5c5c' },
  { id: 'custom:nenqin', name: '嫩青', color: '#47c1a8' },
  { id: 'custom:hongfei', name: '红绯', color: '#f83929' },
  { id: 'custom:lanqing', name: '兰青', color: '#009688' },
  { id: 'custom:shanchui', name: '山吹', color: '#ffb11b' },
  { id: 'custom:quanzhanlan', name: '全栈蓝', color: '#3594f7' }
]

const CSS_STORE = {
  'custom:chazi': chazi,
  'custom:mohei': mohei,
  'custom:nenqin': nenqin,
  'custom:hongfei': hongfei,
  'custom:lanqing': lanqing,
  'custom:shanchui': shanchui,
  'custom:quanzhanlan': quanzhanlan
}

/** 解析主题 id → CSS 文本;内置 id 返回 null(交回 core 注册表),自定义命中返回 CSS,否则 null。 */
export function getCustomThemeCss(id) {
  return Object.prototype.hasOwnProperty.call(CSS_STORE, id) ? CSS_STORE[id] : null
}

export function isCustomTheme(id) {
  return typeof id === 'string' && id.startsWith('custom:')
}