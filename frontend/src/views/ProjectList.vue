<template>
  <div class="page">
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

    <template v-else>
      <!-- 工具带:搜索 / 状态筛选 / 排序(全走服务端参数),右侧批量删除 -->
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

      <el-table class="proj-table" :data="rows" @selection-change="onSelectionChange">
        <el-table-column v-if="user.isAdmin" type="selection" width="42" />
        <el-table-column prop="topic" label="主题" min-width="320">
          <template #default="{ row }">
            <a class="topic-link" @click="$router.push(`/projects/${row.id}`)">{{ row.topic }}</a>
          </template>
        </el-table-column>
        <el-table-column prop="keywords" label="关键词" min-width="160">
          <template #default="{ row }">
            <span class="cell-muted">{{ row.keywords || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdBy" label="创建人" width="120" />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light" round>{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">
            <span class="cell-muted">{{ fmtTime(row.updatedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="88" fixed="right">
          <template #default="{ row }">
            <span class="row-ops">
              <el-button size="small" text type="primary" @click="$router.push(`/projects/${row.id}`)">详情</el-button>
            </span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        :current-page="page" :page-size="size" :total="total"
        layout="prev, pager, next, total" @current-change="onPage" />
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { projectApi } from '../api'
import { useUserStore } from '../store/user'
import { usePageHeader } from '../composables/usePageHeader'
import { PROJECT_STATUS, statusLabel, statusTagType } from '../constants/project'
import { Plus, WarningFilled, Search, Sort, Delete } from '@element-plus/icons-vue'

const user = useUserStore()
const router = useRouter()
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

// ==== 上下文条(外壳 topbar):面包屑 + 右上「新建创作任务」====
// 角色门槛与原 v-if="user.isEditorOrAbove" 保持一致;离开本页时清空,避免污染下一个页面
const header = usePageHeader()
const syncHeader = () => {
  if (!header) return
  header.crumbs = [{ label: '项目' }, { label: '创作项目' }]
  header.actions = user.isEditorOrAbove
    ? [{ key: 'new', label: '新建创作任务', type: 'primary', icon: Plus, onClick: () => router.push('/projects/new') }]
    : []
}
syncHeader()
watch(() => user.isEditorOrAbove, syncHeader)
onBeforeRouteLeave(() => { if (header) { header.crumbs = []; header.actions = [] } })

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
.topic-link {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
  cursor: pointer;
  font-size: var(--fs-13);
  font-weight: 600;
  line-height: var(--lh-13);
  color: var(--ink);
}
.topic-link:hover { color: var(--brand); }
.cell-muted { color: var(--muted); font-size: var(--fs-12); }
.btn-icon { margin-right: var(--sp-1); }

/* 工具带:单行排布,右端批量删除(ADMIN) */
.toolbar { display: flex; align-items: center; gap: var(--sp-4); margin-bottom: var(--sp-5); }
.toolbar-search { width: 240px; }
.toolbar-status { width: 150px; }
.toolbar-order { width: 140px; }
.toolbar-dir { min-width: 56px; }
.toolbar-delete { margin-left: auto; }

/* 表格:去竖线,行高约 40px,弱表头底,操作列悬浮/聚焦显形 */
.proj-table { --el-table-row-hover-bg-color: var(--n-50); }
.proj-table :deep(.el-table__inner-wrapper::before) { display: none; }
.proj-table :deep(.el-table__cell) { padding: var(--sp-4) 0; border-right: none; }
.proj-table :deep(th.el-table__cell) { font-weight: 600; font-size: var(--fs-12); }
.proj-table :deep(.el-table__cell .cell) { padding: 0 var(--sp-5); line-height: var(--lh-13); }
.proj-table :deep(.el-tag) { transform: scale(.94); transform-origin: left center; }
.row-ops { opacity: 0; transition: opacity .12s ease; }
.proj-table :deep(.el-table__row):hover .row-ops,
.proj-table :deep(.el-table__row):focus-within .row-ops { opacity: 1; }
@media (prefers-reduced-motion: reduce) {
  .row-ops { transition: none; }
}
</style>
