<template>
  <div class="task-brief">
    <div class="tb-head">
      <el-icon><Document /></el-icon>意图契约(TaskBrief)
      <span class="tb-hint">澄清收敛产出,下游研究与写作的唯一输入</span>
    </div>
    <div v-if="!hasAny" class="tb-empty">暂无意图契约数据</div>
    <div v-else class="tb-grid">
      <section v-for="def in slotDefs" :key="def.id" class="tb-sec" :class="{ 'is-list': def.list }">
        <div class="tb-label">
          {{ def.label }}
          <el-tag v-if="def.required" size="small" type="danger" effect="plain" round>必要</el-tag>
        </div>
        <template v-if="itemsOf(def.id).length">
          <div v-for="(item, i) in itemsOf(def.id)" :key="i" class="tb-item">
            <span class="tb-value">{{ valueText(item) }}</span>
            <el-tag size="small" effect="plain" round :type="sourceType(item.source)">{{ sourceLabel(item.source) }}</el-tag>
            <span class="tb-conf" :title="`置信度 ${Math.round((item.confidence || 0) * 100)}%`">{{ confBar(item) }}</span>
          </div>
        </template>
        <div v-else class="tb-value empty">未填</div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Document } from '@element-plus/icons-vue'

const props = defineProps({ taskBrief: { type: [Object, String], default: null } })

// 槽位定义(与后端 ClarifyConversationService.SLOT_LABELS / LIST_SLOTS 对齐)
const slotDefs = [
  { id: 'purpose', label: '写作目的', required: true, list: false },
  { id: 'audience', label: '目标读者', required: true, list: false },
  { id: 'tone', label: '语气风格', required: false, list: false },
  { id: 'angles', label: '切入角度', required: false, list: true },
  { id: 'mustCover', label: '必须覆盖', required: true, list: true },
  { id: 'mustAvoid', label: '必须避免', required: false, list: true },
  { id: 'successCriteria', label: '成功标准', required: false, list: false },
  { id: 'lengthTarget', label: '目标字数', required: false, list: false }
]

// 容错解析:字符串→JSON,畸形→空对象(永不抛)
const parsed = computed(() => {
  const tb = props.taskBrief
  if (!tb) return {}
  if (typeof tb === 'string') { try { return JSON.parse(tb) } catch { return {} } }
  return tb
})

// 归一化槽位项:标量 {value,source,confidence} → [item];数组 → 逐项;缺失/空值 → []
const itemsOf = (id) => {
  const v = parsed.value?.[id]
  if (v == null) return []
  const raw = Array.isArray(v) ? v : [v]
  return raw.filter(it => it != null && `${valueText(it)}`.trim() !== '')
}
const valueText = (item) => {
  if (item == null) return ''
  if (typeof item !== 'object') return String(item)
  const v = item.value
  if (Array.isArray(v)) return v.join('、')
  return v == null ? '' : String(v)
}
const sourceLabel = (src) => ({ USER: '用户', PICKED: '选项', DEFAULT: '默认', INFERRED: '推断' }[src] || src || '—')
const sourceType = (src) => ({ USER: 'primary', PICKED: 'success', DEFAULT: 'info', INFERRED: 'warning' }[src] || 'info')
const confBar = (item) => {
  const c = typeof item?.confidence === 'number' ? item.confidence : 0
  return '▮'.repeat(Math.round(c * 5)).padEnd(5, '▯')
}
const hasAny = computed(() => slotDefs.some(d => itemsOf(d.id).length > 0))
</script>

<style scoped>
/* PC-only:token 化,无媒体查询 */
.task-brief { display: flex; flex-direction: column; gap: var(--sp-3); }
.tb-head { display: flex; align-items: center; gap: var(--sp-2); font-weight: 700; font-size: var(--fs-14); line-height: var(--lh-14); color: var(--ink); }
.tb-head .el-icon { color: var(--brand); }
.tb-hint { font-weight: 400; font-size: var(--fs-12); line-height: var(--lh-12); color: var(--faint); }
.tb-empty { color: var(--faint); font-size: var(--fs-13); }
.tb-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: var(--sp-3) var(--sp-5); }
.tb-sec { min-width: 0; }
.tb-label { display: flex; align-items: center; gap: var(--sp-2); font-size: var(--fs-13); line-height: var(--lh-13); font-weight: 700; color: var(--ink); margin-bottom: var(--sp-1); letter-spacing: .03em; }
.tb-item { display: flex; align-items: center; gap: var(--sp-2); font-size: var(--fs-14); line-height: var(--lh-16); color: var(--ink); min-width: 0; }
.tb-value { flex: 1; min-width: 0; }
.tb-value.empty { color: var(--faint); font-size: var(--fs-13); }
.tb-conf { color: var(--faint); font-size: var(--fs-12); letter-spacing: 2px; flex-shrink: 0; }
</style>
