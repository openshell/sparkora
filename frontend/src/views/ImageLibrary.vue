<template>
  <div class="container">
    <div class="page-header">
      <div>
        <span class="page-kicker">Image Library</span>
        <h2 class="serif">图库</h2>
      </div>
      <span class="muted-small">共 {{ total }} 张 · 文章配图在项目「预览」步骤从图库选用</span>
    </div>

    <!-- 上传 + 过滤（S10:筛选全部走服务端,分页 size=24） -->
    <div class="lib-toolbar">
      <el-upload :show-file-list="false" :before-upload="beforeUpload" :http-request="doUpload"
                 accept=".png,.jpg,.jpeg,.webp" multiple>
        <el-button type="primary" :loading="uploading" icon="Upload">上传图片</el-button>
      </el-upload>
      <el-select v-model="projectFilter" clearable placeholder="按项目过滤" class="proj-filter" size="default" @change="onFilterChange">
        <el-option label="全部 / 全局图" :value="''" />
        <el-option v-for="p in projects" :key="p.id" :label="`#${p.id} ${p.topic}`" :value="p.id" />
      </el-select>
      <el-select v-model="sourceFilter" clearable placeholder="按来源过滤" class="src-filter" size="default" @change="onFilterChange">
        <el-option v-for="(label, val) in SOURCE_LABELS" :key="val" :label="label" :value="val" />
      </el-select>
      <el-input v-model="keyword" clearable placeholder="搜索文件名 / 提示词" class="kw-input" size="default"
                :prefix-icon="Search" @input="onKeywordInput" @clear="onFilterChange" />
      <el-button v-if="user.isEditorOrAbove" type="primary" plain icon="MagicStick" @click="aiDrawer = true">AI 生图</el-button>
      <el-button text :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <!-- 加载失败 -->
    <div v-if="loadError && !images.length" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">图库加载失败</div>
      <div class="state-msg">{{ loadError }}</div>
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>

    <div v-else-if="!images.length" class="empty-state">
      <el-empty :description="keyword || sourceFilter || projectFilter !== '' ? '无匹配图片：调整筛选条件' : '图库为空：上传图片，或在项目「预览」步骤用 AI 生成'" :image-size="100" />
    </div>

    <!-- 图库网格 -->
    <template v-else>
      <div class="img-grid">
        <div v-for="img in images" :key="img.id" class="img-card">
          <!-- 网格走缩略图（imageView2/webp），点开大图预览用原图 URL -->
          <el-image :src="imgUrl(img)" fit="cover" class="img-thumb"
                    :preview-src-list="[originUrl(img)]" preview-teleported hide-on-click-modal />
          <div class="img-tags">
            <el-tag size="small" type="info" effect="plain" round>{{ sourceLabel(img.source) }}</el-tag>
            <el-tag size="small" effect="plain" round>{{ projectLabel(img) }}</el-tag>
          </div>
          <div class="img-meta">
            <span class="img-name" :title="img.fileName">{{ img.fileName }}</span>
            <span class="img-dim">{{ img.width && img.height ? `${img.width}×${img.height}` : '' }}</span>
          </div>
          <div class="img-actions">
            <span class="img-own">{{ img.createdBy }} · {{ shortTime(img.createdAt) }}</span>
            <span class="img-ops">
              <el-button v-if="isAiImage(img)" size="small" text type="primary" :loading="regenId === img.id"
                         @click="onRegenerate(img)">重生成</el-button>
              <el-button v-if="user.isEditorOrAbove" size="small" type="danger" plain :loading="deletingId === img.id"
                         @click="onDelete(img)">删除</el-button>
            </span>
          </div>
          <div v-if="img.genModel" class="img-gen">模型 {{ img.genModel }}{{ img.genSize ? ' · ' + img.genSize : '' }}</div>
        </div>
      </div>
      <!-- 分页（S10:服务端分页） -->
      <div class="pager-row">
        <el-pagination v-model:current-page="page" :page-size="size" :total="total"
                       layout="prev, pager, next, total" background @current-change="load" />
      </div>
    </template>

    <!-- AI 生图抽屉（S10 增补:图库页主动生成,产物进全局图库） -->
    <el-drawer v-model="aiDrawer" title="AI 生图" size="420px" class="ai-drawer">
      <el-tabs v-model="aiTab">
        <el-tab-pane label="文生图" name="text2img">
          <el-input v-model="aiPrompt" type="textarea" :rows="3" placeholder="例：俯瞰一杯咖啡与摊开的笔记本，晨光，暖色调，杂志摄影风格" />
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
      <!-- 候选结果:逐张可预览;已全部入库,关闭抽屉即在列表最前 -->
      <div v-if="candidates.length" class="cand-list">
        <div class="cand-tip">本次生成 {{ candidates.length }} 张，已进图库（列表最前）</div>
        <div class="cand-grid">
          <div v-for="img in candidates" :key="img.id" class="cand-cell">
            <el-image :src="imgUrl(img)" fit="cover" class="cand-thumb" :preview-src-list="[originUrl(img)]"
                      preview-teleported hide-on-click-modal />
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { imageApi, projectApi } from '../api'
import { useUserStore } from '../store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, WarningFilled, Search, MagicStick } from '@element-plus/icons-vue'

/** 图库维护页:上传 / 浏览(服务端筛选 + 分页) / 删除。文章配图(选用封面/插图)在项目「预览」步骤进行,两处互不混责。 */
const user = useUserStore()
const SOURCE_LABELS = { upload: '上传', 'ai-text2img': '文生图', 'ai-img2img': '图生图', byd: '比亚迪' }
const images = ref([])
const total = ref(0)
const page = ref(1)
const size = 24
const projects = ref([])          // 供「按项目过滤」下拉
const projectFilter = ref('')
const sourceFilter = ref('')
const keyword = ref('')
const loadError = ref('')
const uploading = ref(false)
const deletingId = ref(null)

const imgUrl = (img) => img?.thumbUrl || img?.url || ''   // 网格缩略图(交付层 webp)
const originUrl = (img) => img?.url || ''                 // 大图预览原图
const sourceLabel = (s) => SOURCE_LABELS[s] || s
const shortTime = (t) => (t || '').slice(5, 16).replace('T', ' ')
const projectLabel = (img) => {
  if (img.projectId == null) return '全局'
  const p = projects.value.find(x => x.id === img.projectId)
  return p ? `#${p.id}` : `#${img.projectId}`
}

// 关键字 300ms 防抖后重查第一页
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
  try {
    const [imgRes, projRes] = await Promise.all([
      imageApi.list({
        page: page.value, size,
        projectId: projectFilter.value || undefined,
        source: sourceFilter.value || undefined,
        keyword: keyword.value.trim() || undefined
      }),
      // 过滤下拉固定拉全量项目名(浅分页足够 MVP)
      projectApi.list({ page: 1, size: 100 })
    ])
    if (imgRes.code === 0) {
      images.value = imgRes.data?.rows || []
      total.value = imgRes.data?.total || 0
    } else loadError.value = imgRes.msg || '加载失败'
    if (projRes.code === 0) projects.value = projRes.data?.rows || []
  } catch (e) {
    loadError.value = e.response?.data?.msg || e.message || '网络异常'
  }
}

const beforeUpload = (file) => {
  // 只拦「声明了 MIME 且不是图片」与超限;MIME 为空或罕见变体(pjpeg 等)放行,统一交后端魔数校验定夺
  if (file.type && !/^image\/(png|jpe?g|pjpeg|webp)$/i.test(file.type)) {
    ElMessage.error('仅支持 png/jpg/webp 格式'); return false
  }
  if (file.size > 10 * 1024 * 1024) { ElMessage.error('图片超过 10MB 上限'); return false }
  return true
}
const doUpload = async ({ file }) => {
  uploading.value = true
  try {
    // 全局图库:不带 projectId
    const res = await imageApi.upload(undefined, file)
    if (res.code === 0) {
      // S10:内容哈希命中已有记录时不重复上传,提示复用
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

// ==== S10:AI 图一键重生成(同 prompt/尺寸产新图,新图置顶) + 上传去重提示 ====
const isAiImage = (img) => img.source === 'ai-text2img' || img.source === 'ai-img2img'
const regenId = ref(null)
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

// ==== S10 增补:图库页 AI 生图(文生图/图生图,产物进全局图库;复用 generate 接口,projectId 传 null) ====
const aiDrawer = ref(false)
const aiTab = ref('text2img')       // text2img | img2img
const aiPrompt = ref('')
const aiSize = ref('1024x1024')
const aiCount = ref(1)               // 1/2/4 张候选
const refImage = ref(null)           // 图生图参考图(从当前列表选)
const refDialog = ref(false)
const generating = ref(false)
const candidates = ref([])           // 本次生成候选(已入库)

const chooseRef = (img) => { refImage.value = img; refDialog.value = false }

const afterGenerated = async (list) => {
  candidates.value = list
  const reused = list.some(img => img.dedupeHit)
  ElMessage.success(reused ? `生成 ${list.length} 张（部分与图库重复，已复用）` : `生成成功 ${list.length} 张，已进图库`)
  page.value = 1
  await load()
}

const onGenerateText = async () => {
  if (!aiPrompt.value.trim()) { ElMessage.warning('请输入画面描述'); return }
  generating.value = true
  try {
    // 全局图库:projectId 传 null
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

onMounted(load)
</script>

<style scoped>
.page-header { align-items: baseline; justify-content: space-between; display: flex; flex-wrap: wrap; gap: 8px; }
.page-header h2 { margin: 2px 0 0; }
.muted-small { color: var(--muted); font-size: 12px; }
.lib-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.proj-filter { width: 220px; }
.src-filter { width: 140px; }
.kw-input { width: 200px; }
.pager-row { display: flex; justify-content: center; margin-top: 18px; }
.state-error { padding: 36px 16px; }
.state-title { font-weight: 700; margin: 8px 0 4px; }
.state-msg { color: var(--muted); font-size: 13px; margin-bottom: 12px; }
.empty-state { padding: 30px 0; }

.img-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 14px; }
.img-card { border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px; background: var(--card); transition: box-shadow .2s; }
.img-card:hover { box-shadow: var(--shadow-hover); }
.img-thumb { width: 100%; aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--paper); }
.img-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 8px; }
.img-meta { display: flex; justify-content: space-between; gap: 6px; margin: 6px 0; font-size: 12px; color: var(--muted); }
.img-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.img-actions { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.img-own { font-size: 12px; color: var(--faint); }
.img-ops { display: flex; gap: 4px; }
.img-gen { font-size: 11px; color: var(--faint); margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* AI 生图抽屉(S10 增补) */
.ai-row { display: flex; gap: 8px; margin-top: 10px; }
.size-select { flex: 1; }
.n-select { width: 90px; flex: none; }
.gen-btn { width: 100%; margin-top: 10px; min-height: 44px; }
.ref-pick { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.ref-thumb { width: 72px; height: 54px; object-fit: cover; border-radius: var(--radius-sm); border: 1px solid var(--line); }
.cand-list { margin-top: 14px; border-top: 1px solid var(--line); padding-top: 10px; }
.cand-tip { font-size: 12px; color: var(--muted); margin-bottom: 8px; }
.cand-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.cand-thumb { width: 100%; aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--paper); }
.img-pop-empty { font-size: 13px; color: var(--muted); padding: 8px 0; }
.ref-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; max-height: 60vh; overflow-y: auto; }
.ref-cell { cursor: pointer; border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 6px; }
.ref-cell:hover { box-shadow: var(--shadow-hover); }
.ref-cell-thumb { width: 100%; aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--paper); }
.ref-cell-name { display: block; font-size: 11px; color: var(--muted); margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 768px) {
  .img-grid { grid-template-columns: repeat(2, 1fr); }
  .proj-filter, .src-filter, .kw-input { width: 100%; }
  .img-actions .el-button { min-height: 44px; }
}
</style>