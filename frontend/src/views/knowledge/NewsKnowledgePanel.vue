<template>
  <div class="news-panel">
    <!-- 工具栏：搜索 + 同步入口 -->
    <div class="panel-toolbar">
      <el-input v-model="keyword" placeholder="搜索新闻标题" clearable class="search" @keyup.enter="onSearch" @clear="onSearch">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button @click="onSearch">搜索</el-button>
      <template v-if="user.isEditorOrAbove">
        <el-select v-model="jobType" class="job-type" placeholder="同步方式">
          <el-option label="增量同步" value="INCREMENT" />
          <el-option label="全量同步" value="FULL" />
        </el-select>
        <el-button type="primary" class="sync-btn" :loading="creating" @click="onSync">
          <el-icon class="btn-icon"><Refresh /></el-icon>同步新闻
        </el-button>
      </template>
    </div>

    <!-- 同步进度面板 -->
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
        已处理 {{ (job.success || 0) + (job.failed || 0) }} / {{ job.total || 0 }} 条新闻
      </div>
    </div>

    <div v-if="loading" class="loading"><el-skeleton :rows="5" animated /></div>

    <div v-else-if="error" class="state-error">
      <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
      <div class="state-title">新闻加载失败</div>
      <div class="state-msg">{{ error }}</div>
      <el-button type="primary" plain @click="load">重试</el-button>
    </div>

    <div v-else-if="!rows.length" class="empty">
      <el-empty description="新闻库为空。点击右上「同步新闻」，从比亚迪官网拉取新闻入库。" />
    </div>

    <template v-else>
      <div class="news-list">
        <div v-for="row in rows" :key="row.id" class="news-card" @click="openDetail(row)">
          <div class="thumb">
            <el-image v-if="coverOf(row)" :src="coverOf(row)" fit="cover" lazy />
            <div v-else class="thumb-ph"><el-icon :size="22"><Document /></el-icon></div>
          </div>
          <div class="card-body">
            <div class="n-title serif">{{ row.title }}</div>
            <!-- 主题标签（09-15 img-classify）：点击跳图库按「主题/<名>」筛选，不触发卡片详情 -->
            <div class="n-themes" v-if="row.themes && row.themes.length">
              <el-tag v-for="t in row.themes" :key="t" size="small" type="warning" effect="plain" round
                      class="theme-tag" @click.stop="gotoImageLibrary(t)">主题/{{ t }}</el-tag>
            </div>
            <div class="n-tags" v-if="parseTags(row.tagNames).length">
              <el-tag v-for="(t, i) in parseTags(row.tagNames).slice(0, 3)" :key="i" size="small" effect="plain" round>{{ t }}</el-tag>
            </div>
            <div class="n-meta">
              <span>{{ fmtDate(row.publishDate) }}</span>
              <span v-if="row.chunkCount != null">· {{ row.chunkCount }} 块</span>
            </div>
          </div>
        </div>
      </div>

      <el-pagination
        class="pager"
        layout="prev, pager, next"
        background
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="onPageChange" />
    </template>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailDlg" title="新闻详情" size="90%" style="max-width:680px">
      <div v-if="detailLoading" class="loading"><el-skeleton :rows="6" animated /></div>
      <div v-else-if="detail">
        <h3 class="d-title serif">{{ detail.title }}</h3>
        <div class="d-meta">
          <span>{{ fmtDate(detail.publishDate) }}</span>
          <span v-if="detail.chunkCount != null">· 已切块 {{ detail.chunkCount }} 块</span>
        </div>
        <div class="d-tags" v-if="parseTags(detail.tagNames).length">
          <el-tag v-for="(t, i) in parseTags(detail.tagNames)" :key="i" size="small" effect="plain" round>{{ t }}</el-tag>
        </div>
        <div class="d-tags" v-if="detail.themes && detail.themes.length">
          <el-tag v-for="t in detail.themes" :key="t" size="small" type="warning" effect="plain" round
                  class="theme-tag" @click="gotoImageLibrary(t)">主题/{{ t }}</el-tag>
        </div>
        <el-image v-if="coverOf(detail)" class="d-cover" :src="coverOf(detail)" fit="cover" />
        <div v-if="detail.content" class="d-content">{{ detail.content }}</div>
        <div v-else class="d-empty">该新闻为图片型内容，请查看官方原文。</div>
        <div class="d-actions">
          <el-button type="primary" plain @click="openSource(detail)">查看官方原文</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { newsApi } from '../../api'
import { useUserStore } from '../../store/user'
import { ElMessage } from 'element-plus'
import { Search, Refresh, WarningFilled, Document, Loading, CircleCheck } from '@element-plus/icons-vue'

const user = useUserStore()
const router = useRouter()

const rows = ref([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const page = ref(1)
const size = 12
const total = ref(0)

// 详情抽屉
const detailDlg = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

// 同步任务
const jobType = ref('INCREMENT')
const creating = ref(false)
const job = ref(null)
const jobId = ref(null)
let pollTimer = null

const BYD_ORIGIN = 'https://www.byd.com'

// 封面/原文为相对路径时补官网域名；绝对 URL 原样返回
const resolveUrl = (u) => {
  if (!u) return ''
  return /^https?:\/\//i.test(u) ? u : BYD_ORIGIN + u
}
// 语义别名：封面图解析（列表/抽屉共用同一规则）
const resolveImageUrl = resolveUrl
/** 封面优先取图库公网 URL（09-15 img-classify：coverImageUrl 非空时优先），未同步封面回退官网 imageUrl */
const coverOf = (row) => (row && row.coverImageUrl) ? row.coverImageUrl : resolveImageUrl(row && row.imageUrl)

/** 点主题标签 → 跳图库并按「主题/<名>」筛选（09-15 img-classify） */
const gotoImageLibrary = (theme) => {
  router.push({ name: 'images', query: { tag: `主题/${theme}` } })
}

// tagNames 是 JSON 数组字符串，解析失败静默降级为空数组
const parseTags = (s) => {
  if (!s) return []
  try {
    const arr = JSON.parse(s)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
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
    const res = await newsApi.list({ page: page.value < 1 ? 1 : page.value, size, keyword: keyword.value.trim() || undefined })
    const pr = res.data || {}
    rows.value = pr.rows || []
    total.value = pr.total || 0
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试'
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
    const res = await newsApi.get(row.id)
    detail.value = res.data || null
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || '加载详情失败')
    detailDlg.value = false
  } finally {
    detailLoading.value = false
  }
}

const openSource = (row) => {
  window.open(resolveUrl(row.url), '_blank', 'noopener')
}

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
  } catch (e) { /* 轮询失败静默，下次重试 */ }
}

const stopPoll = () => { if (pollTimer) { clearInterval(pollTimer); pollTimer = null } }

onMounted(load)
onBeforeUnmount(stopPoll)
</script>

<style scoped>
.panel-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.search { width: 240px; }
.job-type { width: 130px; }
.sync-btn { margin-left: auto; }
.btn-icon { margin-right: 4px; }

/* 列表：移动单列 / 桌面两列 */
.news-list { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14px; }
.news-card {
  display: flex;
  gap: 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow .2s;
}
.news-card:hover { box-shadow: var(--shadow-hover); }
.thumb { flex: 0 0 120px; background: var(--paper); }
.thumb :deep(.el-image) { width: 100%; height: 100%; }
.thumb-ph { width: 100%; height: 100%; min-height: 88px; display: flex; align-items: center; justify-content: center; color: var(--faint); }
.card-body { padding: 10px 12px 10px 0; min-width: 0; }
.n-title { font-weight: 700; font-size: 15px; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.n-tags { display: flex; gap: 4px; flex-wrap: wrap; margin: 6px 0; }
/* 主题标签（09-15 img-classify）：可点击跳图库筛选 */
.n-themes { display: flex; gap: 4px; flex-wrap: wrap; margin: 6px 0; }
.theme-tag { cursor: pointer; }
.n-meta { color: var(--faint); font-size: 12px; display: flex; gap: 6px; }

/* 详情抽屉 */
.d-title { margin: 0 0 6px; font-size: 20px; line-height: 1.4; }
.d-meta { color: var(--faint); font-size: 12px; display: flex; gap: 6px; }
.d-tags { display: flex; gap: 4px; flex-wrap: wrap; margin: 10px 0; }
.d-cover { width: 100%; border-radius: var(--radius-sm); margin: 6px 0 12px; }
.d-content { white-space: pre-wrap; line-height: 1.8; font-size: 15px; color: var(--ink); }
.d-empty { color: var(--muted); font-size: 14px; padding: 20px 0; }
.d-actions { margin-top: 18px; }

/* 进度面板 */
.job-panel {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px 16px;
  margin-bottom: 16px;
  background: var(--card);
}
.job-panel.success { border-color: color-mix(in srgb, var(--ok) 40%, var(--line)); }
.job-panel.partial, .job-panel.failed { border-color: color-mix(in srgb, var(--warn) 40%, var(--line)); }
.job-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.job-title { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.job-count { color: var(--muted); font-size: 13px; }
.job-progress-text { font-size: 12px; color: var(--muted); margin-top: 6px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 768px) {
  .search { width: 100%; }
  .job-type { width: 100%; }
  .sync-btn { width: 100%; margin-left: 0; }
  .news-list { grid-template-columns: 1fr; }
  /* 可点主题标签触控目标 ≥44px（移动端） */
  .theme-tag { min-height: 44px; padding: 0 12px; display: inline-flex; align-items: center; }
}
</style>
