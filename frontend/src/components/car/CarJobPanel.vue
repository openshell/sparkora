<template>
  <div v-if="job" class="job-panel" :class="jobClass">
    <div class="job-head">
      <span class="job-title">
        <el-icon class="spin" v-if="job.status === 'RUNNING'"><Loading /></el-icon>
        <el-icon v-else><CircleCheck /></el-icon>
        {{ jobTitle }}
        <el-tag size="small" effect="plain" round class="job-type">{{ jobTypeLabel }}</el-tag>
      </span>
      <span class="job-head-right">
        <span class="job-count">
          {{ job.success || 0 }} 成功 · {{ job.failed || 0 }} 失败<template v-if="skippedCount"> · 跳过 {{ skippedCount }}</template>
        </span>
        <el-button v-if="canCancel && job.status === 'RUNNING'" text size="small" type="warning"
          :loading="cancelling" title="停止后续车型（当前车型可能跑完）" @click="$emit('cancel')">
          取消
        </el-button>
        <el-button v-if="closable && job.status !== 'RUNNING'" text size="small" class="close-btn"
          title="关闭任务面板" @click="$emit('close')">
          <el-icon><Close /></el-icon>
        </el-button>
      </span>
    </div>

    <el-progress v-if="job.status === 'RUNNING'" :percentage="progress" :stroke-width="8" :show-text="false" />
    <div v-if="job.status === 'RUNNING'" class="job-progress-text">
      已处理 {{ processedCount }} / {{ job.total || 0 }} 个车型
    </div>

    <div v-if="detailItems.length" class="job-failed">
      <div class="failed-title">{{ failedTitle }}</div>
      <div v-for="(f, i) in detailItems" :key="i" class="failed-item">
        <span class="f-name">{{ f.name || f.goodsId }}</span>
        <!-- error=真实失败（红）；note=成功路径的可观测信息（灰），不误报为异常 -->
        <span v-if="f.error" class="f-err">{{ f.error }}</span>
        <span v-else class="f-note">{{ f.note }}</span>
      </div>
      <el-button v-if="canRetry && job.status !== 'RUNNING' && errorItems.length" size="small" type="primary" plain
        :loading="retrying" @click="$emit('retry')">
        重试失败项
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Loading, CircleCheck, Close } from '@element-plus/icons-vue'

const props = defineProps({
  job: { type: Object, default: null },
  retrying: { type: Boolean, default: false },
  closable: { type: Boolean, default: false },
  /** 失败项重试按钮：写接口仅 ADMIN/EDITOR，VIEWER 传入 false 隐藏（后端 @PreAuthorize 兜底）。 */
  canRetry: { type: Boolean, default: true },
  /** 运行中取消按钮：写接口仅 ADMIN/EDITOR。 */
  canCancel: { type: Boolean, default: false },
  cancelling: { type: Boolean, default: false }
})

defineEmits(['retry', 'close', 'cancel'])

const failedItems = computed(() => {
  if (!props.job?.failedItems) return []
  try {
    const v = JSON.parse(props.job.failedItems)
    return Array.isArray(v) ? v : []
  } catch {
    return []
  }
})
const errorItems = computed(() => failedItems.value.filter(f => f.error))
const noteItems = computed(() => failedItems.value.filter(f => !f.error && f.note))
const detailItems = computed(() => [...errorItems.value, ...noteItems.value])
const failedTitle = computed(() =>
  errorItems.value.length && noteItems.value.length ? '任务明细：'
    : errorItems.value.length ? '失败明细：' : '明细备注：')

const skippedCount = computed(() => {
  if (props.job?.skipped != null) return Number(props.job.skipped) || 0
  return noteItems.value.filter(f => (f.note || '').startsWith('跳过')).length
})
const processedCount = computed(() => (props.job?.success || 0) + (props.job?.failed || 0) + skippedCount.value)
const allSkipped = computed(() => !!props.job?.total && skippedCount.value >= props.job.total && !(props.job?.failed > 0))

const jobRunning = computed(() => props.job?.status === 'RUNNING')

const progress = computed(() => {
  if (!props.job || !props.job.total) return 0
  return Math.round((processedCount.value / props.job.total) * 100)
})

const jobTitle = computed(() => {
  if (!props.job) return ''
  if (props.job.status === 'RUNNING') {
    return props.job.jobType === 'INCREMENTAL' ? '检查更新中…' : '同步进行中…'
  }
  if (allSkipped.value) return '检查完成(无变化)'
  return { SUCCESS: '同步完成', PARTIAL: '部分完成', FAILED: '同步失败' }[props.job.status] || '同步'
})
const jobClass = computed(() => props.job?.status?.toLowerCase() || '')

// job_type 中文标签（SELECTED/RETRY/IMAGE_BACKFILL/INCREMENTAL/SCHEDULED）
const jobTypeLabel = computed(() => ({
  SELECTED: '选中同步', RETRY: '重试', IMAGE_BACKFILL: '图片补入库', INCREMENTAL: '增量同步', CLEAN_BACKFILL: '补清洗', SCHEDULED: '定时同步'
}[props.job?.jobType] || '同步'))

defineExpose({ jobRunning })
</script>

<style scoped>
.job-panel {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px 16px;
  margin-bottom: 16px;
  background: var(--card);
}
.job-panel.success { border-color: color-mix(in srgb, var(--ok) 40%, var(--line)); }
.job-panel.partial, .job-panel.failed { border-color: color-mix(in srgb, var(--warn) 40%, var(--line)); }
.job-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 8px; }
.job-title { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.job-type { flex-shrink: 0; }
.job-head-right { display: flex; align-items: center; gap: 4px; }
.job-count { color: var(--muted); font-size: 13px; }
.close-btn { padding: 2px; color: var(--muted); }
.job-progress-text { font-size: 12px; color: var(--muted); margin-top: 6px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.job-failed { margin-top: 10px; }
.failed-title { font-size: 13px; color: var(--muted); margin-bottom: 6px; }
.failed-item { display: flex; gap: 8px; font-size: 12px; padding: 4px 0; border-bottom: 1px dashed var(--line); }
.f-name { font-weight: 600; flex-shrink: 0; }
.f-err { color: var(--err); word-break: break-all; }
.f-note { color: var(--muted); word-break: break-all; }
</style>
