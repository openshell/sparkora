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
      <el-tag size="small" :type="healthView('KB').type" effect="plain">
        KB {{ healthView('KB').text }}
      </el-tag>
      <el-tag size="small" :type="healthView('SEARXNG').type" effect="plain">
        SEARXNG {{ healthView('SEARXNG').text }}
      </el-tag>
      <el-tag size="small" :type="healthView('TAVILY').type" effect="plain">
        Tavily {{ healthView('TAVILY').text }}
      </el-tag>
      <el-tag size="small" type="info" effect="plain">
        策略 {{ strategyText }}
      </el-tag>
    </div>
    <div v-if="fallbackReasons.length" class="fallback-line">
      <span class="fl">降级:</span>
      <span v-for="(f, i) in fallbackReasons" :key="i" class="fl-item">agent{{ f.agentId }}<template v-if="f.provider"> · {{ f.provider }}</template> · {{ reasonText(f.reason) }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { DataAnalysis, Loading } from '@element-plus/icons-vue'
import http from '../../../api/http'

const props = defineProps({ briefId: { type: Number, required: true } })
const emit = defineEmits(['done'])
const route = useRoute()
const agents = ref([])
// 空对象=首次轮询返回前为未知态:不得乐观臆断「全部可用」(接口异常时轮询静默,恒绿会误导)
const toolHealth = ref({})
// 外部搜索策略(09-25):未知态渲染 --,不乐观显示可用
const webStrategy = ref('')
const polling = ref(false)
let timer = null
let doneEmitted = false
let startedAt = Date.now()

const FINISHED = ['DONE', 'FALLBACK', 'FAILED']
const doneCount = computed(() =>
  agents.value.filter(a => FINISHED.includes(a.status)).length)
const allDone = computed(() => agents.value.length > 0
  && agents.value.every(a => FINISHED.includes(a.status)))
const pct = computed(() => agents.value.length === 0 ? 0
  : Math.round(doneCount.value / agents.value.length * 100))
const factCount = (a) => {
  try { const f = typeof a.factsJson === 'string' ? JSON.parse(a.factsJson) : a.factsJson; return (f.facts || []).length } catch { return 0 }
}
const gapCount = (a) => {
  try { const f = typeof a.factsJson === 'string' ? JSON.parse(a.factsJson) : a.factsJson; return (f.gaps || []).length } catch { return 0 }
}
const tagType = (s) => ({ DONE: 'success', FALLBACK: 'warning', FAILED: 'danger', RUNNING: 'warning', PENDING: 'info' }[s] || 'info')
const label = (s) => ({ DONE: '已完成', FALLBACK: '降级完成', FAILED: '失败', RUNNING: '进行中', PENDING: '排队' }[s] || s)

// 工具健康状态码 → 展示;FAILED 文案按工具区分(SEARXNG 已降级 / Tavily 仅调用失败)
const healthView = (tool) => {
  const s = toolHealth.value[tool]
  if (!s) return { type: 'info', text: '--' }   // 未知态(首轮未返回/接口异常)不谎报可用
  if (s === 'DISABLED') return { type: 'info', text: '已停用' }
  if (s === 'UNCONFIGURED') return { type: 'info', text: '未配置' }
  if (s === 'FAILED') return tool === 'SEARXNG'
    ? { type: 'danger', text: '✗ 引擎不可用,已降级' }
    : { type: 'warning', text: '✗ 调用失败' }
  return { type: 'success', text: '✓' }
}

// 策略标签(09-25):后端 webStrategy;未知态 --(不臆断)
const strategyText = computed(() => {
  const s = webStrategy.value
  if (s === 'TAVILY_FIRST') return 'Tavily 优先'
  if (s === 'SEARXNG_FIRST') return 'SearxNG 优先'
  return '--'
})

// 每个 agent 的降级原因(R3 09-26:含 search 层汇总降级 LLM_FALLBACK,以及 attempts 层 provider 降级)
const fallbackReasons = computed(() => {
  const out = []
  const seen = new Set()
  const push = (agentId, provider, reason) => {
    const key = `${agentId}|${provider}|${reason}`
    if (seen.has(key)) return
    seen.add(key)
    out.push({ agentId, provider, reason })
  }
  for (const a of agents.value) {
    const search = a.search || {}
    const attempts = Array.isArray(search.attempts) ? search.attempts : []
    // 汇总降级(search.fallbackReason=LLM_FALLBACK):provider 层 attempts 全部 ok=true 时，
    // attempts 分支捕获不到，必须在此展示。注意：provider 层原因(EMPTY/ERROR/…)由
    // search.fallbackReason 也回传，但其 provider 字段是**成功采信**的 provider，
    // 直接展示会把失败原因错挂到成功 provider 上(如 Tavily 未配置却标成 SearxNG)——
    // 故仅当 attempts 中无同一原因的失败项时才补这条，避免重复且避免错标。
    if (search.fallbackReason
      && !attempts.some(at => at && !at.ok && at.fallbackReason === search.fallbackReason)) {
      push(a.agentId, search.provider || '', search.fallbackReason)
    }
    for (const at of attempts) {
      if (at && !at.ok && at.fallbackReason) push(a.agentId, at.provider, at.fallbackReason)
    }
  }
  return out
})
const reasonText = (r) => ({
  UNCONFIGURED: '未配置已跳过',
  EMPTY: '空结果已降级',
  INVALID_URL: '无有效链接已降级',
  ERROR: '调用异常已降级',
  LLM_FALLBACK: '汇总降级(已用原始条目)',
  DISABLED: '已停用'
}[r] || r)

const finish = () => {
  if (doneEmitted) return
  doneEmitted = true
  clearInterval(timer)
  emit('done')
}

const poll = async () => {
  polling.value = true
  try {
    const res = await http.get(`/projects/${route.params.id}/deep/status?briefId=${props.briefId}`)
    const d = res.data || {}
    if (d.toolHealth) toolHealth.value = d.toolHealth
    if (d.webStrategy) webStrategy.value = d.webStrategy
    if (d.agents) {
      const arr = typeof d.agents === 'string' ? JSON.parse(d.agents) : d.agents
      agents.value = arr.map(a => ({ ...a, status: a.status || 'PENDING' }))   // 保留后端真实状态
    }
    // 兜底:后端 stage 已是 RESEARCH_DONE(研究完成)也触发 done,不依赖 agent 状态
    if (d.stage === 'RESEARCH_DONE') finish()
  } catch { /* 轮询失败静默,下次再试 */ }
  finally { polling.value = false }
}
// 全部完成 → 通知父组件(仅一次),并停止轮询
watch(allDone, (v) => { if (v) finish() })

onMounted(() => {
  poll()
  timer = setInterval(poll, 2000)   // 2s 轮询逐 agent 进度
  // 超时兜底:5 分钟仍未完成(任何异常导致 agent 状态卡住)强制 done,避免无限轮询
  setTimeout(() => { if (!doneEmitted) finish() }, 5 * 60 * 1000)
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
.fallback-line { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 6px; font-size: 12px; color: var(--faint); }
.fl-item { padding: 1px 6px; border: 1px dashed var(--line); border-radius: 4px; }
.spin-hint { color: var(--faint); font-size: 12px; }
.spin { animation: r 1s linear infinite; }
@keyframes r { to { transform: rotate(360deg); } }
@media (max-width: 768px) { .agent-grid { grid-template-columns: 1fr; } .el-button { min-height: 44px; } }
</style>