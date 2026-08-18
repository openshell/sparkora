<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <el-button text @click="$router.push('/')">← 返回工作台</el-button>
        <el-tag v-if="project">{{ statusLabel(project.status) }}</el-tag>
      </div>
      <h2 v-if="project">{{ project.topic }}</h2>

      <!-- 六步步骤条 -->
      <el-steps :active="activeStep" finish-status="success" class="steps" simple>
        <el-step title="Brief" />
        <el-step title="版本" />
        <el-step title="校验" />
        <el-step title="配图" />
        <el-step title="预览" />
        <el-step title="发布" />
      </el-steps>

      <!-- Step 1：生成 Brief（S0 仅此步可点） -->
      <el-card class="step-card" shadow="never">
        <template #header><span>Step 1 · 生成创作 Brief</span></template>
        <p class="muted">由 AI 生成标题候选 / 受众 / 核心观点 / 大纲 / 事实风险点，确认后进入多版本生成。</p>
        <el-button type="primary" :loading="generating" @click="onGenerateBrief">生成 Brief</el-button>
      </el-card>

      <el-card v-for="i in 5" :key="i" class="step-card disabled" shadow="never">
        <template #header><span>Step {{ i + 1 }} · {{ ['版本','校验','配图','预览','发布'][i-1] }}（待后续阶段）</span></template>
        <p class="muted">此步骤在后续里程碑实现，S0 暂不可用。</p>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { projectApi } from '../api'
import { ElMessage } from 'element-plus'
import TopBar from '../layouts/TopBar.vue'

const route = useRoute()
const project = ref(null)
const generating = ref(false)

const activeStep = computed(() => {
  const s = project.value?.status
  if (s === 'READY') return 1
  if (s === 'GENERATING_BRIEF') return 0
  return 0
})

const statusLabel = (s) => ({ DRAFT: '草稿', GENERATING_BRIEF: '生成中', READY: '就绪' }[s] || s)

const load = async () => {
  const res = await projectApi.get(route.params.id)
  project.value = res.data
}
const onGenerateBrief = async () => {
  generating.value = true
  try {
    await projectApi.generateBrief(route.params.id)
    ElMessage.success('已置为就绪（S0 占位，S1 起接 AI）')
    await load()
  } finally { generating.value = false }
}
onMounted(load)
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
.steps { margin: 16px 0; }
.step-card { margin-bottom: 12px; }
.step-card.disabled { opacity: .5; }
.muted { color: var(--muted); font-size: 13px; }
@media (max-width: 768px) { .steps :deep(.el-step__title) { font-size: 12px; } }
</style>
