<template>
  <div class="source-content">
    <div class="panel-toolbar">
      <span class="panel-title">信源内容</span>
      <el-input v-model="keyword" placeholder="搜索标题" clearable class="search" @keyup.enter="onSearch" @clear="onSearch">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="categoryFilter" clearable placeholder="全部分类" class="cat-filter" @change="onSearch">
        <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-model="sourceFilter" clearable placeholder="全部来源" class="src-filter" @change="onSearch">
        <el-option v-for="s in sourceOptions" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>
      <el-button @click="onSearch">搜索</el-button>
      <!-- BYD 专属：同步入口（其余信源走「信源管理」的手动采集） -->
      <template v-if="user.isEditorOrAbove && bydFilterActive">
        <el-select v-model="jobType" class="job-type" placeholder="同步方式">
          <el-option label="增量同步" value="INCREMENT" />
          <el-option label="全量同步" value="FULL" />
        </el-select>
        <el-button type="primary" class="sync-btn" :loading="creating" @click="onSync">
          <el-icon class="btn-icon"><Refresh /></el-icon>同步新闻
        </el-button>
      </template>
    </div>

    <!-- BYD 同步进度面板（原「新闻」Tab 能力保留） -->
    <div v-if="job" class="job-panel" :class="jobClass">
      <div class="job-head">
        <span class="job-title">
          <el-icon class="spin" v-if="job.status === 'RUNNING'"><Loading /></el-icon>
          <el-icon v-else><CircleCheck /></el-icon>
          {{ jobTitle }}
        </span>
        <span class="job-count">{{ job.success }} 成功 · {{ job.failed }} 失败</span>
      </div>
      <el-progress v-if="job.status === 'RUNNING'" :percentage="progress" :stroke-width="8" :show-text="false" />
      <div v-if="job.status === 'RUNNING'" class="job-progress-text">
        已处理 {{ (job.success || 0) + (job.failed || 0) }} / {{ job.total || 0 }} 条
      </div>
    </div>

    <div v-if="loading" class="loading"><el-skeleton :rows="5" animated /></div>

    <div v-else-if="error" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">内容加载失败</div>
      <div class="state-msg">{{ error }}</div>
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>

    <div v-else-if="!rows.length" class="empty">
      <el-empty description="暂无信源内容。可在「信源管理」触发采集，或对 BYD 来源同步新闻。" />
    </div>

    <template v-else>
      <div class="content-list">
        <div v-for="row in rows" :key="row.id" class="content-card" @click="openDetail(row)">
          <div class="card-body">
            <div class="c-title serif">{{ row.title }}</div>
            <div class="c-tags">
              <el-tag size="small" effect="plain">{{ row.category || '未分类' }}</el-tag>
              <el-tag size="small" :type="isByd(row) ? 'success' : 'info'" effect="plain">
                {{ isByd(row) ? 'BYD 官方新闻' : sourceName(row.sourceId) }}
              </el-tag>
            </div>
            <div class="c-meta">
              <span>{{ fmtDate(row.publishDate) }}</span>
              <span v-if="row.chunkCount != null">· {{ row.chunkCount }} 块</span>
            </div>
          </div>
        </div>
      </div>

      <el-pagination class="pager" layout="prev, pager, next" background
        :total="total" :current-page="page" :page-size="size" @current-change="onPageChange" />
    </template>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailDlg" title="内容详情" size="90%" style="max-width:720px">
      <div v-if="detailLoading" class="loading"><el-skeleton :rows="6" animated /></div>
      <div v-else-if="detail">
        <h3 class="d-title serif">{{ detail.title }}</h3>
        <div class="d-meta">
          <el-tag size="small" effect="plain">{{ detail.category || '未分类' }}</el-tag>
          <el-tag size="small" :type="isByd(detail) ? 'success' : 'info'" effect="plain">
            {{ isByd(detail) ? 'BYD 官方新闻' : sourceName(detail.sourceId) }}
          </el-tag>
          <span>{{ fmtDate(detail.publishDate) }}</span>
          <span v-if="detail.chunkCount != null">· 已切块 {{ detail.chunkCount }} 块</span>
        </div>
        <!-- BYD 新闻专属：封面 / 主题标签 / 原始标签（经 newsApi.get 补充，SourceContentDTO 不含这些字段） -->
        <el-image v-if="coverOf(detail)" class="d-cover" :src="coverOf(detail)" fit="cover" />
        <div class="d-tags" v-if="detail.themes && detail.themes.length">
          <el-tag v-for="t in detail.themes" :key="t" size="small" type="warning" effect="plain" round
                  class="theme-tag" @click="gotoImageLibrary(t)">主题/{{ t }}</el-tag>
        </div>
        <div class="d-tags" v-if="parseTags(detail.tagNames).length">
          <el-tag v-for="(t, i) in parseTags(detail.tagNames)" :key="i" size="small" effect="plain" round>{{ t }}</el-tag>
        </div>
        <!-- 正文用 pre-wrap：B 已把表格转成保留行列的行文本，换行不可压平 -->
        <div v-if="detail.content" class="d-content">{{ detail.content }}</div>
        <div v-else class="d-empty">
          {{ isByd(detail) ? '该新闻为图片型内容，请查看官方原文。' : '该内容无正文，请查看原文。' }}
        </div>
        <div class="d-actions">
          <el-button v-if="detail.url" type="primary" plain @click="openSource(detail)">
            {{ isByd(detail) ? '查看官方原文' : '查看原文' }}
          </el-button>
          <!-- 通用信源内容向量重建（非 BYD；BYD 走新闻域自身链路） -->
          <el-button v-if="user.isEditorOrAbove && !isByd(detail)" :loading="rebuilding" @click="onRebuild(detail)">
            重建向量
          </el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Refresh, WarningFilled, Loading, CircleCheck } from '@element-plus/icons-vue'
import { sourceApi, newsApi } from '../../api'
import { useUserStore } from '../../store/user'

const props = defineProps({
  sourceOptions: { type: Array, default: () => [] }
})

const user = useUserStore()
const router = useRouter()

const CATEGORIES = ['官方新闻', '销量数据', '投诉榜', '政策公示', '行业资讯']
const BYD_ORIGIN = 'https://www.byd.com'

const rows = ref([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const categoryFilter = ref(null)
const sourceFilter = ref(null)
const page = ref(1)
const size = 12
const total = ref(0)

const detailDlg = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const rebuilding = ref(false)

// BYD 同步任务（原 NewsKnowledgePanel 能力）
const jobType = ref('INCREMENT')
const creating = ref(false)
const job = ref(null)
const jobId = ref(null)
let pollTimer = null

// BYD 来源判定：后端 DTO 返回 source='byd-news'（兼容旧值 'byd'）
const isByd = (row) => row && (row.source === 'byd-news' || row.source === 'byd')
// 仅在「未按 sourceId 过滤」或「选中了 BYD 源」时暴露同步入口；无法确定 BYD 源 id 时按未过滤显示
const bydFilterActive = computed(() => sourceFilter.value == null)
const sourceName = (id) => props.sourceOptions.find(s => s.id === id)?.name || (id != null ? `#${id}` : '未关联')

const resolveUrl = (row) => {
  const u = row && row.url
  if (!u) return ''
  if (/^https?:\/\//i.test(u)) return u
  return isByd(row) ? BYD_ORIGIN + u : u
}

/** BYD 封面：优先图库公网 URL（newsApi 详情补充），回退官网 imageUrl（相对路径按 BYD 域解析） */
const coverOf = (row) => {
  if (!row) return ''
  if (row.coverImageUrl) return row.coverImageUrl
  const u = row.imageUrl
  if (!u) return ''
  return /^https?:\/\//i.test(u) ? u : (isByd(row) ? BYD_ORIGIN + u : u)
}

// tagNames 为 JSON 数组字符串，解析失败静默降级为空数组
const parseTags = (s) => {
  if (!s) return []
  try {
    const arr = JSON.parse(s)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

/** 点主题标签 → 跳图库按「主题/<名>」筛选（BYD 新闻能力保留） */
const gotoImageLibrary = (theme) => {
  router.push({ name: 'images', query: { tag: `主题/${theme}` } })
}

const fmtDate = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

const progress = computed(() => {
  if (!job.value || !job.value.total) return 0
  return Math.round((((job.value.success || 0) + (job.value.failed || 0)) / job.value.total) * 100)
})
const jobTitle = computed(() => {
  if (!job.value) return ''
  return { RUNNING: '同步进行中…', SUCCESS: '同步完成', PARTIAL: '部分完成', FAILED: '同步失败' }[job.value.status] || '同步'
})
const jobClass = computed(() => job.value?.status?.toLowerCase() || '')

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await sourceApi.contentList({
      page: page.value < 1 ? 1 : page.value,
      size,
      keyword: keyword.value.trim() || undefined,
      category: categoryFilter.value || undefined,
      sourceId: sourceFilter.value == null ? undefined : sourceFilter.value
    })
    const pr = res.data || {}
    rows.value = pr.rows || []
    total.value = pr.total || 0
  } catch (e) {
    error.value = e?.response?.data?.msg || e.message || '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}

const onSearch = () => { page.value = 1; load() }
const onPageChange = (p) => { page.value = p < 1 ? 1 : p; load() }

const openDetail = async (row) => {
  detailDlg.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const res = await sourceApi.contentGet(row.id)
    let d = res.data || null
    // BYD 新闻：SourceContentDTO 不含封面/主题/标签，经既有 newsApi.get 补充，保零回归
    if (d && isByd(row)) {
      try {
        const nres = await newsApi.get(row.id)
        if (nres.code === 0 && nres.data) d = { ...d, ...nres.data }
      } catch { /* 补充失败不阻断正文展示 */ }
    }
    detail.value = d
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '加载详情失败')
    detailDlg.value = false
  } finally {
    detailLoading.value = false
  }
}

const openSource = (row) => {
  const u = resolveUrl(row)
  if (u) window.open(u, '_blank', 'noopener')
}

const onRebuild = async (row) => {
  rebuilding.value = true
  try {
    const res = await sourceApi.rebuildContent(row.id)
    if (res.code === 0) {
      const st = res.data || {}
      st.failed > 0
        ? ElMessage.warning(`重建完成：成功 ${st.success}/${st.total}，失败 ${st.failed}`)
        : ElMessage.success(`重建完成：${st.success}/${st.total} 块已向量化`)
      const refreshed = await sourceApi.contentGet(row.id)
      if (refreshed.code === 0) detail.value = refreshed.data
      await load()
    } else {
      ElMessage.error(res.msg || '重建失败')
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '重建失败')
  } finally {
    rebuilding.value = false
  }
}

// ==================== BYD 同步（原「新闻」Tab 能力） ====================

const onSync = async () => {
  creating.value = true
  try {
    const res = await newsApi.createJob(jobType.value)
    if (res.code === 0) {
      jobId.value = res.data.jobId
      job.value = null
      startPoll()
    } else {
      ElMessage.error(res.msg || '创建任务失败')
    }
  } catch (e) {
    ElMessage.error('创建任务失败：' + (e.message || e))
  } finally {
    creating.value = false
  }
}

const startPoll = () => {
  stopPoll()
  pollTimer = setInterval(poll, 2000)
  poll()
}

const poll = async () => {
  if (!jobId.value) return
  try {
    const res = await newsApi.getJob(jobId.value)
    if (res.code === 0) {
      job.value = res.data
      if (res.data.status !== 'RUNNING') {
        stopPoll()
        if (res.data.status === 'SUCCESS') ElMessage.success(`同步完成，入库 ${res.data.success} 条新闻`)
        else if (res.data.failed > 0) ElMessage.warning(`同步完成，${res.data.success} 成功 / ${res.data.failed} 失败`)
        await load()
      }
    }
  } catch { /* 轮询失败静默，下次重试 */ }
}

const stopPoll = () => { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } }

onMounted(load)
onBeforeUnmount(stopPoll)
</script>

<style scoped>
.panel-toolbar { display: flex; align-items: center; gap: var(--sp-4); margin-bottom: var(--sp-4); flex-wrap: wrap; }
.panel-title { font-size: var(--fs-16); font-weight: 600; }
.search { width: 200px; }
.cat-filter, .src-filter { width: 150px; }
.job-type { width: 130px; }
.sync-btn { margin-left: auto; }
.btn-icon { margin-right: 4px; }

.content-list { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--sp-5); }
.content-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  padding: var(--sp-5) var(--sp-6);
  cursor: pointer;
  transition: box-shadow .2s;
}
.content-card:hover { box-shadow: var(--shadow-hover); }
.c-title { font-weight: 700; font-size: var(--fs-16); line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.c-tags { display: flex; gap: var(--sp-2); flex-wrap: wrap; margin: var(--sp-3) 0; }
.c-meta { color: var(--faint); font-size: var(--fs-12); display: flex; gap: var(--sp-3); }
.pager { margin-top: var(--sp-6); }

/* 详情抽屉 */
.d-title { margin: 0 0 6px; font-size: var(--fs-18); line-height: 1.4; }
.d-meta { color: var(--faint); font-size: var(--fs-12); display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }
.d-cover { width: 100%; border-radius: var(--radius-sm); margin: var(--sp-4) 0; }
.d-tags { display: flex; gap: var(--sp-2); flex-wrap: wrap; margin: var(--sp-3) 0; }
.theme-tag { cursor: pointer; }
.d-content { white-space: pre-wrap; line-height: 1.8; font-size: var(--fs-14); color: var(--ink); margin-top: var(--sp-5); }
.d-empty { color: var(--muted); font-size: var(--fs-14); padding: 20px 0; }
.d-actions { margin-top: 18px; display: flex; gap: var(--sp-4); }

/* 同步进度面板 */
.job-panel { border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; margin-bottom: 16px; background: var(--card); }
.job-panel.success { border-color: color-mix(in srgb, var(--ok) 40%, var(--line)); }
.job-panel.partial, .job-panel.failed { border-color: color-mix(in srgb, var(--warn) 40%, var(--line)); }
.job-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.job-title { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.job-count { color: var(--muted); font-size: var(--fs-13); }
.job-progress-text { font-size: var(--fs-12); color: var(--muted); margin-top: 6px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.loading, .empty { padding: 48px 0; }
.state-error { padding: 48px 0; text-align: center; }
.state-title { margin: 10px 0 4px; font-weight: 600; }
.state-msg { color: var(--faint); font-size: var(--fs-13); margin-bottom: 14px; }
</style>
