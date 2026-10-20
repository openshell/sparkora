<template>
  <div class="page project-page">
    <!-- 左侧竖排步骤导航(自绘,可点/锁定/当前态) -->
    <aside class="step-rail">
      <button type="button" class="back-btn" @click="$router.push('/')">
        <el-icon :size="14"><ArrowLeft /></el-icon>工作台
      </button>

      <nav v-if="!loadError" class="steps-nav" aria-label="创作步骤">
        <button v-for="(s, i) in STEPS" :key="s.key" type="button"
                class="step-item" :class="stepClass(i)" :disabled="i > maxReachable"
                :title="i > maxReachable ? '完成前置步骤后解锁' : s.title"
                @click="onStepClick(i)">
          <span class="step-mark">
            <el-icon v-if="i < activeStep" :size="12"><Check /></el-icon>
            <el-icon v-else-if="i > maxReachable" :size="12"><Lock /></el-icon>
            <template v-else>{{ i + 1 }}</template>
          </span>
          <span class="step-name">{{ s.title }}</span>
        </button>
      </nav>

      <!-- 状态 tag:从原页头下移至 rail 底部(标题并入上下文条) -->
      <div v-if="project" class="rail-status">
        <!-- 小程序项目不展示公众号状态机语义（其推送结果落在 byd_dynasty_* 字段） -->
        <el-tag v-if="isByd" type="info" effect="light" round>王朝小程序</el-tag>
        <el-tag v-else :type="statusTagType(project.status)" effect="light" round>
          {{ statusLabel(project.status) }}
        </el-tag>
      </div>
    </aside>

    <!-- 步骤内容主区 -->
    <div class="project-main">
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
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useProjectDetailStore } from '../../store/project-detail'
import { usePageHeader } from '../../composables/usePageHeader'
import { statusLabel, statusTagType, activeStepOf, maxReachableStepOf, isGenerating } from '../../constants/project'
import { Check, Lock, WarningFilled, ArrowLeft } from '@element-plus/icons-vue'

// 公众号沿用四步向导；王朝小程序为三步工作台（生成可选 → 编辑 → 推送）
const GZH_STEPS = [
  { key: 'brief', title: '简报', route: 'brief' },
  { key: 'versions', title: '版本', route: 'versions' },
  { key: 'preview', title: '预览', route: 'preview' },
  { key: 'publish', title: '发布', route: 'publish' }
]
const BYD_STEPS = [
  { key: 'byd-generate', title: '生成', route: 'byd-generate' },
  { key: 'byd-editor', title: '编辑', route: 'byd-editor' },
  { key: 'byd-push', title: '推送', route: 'byd-push' }
]
const BYD_ROUTE_NAMES = new Set(BYD_STEPS.map((s) => `project-${s.key}`))

const route = useRoute()
const router = useRouter()
// 数据域唯一数据源:project/brief/versions/styles 全部从 store 读写
const store = useProjectDetailStore()
const project = computed(() => store.project(route.params.id))
const loadError = computed(() => store.projectError(route.params.id))
const loadingProject = ref(false)   // 首次装载中(骨架/禁用入口用)
const loadFailedLabel = '加载失败'  // 失败时标题占位

// 渠道缺省按 gzh（存量项目 / 旧接口无 channel 字段）
const isByd = computed(() => project.value?.channel === 'byd_miniapp')
const STEPS = computed(() => (isByd.value ? BYD_STEPS : GZH_STEPS))

// 当前路由对应的步骤(用于「当前」高亮,与状态推进位置解耦)
const routeStepIndex = computed(() => {
  const name = route.name
  if (isByd.value) {
    const i = BYD_ROUTE_NAMES.has(name) ? BYD_STEPS.findIndex((s) => `project-${s.key}` === name) : 0
    return Math.max(0, i)
  }
  if (name === 'project-versions') return 1
  if (name === 'project-preview') return 2
  if (name === 'project-publish') return 3
  return 0
})

// 步骤推进/可达范围:公众号仍由状态机计算；小程序三步全部可达（推送步内部有 Agent 门禁），
// 「已完成」按当前路由之前的步骤判定（生成可选，不能反过来用状态机推断）。
const activeStep = computed(() => (isByd.value ? routeStepIndex.value : activeStepOf(project.value?.status)))
const maxReachable = computed(() => (isByd.value ? BYD_STEPS.length - 1 : maxReachableStepOf(project.value?.status)))

const stepClass = (i) => ({
  done: i < activeStep.value,
  current: i === routeStepIndex.value,
  locked: i > maxReachable.value
})

// ==== 上下文条(外壳 topbar):面包屑「项目 / #id / 主题」,状态 tag 留在 rail 底部 ====
// 依赖 route.name:步骤间切换时重新申明,避免子步骤清空后无人补写
const header = usePageHeader()
const syncHeader = () => {
  if (!header) return
  const id = route.params.id
  // 小程序项目允许空主题：列表/面包屑用统一兜底，避免出现 "#id / " 的空尾
  const tail = (project.value?.topic || '').trim()
    || (isByd.value ? '未命名小程序文章' : '')
    || (loadError.value ? loadFailedLabel : '')
  header.crumbs = [{ label: '项目' }, { label: `#${id}` }, ...(tail ? [{ label: tail }] : [])]
}
watch([() => route.params.id, () => route.name, project, loadError], syncHeader, { immediate: true })
onBeforeRouteLeave(() => { if (header) header.crumbs = [] })

// 渠道守卫：项目加载完成后，gzh / byd 步骤路由互斥。
// - 小程序项目误入公众号步骤 → 回编辑步（生成步仍可通过 rail 主动进入）
// - 公众号项目误入小程序步骤 → 回落到状态机对应的公众号步骤
const guardChannelRoute = () => {
  const p = project.value
  if (!p) return
  const bydProject = p.channel === 'byd_miniapp'
  const onBydRoute = BYD_ROUTE_NAMES.has(route.name)
  if (bydProject && !onBydRoute) {
    router.replace({ name: 'project-byd-editor', params: { id: route.params.id } })
  } else if (!bydProject && onBydRoute) {
    const idx = Math.min(activeStepOf(p.status), GZH_STEPS.length - 1)
    router.replace({ name: `project-${GZH_STEPS[idx].key}`, params: { id: route.params.id } })
  }
}
watch(
  [() => route.params.id, () => route.name, () => project.value?.channel],
  guardChannelRoute,
  { immediate: true }
)

const loadProject = async () => {
  loadingProject.value = true
  await store.ensureProject(route.params.id, { force: true })
  loadingProject.value = false
  // 小程序工作台不带公众号状态机推进，只做渠道路由纠偏
  if (isByd.value) {
    guardChannelRoute()
    return
  }
  // 中断重进自动定位:若当前路由落后于最新流程节点(如从列表进入默认落在简报页),自动跳到该做的步骤
  const targetStep = activeStepOf(project.value?.status)
  if (routeStepIndex.value < targetStep) {
    const step = GZH_STEPS[targetStep]
    if (step?.route) router.replace({ name: `project-${step.key}`, params: { id: route.params.id } })
  }
}

// 生成中状态轮询收敛到 store(唯一事实源驱动)。store 四层共享的仅 store 四层共用,由状态翻转自动停止
watch(() => [route.params.id, project.value?.status], ([id, status]) => {
  if (!id || !isGenerating(status)) { store.stopPolling(); return }
  store.startPolling(id, { intervalMs: status === 'GENERATING_BRIEF' ? 4000 : 5000 })
}, { immediate: true })

// 服务端已有生成进度的项目留在本页时也保持刷新,切换子路由不重复装载
const onStepClick = (i) => {
  if (i > maxReachable.value) return
  const step = STEPS.value[i]
  if (step?.route) router.push({ name: `project-${step.key}`, params: { id: route.params.id } })
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
/* 整页定高(= 外壳内容区高度):步骤主区内部滚动,rail 状态 tag 贴底 */
.project-page { display: flex; align-items: stretch; gap: var(--sp-7); height: 100%; }

/* 左侧竖排步骤 rail(取代原顶部药丸步骤条) */
.step-rail {
  width: 200px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  padding-right: var(--sp-5);
  border-right: 1px solid var(--line);
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  height: var(--control-h-sm);
  padding: 0 var(--sp-3);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--muted);
  font-size: var(--fs-12);
  cursor: pointer;
  align-self: flex-start;
}
.back-btn:hover { color: var(--brand); border-color: var(--brand); }

.steps-nav { display: flex; flex-direction: column; gap: var(--sp-1); }
.step-item {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  height: var(--control-h-lg);
  padding: 0 var(--sp-4);
  border: none;
  border-left: 2px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--muted);
  font-size: var(--fs-13);
  text-align: left;
  cursor: pointer;
  transition: background .15s ease, color .15s ease, border-color .15s ease;
}
.step-item:not(:disabled):hover { background: var(--n-50); color: var(--ink); }
.step-mark {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 1px solid var(--line-strong);
  border-radius: 50%;
  background: var(--card);
  font-size: var(--fs-11);
  font-weight: 600;
}
.step-name { white-space: nowrap; }
.step-item.done { color: var(--ok); }
.step-item.done .step-mark { border-color: var(--ok); color: var(--ok); }
.step-item.current {
  border-left-color: var(--brand);
  background: var(--brand-weak);
  color: var(--brand-strong);
  font-weight: 600;
}
.step-item.current .step-mark { border-color: var(--brand); background: var(--brand); color: #fff; }
.step-item.locked { opacity: .5; cursor: not-allowed; }
.step-item:disabled { cursor: not-allowed; }

.rail-status { margin-top: auto; padding-top: var(--sp-4); border-top: 1px solid var(--line); }

/* 步骤主区:固定高度内滚动,子步骤可自行撑高(批 2/3 的页面不受影响) */
.project-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}
.top-alert { flex: none; margin: 0 0 var(--sp-4); }

@media (prefers-reduced-motion: reduce) {
  .step-item { transition: none; }
}
</style>
