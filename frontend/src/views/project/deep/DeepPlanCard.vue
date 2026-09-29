<template>
  <el-collapse v-model="openPanels" class="deep-plan">
    <el-collapse-item name="plan">
      <template #title>
        <span class="plan-title"><el-icon><Aim /></el-icon>研究计划
          <el-tag size="small" effect="plain" round>深度模式</el-tag>
        </span>
      </template>
      <div class="plan-body">
        <div class="plan-sec"><span class="plan-label">研究问题</span>
          <ol class="plan-list"><li v-for="(q,i) in plan.keyQuestions||[]" :key="i">{{ q }}</li></ol>
        </div>
        <div class="plan-sec" v-if="(plan.dataNeeds||[]).length"><span class="plan-label">数据需求</span>
          <ul class="plan-list plain"><li v-for="(d,i) in plan.dataNeeds" :key="i">{{ d }}</li></ul>
        </div>
        <div class="plan-sec" v-if="(plan.toolHints||[]).length"><span class="plan-label">检索工具</span>
          <div class="tool-row">
            <el-tag v-for="(t,i) in plan.toolHints" :key="i" size="small" effect="plain" class="tool-tag">
              {{ (t.tools||[]).join('+') }}
            </el-tag>
          </div>
        </div>
      </div>
    </el-collapse-item>
  </el-collapse>
</template>

<script setup>
import { ref } from 'vue'
import { Aim } from '@element-plus/icons-vue'

const props = defineProps({ plan: { type: Object, required: true } })
const openPanels = ref(['plan'])
</script>

<style scoped>
/* 批 2(09-28-pc-ui-refactor):去掉 768px 断点(PC-only),走 token */
.plan-title { display: inline-flex; align-items: center; gap: var(--sp-2); font-weight: 600; font-size: var(--fs-14); line-height: var(--lh-14); }
.plan-body { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: var(--sp-4) var(--sp-7); }
.plan-sec { min-width: 0; }
.plan-label { color: var(--faint); font-size: var(--fs-12); line-height: var(--lh-12); }
.plan-list { margin: var(--sp-1) 0 0; padding-left: var(--sp-5); }
.plan-list li { font-size: var(--fs-14); line-height: var(--lh-18); color: var(--ink); }
.plan-list.plain { list-style: none; padding-left: 0; }
.tool-row { display: flex; gap: var(--sp-2); flex-wrap: wrap; margin-top: var(--sp-1); }
</style>
