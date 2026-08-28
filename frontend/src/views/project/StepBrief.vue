<template>
  <el-card class="step-card" shadow="never">
    <template #header>
      <span class="card-head">
        <span class="step-title serif">Step 1 · 生成创作简报</span>
        <span v-if="brief" class="meta">模型 {{ brief.aiModel }} · {{ brief.tokenUsage }} tokens</span>
      </span>
    </template>

    <el-alert v-if="project && project.lastBriefError && !generatingBrief" type="error" :closable="false" show-icon
              :title="`上次生成失败：${project.lastBriefError}`" class="brief-alert" />

    <!-- 简报加载失败(网络抖动/后端重启窗口):可见化 + 重试,不再静默退化成引导语 -->
    <div v-else-if="briefError" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">简报加载失败</div>
      <div class="state-msg">{{ briefError }}</div>
      <el-button type="primary" plain @click="loadBrief">重试</el-button>
    </div>

    <!-- 生成中:以 project.status 为唯一事实源(刷新/切页返回也能恢复),轮询直至状态翻转 -->
    <div v-if="generatingBrief" class="generating">
      <el-skeleton :rows="6" animated />
      <p class="gen-tip">
        <el-icon class="spin"><Loading /></el-icon>
        AI 正在生成创作简报（标题候选 / 受众 / 核心观点 / 大纲 / 事实风险点），通常需要 1~2 分钟，请勿关闭页面…
      </p>
    </div>

    <div v-else-if="!brief" class="muted intro">
      <p>由 AI 生成标题候选 / 受众 / 核心观点 / 大纲 / 事实风险点，确认后进入多版本生成。</p>
      <el-button type="primary" :loading="submitting" @click="onGenerateBrief">
        <el-icon class="btn-icon"><MagicStick /></el-icon>生成简报
      </el-button>
    </div>

    <div v-else class="brief">
      <section class="brief-sec">
        <div class="brief-label"><el-icon><CollectionTag /></el-icon>标题候选</div>
        <div class="tag-row">
          <el-tag v-for="(t,i) in brief.titleCandidates" :key="i" type="info" effect="plain" class="title-tag serif">{{ t }}</el-tag>
        </div>
      </section>
      <section class="brief-sec">
        <div class="brief-label"><el-icon><User /></el-icon>目标读者</div>
        <div class="brief-text">{{ brief.audienceRefine }}</div>
      </section>
      <section class="brief-sec">
        <div class="brief-label"><el-icon><Lightning /></el-icon>核心观点</div>
        <ul class="list"><li v-for="(v,i) in brief.coreViewpoints" :key="i">{{ v }}</li></ul>
      </section>
      <section class="brief-sec">
        <div class="brief-label"><el-icon><Tickets /></el-icon>大纲</div>
        <div v-for="(o,i) in brief.outline" :key="i" class="outline-item">
          <div class="outline-head serif">{{ i+1 }}. {{ o.heading }}</div>
          <ul class="sub-list"><li v-for="(s,j) in o.subPoints" :key="j">{{ s }}</li></ul>
        </div>
      </section>
      <section class="brief-sec">
        <div class="brief-label"><el-icon><Warning /></el-icon>事实风险点</div>
        <div v-for="(r,i) in brief.factRisks" :key="i" class="risk-item">
          <div class="risk-line">
            <el-tag :type="riskType(r.riskLevel)" size="small" class="risk-tag">{{ riskLabel(r.riskLevel) }}</el-tag>
            <div class="risk-claim">{{ r.claim }}</div>
          </div>
          <div class="risk-sug">建议：{{ r.suggestion }}</div>
        </div>
      </section>

      <div class="brief-actions">
        <el-button :loading="submitting" @click="onGenerateBrief">重新生成</el-button>
        <el-button type="success" @click="gotoVersions">
          {{ hasVersions ? '查看版本 →' : '进入多版本生成 →' }}
        </el-button>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectApi } from '../../api'
import { ElMessage } from 'element-plus'
import { isGeneratingBrief } from '../../constants/project'
import { Loading, MagicStick, CollectionTag, User, Lightning, Tickets, Warning, WarningFilled } from '@element-plus/icons-vue'

const props = defineProps({ project: Object, reloadProject: Function })

const route = useRoute()
const router = useRouter()
const brief = ref(null)
const briefError = ref('')      // 简报拉取失败信息(网络层);空=加载正常
const submitting = ref(false)   // 本轮会话内主动点击的 loading(按钮态)
const polling = ref(null)       // 生成中轮询定时器

// 生成中状态:以 project.status 为唯一事实源,刷新/切页返回均能恢复视图
const generatingBrief = computed(() => isGeneratingBrief(props.project?.status))

// 已生成过版本时,按钮文案改为「查看版本」
const hasVersions = computed(() => props.project?.status === 'VERSIONS_READY' || props.project?.status === 'GENERATING_VERSIONS')

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
  briefError.value = ''
  try {
    const res = await projectApi.getBrief(route.params.id)
    // 无 brief 时后端返回 HTTP 200 + data:null(正常路径,不抛错),展示引导语属预期
    brief.value = parseBrief(res.data)
  } catch (e) {
    // 只有网络层失败才会到这:可见化并提供重试,不再静默退化成引导语
    briefError.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试'
  }
}

// 轮询 project 详情:GENERATING_BRIEF 翻转后(READY/DRAFT=失败回退)拉取简报
const stopPolling = () => { if (polling.value) { clearInterval(polling.value); polling.value = null } }
const startPolling = () => {
  stopPolling()
  polling.value = setInterval(async () => {
    try {
      const before = props.project?.status
      await props.reloadProject()
      if (props.project?.status !== before && !isGeneratingBrief(props.project?.status)) {
        stopPolling()
        await loadBrief()
      }
    } catch (e) { /* 轮询期间网络抖动静默,下一 tick 重试;401 由拦截器处理 */ }
  }, 4000)
}
watch(generatingBrief, (on) => { on ? startPolling() : stopPolling() }, { immediate: true })
onUnmounted(stopPolling)
onMounted(loadBrief)

const onGenerateBrief = async () => {
  submitting.value = true
  try {
    const res = await projectApi.generateBrief(route.params.id)
    if (res.code === 0) { brief.value = parseBrief(res.data); ElMessage.success('简报已生成') }
    else ElMessage.error(res.msg || '生成失败')
    await props.reloadProject()
  } catch (e) {
    // 失败已由后端回写 lastBriefError 并回退状态,提示交给 alert 与拦截器
  } finally { submitting.value = false }
}
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: baseline; width: 100%; gap: 12px; }
.step-title { font-size: 16px; font-weight: 700; }
.card-head .meta { font-size: 12px; color: var(--muted); font-weight: normal; white-space: nowrap; }
.muted { color: var(--muted); font-size: 13px; }
.intro p { line-height: 1.7; }
.brief-alert { margin-bottom: 12px; }
.state-error { padding: 36px 16px; }
.btn-icon { margin-right: 2px; }

.generating { padding: 4px 0; }
.gen-tip { display: flex; align-items: center; gap: 6px; margin: 12px 0 0; font-size: 13px; color: var(--muted); line-height: 1.6; }
.spin { animation: spin 1.2s linear infinite; color: var(--brand); }
@keyframes spin { to { transform: rotate(360deg); } }

.brief-sec { margin-bottom: 18px; }
.brief-label { display: flex; align-items: center; gap: 6px; font-weight: 700; margin-bottom: 8px; font-size: 13px; letter-spacing: .04em; color: var(--ink); }
.brief-label .el-icon { color: var(--brand); }
.brief-text { font-size: 14px; line-height: 1.7; }
.tag-row { display: flex; flex-wrap: wrap; gap: 8px; }
.title-tag { max-width: 100%; height: auto; white-space: normal !important; word-break: break-word; line-height: 1.5; padding: 6px 12px; font-size: 14px; }
.list, .sub-list { margin: 0; padding-left: 18px; }
.list li, .sub-list li { font-size: 14px; line-height: 1.8; }
.outline-item { margin-bottom: 10px; }
.outline-head { font-weight: 700; font-size: 14px; }
.sub-list { margin-top: 2px; }
.sub-list li { color: var(--muted); font-size: 13px; }
.risk-item { background: var(--el-fill-color-light); border-radius: var(--radius-sm); padding: 10px 12px; margin-bottom: 8px; }
.risk-line { display: flex; align-items: flex-start; gap: 8px; }
.risk-tag { flex-shrink: 0; margin-top: 2px; }
.risk-claim { font-size: 14px; line-height: 1.6; }
.risk-sug { font-size: 12px; color: var(--muted); margin-top: 4px; }
.brief-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
@media (max-width: 768px) { .title-tag { width: 100%; } .brief-actions .el-button { flex: 1; } }
</style>