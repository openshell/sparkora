import { ref } from 'vue'
import { projectApi } from '../api'

/**
 * 预览页主题/高亮/Mac/脚注的「项目级样式」落库 + 项目级样式初始化。
 *
 * 从 StepPreview.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 * 防抖 400ms，失败仅 warn（保留待存标记，下次防抖/flush 重试），跳发布前 flush。
 *
 * @param opts
 *   - projectId: 项目 id ref（computed）
 *   - theme/highlight/macStyle/footnote: 样式 ref（由父持有，此处只读写）
 *   - getProject: () => props.project（应用项目级样式时读取 preview*）
 *   - store: useProjectDetailStore 实例（落库后就地回写缓存）
 */
export function usePreviewStylePersist({ projectId, theme, highlight, macStyle, footnote, getProject, store }) {
  let previewStyleTimer = null
  let previewStyleDirty = false

  const schedule = () => {
    previewStyleDirty = true
    clearTimeout(previewStyleTimer)
    previewStyleTimer = setTimeout(save, 400)
  }
  /** 立即落库待保存的样式(去发布前调用,避免 400ms 防抖窗口内跳转导致主题丢失)。 */
  const flush = () => {
    if (!previewStyleDirty) return Promise.resolve()
    clearTimeout(previewStyleTimer)
    previewStyleTimer = null
    return save()
  }
  const save = async () => {
    previewStyleDirty = false
    previewStyleTimer = null
    const patch = { theme: theme.value, highlight: highlight.value, macStyle: macStyle.value, footnote: footnote.value }
    try {
      const res = await projectApi.savePreviewStyle(projectId.value, patch)
      if (res.code !== 0) {
        previewStyleDirty = true // 失败保留待存标记:下次防抖/flush 重试,避免样式静默丢失
        console.warn('[sparkora] 预览样式保存失败:', res.msg)
        return
      }
      // 就地回写 store 缓存,避免子步骤切换时项目缓存陈旧导致样式回退
      store.patchProject(projectId.value, {
        previewTheme: patch.theme, previewHighlight: patch.highlight,
        previewMacStyle: patch.macStyle, previewFootnote: patch.footnote
      })
    } catch (e) {
      previewStyleDirty = true // 同上:网络异常也保留待存标记,跳发布前 flush 仍会重试
      console.warn('[sparkora] 预览样式保存失败:', e?.message || e)
    }
  }

  // 项目级样式优先(09-11-preview-publish-bridge,跨会话保持);缺失字段回退后端全局默认。
  const styleDefaults = ref(null)  // preview-options 下发的全局默认(作为 preview* 缺失字段的回退)
  let styleApplied = false         // 是否已用「带项目级样式」的值初始化过(避免后续覆盖用户手改)
  /** 应用「项目级样式 + 全局默认」到样式 ref；返回是否已应用（styleDefaults 未就绪 false）。 */
  const applyEffectiveStyle = () => {
    const d = styleDefaults.value
    if (!d) return false
    const p = getProject() || {}
    theme.value = p.previewTheme || d.theme
    highlight.value = p.previewHighlight || d.highlight
    macStyle.value = p.previewMacStyle ?? d.macStyle
    footnote.value = p.previewFootnote ?? d.footnote
    return true
  }
  /** 标记「已用带项目级样式的值初始化过」（父在项目已到位时调用，避免 watch 再覆盖用户手改）。 */
  const markApplied = () => { styleApplied = true }
  const isApplied = () => styleApplied

  return { schedule, flush, save, styleDefaults, applyEffectiveStyle, markApplied, isApplied }
}
