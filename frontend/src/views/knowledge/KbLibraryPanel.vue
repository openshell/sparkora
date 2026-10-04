<template>
  <div class="kb-panel">
    <div class="panel-toolbar">
      <el-button v-if="user.isEditorOrAbove" @click="openBatch">
        <el-icon class="btn-icon"><Upload /></el-icon>批量导入
      </el-button>
      <el-button v-if="user.isEditorOrAbove" type="primary" @click="openCreate">
        <el-icon class="btn-icon"><Plus /></el-icon>新建知识
      </el-button>
    </div>

    <div v-if="loading" class="loading"><el-skeleton :rows="3" animated /></div>

    <div v-else-if="error" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">知识库加载失败</div>
      <div class="state-msg">{{ error }}</div>
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>

    <div v-else-if="!rows.length" class="empty">
      <el-empty description="知识库为空。点击「新建知识」，录入汽车领域通用知识（如充电桩选择、保养常识），生成时 AI 会检索引用。" />
    </div>

    <div v-else class="kb-grid">
      <el-card v-for="d in rows" :key="d.id" shadow="hover" class="kb-card">
        <div class="d-head">
          <span class="d-title serif">{{ d.title }}</span>
          <el-tag size="small" effect="plain">{{ d.domain }}</el-tag>
          <el-tag v-for="t in (d.tags || [])" :key="t" size="small" type="info" effect="plain">{{ t }}</el-tag>
          <el-tag v-if="!d.enabled" size="small" type="info" effect="plain">停用</el-tag>
        </div>
        <div class="d-meta">已切块 {{ d.chunkCount }} 块 · 更新于 {{ fmtTime(d.updatedAt) }}</div>
        <div v-if="d.source || effText(d)" class="d-extra">{{ d.source ? '来源：' + d.source : '' }}<span v-if="d.source && effText(d)"> · </span>{{ effText(d) }}</div>
        <div class="d-actions" v-if="user.isEditorOrAbove">
          <el-button size="small" text @click="openEdit(d)">编辑</el-button>
          <el-button size="small" text @click="onRebuild(d)" :loading="rebushing === d.id">重建向量</el-button>
          <el-button size="small" text type="danger" @click="onDel(d)">删除</el-button>
        </div>
      </el-card>
    </div>

    <!-- 批量导入对话框（CSV / JSON / Markdown） -->
    <el-dialog v-model="batchDlg" title="批量导入知识" width="680px" :close-on-click-modal="false">
      <div class="batch-tip">
        支持 <b>CSV</b> / <b>JSON</b> / <b>Markdown</b> 三种格式，按文件后缀自动识别（也可手动指定）。
        逐条导入，单条失败不影响其余；同「标题 + 领域」已存在或批内重复将自动跳过。
      </div>
      <el-form label-position="top">
        <el-form-item label="文件">
          <input class="batch-file" type="file" ref="batchFileInput"
            accept=".csv,.json,.md,.markdown,text/*"
            @change="onPickFile" :disabled="batching" />
        </el-form-item>
        <el-form-item label="格式">
          <el-select v-model="batchFormat" placeholder="按文件自动判定" clearable style="width: 220px"
            :disabled="batching">
            <el-option label="CSV" value="csv" />
            <el-option label="JSON" value="json" />
            <el-option label="Markdown" value="markdown" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-collapse class="batch-help">
        <el-collapse-item title="字段与样例模板">
          <div class="batch-help-body">
            <p>字段与单条录入一致：<code>title</code>（必填，≤200）、<code>domain</code>（受控词表，空为「通用」）、
              <code>content</code>（必填，≤50000）、<code>source</code>（≤200）、<code>tags</code>（≤50/个）、
              <code>effectiveFrom</code> / <code>effectiveTo</code>（yyyy-MM-dd，可空）。单批最多 200 条。</p>
            <p><b>CSV</b> 首行表头，<code>tags</code> 用 <code>;</code> 分隔：</p>
            <pre class="batch-sample">title,domain,content,source,tags,effectiveFrom,effectiveTo
家用充电桩选择,充电,看车型支持的功率与物业条件,官网,充电;安装,2026-01-01,</pre>
            <p><b>JSON</b> 对象数组（<code>tags</code> 可数组或 <code>;</code> 字符串）：</p>
            <pre class="batch-sample">[{"title":"保养周期","domain":"保养","content":"首保 5000 公里。","tags":["保养","首保"]}]</pre>
            <p><b>Markdown</b> 以一级标题 <code># </code> 分段，每段一篇；无 H1 则整文件一篇（标题取文件名）。</p>
          </div>
        </el-collapse-item>
      </el-collapse>

      <!-- 结果面板 -->
      <div v-if="batchResult" class="batch-result">
        <div class="batch-sum">
          共 {{ batchResult.total }} 条 · 成功 <b class="ok">{{ batchResult.success }}</b> ·
          失败 <b class="bad">{{ batchResult.failed }}</b>
        </div>
        <el-table :data="batchResult.results" size="small" max-height="280"
          :row-class-name="batchRowClass">
          <el-table-column label="序号" width="64">
            <template #default="{ row }">{{ row.index + 1 }}</template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.success ? 'success' : 'danger'" effect="plain">
                {{ row.success ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="error" label="说明" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.error || '—' }}</template>
          </el-table-column>
        </el-table>
      </div>

      <template #footer>
        <el-button @click="batchDlg = false" :disabled="batching">关闭</el-button>
        <el-button type="primary" :loading="batching" :disabled="!batchFile" @click="onBatchImport">
          开始导入
        </el-button>
      </template>
    </el-dialog>

    <!-- 新建/编辑抽屉 -->
    <el-drawer v-model="editDlg" :title="editingId ? '编辑知识' : '新建知识'" size="90%" style="max-width:640px">
      <el-form label-position="top" :model="form" :rules="rules" ref="formRef">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="200" placeholder="如：家用充电桩选择要点" />
        </el-form-item>
        <el-form-item label="领域标签" prop="domain">
          <el-select v-model="form.domain" placeholder="留空为「通用」" style="width: 100%" clearable>
            <el-option v-for="d in domains" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源">
          <el-input v-model="form.source" maxlength="200" placeholder="可选;URL / 出处 / 署名" />
        </el-form-item>
        <el-form-item label="标签">
          <el-select v-model="form.tags" multiple filterable allow-create default-first-option
            placeholder="可选;输入后回车新建标签(≤50 字)" style="width: 100%" />
        </el-form-item>
        <el-form-item label="生效期">
          <el-date-picker v-model="form.effectiveRange" type="daterange" value-format="YYYY-MM-DD"
            start-placeholder="生效起" end-placeholder="生效止" style="width: 100%" />
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
import { Plus, Upload, WarningFilled } from '@element-plus/icons-vue'
import { kbApi } from '../../api'
import { useUserStore } from '../../store/user'

const user = useUserStore()
const rows = ref([])
const loading = ref(false)
const error = ref('')
const editDlg = ref(false)
const editingId = ref(null)
const saving = ref(false)
const rebushing = ref(null)
const formRef = ref(null)
// 批量导入（10-03 C）
const batchDlg = ref(false)
const batchFile = ref(null)
const batchFileInput = ref(null)
const batchFormat = ref('')
const batching = ref(false)
const batchResult = ref(null)
// 领域受控词表（来自后端 GET /api/kb/domains，与 com.sparkora.kb.KbDomain 同源）
const domains = ref([])
const emptyForm = () => ({ title: '', domain: '', source: '', tags: [], effectiveRange: [], content: '', enabled: true })
const form = ref(emptyForm())
const rules = {
  title: [{ required: true, message: '标题不能为空', trigger: 'blur' },
          { max: 200, message: '标题不能超过 200 字', trigger: 'blur' }],
  content: [{ required: true, message: '正文不能为空', trigger: 'blur' }]
}

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await kbApi.list()
    rows.value = res.data || []
  } catch (e) {
    error.value = e?.response?.data?.msg || e.message
  } finally {
    loading.value = false
  }
}

const loadDomains = async () => {
  try {
    const res = await kbApi.domains()
    domains.value = res.data || []
  } catch { /* 词表加载失败不阻断列表；下拉为空时后端仍会校验非法值 */ }
}

const openCreate = () => {
  editingId.value = null
  form.value = emptyForm()
  editDlg.value = true
}

const openEdit = (d) => {
  editingId.value = d.id
  kbApi.get(d.id).then((res) => {
    const doc = res.data || {}
    form.value = {
      title: doc.title, domain: doc.domain, content: doc.content, enabled: doc.enabled !== false,
      source: doc.source || '', tags: doc.tags || [],
      effectiveRange: (doc.effectiveFrom || doc.effectiveTo)
        ? [doc.effectiveFrom || null, doc.effectiveTo || null] : []
    }
    editDlg.value = true
  }).catch(e => ElMessage.error(e?.response?.data?.msg || '加载详情失败'))
}

// 生效期展示：「生效 起 ~ 止」，单边为空用「不限」
const effText = (d) => {
  if (!d.effectiveFrom && !d.effectiveTo) return ''
  return `生效 ${d.effectiveFrom || '不限'} ~ ${d.effectiveTo || '不限'}`
}

const onSave = async () => {
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    const payload = {
      title: form.value.title,
      domain: form.value.domain || null,
      source: form.value.source || null,
      tags: form.value.tags || [],
      effectiveFrom: form.value.effectiveRange?.[0] || null,
      effectiveTo: form.value.effectiveRange?.[1] || null,
      content: form.value.content,
      enabled: form.value.enabled
    }
    if (editingId.value) {
      await kbApi.update(editingId.value, payload)
      ElMessage.success('已更新并向量化')
    } else {
      await kbApi.create(payload)
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

// ==================== 批量导入（10-03 C） ====================

const openBatch = () => {
  batchFile.value = null
  batchFormat.value = ''
  batchResult.value = null
  // el-dialog 默认不销毁内容；原生 file input 会残留上次选择，重置以免重选同名文件不触发 change
  if (batchFileInput.value) batchFileInput.value.value = ''
  batchDlg.value = true
}

const onPickFile = (e) => {
  batchFile.value = e.target.files?.[0] || null
  batchResult.value = null
}

const batchRowClass = ({ row }) => (row.success ? '' : 'batch-row-fail')

const onBatchImport = async () => {
  if (!batchFile.value) return
  if (batching.value) return            // 非幂等提交：同步重入守卫，置位早于首个 await
  batching.value = true
  try {
    const res = await kbApi.batchImport(batchFile.value, batchFormat.value || undefined)
    // 业务失败为 HTTP 200 + R.fail(code,msg)，axios 不 reject，必须显式检查 code
    if (res.code !== 0) {
      ElMessage.error(res.msg || '批量导入失败')
      return
    }
    const data = res.data || {}
    batchResult.value = data
    if (data.failed > 0) {
      ElMessage.warning(`导入完成：成功 ${data.success}/${data.total}，失败 ${data.failed}（详见结果面板）`)
    } else {
      ElMessage.success(`导入完成：${data.success}/${data.total} 条已创建并向量化`)
    }
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '批量导入失败')
  } finally {
    batching.value = false
  }
}

const onDel = (d) => {
  ElMessageBox.confirm(`删除「${d.title}」?其向量块将一并清除。`, '删除知识', { type: 'warning' })
    .then(async () => {
      await kbApi.remove(d.id)
      ElMessage.success('已删除')
      await load()
    }).catch(() => {})
}

const onRebuild = async (d) => {
  rebushing.value = d.id
  try {
    const res = await kbApi.rebuild(d.id)
    const st = res.data || {}
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

onMounted(() => { load(); loadDomains() })
</script>

<style scoped>
.panel-toolbar { display: flex; align-items: center; justify-content: flex-end; margin-bottom: 16px; }
.kb-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.d-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.d-title { font-size: 16px; font-weight: 600; }
.d-meta { color: var(--faint); font-size: 12px; margin-top: 8px; }
.d-extra { color: var(--faint); font-size: 12px; margin-top: 4px; }
.d-actions { margin-top: 12px; display: flex; gap: 4px; }
.loading, .empty { padding: 48px 0; }
.state-error { padding: 48px 0; text-align: center; }
.state-title { margin: 10px 0 4px; font-weight: 600; }
.state-msg { color: var(--faint); font-size: 13px; margin-bottom: 14px; }
.btn-icon { margin-right: 4px; }

/* 批量导入对话框 */
.batch-tip { color: var(--faint); font-size: var(--fs-13); line-height: 1.7; margin-bottom: 14px; }
.batch-file { font-size: var(--fs-13); }
.batch-help { margin: 4px 0 12px; }
.batch-help-body { font-size: var(--fs-13); line-height: 1.8; color: var(--ink); }
.batch-help-body code { background: var(--n-50); padding: 1px 4px; border-radius: var(--radius-xs); font-size: var(--fs-12); }
.batch-sample { background: var(--n-50); padding: 8px 10px; border-radius: var(--radius-sm);
  font-size: var(--fs-12); line-height: 1.6; overflow-x: auto; white-space: pre; margin: 6px 0; }
.batch-result { margin-top: 8px; }
.batch-sum { font-size: var(--fs-13); margin-bottom: 8px; }
.batch-sum .ok { color: var(--el-color-success); }
.batch-sum .bad { color: var(--el-color-danger); }
:deep(.batch-row-fail) { color: var(--el-color-danger); }
:deep(.batch-row-fail td) { color: var(--el-color-danger); }
</style>
