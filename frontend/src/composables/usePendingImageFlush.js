import { ref } from 'vue'
import { imageApi } from '../api'
import { entries, extractTokens, get, hasToken, remove, replaceToken } from '../utils/pendingImageStore'
import { ElMessage } from 'element-plus'

/**
 * 预览页暂存图的「发布前转存」编排（09-27-preview-clipboard-image）。
 *
 * 唯一上传触发点 = 预览页「去发布 →」：把正文中**仍被引用**的暂存图逐张 `imageApi.upload` 上传图床，
 * 成功后将其 token 精确替换为图床公网 URL；未被引用的暂存条目直接清理（不发上传）。
 * 上传串行（粘贴量小、错误定位清晰）；任一张失败即中止，保留全部未成功条目供重试。
 *
 * @param opts
 *   - projectId: 项目 id ref（computed）
 *   - getContent: () => string 当前正文 markdown
 *   - setContent: (md) => void 回写正文 ref（替换 token 后即时反映到编辑器/预览）
 *   - isDirty:   () => boolean 正文是否有未保存修改
 *   - saveContent: () => Promise<boolean> 保存正文（带提示/错误态，失败 false）
 *   - flushStyle: () => Promise<void> 立即落库预览样式（跳转前调，避免防抖窗口丢主题）
 *   - goNext:    () => void 跳转发布页
 *   - isSaving:  () => boolean 正文保存中（与 goPublish 互斥）
 */
export function usePendingImageFlush({ projectId, getContent, setContent, isDirty, saveContent, flushStyle, goNext, isSaving }) {
  const goingPublish = ref(false)   // 去发布进行中(暂存图上传/保存),防重复点击

  /**
   * 执行转存（供内部与测试直接调用）。
   * @returns {Promise<{ok: boolean, reason?: string, changed: boolean, unresolved: number}>}
   *   changed=正文是否发生 token→URL 替换（决定是否需要再保存一次正文）；
   *   unresolved=正文中无法解析（刷新丢失暂存 / 跨项目 / 已失效）的 token 数，>0 时不可进发布页。
   */
  const flushPendingImages = async () => {
    const pid = String(projectId.value ?? '')
    let md = getContent() || ''
    const tokens = extractTokens(md)
    let changed = false
    let unresolved = 0

    for (const id of tokens) {
      const entry = get(id)
      // 非本项目 / 已失效 token：不误上传，计入未解析（由调用方阻止进发布页并提示重贴）
      if (!entry || entry.projectId !== pid) { unresolved += 1; continue }
      try {
        const res = await imageApi.upload(pid, entry.file)
        if (res.code !== 0) throw new Error(res.msg || '上传失败')
        const url = res.data?.url
        if (!url) throw new Error('上传返回缺少 url')
        md = replaceToken(md, id, url)
        setContent(md)
        remove(id)
        changed = true
      } catch (e) {
        const reason = e?.response?.data?.msg || e?.message || '图片上传失败'
        return { ok: false, reason, changed, unresolved }
      }
    }

    // 清理「已不被正文引用」的本项目暂存条目（用户删除了占位）：只移除并 revoke，不发上传
    const referenced = new Set(extractTokens(md))
    for (const entry of entries(pid)) if (!referenced.has(entry.id)) remove(entry.id)

    return { ok: true, changed, unresolved }
  }

  /**
   * 「去发布 →」完整编排：dirty 先保存 → flush 暂存图 → 最终正文落库 → 样式落库 → 跳转；失败停留预览页。
   */
  const goPublish = async () => {
    if (isSaving?.() || goingPublish.value) return
    if (isDirty?.()) {
      const ok = await saveContent()
      if (!ok) return
    }
    goingPublish.value = true
    try {
      const { ok, reason, changed, unresolved } = await flushPendingImages()
      if (!ok) {
        ElMessage.error(`${reason}(图片未上传,可重试;正文仍保留占位)`)
        return
      }
      if (changed) {
        // 正文 token 已替换为图床 URL:落库最终正文,使发布链路读到的是公网 URL 版本
        const saved = await saveContent()
        if (!saved) { ElMessage.error('暂存图已上传但正文保存失败,请点「保存正文」后重试'); return }
      }
      if (unresolved > 0) {
        // 刷新/跨项目导致暂存丢失的残留 token:不上传也无法替换,阻止发布并提示重贴
        ElMessage.error(`正文含 ${unresolved} 处失效的粘贴图占位(刷新后暂存丢失),请删除占位或重新粘贴后再发布`)
        return
      }
      // 样式防抖窗口内直接跳转会把「刚选的主题」丢掉;跳转前立即落库
      await flushStyle()
      goNext()
    } finally { goingPublish.value = false }
  }

  /** 正文是否含未上传占位（复制排版拦截 / 发布前判断；只做存在性检查）。 */
  const hasPendingToken = () => hasToken(getContent())

  return { goPublish, flushPendingImages, hasPendingToken, goingPublish }
}
