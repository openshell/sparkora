import { ref } from 'vue'
import { renderMarkdownHtml, sanitizeWenyanHtml } from '../utils/wenyanRender'

/**
 * 预览页正文渲染编排：400ms 防抖 → 纯 markdown 渲染 + 旧结果丢弃（seq）。
 * 从 StepPreview.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 *
 * @param getContent () => string 正文 markdown
 * @param isLoaded   () => boolean 正文是否已加载（未加载不渲染）
 */
export function usePreviewRender(getContent, isLoaded) {
  const html = ref('')
  const rendering = ref(false)
  const renderError = ref('')

  let renderTimer = null
  let renderSeq = 0

  const scheduleRender = () => {
    clearTimeout(renderTimer)
    renderTimer = setTimeout(renderMarkdown, 400)
  }
  const renderMarkdown = async () => {
    if (!isLoaded()) return
    const seq = ++renderSeq
    rendering.value = true
    try {
      const raw = await renderMarkdownHtml(getContent() || '')
      if (seq !== renderSeq) return // 过期结果丢弃
      html.value = sanitizeWenyanHtml(raw)
      renderError.value = ''
    } catch (e) {
      if (seq === renderSeq) renderError.value = '渲染失败: ' + (e?.message || e) + '(正文已本地暂存)'
    } finally {
      if (seq === renderSeq) rendering.value = false
    }
  }
  /** 组件卸载清理防抖 timer。 */
  const dispose = () => clearTimeout(renderTimer)

  return { html, rendering, renderError, scheduleRender, renderMarkdown, dispose }
}
