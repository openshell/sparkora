<template>
  <!-- 批 2:去掉 el-card 包裹与页内页头(标题并入上下文条),动作行并入上下文条 -->
  <div class="step-body publish-step">
    <!-- 前置未就绪(状态不该到这步:步骤导航已锁,兜底防护) -->
    <div v-if="!publishable" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">尚未生成正文版本</div>
      <div class="state-msg">请先完成「版本」步骤,状态推进到「版本就绪」后即可发布。</div>
    </div>

    <template v-else>
      <!-- 加载失败可见化 + 重试 -->
      <div v-if="loadError && !optionsLoaded" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">发布参数加载失败</div>
        <div class="state-msg">{{ loadError }}</div>
        <el-button type="primary" plain @click="loadOptions">重试</el-button>
      </div>

      <template v-else>
        <!-- 发布通道不可用黄条(只拦 ADMIN/EDITOR 的发布动作,viewer 只读可见) -->
        <el-alert v-if="optsLoadedOnce && !publishEnabled" type="warning" :closable="false" show-icon class="top-alert"
                  title="发布通道暂不可用"
                  :description="publishDisabledReason || 'wenyan-server 未配置或不可达,请联系管理员检查 WENYAN_MCP_* 配置。'" />

        <!-- 最近一次发布失败原因黄条(后端写回 lastPublishError) -->
        <el-alert v-if="publishError" type="error" :closable="false" show-icon class="top-alert"
                  title="最近一次发布失败" :description="publishError" />

        <!-- 成功态:已进草稿箱(可重发覆盖) -->
        <div v-if="published" class="success-box">
          <el-icon :size="30" color="var(--ok)"><SuccessFilled /></el-icon>
          <div class="success-title">已发布到公众号草稿箱</div>
          <div class="success-meta">
            <span v-if="options.publishMediaId">media_id: <code>{{ options.publishMediaId }}</code></span>
            <span v-if="options.publishedAt">发布时间: {{ shortTime(options.publishedAt) }}</span>
            <span v-if="options.publishTheme">排版主题: {{ themeLabel(options.publishTheme) }}</span>
          </div>
        </div>

        <!-- 发布摘要:标题 / 封面 / 插图数(与预览渲染同源数据) -->
        <div class="summary" v-loading="!summaryLoaded">
          <template v-if="summaryLoaded">
            <img v-if="coverUrl" :src="coverUrl" class="summary-cover" alt="封面" />
            <div v-else class="summary-cover summary-cover-empty">
              <span>无封面</span>
            </div>
            <div class="summary-info">
              <div class="summary-topic">{{ project?.topic || '无标题' }}</div>
              <div class="summary-title">版本标题: {{ versionTitle || '(未命名)' }}</div>
              <div class="summary-sub">
                <el-tag size="small" effect="plain">正文 {{ wordCount }} 字</el-tag>
                <el-tag size="small" effect="plain" :type="bodyImageCount ? 'success' : 'info'">
                  插图 {{ bodyImageCount }} 张
                </el-tag>
                <el-tag size="small" effect="plain" :type="coverUrl ? 'success' : 'danger'">
                  {{ coverUrl ? '已选封面' : '未选封面(公众号要求必选)' }}
                </el-tag>
              </div>
              <div v-if="!coverUrl" class="cover-required-tip">
                <el-icon><WarningFilled /></el-icon>公众号要求文章至少要有封面图，请到「预览」步骤为当前版本设置封面。
              </div>
              <div v-if="!editorOrAbove" class="readonly-tip">viewer 只读,发布需 ADMIN/EDITOR 角色</div>
            </div>
          </template>
        </div>

        <!-- 排版参数(只读回显) + 发布元信息(手填):全幅双列,替代原两条通栏条 -->
        <div class="pub-grid">
          <section class="panel">
            <div class="panel-head">
              <span class="panel-title">排版参数</span>
              <span class="panel-hint">在「预览」步骤设置,发布时原样传给 wenyan-server</span>
            </div>
            <div class="style-readonly">
              <div class="ctrl-group">
                <span class="field-label">主题</span>
                <span class="ro-value">
                  <span class="theme-dot" :style="{ background: themeColor(theme) }" :class="{ 'is-bright': themeIsBright(theme) }"></span>
                  <span class="ro-text">{{ themeLabel(theme) }}</span>
                </span>
              </div>
              <div class="ctrl-group">
                <span class="field-label">高亮</span>
                <span class="ro-value"><span class="ro-text">{{ highlight }}</span></span>
              </div>
              <div class="ctrl-group">
                <span class="field-label">Mac 代码块</span>
                <span class="ro-value"><span class="ro-text">代码块样式</span>
                  <el-tag size="small" :type="macStyle ? 'success' : 'info'" effect="plain">{{ macStyle ? '开' : '关' }}</el-tag>
                </span>
              </div>
              <div class="ctrl-group">
                <span class="field-label">链接转脚注</span>
                <span class="ro-value"><span class="ro-text">外链脚注</span>
                  <el-tag size="small" :type="footnote ? 'success' : 'info'" effect="plain">{{ footnote ? '开' : '关' }}</el-tag>
                </span>
              </div>
            </div>
          </section>

          <section class="panel">
            <div class="panel-head">
              <span class="panel-title">发布元信息</span>
              <span class="panel-hint">选填,项目级落库,留空不发送</span>
            </div>
            <div class="meta-form">
              <div class="meta-field">
                <span class="field-label">作者</span>
                <el-input v-model="author" maxlength="100" clearable placeholder="选填,写入公众号作者"
                          :disabled="!editorOrAbove || publishing" @input="onMetaChange" />
              </div>
              <div class="meta-field">
                <span class="field-label">原文地址</span>
                <el-input v-model="sourceUrl" maxlength="500" clearable placeholder="选填,写入公众号原文链接"
                          :disabled="!editorOrAbove || publishing" @input="onMetaChange" />
              </div>
            </div>
            <p v-if="!editorOrAbove" class="readonly-tip">viewer 只读,发布需 ADMIN/EDITOR 角色</p>
          </section>
        </div>

        <!-- 发布进行中提示(/publish 非幂等:重复点击会产生重复草稿,必须显式警示) -->
        <p v-if="publishing" class="pub-hint">正在渲染并通过 wenyan-server 写入草稿箱,约 1 分钟…请勿刷新或重复点击(重复发布会产生重复草稿)</p>
      </template>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { projectApi, imageApi } from '../../api'
import { useUserStore } from '../../store/user'
import { useProjectDetailStore } from '../../store/project-detail'
import { usePageHeader } from '../../composables/usePageHeader'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled, SuccessFilled } from '@element-plus/icons-vue'
import { isPublishable } from '../../constants/project'
import { hasToken, hasAny, extractTokens, get } from '../../utils/pendingImageStore'
import { countBodyImages } from '../../utils/bodyImageRefs'

/**
 * Step 4 · 发布(S5):同源渲染(与 Step3 preview 完全同参)→ wenyan-server(公众号草稿箱)。
 * 后端 PublishService 保证 preview HTML = 发布真值;本页只做参数选择 + 摘要确认 + 状态展示。
 */
const props = defineProps({ project: Object })
const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.id)
const userStore = useUserStore()
const store = useProjectDetailStore()

// 发布参数(与预览页同形;默认值优先项目级 preview* 样式,回退 .env 配置)
const theme = ref('default')
const highlight = ref('solarized-light')
const macStyle = ref(true)
const footnote = ref(true)
// 发布元信息(手填,项目级落库)
const author = ref('')
const sourceUrl = ref('')
let metaDirty = false

const optionsLoaded = ref(false)
const optsLoadedOnce = ref(false)
const loadError = ref('')
const publishing = ref(false)
const summaryLoaded = ref(false)

// publish-options 下发的运行参数
const options = ref({
  publishEnabled: false,
  publishConfigOk: false,
  publishDisabledReason: '',
  wenyanServer: '',
  publishMediaId: '',
  publishTheme: '',
  publishedAt: '',
  lastPublishError: '',
  previewTheme: '',
  previewHighlight: null,
  previewMacStyle: null,
  previewFootnote: null,
  author: '',
  sourceUrl: '',
  themes: []
})

const publishable = computed(() => isPublishable(props.project?.status))
const published = computed(() => props.project?.status === 'PUBLISHED_DRAFT')
const editorOrAbove = computed(() => userStore.isEditorOrAbove)
const publishError = computed(() => options.value.lastPublishError || props.project?.lastPublishError || '')
const publishEnabled = computed(() => !!options.value.publishEnabled)

// ==== 主题目录(与 StepPreview 同源,来自 publish-options.themes 对象数组) ====
/** 按 id 查目录项(未知返回 undefined)。 */
const themeMeta = (t) => (options.value.themes || []).find((x) => x.id === t)
const themeColor = (t) => themeMeta(t)?.color || '#8a8f98'
const themeIsBright = (t) => !!themeMeta(t)?.bright
/** 主题显示名:内置主题为 id 原样,社区主题为中文名。 */
const themeLabel = (t) => themeMeta(t)?.name || t || ''

// ==== 摘要:标题/字数/封面/插图(与 Step3 同一接口,口径一致) ====
const versionTitle = ref('')
const contentMd = ref('')
const coverUrl = ref('')
/** 插图数:解析正文图片引用,与预览页工具栏同一口径(09-27-image-insert-bugs)。
 *  不再数关联表(bodyImageIds)——它对「手工编辑正文」不敏感(删图不摘登记),数出来会虚高。 */
const bodyImageCount = computed(() => countBodyImages(contentMd.value))
const wordCount = computed(() => (contentMd.value || '').replace(/\s/g, '').length)

const loadSummary = async () => {
  summaryLoaded.value = false
  try {
    // 项目图快照(含 currentVersionId/coverImageId/coverImage) + 版本列表取标题与正文
    const res = await imageApi.projectImages(projectId.value)
    if (res.code !== 0) throw new Error(res.msg || '配图快照加载失败')
    const vid = res.data?.currentVersionId
    coverUrl.value = ''
    // S10:封面 URL 改读服务端解析的 coverImage.url（不再自行从全量 images find）
    const img = res.data?.coverImage
    if (img?.url) coverUrl.value = img.url
    if (vid) {
      const vr = await projectApi.listVersions(projectId.value)
      if (vr.code === 0) {
        const v = (vr.data || []).find(x => x.id === vid)
        if (v) { versionTitle.value = v.title || ''; contentMd.value = v.contentMd || '' }
      }
    }
  } catch (e) {
    // 摘要失败不阻塞发布主流程,字段留空
    console.warn('[sparkora] 发布摘要加载失败:', e)
  } finally {
    summaryLoaded.value = true
  }
}

const loadOptions = async () => {
  loadError.value = ''
  try {
    // publish-options:主题/高亮/默认开关 + 通道就绪度 + 历史发布信息(publishOptions 接口)
    const res = await projectApi.publishOptions(projectId.value)
    if (res.code !== 0) throw new Error(res.msg || '发布参数加载失败')
    const d = res.data || {}
    options.value = { ...options.value, ...d }
    // 排版参数只读回显:优先项目级预览样式(preview*),其次已发布主题(publishTheme),最后全局默认(09-11-preview-publish-bridge)
    theme.value = d.previewTheme || d.publishTheme || d.defaultTheme || 'default'
    highlight.value = d.previewHighlight || d.highlight || 'solarized-light'
    macStyle.value = d.previewMacStyle ?? d.macStyle ?? true
    footnote.value = d.previewFootnote ?? d.footnote ?? true
    // 发布元信息:项目级落库值(可能为空),仅首次初始化,避免覆盖用户正在输入的内容
    if (!metaDirty) {
      author.value = d.author || ''
      sourceUrl.value = d.sourceUrl || ''
    }
    optionsLoaded.value = true
    optsLoadedOnce.value = true
  } catch (e) {
    loadError.value = e?.response?.data?.msg || e?.message || '网络异常'
  }
}

const shortTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

// ==== 发布元信息落库(项目级,防抖 400ms;失败仅 warn 不阻塞发布) ====
let metaTimer = null
let metaSeq = 0 // 编辑序号:识别「请求在途期间用户又改了」,避免旧响应清掉新的待存标记
const onMetaChange = () => {
  metaDirty = true
  metaSeq += 1
  clearTimeout(metaTimer)
  metaTimer = setTimeout(saveMeta, 400)
}
/** 立即落库待保存的元信息(发布前/离开页面前调用,避免防抖窗口内丢失)。 */
const flushSaveMeta = () => {
  if (!metaDirty) return Promise.resolve(true)
  clearTimeout(metaTimer)
  metaTimer = null
  return saveMeta()
}
const saveMeta = async () => {
  const seq = metaSeq
  try {
    const res = await projectApi.savePublishMeta(projectId.value, {
      author: author.value, sourceUrl: sourceUrl.value
    })
    if (res.code !== 0) { console.warn('[sparkora] 发布元信息保存失败:', res.msg); return false }
    // 仅当本次请求发起后没有新编辑时才清 dirty;否则保留标记,等下一次防抖/flush 再存
    if (seq === metaSeq) {
      metaDirty = false
      // 就地回写 store 缓存,避免子步骤切换时元信息回退
      store.patchProject(projectId.value, { author: author.value || null, sourceUrl: sourceUrl.value || null })
    }
    return true
  } catch (e) {
    console.warn('[sparkora] 发布元信息保存失败:', e?.message || e)
    return false
  }
}

/** 发布前二次确认(发布属外部可见动作;重发亦确认)。 */
const confirmPublish = () => {
  ElMessageBox.confirm(
    published.value
      ? '将重新渲染并覆盖公众号草稿箱中的这篇草稿,确定继续?'
      : '将按当前排版渲染并写入公众号草稿箱(不直接群发),确定继续?',
    published.value ? '重发确认' : '发布确认',
    { confirmButtonText: '确认发布', cancelButtonText: '再想想', type: 'warning' }
  ).then(() => doPublish()).catch(() => {})
}

const doPublish = async () => {
  // 防重入(/publish 非幂等,重复执行会产生重复草稿)。按钮 loading 已隐式禁用,
  // 但「双击开出两个确认弹层」「Enter 快速确认」等路径仍可能在 publishing 置位后再次进来,这里兜底。
  if (publishing.value) return
  // 发布防呆(09-27-preview-clipboard-image R5.2):正文含 token 占位时阻止发布;摘要尚未加载完(shell 未知)
  // 且本项目仍有暂存条目时也阻止(无法确认正文干净,宁可不发)。后端另有同口径兜底(R5.3)。
  const pendingUnverified = !summaryLoaded.value && hasAny(projectId.value)
  if (hasToken(contentMd.value) || pendingUnverified) {
    // 09-27-image-insert-bugs：区分「待上传」与「已失效」。已失效的（页面重载丢了暂存）
    // 点「去发布」也传不上去,提示必须指向「重新粘贴/删占位」,否则是把用户引到无效操作。
    const lost = extractTokens(contentMd.value).filter((id) => !get(id)).length
    ElMessage.warning(lost
      ? `当前正文含 ${lost} 处已失效的粘贴图（页面重载后暂存丢失），请回到「预览」步骤重新粘贴或删除占位后再发布`
      : '当前正文含未上传的粘贴图，请回到「预览」步骤点「去发布」完成上传后再发布')
    return
  }
  publishing.value = true
  try {
    // 发布元信息有未落库修改则先保存,保证发布读到的 author/source_url 与表单一致
    if (metaDirty) {
      const ok = await flushSaveMeta()
      if (!ok) { ElMessage.error('作者/原文地址保存失败,已中止发布'); return }
    }
    const res = await projectApi.publish(projectId.value, {
      theme: theme.value, highlight: highlight.value, macStyle: macStyle.value, footnote: footnote.value
    })
    if (res.code !== 0) throw new Error(res.msg || '发布失败')
    ElMessage.success('已发布到公众号草稿箱')
    // 刷新项目状态(VERSIONS_READY → PUBLISHED_DRAFT)与发布信息,成功态自然浮现
    await store.ensureProject(projectId.value, { force: true })
    await loadOptions()
  } catch (e) {
    const msg = e?.response?.data?.msg || e?.message || '发布失败'
    ElMessage.error(msg)
    // 后端已把失败原因写进 lastPublishError:同步刷新项目与通道状态供黄条展示
    await store.ensureProject(projectId.value, { force: true }).catch(() => {})
    loadOptions()
  } finally {
    publishing.value = false
  }
}

/** 跳回预览步复核。 */
const goPreview = () => {
  router.push({ name: 'project-preview', params: { id: projectId.value } })
}

/** 「再检查一遍渲染」:跳回预览步复核后再回来。 */
const recheckPreview = goPreview

// ==== 上下文条(外壳 topbar):面包屑 + 发布动作(取代原 .publish-actions 按钮行)====
// 声明放在 goPreview/recheckPreview/confirmPublish 之后(action 直接引用它们,避免 TDZ)。
// 禁用条件与原按钮逐字一致:通道未就绪或未选封面不可发布;发布中 loading/disabled 双重防重入。
const header = usePageHeader()
const syncHeader = () => {
  if (!header) return
  header.crumbs = [{ label: '项目' }, { label: '发布' }]
  // 参数尚未装载(加载中/失败)时不挂动作:此时按钮状态不可信,交给页内错误卡片给重试
  if (!publishable.value || !optionsLoaded.value) { header.actions = []; return }
  const actions = [{ key: 'back', label: '← 返回预览', onClick: () => goPreview() }]
  if (editorOrAbove.value) {
    if (published.value) {
      actions.push({ key: 'recheck', label: '再检查一遍渲染', disabled: publishing.value, onClick: () => recheckPreview() })
    }
    actions.push({
      key: 'publish',
      label: published.value ? '重发(覆盖草稿)' : '确认发布到草稿箱',
      type: 'primary',
      loading: publishing.value,
      disabled: !publishEnabled.value || !coverUrl.value,
      onClick: () => confirmPublish()
    })
  }
  header.actions = actions
}
syncHeader()
watch([publishable, optionsLoaded, published, editorOrAbove, publishing, publishEnabled, coverUrl], syncHeader)
onBeforeRouteLeave(() => { if (header) { header.crumbs = []; header.actions = [] } })

onMounted(() => {
  loadOptions()
  loadSummary()
})

watch(publishable, (ok) => {
  if (ok && !optionsLoaded.value) { loadOptions(); loadSummary() }
})

onBeforeUnmount(() => { flushSaveMeta() })
</script>

<style scoped>
/* 批 2:步骤主体全幅(纵向 flex),页头/动作行已并入上下文条 */
.publish-step { gap: var(--sp-5); }

.state-error { padding: var(--sp-8) var(--sp-4); }
.state-title { font-weight: 700; margin: var(--sp-2) 0 var(--sp-1); }
.state-msg { color: var(--muted); font-size: var(--fs-13); margin-bottom: var(--sp-4); }
.top-alert { margin-bottom: 0; }

/* 成功态卡片 */
.success-box {
  display: flex; flex-direction: column; align-items: flex-start; gap: var(--sp-2);
  padding: var(--sp-5); border: 1px solid var(--ok); border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--ok) 7%, transparent);
}
.success-title { font-weight: 700; font-size: var(--fs-16); }
.success-meta { display: flex; flex-wrap: wrap; gap: var(--sp-1) var(--sp-5); font-size: var(--fs-12); color: var(--muted); }
.success-meta code { font-size: var(--fs-12); word-break: break-all; }

/* 发布摘要:封面 + 标题/字数/插图(与预览渲染同源数据) */
.summary {
  display: flex; gap: var(--sp-5); align-items: flex-start;
  padding: var(--sp-5); min-height: 96px;
  border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--card);
}
.summary-cover { width: 120px; height: 80px; object-fit: cover; border-radius: var(--radius-sm); border: 1px solid var(--line); flex: none; }
.summary-cover-empty {
  display: flex; align-items: center; justify-content: center;
  font-size: var(--fs-12); color: var(--muted); background: var(--el-fill-color-light);
}
.summary-info { min-width: 0; }
.summary-topic { font-weight: 700; font-size: var(--fs-16); margin-bottom: var(--sp-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.summary-title { font-size: var(--fs-13); color: var(--ink); margin-bottom: var(--sp-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.summary-sub { display: flex; gap: var(--sp-2); flex-wrap: wrap; }
.cover-required-tip {
  display: flex; align-items: center; gap: var(--sp-2);
  margin-top: var(--sp-2); font-size: var(--fs-12); color: var(--err);
}
.readonly-tip { font-size: var(--fs-12); color: var(--muted); margin: var(--sp-2) 0 0; }

/* 双列面板:左=排版参数只读 / 右=发布元信息(替代原两条通栏条) */
.pub-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: var(--sp-5); align-items: start; }
.panel { border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--card); padding: var(--sp-5); }
.panel-head { display: flex; align-items: baseline; gap: var(--sp-3); margin-bottom: var(--sp-4); padding-bottom: var(--sp-3); border-bottom: 1px solid var(--line); }
.panel-title { font-size: var(--fs-14); font-weight: 700; color: var(--ink); }
.panel-hint { font-size: var(--fs-12); color: var(--faint); }

.style-readonly { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--sp-3) var(--sp-5); }
.ctrl-group { display: inline-flex; align-items: center; gap: var(--sp-2); min-width: 0; }
.field-label { font-size: var(--fs-13); color: var(--muted); flex: none; white-space: nowrap; }
.ro-value { display: inline-flex; align-items: center; gap: var(--sp-2); min-width: 0; }
.ro-text { font-size: var(--fs-13); color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.theme-dot { width: 10px; height: 10px; border-radius: 50%; flex: none; box-shadow: inset 0 0 0 1px rgba(0,0,0,.08); }
.theme-dot.is-bright { box-shadow: inset 0 0 0 1px rgba(0,0,0,.14); }

/* 发布元信息(作者/原文地址,项目级落库) */
.meta-form { display: flex; flex-direction: column; gap: var(--sp-4); }
.meta-field { display: flex; align-items: center; gap: var(--sp-3); }
.meta-field .field-label { width: 64px; }
.meta-field .el-input { flex: 1; min-width: 0; }

/* 发布进行中提示条 */
.pub-hint { margin: 0; font-size: var(--fs-12); color: var(--muted); }
</style>