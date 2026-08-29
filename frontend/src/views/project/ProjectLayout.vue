<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header project-head">
        <div class="head-left">
          <el-button text @click="$router.push('/')">← 返回工作台</el-button>
          <div class="head-title">
            <span class="page-kicker">{{ project ? `Project #${project.id}` : 'Project' }}</span>
            <h2 class="serif">{{ project?.topic || loadFailedLabel }}</h2>
          </div>
        </div>
        <el-tag v-if="project" :type="statusTagType(project.status)" effect="light" round>
          {{ statusLabel(project.status) }}
        </el-tag>
      </div>

      <!-- 项目详情加载失败:可见化 + 重试(此前静默会卡死步骤导航) -->
      <div v-if="loadError" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">项目详情加载失败</div>
        <div class="state-msg">{{ loadError }}</div>
        <el-button type="primary" plain @click="loadProject">重试</el-button>
      </div>

      <template v-else>
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

        <!-- 当前步骤内容由子路由渲染;渲染层异常时以错误卡片替代,不再整片空白 -->
        <div v-if="captureError" class="state-error">
          <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
          <div class="state-title">步骤内容渲染异常</div>
          <div class="state-msg">{{ captureError }}</div>
          <el-button type="primary" plain @click="retryRender">重试</el-button>
        </div>
        <router-view v-else :project="project" />
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onErrorCaptured, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TopBar from '../../layouts/TopBar.vue'
import { useProjectDetailStore } from '../../store/project-detail'
import { statusLabel, statusTagType, activeStepOf, maxReachableStepOf, isGenerating } from '../../constants/project'
import { Check, Lock, WarningFilled } from '@element-plus/icons-vue'

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
// 数据域唯一数据源:project/brief/versions/styles 全部从 store 读写
const store = useProjectDetailStore()
const project = computed(() => store.project(route.params.id))
const loadError = computed(() => store.projectError(route.params.id))
const loadingProject = ref(false)   // 首次装载中(骨架/禁用入口用)
const loadFailedLabel = '加载失败'  // 失败时标题占位

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
  loadingProject.value = true
  await store.ensureProject(route.params.id, { force: true })
  loadingProject.value = false
}

// 生成中状态轮询收敛到 store(唯一事实源驱动)。store 四层共享的仅 store 四层共用,由状态翻转自动停止
watch(() => [route.params.id, project.value?.status], ([id, status]) => {
  if (!id || !isGenerating(status)) { store.stopPolling(); return }
  store.startPolling(id, { intervalMs: status === 'GENERATING_BRIEF' ? 4000 : 5000 })
}, { immediate: true })

// 服务端已有生成进度的项目留在本页时也保持刷新,切换子路由不重复装载
const onStepClick = (i) => {
  if (i > maxReachable.value) return
  const step = STEPS[i]
  if (step.route) router.push({ name: `project-${step.key}`, params: { id: route.params.id } })
}

// 渲染层兜底:子树(步骤子路由)抛错时显示错误卡片而非整片空白,提供重试(重建子组件)
const captureError = ref('')
onErrorCaptured((err) => {
  captureError.value = err?.message || String(err)
  console.error('[sparkora] 项目详情子树异常:', err)
  return false   // 阻止继续向全局 errorHandler 传播,页面保持框架可见
})
const retryRender = () => { captureError.value = '' }

onMounted(loadProject)

// 离开项目详情(换项目或去其他页面):停掉轮询;换项目由新路由重新装载
onUnmounted(() => store.stopPolling())
</script>

<style scoped>
.project-head { align-items: flex-start; }
.head-left { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.head-title .page-kicker { margin-bottom: 2px; }
.head-title h2 { margin: 0; font-size: 24px; line-height: 1.25; }
.top-alert { margin: 0 0 14px; }
.state-error { padding: 36px 16px; }

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