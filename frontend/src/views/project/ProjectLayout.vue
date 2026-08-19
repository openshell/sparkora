<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <el-button text @click="$router.push('/')">← 返回工作台</el-button>
        <el-tag v-if="project">{{ statusLabel(project.status) }}</el-tag>
      </div>
      <h2 v-if="project">{{ project.topic }}</h2>

      <el-alert v-if="project && project.lastVersionError" type="warning" :closable="false" show-icon
                :title="`版本生成提示：${project.lastVersionError}`" class="top-alert" />

      <!-- 六步步骤条：可点切换子路由；未达步骤置灰 -->
      <el-steps :active="activeStep" finish-status="success" class="steps" simple>
        <el-step v-for="(s, i) in STEPS" :key="s.key" :title="s.title"
                 :class="{ clickable: i <= maxReachable }"
                 @click="onStepClick(i)" />
      </el-steps>

      <!-- 当前步骤内容由子路由渲染 -->
      <router-view :project="project" :reload-project="loadProject" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectApi } from '../../api'
import TopBar from '../../layouts/TopBar.vue'

const STEPS = [
  { key: 'brief', title: '简报', route: 'brief' },
  { key: 'versions', title: '版本', route: 'versions' },
  { key: 'check', title: '校验' },
  { key: 'images', title: '配图' },
  { key: 'preview', title: '预览' },
  { key: 'publish', title: '发布' }
]

const route = useRoute()
const router = useRouter()
const project = ref(null)

const statusLabel = (s) => ({
  DRAFT: '草稿', GENERATING_BRIEF: '简报生成中', READY: '简报就绪',
  GENERATING_VERSIONS: '版本生成中', VERSIONS_READY: '版本就绪'
}[s] || s)

const activeStep = computed(() => {
  const s = project.value?.status
  if (s === 'VERSIONS_READY') return 2
  if (s === 'GENERATING_VERSIONS') return 1
  return s === 'READY' ? 1 : 0
})

// 当前可达的最远步骤（决定哪些步骤可点）
const maxReachable = computed(() => {
  const s = project.value?.status
  if (s === 'VERSIONS_READY' || s === 'GENERATING_VERSIONS') return 1
  return s === 'READY' ? 1 : 0
})

const loadProject = async () => {
  const res = await projectApi.get(route.params.id)
  project.value = res.data
}

const onStepClick = (i) => {
  if (i > maxReachable.value) return
  const step = STEPS[i]
  if (step.route) router.push({ name: `project-${step.key}`, params: { id: route.params.id } })
}

onMounted(loadProject)
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; }
.steps { margin: 16px 0; }
.top-alert { margin: 12px 0; }
.steps :deep(.el-step.clickable) { cursor: pointer; }
@media (max-width: 768px) { .steps :deep(.el-step__title) { font-size: 12px; } }
</style>
