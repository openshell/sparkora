<template>
  <el-card class="step-card" shadow="never">
    <template #header>
      <span class="card-head">
        <span class="step-title serif">Step 4 · 排版预览</span>
        <span class="meta">排版引擎:文颜(与发布同源)</span>
      </span>
    </template>

    <!-- 前置未就绪 -->
    <div v-if="!previewable" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">尚未完成配图</div>
      <div class="state-msg">请先完成「版本」与「配图」步骤，再进行排版预览。</div>
    </div>

    <template v-else>
      <!-- 主题与排版参数 -->
      <div class="ctrl-bar">
        <el-select v-model="theme" class="theme-select" @change="loadPreview">
          <el-option v-for="t in themeOptions" :key="t" :label="t" :value="t" />
        </el-select>
        <el-select v-model="highlight" class="hl-select" @change="loadPreview">
          <el-option v-for="h in highlightOptions" :key="h" :label="`高亮:${h}`" :value="h" />
        </el-select>
        <el-checkbox v-model="macStyle" @change="loadPreview">Mac 代码块</el-checkbox>
        <el-checkbox v-model="footnote" @change="loadPreview">链接转脚注</el-checkbox>
        <el-button text :icon="Refresh" @click="loadPreview">重新渲染</el-button>
      </div>

      <!-- 加载失败 -->
      <div v-if="loadError && !html" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">渲染失败</div>
        <div class="state-msg">{{ loadError }}</div>
        <el-button type="primary" plain @click="loadPreview">重试</el-button>
      </div>

      <!-- 降级黄条 -->
      <el-alert v-if="degraded" type="warning" :closable="false" show-icon class="degrade-alert"
                :title="degradedReason" />

      <!-- 排版预览:iframe 隔离主题样式(srcdoc) -->
      <div v-if="html" v-loading="rendering" class="preview-wrap">
        <iframe class="preview-frame" :srcdoc="iframeDoc" title="公众号排版预览"
                sandbox="allow-same-origin" referrerpolicy="no-referrer"></iframe>
      </div>
      <div v-else-if="!loadError" class="state-empty">
        <el-skeleton :rows="10" animated />
        <p class="muted" v-if="rendering">文颜排版中…</p>
      </div>
    </template>
  </el-card>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { projectApi, imageApi } from '../../api'
import { ElMessage } from 'element-plus'
import { Refresh, WarningFilled } from '@element-plus/icons-vue'

/**
 * Step 4 · 排版预览(S4,方案 A)。wenyan render 与发布同核:
 * 预览元数据(主题/高亮/macStyle/footnote 清单)由后端配置下发,前端只做选择与展示;
 * 图片已全部转为七牛公网 URL(后端 ensure),srcdoc iframe 内直接加载 = 发布后观感。
 */
const props = defineProps({ project: Object })
const route = useRoute()
const projectId = computed(() => route.params.id)

const html = ref('')
const loadError = ref('')
const rendering = ref(false)
const themeOptions = ref([])
const highlightOptions = ref(['solarized-light'])
const theme = ref('')
const highlight = ref('')
const macStyle = ref(true)
const footnote = ref(true)
const degraded = ref(false)
const degradedReason = ref('')

const previewable = computed(() => !!props.project && ['IMAGES_READY', 'PUBLISHED'].includes(props.project.status))

// 排版配置清单由后端配置接口下发(.env 驱动),与发布参数同源
const loadOptions = async () => {
  try {
    const res = await imageApi.previewOptions()
    if (res.code === 0) {
      themeOptions.value = res.data?.themes || []
      highlightOptions.value = res.data?.highlights || []
      if (!theme.value) theme.value = res.data?.defaultTheme || 'default'
      if (!highlight.value) highlight.value = res.data?.highlight || 'solarized-light'
      macStyle.value = res.data?.macStyle ?? true
      footnote.value = res.data?.footnote ?? true
    }
  } catch (e) { /* 静默:下拉兜底 default */ }
}

const loadPreview = async () => {
  rendering.value = true
  loadError.value = ''
  try {
    const res = await imageApi.preview(projectId.value, {
      theme: theme.value,
      highlight: highlight.value,
      macStyle: macStyle.value,
      footnote: footnote.value
    })
    if (res.code === 0) {
      html.value = res.data?.html || ''
      degraded.value = !!res.data?.degraded
      degradedReason.value = res.data?.degradedReason || ''
      if (res.data?.theme) theme.value = res.data.theme
    } else {
      loadError.value = res.msg || '预览失败'
      html.value = ''
    }
  } catch (e) {
    loadError.value = e.response?.data?.msg || e.message || '网络异常'
    html.value = ''
  } finally { rendering.value = false }
}

// iframe 文档:主题 HTML 直出;预览浏览器直接加载七牛外链,所见即发布观感
const iframeDoc = computed(() => '<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"/></head><body style="margin:0;padding:16px">' + (html.value || '') + '</body></html>')

onMounted(async () => {
  await loadOptions()
  if (previewable.value) loadPreview()
})
watch([previewable, () => props.project], ([ok, p]) => {
  if (ok && p && !html.value && !loadError.value) loadPreview()
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: baseline; width: 100%; gap: 12px; }
.step-title { font-size: 16px; font-weight: 700; }
.card-head .meta { font-size: 12px; color: var(--muted); }
.muted { color: var(--muted); font-size: 13px; }
.state-error { padding: 36px 16px; }
.state-title { font-weight: 700; margin: 8px 0 4px; }
.state-msg { color: var(--muted); font-size: 13px; margin-bottom: 12px; }
.state-empty { padding: 20px 0; }
.ctrl-bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.theme-select { width: 200px; }
.hl-select { width: 190px; }
.degrade-alert { margin-bottom: 10px; }
.preview-wrap { border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--paper); min-height: 400px; }
.preview-frame { width: 100%; min-height: 640px; border: 0; display: block; }

@media (max-width: 768px) {
  .theme-select, .hl-select { width: 100%; }
  .preview-frame { min-height: 480px; }
}
</style>