<template>
  <div class="clarify">
    <div class="clarify-head"><el-icon><ChatDotRound /></el-icon>补齐生成需求
      <span class="hint">{{ locked ? '需求已锁定(只读)' : '填写后 AI 将按此研究,避免空泛表述' }}</span>
    </div>
    <el-form label-position="top" :disabled="locked">
      <el-form-item v-for="(q,i) in questions" :key="i" :required="q.required">
        <template #label><span class="q-text">{{ q.q }}</span></template>
        <el-radio-group v-if="q.type === 'single'" v-model="model[q.q]">
          <el-radio v-for="(o,j) in (q.options||[])" :key="j" :value="o">{{ o }}</el-radio>
        </el-radio-group>
        <el-input v-else v-model="model[q.q]" :placeholder="q.required ? '必填' : '可留空'" />
      </el-form-item>
    </el-form>
    <el-button v-if="!locked" type="primary" :loading="saving" @click="onSubmit">锁定需求,开始研究</el-button>
  </div>
</template>

<script setup>
import { reactive, watch } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'

const props = defineProps({
  questions: { type: Array, required: true },      // [{q,type,options,required}]
  locked: { type: Boolean, default: false },
  answers: { type: Array, default: () => [] }      // 锁定后回显 [{q,a}]
})
const emit = defineEmits(['submit'])
const model = reactive({})

watch(() => props.answers, (v) => {
  if (v && v.length) for (const a of v) model[a.q] = a.a
}, { immediate: true })

const onSubmit = () => emit('submit', { ...model })
</script>

<style scoped>
.clarify-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; font-weight: 600; }
.clarify-head .el-icon { color: var(--brand); }
.q-text { font-size: 14px; }
@media (max-width: 768px) { .el-radio-group { flex-direction: column; gap: 8px; } }
</style>