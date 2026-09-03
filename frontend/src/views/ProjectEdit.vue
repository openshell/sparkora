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
              <div class="sec-desc">关联车型知识库、补充个人见解，让内容有据可依</div>
            </div>
          </div>
          <el-form-item label="关联车型（可选，可多选）">
            <el-select v-model="form.carModelIds" multiple filterable clearable collapse-tags
                       placeholder="选择车型，生成时注入其知识库参数" style="width:100%">
              <el-option v-for="m in carModels" :key="m.id" :label="m.name" :value="m.id" />
            </el-select>
            <div class="form-tip" style="text-align:left;margin-top:4px">
              关联后，生成简报/正文时会检索这些车型的知识库，注入权威参数作为事实约束。未选择时，AI 会自动判断文章是否与车型相关并回填。
            </div>
          </el-form-item>
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
        <p class="form-tip">「创建并生成简报」会立即调用 AI 生成创作简报（通常需要 1~2 分钟）。</p>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { projectApi, carApi } from '../api'
import { ElMessage } from 'element-plus'
import TopBar from '../layouts/TopBar.vue'

const router = useRouter()
const formRef = ref()
const loading = ref(false)   // 创建并生成
const saving = ref(false)    // 仅存草稿
const carModels = ref([])    // S6:车型知识库列表(可选关联)
const form = reactive({ topic: '', keywords: '', audience: '', wordCountTarget: 1500, remark: '', carModelIds: [], extraInfo: '' })
const rules = { topic: [{ required: true, message: '请输入主题', trigger: 'blur' }] }

onMounted(async () => {
  try { const res = await carApi.list(); carModels.value = res.data || [] } catch { carModels.value = [] }
})

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
