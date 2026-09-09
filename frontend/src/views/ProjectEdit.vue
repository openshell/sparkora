<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">New Project</span>
          <h2>新建创作任务</h2>
          <p class="head-sub">设定主题与素材，AI 将据此生成创作简报</p>
        </div>
        <div class="actions">
          <el-button text @click="$router.push('/')">← 返回</el-button>
        </div>
      </div>

      <el-form :model="form" :rules="rules" ref="formRef" label-position="top" class="form-card">
        <!-- 区块一:创作主题(主输入,突出) -->
        <section class="form-sec">
          <div class="sec-head">
            <span class="sec-index">01</span>
            <div class="sec-title">
              <div class="sec-name">创作主题</div>
              <div class="sec-desc">一句话说清要写什么，这是简报与正文的锚点</div>
            </div>
          </div>
          <el-form-item prop="topic" class="topic-item">
            <el-input v-model="form.topic" maxlength="200" show-word-limit size="large"
                      placeholder="如：如何选择自部署的国产 AI 模型" />
          </el-form-item>
          <!-- 2026-09-09 模式收敛(09-09-brief-gen-redesign R2):取消快速/深度双选,所有生成必走深度流程(研究+反问) -->
          <el-form-item class="depth-item">
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
          <el-button type="primary" :loading="loading" @click="onSaveAndGenerate">创建并生成简报 →</el-button>
        </div>
        <p class="form-tip">「创建并生成简报」会立即进入详情页并调用 AI 生成创作简报（通常需要 1~2 分钟）；深度模式先生成研究计划并进入反问环节。</p>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { projectApi } from '../api'
import { ElMessage } from 'element-plus'
import TopBar from '../layouts/TopBar.vue'

const router = useRouter()
const formRef = ref()
const loading = ref(false)   // 创建并生成
const saving = ref(false)    // 仅存草稿
const form = reactive({ topic: '', keywords: '', audience: '', wordCountTarget: 1500, remark: '', extraInfo: '' })
const rules = {
  topic: [{ required: true, message: '请输入主题', trigger: 'blur' }],
}

// 2026-09-09:车型锚点选择入口已移除(创作不与车型绑定);不再加载车型列表

const doSave = async () => {
  await formRef.value.validate()
  const res = await projectApi.create(form)
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
const onSaveAndGenerate = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    const id = await doSave()
    router.push(`/projects/${id}?gen=deep`)
    try {
      // 深度流程:发起研究计划+反问(brief 落库 gen_mode=DEEP,StepBrief onMounted 会恢复 CLARIFYING 态)
      // 失败也不必回退页面:详情页深度面板仍可手动点「生成研究计划」重试
      await projectApi.startDeep(id, form.topic.trim(), form.extraInfo || '')
      ElMessage.success('研究计划已生成，请在简报页确认反问信息')
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

.form-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--line); }
.form-tip { margin: 10px 0 0; font-size: 12px; color: var(--faint); text-align: right; }
@media (max-width: 768px) {
  .form-actions .el-button { flex: 1; }
  .form-tip { text-align: left; }
}
</style>
