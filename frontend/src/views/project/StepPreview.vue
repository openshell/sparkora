<template>
  <div class="page preview-page">
    <!-- 前置未就绪 -->
    <div v-if="!previewable" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">尚未生成正文版本</div>
      <div class="state-msg">请先完成「版本」步骤，再进行排版预览。</div>
    </div>

    <template v-else>
      <!-- 工具栏(主题/高亮/开关/宽度/保存状态/动作) -->
      <PreviewToolbar
        :theme="theme" :highlight="highlight" :mac-style="macStyle" :footnote="footnote"
        :preview-width="previewWidth" :theme-options="themeOptions" :highlight-options="highlightOptions"
        :save-state="saveState" :saved-at="savedAt" :saving="saving" :copying="copying" :dirty="dirty"
        :render-error="renderError" :inserted-count="insertedCount" :pending-count="pendingCount"
        @update:theme="(v) => onStyleFieldChange('theme', v)"
        @update:highlight="(v) => onStyleFieldChange('highlight', v)"
        @update:macStyle="(v) => onStyleFieldChange('macStyle', v)"
        @update:footnote="(v) => onStyleFieldChange('footnote', v)"
        @update:previewWidth="onPreviewWidthChange"
        @save="saveContent" @copy="copyRich" @publish="goPublish" @open-images="imgDrawer = true" />

      <!-- 粘贴图失效警示(09-27-image-insert-bugs):暂存区在内存中,整页重载(刷新/标签丢弃)即丢,
           且此时无法再上传。必须显式告知,否则正文里的占位会静默变成一张破图。 -->
      <el-alert
        v-if="unresolvedTokens.length" class="lost-image-alert" type="warning" :closable="false" show-icon>
        <template #title>
          正文含 {{ unresolvedTokens.length }} 张已失效的粘贴图（页面重载后暂存丢失，无法再上传），请重新粘贴或删除占位。
        </template>
      </el-alert>

      <!-- 正文加载失败 -->
      <div v-if="loadError && !loaded" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">正文加载失败</div>
        <div class="state-msg">{{ loadError }}</div>
        <el-button type="primary" plain @click="loadContent">重试</el-button>
      </div>

      <!-- 双栏:左 CodeMirror 编辑 / 右微信样式滚动同步预览(可拖拽分隔条) -->
      <div v-else-if="loaded" ref="splitRef" class="duo" :class="{ dragging: isSplitDragging }">
        <div class="pane pane-left" :style="{ flex: `0 0 ${splitPercent}%` }">
          <div class="pane-head">
            <span>Markdown</span>
            <span class="pane-meta">{{ wordCount }} 字</span>
          </div>
          <div class="editor-wrap">
            <MarkdownEditor
              v-if="editorReady"
              ref="editorRef"
              v-model="contentMd"
              :project-id="projectId"
              @update:model-value="onEdit"
              @scroll="onEditorScroll"
            />
            <div v-else class="editor-loading"><el-skeleton :rows="8" animated /></div>
          </div>
        </div>

        <div
          class="splitter" role="separator" aria-orientation="vertical" tabindex="0"
          :aria-valuenow="Math.round(splitPercent)" :aria-valuemin="30" :aria-valuemax="75"
          title="拖拽(或用方向键)调整编辑/预览分栏比例"
          @pointerdown="onSplitDown" @keydown="onSplitKey"
        ></div>

        <PreviewPane
          ref="previewPaneRef"
          :html="html" :rendering="rendering" :theme-loading="themeLoading" :render-error="renderError"
          :preview-width="previewWidth"
          @scroll="onPreviewScroll" @retry="renderMarkdown" />
      </div>
    </template>

    <!-- 配图抽屉:图库选用 + AI 生图 + 智能建议（实现见 components/preview/PreviewImageDrawer.vue） -->
    <PreviewImageDrawer
      v-model="imgDrawer"
      :project-id="projectId"
      :busy="busy"
      :inserted-urls="insertedUrls"
      :cover-image-id="coverImageId"
      :snapshot="imgSnapshot"
      :is-editor-ready="isEditorReady"
      :insert-at-anchor="insertAtAnchor"
      :refresh-snapshot="refreshImgSnapshot"
      @update:busy="(v) => (busy = v)"
      @insert="insertBodyImage" @set-cover="onSetCover" @generated="onGenerated" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { projectApi, imageApi } from '../../api'
import { useProjectDetailStore } from '../../store/project-detail'
import { applyPreviewTheme, buildWechatHtml } from '../../utils/wenyanRender'
import MarkdownEditor from '../../components/MarkdownEditor.vue'
import PreviewToolbar from '../../components/preview/PreviewToolbar.vue'
import PreviewPane from '../../components/preview/PreviewPane.vue'
import PreviewImageDrawer from '../../components/preview/PreviewImageDrawer.vue'
import { usePreviewStylePersist } from '../../composables/usePreviewStylePersist'
import { usePreviewRender } from '../../composables/usePreviewRender'
import { usePendingImageFlush } from '../../composables/usePendingImageFlush'
import { usePageHeader } from '../../composables/usePageHeader'
import { clearProject, clearOthers, extractTokens, get } from '../../utils/pendingImageStore'
import { parseBodyImageRefs } from '../../utils/bodyImageRefs'
import { ElMessage } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'

/**
 * Step 3 · 排版预览(wenyan web 原版蓝本,Vue 重写)。
 * 渲染编排(对齐 @wenyan-md/ui):
 *  - 正文编辑(400ms 防抖)→ renderMarkdownHtml;主题/高亮/mac → applyPreviewTheme(共享 style 标签,不重渲染)。
 *  - 复制/保存快照 → buildWechatHtml(内联样式,与发布 server 同参)。
 * 左栏 CodeMirror 6;双栏百分比滚动同步;粘贴图片前端暂存(占位 token + 本地 blob 预览,去发布时才上传);草稿 localStorage 暂存。
 *
 * 09-27-split-monoliths：抽 PreviewToolbar/PreviewPane/PreviewImageDrawer + usePreviewStylePersist
 * （图库分页与智能建议分别收在 usePreviewLibrary/usePreviewSuggestions，由抽屉组件持有）。
 */
const props = defineProps({ project: Object })
const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.id)
const store = useProjectDetailStore()

const loaded = ref(false)
const loadError = ref('')
const editorReady = ref(false)
const editorRef = ref(null)
const previewPaneRef = ref(null)
const versionId = ref(null)
const originalMd = ref('')
const contentMd = ref('')
const imgSnapshot = ref(null)   // 配图快照:{images[],coverImageId,bodyImageIds[]}——与 StepPublish 同一接口
const saving = ref(false)
const savedAt = ref('')
const saveState = ref('clean') // clean | dirty | error
const themeOptions = ref([])
const highlightOptions = ref(['solarized-light'])
const theme = ref('default')
const highlight = ref('solarized-light')
const macStyle = ref(true)
const footnote = ref(true)
const previewWidth = ref('phone') // 预览宽度档位:phone | tablet | full(仅视觉)
const copying = ref(false)

// ==== 配图面板状态(图库插入 + AI 生图——生图 UI/逻辑在共用组件 AiImageDrawer.vue) ====
const imgDrawer = ref(false)        // 配图抽屉开关
const busy = ref(false)             // 封面/建议操作中（互斥标记）

const previewable = computed(() => !!props.project && ['VERSIONS_READY', 'PUBLISHED_DRAFT'].includes(props.project.status))
// 底部「去发布」按钮:版本就绪后显示,发布成功(终态)后消失
const canGoPublish = computed(() => !!props.project && props.project.status === 'VERSIONS_READY')
const dirty = computed(() => contentMd.value !== originalMd.value)
const wordCount = computed(() => (contentMd.value || '').replace(/\s/g, '').length)

const draftKey = computed(() => `sparkora-preview-draft-${projectId.value}`)

// ==== 配图快照(与 StepPublish 同一接口数据) ====
const coverImageId = computed(() => imgSnapshot.value?.coverImageId ?? null)

/** 图库图片图床公网 URL(入库即已转存,后端填充 url 字段)。 */
const originUrl = (img) => img?.url || ''                 // 插入正文/大图预览用原图 URL

// ==== 配图数量:唯一口径 = 解析正文图片引用(09-27-image-insert-bugs) ====
// 此前工具栏用「快照 images」当分母(含封面)、分子取「快照∩正文」,发布页却数关联表,
// 三处口径互不一致 → 同一篇文章在不同页面显示不同的配图数,且封面被算成插图。
// 现在两边都调 bodyImageRefs：封面走 cover_image_id,天然不参与正文插图计数。
const bodyImageRefs = computed(() => parseBodyImageRefs(contentMd.value))
/** 已就绪插图数(不含未上传的粘贴图占位)。 */
const insertedCount = computed(() => bodyImageRefs.value.urls.length)
/** token 是否**仍可上传**(条目在且属当前项目);与 usePendingImageFlush 的可上传判定同口径。 */
const isPendingToken = (id) => {
  const e = get(id)
  return !!e && e.projectId === String(projectId.value)
}
/** 待上传的粘贴图占位数(剪贴板暂存图,点「去发布」才转存图床)。
 *  **只算仍可上传的**:已失效的(重载丢失)不叫「待传」——点「去发布」也传不上去,那样展示会误导用户。 */
const pendingCount = computed(() => bodyImageRefs.value.tokenIds.filter(isPendingToken).length)
/** 已失效(暂存丢失,无法再上传)的占位数 → 顶部警示 + 阻断。
 *  取词用 `extractTokens`(与 flushPendingImages 的阻断口径逐字一致)而非图片语法解析:
 *  宁可多提示,也不能出现「无警示却仍被阻断」。 */
const unresolvedTokens = computed(() => extractTokens(contentMd.value).filter((id) => !isPendingToken(id)))
/** 正文已引用的图床 URL 集合:抽屉「已插入」角标。 */
const insertedUrls = computed(() => new Set(bodyImageRefs.value.urls))

// ==== 渲染:正文 400ms 防抖走纯渲染;首次/出错时同样入口 ====
const { html, rendering, renderError, scheduleRender, renderMarkdown, dispose: disposeRender } =
  usePreviewRender(() => contentMd.value, () => loaded.value)

// ==== 剪贴板暂存图:唯一上传触发点是「去发布」;复制/发布防呆 ====
const { goPublish, hasPendingToken } = usePendingImageFlush({
  projectId,
  getContent: () => contentMd.value,
  setContent: (md) => { contentMd.value = md },
  isDirty: () => dirty.value,
  // 以下用 getter 包装：目标 const 声明在本行之后，直接传值会触发 TDZ（调用时已初始化）
  saveContent: () => saveContent(),
  flushStyle: () => flushSavePreviewStyle(),
  goNext: () => router.push({ name: 'project-publish', params: { id: projectId.value } }),
  isSaving: () => saving.value
})

// ==== 上下文条(外壳 topbar):面包屑 + 主 CTA「去发布」(取代原底部 next-row)====
// 挂载在 goPublish 之后:action 直接引用它(避免 TDZ)
const header = usePageHeader()
const syncHeader = () => {
  if (!header) return
  header.crumbs = [{ label: '项目' }, { label: '预览' }]
  header.actions = canGoPublish.value
    ? [{ key: 'publish', label: '去发布 →', type: 'primary', onClick: goPublish, disabled: !!renderError.value }]
    : []
}
syncHeader()
watch([canGoPublish, renderError], syncHeader)
onBeforeRouteLeave(() => { if (header) { header.crumbs = []; header.actions = [] } })

// ==== 分栏比例:可拖拽分隔条,30%~75%,记忆到 localStorage ====
const SPLIT_KEY = 'sparkora.previewSplit'
const splitRef = ref(null)                                   // .duo 容器(换算指针 x → 百分比)
const isSplitDragging = ref(false)
const splitPercent = ref(clampSplit(Number(localStorage.getItem(SPLIT_KEY)) || 50))
function clampSplit(p) { return Math.min(75, Math.max(30, p || 50)) }
const onSplitDown = (e) => {
  isSplitDragging.value = true
  window.addEventListener('pointermove', onSplitMove)
  window.addEventListener('pointerup', onSplitUp)
  e.preventDefault()
}
function onSplitMove(e) {
  const box = splitRef.value
  if (!isSplitDragging.value || !box) return
  const rect = box.getBoundingClientRect()
  if (!rect.width) return
  splitPercent.value = clampSplit(((e.clientX - rect.left) / rect.width) * 100)
}
function onSplitUp() {
  if (!isSplitDragging.value) return
  isSplitDragging.value = false
  window.removeEventListener('pointermove', onSplitMove)
  window.removeEventListener('pointerup', onSplitUp)
  localStorage.setItem(SPLIT_KEY, String(Math.round(splitPercent.value)))
}
/** 键盘可达:左右方向键微调分栏比例 */
const onSplitKey = (e) => {
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
  const step = e.shiftKey ? 10 : 2
  splitPercent.value = clampSplit(splitPercent.value + (e.key === 'ArrowLeft' ? -step : step))
  localStorage.setItem(SPLIT_KEY, String(Math.round(splitPercent.value)))
}

// ==== 预览样式落库(项目级,跨会话保持):防抖 400ms,失败仅 warn 不阻塞预览 ====
const { schedule: scheduleSavePreviewStyle, flush: flushSavePreviewStyle, styleDefaults, applyEffectiveStyle, markApplied, isApplied } =
  usePreviewStylePersist({
    projectId, theme, highlight, macStyle, footnote,
    getProject: () => props.project,
    store
  })

/** 主题/高亮/mac 变更:只替换共享 style 标签(原版机制,不重渲染);并落库项目级样式(防抖)。 */
const themeLoading = ref(false)
const onPreviewStyleChange = async () => {
  themeLoading.value = true
  scheduleSavePreviewStyle()
  try {
    await applyPreviewTheme({ theme: theme.value, highlight: highlight.value, macStyle: macStyle.value, footnote: footnote.value })
  } catch (e) {
    renderError.value = '主题加载失败: ' + (e?.message || e)
  } finally {
    setTimeout(() => { themeLoading.value = false }, 250)
  }
}

/** footnote 变化影响 DOM 结构(脚注区),需要重渲染;并落库。 */
const onWechatRebuild = () => { scheduleRender(); scheduleSavePreviewStyle() }

/** 工具栏样式字段变更:先写回 ref,再按字段走既有「主题重应用 / footnote 重渲染」分支。 */
const onStyleFieldChange = (field, v) => {
  const refs = { theme, highlight, macStyle, footnote }
  refs[field].value = v
  if (field === 'footnote') onWechatRebuild()
  else onPreviewStyleChange()
}

/** 宽度档位仅作用于预览容器视觉,不触发渲染/落库。 */
const onPreviewWidthChange = (v) => { previewWidth.value = v }

/** 编辑器正文变更(v-model 更新 contentMd 后):防抖重渲染预览。手动插图/粘贴图/打字均走此入口。 */
const onEdit = () => {
  scheduleRender()
}

// ==== 滚动同步(百分比映射,防循环) ====
let syncingScroll = null
const onEditorScroll = (percent) => {
  if (syncingScroll === 'preview') return
  syncingScroll = 'editor'
  previewPaneRef.value?.scrollToPercent?.(percent)
  setTimeout(() => { if (syncingScroll === 'editor') syncingScroll = null }, 50)
}
const onPreviewScroll = () => {
  if (!editorRef.value) return
  if (syncingScroll === 'editor') return
  const percent = previewPaneRef.value?.currentPercent?.() ?? 0
  syncingScroll = 'preview'
  editorRef.value.scrollToPercent?.(percent)
  setTimeout(() => { if (syncingScroll === 'preview') syncingScroll = null }, 50)
}

// ==== 数据加载/保存 ====
/** 只刷新配图快照(封面/插图/引用图),不重载正文——供 AI 生成/设封面后轻量更新,避免打断未保存编辑。 */
const refreshImgSnapshot = async () => {
  const res = await imageApi.projectImages(projectId.value)
  if (res.code !== 0) throw new Error(res.msg || '加载失败')
  imgSnapshot.value = {
    images: res.data?.images || [],
    coverImageId: res.data?.coverImageId ?? null,
    bodyImageIds: res.data?.bodyImageIds || [],
    coverImage: res.data?.coverImage ?? null,
    bodyImages: res.data?.bodyImages || []
  }
}
const loadContent = async () => {
  loadError.value = ''
  try {
    const res = await imageApi.projectImages(projectId.value)
    if (res.code !== 0) throw new Error(res.msg || '加载失败')
    const vid = res.data?.currentVersionId
    if (!vid) throw new Error('未找到当前版本')
    // 配图快照留存:封面/插图渲染组装 + 插图面板共用(S10:images=引用图集合 + 服务端解析 coverImage/bodyImages)
    imgSnapshot.value = {
      images: res.data?.images || [],
      coverImageId: res.data?.coverImageId ?? null,
      bodyImageIds: res.data?.bodyImageIds || [],
      coverImage: res.data?.coverImage ?? null,
      bodyImages: res.data?.bodyImages || []
    }
    const vr = await projectApi.listVersions(projectId.value)
    if (vr.code !== 0) throw new Error(vr.msg || '版本加载失败')
    const v = (vr.data || []).find(x => x.id === vid)
    if (!v) throw new Error('当前版本不存在')
    versionId.value = vid
    // 草稿优先(localStorage,防渲染崩溃/误关丢稿),但提供放弃草稿路径
    const draft = localStorage.getItem(draftKey.value)
    originalMd.value = v.contentMd || ''
    if (draft && draft !== originalMd.value) {
      ElMessage({ message: '检测到未保存的本地草稿,已恢复;点「保存正文」持久化或刷新放弃', type: 'warning', duration: 6000 })
      contentMd.value = draft
    } else {
      contentMd.value = originalMd.value
    }
    loaded.value = true
    await nextTick()
    editorReady.value = true
    await nextTick()
    try {
      // 首屏:共享 style 标签注入 + 纯 markdown 渲染
      await Promise.all([
        applyPreviewTheme({ theme: theme.value, highlight: highlight.value, macStyle: macStyle.value, footnote: footnote.value }),
        renderMarkdown()
      ])
    } catch (e) {
      renderError.value = '渲染引擎加载失败: ' + (e?.message || e)
    }
  } catch (e) {
    loadError.value = e?.response?.data?.msg || e?.message || '网络异常'
  }
}

const saveContent = async () => {
  saving.value = true
  saveState.value = 'dirty'
  try {
    const res = await imageApi.saveContent(projectId.value, versionId.value, contentMd.value)
    if (res.code !== 0) throw new Error(res.msg || '保存失败')
    originalMd.value = contentMd.value
    savedAt.value = new Date().toTimeString().slice(0, 5)
    saveState.value = 'clean'
    localStorage.removeItem(draftKey.value)
    ElMessage.success('正文已保存')
    return true
  } catch (e) {
    saveState.value = 'error'
    ElMessage.error(e?.response?.data?.msg || e?.message || '保存失败')
    return false
  } finally { saving.value = false }
}

/** 复制排版:buildWechatHtml 内联输出(与发布同参),富文本进剪贴板。输入纯正文(不含 frontmatter)。
 *  含未上传暂存图(token)时拦截——否则复制出的 HTML 会带失效 src(09-27-preview-clipboard-image R4)。
 *  09-27-image-insert-bugs:拦截文案区分「待上传」与「已失效」——已失效的点「去发布」也传不上去,
 *  统一提示「先去发布」会把用户引到无效操作上(AC2 要求提示可执行)。 */
const copyRich = async () => {
  if (hasPendingToken()) {
    ElMessage.warning(unresolvedTokens.value.length
      ? `正文含 ${unresolvedTokens.value.length} 处已失效的粘贴图（页面重载后暂存丢失），请重新粘贴或删除占位后再复制`
      : '正文含未上传的粘贴图，请先点「去发布」上传后再复制')
    return
  }
  copying.value = true
  try {
    const inline = await buildWechatHtml(contentMd.value || '', {
      theme: theme.value, highlight: highlight.value, macStyle: macStyle.value, footnote: footnote.value
    })
    if (navigator.clipboard && window.ClipboardItem) {
      await navigator.clipboard.write([new ClipboardItem({
        'text/html': new Blob([inline], { type: 'text/html' }),
        'text/plain': new Blob([props.project?.topic || '', inline], { type: 'text/plain' })
      })])
      ElMessage.success('已复制排版,去公众号编辑器粘贴即可')
      return
    }
    throw new Error('浏览器不支持富文本复制')
  } catch (e) {
    ElMessage.error(e?.message || '复制失败')
  } finally { copying.value = false }
}

/** 跳发布步(S5 衔接):实现见 usePendingImageFlush.goPublish(暂存图转存 + 保存 + 跳转;失败停留预览页)。 */

// ==== 配图面板:插入 / 设封面 / AI 生图 ====
const insertBodyImage = (img) => {
  // 2026-09-11-quanzhanlan-broken-image:插入正文必须用原图 URL(originUrl),
  // 不能用 thumbUrl(imageView2+format/webp 派生)——webp 微信素材接口不支持(40113 unsupported file type)
  editorRef.value?.insertMd?.(`\n![](${originUrl(img)})\n`)
  // 09-27-image-insert-bugs:补登记关联表。markdown 才是渲染真值,故先插入再登记;
  // 登记失败只 warn,不能因此回滚已插入的正文(计数口径已改为解析正文,不受影响)。
  if (img?.id) imageApi.addBodyImage(projectId.value, img.id).catch((e) => {
    console.warn('[preview] 插图登记失败（不影响正文）', e)
  })
}

/** 供抽屉内建议采用按标题插入（找不到标题由编辑器内部退回光标处）。 */
const insertAtAnchor = (headingPath, md) => editorRef.value?.insertMdAtAnchor?.(headingPath, md) ?? false
/** 编辑器是否就绪（建议登记前判定：未就绪则不改任何状态）。 */
const isEditorReady = () => !!editorReady.value

const onSetCover = async (imageId) => {
  busy.value = true
  try {
    const res = await imageApi.setCover(projectId.value, imageId)
    if (res.code === 0) {
      ElMessage.success('已设为封面')
      // 只更新本地快照封面,不重载正文(避免打断未保存编辑)
      if (imgSnapshot.value) imgSnapshot.value.coverImageId = imageId
    } else ElMessage.error(res.msg || '设置失败')
  } catch (e) {
    ElMessage.error('设置失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { busy.value = false }
}

/** AI 生图完成（共用组件 emit generated）：仅刷新配图快照。
 *
 *  09-27-image-insert-bugs：**移除「首次 n=1 自动插入正文」**。旧行为会在用户把生成图
 *  设为封面时，顺手把它也塞进正文，于是同一张图既是封面又是插图，而工具栏又把封面计入分母
 *  → 「配图 1/1」这种把封面算作插图的结果。改为只刷新快照，是否插入完全由用户点「插入正文」决定。 */
const onGenerated = async () => {
  try { await refreshImgSnapshot() } catch (e) { /* 快照刷新失败不影响生成结果展示 */ }
}

watch(saveState, (s) => { if (s !== 'dirty') return })
watch(dirty, (d) => {
  if (d) { saveState.value = 'dirty'; localStorage.setItem(draftKey.value, contentMd.value) }
  else saveState.value = 'clean'
})

onMounted(async () => {
  // 挂载即清掉「其他项目」的残留暂存条目(卸载重挂载路径下 watch(projectId) 拿不到旧 id,
  // clearProject 覆盖不到 → blob URL 会话级泄漏;见 pendingImageStore.clearOthers)
  clearOthers(projectId.value)
  try {
    const res = await imageApi.previewOptions()
    if (res.code === 0) {
      themeOptions.value = res.data?.themes || []
      highlightOptions.value = res.data?.highlights || ['solarized-light']
      styleDefaults.value = {
        theme: res.data?.defaultTheme || 'default',
        highlight: res.data?.highlight || 'solarized-light',
        macStyle: res.data?.macStyle ?? true,
        footnote: res.data?.footnote ?? true
      }
      // 项目已到位:应用「项目级样式 + 全局默认」并标记;否则先用全局默认,留给 watch 在项目到位时覆盖
      applyEffectiveStyle()
      if (props.project) markApplied()
    }
  } catch (e) { /* 兜底默认值 */ }
  if (previewable.value) loadContent()
})

// 项目详情晚到:项目级样式到位后重新应用并即时生效(仅首次,避免覆盖用户后续手改)
watch(() => props.project, (p) => {
  if (isApplied() || !p || !styleDefaults.value) return
  applyEffectiveStyle()
  markApplied()
  applyPreviewTheme({ theme: theme.value, highlight: highlight.value, macStyle: macStyle.value, footnote: footnote.value })
    .catch((e) => { renderError.value = '主题加载失败: ' + (e?.message || e) })
})

// 项目切换:释放旧项目的暂存条目与 blob URL(跨项目隔离,09-27-preview-clipboard-image R5.1)
watch(projectId, (newId, oldId) => {
  if (oldId != null && String(oldId) !== String(newId)) clearProject(oldId)
})

watch(previewable, (ok) => { if (ok && !loaded.value && !loadError.value) loadContent() })
onBeforeUnmount(() => { onSplitUp(); disposeRender(); flushSavePreviewStyle() })
</script>

<style scoped>
/* 整页定高:工具栏常驻,双 pane 填满剩余高度并各自内部滚动 */
.preview-page { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.state-error { padding: var(--sp-8) var(--sp-6); }
.state-title { font-weight: 600; margin: var(--sp-4) 0 var(--sp-2); }
.state-msg { color: var(--muted); font-size: var(--fs-13); margin-bottom: var(--sp-5); }

/* 粘贴图失效警示(09-27-image-insert-bugs):工具带之下的条带 */
.lost-image-alert { flex: none; margin-bottom: var(--sp-4); }

/* 双 pane:左编辑 / 右预览,中间可拖拽分隔条(30%~75%,localStorage 记忆) */
.duo { display: flex; align-items: stretch; flex: 1; min-height: 320px; }
.duo.dragging { cursor: col-resize; user-select: none; }
.pane { border: 1px solid var(--line); border-radius: var(--radius-md); overflow: hidden; background: var(--card); display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.pane-head { display: flex; align-items: center; gap: var(--sp-4); padding: var(--sp-3) var(--sp-5); border-bottom: 1px solid var(--line); font-size: var(--fs-12); font-weight: 600; color: var(--muted); background: var(--n-50); flex: none; }
.pane-meta { margin-left: auto; font-weight: 400; }
.editor-wrap { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.editor-wrap > :deep(.cm-host) { flex: 1; }
.editor-loading { padding: var(--sp-7); }

/* 右侧预览 pane(PreviewPane 根元素):吃掉剩余宽度,内部手机框铺满高度 */
.pane-right { flex: 1 1 0; min-width: 0; }
.pane-right :deep(.wechat-body) { max-height: none; min-height: 0; flex: 1; }

/* 分隔条:细竖线,hover/聚焦变品牌色 */
.splitter { position: relative; flex: 0 0 6px; cursor: col-resize; touch-action: none; }
.splitter::after {
  content: ""; position: absolute; top: 0; bottom: 0; left: 2px; width: 2px;
  background: var(--line); transition: background .15s ease;
}
.splitter:hover::after, .splitter:focus-visible::after { background: var(--brand); }
@media (prefers-reduced-motion: reduce) {
  .splitter::after { transition: none; }
}
</style>
