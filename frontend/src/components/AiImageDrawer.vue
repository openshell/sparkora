<template>
  <!-- 动态外壳：preview 模式内联（宿主「配图」抽屉内已有一层抽屉，不能再叠一层）；
       library 模式独立抽屉（v-model 控制显隐）。生图 UI 两处共用同一份模板，避免重复。 -->
  <component :is="isInline ? 'div' : ElDrawer" v-bind="wrapperAttrs">
    <div class="ai-body">
      <el-tabs v-model="aiTab" class="ai-tabs">
        <!-- 文生图 -->
        <el-tab-pane label="文生图" name="text2img">
          <el-input v-model="t2iPrompt" type="textarea" :rows="3"
                    placeholder="例：俯瞰一杯与摊开的笔记本，晨光，暖色调，杂志摄影风格" />
          <div class="ai-row">
            <el-select v-model="genSize" class="size-select">
              <el-option label="方图 1024×1024" value="1024x1024" />
              <el-option label="横图 1536×1024" value="1536x1024" />
              <el-option label="竖图 1024×1536" value="1024x1536" />
            </el-select>
            <el-select v-model="genCount" class="n-select">
              <el-option label="1 张" :value="1" />
              <el-option label="2 张" :value="2" />
              <el-option label="4 张" :value="4" />
            </el-select>
          </div>
          <el-button type="primary" class="gen-btn" :loading="generating" :disabled="generating" @click="onGenerateText">
            {{ generating ? '生成中…' : '生成候选' }}
          </el-button>
        </el-tab-pane>

        <!-- 图生图：参考图三来源（粘贴 / 本地文件 / 图库），统一本地预览 -->
        <el-tab-pane label="图生图" name="img2img">
          <div class="ref-zone" :class="{ 'is-empty': !refReady, 'is-over': dragOver }"
               @dragover.prevent="onDragOver" @dragleave.prevent="onDragLeave" @drop.prevent="onDrop">
            <template v-if="refReady">
              <img :src="refPreview" class="ref-preview" alt="参考图" />
              <div class="ref-info">
                <div class="ref-name" :title="refFileName">{{ refFileName }}</div>
                <div class="ref-src">{{ refSourceLabel }}</div>
                <div class="ref-ops">
                  <el-button size="small" @click="openFilePicker">更换</el-button>
                  <el-button size="small" text type="danger" :icon="Delete" @click="clearRef">移除</el-button>
                </div>
              </div>
            </template>
            <template v-else>
              <el-icon class="ref-empty-icon" :size="26"><PictureFilled /></el-icon>
              <div class="ref-empty-title">添加参考图</div>
              <div class="ref-empty-hint">粘贴（Ctrl / ⌘ + V）· 拖入图片 · 或从下方选择</div>
              <div class="ref-empty-actions">
                <el-button plain size="small" :icon="Upload" @click="openFilePicker">本地文件</el-button>
                <el-button plain size="small" :icon="Picture" @click="openRefDialog">从图库选择</el-button>
              </div>
            </template>
          </div>
          <el-input v-model="i2iPrompt" type="textarea" :rows="3"
                    placeholder="例：保持构图，改为蓝灰色科技感色调" />
          <div class="ai-row">
            <el-select v-model="genSize" class="size-select">
              <el-option label="方图 1024×1024" value="1024x1024" />
              <el-option label="横图 1536×1024" value="1536x1024" />
              <el-option label="竖图 1024×1536" value="1024x1536" />
            </el-select>
            <el-select v-model="genCount" class="n-select">
              <el-option label="1 张" :value="1" />
              <el-option label="2 张" :value="2" />
              <el-option label="4 张" :value="4" />
            </el-select>
          </div>
          <el-button type="primary" class="gen-btn" :disabled="!refReady || generating" :loading="generating"
                     @click="onGenerateFromImage">
            {{ generating ? '生成中…' : '生成候选' }}
          </el-button>
        </el-tab-pane>
      </el-tabs>

      <!-- 本地文件选择（隐藏 input；粘贴/拖拽之外的第三条本地路径） -->
      <input ref="fileInput" type="file" accept=".png,.jpg,.jpeg,.webp" class="hidden-input" @change="onFileInput" />

      <!-- 生成中骨架 -->
      <div v-if="generating" class="cand-list">
        <div class="cand-tip">生成中，请稍候…（约 10~30 秒）</div>
        <el-skeleton :rows="3" animated />
      </div>

      <!-- 候选结果：预览页可插入/设封面；图库页定位到列表；均可重生成 -->
      <div v-else-if="candidates.length" class="cand-list">
        <div class="cand-tip">
          <template v-if="mode === 'preview'">本次生成 {{ candidates.length }} 张候选：点击插入正文，或设为封面</template>
          <template v-else>本次生成 {{ candidates.length }} 张，已进图库（点击「定位到列表」查看）</template>
        </div>
        <div class="cand-grid">
          <div v-for="img in candidates" :key="img.id" class="cand-cell">
            <el-image :src="thumbOf(img)" fit="cover" class="cand-thumb"
                      :preview-src-list="[originOf(img)]" preview-teleported hide-on-click-modal />
            <span class="cand-id">#{{ img.id }}</span>
            <div class="cand-actions">
              <template v-if="mode === 'preview'">
                <el-button size="small" type="primary" plain @click="emit('insert', img)">插入正文</el-button>
                <el-button v-if="showCoverAction" size="small" @click="emit('set-cover', img.id)">设为封面</el-button>
              </template>
              <el-button v-else size="small" type="primary" plain @click="emit('locate', img)">定位到列表</el-button>
              <!-- 重生成分支：会话缓存命中 → 复用参考图直传；否则图库/文生图走后端 /regenerate；
                   本地上传来源且缓存失效 → 置灰 + tooltip（参考图未入库，后端无法复现）。 -->
              <el-tooltip :disabled="canRegenerate(img)" content="参考图未入库且会话缓存已失效，无法重生成" placement="top">
                <span class="regen-wrap">
                  <el-button size="small" text type="primary" :disabled="!canRegenerate(img)"
                             :loading="regeneratingId === img.id" @click="onRegenerate(img)">重生成</el-button>
                </span>
              </el-tooltip>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 参考图选择弹窗：独立数据源 + 页内搜索（300ms 防抖）+ 分页，不复用宿主主列表 -->
    <el-dialog v-model="refDialog" title="选择参考图" width="720px" class="ref-dialog" append-to-body>
      <el-input v-model="refKeyword" clearable placeholder="搜索文件名 / 提示词" :prefix-icon="Search"
                class="ref-kw" @input="onRefKeywordInput" @clear="onRefSearch" />
      <div v-if="refLoading" class="img-pop-empty">加载中…</div>
      <div v-else-if="!refImages.length" class="img-pop-empty">无匹配图片：换个关键字试试</div>
      <div v-else class="ref-grid">
        <div v-for="img in refImages" :key="img.id" class="ref-cell" @click="chooseRef(img)">
          <el-image :src="thumbOf(img)" fit="cover" class="ref-cell-thumb" />
          <span class="ref-cell-name">#{{ img.id }} {{ img.fileName }}</span>
        </div>
      </div>
      <div v-if="refTotal > REF_SIZE" class="ref-pager">
        <el-pagination v-model:current-page="refPage" :page-size="REF_SIZE" :total="refTotal"
                       layout="prev, pager, next" small background @current-change="loadRefImages" />
      </div>
    </el-dialog>
  </component>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElDrawer, ElMessage } from 'element-plus'
import { Upload, Picture, PictureFilled, Delete, Search } from '@element-plus/icons-vue'
import { imageApi } from '../api'
import * as imageRefCache from '../utils/imageRefCache'

/**
 * 共用的 AI 生图面板（09-26 image-gen-drawer-ux）：
 *  - library 模式：独立抽屉（`v-model`），生成后「定位到列表」（图库页 ImageLibrary.vue）。
 *  - preview 模式：内联面板，嵌在预览页「配图」抽屉的 AI 生图 tab 内，生成后「插入正文 / 设为封面」。
 *
 * 参考图三来源：粘贴（Ctrl/⌘+V）/ 本地文件 / 图库选图。
 *  - 图库来源 → 旧 JSON 接口 generateFromImage(refImageId)（无回归）。
 *  - 粘贴 / 本地来源 → 新 multipart 接口 generateFromImageUpload（参考图不落图库）。
 * 重生成：会话缓存命中 → 复用参考图 + prompt 走 multipart；图库/文生图 → 后端 /regenerate；
 *         本地上传来源且缓存失效 → 置灰 + tooltip。
 * 硬约束：系统只产候选，写入由宿主在用户点击后执行（组件仅 emit，不写宿主状态）。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  projectId: { type: [String, Number], default: null },
  mode: { type: String, default: 'library' },   // 'library' | 'preview'
  presetTags: { type: Array, default: () => [] },
  showCoverAction: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'generated', 'insert', 'set-cover', 'locate'])

const isInline = computed(() => props.mode === 'preview')
/** 关闭抽屉（el-drawer close 事件 / 遮罩点击）。 */
const onVisibleChange = (v) => emit('update:modelValue', v)
/** 抽屉外壳属性：preview 用普通 div（class 仅作样式钩子），library 用 el-drawer。 */
const wrapperAttrs = computed(() => (isInline.value
  ? { class: 'ai-drawer-inline' }
  : { class: 'ai-drawer', title: 'AI 生图', size: '420px', modelValue: props.modelValue, 'onUpdate:modelValue': onVisibleChange }))
// ==== 生成参数 / 状态（文生图与图生图 prompt 独立 ref，切 tab 不串内容） ====
const aiTab = ref('text2img')
const t2iPrompt = ref('')
const i2iPrompt = ref('')
const genSize = ref('1024x1024')
const genCount = ref(1)
const generating = ref(false)
const regeneratingId = ref(null)
const candidates = ref([])

// ==== 参考图（三来源统一为「本地预览 + 提交路径分支」） ====
const refSource = ref(null)          // null | 'library' | 'local'
const refLibraryImage = ref(null)    // 图库来源：图库实体（走 refImageId）
const refFile = ref(null)            // 本地/粘贴来源：File（走 multipart）
const refPreviewUrl = ref('')        // 本地来源为 ObjectURL；图库来源为 thumbUrl||url
const refFileName = ref('')
const refReady = computed(() => refSource.value === 'local' ? !!refFile.value : !!refLibraryImage.value)
const refPreview = computed(() => (refSource.value === 'library'
  ? thumbOf(refLibraryImage.value)
  : refPreviewUrl.value))
const refSourceLabel = computed(() => (refSource.value === 'library' ? '来自图库' : '本地图片（不会存入图库）'))

/** 释放本地预览 ObjectURL（仅 blob:；图库来源的是 http URL，不 revoke）。 */
const revokePreview = () => {
  const u = refPreviewUrl.value
  if (u && u.startsWith('blob:')) {
    try { URL.revokeObjectURL(u) } catch (e) { /* 已释放：忽略 */ }
  }
}
/** 清空参考图（替换/移除/卸载时调用）：释放 ObjectURL，重置三来源状态。 */
const clearRef = () => {
  revokePreview()
  refPreviewUrl.value = ''
  refFile.value = null
  refLibraryImage.value = null
  refFileName.value = ''
  refSource.value = null
  dragOver.value = false
}
/** 按 MIME 推导扩展名（后端按 multipart 文件名扩展名校验白名单）。 */
const extOfMime = (mime) => {
  const m = String(mime || '').toLowerCase()
  if (m.includes('png')) return 'png'
  if (m.includes('webp')) return 'webp'
  if (m.includes('jpeg') || m.includes('jpg')) return 'jpg'
  return ''
}
/**
 * 本地/粘贴来源：类型与大小前置校验（与后端 upload 同口径），生成本地预览 URL。
 * 剪贴板 File 的名字可能是 `image`/`blob` 等无扩展名形式，而后端按 multipart 文件名扩展名做白名单校验——
 * 故无有效扩展名时按 MIME 重命名（`new File`），否则会出现「前端校验通过、后端 400 仅支持 png/jpg/webp」。
 */
const setLocalRef = (file, name) => {
  if (!file) return
  if (!/^image\/(png|jpe?g|pjpeg|webp)$/i.test(file.type)) { ElMessage.error('仅支持 png/jpg/webp 格式'); return }
  if (file.size > 10 * 1024 * 1024) { ElMessage.error('参考图超过 10MB 上限'); return }
  const hasValidExt = /\.(png|jpe?g|webp)$/i.test(file.name || '')
  const uploadFile = hasValidExt
    ? file
    : new File([file], `reference.${extOfMime(file.type) || 'png'}`, { type: file.type || 'image/png' })
  clearRef()
  try { refPreviewUrl.value = URL.createObjectURL(uploadFile) } catch (e) { refPreviewUrl.value = '' }
  refFile.value = uploadFile
  refFileName.value = name || file.name || '参考图'
  refSource.value = 'local'
}
/** 图库来源：记录实体 id，走旧 JSON 接口（无 ObjectURL 需要管理）。 */
const setLibraryRef = (img) => {
  if (!img) return
  clearRef()
  refLibraryImage.value = img
  refFileName.value = img.fileName || `#${img.id}`
  refSource.value = 'library'
}

// ==== 粘贴 / 拖拽 / 本地文件 ====
const dragOver = ref(false)
const onDragOver = () => { dragOver.value = true }
const onDragLeave = () => { dragOver.value = false }
const onDrop = (e) => {
  dragOver.value = false
  const files = e.dataTransfer?.files
  if (!files || !files.length) return
  const img = [...files].find(f => f.type.startsWith('image/'))
  if (!img) { ElMessage.error('仅支持 png/jpg/webp 格式'); return }
  setLocalRef(img, img.name)
}
/** 抽屉打开时监听 window paste：剪贴板含图片才接管（文本粘贴不受影响）。 */
const onWindowPaste = (e) => {
  const cb = e.clipboardData
  if (!cb) return
  let file = null
  const items = cb.items
  if (items && items.length) {
    for (const it of items) {
      if (it.kind === 'file' && it.type && it.type.startsWith('image/')) { file = it.getAsFile(); break }
    }
  }
  if (!file && cb.files && cb.files.length) file = [...cb.files].find(f => f.type.startsWith('image/'))
  if (!file) return   // 无图片：交给浏览器默认处理（文本框粘贴等）
  e.preventDefault()
  setLocalRef(file, '粘贴的图片.png')
  aiTab.value = 'img2img'   // 粘贴意图明确是参考图：自动切到图生图
  ElMessage.success('已从剪贴板读取参考图')
}

const fileInput = ref(null)
const openFilePicker = () => fileInput.value?.click()
const onFileInput = (e) => {
  const f = e.target.files?.[0]
  e.target.value = ''   // 允许重复选同一文件
  if (f) setLocalRef(f, f.name)
}

// ==== 参考图选择弹窗（独立数据源 + 防抖 + 分页） ====
const REF_SIZE = 24
const refDialog = ref(false)
const refImages = ref([])
const refTotal = ref(0)
const refPage = ref(1)
const refKeyword = ref('')
const refLoading = ref(false)
let refKwTimer = null
const openRefDialog = () => {
  refDialog.value = true
  refPage.value = 1
  loadRefImages()
}
const onRefKeywordInput = () => {
  clearTimeout(refKwTimer)
  refKwTimer = setTimeout(onRefSearch, 300)
}
const onRefSearch = () => {
  clearTimeout(refKwTimer)
  refPage.value = 1
  loadRefImages()
}
const loadRefImages = async () => {
  refLoading.value = true
  try {
    const res = await imageApi.list({ page: refPage.value, size: REF_SIZE, keyword: refKeyword.value.trim() || undefined })
    if (res.code === 0) {
      refImages.value = res.data?.rows || []
      refTotal.value = res.data?.total || 0
    } else ElMessage.error(res.msg || '参考图加载失败')
  } catch (e) {
    ElMessage.error('参考图加载失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { refLoading.value = false }
}
const chooseRef = (img) => {
  setLibraryRef(img)
  refDialog.value = false
}

// ==== 生成 ====
/** 图库候选缩略图；大图预览/插入正文必须用原图 url（webp 派生图微信素材接口不支持）。 */
const thumbOf = (img) => img?.thumbUrl || img?.url || ''
const originOf = (img) => img?.url || ''

/** 统一收口：成功则展示候选、写会话缓存（仅本地来源）、通知宿主刷新。
 *  `reason` 供宿主区分「首次生成」与「重生成」（预览页仅首次生成沿用 n=1 自动插入正文行为）。 */
const doGenerate = async (req, ctx) => {
  generating.value = true
  try {
    const res = await req
    if (res.code !== 0) { ElMessage.error(res.msg || '生成失败'); return }
    const list = res.data || []
    candidates.value = list
    // 本地/粘贴来源：每张结果图 id → 参考图信息，供同会话「重生成」复用。
    // size/tags/projectId 用提交时快照（ctx），不用 await 后的响应式值——生成期间用户仍可改尺寸/标签，
    // 若读取当前值会缓存下「与本次生成不符」的参数，重生成产出与预期不同。
    if (ctx?.localRef && ctx.refFile) {
      for (const n of list) {
        imageRefCache.put(n.id, {
          file: ctx.refFile,
          fileName: ctx.refFileName,
          prompt: ctx.prompt,
          size: ctx.size,
          tags: ctx.tags,
          projectId: ctx.projectId
        })
      }
    }
    const reused = list.some(img => img.dedupeHit)
    if (reused) ElMessage.success(`生成 ${list.length} 张（部分与图库重复，已复用）`)
    else ElMessage.success(`生成成功 ${list.length} 张，已进图库`)
    emit('generated', list, { reason: 'generate' })
  } catch (e) {
    ElMessage.error('生成失败：' + (e.response?.data?.msg || e.message || '网络异常或超时'))
  } finally { generating.value = false }
}

const onGenerateText = () => {
  const p = t2iPrompt.value.trim()
  if (!p) { ElMessage.warning('请输入画面描述'); return }
  doGenerate(imageApi.generateText(props.projectId, p, genSize.value, genCount.value, props.presetTags))
}
const onGenerateFromImage = () => {
  if (!refReady.value) { ElMessage.warning('请先添加参考图'); return }
  const p = i2iPrompt.value.trim()
  if (!p) { ElMessage.warning('请输入画面描述'); return }
  if (refSource.value === 'library') {
    // 图库来源：旧 JSON 接口（refImageId），行为与既有一致
    doGenerate(imageApi.generateFromImage(props.projectId, refLibraryImage.value.id, p, genSize.value, genCount.value, props.presetTags))
  } else {
    // 本地/粘贴来源：新 multipart 接口（参考图不落图库）
    // 提交时快照 size/tags（生成期间 select 仍可交互），缓存与本次请求参数保持一致
    doGenerate(
      imageApi.generateFromImageUpload(props.projectId, refFile.value, p, genSize.value, genCount.value, props.presetTags),
      {
        localRef: true,
        refFile: refFile.value,
        refFileName: refFileName.value,
        prompt: p,
        size: genSize.value,
        tags: [...(props.presetTags || [])],
        projectId: props.projectId ?? null
      }
    )
  }
}

// ==== 重生成分支 ====
/** 可用性：会话缓存命中（本地来源可复用参考图）／文生图（prompt 可复现）／图库图生图（refImageId 可复用）。 */
const canRegenerate = (img) => {
  if (!img) return false
  if (imageRefCache.has(img.id)) return true
  if (img.source === 'ai-text2img') return true
  if (img.source === 'ai-img2img' && img.refImageId != null) return true
  return false
}
const onRegenerate = async (img) => {
  if (!canRegenerate(img)) return
  regeneratingId.value = img.id
  try {
    const cached = imageRefCache.get(img.id)
    let res
    if (cached && cached.file) {
      // 缓存命中：同参考图 + 同 prompt 走 multipart（对齐后端 /regenerate 语义）
      res = await imageApi.generateFromImageUpload(cached.projectId, cached.file, cached.prompt, cached.size, 1, cached.tags)
    } else {
      res = await imageApi.regenerate(img.id)
    }
    if (res.code !== 0) { ElMessage.error(res.msg || '重新生成失败'); return }
    const list = res.data || []
    if (!list.length) { ElMessage.warning('未生成新图'); return }
    // 缓存链：新图同样可用同一参考图再重生成（仅本地来源持有 file）
    if (cached && cached.file) {
      for (const n of list) {
        imageRefCache.put(n.id, {
          file: cached.file, fileName: cached.fileName, prompt: cached.prompt,
          size: cached.size, tags: cached.tags, projectId: cached.projectId
        })
      }
    }
    candidates.value = list
    ElMessage.success('已重新生成')
    emit('generated', list, { reason: 'regenerate' })
  } catch (e) {
    ElMessage.error('重新生成失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { regeneratingId.value = null }
}

// ==== 粘贴监听：抽屉打开时挂 window，关闭/卸载时卸 ====
watch(() => props.modelValue, (open) => {
  if (open) window.addEventListener('paste', onWindowPaste)
  else window.removeEventListener('paste', onWindowPaste)
})
onMounted(() => { if (props.modelValue) window.addEventListener('paste', onWindowPaste) })
onBeforeUnmount(() => {
  window.removeEventListener('paste', onWindowPaste)
  clearTimeout(refKwTimer)
  clearRef()   // 释放本地预览 ObjectURL
})
</script>

<style scoped>
.ai-body { min-height: 200px; }
.hidden-input { display: none; }

.ai-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.ai-tabs :deep(.el-tabs__nav-wrap)::after { height: 1px; }

.ai-row { display: flex; gap: 8px; margin-top: 10px; align-items: center; }
.ai-row .size-select { flex: 1; min-width: 0; }
.ai-row .n-select { width: 90px; flex: none; }
.gen-btn { width: 100%; margin-top: 10px; min-height: 44px; }

/* 参考图区：空态为拖拽/粘贴投放区，有图时为预览 + 更换/移除 */
.ref-zone {
  display: flex; align-items: center; gap: 12px;
  padding: 10px; border: 1px dashed var(--line); border-radius: var(--radius-sm);
  background: var(--el-fill-color-light); margin-bottom: 10px;
  transition: border-color .2s, background-color .2s;
}
.ref-zone.is-over { border-color: var(--brand, var(--el-color-primary)); background: var(--brand-weak, var(--el-fill-color)); }
.ref-zone.is-empty { flex-direction: column; text-align: center; padding: 16px 10px; gap: 6px; }
.ref-preview { width: 84px; height: 63px; object-fit: cover; border-radius: var(--radius-sm); border: 1px solid var(--line); flex: none; background: var(--paper); }
.ref-info { flex: 1; min-width: 0; }
.ref-name { font-size: 13px; font-weight: 600; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ref-src { font-size: 11px; color: var(--muted); margin: 2px 0 6px; }
.ref-ops { display: flex; gap: 6px; flex-wrap: wrap; }
.ref-empty-icon { color: var(--faint); }
.ref-empty-title { font-size: 14px; font-weight: 600; color: var(--ink); }
.ref-empty-hint { font-size: 12px; color: var(--muted); margin-bottom: 6px; }
.ref-empty-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; }

/* 候选网格 */
.cand-list { margin-top: 14px; border-top: 1px solid var(--line); padding-top: 10px; }
.cand-tip { font-size: 12px; color: var(--muted); margin-bottom: 8px; }
.cand-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.cand-cell { position: relative; border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 6px; }
.cand-thumb { width: 100%; aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--paper); display: block; }
.cand-id { position: absolute; top: 8px; left: 8px; font-size: 11px; color: #fff; background: rgba(0,0,0,.55); padding: 1px 6px; border-radius: 4px; pointer-events: none; }
.cand-actions { display: flex; gap: 4px; margin-top: 6px; flex-wrap: wrap; }
.regen-wrap { display: inline-flex; }

/* 参考图选择弹窗 */
.ref-kw { margin-bottom: 12px; }
.img-pop-empty { font-size: 13px; color: var(--muted); padding: 8px 0; }
.ref-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; max-height: 60vh; overflow-y: auto; }
.ref-cell { cursor: pointer; border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 6px; }
.ref-cell:hover { box-shadow: var(--shadow-hover); }
.ref-cell-thumb { width: 100%; aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--paper); }
.ref-cell-name { display: block; font-size: 11px; color: var(--muted); margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ref-pager { display: flex; justify-content: center; margin-top: 12px; }

/* 移动端：单列候选、触控目标 ≥44px */
@media (max-width: 768px) {
  .cand-grid { grid-template-columns: 1fr; }
  .ref-empty-actions .el-button,
  .ref-ops .el-button,
  .cand-actions .el-button { min-height: 44px; }
  .ref-zone.is-empty { padding: 20px 12px; }
}
</style>
