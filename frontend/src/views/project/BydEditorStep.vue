<template>
  <div class="step-body byd-editor-step">
    <!-- 正文加载失败：可见化 + 重试 -->
    <div v-if="loadError && !loaded" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">小程序正文加载失败</div>
      <div class="state-msg">{{ loadError }}</div>
      <el-button type="primary" plain @click="loadContent">重试</el-button>
    </div>

    <el-skeleton v-else-if="!loaded" :rows="8" animated />

    <template v-else>
      <el-alert v-if="legacyTokenCount" class="top-alert" type="warning" :closable="false" show-icon>
        <template #title>
          正文含 {{ legacyTokenCount }} 处公众号粘贴图占位（sparkora-img）。BYD 媒体必须重新插入，推送前会阻断。
        </template>
      </el-alert>
      <el-alert v-if="mediaStatusError" class="top-alert" type="error" :closable="false" show-icon>
        <template #title>
          BYD 媒体状态查询失败：{{ mediaStatusError }}
        </template>
      </el-alert>
      <el-alert v-if="malformedTokenCount" class="top-alert" type="error" :closable="false" show-icon>
        <template #title>
          正文含 {{ malformedTokenCount }} 个无法识别的媒体前缀或损坏 token，请删除后重新插入。
        </template>
      </el-alert>
      <el-alert v-if="!mediaChecking && missingMediaCount" class="top-alert" type="error" :closable="false" show-icon>
        <template #title>
          正文含 {{ missingMediaCount }} 个缺失、过期、类型不匹配或已失败的 BYD 媒体，请删除后重新插入。
        </template>
      </el-alert>
      <el-alert v-else-if="!mediaChecking && pendingMediaCount" class="top-alert" type="warning" :closable="false" show-icon>
        <template #title>
          有 {{ pendingMediaCount }} 个 BYD 媒体仍在准备中；准备完成前不能进入推送页。
        </template>
      </el-alert>

      <div class="editor-bar">
        <el-input v-model="title" class="title-input" maxlength="200" show-word-limit
                  placeholder="文章标题（必填，推送时作为小程序草稿标题）"
                  :disabled="!editorOrAbove || saving" />
        <span class="meta">{{ wordCount }} 字</span>
        <el-button size="small" :disabled="!editorOrAbove || uploading" @click="chooseImages">
          <el-icon style="margin-right: 4px"><Picture /></el-icon>
          插入图片
        </el-button>
        <el-button size="small" :disabled="!editorOrAbove || uploading" @click="imgDrawer = true">
          <el-icon style="margin-right: 4px"><Picture /></el-icon>
          配图 {{ imageCount }}
        </el-button>
        <el-button size="small" :disabled="!editorOrAbove || uploading || videoCount >= BYD_MAX_VIDEOS"
                  @click="chooseVideo">
          <el-icon style="margin-right: 4px"><VideoCamera /></el-icon>
          插入视频 {{ videoCount }}/{{ BYD_MAX_VIDEOS }}
        </el-button>
        <input ref="imageInput" type="file" accept="image/png,image/jpeg,image/webp" multiple hidden
               @change="onImageInputChange" />
        <input ref="videoInput" type="file" accept="video/mp4,.mp4" hidden @change="onVideoInputChange" />
        <el-tag v-if="saveState === 'dirty'" type="warning" effect="plain" size="small">未保存</el-tag>
        <el-tag v-else-if="saveState === 'error'" type="danger" effect="plain" size="small">保存失败</el-tag>
        <el-tag v-else-if="savedAt" type="success" effect="plain" size="small">已保存 {{ savedAt }}</el-tag>
      </div>

      <div v-if="uploading" class="upload-strip">
        <span class="upload-label">{{ uploadState.label }}</span>
        <el-progress :percentage="uploadState.percent" :stroke-width="6" :show-text="false" />
        <span class="upload-percent">{{ uploadState.percent }}%</span>
      </div>

      <div class="editor-pane" v-loading="saving">
        <!-- viewer 只读：MarkdownEditor 无 readonly 模式，改用只读多行框展示 -->
        <el-input v-if="!editorOrAbove" v-model="contentMd" type="textarea" :rows="22" readonly class="readonly-body" />
        <MarkdownEditor v-else ref="editorRef" v-model="contentMd" :project-id="projectId" media-mode="byd"
                        :media-upload-handler="uploadEditorImage" @media-inserted="onEditorMediaInserted"
                        @media-insert-failed="onEditorMediaInsertFailed" />
      </div>

      <div class="editor-foot" v-if="editorOrAbove">
        <span class="foot-tip">
          支持 Markdown、粘贴/选择图片与 MP4 视频。图片和视频先进入 Sparkora BYD 暂存区，最终由本机 Agent 上传比亚迪 CDN；
          推送前需保证微信电脑端完全退出。
        </span>
        <span v-if="saveState === 'dirty'" class="foot-dirty">有未保存修改</span>
      </div>
      <p v-else class="readonly-tip">viewer 只读；编辑与推送需要 ADMIN/EDITOR 角色。</p>
    </template>

    <!-- 配图抽屉：图库 / AI 生图 / 智能建议；BYD 模式不提供封面操作。 -->
    <PreviewImageDrawer
      v-model="imgDrawer"
      :project-id="projectId"
      media-mode="byd"
      :busy="busy"
      :inserted-urls="emptyInsertedUrls"
      :cover-image-id="null"
      :snapshot="imgSnapshot"
      :is-editor-ready="isEditorReady"
      :insert-at-anchor="insertAtAnchor"
      :prepare-media-for-insertion="prepareBydMediaForInsertion"
      :refresh-snapshot="refreshImgSnapshot"
      :save-content="persistContent"
      @update:busy="(v) => (busy = v)"
      @insert="insertBodyImage" @generated="onGenerated" />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled, Picture, VideoCamera } from '@element-plus/icons-vue'
import { imageApi, projectApi, bydMediaApi } from '../../api'
import MarkdownEditor from '../../components/MarkdownEditor.vue'
import PreviewImageDrawer from '../../components/preview/PreviewImageDrawer.vue'
import { useUserStore } from '../../store/user'
import { usePageHeader } from '../../composables/usePageHeader'
import {
  BYD_IMAGE_PREFIX,
  BYD_MAX_VIDEOS,
  imageMediaMarkdown,
  parseBydMediaRefs,
  summarizeBydMediaReadiness,
  videoMediaMarkdown
} from '../../utils/bydMediaRefs'
import {
  mediaFromResponse,
  mediaStorageState,
  registerBydImageFromLibrary,
  removeBydMediaQuietly,
  uploadBydImage,
  uploadBydVideo
} from '../../utils/bydMediaUpload'

/**
 * 王朝小程序工作台 · 编辑步（10-20-miniapp-media-push）。
 *
 * 图片/视频直接上传到 BYD 专用暂存区，正文只保存 BYD token；不再调用公众号
 * usePendingImageFlush / imageApi.upload。最终由 Server 编排，本机 Agent 上传比亚迪 CDN。
 */
const props = defineProps({ project: Object })
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const projectId = computed(() => route.params.id)
const editorOrAbove = computed(() => userStore.isEditorOrAbove === true)
const emptyInsertedUrls = new Set()

const loaded = ref(false)
const loadError = ref('')
const loadingContent = ref(false)
const versionId = ref(null)
const title = ref('')
const originalTitle = ref('')
const contentMd = ref('')
const originalMd = ref('')
const saving = ref(false)
const savedAt = ref('')
const saveState = ref('clean') // clean | dirty | error
const editorReady = ref(false)
const editorRef = ref(null)
const imageInput = ref(null)
const videoInput = ref(null)
const goingPush = ref(false)

const mediaStatus = ref({})
const mediaChecking = ref(false)
const mediaStatusError = ref('')
const uploadState = ref({ active: 0, percent: 0, label: '' })
const uploadControllers = new Set()
const uploading = computed(() => uploadState.value.active > 0)

const dirty = computed(() => contentMd.value !== originalMd.value || title.value !== originalTitle.value)
const wordCount = computed(() => (contentMd.value || '').replace(/\s/g, '').length)
const bydRefs = computed(() => parseBydMediaRefs(contentMd.value))
const imageCount = computed(() => bydRefs.value.imageRefCount)
const videoCount = computed(() => bydRefs.value.videoRefCount)
const legacyTokenCount = computed(() => bydRefs.value.legacyTokenIds.length)
const malformedTokenCount = computed(() => bydRefs.value.malformedTokenCount)
const allMediaIds = computed(() => bydRefs.value.mediaIds)
const mediaReadiness = computed(() => summarizeBydMediaReadiness(bydRefs.value.refs, mediaStatus.value))
const pendingMediaCount = computed(() => mediaReadiness.value.pendingCount)
const missingMediaCount = computed(() => mediaReadiness.value.missingCount)
const unreadyMediaCount = computed(() => mediaReadiness.value.unreadyCount)

const setMediaStatus = (media, fallbackState = '') => {
  if (!media?.mediaId) return
  mediaStatus.value = {
    ...mediaStatus.value,
    [media.mediaId]: { ...media, storageState: mediaStorageState(media) || fallbackState }
  }
}

/** 查询正文引用的所有 BYD 媒体，跨项目/过期/未知 token 一律进入失效态。 */
let mediaCheckPromise = null
const refreshMediaStatus = ({ notify = false } = {}) => {
  if (mediaCheckPromise) return mediaCheckPromise
  const ids = allMediaIds.value
  mediaStatusError.value = ''
  if (!ids.length) {
    mediaStatus.value = {}
    return Promise.resolve()
  }
  mediaChecking.value = true
  mediaCheckPromise = (async () => {
    try {
      const results = await Promise.all(ids.map(async (id) => {
        try {
          const res = await bydMediaApi.get(projectId.value, id)
          const media = mediaFromResponse(res, '查询 BYD 媒体失败')
          const state = mediaStorageState(media)
          if (!state) throw new Error('媒体状态缺失')
          return [id, { ...media, storageState: state }]
        } catch (error) {
          const message = error?.response?.data?.msg || error?.message || '媒体不可用'
          const status = error?.response?.status
          const state = status === 404 || /不存在|不属于|过期|失效/.test(message) ? 'MISSING' : 'ERROR'
          return [id, { mediaId: id, storageState: state, errorMessage: message }]
        }
      }))
      // 合并而不是覆盖：上传/插入与一次在途查询并发时，不能丢掉刚写入的 READY 记录。
      mediaStatus.value = { ...mediaStatus.value, ...Object.fromEntries(results) }
      if (notify && !mediaReadiness.value.allReady) {
        ElMessage.warning('仍有媒体缺失或未准备完成，请处理后再推送')
      }
    } catch (error) {
      mediaStatusError.value = error?.response?.data?.msg || error?.message || '媒体状态查询失败'
      if (notify) ElMessage.error(mediaStatusError.value)
    } finally {
      mediaChecking.value = false
    }
  })().finally(() => { mediaCheckPromise = null })
  return mediaCheckPromise
}

const runUpload = async (label, task) => {
  const controller = new AbortController()
  uploadControllers.add(controller)
  uploadState.value = { active: uploadState.value.active + 1, percent: 0, label }
  try {
    return await task({
      signal: controller.signal,
      onProgress: (percent) => {
        uploadState.value = { ...uploadState.value, percent: Number(percent) || 0, label }
      }
    })
  } finally {
    uploadControllers.delete(controller)
    uploadState.value = { ...uploadState.value, active: Math.max(0, uploadState.value.active - 1) }
    if (!uploadState.value.active) uploadState.value = { active: 0, percent: 0, label: '' }
  }
}

/** MarkdownEditor 粘贴图片注入点：只上传暂存，token 插入由编辑器完成。 */
const uploadEditorImage = async (file, options = {}) => {
  const media = await runUpload('正在上传图片到 BYD 暂存区…', ({ signal, onProgress }) =>
    uploadBydImage(projectId.value, file, {
      signal: options.signal || signal,
      onProgress: (percent) => {
        onProgress(percent)
        options.onProgress?.(percent)
      }
    }))
  setMediaStatus(media, 'READY')
  return media
}

const onEditorMediaInserted = async () => {
  await nextTick()
  if (dirty.value) await persistContent()
  await refreshMediaStatus()
}

const onEditorMediaInsertFailed = ({ mediaId } = {}) => {
  if (mediaId) removeBydMediaQuietly(projectId.value, mediaId)
}

const chooseImages = () => imageInput.value?.click()
const chooseVideo = () => {
  if (videoCount.value >= BYD_MAX_VIDEOS) {
    ElMessage.warning(`每篇文章最多 ${BYD_MAX_VIDEOS} 个视频`)
    return
  }
  videoInput.value?.click()
}

const onImageInputChange = async (event) => {
  const files = [...(event.target.files || [])]
  event.target.value = ''
  for (const file of files) {
    try {
      const media = await uploadEditorImage(file)
      if (!editorRef.value?.insertMd?.(imageMediaMarkdown(media.mediaId))) {
        await removeBydMediaQuietly(projectId.value, media.mediaId)
        throw new Error('编辑器未就绪，图片未插入')
      }
      await nextTick()
      await persistContent()
      await refreshMediaStatus()
    } catch (error) {
      if (error?.name !== 'AbortError') ElMessage.error(error?.response?.data?.msg || error?.message || '图片上传失败')
    }
  }
}

const onVideoInputChange = async (event) => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (videoCount.value >= BYD_MAX_VIDEOS) {
    ElMessage.warning(`每篇文章最多 ${BYD_MAX_VIDEOS} 个视频`)
    return
  }
  try {
    const media = await runUpload('正在校验并上传 MP4 视频到 BYD 暂存区…', ({ signal, onProgress }) =>
      uploadBydVideo(projectId.value, file, { currentVideoCount: videoCount.value, signal, onProgress }))
    setMediaStatus(media, 'READY')
    if (!editorRef.value?.insertMd?.(videoMediaMarkdown(media.mediaId))) {
      await removeBydMediaQuietly(projectId.value, media.mediaId)
      throw new Error('编辑器未就绪，视频未插入')
    }
    await nextTick()
    await persistContent()
    await refreshMediaStatus()
  } catch (error) {
    if (error?.name !== 'AbortError') ElMessage.error(error?.response?.data?.msg || error?.message || '视频上传失败')
  }
}

// ==== 配图抽屉状态（图库 / AI 生图 / 智能建议）====
const imgDrawer = ref(false)
const busy = ref(false)
const imgSnapshot = ref(null)

const loadSnapshot = async () => {
  const res = await imageApi.projectImages(projectId.value)
  if (res.code !== 0) throw new Error(res.msg || '配图快照加载失败')
  imgSnapshot.value = {
    images: res.data?.images || [],
    coverImageId: res.data?.coverImageId ?? null,
    bodyImageIds: res.data?.bodyImageIds || [],
    coverImage: res.data?.coverImage ?? null,
    bodyImages: res.data?.bodyImages || []
  }
}
const refreshImgSnapshot = () => loadSnapshot()

const loadContent = async () => {
  if (loadingContent.value) return
  loadError.value = ''
  loadingContent.value = true
  try {
    const preVid = props.project?.currentVersionId ?? null
    const res = await projectApi.listVersions(projectId.value)
    if (res.code !== 0) throw new Error(res.msg || '版本加载失败')
    const versions = res.data || []
    if (!versions.length) throw new Error('项目还没有可编辑的版本，请从生成步重新进入或重建项目')
    const target = versions.find((v) => String(v.id) === String(preVid)) || versions[0]
    versionId.value = target.id
    title.value = target.title || ''
    originalTitle.value = title.value
    contentMd.value = target.contentMd || ''
    originalMd.value = contentMd.value
    saveState.value = 'clean'
    savedAt.value = ''
    loaded.value = true
    loadError.value = ''
    editorReady.value = true
    knownMediaIds = new Set(parseBydMediaRefs(contentMd.value).mediaIds)
    refsInitialized = true
    refreshMediaStatus()
    loadSnapshot().catch((e) => console.warn('[sparkora] 小程序配图快照加载失败:', e?.message || e))
  } catch (e) {
    loadError.value = e?.response?.data?.msg || e?.message || '网络异常'
  } finally {
    loadingContent.value = false
  }
}

/** 保存正文（静默）；成功后才回写 originalMd 清 dirty。 */
const persistContent = async () => {
  if (!versionId.value) return false
  if (contentMd.value === originalMd.value) return true
  saving.value = true
  try {
    const res = await imageApi.saveContent(projectId.value, versionId.value, contentMd.value)
    if (res.code !== 0) throw new Error(res.msg || '保存失败')
    originalMd.value = contentMd.value
    savedAt.value = new Date().toTimeString().slice(0, 5)
    saveState.value = dirty.value ? 'dirty' : 'clean'
    return true
  } catch (e) {
    saveState.value = 'error'
    ElMessage.error(e?.response?.data?.msg || e?.message || '保存失败')
    return false
  } finally {
    saving.value = false
  }
}

/** 保存标题（静默）。 */
const persistTitle = async () => {
  if (!versionId.value || title.value === originalTitle.value) return true
  try {
    const res = await projectApi.saveTitle(projectId.value, versionId.value, title.value)
    if (res.code !== 0) throw new Error(res.msg || '标题保存失败')
    originalTitle.value = title.value
    return true
  } catch (e) {
    saveState.value = 'error'
    ElMessage.error(e?.response?.data?.msg || e?.message || '标题保存失败')
    return false
  }
}

const saveAll = async () => {
  if (!editorOrAbove.value) return false
  const contentOk = await persistContent()
  if (!contentOk) return false
  const titleOk = await persistTitle()
  if (!titleOk) return false
  savedAt.value = new Date().toTimeString().slice(0, 5)
  saveState.value = dirty.value ? 'dirty' : 'clean'
  ElMessage.success('已保存')
  return true
}

/** 去推送：先保存，再做 BYD 媒体准备状态门禁；不再上传七牛。 */
const goPush = async () => {
  if (goingPush.value || saving.value || uploading.value) return
  if (!editorOrAbove.value) { ElMessage.warning('viewer 只读，推送需要 ADMIN/EDITOR 角色'); return }
  if (dirty.value) {
    const ok = await saveAll()
    if (!ok) return
  }
  goingPush.value = true
  try {
    await refreshMediaStatus({ notify: true })
    if (mediaStatusError.value) {
      ElMessage.error(mediaStatusError.value)
      return
    }
    if (malformedTokenCount.value) {
      ElMessage.error(`正文含 ${malformedTokenCount.value} 个无法识别的媒体前缀或损坏 token，请删除后重新插入`)
      return
    }
    if (legacyTokenCount.value) {
      ElMessage.error('正文含公众号粘贴图占位（sparkora-img），请删除或重新插入 BYD 图片后再推送')
      return
    }
    if (missingMediaCount.value) {
      ElMessage.error(`正文含 ${missingMediaCount.value} 个缺失、过期、类型不匹配或失败的 BYD 媒体，请重新插入后再推送`)
      return
    }
    if (pendingMediaCount.value) {
      ElMessage.error(`仍有 ${pendingMediaCount.value} 个 BYD 媒体未准备完成，请稍后重试`)
      return
    }
    if (unreadyMediaCount.value) {
      ElMessage.error(`仍有 ${unreadyMediaCount.value} 个 BYD 媒体状态未知，请刷新后重试`)
      return
    }
    if (videoCount.value > BYD_MAX_VIDEOS) {
      ElMessage.error(`每篇文章最多 ${BYD_MAX_VIDEOS} 个视频`)
      return
    }
    router.push({ name: 'project-byd-push', params: { id: projectId.value } })
  } finally {
    goingPush.value = false
  }
}

// ==== 配图：图库/AI 图片必须先注册成 BYD URL 源媒体 ====
const prepareBydMediaForInsertion = async (img) => {
  const media = await runUpload('正在注册图库图片为 BYD 媒体…', () => registerBydImageFromLibrary(projectId.value, img))
  setMediaStatus(media, 'READY')
  return {
    target: `${BYD_IMAGE_PREFIX}${media.mediaId}`,
    rollback: () => removeBydMediaQuietly(projectId.value, media.mediaId)
  }
}

const insertBodyImage = async (img) => {
  if (busy.value) return
  busy.value = true
  try {
    const prepared = await prepareBydMediaForInsertion(img)
    if (!editorRef.value?.insertMd?.(`\n![图片](${prepared.target})\n`)) {
      await prepared.rollback?.()
      throw new Error('编辑器未就绪，图片未插入')
    }
    await nextTick()
    await persistContent()
    await refreshMediaStatus()
    try { await refreshImgSnapshot() } catch (e) { /* 快照刷新失败不影响正文 */ }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e?.message || '注册 BYD 图片失败')
  } finally {
    busy.value = false
  }
}
const insertAtAnchor = (headingPath, md) => editorRef.value?.insertMdAtAnchor?.(headingPath, md) ?? false
const isEditorReady = () => !!editorReady.value
const onGenerated = async () => {
  try { await refreshImgSnapshot() } catch (e) { /* 快照刷新失败不影响生成结果展示 */ }
}

// 用户删除 token 后 best-effort 清理暂存媒体；失败由过期清理兜底。
let knownMediaIds = new Set()
let refsInitialized = false
watch([() => bydRefs.value.imageIds, () => bydRefs.value.videoIds], () => {
  const current = new Set(allMediaIds.value)
  if (refsInitialized) {
    for (const id of knownMediaIds) {
      if (!current.has(id)) removeBydMediaQuietly(projectId.value, id)
    }
  }
  knownMediaIds = current
  refsInitialized = true
})

// 上下文条：ProjectLayout 持有面包屑，子页只挂动作
const header = usePageHeader()
const syncHeader = () => {
  if (!header) return
  header.actions = editorOrAbove.value
    ? [
        { key: 'save', label: '保存', loading: saving.value, disabled: !loaded.value || !dirty.value || saving.value || uploading.value, onClick: saveAll },
        { key: 'push', label: '去推送 →', type: 'primary', loading: goingPush.value, disabled: !loaded.value || saving.value || uploading.value, onClick: goPush }
      ]
    : []
}
syncHeader()
watch([saving, dirty, goingPush, editorOrAbove, loaded, uploading], syncHeader)
onBeforeRouteLeave(async () => {
  if (header) header.actions = []
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('标题或正文有未保存的修改，离开后将丢失。确定离开？', '离开确认',
      { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' })
    return true
  } catch {
    return false
  }
})

// dirty 状态展示：任何输入变更即标记未保存
watch([contentMd, title], () => {
  if (dirty.value && saveState.value !== 'error') saveState.value = 'dirty'
})

// 项目/当前版本晚到或生成产生新版本时装载正文；已有未保存修改时不覆盖用户输入
watch(() => props.project?.currentVersionId, (vid) => {
  if (vid == null) return
  if (!loaded.value) { loadContent(); return }
  if (String(vid) !== String(versionId.value) && !dirty.value) loadContent()
}, { immediate: true })

onMounted(() => {
  // BYD token 有服务端持久化元数据，不清理公众号 pendingImageStore；旧 token 由正文门禁提示。
})

onBeforeUnmount(() => {
  if (header) header.actions = []
  for (const controller of uploadControllers) controller.abort()
  uploadControllers.clear()
})
</script>

<style scoped>
.byd-editor-step { gap: var(--sp-4); }
.top-alert { flex: none; }
.state-error { padding: var(--sp-8) var(--sp-4); }
.state-title { font-weight: 700; margin: var(--sp-2) 0 var(--sp-1); }
.state-msg { color: var(--muted); font-size: var(--fs-13); margin-bottom: var(--sp-4); }

.editor-bar { display: flex; align-items: center; gap: var(--sp-3); flex: none; }
.title-input { max-width: 560px; }
.title-input :deep(.el-input__inner) { font-size: var(--fs-15); font-weight: 600; }
.meta { color: var(--muted); font-size: var(--fs-12); white-space: nowrap; }

.upload-strip { display: grid; grid-template-columns: auto minmax(160px, 1fr) 42px; align-items: center; gap: var(--sp-2); padding: var(--sp-2) var(--sp-3); border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--soft); }
.upload-label { font-size: var(--fs-12); color: var(--muted); }
.upload-percent { font-size: var(--fs-12); color: var(--muted); text-align: right; }

.editor-pane {
  flex: 1;
  min-height: 360px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--card);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.editor-pane > :deep(.cm-host) { flex: 1; min-height: 0; height: 100%; }
.readonly-body :deep(.el-textarea__inner) { height: 100%; min-height: 360px; font-family: var(--font-mono); line-height: 1.7; border: none; box-shadow: none; resize: none; }

.editor-foot { display: flex; align-items: center; gap: var(--sp-3); flex: none; }
.foot-tip { font-size: var(--fs-12); color: var(--faint); line-height: var(--lh-12); }
.foot-dirty { margin-left: auto; font-size: var(--fs-12); color: var(--warning, var(--brand)); white-space: nowrap; }
.readonly-tip { font-size: var(--fs-12); color: var(--muted); margin: 0; }
</style>
