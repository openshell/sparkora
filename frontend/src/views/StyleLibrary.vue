<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <div>
          <span class="page-kicker">Style Library</span>
          <h2>风格库</h2>
        </div>
        <div class="actions" v-if="user.isEditorOrAbove">
          <el-button type="primary" @click="openCreate(false)">
            <el-icon class="btn-icon"><Plus /></el-icon>新增风格
          </el-button>
          <el-button @click="openCreate(true)">
            <el-icon class="btn-icon"><MagicStick /></el-icon>从样文提炼
          </el-button>
        </div>
      </div>

      <div v-if="loading" class="loading"><el-skeleton :rows="3" animated /></div>

      <!-- 加载失败:与空态区分,可重试 -->
      <div v-else-if="error" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">风格库加载失败</div>
        <div class="state-msg">{{ error }}</div>
        <el-button type="primary" plain @click="load">重试</el-button>
      </div>

      <div v-else-if="!rows.length" class="empty">
        <el-empty description="风格库为空。点击右上「新增风格」手填，或「从样文提炼」粘贴一篇代表性文章由 AI 预填。" />
      </div>

      <div v-else class="style-grid">
        <el-card v-for="s in rows" :key="s.id" shadow="hover" class="style-card">
          <div class="s-head">
            <span class="s-name serif">{{ s.name }}</span>
            <el-tag v-if="!s.enabled" size="small" type="info" effect="plain">停用</el-tag>
          </div>
          <div class="s-desc">{{ s.description || '无描述' }}</div>
          <div class="s-guide">{{ s.toneGuidance }}</div>
          <div class="s-actions" v-if="user.isEditorOrAbove">
            <el-button size="small" text @click="openEdit(s)">编辑</el-button>
            <el-button v-if="user.role === 'ADMIN'" size="small" text type="danger" @click="onDel(s)">删除</el-button>
          </div>
        </el-card>
      </div>
    </div>

    <!-- 新增/提炼统一对话框(两步式:提炼预填不入库,人工修改后保存入库) -->
    <el-dialog v-model="createDlg" title="新增风格" width="90%" style="max-width:720px">
      <el-form ref="createFormRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="风格名" prop="name">
          <el-input v-model="form.name" maxlength="64" placeholder="如：硬核技术深度" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" maxlength="500" placeholder="一句话描述这种风格的特点(20-40字)" />
        </el-form-item>
        <el-form-item label="语气指令（toneGuidance，给生成模型用）">
          <el-input v-model="form.toneGuidance" type="textarea" :rows="4" placeholder="语气/句式/结构/用词偏好,可直接作为 system prompt 片段" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>

        <!-- 提炼区(可折叠):粘贴样文 AI 提炼预填,不入库 -->
        <el-collapse v-model="extractOpen" class="extract-collapse">
          <el-collapse-item name="extract" title="从样文提炼（可选：AI 提炼预填表单）">
            <el-form-item label="风格名（可选，留空由 AI 拟）">
              <el-input v-model="extractName" maxlength="64" placeholder="留空由 AI 拟名" />
            </el-form-item>
            <el-form-item label="样文（粘贴整篇文章）">
              <el-input v-model="extractText" type="textarea" :rows="12" placeholder="粘贴一篇能代表该风格的公众号文章全文…" />
            </el-form-item>
            <el-button type="primary" plain :loading="extracting" @click="onExtractPreview">AI 提炼预填</el-button>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="createDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onCreate">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编辑对话框 -->
    <el-dialog v-model="editDlg" title="编辑风格" width="90%" style="max-width:640px">
      <el-form label-position="top" v-if="editing">
        <el-form-item label="风格名"><el-input v-model="editing.name" maxlength="64" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="editing.description" maxlength="500" /></el-form-item>
        <el-form-item label="语气指令（toneGuidance，给生成模型用）">
          <el-input v-model="editing.toneGuidance" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="editing.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { styleApi } from '../api'
import { useUserStore } from '../store/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import TopBar from '../layouts/TopBar.vue'
import { MagicStick, Plus, WarningFilled } from '@element-plus/icons-vue'

const user = useUserStore()
const rows = ref([])
const loading = ref(false)
const error = ref('')
const createDlg = ref(false)
const createFormRef = ref(null)
// 新增表单(name/description/toneGuidance/enabled + 提炼暂存 sourceExcerpt)
const form = ref({ name: '', description: '', toneGuidance: '', enabled: true })
const rules = {
  name: [{ required: true, message: '风格名必填', trigger: 'blur' }]
}
// 提炼区状态:折叠面板(默认收起,「从样文提炼」入口自动展开)+ 样文输入
const extractOpen = ref([])
const extractName = ref('')
const extractText = ref('')
const extracting = ref(false)
// AI 提炼返回的 sourceExcerpt 暂存(非表单输入项,保存时随 create 提交;纯手工新增为空)
const pendingSourceExcerpt = ref('')
const editDlg = ref(false)
const editing = ref(null)
const saving = ref(false)

const load = async () => {
  loading.value = true
  error.value = ''
  try { const res = await styleApi.list(); rows.value = res.data || [] }
  catch (e) { error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试' }
  finally { loading.value = false }
}
// 统一新增入口:fromExtract=true(「从样文提炼」)自动展开提炼区
const openCreate = (fromExtract) => {
  form.value = { name: '', description: '', toneGuidance: '', enabled: true }
  extractName.value = ''
  extractText.value = ''
  pendingSourceExcerpt.value = ''
  extractOpen.value = fromExtract ? ['extract'] : []
  createDlg.value = true
}
// 两步式提炼:仅预览不入库,结果回填表单,人工修改后保存
const onExtractPreview = async () => {
  if (!extractText.value.trim()) { ElMessage.warning('请粘贴样文'); return }
  extracting.value = true
  try {
    const res = await styleApi.extractPreview(extractName.value, extractText.value)
    if (res.code === 0 && res.data) {
      form.value.name = res.data.name || form.value.name
      form.value.description = res.data.description || form.value.description
      form.value.toneGuidance = res.data.toneGuidance || form.value.toneGuidance
      pendingSourceExcerpt.value = res.data.sourceExcerpt || ''
      ElMessage.success('已提炼,请检查修改后保存')
    } else {
      ElMessage.error(res.msg || '提炼失败')
    }
  } catch (e) {
    ElMessage.error('提炼失败：' + (e.response?.data?.msg || e.message || e))
  } finally { extracting.value = false }
}
const onCreate = async () => {
  try { await createFormRef.value?.validate() } catch { return }
  saving.value = true
  try {
    const body = { ...form.value, sourceExcerpt: pendingSourceExcerpt.value || '' }
    const res = await styleApi.create(body)
    if (res.code === 0) { ElMessage.success('已保存'); createDlg.value = false; await load() }
    else ElMessage.error(res.msg || '保存失败')
  } catch (e) { ElMessage.error('保存失败：' + (e.message || e)) }
  finally { saving.value = false }
}
const openEdit = (s) => { editing.value = { ...s }; editDlg.value = true }
const onSave = async () => {
  saving.value = true
  try {
    const res = await styleApi.update(editing.value.id, editing.value)
    if (res.code === 0) { ElMessage.success('已保存'); editDlg.value = false; await load() }
    else ElMessage.error(res.msg || '保存失败')
  } catch (e) { ElMessage.error('保存失败：' + (e.message || e)) }
  finally { saving.value = false }
}
const onDel = async (s) => {
  await ElMessageBox.confirm(`确认删除风格「${s.name}」？`, '删除', { type: 'warning' })
  await styleApi.remove(s.id)
  ElMessage.success('已删除'); await load()
}
onMounted(load)
</script>

<style scoped>
.btn-icon { margin-right: 2px; }
.style-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
.style-card { display: flex; flex-direction: column; }
.s-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.s-name { font-weight: 700; font-size: 16px; }
.s-desc { color: var(--muted); font-size: 12px; margin-bottom: 10px; line-height: 1.6; }
.s-guide { font-size: 13px; line-height: 1.7; background: var(--el-fill-color-light); padding: 10px; border-radius: var(--radius-sm); flex: 1; }
.s-actions { margin-top: 10px; text-align: right; }
.extract-collapse { margin-bottom: 14px; border-top: 1px solid var(--el-border-color-lighter); }
@media (max-width: 768px) { .style-grid { grid-template-columns: 1fr; } }
</style>