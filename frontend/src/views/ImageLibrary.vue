<template>
  <div>
    <TopBar />
    <div class="container">
    <div class="page-header">
      <div>
        <span class="page-kicker">Image Library</span>
        <h2 class="serif">图库</h2>
      </div>
      <span class="muted-small">共 {{ total }} 张 · 文章配图在项目「预览」步骤从图库选用</span>
    </div>

    <!-- 工具条两段式:左主操作 / 右浏览控制 -->
    <div class="lib-toolbar">
      <div class="tb-group tb-primary">
        <el-upload :show-file-list="false" :before-upload="beforeUpload" :http-request="doUpload"
                   accept=".png,.jpg,.jpeg,.webp" multiple>
          <el-button type="primary" :loading="uploading" icon="Upload">上传图片</el-button>
        </el-upload>
        <el-button v-if="user.isEditorOrAbove" type="primary" plain icon="MagicStick" @click="aiDrawer = true">AI 生图</el-button>
        <!-- 上传标签预选(09-13 image-tags):上传与 AI 生图共读,不持久化;可新建/可清空 -->
        <el-select v-if="user.isEditorOrAbove" v-model="presetTags" multiple filterable allow-create default-first-option
                   clearable collapse-tags collapse-tags-tooltip placeholder="上传标签" class="tag-preset" size="default">
          <el-option v-for="t in tagOptionNames" :key="t" :label="t" :value="t" />
        </el-select>
      </div>
      <div class="tb-group tb-browse">
        <!-- 语义搜索模式(09-15 img-semantic-search 子B):自然语言找图,与下方精确筛选互斥 -->
        <el-tooltip content="语义搜索：用自然语言找图，如「比亚迪销量海报」" placement="top" :show-after="300">
          <el-button :type="semanticMode ? 'primary' : ''" :plain="!semanticMode" :icon="Aim"
                     @click="toggleSemanticMode">语义搜索</el-button>
        </el-tooltip>
        <el-input v-if="semanticMode" v-model="semanticQuery" clearable placeholder="如：比亚迪销量海报 / 出海签约现场" class="sem-input" size="default"
                  :prefix-icon="Aim" @keyup.enter="runSemanticSearch" @clear="exitSemantic" />
        <el-input v-else v-model="keyword" clearable placeholder="搜索文件名 / 提示词" class="kw-input" size="default"
                  :prefix-icon="Search" @input="onKeywordInput" @clear="onFilterChange" />
        <template v-if="!semanticMode">
          <el-select v-model="sourceFilter" clearable placeholder="来源" class="src-filter" size="default" @change="onFilterChange">
            <el-option v-for="(label, val) in SOURCE_LABELS" :key="val" :label="label" :value="val" />
          </el-select>
          <el-select v-model="tagFilter" multiple filterable collapse-tags collapse-tags-tooltip clearable
                     placeholder="标签（可多选，AND）" class="tag-filter" size="default" @change="onFilterChange">
            <!-- 09-15 img-classify:标签按命名空间前缀分组（主题/年份/其他），受控主题与自由标签隔离 -->
            <el-option-group v-for="g in tagGroups" :key="g.name" :label="g.name">
              <el-option v-for="t in g.options" :key="t.name" :label="`${t.name} (${t.count})`" :value="t.name" />
            </el-option-group>
          </el-select>
          <el-select v-model="projectFilter" clearable placeholder="项目" class="proj-filter" size="default" @change="onFilterChange">
            <el-option label="全部 / 全局图" :value="''" />
            <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.topic}`" :value="p.id" />
          </el-select>
        </template>
        <template v-else>
          <el-select v-model="semanticTags" multiple filterable collapse-tags collapse-tags-tooltip clearable
                     placeholder="限定标签（可选，AND）" class="tag-filter" size="default" @change="refreshSemantic">
            <el-option-group v-for="g in tagGroups" :key="g.name" :label="g.name">
              <el-option v-for="t in g.options" :key="t.name" :label="`${t.name} (${t.count})`" :value="t.name" />
            </el-option-group>
          </el-select>
          <!-- 相关度门槛：太低掺噪、太高空结果；开放调节避免「搜了没结果」无从下手 -->
          <el-tooltip content="相关度门槛：越高越严（结果更少更准）；搜不到时调低" placement="top" :show-after="300">
            <el-select v-model="semanticMinScore" class="score-filter" size="default" @change="refreshSemantic">
              <el-option label="宽松 0.15" :value="0.15" />
              <el-option label="默认 0.30" :value="0.3" />
              <el-option label="严格 0.45" :value="0.45" />
            </el-select>
          </el-tooltip>
          <el-button type="primary" :icon="Aim" :loading="semanticLoading" @click="runSemanticSearch">搜索</el-button>
        </template>
        <el-tooltip content="紧凑 / 舒适密度" placement="top" :show-after="300">
          <el-button text :icon="density === 'compact' ? Menu : Grid" aria-label="切换网格密度" @click="toggleDensity" />
        </el-tooltip>
        <el-button v-if="!semanticMode" text :icon="Refresh" aria-label="刷新" @click="load" />
        <el-button v-if="user.isEditorOrAbove && !selectMode && images.length && !semanticMode" text type="danger" plain @click="enterSelectMode">批量管理</el-button>
      </div>
    </div>

    <!-- 语义搜索提示条：结果按相关度排序，展示命中原因（嵌入原文） -->
    <div v-if="semanticMode" class="sem-bar">
      <span class="sem-bar-text">
        <el-icon><Aim /></el-icon>
        语义搜索 {{ semanticQuery ? `「${semanticQuery}」` : '' }} · 按相关度排序
        <template v-if="semanticActive">，命中 {{ total }} 张</template>
      </span>
      <el-tag v-if="semanticActive" size="small" type="info" effect="plain">门槛 ≥{{ semanticMinScore }}，按相关度排序</el-tag>
      <el-button size="small" text @click="exitSemantic">退出语义搜索</el-button>
    </div>

    <!-- 批量选择态工具条 -->
    <div v-if="selectMode" class="bulk-bar">
      <span class="bulk-count">已选 {{ selectedIds.size }} 张</span>
      <el-button size="small" @click="selectAllPage">全选本页</el-button>
      <el-button size="small" type="primary" plain :disabled="!selectedIds.size" @click="openBulkTag">打标签</el-button>
      <el-button size="small" type="danger" :disabled="!selectedIds.size" :loading="bulkDeleting" @click="onBulkDelete">删除</el-button>
      <el-button size="small" text @click="exitSelectMode">取消</el-button>
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

    <!-- 图库网格:卡片瘦身,元数据入 hover 层;移动端 ··· 兜底 -->
    <template v-else>
      <div class="img-grid" :class="{ compact: density === 'compact' }" v-loading="loading" element-loading-text="加载中…">
        <div v-for="img in images" :key="img.id" class="img-card"
             :class="{ selected: selectedIds.has(img.id), highlight: highlightId === img.id }"
             @click="onCardClick(img)">
          <!-- 缩略图 + 连续大图预览(当前页全部原图) -->
          <el-image :src="imgUrl(img)" fit="cover" class="img-thumb" loading="lazy"
                    :preview-src-list="selectMode ? [] : pageOriginUrls" :initial-index="pageOriginUrls.indexOf(originUrl(img))"
                    preview-teleported hide-on-click-modal @click.stop />
          <!-- 来源小标:色点 + 文字 -->
          <span class="src-tag" :class="'src-' + img.source">
            <span class="src-tag-dot"></span>{{ sourceLabel(img.source) }}
          </span>
          <!-- 选中态 checkbox(选择模式) -->
          <span v-if="selectMode" class="check-box" :class="{ checked: selectedIds.has(img.id) }">
            <el-icon v-if="selectedIds.has(img.id)"><Check /></el-icon>
          </span>
          <!-- 桌面 hover 层:元数据 + 操作 -->
          <div class="hover-panel">
            <div class="hp-meta">
              <div class="hp-name" :title="img.fileName">{{ img.fileName }}</div>
              <div class="hp-sub">{{ hpSubText(img) }}</div>
              <!-- 语义搜索命中：显示相关度分数与嵌入原文（可解释性） -->
              <div v-if="img.score != null" class="hp-score" :title="img.sourceText">
                相关度 {{ (img.score * 100).toFixed(0) }}%<span v-if="img.sourceText" class="hp-score-why"> · {{ img.sourceText }}</span>
              </div>
              <div v-else class="hp-sub">{{ img.createdBy }} · {{ shortTime(img.createdAt) }}</div>
              <!-- 来源追溯行（09-15 img-classify）：新闻图显示「来源：<标题> · <日期>」，点击跳原文 -->
              <div v-if="sourceInfo(img.id) && sourceInfo(img.id).news" class="hp-src">
                <span class="hp-src-text" :title="sourceInfo(img.id).news.title"
                      @click.stop="openNews(sourceInfo(img.id))">
                  来源：{{ sourceInfo(img.id).news.title }} · {{ shortDay(sourceInfo(img.id).news.publishDate) }}
                </span>
              </div>
              <div v-if="img.genModel" class="hp-gen">{{ img.genModel }}{{ img.genSize ? ' · ' + img.genSize : '' }}</div>
              <!-- 标签行:点击标签直接筛选(09-13 image-tags) -->
              <div v-if="img.tags && img.tags.length" class="hp-tags">
                <span v-for="t in img.tags" :key="t" class="hp-tag" :title="`按「${t}」筛选`"
                      @click.stop="filterByTag(t)">{{ t }}</span>
              </div>
            </div>
            <div class="hp-actions">
              <el-button v-if="user.isEditorOrAbove" size="small" text
                         @click.stop="openTagDialog(img)">编辑标签</el-button>
              <el-tooltip v-if="isAiImage(img) && user.isEditorOrAbove"
                          :disabled="canRegenerate(img)" content="参考图未入库且会话缓存已失效，无法重生成" placement="top">
                <span class="regen-wrap">
                  <el-button size="small" text type="primary" :disabled="!canRegenerate(img)"
                             :loading="regenId === img.id" @click.stop="onRegenerate(img)">重生成</el-button>
                </span>
              </el-tooltip>
              <el-button v-if="user.isEditorOrAbove" size="small" text type="danger" :loading="deletingId === img.id"
                         @click.stop="onDelete(img)">删除</el-button>
            </div>
          </div>
          <!-- 移动端:常显文件名一行 + 标签 + ··· 更多 -->
          <div class="mobile-bar">
            <span class="m-name">{{ img.fileName }}</span>
            <el-dropdown trigger="click" @command="(cmd) => onMobileCmd(cmd, img)">
              <el-button size="small" text aria-label="更多操作"><el-icon><MoreFilled /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="user.isEditorOrAbove" command="tags">编辑标签</el-dropdown-item>
                  <el-dropdown-item v-if="isAiImage(img) && user.isEditorOrAbove" command="regen" divided
                                    :disabled="!canRegenerate(img)">重新生成</el-dropdown-item>
                  <el-dropdown-item v-if="user.isEditorOrAbove" command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <!-- 移动端常显标签行:溢出横向滚动,点标签筛选 -->
          <div v-if="img.tags && img.tags.length" class="m-tags">
            <span v-for="t in img.tags" :key="t" class="m-tag" @click.stop="filterByTag(t)">{{ t }}</span>
          </div>
          <!-- 移动端来源追溯行（09-15 img-classify，hover 不可用，常显） -->
          <div v-if="sourceInfo(img.id) && sourceInfo(img.id).news" class="m-src"
               @click.stop="openNews(sourceInfo(img.id))">
            来源：{{ sourceInfo(img.id).news.title }}
          </div>
          <!-- 移动端语义分数行（09-15 img-semantic-search，hover 不可用，常显） -->
          <div v-if="img.score != null" class="m-score">相关度 {{ (img.score * 100).toFixed(0) }}%</div>
        </div>
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
    <el-dialog v-model="tagDialog" title="编辑标签" width="420px" class="tag-dialog">
      <div class="tag-dialog-tip">
        <span class="muted-small">为「{{ tagDialogImage?.fileName }}」设置标签</span>
      </div>
      <el-select v-model="tagDialogTags" multiple filterable allow-create default-first-option
                 placeholder="选择或输入标签后回车" class="tag-dialog-select">
        <el-option v-for="t in tagOptionNames" :key="t" :label="t" :value="t" />
      </el-select>
      <template #footer>
        <el-button @click="tagDialog = false">取消</el-button>
        <el-button type="primary" :loading="tagSaving" @click="onSaveTags">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量打标/移除（09-13 image-tags） -->
    <el-dialog v-model="bulkTagDialog" title="批量打标签" width="420px" class="tag-dialog">
      <div class="tag-dialog-tip">
        <span class="muted-small">对已选 {{ selectedIds.size }} 张图片统一操作</span>
      </div>
      <el-radio-group v-model="bulkTagAction" class="bulk-tag-mode">
        <el-radio value="add">补打标签</el-radio>
        <el-radio value="remove">移除标签</el-radio>
      </el-radio-group>
      <el-select v-model="bulkTagTags" multiple filterable allow-create default-first-option
                 placeholder="选择或输入标签后回车" class="tag-dialog-select">
        <el-option v-for="t in tagOptionNames" :key="t" :label="t" :value="t" />
      </el-select>
      <template #footer>
        <el-button @click="bulkTagDialog = false">取消</el-button>
        <el-button type="primary" :loading="bulkTagSaving" @click="onBulkTag">确定</el-button>
      </template>
    </el-dialog>

    <!-- 上传隐藏触发（空态按钮复用） -->
    <input ref="uploadInput" type="file" accept=".png,.jpg,.jpeg,.webp" multiple class="hidden-input" @change="onUploadInput" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TopBar from '../layouts/TopBar.vue'
import AiImageDrawer from '../components/AiImageDrawer.vue'
import { imageApi, projectApi } from '../api'
import * as imageRefCache from '../utils/imageRefCache'
import { useUserStore } from '../store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, WarningFilled, Search, MagicStick, Menu, Grid, Check, MoreFilled, Upload, Aim } from '@element-plus/icons-vue'

/** 图库维护页(S10 功能 + UI 重设计):以看图找图为核心。元数据入 hover 层;批量管理;连续预览;筛选 chip。 */
const user = useUserStore()
const SOURCE_LABELS = { upload: '上传', 'ai-text2img': '文生图', 'ai-img2img': '图生图', byd: '比亚迪', 'byd-news': '比亚迪新闻' }
const images = ref([])
const total = ref(0)
const page = ref(1)
const size = 24
const projects = ref([])
const projectFilter = ref('')
const sourceFilter = ref('')
const keyword = ref('')
// 标签域（09-13 image-tags）：allTags=[{name,count}]；tagFilter=筛选（09-15 起为数组，多标签 AND）；presetTags=上传/AI 生图预选（不持久化）
const allTags = ref([])
const tagFilter = ref([])
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
const uploading = ref(false)
const deletingId = ref(null)
const regenId = ref(null)
const highlightId = ref(null)        // AI 候选定位高亮

// ==== 语义搜索模式（09-15 img-semantic-search 子B）====
// 与精确筛选（关键字/来源/标签/项目）互斥：语义搜索走 POST /images/search，按相关度排序、无分页。
// 展示复用同一网格（命中项字段为 ImageSearchHit 子集，score/sourceText 额外存在）。
const semanticMode = ref(false)
const semanticQuery = ref('')
const semanticTags = ref([])         // 语义检索的标签 AND 预过滤（可选）
const semanticLoading = ref(false)
const semanticActive = ref(false)    // 已执行过检索（区分「未搜」与「搜了没结果」）
const semanticMinScore = ref(0.3)    // 相关度门槛（可选调；默认与后端 AI_IMAGE_MIN_SCORE 一致）
const toggleSemanticMode = () => {
  if (semanticMode.value) {
    exitSemantic()
    return
  }
  if (selectMode.value) exitSelectMode()   // 两种模式互斥：进入语义搜索前退出批量选择
  semanticMode.value = true
  semanticQuery.value = ''
  semanticTags.value = []
  semanticActive.value = false
  images.value = []
  total.value = 0
  sourceMap.value = {}
}
/** 退出语义搜索 → 回到图库浏览态（保留原有精确筛选状态） */
const exitSemantic = () => {
  semanticMode.value = false
  semanticActive.value = false
  images.value = []
  total.value = 0
  sourceMap.value = {}
  page.value = 1
  load()
}
/** 语义检索命中（ImageSearchHit）→ 网格卡片形状（补 id/url/thumbUrl，保留 score/sourceText 供展示）。
 *  命中缺少 projectId/width/height/createdBy 等字段——这些在语义模式下仅作次要信息，缺失时模板自动留空。 */
const hitToCard = (h) => ({
  id: h.imageId,
  fileName: h.fileName,
  source: h.source,
  sourceRef: h.sourceRef,
  url: h.url,
  thumbUrl: h.thumbUrl,
  tags: h.tags || [],
  score: h.score,
  sourceText: h.sourceText
})
/** 执行语义检索：空 query 提示；结果直接替换网格并按相关度降序（后端已排序） */
const runSemanticSearch = async () => {
  const q = semanticQuery.value.trim()
  if (!q) { ElMessage.warning('请输入搜索描述'); return }
  semanticLoading.value = true
  loadError.value = ''
  try {
    const res = await imageApi.search(q, { tags: semanticTags.value, minScore: semanticMinScore.value })
    if (res.code === 0) {
      images.value = (res.data || []).map(hitToCard)
      total.value = images.value.length
      semanticActive.value = true
      loadSources(images.value)
    } else loadError.value = res.msg || '检索失败'
  } catch (e) {
    loadError.value = e.response?.data?.msg || e.message || '网络异常'
  } finally {
    semanticLoading.value = false
  }
}
/** 标签变化后若已搜索过则重跑（未搜索过不动） */
const refreshSemantic = () => { if (semanticActive.value) runSemanticSearch() }

// ==== 密度切换(localStorage 记忆) ====
const density = ref(localStorage.getItem('sparkora-lib-density') || 'cozy')
const toggleDensity = () => {
  density.value = density.value === 'cozy' ? 'compact' : 'cozy'
  localStorage.setItem('sparkora-lib-density', density.value)
}

// ==== 批量选择模式 ====
const selectMode = ref(false)
const selectedIds = ref(new Set())
const bulkDeleting = ref(false)
const enterSelectMode = () => { selectMode.value = true; selectedIds.value = new Set() }
const exitSelectMode = () => { selectMode.value = false; selectedIds.value = new Set() }
const toggleSelect = (img) => {
  const s = new Set(selectedIds.value)
  s.has(img.id) ? s.delete(img.id) : s.add(img.id)
  selectedIds.value = s
}
const selectAllPage = () => { selectedIds.value = new Set(images.value.map(i => i.id)) }
/** 卡片点击:选择模式 = 勾选;浏览态 = 预览(交给 el-image,不处理) */
const onCardClick = (img) => { if (selectMode.value) toggleSelect(img) }
const onBulkDelete = () => {
  const ids = [...selectedIds.value]
  ElMessageBox.confirm(`删除选中的 ${ids.length} 张图片？被封面/插图引用的会被拒绝。`, '批量删除确认', { type: 'warning' })
    .then(async () => {
      bulkDeleting.value = true
      const failed = []
      for (const id of ids) {
        try {
          const res = await imageApi.remove(id)
          if (res.code !== 0) failed.push({ id, msg: res.msg })
        } catch (e) {
          failed.push({ id, msg: e.response?.data?.msg || e.message || '网络异常' })
        }
      }
      bulkDeleting.value = false
      if (failed.length) {
        ElMessage.warning(`已删除 ${ids.length - failed.length} 张，${failed.length} 张失败（多为被引用）`)
      } else {
        ElMessage.success(`已删除 ${ids.length} 张`)
      }
      exitSelectMode()
      await load()
    })
    .catch(() => {})
}

// ==== 筛选 chip ====
const hasFilter = computed(() => !!(keyword.value.trim() || sourceFilter.value || tagFilter.value.length || projectFilter.value !== ''))
const activeChips = computed(() => {
  const chips = []
  if (sourceFilter.value) chips.push({ key: 'src', label: `来源: ${SOURCE_LABELS[sourceFilter.value] || sourceFilter.value}`, clear: () => { sourceFilter.value = ''; onFilterChange() } })
  // 09-15 img-classify:每个选中标签各一个 chip,可单独清除（多标签 AND）
  for (const t of tagFilter.value) {
    chips.push({ key: `tag:${t}`, label: `标签: ${t}`, clear: () => { tagFilter.value = tagFilter.value.filter(x => x !== t); syncRouteTag(); onFilterChange() } })
  }
  if (projectFilter.value !== '') {
    const p = projects.value.find(x => x.id === projectFilter.value)
    chips.push({ key: 'proj', label: `项目: ${p ? '#' + p.id : '#' + projectFilter.value}`, clear: () => { projectFilter.value = ''; onFilterChange() } })
  }
  if (keyword.value.trim()) chips.push({ key: 'kw', label: `关键字: ${keyword.value.trim()}`, clear: () => { keyword.value = ''; onFilterChange() } })
  return chips
})
const clearAllFilters = () => { sourceFilter.value = ''; projectFilter.value = ''; keyword.value = ''; tagFilter.value = []; syncRouteTag(); onFilterChange() }
/** 点卡片标签 → 直接按该标签筛选（09-13 image-tags；09-15 起为多选数组，点已选标签则取消）。
 *  语义模式下点标签则退出检索、回到浏览态按该标签筛选（语义结果与精确筛选不混用）。 */
const filterByTag = (name) => {
  if (semanticMode.value) {
    semanticMode.value = false
    semanticActive.value = false
    semanticQuery.value = ''
    semanticTags.value = []
    tagFilter.value = [name]
    syncRouteTag()
    onFilterChange()
    return
  }
  tagFilter.value = tagFilter.value.includes(name)
    ? tagFilter.value.filter(t => t !== name)
    : [...tagFilter.value, name]
  syncRouteTag()
  onFilterChange()
}

// ==== 来源追溯（09-15 img-classify）：hover/移动端显示「来源：<新闻标题>·<日期>」 ====
const sourceMap = ref({})
const sourceInfo = (id) => sourceMap.value[id]
const shortDay = (t) => (t ? String(t).replace('T', ' ').slice(0, 10) : '')
const openNews = (info) => {
  const u = info?.news?.url
  if (u) window.open(/^https?:\/\//i.test(u) ? u : 'https://www.byd.com' + u, '_blank', 'noopener')
}
/** 页内批查来源（仅新闻来源图有值；失败静默，卡片不显示来源行）；结果按当前页收敛，避免长会话累积 */
const loadSources = async (rows) => {
  const targets = rows.filter(r => r.sourceRef)
  const next = {}
  if (!targets.length) { sourceMap.value = next; return }
  const list = await Promise.all(targets.map(async r => {
    try {
      const res = await imageApi.getSource(r.id)
      return res.code === 0 ? [r.id, res.data] : null
    } catch { return null }
  }))
  for (const item of list) if (item) next[item[0]] = item[1]
  sourceMap.value = next
}

// ==== 外部入口（09-15 img-classify）：/images?tag=主题/销量（新闻卡片点主题标签跳图库筛同主题） ====
const route = useRoute()
const router = useRouter()
/** 路由 query 的 tag（支持重复/逗号）解析为筛选数组 */
const tagsFromRoute = () => {
  const raw = route.query.tag
  const arr = Array.isArray(raw) ? raw : raw == null ? [] : [raw]
  return [...new Set(arr.flatMap(v => String(v).split(',')).map(s => s.trim()).filter(Boolean))]
}
/** 路由 query 的 tag 预置到多选筛选（跨页面跳转/刷新落地）。 */
const applyFilterFromRoute = () => { tagFilter.value = tagsFromRoute() }
/**
 * 标签筛选变化后把 route.query.tag 同步为当前选中（router.replace，不进历史栈）。
 * 必要性：清掉 chip 后若 URL 仍留旧 tag，再次从新闻页点同一主题时 query 未变 →
 * vue-router 判定重复导航、watch 不触发 → 筛选不生效（点了没反应）。
 */
const syncRouteTag = () => {
  const cur = tagsFromRoute()
  const next = tagFilter.value
  if (cur.length === next.length && cur.every((t, i) => next[i] === t)) return
  const query = { ...route.query }
  if (next.length) query.tag = [...next]
  else delete query.tag
  router.replace({ query })
}
watch(() => route.query.tag, () => {
  const tags = tagsFromRoute()
  // 自身 syncRouteTag 触发的回流：与当前筛选一致则不动（防重复 load）
  if (tags.length === tagFilter.value.length && tags.every((t, i) => tagFilter.value[i] === t)) return
  // 外部跳入带 tag（如新闻页点主题标签）→ 退出语义搜索，回到浏览态按标签筛选
  if (semanticMode.value) {
    semanticMode.value = false
    semanticActive.value = false
    semanticQuery.value = ''
    semanticTags.value = []
  }
  tagFilter.value = tags
  onFilterChange()
})

// ==== 连续预览:当前页全部原图 ====
const pageOriginUrls = computed(() => images.value.map(originUrl))

// ==== 数据加载 ====
const imgUrl = (img) => img?.thumbUrl || img?.url || ''
const originUrl = (img) => img?.url || ''
const sourceLabel = (s) => SOURCE_LABELS[s] || s
const shortTime = (t) => (t || '').slice(5, 16).replace('T', ' ')
const isAiImage = (img) => img.source === 'ai-text2img' || img.source === 'ai-img2img'
const projectLabel = (img) => {
  if (img.projectId === undefined) return ''   // 语义检索命中无 projectId 字段（非持久化投影），不臆断为「全局」
  if (img.projectId == null) return '全局'
  const p = projects.value.find(x => x.id === img.projectId)
  return p ? `#${p.id}` : `#${img.projectId}`
}
/** hover 副标题：过滤空段，避免语义模式下项目段缺失留下多余分隔符 */
const hpSubText = (img) => {
  const dims = img.width && img.height ? `${img.width}×${img.height}` : ''
  return [sourceLabel(img.source), projectLabel(img), dims].filter(Boolean).join(' · ')
}

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

// ==== 上传(工具条 el-upload + 空态隐藏 input 复用) ====
const uploadInput = ref(null)
const triggerUpload = () => uploadInput.value?.click()
const onUploadInput = (e) => {
  const files = [...(e.target.files || [])]
  e.target.value = ''
  files.forEach(f => doUpload({ file: f }))
}
const beforeUpload = (file) => {
  if (file.type && !/^image\/(png|jpe?g|pjpeg|webp)$/i.test(file.type)) {
    ElMessage.error('仅支持 png/jpg/webp 格式'); return false
  }
  if (file.size > 10 * 1024 * 1024) { ElMessage.error('图片超过 10MB 上限'); return false }
  return true
}
const doUpload = async ({ file }) => {
  uploading.value = true
  try {
    const res = await imageApi.upload(undefined, file, presetTags.value)
    if (res.code === 0) {
      if (res.data?.dedupeHit) ElMessage.info(`图库已有相同图片（#${res.data.id}），已复用并以并集补写标签`)
      else ElMessage.success('已上传进图库')
      await refreshView()
    }
    else ElMessage.error(res.msg || '上传失败')
  } catch (e) {
    ElMessage.error('上传失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { uploading.value = false }
}

// ==== 单卡删除 / 重生成 ====
/** 变更后刷新当前视图：语义模式重跑检索，浏览模式重载列表 */
const refreshView = async () => {
  if (semanticMode.value) { await runSemanticSearch(); return }
  page.value = 1
  await load()
}
const onDelete = (img) => {
  ElMessageBox.confirm(`删除「${img.fileName}」？被封面/插图引用时会被拒绝。`, '删除确认', { type: 'warning' })
    .then(async () => {
      deletingId.value = img.id
      try {
        const res = await imageApi.remove(img.id)
        if (res.code === 0) { ElMessage.success('已删除'); await refreshView() }
        else ElMessage.error(res.msg || '删除失败')
      } catch (e) {
        ElMessage.error('删除失败：' + (e.response?.data?.msg || e.message))
      } finally { deletingId.value = null }
    })
    .catch(() => {})
}
/**
 * 重生成可用性（09-26）：会话缓存命中（粘贴/本地参考图可复用）／文生图（prompt 可复现）／
 * 图库图生图（refImageId 可复用）；本地来源且缓存失效 → 置灰（后端无参考图可复现）。
 * 与 AiImageDrawer 内 `canRegenerate` 同口径。
 */
const canRegenerate = (img) => {
  if (!img) return false
  if (imageRefCache.has(img.id)) return true
  if (img.source === 'ai-text2img') return true
  if (img.source === 'ai-img2img') {
    // 语义检索命中（ImageSearchHit 投影）不含 refImageId，无从判断来源，故不在此误禁用——
    // 交后端 /regenerate 按 DB 实体校验（缓存缺失的粘贴/本地来源会返回中文错误）。
    if (img.score != null) return true
    // 浏览态实体：refImageId 被 Jackson non_null 省略 ⇔ 后端为 NULL（粘贴/本地直传来源）→ 置灰。
    return img.refImageId != null
  }
  return false
}
const onRegenerate = async (img) => {
  if (!canRegenerate(img)) return
  regenId.value = img.id
  try {
    // 会话缓存命中（粘贴/本地参考图来源）→ 复用参考图 + prompt 走 multipart；
    // 否则图库/文生图来源走后端 /regenerate（复用 refImageId / prompt）。
    // projectId 用缓存里的原值（与后端 /regenerate 的 orphanFallback(src.projectId) 同口径，
    // 也与 AiImageDrawer 组件内重生成一致）；硬编码 null 会把项目内图重生成到全局图库。
    const cached = imageRefCache.get(img.id)
    const res = cached?.file
      ? await imageApi.generateFromImageUpload(cached.projectId ?? null, cached.file, cached.prompt, cached.size, 1, cached.tags)
      : await imageApi.regenerate(img.id)
    if (res.code === 0) {
      const list = res.data || []
      // 新图同样可用同一参考图再重生成
      if (cached?.file) {
        for (const n of list) {
          imageRefCache.put(n.id, { file: cached.file, fileName: cached.fileName, prompt: cached.prompt, size: cached.size, tags: cached.tags, projectId: cached.projectId })
        }
      }
      ElMessage.success(`已重新生成 ${list.length} 张（新图在列表最前）`)
      await refreshView()
    } else ElMessage.error(res.msg || '重新生成失败')
  } catch (e) {
    ElMessage.error('重新生成失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { regenId.value = null }
}
/** 移动端 ··· 命令分派 */
const onMobileCmd = (cmd, img) => {
  if (cmd === 'delete') onDelete(img)
  else if (cmd === 'regen') onRegenerate(img)
  else if (cmd === 'tags') openTagDialog(img)
}

// ==== AI 生图（09-26 image-gen-drawer-ux：抽屉 UI/逻辑抽到共用组件 AiImageDrawer.vue） ====
// 仅保留宿主关心的状态与联动：抽屉开关 + 生成后刷新当前视图（refreshView）+ 候选定位（locateInList）。
const aiDrawer = ref(false)

/** 候选定位:回主列表第一页并高亮该卡 2s（语义模式下先退出检索回浏览态） */
const locateInList = async (img) => {
  if (semanticMode.value) { semanticMode.value = false; semanticActive.value = false }
  page.value = 1
  if (projectFilter.value !== '' || sourceFilter.value || tagFilter.value.length || keyword.value.trim()) {
    clearAllFilters()
  } else {
    await load()
  }
  highlightId.value = img.id
  setTimeout(() => { highlightId.value = null }, 2000)
}

// ==== 单图编辑标签（09-13 image-tags:全量覆盖） ====
const tagDialog = ref(false)
const tagDialogImage = ref(null)
const tagDialogTags = ref([])
const tagSaving = ref(false)
const openTagDialog = (img) => {
  tagDialogImage.value = img
  tagDialogTags.value = [...(img.tags || [])]
  tagDialog.value = true
}
const onSaveTags = async () => {
  const img = tagDialogImage.value
  if (!img) return
  tagSaving.value = true
  try {
    const res = await imageApi.updateTags(img.id, tagDialogTags.value)
    if (res.code === 0) {
      ElMessage.success('标签已保存')
      tagDialog.value = false
      await refreshView()
    } else ElMessage.error(res.msg || '保存失败')
  } catch (e) {
    ElMessage.error('保存失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { tagSaving.value = false }
}

// ==== 批量打标/移除（09-13 image-tags） ====
const bulkTagDialog = ref(false)
const bulkTagTags = ref([])
const bulkTagAction = ref('add')
const bulkTagSaving = ref(false)
const openBulkTag = () => { bulkTagTags.value = []; bulkTagAction.value = 'add'; bulkTagDialog.value = true }
const onBulkTag = async () => {
  if (!bulkTagTags.value.length) { ElMessage.warning('请选择或输入标签'); return }
  bulkTagSaving.value = true
  try {
    const res = await imageApi.batchTags([...selectedIds.value], bulkTagTags.value, bulkTagAction.value)
    if (res.code === 0) {
      ElMessage.success(bulkTagAction.value === 'add' ? '已补打标签' : '已移除标签')
      bulkTagDialog.value = false
      exitSelectMode()
      await load()
    } else ElMessage.error(res.msg || '操作失败')
  } catch (e) {
    ElMessage.error('操作失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { bulkTagSaving.value = false }
}

onMounted(() => { applyFilterFromRoute(); load() })
onBeforeUnmount(() => { clearTimeout(kwTimer) })
</script>

<style scoped>
.page-header { align-items: baseline; justify-content: space-between; display: flex; flex-wrap: wrap; gap: 8px; }
.page-header h2 { margin: 2px 0 0; }
.muted-small { color: var(--muted); font-size: 12px; }

/* 工具条两段式 */
.lib-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.tb-group { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.tb-primary { flex: none; }
.tb-browse { flex: 1; min-width: 0; justify-content: flex-end; }
.kw-input { width: 220px; }
.sem-input { width: 260px; }
.src-filter { width: 110px; }
.tag-filter { width: 150px; }
.score-filter { width: 130px; }
.tag-preset { width: 180px; }
.proj-filter { width: 180px; }

/* 语义搜索提示条（09-15 img-semantic-search 子B） */
.sem-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; padding: 8px 12px; background: var(--card); border: 1px solid var(--line); border-radius: var(--radius-sm); flex-wrap: wrap; }
.sem-bar-text { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: var(--text); }

/* 批量选择态 */
.bulk-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; padding: 8px 12px; background: var(--card); border: 1px solid var(--line); border-radius: var(--radius-sm); }
.bulk-count { font-size: 13px; font-weight: 600; color: var(--text); }

/* 筛选 chip */
.chip-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }

.state-error { padding: 36px 16px; text-align: center; }
.state-title { font-weight: 700; margin: 8px 0 4px; }
.state-msg { color: var(--muted); font-size: 13px; margin-bottom: 12px; }
.empty-state { padding: 40px 0; }
.empty-desc { color: var(--muted); font-size: 13px; }
.empty-actions { display: flex; gap: 10px; justify-content: center; margin-top: 14px; }
.hidden-input { display: none; }

/* 网格:舒适/紧凑双密度 */
.img-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 12px; min-height: 200px; }
.img-grid.compact { grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 8px; }

/* 卡片:瘦身,元数据入 hover 层 */
.img-card { position: relative; border-radius: var(--radius-sm); overflow: hidden; background: var(--paper); cursor: pointer; transition: box-shadow .2s; }
.img-card:hover { box-shadow: var(--shadow-hover); }
.img-card.selected { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.img-card.highlight { animation: hl-pulse 2s ease-out; }
@keyframes hl-pulse {
  0% { box-shadow: 0 0 0 4px var(--el-color-primary); }
  100% { box-shadow: 0 0 0 12px transparent; }
}
@media (prefers-reduced-motion: reduce) {
  .img-card { transition: none; }
  .img-card.highlight { animation: none; box-shadow: 0 0 0 4px var(--el-color-primary); }
}
.img-thumb { width: 100%; aspect-ratio: 4 / 3; display: block; background: var(--paper); }

/* 来源小标:色点 + 文字 */
.src-tag { position: absolute; top: 8px; left: 8px; display: inline-flex; align-items: center; gap: 4px; padding: 3px 8px 3px 6px; border-radius: 999px; background: rgba(0,0,0,.55); color: #fff; font-size: 10px; line-height: 1; backdrop-filter: blur(4px); pointer-events: none; }
.src-tag-dot { width: 6px; height: 6px; border-radius: 50%; flex: none; }
.src-tag.src-upload .src-tag-dot { background: #67c23a; }
.src-tag.src-ai-text2img .src-tag-dot { background: #409eff; }
.src-tag.src-ai-img2img .src-tag-dot { background: #9b59b6; }
.src-tag.src-byd .src-tag-dot { background: #e6a23c; }
.src-tag.src-byd-news .src-tag-dot { background: #f56c6c; }

/* 选择模式 checkbox */
.check-box { position: absolute; top: 8px; right: 8px; width: 22px; height: 22px; border-radius: 6px; background: rgba(255,255,255,.9); border: 1.5px solid var(--line); display: flex; align-items: center; justify-content: center; color: transparent; }
.check-box.checked { background: var(--el-color-primary); border-color: var(--el-color-primary); color: #fff; }

/* hover 层:桌面元数据 + 操作 */
.hover-panel { position: absolute; left: 0; right: 0; bottom: 0; padding: 24px 10px 8px; background: linear-gradient(to top, rgba(0,0,0,.78), rgba(0,0,0,.45) 70%, transparent); color: #fff; opacity: 0; transition: opacity .2s; pointer-events: none; }
.img-card:hover .hover-panel { opacity: 1; pointer-events: auto; }
.hp-meta { margin-bottom: 6px; }
.hp-name { font-size: 12px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hp-sub { font-size: 11px; opacity: .85; margin-top: 2px; }
.hp-gen { font-size: 10px; opacity: .7; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* hover 层语义分数行（09-15 img-semantic-search）：分数 + 嵌入原文截断，title 显示全文 */
.hp-score { font-size: 10px; opacity: .92; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hp-score-why { opacity: .8; }
.hp-actions { display: flex; gap: 6px; justify-content: flex-end; flex-wrap: wrap; }
.hp-actions .el-button { color: #fff; }
/* 置灰态（本地参考图缓存失效）：scoped 规则优先级高于 element 默认 disabled 色，需显式回退 */
.hp-actions .el-button.is-disabled { color: rgba(255,255,255,.4); }
.regen-wrap { display: inline-flex; }
/* hover 层标签行:点击筛选 */
.hp-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.hp-tag { font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 999px; background: rgba(255,255,255,.22); color: #fff; cursor: pointer; pointer-events: auto; }
.hp-tag:hover { background: rgba(255,255,255,.38); }
/* hover 层来源追溯行（09-15 img-classify）：点击跳新闻原文 */
.hp-src { margin-top: 4px; }
.hp-src-text { display: inline-block; max-width: 100%; font-size: 10px; opacity: .92; cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; pointer-events: auto; text-decoration: underline dotted; }
.hp-src-text:hover { opacity: 1; }

/* 移动端:常显文件名 + ··· 更多 */
.mobile-bar { display: none; }
.m-tags { display: none; }
.m-src { display: none; }
.m-score { display: none; }

.pager-row { display: flex; justify-content: center; margin-top: 18px; }

/* 标签编辑 / 批量打标对话框 */
.tag-dialog-tip { margin-bottom: 8px; }
.tag-dialog-select { width: 100%; }
.bulk-tag-mode { margin-bottom: 10px; }

/* 移动端:hover 不可用,常显文件名行 + 标签 + ··· */
@media (max-width: 768px) {
  .img-grid { grid-template-columns: repeat(2, 1fr); gap: 8px; }
  .img-grid.compact { grid-template-columns: repeat(3, 1fr); }
  .hover-panel { display: none; }
  .mobile-bar { display: flex; align-items: center; justify-content: space-between; gap: 4px; padding: 4px 6px 2px; background: var(--card); }
  .m-name { font-size: 11px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .mobile-bar .el-button { min-height: 44px; min-width: 44px; }
  /* 移动端常显标签行:单行横向滚动,点标签筛选 */
  .m-tags { display: flex; gap: 4px; padding: 0 6px 6px; background: var(--card); overflow-x: auto; flex-wrap: nowrap; scrollbar-width: none; }
  .m-tags::-webkit-scrollbar { display: none; }
  .m-tag { flex: none; display: inline-flex; align-items: center; min-height: 44px; font-size: 11px; line-height: 1; padding: 0 10px; border-radius: 999px; background: var(--paper); border: 1px solid var(--line); color: var(--muted); }
  /* 移动端来源行（09-15 img-classify）：常显单行省略，点击跳新闻原文（触控目标 ≥44px）。
     用 block + line-height 而非 flex——flex 容器上的 text-overflow 对匿名 flex item 不生效，
     标题超长会被硬裁而无「…」；block 才能正常省略。 */
  .m-src { display: block; min-height: 44px; line-height: 44px; padding: 0 6px; background: var(--card); font-size: 11px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  /* 移动端语义分数行（09-15 img-semantic-search）：常显 */
  .m-score { display: block; padding: 2px 6px 6px; background: var(--card); font-size: 11px; color: var(--muted); }
  .tb-browse { justify-content: flex-start; }
  .kw-input { width: 100%; }
  .sem-input { width: 100%; }
  .tag-filter, .tag-preset, .src-filter, .proj-filter, .score-filter { width: calc(50% - 5px); }
  .lib-toolbar .el-button { min-height: 44px; }
  .bulk-bar .el-button { min-height: 44px; }
  .sem-bar .el-button { min-height: 44px; }
}
</style>