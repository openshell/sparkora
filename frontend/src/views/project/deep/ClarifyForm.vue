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
          <!-- 其他:自行填写(选项外表述);选中即切到文本框 -->
          <el-radio :value="OTHER">{{ OTHER_LABEL }}</el-radio>
        </el-radio-group>
        <!-- multi(如写作锚点车型/竞品多选):checkbox 组,答案以「、」拼接为字符串提交 -->
        <el-checkbox-group v-else-if="q.type === 'multi'" v-model="model['__multi_' + q.q]">
          <el-checkbox v-for="(o,j) in (q.options||[])" :key="j" :value="o">{{ o }}</el-checkbox>
          <!-- 其他:勾选后展开文本框,内容并入答案 -->
          <el-checkbox :value="OTHER">{{ OTHER_LABEL }}</el-checkbox>
        </el-checkbox-group>
        <!-- 「其他」自由输入(single 选中其他 / multi 勾选其他时出现) -->
        <el-input
          v-if="(q.type === 'single' && model[q.q] === OTHER) || (q.type === 'multi' && otherChecked(q))"
          v-model="model['__other_' + q.q]"
          class="other-input"
          maxlength="200"
          :placeholder="q.required ? '请填写(必填)' : '请填写(可留空)'" />
        <el-input v-else-if="!q.type || q.type === 'input'" v-model="model[q.q]" :placeholder="q.required ? '必填' : '可留空'" />
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

const OTHER = '__other__'
const OTHER_LABEL = '其他(自行填写)'

// multi 是否勾选了「其他」
const otherChecked = (q) => {
  const arr = model['__multi_' + q.q]
  return Array.isArray(arr) && arr.includes(OTHER)
}

watch(() => props.answers, (v) => {
  if (!v || !v.length) return
  for (const a of v) {
    const q = (props.questions || []).find(x => x.q === a.q)
    if (!q) continue
    if (q.type === 'multi') {
      // multi 回显:选项中存在的进勾选数组;不在 options 里的文本(历史「其他」填写)勾选 OTHER 并回填文本框
      const opts = q.options || []
      const parts = a.a ? a.a.split('、') : []
      const picked = []
      for (const p of parts) {
        if (opts.includes(p)) picked.push(p)
        else { picked.push(OTHER); model['__other_' + a.q] = p }
      }
      model['__multi_' + a.q] = picked
    } else if (q.type === 'single') {
      // single 回显:答案不在 options 中 → 视为历史「其他」填写,选中 OTHER 并回填
      if ((q.options || []).includes(a.a)) model[a.q] = a.a
      else if (a.a) { model[a.q] = OTHER; model['__other_' + a.q] = a.a }
      else model[a.q] = a.a
    } else model[a.q] = a.a
  }
}, { immediate: true })

// multi 用 __multi_ 前缀的数组暂存;「其他」选中时取 __other_ 文本并入;
// 提交时数组拼成「、」分隔字符串,与锁定结构 [{q,a}] 对齐
const onSubmit = () => {
  const out = {}
  for (const q of props.questions) {
    if (q.type === 'multi') {
      const arr = model['__multi_' + q.q]
      const parts = Array.isArray(arr) ? arr.filter(x => x !== OTHER) : []
      // 「其他」文本非空才并入(勾了其他但没填 → 丢弃该勾选)
      const other = (model['__other_' + q.q] || '').trim()
      if (otherChecked(q) && other) parts.push(other)
      out[q.q] = parts.join('、')
    } else if (q.type === 'single') {
      // 选了「其他」→ 提交文本框内容;没填 → 提交空(按未答处理)
      out[q.q] = model[q.q] === OTHER ? (model['__other_' + q.q] || '').trim() : (model[q.q] ?? '')
    } else out[q.q] = model[q.q] ?? ''
  }
  emit('submit', out)
}
</script>

<style scoped>
.clarify-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; font-weight: 600; }
.clarify-head .el-icon { color: var(--brand); }
.q-text { font-size: 14px; }
.other-input { margin-top: 8px; max-width: 420px; }
@media (max-width: 768px) { .el-radio-group { flex-direction: column; gap: 8px; } .other-input { max-width: 100%; } }
</style>