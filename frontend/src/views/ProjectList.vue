<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">Workbench</span>
          <h2>创作项目</h2>
        </div>
        <div class="actions">
          <el-button v-if="user.isEditorOrAbove" type="primary" @click="$router.push('/projects/new')">
            <el-icon class="btn-icon"><Plus /></el-icon>新建创作任务
          </el-button>
        </div>
      </div>

      <div v-if="loading" class="loading">
        <el-skeleton :rows="4" animated />
      </div>

      <!-- 加载失败:与空态区分,可重试 -->
      <div v-else-if="error" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">项目列表加载失败</div>
        <div class="state-msg">{{ error }}</div>
        <el-button type="primary" plain @click="load">重试</el-button>
      </div>

      <div v-else-if="!rows.length" class="empty">
        <el-empty description="还没有创作任务，点击右上角「新建创作任务」开始" />
      </div>

      <div v-else class="responsive-table">
        <!-- 桌面端表格 -->
        <el-table :data="rows">
          <el-table-column prop="topic" label="主题" min-width="220">
            <template #default="{ row }">
              <a class="topic-link" @click="$router.push(`/projects/${row.id}`)">{{ row.topic }}</a>
            </template>
          </el-table-column>
          <el-table-column prop="keywords" label="关键词" min-width="140">
            <template #default="{ row }">
              <span class="cell-muted">{{ row.keywords || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createdBy" label="创建人" width="100" />
          <el-table-column prop="status" label="状态" width="130">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" effect="light" round>{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="170">
            <template #default="{ row }">
              <span class="cell-muted">{{ fmtTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="$router.push(`/projects/${row.id}`)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 移动端卡片列表 -->
        <div class="card-list">
          <el-card v-for="row in rows" :key="row.id" shadow="hover" class="proj-card"
                   @click="$router.push(`/projects/${row.id}`)">
            <div class="card-title serif">{{ row.topic }}</div>
            <div class="card-sub">{{ row.keywords || '无关键词' }}</div>
            <div class="card-meta">
              <el-tag size="small" :type="statusTagType(row.status)" effect="light" round>
                {{ statusLabel(row.status) }}
              </el-tag>
              <span class="card-time">{{ row.createdBy }} · {{ fmtTime(row.updatedAt) }}</span>
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
import { statusLabel, statusTagType } from '../constants/project'
import { Plus, WarningFilled } from '@element-plus/icons-vue'

const user = useUserStore()
const loading = ref(false)
const error = ref('')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

// 后端 updatedAt 为 LocalDateTime 序列化,展示成「YYYY-MM-DD HH:mm」
const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await projectApi.list({ page: page.value, size: size.value })
    rows.value = res.data.rows || []
    total.value = res.data.total || 0
  } catch (e) {
    // 失败与空态区分:显示错误 + 重试(401 已由拦截器跳登录)
    error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}
const onPage = (p) => { page.value = p; load() }
onMounted(load)
</script>

<style scoped>
.topic-link { cursor: pointer; font-weight: 600; color: var(--ink); }
.topic-link:hover { color: var(--brand); }
.cell-muted { color: var(--muted); font-size: 13px; }
.btn-icon { margin-right: 2px; }
.proj-card { cursor: pointer; }
.card-title { font-weight: 700; font-size: 16px; line-height: 1.4; }
.card-sub { color: var(--muted); font-size: 12px; margin: 4px 0 10px; }
.card-meta { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.card-time { font-size: 11px; color: var(--faint); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 桌面表格:去默认竖线,更像杂志索引行 */
.responsive-table :deep(.el-table__cell) { border-right: none; }
.responsive-table :deep(.el-table__inner-wrapper::before) { display: none; }
</style>