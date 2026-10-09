<template>
  <div class="source-manage">
    <div class="panel-toolbar">
      <span class="panel-title">信源管理</span>
      <span class="toolbar-hint">信源由后端预置（或经 API 注册）；此处可编辑源级字段、启停、触发采集，并查看各栏目与下次运行时间。</span>
      <el-button class="refresh-btn" :loading="loading" @click="load">
        <el-icon class="btn-icon"><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <div v-if="loading" class="loading"><el-skeleton :rows="4" animated /></div>

    <div v-else-if="error" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">信源加载失败</div>
      <div class="state-msg">{{ error }}</div>
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>

    <div v-else-if="!rows.length" class="empty">
      <el-empty description="暂无信源。信源注册表当前由后端预置，尚未提供新建接口。" />
    </div>

    <el-table v-else :data="rows" size="small" class="source-table" :row-class-name="rowClass">
      <el-table-column label="名称" prop="name" min-width="130" show-overflow-tooltip />
      <el-table-column label="类型" width="72">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.type || 'SITE' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="垂直" width="90">
        <template #default="{ row }">{{ row.vertical || '—' }}</template>
      </el-table-column>
      <el-table-column label="排期" min-width="140">
        <template #default="{ row }">{{ scheduleText(row) }}</template>
      </el-table-column>
      <el-table-column label="下次运行" min-width="120">
        <template #default="{ row }">
          <span v-if="!row.enabled" class="muted">已停用</span>
          <span v-else>{{ nextRunText(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最近采集" min-width="150">
        <template #default="{ row }">
          <template v-if="latestOf(row.id)">
            <el-tag size="small" :type="statusTagType(latestOf(row.id).status)" effect="plain">
              {{ statusText(latestOf(row.id).status) }}
            </el-tag>
            <span class="job-brief">{{ (latestOf(row.id).success || 0) }}/{{ (latestOf(row.id).total || 0) }}</span>
          </template>
          <span v-else class="muted">未采集</span>
        </template>
      </el-table-column>
      <el-table-column label="栏目数" width="72" align="center">
        <template #default="{ row }">{{ row.channelCount != null ? row.channelCount : (row.channels?.length || 0) }}</template>
      </el-table-column>
      <el-table-column label="启停" width="82">
        <template #default="{ row }">
          <el-switch v-if="user.isEditorOrAbove" :model-value="!!row.enabled" :loading="togglingId === row.id"
            @change="(v) => onToggle(row, v)" />
          <el-tag v-else size="small" :type="row.enabled ? 'success' : 'info'" effect="plain">{{ row.enabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="right">
        <template #default="{ row }">
          <el-button size="small" text @click="openEdit(row)">{{ user.isEditorOrAbove ? '编辑' : '查看' }}</el-button>
          <el-button v-if="user.isEditorOrAbove" size="small" text type="primary"
            :loading="collectingSourceId === row.id" @click="onCollect(row, null)">采集</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 源编辑抽屉（含栏目列表） -->
    <el-drawer v-model="editDlg" :title="user.isEditorOrAbove ? '编辑信源' : '信源详情'" size="90%" style="max-width:720px">
      <div v-if="editLoading" class="loading"><el-skeleton :rows="6" animated /></div>
      <template v-else-if="editSource">
        <el-form label-position="top" :model="form" :rules="rules" ref="formRef" :disabled="!user.isEditorOrAbove">
          <el-form-item label="名称" prop="name">
            <el-input v-model="form.name" maxlength="100" placeholder="如：乘联会" />
          </el-form-item>
          <div class="form-row">
            <el-form-item label="类型" prop="type">
              <el-select v-model="form.type" style="width: 160px">
                <el-option label="SITE（列表页）" value="SITE" />
                <el-option label="RSS（订阅源）" value="RSS" />
              </el-select>
            </el-form-item>
            <el-form-item label="垂直领域">
              <el-input v-model="form.vertical" maxlength="30" placeholder="如：汽车 / 政策" style="width: 180px" />
            </el-form-item>
            <el-form-item label="权威分档">
              <el-select v-model="form.authorityTier" clearable placeholder="默认不启用" style="width: 170px">
                <el-option label="official（官方/政务）" value="official" />
                <el-option label="industry（行业媒体）" value="industry" />
                <el-option label="media（媒体）" value="media" />
                <el-option label="ugc（论坛/自媒体）" value="ugc" />
              </el-select>
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="cron（无发布窗口时生效）">
              <el-input v-model="form.cron" maxlength="50" placeholder="如：0 30 3 * * ?" style="width: 220px" />
            </el-form-item>
            <el-form-item label="发布窗口（每月 起始日）">
              <el-input-number v-model="form.windowStartDay" :min="1" :max="31" controls-position="right" style="width: 130px" />
            </el-form-item>
            <el-form-item label="结束日">
              <el-input-number v-model="form.windowEndDay" :min="1" :max="31" controls-position="right" style="width: 130px" />
            </el-form-item>
          </div>
          <div class="form-row switch-row">
            <el-form-item label="启用">
              <el-switch v-model="form.enabled" />
            </el-form-item>
            <el-form-item label="需 Crawl4AI">
              <el-switch v-model="form.needCrawl4ai" />
            </el-form-item>
            <el-form-item label="同步到全部栏目">
              <el-switch v-model="form.applyCrawl4aiToChannels" />
            </el-form-item>
          </div>
        </el-form>

        <div class="channels-head">
          <span class="panel-title">栏目列表</span>
          <span class="toolbar-hint">列表地址/分类/选择器下沉到栏目级；可对单栏目单独触发采集。</span>
        </div>
        <el-table :data="editSource.channels || []" size="small" class="channel-table">
          <el-table-column label="栏目名" prop="name" min-width="120" show-overflow-tooltip />
          <el-table-column label="分类" width="100">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.category || '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="list_url" prop="listUrl" min-width="180" show-overflow-tooltip />
          <el-table-column label="Crawl4AI" width="90" align="center">
            <template #default="{ row }">
              <span class="muted">{{ row.needCrawl4ai == null ? '继承' : (row.needCrawl4ai ? '是' : '否') }}</span>
            </template>
          </el-table-column>
          <el-table-column label="启停" width="72" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled === false ? 'info' : 'success'" effect="plain">
                {{ row.enabled === false ? '停用' : '启用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="right">
            <template #default="{ row }">
              <el-button v-if="user.isEditorOrAbove" size="small" text type="primary"
                :disabled="!editSource.enabled || row.enabled === false"
                :loading="collectingChannelId === row.id" @click="onCollect(editSource, row.id)">采集</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template #footer>
        <el-button @click="editDlg = false">取消</el-button>
        <el-button v-if="user.isEditorOrAbove" type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, WarningFilled } from '@element-plus/icons-vue'
import { sourceApi, sourceJobApi } from '../../api'
import { useUserStore } from '../../store/user'

const emit = defineEmits(['collected'])
const user = useUserStore()

const rows = ref([])
const jobs = ref([])
const loading = ref(false)
const error = ref('')
const togglingId = ref(null)
const collectingSourceId = ref(null)
const collectingChannelId = ref(null)

// 编辑抽屉
const editDlg = ref(false)
const editLoading = ref(false)
const editSource = ref(null)
const saving = ref(false)
const formRef = ref(null)
const form = ref(emptyForm())
const rules = {
  name: [{ max: 100, message: '名称不能超过 100 字', trigger: 'blur' }],
  type: [{ required: true, message: '类型不能为空', trigger: 'change' }]
}

function emptyForm() {
  return {
    name: '', type: 'SITE', vertical: '', cron: '',
    windowStartDay: null, windowEndDay: null, authorityTier: '',
    needCrawl4ai: false, enabled: true, applyCrawl4aiToChannels: false
  }
}

// 任务列表按 id 倒序 → 每个源首次出现即最近一次采集
const latestOf = (sourceId) => jobs.value.find(j => j.sourceId === sourceId) || null

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const [sRes, jRes] = await Promise.all([sourceApi.list(), sourceJobApi.list()])
    rows.value = sRes.data || []
    jobs.value = jRes.data || []
  } catch (e) {
    error.value = e?.response?.data?.msg || e.message || '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}

const rowClass = ({ row }) => (row.enabled ? '' : 'source-row-off')

const scheduleText = (row) => {
  if (row.windowStartDay != null && row.windowEndDay != null) {
    return `每月 ${row.windowStartDay}-${row.windowEndDay} 日`
  }
  return row.cron || '未排期'
}

// G6/R11：计划可视化——后端计算的 nextRunAt（含发布窗口语义）；ISO 串 MM-dd HH:mm
const nextRunText = (row) => {
  if (row.nextRunAt == null) return '—'
  const s = String(row.nextRunAt).replace('T', ' ')
  // "yyyy-MM-dd HH:mm[:ss]" → "MM-dd HH:mm"
  const m = s.match(/^\d{4}-(\d{2}-\d{2}) (\d{2}:\d{2})/)
  return m ? `${m[1]} ${m[2]}` : s
}

const statusText = (s) => ({ RUNNING: '采集中', SUCCESS: '成功', PARTIAL: '部分成功', FAILED: '失败' }[s] || s)
const statusTagType = (s) => ({ RUNNING: 'primary', SUCCESS: 'success', PARTIAL: 'warning', FAILED: 'danger' }[s] || 'info')

const openEdit = async (row) => {
  editDlg.value = true
  editLoading.value = true
  editSource.value = null
  try {
    const res = await sourceApi.get(row.id)
    editSource.value = res.data || null
    const s = editSource.value || {}
    form.value = {
      name: s.name || '',
      type: s.type || 'SITE',
      vertical: s.vertical || '',
      cron: s.cron || '',
      windowStartDay: s.windowStartDay ?? null,
      windowEndDay: s.windowEndDay ?? null,
      authorityTier: s.authorityTier || '',
      needCrawl4ai: !!s.needCrawl4ai,
      enabled: s.enabled !== false,
      applyCrawl4aiToChannels: false
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '加载信源详情失败')
    editDlg.value = false
  } finally {
    editLoading.value = false
  }
}

const onSave = async () => {
  if (!editSource.value) return
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    const payload = {
      // 后端为「部分更新」:null=不改;置空须传空串(否则清空字段被静默忽略)
      name: form.value.name ?? '',
      type: form.value.type || null,
      vertical: form.value.vertical ?? '',
      cron: form.value.cron ?? '',
      windowStartDay: form.value.windowStartDay ?? null,
      windowEndDay: form.value.windowEndDay ?? null,
      authorityTier: form.value.authorityTier ?? '',
      needCrawl4ai: form.value.needCrawl4ai,
      enabled: form.value.enabled,
      applyCrawl4aiToChannels: form.value.applyCrawl4aiToChannels
    }
    const res = await sourceApi.update(editSource.value.id, payload)
    if (res.code === 0) {
      editSource.value = res.data || editSource.value
      ElMessage.success('已保存')
      await load()
    } else {
      ElMessage.error(res.msg || '保存失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '保存失败')
  } finally {
    saving.value = false
  }
}

// 启停：乐观更新 + 失败回滚（读源级 enabled，走同一 PUT 部分更新）
const onToggle = async (row, val) => {
  togglingId.value = row.id
  const prev = !!row.enabled
  row.enabled = val
  try {
    const res = await sourceApi.update(row.id, { enabled: val })
    if (res.code === 0) ElMessage.success(val ? '已启用' : '已停用')
    else { row.enabled = prev; ElMessage.error(res.msg || '操作失败') }
  } catch (e) {
    row.enabled = prev
    ElMessage.error(e?.response?.data?.msg || '操作失败')
  } finally {
    togglingId.value = null
  }
}

// 手动触发采集（channelId 为空 = 整源）；成功后通知父级启动任务轮询
const onCollect = async (source, channelId) => {
  if (!source) return
  if (channelId == null) collectingSourceId.value = source.id
  else collectingChannelId.value = channelId
  try {
    const res = await sourceApi.collect(source.id, channelId)
    if (res.code === 0 && res.data?.jobId != null) {
      ElMessage.success('已触发采集，正在监控任务进度')
      emit('collected', res.data.jobId)
      await load()
    } else {
      ElMessage.error(res.msg || '触发采集失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '触发采集失败')
  } finally {
    collectingSourceId.value = null
    collectingChannelId.value = null
  }
}

onMounted(load)
defineExpose({ load })
</script>

<style scoped>
.source-manage { margin-bottom: var(--sp-6); }
.panel-toolbar { display: flex; align-items: center; gap: var(--sp-4); margin-bottom: var(--sp-4); flex-wrap: wrap; }
.panel-title { font-size: var(--fs-16); font-weight: 600; }
.toolbar-hint { color: var(--faint); font-size: var(--fs-12); }
.refresh-btn { margin-left: auto; }
.btn-icon { margin-right: 4px; }
.source-table { width: 100%; }
.channel-table { width: 100%; }
.muted { color: var(--faint); font-size: var(--fs-12); }
.job-brief { margin-left: var(--sp-3); color: var(--muted); font-size: var(--fs-12); }
.loading, .empty { padding: 40px 0; }
.state-error { padding: 40px 0; text-align: center; }
.state-title { margin: 10px 0 4px; font-weight: 600; }
.state-msg { color: var(--faint); font-size: var(--fs-13); margin-bottom: 14px; }

.form-row { display: flex; gap: var(--sp-6); flex-wrap: wrap; }
.form-row :deep(.el-form-item) { margin-bottom: var(--sp-4); }
.switch-row :deep(.el-form-item__label) { margin-bottom: 0; }

.channels-head { display: flex; align-items: baseline; gap: var(--sp-4); margin: var(--sp-5) 0 var(--sp-3); flex-wrap: wrap; }
:deep(.source-row-off) { color: var(--faint); }
</style>
