<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">Knowledge Center</span>
          <h2>知识中心</h2>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="kc-tabs">
        <el-tab-pane label="车型" name="car">
          <CarKnowledgePanel v-if="loadedTabs.car" />
        </el-tab-pane>
        <el-tab-pane label="新闻" name="news">
          <NewsKnowledgePanel v-if="loadedTabs.news" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import TopBar from '../layouts/TopBar.vue'
import CarKnowledgePanel from './knowledge/CarKnowledgePanel.vue'
import NewsKnowledgePanel from './knowledge/NewsKnowledgePanel.vue'

const activeTab = ref('car')
// 懒挂载：首次切入某 Tab 才挂载对应面板（避免无谓请求），挂载后保留状态
const loadedTabs = reactive({ car: false, news: false })

const markLoaded = (name) => { if (loadedTabs[name] !== undefined) loadedTabs[name] = true }

onMounted(() => markLoaded(activeTab.value))
watch(activeTab, (name) => markLoaded(name))
</script>

<style scoped>
.kc-tabs :deep(.el-tabs__header) { margin-bottom: 20px; }
.kc-tabs :deep(.el-tabs__item) { font-size: 15px; height: 44px; line-height: 44px; }

@media (max-width: 768px) {
  .kc-tabs :deep(.el-tabs__item) { min-height: 44px; }
}
</style>
