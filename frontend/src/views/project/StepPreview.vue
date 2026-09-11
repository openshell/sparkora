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
      <!-- 工具栏(原版布局语义 + sparkora 皮肤):选择器 | 开关 | 动作 三段分组 -->
      <div class="ctrl-bar">
        <div class="ctrl-group">
          <span class="field-label">主题</span>
          <el-select v-model="theme" class="theme-select" @change="onPreviewStyleChange">
            <template #label>
              <span class="theme-dot" :style="{ background: themeColor(theme) }" :class="{ 'is-bright': themeIsBright(theme) }"></span>
              <span class="select-label-text">{{ themeLabel(theme) }}</span>
            </template>
            <el-option-group v-if="builtinThemes.length" label="内置主题">
              <el-option v-for="t in builtinThemes" :key="t.id" :label="t.name" :value="t.id">
                <span class="option-row">
                  <span class="theme-dot" :style="{ background: t.color }" :class="{ 'is-bright': t.bright }"></span>
                  <span class="option-name">{{ t.name }}</span>
                  <el-icon v-if="t.id === theme" class="option-check"><Check /></el-icon>
                </span>
              </el-option>
            </el-option-group>
            <el-option-group v-if="communityThemes.length" label="社区主题">
              <el-option v-for="t in communityThemes" :key="t.id" :label="t.name" :value="t.id">
                <span class="option-row">
                  <span class="theme-dot" :style="{ background: t.color }" :class="{ 'is-bright': t.bright }"></span>
                  <span class="option-name">{{ t.name }}</span>
                  <el-icon v-if="t.id === theme" class="option-check"><Check /></el-icon>
                </span>
              </el-option>
            </el-option-group>
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
        <div class="ctrl-group">
          <span class="field-label">宽度</span>
          <el-radio-group v-model="previewWidth" size="small" class="width-toggle">
            <el-radio-button value="phone">手机</el-radio-button>
            <el-radio-button value="tablet">平板</el-radio-button>
            <el-radio-button value="full">全宽</el-radio-button>
          </el-radio-group>
        </div>
        <span class="ctrl-divider" aria-hidden="true"></span>
        <el-button size="small" @click="imgDrawer = true">
          <el-icon style="margin-right: 4px"><Picture /></el-icon>
          配图 {{ insertedCount }}/{{ snapshotImages.length }}
        </el-button>
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
          <div class="phone" :class="`w-${previewWidth}`">
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

      <!-- 底部:进入下一步(与简报/版本同款 next-row;发布成功后消失) -->
      <div v-if="canGoPublish" class="next-row">
        <el-button type="success" :disabled="!!renderError" @click="goPublish">去发布 →</el-button>
      </div>
    </template>

    <!-- 参考图选择弹窗（图生图；S10 走图库分页检索） -->
    <el-dialog v-model="refDialog" title="选择参考图" width="720px" class="ref-dialog">
      <div v-if="!libraryImages.length" class="img-pop-empty">图库为空，请先到「图库」上传或用 AI 生成</div>
      <div v-else class="ref-grid">
        <div v-for="img in libraryImages" :key="img.id" class="ref-cell" @click="chooseRef(img)">
          <el-image :src="imgUrl(img)" fit="cover" class="ref-cell-thumb" />
          <span class="ref-cell-name">#{{ img.id }} {{ img.fileName }}</span>
        </div>
      </div>
    </el-dialog>

    <!-- 配图抽屉:图库选用 + AI 生图 -->
    <el-drawer v-model="imgDrawer" title="配图" size="420px" class="img-drawer" :with-header="true">
      <el-tabs v-model="imgTab" class="img-tabs">
        <!-- 图库:全量图库选用(插入正文 / 设封面) -->
        <el-tab-pane label="图库" name="library">
          <div class="lib-filter-row">
            <el-select v-model="libSource" clearable placeholder="来源" size="small" class="lib-src" @change="reloadLibrary">
              <el-option v-for="(label, val) in SOURCE_LABELS" :key="val" :label="label" :value="val" />
            </el-select>
            <el-input v-model="libKeyword" clearable placeholder="搜文件名/提示词" size="small" class="lib-kw"
                      @input="onLibKeywordInput" @clear="reloadLibrary" />
          </div>
          <div v-if="!libraryImages.length" class="img-pop-empty">无匹配图片：到「图库」页上传，或用下方 AI 生成</div>
          <template v-else>
            <div class="img-pop-tip">点击图片插入到编辑器光标处；正文里没引用的插图不会出现在文章中</div>
            <div class="img-pop-grid" v-infinite-scroll="loadMoreLibrary" :infinite-scroll-disabled="libLoading"
                 :infinite-scroll-distance="80" :infinite-scroll-immediate-check="false">
              <div v-for="img in libraryImages" :key="img.id" class="img-pop-cell"
                   :class="{ inserted: insertedUrls.has(originUrl(img)) }">
                <div class="img-pop-thumb-wrap" @click="insertBodyImage(img)">
                  <el-image :src="imgUrl(img)" fit="cover" class="img-pop-thumb" />
                  <span v-if="img.id === coverImageId" class="img-pop-cover">封面</span>
                  <span v-if="insertedUrls.has(originUrl(img))" class="img-pop-check">✓</span>
                  <div class="img-pop-hover">
                    <el-icon><Plus /></el-icon> 插入正文
                  </div>
                </div>
                <div class="img-pop-actions">
                  <el-button size="small" :type="img.id === coverImageId ? 'success' : 'default'"
                             :disabled="img.id === coverImageId || busy" @click="onSetCover(img.id)">
                    {{ img.id === coverImageId ? '✓ 封面' : '设为封面' }}
                  </el-button>
                </div>
              </div>
            </div>
            <div v-if="libLoading" class="lib-loading">加载中…</div>
            <div v-else-if="!libHasMore" class="lib-loading">已加载全部 {{ libTotal }} 张</div>
          </template>
        </el-tab-pane>
        <!-- AI 生图:文生图 / 图生图,产物进图库后插入(S10:n 张候选逐张选用) -->
        <el-tab-pane label="AI 生图" name="ai">
          <el-tabs v-model="aiTab" class="ai-tabs">
            <el-tab-pane label="文生图" name="text2img">
              <el-input v-model="t2iPrompt" type="textarea" :rows="2"
                        placeholder="例：俯瞰一杯咖啡与摊开的笔记本，晨光，暖色调，杂志摄影风格" />
              <div class="ai-row">
                <el-select v-model="genSize" class="size-select">
                  <el-option label="方图 1024×1024" value="1024x1024" />
                  <el-option label="横图 1536×1024" value="1536x1024" />
                  <el-option label="竖图 1024×1536" value="1024x1536" />
                </el-select>
                <el-select v-model="genCount" class="n-select">
                  <el-option label="1 张" :value="1" />
                  <el-option label="2 张" :value="2" />
                  <el-option label="4 张" :value="4" />
                </el-select>
                <el-button type="primary" :loading="generating" @click="onGenerateText">
                  {{ generating ? '生成中…' : '生成候选' }}
                </el-button>
              </div>
            </el-tab-pane>
            <el-tab-pane label="图生图" name="img2img">
              <div v-if="refImage" class="ref-pick">
                <img :src="imgUrl(refImage)" class="ref-thumb" alt="参考图" />
                <el-button size="small" text type="primary" @click="refDialog = true">重新选择</el-button>
              </div>
              <el-button v-else plain size="small" @click="refDialog = true">从图库选择参考图</el-button>
              <el-input v-model="i2iPrompt" type="textarea" :rows="2"
                        placeholder="例：保持构图，改为蓝灰色科技感色调" />
              <div class="ai-row">
                <el-select v-model="genSize" class="size-select">
                  <el-option label="方图 1024×1024" value="1024x1024" />
                  <el-option label="横图 1536×1024" value="1536x1024" />
                  <el-option label="竖图 1024×1536" value="1024x1536" />
                </el-select>
                <el-select v-model="genCount" class="n-select">
                  <el-option label="1 张" :value="1" />
                  <el-option label="2 张" :value="2" />
                  <el-option label="4 张" :value="4" />
                </el-select>
                <el-button type="primary" :disabled="!refImage" :loading="generating" @click="onGenerateFromImage">
                  {{ generating ? '生成中…' : '生成候选' }}
                </el-button>
              </div>
            </el-tab-pane>
          </el-tabs>
          <!-- S10:生成候选列表(逐张可插入/设封面/重生成;n=1 时自动插入不再展示) -->
          <div v-if="candidates.length" class="cand-list">
            <div class="cand-tip">本次生成 {{ candidates.length }} 张候选：点击插入正文，或设为封面</div>
            <div class="cand-grid">
              <div v-for="img in candidates" :key="img.id" class="cand-cell">
                <el-image :src="imgUrl(img)" fit="cover" class="cand-thumb" :preview-src-list="[originUrl(img)]"
                          preview-teleported hide-on-click-modal />
                <div class="cand-actions">
                  <el-button size="small" type="primary" plain @click="insertBodyImage(img)">插入正文</el-button>
                  <el-button size="small" @click="onSetCover(img.id)">设为封面</el-button>
                  <el-button size="small" text type="primary" :loading="regeneratingId === img.id"
                             @click="onRegenerate(img)">重生成</el-button>
                </div>
              </div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectApi, imageApi } from '../../api'
import { useProjectDetailStore } from '../../store/project-detail'
import { renderMarkdownHtml, applyPreviewTheme, buildWechatHtml, sanitizeWenyanHtml } from '../../utils/wenyanRender'
import MarkdownEditor from '../../components/MarkdownEditor.vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy, Loading, WarningFilled, Check, Picture, Plus } from '@element-plus/icons-vue'

/**
 * Step 3 · 排版预览(wenyan web 原版蓝本,Vue 重写)。
 * 渲染编排(对齐 @wenyan-md/ui):
 *  - 正文编辑(400ms 防抖)→ renderMarkdownHtml;主题/高亮/mac → applyPreviewTheme(共享 style 标签,不重渲染)。
 *  - 复制/保存快照 → buildWechatHtml(内联样式,与发布 server 同参)。
 * 左栏 CodeMirror 6;双栏百分比滚动同步;粘贴图片自动上传;草稿 localStorage 暂存。
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
const versionId = ref(null)
const originalMd = ref('')
const contentMd = ref('')
const imgSnapshot = ref(null)   // 配图快照:{images[],coverImageId,bodyImageIds[]}——与 StepPublish 同一接口
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
const previewWidth = ref('phone') // 预览宽度档位:phone | tablet | full(仅视觉)
const previewBody = ref(null)
const copying = ref(false)

// ==== 配图面板状态(图库插入 + AI 生图) ====
const imgDrawer = ref(false)        // 配图抽屉开关
const imgTab = ref('library')       // 配图面板 tab:library | ai
const aiTab = ref('text2img')       // AI 生图子 tab:text2img | img2img
const t2iPrompt = ref('')
const i2iPrompt = ref('')
const genSize = ref('1024x1024')
const genCount = ref(1)              // S10:批量生成张数(1/2/4)
const candidates = ref([])           // S10:本次生成候选(响应列表;全部已入库图库)
const regeneratingId = ref(null)     // S10:重生成中的源图 id
const refImage = ref(null)          // 图生图参考图
const refDialog = ref(false)
const generating = ref(false)       // AI 生成中
const busy = ref(false)             // 封面操作中

// ==== 图库分页检索状态(S10:抽屉「图库」tab 与参考图弹窗共用,触底加载) ====
const SOURCE_LABELS = { upload: '上传', 'ai-text2img': '文生图', 'ai-img2img': '图生图', byd: '比亚迪' }
const libraryImages = ref([])       // 抽屉/参考图弹窗网格数据(分页接口累积)
const libPage = ref(1)
const LIB_SIZE = 24
const libTotal = ref(0)
const libLoading = ref(false)
const libSource = ref('')
const libKeyword = ref('')
const libHasMore = computed(() => libraryImages.value.length < libTotal.value)

/** 拉一页图库(筛选条件变化时由 reloadLibrary 重置;触底时 append)。 */
const loadLibraryPage = async (append) => {
  if (libLoading.value) return
  libLoading.value = true
  try {
    const res = await imageApi.list({
      page: libPage.value, size: LIB_SIZE,
      source: libSource.value || undefined,
      keyword: libKeyword.value.trim() || undefined
    })
    if (res.code === 0) {
      const rows = res.data?.rows || []
      libraryImages.value = append ? [...libraryImages.value, ...rows] : rows
      libTotal.value = res.data?.total || 0
    } else ElMessage.error(res.msg || '图库加载失败')
  } catch (e) {
    ElMessage.error('图库加载失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { libLoading.value = false }
}
const reloadLibrary = () => {
  libPage.value = 1
  loadLibraryPage(false)
}
const loadMoreLibrary = () => {
  if (!libHasMore.value || libLoading.value) return
  libPage.value += 1
  loadLibraryPage(true)
}
let libKwTimer = null
const onLibKeywordInput = () => {
  clearTimeout(libKwTimer)
  libKwTimer = setTimeout(reloadLibrary, 300)
}

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
const imgUrl = (img) => img?.thumbUrl || img?.url || ''   // S10:网格缩略图(imageView2/webp)
const originUrl = (img) => img?.url || ''                 // 插入正文/大图预览用原图 URL

// ==== 主题目录(后端下发:内置 + 社区,分组/名称/色点单一真值) ====
/** 按 id 查目录项(未知返回 undefined)。 */
const themeMeta = (t) => (themeOptions.value || []).find((x) => x.id === t)
const themeColor = (t) => themeMeta(t)?.color || '#8a8f98'
const themeIsBright = (t) => !!themeMeta(t)?.bright
/** 主题显示名:内置主题为 id 原样,社区主题为中文名。 */
const themeLabel = (t) => themeMeta(t)?.name || t || ''
// 全量主题按 group 分组(内置主题 / 社区主题),供 el-option-group 渲染
const builtinThemes = computed(() => (themeOptions.value || []).filter((t) => t.group !== 'community'))
const communityThemes = computed(() => (themeOptions.value || []).filter((t) => t.group === 'community'))

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
    const raw = await renderMarkdownHtml(contentMd.value || '')
    if (seq !== renderSeq) return // 过期结果丢弃
    html.value = sanitizeWenyanHtml(raw)
    renderError.value = ''
  } catch (e) {
    if (seq === renderSeq) renderError.value = '渲染失败: ' + (e?.message || e) + '(正文已本地暂存)'
  } finally {
    if (seq === renderSeq) rendering.value = false
  }
}

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

// ==== 预览样式落库(项目级,跨会话保持):防抖 400ms,失败仅 warn 不阻塞预览 ====
let previewStyleTimer = null
let previewStyleDirty = false
const scheduleSavePreviewStyle = () => {
  previewStyleDirty = true
  clearTimeout(previewStyleTimer)
  previewStyleTimer = setTimeout(savePreviewStyle, 400)
}
/** 立即落库待保存的样式(去发布前调用,避免 400ms 防抖窗口内跳转导致 AC2 主题丢失)。 */
const flushSavePreviewStyle = () => {
  if (!previewStyleDirty) return Promise.resolve()
  clearTimeout(previewStyleTimer)
  previewStyleTimer = null
  return savePreviewStyle()
}
const savePreviewStyle = async () => {
  previewStyleDirty = false
  previewStyleTimer = null
  const patch = { theme: theme.value, highlight: highlight.value, macStyle: macStyle.value, footnote: footnote.value }
  try {
    const res = await projectApi.savePreviewStyle(projectId.value, patch)
    if (res.code !== 0) {
      previewStyleDirty = true // 失败保留待存标记:下次防抖/flush 重试,避免样式静默丢失(AC2)
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

/** 编辑器正文变更(v-model 更新 contentMd 后):防抖重渲染预览。手动插图/粘贴图/打字均走此入口。 */
const onEdit = () => {
  scheduleRender()
}

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
  // 样式防抖窗口内直接跳转会把「刚选的主题」丢掉(AC2);跳转前立即落库
  await flushSavePreviewStyle()
  router.push({ name: 'project-publish', params: { id: projectId.value } })
}

// ==== 配图面板:插入 / 设封面 / AI 生图 / 参考图 ====
const insertBodyImage = (img) => {
  editorRef.value?.insertMd?.(`\n![](${imgUrl(img)})\n`)
}

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

const onGenerateText = () => {
  if (!t2iPrompt.value.trim()) { ElMessage.warning('请输入画面描述'); return }
  doGenerate(imageApi.generateText(projectId.value, t2iPrompt.value.trim(), genSize.value, genCount.value))
}
const onGenerateFromImage = () => {
  if (!refImage.value) { ElMessage.warning('请先选择参考图'); return }
  if (!i2iPrompt.value.trim()) { ElMessage.warning('请输入画面描述'); return }
  doGenerate(imageApi.generateFromImage(projectId.value, refImage.value.id, i2iPrompt.value.trim(), genSize.value, genCount.value))
}
/** S10:响应为候选列表(逐张入库);n=1 沿用旧行为自动插入,多张展示候选面板逐张选用。 */
const doGenerate = async (req) => {
  generating.value = true
  try {
    const res = await req
    if (res.code === 0) {
      const list = res.data || []
      const reused = list.some(img => img.dedupeHit)
      ElMessage.success(reused ? '生成完成（部分图与图库重复，已复用）' : '生成成功，已进图库')
      candidates.value = list
      await refreshImgSnapshot()
      // 单张候选沿旧行为:直接插入正文光标处;多张候选由面板逐张选用
      if (list.length === 1 && list[0]?.id) insertBodyImage(list[0])
    } else ElMessage.error(res.msg || '生成失败')
  } catch (e) {
    ElMessage.error('生成失败：' + (e.response?.data?.msg || e.message || '网络异常或超时'))
  } finally { generating.value = false }
}

/** S10:同 prompt/尺寸一键重生成(产新图不覆盖源图);候选列表替换为新候选。 */
const onRegenerate = async (img) => {
  regeneratingId.value = img.id
  try {
    const res = await imageApi.regenerate(img.id)
    if (res.code === 0) {
      const list = res.data || []
      ElMessage.success(list.length ? '已重新生成' : '生成失败')
      candidates.value = list
      await refreshImgSnapshot()
    } else ElMessage.error(res.msg || '重新生成失败')
  } catch (e) {
    ElMessage.error('重新生成失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { regeneratingId.value = null }
}

const chooseRef = (img) => { refImage.value = img; refDialog.value = false }

watch(saveState, (s) => { if (s !== 'dirty') return })
watch(dirty, (d) => {
  if (d) { saveState.value = 'dirty'; localStorage.setItem(draftKey.value, contentMd.value) }
  else saveState.value = 'clean'
})

// 项目级样式优先(09-11-preview-publish-bridge,跨会话保持);缺失字段回退后端全局默认。
// 项目对象可能晚于组件挂载到位,故在 options 到位与 project 变化时各应用一次。
const styleDefaults = ref(null)  // preview-options 下发的全局默认(作为 preview* 缺失字段的回退)
let styleApplied = false         // 是否已用「带项目级样式」的值初始化过(避免后续覆盖用户手改)
const applyEffectiveStyle = () => {
  const d = styleDefaults.value
  if (!d) return false
  const p = props.project || {}
  theme.value = p.previewTheme || d.theme
  highlight.value = p.previewHighlight || d.highlight
  macStyle.value = p.previewMacStyle ?? d.macStyle
  footnote.value = p.previewFootnote ?? d.footnote
  return true
}

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
      if (props.project) styleApplied = true
    }
  } catch (e) { /* 兜底默认值 */ }
  if (previewable.value) loadContent()
})

// 项目详情晚到:项目级样式到位后重新应用并即时生效(仅首次,避免覆盖用户后续手改)
watch(() => props.project, (p) => {
  if (styleApplied || !p || !styleDefaults.value) return
  applyEffectiveStyle()
  styleApplied = true
  applyPreviewTheme({ theme: theme.value, highlight: highlight.value, macStyle: macStyle.value, footnote: footnote.value })
    .catch((e) => { renderError.value = '主题加载失败: ' + (e?.message || e) })
})

watch(previewable, (ok) => { if (ok && !loaded.value && !loadError.value) loadContent() })
// S10:抽屉/参考图弹窗首次打开时拉图库分页(后续打开仅在空态时重拉,避免打断滚动位置)
watch(imgDrawer, (open) => { if (open && !libraryImages.value.length) reloadLibrary() })
watch(refDialog, (open) => { if (open && !libraryImages.value.length) reloadLibrary() })
onBeforeUnmount(() => { clearTimeout(renderTimer); flushSavePreviewStyle() })
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: baseline; width: 100%; gap: 12px; }
.head-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.step-title { font-size: 16px; font-weight: 700; }
.step-sub { font-size: 12px; color: var(--faint); }
.card-head .meta { font-size: 12px; color: var(--muted); }
/* 控件微动效(150-200ms,无布局位移) */
.ctrl-bar :deep(.el-button) { transition: background-color .2s ease, border-color .2s ease, color .2s ease, box-shadow .2s ease; }
.ctrl-bar :deep(.el-switch__core) { transition: background-color .2s ease; }
.state-error { padding: 36px 16px; }
.state-title { font-weight: 700; margin: 8px 0 4px; }
.state-msg { color: var(--muted); font-size: 13px; margin-bottom: 12px; }

/* 配图抽屉(图库插入 + AI 生图) */
.img-drawer :deep(.el-drawer__body) { padding: 0 16px 16px; }
.img-pop-tip { font-size: 12px; color: var(--muted); line-height: 1.6; margin-bottom: 8px; }
.img-pop-empty { font-size: 13px; color: var(--muted); padding: 8px 0; }
.img-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.img-tabs :deep(.el-tabs__nav-wrap)::after { height: 1px; }
.ai-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.ai-tabs :deep(.el-tabs__nav-wrap)::after { height: 1px; }
.img-pop-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; max-height: 60vh; overflow-y: auto; }
.lib-filter-row { display: flex; gap: 8px; margin-bottom: 10px; }
.lib-src { width: 110px; flex: none; }
.lib-kw { flex: 1; }
.lib-loading { text-align: center; color: var(--muted); font-size: 12px; padding: 10px 0; }
.n-select { width: 90px; }
.cand-list { margin-top: 12px; border-top: 1px solid var(--line); padding-top: 10px; }
.cand-tip { font-size: 12px; color: var(--muted); margin-bottom: 8px; }
.cand-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.cand-cell { border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 6px; }
.cand-thumb { width: 100%; aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--paper); }
.cand-actions { display: flex; gap: 4px; margin-top: 6px; flex-wrap: wrap; }
.img-pop-cell { position: relative; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; transition: border-color .2s, box-shadow .2s; background: var(--card); }
.img-pop-cell:hover { border-color: var(--brand, var(--el-color-primary)); }
.img-pop-cell.inserted { border-color: var(--ok, #67c23a); box-shadow: 0 0 0 2px color-mix(in srgb, var(--ok, #67c23a) 18%, transparent); }
.img-pop-thumb-wrap { position: relative; cursor: pointer; }
.img-pop-thumb { width: 100%; aspect-ratio: 1; display: block; }
/* 悬浮「插入正文」提示(桌面 hover;移动端点击图片即插入) */
.img-pop-hover {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: 4px;
  background: rgba(0,0,0,.45); color: #fff; font-size: 13px; font-weight: 600;
  opacity: 0; transition: opacity .2s ease;
}
.img-pop-thumb-wrap:hover .img-pop-hover { opacity: 1; }
.img-pop-cover { position: absolute; left: 4px; top: 4px; min-width: 16px; height: 16px; line-height: 16px;
  text-align: center; font-size: 11px; border-radius: 8px; background: var(--ok, #67c23a); color: #fff; padding: 0 4px; }
.img-pop-check { position: absolute; right: 4px; top: 4px; min-width: 16px; height: 16px; line-height: 16px;
  text-align: center; font-size: 11px; border-radius: 8px; background: var(--ok, #67c23a); color: #fff; }
.img-pop-actions { padding: 6px; }
.img-pop-actions .el-button { width: 100%; min-height: 30px; margin: 0; }
.ai-row { display: flex; gap: 8px; margin-top: 8px; align-items: center; }
.ai-row .size-select { width: 180px; }
.ref-pick { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.ref-thumb { width: 56px; height: 56px; border-radius: var(--radius-sm, 6px); object-fit: cover; border: 1px solid var(--line); }
.ref-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; max-height: 60vh; overflow-y: auto; }
.ref-cell { cursor: pointer; border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 6px; text-align: center; }
.ref-cell:hover { border-color: var(--brand); }
.ref-cell-thumb { width: 100%; aspect-ratio: 1; border-radius: var(--radius-sm); }
.ref-cell-name { display: block; font-size: 12px; color: var(--muted); margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ===== 工具栏(三段分组:选择器 | 开关 | 动作;统一 36px 高度) ===== */
.ctrl-bar {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px;
  padding: 10px 12px; border: 1px solid var(--line); border-radius: var(--radius-sm);
  background: var(--el-fill-color-light);
  /* 粘性工具栏:长文滚动时操作不丢失(停在顶栏下方) */
  position: sticky; top: 68px; z-index: 20;
  box-shadow: 0 2px 8px rgba(0, 0, 0, .04);
  backdrop-filter: blur(6px);
}
.ctrl-group { display: inline-flex; align-items: center; gap: 8px; }
.ctrl-divider { width: 1px; height: 18px; background: var(--line); margin: 0 2px; }
/* 宽度档位分段控件(仅预览视觉,不改渲染内容) */
.width-toggle { flex: none; }
.width-toggle :deep(.el-radio-button__inner) { padding: 6px 12px; }

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
/* 宽度档位:phone 430 / tablet 720 / full 100%(仅视觉容器宽度,不改渲染内容) */
.phone.w-tablet .phone-device { width: 720px; max-width: 100%; }
.phone.w-full .phone-device { width: 100%; max-width: 100%; border-radius: 14px; padding: 6px 10px 8px; }
.phone.w-full .phone-status, .phone.w-full .phone-home { display: none; }
.phone.w-full .wechat-body { border-radius: 10px; }
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

/* 底部进入下一步(与简报/版本同款 next-row) */
.next-row { margin-top: 18px; display: flex; gap: 8px; flex-wrap: wrap; }
.next-row .el-button:last-child { margin-left: auto; }

@media (max-width: 900px) {
  .duo { grid-template-columns: 1fr; }
  .phone-device,
  .phone.w-tablet .phone-device,
  .phone.w-full .phone-device { width: 100%; max-width: 430px; }
  .theme-select, .hl-select { width: 100%; flex: auto; }
  .ctrl-divider { display: none; }
  .ctrl-bar { position: static; top: auto; box-shadow: none; }
  .width-toggle { display: none; } /* 移动端容器已满宽,档位无意义 */
  /* 移动端配图抽屉全屏,网格两列 */
  .img-drawer { --el-drawer-size: 100% !important; }
  .img-pop-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (prefers-reduced-motion: reduce) {
  .wenyan-preview { animation: none; }
  .theme-progress { display: none; }
}
</style>