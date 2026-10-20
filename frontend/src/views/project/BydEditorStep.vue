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
      <!-- 粘贴图失效警示（与预览页同口径：暂存区不持久化，重载后 token 无法再上传） -->
      <el-alert v-if="unresolvedTokens.length" class="top-alert" type="warning" :closable="false" show-icon>
        <template #title>
          正文含 {{ unresolvedTokens.length }} 张已失效的粘贴图（页面重载后暂存丢失，无法再上传），请重新粘贴或删除占位。
        </template>
      </el-alert>

      <div class="editor-bar">
        <el-input v-model="title" class="title-input" maxlength="200" show-word-limit
                  placeholder="文章标题（必填，推送时作为小程序草稿标题）"
                  :disabled="!editorOrAbove || saving" />
        <span class="meta">{{ wordCount }} 字</span>
        <el-button size="small" :disabled="!editorOrAbove" @click="imgDrawer = true">
          <el-icon style="margin-right: 4px"><Picture /></el-icon>
          配图 {{ imageCount }}<template v-if="pendingCount"> · 待传 {{ pendingCount }}</template>
        </el-button>
        <el-tag v-if="saveState === 'dirty'" type="warning" effect="plain" size="small">未保存</el-tag>
        <el-tag v-else-if="saveState === 'error'" type="danger" effect="plain" size="small">保存失败</el-tag>
        <el-tag v-else-if="savedAt" type="success" effect="plain" size="small">已保存 {{ savedAt }}</el-tag>
      </div>

      <div class="editor-pane" v-loading="saving">
        <!-- viewer 只读：MarkdownEditor 无 readonly 模式，改用只读多行框展示 -->
        <el-input v-if="!editorOrAbove" v-model="contentMd" type="textarea" :rows="22" readonly class="readonly-body" />
        <MarkdownEditor v-else ref="editorRef" v-model="contentMd" :project-id="projectId" />
      </div>

      <div class="editor-foot" v-if="editorOrAbove">
        <span class="foot-tip">
          支持 Markdown 与直接粘贴图片；粘贴图会在点「去推送」时统一上传图床。小程序按消息流展示，无需公众号排版主题。
        </span>
        <span v-if="saveState === 'dirty'" class="foot-dirty">有未保存修改</span>
      </div>
      <p v-else class="readonly-tip">viewer 只读；编辑与推送需要 ADMIN/EDITOR 角色。</p>
    </template>

    <!-- 配图抽屉：图库 / AI 生图 / 智能建议（复用预览页同款能力） -->
    <PreviewImageDrawer
      v-model="imgDrawer"
      :project-id="projectId"
      :busy="busy"
      :inserted-urls="insertedUrls"
      :cover-image-id="coverImageId"
      :snapshot="imgSnapshot"
      :is-editor-ready="isEditorReady"
      :insert-at-anchor="insertAtAnchor"
      :refresh-snapshot="refreshImgSnapshot"
      :save-content="persistContent"
      @update:busy="(v) => (busy = v)"
      @insert="insertBodyImage" @set-cover="onSetCover" @generated="onGenerated" />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled, Picture } from '@element-plus/icons-vue'
import { imageApi, projectApi } from '../../api'
import MarkdownEditor from '../../components/MarkdownEditor.vue'
import PreviewImageDrawer from '../../components/preview/PreviewImageDrawer.vue'
import { useUserStore } from '../../store/user'
import { usePageHeader } from '../../composables/usePageHeader'
import { usePendingImageFlush } from '../../composables/usePendingImageFlush'
import { clearOthers, extractTokens, get } from '../../utils/pendingImageStore'
import { parseBodyImageRefs } from '../../utils/bodyImageRefs'

/**
 * 王朝小程序工作台 · 编辑步（10-19-channel-editor-publish R3）。
 *
 * 自由编辑区：标题 + MarkdownEditor 正文 + 配图（图库 / AI 生图 / 智能建议 / 粘贴图）。
 * 内容落在现有版本表（currentVersionId），推送链路 buildPayload 原样读取，无需新增字段。
 * 不渲染 wenyan 主题/预览：小程序按消息流展示，无公众号排版概念。
 */
const props = defineProps({ project: Object })
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const projectId = computed(() => route.params.id)
const editorOrAbove = computed(() => userStore.isEditorOrAbove)

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
const goingPush = ref(false)

const dirty = computed(() => contentMd.value !== originalMd.value || title.value !== originalTitle.value)
const wordCount = computed(() => (contentMd.value || '').replace(/\s/g, '').length)
const bodyImageRefs = computed(() => parseBodyImageRefs(contentMd.value))
const imageCount = computed(() => bodyImageRefs.value.urls.length)
const isPendingToken = (id) => {
  const e = get(id)
  return !!e && e.projectId === String(projectId.value)
}
const pendingCount = computed(() => bodyImageRefs.value.tokenIds.filter(isPendingToken).length)
const unresolvedTokens = computed(() => extractTokens(contentMd.value).filter((id) => !isPendingToken(id)))
const insertedUrls = computed(() => new Set(bodyImageRefs.value.urls))

// ==== 配图抽屉状态（图库 / AI 生图 / 智能建议）====
const imgDrawer = ref(false)
const busy = ref(false)
const imgSnapshot = ref(null)
const coverImageId = computed(() => imgSnapshot.value?.coverImageId ?? null)

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
    loadSnapshot().catch((e) => console.warn('[sparkora] 小程序配图快照加载失败:', e?.message || e))
  } catch (e) {
    loadError.value = e?.response?.data?.msg || e?.message || '网络异常'
  } finally {
    loadingContent.value = false
  }
}

/** 保存正文（静默：供「保存」与暂存图上传编排共用；成功后回写 originalMd 清 dirty）。 */
const persistContent = async () => {
  if (!versionId.value) return false
  // 正文未变化时不打保存接口：新建小程序项目是空正文，只想先存标题时
  // 后端 PUT content 会以「正文不能为空」拒绝（版本正文契约），标题就永远存不进去。
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

// 暂存图上传编排：点「去推送」时把正文仍引用的粘贴图统一上传并替换 token（复用预览页同款逻辑）。
const { flushPendingImages } = usePendingImageFlush({
  projectId,
  getContent: () => contentMd.value,
  setContent: (md) => { contentMd.value = md },
  isDirty: () => dirty.value,
  saveContent: () => persistContent(),
  flushStyle: () => Promise.resolve(),
  goNext: () => {},
  isSaving: () => saving.value
})

/** 去推送：dirty 先保存 → 暂存图转存（内含再保存）→ 跳推送步；任一步失败停留本页。 */
const goPush = async () => {
  if (goingPush.value || saving.value) return
  if (!editorOrAbove.value) { ElMessage.warning('viewer 只读，推送需要 ADMIN/EDITOR 角色'); return }
  if (dirty.value) {
    const ok = await saveAll()
    if (!ok) return
  }
  goingPush.value = true
  try {
    const { ok, reason, entriesKept, unresolved } = await flushPendingImages()
    if (!ok) {
      ElMessage.error(entriesKept
        ? `${reason}；已上传部分已保留，可直接再点「去推送」重试`
        : `${reason}（图片未上传，正文仍保留占位，可重试）`)
      return
    }
    if (unresolved > 0) {
      ElMessage.error(`正文含 ${unresolved} 处失效的粘贴图占位（刷新后暂存丢失），请删除占位或重新粘贴后再推送`)
      return
    }
    router.push({ name: 'project-byd-push', params: { id: projectId.value } })
  } finally {
    goingPush.value = false
  }
}

// ==== 配图 ====
const originUrl = (img) => img?.url || ''
const insertBodyImage = (img) => {
  editorRef.value?.insertMd?.(`\n![](${originUrl(img)})\n`)
  if (img?.id) imageApi.addBodyImage(projectId.value, img.id).catch((e) => {
    console.warn('[byd-editor] 插图登记失败（不影响正文）', e)
  })
}
const insertAtAnchor = (headingPath, md) => editorRef.value?.insertMdAtAnchor?.(headingPath, md) ?? false
const isEditorReady = () => !!editorReady.value
const onSetCover = async (imageId) => {
  busy.value = true
  try {
    const res = await imageApi.setCover(projectId.value, imageId)
    if (res.code === 0) {
      ElMessage.success('已设为封面')
      if (imgSnapshot.value) imgSnapshot.value.coverImageId = imageId
    } else ElMessage.error(res.msg || '设置失败')
  } catch (e) {
    ElMessage.error('设置失败：' + (e.response?.data?.msg || e.message || '网络异常'))
  } finally { busy.value = false }
}
const onGenerated = async () => {
  try { await refreshImgSnapshot() } catch (e) { /* 快照刷新失败不影响生成结果展示 */ }
}

// 上下文条：ProjectLayout 持有面包屑，子页只挂动作
const header = usePageHeader()
const syncHeader = () => {
  if (!header) return
  header.actions = editorOrAbove.value
    ? [
        { key: 'save', label: '保存', loading: saving.value, disabled: !loaded.value || !dirty.value || saving.value, onClick: saveAll },
        { key: 'push', label: '去推送 →', type: 'primary', loading: goingPush.value, disabled: !loaded.value || saving.value, onClick: goPush }
      ]
    : []
}
syncHeader()
watch([saving, dirty, goingPush, editorOrAbove, loaded], syncHeader)
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
  // 与预览页同口径：进入工作台清掉其他项目的粘贴图暂存，避免 blob URL 会话级泄漏
  clearOthers(projectId.value)
})

onBeforeUnmount(() => { if (header) header.actions = [] })
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
