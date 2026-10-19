<template>
  <section class="panel byd-panel">
    <div class="panel-head">
      <span class="panel-title">BYD 王朝小程序本地推送</span>
      <span class="panel-hint">任务由服务端编排，写入由本机 Windows Agent 完成</span>
    </div>

    <el-alert v-if="loadError" type="error" :closable="false" show-icon title="BYD 推送状态加载失败" :description="loadError" />
    <el-alert v-else-if="loaded && !state.agentConfigured" type="warning" :closable="false" show-icon
              title="BYD 推送通道未启用" :description="state.agentDisabledReason || '服务端缺少 BYD Agent 配置'" />
    <el-alert v-else-if="loaded && !state.agentOnline" type="warning" :closable="false" show-icon
              title="本机 Agent 离线" description="请在微信所在 Windows 电脑启动 Local Agent；Agent 不在线时无法创建推送任务。" />
    <el-alert v-else-if="loaded && !preflightReady" type="error" :closable="false" show-icon
              title="本机预检未通过" :description="preflightErrors.join('；')" />

    <el-alert v-if="state.lastError" type="error" :closable="false" show-icon title="最近一次 BYD 推送失败" :description="state.lastError" />

    <div v-if="state.publishedAt" class="state-success">
      <el-icon color="var(--ok)"><SuccessFilled /></el-icon>
      <span>已推送到王朝小程序草稿箱：{{ shortTime(state.publishedAt) }}</span>
    </div>

    <div v-else-if="preflightReady" class="state-ready">
      <el-icon color="var(--ok)"><SuccessFilled /></el-icon>
      <span>Agent 已在线且本地预检通过，可以创建推送任务。</span>
    </div>

    <div class="agent-grid">
      <div><span>Agent</span><code>{{ state.agentId || '未连接' }}</code></div>
      <div><span>机器</span><span>{{ state.agentMachineName || '-' }}</span></div>
      <div><span>Agent 版本</span><span>{{ state.agentVersion || '-' }}</span></div>
      <div><span>草稿数量</span><span>{{ preflight.mmkvDraftCount ?? '-' }}</span></div>
      <div><span>微信进程</span><span>{{ wechatRunning ? '运行中' : '已退出' }}</span></div>
      <div><span>预检</span><span>{{ preflightReady ? '通过' : '未通过' }}</span></div>
    </div>

    <div class="club-form">
      <el-form label-width="72px" @submit.prevent>
        <el-form-item label="车友会">
          <el-input v-model="clubId" maxlength="20" placeholder="如 220；留空用默认" :disabled="!editorOrAbove || submitting" />
        </el-form-item>
        <el-form-item label="标签名">
          <el-input v-model="clubTitle" maxlength="80" placeholder="如 四川迪友会；留空用默认" :disabled="!editorOrAbove || submitting" />
        </el-form-item>
      </el-form>
      <p class="safety-tip">
        推送前必须完全退出微信电脑端（包括托盘），否则本机 Agent 会拒绝写入 MMKV。任务写入的是小程序草稿箱，不会修改公众号发布字段。
      </p>
      <el-button type="primary" :loading="submitting" :disabled="!canCreate" @click="createJob">
        推送到王朝小程序草稿箱
      </el-button>
      <el-button plain :loading="loading" :disabled="submitting" @click="reload">刷新状态</el-button>
    </div>

    <div v-if="job" class="job-status" :class="{ 'job-failed': job.status === 'FAILED' }">
      <div class="job-head">
        <span>最近任务：{{ statusLabel(job.status) }}</span>
        <code>{{ job.jobId }}</code>
      </div>
      <div class="job-meta">
        <span v-if="job.status === 'RUNNING'">本机 Agent 正在执行，请保持微信完全退出。</span>
        <span v-else-if="job.status === 'PENDING'">等待本机 Agent 领取。</span>
        <span v-else-if="job.status === 'SUCCESS'">草稿 ID：<code>{{ job.result?.draftId || job.result?.draftId }}</code></span>
        <span v-else-if="job.status === 'FAILED'">{{ job.errorMessage || '本机执行失败' }}</span>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { SuccessFilled } from '@element-plus/icons-vue'
import { bydApi } from '../../api'
import { useUserStore } from '../../store/user'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  editorOrAbove: { type: Boolean, default: false }
})

const userStore = useUserStore()
const editor = computed(() => props.editorOrAbove || userStore.isEditorOrAbove)
const loaded = ref(false)
const loading = ref(false)
const loadError = ref('')
const submitting = ref(false)
const clubId = ref('')
const clubTitle = ref('')
const state = ref({
  agentConfigured: false,
  agentOnline: false,
  agentId: null,
  agentMachineName: null,
  agentVersion: null,
  agentPreflightReady: false,
  agentPreflight: null,
  agentDisabledReason: null,
  clubId: null,
  clubTitle: null,
  publishedAt: null,
  lastError: null,
  lastJobId: null,
  latestJob: null
})
let pollTimer = null

const preflight = computed(() => state.value.agentPreflight || {})
const checks = computed(() => preflight.value.checks || {})
const preflightReady = computed(() => !!state.value.agentOnline && !!state.value.agentPreflightReady)
const preflightErrors = computed(() => {
  const errors = preflight.value.errors
  if (Array.isArray(errors) && errors.length) return errors
  if (preflight.value.ready === false) return ['本机环境暂不可用，请查看 Local Agent 日志后重新心跳']
  return []
})
const wechatRunning = computed(() => (checks.value.wechatProcessesRunning || []).length > 0)
const job = computed(() => state.value.latestJob)
const canCreate = computed(() => editor.value && preflightReady.value && !job.value?.status?.match?.(/^(PENDING|RUNNING)$/))

const shortTime = (value) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
const statusLabel = (value) => ({
  PENDING: '待领取', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败'
}[value] || value || '-')

async function loadState(showError = false) {
  loading.value = loading.value || !loaded.value
  try {
    const res = await bydApi.state(props.projectId)
    if (res.code !== 0 || !res.data) throw new Error(res.msg || '查询 BYD 推送状态失败')
    const data = res.data
    state.value = { ...state.value, ...data }
    clubId.value = data.clubId || clubId.value || ''
    clubTitle.value = data.clubTitle || clubTitle.value || ''
    loaded.value = true
    loadError.value = ''
  } catch (error) {
    loadError.value = error.response?.data?.msg || error.message || '请求失败'
    if (showError) ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

async function createJob() {
  submitting.value = true
  try {
    const res = await bydApi.createJob(props.projectId, {
      clubId: clubId.value.trim() || null,
      clubTitle: clubTitle.value.trim() || null
    })
    if (res.code !== 0 || !res.data) throw new Error(res.msg || '创建 BYD 推送任务失败')
    const data = res.data
    ElMessage.success('BYD 推送任务已创建，等待本机 Agent 领取')
    state.value = { ...state.value, ...data, publishedAt: null, latestJob: data }
    startPolling()
  } catch (error) {
    ElMessage.error(error.response?.data?.msg || error.message || '创建 BYD 推送任务失败')
  } finally {
    submitting.value = false
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    await loadState(false)
    if (job.value && ['SUCCESS', 'FAILED'].includes(job.value.status)) stopPolling()
  }, 3000)
}

function stopPolling() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = null
}

function reload() {
  loadState(true)
}

onMounted(() => {
  loadState(true)
  startPolling()
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.byd-panel { margin-top: 18px; }
.panel { background: var(--panel, #fff); border: 1px solid var(--line, #e8e8e8); border-radius: 12px; padding: 16px; }
.panel-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 14px; }
.panel-title { font-size: 16px; font-weight: 600; }
.panel-hint { color: var(--faint, #8a8f98); font-size: 12px; }
.byd-panel .el-alert + .el-alert { margin-top: 10px; }
.state-success, .state-ready { display: flex; gap: 8px; align-items: center; margin: 12px 0; }
.agent-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px; }
.agent-grid > div { padding: 10px; border: 1px solid var(--line, #ededed); border-radius: 8px; background: var(--soft, #fafafa); }
.agent-grid span:first-child { display: block; color: var(--faint, #8a8f98); font-size: 12px; margin-bottom: 4px; }
.club-form { border-top: 1px solid var(--line, #ededed); padding-top: 14px; }
.safety-tip { color: var(--faint, #8a8f98); font-size: 12px; line-height: 1.5; margin: 8px 0 10px; }
.job-status { margin-top: 14px; padding: 10px 12px; border-radius: 8px; background: var(--soft, #f6fff8); border: 1px solid var(--ok, #67c23a); }
.job-status.job-failed { background: #fff6f6; border-color: var(--danger, #f56c6c); }
.job-head { display: flex; align-items: center; gap: 10px; margin-bottom: 4px; }
.job-meta { color: var(--faint, #606266); font-size: 13px; }
@media (max-width: 900px) { .agent-grid { grid-template-columns: 1fr; } }
</style>
