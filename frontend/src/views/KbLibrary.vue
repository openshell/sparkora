<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">Knowledge Base</span>
          <h2>知识库</h2>
        </div>
        <div class="actions">
          <el-button v-if="user.isEditorOrAbove" type="primary" @click="openCreate">
            <el-icon class="btn-icon"><Plus /></el-icon>新建知识
          </el-button>
        </div>
      </div>

      <div v-if="loading" class="loading"><el-skeleton :rows="3" animated /></div>

      <div v-else-if="error" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">知识库加载失败</div>
        <div class="state-msg">{{ error }}</div>
        <el-button type="primary" plain @click="load">重试</el-button>
      </div>

      <div v-else-if="!rows.length" class="empty">
        <el-empty description="知识库为空。点击右上「新建知识」，录入汽车领域通用知识（如充电桩选择、保养常识），生成时 AI 会检索引用。" />
      </div>

      <div v-else class="kb-grid">
        <el-card v-for="d in rows" :key="d.id" shadow="hover" class="kb-card">
          <div class="d-head">
            <span class="d-title serif">{{ d.title }}</span>
            <el-tag size="small" effect="plain">{{ d.domain }}</el-tag>
            <el-tag v-if="!d.enabled" size="small" type="info" effect="plain">停用</el-tag>
          </div>
          <div class="d-meta">已切块 {{ d.chunkCount }} 块 · 更新于 {{ fmtTime(d.updatedAt) }}</div>
          <div class="d-actions" v-if="user.isEditorOrAbove">
            <el-button size="small" text @click="openEdit(d)">编辑</el-button>
            <el-button size="small" text @click="onRebuild(d)" :loading="rebushing === d.id">重建向量</el-button>
            <el-button size="small" text type="danger" @click="onDel(d)">删除</el-button>
          </div>
        </el-card>
      </div>
    </div>

    <!-- 新建/编辑抽屉 -->
    <el-drawer v-model="editDlg" :title="editingId ? '编辑知识' : '新建知识'" size="90%" style="max-width:640px">
      <el-form label-position="top" :model="form" :rules="rules" ref="formRef">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="200" placeholder="如：家用充电桩选择要点" />
        </el-form-item>
        <el-form-item label="领域标签" prop="domain">
          <el-input v-model="form.domain" maxlength="50" placeholder="通用 / 充电 / 保养 / 政策 / 技术科普…留空为「通用」" />
        </el-form-item>
        <el-form-item label="正文" prop="content">
          <el-input v-model="form.content" type="textarea" :rows="14"
            placeholder="录入知识正文;空行分段,单段过长会自动切分。保存后自动切块并向量化。" />
        </el-form-item>
        <el-form-item v-if="editingId" label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存并向量化</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, WarningFilled } from '@element-plus/icons-vue'
import TopBar from '../layouts/TopBar.vue'
import http from '../api/http'
import { useUserStore } from '../store/user'

const user = useUserStore()
const rows = ref([])
const loading = ref(false)
const error = ref('')
const editDlg = ref(false)
const editingId = ref(null)
const saving = ref(false)
const rebushing = ref(null)
const formRef = ref(null)
const form = ref({ title: '', domain: '', content: '', enabled: true })
const rules = {
  title: [{ required: true, message: '标题不能为空', trigger: 'blur' },
          { max: 200, message: '标题不能超过 200 字', trigger: 'blur' }],
  content: [{ required: true, message: '正文不能为空', trigger: 'blur' }]
}

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const { data } = await http.get('/kb/docs')
    rows.value = data.data || []
  } catch (e) {
    error.value = e?.response?.data?.msg || e.message
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  editingId.value = null
  form.value = { title: '', domain: '', content: '', enabled: true }
  editDlg.value = true
}

const openEdit = (d) => {
  editingId.value = d.id
  http.get(`/kb/docs/${d.id}`).then(({ data }) => {
    const doc = data.data || {}
    form.value = { title: doc.title, domain: doc.domain, content: doc.content, enabled: doc.enabled !== false }
    editDlg.value = true
  }).catch(e => ElMessage.error(e?.response?.data?.msg || '加载详情失败'))
}

const onSave = async () => {
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    if (editingId.value) {
      await http.put(`/kb/docs/${editingId.value}`, form.value)
      ElMessage.success('已更新并向量化')
    } else {
      await http.post('/kb/docs', form.value)
      ElMessage.success('已创建并向量化')
    }
    editDlg.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '保存失败')
  } finally {
    saving.value = false
  }
}

const onDel = (d) => {
  ElMessageBox.confirm(`删除「${d.title}」?其向量块将一并清除。`, '删除知识', { type: 'warning' })
    .then(async () => {
      await http.delete(`/kb/docs/${d.id}`)
      ElMessage.success('已删除')
      await load()
    }).catch(() => {})
}

const onRebuild = async (d) => {
  rebushing.value = d.id
  try {
    const { data } = await http.post(`/kb/docs/${d.id}/rebuild`)
    const st = data.data || {}
    st.failed > 0
      ? ElMessage.warning(`重建完成:成功 ${st.success}/${st.total},失败 ${st.failed}(可重试)`)
      : ElMessage.success(`重建完成:${st.success}/${st.total} 块已向量化`)
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '重建失败')
  } finally {
    rebushing.value = null
  }
}

const fmtTime = (t) => t ? String(t).replace('T', ' ').slice(0, 16) : '—'

onMounted(load)
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 20px; }
.page-kicker { color: var(--faint); font-size: 12px; letter-spacing: .12em; }
.kb-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.d-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.d-title { font-size: 16px; font-weight: 600; }
.d-meta { color: var(--faint); font-size: 12px; margin-top: 8px; }
.d-actions { margin-top: 12px; display: flex; gap: 4px; }
.loading, .empty { padding: 48px 0; }
.state-error { padding: 48px 0; text-align: center; }
.state-title { margin: 10px 0 4px; font-weight: 600; }
.state-msg { color: var(--faint); font-size: 13px; margin-bottom: 14px; }
.btn-icon { margin-right: 4px; }

@media (max-width: 768px) {
  .kb-grid { grid-template-columns: 1fr; }
  .d-actions .el-button { min-height: 44px; } /* 触控目标 ≥44px */
}
</style>