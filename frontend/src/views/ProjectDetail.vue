<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <el-button text @click="$router.push('/')">← 返回工作台</el-button>
        <el-tag v-if="project">{{ statusLabel(project.status) }}</el-tag>
      </div>
      <h2 v-if="project">{{ project.topic }}</h2>

      <!-- 六步步骤条 -->
      <el-steps :active="activeStep" finish-status="success" class="steps" simple>
        <el-step title="简报" />
        <el-step title="版本" />
        <el-step title="校验" />
        <el-step title="配图" />
        <el-step title="预览" />
        <el-step title="发布" />
      </el-steps>

      <!-- Step 1：生成 Brief（S1 接真实 AI） -->
      <el-card class="step-card" shadow="never">
        <template #header>
          <span class="card-head">
            Step 1 · 生成创作 Brief
            <span v-if="brief" class="meta">模型 {{ brief.aiModel }} · {{ brief.tokenUsage }} tokens</span>
          </span>
        </template>

        <!-- 错误提示 -->
        <el-alert v-if="project && project.lastBriefError" type="error" :closable="false" show-icon
                  :title="`上次生成失败：${project.lastBriefError}`" class="brief-alert" />

        <!-- 进行中 -->
        <el-skeleton v-if="generating" :rows="6" animated />

        <!-- 空 brief -->
        <div v-else-if="!brief" class="muted">
          <p>由 AI 生成标题候选 / 受众 / 核心观点 / 大纲 / 事实风险点，确认后进入多版本生成。</p>
          <el-button type="primary" :loading="generating" @click="onGenerateBrief">生成 Brief</el-button>
        </div>

        <!-- brief 展示 -->
        <div v-else class="brief">
          <section class="brief-sec">
            <div class="brief-label">🏷 标题候选</div>
            <div class="tag-row">
              <el-tag v-for="(t,i) in brief.titleCandidates" :key="i" type="info" effect="plain" class="title-tag">{{ t }}</el-tag>
            </div>
          </section>

          <section class="brief-sec">
            <div class="brief-label">🎯 目标读者</div>
            <div class="brief-text">{{ brief.audienceRefine }}</div>
          </section>

          <section class="brief-sec">
            <div class="brief-label">💡 核心观点</div>
            <ul class="list"><li v-for="(v,i) in brief.coreViewpoints" :key="i">{{ v }}</li></ul>
          </section>

          <section class="brief-sec">
            <div class="brief-label">📋 大纲</div>
            <div v-for="(o,i) in brief.outline" :key="i" class="outline-item">
              <div class="outline-head">{{ i+1 }}. {{ o.heading }}</div>
              <ul class="sub-list"><li v-for="(s,j) in o.subPoints" :key="j">{{ s }}</li></ul>
            </div>
          </section>

          <section class="brief-sec">
            <div class="brief-label">⚠ 事实风险点</div>
            <div v-for="(r,i) in brief.factRisks" :key="i" class="risk-item">
              <el-tag :type="riskType(r.riskLevel)" size="small" class="risk-tag">{{ riskLabel(r.riskLevel) }}</el-tag>
              <div class="risk-claim">{{ r.claim }}</div>
              <div class="risk-sug">建议：{{ r.suggestion }}</div>
            </div>
          </section>

          <div class="brief-actions">
            <el-button :loading="generating" @click="onGenerateBrief">重新生成</el-button>
            <el-button type="success" :disabled="!brief || generatingVersions" :loading="generatingVersions" @click="onGenerateVersions">进入多版本生成 →</el-button>
          </div>
        </div>
      </el-card>

      <!-- Step 2：多版本正文生成 -->
      <el-card class="step-card" shadow="never">
        <template #header>
          <span class="card-head">
            Step 2 · 多版本正文生成
            <span v-if="versions.length" class="meta">共 {{ versions.length }} 版 · 当前：{{ currentVersionLabel }}</span>
          </span>
        </template>

        <el-alert v-if="project && project.lastVersionError" type="warning" :closable="false" show-icon
                  :title="`版本生成提示：${project.lastVersionError}`" class="brief-alert" />

        <el-skeleton v-if="generatingVersions" :rows="8" animated />

        <div v-else-if="!versions.length" class="muted">
          <p>基于简报生成 2 版正文（正式/活泼），逐版对比后选定一版。</p>
          <el-button type="primary" :disabled="!brief" :loading="generatingVersions" @click="onGenerateVersions">生成 2 版</el-button>
        </div>

        <div v-else class="version-grid">
          <div v-for="v in versions" :key="v.id" class="version-card" :class="{ active: v.id === project?.currentVersionId }">
            <div class="version-head">
              <el-tag size="small">{{ v.versionLabel }}</el-tag>
              <el-tag size="small" type="info" effect="plain">{{ v.styleTag }}</el-tag>
              <span class="version-meta">{{ v.wordCount }}字 · {{ v.aiModel }}</span>
            </div>
            <div class="version-title">{{ v.title }}</div>
            <div class="version-content markdown-body" v-html="renderMd(v.contentMd)"></div>
            <div class="version-actions">
              <el-button size="small" :type="v.id === project?.currentVersionId ? 'success' : 'default'" @click="onSetCurrent(v.id)">
                {{ v.id === project?.currentVersionId ? '✓ 当前' : '设为当前' }}
              </el-button>
              <el-button size="small" @click="onRegenerateVersion" :loading="generatingVersions">重生成</el-button>
            </div>
          </div>
        </div>
      </el-card>

      <el-card v-for="i in 4" :key="i" class="step-card disabled" shadow="never">
        <template #header><span>Step {{ i + 2 }} · {{ ['校验','配图','预览','发布'][i-1] }}（待后续阶段）</span></template>
        <p class="muted">此步骤在后续里程碑实现，当前暂不可用。</p>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import MarkdownIt from 'markdown-it'
import { projectApi } from '../api'
import { ElMessage } from 'element-plus'
import TopBar from '../layouts/TopBar.vue'

const md = new MarkdownIt({ html: false, breaks: true, linkify: true })
const renderMd = (src) => { try { return md.render(src || '') } catch { return '' } }

const route = useRoute()
const project = ref(null)
const brief = ref(null)
const versions = ref([])
const generating = ref(false)
const generatingVersions = ref(false)

// 步骤条激活位：GENETATING_BRIEF/DRAFT→0, READY→1, GENERATING_VERSIONS→1, VERSIONS_READY→2
const activeStep = computed(() => {
  const s = project.value?.status
  if (s === 'VERSIONS_READY' || s === 'GENERATING_VERSIONS') return s === 'VERSIONS_READY' ? 2 : 1
  return s === 'READY' ? 1 : 0
})

const currentVersionLabel = computed(() => {
  const cur = versions.value.find(v => v.id === project.value?.currentVersionId)
  return cur ? `${cur.versionLabel}·${cur.styleTag}` : '未选'
})

const statusLabel = (s) => ({
  DRAFT: '草稿', GENERATING_BRIEF: '简报生成中', READY: '简报就绪',
  GENERATING_VERSIONS: '版本生成中', VERSIONS_READY: '版本就绪'
}[s] || s)
const riskType = (l) => ({ high: 'danger', medium: 'warning', low: 'info' }[l] || 'info')
const riskLabel = (l) => ({ high: '高风险', medium: '中风险', low: '低风险' }[l] || l)

// 后端 brief 字段是 JSON 字符串，前端解析
const parseBrief = (b) => {
  if (!b) return null
  const j = (s) => { try { return JSON.parse(s) } catch { return [] } }
  return {
    ...b,
    titleCandidates: j(b.titleCandidates),
    coreViewpoints: j(b.coreViewpoints),
    outline: j(b.outline),
    factRisks: j(b.factRisks)
  }
}

const loadProject = async () => {
  const res = await projectApi.get(route.params.id)
  project.value = res.data
}
const loadBrief = async () => {
  const res = await projectApi.getBrief(route.params.id)
  brief.value = parseBrief(res.data)
}
const onGenerateBrief = async () => {
  generating.value = true
  try {
    const res = await projectApi.generateBrief(route.params.id)
    if (res.code === 0) {
      brief.value = parseBrief(res.data)
      ElMessage.success('Brief 已生成')
    } else {
      ElMessage.error(res.msg || '生成失败')
    }
    await loadProject()
  } catch (e) {
    ElMessage.error('生成失败：' + (e.message || e))
    await loadProject()
  } finally { generating.value = false }
}

const loadVersions = async () => {
  const res = await projectApi.listVersions(route.params.id)
  versions.value = res.data || []
}
const onGenerateVersions = async () => {
  generatingVersions.value = true
  try {
    const res = await projectApi.generateVersions(route.params.id, 2)
    if (res.code === 0) {
      versions.value = res.data || []
      ElMessage.success(`已生成 ${versions.value.length} 版`)
    } else {
      ElMessage.error(res.msg || '生成失败')
    }
    await loadProject()
  } catch (e) {
    ElMessage.error('生成失败：' + (e.message || e))
    await loadProject()
  } finally { generatingVersions.value = false }
}
const onSetCurrent = async (versionId) => {
  const res = await projectApi.setCurrentVersion(route.params.id, versionId)
  if (res.code === 0) {
    await loadProject()
    ElMessage.success('已设为当前版本')
  } else {
    ElMessage.error(res.msg || '设置失败')
  }
}
const onRegenerateVersion = () => onGenerateVersions()
onMounted(async () => {
  await loadProject()
  await loadBrief()
  await loadVersions()
})
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
.steps { margin: 16px 0; }
.step-card { margin-bottom: 12px; }
.step-card.disabled { opacity: .5; }
.card-head { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.card-head .meta { font-size: 12px; color: var(--muted); font-weight: normal; }
.muted { color: var(--muted); font-size: 13px; }
.brief-alert { margin-bottom: 12px; }
.brief-sec { margin-bottom: 16px; }
.brief-label { font-weight: 600; margin-bottom: 6px; font-size: 14px; }
.brief-text { font-size: 14px; line-height: 1.6; }
.tag-row { display: flex; flex-wrap: wrap; gap: 8px; }
.title-tag {
  max-width: 100%;
  height: auto;
  white-space: normal !important;
  word-break: break-word;
  line-height: 1.4;
  padding: 4px 10px;
}
.list, .sub-list { margin: 0; padding-left: 18px; }
.list li, .sub-list li { font-size: 14px; line-height: 1.7; }
.outline-item { margin-bottom: 8px; }
.outline-head { font-weight: 600; font-size: 14px; }
.sub-list { margin-top: 2px; }
.sub-list li { color: var(--muted); font-size: 13px; }
.risk-item { background: var(--el-fill-color-light); border-radius: 6px; padding: 8px 10px; margin-bottom: 8px; }
.risk-tag { margin-right: 8px; }
.risk-claim { font-size: 14px; margin: 4px 0; }
.risk-sug { font-size: 12px; color: var(--muted); }
.brief-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }

/* Step2 版本对比 */
.version-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 12px; }
.version-card { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 12px; background: var(--el-bg-color); }
.version-card.active { border-color: var(--el-color-success); box-shadow: 0 0 0 2px var(--el-color-success-light-7); }
.version-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 6px; }
.version-meta { font-size: 12px; color: var(--muted); margin-left: auto; }
.version-title { font-weight: 600; font-size: 15px; margin-bottom: 8px; line-height: 1.4; }
.version-content { font-size: 14px; line-height: 1.7; max-height: 520px; overflow-y: auto; }
.version-content :deep(h1) { font-size: 18px; margin: 12px 0 6px; }
.version-content :deep(h2) { font-size: 16px; margin: 10px 0 5px; }
.version-content :deep(h3) { font-size: 15px; margin: 8px 0 4px; }
.version-content :deep(p) { margin: 6px 0; }
.version-content :deep(ul), .version-content :deep(ol) { padding-left: 20px; margin: 6px 0; }
.version-content :deep(code) { background: var(--el-fill-color-light); padding: 1px 4px; border-radius: 3px; font-size: 13px; }
.version-actions { display: flex; gap: 8px; margin-top: 10px; }
@media (max-width: 768px) {
  .steps :deep(.el-step__title) { font-size: 12px; }
  .title-tag { width: 100%; }
  .brief-actions .el-button { flex: 1; }
  .version-grid { grid-template-columns: 1fr; }
  .version-actions .el-button { flex: 1; }
}
</style>
