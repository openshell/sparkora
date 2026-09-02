<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">Car Sync</span>
          <h2>车型同步</h2>
        </div>
        <div class="actions">
          <el-button @click="$router.push('/car')">返回车型库</el-button>
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
        <div class="sync-bar">
          <el-checkbox :model-value="allSelected" @change="toggleAll">全选</el-checkbox>
          <span class="sel-count">已选 {{ selected.length }} 个车型</span>
          <el-button type="primary" :loading="syncing" :disabled="!selected.length" @click="onSync">
            <el-icon class="btn-icon"><Refresh /></el-icon>同步选中
          </el-button>
        </div>

        <div class="catalog-list">
          <el-checkbox-group v-model="selected">
            <el-checkbox v-for="c in catalog" :key="c.id" :value="c.id" class="catalog-item">
              <span class="c-name">{{ c.name }}</span>
              <span class="c-meta">{{ c.salesNetworkName || '' }} · {{ c.price || '价格待同步' }}</span>
              <el-tag v-if="isSynced(c.id)" size="small" type="success" effect="plain">已入库</el-tag>
            </el-checkbox>
          </el-checkbox-group>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { carApi } from '../api'
import { ElMessage } from 'element-plus'
import TopBar from '../layouts/TopBar.vue'
import { Refresh, WarningFilled } from '@element-plus/icons-vue'

const loading = ref(false)
const error = ref('')
const catalog = ref([])
const selected = ref([])
const syncing = ref(false)
const syncedIds = ref(new Set())

const allSelected = computed(() => catalog.value.length > 0 && selected.value.length === catalog.value.length)

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const [catRes, listRes] = await Promise.all([carApi.catalog(), carApi.list()])
    catalog.value = catRes.data || []
    syncedIds.value = new Set((listRes.data || []).map(m => m.goodsId))
  } catch (e) { error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试' }
  finally { loading.value = false }
}

const isSynced = (goodsId) => syncedIds.value.has(String(goodsId))

const toggleAll = (val) => {
  selected.value = val ? catalog.value.map(c => c.id) : []
}

const onSync = async () => {
  if (!selected.value.length) { ElMessage.warning('请选择要同步的车型'); return }
  syncing.value = true
  try {
    const res = await carApi.syncSelected(selected.value)
    if (res.code === 0) { ElMessage.success(`同步完成，入库 ${res.data} 个车型`); await load() }
    else ElMessage.error(res.msg || '同步失败')
  } catch (e) { ElMessage.error('同步失败：' + (e.message || e)) }
  finally { syncing.value = false }
}

onMounted(load)
</script>

<style scoped>
.btn-icon { margin-right: 2px; }
.sync-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.sel-count { color: var(--muted); font-size: 13px; }
.catalog-list { display: flex; flex-direction: column; gap: 4px; }
.catalog-item { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border: 1px solid var(--line); border-radius: var(--radius-sm); width: 100%; }
.c-name { font-weight: 600; }
.c-meta { color: var(--muted); font-size: 12px; }
</style>
