import { ref } from 'vue'
import { renderMarkdownHtml, sanitizeWenyanHtml } from '../utils/wenyanRender'
import { mapTokenSrc, previewUrl } from '../utils/pendingImageStore'

/**
 * 预览页正文渲染编排：400ms 防抖 → 纯 markdown 渲染 + 旧结果丢弃（seq）。
 * 从 StepPreview.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 *
 * 09-27-preview-clipboard-image：渲染产物中 `sparkora-img:<id>` 的 src 在**预览投影**层
 * 替换为暂存图 blob URL（即时可见）。已实测 @wenyan-md/core(marked 15) 对
 * `![](sparkora-img:<id>)` 原样输出 `<img src="sparkora-img:<id>">`；替换只作用于展示 HTML，
 * **落库正文始终保留 token**，上传替换在「去发布」时进行。
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
      // 暂存 token → 本地 blob URL（仅预览投影，落库正文不动）
      html.value = sanitizeWenyanHtml(mapTokenSrc(raw, previewUrl))
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
