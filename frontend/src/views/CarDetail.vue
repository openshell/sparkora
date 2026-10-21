<template>
  <div>
    <div class="container">
      <div v-if="loading" class="loading"><el-skeleton :rows="6" animated /></div>

      <div v-else-if="error" class="state-error">
        <el-icon :size="36" color="var(--faint)"><WarningFilled /></el-icon>
        <div class="state-title">车型详情加载失败</div>
        <div class="state-msg">{{ error }}</div>
        <el-button type="primary" plain @click="load">重试</el-button>
      </div>

      <template v-else-if="detail">
        <div class="page-header">
          <div>
            <span class="page-kicker">Car Detail</span>
            <h2>{{ detail.model.name }}</h2>
            <div class="sub-line">
              <el-tag size="small" effect="plain">{{ detail.model.salesNetwork || '—' }}</el-tag>
              <el-tag size="small" :type="statusMeta(detail.model.syncStatus).type" effect="light" round>
                {{ statusMeta(detail.model.syncStatus).label }}
              </el-tag>
              <span class="price">{{ formatPrice(detail.model.priceRange) || '价格待同步' }}</span>
              <span class="last-sync">最近成功：{{ fmtTime(detail.model.lastSyncAt) }}</span>
            </div>
          </div>
          <div class="actions">
            <el-button v-if="user.isEditorOrAbove" type="primary" :loading="syncing" @click="onSync">
              <el-icon class="btn-icon"><Refresh /></el-icon>同步
            </el-button>
            <el-button @click="$router.push('/knowledge')">返回</el-button>
          </div>
        </div>

        <!-- 同步失败原因（R3）：完整展示，不截断 -->
        <el-alert v-if="detail.model.lastSyncError" class="sync-error" type="error" :closable="false" show-icon
          title="最近一次同步失败">
          <template #default><span class="sync-error-text">{{ detail.model.lastSyncError }}</span></template>
        </el-alert>

        <!-- Hero 封面（无封面回退详情图首图；均无图则隐藏） -->
        <div v-if="heroUrl" class="hero">
          <el-image :src="heroUrl" fit="contain" class="hero-img" :preview-src-list="galleryUrls"
            preview-teleported hide-on-click-modal />
        </div>

        <!-- 卖点 chips（features 为 JSON 字符串，解析失败静默不渲染） -->
        <div v-if="featureList.length" class="feature-bar">
          <span class="feature-label">卖点</span>
          <el-tag v-for="(f, i) in featureList" :key="i" size="small" effect="plain" class="feature-tag">{{ f }}</el-tag>
        </div>

        <!-- 详情图集：缩略图用原图 URL 预览 -->
        <div v-if="introUrls.length" class="gallery">
          <div class="gallery-title">详情图集（{{ introUrls.length }}）</div>
          <div class="gallery-row">
            <el-image v-for="(u, i) in introUrls" :key="u + i" :src="u" fit="cover" lazy class="gallery-thumb"
              :preview-src-list="introUrls" :initial-index="i" preview-teleported hide-on-click-modal />
          </div>
        </div>

        <!-- 版本选择 -->
        <div v-if="detail.versions.length" class="version-bar">
          <span class="v-label">版本：</span>
          <el-radio-group v-model="curVersion" size="small" @change="onVersionChange">
            <el-radio-button v-for="v in detail.versions" :key="v.id" :value="v.id">
              {{ v.versionName }}
            </el-radio-button>
          </el-radio-group>
        </div>

        <!-- 参数分组表（展示清洗后结构化参数） -->
        <el-collapse v-model="openGroups">
          <el-collapse-item v-for="g in detail.groups" :key="g.group.id" :name="g.group.id">
            <template #title>
              <span class="group-title">{{ g.group.groupName }}</span>
            </template>
            <el-table :data="g.cleans" size="small">
              <el-table-column prop="paramKey" label="参数" min-width="200" />
              <el-table-column label="值" min-width="240">
                <template #default="{ row }">
                  <span class="param-val">{{ cleanValue(row) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="90">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain">{{ typeLabel(row.valueType) }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>

        <!-- 内部问答 -->
        <div class="qa-section">
          <h3>车型问答（RAG）</h3>
          <div class="qa-input">
            <el-input v-model="question" placeholder="如：大唐EV 纯电续航多少？" @keyup.enter="onAskKey" />
            <el-button type="primary" :loading="asking" @click="ask">提问</el-button>
          </div>
          <div v-if="answer" class="qa-answer">
            <div class="qa-answer-text">{{ answer }}</div>
            <div v-if="hits.length" class="qa-refs">
              <div class="ref-title">检索到的知识块：</div>
              <div v-for="(h, i) in hits" :key="i" class="ref-item">
                <div class="ref-score">相关度 {{ h.score.toFixed(3) }}</div>
                <pre class="ref-text">{{ h.chunkText }}</pre>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { carApi } from '../api'
import { useUserStore } from '../store/user'
import { ElMessage } from 'element-plus'
import { Refresh, WarningFilled } from '@element-plus/icons-vue'
import { coverUrlOf, formatPrice, parseFeatures, statusMeta } from '../utils/carDisplay'

const route = useRoute()
const user = useUserStore()
const id = Number(route.params.id)
const loading = ref(false)
const error = ref('')
const detail = ref(null)
const syncing = ref(false)
const curVersion = ref(null)
const openGroups = ref([])
const question = ref('')
const asking = ref(false)
const answer = ref('')
const hits = ref([])

const heroUrl = computed(() => (detail.value ? coverUrlOf(detail.value.model) : ''))
const introUrls = computed(() => {
  const urls = detail.value?.model?.introImageUrls
  return Array.isArray(urls) ? urls.filter(Boolean) : []
})
// 大图预览：封面在前，随后详情图集（去重）
const galleryUrls = computed(() => {
  const m = detail.value?.model
  if (!m) return []
  const list = []
  if (m.coverImageUrl) list.push(m.coverImageUrl)
  introUrls.value.forEach(u => { if (!list.includes(u)) list.push(u) })
  return list
})
const featureList = computed(() => parseFeatures(detail.value?.model?.features))
const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await carApi.detail(id, curVersion.value || undefined)
    detail.value = res.data
    if (detail.value.versions.length && !curVersion.value) curVersion.value = detail.value.versions[0].id
    openGroups.value = detail.value.groups.map(g => g.group.id)
  } catch (e) { error.value = e.response?.data?.msg || e.message || '网络异常，请稍后重试' }
  finally { loading.value = false }
}

// 切换版本时重新加载该版本的清洗参数
const onVersionChange = () => { load() }

// 清洗后参数值展示(类型化渲染)
const cleanValue = (row) => {
  if (row.valueType === 'NUMBER') return row.numericValue != null ? `${row.numericValue}${row.unit || ''}` : (row.paramValue || '—')
  if (row.valueType === 'ENUM') return row.enumValue || row.paramValue || '—'
  if (row.valueType === 'LIST') {
    try { return (JSON.parse(row.listValues) || []).join('、') } catch { return row.paramValue || '—' }
  }
  return row.paramValue || '—'
}
const typeLabel = (t) => ({ STRING: '文本', NUMBER: '数值', BOOLEAN: '布尔', ENUM: '枚举', LIST: '列表' }[t] || t || '—')

const onSync = async () => {
  if (syncing.value) return
  syncing.value = true
  try {
    const res = await carApi.syncOne(id)
    if (res.code === 0) ElMessage.success('同步完成')
    else ElMessage.error(res.msg || '同步失败')
  } catch (e) {
    ElMessage.error(e?.response?.data?.msg || e.message || '同步失败')
  } finally {
    syncing.value = false
    // 成功/失败都刷新：失败原因与状态须在详情页可见（R2/R3）
    await load()
  }
}

// 中文输入法组字中的回车是选词，不是提交（与项目 Enter 约定一致）
const onAskKey = (e) => {
  if (e.isComposing || e.keyCode === 229) return
  ask()
}

const ask = async () => {
  if (!question.value.trim()) { ElMessage.warning('请输入问题'); return }
  asking.value = true
  answer.value = ''
  hits.value = []
  try {
    const res = await carApi.rag(id, question.value, 8)
    if (res.code === 0) {
      hits.value = res.data.hits || []
      answer.value = hits.value.length
        ? `检索到 ${hits.value.length} 个相关参数块，请结合下方知识核对。`
        : '未检索到相关数据，请尝试换个问法。'
    } else ElMessage.error(res.msg || '检索失败')
  } catch (e) { ElMessage.error('检索失败：' + (e.message || e)) }
  finally { asking.value = false }
}

onMounted(load)
</script>

<style scoped>
.btn-icon { margin-right: 2px; }
.sub-line { display: flex; align-items: center; gap: 10px; margin-top: 6px; }
.price { color: var(--brand); font-weight: 600; }
.last-sync { color: var(--faint); font-size: 12px; }
.sync-error { margin-bottom: 14px; }
.sync-error-text { white-space: pre-wrap; word-break: break-all; }

.hero {
  margin: 8px 0 16px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--paper);
  display: flex;
  justify-content: center;
}
.hero-img { width: 100%; max-width: 680px; height: 320px; }
.hero-img :deep(.el-image__inner) { object-fit: contain; }

.feature-bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
.feature-label { font-size: 13px; color: var(--muted); }
.feature-tag { max-width: 100%; }

.gallery { margin-bottom: 16px; }
.gallery-title { font-size: 13px; color: var(--muted); margin-bottom: 8px; }
.gallery-row { display: flex; gap: 8px; flex-wrap: wrap; }
.gallery-thumb {
  width: 112px; height: 84px; cursor: zoom-in;
  border: 1px solid var(--line); border-radius: var(--radius-sm);
  background: var(--paper); padding: 4px;
}

.version-bar { display: flex; align-items: center; gap: 8px; margin: 16px 0; flex-wrap: wrap; }
.v-label { color: var(--muted); font-size: 13px; }
.group-title { font-weight: 600; }
.param-val { white-space: pre-wrap; line-height: 1.6; }
.qa-section { margin-top: 28px; }
.qa-section h3 { margin-bottom: 12px; }
.qa-input { display: flex; gap: 8px; max-width: 560px; }
.qa-answer { margin-top: 16px; }
.qa-answer-text { font-size: 15px; line-height: 1.7; padding: 12px 16px; background: var(--el-fill-color-light); border-radius: var(--radius-sm); }
.qa-refs { margin-top: 12px; }
.ref-title { font-size: 13px; color: var(--muted); margin-bottom: 8px; }
.ref-item { border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px 12px; margin-bottom: 8px; }
.ref-score { font-size: 11px; color: var(--faint); margin-bottom: 4px; }
.ref-text { font-size: 12px; line-height: 1.6; white-space: pre-wrap; margin: 0; color: var(--muted); }
</style>
