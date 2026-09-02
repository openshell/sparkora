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
        </div>
      </div>

      <div v-if="loading" class="loading"><el-skeleton :rows="4" animated /></div>

      <div v-else-if="error" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">车型知识库加载失败</div>
        <div class="state-msg">{{ error }}</div>
        <el-button type="primary" plain @click="load">重试</el-button>
      </div>

      <div v-else-if="!rows.length" class="empty">
        <el-empty description="车型知识库为空。点击右上「同步车型」，从比亚迪官网拉取车型数据入库。" />
      </div>

      <div v-else class="responsive-table">
        <el-table :data="rows">
          <el-table-column prop="name" label="车型" min-width="140">
            <template #default="{ row }">
              <a class="topic-link" @click="$router.push(`/car/${row.id}`)">{{ row.name }}</a>
            </template>
          </el-table-column>
          <el-table-column prop="salesNetwork" label="网络" width="90">
            <template #default="{ row }"><span class="cell-muted">{{ row.salesNetwork || '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="priceRange" label="价格区间" min-width="150">
            <template #default="{ row }"><span class="cell-muted">{{ row.priceRange || '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="syncStatus" label="同步状态" width="110">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.syncStatus)" effect="light" round>{{ statusLabel(row.syncStatus) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近同步" width="170">
            <template #default="{ row }"><span class="cell-muted">{{ fmtTime(row.lastSyncAt) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="$router.push(`/car/${row.id}`)">详情</el-button>
              <el-button v-if="user.isEditorOrAbove" size="small" text :loading="syncingId === row.id" @click="onSyncOne(row)">同步</el-button>
              <el-button v-if="user.isEditorOrAbove" size="small" text type="danger" @click="onDel(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="card-list">
          <el-card v-for="row in rows" :key="row.id" shadow="hover" class="car-card"
                   @click="$router.push(`/car/${row.id}`)">
            <div class="card-title serif">{{ row.name }}</div>
            <div class="card-sub">{{ row.salesNetwork || '' }} · {{ row.priceRange || '价格待同步' }}</div>
            <div class="card-meta">
              <el-tag size="small" :type="statusTagType(row.syncStatus)" effect="light" round>{{ statusLabel(row.syncStatus) }}</el-tag>
              <span class="card-time">{{ fmtTime(row.lastSyncAt) }}</span>
            </div>
          </el-card>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { carApi } from '../api'
import { useUserStore } from '../store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import TopBar from '../layouts/TopBar.vue'
import { Refresh, WarningFilled } from '@element-plus/icons-vue'

const user = useUserStore()
const loading = ref(false)
const error = ref('')
const rows = ref([])
const syncingId = ref(null)

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

const onSyncOne = async (row) => {
  syncingId.value = row.id
  try {
    const res = await carApi.syncOne(row.id)
    if (res.code === 0) { ElMessage.success(`「${res.data.name}」同步完成`); await load() }
    else ElMessage.error(res.msg || '同步失败')
  } catch (e) { ElMessage.error('同步失败：' + (e.message || e)) }
  finally { syncingId.value = null }
}

const onDel = async (row) => {
  await ElMessageBox.confirm(`确认删除车型「${row.name}」及其全部参数/向量？`, '删除', { type: 'warning' })
  await carApi.remove(row.id)
  ElMessage.success('已删除'); await load()
}

onMounted(load)
</script>

<style scoped>
.btn-icon { margin-right: 2px; }
.topic-link { cursor: pointer; font-weight: 600; color: var(--ink); }
.topic-link:hover { color: var(--brand); }
.cell-muted { color: var(--muted); font-size: 13px; }
.car-card { cursor: pointer; }
.card-title { font-weight: 700; font-size: 16px; line-height: 1.4; }
.card-sub { color: var(--muted); font-size: 12px; margin: 4px 0 10px; }
.card-meta { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.card-time { font-size: 11px; color: var(--faint); }
.responsive-table :deep(.el-table__cell) { border-right: none; }
.responsive-table :deep(.el-table__inner-wrapper::before) { display: none; }
</style>
