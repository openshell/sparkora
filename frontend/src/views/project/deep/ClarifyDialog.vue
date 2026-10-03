<template>
  <div class="clarify-dialog">
    <!-- 对话历史:逐轮 Q/A,展示收敛过程 -->
    <div v-if="turns.length" class="turn-list">
      <div v-for="(t, i) in turns" :key="t.idx ?? i" class="turn">
        <div class="turn-q"><span class="turn-idx">Q{{ i + 1 }}</span>{{ t.question }}</div>
        <div class="turn-a"><span class="turn-idx a">A</span>{{ t.answer || '（跳过）' }}</div>
      </div>
    </div>

    <!-- 当前问题 -->
    <div v-if="question" class="cur-q">
      <div class="q-text">
        {{ question.text }}
        <el-tag v-if="question.required" size="small" type="danger" effect="plain" round>必答</el-tag>
        <el-tag v-else size="small" type="info" effect="plain" round>可选</el-tag>
      </div>

      <el-input v-if="qType === 'input'"
                v-model="scalarValue"
                class="answer-input"
                maxlength="500"
                :placeholder="question.required ? '请填写（必答，Enter 提交）' : '可留空跳过（Enter 提交）'"
                @keydown.enter="onEnter" />

      <el-radio-group v-else-if="qType === 'single'" v-model="singleValue" class="opt-group">
        <el-radio v-for="(o, j) in options" :key="j" :value="o">{{ o }}</el-radio>
        <el-radio :value="OTHER">{{ OTHER_LABEL }}</el-radio>
      </el-radio-group>

      <el-checkbox-group v-else-if="qType === 'multi'" v-model="multiValue" class="opt-group">
        <el-checkbox v-for="(o, j) in options" :key="j" :value="o">{{ o }}</el-checkbox>
        <el-checkbox :value="OTHER">{{ OTHER_LABEL }}</el-checkbox>
      </el-checkbox-group>

      <!-- 其他(自行填写):single 选中 / multi 勾选时展开文本框 -->
      <el-input v-if="otherVisible"
                v-model="otherText"
                class="answer-input other-input"
                maxlength="500"
                placeholder="请填写具体内容（Enter 提交）"
                @keydown.enter="onEnter" />

      <div class="cur-actions">
        <el-button type="primary" :loading="busy" :disabled="busy" @click="submit">提交（Enter）</el-button>
        <el-button text :disabled="busy" @click="emit('converge')">强制收敛</el-button>
        <el-button text type="info" :disabled="busy" @click="emit('abort')">中止</el-button>
        <span class="key-hint">输入框内回车提交</span>
      </div>
    </div>

    <!-- 收敛进度:必答槽位 + 全部槽位填充情况 -->
    <div class="slots">
      <div class="slots-head">
        <el-icon><ChatDotRound /></el-icon>收敛进度
        <el-tag size="small" :type="requiredDone ? 'success' : 'warning'" effect="plain" round>
          必答 {{ requiredFilled }}/{{ requiredSlots.length }}
        </el-tag>
        <el-tag size="small" effect="plain" round>已填 {{ filledCount }}/{{ slotDefs.length }}</el-tag>
      </div>
      <div class="slot-grid">
        <div v-for="s in slotDefs" :key="s.id" class="slot" :class="{ done: slotMap[s.id], required: s.required }">
          <span class="slot-label">{{ s.label }}</span>
          <span v-if="slotMap[s.id]" class="slot-value">{{ displaySlot(slotMap[s.id]) }}</span>
          <span v-else class="slot-value empty">未填</span>
          <template v-if="slotMap[s.id]">
            <el-tag size="small" effect="plain" round :type="sourceType(slotMap[s.id].source)">{{ sourceLabel(slotMap[s.id].source) }}</el-tag>
            <span class="conf">{{ confBar(slotMap[s.id]) }}</span>
          </template>
        </div>
      </div>
    </div>

    <!-- AI 思考过程(可选,折叠弱化) -->
    <el-collapse v-if="reasoning" class="reasoning">
      <el-collapse-item name="r">
        <template #title><span class="reasoning-title"><el-icon><MagicStick /></el-icon>AI 思考过程</span></template>
        <div class="reasoning-body">{{ reasoning }}</div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ChatDotRound, MagicStick } from '@element-plus/icons-vue'

const props = defineProps({
  question: { type: Object, default: null },     // {id,text,type,options,required,slotId}
  session: { type: Object, default: () => ({}) }, // {turns,slots,status,converged}
  busy: { type: Boolean, default: false },
  reasoning: { type: String, default: '' }
})
const emit = defineEmits(['answer', 'converge', 'abort'])

const OTHER = '__other__'
const OTHER_LABEL = '其他(自行填写)'

// 槽位元信息(与后端 ClarifyConversationService.ALL_SLOTS/SLOT_LABELS/REQUIRED_SLOTS 对齐)
const slotDefs = [
  { id: 'purpose', label: '写作目的', required: true },
  { id: 'audience', label: '目标读者', required: true },
  { id: 'tone', label: '语气风格', required: false },
  { id: 'angles', label: '切入角度', required: false },
  { id: 'mustCover', label: '必须覆盖', required: true },
  { id: 'mustAvoid', label: '必须避免', required: false },
  { id: 'successCriteria', label: '成功标准', required: false },
  { id: 'lengthTarget', label: '目标字数', required: false }
]
const requiredSlots = slotDefs.filter(s => s.required)

const scalarValue = ref('')
const singleValue = ref('')
const multiValue = ref([])
const otherText = ref('')

const turns = computed(() => Array.isArray(props.session?.turns) ? props.session.turns : [])
const qType = computed(() => props.question?.type || 'input')
const options = computed(() => Array.isArray(props.question?.options) ? props.question.options : [])
const otherVisible = computed(() => qType.value === 'single'
  ? singleValue.value === OTHER
  : (qType.value === 'multi' && multiValue.value.includes(OTHER)))

// 槽位填充映射:id → slot
const slotMap = computed(() => {
  const m = {}
  const arr = Array.isArray(props.session?.slots) ? props.session.slots : []
  for (const s of arr) { if (s && s.id) m[s.id] = s }
  return m
})
const filledCount = computed(() => slotDefs.filter(s => slotMap.value[s.id]).length)
const requiredFilled = computed(() => requiredSlots.filter(s => slotMap.value[s.id]).length)
const requiredDone = computed(() => requiredFilled.value === requiredSlots.length)

// 问题切换:清空本地作答态(上一题内容不应残留到下一题)。
// 同时监听回合数——必要槽位硬兜底会重复追问同一 slotId(question.id 相同),
// 仅按 id 判会漏清,导致旧答案残留误提交;turns 增长即视为新题。
watch([() => props.question?.id, () => turns.value.length], () => {
  scalarValue.value = ''
  singleValue.value = ''
  multiValue.value = []
  otherText.value = ''
})

const displaySlot = (s) => {
  const v = s?.value
  if (Array.isArray(v)) return v.join('、')
  return v == null || v === '' ? '未填' : String(v)
}
const sourceLabel = (src) => ({ USER: '用户', PICKED: '选项', DEFAULT: '默认', INFERRED: '推断' }[src] || src || '—')
const sourceType = (src) => ({ USER: 'primary', PICKED: 'success', DEFAULT: 'info', INFERRED: 'warning' }[src] || 'info')
const confBar = (s) => {
  const c = typeof s?.confidence === 'number' ? s.confidence : 0
  return '▮'.repeat(Math.round(c * 5)).padEnd(5, '▯')
}

// 组装答案:multi 以「、」拼接(与后端 parseSlotValues 口径一致);「其他」空文本按未选处理
const buildAnswer = () => {
  if (qType.value === 'multi') {
    const parts = multiValue.value.filter(x => x !== OTHER)
    const other = otherText.value.trim()
    if (multiValue.value.includes(OTHER) && other) parts.push(other)
    return parts.join('、')
  }
  if (qType.value === 'single') {
    return singleValue.value === OTHER ? otherText.value.trim() : (singleValue.value ?? '')
  }
  return scalarValue.value.trim()
}

// Enter 提交(IME 安全):组字中(isComposing / keyCode 229)放行,不加 .prevent 修饰符
const onEnter = (e) => {
  if (e.isComposing || e.keyCode === 229) return
  submit()
}

// 防重入:busy 时忽略提交
const submit = () => {
  if (props.busy) return
  emit('answer', buildAnswer())
}
</script>

<style scoped>
/* PC-only:走 token,无媒体查询/44px 触控目标 */
.clarify-dialog { display: flex; flex-direction: column; gap: var(--sp-4); }
.turn-list { display: flex; flex-direction: column; gap: var(--sp-3); }
.turn { padding: var(--sp-3); border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--el-fill-color-lighter); }
.turn-q { font-size: var(--fs-14); line-height: var(--lh-16); color: var(--ink); font-weight: 600; display: flex; gap: var(--sp-2); }
.turn-a { font-size: var(--fs-14); line-height: var(--lh-16); color: var(--muted); margin-top: var(--sp-1); display: flex; gap: var(--sp-2); }
.turn-idx {
  flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center;
  min-width: 20px; height: 20px; border-radius: var(--radius-xs);
  background: var(--brand-weak); color: var(--brand-strong);
  font-size: var(--fs-12); line-height: var(--lh-12); font-weight: 700;
}
.turn-idx.a { background: var(--el-fill-color-darker); color: var(--muted); }

.cur-q { display: flex; flex-direction: column; gap: var(--sp-3); }
.q-text { font-size: var(--fs-16); line-height: var(--lh-18); font-weight: 600; color: var(--ink); display: flex; align-items: center; gap: var(--sp-2); }
.answer-input { max-width: 60ch; }
.other-input { margin-top: 0; }
.opt-group { display: flex; flex-wrap: wrap; gap: var(--sp-2) var(--sp-5); }
.cur-actions { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }
.key-hint { font-size: var(--fs-12); line-height: var(--lh-12); color: var(--faint); }

.slots { border-top: 1px solid var(--line); padding-top: var(--sp-3); }
.slots-head { display: flex; align-items: center; gap: var(--sp-2); font-weight: 600; font-size: var(--fs-14); line-height: var(--lh-14); margin-bottom: var(--sp-2); }
.slots-head .el-icon { color: var(--brand); }
.slot-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: var(--sp-2) var(--sp-4); }
.slot { display: flex; align-items: center; gap: var(--sp-2); font-size: var(--fs-13); line-height: var(--lh-14); min-width: 0; }
.slot-label { flex-shrink: 0; color: var(--faint); }
.slot.required .slot-label::after { content: ' *'; color: var(--el-color-danger); }
.slot-value { color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; flex: 1; }
.slot-value.empty { color: var(--faint); }
.conf { color: var(--faint); font-size: var(--fs-12); letter-spacing: 2px; flex-shrink: 0; }

.reasoning { border-top: 1px solid var(--line); }
.reasoning-title { display: inline-flex; align-items: center; gap: var(--sp-2); font-weight: 600; font-size: var(--fs-13); }
.reasoning-body { white-space: pre-wrap; word-break: break-word; font-size: var(--fs-13); line-height: var(--lh-18); color: var(--muted); max-height: 300px; overflow-y: auto; }
</style>
