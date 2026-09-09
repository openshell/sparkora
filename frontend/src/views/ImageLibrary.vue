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
      </div>
      <div class="tb-group tb-browse">
        <el-input v-model="keyword" clearable placeholder="搜索文件名 / 提示词" class="kw-input" size="default"
                  :prefix-icon="Search" @input="onKeywordInput" @clear="onFilterChange" />
        <el-select v-model="sourceFilter" clearable placeholder="来源" class="src-filter" size="default" @change="onFilterChange">
          <el-option v-for="(label, val) in SOURCE_LABELS" :key="val" :label="label" :value="val" />
        </el-select>
        <el-select v-model="projectFilter" clearable placeholder="项目" class="proj-filter" size="default" @change="onFilterChange">
          <el-option label="全部 / 全局图" :value="''" />
          <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.topic}`" :value="p.id" />
        </el-select>
        <el-tooltip content="紧凑 / 舒适密度" placement="top" :show-after="300">
          <el-button text :icon="density === 'compact' ? Menu : Grid" aria-label="切换网格密度" @click="toggleDensity" />
        </el-tooltip>
        <el-button text :icon="Refresh" aria-label="刷新" @click="load" />
        <el-button v-if="user.isEditorOrAbove && !selectMode && images.length" text type="danger" plain @click="enterSelectMode">批量管理</el-button>
      </div>
    </div>

    <!-- 批量选择态工具条 -->
    <div v-if="selectMode" class="bulk-bar">
      <span class="bulk-count">已选 {{ selectedIds.size }} 张</span>
      <el-button size="small" @click="selectAllPage">全选本页</el-button>
      <el-button size="small" type="danger" :disabled="!selectedIds.size" :loading="bulkDeleting" @click="onBulkDelete">删除</el-button>
      <el-button size="small" text @click="exitSelectMode">取消</el-button>
    </div>

    <!-- 筛选状态 chip 条 -->
    <div v-if="activeChips.length" class="chip-row">
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
          <div class="empty-desc">{{ hasFilter ? '无匹配图片：调整或清除筛选条件' : '图库还是空的，从上传或 AI 生成开始' }}</div>
        </template>
        <div class="empty-actions">
          <el-button type="primary" icon="Upload" :loading="uploading" @click="triggerUpload">上传图片</el-button>
          <el-button v-if="user.isEditorOrAbove" type="primary" plain icon="MagicStick" @click="aiDrawer = true">AI 生图</el-button>
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
              <div class="hp-sub">{{ sourceLabel(img.source) }} · {{ projectLabel(img) }} · {{ img.width && img.height ? `${img.width}×${img.height}` : '' }}</div>
              <div class="hp-sub">{{ img.createdBy }} · {{ shortTime(img.createdAt) }}</div>
              <div v-if="img.genModel" class="hp-gen">{{ img.genModel }}{{ img.genSize ? ' · ' + img.genSize : '' }}</div>
            </div>
            <div class="hp-actions">
              <el-button v-if="isAiImage(img) && user.isEditorOrAbove" size="small" text type="primary"
                         :loading="regenId === img.id" @click.stop="onRegenerate(img)">重生成</el-button>
              <el-button v-if="user.isEditorOrAbove" size="small" text type="danger" :loading="deletingId === img.id"
                         @click.stop="onDelete(img)">删除</el-button>
            </div>
          </div>
          <!-- 移动端:常显文件名一行 + ··· 更多 -->
          <div class="mobile-bar">
            <span class="m-name">{{ img.fileName }}</span>
            <el-dropdown trigger="click" @command="(cmd) => onMobileCmd(cmd, img)">
              <el-button size="small" text aria-label="更多操作"><el-icon><MoreFilled /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-if="isAiImage(img) && user.isEditorOrAbove" command="regen">重新生成</el-dropdown-item>
                  <el-dropdown-item v-if="user.isEditorOrAbove" command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>
      </div>
      <!-- 分页 -->
      <div class="pager-row">
        <el-pagination v-model:current-page="page" :page-size="size" :total="total"
                       layout="prev, pager, next, total" background @current-change="load" />
      </div>
    </template>

    <!-- AI 生图抽屉 -->
    <el-drawer v-model="aiDrawer" title="AI 生图" size="420px" class="ai-drawer">
      <el-tabs v-model="aiTab">
        <el-tab-pane label="文生图" name="text2img">
          <el-input v-model="aiPrompt" type="textarea" :rows="3" placeholder="例：俯瞰一杯与摊开的笔记本，晨光，暖色调，杂志摄影风格" />
          <div class="ai-row">
            <el-select v-model="aiSize" class="size-select">
              <el-option label="方图 1024×1024" value="1024x1024" />
              <el-option label="横图 1536×1024" value="1536x1024" />
              <el-option label="竖图 1024×1536" value="1024x1536" />
            </el-select>
            <el-select v-model="aiCount" class="n-select">
              <el-option label="1 张" :value="1" />
              <el-option label="2 张" :value="2" />
              <el-option label="4 张" :value="4" />
            </el-select>
          </div>
          <el-button type="primary" class="gen-btn" :loading="generating" @click="onGenerateText">
            {{ generating ? '生成中…' : '生成候选' }}
          </el-button>
        </el-tab-pane>
        <el-tab-pane label="图生图" name="img2img">
          <div v-if="refImage" class="ref-pick">
            <img :src="imgUrl(refImage)" class="ref-thumb" alt="参考图" />
            <el-button size="small" text type="primary" @click="refDialog = true">重新选择</el-button>
          </div>
          <el-button v-else plain size="small" @click="refDialog = true">从图库选择参考图</el-button>
          <el-input v-model="aiPrompt" type="textarea" :rows="3" placeholder="例：保持构图，改为蓝灰色科技感色调" />
          <div class="ai-row">
            <el-select v-model="aiSize" class="size-select">
              <el-option label="方图 1024×1024" value="1024x1024" />
              <el-option label="横图 1536×1024" value="1536x1024" />
              <el-option label="竖图 1024×1536" value="1024x1536" />
            </el-select>
            <el-select v-model="aiCount" class="n-select">
              <el-option label="1 张" :value="1" />
              <el-option label="2 张" :value="2" />
              <el-option label="4 张" :value="4" />
            </el-select>
          </div>
          <el-button type="primary" class="gen-btn" :disabled="!refImage" :loading="generating" @click="onGenerateFromImage">
            {{ generating ? '生成中…' : '生成候选' }}
          </el-button>
        </el-tab-pane>
      </el-tabs>
      <!-- 候选结果:可预览,可定位到主列表 -->
      <div v-if="candidates.length" class="cand-list">
        <div class="cand-tip">本次生成 {{ candidates.length }} 张，已进图库（点击卡片定位到列表）</div>
        <div class="cand-grid">
          <div v-for="img in candidates" :key="img.id" class="cand-cell" @click="locateInList(img)">
            <el-image :src="imgUrl(img)" fit="cover" class="cand-thumb" :preview-src-list="[originUrl(img)]"
                      preview-teleported hide-on-click-modal @click.stop />
            <span class="cand-id">#{{ img.id }}</span>
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 参考图选择弹窗（图生图;与图库列表同数据源） -->
    <el-dialog v-model="refDialog" title="选择参考图" width="720px" class="ref-dialog">
      <div v-if="!images.length" class="img-pop-empty">图库为空：先上传图片或用文生图生成</div>
      <div v-else class="ref-grid">
        <div v-for="img in images" :key="img.id" class="ref-cell" @click="chooseRef(img)">
          <el-image :src="imgUrl(img)" fit="cover" class="ref-cell-thumb" />
          <span class="ref-cell-name">#{{ img.id }} {{ img.fileName }}</span>
        </div>
      </div>
    </el-dialog>

    <!-- 上传隐藏触发（空态按钮复用） -->
    <input ref="uploadInput" type="file" accept=".png,.jpg,.jpeg,.webp" multiple class="hidden-input" @change="onUploadInput" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import TopBar from '../layouts/TopBar.vue'
import { imageApi, projectApi } from '../api'
import { useUserStore } from '../store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, WarningFilled, Search, MagicStick, Menu, Grid, Check, MoreFilled, Upload } from '@element-plus/icons-vue'

/** 图库维护页(S10 功能 + UI 重设计):以看图找图为核心。元数据入 hover 层;批量管理;连续预览;筛选 chip。 */
const user = useUserStore()
const SOURCE_LABELS = { upload: '上传', 'ai-text2img': '文生图', 'ai-img2img': '图生图', byd: '比亚迪' }
const images = ref([])
const total = ref(0)
const page = ref(1)
const size = 24
const projects = ref([])
const projectFilter = ref('')
const sourceFilter = ref('')
const keyword = ref('')
const loadError = ref('')
const loading = ref(false)
const uploading = ref(false)
const deletingId = ref(null)
const regenId = ref(null)
const highlightId = ref(null)        // AI 候选定位高亮

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
const hasFilter = computed(() => !!(keyword.value.trim() || sourceFilter.value || projectFilter.value !== ''))
const activeChips = computed(() => {
  const chips = []
  if (sourceFilter.value) chips.push({ key: 'src', label: `来源: ${SOURCE_LABELS[sourceFilter.value]}`, clear: () => { sourceFilter.value = ''; onFilterChange() } })
  if (projectFilter.value !== '') {
    const p = projects.value.find(x => x.id === projectFilter.value)
    chips.push({ key: 'proj', label: `项目: ${p ? '#' + p.id : '#' + projectFilter.value}`, clear: () => { projectFilter.value = ''; onFilterChange() } })
  }
  if (keyword.value.trim()) chips.push({ key: 'kw', label: `关键字: ${keyword.value.trim()}`, clear: () => { keyword.value = ''; onFilterChange() } })
  return chips
})
const clearAllFilters = () => { sourceFilter.value = ''; projectFilter.value = ''; keyword.value = ''; onFilterChange() }

// ==== 连续预览:当前页全部原图 ====
const pageOriginUrls = computed(() => images.value.map(originUrl))

// ==== 数据加载 ====
const imgUrl = (img) => img?.thumbUrl || img?.url || ''
const originUrl = (img) => img?.url || ''
const sourceLabel = (s) => SOURCE_LABELS[s] || s
const shortTime = (t) => (t || '').slice(5, 16).replace('T', ' ')
const isAiImage = (img) => img.source === 'ai-text2img' || img.source === 'ai-img2img'
const projectLabel = (img) => {
  if (img.projectId == null) return '全局'
  const p = projects.value.find(x => x.id === img.projectId)
  return p ? `#${p.id}` : `#${img.projectId}`
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
    const [imgRes, projRes] = await Promise.all([
      imageApi.list({
        page: page.value, size,
        projectId: projectFilter.value || undefined,
        source: sourceFilter.value || undefined,
        keyword: keyword.value.trim() || undefined
      }),
      projectApi.list({ page: 1, size: 100 })
    ])
    if (imgRes.code === 0) {
      images.value = imgRes.data?.rows || []
      total.value = imgRes.data?.total || 0
    } else loadError.value = imgRes.msg || '加载失败'
    if (projRes.code === 0) projects.value = projRes.data?.rows || []
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
    const res = await imageApi.upload(undefined, file)
    if (res.code === 0) {
      if (res.data?.dedupeHit) ElMessage.info(`图库已有相同图片（#${res.data.id}），已复用未重复上传`)
      else ElMessage.success('已上传进图库')
      page.value = 1
      await load()
    }
    else ElMessage.error(res.msg || '上传失败')
  } catch (e) {
    ElMessage.error('上传失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { uploading.value = false }
}

// ==== 单卡删除 / 重生成 ====
const onDelete = (img) => {
  ElMessageBox.confirm(`删除「${img.fileName}」？被封面/插图引用时会被拒绝。`, '删除确认', { type: 'warning' })
    .then(async () => {
      deletingId.value = img.id
      try {
        const res = await imageApi.remove(img.id)
        if (res.code === 0) { ElMessage.success('已删除'); await load() }
        else ElMessage.error(res.msg || '删除失败')
      } catch (e) {
        ElMessage.error('删除失败：' + (e.response?.data?.msg || e.message))
      } finally { deletingId.value = null }
    })
    .catch(() => {})
}
const onRegenerate = async (img) => {
  regenId.value = img.id
  try {
    const res = await imageApi.regenerate(img.id)
    if (res.code === 0) {
      const list = res.data || []
      ElMessage.success(`已重新生成 ${list.length} 张（新图在列表最前）`)
      page.value = 1
      await load()
    } else ElMessage.error(res.msg || '重新生成失败')
  } catch (e) {
    ElMessage.error('重新生成失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { regenId.value = null }
}
/** 移动端 ··· 命令分派 */
const onMobileCmd = (cmd, img) => {
  if (cmd === 'delete') onDelete(img)
  else if (cmd === 'regen') onRegenerate(img)
}

// ==== AI 生图(全局图库) ====
const aiDrawer = ref(false)
const aiTab = ref('text2img')
const aiPrompt = ref('')
const aiSize = ref('1024x1024')
const aiCount = ref(1)
const refImage = ref(null)
const refDialog = ref(false)
const generating = ref(false)
const candidates = ref([])

const chooseRef = (img) => { refImage.value = img; refDialog.value = false }

/** 候选定位:回主列表第一页并高亮该卡 2s */
const locateInList = async (img) => {
  page.value = 1
  if (projectFilter.value !== '' || sourceFilter.value || keyword.value.trim()) {
    clearAllFilters()
  } else {
    await load()
  }
  highlightId.value = img.id
  setTimeout(() => { highlightId.value = null }, 2000)
}

const onGenerateText = async () => {
  if (!aiPrompt.value.trim()) { ElMessage.warning('请输入画面描述'); return }
  generating.value = true
  try {
    const res = await imageApi.generateText(null, aiPrompt.value.trim(), aiSize.value, aiCount.value)
    if (res.code === 0) await afterGenerated(res.data || [])
    else ElMessage.error(res.msg || '生成失败')
  } catch (e) {
    ElMessage.error('生成失败：' + (e.response?.data?.msg || e.message || '网络异常或超时'))
  } finally { generating.value = false }
}
const onGenerateFromImage = async () => {
  if (!refImage.value) { ElMessage.warning('请先选择参考图'); return }
  if (!aiPrompt.value.trim()) { ElMessage.warning('请输入画面描述'); return }
  generating.value = true
  try {
    const res = await imageApi.generateFromImage(null, refImage.value.id, aiPrompt.value.trim(), aiSize.value, aiCount.value)
    if (res.code === 0) await afterGenerated(res.data || [])
    else ElMessage.error(res.msg || '生成失败')
  } catch (e) {
    ElMessage.error('生成失败：' + (e.response?.data?.msg || e.message || '网络异常或超时'))
  } finally { generating.value = false }
}
const afterGenerated = async (list) => {
  candidates.value = list
  const reused = list.some(img => img.dedupeHit)
  ElMessage.success(reused ? `生成 ${list.length} 张（部分与图库重复，已复用）` : `生成成功 ${list.length} 张，已进图库`)
  page.value = 1
  await load()
}

onMounted(load)
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
.src-filter { width: 110px; }
.proj-filter { width: 180px; }

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
.hp-actions { display: flex; gap: 6px; justify-content: flex-end; }
.hp-actions .el-button { color: #fff; }

/* 移动端:常显文件名 + ··· 更多 */
.mobile-bar { display: none; }

.pager-row { display: flex; justify-content: center; margin-top: 18px; }

/* AI 生图抽屉 */
.ai-row { display: flex; gap: 8px; margin-top: 10px; }
.size-select { flex: 1; }
.n-select { width: 90px; flex: none; }
.gen-btn { width: 100%; margin-top: 10px; min-height: 44px; }
.ref-pick { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.ref-thumb { width: 72px; height: 54px; object-fit: cover; border-radius: var(--radius-sm); border: 1px solid var(--line); }
.cand-list { margin-top: 14px; border-top: 1px solid var(--line); padding-top: 10px; }
.cand-tip { font-size: 12px; color: var(--muted); margin-bottom: 8px; }
.cand-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.cand-cell { position: relative; border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 4px; cursor: pointer; }
.cand-cell:hover { box-shadow: var(--shadow-hover); }
.cand-thumb { width: 100%; aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--paper); display: block; }
.cand-id { position: absolute; top: 8px; left: 8px; font-size: 11px; color: #fff; background: rgba(0,0,0,.55); padding: 1px 6px; border-radius: 4px; }
.img-pop-empty { font-size: 13px; color: var(--muted); padding: 8px 0; }
.ref-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; max-height: 60vh; overflow-y: auto; }
.ref-cell { cursor: pointer; border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 6px; }
.ref-cell:hover { box-shadow: var(--shadow-hover); }
.ref-cell-thumb { width: 100%; aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--paper); }
.ref-cell-name { display: block; font-size: 11px; color: var(--muted); margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 移动端:hover 不可用,常显文件名行 + ··· */
@media (max-width: 768px) {
  .img-grid { grid-template-columns: repeat(2, 1fr); gap: 8px; }
  .img-grid.compact { grid-template-columns: repeat(3, 1fr); }
  .hover-panel { display: none; }
  .mobile-bar { display: flex; align-items: center; justify-content: space-between; gap: 4px; padding: 4px 6px 6px; background: var(--card); }
  .m-name { font-size: 11px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .mobile-bar .el-button { min-height: 44px; min-width: 44px; }
  .tb-browse { justify-content: flex-start; }
  .kw-input { width: 100%; }
  .lib-toolbar .el-button { min-height: 44px; }
  .bulk-bar .el-button { min-height: 44px; }
}
</style>