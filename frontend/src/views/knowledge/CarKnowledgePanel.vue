<template>
  <div class="car-panel">
    <div class="panel-toolbar">
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
        <el-option label="失败" value="FAILED" />
        <el-option label="同步中" value="SYNCING" />
        <el-option label="待同步" value="PENDING" />
        <el-option label="官网无数据" value="EMPTY" />
        <el-option label="无封面" value="NONE" />
      </el-select>
      <el-select v-model="sortKey" class="sort" placeholder="排序">
        <el-option label="最近同步" value="recent" />
        <el-option label="名称" value="name" />
        <el-option label="价格" value="price" />
      </el-select>
      <el-button v-if="hasFilter" text class="clear-btn" @click="clearFilters">清除筛选</el-button>

      <div class="toolbar-actions">
        <el-button :loading="loading" @click="load">
          <el-icon class="btn-icon"><Refresh /></el-icon>刷新
        </el-button>
        <el-button v-if="user.isEditorOrAbove" type="primary" @click="drawerVisible = true">
          <el-icon class="btn-icon"><Refresh /></el-icon>同步车型
        </el-button>
        <el-dropdown v-if="user.isEditorOrAbove" trigger="click" @command="onOps">
          <el-button>
            <el-icon class="btn-icon"><Setting /></el-icon>运维
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="backfill">图片补入库（仅缺失/失败）</el-dropdown-item>
              <el-dropdown-item command="backfillFull" divided>全量图片重扫</el-dropdown-item>
              <el-dropdown-item v-if="user.isAdmin" command="cleanMissing" divided>补全清洗参数</el-dropdown-item>
              <el-dropdown-item v-if="user.isAdmin" command="rebuild" divided>重建全部向量</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <div v-if="loading" class="car-grid skeleton-grid">
      <div v-for="n in 8" :key="n" class="car-card">
        <el-skeleton animated>
          <template #template>
            <el-skeleton-item variant="image" class="sk-thumb" />
            <div class="sk-body">
              <el-skeleton-item variant="h3" style="width: 70%" />
              <el-skeleton-item variant="text" style="width: 90%; margin-top: 8px" />
            </div>
          </template>
        </el-skeleton>
      </div>
    </div>

    <div v-else-if="error" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">车型加载失败</div>
      <div class="state-msg">{{ error }}</div>
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>

    <template v-else>
      <div class="stats-bar">
        <div class="stat"><span class="stat-num">{{ rows.length }}</span><span class="stat-label">全部车型</span></div>
        <div class="stat"><span class="stat-num ok">{{ countBy('SUCCESS') }}</span><span class="stat-label">已同步</span></div>
        <div class="stat"><span class="stat-num err">{{ countBy('FAILED') }}</span><span class="stat-label">失败</span></div>
        <div class="stat"><span class="stat-num warn">{{ emptyCount }}</span><span class="stat-label">官网无数据</span></div>
        <div class="stat"><span class="stat-num warn">{{ countBy('SYNCING') }}</span><span class="stat-label">同步中</span></div>
        <div class="stat">
          <span class="stat-num">{{ coverCount }}<span class="stat-sub"> / {{ rows.length }}</span></span>
          <span class="stat-label">封面覆盖</span>
        </div>
      </div>

      <CarJobPanel v-if="job" :job="job" :retrying="retrying" :can-retry="user.isEditorOrAbove"
        :can-cancel="user.isEditorOrAbove" :cancelling="cancelling" closable
        @retry="onRetry" @cancel="onCancelJob" @close="onCloseJob" />

      <div v-if="rows.length" class="result-line">
        <span>共 {{ rows.length }} 台 · 当前显示 {{ filtered.length }} 台</span>
        <span v-if="hasFilter" class="filtered-hint">已按筛选条件过滤</span>
      </div>

      <div v-if="user.isEditorOrAbove && selectedIds.length" class="bulk-bar">
        <span class="bulk-count">已选 {{ selectedIds.length }} 个</span>
        <el-button size="small" text type="primary" :loading="bulkSyncing" :disabled="jobBusy" @click="onBulkSync">
          批量同步
        </el-button>
        <el-button size="small" type="danger" plain :loading="bulkDeleting" :disabled="jobBusy"
          title="同步任务运行中不可删除车型" @click="onBulkDelete">
          批量删除
        </el-button>
        <el-button size="small" text @click="selectedIds = []">清除选择</el-button>
      </div>

      <div v-if="!rows.length" class="empty">
        <el-empty description="车型库为空。点击「同步车型」，从比亚迪官网选择车型拉取入库。" />
      </div>

      <template v-else>
        <div v-if="!filtered.length" class="tab-empty">
          <span>没有符合条件的车型</span>
          <el-button v-if="hasFilter" text type="primary" @click="clearFilters">清除筛选</el-button>
        </div>
        <div v-else class="car-grid">
          <div v-for="row in filtered" :key="row.id" class="car-card" :class="{ selected: selectedIds.includes(row.id) }">
            <div class="thumb" :class="{ 'thumb--fallback': coverIsFallback(row) }" @click="goDetail(row)">
              <el-image v-if="coverUrlOf(row)" :src="coverUrlOf(row)"
                :fit="coverIsFallback(row) ? 'cover' : 'contain'" lazy class="cover-img" />
              <div v-else class="thumb-ph"><el-icon :size="22"><Van /></el-icon></div>
              <el-checkbox v-if="user.isEditorOrAbove" class="pick" :model-value="selectedIds.includes(row.id)"
                title="选择该车型" @click.stop @change="toggleSelect(row)" />
            </div>
            <div class="card-body">
              <div class="c-name serif" @click="goDetail(row)">{{ row.name }}</div>
              <div class="c-meta">
                {{ row.salesNetwork || '—' }} · {{ formatPrice(row.priceRange) || '价格待同步' }}
              </div>
              <div class="c-status">
                <el-tag size="small" :type="rowStatusMeta(row).type" effect="light" round>
                  {{ rowStatusMeta(row).label }}
                </el-tag>
                <span class="c-time">{{ fmtTime(row.lastCheckedAt || row.lastSyncAt) }}</span>
              </div>
              <el-tooltip v-if="row.syncStatus === 'FAILED' && row.lastSyncError"
                :content="row.lastSyncError" placement="top" :show-after="200">
                <div class="c-error">{{ row.lastSyncError }}</div>
              </el-tooltip>
              <div v-if="user.isEditorOrAbove" class="c-actions">
                <el-button size="small" text type="primary" @click="goDetail(row)">详情</el-button>
                <el-button v-if="row.syncStatus === 'FAILED' && row.sourceState !== 'EMPTY'" size="small" text type="warning"
                  :loading="syncingId === row.id" :disabled="jobBusy" @click="onSyncOne(row)">重试</el-button>
                <el-button v-else size="small" text :loading="syncingId === row.id"
                  :disabled="jobBusy || row.sourceState === 'EMPTY'"
                  :title="row.sourceState === 'EMPTY' ? '官网无数据，请使用「图片补入库」；全量同步会保留旧数据但不会补主图' : ''"
                  @click="onSyncOne(row)">同步</el-button>
                <el-button size="small" text type="danger" :disabled="jobBusy"
                  title="同步任务运行中不可删除车型" @click="onDel(row)">删除</el-button>
              </div>
            </div>
          </div>
        </div>
      </template>
    </template>

    <CarCatalogDrawer v-model="drawerVisible" :synced-ids="syncedGoodsIds" :busy="jobBusy" @created="onJobCreated" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { carApi } from '../../api'
import { useUserStore } from '../../store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, WarningFilled, Van, Setting } from '@element-plus/icons-vue'
import CarJobPanel from '../../components/car/CarJobPanel.vue'
import CarCatalogDrawer from '../../components/car/CarCatalogDrawer.vue'
import { useCarSyncJob } from '../../composables/useCarSyncJob'
import { coverIsFallback, coverUrlOf, formatPrice, sortCars, statusMeta } from '../../utils/carDisplay'

const user = useUserStore()
const router = useRouter()
const loading = ref(false)
const error = ref('')
const rows = ref([])
const keyword = ref('')
const networkFilter = ref('all')
const statusFilter = ref('all')
const sortKey = ref('recent')
const drawerVisible = ref(false)
const selectedIds = ref([])

const syncingId = ref(null)
const bulkSyncing = ref(false)
const bulkDeleting = ref(false)
const rebuildingAll = ref(false)
const cleaningMissing = ref(false)

const { job, jobId, jobBusy, retrying, cancelling, cancelCurrent, startPoll, stopPoll, beginStart, cancelStart, recoverLatest, retryCurrent } = useCarSyncJob()

const networks = computed(() => [...new Set(rows.value.map(r => r.salesNetwork).filter(Boolean))])
const coverCount = computed(() => rows.value.filter(r => r.coverImageUrl).length)
const emptyCount = computed(() => rows.value.filter(r => r.sourceState === 'EMPTY').length)
const syncedGoodsIds = computed(() => new Set(rows.value.map(r => r.goodsId)))

const hasFilter = computed(() =>
  !!keyword.value.trim() || networkFilter.value !== 'all' || statusFilter.value !== 'all')

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const list = rows.value.filter(r => {
    if (kw && !(r.name || '').toLowerCase().includes(kw) && !(r.salesNetwork || '').toLowerCase().includes(kw)) return false
    if (networkFilter.value !== 'all' && r.salesNetwork !== networkFilter.value) return false
    if (statusFilter.value === 'NONE') return !r.coverImageUrl
    if (statusFilter.value === 'EMPTY') return r.sourceState === 'EMPTY'
    // 官网无数据车 syncStatus 也是 FAILED,但统计口径已从「失败」排除,筛选必须同口径
    if (statusFilter.value === 'FAILED') return r.syncStatus === 'FAILED' && r.sourceState !== 'EMPTY'
    if (statusFilter.value !== 'all' && r.syncStatus !== statusFilter.value) return false
    return true
  })
  return sortCars(list, sortKey.value)
})

const countBy = (s) => rows.value.filter(r => r.syncStatus === s
  && !(s === 'FAILED' && r.sourceState === 'EMPTY')).length
const rowStatusMeta = (row) => {
  if (!row) return statusMeta('PENDING')
  if (row.sourceState === 'EMPTY') return { label: '官网无数据', type: 'info' }
  if (row.sourceState === 'INCOMPLETE' || row.sourceState === 'STALE') {
    return { label: '数据待复核', type: 'warning' }
  }
  if (row.syncStatus === 'PARTIAL') return { label: '部分同步', type: 'warning' }
  return statusMeta(row.syncStatus)
}
const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

const clearFilters = () => {
  keyword.value = ''
  networkFilter.value = 'all'
  statusFilter.value = 'all'
}

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await carApi.list()
    if (res.code === 0) rows.value = res.data || []
    else error.value = res.msg || '车型加载失败'
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}

const goDetail = (row) => { router.push(`/car/${row.id}`) }

const ensureIdle = () => {
  if (jobBusy.value) {
    ElMessage.warning('已有同步任务在运行，请等待完成后再发起')
    return false
  }
  return true
}

const onJobCreated = ({ jobId: id }) => {
  if (!id) {
    cancelStart()
    ElMessage.error('任务创建响应缺少任务号，请刷新后确认任务列表')
    return
  }
  job.value = null
  startPoll(id, { onFinished: load })
}

const onRetry = () => retryCurrent({ onFinished: load })
const onCancelJob = () => cancelCurrent()

const onCloseJob = () => {
  stopPoll()
  job.value = null
  jobId.value = null
}

const onSyncOne = async (row) => {
  if (row.sourceState === 'EMPTY') {
    ElMessage.warning('官网无数据，请使用「图片补入库」；全量同步不会补主图')
    return
  }
  if (!ensureIdle()) return
  if (syncingId.value != null) return
  if (!beginStart()) return   // 请求在途即置忙，堵住并发的「批量同步 / 补入库」
  syncingId.value = row.id
  try {
    const res = await carApi.createJob([row.goodsId])
    if (res.code === 0) onJobCreated({ jobId: res.data?.jobId })
    else {
      cancelStart()
      ElMessage.error(res.msg || '创建任务失败')
    }
  } catch (e) {
    cancelStart()
    ElMessage.error(e?.response?.data?.msg || '创建任务失败，请稍后重试')
  } finally {
    syncingId.value = null
  }
}

const toggleSelect = (row) => {
  selectedIds.value = selectedIds.value.includes(row.id)
    ? selectedIds.value.filter(id => id !== row.id)
    : [...selectedIds.value, row.id]
}

const selectedRows = computed(() => rows.value.filter(r => selectedIds.value.includes(r.id)))

const onBulkSync = async () => {
  if (!ensureIdle()) return
  if (bulkSyncing.value) return
  const blocked = selectedRows.value.filter(r => r.sourceState === 'EMPTY')
  if (blocked.length) {
    ElMessage.warning(`已选车型中有 ${blocked.length} 台官网无数据，请取消选择后使用「图片补入库」`)
    return
  }
  const goodsIds = selectedRows.value.map(r => r.goodsId).filter(Boolean)
  if (!goodsIds.length) { ElMessage.warning('请选择要同步的车型'); return }
  if (!beginStart()) return
  bulkSyncing.value = true
  try {
    const res = await carApi.createJob(goodsIds)
    if (res.code === 0) {
      selectedIds.value = []
      onJobCreated({ jobId: res.data?.jobId })
    } else {
      cancelStart()
      ElMessage.error(res.msg || '创建任务失败')
    }
  } catch (e) {
    cancelStart()
    ElMessage.error(e?.response?.data?.msg || '创建任务失败，请稍后重试')
  } finally {
    bulkSyncing.value = false
  }
}

const onBulkDelete = async () => {
  if (bulkDeleting.value) return
  if (jobBusy.value) { ElMessage.warning('同步任务运行中，请等待完成后再删除'); return }
  const list = selectedRows.value
  if (!list.length) return
  try {
    await ElMessageBox.confirm(
      `确认删除选中的 ${list.length} 个车型及其全部参数/向量？共享图库资产不会被删除。`,
      '批量删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }
  bulkDeleting.value = true
  try {
    const results = await Promise.allSettled(list.map(r => carApi.remove(r.id)))
    const ok = results.filter(r => r.status === 'fulfilled' && r.value?.code === 0).length
    const failed = list.length - ok
    failed > 0
      ? ElMessage.warning(`已删除 ${ok} 个，${failed} 个失败`)
      : ElMessage.success(`已删除 ${ok} 个车型`)
    selectedIds.value = []
    await load()
  } finally {
    bulkDeleting.value = false
  }
}

const onDel = async (row) => {
  if (jobBusy.value) { ElMessage.warning('同步任务运行中，请等待完成后再删除'); return }
  try {
    await ElMessageBox.confirm(
      `确认删除车型「${row.name}」及其全部参数/向量？共享图库资产不会被删除。`,
      '删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    const res = await carApi.remove(row.id)
    if (res.code === 0) {
      ElMessage.success('已删除')
      selectedIds.value = selectedIds.value.filter(id => id !== row.id)
    } else ElMessage.error(res.msg || '删除失败')
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '删除失败')
  }
  await load()
}

const onBackfill = (full = false) => {
  if (!ensureIdle()) return
  ElMessageBox.confirm(
    full
      ? '全量重扫会重新请求全部车型并下载官网图片；内容哈希去重，不重复占图床，但耗时会明显增加。确认执行？'
      : '只补缺失/失败/过期的车型图片：仅处理图片域（下载/哈希去重/图床/打标），不重跑参数清洗与向量化；已有图片凭内容哈希去重。确认执行？',
    full ? '全量图片重扫' : '图片补入库',
    { type: 'info', confirmButtonText: full ? '开始全量重扫' : '开始补入库', cancelButtonText: '取消' }
  ).then(async () => {
    if (!beginStart()) return
    try {
      const res = await carApi.createBackfillJob(full)
      if (res.code === 0) onJobCreated({ jobId: res.data })
      else {
        cancelStart()
        ElMessage.error(res.msg || '创建任务失败')
      }
    } catch (e) {
      cancelStart()
      if (e?.response?.status === 403) ElMessage.error('无权限：仅管理员/编辑可创建补入库任务')
      else ElMessage.error(e?.response?.data?.msg || '创建任务失败，请稍后重试')
    }
  }).catch(() => {})
}

const onRebuildAll = () => {
  if (rebuildingAll.value) return
  ElMessageBox.confirm(
    '对全部车型重建向量（新切块口径 + 补齐缺失块），耗时较长（每车型约十几秒），执行期间请勿重复触发。确认执行？',
    '重建全部向量', { type: 'warning', confirmButtonText: '开始重建', cancelButtonText: '取消' }
  ).then(async () => {
    rebuildingAll.value = true
    try {
      const res = await carApi.rebuildAll()
      const st = res.data || {}
      st.failed > 0
        ? ElMessage.warning(`重建完成：成功 ${st.success}/${st.total}，失败车型 ${st.failed}（id: ${st.failedModelIds}）`)
        : ElMessage.success(`重建完成：${st.success}/${st.total} 个车型已按新口径重建`)
    } catch (e) {
      ElMessage.error(e?.response?.data?.msg || '批量重建失败')
    } finally {
      rebuildingAll.value = false
    }
  }).catch(() => {})
}

const onCleanMissing = () => {
  if (cleaningMissing.value) return
  ElMessageBox.confirm(
    '只对「有原始参数、清洗结果为空」的车型，基于库内参数重跑清洗；不请求官网、不改版本/参数/图片。确认执行？',
    '补全清洗参数', { type: 'info', confirmButtonText: '开始补清洗', cancelButtonText: '取消' }
  ).then(async () => {
    cleaningMissing.value = true
    try {
      const res = await carApi.cleanMissing()
      const st = res.data || {}
      st.failed > 0
        ? ElMessage.warning(`补清洗完成：成功 ${st.cleaned}/${st.total}，失败 ${st.failed}`)
        : ElMessage.success(`补清洗完成：成功 ${st.cleaned}，跳过 ${st.skipped}`)
      await load()
    } catch (e) {
      ElMessage.error(e?.response?.data?.msg || '补清洗失败')
    } finally {
      cleaningMissing.value = false
    }
  }).catch(() => {})
}

const onOps = (cmd) => {
  if (cmd === 'backfill') onBackfill(false)
  else if (cmd === 'backfillFull') onBackfill(true)
  else if (cmd === 'cleanMissing') onCleanMissing()
  else if (cmd === 'rebuild') onRebuildAll()
}

onMounted(() => {
  load()
  recoverLatest({ onFinished: load })
})
onBeforeUnmount(stopPoll)
</script>

<style scoped>
.panel-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.search { width: 240px; }
.filter { width: 130px; }
.sort { width: 120px; }
.clear-btn { margin-left: -4px; }
.toolbar-actions { margin-left: auto; display: flex; gap: var(--sp-3); }
.btn-icon { margin-right: 4px; }
.tab-empty { padding: 40px 0; text-align: center; color: var(--faint); display: flex; flex-direction: column; align-items: center; gap: 4px; }

.stats-bar { display: flex; gap: 24px; padding: 14px 0; margin-bottom: 8px; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.stat { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.stat-num { font-family: var(--font-serif); font-size: 22px; font-weight: 700; line-height: 1; }
.stat-num.ok { color: var(--ok); }
.stat-num.warn { color: var(--warn); }
.stat-num.err { color: var(--err); }
.stat-sub { font-size: 13px; color: var(--muted); font-weight: 400; }
.stat-label { font-size: 12px; color: var(--muted); }

.result-line { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--muted); margin-bottom: 10px; }
.filtered-hint { color: var(--brand); }

.bulk-bar {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px; margin-bottom: 12px;
  border: 1px solid var(--brand-weak); border-radius: var(--radius);
  background: color-mix(in srgb, var(--brand-weak) 55%, var(--card));
}
.bulk-count { font-size: 13px; font-weight: 600; }

.car-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; }
.skeleton-grid { margin-top: 6px; }
.car-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  overflow: hidden;
  transition: box-shadow .2s, border-color .2s;
}
.car-card:hover { box-shadow: var(--shadow-hover); }
.car-card.selected { border-color: var(--brand); box-shadow: 0 0 0 2px var(--brand-weak); }
.thumb { position: relative; aspect-ratio: 4 / 3; background: var(--paper); cursor: pointer; padding: 10px; }
.thumb :deep(.el-image), .cover-img { width: 100%; height: 100%; }
.thumb :deep(.el-image__inner) { object-position: center; }
.thumb--fallback :deep(.el-image__inner) { object-position: top center; }
.thumb-ph { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; color: var(--faint); }
.pick { position: absolute; top: 6px; left: 8px; padding: 2px 4px; border-radius: var(--radius-sm); background: color-mix(in srgb, var(--card) 82%, transparent); }
.sk-thumb { width: 100%; height: 140px; }
.sk-body { padding: 10px 12px 12px; }

.card-body { padding: 10px 12px 12px; }
.c-name { font-weight: 700; font-size: 15px; line-height: 1.4; cursor: pointer; }
.c-name:hover { color: var(--brand); }
.c-meta { color: var(--muted); font-size: 12px; margin: 4px 0 8px; }
.c-status { display: flex; justify-content: space-between; align-items: center; gap: 6px; }
.c-time { font-size: 11px; color: var(--faint); }
.c-error {
  margin-top: 6px; padding: 4px 6px;
  font-size: 11px; color: var(--err); line-height: 1.5;
  background: color-mix(in srgb, var(--err) 8%, transparent);
  border-radius: var(--radius-sm);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden; word-break: break-all;
}
.c-actions { display: flex; justify-content: flex-end; gap: 2px; margin-top: 6px; flex-wrap: wrap; }
</style>
