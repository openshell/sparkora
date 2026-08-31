/**
 * wenyan 浏览器内渲染编排(蓝本:@wenyan-md/ui 的 wenyan.svelte.ts 与 ThemePreview.svelte)。
 *
 * 原版机制(照搬):
 *  1. markdown 变更 → 只重跑纯 markdown→HTML(不碰主题);
 *  2. 主题/高亮/macStyle/footnote 变更 → 只替换三个共享 <style> 标签(即时生效,不重渲染);
 *  3. 复制/对外输出时才把渲染 DOM 走 applyStylesWithResolvedCss 生成内联样式(与发布 server 同核同参)。
 *
 * 主题解析:内置主题走 @wenyan-md/core 注册表;自定义主题(custom:* 前缀,
 * CSS 见 wenyanThemes.js)直传 themeCss——core 的 applyStylesWithTheme/resolveCssContent
 * 均以 themeCss 优先,与官方"直接注入自定义样式"同一入口。
 *
 * 注意:preview 用的共享 style 标签作用于 .wenyan-preview 容器;发布/复制输出是内联样式,
 * 两者分离,保证"预览即发布观感"的同时,预览性能与原版一致。
 */
import { getCustomThemeCss, isCustomTheme } from './wenyanThemes'

const THEME_STYLE_ID = 'wenyan-theme-style'
const HL_STYLE_ID = 'wenyan-hltheme-style'
const MAC_STYLE_ID = 'wenyan-macstyle-style'

let coreInstance = null

async function getCore() {
  if (!coreInstance) {
    const core = await import('@wenyan-md/core')
    coreInstance = await core.createWenyanCore({ mermaid: false })
  }
  return coreInstance
}

/** 主题/高亮 CSS 解析(id→CSS):内置查 core 注册表;custom:* 查社区主题库;失败回退 default。 */
async function resolveThemeCss(id, isHl = false) {
  const core = await import('@wenyan-md/core')
  // 自定义主题只用于文章主题(高亮主题仍用 core 内置)
  if (!isHl) {
    const custom = getCustomThemeCss(id)
    if (custom) return custom
  }
  const lookup = async () => {
    const t = isHl ? core.getHlTheme(id) : core.getTheme(id)
    if (!t) throw new Error(`${isHl ? '高亮主题' : '主题'}不存在: ${id}`)
    return await t.getCss()
  }
  try {
    return await lookup()
  } catch (e) {
    if (id === 'default' || id === 'solarized-light' || isCustomTheme(id)) throw e
    console.warn('[wenyanRender] 回退默认主题:', id, e)
    return await resolveThemeCss(isHl ? 'solarized-light' : 'default', isHl)
  }
}

/** 写/更新共享 <style> 标签(原版 ThemePreview 机制)。 */
function upsertStyle(id, css) {
  let el = document.getElementById(id)
  if (!el) {
    el = document.createElement('style')
    el.id = id
    document.head.appendChild(el)
  }
  el.textContent = css || ''
}

/** 预览容器的选择器前缀(本页所有主题样式都限定在该容器内)。 */
export const PREVIEW_SELECTOR = '.wenyan-preview'

/**
 * ① 纯 markdown→HTML(无主题,与原版 renderMarkdown 对齐)。
 * frontmatter(title/cover)由 core 处理;输入含图片公网 URL。
 */
export async function renderMarkdownHtml(markdown) {
  const inst = await getCore()
  return await inst.renderMarkdown(markdown)
}

/**
 * ② 预览主题应用(原版 ThemePreview 同款:三个共享 style 标签)。
 * 主题/高亮/mac/footnote 任意变更时只调用本函数,不重渲染 markdown。
 * scopeClass:预览容器类名,把主题 CSS 从 #wenyan 重写为 .wenyan-preview 级联。
 */
export async function applyPreviewTheme({ theme = 'default', highlight = 'solarized-light', macStyle = true, footnote = true } = {}) {
  const core = await import('@wenyan-md/core')
  const [themeCss, hlCss] = await Promise.all([
    resolveThemeCss(theme, false),
    resolveThemeCss(highlight, true)
  ])
  const scoped = (css) => css ? css.replaceAll('#wenyan', PREVIEW_SELECTOR) : css
  upsertStyle(THEME_STYLE_ID, scoped(themeCss))
  upsertStyle(HL_STYLE_ID, scoped(hlCss))
  // mac 风格:原版通过单独 style 标签注入 -apple-system 系字体与代码块样式
  upsertStyle(MAC_STYLE_ID, macStyle ? core.getMacStyleCss() : '')
  return { footnote }
}

/** ③ 复制/发布输出:渲染 DOM → 内联样式(与后端 CLI/server 同参,公众号编辑器直接粘贴)。 */
export async function buildWechatHtml(markdown, { theme = 'default', highlight = 'solarized-light', macStyle = true, footnote = true } = {}) {
  const core = await import('@wenyan-md/core')
  const inst = await getCore()
  const html = await inst.renderMarkdown(markdown)
  const dom = new DOMParser().parseFromString(`<body><section id="wenyan">${html}</section></body>`, 'text/html')
  const wenyan = dom.getElementById('wenyan')
  if (isCustomTheme(theme)) {
    // 自定义主题:CSS 直传(与 resolveCssContent 的 themeCss 优先级一致,最终走同一内联器)
    const [themeCss, hlThemeCss] = await Promise.all([
      resolveThemeCss(theme, false),
      resolveThemeCss(highlight, true)
    ])
    await inst.applyStylesWithResolvedCss(wenyan, {
      themeCss, hlThemeCss, isMacStyle: macStyle, isAddFootnote: footnote
    })
  } else {
    await inst.applyStylesWithTheme(wenyan, {
      themeId: theme,
      hlThemeId: highlight,
      isMacStyle: macStyle,
      isAddFootnote: footnote
    })
  }
  return wenyan.outerHTML
}

/** 兼容导出:一次性内联渲染(旧调用点逐步迁移)。 */
export async function renderWenyan(markdown, opts = {}) {
  return buildWechatHtml(markdown, opts)
}

/**
 * v-html 前置清理:防御性移除 script/iframe/事件属性与 javascript: 协议。
 * 保留 class/内联结构(主题样式走容器级 <style>,不在标签上)。
 */
export function sanitizeWenyanHtml(raw) {
  const doc = new DOMParser().parseFromString(raw, 'text/html')
  doc.querySelectorAll('script,iframe,object,embed,link,meta').forEach(el => el.remove())
  doc.querySelectorAll('*').forEach(el => {
    [...el.attributes].forEach(attr => {
      const n = attr.name.toLowerCase()
      if (n.startsWith('on')) el.removeAttribute(attr.name)
      if ((n === 'href' || n === 'src') && /^\s*javascript:/i.test(attr.value)) el.removeAttribute(attr.name)
    })
  })
  return doc.body.innerHTML
}