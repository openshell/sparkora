<template>
  <div class="source-tab">
    <SourceManagePanel @collected="onCollected" />
    <el-divider class="section-divider" />
    <SourceJobPanel ref="jobRef" :source-options="sourceOptions" />
    <el-divider class="section-divider" />
    <SourceContentPanel :source-options="sourceOptions" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import SourceManagePanel from './SourceManagePanel.vue'
import SourceJobPanel from './SourceJobPanel.vue'
import SourceContentPanel from './SourceContentPanel.vue'
import { sourceApi } from '../../api'

// 信源选项由本 Tab 统一加载，供任务/内容面板做名称映射与筛选下拉，避免各面板重复请求
const sourceOptions = ref([])
const jobRef = ref(null)

const loadSources = async () => {
  try {
    const res = await sourceApi.list()
    sourceOptions.value = res.data || []
  } catch { /* 选项加载失败不阻断子面板；下拉为空时仍可浏览 */ }
}

// 信源面板触发采集 → 通知任务面板刷新并进入轮询
const onCollected = () => {
  jobRef.value?.load?.()
}

onMounted(loadSources)
</script>

<style scoped>
.source-tab { padding-bottom: var(--sp-8); }
.section-divider { margin: var(--sp-7) 0; }
</style>
