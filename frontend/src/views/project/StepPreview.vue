<template>
  <el-card class="step-card" shadow="never">
    <template #header>
      <span class="card-head">
        <span class="step-title serif">Step 4 · 排版预览</span>
        <span class="meta">微信样式实时预览 · 与发布同源(文颜)</span>
      </span>
    </template>

    <!-- 前置未就绪 -->
    <div v-if="!previewable" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">尚未完成配图</div>
      <div class="state-msg">请先完成「版本」与「配图」步骤，再进行排版预览。</div>
    </div>

    <template v-else>
      <!-- 工具栏(原版布局语义 + sparkora 皮肤):选择器 | 开关 | 动作 三段分组 -->
      <div class="ctrl-bar">
        <div class="ctrl-group">
          <span class="field-label">主题</span>
          <el-select v-model="theme" class="theme-select" @change="onPreviewStyleChange">
            <template #label>
              <span class="theme-dot" :style="{ background: themeColor(theme) }" :class="{ 'is-bright': themeIsBright(theme) }"></span>
              <span class="select-label-text">{{ themeLabel(theme) }}</span>
            </template>
            <el-option v-for="t in allThemeOptions" :key="t" :label="themeLabel(t)" :value="t">
              <span class="option-row">
                <span class="theme-dot" :style="{ background: themeColor(t) }" :class="{ 'is-bright': themeIsBright(t) }"></span>
                <span class="option-name">{{ themeLabel(t) }}</span>
                <el-icon v-if="t === theme" class="option-check"><Check /></el-icon>
              </span>
            </el-option>
          </el-select>
          <span class="field-label">高亮</span>
          <el-select v-model="highlight" class="hl-select" @change="onPreviewStyleChange">
            <el-option v-for="h in highlightOptions" :key="h" :label="h" :value="h" />
          </el-select>
        </div>
        <span class="ctrl-divider" aria-hidden="true"></span>
        <div class="ctrl-group">
          <el-tooltip content="代码块顶部仿 Mac 红绿灯" placement="top" :show-after="300">
            <span class="switch-item">
              <el-switch v-model="macStyle" size="small" @change="onPreviewStyleChange" />
              <span class="switch-label">Mac 代码块</span>
            </span>
          </el-tooltip>
          <el-tooltip content="外链转为文末引用脚注" placement="top" :show-after="300">
            <span class="switch-item">
              <el-switch v-model="footnote" size="small" @change="onWechatRebuild" />
              <span class="switch-label">链接转脚注</span>
            </span>
          </el-tooltip>
        </div>
        <span class="ctrl-divider" aria-hidden="true"></span>
        <el-popover placement="bottom-start" :width="308" trigger="click" class="img-pop">
          <template #reference>
            <el-button size="small" :disabled="!bodyImages.length">
              <el-icon style="margin-right: 4px"><Picture /></el-icon>
              插图 {{ bodyImages.length }}
            </el-button>
          </template>
          <div class="img-pop-body">
            <div v-if="!bodyImages.length" class="img-pop-empty">尚未选插图：到「配图」步从图库加插图</div>
            <template v-else>
              <div class="img-pop-tip">点击插入到编辑器光标处；正文里没引用的插图不会出现在文章中</div>
              <div class="img-pop-grid">
                <div v-for="(img, i) in bodyImages" :key="img.id" class="img-pop-cell"
                     :class="{ inserted: insertedUrls.has(img.url) }"
                     :title="`#${i + 1} ${img.fileName}${insertedUrls.has(img.url) ? '（已插入）' : ''}`" @click="insertBodyImage(img)">
                  <el-image :src="img.url" fit="cover" class="img-pop-thumb" />
                  <span class="img-pop-idx">{{ i + 1 }}</span>
                  <span v-if="insertedUrls.has(img.url)" class="img-pop-check">✓</span>
                </div>
              </div>
            </template>
          </div>
        </el-popover>
        <span class="flex-sp"></span>
        <el-tag v-if="saveState === 'dirty'" type="warning" effect="plain" size="small">未保存</el-tag>
        <el-tag v-else-if="saveState === 'error'" type="danger" effect="plain" size="small">保存失败</el-tag>
        <el-tag v-else-if="savedAt" type="success" effect="plain" size="small">已保存 {{ savedAt }}</el-tag>
        <el-button size="small" type="primary" plain :loading="saving" :disabled="!dirty" @click="saveContent">保存正文</el-button>
        <el-button size="small" type="primary" :icon="DocumentCopy" :loading="copying" :disabled="renderError" @click="copyRich">复制排版</el-button>
        <el-button size="small" type="success" :disabled="!!renderError" @click="goPublish">去发布</el-button>
      </div>

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

        <div class="pane pane-right">
          <div class="pane-head">
            <span>公众号预览</span>
            <el-tag v-if="renderError" type="danger" size="small" effect="plain">
              {{ renderError }}
              <el-button link size="small" @click="renderMarkdown">重试</el-button>
            </el-tag>
            <el-icon v-else-if="rendering" class="spin"><Loading /></el-icon>
          </div>
          <!-- 主题/渲染进度条(150ms 细条) -->
          <div class="theme-progress" :class="{ active: rendering || themeLoading }" aria-hidden="true"></div>
          <div class="phone">
            <div class="phone-device">
              <div class="phone-status">
                <span class="status-time">9:41</span>
                <span class="status-icons">
                  <svg width="17" height="11" viewBox="0 0 17 11" fill="currentColor" aria-hidden="true"><path d="M12.5 3.8a5.4 5.4 0 0 0-8 0l1.1 1.2a3.8 3.8 0 0 1 5.8 0l1.9-1.2ZM9.9 6.4a2.2 2.2 0 0 0-2.8 0L8.5 8.2l1.4-1.8Z"/><rect x="0" y="8.4" width="2" height="2.4" rx="0.5"/><rect x="3" y="6.4" width="2" height="4.4" rx="0.5"/><rect x="6" y="4.4" width="2" height="6.4" rx="0.5"/><rect x="9" y="2.4" width="2" height="8.4" rx="0.5"/></svg>
                  <svg width="25" height="12" viewBox="0 0 25 12" fill="none" aria-hidden="true"><rect x="0.5" y="0.5" width="21" height="11" rx="3" stroke="currentColor" opacity="0.5"/><rect x="2" y="2" width="16" height="8" rx="1.8" fill="currentColor"/><path d="M23 4v4c1-.3 1.6-1 1.6-2S24 4.3 23 4Z" fill="currentColor" opacity="0.5"/></svg>
                </span>
              </div>
              <div ref="previewBody" class="wechat-body" @scroll="onPreviewScroll">
                <div v-if="html" class="wenyan-preview" v-html="html"></div>
                <el-skeleton v-else :rows="9" animated class="preview-skeleton" />
              </div>
              <div class="phone-home" aria-hidden="true"><span></span></div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectApi, imageApi } from '../../api'
import { renderMarkdownHtml, applyPreviewTheme, buildWechatHtml, sanitizeWenyanHtml } from '../../utils/wenyanRender'
import { CUSTOM_THEMES } from '../../utils/wenyanThemes'
import MarkdownEditor from '../../components/MarkdownEditor.vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy, Loading, WarningFilled, Check, Picture } from '@element-plus/icons-vue'

/**
 * Step 4 · 排版预览(wenyan web 原版蓝本,Vue 重写)。
 * 渲染编排(对齐 @wenyan-md/ui):
 *  - 正文编辑(400ms 防抖)→ renderMarkdownHtml;主题/高亮/mac → applyPreviewTheme(共享 style 标签,不重渲染)。
 *  - 复制/保存快照 → buildWechatHtml(内联样式,与发布 server 同参)。
 * 左栏 CodeMirror 6;双栏百分比滚动同步;粘贴图片自动上传;草稿 localStorage 暂存。
 */
const props = defineProps({ project: Object })
const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.id)

const loaded = ref(false)
const loadError = ref('')
const editorReady = ref(false)
const editorRef = ref(null)
const versionId = ref(null)
const versionTitle = ref('')
const originalMd = ref('')
const contentMd = ref('')
const imgSnapshot = ref(null)   // 配图快照:{images[],coverImageId,bodyImageIds[]}——与 Step3 同一接口
const html = ref('')
const rendering = ref(false)
const renderError = ref('')
const saving = ref(false)
const savedAt = ref('')
const saveState = ref('clean') // clean | dirty | error
const themeOptions = ref([])
const highlightOptions = ref(['solarized-light'])
const theme = ref('default')
const highlight = ref('solarized-light')
const macStyle = ref(true)
const footnote = ref(true)
const previewBody = ref(null)
const copying = ref(false)

const previewable = computed(() => !!props.project && ['IMAGES_READY', 'PUBLISHED_DRAFT'].includes(props.project.status))
const dirty = computed(() => contentMd.value !== originalMd.value)
const wordCount = computed(() => (contentMd.value || '').replace(/\s/g, '').length)

const draftKey = computed(() => `sparkora-preview-draft-${projectId.value}`)

// ==== 配图快照口径(与 Step3/StepPublish 同一接口数据) ====
const snapshotImages = computed(() => imgSnapshot.value?.images || [])
const coverImageId = computed(() => imgSnapshot.value?.coverImageId ?? null)
const bodyIds = computed(() => imgSnapshot.value?.bodyImageIds || [])
/** 已选插图的资产对象(按 bodyImageIds 顺序),附本地访问 url。 */
const bodyImages = computed(() =>
  bodyIds.value.map(id => snapshotImages.value.find(img => img.id === id)).filter(Boolean)
    .map(img => ({ ...img, url: `/images/${img.storagePath}` })))
const coverImage = computed(() =>
  coverImageId.value == null ? null : snapshotImages.value.find(img => img.id === coverImageId.value))

/** 正文已引用的本地 URL 集合:面板「已插入」状态与后端组装去重口径一致(含 URL 即视为已插入)。 */
const insertedUrls = computed(() => {
  const body = contentMd.value || ''
  return new Set(bodyImages.value.map(img => img.url).filter(u => body.includes(u)))
})

/** frontmatter(title + cover,闭合 ---)+ 正文;插图落点完全由正文 markdown 引用决定(不自动追加文末)。 */
const buildFullMd = () => {
  const title = versionTitle.value || '无标题'
  let out = `---\ntitle: ${title}\n`
  if (coverImage.value) out += `cover: /images/${coverImage.value.storagePath}\n`
  out += `---\n\n`
  return out + (contentMd.value || '')
}

// ==== 主题色点(原版 ThemePreview 下拉的语义:一眼看出主题气质) ====
const THEME_COLORS = {
  default: '#1a73e8',    // 经典蓝
  orangeheart: '#ef7060',
  rainbow: '#e91e63',
  lapis: '#4870ac',
  pie: '#2b2b2b',
  maize: '#ffb11b',
  purple: '#8e44ad',
  phycat: '#3eaf7c'
}
// 自定义主题色点(id 是 custom:<key> 取 key 段)由 wenyanThemes.CUSTOM_THEMES 提供
const CUSTOM_COLOR_MAP = Object.fromEntries(CUSTOM_THEMES.map(t => [t.id, t.color]))
const themeColor = (t) => THEME_COLORS[t] || CUSTOM_COLOR_MAP[t] || '#8a8f98'
const BRIGHT_DOTS = new Set(['maize', 'rainbow'])
const themeIsBright = (t) => BRIGHT_DOTS.has(t)
/** 主题显示名:custom:chazi → 姹紫;内置原样。 */
const themeLabel = (t) => {
  const c = CUSTOM_THEMES.find(x => x.id === t)
  return c ? c.name : t
}
// 全量主题清单 = 后端配置下发(内置)+ 社区主题库
const allThemeOptions = computed(() => [...(themeOptions.value || []), ...CUSTOM_THEMES.map(t => t.id)])

// ==== 渲染:正文 400ms 防抖走纯渲染;首次/出错时同样入口 ====
let renderTimer = null
let renderSeq = 0
const scheduleRender = () => {
  clearTimeout(renderTimer)
  renderTimer = setTimeout(renderMarkdown, 400)
}
const renderMarkdown = async () => {
  if (!loaded.value) return
  const seq = ++renderSeq
  rendering.value = true
  try {
    const raw = await renderMarkdownHtml(buildFullMd())
    if (seq !== renderSeq) return // 过期结果丢弃
    html.value = sanitizeWenyanHtml(raw)
    renderError.value = ''
  } catch (e) {
    if (seq === renderSeq) renderError.value = '渲染失败: ' + (e?.message || e) + '(正文已本地暂存)'
  } finally {
    if (seq === renderSeq) rendering.value = false
  }
}

/** 主题/高亮/mac 变更:只替换共享 style 标签(原版机制,不重渲染)。 */
const themeLoading = ref(false)
const onPreviewStyleChange = async () => {
  themeLoading.value = true
  try {
    await applyPreviewTheme({ theme: theme.value, highlight: highlight.value, macStyle: macStyle.value, footnote: footnote.value })
  } catch (e) {
    renderError.value = '主题加载失败: ' + (e?.message || e)
  } finally {
    setTimeout(() => { themeLoading.value = false }, 250)
  }
}

/** footnote 变化影响 DOM 结构(脚注区),需要重渲染。 */
const onWechatRebuild = () => { scheduleRender() }

// ==== 滚动同步(百分比映射,防循环) ====
let syncingScroll = null
const onEditorScroll = (percent) => {
  if (syncingScroll === 'preview') return
  syncingScroll = 'editor'
  const el = previewBody.value
  if (el) el.scrollTop = percent * (el.scrollHeight - el.clientHeight)
  setTimeout(() => { if (syncingScroll === 'editor') syncingScroll = null }, 50)
}
const onPreviewScroll = () => {
  const el = previewBody.value
  if (!el || !editorRef.value) return
  if (syncingScroll === 'editor') return
  syncingScroll = 'preview'
  editorRef.value.scrollToPercent?.((el.scrollTop) / Math.max(1, el.scrollHeight - el.clientHeight))
  setTimeout(() => { if (syncingScroll === 'preview') syncingScroll = null }, 50)
}

// ==== 数据加载/保存 ====
const loadContent = async () => {
  loadError.value = ''
  try {
    const res = await imageApi.projectImages(projectId.value)
    if (res.code !== 0) throw new Error(res.msg || '加载失败')
    const vid = res.data?.currentVersionId
    if (!vid) throw new Error('未找到当前版本')
    // 配图快照留存:封面/插图渲染组装 + 插图面板共用
    imgSnapshot.value = {
      images: res.data?.images || [],
      coverImageId: res.data?.coverImageId ?? null,
      bodyImageIds: res.data?.bodyImageIds || []
    }
    const vr = await projectApi.listVersions(projectId.value)
    if (vr.code !== 0) throw new Error(vr.msg || '版本加载失败')
    const v = (vr.data || []).find(x => x.id === vid)
    if (!v) throw new Error('当前版本不存在')
    versionId.value = vid
    versionTitle.value = v.title || ''
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
  } catch (e) {
    saveState.value = 'error'
    ElMessage.error(e?.response?.data?.msg || e?.message || '保存失败')
  } finally { saving.value = false }
}

/** 复制排版:buildWechatHtml 内联输出(与发布同参),富文本进剪贴板。 */
const copyRich = async () => {
  copying.value = true
  try {
    const inline = await buildWechatHtml(buildFullMd(), {
      theme: theme.value, highlight: highlight.value, macStyle: macStyle.value, footnote: footnote.value
    })
    if (navigator.clipboard && window.ClipboardItem) {
      await navigator.clipboard.write([new ClipboardItem({
        'text/html': new Blob([inline], { type: 'text/html' }),
        'text/plain': new Blob([props.project?.title || '', inline], { type: 'text/plain' })
      })])
      ElMessage.success('已复制排版,去公众号编辑器粘贴即可')
      return
    }
    throw new Error('浏览器不支持富文本复制')
  } catch (e) {
    ElMessage.error(e?.message || '复制失败')
  } finally { copying.value = false }
}

/** 跳发布步(S5 衔接:预览确认排版后直接去发布)。 */
const goPublish = () => {
  router.push({ name: 'project-publish', params: { id: projectId.value } })
}

// ==== 插图落点:面板点击 → 插入编辑器光标处(落点完全由正文 markdown 引用决定) ====
const insertBodyImage = (img) => {
  editorRef.value?.insertMd?.(`\n![](${img.url})\n`)
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
      themeOptions.value = res.data?.themes || ['default']
      highlightOptions.value = res.data?.highlights || ['solarized-light']
      theme.value = res.data?.defaultTheme || 'default'
      highlight.value = res.data?.highlight || 'solarized-light'
      macStyle.value = res.data?.macStyle ?? true
      footnote.value = res.data?.footnote ?? true
    }
  } catch (e) { /* 兜底默认值 */ }
  if (previewable.value) loadContent()
})

watch(previewable, (ok) => { if (ok && !loaded.value && !loadError.value) loadContent() })
onBeforeUnmount(() => { clearTimeout(renderTimer) })
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: baseline; width: 100%; gap: 12px; }
.step-title { font-size: 16px; font-weight: 700; }
.card-head .meta { font-size: 12px; color: var(--muted); }
/* 控件微动效(150-200ms,无布局位移) */
.ctrl-bar :deep(.el-button) { transition: background-color .2s ease, border-color .2s ease, color .2s ease, box-shadow .2s ease; }
.ctrl-bar :deep(.el-switch__core) { transition: background-color .2s ease; }
.state-error { padding: 36px 16px; }
.state-title { font-weight: 700; margin: 8px 0 4px; }
.state-msg { color: var(--muted); font-size: 13px; margin-bottom: 12px; }

/* 插图落点面板(点击插图 → 插入编辑器光标处) */
.img-pop-body { padding: 2px; }
.img-pop-tip { font-size: 12px; color: var(--muted); line-height: 1.6; margin-bottom: 8px; }
.img-pop-empty { font-size: 13px; color: var(--muted); padding: 8px 0; }
.img-pop-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; max-height: 264px; overflow-y: auto; }
.img-pop-cell { position: relative; cursor: pointer; border: 1px solid var(--line); border-radius: 6px; overflow: hidden; transition: border-color .2s, box-shadow .2s; }
.img-pop-cell:hover { border-color: var(--brand, var(--el-color-primary)); }
.img-pop-cell.inserted { border-color: var(--ok, #67c23a); box-shadow: 0 0 0 2px color-mix(in srgb, var(--ok, #67c23a) 18%, transparent); }
.img-pop-thumb { width: 100%; aspect-ratio: 1; display: block; }
.img-pop-idx { position: absolute; left: 4px; top: 4px; min-width: 16px; height: 16px; line-height: 16px;
  text-align: center; font-size: 11px; border-radius: 8px; background: rgba(0,0,0,.55); color: #fff; padding: 0 4px; }
.img-pop-check { position: absolute; right: 4px; top: 4px; min-width: 16px; height: 16px; line-height: 16px;
  text-align: center; font-size: 11px; border-radius: 8px; background: var(--ok, #67c23a); color: #fff; }

/* ===== 工具栏(三段分组:选择器 | 开关 | 动作;统一 36px 高度) ===== */
.ctrl-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px; }
.ctrl-group { display: inline-flex; align-items: center; gap: 8px; }
.ctrl-divider { width: 1px; height: 18px; background: var(--line); margin: 0 2px; }

:deep(.theme-select .el-select__wrapper),
:deep(.hl-select .el-select__wrapper) { height: 34px; border-radius: 8px; }
:deep(.theme-select .el-select__selection) { display: inline-flex; align-items: center; gap: 7px; }
/* 字段标签(收起态语义可见,无需点开下拉) */
.field-label { font-size: 13px; color: var(--muted); flex: none; white-space: nowrap; }
/* 固定宽度防塌陷:收起态完整显示 色点+名称 */
.theme-select { width: 188px; flex: none; }
.hl-select { width: 190px; flex: none; }
.theme-dot { width: 10px; height: 10px; border-radius: 50%; flex: none; box-shadow: inset 0 0 0 1px rgba(0,0,0,.08); }
.theme-dot.is-bright { box-shadow: inset 0 0 0 1px rgba(0,0,0,.14); }
.select-label-text { font-size: 13px; color: var(--ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.option-row { display: inline-flex; align-items: center; gap: 8px; width: 100%; min-width: 0; }
.option-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.option-check { color: var(--brand); flex: none; }

.switch-item { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; }
.switch-label { font-size: 13px; color: var(--muted); user-select: none; }

.duo { display: grid; grid-template-columns: minmax(280px, 5fr) minmax(320px, 7fr); gap: 16px; align-items: stretch; }
.pane { border: 1px solid var(--line); border-radius: var(--radius-sm); overflow: hidden; background: var(--paper); display: flex; flex-direction: column; }
.pane-head { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--line); font-size: 12px; font-weight: 600; color: var(--muted); background: var(--el-fill-color-light); }
.pane-meta { margin-left: auto; font-weight: 400; }
.editor-wrap { flex: 1; min-height: 620px; max-height: 720px; display: flex; flex-direction: column; }
.editor-wrap > :deep(.cm-host) { flex: 1; }
.editor-loading { padding: 24px; }
.flex-sp { flex: 1; }
/* 旧定义去重:见上方 ctrl-bar 区块 */

/* ===== 手机拟真(参照 iPhone 外观;背景渐变模拟桌面环境) ===== */
.phone { background: linear-gradient(160deg, #f0eee9 0%, #e7e3db 100%); padding: 20px 0; display: flex; justify-content: center; flex: 1; }
.phone-device {
  width: 430px; max-width: 96%;
  background: #fff; border-radius: 28px; padding: 6px 10px 8px;
  border: 1px solid rgba(0,0,0,.06);
  box-shadow: 0 0 0 2px #2c2c2e, 0 1px 3px rgba(0,0,0,.18), var(--shadow-hover);
  display: flex; flex-direction: column; max-height: 100%;
}
.phone-status { display: flex; align-items: center; justify-content: space-between; padding: 4px 14px 2px; color: #1a1a1a; }
.status-time { font-size: 12px; font-weight: 600; letter-spacing: .2px; font-family: -apple-system, "SF Pro Text", "PingFang SC", sans-serif; }
.status-icons { display: inline-flex; align-items: center; gap: 5px; }
.status-icons svg { display: block; opacity: .9; }
.wechat-body { width: 100%; background: #fff; padding: 10px 14px 20px; min-height: 520px; max-height: 640px; overflow: auto; border-radius: 0 0 14px 14px; overflow-y: auto; }
.phone-home { display: flex; justify-content: center; padding: 5px 0 3px; }
.phone-home span { width: 100px; height: 4px; border-radius: 2px; background: rgba(0,0,0,.28); }
.wenyan-preview { animation: fadein .18s ease; }
@keyframes fadein { from { opacity: 0; } to { opacity: 1; } }
.preview-skeleton { padding: 16px; }

/* 主题/渲染进度条:双栏头部下侧的细条 */
.theme-progress { height: 2px; position: relative; overflow: hidden; background: transparent; }
.theme-progress::before { content: ""; position: absolute; inset: 0; width: 40%; background: var(--brand); opacity: 0; transition: opacity .15s ease; }
.theme-progress.active::before { opacity: .85; animation: progress-slide 1s ease-in-out infinite; }
@keyframes progress-slide { 0% { transform: translateX(-100%); } 100% { transform: translateX(350%); } }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 900px) {
  .duo { grid-template-columns: 1fr; }
  .phone-device { width: 100%; max-width: 430px; }
  .theme-select, .hl-select { width: 100%; flex: auto; }
  .ctrl-divider { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .wenyan-preview { animation: none; }
  .theme-progress { display: none; }
}
</style>