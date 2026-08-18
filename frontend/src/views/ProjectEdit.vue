<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <h2>新建创作任务</h2>
        <el-button text @click="$router.push('/')">← 返回</el-button>
      </div>

      <el-form :model="form" :rules="rules" ref="formRef" label-position="top" class="form-card">
        <el-form-item label="主题 *" prop="topic">
          <el-input v-model="form.topic" maxlength="200" placeholder="如：如何选择自部署的国产 AI 模型" />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="form.keywords" maxlength="500" placeholder="逗号分隔" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="12">
            <el-form-item label="目标读者">
              <el-input v-model="form.audience" maxlength="200" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="目标字数">
              <el-input-number v-model="form.wordCountTarget" :min="100" :max="10000" :step="100" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>

        <div class="form-actions">
          <el-button @click="$router.push('/')">取消</el-button>
          <el-button type="primary" :loading="loading" @click="onSave">保存（草稿）</el-button>
          <el-button type="success" :loading="loading" @click="onSaveAndGenerate">创建并生成 Brief →</el-button>
        </div>
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
const loading = ref(false)
const form = reactive({ topic: '', keywords: '', audience: '', wordCountTarget: 1500, remark: '' })
const rules = { topic: [{ required: true, message: '请输入主题', trigger: 'blur' }] }

const doSave = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await projectApi.create(form)
    return res.data
  } finally { loading.value = false }
}

const onSave = async () => {
  const id = await doSave()
  ElMessage.success('已保存为草稿')
  router.push(`/projects/${id}`)
}

const onSaveAndGenerate = async () => {
  const id = await doSave()
  await projectApi.generateBrief(id)
  ElMessage.success('已创建，状态：就绪')
  router.push(`/projects/${id}`)
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; }
.form-card { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 20px; }
.form-actions { display: flex; gap: 8px; flex-wrap: wrap; }
@media (max-width: 768px) { .form-actions .el-button { flex: 1; } }
</style>
