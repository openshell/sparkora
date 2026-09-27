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
 * 09-27-image-insert-bugs：改动两处
 *  - **先持久化、后清理**。此前是「上传 → 替换 → remove(id) → （循环外）saveContent」，
 *    于是「图床已收到对象」与「正文落库 URL」之间存在一个窗口：窗口内任何整页重载
 *    （刷新 / 浏览器标签丢弃 / HMR）都会得到最坏组合——图上有图、正文仍是 token、内存条目已删。
 *    重载后内存必丢 → token 永久失效且**毫无提示**，用户只看到「图没了」。
 *    现在条目延迟到 saveContent 成功后才清理；保存失败则保留条目、可重试。
 *  - 上传成功后 best-effort 补登记 `sparkora_article_version_image`（不再让粘贴图游离于关联表之外）。
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

  /** best-effort 补登记正文插图：失败只 warn（关联表是辅助数据,不能因它阻断发布）。 */
  const registerBodyImage = async (imageId) => {
    if (!imageId) return
    try { await imageApi.addBodyImage(projectId.value, imageId) } catch (e) { /* 忽略 */ }
  }

  /**
   * 执行转存（供内部与测试直接调用）。
   * @returns {Promise<{ok: boolean, reason?: string, changed: boolean, unresolved: number, entriesKept?: boolean}>}
   *   changed=正文是否发生 token→URL 替换（**本函数自己负责把 URL 版正文落库**，调用方无需再存）；
   *   unresolved=正文中无法解析（刷新丢失暂存 / 跨项目 / 已失效）的 token 数，>0 时不可进发布页；
   *   entriesKept=true 表示「已上传但正文未落库」，条目仍留在内存中，用户可直接再点「去发布」。
   *
   * 失败语义：上传失败或**正文保存失败**都返回 ok:false，且此时**不清除任何已上传条目的内存引用**。
   * 重试收敛性（重要）：保存失败时内存正文已是 URL 版（原版仍带 token、dirty 为真），
   * 用户再点「去发布」会先走 goPublish 开头的 `isDirty → saveContent` 把 URL 版落库，
   * 随后本函数 `extractTokens` 为空、changed=false、直接放行 —— 不会「静默不保存正文就跳转」。
   */
  const flushPendingImages = async () => {
    const pid = String(projectId.value ?? '')
    let md = getContent() || ''
    const tokens = extractTokens(md)
    const uploaded = []   // { id, imageId }：已上传并替换进 md，但要等 saveContent 成功才清理
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
        uploaded.push({ id, imageId: res.data?.id })
        changed = true
      } catch (e) {
        const reason = e?.response?.data?.msg || e?.message || '图片上传失败'
        // 前面几张可能已上传并替换进内存正文（尚未落库），条目一律保留 → 提示可重试。
        return { ok: false, reason, changed, unresolved, entriesKept: changed }
      }
    }

    // 关键顺序：正文（URL 版）先落库，成功后才清条目 + 登记关联表。
    // 反过来做（先清后存）会在两步之间留下「图有/正文 token/条目已删」的不可恢复窗口。
    if (changed) {
      const saved = await saveContent()
      if (!saved) {
        return { ok: false, reason: '图片已上传但正文保存失败', changed, unresolved, entriesKept: true }
      }
      for (const u of uploaded) { remove(u.id); registerBodyImage(u.imageId) }
    }

    // 清理「已不被正文引用」的本项目暂存条目（用户删除了占位）：只移除并 revoke，不发上传
    const referenced = new Set(extractTokens(md))
    for (const entry of entries(pid)) if (!referenced.has(entry.id)) remove(entry.id)

    return { ok: true, changed, unresolved }
  }

  /**
   * 「去发布 →」完整编排：dirty 先保存 → flush 暂存图 → 样式落库 → 跳转；失败停留预览页。
   *
   * 正文保存已收敛进 flushPendingImages（它自己知道何时必须落库），此处不再二次保存，
   * 避免「保存两次、两次之间仍可被重载」的窗口残留。
   */
  const goPublish = async () => {
    if (isSaving?.() || goingPublish.value) return
    if (isDirty?.()) {
      const ok = await saveContent()
      if (!ok) return
    }
    goingPublish.value = true
    try {
      const { ok, reason, entriesKept, unresolved } = await flushPendingImages()
      if (!ok) {
        ElMessage.error(entriesKept
          ? `${reason}；已上传部分已保留，可直接再点「去发布」重试`
          : `${reason}（图片未上传，正文仍保留占位，可重试）`)
        return
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
