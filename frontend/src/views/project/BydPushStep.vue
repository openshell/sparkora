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
      <!-- 摘要：与编辑器同源（当前版本 title/contentMd），插图数解析正文引用 -->
      <section class="panel summary">
        <div class="summary-head">
          <span class="panel-title">推送摘要</span>
          <span class="panel-hint">编辑完成后在此推送；推送前必须完全退出微信电脑端</span>
        </div>
        <div class="summary-title">{{ title || '（未命名小程序文章）' }}</div>
        <div class="summary-tags">
          <el-tag size="small" effect="plain">正文 {{ wordCount }} 字</el-tag>
          <el-tag size="small" effect="plain" :type="imageCount ? 'success' : 'info'">插图 {{ imageCount }} 张</el-tag>
          <el-tag v-if="coverImageId" size="small" type="success" effect="plain">已选封面</el-tag>
        </div>
        <div v-if="!hasContent" class="summary-empty">
          正文为空：请先回到编辑步撰写文字（或从生成步让 AI 起草），推送按钮才会生效。
        </div>
        <el-button v-if="!hasContent" type="primary" plain @click="goEditor">去编辑正文</el-button>
      </section>

      <!-- 粘贴图占位防呆：token 会被 Agent 当成图片 URL，必须在编辑步上传/清除后才能推送 -->
      <el-alert v-if="hasTokenInBody" type="warning" :closable="false" show-icon
                title="正文含未上传或已失效的粘贴图占位"
                :description="tokenHint" />
      <div v-if="hasTokenInBody" class="token-block">
        <el-button type="primary" plain @click="goEditor">回到编辑步处理粘贴图</el-button>
      </div>

      <!-- 仅正文就绪且无占位时挂载 BYD 面板（Agent 状态 / 预检 / 车友会 / 任务轮询） -->
      <BydDynastyPanel v-else-if="hasContent" :project-id="projectId" :editor-or-above="editorOrAbove" />

      <p class="push-tip">
        推送任务由服务端编排、本机 Windows Agent 执行；写入前 Agent 会再次预检并备份 MMKV。
        重复推送会覆盖同一条小程序草稿（与 10-16 契约一致）。
      </p>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { WarningFilled } from '@element-plus/icons-vue'
import { projectApi } from '../../api'
import BydDynastyPanel from '../../components/publish/BydDynastyPanel.vue'
import { useUserStore } from '../../store/user'
import { usePageHeader } from '../../composables/usePageHeader'
import { countBodyImages } from '../../utils/bodyImageRefs'
import { extractTokens, get } from '../../utils/pendingImageStore'

/**
 * 王朝小程序工作台 · 推送步（10-19-channel-editor-publish R3/R5）。
 *
 * 摘要 + BYD 本地推送面板（原 StepPublish 内嵌组件整体迁移，props/逻辑不变）。
 * 本步不触碰公众号状态机与 publish_* 字段；推送结果由后端写 byd_dynasty_*。
 */
const props = defineProps({ project: Object })
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const projectId = computed(() => route.params.id)
const editorOrAbove = computed(() => userStore.isEditorOrAbove)

const loaded = ref(false)
const loadError = ref('')
const loading = ref(false)
const title = ref('')
const contentMd = ref('')
const coverImageId = ref(null)

const wordCount = computed(() => (contentMd.value || '').replace(/\s/g, '').length)
const imageCount = computed(() => countBodyImages(contentMd.value))
const hasContent = computed(() => !!(contentMd.value || '').trim())
const tokenIds = computed(() => extractTokens(contentMd.value))
const hasTokenInBody = computed(() => tokenIds.value.length > 0)
const isResolvableToken = (id) => {
  const e = get(id)
  return !!e && e.projectId === String(projectId.value)
}
const lostCount = computed(() => tokenIds.value.filter((id) => !isResolvableToken(id)).length)
const tokenHint = computed(() => {
  const n = tokenIds.value.length
  return lostCount.value
    ? `正文含 ${n} 处粘贴图占位，其中 ${lostCount.value} 处已失效（页面重载后暂存丢失）。请回到编辑步重新粘贴或删除占位。`
    : `正文含 ${n} 处尚未上传的粘贴图。请回到编辑步点「去推送」完成上传后再推送。`
})

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
    coverImageId.value = v.coverImageId ?? null
    loaded.value = true
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
