<template>
  <div class="step-body byd-push-step">
    <div v-if="loadError" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">推送摘要加载失败</div>
      <div class="state-msg">{{ loadError }}</div>
      <el-button type="primary" plain @click="loadSummary">重试</el-button>
    </div>

    <el-skeleton v-else-if="!loaded" :rows="5" animated />

    <template v-else>
      <!-- 摘要：文本、图片、视频统一按当前版本正文解析，媒体准备状态来自 BYD 暂存元数据。 -->
      <section class="panel summary">
        <div class="summary-head">
          <span class="panel-title">推送摘要</span>
          <span class="panel-hint">编辑完成后在此推送；推送前必须完全退出微信电脑端</span>
        </div>
        <div class="summary-title">{{ title || '（未命名小程序文章）' }}</div>
        <div class="summary-tags">
          <el-tag size="small" effect="plain">正文 {{ wordCount }} 字</el-tag>
          <el-tag size="small" effect="plain" :type="imageCount ? 'success' : 'info'">图片 {{ imageCount }} 张</el-tag>
          <el-tag size="small" effect="plain" :type="videoCount ? 'success' : 'info'">视频 {{ videoCount }}/{{ BYD_MAX_VIDEOS }}</el-tag>
          <el-tag v-if="!mediaChecking && pendingCount" size="small" type="warning" effect="plain">待准备 {{ pendingCount }}</el-tag>
          <el-tag v-if="!mediaChecking && unresolvedCount" size="small" type="danger" effect="plain">失效媒体 {{ unresolvedCount }}</el-tag>
          <el-tag v-if="mediaChecking" size="small" type="info" effect="plain">检查媒体中…</el-tag>
        </div>
        <div v-if="!hasContent" class="summary-empty">
          正文为空：请先回到编辑步撰写文字（或从生成步让 AI 起草），推送按钮才会生效。
        </div>
        <el-button v-if="!hasContent" type="primary" plain @click="goEditor">去编辑正文</el-button>
        <el-button v-else plain :loading="mediaChecking" :disabled="mediaChecking" @click="refreshMediaStatus({ notify: true })">
          刷新媒体状态
        </el-button>
      </section>

      <el-alert v-if="malformedTokenCount" type="error" :closable="false" show-icon
                title="正文含损坏或无法识别的媒体 token"
                :description="`发现 ${malformedTokenCount} 个裸文本、损坏 Markdown 或无法识别的媒体前缀，请回到编辑步删除后重新插入。`" />
      <el-alert v-if="legacyTokenCount" type="warning" :closable="false" show-icon
                title="正文含公众号粘贴图占位"
                :description="`发现 ${legacyTokenCount} 个 sparkora-img token。请在编辑步删除或重新插入 BYD 图片；公众号 token 不能进入小程序推送。`" />
      <el-alert v-if="mediaStatusError" type="error" :closable="false" show-icon
                title="BYD 媒体状态查询失败" :description="mediaStatusError" />
      <el-alert v-if="mediaBlockReason" type="warning" :closable="false" show-icon
                title="媒体尚未准备完成，推送已禁用" :description="mediaBlockReason" />
      <div v-if="!mediaChecking && (malformedTokenCount || legacyTokenCount || unresolvedCount || mediaStatusError)" class="token-block">
        <el-button type="primary" plain @click="goEditor">回到编辑步处理媒体</el-button>
      </div>

      <BydDynastyPanel v-if="hasContent" :project-id="projectId" :editor-or-above="editorOrAbove"
                       :media-ready="mediaReady" :media-block-reason="mediaBlockReason" :media-summary="mediaSummary" />

      <p class="push-tip">
        图片和视频先进入 Sparkora BYD 暂存区，任务创建后由本机 Agent 下载并上传比亚迪 CDN。
        写入前 Agent 会再次预检并备份 MMKV；重复推送会覆盖同一条小程序草稿。
      </p>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { projectApi, bydMediaApi } from '../../api'
import BydDynastyPanel from '../../components/publish/BydDynastyPanel.vue'
import { useUserStore } from '../../store/user'
import { usePageHeader } from '../../composables/usePageHeader'
import { BYD_MAX_VIDEOS, parseBydMediaRefs, summarizeBydMediaReadiness } from '../../utils/bydMediaRefs'
import { mediaFromResponse, mediaStorageState } from '../../utils/bydMediaUpload'

/**
 * 王朝小程序工作台 · 推送步（10-20-miniapp-media-push）。
 *
 * 摘要中的图片/视频数量与 token 解析共用 bydMediaRefs；每个媒体都查询暂存元数据，
 * 未解析、缺失、过期、FAILED 或 PENDING 时禁用创建任务。
 */
const props = defineProps({ project: Object })
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const projectId = computed(() => route.params.id)
const editorOrAbove = computed(() => userStore.isEditorOrAbove === true)

const loaded = ref(false)
const loadError = ref('')
const loading = ref(false)
const title = ref('')
const contentMd = ref('')
const mediaStatus = ref({})
const mediaChecking = ref(false)
const mediaStatusError = ref('')

const wordCount = computed(() => (contentMd.value || '').replace(/\s/g, '').length)
const bydRefs = computed(() => parseBydMediaRefs(contentMd.value))
const imageCount = computed(() => bydRefs.value.imageRefCount)
const videoCount = computed(() => bydRefs.value.videoRefCount)
const legacyTokenCount = computed(() => bydRefs.value.legacyTokenIds.length)
const malformedTokenCount = computed(() => bydRefs.value.malformedTokenCount)
const allMediaIds = computed(() => bydRefs.value.mediaIds)
const mediaReadiness = computed(() => summarizeBydMediaReadiness(bydRefs.value.refs, mediaStatus.value))
const pendingCount = computed(() => mediaReadiness.value.pendingCount)
const missingCount = computed(() => mediaReadiness.value.missingCount)
const unreadyCount = computed(() => mediaReadiness.value.unreadyCount)
const unresolvedCount = computed(() => malformedTokenCount.value + legacyTokenCount.value + missingCount.value)
const hasContent = computed(() => !!(contentMd.value || '').trim())

const mediaBlockReason = computed(() => {
  if (mediaChecking.value) return '正在查询 BYD 暂存媒体准备状态。'
  if (mediaStatusError.value) return mediaStatusError.value
  if (malformedTokenCount.value) return `正文含 ${malformedTokenCount.value} 个损坏或无法识别的媒体 token，请删除后重新插入。`
  if (legacyTokenCount.value) return `正文含 ${legacyTokenCount.value} 个公众号 sparkora-img 占位，请重新插入 BYD 图片。`
  if (missingCount.value) return `有 ${missingCount.value} 个媒体缺失、过期、类型不匹配或失败，请回到编辑步重新插入。`
  if (pendingCount.value) return `有 ${pendingCount.value} 个媒体仍在准备中，请稍后刷新。`
  if (unreadyCount.value) return `有 ${unreadyCount.value} 个媒体不是 READY 状态，请刷新后重试。`
  if (videoCount.value > BYD_MAX_VIDEOS) return `正文含 ${videoCount.value} 个视频，超过每篇 ${BYD_MAX_VIDEOS} 个的上限。`
  return ''
})
const mediaReady = computed(() => loaded.value && hasContent.value && !mediaBlockReason.value)
const mediaSummary = computed(() => ({
  imageCount: imageCount.value,
  videoCount: videoCount.value,
  pendingCount: mediaChecking.value ? 0 : pendingCount.value,
  unresolvedCount: mediaChecking.value ? 0 : unresolvedCount.value,
  unreadyCount: mediaChecking.value ? 0 : unreadyCount.value
}))

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
      mediaStatus.value = Object.fromEntries(results)
      if (notify) {
        if (missingCount.value || pendingCount.value || malformedTokenCount.value || legacyTokenCount.value) ElMessage.warning('仍有媒体缺失或未准备完成，请处理后重试')
        else ElMessage.success('媒体状态已刷新')
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

const loadSummary = async () => {
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    const res = await projectApi.listVersions(projectId.value)
    if (res.code !== 0) throw new Error(res.msg || '版本加载失败')
    const versions = res.data || []
    if (!versions.length) throw new Error('项目还没有版本，请先完成编辑')
    const vid = props.project?.currentVersionId
    const v = versions.find((x) => String(x.id) === String(vid)) || versions[0]
    title.value = v.title || ''
    contentMd.value = v.contentMd || ''
    loaded.value = true
    await refreshMediaStatus()
  } catch (e) {
    loadError.value = e?.response?.data?.msg || e?.message || '网络异常'
  } finally {
    loading.value = false
  }
}

const goEditor = () => router.push({ name: 'project-byd-editor', params: { id: projectId.value } })

const header = usePageHeader()
const syncHeader = () => {
  if (!header) return
  header.actions = [{ key: 'back-editor', label: '← 返回编辑', onClick: goEditor }]
}
syncHeader()
onBeforeRouteLeave(() => { if (header) header.actions = [] })

// 项目晚到/重新生成后 currentVersionId 变化时刷新摘要（推送页无未保存编辑，可安全覆盖）
watch(() => props.project?.currentVersionId, (vid, old) => {
  if (vid != null && String(vid) !== String(old)) loadSummary()
})
onMounted(loadSummary)
onBeforeUnmount(() => { if (header) header.actions = [] })
</script>

<style scoped>
.byd-push-step { gap: var(--sp-4); }
.state-error { padding: var(--sp-8) var(--sp-4); }
.state-title { font-weight: 700; margin: var(--sp-2) 0 var(--sp-1); }
.state-msg { color: var(--muted); font-size: var(--fs-13); margin-bottom: var(--sp-4); }

.panel { border: 1px solid var(--line); border-radius: var(--radius-md); background: var(--card); padding: var(--sp-5); }
.panel-head { display: flex; align-items: baseline; gap: var(--sp-3); margin-bottom: var(--sp-4); padding-bottom: var(--sp-3); border-bottom: 1px solid var(--line); }
.panel-title { font-size: var(--fs-14); font-weight: 700; color: var(--ink); }
.panel-hint { font-size: var(--fs-12); color: var(--faint); }
.summary-head { display: flex; align-items: baseline; gap: var(--sp-3); margin-bottom: var(--sp-3); }
.summary-title { font-size: var(--fs-16); font-weight: 700; margin-bottom: var(--sp-2); }
.summary-tags { display: flex; gap: var(--sp-2); flex-wrap: wrap; margin-bottom: var(--sp-3); }
.summary-empty { font-size: var(--fs-13); color: var(--err); margin-bottom: var(--sp-3); }
.token-block { margin-top: calc(var(--sp-3) * -1); }
.push-tip { margin: 0; font-size: var(--fs-12); color: var(--faint); line-height: var(--lh-12); }
</style>
