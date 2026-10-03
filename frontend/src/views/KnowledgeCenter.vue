<template>
  <div class="kc-page">
    <div class="kc-head">
      <h2>知识中心</h2>
    </div>

    <el-tabs v-model="activeTab" class="kc-tabs">
      <el-tab-pane label="车型" name="car">
        <CarKnowledgePanel v-if="loadedTabs.car" />
      </el-tab-pane>
      <el-tab-pane label="知识库" name="kb">
        <KbLibraryPanel v-if="loadedTabs.kb" />
      </el-tab-pane>
      <el-tab-pane label="新闻" name="news">
        <NewsKnowledgePanel v-if="loadedTabs.news" />
      </el-tab-pane>
      <el-tab-pane label="检索问答" name="qa">
        <QaChatPanel v-if="loadedTabs.qa" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import CarKnowledgePanel from './knowledge/CarKnowledgePanel.vue'
import KbLibraryPanel from './knowledge/KbLibraryPanel.vue'
import NewsKnowledgePanel from './knowledge/NewsKnowledgePanel.vue'
import QaChatPanel from './knowledge/QaChatPanel.vue'

const activeTab = ref('car')
// 懒挂载：首次切入某 Tab 才挂载对应面板（避免无谓请求），挂载后保留状态
const loadedTabs = reactive({ car: false, kb: false, news: false, qa: false })

const markLoaded = (name) => { if (loadedTabs[name] !== undefined) loadedTabs[name] = true }

onMounted(() => markLoaded(activeTab.value))
watch(activeTab, (name) => markLoaded(name))
</script>

<style scoped>
/* 知识中心撑满 app-shell__body，tab 内容区接管滚动（内容型 tab）或满高（问答 tab） */
.kc-page { flex: 1; min-height: 0; display: flex; flex-direction: column; padding: var(--sp-5) var(--sp-7) var(--sp-8); }
.kc-head { flex: none; margin-bottom: var(--sp-4); }
.kc-head h2 { margin: 0; font-size: var(--fs-22); font-weight: 600; line-height: var(--lh-22); }

.kc-tabs { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.kc-tabs :deep(.el-tabs__header) { flex: none; margin-bottom: var(--sp-4); }
.kc-tabs :deep(.el-tabs__item) { font-size: 15px; height: 44px; line-height: 44px; }
/* 高度链：content 撑满 → 激活 pane 撑满 → 内容型 pane 自身滚动、问答 pane 满高不滚动 */
.kc-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; }
.kc-tabs :deep(.el-tab-pane) { height: 100%; overflow-y: auto; }
.kc-tabs :deep(#pane-qa) { overflow: hidden; }
</style>
