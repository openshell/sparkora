<template>
  <div class="progress">
    <div class="progress-head"><el-icon><DataAnalysis /></el-icon>研究进度
      <el-tag size="small" :type="allDone ? 'success' : 'warning'" effect="plain" round>
        {{ doneCount }}/{{ agents.length }}
      </el-tag>
      <span v-if="polling" class="spin-hint"><el-icon class="spin"><Loading /></el-icon>轮询中…</span>
    </div>
    <el-progress :percentage="pct" :status="allDone ? 'success' : undefined" :stroke-width="8" />
    <div class="agent-grid">
      <el-card v-for="a in agents" :key="a.agentId" shadow="never" class="agent-card">
        <div class="a-head">
          <span class="a-q">{{ a.question }}</span>
          <el-tag size="small" :type="tagType(a.status)" effect="plain" round>{{ label(a.status) }}</el-tag>
        </div>
        <div class="a-meta" v-if="a.status === 'DONE' || a.status === 'FALLBACK'">
          命中 {{ factCount(a) }} 条<span v-if="a.webCount > 0">(WEB {{ a.webCount }})</span>· 缺口 {{ gapCount(a) }} 条
        </div>
        <div class="a-meta err" v-if="a.status === 'FAILED'">子代理失败,缺口已计入手册</div>
      </el-card>
    </div>
    <div class="tool-health">
      <span class="tl">工具:</span>
      <el-tag size="small" type="success" effect="plain">KB ✓</el-tag>
      <el-tag v-if="webEnabled" size="small" :type="toolHealth.SEARXNG ? 'success' : 'danger'" effect="plain">
        SEARXNG {{ toolHealth.SEARXNG ? '✓' : '✗ 引擎不可用,已降级' }}
      </el-tag>
      <el-tag v-if="webEnabled" size="small" :type="toolHealth.TAVILY ? 'success' : 'info'" effect="plain">
        Tavily {{ toolHealth.TAVILY ? '✓' : '未配置' }}
      </el-tag>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { DataAnalysis, Loading } from '@element-plus/icons-vue'
import http from '../../../api/http'

const props = defineProps({ briefId: { type: Number, required: true } })
const emit = defineEmits(['done'])
const agents = ref([])
const toolHealth = ref({ SEARXNG: false, TAVILY: false })
const webEnabled = ref(true)
const polling = ref(false)
let timer = null
let doneEmitted = false

const allDone = computed(() => agents.value.length > 0
  && agents.value.every(a => a.status === 'DONE' || a.status === 'FALLBACK' || a.status === 'FAILED'))
const pct = computed(() => agents.value.length === 0 ? 0
  : Math.round(agents.value.filter(a => a.status === 'DONE' || a.status === 'FALLBACK' || a.status === 'FAILED').length / agents.value.length * 100))
const factCount = (a) => {
  try { const f = typeof a.factsJson === 'string' ? JSON.parse(a.factsJson) : a.factsJson; return (f.facts || []).length } catch { return 0 }
}
const gapCount = (a) => {
  try { const f = typeof a.factsJson === 'string' ? JSON.parse(a.factsJson) : a.factsJson; return (f.gaps || []).length } catch { return 0 }
}
const tagType = (s) => ({ DONE: 'success', FALLBACK: 'warning', FAILED: 'danger', RUNNING: 'warning', PENDING: 'info' }[s] || 'info')
const label = (s) => ({ DONE: '已完成', FALLBACK: '降级完成', FAILED: '失败', RUNNING: '进行中', PENDING: '排队' }[s] || s)

const poll = async () => {
  polling.value = true
  try {
    const res = await http.get(`/projects/${routeId()}/deep/status?briefId=${props.briefId}`)
    const d = res.data || {}
    if (d.agents) {
      const arr = typeof d.agents === 'string' ? JSON.parse(d.agents) : d.agents
      agents.value = arr.map(a => ({ ...a, status: a.status || 'PENDING' }))   // 保留后端真实状态
      toolHealth.value = d.toolHealth || toolHealth.value
    }
  } catch { /* 轮询失败静默,下次再试 */ }
  finally { polling.value = false }
}
const routeId = () => window.location.pathname.split('/')[2]

// 全部完成 → 通知父组件(仅一次),并停止轮询
watch(allDone, (v) => {
  if (v && !doneEmitted) {
    doneEmitted = true
    clearInterval(timer)
    emit('done')
  }
})

onMounted(() => {
  poll()
  timer = setInterval(poll, 2000)   // 2s 轮询逐 agent 进度
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.progress-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; font-weight: 600; }
.agent-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; margin: 12px 0; }
.a-head { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; }
.a-q { font-size: 13px; font-weight: 600; }
.a-meta { color: var(--faint); font-size: 12px; margin-top: 6px; }
.a-meta.err { color: var(--el-color-danger); }
.tool-health { display: flex; gap: 8px; align-items: center; margin-top: 6px; flex-wrap: wrap; }
.spin-hint { color: var(--faint); font-size: 12px; }
.spin { animation: r 1s linear infinite; }
@keyframes r { to { transform: rotate(360deg); } }
@media (max-width: 768px) { .agent-grid { grid-template-columns: 1fr; } .el-button { min-height: 44px; } }
</style>