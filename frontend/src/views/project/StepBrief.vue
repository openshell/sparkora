<template>
  <el-card class="step-card" shadow="never">
    <template #header>
      <span class="card-head">
        Step 1 · 生成创作简报
        <span v-if="brief" class="meta">模型 {{ brief.aiModel }} · {{ brief.tokenUsage }} tokens</span>
      </span>
    </template>

    <el-alert v-if="project && project.lastBriefError" type="error" :closable="false" show-icon
              :title="`上次生成失败：${project.lastBriefError}`" class="brief-alert" />

    <el-skeleton v-if="generating" :rows="6" animated />

    <div v-else-if="!brief" class="muted">
      <p>由 AI 生成标题候选 / 受众 / 核心观点 / 大纲 / 事实风险点，确认后进入多版本生成。</p>
      <el-button type="primary" :loading="generating" @click="onGenerateBrief">生成简报</el-button>
    </div>

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
        <el-button type="success" :disabled="!brief" @click="gotoVersions">进入多版本生成 →</el-button>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectApi } from '../../api'
import { ElMessage } from 'element-plus'

const props = defineProps({ project: Object, reloadProject: Function })

const route = useRoute()
const router = useRouter()
const brief = ref(null)
const generating = ref(false)

const gotoVersions = () => router.push({ name: 'project-versions', params: { id: route.params.id } })

const riskType = (l) => ({ high: 'danger', medium: 'warning', low: 'info' }[l] || 'info')
const riskLabel = (l) => ({ high: '高风险', medium: '中风险', low: '低风险' }[l] || l)

const parseBrief = (b) => {
  if (!b) return null
  const j = (s) => { try { return JSON.parse(s) } catch { return [] } }
  return { ...b, titleCandidates: j(b.titleCandidates), coreViewpoints: j(b.coreViewpoints),
    outline: j(b.outline), factRisks: j(b.factRisks) }
}
const loadBrief = async () => {
  const res = await projectApi.getBrief(route.params.id)
  brief.value = parseBrief(res.data)
}
const onGenerateBrief = async () => {
  generating.value = true
  try {
    const res = await projectApi.generateBrief(route.params.id)
    if (res.code === 0) { brief.value = parseBrief(res.data); ElMessage.success('简报已生成') }
    else ElMessage.error(res.msg || '生成失败')
    await props.reloadProject()
  } catch (e) { ElMessage.error('生成失败：' + (e.message || e)); await props.reloadProject() }
  finally { generating.value = false }
}
onMounted(loadBrief)
</script>

<style scoped>
.step-card { margin-bottom: 12px; }
.card-head { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.card-head .meta { font-size: 12px; color: var(--muted); font-weight: normal; }
.muted { color: var(--muted); font-size: 13px; }
.brief-alert { margin-bottom: 12px; }
.brief-sec { margin-bottom: 16px; }
.brief-label { font-weight: 600; margin-bottom: 6px; font-size: 14px; }
.brief-text { font-size: 14px; line-height: 1.6; }
.tag-row { display: flex; flex-wrap: wrap; gap: 8px; }
.title-tag { max-width: 100%; height: auto; white-space: normal !important; word-break: break-word; line-height: 1.4; padding: 4px 10px; }
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
@media (max-width: 768px) { .title-tag { width: 100%; } .brief-actions .el-button { flex: 1; } }
</style>
