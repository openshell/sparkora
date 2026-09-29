<template>
  <div class="page">
    <!-- 工具条:左主操作 / 右浏览控制(单行粘性)+ 图库计数说明 -->
    <div class="lib-head">
      <ImageLibraryToolbar
        :can-edit="user.isEditorOrAbove" :uploading="uploading"
        :keyword="keyword" :source-filter="sourceFilter" :tag-filter="tagFilter" :project-filter="projectFilter"
        :projects="projects" :tag-groups="tagGroups"
        :semantic-mode="semanticMode" :semantic-query="semanticQuery" :semantic-tags="semanticTags"
        :semantic-min-score="semanticMinScore" :semantic-loading="semanticLoading"
        :density="density" :select-mode="selectMode" :images-length="images.length"
        :before-upload="beforeUpload" :do-upload="doUpload"
        @update:keyword="(v) => (keyword = v)" @update:source-filter="(v) => (sourceFilter = v)"
        @update:tag-filter="(v) => (tagFilter = v)" @update:project-filter="(v) => (projectFilter = v)"
        @update:semantic-query="(v) => (semanticQuery = v)" @update:semantic-tags="(v) => (semanticTags = v)"
        @update:semantic-min-score="(v) => (semanticMinScore = v)"
        @keyword-input="onKeywordInput" @filter-change="onFilterChange"
        @toggle-semantic="toggleSemanticMode" @run-semantic="runSemanticSearch"
        @exit-semantic="exitSemantic" @refresh-semantic="refreshSemantic"
        @toggle-density="toggleDensity" @reload="load" @ai-gen="aiDrawer = true" @enter-select="enter" />
      <span class="lib-meta">共 {{ total }} 张 · 文章配图在项目「预览」步骤从图库选用</span>
    </div>

    <!-- 语义搜索提示条：结果按相关度排序，展示命中原因（嵌入原文） -->
    <ImageSemanticBar
      :semantic-mode="semanticMode" :semantic-query="semanticQuery" :semantic-active="semanticActive"
      :semantic-min-score="semanticMinScore" :total="total" @exit-semantic="exitSemantic" />

    <!-- 批量选择态工具条 -->
    <div v-if="selectMode" class="bulk-bar">
      <span class="bulk-count">已选 {{ selectedIds.size }} 张</span>
      <el-button size="small" @click="selectAllPage">全选本页</el-button>
      <el-button size="small" type="primary" plain :disabled="!selectedIds.size" @click="openBulkTag">打标签</el-button>
      <el-button size="small" type="danger" :disabled="!selectedIds.size" :loading="bulkDeleting" @click="onBulkDelete">删除</el-button>
      <el-button size="small" text @click="exit">取消</el-button>
    </div>

    <!-- 筛选状态 chip 条（语义搜索模式不展示精确筛选态） -->
    <div v-if="activeChips.length && !semanticMode" class="chip-row">
      <el-tag v-for="c in activeChips" :key="c.key" closable size="small" effect="plain" round @close="c.clear">
        {{ c.label }}
      </el-tag>
      <el-button size="small" text type="primary" @click="clearAllFilters">清除全部</el-button>
    </div>

    <!-- 加载失败 -->
    <div v-if="loadError && !images.length" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">图库加载失败</div>
      <div class="state-msg">{{ loadError }}</div>
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>

    <div v-else-if="!images.length" class="empty-state">
      <el-empty :image-size="100">
        <template #description>
          <div class="empty-desc">
            <template v-if="semanticMode && semanticActive">没有相关度达标（≥{{ semanticMinScore }}）的图片：换个说法、去掉限定标签，或把门槛调低</template>
            <template v-else-if="semanticMode">输入自然语言描述后回车搜索，如「比亚迪销量海报」</template>
            <template v-else>{{ hasFilter ? '无匹配图片：调整或清除筛选条件' : '图库还是空的，从上传或 AI 生成开始' }}</template>
          </div>
        </template>
        <div class="empty-actions">
          <el-button v-if="semanticMode" @click="exitSemantic">退出语义搜索</el-button>
          <template v-else>
            <el-button type="primary" icon="Upload" :loading="uploading" @click="triggerUpload">上传图片</el-button>
            <el-button v-if="user.isEditorOrAbove" type="primary" plain icon="MagicStick" @click="aiDrawer = true">AI 生图</el-button>
          </template>
        </div>
      </el-empty>
    </div>

    <!-- 图库网格:元数据入 hover 层 -->
    <template v-else>
      <div class="img-grid" :class="{ compact: density === 'compact' }" v-loading="loading" element-loading-text="加载中…">
        <ImageCard v-for="img in images" :key="img.id"
                   :img="img" :select-mode="selectMode" :selected="selectedIds.has(img.id)"
                   :highlight="highlightId === img.id" :source-info="sourceInfo(img.id)"
                   :projects="projects" :can-edit="user.isEditorOrAbove"
                   :can-regenerate="canRegenerate(img)" :deleting="deletingId === img.id" :regenerating="regenId === img.id"
                   :page-origin-urls="pageOriginUrls"
                   @card-click="onCardClick" @filter-tag="filterByTag" @edit-tags="openTagDialog"
                   @regen="onRegenerate" @delete="onDelete" @open-news="openNews" />
      </div>
      <!-- 分页：语义搜索为 topK 无分页，仅图库浏览态显示 -->
      <div v-if="!semanticMode" class="pager-row">
        <el-pagination v-model:current-page="page" :page-size="size" :total="total"
                       layout="prev, pager, next, total" background @current-change="load" />
      </div>
    </template>

    <!-- AI 生图抽屉（09-26 image-gen-drawer-ux：共用组件，library 模式 → 生成后定位列表） -->
    <AiImageDrawer v-model="aiDrawer" :project-id="null" mode="library"
                   :preset-tags="presetTags" @generated="refreshView" @locate="locateInList" />

    <!-- 单图编辑标签（09-13 image-tags）：全量覆盖语义 -->
    <ImageTagDialog v-model="tagDialog" :image="tagDialogImage" :tags="tagDialogTags"
                    :tag-option-names="tagOptionNames" :saving="tagSaving"
                    @update:tags="(v) => (tagDialogTags = v)" @save="onSaveTags" />

    <!-- 批量打标/移除（09-13 image-tags） -->
    <ImageBulkTagDialog v-model="bulkTagDialog" :selected-count="selectedIds.size" :tags="bulkTagTags"
                        :action="bulkTagAction" :tag-option-names="tagOptionNames" :saving="bulkTagSaving"
                        @update:tags="(v) => (bulkTagTags = v)" @update:action="(v) => (bulkTagAction = v)" @confirm="onBulkTag" />

    <!-- 上传隐藏触发（空态按钮复用） -->
    <input ref="uploadInput" type="file" accept=".png,.jpg,.jpeg,.webp" multiple class="hidden-input" @change="onUploadInput" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import AiImageDrawer from '../components/AiImageDrawer.vue'
import ImageLibraryToolbar from '../components/image/ImageLibraryToolbar.vue'
import ImageSemanticBar from '../components/image/ImageSemanticBar.vue'
import ImageCard from '../components/image/ImageCard.vue'
import ImageTagDialog from '../components/image/ImageTagDialog.vue'
import ImageBulkTagDialog from '../components/image/ImageBulkTagDialog.vue'
import { imageApi, projectApi } from '../api'
import { useUserStore } from '../store/user'
import { usePageHeader } from '../composables/usePageHeader'
import { useImageFilters } from '../composables/useImageFilters'
import { useSemanticSearch } from '../composables/useSemanticSearch'
import { useBulkSelect } from '../composables/useBulkSelect'
import { useImageSourceTrace, useImageUpload, useImageCardOps, useImageTagDialogs } from '../composables/useImageLibraryOps'
import { WarningFilled } from '@element-plus/icons-vue'

/** 图库维护页(S10 功能 + UI 重设计):以看图找图为核心。元数据入 hover 层;批量管理;连续预览;筛选 chip。
 *  09-27-split-monoliths：抽 ImageLibraryToolbar/ImageCard/ImageSemanticBar/两个标签对话框 +
 *  useImageFilters/useSemanticSearch/useBulkSelect/useImageLibraryOps composable。 */
const user = useUserStore()
const images = ref([])
const total = ref(0)
const page = ref(1)
const size = 24
const projects = ref([])
const allTags = ref([])
const tagDialogTags = ref([])
const bulkTagTags = ref([])
const presetTags = ref([])
const tagOptionNames = computed(() => {
  // 预选/编辑下拉选项 = 全库标签 ∪ 当前已选（allow-create 允许新建，此处保证已选项可回显）
  const set = new Set(allTags.value.map(t => t.name))
  ;[...presetTags.value, ...tagDialogTags.value, ...bulkTagTags.value].forEach(t => t && set.add(t))
  return [...set]
})
/** 标签下拉分组（09-15 img-classify）：按 `/` 前缀分组——「主题」/「年份」独立成组，其余归「其他」。 */
const tagGroups = computed(() => {
  const groups = new Map()
  for (const t of allTags.value) {
    const i = t.name.indexOf('/')
    const g = i > 0 ? t.name.slice(0, i) : '其他'
    if (!groups.has(g)) groups.set(g, [])
    groups.get(g).push(t)
  }
  // 分组顺序固定：主题 > 年份 > 其他 > 其余（保序输出）
  const order = ['主题', '年份', '其他']
  return [...groups.entries()]
    .sort((a, b) => {
      const ia = order.indexOf(a[0]), ib = order.indexOf(b[0])
      return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib)
    })
    .map(([name, options]) => ({ name, options }))
})
const loadError = ref('')
const loading = ref(false)
const highlightId = ref(null)        // AI 候选定位高亮

// ==== 来源追溯 + 上传 + 单卡删除/重生成 + 标签对话框 ====
const { sourceMap, sourceInfo, openNews, loadSources } = useImageSourceTrace()

// ==== 筛选域（关键字/来源/标签/项目 + chip + 路由同步）====
const {
  keyword, sourceFilter, tagFilter, projectFilter, hasFilter, activeChips,
  applyFilterFromRoute, syncRouteTag, clearAllFilters, toggleFilterTag
} = useImageFilters({
  projects,
  onFilterChange: () => onFilterChange(),
  onExternalTag: () => deactivateSemantic(),
  isSemanticMode: () => semanticMode.value
})

// ==== 语义搜索模式（09-15 img-semantic-search 子B）====
// 与精确筛选互斥：语义搜索走 POST /images/search，按相关度排序、无分页。
// 展示复用同一网格（命中项字段为 ImageSearchHit 子集，score/sourceText 额外存在）。
const clearResults = () => { images.value = []; total.value = 0; sourceMap.value = {} }
const applyResults = (list) => { images.value = list; total.value = list.length; loadSources(list) }
const {
  semanticMode, semanticQuery, semanticTags, semanticLoading, semanticActive, semanticMinScore,
  toggleSemanticMode, exitSemantic, deactivate: semanticDeactivate, runSemanticSearch, refreshSemantic
} = useSemanticSearch({
  clearResults,
  applyResults,
  setError: (msg) => { loadError.value = msg },
  exitSelectMode: () => exit(),
  exitToList: () => { page.value = 1; load() }
})
/** 外部带 tag 跳入 → 退出语义模式（清输入与标记，结果随 onFilterChange 的 load 覆盖）。 */
const deactivateSemantic = () => semanticDeactivate({ resetInputs: true })

// ==== 密度切换(localStorage 记忆) ====
const density = ref(localStorage.getItem('sparkora-lib-density') || 'cozy')
const toggleDensity = () => {
  density.value = density.value === 'cozy' ? 'compact' : 'cozy'
  localStorage.setItem('sparkora-lib-density', density.value)
}

// ==== 批量选择模式 ====
const { selectMode, selectedIds, bulkDeleting, enter, exit, selectAllPage, toggleSelect, onBulkDelete } = useBulkSelect({
  getPageIds: () => images.value.map(i => i.id),
  reload: () => load()
})
/** 卡片点击:选择模式 = 勾选;浏览态 = 预览(交给 el-image,不处理)。 */
const onCardClick = (img) => { if (selectMode.value) toggleSelect(img) }

/** 点卡片标签 → 直接按该标签筛选（09-13 image-tags；09-15 起为多选数组，点已选标签则取消）。
 *  语义模式下点标签则退出检索、回到浏览态按该标签筛选（语义结果与精确筛选不混用）。 */
const filterByTag = (name) => {
  if (semanticMode.value) {
    semanticDeactivate({ resetInputs: true })
    tagFilter.value = [name]
    syncRouteTag()
    onFilterChange()
    return
  }
  toggleFilterTag(name)
}

// ==== 连续预览:当前页全部原图 ====
const pageOriginUrls = computed(() => images.value.map(v => v?.url || ''))

// ==== 数据加载 ====
let kwTimer = null
const onKeywordInput = () => {
  clearTimeout(kwTimer)
  kwTimer = setTimeout(onFilterChange, 300)
}
const onFilterChange = () => {
  clearTimeout(kwTimer)
  page.value = 1
  load()
}

const load = async () => {
  loadError.value = ''
  loading.value = true
  try {
    const [imgRes, projRes, tagRes] = await Promise.all([
      imageApi.list({
        page: page.value, size,
        projectId: projectFilter.value || undefined,
        source: sourceFilter.value || undefined,
        tag: tagFilter.value.length ? tagFilter.value : undefined,
        keyword: keyword.value.trim() || undefined
      }),
      projectApi.list({ page: 1, size: 100 }),
      imageApi.listTags()
    ])
    if (imgRes.code === 0) {
      images.value = imgRes.data?.rows || []
      total.value = imgRes.data?.total || 0
      loadSources(images.value)
    } else loadError.value = imgRes.msg || '加载失败'
    if (projRes.code === 0) projects.value = projRes.data?.rows || []
    if (tagRes.code === 0) allTags.value = tagRes.data || []
  } catch (e) {
    loadError.value = e.response?.data?.msg || e.message || '网络异常'
  } finally {
    loading.value = false
  }
}

/** 变更后刷新当前视图：语义模式重跑检索，浏览模式重载列表 */
const refreshView = async () => {
  if (semanticMode.value) { await runSemanticSearch(); return }
  page.value = 1
  await load()
}

const { uploading, uploadInput, triggerUpload, onUploadInput, beforeUpload, doUpload } =
  useImageUpload({ presetTags, refreshView })
const { deletingId, regenId, canRegenerate, onDelete, onRegenerate } = useImageCardOps({ refreshView })
const { tagDialog, tagDialogImage, tagSaving, openTagDialog, onSaveTags, bulkTagDialog, bulkTagAction, bulkTagSaving, openBulkTag, onBulkTag } =
  useImageTagDialogs({ selectedIds, exitSelectMode: exit, refreshView, tagDialogTags, bulkTagTags })

// ==== AI 生图（09-26 image-gen-drawer-ux：抽屉 UI/逻辑抽到共用组件 AiImageDrawer.vue） ====
// 仅保留宿主关心的状态与联动：抽屉开关 + 生成后刷新当前视图（refreshView）+ 候选定位（locateInList）。
const aiDrawer = ref(false)

/** 候选定位:回主列表第一页并高亮该卡 2s（语义模式下先退出检索回浏览态） */
const locateInList = async (img) => {
  if (semanticMode.value) semanticDeactivate()
  page.value = 1
  if (projectFilter.value !== '' || sourceFilter.value || tagFilter.value.length || keyword.value.trim()) {
    clearAllFilters()
  } else {
    await load()
  }
  highlightId.value = img.id
  setTimeout(() => { highlightId.value = null }, 2000)
}

onMounted(() => { applyFilterFromRoute(); load() })
onBeforeUnmount(() => { clearTimeout(kwTimer) })

// ==== 页头（09-28-pc-ui-refactor 批1：标题/计数并入 AppShell 上下文条 + 页内工具带） ====
const header = usePageHeader()
if (header) header.crumbs = [{ label: '资产' }, { label: '图库' }]
onBeforeRouteLeave(() => { if (header) { header.crumbs = []; header.actions = [] } })
</script>

<style scoped>
/* 工具条外壳 + 图库计数（工具条组件内部结构不变，单行粘性；页内错误/空态用全局 .state-error/.empty-state） */
.lib-head { position: sticky; top: 0; z-index: 20; display: flex; align-items: flex-start; gap: var(--sp-4); margin-bottom: var(--sp-4); padding-bottom: var(--sp-2); background: var(--paper); }
.lib-head :deep(.lib-toolbar) { flex: 1; min-width: 0; margin-bottom: 0; }
.lib-meta { flex: none; font-size: var(--fs-12); color: var(--faint); line-height: var(--control-h-md); white-space: nowrap; }

/* 批量选择态 */
.bulk-bar { display: flex; align-items: center; gap: var(--sp-3); margin-bottom: var(--sp-4); padding: var(--sp-3) var(--sp-5); background: var(--el-color-primary-light-9); border: 1px solid var(--el-color-primary-light-8); border-radius: var(--radius-md); }
.bulk-count { font-size: var(--fs-13); font-weight: 600; color: var(--brand-strong); }

/* 筛选 chip */
.chip-row { display: flex; align-items: center; gap: var(--sp-3); margin-bottom: var(--sp-4); flex-wrap: wrap; }

.empty-state { padding: var(--sp-8) 0; }
.empty-desc { color: var(--muted); font-size: var(--fs-13); }
.empty-actions { display: flex; gap: var(--sp-4); justify-content: center; margin-top: var(--sp-5); }
.hidden-input { display: none; }

/* 网格:舒适/紧凑双密度（PC-only，去掉 2 列移动端塌陷） */
.img-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: var(--sp-5); min-height: 200px; }
.img-grid.compact { grid-template-columns: repeat(auto-fill, minmax(148px, 1fr)); gap: var(--sp-3); }

.pager-row { display: flex; justify-content: flex-end; margin-top: var(--sp-6); }
</style>
