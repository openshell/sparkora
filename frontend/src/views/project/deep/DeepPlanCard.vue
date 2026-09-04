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
.plan-title { display: inline-flex; align-items: center; gap: 8px; font-weight: 600; }
.plan-body { display: flex; flex-direction: column; gap: 10px; }
.plan-label { color: var(--faint); font-size: 12px; margin-right: 8px; }
.plan-list { margin: 4px 0 0; padding-left: 20px; }
.plan-list.plain { list-style: none; padding-left: 0; }
.tool-row { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 4px; }
@media (max-width: 768px) { .deep-plan { font-size: 13px; } }
</style>