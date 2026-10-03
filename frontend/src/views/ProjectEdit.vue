<template>
  <!-- 批 2:去掉 .container 窄卡 + 页内页头(标题/动作并入上下文条),改为全幅双列分区表单 -->
  <div class="page edit-page">
    <el-form :model="form" :rules="rules" ref="formRef" label-position="top" class="form-body">
      <div class="form-grid">
        <div class="form-col">
          <!-- 区块零:创作方式(主题创作/文章仿写;仿写时下方分支切换) -->
          <section class="form-sec">
            <div class="sec-head">
              <span class="sec-index">00</span>
              <div class="sec-title">
                <div class="sec-name">创作方式</div>
                <div class="sec-desc">从主题开始，或贴一篇好文章仿写成自己的风格</div>
              </div>
            </div>
            <el-form-item prop="genSource">
              <el-radio-group v-model="form.genSource">
                <el-radio-button value="TOPIC">主题创作</el-radio-button>
                <el-radio-button value="IMITATION">文章仿写</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="isImitation" prop="imitationText">
              <template #label>
                <span>参考原文 <span class="req-mark">必填</span></span>
              </template>
              <el-input v-model="form.imitationText" type="textarea" :rows="10" maxlength="20000" show-word-limit
                        placeholder="粘贴要仿写的参考原文（≤20000 字）。仿写保留其观点组织与信息脉络，以你选定的风格重新表达；原文图片不会保留，配图由你在配图步自行完成。" />
            </el-form-item>
            <el-form-item v-if="isImitation">
              <el-alert type="info" :closable="false" show-icon
                        title="仿写流程"
                        description="创建后进入「原文分析」：AI 分析题材/结构/句式并从风格库推荐匹配风格（≤3 个），选风格后仿写生成多版正文。生成后自动自检与原文的相似度，过度相似会红色警示。" />
            </el-form-item>
          </section>

          <!-- 区块一:创作主题(主输入,突出) -->
          <section class="form-sec">
            <div class="sec-head">
              <span class="sec-index">01</span>
              <div class="sec-title">
                <div class="sec-name">{{ isImitation ? '任务名' : '创作主题' }}</div>
                <div class="sec-desc">{{ isImitation ? '任务名用于项目列表与标题兜底，正文内容由仿写产出' : '一句话说清要写什么，这是简报与正文的锚点' }}</div>
              </div>
            </div>
            <el-form-item prop="topic" class="topic-item">
              <el-input v-model="form.topic" maxlength="200" show-word-limit size="large" @keydown.enter="onEnterSubmit"
                        :placeholder="isImitation ? '如：仿写那篇 AI 模型选型文章' : '如：如何选择自部署的国产 AI 模型'" />
            </el-form-item>
            <!-- 2026-09-09 模式收敛(09-09-brief-gen-redesign R2):取消快速/深度双选,所有生成必走深度流程(研究+反问) -->
            <el-form-item v-if="!isImitation">
              <el-alert type="info" :closable="false" show-icon
                        title="生成流程:深度研究模式"
                        description="创建后 AI 先生成研究计划并向你反问补充信息,确认后多代理并行研究(按系统设置启用内部知识库/外部搜索)再写作。创作不限于车型——任何汽车相关主题都可研究。" />
            </el-form-item>
          </section>
        </div>

        <div class="form-col">
          <!-- 区块二:内容设定 -->
          <section class="form-sec">
            <div class="sec-head">
              <span class="sec-index">02</span>
              <div class="sec-title">
                <div class="sec-name">内容设定</div>
                <div class="sec-desc">读者与篇幅，让生成更贴合目标</div>
              </div>
            </div>
            <el-form-item label="目标读者">
              <el-input v-model="form.audience" maxlength="200" placeholder="可选，如：后端工程师" @keydown.enter="onEnterSubmit" />
            </el-form-item>
            <el-form-item label="目标字数">
              <el-input-number v-model="form.wordCountTarget" :min="100" :max="10000" :step="100" controls-position="right" style="width:100%" />
            </el-form-item>
          </section>

          <!-- 区块三:素材与约束 -->
          <section class="form-sec">
            <div class="sec-head">
              <span class="sec-index">03</span>
              <div class="sec-title">
                <div class="sec-name">内容描述</div>
                <div class="sec-desc">补充个人见解与独家素材，让内容有据可依</div>
              </div>
            </div>
            <!-- 2026-09-09(dec-dd4ba6e1c6bfb7e7):移除「写作锚点车型」选择入口——创作不与车型绑定,
                 知识库停用期间该字段无生效点;后端关联逻辑保留,存量项目不受影响 -->
            <el-form-item label="内容描述（可选）">
              <el-input v-model="form.contentDescription" type="textarea" :rows="4" maxlength="5000" show-word-limit
                        placeholder="说明期望的内容方向/背景素材，如：围绕第2000座闪充站落成写一篇行业解读，突出长期战略目标…" />
              <div class="form-tip">填写后，会在澄清、研究、简报、深度写作、多版本全链路注入，作为创作素材与方向说明，不会遗漏关键内容。</div>
            </el-form-item>
          </section>
        </div>
      </div>

      <!-- 提交按钮已并入上下文条,此处只留流程说明(与主 CTA 强相关) -->
      <p class="form-tip form-tip-lead">{{ isImitation
        ? '「创建并分析原文」会进入详情页并调用 AI 分析原文与推荐风格（通常需要 10~30 秒）。'
        : '「创建并生成简报」会立即进入详情页并调用 AI 生成创作简报（通常需要 1~2 分钟）；深度模式先生成研究计划并进入反问环节。' }}</p>
    </el-form>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { useRouter, onBeforeRouteLeave } from 'vue-router'
import { projectApi } from '../api'
import { usePageHeader } from '../composables/usePageHeader'
import { ElMessage } from 'element-plus'

const router = useRouter()
const formRef = ref()
const loading = ref(false)   // 创建并生成
const saving = ref(false)    // 仅存草稿
// 创作方式(09-09-article-imitation):TOPIC 主题创作(默认)/IMITATION 文章仿写
const form = reactive({ genSource: 'TOPIC', topic: '', audience: '', wordCountTarget: 1500, contentDescription: '', imitationText: '' })
const isImitation = computed(() => form.genSource === 'IMITATION')
// 仿写时 topic 语义为「任务名」,校验文案随模式切换
const rules = computed(() => ({
  topic: [{ required: true, message: isImitation.value ? '请输入任务名' : '请输入主题', trigger: 'blur' }],
  imitationText: [{
    required: true, trigger: 'blur',
    validator: (rule, value, cb) => {
      if (!isImitation.value) return cb()
      if (!value || !value.trim()) return cb(new Error('请粘贴参考原文'))
      cb()
    }
  }]
}))

// 切回主题创作时清掉原文,避免残留文本被误提交(后端对 TOPIC 强制忽略,这里做 UI 一致性)
watch(isImitation, (v) => { if (!v) form.imitationText = '' })

// 2026-09-09:车型锚点选择入口已移除(创作不与车型绑定);不再加载车型列表

const doSave = async () => {
  await formRef.value.validate()
  // genSource 仅仿写时随 create 提交(主题创作不传,后端缺省 TOPIC)
  const payload = { ...form }
  if (isImitation.value) {
    payload.genSource = 'IMITATION'
  } else {
    delete payload.genSource
    delete payload.imitationText
  }
  const res = await projectApi.create(payload)
  return res.data
}

const onSave = async () => {
  saving.value = true
  try {
    const id = await doSave()
    ElMessage.success('已保存为草稿')
    // 跳转按模式分流:仿写与主题创作均不带深度意图参数;主题创作进入详情页后
    // 由 StepBrief 单次拉 /deep/status 恢复状态(09-11 去 ?gen=deep 路径意图)
    router.push(`/projects/${id}`)
  } finally { saving.value = false }
}

// 创建并生成:主按钮只负责「创建 + 启动意图澄清会话」,随即进详情页;
// 10-03 C5:唯一生成路径为深度认知流程(clarifyStart → 澄清对话 → 研究 → 蓝图评审)。
// clarifyStart 为同步 LLM(生成首题,约十秒级),这里 await 后再导航,消除「导航早于落库」竞态;
// 详情页据 /deep/status 的 ASKING 态恢复澄清对话。
// 仿写模式:保持原交互(创建后跳详情页并直接发起「分析原文」,失败也在详情页可见重试)。
const onSaveAndGenerate = async () => {
  // 同步重入守卫:validate 是异步的,防双击/连按 Enter 并发建两次项目(loading 必须在 validate 前置位)
  if (loading.value) return
  loading.value = true
  const imitation = isImitation.value
  try {
    await formRef.value.validate()
    const id = await doSave()
    if (imitation) {
      router.push(`/projects/${id}?gen=imitation`)
      try {
        // 09-27-gen-async 异步化:接口毫秒级返回(置 GENERATING_BRIEF),分析由后台执行;
        // 详情页据 project.status 展示进度(布局层 4s 轮询),READY 翻转后展示分析与推荐。
        await projectApi.analyzeImitation(id)
        ElMessage.success('已开始分析原文，请在简报页查看进度')
      } catch (e) {
        // 发起失败:已跳详情页,状态/lastBriefError 可见,由用户在页面内重试
      }
    } else {
      // 主题创作:先启动澄清会话(同步生成首题,落 ASKING 占位)再导航,详情页据 /deep/status 恢复对话态。
      // 10-03 C5:后端 clarify/start 无参(从项目实体读主题/内容描述/读者/字数),消除 body 与库不一致窗口
      try {
        await projectApi.clarifyStart(id)
      } catch (e) {
        // 发起失败(如并发冲突):项目已建,详情页据 /deep/status 展示并允许重试
      }
      router.push(`/projects/${id}`)
    }
  } catch (e) {
    // 仅创建本身失败(网络异常):留在此页;表单校验失败由 rules 提示,不重复弹窗
  } finally { loading.value = false }
}

// ==== 关键表单 Enter 提交(R6)====
// 只挂在单行输入上(创作主题/目标读者);textarea 保留换行不绑定。
// isComposing / keyCode 229:中文输入法组字过程中的回车是「选词」而非「提交」,
// 不加这个判断会在拼音上屏瞬间误触发创建(与 StepBrief 的 ClarifyDialog 同一口径)。
const onEnterSubmit = (e) => {
  if (e.isComposing || e.keyCode === 229) return
  e.preventDefault()
  // 校验失败时 validate() 会 reject(错误已由表单就地提示),此处吞掉避免未捕获拒绝
  onSaveAndGenerate().catch(() => {})
}

// ==== 上下文条(外壳 topbar):面包屑 + 提交动作(取代原 .form-actions 按钮行)====
// 两个 handler 均为 async,validate 失败会 reject;统一包一层 catch 再交给外壳 @click。
const guard = (fn) => () => Promise.resolve().then(fn).catch(() => {})
const header = usePageHeader()
const syncHeader = () => {
  if (!header) return
  header.crumbs = [{ label: '项目' }, { label: '新建创作任务' }]
  header.actions = [
    { key: 'draft', label: '仅存草稿', loading: saving.value, disabled: loading.value, onClick: guard(onSave) },
    {
      key: 'create',
      label: isImitation.value ? '创建并分析原文 →' : '创建并生成简报 →',
      type: 'primary',
      loading: loading.value,
      disabled: saving.value,
      onClick: guard(onSaveAndGenerate)
    }
  ]
}
syncHeader()
watch([loading, saving, isImitation], syncHeader)
onBeforeRouteLeave(() => { if (header) { header.crumbs = []; header.actions = [] } })
</script>

<style scoped>
/* 批 2:全幅双列分区(左:创作方式+主题;右:内容设定+素材),各分区自带卡片面 */
.edit-page { display: flex; flex-direction: column; }
.form-body { width: 100%; }
.form-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: var(--sp-6); align-items: start; }
.form-col { display: flex; flex-direction: column; gap: var(--sp-5); min-width: 0; }

/* 分区:编号 + 标题 + 描述,细描边面板(替代原整表一张大卡) */
.form-sec { padding: var(--sp-5); border: 1px solid var(--line); border-radius: var(--radius-md); background: var(--card); }
.sec-head { display: flex; align-items: flex-start; gap: var(--sp-3); margin-bottom: var(--sp-5); }
.sec-index {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: var(--radius-md);
  background: var(--brand-weak);
  color: var(--brand-strong);
  font-family: var(--font-mono);
  font-size: var(--fs-13);
  font-weight: 700;
}
.sec-title { display: flex; flex-direction: column; gap: var(--sp-1); min-width: 0; }
.sec-name { font-size: var(--fs-16); font-weight: 700; color: var(--ink); }
.sec-desc { font-size: var(--fs-12); color: var(--faint); line-height: var(--lh-12); }

/* 主题主输入:更大、更醒目 */
.topic-item :deep(.el-input__inner) { font-size: var(--fs-16); }
.req-mark { color: var(--el-color-danger); font-size: var(--fs-12); margin-left: var(--sp-1); }

.form-tip { margin: var(--sp-1) 0 0; font-size: var(--fs-12); color: var(--faint); line-height: var(--lh-12); }
.form-tip-lead { margin: var(--sp-5) 0 0; padding-top: var(--sp-4); border-top: 1px solid var(--line); }
</style>
