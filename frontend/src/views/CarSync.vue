<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">Car Sync</span>
          <h2>车型同步</h2>
        </div>
        <div class="actions">
          <el-button @click="$router.push('/car')">返回车型库</el-button>
        </div>
      </div>

      <div v-if="loading" class="loading"><el-skeleton :rows="5" animated /></div>

      <div v-else-if="error" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">车型目录加载失败</div>
        <div class="state-msg">{{ error }}</div>
        <el-button type="primary" plain @click="load">重试</el-button>
      </div>

      <template v-else>
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
          <div v-if="failedItems.length" class="job-failed">
            <div class="failed-title">失败明细：</div>
            <div v-for="(f, i) in failedItems" :key="i" class="failed-item">
              <span class="f-name">{{ f.name || f.goodsId }}</span>
              <span class="f-err">{{ f.error }}</span>
            </div>
            <el-button v-if="job.status !== 'RUNNING'" size="small" type="primary" plain :loading="retrying" @click="onRetry">
              重试失败项
            </el-button>
          </div>
        </div>

        <!-- 工具栏：搜索 + 全选 + 同步 -->
        <div class="sync-bar">
          <el-input v-model="keyword" placeholder="搜索车型 / 网络" clearable class="search" :prefix-icon="Search">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-checkbox :model-value="allSelected" @change="toggleAll">全选</el-checkbox>
          <span class="sel-count">已选 {{ selected.length }} 个</span>
          <el-button type="primary" :loading="creating" :disabled="!selected.length" @click="onSync">
            <el-icon class="btn-icon"><Refresh /></el-icon>同步选中
          </el-button>
        </div>

        <!-- 选项卡：未入库 / 已入库 -->
        <el-tabs v-model="tab" class="car-tabs">
          <el-tab-pane :label="`未入库 (${notSynced.length})`" name="not">
            <div v-if="!notSynced.length" class="tab-empty">没有未入库的车型</div>
            <div v-else class="car-grid">
              <div v-for="c in notSynced" :key="c.id" class="car-card" :class="{ selected: isSelected(c.id) }"
                   @click="toggle(c.id)">
                <div class="thumb">
                  <el-image v-if="c.img" :src="c.img" fit="cover" lazy />
                  <div v-else class="thumb-ph"><el-icon :size="22"><Van /></el-icon></div>
                  <span class="check" v-if="isSelected(c.id)"><el-icon><Check /></el-icon></span>
                </div>
                <div class="card-body">
                  <div class="c-name serif">{{ c.name }}</div>
                  <div class="c-meta">{{ c.salesNetworkName || '—' }} · {{ c.price || '价格待同步' }}</div>
                  <el-tag size="small" type="warning" effect="plain" round>未入库</el-tag>
                </div>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane :label="`已入库 (${synced.length})`" name="synced">
            <div v-if="!synced.length" class="tab-empty">还没有已入库的车型</div>
            <div v-else class="car-grid">
              <div v-for="c in synced" :key="c.id" class="car-card" :class="{ selected: isSelected(c.id) }"
                   @click="toggle(c.id)">
                <div class="thumb">
                  <el-image v-if="c.img" :src="c.img" fit="cover" lazy />
                  <div v-else class="thumb-ph"><el-icon :size="22"><Van /></el-icon></div>
                  <span class="check" v-if="isSelected(c.id)"><el-icon><Check /></el-icon></span>
                </div>
                <div class="card-body">
                  <div class="c-name serif">{{ c.name }}</div>
                  <div class="c-meta">{{ c.salesNetworkName || '—' }} · {{ c.price || '价格待同步' }}</div>
                  <el-tag size="small" type="success" effect="plain" round>已入库</el-tag>
                </div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { carApi } from '../api'
import { ElMessage } from 'element-plus'
import TopBar from '../layouts/TopBar.vue'
import { Refresh, WarningFilled, Search, Check, Loading, CircleCheck, Van } from '@element-plus/icons-vue'

const loading = ref(false)
const error = ref('')
const catalog = ref([])
const syncedIds = ref(new Set())
const selected = ref([])
const keyword = ref('')
const tab = ref('not')
const creating = ref(false)
const retrying = ref(false)

// 同步任务
const job = ref(null)
const jobId = ref(null)
let pollTimer = null

const allSelected = computed(() => {
  const list = filtered.value
  return list.length > 0 && selected.value.length === list.length
})

// 目录项带 img 字段（官网缩略图）
const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return catalog.value
  return catalog.value.filter(c =>
    (c.name || '').toLowerCase().includes(kw) || (c.salesNetworkName || '').toLowerCase().includes(kw))
})

const notSynced = computed(() => filtered.value.filter(c => !syncedIds.value.has(String(c.id))))
const synced = computed(() => filtered.value.filter(c => syncedIds.value.has(String(c.id))))

const failedItems = computed(() => {
  if (!job.value?.failedItems) return []
  try { return JSON.parse(job.value.failedItems) || [] } catch { return [] }
})

const progress = computed(() => {
  if (!job.value || !job.value.total) return 0
  return Math.round(((job.value.success + job.value.failed) / job.value.total) * 100)
})

const jobTitle = computed(() => {
  if (!job.value) return ''
  return { RUNNING: '同步进行中…', SUCCESS: '同步完成', PARTIAL: '部分完成', FAILED: '同步失败' }[job.value.status] || '同步'
})
const jobClass = computed(() => job.value?.status?.toLowerCase() || '')

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const [catRes, listRes] = await Promise.all([carApi.catalog(), carApi.list()])
    catalog.value = catRes.data || []
    syncedIds.value = new Set((listRes.data || []).map(m => m.goodsId))
  } catch (e) { error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试' }
  finally { loading.value = false }
}

const isSelected = (id) => selected.value.includes(id)
const toggle = (id) => {
  selected.value = selected.value.includes(id)
    ? selected.value.filter(x => x !== id)
    : [...selected.value, id]
}
const toggleAll = (val) => {
  selected.value = val ? filtered.value.map(c => c.id) : []
}

const onSync = async () => {
  if (!selected.value.length) { ElMessage.warning('请选择要同步的车型'); return }
  creating.value = true
  try {
    const res = await carApi.createJob(selected.value)
    if (res.code === 0) {
      jobId.value = res.data.jobId
      selected.value = []
      startPoll()
    } else ElMessage.error(res.msg || '创建任务失败')
  } catch (e) { ElMessage.error('创建任务失败：' + (e.message || e)) }
  finally { creating.value = false }
}

const onRetry = async () => {
  if (!jobId.value) return
  retrying.value = true
  try {
    const res = await carApi.retryJob(jobId.value)
    if (res.code === 0) {
      jobId.value = res.data.jobId
      job.value = null
      startPoll()
    } else ElMessage.error(res.msg || '重试失败')
  } catch (e) { ElMessage.error('重试失败：' + (e.message || e)) }
  finally { retrying.value = false }
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

onMounted(load)
onBeforeUnmount(stopPoll)
</script>

<style scoped>
.btn-icon { margin-right: 2px; }
.sync-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; flex-wrap: wrap; }
.search { width: 240px; }
.sel-count { color: var(--muted); font-size: 13px; }

.car-tabs { margin-top: 4px; }
.tab-empty { padding: 40px 0; text-align: center; color: var(--faint); }

.car-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 14px; }
.car-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow .2s, border-color .2s;
}
.car-card:hover { box-shadow: var(--shadow-hover); }
.car-card.selected { border-color: var(--brand); box-shadow: 0 0 0 2px var(--brand-weak); }

.thumb { position: relative; aspect-ratio: 4 / 3; background: var(--paper); }
.thumb :deep(.el-image) { width: 100%; height: 100%; }
.thumb-ph { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; color: var(--faint); }
.check {
  position: absolute; top: 8px; right: 8px;
  width: 24px; height: 24px; border-radius: 50%;
  background: var(--brand); color: #fff;
  display: flex; align-items: center; justify-content: center;
}

.card-body { padding: 10px 12px 12px; }
.c-name { font-weight: 700; font-size: 15px; line-height: 1.4; }
.c-meta { color: var(--muted); font-size: 12px; margin: 4px 0 8px; }

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
.job-failed { margin-top: 10px; }
.failed-title { font-size: 13px; color: var(--muted); margin-bottom: 6px; }
.failed-item { display: flex; gap: 8px; font-size: 12px; padding: 4px 0; border-bottom: 1px dashed var(--line); }
.f-name { font-weight: 600; flex-shrink: 0; }
.f-err { color: var(--err); word-break: break-all; }

@media (max-width: 768px) {
  .search { width: 100%; }
  .car-grid { grid-template-columns: repeat(2, 1fr); }
  .sync-bar .el-button { min-height: 44px; }
}
</style>
