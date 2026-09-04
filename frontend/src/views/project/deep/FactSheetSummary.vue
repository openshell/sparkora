<template>
  <div class="factsum">
    <div class="fs-head"><el-icon><Notebook /></el-icon>事实手册
      <el-tag size="small" effect="plain" round>{{ total }} 条</el-tag>
      <el-tag size="small" type="success" effect="plain" round>高置信 {{ highConf }}</el-tag>
      <el-tag v-if="pending" size="small" type="warning" effect="plain" round>待核实 {{ pending }}</el-tag>
      <el-button size="small" text type="primary" @click="drawer = true">查看全部</el-button>
    </div>
    <el-drawer v-model="drawer" title="事实手册(正文数值唯一来源)" size="85%" style="max-width:680px">
      <div v-for="(e,i) in entries" :key="i" class="entry">
        <div class="e-key">{{ e.key }}</div>
        <div class="e-val" v-if="e.value"><span class="num serif">{{ e.value }}</span>
          <el-tag size="small" :type="srcType(e)" effect="plain" class="src-tag">{{ srcLabel(e) }}</el-tag>
          <span class="conf">{{ confBar(e) }}</span>
        </div>
        <div class="e-claim" v-if="e.claim && e.claim !== e.key">{{ e.claim }}</div>
      </div>
      <div v-if="(gaps||[]).length" class="gaps">
        <div class="g-title">未覆盖(正文用定性表述)</div>
        <div v-for="(g,i) in gaps" :key="i" class="g-item">{{ g }}</div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Notebook } from '@element-plus/icons-vue'

const props = defineProps({ factSheet: { type: [Object, String], required: true } })
const drawer = ref(false)
const parsed = computed(() => typeof props.factSheet === 'string' ? safeParse(props.factSheet) : props.factSheet)
const entries = computed(() => parsed.value?.entries || [])
const gaps = computed(() => parsed.value?.gaps || [])
const total = computed(() => entries.value.length)
const highConf = computed(() => entries.value.filter(e => (e.confidence || 0) >= 0.7).length)
const pending = computed(() => entries.value.filter(e => (e.confidence || 0) < 0.5).length)
const srcType = (e) => {
  const s = e.sources || {}; const t = s.type || 'KB'
  return t === 'WEB' ? 'warning' : t === 'MULTI' ? 'success' : 'primary'
}
const srcLabel = (e) => { const s = e.sources || {}; const t = s.type || 'KB'
  return t === 'WEB' ? 'WEB·' + (s.url || '').replace(/^https?:\/\//, '').split('/')[0] : t === 'MULTI' ? '多源交叉' : '知识库' }
const confBar = (e) => '▮'.repeat(Math.round((e.confidence || 0) * 5)).padEnd(5, '▯')
function safeParse(s) { try { return JSON.parse(s) } catch { return {} } }
</script>

<style scoped>
.fs-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-weight: 600; }
.entry { padding: 8px 0; border-bottom: 1px dashed var(--line); }
.e-key { font-weight: 600; font-size: 13px; }
.e-val { margin-top: 4px; display: flex; align-items: center; gap: 8px; }
.e-val .num { font-size: 16px; }
.conf { color: var(--faint); font-size: 12px; letter-spacing: 2px; }
.e-claim { color: var(--faint); font-size: 12px; margin-top: 2px; }
.gaps { margin-top: 14px; padding-top: 8px; border-top: 1px solid var(--line); }
.g-title { font-weight: 600; font-size: 13px; margin-bottom: 4px; }
@media (max-width: 768px) { .fs-head { flex-wrap: wrap; } }
</style>