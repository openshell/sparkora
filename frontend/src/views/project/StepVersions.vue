<template>
  <el-card class="step-card" shadow="never">
    <template #header>
      <span class="card-head">
        <span class="head-main">
          <span class="step-title serif">Step 2 · 多版本正文生成</span>
          <span class="step-sub">从风格库选风格，每版一种风格，生成后对比挑选</span>
        </span>
        <span v-if="versions.length" class="meta">共 {{ versions.length }} 版 · 当前：{{ currentVersionLabel }}</span>
      </span>
    </template>

    <!-- 生成中:以 project.status 为唯一事实源(刷新/切页返回也能恢复),轮询直至状态翻转 -->
    <div v-if="generatingVersions" class="generating">
      <el-skeleton :rows="8" animated />
      <p class="gen-tip">
        <el-icon class="spin"><Loading /></el-icon>
        AI 正在按所选风格逐版生成正文（共 {{ estVersions }} 版，约 {{ estMinutes }} 分钟）{{ isImitation ? '，生成后自动自检与原文相似度' : '' }}，请勿关闭页面…
      </p>
    </div>

    <template v-else>
      <!-- 版本列表加载失败:可见化 + 重试,不再静默退化成风格选择区 -->
      <div v-if="versionsError && !appending" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">版本列表加载失败</div>
        <div class="state-msg">{{ versionsError }}</div>
        <el-button type="primary" plain @click="loadVersions">重试</el-button>
      </div>

      <!-- 风格选择区:首次生成 / 追加生成共用 -->
      <div v-else-if="!versions.length || appending" class="style-pick">
        <!-- 仿写:原文摘要卡(可折叠) -->
        <el-collapse v-if="isImitation" class="imitation-src">
          <el-collapse-item title="参考原文摘要（点开查看全文）">
            <div class="src-text">{{ project?.imitationText }}</div>
          </el-collapse-item>
        </el-collapse>
        <div class="pick-hero">
          <div class="pick-icon"><el-icon :size="26"><MagicStick /></el-icon></div>
          <div class="pick-text">
            <div class="pick-title serif">{{ versions.length ? '追加生成更多风格' : (isImitation ? '选择风格，仿写生成多版正文' : '选择风格，生成多版正文') }}</div>
            <p class="muted">
              {{ versions.length
                ? '再选择风格，将在现有版本基础上追加生成（不会清空已有版本）。'
                : (isImitation
                  ? '从风格库选择 1~N 个风格仿写，每版一种风格；带「推荐」角标的风格是 AI 按原文匹配的。'
                  : '从风格库选择 1~N 个风格，每个选中的风格生成一版正文用于对比。') }}
            </p>
          </div>
        </div>
        <div v-if="stylesError" class="empty-style">
          风格库加载失败：{{ stylesError }} <el-button size="small" text type="primary" @click="loadStyles">重试</el-button>
        </div>
        <div v-else-if="!styleOptions.length" class="empty-style">
          风格库为空，请先到 <router-link to="/styles">风格库</router-link> 提炼入库。
        </div>
        <el-checkbox-group v-else v-model="selectedStyleIds" class="style-list">
          <el-checkbox v-for="s in styleOptions" :key="s.id" :label="s.id" border class="style-cb">
            <el-tag v-if="recommendedIds.includes(s.id)" size="small" type="warning" effect="plain" round class="rec-tag">推荐</el-tag>
            <span class="style-name">{{ s.name }}</span>
            <span class="style-desc">{{ s.description }}</span>
          </el-checkbox>
        </el-checkbox-group>
        <div class="gen-actions">
          <el-button v-if="appending && versions.length" @click="appending = false">取消</el-button>
          <el-button type="primary" :disabled="!selectedStyleIds.length" :loading="submitting" @click="onGenerate">
            <el-icon class="btn-icon"><MagicStick /></el-icon>生成 {{ selectedStyleIds.length || '' }} 版
          </el-button>
        </div>
      </div>

      <!-- 版本对比区 -->
      <div v-else>
        <!-- 汇总条:全部版本一览 + 对比模式入口 -->
        <div class="summary-bar">
          <div class="s-chips">
            <button v-for="v in versions" :key="v.id" type="button" class="s-chip"
                    :class="{ active: v.id === project?.currentVersionId, picked: compareIds.includes(v.id) }"
                    @click="onChipClick(v)">
              <span class="chip-label">{{ v.versionLabel }}</span>{{ v.styleTag }} · {{ v.wordCount }}字
            </button>
          </div>
          <el-select v-model="compareIds" multiple collapse-tags collapse-tags-tooltip
                     placeholder="选 2 版对比" size="small" class="compare-select">
            <el-option v-for="v in versions" :key="v.id" :label="`${v.versionLabel}·${v.styleTag}`" :value="v.id" />
          </el-select>
        </div>
        <p v-if="compareIds.length === 1" class="compare-hint">再勾选 1 版即可并排对比</p>

        <div class="version-grid" :class="{ compare: compareIds.length >= 2 }">
          <div v-for="v in displayedVersions" :key="v.id" class="version-card"
               :class="{ active: v.id === project?.currentVersionId }">
            <div class="version-head">
              <el-tag size="small" effect="dark" round class="v-label">{{ v.versionLabel }}</el-tag>
              <el-tag size="small" type="info" effect="plain" round>{{ v.styleTag }}</el-tag>
              <!-- S6.1:本版生成时的知识库检索状态(FAILED/LOW_CONFIDENCE 时提示参数未经知识库核实) -->
              <el-tag v-if="v.ragStatus === 'FAILED' || v.ragStatus === 'LOW_CONFIDENCE'"
                      size="small" type="warning" effect="plain" round>参数未经知识库核实</el-tag>
              <!-- R3:知识库引用明细(本版检索注入的命中块) -->
              <el-tag v-if="citationCount(v)" size="small" type="success" effect="plain" round
                      @click="toggleCites(v.id)">引用 {{ citationCount(v) }}</el-tag>
              <span class="version-meta">{{ v.wordCount }}字 · {{ v.aiModel }}</span>
            </div>
            <!-- 仿写:相似度行(阈值色 + 重复片段明细,可折叠;仅警示不阻断) -->
            <div v-if="isImitation && v.similarityScore != null" class="sim-row" :class="simClass(v.similarityScore)">
              <span class="sim-score">与原文相似度 {{ (v.similarityScore * 100).toFixed(1) }}%</span>
              <span class="sim-hint">{{ simHint(v.similarityScore) }}</span>
              <el-button v-if="simRuns(v).length" size="small" text type="primary" class="sim-toggle" @click="toggleSim(v.id)">
                重复片段({{ simRuns(v).length }})
              </el-button>
            </div>
            <div v-if="isImitation && simOpen[v.id] && simRuns(v).length" class="sim-detail">
              <div v-for="(r, i) in simRuns(v)" :key="i" class="sim-run">
                <span class="sim-run-len">{{ r.length }} 字</span>
                <span class="sim-run-text">「{{ r.text }}」</span>
              </div>
              <div v-if="simMaxRun(v) >= 13" class="sim-run max">最长连续片段 {{ simMaxRun(v) }} 字(≥13 字,过度贴近)</div>
            </div>
            <div v-if="citesOpen[v.id]" class="version-cites">
              <CitationList :citations="v.ragCitations" :rag-status="v.ragStatus" />
            </div>
            <div class="version-title-row">
              <div class="version-title serif">{{ v.title }}</div>
              <el-button size="small" text type="primary" class="edit-title-btn" @click="openTitleEdit(v)">
                <el-icon><Edit /></el-icon>改标题
              </el-button>
            </div>
            <div v-if="editingTitleId === v.id" class="title-edit">
              <el-input v-model="titleDraft" maxlength="200" show-word-limit placeholder="输入标题" @keyup.enter="saveTitle(v)" />
              <div class="title-edit-actions">
                <el-button size="small" text @click="editingTitleId = null">取消</el-button>
                <el-button size="small" type="primary" :loading="savingTitle" @click="saveTitle(v)">保存</el-button>
              </div>
            </div>
            <div class="version-content markdown-body" v-html="renderMd(v.contentMd)"></div>
            <div class="version-actions">
              <el-button size="small" :type="v.id === project?.currentVersionId ? 'success' : 'default'" @click="onSetCurrent(v.id)">
                {{ v.id === project?.currentVersionId ? '✓ 当前版本' : '设为当前' }}
              </el-button>
            </div>
          </div>
        </div>

        <div class="next-row">
          <!-- 再生成其他风格只在 VERSIONS_READY 可见:发布后属增量编辑,再触发会把状态机拉回 VERSIONS_READY -->
          <el-button v-if="project?.status === 'VERSIONS_READY'" :loading="submitting" @click="openAppend">再生成其他风格</el-button>
        </div>
      </div>
    </template>
  </el-card>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import { projectApi } from '../../api'
import { ElMessage } from 'element-plus'
import { isGeneratingVersions } from '../../constants/project'
import { useProjectDetailStore } from '../../store/project-detail'
import { Loading, WarningFilled, Edit, MagicStick } from '@element-plus/icons-vue'
import CitationList from './deep/CitationList.vue'
import { reactive } from 'vue'

// 数据全部来自 project-detail store(布局层已负责项目详情与轮询,这里只读 + 触发动作)
const props = defineProps({ project: Object })
const store = useProjectDetailStore()

const md = new MarkdownIt({ html: false, breaks: true, linkify: true })
const renderMd = (src) => { try { return md.render(src || '') } catch { return '' } }

const route = useRoute()
const router = useRouter()
const versions = computed(() => props.project ? store.versions(route.params.id) : [])
const styleOptions = computed(() => props.project ? store.styles(route.params.id) : [])
const versionsError = computed(() => props.project ? store.versionsError(route.params.id) : '')
const stylesError = computed(() => props.project ? store.stylesError(route.params.id) : '')
const selectedStyleIds = ref([])
const lastStyleIds = ref([])      // 上次实际用于生成的风格 id(供追加面板预选)
const appending = ref(false)      // 追加生成面板展开
const compareIds = ref([])        // 对比模式选中的版本(>=2 生效)
const submitting = ref(false)     // 本轮会话内主动点击的 loading
const editingTitleId = ref(null)  // S6:正在编辑标题的版本 id
const titleDraft = ref('')        // S6:标题编辑草稿
const savingTitle = ref(false)    // S6:标题保存中

// R3:知识库引用展开状态(按版本 id;默认收起,点「引用 N」标签展开)
const citesOpen = reactive({})
const citationCount = (v) => {
  if (Array.isArray(v?.ragCitations)) return v.ragCitations.length
  if (typeof v?.ragCitations === 'string' && v.ragCitations) {
    try { const a = JSON.parse(v.ragCitations); return Array.isArray(a) ? a.length : 0 } catch { return 0 }
  }
  return 0
}
const toggleCites = (id) => { citesOpen[id] = !citesOpen[id] }

// ==================== 文章仿写(09-09-article-imitation) ====================
const isImitation = computed(() => props.project?.genSource === 'IMITATION')
const imitationData = computed(() => props.project ? store.imitation(route.params.id) : null)
// 推荐风格 id(推荐角标;brief.styleRecommendations 中的 styleId)
const recommendedIds = computed(() => (imitationData.value?.recommendations || []).map(r => Number(r.styleId)))
// 相似度展开状态(按版本 id;默认收起)
const simOpen = reactive({})
const toggleSim = (id) => { simOpen[id] = !simOpen[id] }
// 阈值色映射:≥0.60 红 / 0.40~0.60 黄 / <0.40 绿(与后端 ImitationService 常量一致)
const simClass = (score) => (score >= 0.6 ? 'sim-high' : score >= 0.4 ? 'sim-warn' : 'sim-ok')
const simHint = (score) => score >= 0.6 ? '与原文过度相似，建议修改' : score >= 0.4 ? '存在较明显的字面重合，建议检查' : '与原文区分度良好'
// similarity_report JSON 解析(重复片段明细)
const parseReport = (v) => {
  if (!v?.similarityReport) return null
  try { return JSON.parse(v.similarityReport) } catch { return null }
}
const simRuns = (v) => parseReport(v)?.repeatedRuns || []
const simMaxRun = (v) => parseReport(v)?.maxRunLength || 0

// 生成中状态:以 project.status 为唯一事实源,刷新/切页返回均能恢复视图
const generatingVersions = computed(() => isGeneratingVersions(props.project?.status))

// 生成进度提示(按风格数粗估:每版约 1 分钟,总时长 = 版数 × 1 分钟;追加模式同样按本次所选风格数估;
// 预选兜底推荐风格,避免生成成功清空 selectedStyleIds 后重进页面显示陈旧数字)
const estVersions = computed(() =>
  selectedStyleIds.value.length || recommendedIds.value.length || 1)
const estMinutes = computed(() => Math.max(1, estVersions.value))

const currentVersionLabel = computed(() => {
  const cur = versions.value.find(v => v.id === props.project?.currentVersionId)
  return cur ? `${cur.versionLabel}·${cur.styleTag}` : '未选'
})

// 对比模式(勾选 >=2)只显示选中版本并排全高;平时显示全部版本
const displayedVersions = computed(() => {
  if (compareIds.value.length >= 2) {
    const picked = new Set(compareIds.value)
    return versions.value.filter(v => picked.has(v.id))
  }
  return versions.value
})

const onChipClick = (v) => {
  const i = compareIds.value.indexOf(v.id)
  if (i >= 0) compareIds.value.splice(i, 1)
  else compareIds.value.push(v.id)
}

const loadVersions = () => store.ensureVersions(route.params.id, { force: true })
const loadStyles = () => store.ensureStyles(route.params.id, { force: true })

const doGenerate = async (styleIds) => {
  submitting.value = true
  try {
    // 仿写模式(09-09-article-imitation):复用 POST /generate/versions(多版一次生成,
    // VersionService IMITATION 分支产出仿写正文+相似度自检),不走深度单版接口
    if (isImitation.value) {
      const res = await projectApi.generateVersions(route.params.id, styleIds)
      if (res.code === 0) {
        // AC4:留在本页展示版本对比与相似度自检,不自动跳预览
        ElMessage.success(`已生成 ${res.data?.length || 0} 版,请查看相似度自检结果`)
        await loadVersions()
        await store.ensureProject(route.params.id, { force: true })
        selectedStyleIds.value = []
        lastStyleIds.value = [...styleIds]   // 记录本次风格,供追加面板预选
      } else {
        ElMessage.error(res.msg || '仿写生成失败')
        await store.ensureProject(route.params.id, { force: true })
      }
      return
    }
    // 2026-09-09 模式收敛(09-09-brief-gen-redesign R2):快速多版本接口已封死,
    // 深度版本生成逐风格调用 /deep/generate(单风格单版;stylePrompt=风格画像 toneGuidance)
    const entry = store._entryOf(route.params.id)
    const briefId = entry?.brief?.id
    if (!briefId) { ElMessage.error('未找到当前简报,请先完成深度研究'); submitting.value = false; return }
    const allStyles = entry?.styles || []
    const okIds = []      // 生成成功的风格 id
    const failedNames = []   // 失败风格名(汇总提示)
    for (const styleId of styleIds) {
      const style = allStyles.find(s => s.id === styleId)
      try {
        const res = await projectApi.generateDeep(route.params.id, briefId, style?.toneGuidance || '')
        if (res.code === 0) okIds.push(styleId)
        else { failedNames.push(style?.name || String(styleId)); ElMessage.error(res.msg || `风格「${style?.name || styleId}」生成失败`) }
      } catch (e) {
        failedNames.push(style?.name || String(styleId))
        ElMessage.error(`风格「${style?.name || styleId}」生成失败:` + (e.response?.data?.msg || e.message || '网络异常或超时'))
      }
    }
    // 以服务器全量列表为准(本次返回仅含新增,追加时直接拼会漏失败重试的历史)
    if (okIds.length) await loadVersions()
    lastStyleIds.value = okIds.length ? [...okIds] : [...styleIds]
    if (okIds.length) {
      await store.ensureProject(route.params.id, { force: true })
      selectedStyleIds.value = []
      // 统一留在版本页(规格 12:删除 gotoPreview 自动跳转,用户经步骤条自行去预览)
      if (failedNames.length) ElMessage.success(`成功 ${okIds.length} 版,失败 ${failedNames.length} 个风格:${failedNames.join('、')}`)
      else ElMessage.success(`已生成 ${okIds.length} 版,默认选中最新一版,可重新设定`)
      // 部分失败:失败风格预选进追加面板,便于一键重试(把失败 ids 赋给 selectedStyleIds)
      if (failedNames.length) openAppendWith([...styleIds.filter(id => !okIds.includes(id))])
    } else {
      await store.ensureProject(route.params.id, { force: true })
      // 全部失败:同样把失败风格(=本次全部)预选进追加面板,便于重试
      openAppendWith([...styleIds])
    }
  } finally { submitting.value = false }
}
const onGenerate = () => {
  if (!selectedStyleIds.value.length) { ElMessage.warning('请至少选择一个风格'); return }
  doGenerate(selectedStyleIds.value)
}

// 追加生成:预选指定风格(缺省为上次实际用于生成的风格),微调后生成;不清空已有版本
const openAppend = () => openAppendWith([...lastStyleIds.value])
const openAppendWith = (ids) => {
  appending.value = true
  selectedStyleIds.value = ids
}
watch(appending, (on) => { if (on) compareIds.value = [] })

const onSetCurrent = async (versionId) => {
  const res = await projectApi.setCurrentVersion(route.params.id, versionId)
  if (res.code === 0) { await store.ensureProject(route.params.id, { force: true }); ElMessage.success('已设为当前版本') }
  else ElMessage.error(res.msg || '设置失败')
}

// S6:编辑版本标题
const openTitleEdit = (v) => { editingTitleId.value = v.id; titleDraft.value = v.title || '' }
const saveTitle = async (v) => {
  const t = titleDraft.value.trim()
  if (!t) { ElMessage.warning('标题不能为空'); return }
  savingTitle.value = true
  try {
    const res = await projectApi.saveTitle(route.params.id, v.id, t)
    if (res.code === 0) {
      v.title = t
      editingTitleId.value = null
      ElMessage.success('标题已更新')
    } else ElMessage.error(res.msg || '保存失败')
  } catch (e) {
    ElMessage.error('保存失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { savingTitle.value = false }
}

// 挂载即装载版本列表与风格库;仿写分析仅仿写项目装载(主题创作项目不发 GET /imitation);
// project 详情由布局层异步加载,挂载时可能尚未就位——watch 兜底(见下)
onMounted(() => {
  store.ensureVersions(route.params.id); store.ensureStyles(route.params.id)
  // 仿写:装载分析+推荐(推荐角标数据源);仅仿写项目发请求,不以「store 无数据」为条件
  if (props.project?.genSource === 'IMITATION') store.ensureImitation(route.params.id)
  // ?adoptStyle= 由简报页「采用推荐」带入:自动预选该风格并清掉 query 防刷新残留
  if (route.query.adoptStyle) {
    const adoptId = Number(route.query.adoptStyle)
    if (!Number.isNaN(adoptId)) selectedStyleIds.value = [adoptId]
    router.replace({ query: { ...route.query, adoptStyle: undefined } })
  }
})
// 数据兜底(规格 10):project 首次就位时,若仿写项目则补装载分析(挂载时 props 可能尚未就位);
// 状态迁移驱动:仅 GENERATING_VERSIONS → VERSIONS_READY 翻转且版本列表为空时 force 兜底
// (store.startPolling 翻转回调已覆盖主路径;styles 不跟随 project 变化重拉)
let projectSeen = false
watch(() => props.project, (p) => {
  if (!p || projectSeen) return
  projectSeen = true
  if (p.genSource === 'IMITATION') store.ensureImitation(route.params.id)
})
watch(() => props.project?.status, (after, before) => {
  if (before === 'GENERATING_VERSIONS' && after === 'VERSIONS_READY' && !store.versions(route.params.id).length) {
    loadVersions()
  }
})
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: baseline; width: 100%; gap: 12px; }
.head-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.step-title { font-size: 16px; font-weight: 700; }
.step-sub { font-size: 12px; color: var(--faint); }
.card-head .meta { font-size: 12px; color: var(--muted); font-weight: normal; white-space: nowrap; }
.muted { color: var(--muted); font-size: 13px; line-height: 1.7; }
.state-error { padding: 36px 16px; }
.btn-icon { margin-right: 2px; }

/* 风格选择 hero */
.pick-hero { display: flex; align-items: center; gap: 14px; margin-bottom: 16px; }
.pick-icon {
  flex-shrink: 0; display: inline-flex; align-items: center; justify-content: center;
  width: 52px; height: 52px; border-radius: 14px;
  background: var(--brand-gradient); color: #fff; box-shadow: var(--shadow-hover);
}
.pick-text { min-width: 0; }
.pick-title { font-size: 18px; font-weight: 700; color: var(--ink); margin-bottom: 4px; }
.pick-text .muted { margin: 0; }

.generating { padding: 4px 0; }
.gen-tip { display: flex; align-items: center; gap: 6px; margin: 12px 0 0; font-size: 13px; color: var(--muted); line-height: 1.6; }
.spin { animation: spin 1.2s linear infinite; color: var(--brand); }
@keyframes spin { to { transform: rotate(360deg); } }

.style-list { display: flex; flex-direction: column; gap: 10px; margin: 12px 0; }
.style-cb {
  display: flex; align-items: flex-start; height: auto; white-space: normal; margin-right: 0;
  padding: 12px 14px; border-radius: var(--radius-sm);
  transition: border-color .2s, box-shadow .2s, background .2s;
}
.style-cb:hover { border-color: var(--brand); }
.style-cb :deep(.el-checkbox__label) { white-space: normal; line-height: 1.5; }
.style-name { font-weight: 600; margin-right: 6px; }
.style-desc { color: var(--muted); font-size: 12px; }
.empty-style { font-size: 13px; color: var(--muted); margin: 8px 0; }
.gen-actions { display: flex; gap: 8px; }

/* 汇总条 */
.summary-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.s-chips { display: flex; gap: 6px; flex-wrap: wrap; flex: 1; }
.s-chip {
  display: inline-flex; align-items: center; gap: 4px;
  height: 30px; padding: 0 10px;
  border: 1px solid var(--line); border-radius: 999px;
  background: var(--card); color: var(--muted); font-size: 12px; cursor: pointer;
}
.s-chip .chip-label { font-weight: 700; color: var(--ink); }
.s-chip.active { border-color: var(--ok); color: var(--ok); background: transparent; }
.s-chip.active .chip-label { color: var(--ok); }
.s-chip.picked { border-color: var(--brand); color: var(--brand); }
.compare-select { width: 150px; flex-shrink: 0; }
.compare-hint { margin: 0 0 10px; font-size: 12px; color: var(--faint); }

.version-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; }
.version-card {
  border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 16px;
  background: var(--card); transition: box-shadow .2s, border-color .2s, transform .2s;
}
.version-card:hover { box-shadow: var(--shadow-hover); transform: translateY(-2px); }
.version-card.active { border-color: var(--ok); box-shadow: 0 0 0 2px color-mix(in srgb, var(--ok) 18%, transparent); }
/* 对比模式:等高铺开,长文完整滚动阅读 */
.version-grid.compare { grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); align-items: stretch; }
.version-grid.compare .version-card { display: flex; flex-direction: column; }
.version-grid.compare .version-content { flex: 1; max-height: none; }
.version-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
.v-label { font-weight: 600; }
.version-meta { font-size: 12px; color: var(--muted); margin-left: auto; }
.version-title { font-weight: 700; font-size: 15px; margin-bottom: 8px; line-height: 1.45; }
.version-title-row { display: flex; align-items: flex-start; gap: 8px; }
.version-title-row .version-title { flex: 1; margin-bottom: 8px; }
.edit-title-btn { flex-shrink: 0; margin-top: -2px; }
.title-edit { margin-bottom: 8px; }
.title-edit-actions { display: flex; justify-content: flex-end; gap: 4px; margin-top: 6px; }
.version-content { font-size: 14px; line-height: 1.8; max-height: 520px; overflow-y: auto; }
.version-content :deep(h1) { font-size: 18px; margin: 12px 0 6px; }
.version-content :deep(h2) { font-size: 16px; margin: 10px 0 5px; }
.version-content :deep(h3) { font-size: 15px; margin: 8px 0 4px; }
.version-content :deep(p) { margin: 6px 0; }
.version-content :deep(ul), .version-content :deep(ol) { padding-left: 20px; margin: 6px 0; }
.version-content :deep(code) { background: var(--el-fill-color-light); padding: 1px 4px; border-radius: 3px; font-size: 13px; }
.version-actions { display: flex; gap: 8px; margin-top: 12px; }
.version-cites { margin: 10px 0; padding: 10px 12px; background: var(--el-fill-color-light, #f7f7f7); border-radius: 8px; }
.next-row { margin-top: 18px; display: flex; gap: 8px; flex-wrap: wrap; }
.next-row .el-button:last-child { margin-left: auto; }

/* 仿写:原文摘要卡 + 推荐角标 + 相似度行 */
.imitation-src { margin-bottom: 14px; }
.imitation-src :deep(.el-collapse-item__header) { font-size: 13px; color: var(--muted); }
.src-text { font-size: 13px; line-height: 1.8; color: var(--muted); white-space: pre-wrap; max-height: 300px; overflow-y: auto; }
.rec-tag { margin-right: 4px; }
.sim-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 8px 0 4px; padding: 6px 10px; border-radius: 6px; font-size: 12px; }
.sim-row.sim-ok { background: color-mix(in srgb, var(--el-color-success) 10%, transparent); color: var(--el-color-success); }
.sim-row.sim-warn { background: color-mix(in srgb, var(--el-color-warning) 12%, transparent); color: var(--el-color-warning); }
.sim-row.sim-high { background: color-mix(in srgb, var(--el-color-danger) 12%, transparent); color: var(--el-color-danger); }
.sim-score { font-weight: 700; }
.sim-hint { font-size: 12px; }
.sim-toggle { margin-left: auto; }
.sim-detail { margin: 4px 0 8px; padding: 8px 12px; background: var(--el-fill-color-light); border-radius: 6px; }
.sim-run { display: flex; align-items: baseline; gap: 8px; padding: 3px 0; font-size: 12px; }
.sim-run-len { flex-shrink: 0; color: var(--faint); font-weight: 700; }
.sim-run-text { color: var(--muted); word-break: break-all; }
.sim-run.max { color: var(--el-color-danger); }

@media (max-width: 768px) {
  .version-grid, .version-grid.compare { grid-template-columns: 1fr; }
  .version-actions .el-button, .next-row .el-button { flex: 1; }
  .next-row .el-button:last-child { margin-left: 0; }
  .compare-select { width: 100%; }
}
</style>