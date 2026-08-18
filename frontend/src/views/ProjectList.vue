<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <h2>创作项目</h2>
        <el-button v-if="user.isEditorOrAbove" type="primary" @click="$router.push('/projects/new')">
          ＋ 新建创作任务
        </el-button>
      </div>

      <div v-if="loading" class="loading"><el-skeleton :rows="4" /></div>

      <div v-else-if="!rows.length" class="empty">
        <el-empty description="还没有创作任务，点击右上新建" />
      </div>

      <div v-else class="responsive-table">
        <!-- 桌面端表格 -->
        <el-table :data="rows" border>
          <el-table-column prop="topic" label="主题" min-width="180" />
          <el-table-column prop="keywords" label="关键词" min-width="120" />
          <el-table-column prop="createdBy" label="创建人" width="100" />
          <el-table-column prop="status" label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="160" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button size="small" @click="$router.push(`/projects/${row.id}`)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 移动端卡片列表 -->
        <div class="card-list">
          <el-card v-for="row in rows" :key="row.id" shadow="hover" @click="$router.push(`/projects/${row.id}`)">
            <div class="card-title">{{ row.topic }}</div>
            <div class="card-sub">{{ row.keywords || '无关键词' }}</div>
            <div class="card-meta">
              <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
              <span class="card-time">{{ row.updatedAt }}</span>
            </div>
          </el-card>
        </div>

        <el-pagination
          class="pager"
          :current-page="page" :page-size="size" :total="total"
          layout="prev, pager, next, total" @current-change="onPage" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { projectApi } from '../api'
import { useUserStore } from '../store/user'
import TopBar from '../layouts/TopBar.vue'

const user = useUserStore()
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

const statusLabel = (s) => ({ DRAFT: '草稿', GENERATING_BRIEF: '生成中', READY: '就绪' }[s] || s)
const statusType = (s) => ({ DRAFT: 'info', GENERATING_BRIEF: 'warning', READY: 'success' }[s] || '')

const load = async () => {
  loading.value = true
  try {
    const res = await projectApi.list({ page: page.value, size: size.value })
    rows.value = res.data.rows || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}
const onPage = (p) => { page.value = p; load() }
onMounted(load)
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; }
.loading, .empty { padding: 40px 0; }
.card-list .el-card { cursor: pointer; }
.card-title { font-weight: 600; font-size: 15px; }
.card-sub { color: var(--muted); font-size: 12px; margin: 4px 0 8px; }
.card-meta { display: flex; justify-content: space-between; align-items: center; }
.card-time { font-size: 11px; color: var(--muted); }
.pager { margin-top: 16px; justify-content: center; }
@media (max-width: 768px) { .pager { flex-wrap: wrap; } }
</style>
