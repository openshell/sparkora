<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header project-head">
        <div class="head-left">
          <el-button text @click="$router.push('/')">← 返回工作台</el-button>
          <div class="head-title">
            <span class="page-kicker">Project #{{ project?.id }}</span>
            <h2 class="serif">{{ project?.topic || '…' }}</h2>
          </div>
        </div>
        <el-tag v-if="project" :type="statusTagType(project.status)" effect="light" round>
          {{ statusLabel(project.status) }}
        </el-tag>
      </div>

      <el-alert v-if="project && project.lastVersionError" type="warning" :closable="false" show-icon
                :title="`版本生成提示：${project.lastVersionError}`" class="top-alert" />

      <!-- 六步创作向导:编号式步骤导航(自绘,可点/锁定/当前态),移动端横向滑动 -->
      <nav class="steps-nav" aria-label="创作步骤">
        <button v-for="(s, i) in STEPS" :key="s.key" type="button"
                class="step-pill" :class="stepClass(i)" :disabled="i > maxReachable"
                :title="i > maxReachable ? '完成前置步骤后解锁' : s.title"
                @click="onStepClick(i)">
          <span class="step-num">
            <el-icon v-if="i < activeStep" :size="13"><Check /></el-icon>
            <el-icon v-else-if="i > maxReachable" :size="13"><Lock /></el-icon>
            <template v-else>{{ i + 1 }}</template>
          </span>
          <span class="step-name">{{ s.title }}</span>
        </button>
      </nav>

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
import { statusLabel, statusTagType, activeStepOf, maxReachableStepOf } from '../../constants/project'
import { Check, Lock } from '@element-plus/icons-vue'

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

// 步骤推进/可达范围统一由 constants/project.js 计算(生成中停在当前步骤)
const activeStep = computed(() => activeStepOf(project.value?.status))
const maxReachable = computed(() => maxReachableStepOf(project.value?.status))

// 当前路由对应的步骤(用于「当前」高亮,与状态推进位置解耦)
const routeStepIndex = computed(() => {
  const name = route.name
  if (name === 'project-versions') return 1
  return 0
})

const stepClass = (i) => ({
  done: i < activeStep.value,
  current: i === routeStepIndex.value,
  locked: i > maxReachable.value
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
.project-head { align-items: flex-start; }
.head-left { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.head-title .page-kicker { margin-bottom: 2px; }
.head-title h2 { margin: 0; font-size: 24px; line-height: 1.25; }
.top-alert { margin: 0 0 14px; }

/* 步骤导航:编号药丸,完成的打勾、未解锁的上锁 */
.steps-nav {
  display: flex;
  gap: 8px;
  margin: 4px 0 18px;
  padding-bottom: 6px;
  overflow-x: auto;          /* 移动端横向滑动,不再挤压 */
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
}
.steps-nav::-webkit-scrollbar { display: none; }
.step-pill {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 14px 0 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--card);
  color: var(--muted);
  font-size: 13px;
  cursor: pointer;
  transition: border-color .2s, color .2s, background .2s;
}
.step-pill:not(:disabled):hover { border-color: var(--brand); color: var(--brand); }
.step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 1px solid var(--line-strong);
  font-size: 12px;
  font-weight: 600;
  background: var(--paper);
}
.step-pill.done { color: var(--ok); }
.step-pill.done .step-num { border-color: var(--ok); color: var(--ok); }
.step-pill.current { border-color: var(--brand); color: var(--brand-strong); background: var(--brand-weak); font-weight: 600; }
.step-pill.current .step-num { border-color: var(--brand); background: var(--brand); color: #fff; }
.step-pill.locked { opacity: .55; cursor: not-allowed; background: transparent; }

@media (max-width: 768px) {
  .head-title h2 { font-size: 20px; }
  .step-pill { height: 44px; } /* 触控目标 ≥44px */
}
</style>