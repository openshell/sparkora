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
        <!-- 工具栏:搜索 / 状态筛选 / 排序 / 批量删除(全走服务端参数) -->
        <div class="toolbar">
          <el-input
            v-model="filters.topic"
            class="toolbar-search"
            placeholder="搜索主题关键字"
            clearable
            :prefix-icon="Search"
            @keyup.enter="onFilterChange"
            @clear="onFilterChange"
          />
          <el-select v-model="filters.status" class="toolbar-status" @change="onFilterChange">
            <el-option label="全部状态" :value="''" />
            <el-option v-for="(meta, key) in PROJECT_STATUS" :key="key" :label="meta.label" :value="key" />
          </el-select>
          <el-select v-model="orderBy" class="toolbar-order" @change="onFilterChange">
            <el-option label="按更新时间" value="updatedAt" />
            <el-option label="按创建时间" value="createdAt" />
          </el-select>
          <el-button class="toolbar-dir" @click="toggleDir" :title="orderDir === 'desc' ? '降序' : '升序'">
            <el-icon><Sort /></el-icon>
            {{ orderDir === 'desc' ? '降' : '升' }}
          </el-button>
          <el-button
            v-if="user.isAdmin"
            class="toolbar-delete"
            type="danger" plain :disabled="!selection.length"
            @click="onBatchDelete"
          >
            <el-icon class="btn-icon"><Delete /></el-icon>批量删除{{ selection.length ? `(${selection.length})` : '' }}
          </el-button>
        </div>

        <!-- 桌面端表格 -->
        <el-table :data="rows" @selection-change="onSelectionChange">
          <el-table-column v-if="user.isAdmin" type="selection" width="42" />
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
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi } from '../api'
import { useUserStore } from '../store/user'
import TopBar from '../layouts/TopBar.vue'
import { PROJECT_STATUS, statusLabel, statusTagType } from '../constants/project'
import { Plus, WarningFilled, Search, Sort, Delete } from '@element-plus/icons-vue'

const user = useUserStore()
const loading = ref(false)
const error = ref('')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
// 筛选/排序状态:全部走服务端参数,变化时重置回第 1 页
const filters = reactive({ topic: '', status: '' })
const orderBy = ref('updatedAt')
const orderDir = ref('desc')
const selection = ref([])

// 后端 updatedAt 为 LocalDateTime 序列化,展示成「YYYY-MM-DD HH:mm」
const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await projectApi.list({
      page: page.value, size: size.value,
      topic: filters.topic || undefined,
      status: filters.status || undefined,
      orderBy: orderBy.value,
      orderDir: orderDir.value
    })
    rows.value = res.data.rows || []
    total.value = res.data.total || 0
  } catch (e) {
    // 失败与空态区分:显示错误 + 重试(401 已由拦截器跳登录)
    error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}
// 任一筛选/排序变化:重置回第 1 页再查,保证页码语义一致
const onFilterChange = () => { page.value = 1; load() }
const toggleDir = () => { orderDir.value = orderDir.value === 'desc' ? 'asc' : 'desc'; onFilterChange() }
const onSelectionChange = (sel) => { selection.value = sel }
// 批量删除:仅 ADMIN(多选列与按钮同门槛);确认弹层带数量,成功后刷新列表
const onBatchDelete = async () => {
  if (!selection.value.length) return
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selection.value.length} 个项目?删除后不可恢复。`,
      '批量删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return } // 用户取消
  try {
    const count = selection.value.length
    await projectApi.remove(selection.value.map(r => r.id).join(','))
    ElMessage.success(`已删除 ${count} 个项目`)
    selection.value = []
    // 删除后按剩余总数计算最大页,若当前页超界则回退,避免停在空页
    const remaining = Math.max(total.value - count, 0)
    const maxPage = Math.max(Math.ceil(remaining / size.value), 1)
    if (page.value > maxPage) page.value = maxPage
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || e.message || '删除失败,请稍后重试')
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

/* 工具栏:桌面单行排布,移动端换行;触控目标 ≥36px(按钮默认 32px,移动端加大) */
.toolbar { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; align-items: center; }
.toolbar-search { width: 220px; }
.toolbar-status { width: 140px; }
.toolbar-order { width: 130px; }
.toolbar-dir { min-width: 44px; }
.toolbar-delete { margin-left: auto; }
@media (max-width: 768px) {
  .toolbar-search { flex: 1 1 100%; }
  .toolbar-status, .toolbar-order { flex: 1 1 40%; width: auto; }
  .toolbar :deep(.el-button) { min-height: 44px; }
  .toolbar-delete { margin-left: 0; }
}
.card-title { font-weight: 700; font-size: 16px; line-height: 1.4; }
.card-sub { color: var(--muted); font-size: 12px; margin: 4px 0 10px; }
.card-meta { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.card-time { font-size: 11px; color: var(--faint); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 桌面表格:去默认竖线,更像杂志索引行 */
.responsive-table :deep(.el-table__cell) { border-right: none; }
.responsive-table :deep(.el-table__inner-wrapper::before) { display: none; }
</style>