<template>
  <el-card class="step-card" shadow="never">
    <template #header>
      <span class="card-head">
        <span class="head-main">
          <span class="step-title serif">Step 3 · 排版预览</span>
          <span class="step-sub">微信样式实时预览 · 与发布同源(文颜)</span>
        </span>
        <span class="meta">左编辑 · 右预览</span>
      </span>
    </template>

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
        :render-error="renderError" :inserted-count="insertedCount" :snapshot-count="snapshotImages.length"
        @update:theme="(v) => onStyleFieldChange('theme', v)"
        @update:highlight="(v) => onStyleFieldChange('highlight', v)"
        @update:macStyle="(v) => onStyleFieldChange('macStyle', v)"
        @update:footnote="(v) => onStyleFieldChange('footnote', v)"
        @update:previewWidth="onPreviewWidthChange"
        @save="saveContent" @copy="copyRich" @publish="goPublish" @open-images="imgDrawer = true" />

      <!-- 正文加载失败 -->
      <div v-if="loadError && !loaded" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">正文加载失败</div>
        <div class="state-msg">{{ loadError }}</div>
        <el-button type="primary" plain @click="loadContent">重试</el-button>
      </div>

      <!-- 双栏:左 CodeMirror 编辑 / 右微信样式滚动同步预览 -->
      <div v-else-if="loaded" class="duo">
        <div class="pane pane-left">
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

        <PreviewPane
          ref="previewPaneRef"
          :html="html" :rendering="rendering" :theme-loading="themeLoading" :render-error="renderError"
          :preview-width="previewWidth"
          @scroll="onPreviewScroll" @retry="renderMarkdown" />
      </div>

      <!-- 底部:进入下一步(与简报/版本同款 next-row;发布成功后消失) -->
      <div v-if="canGoPublish" class="next-row">
        <el-button type="success" :disabled="!!renderError" @click="goPublish">去发布 →</el-button>
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
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectApi, imageApi } from '../../api'
import { useProjectDetailStore } from '../../store/project-detail'
import { applyPreviewTheme, buildWechatHtml } from '../../utils/wenyanRender'
import MarkdownEditor from '../../components/MarkdownEditor.vue'
import PreviewToolbar from '../../components/preview/PreviewToolbar.vue'
import PreviewPane from '../../components/preview/PreviewPane.vue'
import PreviewImageDrawer from '../../components/preview/PreviewImageDrawer.vue'
import { usePreviewStylePersist } from '../../composables/usePreviewStylePersist'
import { usePreviewRender } from '../../composables/usePreviewRender'
import { ElMessage } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'

/**
 * Step 3 · 排版预览(wenyan web 原版蓝本,Vue 重写)。
 * 渲染编排(对齐 @wenyan-md/ui):
 *  - 正文编辑(400ms 防抖)→ renderMarkdownHtml;主题/高亮/mac → applyPreviewTheme(共享 style 标签,不重渲染)。
 *  - 复制/保存快照 → buildWechatHtml(内联样式,与发布 server 同参)。
 * 左栏 CodeMirror 6;双栏百分比滚动同步;粘贴图片自动上传;草稿 localStorage 暂存。
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

// ==== 配图快照口径(与 StepPublish 同一接口数据;S10 起 images=当前版本引用图集合) ====
const snapshotImages = computed(() => imgSnapshot.value?.images || [])
const coverImageId = computed(() => imgSnapshot.value?.coverImageId ?? null)

/** 正文已引用的本地 URL 集合:面板「已插入」状态与后端组装去重口径一致(含 URL 即视为已插入)。
 *  S10 起基于「当前版本引用图集合」计算(全量图库已分页化,未引用图不可能出现在正文——插入动作即产生引用)。 */
const insertedUrls = computed(() => {
  const body = contentMd.value || ''
  return new Set(snapshotImages.value.map(img => originUrl(img)).filter(u => body.includes(u)))
})
/** 已插入正文的插图数量(配图按钮角标)。 */
const insertedCount = computed(() => insertedUrls.value.size)
/** 图库图片图床公网 URL(入库即已转存,后端填充 url 字段)。 */
const originUrl = (img) => img?.url || ''                 // 插入正文/大图预览用原图 URL

// ==== 渲染:正文 400ms 防抖走纯渲染;首次/出错时同样入口 ====
const { html, rendering, renderError, scheduleRender, renderMarkdown, dispose: disposeRender } =
  usePreviewRender(() => contentMd.value, () => loaded.value)

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

/** 复制排版:buildWechatHtml 内联输出(与发布同参),富文本进剪贴板。输入纯正文(不含 frontmatter)。 */
const copyRich = async () => {
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

/** 跳发布步(S5 衔接):正文有未保存修改时先自动保存,成功才跳转;失败停留预览页。 */
const goPublish = async () => {
  if (saving.value) return
  if (dirty.value) {
    const ok = await saveContent()
    if (!ok) return
  }
  // 样式防抖窗口内直接跳转会把「刚选的主题」丢掉;跳转前立即落库
  await flushSavePreviewStyle()
  router.push({ name: 'project-publish', params: { id: projectId.value } })
}

// ==== 配图面板:插入 / 设封面 / AI 生图 ====
const insertBodyImage = (img) => {
  // 2026-09-11-quanzhanlan-broken-image:插入正文必须用原图 URL(originUrl),
  // 不能用 thumbUrl(imageView2+format/webp 派生)——webp 微信素材接口不支持(40113 unsupported file type)
  editorRef.value?.insertMd?.(`\n![](${originUrl(img)})\n`)
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

/** AI 生图完成（共用组件 emit generated）：刷新配图快照；**首次生成** n=1 沿用旧行为自动插入正文光标处
 *  （重生成不自动插入——与既有 S10 行为一致，重生成结果由用户点「插入正文」）。 */
const onGenerated = async (list, meta) => {
  try { await refreshImgSnapshot() } catch (e) { /* 快照刷新失败不影响生成结果展示 */ }
  if (meta?.reason === 'generate' && list?.length === 1 && list[0]?.id) insertBodyImage(list[0])
}

watch(saveState, (s) => { if (s !== 'dirty') return })
watch(dirty, (d) => {
  if (d) { saveState.value = 'dirty'; localStorage.setItem(draftKey.value, contentMd.value) }
  else saveState.value = 'clean'
})

onMounted(async () => {
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

watch(previewable, (ok) => { if (ok && !loaded.value && !loadError.value) loadContent() })
onBeforeUnmount(() => { disposeRender(); flushSavePreviewStyle() })
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: baseline; width: 100%; gap: 12px; }
.head-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.step-title { font-size: 16px; font-weight: 700; }
.step-sub { font-size: 12px; color: var(--faint); }
.card-head .meta { font-size: 12px; color: var(--muted); }
.state-error { padding: 36px 16px; }
.state-title { font-weight: 700; margin: 8px 0 4px; }
.state-msg { color: var(--muted); font-size: 13px; margin-bottom: 12px; }

.duo { display: grid; grid-template-columns: minmax(280px, 5fr) minmax(320px, 7fr); gap: 16px; align-items: stretch; }
.pane { border: 1px solid var(--line); border-radius: var(--radius-sm); overflow: hidden; background: var(--paper); display: flex; flex-direction: column; }
.pane-head { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--line); font-size: 12px; font-weight: 600; color: var(--muted); background: var(--el-fill-color-light); }
.pane-meta { margin-left: auto; font-weight: 400; }
.editor-wrap { flex: 1; min-height: 620px; max-height: 720px; display: flex; flex-direction: column; }
.editor-wrap > :deep(.cm-host) { flex: 1; }
.editor-loading { padding: 24px; }

/* 底部进入下一步(与简报/版本同款 next-row) */
.next-row { margin-top: 18px; display: flex; gap: 8px; flex-wrap: wrap; }
.next-row .el-button:last-child { margin-left: auto; }

@media (max-width: 900px) {
  .duo { grid-template-columns: 1fr; }
}
</style>
