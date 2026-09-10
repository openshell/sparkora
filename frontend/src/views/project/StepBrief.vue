<template>
  <el-card class="step-card" shadow="never">
    <template #header>
      <span class="card-head">
        <span class="step-title serif">{{ isImitation ? 'Step 1 · 原文分析' : 'Step 1 · 生成创作简报' }}</span>
        <span v-if="brief" class="meta">模型 {{ brief.aiModel }} · {{ brief.tokenUsage }} tokens</span>
        <!-- S6.1:知识库检索状态(随 brief 落库,刷新/重进可见) -->
        <span v-if="brief && brief.ragStatus" class="meta rag-meta">
          <el-tag :type="ragTagType(brief.ragStatus)" size="small" effect="plain" round>知识库 · {{ ragLabel(brief.ragStatus) }}</el-tag>
        </span>
      </span>
    </template>

    <!-- 文章仿写模式(09-09-article-imitation):独立视图(分析中/分析结果+风格推荐/引导语) -->
    <template v-if="isImitation">
      <!-- ① 分析中 -->
      <div v-if="generatingBrief" class="generating">
        <el-skeleton :rows="6" animated />
        <p class="gen-tip">
          <el-icon class="spin"><Loading /></el-icon>
          AI 正在分析原文（题材 / 结构 / 句式）并从风格库推荐匹配风格，通常需要 10~30 秒，请勿关闭页面…
        </p>
      </div>

      <!-- ② 无分析:引导语(含上次失败原因) -->
      <div v-else-if="!imitation" class="muted intro">
        <el-alert v-if="project && project.lastBriefError" type="error" :closable="false" show-icon
                  :title="`上次分析失败：${project.lastBriefError}`" class="brief-alert" />
        <div class="intro-hero">
          <div class="intro-icon"><el-icon :size="30"><MagicStick /></el-icon></div>
          <div class="intro-title serif">分析原文，推荐风格，一键仿写</div>
          <p>AI 将分析参考原文的题材、结构骨架与句式特征，并从风格库推荐最适合仿写这篇的 ≤3 个风格。</p>
          <div class="gen-mode-row">
            <el-button type="primary" :loading="imitationBusy" @click="onAnalyze" size="large">
              <el-icon class="btn-icon"><DataAnalysis /></el-icon>分析原文
            </el-button>
          </div>
          <p v-if="!styles.length" class="form-tip">风格库暂无启用风格：仍可分析原文，但无法获得风格推荐；可先去「风格库」提炼风格再回来。</p>
        </div>
      </div>

      <!-- ③ 分析结果:原文分析卡片 + 风格推荐卡 -->
      <div v-else class="brief">
        <el-alert v-if="project && project.lastBriefError" type="warning" :closable="false" show-icon
                  :title="`上次重新分析失败，以下为当前分析：${project.lastBriefError}`" class="brief-alert" />
        <section class="brief-sec">
          <div class="brief-label"><el-icon><DataAnalysis /></el-icon>原文分析</div>
          <div class="brief-grid">
            <section class="brief-sec panel">
              <div class="brief-label"><el-icon><CollectionTag /></el-icon>题材</div>
              <div class="brief-text">{{ imitation.analysis?.genre || '(未产出)' }}</div>
            </section>
            <section class="brief-sec panel">
              <div class="brief-label"><el-icon><Tickets /></el-icon>结构骨架</div>
              <div class="brief-text">{{ imitation.analysis?.structure || '(未产出)' }}</div>
            </section>
            <section class="brief-sec panel">
              <div class="brief-label"><el-icon><Lightning /></el-icon>句式特征</div>
              <div class="brief-text">{{ imitation.analysis?.sentenceFeatures || '(未产出)' }}</div>
            </section>
          </div>
        </section>

        <section class="brief-sec">
          <div class="brief-label"><el-icon><User /></el-icon>风格推荐
            <span class="label-hint">按匹配度排序，推荐风格在版本生成页高亮标注</span>
          </div>
          <div v-if="!imitation.recommendations.length" class="rec-empty">
            <el-alert type="info" :closable="false" show-icon
                      title="风格库暂无可用推荐"
                      description="风格库为空或没有启用风格，请先到「风格库」页从样文提炼风格，再回来重新分析。" />
          </div>
          <div v-else class="rec-list">
            <div v-for="(r, i) in imitation.recommendations" :key="r.styleId" class="rec-item">
              <div class="rec-head">
                <span class="rec-name serif">{{ r.name }}</span>
                <el-tag size="small" effect="plain" round>匹配度 {{ Math.round((r.matchScore || 0) * 100) }}%</el-tag>
              </div>
              <div class="rec-reason">{{ r.reason }}</div>
            </div>
          </div>
        </section>

        <div class="brief-actions">
          <el-button v-if="canRegenerateBrief" :loading="imitationBusy" @click="onAnalyze">重新分析</el-button>
          <!-- 下一步按状态给出唯一动作:READY 进入版本生成;版本已生成(含已发布)查看版本 -->
          <el-button v-if="canGoVersions" type="success" @click="gotoVersions">进入多版本生成 →</el-button>
          <el-button v-else-if="canViewVersions" type="success" @click="gotoVersions">查看版本 →</el-button>
        </div>
      </div>
    </template>

    <!-- 主题创作模式:既有简报视图(原样保留) -->
    <template v-else>
    <!-- 单一互斥状态机:错误 > 生成中 > 有简报 > 引导语,同一时刻只渲染一个主区 -->
    <!-- ① 简报加载失败(网络抖动/后端重启窗口):可见化 + 重试 -->
    <div v-if="briefError && !generatingBrief" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">简报加载失败</div>
      <div class="state-msg">{{ briefError }}</div>
      <el-button type="primary" plain @click="loadBrief">重试</el-button>
    </div>

    <!-- ② 生成中:以 project.status 为唯一事实源(刷新/切页返回也能恢复),轮询直至状态翻转 -->
    <div v-else-if="generatingBrief" class="generating">
      <el-skeleton :rows="6" animated />
      <p class="gen-tip">
        <el-icon class="spin"><Loading /></el-icon>
        AI 正在生成创作简报（标题候选 / 受众 / 核心观点 / 大纲 / 事实风险点），通常需要 1~2 分钟，请勿关闭页面…
      </p>
    </div>

    <!-- ③ 无简报:引导语(含上次失败原因,若后端记录过);2026-09-09 模式收敛:唯一生成路径为深度流程 -->
    <div v-else-if="!brief" class="muted intro">
      <el-alert v-if="project && project.lastBriefError" type="error" :closable="false" show-icon
                :title="`上次生成失败：${project.lastBriefError}`" class="brief-alert" />
      <!-- 深度流程:计划/澄清表单/进度面板/手册(唯一生成入口) -->
      <template v-if="deepStage !== 'NONE' || deepMode">
        <DeepPlanCard v-if="deepPlan" :plan="deepPlan" />
        <ClarifyForm v-if="deepStage === 'CLARIFYING'" :questions="deepQuestions" @submit="onClarifySubmit" />
        <ClarifyForm v-else-if="deepStage === 'CLARIFIED'" :questions="deepQuestions" :locked="true" :answers="deepAnswers" />
        <ResearchProgress v-if="deepStage === 'RESEARCHING' || deepStage === 'RESEARCH_DONE'" :brief-id="deepBriefId" @done="onResearchDone" />
        <FactSheetSummary v-if="deepStage === 'RESEARCH_DONE'" :fact-sheet="deepFactSheet" />
        <div class="deep-actions">
          <el-button v-if="deepMode && deepStage === 'NONE'" type="primary" :loading="deepBusy" @click="onDeepClarify">生成研究计划</el-button>
          <!-- 研究完成:自动简报已在后台生成;若失败(lastBriefError)可手动重试,也可跳过简报直接写正文 -->
          <el-button v-if="deepStage === 'RESEARCH_DONE' && project && (project.lastBriefError || project.status === 'DRAFT')"
                     type="warning" :loading="deepBusy" @click="onDeepBriefRetry">重新生成简报</el-button>
          <el-button v-if="deepStage === 'RESEARCH_DONE'" :loading="deepBusy" @click="onDeepGenerate">跳过简报,直接生成正文 →</el-button>
        </div>
      </template>
      <template v-else>
      <div class="intro-hero">
        <div class="intro-icon"><el-icon :size="30"><MagicStick /></el-icon></div>
        <div class="intro-title serif">让 AI 先想清楚，再动笔</div>
        <p>AI 先生成研究计划并向你反问补充信息，多代理并行研究后产出标题候选 / 受众 / 核心观点 / 大纲 / 事实风险点，确认后进入版本生成。</p>
        <div class="gen-mode-row">
          <el-button type="primary" :loading="deepBusy" @click="deepMode = true" size="large">
            <el-icon class="btn-icon"><DataAnalysis /></el-icon>开始深度研究
          </el-button>
        </div>
        <p class="form-tip">生成流程已升级为深度模式：先研究后写作，资料来源按系统设置（内部知识库/外部搜索）启用。</p>
      </div>
      </template>
    </div>

    <!-- ④ 简报正文(有数据必渲染;上次失败提示以轻量条幅叠加在内容上方) -->
    <div v-else class="brief">
      <el-alert v-if="project && project.lastBriefError" type="warning" :closable="false" show-icon
                :title="`上次重新生成失败，以下为当前简报：${project.lastBriefError}`" class="brief-alert" />

      <!-- 标题候选:刊头式,可点选(选中后作为版本生成的标题偏好;版本已生成后禁用) -->
      <section class="brief-sec">
        <div class="brief-label"><el-icon><CollectionTag /></el-icon>标题候选
          <span class="label-hint">{{ canPickTitle ? '点选一个作为版本标题偏好' : '版本已生成，标题偏好已锁定' }}</span>
        </div>
        <div class="tag-row">
          <button v-for="(t,i) in brief.titleCandidates" :key="i" type="button"
                  class="title-tag serif" :class="{ picked: t === selectedTitle, disabled: !canPickTitle }"
                  :disabled="!canPickTitle"
                  :title="!canPickTitle ? '版本已生成，标题偏好已锁定' : (t === selectedTitle ? '已选中，点击取消' : '点击选用此标题')"
                  @click="onPickTitle(t)">
            <el-icon v-if="t === selectedTitle" class="pick-check"><Check /></el-icon>{{ t }}
          </button>
        </div>
        <p v-if="selectedTitle" class="pick-tip">已选用「{{ selectedTitle }}」，生成版本时将优先采用此标题。</p>
        <p v-else-if="!canPickTitle" class="pick-tip locked">版本已生成，标题偏好已锁定；如需调整请重新生成版本。</p>
      </section>

      <!-- 受众 + 核心观点:两栏卡片 -->
      <div class="brief-grid">
        <section class="brief-sec panel">
          <div class="brief-label"><el-icon><User /></el-icon>目标读者</div>
          <div class="brief-text">{{ brief.audienceRefine }}</div>
        </section>
        <section class="brief-sec panel">
          <div class="brief-label"><el-icon><Lightning /></el-icon>核心观点</div>
          <ul class="list"><li v-for="(v,i) in brief.coreViewpoints" :key="i">{{ v }}</li></ul>
        </section>
      </div>

      <!-- 大纲:编号章节 -->
      <section class="brief-sec">
        <div class="brief-label"><el-icon><Tickets /></el-icon>大纲</div>
        <div v-for="(o,i) in brief.outline" :key="i" class="outline-item">
          <div class="outline-head serif"><span class="outline-num">{{ i+1 }}</span>{{ o.heading }}</div>
          <ul class="sub-list"><li v-for="(s,j) in o.subPoints" :key="j">{{ s }}</li></ul>
        </div>
      </section>

      <!-- 事实风险点 -->
      <section class="brief-sec">
        <div class="brief-label"><el-icon><Warning /></el-icon>事实风险点</div>
        <div v-for="(r,i) in brief.factRisks" :key="i" class="risk-item">
          <div class="risk-line">
            <el-tag :type="riskType(r.riskLevel)" size="small" class="risk-tag">{{ riskLabel(r.riskLevel) }}</el-tag>
            <div class="risk-claim">{{ r.claim }}</div>
          </div>
          <div class="risk-sug">建议：{{ r.suggestion }}</div>
        </div>
      </section>

      <!-- R3:知识库引用明细(本次简报检索注入 AI 的命中块,可展开核查) -->
      <section class="brief-sec">
        <div class="brief-label"><el-icon><CollectionTag /></el-icon>知识库引用
          <span class="label-hint">{{ citationsHint(brief) }}</span>
        </div>
        <CitationList :citations="brief.ragCitations" :rag-status="brief.ragStatus" :fact-sheet="brief.factSheet" />
      </section>

      <div class="brief-actions">
        <!-- 重新生成(2026-09-09 模式收敛):走深度流程重新研究,不再调快速生成接口 -->
        <el-button v-if="canRegenerateBrief" @click="onRegenerateDeep">重新研究生成</el-button>
        <!-- 下一步按状态给出唯一动作:READY 进入版本生成;版本已生成(含已发布)查看版本 -->
        <el-button v-if="canGoVersions" type="success" @click="gotoVersions">进入多版本生成 →</el-button>
        <el-button v-else-if="canViewVersions" type="success" @click="gotoVersions">查看版本 →</el-button>
      </div>
    </div>
    </template>
  </el-card>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectApi } from '../../api'
import { ElMessage } from 'element-plus'
import { isGeneratingBrief } from '../../constants/project'
import { useProjectDetailStore, parseBrief } from '../../store/project-detail'
import { Loading, MagicStick, CollectionTag, User, Lightning, Tickets, Warning, WarningFilled, Check, DataAnalysis } from '@element-plus/icons-vue'
import DeepPlanCard from './deep/DeepPlanCard.vue'
import ClarifyForm from './deep/ClarifyForm.vue'
import ResearchProgress from './deep/ResearchProgress.vue'
import FactSheetSummary from './deep/FactSheetSummary.vue'
import CitationList from './deep/CitationList.vue'
import http from '../../api/http'

// 数据全部来自 project-detail store(布局层已负责装载与轮询,这里只读 + 触发动作)
const props = defineProps({ project: Object })
const store = useProjectDetailStore()

const route = useRoute()
const router = useRouter()
const brief = computed(() => props.project ? store.brief(route.params.id) : null)
const briefError = computed(() => props.project ? store.briefError(route.params.id) : '')

// S6:简报阶段选定的标题(来自 project.selectedTitle,点选后写回后端)
const selectedTitle = computed(() => props.project?.selectedTitle || '')
// 标题点选只在 READY(简报就绪、版本未生成)可用:版本已生成后点选无意义(不影响已生成版本),锁定
const canPickTitle = computed(() => props.project?.status === 'READY')
const onPickTitle = async (t) => {
  if (!canPickTitle.value) return   // 状态守卫:版本已生成后不再触发接口
  const next = t === selectedTitle.value ? '' : t   // 再点一次取消
  try {
    const res = await projectApi.setSelectedTitle(route.params.id, next)
    if (res.code === 0) {
      await store.ensureProject(route.params.id, { force: true })
      ElMessage.success(next ? '已选用该标题' : '已取消选用')
    } else ElMessage.error(res.msg || '操作失败')
  } catch (e) {
    ElMessage.error('操作失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  }
}

// 生成中状态:以 project.status 为唯一事实源,刷新/切页返回均能恢复视图
const generatingBrief = computed(() => isGeneratingBrief(props.project?.status))

// ==================== 文章仿写(09-09-article-imitation) ====================
const isImitation = computed(() => props.project?.genSource === 'IMITATION')
const imitation = computed(() => props.project ? store.imitation(route.params.id) : null)
const imitationBusy = ref(false)
const styles = computed(() => store.styles(route.params.id))
const onAnalyze = async () => {
  imitationBusy.value = true
  try {
    const res = await projectApi.analyzeImitation(route.params.id)
    if (res.code !== 0) throw new Error(res.msg)
    ElMessage.success('分析完成')
    // 同步 API,无轮询翻转:必须手动刷分析结果与项目详情各一次(状态已翻转 READY,
    // 简报由状态迁移 watch 的兜底回调覆盖,这里不重复 loadBrief)
    await store.ensureImitation(route.params.id, { force: true })
    await store.ensureProject(route.params.id, { force: true })
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e.message || '原文分析失败')
    if (route.params.id) await store.ensureProject(route.params.id, { force: true })
  } finally { imitationBusy.value = false }
}
onMounted(() => {
  if (props.project?.genSource === 'IMITATION' && route.params.id) {
    store.ensureImitation(route.params.id)
    // 空风格库引导提示的数据源:样式库可能只在版本步装载过,这里兜底拉一次(有缓存直出)
    store.ensureStyles(route.params.id)
  }
})
watch(() => props.project, (p) => { if (p?.genSource === 'IMITATION' && route.params.id) store.ensureImitation(route.params.id) })

// 重新生成简报只在 READY 可见:VERSIONS_READY 及之后状态已触发下一步,再生成会把状态机拉回 READY
const canRegenerateBrief = computed(() => props.project?.status === 'READY')

// 进入版本按钮:仅简报就绪(READY)且版本未生成时显示
const canGoVersions = computed(() => props.project?.status === 'READY')
// 查看版本按钮:版本已生成或之后的状态(含已发布草稿箱),简报页提供回看入口
const canViewVersions = computed(() => props.project?.status === 'VERSIONS_READY'
  || props.project?.status === 'PUBLISHED_DRAFT')

const gotoVersions = () => router.push({ name: 'project-versions', params: { id: route.params.id } })

const riskType = (l) => ({ high: 'danger', medium: 'warning', low: 'info' }[l] || 'info')
const riskLabel = (l) => ({ high: '高风险', medium: '中风险', low: '低风险' }[l] || l)

// R3:知识库引用区提示文案(检索状态 + 深度手册来源合并口径)
const citationsHint = (b) => {
  const localN = Array.isArray(b?.ragCitations) ? b.ragCitations.length : 0
  const sheet = typeof b?.factSheet === 'string' ? b.factSheet : ''
  let sheetN = 0
  if (sheet) { try { sheetN = (JSON.parse(sheet).entries || []).length } catch { sheetN = 0 } }
  if (localN + sheetN) return `本次生成引用了 ${localN + sheetN} 条知识来源`
  return { OK: '本次生成未注入知识块', LOW_CONFIDENCE: '低置信已抛弃', FAILED: '检索失败·已降级', NO_KNOWLEDGE: '未引用', DISABLED: '知识库已停用(全局设置),本次未检索本地知识库' }[b?.ragStatus] || ''
}

// S6.1 知识库检索状态文案与标签色;09-09-brief-gen-redesign 增 DISABLED(知识库停用,灰色,不与 NO_KNOWLEDGE 混淆)
const ragLabel = (st) => ({
  OK: '已引用', LOW_CONFIDENCE: '低置信已抛弃', FAILED: '检索失败·已降级', NO_KNOWLEDGE: '未引用', DISABLED: '知识库已停用(全局设置)',
}[st] || st)
const ragTagType = (st) => ({
  OK: 'success', LOW_CONFIDENCE: 'warning', FAILED: 'danger', NO_KNOWLEDGE: 'info', DISABLED: 'info',
}[st] || 'info')

// 重试入口:store 层做并发去重,失败信息落在 store.briefError
const loadBrief = () => store.ensureBrief(route.params.id, { force: true })

// 挂载即装载简报;project 详情由布局层异步加载,挂载时可能尚未就位——
// watch 兜底:project 首次到位时补拉一次(刷新直进页面时必经此路径);
// 生成期间轮询只刷 project 详情,不再 force 重拉 brief(状态翻转回调已覆盖,降噪避免 4~5s 一次的冗余请求)
onMounted(() => {
  store.ensureBrief(route.params.id)
  // 挂载时 project 已就位(热缓存/布局已加载完):立即做深度断点探测(isImitation 判定可靠);
  // 未就位则由下方 watch 首次到位时探测——直接在 onMounted 探测会因 isImitation 恒 false 误发 /deep/status
  if (props.project) { projectSeen = true; probeDeepStatus() }
})
let projectSeen = false   // project 是否已首次就位(首次到位补拉一次)
watch(() => props.project, (p) => {
  if (!p || projectSeen) return
  projectSeen = true
  loadBrief()
  // 深度断点探测延后到 project 就位:isImitation 依赖 props.project,挂载瞬间(冷缓存)恒 false,
  // 此时无法区分仿写/主题创作,须等就位后再判定(见 probeDeepStatus 注释)
  probeDeepStatus()
})
// 状态迁移驱动:仅 GENERATING_BRIEF → READY 翻转时 force 重拉简报(store.startPolling 翻转回调同款语义,此处兜底组件级恢复)
watch(() => props.project?.status, (after, before) => {
  if (before === 'GENERATING_BRIEF' && after === 'READY') loadBrief()
})

// 重新研究生成(2026-09-09 模式收敛):FAST 接口已封死,重新生成走深度流程(重新出研究计划)
const onRegenerateDeep = () => {
  deepStage.value = 'NONE'
  deepMode.value = true
  ElMessage.info('生成流程已升级为深度模式,请重新确认研究计划')
}

// ==================== S9 深度模式 ====================
const deepMode = ref(false)          // 用户点了「深度模式」入口
const deepBusy = ref(false)
const deepBriefId = ref(null)
const deepStage = ref('NONE')        // NONE/CLARIFYING/CLARIFIED/RESEARCHING/RESEARCH_DONE
const deepPlan = ref(null)
const deepQuestions = ref([])
const deepAnswers = ref([])
const deepFactSheet = ref(null)

// 路由意图参数消费(规格 3:gen query 收敛):
// ?gen=deep|DEEP / ?gen=imitation 仅做 query 清理(与 project 无关,顶层执行安全);
// deepMode/genDeepIntent 置位延后到 project 就位后的 probeDeepStatus——isImitation 依赖
// props.project(冷缓存挂载时恒 false),顶层置位会把仿写项目误判为主题创作(误展开深度面板+误发探测)
let genDeepIntent = false   // 本次进入是否带深度意图(驱动直发竞态重查,见 probeDeepStatus)
const hasDeepIntent = route.query.gen === 'DEEP' || route.query.gen === 'deep'
if (hasDeepIntent) {
  router.replace({ query: { ...route.query, gen: undefined } })
  // 仿写项目忽略 deep 意图:不展开深度面板,也不发 /deep/status(判定在 project 就位后)
}
// 文章仿写意图参数 ?gen=imitation(创建页「创建并分析原文」):仅清理 query,
// 分析请求由 ProjectEdit 在创建后直发;详情页以 project.status(GENERATING_BRIEF)为事实源展示进度
else if (route.query.gen === 'imitation') {
  router.replace({ query: { ...route.query, gen: undefined } })
}

/**
 * 深度断点状态恢复(仅非仿写项目,规格 2):
 * 从 /deep/status 恢复 CLARIFYING/RESEARCHING/RESEARCH_DONE 等断点;仿写项目一律不发该请求。
 * 调用时机:project 首次就位后(onMounted 热缓存路径 / watch 兜底路径),此时 isImitation 判定可靠。
 * 直发竞态重查(规格 5,仅 gen=deep 意图进入时):创建页 startDeep(clarify 约 10~30s)尚未落库时
 * 首查返回 NONE——做有界重查,最多 3 次、间隔 2s,任一次返回 DEEP 即恢复;
 * 超限停在引导语(用户可手动点「生成研究计划」)。普通进入仅首查,不多发。
 */
const probeDeepStatus = async () => {
  if (isImitation.value) return   // 仿写项目不探测深度状态(project 已就位,判定可靠)
  if (hasDeepIntent) { deepMode.value = true; genDeepIntent = true }
  try {
    let d = (await http.get(`/projects/${route.params.id}/deep/status`)).data || {}
    if (!genDeepIntent) {
      // 普通进入:首查即恢复,不做重查
      if (d.genMode === 'DEEP') applyDeepStatus(d)
      return
    }
    // 直发竞态场景:项目状态推进到下游(简报已就绪等)则无需恢复,否则 NONE 时有界重查
    const downstream = () => ['READY', 'GENERATING_VERSIONS', 'VERSIONS_READY', 'PUBLISHED_DRAFT']
      .includes(props.project?.status)
    let probe = 0
    while (d.genMode !== 'DEEP' && probe < 3 && !downstream()) {
      probe++
      await new Promise(r => setTimeout(r, 2000))
      d = (await http.get(`/projects/${route.params.id}/deep/status`)).data || {}
    }
    if (d.genMode === 'DEEP') applyDeepStatus(d)
    // 超限仍 NONE:停在引导语,深度面板保留「生成研究计划」按钮供手动重试
  } catch { /* 深度接口异常不影响快速模式 */ }
}

/** 把 /deep/status 返回写入深度面板状态(断点续跑) */
const applyDeepStatus = (d) => {
  deepBriefId.value = d.briefId
  deepStage.value = d.stage || 'NONE'
  deepPlan.value = d.researchPlan ? (typeof d.researchPlan === 'string' ? JSON.parse(d.researchPlan) : d.researchPlan) : null
  deepQuestions.value = d.questions ? (typeof d.questions === 'string' ? JSON.parse(d.questions) : d.questions) : []
  deepAnswers.value = d.answers ? (typeof d.answers === 'string' ? JSON.parse(d.answers) : d.answers) : []
  deepFactSheet.value = d.factSheet || null
}

const onDeepClarify = async () => {
  deepBusy.value = true
  try {
    const res = await http.post(`/projects/${route.params.id}/deep/clarify`,
      { topic: props.project?.topic || props.project?.name, extraInfo: props.project?.extraInfo || '' })
    if (res.code !== 0) throw new Error(res.msg)
    deepBriefId.value = res.data.briefId
    deepPlan.value = res.data.researchPlan ? JSON.parse(res.data.researchPlan) : null
    deepQuestions.value = res.data.questions ? JSON.parse(res.data.questions) : []
    deepStage.value = 'CLARIFYING'
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e.message || '研究计划生成失败') }
  finally { deepBusy.value = false }
}

const onClarifySubmit = async (answers) => {
  deepBusy.value = true
  try {
    const res = await http.post(`/projects/${route.params.id}/deep/clarify-answer`,
      { briefId: deepBriefId.value, answers })
    if (res.code !== 0) throw new Error(res.msg)
    deepAnswers.value = res.data.locked ? JSON.parse(res.data.locked) : []
    deepStage.value = 'CLARIFIED'
    // 锁定即开跑研究
    await onDeepRun()
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '锁定失败') }
  finally { deepBusy.value = false }
}

const onDeepRun = async () => {
  deepBusy.value = true
  deepStage.value = 'RESEARCHING'
  try {
    // run 为异步启动(202 语义):立即返回,后台逐 agent 执行;进度由 ResearchProgress 2s 轮询展示
    const res = await http.post(`/projects/${route.params.id}/deep/run`, { briefId: deepBriefId.value })
    if (res.code !== 0) throw new Error(res.msg)
  } catch (e) { deepStage.value = 'CLARIFIED'; ElMessage.error(e?.response?.data?.msg || e.message || '研究启动失败') }
  finally { deepBusy.value = false }
}

// ResearchProgress 全部 agent 完成时触发:拉手册并切到 RESEARCH_DONE
const onResearchDone = async () => {
  // 无论手册拉取是否成功,都先推进状态(避免停在 RESEARCHING 导致进度面板无限轮询)
  deepStage.value = 'RESEARCH_DONE'
  try {
    const st = await http.get(`/projects/${route.params.id}/deep/status?briefId=${deepBriefId.value}`)
    deepFactSheet.value = st.data?.factSheet || null
    ElMessage.success('研究完成,事实手册已生成;简报正在基于手册自动生成…')
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '拉取事实手册失败') }
  // 自动简报由后端异步触发,项目状态会经 GENERATING_BRIEF→READY,布局层轮询会拉到简报
  await store.ensureProject(route.params.id, { force: true })
}

// 深度简报手动重试(自动生成失败时)
const onDeepBriefRetry = async () => {
  deepBusy.value = true
  try {
    const res = await projectApi.generateDeepBrief(route.params.id, deepBriefId.value)
    if (res.code === 0) ElMessage.success('简报已生成')
    else ElMessage.error(res.msg || '简报生成失败')
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '简报生成失败') }
  finally {
    deepBusy.value = false
    await store.ensureProject(route.params.id, { force: true })
  }
}

const onDeepGenerate = async () => {
  deepBusy.value = true
  try {
    const res = await http.post(`/projects/${route.params.id}/deep/generate`,
      { briefId: deepBriefId.value })
    if (res.code !== 0) throw new Error(res.msg)
    ElMessage.success('深度正文已生成')
    await store.ensureProject(route.params.id, { force: true })
    router.push({ name: 'project-versions', params: { id: route.params.id } })
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '深度写作失败') }
  finally { deepBusy.value = false }
}
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: baseline; width: 100%; gap: 12px; }
.step-title { font-size: 16px; font-weight: 700; }
.card-head .meta { font-size: 12px; color: var(--muted); font-weight: normal; white-space: nowrap; }
.rag-meta { margin-left: 8px; }

.muted { color: var(--muted); font-size: 13px; }
.brief-alert { margin-bottom: 12px; }
.state-error { padding: 36px 16px; }
.btn-icon { margin-right: 2px; }

/* 引导态:居中 hero */
.intro-hero { text-align: center; padding: 28px 12px 8px; }
.intro-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 64px; height: 64px; border-radius: 18px;
  background: var(--brand-gradient); color: #fff;
  margin-bottom: 14px;
  box-shadow: var(--shadow-hover);
}
.intro-title { font-size: 20px; font-weight: 700; color: var(--ink); margin-bottom: 8px; }
.intro-hero p { line-height: 1.7; max-width: 420px; margin: 0 auto 18px; }

.generating { padding: 4px 0; }
.gen-tip { display: flex; align-items: center; gap: 6px; margin: 12px 0 0; font-size: 13px; color: var(--muted); line-height: 1.6; }
.spin { animation: spin 1.2s linear infinite; color: var(--brand); }
@keyframes spin { to { transform: rotate(360deg); } }

.brief-sec { margin-bottom: 18px; }
.brief-label { display: flex; align-items: center; gap: 6px; font-weight: 700; margin-bottom: 8px; font-size: 13px; letter-spacing: .04em; color: var(--ink); }
.brief-label .el-icon { color: var(--brand); }
.label-hint { font-weight: normal; font-size: 12px; color: var(--faint); letter-spacing: 0; }
.brief-text { font-size: 14px; line-height: 1.7; }

/* 受众 + 观点:两栏卡片 */
.brief-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.panel { background: var(--el-fill-color-light); border-radius: var(--radius-sm); padding: 14px 16px; margin-bottom: 18px; }
.panel .brief-label { margin-bottom: 6px; }

.tag-row { display: flex; flex-wrap: wrap; gap: 8px; }
.title-tag {
  display: inline-flex; align-items: center; gap: 6px;
  max-width: 100%; height: auto; white-space: normal !important; word-break: break-word;
  line-height: 1.5; padding: 8px 14px; font-size: 15px;
  border: 1px solid var(--line-strong); border-radius: 8px;
  background: var(--card); color: var(--ink);
  cursor: pointer; text-align: left;
  transition: border-color .2s, color .2s, background .2s, box-shadow .2s;
}
.title-tag:hover { border-color: var(--brand); color: var(--brand); }
.title-tag.picked { border-color: var(--brand); background: var(--brand-weak); color: var(--brand-strong); box-shadow: 0 0 0 2px color-mix(in srgb, var(--brand) 15%, transparent); }
.title-tag.disabled { cursor: not-allowed; opacity: .6; }
.title-tag.disabled:hover { border-color: var(--line-strong); color: var(--ink); }
.pick-check { color: var(--brand-strong); }
.pick-tip { margin: 8px 0 0; font-size: 12px; color: var(--brand-strong); }
.pick-tip.locked { color: var(--faint); }
.list, .sub-list { margin: 0; padding-left: 18px; }
.list li, .sub-list li { font-size: 14px; line-height: 1.8; }

/* 大纲:编号章节 */
.outline-item { margin-bottom: 10px; }
.outline-head { display: flex; align-items: baseline; gap: 10px; font-weight: 700; font-size: 14px; }
.outline-num {
  flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border-radius: 6px;
  background: var(--brand-weak); color: var(--brand-strong);
  font-size: 12px; font-weight: 700;
}
.sub-list { margin-top: 2px; }
.sub-list li { color: var(--muted); font-size: 13px; }

.risk-item { background: var(--el-fill-color-light); border-radius: var(--radius-sm); padding: 10px 12px; margin-bottom: 8px; }
.risk-line { display: flex; align-items: flex-start; gap: 8px; }
.risk-tag { flex-shrink: 0; margin-top: 2px; }
.risk-claim { font-size: 14px; line-height: 1.6; }
.risk-sug { font-size: 12px; color: var(--muted); margin-top: 4px; }
.rec-empty { margin-bottom: 8px; }
.rec-list { display: flex; flex-direction: column; gap: 10px; }
.rec-item { background: var(--el-fill-color-light); border-radius: var(--radius-sm); padding: 12px 14px; }
.rec-head { display: flex; align-items: center; gap: 8px; }
.rec-name { font-weight: 700; font-size: 15px; color: var(--ink); }
.rec-reason { font-size: 13px; color: var(--muted); line-height: 1.6; margin: 6px 0 10px; }
.brief-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }

@media (max-width: 768px) {
  .brief-grid { grid-template-columns: 1fr; }
  .title-tag { width: 100%; }
  .brief-actions .el-button { flex: 1; }
}
</style>
