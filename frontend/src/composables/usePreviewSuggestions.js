import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { projectApi, imageApi } from '../api'

/**
 * 预览页智能配图建议（09-15 article-auto-illustrate 子C）。
 *
 * 硬约束：系统只产出建议，**绝不自动写入**；配图进入正文的唯一路径是用户点「插入到此段」/「全部采用」。
 * 从 StepPreview.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 *
 * @param projectIdRef 项目 id ref（computed）
 * @param opts.callbacks
 *   - busy: 宿主共享的忙碌标记 ref（封面/建议互斥，保持原单 ref 语义）
 *   - getSnapshot: () => imgSnapshot.value（含 bodyImageIds）
 *   - isEditorReady: () => boolean（编辑器是否可用；不可用则登记前直接失败，避免计数虚高）
 *   - insertAtAnchor: (headingPath, md) => boolean（editorRef.insertMdAtAnchor）
 *   - originUrl: (img) => string（原图 URL 派生）
 *   - refreshSnapshot: () => Promise（写入后刷新配图快照）
 */
export function usePreviewSuggestions(projectIdRef, { busy, getSnapshot, isEditorReady, insertAtAnchor, originUrl, refreshSnapshot }) {
  const sugGroups = ref([])          // 按锚点分组的建议: [{anchorKey,anchorIndex,headingPath,anchorText,candidates[]}]
  const sugLoading = ref(false)
  const sugLoaded = ref(false)       // 是否已生成过(区分「未生成」/「生成后无候选」空态)
  const sugTagFilter = ref([])       // 标签预过滤(AND 语义)
  const sugMinScore = ref(0.3)       // 相似度门槛(默认与后端 AI_IMAGE_MIN_SCORE 同口径)
  const sugAdopted = ref(new Set())  // 本次会话已采用的图片 id(仅用于候选标记「已采用」)
  const sugDismissedCount = ref(0)   // 本次会话已忽略的锚点数(区分「无候选」与「全被忽略」两种空态)
  const allTags = ref([])            // 全库标签清单(预过滤下拉同源)

  /** 生成建议(可重算,幂等):按锚点分组返回候选;无候选锚点不出现。 */
  const generateSuggestions = async () => {
    sugLoading.value = true
    try {
      const res = await projectApi.illustrationSuggestions(projectIdRef.value, {
        tags: sugTagFilter.value.length ? sugTagFilter.value : undefined,
        minScore: sugMinScore.value
      })
      if (res.code === 0) {
        sugGroups.value = res.data || []
        sugLoaded.value = true
        sugAdopted.value = new Set()
        sugDismissedCount.value = 0   // 本轮重新生成 → 重置:空态文案不沿用上一轮忽略(后端已过滤,本轮不会返回被忽略锚点)
        if (!sugGroups.value.length) ElMessage.info('本次没有匹配到合适配图,可调低门槛或先去图库补图')
      } else ElMessage.error(res.msg || '生成建议失败')
    } catch (e) {
      ElMessage.error('生成建议失败:' + (e.response?.data?.msg || e.message || '网络异常'))
    } finally { sugLoading.value = false }
  }

  /** 加载全库标签清单(预过滤下拉;失败静默,不阻塞建议功能)。 */
  const loadSugTags = async () => {
    if (allTags.value.length) return
    try {
      const res = await imageApi.listTags()
      if (res.code === 0) allTags.value = res.data || []
    } catch (e) { /* 标签清单仅为可选预过滤,失败忽略 */ }
  }

  /** 单张采用(用户批准):① 登记插图关联行(保证发布页计数+防误删) ② markdown 插入锚点处(保证真正渲染)。
   *  二者均幂等;两处都写才自洽——只写 markdown 则发布计数错/图片可能被误删,只写登记则根本不渲染。
   *  注意:① 是可失败的网络/鉴权写,故先做①——失败时正文原样不动,不会留下「已插正文、未登记」的隐性不一致。 */
  const adoptSuggestion = async (group, img, silent) => {
    // candidates 是 ImageSearchHit(record), 其 id 字段名是 imageId(不是 id)——用错会退化成 /images/undefined/body → 400
    const imageId = img?.imageId ?? img?.id
    const url = originUrl(img)
    if (imageId == null) throw new Error('候选图缺少 id,无法登记插图')
    if (!url) throw new Error('候选图缺少图床 URL,无法插入正文')
    // 编辑器不可用则直接失败:否则会「登记了但是没插进正文」,发布页计数虚高
    if (typeof insertAtAnchor !== 'function' || (isEditorReady && !isEditorReady())) {
      throw new Error('编辑器未就绪,请稍后重试')
    }
    const md = `\n![](${url})\n`
    // 是否本次新登记:用于回滚判定——已登记过的图不能因「② 插入失败」被移除(会误删用户既有插图)。
    // 同时看会话内已采用集合:同名图在两组里重复采用时,快照可能尚未刷新,不能误判为「本次新登记」。
    const alreadyRegistered = (getSnapshot()?.bodyImageIds || []).map(String).includes(String(imageId))
        || sugAdopted.value.has(imageId)
    // ① 登记插图关联行(幂等;失败则正文原样不动)
    const res = await imageApi.addBodyImage(projectIdRef.value, imageId)
    if (res.code !== 0) throw new Error(res.msg || '登记插图失败')
    // ② 插入正文:优先按锚点标题定位;找不到标题时编辑器内部退回光标处(不丢内容)。
    //    ② 未插入或抛错则回滚 ①——「两处都写」不能只写一半
    let inserted = false
    try {
      inserted = insertAtAnchor(group.headingPath, md) !== false
    } catch (e) {
      inserted = false
    }
    if (!inserted) {
      if (!alreadyRegistered) {
        try { await imageApi.removeBodyImage(projectIdRef.value, imageId) } catch (ignored) { /* 回滚失败:留给用户手动移除 */ }
      }
      throw new Error('插入正文失败（编辑器未就绪），已回滚登记，请重试')
    }
    const next = new Set(sugAdopted.value)
    next.add(imageId)
    sugAdopted.value = next
    if (!silent) ElMessage.success('已插入到正文并登记插图')
  }

  const onAdoptOne = async (group, img) => {
    busy.value = true
    try {
      await adoptSuggestion(group, img)
      try { await refreshSnapshot() } catch (e) { /* 快照刷新失败不影响已完成的写入,仅提示 */ }
    } catch (e) {
      ElMessage.error('采用失败:' + (e.response?.data?.msg || e.message || '网络异常'))
    } finally { busy.value = false }
  }

  /** 整组采用:逐张执行(单张失败不阻断其余,末尾汇总提示)。
   *  倒序插入:每次都插到标题行之后,倒序执行才能让「相关度最高」的排在紧贴标题的第一位(与候选展示序一致)。 */
  const onAdoptGroup = async (group) => {
    if (!group.candidates?.length) return
    busy.value = true
    let ok = 0, fail = 0
    try {
      for (const img of [...group.candidates].reverse()) {
        try {
          await adoptSuggestion(group, img, true)
          ok++
        } catch (e) { fail++ }
      }
      try { await refreshSnapshot() } catch (e) { /* 快照刷新失败不影响已完成的写入,仅提示 */ }
      if (fail) ElMessage.warning(`已采用 ${ok} 张,${fail} 张失败`)
      else ElMessage.success(`已采用 ${ok} 张`)
    } catch (e) {
      ElMessage.error('采用失败:' + (e.response?.data?.msg || e.message || '网络异常'))
    } finally { busy.value = false }
  }

  /** 忽略该锚点建议组:落库「忽略」记录(幂等),后续生成建议不再推荐该锚点。 */
  const onDismissGroup = async (group) => {
    busy.value = true
    try {
      const res = await projectApi.dismissIllustration(projectIdRef.value, group.anchorKey)
      if (res.code !== 0) throw new Error(res.msg || '忽略失败')
      sugGroups.value = sugGroups.value.filter(g => g.anchorKey !== group.anchorKey)
      sugDismissedCount.value += 1
      ElMessage.success('已忽略此段建议')
    } catch (e) {
      ElMessage.error('忽略失败:' + (e.response?.data?.msg || e.message || '网络异常'))
    } finally { busy.value = false }
  }

  return {
    sugGroups, sugLoading, sugLoaded, sugTagFilter, sugMinScore, sugAdopted, sugDismissedCount, allTags,
    generateSuggestions, loadSugTags, adoptSuggestion, onAdoptOne, onAdoptGroup, onDismissGroup
  }
}
