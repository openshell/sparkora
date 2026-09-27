<template>
  <div v-if="semanticMode" class="sem-bar">
    <span class="sem-bar-text">
      <el-icon><Aim /></el-icon>
      语义搜索 {{ semanticQuery ? `「${semanticQuery}」` : '' }} · 按相关度排序
      <template v-if="semanticActive">，命中 {{ total }} 张</template>
    </span>
    <el-tag v-if="semanticActive" size="small" type="info" effect="plain">门槛 ≥{{ semanticMinScore }}，按相关度排序</el-tag>
    <el-button size="small" text @click="emit('exit-semantic')">退出语义搜索</el-button>
  </div>
</template>

<script setup>
import { Aim } from '@element-plus/icons-vue'

/**
 * 图库语义搜索提示条：结果按相关度排序，展示命中原因（嵌入原文）。
 * 从 ImageLibrary.vue 抽出（09-27-split-monoliths，纯结构重构，零行为变化）。
 */
defineProps({
  semanticMode: { type: Boolean, default: false },
  semanticQuery: { type: String, default: '' },
  semanticActive: { type: Boolean, default: false },
  semanticMinScore: { type: Number, default: 0.3 },
  total: { type: Number, default: 0 }
})
const emit = defineEmits(['exit-semantic'])
</script>

<style scoped>
/* 语义搜索提示条（09-15 img-semantic-search 子B） */
.sem-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; padding: 8px 12px; background: var(--card); border: 1px solid var(--line); border-radius: var(--radius-sm); flex-wrap: wrap; }
.sem-bar-text { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: var(--text); }

@media (max-width: 768px) {
  .sem-bar .el-button { min-height: 44px; }
}
</style>
