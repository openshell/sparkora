<template>
  <div class="car-panel">
    <!-- 工具栏：搜索 + 网络筛选 + 状态筛选 + 同步入口 -->
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
        <el-option label="待同步" value="PENDING" />
        <el-option label="同步中" value="SYNCING" />
        <el-option label="失败" value="FAILED" />
      </el-select>
      <el-button v-if="user.isEditorOrAbove" class="sync-btn" @click="$router.push('/car/sync')">
        <el-icon class="btn-icon"><Refresh /></el-icon>同步车型
      </el-button>
    </div>

    <div v-if="loading" class="loading"><el-skeleton :rows="4" animated /></div>

    <div v-else-if="error" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">车型加载失败</div>
      <div class="state-msg">{{ error }}</div>
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>

    <div v-else-if="!rows.length" class="empty">
      <el-empty description="车型库为空。点击右上「同步车型」，从比亚迪官网拉取车型数据入库。" />
    </div>

    <template v-else>
      <div v-if="!filtered.length" class="tab-empty">没有符合条件的车型</div>
      <div v-else class="car-grid">
        <div v-for="row in filtered" :key="row.id" class="car-card" @click="$router.push(`/car/${row.id}`)">
          <div class="thumb">
            <el-image v-if="thumbUrl(row)" :src="thumbUrl(row)" fit="cover" lazy />
            <div v-else class="thumb-ph"><el-icon :size="22"><Van /></el-icon></div>
          </div>
          <div class="card-body">
            <div class="c-name serif">{{ row.name }}</div>
            <div class="c-meta">{{ row.salesNetwork || '—' }} · {{ row.priceRange || '价格待同步' }}</div>
            <div class="c-status">
              <el-tag size="small" :type="statusTagType(row.syncStatus)" effect="light" round>{{ statusLabel(row.syncStatus) }}</el-tag>
              <span class="c-time">{{ fmtTime(row.lastSyncAt) }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { carApi } from '../../api'
import { useUserStore } from '../../store/user'
import { Search, Refresh, WarningFilled, Van } from '@element-plus/icons-vue'

const user = useUserStore()
const loading = ref(false)
const error = ref('')
const rows = ref([])
const keyword = ref('')
const networkFilter = ref('all')
const statusFilter = ref('all')

// 销售网络去重（筛选项数据源）
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

// 后端 introImageUrls 已由图库 id 解析为公网 URL（见父任务契约 3.3），取首图作缩略图
const thumbUrl = (row) => {
  const urls = row.introImageUrls
  return Array.isArray(urls) && urls.length ? urls[0] : ''
}

const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')
const statusLabel = (s) => ({ PENDING: '待同步', SYNCING: '同步中', SUCCESS: '已同步', FAILED: '失败' }[s] || s || '—')
const statusTagType = (s) => ({ SUCCESS: 'success', FAILED: 'danger', SYNCING: 'warning', PENDING: 'info' }[s] || 'info')

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await carApi.list()
    rows.value = res.data || []
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.panel-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.search { width: 240px; }
.filter { width: 130px; }
.sync-btn { margin-left: auto; }
.btn-icon { margin-right: 4px; }
.tab-empty { padding: 40px 0; text-align: center; color: var(--faint); }

.car-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; }
.car-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow .2s;
}
.car-card:hover { box-shadow: var(--shadow-hover); }
.thumb { position: relative; aspect-ratio: 4 / 3; background: var(--paper); }
.thumb :deep(.el-image) { width: 100%; height: 100%; }
.thumb-ph { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; color: var(--faint); }
.card-body { padding: 10px 12px 12px; }
.c-name { font-weight: 700; font-size: 15px; line-height: 1.4; }
.c-meta { color: var(--muted); font-size: 12px; margin: 4px 0 8px; }
.c-status { display: flex; justify-content: space-between; align-items: center; gap: 6px; }
.c-time { font-size: 11px; color: var(--faint); }

@media (max-width: 768px) {
  .search { width: 100%; }
  .filter { width: 100%; }
  .sync-btn { width: 100%; margin-left: 0; }
  .car-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
