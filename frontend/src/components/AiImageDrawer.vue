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

        <!-- 图生图：参考图多来源（粘贴 / 本地文件 / 图库），支持多张（≤4），统一本地预览 -->
        <el-tab-pane label="图生图" name="img2img">
          <div class="ref-zone" :class="{ 'is-empty': !refReady, 'is-over': dragOver }"
               @dragover.prevent="onDragOver" @dragleave.prevent="onDragLeave" @drop.prevent="onDrop">
            <template v-if="refReady">
              <div class="ref-head">
                <span class="ref-count">参考图 {{ refs.length }}/{{ REF_MAX }}</span>
                <el-button size="small" text type="danger" :icon="Delete" @click="clearRef">清空</el-button>
              </div>
              <div class="ref-thumbs">
                <div v-for="r in orderedRefs" :key="r.uid" class="ref-item">
                  <img :src="r.kind === 'library' ? thumbOf(r.libraryImage) : r.previewUrl"
                       class="ref-thumb" :alt="r.name" />
                  <span class="ref-badge">{{ r.kind === 'library' ? '图库' : '本地' }}</span>
                  <el-button class="ref-del" size="small" circle :icon="Delete" :aria-label="`移除 ${r.name}`"
                             @click="removeRef(r.uid)" />
                  <div class="ref-item-name" :title="r.name">{{ r.name }}</div>
                </div>
              </div>
              <div class="ref-ops">
                <el-button plain size="small" :icon="Upload" :disabled="refs.length >= REF_MAX"
                           @click="openFilePicker">本地文件</el-button>
                <el-button plain size="small" :icon="Picture" :disabled="refs.length >= REF_MAX"
                           @click="openRefDialog">从图库选择</el-button>
              </div>
            </template>
            <template v-else>
              <el-icon class="ref-empty-icon" :size="26"><PictureFilled /></el-icon>
              <div class="ref-empty-title">添加参考图</div>
              <div class="ref-empty-hint">粘贴（Ctrl / ⌘ + V）· 拖入图片 · 或从下方选择（最多 {{ REF_MAX }} 张）</div>
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
          <el-button type="primary" class="gen-btn" :disabled="generating" :loading="generating"
                     @click="onGenerateFromImage">
            {{ generating ? '生成中…' : '生成候选' }}
          </el-button>
        </el-tab-pane>
      </el-tabs>

      <!-- 本地文件选择（隐藏 input；粘贴/拖拽之外的第三条本地路径，支持多选） -->
      <input ref="fileInput" type="file" accept=".png,.jpg,.jpeg,.webp" multiple class="hidden-input" @change="onFileInput" />

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

    <!-- 参考图选择弹窗：独立数据源 + 页内搜索（300ms 防抖）+ 分页，不复用宿主主列表；多选追加 -->
    <el-dialog v-model="refDialog" title="选择参考图（可多选）" width="720px" class="ref-dialog" append-to-body>
      <el-input v-model="refKeyword" clearable placeholder="搜索文件名 / 提示词" :prefix-icon="Search"
                class="ref-kw" @input="onRefKeywordInput" @clear="onRefSearch" />
      <div class="ref-dialog-tip">已选 {{ pickedIds.length }} 张；确认后追加到参考图（总数上限 {{ REF_MAX }}）</div>
      <div v-if="refLoading" class="img-pop-empty">加载中…</div>
      <div v-else-if="!refImages.length" class="img-pop-empty">无匹配图片：换个关键字试试</div>
      <div v-else class="ref-grid">
        <div v-for="img in refImages" :key="img.id" class="ref-cell"
             :class="{ 'is-picked': pickedIds.includes(img.id) }" @click="togglePick(img)">
          <el-image :src="thumbOf(img)" fit="cover" class="ref-cell-thumb" />
          <span v-if="pickedIds.includes(img.id)" class="ref-cell-check">
            <el-icon><Select /></el-icon>
          </span>
          <span class="ref-cell-name">#{{ img.id }} {{ img.fileName }}</span>
        </div>
      </div>
      <div v-if="refTotal > REF_SIZE" class="ref-pager">
        <el-pagination v-model:current-page="refPage" :page-size="REF_SIZE" :total="refTotal"
                       layout="prev, pager, next" small background @current-change="loadRefImages" />
      </div>
      <template #footer>
        <el-button @click="refDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!pickedIds.length" @click="confirmRefPicks">
          添加 {{ pickedIds.length ? `(${pickedIds.length})` : '' }}
        </el-button>
      </template>
    </el-dialog>
  </component>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElDrawer, ElMessage } from 'element-plus'
import { Upload, Picture, PictureFilled, Delete, Search, Select } from '@element-plus/icons-vue'
import { imageApi } from '../api'
import * as imageRefCache from '../utils/imageRefCache'

/**
 * 共用的 AI 生图面板（09-26 image-gen-drawer-ux）：
 *  - library 模式：独立抽屉（`v-model`），生成后「定位到列表」（图库页 ImageLibrary.vue）。
 *  - preview 模式：内联面板，嵌在预览页「配图」抽屉的 AI 生图 tab 内，生成后「插入正文 / 设为封面」。
 *
 * 参考图多来源（≤4）：粘贴（Ctrl/⌘+V，可多张）/ 本地文件（多选）/ 拖入（多张）/ 图库选图（多选），
 * 可混合、可逐张移除；展示按「本地在前、图库在后」分组，与后端「先 files 后 refImageIds」提交顺序一致。
 *  - 统一走多图 multipart 接口 generateFromImageUpload（参考图不落图库）。
 *  - 仅 1 张且来源为图库时，后端落 ref_image_id → 后端 /regenerate 仍可用（单图能力不回归）。
 * 重生成：会话缓存命中整组参考图 → 复用 files/ids + prompt 走多图接口；文生图 / refImageId 非空 → 后端 /regenerate；
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

// ==== 参考图（多来源统一为有序列表，界面顺序 == 提交顺序） ====
/** 参考图上限（与后端 generateImage2ImageFromUpload 的 1~4 校验一致）。 */
const REF_MAX = 4
/** 每项：{ uid, kind:'local'|'library', file?, previewUrl?, libraryImage?, name, id? }，有序。 */
const refs = ref([])
let refUid = 0
/** 本地组在前、图库组在后——与后端「先 files 后 refImageIds」的提交顺序天然一致（设计口径）。 */
const orderedRefs = computed(() => [
  ...refs.value.filter(r => r.kind === 'local'),
  ...refs.value.filter(r => r.kind === 'library')
])
const localRefs = computed(() => orderedRefs.value.filter(r => r.kind === 'local'))
const libraryRefs = computed(() => orderedRefs.value.filter(r => r.kind === 'library'))
const refReady = computed(() => refs.value.length > 0)
/** 释放单项的本地预览 ObjectURL（仅 blob:；图库来源不持有 ObjectURL）。 */
const revokeRef = (r) => {
  const u = r?.previewUrl
  if (u && u.startsWith('blob:')) {
    try { URL.revokeObjectURL(u) } catch (e) { /* 已释放：忽略 */ }
  }
}
/** 清空参考图（清空/卸载时调用）：释放全部本地预览 ObjectURL。 */
const clearRef = () => {
  refs.value.forEach(revokeRef)
  refs.value = []
  dragOver.value = false
}
/** 逐张移除（按 uid；重排后 uid 仍稳定，不误删同名项）。 */
const removeRef = (uid) => {
  const idx = refs.value.findIndex(r => r.uid === uid)
  if (idx < 0) return
  revokeRef(refs.value[idx])
  refs.value.splice(idx, 1)
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
 * 追加本地/粘贴来源：类型与大小前置校验（与后端 upload 同口径），生成本地预览 URL。
 * 剪贴板 File 的名字可能是 `image`/`blob` 等无扩展名形式，而后端按 multipart 文件名扩展名做白名单校验——
 * 故无有效扩展名时按 MIME 重命名（`new File`），否则会出现「前端校验通过、后端 400 仅支持 png/jpg/webp」。
 * @returns {boolean} 是否成功追加
 */
const appendLocalRef = (file, name) => {
  if (!file) return false
  if (!/^image\/(png|jpe?g|pjpeg|webp)$/i.test(file.type)) { ElMessage.error('仅支持 png/jpg/webp 格式'); return false }
  if (file.size > 10 * 1024 * 1024) { ElMessage.error('参考图超过 10MB 上限'); return false }
  const hasValidExt = /\.(png|jpe?g|webp)$/i.test(file.name || '')
  const uploadFile = hasValidExt
    ? file
    : new File([file], `reference.${extOfMime(file.type) || 'png'}`, { type: file.type || 'image/png' })
  let previewUrl = ''
  try { previewUrl = URL.createObjectURL(uploadFile) } catch (e) { previewUrl = '' }
  refs.value.push({
    uid: `local-${++refUid}`,
    kind: 'local',
    file: uploadFile,
    previewUrl,
    name: name || file.name || '参考图'
  })
  return true
}
/** 追加图库来源（记录实体 id，无 ObjectURL 需要管理）。 */
const appendLibraryRef = (img) => {
  if (!img || img.id == null) return false
  if (refs.value.some(r => r.kind === 'library' && r.id === img.id)) return false   // 同图库图去重
  refs.value.push({
    uid: `library-${++refUid}`,
    kind: 'library',
    libraryImage: img,
    id: img.id,
    name: img.fileName || `#${img.id}`
  })
  return true
}
/** 批量追加本地文件（多选/拖拽/粘贴共用）：逐张校验，超上限丢弃并提示。 */
const appendLocalFiles = (files) => {
  let added = 0
  let overflow = false
  for (const f of files) {
    if (refs.value.length >= REF_MAX) { overflow = true; break }
    if (appendLocalRef(f, f.name)) added++
  }
  if (overflow) ElMessage.warning(`最多支持 ${REF_MAX} 张参考图`)
  return added
}

// ==== 粘贴 / 拖拽 / 本地文件 ====
const dragOver = ref(false)
const onDragOver = () => { dragOver.value = true }
const onDragLeave = () => { dragOver.value = false }
const onDrop = (e) => {
  dragOver.value = false
  const files = e.dataTransfer?.files
  if (!files || !files.length) return
  const imgs = [...files].filter(f => f.type.startsWith('image/'))
  if (!imgs.length) { ElMessage.error('仅支持 png/jpg/webp 格式'); return }
  const added = appendLocalFiles(imgs)
  if (added) aiTab.value = 'img2img'
}/** 抽屉打开时监听 window paste：剪贴板含图片才接管（文本粘贴不受影响）；一次可含多张。 */
const onWindowPaste = (e) => {
  const cb = e.clipboardData
  if (!cb) return
  const imgs = []
  const items = cb.items
  if (items && items.length) {
    for (const it of items) {
      if (it.kind === 'file' && it.type && it.type.startsWith('image/')) {
        const f = it.getAsFile()
        if (f) imgs.push(f)
      }
    }
  }
  if (!imgs.length && cb.files && cb.files.length) {
    imgs.push(...[...cb.files].filter(f => f.type.startsWith('image/')))
  }
  if (!imgs.length) return   // 无图片：交给浏览器默认处理（文本框粘贴等）
  e.preventDefault()
  aiTab.value = 'img2img'   // 粘贴意图明确是参考图：自动切到图生图
  const added = appendLocalFiles(imgs)
  if (added) ElMessage.success(`已从剪贴板读取 ${added} 张参考图`)
}

const fileInput = ref(null)
const openFilePicker = () => fileInput.value?.click()
const onFileInput = (e) => {
  const files = [...(e.target.files || [])]
  e.target.value = ''   // 允许重复选同一文件
  if (files.length) appendLocalFiles(files)
}

// ==== 参考图选择弹窗（独立数据源 + 防抖 + 分页 + 多选） ====
const REF_SIZE = 24
const refDialog = ref(false)
const refImages = ref([])
const refTotal = ref(0)
const refPage = ref(1)
const refKeyword = ref('')
const refLoading = ref(false)
/** 已选库图（保留对象本身，跨分页不丢选择；按选择顺序）。 */
const picked = ref([])
const pickedIds = computed(() => picked.value.map(p => p.id))
let refKwTimer = null
const openRefDialog = () => {
  refDialog.value = true
  refPage.value = 1
  picked.value = []
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
/** 该库图是否已在参考图集合内（重复确认不会新增，故不应占用选择名额）。 */
const alreadyInRefs = (img) => refs.value.some(r => r.kind === 'library' && r.id === img.id)
/** 多选勾/取消（跨分页保留已选，按 id 去重；已在参考图中的不再计入名额，避免误报上限）。 */
const togglePick = (img) => {
  if (alreadyInRefs(img)) { ElMessage.info('该图已在参考图中'); return }
  const i = picked.value.findIndex(p => p.id === img.id)
  if (i >= 0) { picked.value.splice(i, 1); return }
  if (refs.value.length + picked.value.length >= REF_MAX) { ElMessage.warning(`最多支持 ${REF_MAX} 张参考图`); return }
  picked.value.push(img)
}
/** 确认多选：按选择顺序追加（跨分页已选对象都在 picked 内）。 */
const confirmRefPicks = () => {
  let skipped = 0
  for (const img of picked.value) {
    if (refs.value.length >= REF_MAX) { skipped++; continue }
    appendLibraryRef(img)
  }
  if (skipped) ElMessage.warning(`最多支持 ${REF_MAX} 张参考图，已忽略 ${skipped} 张`)
  refDialog.value = false
}

// ==== 生成 ====
/** 图库候选缩略图；大图预览/插入正文必须用原图 url（webp 派生图微信素材接口不支持）。 */
const thumbOf = (img) => img?.thumbUrl || img?.url || ''
const originOf = (img) => img?.url || ''

/** 统一收口：成功则展示候选、写会话缓存（持有本地参考图时）、通知宿主刷新。
 *  `reason` 供宿主区分「首次生成」与「重生成」（预览页仅首次生成沿用 n=1 自动插入正文行为）。 */
const doGenerate = async (req, ctx) => {
  generating.value = true
  try {
    const res = await req
    if (res.code !== 0) { ElMessage.error(res.msg || '生成失败'); return }
    const list = res.data || []
    candidates.value = list
    // 持有本地参考图（或图库 id）时：每张结果图 id → 整组参考图信息，供同会话「重生成」复用。
    // size/tags/projectId 用提交时快照（ctx），不用 await 后的响应式值——生成期间用户仍可改尺寸/标签，
    // 若读取当前值会缓存下「与本次生成不符」的参数，重生成产出与预期不同。
    if (ctx?.cacheRefs) {
      for (const n of list) imageRefCache.put(n.id, buildCacheInfo(ctx))
    }
    const reused = list.some(img => img.dedupeHit)
    if (reused) ElMessage.success(`生成 ${list.length} 张（部分与图库重复，已复用）`)
    else ElMessage.success(`生成成功 ${list.length} 张，已进图库`)
    emit('generated', list, { reason: 'generate' })
  } catch (e) {
    ElMessage.error('生成失败：' + (e.response?.data?.msg || e.message || '网络异常或超时'))
  } finally { generating.value = false }
}

/** 构造缓存条目所需的参考图快照（files/refImageIds/names 与提交顺序一致）。 */
const buildCacheInfo = (ctx) => ({
  files: ctx.files,
  refImageIds: ctx.refImageIds,
  names: ctx.names,
  prompt: ctx.prompt,
  size: ctx.size,
  tags: ctx.tags,
  projectId: ctx.projectId
})

const onGenerateText = () => {
  const p = t2iPrompt.value.trim()
  if (!p) { ElMessage.warning('请输入画面描述'); return }
  doGenerate(imageApi.generateText(props.projectId, p, genSize.value, genCount.value, props.presetTags))
}
const onGenerateFromImage = () => {
  if (!refReady.value) { ElMessage.warning('请至少选择 1 张参考图'); return }
  const p = i2iPrompt.value.trim()
  if (!p) { ElMessage.warning('请输入画面描述'); return }
  // 统一走多图接口：files[]（本地/粘贴，按界面顺序）+ refImageIds[]（图库，按界面顺序），上限 4。
  // 提交时快照 size/tags/files/ids（生成期间 select 仍可交互），缓存与本次请求参数保持一致。
  const localFiles = localRefs.value.map(r => r.file)
  const libIds = libraryRefs.value.map(r => r.id)
  doGenerate(
    imageApi.generateFromImageUpload(props.projectId, localFiles, libIds, p, genSize.value, genCount.value, props.presetTags),
    {
      cacheRefs: true,
      files: localFiles,
      refImageIds: libIds,
      names: orderedRefs.value.map(r => r.name),
      prompt: p,
      size: genSize.value,
      tags: [...(props.presetTags || [])],
      projectId: props.projectId ?? null
    }
  )
}

// ==== 重生成分支 ====
/** 缓存条目是否可用作「复用参考图重生成」的依据（持有本地 files 或图库 ids）。 */
const cacheHasRefs = (cached) => !!cached && ((cached.files?.length || 0) > 0 || (cached.refImageIds?.length || 0) > 0)
/** 可用性：会话缓存命中（复用整组参考图）／文生图（prompt 可复现）／图库图生图（refImageId 可复用）。 */
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
    if (cacheHasRefs(cached)) {
      // 缓存命中：整组参考图 + 同 prompt 走多图接口（对齐后端 /regenerate 语义）
      res = await imageApi.generateFromImageUpload(cached.projectId, cached.files || [], cached.refImageIds || [], cached.prompt, cached.size, 1, cached.tags)
    } else {
      res = await imageApi.regenerate(img.id)
    }
    if (res.code !== 0) { ElMessage.error(res.msg || '重新生成失败'); return }
    const list = res.data || []
    if (!list.length) { ElMessage.warning('未生成新图'); return }
    // 缓存链：新图同样可用同一组参考图再重生成
    if (cacheHasRefs(cached)) {
      for (const n of list) {
        imageRefCache.put(n.id, {
          files: cached.files, refImageIds: cached.refImageIds, names: cached.names,
          prompt: cached.prompt, size: cached.size, tags: cached.tags, projectId: cached.projectId
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

/* 参考图区：空态为拖拽/粘贴投放区，有图时为多图缩略网格 + 来源角标 + 逐张移除 */
.ref-zone {
  display: flex; flex-direction: column; gap: 8px;
  padding: 10px; border: 1px dashed var(--line); border-radius: var(--radius-sm);
  background: var(--el-fill-color-light); margin-bottom: 10px;
  transition: border-color .2s, background-color .2s;
}
.ref-zone.is-over { border-color: var(--brand, var(--el-color-primary)); background: var(--brand-weak, var(--el-fill-color)); }
.ref-zone.is-empty { align-items: center; text-align: center; padding: 16px 10px; gap: 6px; }
.ref-head { display: flex; align-items: center; justify-content: space-between; }
.ref-count { font-size: 12px; font-weight: 600; color: var(--ink); }
.ref-thumbs { display: grid; grid-template-columns: repeat(auto-fill, minmax(76px, 1fr)); gap: 8px; }
.ref-item { position: relative; border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 4px; background: var(--paper); }
.ref-thumb { width: 100%; aspect-ratio: 4 / 3; object-fit: cover; border-radius: var(--radius-sm); display: block; }
.ref-badge { position: absolute; top: 6px; left: 6px; font-size: 10px; color: #fff; background: rgba(0,0,0,.55); padding: 1px 5px; border-radius: 4px; pointer-events: none; }
.ref-del { position: absolute; top: 4px; right: 4px; }
.ref-item-name { font-size: 10px; color: var(--muted); margin-top: 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ref-ops { display: flex; gap: 8px; flex-wrap: wrap; }
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
.ref-kw { margin-bottom: 8px; }
.ref-dialog-tip { font-size: 12px; color: var(--muted); margin-bottom: 8px; }
.img-pop-empty { font-size: 13px; color: var(--muted); padding: 8px 0; }
.ref-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 10px; max-height: 60vh; overflow-y: auto; }
.ref-cell { position: relative; cursor: pointer; border: 2px solid var(--line); border-radius: var(--radius-sm); padding: 6px; }
.ref-cell:hover { box-shadow: var(--shadow-hover); }
.ref-cell.is-picked { border-color: var(--brand, var(--el-color-primary)); }
.ref-cell-check { position: absolute; top: 8px; right: 8px; display: flex; align-items: center; justify-content: center; width: 20px; height: 20px; color: #fff; background: var(--brand, var(--el-color-primary)); border-radius: 50%; }
.ref-cell-thumb { width: 100%; aspect-ratio: 4 / 3; border-radius: var(--radius-sm); background: var(--paper); }
.ref-cell-name { display: block; font-size: 11px; color: var(--muted); margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ref-pager { display: flex; justify-content: center; margin-top: 12px; }

/* 移动端：单列候选、触控目标 ≥44px */
@media (max-width: 768px) {
  .cand-grid { grid-template-columns: 1fr; }
  .ref-empty-actions .el-button,
  .ref-ops .el-button,
  .cand-actions .el-button { min-height: 44px; }
  .ref-thumbs { grid-template-columns: repeat(auto-fill, minmax(96px, 1fr)); }
  .ref-zone.is-empty { padding: 20px 12px; }
}
</style>
