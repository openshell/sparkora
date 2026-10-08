<template>
  <div class="source-job">
    <div class="panel-toolbar">
      <span class="panel-title">采集任务</span>
      <span class="toolbar-hint">采集中 2s 轮询进度；失败项可重试（整源/整栏目幂等重采）。</span>
      <el-select v-model="sourceFilter" clearable placeholder="全部信源" class="src-filter"
        @change="load">
        <el-option v-for="s in sourceOptions" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>
      <el-button class="refresh-btn" :loading="loading" @click="load">
        <el-icon class="btn-icon"><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <div v-if="loading && !rows.length" class="loading"><el-skeleton :rows="4" animated /></div>

    <div v-else-if="error" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">任务加载失败</div>
      <div class="state-msg">{{ error }}</div>
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>

    <div v-else-if="!rows.length" class="empty"><el-empty description="暂无采集任务。触发一次采集后，进度会显示在这里。" /></div>

    <el-table v-else :data="rows" size="small" class="job-table">
      <el-table-column label="任务" prop="id" width="70" />
      <el-table-column label="信源" min-width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ sourceName(row.sourceId) }}</template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ jobTypeText(row.jobType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTagType(row.status)" effect="plain">
            <el-icon v-if="row.status === 'RUNNING'" class="spin"><Loading /></el-icon>
            {{ statusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度（总/成/败/降级）" min-width="150">
        <template #default="{ row }">
          <span>{{ row.total || 0 }} / {{ row.success || 0 }} / </span>
          <span :class="{ bad: row.failed > 0 }">{{ row.failed || 0 }}</span>
          <span> / </span>
          <span :class="{ warn: row.degraded > 0 }">{{ row.degraded || 0 }}</span>
          <el-progress v-if="row.status === 'RUNNING'" class="row-progress" :percentage="progressOf(row)" :stroke-width="4" :show-text="false" />
        </template>
      </el-table-column>
      <el-table-column label="起止时间" min-width="180">
        <template #default="{ row }">
          <div class="t-line">{{ fmtTime(row.startedAt) }}</div>
          <div class="t-line muted">→ {{ fmtTime(row.finishedAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="错误" min-width="120" show-overflow-tooltip>
        <template #default="{ row }"><span class="muted">{{ row.errorMsg || '—' }}</span></template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="right">
        <template #default="{ row }">
          <el-button size="small" text @click="openDetail(row)">明细</el-button>
          <el-button v-if="user.isEditorOrAbove" size="small" text type="primary"
            :disabled="!hasFailed(row)" :loading="retryingId === row.id" @click="onRetry(row)">重试失败</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 失败明细抽屉 -->
    <el-drawer v-model="detailDlg" title="任务明细" size="90%" style="max-width:640px">
      <div v-if="detail">
        <div class="d-meta">
          <el-tag size="small" :type="statusTagType(detail.status)" effect="plain">{{ statusText(detail.status) }}</el-tag>
          <span>任务 #{{ detail.id }}</span>
          <span v-if="detail.createdBy">· 创建人 {{ detail.createdBy }}</span>
        </div>
        <div class="d-stat">
          总数 {{ detail.total || 0 }} · 成功 {{ detail.success || 0 }} · 失败 {{ detail.failed || 0 }} · 降级 {{ detail.degraded || 0 }}
        </div>
        <div v-if="detail.errorMsg" class="d-error">任务错误：{{ detail.errorMsg }}</div>
        <div class="d-sub">失败明细（{{ failedItems(detail).length }}）</div>
        <el-table v-if="failedItems(detail).length" :data="failedItems(detail)" size="small" max-height="360">
          <el-table-column label="标题" prop="title" min-width="180" show-overflow-tooltip />
          <el-table-column label="externalId" prop="externalId" width="140" show-overflow-tooltip />
          <el-table-column label="错误" prop="error" min-width="200" show-overflow-tooltip />
        </el-table>
        <el-empty v-else description="无失败项" :image-size="72" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, WarningFilled, Loading } from '@element-plus/icons-vue'
import { sourceJobApi } from '../../api'
import { useUserStore } from '../../store/user'

const props = defineProps({
  // 信源选项（名称映射 / 筛选下拉），由父面板复用同一份 sourceApi.list 结果下发
  sourceOptions: { type: Array, default: () => [] }
})

const user = useUserStore()
const rows = ref([])
const loading = ref(false)
const error = ref('')
const sourceFilter = ref(null)
const retryingId = ref(null)

const detailDlg = ref(false)
const detail = ref(null)

let pollTimer = null

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const params = sourceFilter.value == null ? {} : { sourceId: sourceFilter.value }
    const res = await sourceJobApi.list(params)
    rows.value = res.data || []
    syncPoll()
  } catch (e) {
    error.value = e?.response?.data?.msg || e.message || '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}

const sourceName = (id) => props.sourceOptions.find(s => s.id === id)?.name || (id != null ? `#${id}` : '—')

const jobTypeText = (t) => ({ SCHEDULED: '定时', MANUAL: '手动', RETRY: '重试' }[t] || t || '—')
const statusText = (s) => ({ RUNNING: '采集中', SUCCESS: '成功', PARTIAL: '部分成功', FAILED: '失败' }[s] || s)
const statusTagType = (s) => ({ RUNNING: 'primary', SUCCESS: 'success', PARTIAL: 'warning', FAILED: 'danger' }[s] || 'info')
const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

const progressOf = (row) => {
  if (!row.total) return 0
  return Math.round((((row.success || 0) + (row.failed || 0) + (row.degraded || 0)) / row.total) * 100)
}

const hasFailed = (row) => (row.failed || 0) > 0 || failedItems(row).length > 0

// failedItems 后端为 JSON 字符串（或已解析数组），解析失败容错为空数组
const failedItems = (job) => {
  const raw = job?.failedItems
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

const openDetail = async (row) => {
  detailDlg.value = true
  detail.value = row
  try {
    const res = await sourceJobApi.get(row.id)
    if (res.code === 0) detail.value = res.data
  } catch { /* 明细失败沿用列表行数据 */ }
}

const onRetry = async (row) => {
  retryingId.value = row.id
  try {
    const res = await sourceJobApi.retry(row.id)
    if (res.code === 0 && res.data?.jobId != null) {
      ElMessage.success('已创建重试任务，正在监控进度')
      await load()
    } else {
      ElMessage.error(res.msg || '重试失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '重试失败')
  } finally {
    retryingId.value = null
  }
}

// ==================== 2s 轮询（照 NewsKnowledgePanel 同步轮询范式） ====================

const syncPoll = () => {
  const hasRunning = rows.value.some(j => j.status === 'RUNNING')
  if (hasRunning) startPoll()
  else stopPoll()
}

const startPoll = () => {
  if (pollTimer) return
  pollTimer = setInterval(poll, 2000)
}

const poll = async () => {
  try {
    const params = sourceFilter.value == null ? {} : { sourceId: sourceFilter.value }
    const res = await sourceJobApi.list(params)
    if (res.code === 0) {
      rows.value = res.data || []
      if (!rows.value.some(j => j.status === 'RUNNING')) stopPoll()
    }
  } catch { /* 轮询失败静默，下次重试 */ }
}

const stopPoll = () => { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } }

onMounted(load)
onBeforeUnmount(stopPoll)

defineExpose({ load })
</script>

<style scoped>
.source-job { margin-bottom: var(--sp-6); }
.panel-toolbar { display: flex; align-items: center; gap: var(--sp-4); margin-bottom: var(--sp-4); flex-wrap: wrap; }
.panel-title { font-size: var(--fs-16); font-weight: 600; }
.toolbar-hint { color: var(--faint); font-size: var(--fs-12); }
.src-filter { width: 180px; margin-left: auto; }
.refresh-btn { margin-left: 0; }
.btn-icon { margin-right: 4px; }
.job-table { width: 100%; }
.bad { color: var(--el-color-danger); }
.warn { color: var(--warn); }
.muted { color: var(--faint); }
.t-line { font-size: var(--fs-12); }
.row-progress { margin-top: var(--sp-2); }
.loading, .empty { padding: 40px 0; }
.state-error { padding: 40px 0; text-align: center; }
.state-title { margin: 10px 0 4px; font-weight: 600; }
.state-msg { color: var(--faint); font-size: var(--fs-13); margin-bottom: 14px; }
.spin { animation: spin 1s linear infinite; margin-right: 2px; }
@keyframes spin { to { transform: rotate(360deg); } }

.d-meta { display: flex; align-items: center; gap: var(--sp-4); color: var(--faint); font-size: var(--fs-12); }
.d-stat { margin: var(--sp-4) 0; font-size: var(--fs-14); }
.d-error { color: var(--el-color-danger); font-size: var(--fs-13); margin-bottom: var(--sp-4); }
.d-sub { font-weight: 600; margin: var(--sp-5) 0 var(--sp-3); }
</style>
