<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">Car Knowledge Base</span>
          <h2>车型知识库</h2>
        </div>
        <div class="actions">
          <el-button v-if="user.isEditorOrAbove" @click="$router.push('/car/sync')">
            <el-icon class="btn-icon"><Refresh /></el-icon>同步车型
          </el-button>
          <el-button v-if="user.isAdmin" :loading="rebuildingAll" @click="onRebuildAll">
            <el-icon class="btn-icon"><MagicStick /></el-icon>重建全部向量
          </el-button>
        </div>
      </div>

      <div v-if="loading" class="loading"><el-skeleton :rows="4" animated /></div>

      <div v-else-if="error" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">车型知识库加载失败</div>
        <div class="state-msg">{{ error }}</div>
        <el-button type="primary" plain @click="load">重试</el-button>
      </div>

      <template v-else>
        <!-- 统计概览条 -->
        <div class="stats-bar">
          <div class="stat"><span class="stat-num">{{ rows.length }}</span><span class="stat-label">全部车型</span></div>
          <div class="stat"><span class="stat-num ok">{{ countBy('SUCCESS') }}</span><span class="stat-label">已同步</span></div>
          <div class="stat"><span class="stat-num warn">{{ countBy('PENDING') }}</span><span class="stat-label">待同步</span></div>
          <div class="stat"><span class="stat-num err">{{ countBy('FAILED') }}</span><span class="stat-label">失败</span></div>
        </div>

        <!-- 同步进度面板 -->
        <div v-if="job" class="job-panel" :class="jobClass">
          <div class="job-head">
            <span class="job-title">
              <el-icon class="spin" v-if="job.status === 'RUNNING'"><Loading /></el-icon>
              <el-icon v-else><CircleCheck /></el-icon>
              {{ jobTitle }}
            </span>
            <span class="job-count">{{ job.success }} 成功 · {{ job.failed }} 失败</span>
          </div>
          <el-progress v-if="job.status === 'RUNNING'" :percentage="progress" :stroke-width="8" :show-text="false" />
          <div v-if="job.status === 'RUNNING'" class="job-progress-text">
            已处理 {{ job.success + job.failed }} / {{ job.total }} 个车型
          </div>
        </div>

        <!-- 空态 -->
        <div v-if="!rows.length" class="empty">
          <el-empty description="车型知识库为空。点击右上「同步车型」，从比亚迪官网拉取车型数据入库。" />
        </div>

        <template v-else>
          <!-- 工具栏：搜索 + 网络筛选 + 状态筛选 -->
          <div class="lib-toolbar">
            <el-input v-model="keyword" placeholder="搜索车型 / 网络" clearable class="search">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-select v-model="networkFilter" class="filter" placeholder="全部网络">
              <el-option label="全部网络" value="all" />
              <el-option v-for="n in networks" :key="n" :label="n" :value="n" />
            </el-select>
            <el-select v-model="statusFilter" class="filter" placeholder="全部状态">
              <el-option label="全部状态" value="all" />
              <el-option label="已同步" value="SUCCESS" />
              <el-option label="待同步" value="PENDING" />
              <el-option label="同步中" value="SYNCING" />
              <el-option label="失败" value="FAILED" />
            </el-select>
          </div>

          <div v-if="!filtered.length" class="tab-empty">没有符合条件的车型</div>
          <div v-else class="car-grid">
            <div v-for="row in filtered" :key="row.id" class="car-card">
              <div class="thumb" @click="$router.push(`/car/${row.id}`)">
                <el-image v-if="thumbUrl(row)" :src="thumbUrl(row)" fit="cover" lazy />
                <div v-else class="thumb-ph"><el-icon :size="22"><Van /></el-icon></div>
              </div>
              <div class="card-body">
                <div class="c-name serif" @click="$router.push(`/car/${row.id}`)">{{ row.name }}</div>
                <div class="c-meta">{{ row.salesNetwork || '—' }} · {{ row.priceRange || '价格待同步' }}</div>
                <div class="c-status">
                  <el-tag size="small" :type="statusTagType(row.syncStatus)" effect="light" round>{{ statusLabel(row.syncStatus) }}</el-tag>
                  <span class="c-time">{{ fmtTime(row.lastSyncAt) }}</span>
                </div>
                <div class="c-actions" v-if="user.isEditorOrAbove">
                  <el-button size="small" text type="primary" @click="$router.push(`/car/${row.id}`)">详情</el-button>
                  <el-button size="small" text :loading="syncingId === row.id" @click="onSyncOne(row)">同步</el-button>
                  <el-button size="small" text type="danger" @click="onDel(row)">删除</el-button>
                </div>
              </div>
            </div>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { carApi } from '../api'
import { useUserStore } from '../store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import TopBar from '../layouts/TopBar.vue'
import { Refresh, WarningFilled, Search, Loading, CircleCheck, Van, MagicStick } from '@element-plus/icons-vue'

const user = useUserStore()
const loading = ref(false)
const error = ref('')
const rows = ref([])
const keyword = ref('')
const networkFilter = ref('all')
const statusFilter = ref('all')

// 单车型同步任务
const syncingId = ref(null)
const job = ref(null)
const jobId = ref(null)
// 批量重建全部向量(ADMIN,一次性运维)
const rebuildingAll = ref(false)
const onRebuildAll = () => {
  ElMessageBox.confirm('对全部车型重建向量(新切块口径+补齐缺失块),耗时较长(每车型约十几秒),确认执行?', '批量重建向量', { type: 'warning' })
    .then(async () => {
      rebuildingAll.value = true
      try {
        const { data } = await http.post('/car/models/rebuild-all')
        const st = data.data || {}
        st.failed > 0
          ? ElMessage.warning(`重建完成:成功 ${st.success}/${st.total},失败车型 ${st.failed}(id: ${st.failedModelIds})`)
          : ElMessage.success(`重建完成:${st.success}/${st.total} 个车型已按新口径重建`)
      } catch (e) {
        ElMessage.error(e?.response?.data?.msg || '批量重建失败')
      } finally {
        rebuildingAll.value = false
      }
    }).catch(() => {})
}
let pollTimer = null

const networks = computed(() => [...new Set(rows.value.map(r => r.salesNetwork).filter(Boolean))])

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return rows.value.filter(r => {
    if (kw && !(r.name || '').toLowerCase().includes(kw) && !(r.salesNetwork || '').toLowerCase().includes(kw)) return false
    if (networkFilter.value !== 'all' && r.salesNetwork !== networkFilter.value) return false
    if (statusFilter.value !== 'all' && r.syncStatus !== statusFilter.value) return false
    return true
  })
})

const countBy = (s) => rows.value.filter(r => r.syncStatus === s).length

const thumbUrl = (row) => {
  if (!row.introImages) return ''
  try {
    const arr = JSON.parse(row.introImages)
    return Array.isArray(arr) && arr.length ? arr[0] : ''
  } catch { return '' }
}

const progress = computed(() => {
  if (!job.value || !job.value.total) return 0
  return Math.round(((job.value.success + job.value.failed) / job.value.total) * 100)
})
const jobTitle = computed(() => {
  if (!job.value) return ''
  return { RUNNING: '同步进行中…', SUCCESS: '同步完成', PARTIAL: '部分完成', FAILED: '同步失败' }[job.value.status] || '同步'
})
const jobClass = computed(() => job.value?.status?.toLowerCase() || '')

const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')
const statusLabel = (s) => ({ PENDING: '待同步', SYNCING: '同步中', SUCCESS: '已同步', FAILED: '失败' }[s] || s || '—')
const statusTagType = (s) => ({ SUCCESS: 'success', FAILED: 'danger', SYNCING: 'warning', PENDING: 'info' }[s] || 'info')

const load = async () => {
  loading.value = true
  error.value = ''
  try { const res = await carApi.list(); rows.value = res.data || [] }
  catch (e) { error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试' }
  finally { loading.value = false }
}

// 单车型同步：创建异步任务 + 轮询进度
const onSyncOne = async (row) => {
  syncingId.value = row.id
  try {
    const res = await carApi.createJob([row.goodsId])
    if (res.code === 0) {
      jobId.value = res.data.jobId
      startPoll()
    } else ElMessage.error(res.msg || '创建任务失败')
  } catch (e) { ElMessage.error('创建任务失败：' + (e.message || e)) }
  finally { syncingId.value = null }
}

const startPoll = () => {
  stopPoll()
  pollTimer = setInterval(poll, 2000)
  poll()
}

const poll = async () => {
  if (!jobId.value) return
  try {
    const res = await carApi.getJob(jobId.value)
    if (res.code === 0) {
      job.value = res.data
      if (res.data.status !== 'RUNNING') {
        stopPoll()
        if (res.data.status === 'SUCCESS') ElMessage.success(`同步完成，入库 ${res.data.success} 个车型`)
        else if (res.data.failed > 0) ElMessage.warning(`同步完成，${res.data.success} 成功 / ${res.data.failed} 失败`)
        await load()
      }
    }
  } catch (e) { /* 轮询失败静默，下次重试 */ }
}

const stopPoll = () => { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } }

const onDel = async (row) => {
  await ElMessageBox.confirm(`确认删除车型「${row.name}」及其全部参数/向量？`, '删除', { type: 'warning' })
  await carApi.remove(row.id)
  ElMessage.success('已删除'); await load()
}

onMounted(load)
onBeforeUnmount(stopPoll)
</script>

<style scoped>
.btn-icon { margin-right: 2px; }

/* 统计概览条 */
.stats-bar { display: flex; gap: 24px; padding: 14px 0; margin-bottom: 8px; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.stat { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.stat-num { font-family: var(--font-serif); font-size: 22px; font-weight: 700; line-height: 1; }
.stat-num.ok { color: var(--ok); }
.stat-num.warn { color: var(--warn); }
.stat-num.err { color: var(--err); }
.stat-label { font-size: 12px; color: var(--muted); }

/* 工具栏 */
.lib-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.search { width: 240px; }
.filter { width: 130px; }
.tab-empty { padding: 40px 0; text-align: center; color: var(--faint); }

/* 卡片网格 */
.car-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; }
.car-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  overflow: hidden;
  transition: box-shadow .2s;
}
.car-card:hover { box-shadow: var(--shadow-hover); }
.thumb { position: relative; aspect-ratio: 4 / 3; background: var(--paper); cursor: pointer; }
.thumb :deep(.el-image) { width: 100%; height: 100%; }
.thumb-ph { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; color: var(--faint); }
.card-body { padding: 10px 12px 12px; }
.c-name { font-weight: 700; font-size: 15px; line-height: 1.4; cursor: pointer; }
.c-name:hover { color: var(--brand); }
.c-meta { color: var(--muted); font-size: 12px; margin: 4px 0 8px; }
.c-status { display: flex; justify-content: space-between; align-items: center; gap: 6px; }
.c-time { font-size: 11px; color: var(--faint); }
.c-actions { display: flex; justify-content: flex-end; gap: 2px; margin-top: 6px; }

/* 进度面板 */
.job-panel {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px 16px;
  margin-bottom: 16px;
  background: var(--card);
}
.job-panel.success { border-color: color-mix(in srgb, var(--ok) 40%, var(--line)); }
.job-panel.partial, .job-panel.failed { border-color: color-mix(in srgb, var(--warn) 40%, var(--line)); }
.job-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.job-title { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.job-count { color: var(--muted); font-size: 13px; }
.job-progress-text { font-size: 12px; color: var(--muted); margin-top: 6px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 768px) {
  .search { width: 100%; }
  .filter { width: 100%; }
  .car-grid { grid-template-columns: repeat(2, 1fr); }
  .c-actions .el-button { min-height: 40px; }
}
</style>
