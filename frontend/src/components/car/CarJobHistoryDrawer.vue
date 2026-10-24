<template>
  <el-drawer :model-value="modelValue" title="同步记录" size="760px"
    :close-on-click-modal="false" @update:model-value="$emit('update:modelValue', $event)">
    <div class="history-body">
      <div class="history-toolbar">
        <span class="hint">最近 50 条任务（含全量/图片补入库/增量/补清洗）</span>
        <el-button :loading="loading" size="small" @click="load">
          <el-icon class="btn-icon"><Refresh /></el-icon>刷新
        </el-button>
      </div>

      <div v-if="loading && !jobs.length" class="loading"><el-skeleton :rows="5" animated /></div>
      <div v-else-if="error" class="state-error">
        <div class="state-msg">{{ error }}</div>
        <el-button type="primary" plain @click="load">重试</el-button>
      </div>
      <el-empty v-else-if="!jobs.length" description="暂无同步记录" />
      <template v-else>
        <el-table :data="jobs" size="small" highlight-current-row class="history-table"
          @row-click="selectRow">
          <el-table-column label="时间" width="145">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="类型" width="100">
            <template #default="{ row }">{{ typeLabel(row.jobType) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="82">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="成功/跳过/失败" min-width="120">
            <template #default="{ row }">
              {{ row.success || 0 }} / {{ row.skipped || 0 }} / {{ row.failed || 0 }}
            </template>
          </el-table-column>
          <el-table-column label="操作人" width="90" prop="createdBy" />
        </el-table>

        <div v-if="selected" class="history-detail">
          <CarJobPanel :job="selected" :can-retry="false" :can-cancel="false" />
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, watch } from 'vue'
import { carApi } from '../../api'
import { Refresh } from '@element-plus/icons-vue'
import CarJobPanel from './CarJobPanel.vue'

const props = defineProps({ modelValue: { type: Boolean, default: false } })
defineEmits(['update:modelValue'])

const jobs = ref([])
const selected = ref(null)
const loading = ref(false)
const error = ref('')

const typeLabel = (t) => ({
  SELECTED: '选中同步', RETRY: '重试', IMAGE_BACKFILL: '图片补入库',
  INCREMENTAL: '增量同步', CLEAN_BACKFILL: '补清洗', SCHEDULED: '定时同步'
}[t] || '同步')

const statusLabel = (s) => ({ RUNNING: '运行中', SUCCESS: '成功', PARTIAL: '部分', FAILED: '失败' }[s] || s || '—')
const statusType = (s) => ({ RUNNING: 'warning', SUCCESS: 'success', PARTIAL: 'warning', FAILED: 'danger' }[s] || 'info')
const fmt = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

const selectRow = (row) => { selected.value = row }

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await carApi.listJobs()
    if (res.code !== 0) throw new Error(res.msg || '加载失败')
    jobs.value = (res.data || []).slice(0, 50)
    if (selected.value) selected.value = jobs.value.find(j => j.id === selected.value.id) || null
  } catch (e) {
    error.value = e?.response?.data?.msg || e?.message || '同步记录加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.modelValue, (open) => {
  if (open) {
    selected.value = null
    load()
  }
})
</script>

<style scoped>
.history-body { display: flex; flex-direction: column; gap: 10px; }
.history-toolbar { display: flex; justify-content: space-between; align-items: center; }
.hint { color: var(--muted); font-size: 13px; }
.history-table { width: 100%; }
.history-detail { margin-top: 8px; }
.history-detail :deep(.job-panel) { margin-bottom: 0; }
.state-error { padding: 24px 0; color: var(--err); }
.loading { padding: 12px 0; }
</style>
