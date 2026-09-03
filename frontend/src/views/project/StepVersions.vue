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
        AI 正在按所选风格逐版生成正文（共 {{ estVersions }} 版，约 {{ estMinutes }} 分钟），请勿关闭页面…
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
        <div class="pick-hero">
          <div class="pick-icon"><el-icon :size="26"><MagicStick /></el-icon></div>
          <div class="pick-text">
            <div class="pick-title serif">{{ versions.length ? '追加生成更多风格' : '选择风格，生成多版正文' }}</div>
            <p class="muted">
              {{ versions.length
                ? '再选择风格，将在现有版本基础上追加生成（不会清空已有版本）。'
                : '从风格库选择 1~N 个风格，每个选中的风格生成一版正文用于对比。' }}
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
              <span class="version-meta">{{ v.wordCount }}字 · {{ v.aiModel }}</span>
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
          <!-- 再生成其他风格只在 VERSIONS_READY 可见:配图完成后属增量编辑,再触发会把状态机拉回 VERSIONS_READY -->
          <el-button v-if="project?.status === 'VERSIONS_READY'" :loading="submitting" @click="openAppend">再生成其他风格</el-button>
          <el-button type="success" :disabled="!project?.currentVersionId" @click="gotoNext">
            {{ project?.status === 'VERSIONS_READY' ? '选定当前版本 · 进入下一步（配图）→' : '已完成配图 · 去预览 →' }}
          </el-button>
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

// 生成中状态:以 project.status 为唯一事实源,刷新/切页返回均能恢复视图
const generatingVersions = computed(() => isGeneratingVersions(props.project?.status))

// 生成进度提示(按风格数粗估:每版约 1 分钟,总时长 = 版数 × 1 分钟;追加模式同样按本次所选风格数估)
const estVersions = computed(() => selectedStyleIds.value.length || 1)
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
    const res = await projectApi.generateVersions(route.params.id, styleIds)
    if (res.code === 0) {
      // 以服务器全量列表为准(本次返回仅含新增,追加时直接拼会漏失败重试的历史)
      await loadVersions()
      lastStyleIds.value = [...styleIds]
      ElMessage.success(`已生成 ${res.data?.length || 0} 版，默认选中本次第一版，可重新设定`)
    } else ElMessage.error(res.msg || '生成失败')
    await store.ensureProject(route.params.id, { force: true })
  } catch (e) {
    ElMessage.error('生成失败：' + (e.response?.data?.msg || e.message || '网络异常或超时'))
    await store.ensureProject(route.params.id, { force: true })
  } finally { submitting.value = false }
}
const onGenerate = () => {
  if (!selectedStyleIds.value.length) { ElMessage.warning('请至少选择一个风格'); return }
  doGenerate(selectedStyleIds.value)
}

// 追加生成:预选上次风格,微调后生成;不清空已有版本
const openAppend = () => {
  appending.value = true
  selectedStyleIds.value = [...lastStyleIds.value]
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
const gotoNext = () => {
  // 下一步按状态推进:VERSIONS_READY(版本就绪、未配图)→ 配图;IMAGES_READY 及之后(已配图)→ 预览
  const target = props.project?.status === 'VERSIONS_READY' ? 'project-images' : 'project-preview'
  router.push({ name: target, params: { id: route.params.id } })
}

// 挂载即装载版本列表与风格库;project 详情由布局层异步加载,挂载时可能尚未就位——
// watch 兜底等它到位后立即补拉(刷新直进页面时必经此路径)
onMounted(() => { store.ensureVersions(route.params.id); store.ensureStyles(route.params.id) })
watch(() => props.project, (p) => { if (p) { loadVersions(); loadStyles() } })
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
.next-row { margin-top: 18px; display: flex; gap: 8px; flex-wrap: wrap; }
.next-row .el-button:last-child { margin-left: auto; }

@media (max-width: 768px) {
  .version-grid, .version-grid.compare { grid-template-columns: 1fr; }
  .version-actions .el-button, .next-row .el-button { flex: 1; }
  .next-row .el-button:last-child { margin-left: 0; }
  .compare-select { width: 100%; }
}
</style>