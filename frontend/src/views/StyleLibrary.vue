<template>
  <div>
    <TopBar />
    <div class="container">
      <div class="page-header">
        <h2>风格库</h2>
        <el-button v-if="user.isEditorOrAbove" type="primary" @click="openExtract">＋ 从样文提炼</el-button>
      </div>

      <div v-if="loading" class="loading"><el-skeleton :rows="3" /></div>
      <div v-else-if="!rows.length" class="empty">
        <el-empty description="风格库为空。点击右上「从样文提炼」，粘贴一篇代表性文章，AI 会提炼风格画像入库。" />
      </div>
      <div v-else class="style-grid">
        <el-card v-for="s in rows" :key="s.id" shadow="hover" class="style-card">
          <div class="s-head">
            <span class="s-name">{{ s.name }}</span>
            <el-tag v-if="!s.enabled" size="small" type="info">停用</el-tag>
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

    <!-- 提炼对话框 -->
    <el-dialog v-model="extractDlg" title="从样文提炼风格" width="90%" style="max-width:720px">
      <el-form label-position="top">
        <el-form-item label="风格名（可选，留空由 AI 拟）">
          <el-input v-model="extractName" maxlength="64" placeholder="如：硬核技术深度" />
        </el-form-item>
        <el-form-item label="样文（粘贴整篇文章）">
          <el-input v-model="extractText" type="textarea" :rows="12" placeholder="粘贴一篇能代表该风格的公众号文章全文…" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="extractDlg = false">取消</el-button>
        <el-button type="primary" :loading="extracting" @click="onExtract">提炼入库</el-button>
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

const user = useUserStore()
const rows = ref([])
const loading = ref(false)
const extractDlg = ref(false)
const extractName = ref('')
const extractText = ref('')
const extracting = ref(false)
const editDlg = ref(false)
const editing = ref(null)
const saving = ref(false)

const load = async () => {
  loading.value = true
  try { const res = await styleApi.list(); rows.value = res.data || [] }
  finally { loading.value = false }
}
const openExtract = () => { extractName.value = ''; extractText.value = ''; extractDlg.value = true }
const onExtract = async () => {
  if (!extractText.value.trim()) { ElMessage.warning('请粘贴样文'); return }
  extracting.value = true
  try {
    const res = await styleApi.extract(extractName.value, extractText.value)
    if (res.code === 0) { ElMessage.success('已提炼入库'); extractDlg.value = false; await load() }
    else ElMessage.error(res.msg || '提炼失败')
  } catch (e) { ElMessage.error('提炼失败：' + (e.message || e)) }
  finally { extracting.value = false }
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
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; }
.loading, .empty { padding: 40px 0; }
.style-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.style-card .s-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.s-name { font-weight: 600; font-size: 15px; }
.s-desc { color: var(--muted); font-size: 12px; margin-bottom: 8px; }
.s-guide { font-size: 13px; line-height: 1.6; background: var(--el-fill-color-light); padding: 8px; border-radius: 6px; }
.s-actions { margin-top: 8px; text-align: right; }
@media (max-width: 768px) { .style-grid { grid-template-columns: 1fr; } }
</style>
