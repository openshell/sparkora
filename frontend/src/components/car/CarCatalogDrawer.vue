<template>
  <el-drawer :model-value="modelValue" title="同步官网车型" size="760px"
    :close-on-click-modal="false" @update:model-value="$emit('update:modelValue', $event)">
    <div class="drawer-body">
      <div v-if="loading" class="loading"><el-skeleton :rows="5" animated /></div>

      <div v-else-if="error" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">车型目录加载失败</div>
        <div class="state-msg">{{ error }}</div>
        <el-button type="primary" plain @click="loadCatalog">重试</el-button>
      </div>

      <CarCatalogGrid v-else v-model="selected" :catalog="catalog" :synced-ids="syncedIds" />
    </div>

    <template #footer>
      <div class="drawer-footer">
        <el-button :loading="loading" @click="loadCatalog">
          <el-icon class="btn-icon"><Refresh /></el-icon>刷新目录
        </el-button>
        <span v-if="busy" class="busy-hint">已有任务运行中，完成后再发起</span>
        <div class="footer-right">
          <el-button :loading="backfilling" :disabled="busy || creating" @click="onBackfill">
            <el-icon class="btn-icon"><Picture /></el-icon>图片补入库
          </el-button>
          <el-button type="primary" :loading="creating" :disabled="busy || !selected.length" @click="onCreate">
            <el-icon class="btn-icon"><Refresh /></el-icon>同步选中
          </el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref, watch } from 'vue'
import { carApi } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Picture, WarningFilled } from '@element-plus/icons-vue'
import CarCatalogGrid from './CarCatalogGrid.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  syncedIds: { type: [Set, Array], default: () => [] },
  /** 外部任务运行中时禁止再创建任务。 */
  busy: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'created'])

const loading = ref(false)
const error = ref('')
const catalog = ref([])
const selected = ref([])
const creating = ref(false)
const backfilling = ref(false)

const loadCatalog = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await carApi.catalog()
    if (res.code === 0) catalog.value = res.data || []
    else error.value = res.msg || '车型目录加载失败'
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}

watch(() => props.modelValue, (visible) => {
  if (visible) {
    selected.value = []
    loadCatalog()
  }
}, { immediate: true })

const finish = (jobId, type) => {
  if (!jobId) {   // 后端成功码但缺任务号：不关闭抽屉、不上抛，避免父级空轮询
    ElMessage.warning('任务创建响应缺少任务号，请刷新任务列表确认')
    return
  }
  selected.value = []
  emit('created', { jobId, type })
  emit('update:modelValue', false)
}

const onCreate = async () => {
  if (props.busy) { ElMessage.warning('已有任务在运行，请等待完成后再发起'); return }
  if (creating.value) return
  if (!selected.value.length) { ElMessage.warning('请选择要同步的车型'); return }
  creating.value = true
  try {
    const res = await carApi.createJob(selected.value)
    if (res.code === 0) {
      ElMessage.success('同步任务已创建')
      finish(res.data.jobId, res.data.jobType || 'SELECTED')
    } else ElMessage.error(res.msg || '创建任务失败')
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '创建任务失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

// 图片补入库：只动图片域（封面+详情图集），哈希去重；不重跑参数清洗/向量
const onBackfill = () => {
  if (props.busy) { ElMessage.warning('已有任务在运行，请等待完成后再发起'); return }
  if (backfilling.value) return
  ElMessageBox.confirm(
    '为全部存量车型补转官网封面与详情图进图库：仅处理图片域（下载/哈希去重/图床/打标），不重跑参数清洗与向量化；已有图片凭内容哈希去重，不会重复占图床。确认执行？',
    '图片补入库', { type: 'info', confirmButtonText: '开始补入库', cancelButtonText: '取消' }
  ).then(async () => {
    backfilling.value = true
    try {
      const res = await carApi.createBackfillJob()
      if (res.code === 0) {
        ElMessage.success('图片补入库任务已创建')
        finish(res.data, 'IMAGE_BACKFILL')
      } else ElMessage.error(res.msg || '创建任务失败')
    } catch (e) {
      if (e?.response?.status === 403) ElMessage.error('无权限：仅管理员/编辑可创建补入库任务')
      else ElMessage.error(e?.response?.data?.msg || '创建任务失败，请稍后重试')
    } finally {
      backfilling.value = false
    }
  }).catch(() => {})
}
</script>

<style scoped>
.drawer-body { min-height: 220px; }
.btn-icon { margin-right: 4px; }
.drawer-footer { display: flex; align-items: center; gap: 10px; }
.footer-right { margin-left: auto; display: flex; gap: var(--sp-3); }
.busy-hint { color: var(--warn); font-size: 12px; }
</style>
