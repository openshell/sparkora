<template>
  <div>
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">Car Sync</span>
          <h2>车型同步</h2>
        </div>
        <div class="actions">
          <el-button @click="historyVisible = true">
            <el-icon class="btn-icon"><Clock /></el-icon>同步记录
          </el-button>
          <el-button @click="$router.push('/knowledge')">返回知识中心</el-button>
        </div>
      </div>

      <div v-if="loading" class="loading"><el-skeleton :rows="5" animated /></div>

      <div v-else-if="error" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">车型目录加载失败</div>
        <div class="state-msg">{{ error }}</div>
        <el-button type="primary" plain @click="load">重试</el-button>
      </div>

      <template v-else>
        <CarJobPanel v-if="job" :job="job" :retrying="retrying" :can-retry="user.isEditorOrAbove"
          :can-cancel="user.isEditorOrAbove" :cancelling="cancelling" closable
          @retry="onRetry" @cancel="onCancelJob" @close="onCloseJob" />

        <div class="sync-bar">
          <span class="hint">从官网在售目录选择车型同步（含参数清洗 / 切块 / 向量）；「图片补入库」只补封面与详情图。</span>
          <div class="sync-actions">
            <el-button :loading="loading" @click="load">
              <el-icon class="btn-icon"><Refresh /></el-icon>刷新目录
            </el-button>
            <el-button v-if="user.isEditorOrAbove" :loading="backfilling" :disabled="jobBusy" @click="onBackfill">
              <el-icon class="btn-icon"><Picture /></el-icon>图片补入库
            </el-button>
            <el-button v-if="user.isEditorOrAbove" type="primary" :loading="creating"
              :disabled="!selected.length || jobBusy" @click="onSync">
              <el-icon class="btn-icon"><Refresh /></el-icon>同步选中
            </el-button>
          </div>
        </div>

        <CarCatalogGrid v-model="selected" :catalog="catalog" :synced-ids="syncedIds" />
      </template>

      <CarJobHistoryDrawer v-model="historyVisible" />
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { carApi } from '../api'
import { useUserStore } from '../store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, Refresh, WarningFilled, Picture } from '@element-plus/icons-vue'
import CarJobPanel from '../components/car/CarJobPanel.vue'
import CarJobHistoryDrawer from '../components/car/CarJobHistoryDrawer.vue'
import CarCatalogGrid from '../components/car/CarCatalogGrid.vue'
import { useCarSyncJob } from '../composables/useCarSyncJob'

const user = useUserStore()
const loading = ref(false)
const error = ref('')
const catalog = ref([])
const syncedIds = ref(new Set())
const selected = ref([])
const historyVisible = ref(false)
const creating = ref(false)
const backfilling = ref(false)

const { job, jobId, jobBusy, retrying, cancelling, cancelCurrent, startPoll, stopPoll, beginStart, cancelStart, recoverLatest, retryCurrent } = useCarSyncJob()

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const [catRes, listRes] = await Promise.all([carApi.catalog(), carApi.list()])
    if (catRes.code !== 0) throw new Error(catRes.msg || '车型目录加载失败')
    catalog.value = catRes.data || []
    syncedIds.value = new Set(((listRes.code === 0 ? listRes.data : []) || []).map(m => m.goodsId))
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}

const onSync = async () => {
  if (jobBusy.value) { ElMessage.warning('已有同步任务在运行，请等待完成后再发起'); return }
  if (creating.value) return
  if (!selected.value.length) { ElMessage.warning('请选择要同步的车型'); return }
  if (!beginStart()) return   // 请求在途即置忙，堵住并发创建（含「图片补入库」）
  creating.value = true
  try {
    const res = await carApi.createJob(selected.value)
    if (res.code === 0 && res.data?.jobId) {
      selected.value = []
      job.value = null
      startPoll(res.data.jobId, { onFinished: load })
    } else {
      cancelStart()
      ElMessage.error(res.msg || '创建任务失败')
    }
  } catch (e) {
    cancelStart()
    ElMessage.error(e?.response?.data?.msg || '创建任务失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

// 图片补入库：仅图片域（封面+详情图集）+ 哈希去重，不重跑清洗/向量
const onBackfill = () => {
  if (jobBusy.value) { ElMessage.warning('已有任务在运行，请等待完成后再发起'); return }
  if (backfilling.value) return
  ElMessageBox.confirm(
    '只补缺失/失败/过期的车型图片（封面 + 详情图集）：仅处理图片域（下载/哈希去重/图床/打标），不重跑参数清洗与向量化；已有图片凭内容哈希去重，不会重复占图床。确认执行？',
    '图片补入库', { type: 'info', confirmButtonText: '开始补入库', cancelButtonText: '取消' }
  ).then(async () => {
    if (!beginStart()) return
    backfilling.value = true
    try {
      const res = await carApi.createBackfillJob()
      if (res.code === 0 && res.data) {
        job.value = null
        startPoll(res.data, { onFinished: load })
      } else {
        cancelStart()
        ElMessage.error(res.msg || '创建任务失败')
      }
    } catch (e) {
      cancelStart()
      if (e?.response?.status === 403) ElMessage.error('无权限：仅管理员/编辑可创建补入库任务')
      else ElMessage.error(e?.response?.data?.msg || '创建任务失败，请稍后重试')
    } finally {
      backfilling.value = false
    }
  }).catch(() => {})
}

const onRetry = () => retryCurrent({ onFinished: load })
const onCancelJob = () => cancelCurrent()

const onCloseJob = () => {
  stopPoll()
  job.value = null
  jobId.value = null
}

onMounted(() => {
  load()
  recoverLatest({ onFinished: load })
})
onBeforeUnmount(stopPoll)
</script>

<style scoped>
.btn-icon { margin-right: 2px; }
.sync-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.hint { font-size: 12px; color: var(--muted); }
.sync-actions { margin-left: auto; display: flex; gap: var(--sp-3); }
</style>
