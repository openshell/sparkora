<template>
  <el-card class="step-card" shadow="never">
    <template #header>
      <span class="card-head">
        <span class="head-main">
          <span class="step-title serif">Step 5 · 发布</span>
          <span class="step-sub">发布到微信公众号草稿箱 · 与预览同源(文颜)</span>
        </span>
        <span class="meta">确认排版后发布</span>
      </span>
    </template>

    <!-- 前置未就绪(状态不该到这步:步骤导航已锁,兜底防护) -->
    <div v-if="!publishable" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">尚未完成配图</div>
      <div class="state-msg">请先完成「版本」与「配图」步骤,状态推进到「配图完成」后即可发布。</div>
    </div>

    <template v-else>
      <!-- 加载失败可见化 + 重试 -->
      <div v-if="loadError && !optionsLoaded" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">发布参数加载失败</div>
        <div class="state-msg">{{ loadError }}</div>
        <el-button type="primary" plain @click="loadOptions">重试</el-button>
      </div>

      <template v-else>
        <!-- 发布通道不可用黄条(只拦 ADMIN/EDITOR 的发布动作,viewer 只读可见) -->
        <el-alert v-if="optsLoadedOnce && !publishEnabled" type="warning" :closable="false" show-icon class="top-alert"
                  title="发布通道暂不可用"
                  :description="publishDisabledReason || 'wenyan-server 未配置或不可达,请联系管理员检查 WENYAN_MCP_* 配置。'" />

        <!-- 最近一次发布失败原因黄条(后端写回 lastPublishError) -->
        <el-alert v-if="publishError" type="error" :closable="false" show-icon class="top-alert"
                  title="最近一次发布失败" :description="publishError" />

        <!-- 成功态:已进草稿箱(可重发覆盖) -->
        <div v-if="published" class="success-box">
          <el-icon :size="30" color="var(--ok)"><SuccessFilled /></el-icon>
          <div class="success-title">已发布到公众号草稿箱</div>
          <div class="success-meta">
            <span v-if="options.publishMediaId">media_id: <code>{{ options.publishMediaId }}</code></span>
            <span v-if="options.publishedAt">发布时间: {{ shortTime(options.publishedAt) }}</span>
            <span v-if="options.publishTheme">排版主题: {{ themeLabel(options.publishTheme) }}</span>
          </div>
        </div>

        <!-- 发布摘要:标题 / 封面 / 插图数(与预览渲染同源数据) -->
        <div class="summary" v-loading="!summaryLoaded">
          <template v-if="summaryLoaded">
            <img v-if="coverUrl" :src="coverUrl" class="summary-cover" alt="封面" />
            <div v-else class="summary-cover summary-cover-empty">
              <span>无封面</span>
            </div>
            <div class="summary-info">
              <div class="summary-topic">{{ project?.topic || '无标题' }}</div>
              <div class="summary-title">版本标题: {{ versionTitle || '(未命名)' }}</div>
              <div class="summary-sub">
                <el-tag size="small" effect="plain">正文 {{ wordCount }} 字</el-tag>
                <el-tag size="small" effect="plain" :type="bodyImageIds.length ? 'success' : 'info'">
                  插图 {{ bodyImageIds.length }} 张
                </el-tag>
                <el-tag size="small" effect="plain" :type="coverUrl ? 'success' : 'warning'">
                  {{ coverUrl ? '已选封面' : '未选封面(发布将无封面图)' }}
                </el-tag>
              </div>
              <div v-if="!editorOrAbove" class="readonly-tip">viewer 只读,发布需 ADMIN/EDITOR 角色</div>
            </div>
          </template>
        </div>

        <!-- 参数表单(与预览页同形;默认值来自后端 .env 配置) -->
        <div class="ctrl-bar">
          <div class="ctrl-group">
            <span class="field-label">主题</span>
            <el-select v-model="theme" class="theme-select" :disabled="!editorOrAbove || publishing">
              <template #label>
                <span class="theme-dot" :style="{ background: themeColor(theme) }" :class="{ 'is-bright': themeIsBright(theme) }"></span>
                <span class="select-label-text">{{ themeLabel(theme) }}</span>
              </template>
              <el-option v-for="t in themeOptions" :key="t" :label="themeLabel(t)" :value="t">
                <span class="theme-dot" :style="{ background: themeColor(t) }" :class="{ 'is-bright': themeIsBright(t) }"></span>
                <span class="option-name">{{ themeLabel(t) }}</span>
              </el-option>
            </el-select>
            <span class="field-label">高亮</span>
            <el-select v-model="highlight" class="hl-select" :disabled="!editorOrAbove || publishing">
              <el-option v-for="h in highlightOptions" :key="h" :label="h" :value="h" />
            </el-select>
          </div>
          <span class="ctrl-divider" aria-hidden="true"></span>
          <div class="ctrl-group">
            <el-tooltip content="代码块顶部仿 Mac 红绿灯" placement="top" :show-after="300">
              <span class="switch-item">
                <el-switch v-model="macStyle" size="small" :disabled="!editorOrAbove || publishing" />
                <span class="switch-label">Mac 代码块</span>
              </span>
            </el-tooltip>
            <el-tooltip content="外链转为文末引用脚注" placement="top" :show-after="300">
              <span class="switch-item">
                <el-switch v-model="footnote" size="small" :disabled="!editorOrAbove || publishing" />
                <span class="switch-label">链接转脚注</span>
              </span>
            </el-tooltip>
          </div>
        </div>

        <!-- 发布动作区 -->
        <div class="publish-actions">
          <el-button size="small" plain @click="goPreview">← 返回预览</el-button>
          <template v-if="editorOrAbove">
            <el-button size="small" plain :disabled="!published || publishing" @click="recheckPreview">再检查一遍渲染</el-button>
            <el-button type="primary" :loading="publishing" :disabled="!publishEnabled"
                       @click="confirmPublish">
              {{ published ? '重发(覆盖草稿)' : '确认发布到草稿箱' }}
            </el-button>
          </template>
          <span v-if="publishing" class="pub-hint">正在渲染并通过 wenyan-server 写入草稿箱,约十几秒…</span>
        </div>
      </template>
    </template>
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectApi, imageApi } from '../../api'
import { useUserStore } from '../../store/user'
import { useProjectDetailStore } from '../../store/project-detail'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled, SuccessFilled } from '@element-plus/icons-vue'
import { isPublishable } from '../../constants/project'
import { CUSTOM_THEMES } from '../../utils/wenyanThemes'

/**
 * Step 5 · 发布(S5):同源渲染(与 Step4 preview 完全同参)→ wenyan-server(公众号草稿箱)。
 * 后端 PublishService 保证 preview HTML = 发布真值;本页只做参数选择 + 摘要确认 + 状态展示。
 */
const props = defineProps({ project: Object })
const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.id)
const userStore = useUserStore()
const store = useProjectDetailStore()

// 发布参数(与预览页同形;默认值取后端 .env 配置,与 preview-options 同源)
const theme = ref('default')
const highlight = ref('solarized-light')
const macStyle = ref(true)
const footnote = ref(true)

const optionsLoaded = ref(false)
const optsLoadedOnce = ref(false)
const loadError = ref('')
const publishing = ref(false)
const summaryLoaded = ref(false)

// publish-options 下发的运行参数
const options = ref({
  publishEnabled: false,
  publishConfigOk: false,
  publishDisabledReason: '',
  wenyanServer: '',
  publishMediaId: '',
  publishTheme: '',
  publishedAt: '',
  lastPublishError: ''
})

const publishable = computed(() => isPublishable(props.project?.status))
const published = computed(() => props.project?.status === 'PUBLISHED_DRAFT')
const editorOrAbove = computed(() => userStore.isEditorOrAbove)
const publishError = computed(() => options.value.lastPublishError || props.project?.lastPublishError || '')
const publishEnabled = computed(() => !!options.value.publishEnabled)

// ==== 主题色点/显示名(与 StepPreview 同源,含社区自定义主题) ====
const themeOptions = ref(['default'])
const highlightOptions = ref(['solarized-light'])
const THEME_COLORS = {
  default: '#1a73e8', orangeheart: '#ef7060', rainbow: '#e91e63', lapis: '#4870ac',
  pie: '#2b2b2b', maize: '#ffb11b', purple: '#8e44ad', phycat: '#3eaf7c'
}
const CUSTOM_COLOR_MAP = Object.fromEntries(CUSTOM_THEMES.map(t => [t.id, t.color]))
const themeColor = (t) => THEME_COLORS[t] || CUSTOM_COLOR_MAP[t] || '#8a8f98'
const BRIGHT_DOTS = new Set(['maize', 'rainbow'])
const themeIsBright = (t) => BRIGHT_DOTS.has(t)
const themeLabel = (t) => {
  const c = CUSTOM_THEMES.find(x => x.id === t)
  return c ? c.name : (t || '')
}

// ==== 摘要:标题/字数/封面/插图(与 Step3/Step4 同一接口,口径一致) ====
const versionTitle = ref('')
const contentMd = ref('')
const coverUrl = ref('')
const bodyImageIds = ref([])
const wordCount = computed(() => (contentMd.value || '').replace(/\s/g, '').length)

const loadSummary = async () => {
  summaryLoaded.value = false
  try {
    // 项目图快照(含 currentVersionId/coverImageId/bodyImageIds) + 版本列表取标题与正文
    const res = await imageApi.projectImages(projectId.value)
    if (res.code !== 0) throw new Error(res.msg || '配图快照加载失败')
    const vid = res.data?.currentVersionId
    bodyImageIds.value = res.data?.bodyImageIds || []
    coverUrl.value = ''
    const images = res.data?.images || []
    const targetId = res.data?.coverImageId
    if (targetId != null && images.length) {
      const img = images.find(x => x.id === targetId)
      // 本地静态映射路径直接可用(浏览器同源可见)
      if (img?.storagePath) coverUrl.value = `/images/${img.storagePath}`
      else if (img?.qiniuUrl) coverUrl.value = img.qiniuUrl
    }
    if (vid) {
      const vr = await projectApi.listVersions(projectId.value)
      if (vr.code === 0) {
        const v = (vr.data || []).find(x => x.id === vid)
        if (v) { versionTitle.value = v.title || ''; contentMd.value = v.contentMd || '' }
      }
    }
  } catch (e) {
    // 摘要失败不阻塞发布主流程,字段留空
    console.warn('[sparkora] 发布摘要加载失败:', e)
  } finally {
    summaryLoaded.value = true
  }
}

const loadOptions = async () => {
  loadError.value = ''
  try {
    // publish-options:主题/高亮/默认开关 + 通道就绪度 + 历史发布信息(publishOptions 接口)
    const res = await projectApi.publishOptions(projectId.value)
    if (res.code !== 0) throw new Error(res.msg || '发布参数加载失败')
    const d = res.data || {}
    options.value = { ...options.value, ...d }
    themeOptions.value = d.themes?.length ? d.themes : ['default']
    highlightOptions.value = d.highlights?.length ? d.highlights : ['solarized-light']
    theme.value = d.publishTheme || d.defaultTheme || 'default'
    highlight.value = d.highlight || 'solarized-light'
    macStyle.value = d.macStyle ?? true
    footnote.value = d.footnote ?? true
    optionsLoaded.value = true
    optsLoadedOnce.value = true
  } catch (e) {
    loadError.value = e?.response?.data?.msg || e?.message || '网络异常'
  }
}

const shortTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

/** 发布前二次确认(发布属外部可见动作;重发亦确认)。 */
const confirmPublish = () => {
  ElMessageBox.confirm(
    published.value
      ? '将重新渲染并覆盖公众号草稿箱中的这篇草稿,确定继续?'
      : '将按当前排版渲染并写入公众号草稿箱(不直接群发),确定继续?',
    published.value ? '重发确认' : '发布确认',
    { confirmButtonText: '确认发布', cancelButtonText: '再想想', type: 'warning' }
  ).then(() => doPublish()).catch(() => {})
}

const doPublish = async () => {
  publishing.value = true
  try {
    const res = await projectApi.publish(projectId.value, {
      theme: theme.value, highlight: highlight.value, macStyle: macStyle.value, footnote: footnote.value
    })
    if (res.code !== 0) throw new Error(res.msg || '发布失败')
    ElMessage.success('已发布到公众号草稿箱')
    // 刷新项目状态(IMAGES_READY → PUBLISHED_DRAFT)与发布信息,成功态自然浮现
    await store.ensureProject(projectId.value, { force: true })
    await loadOptions()
  } catch (e) {
    const msg = e?.response?.data?.msg || e?.message || '发布失败'
    ElMessage.error(msg)
    // 后端已把失败原因写进 lastPublishError:同步刷新项目与通道状态供黄条展示
    await store.ensureProject(projectId.value, { force: true }).catch(() => {})
    loadOptions()
  } finally {
    publishing.value = false
  }
}

/** 跳回预览步复核。 */
const goPreview = () => {
  router.push({ name: 'project-preview', params: { id: projectId.value } })
}

/** 「再检查一遍渲染」:跳回预览步复核后再回来。 */
const recheckPreview = goPreview

onMounted(() => {
  loadOptions()
  loadSummary()
})

watch(publishable, (ok) => {
  if (ok && !optionsLoaded.value) { loadOptions(); loadSummary() }
})
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
.top-alert { margin-bottom: 14px; }

/* 成功态卡片 */
.success-box {
  display: flex; flex-direction: column; align-items: flex-start; gap: 6px;
  padding: 20px; margin-bottom: 14px;
  border: 1px solid var(--ok); border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--ok) 7%, transparent);
}
.success-title { font-weight: 700; font-size: 15px; }
.success-meta { display: flex; flex-wrap: wrap; gap: 6px 18px; font-size: 12px; color: var(--muted); }
.success-meta code { font-size: 12px; word-break: break-all; }

/* 发布摘要 */
.summary {
  display: flex; gap: 14px; align-items: flex-start;
  padding: 16px; margin-bottom: 14px; min-height: 96px;
  border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--paper);
}
.summary-cover { width: 96px; height: 64px; object-fit: cover; border-radius: 8px; border: 1px solid var(--line); flex: none; }
.summary-cover-empty {
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; color: var(--muted); background: var(--el-fill-color-light);
}
.summary-info { min-width: 0; }
.summary-topic { font-weight: 700; margin-bottom: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.summary-title { font-size: 13px; color: var(--ink); margin-bottom: 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.summary-sub { display: flex; gap: 8px; flex-wrap: wrap; }
.readonly-tip { font-size: 12px; color: var(--muted); margin-top: 6px; }

/* 参数表单(与预览页同形) */
.ctrl-bar {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 14px;
  padding: 10px 12px; border: 1px solid var(--line); border-radius: var(--radius-sm);
  background: var(--el-fill-color-light);
}
.ctrl-group { display: inline-flex; align-items: center; gap: 8px; }
.ctrl-divider { width: 1px; height: 18px; background: var(--line); margin: 0 2px; }
.field-label { font-size: 13px; color: var(--muted); flex: none; white-space: nowrap; }
.theme-select { width: 188px; flex: none; }
.hl-select { width: 190px; flex: none; }
.theme-dot { width: 10px; height: 10px; border-radius: 50%; flex: none; box-shadow: inset 0 0 0 1px rgba(0,0,0,.08); }
.theme-dot.is-bright { box-shadow: inset 0 0 0 1px rgba(0,0,0,.14); }
.option-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.switch-item { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; }
.switch-label { font-size: 13px; color: var(--muted); user-select: none; }

/* 动作区 */
.publish-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.pub-hint { font-size: 12px; color: var(--muted); }

@media (max-width: 768px) {
  .theme-select, .hl-select { width: 100%; flex: auto; }
  .ctrl-divider { display: none; }
  .publish-actions :deep(.el-button) { min-height: 44px; } /* 触控目标 ≥44px */
  .summary { flex-wrap: wrap; }
}
</style>