<template>
  <div class="step-body byd-generate-step">
    <!-- 官方活动发文：两项必填，AI 后台生成新版本；生成步是可选的（方式一可直接跳过进编辑器） -->
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="gen-form">
      <div class="gen-grid">
        <section class="panel">
          <div class="panel-head">
            <span class="panel-title">官方活动要求</span>
            <span class="panel-hint">把官方活动通知/规则原文贴进来（≤20000 字）</span>
          </div>
          <el-form-item prop="activityBrief">
            <el-input v-model="form.activityBrief" type="textarea" :rows="14" maxlength="20000" show-word-limit
                      :disabled="!editorOrAbove || busy"
                      placeholder="例如：比亚迪王朝「春日自驾」车主故事征集：分享一次与爱车的出行经历，字数不限，需包含车型、路线与真实感受……" />
          </el-form-item>
        </section>

        <section class="panel">
          <div class="panel-head">
            <span class="panel-title">我的情况</span>
            <span class="panel-hint">车型 / 用车经历 / 想分享的细节（≤5000 字）</span>
          </div>
          <el-form-item prop="personalInfo">
            <el-input v-model="form.personalInfo" type="textarea" :rows="8" maxlength="5000" show-word-limit
                      :disabled="!editorOrAbove || busy"
                      placeholder="例如：2023 款汉 DM-i 车主，去年国庆从成都自驾到稻城亚丁，全程 1600 公里，最难忘的是高原上纯电续航的表现……" />
          </el-form-item>
          <el-alert type="info" :closable="false" show-icon
                    title="生成结果会成为项目的新版本"
                    description="AI 按车友会口语化风格输出纯文本，生成后可直接进入编辑步修改，不会覆盖你已经写过的内容。" />
        </section>
      </div>
    </el-form>

    <el-alert v-if="error" type="error" :closable="false" show-icon class="gen-error"
              title="活动发文生成失败" :description="error" />

    <div v-if="generating" class="generating">
      <el-skeleton :rows="5" animated />
      <p class="gen-tip">
        <el-icon class="spin"><Loading /></el-icon>
        AI 正在根据活动要求与你的情况撰写文章，通常需要几十秒，请勿关闭页面…
      </p>
    </div>

    <p v-if="!editorOrAbove" class="readonly-tip">viewer 只读，生成文章需要 ADMIN/EDITOR 角色。</p>

    <p class="gen-note">
      也可以跳过本步，直接在编辑步手写文字并配图；两种方式最终都进入同一个编辑器与推送流程。
    </p>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { bydApi } from '../../api'
import { useUserStore } from '../../store/user'
import { useProjectDetailStore } from '../../store/project-detail'
import { usePageHeader } from '../../composables/usePageHeader'

/**
 * 王朝小程序工作台 · 生成步（10-19-channel-editor-publish R3/R4）。
 *
 * 方式二「官方活动发文」：粘贴官方活动要求 + 填写自己的情况 → 后端异步生成新版本，
 * 前端轮询 generate-state 直到 generating=false，成功后进入编辑步继续打磨。
 * 方式一（自由手写）可直接「跳过，直接进入编辑器」。
 */
const props = defineProps({ project: Object })
const route = useRoute()
const router = useRouter()
const store = useProjectDetailStore()
const userStore = useUserStore()

const projectId = computed(() => route.params.id)
const editorOrAbove = computed(() => userStore.isEditorOrAbove)
const busy = computed(() => starting.value || generating.value)

const formRef = ref()
const form = reactive({ activityBrief: '', personalInfo: '' })
const rules = {
  activityBrief: [{ required: true, message: '请粘贴官方活动要求', trigger: 'blur' }],
  personalInfo: [{ required: true, message: '请填写你的情况', trigger: 'blur' }]
}

const starting = ref(false)      // 发起请求中（毫秒级）
const generating = ref(false)    // 后台 AI 生成中（轮询得到）
const error = ref('')
let pollTimer = null
let wasGenerating = false
let baselineVersionId = null

const stopPoll = () => {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = null
}

/** 生成完成：刷新项目/版本缓存并跳编辑步；失败则停留本页展示中文原因。 */
const onFinished = async (state) => {
  await store.ensureProject(projectId.value, { force: true }).catch(() => {})
  await store.ensureVersions(projectId.value, { force: true }).catch(() => {})
  const message = state?.lastError || ''
  if (message) {
    error.value = message
    return
  }
  const newVersionId = state?.currentVersionId
  if (newVersionId != null && String(newVersionId) !== String(baselineVersionId)) {
    ElMessage.success('文章已生成，进入编辑器继续修改')
    router.push({ name: 'project-byd-editor', params: { id: projectId.value } })
    return
  }
  error.value = '生成已结束，但没有产生新版本。请重试，或直接进入编辑器手写。'
}

const tick = async () => {
  try {
    const res = await bydApi.generateState(projectId.value)
    if (res.code !== 0) throw new Error(res.msg || '查询生成状态失败')
    const state = res.data || {}
    generating.value = !!state.generating
    if (state.lastError) error.value = state.lastError
    if (!state.generating && wasGenerating) {
      wasGenerating = false
      stopPoll()
      await onFinished(state)
    }
  } catch (e) {
    // 轮询偶发失败不终止：保留在途状态，下一轮继续；连续失败用户可手动刷新页面恢复
    console.warn('[sparkora] 小程序生成状态轮询失败:', e?.message || e)
  }
}

const startPoll = () => {
  stopPoll()
  pollTimer = setInterval(tick, 2000)
}

const onGenerate = async () => {
  if (busy.value || !editorOrAbove.value) return
  // 重入守卫必须早于第一个 await：validate 是异步的，先 await 再置位会让双击并发提交两次
  //（虽然后端状态机会拒绝第二次，但用户会白白看到「正在生成中」错误）。
  starting.value = true
  try {
    await formRef.value.validate()
  } catch {
    starting.value = false
    return // 校验失败已由表单就地提示
  }
  error.value = ''
  baselineVersionId = props.project?.currentVersionId ?? null
  try {
    const res = await bydApi.generate(projectId.value, {
      activityBrief: form.activityBrief,
      personalInfo: form.personalInfo
    })
    if (res.code !== 0) throw new Error(res.msg || '启动生成失败')
    generating.value = true
    wasGenerating = true
    startPoll()
    ElMessage.info('已开始生成，请稍候…')
  } catch (e) {
    error.value = e?.response?.data?.msg || e?.message || '启动生成失败'
  } finally {
    starting.value = false
  }
}

const goEditor = () => router.push({ name: 'project-byd-editor', params: { id: projectId.value } })

// 上下文条：ProjectLayout 持有面包屑，子页只挂动作
const header = usePageHeader()
const syncHeader = () => {
  if (!header) return
  const actions = [{ key: 'skip', label: '跳过，直接进入编辑 →', onClick: goEditor }]
  if (editorOrAbove.value) {
    actions.unshift({
      key: 'generate',
      label: 'AI 生成文章',
      type: 'primary',
      loading: busy.value,
      disabled: busy.value,
      onClick: onGenerate
    })
  }
  header.actions = actions
}
syncHeader()
watch([busy, editorOrAbove], syncHeader)
onBeforeRouteLeave(() => { if (header) header.actions = [] })

onMounted(async () => {
  try {
    const res = await bydApi.generateState(projectId.value)
    if (res.code === 0) {
      const state = res.data || {}
      if (state.lastError) error.value = state.lastError
      if (state.generating) {
        generating.value = true
        wasGenerating = true
        baselineVersionId = state.currentVersionId ?? props.project?.currentVersionId ?? null
        startPoll()
      }
    }
  } catch (e) {
    console.warn('[sparkora] 初始化生成状态失败:', e?.message || e)
  }
})

onBeforeUnmount(stopPoll)
</script>

<style scoped>
.byd-generate-step { gap: var(--sp-5); }
.gen-grid { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: var(--sp-5); align-items: start; }
.panel { border: 1px solid var(--line); border-radius: var(--radius-md); background: var(--card); padding: var(--sp-5); }
.panel-head { display: flex; align-items: baseline; gap: var(--sp-3); margin-bottom: var(--sp-4); padding-bottom: var(--sp-3); border-bottom: 1px solid var(--line); }
.panel-title { font-size: var(--fs-14); font-weight: 700; color: var(--ink); }
.panel-hint { font-size: var(--fs-12); color: var(--faint); }
.gen-form :deep(.el-form-item) { margin-bottom: var(--sp-3); }
.gen-form :deep(.el-form-item:last-child) { margin-bottom: 0; }
.gen-error { margin-top: var(--sp-2); }
.generating { padding: var(--sp-4); border: 1px solid var(--line); border-radius: var(--radius-md); background: var(--n-50); }
.gen-tip { display: flex; align-items: center; gap: var(--sp-2); margin: var(--sp-3) 0 0; color: var(--muted); font-size: var(--fs-13); }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.readonly-tip { color: var(--muted); font-size: var(--fs-12); }
.gen-note { margin: 0; font-size: var(--fs-12); color: var(--faint); }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
