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
        <el-option label="数据待复核" value="REVIEW" />
        <el-option label="部分同步" value="PARTIAL" />
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
        <!-- 质量（三角色只读）：全库向量对账，查看缺失块 TopN -->
        <el-button @click="openQuality">
          <el-icon class="btn-icon"><DataAnalysis /></el-icon>质量
        </el-button>
        <el-button @click="historyVisible = true">
          <el-icon class="btn-icon"><Clock /></el-icon>同步记录
        </el-button>
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
              <el-dropdown-item v-if="user.isAdmin" command="cleanBackfill" divided>补全清洗参数</el-dropdown-item>
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
      <!-- 统计项即筛选入口：筛选/计数/卡片标签同源(rowStatusKey) -->
      <div class="stats-bar">
        <button v-for="item in statItems" :key="item.key" type="button" class="stat"
          :class="{ active: statActive(item) }" :aria-pressed="statActive(item)"
          :title="`筛选：${item.label}`" @click="onStatClick(item)">
          <span class="stat-num" :class="item.cls">{{ item.num }}<span v-if="item.sub" class="stat-sub">{{ item.sub }}</span></span>
          <span class="stat-label">{{ item.label }}</span>
        </button>
      </div>

      <CarJobPanel v-if="job" :job="job" :retrying="retrying" :can-retry="user.isEditorOrAbove"
        :can-cancel="user.isEditorOrAbove" :cancelling="cancelling" closable
        @retry="onRetry" @cancel="onCancelJob" @close="onCloseJob" />

      <div v-if="rows.length" class="result-line">
        <div class="result-left">
          <el-checkbox v-if="user.isEditorOrAbove && filtered.length" class="select-all"
            :model-value="allFilteredSelected" :indeterminate="selectAllIndeterminate"
            @change="onSelectAllFiltered">全选筛选结果</el-checkbox>
          <span>共 {{ rows.length }} 台 · 当前显示 {{ filtered.length }} 台</span>
          <span v-if="hasFilter" class="filtered-hint">已按筛选条件过滤</span>
        </div>
      </div>

      <div v-if="user.isEditorOrAbove && selectedIds.length" class="bulk-bar">
        <span class="bulk-count">已选 {{ selectedIds.length }} 个</span>
        <el-button size="small" text type="primary" :loading="bulkSyncing" :disabled="jobBusy" @click="onBulkSync">
          批量同步
        </el-button>
        <!-- 10-24 R3 定向补图：只对选中车型创建图片补入库任务（含 EMPTY 车型走存量 URL 兜底） -->
        <el-button size="small" text type="primary" :loading="bulkBackfilling" :disabled="jobBusy" @click="onBulkBackfill">
          补选中图片
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
                <template v-if="formatDate(row.sourceCreatedAt)"> · 创建 {{ formatDate(row.sourceCreatedAt) }}</template>
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
                  :title="row.sourceState === 'EMPTY' ? '官网无数据，全量同步不可用；可勾选后使用批量栏「补选中图片」补图' : ''"
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
    <CarJobHistoryDrawer v-model="historyVisible" />

    <!-- 质量：全库向量对账（三角色只读，数据来自 GET /car/models/vector-stats） -->
    <el-dialog v-model="qualityVisible" title="向量对账（质量）" width="560px">
      <div v-if="qualityLoading" class="quality-skeleton">
        <el-skeleton :rows="4" animated />
      </div>
      <div v-else-if="qualityError" class="state-error">
        <el-icon :size="32" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">质量数据加载失败</div>
        <div class="state-msg">{{ qualityError }}</div>
        <el-button type="primary" plain @click="loadQuality">重试</el-button>
      </div>
      <template v-else>
        <div class="quality-stats">
          <div class="q-stat"><span class="q-num">{{ quality.modelCount || 0 }}</span><span class="q-label">车型数</span></div>
          <div class="q-stat"><span class="q-num">{{ quality.chunkCount || 0 }}</span><span class="q-label">块总数</span></div>
          <div class="q-stat"><span class="q-num">{{ quality.embeddedCount || 0 }}</span><span class="q-label">已嵌入块</span></div>
          <div class="q-stat">
            <span class="q-num" :class="{ err: (quality.missingCount || 0) > 0 }">{{ quality.missingCount || 0 }}</span>
            <span class="q-label">缺失块</span>
          </div>
        </div>
        <div class="q-missing-title">缺失向量 Top {{ qualityTop.length }}</div>
        <el-table v-if="qualityTop.length" :data="qualityTop" size="small" class="q-table"
          @row-click="goQualityModel">
          <el-table-column prop="modelName" label="车型" min-width="160" />
          <el-table-column prop="missing" label="缺失块数" width="100" align="right" />
        </el-table>
        <el-empty v-else :image-size="60" description="全部块均已有向量，无缺失" />
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { carApi } from '../../api'
import { useUserStore } from '../../store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, WarningFilled, Van, Setting, Clock, DataAnalysis } from '@element-plus/icons-vue'
import CarJobPanel from '../../components/car/CarJobPanel.vue'
import CarCatalogDrawer from '../../components/car/CarCatalogDrawer.vue'
import CarJobHistoryDrawer from '../../components/car/CarJobHistoryDrawer.vue'
import { useCarSyncJob } from '../../composables/useCarSyncJob'
import { coverIsFallback, coverUrlOf, formatDate, formatPrice, rowStatusKey, rowStatusMeta, sortCars } from '../../utils/carDisplay'

const user = useUserStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const error = ref('')
const rows = ref([])

// 筛选状态随 URL 持久化（10-25 R2）：kw/net/status/sort，非法值白名单兜底
const STATUS_VALUES = ['SUCCESS', 'FAILED', 'SYNCING', 'PENDING', 'REVIEW', 'PARTIAL', 'EMPTY', 'NONE']
const SORT_VALUES = ['recent', 'name', 'price']
const queryString = (v) => (typeof v === 'string' ? v : '')
const statusOf = (v) => (v === 'all' || STATUS_VALUES.includes(v) ? v : 'all')
const sortOf = (v) => (SORT_VALUES.includes(v) ? v : 'recent')

const keyword = ref(queryString(route.query.kw))
const networkFilter = ref(queryString(route.query.net) || 'all')
const statusFilter = ref(statusOf(route.query.status))
const sortKey = ref(sortOf(route.query.sort))
const drawerVisible = ref(false)
const historyVisible = ref(false)
const selectedIds = ref([])

const syncingId = ref(null)
const bulkSyncing = ref(false)
const bulkBackfilling = ref(false)
const bulkDeleting = ref(false)

const { job, jobId, jobRunning, jobBusy, retrying, cancelling, cancelCurrent, startPoll, stopPoll, beginStart, cancelStart, recoverLatest, retryCurrent } = useCarSyncJob()

const networks = computed(() => [...new Set(rows.value.map(r => r.salesNetwork).filter(Boolean))])
const coverCount = computed(() => rows.value.filter(r => r.coverImageUrl).length)
const syncedGoodsIds = computed(() => new Set(rows.value.map(r => r.goodsId)))

const hasFilter = computed(() =>
  !!keyword.value.trim() || networkFilter.value !== 'all' || statusFilter.value !== 'all')

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const status = statusFilter.value
  const list = rows.value.filter(r => {
    if (kw && !(r.name || '').toLowerCase().includes(kw) && !(r.salesNetwork || '').toLowerCase().includes(kw)) return false
    if (networkFilter.value !== 'all' && r.salesNetwork !== networkFilter.value) return false
    if (status === 'NONE') return !r.coverImageUrl
    // 状态键单一真源(EMPTY > REVIEW > PARTIAL > syncStatus),与统计/标签同口径
    if (status !== 'all' && rowStatusKey(r) !== status) return false
    return true
  })
  return sortCars(list, sortKey.value)
})

const countByKey = (key) => rows.value.filter(r => rowStatusKey(r) === key).length
const statItems = computed(() => [
  { key: 'all', label: '全部车型', num: rows.value.length, cls: '' },
  { key: 'SUCCESS', label: '已同步', num: countByKey('SUCCESS'), cls: 'ok' },
  { key: 'FAILED', label: '失败', num: countByKey('FAILED'), cls: 'err' },
  { key: 'REVIEW', label: '数据待复核', num: countByKey('REVIEW'), cls: 'warn' },
  { key: 'PARTIAL', label: '部分同步', num: countByKey('PARTIAL'), cls: 'warn' },
  { key: 'EMPTY', label: '官网无数据', num: countByKey('EMPTY'), cls: 'warn' },
  { key: 'SYNCING', label: '同步中', num: countByKey('SYNCING'), cls: 'warn' },
  { key: 'cover', label: '封面覆盖', num: coverCount.value, sub: ` / ${rows.value.length}`, cls: '' }
])
const statActive = (item) => {
  if (item.key === 'all') return statusFilter.value === 'all'
  if (item.key === 'cover') return statusFilter.value === 'NONE'
  return statusFilter.value === item.key
}
const onStatClick = (item) => {
  if (item.key === 'all') statusFilter.value = 'all'
  else if (item.key === 'cover') statusFilter.value = 'NONE'
  else statusFilter.value = item.key
}
const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

// ---- URL 双向同步（R2，对齐 frontend spec「路由 query 双向同步」约定）----
const pickFilters = (q) => ({
  kw: queryString(q.kw),
  net: queryString(q.net),
  status: q.status ? statusOf(q.status) : 'all',
  sort: q.sort ? sortOf(q.sort) : 'recent'
})
const currentFilters = () => ({
  kw: keyword.value,
  net: networkFilter.value === 'all' ? '' : networkFilter.value,
  status: statusFilter.value,
  sort: sortKey.value
})
const sameFilters = (a, b) =>
  a.kw === b.kw && a.net === b.net && a.status === b.status && a.sort === b.sort

let kwTimer = null
const writeQuery = () => {
  if (sameFilters(pickFilters(route.query), currentFilters())) return   // 自身回流短路
  const next = currentFilters()
  const query = { ...route.query }
  const put = (k, v) => { if (v && v !== 'all' && !(k === 'sort' && v === 'recent')) query[k] = v; else delete query[k] }
  put('kw', next.kw)
  put('net', next.net)
  put('status', next.status)
  put('sort', next.sort)
  router.replace({ query })
}

const clearFilters = () => {
  clearTimeout(kwTimer)
  keyword.value = ''
  networkFilter.value = 'all'
  statusFilter.value = 'all'
  sortKey.value = 'recent'
  const query = { ...route.query }
  ;['kw', 'net', 'status', 'sort'].forEach(k => delete query[k])
  router.replace({ query })
}

// 关键词防抖 300ms（避免每键 replace 刷历史），其余筛选立即写回
watch(keyword, () => {
  clearTimeout(kwTimer)
  kwTimer = setTimeout(writeQuery, 300)
})
watch([networkFilter, statusFilter, sortKey], writeQuery)
// 浏览器前进/后退或外部链接：恢复筛选态（一致则短路，防写回死循环）
watch(() => route.query, () => {
  const ext = pickFilters(route.query)
  if (sameFilters(ext, currentFilters())) return
  clearTimeout(kwTimer)
  keyword.value = ext.kw
  networkFilter.value = ext.net || 'all'
  statusFilter.value = ext.status
  sortKey.value = ext.sort
})

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

// ---- 质量：全库向量对账（三角色只读，10-25 P2 R1）----
const qualityVisible = ref(false)
const qualityLoading = ref(false)
const qualityError = ref('')
const quality = ref({})
const qualityTop = computed(() => (Array.isArray(quality.value.missingTopN) ? quality.value.missingTopN : []))

const loadQuality = async () => {
  qualityLoading.value = true
  qualityError.value = ''
  try {
    const res = await carApi.vectorStats()
    if (res.code === 0) quality.value = res.data || {}
    else qualityError.value = res.msg || '质量数据加载失败'
  } catch (e) {
    qualityError.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试'
  } finally {
    qualityLoading.value = false
  }
}

const openQuality = () => {
  qualityVisible.value = true
  loadQuality()
}

// 行点击跳详情并关闭弹窗（missingTopN 条目含 modelId/modelName/missing）
const goQualityModel = (row) => {
  if (!row?.modelId) return
  qualityVisible.value = false
  router.push(`/car/${row.modelId}`)
}

// ---- 运行期静默刷新（10-25 P2 R2）：任务 RUNNING 时每 10s 刷新列表 ----
// 与「刷新」按钮/终态 load 区分：不置 loading(无骨架闪烁)、不动 error、失败静默下次再试。
let refreshTimer = null
let refreshing = false
const silentRefresh = async () => {
  if (refreshing) return
  refreshing = true
  try {
    const res = await carApi.list()
    if (res.code === 0 && Array.isArray(res.data)) rows.value = res.data
  } catch {
    // 静默：网络抖动不清空已有数据，下一拍再试
  } finally {
    refreshing = false
  }
}

watch(jobRunning, (running) => {
  if (running) {
    if (!refreshTimer) refreshTimer = setInterval(silentRefresh, 10000)
  } else {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}, { immediate: true })

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
    ElMessage.warning('官网无数据，全量同步不可用；可勾选后使用批量栏「补选中图片」补图')
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

// 「全选筛选结果」（R3）：勾选 = 选中当前筛选结果全部（保留已选中的非筛选行）；取消 = 清空全部
const allFilteredSelected = computed(() =>
  filtered.value.length > 0 && filtered.value.every(r => selectedIds.value.includes(r.id)))
const selectAllIndeterminate = computed(() =>
  !allFilteredSelected.value && filtered.value.some(r => selectedIds.value.includes(r.id)))
const onSelectAllFiltered = (checked) => {
  if (!checked) { selectedIds.value = []; return }
  selectedIds.value = [...new Set([...selectedIds.value, ...filtered.value.map(r => r.id)])]
}

const onBulkSync = async () => {
  if (!ensureIdle()) return
  if (bulkSyncing.value) return
  const blocked = selectedRows.value.filter(r => r.sourceState === 'EMPTY')
  if (blocked.length) {
    ElMessage.warning(`已选车型中有 ${blocked.length} 台官网无数据，已跳过全量同步；如需补图请勾选后使用「补选中图片」`)
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

// 定向图片补入库（10-24 R3）：只对选中车型创建任务，忽略缺失过滤（显式选择即目标，
// 含 EMPTY 车型走存量 URL 兜底）；仅处理图片域，已有图片凭内容哈希去重
const onBulkBackfill = () => {
  if (!ensureIdle()) return
  if (bulkBackfilling.value) return
  const goodsIds = selectedRows.value.map(r => r.goodsId).filter(Boolean)
  if (!goodsIds.length) { ElMessage.warning('选中的车型均缺少官网 goodsId，无法补图'); return }
  ElMessageBox.confirm(
    `只对选中的 ${goodsIds.length} 个车型执行图片补入库：仅处理图片域（封面+详情图集，下载/哈希去重/图床/打标），已有图片凭内容哈希去重，不重跑参数清洗与向量化。确认执行？`,
    '补选中图片', { type: 'info', confirmButtonText: '开始补图', cancelButtonText: '取消' }
  ).then(async () => {
    if (!beginStart()) return
    bulkBackfilling.value = true
    try {
      // backfill 返回裸 jobId（区别于 createJob 的 {jobId} 包装）
      const res = await carApi.createBackfillJob(false, goodsIds)
      if (res.code === 0) {
        onJobCreated({ jobId: res.data })
      } else {
        cancelStart()
        ElMessage.error(res.msg || '创建任务失败')
      }
    } catch (e) {
      cancelStart()
      if (e?.response?.status === 403) ElMessage.error('无权限：仅管理员/编辑可创建补入库任务')
      else ElMessage.error(e?.response?.data?.msg || '创建任务失败，请稍后重试')
    } finally {
      bulkBackfilling.value = false
    }
  }).catch(() => {})
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

// 「重建全部向量」迁移异步任务（10-24 R1）：全库逐车型 VECTOR_REBUILD，
// 进度在任务面板实时查看；失败落任务明细可「重试失败项」，不再阻塞 HTTP
const onRebuildJob = () => {
  if (!ensureIdle()) return
  ElMessageBox.confirm(
    '创建全库重建任务（新切块口径 + 补齐缺失块），进度在任务面板实时查看；耗时较长（每车型约十几秒），执行期间请勿重复触发。确认执行？',
    '重建全部向量', { type: 'warning', confirmButtonText: '创建重建任务', cancelButtonText: '取消' }
  ).then(async () => {
    if (!beginStart()) return
    try {
      const res = await carApi.createRebuildJob()
      if (res.code === 0) onJobCreated({ jobId: res.data?.jobId })
      else {
        cancelStart()
        ElMessage.error(res.msg || '创建任务失败')
      }
    } catch (e) {
      cancelStart()
      if (e?.response?.status === 403) ElMessage.error('无权限：仅管理员可重建向量')
      else ElMessage.error(e?.response?.data?.msg || '创建任务失败，请稍后重试')
    }
  }).catch(() => {})
}

// 批量补清洗：创建异步 CLEAN_BACKFILL 任务（只处理有参数无清洗车型，allowAi=false）
const onCleanBackfill = () => {
  if (!ensureIdle()) return
  ElMessageBox.confirm(
    '对「有原始参数、清洗结果为空」的车型，基于库内参数异步重跑清洗；不请求官网、不改版本/参数/图片。确认执行？',
    '补全清洗参数', { type: 'info', confirmButtonText: '开始补清洗', cancelButtonText: '取消' }
  ).then(async () => {
    if (!beginStart()) return
    try {
      const res = await carApi.startCleanJob(false)
      if (res.code === 0 && res.data?.jobId) {
        startPoll(res.data.jobId, { onFinished: load })
      } else {
        cancelStart()
        ElMessage.error(res.msg || '创建补清洗任务失败')
      }
    } catch (e) {
      cancelStart()
      ElMessage.error(e?.response?.data?.msg || '创建补清洗任务失败')
    }
  }).catch(() => {})
}

const onOps = (cmd) => {
  if (cmd === 'backfill') onBackfill(false)
  else if (cmd === 'backfillFull') onBackfill(true)
  else if (cmd === 'cleanBackfill') onCleanBackfill()
  else if (cmd === 'rebuild') onRebuildJob()
}

onMounted(() => {
  load()
  recoverLatest({ onFinished: load })
})
onBeforeUnmount(() => {
  clearTimeout(kwTimer)
  clearInterval(refreshTimer)
  refreshTimer = null
  stopPoll()
})
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

.stats-bar { display: flex; gap: 8px; padding: 14px 0; margin-bottom: 8px; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.stat {
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  padding: 4px 10px; border: 0; border-radius: var(--radius-sm);
  background: none; color: inherit; font: inherit; cursor: pointer;
  transition: background .15s;
}
.stat:hover { background: color-mix(in srgb, var(--brand-weak) 45%, transparent); }
.stat.active { background: var(--brand-weak); }
.stat:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.stat-num { font-family: var(--font-serif); font-size: 22px; font-weight: 700; line-height: 1; }
.stat-num.ok { color: var(--ok); }
.stat-num.warn { color: var(--warn); }
.stat-num.err { color: var(--err); }
.stat-sub { font-size: 13px; color: var(--muted); font-weight: 400; }
.stat-label { font-size: 12px; color: var(--muted); }

.result-line { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--muted); margin-bottom: 10px; }
.result-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.filtered-hint { color: var(--brand); }

.bulk-bar {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px; margin-bottom: 12px;
  border: 1px solid var(--brand-weak); border-radius: var(--radius);
  background: color-mix(in srgb, var(--brand-weak) 55%, var(--card));
  /* R4 吸附：Tab pane 即滚动容器，top:0 直达内容区顶部；不透明背景避免透视 */
  position: sticky; top: 0; z-index: 10;
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

/* 质量弹窗（向量对账） */
.quality-skeleton { padding: var(--sp-2) 0; }
.quality-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 14px; }
.q-stat {
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  padding: 10px 6px; border: 1px solid var(--line); border-radius: var(--radius); background: var(--paper);
}
.q-num { font-family: var(--font-serif); font-size: 22px; font-weight: 700; line-height: 1; }
.q-num.err { color: var(--err); }
.q-label { font-size: 12px; color: var(--muted); }
.q-missing-title { font-size: 13px; color: var(--muted); margin-bottom: 6px; }
.q-table :deep(.el-table__row) { cursor: pointer; }
</style>
