<template>
  <div class="step-body brief-step">
    <!-- 「Step 1 · 简报 / 原文分析」标题与下一步动作已并入外壳上下文条(见 script syncHeader);
         原 el-card 头部的元信息下沉为下方属性条,页面回到全幅无卡片形态 -->
    <div v-if="brief" class="prop-strip">
      <div class="prop">
        <span class="prop-k">模型</span>
        <span class="prop-v">{{ brief.aiModel || '—' }}</span>
      </div>
      <div class="prop">
        <span class="prop-k">词元</span>
        <span class="prop-v">{{ brief.tokenUsage || '—' }}</span>
      </div>
      <!-- S6.1:知识库检索状态(随 brief 落库,刷新/重进可见) -->
      <div class="prop">
        <span class="prop-k">知识库</span>
        <el-tag v-if="brief.ragStatus" :type="ragTagType(brief.ragStatus)" size="small" effect="plain" round>
          {{ ragLabel(brief.ragStatus) }}
        </el-tag>
        <span v-else class="prop-v">—</span>
      </div>
      <div class="prop">
        <span class="prop-k">标题偏好</span>
        <span class="prop-v" :class="{ 'is-muted': !selectedTitle }">{{ selectedTitle || '未选用' }}</span>
      </div>
      <div class="prop">
        <span class="prop-k">风险点</span>
        <span class="prop-v">{{ riskSummary(brief) }}</span>
      </div>
    </div>

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
          <div class="intro-title">分析原文，推荐风格，一键仿写</div>
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
                <span class="rec-name">{{ r.name }}</span>
                <el-tag size="small" effect="plain" round>匹配度 {{ Math.round((r.matchScore || 0) * 100) }}%</el-tag>
              </div>
              <div class="rec-reason">{{ r.reason }}</div>
            </div>
          </div>
        </section>

        <!-- 下一步动作(重新分析 / 进入多版本生成 / 查看版本)已上移至上下文条 actions,
             逻辑与显隐条件逐字不变(canRegenerateBrief / canGoVersions / canViewVersions) -->
      </div>
    </template>

    <!-- 主题创作模式:单一 deepStage 状态机驱动(09-11 收敛:同一状态恒渲染同一 UI,与进入路径无关) -->
    <template v-else>
    <!-- ① 简报加载失败(网络抖动/后端重启窗口):可见化 + 重试 -->
    <div v-if="briefError && !generatingBrief && !brief" class="state-error">
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
        AI 正在生成写作蓝图（基于事实手册与意图契约的论证结构与证据绑定），通常需要 1~2 分钟，请勿关闭页面…
      </p>
    </div>

    <!-- ③ 简报正文(有数据且深度流程未活跃必渲染;上次失败提示以轻量条幅叠加在内容上方) -->
    <div v-else-if="brief && !deepActive" class="brief">
      <el-alert v-if="project && project.lastBriefError" type="warning" :closable="false" show-icon
                :title="`上次重新生成失败，以下为当前简报：${project.lastBriefError}`" class="brief-alert" />

      <!-- 标题候选:刊头式,可点选(选中后作为版本生成的标题偏好;版本已生成后禁用) -->
      <section class="brief-sec">
        <div class="brief-label"><el-icon><CollectionTag /></el-icon>标题候选
          <span class="label-hint">{{ canPickTitle ? '点选一个作为版本标题偏好' : '版本已生成，标题偏好已锁定' }}</span>
        </div>
        <div class="tag-row">
          <button v-for="(t,i) in brief.titleCandidates" :key="i" type="button"
                  class="title-tag" :class="{ picked: t === selectedTitle, disabled: !canPickTitle }"
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

      <!-- 大纲 + 事实风险点:宽屏并排(全幅利用横向空间,窄栏时 grid 自动收敛为单列) -->
      <div class="brief-cols">
        <!-- 大纲:编号章节 -->
        <section class="brief-sec">
          <div class="brief-label"><el-icon><Tickets /></el-icon>大纲</div>
          <div v-for="(o,i) in brief.outline" :key="i" class="outline-item">
            <div class="outline-head"><span class="outline-num">{{ i+1 }}</span>{{ o.heading }}</div>
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
      </div>

      <!-- R3:知识库引用明细(本次简报检索注入 AI 的命中块,可展开核查) -->
      <section class="brief-sec">
        <div class="brief-label"><el-icon><CollectionTag /></el-icon>知识库引用
          <span class="label-hint">{{ citationsHint(brief) }}</span>
        </div>
        <CitationList :citations="brief.ragCitations" :rag-status="brief.ragStatus" :fact-sheet="brief.factSheet" />
      </section>

      <!-- 10-02:研究计划 + AI 思考过程(澄清阶段 reasoning)。完成后 deepActive 转 false 切到本分支,
           若此处不渲染,思考过程面板会在简报就绪后消失(用户「事后一次性展示」诉求落空)。 -->
      <DeepPlanCard v-if="deepPlan" :plan="deepPlan" :reasoning="deepReasoning" />

      <!-- 下一步动作(重新研究生成 / 进入多版本生成 / 查看版本)已上移至上下文条 actions,
           逻辑与显隐条件逐字不变;此处不再重复渲染按钮 -->
    </div>

    <!-- ④ 无简报区间(或重启流程中):唯一 deepStage 状态机,同一状态恒渲染同一 UI -->
    <div v-else class="muted intro">
      <el-alert v-if="project && project.lastBriefError" type="error" :closable="false" show-icon
                :title="`上次生成失败：${project.lastBriefError}`" class="brief-alert" />

      <!-- 计划生成中:plan 为同步调用,此态为过渡反馈(毫秒级) -->
      <div v-if="deepStage === 'PLANNING'" class="generating">
        <el-skeleton :rows="5" animated />
        <p class="gen-tip">
          <el-icon class="spin"><Loading /></el-icon>
          研究计划生成中（AI 正在拆解研究问题与数据需求），通常需要 10~30 秒，完成后将自动开跑多代理研究…
        </p>
      </div>

      <!-- 澄清对话:逐轮问答 + 收敛进度 -->
      <template v-else-if="deepStage === 'ASKING'">
        <DeepPlanCard v-if="deepPlan" :plan="deepPlan" :reasoning="deepReasoning" />
        <ClarifyDialog :question="deepQuestion" :session="deepSession" :busy="deepBusy"
                       :reasoning="deepReasoning"
                       @answer="onClarifyAnswer" @converge="onClarifyConverge" @abort="onClarifyAbort" />
      </template>

      <!-- 澄清收敛:展示意图契约,待「开始研究」 -->
      <template v-else-if="deepStage === 'CONVERGED'">
        <TaskBriefCard :task-brief="deepTaskBrief" />
        <div class="gen-mode-row">
          <el-button type="primary" :loading="deepBusy" @click="onStartResearch" size="large">
            <el-icon class="btn-icon"><DataAnalysis /></el-icon>开始研究 →
          </el-button>
        </div>
      </template>

      <!-- 研究中 / 研究完成:进度面板 + 事实手册 -->
      <template v-else-if="deepStage === 'RESEARCHING' || deepStage === 'RESEARCH_DONE'">
        <DeepPlanCard v-if="deepPlan" :plan="deepPlan" :reasoning="deepReasoning" />
        <ResearchProgress :brief-id="deepBriefId" @done="onResearchDone" />
        <FactSheetSummary v-if="deepStage === 'RESEARCH_DONE'" :fact-sheet="deepFactSheet" />
      </template>

      <!-- 蓝图评审:结构化展示/编辑/确认,确认后解锁写作 -->
      <template v-else-if="deepStage === 'BLUEPRINT_REVIEW'">
        <DeepPlanCard v-if="deepPlan" :plan="deepPlan" :reasoning="deepReasoning" />
        <FactSheetSummary v-if="deepFactSheet" :fact-sheet="deepFactSheet" />
        <BlueprintReview :blueprint="deepBlueprint" :status="deepBlueprintStatus" :busy="deepBusy"
                         @confirm="onBlueprintConfirm" @regenerate="onBlueprintRegenerate" @next="onDeepGenerate" />
      </template>

      <!-- 引导页(唯一主操作「开始深度研究」;失败原因由上方 alert 展示,点击即重试) -->
      <div v-else class="intro-hero">
        <div class="intro-icon"><el-icon :size="30"><MagicStick /></el-icon></div>
        <div class="intro-title">让 AI 先想清楚，再动笔</div>
        <p>AI 先与你逐轮澄清写作意图，收敛为意图契约后生成研究计划并多代理并行研究；事实手册就绪后自动产出写作蓝图，评审确认后进入版本生成。</p>
        <div class="gen-mode-row">
          <el-button type="primary" :loading="deepBusy" @click="startClarify" size="large">
            <el-icon class="btn-icon"><DataAnalysis /></el-icon>{{ project && project.lastBriefError ? '重试生成研究计划' : '开始深度研究' }}
          </el-button>
        </div>
        <p class="form-tip">生成流程为深度模式：先澄清意图，再研究，最后评审蓝图后写作；资料来源按系统设置（内部知识库/外部搜索）启用。</p>
      </div>
    </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { projectApi } from '../../api'
import { ElMessage } from 'element-plus'
import { isGeneratingBrief } from '../../constants/project'
import { useProjectDetailStore } from '../../store/project-detail'
import { Loading, MagicStick, CollectionTag, User, Lightning, Tickets, Warning, WarningFilled, Check, DataAnalysis } from '@element-plus/icons-vue'
import DeepPlanCard from './deep/DeepPlanCard.vue'
import ClarifyDialog from './deep/ClarifyDialog.vue'
import TaskBriefCard from './deep/TaskBriefCard.vue'
import BlueprintReview from './deep/BlueprintReview.vue'
import ResearchProgress from './deep/ResearchProgress.vue'
import FactSheetSummary from './deep/FactSheetSummary.vue'
import CitationList from './deep/CitationList.vue'
import http from '../../api/http'
import { usePageHeader } from '../../composables/usePageHeader'

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
    // 09-27-gen-async 异步化:接口毫秒级返回占位标记,分析由后台执行;
    // 状态已置 GENERATING_BRIEF → 布局层 watch(status) 自动 startPolling,
    // READY 翻转时由 store 统一 ensureBrief + ensureImitation(IMITATION 分支),这里无需手动刷新。
    ElMessage.success('已开始分析原文')
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

// ==================== S9 深度模式(10-03-gen-cognitive-redesign C5:对话澄清→研究→蓝图评审) ====================
const deepBusy = ref(false)
const deepBriefId = ref(null)
// NONE/ASKING/CONVERGED/PLANNING/RESEARCHING/RESEARCH_DONE/BLUEPRINT_REVIEW
const deepStage = ref('NONE')
const deepSession = ref(null)         // C1 澄清会话 {status,turns,slots,currentQuestion}
const deepQuestion = ref(null)        // 当前待答问题(收敛后 null)
const deepTaskBrief = ref(null)       // C1 收敛产出意图契约
const deepPlan = ref(null)
const deepFactSheet = ref(null)
const deepBlueprint = ref(null)       // C3 写作蓝图
const deepBlueprintStatus = ref('')   // REVIEWING|CONFIRMED
const deepBlueprintQuality = ref(null)
const deepReasoning = ref('')         // 10-02:澄清阶段 AI 思考过程(reasoning),缺失时隐藏面板
// 文章仿写意图参数 ?gen=imitation(创建页「创建并分析原文」):仅清理 query(仿写交互不变);
// 分析请求由 ProjectEdit 在创建后直发,详情页以 project.status(GENERATING_BRIEF)为事实源展示进度
if (route.query.gen === 'imitation') {
  router.replace({ query: { ...route.query, gen: undefined } })
}
// 「重新研究生成」:旧 brief 仍在 currentBriefId 上,重启期间须跳过「简报正文」分支优先走深度流程;
// 记下重启前的 currentBriefId,新简报落库(currentBriefId 变化)后退出重启态
const restarting = ref(false)
let restartingFromBriefId = null
let blueprintTimer = null             // 蓝图出现竞态的有界轮询
let blueprintPollStart = 0

// 深拷贝解析辅助:对象/JSON 字符串统一转对象,畸形返回 fallback(永不抛)
const asObj = (v, fallback = null) => {
  if (v == null) return fallback
  if (typeof v === 'string') { try { return JSON.parse(v) } catch { return fallback } }
  return v
}

// 深度流程是否活跃(决定「有旧简报」时渲染简报正文还是深度流程):
// ASKING/CONVERGED/PLANNING/RESEARCHING/BLUEPRINT_REVIEW 恒活跃;RESEARCH_DONE 仅在没有简报时活跃;
// restarting 期间恒活跃(新简报尚未落库,优先展示重启流程)
const deepActive = computed(() => {
  if (restarting.value) return true
  const s = deepStage.value
  if (s === 'ASKING' || s === 'CONVERGED' || s === 'PLANNING'
      || s === 'RESEARCHING' || s === 'BLUEPRINT_REVIEW') return true
  return s === 'RESEARCH_DONE' && !brief.value
})

const stopBlueprintPoll = () => { if (blueprintTimer) { clearInterval(blueprintTimer); blueprintTimer = null } }

/** 把 /deep/status 返回写入深度面板状态(断点续跑) */
const applyDeepStatus = (d) => {
  deepBriefId.value = d.briefId
  deepSession.value = asObj(d.clarifySession, null)
  deepQuestion.value = deepSession.value?.currentQuestion || null
  deepTaskBrief.value = asObj(d.taskBrief, null)
  deepPlan.value = asObj(d.researchPlan, null)
  deepFactSheet.value = d.factSheet || null
  deepBlueprint.value = asObj(d.writingBlueprint, null)
  deepBlueprintStatus.value = d.blueprintStatus || ''
  deepBlueprintQuality.value = asObj(d.blueprintQuality, null)
  // 推理透出:研究阶段 planReasoning 优先;澄清阶段回退 session.reasoning(C1 逐轮思考)
  deepReasoning.value = d.planReasoning || deepSession.value?.reasoning || ''
  // 阶段以扩展后的后端 stage 为准;蓝图为竞态兜底(阶段字段可能滞后一拍)
  const bs = deepBlueprintStatus.value
  if (d.stage === 'BLUEPRINT_REVIEW' || bs === 'REVIEWING' || bs === 'CONFIRMED') deepStage.value = 'BLUEPRINT_REVIEW'
  else if (d.stage === 'ASKING') deepStage.value = 'ASKING'
  else if (d.stage === 'CONVERGED') deepStage.value = 'CONVERGED'
  else if (d.stage === 'PLANNING') deepStage.value = 'PLANNING'
  else if (d.stage === 'RESEARCHING') deepStage.value = 'RESEARCHING'
  else if (d.stage === 'RESEARCH_DONE') deepStage.value = 'RESEARCH_DONE'
  else deepStage.value = 'NONE'
}

/**
 * 蓝图出现竞态的有界轮询:研究完成后后端自动生成蓝图(异步,状态经 GENERATING_BRIEF→READY)。
 * 每 2.5s 拉 /deep/status,直到 writingBlueprint 出现(→ BLUEPRINT_REVIEW)或超时(~3min)。
 */
const startBlueprintPoll = () => {
  stopBlueprintPoll()
  blueprintPollStart = Date.now()
  blueprintTimer = setInterval(async () => {
    if (Date.now() - blueprintPollStart > 3 * 60 * 1000) { stopBlueprintPoll(); return }
    try {
      const d = (await http.get(`/projects/${route.params.id}/deep/status?briefId=${deepBriefId.value}`)).data || {}
      if (d.writingBlueprint) {
        applyDeepStatus(d)
        stopBlueprintPoll()
        await store.ensureProject(route.params.id, { force: true })
      }
    } catch { /* 轮询失败静默,下次再试 */ }
  }, 2500)
}

/**
 * 深度断点状态恢复(仅非仿写项目):project 就位后单次拉 /deep/status,
 * 恢复 ASKING/CONVERGED/PLANNING/RESEARCHING/RESEARCH_DONE/BLUEPRINT_REVIEW。
 * /deep/plan 已同步,无需 PLANNING 轮询;蓝图竞态由有界轮询兜底。
 */
const syncDeepStatus = async () => {
  if (isImitation.value) return   // 仿写项目不发深度接口
  try {
    const d = (await http.get(`/projects/${route.params.id}/deep/status`)).data || {}
    if (d.genMode !== 'DEEP' || !d.stage || d.stage === 'NONE') {
      deepStage.value = 'NONE'
      stopBlueprintPoll()
      return
    }
    applyDeepStatus(d)
    // 研究进行中/已完成但蓝图尚未出现:启动竞态轮询
    if ((deepStage.value === 'RESEARCHING' || deepStage.value === 'RESEARCH_DONE') && !deepBlueprint.value) {
      startBlueprintPoll()
    } else stopBlueprintPoll()
  } catch { /* 深度接口异常不影响引导页 */ }
}

/** C1 启动澄清对话(同步 LLM 生成首题,约十秒级) */
const startClarify = async () => {
  if (deepBusy.value) return   // 防重入:非幂等(新建 ASKING 会话)
  deepBusy.value = true
  // 乐观置 ASKING:立即给出对话态反馈;失败再按后端真实状态回填
  deepPlan.value = null
  deepTaskBrief.value = null
  deepFactSheet.value = null
  deepBlueprint.value = null
  deepBlueprintStatus.value = ''
  deepBlueprintQuality.value = null
  deepReasoning.value = ''
  deepSession.value = deepSession.value || { turns: [], slots: [] }
  deepQuestion.value = null
  deepStage.value = 'ASKING'
  stopBlueprintPoll()
  try {
    const res = await projectApi.clarifyStart(route.params.id)
    if (res.code !== 0) throw new Error(res.msg)
    deepBriefId.value = res.data.briefId
    deepSession.value = res.data.session || null
    deepQuestion.value = res.data.session?.currentQuestion || res.data.question || null
    deepStage.value = 'ASKING'
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e.message || '澄清会话启动失败')
    // 启动失败(如并发冲突/已有会话):退出重启态,按后端真实状态回填
    restarting.value = false
    await syncDeepStatus()
  } finally { deepBusy.value = false }
}

// 挂载即装载简报;project 详情由布局层异步加载,挂载时可能尚未就位——
// watch 兜底:project 首次到位时补拉一次并同步深度状态(刷新直进页面时必经此路径)
onMounted(() => {
  store.ensureBrief(route.params.id)
  if (props.project) { projectSeen = true; syncDeepStatus() }
})
let projectSeen = false   // project 是否已首次就位(首次到位补拉一次)
watch(() => props.project, (p) => {
  if (!p || projectSeen) return
  projectSeen = true
  loadBrief()
  syncDeepStatus()
})
// 状态迁移驱动:仅 GENERATING_BRIEF → READY 翻转时 force 重拉简报(store.startPolling 翻转回调同款语义,此处兜底组件级恢复)
watch(() => props.project?.status, (after, before) => {
  if (before === 'GENERATING_BRIEF' && after === 'READY') loadBrief()
})

// 重新研究生成(10-03 C5):FAST 接口已封死,重启整个认知流程(从澄清对话开始)
const onRegenerateDeep = () => {
  restarting.value = true
  restartingFromBriefId = props.project?.currentBriefId ?? null
  startClarify()
}
// 新简报落库(currentBriefId 指向新 brief)后退出重启态,恢复正常简报展示
watch(() => props.project?.currentBriefId, (v) => {
  if (restarting.value && v != null && v !== restartingFromBriefId) restarting.value = false
})

onUnmounted(stopBlueprintPoll)

/** C1 回答当前问题并推进一轮;收敛则展示 TaskBrief */
const onClarifyAnswer = async (answer) => {
  if (deepBusy.value) return   // 防重入
  deepBusy.value = true
  try {
    const res = await projectApi.clarifyAnswer(route.params.id, deepBriefId.value, deepQuestion.value?.id, answer)
    if (res.code !== 0) throw new Error(res.msg)
    deepSession.value = res.data.session || deepSession.value
    deepReasoning.value = deepSession.value?.reasoning || deepReasoning.value
    if (res.data.converged) {
      deepTaskBrief.value = res.data.taskBrief || null
      deepQuestion.value = null
      deepStage.value = 'CONVERGED'
      ElMessage.success('意图已收敛，请确认意图契约后开始研究')
    } else {
      deepQuestion.value = res.data.question || res.data.session?.currentQuestion || null
      deepStage.value = 'ASKING'
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e.message || '回答推进失败')
    await syncDeepStatus()
  } finally { deepBusy.value = false }
}

/** C1 强制收敛 */
const onClarifyConverge = async () => {
  if (deepBusy.value) return
  deepBusy.value = true
  try {
    const res = await projectApi.clarifyConverge(route.params.id, deepBriefId.value)
    if (res.code !== 0) throw new Error(res.msg)
    deepTaskBrief.value = res.data.taskBrief || null
    deepQuestion.value = null
    deepStage.value = 'CONVERGED'
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e.message || '收敛失败') }
  finally { deepBusy.value = false }
}

/** C1 中止会话:回引导态 */
const onClarifyAbort = async () => {
  if (deepBusy.value) return
  deepBusy.value = true
  try {
    const res = await projectApi.clarifyAbort(route.params.id, deepBriefId.value)
    if (res.code !== 0) throw new Error(res.msg)
    deepStage.value = 'NONE'
    deepSession.value = null
    deepQuestion.value = null
    deepTaskBrief.value = null
    await store.ensureProject(route.params.id, { force: true })
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e.message || '中止失败') }
  finally { deepBusy.value = false }
}

/**
 * C2 开始研究:先 plan(同步生成研究计划,置 planStatus=READY)再 run(异步多代理研究)。
 * 失败回 CONVERGED 待重试。plan 需 task_brief 非空(已在 CONVERGED 保证)。
 */
const onStartResearch = async () => {
  if (deepBusy.value) return
  deepBusy.value = true
  deepStage.value = 'PLANNING'
  try {
    const planRes = await projectApi.planDeep(route.params.id, deepBriefId.value)
    if (planRes.code !== 0) throw new Error(planRes.msg)
    deepPlan.value = asObj(planRes.data?.researchPlan, deepPlan.value)
    const runRes = await projectApi.runDeep(route.params.id, deepBriefId.value)
    if (runRes.code !== 0) throw new Error(runRes.msg)
    deepStage.value = 'RESEARCHING'
  } catch (e) {
    deepStage.value = 'CONVERGED'
    ElMessage.error(e?.response?.data?.msg || e.message || '研究启动失败')
  } finally { deepBusy.value = false }
}

// ResearchProgress 全部 agent 完成时触发:拉手册并切到 RESEARCH_DONE,轮询等蓝图出现
const onResearchDone = async () => {
  // 无论手册拉取是否成功,都先推进状态(避免停在 RESEARCHING 导致进度面板无限轮询)
  deepStage.value = 'RESEARCH_DONE'
  try {
    const st = await http.get(`/projects/${route.params.id}/deep/status?briefId=${deepBriefId.value}`)
    deepFactSheet.value = st.data?.factSheet || null
    ElMessage.success('研究完成,事实手册已生成;写作蓝图正在自动生成…')
  } catch (e) { ElMessage.error(e?.response?.data?.msg || '拉取事实手册失败') }
  // 自动蓝图由后端异步触发(经 GENERATING_BRIEF→READY),启动有界轮询直至 writingBlueprint 出现
  startBlueprintPoll()
}

/** C3 人工确认写作蓝图(可带人工编辑后的 JSON),解锁写作 */
const onBlueprintConfirm = async (editedJson) => {
  if (deepBusy.value) return
  deepBusy.value = true
  try {
    const res = await projectApi.confirmBlueprint(route.params.id, deepBriefId.value, editedJson)
    if (res.code !== 0) throw new Error(res.msg)
    deepBlueprintStatus.value = 'CONFIRMED'
    if (res.data?.writingBlueprint) deepBlueprint.value = asObj(res.data.writingBlueprint, deepBlueprint.value)
    if (res.data?.blueprintQuality) deepBlueprintQuality.value = asObj(res.data.blueprintQuality, deepBlueprintQuality.value)
    ElMessage.success('写作蓝图已确认，写作已解锁')
    await store.ensureProject(route.params.id, { force: true })
  } catch (e) { ElMessage.error(e?.response?.data?.msg || e.message || '蓝图确认失败') }
  finally { deepBusy.value = false }
}

/** C3 重新生成写作蓝图(委托 /deep/brief) */
const onBlueprintRegenerate = async () => {
  if (deepBusy.value) return   // 防重入:非幂等(重复生成覆盖蓝图)
  deepBusy.value = true
  deepStage.value = 'PLANNING'
  try {
    const res = await projectApi.generateDeepBrief(route.params.id, deepBriefId.value)
    if (res.code !== 0) throw new Error(res.msg)
    await syncDeepStatus()
  } catch (e) {
    deepStage.value = 'BLUEPRINT_REVIEW'
    ElMessage.error(e?.response?.data?.msg || e.message || '蓝图生成失败')
  } finally { deepBusy.value = false }
}

const onDeepGenerate = async () => {
  if (deepBusy.value) return   // 防重入:非幂等(重复触发生成)
  deepBusy.value = true
  try {
    // 09-27-gen-async 异步化:毫秒级返回占位,后台逐风格生成;跳版本页后由布局层轮询状态翻转刷新。
    // styleIds 缺省 = 无风格单版默认(后端 startBatchLegacy 语义,style_tag 回退「深度」)
    const res = await projectApi.generateDeep(route.params.id, deepBriefId.value)
    if (res.code !== 0) throw new Error(res.msg)
    ElMessage.success('已开始生成正文，生成完成后自动刷新')
    await store.ensureProject(route.params.id, { force: true })
    router.push({ name: 'project-versions', params: { id: route.params.id } })
  }   catch (e) { ElMessage.error(e?.response?.data?.msg || '深度写作失败') }
  finally { deepBusy.value = false }
}

// ==================== 批 2(09-28-pc-ui-refactor):属性条 + 上下文条 ====================
// 属性条「风险点」文案:按 high/medium/low 计数,空则「无」;仅视图派生,不改后端口径
const riskSummary = (b) => {
  const rs = Array.isArray(b?.factRisks) ? b.factRisks : []
  if (!rs.length) return '无'
  const n = (l) => rs.filter((r) => r.riskLevel === l).length
  return [
    n('high') ? `高 ${n('high')}` : '',
    n('medium') ? `中 ${n('medium')}` : '',
    n('low') ? `低 ${n('low')}` : '',
  ].filter(Boolean).join(' · ')
}

// 挂载在全部动作声明之后:action 直接引用 gotoVersions / onRegenerateDeep / onAnalyze(避免 TDZ)
// 契约(批 1 试点同款):usePageHeader() 返回 reactive 壳,必须整体持有后写属性。
// 不可解构后按 ref 用(crumbs.value = ...)——reactive 解构拿到的是普通数组,写 .value 不会回到壳里,
// 外壳读 header.crumbs/actions 仍是旧值,表现为面包屑与动作全部不出现。
const header = usePageHeader()
onBeforeRouteLeave(() => { if (header) { header.crumbs = []; header.actions = [] } })
function syncHeader() {
  if (!header) return
  const im = isImitation.value
  header.crumbs = [{ label: '项目' }, { label: im ? '原文分析' : '简报' }]
  const acts = []
  // 认知流程(10-03 C5):按 deepStage 给出阶段唯一主操作
  const inBlueprintReview = deepActive.value && deepStage.value === 'BLUEPRINT_REVIEW'
  if (deepActive.value) {
    if (deepStage.value === 'CONVERGED') {
      acts.push({ key: 'deep-research', label: '开始研究 →', type: 'primary', loading: deepBusy.value, onClick: onStartResearch })
    } else if (deepStage.value === 'RESEARCH_DONE') {
      // 自动蓝图生成失败/未就绪:手动重试(relabel 为「重新生成蓝图」)
      if (props.project && (props.project.lastBriefError || props.project.status === 'DRAFT')) {
        acts.push({ key: 'bp-regen', label: '重新生成蓝图', loading: deepBusy.value, onClick: onBlueprintRegenerate })
      }
    } else if (inBlueprintReview && deepBlueprintStatus.value === 'CONFIRMED' && !canViewVersions.value) {
      // 蓝图已确认且版本尚未生成:启动正文生成并跳版本页。
      // 版本已生成(canViewVersions)时抑制,避免与下方「查看版本」重复渲染两个下一步按钮,
      // 也避免误触 onDeepGenerate 重复生成(非幂等)。
      acts.push({ key: 'next', label: '进入多版本生成 →', type: 'primary', loading: deepBusy.value, onClick: onDeepGenerate })
    }
  } else if (!im && !brief.value && !generatingBrief.value) {
    // 无简报引导态:唯一主操作「开始深度研究」
    acts.push({ key: 'deep-start', label: props.project?.lastBriefError ? '重试生成研究计划' : '开始深度研究', type: 'primary', loading: deepBusy.value, onClick: startClarify })
  }
  if (canRegenerateBrief.value && !inBlueprintReview) {
    acts.push(im
      // 仿写:重新分析(analyzeImitation);主题:重新研究生成(重启整个认知流程)
      ? { key: 'regen', label: '重新分析', loading: imitationBusy.value, disabled: generatingBrief.value, onClick: onAnalyze }
      : { key: 'regen', label: '重新研究生成', loading: deepBusy.value, disabled: generatingBrief.value, onClick: onRegenerateDeep })
  }
  // 下一步按状态给出唯一动作:READY 进入版本生成;版本已生成(含已发布)查看版本
  // (蓝图评审未确认时写作未解锁,抑制通用「进入多版本生成」,避免误触 409)
  if (canGoVersions.value && !inBlueprintReview) acts.push({ key: 'next', label: '进入多版本生成 →', type: 'primary', onClick: gotoVersions })
  else if (canViewVersions.value) acts.push({ key: 'next', label: '查看版本 →', type: 'primary', onClick: gotoVersions })
  header.actions = acts
}
syncHeader()
watch([isImitation, canRegenerateBrief, canGoVersions, canViewVersions, deepBusy, imitationBusy, generatingBrief, deepActive, deepStage,
  deepBlueprintStatus, restarting, () => brief.value,
  () => `${props.project?.lastBriefError || ''}|${props.project?.status || ''}`], syncHeader)
</script>

<style scoped>
/* 批 2(09-28-pc-ui-refactor):扁平全幅,去卡片头/移动断点,全部走 token */
.brief-step { display: flex; flex-direction: column; gap: var(--sp-6); }

/* 属性条:取代原 el-card 头,常驻展示生成元信息 */
.prop-strip {
  display: flex; flex-wrap: wrap; align-items: stretch; gap: var(--sp-1) var(--sp-6);
  padding: var(--sp-3) var(--sp-4);
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
}
.prop { display: flex; align-items: center; gap: var(--sp-2); min-width: 0; }
.prop-k { color: var(--faint); font-size: var(--fs-12); line-height: var(--lh-12); }
.prop-v {
  color: var(--ink); font-size: var(--fs-13); line-height: var(--lh-13); font-weight: 600;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 32ch;
}
.prop-v.is-muted { color: var(--muted); font-weight: 500; }

.muted { color: var(--muted); font-size: var(--fs-13); line-height: var(--lh-13); }
.brief-alert { margin-bottom: var(--sp-4); }
.state-error { padding: var(--sp-8) var(--sp-4); }
.btn-icon { margin-right: var(--sp-1); }

/* 引导态:居中 hero(全幅列内居中,不再压在卡片里) */
.intro-hero { text-align: center; padding: var(--sp-8) var(--sp-4) var(--sp-4); }
.intro-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 64px; height: 64px; border-radius: var(--radius-lg);
  background: var(--brand-gradient); color: #fff;
  margin-bottom: var(--sp-4);
  box-shadow: var(--shadow-hover);
}
.intro-title { font-size: var(--fs-22); line-height: var(--lh-22); font-weight: 700; color: var(--ink); margin-bottom: var(--sp-2); }
.intro-hero p { line-height: var(--lh-16); max-width: 52ch; margin: 0 auto var(--sp-5); color: var(--muted); }
.intro-hero .muted { max-width: 60ch; margin: 0 auto; display: block; }

.generating { padding: var(--sp-1) 0; }
.gen-tip { display: flex; align-items: center; gap: var(--sp-2); margin: var(--sp-3) 0 0; font-size: var(--fs-13); line-height: var(--lh-14); color: var(--muted); }
.spin { animation: spin 1.2s linear infinite; color: var(--brand); }
@keyframes spin { to { transform: rotate(360deg); } }

/* 受众 + 观点:并排双列(全幅列内,窄栏自动收敛为单列) */
/* 受众/观点、题材/结构/句式:随可用宽度自动增列(纯 grid,无媒体查询) */
.brief-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: var(--sp-4); }
.brief-cols { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr); gap: var(--sp-7); }
/* 状态容器内的纵向节奏:首个状态块(简报正文 / 深度引导)统一由父级 gap 承担 */
.brief > * + *, .intro > * + * { margin-top: var(--sp-5); }
.brief-sec { min-width: 0; }
.brief-label {
  display: flex; align-items: center; gap: var(--sp-2);
  font-weight: 700; margin-bottom: var(--sp-3);
  font-size: var(--fs-13); line-height: var(--lh-13); letter-spacing: .04em; color: var(--ink);
}
.brief-label .el-icon { color: var(--brand); }
.label-hint { font-weight: normal; font-size: var(--fs-12); color: var(--faint); letter-spacing: 0; }
.brief-text { font-size: var(--fs-14); line-height: var(--lh-16); color: var(--ink); }

.panel { background: var(--el-fill-color-lighter); border-radius: var(--radius-sm); padding: var(--sp-4); }
.panel .brief-label { margin-bottom: var(--sp-2); }

.tag-row { display: flex; flex-wrap: wrap; gap: var(--sp-2); }
.title-tag {
  display: inline-flex; align-items: center; gap: var(--sp-2);
  max-width: 100%; height: auto; white-space: normal !important; word-break: break-word;
  line-height: var(--lh-14); padding: var(--sp-2) var(--sp-3); font-size: var(--fs-14);
  border: 1px solid var(--line-strong); border-radius: var(--radius-sm);
  background: var(--card); color: var(--ink);
  cursor: pointer; text-align: left;
  transition: border-color .2s, color .2s, background .2s, box-shadow .2s;
}
.title-tag:hover { border-color: var(--brand); color: var(--brand); }
.title-tag:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.title-tag.picked { border-color: var(--brand); background: var(--brand-weak); color: var(--brand-strong); box-shadow: 0 0 0 2px color-mix(in srgb, var(--brand) 15%, transparent); }
.title-tag.disabled { cursor: not-allowed; opacity: .6; }
.title-tag.disabled:hover { border-color: var(--line-strong); color: var(--ink); }
.pick-check { color: var(--brand-strong); }
.pick-tip { margin: var(--sp-2) 0 0; font-size: var(--fs-12); color: var(--brand-strong); }
.pick-tip.locked { color: var(--faint); }
.list, .sub-list { margin: 0; padding-left: var(--sp-5); }
.list li, .sub-list li { font-size: var(--fs-14); line-height: var(--lh-18); color: var(--ink); }

/* 大纲:编号章节 */
.outline-item { margin-bottom: var(--sp-3); }
.outline-head { display: flex; align-items: baseline; gap: var(--sp-2); font-weight: 700; font-size: var(--fs-14); line-height: var(--lh-14); color: var(--ink); }
.outline-num {
  flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border-radius: var(--radius-xs);
  background: var(--brand-weak); color: var(--brand-strong);
  font-size: var(--fs-12); line-height: var(--lh-12); font-weight: 700;
}
.sub-list { margin-top: var(--sp-1); }
.sub-list li { color: var(--muted); font-size: var(--fs-13); line-height: var(--lh-16); }

/* 风险点 */
.risk-item { background: var(--el-fill-color-lighter); border-radius: var(--radius-sm); padding: var(--sp-3); margin-bottom: var(--sp-2); }
.risk-line { display: flex; align-items: flex-start; gap: var(--sp-2); }
.risk-tag { flex-shrink: 0; margin-top: 2px; }
.risk-claim { font-size: var(--fs-14); line-height: var(--lh-14); color: var(--ink); }
.risk-sug { font-size: var(--fs-12); line-height: var(--lh-12); color: var(--muted); margin-top: var(--sp-1); }

/* 风格推荐(仿写模式) */
.rec-empty { margin-bottom: var(--sp-2); }
.rec-list { display: flex; flex-direction: column; gap: var(--sp-3); }
.rec-item { background: var(--el-fill-color-lighter); border-radius: var(--radius-sm); padding: var(--sp-3) var(--sp-4); }
.rec-head { display: flex; align-items: center; gap: var(--sp-2); }
.rec-name { font-weight: 700; font-size: var(--fs-16); line-height: var(--lh-16); color: var(--ink); }
.rec-reason { font-size: var(--fs-13); line-height: var(--lh-16); color: var(--muted); margin: var(--sp-2) 0 var(--sp-3); }

/* 引导页主操作行 + 脚注(原为无样式的遗留钩子,本批按 token 归一) */
.gen-mode-row { display: flex; justify-content: center; }
.form-tip { margin: var(--sp-4) 0 0; font-size: var(--fs-12); line-height: var(--lh-12); color: var(--faint); text-align: center; }
</style>
