<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">New Project</span>
          <h2>新建创作任务</h2>
          <p class="head-sub">主题创作或粘贴原文仿写，AI 将据此生成创作简报</p>
        </div>
        <div class="actions">
          <el-button text @click="$router.push('/')">← 返回</el-button>
        </div>
      </div>

      <el-form :model="form" :rules="rules" ref="formRef" label-position="top" class="form-card">
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
            <el-radio-group v-model="form.genSource" size="large">
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
            <el-input v-model="form.topic" maxlength="200" show-word-limit size="large"
                      :placeholder="isImitation ? '如：仿写那篇 AI 模型选型文章' : '如：如何选择自部署的国产 AI 模型'" />
          </el-form-item>
          <!-- 2026-09-09 模式收敛(09-09-brief-gen-redesign R2):取消快速/深度双选,所有生成必走深度流程(研究+反问) -->
          <el-form-item v-if="!isImitation" class="depth-item">
            <el-alert type="info" :closable="false" show-icon
                      title="生成流程:深度研究模式"
                      description="创建后 AI 先生成研究计划并向你反问补充信息,确认后多代理并行研究(按系统设置启用内部知识库/外部搜索)再写作。创作不限于车型——任何汽车相关主题都可研究。" />
          </el-form-item>
        </section>

        <!-- 区块二:内容设定 -->
        <section class="form-sec">
          <div class="sec-head">
            <span class="sec-index">02</span>
            <div class="sec-title">
              <div class="sec-name">内容设定</div>
              <div class="sec-desc">关键词、读者与篇幅，让生成更贴合目标</div>
            </div>
          </div>
          <el-form-item label="关键词">
            <el-input v-model="form.keywords" maxlength="500" placeholder="逗号分隔，可选" />
          </el-form-item>
          <el-row :gutter="12">
            <el-col :xs="24" :sm="12">
              <el-form-item label="目标读者">
                <el-input v-model="form.audience" maxlength="200" placeholder="可选，如：后端工程师" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12">
              <el-form-item label="目标字数">
                <el-input-number v-model="form.wordCountTarget" :min="100" :max="10000" :step="100" style="width:100%" />
              </el-form-item>
            </el-col>
          </el-row>
        </section>

        <!-- 区块三:素材与约束 -->
        <section class="form-sec">
          <div class="sec-head">
            <span class="sec-index">03</span>
            <div class="sec-title">
              <div class="sec-name">素材与约束</div>
              <div class="sec-desc">补充个人见解与独家素材，让内容有据可依</div>
            </div>
          </div>
          <!-- 2026-09-09(dec-dd4ba6e1c6bfb7e7):移除「写作锚点车型」选择入口——创作不与车型绑定,
               知识库停用期间该字段无生效点;后端关联逻辑保留,存量项目不受影响 -->
          <el-form-item label="补充信息（可选）">
            <el-input v-model="form.extraInfo" type="textarea" :rows="4" maxlength="5000" show-word-limit
                      placeholder="个人见解、独家资讯等，生成简报/正文时会作为创作素材融入，如：我了解到该车型 2026 款将新增 XX 配置…" />
            <div class="form-tip" style="text-align:left;margin-top:4px">填写后，生成简报/正文时会注入这些信息作为创作素材，不会遗漏关键内容。</div>
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" placeholder="可选" />
          </el-form-item>
        </section>

        <div class="form-actions">
          <!-- 主操作唯一:「创建并生成简报」;仅存草稿降为次级按钮,取消为文字按钮 -->
          <el-button text @click="$router.push('/')">取消</el-button>
          <el-button :loading="saving" @click="onSave">仅存草稿</el-button>
          <el-button type="primary" :loading="loading" @click="onSaveAndGenerate">{{ isImitation ? '创建并分析原文 →' : '创建并生成简报 →' }}</el-button>
        </div>
        <p class="form-tip">{{ isImitation
          ? '「创建并分析原文」会进入详情页并调用 AI 分析原文与推荐风格（通常需要 10~30 秒）。'
          : '「创建并生成简报」会立即进入详情页并调用 AI 生成创作简报（通常需要 1~2 分钟）；深度模式先生成研究计划并进入反问环节。' }}</p>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { projectApi } from '../api'
import { ElMessage } from 'element-plus'
import TopBar from '../layouts/TopBar.vue'

const router = useRouter()
const formRef = ref()
const loading = ref(false)   // 创建并生成
const saving = ref(false)    // 仅存草稿
// 创作方式(09-09-article-imitation):TOPIC 主题创作(默认)/IMITATION 文章仿写
const form = reactive({ genSource: 'TOPIC', topic: '', keywords: '', audience: '', wordCountTarget: 1500, remark: '', extraInfo: '', imitationText: '' })
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
    router.push(`/projects/${id}?gen=deep`)
  } finally { saving.value = false }
}

// 创建并生成:主按钮只负责「创建 + 发起深度研究计划」,立即进详情页;
// 生成过程由详情页按 project.status 展示(布局层 4s 轮询,简报页以 GENERATING_BRIEF 状态为事实源)。
// 2026-09-09 模式收敛:唯一生成路径为深度流程(startDeep),快速模式入口已下线。
// 仿写模式:创建后跳详情页并直接发起「分析原文」(失败也在详情页可见重试)。
const onSaveAndGenerate = async () => {
  await formRef.value.validate()
  loading.value = true
  const imitation = isImitation.value
  try {
    const id = await doSave()
    router.push(imitation ? `/projects/${id}?gen=imitation` : `/projects/${id}?gen=deep`)
    try {
      if (imitation) {
        await projectApi.analyzeImitation(id)
        ElMessage.success('原文分析完成，请在简报页查看分析与风格推荐')
      } else {
        // 深度流程:发起研究计划+反问(brief 落库 gen_mode=DEEP,StepBrief onMounted 会恢复 CLARIFYING 态)
        // 失败也不必回退页面:详情页深度面板仍可手动点「生成研究计划」重试
        await projectApi.startDeep(id, form.topic.trim(), form.extraInfo || '')
        ElMessage.success('研究计划已生成，请在简报页确认反问信息')
      }
    } catch (e) {
      // 发起失败:已跳详情页,状态/lastBriefError 可见,由用户在页面内重试
    }
  } catch (e) {
    // 仅创建本身失败(网络异常):留在此页;表单校验失败由 rules 提示,不重复弹窗
  } finally { loading.value = false }
}
</script>

<style scoped>
.form-card {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 8px 22px 24px;
  box-shadow: var(--shadow-card);
}
.head-sub { margin: 6px 0 0; font-size: 13px; color: var(--muted); }

/* 分区:编号 + 标题 + 描述,底部细线分隔 */
.form-sec { padding: 20px 0 4px; }
.form-sec + .form-sec { border-top: 1px dashed var(--line); }
.sec-head { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
.sec-index {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: var(--brand-weak);
  color: var(--brand-strong);
  font-family: var(--font-serif);
  font-size: 13px;
  font-weight: 700;
}
.sec-title { display: flex; flex-direction: column; gap: 2px; }
.sec-name { font-family: var(--font-serif); font-size: 16px; font-weight: 700; color: var(--ink); }
.sec-desc { font-size: 12px; color: var(--faint); }

/* 主题主输入:更大、更醒目 */
.topic-item :deep(.el-input__inner) { font-size: 16px; }
.req-mark { color: var(--el-color-danger); font-size: 12px; margin-left: 4px; }

.form-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--line); }
.form-tip { margin: 10px 0 0; font-size: 12px; color: var(--faint); text-align: right; }
@media (max-width: 768px) {
  .form-actions .el-button { flex: 1; }
  .form-tip { text-align: left; }
}
</style>
