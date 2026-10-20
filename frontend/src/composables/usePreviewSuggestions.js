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
 *   - saveContent: () => Promise（persist:采纳结算后保存正文;宿主注入唯一保存通道(StepPreview.saveContent,
 *     实为静默变体 saveContentSilent,10-12 R1:成功提示抑制、失败警示仍由宿主完成）,
 *     本组合函数不感知其实现——此处只吞结果与异常,不改变采纳成功口径）
 *   - prepareMediaForInsertion?: (img) => Promise<{ target: string, rollback?: () => Promise }>
 *     （可选；BYD 模式注入 from-image 注册，返回专用 token 目标与插入失败回滚回调。未传时保持公众号关联表路径）
 */
export function usePreviewSuggestions(projectIdRef, { busy, getSnapshot, isEditorReady, insertAtAnchor, originUrl, refreshSnapshot, saveContent, prepareMediaForInsertion = null }) {
  const sugGroups = ref([])          // 按锚点分组的建议: [{anchorKey,anchorIndex,headingPath,anchorText,candidates[]}]
  const sugLoading = ref(false)
  const sugLoaded = ref(false)       // 是否已生成过(区分「未生成」/「生成后无候选」空态)
  const sugTagFilter = ref([])       // 标签预过滤(AND 语义)
  const sugMinScore = ref(0.4)       // 相似度门槛(默认对齐后端建议专用配置 AI_ILLUSTRATION_MIN_SCORE=0.4;与图库搜索 AI_IMAGE_MIN_SCORE=0.3 解耦,10-11 img-suggest-threshold)
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

  /** 单张采用(用户批准):先完成媒体登记，再插入 markdown；插入失败时回滚本次登记。
   *  默认仍是公众号路径（body_image 关联行）；BYD 通过 prepareMediaForInsertion 注入
   *  from-image 注册，返回 `{ target, rollback }`，不写公众号关联表。 */
  const adoptSuggestion = async (group, img, silent) => {
    // candidates 是 ImageSearchHit(record), 其 id 字段名是 imageId(不是 id)——用错会退化成 /images/undefined/body → 400
    const imageId = img?.imageId ?? img?.id
    const url = originUrl(img)
    if (imageId == null) throw new Error('候选图缺少 id,无法登记插图')
    const useExternalRegistration = typeof prepareMediaForInsertion === 'function'
    if (!useExternalRegistration && !url) throw new Error('候选图缺少图床 URL,无法插入正文')
    // 编辑器不可用则直接失败:否则会「登记了但是没插进正文」,发布页计数虚高
    if (typeof insertAtAnchor !== 'function' || (isEditorReady && !isEditorReady())) {
      throw new Error('编辑器未就绪,请稍后重试')
    }

    let md = ''
    let rollback = null
    let alreadyRegistered = false
    if (useExternalRegistration) {
      const prepared = await prepareMediaForInsertion(img)
      rollback = typeof prepared?.rollback === 'function' ? prepared.rollback : null
      const target = String(prepared?.target || '')
      if (!target) {
        if (rollback) {
          try { await rollback() } catch (ignored) { /* 回滚失败由过期清理兜底 */ }
        }
        throw new Error('BYD 媒体登记返回缺少 token')
      }
      md = `\n![图片](${target})\n`
    } else {
      md = `\n![](${url})\n`
      // 是否本次新登记:用于回滚判定——已登记过的图不能因插入失败被移除(会误删用户既有插图)。
      alreadyRegistered = (getSnapshot()?.bodyImageIds || []).map(String).includes(String(imageId))
          || sugAdopted.value.has(imageId)
      const res = await imageApi.addBodyImage(projectIdRef.value, imageId)
      if (res.code !== 0) throw new Error(res.msg || '登记插图失败')
    }

    // 插入正文:优先按锚点标题定位;找不到标题时编辑器内部退回光标处(不丢内容)。
    let inserted = false
    try {
      inserted = insertAtAnchor(group.headingPath, md) !== false
    } catch (e) {
      inserted = false
    }
    if (!inserted) {
      if (rollback) {
        try { await rollback() } catch (ignored) { /* 回滚失败由过期清理兜底 */ }
      } else if (!alreadyRegistered) {
        try { await imageApi.removeBodyImage(projectIdRef.value, imageId) } catch (ignored) { /* 回滚失败:留给用户手动移除 */ }
      }
      throw new Error(useExternalRegistration
        ? '插入正文失败（编辑器未就绪），已回滚媒体登记，请重试'
        : '插入正文失败（编辑器未就绪），已回滚登记，请重试')
    }
    const next = new Set(sugAdopted.value)
    next.add(imageId)
    sugAdopted.value = next
    if (!silent) ElMessage.success(useExternalRegistration ? '已插入到正文' : '已插入到正文并登记插图')
  }

  /** 采纳结算后的正文落库(persist,10-11-img-suggest-adopt-persist):消除「关联行已落、content_md 未落」的半状态窗口。
   *  复用宿主唯一保存通道(saveContent → StepPreview.saveContent,经 prop 注入),不新增保存通道。
   *  提示语义(10-12-preview-leftover-cleanup R1):宿主注入的是静默变体 saveContentSilent——成功「正文已保存」
   *  toast 被抑制(本 composable 自有「已插入到正文并登记插图」/「已采用 N 张」提示),失败中文警示仍由宿主弹出;
   *  此处吞掉返回值与异常——不回滚正文与登记行、不重复弹错;persist 失败不改变「采纳成功」口径。 */
  const persistAfterAdopt = async () => {
    if (typeof saveContent !== 'function') return
    try { await saveContent() } catch (e) { /* 保存失败已在宿主内部提示,仅吞错避免双 toast */ }
  }

  const onAdoptOne = async (group, img) => {
    busy.value = true
    try {
      await adoptSuggestion(group, img)
      // 采纳结算(登记+插入含回滚)后立即落正文;CodeMirror dispatch 同步驱动 v-model,当前 contentMd 已含刚插图
      await persistAfterAdopt()
      try { await refreshSnapshot() } catch (e) { /* 快照刷新失败不影响已完成的写入,仅提示 */ }
    } catch (e) {
      ElMessage.error('采用失败:' + (e.response?.data?.msg || e.message || '网络异常'))
    } finally { busy.value = false }
  }

  /** 整组采用:逐张执行(单张失败不阻断其余,末尾汇总提示)。
   *  倒序插入:每次都插到标题行之后,倒序执行才能让「相关度最高」的排在紧贴标题的第一位(与候选展示序一致)。
   *  整组处理完后**仅触发一次** persist(不逐张保存);组内有失败也不中断,persist 内容=最新 contentMd,与组内成败无关。 */
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
      await persistAfterAdopt()   // 整组结算后一次落正文(R1:N 次采纳 1 次保存)
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
