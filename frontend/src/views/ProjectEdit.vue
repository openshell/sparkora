<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">New Project</span>
          <h2>新建创作任务</h2>
        </div>
        <div class="actions">
          <el-button text @click="$router.push('/')">← 返回</el-button>
        </div>
      </div>

      <el-form :model="form" :rules="rules" ref="formRef" label-position="top" class="form-card">
        <el-form-item label="主题" prop="topic" required>
          <el-input v-model="form.topic" maxlength="200" show-word-limit
                    placeholder="如：如何选择自部署的国产 AI 模型" />
        </el-form-item>
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
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" placeholder="可选" />
        </el-form-item>

        <div class="form-actions">
          <!-- 主操作唯一:「创建并生成简报」;仅存草稿降为次级按钮,取消为文字按钮 -->
          <el-button text @click="$router.push('/')">取消</el-button>
          <el-button :loading="saving" @click="onSave">仅存草稿</el-button>
          <el-button type="primary" :loading="loading" @click="onSaveAndGenerate">创建并生成简报 →</el-button>
        </div>
        <p class="form-tip">「创建并生成简报」会立即调用 AI 生成创作简报（通常需要 1~2 分钟）。</p>
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
const form = reactive({ topic: '', keywords: '', audience: '', wordCountTarget: 1500, remark: '' })
const rules = { topic: [{ required: true, message: '请输入主题', trigger: 'blur' }] }

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
    router.push(`/projects/${id}`)
  } finally { saving.value = false }
}

const onSaveAndGenerate = async () => {
  let id = null
  loading.value = true
  try {
    id = await doSave()
    const res = await projectApi.generateBrief(id)
    // 后端把生成失败包装为 R.fail 且 HTTP 200,必须检查业务码
    if (res.code === 0) ElMessage.success('已创建，简报生成完成')
    else ElMessage.warning('已创建草稿，但简报生成失败，可在详情页重试')
  } catch (e) {
    // 网络层异常也已建出草稿:进详情页可看 lastBriefError 并重试;
    // 仅表单校验失败(id 为空)时静默,由表单 rules 提示
    if (id) ElMessage.warning('已创建草稿，但简报生成失败，可在详情页重试')
  } finally {
    loading.value = false
    if (id) router.push(`/projects/${id}`)
  }
}
</script>

<style scoped>
.form-card {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 24px 22px;
  box-shadow: var(--shadow-card);
}
.form-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.form-tip { margin: 10px 0 0; font-size: 12px; color: var(--faint); text-align: right; }
@media (max-width: 768px) {
  .form-actions .el-button { flex: 1; }
  .form-tip { text-align: left; }
}
</style>